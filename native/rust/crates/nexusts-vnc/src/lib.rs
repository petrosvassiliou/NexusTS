//! RFB 3.8 (UltraVNC, TightVNC, RealVNC) & Apple Screen Sharing Framebuffer Engine
//! supporting Raw, CopyRect, and ZRLE (Zlib Run-Length Encoding) into RGBA8888 surfaces.

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
    #[error("ZRLE zlib decompression failure: {0}")]
    ZlibError(String),
}

pub struct RfbFramebuffer {
    pub width: u32,
    pub height: u32,
    pub rgba_pixels: Vec<u8>,
    zrle_inflater: Decompress,
}

impl RfbFramebuffer {
    pub fn new(width: u32, height: u32) -> Self {
        let pixel_count = (width as usize) * (height as usize);
        Self {
            width,
            height,
            rgba_pixels: vec![0u8; pixel_count * 4],
            zrle_inflater: Decompress::new(true),
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
