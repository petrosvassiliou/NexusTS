#include "rdp_session_context.hpp"

#include <jni.h>
#include <algorithm>
#include <memory>
#include <unordered_map>
#include <vector>

#ifdef __ANDROID__
#include <android/native_window_jni.h>
#endif

namespace {

std::mutex g_sessions_mutex;
int64_t g_next_rdp_handle = 5000;
std::unordered_map<int64_t, std::shared_ptr<nexusts::rdp::RdpSessionContext>> g_sessions;

void append_utf8_codepoint(std::string& out, uint32_t cp) {
    if (cp <= 0x7Fu) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FFu) {
        out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0xFFFFu) {
        out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0x10FFFFu) {
        out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
        out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else {
        append_utf8_codepoint(out, 0xFFFDu);
    }
}

/**
 * Converts a Java UTF-16 `jstring` into standard RFC 3629 UTF-8 using `GetStringChars`/`GetStringLength`
 * so 4-byte supplementary plane codepoints (emojis, symbols) never trigger Android CheckJNI modified-UTF-8 aborts.
 */
std::string jstring_to_utf8(JNIEnv* env, jstring jstr) {
    if (!env || !jstr) {
        return {};
    }
    const jsize utf16_len = env->GetStringLength(jstr);
    if (utf16_len <= 0) {
        return {};
    }
    const jchar* chars = env->GetStringChars(jstr, nullptr);
    if (!chars) {
        return {};
    }

    std::string out;
    out.reserve(static_cast<size_t>(utf16_len) * 2u);
    for (jsize i = 0; i < utf16_len; ++i) {
        uint32_t cp = static_cast<uint32_t>(chars[i]);
        if (cp >= 0xD800u && cp <= 0xDBFFu) {
            if (i + 1 < utf16_len) {
                const uint32_t low = static_cast<uint32_t>(chars[i + 1]);
                if (low >= 0xDC00u && low <= 0xDFFFu) {
                    cp = 0x10000u + (((cp - 0xD800u) << 10) | (low - 0xDC00u));
                    ++i;
                } else {
                    cp = 0xFFFDu;
                }
            } else {
                cp = 0xFFFDu;
            }
        } else if (cp >= 0xDC00u && cp <= 0xDFFFu) {
            cp = 0xFFFDu;
        }
        append_utf8_codepoint(out, cp);
    }

    env->ReleaseStringChars(jstr, chars);
    return out;
}

/**
 * Converts standard RFC 3629 UTF-8 into a Java UTF-16 `jstring` via `NewString` (avoiding `NewStringUTF`
 * which rejects standard 4-byte UTF-8 sequences under Android CheckJNI).
 */
jstring utf8_to_jstring(JNIEnv* env, const std::string& utf8) {
    if (!env) {
        return nullptr;
    }
    if (utf8.empty()) {
        const jchar empty_char = 0;
        return env->NewString(&empty_char, 0);
    }

    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    const auto* bytes = reinterpret_cast<const uint8_t*>(utf8.data());
    const size_t n = utf8.size();
    size_t i = 0;

    while (i < n) {
        const uint8_t b0 = bytes[i];
        uint32_t cp = 0xFFFDu;
        size_t step = 1;

        if (b0 < 0x80u) {
            cp = b0;
        } else if ((b0 & 0xE0u) == 0xC0u && i + 1 < n && (bytes[i + 1] & 0xC0u) == 0x80u) {
            const uint32_t candidate = ((static_cast<uint32_t>(b0 & 0x1Fu) << 6) |
                                        static_cast<uint32_t>(bytes[i + 1] & 0x3Fu));
            if (candidate >= 0x80u) {
                cp = candidate;
                step = 2;
            }
        } else if ((b0 & 0xF0u) == 0xE0u && i + 2 < n &&
                   (bytes[i + 1] & 0xC0u) == 0x80u &&
                   (bytes[i + 2] & 0xC0u) == 0x80u) {
            const uint32_t candidate = ((static_cast<uint32_t>(b0 & 0x0Fu) << 12) |
                                        (static_cast<uint32_t>(bytes[i + 1] & 0x3Fu) << 6) |
                                        static_cast<uint32_t>(bytes[i + 2] & 0x3Fu));
            if (candidate >= 0x800u && (candidate < 0xD800u || candidate > 0xDFFFu)) {
                cp = candidate;
                step = 3;
            }
        } else if ((b0 & 0xF8u) == 0xF0u && i + 3 < n &&
                   (bytes[i + 1] & 0xC0u) == 0x80u &&
                   (bytes[i + 2] & 0xC0u) == 0x80u &&
                   (bytes[i + 3] & 0xC0u) == 0x80u) {
            const uint32_t candidate = ((static_cast<uint32_t>(b0 & 0x07u) << 18) |
                                        (static_cast<uint32_t>(bytes[i + 1] & 0x3Fu) << 12) |
                                        (static_cast<uint32_t>(bytes[i + 2] & 0x3Fu) << 6) |
                                        static_cast<uint32_t>(bytes[i + 3] & 0x3Fu));
            if (candidate >= 0x10000u && candidate <= 0x10FFFFu) {
                cp = candidate;
                step = 4;
            }
        }

        if (cp <= 0xFFFFu) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            const uint32_t adj = cp - 0x10000u;
            utf16.push_back(static_cast<jchar>(0xD800u + (adj >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00u + (adj & 0x3FFu)));
        }
        i += step;
    }

    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::shared_ptr<nexusts::rdp::RdpSessionContext> lookup_session_shared(jlong handle) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(static_cast<int64_t>(handle));
    return (it != g_sessions.end()) ? it->second : nullptr;
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
    jboolean connect_to_console,
    jboolean hidpi_enabled,
    jint desktop_scale_percent,
    jint color_depth_bits,
    jint gfx_codec_mode,
    jboolean enable_h264_avc444,
    jboolean enable_remotefx,
    jboolean enable_dynamic_resize,
    jboolean multi_monitor,
    jboolean redirect_clipboard,
    jint audio_redirection_mode,
    jboolean redirect_mic,
    jboolean redirect_drives,
    jboolean redirect_printers,
    jboolean redirect_ports,
    jboolean redirect_smartcard,
    jboolean enable_font_smoothing,
    jboolean enable_desktop_composition,
    jboolean enable_wallpaper,
    jboolean compression_enabled,
    jboolean nla_security,
    jboolean restricted_admin_mode,
    jboolean ignore_certificate,
    jboolean remote_app_mode,
    jstring remote_app_program,
    jstring remote_app_args,
    jstring shared_drive_name,
    jstring shared_drive_local_path,
    jstring app_home_dir,
    jstring preconnection_blob
) {
    nexusts::rdp::RdpConnectParams params;
    params.host = jstring_to_utf8(env, host);
    params.port = port <= 0 ? 0u : static_cast<uint16_t>(std::clamp<jint>(port, 1, 65535));
    params.username = jstring_to_utf8(env, username);
    params.password = jstring_to_utf8(env, password);
    params.domain = jstring_to_utf8(env, domain);
    params.rd_gateway_host = jstring_to_utf8(env, gateway_host);
    params.rd_gateway_port = static_cast<uint16_t>(std::clamp<jint>(gateway_port, 1, 65535));
    params.initial_width = static_cast<uint32_t>(std::max<jint>(320, initial_width));
    params.initial_height = static_cast<uint32_t>(std::max<jint>(240, initial_height));
    params.connect_to_console = (connect_to_console == JNI_TRUE);
    params.hidpi_enabled = (hidpi_enabled == JNI_TRUE);
    const uint32_t clamped_scale = static_cast<uint32_t>(std::clamp<jint>(desktop_scale_percent, 100, 500));
    params.desktop_scale_factor = clamped_scale;
    params.device_scale_factor = nexusts::rdp::map_device_scale_factor(clamped_scale);
    params.color_depth_bits = static_cast<uint32_t>(color_depth_bits);
    params.gfx_codec_mode = static_cast<int32_t>(std::clamp<jint>(gfx_codec_mode, 0, 2));
    params.enable_h264_avc444 = (enable_h264_avc444 == JNI_TRUE);
    params.enable_remotefx = (enable_remotefx == JNI_TRUE);
    params.enable_dynamic_resize = (enable_dynamic_resize == JNI_TRUE);
    params.multi_monitor_spanning = (multi_monitor == JNI_TRUE);
    params.redirect_clipboard = (redirect_clipboard == JNI_TRUE);
    params.audio_redirection_mode = static_cast<int32_t>(audio_redirection_mode);
    params.redirect_audio_out = (audio_redirection_mode == 0);
    params.redirect_mic_in = (redirect_mic == JNI_TRUE);
    params.redirect_drives = (redirect_drives == JNI_TRUE);
    params.redirect_printers = (redirect_printers == JNI_TRUE);
    params.redirect_serial_ports = (redirect_ports == JNI_TRUE);
    params.redirect_smartcard = (redirect_smartcard == JNI_TRUE);
    params.enable_font_smoothing = (enable_font_smoothing == JNI_TRUE);
    params.enable_desktop_composition = (enable_desktop_composition == JNI_TRUE);
    params.enable_wallpaper = (enable_wallpaper == JNI_TRUE);
    params.compression_enabled = (compression_enabled == JNI_TRUE);
    params.nla_security = (nla_security == JNI_TRUE);
    params.restricted_admin_mode = (restricted_admin_mode == JNI_TRUE);
    params.ignore_certificate = (ignore_certificate == JNI_TRUE);
    params.remote_app_mode = (remote_app_mode == JNI_TRUE);
    params.remote_app_program = jstring_to_utf8(env, remote_app_program);
    params.remote_app_args = jstring_to_utf8(env, remote_app_args);
    params.shared_drive_name = jstring_to_utf8(env, shared_drive_name);
    params.shared_drive_local_path = jstring_to_utf8(env, shared_drive_local_path);
    params.app_home_dir = jstring_to_utf8(env, app_home_dir);
    params.preconnection_blob = jstring_to_utf8(env, preconnection_blob);

    auto session = std::make_shared<nexusts::rdp::RdpSessionContext>(std::move(params));
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    const int64_t handle = g_next_rdp_handle++;
    g_sessions.emplace(handle, std::move(session));
    return static_cast<jlong>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeConnect(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    // Copy shared_ptr under g_sessions_mutex and unlock BEFORE slow network connect()
    const auto session = lookup_session_shared(handle);
    if (!session) {
        return JNI_FALSE;
    }
    return session->connect() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeBindSurface(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jobject surface
) {
    const auto session = lookup_session_shared(handle);
    if (!session) {
        return;
    }
#ifdef __ANDROID__
    if (surface) {
        ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
        session->presenter().attach_native_window(win);
        if (win) {
            ANativeWindow_release(win);
        }
        session->on_surface_bound();
    } else {
        session->presenter().detach_native_window();
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
    const auto session = lookup_session_shared(handle);
    if (!session) {
        return JNI_FALSE;
    }
    const bool ok = session->request_dynamic_display_resize(
        static_cast<uint32_t>(std::max<jint>(320, width_px)),
        static_cast<uint32_t>(std::max<jint>(240, height_px)),
        static_cast<uint32_t>(std::clamp<jint>(desktop_scale_percent, 100, 500))
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
    if (const auto session = lookup_session_shared(handle)) {
        session->send_keyboard_scancode(
            static_cast<uint16_t>(rdp_scancode),
            key_down == JNI_TRUE,
            extended == JNI_TRUE
        );
    }
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeReleaseAllKeys(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        session->release_all_pressed_keys();
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
    if (const auto session = lookup_session_shared(handle)) {
        session->send_pointer_event(
            static_cast<uint16_t>(pointer_flags),
            static_cast<uint16_t>(std::clamp<jint>(x, 0, 65535)),
            static_cast<uint16_t>(std::clamp<jint>(y, 0, 65535))
        );
    }
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeSendExtendedMouseEvent(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint xflags,
    jint x,
    jint y
) {
    if (const auto session = lookup_session_shared(handle)) {
        session->send_extended_pointer_event(
            static_cast<uint16_t>(xflags),
            static_cast<uint16_t>(std::clamp<jint>(x, 0, 65535)),
            static_cast<uint16_t>(std::clamp<jint>(y, 0, 65535))
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
    const std::string converted = jstring_to_utf8(env, utf8_text);
    if (const auto session = lookup_session_shared(handle)) {
        session->channels().on_local_clipboard_text(converted);
    }
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeSyncClipboardFiles(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jobjectArray local_paths
) {
    if (!env || !local_paths) {
        return;
    }
    const jsize len = env->GetArrayLength(local_paths);
    std::vector<std::string> paths;
    paths.reserve(static_cast<size_t>(std::max<jsize>(0, len)));
    for (jsize i = 0; i < len; ++i) {
        auto* elem = static_cast<jstring>(env->GetObjectArrayElement(local_paths, i));
        if (elem) {
            std::string p = jstring_to_utf8(env, elem);
            env->DeleteLocalRef(elem);
            if (!p.empty()) {
                paths.push_back(std::move(p));
            }
        }
    }
    if (!paths.empty()) {
        if (const auto session = lookup_session_shared(handle)) {
            session->channels().on_local_clipboard_files(paths);
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeGetRemoteClipboardKind(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        return static_cast<jint>(session->channels().remote_clipboard_kind());
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativePullRemoteClipboardText(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        const std::string text = session->channels().latest_remote_clipboard_text();
        return utf8_to_jstring(env, text);
    }
    return utf8_to_jstring(env, "");
}

JNIEXPORT jstring JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativePullRemoteClipboardFiles(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        const std::vector<std::string> files = session->channels().latest_remote_clipboard_files();
        std::string joined;
        for (size_t i = 0u; i < files.size(); ++i) {
            if (i > 0u) {
                joined.push_back('\n');
            }
            joined += files[i];
        }
        return utf8_to_jstring(env, joined);
    }
    return utf8_to_jstring(env, "");
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeRequestSynchronizedPaste(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        session->request_synchronized_paste();
    }
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeLogOffSession(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        session->logoff_remote_session();
    }
}

JNIEXPORT jlong JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeGetRemoteClipboardSequence(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        return static_cast<jlong>(session->channels().remote_clipboard_sequence());
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeGetLastError(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        return utf8_to_jstring(env, session->last_error());
    }
    return utf8_to_jstring(env, "");
}

JNIEXPORT jboolean JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeIsConnected(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        return session->is_connected() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeIsReconnecting(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        return session->is_reconnecting() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeGetReconnectAttempt(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        return static_cast<jint>(session->reconnect_attempt());
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeGetDesktopDimensions(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    if (const auto session = lookup_session_shared(handle)) {
        const uint64_t w = session->presenter().width();
        const uint64_t h = session->presenter().height();
        return static_cast<jlong>((w << 32u) | (h & 0xFFFFFFFFull));
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_nexusts_app_protocols_rdp_bridge_FreeRdpNativeBridge_nativeDestroySession(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    std::shared_ptr<nexusts::rdp::RdpSessionContext> session;
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        auto it = g_sessions.find(static_cast<int64_t>(handle));
        if (it != g_sessions.end()) {
            session = std::move(it->second);
            g_sessions.erase(it);
        }
    }
    if (session) {
        session->disconnect();
    }
}

} // extern "C"
