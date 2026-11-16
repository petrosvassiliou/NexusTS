#include "rdp_session_context.hpp"

#include <algorithm>
#include <cstring>

#ifdef __ANDROID__
#include <android/native_window.h>
#endif

namespace nexusts::rdp {

namespace {

inline void fill_dark_slate_backdrop(std::vector<uint8_t>& buf, size_t pixel_count) {
    buf.resize(pixel_count * 4u);
    for (size_t i = 0; i < pixel_count; ++i) {
#ifdef __APPLE__
        // BGRX32 memory layout: [B, G, R, X]
        buf[i * 4u + 0] = 0x1B;
        buf[i * 4u + 1] = 0x10;
        buf[i * 4u + 2] = 0x0B;
        buf[i * 4u + 3] = 0xFF;
#else
        // RGBX32 memory layout: [R, G, B, X]
        buf[i * 4u + 0] = 0x0B;
        buf[i * 4u + 1] = 0x10;
        buf[i * 4u + 2] = 0x1B;
        buf[i * 4u + 3] = 0xFF;
#endif
    }
}

} // namespace

RdpGdiSurfacePresenter::RdpGdiSurfacePresenter() {
    fill_dark_slate_backdrop(shadow_rgba_, 1920u * 1080u);
    mark_full_frame_dirty_locked(1920u, 1080u);
}

RdpGdiSurfacePresenter::~RdpGdiSurfacePresenter() {
    detach_native_window();
}

void RdpGdiSurfacePresenter::expand_dirty_bounds_locked(uint32_t x, uint32_t y, uint32_t w, uint32_t h) {
    if (w == 0 || h == 0) {
        return;
    }
    const uint32_t right = x + w;
    const uint32_t bottom = y + h;

    if (!has_acc_dirty_) {
        acc_dirty_left_ = x;
        acc_dirty_top_ = y;
        acc_dirty_right_ = right;
        acc_dirty_bottom_ = bottom;
        has_acc_dirty_ = true;
    } else {
        acc_dirty_left_ = std::min(acc_dirty_left_, x);
        acc_dirty_top_ = std::min(acc_dirty_top_, y);
        acc_dirty_right_ = std::max(acc_dirty_right_, right);
        acc_dirty_bottom_ = std::max(acc_dirty_bottom_, bottom);
    }

    if (!has_client_dirty_) {
        client_dirty_left_ = x;
        client_dirty_top_ = y;
        client_dirty_right_ = right;
        client_dirty_bottom_ = bottom;
        has_client_dirty_ = true;
    } else {
        client_dirty_left_ = std::min(client_dirty_left_, x);
        client_dirty_top_ = std::min(client_dirty_top_, y);
        client_dirty_right_ = std::max(client_dirty_right_, right);
        client_dirty_bottom_ = std::max(client_dirty_bottom_, bottom);
    }
}

void RdpGdiSurfacePresenter::mark_full_frame_dirty_locked(uint32_t width, uint32_t height) {
    acc_dirty_left_ = 0;
    acc_dirty_top_ = 0;
    acc_dirty_right_ = width;
    acc_dirty_bottom_ = height;
    has_acc_dirty_ = (width > 0 && height > 0);

    client_dirty_left_ = 0;
    client_dirty_top_ = 0;
    client_dirty_right_ = width;
    client_dirty_bottom_ = height;
    has_client_dirty_ = (width > 0 && height > 0);
}

void RdpGdiSurfacePresenter::ensure_window_geometry_locked(uint32_t width, uint32_t height) {
#ifdef __ANDROID__
    if (native_window_ && width > 0 && height > 0 &&
        (configured_geom_w_ != width || configured_geom_h_ != height)) {
        ANativeWindow_setBuffersGeometry(
            native_window_,
            static_cast<int32_t>(width),
            static_cast<int32_t>(height),
            WINDOW_FORMAT_RGBX_8888
        );
        configured_geom_w_ = width;
        configured_geom_h_ = height;
        acc_dirty_left_ = 0;
        acc_dirty_top_ = 0;
        acc_dirty_right_ = width;
        acc_dirty_bottom_ = height;
        has_acc_dirty_ = true;
    }
#else
    (void)width;
    (void)height;
#endif
}

void RdpGdiSurfacePresenter::attach_native_window(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(surface_mutex_);
#ifdef __ANDROID__
    if (native_window_ == window) {
        if (native_window_) {
            mark_full_frame_dirty_locked(width_.load(), height_.load());
            present_locked();
        }
        return;
    }
    if (native_window_) {
        ANativeWindow_release(native_window_);
    }
    native_window_ = window;
    configured_geom_w_ = 0;
    configured_geom_h_ = 0;
    if (native_window_) {
        ANativeWindow_acquire(native_window_);
        const uint32_t w = width_.load();
        const uint32_t h = height_.load();
        mark_full_frame_dirty_locked(w, h);
        ensure_window_geometry_locked(w, h);
        present_locked();
    }
#else
    native_window_ = window;
#endif
}

void RdpGdiSurfacePresenter::detach_native_window() {
    std::lock_guard<std::mutex> lock(surface_mutex_);
#ifdef __ANDROID__
    if (native_window_) {
        ANativeWindow_release(native_window_);
    }
#endif
    native_window_ = nullptr;
    configured_geom_w_ = 0;
    configured_geom_h_ = 0;
}

bool RdpGdiSurfacePresenter::has_bound_surface() const noexcept {
    std::lock_guard<std::mutex> lock(surface_mutex_);
    return native_window_ != nullptr;
}

void RdpGdiSurfacePresenter::resize_shadow_framebuffer(uint32_t width, uint32_t height) {
    if (width == 0 || height == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(surface_mutex_);
    if (width == width_.load() && height == height_.load() &&
        shadow_rgba_.size() == static_cast<size_t>(width) * static_cast<size_t>(height) * 4u) {
        ensure_window_geometry_locked(width, height);
        return;
    }
    width_.store(width);
    height_.store(height);
    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    fill_dark_slate_backdrop(shadow_rgba_, pixel_count);
    mark_full_frame_dirty_locked(width, height);
    ensure_window_geometry_locked(width, height);
}

void RdpGdiSurfacePresenter::update_dirty_region(
    uint32_t x,
    uint32_t y,
    uint32_t w,
    uint32_t h,
    const uint8_t* src_rgba,
    uint32_t src_stride
) {
    if (!src_rgba || w == 0 || h == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(surface_mutex_);
    const uint32_t fb_w = width_.load();
    const uint32_t fb_h = height_.load();
    if (x >= fb_w || y >= fb_h) {
        return;
    }
    const uint32_t copy_w = std::min(w, fb_w - x);
    const uint32_t copy_h = std::min(h, fb_h - y);
    const size_t dst_stride = static_cast<size_t>(fb_w) * 4u;
    const size_t row_bytes = static_cast<size_t>(copy_w) * 4u;

    if (x == 0 && copy_w == fb_w && src_stride == dst_stride) {
        std::memcpy(
            shadow_rgba_.data() + static_cast<size_t>(y) * dst_stride,
            src_rgba,
            static_cast<size_t>(copy_h) * dst_stride
        );
    } else {
        for (uint32_t row = 0; row < copy_h; ++row) {
            uint8_t* dst = shadow_rgba_.data() + (static_cast<size_t>(y + row) * dst_stride) + (static_cast<size_t>(x) * 4u);
            const uint8_t* src = src_rgba + (static_cast<size_t>(row) * src_stride);
            std::memcpy(dst, src, row_bytes);
        }
    }
    expand_dirty_bounds_locked(x, y, copy_w, copy_h);
    dirty_pending_.store(true, std::memory_order_release);
}

void RdpGdiSurfacePresenter::stage_gdi_dirty_region(
    const uint8_t* primary_buffer,
    uint32_t fb_width,
    uint32_t fb_height,
    uint32_t fb_stride,
    uint32_t dirty_x,
    uint32_t dirty_y,
    uint32_t dirty_w,
    uint32_t dirty_h
) {
    if (!primary_buffer || fb_width == 0 || fb_height == 0 || dirty_w == 0 || dirty_h == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(surface_mutex_);
    if (fb_width != width_.load() || fb_height != height_.load() ||
        shadow_rgba_.size() != static_cast<size_t>(fb_width) * static_cast<size_t>(fb_height) * 4u) {
        width_.store(fb_width);
        height_.store(fb_height);
        shadow_rgba_.resize(static_cast<size_t>(fb_width) * static_cast<size_t>(fb_height) * 4u);
        mark_full_frame_dirty_locked(fb_width, fb_height);
        ensure_window_geometry_locked(fb_width, fb_height);
        dirty_x = 0;
        dirty_y = 0;
        dirty_w = fb_width;
        dirty_h = fb_height;
    }
    if (dirty_x >= fb_width || dirty_y >= fb_height) {
        return;
    }
    const uint32_t copy_w = std::min(dirty_w, fb_width - dirty_x);
    const uint32_t copy_h = std::min(dirty_h, fb_height - dirty_y);
    const size_t dst_stride = static_cast<size_t>(fb_width) * 4u;
    const size_t src_stride = fb_stride >= dst_stride ? static_cast<size_t>(fb_stride) : dst_stride;

    if (dirty_x == 0 && copy_w == fb_width && src_stride == dst_stride) {
        const size_t byte_offset = static_cast<size_t>(dirty_y) * dst_stride;
        std::memcpy(
            shadow_rgba_.data() + byte_offset,
            primary_buffer + byte_offset,
            static_cast<size_t>(copy_h) * dst_stride
        );
    } else {
        const size_t x_byte = static_cast<size_t>(dirty_x) * 4u;
        const size_t row_bytes = static_cast<size_t>(copy_w) * 4u;
        for (uint32_t row = 0; row < copy_h; ++row) {
            const size_t y_idx = static_cast<size_t>(dirty_y + row);
            std::memcpy(
                shadow_rgba_.data() + y_idx * dst_stride + x_byte,
                primary_buffer + y_idx * src_stride + x_byte,
                row_bytes
            );
        }
    }
    expand_dirty_bounds_locked(dirty_x, dirty_y, copy_w, copy_h);
    dirty_pending_.store(true, std::memory_order_release);
}

bool RdpGdiSurfacePresenter::flush_pending_frame() {
    if (!dirty_pending_.exchange(false, std::memory_order_acq_rel)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(surface_mutex_);
    present_locked();
    return true;
}

void RdpGdiSurfacePresenter::present_frame() {
    std::lock_guard<std::mutex> lock(surface_mutex_);
    dirty_pending_.store(false, std::memory_order_release);
    mark_full_frame_dirty_locked(width_.load(), height_.load());
    present_locked();
}

void RdpGdiSurfacePresenter::present_frame(
    const uint8_t* src_rgba,
    uint32_t width,
    uint32_t height,
    uint32_t src_stride
) {
    if (!src_rgba || width == 0 || height == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(surface_mutex_);
    if (width != width_.load() || height != height_.load() ||
        shadow_rgba_.size() != static_cast<size_t>(width) * static_cast<size_t>(height) * 4u) {
        width_.store(width);
        height_.store(height);
        shadow_rgba_.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 4u);
        ensure_window_geometry_locked(width, height);
    }
    const size_t dst_stride = static_cast<size_t>(width) * 4u;
    const size_t effective_src_stride = src_stride >= dst_stride ? static_cast<size_t>(src_stride) : dst_stride;
    if (effective_src_stride == dst_stride) {
        std::memcpy(shadow_rgba_.data(), src_rgba, static_cast<size_t>(height) * dst_stride);
    } else {
        for (uint32_t r = 0; r < height; ++r) {
            std::memcpy(
                shadow_rgba_.data() + static_cast<size_t>(r) * dst_stride,
                src_rgba + static_cast<size_t>(r) * effective_src_stride,
                dst_stride
            );
        }
    }
    mark_full_frame_dirty_locked(width, height);
    dirty_pending_.store(false, std::memory_order_release);
    present_locked();
}

size_t RdpGdiSurfacePresenter::copy_shadow_rgba(
    uint8_t* dst_rgba,
    size_t dst_capacity,
    uint32_t* out_width,
    uint32_t* out_height
) const {
    std::lock_guard<std::mutex> lock(surface_mutex_);
    const uint32_t w = width_.load();
    const uint32_t h = height_.load();
    if (out_width) {
        *out_width = w;
    }
    if (out_height) {
        *out_height = h;
    }
    if (!dst_rgba || dst_capacity == 0 || shadow_rgba_.empty()) {
        return shadow_rgba_.size();
    }
    const size_t n = std::min(dst_capacity, shadow_rgba_.size());
    std::memcpy(dst_rgba, shadow_rgba_.data(), n);
    return n;
}

size_t RdpGdiSurfacePresenter::copy_dirty_shadow_pixels(
    uint8_t* dst_pixels,
    size_t dst_capacity,
    uint32_t* out_width,
    uint32_t* out_height,
    uint32_t* out_dirty_x,
    uint32_t* out_dirty_y,
    uint32_t* out_dirty_w,
    uint32_t* out_dirty_h
) {
    std::lock_guard<std::mutex> lock(surface_mutex_);
    const uint32_t w = width_.load();
    const uint32_t h = height_.load();
    if (out_width) {
        *out_width = w;
    }
    if (out_height) {
        *out_height = h;
    }
    if (!dst_pixels || dst_capacity < shadow_rgba_.size() || shadow_rgba_.empty() || w == 0 || h == 0) {
        if (out_dirty_x) *out_dirty_x = 0;
        if (out_dirty_y) *out_dirty_y = 0;
        if (out_dirty_w) *out_dirty_w = w;
        if (out_dirty_h) *out_dirty_h = h;
        return shadow_rgba_.size();
    }

    uint32_t dx = 0;
    uint32_t dy = 0;
    uint32_t dw = 0;
    uint32_t dh = 0;
    if (has_client_dirty_ && client_dirty_right_ > client_dirty_left_ && client_dirty_bottom_ > client_dirty_top_) {
        dx = std::min(client_dirty_left_, w);
        dy = std::min(client_dirty_top_, h);
        const uint32_t right = std::min(client_dirty_right_, w);
        const uint32_t bottom = std::min(client_dirty_bottom_, h);
        dw = right > dx ? (right - dx) : 0u;
        dh = bottom > dy ? (bottom - dy) : 0u;
    }
    has_client_dirty_ = false;

    if (out_dirty_x) *out_dirty_x = dx;
    if (out_dirty_y) *out_dirty_y = dy;
    if (out_dirty_w) *out_dirty_w = dw;
    if (out_dirty_h) *out_dirty_h = dh;

    if (dw > 0 && dh > 0) {
        const size_t stride = static_cast<size_t>(w) * 4u;
        if (dx == 0 && dw == w) {
            const size_t offset = static_cast<size_t>(dy) * stride;
            std::memcpy(
                dst_pixels + offset,
                shadow_rgba_.data() + offset,
                static_cast<size_t>(dh) * stride
            );
        } else {
            const size_t x_byte = static_cast<size_t>(dx) * 4u;
            const size_t row_bytes = static_cast<size_t>(dw) * 4u;
            for (uint32_t r = 0; r < dh; ++r) {
                const size_t offset = static_cast<size_t>(dy + r) * stride + x_byte;
                std::memcpy(dst_pixels + offset, shadow_rgba_.data() + offset, row_bytes);
            }
        }
    }
    return shadow_rgba_.size();
}

void RdpGdiSurfacePresenter::present_locked() {
    frame_seq_.fetch_add(1, std::memory_order_release);
#ifdef __ANDROID__
    if (!native_window_ || shadow_rgba_.empty()) {
        return;
    }
    const uint32_t fb_w = width_.load();
    const uint32_t fb_h = height_.load();
    ensure_window_geometry_locked(fb_w, fb_h);

    ARect dirty_rect{
        0,
        0,
        static_cast<int32_t>(fb_w),
        static_cast<int32_t>(fb_h)
    };
    if (has_acc_dirty_ && acc_dirty_right_ > acc_dirty_left_ && acc_dirty_bottom_ > acc_dirty_top_) {
        dirty_rect.left = static_cast<int32_t>(std::min(acc_dirty_left_, fb_w));
        dirty_rect.top = static_cast<int32_t>(std::min(acc_dirty_top_, fb_h));
        dirty_rect.right = static_cast<int32_t>(std::min(acc_dirty_right_, fb_w));
        dirty_rect.bottom = static_cast<int32_t>(std::min(acc_dirty_bottom_, fb_h));
    }
    has_acc_dirty_ = false;

    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(native_window_, &buffer, &dirty_rect) == 0) {
        const uint32_t rows = std::min<uint32_t>(fb_h, static_cast<uint32_t>(buffer.height));
        const uint32_t cols = std::min<uint32_t>(fb_w, static_cast<uint32_t>(buffer.width));
        const uint32_t r0 = std::clamp<int32_t>(dirty_rect.top, 0, static_cast<int32_t>(rows));
        const uint32_t r1 = std::clamp<int32_t>(dirty_rect.bottom, static_cast<int32_t>(r0), static_cast<int32_t>(rows));
        const uint32_t c0 = std::clamp<int32_t>(dirty_rect.left, 0, static_cast<int32_t>(cols));
        const uint32_t c1 = std::clamp<int32_t>(dirty_rect.right, static_cast<int32_t>(c0), static_cast<int32_t>(cols));

        auto* dst_base = static_cast<uint8_t*>(buffer.bits);
        const size_t dst_stride = static_cast<size_t>(buffer.stride) * 4u;
        const size_t src_stride = static_cast<size_t>(fb_w) * 4u;

        if (r1 > r0 && c1 > c0) {
            if (c0 == 0u && c1 == fb_w && dst_stride == src_stride) {
                std::memcpy(
                    dst_base + static_cast<size_t>(r0) * dst_stride,
                    shadow_rgba_.data() + static_cast<size_t>(r0) * src_stride,
                    static_cast<size_t>(r1 - r0) * src_stride
                );
            } else {
                const size_t x_byte = static_cast<size_t>(c0) * 4u;
                const size_t copy_bytes = static_cast<size_t>(c1 - c0) * 4u;
                for (uint32_t r = r0; r < r1; ++r) {
                    std::memcpy(
                        dst_base + static_cast<size_t>(r) * dst_stride + x_byte,
                        shadow_rgba_.data() + static_cast<size_t>(r) * src_stride + x_byte,
                        copy_bytes
                    );
                }
            }
        }
        ANativeWindow_unlockAndPost(native_window_);
    } else {
        // Restore dirty bounds if surface lock transiently failed during geometry transition
        mark_full_frame_dirty_locked(fb_w, fb_h);
    }
#endif
}

} // namespace nexusts::rdp
