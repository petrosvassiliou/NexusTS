//! Unified JNI Bridge (`libnexusts_jni.so`) exposing Rust Vault Crypto, `xterm-256color`
//! Terminal Emulation, SFTP Transfer Queue, VNC Framebuffer, and Network Utilities to Kotlin.

use jni::objects::{JByteArray, JClass, JString};
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
    let _ = env.throw_new("java/lang/RuntimeException", msg);
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
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<u8>, String> {
        let plain = env
            .convert_byte_array(&plaintext)
            .map_err(|e| e.to_string())?;
        let pass = env
            .convert_byte_array(&passphrase_utf8)
            .map_err(|e| e.to_string())?;
        let profile = Argon2idProfile {
            memory_kib: memory_kib as u32,
            iterations: iterations as u32,
            parallelism: parallelism as u32,
        };
        seal_nxts_vault(&plain, &pass, revision as u64, profile).map_err(|e| e.to_string())
    }));

    match outcome {
        Ok(Ok(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                throw_runtime_exception(&mut env, &e.to_string());
                std::ptr::null_mut()
            }
        },
        Ok(Err(err)) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeSealNxtsVault");
            std::ptr::null_mut()
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
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<u8>, String> {
        let blob = env
            .convert_byte_array(&container)
            .map_err(|e| e.to_string())?;
        let pass = env
            .convert_byte_array(&passphrase_utf8)
            .map_err(|e| e.to_string())?;
        let unsealed = open_nxts_vault(&blob, &pass).map_err(|e| e.to_string())?;
        Ok(unsealed.plaintext.to_vec())
    }));

    match outcome {
        Ok(Ok(plain)) => match env.byte_array_from_slice(&plain) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                throw_runtime_exception(&mut env, &e.to_string());
                std::ptr::null_mut()
            }
        },
        Ok(Err(err)) => {
            throw_runtime_exception(&mut env, &err);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeOpenNxtsVault");
            std::ptr::null_mut()
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
    let comment_str: String = env
        .get_string(&comment)
        .map(|s| s.into())
        .unwrap_or_default();
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
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeCreateTerminalBuffer<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    cols: jint,
    rows: jint,
    scrollback: jint,
) -> jlong {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let vt = VtTerminalBuffer::new(cols as usize, rows as usize, scrollback as usize);
    if let Ok(mut reg) = terminal_registry().lock() {
        reg.insert(handle, vt);
    }
    handle
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeResizeTerminalBuffer<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    if let Ok(mut reg) = terminal_registry().lock() {
        if let Some(vt) = reg.get_mut(&handle) {
            vt.resize(cols as usize, rows as usize);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeFeedTerminalBytes<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jstring {
    let bytes = env.convert_byte_array(&data).unwrap_or_default();
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
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeDestroyTerminalBuffer<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if let Ok(mut reg) = terminal_registry().lock() {
        reg.remove(&handle);
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
    let dir_s: String = env
        .get_string(&parent_dir)
        .map(|s| s.into())
        .unwrap_or_else(|_| "/".into());
    let body_s: String = env
        .get_string(&mlsd_body)
        .map(|s| s.into())
        .unwrap_or_default();
    let entries = parse_ftp_mlsd_listing(&dir_s, &body_s);
    let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".into());
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeCreateFramebuffer<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    width: jint,
    height: jint,
) -> jlong {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if let Ok(mut reg) = vnc_registry().lock() {
        reg.insert(handle, RfbFramebuffer::new(width as u32, height as u32));
    }
    handle
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
    let raw_payload = env.convert_byte_array(&payload).unwrap_or_default();
    let res: Result<Vec<u8>, String> = if let Ok(mut reg) = vnc_registry().lock() {
        if let Some(fb) = reg.get_mut(&handle) {
            let (ux, uy, uw, uh) = (x as u16, y as u16, w as u16, h as u16);
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
            .unwrap_or(std::ptr::null_mut()),
        Err(msg) => {
            throw_runtime_exception(&mut env, &msg);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeDestroyFramebuffer<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if let Ok(mut reg) = vnc_registry().lock() {
        reg.remove(&handle);
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
    let mac_s: String = env.get_string(&mac).map(|s| s.into()).unwrap_or_default();
    let ip_s: String = env
        .get_string(&broadcast_ip)
        .map(|s| s.into())
        .unwrap_or_else(|_| "255.255.255.255".into());
    match send_wake_on_lan(&mac_s, &ip_s, port as u16, None) {
        Ok(bytes) => bytes as jint,
        Err(e) => {
            throw_runtime_exception(&mut env, &e.to_string());
            -1
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
    let host_s: String = env.get_string(&host).map(|s| s.into()).unwrap_or_default();
    let hit = probe_tcp_port(&host_s, port as u16, timeout_ms as u64);
    let json = serde_json::to_string(&hit).unwrap_or_else(|_| "null".into());
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or_else(|e| {
            throw_runtime_exception(&mut env, &e.to_string());
            std::ptr::null_mut()
        })
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
    let host_s: String = env.get_string(&host).map(|s| s.into()).unwrap_or_default();
    match trace_route_hops(
        &host_s,
        probe_port as u16,
        (max_hops as u8).clamp(1, 32),
        timeout_ms as u64,
    ) {
        Ok(hops) => {
            let json = serde_json::to_string(&hops).unwrap_or_else(|_| "[]".into());
            env.new_string(json)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }
        Err(e) => {
            throw_runtime_exception(&mut env, &e.to_string());
            std::ptr::null_mut()
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
    let host_s: String = env.get_string(&host).map(|s| s.into()).unwrap_or_default();
    let banner = grab_tcp_service_banner(&host_s, port as u16, timeout_ms as u64).unwrap_or_default();
    env.new_string(banner)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
