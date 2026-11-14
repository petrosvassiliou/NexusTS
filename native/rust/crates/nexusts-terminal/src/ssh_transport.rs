//! Async SSHv2 (`russh`) Transport, Bastion Jump-Host Chaining, Port/X11 Forwarding,
//! and Telnet RFC 854 / RFC 1073 (`IAC` Negotiation) Wire State Machine.

use async_trait::async_trait;
use base64::engine::general_purpose::{STANDARD as BASE64_STD, STANDARD_NO_PAD as BASE64_NO_PAD};
use base64::Engine;
use russh::client::{self, Handle, Msg};
use russh::{Channel, ChannelId, Disconnect};
use russh_keys::key::PrivateKeyWithHashAlg;
use russh_keys::{HashAlg, PublicKey, PublicKeyBase64};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Duration;
use thiserror::Error;
use tokio::net::TcpListener;
use tokio::sync::mpsc;

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

pub struct ActiveSshClientSession {
    handle: Arc<Handle<NexusSshHandler>>,
    pty_channel_id: Option<ChannelId>,
}

impl ActiveSshClientSession {
    async fn authenticate_session(
        spec: &SshConnectSpec,
        bastion: Option<&ActiveSshClientSession>,
    ) -> Result<Arc<Handle<NexusSshHandler>>, TransportError> {
        let mut config = client::Config::default();
        if spec.keepalive_secs > 0 {
            config.keepalive_interval = Some(Duration::from_secs(spec.keepalive_secs));
        }
        let config = Arc::new(config);
        let handler = NexusSshHandler {
            expected_fp: spec.expected_host_fingerprint_sha256.clone(),
            x11_events_tx: None,
        };

        let mut handle = if let Some(jump_host) = bastion {
            let tunnel_channel = jump_host
                .handle
                .channel_open_direct_tcpip(&spec.host, u32::from(spec.port), "127.0.0.1", 0)
                .await?;
            client::connect_stream(config, tunnel_channel.into_stream(), handler).await?
        } else {
            client::connect(config, (spec.host.as_str(), spec.port), handler).await?
        };

        let authenticated = match &spec.auth {
            SshAuthMethod::Password { password } => {
                handle
                    .authenticate_password(&spec.username, password)
                    .await?
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

    /// Connects and authenticates without opening an interactive shell PTY (ideal for SFTP-only or Bastion sessions).
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
                spec.initial_cols,
                spec.initial_rows,
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

    /// Binds a local port `-L bind_addr:bind_port:target_host:target_port` and forwards every socket over `direct-tcpip`.
    pub async fn spawn_local_port_forward(
        handle: Arc<Handle<NexusSshHandler>>,
        bind_addr: String,
        bind_port: u16,
        target_host: String,
        target_port: u16,
    ) -> Result<(), TransportError> {
        let listener = TcpListener::bind((bind_addr.as_str(), bind_port)).await?;
        tokio::spawn(async move {
            while let Ok((mut local_sock, peer)) = listener.accept().await {
                let h = Arc::clone(&handle);
                let th = target_host.clone();
                tokio::spawn(async move {
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
        });
        Ok(())
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
        const IAC: u8 = 255;
        const DONT: u8 = 254;
        const DO: u8 = 253;
        const WONT: u8 = 252;
        const WILL: u8 = 251;
        const SB: u8 = 250;
        const SE: u8 = 240;
        const OPT_TTYPE: u8 = 24;
        const OPT_NAWS: u8 = 31;

        let mut clean = Vec::with_capacity(wire.len());
        let mut replies = Vec::new();
        let mut i = 0;

        while i < wire.len() {
            if wire[i] != IAC {
                clean.push(wire[i]);
                i += 1;
                continue;
            }
            if i + 1 >= wire.len() {
                break;
            }
            let cmd = wire[i + 1];
            match cmd {
                IAC => {
                    clean.push(IAC);
                    i += 2;
                }
                DO if i + 2 < wire.len() => {
                    let opt = wire[i + 2];
                    if opt == OPT_NAWS {
                        replies.extend_from_slice(&[IAC, WILL, OPT_NAWS]);
                        replies.extend_from_slice(&crate::build_telnet_naws_packet(
                            self.cols, self.rows,
                        ));
                    } else if opt == OPT_TTYPE {
                        replies.extend_from_slice(&[IAC, WILL, OPT_TTYPE]);
                    } else {
                        replies.extend_from_slice(&[IAC, WONT, opt]);
                    }
                    i += 3;
                }
                WILL if i + 2 < wire.len() => {
                    let opt = wire[i + 2];
                    // Accept ECHO (1) and SUPPRESS-GO-AHEAD (3)
                    if opt == 1 || opt == 3 {
                        replies.extend_from_slice(&[IAC, DO, opt]);
                    } else {
                        replies.extend_from_slice(&[IAC, DONT, opt]);
                    }
                    i += 3;
                }
                WONT | DONT if i + 2 < wire.len() => {
                    i += 3;
                }
                SB => {
                    let mut end = i + 2;
                    while end + 1 < wire.len() && !(wire[end] == IAC && wire[end + 1] == SE) {
                        end += 1;
                    }
                    if i + 3 < end && wire[i + 2] == OPT_TTYPE && wire[i + 3] == 1 {
                        // IAC SB TTYPE IS <term_type> IAC SE
                        replies.extend_from_slice(&[IAC, SB, OPT_TTYPE, 0]);
                        replies.extend_from_slice(self.term_type.as_bytes());
                        replies.extend_from_slice(&[IAC, SE]);
                    }
                    i = (end + 2).min(wire.len());
                }
                _ => {
                    i += 2;
                }
            }
        }

        (clean, replies)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
