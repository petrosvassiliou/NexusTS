#include "rdp_session_context.hpp"

#include <algorithm>
#include <cstring>
#include <filesystem>
#include <unordered_set>
#include <sys/stat.h>

#ifdef __ANDROID__
#include <aaudio/AAudio.h>
#endif

namespace nexusts::rdp {

namespace {

constexpr uint32_t kClipFdAttributes = 0x00000004u;
constexpr uint32_t kClipFdWriteTime  = 0x00000020u;
constexpr uint32_t kClipFdFileSize   = 0x00000040u;
constexpr uint32_t kClipFdProgressUi = 0x00004000u;

constexpr uint32_t kWinFileAttrDirectory = 0x00000010u;
constexpr uint32_t kWinFileAttrNormal    = 0x00000080u;

constexpr size_t kFileDescriptorWBytes = 592u;
constexpr size_t kMaxCliprdrFileEntries = 2048u;
constexpr uint64_t kUnixToWinFiletimeSecOffset = 11644473600ULL;
constexpr uint64_t kWinFiletimeTicksPerSec = 10000000ULL;

inline void write_u16_le(uint8_t* dst, uint16_t v) noexcept {
    dst[0] = static_cast<uint8_t>(v & 0xFFu);
    dst[1] = static_cast<uint8_t>((v >> 8) & 0xFFu);
}

inline void write_u32_le(uint8_t* dst, uint32_t v) noexcept {
    dst[0] = static_cast<uint8_t>(v & 0xFFu);
    dst[1] = static_cast<uint8_t>((v >> 8) & 0xFFu);
    dst[2] = static_cast<uint8_t>((v >> 16) & 0xFFu);
    dst[3] = static_cast<uint8_t>((v >> 24) & 0xFFu);
}

inline void write_u64_le(uint8_t* dst, uint64_t v) noexcept {
    write_u32_le(dst, static_cast<uint32_t>(v & 0xFFFFFFFFULL));
    write_u32_le(dst + 4, static_cast<uint32_t>((v >> 32) & 0xFFFFFFFFULL));
}

inline uint16_t read_u16_le(const uint8_t* src) noexcept {
    return static_cast<uint16_t>(src[0]) |
           (static_cast<uint16_t>(src[1]) << 8);
}

inline uint32_t read_u32_le(const uint8_t* src) noexcept {
    return static_cast<uint32_t>(src[0]) |
           (static_cast<uint32_t>(src[1]) << 8) |
           (static_cast<uint32_t>(src[2]) << 16) |
           (static_cast<uint32_t>(src[3]) << 24);
}

inline uint64_t read_u64_le(const uint8_t* src) noexcept {
    return static_cast<uint64_t>(read_u32_le(src)) |
           (static_cast<uint64_t>(read_u32_le(src + 4)) << 32);
}

std::vector<uint16_t> utf8_to_utf16_units(const std::string& utf8) {
    std::vector<uint16_t> out;
    out.reserve(utf8.size());
    size_t i = 0u;
    while (i < utf8.size()) {
        const auto c0 = static_cast<uint8_t>(utf8[i]);
        uint32_t cp = 0xFFFDu;
        size_t len = 1u;
        if (c0 < 0x80u) {
            cp = c0;
        } else if ((c0 & 0xE0u) == 0xC0u && i + 1u < utf8.size()) {
            const auto c1 = static_cast<uint8_t>(utf8[i + 1u]);
            if ((c1 & 0xC0u) == 0x80u) {
                const uint32_t cand = ((c0 & 0x1Fu) << 6) | (c1 & 0x3Fu);
                if (cand >= 0x80u) {
                    cp = cand;
                    len = 2u;
                }
            }
        } else if ((c0 & 0xF0u) == 0xE0u && i + 2u < utf8.size()) {
            const auto c1 = static_cast<uint8_t>(utf8[i + 1u]);
            const auto c2 = static_cast<uint8_t>(utf8[i + 2u]);
            if ((c1 & 0xC0u) == 0x80u && (c2 & 0xC0u) == 0x80u) {
                const uint32_t cand = ((c0 & 0x0Fu) << 12) | ((c1 & 0x3Fu) << 6) | (c2 & 0x3Fu);
                if (cand >= 0x800u && (cand < 0xD800u || cand > 0xDFFFu)) {
                    cp = cand;
                    len = 3u;
                }
            }
        } else if ((c0 & 0xF8u) == 0xF0u && i + 3u < utf8.size()) {
            const auto c1 = static_cast<uint8_t>(utf8[i + 1u]);
            const auto c2 = static_cast<uint8_t>(utf8[i + 2u]);
            const auto c3 = static_cast<uint8_t>(utf8[i + 3u]);
            if ((c1 & 0xC0u) == 0x80u && (c2 & 0xC0u) == 0x80u && (c3 & 0xC0u) == 0x80u) {
                const uint32_t cand =
                    ((c0 & 0x07u) << 18) | ((c1 & 0x3Fu) << 12) | ((c2 & 0x3Fu) << 6) | (c3 & 0x3Fu);
                if (cand >= 0x10000u && cand <= 0x10FFFFu) {
                    cp = cand;
                    len = 4u;
                }
            }
        }
        i += len;
        if (cp <= 0xFFFFu) {
            out.push_back(static_cast<uint16_t>(cp));
        } else {
            const uint32_t adj = cp - 0x10000u;
            out.push_back(static_cast<uint16_t>(0xD800u + (adj >> 10)));
            out.push_back(static_cast<uint16_t>(0xDC00u + (adj & 0x3FFu)));
        }
    }
    return out;
}

void append_utf8_codepoint(std::string& dst, uint32_t cp) {
    if (cp <= 0x7Fu) {
        dst.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FFu) {
        dst.push_back(static_cast<char>(0xC0u | (cp >> 6)));
        dst.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0xFFFFu) {
        dst.push_back(static_cast<char>(0xE0u | (cp >> 12)));
        dst.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        dst.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0x10FFFFu) {
        dst.push_back(static_cast<char>(0xF0u | (cp >> 18)));
        dst.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
        dst.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        dst.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    }
}

std::string utf16le_fixed_to_utf8(const uint8_t* src, size_t max_wchars) {
    std::string out;
    size_t i = 0u;
    while (i < max_wchars) {
        const uint16_t w0 = read_u16_le(src + i * 2u);
        if (w0 == 0u) {
            break;
        }
        if (w0 >= 0xD800u && w0 <= 0xDBFFu && i + 1u < max_wchars) {
            const uint16_t w1 = read_u16_le(src + (i + 1u) * 2u);
            if (w1 >= 0xDC00u && w1 <= 0xDFFFu) {
                const uint32_t cp =
                    0x10000u + ((static_cast<uint32_t>(w0 - 0xD800u) << 10) |
                                static_cast<uint32_t>(w1 - 0xDC00u));
                append_utf8_codepoint(out, cp);
                i += 2u;
                continue;
            }
        }
        if (w0 >= 0xD800u && w0 <= 0xDFFFu) {
            append_utf8_codepoint(out, 0xFFFDu);
        } else {
            append_utf8_codepoint(out, w0);
        }
        ++i;
    }
    return out;
}

uint64_t unix_time_to_win_filetime(time_t t) noexcept {
    if (t <= 0) {
        return (1704067200ULL + kUnixToWinFiletimeSecOffset) * kWinFiletimeTicksPerSec;
    }
    return (static_cast<uint64_t>(t) + kUnixToWinFiletimeSecOffset) * kWinFiletimeTicksPerSec;
}

std::string sanitize_win_component(const std::string& raw) {
    std::string out;
    out.reserve(raw.size());
    for (char ch : raw) {
        const auto uc = static_cast<unsigned char>(ch);
        if (uc < 0x20u || ch == '\\' || ch == '/' || ch == ':' || ch == '*' ||
            ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|') {
            out.push_back('_');
        } else {
            out.push_back(ch);
        }
    }
    while (!out.empty() && (out.back() == ' ' || out.back() == '.')) {
        out.pop_back();
    }
    if (out.empty() || out == "." || out == "..") {
        return "item";
    }
    return out;
}

void collect_directory_entries_recursive(
    const std::filesystem::path& dir_path,
    const std::string& win_prefix,
    std::vector<RdpClipboardFileEntry>& out
) {
    if (out.size() >= kMaxCliprdrFileEntries) {
        return;
    }
    std::error_code ec;
    std::vector<std::filesystem::path> children;
    for (std::filesystem::directory_iterator it(dir_path, ec); !ec && it != std::filesystem::directory_iterator(); it.increment(ec)) {
        const std::string name = it->path().filename().string();
        if (name.empty() || name == "." || name == ".." || name == ".DS_Store") {
            continue;
        }
        children.push_back(it->path());
    }
    std::sort(children.begin(), children.end());

    for (const auto& child : children) {
        if (out.size() >= kMaxCliprdrFileEntries) {
            break;
        }
        struct stat st {};
        const std::string child_str = child.string();
        if (::lstat(child_str.c_str(), &st) != 0) {
            continue;
        }
        if (S_ISLNK(st.st_mode)) {
            continue;
        }
        const std::string clean_name = sanitize_win_component(child.filename().string());
        const std::string rel_win = win_prefix + "\\" + clean_name;
        if (utf8_to_utf16_units(rel_win).size() >= 260u) {
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            RdpClipboardFileEntry dir_entry{};
            dir_entry.relative_win_path = rel_win;
            dir_entry.local_posix_path = child_str;
            dir_entry.file_size = 0u;
            dir_entry.last_write_filetime = unix_time_to_win_filetime(st.st_mtime);
            dir_entry.is_directory = true;
            dir_entry.has_known_size = true;
            out.push_back(std::move(dir_entry));
            collect_directory_entries_recursive(child, rel_win, out);
        } else if (S_ISREG(st.st_mode)) {
            RdpClipboardFileEntry file_entry{};
            file_entry.relative_win_path = rel_win;
            file_entry.local_posix_path = child_str;
            file_entry.file_size = st.st_size > 0 ? static_cast<uint64_t>(st.st_size) : 0u;
            file_entry.last_write_filetime = unix_time_to_win_filetime(st.st_mtime);
            file_entry.is_directory = false;
            file_entry.has_known_size = true;
            out.push_back(std::move(file_entry));
        }
    }
}

} // namespace

std::vector<uint8_t> serialize_cliprdr_file_list(const std::vector<RdpClipboardFileEntry>& entries) {
    const size_t count = std::min<size_t>(entries.size(), kMaxCliprdrFileEntries);
    std::vector<uint8_t> buf(4u + count * kFileDescriptorWBytes, 0u);
    write_u32_le(buf.data(), static_cast<uint32_t>(count));

    for (size_t i = 0u; i < count; ++i) {
        const auto& e = entries[i];
        uint8_t* rec = buf.data() + 4u + i * kFileDescriptorWBytes;

        uint32_t flags = kClipFdAttributes | kClipFdProgressUi;
        if (e.last_write_filetime != 0u) {
            flags |= kClipFdWriteTime;
        }
        if (!e.is_directory) {
            flags |= kClipFdFileSize;
        }
        write_u32_le(rec + 0u, flags);
        write_u32_le(rec + 36u, e.is_directory ? kWinFileAttrDirectory : kWinFileAttrNormal);
        write_u64_le(rec + 56u, e.last_write_filetime);
        write_u32_le(rec + 64u, static_cast<uint32_t>((e.file_size >> 32) & 0xFFFFFFFFULL));
        write_u32_le(rec + 68u, static_cast<uint32_t>(e.file_size & 0xFFFFFFFFULL));

        const std::vector<uint16_t> wunits = utf8_to_utf16_units(e.relative_win_path);
        size_t copy_units = std::min<size_t>(wunits.size(), 259u);
        if (copy_units > 0u && wunits[copy_units - 1u] >= 0xD800u && wunits[copy_units - 1u] <= 0xDBFFu) {
            --copy_units;
        }
        for (size_t u = 0u; u < copy_units; ++u) {
            write_u16_le(rec + 72u + u * 2u, wunits[u]);
        }
        write_u16_le(rec + 72u + copy_units * 2u, 0u);
    }
    return buf;
}

bool parse_and_sanitize_cliprdr_file_list(
    const uint8_t* data,
    size_t data_len,
    std::vector<RdpClipboardFileEntry>& out_entries
) {
    out_entries.clear();
    if (!data || data_len < 4u) {
        return false;
    }
    const uint32_t count = read_u32_le(data);
    if (count == 0u || count > kMaxCliprdrFileEntries) {
        return false;
    }
    const size_t required_bytes = 4u + static_cast<size_t>(count) * kFileDescriptorWBytes;
    if (data_len < required_bytes) {
        return false;
    }

    out_entries.reserve(count);
    for (uint32_t i = 0u; i < count; ++i) {
        const uint8_t* rec = data + 4u + static_cast<size_t>(i) * kFileDescriptorWBytes;
        const uint32_t flags = read_u32_le(rec + 0u);
        const uint32_t attrs = read_u32_le(rec + 36u);
        const uint64_t write_ft = read_u64_le(rec + 56u);
        const uint32_t size_hi = read_u32_le(rec + 64u);
        const uint32_t size_lo = read_u32_le(rec + 68u);

        std::string raw_name = utf16le_fixed_to_utf8(rec + 72u, 260u);
        if (raw_name.empty()) {
            out_entries.clear();
            return false;
        }
        for (char& ch : raw_name) {
            if (ch == '\\') {
                ch = '/';
            }
        }
        if (raw_name.front() == '/' || raw_name.back() == '/' || raw_name.find(':') != std::string::npos) {
            out_entries.clear();
            return false;
        }
        for (char ch : raw_name) {
            if (static_cast<unsigned char>(ch) < 0x20u) {
                out_entries.clear();
                return false;
            }
        }

        std::string clean_posix;
        std::string clean_win;
        size_t pos = 0u;
        while (pos < raw_name.size()) {
            const size_t slash = raw_name.find('/', pos);
            const std::string seg = (slash == std::string::npos)
                ? raw_name.substr(pos)
                : raw_name.substr(pos, slash - pos);
            if (seg.empty() || seg == "." || seg == "..") {
                out_entries.clear();
                return false;
            }
            if (!clean_posix.empty()) {
                clean_posix.push_back('/');
                clean_win.push_back('\\');
            }
            clean_posix += seg;
            clean_win += seg;
            if (slash == std::string::npos) {
                break;
            }
            pos = slash + 1u;
        }
        if (clean_posix.empty()) {
            out_entries.clear();
            return false;
        }

        const bool is_dir = ((flags & kClipFdAttributes) != 0u) && ((attrs & kWinFileAttrDirectory) != 0u);
        const bool has_size = is_dir || ((flags & kClipFdFileSize) != 0u);
        const uint64_t full_size = is_dir
            ? 0u
            : ((static_cast<uint64_t>(size_hi) << 32) | static_cast<uint64_t>(size_lo));

        RdpClipboardFileEntry entry{};
        entry.relative_win_path = std::move(clean_win);
        entry.local_posix_path = std::move(clean_posix);
        entry.file_size = has_size ? full_size : 0u;
        entry.last_write_filetime = ((flags & kClipFdWriteTime) != 0u) ? write_ft : 0u;
        entry.is_directory = is_dir;
        entry.has_known_size = has_size;
        out_entries.push_back(std::move(entry));
    }
    return !out_entries.empty();
}

std::vector<RdpClipboardFileEntry> build_local_clipboard_file_entries(
    const std::vector<std::string>& root_paths
) {
    std::vector<RdpClipboardFileEntry> out;
    std::unordered_set<std::string> used_root_names;

    for (const auto& raw_path : root_paths) {
        if (raw_path.empty() || out.size() >= kMaxCliprdrFileEntries) {
            continue;
        }
        std::filesystem::path fs_path(raw_path);
        if (fs_path.filename().empty() && fs_path.has_parent_path()) {
            fs_path = fs_path.parent_path();
        }
        const std::string resolved_str = fs_path.string();
        struct stat st {};
        if (::stat(resolved_str.c_str(), &st) != 0) {
            continue;
        }
        const std::string base_raw = sanitize_win_component(fs_path.filename().string());
        std::string unique_root = base_raw;
        int suffix = 2;
        while (used_root_names.count(unique_root) > 0u) {
            const auto dot = base_raw.find_last_of('.');
            if (dot != std::string::npos && dot > 0u && !S_ISDIR(st.st_mode)) {
                unique_root = base_raw.substr(0u, dot) + " (" + std::to_string(suffix) + ")" + base_raw.substr(dot);
            } else {
                unique_root = base_raw + " (" + std::to_string(suffix) + ")";
            }
            ++suffix;
        }
        if (utf8_to_utf16_units(unique_root).size() >= 260u) {
            continue;
        }
        used_root_names.insert(unique_root);

        if (S_ISDIR(st.st_mode)) {
            RdpClipboardFileEntry dir_entry{};
            dir_entry.relative_win_path = unique_root;
            dir_entry.local_posix_path = resolved_str;
            dir_entry.file_size = 0u;
            dir_entry.last_write_filetime = unix_time_to_win_filetime(st.st_mtime);
            dir_entry.is_directory = true;
            dir_entry.has_known_size = true;
            out.push_back(std::move(dir_entry));
            collect_directory_entries_recursive(fs_path, unique_root, out);
        } else if (S_ISREG(st.st_mode)) {
            RdpClipboardFileEntry file_entry{};
            file_entry.relative_win_path = unique_root;
            file_entry.local_posix_path = resolved_str;
            file_entry.file_size = st.st_size > 0 ? static_cast<uint64_t>(st.st_size) : 0u;
            file_entry.last_write_filetime = unix_time_to_win_filetime(st.st_mtime);
            file_entry.is_directory = false;
            file_entry.has_known_size = true;
            out.push_back(std::move(file_entry));
        }
    }
    return out;
}

RdpVirtualChannelHub::RdpVirtualChannelHub() = default;

RdpVirtualChannelHub::~RdpVirtualChannelHub() {
    stop_audio_streams();
}

void RdpVirtualChannelHub::start_audio_streams(bool speaker_out, bool mic_in, uint32_t sample_rate_hz) {
    std::lock_guard<std::mutex> lock(channel_mutex_);
#ifdef __ANDROID__
    if (speaker_out && !aaudio_out_stream_) {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) == AAUDIO_OK && builder) {
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setSampleRate(builder, static_cast<int32_t>(sample_rate_hz));

            AAudioStream* stream = nullptr;
            if (AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK && stream) {
                AAudioStream_requestStart(stream);
                aaudio_out_stream_ = stream;
            }
            AAudioStreamBuilder_delete(builder);
        }
    }

    if (mic_in && !aaudio_in_stream_) {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) == AAUDIO_OK && builder) {
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setChannelCount(builder, 1);
            AAudioStreamBuilder_setSampleRate(builder, static_cast<int32_t>(sample_rate_hz));

            AAudioStream* stream = nullptr;
            if (AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK && stream) {
                AAudioStream_requestStart(stream);
                aaudio_in_stream_ = stream;
            }
            AAudioStreamBuilder_delete(builder);
        }
    }
#else
    (void)speaker_out;
    (void)mic_in;
    (void)sample_rate_hz;
#endif
}

void RdpVirtualChannelHub::stop_audio_streams() {
    std::lock_guard<std::mutex> lock(channel_mutex_);
#ifdef __ANDROID__
    if (aaudio_out_stream_) {
        auto* out = static_cast<AAudioStream*>(aaudio_out_stream_);
        AAudioStream_requestStop(out);
        AAudioStream_close(out);
        aaudio_out_stream_ = nullptr;
    }
    if (aaudio_in_stream_) {
        auto* in = static_cast<AAudioStream*>(aaudio_in_stream_);
        AAudioStream_requestStop(in);
        AAudioStream_close(in);
        aaudio_in_stream_ = nullptr;
    }
#endif
}

void RdpVirtualChannelHub::push_pcm_playback(const int16_t* pcm_frames, size_t frame_count) {
    std::lock_guard<std::mutex> lock(channel_mutex_);
#ifdef __ANDROID__
    if (aaudio_out_stream_ && pcm_frames && frame_count > 0) {
        auto* out = static_cast<AAudioStream*>(aaudio_out_stream_);
        AAudioStream_write(out, pcm_frames, static_cast<int32_t>(frame_count), 0);
    }
#else
    (void)pcm_frames;
    (void)frame_count;
#endif
}

void RdpVirtualChannelHub::on_local_clipboard_text(const std::string& utf8_text) {
    std::function<void(const std::string&)> announce_cb;
    {
        std::lock_guard<std::mutex> lock(channel_mutex_);
        local_clipboard_files_.reset();
        local_clipboard_utf8_ = utf8_text;
        local_kind_ = utf8_text.empty() ? RdpClipboardContentKind::None : RdpClipboardContentKind::Text;
        announce_cb = clipboard_announce_callback_;
    }
    if (announce_cb && !utf8_text.empty()) {
        announce_cb(utf8_text);
    }
}

void RdpVirtualChannelHub::on_local_clipboard_files(const std::vector<std::string>& root_paths) {
    auto built = std::make_shared<std::vector<RdpClipboardFileEntry>>(
        build_local_clipboard_file_entries(root_paths)
    );
    if (built->empty()) {
        return;
    }
    std::function<void(const std::string&)> announce_cb;
    {
        std::lock_guard<std::mutex> lock(channel_mutex_);
        local_clipboard_utf8_.clear();
        local_clipboard_files_ = built;
        local_kind_ = RdpClipboardContentKind::Files;
        announce_cb = clipboard_announce_callback_;
    }
    if (announce_cb) {
        announce_cb("<files>");
    }
}

void RdpVirtualChannelHub::on_remote_clipboard_text(const std::string& utf8_text) {
    if (utf8_text.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(channel_mutex_);
    remote_clipboard_files_.clear();
    remote_clipboard_utf8_ = utf8_text;
    remote_kind_ = RdpClipboardContentKind::Text;
    // Mirror into local_clipboard_utf8_ so pasting back without a new local copy never overwrites remote text
    local_clipboard_files_.reset();
    local_clipboard_utf8_ = utf8_text;
    local_kind_ = RdpClipboardContentKind::Text;
    remote_clipboard_seq_.fetch_add(1u, std::memory_order_release);
}

void RdpVirtualChannelHub::on_remote_clipboard_files(const std::vector<std::string>& staged_root_paths) {
    if (staged_root_paths.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(channel_mutex_);
    remote_clipboard_utf8_.clear();
    remote_clipboard_files_ = staged_root_paths;
    remote_kind_ = RdpClipboardContentKind::Files;
    local_clipboard_utf8_.clear();
    local_clipboard_files_.reset();
    local_kind_ = RdpClipboardContentKind::None;
    remote_clipboard_seq_.fetch_add(1u, std::memory_order_release);
}

void RdpVirtualChannelHub::mark_remote_clipboard_owner_changed() {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    local_clipboard_utf8_.clear();
    local_clipboard_files_.reset();
    local_kind_ = RdpClipboardContentKind::None;
}

RdpClipboardContentKind RdpVirtualChannelHub::local_clipboard_kind() const {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    return local_kind_;
}

RdpClipboardContentKind RdpVirtualChannelHub::remote_clipboard_kind() const {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    return remote_kind_;
}

std::string RdpVirtualChannelHub::local_clipboard_text() const {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    return local_clipboard_utf8_;
}

std::shared_ptr<const std::vector<RdpClipboardFileEntry>> RdpVirtualChannelHub::local_clipboard_files() const {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    return local_clipboard_files_;
}

std::string RdpVirtualChannelHub::latest_remote_clipboard_text() const {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    return remote_clipboard_utf8_;
}

std::vector<std::string> RdpVirtualChannelHub::latest_remote_clipboard_files() const {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    return remote_clipboard_files_;
}

void RdpVirtualChannelHub::set_local_clipboard_announce_handler(
    std::function<void(const std::string&)> callback
) {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    clipboard_announce_callback_ = std::move(callback);
}

std::vector<uint8_t> RdpVirtualChannelHub::transmit_smartcard_apdu(
    const std::vector<uint8_t>& command_apdu
) {
    std::function<std::vector<uint8_t>(const std::vector<uint8_t>&)> cb;
    {
        std::lock_guard<std::mutex> lock(channel_mutex_);
        cb = apdu_callback_;
    }
    if (cb) {
        return cb(command_apdu);
    }
    // ISO 7816-4 SW1/SW2 0x6A82 (File/Applet not found when no USB CCID token attached)
    return {0x6A, 0x82};
}

void RdpVirtualChannelHub::set_smartcard_apdu_handler(
    std::function<std::vector<uint8_t>(const std::vector<uint8_t>&)> callback
) {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    apdu_callback_ = std::move(callback);
}

} // namespace nexusts::rdp
