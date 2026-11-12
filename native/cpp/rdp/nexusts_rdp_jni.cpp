#include "rdp_session_context.hpp"

#include <jni.h>
#include <unordered_map>

#ifdef __ANDROID__
#include <android/native_window_jni.h>
#endif

namespace {

std::mutex g_sessions_mutex;
int64_t g_next_rdp_handle = 5000;
std::unordered_map<int64_t, std::unique_ptr<nexusts::rdp::RdpSessionContext>> g_sessions;

std::string jstring_to_utf8(JNIEnv* env, jstring jstr) {
    if (!jstr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

nexusts::rdp::RdpSessionContext* lookup_session(jlong handle) {
    auto it = g_sessions.find(static_cast<int64_t>(handle));
    return (it != g_sessions.end()) ? it->second.get() : nullptr;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeCreateSession(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring host,
    jint port,
    jstring username,
    jstring password,
    jstring domain,
    jstring gateway_host,
    jint gateway_port,
    jint initial_width,
    jint initial_height,
    jboolean enable_h264_avc444,
    jboolean enable_remotefx,
    jboolean enable_dynamic_resize,
    jboolean multi_monitor,
    jboolean redirect_clipboard,
    jboolean redirect_smartcard,
    jboolean redirect_audio,
    jboolean redirect_mic,
    jboolean remote_app_mode,
    jstring remote_app_program,
    jstring remote_app_args
) {
    nexusts::rdp::RdpConnectParams params;
    params.host = jstring_to_utf8(env, host);
    params.port = static_cast<uint16_t>(port);
    params.username = jstring_to_utf8(env, username);
    params.password = jstring_to_utf8(env, password);
    params.domain = jstring_to_utf8(env, domain);
    params.rd_gateway_host = jstring_to_utf8(env, gateway_host);
    params.rd_gateway_port = static_cast<uint16_t>(gateway_port);
    params.initial_width = static_cast<uint32_t>(initial_width);
    params.initial_height = static_cast<uint32_t>(initial_height);
    params.enable_h264_avc444 = (enable_h264_avc444 == JNI_TRUE);
    params.enable_remotefx = (enable_remotefx == JNI_TRUE);
    params.enable_dynamic_resize = (enable_dynamic_resize == JNI_TRUE);
    params.multi_monitor_spanning = (multi_monitor == JNI_TRUE);
    params.redirect_clipboard = (redirect_clipboard == JNI_TRUE);
    params.redirect_smartcard = (redirect_smartcard == JNI_TRUE);
    params.redirect_audio_out = (redirect_audio == JNI_TRUE);
    params.redirect_mic_in = (redirect_mic == JNI_TRUE);
    params.remote_app_mode = (remote_app_mode == JNI_TRUE);
    params.remote_app_program = jstring_to_utf8(env, remote_app_program);
    params.remote_app_args = jstring_to_utf8(env, remote_app_args);

    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    const int64_t handle = g_next_rdp_handle++;
    g_sessions.emplace(handle, std::make_unique<nexusts::rdp::RdpSessionContext>(std::move(params)));
    return static_cast<jlong>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeConnect(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto* ctx = lookup_session(handle);
    if (!ctx) {
        return JNI_FALSE;
    }
    return ctx->connect() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeBindSurface(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jobject surface
) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto* ctx = lookup_session(handle);
    if (!ctx) {
        return;
    }
#ifdef __ANDROID__
    if (surface) {
        ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
        ctx->presenter().attach_native_window(win);
        if (win) {
            ANativeWindow_release(win);
        }
    } else {
        ctx->presenter().detach_native_window();
    }
#else
    (void)env;
    (void)surface;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeRequestDynamicResize(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint width_px,
    jint height_px,
    jint desktop_scale_percent
) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto* ctx = lookup_session(handle);
    if (!ctx) {
        return JNI_FALSE;
    }
    const bool ok = ctx->request_dynamic_display_resize(
        static_cast<uint32_t>(width_px),
        static_cast<uint32_t>(height_px),
        static_cast<uint32_t>(desktop_scale_percent)
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeSendKeyScancode(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint rdp_scancode,
    jboolean key_down,
    jboolean extended
) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    if (auto* ctx = lookup_session(handle)) {
        ctx->send_keyboard_scancode(
            static_cast<uint16_t>(rdp_scancode),
            key_down == JNI_TRUE,
            extended == JNI_TRUE
        );
    }
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeSendMouseEvent(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint pointer_flags,
    jint x,
    jint y
) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    if (auto* ctx = lookup_session(handle)) {
        ctx->send_pointer_event(
            static_cast<uint16_t>(pointer_flags),
            static_cast<uint16_t>(x),
            static_cast<uint16_t>(y)
        );
    }
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeSyncClipboardText(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jstring utf8_text
) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    if (auto* ctx = lookup_session(handle)) {
        ctx->channels().on_local_clipboard_text(jstring_to_utf8(env, utf8_text));
    }
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeDestroySession(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(static_cast<int64_t>(handle));
    if (it != g_sessions.end()) {
        it->second->disconnect();
        g_sessions.erase(it);
    }
}

} // extern "C"
