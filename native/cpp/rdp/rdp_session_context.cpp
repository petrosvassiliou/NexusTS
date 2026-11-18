#include "rdp_session_context.hpp"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <system_error>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#define NX_RDP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NexusTS-RDP", __VA_ARGS__)
#define NX_RDP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NexusTS-RDP", __VA_ARGS__)
#else
#define NX_RDP_LOGI(...) ((void)0)
#define NX_RDP_LOGE(...) ((void)0)
#endif

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
#include <cstdio>
#include <freerdp/addin.h>
#include <freerdp/freerdp.h>
#include <freerdp/error.h>
#include <freerdp/event.h>
#include <freerdp/codec/color.h>
#include <freerdp/codec/h264.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/graphics.h>
#include <freerdp/channels/channels.h>
#include <freerdp/channels/cliprdr.h>
#include <freerdp/channels/rdpdr.h>
#include <freerdp/channels/rdpgfx.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cliprdr.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/client/disp.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/input.h>
#include <freerdp/settings.h>
#include <winpr/environment.h>
#include <winpr/handle.h>
#include <winpr/synch.h>
#endif

namespace nexusts::rdp {

namespace {
constexpr uint32_t align_even_h264_dimension(uint32_t raw, uint32_t min_val, uint32_t max_val) {
    const uint32_t clamped = std::clamp(raw, min_val, max_val);
    return (clamped & ~1u);
}

inline std::pair<uint32_t, uint32_t> clamp_rdp_surface_dimensions(
    uint32_t raw_w,
    uint32_t raw_h,
    uint32_t min_w,
    uint32_t min_h,
    uint32_t max_w,
    uint32_t max_h,
    bool h264_enabled
) {
#ifndef __ANDROID__
    // Cisco OpenH264 v2.5.0 & Windows MF H.264 RDPGFX surfaces max out at 3840x2160 (32,400 <= 36,864 macroblocks).
    // Proportionally scale down >4K Retina viewports while preserving exact aspect ratio so RDPGFX never stalls.
    const uint32_t effective_max_w = h264_enabled ? std::min<uint32_t>(max_w, 3840u) : max_w;
    const uint32_t effective_max_h = h264_enabled ? std::min<uint32_t>(max_h, 2160u) : max_h;
#else
    (void)h264_enabled;
    const uint32_t effective_max_w = max_w;
    const uint32_t effective_max_h = max_h;
#endif
    double w = static_cast<double>(std::max(raw_w, min_w));
    double h = static_cast<double>(std::max(raw_h, min_h));
    if (w > static_cast<double>(effective_max_w) || h > static_cast<double>(effective_max_h)) {
        const double scale = std::min(
            static_cast<double>(effective_max_w) / w,
            static_cast<double>(effective_max_h) / h
        );
        w = std::max(static_cast<double>(min_w), std::round(w * scale));
        h = std::max(static_cast<double>(min_h), std::round(h * scale));
    }
    return {
        align_even_h264_dimension(static_cast<uint32_t>(w), min_w, effective_max_w),
        align_even_h264_dimension(static_cast<uint32_t>(h), min_h, effective_max_h)
    };
}

std::atomic<uint64_t>& global_remote_clip_batch_seq() {
    static auto* seq = new std::atomic<uint64_t>(1u);
    return *seq;
}

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
constexpr UINT32 kWinCfText = 1u;
constexpr UINT32 kWinCfOemText = 7u;
constexpr UINT32 kWinCfUnicodeText = 13u;

struct FreeRdpRuntimeEnvState {
    std::mutex mutex;
    bool addin_provider_registered{false};
    bool env_initialized{false};
    std::string cached_resolved_home;
};

FreeRdpRuntimeEnvState& freerdp_env_state() {
    static auto* state = new FreeRdpRuntimeEnvState();
    return *state;
}

void set_posix_and_winpr_env(const char* key, const std::string& value) {
    if (!key || value.empty()) {
        return;
    }
#if !defined(_WIN32)
    ::setenv(key, value.c_str(), 1);
#endif
    SetEnvironmentVariableA(key, value.c_str());
}

void append_utf8_cp(std::string& out, uint32_t cp) {
    if (cp <= 0x7Fu) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FFu) {
        out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0xFFFFu) {
        out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0x10FFFFu) {
        out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else {
        out.push_back('?');
    }
}

std::string decode_utf16le_clipboard_to_utf8(const BYTE* bytes, size_t byte_len) {
    if (!bytes || byte_len < 2u) {
        return {};
    }
    const size_t unit_count = byte_len / 2u;
    std::string utf8;
    utf8.reserve(unit_count);
    for (size_t i = 0; i < unit_count; ++i) {
        const uint16_t u0 = static_cast<uint16_t>(bytes[i * 2u]) |
                            (static_cast<uint16_t>(bytes[i * 2u + 1u]) << 8);
        if (u0 == 0u) {
            break;
        }
        if (u0 == '\r') {
            if (i + 1u < unit_count) {
                const uint16_t next_u = static_cast<uint16_t>(bytes[(i + 1u) * 2u]) |
                                        (static_cast<uint16_t>(bytes[(i + 1u) * 2u + 1u]) << 8);
                if (next_u == '\n') {
                    continue; // Normalize CRLF -> LF
                }
            }
            utf8.push_back('\n');
            continue;
        }
        uint32_t cp = u0;
        if (u0 >= 0xD800u && u0 <= 0xDBFFu && i + 1u < unit_count) {
            const uint16_t u1 = static_cast<uint16_t>(bytes[(i + 1u) * 2u]) |
                                (static_cast<uint16_t>(bytes[(i + 1u) * 2u + 1u]) << 8);
            if (u1 >= 0xDC00u && u1 <= 0xDFFFu) {
                cp = 0x10000u + (((static_cast<uint32_t>(u0) - 0xD800u) << 10) |
                                 (static_cast<uint32_t>(u1) - 0xDC00u));
                ++i;
            } else {
                cp = 0xFFFDu;
            }
        } else if (u0 >= 0xDC00u && u0 <= 0xDFFFu) {
            cp = 0xFFFDu;
        }
        append_utf8_cp(utf8, cp);
    }
    return utf8;
}

std::vector<BYTE> encode_utf8_to_utf16le_crlf(const std::string& utf8) {
    std::vector<uint16_t> units;
    units.reserve(utf8.size() + 8u);
    const auto* src = reinterpret_cast<const uint8_t*>(utf8.data());
    const size_t n = utf8.size();
    size_t i = 0;
    while (i < n) {
        const uint8_t b0 = src[i];
        if (b0 == '\r') {
            units.push_back('\r');
            units.push_back('\n');
            if (i + 1u < n && src[i + 1u] == '\n') {
                i += 2u;
            } else {
                i += 1u;
            }
            continue;
        }
        if (b0 == '\n') {
            units.push_back('\r');
            units.push_back('\n');
            ++i;
            continue;
        }
        uint32_t cp = 0xFFFDu;
        size_t step = 1u;
        if (b0 < 0x80u) {
            cp = b0;
        } else if ((b0 & 0xE0u) == 0xC0u && i + 1u < n && (src[i + 1u] & 0xC0u) == 0x80u) {
            cp = ((static_cast<uint32_t>(b0 & 0x1Fu) << 6) | static_cast<uint32_t>(src[i + 1u] & 0x3Fu));
            step = 2u;
        } else if ((b0 & 0xF0u) == 0xE0u && i + 2u < n &&
                   (src[i + 1u] & 0xC0u) == 0x80u && (src[i + 2u] & 0xC0u) == 0x80u) {
            cp = ((static_cast<uint32_t>(b0 & 0x0Fu) << 12) |
                  (static_cast<uint32_t>(src[i + 1u] & 0x3Fu) << 6) |
                  static_cast<uint32_t>(src[i + 2u] & 0x3Fu));
            step = 3u;
        } else if ((b0 & 0xF8u) == 0xF0u && i + 3u < n &&
                   (src[i + 1u] & 0xC0u) == 0x80u &&
                   (src[i + 2u] & 0xC0u) == 0x80u &&
                   (src[i + 3u] & 0xC0u) == 0x80u) {
            cp = ((static_cast<uint32_t>(b0 & 0x07u) << 18) |
                  (static_cast<uint32_t>(src[i + 1u] & 0x3Fu) << 12) |
                  (static_cast<uint32_t>(src[i + 2u] & 0x3Fu) << 6) |
                  static_cast<uint32_t>(src[i + 3u] & 0x3Fu));
            step = 4u;
        }
        if (cp <= 0xFFFFu) {
            units.push_back(static_cast<uint16_t>(cp));
        } else if (cp <= 0x10FFFFu) {
            const uint32_t adj = cp - 0x10000u;
            units.push_back(static_cast<uint16_t>(0xD800u + (adj >> 10)));
            units.push_back(static_cast<uint16_t>(0xDC00u + (adj & 0x3FFu)));
        }
        i += step;
    }
    units.push_back(0u); // Required UTF-16LE NUL terminator for CF_UNICODETEXT

    std::vector<BYTE> out(units.size() * 2u);
    for (size_t idx = 0; idx < units.size(); ++idx) {
        out[idx * 2u] = static_cast<BYTE>(units[idx] & 0xFFu);
        out[idx * 2u + 1u] = static_cast<BYTE>((units[idx] >> 8) & 0xFFu);
    }
    return out;
}

std::string detect_android_fallback_files_dir() {
#ifdef __ANDROID__
    std::ifstream cmdline("/proc/self/cmdline", std::ios::binary);
    if (cmdline.is_open()) {
        std::string pkg;
        std::getline(cmdline, pkg, '\0');
        const auto colon = pkg.find(':');
        if (colon != std::string::npos) {
            pkg.resize(colon);
        }
        if (!pkg.empty() && pkg.find('/') == std::string::npos) {
            std::error_code ec;
            const std::filesystem::path user0_dir = std::filesystem::path("/data/user/0") / pkg / "files";
            if (std::filesystem::exists(user0_dir, ec)) {
                return (user0_dir / "freerdp_runtime").string();
            }
            const std::filesystem::path legacy_dir = std::filesystem::path("/data/data") / pkg / "files";
            if (std::filesystem::exists(legacy_dir, ec)) {
                return (legacy_dir / "freerdp_runtime").string();
            }
        }
    }
#endif
    return {};
}

/**
 * Ensures `HOME`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_CACHE_HOME`, and `TMPDIR` point to
 * writable app-private directories ONCE per process so concurrent FreeRDP sessions never race
 * `setenv()` against `getenv()` on background threads.
 */
bool ensure_freerdp_runtime_environment(const std::string& preferred_home_dir, std::string* out_resolved_home) {
    auto& env = freerdp_env_state();
    std::lock_guard<std::mutex> lock(env.mutex);

    if (env.env_initialized && !env.cached_resolved_home.empty()) {
        if (out_resolved_home) {
            *out_resolved_home = env.cached_resolved_home;
        }
        return true;
    }

    std::string base_dir = preferred_home_dir;
    if (base_dir.empty()) {
        const char* existing_home = std::getenv("HOME");
        if (existing_home && existing_home[0] != '\0' && std::strcmp(existing_home, "/") != 0) {
            base_dir = existing_home;
        }
    }
    if (base_dir.empty()) {
        base_dir = detect_android_fallback_files_dir();
    }
    if (base_dir.empty()) {
        std::error_code tmp_ec;
        const auto sys_tmp = std::filesystem::temp_directory_path(tmp_ec);
        if (!tmp_ec && !sys_tmp.empty()) {
            base_dir = (sys_tmp / "nexusts_freerdp").string();
        }
    }
    if (base_dir.empty()) {
        return false;
    }

    const std::filesystem::path home_path(base_dir);
    const std::filesystem::path xdg_config = home_path / ".config";
    const std::filesystem::path freerdp_certs = xdg_config / "freerdp" / "certs";
    const std::filesystem::path freerdp_server = xdg_config / "freerdp" / "server";
    const std::filesystem::path xdg_data = home_path / ".local" / "share";
    const std::filesystem::path xdg_cache = home_path / ".cache";
    const std::filesystem::path tmp_dir = home_path / "tmp";

    std::error_code ec;
    std::filesystem::create_directories(freerdp_certs, ec);
    std::filesystem::create_directories(freerdp_server, ec);
    std::filesystem::create_directories(xdg_data, ec);
    std::filesystem::create_directories(xdg_cache, ec);
    std::filesystem::create_directories(tmp_dir, ec);

    set_posix_and_winpr_env("HOME", home_path.string());
    set_posix_and_winpr_env("XDG_CONFIG_HOME", xdg_config.string());
    set_posix_and_winpr_env("XDG_DATA_HOME", xdg_data.string());
    set_posix_and_winpr_env("XDG_CACHE_HOME", xdg_cache.string());
    set_posix_and_winpr_env("TMPDIR", tmp_dir.string());

    if (!env.addin_provider_registered) {
        if (freerdp_register_addin_provider(freerdp_channels_load_static_addin_entry, 0) == CHANNEL_RC_OK) {
            env.addin_provider_registered = true;
            NX_RDP_LOGI("Registered FreeRDP 3.x static builtin channel addin provider");
        } else {
            NX_RDP_LOGE("freerdp_register_addin_provider(freerdp_channels_load_static_addin_entry) returned non-OK");
        }
    }

    if (std::filesystem::exists(home_path, ec)) {
        env.cached_resolved_home = home_path.string();
        env.env_initialized = true;
        if (out_resolved_home) {
            *out_resolved_home = env.cached_resolved_home;
        }
        return true;
    }
    return false;
}

char* dup_cstr_malloc(const std::string& s) {
    char* copy = static_cast<char*>(std::calloc(s.size() + 1u, sizeof(char)));
    if (copy && !s.empty()) {
        std::memcpy(copy, s.data(), s.size());
    }
    return copy;
}

std::string format_freerdp_last_error(rdpContext* context, const std::string& host, uint16_t port) {
    if (!context) {
        return "CONNECT_TRANSPORT_FAILED: FreeRDP context unavailable (" + host + ":" + std::to_string(port) + ")";
    }
    const UINT32 err_code = freerdp_get_last_error(context);
    if (err_code == 0u) {
        return "";
    }
    const char* err_name = freerdp_get_last_error_name(err_code);
    const char* err_desc = freerdp_get_last_error_string(err_code);
    char hex_buf[24]{};
    std::snprintf(hex_buf, sizeof(hex_buf), "0x%08X", static_cast<unsigned int>(err_code));
    std::string out = (err_name && err_name[0] != '\0') ? std::string(err_name) : "FREERDP_ERROR";
    out += " (";
    out += hex_buf;
    out += ")";
    if (err_desc && err_desc[0] != '\0') {
        out += ": ";
        out += err_desc;
    }
    return out;
}

struct FreeRdpCallbackState {
    RdpSessionContext* owner{nullptr};
    bool ignore_cert_warnings{false};
    bool cert_store_writable{false};
    std::string username;
    std::string password;
    std::string domain;
};

struct FreeRdpCallbackRegistry {
    std::mutex mutex;
    std::unordered_map<freerdp*, FreeRdpCallbackState> callbacks;
};

FreeRdpCallbackRegistry& freerdp_cb_registry() {
    static auto* reg = new FreeRdpCallbackRegistry();
    return *reg;
}

void register_freerdp_callbacks(
    freerdp* instance,
    RdpSessionContext* owner,
    bool ignore_certs,
    bool cert_store_writable,
    const std::string& username,
    const std::string& password,
    const std::string& domain
) {
    auto& reg = freerdp_cb_registry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    reg.callbacks[instance] = FreeRdpCallbackState{
        owner,
        ignore_certs,
        cert_store_writable,
        username,
        password,
        domain
    };
}

void unregister_freerdp_callbacks(freerdp* instance) {
    auto& reg = freerdp_cb_registry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    reg.callbacks.erase(instance);
}

FreeRdpCallbackState lookup_freerdp_callbacks(freerdp* instance) {
    auto& reg = freerdp_cb_registry();
    std::lock_guard<std::mutex> lock(reg.mutex);
    auto it = reg.callbacks.find(instance);
    return (it != reg.callbacks.end()) ? it->second : FreeRdpCallbackState{};
}

constexpr UINT32 kClientFormatFileGroupDescriptorW = 0xC080u;
constexpr uint64_t kMaxLegacyNonHugeFileBytes = 0x7FFFFFFFULL;
constexpr BYTE kEmptyClipPayloadByte = 0u;

bool equals_ascii_icase(const char* a, const char* b) noexcept {
    if (!a || !b) {
        return false;
    }
    while (*a != '\0' && *b != '\0') {
        unsigned char ca = static_cast<unsigned char>(*a);
        unsigned char cb = static_cast<unsigned char>(*b);
        if (ca >= 'A' && ca <= 'Z') {
            ca = static_cast<unsigned char>(ca - 'A' + 'a');
        }
        if (cb >= 'A' && cb <= 'Z') {
            cb = static_cast<unsigned char>(cb - 'A' + 'a');
        }
        if (ca != cb) {
            return false;
        }
        ++a;
        ++b;
    }
    return *a == '\0' && *b == '\0';
}

UINT cliprdr_send_client_format_list(
    CliprdrClientContext* cliprdr,
    RdpSessionContext* owner,
    bool is_initial_monitor_ready,
    bool* out_sent_wire_pdu = nullptr
) {
    if (out_sent_wire_pdu) {
        *out_sent_wire_pdu = false;
    }
    if (!cliprdr || !cliprdr->ClientFormatList) {
        return CHANNEL_RC_OK;
    }
    const RdpClipboardContentKind kind =
        owner ? owner->channels().local_clipboard_kind() : RdpClipboardContentKind::None;

    // Per [MS-RDPECLIP] & FreeRDP xf_cliprdr/wlf_cliprdr, announce ONLY "FileGroupDescriptorW"
    // when CB_STREAM_FILECLIP_ENABLED is active; advertising "FileContents" in CB_FORMAT_LIST
    // causes some Windows consumers to issue CB_FORMAT_DATA_REQUEST(FileContents) (TYMED_HGLOBAL)
    // instead of CB_FILECONTENTS_REQUEST (TYMED_ISTREAM).
    char fgd_name[] = "FileGroupDescriptorW";
    CLIPRDR_FORMAT formats[2]{};
    UINT32 num_formats = 0u;

    if (kind == RdpClipboardContentKind::Files && owner) {
        const auto files = owner->channels().local_clipboard_files();
        if (files && !files->empty()) {
            formats[0].formatId = kClientFormatFileGroupDescriptorW;
            formats[0].formatName = fgd_name;
            num_formats = 1u;
        }
    } else if (kind == RdpClipboardContentKind::Text && owner) {
        if (!owner->channels().local_clipboard_text().empty()) {
            formats[0].formatId = kWinCfUnicodeText;
            formats[0].formatName = nullptr;
            formats[1].formatId = kWinCfText;
            formats[1].formatName = nullptr;
            num_formats = 2u;
        }
    }

    // FreeRDP's cliprdr_client_format_list() drops empty format lists after initial MonitorReady
    // without transmitting a wire PDU, so avoid waiting on a CB_FORMAT_LIST_RESPONSE that won't come.
    if (!is_initial_monitor_ready && num_formats == 0u) {
        return CHANNEL_RC_OK;
    }

    CLIPRDR_FORMAT_LIST format_list{};
    format_list.common.msgType = CB_FORMAT_LIST;
    format_list.common.msgFlags = 0;
    format_list.numFormats = num_formats;
    format_list.formats = (num_formats > 0u) ? formats : nullptr;
    const UINT rc = cliprdr->ClientFormatList(cliprdr, &format_list);
    if (out_sent_wire_pdu && rc == CHANNEL_RC_OK) {
        *out_sent_wire_pdu = true;
    }
    return rc;
}

UINT cliprdr_on_monitor_ready(CliprdrClientContext* cliprdr, const CLIPRDR_MONITOR_READY* /*monitor_ready*/) {
    if (!cliprdr) {
        return ERROR_INVALID_PARAMETER;
    }
    if (cliprdr->ClientCapabilities) {
        CLIPRDR_GENERAL_CAPABILITY_SET gen_caps{};
        gen_caps.capabilitySetType = CB_CAPSTYPE_GENERAL;
        gen_caps.capabilitySetLength = CB_CAPSTYPE_GENERAL_LEN;
        gen_caps.version = CB_CAPS_VERSION_2;
        gen_caps.generalFlags =
            CB_USE_LONG_FORMAT_NAMES |
            CB_STREAM_FILECLIP_ENABLED |
            CB_FILECLIP_NO_FILE_PATHS |
            CB_CAN_LOCK_CLIPDATA |
            CB_HUGE_FILE_SUPPORT_ENABLED;

        CLIPRDR_CAPABILITIES caps{};
        caps.common.msgType = CB_CLIP_CAPS;
        caps.cCapabilitiesSets = 1u;
        caps.capabilitySets = reinterpret_cast<CLIPRDR_CAPABILITY_SET*>(&gen_caps);
        cliprdr->ClientCapabilities(cliprdr, &caps);
    }
    auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom);
    if (owner) {
        // Clear any pre-MonitorReady pending_clipboard_announce_ BEFORE sending the initial
        // CB_FORMAT_LIST so the event loop never sends a back-to-back duplicate format list.
        owner->on_cliprdr_monitor_ready_sent();
    }
    // Per [MS-RDPECLIP] 1.3.1.1, ClientFormatList MUST always be sent in response to MonitorReady
    // (even with 0 formats when the local clipboard is initially empty) so rdpclip.exe activates CLIPRDR.
    const UINT rc = cliprdr_send_client_format_list(cliprdr, owner, /*is_initial_monitor_ready=*/true);
    if (owner) {
        owner->set_cliprdr_ready(true);
    }
    return rc;
}

UINT cliprdr_on_server_capabilities(CliprdrClientContext* cliprdr, const CLIPRDR_CAPABILITIES* caps) {
    if (!cliprdr || !caps || !caps->capabilitySets) {
        return CHANNEL_RC_OK;
    }
    auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom);
    if (!owner) {
        return CHANNEL_RC_OK;
    }
    const auto* cursor = reinterpret_cast<const BYTE*>(caps->capabilitySets);
    for (UINT32 i = 0u; i < caps->cCapabilitiesSets; ++i) {
        const auto* set_hdr = reinterpret_cast<const CLIPRDR_CAPABILITY_SET*>(cursor);
        if (set_hdr->capabilitySetLength < 4u) {
            break;
        }
        if (set_hdr->capabilitySetType == CB_CAPSTYPE_GENERAL &&
            set_hdr->capabilitySetLength >= CB_CAPSTYPE_GENERAL_LEN) {
            const auto* gen = reinterpret_cast<const CLIPRDR_GENERAL_CAPABILITY_SET*>(cursor);
            owner->on_cliprdr_server_capabilities(gen->generalFlags);
        }
        cursor += set_hdr->capabilitySetLength;
    }
    return CHANNEL_RC_OK;
}

UINT cliprdr_on_server_format_list(CliprdrClientContext* cliprdr, const CLIPRDR_FORMAT_LIST* format_list) {
    if (!cliprdr || !format_list) {
        return ERROR_INVALID_PARAMETER;
    }
    if (cliprdr->ClientFormatListResponse) {
        CLIPRDR_FORMAT_LIST_RESPONSE resp{};
        resp.common.msgType = CB_FORMAT_LIST_RESPONSE;
        resp.common.msgFlags = CB_RESPONSE_OK;
        cliprdr->ClientFormatListResponse(cliprdr, &resp);
    }

    auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom);
    if (owner) {
        owner->channels().mark_remote_clipboard_owner_changed();
    }

    UINT32 fgd_format_id = 0u;
    bool fgd_is_ansi = false;
    bool saw_unicode_fgd = false;
    bool has_unicode = false;
    bool has_ansi = false;
    for (UINT32 i = 0; i < format_list->numFormats; ++i) {
        const UINT32 fmt_id = format_list->formats[i].formatId;
        const char* fmt_name = format_list->formats[i].formatName;
        if (fmt_name &&
            (equals_ascii_icase(fmt_name, "FileGroupDescriptorW") ||
             equals_ascii_icase(fmt_name, "FileGroupDescrip"))) {
            fgd_format_id = fmt_id;
            fgd_is_ansi = false;
            saw_unicode_fgd = true;
        } else if (fmt_name && !saw_unicode_fgd &&
                   equals_ascii_icase(fmt_name, "FileGroupDescriptor")) {
            fgd_format_id = fmt_id;
            fgd_is_ansi = true;
        } else if (fmt_id == kWinCfUnicodeText) {
            has_unicode = true;
        } else if (fmt_id == kWinCfText || fmt_id == kWinCfOemText) {
            has_ansi = true;
        }
    }

    if (fgd_format_id != 0u && owner && cliprdr->ClientFormatDataRequest) {
        owner->begin_remote_file_list_fetch(cliprdr, fgd_format_id, fgd_is_ansi);
        cliprdr->lastRequestedFormatId = fgd_format_id;
        CLIPRDR_FORMAT_DATA_REQUEST req{};
        req.common.msgType = CB_FORMAT_DATA_REQUEST;
        req.requestedFormatId = fgd_format_id;
        return cliprdr->ClientFormatDataRequest(cliprdr, &req);
    }

    if ((has_unicode || has_ansi) && cliprdr->ClientFormatDataRequest) {
        const UINT32 req_fmt = has_unicode ? kWinCfUnicodeText : kWinCfText;
        cliprdr->lastRequestedFormatId = req_fmt;
        CLIPRDR_FORMAT_DATA_REQUEST req{};
        req.common.msgType = CB_FORMAT_DATA_REQUEST;
        req.requestedFormatId = req_fmt;
        return cliprdr->ClientFormatDataRequest(cliprdr, &req);
    }

    // Wake host UI so remote non-text/non-file clipboard updates (e.g. screenshots) clear stale mirror state
    if (owner) {
        owner->presenter().notify_frame_ready();
    }
    return CHANNEL_RC_OK;
}

UINT cliprdr_on_server_format_list_response(
    CliprdrClientContext* cliprdr,
    const CLIPRDR_FORMAT_LIST_RESPONSE* resp
) {
    if (!cliprdr) {
        return CHANNEL_RC_OK;
    }
    if (auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom)) {
        const bool ok = resp && ((resp->common.msgFlags & CB_RESPONSE_OK) != 0u);
        owner->on_cliprdr_server_format_list_response(ok);
    }
    return CHANNEL_RC_OK;
}

UINT cliprdr_on_server_lock_clipboard_data(
    CliprdrClientContext* cliprdr,
    const CLIPRDR_LOCK_CLIPBOARD_DATA* lock_data
) {
    if (cliprdr && lock_data) {
        if (auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom)) {
            owner->lock_local_clipboard_files(lock_data->clipDataId);
        }
    }
    return CHANNEL_RC_OK;
}

UINT cliprdr_on_server_unlock_clipboard_data(
    CliprdrClientContext* cliprdr,
    const CLIPRDR_UNLOCK_CLIPBOARD_DATA* unlock_data
) {
    if (cliprdr && unlock_data) {
        if (auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom)) {
            owner->unlock_local_clipboard_files(unlock_data->clipDataId);
        }
    }
    return CHANNEL_RC_OK;
}

UINT cliprdr_on_server_format_data_request(
    CliprdrClientContext* cliprdr,
    const CLIPRDR_FORMAT_DATA_REQUEST* request
) {
    if (!cliprdr || !request || !cliprdr->ClientFormatDataResponse) {
        return ERROR_INVALID_PARAMETER;
    }
    auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom);

    CLIPRDR_FORMAT_DATA_RESPONSE resp{};
    resp.common.msgType = CB_FORMAT_DATA_RESPONSE;

    if (owner && request->requestedFormatId == kClientFormatFileGroupDescriptorW) {
        const auto files = owner->bind_active_file_group_descriptor_snapshot();
        if (files && !files->empty()) {
            const bool huge_ok =
                (owner->cliprdr_server_capabilities() & CB_HUGE_FILE_SUPPORT_ENABLED) != 0u;
            bool size_compatible = true;
            if (!huge_ok) {
                for (const auto& entry : *files) {
                    if (!entry.is_directory && entry.file_size > kMaxLegacyNonHugeFileBytes) {
                        size_compatible = false;
                        break;
                    }
                }
            }
            if (size_compatible) {
                const std::vector<uint8_t> packed = serialize_cliprdr_file_list(*files);
                resp.common.msgFlags = CB_RESPONSE_OK;
                resp.common.dataLen = static_cast<UINT32>(packed.size());
                resp.requestedFormatData = packed.data();
                return cliprdr->ClientFormatDataResponse(cliprdr, &resp);
            }
        }
    }

    const std::string local_utf8 = owner ? owner->channels().local_clipboard_text() : std::string();
    if (!local_utf8.empty() && request->requestedFormatId == kWinCfUnicodeText) {
        const std::vector<BYTE> utf16 = encode_utf8_to_utf16le_crlf(local_utf8);
        resp.common.msgFlags = CB_RESPONSE_OK;
        resp.common.dataLen = static_cast<UINT32>(utf16.size());
        resp.requestedFormatData = utf16.data();
        return cliprdr->ClientFormatDataResponse(cliprdr, &resp);
    }
    if (!local_utf8.empty() &&
        (request->requestedFormatId == kWinCfText || request->requestedFormatId == kWinCfOemText)) {
        std::vector<BYTE> ansi;
        ansi.reserve(local_utf8.size() + 8u);
        for (size_t i = 0; i < local_utf8.size(); ++i) {
            if (local_utf8[i] == '\n' && (i == 0 || local_utf8[i - 1] != '\r')) {
                ansi.push_back(static_cast<BYTE>('\r'));
            }
            ansi.push_back(static_cast<BYTE>(local_utf8[i]));
        }
        ansi.push_back(0u);
        resp.common.msgFlags = CB_RESPONSE_OK;
        resp.common.dataLen = static_cast<UINT32>(ansi.size());
        resp.requestedFormatData = ansi.data();
        return cliprdr->ClientFormatDataResponse(cliprdr, &resp);
    }

    resp.common.msgFlags = CB_RESPONSE_FAIL;
    resp.common.dataLen = 0u;
    resp.requestedFormatData = &kEmptyClipPayloadByte;
    return cliprdr->ClientFormatDataResponse(cliprdr, &resp);
}

UINT cliprdr_send_file_contents_fail(CliprdrClientContext* cliprdr, UINT32 stream_id) {
    CLIPRDR_FILE_CONTENTS_RESPONSE resp{};
    resp.common.msgType = CB_FILECONTENTS_RESPONSE;
    resp.common.msgFlags = CB_RESPONSE_FAIL;
    resp.common.dataLen = 4u;
    resp.streamId = stream_id;
    resp.cbRequested = 0u;
    resp.requestedData = &kEmptyClipPayloadByte;
    return cliprdr->ClientFileContentsResponse(cliprdr, &resp);
}

UINT cliprdr_on_server_file_contents_request(
    CliprdrClientContext* cliprdr,
    const CLIPRDR_FILE_CONTENTS_REQUEST* request
) {
    if (!cliprdr || !request || !cliprdr->ClientFileContentsResponse) {
        return ERROR_INVALID_PARAMETER;
    }
    auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom);
    if (!owner) {
        return cliprdr_send_file_contents_fail(cliprdr, request->streamId);
    }
    const UINT32 mode_flags = request->dwFlags & (FILECONTENTS_SIZE | FILECONTENTS_RANGE);
    if (mode_flags != FILECONTENTS_SIZE && mode_flags != FILECONTENTS_RANGE) {
        return cliprdr_send_file_contents_fail(cliprdr, request->streamId);
    }

    const auto files = owner->resolve_local_clipboard_files(
        request->haveClipDataId != FALSE,
        request->clipDataId
    );
    if (!files || request->listIndex >= files->size()) {
        return cliprdr_send_file_contents_fail(cliprdr, request->streamId);
    }
    const RdpClipboardFileEntry& entry = (*files)[request->listIndex];
    if (entry.is_directory) {
        return cliprdr_send_file_contents_fail(cliprdr, request->streamId);
    }

    if (mode_flags == FILECONTENTS_SIZE) {
        if (request->nPositionLow != 0u || request->nPositionHigh != 0u) {
            return cliprdr_send_file_contents_fail(cliprdr, request->streamId);
        }
        uint64_t actual_size = entry.file_size;
        struct stat st {};
        if (::stat(entry.local_posix_path.c_str(), &st) == 0 && st.st_size >= 0) {
            actual_size = static_cast<uint64_t>(st.st_size);
        }
        BYTE size_le[8]{};
        for (int b = 0; b < 8; ++b) {
            size_le[b] = static_cast<BYTE>((actual_size >> (b * 8)) & 0xFFULL);
        }
        CLIPRDR_FILE_CONTENTS_RESPONSE resp{};
        resp.common.msgType = CB_FILECONTENTS_RESPONSE;
        resp.common.msgFlags = CB_RESPONSE_OK;
        resp.common.dataLen = 12u;
        resp.streamId = request->streamId;
        resp.cbRequested = 8u;
        resp.requestedData = size_le;
        return cliprdr->ClientFileContentsResponse(cliprdr, &resp);
    }

    const uint64_t offset =
        (static_cast<uint64_t>(request->nPositionHigh) << 32) |
        static_cast<uint64_t>(request->nPositionLow);
    std::vector<uint8_t> chunk;
    if (!read_local_clipboard_file_range(
            entry.local_posix_path,
            offset,
            request->cbRequested,
            chunk
        )) {
        return cliprdr_send_file_contents_fail(cliprdr, request->streamId);
    }

    CLIPRDR_FILE_CONTENTS_RESPONSE resp{};
    resp.common.msgType = CB_FILECONTENTS_RESPONSE;
    resp.common.msgFlags = CB_RESPONSE_OK;
    resp.common.dataLen = static_cast<UINT32>(4u + chunk.size());
    resp.streamId = request->streamId;
    resp.cbRequested = static_cast<UINT32>(chunk.size());
    resp.requestedData = chunk.empty() ? &kEmptyClipPayloadByte : chunk.data();
    return cliprdr->ClientFileContentsResponse(cliprdr, &resp);
}

UINT cliprdr_on_server_format_data_response(
    CliprdrClientContext* cliprdr,
    const CLIPRDR_FORMAT_DATA_RESPONSE* response
) {
    if (!cliprdr || !response) {
        return ERROR_INVALID_PARAMETER;
    }
    auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom);
    if (!owner) {
        return CHANNEL_RC_OK;
    }

    const UINT32 fgd_fmt = owner->server_file_group_format_id();
    if (fgd_fmt != 0u && cliprdr->lastRequestedFormatId == fgd_fmt) {
        if ((response->common.msgFlags & CB_RESPONSE_OK) == 0u ||
            !response->requestedFormatData ||
            response->common.dataLen == 0u) {
            owner->handle_remote_file_group_descriptor_response(cliprdr, nullptr, 0u);
            return CHANNEL_RC_OK;
        }
        owner->handle_remote_file_group_descriptor_response(
            cliprdr,
            response->requestedFormatData,
            response->common.dataLen
        );
        return CHANNEL_RC_OK;
    }

    if ((response->common.msgFlags & CB_RESPONSE_OK) == 0 ||
        !response->requestedFormatData ||
        response->common.dataLen == 0u) {
        return CHANNEL_RC_OK;
    }

    std::string decoded;
    if (cliprdr->lastRequestedFormatId == kWinCfUnicodeText) {
        decoded = decode_utf16le_clipboard_to_utf8(response->requestedFormatData, response->common.dataLen);
    } else {
        const auto* chars = reinterpret_cast<const char*>(response->requestedFormatData);
        size_t len = 0u;
        while (len < response->common.dataLen && chars[len] != '\0') {
            if (chars[len] == '\r' && len + 1u < response->common.dataLen && chars[len + 1u] == '\n') {
                ++len;
                continue;
            }
            decoded.push_back(chars[len] == '\r' ? '\n' : chars[len]);
            ++len;
        }
    }
    if (!decoded.empty()) {
        owner->channels().on_remote_clipboard_text(decoded);
        owner->presenter().notify_frame_ready();
    }
    return CHANNEL_RC_OK;
}

UINT cliprdr_on_server_file_contents_response(
    CliprdrClientContext* cliprdr,
    const CLIPRDR_FILE_CONTENTS_RESPONSE* response
) {
    if (!cliprdr || !response) {
        return ERROR_INVALID_PARAMETER;
    }
    if (auto* owner = static_cast<RdpSessionContext*>(cliprdr->custom)) {
        const bool ok = (response->common.msgFlags & CB_RESPONSE_OK) != 0u;
        owner->handle_remote_file_contents_response(
            cliprdr,
            response->streamId,
            ok,
            response->requestedData,
            response->cbRequested
        );
    }
    return CHANNEL_RC_OK;
}

void freerdp_on_channel_connected(void* context, const ChannelConnectedEventArgs* e) {
    if (!context || !e || !e->name) {
        return;
    }
    auto* rdp_ctx = static_cast<rdpContext*>(context);
    if (!rdp_ctx->instance) {
        return;
    }
    if (std::strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0) {
        if (rdp_ctx->gdi && e->pInterface) {
            if (gdi_graphics_pipeline_init(rdp_ctx->gdi, static_cast<RdpgfxClientContext*>(e->pInterface))) {
                NX_RDP_LOGI("Attached FreeRDP RDPGFX graphics pipeline (%s)", e->name);
            } else {
                NX_RDP_LOGE("gdi_graphics_pipeline_init failed for channel %s", e->name);
            }
        }
    } else if (std::strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0) {
        const auto cb = lookup_freerdp_callbacks(rdp_ctx->instance);
        if (cb.owner) {
            cb.owner->set_disp_context(e->pInterface);
            NX_RDP_LOGI("Attached FreeRDP DISP_DVC dynamic display channel (%s)", e->name);
        }
    } else if (std::strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0 && e->pInterface) {
        const auto cb = lookup_freerdp_callbacks(rdp_ctx->instance);
        if (cb.owner) {
            auto* cliprdr = static_cast<CliprdrClientContext*>(e->pInterface);
            cliprdr->custom = cb.owner;
            cliprdr->MonitorReady = &cliprdr_on_monitor_ready;
            cliprdr->ServerCapabilities = &cliprdr_on_server_capabilities;
            cliprdr->ServerFormatList = &cliprdr_on_server_format_list;
            cliprdr->ServerFormatListResponse = &cliprdr_on_server_format_list_response;
            cliprdr->ServerLockClipboardData = &cliprdr_on_server_lock_clipboard_data;
            cliprdr->ServerUnlockClipboardData = &cliprdr_on_server_unlock_clipboard_data;
            cliprdr->ServerFormatDataRequest = &cliprdr_on_server_format_data_request;
            cliprdr->ServerFormatDataResponse = &cliprdr_on_server_format_data_response;
            cliprdr->ServerFileContentsRequest = &cliprdr_on_server_file_contents_request;
            cliprdr->ServerFileContentsResponse = &cliprdr_on_server_file_contents_response;
            cb.owner->set_cliprdr_context(cliprdr);
            NX_RDP_LOGI("Attached FreeRDP CLIPRDR bidirectional text+file clipboard channel (%s)", e->name);
        }
    }
}

void freerdp_on_channel_disconnected(void* context, const ChannelDisconnectedEventArgs* e) {
    if (!context || !e || !e->name) {
        return;
    }
    auto* rdp_ctx = static_cast<rdpContext*>(context);
    if (!rdp_ctx->instance) {
        return;
    }
    if (std::strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0) {
        if (rdp_ctx->gdi && e->pInterface) {
            gdi_graphics_pipeline_uninit(rdp_ctx->gdi, static_cast<RdpgfxClientContext*>(e->pInterface));
        }
    } else if (std::strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0) {
        const auto cb = lookup_freerdp_callbacks(rdp_ctx->instance);
        if (cb.owner) {
            cb.owner->set_disp_context(nullptr);
        }
    } else if (std::strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) {
        const auto cb = lookup_freerdp_callbacks(rdp_ctx->instance);
        if (cb.owner) {
            cb.owner->set_cliprdr_context(nullptr);
        }
    }
}

BOOL freerdp_on_desktop_resize(rdpContext* context) {
    if (!context || !context->gdi || !context->settings) {
        return FALSE;
    }
    const UINT32 new_w = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
    const UINT32 new_h = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);
    if (!gdi_resize(context->gdi, new_w, new_h)) {
        NX_RDP_LOGE("FreeRDP gdi_resize(%u, %u) failed", new_w, new_h);
        return FALSE;
    }
    const auto cb = lookup_freerdp_callbacks(context->instance);
    if (cb.owner && new_w > 0 && new_h > 0) {
        cb.owner->presenter().resize_shadow_framebuffer(new_w, new_h);
        cb.owner->decoder().reconfigure_resolution(new_w, new_h);
    }
    return TRUE;
}

BOOL freerdp_on_begin_paint(rdpContext* context) {
    if (!context || !context->gdi || !context->gdi->primary || !context->gdi->primary->hdc ||
        !context->gdi->primary->hdc->hwnd || !context->gdi->primary->hdc->hwnd->invalid) {
        return FALSE;
    }
    context->gdi->primary->hdc->hwnd->invalid->null = TRUE;
    context->gdi->primary->hdc->hwnd->ninvalid = 0;
    return TRUE;
}

BOOL freerdp_on_end_paint(rdpContext* context) {
    if (!context || !context->gdi || !context->gdi->primary_buffer) {
        return FALSE;
    }
    rdpGdi* gdi = context->gdi;
    HGDI_WND hwnd =
        (gdi->primary && gdi->primary->hdc)
            ? gdi->primary->hdc->hwnd
            : nullptr;
    const HGDI_RGN invalid = hwnd ? hwnd->invalid : nullptr;

    if (invalid && invalid->null) {
        return TRUE;
    }

    const auto cb = lookup_freerdp_callbacks(context->instance);
    if (cb.owner && gdi->width > 0 && gdi->height > 0) {
        const uint32_t fb_w = static_cast<uint32_t>(gdi->width);
        const uint32_t fb_h = static_cast<uint32_t>(gdi->height);
        const uint32_t stride = gdi->stride > 0 ? gdi->stride : (fb_w * 4u);

        constexpr size_t kMaxInlineDirtyRects = 32u;
        RdpDirtyRect dirty_rects[kMaxInlineDirtyRects]{};
        size_t rect_count = 0u;

        if (hwnd && hwnd->cinvalid && hwnd->ninvalid > 0 &&
            static_cast<size_t>(hwnd->ninvalid) <= kMaxInlineDirtyRects) {
            for (int i = 0; i < hwnd->ninvalid; ++i) {
                const GDI_RGN& rgn = hwnd->cinvalid[i];
                if (rgn.null || rgn.w <= 0 || rgn.h <= 0) {
                    continue;
                }
                const int32_t ix = std::max<int32_t>(0, rgn.x);
                const int32_t iy = std::max<int32_t>(0, rgn.y);
                const int32_t right = std::min<int32_t>(gdi->width, rgn.x + rgn.w);
                const int32_t bottom = std::min<int32_t>(gdi->height, rgn.y + rgn.h);
                if (right > ix && bottom > iy) {
                    dirty_rects[rect_count++] = RdpDirtyRect{
                        static_cast<uint32_t>(ix),
                        static_cast<uint32_t>(iy),
                        static_cast<uint32_t>(right - ix),
                        static_cast<uint32_t>(bottom - iy)
                    };
                }
            }
        }

        if (rect_count == 0u) {
            uint32_t dx = 0u;
            uint32_t dy = 0u;
            uint32_t dw = fb_w;
            uint32_t dh = fb_h;
            if (invalid && invalid->w > 0 && invalid->h > 0) {
                const int32_t ix = std::max<int32_t>(0, invalid->x);
                const int32_t iy = std::max<int32_t>(0, invalid->y);
                const int32_t right = std::min<int32_t>(gdi->width, invalid->x + invalid->w);
                const int32_t bottom = std::min<int32_t>(gdi->height, invalid->y + invalid->h);
                if (right > ix && bottom > iy) {
                    dx = static_cast<uint32_t>(ix);
                    dy = static_cast<uint32_t>(iy);
                    dw = static_cast<uint32_t>(right - ix);
                    dh = static_cast<uint32_t>(bottom - iy);
                }
            }
            dirty_rects[0] = RdpDirtyRect{dx, dy, dw, dh};
            rect_count = 1u;
        }

        cb.owner->presenter().stage_gdi_dirty_rects(
            gdi->primary_buffer,
            fb_w,
            fb_h,
            stride,
            dirty_rects,
            rect_count
        );
    }
    if (invalid) {
        invalid->null = TRUE;
        if (hwnd) {
            hwnd->ninvalid = 0;
        }
    }
    return TRUE;
}

BOOL freerdp_on_pre_connect(freerdp* instance) {
    if (!instance || !instance->context || !instance->context->settings) {
        return FALSE;
    }
    if (instance->context->pubSub) {
        PubSub_SubscribeChannelConnected(instance->context->pubSub, &freerdp_on_channel_connected);
        PubSub_SubscribeChannelDisconnected(instance->context->pubSub, &freerdp_on_channel_disconnected);
    }
    if (instance->context->channels) {
        if (!freerdp_client_load_addins(instance->context->channels, instance->context->settings)) {
            NX_RDP_LOGE("freerdp_client_load_addins failed; continuing with core channels");
        }
    }
    return TRUE;
}

struct NexusRdpPointer {
    rdpPointer base;
    uint32_t decoded_w;
    uint32_t decoded_h;
    uint32_t hot_x;
    uint32_t hot_y;
    std::vector<uint8_t>* premul_bgra;
};

BOOL nexusts_pointer_new(rdpContext* context, rdpPointer* pointer) {
    if (!pointer) {
        return FALSE;
    }
    auto* np = reinterpret_cast<NexusRdpPointer*>(pointer);
    np->decoded_w = 0u;
    np->decoded_h = 0u;
    np->hot_x = 0u;
    np->hot_y = 0u;
    np->premul_bgra = nullptr;

    if (pointer->width == 0u || pointer->height == 0u ||
        pointer->width > 512u || pointer->height > 512u) {
        return TRUE;
    }

    const uint32_t w = pointer->width;
    const uint32_t h = pointer->height;
    const size_t pixel_count = static_cast<size_t>(w) * static_cast<size_t>(h);
    auto* buf = new std::vector<uint8_t>(pixel_count * 4u, 0u);
    const gdiPalette* palette = (context && context->gdi) ? &context->gdi->palette : nullptr;

    const BOOL decoded = freerdp_image_copy_from_pointer_data(
        buf->data(),
        PIXEL_FORMAT_BGRA32,
        w * 4u,
        0u,
        0u,
        w,
        h,
        pointer->xorMaskData,
        pointer->lengthXorMask,
        pointer->andMaskData,
        pointer->lengthAndMask,
        pointer->xorBpp,
        palette
    );
    if (!decoded) {
        delete buf;
        return TRUE;
    }

    // Premultiply BGRA32 alpha for CoreGraphics kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst
    uint8_t* raw = buf->data();
    for (size_t i = 0; i < pixel_count; ++i) {
        uint8_t* px = raw + i * 4u;
        const uint32_t a = px[3];
        if (a == 0u) {
            px[0] = 0u;
            px[1] = 0u;
            px[2] = 0u;
        } else if (a < 255u) {
            px[0] = static_cast<uint8_t>((static_cast<uint32_t>(px[0]) * a + 127u) / 255u);
            px[1] = static_cast<uint8_t>((static_cast<uint32_t>(px[1]) * a + 127u) / 255u);
            px[2] = static_cast<uint8_t>((static_cast<uint32_t>(px[2]) * a + 127u) / 255u);
        }
    }

    np->decoded_w = w;
    np->decoded_h = h;
    np->hot_x = std::min(pointer->xPos, w - 1u);
    np->hot_y = std::min(pointer->yPos, h - 1u);
    np->premul_bgra = buf;
    return TRUE;
}

void nexusts_pointer_free(rdpContext* /*context*/, rdpPointer* pointer) {
    if (!pointer) {
        return;
    }
    auto* np = reinterpret_cast<NexusRdpPointer*>(pointer);
    delete np->premul_bgra;
    np->premul_bgra = nullptr;
}

BOOL nexusts_pointer_set(rdpContext* context, rdpPointer* pointer) {
    if (!context || !pointer) {
        return TRUE;
    }
    const auto cb = lookup_freerdp_callbacks(context->instance);
    if (cb.owner) {
        const auto* np = reinterpret_cast<const NexusRdpPointer*>(pointer);
        if (np->premul_bgra && !np->premul_bgra->empty() && np->decoded_w > 0u && np->decoded_h > 0u) {
            cb.owner->update_remote_cursor_bitmap(
                np->decoded_w,
                np->decoded_h,
                np->hot_x,
                np->hot_y,
                np->premul_bgra->data(),
                np->premul_bgra->size()
            );
        }
    }
    return TRUE;
}

BOOL nexusts_pointer_set_null(rdpContext* context) {
    if (context) {
        const auto cb = lookup_freerdp_callbacks(context->instance);
        if (cb.owner) {
            cb.owner->update_remote_cursor_state(false, false);
        }
    }
    return TRUE;
}

BOOL nexusts_pointer_set_default(rdpContext* context) {
    if (context) {
        const auto cb = lookup_freerdp_callbacks(context->instance);
        if (cb.owner) {
            cb.owner->update_remote_cursor_state(true, true);
        }
    }
    return TRUE;
}

BOOL nexusts_pointer_set_position(rdpContext* /*context*/, UINT32 /*x*/, UINT32 /*y*/) {
    return TRUE;
}

void register_safe_pointer_callbacks(rdpGraphics* graphics) {
    if (!graphics) {
        return;
    }
    rdpPointer pointer{};
    pointer.size = sizeof(NexusRdpPointer);
    pointer.New = &nexusts_pointer_new;
    pointer.Free = &nexusts_pointer_free;
    pointer.Set = &nexusts_pointer_set;
    pointer.SetNull = &nexusts_pointer_set_null;
    pointer.SetDefault = &nexusts_pointer_set_default;
    pointer.SetPosition = &nexusts_pointer_set_position;
    graphics_register_pointer(graphics, &pointer);
}

BOOL freerdp_on_post_connect(freerdp* instance) {
    if (!instance || !instance->context) {
        return FALSE;
    }
#ifdef __APPLE__
    constexpr UINT32 kTargetGdiPixelFormat = PIXEL_FORMAT_BGRX32;
#else
    constexpr UINT32 kTargetGdiPixelFormat = PIXEL_FORMAT_RGBX32;
#endif
    if (!gdi_init(instance, kTargetGdiPixelFormat)) {
        NX_RDP_LOGE("FreeRDP gdi_init(0x%08x) failed", kTargetGdiPixelFormat);
        const auto cb = lookup_freerdp_callbacks(instance);
        if (cb.owner) {
            cb.owner->set_last_error("INTERNAL: FreeRDP gdi_init() failed.");
        }
        return FALSE;
    }
    if (instance->context->gdi && instance->context->gdi->width > 0 && instance->context->gdi->height > 0) {
        const auto cb = lookup_freerdp_callbacks(instance);
        if (cb.owner) {
            cb.owner->presenter().resize_shadow_framebuffer(
                static_cast<uint32_t>(instance->context->gdi->width),
                static_cast<uint32_t>(instance->context->gdi->height)
            );
        }
    }
    register_safe_pointer_callbacks(instance->context->graphics);
    if (instance->context->update) {
        instance->context->update->BeginPaint = &freerdp_on_begin_paint;
        instance->context->update->EndPaint = &freerdp_on_end_paint;
        instance->context->update->DesktopResize = &freerdp_on_desktop_resize;
    }
    return TRUE;
}

void freerdp_on_post_disconnect(freerdp* instance) {
    if (instance) {
        if (instance->context && instance->context->pubSub) {
            PubSub_UnsubscribeChannelConnected(instance->context->pubSub, &freerdp_on_channel_connected);
            PubSub_UnsubscribeChannelDisconnected(instance->context->pubSub, &freerdp_on_channel_disconnected);
        }
        const auto cb = lookup_freerdp_callbacks(instance);
        if (cb.owner) {
            cb.owner->set_disp_context(nullptr);
            cb.owner->set_cliprdr_ready(false);
            cb.owner->set_cliprdr_context(nullptr);
        }
        gdi_free(instance);
    }
}

BOOL freerdp_on_authenticate_ex(
    freerdp* instance,
    char** username,
    char** password,
    char** domain,
    rdp_auth_reason reason
) {
    if (!username || !password || !domain) {
        return FALSE;
    }
    const auto cb = lookup_freerdp_callbacks(instance);
    if ((!*username || (*username)[0] == '\0') && !cb.username.empty()) {
        std::free(*username);
        *username = dup_cstr_malloc(cb.username);
    }
    if ((!*domain || (*domain)[0] == '\0') && !cb.domain.empty()) {
        std::free(*domain);
        *domain = dup_cstr_malloc(cb.domain);
    }
    if ((!*password || (*password)[0] == '\0') && !cb.password.empty()) {
        std::free(*password);
        *password = dup_cstr_malloc(cb.password);
    }

    const bool has_user = (*username && (*username)[0] != '\0');
    const bool has_pass = (*password && (*password)[0] != '\0');

    if (reason == AUTH_NLA || reason == GW_AUTH_HTTP || reason == GW_AUTH_RDG || reason == GW_AUTH_RPC) {
        if (!has_user && !has_pass) {
            if (cb.owner) {
                cb.owner->set_last_error(
                    "AUTHENTICATION_FAILED: Network Level Authentication (NLA) requires a username and password. "
                    "Assign a credential to this connection or disable NLA in RDP Security settings."
                );
            }
            return FALSE;
        }
    }

    // For classic TLS/RDP interactive Windows logon screens, FreeRDP requires non-NULL empty strings with TRUE
    if (!*username) {
        *username = dup_cstr_malloc("");
    }
    if (!*domain) {
        *domain = dup_cstr_malloc("");
    }
    if (!*password) {
        *password = dup_cstr_malloc("");
    }
    return TRUE;
}

DWORD freerdp_on_verify_certificate_ex(
    freerdp* instance,
    const char* host,
    UINT16 port,
    const char* common_name,
    const char* subject,
    const char* issuer,
    const char* fingerprint,
    DWORD flags
) {
    (void)host;
    (void)port;
    (void)common_name;
    (void)subject;
    (void)issuer;
    (void)fingerprint;
    (void)flags;
    const auto cb = lookup_freerdp_callbacks(instance);
    if (cb.ignore_cert_warnings) {
        NX_RDP_LOGI("Accepting certificate for %s:%u (%s) per ignore_cert_warnings",
                    host ? host : "", port, fingerprint ? fingerprint : "");
        return 2u; // Accept temporarily for this session without mutating disk known_hosts2
    }
    // Trust-On-First-Use (TOFU) for newly encountered self-signed Windows RDP hosts:
    // Save fingerprint in app-private known_hosts2 (1u) when writable so subsequent MITM changes are caught by VerifyChangedCertificateEx.
    NX_RDP_LOGI("Trust-On-First-Use (TOFU) accepting X.509 certificate for %s:%u (%s)",
                host ? host : "", port, fingerprint ? fingerprint : "");
    return cb.cert_store_writable ? 1u : 2u;
}

DWORD freerdp_on_verify_changed_certificate_ex(
    freerdp* instance,
    const char* host,
    UINT16 port,
    const char* common_name,
    const char* subject,
    const char* issuer,
    const char* new_fingerprint,
    const char* old_subject,
    const char* old_issuer,
    const char* old_fingerprint,
    DWORD flags
) {
    (void)common_name;
    (void)subject;
    (void)issuer;
    (void)old_subject;
    (void)old_issuer;
    (void)flags;
    const auto cb = lookup_freerdp_callbacks(instance);
    if (cb.ignore_cert_warnings) {
        NX_RDP_LOGI("Accepting changed X.509 certificate for %s:%u (%s -> %s) per ignore_cert_warnings",
                    host ? host : "", port,
                    old_fingerprint ? old_fingerprint : "",
                    new_fingerprint ? new_fingerprint : "");
        return 2u;
    }
    const std::string host_str = host ? host : "";
    const std::string new_fp = new_fingerprint ? new_fingerprint : "unknown";
    const std::string old_fp = old_fingerprint ? old_fingerprint : "unknown";
    if (cb.owner) {
        cb.owner->set_last_error(
            "TLS_CERTIFICATE_CHANGED: X.509 certificate fingerprint changed for " +
            host_str + ":" + std::to_string(port) +
            " (new=" + new_fp + ", previous=" + old_fp + ")."
        );
    }
    NX_RDP_LOGE("Rejected changed X.509 certificate for %s:%u (old=%s, new=%s)",
                host_str.c_str(), port, old_fp.c_str(), new_fp.c_str());
    return 0u;
}
#endif

} // namespace

std::string normalize_hyperv_preconnection_blob(
    const std::string& raw_pcb,
    bool force_enhanced_session
) {
    auto trim_ws = [](const std::string& s) -> std::string {
        const auto first = s.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) {
            return {};
        }
        const auto last = s.find_last_not_of(" \t\r\n");
        return s.substr(first, last - first + 1u);
    };

    const std::string trimmed = trim_ws(raw_pcb);
    if (trimmed.empty()) {
        return {};
    }

    const auto semi_pos = trimmed.find(';');
    std::string guid_part = trim_ws(semi_pos == std::string::npos ? trimmed : trimmed.substr(0u, semi_pos));
    std::string suffix_part = semi_pos == std::string::npos ? std::string() : trim_ws(trimmed.substr(semi_pos + 1u));

    while (!guid_part.empty() && (guid_part.front() == '{' || guid_part.front() == '"' || guid_part.front() == '\'')) {
        guid_part.erase(guid_part.begin());
    }
    while (!guid_part.empty() && (guid_part.back() == '}' || guid_part.back() == '"' || guid_part.back() == '\'')) {
        guid_part.pop_back();
    }
    guid_part = trim_ws(guid_part);
    if (guid_part.empty()) {
        return {};
    }

    if (!suffix_part.empty()) {
        while (!suffix_part.empty() && (suffix_part.back() == '"' || suffix_part.back() == '\'')) {
            suffix_part.pop_back();
        }
        return guid_part + ";" + suffix_part;
    }
    if (force_enhanced_session) {
        return guid_part + ";EnhancedMode=1";
    }
    return guid_part;
}

uint16_t resolve_effective_rdp_port(
    uint16_t configured_port,
    bool port_from_host_literal,
    const std::string& normalized_preconnection_blob
) noexcept {
    if (!normalized_preconnection_blob.empty()) {
        if (port_from_host_literal && configured_port != 0u) {
            return configured_port;
        }
        if (configured_port == 0u || configured_port == 3389u) {
            return 2179u;
        }
        return configured_port;
    }
    return configured_port == 0u ? 3389u : configured_port;
}

RdpSessionContext::RdpSessionContext(RdpConnectParams params)
    : params_(std::move(params)),
      active_scale_percent_(std::clamp(params_.desktop_scale_factor, 100u, 500u)),
      gdi_presenter_(std::make_unique<RdpGdiSurfacePresenter>()) {
    const bool h264_cap = params_.uses_h264_codec();
    const auto [w, h] = clamp_rdp_surface_dimensions(
        params_.initial_width,
        params_.initial_height,
        320u,
        240u,
        8192u,
        8192u,
        h264_cap
    );
    last_requested_width_.store(w, std::memory_order_relaxed);
    last_requested_height_.store(h, std::memory_order_relaxed);
    gdi_presenter_->resize_shadow_framebuffer(w, h);
    if (params_.redirect_clipboard) {
        channels_.set_local_clipboard_announce_handler([this](const std::string&) {
            announce_local_clipboard_to_remote();
        });
    }
#if !defined(NEXUSTS_HAS_FREERDP3_BINARY) || NEXUSTS_HAS_FREERDP3_BINARY == 0
    render_diagnostic_surface_frame(w, h);
#endif
}

RdpSessionContext::~RdpSessionContext() {
    if (gdi_presenter_) {
        gdi_presenter_->set_frame_ready_callback(nullptr, nullptr);
    }
    disconnect();
    std::lock_guard<std::mutex> lock(cliprdr_mutex_);
    close_active_remote_file_locked(0u);
}

void RdpSessionContext::signal_event_loop_wakeup() noexcept {
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(input_mutex_);
    if (wakeup_event_) {
        wakeup_signaled_.store(true, std::memory_order_release);
        SetEvent(static_cast<HANDLE>(wakeup_event_));
    }
#endif
}

void RdpSessionContext::on_surface_bound() {
#if !defined(NEXUSTS_HAS_FREERDP3_BINARY) || NEXUSTS_HAS_FREERDP3_BINARY == 0
    if (gdi_presenter_->has_bound_surface()) {
        render_diagnostic_surface_frame(gdi_presenter_->width(), gdi_presenter_->height());
    }
#else
    if (gdi_presenter_->has_bound_surface()) {
        gdi_presenter_->present_frame();
    }
#endif
}

void RdpSessionContext::set_disp_context(void* disp_context) noexcept {
    disp_context_.store(disp_context, std::memory_order_release);
    if (disp_context) {
        // Flush any resize layout queued before DISP_DVC finished opening
        signal_event_loop_wakeup();
    }
}

void RdpSessionContext::set_cliprdr_context(void* cliprdr_context) {
    if (!cliprdr_context) {
        cliprdr_ready_.store(false, std::memory_order_release);
        server_clip_caps_.store(0u, std::memory_order_release);
        server_fgd_format_id_.store(0u, std::memory_order_release);
        server_fgd_is_ansi_.store(false, std::memory_order_release);
        {
            std::lock_guard<std::mutex> in_lock(input_mutex_);
            awaiting_format_list_response_ = false;
            pending_synchronized_paste_ = false;
        }
    }
    std::lock_guard<std::mutex> lock(cliprdr_mutex_);
    if (!cliprdr_context) {
        has_active_server_clip_data_id_ = false;
        active_server_clip_data_id_ = 0u;
        active_fgd_snapshot_.reset();
        locked_local_file_snapshots_.clear();
        close_active_remote_file_locked(0u);
        remote_file_transfer_ = RemoteFileTransferState{};
    }
    cliprdr_context_ = cliprdr_context;
}

void RdpSessionContext::set_cliprdr_ready(bool ready) noexcept {
    cliprdr_ready_.store(ready, std::memory_order_release);
    if (ready) {
        signal_event_loop_wakeup();
    }
}

void RdpSessionContext::announce_local_clipboard_to_remote() {
    if (!params_.redirect_clipboard) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(input_mutex_);
    pending_clipboard_announce_ = true;
    if (!awaiting_format_list_response_) {
        format_list_sent_tp_ = std::chrono::steady_clock::now();
    }
    if (wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
        SetEvent(static_cast<HANDLE>(wakeup_event_));
    }
#endif
}

void RdpSessionContext::enqueue_ctrl_v_locked() {
    // Press Left Ctrl (0x1D) BEFORE releasing any held Left/Right Win (0x5B/0x5C extended)
    // so Windows never interprets a Cmd->Ctrl transition as a bare Win-key Start Menu tap.
    constexpr size_t kLeftCtrlIdx = 0x1Du;
    constexpr size_t kLeftShiftIdx = 0x2Au;
    constexpr size_t kRightShiftIdx = 0x36u;
    constexpr size_t kLeftWinExtIdx = 256u + 0x5Bu;
    constexpr size_t kRightWinExtIdx = 256u + 0x5Cu;

    pressed_scancodes_[kLeftCtrlIdx] = 1u;
    pending_keys_.push_back(QueuedKeyEvent{0x1Du, /*key_down=*/true, /*extended=*/false});

    if (pressed_scancodes_[kLeftWinExtIdx] != 0u) {
        pressed_scancodes_[kLeftWinExtIdx] = 0u;
        pending_keys_.push_back(QueuedKeyEvent{0x5Bu, /*key_down=*/false, /*extended=*/true});
    }
    if (pressed_scancodes_[kRightWinExtIdx] != 0u) {
        pressed_scancodes_[kRightWinExtIdx] = 0u;
        pending_keys_.push_back(QueuedKeyEvent{0x5Cu, /*key_down=*/false, /*extended=*/true});
    }
    if (pressed_scancodes_[kLeftShiftIdx] != 0u) {
        pressed_scancodes_[kLeftShiftIdx] = 0u;
        pending_keys_.push_back(QueuedKeyEvent{0x2Au, /*key_down=*/false, /*extended=*/false});
    }
    if (pressed_scancodes_[kRightShiftIdx] != 0u) {
        pressed_scancodes_[kRightShiftIdx] = 0u;
        pending_keys_.push_back(QueuedKeyEvent{0x36u, /*key_down=*/false, /*extended=*/false});
    }

    pending_keys_.push_back(QueuedKeyEvent{0x2Fu, /*key_down=*/true, /*extended=*/false});
    pending_keys_.push_back(QueuedKeyEvent{0x2Fu, /*key_down=*/false, /*extended=*/false});
    pressed_scancodes_[kLeftCtrlIdx] = 0u;
    pending_keys_.push_back(QueuedKeyEvent{0x1Du, /*key_down=*/false, /*extended=*/false});
}

void RdpSessionContext::request_synchronized_paste() {
    if (!connected_.load(std::memory_order_acquire) || reconnecting_.load(std::memory_order_acquire)) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(input_mutex_);
    if (!freerdp_instance_) {
        return;
    }
    const bool clip_ready = cliprdr_ready_.load(std::memory_order_acquire);
    if (params_.redirect_clipboard && clip_ready &&
        (pending_clipboard_announce_ || awaiting_format_list_response_)) {
        // Gate Fast-Path Ctrl+V until rdpclip.exe confirms CB_FORMAT_LIST_RESPONSE (or 350 ms timeout)
        pending_synchronized_paste_ = true;
        if (!awaiting_format_list_response_) {
            format_list_sent_tp_ = std::chrono::steady_clock::now();
        }
    } else {
        enqueue_ctrl_v_locked();
    }
    if (wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
        SetEvent(static_cast<HANDLE>(wakeup_event_));
    }
#else
    std::lock_guard<std::mutex> lock(input_mutex_);
    enqueue_ctrl_v_locked();
    pending_keys_.clear();
#endif
}

void RdpSessionContext::on_cliprdr_monitor_ready_sent() {
    std::shared_ptr<const std::vector<RdpClipboardFileEntry>> prebound_files;
    if (channels_.local_clipboard_kind() == RdpClipboardContentKind::Files) {
        prebound_files = channels_.local_clipboard_files();
    }
    {
        std::lock_guard<std::mutex> clip_lock(cliprdr_mutex_);
        has_active_server_clip_data_id_ = false;
        active_server_clip_data_id_ = 0u;
        active_fgd_snapshot_ = (prebound_files && !prebound_files->empty()) ? prebound_files : nullptr;
    }
    std::lock_guard<std::mutex> lock(input_mutex_);
    pending_clipboard_announce_ = false;
    awaiting_format_list_response_ = true;
    format_list_sent_tp_ = std::chrono::steady_clock::now();
}

void RdpSessionContext::on_cliprdr_server_capabilities(uint32_t general_flags) noexcept {
    server_clip_caps_.store(general_flags, std::memory_order_release);
}

void RdpSessionContext::on_cliprdr_server_format_list_response(bool ok) {
    (void)ok;
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(input_mutex_);
    awaiting_format_list_response_ = false;
    bool need_wake = !pending_pointer_events_.empty();
    if (pending_clipboard_announce_) {
        // Another local clipboard update arrived while CB_FORMAT_LIST was in flight — announce it next
        need_wake = true;
    } else if (pending_synchronized_paste_) {
        pending_synchronized_paste_ = false;
        enqueue_ctrl_v_locked();
        need_wake = true;
    }
    if (need_wake && wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
        SetEvent(static_cast<HANDLE>(wakeup_event_));
    }
#endif
}

void RdpSessionContext::lock_local_clipboard_files(uint32_t clip_data_id) {
    auto snapshot = channels_.local_clipboard_files();
    std::lock_guard<std::mutex> lock(cliprdr_mutex_);
    has_active_server_clip_data_id_ = true;
    active_server_clip_data_id_ = clip_data_id;
    if (!snapshot || snapshot->empty()) {
        snapshot = active_fgd_snapshot_;
    }
    if (!snapshot || snapshot->empty()) {
        return;
    }
    if (locked_local_file_snapshots_.size() >= 16u &&
        locked_local_file_snapshots_.find(clip_data_id) == locked_local_file_snapshots_.end()) {
        locked_local_file_snapshots_.erase(locked_local_file_snapshots_.begin());
    }
    locked_local_file_snapshots_[clip_data_id] = snapshot;
    active_fgd_snapshot_ = snapshot;
}

void RdpSessionContext::unlock_local_clipboard_files(uint32_t clip_data_id) {
    std::lock_guard<std::mutex> lock(cliprdr_mutex_);
    locked_local_file_snapshots_.erase(clip_data_id);
    if (has_active_server_clip_data_id_ && active_server_clip_data_id_ == clip_data_id) {
        has_active_server_clip_data_id_ = false;
        active_server_clip_data_id_ = 0u;
    }
}

std::shared_ptr<const std::vector<RdpClipboardFileEntry>>
RdpSessionContext::bind_active_file_group_descriptor_snapshot() {
    auto latest = channels_.local_clipboard_files();
    std::lock_guard<std::mutex> lock(cliprdr_mutex_);
    if (has_active_server_clip_data_id_) {
        const auto it = locked_local_file_snapshots_.find(active_server_clip_data_id_);
        if (it != locked_local_file_snapshots_.end() && it->second && !it->second->empty()) {
            active_fgd_snapshot_ = it->second;
            return it->second;
        }
    }
    if (latest && !latest->empty()) {
        active_fgd_snapshot_ = latest;
        if (has_active_server_clip_data_id_) {
            if (locked_local_file_snapshots_.size() >= 16u &&
                locked_local_file_snapshots_.find(active_server_clip_data_id_) == locked_local_file_snapshots_.end()) {
                locked_local_file_snapshots_.erase(locked_local_file_snapshots_.begin());
            }
            locked_local_file_snapshots_[active_server_clip_data_id_] = latest;
        }
        return latest;
    }
    return active_fgd_snapshot_;
}

std::shared_ptr<const std::vector<RdpClipboardFileEntry>> RdpSessionContext::resolve_local_clipboard_files(
    bool has_clip_data_id,
    uint32_t clip_data_id
) {
    {
        std::lock_guard<std::mutex> lock(cliprdr_mutex_);
        if (has_clip_data_id) {
            const auto it = locked_local_file_snapshots_.find(clip_data_id);
            if (it != locked_local_file_snapshots_.end() && it->second) {
                return it->second;
            }
        }
        if (active_fgd_snapshot_ && !active_fgd_snapshot_->empty()) {
            return active_fgd_snapshot_;
        }
    }
    return channels_.local_clipboard_files();
}

void RdpSessionContext::close_active_remote_file_locked(uint64_t last_write_filetime) noexcept {
    if (remote_file_transfer_.active_fd >= 0) {
        constexpr uint64_t kWinEpochDiffTicks = 116444736000000000ULL;
        if (last_write_filetime > kWinEpochDiffTicks) {
            const uint64_t unix_ticks = last_write_filetime - kWinEpochDiffTicks;
            struct timespec times[2]{};
            times[0].tv_sec = 0;
            times[0].tv_nsec = UTIME_NOW;
            times[1].tv_sec = static_cast<time_t>(unix_ticks / 10000000ULL);
            times[1].tv_nsec = static_cast<long>((unix_ticks % 10000000ULL) * 100ULL);
            (void)::futimens(remote_file_transfer_.active_fd, times);
        }
        (void)::close(remote_file_transfer_.active_fd);
        remote_file_transfer_.active_fd = -1;
    }
    remote_file_transfer_.active_file_path.clear();
}

void RdpSessionContext::set_file_contents_request_hook(FileContentsRequestHook hook) {
    std::lock_guard<std::mutex> lock(cliprdr_mutex_);
    file_contents_request_hook_ = std::move(hook);
}

void RdpSessionContext::abort_remote_file_transfer_locked(void* raw_cliprdr) {
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    if (remote_file_transfer_.has_lock && raw_cliprdr) {
        auto* cliprdr = static_cast<CliprdrClientContext*>(raw_cliprdr);
        if (cliprdr->ClientUnlockClipboardData) {
            CLIPRDR_UNLOCK_CLIPBOARD_DATA unlock_pdu{};
            unlock_pdu.common.msgType = CB_UNLOCK_CLIPDATA;
            unlock_pdu.common.dataLen = 4u;
            unlock_pdu.clipDataId = remote_file_transfer_.clip_data_id;
            cliprdr->ClientUnlockClipboardData(cliprdr, &unlock_pdu);
        }
    }
#else
    (void)raw_cliprdr;
#endif
    close_active_remote_file_locked(0u);
    if (!remote_file_transfer_.batch_root_dir.empty()) {
        std::error_code ec;
        std::filesystem::remove_all(remote_file_transfer_.batch_root_dir, ec);
    }
    remote_file_transfer_ = RemoteFileTransferState{};
}

void RdpSessionContext::begin_remote_file_list_fetch(
    void* raw_cliprdr,
    uint32_t format_id,
    bool is_ansi
) {
    server_fgd_format_id_.store(format_id, std::memory_order_release);
    server_fgd_is_ansi_.store(is_ansi, std::memory_order_release);
    std::lock_guard<std::mutex> lock(cliprdr_mutex_);
    abort_remote_file_transfer_locked(raw_cliprdr);

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    auto* cliprdr = static_cast<CliprdrClientContext*>(raw_cliprdr);
    if (cliprdr &&
        cliprdr->ClientLockClipboardData &&
        (server_clip_caps_.load(std::memory_order_acquire) & CB_CAN_LOCK_CLIPDATA) != 0u) {
        uint32_t lock_id = next_client_clip_data_id_++;
        if (lock_id == 0u) {
            lock_id = next_client_clip_data_id_++;
        }
        CLIPRDR_LOCK_CLIPBOARD_DATA lock_pdu{};
        lock_pdu.common.msgType = CB_LOCK_CLIPDATA;
        lock_pdu.common.dataLen = 4u;
        lock_pdu.clipDataId = lock_id;
        if (cliprdr->ClientLockClipboardData(cliprdr, &lock_pdu) == CHANNEL_RC_OK) {
            remote_file_transfer_.has_lock = true;
            remote_file_transfer_.clip_data_id = lock_id;
        }
    }
#else
    (void)raw_cliprdr;
#endif
}

void RdpSessionContext::advance_remote_file_transfer_locked(void* raw_cliprdr) {
    constexpr uint32_t kFileContentsSizeFlag = 0x00000001u;
    constexpr uint32_t kFileContentsRangeFlag = 0x00000002u;
    constexpr uint32_t kRemotePullChunkBytes = 4u * 1024u * 1024u;

    auto send_file_contents_req = [&](
        uint32_t stream_id,
        uint32_t list_index,
        uint32_t dw_flags,
        uint64_t offset,
        uint32_t cb_requested
    ) -> bool {
        if (file_contents_request_hook_) {
            return file_contents_request_hook_(
                stream_id,
                list_index,
                dw_flags,
                offset,
                cb_requested,
                remote_file_transfer_.has_lock,
                remote_file_transfer_.clip_data_id
            );
        }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
        auto* cliprdr = static_cast<CliprdrClientContext*>(raw_cliprdr);
        if (!cliprdr || !cliprdr->ClientFileContentsRequest) {
            return false;
        }
        CLIPRDR_FILE_CONTENTS_REQUEST req{};
        req.common.msgType = CB_FILECONTENTS_REQUEST;
        req.streamId = stream_id;
        req.listIndex = list_index;
        req.dwFlags = dw_flags;
        req.nPositionLow = static_cast<UINT32>(offset & 0xFFFFFFFFULL);
        req.nPositionHigh = static_cast<UINT32>((offset >> 32) & 0xFFFFFFFFULL);
        req.cbRequested = cb_requested;
        req.haveClipDataId = remote_file_transfer_.has_lock ? TRUE : FALSE;
        req.clipDataId = remote_file_transfer_.clip_data_id;
        return cliprdr->ClientFileContentsRequest(cliprdr, &req) == CHANNEL_RC_OK;
#else
        (void)raw_cliprdr;
        return false;
#endif
    };

    while (remote_file_transfer_.current_index < remote_file_transfer_.entries.size()) {
        auto& entry = remote_file_transfer_.entries[remote_file_transfer_.current_index];
        if (entry.is_directory) {
            close_active_remote_file_locked(0u);
            ++remote_file_transfer_.current_index;
            remote_file_transfer_.size_queried = false;
            remote_file_transfer_.awaiting_size = false;
            remote_file_transfer_.current_offset = 0u;
            continue;
        }
        // Always query FILECONTENTS_SIZE (IStream::Stat) first for every regular file:
        // virtual/compressed Windows shell items often set FD_FILESIZE (0x40) with nFileSize=0
        // in FILEDESCRIPTORW while reporting the real byte length via FILECONTENTS_SIZE.
        if (!remote_file_transfer_.size_queried) {
            uint32_t stream_id = next_client_stream_id_++;
            if (stream_id == 0u) {
                stream_id = next_client_stream_id_++;
            }
            remote_file_transfer_.awaiting_size = true;
            remote_file_transfer_.active_stream_id = stream_id;
            if (!send_file_contents_req(
                    stream_id,
                    static_cast<uint32_t>(remote_file_transfer_.current_index),
                    kFileContentsSizeFlag,
                    0u,
                    8u
                )) {
                abort_remote_file_transfer_locked(raw_cliprdr);
            }
            return;
        }
        if (entry.file_size == 0u || remote_file_transfer_.current_offset >= entry.file_size) {
            if (entry.file_size == 0u && remote_file_transfer_.active_fd < 0) {
                const std::filesystem::path target_file =
                    std::filesystem::path(remote_file_transfer_.batch_root_dir) / entry.local_posix_path;
                const std::string target_file_str = target_file.string();
                int fd = -1;
                do {
                    fd = ::open(target_file_str.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
                } while (fd < 0 && errno == EINTR);
                if (fd < 0) {
                    abort_remote_file_transfer_locked(raw_cliprdr);
                    return;
                }
                remote_file_transfer_.active_fd = fd;
                remote_file_transfer_.active_file_path = target_file_str;
            }
            close_active_remote_file_locked(entry.last_write_filetime);
            ++remote_file_transfer_.current_index;
            remote_file_transfer_.size_queried = false;
            remote_file_transfer_.awaiting_size = false;
            remote_file_transfer_.current_offset = 0u;
            continue;
        }

        const uint64_t remaining = entry.file_size - remote_file_transfer_.current_offset;
        const uint32_t chunk_req = static_cast<uint32_t>(std::min<uint64_t>(remaining, kRemotePullChunkBytes));
        uint32_t stream_id = next_client_stream_id_++;
        if (stream_id == 0u) {
            stream_id = next_client_stream_id_++;
        }
        remote_file_transfer_.awaiting_size = false;
        remote_file_transfer_.active_stream_id = stream_id;
        if (!send_file_contents_req(
                stream_id,
                static_cast<uint32_t>(remote_file_transfer_.current_index),
                kFileContentsRangeFlag,
                remote_file_transfer_.current_offset,
                chunk_req
            )) {
            abort_remote_file_transfer_locked(raw_cliprdr);
        }
        return;
    }

    // All files & folders in the batch have been staged locally as exact raw binaries!
    close_active_remote_file_locked(0u);
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    if (remote_file_transfer_.has_lock && raw_cliprdr) {
        auto* cliprdr = static_cast<CliprdrClientContext*>(raw_cliprdr);
        if (cliprdr->ClientUnlockClipboardData) {
            CLIPRDR_UNLOCK_CLIPBOARD_DATA unlock_pdu{};
            unlock_pdu.common.msgType = CB_UNLOCK_CLIPDATA;
            unlock_pdu.common.dataLen = 4u;
            unlock_pdu.clipDataId = remote_file_transfer_.clip_data_id;
            cliprdr->ClientUnlockClipboardData(cliprdr, &unlock_pdu);
            remote_file_transfer_.has_lock = false;
        }
    }
#endif
    remote_file_transfer_.active = false;
}

void RdpSessionContext::handle_remote_file_group_descriptor_response(
    void* raw_cliprdr,
    const uint8_t* data,
    size_t data_len
) {
    constexpr uint64_t kMaxRemoteClipTotalBytes = 16ULL * 1024ULL * 1024ULL * 1024ULL;
    const bool is_ansi_fgd = server_fgd_is_ansi_.load(std::memory_order_acquire);
    std::vector<std::string> completed_roots;
    {
        std::lock_guard<std::mutex> lock(cliprdr_mutex_);
        std::vector<RdpClipboardFileEntry> parsed;
        if (!data ||
            data_len == 0u ||
            !parse_and_sanitize_cliprdr_file_list(data, data_len, parsed, is_ansi_fgd)) {
            abort_remote_file_transfer_locked(raw_cliprdr);
            return;
        }

        uint64_t known_total = 0u;
        for (const auto& e : parsed) {
            if (e.file_size > kMaxRemoteClipTotalBytes ||
                known_total + e.file_size > kMaxRemoteClipTotalBytes) {
                abort_remote_file_transfer_locked(raw_cliprdr);
                return;
            }
            known_total += e.file_size;
        }

        std::error_code ec;
        std::filesystem::path base_dir = !resolved_runtime_home_.empty()
            ? (std::filesystem::path(resolved_runtime_home_) / "tmp" / "cliprdr_recv")
            : (!params_.app_home_dir.empty()
                ? (std::filesystem::path(params_.app_home_dir) / "tmp" / "cliprdr_recv")
                : (std::filesystem::temp_directory_path(ec) / "nexusts_cliprdr_recv"));
        std::filesystem::create_directories(base_dir, ec);

        // Retain the 10 newest staged clipboard batches so Finder/Explorer paste operations
        // still reading a recent batch never lose files across rapid copies or multiple tabs.
        {
            std::vector<std::pair<std::filesystem::file_time_type, std::filesystem::path>> existing_batches;
            for (const auto& dir_entry : std::filesystem::directory_iterator(base_dir, ec)) {
                if (ec) {
                    break;
                }
                const std::string fname = dir_entry.path().filename().string();
                if (dir_entry.is_directory(ec) && fname.rfind("clip_", 0u) == 0u) {
                    const auto mtime = dir_entry.last_write_time(ec);
                    existing_batches.emplace_back(mtime, dir_entry.path());
                }
            }
            if (existing_batches.size() >= 10u) {
                std::sort(
                    existing_batches.begin(),
                    existing_batches.end(),
                    [](const auto& a, const auto& b) { return a.first < b.first; }
                );
                const size_t to_prune = existing_batches.size() - 9u;
                for (size_t i = 0u; i < to_prune; ++i) {
                    std::filesystem::remove_all(existing_batches[i].second, ec);
                }
            }
        }

        const uint64_t batch_id = global_remote_clip_batch_seq().fetch_add(1u, std::memory_order_relaxed);
        const auto epoch_ms = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()
            ).count()
        );
        const std::filesystem::path batch_dir =
            base_dir / ("clip_" + std::to_string(epoch_ms) + "_" + std::to_string(batch_id));
        std::filesystem::remove_all(batch_dir, ec);
        if (!std::filesystem::create_directories(batch_dir, ec) && ec) {
            abort_remote_file_transfer_locked(raw_cliprdr);
            return;
        }
        const std::filesystem::path canonical_batch = std::filesystem::weakly_canonical(batch_dir, ec);
        if (ec || canonical_batch.empty()) {
            abort_remote_file_transfer_locked(raw_cliprdr);
            return;
        }
        const std::string canonical_prefix = canonical_batch.string() + "/";

        std::vector<std::string> top_roots;
        std::unordered_set<std::string> seen_roots;
        for (const auto& e : parsed) {
            const std::filesystem::path target = canonical_batch / e.local_posix_path;
            const std::filesystem::path canonical_target = std::filesystem::weakly_canonical(target, ec);
            const std::string target_str = canonical_target.string();
            if (ec || target_str.size() <= canonical_prefix.size() ||
                target_str.compare(0u, canonical_prefix.size(), canonical_prefix) != 0) {
                abort_remote_file_transfer_locked(raw_cliprdr);
                return;
            }
            if (e.is_directory) {
                std::filesystem::create_directories(canonical_target, ec);
            } else if (canonical_target.has_parent_path()) {
                std::filesystem::create_directories(canonical_target.parent_path(), ec);
            }

            const auto slash_pos = e.local_posix_path.find('/');
            const std::string first_seg = (slash_pos == std::string::npos)
                ? e.local_posix_path
                : e.local_posix_path.substr(0u, slash_pos);
            if (!first_seg.empty() && seen_roots.insert(first_seg).second) {
                top_roots.push_back((canonical_batch / first_seg).string());
            }
        }

        close_active_remote_file_locked(0u);
        remote_file_transfer_.active = true;
        remote_file_transfer_.batch_id = batch_id;
        remote_file_transfer_.batch_root_dir = canonical_batch.string();
        remote_file_transfer_.entries = std::move(parsed);
        remote_file_transfer_.top_level_staged_paths = std::move(top_roots);
        remote_file_transfer_.current_index = 0u;
        remote_file_transfer_.size_queried = false;
        remote_file_transfer_.awaiting_size = false;
        remote_file_transfer_.current_offset = 0u;
        remote_file_transfer_.total_bytes_written = 0u;
        remote_file_transfer_.active_file_path.clear();

        advance_remote_file_transfer_locked(raw_cliprdr);
        if (!remote_file_transfer_.active && !remote_file_transfer_.top_level_staged_paths.empty()) {
            completed_roots = std::move(remote_file_transfer_.top_level_staged_paths);
            remote_file_transfer_ = RemoteFileTransferState{};
        }
    }

    if (!completed_roots.empty()) {
        channels_.on_remote_clipboard_files(completed_roots);
        gdi_presenter_->notify_frame_ready();
    }
}

void RdpSessionContext::handle_remote_file_contents_response(
    void* raw_cliprdr,
    uint32_t stream_id,
    bool ok,
    const uint8_t* data,
    size_t data_len
) {
    constexpr uint64_t kMaxRemoteClipTotalBytes = 16ULL * 1024ULL * 1024ULL * 1024ULL;
    std::vector<std::string> completed_roots;
    {
        std::lock_guard<std::mutex> lock(cliprdr_mutex_);
        if (!remote_file_transfer_.active ||
            stream_id != remote_file_transfer_.active_stream_id ||
            remote_file_transfer_.current_index >= remote_file_transfer_.entries.size()) {
            return;
        }
        auto& entry = remote_file_transfer_.entries[remote_file_transfer_.current_index];
        if (!ok) {
            if (remote_file_transfer_.awaiting_size && entry.has_known_size) {
                // Fallback to FILEDESCRIPTOR FD_FILESIZE when a server rejects FILECONTENTS_SIZE
                remote_file_transfer_.awaiting_size = false;
                remote_file_transfer_.size_queried = true;
                remote_file_transfer_.current_offset = 0u;
                advance_remote_file_transfer_locked(raw_cliprdr);
                if (!remote_file_transfer_.active && !remote_file_transfer_.top_level_staged_paths.empty()) {
                    completed_roots = std::move(remote_file_transfer_.top_level_staged_paths);
                    remote_file_transfer_ = RemoteFileTransferState{};
                }
            } else {
                abort_remote_file_transfer_locked(raw_cliprdr);
                return;
            }
        } else if (remote_file_transfer_.awaiting_size) {
            if (!data || data_len < 8u) {
                abort_remote_file_transfer_locked(raw_cliprdr);
                return;
            }
            uint64_t remote_sz = 0u;
            for (int b = 0; b < 8; ++b) {
                remote_sz |= (static_cast<uint64_t>(data[b]) << (b * 8));
            }
            if (remote_sz > kMaxRemoteClipTotalBytes ||
                remote_file_transfer_.total_bytes_written + remote_sz > kMaxRemoteClipTotalBytes) {
                abort_remote_file_transfer_locked(raw_cliprdr);
                return;
            }
            entry.file_size = remote_sz;
            entry.has_known_size = true;
            remote_file_transfer_.size_queried = true;
            remote_file_transfer_.awaiting_size = false;
            remote_file_transfer_.current_offset = 0u;

            advance_remote_file_transfer_locked(raw_cliprdr);
            if (!remote_file_transfer_.active && !remote_file_transfer_.top_level_staged_paths.empty()) {
                completed_roots = std::move(remote_file_transfer_.top_level_staged_paths);
                remote_file_transfer_ = RemoteFileTransferState{};
            }
        } else {
            if (!data && data_len > 0u) {
                abort_remote_file_transfer_locked(raw_cliprdr);
                return;
            }
            const std::filesystem::path target_file =
                std::filesystem::path(remote_file_transfer_.batch_root_dir) / entry.local_posix_path;
            const std::string target_file_str = target_file.string();
            if (remote_file_transfer_.active_fd < 0 ||
                remote_file_transfer_.active_file_path != target_file_str) {
                close_active_remote_file_locked(0u);
                const int open_flags = O_WRONLY | O_CREAT |
                    (remote_file_transfer_.current_offset == 0u ? O_TRUNC : O_APPEND);
                int fd = -1;
                do {
                    fd = ::open(target_file_str.c_str(), open_flags, 0644);
                } while (fd < 0 && errno == EINTR);
                if (fd < 0) {
                    abort_remote_file_transfer_locked(raw_cliprdr);
                    return;
                }
                remote_file_transfer_.active_fd = fd;
                remote_file_transfer_.active_file_path = target_file_str;
            }
            if (data_len > 0u) {
                if (remote_file_transfer_.total_bytes_written + data_len > kMaxRemoteClipTotalBytes) {
                    abort_remote_file_transfer_locked(raw_cliprdr);
                    return;
                }
                size_t total_written_chunk = 0u;
                while (total_written_chunk < data_len) {
                    const ssize_t nw = ::write(
                        remote_file_transfer_.active_fd,
                        data + total_written_chunk,
                        data_len - total_written_chunk
                    );
                    if (nw < 0) {
                        if (errno == EINTR) {
                            continue;
                        }
                        abort_remote_file_transfer_locked(raw_cliprdr);
                        return;
                    }
                    if (nw == 0) {
                        abort_remote_file_transfer_locked(raw_cliprdr);
                        return;
                    }
                    total_written_chunk += static_cast<size_t>(nw);
                }
                remote_file_transfer_.current_offset += data_len;
                remote_file_transfer_.total_bytes_written += data_len;
            } else {
                // Early EOF from remote application stream
                entry.file_size = remote_file_transfer_.current_offset;
            }

            advance_remote_file_transfer_locked(raw_cliprdr);
            if (!remote_file_transfer_.active && !remote_file_transfer_.top_level_staged_paths.empty()) {
                completed_roots = std::move(remote_file_transfer_.top_level_staged_paths);
                remote_file_transfer_ = RemoteFileTransferState{};
            }
        }
    }

    if (!completed_roots.empty()) {
        channels_.on_remote_clipboard_files(completed_roots);
        gdi_presenter_->notify_frame_ready();
    }
}

void RdpSessionContext::drain_queued_commands_on_event_thread(void* raw_instance) {
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    auto* instance = static_cast<freerdp*>(raw_instance);
    if (!instance || !instance->context) {
        return;
    }

    std::vector<QueuedKeyEvent> keys;
    std::vector<QueuedPointerEvent> pointers;
    std::vector<RdpMonitorLayout> monitors;
    bool send_monitors = false;
    bool send_clipboard = false;
    void* raw_disp = disp_context_.load(std::memory_order_acquire);
    const bool clip_ready = cliprdr_ready_.load(std::memory_order_acquire);
    const auto now_tp = std::chrono::steady_clock::now();

    {
        std::lock_guard<std::mutex> lock(input_mutex_);
        if (wakeup_event_ && wakeup_signaled_.exchange(false, std::memory_order_acq_rel)) {
            ResetEvent(static_cast<HANDLE>(wakeup_event_));
        }

        if (awaiting_format_list_response_ &&
            (now_tp - format_list_sent_tp_) >= std::chrono::milliseconds(350)) {
            awaiting_format_list_response_ = false;
            if (!pending_clipboard_announce_ && pending_synchronized_paste_) {
                pending_synchronized_paste_ = false;
                enqueue_ctrl_v_locked();
            }
        }

        if (pending_clipboard_announce_ && clip_ready && !awaiting_format_list_response_) {
            pending_clipboard_announce_ = false;
            awaiting_format_list_response_ = true;
            format_list_sent_tp_ = now_tp;
            send_clipboard = true;
        }

        if (pending_logoff_command_ && now_tp >= logoff_command_due_tp_) {
            pending_logoff_command_ = false;
            // Type "logoff" + Enter into the Windows Run dialog opened by Win+R
            constexpr uint16_t kLogoffScancodes[7] = {
                0x26u, // l
                0x18u, // o
                0x22u, // g
                0x18u, // o
                0x21u, // f
                0x21u, // f
                0x1Cu  // Enter
            };
            for (uint16_t sc : kLogoffScancodes) {
                pending_keys_.push_back(QueuedKeyEvent{sc, /*key_down=*/true, /*extended=*/false});
                pending_keys_.push_back(QueuedKeyEvent{sc, /*key_down=*/false, /*extended=*/false});
            }
        }

        if (!pending_keys_.empty()) {
            keys.swap(pending_keys_);
        }
        if (!pending_pointer_events_.empty()) {
            // If a new local clipboard CB_FORMAT_LIST is currently awaiting rdpclip.exe acknowledgement,
            // briefly gate right-mouse-button-down (<220 ms) so Windows Explorer's context menu sees
            // the newly announced FileGroupDescriptorW / text formats and enables "Paste".
            bool hold_for_right_click_clip_sync = false;
            if (params_.redirect_clipboard && clip_ready &&
                (pending_clipboard_announce_ || awaiting_format_list_response_) &&
                (now_tp - format_list_sent_tp_) < std::chrono::milliseconds(220)) {
                for (const auto& ev : pending_pointer_events_) {
                    if (!ev.is_extended && (ev.flags & 0xA000u) == 0xA000u) {
                        hold_for_right_click_clip_sync = true;
                        break;
                    }
                }
            }
            if (!hold_for_right_click_clip_sync) {
                pointers.swap(pending_pointer_events_);
            }
        }
        if (has_pending_monitors_ && raw_disp != nullptr) {
            monitors = pending_monitors_;
            has_pending_monitors_ = false;
            send_monitors = true;
        }
    }

    // Transmit CB_FORMAT_LIST BEFORE synthetic scancodes / pointer clicks
    if (send_clipboard) {
        std::shared_ptr<const std::vector<RdpClipboardFileEntry>> prebound_files;
        if (channels_.local_clipboard_kind() == RdpClipboardContentKind::Files) {
            prebound_files = channels_.local_clipboard_files();
        }
        bool sent_wire_pdu = false;
        {
            std::lock_guard<std::mutex> clip_lock(cliprdr_mutex_);
            has_active_server_clip_data_id_ = false;
            active_server_clip_data_id_ = 0u;
            active_fgd_snapshot_ = (prebound_files && !prebound_files->empty()) ? prebound_files : nullptr;
            if (cliprdr_context_) {
                auto* cliprdr = static_cast<CliprdrClientContext*>(cliprdr_context_);
                cliprdr_send_client_format_list(
                    cliprdr,
                    this,
                    /*is_initial_monitor_ready=*/false,
                    &sent_wire_pdu
                );
            }
        }
        if (!sent_wire_pdu) {
            std::lock_guard<std::mutex> lock(input_mutex_);
            awaiting_format_list_response_ = false;
            if (!pending_clipboard_announce_ && pending_synchronized_paste_) {
                pending_synchronized_paste_ = false;
                enqueue_ctrl_v_locked();
                keys.insert(keys.end(), pending_keys_.begin(), pending_keys_.end());
                pending_keys_.clear();
            }
            if (!pending_pointer_events_.empty()) {
                pointers.insert(
                    pointers.end(),
                    pending_pointer_events_.begin(),
                    pending_pointer_events_.end()
                );
                pending_pointer_events_.clear();
            }
        }
    }

    if (instance->context->input) {
        for (const auto& ev : pointers) {
            if (ev.is_extended) {
                freerdp_input_send_extended_mouse_event(instance->context->input, ev.flags, ev.x, ev.y);
            } else {
                freerdp_input_send_mouse_event(instance->context->input, ev.flags, ev.x, ev.y);
            }
        }
        for (const auto& key : keys) {
            UINT16 flags = key.key_down ? 0u : KBD_FLAGS_RELEASE;
            if (key.extended) {
                flags |= KBD_FLAGS_EXTENDED;
            }
            freerdp_input_send_keyboard_event(
                instance->context->input,
                flags,
                static_cast<UINT8>(key.scancode & 0xFFu)
            );
        }
    }

    if (send_monitors && raw_disp) {
        auto* disp = static_cast<DispClientContext*>(raw_disp);
        if (disp->SendMonitorLayout && !monitors.empty()) {
            UINT32 orientation = ORIENTATION_LANDSCAPE;
            if (instance->context->settings) {
                orientation = freerdp_settings_get_uint16(
                    instance->context->settings,
                    FreeRDP_DesktopOrientation
                );
            }
            const bool h264_cap = params_.uses_h264_codec();
            std::vector<DISPLAY_CONTROL_MONITOR_LAYOUT> layouts;
            layouts.reserve(monitors.size());
            for (const auto& m : monitors) {
                const auto [w, h] = clamp_rdp_surface_dimensions(
                    m.width,
                    m.height,
                    DISPLAY_CONTROL_MIN_MONITOR_WIDTH,
                    DISPLAY_CONTROL_MIN_MONITOR_HEIGHT,
                    DISPLAY_CONTROL_MAX_MONITOR_WIDTH,
                    DISPLAY_CONTROL_MAX_MONITOR_HEIGHT,
                    h264_cap
                );
                const uint32_t desk_scale = std::clamp(m.desktop_scale_factor, 100u, 500u);
                const uint32_t dev_scale = map_device_scale_factor(desk_scale);
                const uint32_t effective_dpi = (96u * desk_scale) / 100u;
                const uint32_t dpi_x10 = std::max<uint32_t>(960u, effective_dpi * 10u);
                const uint32_t phys_w = std::clamp<uint32_t>(
                    m.physical_width_mm > 0 ? m.physical_width_mm : ((w * 254u) / dpi_x10),
                    DISPLAY_CONTROL_MIN_PHYSICAL_MONITOR_WIDTH,
                    DISPLAY_CONTROL_MAX_PHYSICAL_MONITOR_WIDTH
                );
                const uint32_t phys_h = std::clamp<uint32_t>(
                    m.physical_height_mm > 0 ? m.physical_height_mm : ((h * 254u) / dpi_x10),
                    DISPLAY_CONTROL_MIN_PHYSICAL_MONITOR_HEIGHT,
                    DISPLAY_CONTROL_MAX_PHYSICAL_MONITOR_HEIGHT
                );

                DISPLAY_CONTROL_MONITOR_LAYOUT layout{};
                layout.Flags = m.is_primary ? DISPLAY_CONTROL_MONITOR_PRIMARY : 0u;
                layout.Left = m.left;
                layout.Top = m.top;
                layout.Width = w;
                layout.Height = h;
                layout.PhysicalWidth = phys_w;
                layout.PhysicalHeight = phys_h;
                layout.Orientation = orientation;
                layout.DesktopScaleFactor = desk_scale;
                layout.DeviceScaleFactor = dev_scale;
                layouts.push_back(layout);
            }
            const UINT rc = disp->SendMonitorLayout(
                disp,
                static_cast<UINT32>(layouts.size()),
                layouts.data()
            );
            if (rc != CHANNEL_RC_OK) {
                NX_RDP_LOGE("DISP_DVC SendMonitorLayout returned error code %u", rc);
            } else {
                NX_RDP_LOGI("DISP_DVC monitor layout transmitted: %zu monitor(s), primary=%ux%u @ %u%%",
                            layouts.size(), layouts[0].Width, layouts[0].Height, layouts[0].DesktopScaleFactor);
            }
        }
    }
#else
    (void)raw_instance;
#endif
}

void RdpSessionContext::render_diagnostic_surface_frame(uint32_t width, uint32_t height) {
    if (width == 0 || height == 0) {
        return;
    }
    std::vector<uint8_t> frame(static_cast<size_t>(width) * static_cast<size_t>(height) * 4u, 0);
    const uint32_t header_h = std::min<uint32_t>(height, 44u);
    const uint32_t accent_h = std::min<uint32_t>(height, 3u);

    for (uint32_t y = 0; y < height; ++y) {
        uint8_t* row = frame.data() + static_cast<size_t>(y) * static_cast<size_t>(width) * 4u;
        uint8_t r = 0x0B;
        uint8_t g = 0x10;
        uint8_t b = 0x1B;
        if (y < accent_h) {
            // NexusTS RDP cobalt accent bar
            r = 0x3B;
            g = 0x82;
            b = 0xF6;
        } else if (y < header_h) {
            // Subtle elevated chrome bar
            r = 0x14;
            g = 0x1E;
            b = 0x33;
        } else if ((y % 32u) == 0u) {
            // Subtle dark diagnostic grid line
            r = 0x10;
            g = 0x17;
            b = 0x26;
        }
        for (uint32_t x = 0; x < width; ++x) {
            const bool vgrid = (y >= header_h) && ((x % 32u) == 0u);
            const uint8_t pr = vgrid ? 0x10 : r;
            const uint8_t pg = vgrid ? 0x17 : g;
            const uint8_t pb = vgrid ? 0x26 : b;
#ifdef __APPLE__
            row[x * 4u + 0] = pb;
            row[x * 4u + 1] = pg;
            row[x * 4u + 2] = pr;
            row[x * 4u + 3] = 0xFF;
#else
            row[x * 4u + 0] = pr;
            row[x * 4u + 1] = pg;
            row[x * 4u + 2] = pb;
            row[x * 4u + 3] = 0xFF;
#endif
        }
    }
    gdi_presenter_->present_frame(frame.data(), width, height, width * 4u);
}

bool RdpSessionContext::connect() {
    std::lock_guard<std::mutex> lifecycle_lock(disconnect_mutex_);
    if (connected_.load(std::memory_order_acquire)) {
        return true;
    }

    // Ensure any prior background event thread has completed before reconnecting
    std::thread stale_worker;
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        if (connected_.load(std::memory_order_relaxed)) {
            return true;
        }
        if (event_loop_thread_.joinable()) {
            stop_requested_.store(true, std::memory_order_release);
            signal_event_loop_wakeup();
            stale_worker = std::move(event_loop_thread_);
        }
    }
    if (stale_worker.joinable()) {
        stale_worker.join();
    }
    event_loop_thread_id_.store(std::thread::id{}, std::memory_order_release);

    const RdpConnectParams connect_params = [this]() {
        std::lock_guard<std::mutex> lock(state_mutex_);
        return params_;
    }();

    set_last_error("");
    auto trim_ascii = [](const std::string& raw) -> std::string {
        const auto first = raw.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) {
            return {};
        }
        const auto last = raw.find_last_not_of(" \t\r\n");
        return raw.substr(first, last - first + 1u);
    };
    std::string effective_host = trim_ascii(connect_params.host);
    const std::string effective_pcb = normalize_hyperv_preconnection_blob(connect_params.preconnection_blob);
    uint16_t effective_port = connect_params.port;
    bool port_from_host_literal = false;
    if (effective_host.size() >= 2u && effective_host.front() == '[' && effective_host.back() == ']') {
        effective_host = effective_host.substr(1u, effective_host.size() - 2u);
    } else {
        const auto first_colon = effective_host.find(':');
        if (first_colon != std::string::npos && effective_host.find(':', first_colon + 1u) == std::string::npos) {
            const std::string port_str = trim_ascii(effective_host.substr(first_colon + 1u));
            const std::string host_part = trim_ascii(effective_host.substr(0u, first_colon));
            if (!host_part.empty() && !port_str.empty() &&
                std::all_of(port_str.begin(), port_str.end(), [](unsigned char c) { return c >= '0' && c <= '9'; })) {
                const unsigned long parsed_port = std::strtoul(port_str.c_str(), nullptr, 10);
                if (parsed_port >= 1ul && parsed_port <= 65535ul) {
                    effective_host = host_part;
                    effective_port = static_cast<uint16_t>(parsed_port);
                    port_from_host_literal = true;
                }
            }
        }
    }
    effective_port = resolve_effective_rdp_port(effective_port, port_from_host_literal, effective_pcb);
    if (effective_host.empty()) {
        set_last_error("DNS_NAME_NOT_FOUND: Hostname is empty.");
        return false;
    }
    stop_requested_.store(false, std::memory_order_release);
    logoff_requested_.store(false, std::memory_order_release);
    reconnecting_.store(false, std::memory_order_release);
    reconnect_attempt_.store(0u, std::memory_order_release);
    wakeup_signaled_.store(false, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> input_lock(input_mutex_);
        pressed_scancodes_.fill(0u);
        awaiting_format_list_response_ = false;
        pending_synchronized_paste_ = false;
        pending_logoff_command_ = false;
    }

    const uint32_t w = gdi_presenter_->width();
    const uint32_t h = gdi_presenter_->height();
    const bool local_audio_out = connect_params.redirect_audio_out && (connect_params.audio_redirection_mode == 0);

#if !defined(NEXUSTS_HAS_FREERDP3_BINARY) || NEXUSTS_HAS_FREERDP3_BINARY == 0
    resolved_runtime_home_ = connect_params.app_home_dir;
    if (connect_params.uses_h264_codec()) {
        h264_decoder_.initialize(w, h, /*avc444_chroma=*/connect_params.gfx_codec_mode == 2);
    }
    if (local_audio_out || connect_params.redirect_mic_in) {
        channels_.start_audio_streams(local_audio_out, connect_params.redirect_mic_in, 48000);
    }
#endif

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::string resolved_home;
    const bool env_ready = ensure_freerdp_runtime_environment(connect_params.app_home_dir, &resolved_home);
    resolved_runtime_home_ = resolved_home;

    std::string effective_username = trim_ascii(connect_params.username);
    std::string effective_domain = trim_ascii(connect_params.domain);
    const std::string effective_gateway_host = trim_ascii(connect_params.rd_gateway_host);
    if (effective_domain.empty()) {
        const auto bs_pos = effective_username.find('\\');
        if (bs_pos != std::string::npos) {
            effective_domain = effective_username.substr(0, bs_pos);
            effective_username = effective_username.substr(bs_pos + 1u);
        }
    }

    freerdp* instance = freerdp_new();
    if (!instance) {
        set_last_error("INTERNAL: freerdp_new() allocation failed.");
        NX_RDP_LOGE("freerdp_new() allocation failed");
        return false;
    }
    instance->PreConnect = &freerdp_on_pre_connect;
    instance->PostConnect = &freerdp_on_post_connect;
    instance->PostDisconnect = &freerdp_on_post_disconnect;
    instance->AuthenticateEx = &freerdp_on_authenticate_ex;
    instance->VerifyCertificateEx = &freerdp_on_verify_certificate_ex;
    instance->VerifyChangedCertificateEx = &freerdp_on_verify_changed_certificate_ex;

    const bool effective_ignore_cert = connect_params.ignore_certificate || !effective_pcb.empty();
    register_freerdp_callbacks(
        instance,
        this,
        effective_ignore_cert,
        env_ready,
        effective_username,
        connect_params.password,
        effective_domain
    );

    if (!freerdp_context_new(instance)) {
        set_last_error(
            "INTERNAL: freerdp_context_new() initialization failed (HOME=" +
            (resolved_home.empty() ? std::string("<unset>") : resolved_home) + ")."
        );
        NX_RDP_LOGE("freerdp_context_new() failed (resolved_home='%s', env_ready=%d)",
                    resolved_home.c_str(), env_ready ? 1 : 0);
        unregister_freerdp_callbacks(instance);
        freerdp_free(instance);
        return false;
    }

    rdpSettings* settings = instance->context->settings;
    if (!resolved_home.empty()) {
        const char* cur_home = freerdp_settings_get_string(settings, FreeRDP_HomePath);
        if (!cur_home || cur_home[0] == '\0') {
            freerdp_settings_set_string(settings, FreeRDP_HomePath, resolved_home.c_str());
        }
        const char* cur_cfg = freerdp_settings_get_string(settings, FreeRDP_ConfigPath);
        if (!cur_cfg || cur_cfg[0] == '\0') {
            const std::string cfg_dir = (std::filesystem::path(resolved_home) / ".config" / "freerdp").string();
            freerdp_settings_set_string(settings, FreeRDP_ConfigPath, cfg_dir.c_str());
        }
    }

    freerdp_settings_set_bool(settings, FreeRDP_SoftwareGdi, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_UseCommonStdioCallbacks, FALSE);
    freerdp_settings_set_uint32(settings, FreeRDP_TlsSecLevel, 0u);
    freerdp_settings_set_uint32(settings, FreeRDP_TcpConnectTimeout, 8000u);
    freerdp_settings_set_bool(settings, FreeRDP_SupportErrorInfoPdu, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_SupportStatusInfoPdu, TRUE);
    // Disable unused UDP multitransport negotiation so Windows Server never stalls probing UDP 3389
    freerdp_settings_set_bool(settings, FreeRDP_SupportMultitransport, FALSE);
    freerdp_settings_set_uint32(settings, FreeRDP_MultitransportFlags, 0u);
    // Request ServerAutoReconnectCookie, RTT auto-detect, Heartbeat PDUs, and fast TCP dead-peer detection
    freerdp_settings_set_bool(settings, FreeRDP_AutoReconnectionEnabled, TRUE);
    freerdp_settings_set_uint32(settings, FreeRDP_AutoReconnectMaxRetries, 20u);
    freerdp_settings_set_bool(settings, FreeRDP_NetworkAutoDetect, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_SupportHeartbeatPdu, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_TcpKeepAlive, TRUE);
    freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveDelay, 10u);
    freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveInterval, 3u);
    freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveRetries, 3u);
    if (effective_gateway_host.empty()) {
        // Prevent FreeRDP proxy_prepare() from hijacking direct LAN RDP sockets via OS HTTPS_PROXY env vars
        freerdp_settings_set_uint32(settings, FreeRDP_ProxyType, PROXY_TYPE_IGNORE);
    }

    freerdp_settings_set_string(settings, FreeRDP_ServerHostname, effective_host.c_str());
    freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, effective_port);
    freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, w);
    freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, h);
    freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, connect_params.color_depth_bits);
    freerdp_settings_set_bool(settings, FreeRDP_ConsoleSession, connect_params.connect_to_console);

    if (connect_params.hidpi_enabled && connect_params.desktop_scale_factor > 100) {
        const uint32_t scale = std::clamp(connect_params.desktop_scale_factor, 100u, 500u);
        const uint32_t dev_scale = map_device_scale_factor(scale);
        const uint32_t effective_dpi = (96u * scale) / 100u;
        const uint32_t dpi_x10 = std::max<uint32_t>(960u, effective_dpi * 10u);
        const uint32_t phys_w = std::clamp<uint32_t>((w * 254u) / dpi_x10, 10u, 10000u);
        const uint32_t phys_h = std::clamp<uint32_t>((h * 254u) / dpi_x10, 10u, 10000u);
        // Per [MS-RDPBCGR] 2.2.1.3.2, Windows ignores DesktopScaleFactor unless DesktopPhysicalWidth/Height are [10..10000] mm
        freerdp_settings_set_uint32(settings, FreeRDP_DesktopPhysicalWidth, phys_w);
        freerdp_settings_set_uint32(settings, FreeRDP_DesktopPhysicalHeight, phys_h);
        freerdp_settings_set_uint32(settings, FreeRDP_DesktopScaleFactor, scale);
        freerdp_settings_set_uint32(settings, FreeRDP_DeviceScaleFactor, dev_scale);
    }

    bool enable_gfx_h264 = false;
    bool enable_gfx_avc444 = false;
    if (connect_params.enable_gfx_pipeline && (connect_params.gfx_codec_mode == 1 || connect_params.gfx_codec_mode == 2)) {
        // Mode 1 = Single-stream H.264 AVC420 (CAPVERSION_81, zero dual-stream OpenH264 DPB corruption).
        // Mode 2 = Dual-stream H.264 AVC444 (only enabled on desktop; Android AMediaCodec lacks High 4:4:4).
        H264_CONTEXT* probe_h264 = h264_context_new(FALSE);
        if (probe_h264) {
            enable_gfx_h264 = true;
#ifndef __ANDROID__
            enable_gfx_avc444 = (connect_params.gfx_codec_mode == 2);
#endif
            h264_context_free(probe_h264);
        }
    }

    // Preserve FreeRDP 3.12's freerdp_settings_new() defaults for FastPathInput/Output (TRUE),
    // FrameMarkerCommandEnabled (TRUE), SurfaceCommandsSupported (TRUE), ConnectionType
    // (CONNECTION_TYPE_AUTODETECT), BitmapCacheEnabled (FALSE), and
    // OffscreenSupportLevel (0). Setting BitmapCacheEnabled or OffscreenSupportLevel directly on
    // raw rdpSettings without OrderSupport[NEG_MEMBLT_INDEX] or prior to PostConnect gdi_init()
    // causes rdp_client_wait_for_activation() to abort with FREERDP_ERROR_CONNECT_TRANSPORT_FAILED (0x0002000D).
    freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, connect_params.enable_gfx_pipeline);
    freerdp_settings_set_bool(settings, FreeRDP_GfxH264, enable_gfx_h264);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, enable_gfx_avc444);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, enable_gfx_avc444);
    freerdp_settings_set_bool(settings, FreeRDP_GfxProgressive, connect_params.enable_gfx_pipeline);
    freerdp_settings_set_bool(settings, FreeRDP_GfxProgressiveV2, connect_params.enable_gfx_pipeline);
    freerdp_settings_set_bool(settings, FreeRDP_GfxPlanar, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxSmallCache, FALSE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxSendQoeAck, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteFxCodec, connect_params.enable_remotefx);
    freerdp_settings_set_bool(settings, FreeRDP_NSCodec, TRUE);

    const bool enable_disp_channel =
        connect_params.enable_dynamic_resize || connect_params.hidpi_enabled || connect_params.multi_monitor_spanning;
    freerdp_settings_set_bool(settings, FreeRDP_SupportDisplayControl, enable_disp_channel);
    freerdp_settings_set_bool(settings, FreeRDP_DynamicResolutionUpdate, enable_disp_channel);
    freerdp_settings_set_bool(settings, FreeRDP_UseMultimon, connect_params.multi_monitor_spanning);

    freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, connect_params.redirect_clipboard);
#if defined(CLIPRDR_FLAG_DEFAULT_MASK)
    freerdp_settings_set_uint32(
        settings,
        FreeRDP_ClipboardFeatureMask,
        connect_params.redirect_clipboard ? static_cast<UINT32>(CLIPRDR_FLAG_DEFAULT_MASK) : 0u
    );
#endif
#ifdef __APPLE__
    // macOS Universal2 static SDK builds with -DWITH_PCSC=OFF and -DWITH_MACAUDIO=OFF; advertising
    // virtual SmartCard or RDPSND without backends stalls Windows Winlogon/SCardSvr by ~1.5-2.0s.
    freerdp_settings_set_bool(settings, FreeRDP_RedirectSmartCards, FALSE);
#else
    freerdp_settings_set_bool(settings, FreeRDP_RedirectSmartCards, connect_params.redirect_smartcard);
#endif
    freerdp_settings_set_bool(settings, FreeRDP_RedirectDrives, connect_params.redirect_drives);
    if (connect_params.redirect_drives && !connect_params.shared_drive_local_path.empty()) {
        const std::string drive_name =
            connect_params.shared_drive_name.empty() ? "NexusTS" : connect_params.shared_drive_local_path.empty() ? "NexusTS" : connect_params.shared_drive_name;
        const char* drive_args[2] = {
            drive_name.c_str(),
            connect_params.shared_drive_local_path.c_str()
        };
        RDPDR_DEVICE* drive_device = freerdp_device_new(RDPDR_DTYP_FILESYSTEM, 2, drive_args);
        if (drive_device) {
            if (!freerdp_device_collection_add(settings, drive_device)) {
                freerdp_device_free(drive_device);
                NX_RDP_LOGE("freerdp_device_collection_add failed for drive '%s'", drive_name.c_str());
            } else {
                NX_RDP_LOGI("Registered RDPDR shared filesystem drive '\\\\tsclient\\%s' -> '%s'",
                            drive_name.c_str(), connect_params.shared_drive_local_path.c_str());
            }
        }
    }
    freerdp_settings_set_bool(settings, FreeRDP_RedirectPrinters, connect_params.redirect_printers);
    freerdp_settings_set_bool(settings, FreeRDP_RedirectSerialPorts, connect_params.redirect_serial_ports);
#ifdef __APPLE__
    (void)local_audio_out;
    freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, FALSE);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteConsoleAudio, connect_params.audio_redirection_mode == 1);
    freerdp_settings_set_bool(settings, FreeRDP_AudioCapture, FALSE);
#else
    freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, local_audio_out);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteConsoleAudio, connect_params.audio_redirection_mode == 1);
    freerdp_settings_set_bool(settings, FreeRDP_AudioCapture, connect_params.redirect_mic_in);
#endif

    freerdp_settings_set_bool(settings, FreeRDP_AllowFontSmoothing, connect_params.enable_font_smoothing);
    freerdp_settings_set_bool(settings, FreeRDP_AllowDesktopComposition, connect_params.enable_desktop_composition);
    freerdp_settings_set_bool(settings, FreeRDP_DisableWallpaper, !connect_params.enable_wallpaper);
    freerdp_settings_set_bool(settings, FreeRDP_DisableFullWindowDrag, !connect_params.enable_full_window_drag);
    freerdp_settings_set_bool(settings, FreeRDP_DisableMenuAnims, !connect_params.enable_menu_animations);
    freerdp_settings_set_bool(settings, FreeRDP_DisableThemes, !connect_params.enable_themes);
    // Pack the individual visual performance booleans into FreeRDP_PerformanceFlags for TS_INFO_PACKET
    freerdp_performance_flags_make(settings);
    if (!connect_params.enable_menu_animations) {
        const UINT32 perf_flags = freerdp_settings_get_uint32(settings, FreeRDP_PerformanceFlags);
        freerdp_settings_set_uint32(settings, FreeRDP_PerformanceFlags, perf_flags | PERF_DISABLE_CURSOR_SHADOW);
    }
    freerdp_settings_set_bool(settings, FreeRDP_CompressionEnabled, connect_params.compression_enabled);

    const bool has_credentials =
        !effective_username.empty() || !connect_params.password.empty() || connect_params.restricted_admin_mode;
    const bool effective_nla = connect_params.nla_security && has_credentials;
    freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, effective_nla);
    freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, connect_params.tls_security);
    freerdp_settings_set_bool(settings, FreeRDP_RestrictedAdminModeRequired, connect_params.restricted_admin_mode);
    freerdp_settings_set_bool(settings, FreeRDP_IgnoreCertificate, effective_ignore_cert);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteApplicationMode, connect_params.remote_app_mode);

    if (!effective_pcb.empty()) {
        // Hyper-V VMConnect (vmms.exe on TCP 2179): send RDP_PRECONNECTION_PDU_V2 before X.224
        // Connection Request and enforce CredSSP/NLA directly without NegotiateSecurityLayer,
        // matching FreeRDP 3.x `/vmconnect:<VM-GUID>[;EnhancedMode=1]`.
        freerdp_settings_set_bool(settings, FreeRDP_SendPreconnectionPdu, TRUE);
        freerdp_settings_set_string(settings, FreeRDP_PreconnectionBlob, effective_pcb.c_str());
        freerdp_settings_set_bool(settings, FreeRDP_NegotiateSecurityLayer, FALSE);
        freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, TRUE);
        freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, FALSE);
        freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE);
        NX_RDP_LOGI("Configured Hyper-V VMConnect Preconnection PDU v2 (host='%s', port=%u, PCB='%s')",
                    effective_host.c_str(), effective_port, effective_pcb.c_str());
    }

    if (!effective_username.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Username, effective_username.c_str());
    }
    if (!connect_params.password.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Password, connect_params.password.c_str());
    }
    if (!effective_domain.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Domain, effective_domain.c_str());
    }
    if (!effective_gateway_host.empty()) {
        freerdp_settings_set_bool(settings, FreeRDP_GatewayEnabled, TRUE);
        freerdp_settings_set_string(settings, FreeRDP_GatewayHostname, effective_gateway_host.c_str());
        freerdp_settings_set_uint32(settings, FreeRDP_GatewayPort, connect_params.rd_gateway_port);
        freerdp_settings_set_bool(settings, FreeRDP_GatewayUseSameCredentials, TRUE);
    }
    if (connect_params.remote_app_mode && !connect_params.remote_app_program.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationProgram, connect_params.remote_app_program.c_str());
        freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationCmdLine, connect_params.remote_app_args.c_str());
    }

    disp_context_.store(nullptr, std::memory_order_release);
    set_cliprdr_context(nullptr);

    HANDLE wakeup_ev = CreateEventA(nullptr, TRUE, FALSE, nullptr);
    {
        std::lock_guard<std::mutex> input_lock(input_mutex_);
        freerdp_instance_ = instance;
        wakeup_event_ = wakeup_ev;
        wakeup_signaled_.store(false, std::memory_order_relaxed);
        if (stop_requested_.load(std::memory_order_acquire) && instance->context) {
            freerdp_abort_connect_context(instance->context);
        }
    }

    const BOOL connect_ok = !stop_requested_.load(std::memory_order_acquire)
        ? freerdp_connect(instance)
        : FALSE;
    if (!connect_ok || stop_requested_.load(std::memory_order_acquire)) {
        if (!connect_ok) {
            const std::string callback_err = last_error();
            const std::string freerdp_err = format_freerdp_last_error(instance->context, effective_host, effective_port);
            std::string captured;
            if (!callback_err.empty() && !freerdp_err.empty()) {
                captured = callback_err + " [" + freerdp_err + "]";
            } else if (!callback_err.empty()) {
                captured = callback_err;
            } else if (!freerdp_err.empty()) {
                captured = freerdp_err;
            } else {
                captured = "CONNECT_TRANSPORT_FAILED: FreeRDP 3.x connection failed to " +
                    effective_host + ":" + std::to_string(effective_port);
            }
            set_last_error(captured);
            NX_RDP_LOGE("FreeRDP 3.x connection failed to %s:%u (%s)",
                        effective_host.c_str(), effective_port, captured.c_str());
        }
        {
            std::lock_guard<std::mutex> input_lock(input_mutex_);
            freerdp_instance_ = nullptr;
            wakeup_event_ = nullptr;
            wakeup_signaled_.store(false, std::memory_order_relaxed);
        }
        if (wakeup_ev) {
            CloseHandle(wakeup_ev);
        }
        disp_context_.store(nullptr, std::memory_order_release);
        set_cliprdr_context(nullptr);
        if (connect_ok) {
            freerdp_disconnect(instance);
        }
        unregister_freerdp_callbacks(instance);
        gdi_free(instance);
        freerdp_context_free(instance);
        freerdp_free(instance);
        return false;
    }

    {
        std::lock_guard<std::mutex> input_lock(input_mutex_);
        wakeup_signaled_.store(false, std::memory_order_relaxed);
        pending_keys_.clear();
        pending_pointer_events_.clear();
        pending_clipboard_announce_ = false;
        awaiting_format_list_response_ = false;
        pending_synchronized_paste_ = false;
        pending_logoff_command_ = false;
        if (has_pending_monitors_ && wakeup_ev) {
            wakeup_signaled_.store(true, std::memory_order_release);
            SetEvent(wakeup_ev);
        }
    }
    connected_.store(true, std::memory_order_release);

    std::thread worker([this, instance, wakeup_ev, effective_host, effective_port, connect_params]() {
        event_loop_thread_id_.store(std::this_thread::get_id(), std::memory_order_release);
        HANDLE handles[64]{};
#ifndef __APPLE__
        constexpr auto kFrameBudget = std::chrono::milliseconds(8); // ~120 Hz ANativeWindow ceiling
        auto last_present_tp = std::chrono::steady_clock::now() - kFrameBudget;
#endif
        auto try_seamless_reconnect = [&]() -> bool {
            if (stop_requested_.load(std::memory_order_acquire) ||
                logoff_requested_.load(std::memory_order_acquire) ||
                !instance || !instance->context) {
                return false;
            }
            const UINT32 err_info = freerdp_error_info(instance);
            const UINT32 last_err = freerdp_get_last_error(instance->context);
            // Only auto-reconnect on transient network drops or server graphics resets — never on
            // intentional user logoff, admin disconnect, session takeover, or credential rejection.
            if (err_info != ERRINFO_SUCCESS && err_info != ERRINFO_GRAPHICS_SUBSYSTEM_FAILED) {
                return false;
            }
            if (last_err == FREERDP_ERROR_CONNECT_CANCELLED ||
                last_err == FREERDP_ERROR_AUTHENTICATION_FAILED ||
                last_err == FREERDP_ERROR_SECURITY_NEGO_CONNECT_FAILED) {
                return false;
            }

            // Let PubSub ChannelDisconnected/ChannelConnected manage disp_context_ & cliprdr_context_
            // so static virtual channels reconnected in-place by rdp_client_reconnect_channels() stay bound.
            set_cliprdr_ready(false);
            {
                std::lock_guard<std::mutex> input_lock(input_mutex_);
                pressed_scancodes_.fill(0u);
                pending_keys_.clear();
                pending_pointer_events_.clear();
                awaiting_format_list_response_ = false;
                pending_synchronized_paste_ = false;
                pending_logoff_command_ = false;
            }

            constexpr uint32_t kMaxAutoReconnectAttempts = 8u;
            constexpr DWORD kBackoffDelaysMs[kMaxAutoReconnectAttempts] = {
                250u, 500u, 1000u, 1500u, 2000u, 2500u, 3000u, 3000u
            };

            for (uint32_t attempt = 1u; attempt <= kMaxAutoReconnectAttempts; ++attempt) {
                if (stop_requested_.load(std::memory_order_acquire) ||
                    logoff_requested_.load(std::memory_order_acquire)) {
                    break;
                }
                reconnecting_.store(true, std::memory_order_release);
                reconnect_attempt_.store(attempt, std::memory_order_release);
                if (!stop_requested_.load(std::memory_order_acquire)) {
                    gdi_presenter_->notify_frame_ready();
                }

                const DWORD delay_ms = kBackoffDelaysMs[attempt - 1u];
                if (wakeup_ev) {
                    WaitForSingleObject(wakeup_ev, delay_ms);
                    if (stop_requested_.load(std::memory_order_acquire) ||
                        logoff_requested_.load(std::memory_order_acquire)) {
                        break;
                    }
                    ResetEvent(wakeup_ev);
                }

                NX_RDP_LOGI("Attempting seamless RDP auto-reconnect %u/%u to %s:%u",
                            attempt, kMaxAutoReconnectAttempts, effective_host.c_str(), effective_port);
                if (freerdp_reconnect(instance)) {
                    reconnecting_.store(false, std::memory_order_release);
                    reconnect_attempt_.store(0u, std::memory_order_release);
                    set_last_error("");
                    if (connect_params.enable_dynamic_resize) {
                        const uint32_t cur_w = last_requested_width_.load(std::memory_order_relaxed);
                        const uint32_t cur_h = last_requested_height_.load(std::memory_order_relaxed);
                        const uint32_t cur_scale = active_scale_percent_.load(std::memory_order_relaxed);
                        if (cur_w >= 320u && cur_h >= 240u) {
                            RdpMonitorLayout primary{};
                            primary.monitor_id = 0;
                            primary.left = 0;
                            primary.top = 0;
                            primary.width = cur_w;
                            primary.height = cur_h;
                            primary.desktop_scale_factor = cur_scale;
                            primary.device_scale_factor = map_device_scale_factor(cur_scale);
                            primary.is_primary = true;
                            std::lock_guard<std::mutex> input_lock(input_mutex_);
                            pending_monitors_ = {primary};
                            has_pending_monitors_ = true;
                        }
                    }
                    if (!stop_requested_.load(std::memory_order_acquire)) {
                        gdi_presenter_->notify_frame_ready();
                    }
                    NX_RDP_LOGI("Seamless RDP auto-reconnect succeeded on attempt %u", attempt);
                    return true;
                }

                if (freerdp_get_last_error(instance->context) == FREERDP_ERROR_CONNECT_CANCELLED) {
                    break;
                }
            }

            reconnecting_.store(false, std::memory_order_release);
            reconnect_attempt_.store(0u, std::memory_order_release);
            if (!stop_requested_.load(std::memory_order_acquire)) {
                gdi_presenter_->notify_frame_ready();
            }
            return false;
        };

        while (!stop_requested_.load(std::memory_order_acquire)) {
            if (!instance->context) {
                break;
            }
            if (freerdp_shall_disconnect_context(instance->context)) {
                if (try_seamless_reconnect()) {
                    continue;
                }
                break;
            }

            if (freerdp_focus_required(instance)) {
                release_all_pressed_keys();
            }

            drain_queued_commands_on_event_thread(instance);

            DWORD count = freerdp_get_event_handles(instance->context, handles, 63);
            if (count == 0) {
                if (try_seamless_reconnect()) {
                    continue;
                }
                break;
            }
            if (wakeup_ev && count < 64u) {
                handles[count++] = wakeup_ev;
            }

            DWORD wait_ms = 60u;
            {
                std::lock_guard<std::mutex> input_lock(input_mutex_);
                if (pending_logoff_command_ || pending_synchronized_paste_ || awaiting_format_list_response_) {
                    wait_ms = 16u;
                }
            }
#ifdef __APPLE__
            if (gdi_presenter_->has_pending_dirty()) {
                wait_ms = 0u;
            }
#else
            if (gdi_presenter_->has_pending_dirty()) {
                const auto elapsed = std::chrono::steady_clock::now() - last_present_tp;
                if (elapsed >= kFrameBudget) {
                    wait_ms = 0u;
                } else {
                    const auto rem = std::chrono::duration_cast<std::chrono::milliseconds>(kFrameBudget - elapsed).count();
                    wait_ms = std::min<DWORD>(wait_ms, static_cast<DWORD>(std::max<int64_t>(1, rem)));
                }
            }
#endif

            const DWORD wait_status = WaitForMultipleObjects(count, handles, FALSE, wait_ms);
            if (wait_status == WAIT_FAILED) {
                if (try_seamless_reconnect()) {
                    continue;
                }
                break;
            }
            if (stop_requested_.load(std::memory_order_acquire)) {
                break;
            }

            drain_queued_commands_on_event_thread(instance);

            if (!freerdp_check_event_handles(instance->context)) {
                gdi_presenter_->flush_pending_frame();
                if (try_seamless_reconnect()) {
                    continue;
                }
                break;
            }
#ifdef __APPLE__
            if (gdi_presenter_->has_pending_dirty()) {
                gdi_presenter_->flush_pending_frame();
            }
#else
            if (gdi_presenter_->has_pending_dirty()) {
                const auto now = std::chrono::steady_clock::now();
                if (now - last_present_tp >= kFrameBudget) {
                    if (gdi_presenter_->flush_pending_frame()) {
                        last_present_tp = now;
                    }
                }
            }
#endif
        }
        reconnecting_.store(false, std::memory_order_release);
        reconnect_attempt_.store(0u, std::memory_order_release);
        gdi_presenter_->flush_pending_frame();
        if (!stop_requested_.load(std::memory_order_acquire) &&
            !logoff_requested_.load(std::memory_order_acquire) &&
            instance->context) {
            const std::string drop_err = format_freerdp_last_error(instance->context, effective_host, effective_port);
            if (!drop_err.empty()) {
                set_last_error(drop_err);
            }
        }
        connected_.store(false, std::memory_order_release);
        if (!stop_requested_.load(std::memory_order_acquire)) {
            gdi_presenter_->notify_frame_ready();
        }
        event_loop_thread_id_.store(std::thread::id{}, std::memory_order_release);
    });
    event_loop_thread_id_.store(worker.get_id(), std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        event_loop_thread_ = std::move(worker);
    }
#else
    (void)effective_port;
    (void)effective_pcb;
    render_diagnostic_surface_frame(w, h);
    connected_.store(true, std::memory_order_release);
#endif

    NX_RDP_LOGI("NexusTS RDP session active (%ux%u @ %u%% HiDPI, CodecMode=%d, Console=%d, DISP_DVC=%d, ClipSync=%d, Drive=%d)",
                w, h, active_scale_percent_.load(), connect_params.gfx_codec_mode, connect_params.connect_to_console,
                connect_params.enable_dynamic_resize, connect_params.redirect_clipboard, connect_params.redirect_drives);
    return true;
}

std::string RdpSessionContext::last_error() const {
    std::lock_guard<std::mutex> lock(error_mutex_);
    return last_error_;
}

void RdpSessionContext::set_last_error(std::string message) {
    std::lock_guard<std::mutex> lock(error_mutex_);
    last_error_ = std::move(message);
}

void RdpSessionContext::logoff_remote_session() {
    logoff_requested_.store(true, std::memory_order_release);
    if (!connected_.load(std::memory_order_acquire) || reconnecting_.load(std::memory_order_acquire)) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(input_mutex_);
    if (!freerdp_instance_) {
        return;
    }
    for (size_t i = 0u; i < pressed_scancodes_.size(); ++i) {
        if (pressed_scancodes_[i] != 0u) {
            pressed_scancodes_[i] = 0u;
            const bool extended = (i >= 256u);
            const uint16_t sc = static_cast<uint16_t>(i & 0xFFu);
            pending_keys_.push_back(QueuedKeyEvent{sc, /*key_down=*/false, extended});
        }
    }
    // Open Windows Run dialog (Win + R), then type "logoff" + Enter after 240 ms
    pending_keys_.push_back(QueuedKeyEvent{0x5Bu, /*key_down=*/true, /*extended=*/true});
    pending_keys_.push_back(QueuedKeyEvent{0x13u, /*key_down=*/true, /*extended=*/false});
    pending_keys_.push_back(QueuedKeyEvent{0x13u, /*key_down=*/false, /*extended=*/false});
    pending_keys_.push_back(QueuedKeyEvent{0x5Bu, /*key_down=*/false, /*extended=*/true});
    pending_logoff_command_ = true;
    logoff_command_due_tp_ = std::chrono::steady_clock::now() + std::chrono::milliseconds(240);
    if (wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
        SetEvent(static_cast<HANDLE>(wakeup_event_));
    }
#else
    connected_.store(false, std::memory_order_release);
    gdi_presenter_->notify_frame_ready();
#endif
}

void RdpSessionContext::request_stop() noexcept {
    stop_requested_.store(true, std::memory_order_release);
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> input_lock(input_mutex_);
    if (freerdp_instance_) {
        auto* inst = static_cast<freerdp*>(freerdp_instance_);
        if (inst->context) {
            freerdp_abort_connect_context(inst->context);
        }
    }
    if (wakeup_event_) {
        wakeup_signaled_.store(true, std::memory_order_release);
        SetEvent(static_cast<HANDLE>(wakeup_event_));
    }
#else
    signal_event_loop_wakeup();
#endif
}

void RdpSessionContext::disconnect() {
    request_stop();

    const auto worker_tid = event_loop_thread_id_.load(std::memory_order_acquire);
    if (worker_tid != std::thread::id{} && worker_tid == std::this_thread::get_id()) {
        return;
    }

    std::lock_guard<std::mutex> lifecycle_lock(disconnect_mutex_);

    std::thread worker_to_join;
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        if (event_loop_thread_.joinable()) {
            worker_to_join = std::move(event_loop_thread_);
        }
    }
    if (worker_to_join.joinable()) {
        if (worker_to_join.get_id() == std::this_thread::get_id()) {
            worker_to_join.detach();
        } else {
            worker_to_join.join();
        }
    }
    event_loop_thread_id_.store(std::thread::id{}, std::memory_order_release);

    const bool was_connected = connected_.exchange(false);
    reconnecting_.store(false, std::memory_order_release);
    reconnect_attempt_.store(0u, std::memory_order_release);
    disp_context_.store(nullptr, std::memory_order_release);
    set_cliprdr_context(nullptr);

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    freerdp* instance_to_free = nullptr;
    HANDLE wakeup_to_close = nullptr;
    {
        std::lock_guard<std::mutex> input_lock(input_mutex_);
        instance_to_free = static_cast<freerdp*>(freerdp_instance_);
        freerdp_instance_ = nullptr;
        wakeup_to_close = static_cast<HANDLE>(wakeup_event_);
        wakeup_event_ = nullptr;
        wakeup_signaled_.store(false, std::memory_order_relaxed);
        pressed_scancodes_.fill(0u);
        pending_keys_.clear();
        pending_pointer_events_.clear();
        pending_monitors_.clear();
        has_pending_monitors_ = false;
        pending_clipboard_announce_ = false;
        awaiting_format_list_response_ = false;
        pending_synchronized_paste_ = false;
        pending_logoff_command_ = false;
    }
    if (wakeup_to_close) {
        CloseHandle(wakeup_to_close);
    }
    if (!was_connected && !instance_to_free) {
        gdi_presenter_->detach_native_window();
        return;
    }
    channels_.stop_audio_streams();
    h264_decoder_.shutdown();
    if (instance_to_free) {
        freerdp_disconnect(instance_to_free);
        unregister_freerdp_callbacks(instance_to_free);
        gdi_free(instance_to_free);
        freerdp_context_free(instance_to_free);
        freerdp_free(instance_to_free);
    }
#else
    if (!was_connected && !freerdp_instance_) {
        gdi_presenter_->detach_native_window();
        return;
    }
    channels_.stop_audio_streams();
    h264_decoder_.shutdown();
#endif
    gdi_presenter_->detach_native_window();
}

bool RdpSessionContext::request_dynamic_display_resize(
    uint32_t new_width,
    uint32_t new_height,
    uint32_t desktop_scale_percent
) {
    const bool h264_cap = params_.uses_h264_codec();
    const auto [aligned_w, aligned_h] = clamp_rdp_surface_dimensions(
        new_width,
        new_height,
        320u,
        240u,
        8192u,
        8192u,
        h264_cap
    );
    const uint32_t clamped_scale = std::clamp(desktop_scale_percent, 100u, 500u);

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    const uint32_t prev_w = last_requested_width_.load(std::memory_order_relaxed);
    const uint32_t prev_h = last_requested_height_.load(std::memory_order_relaxed);
    const uint32_t prev_scale = active_scale_percent_.load(std::memory_order_relaxed);
    const int diff_w = static_cast<int>(aligned_w) - static_cast<int>(prev_w);
    const int diff_h = static_cast<int>(aligned_h) - static_cast<int>(prev_h);
    if (diff_w >= -2 && diff_w <= 2 &&
        diff_h >= -2 && diff_h <= 2 &&
        clamped_scale == prev_scale) {
        return true;
    }
    last_requested_width_.store(aligned_w, std::memory_order_relaxed);
    last_requested_height_.store(aligned_h, std::memory_order_relaxed);
    active_scale_percent_.store(clamped_scale, std::memory_order_relaxed);
#else
    if (aligned_w == gdi_presenter_->width() &&
        aligned_h == gdi_presenter_->height() &&
        clamped_scale == active_scale_percent_.load()) {
        return true;
    }
    if (aligned_w != gdi_presenter_->width() || aligned_h != gdi_presenter_->height()) {
        gdi_presenter_->resize_shadow_framebuffer(aligned_w, aligned_h);
        h264_decoder_.reconfigure_resolution(aligned_w, aligned_h);
        render_diagnostic_surface_frame(aligned_w, aligned_h);
    }
    last_requested_width_.store(aligned_w, std::memory_order_relaxed);
    last_requested_height_.store(aligned_h, std::memory_order_relaxed);
    active_scale_percent_.store(clamped_scale, std::memory_order_relaxed);
#endif

    RdpMonitorLayout primary{};
    primary.monitor_id = 0;
    primary.left = 0;
    primary.top = 0;
    primary.width = aligned_w;
    primary.height = aligned_h;
    primary.desktop_scale_factor = clamped_scale;
    primary.device_scale_factor = map_device_scale_factor(clamped_scale);
    primary.is_primary = true;

    return update_multi_monitor_topology({primary});
}

bool RdpSessionContext::update_multi_monitor_topology(
    const std::vector<RdpMonitorLayout>& monitors
) {
    if (monitors.empty()) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        params_.monitors = monitors;
    }

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    {
        std::lock_guard<std::mutex> lock(input_mutex_);
        pending_monitors_ = monitors;
        has_pending_monitors_ = true;
        if (wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
            SetEvent(static_cast<HANDLE>(wakeup_event_));
        }
    }
    return disp_context_.load(std::memory_order_acquire) != nullptr;
#else
    NX_RDP_LOGI("DISP_DVC monitor layout updated: %zu monitor(s), primary=%ux%u @ %u%%",
                monitors.size(), monitors[0].width, monitors[0].height, monitors[0].desktop_scale_factor);
    return true;
#endif
}

void RdpSessionContext::send_keyboard_scancode(
    uint16_t rdp_scancode,
    bool key_down,
    bool extended_key
) {
    if (!connected_.load(std::memory_order_acquire) || reconnecting_.load(std::memory_order_acquire)) {
        return;
    }
    const size_t idx = (extended_key ? 256u : 0u) + static_cast<size_t>(rdp_scancode & 0xFFu);
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(input_mutex_);
    if (freerdp_instance_) {
        pressed_scancodes_[idx] = key_down ? 1u : 0u;
        pending_keys_.push_back(QueuedKeyEvent{rdp_scancode, key_down, extended_key});
        if (wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
            SetEvent(static_cast<HANDLE>(wakeup_event_));
        }
    }
#else
    std::lock_guard<std::mutex> lock(input_mutex_);
    pressed_scancodes_[idx] = key_down ? 1u : 0u;
#endif
}

void RdpSessionContext::release_all_pressed_keys() {
    if (!connected_.load(std::memory_order_acquire)) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(input_mutex_);
    if (!freerdp_instance_) {
        pressed_scancodes_.fill(0u);
        return;
    }
    bool any_released = false;
    for (size_t i = 0u; i < pressed_scancodes_.size(); ++i) {
        if (pressed_scancodes_[i] != 0u) {
            pressed_scancodes_[i] = 0u;
            const bool extended = (i >= 256u);
            const uint16_t scancode = static_cast<uint16_t>(i & 0xFFu);
            pending_keys_.push_back(QueuedKeyEvent{scancode, /*key_down=*/false, extended});
            any_released = true;
        }
    }
    if (any_released && wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
        SetEvent(static_cast<HANDLE>(wakeup_event_));
    }
#else
    std::lock_guard<std::mutex> lock(input_mutex_);
    pressed_scancodes_.fill(0u);
#endif
}

void RdpSessionContext::send_pointer_event(
    uint16_t pointer_flags,
    uint16_t x,
    uint16_t y
) {
    if (!connected_.load(std::memory_order_acquire) || reconnecting_.load(std::memory_order_acquire)) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    constexpr uint16_t kPtrFlagsMoveOnly = 0x0800u; // PTR_FLAGS_MOVE
    std::lock_guard<std::mutex> lock(input_mutex_);
    if (freerdp_instance_) {
        if (pointer_flags == kPtrFlagsMoveOnly &&
            !pending_pointer_events_.empty() &&
            !pending_pointer_events_.back().is_extended &&
            pending_pointer_events_.back().flags == kPtrFlagsMoveOnly) {
            // Coalesce consecutive intermediate mouse-move events in O(1) while preserving click causality
            pending_pointer_events_.back().x = x;
            pending_pointer_events_.back().y = y;
        } else {
            pending_pointer_events_.push_back(QueuedPointerEvent{pointer_flags, x, y, false});
        }
        if (wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
            SetEvent(static_cast<HANDLE>(wakeup_event_));
        }
    }
#else
    (void)pointer_flags;
    (void)x;
    (void)y;
#endif
}

void RdpSessionContext::send_extended_pointer_event(
    uint16_t xflags,
    uint16_t x,
    uint16_t y
) {
    if (!connected_.load(std::memory_order_acquire) || reconnecting_.load(std::memory_order_acquire)) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(input_mutex_);
    if (freerdp_instance_) {
        pending_pointer_events_.push_back(QueuedPointerEvent{xflags, x, y, true});
        if (wakeup_event_ && !wakeup_signaled_.exchange(true, std::memory_order_acq_rel)) {
            SetEvent(static_cast<HANDLE>(wakeup_event_));
        }
    }
#else
    (void)xflags;
    (void)x;
    (void)y;
#endif
}

void RdpSessionContext::update_remote_cursor_bitmap(
    uint32_t width,
    uint32_t height,
    uint32_t hot_x,
    uint32_t hot_y,
    const uint8_t* premultiplied_bgra,
    size_t byte_len
) {
    if (!premultiplied_bgra || width == 0u || height == 0u) {
        return;
    }
    const size_t expected = static_cast<size_t>(width) * static_cast<size_t>(height) * 4u;
    if (byte_len < expected) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(cursor_mutex_);
        cursor_width_ = width;
        cursor_height_ = height;
        cursor_hot_x_ = std::min(hot_x, width - 1u);
        cursor_hot_y_ = std::min(hot_y, height - 1u);
        cursor_visible_ = true;
        cursor_is_default_ = false;
        cursor_bgra_.assign(premultiplied_bgra, premultiplied_bgra + expected);
        cursor_seq_.fetch_add(1u, std::memory_order_release);
    }
    gdi_presenter_->notify_frame_ready();
}

void RdpSessionContext::update_remote_cursor_state(bool visible, bool use_default) {
    {
        std::lock_guard<std::mutex> lock(cursor_mutex_);
        cursor_visible_ = visible;
        cursor_is_default_ = use_default;
        if (use_default || !visible) {
            cursor_width_ = 0u;
            cursor_height_ = 0u;
            cursor_hot_x_ = 0u;
            cursor_hot_y_ = 0u;
            cursor_bgra_.clear();
        }
        cursor_seq_.fetch_add(1u, std::memory_order_release);
    }
    gdi_presenter_->notify_frame_ready();
}

size_t RdpSessionContext::copy_cursor_bgra(
    uint8_t* dst_bgra,
    size_t dst_capacity,
    uint32_t* out_width,
    uint32_t* out_height,
    uint32_t* out_hot_x,
    uint32_t* out_hot_y,
    bool* out_visible,
    bool* out_is_default
) const {
    std::lock_guard<std::mutex> lock(cursor_mutex_);
    if (out_width) *out_width = cursor_width_;
    if (out_height) *out_height = cursor_height_;
    if (out_hot_x) *out_hot_x = cursor_hot_x_;
    if (out_hot_y) *out_hot_y = cursor_hot_y_;
    if (out_visible) *out_visible = cursor_visible_;
    if (out_is_default) *out_is_default = cursor_is_default_;
    if (!dst_bgra || dst_capacity == 0u || cursor_bgra_.empty()) {
        return cursor_bgra_.size();
    }
    const size_t n = std::min(dst_capacity, cursor_bgra_.size());
    std::memcpy(dst_bgra, cursor_bgra_.data(), n);
    return n;
}

} // namespace nexusts::rdp
