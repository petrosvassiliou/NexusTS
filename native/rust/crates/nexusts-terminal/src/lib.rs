//! High-performance `xterm-256color` & 24-bit TrueColor VT100/VT520 Terminal Emulator,
//! SSHv2 (`russh`) Session & Port/X11/Bastion Multiplexer, Serial Port Framing, and Telnet (`IAC` NAWS) Engine.

pub mod ssh_transport;
pub mod telnet_transport;

use serde::{Deserialize, Serialize};
use std::collections::VecDeque;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct CellAttributes {
    pub fg_rgb: u32,
    pub bg_rgb: u32,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub inverse: bool,
}

impl Default for CellAttributes {
    fn default() -> Self {
        Self {
            fg_rgb: 0xFFE2E6F3,
            bg_rgb: 0xFF090D16,
            bold: false,
            italic: false,
            underline: false,
            inverse: false,
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct TerminalCell {
    pub ch: char,
    pub attrs: CellAttributes,
}

impl Default for TerminalCell {
    fn default() -> Self {
        Self {
            ch: ' ',
            attrs: CellAttributes::default(),
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct TerminalViewportSnapshot {
    pub cols: usize,
    pub rows: usize,
    pub cursor_col: usize,
    pub cursor_row: usize,
    pub cursor_visible: bool,
    pub bracketed_paste_mode: bool,
    #[serde(default)]
    pub application_cursor_keys: bool,
    pub title: String,
    pub visible_lines: Vec<String>,
    #[serde(default)]
    pub scrollback_lines: Vec<String>,
    pub packed_rgba_attrs: Vec<u64>,
}

pub type TerminalSnapshot = TerminalViewportSnapshot;

enum ParserState {
    Ground,
    Escape,
    CsiEntry,
    OscString,
}

/// Complete VT100/VT520 + `xterm-256color` + 24-bit TrueColor Terminal State Machine.
pub struct VtTerminalBuffer {
    cols: usize,
    rows: usize,
    primary_grid: Vec<Vec<TerminalCell>>,
    alt_grid: Vec<Vec<TerminalCell>>,
    using_alt_screen: bool,
    scrollback: VecDeque<Vec<TerminalCell>>,
    max_scrollback: usize,
    cursor_col: usize,
    cursor_row: usize,
    saved_cursor: (usize, usize),
    scroll_top: usize,
    scroll_bottom: usize,
    current_attrs: CellAttributes,
    cursor_visible: bool,
    bracketed_paste: bool,
    application_cursor_keys: bool,
    window_title: String,
    parser_state: ParserState,
    csi_buf: String,
    osc_buf: String,
    pending_utf8: Vec<u8>,
}

impl VtTerminalBuffer {
    pub fn new(cols: usize, rows: usize, max_scrollback: usize) -> Self {
        let cols = cols.max(10);
        let rows = rows.max(4);
        Self {
            cols,
            rows,
            primary_grid: vec![vec![TerminalCell::default(); cols]; rows],
            alt_grid: vec![vec![TerminalCell::default(); cols]; rows],
            using_alt_screen: false,
            scrollback: VecDeque::with_capacity(max_scrollback.min(2048)),
            max_scrollback,
            cursor_col: 0,
            cursor_row: 0,
            saved_cursor: (0, 0),
            scroll_top: 0,
            scroll_bottom: rows - 1,
            current_attrs: CellAttributes::default(),
            cursor_visible: true,
            bracketed_paste: false,
            application_cursor_keys: false,
            window_title: String::from("NexusTS Terminal"),
            parser_state: ParserState::Ground,
            csi_buf: String::with_capacity(32),
            osc_buf: String::with_capacity(64),
            pending_utf8: Vec::with_capacity(4),
        }
    }

    pub fn application_cursor_keys(&self) -> bool {
        self.application_cursor_keys
    }

    pub fn resize(&mut self, new_cols: usize, new_rows: usize) {
        let new_cols = new_cols.max(10);
        let new_rows = new_rows.max(4);
        Self::resize_grid(&mut self.primary_grid, new_cols, new_rows);
        Self::resize_grid(&mut self.alt_grid, new_cols, new_rows);
        self.cols = new_cols;
        self.rows = new_rows;
        self.scroll_top = 0;
        self.scroll_bottom = new_rows - 1;
        self.cursor_col = self.cursor_col.min(new_cols - 1);
        self.cursor_row = self.cursor_row.min(new_rows - 1);
    }

    fn resize_grid(grid: &mut Vec<Vec<TerminalCell>>, cols: usize, rows: usize) {
        grid.resize(rows, vec![TerminalCell::default(); cols]);
        for row in grid.iter_mut() {
            row.resize(cols, TerminalCell::default());
        }
    }

    pub fn feed_bytes(&mut self, bytes: &[u8]) {
        if bytes.is_empty() {
            return;
        }
        let combined_storage: Vec<u8>;
        let mut slice: &[u8] = if self.pending_utf8.is_empty() {
            bytes
        } else {
            let mut buf = std::mem::take(&mut self.pending_utf8);
            buf.extend_from_slice(bytes);
            combined_storage = buf;
            &combined_storage
        };

        loop {
            match std::str::from_utf8(slice) {
                Ok(valid) => {
                    for ch in valid.chars() {
                        self.advance_char(ch);
                    }
                    break;
                }
                Err(err) => {
                    let valid_up_to = err.valid_up_to();
                    if valid_up_to > 0 {
                        if let Ok(valid_prefix) = std::str::from_utf8(&slice[..valid_up_to]) {
                            for ch in valid_prefix.chars() {
                                self.advance_char(ch);
                            }
                        }
                    }
                    match err.error_len() {
                        None => {
                            // Trailing incomplete UTF-8 sequence split across read boundary
                            self.pending_utf8.extend_from_slice(&slice[valid_up_to..]);
                            break;
                        }
                        Some(invalid_len) => {
                            self.advance_char('\u{FFFD}');
                            slice = &slice[valid_up_to + invalid_len..];
                            if slice.is_empty() {
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    fn active_grid_mut(&mut self) -> &mut Vec<Vec<TerminalCell>> {
        if self.using_alt_screen {
            &mut self.alt_grid
        } else {
            &mut self.primary_grid
        }
    }

    fn active_grid(&self) -> &Vec<Vec<TerminalCell>> {
        if self.using_alt_screen {
            &self.alt_grid
        } else {
            &self.primary_grid
        }
    }

    fn advance_char(&mut self, ch: char) {
        match self.parser_state {
            ParserState::Ground => match ch {
                '\x1b' => self.parser_state = ParserState::Escape,
                '\r' => self.cursor_col = 0,
                '\n' => self.line_feed(),
                '\x08' => {
                    if self.cursor_col > 0 {
                        self.cursor_col -= 1;
                    }
                }
                '\t' => {
                    let next_tab = ((self.cursor_col / 8) + 1) * 8;
                    self.cursor_col = next_tab.min(self.cols - 1);
                }
                '\x07' => {} // BEL
                c if !c.is_control() => self.put_printable(c),
                _ => {}
            },
            ParserState::Escape => match ch {
                '[' => {
                    self.csi_buf.clear();
                    self.parser_state = ParserState::CsiEntry;
                }
                ']' => {
                    self.osc_buf.clear();
                    self.parser_state = ParserState::OscString;
                }
                '7' => {
                    self.saved_cursor = (self.cursor_col, self.cursor_row);
                    self.parser_state = ParserState::Ground;
                }
                '8' => {
                    self.cursor_col = self.saved_cursor.0.min(self.cols - 1);
                    self.cursor_row = self.saved_cursor.1.min(self.rows - 1);
                    self.parser_state = ParserState::Ground;
                }
                'c' => {
                    *self = Self::new(self.cols, self.rows, self.max_scrollback);
                }
                _ => self.parser_state = ParserState::Ground,
            },
            ParserState::OscString => {
                if ch == '\x07' || (ch == '\\' && self.osc_buf.ends_with('\x1b')) {
                    let clean = self.osc_buf.trim_end_matches('\x1b');
                    if let Some(rest) = clean.strip_prefix("0;").or_else(|| clean.strip_prefix("2;")) {
                        self.window_title = rest.to_string();
                    }
                    self.parser_state = ParserState::Ground;
                } else {
                    self.osc_buf.push(ch);
                }
            }
            ParserState::CsiEntry => {
                if ('@'..='~').contains(&ch) {
                    let params = self.csi_buf.clone();
                    self.dispatch_csi(&params, ch);
                    self.parser_state = ParserState::Ground;
                } else {
                    self.csi_buf.push(ch);
                }
            }
        }
    }

    fn put_printable(&mut self, ch: char) {
        if self.cursor_col >= self.cols {
            self.cursor_col = 0;
            self.line_feed();
        }
        let r = self.cursor_row;
        let c = self.cursor_col;
        let attrs = self.current_attrs;
        self.active_grid_mut()[r][c] = TerminalCell { ch, attrs };
        self.cursor_col += 1;
    }

    fn line_feed(&mut self) {
        if self.cursor_row >= self.scroll_bottom {
            self.scroll_up_region();
        } else if self.cursor_row + 1 < self.rows {
            self.cursor_row += 1;
        }
    }

    fn scroll_up_region(&mut self) {
        let top = self.scroll_top;
        let bottom = self.scroll_bottom.min(self.rows - 1);
        if top >= bottom {
            return;
        }
        if !self.using_alt_screen && top == 0 {
            let evicted = self.primary_grid.remove(0);
            if self.scrollback.len() >= self.max_scrollback {
                self.scrollback.pop_front();
            }
            self.scrollback.push_back(evicted);
            self.primary_grid
                .insert(bottom, vec![TerminalCell::default(); self.cols]);
        } else {
            let cols = self.cols;
            let grid = self.active_grid_mut();
            grid.remove(top);
            grid.insert(bottom, vec![TerminalCell::default(); cols]);
        }
    }

    fn dispatch_csi(&mut self, raw_params: &str, final_byte: char) {
        let private_mode = raw_params.starts_with('?');
        let cleaned = raw_params.trim_start_matches('?');
        let nums: Vec<usize> = if cleaned.is_empty() {
            vec![]
        } else {
            cleaned
                .split(';')
                .map(|s| s.parse::<usize>().unwrap_or(0))
                .collect()
        };
        let p0 = nums.first().copied().unwrap_or(0);
        let p1 = nums.get(1).copied().unwrap_or(0);

        match final_byte {
            'A' => self.cursor_row = self.cursor_row.saturating_sub(p0.max(1)),
            'B' => self.cursor_row = (self.cursor_row + p0.max(1)).min(self.rows - 1),
            'C' => self.cursor_col = (self.cursor_col + p0.max(1)).min(self.cols - 1),
            'D' => self.cursor_col = self.cursor_col.saturating_sub(p0.max(1)),
            'H' | 'f' => {
                self.cursor_row = p0.saturating_sub(1).min(self.rows - 1);
                self.cursor_col = p1.saturating_sub(1).min(self.cols - 1);
            }
            'J' => self.erase_display(p0),
            'K' => self.erase_line(p0),
            'r' => {
                let top = p0.max(1) - 1;
                let bottom = if p1 == 0 { self.rows - 1 } else { (p1 - 1).min(self.rows - 1) };
                if top < bottom {
                    self.scroll_top = top;
                    self.scroll_bottom = bottom;
                }
            }
            'h' | 'l' if private_mode => {
                let enable = final_byte == 'h';
                for code in nums {
                    match code {
                        1 => self.application_cursor_keys = enable,
                        25 => self.cursor_visible = enable,
                        1049 | 47 => {
                            if enable {
                                if code == 1049 && !self.using_alt_screen {
                                    self.saved_cursor = (self.cursor_col, self.cursor_row);
                                }
                                self.using_alt_screen = true;
                                let cols = self.cols;
                                let rows = self.rows;
                                self.alt_grid = vec![vec![TerminalCell::default(); cols]; rows];
                                self.cursor_col = 0;
                                self.cursor_row = 0;
                            } else {
                                let was_alt = self.using_alt_screen;
                                self.using_alt_screen = false;
                                if code == 1049 && was_alt {
                                    self.cursor_col = self.saved_cursor.0.min(self.cols - 1);
                                    self.cursor_row = self.saved_cursor.1.min(self.rows - 1);
                                }
                            }
                        }
                        2004 => self.bracketed_paste = enable,
                        _ => {}
                    }
                }
            }
            'm' => self.apply_sgr(&nums),
            _ => {}
        }
    }

    fn erase_display(&mut self, mode: usize) {
        let cols = self.cols;
        let rows = self.rows;
        let cr = self.cursor_row;
        let cc = self.cursor_col;
        let grid = self.active_grid_mut();
        match mode {
            0 => {
                for x in cc..cols {
                    grid[cr][x] = TerminalCell::default();
                }
                for row in grid.iter_mut().take(rows).skip(cr + 1) {
                    row.fill(TerminalCell::default());
                }
            }
            1 => {
                for row in grid.iter_mut().take(cr) {
                    row.fill(TerminalCell::default());
                }
                for x in 0..=cc.min(cols - 1) {
                    grid[cr][x] = TerminalCell::default();
                }
            }
            2 | 3 => {
                for row in grid.iter_mut() {
                    row.fill(TerminalCell::default());
                }
            }
            _ => {}
        }
    }

    fn erase_line(&mut self, mode: usize) {
        let cols = self.cols;
        let cr = self.cursor_row;
        let cc = self.cursor_col;
        let row = &mut self.active_grid_mut()[cr];
        match mode {
            0 => {
                for cell in row.iter_mut().take(cols).skip(cc) {
                    *cell = TerminalCell::default();
                }
            }
            1 => {
                for cell in row.iter_mut().take(cc.min(cols - 1) + 1) {
                    *cell = TerminalCell::default();
                }
            }
            2 => row.fill(TerminalCell::default()),
            _ => {}
        }
    }

    fn apply_sgr(&mut self, codes: &[usize]) {
        if codes.is_empty() {
            self.current_attrs = CellAttributes::default();
            return;
        }
        let mut idx = 0;
        while idx < codes.len() {
            match codes[idx] {
                0 => self.current_attrs = CellAttributes::default(),
                1 => self.current_attrs.bold = true,
                3 => self.current_attrs.italic = true,
                4 => self.current_attrs.underline = true,
                7 => self.current_attrs.inverse = true,
                22 => self.current_attrs.bold = false,
                23 => self.current_attrs.italic = false,
                24 => self.current_attrs.underline = false,
                27 => self.current_attrs.inverse = false,
                c @ 30..=37 => self.current_attrs.fg_rgb = xterm_palette_256(c - 30),
                39 => self.current_attrs.fg_rgb = CellAttributes::default().fg_rgb,
                c @ 40..=47 => self.current_attrs.bg_rgb = xterm_palette_256(c - 40),
                49 => self.current_attrs.bg_rgb = CellAttributes::default().bg_rgb,
                c @ 90..=97 => self.current_attrs.fg_rgb = xterm_palette_256((c - 90) + 8),
                c @ 100..=107 => self.current_attrs.bg_rgb = xterm_palette_256((c - 100) + 8),
                38 | 48 => {
                    let is_fg = codes[idx] == 38;
                    if idx + 2 < codes.len() && codes[idx + 1] == 5 {
                        let color = xterm_palette_256(codes[idx + 2] & 0xFF);
                        if is_fg {
                            self.current_attrs.fg_rgb = color;
                        } else {
                            self.current_attrs.bg_rgb = color;
                        }
                        idx += 2;
                    } else if idx + 4 < codes.len() && codes[idx + 1] == 2 {
                        let r = (codes[idx + 2] & 0xFF) as u32;
                        let g = (codes[idx + 3] & 0xFF) as u32;
                        let b = (codes[idx + 4] & 0xFF) as u32;
                        let argb = 0xFF00_0000 | (r << 16) | (g << 8) | b;
                        if is_fg {
                            self.current_attrs.fg_rgb = argb;
                        } else {
                            self.current_attrs.bg_rgb = argb;
                        }
                        idx += 4;
                    }
                }
                _ => {}
            }
            idx += 1;
        }
    }

    pub fn snapshot(&self) -> TerminalViewportSnapshot {
        let grid = self.active_grid();
        let mut visible_lines = Vec::with_capacity(self.rows);
        let mut packed_rgba_attrs = Vec::with_capacity(self.rows * self.cols);

        for row in grid {
            let mut s = String::with_capacity(self.cols);
            for cell in row {
                s.push(cell.ch);
                let flags = (u64::from(cell.attrs.bold))
                    | (u64::from(cell.attrs.italic) << 1)
                    | (u64::from(cell.attrs.underline) << 2)
                    | (u64::from(cell.attrs.inverse) << 3);
                let packed = ((cell.attrs.fg_rgb as u64) << 32)
                    | ((cell.attrs.bg_rgb as u64) & 0x00FF_FFFF) << 8
                    | flags;
                packed_rgba_attrs.push(packed);
            }
            visible_lines.push(s);
        }

        let scrollback_lines = if self.using_alt_screen {
            Vec::new()
        } else {
            self.scrollback
                .iter()
                .map(|row| row.iter().map(|cell| cell.ch).collect::<String>())
                .collect()
        };

        TerminalViewportSnapshot {
            cols: self.cols,
            rows: self.rows,
            cursor_col: self.cursor_col,
            cursor_row: self.cursor_row,
            cursor_visible: self.cursor_visible,
            bracketed_paste_mode: self.bracketed_paste,
            application_cursor_keys: self.application_cursor_keys,
            title: self.window_title.clone(),
            visible_lines,
            scrollback_lines,
            packed_rgba_attrs,
        }
    }
}

/// Standard `xterm-256color` palette lookup returning 0xAARRGGBB.
pub fn xterm_palette_256(idx: usize) -> u32 {
    const ANSI_16: [u32; 16] = [
        0xFF151A28, 0xFFFF6B6B, 0xFF3CE69D, 0xFFFFCA58, 0xFF6EA2FF, 0xFFC58FFF, 0xFF47E2C2,
        0xFFE2E6F3, 0xFF56607A, 0xFFFF8E8E, 0xFF69F5B8, 0xFFFFDC82, 0xFF94BAFF, 0xFFD9B3FF,
        0xFF7AF8DF, 0xFFFFFFFF,
    ];
    match idx {
        0..=15 => ANSI_16[idx],
        16..=231 => {
            let c = idx - 16;
            let r_i = (c / 36) % 6;
            let g_i = (c / 6) % 6;
            let b_i = c % 6;
            let map = |v: usize| if v == 0 { 0u32 } else { (v as u32 * 40) + 55 };
            0xFF00_0000 | (map(r_i) << 16) | (map(g_i) << 8) | map(b_i)
        }
        232..=255 => {
            let gray = ((idx - 232) as u32 * 10) + 8;
            0xFF00_0000 | (gray << 16) | (gray << 8) | gray
        }
        _ => 0xFFE2E6F3,
    }
}

/// Encodes Telnet RFC 1073 NAWS (Negotiate About Window Size) `IAC SB NAWS <w_hi> <w_lo> <h_hi> <h_lo> IAC SE`,
/// doubling any `0xFF` dimension byte per RFC 855.
pub fn build_telnet_naws_packet(cols: u16, rows: u16) -> Vec<u8> {
    let mut pkt = Vec::with_capacity(13);
    pkt.extend_from_slice(&[255, 250, 31]); // IAC SB OPT_NAWS
    let dims = [
        (cols >> 8) as u8,
        (cols & 0xFF) as u8,
        (rows >> 8) as u8,
        (rows & 0xFF) as u8,
    ];
    for b in dims {
        pkt.push(b);
        if b == 255 {
            pkt.push(255);
        }
    }
    pkt.extend_from_slice(&[255, 240]); // IAC SE
    pkt
}

/// Hardware Serial Port (USB-CDC / FTDI / CP210x / RS-232) framing parameters for Googlebook OS.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct SerialLineConfig {
    pub baud_rate: u32,
    pub data_bits: u8,
    pub parity: char, // 'N', 'E', 'O'
    pub stop_bits: u8,
    pub rts_cts_flow_control: bool,
}

impl Default for SerialLineConfig {
    fn default() -> Self {
        Self {
            baud_rate: 115_200,
            data_bits: 8,
            parity: 'N',
            stop_bits: 1,
            rts_cts_flow_control: false,
        }
    }
}

impl SerialLineConfig {
    /// Generates a POSIX `stty` raw line discipline configuration string (`115200 cs8 -parenb -cstopb`).
    pub fn to_posix_stty_args(&self, device_path: &str) -> String {
        let cs = match self.data_bits {
            5 => "cs5",
            6 => "cs6",
            7 => "cs7",
            _ => "cs8",
        };
        let parity_flag = match self.parity.to_ascii_uppercase() {
            'E' => "parenb -parodd",
            'O' => "parenb parodd",
            _ => "-parenb",
        };
        let stop_flag = if self.stop_bits == 2 { "cstopb" } else { "-cstopb" };
        let flow_flag = if self.rts_cts_flow_control { "crtscts" } else { "-crtscts" };
        format!(
            "stty -F {} {} {} {} {} {} raw -echo",
            device_path, self.baud_rate, cs, parity_flag, stop_flag, flow_flag
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn vt_buffer_renders_24bit_truecolor_and_alt_screen() {
        let mut vt = VtTerminalBuffer::new(40, 10, 500);
        vt.feed_bytes(b"\x1b]0;Prod-Bastion\x07\x1b[38;2;60;230;157mNEXUS\x1b[0m");
        let snap = vt.snapshot();
        assert_eq!(snap.title, "Prod-Bastion");
        assert!(snap.visible_lines[0].starts_with("NEXUS"));

        // Enter alternate screen (?1049h) and verify primary buffer is preserved upon exit (?1049l)
        vt.feed_bytes(b"\x1b[?1049hALT_VIM");
        assert!(vt.snapshot().visible_lines[0].starts_with("ALT_VIM"));
        vt.feed_bytes(b"\x1b[?1049l");
        assert!(vt.snapshot().visible_lines[0].starts_with("NEXUS"));
    }

    #[test]
    fn vt_buffer_preserves_split_multibyte_utf8_and_scrollback() {
        let mut vt = VtTerminalBuffer::new(20, 4, 100);
        // Split 3-byte box-drawing '─' (E2 94 80) and 4-byte emoji '🚀' (F0 9F 9A 80) across single-byte reads
        for &b in "─🚀OK".as_bytes() {
            vt.feed_bytes(&[b]);
        }
        let snap = vt.snapshot();
        assert!(
            snap.visible_lines[0].starts_with("─🚀OK"),
            "Expected clean UTF-8 without U+FFFD, got {:?}",
            snap.visible_lines[0]
        );
        assert!(!snap.visible_lines[0].contains('\u{FFFD}'));

        // Feed 6 lines into a 4-row terminal so 2 lines scroll into scrollback_lines
        vt.feed_bytes(b"\r\nL2\r\nL3\r\nL4\r\nL5\r\nL6");
        let scrolled = vt.snapshot();
        assert_eq!(scrolled.scrollback_lines.len(), 2);
        assert!(scrolled.scrollback_lines[0].starts_with("─🚀OK"));
        assert!(scrolled.scrollback_lines[1].starts_with("L2"));
        assert!(scrolled.visible_lines[0].starts_with("L3"));
    }

    #[test]
    fn telnet_naws_encodes_big_endian_dimensions() {
        let pkt = build_telnet_naws_packet(132, 43);
        assert_eq!(pkt, vec![255, 250, 31, 0, 132, 0, 43, 255, 240]);

        // RFC 855: 0xFF bytes inside NAWS dimensions must be doubled
        let ff_pkt = build_telnet_naws_packet(255, 0xFF10);
        assert_eq!(ff_pkt, vec![255, 250, 31, 0, 255, 255, 255, 255, 16, 255, 240]);
    }

    #[test]
    fn vt_buffer_tracks_decckm_application_cursor_keys_and_ris_reset() {
        let mut vt = VtTerminalBuffer::new(80, 24, 200);
        assert!(!vt.application_cursor_keys());
        assert!(!vt.snapshot().application_cursor_keys);

        vt.feed_bytes(b"\x1b[?1h");
        assert!(vt.application_cursor_keys());
        assert!(vt.snapshot().application_cursor_keys);

        vt.feed_bytes(b"\x1b[?1l");
        assert!(!vt.application_cursor_keys());

        vt.feed_bytes(b"\x1b[?1h");
        assert!(vt.application_cursor_keys());
        // RIS (ESC c) resets DECCKM back to false
        vt.feed_bytes(b"\x1bc");
        assert!(!vt.application_cursor_keys());
    }
}

