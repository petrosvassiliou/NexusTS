#include "rdp_session_context.hpp"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <system_error>
#include <unordered_map>

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
#include <freerdp/codec/h264.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/channels/channels.h>
#include <freerdp/channels/rdpdr.h>
#include <freerdp/channels/rdpgfx.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/client/disp.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/input.h>
#include <freerdp/settings.h>
#include <winpr/environment.h>
#include <winpr/synch.h>
#endif

namespace nexusts::rdp {

namespace {
constexpr uint32_t align_even_h264_dimension(uint32_t raw, uint32_t min_val, uint32_t max_val) {
    const uint32_t clamped = std::clamp(raw, min_val, max_val);
    return (clamped & ~1u);
}

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
std::mutex g_freerdp_env_mutex;
bool g_addin_provider_registered = false;

void set_posix_and_winpr_env(const char* key, const std::string& value) {
    if (!key || value.empty()) {
        return;
    }
#if !defined(_WIN32)
    ::setenv(key, value.c_str(), 1);
#endif
    SetEnvironmentVariableA(key, value.c_str());
}

std::string detect_android_fallback_files_dir() {
#ifdef __ANDROID__
    std::ifstream cmdline("/proc/self/cmdline", std::ios::binary);
    if (cmdline.is_open()) {
        std::string pkg;
        std::getline(cmdline, pkg, '\0');
        // Strip optional Android ":process" suffix
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
 * writable app-private directories BEFORE `freerdp_context_new()` runs (`freerdp_settings_new()`
 * fails immediately on Android/Googlebook OS when `GetKnownPath(KNOWN_PATH_HOME)` is NULL).
 * Also registers FreeRDP's static builtin virtual channel addin table (`tables.c`).
 */
bool ensure_freerdp_runtime_environment(const std::string& preferred_home_dir, std::string* out_resolved_home) {
    std::lock_guard<std::mutex> lock(g_freerdp_env_mutex);

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

    if (!g_addin_provider_registered) {
        if (freerdp_register_addin_provider(freerdp_channels_load_static_addin_entry, 0) == CHANNEL_RC_OK) {
            g_addin_provider_registered = true;
            NX_RDP_LOGI("Registered FreeRDP 3.x static builtin channel addin provider");
        } else {
            NX_RDP_LOGE("freerdp_register_addin_provider(freerdp_channels_load_static_addin_entry) returned non-OK");
        }
    }

    if (out_resolved_home) {
        *out_resolved_home = home_path.string();
    }
    return std::filesystem::exists(home_path, ec);
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

std::mutex g_freerdp_cb_mutex;
std::unordered_map<freerdp*, FreeRdpCallbackState> g_freerdp_callbacks;

void register_freerdp_callbacks(
    freerdp* instance,
    RdpSessionContext* owner,
    bool ignore_certs,
    bool cert_store_writable,
    const std::string& username,
    const std::string& password,
    const std::string& domain
) {
    std::lock_guard<std::mutex> lock(g_freerdp_cb_mutex);
    g_freerdp_callbacks[instance] = FreeRdpCallbackState{
        owner,
        ignore_certs,
        cert_store_writable,
        username,
        password,
        domain
    };
}

void unregister_freerdp_callbacks(freerdp* instance) {
    std::lock_guard<std::mutex> lock(g_freerdp_cb_mutex);
    g_freerdp_callbacks.erase(instance);
}

FreeRdpCallbackState lookup_freerdp_callbacks(freerdp* instance) {
    std::lock_guard<std::mutex> lock(g_freerdp_cb_mutex);
    auto it = g_freerdp_callbacks.find(instance);
    return (it != g_freerdp_callbacks.end()) ? it->second : FreeRdpCallbackState{};
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
    const auto cb = lookup_freerdp_callbacks(context->instance);
    if (cb.owner && gdi->width > 0 && gdi->height > 0) {
        const uint32_t w = static_cast<uint32_t>(gdi->width);
        const uint32_t h = static_cast<uint32_t>(gdi->height);
        const uint32_t stride = gdi->stride > 0 ? gdi->stride : (w * 4u);
        cb.owner->presenter().present_frame(gdi->primary_buffer, w, h, stride);
    }
    if (gdi->primary && gdi->primary->hdc && gdi->primary->hdc->hwnd && gdi->primary->hdc->hwnd->invalid) {
        gdi->primary->hdc->hwnd->invalid->null = TRUE;
        gdi->primary->hdc->hwnd->ninvalid = 0;
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

BOOL freerdp_on_post_connect(freerdp* instance) {
    if (!instance || !instance->context) {
        return FALSE;
    }
    if (!gdi_init(instance, PIXEL_FORMAT_RGBA32)) {
        NX_RDP_LOGE("FreeRDP gdi_init(PIXEL_FORMAT_RGBA32) failed");
        const auto cb = lookup_freerdp_callbacks(instance);
        if (cb.owner) {
            cb.owner->set_last_error("INTERNAL: FreeRDP gdi_init(PIXEL_FORMAT_RGBA32) failed.");
        }
        return FALSE;
    }
    if (instance->context->update) {
        instance->context->update->BeginPaint = &freerdp_on_begin_paint;
        instance->context->update->EndPaint = &freerdp_on_end_paint;
        instance->context->update->DesktopResize = &freerdp_on_desktop_resize;
    }
    if (instance->context->gdi && instance->context->gdi->primary_buffer) {
        freerdp_on_end_paint(instance->context);
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

RdpSessionContext::RdpSessionContext(RdpConnectParams params)
    : params_(std::move(params)),
      active_scale_percent_(std::clamp(params_.desktop_scale_factor, 100u, 500u)),
      gdi_presenter_(std::make_unique<RdpGdiSurfacePresenter>()) {
    const uint32_t w = align_even_h264_dimension(params_.initial_width, 320, 8192);
    const uint32_t h = align_even_h264_dimension(params_.initial_height, 240, 8192);
    gdi_presenter_->resize_shadow_framebuffer(w, h);
#if !defined(NEXUSTS_HAS_FREERDP3_BINARY) || NEXUSTS_HAS_FREERDP3_BINARY == 0
    render_diagnostic_surface_frame(w, h);
#endif
}

RdpSessionContext::~RdpSessionContext() {
    disconnect();
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
            row[x * 4u + 0] = vgrid ? 0x10 : r;
            row[x * 4u + 1] = vgrid ? 0x17 : g;
            row[x * 4u + 2] = vgrid ? 0x26 : b;
            row[x * 4u + 3] = 0xFF;
        }
    }
    gdi_presenter_->present_frame(frame.data(), width, height, width * 4u);
}

bool RdpSessionContext::connect() {
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
            stale_worker = std::move(event_loop_thread_);
        }
    }
    if (stale_worker.joinable()) {
        stale_worker.join();
    }

    std::lock_guard<std::mutex> lock(state_mutex_);
    set_last_error("");
    if (params_.host.empty()) {
        set_last_error("DNS_NAME_NOT_FOUND: Hostname is empty.");
        return false;
    }
    stop_requested_.store(false, std::memory_order_release);

    const uint32_t w = gdi_presenter_->width();
    const uint32_t h = gdi_presenter_->height();
    const bool local_audio_out = params_.redirect_audio_out && (params_.audio_redirection_mode == 0);

    if (params_.enable_h264_avc444) {
        h264_decoder_.initialize(w, h, /*avc444_chroma=*/true);
    }
    if (local_audio_out || params_.redirect_mic_in) {
        channels_.start_audio_streams(local_audio_out, params_.redirect_mic_in, 48000);
    }

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::string resolved_home;
    const bool env_ready = ensure_freerdp_runtime_environment(params_.app_home_dir, &resolved_home);

    std::string effective_username = params_.username;
    std::string effective_domain = params_.domain;
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

    register_freerdp_callbacks(
        instance,
        this,
        params_.ignore_certificate,
        env_ready,
        effective_username,
        params_.password,
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
    freerdp_settings_set_uint32(settings, FreeRDP_TcpConnectTimeout, 15000u);

    freerdp_settings_set_string(settings, FreeRDP_ServerHostname, params_.host.c_str());
    freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, params_.port);
    freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, w);
    freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, h);
    freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, params_.color_depth_bits);
    freerdp_settings_set_bool(settings, FreeRDP_ConsoleSession, params_.connect_to_console);

    if (params_.hidpi_enabled && params_.desktop_scale_factor > 100) {
        const uint32_t scale = std::clamp(params_.desktop_scale_factor, 100u, 500u);
        const uint32_t dev_scale = map_device_scale_factor(scale);
        freerdp_settings_set_uint32(settings, FreeRDP_DesktopScaleFactor, scale);
        freerdp_settings_set_uint32(settings, FreeRDP_DeviceScaleFactor, dev_scale);
    }

    bool h264_runtime_supported = false;
    if (params_.enable_gfx_pipeline && params_.enable_h264_avc444) {
        H264_CONTEXT* probe_h264 = h264_context_new(FALSE);
        if (probe_h264) {
            h264_runtime_supported = true;
            h264_context_free(probe_h264);
        } else {
            NX_RDP_LOGI("FreeRDP H.264 subsystem unavailable on this device; falling back to Progressive/RemoteFX/NSCodec");
        }
    }

    freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, params_.enable_gfx_pipeline);
    freerdp_settings_set_bool(settings, FreeRDP_GfxH264, h264_runtime_supported);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, h264_runtime_supported);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, h264_runtime_supported);
    freerdp_settings_set_bool(settings, FreeRDP_GfxProgressive, params_.enable_gfx_pipeline);
    freerdp_settings_set_bool(settings, FreeRDP_GfxProgressiveV2, params_.enable_gfx_pipeline);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteFxCodec, params_.enable_remotefx);
    freerdp_settings_set_bool(settings, FreeRDP_NSCodec, TRUE);

    const bool enable_disp_channel =
        params_.enable_dynamic_resize || params_.hidpi_enabled || params_.multi_monitor_spanning;
    freerdp_settings_set_bool(settings, FreeRDP_SupportDisplayControl, enable_disp_channel);
    freerdp_settings_set_bool(settings, FreeRDP_DynamicResolutionUpdate, enable_disp_channel);
    freerdp_settings_set_bool(settings, FreeRDP_UseMultimon, params_.multi_monitor_spanning);

    freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, params_.redirect_clipboard);
    freerdp_settings_set_bool(settings, FreeRDP_RedirectSmartCards, params_.redirect_smartcard);
    freerdp_settings_set_bool(settings, FreeRDP_RedirectDrives, params_.redirect_drives);
    if (params_.redirect_drives && !params_.shared_drive_local_path.empty()) {
        const std::string drive_name =
            params_.shared_drive_name.empty() ? "NexusTS" : params_.shared_drive_name;
        const char* drive_args[2] = {
            drive_name.c_str(),
            params_.shared_drive_local_path.c_str()
        };
        RDPDR_DEVICE* drive_device = freerdp_device_new(RDPDR_DTYP_FILESYSTEM, 2, drive_args);
        if (drive_device) {
            if (!freerdp_device_collection_add(settings, drive_device)) {
                freerdp_device_free(drive_device);
                NX_RDP_LOGE("freerdp_device_collection_add failed for drive '%s'", drive_name.c_str());
            } else {
                NX_RDP_LOGI("Registered RDPDR shared filesystem drive '\\\\tsclient\\%s' -> '%s'",
                            drive_name.c_str(), params_.shared_drive_local_path.c_str());
            }
        }
    }
    freerdp_settings_set_bool(settings, FreeRDP_RedirectPrinters, params_.redirect_printers);
    freerdp_settings_set_bool(settings, FreeRDP_RedirectSerialPorts, params_.redirect_serial_ports);
    freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, local_audio_out);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteConsoleAudio, params_.audio_redirection_mode == 1);
    freerdp_settings_set_bool(settings, FreeRDP_AudioCapture, params_.redirect_mic_in);

    freerdp_settings_set_bool(settings, FreeRDP_AllowFontSmoothing, params_.enable_font_smoothing);
    freerdp_settings_set_bool(settings, FreeRDP_AllowDesktopComposition, params_.enable_desktop_composition);
    freerdp_settings_set_bool(settings, FreeRDP_DisableWallpaper, !params_.enable_wallpaper);
    freerdp_settings_set_bool(settings, FreeRDP_DisableFullWindowDrag, !params_.enable_full_window_drag);
    freerdp_settings_set_bool(settings, FreeRDP_DisableMenuAnims, !params_.enable_menu_animations);
    freerdp_settings_set_bool(settings, FreeRDP_DisableThemes, !params_.enable_themes);
    freerdp_settings_set_bool(settings, FreeRDP_CompressionEnabled, params_.compression_enabled);

    const bool has_credentials =
        !effective_username.empty() || !params_.password.empty() || params_.restricted_admin_mode;
    const bool effective_nla = params_.nla_security && has_credentials;
    freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, effective_nla);
    freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, params_.tls_security);
    freerdp_settings_set_bool(settings, FreeRDP_RestrictedAdminModeRequired, params_.restricted_admin_mode);
    freerdp_settings_set_bool(settings, FreeRDP_IgnoreCertificate, params_.ignore_certificate);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteApplicationMode, params_.remote_app_mode);

    if (!effective_username.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Username, effective_username.c_str());
    }
    if (!params_.password.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Password, params_.password.c_str());
    }
    if (!effective_domain.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Domain, effective_domain.c_str());
    }
    if (!params_.rd_gateway_host.empty()) {
        freerdp_settings_set_bool(settings, FreeRDP_GatewayEnabled, TRUE);
        freerdp_settings_set_string(settings, FreeRDP_GatewayHostname, params_.rd_gateway_host.c_str());
        freerdp_settings_set_uint32(settings, FreeRDP_GatewayPort, params_.rd_gateway_port);
        freerdp_settings_set_bool(settings, FreeRDP_GatewayUseSameCredentials, TRUE);
    }
    if (params_.remote_app_mode && !params_.remote_app_program.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationProgram, params_.remote_app_program.c_str());
        freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationCmdLine, params_.remote_app_args.c_str());
    }

    disp_context_.store(nullptr, std::memory_order_release);
    if (!freerdp_connect(instance)) {
        const std::string callback_err = last_error();
        const std::string freerdp_err = format_freerdp_last_error(instance->context, params_.host, params_.port);
        std::string captured;
        if (!callback_err.empty() && !freerdp_err.empty()) {
            captured = callback_err + " [" + freerdp_err + "]";
        } else if (!callback_err.empty()) {
            captured = callback_err;
        } else if (!freerdp_err.empty()) {
            captured = freerdp_err;
        } else {
            captured = "CONNECT_TRANSPORT_FAILED: FreeRDP 3.x connection failed to " +
                params_.host + ":" + std::to_string(params_.port);
        }
        set_last_error(captured);
        NX_RDP_LOGE("FreeRDP 3.x connection failed to %s:%u (%s)",
                    params_.host.c_str(), params_.port, captured.c_str());
        disp_context_.store(nullptr, std::memory_order_release);
        unregister_freerdp_callbacks(instance);
        freerdp_context_free(instance);
        freerdp_free(instance);
        return false;
    }

    freerdp_instance_ = instance;
    connected_.store(true, std::memory_order_release);

    event_loop_thread_ = std::thread([this, instance]() {
        HANDLE handles[64]{};
        while (!stop_requested_.load(std::memory_order_acquire)) {
            if (!instance->context || freerdp_shall_disconnect_context(instance->context)) {
                break;
            }
            const DWORD count = freerdp_get_event_handles(instance->context, handles, 64);
            if (count == 0) {
                break;
            }
            const DWORD wait_status = WaitForMultipleObjects(count, handles, FALSE, 100);
            if (wait_status == WAIT_FAILED) {
                break;
            }
            if (stop_requested_.load(std::memory_order_acquire)) {
                break;
            }
            if (!freerdp_check_event_handles(instance->context)) {
                break;
            }
        }
        if (!stop_requested_.load(std::memory_order_acquire) && instance->context) {
            const std::string drop_err = format_freerdp_last_error(instance->context, params_.host, params_.port);
            if (!drop_err.empty()) {
                set_last_error(drop_err);
            }
        }
        connected_.store(false, std::memory_order_release);
    });
#else
    render_diagnostic_surface_frame(w, h);
    connected_.store(true, std::memory_order_release);
#endif

    NX_RDP_LOGI("NexusTS RDP session active (%ux%u @ %u%% HiDPI, Console=%d, H264=%d, DISP_DVC=%d, Drive=%d)",
                w, h, active_scale_percent_.load(), params_.connect_to_console,
                params_.enable_h264_avc444, params_.enable_dynamic_resize, params_.redirect_drives);
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

void RdpSessionContext::disconnect() {
    stop_requested_.store(true, std::memory_order_release);

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

    std::lock_guard<std::mutex> lock(state_mutex_);
    const bool was_connected = connected_.exchange(false);
    disp_context_.store(nullptr, std::memory_order_release);
    if (!was_connected && !freerdp_instance_) {
        return;
    }

    channels_.stop_audio_streams();
    h264_decoder_.shutdown();

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    if (freerdp_instance_) {
        auto* instance = static_cast<freerdp*>(freerdp_instance_);
        freerdp_disconnect(instance);
        unregister_freerdp_callbacks(instance);
        gdi_free(instance);
        freerdp_context_free(instance);
        freerdp_free(instance);
        freerdp_instance_ = nullptr;
    }
#endif
    gdi_presenter_->detach_native_window();
}

bool RdpSessionContext::request_dynamic_display_resize(
    uint32_t new_width,
    uint32_t new_height,
    uint32_t desktop_scale_percent
) {
    const uint32_t aligned_w = align_even_h264_dimension(new_width, 320, 8192);
    const uint32_t aligned_h = align_even_h264_dimension(new_height, 240, 8192);
    const uint32_t clamped_scale = std::clamp(desktop_scale_percent, 100u, 500u);

    if (aligned_w == gdi_presenter_->width() &&
        aligned_h == gdi_presenter_->height() &&
        clamped_scale == active_scale_percent_.load()) {
        return true;
    }

    if (aligned_w != gdi_presenter_->width() || aligned_h != gdi_presenter_->height()) {
        gdi_presenter_->resize_shadow_framebuffer(aligned_w, aligned_h);
        h264_decoder_.reconfigure_resolution(aligned_w, aligned_h);
#if !defined(NEXUSTS_HAS_FREERDP3_BINARY) || NEXUSTS_HAS_FREERDP3_BINARY == 0
        render_diagnostic_surface_frame(aligned_w, aligned_h);
#endif
    }
    active_scale_percent_.store(clamped_scale);

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
    std::lock_guard<std::mutex> lock(state_mutex_);
    params_.monitors = monitors;

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    void* raw_disp = disp_context_.load(std::memory_order_acquire);
    if (freerdp_instance_ && raw_disp) {
        auto* instance = static_cast<freerdp*>(freerdp_instance_);
        auto* disp = static_cast<DispClientContext*>(raw_disp);
        if (disp->SendMonitorLayout) {
            UINT32 orientation = ORIENTATION_LANDSCAPE;
            if (instance->context && instance->context->settings) {
                orientation = freerdp_settings_get_uint16(
                    instance->context->settings,
                    FreeRDP_DesktopOrientation
                );
            }
            std::vector<DISPLAY_CONTROL_MONITOR_LAYOUT> layouts;
            layouts.reserve(monitors.size());
            for (const auto& m : monitors) {
                const uint32_t w = align_even_h264_dimension(
                    m.width,
                    DISPLAY_CONTROL_MIN_MONITOR_WIDTH,
                    DISPLAY_CONTROL_MAX_MONITOR_WIDTH
                );
                const uint32_t h = align_even_h264_dimension(
                    m.height,
                    DISPLAY_CONTROL_MIN_MONITOR_HEIGHT,
                    DISPLAY_CONTROL_MAX_MONITOR_HEIGHT
                );
                const uint32_t phys_w = std::clamp<uint32_t>(
                    m.physical_width_mm > 0 ? m.physical_width_mm : ((w * 254u) / 960u),
                    DISPLAY_CONTROL_MIN_PHYSICAL_MONITOR_WIDTH,
                    DISPLAY_CONTROL_MAX_PHYSICAL_MONITOR_WIDTH
                );
                const uint32_t phys_h = std::clamp<uint32_t>(
                    m.physical_height_mm > 0 ? m.physical_height_mm : ((h * 254u) / 960u),
                    DISPLAY_CONTROL_MIN_PHYSICAL_MONITOR_HEIGHT,
                    DISPLAY_CONTROL_MAX_PHYSICAL_MONITOR_HEIGHT
                );
                const uint32_t desk_scale = std::clamp(m.desktop_scale_factor, 100u, 500u);
                const uint32_t dev_scale = map_device_scale_factor(desk_scale);

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
                return false;
            }
        }
    }
#endif
    NX_RDP_LOGI("DISP_DVC monitor layout updated: %zu monitor(s), primary=%ux%u @ %u%%",
                monitors.size(), monitors[0].width, monitors[0].height, monitors[0].desktop_scale_factor);
    return true;
}

void RdpSessionContext::send_keyboard_scancode(
    uint16_t rdp_scancode,
    bool key_down,
    bool extended_key
) {
    if (!connected_.load(std::memory_order_acquire)) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (freerdp_instance_) {
        auto* instance = static_cast<freerdp*>(freerdp_instance_);
        UINT16 flags = key_down ? 0 : KBD_FLAGS_RELEASE;
        if (extended_key) {
            flags |= KBD_FLAGS_EXTENDED;
        }
        freerdp_input_send_keyboard_event(instance->context->input, flags, static_cast<UINT8>(rdp_scancode & 0xFF));
    }
#else
    (void)rdp_scancode;
    (void)key_down;
    (void)extended_key;
#endif
}

void RdpSessionContext::send_pointer_event(
    uint16_t pointer_flags,
    uint16_t x,
    uint16_t y
) {
    if (!connected_.load(std::memory_order_acquire)) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (freerdp_instance_) {
        auto* instance = static_cast<freerdp*>(freerdp_instance_);
        freerdp_input_send_mouse_event(instance->context->input, pointer_flags, x, y);
    }
#else
    (void)pointer_flags;
    (void)x;
    (void)y;
#endif
}

} // namespace nexusts::rdp
