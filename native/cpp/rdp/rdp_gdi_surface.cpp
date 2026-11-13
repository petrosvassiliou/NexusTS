#include "rdp_session_context.hpp"

#include <algorithm>
#include <cstring>

#ifdef __ANDROID__
#include <android/native_window.h>
#endif

namespace nexusts::rdp {

RdpGdiSurfacePresenter::RdpGdiSurfacePresenter() {
    shadow_rgba_.resize(1920u * 1080u * 4u, 0);
}

RdpGdiSurfacePresenter::~RdpGdiSurfacePresenter() {
    detach_native_window();
}

void RdpGdiSurfacePresenter::attach_native_window(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(surface_mutex_);
#ifdef __ANDROID__
    if (native_window_) {
        ANativeWindow_release(native_window_);
    }
    native_window_ = window;
    if (native_window_) {
        ANativeWindow_acquire(native_window_);
        ANativeWindow_setBuffersGeometry(
            native_window_,
            static_cast<int32_t>(width_.load()),
            static_cast<int32_t>(height_.load()),
            WINDOW_FORMAT_RGBA_8888
        );
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
}

bool RdpGdiSurfacePresenter::has_bound_surface() const noexcept {
    std::lock_guard<std::mutex> lock(surface_mutex_);
    return native_window_ != nullptr;
}

void RdpGdiSurfacePresenter::resize_shadow_framebuffer(uint32_t width, uint32_t height) {
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
#ifdef __ANDROID__
    if (native_window_) {
        ANativeWindow_setBuffersGeometry(
            native_window_,
            static_cast<int32_t>(width),
            static_cast<int32_t>(height),
            WINDOW_FORMAT_RGBA_8888
        );
    }
#endif
}

void RdpGdiSurfacePresenter::update_dirty_region(
    uint32_t x,
    uint32_t y,
    uint32_t w,
    uint32_t h,
    const uint8_t* src_rgba,
    uint32_t src_stride
) {
    if (!src_rgba) {
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
    }
}

void RdpGdiSurfacePresenter::present_frame() {
    std::lock_guard<std::mutex> lock(surface_mutex_);
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
#ifdef __ANDROID__
        if (native_window_) {
            ANativeWindow_setBuffersGeometry(
                native_window_,
                static_cast<int32_t>(width),
                static_cast<int32_t>(height),
                WINDOW_FORMAT_RGBA_8888
            );
        }
#endif
    }
    const size_t dst_stride = static_cast<size_t>(width) * 4u;
    const size_t effective_src_stride = src_stride >= dst_stride ? static_cast<size_t>(src_stride) : dst_stride;
    for (uint32_t r = 0; r < height; ++r) {
        std::memcpy(
            shadow_rgba_.data() + static_cast<size_t>(r) * dst_stride,
            src_rgba + static_cast<size_t>(r) * effective_src_stride,
            dst_stride
        );
    }
    present_locked();
}

void RdpGdiSurfacePresenter::present_locked() {
#ifdef __ANDROID__
    if (!native_window_ || shadow_rgba_.empty()) {
        return;
    }
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(native_window_, &buffer, nullptr) == 0) {
        const uint32_t fb_w = width_.load();
        const uint32_t fb_h = height_.load();
        const uint32_t rows = std::min<uint32_t>(fb_h, static_cast<uint32_t>(buffer.height));
        const uint32_t cols = std::min<uint32_t>(fb_w, static_cast<uint32_t>(buffer.width));
        auto* dst_base = static_cast<uint8_t*>(buffer.bits);
        const size_t dst_stride = static_cast<size_t>(buffer.stride) * 4u;
        const size_t src_stride = static_cast<size_t>(fb_w) * 4u;

        for (uint32_t r = 0; r < rows; ++r) {
            std::memcpy(
                dst_base + r * dst_stride,
                shadow_rgba_.data() + r * src_stride,
                static_cast<size_t>(cols) * 4u
            );
        }
        ANativeWindow_unlockAndPost(native_window_);
    }
#endif
}

} // namespace nexusts::rdp
