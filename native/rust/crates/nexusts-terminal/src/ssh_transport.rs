//! Async SSHv2 (`russh`) Transport, Bastion Jump-Host Chaining, Port/SOCKS5/X11 Forwarding,
//! Keyboard-Interactive PAM Fallback, and Telnet RFC 854 / RFC 1073 (`IAC` Negotiation) Helpers.

use async_trait::async_trait;
use base64::engine::general_purpose::{STANDARD as BASE64_STD, STANDARD_NO_PAD as BASE64_NO_PAD};
use base64::Engine;
use russh::client::{self, Handle, KeyboardInteractiveAuthResponse, Msg};
use russh::{Channel, ChannelId, Disconnect};
use russh_keys::key::PrivateKeyWithHashAlg;
use russh_keys::{HashAlg, PublicKey, PublicKeyBase64};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Duration;
use thiserror::Error;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio::task::{AbortHandle, JoinSet};

#[derive(Debug, Error)]
pub enum TransportError {
    #[error("SSH protocol error: {0}")]
    Ssh(#[from] russh::Error),
    #[error("SSH key decode error: {0}")]
    Key(#[from] russh_keys::Error),
    #[error("Authentication rejected by remote SSH server")]
    AuthenticationRejected,
    #[error("Host key verification rejected (expected {expected}, got {actual})")]
    HostKeyMismatch { expected: String, actual: String },
    #[error("Network I/O error: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum SshAuthMethod {
    Password { password: String },
    PrivateKeyPem { pem: String, passphrase: Option<String> },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SshConnectSpec {
    pub host: String,
    pub port: u16,
    pub username: String,
    pub auth: SshAuthMethod,
    pub expected_host_fingerprint_sha256: Option<String>,
    pub term_env: String,
    pub initial_cols: u32,
    pub initial_rows: u32,
    pub enable_x11: bool,
    pub keepalive_secs: u64,
    #[serde(default)]
    pub startup_command: Option<String>,
}

pub struct NexusSshHandler {
    expected_fp: Option<String>,
    x11_events_tx: Option<mpsc::UnboundedSender<Vec<u8>>>,
}

/// Computes the standard OpenSSH `SHA256:<unpadded_base64>` fingerprint over the decoded public key blob,
/// along with hex fallback representations so both standard `ssh-keygen -lf` pins and hex pins match.
pub fn verify_server_public_key_pin(raw_b64: &str, expected: &str) -> Result<String, String> {
    let decoded_key_bytes = BASE64_STD
        .decode(raw_b64.trim())
        .unwrap_or_else(|_| raw_b64.as_bytes().to_vec());
    let digest = Sha256::digest(&decoded_key_bytes);
    let openssh_b64 = BASE64_NO_PAD.encode(digest);
    let computed_openssh = format!("SHA256:{openssh_b64}");

    let trimmed = expected.trim();
    if trimmed.is_empty() {
        return Ok(computed_openssh);
    }

    let stripped_expected = trimmed
        .strip_prefix("SHA256:")
        .or_else(|| trimmed.strip_prefix("sha256:"))
        .unwrap_or(trimmed)
        .trim_end_matches('=');

    // 1. Standard OpenSSH SHA256:<unpadded_base64> check (base64 is case-sensitive)
    if stripped_expected == openssh_b64 {
        return Ok(computed_openssh);
    }

    // 2. Fallback check against hex of decoded key SHA-256 or legacy base64-string SHA-256
    let decoded_hex = format!("{:x}", digest);
    let legacy_hex = format!("{:x}", Sha256::digest(raw_b64.as_bytes()));
    if stripped_expected.eq_ignore_ascii_case(&decoded_hex)
        || stripped_expected.eq_ignore_ascii_case(&legacy_hex)
    {
        return Ok(computed_openssh);
    }

    Err(computed_openssh)
}

#[async_trait]
impl client::Handler for NexusSshHandler {
    type Error = TransportError;

    async fn check_server_key(
        &mut self,
        server_public_key: &PublicKey,
    ) -> Result<bool, Self::Error> {
        let raw_b64 = server_public_key.public_key_base64();
        if let Some(expected) = &self.expected_fp {
            if let Err(actual) = verify_server_public_key_pin(&raw_b64, expected) {
                return Err(TransportError::HostKeyMismatch {
                    expected: expected.clone(),
                    actual,
                });
            }
        }
        Ok(true)
    }

    async fn server_channel_open_x11(
        &mut self,
        mut channel: Channel<Msg>,
        _originator_address: &str,
        _originator_port: u32,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        if let Some(tx) = self.x11_events_tx.clone() {
            tokio::spawn(async move {
                while let Some(msg) = channel.wait().await {
                    if let russh::ChannelMsg::Data { data } = msg {
                        let _ = tx.send(data.to_vec());
                    }
                }
            });
        }
        Ok(())
    }
}

async fn authenticate_keyboard_interactive_with_password(
    handle: &mut Handle<NexusSshHandler>,
    username: &str,
    password: &str,
) -> Result<bool, TransportError> {
    let fut = async {
        let mut step = handle
            .authenticate_keyboard_interactive_start(username, None::<String>)
            .await?;

        for _ in 0..8 {
            match step {
                KeyboardInteractiveAuthResponse::Success => return Ok(true),
                KeyboardInteractiveAuthResponse::Failure => return Ok(false),
                KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                    let responses: Vec<String> = prompts
                        .iter()
                        .map(|p| {
                            if p.prompt.is_empty() {
                                String::new()
                            } else {
                                password.to_string()
                            }
                        })
                        .collect();
                    step = handle
                        .authenticate_keyboard_interactive_respond(responses)
                        .await?;
                }
            }
        }

        Ok::<bool, TransportError>(false)
    };

    match tokio::time::timeout(Duration::from_secs(15), fut).await {
        Ok(res) => res,
        Err(_) => Ok(false),
    }
}

pub struct ActiveSshClientSession {
    handle: Arc<Handle<NexusSshHandler>>,
    pty_channel_id: Option<ChannelId>,
}

impl ActiveSshClientSession {
    async fn open_raw_handle(
        spec: &SshConnectSpec,
        bastion: Option<&ActiveSshClientSession>,
    ) -> Result<Handle<NexusSshHandler>, TransportError> {
        let mut config = client::Config::default();
        if spec.keepalive_secs > 0 {
            config.keepalive_interval = Some(Duration::from_secs(spec.keepalive_secs));
        }
        let config = Arc::new(config);
        let handler = NexusSshHandler {
            expected_fp: spec.expected_host_fingerprint_sha256.clone(),
            x11_events_tx: None,
        };

        let effective_port = if spec.port == 0 { 22 } else { spec.port };

        let handle = if let Some(jump_host) = bastion {
            let tunnel_channel = jump_host
                .handle
                .channel_open_direct_tcpip(&spec.host, u32::from(effective_port), "127.0.0.1", 0)
                .await?;
            client::connect_stream(config, tunnel_channel.into_stream(), handler).await?
        } else {
            client::connect(config, (spec.host.as_str(), effective_port), handler).await?
        };
        Ok(handle)
    }

    async fn authenticate_session(
        spec: &SshConnectSpec,
        bastion: Option<&ActiveSshClientSession>,
    ) -> Result<Arc<Handle<NexusSshHandler>>, TransportError> {
        let mut handle = Self::open_raw_handle(spec, bastion).await?;

        let authenticated = match &spec.auth {
            SshAuthMethod::Password { password } => {
                if handle
                    .authenticate_password(&spec.username, password)
                    .await?
                {
                    true
                } else {
                    // Fallback to RFC 4256 keyboard-interactive for PAM / BSD / Linux OpenSSH servers.
                    // Note: russh 0.49 only initializes `CurrentRequest::KeyboardInteractive` when
                    // keyboard-interactive is the first auth request on a `Handle`, so we open a fresh
                    // transport handle for the PAM challenge-response exchange.
                    let _ = handle
                        .disconnect(Disconnect::ByApplication, "Fallback to kbd-int", "en")
                        .await;
                    let mut kbd_handle = Self::open_raw_handle(spec, bastion).await?;
                    let ok = authenticate_keyboard_interactive_with_password(
                        &mut kbd_handle,
                        &spec.username,
                        password,
                    )
                    .await?;
                    handle = kbd_handle;
                    ok
                }
            }
            SshAuthMethod::PrivateKeyPem { pem, passphrase } => {
                let key_pair = russh_keys::decode_secret_key(pem, passphrase.as_deref())?;
                let hash_alg = if key_pair.algorithm().is_rsa() {
                    Some(HashAlg::Sha256)
                } else {
                    None
                };
                let key_with_alg = PrivateKeyWithHashAlg::new(Arc::new(key_pair), hash_alg)?;
                handle
                    .authenticate_publickey(&spec.username, key_with_alg)
                    .await?
            }
        };

        if !authenticated {
            return Err(TransportError::AuthenticationRejected);
        }

        Ok(Arc::new(handle))
    }

    /// Connects and authenticates without opening an interactive shell PTY (ideal for SFTP/SCP-only or Bastion sessions).
    pub async fn connect_without_shell(
        spec: SshConnectSpec,
        bastion: Option<&ActiveSshClientSession>,
    ) -> Result<Self, TransportError> {
        let handle = Self::authenticate_session(&spec, bastion).await?;
        Ok(Self {
            handle,
            pty_channel_id: None,
        })
    }

    /// Connects directly to `spec` or tunnels through an existing `bastion` SSH session via `direct-tcpip`.
    pub async fn connect(
        spec: SshConnectSpec,
        bastion: Option<&ActiveSshClientSession>,
    ) -> Result<(Self, Channel<Msg>), TransportError> {
        let handle = Self::authenticate_session(&spec, bastion).await?;
        let channel = handle.channel_open_session().await?;
        let pty_id = channel.id();
        channel
            .request_pty(
                false,
                &spec.term_env,
                spec.initial_cols.max(10),
                spec.initial_rows.max(4),
                0,
                0,
                &[],
            )
            .await?;

        if spec.enable_x11 {
            channel
                .request_x11(false, false, "MIT-MAGIC-COOKIE-1", "00", 0)
                .await?;
        }

        channel.request_shell(false).await?;

        if let Some(cmd) = spec
            .startup_command
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            let line = format!("{cmd}\n");
            channel.data(line.as_bytes()).await?;
        }

        Ok((
            Self {
                handle,
                pty_channel_id: Some(pty_id),
            },
            channel,
        ))
    }

    pub fn pty_channel_id(&self) -> Option<ChannelId> {
        self.pty_channel_id
    }

    pub fn shared_handle(&self) -> Arc<Handle<NexusSshHandler>> {
        Arc::clone(&self.handle)
    }

    pub async fn notify_pty_resize(
        channel: &Channel<Msg>,
        cols: u32,
        rows: u32,
    ) -> Result<(), TransportError> {
        channel.window_change(cols, rows, 0, 0).await?;
        Ok(())
    }

    /// Binds a local port `-L bind_addr:bind_port:target_host:target_port`, forwards sockets over `direct-tcpip`,
    /// and returns an [`AbortHandle`] that stops the listener and releases the bound port on disconnect.
    pub async fn start_local_port_forward(
        handle: Arc<Handle<NexusSshHandler>>,
        bind_addr: String,
        bind_port: u16,
        target_host: String,
        target_port: u16,
    ) -> Result<AbortHandle, TransportError> {
        let listener = TcpListener::bind((bind_addr.as_str(), bind_port)).await?;
        let join = tokio::spawn(async move {
            let mut sub_tasks = JoinSet::new();
            loop {
                tokio::select! {
                    accept_res = listener.accept() => {
                        match accept_res {
                            Ok((mut local_sock, peer)) => {
                                let h = Arc::clone(&handle);
                                let th = target_host.clone();
                                sub_tasks.spawn(async move {
                                    if let Ok(channel) = h
                                        .channel_open_direct_tcpip(
                                            &th,
                                            u32::from(target_port),
                                            &peer.ip().to_string(),
                                            u32::from(peer.port()),
                                        )
                                        .await
                                    {
                                        let mut ssh_stream = channel.into_stream();
                                        let _ = tokio::io::copy_bidirectional(&mut local_sock, &mut ssh_stream).await;
                                    }
                                });
                            }
                            Err(_) => break,
                        }
                    }
                    Some(_) = sub_tasks.join_next(), if !sub_tasks.is_empty() => {}
                }
            }
        });
        Ok(join.abort_handle())
    }

    /// Backwards-compatible wrapper around [`Self::start_local_port_forward`].
    pub async fn spawn_local_port_forward(
        handle: Arc<Handle<NexusSshHandler>>,
        bind_addr: String,
        bind_port: u16,
        target_host: String,
        target_port: u16,
    ) -> Result<(), TransportError> {
        let _ = Self::start_local_port_forward(
            handle,
            bind_addr,
            bind_port,
            target_host,
            target_port,
        )
        .await?;
        Ok(())
    }

    /// Binds a dynamic RFC 1928 SOCKS5 proxy `-D bind_addr:bind_port`, tunnels `CONNECT` streams over
    /// `direct-tcpip`, and returns an [`AbortHandle`] that stops the listener and releases the bound port on disconnect.
    pub async fn start_dynamic_socks5_proxy(
        handle: Arc<Handle<NexusSshHandler>>,
        bind_addr: String,
        bind_port: u16,
    ) -> Result<AbortHandle, TransportError> {
        let listener = TcpListener::bind((bind_addr.as_str(), bind_port)).await?;
        let join = tokio::spawn(async move {
            let mut sub_tasks = JoinSet::new();
            loop {
                tokio::select! {
                    accept_res = listener.accept() => {
                        match accept_res {
                            Ok((mut client_sock, peer)) => {
                                let h = Arc::clone(&handle);
                                sub_tasks.spawn(async move {
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
                            Err(_) => break,
                        }
                    }
                    Some(_) = sub_tasks.join_next(), if !sub_tasks.is_empty() => {}
                }
            }
        });
        Ok(join.abort_handle())
    }

    /// Opens an SFTP v3 subsystem channel on the existing SSH multiplex connection.
    pub async fn open_sftp_subsystem_stream(
        &self,
    ) -> Result<russh::ChannelStream<Msg>, TransportError> {
        let channel = self.handle.channel_open_session().await?;
        channel.request_subsystem(true, "sftp").await?;
        Ok(channel.into_stream())
    }

    pub async fn disconnect(&self) -> Result<(), TransportError> {
        self.handle
            .disconnect(Disconnect::ByApplication, "NexusTS session closed", "en")
            .await?;
        Ok(())
    }

    /// Executes a non-interactive one-shot remote command (`exec` channel) over SSHv2 and returns
    /// UTF-8 stdout (or an error with stderr/exit code when non-zero), used for OpenSSH PowerShell
    /// Hyper-V VM inventory (`Get-VM`) and VM power control (`Start-VM` / `Stop-VM` / `Save-VM` / `Restart-VM`).
    pub async fn exec_oneshot_command(
        spec: SshConnectSpec,
        command: &str,
        timeout_secs: u64,
    ) -> Result<String, TransportError> {
        let timeout_dur = Duration::from_secs(timeout_secs.clamp(2, 60));
        let fut = async {
            let session = Self::connect_without_shell(spec, None).await?;
            let mut channel = session.handle.channel_open_session().await?;
            channel.exec(true, command).await?;

            let mut stdout = Vec::new();
            let mut stderr = Vec::new();
            let mut exit_status: Option<u32> = None;

            while let Some(msg) = channel.wait().await {
                match msg {
                    russh::ChannelMsg::Data { data } => stdout.extend_from_slice(&data),
                    russh::ChannelMsg::ExtendedData { data, .. } => stderr.extend_from_slice(&data),
                    russh::ChannelMsg::ExitStatus { exit_status: code } => exit_status = Some(code),
                    russh::ChannelMsg::Eof | russh::ChannelMsg::Close => {}
                    _ => {}
                }
            }
            let _ = session.disconnect().await;

            let out_str = String::from_utf8_lossy(&stdout).trim().to_string();
            let err_str = String::from_utf8_lossy(&stderr).trim().to_string();
            if let Some(code) = exit_status {
                if code != 0 && out_str.is_empty() {
                    return Err(TransportError::Io(std::io::Error::other(if err_str.is_empty() {
                        format!("Remote SSH command exited with status {code}")
                    } else {
                        err_str
                    })));
                }
            }
            Ok(out_str)
        };

        tokio::time::timeout(timeout_dur, fut).await.map_err(|_| {
            TransportError::Io(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!("SSH command timed out after {}s", timeout_dur.as_secs()),
            ))
        })?
    }
}

/// Complete RFC 854 Telnet `IAC` Command Filter & Negotiation Engine.
/// Strips Telnet `IAC` (`0xFF`) sequences out of terminal display data while answering
/// `DO TTYPE` (24) and `DO NAWS` (31) negotiations cleanly.
pub struct TelnetNegotiator {
    pub cols: u16,
    pub rows: u16,
    pub term_type: String,
}

impl TelnetNegotiator {
    pub fn new(cols: u16, rows: u16, term_type: &str) -> Self {
        Self {
            cols,
            rows,
            term_type: term_type.to_string(),
        }
    }

    /// Processes incoming Telnet wire bytes and returns `(clean_terminal_bytes, outbound_iac_replies)`.
    pub fn process_incoming(&self, wire: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let mut parser =
            crate::telnet_transport::StatefulTelnetParser::new(self.cols, self.rows, &self.term_type);
        parser.feed(wire)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use russh::server::{self, Auth, Msg as ServerMsg, Response, Session as ServerSession};
    use russh::{ChannelMsg, CryptoVec, MethodSet, Pty};
    use russh_keys::ssh_key::rand_core::OsRng;
    use russh_keys::ssh_key::{Algorithm, LineEnding, PrivateKey};
    use std::borrow::Cow;
    use std::sync::Mutex;
    use tokio::net::TcpStream;

    #[test]
    fn openssh_sha256_fingerprint_matches_decoded_key_and_hex_fallback() {
        let raw_key_blob = b"\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x200123456789abcdef0123456789abcdef";
        let key_b64 = BASE64_STD.encode(raw_key_blob);
        let expected_digest = Sha256::digest(raw_key_blob);
        let expected_openssh = format!("SHA256:{}", BASE64_NO_PAD.encode(expected_digest));
        let expected_hex = format!("SHA256:{:x}", expected_digest);

        assert_eq!(
            verify_server_public_key_pin(&key_b64, &expected_openssh).unwrap(),
            expected_openssh
        );
        assert_eq!(
            verify_server_public_key_pin(&key_b64, &expected_hex).unwrap(),
            expected_openssh
        );
        assert!(verify_server_public_key_pin(&key_b64, "SHA256:wrongpin").is_err());
    }

    #[derive(Clone)]
    struct LoopbackSshServerState {
        require_kbd_interactive: bool,
        expected_user: String,
        expected_password: String,
        allowed_pubkey: Option<PublicKey>,
        recorded_resizes: Arc<Mutex<Vec<(u32, u32)>>>,
    }

    struct LoopbackSshHandler {
        state: LoopbackSshServerState,
        kbd_prompted: bool,
        shell_channels: std::collections::HashSet<ChannelId>,
    }

    #[async_trait]
    impl server::Handler for LoopbackSshHandler {
        type Error = russh::Error;

        async fn auth_password(&mut self, user: &str, password: &str) -> Result<Auth, Self::Error> {
            if self.state.require_kbd_interactive {
                return Ok(Auth::Reject {
                    proceed_with_methods: Some(MethodSet::KEYBOARD_INTERACTIVE),
                });
            }
            if user == self.state.expected_user && password == self.state.expected_password {
                Ok(Auth::Accept)
            } else {
                Ok(Auth::Reject {
                    proceed_with_methods: None,
                })
            }
        }

        async fn auth_keyboard_interactive(
            &mut self,
            user: &str,
            _submethods: &str,
            response: Option<Response<'async_trait>>,
        ) -> Result<Auth, Self::Error> {
            if user != self.state.expected_user {
                return Ok(Auth::Reject {
                    proceed_with_methods: None,
                });
            }
            if !self.kbd_prompted {
                self.kbd_prompted = true;
                let prompts: &'static [(Cow<'static, str>, bool)] =
                    &[(Cow::Borrowed("PAM Password: "), false)];
                return Ok(Auth::Partial {
                    name: Cow::Borrowed("PAM Authentication"),
                    instructions: Cow::Borrowed("Enter system password"),
                    prompts: Cow::Borrowed(prompts),
                });
            }
            if let Some(mut resp) = response {
                if let Some(ans) = resp.next() {
                    if ans.as_ref() == self.state.expected_password.as_bytes() {
                        return Ok(Auth::Accept);
                    }
                }
            }
            Ok(Auth::Reject {
                proceed_with_methods: None,
            })
        }

        async fn auth_publickey(
            &mut self,
            user: &str,
            public_key: &PublicKey,
        ) -> Result<Auth, Self::Error> {
            if user == self.state.expected_user {
                if let Some(allowed) = &self.state.allowed_pubkey {
                    if allowed.public_key_base64() == public_key.public_key_base64() {
                        return Ok(Auth::Accept);
                    }
                }
            }
            Ok(Auth::Reject {
                proceed_with_methods: None,
            })
        }

        async fn channel_open_session(
            &mut self,
            _channel: Channel<ServerMsg>,
            _session: &mut ServerSession,
        ) -> Result<bool, Self::Error> {
            Ok(true)
        }

        async fn pty_request(
            &mut self,
            channel: ChannelId,
            _term: &str,
            col_width: u32,
            row_height: u32,
            _pix_width: u32,
            _pix_height: u32,
            _modes: &[(Pty, u32)],
            session: &mut ServerSession,
        ) -> Result<(), Self::Error> {
            self.state
                .recorded_resizes
                .lock()
                .unwrap()
                .push((col_width, row_height));
            let _ = session.channel_success(channel);
            Ok(())
        }

        async fn shell_request(
            &mut self,
            channel: ChannelId,
            session: &mut ServerSession,
        ) -> Result<(), Self::Error> {
            self.shell_channels.insert(channel);
            let _ = session.channel_success(channel);
            let _ = session.data(channel, CryptoVec::from_slice(b"READY>"));
            Ok(())
        }

        async fn window_change_request(
            &mut self,
            channel: ChannelId,
            col_width: u32,
            row_height: u32,
            _pix_width: u32,
            _pix_height: u32,
            session: &mut ServerSession,
        ) -> Result<(), Self::Error> {
            self.state
                .recorded_resizes
                .lock()
                .unwrap()
                .push((col_width, row_height));
            let _ = session.data(
                channel,
                CryptoVec::from_slice(format!("RESIZED:{col_width}x{row_height}\n").as_bytes()),
            );
            Ok(())
        }

        async fn data(
            &mut self,
            channel: ChannelId,
            data: &[u8],
            session: &mut ServerSession,
        ) -> Result<(), Self::Error> {
            if self.shell_channels.contains(&channel) {
                let mut reply = b"ECHO:".to_vec();
                reply.extend_from_slice(data);
                let _ = session.data(channel, CryptoVec::from_slice(&reply));
            }
            Ok(())
        }

        async fn channel_open_direct_tcpip(
            &mut self,
            channel: Channel<ServerMsg>,
            host_to_connect: &str,
            port_to_connect: u32,
            _originator_address: &str,
            _originator_port: u32,
            _session: &mut ServerSession,
        ) -> Result<bool, Self::Error> {
            let target = format!("{host_to_connect}:{port_to_connect}");
            match TcpStream::connect(&target).await {
                Ok(mut backend) => {
                    tokio::spawn(async move {
                        let mut ch_stream = channel.into_stream();
                        let _ = tokio::io::copy_bidirectional(&mut ch_stream, &mut backend).await;
                    });
                    Ok(true)
                }
                Err(_) => Ok(false),
            }
        }
    }

    async fn spawn_loopback_ssh_server(state: LoopbackSshServerState) -> (u16, AbortHandle) {
        let host_key = PrivateKey::random(&mut OsRng, Algorithm::Ed25519).unwrap();
        let mut cfg = server::Config {
            auth_rejection_time: Duration::from_millis(1),
            auth_rejection_time_initial: Some(Duration::from_millis(1)),
            ..Default::default()
        };
        cfg.keys.push(host_key);
        let cfg = Arc::new(cfg);

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();

        let join = tokio::spawn(async move {
            while let Ok((socket, _)) = listener.accept().await {
                let st = state.clone();
                let c = Arc::clone(&cfg);
                tokio::spawn(async move {
                    let handler = LoopbackSshHandler {
                        state: st,
                        kbd_prompted: false,
                        shell_channels: std::collections::HashSet::new(),
                    };
                    if let Ok(session) = server::run_stream(c, socket, handler).await {
                        let _ = session.await;
                    }
                });
            }
        });

        (port, join.abort_handle())
    }

    #[tokio::test]
    async fn loopback_ssh_password_kbdint_ed25519_pty_resize_forwards_and_port_release() {
        // 1. Spawn a local TCP echo backend to tunnel to via -L and -D
        let backend_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let backend_port = backend_listener.local_addr().unwrap().port();
        let backend_task = tokio::spawn(async move {
            while let Ok((mut s, _)) = backend_listener.accept().await {
                tokio::spawn(async move {
                    let mut buf = [0u8; 256];
                    if let Ok(n) = s.read(&mut buf).await {
                        let mut out = b"TUNNELED:".to_vec();
                        out.extend_from_slice(&buf[..n]);
                        let _ = s.write_all(&out).await;
                        let _ = s.shutdown().await;
                    }
                });
            }
        });

        // Generate an Ed25519 client keypair
        let client_priv = PrivateKey::random(&mut OsRng, Algorithm::Ed25519).unwrap();
        let client_pub: PublicKey = client_priv.public_key().clone();
        let client_pem = client_priv.to_openssh(LineEnding::LF).unwrap().to_string();

        let resizes = Arc::new(Mutex::new(Vec::new()));
        let (ssh_port, ssh_srv_abort) = spawn_loopback_ssh_server(LoopbackSshServerState {
            require_kbd_interactive: false,
            expected_user: "ops".into(),
            expected_password: "s3cret-password".into(),
            allowed_pubkey: Some(client_pub.clone()),
            recorded_resizes: Arc::clone(&resizes),
        })
        .await;

        // A) Password Auth + Shell Echo + PTY Resize + -L + -D + Port Release
        let spec_pw = SshConnectSpec {
            host: "127.0.0.1".into(),
            port: ssh_port,
            username: "ops".into(),
            auth: SshAuthMethod::Password {
                password: "s3cret-password".into(),
            },
            expected_host_fingerprint_sha256: None,
            term_env: "xterm-256color".into(),
            initial_cols: 100,
            initial_rows: 30,
            enable_x11: false,
            keepalive_secs: 0,
            startup_command: None,
        };
        let (sess_pw, mut ch_pw) = ActiveSshClientSession::connect(spec_pw, None).await.unwrap();
        ch_pw.data(&b"ping-shell"[..]).await.unwrap();
        ActiveSshClientSession::notify_pty_resize(&ch_pw, 160, 48)
            .await
            .unwrap();

        let mut shell_out = Vec::new();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
        while tokio::time::Instant::now() < deadline {
            if let Ok(Some(ChannelMsg::Data { data })) =
                tokio::time::timeout(Duration::from_millis(100), ch_pw.wait()).await
            {
                shell_out.extend_from_slice(&data);
                let s = String::from_utf8_lossy(&shell_out);
                if s.contains("READY>") && s.contains("ECHO:ping-shell") && s.contains("RESIZED:160x48") {
                    break;
                }
            }
        }
        let shell_text = String::from_utf8_lossy(&shell_out);
        assert!(shell_text.contains("READY>"));
        assert!(shell_text.contains("ECHO:ping-shell"));
        assert!(shell_text.contains("RESIZED:160x48"));

        // Reserve two free ports for -L and -D
        let tmp_l = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let fwd_l_port = tmp_l.local_addr().unwrap().port();
        drop(tmp_l);

        let tmp_d = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let fwd_d_port = tmp_d.local_addr().unwrap().port();
        drop(tmp_d);

        let abort_l = ActiveSshClientSession::start_local_port_forward(
            sess_pw.shared_handle(),
            "127.0.0.1".into(),
            fwd_l_port,
            "127.0.0.1".into(),
            backend_port,
        )
        .await
        .unwrap();

        let abort_d = ActiveSshClientSession::start_dynamic_socks5_proxy(
            sess_pw.shared_handle(),
            "127.0.0.1".into(),
            fwd_d_port,
        )
        .await
        .unwrap();

        // Test -L local port forward
        {
            let mut l_sock = TcpStream::connect(("127.0.0.1", fwd_l_port)).await.unwrap();
            l_sock.write_all(b"via-local-L").await.unwrap();
            let mut resp = [0u8; 64];
            let n = l_sock.read(&mut resp).await.unwrap();
            assert_eq!(&resp[..n], b"TUNNELED:via-local-L");
        }

        // Test -D SOCKS5 proxy (RFC 1928 domain-type CONNECT to 127.0.0.1:backend_port)
        {
            let mut d_sock = TcpStream::connect(("127.0.0.1", fwd_d_port)).await.unwrap();
            d_sock.write_all(&[0x05, 0x01, 0x00]).await.unwrap();
            let mut greet = [0u8; 2];
            d_sock.read_exact(&mut greet).await.unwrap();
            assert_eq!(greet, [0x05, 0x00]);

            let host_bytes = b"127.0.0.1";
            let mut req = vec![0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8];
            req.extend_from_slice(host_bytes);
            req.extend_from_slice(&backend_port.to_be_bytes());
            d_sock.write_all(&req).await.unwrap();

            let mut reply = [0u8; 10];
            d_sock.read_exact(&mut reply).await.unwrap();
            assert_eq!(reply[0], 0x05);
            assert_eq!(reply[1], 0x00);

            d_sock.write_all(b"via-socks5-D").await.unwrap();
            let mut resp = [0u8; 64];
            let n = d_sock.read(&mut resp).await.unwrap();
            assert_eq!(&resp[..n], b"TUNNELED:via-socks5-D");
        }

        // Abort forwarders and disconnect, then verify ports are immediately released (no EADDRINUSE!)
        abort_l.abort();
        abort_d.abort();
        let _ = sess_pw.disconnect().await;
        tokio::task::yield_now().await;
        tokio::time::sleep(Duration::from_millis(15)).await;

        let rebind_l = TcpListener::bind(("127.0.0.1", fwd_l_port)).await;
        assert!(rebind_l.is_ok(), "Local forward port leaked after abort: {rebind_l:?}");
        drop(rebind_l);

        let rebind_d = TcpListener::bind(("127.0.0.1", fwd_d_port)).await;
        assert!(rebind_d.is_ok(), "SOCKS5 port leaked after abort: {rebind_d:?}");
        drop(rebind_d);

        // B) Ed25519 PrivateKeyPem Auth
        let spec_key = SshConnectSpec {
            host: "127.0.0.1".into(),
            port: ssh_port,
            username: "ops".into(),
            auth: SshAuthMethod::PrivateKeyPem {
                pem: client_pem,
                passphrase: None,
            },
            expected_host_fingerprint_sha256: None,
            term_env: "xterm-256color".into(),
            initial_cols: 80,
            initial_rows: 24,
            enable_x11: false,
            keepalive_secs: 0,
            startup_command: None,
        };
        let (sess_key, _ch_key) = ActiveSshClientSession::connect(spec_key, None).await.unwrap();
        let _ = sess_key.disconnect().await;

        // C) Keyboard-Interactive Fallback Auth (server rejects password auth, requires PAM kbd-int)
        let (kbd_port, kbd_srv_abort) = spawn_loopback_ssh_server(LoopbackSshServerState {
            require_kbd_interactive: true,
            expected_user: "pamuser".into(),
            expected_password: "pam-password-42".into(),
            allowed_pubkey: None,
            recorded_resizes: Arc::new(Mutex::new(Vec::new())),
        })
        .await;

        let spec_kbd = SshConnectSpec {
            host: "127.0.0.1".into(),
            port: kbd_port,
            username: "pamuser".into(),
            auth: SshAuthMethod::Password {
                password: "pam-password-42".into(),
            },
            expected_host_fingerprint_sha256: None,
            term_env: "xterm-256color".into(),
            initial_cols: 80,
            initial_rows: 24,
            enable_x11: false,
            keepalive_secs: 0,
            startup_command: None,
        };
        let (sess_kbd, _ch_kbd) = ActiveSshClientSession::connect(spec_kbd, None).await.unwrap();
        let _ = sess_kbd.disconnect().await;

        ssh_srv_abort.abort();
        kbd_srv_abort.abort();
        backend_task.abort();
    }
}
