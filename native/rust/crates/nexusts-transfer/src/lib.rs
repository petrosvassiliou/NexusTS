//! Dual-Pane SFTP / SCP / FTP / FTPS File Transfer Protocol Framing & Background Queue Engine.

pub mod ftp_client;
pub mod wire_clients;

use serde::{Deserialize, Serialize};
use std::collections::VecDeque;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransferProtocol {
    Sftp,
    Scp,
    Ftp,
    FtpsExplicit,
    FtpsImplicit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransferDirection {
    UploadToRemote,
    DownloadToLocal,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransferJobState {
    Queued,
    InProgress,
    Paused,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RemoteFileEntry {
    pub name: String,
    pub full_path: String,
    pub is_directory: bool,
    pub is_symlink: bool,
    pub size_bytes: u64,
    pub unix_mode: u32,
    pub modified_epoch_sec: u64,
    pub owner: String,
}

impl RemoteFileEntry {
    pub fn format_rwx_permissions(&self) -> String {
        let kind = if self.is_directory {
            'd'
        } else if self.is_symlink {
            'l'
        } else {
            '-'
        };
        let triplet = |shift: u32| -> String {
            let bits = (self.unix_mode >> shift) & 0b111;
            let r = if bits & 0b100 != 0 { 'r' } else { '-' };
            let w = if bits & 0b010 != 0 { 'w' } else { '-' };
            let x = if bits & 0b001 != 0 { 'x' } else { '-' };
            format!("{r}{w}{x}")
        };
        format!("{kind}{}{}{}", triplet(6), triplet(3), triplet(0))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferQueueItem {
    pub job_id: String,
    pub protocol: TransferProtocol,
    pub direction: TransferDirection,
    pub source_path: String,
    pub destination_path: String,
    pub total_bytes: u64,
    pub transferred_bytes: u64,
    pub speed_bytes_per_sec: u64,
    pub state: TransferJobState,
    pub error_message: Option<String>,
}

/// Manages concurrent SFTP/SCP/FTPS background transfer jobs with resumable byte offsets.
pub struct BackgroundTransferQueue {
    jobs: VecDeque<TransferQueueItem>,
    max_concurrent: usize,
}

impl BackgroundTransferQueue {
    pub fn new(max_concurrent: usize) -> Self {
        Self {
            jobs: VecDeque::new(),
            max_concurrent: max_concurrent.max(1),
        }
    }

    pub fn enqueue(&mut self, job: TransferQueueItem) {
        self.jobs.push_back(job);
    }

    pub fn update_progress(&mut self, job_id: &str, transferred: u64, speed_bps: u64) {
        if let Some(item) = self.jobs.iter_mut().find(|j| j.job_id == job_id) {
            item.transferred_bytes = transferred;
            item.speed_bytes_per_sec = speed_bps;
            item.state = if item.total_bytes > 0 && transferred >= item.total_bytes {
                TransferJobState::Completed
            } else {
                TransferJobState::InProgress
            };
        }
    }

    pub fn pause_job(&mut self, job_id: &str) -> bool {
        if let Some(item) = self.jobs.iter_mut().find(|j| j.job_id == job_id) {
            if matches!(item.state, TransferJobState::InProgress | TransferJobState::Queued) {
                item.state = TransferJobState::Paused;
                item.speed_bytes_per_sec = 0;
                return true;
            }
        }
        false
    }

    pub fn resume_job(&mut self, job_id: &str) -> bool {
        if let Some(item) = self.jobs.iter_mut().find(|j| j.job_id == job_id) {
            if matches!(item.state, TransferJobState::Paused | TransferJobState::Failed) {
                item.state = TransferJobState::Queued;
                item.error_message = None;
                return true;
            }
        }
        false
    }

    pub fn cancel_job(&mut self, job_id: &str) -> bool {
        if let Some(idx) = self.jobs.iter().position(|j| j.job_id == job_id) {
            self.jobs.remove(idx);
            return true;
        }
        false
    }

    pub fn next_schedulable_jobs(&self) -> Vec<TransferQueueItem> {
        let active = self
            .jobs
            .iter()
            .filter(|j| j.state == TransferJobState::InProgress)
            .count();
        let slots = self.max_concurrent.saturating_sub(active);
        self.jobs
            .iter()
            .filter(|j| j.state == TransferJobState::Queued)
            .take(slots)
            .cloned()
            .collect()
    }

    pub fn snapshot(&self) -> Vec<TransferQueueItem> {
        self.jobs.iter().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ftp_client::NexusFtpClient;
    use crate::wire_clients::{
        parse_ftp_epsv_port, parse_ftp_mlsd_listing, scp_send_single_file, NexusScpClient,
        NexusSftpClient,
    };
    use nexusts_terminal::ssh_transport::{ActiveSshClientSession, SshAuthMethod, SshConnectSpec};
    use russh::keys::ssh_key::rand_core::OsRng;
    use russh::keys::ssh_key::{Algorithm, PrivateKey};
    use russh::server::{self, Auth, Msg as ServerMsg, Session as ServerSession};
    use russh::{Channel, ChannelId, CryptoVec};
    use russh_sftp::protocol::{
        Attrs, Data, File, FileAttributes, Handle, Name, OpenFlags, Status, StatusCode,
    };
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};
    use std::time::Duration;
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
    use tokio::net::TcpListener;

    #[test]
    fn ftp_mlsd_and_epsv_parsers_extract_entries_and_port() {
        let mlsd = "\
type=dir;modify=20261112180000;UNIX.mode=0755; releases\n\
type=file;size=65536;modify=20261112183000;UNIX.mode=0640; vault-backup.nxts\n";
        let entries = parse_ftp_mlsd_listing("/srv/ftp", mlsd);
        assert_eq!(entries.len(), 2);
        assert!(entries[0].is_directory);
        assert_eq!(entries[0].full_path, "/srv/ftp/releases");
        assert_eq!(entries[0].format_rwx_permissions(), "drwxr-xr-x");
        assert_eq!(entries[1].size_bytes, 65_536);
        assert_eq!(entries[1].format_rwx_permissions(), "-rw-r-----");

        let port = parse_ftp_epsv_port("229 Entering Extended Passive Mode (|||49215|)").unwrap();
        assert_eq!(port, 49215);
    }

    #[test]
    fn transfer_queue_concurrency_pause_resume_and_completion() {
        let mut q = BackgroundTransferQueue::new(1);
        q.enqueue(TransferQueueItem {
            job_id: "job-1".into(),
            protocol: TransferProtocol::Sftp,
            direction: TransferDirection::UploadToRemote,
            source_path: "/local/a.tar".into(),
            destination_path: "/remote/a.tar".into(),
            total_bytes: 1000,
            transferred_bytes: 0,
            speed_bytes_per_sec: 0,
            state: TransferJobState::Queued,
            error_message: None,
        });
        q.enqueue(TransferQueueItem {
            job_id: "job-2".into(),
            protocol: TransferProtocol::Scp,
            direction: TransferDirection::DownloadToLocal,
            source_path: "/remote/b.tar".into(),
            destination_path: "/local/b.tar".into(),
            total_bytes: 2000,
            transferred_bytes: 0,
            speed_bytes_per_sec: 0,
            state: TransferJobState::Queued,
            error_message: None,
        });

        assert_eq!(q.next_schedulable_jobs().len(), 1);
        q.update_progress("job-1", 500, 250);
        assert_eq!(q.next_schedulable_jobs().len(), 0);
        assert!(q.pause_job("job-1"));
        assert_eq!(q.next_schedulable_jobs().len(), 1);
        assert!(q.resume_job("job-1"));
        q.update_progress("job-1", 1000, 500);
        assert_eq!(q.snapshot()[0].state, TransferJobState::Completed);
        assert!(q.cancel_job("job-2"));
        assert_eq!(q.snapshot().len(), 1);
    }

    #[tokio::test]
    async fn scp_sink_protocol_streams_header_payload_and_zero_acks() {
        let (mut client_side, mut server_side) = tokio::io::duplex(256);
        let server_task = tokio::spawn(async move {
            let mut hdr = Vec::new();
            loop {
                let b = server_side.read_u8().await.unwrap();
                hdr.push(b);
                if b == b'\n' {
                    break;
                }
            }
            assert_eq!(std::str::from_utf8(&hdr).unwrap(), "C0600 5 key.pem\n");
            server_side.write_u8(0).await.unwrap();

            let mut body = [0u8; 6];
            server_side.read_exact(&mut body).await.unwrap();
            assert_eq!(&body[..5], b"HELLO");
            assert_eq!(body[5], 0);
            server_side.write_u8(0).await.unwrap();
        });

        scp_send_single_file(&mut client_side, "/etc/ssh/key.pem", 0o600, b"HELLO")
            .await
            .unwrap();
        server_task.await.unwrap();
    }

    #[derive(Clone, Default)]
    struct MemFsNode {
        is_dir: bool,
        mode: u32,
        data: Vec<u8>,
    }

    struct MemSftpHandler {
        fs: Arc<Mutex<HashMap<String, MemFsNode>>>,
        open_handles: HashMap<String, (String, bool)>,
        next_id: u64,
    }

    impl MemSftpHandler {
        fn new(fs: Arc<Mutex<HashMap<String, MemFsNode>>>) -> Self {
            Self {
                fs,
                open_handles: HashMap::new(),
                next_id: 1,
            }
        }

        fn ok_status(id: u32) -> Status {
            Status {
                id,
                status_code: StatusCode::Ok,
                error_message: "Ok".into(),
                language_tag: "en-US".into(),
            }
        }

        fn attrs_for(node: &MemFsNode) -> FileAttributes {
            let type_bits = if node.is_dir { 0o040000 } else { 0o100000 };
            FileAttributes {
                size: Some(node.data.len() as u64),
                uid: Some(1000),
                user: Some("ops".into()),
                gid: Some(1000),
                group: Some("ops".into()),
                permissions: Some(type_bits | (node.mode & 0o7777)),
                atime: Some(1_700_000_000),
                mtime: Some(1_700_000_000),
            }
        }
    }

    impl russh_sftp::server::Handler for MemSftpHandler {
        type Error = StatusCode;

        fn unimplemented(&self) -> Self::Error {
            StatusCode::OpUnsupported
        }

        async fn realpath(&mut self, id: u32, path: String) -> Result<Name, Self::Error> {
            let resolved = if path.is_empty() || path == "." {
                "/srv".to_string()
            } else {
                path
            };
            Ok(Name {
                id,
                files: vec![File::dummy(resolved)],
            })
        }

        async fn opendir(&mut self, id: u32, path: String) -> Result<Handle, Self::Error> {
            let hid = format!("dir-{}", self.next_id);
            self.next_id += 1;
            self.open_handles.insert(hid.clone(), (path, false));
            Ok(Handle { id, handle: hid })
        }

        async fn readdir(&mut self, id: u32, handle: String) -> Result<Name, Self::Error> {
            let Some((dir_path, already_read)) = self.open_handles.get_mut(&handle) else {
                return Err(StatusCode::NoSuchFile);
            };
            if *already_read {
                return Err(StatusCode::Eof);
            }
            *already_read = true;
            let prefix = if dir_path == "/" {
                "/".to_string()
            } else {
                format!("{}/", dir_path.trim_end_matches('/'))
            };

            let guard = self.fs.lock().unwrap();
            let mut files = Vec::new();
            for (full, node) in guard.iter() {
                if let Some(rest) = full.strip_prefix(&prefix) {
                    if !rest.is_empty() && !rest.contains('/') {
                        files.push(File::new(rest.to_string(), Self::attrs_for(node)));
                    }
                }
            }
            Ok(Name { id, files })
        }

        async fn open(
            &mut self,
            id: u32,
            filename: String,
            pflags: OpenFlags,
            _attrs: FileAttributes,
        ) -> Result<Handle, Self::Error> {
            let mut guard = self.fs.lock().unwrap();
            if pflags.contains(OpenFlags::CREATE) || pflags.contains(OpenFlags::TRUNCATE) {
                guard.insert(
                    filename.clone(),
                    MemFsNode {
                        is_dir: false,
                        mode: 0o644,
                        data: Vec::new(),
                    },
                );
            } else if !guard.contains_key(&filename) {
                return Err(StatusCode::NoSuchFile);
            }
            let hid = format!("file-{}", self.next_id);
            self.next_id += 1;
            self.open_handles.insert(hid.clone(), (filename, false));
            Ok(Handle { id, handle: hid })
        }

        async fn read(
            &mut self,
            id: u32,
            handle: String,
            offset: u64,
            len: u32,
        ) -> Result<Data, Self::Error> {
            let Some((path, _)) = self.open_handles.get(&handle) else {
                return Err(StatusCode::NoSuchFile);
            };
            let guard = self.fs.lock().unwrap();
            let Some(node) = guard.get(path) else {
                return Err(StatusCode::NoSuchFile);
            };
            let off = offset as usize;
            if off >= node.data.len() {
                return Err(StatusCode::Eof);
            }
            let end = (off + len as usize).min(node.data.len());
            Ok(Data {
                id,
                data: node.data[off..end].to_vec(),
            })
        }

        async fn write(
            &mut self,
            id: u32,
            handle: String,
            offset: u64,
            data: Vec<u8>,
        ) -> Result<Status, Self::Error> {
            let Some((path, _)) = self.open_handles.get(&handle).cloned() else {
                return Err(StatusCode::NoSuchFile);
            };
            let mut guard = self.fs.lock().unwrap();
            let node = guard.entry(path).or_insert(MemFsNode {
                is_dir: false,
                mode: 0o644,
                data: Vec::new(),
            });
            let off = offset as usize;
            if node.data.len() < off + data.len() {
                node.data.resize(off + data.len(), 0);
            }
            node.data[off..off + data.len()].copy_from_slice(&data);
            Ok(Self::ok_status(id))
        }

        async fn stat(&mut self, id: u32, path: String) -> Result<Attrs, Self::Error> {
            let guard = self.fs.lock().unwrap();
            let Some(node) = guard.get(&path) else {
                return Err(StatusCode::NoSuchFile);
            };
            Ok(Attrs {
                id,
                attrs: Self::attrs_for(node),
            })
        }

        async fn lstat(&mut self, id: u32, path: String) -> Result<Attrs, Self::Error> {
            self.stat(id, path).await
        }

        async fn fstat(&mut self, id: u32, handle: String) -> Result<Attrs, Self::Error> {
            let Some((path, _)) = self.open_handles.get(&handle).cloned() else {
                return Err(StatusCode::NoSuchFile);
            };
            self.stat(id, path).await
        }

        async fn close(&mut self, id: u32, handle: String) -> Result<Status, Self::Error> {
            self.open_handles.remove(&handle);
            Ok(Self::ok_status(id))
        }

        async fn mkdir(
            &mut self,
            id: u32,
            path: String,
            _attrs: FileAttributes,
        ) -> Result<Status, Self::Error> {
            self.fs.lock().unwrap().insert(
                path,
                MemFsNode {
                    is_dir: true,
                    mode: 0o755,
                    data: Vec::new(),
                },
            );
            Ok(Self::ok_status(id))
        }

        async fn remove(&mut self, id: u32, filename: String) -> Result<Status, Self::Error> {
            let mut guard = self.fs.lock().unwrap();
            match guard.get(&filename) {
                Some(n) if !n.is_dir => {
                    guard.remove(&filename);
                    Ok(Self::ok_status(id))
                }
                _ => Err(StatusCode::NoSuchFile),
            }
        }

        async fn rmdir(&mut self, id: u32, path: String) -> Result<Status, Self::Error> {
            let mut guard = self.fs.lock().unwrap();
            match guard.get(&path) {
                Some(n) if n.is_dir => {
                    guard.remove(&path);
                    Ok(Self::ok_status(id))
                }
                _ => Err(StatusCode::NoSuchFile),
            }
        }

        async fn rename(
            &mut self,
            id: u32,
            oldpath: String,
            newpath: String,
        ) -> Result<Status, Self::Error> {
            let mut guard = self.fs.lock().unwrap();
            let Some(node) = guard.remove(&oldpath) else {
                return Err(StatusCode::NoSuchFile);
            };
            guard.insert(newpath, node);
            Ok(Self::ok_status(id))
        }

        async fn setstat(
            &mut self,
            id: u32,
            path: String,
            attrs: FileAttributes,
        ) -> Result<Status, Self::Error> {
            let mut guard = self.fs.lock().unwrap();
            let Some(node) = guard.get_mut(&path) else {
                return Err(StatusCode::NoSuchFile);
            };
            if let Some(perm) = attrs.permissions {
                node.mode = perm & 0o7777;
            }
            Ok(Self::ok_status(id))
        }
    }

    struct TransferSshServerHandler {
        fs: Arc<Mutex<HashMap<String, MemFsNode>>>,
        channels: HashMap<ChannelId, Channel<ServerMsg>>,
    }

    #[async_trait::async_trait]
    impl server::Handler for TransferSshServerHandler {
        type Error = russh::Error;

        async fn auth_password(&mut self, user: &str, password: &str) -> Result<Auth, Self::Error> {
            if user == "xfer" && password == "xfer-pass" {
                Ok(Auth::Accept)
            } else {
                Ok(Auth::Reject {
                    proceed_with_methods: None,
                })
            }
        }

        async fn channel_open_session(
            &mut self,
            channel: Channel<ServerMsg>,
            _session: &mut ServerSession,
        ) -> Result<bool, Self::Error> {
            self.channels.insert(channel.id(), channel);
            Ok(true)
        }

        async fn subsystem_request(
            &mut self,
            channel_id: ChannelId,
            name: &str,
            session: &mut ServerSession,
        ) -> Result<(), Self::Error> {
            if name == "sftp" {
                if let Some(channel) = self.channels.remove(&channel_id) {
                    session.channel_success(channel_id)?;
                    let sftp_handler = MemSftpHandler::new(Arc::clone(&self.fs));
                    russh_sftp::server::run(channel.into_stream(), sftp_handler).await;
                    return Ok(());
                }
            }
            session.channel_failure(channel_id)?;
            Ok(())
        }

        async fn exec_request(
            &mut self,
            channel_id: ChannelId,
            data: &[u8],
            session: &mut ServerSession,
        ) -> Result<(), Self::Error> {
            let cmd = String::from_utf8_lossy(data).to_string();
            session.channel_success(channel_id)?;

            let unquote = |s: &str| -> String {
                let t = s.trim();
                if t.starts_with('\'') && t.ends_with('\'') && t.len() >= 2 {
                    t[1..t.len() - 1].replace("'\\''", "'")
                } else {
                    t.to_string()
                }
            };

            if cmd == "pwd" {
                session.data(channel_id, CryptoVec::from_slice(b"/srv\n"))?;
                session.exit_status_request(channel_id, 0)?;
                session.eof(channel_id)?;
                session.close(channel_id)?;
            } else if let Some(arg) = cmd.strip_prefix("LC_ALL=C ls -la ") {
                let dir = unquote(arg);
                let prefix = if dir == "/" {
                    "/".to_string()
                } else {
                    format!("{}/", dir.trim_end_matches('/'))
                };
                let mut listing = String::from(
                    "total 16\ndrwxr-xr-x 2 xfer xfer 4096 Nov 15 12:00 .\ndrwxr-xr-x 2 xfer xfer 4096 Nov 15 12:00 ..\n",
                );
                {
                    let guard = self.fs.lock().unwrap();
                    for (full, node) in guard.iter() {
                        if let Some(name) = full.strip_prefix(&prefix) {
                            if !name.is_empty() && !name.contains('/') {
                                let entry = RemoteFileEntry {
                                    name: name.to_string(),
                                    full_path: full.clone(),
                                    is_directory: node.is_dir,
                                    is_symlink: false,
                                    size_bytes: node.data.len() as u64,
                                    unix_mode: node.mode,
                                    modified_epoch_sec: 0,
                                    owner: "xfer".into(),
                                };
                                listing.push_str(&format!(
                                    "{} 1 xfer xfer {} Nov 15 12:05 {}\n",
                                    entry.format_rwx_permissions(),
                                    entry.size_bytes,
                                    entry.name
                                ));
                            }
                        }
                    }
                }
                session.data(channel_id, CryptoVec::from_slice(listing.as_bytes()))?;
                session.exit_status_request(channel_id, 0)?;
                session.eof(channel_id)?;
                session.close(channel_id)?;
            } else if let Some(arg) = cmd.strip_prefix("mkdir -p ") {
                let path = unquote(arg);
                self.fs.lock().unwrap().insert(
                    path,
                    MemFsNode {
                        is_dir: true,
                        mode: 0o755,
                        data: Vec::new(),
                    },
                );
                session.exit_status_request(channel_id, 0)?;
                session.eof(channel_id)?;
                session.close(channel_id)?;
            } else if let Some(arg) = cmd.strip_prefix("rm -rf ") {
                let path = unquote(arg);
                self.fs.lock().unwrap().remove(&path);
                session.exit_status_request(channel_id, 0)?;
                session.eof(channel_id)?;
                session.close(channel_id)?;
            } else if let Some(rest) = cmd.strip_prefix("chmod ") {
                let (oct, p) = rest.split_once(' ').unwrap();
                let mode = u32::from_str_radix(oct, 8).unwrap();
                let path = unquote(p);
                if let Some(node) = self.fs.lock().unwrap().get_mut(&path) {
                    node.mode = mode;
                }
                session.exit_status_request(channel_id, 0)?;
                session.eof(channel_id)?;
                session.close(channel_id)?;
            } else if let Some(rest) = cmd.strip_prefix("mv -f ") {
                let parts: Vec<&str> = rest.split("' '").collect();
                if parts.len() == 2 {
                    let old_p = unquote(&format!("{}'", parts[0]));
                    let new_p = unquote(&format!("'{}", parts[1]));
                    let mut guard = self.fs.lock().unwrap();
                    if let Some(node) = guard.remove(&old_p) {
                        guard.insert(new_p, node);
                    }
                }
                session.exit_status_request(channel_id, 0)?;
                session.eof(channel_id)?;
                session.close(channel_id)?;
            } else if let Some(arg) = cmd.strip_prefix("scp -t ") {
                let target_path = unquote(arg);
                if let Some(channel) = self.channels.remove(&channel_id) {
                    let fs = Arc::clone(&self.fs);
                    tokio::spawn(async move {
                        let mut stream = channel.into_stream();
                        let _ = stream.write_u8(0).await;
                        let mut line = Vec::new();
                        loop {
                            match stream.read_u8().await {
                                Ok(b) => {
                                    line.push(b);
                                    if b == b'\n' {
                                        break;
                                    }
                                }
                                Err(_) => return,
                            }
                        }
                        let hdr = String::from_utf8_lossy(&line);
                        let after_c = hdr.trim().strip_prefix('C').unwrap();
                        let mut sp = after_c.splitn(3, ' ');
                        let mode = u32::from_str_radix(sp.next().unwrap(), 8).unwrap_or(0o644);
                        let sz: usize = sp.next().unwrap().parse().unwrap();
                        let _ = stream.write_u8(0).await;
                        let mut body = vec![0u8; sz + 1];
                        let _ = stream.read_exact(&mut body).await;
                        body.truncate(sz);
                        fs.lock().unwrap().insert(
                            target_path,
                            MemFsNode {
                                is_dir: false,
                                mode,
                                data: body,
                            },
                        );
                        let _ = stream.write_u8(0).await;
                        let _ = stream.shutdown().await;
                    });
                }
            } else if let Some(arg) = cmd.strip_prefix("scp -f ") {
                let source_path = unquote(arg);
                if let Some(channel) = self.channels.remove(&channel_id) {
                    let fs = Arc::clone(&self.fs);
                    tokio::spawn(async move {
                        let mut stream = channel.into_stream();
                        let _ = stream.read_u8().await;
                        let node_opt = fs.lock().unwrap().get(&source_path).cloned();
                        if let Some(node) = node_opt {
                            let fname = source_path.rsplit('/').next().unwrap_or("file");
                            let hdr = format!("C{:04o} {} {}\n", node.mode & 0o7777, node.data.len(), fname);
                            let _ = stream.write_all(hdr.as_bytes()).await;
                            let _ = stream.read_u8().await;
                            let _ = stream.write_all(&node.data).await;
                            let _ = stream.write_u8(0).await;
                            let _ = stream.read_u8().await;
                        } else {
                            let _ = stream.write_all(b"\x01scp: No such file\n").await;
                        }
                        let _ = stream.shutdown().await;
                    });
                }
            }
            Ok(())
        }
    }

    async fn spawn_transfer_ssh_server() -> (u16, tokio::task::AbortHandle) {
        let host_key = PrivateKey::random(&mut OsRng, Algorithm::Ed25519).unwrap();
        let mut cfg = server::Config {
            auth_rejection_time: Duration::from_millis(1),
            auth_rejection_time_initial: Some(Duration::from_millis(1)),
            ..Default::default()
        };
        cfg.keys.push(host_key);
        let cfg = Arc::new(cfg);

        let fs = Arc::new(Mutex::new(HashMap::new()));
        fs.lock().unwrap().insert(
            "/srv".into(),
            MemFsNode {
                is_dir: true,
                mode: 0o755,
                data: Vec::new(),
            },
        );

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let join = tokio::spawn(async move {
            while let Ok((sock, _)) = listener.accept().await {
                let c = Arc::clone(&cfg);
                let fs_clone = Arc::clone(&fs);
                tokio::spawn(async move {
                    let handler = TransferSshServerHandler {
                        fs: fs_clone,
                        channels: HashMap::new(),
                    };
                    if let Ok(session) = server::run_stream(c, sock, handler).await {
                        let _ = session.await;
                    }
                });
            }
        });
        (port, join.abort_handle())
    }

    #[tokio::test]
    async fn loopback_sftp_and_scp_full_lifecycle() {
        let (port, srv_abort) = spawn_transfer_ssh_server().await;

        let make_spec = || SshConnectSpec {
            host: "127.0.0.1".into(),
            port,
            username: "xfer".into(),
            auth: SshAuthMethod::Password {
                password: "xfer-pass".into(),
            },
            expected_host_fingerprint_sha256: None,
            term_env: "xterm-256color".into(),
            initial_cols: 80,
            initial_rows: 24,
            enable_x11: false,
            keepalive_secs: 0,
            startup_command: None,
        };

        // 1. Live SFTP v3 session test (pwd, mkdir, upload, list, chmod, rename, download, remove)
        let ssh_sess = ActiveSshClientSession::connect_without_shell(make_spec(), None)
            .await
            .unwrap();
        let sftp_stream = ssh_sess.open_sftp_subsystem_stream().await.unwrap();
        let sftp = NexusSftpClient::from_subsystem_stream(sftp_stream)
            .await
            .unwrap();

        assert_eq!(sftp.pwd().await.unwrap(), "/srv");
        sftp.create_directory("/srv/releases").await.unwrap();
        sftp.upload_bytes("/srv/draft.bin", b"SFTP-PAYLOAD-2026")
            .await
            .unwrap();
        sftp.chmod("/srv/draft.bin", 0o600).await.unwrap();
        sftp.rename("/srv/draft.bin", "/srv/final.bin").await.unwrap();

        let entries = sftp.list_directory("/srv").await.unwrap();
        assert_eq!(entries.len(), 2);
        let file_entry = entries.iter().find(|e| e.name == "final.bin").unwrap();
        assert_eq!(file_entry.unix_mode & 0o777, 0o600);
        assert_eq!(file_entry.size_bytes, 17);

        let downloaded = sftp.download_bytes("/srv/final.bin").await.unwrap();
        assert_eq!(downloaded, b"SFTP-PAYLOAD-2026");

        sftp.remove_entry("/srv/final.bin", false).await.unwrap();
        sftp.remove_entry("/srv/releases", true).await.unwrap();
        assert!(sftp.list_directory("/srv").await.unwrap().is_empty());
        let _ = sftp.close().await;
        let _ = ssh_sess.disconnect().await;

        // 2. Live SCP (`scp -t` / `scp -f`) + SSH exec (`ls -la`, `mkdir -p`, `chmod`, `mv`, `rm -rf`) test
        let scp = NexusScpClient::connect(make_spec(), None).await.unwrap();
        assert_eq!(scp.pwd().await.unwrap(), "/srv");
        scp.create_directory("/srv/scp-folder").await.unwrap();
        scp.upload_bytes("/srv/report 2026.txt", b"SCP-WIRE-BYTES", 0o640)
            .await
            .unwrap();
        scp.chmod("/srv/report 2026.txt", 0o750).await.unwrap();
        scp.rename("/srv/report 2026.txt", "/srv/renamed report.txt")
            .await
            .unwrap();

        let scp_list = scp.list_directory("/srv").await.unwrap();
        assert_eq!(scp_list.len(), 2);
        let renamed_item = scp_list
            .iter()
            .find(|e| e.name == "renamed report.txt")
            .unwrap();
        assert_eq!(renamed_item.unix_mode, 0o750);
        assert_eq!(renamed_item.size_bytes, 14);

        let scp_dl = scp.download_bytes("/srv/renamed report.txt").await.unwrap();
        assert_eq!(scp_dl, b"SCP-WIRE-BYTES");

        scp.remove_entry("/srv/renamed report.txt").await.unwrap();
        scp.remove_entry("/srv/scp-folder").await.unwrap();
        assert!(scp.list_directory("/srv").await.unwrap().is_empty());
        scp.disconnect().await.unwrap();

        srv_abort.abort();
    }

    #[tokio::test]
    async fn loopback_ftp_epsv_pasv_mlsd_list_fallback_and_file_ops() {
        let files: Arc<Mutex<HashMap<String, (bool, u32, Vec<u8>)>>> =
            Arc::new(Mutex::new(HashMap::new()));

        let spawn_ftp_server = |allow_epsv_and_mlsd: bool,
                                shared_fs: Arc<Mutex<HashMap<String, (bool, u32, Vec<u8>)>>>| async move {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let ctrl_port = listener.local_addr().unwrap().port();
            let join = tokio::spawn(async move {
                while let Ok((sock, _)) = listener.accept().await {
                    let fs = Arc::clone(&shared_fs);
                    tokio::spawn(async move {
                        let mut reader = BufReader::new(sock);
                        // Multi-line 220 banner
                        let _ = reader
                            .get_mut()
                            .write_all(b"220-NexusTS Loopback FTP Server\r\n220 Ready\r\n")
                            .await;

                        let mut data_listener: Option<TcpListener> = None;
                        let mut cwd = String::from("/pub");
                        let mut pending_rnfr: Option<String> = None;

                        loop {
                            let mut line = String::new();
                            let Ok(n) = reader.read_line(&mut line).await else {
                                break;
                            };
                            if n == 0 {
                                break;
                            }
                            let cmd_line = line.trim_end_matches(['\r', '\n']);
                            let (verb, arg) = match cmd_line.split_once(' ') {
                                Some((v, a)) => (v.to_ascii_uppercase(), a.trim().to_string()),
                                None => (cmd_line.to_ascii_uppercase(), String::new()),
                            };

                            match verb.as_str() {
                                "USER" => {
                                    let _ = reader.get_mut().write_all(b"331 Password required\r\n").await;
                                }
                                "PASS" => {
                                    let _ = reader.get_mut().write_all(b"230 Logged in\r\n").await;
                                }
                                "OPTS" => {
                                    let _ = reader.get_mut().write_all(b"200 UTF8 enabled\r\n").await;
                                }
                                "TYPE" => {
                                    let _ = reader.get_mut().write_all(b"200 Binary mode\r\n").await;
                                }
                                "PWD" => {
                                    let msg = format!("257 \"{cwd}\" is current directory\r\n");
                                    let _ = reader.get_mut().write_all(msg.as_bytes()).await;
                                }
                                "CWD" => {
                                    cwd = arg;
                                    let _ = reader.get_mut().write_all(b"250 CWD ok\r\n").await;
                                }
                                "EPSV" => {
                                    if allow_epsv_and_mlsd {
                                        let dl = TcpListener::bind("127.0.0.1:0").await.unwrap();
                                        let dp = dl.local_addr().unwrap().port();
                                        data_listener = Some(dl);
                                        let msg = format!(
                                            "229 Entering Extended Passive Mode (|||{dp}|)\r\n"
                                        );
                                        let _ = reader.get_mut().write_all(msg.as_bytes()).await;
                                    } else {
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"502 EPSV disabled\r\n")
                                            .await;
                                    }
                                }
                                "PASV" => {
                                    let dl = TcpListener::bind("127.0.0.1:0").await.unwrap();
                                    let dp = dl.local_addr().unwrap().port();
                                    data_listener = Some(dl);
                                    // Deliberately return 0,0,0,0 to verify peer IP substitution!
                                    let msg = format!(
                                        "227 Entering Passive Mode (0,0,0,0,{},{})\r\n",
                                        dp >> 8,
                                        dp & 0xFF
                                    );
                                    let _ = reader.get_mut().write_all(msg.as_bytes()).await;
                                }
                                "MLSD" => {
                                    if !allow_epsv_and_mlsd {
                                        data_listener = None;
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"502 MLSD not implemented\r\n")
                                            .await;
                                    } else if let Some(dl) = data_listener.take() {
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"150 Opening MLSD data connection\r\n")
                                            .await;
                                        let (mut ds, _) = dl.accept().await.unwrap();
                                        let body = {
                                            let guard = fs.lock().unwrap();
                                            let mut s = String::new();
                                            for (p, (is_dir, mode, bytes)) in guard.iter() {
                                                let name = p.rsplit('/').next().unwrap_or(p);
                                                let t = if *is_dir { "dir" } else { "file" };
                                                s.push_str(&format!(
                                                    "type={t};size={};UNIX.mode={:04o}; {name}\r\n",
                                                    bytes.len(),
                                                    mode
                                                ));
                                            }
                                            s
                                        };
                                        let _ = ds.write_all(body.as_bytes()).await;
                                        let _ = ds.shutdown().await;
                                        drop(ds);
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"226 MLSD complete\r\n")
                                            .await;
                                    }
                                }
                                "LIST" => {
                                    if let Some(dl) = data_listener.take() {
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"150 Opening ASCII LIST\r\n")
                                            .await;
                                        let (mut ds, _) = dl.accept().await.unwrap();
                                        // Return one UNIX ls -la entry + one Windows/IIS DOS entry!
                                        let mixed = concat!(
                                            "-rw-r----- 1 ftp ftp 12 Nov 15 10:30 unix-note.txt\r\n",
                                            "11-15-26  04:15PM       <DIR>          dos-backups\r\n",
                                            "11-15-26  04:16PM                 4096 iis archive.zip\r\n"
                                        );
                                        let _ = ds.write_all(mixed.as_bytes()).await;
                                        let _ = ds.shutdown().await;
                                        drop(ds);
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"226 LIST complete\r\n")
                                            .await;
                                    }
                                }
                                "STOR" => {
                                    if let Some(dl) = data_listener.take() {
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"150 Ready for STOR\r\n")
                                            .await;
                                        let (mut ds, _) = dl.accept().await.unwrap();
                                        let mut buf = Vec::new();
                                        let _ = ds.read_to_end(&mut buf).await;
                                        fs.lock().unwrap().insert(arg, (false, 0o644, buf));
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"226 STOR complete\r\n")
                                            .await;
                                    }
                                }
                                "RETR" => {
                                    if let Some(dl) = data_listener.take() {
                                        let data_opt =
                                            fs.lock().unwrap().get(&arg).map(|(_, _, b)| b.clone());
                                        if let Some(bytes) = data_opt {
                                            let _ = reader
                                                .get_mut()
                                                .write_all(b"150 Opening binary RETR\r\n")
                                                .await;
                                            let (mut ds, _) = dl.accept().await.unwrap();
                                            let _ = ds.write_all(&bytes).await;
                                            let _ = ds.shutdown().await;
                                            drop(ds);
                                            let _ = reader
                                                .get_mut()
                                                .write_all(b"226 RETR complete\r\n")
                                                .await;
                                        } else {
                                            let _ = reader
                                                .get_mut()
                                                .write_all(b"550 File not found\r\n")
                                                .await;
                                        }
                                    }
                                }
                                "MKD" => {
                                    fs.lock().unwrap().insert(arg.clone(), (true, 0o755, Vec::new()));
                                    let msg = format!("257 \"{arg}\" created\r\n");
                                    let _ = reader.get_mut().write_all(msg.as_bytes()).await;
                                }
                                "DELE" => {
                                    let deleted = {
                                        let mut guard = fs.lock().unwrap();
                                        if matches!(guard.get(&arg), Some((false, _, _))) {
                                            guard.remove(&arg);
                                            true
                                        } else {
                                            false
                                        }
                                    };
                                    if deleted {
                                        let _ = reader.get_mut().write_all(b"250 Deleted\r\n").await;
                                    } else {
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"550 Is a directory\r\n")
                                            .await;
                                    }
                                }
                                "RMD" => {
                                    let removed = {
                                        let mut guard = fs.lock().unwrap();
                                        if matches!(guard.get(&arg), Some((true, _, _))) {
                                            guard.remove(&arg);
                                            true
                                        } else {
                                            false
                                        }
                                    };
                                    if removed {
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"250 Directory removed\r\n")
                                            .await;
                                    } else {
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"550 Not found\r\n")
                                            .await;
                                    }
                                }
                                "RNFR" => {
                                    pending_rnfr = Some(arg);
                                    let _ = reader
                                        .get_mut()
                                        .write_all(b"350 Ready for RNTO\r\n")
                                        .await;
                                }
                                "RNTO" => {
                                    if let Some(old) = pending_rnfr.take() {
                                        let mut guard = fs.lock().unwrap();
                                        if let Some(val) = guard.remove(&old) {
                                            guard.insert(arg, val);
                                        }
                                    }
                                    let _ = reader.get_mut().write_all(b"250 Renamed\r\n").await;
                                }
                                "SITE" => {
                                    if let Some(rest) = arg.strip_prefix("CHMOD ") {
                                        let (oct, path) = rest.split_once(' ').unwrap();
                                        let m = u32::from_str_radix(oct, 8).unwrap();
                                        if let Some(entry) = fs.lock().unwrap().get_mut(path) {
                                            entry.1 = m;
                                        }
                                        let _ = reader
                                            .get_mut()
                                            .write_all(b"200 SITE CHMOD ok\r\n")
                                            .await;
                                    }
                                }
                                "QUIT" => {
                                    let _ = reader.get_mut().write_all(b"221 Bye\r\n").await;
                                    break;
                                }
                                _ => {
                                    let _ = reader.get_mut().write_all(b"500 Unknown\r\n").await;
                                }
                            }
                        }
                    });
                }
            });
            (ctrl_port, join.abort_handle())
        };

        // Mode A: Modern EPSV + MLSD + STOR/RETR/MKD/CHMOD/RNFR-RNTO/DELE/RMD
        let (port_modern, abort_modern) = spawn_ftp_server(true, Arc::clone(&files)).await;
        let ftp = NexusFtpClient::connect("127.0.0.1", port_modern, "ftpuser", "secret")
            .await
            .unwrap();
        assert_eq!(ftp.pwd().await.unwrap(), "/pub");
        ftp.cwd("/pub/uploads").await.unwrap();
        assert_eq!(ftp.pwd().await.unwrap(), "/pub/uploads");

        ftp.create_directory("/pub/uploads/archive").await.unwrap();
        ftp.upload_bytes("/pub/uploads/hello.bin", b"FTP-WIRE-OK")
            .await
            .unwrap();
        ftp.chmod("/pub/uploads/hello.bin", 0o600).await.unwrap();
        ftp.rename("/pub/uploads/hello.bin", "/pub/uploads/hello2.bin")
            .await
            .unwrap();

        let listed = ftp.list_directory("/pub/uploads").await.unwrap();
        assert_eq!(listed.len(), 2);
        let f_item = listed.iter().find(|e| e.name == "hello2.bin").unwrap();
        assert_eq!(f_item.unix_mode, 0o600);
        assert_eq!(f_item.size_bytes, 11);

        let dl = ftp.download_bytes("/pub/uploads/hello2.bin").await.unwrap();
        assert_eq!(dl, b"FTP-WIRE-OK");

        // DELE for file, DELE->RMD fallback for directory
        ftp.remove_entry("/pub/uploads/hello2.bin").await.unwrap();
        ftp.remove_entry("/pub/uploads/archive").await.unwrap();
        ftp.disconnect().await.unwrap();
        abort_modern.abort();

        // Mode B: Legacy FTP server (502 on EPSV -> PASV 0.0.0.0 substitution; 502 on MLSD -> LIST UNIX + DOS)
        let (port_legacy, abort_legacy) = spawn_ftp_server(false, files).await;
        let ftp_leg = NexusFtpClient::connect("127.0.0.1", port_legacy, "legacy", "pw")
            .await
            .unwrap();
        let leg_entries = ftp_leg.list_directory("/legacy").await.unwrap();
        assert_eq!(leg_entries.len(), 3);
        assert_eq!(leg_entries[0].name, "unix-note.txt");
        assert_eq!(leg_entries[0].unix_mode, 0o640);
        assert_eq!(leg_entries[1].name, "dos-backups");
        assert!(leg_entries[1].is_directory);
        assert_eq!(leg_entries[2].name, "iis archive.zip");
        assert_eq!(leg_entries[2].size_bytes, 4096);
        ftp_leg.disconnect().await.unwrap();
        abort_legacy.abort();
    }
}
