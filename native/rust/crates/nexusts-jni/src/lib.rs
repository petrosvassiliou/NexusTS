//! Unified JNI Bridge (`libnexusts_jni.so`) exposing Rust Vault Crypto, `xterm-256color`
//! Terminal Emulation, SFTP Transfer Queue, VNC Framebuffer, and Network Utilities to Kotlin.

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use nexusts_crypto::{
    generate_ed25519_ssh_keypair, open_nxts_vault, seal_nxts_vault, Argon2idProfile,
};
use nexusts_netutils::{grab_tcp_service_banner, probe_tcp_port, send_wake_on_lan, trace_route_hops};
use nexusts_terminal::VtTerminalBuffer;
use nexusts_transfer::wire_clients::parse_ftp_mlsd_listing;
use nexusts_vnc::rfb_client::apply_hextile_rect;
use nexusts_vnc::{
    RfbFramebuffer, RFB_ENCODING_COPYRECT, RFB_ENCODING_HEXTILE, RFB_ENCODING_RAW,
    RFB_ENCODING_TIGHT, RFB_ENCODING_ZRLE,
};
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, OnceLock};

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1000);

fn terminal_registry() -> &'static Mutex<HashMap<i64, VtTerminalBuffer>> {
    static REG: OnceLock<Mutex<HashMap<i64, VtTerminalBuffer>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn vnc_registry() -> &'static Mutex<HashMap<i64, RfbFramebuffer>> {
    static REG: OnceLock<Mutex<HashMap<i64, RfbFramebuffer>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn throw_runtime_exception(env: &mut JNIEnv, msg: &str) {
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/RuntimeException", msg);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_core_vault_crypto_NativeVaultCryptoBridge_nativeSealNxtsVault<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    passphrase_utf8: JByteArray<'local>,
    revision: jlong,
    memory_kib: jint,
    iterations: jint,
    parallelism: jint,
) -> jbyteArray {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jbyteArray {
        let res: Result<Vec<u8>, String> = (|| {
            let plain = env
                .convert_byte_array(&plaintext)
                .map_err(|e| e.to_string())?;
            let pass = env
                .convert_byte_array(&passphrase_utf8)
                .map_err(|e| e.to_string())?;
            let profile = Argon2idProfile {
                memory_kib: memory_kib.max(0) as u32,
                iterations: iterations.max(0) as u32,
                parallelism: parallelism.max(0) as u32,
            };
            seal_nxts_vault(&plain, &pass, revision as u64, profile).map_err(|e| e.to_string())
        })();

        match res {
            Ok(bytes) => match env.byte_array_from_slice(&bytes) {
                Ok(arr) => arr.into_raw(),
                Err(e) => {
                    throw_runtime_exception(&mut env, &e.to_string());
                    JObject::null().into_raw()
                }
            },
            Err(err) => {
                throw_runtime_exception(&mut env, &err);
                JObject::null().into_raw()
            }
        }
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeSealNxtsVault");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_core_vault_crypto_NativeVaultCryptoBridge_nativeOpenNxtsVault<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    container: JByteArray<'local>,
    passphrase_utf8: JByteArray<'local>,
) -> jbyteArray {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jbyteArray {
        let res: Result<Vec<u8>, String> = (|| {
            let blob = env
                .convert_byte_array(&container)
                .map_err(|e| e.to_string())?;
            let pass = env
                .convert_byte_array(&passphrase_utf8)
                .map_err(|e| e.to_string())?;
            let unsealed = open_nxts_vault(&blob, &pass).map_err(|e| e.to_string())?;
            Ok(unsealed.plaintext.to_vec())
        })();

        match res {
            Ok(plain) => match env.byte_array_from_slice(&plain) {
                Ok(arr) => arr.into_raw(),
                Err(e) => {
                    throw_runtime_exception(&mut env, &e.to_string());
                    JObject::null().into_raw()
                }
            },
            Err(err) => {
                throw_runtime_exception(&mut env, &err);
                JObject::null().into_raw()
            }
        }
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeOpenNxtsVault");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_core_vault_crypto_NativeVaultCryptoBridge_nativeGenerateEd25519KeypairJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    comment: JString<'local>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jstring {
        let comment_str: String = if comment.is_null() {
            String::new()
        } else {
            env.get_string(&comment)
                .map(|s| s.into())
                .unwrap_or_default()
        };
        let kp = generate_ed25519_ssh_keypair(&comment_str);
        let json = serde_json::json!({
            "key_id": kp.key_id,
            "public_key_openssh": kp.public_key_openssh,
            "private_key_openssh_pem": kp.private_key_openssh_pem.as_str(),
            "sha256_fingerprint": kp.sha256_fingerprint,
        })
        .to_string();

        match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                throw_runtime_exception(&mut env, &e.to_string());
                JObject::null().into_raw()
            }
        }
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeGenerateEd25519KeypairJson");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeCreateTerminalBuffer<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    cols: jint,
    rows: jint,
    scrollback: jint,
) -> jlong {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jlong {
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let vt = VtTerminalBuffer::new(
            cols.max(10) as usize,
            rows.max(4) as usize,
            scrollback.max(0) as usize,
        );
        if let Ok(mut reg) = terminal_registry().lock() {
            reg.insert(handle, vt);
            handle
        } else {
            0
        }
    }));

    match outcome {
        Ok(handle) => handle,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeCreateTerminalBuffer");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeResizeTerminalBuffer<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut reg) = terminal_registry().lock() {
            if let Some(vt) = reg.get_mut(&handle) {
                vt.resize(cols.max(10) as usize, rows.max(4) as usize);
            }
        }
    }));

    if outcome.is_err() {
        throw_runtime_exception(&mut env, "Rust panic in nativeResizeTerminalBuffer");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeFeedTerminalBytes<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jstring {
        let bytes = if data.is_null() {
            Vec::new()
        } else {
            env.convert_byte_array(&data).unwrap_or_default()
        };
        let snapshot_json = if let Ok(mut reg) = terminal_registry().lock() {
            if let Some(vt) = reg.get_mut(&handle) {
                vt.feed_bytes(&bytes);
                serde_json::to_string(&vt.snapshot()).unwrap_or_default()
            } else {
                String::new()
            }
        } else {
            String::new()
        };

        env.new_string(snapshot_json)
            .map(|s| s.into_raw())
            .unwrap_or_else(|_| JObject::null().into_raw())
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeFeedTerminalBytes");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeDestroyTerminalBuffer<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut reg) = terminal_registry().lock() {
            reg.remove(&handle);
        }
    }));

    if outcome.is_err() {
        throw_runtime_exception(&mut env, "Rust panic in nativeDestroyTerminalBuffer");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeParseFtpMlsdJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    parent_dir: JString<'local>,
    mlsd_body: JString<'local>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jstring {
        let dir_s: String = if parent_dir.is_null() {
            String::from("/")
        } else {
            env.get_string(&parent_dir)
                .map(|s| s.into())
                .unwrap_or_else(|_| "/".into())
        };
        let body_s: String = if mlsd_body.is_null() {
            String::new()
        } else {
            env.get_string(&mlsd_body)
                .map(|s| s.into())
                .unwrap_or_default()
        };
        let entries = parse_ftp_mlsd_listing(&dir_s, &body_s);
        let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".into());
        env.new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or_else(|_| JObject::null().into_raw())
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeParseFtpMlsdJson");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeCreateFramebuffer<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    width: jint,
    height: jint,
) -> jlong {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jlong {
        let w = width.clamp(1, 8192) as u32;
        let h = height.clamp(1, 8192) as u32;
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut reg) = vnc_registry().lock() {
            reg.insert(handle, RfbFramebuffer::new(w, h));
            handle
        } else {
            0
        }
    }));

    match outcome {
        Ok(handle) => handle,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeCreateFramebuffer");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeApplyRfbRect<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    encoding: jint,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
    payload: JByteArray<'local>,
) -> jbyteArray {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jbyteArray {
        let raw_payload = if payload.is_null() {
            Vec::new()
        } else {
            env.convert_byte_array(&payload).unwrap_or_default()
        };
        let res: Result<Vec<u8>, String> = if let Ok(mut reg) = vnc_registry().lock() {
            if let Some(fb) = reg.get_mut(&handle) {
                let ux = x.clamp(0, u16::MAX as jint) as u16;
                let uy = y.clamp(0, u16::MAX as jint) as u16;
                let uw = w.clamp(0, u16::MAX as jint) as u16;
                let uh = h.clamp(0, u16::MAX as jint) as u16;
                let step = match encoding {
                    RFB_ENCODING_RAW => fb.apply_raw_rect(ux, uy, uw, uh, &raw_payload),
                    RFB_ENCODING_COPYRECT if raw_payload.len() >= 4 => {
                        let sx = u16::from_be_bytes([raw_payload[0], raw_payload[1]]);
                        let sy = u16::from_be_bytes([raw_payload[2], raw_payload[3]]);
                        fb.apply_copy_rect(ux, uy, uw, uh, sx, sy)
                    }
                    RFB_ENCODING_HEXTILE => apply_hextile_rect(fb, ux, uy, uw, uh, &raw_payload),
                    RFB_ENCODING_TIGHT => fb.apply_tight_rect(ux, uy, uw, uh, &raw_payload),
                    RFB_ENCODING_ZRLE => fb.apply_zrle_rect(ux, uy, uw, uh, &raw_payload),
                    _ => Ok(()),
                };
                step.map(|_| fb.rgba_pixels.clone())
                    .map_err(|e| e.to_string())
            } else {
                Err(String::from("Unknown VNC framebuffer handle"))
            }
        } else {
            Err(String::from("Poisoned VNC registry mutex"))
        };

        match res {
            Ok(rgba) => env
                .byte_array_from_slice(&rgba)
                .map(|a| a.into_raw())
                .unwrap_or_else(|_| JObject::null().into_raw()),
            Err(msg) => {
                throw_runtime_exception(&mut env, &msg);
                JObject::null().into_raw()
            }
        }
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeApplyRfbRect");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeDestroyFramebuffer<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut reg) = vnc_registry().lock() {
            reg.remove(&handle);
        }
    }));

    if outcome.is_err() {
        throw_runtime_exception(&mut env, "Rust panic in nativeDestroyFramebuffer");
    }
}

fn send_wol_with_optional_secure_on<'local>(
    env: &mut JNIEnv<'local>,
    mac: &JString<'local>,
    broadcast_ip: &JString<'local>,
    port: jint,
    secure_on: Option<&JString<'local>>,
) -> jint {
    let mac_s: String = if mac.is_null() {
        String::new()
    } else {
        env.get_string(mac).map(|s| s.into()).unwrap_or_default()
    };
    let ip_s: String = if broadcast_ip.is_null() {
        String::from("255.255.255.255")
    } else {
        env.get_string(broadcast_ip)
            .map(|s| s.into())
            .unwrap_or_else(|_| "255.255.255.255".into())
    };
    let sec_s: Option<String> = secure_on.and_then(|jsec| {
        if jsec.is_null() {
            None
        } else {
            env.get_string(jsec)
                .ok()
                .map(String::from)
                .filter(|s| !s.trim().is_empty())
        }
    });
    let udp_port = u16::try_from(port).unwrap_or(9);
    match send_wake_on_lan(&mac_s, &ip_s, udp_port, sec_s.as_deref()) {
        Ok(bytes) => bytes as jint,
        Err(e) => {
            throw_runtime_exception(env, &e.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeSendWakeOnLan<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    mac: JString<'local>,
    broadcast_ip: JString<'local>,
    port: jint,
) -> jint {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jint {
        send_wol_with_optional_secure_on(&mut env, &mac, &broadcast_ip, port, None)
    }));

    match outcome {
        Ok(code) => code,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeSendWakeOnLan");
            -99
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeSendWakeOnLanWithSecureOn<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    mac_address: JString<'local>,
    broadcast_ip: JString<'local>,
    port: jint,
    secure_on_hex: JString<'local>,
) -> jint {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jint {
        send_wol_with_optional_secure_on(
            &mut env,
            &mac_address,
            &broadcast_ip,
            port,
            Some(&secure_on_hex),
        )
    }));

    match outcome {
        Ok(code) => code,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeSendWakeOnLanWithSecureOn");
            -99
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeProbeTcpPortJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    timeout_ms: jint,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jstring {
        let host_s: String = if host.is_null() {
            String::new()
        } else {
            env.get_string(&host).map(|s| s.into()).unwrap_or_default()
        };
        let tcp_port = u16::try_from(port).unwrap_or(0);
        let hit = if tcp_port == 0 {
            None
        } else {
            probe_tcp_port(&host_s, tcp_port, timeout_ms.max(1) as u64)
        };
        let json = serde_json::to_string(&hit).unwrap_or_else(|_| "null".into());
        env.new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or_else(|e| {
                throw_runtime_exception(&mut env, &e.to_string());
                JObject::null().into_raw()
            })
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeProbeTcpPortJson");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeTraceRouteJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    probe_port: jint,
    max_hops: jint,
    timeout_ms: jint,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jstring {
        let host_s: String = if host.is_null() {
            String::new()
        } else {
            env.get_string(&host).map(|s| s.into()).unwrap_or_default()
        };
        let port = u16::try_from(probe_port).unwrap_or(443);
        let hops_limit = max_hops.clamp(1, 32) as u8;
        match trace_route_hops(&host_s, port, hops_limit, timeout_ms.max(50) as u64) {
            Ok(hops) => {
                let json = serde_json::to_string(&hops).unwrap_or_else(|_| "[]".into());
                env.new_string(json)
                    .map(|s| s.into_raw())
                    .unwrap_or_else(|_| JObject::null().into_raw())
            }
            Err(e) => {
                throw_runtime_exception(&mut env, &e.to_string());
                JObject::null().into_raw()
            }
        }
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeTraceRouteJson");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeGrabTcpBanner<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    timeout_ms: jint,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jstring {
        let host_s: String = if host.is_null() {
            String::new()
        } else {
            env.get_string(&host).map(|s| s.into()).unwrap_or_default()
        };
        let tcp_port = u16::try_from(port).unwrap_or(0);
        let banner = if tcp_port == 0 {
            String::new()
        } else {
            grab_tcp_service_banner(&host_s, tcp_port, timeout_ms.max(50) as u64).unwrap_or_default()
        };
        env.new_string(banner)
            .map(|s| s.into_raw())
            .unwrap_or_else(|_| JObject::null().into_raw())
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeGrabTcpBanner");
            JObject::null().into_raw()
        }
    }
}
