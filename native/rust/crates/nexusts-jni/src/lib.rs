//! Unified JNI Bridge (`libnexusts_jni.so`) exposing Rust Vault Crypto, `xterm-256color`
//! Terminal Emulation, SFTP Transfer Queue, VNC Framebuffer, and Network Utilities to Kotlin.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use nexusts_crypto::{
    generate_ed25519_ssh_keypair, open_nxts_vault, seal_nxts_vault, Argon2idProfile,
};
use nexusts_netutils::{probe_tcp_port, send_wake_on_lan};
use nexusts_terminal::VtTerminalBuffer;
use nexusts_vnc::RfbFramebuffer;
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
