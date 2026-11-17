//! Unified C-ABI FFI Bridge (`libnexusts_ffi.a` / `libnexusts_ffi.dylib`) exposing
//! Zero-Knowledge `.nxts` v2 Vault Crypto, `xterm-256color` VT Terminal Engine (incl. DECCKM),
//! Async `russh` SSHv2 + Bastion + Abort-Safe Port Forwards, Stateful Telnet,
//! SFTP v3 + SCP + FTP File Transfer, Live RFB 3.3/3.7/3.8 VNC & Apple ARD Type 30,
//! and Network Diagnostics natively to Swift 6 / macOS.

use nexusts_crypto::{
    clear_ssh_agent, generate_ed25519_ssh_keypair, open_nxts_vault, seal_nxts_vault,
    sign_ssh_agent_challenge, Argon2idProfile,
};
use nexusts_netutils::winrm_ntlm::{
    ntlm_build_authenticate_b64, ntlm_build_negotiate_b64, winrm_http_wsman_post,
};
use nexusts_netutils::{
    grab_tcp_service_banner, probe_tcp_port, scan_tcp_ports, send_wake_on_lan, tcp_latency_ping,
    trace_route_hops,
};
use nexusts_terminal::ssh_transport::{
    ActiveSshClientSession, SshConnectSpec, TelnetNegotiator,
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
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use tokio::runtime::{Builder, Runtime};
use tokio::sync::mpsc;
use tokio::task::AbortHandle;

#[repr(C)]
pub struct NexusFfiBuffer {
    pub data: *mut u8,
    pub len: usize,
    pub cap: usize,
    pub error_msg: *mut c_char,
}

impl NexusFfiBuffer {
    fn from_vec(mut v: Vec<u8>) -> Self {
        let ptr = v.as_mut_ptr();
        let len = v.len();
        let cap = v.capacity();
        std::mem::forget(v);
        Self {
            data: ptr,
            len,
            cap,
            error_msg: ptr::null_mut(),
        }
    }

    fn from_error(msg: impl Into<String>) -> Self {
        let cstr = to_c_string(msg.into());
        Self {
            data: ptr::null_mut(),
            len: 0,
            cap: 0,
            error_msg: cstr,
        }
    }

    fn empty() -> Self {
        Self {
            data: ptr::null_mut(),
            len: 0,
            cap: 0,
            error_msg: ptr::null_mut(),
        }
    }
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(10_000);

fn tokio_runtime() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("nexusts-ffi-tokio")
            .enable_all()
            .build()
            .expect("Failed to initialize NexusTS Tokio runtime")
    })
}

enum SshActorCommand {
    SendInput(Vec<u8>),
    ResizePty { cols: u32, rows: u32 },
    Close,
}

struct LiveSshRecord {
    session: Arc<ActiveSshClientSession>,
    cmd_tx: Option<mpsc::UnboundedSender<SshActorCommand>>,
    output_buf: Arc<Mutex<Vec<u8>>>,
    eof_flag: Arc<AtomicBool>,
    abort_handles: Mutex<Vec<AbortHandle>>,
}

fn vt_registry() -> &'static Mutex<HashMap<i64, VtTerminalBuffer>> {
    static REG: OnceLock<Mutex<HashMap<i64, VtTerminalBuffer>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn ssh_registry() -> &'static Mutex<HashMap<i64, LiveSshRecord>> {
    static REG: OnceLock<Mutex<HashMap<i64, LiveSshRecord>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn telnet_registry() -> &'static Mutex<HashMap<i64, Arc<ActiveTelnetSession>>> {
    static REG: OnceLock<Mutex<HashMap<i64, Arc<ActiveTelnetSession>>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn sftp_registry() -> &'static Mutex<HashMap<i64, Arc<NexusSftpClient>>> {
    static REG: OnceLock<Mutex<HashMap<i64, Arc<NexusSftpClient>>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn scp_registry() -> &'static Mutex<HashMap<i64, Arc<NexusScpClient>>> {
    static REG: OnceLock<Mutex<HashMap<i64, Arc<NexusScpClient>>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn ftp_registry() -> &'static Mutex<HashMap<i64, Arc<NexusFtpClient>>> {
    static REG: OnceLock<Mutex<HashMap<i64, Arc<NexusFtpClient>>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn vnc_registry() -> &'static Mutex<HashMap<i64, RfbFramebuffer>> {
    static REG: OnceLock<Mutex<HashMap<i64, RfbFramebuffer>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn vnc_session_registry() -> &'static Mutex<HashMap<i64, Arc<ActiveVncClient>>> {
    static REG: OnceLock<Mutex<HashMap<i64, Arc<ActiveVncClient>>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn to_c_string(s: impl Into<String>) -> *mut c_char {
    let cleaned = s.into().replace('\0', "");
    CString::new(cleaned)
        .map(CString::into_raw)
        .unwrap_or(ptr::null_mut())
}

unsafe fn opt_str_from_c<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        None
    } else {
        CStr::from_ptr(ptr).to_str().ok()
    }
}

unsafe fn slice_from_c<'a>(ptr: *const u8, len: usize) -> &'a [u8] {
    if ptr.is_null() || len == 0 {
        &[]
    } else {
        slice::from_raw_parts(ptr, len)
    }
}

unsafe fn write_out_error(out_error: *mut *mut c_char, msg: impl Into<String>) {
    if !out_error.is_null() {
        *out_error = to_c_string(msg);
    }
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_free_buffer(buf: NexusFfiBuffer) {
    unsafe {
        if !buf.data.is_null() && buf.cap > 0 {
            let _ = Vec::from_raw_parts(buf.data, buf.len, buf.cap);
        }
        if !buf.error_msg.is_null() {
            let _ = CString::from_raw(buf.error_msg);
        }
    }
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_free_cstr(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}

// ============================================================================
// 1. Zero-Knowledge .nxts v2 Vault Cryptography & OpenSSH Ed25519 Keygen
// ============================================================================

#[no_mangle]
pub extern "C" fn nexusts_ffi_seal_vault(
    plaintext_ptr: *const u8,
    plaintext_len: usize,
    passphrase_ptr: *const u8,
    passphrase_len: usize,
    revision: u64,
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let plain = slice_from_c(plaintext_ptr, plaintext_len);
        let pass = slice_from_c(passphrase_ptr, passphrase_len);
        let profile = Argon2idProfile {
            memory_kib: memory_kib.max(8_192),
            iterations: iterations.max(1),
            parallelism: parallelism.max(1),
        };
        match seal_nxts_vault(plain, pass, revision.max(1), profile) {
            Ok(sealed) => NexusFfiBuffer::from_vec(sealed),
            Err(e) => NexusFfiBuffer::from_error(e.to_string()),
        }
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_ffi_seal_vault"))
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_open_vault(
    container_ptr: *const u8,
    container_len: usize,
    passphrase_ptr: *const u8,
    passphrase_len: usize,
    out_revision: *mut u64,
) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let blob = slice_from_c(container_ptr, container_len);
        let pass = slice_from_c(passphrase_ptr, passphrase_len);
        match open_nxts_vault(blob, pass) {
            Ok(opened) => {
                if !out_revision.is_null() {
                    *out_revision = opened.revision;
                }
                NexusFfiBuffer::from_vec(opened.plaintext.to_vec())
            }
            Err(e) => NexusFfiBuffer::from_error(e.to_string()),
        }
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_ffi_open_vault"))
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_generate_ed25519_keypair_json(
    comment_cstr: *const c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let comment = opt_str_from_c(comment_cstr).unwrap_or("");
        let kp = generate_ed25519_ssh_keypair(comment);
        let payload = serde_json::json!({
            "key_id": kp.key_id,
            "public_key_openssh": kp.public_key_openssh,
            "private_key_openssh_pem": kp.private_key_openssh_pem.as_str(),
            "sha256_fingerprint": kp.sha256_fingerprint,
        });
        to_c_string(payload.to_string())
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_agent_sign(
    key_id_cstr: *const c_char,
    challenge_ptr: *const u8,
    challenge_len: usize,
) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let key_id = opt_str_from_c(key_id_cstr).unwrap_or("");
        let challenge = slice_from_c(challenge_ptr, challenge_len);
        match sign_ssh_agent_challenge(key_id, challenge) {
            Ok(sig) => NexusFfiBuffer::from_vec(sig),
            Err(e) => NexusFfiBuffer::from_error(e.to_string()),
        }
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_ffi_ssh_agent_sign"))
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_agent_clear() {
    let _ = catch_unwind(|| {
        clear_ssh_agent();
    });
}

// ============================================================================
// 2. xterm-256color / 24-bit TrueColor VT Terminal Engine & Stateful Telnet
// ============================================================================

#[no_mangle]
pub extern "C" fn nexusts_ffi_vt_create(cols: u32, rows: u32, max_scrollback: u32) -> i64 {
    catch_unwind(AssertUnwindSafe(|| {
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let vt = VtTerminalBuffer::new(
            cols.max(10) as usize,
            rows.max(4) as usize,
            max_scrollback as usize,
        );
        if let Ok(mut reg) = vt_registry().lock() {
            reg.insert(handle, vt);
            handle
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vt_resize(handle: i64, cols: u32, rows: u32) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut reg) = vt_registry().lock() {
            if let Some(vt) = reg.get_mut(&handle) {
                vt.resize(cols.max(10) as usize, rows.max(4) as usize);
            }
        }
    }));
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vt_feed_and_snapshot_json(
    handle: i64,
    data_ptr: *const u8,
    data_len: usize,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let bytes = slice_from_c(data_ptr, data_len);
        if let Ok(mut reg) = vt_registry().lock() {
            if let Some(vt) = reg.get_mut(&handle) {
                if !bytes.is_empty() {
                    vt.feed_bytes(bytes);
                }
                let snap = serde_json::to_string(&vt.snapshot()).unwrap_or_default();
                return to_c_string(snap);
            }
        }
        ptr::null_mut()
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vt_snapshot_json(handle: i64) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| {
        if let Ok(reg) = vt_registry().lock() {
            if let Some(vt) = reg.get(&handle) {
                let snap = serde_json::to_string(&vt.snapshot()).unwrap_or_default();
                return to_c_string(snap);
            }
        }
        ptr::null_mut()
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_vt_application_cursor_keys(handle: i64) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        vt_registry()
            .lock()
            .ok()
            .and_then(|reg| reg.get(&handle).map(VtTerminalBuffer::application_cursor_keys))
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vt_application_cursor_keys(handle: i64) -> bool {
    nexusts_vt_application_cursor_keys(handle)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vt_destroy(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut reg) = vt_registry().lock() {
            reg.remove(&handle);
        }
    }));
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_telnet_process(
    cols: u16,
    rows: u16,
    term_cstr: *const c_char,
    wire_ptr: *const u8,
    wire_len: usize,
    out_replies: *mut NexusFfiBuffer,
) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let term = opt_str_from_c(term_cstr).unwrap_or("xterm-256color");
        let wire = slice_from_c(wire_ptr, wire_len);
        let neg = TelnetNegotiator::new(cols.max(10), rows.max(4), term);
        let (clean, replies) = neg.process_incoming(wire);
        if !out_replies.is_null() {
            *out_replies = NexusFfiBuffer::from_vec(replies);
        }
        NexusFfiBuffer::from_vec(clean)
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_ffi_telnet_process"))
}

fn get_telnet_session(handle: i64) -> Option<Arc<ActiveTelnetSession>> {
    telnet_registry()
        .lock()
        .ok()
        .and_then(|reg| reg.get(&handle).cloned())
}

#[no_mangle]
pub extern "C" fn nexusts_telnet_connect(
    host_cstr: *const c_char,
    port: u16,
    cols: u16,
    rows: u16,
    term_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let host = match opt_str_from_c(host_cstr).map(str::trim).filter(|s| !s.is_empty()) {
            Some(h) => h.to_string(),
            None => {
                write_out_error(out_error, "Missing Telnet host");
                return 0;
            }
        };
        let term = opt_str_from_c(term_cstr)
            .filter(|s| !s.trim().is_empty())
            .unwrap_or("xterm-256color")
            .to_string();

        let connect_res = tokio_runtime().block_on(async move {
            ActiveTelnetSession::connect(&host, port, cols.max(10), rows.max(4), &term).await
        });

        match connect_res {
            Ok(session) => {
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                if let Ok(mut reg) = telnet_registry().lock() {
                    reg.insert(handle, Arc::new(session));
                    handle
                } else {
                    write_out_error(out_error, "Telnet registry poisoned");
                    0
                }
            }
            Err(err) => {
                write_out_error(out_error, err.to_string());
                0
            }
        }
    }))
    .unwrap_or_else(|_| unsafe {
        write_out_error(out_error, "Panic in nexusts_telnet_connect");
        0
    })
}

#[no_mangle]
pub extern "C" fn nexusts_telnet_write(
    handle: i64,
    bytes_ptr: *const u8,
    bytes_len: usize,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let data = slice_from_c(bytes_ptr, bytes_len);
        get_telnet_session(handle)
            .map(|s| s.write_stdin(data))
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_telnet_read(handle: i64, out_eof: *mut bool) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        if let Some(session) = get_telnet_session(handle) {
            let drained = session.drain_output();
            let is_conn = session.is_connected();
            if !out_eof.is_null() {
                *out_eof = !is_conn && drained.is_empty();
            }
            if drained.is_empty() {
                NexusFfiBuffer::empty()
            } else {
                NexusFfiBuffer::from_vec(drained)
            }
        } else {
            if !out_eof.is_null() {
                *out_eof = true;
            }
            NexusFfiBuffer::empty()
        }
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_telnet_read"))
}

#[no_mangle]
pub extern "C" fn nexusts_telnet_resize(handle: i64, cols: u16, rows: u16) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        get_telnet_session(handle)
            .map(|s| s.resize_pty(cols.max(10), rows.max(4)))
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_telnet_is_connected(handle: i64) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        get_telnet_session(handle)
            .map(|s| s.is_connected())
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_telnet_last_error(handle: i64) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| {
        let err = get_telnet_session(handle)
            .map(|s| s.last_error())
            .unwrap_or_default();
        to_c_string(err)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_telnet_disconnect(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let removed = telnet_registry()
            .lock()
            .ok()
            .and_then(|mut reg| reg.remove(&handle));
        if let Some(session) = removed {
            session.disconnect();
        }
    }));
}

// ============================================================================
// 3. Async SSHv2 (russh), Bastion Chaining, Port Forwards & SFTP/SCP/FTP
// ============================================================================

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_connect_json(
    connect_spec_json_cstr: *const c_char,
    bastion_spec_json_cstr: *const c_char,
    open_pty: bool,
    out_error: *mut *mut c_char,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let spec_str = match opt_str_from_c(connect_spec_json_cstr) {
            Some(s) => s,
            None => {
                write_out_error(out_error, "Missing SSH connection spec JSON");
                return 0;
            }
        };
        let spec: SshConnectSpec = match serde_json::from_str(spec_str) {
            Ok(s) => s,
            Err(e) => {
                write_out_error(out_error, format!("Invalid SshConnectSpec JSON: {e}"));
                return 0;
            }
        };
        let bastion_spec: Option<SshConnectSpec> = opt_str_from_c(bastion_spec_json_cstr)
            .filter(|s| !s.trim().is_empty())
            .and_then(|s| serde_json::from_str(s).ok());

        let rt = tokio_runtime();
        let connect_res = rt.block_on(async move {
            let bastion_session = if let Some(bspec) = bastion_spec {
                Some(ActiveSshClientSession::connect_without_shell(bspec, None).await?)
            } else {
                None
            };

            let output_buf = Arc::new(Mutex::new(Vec::<u8>::with_capacity(16_384)));
            let eof_flag = Arc::new(AtomicBool::new(false));
            let mut aborts = Vec::new();

            if open_pty {
                let (session, mut pty_channel) =
                    ActiveSshClientSession::connect(spec, bastion_session.as_ref()).await?;

                let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<SshActorCommand>();
                let out_clone = Arc::clone(&output_buf);
                let eof_clone = Arc::clone(&eof_flag);

                let actor_task = tokio::spawn(async move {
                    // Keep bastion session alive for the lifetime of the tunneled session
                    let _keep_bastion_alive = bastion_session;
                    loop {
                        tokio::select! {
                            maybe_cmd = cmd_rx.recv() => {
                                match maybe_cmd {
                                    Some(SshActorCommand::SendInput(bytes)) => {
                                        if pty_channel.data(&bytes[..]).await.is_err() {
                                            eof_clone.store(true, Ordering::Release);
                                            break;
                                        }
                                    }
                                    Some(SshActorCommand::ResizePty { cols, rows }) => {
                                        let _ = pty_channel.window_change(cols, rows, 0, 0).await;
                                    }
                                    Some(SshActorCommand::Close) | None => {
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
                                        if let Ok(mut buf) = out_clone.lock() {
                                            buf.extend_from_slice(&data);
                                        }
                                    }
                                    Some(ChannelMsg::ExtendedData { data, .. }) => {
                                        if let Ok(mut buf) = out_clone.lock() {
                                            buf.extend_from_slice(&data);
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
                aborts.push(actor_task.abort_handle());

                Ok::<LiveSshRecord, nexusts_terminal::ssh_transport::TransportError>(LiveSshRecord {
                    session: Arc::new(session),
                    cmd_tx: Some(cmd_tx),
                    output_buf,
                    eof_flag,
                    abort_handles: Mutex::new(aborts),
                })
            } else {
                let session =
                    ActiveSshClientSession::connect_without_shell(spec, bastion_session.as_ref())
                        .await?;
                if let Some(b) = bastion_session {
                    let bastion_guard = tokio::spawn(async move {
                        let _keep = b;
                        std::future::pending::<()>().await;
                    });
                    aborts.push(bastion_guard.abort_handle());
                }
                Ok(LiveSshRecord {
                    session: Arc::new(session),
                    cmd_tx: None,
                    output_buf,
                    eof_flag,
                    abort_handles: Mutex::new(aborts),
                })
            }
        });

        match connect_res {
            Ok(record) => {
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                if let Ok(mut reg) = ssh_registry().lock() {
                    reg.insert(handle, record);
                    handle
                } else {
                    write_out_error(out_error, "SSH session registry poisoned");
                    0
                }
            }
            Err(err) => {
                write_out_error(out_error, err.to_string());
                0
            }
        }
    }))
    .unwrap_or_else(|_| unsafe {
        write_out_error(out_error, "Panic in nexusts_ffi_ssh_connect_json");
        0
    })
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_is_connected(session_handle: i64) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        ssh_registry()
            .lock()
            .ok()
            .and_then(|reg| {
                reg.get(&session_handle)
                    .map(|rec| !rec.eof_flag.load(Ordering::Acquire))
            })
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_send_input(
    session_handle: i64,
    bytes_ptr: *const u8,
    bytes_len: usize,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let payload = slice_from_c(bytes_ptr, bytes_len).to_vec();
        if payload.is_empty() {
            return true;
        }
        if let Ok(reg) = ssh_registry().lock() {
            if let Some(rec) = reg.get(&session_handle) {
                if let Some(tx) = &rec.cmd_tx {
                    return tx.send(SshActorCommand::SendInput(payload)).is_ok();
                }
            }
        }
        false
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_drain_output(
    session_handle: i64,
    out_eof: *mut bool,
) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        if let Ok(reg) = ssh_registry().lock() {
            if let Some(rec) = reg.get(&session_handle) {
                if !out_eof.is_null() {
                    *out_eof = rec.eof_flag.load(Ordering::Acquire);
                }
                if let Ok(mut buf) = rec.output_buf.lock() {
                    if buf.is_empty() {
                        return NexusFfiBuffer::empty();
                    }
                    let drained = std::mem::take(&mut *buf);
                    return NexusFfiBuffer::from_vec(drained);
                }
            }
        }
        if !out_eof.is_null() {
            *out_eof = true;
        }
        NexusFfiBuffer::empty()
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_ffi_ssh_drain_output"))
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_resize_pty(
    session_handle: i64,
    cols: u32,
    rows: u32,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        if let Ok(reg) = ssh_registry().lock() {
            if let Some(rec) = reg.get(&session_handle) {
                if let Some(tx) = &rec.cmd_tx {
                    return tx
                        .send(SshActorCommand::ResizePty {
                            cols: cols.max(10),
                            rows: rows.max(4),
                        })
                        .is_ok();
                }
            }
        }
        false
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_start_local_forward(
    session_handle: i64,
    bind_addr_cstr: *const c_char,
    bind_port: u16,
    target_host_cstr: *const c_char,
    target_port: u16,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let bind_addr = opt_str_from_c(bind_addr_cstr)
            .unwrap_or("127.0.0.1")
            .to_string();
        let target_host = opt_str_from_c(target_host_cstr)
            .unwrap_or("127.0.0.1")
            .to_string();

        let session = {
            let reg = match ssh_registry().lock() {
                Ok(r) => r,
                Err(_) => {
                    write_out_error(out_error, "SSH registry poisoned");
                    return false;
                }
            };
            match reg.get(&session_handle) {
                Some(rec) => Arc::clone(&rec.session),
                None => {
                    write_out_error(out_error, "Unknown SSH session handle");
                    return false;
                }
            }
        };

        let shared_ssh = session.shared_handle();
        let res = tokio_runtime().block_on(async move {
            ActiveSshClientSession::start_local_port_forward(
                shared_ssh,
                bind_addr,
                bind_port,
                target_host,
                target_port,
            )
            .await
        });

        match res {
            Ok(abort_handle) => {
                if let Ok(reg) = ssh_registry().lock() {
                    if let Some(rec) = reg.get(&session_handle) {
                        if let Ok(mut aborts) = rec.abort_handles.lock() {
                            aborts.push(abort_handle);
                        }
                    } else {
                        abort_handle.abort();
                    }
                }
                true
            }
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_start_socks5_forward(
    session_handle: i64,
    bind_addr_cstr: *const c_char,
    bind_port: u16,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let bind_addr = opt_str_from_c(bind_addr_cstr)
            .unwrap_or("127.0.0.1")
            .to_string();

        let session = {
            let reg = match ssh_registry().lock() {
                Ok(r) => r,
                Err(_) => {
                    write_out_error(out_error, "SSH registry poisoned");
                    return false;
                }
            };
            match reg.get(&session_handle) {
                Some(rec) => Arc::clone(&rec.session),
                None => {
                    write_out_error(out_error, "Unknown SSH session handle");
                    return false;
                }
            }
        };

        let shared_ssh = session.shared_handle();
        let res = tokio_runtime().block_on(async move {
            ActiveSshClientSession::start_dynamic_socks5_proxy(shared_ssh, bind_addr, bind_port)
                .await
        });

        match res {
            Ok(abort_handle) => {
                if let Ok(reg) = ssh_registry().lock() {
                    if let Some(rec) = reg.get(&session_handle) {
                        if let Ok(mut aborts) = rec.abort_handles.lock() {
                            aborts.push(abort_handle);
                        }
                    } else {
                        abort_handle.abort();
                    }
                }
                true
            }
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_disconnect(session_handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let removed = ssh_registry()
            .lock()
            .ok()
            .and_then(|mut r| r.remove(&session_handle));
        if let Some(rec) = removed {
            if let Some(tx) = rec.cmd_tx {
                let _ = tx.send(SshActorCommand::Close);
            }
            if let Ok(mut aborts) = rec.abort_handles.lock() {
                for handle in aborts.drain(..) {
                    handle.abort();
                }
            }
            let session = rec.session;
            tokio_runtime().spawn(async move {
                let _ = session.disconnect().await;
            });
        }
    }));
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_open_on_ssh(
    ssh_session_handle: i64,
    out_error: *mut *mut c_char,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let session = {
            let reg = match ssh_registry().lock() {
                Ok(r) => r,
                Err(_) => {
                    write_out_error(out_error, "SSH registry poisoned");
                    return 0;
                }
            };
            match reg.get(&ssh_session_handle) {
                Some(rec) => Arc::clone(&rec.session),
                None => {
                    write_out_error(out_error, "Unknown SSH session handle");
                    return 0;
                }
            }
        };

        let client_res = tokio_runtime().block_on(async move {
            let stream = session
                .open_sftp_subsystem_stream()
                .await
                .map_err(|e| e.to_string())?;
            NexusSftpClient::from_subsystem_stream(stream)
                .await
                .map_err(|e| e.to_string())
        });

        match client_res {
            Ok(sftp) => {
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                if let Ok(mut reg) = sftp_registry().lock() {
                    reg.insert(handle, Arc::new(sftp));
                    handle
                } else {
                    write_out_error(out_error, "SFTP registry poisoned");
                    0
                }
            }
            Err(err) => {
                write_out_error(out_error, err);
                0
            }
        }
    }))
    .unwrap_or(0)
}

fn get_sftp_client(handle: i64) -> Option<Arc<NexusSftpClient>> {
    sftp_registry()
        .lock()
        .ok()
        .and_then(|reg| reg.get(&handle).cloned())
}

#[no_mangle]
pub extern "C" fn nexusts_sftp_pwd(
    sftp_handle: i64,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(client) = get_sftp_client(sftp_handle) else {
            write_out_error(out_error, "Unknown SFTP handle");
            return ptr::null_mut();
        };
        match tokio_runtime().block_on(async move { client.pwd().await }) {
            Ok(cwd) => to_c_string(cwd),
            Err(e) => {
                write_out_error(out_error, e.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_pwd(
    sftp_handle: i64,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    nexusts_sftp_pwd(sftp_handle, out_error)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_list_dir_json(
    sftp_handle: i64,
    remote_dir_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let dir = opt_str_from_c(remote_dir_cstr).unwrap_or("").to_string();
        let Some(client) = get_sftp_client(sftp_handle) else {
            write_out_error(out_error, "Unknown SFTP handle");
            return ptr::null_mut();
        };
        match tokio_runtime().block_on(async move { client.list_directory(&dir).await }) {
            Ok(entries) => {
                let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".into());
                to_c_string(json)
            }
            Err(e) => {
                write_out_error(out_error, e.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_mkdir(
    sftp_handle: i64,
    remote_path_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_sftp_client(sftp_handle) else {
            write_out_error(out_error, "Unknown SFTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.create_directory(&path).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_remove(
    sftp_handle: i64,
    remote_path_cstr: *const c_char,
    is_dir: bool,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_sftp_client(sftp_handle) else {
            write_out_error(out_error, "Unknown SFTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.remove_entry(&path, is_dir).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_rename(
    sftp_handle: i64,
    old_path_cstr: *const c_char,
    new_path_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let old_p = opt_str_from_c(old_path_cstr).unwrap_or("").to_string();
        let new_p = opt_str_from_c(new_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_sftp_client(sftp_handle) else {
            write_out_error(out_error, "Unknown SFTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.rename(&old_p, &new_p).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_sftp_chmod(
    sftp_handle: i64,
    remote_path_cstr: *const c_char,
    mode_octal: u32,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_sftp_client(sftp_handle) else {
            write_out_error(out_error, "Unknown SFTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.chmod(&path, mode_octal).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_chmod(
    sftp_handle: i64,
    remote_path_cstr: *const c_char,
    mode_octal: u32,
    out_error: *mut *mut c_char,
) -> bool {
    nexusts_sftp_chmod(sftp_handle, remote_path_cstr, mode_octal, out_error)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_download(
    sftp_handle: i64,
    remote_path_cstr: *const c_char,
) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_sftp_client(sftp_handle) else {
            return NexusFfiBuffer::from_error("Unknown SFTP handle");
        };
        match tokio_runtime().block_on(async move { client.download_bytes(&path).await }) {
            Ok(bytes) => NexusFfiBuffer::from_vec(bytes),
            Err(e) => NexusFfiBuffer::from_error(e.to_string()),
        }
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_ffi_sftp_download"))
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_upload(
    sftp_handle: i64,
    remote_path_cstr: *const c_char,
    data_ptr: *const u8,
    data_len: usize,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let payload = slice_from_c(data_ptr, data_len).to_vec();
        let Some(client) = get_sftp_client(sftp_handle) else {
            write_out_error(out_error, "Unknown SFTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.upload_bytes(&path, &payload).await }) {
            Ok(_) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_sftp_close(sftp_handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let removed = sftp_registry()
            .lock()
            .ok()
            .and_then(|mut reg| reg.remove(&sftp_handle));
        if let Some(client) = removed {
            tokio_runtime().spawn(async move {
                let _ = client.close().await;
            });
        }
    }));
}

// --- SCP Client C-ABI (`NexusScpClient`) ---

fn get_scp_client(handle: i64) -> Option<Arc<NexusScpClient>> {
    scp_registry()
        .lock()
        .ok()
        .and_then(|reg| reg.get(&handle).cloned())
}

#[no_mangle]
pub extern "C" fn nexusts_scp_connect_json(
    connect_spec_json_cstr: *const c_char,
    bastion_spec_json_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(spec_str) = opt_str_from_c(connect_spec_json_cstr) else {
            write_out_error(out_error, "Missing SCP connection spec JSON");
            return 0;
        };
        let spec: SshConnectSpec = match serde_json::from_str(spec_str) {
            Ok(s) => s,
            Err(e) => {
                write_out_error(out_error, format!("Invalid SCP SshConnectSpec JSON: {e}"));
                return 0;
            }
        };
        let bastion_spec: Option<SshConnectSpec> = opt_str_from_c(bastion_spec_json_cstr)
            .filter(|s| !s.trim().is_empty())
            .and_then(|s| serde_json::from_str(s).ok());

        match tokio_runtime().block_on(async move { NexusScpClient::connect(spec, bastion_spec).await })
        {
            Ok(client) => {
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                if let Ok(mut reg) = scp_registry().lock() {
                    reg.insert(handle, Arc::new(client));
                    handle
                } else {
                    write_out_error(out_error, "SCP registry poisoned");
                    0
                }
            }
            Err(e) => {
                write_out_error(out_error, e.to_string());
                0
            }
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_scp_pwd(
    scp_handle: i64,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(client) = get_scp_client(scp_handle) else {
            write_out_error(out_error, "Unknown SCP handle");
            return ptr::null_mut();
        };
        match tokio_runtime().block_on(async move { client.pwd().await }) {
            Ok(cwd) => to_c_string(cwd),
            Err(e) => {
                write_out_error(out_error, e.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_scp_list_dir_json(
    scp_handle: i64,
    remote_dir_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let dir = opt_str_from_c(remote_dir_cstr).unwrap_or("").to_string();
        let Some(client) = get_scp_client(scp_handle) else {
            write_out_error(out_error, "Unknown SCP handle");
            return ptr::null_mut();
        };
        match tokio_runtime().block_on(async move { client.list_directory(&dir).await }) {
            Ok(entries) => {
                let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".into());
                to_c_string(json)
            }
            Err(e) => {
                write_out_error(out_error, e.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_scp_download(
    scp_handle: i64,
    remote_path_cstr: *const c_char,
) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_scp_client(scp_handle) else {
            return NexusFfiBuffer::from_error("Unknown SCP handle");
        };
        match tokio_runtime().block_on(async move { client.download_bytes(&path).await }) {
            Ok(bytes) => NexusFfiBuffer::from_vec(bytes),
            Err(e) => NexusFfiBuffer::from_error(e.to_string()),
        }
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_scp_download"))
}

#[no_mangle]
pub extern "C" fn nexusts_scp_upload(
    scp_handle: i64,
    remote_path_cstr: *const c_char,
    data_ptr: *const u8,
    data_len: usize,
    mode_octal: u32,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let payload = slice_from_c(data_ptr, data_len).to_vec();
        let mode = if mode_octal == 0 { 0o644 } else { mode_octal };
        let Some(client) = get_scp_client(scp_handle) else {
            write_out_error(out_error, "Unknown SCP handle");
            return false;
        };
        match tokio_runtime()
            .block_on(async move { client.upload_bytes(&path, &payload, mode).await })
        {
            Ok(_) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_scp_mkdir(
    scp_handle: i64,
    remote_path_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_scp_client(scp_handle) else {
            write_out_error(out_error, "Unknown SCP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.create_directory(&path).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_scp_remove(
    scp_handle: i64,
    remote_path_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_scp_client(scp_handle) else {
            write_out_error(out_error, "Unknown SCP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.remove_entry(&path).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_scp_rename(
    scp_handle: i64,
    old_path_cstr: *const c_char,
    new_path_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let old_p = opt_str_from_c(old_path_cstr).unwrap_or("").to_string();
        let new_p = opt_str_from_c(new_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_scp_client(scp_handle) else {
            write_out_error(out_error, "Unknown SCP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.rename(&old_p, &new_p).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_scp_chmod(
    scp_handle: i64,
    remote_path_cstr: *const c_char,
    mode_octal: u32,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_scp_client(scp_handle) else {
            write_out_error(out_error, "Unknown SCP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.chmod(&path, mode_octal).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_scp_disconnect(scp_handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let removed = scp_registry()
            .lock()
            .ok()
            .and_then(|mut reg| reg.remove(&scp_handle));
        if let Some(client) = removed {
            tokio_runtime().spawn(async move {
                let _ = client.disconnect().await;
            });
        }
    }));
}

// --- FTP Client C-ABI (`NexusFtpClient`) ---

fn get_ftp_client(handle: i64) -> Option<Arc<NexusFtpClient>> {
    ftp_registry()
        .lock()
        .ok()
        .and_then(|reg| reg.get(&handle).cloned())
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_connect(
    host_cstr: *const c_char,
    port: u16,
    username_cstr: *const c_char,
    password_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(host) = opt_str_from_c(host_cstr).map(str::trim).filter(|s| !s.is_empty()) else {
            write_out_error(out_error, "Missing FTP host");
            return 0;
        };
        let host = host.to_string();
        let user = opt_str_from_c(username_cstr)
            .filter(|s| !s.trim().is_empty())
            .unwrap_or("anonymous")
            .to_string();
        let pass = opt_str_from_c(password_cstr).unwrap_or("").to_string();

        match tokio_runtime()
            .block_on(async move { NexusFtpClient::connect(&host, port, &user, &pass).await })
        {
            Ok(client) => {
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                if let Ok(mut reg) = ftp_registry().lock() {
                    reg.insert(handle, Arc::new(client));
                    handle
                } else {
                    write_out_error(out_error, "FTP registry poisoned");
                    0
                }
            }
            Err(e) => {
                write_out_error(out_error, e.to_string());
                0
            }
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_pwd(
    ftp_handle: i64,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(client) = get_ftp_client(ftp_handle) else {
            write_out_error(out_error, "Unknown FTP handle");
            return ptr::null_mut();
        };
        match tokio_runtime().block_on(async move { client.pwd().await }) {
            Ok(cwd) => to_c_string(cwd),
            Err(e) => {
                write_out_error(out_error, e.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_cwd(
    ftp_handle: i64,
    remote_dir_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let dir = opt_str_from_c(remote_dir_cstr).unwrap_or("/").to_string();
        let Some(client) = get_ftp_client(ftp_handle) else {
            write_out_error(out_error, "Unknown FTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.cwd(&dir).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_list_dir_json(
    ftp_handle: i64,
    remote_dir_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let dir = opt_str_from_c(remote_dir_cstr).unwrap_or("").to_string();
        let Some(client) = get_ftp_client(ftp_handle) else {
            write_out_error(out_error, "Unknown FTP handle");
            return ptr::null_mut();
        };
        match tokio_runtime().block_on(async move { client.list_directory(&dir).await }) {
            Ok(entries) => {
                let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".into());
                to_c_string(json)
            }
            Err(e) => {
                write_out_error(out_error, e.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_download(
    ftp_handle: i64,
    remote_path_cstr: *const c_char,
) -> NexusFfiBuffer {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_ftp_client(ftp_handle) else {
            return NexusFfiBuffer::from_error("Unknown FTP handle");
        };
        match tokio_runtime().block_on(async move { client.download_bytes(&path).await }) {
            Ok(bytes) => NexusFfiBuffer::from_vec(bytes),
            Err(e) => NexusFfiBuffer::from_error(e.to_string()),
        }
    }))
    .unwrap_or_else(|_| NexusFfiBuffer::from_error("Panic in nexusts_ftp_download"))
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_upload(
    ftp_handle: i64,
    remote_path_cstr: *const c_char,
    data_ptr: *const u8,
    data_len: usize,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let payload = slice_from_c(data_ptr, data_len).to_vec();
        let Some(client) = get_ftp_client(ftp_handle) else {
            write_out_error(out_error, "Unknown FTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.upload_bytes(&path, &payload).await }) {
            Ok(_) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_mkdir(
    ftp_handle: i64,
    remote_path_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_ftp_client(ftp_handle) else {
            write_out_error(out_error, "Unknown FTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.create_directory(&path).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_remove(
    ftp_handle: i64,
    remote_path_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_ftp_client(ftp_handle) else {
            write_out_error(out_error, "Unknown FTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.remove_entry(&path).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_rename(
    ftp_handle: i64,
    old_path_cstr: *const c_char,
    new_path_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let old_p = opt_str_from_c(old_path_cstr).unwrap_or("").to_string();
        let new_p = opt_str_from_c(new_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_ftp_client(ftp_handle) else {
            write_out_error(out_error, "Unknown FTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.rename(&old_p, &new_p).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_chmod(
    ftp_handle: i64,
    remote_path_cstr: *const c_char,
    mode_octal: u32,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let path = opt_str_from_c(remote_path_cstr).unwrap_or("").to_string();
        let Some(client) = get_ftp_client(ftp_handle) else {
            write_out_error(out_error, "Unknown FTP handle");
            return false;
        };
        match tokio_runtime().block_on(async move { client.chmod(&path, mode_octal).await }) {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                false
            }
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ftp_disconnect(ftp_handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let removed = ftp_registry()
            .lock()
            .ok()
            .and_then(|mut reg| reg.remove(&ftp_handle));
        if let Some(client) = removed {
            tokio_runtime().spawn(async move {
                let _ = client.disconnect().await;
            });
        }
    }));
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_parse_ftp_mlsd_json(
    parent_dir_cstr: *const c_char,
    mlsd_cstr: *const c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let parent = opt_str_from_c(parent_dir_cstr).unwrap_or("/");
        let mlsd = opt_str_from_c(mlsd_cstr).unwrap_or("");
        let entries = parse_ftp_mlsd_listing(parent, mlsd);
        let json = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".into());
        to_c_string(json)
    }))
    .unwrap_or(ptr::null_mut())
}

// ============================================================================
// 4. RFB / VNC / Apple Screen Sharing (ARD Type 30) Framebuffer & Live Client
// ============================================================================

#[no_mangle]
pub extern "C" fn nexusts_ffi_vnc_create(width: u32, height: u32) -> i64 {
    catch_unwind(AssertUnwindSafe(|| {
        let w = width.clamp(1, 8192);
        let h = height.clamp(1, 8192);
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut reg) = vnc_registry().lock() {
            reg.insert(handle, RfbFramebuffer::new(w, h));
            handle
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vnc_resize(handle: i64, width: u32, height: u32) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let w = width.clamp(1, 8192);
        let h = height.clamp(1, 8192);
        if let Ok(mut reg) = vnc_registry().lock() {
            if let Some(fb) = reg.get_mut(&handle) {
                fb.resize(w, h);
            }
        }
    }));
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vnc_apply_rect(
    handle: i64,
    encoding: i32,
    x: u16,
    y: u16,
    w: u16,
    h: u16,
    payload_ptr: *const u8,
    payload_len: usize,
    out_error: *mut *mut c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let payload = slice_from_c(payload_ptr, payload_len);
        if let Ok(mut reg) = vnc_registry().lock() {
            if let Some(fb) = reg.get_mut(&handle) {
                let res = match encoding {
                    RFB_PSEUDO_DESKTOP_SIZE => {
                        fb.resize(u32::from(w.max(1)), u32::from(h.max(1)));
                        Ok(())
                    }
                    RFB_ENCODING_RAW => fb.apply_raw_rect(x, y, w, h, payload),
                    RFB_ENCODING_COPYRECT if payload.len() >= 4 => {
                        let sx = u16::from_be_bytes([payload[0], payload[1]]);
                        let sy = u16::from_be_bytes([payload[2], payload[3]]);
                        fb.apply_copy_rect(x, y, w, h, sx, sy)
                    }
                    RFB_ENCODING_RRE => fb.apply_rre_rect(x, y, w, h, payload),
                    RFB_ENCODING_HEXTILE => apply_hextile_rect(fb, x, y, w, h, payload),
                    RFB_ENCODING_TIGHT => fb.apply_tight_rect(x, y, w, h, payload),
                    RFB_ENCODING_ZRLE => fb.apply_zrle_rect(x, y, w, h, payload),
                    _ => Ok(()),
                };
                match res {
                    Ok(()) => return true,
                    Err(e) => {
                        write_out_error(out_error, e.to_string());
                        return false;
                    }
                }
            }
        }
        write_out_error(out_error, "Unknown VNC framebuffer handle");
        false
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vnc_copy_rgba(
    handle: i64,
    dst_ptr: *mut u8,
    dst_cap: usize,
) -> usize {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        if dst_ptr.is_null() || dst_cap == 0 {
            return 0;
        }
        if let Ok(reg) = vnc_registry().lock() {
            if let Some(fb) = reg.get(&handle) {
                let dst = slice::from_raw_parts_mut(dst_ptr, dst_cap);
                return fb.copy_rgba(dst);
            }
        }
        0
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vnc_copy_bgra(
    handle: i64,
    dst_ptr: *mut u8,
    dst_cap: usize,
) -> usize {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        if dst_ptr.is_null() || dst_cap == 0 {
            return 0;
        }
        if let Ok(reg) = vnc_registry().lock() {
            if let Some(fb) = reg.get(&handle) {
                let dst = slice::from_raw_parts_mut(dst_ptr, dst_cap);
                return fb.copy_bgra(dst);
            }
        }
        0
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_vnc_destroy(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut reg) = vnc_registry().lock() {
            reg.remove(&handle);
        }
    }));
}

fn get_vnc_session(handle: i64) -> Option<Arc<ActiveVncClient>> {
    vnc_session_registry()
        .lock()
        .ok()
        .and_then(|reg| reg.get(&handle).cloned())
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_connect(
    host_cstr: *const c_char,
    port: u16,
    username_cstr: *const c_char,
    password_cstr: *const c_char,
    shared_session: bool,
    view_only: bool,
    out_error: *mut *mut c_char,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(host) = opt_str_from_c(host_cstr).map(str::trim).filter(|s| !s.is_empty()) else {
            write_out_error(out_error, "Missing VNC host");
            return 0;
        };
        let host = host.to_string();
        let username = opt_str_from_c(username_cstr).unwrap_or("").to_string();
        let password = opt_str_from_c(password_cstr).unwrap_or("").to_string();

        let connect_res = tokio_runtime().block_on(async move {
            ActiveVncClient::connect(&host, port, &username, &password, shared_session, view_only)
                .await
        });

        match connect_res {
            Ok(client) => {
                let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
                if let Ok(mut reg) = vnc_session_registry().lock() {
                    reg.insert(handle, Arc::new(client));
                    handle
                } else {
                    write_out_error(out_error, "VNC session registry poisoned");
                    0
                }
            }
            Err(e) => {
                write_out_error(out_error, e.to_string());
                0
            }
        }
    }))
    .unwrap_or_else(|_| unsafe {
        write_out_error(out_error, "Panic in nexusts_vnc_session_connect");
        0
    })
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_is_connected(handle: i64) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        get_vnc_session(handle)
            .map(|c| c.is_connected())
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_last_error(handle: i64) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| {
        let err = get_vnc_session(handle)
            .map(|c| c.last_error())
            .unwrap_or_default();
        to_c_string(err)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_desktop_name(handle: i64) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| {
        let name = get_vnc_session(handle)
            .map(|c| c.desktop_name().to_string())
            .unwrap_or_default();
        to_c_string(name)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_frame_sequence(handle: i64) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        get_vnc_session(handle)
            .map(|c| c.frame_sequence())
            .unwrap_or(0)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_get_dimensions(
    handle: i64,
    out_width: *mut u32,
    out_height: *mut u32,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        if let Some(client) = get_vnc_session(handle) {
            let (w, h) = client.dimensions();
            if !out_width.is_null() {
                *out_width = w;
            }
            if !out_height.is_null() {
                *out_height = h;
            }
            true
        } else {
            false
        }
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_copy_frame_bgra(
    handle: i64,
    dst_ptr: *mut u8,
    dst_cap: usize,
) -> usize {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        if dst_ptr.is_null() || dst_cap == 0 {
            return 0;
        }
        if let Some(client) = get_vnc_session(handle) {
            let dst = slice::from_raw_parts_mut(dst_ptr, dst_cap);
            client.copy_frame_bgra(dst)
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_copy_frame_rgba(
    handle: i64,
    dst_ptr: *mut u8,
    dst_cap: usize,
) -> usize {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        if dst_ptr.is_null() || dst_cap == 0 {
            return 0;
        }
        if let Some(client) = get_vnc_session(handle) {
            let dst = slice::from_raw_parts_mut(dst_ptr, dst_cap);
            client.copy_frame_rgba(dst)
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_send_pointer(
    handle: i64,
    button_mask: u8,
    x: u16,
    y: u16,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        get_vnc_session(handle)
            .map(|c| c.send_pointer(button_mask, x, y))
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_send_key(handle: i64, down: bool, keysym: u32) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        get_vnc_session(handle)
            .map(|c| c.send_key(down, keysym))
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_send_clipboard(
    handle: i64,
    text_cstr: *const c_char,
) -> bool {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let text = opt_str_from_c(text_cstr).unwrap_or("");
        get_vnc_session(handle)
            .map(|c| c.send_clipboard(text))
            .unwrap_or(false)
    }))
    .unwrap_or(false)
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_pull_clipboard(handle: i64) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| {
        get_vnc_session(handle)
            .and_then(|c| c.pull_clipboard())
            .map(to_c_string)
            .unwrap_or(ptr::null_mut())
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_vnc_session_disconnect(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let removed = vnc_session_registry()
            .lock()
            .ok()
            .and_then(|mut reg| reg.remove(&handle));
        if let Some(client) = removed {
            client.disconnect();
        }
    }));
}

// ============================================================================
// 5. Integrated Network Diagnostics (WoL, Port Probe, Scanner, Traceroute, Banner)
// ============================================================================

#[no_mangle]
pub extern "C" fn nexusts_ffi_net_wol(
    mac_cstr: *const c_char,
    broadcast_ip_cstr: *const c_char,
    udp_port: u16,
    secure_on_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let mac = opt_str_from_c(mac_cstr).unwrap_or("");
        let ip = opt_str_from_c(broadcast_ip_cstr).unwrap_or("255.255.255.255");
        let sec = opt_str_from_c(secure_on_cstr).filter(|s| !s.trim().is_empty());
        match send_wake_on_lan(mac, ip, if udp_port == 0 { 9 } else { udp_port }, sec) {
            Ok(sent) => sent as i32,
            Err(e) => {
                write_out_error(out_error, e.to_string());
                -1
            }
        }
    }))
    .unwrap_or(-99)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_net_probe_tcp_json(
    host_cstr: *const c_char,
    port: u16,
    timeout_ms: u64,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let host = opt_str_from_c(host_cstr).unwrap_or("");
        let hit = if port == 0 || host.is_empty() {
            None
        } else {
            probe_tcp_port(host, port, timeout_ms.max(25))
        };
        let json = serde_json::to_string(&hit).unwrap_or_else(|_| "null".into());
        to_c_string(json)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_net_scan_ports_json(
    host_cstr: *const c_char,
    start_port: u16,
    end_port: u16,
    timeout_ms: u64,
    concurrency: u32,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let host = opt_str_from_c(host_cstr).unwrap_or("").trim().to_string();
        if host.is_empty() || (start_port == 0 && end_port == 0) {
            return to_c_string("[]");
        }
        let hits = tokio_runtime().block_on(async move {
            scan_tcp_ports(
                &host,
                start_port.max(1),
                end_port.max(1),
                timeout_ms.max(20),
                (concurrency as usize).clamp(1, 512),
            )
            .await
        });
        let json = serde_json::to_string(&hits).unwrap_or_else(|_| "[]".into());
        to_c_string(json)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_net_scan_ports_json(
    host_cstr: *const c_char,
    start_port: u16,
    end_port: u16,
    timeout_ms: u64,
    concurrency: u32,
) -> *mut c_char {
    nexusts_net_scan_ports_json(host_cstr, start_port, end_port, timeout_ms, concurrency)
}

#[no_mangle]
pub extern "C" fn nexusts_net_tcp_ping_ms(
    host_cstr: *const c_char,
    port: u16,
    timeout_ms: u64,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let host = opt_str_from_c(host_cstr).unwrap_or("").trim().to_string();
        if host.is_empty() {
            return -1;
        }
        tokio_runtime()
            .block_on(async move { tcp_latency_ping(&host, port, timeout_ms.max(20)).await })
            .map(|ms| ms as i64)
            .unwrap_or(-1)
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_net_traceroute_json(
    host_cstr: *const c_char,
    probe_port: u16,
    max_hops: u8,
    timeout_ms: u64,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let host = opt_str_from_c(host_cstr).unwrap_or("");
        let hops = trace_route_hops(
            host,
            if probe_port == 0 { 443 } else { probe_port },
            max_hops.clamp(1, 32),
            timeout_ms.max(50),
        )
        .unwrap_or_default();
        let json = serde_json::to_string(&hops).unwrap_or_else(|_| "[]".into());
        to_c_string(json)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_net_grab_banner(
    host_cstr: *const c_char,
    port: u16,
    timeout_ms: u64,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let host = opt_str_from_c(host_cstr).unwrap_or("");
        let banner = if port == 0 || host.is_empty() {
            String::new()
        } else {
            grab_tcp_service_banner(host, port, timeout_ms.max(50)).unwrap_or_default()
        };
        to_c_string(banner)
    }))
    .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_winrm_wsman_post(
    host_cstr: *const c_char,
    port: u16,
    path_cstr: *const c_char,
    username_cstr: *const c_char,
    password_cstr: *const c_char,
    domain_cstr: *const c_char,
    soap_xml_cstr: *const c_char,
    timeout_ms: u64,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let host = opt_str_from_c(host_cstr).unwrap_or("").trim().to_string();
        if host.is_empty() {
            write_out_error(out_error, "WinRM host is empty");
            return ptr::null_mut();
        }
        let path = opt_str_from_c(path_cstr).unwrap_or("/wsman").to_string();
        let username = opt_str_from_c(username_cstr).unwrap_or("").to_string();
        let password = opt_str_from_c(password_cstr).unwrap_or("").to_string();
        let domain = opt_str_from_c(domain_cstr).unwrap_or("").to_string();
        let soap_xml = opt_str_from_c(soap_xml_cstr).unwrap_or("").to_string();

        let res = tokio_runtime().block_on(async move {
            winrm_http_wsman_post(
                &host,
                port,
                &path,
                &username,
                &password,
                &domain,
                &soap_xml,
                timeout_ms,
            )
            .await
        });

        match res {
            Ok(reply) => to_c_string(reply.soap_xml),
            Err(err) => {
                write_out_error(out_error, err.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or_else(|_| unsafe {
        write_out_error(out_error, "Panic in nexusts_ffi_winrm_wsman_post");
        ptr::null_mut()
    })
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ntlm_build_negotiate() -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| to_c_string(ntlm_build_negotiate_b64())))
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ntlm_build_authenticate(
    challenge_b64_cstr: *const c_char,
    username_cstr: *const c_char,
    password_cstr: *const c_char,
    domain_cstr: *const c_char,
    spn_host_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let challenge_b64 = opt_str_from_c(challenge_b64_cstr).unwrap_or("");
        let username = opt_str_from_c(username_cstr).unwrap_or("");
        let password = opt_str_from_c(password_cstr).unwrap_or("");
        let domain = opt_str_from_c(domain_cstr).unwrap_or("");
        let spn_host = opt_str_from_c(spn_host_cstr).unwrap_or("");

        match ntlm_build_authenticate_b64(challenge_b64, username, password, domain, spn_host) {
            Ok(b64) => to_c_string(b64),
            Err(err) => {
                write_out_error(out_error, err.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or_else(|_| unsafe {
        write_out_error(out_error, "Panic in nexusts_ffi_ntlm_build_authenticate");
        ptr::null_mut()
    })
}

#[no_mangle]
pub extern "C" fn nexusts_ffi_ssh_exec_command(
    connect_spec_json_cstr: *const c_char,
    command_cstr: *const c_char,
    timeout_secs: u64,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let Some(spec_json) = opt_str_from_c(connect_spec_json_cstr) else {
            write_out_error(out_error, "Missing connect_spec_json");
            return ptr::null_mut();
        };
        let spec: SshConnectSpec = match serde_json::from_str(spec_json) {
            Ok(s) => s,
            Err(e) => {
                write_out_error(out_error, format!("Invalid SshConnectSpec JSON: {e}"));
                return ptr::null_mut();
            }
        };
        let command = opt_str_from_c(command_cstr).unwrap_or("").to_string();
        if command.trim().is_empty() {
            write_out_error(out_error, "Empty SSH exec command");
            return ptr::null_mut();
        }

        let res = tokio_runtime().block_on(async move {
            ActiveSshClientSession::exec_oneshot_command(spec, &command, timeout_secs).await
        });

        match res {
            Ok(stdout) => to_c_string(stdout),
            Err(err) => {
                write_out_error(out_error, err.to_string());
                ptr::null_mut()
            }
        }
    }))
    .unwrap_or_else(|_| unsafe {
        write_out_error(out_error, "Panic in nexusts_ffi_ssh_exec_command");
        ptr::null_mut()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ffi_vault_seal_open_and_ed25519_roundtrip() {
        let plain = br#"{"documentId":"00000000-0000-0000-0000-000000000001","name":"macOS Vault"}"#;
        let pass = b"NexusTS-macOS-ZeroKnowledge-Secret";

        let sealed = nexusts_ffi_seal_vault(
            plain.as_ptr(),
            plain.len(),
            pass.as_ptr(),
            pass.len(),
            7,
            8_192,
            1,
            1,
        );
        assert!(sealed.error_msg.is_null());
        assert!(sealed.len > 86);

        let mut rev: u64 = 0;
        let opened = nexusts_ffi_open_vault(
            sealed.data,
            sealed.len,
            pass.as_ptr(),
            pass.len(),
            &mut rev,
        );
        assert!(opened.error_msg.is_null());
        assert_eq!(rev, 7);
        let opened_slice = unsafe { slice::from_raw_parts(opened.data, opened.len) };
        assert_eq!(opened_slice, plain);

        nexusts_ffi_free_buffer(sealed);
        nexusts_ffi_free_buffer(opened);

        let comment = CString::new("macos@nexusts.app").unwrap();
        let kp_json_ptr = nexusts_ffi_generate_ed25519_keypair_json(comment.as_ptr());
        assert!(!kp_json_ptr.is_null());
        let kp_json = unsafe { CStr::from_ptr(kp_json_ptr).to_str().unwrap().to_owned() };
        nexusts_ffi_free_cstr(kp_json_ptr);
        assert!(kp_json.contains("ssh-ed25519 "));
        assert!(kp_json.contains("BEGIN OPENSSH PRIVATE KEY"));
    }

    #[test]
    fn ffi_vt_decckm_and_vnc_framebuffer_rgba_bgra_lifecycle() {
        let vt = nexusts_ffi_vt_create(80, 24, 1000);
        assert!(vt > 0);
        assert!(!nexusts_vt_application_cursor_keys(vt));

        let sample = b"\x1b[?1h\x1b[38;2;91;155;255mNexusTS macOS\x1b[0m\r\n";
        let snap_ptr = nexusts_ffi_vt_feed_and_snapshot_json(vt, sample.as_ptr(), sample.len());
        assert!(!snap_ptr.is_null());
        let snap_str = unsafe { CStr::from_ptr(snap_ptr).to_str().unwrap().to_owned() };
        nexusts_ffi_free_cstr(snap_ptr);
        assert!(snap_str.contains("NexusTS macOS"));
        assert!(nexusts_vt_application_cursor_keys(vt));

        let reset_decckm = b"\x1b[?1l";
        let snap2 =
            nexusts_ffi_vt_feed_and_snapshot_json(vt, reset_decckm.as_ptr(), reset_decckm.len());
        nexusts_ffi_free_cstr(snap2);
        assert!(!nexusts_vt_application_cursor_keys(vt));
        nexusts_ffi_vt_destroy(vt);

        let vnc = nexusts_ffi_vnc_create(2, 2);
        assert!(vnc > 0);
        let raw_pixels = [
            255u8, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255,
        ];
        let mut err: *mut c_char = ptr::null_mut();
        assert!(nexusts_ffi_vnc_apply_rect(
            vnc,
            RFB_ENCODING_RAW,
            0,
            0,
            2,
            2,
            raw_pixels.as_ptr(),
            raw_pixels.len(),
            &mut err,
        ));
        assert!(err.is_null());
        let mut out_rgba = [0u8; 16];
        let mut out_bgra = [0u8; 16];
        assert_eq!(
            nexusts_ffi_vnc_copy_rgba(vnc, out_rgba.as_mut_ptr(), out_rgba.len()),
            16
        );
        assert_eq!(out_rgba, raw_pixels);
        assert_eq!(
            nexusts_ffi_vnc_copy_bgra(vnc, out_bgra.as_mut_ptr(), out_bgra.len()),
            16
        );
        assert_eq!(&out_bgra[0..4], &[0, 0, 255, 255]);
        nexusts_ffi_vnc_destroy(vnc);
    }

    #[test]
    fn ffi_live_telnet_and_port_scanner_roundtrip() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            if let Ok((mut s, _)) = listener.accept() {
                use std::io::{Read, Write};
                let _ = s.write_all(b"Welcome Telnet\r\n");
                let mut buf = [0u8; 64];
                if let Ok(n) = s.read(&mut buf) {
                    let _ = s.write_all(&buf[..n]);
                }
            }
        });

        let host_c = CString::new("127.0.0.1").unwrap();
        let term_c = CString::new("xterm-256color").unwrap();
        let mut err: *mut c_char = ptr::null_mut();
        let tn = nexusts_telnet_connect(host_c.as_ptr(), port, 100, 30, term_c.as_ptr(), &mut err);
        assert!(tn > 0, "telnet connect failed");
        assert!(err.is_null());
        assert!(nexusts_telnet_is_connected(tn));

        assert!(nexusts_telnet_write(tn, b"hello\r\n".as_ptr(), 7));
        let mut collected = Vec::new();
        for _ in 0..40 {
            let mut eof = false;
            let buf = nexusts_telnet_read(tn, &mut eof);
            if !buf.data.is_null() && buf.len > 0 {
                collected.extend_from_slice(unsafe { slice::from_raw_parts(buf.data, buf.len) });
            }
            nexusts_ffi_free_buffer(buf);
            if String::from_utf8_lossy(&collected).contains("hello") {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(15));
        }
        let text = String::from_utf8_lossy(&collected);
        assert!(text.contains("Welcome Telnet"));
        assert!(text.contains("hello"));
        nexusts_telnet_disconnect(tn);
    }
}
