// High-Performance `xterm-256color` & 24-bit TrueColor VT100/VT520 Terminal State Machine & Canvas Renderer.
// Supports Primary + Alternate Screen (`?1049h/l`), Application Cursor Keys (`?1h/l`),
// Bracketed Paste (`?2004h/l`), Scroll Regions (`DECSTBM`), SGR 16/256/TrueColor, and Scrollback.

const ANSI_16_PALETTE = [
  '#0f141e', '#ef5350', '#4caf50', '#ffca28', '#42a5f5', '#ab47bc', '#26c6da', '#dce3ef',
  '#5d6b82', '#ff7070', '#69f0ae', '#ffd740', '#82b1ff', '#ce93d8', '#84ffff', '#ffffff'
];

function build256Palette() {
  const pal = [...ANSI_16_PALETTE];
  const cube = [0, 95, 135, 175, 215, 255];
  for (let r = 0; r < 6; r++) {
    for (let g = 0; g < 6; g++) {
      for (let b = 0; b < 6; b++) {
        pal.push(`rgb(${cube[r]},${cube[g]},${cube[b]})`);
      }
    }
  }
  for (let gray = 0; gray < 24; gray++) {
    const v = 8 + gray * 10;
    pal.push(`rgb(${v},${v},${v})`);
  }
  return pal;
}

export const XTERM_256_PALETTE = build256Palette();

const DEFAULT_FG = '#e4ebf5';
const DEFAULT_BG = '#0b1018';

function blankCell(attrs = null) {
  return {
    ch: ' ',
    fg: attrs?.fg || DEFAULT_FG,
    bg: attrs?.bg || DEFAULT_BG,
    bold: Boolean(attrs?.bold),
    italic: Boolean(attrs?.italic),
    underline: Boolean(attrs?.underline),
    inverse: Boolean(attrs?.inverse)
  };
}

function createGrid(cols, rows) {
  return Array.from({ length: rows }, () => Array.from({ length: cols }, () => blankCell()));
}

export class VtTerminalEmulator {
  constructor(cols = 110, rows = 32, maxScrollback = 5000) {
    this.cols = Math.max(20, cols);
    this.rows = Math.max(6, rows);
    this.maxScrollback = maxScrollback;
    this.primaryGrid = createGrid(this.cols, this.rows);
    this.altGrid = createGrid(this.cols, this.rows);
    this.usingAltScreen = false;
    this.scrollback = [];
    this.cursorCol = 0;
    this.cursorRow = 0;
    this.savedCursor = { col: 0, row: 0 };
    this.scrollTop = 0;
    this.scrollBottom = this.rows - 1;
    this.cursorVisible = true;
    this.applicationCursorKeys = false;
    this.bracketedPaste = false;
    this.title = 'NexusTS Terminal';
    this.attrs = { fg: DEFAULT_FG, bg: DEFAULT_BG, bold: false, italic: false, underline: false, inverse: false };
    this._state = 'GROUND';
    this._csiBuf = '';
    this._oscBuf = '';
    this._decoder = new TextDecoder('utf-8', { fatal: false });
    this.onRenderRequest = null;
    this.onReplyBytes = null;
  }

  get activeGrid() {
    return this.usingAltScreen ? this.altGrid : this.primaryGrid;
  }

  resize(newCols, newRows) {
    const c = Math.max(20, Math.trunc(newCols));
    const r = Math.max(6, Math.trunc(newRows));
    if (c === this.cols && r === this.rows) return;
    const resizeOne = (grid) => {
      while (grid.length < r) grid.push(Array.from({ length: c }, () => blankCell()));
      if (grid.length > r) grid.length = r;
      for (const row of grid) {
        while (row.length < c) row.push(blankCell());
        if (row.length > c) row.length = c;
      }
    };
    resizeOne(this.primaryGrid);
    resizeOne(this.altGrid);
    this.cols = c;
    this.rows = r;
    this.scrollTop = 0;
    this.scrollBottom = r - 1;
    this.cursorCol = Math.min(this.cursorCol, c - 1);
    this.cursorRow = Math.min(this.cursorRow, r - 1);
    this.onRenderRequest?.();
  }

  feedBytes(bytes) {
    const text = typeof bytes === 'string' ? bytes : this._decoder.decode(bytes, { stream: true });
    this.feedText(text);
  }

  feedText(text) {
    for (let i = 0; i < text.length; i++) {
      this._stepChar(text[i]);
    }
    this.onRenderRequest?.();
  }

  _scrollUpRegion(top, bottom, count = 1) {
    const grid = this.activeGrid;
    for (let n = 0; n < count; n++) {
      const removed = grid.splice(top, 1)[0];
      if (!this.usingAltScreen && top === 0 && removed) {
        this.scrollback.push(removed);
        if (this.scrollback.length > this.maxScrollback) this.scrollback.shift();
      }
      grid.splice(bottom, 0, Array.from({ length: this.cols }, () => blankCell(this.attrs)));
    }
  }

  _scrollDownRegion(top, bottom, count = 1) {
    const grid = this.activeGrid;
    for (let n = 0; n < count; n++) {
      grid.splice(bottom, 1);
      grid.splice(top, 0, Array.from({ length: this.cols }, () => blankCell(this.attrs)));
    }
  }

  _lineFeed() {
    if (this.cursorRow === this.scrollBottom) {
      this._scrollUpRegion(this.scrollTop, this.scrollBottom, 1);
    } else if (this.cursorRow < this.rows - 1) {
      this.cursorRow++;
    }
  }

  _putChar(ch) {
    const grid = this.activeGrid;
    if (this.cursorCol >= this.cols) {
      this.cursorCol = 0;
      this._lineFeed();
    }
    grid[this.cursorRow][this.cursorCol] = {
      ch,
      fg: this.attrs.fg,
      bg: this.attrs.bg,
      bold: this.attrs.bold,
      italic: this.attrs.italic,
      underline: this.attrs.underline,
      inverse: this.attrs.inverse
    };
    this.cursorCol++;
  }

  _stepChar(ch) {
    const code = ch.charCodeAt(0);
    if (this._state === 'GROUND') {
      if (code === 0x1b) {
        this._state = 'ESC';
      } else if (code === 0x0d) {
        this.cursorCol = 0;
      } else if (code === 0x0a || code === 0x0b || code === 0x0c) {
        this._lineFeed();
      } else if (code === 0x08) {
        if (this.cursorCol > 0) this.cursorCol--;
      } else if (code === 0x09) {
        this.cursorCol = Math.min(this.cols - 1, (Math.floor(this.cursorCol / 8) + 1) * 8);
      } else if (code >= 0x20) {
        this._putChar(ch);
      }
      return;
    }

    if (this._state === 'ESC') {
      if (ch === '[') {
        this._csiBuf = '';
        this._state = 'CSI';
      } else if (ch === ']') {
        this._oscBuf = '';
        this._state = 'OSC';
      } else if (ch === '7') {
        this.savedCursor = { col: this.cursorCol, row: this.cursorRow };
        this._state = 'GROUND';
      } else if (ch === '8') {
        this.cursorCol = Math.min(this.cols - 1, this.savedCursor.col);
        this.cursorRow = Math.min(this.rows - 1, this.savedCursor.row);
        this._state = 'GROUND';
      } else if (ch === 'M') {
        if (this.cursorRow === this.scrollTop) {
          this._scrollDownRegion(this.scrollTop, this.scrollBottom, 1);
        } else if (this.cursorRow > 0) {
          this.cursorRow--;
        }
        this._state = 'GROUND';
      } else if (ch === 'c') {
        this.primaryGrid = createGrid(this.cols, this.rows);
        this.cursorCol = 0;
        this.cursorRow = 0;
        this._state = 'GROUND';
      } else {
        this._state = 'GROUND';
      }
      return;
    }

    if (this._state === 'OSC') {
      if (code === 0x07) {
        this._handleOsc(this._oscBuf);
        this._state = 'GROUND';
      } else if (code === 0x1b) {
        this._state = 'OSC_ESC';
      } else {
        this._oscBuf += ch;
      }
      return;
    }

    if (this._state === 'OSC_ESC') {
      if (ch === '\\') this._handleOsc(this._oscBuf);
      this._state = 'GROUND';
      return;
    }

    if (this._state === 'CSI') {
      if (code >= 0x40 && code <= 0x7e) {
        this._handleCsi(this._csiBuf, ch);
        this._state = 'GROUND';
      } else {
        this._csiBuf += ch;
      }
    }
  }

  _handleOsc(payload) {
    const semi = payload.indexOf(';');
    if (semi > 0) {
      const cmd = payload.slice(0, semi);
      if (cmd === '0' || cmd === '2') {
        this.title = payload.slice(semi + 1).slice(0, 120);
      }
    }
  }

  _handleCsi(rawParams, finalChar) {
    const grid = this.activeGrid;
    const priv = rawParams.startsWith('?');
    const clean = priv ? rawParams.slice(1) : rawParams;
    const nums = clean.length === 0 ? [] : clean.split(';').map((s) => (s === '' ? 0 : Number(s) || 0));
    const p0 = nums[0] || 0;
    const p1 = nums[1] || 0;

    if (priv && (finalChar === 'h' || finalChar === 'l')) {
      const enable = finalChar === 'h';
      for (const mode of nums) {
        if (mode === 1) this.applicationCursorKeys = enable;
        else if (mode === 25) this.cursorVisible = enable;
        else if (mode === 2004) this.bracketedPaste = enable;
        else if (mode === 1049 || mode === 47 || mode === 1047) {
          if (enable && !this.usingAltScreen) {
            this.savedCursor = { col: this.cursorCol, row: this.cursorRow };
            this.altGrid = createGrid(this.cols, this.rows);
            this.usingAltScreen = true;
            this.cursorCol = 0;
            this.cursorRow = 0;
          } else if (!enable && this.usingAltScreen) {
            this.usingAltScreen = false;
            this.cursorCol = Math.min(this.cols - 1, this.savedCursor.col);
            this.cursorRow = Math.min(this.rows - 1, this.savedCursor.row);
          }
        }
      }
      return;
    }

    switch (finalChar) {
      case 'A':
        this.cursorRow = Math.max(0, this.cursorRow - Math.max(1, p0));
        break;
      case 'B':
        this.cursorRow = Math.min(this.rows - 1, this.cursorRow + Math.max(1, p0));
        break;
      case 'C':
        this.cursorCol = Math.min(this.cols - 1, this.cursorCol + Math.max(1, p0));
        break;
      case 'D':
        this.cursorCol = Math.max(0, this.cursorCol - Math.max(1, p0));
        break;
      case 'G':
        this.cursorCol = Math.min(this.cols - 1, Math.max(0, (p0 || 1) - 1));
        break;
      case 'd':
        this.cursorRow = Math.min(this.rows - 1, Math.max(0, (p0 || 1) - 1));
        break;
      case 'H':
      case 'f':
        this.cursorRow = Math.min(this.rows - 1, Math.max(0, (p0 || 1) - 1));
        this.cursorCol = Math.min(this.cols - 1, Math.max(0, (p1 || 1) - 1));
        break;
      case 'J':
        if (p0 === 2 || p0 === 3) {
          for (let r = 0; r < this.rows; r++) {
            for (let c = 0; c < this.cols; c++) grid[r][c] = blankCell(this.attrs);
          }
        } else if (p0 === 0) {
          for (let c = this.cursorCol; c < this.cols; c++) grid[this.cursorRow][c] = blankCell(this.attrs);
          for (let r = this.cursorRow + 1; r < this.rows; r++) {
            for (let c = 0; c < this.cols; c++) grid[r][c] = blankCell(this.attrs);
          }
        } else if (p0 === 1) {
          for (let r = 0; r < this.cursorRow; r++) {
            for (let c = 0; c < this.cols; c++) grid[r][c] = blankCell(this.attrs);
          }
          for (let c = 0; c <= this.cursorCol; c++) grid[this.cursorRow][c] = blankCell(this.attrs);
        }
        break;
      case 'K':
        if (p0 === 0) {
          for (let c = this.cursorCol; c < this.cols; c++) grid[this.cursorRow][c] = blankCell(this.attrs);
        } else if (p0 === 1) {
          for (let c = 0; c <= this.cursorCol; c++) grid[this.cursorRow][c] = blankCell(this.attrs);
        } else if (p0 === 2) {
          for (let c = 0; c < this.cols; c++) grid[this.cursorRow][c] = blankCell(this.attrs);
        }
        break;
      case 'L':
        this._scrollDownRegion(this.cursorRow, this.scrollBottom, Math.max(1, p0));
        break;
      case 'M':
        this._scrollUpRegion(this.cursorRow, this.scrollBottom, Math.max(1, p0));
        break;
      case 'P': {
        const delCount = Math.max(1, p0);
        grid[this.cursorRow].splice(this.cursorCol, delCount);
        while (grid[this.cursorRow].length < this.cols) grid[this.cursorRow].push(blankCell(this.attrs));
        break;
      }
      case '@': {
        const insCount = Math.max(1, p0);
        for (let i = 0; i < insCount; i++) grid[this.cursorRow].splice(this.cursorCol, 0, blankCell(this.attrs));
        grid[this.cursorRow].length = this.cols;
        break;
      }
      case 'r': {
        const top = Math.max(0, (p0 || 1) - 1);
        const bot = Math.min(this.rows - 1, (p1 || this.rows) - 1);
        if (top < bot) {
          this.scrollTop = top;
          this.scrollBottom = bot;
          this.cursorCol = 0;
          this.cursorRow = 0;
        }
        break;
      }
      case 'n':
        if (p0 === 6) {
          const rep = `\x1b[${this.cursorRow + 1};${this.cursorCol + 1}R`;
          this.onReplyBytes?.(new TextEncoder().encode(rep));
        }
        break;
      case 'm':
        this._applySgr(nums.length === 0 ? [0] : nums);
        break;
      default:
        break;
    }
  }

  _applySgr(codes) {
    for (let i = 0; i < codes.length; i++) {
      const c = codes[i];
      if (c === 0) {
        this.attrs = { fg: DEFAULT_FG, bg: DEFAULT_BG, bold: false, italic: false, underline: false, inverse: false };
      } else if (c === 1) this.attrs.bold = true;
      else if (c === 3) this.attrs.italic = true;
      else if (c === 4) this.attrs.underline = true;
      else if (c === 7) this.attrs.inverse = true;
      else if (c === 22) this.attrs.bold = false;
      else if (c === 23) this.attrs.italic = false;
      else if (c === 24) this.attrs.underline = false;
      else if (c === 27) this.attrs.inverse = false;
      else if (c >= 30 && c <= 37) this.attrs.fg = XTERM_256_PALETTE[c - 30 + (this.attrs.bold ? 8 : 0)];
      else if (c === 39) this.attrs.fg = DEFAULT_FG;
      else if (c >= 40 && c <= 47) this.attrs.bg = XTERM_256_PALETTE[c - 40];
      else if (c === 49) this.attrs.bg = DEFAULT_BG;
      else if (c >= 90 && c <= 97) this.attrs.fg = XTERM_256_PALETTE[c - 90 + 8];
      else if (c >= 100 && c <= 107) this.attrs.bg = XTERM_256_PALETTE[c - 100 + 8];
      else if (c === 38 || c === 48) {
        const isFg = c === 38;
        const mode = codes[i + 1];
        if (mode === 5 && i + 2 < codes.length) {
          const idx = Math.max(0, Math.min(255, codes[i + 2]));
          if (isFg) this.attrs.fg = XTERM_256_PALETTE[idx];
          else this.attrs.bg = XTERM_256_PALETTE[idx];
          i += 2;
        } else if (mode === 2 && i + 4 < codes.length) {
          const rgb = `rgb(${codes[i + 2] & 255},${codes[i + 3] & 255},${codes[i + 4] & 255})`;
          if (isFg) this.attrs.fg = rgb;
          else this.attrs.bg = rgb;
          i += 4;
        }
      }
    }
  }

  translateKeyEvent(ev) {
    if (ev.ctrlKey && !ev.altKey && !ev.metaKey && ev.key.length === 1) {
      const upper = ev.key.toUpperCase().charCodeAt(0);
      if (upper >= 64 && upper <= 95) {
        return new Uint8Array([upper - 64]);
      }
    }
    const app = this.applicationCursorKeys;
    const special = {
      Enter: '\r',
      Backspace: '\x7f',
      Tab: '\t',
      Escape: '\x1b',
      ArrowUp: app ? '\x1bOA' : '\x1b[A',
      ArrowDown: app ? '\x1bOB' : '\x1b[B',
      ArrowRight: app ? '\x1bOC' : '\x1b[C',
      ArrowLeft: app ? '\x1bOD' : '\x1b[D',
      Home: '\x1b[H',
      End: '\x1b[F',
      Delete: '\x1b[3~',
      PageUp: '\x1b[5~',
      PageDown: '\x1b[6~',
      Insert: '\x1b[2~',
      F1: '\x1bOP',
      F2: '\x1bOQ',
      F3: '\x1bOR',
      F4: '\x1bOS',
      F5: '\x1b[15~',
      F6: '\x1b[17~',
      F7: '\x1b[18~',
      F8: '\x1b[19~',
      F9: '\x1b[20~',
      F10: '\x1b[21~',
      F11: '\x1b[23~',
      F12: '\x1b[24~'
    };
    if (special[ev.key]) return new TextEncoder().encode(special[ev.key]);
    if (ev.key.length === 1 && !ev.metaKey) {
      const prefix = ev.altKey ? '\x1b' : '';
      return new TextEncoder().encode(prefix + ev.key);
    }
    return null;
  }

  visibleLinesText() {
    return this.activeGrid.map((row) => row.map((c) => c.ch).join('').replace(/\s+$/, ''));
  }

  renderToCanvas(canvas, focused = true) {
    if (!canvas) return;
    const dpr = globalThis.devicePixelRatio || 1;
    const fontSize = 13;
    const cellW = 8.1;
    const cellH = 18;
    const padX = 10;
    const padY = 8;
    const logicalW = Math.ceil(this.cols * cellW + padX * 2);
    const logicalH = Math.ceil(this.rows * cellH + padY * 2);

    if (canvas.width !== Math.round(logicalW * dpr) || canvas.height !== Math.round(logicalH * dpr)) {
      canvas.width = Math.round(logicalW * dpr);
      canvas.height = Math.round(logicalH * dpr);
      canvas.style.width = `${logicalW}px`;
      canvas.style.height = `${logicalH}px`;
    }
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = DEFAULT_BG;
    ctx.fillRect(0, 0, logicalW, logicalH);
    ctx.textBaseline = 'top';

    const grid = this.activeGrid;
    for (let r = 0; r < this.rows; r++) {
      const y = padY + r * cellH;
      const row = grid[r];
      for (let c = 0; c < this.cols; c++) {
        const cell = row[c];
        const x = padX + c * cellW;
        const fg = cell.inverse ? cell.bg : cell.fg;
        const bg = cell.inverse ? cell.fg : cell.bg;
        if (bg !== DEFAULT_BG) {
          ctx.fillStyle = bg;
          ctx.fillRect(x, y, Math.ceil(cellW), cellH);
        }
        if (cell.ch !== ' ') {
          ctx.font = `${cell.italic ? 'italic ' : ''}${cell.bold ? '600 ' : '400 '}${fontSize}px ui-monospace, "JetBrains Mono", "Fira Code", monospace`;
          ctx.fillStyle = fg;
          ctx.fillText(cell.ch, x, y + 2);
        }
        if (cell.underline) {
          ctx.fillStyle = fg;
          ctx.fillRect(x, y + cellH - 2, cellW, 1);
        }
      }
    }

    if (this.cursorVisible) {
      const cx = padX + this.cursorCol * cellW;
      const cy = padY + this.cursorRow * cellH;
      if (focused) {
        ctx.fillStyle = 'rgba(77, 182, 255, 0.72)';
        ctx.fillRect(cx, cy + 1, cellW, cellH - 2);
      } else {
        ctx.strokeStyle = 'rgba(77, 182, 255, 0.72)';
        ctx.strokeRect(cx + 0.5, cy + 1.5, cellW - 1, cellH - 3);
      }
    }
  }
}
