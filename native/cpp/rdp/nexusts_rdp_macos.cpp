#include "nexusts_rdp_macos.h"
#include "rdp_session_context.hpp"

#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

std::mutex g_macos_rdp_mutex;
int64_t g_next_macos_rdp_handle = 8000;
std::unordered_map<int64_t, std::shared_ptr<nexusts::rdp::RdpSessionContext>> g_macos_rdp_sessions;

std::string safe_cstr(const char* ptr) {
    return ptr ? std::string(ptr) : std::string();
}

std::shared_ptr<nexusts::rdp::RdpSessionContext> lookup_session(int64_t handle) {
    std::lock_guard<std::mutex> lock(g_macos_rdp_mutex);
    auto it = g_macos_rdp_sessions.find(handle);
    return it != g_macos_rdp_sessions.end() ? it->second : nullptr;
}

} // namespace

extern "C" {

int64_t nexusts_rdp_create_session(const NexusRdpConfig* config) {
    if (!config) {
        return 0;
    }
    nexusts::rdp::RdpConnectParams params{};
    params.host = safe_cstr(config->host);
    params.port = config->port == 0 ? 3389 : config->port;
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

    auto ctx = std::make_shared<nexusts::rdp::RdpSessionContext>(std::move(params));
    std::lock_guard<std::mutex> lock(g_macos_rdp_mutex);
    const int64_t handle = ++g_next_macos_rdp_handle;
    g_macos_rdp_sessions.emplace(handle, std::move(ctx));
    return handle;
}

bool nexusts_rdp_connect(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        const bool ok = ctx->connect();
        if (ok) {
            ctx->on_surface_bound();
        }
        return ok;
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

void nexusts_rdp_destroy_session(int64_t handle) {
    std::shared_ptr<nexusts::rdp::RdpSessionContext> removed;
    {
        std::lock_guard<std::mutex> lock(g_macos_rdp_mutex);
        auto it = g_macos_rdp_sessions.find(handle);
        if (it != g_macos_rdp_sessions.end()) {
            removed = std::move(it->second);
            g_macos_rdp_sessions.erase(it);
        }
    }
    if (removed) {
        removed->disconnect();
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

void nexusts_rdp_set_local_clipboard(int64_t handle, const char* utf8_text) {
    if (auto ctx = lookup_session(handle)) {
        ctx->channels().on_local_clipboard_text(safe_cstr(utf8_text));
    }
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

void nexusts_rdp_free_cstr(char* ptr) {
    std::free(ptr);
}

uint64_t nexusts_rdp_frame_sequence(int64_t handle) {
    if (auto ctx = lookup_session(handle)) {
        return ctx->presenter().frame_sequence();
    }
    return 0;
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

} // extern "C"
