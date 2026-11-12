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
