// `[MS-RDPBCGR]` + `[MS-CSSP]` Remote Desktop Protocol (RDP), Windows Terminal Services & Hyper-V VMConnect Client.
// Supports:
// - TLS 1.2 / TLS 1.3 (`PROTOCOL_SSL`) & NLA CredSSP v2–v6 + NTLMv2 (`PROTOCOL_HYBRID`) over `Direct Sockets` (`TCPSocket`)
// - Hyper-V Preconnection PDU v2 (`PCB` `<vm-id>;EnhancedMode=1` on `:2179`)
// - Full `[MS-RDPBCGR]` capability exchange (`DemandActive` -> `ConfirmActive` + `Synchronize` + `Control` + `FontList`,
//   including seamless `DeactivateAll` / logon-to-desktop re-activation)
// - Slow-Path & Fast-Path Bitmap/Surface Updates (Uncompressed 15/16/24/32-bpp, Interleaved RLE, and RDP 6.0 32-bpp Planar)
// - Fast-Path PC AT Scancodes (`Ctrl+Alt+Del`, `Win`, `F1–F12`), Mouse/Wheel, and `CLIPRDR` Bidirectional Text Clipboard.

import { effectiveRdpPreconnectionBlob } from '../core/vault-models.js';
import { openRawTcpStream } from './socket-transport.js';
import { performCredSspNla, upgradeTcpStreamToTls } from './tls-credssp.js';

export const PROTOCOL_RDP = 0x00000000;
export const PROTOCOL_SSL = 0x00000001;
export const PROTOCOL_HYBRID = 0x00000002;
export const PROTOCOL_HYBRID_EX = 0x00000008;

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

function writeBerLength(len) {
  if (len < 0x80) return new Uint8Array([len]);
  if (len <= 0xff) return new Uint8Array([0x81, len]);
  return new Uint8Array([0x82, (len >>> 8) & 0xff, len & 0xff]);
}

function writePerLength(len) {
  if (len < 0x80) return new Uint8Array([len]);
  return new Uint8Array([0x80 | ((len >>> 8) & 0x3f), len & 0xff]);
}

function readPerLength(buf, offset) {
  if (offset >= buf.length) return { length: 0, nextOffset: offset };
  const b0 = buf[offset];
  if ((b0 & 0x80) === 0) {
    return { length: b0, nextOffset: offset + 1 };
  }
  const b1 = buf[offset + 1] || 0;
  return { length: ((b0 & 0x3f) << 8) | b1, nextOffset: offset + 2 };
}

function concatArrays(chunks) {
  const out = new Uint8Array(chunks.reduce((a, c) => a + c.length, 0));
  let pos = 0;
  for (const c of chunks) {
    out.set(c, pos);
    pos += c.length;
  }
  return out;
}

function u16le(v) {
  return new Uint8Array([v & 0xff, (v >>> 8) & 0xff]);
}

function u32le(v) {
  const b = new Uint8Array(4);
  new DataView(b.buffer).setUint32(0, v >>> 0, true);
  return b;
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
export function buildX224ConnectionRequestPdu({
  username = '',
  requestedProtocols = PROTOCOL_HYBRID | PROTOCOL_SSL | PROTOCOL_RDP
} = {}) {
  const cleanUser = String(username || 'nexusts').replace(/[\r\n]/g, '').slice(0, 16);
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
  return new Uint8Array([0x04, 0x04, eventHeader, scancode & 0xff]);
}

/**
 * Builds a Fast-Path Mouse Pointer Input Event PDU (`[MS-RDPBCGR]` §2.2.8.1.2.2.3).
 */
export function buildFastPathMousePdu(x, y, pointerFlags) {
  const pkt = new Uint8Array(9);
  pkt[0] = 0x04; // 1 event
  pkt[1] = 0x09; // 9 bytes total
  pkt[2] = 0x20; // FASTPATH_INPUT_EVENT_MOUSE (1 << 5 = 0x20)
  const dv = new DataView(pkt.buffer);
  dv.setUint16(3, pointerFlags & 0xffff, true);
  dv.setUint16(5, Math.max(0, Math.round(x)) & 0xffff, true);
  dv.setUint16(7, Math.max(0, Math.round(y)) & 0xffff, true);
  return pkt;
}

/**
 * Decompresses RDP 6.0 Planar 32-bpp compressed bitmap streams (`[MS-RDPEGDI]` §2.2.2.5.1).
 */
function decodeRdp6PlanarToRgba(src, width, height) {
  const out = new Uint8ClampedArray(width * height * 4);
  for (let i = 3; i < out.length; i += 4) out[i] = 255;
  if (src.length < 1) return out;

  const hdr = src[0];
  const cll = hdr & 0x07;
  const cs = (hdr & 0x08) !== 0;
  const rle = (hdr & 0x10) !== 0;
  const na = (hdr & 0x20) !== 0;
  let pos = 1;

  const decodePlane = (planeW, planeH) => {
    const plane = new Uint8Array(planeW * planeH);
    if (!rle) {
      const n = Math.min(plane.length, src.length - pos);
      plane.set(src.subarray(pos, pos + n), 0);
      pos += n;
      return plane;
    }
    for (let y = 0; y < planeH; y++) {
      let x = 0;
      const rowBase = y * planeW;
      const prevBase = (y - 1) * planeW;
      while (x < planeW && pos < src.length) {
        const ctrl = src[pos++];
        let nRun = ctrl & 0x0f;
        let cRaw = (ctrl >>> 4) & 0x0f;
        if (nRun === 1) {
          nRun = cRaw + 16;
          cRaw = 0;
        } else if (nRun === 2) {
          if (cRaw === 15) {
            nRun = 47;
            cRaw = 0;
          } else {
            nRun = cRaw + 32;
            cRaw = 0;
          }
        }
        for (let r = 0; r < cRaw && x < planeW && pos < src.length; r++, x++) {
          const rawByte = src[pos++];
          if (y === 0) {
            plane[rowBase + x] = rawByte;
          } else {
            const delta = (rawByte & 1) ? -((rawByte + 1) >>> 1) : (rawByte >>> 1);
            plane[rowBase + x] = (plane[prevBase + x] + delta) & 0xff;
          }
        }
        const prevVal = x > 0 ? plane[rowBase + x - 1] : (y > 0 ? plane[prevBase + x] : 0);
        for (let k = 0; k < nRun && x < planeW; k++, x++) {
          plane[rowBase + x] = y === 0 ? prevVal : plane[prevBase + x];
        }
      }
    }
    return plane;
  };

  const alphaPlane = na ? null : decodePlane(width, height);
  const cW = cs && cll > 0 ? Math.ceil(width / 2) : width;
  const cH = cs && cll > 0 ? Math.ceil(height / 2) : height;
  const p1 = decodePlane(width, height);
  const p2 = decodePlane(cW, cH);
  const p3 = decodePlane(cW, cH);

  const clamp8 = (v) => (v < 0 ? 0 : v > 255 ? 255 : v);

  for (let srcY = 0; srcY < height; srcY++) {
    const dstY = height - 1 - srcY;
    for (let x = 0; x < width; x++) {
      const idx = srcY * width + x;
      const cIdx = cs && cll > 0 ? Math.floor(srcY / 2) * cW + Math.floor(x / 2) : idx;
      let r, g, b;
      if (cll === 0) {
        r = p1[idx];
        g = p2[cIdx];
        b = p3[cIdx];
      } else {
        const shift = cll - 1;
        const Y = p1[idx];
        const Co = ((p2[cIdx] << shift) - 0x80 * (1 << shift)) >> shift;
        const Cg = ((p3[cIdx] << shift) - 0x80 * (1 << shift)) >> shift;
        const t = Y - (Cg >> 1);
        g = clamp8(t + Cg);
        b = clamp8(t - (Co >> 1));
        r = clamp8(b + Co);
      }
      const dOff = (dstY * width + x) * 4;
      out[dOff] = r;
      out[dOff + 1] = g;
      out[dOff + 2] = b;
      out[dOff + 3] = alphaPlane ? alphaPlane[idx] : 255;
    }
  }
  return out;
}

/**
 * Decompresses uncompressed bottom-up 15/16/24/32-bpp, MS-RDPBCGR Interleaved RLE, or RDP 6.0 32-bpp Planar into RGBA8888.
 */
export function decodeRdpBitmapTileToRgba(srcBytes, width, height, bpp, isCompressed) {
  if (isCompressed && bpp === 32) {
    return decodeRdp6PlanarToRgba(srcBytes, width, height);
  }

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

  // MS-RDPBCGR §3.1.9.1.1 Interleaved RLE decompression (bottom-up scanlines, full standard + mega opcodes)
  const rawPixels = new Uint8Array(width * height * bytesPerPixel);
  const rowDelta = width * bytesPerPixel;
  let srcPos = 0;
  let dstPos = 0;
  let fgPel = new Uint8Array(bytesPerPixel);
  if (bytesPerPixel >= 1) fgPel.fill(0xff);
  let insertFgPel = false;

  // Skip optional 8-byte TS_CD_HEADER (`cbCompFirstRowSize == 0`) if present
  if (srcBytes.length >= 8 && srcBytes[0] === 0 && srcBytes[1] === 0) {
    const mainBodySize = srcBytes[2] | (srcBytes[3] << 8);
    if (mainBodySize > 0 && mainBodySize <= srcBytes.length - 8) {
      srcPos = 8;
    }
  }

  const readMegaLen = () => {
    const lo = srcBytes[srcPos++] || 0;
    const hi = srcBytes[srcPos++] || 0;
    return lo | (hi << 8);
  };

  while (srcPos < srcBytes.length && dstPos < rawPixels.length) {
    const hdr = srcBytes[srcPos++];

    if (hdr === 0xf9 || hdr === 0xfa) {
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
      continue;
    }
    if (hdr === 0xfd) {
      for (let b = 0; b < bytesPerPixel && dstPos < rawPixels.length; b++) rawPixels[dstPos++] = 0xff;
      insertFgPel = false;
      continue;
    }
    if (hdr === 0xfe) {
      for (let b = 0; b < bytesPerPixel && dstPos < rawPixels.length; b++) rawPixels[dstPos++] = 0x00;
      insertFgPel = false;
      continue;
    }
    if (hdr === 0xff) continue; // SPECIAL_START_OE

    let code = hdr >>> 5;
    let runLen = hdr & 0x1f;
    let isMegaMega = false;

    if (hdr >= 0xf0 && hdr <= 0xf8) {
      isMegaMega = true;
      code = hdr & 0x0f; // 0..8
    } else if (code === 6 || code === 7) {
      code = hdr >>> 4; // 12..14
      runLen = hdr & 0x0f;
    }

    const extractRunLength = (baseLen, megaBase) => {
      if (isMegaMega) return readMegaLen();
      if (baseLen !== 0) return baseLen;
      if (srcPos >= srcBytes.length) return 0;
      return srcBytes[srcPos++] + megaBase;
    };

    if (code === 0) {
      // REGULAR_BG_RUN / MEGA_MEGA_BG_RUN
      const count = extractRunLength(runLen, 32);
      for (let i = 0; i < count && dstPos + bytesPerPixel <= rawPixels.length; i++) {
        for (let b = 0; b < bytesPerPixel; b++) {
          const above = dstPos >= rowDelta ? rawPixels[dstPos - rowDelta + b] : 0;
          rawPixels[dstPos + b] = insertFgPel && i === 0 ? above ^ fgPel[b] : above;
        }
        dstPos += bytesPerPixel;
      }
      insertFgPel = true;
    } else if (code === 1 || code === 12 || code === 6) {
      // REGULAR_FG_RUN (1) / LITE_SET_FG_FG_RUN (12) / MEGA_MEGA_SET_FG_RUN (6)
      const count = extractRunLength(runLen, code === 12 ? 16 : 32);
      if (code === 12 || code === 6) {
        fgPel = srcBytes.slice(srcPos, srcPos + bytesPerPixel);
        srcPos += bytesPerPixel;
      }
      for (let i = 0; i < count && dstPos + bytesPerPixel <= rawPixels.length; i++) {
        for (let b = 0; b < bytesPerPixel; b++) {
          const above = dstPos >= rowDelta ? rawPixels[dstPos - rowDelta + b] : 0;
          rawPixels[dstPos + b] = above ^ fgPel[b];
        }
        dstPos += bytesPerPixel;
      }
      insertFgPel = false;
    } else if (code === 2 || code === 13 || code === 7) {
      // REGULAR_FG_BG_IMAGE (2) / LITE_SET_FG_FGBG_IMAGE (13) / MEGA_MEGA_SET_FGBG_IMAGE (7)
      let totalPixels;
      if (isMegaMega) {
        totalPixels = readMegaLen();
      } else if (code === 13) {
        totalPixels = runLen === 0 ? (srcBytes[srcPos++] || 0) + 16 : runLen * 8;
      } else {
        totalPixels = runLen === 0 ? (srcBytes[srcPos++] || 0) + 1 : runLen * 8;
      }
      if (code === 13 || code === 7) {
        fgPel = srcBytes.slice(srcPos, srcPos + bytesPerPixel);
        srcPos += bytesPerPixel;
      }
      let rem = totalPixels;
      while (rem > 0 && srcPos < srcBytes.length && dstPos + bytesPerPixel <= rawPixels.length) {
        const mask = srcBytes[srcPos++];
        const nBits = Math.min(8, rem);
        for (let bit = 0; bit < nBits && dstPos + bytesPerPixel <= rawPixels.length; bit++) {
          const isFg = ((mask >>> bit) & 1) !== 0;
          for (let b = 0; b < bytesPerPixel; b++) {
            const above = dstPos >= rowDelta ? rawPixels[dstPos - rowDelta + b] : 0;
            rawPixels[dstPos + b] = isFg ? above ^ fgPel[b] : above;
          }
          dstPos += bytesPerPixel;
        }
        rem -= nBits;
      }
      insertFgPel = false;
    } else if (code === 3) {
      // REGULAR_COLOR_RUN / MEGA_MEGA_COLOR_RUN
      const count = extractRunLength(runLen, 32);
      const color = srcBytes.subarray(srcPos, srcPos + bytesPerPixel);
      srcPos += bytesPerPixel;
      for (let i = 0; i < count && dstPos + bytesPerPixel <= rawPixels.length; i++) {
        rawPixels.set(color, dstPos);
        dstPos += bytesPerPixel;
      }
      insertFgPel = false;
    } else if (code === 4) {
      // REGULAR_COLOR_IMAGE / MEGA_MEGA_COLOR_IMAGE
      const count = extractRunLength(runLen, 32);
      const byteCount = count * bytesPerPixel;
      const copySlice = srcBytes.subarray(srcPos, Math.min(srcBytes.length, srcPos + byteCount));
      srcPos += copySlice.length;
      const fit = Math.min(copySlice.length, rawPixels.length - dstPos);
      rawPixels.set(copySlice.subarray(0, fit), dstPos);
      dstPos += fit;
      insertFgPel = false;
    } else if (code === 5 || code === 14 || code === 8) {
      // DITHERED_RUN (`count` pairs of 2 alternating pixels!)
      const pairs = extractRunLength(runLen, code === 14 ? 16 : 32);
      const cA = srcBytes.subarray(srcPos, srcPos + bytesPerPixel);
      srcPos += bytesPerPixel;
      const cB = srcBytes.subarray(srcPos, srcPos + bytesPerPixel);
      srcPos += bytesPerPixel;
      for (let i = 0; i < pairs; i++) {
        if (dstPos + bytesPerPixel <= rawPixels.length) {
          rawPixels.set(cA, dstPos);
          dstPos += bytesPerPixel;
        }
        if (dstPos + bytesPerPixel <= rawPixels.length) {
          rawPixels.set(cB, dstPos);
          dstPos += bytesPerPixel;
        }
      }
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
    this.width = Math.max(640, (Number(this.settings.desktopWidth) || width) & ~3);
    this.height = Math.max(480, (Number(this.settings.desktopHeight) || height) & ~1);
    this.canvas = canvas;
    this.onStatus = onStatus;
    this.onRemoteClipboard = onRemoteClipboard;
    this.onClose = onClose;
    this.transportOptions = transportOptions;
    this.stream = null;
    this.selectedProtocol = PROTOCOL_RDP;
    this.userChannelId = 1007;
    this.ioChannelId = 1003;
    this.cliprdrChannelId = null;
    this.shareId = 0;
    this._fastPathFragBuf = new Uint8Array(0);
    this._cliprdrFragBuf = new Uint8Array(0);
    this._localClipboardText = '';
    this._backingRgba = new Uint8ClampedArray(this.width * this.height * 4);
    this._hasFrame = false;
    this._closed = false;
  }

  attachCanvas(canvas) {
    this.canvas = canvas;
    if (!this.canvas) return;
    if (this.canvas.width !== this.width || this.canvas.height !== this.height) {
      this.canvas.width = this.width;
      this.canvas.height = this.height;
    }
    if (this._hasFrame) {
      const ctx = this.canvas.getContext('2d');
      if (ctx) {
        ctx.putImageData(new ImageData(this._backingRgba, this.width, this.height), 0, 0);
      }
    }
  }

  async _readTpktOrFastPath() {
    const actionByte = (await this.stream.readExact(1))[0];
    if (actionByte === 0x03) {
      const hdr = await this.stream.readExact(3);
      const totalLen = (hdr[1] << 8) | hdr[2];
      const payload = await this.stream.readExact(Math.max(0, totalLen - 4));
      return { isFastPath: false, payload };
    }
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

  _wrapMcsSendData(channelId, payload) {
    const initiator = Math.max(0, this.userChannelId - 1001);
    const hdr = new Uint8Array(6);
    hdr[0] = 0x64; // SendDataRequest
    const dv = new DataView(hdr.buffer);
    dv.setUint16(1, initiator, false);
    dv.setUint16(3, channelId, false);
    hdr[5] = 0x70;
    return wrapTpkt(concatArrays([new Uint8Array([0x02, 0xf0, 0x80]), hdr, writePerLength(payload.length), payload]));
  }

  _buildMcsConnectInitial() {
    // `[MS-RDPBCGR]` §2.2.1.3.2 `TS_UD_CS_CORE` (exact 216-byte layout including `serverSelectedProtocol` at offset 212)
    const csCore = new Uint8Array(216);
    const dv = new DataView(csCore.buffer);
    dv.setUint16(0, 0xc001, true);
    dv.setUint16(2, csCore.length, true);
    dv.setUint32(4, 0x00080004, true); // RDP 5.0+
    dv.setUint16(8, this.width, true);
    dv.setUint16(10, this.height, true);
    dv.setUint16(12, 0xca01, true); // RNS_UD_COLOR_8BPP
    dv.setUint16(14, 0xaa03, true); // RNS_UD_SAS_DEL
    dv.setUint32(16, 0x00000409, true); // keyboardLayout US
    dv.setUint32(20, 19041, true); // clientBuild
    const clientName = encodeUtf16Le('ChromeOS-NexusT'.slice(0, 15));
    csCore.set(clientName, 24);
    dv.setUint32(56, 4, true); // keyboardType = IBM Enhanced 101/102
    dv.setUint32(60, 0, true); // keyboardSubType
    dv.setUint32(64, 12, true); // keyboardFunctionKey
    dv.setUint16(132, 0xca01, true); // postBeta2ColorDepth
    dv.setUint16(134, 1, true); // clientProductId
    dv.setUint32(136, 0, true); // serialNumber
    dv.setUint16(140, 0x0018, true); // highColorDepth = HIGH_COLOR_24BPP (0x0018)
    dv.setUint16(142, 0x0007, true); // supportedColorDepths = 24BPP | 16BPP | 15BPP
    dv.setUint16(144, 0x0001, true); // earlyCapabilityFlags = RNS_UD_CS_SUPPORT_ERRINFO_PDU
    csCore[210] = 0x06; // CONNECTION_TYPE_LAN
    dv.setUint32(212, this.selectedProtocol >>> 0, true); // serverSelectedProtocol MUST match X.224 confirm!

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

  _parseServerChannelsFromMcsResponse(mcsRespPayload) {
    const extraChannels = [];
    // Scan GCC UserData blocks for SC_NET (`0x0C03`): header(4) + MCSChannelId(u16) + channelCount(u16) + u16[channelCount]
    for (let i = 0; i + 8 <= mcsRespPayload.length; i++) {
      if (mcsRespPayload[i] === 0x03 && mcsRespPayload[i + 1] === 0x0c) {
        const dv = new DataView(mcsRespPayload.buffer, mcsRespPayload.byteOffset + i, mcsRespPayload.byteLength - i);
        const blockLen = dv.getUint16(2, true);
        const ioChan = dv.getUint16(4, true);
        const count = dv.getUint16(6, true);
        if (ioChan >= 1001 && ioChan <= 1100 && blockLen === 8 + count * 2 + (count % 2 === 1 ? 2 : 0) || (ioChan === 1003 && count <= 16)) {
          this.ioChannelId = ioChan;
          for (let c = 0; c < count && 8 + c * 2 + 2 <= dv.byteLength; c++) {
            const chId = dv.getUint16(8 + c * 2, true);
            extraChannels.push(chId);
          }
          if (extraChannels.length > 0) this.cliprdrChannelId = extraChannels[0];
          break;
        }
      }
    }
    return extraChannels;
  }

  _buildClientInfoPdu() {
    const domBytes = encodeUtf16Le(this.domain, false);
    const usrBytes = encodeUtf16Le(this.username, false);
    const pwdBytes = encodeUtf16Le(this.password, false);
    const shlBytes = encodeUtf16Le(this.settings.remoteAppMode ? this.settings.remoteAppProgram : '', false);
    const dirBytes = new Uint8Array(0);
    const nullUtf16 = new Uint8Array([0, 0]);

    const infoHdr = new Uint8Array(18);
    const dv = new DataView(infoHdr.buffer);
    dv.setUint32(0, 0x00000409, true);
    // INFO_MOUSE | INFO_DISABLECTRLALTDEL | INFO_UNICODE | INFO_MAXIMIZESHELL | INFO_LOGONNOTIFY | INFO_ENABLEWINDOWSKEY
    let flags = 0x00000033 | 0x00000100 | 0x00040000;
    if (this.password) flags |= 0x00000008; // INFO_AUTOLOGON
    dv.setUint32(4, flags >>> 0, true);
    dv.setUint16(8, domBytes.length, true);
    dv.setUint16(10, usrBytes.length, true);
    dv.setUint16(12, pwdBytes.length, true);
    dv.setUint16(14, shlBytes.length, true);
    dv.setUint16(16, dirBytes.length, true);

    // Extended Info Packet (clientAddressFamily=AF_INET, clientAddress="127.0.0.1\0", clientDir, TimeZone + performanceFlags)
    const addrUtf16 = encodeUtf16Le('127.0.0.1', true);
    const cliDirUtf16 = encodeUtf16Le('C:\\Windows\\System32\\mstscax.dll', true);
    const extTail = new Uint8Array(172 + 4 + 4); // TIME_ZONE_INFORMATION(172) + clientSessionId(4) + performanceFlags(4)
    new DataView(extTail.buffer).setUint32(176, 0x00000080, true); // PERF_ENABLE_FONT_SMOOTHING

    const extInfo = concatArrays([
      u16le(0x0002),
      u16le(addrUtf16.length),
      addrUtf16,
      u16le(cliDirUtf16.length),
      cliDirUtf16,
      extTail
    ]);

    const secHdr = new Uint8Array([0x40, 0x00, 0x00, 0x00]); // SEC_INFO_PKT
    const payload = concatArrays([
      secHdr,
      infoHdr,
      domBytes, nullUtf16,
      usrBytes, nullUtf16,
      pwdBytes, nullUtf16,
      shlBytes, nullUtf16,
      dirBytes, nullUtf16,
      extInfo
    ]);
    return this._wrapMcsSendData(this.ioChannelId, payload);
  }

  _buildConfirmActivePdu() {
    const capSet = (type, bodyBytes) => concatArrays([u16le(type), u16le(4 + bodyBytes.length), bodyBytes]);

    // 1. CAPSTYPE_GENERAL (0x0001, 24 bytes total -> 20 body)
    const genBody = new Uint8Array(20);
    const gdv = new DataView(genBody.buffer);
    gdv.setUint16(0, 0x0004, true); // OSMAJORTYPE_UNIX
    gdv.setUint16(2, 0x0000, true);
    gdv.setUint16(4, 0x0200, true); // TS_CAPS_PROTOCOLVERSION
    // extraFlags: FASTPATH_OUTPUT_SUPPORTED (0x0001) | NO_BITMAP_COMPRESSION_HDR (0x0400) | LONG_CREDENTIALS_SUPPORTED (0x0004)
    gdv.setUint16(10, 0x0405, true);

    // 2. CAPSTYPE_BITMAP (0x0002, 28 bytes total -> 24 body)
    const bmpBody = new Uint8Array(24);
    const bdv = new DataView(bmpBody.buffer);
    bdv.setUint16(0, 24, true); // preferredBitsPerPixel = 24-bit TrueColor
    bdv.setUint16(2, 1, true);  // receive1BitPerPixel
    bdv.setUint16(4, 1, true);  // receive4BitsPerPixel
    bdv.setUint16(6, 1, true);  // receive8BitsPerPixel
    bdv.setUint16(8, this.width, true);
    bdv.setUint16(10, this.height, true);
    bdv.setUint16(14, 1, true); // desktopResizeFlag = TRUE
    bdv.setUint16(16, 1, true); // bitmapCompressionFlag = TRUE
    bmpBody[20] = 1;            // multipleRectangleSupport = TRUE

    // 3. CAPSTYPE_ORDER (0x0003, 88 bytes total -> 84 body, zero primary orders -> server sends clean bitmaps)
    const ordBody = new Uint8Array(84);
    const odv = new DataView(ordBody.buffer);
    odv.setUint16(20, 1, true);
    odv.setUint16(22, 20, true);
    odv.setUint16(28, 0x002a, true); // NEGOTIATEORDERSUPPORT | ZEROBOUNDSDELTASSUPPORT | COLORINDEXSUPPORT

    // 4. CAPSTYPE_BITMAPCACHE (0x0004, 40 bytes total -> 36 body, 0 entries so no offscreen cache misses)
    const cacheBody = new Uint8Array(36);

    // 5. CAPSTYPE_POINTER (0x0008, 10 bytes total -> 6 body)
    const ptrBody = new Uint8Array(6);
    const pdv = new DataView(ptrBody.buffer);
    pdv.setUint16(0, 1, true);
    pdv.setUint16(2, 20, true);
    pdv.setUint16(4, 20, true);

    // 6. CAPSTYPE_INPUT (0x000D, 88 bytes total -> 84 body)
    const inpBody = new Uint8Array(84);
    const idv = new DataView(inpBody.buffer);
    // INPUT_FLAG_SCANCODES (0x01) | INPUT_FLAG_MOUSEX (0x04) | INPUT_FLAG_FASTPATH_INPUT (0x08) | INPUT_FLAG_UNICODE (0x10) | INPUT_FLAG_FASTPATH_INPUT2 (0x20)
    idv.setUint16(0, 0x003d, true);
    idv.setUint32(4, 0x00000409, true);
    idv.setUint32(8, 4, true);
    idv.setUint32(16, 12, true);

    // 7. CAPSTYPE_SOUND (0x000C, 8 bytes total -> 4 body)
    const sndBody = new Uint8Array(4);

    // 8. CAPSTYPE_VIRTUALCHANNEL (0x0014, 12 bytes total -> 8 body)
    const vcBody = new Uint8Array(8);
    new DataView(vcBody.buffer).setUint32(4, 1600, true);

    const capsList = [
      capSet(0x0001, genBody),
      capSet(0x0002, bmpBody),
      capSet(0x0003, ordBody),
      capSet(0x0004, cacheBody),
      capSet(0x0008, ptrBody),
      capSet(0x000d, inpBody),
      capSet(0x000c, sndBody),
      capSet(0x0014, vcBody)
    ];
    const combinedCaps = concatArrays(capsList);
    const srcDesc = new TextEncoder().encode('MSTSC\0');

    const confirmBody = concatArrays([
      u32le(this.shareId),
      u16le(0x03ea), // originatorId = 1002
      u16le(srcDesc.length),
      u16le(combinedCaps.length),
      srcDesc,
      u16le(capsList.length),
      u16le(0),
      combinedCaps
    ]);

    const shareControlHdr = concatArrays([
      u16le(6 + confirmBody.length),
      u16le(0x0013), // PDUTYPE_CONFIRMACTIVEPDU (0x10 | 0x03)
      u16le(this.userChannelId)
    ]);
    return this._wrapMcsSendData(this.ioChannelId, concatArrays([shareControlHdr, confirmBody]));
  }

  _buildShareDataPdu(pduType2, bodyBytes) {
    const uncompressedLen = 18 + bodyBytes.length;
    const shareData = new Uint8Array(18 + bodyBytes.length);
    const dv = new DataView(shareData.buffer);
    dv.setUint16(0, uncompressedLen, true);
    dv.setUint16(2, 0x0017, true); // PDUTYPE_DATAPDU (0x10 | 0x07)
    dv.setUint16(4, this.userChannelId, true);
    dv.setUint32(6, this.shareId >>> 0, true);
    shareData[10] = 0; // pad1
    shareData[11] = 1; // STREAM_LOW
    dv.setUint16(12, 4 + bodyBytes.length, true); // uncompressedLength
    shareData[14] = pduType2 & 0xff;
    shareData[15] = 0; // compressedType
    dv.setUint16(16, 0, true); // compressedLength
    shareData.set(bodyBytes, 18);
    return this._wrapMcsSendData(this.ioChannelId, shareData);
  }

  async _respondToDemandActive(shareId) {
    this.shareId = shareId >>> 0;
    // 1. Confirm Active PDU
    await this.stream.write(this._buildConfirmActivePdu());
    // 2. Synchronize PDU (PDUTYPE2_SYNCHRONIZE = 31)
    await this.stream.write(this._buildShareDataPdu(31, concatArrays([u16le(1), u16le(this.userChannelId)])));
    // 3. Control PDU - Cooperate (PDUTYPE2_CONTROL = 20, action = 4)
    await this.stream.write(this._buildShareDataPdu(20, concatArrays([u16le(4), u16le(0), u32le(0)])));
    // 4. Control PDU - Request Control (PDUTYPE2_CONTROL = 20, action = 1)
    await this.stream.write(this._buildShareDataPdu(20, concatArrays([u16le(1), u16le(0), u32le(0)])));
    // 5. Font List PDU (PDUTYPE2_FONTLIST = 39, listFlags = 3, entrySize = 50)
    await this.stream.write(this._buildShareDataPdu(39, concatArrays([u16le(0), u16le(0), u16le(0x0003), u16le(0x0032)])));
    this.onStatus?.(`Connected • ${this.width}×${this.height} (${this._securityLabel()})`);
  }

  _securityLabel() {
    if (this.selectedProtocol & PROTOCOL_HYBRID) return 'TLS + NLA';
    if (this.selectedProtocol & PROTOCOL_SSL) return 'TLS';
    return 'Standard RDP';
  }

  async _openAndNegotiateX224(requestedProtocols) {
    const rawStream = await openRawTcpStream(this.host, this.port, this.transportOptions);
    if (this.pcbBlob) {
      await rawStream.write(buildHyperVPreconnectionPdu(this.pcbBlob));
    }
    this.stream = rawStream;
    await this.stream.write(
      buildX224ConnectionRequestPdu({
        username: this.username,
        requestedProtocols
      })
    );
    const ccFrame = await this._readTpktOrFastPath();
    return parseX224ConnectionConfirmPdu(ccFrame.payload);
  }

  async connect() {
    this.onStatus?.(`Connecting RDP to ${this.host}:${this.port}…`);

    const nlaAllowed = this.settings.enableNla !== false;
    let initialMask = nlaAllowed ? (PROTOCOL_HYBRID | PROTOCOL_SSL | PROTOCOL_RDP) : (PROTOCOL_SSL | PROTOCOL_RDP);
    if (this.settings.forceLegacyRdpSecurity === true) {
      initialMask = PROTOCOL_RDP;
    }

    let neg = await this._openAndNegotiateX224(initialMask);
    if (neg.failureCode !== null) {
      await this.stream.close();
      // Retry automatically with matching protocol requirement (`SSL_NOT_ALLOWED = 0x02` -> `PROTOCOL_RDP`; `HYBRID_REQUIRED = 0x05` -> `PROTOCOL_HYBRID | PROTOCOL_SSL`)
      if (neg.failureCode === 0x00000002) {
        neg = await this._openAndNegotiateX224(PROTOCOL_RDP);
      } else if (neg.failureCode === 0x00000005 || neg.failureCode === 0x00000001) {
        neg = await this._openAndNegotiateX224(PROTOCOL_HYBRID | PROTOCOL_SSL);
      }
      if (neg.failureCode !== null) {
        throw new Error(`RDP negotiation rejected by server (code 0x${neg.failureCode.toString(16)})`);
      }
    }

    this.selectedProtocol = neg.selectedProtocol;

    // Upgrade in-place to TLS 1.2 / TLS 1.3 + CredSSP NLA when negotiated!
    if ((this.selectedProtocol & (PROTOCOL_SSL | PROTOCOL_HYBRID | PROTOCOL_HYBRID_EX)) !== 0) {
      this.onStatus?.(`Upgrading ${this.host}:${this.port} to TLS…`);
      const tlsInfo = await upgradeTcpStreamToTls(this.stream, { serverName: this.host, allowTls13: true });
      this.stream = tlsInfo.stream;

      if ((this.selectedProtocol & (PROTOCOL_HYBRID | PROTOCOL_HYBRID_EX)) !== 0) {
        this.onStatus?.(`Authenticating NLA (CredSSP/NTLMv2) as ${this.domain ? `${this.domain}\\` : ''}${this.username}…`);
        await performCredSspNla(this.stream, {
          domain: this.domain,
          username: this.username,
          password: this.password,
          serverPublicKeyBytes: tlsInfo.serverPublicKeyBytes,
          targetHost: this.host
        });
        if ((this.selectedProtocol & PROTOCOL_HYBRID_EX) !== 0) {
          const authRes = await this.stream.readExact(4);
          const resCode = new DataView(authRes.buffer, authRes.byteOffset, 4).getUint32(0, true);
          if (resCode !== 0) throw new Error(`Early User Authorization failed (0x${resCode.toString(16)})`);
        }
      }
    }

    // MCS Connect-Initial -> Connect-Response
    this.onStatus?.('Negotiating MCS channels & desktop capabilities…');
    await this.stream.write(this._buildMcsConnectInitial());
    const mcsResp = await this._readTpktOrFastPath();
    const extraChannels = this._parseServerChannelsFromMcsResponse(mcsResp.payload);

    // MCS ErectDomainRequest (`0x04 0x01 0x00 0x01 0x00`) + AttachUserRequest (`0x28`)
    await this.stream.write(wrapTpkt(new Uint8Array([0x02, 0xf0, 0x80, 0x04, 0x01, 0x00, 0x01, 0x00])));
    await this.stream.write(wrapTpkt(new Uint8Array([0x02, 0xf0, 0x80, 0x28])));
    const aucf = await this._readTpktOrFastPath();
    const initiatorOffset = (aucf.payload[4] << 8) | aucf.payload[5];
    this.userChannelId = 1001 + initiatorOffset;

    const channelsToJoin = [...new Set([this.userChannelId, this.ioChannelId, ...extraChannels])];
    for (const chId of channelsToJoin) {
      const cj = new Uint8Array([
        0x02, 0xf0, 0x80, 0x38,
        (initiatorOffset >>> 8) & 0xff, initiatorOffset & 0xff,
        (chId >>> 8) & 0xff, chId & 0xff
      ]);
      await this.stream.write(wrapTpkt(cj));
      await this._readTpktOrFastPath();
    }

    await this.stream.write(this._buildClientInfoPdu());
    this.onStatus?.(`RDP session established (${this.width}×${this.height})`);
    this._pumpUpdates();
    return this;
  }

  _paintBitmapRect(destLeft, destTop, width, height, bpp, isCompressed, bitmapBytes) {
    if (width <= 0 || height <= 0) return;
    const rgba = decodeRdpBitmapTileToRgba(bitmapBytes, width, height, bpp, isCompressed);
    if (this._backingRgba.length !== this.width * this.height * 4) {
      this._backingRgba = new Uint8ClampedArray(this.width * this.height * 4);
    }
    for (let row = 0; row < height; row++) {
      const dy = destTop + row;
      if (dy < 0 || dy >= this.height) continue;
      const copyW = Math.min(width, this.width - Math.max(0, destLeft));
      if (copyW <= 0) continue;
      const srcStart = row * width * 4;
      const dstStart = (dy * this.width + Math.max(0, destLeft)) * 4;
      this._backingRgba.set(rgba.subarray(srcStart, srcStart + copyW * 4), dstStart);
    }
    this._hasFrame = true;

    if (!this.canvas) return;
    if (this.canvas.width !== this.width || this.canvas.height !== this.height) {
      this.canvas.width = this.width;
      this.canvas.height = this.height;
    }
    const ctx = this.canvas.getContext('2d');
    if (ctx) {
      ctx.putImageData(new ImageData(rgba, width, height), destLeft, destTop);
    }
  }

  _parseAndPaintBitmapUpdateData(data) {
    if (data.length < 4) return;
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
      const slice = data.subarray(rOff + 18, Math.min(data.length, rOff + 18 + bitmapLen));
      rOff += 18 + bitmapLen;
      this._paintBitmapRect(destLeft, destTop, width, height, bpp, (flags & 0x0001) !== 0, slice);
    }
  }

  _sendCliprdrPdu(msgType, msgFlags, bodyBytes) {
    if (!this.cliprdrChannelId || !this.stream || this._closed) return;
    const clipPdu = concatArrays([u16le(msgType), u16le(msgFlags), u32le(bodyBytes.length), bodyBytes]);
    // Static Virtual Channel header (totalLength u32le, CHANNEL_FLAG_FIRST | CHANNEL_FLAG_LAST = 0x03 u32le)
    const vcPacket = concatArrays([u32le(clipPdu.length), u32le(0x00000003), clipPdu]);
    this.stream.write(this._wrapMcsSendData(this.cliprdrChannelId, vcPacket)).catch(() => {});
  }

  _handleCliprdrPacket(vcPayload) {
    if (vcPayload.length < 8) return;
    const dv = new DataView(vcPayload.buffer, vcPayload.byteOffset, vcPayload.byteLength);
    const vcFlags = dv.getUint32(4, true);
    const chunk = vcPayload.subarray(8);
    if (vcFlags & 0x01) this._cliprdrFragBuf = new Uint8Array(0);
    this._cliprdrFragBuf = concatArrays([this._cliprdrFragBuf, chunk]);
    if ((vcFlags & 0x02) === 0) return;

    const pdu = this._cliprdrFragBuf;
    this._cliprdrFragBuf = new Uint8Array(0);
    if (pdu.length < 8) return;
    const pdv = new DataView(pdu.buffer, pdu.byteOffset, pdu.byteLength);
    const msgType = pdv.getUint16(0, true);
    const msgFlags = pdv.getUint16(2, true);
    const dataLen = pdv.getUint32(4, true);
    const body = pdu.subarray(8, Math.min(pdu.length, 8 + dataLen));

    if (msgType === 0x0001) {
      // CB_MONITOR_READY -> send CB_CLIP_CAPS (CB_USE_LONG_FORMAT_NAMES = 0x02) + empty CB_FORMAT_LIST
      const capsBody = concatArrays([u16le(1), u16le(0), u16le(1), u16le(12), u32le(2), u32le(0x00000002)]);
      this._sendCliprdrPdu(0x0007, 0, capsBody);
      this._sendCliprdrPdu(0x0002, 0, new Uint8Array(0));
    } else if (msgType === 0x0002) {
      // Server CB_FORMAT_LIST -> acknowledge (`CB_RESPONSE_OK = 0x0001`) and request `CF_UNICODETEXT (13)`
      this._sendCliprdrPdu(0x0003, 0x0001, new Uint8Array(0));
      this._sendCliprdrPdu(0x0004, 0, u32le(13));
    } else if (msgType === 0x0004 && body.length >= 4) {
      // Server CB_FORMAT_DATA_REQUEST -> reply with `this._localClipboardText` in UTF-16LE
      const reqFmt = new DataView(body.buffer, body.byteOffset, 4).getUint32(0, true);
      if (reqFmt === 13) {
        this._sendCliprdrPdu(0x0005, 0x0001, encodeUtf16Le(this._localClipboardText.replace(/\r?\n/g, '\r\n'), true));
      } else {
        this._sendCliprdrPdu(0x0005, 0x0002, new Uint8Array(0));
      }
    } else if (msgType === 0x0005 && (msgFlags & 0x0001) !== 0 && body.length > 0) {
      // Server CB_FORMAT_DATA_RESPONSE -> notify ChromeOS clipboard
      const text = decodeUtf16Le(body).replace(/\r\n/g, '\n');
      if (text) this.onRemoteClipboard?.(text);
    }
  }

  async sendClientClipboardText(text) {
    this._localClipboardText = String(text || '');
    if (!this.cliprdrChannelId) return;
    // Advertise CF_UNICODETEXT (13) with empty long format name (`u32(13) + u16(0)`)
    this._sendCliprdrPdu(0x0002, 0, concatArrays([u32le(13), u16le(0)]));
  }

  async _handleSlowPathTpkt(tpktPayload) {
    if (tpktPayload.length < 9) return;
    // X.224 Data (`0x02 0xf0 0x80`) + MCS PDU byte
    const mcsChoice = tpktPayload[3] & 0xfc;
    if (mcsChoice === 0x20) {
      // DisconnectProviderUltimatum (`0x20` / `0x21`)
      throw new Error('Remote desktop session ended by server');
    }
    if (mcsChoice !== 0x68) return; // Not SendDataIndication

    const channelId = (tpktPayload[6] << 8) | tpktPayload[7];
    const per = readPerLength(tpktPayload, 9);
    const mcsUserData = tpktPayload.subarray(per.nextOffset, Math.min(tpktPayload.length, per.nextOffset + per.length));
    if (mcsUserData.length < 4) return;

    if (this.cliprdrChannelId && channelId === this.cliprdrChannelId) {
      this._handleCliprdrPacket(mcsUserData);
      return;
    }
    if (channelId !== this.ioChannelId) return;

    const dv = new DataView(mcsUserData.buffer, mcsUserData.byteOffset, mcsUserData.byteLength);
    // Check for Server Licensing PDU (`SEC_LICENSE_PKT = 0x0080` in 4-byte security header before first DemandActive)
    const secFlags = dv.getUint16(0, true);
    if ((secFlags & 0x0080) !== 0 && this.shareId === 0) {
      return; // Licensing PDU (e.g., STATUS_VALID_CLIENT) — proceed to DemandActivePDU!
    }

    // Parse one or more concatenated `TS_SHARECONTROLHEADER` PDUs inside `mcsUserData`
    let pos = 0;
    while (pos + 6 <= mcsUserData.length) {
      const totalLen = dv.getUint16(pos, true);
      const pduType = dv.getUint16(pos + 2, true) & 0x000f;
      if (totalLen < 6 || pos + totalLen > mcsUserData.length) break;
      const pduSlice = mcsUserData.subarray(pos, pos + totalLen);
      pos += totalLen;

      if (pduType === 0x0001 && pduSlice.length >= 10) {
        // PDUTYPE_DEMANDACTIVEPDU (`shareId` at offset 6..10)
        const sId = new DataView(pduSlice.buffer, pduSlice.byteOffset + 6, 4).getUint32(0, true);
        await this._respondToDemandActive(sId);
      } else if (pduType === 0x0006) {
        // PDUTYPE_DEACTIVATEALLPDU
        this.shareId = 0;
      } else if (pduType === 0x0007 && pduSlice.length >= 18) {
        // PDUTYPE_DATAPDU
        const pduType2 = pduSlice[14];
        const compType = pduSlice[15];
        if ((compType & 0x20) !== 0) continue; // MPPC compressed (disabled since we don't advertise MPPC)
        const dataBody = pduSlice.subarray(18);
        if (pduType2 === 0x02 && dataBody.length >= 2) {
          // PDUTYPE2_UPDATE (`updateType` u16: 0x0001 = UPDATETYPE_BITMAP)
          const uType = new DataView(dataBody.buffer, dataBody.byteOffset, 2).getUint16(0, true);
          if (uType === 0x0001) {
            this._parseAndPaintBitmapUpdateData(dataBody);
          }
        } else if (pduType2 === 0x2f && dataBody.length >= 4) {
          // PDUTYPE2_SET_ERROR_INFO_PDU
          const errInfo = new DataView(dataBody.buffer, dataBody.byteOffset, 4).getUint32(0, true);
          if (errInfo !== 0 && errInfo !== 0x00000001 && errInfo !== 0x00000002) {
            this.onStatus?.(`RDP notice (0x${errInfo.toString(16)})`);
          }
        }
      }
    }
  }

  async _pumpUpdates() {
    try {
      while (!this._closed) {
        const pkt = await this._readTpktOrFastPath();
        if (!pkt.isFastPath) {
          await this._handleSlowPathTpkt(pkt.payload);
          continue;
        }
        const buf = pkt.payload;
        let pos = 0;
        while (pos + 3 <= buf.length) {
          const updateHdr = buf[pos];
          const updateCode = updateHdr & 0x0f;
          const frag = (updateHdr >>> 4) & 0x03;
          const comp = (updateHdr >>> 6) & 0x03;
          pos += 1;
          if (comp & 0x02) pos += 1;
          if (pos + 2 > buf.length) break;
          const size = buf[pos] | (buf[pos + 1] << 8);
          pos += 2;
          const rawChunk = buf.subarray(pos, Math.min(buf.length, pos + size));
          pos += size;

          let data = rawChunk;
          if (frag === 2) {
            // FASTPATH_FRAGMENT_FIRST
            this._fastPathFragBuf = rawChunk.slice();
            continue;
          } else if (frag === 3) {
            // FASTPATH_FRAGMENT_NEXT
            this._fastPathFragBuf = concatArrays([this._fastPathFragBuf, rawChunk]);
            continue;
          } else if (frag === 1) {
            // FASTPATH_FRAGMENT_LAST
            data = concatArrays([this._fastPathFragBuf, rawChunk]);
            this._fastPathFragBuf = new Uint8Array(0);
          }

          if (updateCode === 0x01 && data.length >= 4) {
            this._parseAndPaintBitmapUpdateData(data);
          } else if (updateCode === 0x04 && data.length >= 22) {
            // FASTPATH_UPDATETYPE_SURFCMDS (`CMDTYPE_SET_SURFACE_BITS = 0x0001` / `STREAM_SURFACE_BITS = 0x0006`)
            const sdv = new DataView(data.buffer, data.byteOffset, data.byteLength);
            const cmdType = sdv.getUint16(0, true);
            if (cmdType === 0x0001 || cmdType === 0x0006) {
              const destLeft = sdv.getUint16(2, true);
              const destTop = sdv.getUint16(4, true);
              const bpp = data[10];
              const w = sdv.getUint16(14, true);
              const h = sdv.getUint16(16, true);
              const bmpLen = sdv.getUint32(18, true);
              const bmpSlice = data.subarray(22, Math.min(data.length, 22 + bmpLen));
              this._paintBitmapRect(destLeft, destTop, w, h, bpp || 32, false, bmpSlice);
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
    await this.stream.write(buildFastPathMousePdu(x, y, 0x0800));
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
