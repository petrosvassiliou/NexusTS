//! NexusTS Local Offline Companion Relay (`nexusts-local-relay`) for Standard ChromeOS PWA Fallback.
//!
//! When NexusTS runs as an Isolated Web App (`.swbn`), ChromeOS provides `Direct Sockets API` (`TCPSocket` / `UDPSocket`)
//! natively and this relay is NOT needed. On unmanaged Chromebooks running NexusTS as a standard browser PWA without
//! IWA flags, running `nexusts-local-relay` inside ChromeOS Linux (Crostini) on `127.0.0.1:44890` provides 100% offline:
//! - Full-duplex raw binary TCP over WebSocket (`/v1/tcp?host=...&port=...`) for RDP, VNC, Telnet, FTP & custom TCP
//! - Interactive `russh` SSHv2 PTY + Bastion Jump Host (`/v1/ssh`)
//! - Live SFTP (`russh-sftp`) and FTP (`NexusFtpClient`) directory & file operations (`/v1/sftp`, `/v1/ftp`)
//! - IEEE 802 Wake-on-LAN UDP broadcast, sub-ms TCP Ping, and Port Scanner (`/v1/net/wol`, `/v1/net/ping`, `/v1/net/portscan`)
//! - Local ChromeOS Linux shell (`/v1/local-shell`)

use base64::engine::general_purpose::STANDARD as BASE64_STD;
use base64::Engine;
use nexusts_netutils::{format_socket_target, probe_tcp_port, send_wake_on_lan};
use nexusts_terminal::ssh_transport::{ActiveSshClientSession, SshAuthMethod, SshConnectSpec};
use nexusts_transfer::ftp_client::NexusFtpClient;
use nexusts_transfer::wire_clients::NexusSftpClient;
use russh::ChannelMsg;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;
use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::process::Command;

const DEFAULT_BIND: &str = "127.0.0.1:44890";
const WS_GUID: &[u8] = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

/// Pure-Rust RFC 3174 SHA-1 used exclusively for RFC 6455 `Sec-WebSocket-Accept` handshake.
fn sha1_digest(data: &[u8]) -> [u8; 20] {
    let mut h0: u32 = 0x6745_2301;
    let mut h1: u32 = 0xEFCD_AB89;
    let mut h2: u32 = 0x98BA_DCFE;
    let mut h3: u32 = 0x1032_5476;
    let mut h4: u32 = 0xC3D2_E1F0;

    let bit_len = (data.len() as u64).wrapping_mul(8);
    let mut padded = Vec::with_capacity(data.len() + 72);
    padded.extend_from_slice(data);
    padded.push(0x80);
    while (padded.len() % 64) != 56 {
        padded.push(0);
    }
    padded.extend_from_slice(&bit_len.to_be_bytes());

    for chunk in padded.chunks_exact(64) {
        let mut w = [0u32; 80];
        for i in 0..16 {
            let j = i * 4;
            w[i] = u32::from_be_bytes([chunk[j], chunk[j + 1], chunk[j + 2], chunk[j + 3]]);
        }
        for i in 16..80 {
            w[i] = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]).rotate_left(1);
        }
        let (mut a, mut b, mut c, mut d, mut e) = (h0, h1, h2, h3, h4);
        for (i, &wi) in w.iter().enumerate() {
            let (f, k) = match i {
                0..=19 => ((b & c) | ((!b) & d), 0x5A82_7999),
                20..=39 => (b ^ c ^ d, 0x6ED9_EBA1),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1BBCDC),
                _ => (b ^ c ^ d, 0xCA62_C1D6),
            };
            let temp = a
                .rotate_left(5)
                .wrapping_add(f)
                .wrapping_add(e)
                .wrapping_add(k)
                .wrapping_add(wi);
            e = d;
            d = c;
            c = b.rotate_left(30);
            b = a;
            a = temp;
        }
        h0 = h0.wrapping_add(a);
        h1 = h1.wrapping_add(b);
        h2 = h2.wrapping_add(c);
        h3 = h3.wrapping_add(d);
        h4 = h4.wrapping_add(e);
    }

    let mut out = [0u8; 20];
    out[0..4].copy_from_slice(&h0.to_be_bytes());
    out[4..8].copy_from_slice(&h1.to_be_bytes());
    out[8..12].copy_from_slice(&h2.to_be_bytes());
    out[12..16].copy_from_slice(&h3.to_be_bytes());
    out[16..20].copy_from_slice(&h4.to_be_bytes());
    out
}

fn websocket_accept_key(sec_ws_key: &str) -> String {
    let mut input = Vec::with_capacity(sec_ws_key.trim().len() + WS_GUID.len());
    input.extend_from_slice(sec_ws_key.trim().as_bytes());
    input.extend_from_slice(WS_GUID);
    BASE64_STD.encode(sha1_digest(&input))
}

#[derive(Debug)]
struct HttpRequestHead {
    method: String,
    path: String,
    query: HashMap<String, String>,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

fn url_decode(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Ok(h), Ok(l)) = (
                std::str::from_utf8(&bytes[i + 1..i + 2])
                    .map_err(|_| ())
                    .and_then(|s| u8::from_str_radix(s, 16).map_err(|_| ())),
                std::str::from_utf8(&bytes[i + 2..i + 3])
                    .map_err(|_| ())
                    .and_then(|s| u8::from_str_radix(s, 16).map_err(|_| ())),
            ) {
                out.push((h << 4) | l);
                i += 3;
                continue;
            }
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

fn parse_query_map(raw_query: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for pair in raw_query.split('&').filter(|s| !s.is_empty()) {
        if let Some((k, v)) = pair.split_once('=') {
            map.insert(url_decode(k), url_decode(v));
        } else {
            map.insert(url_decode(pair), String::new());
        }
    }
    map
}

async fn read_http_request(stream: &mut TcpStream) -> std::io::Result<Option<HttpRequestHead>> {
    let mut buf = Vec::with_capacity(4096);
    let mut tmp = [0u8; 2048];
    let header_end = loop {
        let n = stream.read(&mut tmp).await?;
        if n == 0 {
            if buf.is_empty() {
                return Ok(None);
            }
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "EOF before HTTP headers end",
            ));
        }
        buf.extend_from_slice(&tmp[..n]);
        if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break pos + 4;
        }
        if buf.len() > 65_536 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "HTTP header too large",
            ));
        }
    };

    let head_str = String::from_utf8_lossy(&buf[..header_end - 4]);
    let mut lines = head_str.split("\r\n");
    let req_line = lines.next().unwrap_or("");
    let mut parts = req_line.split_whitespace();
    let method = parts.next().unwrap_or("GET").to_uppercase();
    let target = parts.next().unwrap_or("/");
    let (path, query) = match target.split_once('?') {
        Some((p, q)) => (p.to_string(), parse_query_map(q)),
        None => (target.to_string(), HashMap::new()),
    };

    let mut headers = HashMap::new();
    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
        }
    }

    let content_len = headers
        .get("content-length")
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(0);
    if content_len > 64 * 1024 * 1024 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "Body exceeds 64 MiB limit",
        ));
    }
    let mut body = buf[header_end..].to_vec();
    while body.len() < content_len {
        let n = stream.read(&mut tmp).await?;
        if n == 0 {
            break;
        }
        body.extend_from_slice(&tmp[..n]);
    }
    body.truncate(content_len);

    Ok(Some(HttpRequestHead {
        method,
        path,
        query,
        headers,
        body,
    }))
}

async fn write_json_response(
    stream: &mut TcpStream,
    status: u16,
    reason: &str,
    payload: &serde_json::Value,
) -> std::io::Result<()> {
    let body = serde_json::to_vec(payload).unwrap_or_else(|_| b"{}".to_vec());
    let head = format!(
        "HTTP/1.1 {status} {reason}\r\n\
         Content-Type: application/json; charset=utf-8\r\n\
         Content-Length: {}\r\n\
         Access-Control-Allow-Origin: *\r\n\
         Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\
         Access-Control-Allow-Headers: Content-Type, Authorization, X-NexusTS-Relay\r\n\
         Access-Control-Allow-Private-Network: true\r\n\
         Connection: close\r\n\r\n",
        body.len()
    );
    stream.write_all(head.as_bytes()).await?;
    stream.write_all(&body).await?;
    stream.flush().await
}

#[derive(Debug)]
struct WsFrame {
    opcode: u8,
    payload: Vec<u8>,
}

async fn read_ws_frame<R: AsyncRead + Unpin>(reader: &mut R) -> std::io::Result<Option<WsFrame>> {
    let mut hdr = [0u8; 2];
    match reader.read_exact(&mut hdr).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let opcode = hdr[0] & 0x0F;
    let masked = (hdr[1] & 0x80) != 0;
    let mut len = (hdr[1] & 0x7F) as u64;
    if len == 126 {
        let mut ext = [0u8; 2];
        reader.read_exact(&mut ext).await?;
        len = u16::from_be_bytes(ext) as u64;
    } else if len == 127 {
        let mut ext = [0u8; 8];
        reader.read_exact(&mut ext).await?;
        len = u64::from_be_bytes(ext);
    }
    if len > 16 * 1024 * 1024 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "WS frame > 16 MiB",
        ));
    }
    let mut mask = [0u8; 4];
    if masked {
        reader.read_exact(&mut mask).await?;
    }
    let mut payload = vec![0u8; len as usize];
    reader.read_exact(&mut payload).await?;
    if masked {
        for (i, b) in payload.iter_mut().enumerate() {
            *b ^= mask[i & 3];
        }
    }
    Ok(Some(WsFrame { opcode, payload }))
}

async fn write_ws_frame<W: AsyncWrite + Unpin>(
    writer: &mut W,
    opcode: u8,
    payload: &[u8],
) -> std::io::Result<()> {
    let mut header = Vec::with_capacity(10);
    header.push(0x80 | (opcode & 0x0F));
    if payload.len() < 126 {
        header.push(payload.len() as u8);
    } else if payload.len() <= 65_535 {
        header.push(126);
        header.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    } else {
        header.push(127);
        header.extend_from_slice(&(payload.len() as u64).to_be_bytes());
    }
    writer.write_all(&header).await?;
    writer.write_all(payload).await?;
    writer.flush().await
}

async fn upgrade_websocket(
    stream: &mut TcpStream,
    headers: &HashMap<String, String>,
) -> std::io::Result<bool> {
    let Some(key) = headers.get("sec-websocket-key") else {
        return Ok(false);
    };
    let accept = websocket_accept_key(key);
    let resp = format!(
        "HTTP/1.1 101 Switching Protocols\r\n\
         Upgrade: websocket\r\n\
         Connection: Upgrade\r\n\
         Sec-WebSocket-Accept: {accept}\r\n\r\n"
    );
    stream.write_all(resp.as_bytes()).await?;
    stream.flush().await?;
    Ok(true)
}

async fn handle_ws_tcp_tunnel(mut ws_stream: TcpStream, host: String, port: u16) -> std::io::Result<()> {
    let target = format_socket_target(&host, port);
    let remote = match tokio::time::timeout(Duration::from_secs(12), TcpStream::connect(&target)).await {
        Ok(Ok(sock)) => sock,
        Ok(Err(e)) => {
            let msg = json!({"type":"error","message":format!("TCP connect failed to {target}: {e}")}).to_string();
            let _ = write_ws_frame(&mut ws_stream, 0x01, msg.as_bytes()).await;
            let _ = write_ws_frame(&mut ws_stream, 0x08, &[]).await;
            return Ok(());
        }
        Err(_) => {
            let msg = json!({"type":"error","message":format!("TCP connect timed out to {target}")}).to_string();
            let _ = write_ws_frame(&mut ws_stream, 0x01, msg.as_bytes()).await;
            let _ = write_ws_frame(&mut ws_stream, 0x08, &[]).await;
            return Ok(());
        }
    };
    let _ = remote.set_nodelay(true);
    let (mut ws_read, mut ws_write) = ws_stream.into_split();
    let (mut tcp_read, mut tcp_write) = remote.into_split();

    let mut tcp_buf = vec![0u8; 32_768];
    loop {
        tokio::select! {
            frame_res = read_ws_frame(&mut ws_read) => {
                match frame_res? {
                    Some(frame) => match frame.opcode {
                        0x01 | 0x02 => {
                            tcp_write.write_all(&frame.payload).await?;
                            tcp_write.flush().await?;
                        }
                        0x08 => {
                            let _ = write_ws_frame(&mut ws_write, 0x08, &[]).await;
                            break;
                        }
                        0x09 => {
                            write_ws_frame(&mut ws_write, 0x0A, &frame.payload).await?;
                        }
                        _ => {}
                    },
                    None => break,
                }
            }
            n_res = tcp_read.read(&mut tcp_buf) => {
                let n = n_res?;
                if n == 0 {
                    let _ = write_ws_frame(&mut ws_write, 0x08, &[]).await;
                    break;
                }
                write_ws_frame(&mut ws_write, 0x02, &tcp_buf[..n]).await?;
            }
        }
    }
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WsSshInit {
    host: String,
    #[serde(default = "default_ssh_port")]
    port: u16,
    username: String,
    #[serde(default)]
    password: String,
    #[serde(default)]
    private_key_pem: String,
    #[serde(default)]
    key_passphrase: String,
    #[serde(default = "default_cols")]
    cols: u32,
    #[serde(default = "default_rows")]
    rows: u32,
    #[serde(default)]
    startup_command: String,
    #[serde(default)]
    bastion: Option<Box<WsSshInit>>,
}
fn default_ssh_port() -> u16 { 22 }
fn default_cols() -> u32 { 120 }
fn default_rows() -> u32 { 34 }

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum WsSshClientMsg {
    Input { data_b64: String },
    Resize { cols: u32, rows: u32 },
}

fn to_ssh_spec(cfg: &WsSshInit) -> SshConnectSpec {
    let auth = if !cfg.private_key_pem.trim().is_empty() {
        SshAuthMethod::PrivateKeyPem {
            pem: cfg.private_key_pem.clone(),
            passphrase: if cfg.key_passphrase.is_empty() {
                None
            } else {
                Some(cfg.key_passphrase.clone())
            },
        }
    } else {
        SshAuthMethod::Password {
            password: cfg.password.clone(),
        }
    };
    SshConnectSpec {
        host: cfg.host.clone(),
        port: if cfg.port == 0 { 22 } else { cfg.port },
        username: if cfg.username.trim().is_empty() {
            "root".into()
        } else {
            cfg.username.clone()
        },
        auth,
        expected_host_fingerprint_sha256: None,
        term_env: "xterm-256color".into(),
        initial_cols: cfg.cols.max(20),
        initial_rows: cfg.rows.max(6),
        enable_x11: false,
        keepalive_secs: 25,
        startup_command: if cfg.startup_command.trim().is_empty() {
            None
        } else {
            Some(cfg.startup_command.clone())
        },
    }
}

async fn handle_ws_ssh(mut ws_stream: TcpStream) -> std::io::Result<()> {
    let Some(init_frame) = read_ws_frame(&mut ws_stream).await? else {
        return Ok(());
    };
    let cfg: WsSshInit = match serde_json::from_slice(&init_frame.payload) {
        Ok(c) => c,
        Err(e) => {
            let err = json!({"type":"error","message":format!("Invalid SSH init JSON: {e}")}).to_string();
            let _ = write_ws_frame(&mut ws_stream, 0x01, err.as_bytes()).await;
            return Ok(());
        }
    };

    let bastion_session = if let Some(b_cfg) = &cfg.bastion {
        match ActiveSshClientSession::connect_without_shell(to_ssh_spec(b_cfg), None).await {
            Ok(b) => Some(b),
            Err(e) => {
                let err = json!({"type":"error","message":format!("Bastion jump failed: {e}")}).to_string();
                let _ = write_ws_frame(&mut ws_stream, 0x01, err.as_bytes()).await;
                return Ok(());
            }
        }
    } else {
        None
    };

    let (_ssh_session, mut channel) =
        match ActiveSshClientSession::connect(to_ssh_spec(&cfg), bastion_session.as_ref()).await {
            Ok(pair) => pair,
            Err(e) => {
                let err = json!({"type":"error","message":format!("{e}")}).to_string();
                let _ = write_ws_frame(&mut ws_stream, 0x01, err.as_bytes()).await;
                return Ok(());
            }
        };

    let ready = json!({"type":"ready","banner":format!("Connected to {}:{} via russh SSHv2", cfg.host, cfg.port)}).to_string();
    write_ws_frame(&mut ws_stream, 0x01, ready.as_bytes()).await?;

    let (mut ws_read, mut ws_write) = ws_stream.into_split();
    loop {
        tokio::select! {
            msg_opt = channel.wait() => {
                match msg_opt {
                    Some(ChannelMsg::Data { data }) => {
                        write_ws_frame(&mut ws_write, 0x02, data.as_ref()).await?;
                    }
                    Some(ChannelMsg::ExtendedData { data, .. }) => {
                        write_ws_frame(&mut ws_write, 0x02, data.as_ref()).await?;
                    }
                    Some(ChannelMsg::Eof | ChannelMsg::Close) | None => {
                        let _ = write_ws_frame(&mut ws_write, 0x08, &[]).await;
                        break;
                    }
                    _ => {}
                }
            }
            frame_res = read_ws_frame(&mut ws_read) => {
                match frame_res? {
                    Some(frame) => match frame.opcode {
                        0x02 => {
                            let _ = channel.data(&frame.payload[..]).await;
                        }
                        0x01 => {
                            if let Ok(cmd) = serde_json::from_slice::<WsSshClientMsg>(&frame.payload) {
                                match cmd {
                                    WsSshClientMsg::Input { data_b64 } => {
                                        if let Ok(bytes) = BASE64_STD.decode(data_b64) {
                                            let _ = channel.data(&bytes[..]).await;
                                        }
                                    }
                                    WsSshClientMsg::Resize { cols, rows } => {
                                        let _ = ActiveSshClientSession::notify_pty_resize(&channel, cols, rows).await;
                                    }
                                }
                            } else {
                                let _ = channel.data(&frame.payload[..]).await;
                            }
                        }
                        0x08 => break,
                        0x09 => {
                            write_ws_frame(&mut ws_write, 0x0A, &frame.payload).await?;
                        }
                        _ => {}
                    },
                    None => break,
                }
            }
        }
    }
    Ok(())
}

async fn handle_ws_local_shell(ws_stream: TcpStream) -> std::io::Result<()> {
    let shell_bin = std::env::var("SHELL").unwrap_or_else(|_| "/bin/bash".into());
    let mut child = Command::new(&shell_bin)
        .arg("-i")
        .env("TERM", "xterm-256color")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;

    let mut stdin = child.stdin.take().unwrap();
    let mut stdout = child.stdout.take().unwrap();
    let mut stderr = child.stderr.take().unwrap();
    let (mut ws_read, mut ws_write) = ws_stream.into_split();

    let mut out_buf = [0u8; 8192];
    let mut err_buf = [0u8; 4096];
    loop {
        tokio::select! {
            n_res = stdout.read(&mut out_buf) => {
                let n = n_res?;
                if n == 0 { break; }
                write_ws_frame(&mut ws_write, 0x02, &out_buf[..n]).await?;
            }
            n_res = stderr.read(&mut err_buf) => {
                let n = n_res?;
                if n > 0 {
                    write_ws_frame(&mut ws_write, 0x02, &err_buf[..n]).await?;
                }
            }
            frame_res = read_ws_frame(&mut ws_read) => {
                match frame_res? {
                    Some(f) if f.opcode == 0x01 || f.opcode == 0x02 => {
                        stdin.write_all(&f.payload).await?;
                        stdin.flush().await?;
                    }
                    Some(f) if f.opcode == 0x08 => break,
                    None => break,
                    _ => {}
                }
            }
        }
    }
    let _ = child.kill().await;
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TransferActionRequest {
    action: String,
    host: String,
    #[serde(default)]
    port: u16,
    #[serde(default)]
    username: String,
    #[serde(default)]
    password: String,
    #[serde(default)]
    private_key_pem: String,
    #[serde(default)]
    key_passphrase: String,
    #[serde(default)]
    path: String,
    #[serde(default)]
    new_path: String,
    #[serde(default)]
    is_dir: bool,
    #[serde(default)]
    content_base64: String,
}

async fn handle_sftp_rpc(req: TransferActionRequest) -> Result<serde_json::Value, String> {
    let init = WsSshInit {
        host: req.host,
        port: if req.port == 0 { 22 } else { req.port },
        username: req.username,
        password: req.password,
        private_key_pem: req.private_key_pem,
        key_passphrase: req.key_passphrase,
        cols: 80,
        rows: 24,
        startup_command: String::new(),
        bastion: None,
    };
    let ssh = ActiveSshClientSession::connect_without_shell(to_ssh_spec(&init), None)
        .await
        .map_err(|e| e.to_string())?;
    let stream = ssh.open_sftp_subsystem_stream().await.map_err(|e| e.to_string())?;
    let sftp = NexusSftpClient::from_subsystem_stream(stream)
        .await
        .map_err(|e| e.to_string())?;

    let result = match req.action.as_str() {
        "pwd" => json!({"ok": true, "path": sftp.pwd().await.map_err(|e| e.to_string())?}),
        "list" => {
            let dir = if req.path.is_empty() {
                sftp.pwd().await.unwrap_or_else(|_| ".".into())
            } else {
                req.path.clone()
            };
            let entries = sftp.list_directory(&dir).await.map_err(|e| e.to_string())?;
            json!({"ok": true, "path": dir, "entries": entries})
        }
        "mkdir" => {
            sftp.create_directory(&req.path).await.map_err(|e| e.to_string())?;
            json!({"ok": true})
        }
        "delete" => {
            sftp.remove_entry(&req.path, req.is_dir).await.map_err(|e| e.to_string())?;
            json!({"ok": true})
        }
        "rename" => {
            sftp.rename(&req.path, &req.new_path).await.map_err(|e| e.to_string())?;
            json!({"ok": true})
        }
        "download" => {
            let bytes = sftp.download_bytes(&req.path).await.map_err(|e| e.to_string())?;
            json!({"ok": true, "sizeBytes": bytes.len(), "contentBase64": BASE64_STD.encode(bytes)})
        }
        "upload" => {
            let bytes = BASE64_STD.decode(&req.content_base64).map_err(|e| e.to_string())?;
            let written = sftp.upload_bytes(&req.path, &bytes).await.map_err(|e| e.to_string())?;
            json!({"ok": true, "bytesWritten": written})
        }
        other => return Err(format!("Unsupported SFTP action: {other}")),
    };
    let _ = sftp.close().await;
    Ok(result)
}

async fn handle_ftp_rpc(req: TransferActionRequest) -> Result<serde_json::Value, String> {
    let ftp = NexusFtpClient::connect(
        &req.host,
        if req.port == 0 { 21 } else { req.port },
        &req.username,
        &req.password,
    )
    .await
    .map_err(|e| e.to_string())?;

    let result = match req.action.as_str() {
        "pwd" => json!({"ok": true, "path": ftp.pwd().await.map_err(|e| e.to_string())?}),
        "list" => {
            let dir = if req.path.is_empty() {
                ftp.pwd().await.unwrap_or_else(|_| "/".into())
            } else {
                req.path.clone()
            };
            let entries = ftp.list_directory(&dir).await.map_err(|e| e.to_string())?;
            json!({"ok": true, "path": dir, "entries": entries})
        }
        "mkdir" => {
            ftp.create_directory(&req.path).await.map_err(|e| e.to_string())?;
            json!({"ok": true})
        }
        "delete" => {
            ftp.remove_entry(&req.path).await.map_err(|e| e.to_string())?;
            json!({"ok": true})
        }
        "rename" => {
            ftp.rename(&req.path, &req.new_path).await.map_err(|e| e.to_string())?;
            json!({"ok": true})
        }
        "download" => {
            let bytes = ftp.download_bytes(&req.path).await.map_err(|e| e.to_string())?;
            json!({"ok": true, "sizeBytes": bytes.len(), "contentBase64": BASE64_STD.encode(bytes)})
        }
        "upload" => {
            let bytes = BASE64_STD.decode(&req.content_base64).map_err(|e| e.to_string())?;
            let written = ftp.upload_bytes(&req.path, &bytes).await.map_err(|e| e.to_string())?;
            json!({"ok": true, "bytesWritten": written})
        }
        other => return Err(format!("Unsupported FTP action: {other}")),
    };
    let _ = ftp.disconnect().await;
    Ok(result)
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct WolRequest {
    mac: String,
    #[serde(default = "default_bcast")]
    broadcast_address: String,
    #[serde(default = "default_wol_port")]
    port: u16,
    #[serde(default)]
    secure_on_hex: String,
}
fn default_bcast() -> String { "255.255.255.255".into() }
fn default_wol_port() -> u16 { 9 }

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PortScanRequest {
    host: String,
    #[serde(default = "default_scan_start")]
    start_port: u16,
    #[serde(default = "default_scan_end")]
    end_port: u16,
    #[serde(default = "default_timeout_ms")]
    timeout_ms: u64,
}
fn default_scan_start() -> u16 { 1 }
fn default_scan_end() -> u16 { 1024 }
fn default_timeout_ms() -> u64 { 250 }

async fn handle_client(mut stream: TcpStream) -> std::io::Result<()> {
    let Some(req) = read_http_request(&mut stream).await? else {
        return Ok(());
    };

    if req.method == "OPTIONS" {
        return write_json_response(&mut stream, 200, "OK", &json!({"ok": true})).await;
    }

    let is_ws = req
        .headers
        .get("upgrade")
        .map(|v| v.eq_ignore_ascii_case("websocket"))
        .unwrap_or(false);

    if is_ws {
        if !upgrade_websocket(&mut stream, &req.headers).await? {
            return write_json_response(
                &mut stream,
                400,
                "Bad Request",
                &json!({"ok": false, "error": "Missing Sec-WebSocket-Key"}),
            )
            .await;
        }
        return match req.path.as_str() {
            "/v1/tcp" => {
                let host = req.query.get("host").cloned().unwrap_or_default();
                let port = req
                    .query
                    .get("port")
                    .and_then(|p| p.parse::<u16>().ok())
                    .unwrap_or(0);
                if host.is_empty() || port == 0 {
                    return Ok(());
                }
                handle_ws_tcp_tunnel(stream, host, port).await
            }
            "/v1/ssh" => handle_ws_ssh(stream).await,
            "/v1/local-shell" => handle_ws_local_shell(stream).await,
            _ => Ok(()),
        };
    }

    match (req.method.as_str(), req.path.as_str()) {
        ("GET", "/health") => {
            write_json_response(
                &mut stream,
                200,
                "OK",
                &json!({
                    "ok": true,
                    "service": "nexusts-local-relay",
                    "version": env!("CARGO_PKG_VERSION"),
                    "capabilities": ["tcp", "ssh", "sftp", "ftp", "wol", "ping", "portscan", "local-shell"]
                }),
            )
            .await
        }
        ("POST", "/v1/sftp") => match serde_json::from_slice::<TransferActionRequest>(&req.body) {
            Ok(parsed) => match handle_sftp_rpc(parsed).await {
                Ok(val) => write_json_response(&mut stream, 200, "OK", &val).await,
                Err(e) => {
                    write_json_response(&mut stream, 502, "Bad Gateway", &json!({"ok": false, "error": e}))
                        .await
                }
            },
            Err(e) => {
                write_json_response(&mut stream, 400, "Bad Request", &json!({"ok": false, "error": e.to_string()}))
                    .await
            }
        },
        ("POST", "/v1/ftp") => match serde_json::from_slice::<TransferActionRequest>(&req.body) {
            Ok(parsed) => match handle_ftp_rpc(parsed).await {
                Ok(val) => write_json_response(&mut stream, 200, "OK", &val).await,
                Err(e) => {
                    write_json_response(&mut stream, 502, "Bad Gateway", &json!({"ok": false, "error": e}))
                        .await
                }
            },
            Err(e) => {
                write_json_response(&mut stream, 400, "Bad Request", &json!({"ok": false, "error": e.to_string()}))
                    .await
            }
        },
        ("POST", "/v1/net/wol") => match serde_json::from_slice::<WolRequest>(&req.body) {
            Ok(w) => {
                let sec = if w.secure_on_hex.trim().is_empty() {
                    None
                } else {
                    Some(w.secure_on_hex.as_str())
                };
                match send_wake_on_lan(&w.mac, &w.broadcast_address, w.port, sec) {
                    Ok(bytes) => {
                        write_json_response(&mut stream, 200, "OK", &json!({"ok": true, "bytesSent": bytes}))
                            .await
                    }
                    Err(e) => {
                        write_json_response(&mut stream, 400, "Bad Request", &json!({"ok": false, "error": e.to_string()}))
                            .await
                    }
                }
            }
            Err(e) => {
                write_json_response(&mut stream, 400, "Bad Request", &json!({"ok": false, "error": e.to_string()}))
                    .await
            }
        },
        ("POST", "/v1/net/ping") => {
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct PingReq {
                host: String,
                #[serde(default = "default_ssh_port")]
                port: u16,
                #[serde(default = "default_timeout_ms")]
                timeout_ms: u64,
            }
            match serde_json::from_slice::<PingReq>(&req.body) {
                Ok(p) => {
                    let host = p.host.clone();
                    let port = p.port;
                    let timeout = p.timeout_ms.clamp(50, 5000);
                    let hit = tokio::task::spawn_blocking(move || probe_tcp_port(&host, port, timeout))
                        .await
                        .ok()
                        .flatten();
                    write_json_response(&mut stream, 200, "OK", &json!({"ok": true, "hit": hit})).await
                }
                Err(e) => {
                    write_json_response(&mut stream, 400, "Bad Request", &json!({"ok": false, "error": e.to_string()}))
                        .await
                }
            }
        }
        ("POST", "/v1/net/portscan") => match serde_json::from_slice::<PortScanRequest>(&req.body) {
            Ok(scan) => {
                let host = scan.host.clone();
                let start = scan.start_port.max(1);
                let end = scan.end_port.max(start).min(start.saturating_add(2048));
                let timeout = scan.timeout_ms.clamp(60, 2000);
                let mut open_hits = Vec::new();
                for chunk_start in (start..=end).step_by(64) {
                    let chunk_end = (chunk_start.saturating_add(63)).min(end);
                    let mut tasks = Vec::new();
                    for p in chunk_start..=chunk_end {
                        let h = host.clone();
                        tasks.push(tokio::task::spawn_blocking(move || probe_tcp_port(&h, p, timeout)));
                    }
                    for t in tasks {
                        if let Ok(Some(hit)) = t.await {
                            open_hits.push(hit);
                        }
                    }
                }
                write_json_response(&mut stream, 200, "OK", &json!({"ok": true, "hits": open_hits})).await
            }
            Err(e) => {
                write_json_response(&mut stream, 400, "Bad Request", &json!({"ok": false, "error": e.to_string()}))
                    .await
            }
        },
        _ => {
            write_json_response(
                &mut stream,
                404,
                "Not Found",
                &json!({"ok": false, "error": "Unknown relay endpoint"}),
            )
            .await
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--version" || a == "-V") {
        println!("nexusts-local-relay {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    if args.iter().any(|a| a == "--self-test") {
        // RFC 6455 §4.2.2 test vector check
        let accept = websocket_accept_key("dGhlIHNhbXBsZSBub25jZQ==");
        assert_eq!(accept, "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
        println!("NEXUSTS_RELAY_SELF_TEST_OK version={}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    let bind_addr = args
        .windows(2)
        .find(|w| w[0] == "--bind")
        .map(|w| w[1].as_str())
        .unwrap_or(DEFAULT_BIND);

    let listener = TcpListener::bind(bind_addr).await?;
    println!(
        "[NexusTS Local Relay v{}] Listening offline on http://{bind_addr}",
        env!("CARGO_PKG_VERSION")
    );
    loop {
        let (sock, _) = listener.accept().await?;
        let _ = sock.set_nodelay(true);
        tokio::spawn(async move {
            let _ = handle_client(sock).await;
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc6455_websocket_accept_vector() {
        assert_eq!(
            websocket_accept_key("dGhlIHNhbXBsZSBub25jZQ=="),
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        );
    }
}
