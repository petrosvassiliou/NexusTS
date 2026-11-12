//! Async SFTP (`russh-sftp`), SCP Wire Protocol (`C0644`), and FTP/FTPS Directory & Stream Parsers.

use crate::RemoteFileEntry;
use russh_sftp::client::SftpSession;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

#[derive(Debug, Error)]
pub enum WireTransferError {
    #[error("SFTP error: {0}")]
    Sftp(#[from] russh_sftp::client::error::Error),
    #[error("SCP protocol error: {0}")]
    Scp(String),
    #[error("FTP protocol error: {0}")]
    Ftp(String),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
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

    pub async fn list_directory(&self, path: &str) -> Result<Vec<RemoteFileEntry>, WireTransferError> {
        let read_dir = self.session.read_dir(path).await?;
        let base = path.trim_end_matches('/');
        let mut entries = Vec::new();

        for entry in read_dir {
            let name = entry.file_name();
            if name == "." {
                continue;
            }
            let meta = entry.metadata();
            let mode = meta.permissions.unwrap_or(0o644);
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
        } else {
            self.session.remove_file(path).await?;
        }
        Ok(())
    }

    pub async fn rename_entry(&self, old_path: &str, new_path: &str) -> Result<(), WireTransferError> {
        self.session.rename(old_path, new_path).await?;
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
    let clean_name = filename.rsplit('/').next().unwrap_or(filename);
    let header = format!("C{:04o} {} {}\n", unix_mode & 0o7777, payload.len(), clean_name);
    stream.write_all(header.as_bytes()).await?;
    stream.flush().await?;
    expect_scp_zero_ack(stream).await?;

    stream.write_all(payload).await?;
    stream.write_u8(0).await?;
    stream.flush().await?;
    expect_scp_zero_ack(stream).await
}

async fn expect_scp_zero_ack<S: AsyncRead + Unpin>(stream: &mut S) -> Result<(), WireTransferError> {
    let code = stream.read_u8().await?;
    if code == 0 {
        Ok(())
    } else {
        Err(WireTransferError::Scp(format!("SCP remote returned status {code}")))
    }
}

/// Parses RFC 3659 FTP/FTPS `MLSD` machine-readable directory listing lines into [`RemoteFileEntry`] items.
/// Example line: `type=file;size=4096;modify=20261112183000;UNIX.mode=0644; release.tar.gz`
pub fn parse_ftp_mlsd_listing(parent_dir: &str, mlsd_body: &str) -> Vec<RemoteFileEntry> {
    let base = parent_dir.trim_end_matches('/');
    let mut out = Vec::new();

    for raw_line in mlsd_body.lines() {
        let line = raw_line.trim();
        if line.is_empty() {
            continue;
        }
        let Some((facts_str, name_part)) = line.split_once(' ') else {
            continue;
        };
        let name = name_part.trim().to_string();
        if name == "." {
            continue;
        }

        let mut is_dir = false;
        let mut is_symlink = false;
        let mut size_bytes = 0u64;
        let mut unix_mode = 0o644u32;

        for fact in facts_str.split(';').filter(|f| !f.is_empty()) {
            if let Some((k, v)) = fact.split_once('=') {
                match k.to_ascii_lowercase().as_str() {
                    "type" => {
                        is_dir = v.eq_ignore_ascii_case("dir") || v.eq_ignore_ascii_case("pdir");
                        is_symlink = v.to_ascii_lowercase().starts_with("os.unix=symlink");
                    }
                    "size" => size_bytes = v.parse::<u64>().unwrap_or(0),
                    "unix.mode" => unix_mode = u32::from_str_radix(v, 8).unwrap_or(0o644),
                    _ => {}
                }
            }
        }

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
            unix_mode,
            modified_epoch_sec: 0,
            owner: String::from("ftp"),
        });
    }

    out
}

/// Parses an FTP `229 Entering Extended Passive Mode (|||port|)` response to extract the data port.
pub fn parse_ftp_epsv_port(response_line: &str) -> Result<u16, WireTransferError> {
    let start = response_line
        .find("(|||")
        .ok_or_else(|| WireTransferError::Ftp("Missing EPSV delimiter".into()))?
        + 4;
    let rest = &response_line[start..];
    let end = rest
        .find('|')
        .ok_or_else(|| WireTransferError::Ftp("Malformed EPSV port".into()))?;
    rest[..end]
        .parse::<u16>()
        .map_err(|_| WireTransferError::Ftp("Invalid EPSV port integer".into()))
}
