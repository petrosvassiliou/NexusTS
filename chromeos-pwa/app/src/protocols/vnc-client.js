// RFB 3.3 / 3.7 / 3.8 VNC & Apple Screen Sharing Client over `openRawTcpStream`.
// Supports Security Types 1 (None) & 2 (RFB 56-bit bit-reversed DES challenge-response),
// Raw, CopyRect, RRE, Hextile, and DesktopSize encodings, X11 Keysyms, Pointer & Clipboard sync.

import { initNexusWasm, wasmVncDesEncrypt } from '../core/wasm-loader.js';
import { openRawTcpStream } from './socket-transport.js';

// Standard FIPS 46-3 DES tables for standalone offline VNC authentication
const IP_TABLE = [
  58,50,42,34,26,18,10,2, 60,52,44,36,28,20,12,4,
  62,54,46,38,30,22,14,6, 64,56,48,40,32,24,16,8,
  57,49,41,33,25,17,9,1,  59,51,43,35,27,19,11,3,
  61,53,45,37,29,21,13,5, 63,55,47,39,31,23,15,7
];
const FP_TABLE = [
  40,8,48,16,56,24,64,32, 39,7,47,15,55,23,63,31,
  38,6,46,14,54,22,62,30, 37,5,45,13,53,21,61,29,
  36,4,44,12,52,20,60,28, 35,3,43,11,51,19,59,27,
  34,2,42,10,50,18,58,26, 33,1,41,9,49,17,57,25
];
const PC1 = [
  57,49,41,33,25,17,9, 1,58,50,42,34,26,18,
  10,2,59,51,43,35,27, 19,11,3,60,52,44,36,
  63,55,47,39,31,23,15, 7,62,54,46,38,30,22,
  14,6,61,53,45,37,29, 21,13,5,28,20,12,4
];
const PC2 = [
  14,17,11,24,1,5, 3,28,15,6,21,10,
  23,19,12,4,26,8, 16,7,27,20,13,2,
  41,52,31,37,47,55, 30,40,51,45,33,48,
  44,49,39,56,34,53, 46,42,50,36,29,32
];
const E_TABLE = [
  32,1,2,3,4,5, 4,5,6,7,8,9,
  8,9,10,11,12,13, 12,13,14,15,16,17,
  16,17,18,19,20,21, 20,21,22,23,24,25,
  24,25,26,27,28,29, 28,29,30,31,32,1
];
const P_TABLE = [
  16,7,20,21,29,12,28,17, 1,15,23,26,5,18,31,10,
  2,8,24,14,32,27,3,9, 19,13,30,6,22,11,4,25
];
const S_BOXES = [
  [14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7, 0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8, 4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0, 15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13],
  [15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10, 3,13,4,7,15,2,8,14,12,0,1,10,6,9,11,5, 0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15, 13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9],
  [10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8, 13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1, 13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7, 1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12],
  [7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15, 13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9, 10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4, 3,15,0,6,10,1,13,8,9,4,5,11,12,7,2,14],
  [2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9, 14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6, 4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14, 11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3],
  [12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11, 10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8, 9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6, 4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13],
  [4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1, 13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6, 1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2, 6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12],
  [13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7, 1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2, 7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8, 2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11]
];
const KEY_SHIFTS = [1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1];

function reverseByteBits(b) {
  let r = 0;
  for (let i = 0; i < 8; i++) {
    r = (r << 1) | ((b >>> i) & 1);
  }
  return r & 0xff;
}

function bytesToBits(bytes) {
  const bits = new Uint8Array(bytes.length * 8);
  for (let i = 0; i < bytes.length; i++) {
    for (let b = 0; b < 8; b++) {
      bits[i * 8 + b] = (bytes[i] >>> (7 - b)) & 1;
    }
  }
  return bits;
}

function bitsToBytes(bits) {
  const out = new Uint8Array(bits.length >>> 3);
  for (let i = 0; i < out.length; i++) {
    let v = 0;
    for (let b = 0; b < 8; b++) v = (v << 1) | bits[i * 8 + b];
    out[i] = v;
  }
  return out;
}

function permuteBits(src, table) {
  const out = new Uint8Array(table.length);
  for (let i = 0; i < table.length; i++) out[i] = src[table[i] - 1];
  return out;
}

function desEncryptBlock8(key8, block8) {
  const keyBits = bytesToBits(key8);
  const cd = permuteBits(keyBits, PC1);
  let c = cd.slice(0, 28);
  let d = cd.slice(28, 56);
  const subkeys = [];
  for (let round = 0; round < 16; round++) {
    const s = KEY_SHIFTS[round];
    c = Uint8Array.from([...c.slice(s), ...c.slice(0, s)]);
    d = Uint8Array.from([...d.slice(s), ...d.slice(0, s)]);
    const merged = new Uint8Array(56);
    merged.set(c, 0);
    merged.set(d, 28);
    subkeys.push(permuteBits(merged, PC2));
  }

  const ip = permuteBits(bytesToBits(block8), IP_TABLE);
  let l = ip.slice(0, 32);
  let r = ip.slice(32, 64);
  for (let round = 0; round < 16; round++) {
    const exp = permuteBits(r, E_TABLE);
    const sk = subkeys[round];
    const sOut = new Uint8Array(32);
    for (let box = 0; box < 8; box++) {
      const o = box * 6;
      const b0 = exp[o] ^ sk[o];
      const b1 = exp[o + 1] ^ sk[o + 1];
      const b2 = exp[o + 2] ^ sk[o + 2];
      const b3 = exp[o + 3] ^ sk[o + 3];
      const b4 = exp[o + 4] ^ sk[o + 4];
      const b5 = exp[o + 5] ^ sk[o + 5];
      const row = (b0 << 1) | b5;
      const col = (b1 << 3) | (b2 << 2) | (b3 << 1) | b4;
      const val = S_BOXES[box][row * 16 + col];
      sOut[box * 4 + 0] = (val >>> 3) & 1;
      sOut[box * 4 + 1] = (val >>> 2) & 1;
      sOut[box * 4 + 2] = (val >>> 1) & 1;
      sOut[box * 4 + 3] = val & 1;
    }
    const f = permuteBits(sOut, P_TABLE);
    const nextR = new Uint8Array(32);
    for (let i = 0; i < 32; i++) nextR[i] = l[i] ^ f[i];
    l = r;
    r = nextR;
  }
  const rl = new Uint8Array(64);
  rl.set(r, 0);
  rl.set(l, 32);
  return bitsToBytes(permuteBits(rl, FP_TABLE));
}

export async function computeVncDesResponse(passwordString, challenge16) {
  await initNexusWasm();
  const rawPass8 = new Uint8Array(8);
  const encoded = new TextEncoder().encode(String(passwordString || ''));
  rawPass8.set(encoded.subarray(0, 8));

  const wasmOut = wasmVncDesEncrypt(rawPass8, challenge16);
  if (wasmOut) return wasmOut;

  const revKey = new Uint8Array(8);
  for (let i = 0; i < 8; i++) revKey[i] = reverseByteBits(rawPass8[i]);
  const out = new Uint8Array(16);
  out.set(desEncryptBlock8(revKey, challenge16.subarray(0, 8)), 0);
  out.set(desEncryptBlock8(revKey, challenge16.subarray(8, 16)), 8);
  revKey.fill(0);
  return out;
}

const KEYSYM_MAP = {
  Backspace: 0xff08, Tab: 0xff09, Enter: 0xff0d, Escape: 0xff1b,
  Insert: 0xff63, Delete: 0xffff, Home: 0xff50, End: 0xff57,
  PageUp: 0xff55, PageDown: 0xff56,
  ArrowLeft: 0xff51, ArrowUp: 0xff52, ArrowRight: 0xff53, ArrowDown: 0xff54,
  F1: 0xffbe, F2: 0xffbf, F3: 0xffc0, F4: 0xffc1, F5: 0xffc2, F6: 0xffc3,
  F7: 0xffc4, F8: 0xffc5, F9: 0xffc6, F10: 0xffc7, F11: 0xffc8, F12: 0xffc9,
  Shift: 0xffe1, Control: 0xffe3, Alt: 0xffe9, Meta: 0xffeb
};

export function domKeyToX11Keysym(ev) {
  if (KEYSYM_MAP[ev.key]) return KEYSYM_MAP[ev.key];
  if (ev.key.length === 1) return ev.key.codePointAt(0);
  return null;
}

export class NexusVncSession {
  constructor({
    host,
    port = 5900,
    password = '',
    shared = true,
    viewOnly = false,
    canvas = null,
    onDesktopResize,
    onServerClipboard,
    onStatus,
    onClose,
    transportOptions = {}
  }) {
    this.host = host;
    this.port = port || 5900;
    this.password = password || '';
    this.shared = shared !== false;
    this.viewOnly = Boolean(viewOnly);
    this.canvas = canvas;
    this.onDesktopResize = onDesktopResize;
    this.onServerClipboard = onServerClipboard;
    this.onStatus = onStatus;
    this.onClose = onClose;
    this.transportOptions = transportOptions;
    this.stream = null;
    this.width = 1280;
    this.height = 800;
    this.serverName = '';
    this.rgbaBuffer = new Uint8ClampedArray(this.width * this.height * 4);
    this._closed = false;
  }

  attachCanvas(canvas) {
    this.canvas = canvas;
    this._paintFullCanvas();
  }

  async connect() {
    this.onStatus?.(`Connecting RFB to ${this.host}:${this.port}…`);
    this.stream = await openRawTcpStream(this.host, this.port, this.transportOptions);

    // 1. ProtocolVersion (`RFB 003.008\n`)
    const verBytes = await this.stream.readExact(12);
    const verStr = new TextDecoder().decode(verBytes);
    if (!verStr.startsWith('RFB ')) throw new Error(`Invalid VNC banner: ${verStr}`);
    const minor = Number(verStr.slice(8, 11));
    const replyVer = minor >= 8 ? 'RFB 003.008\n' : minor >= 7 ? 'RFB 003.007\n' : 'RFB 003.003\n';
    await this.stream.write(replyVer);

    // 2. Security handshake
    let selectedSec = 1;
    if (minor >= 7) {
      const count = (await this.stream.readExact(1))[0];
      if (count === 0) {
        const len = new DataView((await this.stream.readExact(4)).buffer).getUint32(0, false);
        const msg = new TextDecoder().decode(await this.stream.readExact(len));
        throw new Error(`VNC security rejected: ${msg}`);
      }
      const types = [...(await this.stream.readExact(count))];
      if (types.includes(1) && !this.password) selectedSec = 1;
      else if (types.includes(2)) selectedSec = 2;
      else if (types.includes(1)) selectedSec = 1;
      else throw new Error(`Unsupported VNC security types: ${types.join(', ')}`);
      await this.stream.write(new Uint8Array([selectedSec]));
    } else {
      selectedSec = new DataView((await this.stream.readExact(4)).buffer).getUint32(0, false);
    }

    if (selectedSec === 2) {
      const challenge = await this.stream.readExact(16);
      const response = await computeVncDesResponse(this.password, challenge);
      await this.stream.write(response);
    }

    if (selectedSec === 2 || minor >= 8) {
      const secRes = new DataView((await this.stream.readExact(4)).buffer).getUint32(0, false);
      if (secRes !== 0) {
        let detail = 'VNC password authentication failed';
        if (minor >= 8) {
          try {
            const rLen = new DataView((await this.stream.readExact(4)).buffer).getUint32(0, false);
            detail = new TextDecoder().decode(await this.stream.readExact(rLen));
          } catch {}
        }
        throw new Error(detail);
      }
    }

    // 3. ClientInit + ServerInit
    await this.stream.write(new Uint8Array([this.shared ? 1 : 0]));
    const srvInit = await this.stream.readExact(24);
    const dv = new DataView(srvInit.buffer, srvInit.byteOffset, srvInit.byteLength);
    const w = dv.getUint16(0, false);
    const h = dv.getUint16(2, false);
    const nameLen = dv.getUint32(20, false);
    this.serverName = new TextDecoder().decode(await this.stream.readExact(nameLen));
    this._initFramebuffer(w, h);

    // 4. SetPixelFormat (32-bpp TrueColor little-endian RGBA: R@0, G@8, B@16)
    const pfMsg = new Uint8Array(20);
    pfMsg[0] = 0; // SetPixelFormat
    pfMsg[4] = 32; // bits-per-pixel
    pfMsg[5] = 24; // depth
    pfMsg[6] = 0; // little-endian
    pfMsg[7] = 1; // true-color
    const pfDv = new DataView(pfMsg.buffer);
    pfDv.setUint16(8, 255, false);
    pfDv.setUint16(10, 255, false);
    pfDv.setUint16(12, 255, false);
    pfMsg[14] = 0;  // red-shift
    pfMsg[15] = 8;  // green-shift
    pfMsg[16] = 16; // blue-shift
    await this.stream.write(pfMsg);

    // 5. SetEncodings (CopyRect=1, Hextile=5, RRE=2, Raw=0, DesktopSize=-223)
    const encodings = [1, 5, 2, 0, -223];
    const encMsg = new Uint8Array(4 + encodings.length * 4);
    encMsg[0] = 2;
    const encDv = new DataView(encMsg.buffer);
    encDv.setUint16(2, encodings.length, false);
    encodings.forEach((enc, idx) => encDv.setInt32(4 + idx * 4, enc, false));
    await this.stream.write(encMsg);

    this.onStatus?.(`Connected to ${this.serverName || this.host} (${this.width}×${this.height})`);
    await this.requestFramebufferUpdate(false);
    this._pumpServerMessages();
    return this;
  }

  _initFramebuffer(w, h) {
    this.width = Math.max(1, w);
    this.height = Math.max(1, h);
    this.rgbaBuffer = new Uint8ClampedArray(this.width * this.height * 4);
    for (let i = 3; i < this.rgbaBuffer.length; i += 4) this.rgbaBuffer[i] = 255;
    if (this.canvas) {
      this.canvas.width = this.width;
      this.canvas.height = this.height;
    }
    this.onDesktopResize?.(this.width, this.height, this.serverName);
  }

  async requestFramebufferUpdate(incremental = true) {
    if (!this.stream || this._closed) return;
    const req = new Uint8Array(10);
    req[0] = 3;
    req[1] = incremental ? 1 : 0;
    const dv = new DataView(req.buffer);
    dv.setUint16(2, 0, false);
    dv.setUint16(4, 0, false);
    dv.setUint16(6, this.width, false);
    dv.setUint16(8, this.height, false);
    await this.stream.write(req);
  }

  _fillSolidRect(rx, ry, rw, rh, r, g, b) {
    const maxY = Math.min(this.height, ry + rh);
    const maxX = Math.min(this.width, rx + rw);
    for (let y = Math.max(0, ry); y < maxY; y++) {
      let idx = (y * this.width + Math.max(0, rx)) * 4;
      for (let x = Math.max(0, rx); x < maxX; x++) {
        this.rgbaBuffer[idx] = r;
        this.rgbaBuffer[idx + 1] = g;
        this.rgbaBuffer[idx + 2] = b;
        this.rgbaBuffer[idx + 3] = 255;
        idx += 4;
      }
    }
  }

  _paintFullCanvas() {
    if (!this.canvas) return;
    if (this.canvas.width !== this.width || this.canvas.height !== this.height) {
      this.canvas.width = this.width;
      this.canvas.height = this.height;
    }
    const ctx = this.canvas.getContext('2d');
    const img = new ImageData(this.rgbaBuffer, this.width, this.height);
    ctx.putImageData(img, 0, 0);
  }

  async _pumpServerMessages() {
    try {
      while (!this._closed) {
        const msgType = (await this.stream.readExact(1))[0];
        if (msgType === 0) {
          // FramebufferUpdate
          const hdr = await this.stream.readExact(3);
          const numRects = (hdr[1] << 8) | hdr[2];
          for (let r = 0; r < numRects; r++) {
            const rhdr = await this.stream.readExact(12);
            const rdv = new DataView(rhdr.buffer, rhdr.byteOffset, 12);
            const rx = rdv.getUint16(0, false);
            const ry = rdv.getUint16(2, false);
            const rw = rdv.getUint16(4, false);
            const rh = rdv.getUint16(6, false);
            const enc = rdv.getInt32(8, false);

            if (enc === -223) {
              this._initFramebuffer(rw, rh);
            } else if (enc === 0) {
              // Raw 32-bpp RGBA
              const raw = await this.stream.readExact(rw * rh * 4);
              for (let row = 0; row < rh; row++) {
                if (ry + row >= this.height) break;
                const srcOff = row * rw * 4;
                const dstOff = ((ry + row) * this.width + rx) * 4;
                const copyPixels = Math.min(rw, this.width - rx);
                for (let p = 0; p < copyPixels; p++) {
                  const s = srcOff + p * 4;
                  const d = dstOff + p * 4;
                  this.rgbaBuffer[d] = raw[s];
                  this.rgbaBuffer[d + 1] = raw[s + 1];
                  this.rgbaBuffer[d + 2] = raw[s + 2];
                  this.rgbaBuffer[d + 3] = 255;
                }
              }
            } else if (enc === 1) {
              // CopyRect
              const cp = await this.stream.readExact(4);
              const srcX = (cp[0] << 8) | cp[1];
              const srcY = (cp[2] << 8) | cp[3];
              const tmp = new Uint8ClampedArray(rw * rh * 4);
              for (let row = 0; row < rh; row++) {
                const sOff = ((srcY + row) * this.width + srcX) * 4;
                tmp.set(this.rgbaBuffer.subarray(sOff, sOff + rw * 4), row * rw * 4);
              }
              for (let row = 0; row < rh; row++) {
                const dOff = ((ry + row) * this.width + rx) * 4;
                this.rgbaBuffer.set(tmp.subarray(row * rw * 4, (row + 1) * rw * 4), dOff);
              }
            } else if (enc === 2) {
              // RRE
              const rreHdr = await this.stream.readExact(8);
              const subCount = new DataView(rreHdr.buffer, rreHdr.byteOffset, 4).getUint32(0, false);
              this._fillSolidRect(rx, ry, rw, rh, rreHdr[4], rreHdr[5], rreHdr[6]);
              const subs = await this.stream.readExact(subCount * 12);
              const sdv = new DataView(subs.buffer, subs.byteOffset, subs.byteLength);
              for (let i = 0; i < subCount; i++) {
                const o = i * 12;
                this._fillSolidRect(
                  rx + sdv.getUint16(o + 4, false),
                  ry + sdv.getUint16(o + 6, false),
                  sdv.getUint16(o + 8, false),
                  sdv.getUint16(o + 10, false),
                  subs[o], subs[o + 1], subs[o + 2]
                );
              }
            } else if (enc === 5) {
              // Hextile
              let bg = [0, 0, 0];
              let fg = [255, 255, 255];
              for (let ty = ry; ty < ry + rh; ty += 16) {
                const th = Math.min(16, ry + rh - ty);
                for (let tx = rx; tx < rx + rw; tx += 16) {
                  const tw = Math.min(16, rx + rw - tx);
                  const subenc = (await this.stream.readExact(1))[0];
                  if (subenc & 1) {
                    const tileRaw = await this.stream.readExact(tw * th * 4);
                    for (let r0 = 0; r0 < th; r0++) {
                      const dOff = ((ty + r0) * this.width + tx) * 4;
                      for (let c0 = 0; c0 < tw; c0++) {
                        const s = (r0 * tw + c0) * 4;
                        this.rgbaBuffer[dOff + c0 * 4] = tileRaw[s];
                        this.rgbaBuffer[dOff + c0 * 4 + 1] = tileRaw[s + 1];
                        this.rgbaBuffer[dOff + c0 * 4 + 2] = tileRaw[s + 2];
                        this.rgbaBuffer[dOff + c0 * 4 + 3] = 255;
                      }
                    }
                    continue;
                  }
                  if (subenc & 2) {
                    const b = await this.stream.readExact(4);
                    bg = [b[0], b[1], b[2]];
                  }
                  this._fillSolidRect(tx, ty, tw, th, bg[0], bg[1], bg[2]);
                  if (subenc & 4) {
                    const f = await this.stream.readExact(4);
                    fg = [f[0], f[1], f[2]];
                  }
                  if (subenc & 8) {
                    const nSub = (await this.stream.readExact(1))[0];
                    const colored = (subenc & 16) !== 0;
                    const subBytes = await this.stream.readExact(nSub * (colored ? 6 : 2));
                    for (let s = 0; s < nSub; s++) {
                      const o = s * (colored ? 6 : 2);
                      const col = colored ? [subBytes[o], subBytes[o + 1], subBytes[o + 2]] : fg;
                      const xy = subBytes[colored ? o + 4 : o];
                      const wh = subBytes[colored ? o + 5 : o + 1];
                      this._fillSolidRect(
                        tx + ((xy >>> 4) & 0x0f),
                        ty + (xy & 0x0f),
                        ((wh >>> 4) & 0x0f) + 1,
                        (wh & 0x0f) + 1,
                        col[0], col[1], col[2]
                      );
                    }
                  }
                }
              }
            }
          }
          this._paintFullCanvas();
          await this.requestFramebufferUpdate(true);
        } else if (msgType === 1) {
          // SetColourMapEntries
          const hdr = await this.stream.readExact(5);
          const nColors = (hdr[3] << 8) | hdr[4];
          await this.stream.readExact(nColors * 6);
        } else if (msgType === 2) {
          // Bell
        } else if (msgType === 3) {
          // ServerCutText
          const hdr = await this.stream.readExact(7);
          const len = new DataView(hdr.buffer, hdr.byteOffset + 3, 4).getUint32(0, false);
          const text = new TextDecoder().decode(await this.stream.readExact(len));
          this.onServerClipboard?.(text);
        }
      }
    } catch (err) {
      if (!this._closed) this.onClose?.(err);
    }
  }

  async sendKeyEvent(keysym, down) {
    if (this.viewOnly || !this.stream || !keysym) return;
    const pkt = new Uint8Array(8);
    pkt[0] = 4;
    pkt[1] = down ? 1 : 0;
    new DataView(pkt.buffer).setUint32(4, keysym >>> 0, false);
    await this.stream.write(pkt);
  }

  async sendPointerEvent(x, y, buttonMask) {
    if (this.viewOnly || !this.stream) return;
    const pkt = new Uint8Array(6);
    pkt[0] = 5;
    pkt[1] = buttonMask & 0xff;
    const dv = new DataView(pkt.buffer);
    dv.setUint16(2, Math.max(0, Math.min(this.width - 1, Math.round(x))), false);
    dv.setUint16(4, Math.max(0, Math.min(this.height - 1, Math.round(y))), false);
    await this.stream.write(pkt);
  }

  async sendClipboardText(text) {
    if (this.viewOnly || !this.stream) return;
    const raw = new TextEncoder().encode(String(text || ''));
    const pkt = new Uint8Array(8 + raw.length);
    pkt[0] = 6;
    new DataView(pkt.buffer).setUint32(4, raw.length, false);
    pkt.set(raw, 8);
    await this.stream.write(pkt);
  }

  async close() {
    this._closed = true;
    await this.stream?.close();
  }
}
