//! Unified JNI Bridge (`libnexusts_jni.so`) exposing Rust Zero-Knowledge Vault Crypto,
//! `xterm-256color` VT Terminal Emulation (incl. DECCKM), Live Async SSHv2 (`russh` + Bastion
//! + Abort-Safe `-L` & `-D` SOCKS5), Stateful Telnet, Unified SFTP/SCP/FTP File Transfer,
//! Live RFB 3.3/3.7/3.8 VNC & Apple ARD Type 30, and Network Utilities to Kotlin / Android.

use dashmap::DashMap;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jbyte, jbyteArray, jint, jintArray, jlong, jsize, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use nexusts_crypto::{
    generate_ed25519_ssh_keypair, open_nxts_vault, seal_nxts_vault, Argon2idProfile,
};
use nexusts_netutils::{
    grab_tcp_service_banner, probe_tcp_port, scan_tcp_ports, send_wake_on_lan, tcp_latency_ping,
    trace_route_hops,
};
use nexusts_terminal::ssh_transport::{
    ActiveSshClientSession, SshAuthMethod, SshConnectSpec,
};
use nexusts_terminal::telnet_transport::ActiveTelnetSession;
use nexusts_terminal::VtTerminalBuffer;
use nexusts_transfer::ftp_client::NexusFtpClient;
use nexusts_transfer::wire_clients::{parse_ftp_mlsd_listing, NexusScpClient, NexusSftpClient};
use nexusts_vnc::rfb_client::{apply_hextile_rect, ActiveVncClient};
use nexusts_vnc::{
    RfbFramebuffer, RFB_ENCODING_COPYRECT, RFB_ENCODING_HEXTILE, RFB_ENCODING_RAW,
    RFB_ENCODING_RRE, RFB_ENCODING_TIGHT, RFB_ENCODING_ZRLE, RFB_PSEUDO_DESKTOP_SIZE,
};
use russh::ChannelMsg;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use tokio::runtime::{Builder, Runtime};
use tokio::sync::mpsc;
use tokio::task::AbortHandle;

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1000);

fn tokio_runtime() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("nexusts-jni-tokio")
            .enable_all()
            .build()
            .expect("Failed to initialize NexusTS JNI Tokio runtime")
    })
}

fn terminal_registry() -> &'static Mutex<HashMap<i64, VtTerminalBuffer>> {
    static REG: OnceLock<Mutex<HashMap<i64, VtTerminalBuffer>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn vnc_registry() -> &'static Mutex<HashMap<i64, RfbFramebuffer>> {
    static REG: OnceLock<Mutex<HashMap<i64, RfbFramebuffer>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

enum JniSshActorCmd {
    Write(Vec<u8>),
    Resize { cols: u32, rows: u32 },
    Close,
}

struct JniSshSessionRecord {
    session: Arc<ActiveSshClientSession>,
    cmd_tx: Option<mpsc::UnboundedSender<JniSshActorCmd>>,
    output_buf: Arc<Mutex<Vec<u8>>>,
    eof_flag: Arc<AtomicBool>,
    last_error: Arc<Mutex<String>>,
    abort_handles: Mutex<Vec<AbortHandle>>,
}

enum JniTransferBackend {
    Sftp {
        ssh: Arc<ActiveSshClientSession>,
        sftp: Arc<NexusSftpClient>,
        bastion_abort: Option<AbortHandle>,
    },
    Scp {
        client: Arc<NexusScpClient>,
    },
    Ftp {
        client: Arc<NexusFtpClient>,
    },
}

struct JniTransferRecord {
    backend: JniTransferBackend,
    last_error: Mutex<String>,
}

fn ssh_sessions() -> &'static DashMap<i64, Arc<JniSshSessionRecord>> {
    static MAP: OnceLock<DashMap<i64, Arc<JniSshSessionRecord>>> = OnceLock::new();
    MAP.get_or_init(DashMap::new)
}

fn telnet_sessions() -> &'static DashMap<i64, Arc<ActiveTelnetSession>> {
    static MAP: OnceLock<DashMap<i64, Arc<ActiveTelnetSession>>> = OnceLock::new();
    MAP.get_or_init(DashMap::new)
}

fn transfer_sessions() -> &'static DashMap<i64, Arc<JniTransferRecord>> {
    static MAP: OnceLock<DashMap<i64, Arc<JniTransferRecord>>> = OnceLock::new();
    MAP.get_or_init(DashMap::new)
}

fn vnc_sessions() -> &'static DashMap<i64, Arc<ActiveVncClient>> {
    static MAP: OnceLock<DashMap<i64, Arc<ActiveVncClient>>> = OnceLock::new();
    MAP.get_or_init(DashMap::new)
}

fn last_ssh_connect_error() -> &'static Mutex<String> {
    static ERR: OnceLock<Mutex<String>> = OnceLock::new();
    ERR.get_or_init(|| Mutex::new(String::new()))
}

fn last_telnet_connect_error() -> &'static Mutex<String> {
    static ERR: OnceLock<Mutex<String>> = OnceLock::new();
    ERR.get_or_init(|| Mutex::new(String::new()))
}

fn last_transfer_connect_error() -> &'static Mutex<String> {
    static ERR: OnceLock<Mutex<String>> = OnceLock::new();
    ERR.get_or_init(|| Mutex::new(String::new()))
}

fn last_vnc_connect_error() -> &'static Mutex<String> {
    static ERR: OnceLock<Mutex<String>> = OnceLock::new();
    ERR.get_or_init(|| Mutex::new(String::new()))
}

fn set_mutex_string(slot: &Mutex<String>, msg: impl Into<String>) {
    if let Ok(mut guard) = slot.lock() {
        *guard = msg.into();
    }
}

fn get_mutex_string(slot: &Mutex<String>) -> String {
    slot.lock().map(|g| g.clone()).unwrap_or_default()
}

fn read_jstring(env: &mut JNIEnv<'_>, s: &JString<'_>) -> String {
    if s.is_null() {
        String::new()
    } else {
        env.get_string(s).map(Into::into).unwrap_or_default()
    }
}

fn new_checkjni_string(env: &mut JNIEnv<'_>, text: impl Into<String>) -> jstring {
    let sanitized = text.into().replace('\0', "");
    env.new_string(sanitized)
        .map(|s| s.into_raw())
        .unwrap_or_else(|_| JObject::null().into_raw())
}

#[inline]
fn to_jbool(val: bool) -> jboolean {
    if val {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn throw_runtime_exception(env: &mut JNIEnv, msg: &str) {
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/RuntimeException", msg);
    }
}

fn build_ssh_spec_from_params(
    host: String,
    port: u16,
    username: String,
    password: String,
    private_key_pem: String,
    passphrase: String,
    expected_fp: String,
    cols: u32,
    rows: u32,
    enable_x11: bool,
    startup_command: String,
) -> SshConnectSpec {
    let auth = if !private_key_pem.trim().is_empty() {
        SshAuthMethod::PrivateKeyPem {
            pem: private_key_pem,
            passphrase: if passphrase.is_empty() {
                None
            } else {
                Some(passphrase)
            },
        }
    } else {
        SshAuthMethod::Password { password }
    };

    SshConnectSpec {
        host: host.trim().to_string(),
        port: if port == 0 { 22 } else { port },
        username: if username.trim().is_empty() {
            String::from("root")
        } else {
            username.trim().to_string()
        },
        auth,
        expected_host_fingerprint_sha256: if expected_fp.trim().is_empty() {
            None
        } else {
            Some(expected_fp.trim().to_string())
        },
        term_env: String::from("xterm-256color"),
        initial_cols: cols.max(10),
        initial_rows: rows.max(4),
        enable_x11,
        keepalive_secs: 30,
        startup_command: if startup_command.trim().is_empty() {
            None
        } else {
            Some(startup_command)
        },
    }
}

fn connect_ssh_session_internal(
    spec: SshConnectSpec,
    bastion_spec: Option<SshConnectSpec>,
    open_pty: bool,
) -> Result<i64, String> {
    let startup_cmd = spec
        .startup_command
        .clone()
        .filter(|s| !s.trim().is_empty());

    let record_res = tokio_runtime().block_on(async move {
        let bastion_session = if let Some(bspec) = bastion_spec {
            Some(ActiveSshClientSession::connect_without_shell(bspec, None).await?)
        } else {
            None
        };

        let output_buf = Arc::new(Mutex::new(Vec::<u8>::with_capacity(16_384)));
        let eof_flag = Arc::new(AtomicBool::new(false));
        let last_error = Arc::new(Mutex::new(String::new()));
        let mut aborts = Vec::new();

        if open_pty {
            let (session, mut pty_channel) =
                ActiveSshClientSession::connect(spec, bastion_session.as_ref()).await?;
            if let Some(cmd) = startup_cmd {
                let line = format!("{cmd}\r");
                let _ = pty_channel.data(line.as_bytes()).await;
            }

            let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<JniSshActorCmd>();
            let out_clone = Arc::clone(&output_buf);
            let eof_clone = Arc::clone(&eof_flag);
            let err_clone = Arc::clone(&last_error);

            let actor = tokio::spawn(async move {
                let _keep_bastion = bastion_session;
                loop {
                    tokio::select! {
                        maybe_cmd = cmd_rx.recv() => {
                            match maybe_cmd {
                                Some(JniSshActorCmd::Write(bytes)) => {
                                    if let Err(e) = pty_channel.data(&bytes[..]).await {
                                        set_mutex_string(&err_clone, e.to_string());
                                        eof_clone.store(true, Ordering::Release);
                                        break;
                                    }
                                }
                                Some(JniSshActorCmd::Resize { cols, rows }) => {
                                    let _ = pty_channel.window_change(cols, rows, 0, 0).await;
                                }
                                Some(JniSshActorCmd::Close) | None => {
                                    let _ = pty_channel.eof().await;
                                    let _ = pty_channel.close().await;
                                    eof_clone.store(true, Ordering::Release);
                                    break;
                                }
                            }
                        }
                        maybe_msg = pty_channel.wait() => {
                            match maybe_msg {
                                Some(ChannelMsg::Data { data }) => {
                                    if let Ok(mut b) = out_clone.lock() {
                                        b.extend_from_slice(&data);
                                    }
                                }
                                Some(ChannelMsg::ExtendedData { data, .. }) => {
                                    if let Ok(mut b) = out_clone.lock() {
                                        b.extend_from_slice(&data);
                                    }
                                }
                                Some(ChannelMsg::Eof) | Some(ChannelMsg::Close) | None => {
                                    eof_clone.store(true, Ordering::Release);
                                    break;
                                }
                                _ => {}
                            }
                        }
                    }
                }
            });
            aborts.push(actor.abort_handle());

            Ok::<JniSshSessionRecord, nexusts_terminal::ssh_transport::TransportError>(
                JniSshSessionRecord {
                    session: Arc::new(session),
                    cmd_tx: Some(cmd_tx),
                    output_buf,
                    eof_flag,
                    last_error,
                    abort_handles: Mutex::new(aborts),
                },
            )
        } else {
            let session =
                ActiveSshClientSession::connect_without_shell(spec, bastion_session.as_ref())
                    .await?;
            if let Some(b) = bastion_session {
                let guard = tokio::spawn(async move {
                    let _keep = b;
                    std::future::pending::<()>().await;
                });
                aborts.push(guard.abort_handle());
            }
            Ok(JniSshSessionRecord {
                session: Arc::new(session),
                cmd_tx: None,
                output_buf,
                eof_flag,
                last_error,
                abort_handles: Mutex::new(aborts),
            })
        }
    });

    match record_res {
        Ok(rec) => {
            set_mutex_string(last_ssh_connect_error(), "");
            let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
            ssh_sessions().insert(handle, Arc::new(rec));
            Ok(handle)
        }
        Err(e) => {
            let msg = e.to_string();
            set_mutex_string(last_ssh_connect_error(), &msg);
            Err(msg)
        }
    }
}

// ============================================================================
// 1. NativeVaultCryptoBridge (Preserved 100%)
// ============================================================================

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
        let comment_str = read_jstring(&mut env, &comment);
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

// ============================================================================
// 2. SshNativeBridge — VT Terminal Engine, Live SSH, Live Telnet & Transfer
// ============================================================================

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

        new_checkjni_string(&mut env, snapshot_json)
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
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeVtApplicationCursorKeys<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let active = terminal_registry()
            .lock()
            .ok()
            .and_then(|reg| reg.get(&handle).map(VtTerminalBuffer::application_cursor_keys))
            .unwrap_or(false);
        to_jbool(active)
    }))
    .unwrap_or(JNI_FALSE)
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
        let dir_s = {
            let s = read_jstring(&mut env, &parent_dir);
            if s.is_empty() { String::from("/") } else { s }
        };
        let body_s = read_jstring(&mut env, &mlsd_body);
        let entries = parse_ftp_mlsd_listing(&dir_s, &body_s);
        let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".into());
        new_checkjni_string(&mut env, json)
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeParseFtpMlsdJson");
            JObject::null().into_raw()
        }
    }
}

// --- Live SSHv2 JNI (`SshNativeBridge.nativeSsh*`) ---

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshConnect<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    username: JString<'local>,
    password: JString<'local>,
    private_key_pem: JString<'local>,
    passphrase: JString<'local>,
    expected_fingerprint: JString<'local>,
    cols: jint,
    rows: jint,
    enable_x11: jboolean,
    startup_command: JString<'local>,
    bastion_host: JString<'local>,
    bastion_port: jint,
    bastion_user: JString<'local>,
    bastion_password: JString<'local>,
    bastion_key_pem: JString<'local>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| -> jlong {
        let host_s = read_jstring(&mut env, &host);
        if host_s.trim().is_empty() {
            set_mutex_string(last_ssh_connect_error(), "Empty SSH host");
            return 0;
        }
        let user_s = read_jstring(&mut env, &username);
        let pass_s = read_jstring(&mut env, &password);
        let key_s = read_jstring(&mut env, &private_key_pem);
        let key_pass_s = read_jstring(&mut env, &passphrase);
        let fp_s = read_jstring(&mut env, &expected_fingerprint);
        let cmd_s = read_jstring(&mut env, &startup_command);

        let b_host = read_jstring(&mut env, &bastion_host);
        let b_user = read_jstring(&mut env, &bastion_user);
        let b_pass = read_jstring(&mut env, &bastion_password);
        let b_key = read_jstring(&mut env, &bastion_key_pem);

        let spec = build_ssh_spec_from_params(
            host_s,
            u16::try_from(port).unwrap_or(22),
            user_s,
            pass_s,
            key_s,
            key_pass_s,
            fp_s,
            cols.max(10) as u32,
            rows.max(4) as u32,
            enable_x11 != JNI_FALSE,
            cmd_s,
        );

        let bastion_spec = if !b_host.trim().is_empty() {
            Some(build_ssh_spec_from_params(
                b_host,
                u16::try_from(bastion_port).unwrap_or(22),
                b_user,
                b_pass,
                b_key,
                String::new(),
                String::new(),
                80,
                24,
                false,
                String::new(),
            ))
        } else {
            None
        };

        connect_ssh_session_internal(spec, bastion_spec, true).unwrap_or(0)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshConnectJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    connect_spec_json: JString<'local>,
    bastion_spec_json: JString<'local>,
    open_pty: jboolean,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| -> jlong {
        let spec_s = read_jstring(&mut env, &connect_spec_json);
        let spec: SshConnectSpec = match serde_json::from_str(&spec_s) {
            Ok(s) => s,
            Err(e) => {
                set_mutex_string(last_ssh_connect_error(), format!("Invalid SSH spec JSON: {e}"));
                return 0;
            }
        };
        let b_s = read_jstring(&mut env, &bastion_spec_json);
        let bastion_spec: Option<SshConnectSpec> = if b_s.trim().is_empty() {
            None
        } else {
            serde_json::from_str(&b_s).ok()
        };

        connect_ssh_session_internal(spec, bastion_spec, open_pty != JNI_FALSE).unwrap_or(0)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshStartLocalForward<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bind_addr: JString<'local>,
    bind_port: jint,
    target_host: JString<'local>,
    target_port: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let Some(rec) = ssh_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let bind_s = {
            let s = read_jstring(&mut env, &bind_addr);
            if s.trim().is_empty() {
                String::from("127.0.0.1")
            } else {
                s
            }
        };
        let target_s = {
            let s = read_jstring(&mut env, &target_host);
            if s.trim().is_empty() {
                String::from("127.0.0.1")
            } else {
                s
            }
        };
        let Ok(bp) = u16::try_from(bind_port) else {
            return JNI_FALSE;
        };
        let Ok(tp) = u16::try_from(target_port) else {
            return JNI_FALSE;
        };

        let ssh_handle = rec.session.shared_handle();
        let res = tokio_runtime().block_on(async move {
            ActiveSshClientSession::start_local_port_forward(ssh_handle, bind_s, bp, target_s, tp)
                .await
        });

        match res {
            Ok(abort_handle) => {
                if let Ok(mut aborts) = rec.abort_handles.lock() {
                    aborts.push(abort_handle);
                }
                JNI_TRUE
            }
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                JNI_FALSE
            }
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshStartDynamicSocks5<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bind_addr: JString<'local>,
    bind_port: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let Some(rec) = ssh_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let bind_s = {
            let s = read_jstring(&mut env, &bind_addr);
            if s.trim().is_empty() {
                String::from("127.0.0.1")
            } else {
                s
            }
        };
        let Ok(bp) = u16::try_from(bind_port) else {
            return JNI_FALSE;
        };

        let ssh_handle = rec.session.shared_handle();
        let res = tokio_runtime().block_on(async move {
            ActiveSshClientSession::start_dynamic_socks5_proxy(ssh_handle, bind_s, bp).await
        });

        match res {
            Ok(abort_handle) => {
                if let Ok(mut aborts) = rec.abort_handles.lock() {
                    aborts.push(abort_handle);
                }
                JNI_TRUE
            }
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                JNI_FALSE
            }
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshWrite<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        if data.is_null() {
            return JNI_TRUE;
        }
        let Ok(bytes) = env.convert_byte_array(&data) else {
            return JNI_FALSE;
        };
        if bytes.is_empty() {
            return JNI_TRUE;
        }
        let Some(rec) = ssh_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let ok = rec
            .cmd_tx
            .as_ref()
            .map(|tx| tx.send(JniSshActorCmd::Write(bytes)).is_ok())
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshRead<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| -> jbyteArray {
        let Some(rec) = ssh_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JObject::null().into_raw();
        };
        let drained = rec
            .output_buf
            .lock()
            .ok()
            .map(|mut b| std::mem::take(&mut *b))
            .unwrap_or_default();
        if drained.is_empty() && rec.eof_flag.load(Ordering::Acquire) {
            return JObject::null().into_raw();
        }
        env.byte_array_from_slice(&drained)
            .map(|a| a.into_raw())
            .unwrap_or_else(|_| JObject::null().into_raw())
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshResize<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    cols: jint,
    rows: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let Some(rec) = ssh_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let ok = rec
            .cmd_tx
            .as_ref()
            .map(|tx| {
                tx.send(JniSshActorCmd::Resize {
                    cols: cols.max(10) as u32,
                    rows: rows.max(4) as u32,
                })
                .is_ok()
            })
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshIsConnected<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let connected = ssh_sessions()
            .get(&handle)
            .map(|rec| !rec.eof_flag.load(Ordering::Acquire))
            .unwrap_or(false);
        to_jbool(connected)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshLastError<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| -> jstring {
        let msg = if handle <= 0 {
            get_mutex_string(last_ssh_connect_error())
        } else {
            ssh_sessions()
                .get(&handle)
                .map(|rec| get_mutex_string(&rec.last_error))
                .unwrap_or_else(|| get_mutex_string(last_ssh_connect_error()))
        };
        new_checkjni_string(&mut env, msg)
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeSshDisconnect<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some((_, rec)) = ssh_sessions().remove(&handle) {
            if let Some(tx) = &rec.cmd_tx {
                let _ = tx.send(JniSshActorCmd::Close);
            }
            if let Ok(mut aborts) = rec.abort_handles.lock() {
                for abort_handle in aborts.drain(..) {
                    abort_handle.abort();
                }
            }
            let session = Arc::clone(&rec.session);
            tokio_runtime().spawn(async move {
                let _ = session.disconnect().await;
            });
        }
    }));
}

// --- Live Stateful Telnet JNI (`SshNativeBridge.nativeTelnet*`) ---

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTelnetConnect<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    cols: jint,
    rows: jint,
    term_type: JString<'local>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| -> jlong {
        let host_s = read_jstring(&mut env, &host);
        if host_s.trim().is_empty() {
            set_mutex_string(last_telnet_connect_error(), "Empty Telnet host");
            return 0;
        }
        let term_s = {
            let t = read_jstring(&mut env, &term_type);
            if t.trim().is_empty() {
                String::from("xterm-256color")
            } else {
                t
            }
        };
        let p = u16::try_from(port).unwrap_or(23);
        let c = (cols.clamp(10, 1000)) as u16;
        let r = (rows.clamp(4, 500)) as u16;

        let res = tokio_runtime().block_on(async move {
            ActiveTelnetSession::connect(&host_s, p, c, r, &term_s).await
        });

        match res {
            Ok(session) => {
                set_mutex_string(last_telnet_connect_error(), "");
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                telnet_sessions().insert(handle, Arc::new(session));
                handle
            }
            Err(e) => {
                set_mutex_string(last_telnet_connect_error(), e.to_string());
                0
            }
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTelnetWrite<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        if data.is_null() {
            return JNI_TRUE;
        }
        let Ok(bytes) = env.convert_byte_array(&data) else {
            return JNI_FALSE;
        };
        let ok = telnet_sessions()
            .get(&handle)
            .map(|s| s.write_stdin(&bytes))
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTelnetRead<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| -> jbyteArray {
        let Some(session) = telnet_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JObject::null().into_raw();
        };
        let drained = session.drain_output();
        if drained.is_empty() && !session.is_connected() {
            return JObject::null().into_raw();
        }
        env.byte_array_from_slice(&drained)
            .map(|a| a.into_raw())
            .unwrap_or_else(|_| JObject::null().into_raw())
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTelnetResize<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    cols: jint,
    rows: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let ok = telnet_sessions()
            .get(&handle)
            .map(|s| s.resize_pty(cols.clamp(10, 1000) as u16, rows.clamp(4, 500) as u16))
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTelnetIsConnected<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let ok = telnet_sessions()
            .get(&handle)
            .map(|s| s.is_connected())
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTelnetLastError<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| -> jstring {
        let err = if handle <= 0 {
            get_mutex_string(last_telnet_connect_error())
        } else {
            telnet_sessions()
                .get(&handle)
                .map(|s| s.last_error())
                .unwrap_or_else(|| get_mutex_string(last_telnet_connect_error()))
        };
        new_checkjni_string(&mut env, err)
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTelnetDisconnect<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some((_, session)) = telnet_sessions().remove(&handle) {
            session.disconnect();
        }
    }));
}

// --- Live Unified Transfer JNI (`SshNativeBridge.nativeTransfer*` for SFTP / SCP / FTP) ---

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferConnect<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    protocol: JString<'local>,
    host: JString<'local>,
    port: jint,
    username: JString<'local>,
    password: JString<'local>,
    private_key_pem: JString<'local>,
    passphrase: JString<'local>,
    bastion_host: JString<'local>,
    bastion_port: jint,
    bastion_user: JString<'local>,
    bastion_password: JString<'local>,
    bastion_key_pem: JString<'local>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| -> jlong {
        let proto = read_jstring(&mut env, &protocol)
            .trim()
            .to_ascii_uppercase();
        let host_s = read_jstring(&mut env, &host);
        if host_s.trim().is_empty() {
            set_mutex_string(last_transfer_connect_error(), "Empty remote host");
            return 0;
        }
        let user_s = read_jstring(&mut env, &username);
        let pass_s = read_jstring(&mut env, &password);
        let key_s = read_jstring(&mut env, &private_key_pem);
        let key_pass_s = read_jstring(&mut env, &passphrase);

        let b_host = read_jstring(&mut env, &bastion_host);
        let b_user = read_jstring(&mut env, &bastion_user);
        let b_pass = read_jstring(&mut env, &bastion_password);
        let b_key = read_jstring(&mut env, &bastion_key_pem);

        let res: Result<JniTransferBackend, String> = tokio_runtime().block_on(async move {
            match proto.as_str() {
                "FTP" => {
                    let p = u16::try_from(port).unwrap_or(21);
                    let u = if user_s.trim().is_empty() {
                        "anonymous"
                    } else {
                        user_s.trim()
                    };
                    let client = NexusFtpClient::connect(&host_s, p, u, &pass_s)
                        .await
                        .map_err(|e| e.to_string())?;
                    Ok(JniTransferBackend::Ftp {
                        client: Arc::new(client),
                    })
                }
                "SCP" => {
                    let spec = build_ssh_spec_from_params(
                        host_s,
                        u16::try_from(port).unwrap_or(22),
                        user_s,
                        pass_s,
                        key_s,
                        key_pass_s,
                        String::new(),
                        80,
                        24,
                        false,
                        String::new(),
                    );
                    let bspec = if !b_host.trim().is_empty() {
                        Some(build_ssh_spec_from_params(
                            b_host,
                            u16::try_from(bastion_port).unwrap_or(22),
                            b_user,
                            b_pass,
                            b_key,
                            String::new(),
                            String::new(),
                            80,
                            24,
                            false,
                            String::new(),
                        ))
                    } else {
                        None
                    };
                    let client = NexusScpClient::connect(spec, bspec)
                        .await
                        .map_err(|e| e.to_string())?;
                    Ok(JniTransferBackend::Scp {
                        client: Arc::new(client),
                    })
                }
                _ => {
                    // Default: SFTP v3 over SSHv2
                    let spec = build_ssh_spec_from_params(
                        host_s,
                        u16::try_from(port).unwrap_or(22),
                        user_s,
                        pass_s,
                        key_s,
                        key_pass_s,
                        String::new(),
                        80,
                        24,
                        false,
                        String::new(),
                    );
                    let bspec = if !b_host.trim().is_empty() {
                        Some(build_ssh_spec_from_params(
                            b_host,
                            u16::try_from(bastion_port).unwrap_or(22),
                            b_user,
                            b_pass,
                            b_key,
                            String::new(),
                            String::new(),
                            80,
                            24,
                            false,
                            String::new(),
                        ))
                    } else {
                        None
                    };
                    let bastion = if let Some(bs) = bspec {
                        Some(
                            ActiveSshClientSession::connect_without_shell(bs, None)
                                .await
                                .map_err(|e| e.to_string())?,
                        )
                    } else {
                        None
                    };
                    let ssh = ActiveSshClientSession::connect_without_shell(spec, bastion.as_ref())
                        .await
                        .map_err(|e| e.to_string())?;
                    let stream = ssh
                        .open_sftp_subsystem_stream()
                        .await
                        .map_err(|e| e.to_string())?;
                    let sftp = NexusSftpClient::from_subsystem_stream(stream)
                        .await
                        .map_err(|e| e.to_string())?;

                    let bastion_abort = bastion.map(|b| {
                        tokio::spawn(async move {
                            let _keep = b;
                            std::future::pending::<()>().await;
                        })
                        .abort_handle()
                    });

                    Ok(JniTransferBackend::Sftp {
                        ssh: Arc::new(ssh),
                        sftp: Arc::new(sftp),
                        bastion_abort,
                    })
                }
            }
        });

        match res {
            Ok(backend) => {
                set_mutex_string(last_transfer_connect_error(), "");
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                transfer_sessions().insert(
                    handle,
                    Arc::new(JniTransferRecord {
                        backend,
                        last_error: Mutex::new(String::new()),
                    }),
                );
                handle
            }
            Err(err) => {
                set_mutex_string(last_transfer_connect_error(), err);
                0
            }
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferLastError<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| -> jstring {
        let msg = if handle <= 0 {
            get_mutex_string(last_transfer_connect_error())
        } else {
            transfer_sessions()
                .get(&handle)
                .map(|r| get_mutex_string(&r.last_error))
                .unwrap_or_else(|| get_mutex_string(last_transfer_connect_error()))
        };
        new_checkjni_string(&mut env, msg)
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferPwd<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| -> jstring {
        let Some(rec) = transfer_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return new_checkjni_string(&mut env, "");
        };
        let res = tokio_runtime().block_on(async {
            match &rec.backend {
                JniTransferBackend::Sftp { sftp, .. } => sftp.pwd().await,
                JniTransferBackend::Scp { client } => client.pwd().await,
                JniTransferBackend::Ftp { client } => client.pwd().await,
            }
        });
        match res {
            Ok(path) => new_checkjni_string(&mut env, path),
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                new_checkjni_string(&mut env, "")
            }
        }
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferListDirJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    remote_path: JString<'local>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| -> jstring {
        let Some(rec) = transfer_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return new_checkjni_string(&mut env, "[]");
        };
        let path = {
            let p = read_jstring(&mut env, &remote_path);
            if p.trim().is_empty() {
                String::from("/")
            } else {
                p
            }
        };
        let res = tokio_runtime().block_on(async {
            match &rec.backend {
                JniTransferBackend::Sftp { sftp, .. } => sftp.list_directory(&path).await,
                JniTransferBackend::Scp { client } => client.list_directory(&path).await,
                JniTransferBackend::Ftp { client } => client.list_directory(&path).await,
            }
        });
        match res {
            Ok(entries) => {
                let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".into());
                new_checkjni_string(&mut env, json)
            }
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                new_checkjni_string(&mut env, "[]")
            }
        }
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferReadFile<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    remote_path: JString<'local>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| -> jbyteArray {
        let Some(rec) = transfer_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JObject::null().into_raw();
        };
        let path = read_jstring(&mut env, &remote_path);
        let res = tokio_runtime().block_on(async {
            match &rec.backend {
                JniTransferBackend::Sftp { sftp, .. } => sftp.download_bytes(&path).await,
                JniTransferBackend::Scp { client } => client.download_bytes(&path).await,
                JniTransferBackend::Ftp { client } => client.download_bytes(&path).await,
            }
        });
        match res {
            Ok(bytes) => env
                .byte_array_from_slice(&bytes)
                .map(|a| a.into_raw())
                .unwrap_or_else(|_| JObject::null().into_raw()),
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                JObject::null().into_raw()
            }
        }
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferWriteFile<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    remote_path: JString<'local>,
    content: JByteArray<'local>,
    mode_octal: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let Some(rec) = transfer_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let path = read_jstring(&mut env, &remote_path);
        let payload = if content.is_null() {
            Vec::new()
        } else {
            env.convert_byte_array(&content).unwrap_or_default()
        };
        let mode = if mode_octal <= 0 {
            0o644u32
        } else {
            mode_octal as u32
        };

        let res = tokio_runtime().block_on(async {
            match &rec.backend {
                JniTransferBackend::Sftp { sftp, .. } => {
                    sftp.upload_bytes(&path, &payload).await.map(|_| ())
                }
                JniTransferBackend::Scp { client } => {
                    client.upload_bytes(&path, &payload, mode).await.map(|_| ())
                }
                JniTransferBackend::Ftp { client } => {
                    client.upload_bytes(&path, &payload).await.map(|_| ())
                }
            }
        });

        match res {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                JNI_FALSE
            }
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferMkdir<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    remote_path: JString<'local>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let Some(rec) = transfer_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let path = read_jstring(&mut env, &remote_path);
        let res = tokio_runtime().block_on(async {
            match &rec.backend {
                JniTransferBackend::Sftp { sftp, .. } => sftp.create_directory(&path).await,
                JniTransferBackend::Scp { client } => client.create_directory(&path).await,
                JniTransferBackend::Ftp { client } => client.create_directory(&path).await,
            }
        });
        match res {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                JNI_FALSE
            }
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferRemove<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    remote_path: JString<'local>,
    is_dir: jboolean,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let Some(rec) = transfer_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let path = read_jstring(&mut env, &remote_path);
        let dir_flag = is_dir != JNI_FALSE;
        let res = tokio_runtime().block_on(async {
            match &rec.backend {
                JniTransferBackend::Sftp { sftp, .. } => sftp.remove_entry(&path, dir_flag).await,
                JniTransferBackend::Scp { client } => client.remove_entry(&path).await,
                JniTransferBackend::Ftp { client } => client.remove_entry(&path).await,
            }
        });
        match res {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                JNI_FALSE
            }
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferRename<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    old_path: JString<'local>,
    new_path: JString<'local>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let Some(rec) = transfer_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let old_p = read_jstring(&mut env, &old_path);
        let new_p = read_jstring(&mut env, &new_path);
        let res = tokio_runtime().block_on(async {
            match &rec.backend {
                JniTransferBackend::Sftp { sftp, .. } => sftp.rename(&old_p, &new_p).await,
                JniTransferBackend::Scp { client } => client.rename(&old_p, &new_p).await,
                JniTransferBackend::Ftp { client } => client.rename(&old_p, &new_p).await,
            }
        });
        match res {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                JNI_FALSE
            }
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferChmod<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    remote_path: JString<'local>,
    mode_octal: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let Some(rec) = transfer_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JNI_FALSE;
        };
        let path = read_jstring(&mut env, &remote_path);
        let mode = mode_octal.max(0) as u32;
        let res = tokio_runtime().block_on(async {
            match &rec.backend {
                JniTransferBackend::Sftp { sftp, .. } => sftp.chmod(&path, mode).await,
                JniTransferBackend::Scp { client } => client.chmod(&path, mode).await,
                JniTransferBackend::Ftp { client } => client.chmod(&path, mode).await,
            }
        });
        match res {
            Ok(()) => JNI_TRUE,
            Err(e) => {
                set_mutex_string(&rec.last_error, e.to_string());
                JNI_FALSE
            }
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_ssh_bridge_SshNativeBridge_nativeTransferDisconnect<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some((_, rec)) = transfer_sessions().remove(&handle) {
            match &rec.backend {
                JniTransferBackend::Sftp {
                    ssh,
                    sftp,
                    bastion_abort,
                } => {
                    if let Some(abort) = bastion_abort {
                        abort.abort();
                    }
                    let sftp_c = Arc::clone(sftp);
                    let ssh_c = Arc::clone(ssh);
                    tokio_runtime().spawn(async move {
                        let _ = sftp_c.close().await;
                        let _ = ssh_c.disconnect().await;
                    });
                }
                JniTransferBackend::Scp { client } => {
                    let c = Arc::clone(client);
                    tokio_runtime().spawn(async move {
                        let _ = c.disconnect().await;
                    });
                }
                JniTransferBackend::Ftp { client } => {
                    let c = Arc::clone(client);
                    tokio_runtime().spawn(async move {
                        let _ = c.disconnect().await;
                    });
                }
            }
        }
    }));
}

// ============================================================================
// 3. VncNativeBridge — Standalone Framebuffer, Live VNC/ARD & Net Utilities
// ============================================================================

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
                    RFB_PSEUDO_DESKTOP_SIZE => {
                        fb.resize(u32::from(uw.max(1)), u32::from(uh.max(1)));
                        Ok(())
                    }
                    RFB_ENCODING_RAW => fb.apply_raw_rect(ux, uy, uw, uh, &raw_payload),
                    RFB_ENCODING_COPYRECT if raw_payload.len() >= 4 => {
                        let sx = u16::from_be_bytes([raw_payload[0], raw_payload[1]]);
                        let sy = u16::from_be_bytes([raw_payload[2], raw_payload[3]]);
                        fb.apply_copy_rect(ux, uy, uw, uh, sx, sy)
                    }
                    RFB_ENCODING_RRE => fb.apply_rre_rect(ux, uy, uw, uh, &raw_payload),
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

// --- Live Async RFB 3.3 / 3.7 / 3.8 & Apple ARD Type 30 JNI (`VncNativeBridge.nativeVncSession*`) ---

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionConnect<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    username: JString<'local>,
    password: JString<'local>,
    shared_session: jboolean,
    view_only: jboolean,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| -> jlong {
        let host_s = read_jstring(&mut env, &host);
        if host_s.trim().is_empty() {
            set_mutex_string(last_vnc_connect_error(), "Empty VNC host");
            return 0;
        }
        let user_s = read_jstring(&mut env, &username);
        let pass_s = read_jstring(&mut env, &password);
        let p = u16::try_from(port).unwrap_or(5900);
        let shared = shared_session != JNI_FALSE;
        let ro = view_only != JNI_FALSE;

        let res = tokio_runtime().block_on(async move {
            ActiveVncClient::connect(&host_s, p, &user_s, &pass_s, shared, ro).await
        });

        match res {
            Ok(client) => {
                set_mutex_string(last_vnc_connect_error(), "");
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                vnc_sessions().insert(handle, Arc::new(client));
                handle
            }
            Err(e) => {
                set_mutex_string(last_vnc_connect_error(), e.to_string());
                0
            }
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionIsConnected<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let ok = vnc_sessions()
            .get(&handle)
            .map(|c| c.is_connected())
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionLastError<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| -> jstring {
        let err = if handle <= 0 {
            get_mutex_string(last_vnc_connect_error())
        } else {
            vnc_sessions()
                .get(&handle)
                .map(|c| c.last_error())
                .unwrap_or_else(|| get_mutex_string(last_vnc_connect_error()))
        };
        new_checkjni_string(&mut env, err)
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionDesktopName<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| -> jstring {
        let name = vnc_sessions()
            .get(&handle)
            .map(|c| c.desktop_name().to_string())
            .unwrap_or_default();
        new_checkjni_string(&mut env, name)
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionFrameSequence<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| -> jlong {
        vnc_sessions()
            .get(&handle)
            .map(|c| c.frame_sequence() as jlong)
            .unwrap_or(0)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionGetDimensions<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jintArray {
    catch_unwind(AssertUnwindSafe(|| -> jintArray {
        let Some(client) = vnc_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return env
                .new_int_array(0)
                .map(|a| a.into_raw())
                .unwrap_or_else(|_| JObject::null().into_raw());
        };
        let (w, h) = client.dimensions();
        match env.new_int_array(2) {
            Ok(arr) => {
                let dims = [w as jint, h as jint];
                let _ = env.set_int_array_region(&arr, 0, &dims);
                arr.into_raw()
            }
            Err(_) => JObject::null().into_raw(),
        }
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionCopyFrameRgba<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    dst_rgba: JByteArray<'local>,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| -> jint {
        if dst_rgba.is_null() {
            return 0;
        }
        let Ok(dst_len) = env.get_array_length(&dst_rgba) else {
            return 0;
        };
        if dst_len <= 0 {
            return 0;
        }
        let Some(client) = vnc_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return 0;
        };
        let rgba = client.to_rgba_vec();
        let copy_len = rgba.len().min(dst_len as usize);
        if copy_len == 0 {
            return 0;
        }
        let jbytes = unsafe { std::slice::from_raw_parts(rgba.as_ptr() as *const jbyte, copy_len) };
        if env.set_byte_array_region(&dst_rgba, 0 as jsize, jbytes).is_ok() {
            copy_len as jint
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionGetFrameRgba<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| -> jbyteArray {
        let Some(client) = vnc_sessions().get(&handle).map(|r| Arc::clone(r.value())) else {
            return JObject::null().into_raw();
        };
        let rgba = client.to_rgba_vec();
        env.byte_array_from_slice(&rgba)
            .map(|a| a.into_raw())
            .unwrap_or_else(|_| JObject::null().into_raw())
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionSendPointer<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    button_mask: jint,
    x: jint,
    y: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let ok = vnc_sessions()
            .get(&handle)
            .map(|c| {
                c.send_pointer(
                    (button_mask & 0xFF) as u8,
                    x.clamp(0, u16::MAX as jint) as u16,
                    y.clamp(0, u16::MAX as jint) as u16,
                )
            })
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionSendKey<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    down: jboolean,
    keysym: jint,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let ok = vnc_sessions()
            .get(&handle)
            .map(|c| c.send_key(down != JNI_FALSE, keysym as u32))
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionSendClipboard<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    text: JString<'local>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| -> jboolean {
        let clip_text = read_jstring(&mut env, &text);
        let ok = vnc_sessions()
            .get(&handle)
            .map(|c| c.send_clipboard(&clip_text))
            .unwrap_or(false);
        to_jbool(ok)
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionPullClipboard<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| -> jstring {
        let Some(clip) = vnc_sessions().get(&handle).and_then(|c| c.pull_clipboard()) else {
            return JObject::null().into_raw();
        };
        new_checkjni_string(&mut env, clip)
    }))
    .unwrap_or_else(|_| JObject::null().into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeVncSessionDisconnect<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some((_, client)) = vnc_sessions().remove(&handle) {
            client.disconnect();
        }
    }));
}

// --- Network Utilities JNI (`VncNativeBridge`) ---

fn send_wol_with_optional_secure_on<'local>(
    env: &mut JNIEnv<'local>,
    mac: &JString<'local>,
    broadcast_ip: &JString<'local>,
    port: jint,
    secure_on: Option<&JString<'local>>,
) -> jint {
    let mac_s = read_jstring(env, mac);
    let ip_s = {
        let s = read_jstring(env, broadcast_ip);
        if s.trim().is_empty() {
            String::from("255.255.255.255")
        } else {
            s
        }
    };
    let sec_s: Option<String> = secure_on
        .map(|jsec| read_jstring(env, jsec))
        .filter(|s| !s.trim().is_empty());
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
        let host_s = read_jstring(&mut env, &host);
        let tcp_port = u16::try_from(port).unwrap_or(0);
        let hit = if tcp_port == 0 {
            None
        } else {
            probe_tcp_port(&host_s, tcp_port, timeout_ms.max(1) as u64)
        };
        let json = serde_json::to_string(&hit).unwrap_or_else(|_| "null".into());
        new_checkjni_string(&mut env, json)
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
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeScanPortsJson<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    start_port: jint,
    end_port: jint,
    timeout_ms: jint,
    concurrency: jint,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> jstring {
        let host_s = read_jstring(&mut env, &host);
        if host_s.trim().is_empty() {
            return new_checkjni_string(&mut env, "[]");
        }
        let sp = start_port.clamp(1, 65535) as u16;
        let ep = end_port.clamp(1, 65535) as u16;
        let t_ms = timeout_ms.max(20) as u64;
        let conc = concurrency.clamp(1, 512) as usize;

        let hits = tokio_runtime()
            .block_on(async move { scan_tcp_ports(&host_s, sp, ep, t_ms, conc).await });
        let json = serde_json::to_string(&hits).unwrap_or_else(|_| "[]".into());
        new_checkjni_string(&mut env, json)
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeScanPortsJson");
            JObject::null().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nexusts_app_protocols_vnc_bridge_VncNativeBridge_nativeTcpLatencyPingMs<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    timeout_ms: jint,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| -> jlong {
        let host_s = read_jstring(&mut env, &host);
        let Ok(p) = u16::try_from(port) else {
            return -1;
        };
        if host_s.trim().is_empty() || p == 0 {
            return -1;
        }
        let t_ms = timeout_ms.max(20) as u64;
        tokio_runtime()
            .block_on(async move { tcp_latency_ping(&host_s, p, t_ms).await })
            .map(|ms| ms as jlong)
            .unwrap_or(-1)
    }))
    .unwrap_or(-1)
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
        let host_s = read_jstring(&mut env, &host);
        let port = u16::try_from(probe_port).unwrap_or(443);
        let hops_limit = max_hops.clamp(1, 32) as u8;
        match trace_route_hops(&host_s, port, hops_limit, timeout_ms.max(50) as u64) {
            Ok(hops) => {
                let json = serde_json::to_string(&hops).unwrap_or_else(|_| "[]".into());
                new_checkjni_string(&mut env, json)
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
        let host_s = read_jstring(&mut env, &host);
        let tcp_port = u16::try_from(port).unwrap_or(0);
        let banner = if tcp_port == 0 {
            String::new()
        } else {
            grab_tcp_service_banner(&host_s, tcp_port, timeout_ms.max(50) as u64)
                .unwrap_or_default()
        };
        new_checkjni_string(&mut env, banner)
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => {
            throw_runtime_exception(&mut env, "Rust panic in nativeGrabTcpBanner");
            JObject::null().into_raw()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ssh_spec_builder_prefers_private_key_when_present_and_falls_back_to_password() {
        let pw_spec = build_ssh_spec_from_params(
            "10.0.0.15".into(),
            2222,
            "ops".into(),
            "secret".into(),
            String::new(),
            String::new(),
            String::new(),
            120,
            40,
            false,
            "uptime".into(),
        );
        assert_eq!(pw_spec.host, "10.0.0.15");
        assert_eq!(pw_spec.port, 2222);
        assert_eq!(pw_spec.startup_command.as_deref(), Some("uptime"));
        assert!(matches!(pw_spec.auth, SshAuthMethod::Password { .. }));

        let key_spec = build_ssh_spec_from_params(
            "10.0.0.16".into(),
            0,
            "".into(),
            "ignored".into(),
            "-----BEGIN OPENSSH PRIVATE KEY-----\n...".into(),
            "pass".into(),
            "SHA256:abcd".into(),
            80,
            24,
            true,
            String::new(),
        );
        assert_eq!(key_spec.port, 22);
        assert_eq!(key_spec.username, "root");
        assert!(matches!(
            key_spec.auth,
            SshAuthMethod::PrivateKeyPem {
                passphrase: Some(_),
                ..
            }
        ));
    }
}
