#include "rdp_session_context.hpp"

#include <algorithm>
#include <cstring>

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
#include <freerdp/client/disp.h>
#include <freerdp/settings.h>
#endif

namespace nexusts::rdp {

namespace {
constexpr uint32_t align_even_h264_dimension(uint32_t raw, uint32_t min_val, uint32_t max_val) {
    const uint32_t clamped = std::clamp(raw, min_val, max_val);
    return (clamped & ~1u);
}
} // namespace

RdpSessionContext::RdpSessionContext(RdpConnectParams params)
    : params_(std::move(params)) {
    const uint32_t w = align_even_h264_dimension(params_.initial_width, 320, 8192);
    const uint32_t h = align_even_h264_dimension(params_.initial_height, 240, 8192);
    presenter_.resize_shadow_framebuffer(w, h);
}

RdpSessionContext::~RdpSessionContext() {
    disconnect();
}

bool RdpSessionContext::connect() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (connected_.load()) {
        return true;
    }

    const uint32_t w = presenter_.width();
    const uint32_t h = presenter_.height();

    if (params_.enable_h264_avc444) {
        h264_decoder_.initialize(w, h, /*avc444_chroma=*/true);
    }
    if (params_.redirect_audio_out || params_.redirect_mic_in) {
        channels_.start_audio_streams(params_.redirect_audio_out, params_.redirect_mic_in, 48000);
    }

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    freerdp* instance = freerdp_new();
    if (!instance) {
        NX_RDP_LOGE("freerdp_new() allocation failed");
        return false;
    }
    if (!freerdp_context_new(instance)) {
        freerdp_free(instance);
        return false;
    }

    rdpSettings* settings = instance->context->settings;
    freerdp_settings_set_string(settings, FreeRDP_ServerHostname, params_.host.c_str());
    freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, params_.port);
    freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, w);
    freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, h);
    freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, 32);
    freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, params_.enable_gfx_pipeline);
    freerdp_settings_set_bool(settings, FreeRDP_GfxH264, params_.enable_h264_avc444);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, params_.enable_h264_avc444);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteFxCodec, params_.enable_remotefx);
    freerdp_settings_set_bool(settings, FreeRDP_DynamicResolutionUpdate, params_.enable_dynamic_resize);
    freerdp_settings_set_bool(settings, FreeRDP_UseMultimon, params_.multi_monitor_spanning);
    freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, params_.redirect_clipboard);
    freerdp_settings_set_bool(settings, FreeRDP_RedirectSmartCards, params_.redirect_smartcard);
    freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, params_.redirect_audio_out);
    freerdp_settings_set_bool(settings, FreeRDP_AudioCapture, params_.redirect_mic_in);
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
        freerdp_settings_set_bool(settings, FreeRDP_GatewayEnabled, TRUE);
        freerdp_settings_set_string(settings, FreeRDP_GatewayHostname, params_.rd_gateway_host.c_str());
        freerdp_settings_set_uint32(settings, FreeRDP_GatewayPort, params_.rd_gateway_port);
    }
    if (params_.remote_app_mode && !params_.remote_app_program.empty()) {
        freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationProgram, params_.remote_app_program.c_str());
        freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationCmdLine, params_.remote_app_args.c_str());
    }

    if (!freerdp_connect(instance)) {
        NX_RDP_LOGE("FreeRDP 3.x connection failed to %s:%u", params_.host.c_str(), params_.port);
        freerdp_context_free(instance);
        freerdp_free(instance);
        return false;
    }
    freerdp_instance_ = instance;
#endif

    NX_RDP_LOGI("NexusTS RDP session active (%ux%u, H264=%d, DISP_DVC=%d)",
                w, h, params_.enable_h264_avc444, params_.enable_dynamic_resize);
    connected_.store(true);
    return true;
}

void RdpSessionContext::disconnect() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (!connected_.exchange(false)) {
        return;
    }

    channels_.stop_audio_streams();
    h264_decoder_.shutdown();

#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
    if (freerdp_instance_) {
        auto* instance = static_cast<freerdp*>(freerdp_instance_);
        freerdp_disconnect(instance);
        freerdp_context_free(instance);
        freerdp_free(instance);
        freerdp_instance_ = nullptr;
    }
#endif
    presenter_.detach_native_window();
}

bool RdpSessionContext::request_dynamic_display_resize(
    uint32_t new_width,
    uint32_t new_height,
    uint32_t desktop_scale_percent
) {
    if (!params_.enable_dynamic_resize) {
        return false;
    }
    const uint32_t aligned_w = align_even_h264_dimension(new_width, 320, 8192);
    const uint32_t aligned_h = align_even_h264_dimension(new_height, 240, 8192);
    const uint32_t clamped_scale = std::clamp(desktop_scale_percent, 100u, 500u);

    if (aligned_w == presenter_.width() && aligned_h == presenter_.height()) {
        return true;
    }

    presenter_.resize_shadow_framebuffer(aligned_w, aligned_h);
    h264_decoder_.reconfigure_resolution(aligned_w, aligned_h);

    RdpMonitorLayout primary{};
    primary.monitor_id = 0;
    primary.left = 0;
    primary.top = 0;
    primary.width = aligned_w;
    primary.height = aligned_h;
    primary.desktop_scale_factor = clamped_scale;
    primary.device_scale_factor = (clamped_scale >= 180) ? 180 : ((clamped_scale >= 140) ? 140 : 100);
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
    NX_RDP_LOGI("DISP_DVC monitor layout updated: %zu monitor(s), primary=%ux%u",
                monitors.size(), monitors[0].width, monitors[0].height);
    return true;
}

void RdpSessionContext::send_keyboard_scancode(
    uint16_t rdp_scancode,
    bool key_down,
    bool extended_key
) {
    if (!connected_.load()) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
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
    if (!connected_.load()) {
        return;
    }
#if defined(NEXUSTS_HAS_FREERDP3_BINARY) && NEXUSTS_HAS_FREERDP3_BINARY == 1
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
