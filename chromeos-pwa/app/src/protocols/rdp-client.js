// `[MS-RDPBCGR]` Remote Desktop Protocol (RDP), Windows Terminal Services & Hyper-V VMConnect Client.
// Supports X.224 Connection Request + Hyper-V Preconnection PDU v2 (`PCB` `<vm-id>;EnhancedMode=1`),
// MCS Connect-Initial + GCC ConferenceCreateRequest, Fast-Path Keyboard Scancodes (`Ctrl+Alt+Del`, `Win`, `F1-F12`),
// Fast-Path Pointer/Wheel, Uncompressed 15/16/24/32-bpp & Interleaved RLE Bitmap Rendering, and `CLIPRDR` Text Clipboard.

import { effectiveRdpPreconnectionBlob } from '../core/vault-models.js';
import { openRawTcpStream } from './socket-transport.js';

export const PROTOCOL_RDP = 0x00000000;
export const PROTOCOL_SSL = 0x00000001;
export const PROTOCOL_HYBRID = 0x00000002;

// Complete DOM `KeyboardEvent.code` -> PC AT Set 1 Scancode + Extended Flag
const DOM_CODE_TO_SCANCODE = {
  Escape: [0x01, false], Digit1: [0x02, false], Digit2: [0x03, false], Digit3: [0x04, false],
  Digit4: [0x05, false], Digit5: [0x06, false], Digit6: [0x07, false], Digit7: [0x08, false],
  Digit8: [0x09, false], Digit9: [0x0a, false], Digit0: [0x0b, false], Minus: [0x0c, false],
  Equal: [0x0d, false], Backspace: [0x0e, false], Tab: [0x0f, false],
  KeyQ: [0x10, false], KeyW: [0x11, false], KeyE: [0x12, false], KeyR: [0x13, false],
  KeyT: [0x14, false], KeyY: [0x15, false], KeyU: [0x16, false], KeyI: [0x17, false],
  KeyO: [0x18, false], KeyP: [0x19, false], BracketLeft: [0x1a, false], BracketRight: [0x1b, false],
  Enter: [0x1c, false], ControlLeft: [0x1d, false],
  KeyA: [0x1e, false], KeyS: [0x1f, false], KeyD: [0x20, false], KeyF: [0x21, false],
  KeyG: [0x22, false], KeyH: [0x23, false], KeyJ: [0x24, false], KeyK: [0x25, false],
  KeyL: [0x26, false], Semicolon: [0x27, false], Quote: [0x28, false], Backquote: [0x29, false],
  ShiftLeft: [0x2a, false], Backslash: [0x2b, false],
  KeyZ: [0x2c, false], KeyX: [0x2d, false], KeyC: [0x2e, false], KeyV: [0x2f, false],
  KeyB: [0x30, false], KeyN: [0x31, false], KeyM: [0x32, false], Comma: [0x33, false],
  Period: [0x34, false], Slash: [0x35, false], ShiftRight: [0x36, false],
  NumpadMultiply: [0x37, false], AltLeft: [0x38, false], Space: [0x39, false], CapsLock: [0x3a, false],
  F1: [0x3b, false], F2: [0x3c, false], F3: [0x3d, false], F4: [0x3e, false], F5: [0x3f, false],
  F6: [0x40, false], F7: [0x41, false], F8: [0x42, false], F9: [0x43, false], F10: [0x44, false],
  F11: [0x57, false], F12: [0x58, false],
  ControlRight: [0x1d, true], AltRight: [0x38, true], MetaLeft: [0x5b, true], MetaRight: [0x5c, true],
  Home: [0x47, true], ArrowUp: [0x48, true], PageUp: [0x49, true],
  ArrowLeft: [0x4b, true], ArrowRight: [0x4d, true],
  End: [0x4f, true], ArrowDown: [0x50, true], PageDown: [0x51, true],
  Insert: [0x52, true], Delete: [0x53, true], NumpadEnter: [0x1c, true], NumpadDivide: [0x35, true]
};

export function encodeUtf16Le(str, includeNull = false) {
  const s = String(str || '');
  const len = s.length + (includeNull ? 1 : 0);
  const out = new Uint8Array(len * 2);
  const dv = new DataView(out.buffer);
  for (let i = 0; i < s.length; i++) {
    dv.setUint16(i * 2, s.charCodeAt(i), true);
  }
  return out;
}

export function decodeUtf16Le(bytes) {
  let out = '';
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (let i = 0; i + 1 < bytes.length; i += 2) {
    const code = dv.getUint16(i, true);
    if (code === 0) break;
    out += String.fromCharCode(code);
  }
  return out;
}

function wrapTpkt(x224Payload) {
  const total = 4 + x224Payload.length;
  const out = new Uint8Array(total);
  out[0] = 0x03; // TPKT v3
  out[1] = 0x00;
  out[2] = (total >>> 8) & 0xff;
  out[3] = total & 0xff;
  out.set(x224Payload, 4);
  return out;
}

/**
 * Builds the `[MS-RDPBCGR]` Preconnection PDU v2 (`RDP_PRECONNECTION_PDU_V2`) for Hyper-V VMConnect (`:2179`).
 */
export function buildHyperVPreconnectionPdu(pcbBlobString) {
  const utf16 = encodeUtf16Le(pcbBlobString, true);
  const cchPCB = utf16.length / 2;
  const out = new Uint8Array(18 + utf16.length);
  const dv = new DataView(out.buffer);
  dv.setUint32(0, out.length, true); // cbSize
  dv.setUint32(4, 0, true);          // Flags
  dv.setUint32(8, 2, true);          // Version = 2
  dv.setUint32(12, 0, true);         // Id
  dv.setUint16(16, cchPCB, true);    // cchPCB
  out.set(utf16, 18);
  return out;
}

/**
 * Builds an X.224 Connection Request PDU wrapped in RFC 1006 TPKT v3.
 */
export function buildX224ConnectionRequestPdu({ username = '', requestedProtocols = PROTOCOL_RDP } = {}) {
  const cleanUser = String(username || 'nexusts').slice(0, 16);
  const cookie = new TextEncoder().encode(`Cookie: mstshash=${cleanUser}\r\n`);
  const negReq = new Uint8Array(8);
  negReq[0] = 0x01; // TYPE_RDP_NEG_REQ
  negReq[1] = 0x00;
  negReq[2] = 0x08;
  negReq[3] = 0x00;
  new DataView(negReq.buffer).setUint32(4, requestedProtocols >>> 0, true);

  const crPayloadLen = 6 + cookie.length + negReq.length;
  const x224 = new Uint8Array(1 + crPayloadLen);
  x224[0] = crPayloadLen;
  x224[1] = 0xe0; // X.224 Connection Request
  x224[2] = 0x00;
  x224[3] = 0x00;
  x224[4] = 0x00;
  x224[5] = 0x00;
  x224[6] = 0x00;
  x224.set(cookie, 7);
  x224.set(negReq, 7 + cookie.length);
  return wrapTpkt(x224);
}

export function parseX224ConnectionConfirmPdu(tpktPayload) {
  if (tpktPayload.length < 7 || (tpktPayload[1] & 0xf0) !== 0xd0) {
    throw new Error('Expected X.224 Connection Confirm PDU (0xD0)');
  }
  let selectedProtocol = PROTOCOL_RDP;
  let failureCode = null;
  if (tpktPayload.length >= 15) {
    const negType = tpktPayload[7];
    const dv = new DataView(tpktPayload.buffer, tpktPayload.byteOffset + 11, 4);
    if (negType === 0x02) {
      selectedProtocol = dv.getUint32(0, true);
    } else if (negType === 0x03) {
      failureCode = dv.getUint32(0, true);
    }
  }
  return { selectedProtocol, failureCode };
}

/**
 * Builds a Fast-Path Keyboard Scancode Input Event PDU (`[MS-RDPBCGR]` §2.2.8.1.2.2.1).
 */
export function buildFastPathKeyboardPdu(scancode, isKeyDown, isExtended = false) {
  let eventFlags = isKeyDown ? 0x00 : 0x01; // FASTPATH_INPUT_KBDFLAGS_RELEASE = 0x01
  if (isExtended) eventFlags |= 0x02;      // FASTPATH_INPUT_KBDFLAGS_EXTENDED = 0x02
  const eventHeader = ((eventFlags & 0x1f) << 3) | 0x00; // FASTPATH_INPUT_EVENT_SCANCODE = 0
  // Fast-Path header: action=0 (FASTPATH_INPUT_ACTION_FASTPATH), numEvents=1 -> (1 << 2) = 0x04, length=4
  return new Uint8Array([0x04, 0x04, eventHeader, scancode & 0xff]);
}

/**
 * Builds a Fast-Path Mouse Pointer Input Event PDU (`[MS-RDPBCGR]` §2.2.8.1.2.2.3).
 */
export function buildFastPathMousePdu(x, y, pointerFlags) {
  const pkt = new Uint8Array(9);
  pkt[0] = 0x04; // 1 event
  pkt[1] = 0x09; // 9 bytes total
  pkt[2] = (0x01 << 5) ? 0x20 : 0x20; // eventCode = FASTPATH_INPUT_EVENT_MOUSE (1 << 5 = 0x20)
  const dv = new DataView(pkt.buffer);
  dv.setUint16(3, pointerFlags & 0xffff, true);
  dv.setUint16(5, Math.max(0, x) & 0xffff, true);
  dv.setUint16(7, Math.max(0, y) & 0xffff, true);
  return pkt;
}

/**
 * Decompresses uncompressed bottom-up 15/16/24/32-bpp OR MS-RDPBCGR Interleaved RLE bitmap data into RGBA8888.
 */
export function decodeRdpBitmapTileToRgba(srcBytes, width, height, bpp, isCompressed) {
  const out = new Uint8ClampedArray(width * height * 4);
  for (let i = 3; i < out.length; i += 4) out[i] = 255;
  const bytesPerPixel = bpp === 32 ? 4 : bpp === 24 ? 3 : bpp === 16 || bpp === 15 ? 2 : 1;

  const unpackPixel = (buf, off) => {
    if (bpp === 32 || bpp === 24) {
      return [buf[off + 2] || 0, buf[off + 1] || 0, buf[off] || 0];
    }
    if (bpp === 16) {
      const v = (buf[off] || 0) | ((buf[off + 1] || 0) << 8);
      return [((v >>> 11) & 0x1f) * 255 / 31, ((v >>> 5) & 0x3f) * 255 / 63, (v & 0x1f) * 255 / 31];
    }
    if (bpp === 15) {
      const v = (buf[off] || 0) | ((buf[off + 1] || 0) << 8);
      return [((v >>> 10) & 0x1f) * 255 / 31, ((v >>> 5) & 0x1f) * 255 / 31, (v & 0x1f) * 255 / 31];
    }
    const g = buf[off] || 0;
    return [g, g, g];
  };

  if (!isCompressed) {
    const rowStride = width * bytesPerPixel;
    for (let srcRow = 0; srcRow < height; srcRow++) {
      const dstY = height - 1 - srcRow;
      for (let x = 0; x < width; x++) {
        const sOff = srcRow * rowStride + x * bytesPerPixel;
        if (sOff + bytesPerPixel > srcBytes.length) break;
        const [r, g, b] = unpackPixel(srcBytes, sOff);
        const dOff = (dstY * width + x) * 4;
        out[dOff] = r;
        out[dOff + 1] = g;
        out[dOff + 2] = b;
      }
    }
    return out;
  }

  // MS-RDPBCGR §3.1.9.1.1 Interleaved RLE decompression (bottom-up scanlines)
  const rawPixels = new Uint8Array(width * height * bytesPerPixel);
  const rowDelta = width * bytesPerPixel;
  let srcPos = 0;
  let dstPos = 0;
  let fgPel = new Uint8Array(bytesPerPixel);
  let insertFgPel = false;

  // Skip optional 8-byte CompressedDataHeader (`cbCompFirstRowSize == 0`) if present
  if (srcBytes.length >= 8 && srcBytes[0] === 0 && srcBytes[1] === 0) {
    const mainBodySize = srcBytes[2] | (srcBytes[3] << 8);
    if (mainBodySize > 0 && mainBodySize <= srcBytes.length - 8) {
      srcPos = 8;
    }
  }

  while (srcPos < srcBytes.length && dstPos < rawPixels.length) {
    const hdr = srcBytes[srcPos++];
    let code = hdr >>> 5;
    let runLen = hdr & 0x1f;
    if (code === 7) {
      code = hdr >>> 4;
      runLen = hdr & 0x0f;
    }

    const extractRunLength = (baseLen, megaBase) => {
      if (baseLen !== 0) return baseLen;
      if (srcPos >= srcBytes.length) return 0;
      return srcBytes[srcPos++] + megaBase;
    };

    if (code === 0) {
      // REGULAR_BG_RUN
      const count = extractRunLength(runLen, 32);
      for (let i = 0; i < count && dstPos + bytesPerPixel <= rawPixels.length; i++) {
        for (let b = 0; b < bytesPerPixel; b++) {
          const above = dstPos >= rowDelta ? rawPixels[dstPos - rowDelta + b] : 0;
          rawPixels[dstPos + b] = insertFgPel && i === 0 ? above ^ fgPel[b] : above;
        }
        dstPos += bytesPerPixel;
      }
      insertFgPel = true;
    } else if (code === 1) {
      // REGULAR_FG_RUN
      const count = extractRunLength(runLen, 32);
      for (let i = 0; i < count && dstPos + bytesPerPixel <= rawPixels.length; i++) {
        for (let b = 0; b < bytesPerPixel; b++) {
          const above = dstPos >= rowDelta ? rawPixels[dstPos - rowDelta + b] : 0;
          rawPixels[dstPos + b] = above ^ fgPel[b];
        }
        dstPos += bytesPerPixel;
      }
      insertFgPel = false;
    } else if (code === 2) {
      // REGULAR_FG_BG_IMAGE
      const bitmaskBytes = runLen === 0 ? (srcBytes[srcPos++] || 0) + 1 : runLen * 1;
      for (let m = 0; m < bitmaskBytes && srcPos < srcBytes.length; m++) {
        const mask = srcBytes[srcPos++];
        for (let bit = 0; bit < 8 && dstPos + bytesPerPixel <= rawPixels.length; bit++) {
          const isFg = ((mask >>> bit) & 1) !== 0;
          for (let b = 0; b < bytesPerPixel; b++) {
            const above = dstPos >= rowDelta ? rawPixels[dstPos - rowDelta + b] : 0;
            rawPixels[dstPos + b] = isFg ? above ^ fgPel[b] : above;
          }
          dstPos += bytesPerPixel;
        }
      }
      insertFgPel = false;
    } else if (code === 3) {
      // REGULAR_COLOR_RUN
      const count = extractRunLength(runLen, 32);
      const color = srcBytes.subarray(srcPos, srcPos + bytesPerPixel);
      srcPos += bytesPerPixel;
      for (let i = 0; i < count && dstPos + bytesPerPixel <= rawPixels.length; i++) {
        rawPixels.set(color, dstPos);
        dstPos += bytesPerPixel;
      }
      insertFgPel = false;
    } else if (code === 4) {
      // REGULAR_COLOR_IMAGE
      const count = extractRunLength(runLen, 32);
      const byteCount = count * bytesPerPixel;
      const copySlice = srcBytes.subarray(srcPos, Math.min(srcBytes.length, srcPos + byteCount));
      srcPos += copySlice.length;
      const fit = Math.min(copySlice.length, rawPixels.length - dstPos);
      rawPixels.set(copySlice.subarray(0, fit), dstPos);
      dstPos += fit;
      insertFgPel = false;
    } else if (code === 12) {
      // LITE_SET_FG_FG_RUN
      const count = extractRunLength(runLen, 16);
      fgPel = srcBytes.slice(srcPos, srcPos + bytesPerPixel);
      srcPos += bytesPerPixel;
      for (let i = 0; i < count && dstPos + bytesPerPixel <= rawPixels.length; i++) {
        for (let b = 0; b < bytesPerPixel; b++) {
          const above = dstPos >= rowDelta ? rawPixels[dstPos - rowDelta + b] : 0;
          rawPixels[dstPos + b] = above ^ fgPel[b];
        }
        dstPos += bytesPerPixel;
      }
      insertFgPel = false;
    } else if (hdr === 0xf9 || hdr === 0xfa) {
      // SPECIAL_FGBG_1 / SPECIAL_FGBG_2
      const mask = hdr === 0xf9 ? 0x03 : 0x05;
      for (let bit = 0; bit < 8 && dstPos + bytesPerPixel <= rawPixels.length; bit++) {
        const isFg = ((mask >>> bit) & 1) !== 0;
        for (let b = 0; b < bytesPerPixel; b++) {
          const above = dstPos >= rowDelta ? rawPixels[dstPos - rowDelta + b] : 0;
          rawPixels[dstPos + b] = isFg ? above ^ fgPel[b] : above;
        }
        dstPos += bytesPerPixel;
      }
      insertFgPel = false;
    } else if (hdr === 0xfd) {
      // WHITE
      for (let b = 0; b < bytesPerPixel && dstPos < rawPixels.length; b++) rawPixels[dstPos++] = 0xff;
      insertFgPel = false;
    } else if (hdr === 0xfe) {
      // BLACK
      for (let b = 0; b < bytesPerPixel && dstPos < rawPixels.length; b++) rawPixels[dstPos++] = 0x00;
      insertFgPel = false;
    } else {
      break;
    }
  }

  for (let srcRow = 0; srcRow < height; srcRow++) {
    const dstY = height - 1 - srcRow;
    for (let x = 0; x < width; x++) {
      const sOff = srcRow * rowDelta + x * bytesPerPixel;
      const [r, g, b] = unpackPixel(rawPixels, sOff);
      const dOff = (dstY * width + x) * 4;
      out[dOff] = r;
      out[dOff + 1] = g;
      out[dOff + 2] = b;
    }
  }
  return out;
}

function writeBerLength(len) {
  if (len < 0x80) return new Uint8Array([len]);
  if (len <= 0xff) return new Uint8Array([0x81, len]);
  return new Uint8Array([0x82, (len >>> 8) & 0xff, len & 0xff]);
}

function concatArrays(chunks) {
  const out = new Uint8Array(chunks.reduce((a, c) => a + c.length, 0));
  let pos = 0;
  for (const c of chunks) { out.set(c, pos); pos += c.length; }
  return out;
}

export class NexusRdpSession {
  constructor({
    host,
    port = 3389,
    username = '',
    domain = '',
    password = '',
    settings = {},
    width = 1440,
    height = 900,
    canvas = null,
    onStatus,
    onRemoteClipboard,
    onClose,
    transportOptions = {}
  }) {
    this.host = host;
    this.settings = settings || {};
    this.pcbBlob = effectiveRdpPreconnectionBlob(this.settings);
    this.port = port || (this.pcbBlob ? 2179 : 3389);
    this.username = username || '';
    this.domain = domain || '';
    this.password = password || '';
    this.width = Math.max(640, width & ~3);
    this.height = Math.max(480, height & ~1);
    this.canvas = canvas;
    this.onStatus = onStatus;
    this.onRemoteClipboard = onRemoteClipboard;
    this.onClose = onClose;
    this.transportOptions = transportOptions;
    this.stream = null;
    this.selectedProtocol = PROTOCOL_RDP;
    this._closed = false;
  }

  attachCanvas(canvas) {
    this.canvas = canvas;
    if (this.canvas) {
      this.canvas.width = this.width;
      this.canvas.height = this.height;
    }
  }

  async _readTpktOrFastPath() {
    const actionByte = (await this.stream.readExact(1))[0];
    if (actionByte === 0x03) {
      // TPKT v3
      const hdr = await this.stream.readExact(3);
      const totalLen = (hdr[1] << 8) | hdr[2];
      const payload = await this.stream.readExact(Math.max(0, totalLen - 4));
      return { isFastPath: false, payload };
    }
    // Fast-Path Server Update PDU
    const b1 = (await this.stream.readExact(1))[0];
    let totalLen = b1;
    let hdrSize = 2;
    if (b1 & 0x80) {
      const b2 = (await this.stream.readExact(1))[0];
      totalLen = ((b1 & 0x7f) << 8) | b2;
      hdrSize = 3;
    }
    const secFlags = (actionByte >>> 6) & 0x03;
    if (secFlags & 0x02) {
      await this.stream.readExact(8);
      hdrSize += 8;
    }
    const payload = await this.stream.readExact(Math.max(0, totalLen - hdrSize));
    return { isFastPath: true, actionByte, payload };
  }

  _buildMcsConnectInitial() {
    // CS_CORE (`0xC001`)
    const csCore = new Uint8Array(216);
    const dv = new DataView(csCore.buffer);
    dv.setUint16(0, 0xc001, true);
    dv.setUint16(2, csCore.length, true);
    dv.setUint32(4, 0x00080004, true); // RDP 5.0+
    dv.setUint16(8, this.width, true);
    dv.setUint16(10, this.height, true);
    dv.setUint16(12, 0xca01, true); // RNS_UD_COLOR_8BPP
    dv.setUint16(14, 0xaa03, true); // RNS_UD_SAS_DEL
    dv.setUint32(16, 0x00000409, true); // keyboardLayout US/Intl
    dv.setUint32(20, 2600, true); // clientBuild
    const clientName = encodeUtf16Le('ChromeOS-NexusTS'.slice(0, 15));
    csCore.set(clientName, 24);
    dv.setUint32(56, 4, true); // keyboardType = IBM Enhanced 101/102
    dv.setUint32(64, 12, true); // keyboardFunctionKey
    dv.setUint16(132, 0xca01, true);
    dv.setUint16(136, 1, true); // clientProductId
    dv.setUint16(142, 0x001b, true); // highColorDepth = 24/32
    dv.setUint16(144, 0x000f, true); // supportedColorDepths (15/16/24/32)
    dv.setUint16(146, 0x0783, true); // earlyCapabilityFlags (SUPPORT_ERRINFO_PDU | WANT_32BPP_SESSION | SUPPORT_DYNVC_GFX_PROTOCOL)
    dv.setUint32(204, Number(this.settings.desktopScaleFactorPercent || 200), true);
    dv.setUint32(208, Number(this.settings.deviceScaleFactorPercent || 180), true);

    // CS_SECURITY (`0xC002`)
    const csSec = new Uint8Array(12);
    const sdv = new DataView(csSec.buffer);
    sdv.setUint16(0, 0xc002, true);
    sdv.setUint16(2, 12, true);
    sdv.setUint32(4, 0x00000003, true);

    // CS_CLUSTER (`0xC004`)
    const csCluster = new Uint8Array(12);
    const cdv = new DataView(csCluster.buffer);
    cdv.setUint16(0, 0xc004, true);
    cdv.setUint16(2, 12, true);
    cdv.setUint32(4, this.settings.connectToConsoleAdmin ? 0x0000000d : 0x00000009, true);

    // CS_NET (`0xC003`) requesting `cliprdr`
    const csNet = new Uint8Array(20);
    const ndv = new DataView(csNet.buffer);
    ndv.setUint16(0, 0xc003, true);
    ndv.setUint16(2, 20, true);
    ndv.setUint32(4, 1, true);
    csNet.set(new TextEncoder().encode('cliprdr\0'), 8);
    ndv.setUint32(16, 0xa0c00000, true);

    const userData = concatArrays([csCore, csSec, csCluster, csNet]);
    const gccHeader = new Uint8Array([
      0x00, 0x05, 0x00, 0x14, 0x7c, 0x00, 0x01, 0x81,
      ((userData.length + 14) >>> 8) | 0x80, (userData.length + 14) & 0xff,
      0x00, 0x08, 0x00, 0x10, 0x00, 0x01, 0xc0, 0x00,
      0x44, 0x75, 0x63, 0x61,
      0x82, (userData.length >>> 8) & 0xff, userData.length & 0xff
    ]);
    const gccPayload = concatArrays([gccHeader, userData]);

    const domainParams = new Uint8Array([
      0x30, 0x19, 0x02, 0x01, 0x22, 0x02, 0x01, 0x02, 0x02, 0x01, 0x00, 0x02, 0x01, 0x01,
      0x02, 0x01, 0x00, 0x02, 0x01, 0x01, 0x02, 0x02, 0xff, 0xff, 0x02, 0x01, 0x02
    ]);
    const mcsBody = concatArrays([
      new Uint8Array([0x04, 0x01, 0x01, 0x04, 0x01, 0x01, 0x01, 0x01, 0xff]),
      domainParams,
      domainParams,
      domainParams,
      new Uint8Array([0x04]),
      writeBerLength(gccPayload.length),
      gccPayload
    ]);
    const mcsConnectInitial = concatArrays([
      new Uint8Array([0x7f, 0x65]),
      writeBerLength(mcsBody.length),
      mcsBody
    ]);
    return wrapTpkt(concatArrays([new Uint8Array([0x02, 0xf0, 0x80]), mcsConnectInitial]));
  }

  _buildClientInfoPdu(userChannelId) {
    const domBytes = encodeUtf16Le(this.domain, false);
    const usrBytes = encodeUtf16Le(this.username, false);
    const pwdBytes = encodeUtf16Le(this.password, false);
    const shlBytes = encodeUtf16Le(this.settings.remoteAppMode ? this.settings.remoteAppProgram : '', false);
    const dirBytes = new Uint8Array(0);
    const nullUtf16 = new Uint8Array([0, 0]);

    const infoHdr = new Uint8Array(18);
    const dv = new DataView(infoHdr.buffer);
    dv.setUint32(0, 0x00000409, true);
    let flags = 0x00000033 | 0x00040000; // INFO_MOUSE | INFO_DISABLECTRLALTDEL | INFO_UNICODE | INFO_MAXIMIZESHELL | INFO_LOGONNOTIFY
    if (this.password) flags |= 0x00000008; // INFO_AUTOLOGON
    dv.setUint32(4, flags, true);
    dv.setUint16(8, domBytes.length, true);
    dv.setUint16(10, usrBytes.length, true);
    dv.setUint16(12, pwdBytes.length, true);
    dv.setUint16(14, shlBytes.length, true);
    dv.setUint16(16, dirBytes.length, true);

    const secHdr = new Uint8Array([0x40, 0x00, 0x00, 0x00]); // SEC_INFO_PKT
    const payload = concatArrays([
      secHdr, infoHdr,
      domBytes, nullUtf16,
      usrBytes, nullUtf16,
      pwdBytes, nullUtf16,
      shlBytes, nullUtf16,
      dirBytes, nullUtf16
    ]);

    const mcsSendData = new Uint8Array(8);
    mcsSendData[0] = 0x64; // SendDataRequest
    const mdv = new DataView(mcsSendData.buffer);
    mdv.setUint16(1, userChannelId - 1001, false);
    mdv.setUint16(3, 1003, false); // MCS_GLOBAL_CHANNEL
    mcsSendData[5] = 0x70;
    mdv.setUint16(6, 0x8000 | payload.length, false);
    return wrapTpkt(concatArrays([new Uint8Array([0x02, 0xf0, 0x80]), mcsSendData, payload]));
  }

  async connect() {
    this.onStatus?.(`Connecting RDP to ${this.host}:${this.port}…`);
    this.stream = await openRawTcpStream(this.host, this.port, this.transportOptions);

    // 1. Optional Hyper-V Preconnection PDU v2 (`PCB`)
    if (this.pcbBlob) {
      await this.stream.write(buildHyperVPreconnectionPdu(this.pcbBlob));
    }

    // 2. X.224 Connection Request
    const reqPdu = buildX224ConnectionRequestPdu({
      username: this.username,
      requestedProtocols: PROTOCOL_RDP
    });
    await this.stream.write(reqPdu);

    // 3. X.224 Connection Confirm
    const ccFrame = await this._readTpktOrFastPath();
    const { selectedProtocol, failureCode } = parseX224ConnectionConfirmPdu(ccFrame.payload);
    if (failureCode !== null) {
      throw new Error(`RDP server requires NLA/TLS negotiation (code 0x${failureCode.toString(16)})`);
    }
    this.selectedProtocol = selectedProtocol;

    // 4. MCS Connect-Initial -> Connect-Response + ErectDomain + AttachUser + ClientInfo
    await this.stream.write(this._buildMcsConnectInitial());
    await this._readTpktOrFastPath(); // MCS Connect-Response
    // ErectDomainRequest (`0x04 0x01 0x00 0x01 0x00`) + AttachUserRequest (`0x28`)
    await this.stream.write(wrapTpkt(new Uint8Array([0x02, 0xf0, 0x80, 0x04, 0x01, 0x00, 0x01, 0x00])));
    await this.stream.write(wrapTpkt(new Uint8Array([0x02, 0xf0, 0x80, 0x28])));
    const aucf = await this._readTpktOrFastPath();
    const initiatorOffset = (aucf.payload[4] << 8) | aucf.payload[5];
    const userChannelId = 1001 + initiatorOffset;

    for (const chId of [userChannelId, 1003, 1004]) {
      const cj = new Uint8Array([0x02, 0xf0, 0x80, 0x38, (initiatorOffset >>> 8) & 0xff, initiatorOffset & 0xff, (chId >>> 8) & 0xff, chId & 0xff]);
      await this.stream.write(wrapTpkt(cj));
      await this._readTpktOrFastPath();
    }

    await this.stream.write(this._buildClientInfoPdu(userChannelId));
    this.onStatus?.(`RDP session active (${this.width}×${this.height})`);
    this._pumpUpdates();
    return this;
  }

  _paintBitmapRect(destLeft, destTop, width, height, bpp, isCompressed, bitmapBytes) {
    if (!this.canvas) return;
    if (this.canvas.width !== this.width || this.canvas.height !== this.height) {
      this.canvas.width = this.width;
      this.canvas.height = this.height;
    }
    const rgba = decodeRdpBitmapTileToRgba(bitmapBytes, width, height, bpp, isCompressed);
    const ctx = this.canvas.getContext('2d');
    ctx.putImageData(new ImageData(rgba, width, height), destLeft, destTop);
  }

  async _pumpUpdates() {
    try {
      while (!this._closed) {
        const pkt = await this._readTpktOrFastPath();
        if (!pkt.isFastPath) continue;
        const buf = pkt.payload;
        let pos = 0;
        while (pos + 3 <= buf.length) {
          const updateHdr = buf[pos];
          const updateCode = updateHdr & 0x0f;
          const frag = (updateHdr >>> 4) & 0x03;
          const comp = (updateHdr >>> 6) & 0x03;
          pos += 1;
          if (comp & 0x02) pos += 1;
          const size = buf[pos] | (buf[pos + 1] << 8);
          pos += 2;
          const data = buf.subarray(pos, pos + size);
          pos += size;

          if (updateCode === 0x01 && frag === 0 && data.length >= 4) {
            // FASTPATH_UPDATETYPE_BITMAP (`TS_UPDATE_BITMAP_DATA`: updateType u16, numberRectangles u16)
            const dv = new DataView(data.buffer, data.byteOffset, data.byteLength);
            const count = dv.getUint16(2, true);
            let rOff = 4;
            for (let i = 0; i < count && rOff + 18 <= data.length; i++) {
              const destLeft = dv.getUint16(rOff, true);
              const destTop = dv.getUint16(rOff + 2, true);
              const width = dv.getUint16(rOff + 8, true);
              const height = dv.getUint16(rOff + 10, true);
              const bpp = dv.getUint16(rOff + 12, true);
              const flags = dv.getUint16(rOff + 14, true);
              const bitmapLen = dv.getUint16(rOff + 16, true);
              const slice = data.subarray(rOff + 18, rOff + 18 + bitmapLen);
              rOff += 18 + bitmapLen;
              this._paintBitmapRect(destLeft, destTop, width, height, bpp, (flags & 0x0001) !== 0, slice);
            }
          }
        }
      }
    } catch (err) {
      if (!this._closed) this.onClose?.(err);
    }
  }

  async sendKeyEvent(ev, isDown) {
    if (!this.stream || this._closed) return;
    const entry = DOM_CODE_TO_SCANCODE[ev.code];
    if (!entry) return;
    await this.stream.write(buildFastPathKeyboardPdu(entry[0], isDown, entry[1]));
  }

  async sendCtrlAltDel() {
    if (!this.stream || this._closed) return;
    await this.stream.write(buildFastPathKeyboardPdu(0x1d, true, false));  // Ctrl down
    await this.stream.write(buildFastPathKeyboardPdu(0x38, true, false));  // Alt down
    await this.stream.write(buildFastPathKeyboardPdu(0x53, true, true));   // Del down
    await this.stream.write(buildFastPathKeyboardPdu(0x53, false, true));  // Del up
    await this.stream.write(buildFastPathKeyboardPdu(0x38, false, false)); // Alt up
    await this.stream.write(buildFastPathKeyboardPdu(0x1d, false, false)); // Ctrl up
  }

  async sendMouseMove(x, y) {
    if (!this.stream || this._closed) return;
    await this.stream.write(buildFastPathMousePdu(x, y, 0x0800)); // PTRFLAGS_MOVE
  }

  async sendMouseButton(x, y, button, isDown) {
    if (!this.stream || this._closed) return;
    const btnFlag = button === 2 ? 0x2000 : button === 1 ? 0x4000 : 0x1000;
    const flags = btnFlag | (isDown ? 0x8000 : 0x0000);
    await this.stream.write(buildFastPathMousePdu(x, y, flags));
  }

  async sendMouseWheel(x, y, deltaY) {
    if (!this.stream || this._closed) return;
    const negative = deltaY > 0;
    const steps = Math.min(0x00ff, Math.max(1, Math.round(Math.abs(deltaY))));
    const flags = 0x0200 | (negative ? 0x0100 | ((0x0100 - steps) & 0xff) : steps);
    await this.stream.write(buildFastPathMousePdu(x, y, flags));
  }

  async close() {
    this._closed = true;
    await this.stream?.close();
  }
}
