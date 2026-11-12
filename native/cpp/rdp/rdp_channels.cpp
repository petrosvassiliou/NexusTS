#include "rdp_session_context.hpp"

#ifdef __ANDROID__
#include <aaudio/AAudio.h>
#endif

namespace nexusts::rdp {

RdpVirtualChannelHub::RdpVirtualChannelHub() = default;

RdpVirtualChannelHub::~RdpVirtualChannelHub() {
    stop_audio_streams();
}

void RdpVirtualChannelHub::start_audio_streams(bool speaker_out, bool mic_in, uint32_t sample_rate_hz) {
    std::lock_guard<std::mutex> lock(channel_mutex_);
#ifdef __ANDROID__
    if (speaker_out && !aaudio_out_stream_) {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) == AAUDIO_OK && builder) {
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setSampleRate(builder, static_cast<int32_t>(sample_rate_hz));

            AAudioStream* stream = nullptr;
            if (AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK && stream) {
                AAudioStream_requestStart(stream);
                aaudio_out_stream_ = stream;
            }
            AAudioStreamBuilder_delete(builder);
        }
    }

    if (mic_in && !aaudio_in_stream_) {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) == AAUDIO_OK && builder) {
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setChannelCount(builder, 1);
            AAudioStreamBuilder_setSampleRate(builder, static_cast<int32_t>(sample_rate_hz));

            AAudioStream* stream = nullptr;
            if (AAudioStreamBuilder_openStream(builder, &stream) == AAUDIO_OK && stream) {
                AAudioStream_requestStart(stream);
                aaudio_in_stream_ = stream;
            }
            AAudioStreamBuilder_delete(builder);
        }
    }
#else
    (void)speaker_out;
    (void)mic_in;
    (void)sample_rate_hz;
#endif
}

void RdpVirtualChannelHub::stop_audio_streams() {
    std::lock_guard<std::mutex> lock(channel_mutex_);
#ifdef __ANDROID__
    if (aaudio_out_stream_) {
        auto* out = static_cast<AAudioStream*>(aaudio_out_stream_);
        AAudioStream_requestStop(out);
        AAudioStream_close(out);
        aaudio_out_stream_ = nullptr;
    }
    if (aaudio_in_stream_) {
        auto* in = static_cast<AAudioStream*>(aaudio_in_stream_);
        AAudioStream_requestStop(in);
        AAudioStream_close(in);
        aaudio_in_stream_ = nullptr;
    }
#endif
}

void RdpVirtualChannelHub::push_pcm_playback(const int16_t* pcm_frames, size_t frame_count) {
    std::lock_guard<std::mutex> lock(channel_mutex_);
#ifdef __ANDROID__
    if (aaudio_out_stream_ && pcm_frames && frame_count > 0) {
        auto* out = static_cast<AAudioStream*>(aaudio_out_stream_);
        AAudioStream_write(out, pcm_frames, static_cast<int32_t>(frame_count), 0);
    }
#else
    (void)pcm_frames;
    (void)frame_count;
#endif
}

void RdpVirtualChannelHub::on_local_clipboard_text(const std::string& utf8_text) {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    clipboard_utf8_ = utf8_text;
}

std::string RdpVirtualChannelHub::latest_remote_clipboard_text() const {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    return clipboard_utf8_;
}

std::vector<uint8_t> RdpVirtualChannelHub::transmit_smartcard_apdu(
    const std::vector<uint8_t>& command_apdu
) {
    std::function<std::vector<uint8_t>(const std::vector<uint8_t>&)> cb;
    {
        std::lock_guard<std::mutex> lock(channel_mutex_);
        cb = apdu_callback_;
    }
    if (cb) {
        return cb(command_apdu);
    }
    // ISO 7816-4 SW1/SW2 0x6A82 (File/Applet not found when no USB CCID token attached)
    return {0x6A, 0x82};
}

void RdpVirtualChannelHub::set_smartcard_apdu_handler(
    std::function<std::vector<uint8_t>(const std::vector<uint8_t>&)> callback
) {
    std::lock_guard<std::mutex> lock(channel_mutex_);
    apdu_callback_ = std::move(callback);
}

} // namespace nexusts::rdp
