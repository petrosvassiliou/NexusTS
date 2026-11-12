//! Dual-Pane SFTP / SCP / FTP / FTPS File Transfer Protocol Framing & Background Queue Engine.

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
    use crate::wire_clients::{parse_ftp_epsv_port, parse_ftp_mlsd_listing, scp_send_single_file};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

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
}
