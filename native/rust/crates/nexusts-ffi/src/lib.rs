//! Unified C-ABI FFI Bridge (`libnexusts_ffi.a` / `libnexusts_ffi.dylib`) exposing
//! Zero-Knowledge `.nxts` v2 Vault Crypto, `xterm-256color` VT Terminal Engine,
//! Async `russh` SSHv2 + Bastion + Port Forwards + SFTP v3, RFB VNC Framebuffer,
//! and Network Diagnostics natively to Swift 6 / macOS.

use nexusts_crypto::{
    clear_ssh_agent, generate_ed25519_ssh_keypair, open_nxts_vault, seal_nxts_vault,
    sign_ssh_agent_challenge, Argon2idProfile,
};
use nexusts_netutils::{
    grab_tcp_service_banner, probe_tcp_port, send_wake_on_lan, trace_route_hops,
};
use nexusts_terminal::ssh_transport::{
    ActiveSshClientSession, SshConnectSpec, TelnetNegotiator,
};
use nexusts_terminal::VtTerminalBuffer;
use nexusts_transfer::wire_clients::{parse_ftp_mlsd_listing, NexusSftpClient};
use nexusts_vnc::rfb_client::apply_hextile_rect;
use nexusts_vnc::{
    RfbFramebuffer, RFB_ENCODING_COPYRECT, RFB_ENCODING_HEXTILE, RFB_ENCODING_RAW,
    RFB_ENCODING_TIGHT, RFB_ENCODING_ZRLE,
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
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::runtime::{Builder, Runtime};
use tokio::sync::mpsc;

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
}

fn vt_registry() -> &'static Mutex<HashMap<i64, VtTerminalBuffer>> {
    static REG: OnceLock<Mutex<HashMap<i64, VtTerminalBuffer>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn ssh_registry() -> &'static Mutex<HashMap<i64, LiveSshRecord>> {
    static REG: OnceLock<Mutex<HashMap<i64, LiveSshRecord>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn sftp_registry() -> &'static Mutex<HashMap<i64, Arc<NexusSftpClient>>> {
    static REG: OnceLock<Mutex<HashMap<i64, Arc<NexusSftpClient>>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn vnc_registry() -> &'static Mutex<HashMap<i64, RfbFramebuffer>> {
    static REG: OnceLock<Mutex<HashMap<i64, RfbFramebuffer>>> = OnceLock::new();
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
// 2. xterm-256color / 24-bit TrueColor VT Terminal Engine & Telnet RFC 854
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

// ============================================================================
// 3. Async SSHv2 (russh), Bastion Chaining, Port Forwards & SFTP v3
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

            if open_pty {
                let (session, mut pty_channel) =
                    ActiveSshClientSession::connect(spec, bastion_session.as_ref()).await?;
                let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<SshActorCommand>();
                let out_clone = Arc::clone(&output_buf);
                let eof_clone = Arc::clone(&eof_flag);

                tokio::spawn(async move {
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

                Ok::<LiveSshRecord, nexusts_terminal::ssh_transport::TransportError>(LiveSshRecord {
                    session: Arc::new(session),
                    cmd_tx: Some(cmd_tx),
                    output_buf,
                    eof_flag,
                })
            } else {
                let session =
                    ActiveSshClientSession::connect_without_shell(spec, bastion_session.as_ref())
                        .await?;
                if let Some(b) = bastion_session {
                    tokio::spawn(async move {
                        let _keep = b;
                        std::future::pending::<()>().await;
                    });
                }
                Ok(LiveSshRecord {
                    session: Arc::new(session),
                    cmd_tx: None,
                    output_buf,
                    eof_flag,
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

        let shared_ssh = {
            let reg = match ssh_registry().lock() {
                Ok(r) => r,
                Err(_) => {
                    write_out_error(out_error, "SSH registry poisoned");
                    return false;
                }
            };
            match reg.get(&session_handle) {
                Some(rec) => rec.session.shared_handle(),
                None => {
                    write_out_error(out_error, "Unknown SSH session handle");
                    return false;
                }
            }
        };

        let res = tokio_runtime().block_on(async move {
            ActiveSshClientSession::spawn_local_port_forward(
                shared_ssh,
                bind_addr,
                bind_port,
                target_host,
                target_port,
            )
            .await
        });

        match res {
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

        let shared_ssh = {
            let reg = match ssh_registry().lock() {
                Ok(r) => r,
                Err(_) => {
                    write_out_error(out_error, "SSH registry poisoned");
                    return false;
                }
            };
            match reg.get(&session_handle) {
                Some(rec) => rec.session.shared_handle(),
                None => {
                    write_out_error(out_error, "Unknown SSH session handle");
                    return false;
                }
            }
        };

        let res: Result<(), String> = tokio_runtime().block_on(async move {
            let listener = TcpListener::bind((bind_addr.as_str(), bind_port))
                .await
                .map_err(|e| e.to_string())?;
            tokio::spawn(async move {
                while let Ok((mut client_sock, peer)) = listener.accept().await {
                    let h = Arc::clone(&shared_ssh);
                    tokio::spawn(async move {
                        // RFC 1928 SOCKS5 greeting
                        let mut hdr = [0u8; 2];
                        if client_sock.read_exact(&mut hdr).await.is_err() || hdr[0] != 0x05 {
                            return;
                        }
                        let mut methods = vec![0u8; hdr[1] as usize];
                        if client_sock.read_exact(&mut methods).await.is_err() {
                            return;
                        }
                        if client_sock.write_all(&[0x05, 0x00]).await.is_err() {
                            return;
                        }
                        // Request header [VER=5, CMD=1, RSV=0, ATYP]
                        let mut req = [0u8; 4];
                        if client_sock.read_exact(&mut req).await.is_err() || req[1] != 0x01 {
                            return;
                        }
                        let dest_host = match req[3] {
                            0x01 => {
                                let mut ip = [0u8; 4];
                                if client_sock.read_exact(&mut ip).await.is_err() {
                                    return;
                                }
                                std::net::Ipv4Addr::from(ip).to_string()
                            }
                            0x03 => {
                                let mut len_b = [0u8; 1];
                                if client_sock.read_exact(&mut len_b).await.is_err() {
                                    return;
                                }
                                let mut dom = vec![0u8; len_b[0] as usize];
                                if client_sock.read_exact(&mut dom).await.is_err() {
                                    return;
                                }
                                String::from_utf8_lossy(&dom).to_string()
                            }
                            0x04 => {
                                let mut ip6 = [0u8; 16];
                                if client_sock.read_exact(&mut ip6).await.is_err() {
                                    return;
                                }
                                std::net::Ipv6Addr::from(ip6).to_string()
                            }
                            _ => return,
                        };
                        let mut port_b = [0u8; 2];
                        if client_sock.read_exact(&mut port_b).await.is_err() {
                            return;
                        }
                        let dest_port = u16::from_be_bytes(port_b);

                        if let Ok(ch) = h
                            .channel_open_direct_tcpip(
                                &dest_host,
                                u32::from(dest_port),
                                &peer.ip().to_string(),
                                u32::from(peer.port()),
                            )
                            .await
                        {
                            let reply = [0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
                            if client_sock.write_all(&reply).await.is_ok() {
                                let mut ssh_stream = ch.into_stream();
                                let _ = tokio::io::copy_bidirectional(
                                    &mut client_sock,
                                    &mut ssh_stream,
                                )
                                .await;
                            }
                        } else {
                            let _ = client_sock
                                .write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                                .await;
                        }
                    });
                }
            });
            Ok(())
        });

        match res {
            Ok(()) => true,
            Err(e) => {
                write_out_error(out_error, e);
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
pub extern "C" fn nexusts_ffi_sftp_list_dir_json(
    sftp_handle: i64,
    remote_dir_cstr: *const c_char,
    out_error: *mut *mut c_char,
) -> *mut c_char {
    catch_unwind(AssertUnwindSafe(|| unsafe {
        let dir = opt_str_from_c(remote_dir_cstr).unwrap_or("/").to_string();
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
        match tokio_runtime().block_on(async move { client.rename_entry(&old_p, &new_p).await }) {
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
        if let Ok(mut reg) = sftp_registry().lock() {
            reg.remove(&sftp_handle);
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
// 4. RFB / VNC / Apple Screen Sharing Framebuffer
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
                    RFB_ENCODING_RAW => fb.apply_raw_rect(x, y, w, h, payload),
                    RFB_ENCODING_COPYRECT if payload.len() >= 4 => {
                        let sx = u16::from_be_bytes([payload[0], payload[1]]);
                        let sy = u16::from_be_bytes([payload[2], payload[3]]);
                        fb.apply_copy_rect(x, y, w, h, sx, sy)
                    }
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
                let copy_len = fb.rgba_pixels.len().min(dst_cap);
                ptr::copy_nonoverlapping(fb.rgba_pixels.as_ptr(), dst_ptr, copy_len);
                return copy_len;
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

// ============================================================================
// 5. Integrated Network Diagnostics (WoL, Port Probe, Traceroute, Banner)
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
    fn ffi_vt_and_vnc_framebuffer_lifecycle() {
        let vt = nexusts_ffi_vt_create(80, 24, 1000);
        assert!(vt > 0);
        let sample = b"\x1b[38;2;91;155;255mNexusTS macOS\x1b[0m\r\n";
        let snap_ptr = nexusts_ffi_vt_feed_and_snapshot_json(vt, sample.as_ptr(), sample.len());
        assert!(!snap_ptr.is_null());
        let snap_str = unsafe { CStr::from_ptr(snap_ptr).to_str().unwrap().to_owned() };
        nexusts_ffi_free_cstr(snap_ptr);
        assert!(snap_str.contains("NexusTS macOS"));
        nexusts_ffi_vt_destroy(vt);

        let vnc = nexusts_ffi_vnc_create(2, 2);
        assert!(vnc > 0);
        let raw_pixels = [255u8, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255];
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
        let mut out = [0u8; 16];
        assert_eq!(nexusts_ffi_vnc_copy_rgba(vnc, out.as_mut_ptr(), out.len()), 16);
        assert_eq!(out, raw_pixels);
        nexusts_ffi_vnc_destroy(vnc);
    }
}
