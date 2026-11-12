//! RFB 3.8 (UltraVNC, TightVNC, RealVNC) & Apple Screen Sharing Framebuffer Engine
//! supporting Raw, CopyRect, Hextile, Tight, and ZRLE (Zlib Run-Length Encoding) into RGBA8888 surfaces.

pub mod rfb_client;

use flate2::Decompress;
use flate2::FlushDecompress;
use thiserror::Error;

pub const RFB_ENCODING_RAW: i32 = 0;
pub const RFB_ENCODING_COPYRECT: i32 = 1;
pub const RFB_ENCODING_HEXTILE: i32 = 5;
pub const RFB_ENCODING_TIGHT: i32 = 7;
pub const RFB_ENCODING_ZRLE: i32 = 16;

#[derive(Debug, Error)]
pub enum VncError {
    #[error("RFB rectangle out of framebuffer bounds")]
    OutOfBounds,
    #[error("Truncated RFB rectangle payload")]
    TruncatedPayload,
    #[error("ZRLE/Tight zlib decompression failure: {0}")]
    ZlibError(String),
}

pub struct RfbFramebuffer {
    pub width: u32,
    pub height: u32,
    pub rgba_pixels: Vec<u8>,
    zrle_inflater: Decompress,
    tight_inflaters: [Decompress; 4],
}

impl RfbFramebuffer {
    pub fn new(width: u32, height: u32) -> Self {
        let pixel_count = (width as usize) * (height as usize);
        Self {
            width,
            height,
            rgba_pixels: vec![0u8; pixel_count * 4],
            zrle_inflater: Decompress::new(true),
            tight_inflaters: [
                Decompress::new(true),
                Decompress::new(true),
                Decompress::new(true),
                Decompress::new(true),
            ],
        }
    }

    pub fn resize(&mut self, width: u32, height: u32) {
        self.width = width;
        self.height = height;
        self.rgba_pixels
            .resize((width as usize) * (height as usize) * 4, 0);
    }

    /// Applies an RFB 3.8 Raw RGBA rectangle update.
    pub fn apply_raw_rect(
        &mut self,
        x: u16,
        y: u16,
        w: u16,
        h: u16,
        src_rgba: &[u8],
    ) -> Result<(), VncError> {
        let (x, y, w, h) = (x as usize, y as usize, w as usize, h as usize);
        if x + w > self.width as usize || y + h > self.height as usize {
            return Err(VncError::OutOfBounds);
        }
        if src_rgba.len() < w * h * 4 {
            return Err(VncError::TruncatedPayload);
        }
        let stride = (self.width as usize) * 4;
        for row in 0..h {
            let dst_start = (y + row) * stride + x * 4;
            let src_start = row * w * 4;
            self.rgba_pixels[dst_start..dst_start + w * 4]
                .copy_from_slice(&src_rgba[src_start..src_start + w * 4]);
        }
        Ok(())
    }

    /// Applies an RFB CopyRect blit within the framebuffer (fast window drag acceleration).
    pub fn apply_copy_rect(
        &mut self,
        dst_x: u16,
        dst_y: u16,
        w: u16,
        h: u16,
        src_x: u16,
        src_y: u16,
    ) -> Result<(), VncError> {
        let (dx, dy, sx, sy, w, h) = (
            dst_x as usize,
            dst_y as usize,
            src_x as usize,
            src_y as usize,
            w as usize,
            h as usize,
        );
        if dx + w > self.width as usize
            || dy + h > self.height as usize
            || sx + w > self.width as usize
            || sy + h > self.height as usize
        {
            return Err(VncError::OutOfBounds);
        }

        let stride = (self.width as usize) * 4;
        let mut scratch = vec![0u8; w * h * 4];
        for row in 0..h {
            let s_off = (sy + row) * stride + sx * 4;
            scratch[row * w * 4..(row + 1) * w * 4]
                .copy_from_slice(&self.rgba_pixels[s_off..s_off + w * 4]);
        }
        for row in 0..h {
            let d_off = (dy + row) * stride + dx * 4;
            self.rgba_pixels[d_off..d_off + w * 4]
                .copy_from_slice(&scratch[row * w * 4..(row + 1) * w * 4]);
        }
        Ok(())
    }

    /// Decodes an RFB Tight (Encoding 7) rectangle supporting FillCompression (`0x80`)
    /// and BasicCompression (`0x00..0x7F` with stream reset masks & TPIXEL 24-bit RGB).
    pub fn apply_tight_rect(
        &mut self,
        x: u16,
        y: u16,
        w: u16,
        h: u16,
        payload: &[u8],
    ) -> Result<(), VncError> {
        if (x as usize) + (w as usize) > self.width as usize
            || (y as usize) + (h as usize) > self.height as usize
        {
            return Err(VncError::OutOfBounds);
        }
        if payload.is_empty() {
            return Err(VncError::TruncatedPayload);
        }
        let comp_ctl = payload[0];
        for stream_id in 0..4 {
            if (comp_ctl & (1 << stream_id)) != 0 {
                self.tight_inflaters[stream_id] = Decompress::new(true);
            }
        }
        let sub_type = comp_ctl >> 4;
        if sub_type == 0x08 {
            // FillCompression: 3-byte TPIXEL RGB solid fill
            if payload.len() < 4 {
                return Err(VncError::TruncatedPayload);
            }
            let rgba = [payload[1], payload[2], payload[3], 255];
            for row in 0..h {
                for col in 0..w {
                    self.put_pixel(x + col, y + row, rgba);
                }
            }
            return Ok(());
        }

        // BasicCompression: stream_id in bits 4..5, optional filter_id in bit 6
        let stream_id = ((sub_type) & 0x03) as usize;
        let read_filter = (sub_type & 0x04) != 0;
        let mut cursor = 1usize;
        let mut filter_id = 0u8;
        if read_filter {
            if cursor >= payload.len() {
                return Err(VncError::TruncatedPayload);
            }
            filter_id = payload[cursor];
            cursor += 1;
        }

        if filter_id == 0 {
            // CopyFilter: 3 bytes per pixel; uncompressed if < 12 bytes, else compact-length + zlib
            let raw_bytes_expected = (w as usize) * (h as usize) * 3;
            let rgb_slice: Vec<u8> = if raw_bytes_expected < 12 {
                if cursor + raw_bytes_expected > payload.len() {
                    return Err(VncError::TruncatedPayload);
                }
                payload[cursor..cursor + raw_bytes_expected].to_vec()
            } else {
                let (comp_len, hdr_bytes) = decode_tight_compact_len(&payload[cursor..])?;
                cursor += hdr_bytes;
                if cursor + comp_len > payload.len() {
                    return Err(VncError::TruncatedPayload);
                }
                let mut out = vec![0u8; raw_bytes_expected + 64];
                let before = self.tight_inflaters[stream_id].total_out();
                self.tight_inflaters[stream_id]
                    .decompress(
                        &payload[cursor..cursor + comp_len],
                        &mut out,
                        FlushDecompress::Sync,
                    )
                    .map_err(|e| VncError::ZlibError(e.to_string()))?;
                let produced = (self.tight_inflaters[stream_id].total_out() - before) as usize;
                if produced < raw_bytes_expected {
                    return Err(VncError::TruncatedPayload);
                }
                out.truncate(raw_bytes_expected);
                out
            };

            let mut p = 0usize;
            for row in 0..h {
                for col in 0..w {
                    self.put_pixel(
                        x + col,
                        y + row,
                        [rgb_slice[p], rgb_slice[p + 1], rgb_slice[p + 2], 255],
                    );
                    p += 3;
                }
            }
        }
        Ok(())
    }

    /// Decodes an RFB ZRLE (Encoding 16) zlib-compressed 64x64 tiled rectangle.
    pub fn apply_zrle_rect(
        &mut self,
        x: u16,
        y: u16,
        w: u16,
        h: u16,
        zlib_stream: &[u8],
    ) -> Result<(), VncError> {
        let mut decompressed = vec![0u8; (w as usize) * (h as usize) * 5 + 256];
        let before_out = self.zrle_inflater.total_out();
        self.zrle_inflater
            .decompress(zlib_stream, &mut decompressed, FlushDecompress::Sync)
            .map_err(|e| VncError::ZlibError(e.to_string()))?;
        let produced = (self.zrle_inflater.total_out() - before_out) as usize;
        let data = &decompressed[..produced];

        let mut cursor = 0usize;
        let mut ty = y;
        while ty < y + h {
            let th = 64.min((y + h) - ty);
            let mut tx = x;
            while tx < x + w {
                let tw = 64.min((x + w) - tx);
                if cursor >= data.len() {
                    return Err(VncError::TruncatedPayload);
                }
                let subencoding = data[cursor];
                cursor += 1;
                match subencoding {
                    0 => {
                        // Raw 24-bit CPIXEL (3 bytes per pixel)
                        let needed = (tw as usize) * (th as usize) * 3;
                        if cursor + needed > data.len() {
                            return Err(VncError::TruncatedPayload);
                        }
                        for row in 0..th {
                            for col in 0..tw {
                                let r = data[cursor];
                                let g = data[cursor + 1];
                                let b = data[cursor + 2];
                                cursor += 3;
                                self.put_pixel(tx + col, ty + row, [r, g, b, 255]);
                            }
                        }
                    }
                    1 => {
                        // Solid single-color tile
                        if cursor + 3 > data.len() {
                            return Err(VncError::TruncatedPayload);
                        }
                        let rgba = [data[cursor], data[cursor + 1], data[cursor + 2], 255];
                        cursor += 3;
                        for row in 0..th {
                            for col in 0..tw {
                                self.put_pixel(tx + col, ty + row, rgba);
                            }
                        }
                    }
                    _ => break,
                }
                tx += 64;
            }
            ty += 64;
        }
        Ok(())
    }

    #[inline]
    fn put_pixel(&mut self, x: u16, y: u16, rgba: [u8; 4]) {
        let idx = ((y as usize) * (self.width as usize) + (x as usize)) * 4;
        if idx + 4 <= self.rgba_pixels.len() {
            self.rgba_pixels[idx..idx + 4].copy_from_slice(&rgba);
        }
    }
}

fn decode_tight_compact_len(buf: &[u8]) -> Result<(usize, usize), VncError> {
    if buf.is_empty() {
        return Err(VncError::TruncatedPayload);
    }
    let b0 = buf[0] as usize;
    let mut len = b0 & 0x7F;
    if (b0 & 0x80) == 0 {
        return Ok((len, 1));
    }
    if buf.len() < 2 {
        return Err(VncError::TruncatedPayload);
    }
    let b1 = buf[1] as usize;
    len |= (b1 & 0x7F) << 7;
    if (b1 & 0x80) == 0 {
        return Ok((len, 2));
    }
    if buf.len() < 3 {
        return Err(VncError::TruncatedPayload);
    }
    let b2 = buf[2] as usize;
    len |= b2 << 14;
    Ok((len, 3))
}

/// Encodes an RFB ClientCutText / KeyEvent (msg type 4) or PointerEvent (msg type 5).
pub fn build_rfb_key_event(down: bool, keysym: u32) -> [u8; 8] {
    let mut pkt = [0u8; 8];
    pkt[0] = 4;
    pkt[1] = u8::from(down);
    pkt[4..8].copy_from_slice(&keysym.to_be_bytes());
    pkt
}

pub fn build_rfb_pointer_event(button_mask: u8, x: u16, y: u16) -> [u8; 6] {
    let mut pkt = [0u8; 6];
    pkt[0] = 5;
    pkt[1] = button_mask;
    pkt[2..4].copy_from_slice(&x.to_be_bytes());
    pkt[4..6].copy_from_slice(&y.to_be_bytes());
    pkt
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rfb_client::{apply_hextile_rect, build_rfb_preferred_encodings};
    use flate2::{Compress, Compression, FlushCompress};

    #[test]
    fn rfb_raw_copyrect_tight_hextile_and_zrle_roundtrip() {
        let mut fb = RfbFramebuffer::new(32, 32);

        // 1. Raw 2x2 rect at (0, 0)
        let raw_px = [10u8, 20, 30, 255].repeat(4);
        fb.apply_raw_rect(0, 0, 2, 2, &raw_px).unwrap();
        assert_eq!(&fb.rgba_pixels[0..4], &[10, 20, 30, 255]);

        // 2. CopyRect 2x2 from (0, 0) -> (4, 4)
        fb.apply_copy_rect(4, 4, 2, 2, 0, 0).unwrap();
        let copy_off = (4 * 32 + 4) * 4;
        assert_eq!(&fb.rgba_pixels[copy_off..copy_off + 4], &[10, 20, 30, 255]);

        // 3. Tight FillCompression (sub_type = 0x08 -> comp_ctl = 0x80) at (8, 8)
        fb.apply_tight_rect(8, 8, 4, 4, &[0x80, 99, 150, 201]).unwrap();
        let tight_off = (8 * 32 + 8) * 4;
        assert_eq!(&fb.rgba_pixels[tight_off..tight_off + 4], &[99, 150, 201, 255]);

        // 4. Hextile background-specified (submask = 0x02) 4x4 tile at (12, 12)
        apply_hextile_rect(&mut fb, 12, 12, 4, 4, &[0x02, 44, 55, 66, 255]).unwrap();
        let hex_off = (12 * 32 + 12) * 4;
        assert_eq!(&fb.rgba_pixels[hex_off..hex_off + 4], &[44, 55, 66, 255]);

        // 5. ZRLE solid single-color tile (subencoding = 1, RGB = 210, 110, 50) at (16, 16)
        let zrle_plain = [1u8, 210, 110, 50];
        let mut deflate = Compress::new(Compression::default(), true);
        let mut zrle_z = vec![0u8; 64];
        deflate
            .compress(&zrle_plain, &mut zrle_z, FlushCompress::Sync)
            .unwrap();
        let z_len = deflate.total_out() as usize;
        fb.apply_zrle_rect(16, 16, 8, 8, &zrle_z[..z_len]).unwrap();
        let zrle_off = (16 * 32 + 16) * 4;
        assert_eq!(&fb.rgba_pixels[zrle_off..zrle_off + 4], &[210, 110, 50, 255]);

        let enc_pkt = build_rfb_preferred_encodings(true);
        assert_eq!(enc_pkt[0], 2);
    }
}
