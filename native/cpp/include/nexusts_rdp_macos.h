#ifndef NEXUSTS_RDP_MACOS_H
#define NEXUSTS_RDP_MACOS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct NexusRdpMonitorSpec {
    int32_t monitor_id;
    int32_t left;
    int32_t top;
    uint32_t width;
    uint32_t height;
    uint32_t physical_width_mm;
    uint32_t physical_height_mm;
    uint32_t desktop_scale_factor;
    uint32_t device_scale_factor;
    bool is_primary;
} NexusRdpMonitorSpec;

typedef struct NexusRdpConfig {
    const char* host;
    uint16_t port;
    const char* username;
    const char* password;
    const char* domain;
    const char* rd_gateway_host;
    uint16_t rd_gateway_port;
    uint32_t initial_width;
    uint32_t initial_height;
    uint32_t desktop_dpi;
    bool connect_to_console;
    bool hidpi_enabled;
    uint32_t desktop_scale_factor;
    uint32_t device_scale_factor;
    uint32_t color_depth_bits;
    bool enable_gfx_pipeline;
    bool enable_h264_avc444;
    bool enable_remotefx;
    bool enable_dynamic_resize;
    bool multi_monitor_spanning;
    bool redirect_clipboard;
    int32_t audio_redirection_mode;
    bool redirect_audio_out;
    bool redirect_mic_in;
    bool redirect_drives;
    bool redirect_printers;
    bool redirect_serial_ports;
    bool redirect_smartcard;
    bool enable_font_smoothing;
    bool enable_desktop_composition;
    bool enable_wallpaper;
    bool enable_full_window_drag;
    bool enable_menu_animations;
    bool enable_themes;
    bool compression_enabled;
    bool nla_security;
    bool tls_security;
    bool restricted_admin_mode;
    bool ignore_certificate;
    bool remote_app_mode;
    const char* remote_app_program;
    const char* remote_app_args;
    const char* shared_drive_name;
    const char* shared_drive_local_path;
    const char* app_home_dir;
} NexusRdpConfig;

int64_t nexusts_rdp_create_session(const NexusRdpConfig* config);
bool nexusts_rdp_connect(int64_t handle);
void nexusts_rdp_disconnect(int64_t handle);
bool nexusts_rdp_is_connected(int64_t handle);
void nexusts_rdp_destroy_session(int64_t handle);

bool nexusts_rdp_request_resize(
    int64_t handle,
    uint32_t new_width,
    uint32_t new_height,
    uint32_t desktop_scale_percent
);

bool nexusts_rdp_update_monitors(
    int64_t handle,
    const NexusRdpMonitorSpec* monitors,
    size_t monitor_count
);

void nexusts_rdp_send_key_scancode(
    int64_t handle,
    uint16_t rdp_scancode,
    bool key_down,
    bool extended_key
);

void nexusts_rdp_send_pointer_event(
    int64_t handle,
    uint16_t pointer_flags,
    uint16_t x,
    uint16_t y
);

void nexusts_rdp_set_local_clipboard(int64_t handle, const char* utf8_text);
char* nexusts_rdp_copy_remote_clipboard(int64_t handle);
uint64_t nexusts_rdp_remote_clipboard_sequence(int64_t handle);
char* nexusts_rdp_copy_last_error(int64_t handle);
void nexusts_rdp_free_cstr(char* ptr);

uint64_t nexusts_rdp_frame_sequence(int64_t handle);
size_t nexusts_rdp_copy_frame_rgba(
    int64_t handle,
    uint8_t* dst_rgba,
    size_t dst_capacity,
    uint32_t* out_width,
    uint32_t* out_height
);
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
);

#ifdef __cplusplus
}
#endif

#endif /* NEXUSTS_RDP_MACOS_H */
