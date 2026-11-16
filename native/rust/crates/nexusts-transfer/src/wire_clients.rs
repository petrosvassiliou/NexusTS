//! Async SFTP (`russh-sftp`), Real SCP + SSH Exec Client (`NexusScpClient`),
//! and UNIX `ls -la` / FTP `MLSD` / DOS Directory Listing Parsers.

use crate::RemoteFileEntry;
use nexusts_terminal::ssh_transport::{ActiveSshClientSession, SshConnectSpec, TransportError};
use russh::ChannelMsg;
use russh_sftp::client::SftpSession;
use russh_sftp::protocol::FileAttributes;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

#[derive(Debug, Error)]
pub enum WireTransferError {
    #[error("SSH transport error: {0}")]
    Transport(#[from] TransportError),
    #[error("SSH channel error: {0}")]
    Ssh(#[from] russh::Error),
    #[error("SFTP error: {0}")]
    Sftp(#[from] russh_sftp::client::error::Error),
    #[error("SCP protocol error: {0}")]
    Scp(String),
    #[error("FTP protocol error: {0}")]
    Ftp(String),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
}

/// Escapes an arbitrary POSIX path for safe single-quoted shell execution (`'...'`).
pub fn shell_escape_posix(arg: &str) -> String {
    let mut out = String::with_capacity(arg.len() + 4);
    out.push('\'');
    for ch in arg.chars() {
        if ch == '\'' {
            out.push_str("'\\''");
        } else {
            out.push(ch);
        }
    }
    out.push('\'');
    out
}

/// Real SFTP v3 session client powered by `russh-sftp`.
pub struct NexusSftpClient {
    session: SftpSession,
}

impl NexusSftpClient {
    pub async fn from_subsystem_stream<S>(stream: S) -> Result<Self, WireTransferError>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let session = SftpSession::new(stream).await?;
        Ok(Self { session })
    }

    pub async fn pwd(&self) -> Result<String, WireTransferError> {
        let dir = self.session.canonicalize(".").await?;
        Ok(dir)
    }

    pub async fn list_directory(&self, path: &str) -> Result<Vec<RemoteFileEntry>, WireTransferError> {
        let read_dir = self.session.read_dir(path).await?;
        let base = path.trim_end_matches('/');
        let mut entries = Vec::new();

        for entry in read_dir {
            let name = entry.file_name();
            if name == "." || name == ".." {
                continue;
            }
            let meta = entry.metadata();
            let mode = meta.permissions.unwrap_or(0o644) & 0o7777;
            let is_dir = entry.file_type().is_dir();
            let is_symlink = entry.file_type().is_symlink();
            let full_path = if base.is_empty() {
                format!("/{name}")
            } else {
                format!("{base}/{name}")
            };

            entries.push(RemoteFileEntry {
                name,
                full_path,
                is_directory: is_dir,
                is_symlink,
                size_bytes: meta.size.unwrap_or(0),
                unix_mode: mode,
                modified_epoch_sec: u64::from(meta.mtime.unwrap_or(0)),
                owner: meta.uid.map(|u| u.to_string()).unwrap_or_else(|| "0".into()),
            });
        }
        Ok(entries)
    }

    pub async fn create_directory(&self, path: &str) -> Result<(), WireTransferError> {
        self.session.create_dir(path).await?;
        Ok(())
    }

    pub async fn remove_entry(&self, path: &str, is_dir: bool) -> Result<(), WireTransferError> {
        if is_dir {
            self.session.remove_dir(path).await?;
        } else if self.session.remove_file(path).await.is_err() {
            self.session.remove_dir(path).await?;
        }
        Ok(())
    }

    pub async fn rename(&self, old_path: &str, new_path: &str) -> Result<(), WireTransferError> {
        self.session.rename(old_path, new_path).await?;
        Ok(())
    }

    pub async fn rename_entry(&self, old_path: &str, new_path: &str) -> Result<(), WireTransferError> {
        self.rename(old_path, new_path).await
    }

    pub async fn chmod(&self, remote_path: &str, mode_octal: u32) -> Result<(), WireTransferError> {
        let attrs = FileAttributes {
            size: None,
            uid: None,
            user: None,
            gid: None,
            group: None,
            permissions: Some(mode_octal & 0o7777),
            atime: None,
            mtime: None,
        };
        self.session.set_metadata(remote_path, attrs).await?;
        Ok(())
    }

    pub async fn upload_bytes(&self, remote_path: &str, content: &[u8]) -> Result<u64, WireTransferError> {
        let mut file = self.session.create(remote_path).await?;
        file.write_all(content).await?;
        file.flush().await?;
        Ok(content.len() as u64)
    }

    pub async fn download_bytes(&self, remote_path: &str) -> Result<Vec<u8>, WireTransferError> {
        let mut file = self.session.open(remote_path).await?;
        let mut out = Vec::new();
        file.read_to_end(&mut out).await?;
        Ok(out)
    }

    pub async fn close(&self) -> Result<(), WireTransferError> {
        self.session.close().await?;
        Ok(())
    }
}

/// Complete live SCP (`scp -t` / `scp -f`) + SSH `exec` directory management client powered by `russh`.
pub struct NexusScpClient {
    ssh: ActiveSshClientSession,
    bastion: Option<ActiveSshClientSession>,
}

impl NexusScpClient {
    /// Connects via `russh` supporting password, keyboard-interactive PAM fallback,
    /// PEM private key (+ optional passphrase), and optional jump-host bastion tunneling.
    pub async fn connect(
        spec: SshConnectSpec,
        bastion_spec: Option<SshConnectSpec>,
    ) -> Result<Self, WireTransferError> {
        let bastion = if let Some(bspec) = bastion_spec {
            Some(ActiveSshClientSession::connect_without_shell(bspec, None).await?)
        } else {
            None
        };
        let ssh = ActiveSshClientSession::connect_without_shell(spec, bastion.as_ref()).await?;
        Ok(Self { ssh, bastion })
    }

    async fn exec_command(&self, cmd: &str) -> Result<(u32, Vec<u8>, Vec<u8>), WireTransferError> {
        let mut channel = self.ssh.shared_handle().channel_open_session().await?;
        channel.exec(true, cmd).await?;

        let mut stdout = Vec::new();
        let mut stderr = Vec::new();
        let mut exit_status: u32 = 0;

        while let Some(msg) = channel.wait().await {
            match msg {
                ChannelMsg::Data { data } => stdout.extend_from_slice(&data),
                ChannelMsg::ExtendedData { data, .. } => stderr.extend_from_slice(&data),
                ChannelMsg::ExitStatus { exit_status: code } => exit_status = code,
                ChannelMsg::Eof | ChannelMsg::Close => {}
                _ => {}
            }
        }

        Ok((exit_status, stdout, stderr))
    }

    fn ensure_zero_exit(
        action: &str,
        status: u32,
        stderr: &[u8],
    ) -> Result<(), WireTransferError> {
        if status == 0 {
            Ok(())
        } else {
            let detail = String::from_utf8_lossy(stderr).trim().to_string();
            Err(WireTransferError::Scp(if detail.is_empty() {
                format!("{action} exited with status {status}")
            } else {
                format!("{action} failed ({status}): {detail}")
            }))
        }
    }

    pub async fn pwd(&self) -> Result<String, WireTransferError> {
        let (code, stdout, stderr) = self.exec_command("pwd").await?;
        Self::ensure_zero_exit("pwd", code, &stderr)?;
        let path = String::from_utf8_lossy(&stdout).trim().to_string();
        Ok(if path.is_empty() { "/".into() } else { path })
    }

    pub async fn list_directory(
        &self,
        remote_path: &str,
    ) -> Result<Vec<RemoteFileEntry>, WireTransferError> {
        let target = if remote_path.trim().is_empty() {
            "."
        } else {
            remote_path
        };
        let cmd = format!("LC_ALL=C ls -la {}", shell_escape_posix(target));
        let (code, stdout, stderr) = self.exec_command(&cmd).await?;
        Self::ensure_zero_exit("ls -la", code, &stderr)?;
        let listing = String::from_utf8_lossy(&stdout);
        Ok(parse_unix_ls_la(target, &listing))
    }

    pub async fn upload_bytes(
        &self,
        remote_path: &str,
        content: &[u8],
        mode: u32,
    ) -> Result<u64, WireTransferError> {
        let cmd = format!("scp -t {}", shell_escape_posix(remote_path));
        let channel = self.ssh.shared_handle().channel_open_session().await?;
        channel.exec(true, cmd).await?;
        let mut stream = channel.into_stream();

        // Wait for remote `scp -t` initial ready NUL byte
        expect_scp_zero_ack(&mut stream).await?;
        scp_send_single_file(
            &mut stream,
            remote_path,
            if mode == 0 { 0o644 } else { mode },
            content,
        )
        .await?;
        let _ = stream.shutdown().await;
        Ok(content.len() as u64)
    }

    pub async fn download_bytes(&self, remote_path: &str) -> Result<Vec<u8>, WireTransferError> {
        let cmd = format!("scp -f {}", shell_escape_posix(remote_path));
        let channel = self.ssh.shared_handle().channel_open_session().await?;
        channel.exec(true, cmd).await?;
        let mut stream = channel.into_stream();
        scp_receive_single_file(&mut stream).await
    }

    pub async fn create_directory(&self, remote_path: &str) -> Result<(), WireTransferError> {
        let cmd = format!("mkdir -p {}", shell_escape_posix(remote_path));
        let (code, _, stderr) = self.exec_command(&cmd).await?;
        Self::ensure_zero_exit("mkdir -p", code, &stderr)
    }

    pub async fn remove_entry(&self, remote_path: &str) -> Result<(), WireTransferError> {
        if remote_path.trim().is_empty() || remote_path.trim() == "/" {
            return Err(WireTransferError::Scp(
                "Refusing to remove root directory".into(),
            ));
        }
        let cmd = format!("rm -rf {}", shell_escape_posix(remote_path));
        let (code, _, stderr) = self.exec_command(&cmd).await?;
        Self::ensure_zero_exit("rm -rf", code, &stderr)
    }

    pub async fn rename(&self, old_path: &str, new_path: &str) -> Result<(), WireTransferError> {
        let cmd = format!(
            "mv -f {} {}",
            shell_escape_posix(old_path),
            shell_escape_posix(new_path)
        );
        let (code, _, stderr) = self.exec_command(&cmd).await?;
        Self::ensure_zero_exit("mv", code, &stderr)
    }

    pub async fn chmod(
        &self,
        remote_path: &str,
        mode_octal: u32,
    ) -> Result<(), WireTransferError> {
        let cmd = format!(
            "chmod {:o} {}",
            mode_octal & 0o7777,
            shell_escape_posix(remote_path)
        );
        let (code, _, stderr) = self.exec_command(&cmd).await?;
        Self::ensure_zero_exit("chmod", code, &stderr)
    }

    pub async fn disconnect(&self) -> Result<(), WireTransferError> {
        let _ = self.ssh.disconnect().await;
        if let Some(b) = &self.bastion {
            let _ = b.disconnect().await;
        }
        Ok(())
    }
}

/// Encodes an SCP sink header (`C<octal_mode> <length> <filename>\n`) and streams payload + `\0` ack.
pub async fn scp_send_single_file<S>(
    stream: &mut S,
    filename: &str,
    unix_mode: u32,
    payload: &[u8],
) -> Result<(), WireTransferError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let clean_name = filename
        .rsplit('/')
        .next()
        .filter(|s| !s.is_empty())
        .unwrap_or("file");
    let header = format!(
        "C{:04o} {} {}\n",
        unix_mode & 0o7777,
        payload.len(),
        clean_name
    );
    stream.write_all(header.as_bytes()).await?;
    stream.flush().await?;
    expect_scp_zero_ack(stream).await?;

    stream.write_all(payload).await?;
    stream.write_u8(0).await?;
    stream.flush().await?;
    expect_scp_zero_ack(stream).await
}

/// Executes the client side of `scp -f` source mode:
/// sends `\0`, reads `C<mode> <size> <filename>\n`, sends `\0`, reads `<size>` bytes + status byte, sends `\0`.
pub async fn scp_receive_single_file<S>(stream: &mut S) -> Result<Vec<u8>, WireTransferError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    stream.write_u8(0).await?;
    stream.flush().await?;

    let file_size = loop {
        let first = stream.read_u8().await?;
        if first == 1 || first == 2 {
            let msg = read_scp_line(stream).await?;
            return Err(WireTransferError::Scp(msg.trim().to_string()));
        }
        let rest = read_scp_line(stream).await?;
        let full_line = format!("{}{}", first as char, rest);
        let trimmed = full_line.trim_end_matches(['\r', '\n']);

        if trimmed.starts_with('T') {
            // Optional modification timestamp header (`T<mtime> 0 <atime> 0\n`)
            stream.write_u8(0).await?;
            stream.flush().await?;
            continue;
        }
        if let Some(after_c) = trimmed.strip_prefix('C') {
            // Format: <4-digit-mode> <size> <filename>
            let mut parts = after_c.splitn(3, ' ');
            let _mode_str = parts
                .next()
                .ok_or_else(|| WireTransferError::Scp("Malformed SCP C-header mode".into()))?;
            let size_str = parts
                .next()
                .ok_or_else(|| WireTransferError::Scp("Malformed SCP C-header size".into()))?;
            let parsed_size = size_str
                .parse::<usize>()
                .map_err(|_| WireTransferError::Scp(format!("Invalid SCP size: {size_str}")))?;
            break parsed_size;
        }
        return Err(WireTransferError::Scp(format!(
            "Unexpected SCP source header: {trimmed}"
        )));
    };

    // Acknowledge header so remote `scp -f` streams the raw file bytes
    stream.write_u8(0).await?;
    stream.flush().await?;

    let mut data = vec![0u8; file_size];
    stream.read_exact(&mut data).await?;
    expect_scp_zero_ack(stream).await?;

    let _ = stream.write_u8(0).await;
    let _ = stream.flush().await;
    Ok(data)
}

async fn read_scp_line<S: AsyncRead + Unpin>(stream: &mut S) -> Result<String, WireTransferError> {
    let mut buf = Vec::with_capacity(128);
    loop {
        let b = stream.read_u8().await?;
        buf.push(b);
        if b == b'\n' || buf.len() >= 4096 {
            break;
        }
    }
    Ok(String::from_utf8_lossy(&buf).to_string())
}

async fn expect_scp_zero_ack<S: AsyncRead + Unpin>(
    stream: &mut S,
) -> Result<(), WireTransferError> {
    let code = stream.read_u8().await?;
    if code == 0 {
        Ok(())
    } else if code == 1 || code == 2 {
        let msg = read_scp_line(stream).await.unwrap_or_default();
        Err(WireTransferError::Scp(format!(
            "SCP remote error ({code}): {}",
            msg.trim()
        )))
    } else {
        Err(WireTransferError::Scp(format!(
            "SCP remote returned status {code}"
        )))
    }
}

/// Parses a POSIX `rwxr-xr-x` permission string (10+ chars including leading file-type char) into octal mode bits.
pub fn parse_rwx_mode(perm: &str) -> Option<(bool, bool, u32)> {
    let bytes = perm.as_bytes();
    if bytes.len() < 10 {
        return None;
    }
    let kind = bytes[0];
    if !matches!(kind, b'-' | b'd' | b'l' | b'b' | b'c' | b'p' | b's') {
        return None;
    }
    let is_dir = kind == b'd';
    let is_symlink = kind == b'l';
    let mut mode = 0u32;

    if bytes[1] == b'r' {
        mode |= 0o400;
    }
    if bytes[2] == b'w' {
        mode |= 0o200;
    }
    match bytes[3] {
        b'x' => mode |= 0o100,
        b's' => mode |= 0o4100,
        b'S' => mode |= 0o4000,
        _ => {}
    }

    if bytes[4] == b'r' {
        mode |= 0o040;
    }
    if bytes[5] == b'w' {
        mode |= 0o020;
    }
    match bytes[6] {
        b'x' => mode |= 0o010,
        b's' => mode |= 0o2010,
        b'S' => mode |= 0o2000,
        _ => {}
    }

    if bytes[7] == b'r' {
        mode |= 0o004;
    }
    if bytes[8] == b'w' {
        mode |= 0o002;
    }
    match bytes[9] {
        b'x' => mode |= 0o001,
        b't' => mode |= 0o1001,
        b'T' => mode |= 0o1000,
        _ => {}
    }

    Some((is_dir, is_symlink, mode))
}

fn next_ws_token(s: &str) -> Option<(&str, &str)> {
    let trimmed = s.trim_start_matches([' ', '\t']);
    if trimmed.is_empty() {
        return None;
    }
    match trimmed.find([' ', '\t']) {
        Some(idx) => Some((&trimmed[..idx], &trimmed[idx..])),
        None => Some((trimmed, "")),
    }
}

fn is_month_abbrev(tok: &str) -> bool {
    matches!(
        tok.to_ascii_lowercase().as_str(),
        "jan" | "feb" | "mar" | "apr" | "may" | "jun" | "jul" | "aug" | "sep" | "oct" | "nov" | "dec"
    )
}

/// Parses a single UNIX `ls -la` line into [`RemoteFileEntry`], preserving spaces inside filenames.
pub fn parse_unix_ls_la_line(parent_dir: &str, raw_line: &str) -> Option<RemoteFileEntry> {
    let line = raw_line.trim_end_matches(['\r', '\n']);
    let trimmed = line.trim_start();
    if trimmed.is_empty() || trimmed.starts_with("total ") {
        return None;
    }

    let (perm_tok, rest) = next_ws_token(trimmed)?;
    let (is_dir, is_symlink, unix_mode) = parse_rwx_mode(perm_tok)?;
    let (_links_tok, rest) = next_ws_token(rest)?;
    let (owner_tok, rest) = next_ws_token(rest)?;
    let (_group_tok, rest) = next_ws_token(rest)?;
    let (size_tok, rest) = next_ws_token(rest)?;
    let size_bytes = size_tok.parse::<u64>().ok()?;

    let (date1, rest_after_d1) = next_ws_token(rest)?;
    let name_remainder = if is_month_abbrev(date1) {
        // Traditional 3-column date: `Nov 15 14:20` or `Nov 15 2025`
        let (_day, r2) = next_ws_token(rest_after_d1)?;
        let (_time_or_yr, r3) = next_ws_token(r2)?;
        r3
    } else {
        // ISO 2-column date: `2026-11-15 14:20`
        let (_time_tok, r2) = next_ws_token(rest_after_d1)?;
        r2
    };

    // Strip exactly one leading whitespace run before filename (preserving internal spaces)
    let raw_name = name_remainder.trim_start_matches([' ', '\t']);
    if raw_name.is_empty() {
        return None;
    }

    let clean_name = if is_symlink {
        raw_name
            .split_once(" -> ")
            .map(|(link_name, _)| link_name)
            .unwrap_or(raw_name)
    } else {
        raw_name
    };

    if clean_name == "." || clean_name == ".." {
        return None;
    }

    let base = parent_dir.trim_end_matches('/');
    let full_path = if base.is_empty() || base == "." {
        if base == "." {
            clean_name.to_string()
        } else {
            format!("/{clean_name}")
        }
    } else {
        format!("{base}/{clean_name}")
    };

    Some(RemoteFileEntry {
        name: clean_name.to_string(),
        full_path,
        is_directory: is_dir,
        is_symlink,
        size_bytes,
        unix_mode,
        modified_epoch_sec: 0,
        owner: owner_tok.to_string(),
    })
}

/// Parses multi-line UNIX `ls -la` output into [`RemoteFileEntry`] items.
pub fn parse_unix_ls_la(parent_dir: &str, ls_output: &str) -> Vec<RemoteFileEntry> {
    ls_output
        .lines()
        .filter_map(|line| parse_unix_ls_la_line(parent_dir, line))
        .collect()
}

/// Parses a Windows / IIS DOS FTP `LIST` line (`MM-DD-YY  hh:mmAM  <DIR>|size  name`).
pub fn parse_dos_ftp_list_line(parent_dir: &str, raw_line: &str) -> Option<RemoteFileEntry> {
    let line = raw_line.trim_end_matches(['\r', '\n']);
    let (date_tok, rest) = next_ws_token(line)?;
    // Expect MM-DD-YY or MM-DD-YYYY
    let date_parts: Vec<&str> = date_tok.split('-').collect();
    if date_parts.len() != 3 || !date_parts.iter().all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())) {
        return None;
    }
    let (_time_tok, rest) = next_ws_token(rest)?;
    let (dir_or_size, rest) = next_ws_token(rest)?;
    let (is_directory, size_bytes, unix_mode) = if dir_or_size.eq_ignore_ascii_case("<DIR>") {
        (true, 0u64, 0o755u32)
    } else {
        let sz = dir_or_size.parse::<u64>().ok()?;
        (false, sz, 0o644u32)
    };

    let name = rest.trim_start_matches([' ', '\t']);
    if name.is_empty() || name == "." || name == ".." {
        return None;
    }

    let base = parent_dir.trim_end_matches('/');
    let full_path = if base.is_empty() {
        format!("/{name}")
    } else {
        format!("{base}/{name}")
    };

    Some(RemoteFileEntry {
        name: name.to_string(),
        full_path,
        is_directory,
        is_symlink: false,
        size_bytes,
        unix_mode,
        modified_epoch_sec: 0,
        owner: String::from("ftp"),
    })
}

/// Parses FTP `LIST` output supporting both UNIX `ls -la` and Windows/IIS MS-DOS formats.
pub fn parse_ftp_list_output(parent_dir: &str, list_body: &str) -> Vec<RemoteFileEntry> {
    list_body
        .lines()
        .filter_map(|line| {
            parse_unix_ls_la_line(parent_dir, line)
                .or_else(|| parse_dos_ftp_list_line(parent_dir, line))
        })
        .collect()
}

/// Parses RFC 3659 FTP/FTPS `MLSD` machine-readable directory listing lines into [`RemoteFileEntry`] items.
/// Example line: `type=file;size=4096;modify=20261112183000;UNIX.mode=0644; release.tar.gz`
pub fn parse_ftp_mlsd_listing(parent_dir: &str, mlsd_body: &str) -> Vec<RemoteFileEntry> {
    let base = parent_dir.trim_end_matches('/');
    let mut out = Vec::new();

    for raw_line in mlsd_body.lines() {
        let line = raw_line.trim_end_matches(['\r', '\n']).trim();
        if line.is_empty() {
            continue;
        }
        let Some((facts_str, name_part)) = line.split_once(' ') else {
            continue;
        };
        let name = name_part.trim().to_string();
        if name == "." || name == ".." {
            continue;
        }

        let mut is_dir = false;
        let mut is_symlink = false;
        let mut is_cdir_or_pdir = false;
        let mut size_bytes = 0u64;
        let mut unix_mode: Option<u32> = None;

        for fact in facts_str.split(';').filter(|f| !f.is_empty()) {
            if let Some((k, v)) = fact.split_once('=') {
                match k.to_ascii_lowercase().as_str() {
                    "type" => {
                        if v.eq_ignore_ascii_case("cdir") || v.eq_ignore_ascii_case("pdir") {
                            is_cdir_or_pdir = true;
                        }
                        is_dir = v.eq_ignore_ascii_case("dir") || v.eq_ignore_ascii_case("pdir");
                        is_symlink = v.to_ascii_lowercase().starts_with("os.unix=symlink");
                    }
                    "size" | "sizd" => size_bytes = v.parse::<u64>().unwrap_or(0),
                    "unix.mode" => unix_mode = u32::from_str_radix(v, 8).ok(),
                    _ => {}
                }
            }
        }

        if is_cdir_or_pdir {
            continue;
        }

        let resolved_mode = unix_mode.unwrap_or(if is_dir { 0o755 } else { 0o644 });

        let full_path = if base.is_empty() {
            format!("/{name}")
        } else {
            format!("{base}/{name}")
        };

        out.push(RemoteFileEntry {
            name,
            full_path,
            is_directory: is_dir,
            is_symlink,
            size_bytes,
            unix_mode: resolved_mode,
            modified_epoch_sec: 0,
            owner: String::from("ftp"),
        });
    }

    out
}

/// Parses an FTP `229 Entering Extended Passive Mode (|||port|)` response to extract the data port.
pub fn parse_ftp_epsv_port(response_line: &str) -> Result<u16, WireTransferError> {
    if let Some(idx) = response_line.find("(|||") {
        let rest = &response_line[idx + 4..];
        let end = rest
            .find('|')
            .ok_or_else(|| WireTransferError::Ftp("Malformed EPSV port".into()))?;
        return rest[..end]
            .parse::<u16>()
            .map_err(|_| WireTransferError::Ftp("Invalid EPSV port integer".into()));
    }

    // RFC 2428 §2 generic delimiter support: `(<d><d><d><tcp-port><d>)`
    let open = response_line
        .find('(')
        .ok_or_else(|| WireTransferError::Ftp("Missing EPSV '('".into()))?;
    let close = response_line[open..]
        .find(')')
        .map(|i| open + i)
        .ok_or_else(|| WireTransferError::Ftp("Missing EPSV ')'".into()))?;
    let inside = &response_line[open + 1..close];
    let bytes = inside.as_bytes();
    if bytes.len() >= 5 && bytes[0] == bytes[1] && bytes[1] == bytes[2] && bytes[bytes.len() - 1] == bytes[0] {
        let port_str = &inside[3..inside.len() - 1];
        return port_str
            .parse::<u16>()
            .map_err(|_| WireTransferError::Ftp("Invalid EPSV port integer".into()));
    }

    Err(WireTransferError::Ftp("Missing EPSV delimiter".into()))
}

/// Parses an FTP `227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)` response into `(Ipv4Addr, port)`.
pub fn parse_ftp_pasv_endpoint(
    response_line: &str,
) -> Result<(std::net::Ipv4Addr, u16), WireTransferError> {
    let open = response_line
        .find('(')
        .ok_or_else(|| WireTransferError::Ftp("Missing PASV '('".into()))?;
    let close = response_line[open..]
        .find(')')
        .map(|i| open + i)
        .ok_or_else(|| WireTransferError::Ftp("Missing PASV ')'".into()))?;
    let nums: Vec<u8> = response_line[open + 1..close]
        .split(',')
        .map(|s| s.trim().parse::<u8>())
        .collect::<Result<Vec<u8>, _>>()
        .map_err(|_| WireTransferError::Ftp("Malformed PASV tuple".into()))?;
    if nums.len() != 6 {
        return Err(WireTransferError::Ftp("PASV tuple must have 6 octets".into()));
    }
    let ip = std::net::Ipv4Addr::new(nums[0], nums[1], nums[2], nums[3]);
    let port = (u16::from(nums[4]) << 8) | u16::from(nums[5]);
    Ok((ip, port))
}
