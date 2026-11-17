//! Pure-Rust `[MS-NLMP]` NTLMv2 Authentication + `[MS-WSMV]` `HTTP-SPNEGO-session-encrypted`
//! Message-Level RC4 Sealing & Unsealing Client for Windows Remote Management (`WinRM` `:5985` / `:5986`).
//!
//! Stock Windows Server (2016 / 2019 / 2022 / 2025) and Windows 10/11 Pro/Enterprise Hyper-V hosts
//! enable WinRM HTTP on TCP `5985` with `AllowUnencrypted = false` and `Auth.Basic = false` by default,
//! and macOS's built-in GSSAPI / URLSession cannot perform SPNEGO NTLM message sealing over HTTP.
//! This module implements:
//! - RFC 1320 `MD4` (for NT password hash `NTWFv2`)
//! - Stateful `RC4` stream cipher (`NTLMSSP_NEGOTIATE_SEAL` + `NTLMSSP_NEGOTIATE_KEY_EXCH`)
//! - Full `[MS-NLMP]` NTLMv2 Type 1 (`Negotiate`), Type 2 (`Challenge`), and Type 3 (`Authenticate`)
//!   with `MsvAvTimestamp` preservation, `MsvAvFlags = 0x02` 16-byte `MIC`, and `MsvAvTargetName` (`HTTP/<host>`)
//! - `[MS-WSMV]` Section 2.2.9.1.2 `multipart/encrypted;protocol="application/HTTP-SPNEGO-session-encrypted"`
//!   outbound sealing and multi-chunk inbound unsealing over persistent HTTP/1.1 Keep-Alive sockets.

use base64::engine::general_purpose::STANDARD as BASE64_STD;
use base64::Engine;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use thiserror::Error;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::format_socket_target;

const NTLMSSP_SIGNATURE: &[u8; 8] = b"NTLMSSP\0";

const NTLMSSP_NEGOTIATE_UNICODE: u32 = 0x0000_0001;
const NTLMSSP_REQUEST_TARGET: u32 = 0x0000_0004;
const NTLMSSP_NEGOTIATE_SIGN: u32 = 0x0000_0010;
const NTLMSSP_NEGOTIATE_SEAL: u32 = 0x0000_0020;
const NTLMSSP_NEGOTIATE_NTLM: u32 = 0x0000_0200;
const NTLMSSP_NEGOTIATE_ALWAYS_SIGN: u32 = 0x0000_8000;
const NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY: u32 = 0x0008_0000;
const NTLMSSP_NEGOTIATE_TARGET_INFO: u32 = 0x0080_0000;
const NTLMSSP_NEGOTIATE_VERSION: u32 = 0x0200_0000;
const NTLMSSP_NEGOTIATE_128: u32 = 0x2000_0000;
const NTLMSSP_NEGOTIATE_KEY_EXCH: u32 = 0x4000_0000;
const NTLMSSP_NEGOTIATE_56: u32 = 0x8000_0000;

const DEFAULT_CLIENT_NTLM_FLAGS: u32 = NTLMSSP_NEGOTIATE_UNICODE
    | NTLMSSP_REQUEST_TARGET
    | NTLMSSP_NEGOTIATE_SIGN
    | NTLMSSP_NEGOTIATE_SEAL
    | NTLMSSP_NEGOTIATE_NTLM
    | NTLMSSP_NEGOTIATE_ALWAYS_SIGN
    | NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY
    | NTLMSSP_NEGOTIATE_VERSION
    | NTLMSSP_NEGOTIATE_128
    | NTLMSSP_NEGOTIATE_KEY_EXCH
    | NTLMSSP_NEGOTIATE_56;

const MSV_AV_EOL: u16 = 0x0000;
const MSV_AV_FLAGS: u16 = 0x0006;
const MSV_AV_TIMESTAMP: u16 = 0x0007;
const MSV_AV_TARGET_NAME: u16 = 0x0009;

const CLIENT_SIGN_MAGIC: &[u8] = b"session key to client-to-server signing key magic constant\0";
const SERVER_SIGN_MAGIC: &[u8] = b"session key to server-to-client signing key magic constant\0";
const CLIENT_SEAL_MAGIC: &[u8] = b"session key to client-to-server sealing key magic constant\0";
const SERVER_SEAL_MAGIC: &[u8] = b"session key to server-to-client sealing key magic constant\0";

#[derive(Debug, Error)]
pub enum WinRmError {
    #[error("Invalid NTLM challenge token: {0}")]
    InvalidNtlmChallenge(String),
    #[error("WinRM authentication rejected (HTTP {status}): {detail}")]
    AuthenticationFailed { status: u16, detail: String },
    #[error("WinRM HTTP {status} error: {detail}")]
    HttpError { status: u16, detail: String },
    #[error("WinRM SPNEGO decryption error: {0}")]
    DecryptionError(String),
    #[error("WinRM network timeout after {0} ms")]
    Timeout(u64),
    #[error("WinRM I/O error: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WinRmHttpResponse {
    pub status_code: u16,
    pub content_type: String,
    pub soap_xml: String,
    pub used_spnego_encryption: bool,
}

/// Pure-Rust RFC 1320 MD4 digest (used solely for NTLMv2 `NTWFv2 = MD4(UTF16LE(password))`).
pub fn md4_digest(input: &[u8]) -> [u8; 16] {
    #[inline]
    fn f(x: u32, y: u32, z: u32) -> u32 {
        (x & y) | (!x & z)
    }
    #[inline]
    fn g(x: u32, y: u32, z: u32) -> u32 {
        (x & y) | (x & z) | (y & z)
    }
    #[inline]
    fn h(x: u32, y: u32, z: u32) -> u32 {
        x ^ y ^ z
    }

    let bit_len = (input.len() as u64).wrapping_mul(8);
    let mut msg = Vec::with_capacity(input.len() + 72);
    msg.extend_from_slice(input);
    msg.push(0x80);
    while (msg.len() % 64) != 56 {
        msg.push(0x00);
    }
    msg.extend_from_slice(&bit_len.to_le_bytes());

    let mut a0: u32 = 0x6745_2301;
    let mut b0: u32 = 0xEFCD_AB89;
    let mut c0: u32 = 0x98BA_DCFE;
    let mut d0: u32 = 0x1032_5476;

    for chunk in msg.chunks_exact(64) {
        let mut x = [0u32; 16];
        for (i, word) in chunk.chunks_exact(4).enumerate() {
            x[i] = u32::from_le_bytes([word[0], word[1], word[2], word[3]]);
        }

        let mut a = a0;
        let mut b = b0;
        let mut c = c0;
        let mut d = d0;

        // Round 1
        const R1_S: [u32; 4] = [3, 7, 11, 19];
        for i in 0..16 {
            let t = a.wrapping_add(f(b, c, d)).wrapping_add(x[i]).rotate_left(R1_S[i % 4]);
            a = d;
            d = c;
            c = b;
            b = t;
        }

        // Round 2
        const R2_K: u32 = 0x5A82_7999;
        const R2_IDX: [usize; 16] = [0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15];
        const R2_S: [u32; 4] = [3, 5, 9, 13];
        for (step, &k_idx) in R2_IDX.iter().enumerate() {
            let t = a
                .wrapping_add(g(b, c, d))
                .wrapping_add(x[k_idx])
                .wrapping_add(R2_K)
                .rotate_left(R2_S[step % 4]);
            a = d;
            d = c;
            c = b;
            b = t;
        }

        // Round 3
        const R3_K: u32 = 0x6ED9_EBA1;
        const R3_IDX: [usize; 16] = [0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15];
        const R3_S: [u32; 4] = [3, 9, 11, 15];
        for (step, &k_idx) in R3_IDX.iter().enumerate() {
            let t = a
                .wrapping_add(h(b, c, d))
                .wrapping_add(x[k_idx])
                .wrapping_add(R3_K)
                .rotate_left(R3_S[step % 4]);
            a = d;
            d = c;
            c = b;
            b = t;
        }

        a0 = a0.wrapping_add(a);
        b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c);
        d0 = d0.wrapping_add(d);
    }

    let mut out = [0u8; 16];
    out[0..4].copy_from_slice(&a0.to_le_bytes());
    out[4..8].copy_from_slice(&b0.to_le_bytes());
    out[8..12].copy_from_slice(&c0.to_le_bytes());
    out[12..16].copy_from_slice(&d0.to_le_bytes());
    out
}

/// Stateful RC4 stream cipher for NTLM session key exchange and `[MS-NLMP]` `GSS_WrapEx` sealing.
#[derive(Clone)]
pub struct Rc4Cipher {
    s: [u8; 256],
    i: u8,
    j: u8,
}

impl Rc4Cipher {
    pub fn new(key: &[u8]) -> Self {
        let mut s = [0u8; 256];
        for (idx, slot) in s.iter_mut().enumerate() {
            *slot = idx as u8;
        }
        if !key.is_empty() {
            let mut j: u8 = 0;
            for idx in 0..256 {
                j = j.wrapping_add(s[idx]).wrapping_add(key[idx % key.len()]);
                s.swap(idx, usize::from(j));
            }
        }
        Self { s, i: 0, j: 0 }
    }

    pub fn apply_keystream(&mut self, data: &mut [u8]) {
        for byte in data.iter_mut() {
            self.i = self.i.wrapping_add(1);
            self.j = self.j.wrapping_add(self.s[usize::from(self.i)]);
            self.s.swap(usize::from(self.i), usize::from(self.j));
            let k = self.s[usize::from(
                self.s[usize::from(self.i)].wrapping_add(self.s[usize::from(self.j)]),
            )];
            *byte ^= k;
        }
    }
}

fn encode_utf16le(s: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(s.len() * 2);
    for u in s.encode_utf16() {
        out.extend_from_slice(&u.to_le_bytes());
    }
    out
}

fn hmac_md5(key: &[u8], data: &[u8]) -> [u8; 16] {
    let mut k_block = [0u8; 64];
    if key.len() > 64 {
        let hashed = md5::compute(key);
        k_block[0..16].copy_from_slice(&hashed.0);
    } else {
        k_block[0..key.len()].copy_from_slice(key);
    }

    let mut ipad = [0x36u8; 64];
    let mut opad = [0x5Cu8; 64];
    for idx in 0..64 {
        ipad[idx] ^= k_block[idx];
        opad[idx] ^= k_block[idx];
    }

    let mut inner = md5::Context::new();
    inner.consume(ipad);
    inner.consume(data);
    let inner_digest = inner.compute();

    let mut outer = md5::Context::new();
    outer.consume(opad);
    outer.consume(inner_digest.0);
    outer.compute().0
}

fn md5_concat(a: &[u8], b: &[u8]) -> [u8; 16] {
    let mut ctx = md5::Context::new();
    ctx.consume(a);
    ctx.consume(b);
    ctx.compute().0
}

/// Normalizes Windows account representations (`DOMAIN\user`, `.\user`, `localhost\user`, `user@upn`)
/// into `(domain, username)` suitable for `[MS-NLMP]` `NTWFv2`.
///
/// Crucially, `.\Administrator` and `localhost\Administrator` map to `domain = ""` so `NTWFv2`
/// hashes `UPPER("Administrator") + ""` rather than a literal `"."` domain prefix.
pub fn split_windows_account(raw_username: &str, raw_domain: &str) -> (String, String) {
    let mut user = raw_username.trim().to_string();
    let mut domain = raw_domain.trim().to_string();

    if let Some((dom_part, user_part)) = user.split_once('\\') {
        if domain.is_empty() {
            domain = dom_part.trim().to_string();
        }
        user = user_part.trim().to_string();
    }

    if domain == "." || domain.eq_ignore_ascii_case("localhost") {
        domain.clear();
    }

    (domain, user)
}

/// Computes `ResponseKeyNT = NTWFv2(Passwd, User, UserDom)` per `[MS-NLMP]` Section 3.3.2.
pub fn ntowfv2(password: &str, username: &str, domain: &str) -> [u8; 16] {
    let nt_hash = md4_digest(&encode_utf16le(password));
    let mut user_dom = username.to_uppercase();
    user_dom.push_str(domain);
    hmac_md5(&nt_hash, &encode_utf16le(&user_dom))
}

/// Builds an `[MS-NLMP]` Type 1 `NEGOTIATE_MESSAGE` raw byte vector.
pub fn build_ntlm_type1_bytes() -> Vec<u8> {
    let mut buf = Vec::with_capacity(40);
    buf.extend_from_slice(NTLMSSP_SIGNATURE);
    buf.extend_from_slice(&1u32.to_le_bytes());
    buf.extend_from_slice(&DEFAULT_CLIENT_NTLM_FLAGS.to_le_bytes());
    // DomainNameFields (len=0, max_len=0, offset=40)
    buf.extend_from_slice(&0u16.to_le_bytes());
    buf.extend_from_slice(&0u16.to_le_bytes());
    buf.extend_from_slice(&40u32.to_le_bytes());
    // WorkstationFields (len=0, max_len=0, offset=40)
    buf.extend_from_slice(&0u16.to_le_bytes());
    buf.extend_from_slice(&0u16.to_le_bytes());
    buf.extend_from_slice(&40u32.to_le_bytes());
    // Version: Windows 10.0 Build 19041, NTLMSSP_REVISION_W2K3 (15)
    buf.extend_from_slice(&[10, 0]);
    buf.extend_from_slice(&19041u16.to_le_bytes());
    buf.extend_from_slice(&[0, 0, 0, 15]);
    buf
}

/// Returns Base64-encoded `[MS-NLMP]` Type 1 `NEGOTIATE_MESSAGE` for `Authorization: Negotiate <b64>`.
pub fn ntlm_build_negotiate_b64() -> String {
    BASE64_STD.encode(build_ntlm_type1_bytes())
}

#[derive(Debug, Clone)]
struct ParsedType2Challenge {
    raw_type2: Vec<u8>,
    server_flags: u32,
    server_challenge: [u8; 8],
    target_info: Vec<u8>,
    server_timestamp: Option<[u8; 8]>,
}

fn parse_ntlm_type2(raw_token: &[u8]) -> Result<ParsedType2Challenge, WinRmError> {
    // Locate NTLMSSP\0 signature (either at index 0 for raw NTLM/Negotiate or inside SPNEGO wrapper)
    let sig_pos = raw_token
        .windows(8)
        .position(|w| w == NTLMSSP_SIGNATURE)
        .ok_or_else(|| WinRmError::InvalidNtlmChallenge("Missing NTLMSSP signature".into()))?;
    let msg = &raw_token[sig_pos..];
    if msg.len() < 32 {
        return Err(WinRmError::InvalidNtlmChallenge(format!(
            "Type 2 message truncated ({} bytes)",
            msg.len()
        )));
    }
    let msg_type = u32::from_le_bytes([msg[8], msg[9], msg[10], msg[11]]);
    if msg_type != 2 {
        return Err(WinRmError::InvalidNtlmChallenge(format!(
            "Expected NTLM message type 2, got {msg_type}"
        )));
    }
    let server_flags = u32::from_le_bytes([msg[20], msg[21], msg[22], msg[23]]);
    let mut server_challenge = [0u8; 8];
    server_challenge.copy_from_slice(&msg[24..32]);

    let mut target_info = Vec::new();
    let mut server_timestamp = None;
    if (server_flags & NTLMSSP_NEGOTIATE_TARGET_INFO) != 0 && msg.len() >= 48 {
        let ti_len = u16::from_le_bytes([msg[40], msg[41]]) as usize;
        let ti_off = u32::from_le_bytes([msg[44], msg[45], msg[46], msg[47]]) as usize;
        if ti_len > 0 && ti_off <= msg.len() && ti_off.saturating_add(ti_len) <= msg.len() {
            target_info.extend_from_slice(&msg[ti_off..ti_off + ti_len]);
            let mut cursor = 0usize;
            while cursor + 4 <= target_info.len() {
                let av_id = u16::from_le_bytes([target_info[cursor], target_info[cursor + 1]]);
                let av_len =
                    u16::from_le_bytes([target_info[cursor + 2], target_info[cursor + 3]]) as usize;
                cursor += 4;
                if av_id == MSV_AV_EOL || cursor + av_len > target_info.len() {
                    break;
                }
                if av_id == MSV_AV_TIMESTAMP && av_len == 8 {
                    let mut ts = [0u8; 8];
                    ts.copy_from_slice(&target_info[cursor..cursor + 8]);
                    server_timestamp = Some(ts);
                }
                cursor += av_len;
            }
        }
    }

    Ok(ParsedType2Challenge {
        raw_type2: msg.to_vec(),
        server_flags,
        server_challenge,
        target_info,
        server_timestamp,
    })
}

fn current_windows_filetime_le() -> [u8; 8] {
    const EPOCH_DIFF_SECS: u64 = 11_644_473_600;
    let dur = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(1_700_000_000));
    let intervals = dur
        .as_secs()
        .saturating_add(EPOCH_DIFF_SECS)
        .saturating_mul(10_000_000)
        .saturating_add(u64::from(dur.subsec_nanos() / 100));
    intervals.to_le_bytes()
}

fn augment_target_info_for_mic_and_spn(raw_ti: &[u8], spn_host: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(raw_ti.len() + 64);
    let mut saw_flags = false;
    let mut saw_target_name = false;

    let mut cursor = 0usize;
    while cursor + 4 <= raw_ti.len() {
        let av_id = u16::from_le_bytes([raw_ti[cursor], raw_ti[cursor + 1]]);
        let av_len = u16::from_le_bytes([raw_ti[cursor + 2], raw_ti[cursor + 3]]) as usize;
        cursor += 4;
        if av_id == MSV_AV_EOL || cursor + av_len > raw_ti.len() {
            break;
        }
        let val = &raw_ti[cursor..cursor + av_len];
        cursor += av_len;

        if av_id == MSV_AV_FLAGS && av_len == 4 {
            saw_flags = true;
            let existing = u32::from_le_bytes([val[0], val[1], val[2], val[3]]);
            let updated = existing | 0x0000_0002; // MIC present
            out.extend_from_slice(&MSV_AV_FLAGS.to_le_bytes());
            out.extend_from_slice(&4u16.to_le_bytes());
            out.extend_from_slice(&updated.to_le_bytes());
        } else {
            if av_id == MSV_AV_TARGET_NAME {
                saw_target_name = true;
            }
            out.extend_from_slice(&av_id.to_le_bytes());
            out.extend_from_slice(&(av_len as u16).to_le_bytes());
            out.extend_from_slice(val);
        }
    }

    if !saw_flags {
        out.extend_from_slice(&MSV_AV_FLAGS.to_le_bytes());
        out.extend_from_slice(&4u16.to_le_bytes());
        out.extend_from_slice(&2u32.to_le_bytes());
    }

    let trimmed_host = spn_host
        .trim()
        .trim_start_matches('[')
        .trim_end_matches(']');
    if !saw_target_name && !trimmed_host.is_empty() {
        let spn_utf16 = encode_utf16le(&format!("HTTP/{trimmed_host}"));
        if spn_utf16.len() <= usize::from(u16::MAX) {
            out.extend_from_slice(&MSV_AV_TARGET_NAME.to_le_bytes());
            out.extend_from_slice(&(spn_utf16.len() as u16).to_le_bytes());
            out.extend_from_slice(&spn_utf16);
        }
    }

    // MsvAvEOL
    out.extend_from_slice(&MSV_AV_EOL.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out
}

/// Stateful `[MS-NLMP]` session signing & RC4 sealing context for `[MS-WSMV]` SPNEGO encryption.
pub struct NtlmSealingContext {
    client_sign_key: [u8; 16],
    server_sign_key: [u8; 16],
    client_rc4: Rc4Cipher,
    server_rc4: Rc4Cipher,
    client_seq: u32,
    server_seq: u32,
    key_exch: bool,
}

impl NtlmSealingContext {
    pub fn from_exported_session_key(exported_session_key: &[u8; 16], negotiated_flags: u32) -> Self {
        let client_sign_key = md5_concat(exported_session_key, CLIENT_SIGN_MAGIC);
        let server_sign_key = md5_concat(exported_session_key, SERVER_SIGN_MAGIC);

        let seal_base: &[u8] = if (negotiated_flags & NTLMSSP_NEGOTIATE_128) != 0 {
            &exported_session_key[0..16]
        } else if (negotiated_flags & NTLMSSP_NEGOTIATE_56) != 0 {
            &exported_session_key[0..7]
        } else {
            &exported_session_key[0..5]
        };
        let client_seal_key = md5_concat(seal_base, CLIENT_SEAL_MAGIC);
        let server_seal_key = md5_concat(seal_base, SERVER_SEAL_MAGIC);

        Self {
            client_sign_key,
            server_sign_key,
            client_rc4: Rc4Cipher::new(&client_seal_key),
            server_rc4: Rc4Cipher::new(&server_seal_key),
            client_seq: 0,
            server_seq: 0,
            key_exch: (negotiated_flags & NTLMSSP_NEGOTIATE_KEY_EXCH) != 0,
        }
    }

    /// Computes `[MS-NLMP]` `GSS_WrapEx` `(signature_16, ciphertext)` over `plaintext`.
    pub fn seal_raw(&mut self, plaintext: &[u8]) -> ([u8; 16], Vec<u8>) {
        let seq_bytes = self.client_seq.to_le_bytes();
        let mut ciphertext = plaintext.to_vec();
        self.client_rc4.apply_keystream(&mut ciphertext);

        let mut mac_input = Vec::with_capacity(4 + plaintext.len());
        mac_input.extend_from_slice(&seq_bytes);
        mac_input.extend_from_slice(plaintext);
        let mac_full = hmac_md5(&self.client_sign_key, &mac_input);

        let mut checksum = [0u8; 8];
        checksum.copy_from_slice(&mac_full[0..8]);
        if self.key_exch {
            self.client_rc4.apply_keystream(&mut checksum);
        }

        let mut sig = [0u8; 16];
        sig[0..4].copy_from_slice(&1u32.to_le_bytes());
        sig[4..12].copy_from_slice(&checksum);
        sig[12..16].copy_from_slice(&seq_bytes);

        self.client_seq = self.client_seq.wrapping_add(1);
        (sig, ciphertext)
    }

    /// Decrypts and authenticates a single `[MS-NLMP]` `GSS_WrapEx` chunk `(signature_16, ciphertext)`.
    pub fn unseal_raw(&mut self, signature: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, WinRmError> {
        if signature.len() != 16 {
            return Err(WinRmError::DecryptionError(format!(
                "Expected 16-byte NTLMSSP signature, got {}",
                signature.len()
            )));
        }
        let version = u32::from_le_bytes([signature[0], signature[1], signature[2], signature[3]]);
        if version != 1 {
            return Err(WinRmError::DecryptionError(format!(
                "Unsupported NTLMSSP signature version {version}"
            )));
        }

        let mut plaintext = ciphertext.to_vec();
        self.server_rc4.apply_keystream(&mut plaintext);

        // IMPORTANT: When KEY_EXCH is active, the sender encrypted the 8-byte HMAC checksum with
        // the same stateful RC4 handle immediately after encrypting `Plaintext`. We MUST advance
        // `server_rc4` by 8 bytes on the checksum so multi-chunk WinRM responses stay synchronized!
        let mut checksum = [0u8; 8];
        checksum.copy_from_slice(&signature[4..12]);
        if self.key_exch {
            self.server_rc4.apply_keystream(&mut checksum);
        }

        let seq_bytes = &signature[12..16];
        let mut mac_input = Vec::with_capacity(4 + plaintext.len());
        mac_input.extend_from_slice(seq_bytes);
        mac_input.extend_from_slice(&plaintext);
        let expected_mac = hmac_md5(&self.server_sign_key, &mac_input);
        if checksum != expected_mac[0..8] {
            return Err(WinRmError::DecryptionError(
                "NTLMSSP HMAC-MD5 integrity check failed on WinRM response".into(),
            ));
        }

        self.server_seq = self.server_seq.wrapping_add(1);
        Ok(plaintext)
    }

    /// Wraps UTF-8 SOAP XML into `[MS-WSMV]` Section 2.2.9.1.2 `multipart/encrypted` wire bytes.
    pub fn wrap_wsmv_soap(&mut self, soap_utf8: &[u8]) -> Vec<u8> {
        let (sig, ciphertext) = self.seal_raw(soap_utf8);
        let prefix = format!(
            "--Encrypted Boundary\r\n\tContent-Type: application/HTTP-SPNEGO-session-encrypted\r\n\tOriginalContent: type=application/soap+xml;charset=UTF-8;Length={}\r\n--Encrypted Boundary\r\n\tContent-Type: application/octet-stream\r\n",
            soap_utf8.len()
        );
        let suffix = b"--Encrypted Boundary--\r\n";

        let mut out = Vec::with_capacity(prefix.len() + 4 + 16 + ciphertext.len() + suffix.len());
        out.extend_from_slice(prefix.as_bytes());
        out.extend_from_slice(&16u32.to_le_bytes());
        out.extend_from_slice(&sig);
        out.extend_from_slice(&ciphertext);
        out.extend_from_slice(suffix);
        out
    }

    /// Unwraps `[MS-WSMV]` `multipart/encrypted` response bytes (supporting single or multi-chunk
    /// encrypted payloads as well as both `--Encrypted Boundary` and `-- Encrypted Boundary`).
    pub fn unwrap_wsmv_multipart(&mut self, body: &[u8]) -> Result<Vec<u8>, WinRmError> {
        let mut decrypted_total = Vec::new();
        let mut search_pos = 0usize;
        let marker = b"application/octet-stream";

        while let Some(rel_idx) = body[search_pos..]
            .windows(marker.len())
            .position(|w| w.eq_ignore_ascii_case(marker))
        {
            // Try to extract OriginalContent Length=<N> from the preceding MIME metadata section
            let header_region = &body[search_pos..search_pos + rel_idx];
            let declared_plain_len = parse_original_content_length(header_region);

            let mut payload_start = search_pos + rel_idx + marker.len();
            // Skip `\r\n` (standard [MS-WSMV]) or `\r\n\r\n` (RFC MIME proxies)
            if body[payload_start..].starts_with(b"\r\n\r\n")
                && body.len() >= payload_start + 8
                && u32::from_le_bytes([
                    body[payload_start + 4],
                    body[payload_start + 5],
                    body[payload_start + 6],
                    body[payload_start + 7],
                ]) == 16
            {
                payload_start += 4;
            } else if body[payload_start..].starts_with(b"\r\n") {
                payload_start += 2;
            } else if body[payload_start..].starts_with(b"\n") {
                payload_start += 1;
            }

            if payload_start + 4 > body.len() {
                return Err(WinRmError::DecryptionError(
                    "Truncated SPNEGO signature length prefix".into(),
                ));
            }
            let sig_len = u32::from_le_bytes([
                body[payload_start],
                body[payload_start + 1],
                body[payload_start + 2],
                body[payload_start + 3],
            ]) as usize;
            if sig_len == 0 || sig_len > 64 || payload_start + 4 + sig_len > body.len() {
                return Err(WinRmError::DecryptionError(format!(
                    "Invalid SPNEGO signature length {sig_len}"
                )));
            }

            let sig_slice = &body[payload_start + 4..payload_start + 4 + sig_len];
            let cipher_start = payload_start + 4 + sig_len;

            let cipher_end = if let Some(plain_len) = declared_plain_len {
                if cipher_start + plain_len <= body.len() {
                    cipher_start + plain_len
                } else {
                    find_next_encrypted_boundary(body, cipher_start)
                }
            } else {
                find_next_encrypted_boundary(body, cipher_start)
            };

            let cipher_slice = &body[cipher_start..cipher_end];
            let chunk_plain = self.unseal_raw(sig_slice, cipher_slice)?;
            decrypted_total.extend_from_slice(&chunk_plain);
            search_pos = cipher_end;
        }

        if decrypted_total.is_empty() {
            return Err(WinRmError::DecryptionError(
                "No application/octet-stream SPNEGO payload found in multipart/encrypted body".into(),
            ));
        }

        Ok(decrypted_total)
    }
}

fn parse_original_content_length(header_bytes: &[u8]) -> Option<usize> {
    let text = String::from_utf8_lossy(header_bytes);
    for token in text.split([';', '\r', '\n', ' ', '\t']) {
        if let Some(val) = token
            .strip_prefix("Length=")
            .or_else(|| token.strip_prefix("length="))
        {
            if let Ok(parsed) = val.trim().parse::<usize>() {
                return Some(parsed);
            }
        }
    }
    None
}

fn find_next_encrypted_boundary(body: &[u8], start: usize) -> usize {
    for pat in [&b"--Encrypted Boundary"[..], &b"-- Encrypted Boundary"[..]] {
        if let Some(rel) = body[start..].windows(pat.len()).position(|w| w == pat) {
            let mut end = start + rel;
            if end >= 2 && &body[end - 2..end] == b"\r\n" && !body[end..].starts_with(b"--Encrypted Boundary--") {
                end -= 2;
            }
            return end;
        }
    }
    body.len()
}

/// Builds an `[MS-NLMP]` Type 3 `AUTHENTICATE_MESSAGE` + matching [`NtlmSealingContext`] from
/// the Type 1 bytes and server Type 2 challenge bytes.
pub fn build_ntlm_type3_and_sealing(
    type1_bytes: &[u8],
    server_challenge_token: &[u8],
    raw_username: &str,
    password: &str,
    raw_domain: &str,
    spn_host: &str,
) -> Result<(Vec<u8>, NtlmSealingContext), WinRmError> {
    let parsed = parse_ntlm_type2(server_challenge_token)?;
    let (domain, username) = split_windows_account(raw_username, raw_domain);

    let response_key_nt = ntowfv2(password, &username, &domain);
    let mut rng = rand::thread_rng();
    let mut client_challenge = [0u8; 8];
    rng.fill_bytes(&mut client_challenge);

    let timestamp = parsed
        .server_timestamp
        .unwrap_or_else(current_windows_filetime_le);
    let modified_ti = augment_target_info_for_mic_and_spn(&parsed.target_info, spn_host);

    // NTLMv2_CLIENT_CHALLENGE (`temp`)
    let mut temp = Vec::with_capacity(28 + modified_ti.len() + 4);
    temp.extend_from_slice(&[0x01, 0x01, 0, 0, 0, 0]);
    temp.extend_from_slice(&[0, 0]);
    temp.extend_from_slice(&timestamp);
    temp.extend_from_slice(&client_challenge);
    temp.extend_from_slice(&[0, 0, 0, 0]);
    temp.extend_from_slice(&modified_ti);
    temp.extend_from_slice(&[0, 0, 0, 0]);

    let mut proof_input = Vec::with_capacity(8 + temp.len());
    proof_input.extend_from_slice(&parsed.server_challenge);
    proof_input.extend_from_slice(&temp);
    let nt_proof_str = hmac_md5(&response_key_nt, &proof_input);

    let mut nt_challenge_response = Vec::with_capacity(16 + temp.len());
    nt_challenge_response.extend_from_slice(&nt_proof_str);
    nt_challenge_response.extend_from_slice(&temp);

    // Per [MS-NLMP] 3.3.2, when MsvAvTimestamp is present in TargetInfo, LmChallengeResponse MUST be Z(24)
    let lm_challenge_response = if parsed.server_timestamp.is_some() {
        vec![0u8; 24]
    } else {
        let mut lm_in = [0u8; 16];
        lm_in[0..8].copy_from_slice(&parsed.server_challenge);
        lm_in[8..16].copy_from_slice(&client_challenge);
        let lm_mac = hmac_md5(&response_key_nt, &lm_in);
        let mut lm = Vec::with_capacity(24);
        lm.extend_from_slice(&lm_mac);
        lm.extend_from_slice(&client_challenge);
        lm
    };

    let session_base_key = hmac_md5(&response_key_nt, &nt_proof_str);
    let negotiated_flags =
        (DEFAULT_CLIENT_NTLM_FLAGS & parsed.server_flags) | NTLMSSP_NEGOTIATE_VERSION;

    let mut exported_session_key = [0u8; 16];
    let encrypted_random_session_key = if (negotiated_flags & NTLMSSP_NEGOTIATE_KEY_EXCH) != 0 {
        rng.fill_bytes(&mut exported_session_key);
        let mut enc = exported_session_key.to_vec();
        Rc4Cipher::new(&session_base_key).apply_keystream(&mut enc);
        enc
    } else {
        exported_session_key.copy_from_slice(&session_base_key);
        Vec::new()
    };

    let domain_utf16 = encode_utf16le(&domain);
    let user_utf16 = encode_utf16le(&username);
    let workstation_utf16 = encode_utf16le("NEXUSTS");

    // 88-byte Type 3 header (64 bytes security buffers + 8 bytes Version + 16 bytes MIC)
    let header_len: usize = 88;
    let domain_off = header_len;
    let user_off = domain_off + domain_utf16.len();
    let workstation_off = user_off + user_utf16.len();
    let lm_off = workstation_off + workstation_utf16.len();
    let nt_off = lm_off + lm_challenge_response.len();
    let session_key_off = nt_off + nt_challenge_response.len();
    let total_len = session_key_off + encrypted_random_session_key.len();

    let mut type3 = Vec::with_capacity(total_len);
    type3.extend_from_slice(NTLMSSP_SIGNATURE);
    type3.extend_from_slice(&3u32.to_le_bytes());

    let push_sec_buf = |dst: &mut Vec<u8>, len: usize, offset: usize| {
        let len_u16 = len as u16;
        dst.extend_from_slice(&len_u16.to_le_bytes());
        dst.extend_from_slice(&len_u16.to_le_bytes());
        dst.extend_from_slice(&(offset as u32).to_le_bytes());
    };

    push_sec_buf(&mut type3, lm_challenge_response.len(), lm_off);
    push_sec_buf(&mut type3, nt_challenge_response.len(), nt_off);
    push_sec_buf(&mut type3, domain_utf16.len(), domain_off);
    push_sec_buf(&mut type3, user_utf16.len(), user_off);
    push_sec_buf(&mut type3, workstation_utf16.len(), workstation_off);
    push_sec_buf(&mut type3, encrypted_random_session_key.len(), session_key_off);
    type3.extend_from_slice(&negotiated_flags.to_le_bytes());

    // Version (8 bytes)
    type3.extend_from_slice(&[10, 0]);
    type3.extend_from_slice(&19041u16.to_le_bytes());
    type3.extend_from_slice(&[0, 0, 0, 15]);

    // Placeholder 16-byte zero MIC at bytes [72..88]
    type3.extend_from_slice(&[0u8; 16]);

    // Variable payload
    type3.extend_from_slice(&domain_utf16);
    type3.extend_from_slice(&user_utf16);
    type3.extend_from_slice(&workstation_utf16);
    type3.extend_from_slice(&lm_challenge_response);
    type3.extend_from_slice(&nt_challenge_response);
    type3.extend_from_slice(&encrypted_random_session_key);

    // Compute MIC = HMAC_MD5(ExportedSessionKey, Type1 || Type2 || Type3_with_zero_MIC)
    let mut mic_input =
        Vec::with_capacity(type1_bytes.len() + parsed.raw_type2.len() + type3.len());
    mic_input.extend_from_slice(type1_bytes);
    mic_input.extend_from_slice(&parsed.raw_type2);
    mic_input.extend_from_slice(&type3);
    let mic_bytes = hmac_md5(&exported_session_key, &mic_input);
    type3[72..88].copy_from_slice(&mic_bytes);

    let sealing = NtlmSealingContext::from_exported_session_key(&exported_session_key, negotiated_flags);
    Ok((type3, sealing))
}

/// Stateless helper for HTTPS (`:5986`) callers that already have TLS channel encryption and only
/// need the Base64 Type 3 NTLMv2 token from a Base64 Type 2 `WWW-Authenticate` challenge.
pub fn ntlm_build_authenticate_b64(
    server_challenge_b64: &str,
    username: &str,
    password: &str,
    domain: &str,
    spn_host: &str,
) -> Result<String, WinRmError> {
    let clean_b64 = server_challenge_b64
        .trim()
        .strip_prefix("Negotiate ")
        .or_else(|| server_challenge_b64.trim().strip_prefix("NTLM "))
        .unwrap_or(server_challenge_b64.trim())
        .trim();
    let challenge_bytes = BASE64_STD
        .decode(clean_b64)
        .map_err(|e| WinRmError::InvalidNtlmChallenge(format!("Base64 decode failed: {e}")))?;
    let type1 = build_ntlm_type1_bytes();
    let (type3, _) = build_ntlm_type3_and_sealing(
        &type1,
        &challenge_bytes,
        username,
        password,
        domain,
        spn_host,
    )?;
    Ok(BASE64_STD.encode(type3))
}

#[derive(Debug)]
struct RawHttpWireResponse {
    status_code: u16,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl RawHttpWireResponse {
    fn header_values<'a>(&'a self, name: &'a str) -> impl Iterator<Item = &'a str> {
        self.headers
            .iter()
            .filter(move |(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    fn content_type(&self) -> String {
        self.header_values("content-type")
            .next()
            .unwrap_or("")
            .to_string()
    }
}

async fn read_http_response(stream: &mut TcpStream) -> Result<RawHttpWireResponse, WinRmError> {
    let mut buf = Vec::with_capacity(4096);
    let mut temp = [0u8; 4096];

    let header_end = loop {
        if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break pos;
        }
        let n = stream.read(&mut temp).await?;
        if n == 0 {
            return Err(WinRmError::Io(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "WinRM server closed socket before sending HTTP headers",
            )));
        }
        buf.extend_from_slice(&temp[..n]);
        if buf.len() > 256 * 1024 {
            return Err(WinRmError::HttpError {
                status: 0,
                detail: "HTTP response headers exceeded 256 KiB".into(),
            });
        }
    };

    let header_text = String::from_utf8_lossy(&buf[..header_end]).into_owned();
    let mut lines = header_text.split("\r\n");
    let status_line = lines.next().unwrap_or("");
    let status_code = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .ok_or_else(|| WinRmError::HttpError {
            status: 0,
            detail: format!("Malformed HTTP status line: {status_line}"),
        })?;

    let mut headers = Vec::new();
    let mut content_length: Option<usize> = None;
    let mut is_chunked = false;

    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            let key = k.trim().to_string();
            let val = v.trim().to_string();
            if key.eq_ignore_ascii_case("content-length") {
                content_length = val.parse::<usize>().ok();
            } else if key.eq_ignore_ascii_case("transfer-encoding")
                && val.to_ascii_lowercase().contains("chunked")
            {
                is_chunked = true;
            }
            headers.push((key, val));
        }
    }

    let body_start = header_end + 4;
    let mut remainder = buf.split_off(body_start);

    let body = if is_chunked {
        read_chunked_body(stream, &mut remainder).await?
    } else if let Some(expected_len) = content_length {
        while remainder.len() < expected_len {
            let n = stream.read(&mut temp).await?;
            if n == 0 {
                break;
            }
            remainder.extend_from_slice(&temp[..n]);
        }
        remainder.truncate(expected_len);
        remainder
    } else if status_code == 204 || status_code == 304 || (100..200).contains(&status_code) {
        Vec::new()
    } else {
        // Connection-close delimited fallback
        loop {
            let n = stream.read(&mut temp).await?;
            if n == 0 {
                break;
            }
            remainder.extend_from_slice(&temp[..n]);
            if remainder.len() > 16 * 1024 * 1024 {
                break;
            }
        }
        remainder
    };

    Ok(RawHttpWireResponse {
        status_code,
        headers,
        body,
    })
}

async fn read_chunked_body(
    stream: &mut TcpStream,
    buf: &mut Vec<u8>,
) -> Result<Vec<u8>, WinRmError> {
    let mut decoded = Vec::new();
    let mut temp = [0u8; 4096];

    loop {
        let line_end = loop {
            if let Some(pos) = buf.windows(2).position(|w| w == b"\r\n") {
                break pos;
            }
            let n = stream.read(&mut temp).await?;
            if n == 0 {
                return Ok(decoded);
            }
            buf.extend_from_slice(&temp[..n]);
        };

        let size_str = String::from_utf8_lossy(&buf[..line_end]);
        let hex_part = size_str.split(';').next().unwrap_or("").trim();
        let chunk_size = usize::from_str_radix(hex_part, 16).map_err(|_| WinRmError::HttpError {
            status: 200,
            detail: format!("Malformed chunk size '{hex_part}'"),
        })?;
        buf.drain(..line_end + 2);

        if chunk_size == 0 {
            // Drain trailing CRLF
            while buf.len() < 2 {
                let n = stream.read(&mut temp).await?;
                if n == 0 {
                    break;
                }
                buf.extend_from_slice(&temp[..n]);
            }
            if buf.starts_with(b"\r\n") {
                buf.drain(..2);
            }
            break;
        }

        while buf.len() < chunk_size + 2 {
            let n = stream.read(&mut temp).await?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&temp[..n]);
        }
        let take_n = chunk_size.min(buf.len());
        decoded.extend_from_slice(&buf[..take_n]);
        let drain_n = (chunk_size + 2).min(buf.len());
        buf.drain(..drain_n);
    }

    Ok(decoded)
}

fn extract_ntlm_challenge_header(resp: &RawHttpWireResponse) -> Option<(&'static str, Vec<u8>)> {
    for hdr in resp.header_values("www-authenticate") {
        let trimmed = hdr.trim();
        for scheme in ["Negotiate", "NTLM"] {
            if let Some(rest) = trimmed.strip_prefix(scheme) {
                let token_part = rest.trim().split(',').next().unwrap_or("").trim();
                if !token_part.is_empty() {
                    if let Ok(bytes) = BASE64_STD.decode(token_part) {
                        if bytes.windows(8).any(|w| w == NTLMSSP_SIGNATURE) {
                            return Some((scheme, bytes));
                        }
                    }
                }
            }
        }
    }
    None
}

async fn send_http_post(
    stream: &mut TcpStream,
    host_header: &str,
    path: &str,
    auth_header: Option<&str>,
    content_type: &str,
    body: &[u8],
) -> Result<RawHttpWireResponse, WinRmError> {
    let mut req = Vec::with_capacity(512 + body.len());
    req.extend_from_slice(format!("POST {path} HTTP/1.1\r\n").as_bytes());
    req.extend_from_slice(format!("Host: {host_header}\r\n").as_bytes());
    req.extend_from_slice(b"User-Agent: Microsoft WinRM Client\r\n");
    req.extend_from_slice(b"Accept: */*\r\n");
    req.extend_from_slice(b"Connection: Keep-Alive\r\n");
    if let Some(auth) = auth_header {
        req.extend_from_slice(format!("Authorization: {auth}\r\n").as_bytes());
    }
    req.extend_from_slice(format!("Content-Type: {content_type}\r\n").as_bytes());
    req.extend_from_slice(format!("Content-Length: {}\r\n\r\n", body.len()).as_bytes());
    req.extend_from_slice(body);

    stream.write_all(&req).await?;
    stream.flush().await?;
    read_http_response(stream).await
}

/// Executes an authenticated WinRM `/wsman` SOAP `POST` over HTTP (`:5985` or custom port) using
/// `[MS-NLMP]` NTLMv2 + `[MS-WSMV]` `HTTP-SPNEGO-session-encrypted` sealing over a single persistent
/// TCP Keep-Alive connection, automatically unwrapping encrypted or plain SOAP responses.
pub async fn winrm_http_wsman_post(
    host: &str,
    port: u16,
    path: &str,
    username: &str,
    password: &str,
    domain: &str,
    soap_xml: &str,
    timeout_ms: u64,
) -> Result<WinRmHttpResponse, WinRmError> {
    let effective_port = if port == 0 { 5985 } else { port };
    let effective_path = if path.trim().is_empty() {
        "/wsman"
    } else if path.starts_with('/') {
        path.trim()
    } else {
        "/wsman"
    };
    let clean_host = host.trim();
    let host_header = if clean_host.contains(':') && !clean_host.starts_with('[') {
        format!("[{clean_host}]:{effective_port}")
    } else {
        format!("{clean_host}:{effective_port}")
    };
    let target = format_socket_target(clean_host, effective_port);
    let timeout_dur = Duration::from_millis(timeout_ms.clamp(1_000, 60_000));

    tokio::time::timeout(timeout_dur, async {
        let mut stream = TcpStream::connect(&target).await?;
        let _ = stream.set_nodelay(true);

        // Step 1: Send NTLMSSP Type 1 Negotiate with Content-Length: 0 on Keep-Alive socket
        let type1_bytes = build_ntlm_type1_bytes();
        let auth_step1 = format!("Negotiate {}", BASE64_STD.encode(&type1_bytes));
        let step1_resp = send_http_post(
            &mut stream,
            &host_header,
            effective_path,
            Some(&auth_step1),
            "application/soap+xml;charset=UTF-8",
            &[],
        )
        .await?;

        if let Some((scheme, challenge_bytes)) = extract_ntlm_challenge_header(&step1_resp) {
            let (type3_bytes, mut sealing) = build_ntlm_type3_and_sealing(
                &type1_bytes,
                &challenge_bytes,
                username,
                password,
                domain,
                clean_host,
            )?;
            let auth_step2 = format!("{scheme} {}", BASE64_STD.encode(&type3_bytes));

            // When Negotiate was accepted, encrypt SOAP payload per [MS-WSMV] 2.2.9.1.2
            let (content_type, outbound_body, used_spnego) = if scheme.eq_ignore_ascii_case("Negotiate") {
                (
                    "multipart/encrypted;protocol=\"application/HTTP-SPNEGO-session-encrypted\";boundary=\"Encrypted Boundary\"",
                    sealing.wrap_wsmv_soap(soap_xml.as_bytes()),
                    true,
                )
            } else {
                (
                    "application/soap+xml;charset=UTF-8",
                    soap_xml.as_bytes().to_vec(),
                    false,
                )
            };

            let mut step2_resp = send_http_post(
                &mut stream,
                &host_header,
                effective_path,
                Some(&auth_step2),
                content_type,
                &outbound_body,
            )
            .await?;

            // Handle proxy/server edge case where Type 3 returned 200 empty body to complete auth first
            if step2_resp.status_code == 200 && step2_resp.body.is_empty() && used_spnego {
                let mut fresh_sealing = sealing;
                let retry_body = fresh_sealing.wrap_wsmv_soap(soap_xml.as_bytes());
                step2_resp = send_http_post(
                    &mut stream,
                    &host_header,
                    effective_path,
                    None,
                    content_type,
                    &retry_body,
                )
                .await?;
                return decode_final_winrm_response(step2_resp, &mut fresh_sealing, true);
            }

            return decode_final_winrm_response(step2_resp, &mut sealing, used_spnego);
        }

        // Fallback: Server only offered Basic auth (AllowUnencrypted=true & Basic=true)
        let (dom, usr) = split_windows_account(username, domain);
        let basic_user = if dom.is_empty() {
            usr
        } else {
            format!("{dom}\\{usr}")
        };
        let basic_token = BASE64_STD.encode(format!("{basic_user}:{password}"));
        let basic_auth = format!("Basic {basic_token}");
        let mut basic_stream = TcpStream::connect(&target).await?;
        let basic_resp = send_http_post(
            &mut basic_stream,
            &host_header,
            effective_path,
            Some(&basic_auth),
            "application/soap+xml;charset=UTF-8",
            soap_xml.as_bytes(),
        )
        .await?;

        let mut dummy_seal = NtlmSealingContext::from_exported_session_key(&[0u8; 16], 0);
        decode_final_winrm_response(basic_resp, &mut dummy_seal, false)
    })
    .await
    .map_err(|_| WinRmError::Timeout(timeout_ms))?
}

fn decode_final_winrm_response(
    resp: RawHttpWireResponse,
    sealing: &mut NtlmSealingContext,
    used_spnego_encryption: bool,
) -> Result<WinRmHttpResponse, WinRmError> {
    let ct = resp.content_type();
    if resp.status_code == 401 || resp.status_code == 403 {
        return Err(WinRmError::AuthenticationFailed {
            status: resp.status_code,
            detail: if resp.status_code == 401 {
                "Invalid Hyper-V Windows credentials (HTTP 401 Unauthorized)".into()
            } else {
                "Access denied by Hyper-V WinRM (HTTP 403 Forbidden — ensure account is in Administrators or Hyper-V Administrators)".into()
            },
        });
    }

    let is_multipart_encrypted = ct.to_ascii_lowercase().contains("multipart/encrypted")
        || resp
            .body
            .windows(24)
            .any(|w| w.eq_ignore_ascii_case(b"application/octet-stream"));

    let decoded_bytes = if is_multipart_encrypted {
        sealing.unwrap_wsmv_multipart(&resp.body)?
    } else {
        resp.body
    };

    let soap_xml = String::from_utf8_lossy(&decoded_bytes).into_owned();
    if !(200..300).contains(&resp.status_code) {
        let preview: String = soap_xml.chars().take(400).collect();
        return Err(WinRmError::HttpError {
            status: resp.status_code,
            detail: if preview.trim().is_empty() {
                format!("HTTP {}", resp.status_code)
            } else {
                preview
            },
        });
    }

    Ok(WinRmHttpResponse {
        status_code: resp.status_code,
        content_type: ct,
        soap_xml,
        used_spnego_encryption,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    fn hex_encode(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    fn hex_decode(hex: &str) -> Vec<u8> {
        (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect()
    }

    #[test]
    fn rfc1320_md4_official_test_vectors() {
        assert_eq!(
            hex_encode(&md4_digest(b"")),
            "31d6cfe0d16ae931b73c59d7e0c089c0"
        );
        assert_eq!(
            hex_encode(&md4_digest(b"abc")),
            "a448017aaf21d8525fc10ae87aa6729d"
        );
        assert_eq!(
            hex_encode(&md4_digest(b"12345678901234567890123456789012345678901234567890123456789012345678901234567890")),
            "e33b4ddc9c38f2199c3e7b164fcc0536"
        );
    }

    #[test]
    fn ms_nlmp_section_4_2_3_4_ntowfv2_and_sealing_key_vectors() {
        // [MS-NLMP] 4.2.1: User="User", Domain="Domain", Password="Password"
        let key_nt = ntowfv2("Password", "User", "Domain");
        assert_eq!(hex_encode(&key_nt), "0c868a403bfd7a93a3001ef22ef02e3f");

        // [MS-NLMP] 4.2.3.4: 56-bit sealing key derivation with trailing NUL byte
        let exported_key_56_prefix = hex_decode("eb93429a8bd952");
        let seal_key = md5_concat(&exported_key_56_prefix, CLIENT_SEAL_MAGIC);
        assert_eq!(hex_encode(&seal_key), "04dd7f014d8504d265a25cc86a3a7c06");

        // Verify dot-domain normalization (`.\Administrator` -> `("", "Administrator")`)
        assert_eq!(
            split_windows_account(r".\Administrator", ""),
            (String::new(), String::from("Administrator"))
        );
        assert_eq!(
            split_windows_account(r"CONTOSO\petros", ""),
            (String::from("CONTOSO"), String::from("petros"))
        );
    }

    #[test]
    fn wsmv_spnego_multipart_seal_and_multichunk_unseal_roundtrip() {
        let session_key = [0x42u8; 16];
        let flags = DEFAULT_CLIENT_NTLM_FLAGS;

        let mut client_ctx = NtlmSealingContext::from_exported_session_key(&session_key, flags);
        // Build a symmetric server receiver by swapping sign/seal keys
        let mut receiver_ctx = NtlmSealingContext {
            client_sign_key: client_ctx.server_sign_key,
            server_sign_key: client_ctx.client_sign_key,
            client_rc4: client_ctx.server_rc4.clone(),
            server_rc4: client_ctx.client_rc4.clone(),
            client_seq: 0,
            server_seq: 0,
            key_exch: true,
        };

        let soap = r#"<s:Envelope><s:Body><EnumerateResponse><ElementName>Prod-SQL-01</ElementName></EnumerateResponse></s:Body></s:Envelope>"#;
        let wrapped = client_ctx.wrap_wsmv_soap(soap.as_bytes());
        let unwrapped = receiver_ctx.unwrap_wsmv_multipart(&wrapped).unwrap();
        assert_eq!(String::from_utf8(unwrapped).unwrap(), soap);

        // Also test a 2-chunk [MS-WSMV] response (verifying RC4 8-byte checksum state advancement!)
        let chunk_a = b"<s:Envelope><s:Body><ChunkA>";
        let chunk_b = b"</ChunkA><ChunkB>VM-02</ChunkB></s:Body></s:Envelope>";
        let (sig_a, enc_a) = client_ctx.seal_raw(chunk_a);
        let (sig_b, enc_b) = client_ctx.seal_raw(chunk_b);

        let mut multi = Vec::new();
        multi.extend_from_slice(
            format!(
                "-- Encrypted Boundary\r\n\tContent-Type: application/HTTP-SPNEGO-session-encrypted\r\n\tOriginalContent: type=application/soap+xml;charset=UTF-8;Length={}\r\n-- Encrypted Boundary\r\n\tContent-Type: application/octet-stream\r\n",
                chunk_a.len()
            )
            .as_bytes(),
        );
        multi.extend_from_slice(&16u32.to_le_bytes());
        multi.extend_from_slice(&sig_a);
        multi.extend_from_slice(&enc_a);
        multi.extend_from_slice(
            format!(
                "-- Encrypted Boundary\r\n\tContent-Type: application/HTTP-SPNEGO-session-encrypted\r\n\tOriginalContent: type=application/soap+xml;charset=UTF-8;Length={}\r\n-- Encrypted Boundary\r\n\tContent-Type: application/octet-stream\r\n",
                chunk_b.len()
            )
            .as_bytes(),
        );
        multi.extend_from_slice(&16u32.to_le_bytes());
        multi.extend_from_slice(&sig_b);
        multi.extend_from_slice(&enc_b);
        multi.extend_from_slice(b"-- Encrypted Boundary--\r\n");

        let joined = receiver_ctx.unwrap_wsmv_multipart(&multi).unwrap();
        assert_eq!(
            String::from_utf8(joined).unwrap(),
            "<s:Envelope><s:Body><ChunkA></ChunkA><ChunkB>VM-02</ChunkB></s:Body></s:Envelope>"
        );
    }

    #[tokio::test]
    async fn live_loopback_winrm_5985_ntlmv2_spnego_sealed_exchange() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();

        let server_task = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();

            // Read Step 1 (Negotiate Type 1)
            let req1 = read_http_response_like_request(&mut sock).await;
            assert!(req1.contains("Authorization: Negotiate "));

            // Build NTLMSSP Type 2 Challenge with TargetInfo + Timestamp
            let mut ti = Vec::new();
            ti.extend_from_slice(&MSV_AV_TIMESTAMP.to_le_bytes());
            ti.extend_from_slice(&8u16.to_le_bytes());
            ti.extend_from_slice(&133_500_000_000_000_000u64.to_le_bytes());
            ti.extend_from_slice(&MSV_AV_EOL.to_le_bytes());
            ti.extend_from_slice(&0u16.to_le_bytes());

            let mut type2 = Vec::new();
            type2.extend_from_slice(NTLMSSP_SIGNATURE);
            type2.extend_from_slice(&2u32.to_le_bytes());
            // TargetName empty (offset 56)
            type2.extend_from_slice(&0u16.to_le_bytes());
            type2.extend_from_slice(&0u16.to_le_bytes());
            type2.extend_from_slice(&56u32.to_le_bytes());
            let srv_flags = DEFAULT_CLIENT_NTLM_FLAGS | NTLMSSP_NEGOTIATE_TARGET_INFO;
            type2.extend_from_slice(&srv_flags.to_le_bytes());
            let srv_challenge = [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88];
            type2.extend_from_slice(&srv_challenge);
            type2.extend_from_slice(&[0u8; 8]); // Reserved
            type2.extend_from_slice(&(ti.len() as u16).to_le_bytes());
            type2.extend_from_slice(&(ti.len() as u16).to_le_bytes());
            type2.extend_from_slice(&56u32.to_le_bytes());
            type2.extend_from_slice(&[10, 0, 0x61, 0x4a, 0, 0, 0, 15]);
            type2.extend_from_slice(&ti);

            let challenge_hdr = format!(
                "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Negotiate {}\r\nContent-Length: 0\r\nConnection: Keep-Alive\r\n\r\n",
                BASE64_STD.encode(&type2)
            );
            sock.write_all(challenge_hdr.as_bytes()).await.unwrap();

            // Read Step 2 (Type 3 + SPNEGO Encrypted Body)
            let (req2_hdr, req2_body) = read_raw_request_with_body(&mut sock).await;
            let auth_line = req2_hdr
                .lines()
                .find(|l| l.starts_with("Authorization: Negotiate "))
                .unwrap();
            let type3_b64 = auth_line.trim_start_matches("Authorization: Negotiate ").trim();
            let type3 = BASE64_STD.decode(type3_b64).unwrap();

            // Server derives ExportedSessionKey from client's Type 3 to unseal & reply!
            let nt_off = u32::from_le_bytes([type3[24], type3[25], type3[26], type3[27]]) as usize;
            let nt_len = u16::from_le_bytes([type3[20], type3[21]]) as usize;
            let nt_resp = &type3[nt_off..nt_off + nt_len];
            let nt_proof = &nt_resp[0..16];

            let sk_len = u16::from_le_bytes([type3[52], type3[53]]) as usize;
            let sk_off = u32::from_le_bytes([type3[56], type3[57], type3[58], type3[59]]) as usize;
            let enc_sk = &type3[sk_off..sk_off + sk_len];

            let resp_key_nt = ntowfv2("HyperVPass!23", "Administrator", "");
            let session_base_key = hmac_md5(&resp_key_nt, nt_proof);
            let mut exported_sk = [0u8; 16];
            exported_sk.copy_from_slice(enc_sk);
            Rc4Cipher::new(&session_base_key).apply_keystream(&mut exported_sk);

            let client_ctx_keys =
                NtlmSealingContext::from_exported_session_key(&exported_sk, DEFAULT_CLIENT_NTLM_FLAGS);
            let mut server_seal = NtlmSealingContext {
                client_sign_key: client_ctx_keys.server_sign_key,
                server_sign_key: client_ctx_keys.client_sign_key,
                client_rc4: client_ctx_keys.server_rc4,
                server_rc4: client_ctx_keys.client_rc4,
                client_seq: 0,
                server_seq: 0,
                key_exch: true,
            };

            let decrypted_req = server_seal.unwrap_wsmv_multipart(&req2_body).unwrap();
            assert!(String::from_utf8_lossy(&decrypted_req).contains("Msvm_ComputerSystem"));

            let reply_soap = r#"<s:Envelope><s:Body><n:EnumerateResponse><w:Items><p:Msvm_ComputerSystem><p:Name>A1B2C3D4-1111-2222-3333-444455556666</p:Name><p:ElementName>HV-DC01</p:ElementName><p:EnabledState>2</p:EnabledState></p:Msvm_ComputerSystem></w:Items></n:EnumerateResponse></s:Body></s:Envelope>"#;
            let encrypted_reply = server_seal.wrap_wsmv_soap(reply_soap.as_bytes());
            let resp_hdr = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: multipart/encrypted;protocol=\"application/HTTP-SPNEGO-session-encrypted\";boundary=\"Encrypted Boundary\"\r\nContent-Length: {}\r\n\r\n",
                encrypted_reply.len()
            );
            sock.write_all(resp_hdr.as_bytes()).await.unwrap();
            sock.write_all(&encrypted_reply).await.unwrap();
        });

        let resp = winrm_http_wsman_post(
            "127.0.0.1",
            port,
            "/wsman",
            r".\Administrator",
            "HyperVPass!23",
            "",
            "<s:Envelope><s:Body>SELECT * FROM Msvm_ComputerSystem</s:Body></s:Envelope>",
            5_000,
        )
        .await
        .unwrap();

        server_task.await.unwrap();
        assert_eq!(resp.status_code, 200);
        assert!(resp.used_spnego_encryption);
        assert!(resp.soap_xml.contains("HV-DC01"));
        assert!(resp.soap_xml.contains("A1B2C3D4-1111-2222-3333-444455556666"));
    }

    async fn read_http_response_like_request(sock: &mut TcpStream) -> String {
        let (hdr, _) = read_raw_request_with_body(sock).await;
        hdr
    }

    async fn read_raw_request_with_body(sock: &mut TcpStream) -> (String, Vec<u8>) {
        let mut buf = Vec::new();
        let mut tmp = [0u8; 2048];
        let hdr_end = loop {
            if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                break pos;
            }
            let n = sock.read(&mut tmp).await.unwrap();
            buf.extend_from_slice(&tmp[..n]);
        };
        let hdr = String::from_utf8_lossy(&buf[..hdr_end]).into_owned();
        let cl = hdr
            .lines()
            .find_map(|l| {
                l.strip_prefix("Content-Length:")
                    .or_else(|| l.strip_prefix("content-length:"))
                    .and_then(|v| v.trim().parse::<usize>().ok())
            })
            .unwrap_or(0);
        let mut body = buf.split_off(hdr_end + 4);
        while body.len() < cl {
            let n = sock.read(&mut tmp).await.unwrap();
            body.extend_from_slice(&tmp[..n]);
        }
        body.truncate(cl);
        (hdr, body)
    }
}
