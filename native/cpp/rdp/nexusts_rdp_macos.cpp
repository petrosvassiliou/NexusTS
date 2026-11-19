#include "nexusts_rdp_macos.h"
#include "rdp_session_context.hpp"

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

struct MacosRdpRegistry {
    std::mutex mutex;
    int64_t next_handle{8000};
    std::unordered_map<int64_t, std::shared_ptr<nexusts::rdp::RdpSessionContext>> sessions;
};

MacosRdpRegistry& macos_rdp_registry() {
    static auto* reg = new MacosRdpRegistry();
    return *reg;
}

std::string safe_cstr(const char* ptr) {
    return ptr ? std::string(ptr) : std::string();
}

std::shared_ptr<nexusts::rdp::RdpSessionContext> lookup_session(int64_t handle) {
    auto& reg = macos_rdp_registry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    auto it = reg.sessions.find(handle);
    return it != reg.sessions.end() ? it->second : nullptr;
}

} // namespace

extern "C" {

int64_t nexusts_rdp_create_session(const NexusRdpConfig* config) {
    if (!config) {
        return 0;
    }
    nexusts::rdp::RdpConnectParams params{};
    params.host = safe_cstr(config->host);
    params.port = config->port;
    params.username = safe_cstr(config->username);
    params.password = safe_cstr(config->password);
    params.domain = safe_cstr(config->domain);
    params.rd_gateway_host = safe_cstr(config->rd_gateway_host);
    params.rd_gateway_port = config->rd_gateway_port == 0 ? 443 : config->rd_gateway_port;
    params.initial_width = config->initial_width == 0 ? 1920 : config->initial_width;
    params.initial_height = config->initial_height == 0 ? 1080 : config->initial_height;
    params.desktop_dpi = config->desktop_dpi == 0 ? 144 : config->desktop_dpi;
    params.connect_to_console = config->connect_to_console;
    params.hidpi_enabled = config->hidpi_enabled;
    params.desktop_scale_factor = config->desktop_scale_factor == 0 ? 100 : config->desktop_scale_factor;
    params.device_scale_factor = config->device_scale_factor == 0
        ? nexusts::rdp::map_device_scale_factor(params.desktop_scale_factor)
        : config->device_scale_factor;
    params.color_depth_bits = config->color_depth_bits == 0 ? 32 : config->color_depth_bits;
    params.enable_gfx_pipeline = config->enable_gfx_pipeline;
    params.gfx_codec_mode = config->gfx_codec_mode;
    params.enable_h264_avc444 = config->enable_h264_avc444;
    params.enable_remotefx = config->enable_remotefx;
    params.enable_dynamic_resize = config->enable_dynamic_resize;
    params.multi_monitor_spanning = config->multi_monitor_spanning;
    params.redirect_clipboard = config->redirect_clipboard;
    params.audio_redirection_mode = config->audio_redirection_mode;
    params.redirect_audio_out = config->redirect_audio_out;
    params.redirect_mic_in = config->redirect_mic_in;
    params.redirect_drives = config->redirect_drives;
    params.redirect_printers = config->redirect_printers;
    params.redirect_serial_ports = config->redirect_serial_ports;
    params.redirect_smartcard = config->redirect_smartcard;
    params.enable_font_smoothing = config->enable_font_smoothing;
    params.enable_desktop_composition = config->enable_desktop_composition;
    params.enable_wallpaper = config->enable_wallpaper;
    params.enable_full_window_drag = config->enable_full_window_drag;
    params.enable_menu_animations = config->enable_menu_animations;
    params.enable_themes = config->enable_themes;
    params.compression_enabled = config->compression_enabled;
    params.nla_security = config->nla_security;
    params.tls_security = config->tls_security;
    params.restricted_admin_mode = config->restricted_admin_mode;
    params.ignore_certificate = config->ignore_certificate;
    params.remote_app_mode = config->remote_app_mode;
    params.remote_app_program = safe_cstr(config->remote_app_program);
    params.remote_app_args = safe_cstr(config->remote_app_args);
    params.shared_drive_name = safe_cstr(config->shared_drive_name);
    params.shared_drive_local_path = safe_cstr(config->shared_drive_local_path);
    params.app_home_dir = safe_cstr(config->app_home_dir);
    params.preconnection_blob = safe_cstr(config->preconnection_blob);

    auto ctx = std::make_shared<nexusts::rdp::RdpSessionContext>(std::move(params));
    auto& reg = macos_rdp_registry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    const int64_t handle = ++reg.next_handle;
    reg.sessions.emplace(handle, std::move(ctx));
    return handle;
}

bool nexusts_rdp_connect(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->connect();
    }
    return false;
}

void nexusts_rdp_disconnect(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        ctx->disconnect();
    }
}

bool nexusts_rdp_is_connected(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->is_connected();
    }
    return false;
}

bool nexusts_rdp_is_reconnecting(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->is_reconnecting();
    }
    return false;
}

uint32_t nexusts_rdp_reconnect_attempt(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->reconnect_attempt();
    }
    return 0u;
}

void nexusts_rdp_destroy_session(int64_t handle) {
    std::shared_ptr<nexusts::rdp::RdpSessionContext> removed;
    {
        auto& reg = macos_rdp_registry();
        std::lock_guard<std::mutex> lock(reg.mutex);
        auto it = reg.sessions.find(handle);
        if (it != reg.sessions.end()) {
            removed = std::move(it->second);
            reg.sessions.erase(it);
        }
    }
    if (removed) {
        removed->presenter().set_frame_ready_callback(nullptr, nullptr);
        removed->request_stop();
        removed->disconnect();
    }
}

void nexusts_rdp_shutdown_all_sessions(void) {
    std::vector<std::shared_ptr<nexusts::rdp::RdpSessionContext>> drained;
    {
        auto& reg = macos_rdp_registry();
        std::lock_guard<std::mutex> lock(reg.mutex);
        drained.reserve(reg.sessions.size());
        for (auto& kv : reg.sessions) {
            if (kv.second) {
                drained.push_back(std::move(kv.second));
            }
        }
        reg.sessions.clear();
    }
    // Signal stop and detach bridge callbacks on ALL sessions in parallel first
    // so multi-tab shutdown aborts in-flight connects and event loops simultaneously.
    for (const auto& ctx : drained) {
        ctx->presenter().set_frame_ready_callback(nullptr, nullptr);
        ctx->request_stop();
    }
    for (const auto& ctx : drained) {
        ctx->disconnect();
    }
}

bool nexusts_rdp_request_resize(
    int64_t handle,
    uint32_t new_width,
    uint32_t new_height,
    uint32_t desktop_scale_percent
) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->request_dynamic_display_resize(new_width, new_height, desktop_scale_percent);
    }
    return false;
}

bool nexusts_rdp_update_monitors(
    int64_t handle,
    const NexusRdpMonitorSpec* monitors,
    size_t monitor_count
) {
    if (!monitors || monitor_count == 0) {
        return false;
    }
    if (auto ctx = lookup_session(handle)) {
        std::vector<nexusts::rdp::RdpMonitorLayout> layouts;
        layouts.reserve(monitor_count);
        for (size_t i = 0; i < monitor_count; ++i) {
            nexusts::rdp::RdpMonitorLayout m{};
            m.monitor_id = monitors[i].monitor_id;
            m.left = monitors[i].left;
            m.top = monitors[i].top;
            m.width = monitors[i].width;
            m.height = monitors[i].height;
            m.physical_width_mm = monitors[i].physical_width_mm;
            m.physical_height_mm = monitors[i].physical_height_mm;
            m.desktop_scale_factor = monitors[i].desktop_scale_factor;
            m.device_scale_factor = monitors[i].device_scale_factor;
            m.is_primary = monitors[i].is_primary;
            layouts.push_back(m);
        }
        return ctx->update_multi_monitor_topology(layouts);
    }
    return false;
}

void nexusts_rdp_send_key_scancode(
    int64_t handle,
    uint16_t rdp_scancode,
    bool key_down,
    bool extended_key
) {
    if (auto ctx = lookup_session(handle)) {
        ctx->send_keyboard_scancode(rdp_scancode, key_down, extended_key);
    }
}

void nexusts_rdp_release_all_keys(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        ctx->release_all_pressed_keys();
    }
}

void nexusts_rdp_logoff_session(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        ctx->logoff_remote_session();
    }
}

void nexusts_rdp_request_synchronized_paste(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        ctx->request_synchronized_paste();
    }
}

void nexusts_rdp_send_pointer_event(
    int64_t handle,
    uint16_t pointer_flags,
    uint16_t x,
    uint16_t y
) {
    if (auto ctx = lookup_session(handle)) {
        ctx->send_pointer_event(pointer_flags, x, y);
    }
}

void nexusts_rdp_send_extended_pointer_event(
    int64_t handle,
    uint16_t xflags,
    uint16_t x,
    uint16_t y
) {
    if (auto ctx = lookup_session(handle)) {
        ctx->send_extended_pointer_event(xflags, x, y);
    }
}

void nexusts_rdp_set_local_clipboard(int64_t handle, const char* utf8_text) {
    if (auto ctx = lookup_session(handle)) {
        ctx->channels().on_local_clipboard_text(safe_cstr(utf8_text));
    }
}

void nexusts_rdp_set_local_clipboard_files(int64_t handle, const char* newline_joined_utf8_paths) {
    if (auto ctx = lookup_session(handle)) {
        const std::string joined = safe_cstr(newline_joined_utf8_paths);
        std::vector<std::string> paths;
        size_t pos = 0u;
        while (pos < joined.size()) {
            const size_t nl = joined.find('\n', pos);
            std::string seg = (nl == std::string::npos)
                ? joined.substr(pos)
                : joined.substr(pos, nl - pos);
            if (!seg.empty() && seg.back() == '\r') {
                seg.pop_back();
            }
            if (!seg.empty()) {
                paths.push_back(std::move(seg));
            }
            if (nl == std::string::npos) {
                break;
            }
            pos = nl + 1u;
        }
        if (!paths.empty()) {
            ctx->channels().on_local_clipboard_files(paths);
        }
    }
}

uint32_t nexusts_rdp_remote_clipboard_kind(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return static_cast<uint32_t>(ctx->channels().remote_clipboard_kind());
    }
    return 0u;
}

char* nexusts_rdp_copy_remote_clipboard(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        const std::string clip = ctx->channels().latest_remote_clipboard_text();
        auto* dup = static_cast<char*>(std::malloc(clip.size() + 1u));
        if (dup) {
            std::memcpy(dup, clip.data(), clip.size());
            dup[clip.size()] = '\0';
        }
        return dup;
    }
    return nullptr;
}

char* nexusts_rdp_copy_remote_clipboard_files(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        const std::vector<std::string> files = ctx->channels().latest_remote_clipboard_files();
        std::string joined;
        for (size_t i = 0u; i < files.size(); ++i) {
            if (i > 0u) {
                joined.push_back('\n');
            }
            joined += files[i];
        }
        auto* dup = static_cast<char*>(std::malloc(joined.size() + 1u));
        if (dup) {
            std::memcpy(dup, joined.data(), joined.size());
            dup[joined.size()] = '\0';
        }
        return dup;
    }
    return nullptr;
}

uint64_t nexusts_rdp_remote_clipboard_sequence(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->channels().remote_clipboard_sequence();
    }
    return 0;
}

char* nexusts_rdp_copy_last_error(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        const std::string err = ctx->last_error();
        auto* dup = static_cast<char*>(std::malloc(err.size() + 1u));
        if (dup) {
            std::memcpy(dup, err.data(), err.size());
            dup[err.size()] = '\0';
        }
        return dup;
    }
    return nullptr;
}

void nexusts_rdp_free_cstr(char* ptr) {
    std::free(ptr);
}

void nexusts_rdp_set_frame_ready_callback(
    int64_t handle,
    NexusRdpFrameReadyCallback callback,
    void* user_data
) {
    if (auto ctx = lookup_session(handle)) {
        ctx->presenter().set_frame_ready_callback(callback, user_data);
    }
}

uint64_t nexusts_rdp_frame_sequence(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->presenter().frame_sequence();
    }
    return 0;
}

bool nexusts_rdp_consume_dirty_region(
    int64_t handle,
    bool force_full,
    NexusRdpDirtyRegionConsumer consumer,
    void* user_data
) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->presenter().consume_dirty_shadow_region(force_full, consumer, user_data);
    }
    return false;
}

bool nexusts_rdp_consume_dirty_rects(
    int64_t handle,
    bool force_full,
    NexusRdpMultiDirtyRegionConsumer consumer,
    void* user_data
) {
    if (!consumer) {
        return false;
    }
    static_assert(
        sizeof(NexusRdpDirtyRect) == sizeof(nexusts::rdp::RdpDirtyRect) &&
            alignof(NexusRdpDirtyRect) == alignof(nexusts::rdp::RdpDirtyRect),
        "NexusRdpDirtyRect and RdpDirtyRect layout must match"
    );
    struct MultiRectThunk {
        NexusRdpMultiDirtyRegionConsumer fn;
        void* ctx;
    } thunk{consumer, user_data};

    if (auto session = lookup_session(handle)) {
        return session->presenter().consume_dirty_shadow_rects(
            force_full,
            [](const uint8_t* full_pixels,
               uint32_t stride_bytes,
               uint32_t fb_width,
               uint32_t fb_height,
               const nexusts::rdp::RdpDirtyRect* rects,
               size_t rect_count,
               uint64_t frame_seq,
               void* raw_thunk) {
                auto* t = static_cast<MultiRectThunk*>(raw_thunk);
                t->fn(
                    full_pixels,
                    stride_bytes,
                    fb_width,
                    fb_height,
                    reinterpret_cast<const NexusRdpDirtyRect*>(rects),
                    rect_count,
                    frame_seq,
                    t->ctx
                );
            },
            &thunk
        );
    }
    return false;
}

size_t nexusts_rdp_copy_frame_rgba(
    int64_t handle,
    uint8_t* dst_rgba,
    size_t dst_capacity,
    uint32_t* out_width,
    uint32_t* out_height
) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->presenter().copy_shadow_rgba(dst_rgba, dst_capacity, out_width, out_height);
    }
    return 0;
}

size_t nexusts_rdp_copy_dirty_frame(
    int64_t handle,
    uint8_t* dst_pixels,
    size_t dst_capacity,
    uint32_t* out_width,
    uint32_t* out_height,
    uint32_t* out_dirty_x,
    uint32_t* out_dirty_y,
    uint32_t* out_dirty_w,
    uint32_t* out_dirty_h
) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->presenter().copy_dirty_shadow_pixels(
            dst_pixels,
            dst_capacity,
            out_width,
            out_height,
            out_dirty_x,
            out_dirty_y,
            out_dirty_w,
            out_dirty_h
        );
    }
    return 0;
}

uint64_t nexusts_rdp_cursor_sequence(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->cursor_sequence();
    }
    return 0;
}

size_t nexusts_rdp_copy_cursor_bgra(
    int64_t handle,
    uint8_t* dst_bgra,
    size_t dst_capacity,
    uint32_t* out_width,
    uint32_t* out_height,
    uint32_t* out_hot_x,
    uint32_t* out_hot_y,
    bool* out_visible,
    bool* out_is_default
) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->copy_cursor_bgra(
            dst_bgra,
            dst_capacity,
            out_width,
            out_height,
            out_hot_x,
            out_hot_y,
            out_visible,
            out_is_default
        );
    }
    return 0;
}

bool nexusts_rdp_self_test_cliprdr_binary_roundtrip(const char* work_dir_utf8) {
    if (!work_dir_utf8 || work_dir_utf8[0] == '\0') {
        return false;
    }

    auto write_raw_file = [](const std::filesystem::path& p, const std::vector<uint8_t>& bytes) -> bool {
        int fd = -1;
        do {
            fd = ::open(p.string().c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
        } while (fd < 0 && errno == EINTR);
        if (fd < 0) {
            return false;
        }
        size_t off = 0u;
        while (off < bytes.size()) {
            const ssize_t nw = ::write(fd, bytes.data() + off, bytes.size() - off);
            if (nw < 0) {
                if (errno == EINTR) {
                    continue;
                }
                (void)::close(fd);
                return false;
            }
            if (nw == 0) {
                (void)::close(fd);
                return false;
            }
            off += static_cast<size_t>(nw);
        }
        (void)::close(fd);
        return true;
    };

    auto read_raw_file = [](const std::filesystem::path& p, std::vector<uint8_t>& out) -> bool {
        int fd = -1;
        do {
            fd = ::open(p.string().c_str(), O_RDONLY);
        } while (fd < 0 && errno == EINTR);
        if (fd < 0) {
            return false;
        }
        struct stat st {};
        if (::fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < 0) {
            (void)::close(fd);
            return false;
        }
        out.assign(static_cast<size_t>(st.st_size), 0u);
        size_t off = 0u;
        while (off < out.size()) {
            const ssize_t nr = ::read(fd, out.data() + off, out.size() - off);
            if (nr < 0) {
                if (errno == EINTR) {
                    continue;
                }
                (void)::close(fd);
                return false;
            }
            if (nr == 0) {
                break;
            }
            off += static_cast<size_t>(nr);
        }
        (void)::close(fd);
        return off == out.size();
    };

    std::error_code ec;
    const std::filesystem::path root(work_dir_utf8);
    const std::filesystem::path src_dir = root / "src_local";
    const std::filesystem::path app_home = root / "app_home";
    std::filesystem::remove_all(src_dir, ec);
    std::filesystem::remove_all(app_home, ec);
    std::filesystem::create_directories(src_dir / "nested_pkg", ec);
    std::filesystem::create_directories(app_home, ec);
    if (ec) {
        return false;
    }

    // 1. Exact .txt payload with UTF-8 BOM, bare LF, CRLF, bare CR, NUL, 0x1A DOS EOF, and raw 0x80..0xFF bytes
    std::vector<uint8_t> txt_payload = {
        0xEFu, 0xBBu, 0xBFu,
        'N', 'e', 'x', 'u', 's', 'T', 'S', ' ', 'b', 'i', 'n', 'a', 'r', 'y', ' ', '.', 't', 'x', 't', '\n',
        'L', 'i', 'n', 'e', '2', '\r', '\n',
        'B', 'a', 'r', 'e', 'C', 'R', '\r',
        0x00u, 0x1Au, 0x7Fu, 0x80u, 0xFEu, 0xFFu, '\n'
    };
    while (txt_payload.size() < 4321u) {
        txt_payload.push_back(static_cast<uint8_t>((txt_payload.size() * 37u + 11u) & 0xFFu));
    }

    // 2. 0-byte .txt file
    const std::vector<uint8_t> zero_txt_payload;

    // 3. > 8 MiB .exe PE binary (9,699,337 bytes) with MZ/PE headers, NULs, 0x1A, CRLF, LF, and all 0x00..0xFF bytes
    constexpr size_t kLargeExeBytes = 9u * 1024u * 1024u + 262153u;
    std::vector<uint8_t> exe_payload(kLargeExeBytes, 0u);
    exe_payload[0] = 'M';
    exe_payload[1] = 'Z';
    exe_payload[2] = 0x90u;
    exe_payload[3] = 0x00u;
    exe_payload[4] = 0x03u;
    exe_payload[0x3Cu] = 0x80u;
    exe_payload[0x80u] = 'P';
    exe_payload[0x81u] = 'E';
    exe_payload[0x82u] = 0x00u;
    exe_payload[0x83u] = 0x00u;
    exe_payload[0x84u] = 0x64u;
    exe_payload[0x85u] = 0x86u;
    for (size_t i = 0x90u; i < exe_payload.size(); ++i) {
        exe_payload[i] = static_cast<uint8_t>(((i * 1315423911ULL) ^ (i >> 7)) & 0xFFULL);
    }
    exe_payload[512] = 0x1Au;
    exe_payload[513] = 0x00u;
    exe_payload[514] = '\n';
    exe_payload[515] = '\r';
    exe_payload[516] = '\n';
    exe_payload[517] = 0xFFu;

    // 4. Nested .exe inside folder
    std::vector<uint8_t> nested_exe_payload(65537u, 0u);
    nested_exe_payload[0] = 'M';
    nested_exe_payload[1] = 'Z';
    for (size_t i = 2u; i < nested_exe_payload.size(); ++i) {
        nested_exe_payload[i] = static_cast<uint8_t>((i * 73u + 19u) & 0xFFu);
    }

    const std::filesystem::path src_txt = src_dir / "notes_exact_name.txt";
    const std::filesystem::path src_zero_txt = src_dir / "empty_zero.txt";
    const std::filesystem::path src_exe = src_dir / "NexusSetup_v1.0.exe";
    const std::filesystem::path src_nested_dir = src_dir / "nested_pkg";
    const std::filesystem::path src_nested_exe = src_nested_dir / "tool_helper.exe";
    const std::filesystem::path src_symlink_exe = src_dir / "AliasLauncher.exe";

    if (!write_raw_file(src_txt, txt_payload) ||
        !write_raw_file(src_zero_txt, zero_txt_payload) ||
        !write_raw_file(src_exe, exe_payload) ||
        !write_raw_file(src_nested_exe, nested_exe_payload)) {
        return false;
    }
    std::filesystem::create_symlink(src_nested_exe, src_symlink_exe, ec);
    const bool has_symlink = !ec;

    nexusts::rdp::RdpConnectParams params{};
    params.host = "127.0.0.1";
    params.redirect_clipboard = true;
    params.app_home_dir = app_home.string();
    nexusts::rdp::RdpSessionContext session(params);

    std::vector<std::string> local_roots = {
        src_txt.string(),
        src_zero_txt.string(),
        src_exe.string(),
        src_nested_dir.string()
    };
    if (has_symlink) {
        local_roots.push_back(src_symlink_exe.string());
    }
    session.channels().on_local_clipboard_files(local_roots);

    const auto bound_snapshot = session.bind_active_file_group_descriptor_snapshot();
    if (!bound_snapshot || bound_snapshot->size() != (has_symlink ? 6u : 5u)) {
        return false;
    }

    // Verify Local -> Remote FILEDESCRIPTORW serialization & >8 MiB single-request raw binary read
    const std::vector<uint8_t> packed_fgdw = nexusts::rdp::serialize_cliprdr_file_list(*bound_snapshot);
    std::vector<nexusts::rdp::RdpClipboardFileEntry> parsed_wire;
    if (!nexusts::rdp::parse_and_sanitize_cliprdr_file_list(
            packed_fgdw.data(),
            packed_fgdw.size(),
            parsed_wire,
            /*is_ansi_descriptor=*/false
        ) ||
        parsed_wire.size() != bound_snapshot->size()) {
        return false;
    }

    if (parsed_wire[0].local_posix_path != "notes_exact_name.txt" ||
        parsed_wire[1].local_posix_path != "empty_zero.txt" ||
        parsed_wire[2].local_posix_path != "NexusSetup_v1.0.exe" ||
        parsed_wire[3].local_posix_path != "nested_pkg" || !parsed_wire[3].is_directory ||
        parsed_wire[4].local_posix_path != "nested_pkg/tool_helper.exe") {
        return false;
    }
    if (has_symlink && parsed_wire[5].local_posix_path != "AliasLauncher.exe") {
        return false;
    }

    // Single 16 MiB FILECONTENTS_RANGE request on 9.25 MiB .exe MUST return all 9,699,337 bytes without 8 MiB truncation
    std::vector<uint8_t> single_shot_exe;
    uint64_t reported_exe_size = 0u;
    if (!nexusts::rdp::read_local_clipboard_file_range(
            (*bound_snapshot)[2].local_posix_path,
            0u,
            16u * 1024u * 1024u,
            single_shot_exe,
            &reported_exe_size
        ) ||
        reported_exe_size != exe_payload.size() ||
        single_shot_exe != exe_payload) {
        return false;
    }

    std::vector<uint8_t> single_shot_txt;
    if (!nexusts::rdp::read_local_clipboard_file_range(
            (*bound_snapshot)[0].local_posix_path,
            0u,
            65536u,
            single_shot_txt
        ) ||
        single_shot_txt != txt_payload) {
        return false;
    }

    // Verify Remote -> Local staging, including virtual stream FD_FILESIZE=0 + FILECONTENTS_SIZE discovery
    std::vector<uint8_t> virtual_fgdw = packed_fgdw;
    // Entry 2 (NexusSetup_v1.0.exe) offset: 4 + 2 * 592; zero out nFileSizeHigh (offset 64) & nFileSizeLow (offset 68)
    const size_t exe_desc_offset = 4u + 2u * 592u;
    std::memset(virtual_fgdw.data() + exe_desc_offset + 64u, 0, 8u);

    struct PendingReq {
        uint32_t stream_id;
        uint32_t list_index;
        uint32_t dw_flags;
        uint64_t offset;
        uint32_t cb_requested;
        bool has_clip_data_id;
    };
    std::deque<PendingReq> req_queue;
    session.set_file_contents_request_hook([&](
        uint32_t stream_id,
        uint32_t list_index,
        uint32_t dw_flags,
        uint64_t offset,
        uint32_t cb_requested,
        bool has_clip_data_id,
        uint32_t /*clip_data_id*/
    ) -> bool {
        req_queue.push_back(PendingReq{
            stream_id,
            list_index,
            dw_flags,
            offset,
            cb_requested,
            has_clip_data_id
        });
        return true;
    });

    session.begin_remote_file_list_fetch(nullptr, 0xC099u, /*is_ansi=*/false);
    session.handle_remote_file_group_descriptor_response(
        nullptr,
        virtual_fgdw.data(),
        virtual_fgdw.size()
    );

    size_t safety_iters = 0u;
    while (!req_queue.empty() && safety_iters++ < 256u) {
        const PendingReq req = req_queue.front();
        req_queue.pop_front();
        // MS-RDPECLIP 2.2.5.3: client-pulled FileContentsRequest MUST NOT fabricate clipDataId
        if (req.has_clip_data_id || req.list_index >= bound_snapshot->size()) {
            return false;
        }
        const auto& src_entry = (*bound_snapshot)[req.list_index];
        if (req.dw_flags == 0x1u) {
            struct stat st {};
            if (::stat(src_entry.local_posix_path.c_str(), &st) != 0 || st.st_size < 0) {
                return false;
            }
            const uint64_t true_sz = static_cast<uint64_t>(st.st_size);
            uint8_t sz_le[8]{};
            for (int b = 0; b < 8; ++b) {
                sz_le[b] = static_cast<uint8_t>((true_sz >> (b * 8)) & 0xFFULL);
            }
            session.handle_remote_file_contents_response(nullptr, req.stream_id, true, sz_le, 8u);
        } else if (req.dw_flags == 0x2u) {
            // Deliberately cap server response chunks to 600,000 bytes to exercise multi-chunk + short-read reassembly
            const uint32_t capped = std::min<uint32_t>(req.cb_requested, 600000u);
            std::vector<uint8_t> slice;
            if (!nexusts::rdp::read_local_clipboard_file_range(
                    src_entry.local_posix_path,
                    req.offset,
                    capped,
                    slice
                )) {
                return false;
            }
            const uint8_t dummy = 0u;
            session.handle_remote_file_contents_response(
                nullptr,
                req.stream_id,
                true,
                slice.empty() ? &dummy : slice.data(),
                slice.size()
            );
        } else {
            return false;
        }
    }
    if (!req_queue.empty()) {
        return false;
    }

    const std::vector<std::string> staged_roots = session.channels().latest_remote_clipboard_files();
    if (staged_roots.size() != (has_symlink ? 5u : 4u)) {
        return false;
    }
    if (std::filesystem::path(staged_roots[0]).filename().string() != "notes_exact_name.txt" ||
        std::filesystem::path(staged_roots[1]).filename().string() != "empty_zero.txt" ||
        std::filesystem::path(staged_roots[2]).filename().string() != "NexusSetup_v1.0.exe" ||
        std::filesystem::path(staged_roots[3]).filename().string() != "nested_pkg") {
        return false;
    }
    if (has_symlink && std::filesystem::path(staged_roots[4]).filename().string() != "AliasLauncher.exe") {
        return false;
    }

    std::vector<uint8_t> got_txt;
    std::vector<uint8_t> got_zero_txt;
    std::vector<uint8_t> got_exe;
    std::vector<uint8_t> got_nested_exe;
    if (!read_raw_file(staged_roots[0], got_txt) || got_txt != txt_payload) {
        return false;
    }
    if (!read_raw_file(staged_roots[1], got_zero_txt) || !got_zero_txt.empty()) {
        return false;
    }
    if (!read_raw_file(staged_roots[2], got_exe) || got_exe != exe_payload) {
        return false;
    }
    if (!read_raw_file(std::filesystem::path(staged_roots[3]) / "tool_helper.exe", got_nested_exe) ||
        got_nested_exe != nested_exe_payload) {
        return false;
    }
    if (has_symlink) {
        std::vector<uint8_t> got_sym_exe;
        if (!read_raw_file(staged_roots[4], got_sym_exe) || got_sym_exe != nested_exe_payload) {
            return false;
        }
    }

    // Also verify ANSI FILEDESCRIPTORA (332-byte entries) staging for legacy RDP servers
    std::vector<uint8_t> ansi_fgd(4u + 332u, 0u);
    ansi_fgd[0] = 1u; // cItems = 1
    ansi_fgd[4] = 0x44u; // FD_ATTRIBUTES | FD_FILESIZE
    ansi_fgd[4 + 36] = 0x80u; // FILE_ATTRIBUTE_NORMAL
    const uint32_t ansi_sz = static_cast<uint32_t>(nested_exe_payload.size());
    ansi_fgd[4 + 68] = static_cast<uint8_t>(ansi_sz & 0xFFu);
    ansi_fgd[4 + 69] = static_cast<uint8_t>((ansi_sz >> 8) & 0xFFu);
    ansi_fgd[4 + 70] = static_cast<uint8_t>((ansi_sz >> 16) & 0xFFu);
    ansi_fgd[4 + 71] = static_cast<uint8_t>((ansi_sz >> 24) & 0xFFu);
    const char ansi_name[] = "legacy_installer.exe";
    std::memcpy(ansi_fgd.data() + 4u + 72u, ansi_name, sizeof(ansi_name));

    session.begin_remote_file_list_fetch(nullptr, 0xC09Au, /*is_ansi=*/true);
    session.handle_remote_file_group_descriptor_response(nullptr, ansi_fgd.data(), ansi_fgd.size());
    while (!req_queue.empty()) {
        const PendingReq req = req_queue.front();
        req_queue.pop_front();
        if (req.has_clip_data_id) {
            return false;
        }
        if (req.dw_flags == 0x1u) {
            session.handle_remote_file_contents_response(nullptr, req.stream_id, false, nullptr, 0u);
        } else if (req.dw_flags == 0x2u) {
            const size_t avail = req.offset < nested_exe_payload.size()
                ? (nested_exe_payload.size() - static_cast<size_t>(req.offset))
                : 0u;
            const size_t take = std::min<size_t>(avail, req.cb_requested);
            const uint8_t dummy = 0u;
            session.handle_remote_file_contents_response(
                nullptr,
                req.stream_id,
                true,
                take > 0u ? (nested_exe_payload.data() + req.offset) : &dummy,
                take
            );
        }
    }
    const std::vector<std::string> ansi_staged = session.channels().latest_remote_clipboard_files();
    std::vector<uint8_t> got_ansi_exe;
    if (ansi_staged.size() != 1u ||
        std::filesystem::path(ansi_staged[0]).filename().string() != "legacy_installer.exe" ||
        !read_raw_file(ansi_staged[0], got_ansi_exe) ||
        got_ansi_exe != nested_exe_payload) {
        return false;
    }

    // v0.8.11 Regression 1: 283 KB Windows Explorer CSV (1_Group_Mapping_Final.csv) with FD_FILESIZE > 0
    // MUST issue FILECONTENTS_RANGE (0x2) directly without redundant FILECONTENTS_SIZE (0x1) or clipDataId.
    const std::filesystem::path src_csv = src_dir / "1_Group_Mapping_Final.csv";
    std::vector<uint8_t> csv_payload;
    const std::string csv_header = "GroupID;SamAccountName;DisplayName;Department;Notes\r\n";
    const std::string csv_row = "GRP-0042;sec_ops_dach;Security Operations München (äöüßÄÖÜ€);IT-Security;Active\r\n";
    csv_payload.insert(csv_payload.end(), csv_header.begin(), csv_header.end());
    while (csv_payload.size() < 289792u) {
        csv_payload.insert(csv_payload.end(), csv_row.begin(), csv_row.end());
    }
    csv_payload.resize(289792u);
    if (!write_raw_file(src_csv, csv_payload)) {
        return false;
    }
    session.channels().on_local_clipboard_files({src_csv.string()});
    const auto csv_snapshot = session.bind_active_file_group_descriptor_snapshot();
    if (!csv_snapshot || csv_snapshot->size() != 1u) {
        return false;
    }
    const std::vector<uint8_t> csv_fgdw = nexusts::rdp::serialize_cliprdr_file_list(*csv_snapshot);
    session.begin_remote_file_list_fetch(nullptr, 0xC09Bu, /*is_ansi=*/false);
    session.handle_remote_file_group_descriptor_response(nullptr, csv_fgdw.data(), csv_fgdw.size());
    bool saw_size_query_for_known_csv = false;
    while (!req_queue.empty()) {
        const PendingReq req = req_queue.front();
        req_queue.pop_front();
        if (req.has_clip_data_id) {
            return false;
        }
        if (req.dw_flags == 0x1u) {
            saw_size_query_for_known_csv = true;
            return false;
        }
        if (req.dw_flags == 0x2u) {
            const size_t avail = req.offset < csv_payload.size()
                ? (csv_payload.size() - static_cast<size_t>(req.offset))
                : 0u;
            const size_t take = std::min<size_t>(avail, req.cb_requested);
            const uint8_t dummy = 0u;
            session.handle_remote_file_contents_response(
                nullptr,
                req.stream_id,
                true,
                take > 0u ? (csv_payload.data() + req.offset) : &dummy,
                take
            );
        }
    }
    const std::vector<std::string> csv_staged = session.channels().latest_remote_clipboard_files();
    std::vector<uint8_t> got_csv;
    if (saw_size_query_for_known_csv ||
        csv_staged.size() != 1u ||
        std::filesystem::path(csv_staged[0]).filename().string() != "1_Group_Mapping_Final.csv" ||
        !read_raw_file(csv_staged[0], got_csv) ||
        got_csv != csv_payload) {
        return false;
    }

    // v0.8.11 Regression 2: Local clipboard text & files MUST survive incoming remote ServerFormatList / text updates
    session.channels().on_local_clipboard_text("Local macOS text äöüß€");
    session.channels().mark_remote_clipboard_owner_changed();
    session.channels().on_remote_clipboard_text("Remote Windows text 12345");
    if (session.channels().local_clipboard_text() != "Local macOS text äöüß€" ||
        session.channels().latest_remote_clipboard_text() != "Remote Windows text 12345" ||
        session.channels().remote_clipboard_kind() != nexusts::rdp::RdpClipboardContentKind::Text) {
        return false;
    }
    session.channels().on_local_clipboard_files({src_csv.string()});
    session.channels().mark_remote_clipboard_owner_changed();
    const auto preserved_local_files = session.channels().local_clipboard_files();
    if (!preserved_local_files || preserved_local_files->size() != 1u) {
        return false;
    }

    // v0.8.11 Regression 3: Independent client_requested_format_id_ & hybrid fallback_text_format_id_ consumption
    session.set_client_requested_format(0xC09Cu, 13u);
    if (session.client_requested_format_id() != 0xC09Cu) {
        return false;
    }
    session.begin_remote_file_list_fetch(nullptr, 0xC09Cu, /*is_ansi=*/false);
    session.handle_remote_file_group_descriptor_response(nullptr, nullptr, 0u);
    if (session.consume_fallback_text_format_id() != 0u) {
        return false;
    }

    session.set_file_contents_request_hook(nullptr);
    std::filesystem::remove_all(src_dir, ec);
    std::filesystem::remove_all(app_home, ec);
    return true;
}

} // extern "C"
