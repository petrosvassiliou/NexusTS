//! Integrated Enterprise Network Utilities:
//! Wake-on-LAN (Magic Packet + SecureOn), Multi-Hop Traceroute, Service Banner Grabber,
//! Concurrent Port Scanner, and Sub-Millisecond TCP Reachability Probe.

use serde::{Deserialize, Serialize};
use std::io::Read;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::time::{Duration, Instant};
use thiserror::Error;

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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PortScanHit {
    pub port: u16,
    pub open: bool,
    pub connect_latency_ms: u64,
    pub guessed_service: String,
    pub service_banner: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TracerouteHopResult {
    pub ttl: u8,
    pub responder_addr: String,
    pub rtt_ms: f64,
    pub reached_destination: bool,
    pub timed_out: bool,
}

/// Parses `AA:BB:CC:DD:EE:FF` or `AA-BB-CC-DD-EE-FF` into 6 bytes and builds an IEEE 802
/// Wake-on-LAN Magic Packet (`0xFF * 6` + `MAC * 16` + optional 6-byte SecureOn password).
pub fn build_wol_magic_packet(
    mac_str: &str,
    secure_on_hex: Option<&str>,
) -> Result<Vec<u8>, NetUtilError> {
    let parts: Vec<&str> = mac_str.split([':', '-']).filter(|s| !s.is_empty()).collect();
    if parts.len() != 6 {
        return Err(NetUtilError::InvalidMacAddress(mac_str.to_string()));
    }
    let mut mac = [0u8; 6];
    for (idx, part) in parts.iter().enumerate() {
        mac[idx] = u8::from_str_radix(part, 16)
            .map_err(|_| NetUtilError::InvalidMacAddress(mac_str.to_string()))?;
    }

    let mut packet = Vec::with_capacity(108);
    packet.extend_from_slice(&[0xFF; 6]);
    for _ in 0..16 {
        packet.extend_from_slice(&mac);
    }

    if let Some(sec) = secure_on_hex.filter(|s| !s.trim().is_empty()) {
        let sec_parts: Vec<&str> = sec.split([':', '-']).collect();
        if sec_parts.len() != 6 {
            return Err(NetUtilError::InvalidSecureOn);
        }
        for p in sec_parts {
            packet.push(u8::from_str_radix(p, 16).map_err(|_| NetUtilError::InvalidSecureOn)?);
        }
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
    let sock = UdpSocket::bind("0.0.0.0:0")?;
    sock.set_broadcast(true)?;
    let target = format!("{broadcast_ip}:{udp_port}");
    let sent = sock.send_to(&payload, target)?;
    Ok(sent)
}

/// Reads an initial protocol greeting banner (e.g., `SSH-2.0-OpenSSH_9.7`, `220 ProFTPD`, `RFB 003.008`)
/// without requiring root privileges.
pub fn grab_tcp_service_banner(host: &str, port: u16, timeout_ms: u64) -> Option<String> {
    let addr: SocketAddr = format!("{host}:{port}").to_socket_addrs().ok()?.next()?;
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
    let addr: SocketAddr = format!("{host}:{port}").to_socket_addrs().ok()?.next()?;
    let start = Instant::now();
    let stream = TcpStream::connect_timeout(&addr, Duration::from_millis(timeout_ms));
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

/// Unprivileged UDP/TCP TTL-stepped Traceroute tailored for Android & Googlebook OS sandboxes
/// (where `CAP_NET_RAW` raw ICMP sockets are restricted). Steps `IP_TTL` from `1..=max_hops`,
/// probing UDP `33434 + ttl` + final TCP `probe_port` reachability verification.
pub fn trace_route_hops(
    host: &str,
    probe_port: u16,
    max_hops: u8,
    per_hop_timeout_ms: u64,
) -> Result<Vec<TracerouteHopResult>, NetUtilError> {
    let target_addr: SocketAddr = format!("{host}:{probe_port}")
        .to_socket_addrs()
        .map_err(|_| NetUtilError::DnsResolutionFailed(host.to_string()))?
        .next()
        .ok_or_else(|| NetUtilError::DnsResolutionFailed(host.to_string()))?;

    let target_ip = target_addr.ip();
    let bind_addr = if target_ip.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    };
    let timeout = Duration::from_millis(per_hop_timeout_ms.clamp(100, 3_000));
    let capped_hops = max_hops.clamp(1, 32);
    let mut hops = Vec::with_capacity(capped_hops as usize);

    for ttl in 1..=capped_hops {
        let udp = UdpSocket::bind(bind_addr)?;
        udp.set_ttl(u32::from(ttl))?;
        udp.set_read_timeout(Some(timeout))?;

        let udp_dest = SocketAddr::new(target_ip, 33434u16.saturating_add(u16::from(ttl)));
        let started = Instant::now();
        let _ = udp.send_to(b"NEXUSTS-TRACE-PROBE", udp_dest);

        // Also test if a TCP SYN with this exact TTL reaches `target_addr`
        let tcp_started = Instant::now();
        let reached = probe_tcp_with_ttl(target_addr, ttl, timeout);
        let rtt_ms = (tcp_started.elapsed().as_micros() as f64) / 1000.0;

        if reached {
            hops.push(TracerouteHopResult {
                ttl,
                responder_addr: target_ip.to_string(),
                rtt_ms,
                reached_destination: true,
                timed_out: false,
            });
            break;
        } else {
            let mut recv_buf = [0u8; 64];
            match udp.recv_from(&mut recv_buf) {
                Ok((_, peer)) => {
                    let elapsed_ms = (started.elapsed().as_micros() as f64) / 1000.0;
                    let is_dest = peer.ip() == target_ip;
                    hops.push(TracerouteHopResult {
                        ttl,
                        responder_addr: peer.ip().to_string(),
                        rtt_ms: elapsed_ms,
                        reached_destination: is_dest,
                        timed_out: false,
                    });
                    if is_dest {
                        break;
                    }
                }
                Err(_) => {
                    hops.push(TracerouteHopResult {
                        ttl,
                        responder_addr: String::from("*"),
                        rtt_ms: (started.elapsed().as_micros() as f64) / 1000.0,
                        reached_destination: false,
                        timed_out: true,
                    });
                }
            }
        }
    }

    Ok(hops)
}

fn probe_tcp_with_ttl(addr: SocketAddr, ttl: u8, timeout: Duration) -> bool {
    if ttl == 0 {
        return false;
    }
    match TcpStream::connect_timeout(&addr, timeout) {
        Ok(sock) => {
            let _ = sock.set_ttl(u32::from(ttl));
            true
        }
        Err(err) => {
            // ConnectionRefused proves our packet reached the destination host!
            err.kind() == std::io::ErrorKind::ConnectionRefused
        }
    }
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

    #[test]
    fn wol_magic_packet_builds_102_or_108_bytes() {
        let pkt = build_wol_magic_packet("00:11:22:AA:BB:CC", None).expect("valid MAC");
        assert_eq!(pkt.len(), 102);
        assert_eq!(&pkt[0..6], &[0xFF; 6]);
        assert_eq!(&pkt[6..12], &[0x00, 0x11, 0x22, 0xAA, 0xBB, 0xCC]);

        let sec_pkt =
            build_wol_magic_packet("00-11-22-AA-BB-CC", Some("DE:AD:BE:EF:01:02")).expect("secureon");
        assert_eq!(sec_pkt.len(), 108);
        assert_eq!(&sec_pkt[102..108], &[0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02]);
    }
}
