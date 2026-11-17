//! Complete Async Tokio TCP RFC 959 + RFC 2428 (`EPSV`) + RFC 3659 (`MLSD`) FTP Client.

use crate::wire_clients::{
    parse_ftp_epsv_port, parse_ftp_list_output, parse_ftp_mlsd_listing, parse_ftp_pasv_endpoint,
    WireTransferError,
};
use crate::RemoteFileEntry;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::Mutex;

#[derive(Debug, Clone)]
pub struct FtpControlReply {
    pub code: u16,
    pub text: String,
}

struct FtpControlChannel {
    stream: BufReader<TcpStream>,
    peer_ip: IpAddr,
    epsv_supported: bool,
    mlsd_supported: bool,
}

fn is_rfc1918_or_link_local_v4(ip: std::net::Ipv4Addr) -> bool {
    ip.is_private()
        || ip.is_link_local()
        || (ip.octets()[0] == 100 && (ip.octets()[1] & 0xC0) == 64)
}

fn peer_is_public_or_different_family(peer_ip: IpAddr) -> bool {
    match peer_ip {
        IpAddr::V4(v4) => !v4.is_loopback() && !is_rfc1918_or_link_local_v4(v4),
        IpAddr::V6(v6) => !v6.is_loopback(),
    }
}

impl FtpControlChannel {
    fn sanitize_arg(arg: &str) -> String {
        arg.chars().filter(|&c| c != '\r' && c != '\n').collect()
    }

    async fn read_reply(&mut self) -> Result<FtpControlReply, WireTransferError> {
        let mut first_line = String::new();
        let n = tokio::time::timeout(
            Duration::from_secs(20),
            self.stream.read_line(&mut first_line),
        )
        .await
        .map_err(|_| WireTransferError::Ftp("Timed out reading FTP control reply".into()))??;

        if n == 0 {
            return Err(WireTransferError::Ftp(
                "FTP server closed control connection unexpectedly".into(),
            ));
        }

        let trimmed = first_line.trim_end_matches(['\r', '\n']);
        if trimmed.len() < 3 {
            return Err(WireTransferError::Ftp(format!(
                "Malformed FTP reply line: {trimmed}"
            )));
        }

        let code_str = &trimmed[..3];
        let code = code_str
            .parse::<u16>()
            .map_err(|_| WireTransferError::Ftp(format!("Invalid FTP status code: {trimmed}")))?;

        let mut full_text = String::from(trimmed);
        let bytes = trimmed.as_bytes();
        if bytes.len() >= 4 && bytes[3] == b'-' {
            let terminal_prefix = format!("{code_str} ");
            loop {
                let mut next_line = String::new();
                let m = tokio::time::timeout(
                    Duration::from_secs(20),
                    self.stream.read_line(&mut next_line),
                )
                .await
                .map_err(|_| {
                    WireTransferError::Ftp("Timed out reading multi-line FTP reply".into())
                })??;

                if m == 0 {
                    return Err(WireTransferError::Ftp(
                        "EOF during multi-line FTP control reply".into(),
                    ));
                }

                let next_trimmed = next_line.trim_end_matches(['\r', '\n']);
                full_text.push('\n');
                full_text.push_str(next_trimmed);

                if next_trimmed.starts_with(&terminal_prefix) {
                    break;
                }
            }
        }

        Ok(FtpControlReply {
            code,
            text: full_text,
        })
    }

    async fn send_command(&mut self, raw_cmd: &str) -> Result<FtpControlReply, WireTransferError> {
        let clean = Self::sanitize_arg(raw_cmd);
        let wire = format!("{clean}\r\n");
        let writer = self.stream.get_mut();
        writer.write_all(wire.as_bytes()).await?;
        writer.flush().await?;
        self.read_reply().await
    }

    async fn open_passive_data_stream(&mut self) -> Result<TcpStream, WireTransferError> {
        // 1. Try RFC 2428 EPSV first (IPv4 & IPv6 NAT-friendly)
        let mut last_epsv_code = 0u16;
        if self.epsv_supported {
            let epsv_reply = self.send_command("EPSV").await?;
            last_epsv_code = epsv_reply.code;
            if epsv_reply.code == 229 {
                let last_line = epsv_reply.text.lines().last().unwrap_or(&epsv_reply.text);
                let port = parse_ftp_epsv_port(last_line)?;
                let addr = SocketAddr::new(self.peer_ip, port);
                let data_stream =
                    tokio::time::timeout(Duration::from_secs(15), TcpStream::connect(addr))
                        .await
                        .map_err(|_| {
                            WireTransferError::Ftp(format!(
                                "Timed out connecting EPSV data port {addr}"
                            ))
                        })??;
                return Ok(data_stream);
            }
            if matches!(epsv_reply.code, 500 | 501 | 502 | 504 | 522) {
                self.epsv_supported = false;
            }
        }

        // 2. Fall back to RFC 959 PASV
        let pasv_reply = self.send_command("PASV").await?;
        if pasv_reply.code != 227 {
            return Err(WireTransferError::Ftp(format!(
                "Passive mode rejected (EPSV={}, PASV={}): {}",
                last_epsv_code, pasv_reply.code, pasv_reply.text
            )));
        }

        let last_line = pasv_reply.text.lines().last().unwrap_or(&pasv_reply.text);
        let (pasv_ipv4, port) = parse_ftp_pasv_endpoint(last_line)?;

        // Substitute control peer IP if PASV returns 0.0.0.0, 127.0.0.1 on non-loopback peer,
        // or unroutable RFC1918/CGNAT/link-local IP when control peer is public/IPv6
        let pasv_ip = IpAddr::V4(pasv_ipv4);
        let resolved_ip = if pasv_ipv4.is_unspecified()
            || (pasv_ipv4.is_loopback() && !self.peer_ip.is_loopback())
            || (is_rfc1918_or_link_local_v4(pasv_ipv4)
                && peer_is_public_or_different_family(self.peer_ip))
        {
            self.peer_ip
        } else {
            pasv_ip
        };

        let primary_addr = SocketAddr::new(resolved_ip, port);
        match tokio::time::timeout(Duration::from_secs(8), TcpStream::connect(primary_addr)).await {
            Ok(Ok(s)) => Ok(s),
            _ if resolved_ip != self.peer_ip => {
                // Split-horizon / Docker bridge / NAT fallback to control peer IP
                let fallback_addr = SocketAddr::new(self.peer_ip, port);
                let s = tokio::time::timeout(
                    Duration::from_secs(10),
                    TcpStream::connect(fallback_addr),
                )
                .await
                .map_err(|_| {
                    WireTransferError::Ftp(format!(
                        "Timed out connecting PASV data port {primary_addr} (and NAT fallback {fallback_addr})"
                    ))
                })??;
                Ok(s)
            }
            Ok(Err(e)) => Err(e.into()),
            Err(_) => Err(WireTransferError::Ftp(format!(
                "Timed out connecting PASV data port {primary_addr}"
            ))),
        }
    }
}

/// Complete async Tokio TCP FTP client supporting RFC 959, RFC 2428 (`EPSV`), and RFC 3659 (`MLSD`).
pub struct NexusFtpClient {
    inner: Mutex<FtpControlChannel>,
}

impl NexusFtpClient {
    pub async fn connect(
        host: &str,
        port: u16,
        username: &str,
        password: &str,
    ) -> Result<Self, WireTransferError> {
        let effective_port = if port == 0 { 21 } else { port };
        let trimmed_host = host.trim();
        let target = if trimmed_host.contains(':') && !trimmed_host.starts_with('[') {
            format!("[{trimmed_host}]:{effective_port}")
        } else {
            format!("{trimmed_host}:{effective_port}")
        };

        let tcp = tokio::time::timeout(Duration::from_secs(15), TcpStream::connect(&target))
            .await
            .map_err(|_| WireTransferError::Ftp(format!("Connection timed out to {target}")))??;
        let _ = tcp.set_nodelay(true);
        let peer_ip = tcp.peer_addr()?.ip();

        let mut ctrl = FtpControlChannel {
            stream: BufReader::new(tcp),
            peer_ip,
            epsv_supported: true,
            mlsd_supported: true,
        };

        let banner = ctrl.read_reply().await?;
        if banner.code != 220 {
            return Err(WireTransferError::Ftp(format!(
                "Unexpected FTP greeting ({}): {}",
                banner.code, banner.text
            )));
        }

        let user = if username.trim().is_empty() {
            "anonymous"
        } else {
            username
        };
        let user_reply = ctrl.send_command(&format!("USER {user}")).await?;
        match user_reply.code {
            230 => {}
            331 | 332 => {
                let pass_reply = ctrl.send_command(&format!("PASS {password}")).await?;
                if pass_reply.code != 230 && pass_reply.code != 202 {
                    return Err(WireTransferError::Ftp(format!(
                        "FTP authentication failed ({}): {}",
                        pass_reply.code, pass_reply.text
                    )));
                }
            }
            code => {
                return Err(WireTransferError::Ftp(format!(
                    "FTP USER rejected ({code}): {}",
                    user_reply.text
                )));
            }
        }

        // Best-effort UTF-8 negotiation (RFC 2640)
        let _ = ctrl.send_command("OPTS UTF8 ON").await;

        // Always switch to binary image transfer mode (`TYPE I`)
        let type_reply = ctrl.send_command("TYPE I").await?;
        if !(200..300).contains(&type_reply.code) {
            return Err(WireTransferError::Ftp(format!(
                "FTP TYPE I failed ({}): {}",
                type_reply.code, type_reply.text
            )));
        }

        Ok(Self {
            inner: Mutex::new(ctrl),
        })
    }

    pub async fn pwd(&self) -> Result<String, WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let reply = ctrl.send_command("PWD").await?;
        if reply.code != 257 && !(200..300).contains(&reply.code) {
            return Err(WireTransferError::Ftp(format!(
                "FTP PWD failed ({}): {}",
                reply.code, reply.text
            )));
        }
        Ok(extract_quoted_ftp_path(&reply.text).unwrap_or_else(|| "/".into()))
    }

    pub async fn cwd(&self, path: &str) -> Result<(), WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let reply = ctrl.send_command(&format!("CWD {path}")).await?;
        if (200..300).contains(&reply.code) {
            Ok(())
        } else {
            Err(WireTransferError::Ftp(format!(
                "FTP CWD failed ({}): {}",
                reply.code, reply.text
            )))
        }
    }

    pub async fn list_directory(
        &self,
        path: &str,
    ) -> Result<Vec<RemoteFileEntry>, WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let trimmed = path.trim();
        let parent_dir = if trimmed.is_empty() || trimmed == "." {
            let pwd_reply = ctrl.send_command("PWD").await?;
            if (200..300).contains(&pwd_reply.code) {
                extract_quoted_ftp_path(&pwd_reply.text).unwrap_or_else(|| "/".into())
            } else {
                "/".into()
            }
        } else {
            trimmed.to_string()
        };

        // 1. Attempt RFC 3659 MLSD first if supported
        if ctrl.mlsd_supported {
            let mut mlsd_data = ctrl.open_passive_data_stream().await?;
            let mlsd_cmd = if trimmed.is_empty() || trimmed == "." {
                String::from("MLSD")
            } else {
                format!("MLSD {trimmed}")
            };
            let mlsd_reply = ctrl.send_command(&mlsd_cmd).await?;

            if mlsd_reply.code == 125 || mlsd_reply.code == 150 {
                let mut raw = Vec::new();
                tokio::time::timeout(Duration::from_secs(30), mlsd_data.read_to_end(&mut raw))
                    .await
                    .map_err(|_| WireTransferError::Ftp("Timed out reading FTP MLSD data".into()))??;
                drop(mlsd_data);
                let done = ctrl.read_reply().await?;
                if !(200..300).contains(&done.code) {
                    return Err(WireTransferError::Ftp(format!(
                        "FTP MLSD transfer failed ({}): {}",
                        done.code, done.text
                    )));
                }
                let text = String::from_utf8_lossy(&raw);
                return Ok(parse_ftp_mlsd_listing(&parent_dir, &text));
            }

            drop(mlsd_data);
            if matches!(mlsd_reply.code, 500 | 501 | 502 | 503 | 504 | 550 | 450) {
                ctrl.mlsd_supported = false;
            } else {
                return Err(WireTransferError::Ftp(format!(
                    "FTP MLSD rejected ({}): {}",
                    mlsd_reply.code, mlsd_reply.text
                )));
            }
        }

        // 2. Cleanly fall back to RFC 959 LIST (UNIX `ls -la` + Windows/IIS DOS parser)
        let mut prev_pwd: Option<String> = None;
        let use_cwd_for_list = !trimmed.is_empty() && trimmed != "." && trimmed.contains(' ');
        if use_cwd_for_list {
            if let Ok(pwd_rep) = ctrl.send_command("PWD").await {
                if (200..300).contains(&pwd_rep.code) {
                    prev_pwd = extract_quoted_ftp_path(&pwd_rep.text);
                }
            }
            let _ = ctrl.send_command(&format!("CWD {trimmed}")).await;
        }

        let mut list_data = ctrl.open_passive_data_stream().await?;
        let list_cmd = if trimmed.is_empty() || trimmed == "." || use_cwd_for_list {
            String::from("LIST")
        } else {
            format!("LIST {trimmed}")
        };
        let list_reply = ctrl.send_command(&list_cmd).await?;
        if list_reply.code != 125 && list_reply.code != 150 {
            if let Some(prev) = prev_pwd {
                let _ = ctrl.send_command(&format!("CWD {prev}")).await;
            }
            return Err(WireTransferError::Ftp(format!(
                "FTP LIST failed ({}): {}",
                list_reply.code, list_reply.text
            )));
        }

        let mut raw = Vec::new();
        tokio::time::timeout(Duration::from_secs(30), list_data.read_to_end(&mut raw))
            .await
            .map_err(|_| WireTransferError::Ftp("Timed out reading FTP LIST data".into()))??;
        drop(list_data);

        let done = ctrl.read_reply().await?;
        if let Some(prev) = prev_pwd {
            let _ = ctrl.send_command(&format!("CWD {prev}")).await;
        }
        if !(200..300).contains(&done.code) {
            return Err(WireTransferError::Ftp(format!(
                "FTP LIST completion failed ({}): {}",
                done.code, done.text
            )));
        }

        let text = String::from_utf8_lossy(&raw);
        Ok(parse_ftp_list_output(&parent_dir, &text))
    }

    pub async fn download_bytes(&self, remote_path: &str) -> Result<Vec<u8>, WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let mut data_stream = ctrl.open_passive_data_stream().await?;
        let retr = ctrl.send_command(&format!("RETR {remote_path}")).await?;
        if retr.code != 125 && retr.code != 150 {
            return Err(WireTransferError::Ftp(format!(
                "FTP RETR failed ({}): {}",
                retr.code, retr.text
            )));
        }

        let mut payload = Vec::new();
        tokio::time::timeout(
            Duration::from_secs(120),
            data_stream.read_to_end(&mut payload),
        )
        .await
        .map_err(|_| WireTransferError::Ftp("Timed out reading FTP RETR stream".into()))??;
        drop(data_stream);

        let done = ctrl.read_reply().await?;
        if !(200..300).contains(&done.code) {
            return Err(WireTransferError::Ftp(format!(
                "FTP RETR completion failed ({}): {}",
                done.code, done.text
            )));
        }
        Ok(payload)
    }

    pub async fn upload_bytes(
        &self,
        remote_path: &str,
        data: &[u8],
    ) -> Result<u64, WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let mut data_stream = ctrl.open_passive_data_stream().await?;
        let stor = ctrl.send_command(&format!("STOR {remote_path}")).await?;
        if stor.code != 125 && stor.code != 150 {
            return Err(WireTransferError::Ftp(format!(
                "FTP STOR failed ({}): {}",
                stor.code, stor.text
            )));
        }

        tokio::time::timeout(Duration::from_secs(120), async {
            data_stream.write_all(data).await?;
            data_stream.flush().await?;
            let _ = data_stream.shutdown().await;
            Ok::<(), std::io::Error>(())
        })
        .await
        .map_err(|_| WireTransferError::Ftp("Timed out writing FTP STOR stream".into()))??;
        drop(data_stream);

        let done = ctrl.read_reply().await?;
        if !(200..300).contains(&done.code) {
            return Err(WireTransferError::Ftp(format!(
                "FTP STOR completion failed ({}): {}",
                done.code, done.text
            )));
        }
        Ok(data.len() as u64)
    }

    pub async fn create_directory(&self, remote_path: &str) -> Result<(), WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let reply = ctrl.send_command(&format!("MKD {remote_path}")).await?;
        if (200..300).contains(&reply.code) {
            Ok(())
        } else {
            Err(WireTransferError::Ftp(format!(
                "FTP MKD failed ({}): {}",
                reply.code, reply.text
            )))
        }
    }

    pub fn remove_entry<'a>(
        &'a self,
        remote_path: &'a str,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<(), WireTransferError>> + Send + 'a>> {
        Box::pin(async move {
            {
                let mut ctrl = self.inner.lock().await;
                let dele = ctrl.send_command(&format!("DELE {remote_path}")).await?;
                if (200..300).contains(&dele.code) {
                    return Ok(());
                }
                let rmd = ctrl.send_command(&format!("RMD {remote_path}")).await?;
                if (200..300).contains(&rmd.code) {
                    return Ok(());
                }
            }
            let children = self.list_directory(remote_path).await?;
            for child in children {
                self.remove_entry(&child.full_path).await?;
            }
            let mut ctrl = self.inner.lock().await;
            let rmd = ctrl.send_command(&format!("RMD {remote_path}")).await?;
            if (200..300).contains(&rmd.code) {
                Ok(())
            } else {
                Err(WireTransferError::Ftp(format!(
                    "FTP RMD failed ({}): {}",
                    rmd.code, rmd.text
                )))
            }
        })
    }

    pub async fn rename(&self, old_path: &str, new_path: &str) -> Result<(), WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let rnfr = ctrl.send_command(&format!("RNFR {old_path}")).await?;
        if rnfr.code != 350 {
            return Err(WireTransferError::Ftp(format!(
                "FTP RNFR failed ({}): {}",
                rnfr.code, rnfr.text
            )));
        }
        let rnto = ctrl.send_command(&format!("RNTO {new_path}")).await?;
        if (200..300).contains(&rnto.code) {
            Ok(())
        } else {
            Err(WireTransferError::Ftp(format!(
                "FTP RNTO failed ({}): {}",
                rnto.code, rnto.text
            )))
        }
    }

    pub async fn chmod(&self, remote_path: &str, mode_octal: u32) -> Result<(), WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let reply = ctrl
            .send_command(&format!("SITE CHMOD {:o} {remote_path}", mode_octal & 0o7777))
            .await?;
        if (200..300).contains(&reply.code) {
            Ok(())
        } else {
            Err(WireTransferError::Ftp(format!(
                "FTP SITE CHMOD failed ({}): {}",
                reply.code, reply.text
            )))
        }
    }

    pub async fn disconnect(&self) -> Result<(), WireTransferError> {
        let mut ctrl = self.inner.lock().await;
        let _ = ctrl.send_command("QUIT").await;
        let _ = ctrl.stream.get_mut().shutdown().await;
        Ok(())
    }
}

fn extract_quoted_ftp_path(reply_text: &str) -> Option<String> {
    let start = reply_text.find('"')? + 1;
    let bytes = reply_text.as_bytes();
    let mut i = start;
    let mut out = String::new();
    while i < bytes.len() {
        if bytes[i] == b'"' {
            if i + 1 < bytes.len() && bytes[i + 1] == b'"' {
                out.push('"');
                i += 2;
                continue;
            }
            return Some(out);
        }
        out.push(bytes[i] as char);
        i += 1;
    }
    None
}
