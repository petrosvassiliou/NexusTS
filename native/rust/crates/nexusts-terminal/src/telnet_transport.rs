//! Stateful Async Tokio TCP Telnet Client (`RFC 854`, `RFC 855`, `RFC 1073` NAWS, `RFC 1091` TTYPE).
//!
//! Preserves partial `IAC` (`0xFF`) command sequences and subnegotiations across arbitrary
//! TCP segment boundaries while negotiating `NAWS` (31), `TTYPE` (24 -> `xterm-256color`),
//! `SGA` (3), `BINARY` (0), and `ECHO` (1).

use crate::build_telnet_naws_packet;
use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Notify};
use tokio::task::AbortHandle;

pub const TELNET_IAC: u8 = 255;
pub const TELNET_DONT: u8 = 254;
pub const TELNET_DO: u8 = 253;
pub const TELNET_WONT: u8 = 252;
pub const TELNET_WILL: u8 = 251;
pub const TELNET_SB: u8 = 250;
pub const TELNET_SE: u8 = 240;

pub const TELNET_OPT_BINARY: u8 = 0;
pub const TELNET_OPT_ECHO: u8 = 1;
pub const TELNET_OPT_SGA: u8 = 3;
pub const TELNET_OPT_TTYPE: u8 = 24;
pub const TELNET_OPT_NAWS: u8 = 31;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TelnetParseState {
    Ground,
    SeenIac,
    CommandOption(u8),
    Subnegotiation,
    SubnegSeenIac,
}

/// Stateful RFC 854 / 855 Telnet stream parser that survives arbitrary byte/chunk splits.
pub struct StatefulTelnetParser {
    state: TelnetParseState,
    subneg_buf: Vec<u8>,
    cols: u16,
    rows: u16,
    term_type: String,
    naws_enabled: bool,
    local_will: [bool; 256],
    remote_do: [bool; 256],
}

impl StatefulTelnetParser {
    pub fn new(cols: u16, rows: u16, term_type: &str) -> Self {
        Self {
            state: TelnetParseState::Ground,
            subneg_buf: Vec::with_capacity(64),
            cols: cols.max(1),
            rows: rows.max(1),
            term_type: if term_type.trim().is_empty() {
                String::from("xterm-256color")
            } else {
                term_type.to_string()
            },
            naws_enabled: false,
            local_will: [false; 256],
            remote_do: [false; 256],
        }
    }

    pub fn set_dimensions(&mut self, cols: u16, rows: u16) {
        self.cols = cols.max(1);
        self.rows = rows.max(1);
    }

    pub fn is_naws_enabled(&self) -> bool {
        self.naws_enabled
    }

    /// Feeds arbitrary incoming TCP bytes and returns `(clean_terminal_bytes, outbound_iac_replies)`.
    pub fn feed(&mut self, wire: &[u8]) -> (Vec<u8>, Vec<u8>) {
        let mut clean = Vec::with_capacity(wire.len());
        let mut replies = Vec::new();

        for &byte in wire {
            match self.state {
                TelnetParseState::Ground => {
                    if byte == TELNET_IAC {
                        self.state = TelnetParseState::SeenIac;
                    } else {
                        clean.push(byte);
                    }
                }
                TelnetParseState::SeenIac => match byte {
                    TELNET_IAC => {
                        clean.push(TELNET_IAC);
                        self.state = TelnetParseState::Ground;
                    }
                    TELNET_WILL | TELNET_WONT | TELNET_DO | TELNET_DONT => {
                        self.state = TelnetParseState::CommandOption(byte);
                    }
                    TELNET_SB => {
                        self.subneg_buf.clear();
                        self.state = TelnetParseState::Subnegotiation;
                    }
                    _ => {
                        // 2-byte Telnet command (NOP, GA, BRK, AYT, etc.)
                        self.state = TelnetParseState::Ground;
                    }
                },
                TelnetParseState::CommandOption(cmd) => {
                    self.handle_negotiation(cmd, byte, &mut replies);
                    self.state = TelnetParseState::Ground;
                }
                TelnetParseState::Subnegotiation => {
                    if byte == TELNET_IAC {
                        self.state = TelnetParseState::SubnegSeenIac;
                    } else if self.subneg_buf.len() < 4096 {
                        self.subneg_buf.push(byte);
                    }
                }
                TelnetParseState::SubnegSeenIac => match byte {
                    TELNET_IAC => {
                        if self.subneg_buf.len() < 4096 {
                            self.subneg_buf.push(TELNET_IAC);
                        }
                        self.state = TelnetParseState::Subnegotiation;
                    }
                    TELNET_SE => {
                        self.handle_subnegotiation(&mut replies);
                        self.subneg_buf.clear();
                        self.state = TelnetParseState::Ground;
                    }
                    _ => {
                        self.subneg_buf.clear();
                        self.state = TelnetParseState::Ground;
                    }
                },
            }
        }

        (clean, replies)
    }

    fn handle_negotiation(&mut self, cmd: u8, opt: u8, replies: &mut Vec<u8>) {
        let idx = opt as usize;
        match cmd {
            TELNET_DO => match opt {
                TELNET_OPT_NAWS => {
                    if !self.local_will[idx] {
                        self.local_will[idx] = true;
                        replies.extend_from_slice(&[TELNET_IAC, TELNET_WILL, TELNET_OPT_NAWS]);
                    }
                    self.naws_enabled = true;
                    replies.extend_from_slice(&build_telnet_naws_packet(self.cols, self.rows));
                }
                TELNET_OPT_TTYPE | TELNET_OPT_SGA | TELNET_OPT_BINARY => {
                    if !self.local_will[idx] {
                        self.local_will[idx] = true;
                        replies.extend_from_slice(&[TELNET_IAC, TELNET_WILL, opt]);
                    }
                }
                _ => {
                    self.local_will[idx] = false;
                    replies.extend_from_slice(&[TELNET_IAC, TELNET_WONT, opt]);
                }
            },
            TELNET_DONT => {
                if opt == TELNET_OPT_NAWS {
                    self.naws_enabled = false;
                }
                if self.local_will[idx] {
                    self.local_will[idx] = false;
                    replies.extend_from_slice(&[TELNET_IAC, TELNET_WONT, opt]);
                }
            }
            TELNET_WILL => match opt {
                TELNET_OPT_SGA | TELNET_OPT_BINARY | TELNET_OPT_ECHO => {
                    if !self.remote_do[idx] {
                        self.remote_do[idx] = true;
                        replies.extend_from_slice(&[TELNET_IAC, TELNET_DO, opt]);
                    }
                }
                _ => {
                    self.remote_do[idx] = false;
                    replies.extend_from_slice(&[TELNET_IAC, TELNET_DONT, opt]);
                }
            },
            TELNET_WONT => {
                if self.remote_do[idx] {
                    self.remote_do[idx] = false;
                    replies.extend_from_slice(&[TELNET_IAC, TELNET_DONT, opt]);
                }
            }
            _ => {}
        }
    }

    fn handle_subnegotiation(&self, replies: &mut Vec<u8>) {
        // RFC 1091: IAC SB TTYPE SEND (1) IAC SE -> IAC SB TTYPE IS (0) <term_type> IAC SE
        if self.subneg_buf.len() >= 2
            && self.subneg_buf[0] == TELNET_OPT_TTYPE
            && self.subneg_buf[1] == 1
        {
            replies.extend_from_slice(&[TELNET_IAC, TELNET_SB, TELNET_OPT_TTYPE, 0]);
            for &b in self.term_type.as_bytes() {
                replies.push(b);
                if b == TELNET_IAC {
                    replies.push(TELNET_IAC);
                }
            }
            replies.extend_from_slice(&[TELNET_IAC, TELNET_SE]);
        }
    }
}

/// Escapes `0xFF` data bytes as `0xFF 0xFF` for outbound Telnet transmission.
pub fn escape_telnet_data(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len() + 8);
    for &b in data {
        out.push(b);
        if b == TELNET_IAC {
            out.push(TELNET_IAC);
        }
    }
    out
}

fn format_host_port(host: &str, port: u16) -> String {
    let clean = host.trim().trim_start_matches('[').trim_end_matches(']');
    if clean.contains(':') {
        format!("[{clean}]:{port}")
    } else {
        format!("{clean}:{port}")
    }
}

enum TelnetCommand {
    WriteRaw(Vec<u8>),
    Resize { cols: u16, rows: u16 },
    Disconnect,
}

/// Live asynchronous Telnet client session backed by Tokio TCP and [`StatefulTelnetParser`].
pub struct ActiveTelnetSession {
    cmd_tx: mpsc::UnboundedSender<TelnetCommand>,
    output_buf: Arc<Mutex<Vec<u8>>>,
    output_notify: Arc<Notify>,
    connected: Arc<AtomicBool>,
    last_error: Arc<Mutex<String>>,
    abort_handle: AbortHandle,
}

impl ActiveTelnetSession {
    /// Connects to a remote Telnet endpoint (default port `23` if `port == 0`).
    pub async fn connect(
        host: &str,
        port: u16,
        cols: u16,
        rows: u16,
        term_type: &str,
    ) -> Result<Self, io::Error> {
        let effective_port = if port == 0 { 23 } else { port };
        let target = format_host_port(host, effective_port);
        let stream = TcpStream::connect(&target).await?;
        let _ = stream.set_nodelay(true);

        let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<TelnetCommand>();
        let output_buf = Arc::new(Mutex::new(Vec::<u8>::with_capacity(8192)));
        let output_notify = Arc::new(Notify::new());
        let connected = Arc::new(AtomicBool::new(true));
        let last_error = Arc::new(Mutex::new(String::new()));

        let out_clone = Arc::clone(&output_buf);
        let notify_clone = Arc::clone(&output_notify);
        let conn_clone = Arc::clone(&connected);
        let err_clone = Arc::clone(&last_error);
        let mut parser = StatefulTelnetParser::new(cols.max(1), rows.max(1), term_type);

        let join = tokio::spawn(async move {
            let (mut reader, mut writer) = stream.into_split();
            let mut buf = [0u8; 4096];

            loop {
                tokio::select! {
                    maybe_cmd = cmd_rx.recv() => {
                        match maybe_cmd {
                            Some(TelnetCommand::WriteRaw(wire_bytes)) => {
                                if let Err(e) = writer.write_all(&wire_bytes).await {
                                    if let Ok(mut guard) = err_clone.lock() {
                                        *guard = e.to_string();
                                    }
                                    break;
                                }
                                let _ = writer.flush().await;
                            }
                            Some(TelnetCommand::Resize { cols, rows }) => {
                                parser.set_dimensions(cols, rows);
                                let naws_pkt = build_telnet_naws_packet(cols.max(1), rows.max(1));
                                if let Err(e) = writer.write_all(&naws_pkt).await {
                                    if let Ok(mut guard) = err_clone.lock() {
                                        *guard = e.to_string();
                                    }
                                    break;
                                }
                                let _ = writer.flush().await;
                            }
                            Some(TelnetCommand::Disconnect) | None => {
                                let _ = writer.shutdown().await;
                                break;
                            }
                        }
                    }
                    read_res = reader.read(&mut buf) => {
                        match read_res {
                            Ok(0) => break,
                            Ok(n) => {
                                let (clean, replies) = parser.feed(&buf[..n]);
                                if !replies.is_empty() {
                                    if let Err(e) = writer.write_all(&replies).await {
                                        if let Ok(mut guard) = err_clone.lock() {
                                            *guard = e.to_string();
                                        }
                                        break;
                                    }
                                    let _ = writer.flush().await;
                                }
                                if !clean.is_empty() {
                                    if let Ok(mut out) = out_clone.lock() {
                                        out.extend_from_slice(&clean);
                                    }
                                    notify_clone.notify_waiters();
                                }
                            }
                            Err(e) => {
                                if let Ok(mut guard) = err_clone.lock() {
                                    *guard = e.to_string();
                                }
                                break;
                            }
                        }
                    }
                }
            }

            conn_clone.store(false, Ordering::Release);
            notify_clone.notify_waiters();
        });

        Ok(Self {
            cmd_tx,
            output_buf,
            output_notify,
            connected,
            last_error,
            abort_handle: join.abort_handle(),
        })
    }

    /// Escapes any `0xFF` bytes per RFC 854 and queues `data` to the remote Telnet socket.
    pub fn write_stdin(&self, data: &[u8]) -> bool {
        if data.is_empty() {
            return self.is_connected();
        }
        if !self.is_connected() {
            return false;
        }
        let escaped = escape_telnet_data(data);
        self.cmd_tx.send(TelnetCommand::WriteRaw(escaped)).is_ok()
    }

    /// Sends a live `IAC SB NAWS` window-size frame (with RFC 855 `0xFF` byte doubling).
    pub fn resize_pty(&self, cols: u16, rows: u16) -> bool {
        if !self.is_connected() {
            return false;
        }
        self.cmd_tx
            .send(TelnetCommand::Resize {
                cols: cols.max(1),
                rows: rows.max(1),
            })
            .is_ok()
    }

    /// Non-blocking drain of all decoded terminal output bytes accumulated so far.
    pub fn drain_output(&self) -> Vec<u8> {
        if let Ok(mut out) = self.output_buf.lock() {
            if out.is_empty() {
                return Vec::new();
            }
            return std::mem::take(&mut *out);
        }
        Vec::new()
    }

    /// Awaits terminal output up to `wait_timeout` if the buffer is currently empty, then drains it.
    pub async fn wait_and_drain_output(&self, wait_timeout: Duration) -> Vec<u8> {
        let immediate = self.drain_output();
        if !immediate.is_empty() || !self.is_connected() {
            return immediate;
        }
        let _ = tokio::time::timeout(wait_timeout, self.output_notify.notified()).await;
        self.drain_output()
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Acquire)
    }

    pub fn last_error(&self) -> String {
        self.last_error
            .lock()
            .map(|g| g.clone())
            .unwrap_or_default()
    }

    pub fn disconnect(&self) {
        self.connected.store(false, Ordering::Release);
        let _ = self.cmd_tx.send(TelnetCommand::Disconnect);
        self.abort_handle.abort();
        self.output_notify.notify_waiters();
    }
}

impl Drop for ActiveTelnetSession {
    fn drop(&mut self) {
        self.abort_handle.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn loopback_telnet_handles_split_iac_ttype_naws_ff_escaping_and_live_resize() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();

        let server_task = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();

            // Send split IAC sequences 1-2 bytes at a time across TCP writes:
            // IAC DO TTYPE, IAC DO NAWS, IAC WILL ECHO, IAC WILL SGA, IAC SB TTYPE SEND IAC SE,
            // plus "Hi" + escaped 0xFF 0xFF + "!"
            let server_hello: Vec<u8> = vec![
                TELNET_IAC, TELNET_DO, TELNET_OPT_TTYPE,
                TELNET_IAC, TELNET_DO, TELNET_OPT_NAWS,
                TELNET_IAC, TELNET_WILL, TELNET_OPT_ECHO,
                TELNET_IAC, TELNET_WILL, TELNET_OPT_SGA,
                TELNET_IAC, TELNET_SB, TELNET_OPT_TTYPE, 1, TELNET_IAC, TELNET_SE,
                b'H', b'i', TELNET_IAC, TELNET_IAC, b'!',
            ];
            for chunk in server_hello.chunks(2) {
                sock.write_all(chunk).await.unwrap();
                sock.flush().await.unwrap();
                tokio::time::sleep(Duration::from_millis(2)).await;
            }

            // Collect client replies + stdin + live NAWS resize(255, 60)
            let mut received = Vec::new();
            let mut tmp = [0u8; 256];
            let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
            // Expect final live NAWS with cols=255 -> [255, 250, 31, 0, 255, 255, 0, 60, 255, 240]
            let expected_live_naws = [255u8, 250, 31, 0, 255, 255, 0, 60, 255, 240];
            while tokio::time::Instant::now() < deadline {
                if let Ok(Ok(n)) =
                    tokio::time::timeout(Duration::from_millis(100), sock.read(&mut tmp)).await
                {
                    if n == 0 {
                        break;
                    }
                    received.extend_from_slice(&tmp[..n]);
                    if received
                        .windows(expected_live_naws.len())
                        .any(|w| w == expected_live_naws)
                    {
                        break;
                    }
                }
            }
            received
        });

        let session =
            ActiveTelnetSession::connect("127.0.0.1", port, 120, 40, "xterm-256color")
                .await
                .unwrap();

        // Wait until all 4 clean bytes [b'H', b'i', 0xFF, b'!'] arrive
        let mut clean_collected = Vec::new();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
        while clean_collected.len() < 4 && tokio::time::Instant::now() < deadline {
            let chunk = session.wait_and_drain_output(Duration::from_millis(50)).await;
            clean_collected.extend_from_slice(&chunk);
        }
        assert_eq!(clean_collected, vec![b'H', b'i', 0xFF, b'!']);

        // Send stdin containing 0xFF byte and trigger live NAWS resize with cols=255 (tests 0xFF doubling!)
        assert!(session.write_stdin(&[b'A', 0xFF, b'B']));
        assert!(session.resize_pty(255, 60));

        let server_received = server_task.await.unwrap();

        // Verify WILL TTYPE, WILL NAWS, DO ECHO, DO SGA
        assert!(server_received.windows(3).any(|w| w == [255, 251, 24]));
        assert!(server_received.windows(3).any(|w| w == [255, 251, 31]));
        assert!(server_received.windows(3).any(|w| w == [255, 253, 1]));
        assert!(server_received.windows(3).any(|w| w == [255, 253, 3]));

        // Verify TTYPE IS "xterm-256color"
        let mut expected_ttype = vec![255, 250, 24, 0];
        expected_ttype.extend_from_slice(b"xterm-256color");
        expected_ttype.extend_from_slice(&[255, 240]);
        assert!(server_received
            .windows(expected_ttype.len())
            .any(|w| w == expected_ttype.as_slice()));

        // Verify initial NAWS (120x40)
        assert!(server_received
            .windows(9)
            .any(|w| w == [255, 250, 31, 0, 120, 0, 40, 255, 240]));

        // Verify stdin 0xFF escaping ([b'A', 0xFF, 0xFF, b'B'])
        assert!(server_received
            .windows(4)
            .any(|w| w == [b'A', 0xFF, 0xFF, b'B']));

        // Verify live NAWS resize(255, 60) with RFC 855 0xFF doubling!
        assert!(server_received
            .windows(10)
            .any(|w| w == [255, 250, 31, 0, 255, 255, 0, 60, 255, 240]));

        session.disconnect();
        assert!(!session.is_connected());
    }
}
