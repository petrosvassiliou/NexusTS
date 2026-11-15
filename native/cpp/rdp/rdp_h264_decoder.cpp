#include "rdp_session_context.hpp"

#ifdef __ANDROID__
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#endif

namespace nexusts::rdp {

RdpHardwareH264Decoder::RdpHardwareH264Decoder() = default;

RdpHardwareH264Decoder::~RdpHardwareH264Decoder() {
    shutdown();
}

bool RdpHardwareH264Decoder::initialize(uint32_t width, uint32_t height, bool avc444_chroma) {
    std::lock_guard<std::mutex> lock(codec_mutex_);
    return initialize_locked(width, height, avc444_chroma);
}

bool RdpHardwareH264Decoder::initialize_locked(uint32_t width, uint32_t height, bool avc444_chroma) {
    if (initialized_) {
        shutdown_locked();
    }
    width_ = width;
    height_ = height;
    avc444_ = avc444_chroma;

#ifdef __ANDROID__
    AMediaCodec* codec = AMediaCodec_createDecoderByType("video/avc");
    if (!codec) {
        return false;
    }
    AMediaFormat* format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, static_cast<int32_t>(width));
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, static_cast<int32_t>(height));
    // Low-latency real-time decoding hint for desktop cursor responsiveness
    AMediaFormat_setInt32(format, "low-latency", 1);

    const media_status_t status = AMediaCodec_configure(codec, format, nullptr, nullptr, 0);
    AMediaFormat_delete(format);
    if (status != AMEDIA_OK) {
        AMediaCodec_delete(codec);
        return false;
    }
    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        AMediaCodec_delete(codec);
        return false;
    }
    media_codec_handle_ = codec;
#endif
    initialized_ = true;
    return true;
}

bool RdpHardwareH264Decoder::decode_nal_unit(
    const uint8_t* nal_data,
    size_t nal_size,
    uint8_t* dst_rgba_buffer,
    uint32_t dst_stride_bytes
) {
    std::lock_guard<std::mutex> lock(codec_mutex_);
    if (!initialized_ || !nal_data || nal_size == 0 || !dst_rgba_buffer) {
        return false;
    }

#ifdef __ANDROID__
    auto* codec = static_cast<AMediaCodec*>(media_codec_handle_);
    if (!codec) {
        return false;
    }
    const ssize_t in_idx = AMediaCodec_dequeueInputBuffer(codec, 2000);
    if (in_idx >= 0) {
        size_t buf_cap = 0;
        uint8_t* in_buf = AMediaCodec_getInputBuffer(codec, static_cast<size_t>(in_idx), &buf_cap);
        if (in_buf && nal_size <= buf_cap) {
            __builtin_memcpy(in_buf, nal_data, nal_size);
            AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(in_idx), 0, nal_size, 0, 0);
        }
    }

    AMediaCodecBufferInfo info{};
    const ssize_t out_idx = AMediaCodec_dequeueOutputBuffer(codec, &info, 2000);
    if (out_idx >= 0) {
        size_t out_size = 0;
        uint8_t* out_yuv = AMediaCodec_getOutputBuffer(codec, static_cast<size_t>(out_idx), &out_size);
        if (out_yuv && info.size > 0 && dst_stride_bytes >= width_ * 4u) {
            // Convert NV12/YUV420 luma plane preview into RGBA target surface when not bound to Surface
            const uint32_t pixels = width_ * height_;
            for (uint32_t i = 0; i < pixels && i < static_cast<uint32_t>(info.size); ++i) {
                const uint8_t y_val = out_yuv[info.offset + i];
                dst_rgba_buffer[i * 4u + 0] = y_val;
                dst_rgba_buffer[i * 4u + 1] = y_val;
                dst_rgba_buffer[i * 4u + 2] = y_val;
                dst_rgba_buffer[i * 4u + 3] = 0xFF;
            }
        }
        AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(out_idx), false);
        return true;
    }
#else
    (void)dst_stride_bytes;
#endif
    return false;
}

void RdpHardwareH264Decoder::reconfigure_resolution(uint32_t width, uint32_t height) {
    std::lock_guard<std::mutex> lock(codec_mutex_);
    width_ = width;
    height_ = height;
    if (!initialized_) {
        return;
    }
    const bool was_avc444 = avc444_;
    shutdown_locked();
    initialize_locked(width, height, was_avc444);
}

void RdpHardwareH264Decoder::shutdown() {
    std::lock_guard<std::mutex> lock(codec_mutex_);
    shutdown_locked();
}

void RdpHardwareH264Decoder::shutdown_locked() {
#ifdef __ANDROID__
    if (media_codec_handle_) {
        auto* codec = static_cast<AMediaCodec*>(media_codec_handle_);
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
        media_codec_handle_ = nullptr;
    }
#endif
    initialized_ = false;
}

} // namespace nexusts::rdp
