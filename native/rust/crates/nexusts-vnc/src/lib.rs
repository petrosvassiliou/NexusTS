//! RFB 3.3 / 3.7 / 3.8 (UltraVNC, TightVNC, RealVNC) & Apple Screen Sharing (ARD Type 30)
//! Framebuffer Engine supporting Raw, CopyRect, RRE, Hextile, Tight, and full ZRLE
//! (continuous zlib stream + Raw, Solid, Packed Palette 1/2/4-bit, Plain RLE, Palette RLE)
//! with fast RGBA8888 (Android) and BGRA8888 (macOS CoreGraphics) output helpers.

pub mod rfb_client;

use flate2::{Decompress, FlushDecompress, Status};
use thiserror::Error;

pub const RFB_ENCODING_RAW: i32 = 0;
pub const RFB_ENCODING_COPYRECT: i32 = 1;
pub const RFB_ENCODING_RRE: i32 = 2;
pub const RFB_ENCODING_HEXTILE: i32 = 5;
pub const RFB_ENCODING_TIGHT: i32 = 7;
pub const RFB_ENCODING_ZRLE: i32 = 16;
pub const RFB_PSEUDO_DESKTOP_SIZE: i32 = -223;

#[derive(Debug, Error)]
pub enum VncError {
    #[error("RFB rectangle out of framebuffer bounds")]
    OutOfBounds,
    #[error("Truncated RFB rectangle payload")]
    TruncatedPayload,
    #[error("ZRLE/Tight zlib decompression failure: {0}")]
    ZlibError(String),
    #[error("Invalid RFB/ZRLE subencoding: {0}")]
    InvalidEncoding(String),
    #[error("RFB protocol error: {0}")]
    Protocol(String),
    #[error("RFB authentication failed: {0}")]
    AuthFailed(String),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
}

/// Wire pixel format negotiated via `ServerInit` or overridden via `SetPixelFormat`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RfbPixelFormat {
    pub bits_per_pixel: u8,
    pub depth: u8,
    pub big_endian: bool,
    pub true_color: bool,
    pub red_max: u16,
    pub green_max: u16,
    pub blue_max: u16,
    pub red_shift: u8,
    pub green_shift: u8,
    pub blue_shift: u8,
}

impl RfbPixelFormat {
    /// Standalone RGBA8888 little-endian layout (`[R, G, B, A]` in memory; `R` at shift 0).
    pub const fn rgba8888_le() -> Self {
        Self {
            bits_per_pixel: 32,
            depth: 24,
            big_endian: false,
            true_color: true,
            red_max: 255,
            green_max: 255,
            blue_max: 255,
            red_shift: 0,
            green_shift: 8,
            blue_shift: 16,
        }
    }

    /// Canonical RFB wire format (`32bpp, depth 24, little-endian, R shift 16, G shift 8, B shift 0`).
    /// Wire 4-byte `PIXEL` is `[B, G, R, X]` and 3-byte `CPIXEL` is `[B, G, R]`.
    pub const fn canonical_bgra8888_le() -> Self {
        Self {
            bits_per_pixel: 32,
            depth: 24,
            big_endian: false,
            true_color: true,
            red_max: 255,
            green_max: 255,
            blue_max: 255,
            red_shift: 16,
            green_shift: 8,
            blue_shift: 0,
        }
    }

    pub fn bytes_per_pixel(&self) -> usize {
        ((self.bits_per_pixel as usize) + 7) / 8
    }

    /// Returns `(cpixel_byte_len, fits_low_3_bytes)` per RFC 6143 §7.7.6.
    pub fn zrle_cpixel_layout(&self) -> (usize, Option<bool>) {
        if self.true_color && self.bits_per_pixel == 32 && self.depth <= 24 {
            let mask = ((self.red_max as u32) << self.red_shift)
                | ((self.green_max as u32) << self.green_shift)
                | ((self.blue_max as u32) << self.blue_shift);
            if (mask & 0xFF00_0000) == 0 {
                return (3, Some(true));
            }
            if (mask & 0x0000_00FF) == 0 {
                return (3, Some(false));
            }
        }
        (self.bytes_per_pixel(), None)
    }

    #[inline]
    pub fn unpack_u32_to_rgba(&self, val: u32) -> [u8; 4] {
        let scale = |raw: u32, max: u16| -> u8 {
            if max == 0 {
                0
            } else if max == 255 {
                (raw & 0xFF) as u8
            } else {
                (((raw & u32::from(max)) * 255 + u32::from(max / 2)) / u32::from(max)) as u8
            }
        };
        let r = scale(val >> self.red_shift, self.red_max);
        let g = scale(val >> self.green_shift, self.green_max);
        let b = scale(val >> self.blue_shift, self.blue_max);
        [r, g, b, 0xFF]
    }

    #[inline]
    pub fn decode_pixel(&self, buf: &[u8]) -> Result<([u8; 4], usize), VncError> {
        let bpp = self.bytes_per_pixel();
        if buf.len() < bpp {
            return Err(VncError::TruncatedPayload);
        }
        let val = match (bpp, self.big_endian) {
            (4, false) => u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]),
            (4, true) => u32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]),
            (2, false) => u32::from(u16::from_le_bytes([buf[0], buf[1]])),
            (2, true) => u32::from(u16::from_be_bytes([buf[0], buf[1]])),
            (1, _) => u32::from(buf[0]),
            _ => return Err(VncError::InvalidEncoding("Unsupported bpp".into())),
        };
        Ok((self.unpack_u32_to_rgba(val), bpp))
    }

    #[inline]
    pub fn decode_zrle_cpixel(&self, buf: &[u8]) -> Result<([u8; 4], usize), VncError> {
        let (len, low_sig) = self.zrle_cpixel_layout();
        if buf.len() < len {
            return Err(VncError::TruncatedPayload);
        }
        if let Some(fits_low) = low_sig {
            let val = match (self.big_endian, fits_low) {
                (false, true) => u32::from_le_bytes([buf[0], buf[1], buf[2], 0]),
                (false, false) => u32::from_le_bytes([0, buf[0], buf[1], buf[2]]),
                (true, true) => u32::from_be_bytes([0, buf[0], buf[1], buf[2]]),
                (true, false) => u32::from_be_bytes([buf[0], buf[1], buf[2], 0]),
            };
            Ok((self.unpack_u32_to_rgba(val), 3))
        } else {
            self.decode_pixel(buf)
        }
    }
}

pub struct RfbFramebuffer {
    pub width: u32,
    pub height: u32,
    /// Internal canonical pixel storage: `[R, G, B, 0xFF]` per pixel.
    pub rgba_pixels: Vec<u8>,
    pub pixel_format: RfbPixelFormat,
    zrle_inflater: Decompress,
    tight_inflaters: [Decompress; 4],
}

impl RfbFramebuffer {
    pub fn new(width: u32, height: u32) -> Self {
        Self::new_with_pixel_format(width, height, RfbPixelFormat::rgba8888_le())
    }

    pub fn new_canonical_bgra(width: u32, height: u32) -> Self {
        Self::new_with_pixel_format(width, height, RfbPixelFormat::canonical_bgra8888_le())
    }

    pub fn new_with_pixel_format(width: u32, height: u32, pixel_format: RfbPixelFormat) -> Self {
        let pixel_count = (width as usize) * (height as usize);
        let mut rgba_pixels = vec![0u8; pixel_count * 4];
        for px in rgba_pixels.chunks_exact_mut(4) {
            px[3] = 0xFF;
        }
        Self {
            width,
            height,
            rgba_pixels,
            pixel_format,
            zrle_inflater: Decompress::new(true),
            tight_inflaters: [
                Decompress::new(true),
                Decompress::new(true),
                Decompress::new(true),
                Decompress::new(true),
            ],
        }
    }

    pub fn set_pixel_format(&mut self, pixel_format: RfbPixelFormat) {
        self.pixel_format = pixel_format;
    }

    /// Resizes the framebuffer while preserving the continuous ZRLE/Tight zlib inflate streams!
    pub fn resize(&mut self, width: u32, height: u32) {
        self.width = width;
        self.height = height;
        let new_len = (width as usize) * (height as usize) * 4;
        let old_len = self.rgba_pixels.len();
        self.rgba_pixels.resize(new_len, 0);
        if new_len > old_len {
            for px in self.rgba_pixels[old_len..].chunks_exact_mut(4) {
                px[3] = 0xFF;
            }
        }
    }

    /// Fast copy of `[R, G, B, 0xFF]` pixels (ready for Android `Bitmap.Config.ARGB_8888` copyPixelsFromBuffer).
    pub fn copy_rgba(&self, dst: &mut [u8]) -> usize {
        let n = self.rgba_pixels.len().min(dst.len());
        dst[..n].copy_from_slice(&self.rgba_pixels[..n]);
        n
    }

    /// Fast conversion to `[B, G, R, 0xFF]` pixels (ready for macOS `byteOrder32Little | noneSkipFirst`).
    pub fn copy_bgra(&self, dst: &mut [u8]) -> usize {
        let n = (self.rgba_pixels.len().min(dst.len())) & !3;
        for (src_px, dst_px) in self.rgba_pixels[..n]
            .chunks_exact(4)
            .zip(dst[..n].chunks_exact_mut(4))
        {
            dst_px[0] = src_px[2];
            dst_px[1] = src_px[1];
            dst_px[2] = src_px[0];
            dst_px[3] = 0xFF;
        }
        n
    }

    pub fn to_bgra_vec(&self) -> Vec<u8> {
        let mut out = vec![0u8; self.rgba_pixels.len()];
        self.copy_bgra(&mut out);
        out
    }

    /// Applies an RFB Raw rectangle update using the active `pixel_format`.
    pub fn apply_raw_rect(
        &mut self,
        x: u16,
        y: u16,
        w: u16,
        h: u16,
        src_wire: &[u8],
    ) -> Result<(), VncError> {
        let (x, y, w, h) = (x as usize, y as usize, w as usize, h as usize);
        if x + w > self.width as usize || y + h > self.height as usize {
            return Err(VncError::OutOfBounds);
        }
        let bpp = self.pixel_format.bytes_per_pixel();
        if src_wire.len() < w * h * bpp {
            return Err(VncError::TruncatedPayload);
        }

        let stride = (self.width as usize) * 4;
        if self.pixel_format == RfbPixelFormat::rgba8888_le() {
            for row in 0..h {
                let dst_start = (y + row) * stride + x * 4;
                let src_start = row * w * 4;
                self.rgba_pixels[dst_start..dst_start + w * 4]
                    .copy_from_slice(&src_wire[src_start..src_start + w * 4]);
                for px in self.rgba_pixels[dst_start..dst_start + w * 4].chunks_exact_mut(4) {
                    px[3] = 0xFF;
                }
            }
        } else if self.pixel_format == RfbPixelFormat::canonical_bgra8888_le() {
            for row in 0..h {
                let dst_start = (y + row) * stride + x * 4;
                let src_start = row * w * 4;
                for (s, d) in src_wire[src_start..src_start + w * 4]
                    .chunks_exact(4)
                    .zip(self.rgba_pixels[dst_start..dst_start + w * 4].chunks_exact_mut(4))
                {
                    d[0] = s[2];
                    d[1] = s[1];
                    d[2] = s[0];
                    d[3] = 0xFF;
                }
            }
        } else {
            let fmt = self.pixel_format;
            let mut cursor = 0usize;
            for row in 0..h {
                for col in 0..w {
                    let (rgba, step) = fmt.decode_pixel(&src_wire[cursor..])?;
                    cursor += step;
                    self.put_pixel((x + col) as u16, (y + row) as u16, rgba);
                }
            }
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

    /// Applies an RFB RRE (Encoding 2: Rise-and-Run-length Encoding) rectangle update.
    pub fn apply_rre_rect(
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
        if payload.len() < 4 {
            return Err(VncError::TruncatedPayload);
        }
        let num_subrects =
            u32::from_be_bytes([payload[0], payload[1], payload[2], payload[3]]) as usize;
        let fmt = self.pixel_format;
        let (bg, bpp) = fmt.decode_pixel(&payload[4..])?;
        let mut cursor = 4 + bpp;

        self.fill_solid_subrect(x, y, w, h, bg);

        for _ in 0..num_subrects {
            let (fg, step) = fmt.decode_pixel(&payload[cursor..])?;
            cursor += step;
            if cursor + 8 > payload.len() {
                return Err(VncError::TruncatedPayload);
            }
            let sx = u16::from_be_bytes([payload[cursor], payload[cursor + 1]]);
            let sy = u16::from_be_bytes([payload[cursor + 2], payload[cursor + 3]]);
            let sw = u16::from_be_bytes([payload[cursor + 4], payload[cursor + 5]]);
            let sh = u16::from_be_bytes([payload[cursor + 6], payload[cursor + 7]]);
            cursor += 8;

            let draw_w = sw.min(w.saturating_sub(sx));
            let draw_h = sh.min(h.saturating_sub(sy));
            self.fill_solid_subrect(x + sx, y + sy, draw_w, draw_h, fg);
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
            self.fill_solid_subrect(x, y, w, h, rgba);
            return Ok(());
        }

        // BasicCompression: stream_id in bits 4..5, optional filter_id in bit 6
        let stream_id = (sub_type & 0x03) as usize;
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
                inflate_stream_chunk(
                    &mut self.tight_inflaters[stream_id],
                    &payload[cursor..cursor + comp_len],
                    raw_bytes_expected,
                )?
            };

            if rgb_slice.len() < raw_bytes_expected {
                return Err(VncError::TruncatedPayload);
            }
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

    /// Decodes an RFB ZRLE (Encoding 16, RFC 6143 §7.7.6) zlib-compressed 64x64 tiled rectangle
    /// over the persistent session `zrle_inflater`, supporting ALL ZRLE subencodings:
    /// - `0`: Raw (`CPIXEL` / `PIXEL`)
    /// - `1`: Solid single-color tile
    /// - `2..=16`: Packed Palette (1-bit, 2-bit, or 4-bit MSB-first row-aligned indices)
    /// - `128`: Plain RLE
    /// - `130..=255`: Palette RLE
    pub fn apply_zrle_rect(
        &mut self,
        x: u16,
        y: u16,
        w: u16,
        h: u16,
        zlib_stream: &[u8],
    ) -> Result<(), VncError> {
        if (x as usize) + (w as usize) > self.width as usize
            || (y as usize) + (h as usize) > self.height as usize
        {
            return Err(VncError::OutOfBounds);
        }
        if w == 0 || h == 0 {
            return Ok(());
        }

        let estimated = ((w as usize) * (h as usize) * 4).max(256);
        let data = inflate_stream_chunk(&mut self.zrle_inflater, zlib_stream, estimated)?;
        let fmt = self.pixel_format;

        let mut cursor = 0usize;
        let mut ty = y;
        while ty < y + h {
            let th = 64.min((y + h) - ty);
            let mut tx = x;
            while tx < x + w {
                let tw = 64.min((x + w) - tx);
                let tile_pixels = (tw as usize) * (th as usize);
                if cursor >= data.len() {
                    return Err(VncError::TruncatedPayload);
                }
                let subencoding = data[cursor];
                cursor += 1;

                match subencoding {
                    0 => {
                        // Raw tile (tw * th CPIXELs)
                        for row in 0..th {
                            for col in 0..tw {
                                let (rgba, step) = fmt.decode_zrle_cpixel(&data[cursor..])?;
                                cursor += step;
                                self.put_pixel(tx + col, ty + row, rgba);
                            }
                        }
                    }
                    1 => {
                        // Solid single-color tile
                        let (rgba, step) = fmt.decode_zrle_cpixel(&data[cursor..])?;
                        cursor += step;
                        self.fill_solid_subrect(tx, ty, tw, th, rgba);
                    }
                    2..=16 => {
                        // Packed Palette (1, 2, or 4 bits per pixel, row-aligned to byte boundary)
                        let palette_size = subencoding as usize;
                        let mut palette = [[0u8; 4]; 16];
                        for item in palette.iter_mut().take(palette_size) {
                            let (rgba, step) = fmt.decode_zrle_cpixel(&data[cursor..])?;
                            cursor += step;
                            *item = rgba;
                        }

                        let bppp: usize = if palette_size == 2 {
                            1
                        } else if palette_size <= 4 {
                            2
                        } else {
                            4
                        };
                        let row_bytes = ((tw as usize) * bppp + 7) / 8;

                        for row in 0..th {
                            if cursor + row_bytes > data.len() {
                                return Err(VncError::TruncatedPayload);
                            }
                            let row_slice = &data[cursor..cursor + row_bytes];
                            cursor += row_bytes;

                            for col in 0..(tw as usize) {
                                let idx = match bppp {
                                    1 => ((row_slice[col >> 3] >> (7 - (col & 7))) & 0x01) as usize,
                                    2 => {
                                        ((row_slice[col >> 2] >> (6 - ((col & 3) * 2))) & 0x03)
                                            as usize
                                    }
                                    _ => {
                                        ((row_slice[col >> 1] >> (4 - ((col & 1) * 4))) & 0x0F)
                                            as usize
                                    }
                                };
                                if idx >= palette_size {
                                    return Err(VncError::InvalidEncoding(format!(
                                        "ZRLE packed palette index {idx} >= {palette_size}"
                                    )));
                                }
                                self.put_pixel(tx + (col as u16), ty + row, palette[idx]);
                            }
                        }
                    }
                    128 => {
                        // Plain RLE
                        let mut drawn = 0usize;
                        while drawn < tile_pixels {
                            let (rgba, step) = fmt.decode_zrle_cpixel(&data[cursor..])?;
                            cursor += step;
                            let mut run_len = 1usize;
                            loop {
                                if cursor >= data.len() {
                                    return Err(VncError::TruncatedPayload);
                                }
                                let b = data[cursor];
                                cursor += 1;
                                run_len += b as usize;
                                if b != 255 {
                                    break;
                                }
                            }
                            let end = (drawn + run_len).min(tile_pixels);
                            while drawn < end {
                                let r = (drawn / (tw as usize)) as u16;
                                let c = (drawn % (tw as usize)) as u16;
                                self.put_pixel(tx + c, ty + r, rgba);
                                drawn += 1;
                            }
                        }
                    }
                    130..=255 => {
                        // Palette RLE
                        let palette_size = (subencoding - 128) as usize;
                        let mut palette = Vec::with_capacity(palette_size);
                        for _ in 0..palette_size {
                            let (rgba, step) = fmt.decode_zrle_cpixel(&data[cursor..])?;
                            cursor += step;
                            palette.push(rgba);
                        }

                        let mut drawn = 0usize;
                        while drawn < tile_pixels {
                            if cursor >= data.len() {
                                return Err(VncError::TruncatedPayload);
                            }
                            let index_byte = data[cursor];
                            cursor += 1;
                            let pal_idx = (index_byte & 0x7F) as usize;
                            if pal_idx >= palette_size {
                                return Err(VncError::InvalidEncoding(format!(
                                    "ZRLE palette RLE index {pal_idx} >= {palette_size}"
                                )));
                            }
                            let mut run_len = 1usize;
                            if (index_byte & 0x80) != 0 {
                                loop {
                                    if cursor >= data.len() {
                                        return Err(VncError::TruncatedPayload);
                                    }
                                    let b = data[cursor];
                                    cursor += 1;
                                    run_len += b as usize;
                                    if b != 255 {
                                        break;
                                    }
                                }
                            }
                            let color = palette[pal_idx];
                            let end = (drawn + run_len).min(tile_pixels);
                            while drawn < end {
                                let r = (drawn / (tw as usize)) as u16;
                                let c = (drawn % (tw as usize)) as u16;
                                self.put_pixel(tx + c, ty + r, color);
                                drawn += 1;
                            }
                        }
                    }
                    other => {
                        return Err(VncError::InvalidEncoding(format!(
                            "Unsupported ZRLE subencoding {other}"
                        )));
                    }
                }
                tx += 64;
            }
            ty += 64;
        }
        Ok(())
    }

    #[inline]
    pub fn fill_solid_subrect(&mut self, x: u16, y: u16, w: u16, h: u16, rgba: [u8; 4]) {
        let stride = (self.width as usize) * 4;
        let (x, y, w, h) = (x as usize, y as usize, w as usize, h as usize);
        if x + w > self.width as usize || y + h > self.height as usize {
            return;
        }
        for row in 0..h {
            let row_off = (y + row) * stride + x * 4;
            for px in self.rgba_pixels[row_off..row_off + w * 4].chunks_exact_mut(4) {
                px.copy_from_slice(&rgba);
            }
        }
    }

    #[inline]
    pub fn put_pixel(&mut self, x: u16, y: u16, rgba: [u8; 4]) {
        let idx = ((y as usize) * (self.width as usize) + (x as usize)) * 4;
        if idx + 4 <= self.rgba_pixels.len() {
            self.rgba_pixels[idx..idx + 4].copy_from_slice(&rgba);
        }
    }
}

fn inflate_stream_chunk(
    inflater: &mut Decompress,
    mut compressed: &[u8],
    initial_cap: usize,
) -> Result<Vec<u8>, VncError> {
    let mut out = Vec::with_capacity(initial_cap.max(256));
    loop {
        if out.capacity() == out.len() {
            out.reserve(out.len().max(4096));
        }
        let before_in = inflater.total_in();
        let before_out = inflater.total_out();
        let status = inflater
            .decompress_vec(compressed, &mut out, FlushDecompress::Sync)
            .map_err(|e| VncError::ZlibError(e.to_string()))?;
        let consumed = (inflater.total_in() - before_in) as usize;
        let produced = (inflater.total_out() - before_out) as usize;
        compressed = &compressed[consumed..];

        if status == Status::StreamEnd || (compressed.is_empty() && produced == 0) {
            break;
        }
        if consumed == 0 && produced == 0 && status != Status::BufError {
            break;
        }
    }
    Ok(out)
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

/// Encodes an RFB KeyEvent (msg type 4).
pub fn build_rfb_key_event(down: bool, keysym: u32) -> [u8; 8] {
    let mut pkt = [0u8; 8];
    pkt[0] = 4;
    pkt[1] = u8::from(down);
    pkt[4..8].copy_from_slice(&keysym.to_be_bytes());
    pkt
}

/// Encodes an RFB PointerEvent (msg type 5).
pub fn build_rfb_pointer_event(button_mask: u8, x: u16, y: u16) -> [u8; 6] {
    let mut pkt = [0u8; 6];
    pkt[0] = 5;
    pkt[1] = button_mask;
    pkt[2..4].copy_from_slice(&x.to_be_bytes());
    pkt[4..6].copy_from_slice(&y.to_be_bytes());
    pkt
}

/// Encodes an RFB ClientCutText (msg type 6).
pub fn build_rfb_client_cut_text(text: &str) -> Vec<u8> {
    let bytes = text.as_bytes();
    let mut pkt = Vec::with_capacity(8 + bytes.len());
    pkt.extend_from_slice(&[6, 0, 0, 0]);
    pkt.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    pkt.extend_from_slice(bytes);
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

        // 5. ZRLE continuous stream across multiple rects:
        //    Rect A: solid single-color tile (subencoding = 1, RGB = 210, 110, 50) at (16, 16)
        //    Rect B: packed 2-color 1-bit palette (subencoding = 2) at (0, 16)
        //    Rect C: plain RLE (subencoding = 128) at (8, 16)
        //    Rect D: palette RLE (subencoding = 130) at (24, 16)
        let mut deflate = Compress::new(Compression::default(), true);
        let compress_sync = |deflate: &mut Compress, plain: &[u8]| -> Vec<u8> {
            let mut z = Vec::with_capacity(plain.len() + 64);
            deflate
                .compress_vec(plain, &mut z, FlushCompress::Sync)
                .unwrap();
            z
        };

        let z_a = compress_sync(&mut deflate, &[1u8, 210, 110, 50]);
        fb.apply_zrle_rect(16, 16, 8, 8, &z_a).unwrap();
        let zrle_off = (16 * 32 + 16) * 4;
        assert_eq!(&fb.rgba_pixels[zrle_off..zrle_off + 4], &[210, 110, 50, 255]);

        // 2x2 packed 1-bit palette: color0=[1,2,3], color1=[7,8,9]; row0=0b1000_0000 (1,0), row1=0b0100_0000 (0,1)
        let z_b = compress_sync(&mut deflate, &[2u8, 1, 2, 3, 7, 8, 9, 0x80, 0x40]);
        fb.apply_zrle_rect(0, 16, 2, 2, &z_b).unwrap();
        assert_eq!(&fb.rgba_pixels[(16 * 32) * 4..(16 * 32) * 4 + 4], &[7, 8, 9, 255]);
        assert_eq!(
            &fb.rgba_pixels[(16 * 32 + 1) * 4..(16 * 32 + 1) * 4 + 4],
            &[1, 2, 3, 255]
        );
        assert_eq!(
            &fb.rgba_pixels[(17 * 32 + 1) * 4..(17 * 32 + 1) * 4 + 4],
            &[7, 8, 9, 255]
        );

        // Plain RLE 2x2 (4 pixels): 3 pixels of [11,22,33] (len byte = 2 -> 1+2=3) + 1 pixel of [44,55,66] (len byte = 0 -> 1)
        let z_c = compress_sync(&mut deflate, &[128u8, 11, 22, 33, 2, 44, 55, 66, 0]);
        fb.apply_zrle_rect(8, 16, 2, 2, &z_c).unwrap();
        assert_eq!(
            &fb.rgba_pixels[(16 * 32 + 8) * 4..(16 * 32 + 8) * 4 + 4],
            &[11, 22, 33, 255]
        );
        assert_eq!(
            &fb.rgba_pixels[(17 * 32 + 9) * 4..(17 * 32 + 9) * 4 + 4],
            &[44, 55, 66, 255]
        );

        // Palette RLE 2x2 (subencoding = 130 -> 2 colors): run of 3 of idx 1 (0x80|1, len=2) + single of idx 0 (0x00)
        let z_d = compress_sync(
            &mut deflate,
            &[130u8, 100, 101, 102, 200, 201, 202, 0x81, 2, 0x00],
        );
        fb.apply_zrle_rect(24, 16, 2, 2, &z_d).unwrap();
        assert_eq!(
            &fb.rgba_pixels[(16 * 32 + 24) * 4..(16 * 32 + 24) * 4 + 4],
            &[200, 201, 202, 255]
        );
        assert_eq!(
            &fb.rgba_pixels[(17 * 32 + 25) * 4..(17 * 32 + 25) * 4 + 4],
            &[100, 101, 102, 255]
        );

        // Verify BGRA helper swizzles R and B channels cleanly
        let bgra = fb.to_bgra_vec();
        assert_eq!(&bgra[0..4], &[30, 20, 10, 255]);

        let enc_pkt = build_rfb_preferred_encodings(true);
        assert_eq!(enc_pkt[0], 2);
    }
}
