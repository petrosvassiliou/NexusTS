#include "rdp_session_context.hpp"

#include <algorithm>
#include <cstring>

#ifdef __ANDROID__
#include <android/native_window.h>
#endif

namespace nexusts::rdp {

RdpGdiSurfacePresenter::RdpGdiSurfacePresenter() {
    const size_t pixels = 1920u * 1080u;
    shadow_rgba_.resize(pixels * 4u, 0);
    for (size_t i = 0; i < pixels; ++i) {
        shadow_rgba_[i * 4u + 0] = 0x0B;
        shadow_rgba_[i * 4u + 1] = 0x10;
        shadow_rgba_[i * 4u + 2] = 0x1B;
        shadow_rgba_[i * 4u + 3] = 0xFF;
    }
}

RdpGdiSurfacePresenter::~RdpGdiSurfacePresenter() {
    detach_native_window();
}

void RdpGdiSurfacePresenter::ensure_window_geometry_locked(uint32_t width, uint32_t height) {
#ifdef __ANDROID__
    if (native_window_ && width > 0 && height > 0 &&
        (configured_geom_w_ != width || configured_geom_h_ != height)) {
        ANativeWindow_setBuffersGeometry(
            native_window_,
            static_cast<int32_t>(width),
            static_cast<int32_t>(height),
            WINDOW_FORMAT_RGBA_8888
        );
        configured_geom_w_ = width;
        configured_geom_h_ = height;
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
        ensure_window_geometry_locked(width_.load(), height_.load());
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
    width_.store(width);
    height_.store(height);
    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    shadow_rgba_.resize(pixel_count * 4u);
    for (size_t i = 0; i < pixel_count; ++i) {
        shadow_rgba_[i * 4u + 0] = 0x0B;
        shadow_rgba_[i * 4u + 1] = 0x10;
        shadow_rgba_[i * 4u + 2] = 0x1B;
        shadow_rgba_[i * 4u + 3] = 0xFF;
    }
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

    for (uint32_t row = 0; row < copy_h; ++row) {
        uint8_t* dst = shadow_rgba_.data() + (static_cast<size_t>(y + row) * dst_stride) + (static_cast<size_t>(x) * 4u);
        const uint8_t* src = src_rgba + (static_cast<size_t>(row) * src_stride);
        std::memcpy(dst, src, static_cast<size_t>(copy_w) * 4u);
        for (uint32_t col = 0; col < copy_w; ++col) {
            dst[col * 4u + 3u] = 0xFF;
        }
    }
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
        shadow_rgba_.assign(static_cast<size_t>(fb_width) * static_cast<size_t>(fb_height) * 4u, 0xFF);
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

    for (uint32_t row = 0; row < copy_h; ++row) {
        const size_t y_idx = static_cast<size_t>(dirty_y + row);
        const size_t x_byte = static_cast<size_t>(dirty_x) * 4u;
        uint8_t* dst = shadow_rgba_.data() + y_idx * dst_stride + x_byte;
        const uint8_t* src = primary_buffer + y_idx * src_stride + x_byte;
        std::memcpy(dst, src, static_cast<size_t>(copy_w) * 4u);
        // Guarantee opaque alpha (0xFF) so TextureView & premultiplied CGImage never blend transparent desktop pixels
        for (uint32_t col = 0; col < copy_w; ++col) {
            dst[col * 4u + 3u] = 0xFF;
        }
    }
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
    if (width != width_.load() || height != height_.load()) {
        width_.store(width);
        height_.store(height);
        shadow_rgba_.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 4u);
        ensure_window_geometry_locked(width, height);
    }
    const size_t dst_stride = static_cast<size_t>(width) * 4u;
    const size_t effective_src_stride = src_stride >= dst_stride ? static_cast<size_t>(src_stride) : dst_stride;
    for (uint32_t r = 0; r < height; ++r) {
        uint8_t* dst = shadow_rgba_.data() + static_cast<size_t>(r) * dst_stride;
        std::memcpy(
            dst,
            src_rgba + static_cast<size_t>(r) * effective_src_stride,
            dst_stride
        );
        for (uint32_t col = 0; col < width; ++col) {
            dst[col * 4u + 3u] = 0xFF;
        }
    }
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

void RdpGdiSurfacePresenter::present_locked() {
    frame_seq_.fetch_add(1, std::memory_order_release);
#ifdef __ANDROID__
    if (!native_window_ || shadow_rgba_.empty()) {
        return;
    }
    const uint32_t fb_w = width_.load();
    const uint32_t fb_h = height_.load();
    ensure_window_geometry_locked(fb_w, fb_h);
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(native_window_, &buffer, nullptr) == 0) {
        const uint32_t rows = std::min<uint32_t>(fb_h, static_cast<uint32_t>(buffer.height));
        const uint32_t cols = std::min<uint32_t>(fb_w, static_cast<uint32_t>(buffer.width));
        auto* dst_base = static_cast<uint8_t*>(buffer.bits);
        const size_t dst_stride = static_cast<size_t>(buffer.stride) * 4u;
        const size_t src_stride = static_cast<size_t>(fb_w) * 4u;

        if (dst_stride == src_stride && cols == fb_w) {
            std::memcpy(dst_base, shadow_rgba_.data(), static_cast<size_t>(rows) * src_stride);
        } else {
            for (uint32_t r = 0; r < rows; ++r) {
                std::memcpy(
                    dst_base + r * dst_stride,
                    shadow_rgba_.data() + r * src_stride,
                    static_cast<size_t>(cols) * 4u
                );
            }
        }
        ANativeWindow_unlockAndPost(native_window_);
    }
#endif
}

} // namespace nexusts::rdp
