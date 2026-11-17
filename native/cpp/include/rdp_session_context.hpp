#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
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

enum class RdpClipboardContentKind : uint8_t {
    None = 0,
    Text = 1,
    Files = 2
};

struct RdpClipboardFileEntry {
    std::string relative_win_path;
    std::string local_posix_path;
    uint64_t file_size{0};
    uint64_t last_write_filetime{0};
    bool is_directory{false};
    bool has_known_size{true};
};

std::vector<uint8_t> serialize_cliprdr_file_list(const std::vector<RdpClipboardFileEntry>& entries);
bool parse_and_sanitize_cliprdr_file_list(
    const uint8_t* data,
    size_t data_len,
    std::vector<RdpClipboardFileEntry>& out_entries
);
std::vector<RdpClipboardFileEntry> build_local_clipboard_file_entries(
    const std::vector<std::string>& root_paths
);

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

struct RdpDirtyRect {
    uint32_t x{0};
    uint32_t y{0};
    uint32_t w{0};
    uint32_t h{0};
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
    // 0 = ADAPTIVE_PROGRESSIVE (RDPGFX 10.4 Progressive/ClearCodec/Planar/ZGFX — RoyalTS default),
    // 1 = AVC420_VIDEO (Single-stream H.264 4:2:0 + Progressive),
    // 2 = AVC444_CHROMA (Dual-stream H.264 4:4:4)
    int32_t gfx_codec_mode{0};
    bool enable_h264_avc444{false};
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
    // Hyper-V VMConnect ([MS-RDPBCGR] 2.2.1.3.9 Preconnection PDU v2 PCB):
    // "<VM-GUID>" for basic VM console or "<VM-GUID>;EnhancedMode=1" for VMBus Enhanced Session Mode.
    std::string preconnection_blob;
    std::vector<RdpMonitorLayout> monitors;

    constexpr bool uses_h264_codec() const noexcept {
        return enable_gfx_pipeline && (gfx_codec_mode == 1 || gfx_codec_mode == 2);
    }
};

std::string normalize_hyperv_preconnection_blob(
    const std::string& raw_pcb,
    bool force_enhanced_session = false
);

uint16_t resolve_effective_rdp_port(
    uint16_t configured_port,
    bool port_from_host_literal,
    const std::string& normalized_preconnection_blob
) noexcept;

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
    void on_local_clipboard_files(const std::vector<std::string>& root_paths);
    void on_remote_clipboard_text(const std::string& utf8_text);
    void on_remote_clipboard_files(const std::vector<std::string>& staged_root_paths);
    void mark_remote_clipboard_owner_changed();

    RdpClipboardContentKind local_clipboard_kind() const;
    RdpClipboardContentKind remote_clipboard_kind() const;
    std::string local_clipboard_text() const;
    std::shared_ptr<const std::vector<RdpClipboardFileEntry>> local_clipboard_files() const;
    std::string latest_remote_clipboard_text() const;
    std::vector<std::string> latest_remote_clipboard_files() const;
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
    RdpClipboardContentKind local_kind_{RdpClipboardContentKind::None};
    RdpClipboardContentKind remote_kind_{RdpClipboardContentKind::None};
    std::string local_clipboard_utf8_;
    std::shared_ptr<const std::vector<RdpClipboardFileEntry>> local_clipboard_files_;
    std::string remote_clipboard_utf8_;
    std::vector<std::string> remote_clipboard_files_;
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
    void stage_gdi_dirty_rects(
        const uint8_t* primary_buffer,
        uint32_t fb_width,
        uint32_t fb_height,
        uint32_t fb_stride,
        const RdpDirtyRect* rects,
        size_t rect_count
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

    using FrameReadyCallback = void(*)(void* user_data);
    using DirtyRegionConsumer = void(*)(
        const uint8_t* full_frame_pixels,
        uint32_t stride_bytes,
        uint32_t fb_width,
        uint32_t fb_height,
        uint32_t dirty_x,
        uint32_t dirty_y,
        uint32_t dirty_w,
        uint32_t dirty_h,
        uint64_t frame_seq,
        void* user_data
    );
    using MultiDirtyRegionConsumer = void(*)(
        const uint8_t* full_frame_pixels,
        uint32_t stride_bytes,
        uint32_t fb_width,
        uint32_t fb_height,
        const RdpDirtyRect* dirty_rects,
        size_t dirty_rect_count,
        uint64_t frame_seq,
        void* user_data
    );

    void set_frame_ready_callback(FrameReadyCallback cb, void* user_data);
    void notify_frame_ready() const;
    bool consume_dirty_shadow_region(
        bool force_full,
        DirtyRegionConsumer consumer,
        void* user_data
    );
    bool consume_dirty_shadow_rects(
        bool force_full,
        MultiDirtyRegionConsumer consumer,
        void* user_data
    );

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
    static constexpr size_t kMaxClientDirtyRects = 24u;

    void ensure_window_geometry_locked(uint32_t width, uint32_t height);
    void expand_dirty_bounds_locked(uint32_t x, uint32_t y, uint32_t w, uint32_t h);
    void append_client_dirty_rect_locked(uint32_t x, uint32_t y, uint32_t w, uint32_t h, uint32_t fb_w, uint32_t fb_h);
    void mark_full_frame_dirty_locked(uint32_t width, uint32_t height);
    void present_locked();

    mutable std::mutex surface_mutex_;
    mutable std::mutex callback_mutex_;
    FrameReadyCallback frame_ready_cb_{nullptr};
    void* frame_ready_user_data_{nullptr};
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
    bool client_dirty_overflow_{true};
    uint32_t client_dirty_left_{0};
    uint32_t client_dirty_top_{0};
    uint32_t client_dirty_right_{1920};
    uint32_t client_dirty_bottom_{1080};
    std::vector<RdpDirtyRect> client_dirty_rects_;
    std::vector<uint8_t> shadow_rgba_;
};

class RdpSessionContext {
public:
    explicit RdpSessionContext(RdpConnectParams params);
    ~RdpSessionContext();

    bool connect();
    void disconnect();
    void logoff_remote_session();
    bool is_connected() const noexcept { return connected_.load(std::memory_order_acquire); }
    bool is_reconnecting() const noexcept { return reconnecting_.load(std::memory_order_acquire); }
    uint32_t reconnect_attempt() const noexcept { return reconnect_attempt_.load(std::memory_order_acquire); }
    std::string last_error() const;
    void set_last_error(std::string message);

    void on_surface_bound();
    void set_disp_context(void* disp_context) noexcept;
    void set_cliprdr_context(void* cliprdr_context);
    void set_cliprdr_ready(bool ready) noexcept;
    void announce_local_clipboard_to_remote();
    void request_synchronized_paste();

    void on_cliprdr_monitor_ready_sent();
    void on_cliprdr_server_capabilities(uint32_t general_flags) noexcept;
    uint32_t cliprdr_server_capabilities() const noexcept {
        return server_clip_caps_.load(std::memory_order_acquire);
    }
    void on_cliprdr_server_format_list_response(bool ok);
    void lock_local_clipboard_files(uint32_t clip_data_id);
    void unlock_local_clipboard_files(uint32_t clip_data_id);
    std::shared_ptr<const std::vector<RdpClipboardFileEntry>> resolve_local_clipboard_files(
        bool has_clip_data_id,
        uint32_t clip_data_id
    );
    void begin_remote_file_list_fetch(void* cliprdr_ctx, uint32_t format_id);
    uint32_t server_file_group_format_id() const noexcept {
        return server_fgd_format_id_.load(std::memory_order_acquire);
    }
    void handle_remote_file_group_descriptor_response(
        void* cliprdr_ctx,
        const uint8_t* data,
        size_t data_len
    );
    void handle_remote_file_contents_response(
        void* cliprdr_ctx,
        uint32_t stream_id,
        bool ok,
        const uint8_t* data,
        size_t data_len
    );

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

    void release_all_pressed_keys();

    void send_pointer_event(
        uint16_t pointer_flags,
        uint16_t x,
        uint16_t y
    );

    void send_extended_pointer_event(
        uint16_t xflags,
        uint16_t x,
        uint16_t y
    );

    void update_remote_cursor_bitmap(
        uint32_t width,
        uint32_t height,
        uint32_t hot_x,
        uint32_t hot_y,
        const uint8_t* premultiplied_bgra,
        size_t byte_len
    );
    void update_remote_cursor_state(bool visible, bool use_default);
    uint64_t cursor_sequence() const noexcept { return cursor_seq_.load(std::memory_order_acquire); }
    size_t copy_cursor_bgra(
        uint8_t* dst_bgra,
        size_t dst_capacity,
        uint32_t* out_width,
        uint32_t* out_height,
        uint32_t* out_hot_x,
        uint32_t* out_hot_y,
        bool* out_visible,
        bool* out_is_default
    ) const;

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
        bool is_extended{false};
    };

    struct RemoteFileTransferState {
        bool active{false};
        bool has_lock{false};
        uint32_t clip_data_id{0};
        uint64_t batch_id{0};
        std::string batch_root_dir;
        std::vector<RdpClipboardFileEntry> entries;
        std::vector<std::string> top_level_staged_paths;
        size_t current_index{0};
        bool awaiting_size{false};
        uint32_t active_stream_id{0};
        uint64_t current_offset{0};
        uint64_t total_bytes_written{0};
    };

    void render_diagnostic_surface_frame(uint32_t width, uint32_t height);
    void signal_event_loop_wakeup() noexcept;
    void drain_queued_commands_on_event_thread(void* raw_instance);
    void enqueue_ctrl_v_locked();
    void advance_remote_file_transfer_locked(void* cliprdr_ctx);
    void abort_remote_file_transfer_locked(void* cliprdr_ctx);

    RdpConnectParams params_;
    std::string resolved_runtime_home_;
    std::atomic<bool> connected_{false};
    std::atomic<bool> reconnecting_{false};
    std::atomic<uint32_t> reconnect_attempt_{0};
    std::atomic<bool> stop_requested_{false};
    std::atomic<bool> logoff_requested_{false};
    std::atomic<bool> wakeup_signaled_{false};
    std::atomic<uint32_t> active_scale_percent_{100};
    std::atomic<uint32_t> last_requested_width_{0};
    std::atomic<uint32_t> last_requested_height_{0};
    std::mutex state_mutex_;
    std::mutex input_mutex_;
    std::mutex cliprdr_mutex_;
    mutable std::mutex error_mutex_;
    std::string last_error_;
    mutable std::mutex cursor_mutex_;
    std::atomic<uint64_t> cursor_seq_{0};
    uint32_t cursor_width_{0};
    uint32_t cursor_height_{0};
    uint32_t cursor_hot_x_{0};
    uint32_t cursor_hot_y_{0};
    bool cursor_visible_{true};
    bool cursor_is_default_{true};
    std::vector<uint8_t> cursor_bgra_;
    std::unique_ptr<RdpGdiSurfacePresenter> gdi_presenter_;
    RdpVirtualChannelHub channels_;
    RdpHardwareH264Decoder h264_decoder_;
    std::thread event_loop_thread_;
    [[maybe_unused]] void* freerdp_instance_{nullptr};
    [[maybe_unused]] void* wakeup_event_{nullptr};
    std::array<uint8_t, 512> pressed_scancodes_{};
    std::vector<QueuedKeyEvent> pending_keys_;
    std::vector<QueuedPointerEvent> pending_pointer_events_;
    std::vector<RdpMonitorLayout> pending_monitors_;
    [[maybe_unused]] bool has_pending_monitors_{false};
    [[maybe_unused]] bool pending_clipboard_announce_{false};
    [[maybe_unused]] bool awaiting_format_list_response_{false};
    [[maybe_unused]] bool pending_synchronized_paste_{false};
    [[maybe_unused]] bool pending_logoff_command_{false};
    std::chrono::steady_clock::time_point format_list_sent_tp_{};
    std::chrono::steady_clock::time_point logoff_command_due_tp_{};
    std::atomic<void*> disp_context_{nullptr};
    std::atomic<bool> cliprdr_ready_{false};
    std::atomic<uint32_t> server_clip_caps_{0};
    std::atomic<uint32_t> server_fgd_format_id_{0};
    [[maybe_unused]] void* cliprdr_context_{nullptr};
    [[maybe_unused]] uint32_t next_client_clip_data_id_{1};
    [[maybe_unused]] uint32_t next_client_stream_id_{1};
    uint64_t next_remote_clip_batch_id_{1};
    std::unordered_map<uint32_t, std::shared_ptr<const std::vector<RdpClipboardFileEntry>>> locked_local_file_snapshots_;
    RemoteFileTransferState remote_file_transfer_{};
};

} // namespace nexusts::rdp
