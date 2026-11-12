//! RFB 3.8 Wire Handshake, Security Negotiation (VNC Auth & Apple Screen Sharing ARD Type 30),
//! Hextile Decoder, and Encoding Advertisement.

use crate::{
    RfbFramebuffer, VncError, RFB_ENCODING_COPYRECT, RFB_ENCODING_HEXTILE, RFB_ENCODING_RAW,
    RFB_ENCODING_TIGHT, RFB_ENCODING_ZRLE,
};

pub const RFB_VERSION_3_8: &[u8; 12] = b"RFB 003.008\n";
pub const RFB_SEC_NONE: u8 = 1;
pub const RFB_SEC_VNC_AUTH: u8 = 2;
pub const RFB_SEC_VENCRYPT: u8 = 19;
pub const RFB_SEC_APPLE_ARD: u8 = 30;

/// Builds the RFB 3.8 `SetPixelFormat` (msg 0) packet requesting 32-bpp true-color RGBA8888 little-endian.
pub fn build_rfb_rgba8888_pixel_format() -> [u8; 20] {
    [
        0, // message-type = SetPixelFormat
        0, 0, 0,  // padding
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

    let mut pkt = Vec::with_capacity(4 + encodings.len() * 4);
    pkt.push(2); // SetEncodings
    pkt.push(0); // padding
    pkt.extend_from_slice(&(encodings.len() as u16).to_be_bytes());
    for enc in encodings {
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

/// Decodes an RFB Hextile (Encoding 5) 16x16 tiled rectangle update into [`RfbFramebuffer`].
pub fn apply_hextile_rect(
    fb: &mut RfbFramebuffer,
    x: u16,
    y: u16,
    w: u16,
    h: u16,
    payload: &[u8],
) -> Result<(), VncError> {
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
                let bytes_needed = (tw as usize) * (th as usize) * 4;
                if cursor + bytes_needed > payload.len() {
                    return Err(VncError::TruncatedPayload);
                }
                fb.apply_raw_rect(tx, ty, tw, th, &payload[cursor..cursor + bytes_needed])?;
                cursor += bytes_needed;
            } else {
                if submask & 0x02 != 0 {
                    if cursor + 4 > payload.len() {
                        return Err(VncError::TruncatedPayload);
                    }
                    bg = [payload[cursor], payload[cursor + 1], payload[cursor + 2], 255];
                    cursor += 4;
                }
                if submask & 0x04 != 0 {
                    if cursor + 4 > payload.len() {
                        return Err(VncError::TruncatedPayload);
                    }
                    fg = [payload[cursor], payload[cursor + 1], payload[cursor + 2], 255];
                    cursor += 4;
                }

                // Fill background of 16x16 tile
                let mut tile_buf = vec![0u8; (tw as usize) * (th as usize) * 4];
                for px in tile_buf.chunks_exact_mut(4) {
                    px.copy_from_slice(&bg);
                }

                if submask & 0x08 != 0 {
                    if cursor >= payload.len() {
                        return Err(VncError::TruncatedPayload);
                    }
                    let num_subrects = payload[cursor] as usize;
                    cursor += 1;
                    let subrects_colored = (submask & 0x10) != 0;

                    for _ in 0..num_subrects {
                        let color = if subrects_colored {
                            if cursor + 4 > payload.len() {
                                return Err(VncError::TruncatedPayload);
                            }
                            let c = [payload[cursor], payload[cursor + 1], payload[cursor + 2], 255];
                            cursor += 4;
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
                        let sx = ((xy >> 4) & 0x0F) as usize;
                        let sy = (xy & 0x0F) as usize;
                        let sw = (((wh >> 4) & 0x0F) + 1) as usize;
                        let sh = ((wh & 0x0F) + 1) as usize;

                        for r in sy..(sy + sh).min(th as usize) {
                            for c in sx..(sx + sw).min(tw as usize) {
                                let off = (r * (tw as usize) + c) * 4;
                                tile_buf[off..off + 4].copy_from_slice(&color);
                            }
                        }
                    }
                }
                fb.apply_raw_rect(tx, ty, tw, th, &tile_buf)?;
            }

            tx += 16;
        }
        ty += 16;
    }

    Ok(())
}
