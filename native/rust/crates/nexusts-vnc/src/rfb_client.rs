//! RFB 3.3 / 3.7 / 3.8 Live Async Wire Client, Security Negotiation
//! (None, VNC DES Auth Type 2, & Apple Remote Desktop / Screen Sharing DH-AES Type 30),
//! Streaming Hextile Decoder, and Encoding Advertisement.

use crate::{
    build_rfb_client_cut_text, build_rfb_key_event, build_rfb_pointer_event, RfbFramebuffer,
    VncError, RFB_ENCODING_COPYRECT, RFB_ENCODING_HEXTILE, RFB_ENCODING_RAW, RFB_ENCODING_RRE,
    RFB_ENCODING_TIGHT, RFB_ENCODING_ZRLE, RFB_PSEUDO_DESKTOP_SIZE,
};
use aes::Aes128;
use cipher::generic_array::GenericArray;
use cipher::{BlockEncrypt, KeyInit};
use des::Des;
use num_bigint::BigUint;
use rand::RngCore;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::task::AbortHandle;

pub const RFB_VERSION_3_3: &[u8; 12] = b"RFB 003.003\n";
pub const RFB_VERSION_3_7: &[u8; 12] = b"RFB 003.007\n";
pub const RFB_VERSION_3_8: &[u8; 12] = b"RFB 003.008\n";
pub const RFB_SEC_NONE: u8 = 1;
pub const RFB_SEC_VNC_AUTH: u8 = 2;
pub const RFB_SEC_VENCRYPT: u8 = 19;
pub const RFB_SEC_APPLE_ARD: u8 = 30;

/// Builds the RFB 3.8 `SetPixelFormat` (msg 0) packet requesting 32-bpp true-color RGBA8888 little-endian.
pub fn build_rfb_rgba8888_pixel_format() -> [u8; 20] {
    [
        0, // message-type = SetPixelFormat
        0, 0, 0, // padding
        32, // bits-per-pixel
        24, // depth
        0,  // big-endian-flag = false
        1,  // true-color-flag = true
        0, 255, // red-max = 255
        0, 255, // green-max = 255
        0, 255, // blue-max = 255
        0,  // red-shift = 0
        8,  // green-shift = 8
        16, // blue-shift = 16
        0, 0, 0, // padding
    ]
}

/// Builds the canonical RFB `SetPixelFormat` (msg 0) packet requesting
/// 32-bpp, depth 24, little-endian, true-color, R max 255 shift 16, G max 255 shift 8, B max 255 shift 0.
pub fn build_rfb_canonical_pixel_format() -> [u8; 20] {
    [
        0, // message-type = SetPixelFormat
        0, 0, 0, // padding
        32, // bits-per-pixel
        24, // depth
        0,  // big-endian-flag = false
        1,  // true-color-flag = true
        0, 255, // red-max = 255
        0, 255, // green-max = 255
        0, 255, // blue-max = 255
        16, // red-shift = 16
        8,  // green-shift = 8
        0,  // blue-shift = 0
        0, 0, 0, // padding
    ]
}

/// Builds the RFB 3.8 `SetEncodings` (msg 2) packet advertising ZRLE, Tight, CopyRect, Hextile, and Raw.
pub fn build_rfb_preferred_encodings(prefer_zrle: bool) -> Vec<u8> {
    let encodings: Vec<i32> = if prefer_zrle {
        vec![
            RFB_ENCODING_ZRLE,
            RFB_ENCODING_TIGHT,
            RFB_ENCODING_COPYRECT,
            RFB_ENCODING_HEXTILE,
            RFB_ENCODING_RAW,
        ]
    } else {
        vec![
            RFB_ENCODING_TIGHT,
            RFB_ENCODING_ZRLE,
            RFB_ENCODING_COPYRECT,
            RFB_ENCODING_HEXTILE,
            RFB_ENCODING_RAW,
        ]
    };

    build_set_encodings_packet(&encodings)
}

/// Builds the live session `SetEncodings` packet advertising
/// `[16 ZRLE, 5 Hextile, 2 RRE, 1 CopyRect, 0 Raw, -223 DesktopSize]`.
pub fn build_rfb_live_session_encodings() -> Vec<u8> {
    build_set_encodings_packet(&[
        RFB_ENCODING_ZRLE,
        RFB_ENCODING_HEXTILE,
        RFB_ENCODING_RRE,
        RFB_ENCODING_COPYRECT,
        RFB_ENCODING_RAW,
        RFB_PSEUDO_DESKTOP_SIZE,
    ])
}

fn build_set_encodings_packet(encodings: &[i32]) -> Vec<u8> {
    let mut pkt = Vec::with_capacity(4 + encodings.len() * 4);
    pkt.push(2); // SetEncodings
    pkt.push(0); // padding
    pkt.extend_from_slice(&(encodings.len() as u16).to_be_bytes());
    for &enc in encodings {
        pkt.extend_from_slice(&enc.to_be_bytes());
    }
    pkt
}

/// Builds an RFB `FramebufferUpdateRequest` (msg 3) packet.
pub fn build_rfb_update_request(incremental: bool, x: u16, y: u16, w: u16, h: u16) -> [u8; 10] {
    let mut pkt = [0u8; 10];
    pkt[0] = 3;
    pkt[1] = u8::from(incremental);
    pkt[2..4].copy_from_slice(&x.to_be_bytes());
    pkt[4..6].copy_from_slice(&y.to_be_bytes());
    pkt[6..8].copy_from_slice(&w.to_be_bytes());
    pkt[8..10].copy_from_slice(&h.to_be_bytes());
    pkt
}

/// Encrypts a 16-byte RFB Type 2 VNC Authentication challenge using DES-ECB
/// with bit-reversed password key bytes per RFC 6143 §7.2.2.
pub fn encrypt_vnc_des_challenge(challenge: &[u8; 16], password: &str) -> [u8; 16] {
    let mut reversed_key = [0u8; 8];
    for (idx, &b) in password.as_bytes().iter().take(8).enumerate() {
        reversed_key[idx] = b.reverse_bits();
    }
    let cipher = Des::new_from_slice(&reversed_key).expect("8-byte DES key");
    let mut out = *challenge;
    cipher.encrypt_block(GenericArray::from_mut_slice(&mut out[0..8]));
    cipher.encrypt_block(GenericArray::from_mut_slice(&mut out[8..16]));
    out
}

/// Computes the Apple Remote Desktop / macOS Screen Sharing Security Type 30 (`DH-AES128-ECB`)
/// response packet `[ciphertext(128B) || client_pub(key_len)]`.
pub fn compute_apple_ard_auth_response(
    generator: u16,
    key_len: u16,
    prime_be: &[u8],
    server_pub_be: &[u8],
    username: &str,
    password: &str,
) -> Result<Vec<u8>, VncError> {
    let klen = key_len as usize;
    if !(16..=1024).contains(&klen) || prime_be.len() != klen || server_pub_be.len() != klen {
        return Err(VncError::Protocol(format!(
            "Invalid Apple ARD DH key parameters (key_len={key_len})"
        )));
    }

    let prime = BigUint::from_bytes_be(prime_be);
    if prime <= BigUint::from(2u8) {
        return Err(VncError::Protocol("Apple ARD DH prime too small".into()));
    }
    let server_pub = BigUint::from_bytes_be(server_pub_be);
    let gen_big = BigUint::from(generator);

    // 512-bit (64-byte) random private exponent
    let mut priv_bytes = [0u8; 64];
    rand::thread_rng().fill_bytes(&mut priv_bytes);
    priv_bytes[0] |= 0x80;
    priv_bytes[63] |= 0x01;
    let priv_exp = BigUint::from_bytes_be(&priv_bytes);

    let client_pub = gen_big.modpow(&priv_exp, &prime);
    let shared_secret = server_pub.modpow(&priv_exp, &prime);

    let client_pub_padded = pad_biguint_be(&client_pub, klen)?;
    let shared_padded = pad_biguint_be(&shared_secret, klen)?;

    let aes_key: [u8; 16] = md5::compute(&shared_padded).0;
    let cipher = Aes128::new_from_slice(&aes_key).expect("16-byte AES-128 key");

    let mut credentials_block = [0u8; 128];
    pack_ard_credential_field(&mut credentials_block[0..64], username);
    pack_ard_credential_field(&mut credentials_block[64..128], password);

    for chunk in credentials_block.chunks_exact_mut(16) {
        cipher.encrypt_block(GenericArray::from_mut_slice(chunk));
    }

    let mut response = Vec::with_capacity(128 + klen);
    response.extend_from_slice(&credentials_block);
    response.extend_from_slice(&client_pub_padded);
    Ok(response)
}

fn pad_biguint_be(value: &BigUint, target_len: usize) -> Result<Vec<u8>, VncError> {
    let raw = value.to_bytes_be();
    if raw.len() > target_len {
        return Err(VncError::Protocol(
            "DH value exceeded prime byte length".into(),
        ));
    }
    let mut padded = vec![0u8; target_len];
    let offset = target_len - raw.len();
    padded[offset..].copy_from_slice(&raw);
    Ok(padded)
}

fn pack_ard_credential_field(dst: &mut [u8], text: &str) {
    debug_assert_eq!(dst.len(), 64);
    let bytes = text.as_bytes();
    let copy_len = bytes.len().min(63);
    dst[..copy_len].copy_from_slice(&bytes[..copy_len]);
    dst[copy_len] = 0; // Explicit NUL terminator
    if copy_len + 1 < 64 {
        rand::thread_rng().fill_bytes(&mut dst[copy_len + 1..]);
    }
}

/// Decodes an RFB Hextile (Encoding 5) 16x16 tiled rectangle update into [`RfbFramebuffer`].
pub fn apply_hextile_rect(
    fb: &mut RfbFramebuffer,
    x: u16,
    y: u16,
    w: u16,
    h: u16,
    payload: &[u8],
) -> Result<(), VncError> {
    if (x as usize) + (w as usize) > fb.width as usize
        || (y as usize) + (h as usize) > fb.height as usize
    {
        return Err(VncError::OutOfBounds);
    }

    let fmt = fb.pixel_format;
    let bpp = fmt.bytes_per_pixel();
    let mut cursor = 0usize;
    let mut bg = [0u8, 0, 0, 255];
    let mut fg = [255u8, 255, 255, 255];

    let mut ty = y;
    while ty < y + h {
        let th = 16.min((y + h) - ty);
        let mut tx = x;
        while tx < x + w {
            let tw = 16.min((x + w) - tx);
            if cursor >= payload.len() {
                return Err(VncError::TruncatedPayload);
            }
            let submask = payload[cursor];
            cursor += 1;

            if submask & 0x01 != 0 {
                // Raw 16x16 tile
                let bytes_needed = (tw as usize) * (th as usize) * bpp;
                if cursor + bytes_needed > payload.len() {
                    return Err(VncError::TruncatedPayload);
                }
                fb.apply_raw_rect(tx, ty, tw, th, &payload[cursor..cursor + bytes_needed])?;
                cursor += bytes_needed;
            } else {
                if submask & 0x02 != 0 {
                    let (decoded_bg, step) = fmt.decode_pixel(&payload[cursor..])?;
                    bg = decoded_bg;
                    cursor += step;
                }
                if submask & 0x04 != 0 {
                    let (decoded_fg, step) = fmt.decode_pixel(&payload[cursor..])?;
                    fg = decoded_fg;
                    cursor += step;
                }

                fb.fill_solid_subrect(tx, ty, tw, th, bg);

                if submask & 0x08 != 0 {
                    if cursor >= payload.len() {
                        return Err(VncError::TruncatedPayload);
                    }
                    let num_subrects = payload[cursor] as usize;
                    cursor += 1;
                    let subrects_colored = (submask & 0x10) != 0;

                    for _ in 0..num_subrects {
                        let color = if subrects_colored {
                            let (c, step) = fmt.decode_pixel(&payload[cursor..])?;
                            cursor += step;
                            c
                        } else {
                            fg
                        };
                        if cursor + 2 > payload.len() {
                            return Err(VncError::TruncatedPayload);
                        }
                        let xy = payload[cursor];
                        let wh = payload[cursor + 1];
                        cursor += 2;
                        let sx = u16::from((xy >> 4) & 0x0F);
                        let sy = u16::from(xy & 0x0F);
                        let sw = u16::from(((wh >> 4) & 0x0F) + 1);
                        let sh = u16::from((wh & 0x0F) + 1);

                        let draw_w = sw.min(tw.saturating_sub(sx));
                        let draw_h = sh.min(th.saturating_sub(sy));
                        fb.fill_solid_subrect(tx + sx, ty + sy, draw_w, draw_h, color);
                    }
                }
            }

            tx += 16;
        }
        ty += 16;
    }

    Ok(())
}

/// Live asynchronous RFB 3.3 / 3.7 / 3.8 client supporting VNC Auth (DES) and
/// Apple Remote Desktop / macOS Screen Sharing (Type 30 DH-AES).
pub struct ActiveVncClient {
    framebuffer: Arc<Mutex<RfbFramebuffer>>,
    desktop_name: String,
    negotiated_version: &'static str,
    view_only: bool,
    connected: Arc<AtomicBool>,
    frame_sequence: Arc<AtomicU64>,
    last_error: Arc<Mutex<String>>,
    remote_clipboard: Arc<Mutex<Option<String>>>,
    outbound_tx: mpsc::UnboundedSender<Vec<u8>>,
    reader_abort: AbortHandle,
    writer_abort: AbortHandle,
}

impl ActiveVncClient {
    pub async fn connect(
        host: &str,
        port: u16,
        username: &str,
        password: &str,
        shared_session: bool,
        view_only: bool,
    ) -> Result<Self, VncError> {
        let trimmed_host = host.trim();
        if trimmed_host.is_empty() {
            return Err(VncError::Protocol("Empty VNC host".into()));
        }
        let target_port = if port == 0 { 5900 } else { port };
        let socket_target = format_vnc_socket_target(trimmed_host, target_port);

        let stream = tokio::time::timeout(
            Duration::from_secs(10),
            TcpStream::connect(&socket_target),
        )
        .await
        .map_err(|_| {
            VncError::Io(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!("Timed out connecting to VNC server {socket_target}"),
            ))
        })??;
        let _ = stream.set_nodelay(true);

        let (read_half, mut write_half) = tokio::io::split(stream);
        let mut reader = BufReader::with_capacity(64 * 1024, read_half);

        // 1. Read 12-byte RFB ProtocolVersion banner ("RFB xxx.yyy\n")
        let mut banner = [0u8; 12];
        reader.read_exact(&mut banner).await?;
        let (negotiated_minor, client_banner, version_label) = negotiate_rfb_version(&banner)?;
        write_half.write_all(client_banner).await?;
        write_half.flush().await?;

        // 2. Security negotiation
        let chosen_sec = if negotiated_minor == 3 {
            let sec_u32 = reader.read_u32().await?;
            if sec_u32 == 0 {
                let reason = read_rfb_reason_string(&mut reader).await?;
                return Err(VncError::AuthFailed(reason));
            }
            sec_u32 as u8
        } else {
            let count = reader.read_u8().await? as usize;
            if count == 0 {
                let reason = read_rfb_reason_string(&mut reader).await?;
                return Err(VncError::AuthFailed(reason));
            }
            let mut offered = vec![0u8; count];
            reader.read_exact(&mut offered).await?;
            let picked = select_security_type(&offered, username, password)?;
            write_half.write_u8(picked).await?;
            write_half.flush().await?;
            picked
        };

        // 3. Execute security handshake
        match chosen_sec {
            RFB_SEC_NONE => {
                if negotiated_minor >= 8 {
                    verify_security_result(&mut reader, negotiated_minor).await?;
                }
            }
            RFB_SEC_VNC_AUTH => {
                let mut challenge = [0u8; 16];
                reader.read_exact(&mut challenge).await?;
                let response = encrypt_vnc_des_challenge(&challenge, password);
                write_half.write_all(&response).await?;
                write_half.flush().await?;
                verify_security_result(&mut reader, negotiated_minor).await?;
            }
            RFB_SEC_APPLE_ARD => {
                let generator = reader.read_u16().await?;
                let key_len = reader.read_u16().await?;
                let klen = key_len as usize;
                if !(16..=1024).contains(&klen) {
                    return Err(VncError::Protocol(format!(
                        "Unsupported Apple ARD key length: {key_len}"
                    )));
                }
                let mut prime_be = vec![0u8; klen];
                let mut server_pub_be = vec![0u8; klen];
                reader.read_exact(&mut prime_be).await?;
                reader.read_exact(&mut server_pub_be).await?;

                let ard_packet = compute_apple_ard_auth_response(
                    generator,
                    key_len,
                    &prime_be,
                    &server_pub_be,
                    username,
                    password,
                )?;
                write_half.write_all(&ard_packet).await?;
                write_half.flush().await?;
                verify_security_result(&mut reader, negotiated_minor).await?;
            }
            other => {
                return Err(VncError::AuthFailed(format!(
                    "Unsupported RFB security type {other}"
                )));
            }
        }

        // 4. ClientInit & ServerInit
        write_half.write_u8(u8::from(shared_session)).await?;
        write_half.flush().await?;

        let raw_w = reader.read_u16().await?;
        let raw_h = reader.read_u16().await?;
        let fb_w = u32::from(raw_w.max(1));
        let fb_h = u32::from(raw_h.max(1));

        let mut _server_pixfmt = [0u8; 16];
        reader.read_exact(&mut _server_pixfmt).await?;

        let name_len = (reader.read_u32().await? as usize).min(65_536);
        let mut name_bytes = vec![0u8; name_len];
        reader.read_exact(&mut name_bytes).await?;
        let desktop_name = {
            let parsed = String::from_utf8_lossy(&name_bytes).trim().to_string();
            if parsed.is_empty() {
                format!("{trimmed_host}:{target_port}")
            } else {
                parsed
            }
        };

        // 5. Send canonical SetPixelFormat, SetEncodings, and initial full FramebufferUpdateRequest
        let set_pixfmt = build_rfb_canonical_pixel_format();
        let set_encodings = build_rfb_live_session_encodings();
        let initial_req = build_rfb_update_request(false, 0, 0, fb_w as u16, fb_h as u16);

        write_half.write_all(&set_pixfmt).await?;
        write_half.write_all(&set_encodings).await?;
        write_half.write_all(&initial_req).await?;
        write_half.flush().await?;

        let framebuffer = Arc::new(Mutex::new(RfbFramebuffer::new_canonical_bgra(fb_w, fb_h)));
        let connected = Arc::new(AtomicBool::new(true));
        let frame_sequence = Arc::new(AtomicU64::new(0));
        let last_error = Arc::new(Mutex::new(String::new()));
        let remote_clipboard = Arc::new(Mutex::new(None));

        let (outbound_tx, mut outbound_rx) = mpsc::unbounded_channel::<Vec<u8>>();

        let connected_writer = Arc::clone(&connected);
        let err_writer = Arc::clone(&last_error);
        let writer_handle = tokio::spawn(async move {
            while let Some(pkt) = outbound_rx.recv().await {
                if let Err(e) = write_half.write_all(&pkt).await {
                    set_shared_error(&err_writer, e.to_string());
                    connected_writer.store(false, Ordering::Release);
                    break;
                }
                if let Err(e) = write_half.flush().await {
                    set_shared_error(&err_writer, e.to_string());
                    connected_writer.store(false, Ordering::Release);
                    break;
                }
            }
            connected_writer.store(false, Ordering::Release);
        });

        let fb_reader = Arc::clone(&framebuffer);
        let connected_reader = Arc::clone(&connected);
        let seq_reader = Arc::clone(&frame_sequence);
        let err_reader = Arc::clone(&last_error);
        let clip_reader = Arc::clone(&remote_clipboard);
        let req_tx = outbound_tx.clone();

        let reader_handle = tokio::spawn(async move {
            if let Err(err) = run_rfb_reader_loop(
                &mut reader,
                &fb_reader,
                &seq_reader,
                &clip_reader,
                &req_tx,
            )
            .await
            {
                if connected_reader.load(Ordering::Acquire) {
                    set_shared_error(&err_reader, err.to_string());
                }
            }
            connected_reader.store(false, Ordering::Release);
        });

        Ok(Self {
            framebuffer,
            desktop_name,
            negotiated_version: version_label,
            view_only,
            connected,
            frame_sequence,
            last_error,
            remote_clipboard,
            outbound_tx,
            reader_abort: reader_handle.abort_handle(),
            writer_abort: writer_handle.abort_handle(),
        })
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

    pub fn desktop_name(&self) -> &str {
        &self.desktop_name
    }

    pub fn negotiated_version(&self) -> &'static str {
        self.negotiated_version
    }

    pub fn frame_sequence(&self) -> u64 {
        self.frame_sequence.load(Ordering::Acquire)
    }

    pub fn dimensions(&self) -> (u32, u32) {
        self.framebuffer
            .lock()
            .map(|fb| (fb.width, fb.height))
            .unwrap_or((0, 0))
    }

    pub fn copy_frame_rgba(&self, dst: &mut [u8]) -> usize {
        self.framebuffer
            .lock()
            .map(|fb| fb.copy_rgba(dst))
            .unwrap_or(0)
    }

    pub fn to_rgba_vec(&self) -> Vec<u8> {
        self.framebuffer
            .lock()
            .map(|fb| fb.rgba_pixels.clone())
            .unwrap_or_default()
    }

    pub fn copy_frame_bgra(&self, dst: &mut [u8]) -> usize {
        self.framebuffer
            .lock()
            .map(|fb| fb.copy_bgra(dst))
            .unwrap_or(0)
    }

    pub fn send_pointer(&self, button_mask: u8, x: u16, y: u16) -> bool {
        if self.view_only || !self.is_connected() {
            return false;
        }
        let pkt = build_rfb_pointer_event(button_mask, x, y);
        self.outbound_tx.send(pkt.to_vec()).is_ok()
    }

    pub fn send_key(&self, down: bool, keysym: u32) -> bool {
        if self.view_only || !self.is_connected() {
            return false;
        }
        let pkt = build_rfb_key_event(down, keysym);
        self.outbound_tx.send(pkt.to_vec()).is_ok()
    }

    pub fn send_clipboard(&self, text: &str) -> bool {
        if !self.is_connected() {
            return false;
        }
        let pkt = build_rfb_client_cut_text(text);
        self.outbound_tx.send(pkt).is_ok()
    }

    pub fn pull_clipboard(&self) -> Option<String> {
        self.remote_clipboard
            .lock()
            .ok()
            .and_then(|mut clip| clip.take())
    }

    pub fn disconnect(&self) {
        self.connected.store(false, Ordering::Release);
        self.reader_abort.abort();
        self.writer_abort.abort();
    }
}

impl Drop for ActiveVncClient {
    fn drop(&mut self) {
        self.disconnect();
    }
}

fn format_vnc_socket_target(host: &str, port: u16) -> String {
    if host.starts_with('[') && host.ends_with(']') {
        format!("{host}:{port}")
    } else if host.contains(':') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    }
}

fn negotiate_rfb_version(banner: &[u8; 12]) -> Result<(u16, &'static [u8; 12], &'static str), VncError> {
    let text = String::from_utf8_lossy(banner);
    if !text.starts_with("RFB ") {
        return Err(VncError::Protocol(format!(
            "Invalid RFB version banner: {}",
            text.trim()
        )));
    }
    let major: u16 = text.get(4..7).and_then(|s| s.parse().ok()).unwrap_or(3);
    let minor: u16 = text.get(8..11).and_then(|s| s.parse().ok()).unwrap_or(8);

    // Note: Apple Screen Sharing advertises "RFB 003.889\n" -> negotiate RFB 3.8!
    if major > 3 || (major == 3 && minor >= 8) {
        Ok((8, RFB_VERSION_3_8, "RFB 3.8"))
    } else if major == 3 && minor == 7 {
        Ok((7, RFB_VERSION_3_7, "RFB 3.7"))
    } else {
        Ok((3, RFB_VERSION_3_3, "RFB 3.3"))
    }
}

fn select_security_type(
    offered: &[u8],
    username: &str,
    password: &str,
) -> Result<u8, VncError> {
    let has_ard_creds = !username.is_empty() || !password.is_empty();
    if has_ard_creds && offered.contains(&RFB_SEC_APPLE_ARD) {
        return Ok(RFB_SEC_APPLE_ARD);
    }
    if !password.is_empty() && offered.contains(&RFB_SEC_VNC_AUTH) {
        return Ok(RFB_SEC_VNC_AUTH);
    }
    if offered.contains(&RFB_SEC_NONE) {
        return Ok(RFB_SEC_NONE);
    }
    if offered.contains(&RFB_SEC_APPLE_ARD) {
        return Ok(RFB_SEC_APPLE_ARD);
    }
    if offered.contains(&RFB_SEC_VNC_AUTH) {
        return Ok(RFB_SEC_VNC_AUTH);
    }
    Err(VncError::AuthFailed(format!(
        "Server offered unsupported security types: {offered:?}"
    )))
}

async fn read_rfb_reason_string<R: AsyncRead + Unpin>(reader: &mut R) -> Result<String, VncError> {
    let len = (reader.read_u32().await? as usize).min(8_192);
    let mut buf = vec![0u8; len];
    reader.read_exact(&mut buf).await?;
    Ok(String::from_utf8_lossy(&buf).trim().to_string())
}

async fn verify_security_result<R: AsyncRead + Unpin>(
    reader: &mut R,
    negotiated_minor: u16,
) -> Result<(), VncError> {
    let status = reader.read_u32().await?;
    if status == 0 {
        return Ok(());
    }
    let reason = if negotiated_minor >= 8 {
        read_rfb_reason_string(reader)
            .await
            .unwrap_or_else(|_| String::from("RFB authentication rejected"))
    } else {
        String::from("RFB authentication rejected")
    };
    Err(VncError::AuthFailed(reason))
}

fn set_shared_error(slot: &Arc<Mutex<String>>, msg: String) {
    if let Ok(mut guard) = slot.lock() {
        if guard.is_empty() {
            *guard = msg;
        }
    }
}

async fn run_rfb_reader_loop<R: AsyncRead + Unpin>(
    reader: &mut R,
    framebuffer: &Arc<Mutex<RfbFramebuffer>>,
    frame_sequence: &Arc<AtomicU64>,
    remote_clipboard: &Arc<Mutex<Option<String>>>,
    outbound_tx: &mpsc::UnboundedSender<Vec<u8>>,
) -> Result<(), VncError> {
    loop {
        let msg_type = reader.read_u8().await?;
        match msg_type {
            0 => {
                // FramebufferUpdate: [padding: u8, num_rects: u16 BE]
                let _pad = reader.read_u8().await?;
                let num_rects = reader.read_u16().await?;

                for _ in 0..num_rects {
                    let x = reader.read_u16().await?;
                    let y = reader.read_u16().await?;
                    let w = reader.read_u16().await?;
                    let h = reader.read_u16().await?;
                    let encoding = reader.read_i32().await?;

                    match encoding {
                        RFB_PSEUDO_DESKTOP_SIZE => {
                            if let Ok(mut fb) = framebuffer.lock() {
                                fb.resize(u32::from(w.max(1)), u32::from(h.max(1)));
                            }
                        }
                        RFB_ENCODING_RAW => {
                            let bpp = framebuffer
                                .lock()
                                .map(|fb| fb.pixel_format.bytes_per_pixel())
                                .unwrap_or(4);
                            let total = (w as usize) * (h as usize) * bpp;
                            let mut raw = vec![0u8; total];
                            reader.read_exact(&mut raw).await?;
                            if let Ok(mut fb) = framebuffer.lock() {
                                fb.apply_raw_rect(x, y, w, h, &raw)?;
                            }
                        }
                        RFB_ENCODING_COPYRECT => {
                            let src_x = reader.read_u16().await?;
                            let src_y = reader.read_u16().await?;
                            if let Ok(mut fb) = framebuffer.lock() {
                                fb.apply_copy_rect(x, y, w, h, src_x, src_y)?;
                            }
                        }
                        RFB_ENCODING_RRE => {
                            let bpp = framebuffer
                                .lock()
                                .map(|fb| fb.pixel_format.bytes_per_pixel())
                                .unwrap_or(4);
                            let num_subrects = reader.read_u32().await? as usize;
                            let body_len = bpp + num_subrects * (bpp + 8);
                            let mut payload = Vec::with_capacity(4 + body_len);
                            payload.extend_from_slice(&(num_subrects as u32).to_be_bytes());
                            payload.resize(4 + body_len, 0);
                            reader.read_exact(&mut payload[4..]).await?;
                            if let Ok(mut fb) = framebuffer.lock() {
                                fb.apply_rre_rect(x, y, w, h, &payload)?;
                            }
                        }
                        RFB_ENCODING_HEXTILE => {
                            stream_hextile_rect(reader, framebuffer, x, y, w, h).await?;
                        }
                        RFB_ENCODING_ZRLE => {
                            let zlib_len = reader.read_u32().await? as usize;
                            if zlib_len > 64 * 1024 * 1024 {
                                return Err(VncError::Protocol(format!(
                                    "ZRLE payload too large: {zlib_len}"
                                )));
                            }
                            let mut zbuf = vec![0u8; zlib_len];
                            reader.read_exact(&mut zbuf).await?;
                            if let Ok(mut fb) = framebuffer.lock() {
                                fb.apply_zrle_rect(x, y, w, h, &zbuf)?;
                            }
                        }
                        other => {
                            return Err(VncError::InvalidEncoding(format!(
                                "Server sent unrequested RFB encoding {other}"
                            )));
                        }
                    }
                }

                frame_sequence.fetch_add(1, Ordering::Release);
                let (cur_w, cur_h) = framebuffer
                    .lock()
                    .map(|fb| (fb.width as u16, fb.height as u16))
                    .unwrap_or((1, 1));
                let next_req = build_rfb_update_request(true, 0, 0, cur_w, cur_h);
                let _ = outbound_tx.send(next_req.to_vec());
            }
            1 => {
                // SetColourMapEntries: [padding: u8, first: u16, count: u16, RGB(u16*3)*count]
                let _pad = reader.read_u8().await?;
                let _first = reader.read_u16().await?;
                let count = reader.read_u16().await? as usize;
                let mut cmap = vec![0u8; count * 6];
                reader.read_exact(&mut cmap).await?;
            }
            2 => {
                // Bell: no payload
            }
            3 => {
                // ServerCutText: [padding: 3B, len: u32 BE, text(len)]
                let mut pad = [0u8; 3];
                reader.read_exact(&mut pad).await?;
                let len = (reader.read_u32().await? as usize).min(16 * 1024 * 1024);
                let mut cut_bytes = vec![0u8; len];
                reader.read_exact(&mut cut_bytes).await?;
                let text = String::from_utf8_lossy(&cut_bytes).into_owned();
                if let Ok(mut slot) = remote_clipboard.lock() {
                    *slot = Some(text);
                }
            }
            other => {
                return Err(VncError::Protocol(format!(
                    "Unknown RFB server-to-client message type {other}"
                )));
            }
        }
    }
}

async fn stream_hextile_rect<R: AsyncRead + Unpin>(
    reader: &mut R,
    framebuffer: &Arc<Mutex<RfbFramebuffer>>,
    x: u16,
    y: u16,
    w: u16,
    h: u16,
) -> Result<(), VncError> {
    let fmt = framebuffer
        .lock()
        .map(|fb| fb.pixel_format)
        .map_err(|_| VncError::Protocol("Poisoned framebuffer mutex".into()))?;
    let bpp = fmt.bytes_per_pixel();
    let mut bg = [0u8, 0, 0, 255];
    let mut fg = [255u8, 255, 255, 255];
    let mut px_buf = [0u8; 4];

    let mut ty = y;
    while ty < y + h {
        let th = 16.min((y + h) - ty);
        let mut tx = x;
        while tx < x + w {
            let tw = 16.min((x + w) - tx);
            let submask = reader.read_u8().await?;
            if submask & 0x01 != 0 {
                let raw_len = (tw as usize) * (th as usize) * bpp;
                let mut raw = vec![0u8; raw_len];
                reader.read_exact(&mut raw).await?;
                if let Ok(mut fb) = framebuffer.lock() {
                    fb.apply_raw_rect(tx, ty, tw, th, &raw)?;
                }
            } else {
                if submask & 0x02 != 0 {
                    reader.read_exact(&mut px_buf[..bpp]).await?;
                    bg = fmt.decode_pixel(&px_buf[..bpp])?.0;
                }
                if submask & 0x04 != 0 {
                    reader.read_exact(&mut px_buf[..bpp]).await?;
                    fg = fmt.decode_pixel(&px_buf[..bpp])?.0;
                }

                let mut subrects: Vec<(u16, u16, u16, u16, [u8; 4])> = Vec::new();
                if submask & 0x08 != 0 {
                    let count = reader.read_u8().await? as usize;
                    let colored = (submask & 0x10) != 0;
                    subrects.reserve(count);
                    for _ in 0..count {
                        let color = if colored {
                            reader.read_exact(&mut px_buf[..bpp]).await?;
                            fmt.decode_pixel(&px_buf[..bpp])?.0
                        } else {
                            fg
                        };
                        let xy = reader.read_u8().await?;
                        let wh = reader.read_u8().await?;
                        let sx = u16::from((xy >> 4) & 0x0F);
                        let sy = u16::from(xy & 0x0F);
                        let sw = u16::from(((wh >> 4) & 0x0F) + 1);
                        let sh = u16::from((wh & 0x0F) + 1);
                        subrects.push((sx, sy, sw, sh, color));
                    }
                }

                if let Ok(mut fb) = framebuffer.lock() {
                    fb.fill_solid_subrect(tx, ty, tw, th, bg);
                    for (sx, sy, sw, sh, color) in subrects {
                        let draw_w = sw.min(tw.saturating_sub(sx));
                        let draw_h = sh.min(th.saturating_sub(sy));
                        fb.fill_solid_subrect(tx + sx, ty + sy, draw_w, draw_h, color);
                    }
                }
            }
            tx += 16;
        }
        ty += 16;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use cipher::BlockDecrypt;
    use flate2::{Compress, Compression, FlushCompress};
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn live_rfb_3_8_vnc_des_continuous_zrle_copyrect_resize_and_clipboard() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();

        let server_task = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();

            // 1. ProtocolVersion 3.8
            sock.write_all(RFB_VERSION_3_8).await.unwrap();
            let mut client_ver = [0u8; 12];
            sock.read_exact(&mut client_ver).await.unwrap();
            assert_eq!(&client_ver, RFB_VERSION_3_8);

            // 2. Offer Security types [None=1, VNC Auth=2]; client has password so picks 2
            sock.write_all(&[2, RFB_SEC_NONE, RFB_SEC_VNC_AUTH])
                .await
                .unwrap();
            let chosen = sock.read_u8().await.unwrap();
            assert_eq!(chosen, RFB_SEC_VNC_AUTH);

            let challenge: [u8; 16] = *b"NexusTSRfb38Chal";
            sock.write_all(&challenge).await.unwrap();
            let mut resp = [0u8; 16];
            sock.read_exact(&mut resp).await.unwrap();
            let expected_resp = encrypt_vnc_des_challenge(&challenge, "v3ryS3cr3t");
            assert_eq!(resp, expected_resp);
            sock.write_u32(0).await.unwrap(); // SecurityResult = OK

            // 3. ClientInit & ServerInit (initial 4x4 desktop)
            let shared = sock.read_u8().await.unwrap();
            assert_eq!(shared, 1);

            let mut server_init = Vec::new();
            server_init.extend_from_slice(&4u16.to_be_bytes());
            server_init.extend_from_slice(&4u16.to_be_bytes());
            server_init.extend_from_slice(&[32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0]);
            let name = b"Loopback QEMU VNC";
            server_init.extend_from_slice(&(name.len() as u32).to_be_bytes());
            server_init.extend_from_slice(name);
            sock.write_all(&server_init).await.unwrap();

            // 4. Read SetPixelFormat (20B) + SetEncodings (4 + 6*4 = 28B) + FramebufferUpdateRequest (10B)
            let mut init_client_msgs = [0u8; 20 + 28 + 10];
            sock.read_exact(&mut init_client_msgs).await.unwrap();
            assert_eq!(init_client_msgs[0], 0); // SetPixelFormat
            assert_eq!(init_client_msgs[20], 2); // SetEncodings
            assert_eq!(init_client_msgs[48], 3); // FramebufferUpdateRequest

            // Shared continuous zlib compressor across Update #1 and Update #2!
            // Canonical pixel format has R shift 16, G shift 8, B shift 0 (LE) -> 3-byte CPIXEL is [B, G, R]!
            let mut zrle_deflate = Compress::new(Compression::default(), true);
            let deflate_chunk = |comp: &mut Compress, plain: &[u8]| -> Vec<u8> {
                let mut out = Vec::with_capacity(plain.len() + 64);
                comp.compress_vec(plain, &mut out, FlushCompress::Sync)
                    .unwrap();
                out
            };

            // Update #1:
            //   Rect 1: ZRLE Packed 2-color palette at (0,0,2,2):
            //     color0 CPIXEL [B=30, G=20, R=10], color1 CPIXEL [B=90, G=80, R=70]
            //     row0 = 0b0100_0000 (idx 0, idx 1), row1 = 0b1000_0000 (idx 1, idx 0)
            //   Rect 2: CopyRect from (0,0) -> (2,0,2,2)
            let z1 = deflate_chunk(&mut zrle_deflate, &[2u8, 30, 20, 10, 90, 80, 70, 0x40, 0x80]);
            let mut upd1 = Vec::new();
            upd1.extend_from_slice(&[0, 0]);
            upd1.extend_from_slice(&2u16.to_be_bytes());
            // Rect 1 (ZRLE)
            upd1.extend_from_slice(&0u16.to_be_bytes());
            upd1.extend_from_slice(&0u16.to_be_bytes());
            upd1.extend_from_slice(&2u16.to_be_bytes());
            upd1.extend_from_slice(&2u16.to_be_bytes());
            upd1.extend_from_slice(&RFB_ENCODING_ZRLE.to_be_bytes());
            upd1.extend_from_slice(&(z1.len() as u32).to_be_bytes());
            upd1.extend_from_slice(&z1);
            // Rect 2 (CopyRect)
            upd1.extend_from_slice(&2u16.to_be_bytes());
            upd1.extend_from_slice(&0u16.to_be_bytes());
            upd1.extend_from_slice(&2u16.to_be_bytes());
            upd1.extend_from_slice(&2u16.to_be_bytes());
            upd1.extend_from_slice(&RFB_ENCODING_COPYRECT.to_be_bytes());
            upd1.extend_from_slice(&0u16.to_be_bytes());
            upd1.extend_from_slice(&0u16.to_be_bytes());
            sock.write_all(&upd1).await.unwrap();

            // Wait for incremental FramebufferUpdateRequest #2
            let mut req2 = [0u8; 10];
            sock.read_exact(&mut req2).await.unwrap();
            assert_eq!(req2[0], 3);
            assert_eq!(req2[1], 1);

            // Update #2 (continues the SAME zlib dictionary!):
            //   Rect 1: DesktopSize resize to 6x4
            //   Rect 2: ZRLE Plain RLE (subencoding 128) at (4, 0, 2, 2) -> 4 pixels of CPIXEL [B=250, G=150, R=50] (run byte = 3)
            //   Rect 3: ZRLE Palette RLE (subencoding 130) at (0, 2, 2, 2) -> 4 pixels of idx 1 CPIXEL [B=12, G=34, R=56]
            let z2 = deflate_chunk(&mut zrle_deflate, &[128u8, 250, 150, 50, 3]);
            let z3 = deflate_chunk(
                &mut zrle_deflate,
                &[130u8, 0, 0, 0, 12, 34, 56, 0x81, 3],
            );
            let mut upd2 = Vec::new();
            upd2.extend_from_slice(&[0, 0]);
            upd2.extend_from_slice(&3u16.to_be_bytes());
            // DesktopSize 6x4
            upd2.extend_from_slice(&0u16.to_be_bytes());
            upd2.extend_from_slice(&0u16.to_be_bytes());
            upd2.extend_from_slice(&6u16.to_be_bytes());
            upd2.extend_from_slice(&4u16.to_be_bytes());
            upd2.extend_from_slice(&RFB_PSEUDO_DESKTOP_SIZE.to_be_bytes());
            // Plain RLE
            upd2.extend_from_slice(&4u16.to_be_bytes());
            upd2.extend_from_slice(&0u16.to_be_bytes());
            upd2.extend_from_slice(&2u16.to_be_bytes());
            upd2.extend_from_slice(&2u16.to_be_bytes());
            upd2.extend_from_slice(&RFB_ENCODING_ZRLE.to_be_bytes());
            upd2.extend_from_slice(&(z2.len() as u32).to_be_bytes());
            upd2.extend_from_slice(&z2);
            // Palette RLE
            upd2.extend_from_slice(&0u16.to_be_bytes());
            upd2.extend_from_slice(&2u16.to_be_bytes());
            upd2.extend_from_slice(&2u16.to_be_bytes());
            upd2.extend_from_slice(&2u16.to_be_bytes());
            upd2.extend_from_slice(&RFB_ENCODING_ZRLE.to_be_bytes());
            upd2.extend_from_slice(&(z3.len() as u32).to_be_bytes());
            upd2.extend_from_slice(&z3);
            // Also send ServerCutText clipboard message
            upd2.extend_from_slice(&[3, 0, 0, 0]);
            let clip = b"remote-vnc-clipboard";
            upd2.extend_from_slice(&(clip.len() as u32).to_be_bytes());
            upd2.extend_from_slice(clip);
            sock.write_all(&upd2).await.unwrap();

            // Read incremental FramebufferUpdateRequest #3 (10B) + PointerEvent (6B) + KeyEvent (8B)
            let mut req3 = [0u8; 10];
            sock.read_exact(&mut req3).await.unwrap();
            assert_eq!(&req3[0..2], &[3, 1]);

            let mut ptr_pkt = [0u8; 6];
            sock.read_exact(&mut ptr_pkt).await.unwrap();
            assert_eq!(ptr_pkt, [5, 1, 0, 3, 0, 2]);

            let mut key_pkt = [0u8; 8];
            sock.read_exact(&mut key_pkt).await.unwrap();
            assert_eq!(key_pkt, [4, 1, 0, 0, 0, 0, 0xFF, 0x0D]);
        });

        let client = ActiveVncClient::connect("127.0.0.1", port, "", "v3ryS3cr3t", true, false)
            .await
            .expect("VNC connect");
        assert_eq!(client.desktop_name(), "Loopback QEMU VNC");

        // Wait until both FramebufferUpdates (sequence >= 2) and clipboard arrive
        for _ in 0..80 {
            if client.frame_sequence() >= 2 && client.dimensions() == (6, 4) {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert_eq!(client.frame_sequence(), 2);
        assert_eq!(client.dimensions(), (6, 4));

        let mut rgba = vec![0u8; 6 * 4 * 4];
        let mut bgra = vec![0u8; 6 * 4 * 4];
        assert_eq!(client.copy_frame_rgba(&mut rgba), rgba.len());
        assert_eq!(client.copy_frame_bgra(&mut bgra), bgra.len());

        // Pixel (0, 0) had CPIXEL [B=30, G=20, R=10] -> RGBA=[10, 20, 30, 255], BGRA=[30, 20, 10, 255]
        assert_eq!(&rgba[0..4], &[10, 20, 30, 255]);
        assert_eq!(&bgra[0..4], &[30, 20, 10, 255]);
        // CopyRect copied (0, 0) -> (2, 0)
        assert_eq!(&rgba[(2) * 4..(2) * 4 + 4], &[10, 20, 30, 255]);
        // Plain RLE at (4, 0) had CPIXEL [B=250, G=150, R=50] -> RGBA=[50, 150, 250, 255]
        assert_eq!(&rgba[(4) * 4..(4) * 4 + 4], &[50, 150, 250, 255]);
        // Palette RLE at (0, 2) (row 2 * width 6 = pixel 12) -> RGBA=[56, 34, 12, 255]
        assert_eq!(&rgba[(12) * 4..(12) * 4 + 4], &[56, 34, 12, 255]);

        let mut got_clip = false;
        for _ in 0..30 {
            if let Some(clip) = client.pull_clipboard() {
                assert_eq!(clip, "remote-vnc-clipboard");
                got_clip = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(got_clip);

        assert!(client.send_pointer(1, 3, 2));
        assert!(client.send_key(true, 0xFF0D));
        server_task.await.unwrap();
        client.disconnect();
    }

    #[tokio::test]
    async fn live_apple_ard_type_30_dh_aes_handshake_and_view_only_suppression() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();

        // Standard 512-bit safe prime (RFC 2409 / Oakley-style 64-byte prime) for fast deterministic DH test
        let prime_hex = concat!(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1",
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD",
            "EF9519B3CD3A431B302B0A6DF25F1437"
        );
        let prime_bytes: Vec<u8> = (0..prime_hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&prime_hex[i..i + 2], 16).unwrap())
            .collect();
        assert_eq!(prime_bytes.len(), 64);

        let server_task = tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();

            // Real macOS Screen Sharing advertises "RFB 003.889\n"!
            sock.write_all(b"RFB 003.889\n").await.unwrap();
            let mut client_ver = [0u8; 12];
            sock.read_exact(&mut client_ver).await.unwrap();
            assert_eq!(&client_ver, RFB_VERSION_3_8);

            // Offer [ARD=30, VNC=2]
            sock.write_all(&[2, RFB_SEC_APPLE_ARD, RFB_SEC_VNC_AUTH])
                .await
                .unwrap();
            let chosen = sock.read_u8().await.unwrap();
            assert_eq!(chosen, RFB_SEC_APPLE_ARD);

            let p_big = BigUint::from_bytes_be(&prime_bytes);
            let g_big = BigUint::from(2u16);
            let server_priv = BigUint::from_bytes_be(&[0x55u8; 64]);
            let server_pub = pad_biguint_be(&g_big.modpow(&server_priv, &p_big), 64).unwrap();

            sock.write_u16(2).await.unwrap(); // generator = 2
            sock.write_u16(64).await.unwrap(); // key_len = 64
            sock.write_all(&prime_bytes).await.unwrap();
            sock.write_all(&server_pub).await.unwrap();

            let mut ciphertext = [0u8; 128];
            let mut client_pub_bytes = [0u8; 64];
            sock.read_exact(&mut ciphertext).await.unwrap();
            sock.read_exact(&mut client_pub_bytes).await.unwrap();

            let client_pub = BigUint::from_bytes_be(&client_pub_bytes);
            let shared = pad_biguint_be(&client_pub.modpow(&server_priv, &p_big), 64).unwrap();
            let aes_key: [u8; 16] = md5::compute(&shared).0;
            let cipher = Aes128::new_from_slice(&aes_key).unwrap();
            for block in ciphertext.chunks_exact_mut(16) {
                cipher.decrypt_block(GenericArray::from_mut_slice(block));
            }

            let nul_u = ciphertext[0..64].iter().position(|&b| b == 0).unwrap();
            let nul_p = ciphertext[64..128].iter().position(|&b| b == 0).unwrap();
            let dec_user = std::str::from_utf8(&ciphertext[0..nul_u]).unwrap();
            let dec_pass = std::str::from_utf8(&ciphertext[64..64 + nul_p]).unwrap();
            assert_eq!(dec_user, "macadmin");
            assert_eq!(dec_pass, "ard-pass-2026!");

            sock.write_u32(0).await.unwrap(); // SecurityResult = 0

            let _shared_init = sock.read_u8().await.unwrap();
            let mut server_init = Vec::new();
            server_init.extend_from_slice(&2u16.to_be_bytes());
            server_init.extend_from_slice(&2u16.to_be_bytes());
            server_init.extend_from_slice(&[32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0]);
            let name = b"Mac Studio ARD";
            server_init.extend_from_slice(&(name.len() as u32).to_be_bytes());
            server_init.extend_from_slice(name);
            sock.write_all(&server_init).await.unwrap();

            let mut init_msgs = [0u8; 58];
            sock.read_exact(&mut init_msgs).await.unwrap();
        });

        let client = ActiveVncClient::connect(
            "127.0.0.1",
            port,
            "macadmin",
            "ard-pass-2026!",
            true,
            true, // view_only = true!
        )
        .await
        .expect("Apple ARD Type 30 handshake");

        assert_eq!(client.desktop_name(), "Mac Studio ARD");
        assert_eq!(client.dimensions(), (2, 2));
        // View-only mode MUST suppress pointer and key events!
        assert!(!client.send_pointer(1, 1, 1));
        assert!(!client.send_key(true, 0x61));

        client.disconnect();
        server_task.await.unwrap();
    }
}
