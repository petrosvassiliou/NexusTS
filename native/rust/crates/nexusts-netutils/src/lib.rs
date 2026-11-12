//! Integrated Enterprise Network Utilities:
//! Wake-on-LAN (Magic Packet + SecureOn), Concurrent Async Port Scanner, and TCP Reachability Probe.

use serde::{Deserialize, Serialize};
use std::net::{SocketAddr, ToSocketAddrs, UdpSocket};
use std::time::{Duration, Instant};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum NetUtilError {
    #[error("Invalid MAC address '{0}' (expected AA:BB:CC:DD:EE:FF)")]
    InvalidMacAddress(String),
    #[error("Invalid SecureOn hex password")]
    InvalidSecureOn,
    #[error("Socket I/O error: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PortScanHit {
    pub port: u16,
    pub open: bool,
    pub connect_latency_ms: u64,
    pub guessed_service: String,
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

/// Synchronous single-port TCP probe with sub-millisecond timing for Ping/Scanner tasks.
pub fn probe_tcp_port(host: &str, port: u16, timeout_ms: u64) -> Option<PortScanHit> {
    let addr: SocketAddr = format!("{host}:{port}").to_socket_addrs().ok()?.next()?;
    let start = Instant::now();
    let stream = std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(timeout_ms));
    match stream {
        Ok(_) => Some(PortScanHit {
            port,
            open: true,
            connect_latency_ms: start.elapsed().as_millis() as u64,
            guessed_service: well_known_service_name(port).to_string(),
        }),
        Err(_) => None,
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
