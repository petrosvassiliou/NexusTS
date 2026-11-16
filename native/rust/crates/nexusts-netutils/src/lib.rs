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
    let mut out = [0u8; 6];
    if trimmed.contains(':') || trimmed.contains('-') {
        let parts: Vec<&str> = trimmed.split([':', '-']).filter(|s| !s.is_empty()).collect();
        if parts.len() != 6 {
            return None;
        }
        for (idx, part) in parts.iter().enumerate() {
            out[idx] = u8::from_str_radix(part, 16).ok()?;
        }
        Some(out)
    } else if trimmed.len() == 12 {
        for idx in 0..6 {
            out[idx] = u8::from_str_radix(&trimmed[idx * 2..idx * 2 + 2], 16).ok()?;
        }
        Some(out)
    } else {
        None
    }
}

/// Parses `AA:BB:CC:DD:EE:FF` or `AA-BB-CC-DD-EE-FF` into 6 bytes and builds an IEEE 802
/// Wake-on-LAN Magic Packet (`0xFF * 6` + `MAC * 16` + optional 6-byte SecureOn password).
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

/// Broadcasts a Wake-on-LAN Magic Packet over UDP.
pub fn send_wake_on_lan(
    mac_str: &str,
    broadcast_ip: &str,
    udp_port: u16,
    secure_on_hex: Option<&str>,
) -> Result<usize, NetUtilError> {
    let payload = build_wol_magic_packet(mac_str, secure_on_hex)?;
    let target = format_socket_target(
        if broadcast_ip.trim().is_empty() {
            "255.255.255.255"
        } else {
            broadcast_ip
        },
        if udp_port == 0 { 9 } else { udp_port },
    );
    let dest_addr = target
        .to_socket_addrs()
        .map_err(|_| NetUtilError::DnsResolutionFailed(broadcast_ip.to_string()))?
        .next()
        .ok_or_else(|| NetUtilError::DnsResolutionFailed(broadcast_ip.to_string()))?;

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

/// Synchronous single-port TCP probe with sub-millisecond timing for Ping/Scanner tasks.
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

/// Async TCP handshake latency ping (IPv4 & IPv6 safe).
pub async fn tcp_latency_ping(host: &str, port: u16, timeout_ms: u64) -> Option<u64> {
    let target = format_socket_target(host, port);
    let addr = tokio::net::lookup_host(&target).await.ok()?.next()?;
    let timeout = Duration::from_millis(timeout_ms.max(1));
    let started = Instant::now();
    match tokio::time::timeout(timeout, tokio::net::TcpStream::connect(addr)).await {
        Ok(Ok(_stream)) => Some(started.elapsed().as_millis() as u64),
        _ => None,
    }
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
    let sem = Arc::new(Semaphore::new(concurrency.clamp(1, 512)));
    let mut join_set = JoinSet::new();

    for port in lo..=hi {
        let sem_clone = Arc::clone(&sem);
        let addr = SocketAddr::new(target_ip, port);
        join_set.spawn(async move {
            let _permit = sem_clone.acquire_owned().await.ok()?;
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

    let mut hits = Vec::new();
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

async fn probe_tcp_with_ttl_tokio(
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

    let std_stream: std::net::TcpStream = socket.into();
    let Ok(tokio_stream) = tokio::net::TcpStream::from_std(std_stream) else {
        return TcpTtlProbeOutcome::HopExpired;
    };

    match tokio::time::timeout(timeout, tokio_stream.writable()).await {
        Ok(Ok(())) => {
            let local_ip = tokio_stream.local_addr().ok().map(|s| s.ip());
            match tokio_stream.take_error() {
                Ok(None) => {
                    if tokio_stream.peer_addr().is_ok() {
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
                        TcpTtlProbeOutcome::ReachedDestination
                    }
                }
                Ok(Some(_)) | Err(_) => TcpTtlProbeOutcome::HopExpired,
            }
        }
        Ok(Err(_)) => TcpTtlProbeOutcome::HopExpired,
        Err(_) => TcpTtlProbeOutcome::TimedOut,
    }
}

/// Optional unprivileged `SOCK_DGRAM` ICMP Echo probe (permitted when kernel `ping_group_range` allows).
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

    let echo_req = [echo_type, 0, 0, 0, 0, 0, 0, ttl];
    let started = Instant::now();
    sock.send_to(&echo_req, &SockAddr::from(SocketAddr::new(target_ip, 0)))
        .ok()?;

    let mut recv_buf = [std::mem::MaybeUninit::<u8>::uninit(); 128];
    let (n, peer_sock) = sock.recv_from(&mut recv_buf).ok()?;
    let elapsed_ms = (started.elapsed().as_micros() as f64) / 1000.0;
    let peer_addr = peer_sock.as_socket()?;
    let first_byte = if n > 0 {
        unsafe { recv_buf[0].assume_init() }
    } else {
        0xFF
    };
    let reached = peer_addr.ip() == target_ip && first_byte == reply_type;
    Some((peer_addr.ip().to_string(), elapsed_ms, reached))
}

/// Unprivileged TTL-stepped Traceroute tailored for Android, macOS, and Linux sandboxes.
/// Sets `IP_TTL` / `IPV6_UNICAST_HOPS` **before** `connect()` via `socket2::Socket` and
/// optionally probes via unprivileged `DGRAM` ICMP Echo when allowed by the kernel, so
/// expiring `TTL=1` hops never falsely claim destination reached.
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
    let timeout = Duration::from_millis(per_hop_timeout_ms.clamp(50, 3_000));
    let icmp_timeout = Duration::from_millis((per_hop_timeout_ms / 2).clamp(25, 400));
    let capped_hops = max_hops.clamp(1, 32);
    let mut hops = Vec::with_capacity(capped_hops as usize);

    for ttl in 1..=capped_hops {
        // 1. Optional unprivileged DGRAM ICMP Echo probe
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

        // 2. Non-blocking TCP SYN probe with IP_TTL / IPV6_UNICAST_HOPS set BEFORE connect()
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

/// Async Tokio variant of [`trace_route_hops`] using `AsyncFd` readiness checks on the
/// TTL-configured `socket2::Socket`.
pub async fn trace_route_hops_async(
    host: &str,
    probe_port: u16,
    max_hops: u8,
    per_hop_timeout_ms: u64,
) -> Result<Vec<TracerouteHopResult>, NetUtilError> {
    let effective_port = if probe_port == 0 { 443 } else { probe_port };
    let lookup = format_socket_target(host, effective_port);
    let target_addr = tokio::net::lookup_host(&lookup)
        .await
        .map_err(|_| NetUtilError::DnsResolutionFailed(host.to_string()))?
        .next()
        .ok_or_else(|| NetUtilError::DnsResolutionFailed(host.to_string()))?;

    let target_ip = target_addr.ip();
    let timeout = Duration::from_millis(per_hop_timeout_ms.clamp(50, 3_000));
    let capped_hops = max_hops.clamp(1, 32);
    let mut hops = Vec::with_capacity(capped_hops as usize);

    for ttl in 1..=capped_hops {
        let started = Instant::now();
        let outcome = probe_tcp_with_ttl_tokio(target_addr, ttl, timeout).await;
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

pub fn well_known_service_name(port: u16) -> &'static str {
    match port {
        21 => "FTP",
        22 => "SSH / SFTP",
        23 => "Telnet",
        53 => "DNS",
        80 => "HTTP",
        88 => "Kerberos",
        389 => "LDAP",
        443 => "HTTPS",
        445 => "SMB",
        636 => "LDAPS",
        3306 => "MySQL",
        3389 => "RDP",
        5432 => "PostgreSQL",
        5900..=5909 => "VNC / RFB",
        5985 | 5986 => "WinRM",
        6443 => "Kubernetes API",
        8006 => "Proxmox VE",
        8080 | 8443 => "HTTP-Alt / Mgmt",
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

        let start_port = p1.saturating_sub(4).max(1);
        let end_port = p1.saturating_add(4);
        let hits = scan_tcp_ports("127.0.0.1", start_port, end_port, 200, 16).await;

        let matched = hits.iter().find(|h| h.port == p1).expect("scanner found p1");
        assert!(matched.open);
        assert_eq!(
            matched.service_banner.as_deref(),
            Some("SSH-2.0-NexusTS_Test")
        );
    }
}
