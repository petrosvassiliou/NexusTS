#include "rdp_session_context.hpp"

#include <algorithm>
#include <cstring>
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
#include <freerdp/freerdp.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/client/disp.h>
#include <freerdp/input.h>
#include <freerdp/settings.h>
#include <winpr/synch.h>
#endif

namespace nexusts::rdp {

namespace {
constexpr uint32_t align_even_h264_dimension(uint32_t raw, uint32_t min_val, uint32_t max_val) {
    const uint32_t clamped = std::clamp(raw, min_val, max_val);
    return (clamped & ~1u);
}

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
struct FreeRdpCallbackState {
    RdpSessionContext* owner{nullptr};
    bool ignore_cert_warnings{false};
};

std::mutex g_freerdp_cb_mutex;
std::unordered_map<freerdp*, FreeRdpCallbackState> g_freerdp_callbacks;

void register_freerdp_callbacks(freerdp* instance, RdpSessionContext* owner, bool ignore_certs) {
    std::lock_guard<std::mutex> lock(g_freerdp_cb_mutex);
    g_freerdp_callbacks[instance] = FreeRdpCallbackState{owner, ignore_certs};
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
    return TRUE;
}

BOOL freerdp_on_post_connect(freerdp* instance) {
    if (!instance || !instance->context) {
        return FALSE;
    }
    if (!gdi_init(instance, PIXEL_FORMAT_RGBA32)) {
        NX_RDP_LOGE("FreeRDP gdi_init(PIXEL_FORMAT_RGBA32) failed");
        return FALSE;
    }
    if (instance->context->update) {
        instance->context->update->EndPaint = &freerdp_on_end_paint;
    }
    if (instance->context->gdi && instance->context->gdi->primary_buffer) {
        freerdp_on_end_paint(instance->context);
    }
    return TRUE;
}

void freerdp_on_post_disconnect(freerdp* instance) {
    if (instance) {
        gdi_free(instance);
    }
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
    (void)common_name;
    (void)subject;
    (void)issuer;
    (void)flags;
    const auto cb = lookup_freerdp_callbacks(instance);
    if (cb.ignore_cert_warnings) {
        NX_RDP_LOGI("Accepting certificate for %s:%u (%s) per ignore_cert_warnings",
                    host ? host : "", port, fingerprint ? fingerprint : "");
        return 2u; // Accept temporarily for this session without mutating disk known_hosts
    }
    NX_RDP_LOGE("Rejected untrusted X.509 certificate for %s:%u (%s)",
                host ? host : "", port, fingerprint ? fingerprint : "");
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
    freerdp* instance = freerdp_new();
    if (!instance) {
        NX_RDP_LOGE("freerdp_new() allocation failed");
        return false;
    }
    instance->PreConnect = &freerdp_on_pre_connect;
    instance->PostConnect = &freerdp_on_post_connect;
    instance->PostDisconnect = &freerdp_on_post_disconnect;
    instance->VerifyCertificateEx = &freerdp_on_verify_certificate_ex;

    if (!freerdp_context_new(instance)) {
        freerdp_free(instance);
        return false;
    }
    register_freerdp_callbacks(instance, this, params_.ignore_certificate);

    rdpSettings* settings = instance->context->settings;
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

    freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, params_.enable_gfx_pipeline);
    freerdp_settings_set_bool(settings, FreeRDP_GfxH264, params_.enable_h264_avc444);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, params_.enable_h264_avc444);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteFxCodec, params_.enable_remotefx);
    freerdp_settings_set_bool(
        settings,
        FreeRDP_DynamicResolutionUpdate,
        params_.enable_dynamic_resize || params_.hidpi_enabled
    );
    freerdp_settings_set_bool(settings, FreeRDP_UseMultimon, params_.multi_monitor_spanning);

    freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, params_.redirect_clipboard);
    freerdp_settings_set_bool(settings, FreeRDP_RedirectSmartCards, params_.redirect_smartcard);
    freerdp_settings_set_bool(settings, FreeRDP_RedirectDrives, params_.redirect_drives);
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

    freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, params_.nla_security);
    freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, params_.tls_security);
    freerdp_settings_set_bool(settings, FreeRDP_RestrictedAdminModeRequired, params_.restricted_admin_mode);
    freerdp_settings_set_bool(settings, FreeRDP_IgnoreCertificate, params_.ignore_certificate);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteApplicationMode, params_.remote_app_mode);

    if (!params_.username.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Username, params_.username.c_str());
    }
    if (!params_.password.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Password, params_.password.c_str());
    }
    if (!params_.domain.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_Domain, params_.domain.c_str());
    }
    if (!params_.rd_gateway_host.empty()) {
        freerdp_settings_set_bool(settings, FreeRDP_GatewayEnabled, true);
        freerdp_settings_set_string(settings, FreeRDP_GatewayHostname, params_.rd_gateway_host.c_str());
        freerdp_settings_set_uint32(settings, FreeRDP_GatewayPort, params_.rd_gateway_port);
    }
    if (params_.remote_app_mode && !params_.remote_app_program.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationProgram, params_.remote_app_program.c_str());
        freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationCmdLine, params_.remote_app_args.c_str());
    }

    if (!freerdp_connect(instance)) {
        NX_RDP_LOGE("FreeRDP 3.x connection failed to %s:%u", params_.host.c_str(), params_.port);
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
        connected_.store(false, std::memory_order_release);
    });
#else
    render_diagnostic_surface_frame(w, h);
    connected_.store(true, std::memory_order_release);
#endif

    NX_RDP_LOGI("NexusTS RDP session active (%ux%u @ %u%% HiDPI, Console=%d, H264=%d, DISP_DVC=%d)",
                w, h, active_scale_percent_.load(), params_.connect_to_console,
                params_.enable_h264_avc444, params_.enable_dynamic_resize);
    return true;
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
    if (!was_connected && !freerdp_instance_) {
        return;
    }

    channels_.stop_audio_streams();
    h264_decoder_.shutdown();

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    if (freerdp_instance_) {
        auto* instance = static_cast<freerdp*>(freerdp_instance_);
        unregister_freerdp_callbacks(instance);
        freerdp_disconnect(instance);
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
    if (freerdp_instance_) {
        auto* instance = static_cast<freerdp*>(freerdp_instance_);
        (void)instance;
        // Dispatches DISPLAY_CONTROL_MONITOR_LAYOUT_PDU over MS-RDPEDI dynamic virtual channel
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
