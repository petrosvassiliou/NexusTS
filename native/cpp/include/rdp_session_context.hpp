#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifdef __ANDROID__
#include <android/native_window.h>
#else
struct ANativeWindow;
#endif

namespace nexusts::rdp {

constexpr uint32_t map_device_scale_factor(uint32_t desktop_scale_percent) noexcept {
    if (desktop_scale_percent >= 175u) {
        return 180u;
    }
    if (desktop_scale_percent >= 125u) {
        return 140u;
    }
    return 100u;
}

struct RdpMonitorLayout {
    int32_t monitor_id{0};
    int32_t left{0};
    int32_t top{0};
    uint32_t width{1920};
    uint32_t height{1080};
    uint32_t physical_width_mm{510};
    uint32_t physical_height_mm{287};
    uint32_t desktop_scale_factor{100};
    uint32_t device_scale_factor{100};
    bool is_primary{true};
};

struct RdpConnectParams {
    std::string host;
    uint16_t port{3389};
    std::string username;
    std::string password;
    std::string domain;
    std::string rd_gateway_host;
    uint16_t rd_gateway_port{443};
    uint32_t initial_width{1920};
    uint32_t initial_height{1080};
    uint32_t desktop_dpi{96};
    bool connect_to_console{false};
    bool hidpi_enabled{true};
    uint32_t desktop_scale_factor{100};
    uint32_t device_scale_factor{100};
    uint32_t color_depth_bits{32};
    bool enable_gfx_pipeline{true};
    bool enable_h264_avc444{true};
    bool enable_remotefx{true};
    bool enable_dynamic_resize{true};
    bool multi_monitor_spanning{false};
    bool redirect_clipboard{true};
    int32_t audio_redirection_mode{0}; // 0=PLAY_LOCAL, 1=PLAY_ON_REMOTE, 2=DO_NOT_PLAY
    bool redirect_audio_out{true};
    bool redirect_mic_in{false};
    bool redirect_drives{false};
    bool redirect_printers{false};
    bool redirect_serial_ports{false};
    bool redirect_smartcard{true};
    bool enable_font_smoothing{true};
    bool enable_desktop_composition{true};
    bool enable_wallpaper{false};
    bool enable_full_window_drag{false};
    bool enable_menu_animations{false};
    bool enable_themes{true};
    bool compression_enabled{true};
    bool nla_security{true};
    bool tls_security{true};
    bool restricted_admin_mode{false};
    bool ignore_certificate{false};
    bool remote_app_mode{false};
    std::string remote_app_program;
    std::string remote_app_args;
    std::string shared_drive_name;
    std::string shared_drive_local_path;
    std::string app_home_dir;
    std::vector<RdpMonitorLayout> monitors;
};

class RdpHardwareH264Decoder {
public:
    RdpHardwareH264Decoder();
    ~RdpHardwareH264Decoder();

    bool initialize(uint32_t width, uint32_t height, bool avc444_chroma);
    bool decode_nal_unit(
        const uint8_t* nal_data,
        size_t nal_size,
        uint8_t* dst_rgba_buffer,
        uint32_t dst_stride_bytes
    );
    void reconfigure_resolution(uint32_t width, uint32_t height);
    void shutdown();

private:
    bool initialize_locked(uint32_t width, uint32_t height, bool avc444_chroma);
    void shutdown_locked();

    std::mutex codec_mutex_;
    uint32_t width_{0};
    uint32_t height_{0};
    bool avc444_{false};
    bool initialized_{false};
    [[maybe_unused]] void* media_codec_handle_{nullptr};
};

class RdpVirtualChannelHub {
public:
    RdpVirtualChannelHub();
    ~RdpVirtualChannelHub();

    void start_audio_streams(bool speaker_out, bool mic_in, uint32_t sample_rate_hz = 48000);
    void stop_audio_streams();

    void push_pcm_playback(const int16_t* pcm_frames, size_t frame_count);

    void on_local_clipboard_text(const std::string& utf8_text);
    void on_remote_clipboard_text(const std::string& utf8_text);
    std::string local_clipboard_text() const;
    std::string latest_remote_clipboard_text() const;
    uint64_t remote_clipboard_sequence() const noexcept {
        return remote_clipboard_seq_.load(std::memory_order_acquire);
    }
    void set_local_clipboard_announce_handler(std::function<void(const std::string&)> callback);

    std::vector<uint8_t> transmit_smartcard_apdu(const std::vector<uint8_t>& command_apdu);
    void set_smartcard_apdu_handler(
        std::function<std::vector<uint8_t>(const std::vector<uint8_t>&)> callback
    );

private:
    mutable std::mutex channel_mutex_;
    std::string local_clipboard_utf8_;
    std::string remote_clipboard_utf8_;
    std::atomic<uint64_t> remote_clipboard_seq_{0};
    [[maybe_unused]] void* aaudio_out_stream_{nullptr};
    [[maybe_unused]] void* aaudio_in_stream_{nullptr};
    std::function<void(const std::string&)> clipboard_announce_callback_;
    std::function<std::vector<uint8_t>(const std::vector<uint8_t>&)> apdu_callback_;
};

class RdpGdiSurfacePresenter {
public:
    RdpGdiSurfacePresenter();
    ~RdpGdiSurfacePresenter();

    void attach_native_window(ANativeWindow* window);
    void detach_native_window();
    bool has_bound_surface() const noexcept;

    void resize_shadow_framebuffer(uint32_t width, uint32_t height);
    void update_dirty_region(
        uint32_t x,
        uint32_t y,
        uint32_t w,
        uint32_t h,
        const uint8_t* src_rgba,
        uint32_t src_stride
    );
    void stage_gdi_dirty_region(
        const uint8_t* primary_buffer,
        uint32_t fb_width,
        uint32_t fb_height,
        uint32_t fb_stride,
        uint32_t dirty_x,
        uint32_t dirty_y,
        uint32_t dirty_w,
        uint32_t dirty_h
    );
    bool flush_pending_frame();
    void present_frame();
    void present_frame(
        const uint8_t* src_rgba,
        uint32_t width,
        uint32_t height,
        uint32_t src_stride
    );

    uint32_t width() const noexcept { return width_.load(); }
    uint32_t height() const noexcept { return height_.load(); }
    uint64_t frame_sequence() const noexcept { return frame_seq_.load(std::memory_order_acquire); }
    bool has_pending_dirty() const noexcept { return dirty_pending_.load(std::memory_order_acquire); }

    size_t copy_shadow_rgba(
        uint8_t* dst_rgba,
        size_t dst_capacity,
        uint32_t* out_width,
        uint32_t* out_height
    ) const;

    size_t copy_dirty_shadow_pixels(
        uint8_t* dst_pixels,
        size_t dst_capacity,
        uint32_t* out_width,
        uint32_t* out_height,
        uint32_t* out_dirty_x,
        uint32_t* out_dirty_y,
        uint32_t* out_dirty_w,
        uint32_t* out_dirty_h
    );

private:
    void ensure_window_geometry_locked(uint32_t width, uint32_t height);
    void expand_dirty_bounds_locked(uint32_t x, uint32_t y, uint32_t w, uint32_t h);
    void mark_full_frame_dirty_locked(uint32_t width, uint32_t height);
    void present_locked();

    mutable std::mutex surface_mutex_;
    ANativeWindow* native_window_{nullptr};
    uint32_t configured_geom_w_{0};
    uint32_t configured_geom_h_{0};
    std::atomic<uint32_t> width_{1920};
    std::atomic<uint32_t> height_{1080};
    std::atomic<uint64_t> frame_seq_{0};
    std::atomic<bool> dirty_pending_{false};
    bool has_acc_dirty_{true};
    uint32_t acc_dirty_left_{0};
    uint32_t acc_dirty_top_{0};
    uint32_t acc_dirty_right_{1920};
    uint32_t acc_dirty_bottom_{1080};
    bool has_client_dirty_{true};
    uint32_t client_dirty_left_{0};
    uint32_t client_dirty_top_{0};
    uint32_t client_dirty_right_{1920};
    uint32_t client_dirty_bottom_{1080};
    std::vector<uint8_t> shadow_rgba_;
};

class RdpSessionContext {
public:
    explicit RdpSessionContext(RdpConnectParams params);
    ~RdpSessionContext();

    bool connect();
    void disconnect();
    bool is_connected() const noexcept { return connected_.load(); }
    std::string last_error() const;
    void set_last_error(std::string message);

    void on_surface_bound();
    void set_disp_context(void* disp_context) noexcept;
    void set_cliprdr_context(void* cliprdr_context);
    void announce_local_clipboard_to_remote();

    /**
     * Sends MS-RDPEDI (`DISP_DVC`) Display Control PDU to dynamically resize remote desktop
     * on Googlebook OS freeform window drag or HiDPI scale toggle without reconnecting.
     * Aligns dimensions to even multiples of 2 pixels as required by H.264/AVC444 codecs.
     */
    bool request_dynamic_display_resize(
        uint32_t new_width,
        uint32_t new_height,
        uint32_t desktop_scale_percent
    );

    bool update_multi_monitor_topology(const std::vector<RdpMonitorLayout>& monitors);

    void send_keyboard_scancode(
        uint16_t rdp_scancode,
        bool key_down,
        bool extended_key
    );

    void send_pointer_event(
        uint16_t pointer_flags,
        uint16_t x,
        uint16_t y
    );

    RdpGdiSurfacePresenter& presenter() noexcept { return *gdi_presenter_; }
    RdpVirtualChannelHub& channels() noexcept { return channels_; }
    RdpHardwareH264Decoder& decoder() noexcept { return h264_decoder_; }

private:
    struct QueuedKeyEvent {
        uint16_t scancode{0};
        bool key_down{false};
        bool extended{false};
    };

    struct QueuedPointerEvent {
        uint16_t flags{0};
        uint16_t x{0};
        uint16_t y{0};
    };

    void render_diagnostic_surface_frame(uint32_t width, uint32_t height);
    void signal_event_loop_wakeup() noexcept;
    void drain_queued_commands_on_event_thread(void* raw_instance);

    RdpConnectParams params_;
    std::atomic<bool> connected_{false};
    std::atomic<bool> stop_requested_{false};
    std::atomic<uint32_t> active_scale_percent_{100};
    std::atomic<uint32_t> last_requested_width_{0};
    std::atomic<uint32_t> last_requested_height_{0};
    std::mutex state_mutex_;
    std::mutex input_mutex_;
    std::mutex cliprdr_mutex_;
    mutable std::mutex error_mutex_;
    std::string last_error_;
    std::unique_ptr<RdpGdiSurfacePresenter> gdi_presenter_;
    RdpVirtualChannelHub channels_;
    RdpHardwareH264Decoder h264_decoder_;
    std::thread event_loop_thread_;
    [[maybe_unused]] void* freerdp_instance_{nullptr};
    [[maybe_unused]] void* wakeup_event_{nullptr};
    std::vector<QueuedKeyEvent> pending_keys_;
    std::vector<QueuedPointerEvent> pending_pointer_events_;
    std::vector<RdpMonitorLayout> pending_monitors_;
    bool has_pending_monitors_{false};
    bool pending_clipboard_announce_{false};
    std::atomic<void*> disp_context_{nullptr};
    void* cliprdr_context_{nullptr};
};

} // namespace nexusts::rdp
