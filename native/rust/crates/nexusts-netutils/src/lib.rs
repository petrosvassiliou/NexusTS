//! Integrated Enterprise Network Utilities:
//! Wake-on-LAN (Magic Packet + SecureOn), IPv6-safe Socket Target Formatting,
//! TTL-before-Connect Multi-Hop Traceroute (`socket2` + optional unprivileged `DGRAM` ICMP),
//! Service Banner Grabber, Concurrent Async Port Scanner, and Sub-Millisecond TCP Reachability Probe.

use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use std::io::{ErrorKind, Read};
use std::net::{IpAddr, SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::time::{Duration, Instant};
use thiserror::Error;
use tokio::io::AsyncReadExt;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

pub mod winrm_ntlm;

#[derive(Debug, Error)]
pub enum NetUtilError {
    #[error("Invalid MAC address '{0}' (expected AA:BB:CC:DD:EE:FF)")]
    InvalidMacAddress(String),
    #[error("Invalid SecureOn hex password")]
    InvalidSecureOn,
    #[error("Host DNS resolution failed for '{0}'")]
    DnsResolutionFailed(String),
    #[error("Socket I/O error: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PortScanHit {
    pub port: u16,
    pub open: bool,
    pub connect_latency_ms: u64,
    pub guessed_service: String,
    pub service_banner: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TracerouteHopResult {
    pub ttl: u8,
    pub responder_addr: String,
    pub rtt_ms: f64,
    pub reached_destination: bool,
    pub timed_out: bool,
}

/// Formats `(host, port)` into a `ToSocketAddrs` / `tokio::net::lookup_host`-safe string,
/// wrapping bare IPv6 literals (`::1`, `fe80::1`, `2001:db8::1`) in brackets (`[::1]:port`)
/// while preserving already-bracketed `[::1]` and IPv4/DNS hostnames.
pub fn format_socket_target(host: &str, port: u16) -> String {
    let trimmed = host.trim();
    if trimmed.starts_with('[') && trimmed.ends_with(']') {
        format!("{trimmed}:{port}")
    } else if trimmed.contains(':') {
        format!("[{trimmed}]:{port}")
    } else {
        format!("{trimmed}:{port}")
    }
}

fn parse_six_hex_bytes(raw: &str) -> Option<[u8; 6]> {
    let trimmed = raw.trim();
    // Normalize `:`, `-`, and Cisco `.` (`aabb.ccdd.eeff`) into 12 hex characters
    let hex_only: String = trimmed
        .chars()
        .filter(|&c| c != ':' && c != '-' && c != '.' && !c.is_whitespace())
        .collect();
    if hex_only.len() == 12 && hex_only.chars().all(|c| c.is_ascii_hexdigit()) {
        let mut out = [0u8; 6];
        for idx in 0..6 {
            out[idx] = u8::from_str_radix(&hex_only[idx * 2..idx * 2 + 2], 16).ok()?;
        }
        return Some(out);
    }
    if trimmed.contains(':') || trimmed.contains('-') {
        let parts: Vec<&str> = trimmed.split([':', '-']).filter(|s| !s.is_empty()).collect();
        if parts.len() != 6 {
            return None;
        }
        let mut out = [0u8; 6];
        for (idx, part) in parts.iter().enumerate() {
            out[idx] = u8::from_str_radix(part, 16).ok()?;
        }
        return Some(out);
    }
    None
}

/// Parses `AA:BB:CC:DD:EE:FF`, `AA-BB-CC-DD-EE-FF`, `AABBCCDDEEFF`, or `AABB.CCDD.EEFF` into 6 bytes
/// and builds an IEEE 802 Wake-on-LAN Magic Packet (`0xFF * 6` + `MAC * 16` + optional 6-byte SecureOn password).
pub fn build_wol_magic_packet(
    mac_str: &str,
    secure_on_hex: Option<&str>,
) -> Result<Vec<u8>, NetUtilError> {
    let mac = parse_six_hex_bytes(mac_str)
        .ok_or_else(|| NetUtilError::InvalidMacAddress(mac_str.to_string()))?;

    let mut packet = Vec::with_capacity(108);
    packet.extend_from_slice(&[0xFF; 6]);
    for _ in 0..16 {
        packet.extend_from_slice(&mac);
    }

    if let Some(sec) = secure_on_hex.filter(|s| !s.trim().is_empty()) {
        let sec_bytes = parse_six_hex_bytes(sec).ok_or(NetUtilError::InvalidSecureOn)?;
        packet.extend_from_slice(&sec_bytes);
    }

    Ok(packet)
}

/// Broadcasts a Wake-on-LAN Magic Packet over UDP (auto-recovering if caller passed a MAC in `broadcast_ip`).
pub fn send_wake_on_lan(
    mac_str: &str,
    broadcast_ip: &str,
    udp_port: u16,
    secure_on_hex: Option<&str>,
) -> Result<usize, NetUtilError> {
    let payload = build_wol_magic_packet(mac_str, secure_on_hex)?;
    let trimmed_bcast = broadcast_ip.trim();
    // Guard against callers passing the target MAC string in `broadcast_ip`
    let effective_bcast = if trimmed_bcast.is_empty() || parse_six_hex_bytes(trimmed_bcast).is_some()
    {
        "255.255.255.255"
    } else {
        trimmed_bcast
    };
    let effective_port = if udp_port == 0 { 9 } else { udp_port };
    let target = format_socket_target(effective_bcast, effective_port);
    let dest_addr = target
        .to_socket_addrs()
        .map_err(|_| NetUtilError::DnsResolutionFailed(effective_bcast.to_string()))?
        .next()
        .ok_or_else(|| NetUtilError::DnsResolutionFailed(effective_bcast.to_string()))?;

    let bind_addr = if dest_addr.is_ipv6() {
        "[::]:0"
    } else {
        "0.0.0.0:0"
    };
    let sock = UdpSocket::bind(bind_addr)?;
    if dest_addr.is_ipv4() {
        sock.set_broadcast(true)?;
    }
    let sent = sock.send_to(&payload, dest_addr)?;
    // Send a second redundancy datagram when broadcasting to 255.255.255.255 over Wi-Fi
    if effective_bcast == "255.255.255.255" {
        let _ = sock.send_to(&payload, dest_addr);
        if udp_port == 0 {
            let _ = sock.send_to(&payload, SocketAddr::new(dest_addr.ip(), 7));
        }
    }
    Ok(sent)
}

/// Reads an initial protocol greeting banner (e.g., `SSH-2.0-OpenSSH_9.7`, `220 ProFTPD`, `RFB 003.008`)
/// without requiring root privileges.
pub fn grab_tcp_service_banner(host: &str, port: u16, timeout_ms: u64) -> Option<String> {
    let addr: SocketAddr = format_socket_target(host, port)
        .to_socket_addrs()
        .ok()?
        .next()?;
    let timeout = Duration::from_millis(timeout_ms.clamp(80, 5_000));
    let mut stream = TcpStream::connect_timeout(&addr, timeout).ok()?;
    let _ = stream.set_read_timeout(Some(Duration::from_millis(250)));
    let mut buf = [0u8; 192];
    let n = stream.read(&mut buf).ok().filter(|&len| len > 0)?;
    let line = String::from_utf8_lossy(&buf[..n])
        .lines()
        .next()
        .unwrap_or("")
        .trim()
        .to_string();
    if line.is_empty() {
        None
    } else {
        Some(line)
    }
}

/// Synchronous single-port TCP probe with sub-millisecond timing for Port Scanner tasks.
pub fn probe_tcp_port(host: &str, port: u16, timeout_ms: u64) -> Option<PortScanHit> {
    let addr: SocketAddr = format_socket_target(host, port)
        .to_socket_addrs()
        .ok()?
        .next()?;
    let start = Instant::now();
    let stream = TcpStream::connect_timeout(&addr, Duration::from_millis(timeout_ms.max(1)));
    match stream {
        Ok(mut sock) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            let _ = sock.set_read_timeout(Some(Duration::from_millis(120)));
            let mut buf = [0u8; 128];
            let banner = sock
                .read(&mut buf)
                .ok()
                .filter(|&n| n > 0)
                .map(|n| {
                    String::from_utf8_lossy(&buf[..n])
                        .lines()
                        .next()
                        .unwrap_or("")
                        .trim()
                        .to_string()
                })
                .filter(|s| !s.is_empty());

            Some(PortScanHit {
                port,
                open: true,
                connect_latency_ms: latency_ms,
                guessed_service: well_known_service_name(port).to_string(),
                service_banner: banner,
            })
        }
        Err(_) => None,
    }
}

/// Computes an RFC 1071 Internet Checksum over `data` (required on macOS for `SOCK_DGRAM` `IPPROTO_ICMP`).
fn rfc1071_icmp_checksum(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut chunks = data.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }
    if let Some(&rem) = chunks.remainder().first() {
        sum += u32::from(u16::from_be_bytes([rem, 0]));
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

fn system_ping_fallback_ms(host: &str, timeout_ms: u64) -> Option<u64> {
    let clean = host.trim();
    if clean.is_empty() || clean.starts_with('-') {
        return None;
    }
    #[cfg(target_os = "macos")]
    let output = std::process::Command::new("/sbin/ping")
        .args([
            "-n",
            "-c",
            "1",
            "-W",
            &timeout_ms.clamp(200, 3_000).to_string(),
            clean,
        ])
        .output()
        .ok()?;

    #[cfg(not(target_os = "macos"))]
    let output = {
        let secs = ((timeout_ms.clamp(250, 4_000) + 999) / 1000).to_string();
        std::process::Command::new("ping")
            .args(["-n", "-c", "1", "-W", &secs, clean])
            .output()
            .or_else(|_| {
                std::process::Command::new("/system/bin/ping")
                    .args(["-n", "-c", "1", "-W", &secs, clean])
                    .output()
            })
            .ok()?
    };

    if !output.status.success() {
        return None;
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    if let Some(idx) = stdout.find("time=") {
        let after = &stdout[idx + 5..];
        let num_str: String = after
            .chars()
            .take_while(|c| c.is_ascii_digit() || *c == '.')
            .collect();
        if let Ok(ms_f) = num_str.parse::<f64>() {
            return Some(ms_f.round() as u64);
        }
    }
    Some(1)
}

async fn tcp_connect_or_rst_rtt_ms(addr: SocketAddr, timeout: Duration) -> Option<u64> {
    let started = Instant::now();
    match tokio::time::timeout(timeout, tokio::net::TcpStream::connect(addr)).await {
        Ok(Ok(_stream)) => Some(started.elapsed().as_millis() as u64),
        Ok(Err(err)) if err.kind() == ErrorKind::ConnectionRefused => {
            // Remote IP stack actively replied with TCP RST -> host is alive & reachable!
            Some(started.elapsed().as_millis() as u64)
        }
        _ => None,
    }
}

/// Multi-strategy host reachability & latency ping (IPv4 & IPv6 safe).
/// - When `port > 0`: measures TCP `SYN -> SYN-ACK` or `SYN -> RST` (`ConnectionRefused`) round-trip latency.
/// - When `port == 0`: attempts unprivileged `SOCK_DGRAM` ICMP Echo (with RFC 1071 checksum & macOS IPv4 header stripping),
///   then OS `ping`, then concurrent multi-port TCP SYN/RST probing (`443, 80, 22, 3389, 5985, 8006, 53`).
pub async fn tcp_latency_ping(host: &str, port: u16, timeout_ms: u64) -> Option<u64> {
    let trimmed = host.trim();
    if trimmed.is_empty() {
        return None;
    }
    let timeout = Duration::from_millis(timeout_ms.max(20));

    if port > 0 {
        let target = format_socket_target(trimmed, port);
        let addr = tokio::net::lookup_host(&target).await.ok()?.next()?;
        return tcp_connect_or_rst_rtt_ms(addr, timeout).await;
    }

    // Resolve IP once via port 443 lookup
    let lookup_target = format_socket_target(trimmed, 443);
    let resolved = tokio::net::lookup_host(&lookup_target).await.ok()?.next()?;
    let target_ip = resolved.ip();

    // 1. Unprivileged SOCK_DGRAM ICMP Echo with RFC 1071 checksum
    let icmp_timeout = Duration::from_millis(timeout_ms.clamp(80, 1_500));
    if let Some((_, rtt_f64, true)) = tokio::task::spawn_blocking(move || {
        probe_unprivileged_dgram_icmp(target_ip, 64, icmp_timeout)
    })
    .await
    .ok()
    .flatten()
    {
        return Some(rtt_f64.round() as u64);
    }

    // 2. Concurrent TCP SYN/RST probe across common infrastructure ports
    let mut probe_set = JoinSet::new();
    for p in [443u16, 80, 22, 3389, 5985, 8006, 53] {
        let addr = SocketAddr::new(target_ip, p);
        let per_port = Duration::from_millis(timeout_ms.clamp(80, 1_200));
        probe_set.spawn(async move { tcp_connect_or_rst_rtt_ms(addr, per_port).await });
    }
    while let Some(res) = probe_set.join_next().await {
        if let Ok(Some(ms)) = res {
            probe_set.abort_all();
            return Some(ms);
        }
    }

    // 3. OS `ping` utility fallback (handles ICMP-only hosts where TCP SYN is silently dropped by firewall)
    let host_owned = trimmed.to_string();
    tokio::task::spawn_blocking(move || system_ping_fallback_ms(&host_owned, timeout_ms))
        .await
        .ok()
        .flatten()
}

/// Bounded-concurrency async TCP port scanner with optional protocol banner grabbing.
pub async fn scan_tcp_ports(
    host: &str,
    start_port: u16,
    end_port: u16,
    timeout_ms: u64,
    concurrency: usize,
) -> Vec<PortScanHit> {
    let trimmed = host.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }
    let lo = start_port.min(end_port).max(1);
    let hi = start_port.max(end_port).max(lo);

    let lookup_target = format_socket_target(trimmed, lo);
    let resolved_addr = match tokio::net::lookup_host(&lookup_target)
        .await
        .ok()
        .and_then(|mut it| it.next())
    {
        Some(a) => a,
        None => return Vec::new(),
    };
    let target_ip = resolved_addr.ip();
    let connect_timeout = Duration::from_millis(timeout_ms.clamp(15, 10_000));
    let max_workers = concurrency.clamp(1, 512);
    let sem = Arc::new(Semaphore::new(max_workers));
    let mut join_set = JoinSet::new();
    let mut hits = Vec::new();

    for port in lo..=hi {
        // Drain completed tasks periodically so 1..=65535 never queues 65k futures in RAM simultaneously
        while join_set.len() >= max_workers * 2 {
            if let Some(Ok(Some(hit))) = join_set.join_next().await {
                hits.push(hit);
            }
        }
        let Ok(permit) = Arc::clone(&sem).acquire_owned().await else {
            break;
        };
        let addr = SocketAddr::new(target_ip, port);
        join_set.spawn(async move {
            let _permit = permit;
            let started = Instant::now();
            let mut stream = tokio::time::timeout(
                connect_timeout,
                tokio::net::TcpStream::connect(addr),
            )
            .await
            .ok()?
            .ok()?;
            let latency_ms = started.elapsed().as_millis() as u64;

            let mut buf = [0u8; 128];
            let banner = tokio::time::timeout(Duration::from_millis(90), stream.read(&mut buf))
                .await
                .ok()
                .and_then(|r| r.ok())
                .filter(|&n| n > 0)
                .map(|n| {
                    String::from_utf8_lossy(&buf[..n])
                        .lines()
                        .next()
                        .unwrap_or("")
                        .trim()
                        .to_string()
                })
                .filter(|s| !s.is_empty());

            Some(PortScanHit {
                port,
                open: true,
                connect_latency_ms: latency_ms,
                guessed_service: well_known_service_name(port).to_string(),
                service_banner: banner,
            })
        });
    }

    while let Some(res) = join_set.join_next().await {
        if let Ok(Some(hit)) = res {
            hits.push(hit);
        }
    }
    hits.sort_by_key(|h| h.port);
    hits
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TcpTtlProbeOutcome {
    ReachedDestination,
    HopExpired,
    TimedOut,
}

/// Creates a non-blocking TCP `socket2::Socket` with `IP_TTL` (IPv4) or `IPV6_UNICAST_HOPS` (IPv6)
/// applied **before** `connect()` so the very first outbound `SYN` carries the requested hop limit.
pub fn create_ttl_configured_tcp_socket(
    target_addr: SocketAddr,
    ttl: u8,
) -> std::io::Result<Socket> {
    let domain = if target_addr.is_ipv4() {
        Domain::IPV4
    } else {
        Domain::IPV6
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    let hops = u32::from(ttl.max(1));
    if target_addr.is_ipv4() {
        socket.set_ttl_v4(hops)?;
    } else {
        socket.set_unicast_hops_v6(hops)?;
    }
    socket.set_nonblocking(true)?;
    Ok(socket)
}

fn is_loopback_redirected(local_ip: Option<IpAddr>, target_ip: IpAddr) -> bool {
    !target_ip.is_loopback() && local_ip.map(|ip| ip.is_loopback()).unwrap_or(false)
}

fn classify_post_connect_socket(socket: &Socket, target_addr: SocketAddr) -> TcpTtlProbeOutcome {
    let local_ip = socket
        .local_addr()
        .ok()
        .and_then(|sa| sa.as_socket())
        .map(|s| s.ip());
    match socket.take_error() {
        Ok(None) => {
            if socket.peer_addr().is_ok() {
                if is_loopback_redirected(local_ip, target_addr.ip()) {
                    TcpTtlProbeOutcome::TimedOut
                } else {
                    TcpTtlProbeOutcome::ReachedDestination
                }
            } else {
                TcpTtlProbeOutcome::HopExpired
            }
        }
        Ok(Some(err)) if err.kind() == ErrorKind::ConnectionRefused => {
            if is_loopback_redirected(local_ip, target_addr.ip()) {
                TcpTtlProbeOutcome::TimedOut
            } else {
                // Destination TCP stack actively replied with RST -> destination reached!
                TcpTtlProbeOutcome::ReachedDestination
            }
        }
        Ok(Some(_)) | Err(_) => {
            // EHOSTUNREACH / ENETUNREACH / ETIMEDOUT from ICMP Time Exceeded -> hop expired!
            TcpTtlProbeOutcome::HopExpired
        }
    }
}

fn probe_tcp_with_ttl_sync(
    target_addr: SocketAddr,
    ttl: u8,
    timeout: Duration,
) -> TcpTtlProbeOutcome {
    if ttl == 0 {
        return TcpTtlProbeOutcome::HopExpired;
    }
    let Ok(socket) = create_ttl_configured_tcp_socket(target_addr, ttl) else {
        return TcpTtlProbeOutcome::HopExpired;
    };

    match socket.connect(&SockAddr::from(target_addr)) {
        Ok(()) => return classify_post_connect_socket(&socket, target_addr),
        Err(err) if err.kind() == ErrorKind::ConnectionRefused => {
            return classify_post_connect_socket(&socket, target_addr);
        }
        Err(err)
            if err.raw_os_error() == Some(libc::EINPROGRESS)
                || err.kind() == ErrorKind::WouldBlock => {}
        Err(_) => return TcpTtlProbeOutcome::HopExpired,
    }

    let deadline = Instant::now() + timeout;
    loop {
        let now = Instant::now();
        if now >= deadline {
            return TcpTtlProbeOutcome::TimedOut;
        }
        let remaining_ms = deadline
            .saturating_duration_since(now)
            .as_millis()
            .clamp(1, i32::MAX as u128) as libc::c_int;

        let mut pfd = libc::pollfd {
            fd: socket.as_raw_fd(),
            events: libc::POLLOUT | libc::POLLERR | libc::POLLHUP,
            revents: 0,
        };
        let rc = unsafe { libc::poll(&mut pfd, 1, remaining_ms) };
        if rc == 0 {
            return TcpTtlProbeOutcome::TimedOut;
        }
        if rc < 0 {
            let os_err = std::io::Error::last_os_error();
            if os_err.kind() == ErrorKind::Interrupted {
                continue;
            }
            return TcpTtlProbeOutcome::HopExpired;
        }
        return classify_post_connect_socket(&socket, target_addr);
    }
}

/// Unprivileged Linux / Android UDP + `IP_RECVERR` / `IPV6_RECVERR` (`MSG_ERRQUEUE`) TTL probe
/// that extracts exact intermediate router IPs (`SO_EE_OFFENDER`) without `CAP_NET_RAW`!
#[cfg(any(target_os = "linux", target_os = "android"))]
fn probe_linux_udp_errqueue_hop(
    target_ip: IpAddr,
    ttl: u8,
    timeout: Duration,
) -> Option<(String, f64, bool)> {
    let domain = if target_ip.is_ipv4() {
        Domain::IPV4
    } else {
        Domain::IPV6
    };
    let sock = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP)).ok()?;
    let fd = sock.as_raw_fd();
    let enable: libc::c_int = 1;
    unsafe {
        if target_ip.is_ipv4() {
            if libc::setsockopt(
                fd,
                libc::SOL_IP,
                libc::IP_RECVERR,
                &enable as *const _ as *const libc::c_void,
                std::mem::size_of::<libc::c_int>() as libc::socklen_t,
            ) != 0
            {
                return None;
            }
        } else if libc::setsockopt(
            fd,
            libc::SOL_IPV6,
            libc::IPV6_RECVERR,
            &enable as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        ) != 0
        {
            return None;
        }
    }

    if target_ip.is_ipv4() {
        sock.set_ttl_v4(u32::from(ttl.max(1))).ok()?;
    } else {
        sock.set_unicast_hops_v6(u32::from(ttl.max(1))).ok()?;
    }
    sock.set_nonblocking(true).ok()?;

    let dst_port = 33434u16.saturating_add(u16::from(ttl));
    let dst = SocketAddr::new(target_ip, dst_port);
    let payload = [b'N', b'X', ttl];
    let started = Instant::now();
    let _ = sock.send_to(&payload, &SockAddr::from(dst));

    let wait_ms = timeout.as_millis().clamp(1, 3_000) as libc::c_int;
    let mut pfd = libc::pollfd {
        fd,
        events: libc::POLLIN | libc::POLLERR,
        revents: 0,
    };
    let rc = unsafe { libc::poll(&mut pfd, 1, wait_ms) };
    if rc <= 0 {
        return None;
    }
    let rtt_ms = (started.elapsed().as_micros() as f64) / 1000.0;

    unsafe {
        let mut data_buf = [0u8; 64];
        let mut iov = libc::iovec {
            iov_base: data_buf.as_mut_ptr() as *mut libc::c_void,
            iov_len: data_buf.len(),
        };
        let mut ctrl_buf = [0u8; 256];
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = ctrl_buf.as_mut_ptr() as *mut libc::c_void;
        msg.msg_controllen = ctrl_buf.len() as _;

        let n = libc::recvmsg(fd, &mut msg, libc::MSG_ERRQUEUE);
        if n < 0 {
            return None;
        }

        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            let level = (*cmsg).cmsg_level;
            let ctype = (*cmsg).cmsg_type;
            if (level == libc::SOL_IP && ctype == libc::IP_RECVERR)
                || (level == libc::SOL_IPV6 && ctype == libc::IPV6_RECVERR)
            {
                let data_ptr = libc::CMSG_DATA(cmsg);
                // struct sock_extended_err is 16 bytes, followed immediately by offender sockaddr
                let ee_origin = *data_ptr.add(4);
                let ee_type = *data_ptr.add(5);
                // SO_EE_ORIGIN_ICMP (2) or SO_EE_ORIGIN_ICMP6 (3)
                if ee_origin == 2 || ee_origin == 3 {
                    let offender_ptr = data_ptr.add(16) as *const libc::sockaddr;
                    let family = (*offender_ptr).sa_family as libc::c_int;
                    if family == libc::AF_INET {
                        let sin = *(offender_ptr as *const libc::sockaddr_in);
                        let ip = IpAddr::V4(std::net::Ipv4Addr::from(u32::from_be(
                            sin.sin_addr.s_addr,
                        )));
                        // ICMPv4 Type 3 = Destination/Port Unreachable -> reached target!
                        // ICMPv4 Type 11 = Time Exceeded -> intermediate router!
                        let reached = ip == target_ip && ee_type != 11;
                        return Some((ip.to_string(), rtt_ms, reached));
                    } else if family == libc::AF_INET6 {
                        let sin6 = *(offender_ptr as *const libc::sockaddr_in6);
                        let ip = IpAddr::V6(std::net::Ipv6Addr::from(sin6.sin6_addr.s6_addr));
                        // ICMPv6 Type 3 = Time Exceeded; Type 1 = Dest/Port Unreachable
                        let reached = ip == target_ip && ee_type != 3;
                        return Some((ip.to_string(), rtt_ms, reached));
                    }
                }
            }
            cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
        }
    }
    None
}

#[cfg(target_os = "macos")]
fn system_traceroute_macos(
    target_ip: IpAddr,
    max_hops: u8,
    per_hop_timeout_ms: u64,
) -> Option<Vec<TracerouteHopResult>> {
    let wait_sec = ((per_hop_timeout_ms.clamp(200, 3_000) + 999) / 1000).max(1);
    let bin = if target_ip.is_ipv6() {
        "/usr/sbin/traceroute6"
    } else {
        "/usr/sbin/traceroute"
    };
    let mut args = Vec::new();
    args.push("-n".to_string());
    args.push("-q".to_string());
    args.push("1".to_string());
    args.push("-w".to_string());
    args.push(wait_sec.to_string());
    args.push("-m".to_string());
    args.push(max_hops.to_string());
    args.push(target_ip.to_string());

    let output = std::process::Command::new(bin).args(&args).output().ok()?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    let mut hops = Vec::new();
    for line in stdout.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with("traceroute") {
            continue;
        }
        let mut parts = trimmed.split_whitespace();
        let Some(ttl_str) = parts.next() else { continue };
        let Ok(ttl) = ttl_str.parse::<u8>() else { continue };
        let Some(addr_tok) = parts.next() else { continue };
        if addr_tok == "*" {
            hops.push(TracerouteHopResult {
                ttl,
                responder_addr: String::from("*"),
                rtt_ms: 0.0,
                reached_destination: false,
                timed_out: true,
            });
            continue;
        }
        let mut rtt_ms = 1.0f64;
        let rest: Vec<&str> = parts.collect();
        for w in rest.windows(2) {
            if w[1].eq_ignore_ascii_case("ms") {
                if let Ok(v) = w[0].parse::<f64>() {
                    rtt_ms = v;
                    break;
                }
            }
        }
        let reached = addr_tok
            .parse::<IpAddr>()
            .map(|ip| ip == target_ip)
            .unwrap_or(false);
        hops.push(TracerouteHopResult {
            ttl,
            responder_addr: addr_tok.to_string(),
            rtt_ms,
            reached_destination: reached,
            timed_out: false,
        });
        if reached {
            break;
        }
    }
    if hops.is_empty() {
        None
    } else {
        Some(hops)
    }
}

/// Optional unprivileged `SOCK_DGRAM` ICMP Echo probe with RFC 1071 checksum and macOS IPv4 header stripping.
fn probe_unprivileged_dgram_icmp(
    target_ip: IpAddr,
    ttl: u8,
    timeout: Duration,
) -> Option<(String, f64, bool)> {
    let (domain, proto, echo_type, reply_type) = match target_ip {
        IpAddr::V4(_) => (Domain::IPV4, Protocol::ICMPV4, 8u8, 0u8),
        IpAddr::V6(_) => (Domain::IPV6, Protocol::ICMPV6, 128u8, 129u8),
    };
    let sock = Socket::new(domain, Type::DGRAM, Some(proto)).ok()?;
    if target_ip.is_ipv4() {
        sock.set_ttl_v4(u32::from(ttl.max(1))).ok()?;
    } else {
        sock.set_unicast_hops_v6(u32::from(ttl.max(1))).ok()?;
    }
    sock.set_read_timeout(Some(timeout)).ok()?;

    let mut echo_req = [echo_type, 0, 0, 0, 0x4E, 0x58, 0, ttl];
    if target_ip.is_ipv4() {
        let cksum = rfc1071_icmp_checksum(&echo_req);
        echo_req[2] = (cksum >> 8) as u8;
        echo_req[3] = (cksum & 0xFF) as u8;
    }
    let started = Instant::now();
    sock.send_to(&echo_req, &SockAddr::from(SocketAddr::new(target_ip, 0)))
        .ok()?;

    let mut recv_buf = [std::mem::MaybeUninit::<u8>::uninit(); 160];
    let (n, peer_sock) = sock.recv_from(&mut recv_buf).ok()?;
    if n == 0 {
        return None;
    }
    let elapsed_ms = (started.elapsed().as_micros() as f64) / 1000.0;
    let peer_addr = peer_sock.as_socket()?;
    let slice: &[u8] =
        unsafe { std::slice::from_raw_parts(recv_buf.as_ptr() as *const u8, n) };
    // macOS SOCK_DGRAM IPPROTO_ICMP prepends the 20+ byte IPv4 header (`0x45..`)
    let icmp_offset = if target_ip.is_ipv4() && (slice[0] >> 4) == 4 {
        let ihl = ((slice[0] & 0x0F) as usize) * 4;
        if ihl < slice.len() { ihl } else { 0 }
    } else {
        0
    };
    let icmp_type = slice.get(icmp_offset).copied().unwrap_or(0xFF);
    let reached = peer_addr.ip() == target_ip && icmp_type == reply_type;
    Some((peer_addr.ip().to_string(), elapsed_ms, reached))
}

/// Unprivileged TTL-stepped Traceroute tailored for Android, macOS, and Linux sandboxes:
/// - On Linux & Android: uses UDP + `IP_RECVERR`/`IPV6_RECVERR` (`MSG_ERRQUEUE`) to extract real
///   intermediate router `SO_EE_OFFENDER` IPs without `CAP_NET_RAW`.
/// - On macOS: uses `/usr/sbin/traceroute` when interactive (`max_hops > 1 && per_hop_timeout_ms >= 150`),
///   plus RFC-1071-checksummed `SOCK_DGRAM` ICMP and `socket2` TTL-stepped TCP SYN fallback.
pub fn trace_route_hops(
    host: &str,
    probe_port: u16,
    max_hops: u8,
    per_hop_timeout_ms: u64,
) -> Result<Vec<TracerouteHopResult>, NetUtilError> {
    let effective_port = if probe_port == 0 { 443 } else { probe_port };
    let target_addr: SocketAddr = format_socket_target(host, effective_port)
        .to_socket_addrs()
        .map_err(|_| NetUtilError::DnsResolutionFailed(host.to_string()))?
        .next()
        .ok_or_else(|| NetUtilError::DnsResolutionFailed(host.to_string()))?;

    let target_ip = target_addr.ip();
    let capped_hops = max_hops.clamp(1, 32);

    #[cfg(target_os = "macos")]
    if capped_hops > 1 && per_hop_timeout_ms >= 150 && !target_ip.is_loopback() {
        if let Some(sys_hops) = system_traceroute_macos(target_ip, capped_hops, per_hop_timeout_ms)
        {
            return Ok(sys_hops);
        }
    }

    let timeout = Duration::from_millis(per_hop_timeout_ms.clamp(50, 3_000));
    let icmp_timeout = Duration::from_millis((per_hop_timeout_ms / 2).clamp(25, 500));
    let mut hops = Vec::with_capacity(capped_hops as usize);

    for ttl in 1..=capped_hops {
        // 1. Linux / Android unprivileged UDP + IP_RECVERR MSG_ERRQUEUE router-IP discovery
        #[cfg(any(target_os = "linux", target_os = "android"))]
        if let Some((responder, errq_rtt, reached)) =
            probe_linux_udp_errqueue_hop(target_ip, ttl, icmp_timeout)
        {
            hops.push(TracerouteHopResult {
                ttl,
                responder_addr: responder,
                rtt_ms: errq_rtt,
                reached_destination: reached,
                timed_out: false,
            });
            if reached {
                break;
            }
            continue;
        }

        // 2. Unprivileged DGRAM ICMP Echo probe (with RFC 1071 checksum & macOS IPv4 header stripping)
        if let Some((responder, icmp_rtt, reached)) =
            probe_unprivileged_dgram_icmp(target_ip, ttl, icmp_timeout)
        {
            hops.push(TracerouteHopResult {
                ttl,
                responder_addr: responder,
                rtt_ms: icmp_rtt,
                reached_destination: reached,
                timed_out: false,
            });
            if reached {
                break;
            }
            continue;
        }

        // 3. Non-blocking TCP SYN probe with IP_TTL / IPV6_UNICAST_HOPS set BEFORE connect()
        let started = Instant::now();
        let outcome = probe_tcp_with_ttl_sync(target_addr, ttl, timeout);
        let rtt_ms = (started.elapsed().as_micros() as f64) / 1000.0;

        match outcome {
            TcpTtlProbeOutcome::ReachedDestination => {
                hops.push(TracerouteHopResult {
                    ttl,
                    responder_addr: target_ip.to_string(),
                    rtt_ms,
                    reached_destination: true,
                    timed_out: false,
                });
                break;
            }
            TcpTtlProbeOutcome::HopExpired | TcpTtlProbeOutcome::TimedOut => {
                hops.push(TracerouteHopResult {
                    ttl,
                    responder_addr: String::from("*"),
                    rtt_ms,
                    reached_destination: false,
                    timed_out: true,
                });
            }
        }
    }

    Ok(hops)
}

/// Async Tokio variant of [`trace_route_hops`].
pub async fn trace_route_hops_async(
    host: &str,
    probe_port: u16,
    max_hops: u8,
    per_hop_timeout_ms: u64,
) -> Result<Vec<TracerouteHopResult>, NetUtilError> {
    let host_owned = host.to_string();
    tokio::task::spawn_blocking(move || {
        trace_route_hops(&host_owned, probe_port, max_hops, per_hop_timeout_ms)
    })
    .await
    .map_err(|e| std::io::Error::other(e.to_string()))?
}

pub fn well_known_service_name(port: u16) -> &'static str {
    match port {
        20 | 21 => "FTP",
        22 => "SSH / SFTP",
        23 => "Telnet",
        25 | 587 => "SMTP",
        53 => "DNS",
        80 => "HTTP",
        88 => "Kerberos",
        110 => "POP3",
        111 => "RPCBind",
        135 => "MS-RPC",
        139 => "NetBIOS-SSN",
        143 => "IMAP",
        389 => "LDAP",
        443 => "HTTPS",
        445 => "SMB",
        465 => "SMTPS",
        636 => "LDAPS",
        990 => "FTPS",
        993 => "IMAPS",
        995 => "POP3S",
        1433 => "MSSQL",
        1521 => "Oracle DB",
        2049 => "NFS",
        2179 => "Hyper-V VMConnect",
        2375 => "Docker API",
        2376 => "Docker TLS",
        3306 => "MySQL",
        3389 => "RDP",
        5432 => "PostgreSQL",
        5900..=5909 => "VNC / RFB",
        5985 => "WinRM HTTP",
        5986 => "WinRM HTTPS",
        6379 => "Redis",
        6443 => "Kubernetes API",
        8006 => "Proxmox VE",
        8080 | 8443 => "HTTP-Alt / Mgmt",
        9000 | 9090 => "Web / Prometheus",
        27017 => "MongoDB",
        _ => "TCP Service",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncWriteExt;
    use tokio::net::TcpListener;

    #[test]
    fn format_socket_target_handles_ipv4_dns_and_ipv6_literals() {
        assert_eq!(format_socket_target("example.com", 443), "example.com:443");
        assert_eq!(format_socket_target("127.0.0.1", 22), "127.0.0.1:22");
        assert_eq!(format_socket_target("::1", 2222), "[::1]:2222");
        assert_eq!(format_socket_target("[::1]", 2222), "[::1]:2222");
        assert_eq!(
            format_socket_target("2001:db8::abcd", 5900),
            "[2001:db8::abcd]:5900"
        );
    }

    #[test]
    fn wol_magic_packet_builds_and_transmits_108_byte_secureon_udp_frame() {
        let pkt = build_wol_magic_packet("00:11:22:AA:BB:CC", None).expect("valid MAC");
        assert_eq!(pkt.len(), 102);
        assert_eq!(&pkt[0..6], &[0xFF; 6]);
        assert_eq!(&pkt[6..12], &[0x00, 0x11, 0x22, 0xAA, 0xBB, 0xCC]);

        let listener = UdpSocket::bind("127.0.0.1:0").expect("bind UDP receiver");
        listener
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let udp_port = listener.local_addr().unwrap().port();

        let sent = send_wake_on_lan(
            "00-11-22-AA-BB-CC",
            "127.0.0.1",
            udp_port,
            Some("DE:AD:BE:EF:01:02"),
        )
        .expect("send WoL UDP");
        assert_eq!(sent, 108);

        let mut recv = [0u8; 128];
        let (n, _) = listener.recv_from(&mut recv).expect("receive WoL UDP");
        assert_eq!(n, 108);
        assert_eq!(&recv[0..6], &[0xFF; 6]);
        assert_eq!(&recv[102..108], &[0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02]);
    }

    #[test]
    fn ipv6_traceroute_and_ttl_before_connect_behavior() {
        // Bare IPv6 literal "::1" and bracketed "[::1]" must both resolve without EINVAL
        let res_bare = trace_route_hops("::1", 65530, 1, 100);
        assert!(res_bare.is_ok(), "Bare IPv6 traceroute failed: {res_bare:?}");
        let res_bracketed = trace_route_hops("[::1]", 65530, 1, 100);
        assert!(
            res_bracketed.is_ok(),
            "Bracketed IPv6 traceroute failed: {res_bracketed:?}"
        );

        // Verify socket2 sets IP_TTL = 1 BEFORE connect() is ever called
        let v4_addr: SocketAddr = "198.51.100.1:443".parse().unwrap();
        let sock = create_ttl_configured_tcp_socket(v4_addr, 1).expect("create v4 ttl=1 socket");
        assert_eq!(sock.ttl_v4().unwrap(), 1);

        // Against an unroutable TEST-NET-2 address (198.51.100.1), Hop 1 MUST NOT falsely claim reached_destination!
        let hops = trace_route_hops("198.51.100.1", 443, 1, 60).expect("trace_route_hops");
        assert_eq!(hops.len(), 1);
        assert_eq!(hops[0].ttl, 1);
        assert!(
            !hops[0].reached_destination,
            "Expiring/unroutable TTL=1 hop must never falsely claim destination reached"
        );
    }

    #[tokio::test]
    async fn concurrent_port_scanner_and_tcp_latency_ping_loopback() {
        // Bind two listeners inside a contiguous port window
        let l1 = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let p1 = l1.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((mut s, _)) = l1.accept().await {
                let _ = s.write_all(b"SSH-2.0-NexusTS_Test\r\n").await;
            }
        });

        let ping_ms = tcp_latency_ping("127.0.0.1", p1, 500).await;
        assert!(ping_ms.is_some(), "Open loopback port should respond to TCP latency ping");

        // Scan only ports owned by this test (never sweep p1-4..=p1+4 across parallel tests' ephemeral listeners!)
        let hits = scan_tcp_ports("127.0.0.1", p1, p1, 200, 16).await;

        let matched = hits.iter().find(|h| h.port == p1).expect("scanner found p1");
        assert!(matched.open);
        assert_eq!(
            matched.service_banner.as_deref(),
            Some("SSH-2.0-NexusTS_Test")
        );

        // Universal ping (port = 0) and closed-port TCP RST ping must both report live host RTT on loopback
        let universal_ms = tcp_latency_ping("127.0.0.1", 0, 500).await;
        assert!(universal_ms.is_some(), "port=0 universal ping must succeed on 127.0.0.1");

        let closed_probe = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let closed_port = closed_probe.local_addr().unwrap().port();
        drop(closed_probe);
        let closed_hits = scan_tcp_ports("127.0.0.1", closed_port, closed_port, 150, 4).await;
        assert!(closed_hits.iter().all(|h| !h.open), "Closed port must not report open");
        let rst_ms = tcp_latency_ping("127.0.0.1", closed_port, 500).await;
        assert!(rst_ms.is_some(), "TCP RST on closed port proves host is alive");

        // Cisco 3-group MAC format (aabb.ccdd.eeff)
        let cisco_pkt = build_wol_magic_packet("0011.22aa.bbcc", None).expect("Cisco MAC WoL");
        assert_eq!(cisco_pkt.len(), 102);
        assert_eq!(&cisco_pkt[6..12], &[0x00, 0x11, 0x22, 0xAA, 0xBB, 0xCC]);
    }
}
