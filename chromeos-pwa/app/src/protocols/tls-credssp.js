// Standalone WebCrypto TLS 1.2 / TLS 1.3 Transport + `[MS-CSSP]` CredSSP (v2–v6) & `[MS-NLMP]` NTLMv2 Engine
// Enables 100% native Windows 10/11 & Windows Server 2016/2019/2022/2025 NLA + TLS RDP connections
// directly over ChromeOS Isolated Web App `Direct Sockets` (`window.TCPSocket`) for both VPN & Internet IPs.

import { BufferedByteStream } from './socket-transport.js';

function concatBytes(arrays) {
  let total = 0;
  for (const a of arrays) total += a.length;
  const out = new Uint8Array(total);
  let off = 0;
  for (const a of arrays) {
    out.set(a, off);
    off += a.length;
  }
  return out;
}

function u16be(n) {
  return new Uint8Array([(n >>> 8) & 0xff, n & 0xff]);
}

function u24be(n) {
  return new Uint8Array([(n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff]);
}

function u32le(n) {
  const b = new Uint8Array(4);
  new DataView(b.buffer).setUint32(0, n >>> 0, true);
  return b;
}

function u16le(n) {
  const b = new Uint8Array(2);
  new DataView(b.buffer).setUint16(0, n & 0xffff, true);
  return b;
}

function encodeUtf16Le(str) {
  const s = String(str || '');
  const out = new Uint8Array(s.length * 2);
  const dv = new DataView(out.buffer);
  for (let i = 0; i < s.length; i++) {
    dv.setUint16(i * 2, s.charCodeAt(i), true);
  }
  return out;
}

function isIpAddressLiteral(host) {
  const h = String(host || '').trim();
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) return true;
  if (h.includes(':')) return true;
  return false;
}

// ============================================================================
// Pure-JS Cryptographic Primitives Required by NTLMv2 (`[MS-NLMP]`):
// RFC 1320 MD4, RFC 1321 MD5 + HMAC-MD5, and Stateful RC4 Stream Cipher
// ============================================================================

export function md4Bytes(input) {
  const msg = input instanceof Uint8Array ? input : new Uint8Array(input);
  const bitLen = BigInt(msg.length) * 8n;
  const padLen = ((56 - ((msg.length + 1) % 64)) + 64) % 64;
  const buf = new Uint8Array(msg.length + 1 + padLen + 8);
  buf.set(msg, 0);
  buf[msg.length] = 0x80;
  const dv = new DataView(buf.buffer);
  dv.setUint32(buf.length - 8, Number(bitLen & 0xffffffffn) >>> 0, true);
  dv.setUint32(buf.length - 4, Number((bitLen >> 32n) & 0xffffffffn) >>> 0, true);

  let a = 0x67452301;
  let b = 0xefcdab89;
  let c = 0x98badcfe;
  let d = 0x10325476;
  const rotl = (x, s) => ((x << s) | (x >>> (32 - s))) >>> 0;
  const X = new Uint32Array(16);

  for (let off = 0; off < buf.length; off += 64) {
    for (let i = 0; i < 16; i++) X[i] = dv.getUint32(off + i * 4, true);
    let aa = a, bb = b, cc = c, dd = d;

    const r1 = (p, q, r, s, k, sh) => rotl((p + ((q & r) | (~q & s)) + X[k]) >>> 0, sh);
    a = r1(a, b, c, d, 0, 3);  d = r1(d, a, b, c, 1, 7);  c = r1(c, d, a, b, 2, 11); b = r1(b, c, d, a, 3, 19);
    a = r1(a, b, c, d, 4, 3);  d = r1(d, a, b, c, 5, 7);  c = r1(c, d, a, b, 6, 11); b = r1(b, c, d, a, 7, 19);
    a = r1(a, b, c, d, 8, 3);  d = r1(d, a, b, c, 9, 7);  c = r1(c, d, a, b, 10, 11); b = r1(b, c, d, a, 11, 19);
    a = r1(a, b, c, d, 12, 3); d = r1(d, a, b, c, 13, 7); c = r1(c, d, a, b, 14, 11); b = r1(b, c, d, a, 15, 19);

    const r2 = (p, q, r, s, k, sh) => rotl((p + ((q & r) | (q & s) | (r & s)) + X[k] + 0x5a827999) >>> 0, sh);
    a = r2(a, b, c, d, 0, 3);  d = r2(d, a, b, c, 4, 5);  c = r2(c, d, a, b, 8, 9);   b = r2(b, c, d, a, 12, 13);
    a = r2(a, b, c, d, 1, 3);  d = r2(d, a, b, c, 5, 5);  c = r2(c, d, a, b, 9, 9);   b = r2(b, c, d, a, 13, 13);
    a = r2(a, b, c, d, 2, 3);  d = r2(d, a, b, c, 6, 5);  c = r2(c, d, a, b, 10, 9);  b = r2(b, c, d, a, 14, 13);
    a = r2(a, b, c, d, 3, 3);  d = r2(d, a, b, c, 7, 5);  c = r2(c, d, a, b, 11, 9);  b = r2(b, c, d, a, 15, 13);

    const r3 = (p, q, r, s, k, sh) => rotl((p + (q ^ r ^ s) + X[k] + 0x6ed9eba1) >>> 0, sh);
    a = r3(a, b, c, d, 0, 3);  d = r3(d, a, b, c, 8, 9);  c = r3(c, d, a, b, 4, 11);  b = r3(b, c, d, a, 12, 15);
    a = r3(a, b, c, d, 2, 3);  d = r3(d, a, b, c, 10, 9); c = r3(c, d, a, b, 6, 11);  b = r3(b, c, d, a, 14, 15);
    a = r3(a, b, c, d, 1, 3);  d = r3(d, a, b, c, 9, 9);  c = r3(c, d, a, b, 5, 11);  b = r3(b, c, d, a, 13, 15);
    a = r3(a, b, c, d, 3, 3);  d = r3(d, a, b, c, 11, 9); c = r3(c, d, a, b, 7, 11);  b = r3(b, c, d, a, 15, 15);

    a = (a + aa) >>> 0;
    b = (b + bb) >>> 0;
    c = (c + cc) >>> 0;
    d = (d + dd) >>> 0;
  }

  const out = new Uint8Array(16);
  const odv = new DataView(out.buffer);
  odv.setUint32(0, a, true);
  odv.setUint32(4, b, true);
  odv.setUint32(8, c, true);
  odv.setUint32(12, d, true);
  return out;
}

const MD5_K = Uint32Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0);
const MD5_S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
];

export function md5Bytes(input) {
  const msg = input instanceof Uint8Array ? input : new Uint8Array(input);
  const bitLen = BigInt(msg.length) * 8n;
  const padLen = ((56 - ((msg.length + 1) % 64)) + 64) % 64;
  const buf = new Uint8Array(msg.length + 1 + padLen + 8);
  buf.set(msg, 0);
  buf[msg.length] = 0x80;
  const dv = new DataView(buf.buffer);
  dv.setUint32(buf.length - 8, Number(bitLen & 0xffffffffn) >>> 0, true);
  dv.setUint32(buf.length - 4, Number((bitLen >> 32n) & 0xffffffffn) >>> 0, true);

  let a0 = 0x67452301;
  let b0 = 0xefcdab89;
  let c0 = 0x98badcfe;
  let d0 = 0x10325476;
  const M = new Uint32Array(16);

  for (let off = 0; off < buf.length; off += 64) {
    for (let j = 0; j < 16; j++) M[j] = dv.getUint32(off + j * 4, true);
    let A = a0, B = b0, C = c0, D = d0;
    for (let i = 0; i < 64; i++) {
      let F, g;
      if (i < 16) {
        F = (B & C) | (~B & D);
        g = i;
      } else if (i < 32) {
        F = (D & B) | (~D & C);
        g = (5 * i + 1) % 16;
      } else if (i < 48) {
        F = B ^ C ^ D;
        g = (3 * i + 5) % 16;
      } else {
        F = C ^ (B | ~D);
        g = (7 * i) % 16;
      }
      const tmp = D;
      D = C;
      C = B;
      const sum = (A + F + MD5_K[i] + M[g]) >>> 0;
      const sh = MD5_S[i];
      B = (B + (((sum << sh) | (sum >>> (32 - sh))) >>> 0)) >>> 0;
      A = tmp;
    }
    a0 = (a0 + A) >>> 0;
    b0 = (b0 + B) >>> 0;
    c0 = (c0 + C) >>> 0;
    d0 = (d0 + D) >>> 0;
  }

  const out = new Uint8Array(16);
  const odv = new DataView(out.buffer);
  odv.setUint32(0, a0, true);
  odv.setUint32(4, b0, true);
  odv.setUint32(8, c0, true);
  odv.setUint32(12, d0, true);
  return out;
}

export function hmacMd5(keyBytes, dataBytes) {
  let k = keyBytes instanceof Uint8Array ? keyBytes : new Uint8Array(keyBytes);
  if (k.length > 64) k = md5Bytes(k);
  const ipad = new Uint8Array(64).fill(0x36);
  const opad = new Uint8Array(64).fill(0x5c);
  for (let i = 0; i < k.length; i++) {
    ipad[i] ^= k[i];
    opad[i] ^= k[i];
  }
  const inner = md5Bytes(concatBytes([ipad, dataBytes]));
  return md5Bytes(concatBytes([opad, inner]));
}

export class Rc4Stream {
  constructor(keyBytes) {
    this.S = new Uint8Array(256);
    for (let i = 0; i < 256; i++) this.S[i] = i;
    let j = 0;
    for (let i = 0; i < 256; i++) {
      j = (j + this.S[i] + keyBytes[i % keyBytes.length]) & 0xff;
      const t = this.S[i];
      this.S[i] = this.S[j];
      this.S[j] = t;
    }
    this.i = 0;
    this.j = 0;
  }

  process(data) {
    const out = new Uint8Array(data.length);
    for (let k = 0; k < data.length; k++) {
      this.i = (this.i + 1) & 0xff;
      this.j = (this.j + this.S[this.i]) & 0xff;
      const t = this.S[this.i];
      this.S[this.i] = this.S[this.j];
      this.S[this.j] = t;
      out[k] = data[k] ^ this.S[(this.S[this.i] + this.S[this.j]) & 0xff];
    }
    return out;
  }
}

// ============================================================================
// ASN.1 DER Reader & Writer (for X.509 `SubjectPublicKey` & CredSSP `TSRequest`)
// ============================================================================

function derEncodeLength(len) {
  if (len < 0x80) return new Uint8Array([len]);
  if (len <= 0xff) return new Uint8Array([0x81, len]);
  if (len <= 0xffff) return new Uint8Array([0x82, (len >>> 8) & 0xff, len & 0xff]);
  return new Uint8Array([0x83, (len >>> 16) & 0xff, (len >>> 8) & 0xff, len & 0xff]);
}

function derTlv(tag, contentBytes) {
  return concatBytes([new Uint8Array([tag]), derEncodeLength(contentBytes.length), contentBytes]);
}

function derInteger(n) {
  if (n >= 0 && n < 128) return derTlv(0x02, new Uint8Array([n]));
  const bytes = [];
  let v = n;
  while (v > 0) {
    bytes.unshift(v & 0xff);
    v >>>= 8;
  }
  if (bytes[0] & 0x80) bytes.unshift(0x00);
  return derTlv(0x02, new Uint8Array(bytes));
}

function derOctetString(bytes) {
  return derTlv(0x04, bytes);
}

function derSequence(items) {
  return derTlv(0x30, concatBytes(items));
}

function derExplicit(contextNum, innerTlv) {
  return derTlv(0xa0 | (contextNum & 0x1f), innerTlv);
}

export function derReadElement(buf, offset = 0) {
  if (offset >= buf.length) throw new Error('ASN.1 DER truncated');
  const tag = buf[offset++];
  if (offset >= buf.length) throw new Error('ASN.1 DER missing length');
  let len = buf[offset++];
  if (len & 0x80) {
    const numBytes = len & 0x7f;
    if (numBytes === 0 || numBytes > 4 || offset + numBytes > buf.length) {
      throw new Error('ASN.1 DER unsupported length');
    }
    len = 0;
    for (let i = 0; i < numBytes; i++) len = (len << 8) | buf[offset++];
  }
  if (offset + len > buf.length) throw new Error('ASN.1 DER element exceeds buffer');
  return {
    tag,
    headerOffset: offset,
    length: len,
    content: buf.subarray(offset, offset + len),
    nextOffset: offset + len
  };
}

/**
 * Extracts `Certificate.tbsCertificate.subjectPublicKeyInfo.subjectPublicKey` raw BIT STRING payload
 * (without the 1-byte unused-bits prefix), matching `[MS-CSSP]` §3.1.5 `SubjectPublicKey`!
 */
export function extractX509SubjectPublicKeyBytes(certDer) {
  const certSeq = derReadElement(certDer, 0);
  const tbsSeq = derReadElement(certSeq.content, 0);
  let pos = 0;
  const first = derReadElement(tbsSeq.content, pos);
  if (first.tag === 0xa0) {
    // Explicit [0] version present
    pos = first.nextOffset;
  }
  // serialNumber, signature, issuer, validity, subject -> 5 elements
  for (let i = 0; i < 5; i++) {
    const el = derReadElement(tbsSeq.content, pos);
    pos = el.nextOffset;
  }
  // 6th element is SubjectPublicKeyInfo ::= SEQUENCE { algorithm, subjectPublicKey BIT STRING }
  const spki = derReadElement(tbsSeq.content, pos);
  const alg = derReadElement(spki.content, 0);
  const bitStr = derReadElement(spki.content, alg.nextOffset);
  if (bitStr.tag !== 0x03 || bitStr.content.length < 2) {
    throw new Error('Invalid X.509 subjectPublicKey BIT STRING');
  }
  // Skip 1st byte (number of unused bits = 0x00)
  return bitStr.content.slice(1);
}

// ============================================================================
// WebCrypto TLS 1.2 + TLS 1.3 Client Transport (`upgradeTcpStreamToTls`)
// ============================================================================

async function webHash(algName, bytes) {
  return new Uint8Array(await crypto.subtle.digest(algName, bytes));
}

async function webHmac(algName, keyBytes, dataBytes) {
  const k = await crypto.subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: algName }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', k, dataBytes));
}

async function tls12Prf(hashAlg, secret, labelAscii, seed, outLen) {
  const labelAndSeed = concatBytes([new TextEncoder().encode(labelAscii), seed]);
  const chunks = [];
  let total = 0;
  let A = labelAndSeed;
  while (total < outLen) {
    A = await webHmac(hashAlg, secret, A);
    const block = await webHmac(hashAlg, secret, concatBytes([A, labelAndSeed]));
    chunks.push(block);
    total += block.length;
  }
  return concatBytes(chunks).subarray(0, outLen);
}

async function hkdfExtract(hashAlg, salt, ikm) {
  const hashLen = hashAlg === 'SHA-384' ? 48 : 32;
  const realSalt = salt && salt.length > 0 ? salt : new Uint8Array(hashLen);
  return await webHmac(hashAlg, realSalt, ikm);
}

async function hkdfExpand(hashAlg, prk, info, length) {
  const chunks = [];
  let prev = new Uint8Array(0);
  let total = 0;
  let counter = 1;
  while (total < length) {
    const block = await webHmac(hashAlg, prk, concatBytes([prev, info, new Uint8Array([counter++])]));
    chunks.push(block);
    prev = block;
    total += block.length;
  }
  return concatBytes(chunks).subarray(0, length);
}

async function hkdfExpandLabel(hashAlg, secret, label, contextBytes, length) {
  const fullLabel = new TextEncoder().encode(`tls13 ${label}`);
  const hkdfLabel = concatBytes([
    u16be(length),
    new Uint8Array([fullLabel.length]),
    fullLabel,
    new Uint8Array([contextBytes.length]),
    contextBytes
  ]);
  return await hkdfExpand(hashAlg, secret, hkdfLabel, length);
}

function buildSeqBytes8(seqBigInt) {
  const out = new Uint8Array(8);
  const dv = new DataView(out.buffer);
  dv.setUint32(0, Number((seqBigInt >> 32n) & 0xffffffffn) >>> 0, false);
  dv.setUint32(4, Number(seqBigInt & 0xffffffffn) >>> 0, false);
  return out;
}

function xorNonce(iv12, seqBigInt) {
  const out = iv12.slice();
  const seq8 = buildSeqBytes8(seqBigInt);
  for (let i = 0; i < 8; i++) out[4 + i] ^= seq8[i];
  return out;
}

const CIPHER_SUITES_META = {
  0x1301: { tls13: true, hash: 'SHA-256', keyLen: 16, ivLen: 12, mode: 'GCM' },
  0x1302: { tls13: true, hash: 'SHA-384', keyLen: 32, ivLen: 12, mode: 'GCM' },
  0xc02f: { tls13: false, hash: 'SHA-256', keyLen: 16, ivLen: 4, mode: 'GCM' },
  0xc02b: { tls13: false, hash: 'SHA-256', keyLen: 16, ivLen: 4, mode: 'GCM' },
  0xc030: { tls13: false, hash: 'SHA-384', keyLen: 32, ivLen: 4, mode: 'GCM' },
  0xc02c: { tls13: false, hash: 'SHA-384', keyLen: 32, ivLen: 4, mode: 'GCM' },
  0xc027: { tls13: false, hash: 'SHA-256', macHash: 'SHA-256', macLen: 32, keyLen: 16, ivLen: 16, mode: 'CBC' },
  0xc013: { tls13: false, hash: 'SHA-256', macHash: 'SHA-1', macLen: 20, keyLen: 16, ivLen: 16, mode: 'CBC' }
};

function buildTlsClientHello({ clientRandom, sessionId, serverName, p256PubRaw, allowTls13 = true }) {
  const suites = allowTls13
    ? [0x1301, 0x1302, 0xc02f, 0xc02b, 0xc030, 0xc02c, 0xc027, 0xc013]
    : [0xc02f, 0xc02b, 0xc030, 0xc02c, 0xc027, 0xc013];
  const suitesBuf = new Uint8Array(suites.length * 2);
  suites.forEach((cs, idx) => {
    suitesBuf[idx * 2] = (cs >>> 8) & 0xff;
    suitesBuf[idx * 2 + 1] = cs & 0xff;
  });

  const extensions = [];
  const ext = (type, body) => concatBytes([u16be(type), u16be(body.length), body]);

  if (serverName && !isIpAddressLiteral(serverName)) {
    const hostBytes = new TextEncoder().encode(serverName);
    const sniEntry = concatBytes([new Uint8Array([0x00]), u16be(hostBytes.length), hostBytes]);
    extensions.push(ext(0x0000, concatBytes([u16be(sniEntry.length), sniEntry])));
  }

  // renegotiation_info (0xff01)
  extensions.push(ext(0xff01, new Uint8Array([0x00])));
  // supported_groups (0x000a): secp256r1 (0x0017), secp384r1 (0x0018)
  extensions.push(ext(0x000a, new Uint8Array([0x00, 0x04, 0x00, 0x17, 0x00, 0x18])));
  // ec_point_formats (0x000b): uncompressed (0)
  extensions.push(ext(0x000b, new Uint8Array([0x01, 0x00])));
  // signature_algorithms (0x000d)
  const sigAlgs = new Uint8Array([
    0x08, 0x04, 0x08, 0x05, 0x08, 0x06,
    0x04, 0x01, 0x05, 0x01, 0x06, 0x01,
    0x04, 0x03, 0x05, 0x03, 0x02, 0x01
  ]);
  extensions.push(ext(0x000d, concatBytes([u16be(sigAlgs.length), sigAlgs])));
  // extended_master_secret (0x0017)
  extensions.push(ext(0x0017, new Uint8Array(0)));

  if (allowTls13 && p256PubRaw) {
    // supported_versions (0x002b): TLS 1.3 (0x0304), TLS 1.2 (0x0303)
    extensions.push(ext(0x002b, new Uint8Array([0x04, 0x03, 0x04, 0x03, 0x03])));
    // key_share (0x0033): secp256r1 (0x0017)
    const ksEntry = concatBytes([u16be(0x0017), u16be(p256PubRaw.length), p256PubRaw]);
    extensions.push(ext(0x0033, concatBytes([u16be(ksEntry.length), ksEntry])));
  }

  const extBytes = concatBytes(extensions);
  const body = concatBytes([
    new Uint8Array([0x03, 0x03]),
    clientRandom,
    new Uint8Array([sessionId.length]),
    sessionId,
    u16be(suitesBuf.length),
    suitesBuf,
    new Uint8Array([0x01, 0x00]), // null compression
    u16be(extBytes.length),
    extBytes
  ]);

  return concatBytes([new Uint8Array([0x01]), u24be(body.length), body]);
}

function parseServerHello(hsBody) {
  const legacyVersion = (hsBody[0] << 8) | hsBody[1];
  const serverRandom = hsBody.slice(2, 34);
  const sidLen = hsBody[34];
  let pos = 35 + sidLen;
  const cipherSuite = (hsBody[pos] << 8) | hsBody[pos + 1];
  pos += 3; // cipherSuite(2) + compressionMethod(1)

  let negotiatedVersion = legacyVersion;
  let extendedMasterSecret = false;
  let tls13KeyShareGroup = 0;
  let tls13KeySharePub = null;

  if (pos + 2 <= hsBody.length) {
    const extTotal = (hsBody[pos] << 8) | hsBody[pos + 1];
    pos += 2;
    const end = Math.min(hsBody.length, pos + extTotal);
    while (pos + 4 <= end) {
      const eType = (hsBody[pos] << 8) | hsBody[pos + 1];
      const eLen = (hsBody[pos + 2] << 8) | hsBody[pos + 3];
      pos += 4;
      const eBody = hsBody.subarray(pos, pos + eLen);
      pos += eLen;
      if (eType === 0x002b && eBody.length >= 2) {
        negotiatedVersion = (eBody[0] << 8) | eBody[1];
      } else if (eType === 0x0017) {
        extendedMasterSecret = true;
      } else if (eType === 0x0033 && eBody.length >= 4) {
        tls13KeyShareGroup = (eBody[0] << 8) | eBody[1];
        const kLen = (eBody[2] << 8) | eBody[3];
        tls13KeySharePub = eBody.slice(4, 4 + kLen);
      }
    }
  }

  return { negotiatedVersion, serverRandom, cipherSuite, extendedMasterSecret, tls13KeyShareGroup, tls13KeySharePub };
}

/**
 * Upgrades an open `BufferedByteStream` to TLS 1.2 or TLS 1.3 in-place (STARTTLS after X.224 Connection Confirm).
 * Returns `{ stream, serverPublicKeyBytes, cipherSuite, tlsVersion }`.
 */
export async function upgradeTcpStreamToTls(rawStream, { serverName = '', allowTls13 = true } = {}) {
  const clientRandom = crypto.getRandomValues(new Uint8Array(32));
  const sessionId = crypto.getRandomValues(new Uint8Array(32));

  // Pre-generate P-256 keypair for TLS 1.3 key_share (reusable for TLS 1.2 P-256 ECDHE too!)
  const p256KeyPair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const p256PubRaw = new Uint8Array(await crypto.subtle.exportKey('raw', p256KeyPair.publicKey));

  const transcript = [];
  const appendTranscript = (hsMsg) => transcript.push(hsMsg);

  const sendPlainRecord = async (contentType, payload, legacyVer = 0x0303) => {
    const rec = concatBytes([
      new Uint8Array([contentType, (legacyVer >>> 8) & 0xff, legacyVer & 0xff]),
      u16be(payload.length),
      payload
    ]);
    await rawStream.write(rec);
  };

  const readRawRecord = async () => {
    const hdr = await rawStream.readExact(5);
    const contentType = hdr[0];
    const ver = (hdr[1] << 8) | hdr[2];
    const len = (hdr[3] << 8) | hdr[4];
    const body = await rawStream.readExact(len);
    return { contentType, ver, body };
  };

  // Handshake message reassembler across TLS records
  let hsBuffer = new Uint8Array(0);
  const pullNextPlainHandshakeMsg = async () => {
    while (true) {
      if (hsBuffer.length >= 4) {
        const msgType = hsBuffer[0];
        const msgLen = (hsBuffer[1] << 16) | (hsBuffer[2] << 8) | hsBuffer[3];
        if (hsBuffer.length >= 4 + msgLen) {
          const fullMsg = hsBuffer.slice(0, 4 + msgLen);
          const msgBody = fullMsg.subarray(4);
          hsBuffer = hsBuffer.slice(4 + msgLen);
          return { msgType, msgBody, fullMsg };
        }
      }
      const rec = await readRawRecord();
      if (rec.contentType === 0x15) {
        throw new Error(`TLS handshake alert (${rec.body[0]}/${rec.body[1]})`);
      }
      if (rec.contentType !== 0x16) {
        throw new Error(`Expected TLS Handshake record (0x16), got 0x${rec.contentType.toString(16)}`);
      }
      hsBuffer = concatBytes([hsBuffer, rec.body]);
    }
  };

  const chMsg = buildTlsClientHello({ clientRandom, sessionId, serverName, p256PubRaw, allowTls13 });
  appendTranscript(chMsg);
  await sendPlainRecord(0x16, chMsg, 0x0301);

  const shPkt = await pullNextPlainHandshakeMsg();
  if (shPkt.msgType !== 0x02) throw new Error(`Expected ServerHello (2), got ${shPkt.msgType}`);
  appendTranscript(shPkt.fullMsg);
  const sh = parseServerHello(shPkt.msgBody);
  const meta = CIPHER_SUITES_META[sh.cipherSuite];
  if (!meta) {
    throw new Error(`Unsupported RDP server TLS cipher suite: 0x${sh.cipherSuite.toString(16)}`);
  }

  let serverPublicKeyBytes = null;
  let encryptAppRecord;
  let decryptAppRecord;

  if (sh.negotiatedVersion === 0x0304 && meta.tls13) {
    // ========================== TLS 1.3 Handshake ==========================
    if (sh.tls13KeyShareGroup !== 0x0017 || !sh.tls13KeySharePub) {
      throw new Error('TLS 1.3 server requested unsupported key_share group');
    }
    const srvPubKey = await crypto.subtle.importKey('raw', sh.tls13KeySharePub, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    const sharedSecret = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: srvPubKey }, p256KeyPair.privateKey, 256));

    const hashLen = meta.hash === 'SHA-384' ? 48 : 32;
    const emptyHash = await webHash(meta.hash, new Uint8Array(0));
    const earlySecret = await hkdfExtract(meta.hash, new Uint8Array(hashLen), new Uint8Array(hashLen));
    const derivedEarly = await hkdfExpandLabel(meta.hash, earlySecret, 'derived', emptyHash, hashLen);
    const handshakeSecret = await hkdfExtract(meta.hash, derivedEarly, sharedSecret);

    const chShHash = await webHash(meta.hash, concatBytes(transcript));
    const cHsTraffic = await hkdfExpandLabel(meta.hash, handshakeSecret, 'c hs traffic', chShHash, hashLen);
    const sHsTraffic = await hkdfExpandLabel(meta.hash, handshakeSecret, 's hs traffic', chShHash, hashLen);

    const sHsKeyBytes = await hkdfExpandLabel(meta.hash, sHsTraffic, 'key', new Uint8Array(0), meta.keyLen);
    const sHsIv = await hkdfExpandLabel(meta.hash, sHsTraffic, 'iv', new Uint8Array(0), 12);
    const cHsKeyBytes = await hkdfExpandLabel(meta.hash, cHsTraffic, 'key', new Uint8Array(0), meta.keyLen);
    const cHsIv = await hkdfExpandLabel(meta.hash, cHsTraffic, 'iv', new Uint8Array(0), 12);

    const sHsKey = await crypto.subtle.importKey('raw', sHsKeyBytes, 'AES-GCM', false, ['decrypt']);
    const cHsKey = await crypto.subtle.importKey('raw', cHsKeyBytes, 'AES-GCM', false, ['encrypt']);

    let srvHsSeq = 0n;
    let tls13PlainHsBuf = new Uint8Array(0);

    const readTls13HandshakeMsg = async () => {
      while (true) {
        if (tls13PlainHsBuf.length >= 4) {
          const mType = tls13PlainHsBuf[0];
          const mLen = (tls13PlainHsBuf[1] << 16) | (tls13PlainHsBuf[2] << 8) | tls13PlainHsBuf[3];
          if (tls13PlainHsBuf.length >= 4 + mLen) {
            const full = tls13PlainHsBuf.slice(0, 4 + mLen);
            tls13PlainHsBuf = tls13PlainHsBuf.slice(4 + mLen);
            return { msgType: mType, msgBody: full.subarray(4), fullMsg: full };
          }
        }
        const rec = await readRawRecord();
        if (rec.contentType === 0x14) continue; // middlebox ChangeCipherSpec
        if (rec.contentType === 0x15) throw new Error(`TLS 1.3 alert (${rec.body[0]}/${rec.body[1]})`);
        if (rec.contentType !== 0x17) throw new Error(`Unexpected TLS 1.3 record 0x${rec.contentType.toString(16)}`);
        const aad = concatBytes([new Uint8Array([0x17, 0x03, 0x03]), u16be(rec.body.length)]);
        const dec = new Uint8Array(
          await crypto.subtle.decrypt({ name: 'AES-GCM', iv: xorNonce(sHsIv, srvHsSeq++), additionalData: aad }, sHsKey, rec.body)
        );
        let padEnd = dec.length - 1;
        while (padEnd >= 0 && dec[padEnd] === 0) padEnd--;
        if (padEnd < 0) throw new Error('Malformed TLS 1.3 inner plaintext');
        const innerType = dec[padEnd];
        const innerContent = dec.subarray(0, padEnd);
        if (innerType === 0x15) throw new Error(`TLS 1.3 encrypted alert (${innerContent[0]}/${innerContent[1]})`);
        if (innerType === 0x16) tls13PlainHsBuf = concatBytes([tls13PlainHsBuf, innerContent]);
      }
    };

    let certRequested = false;
    while (true) {
      const m = await readTls13HandshakeMsg();
      appendTranscript(m.fullMsg);
      if (m.msgType === 0x08) {
        // EncryptedExtensions
      } else if (m.msgType === 0x0d) {
        certRequested = true;
      } else if (m.msgType === 0x0b) {
        // TLS 1.3 Certificate: contextLen(1) + context + cert_list_len(3) + [cert_len(3) + cert + ext_len(2) + ext]
        const ctxLen = m.msgBody[0];
        const listStart = 1 + ctxLen + 3;
        const firstCertLen = (m.msgBody[listStart] << 16) | (m.msgBody[listStart + 1] << 8) | m.msgBody[listStart + 2];
        const leafDer = m.msgBody.subarray(listStart + 3, listStart + 3 + firstCertLen);
        serverPublicKeyBytes = extractX509SubjectPublicKeyBytes(leafDer);
      } else if (m.msgType === 0x0f) {
        // CertificateVerify
      } else if (m.msgType === 0x14) {
        // Server Finished -> verify HMAC
        const finishedKey = await hkdfExpandLabel(meta.hash, sHsTraffic, 'finished', new Uint8Array(0), hashLen);
        const transcriptBeforeSrvFin = concatBytes(transcript.slice(0, -1));
        const expectedFin = await webHmac(meta.hash, finishedKey, await webHash(meta.hash, transcriptBeforeSrvFin));
        if (expectedFin.length !== m.msgBody.length || expectedFin.some((b, i) => b !== m.msgBody[i])) {
          throw new Error('TLS 1.3 Server Finished verification failed');
        }
        break;
      }
    }

    // Derive Application Traffic Secrets (`transcript` up through Server Finished!)
    const srvFinTranscriptHash = await webHash(meta.hash, concatBytes(transcript));
    const derivedMaster = await hkdfExpandLabel(meta.hash, handshakeSecret, 'derived', emptyHash, hashLen);
    const masterSecret = await hkdfExtract(meta.hash, derivedMaster, new Uint8Array(hashLen));
    const cAppTraffic = await hkdfExpandLabel(meta.hash, masterSecret, 'c ap traffic', srvFinTranscriptHash, hashLen);
    const sAppTraffic = await hkdfExpandLabel(meta.hash, masterSecret, 's ap traffic', srvFinTranscriptHash, hashLen);

    // Send middlebox CCS + optional empty Certificate + Client Finished under cHsKey
    await sendPlainRecord(0x14, new Uint8Array([0x01]), 0x0303);
    let cliHsSeq = 0n;
    const sendTls13HsMsg = async (hsFull) => {
      const inner = concatBytes([hsFull, new Uint8Array([0x16])]);
      const aad = concatBytes([new Uint8Array([0x17, 0x03, 0x03]), u16be(inner.length + 16)]);
      const ct = new Uint8Array(
        await crypto.subtle.encrypt({ name: 'AES-GCM', iv: xorNonce(cHsIv, cliHsSeq++), additionalData: aad }, cHsKey, inner)
      );
      await sendPlainRecord(0x17, ct, 0x0303);
    };

    if (certRequested) {
      const emptyCert = new Uint8Array([0x0b, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00]);
      appendTranscript(emptyCert);
      await sendTls13HsMsg(emptyCert);
    }

    const cFinKey = await hkdfExpandLabel(meta.hash, cHsTraffic, 'finished', new Uint8Array(0), hashLen);
    const cFinVerify = await webHmac(meta.hash, cFinKey, await webHash(meta.hash, concatBytes(transcript)));
    const cFinMsg = concatBytes([new Uint8Array([0x14]), u24be(cFinVerify.length), cFinVerify]);
    appendTranscript(cFinMsg);
    await sendTls13HsMsg(cFinMsg);

    const cAppKey = await crypto.subtle.importKey(
      'raw',
      await hkdfExpandLabel(meta.hash, cAppTraffic, 'key', new Uint8Array(0), meta.keyLen),
      'AES-GCM',
      false,
      ['encrypt']
    );
    const cAppIv = await hkdfExpandLabel(meta.hash, cAppTraffic, 'iv', new Uint8Array(0), 12);
    const sAppKey = await crypto.subtle.importKey(
      'raw',
      await hkdfExpandLabel(meta.hash, sAppTraffic, 'key', new Uint8Array(0), meta.keyLen),
      'AES-GCM',
      false,
      ['decrypt']
    );
    const sAppIv = await hkdfExpandLabel(meta.hash, sAppTraffic, 'iv', new Uint8Array(0), 12);

    let cAppSeq = 0n;
    let sAppSeq = 0n;

    encryptAppRecord = async (plainChunk) => {
      const inner = concatBytes([plainChunk, new Uint8Array([0x17])]);
      const aad = concatBytes([new Uint8Array([0x17, 0x03, 0x03]), u16be(inner.length + 16)]);
      const ct = new Uint8Array(
        await crypto.subtle.encrypt({ name: 'AES-GCM', iv: xorNonce(cAppIv, cAppSeq++), additionalData: aad }, cAppKey, inner)
      );
      await sendPlainRecord(0x17, ct, 0x0303);
    };

    decryptAppRecord = async (rec) => {
      if (rec.contentType === 0x14) return null;
      if (rec.contentType === 0x15) return { closeNotify: true };
      if (rec.contentType !== 0x17) throw new Error(`Unexpected TLS 1.3 app record 0x${rec.contentType.toString(16)}`);
      const aad = concatBytes([new Uint8Array([0x17, 0x03, 0x03]), u16be(rec.body.length)]);
      const dec = new Uint8Array(
        await crypto.subtle.decrypt({ name: 'AES-GCM', iv: xorNonce(sAppIv, sAppSeq++), additionalData: aad }, sAppKey, rec.body)
      );
      let padEnd = dec.length - 1;
      while (padEnd >= 0 && dec[padEnd] === 0) padEnd--;
      if (padEnd < 0) return null;
      const innerType = dec[padEnd];
      const content = dec.subarray(0, padEnd);
      if (innerType === 0x17) return { data: content };
      if (innerType === 0x16) return null; // Post-handshake NewSessionTicket (0x04)
      if (innerType === 0x15) {
        if (content[1] === 0) return { closeNotify: true };
        throw new Error(`TLS 1.3 runtime alert (${content[0]}/${content[1]})`);
      }
      return null;
    };
  } else {
    // ========================== TLS 1.2 Handshake ==========================
    let srvEcdhCurve = 0;
    let srvEcdhPub = null;
    let certRequested = false;

    while (true) {
      const m = await pullNextPlainHandshakeMsg();
      appendTranscript(m.fullMsg);
      if (m.msgType === 0x0b) {
        // TLS 1.2 Certificate: listLen(3) + certLen(3) + leafDer
        const firstLen = (m.msgBody[3] << 16) | (m.msgBody[4] << 8) | m.msgBody[5];
        const leafDer = m.msgBody.subarray(6, 6 + firstLen);
        serverPublicKeyBytes = extractX509SubjectPublicKeyBytes(leafDer);
      } else if (m.msgType === 0x0c) {
        // ServerKeyExchange (ECDHE: curve_type=3 named_curve, namedcurve u16, pubLen u8, pubBytes)
        if (m.msgBody[0] !== 0x03) throw new Error('Expected ECDHE named_curve ServerKeyExchange');
        srvEcdhCurve = (m.msgBody[1] << 8) | m.msgBody[2];
        const pubLen = m.msgBody[3];
        srvEcdhPub = m.msgBody.slice(4, 4 + pubLen);
      } else if (m.msgType === 0x0d) {
        certRequested = true;
      } else if (m.msgType === 0x0e) {
        // ServerHelloDone
        break;
      }
    }

    if (!srvEcdhPub) throw new Error('Missing ECDHE ServerKeyExchange');
    const namedCurve = srvEcdhCurve === 0x0018 ? 'P-384' : 'P-256';
    const bitLen = srvEcdhCurve === 0x0018 ? 384 : 256;
    const cliKeyPair =
      namedCurve === 'P-256'
        ? p256KeyPair
        : await crypto.subtle.generateKey({ name: 'ECDH', namedCurve }, true, ['deriveBits']);
    const cliPubRaw =
      namedCurve === 'P-256'
        ? p256PubRaw
        : new Uint8Array(await crypto.subtle.exportKey('raw', cliKeyPair.publicKey));
    const importedSrvPub = await crypto.subtle.importKey('raw', srvEcdhPub, { name: 'ECDH', namedCurve }, false, []);
    const preMasterSecret = new Uint8Array(
      await crypto.subtle.deriveBits({ name: 'ECDH', public: importedSrvPub }, cliKeyPair.privateKey, bitLen)
    );

    if (certRequested) {
      const emptyCert = new Uint8Array([0x0b, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00]);
      appendTranscript(emptyCert);
      await sendPlainRecord(0x16, emptyCert, 0x0303);
    }

    // ClientKeyExchange (`0x10`, length(3), ecdhPubLen(1), cliPubRaw)
    const ckeBody = concatBytes([new Uint8Array([cliPubRaw.length]), cliPubRaw]);
    const ckeMsg = concatBytes([new Uint8Array([0x10]), u24be(ckeBody.length), ckeBody]);
    appendTranscript(ckeMsg);
    await sendPlainRecord(0x16, ckeMsg, 0x0303);

    // Master secret (RFC 7627 `extended_master_secret` if negotiated, else classic RFC 5246)
    let masterSecret;
    if (sh.extendedMasterSecret) {
      const sessionHash = await webHash(meta.hash, concatBytes(transcript));
      masterSecret = await tls12Prf(meta.hash, preMasterSecret, 'extended master secret', sessionHash, 48);
    } else {
      masterSecret = await tls12Prf(
        meta.hash,
        preMasterSecret,
        'master secret',
        concatBytes([clientRandom, sh.serverRandom]),
        48
      );
    }

    const macLen = meta.mode === 'CBC' ? meta.macLen : 0;
    const kbLen = 2 * macLen + 2 * meta.keyLen + 2 * meta.ivLen;
    const keyBlock = await tls12Prf(
      meta.hash,
      masterSecret,
      'key expansion',
      concatBytes([sh.serverRandom, clientRandom]),
      kbLen
    );

    let kOff = 0;
    const cMacSecret = keyBlock.slice(kOff, (kOff += macLen));
    const sMacSecret = keyBlock.slice(kOff, (kOff += macLen));
    const cWriteKeyBytes = keyBlock.slice(kOff, (kOff += meta.keyLen));
    const sWriteKeyBytes = keyBlock.slice(kOff, (kOff += meta.keyLen));
    const cWriteIv = keyBlock.slice(kOff, (kOff += meta.ivLen));
    const sWriteIv = keyBlock.slice(kOff, (kOff += meta.ivLen));

    let cliSeq = 0n;
    let srvSeq = 0n;

    if (meta.mode === 'GCM') {
      const cKey = await crypto.subtle.importKey('raw', cWriteKeyBytes, 'AES-GCM', false, ['encrypt']);
      const sKey = await crypto.subtle.importKey('raw', sWriteKeyBytes, 'AES-GCM', false, ['decrypt']);

      const sealTls12Record = async (contentType, plainBytes) => {
        const seq8 = buildSeqBytes8(cliSeq++);
        const iv12 = concatBytes([cWriteIv, seq8]);
        const aad = concatBytes([seq8, new Uint8Array([contentType, 0x03, 0x03]), u16be(plainBytes.length)]);
        const ctAndTag = new Uint8Array(
          await crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv12, additionalData: aad }, cKey, plainBytes)
        );
        await sendPlainRecord(contentType, concatBytes([seq8, ctAndTag]), 0x0303);
      };

      const openTls12Record = async (contentType, bodyBytes) => {
        const explicitNonce = bodyBytes.subarray(0, 8);
        const ctAndTag = bodyBytes.subarray(8);
        const plainLen = ctAndTag.length - 16;
        const seq8 = buildSeqBytes8(srvSeq++);
        const iv12 = concatBytes([sWriteIv, explicitNonce]);
        const aad = concatBytes([seq8, new Uint8Array([contentType, 0x03, 0x03]), u16be(plainLen)]);
        return new Uint8Array(
          await crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv12, additionalData: aad }, sKey, ctAndTag)
        );
      };

      await sendPlainRecord(0x14, new Uint8Array([0x01]), 0x0303);
      const cFinHash = await webHash(meta.hash, concatBytes(transcript));
      const cVerifyData = await tls12Prf(meta.hash, masterSecret, 'client finished', cFinHash, 12);
      const cFinMsg = concatBytes([new Uint8Array([0x14, 0x00, 0x00, 0x0c]), cVerifyData]);
      appendTranscript(cFinMsg);
      await sealTls12Record(0x16, cFinMsg);

      // Read Server ChangeCipherSpec + Encrypted Finished
      while (true) {
        const r = await readRawRecord();
        if (r.contentType === 0x14) break;
        if (r.contentType === 0x15) throw new Error(`TLS 1.2 alert before CCS (${r.body[0]}/${r.body[1]})`);
      }
      const finRec = await readRawRecord();
      if (finRec.contentType !== 0x16) throw new Error('Expected encrypted Server Finished');
      const sFinPlain = await openTls12Record(0x16, finRec.body);
      const expectedSVerify = await tls12Prf(
        meta.hash,
        masterSecret,
        'server finished',
        await webHash(meta.hash, concatBytes(transcript)),
        12
      );
      if (sFinPlain.subarray(4).some((b, i) => b !== expectedSVerify[i])) {
        throw new Error('TLS 1.2 Server Finished verification failed');
      }

      encryptAppRecord = (plainChunk) => sealTls12Record(0x17, plainChunk);
      decryptAppRecord = async (rec) => {
        if (rec.contentType === 0x15) {
          const decAlert = await openTls12Record(0x15, rec.body);
          if (decAlert[1] === 0) return { closeNotify: true };
          throw new Error(`TLS 1.2 runtime alert (${decAlert[0]}/${decAlert[1]})`);
        }
        if (rec.contentType === 0x17) {
          return { data: await openTls12Record(0x17, rec.body) };
        }
        return null;
      };
    } else {
      // TLS 1.2 AES-CBC + HMAC fallback (explicit 16-byte IV per record)
      const cKey = await crypto.subtle.importKey('raw', cWriteKeyBytes, 'AES-CBC', false, ['encrypt']);
      const sKey = await crypto.subtle.importKey('raw', sWriteKeyBytes, 'AES-CBC', false, ['decrypt']);

      const sealCbcRecord = async (contentType, plainBytes) => {
        const seq8 = buildSeqBytes8(cliSeq++);
        const macInput = concatBytes([seq8, new Uint8Array([contentType, 0x03, 0x03]), u16be(plainBytes.length), plainBytes]);
        const mac = await webHmac(meta.macHash, cMacSecret, macInput);
        const contentPlusMac = concatBytes([plainBytes, mac]);
        const iv16 = crypto.getRandomValues(new Uint8Array(16));
        // WebCrypto AES-CBC automatically appends PKCS#7 padding (`1..16` bytes equal to `padLen`),
        // whereas TLS CBC uses `padByte = padLen - 1` (`0..15`). Subtract 1 from each PKCS#7 pad byte via manual block encrypt:
        const padTotal = 16 - (contentPlusMac.length % 16);
        const tlsPadByte = padTotal - 1;
        const padded = new Uint8Array(contentPlusMac.length + padTotal);
        padded.set(contentPlusMac, 0);
        padded.fill(tlsPadByte, contentPlusMac.length);
        // Encrypt padded blocks without extra PKCS#7 block by trimming trailing 16-byte WebCrypto PKCS#7 block
        const encWithExtra = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CBC', iv: iv16 }, cKey, padded));
        const ct = encWithExtra.subarray(0, padded.length);
        await sendPlainRecord(contentType, concatBytes([iv16, ct]), 0x0303);
      };

      const openCbcRecord = async (contentType, bodyBytes) => {
        const iv16 = bodyBytes.subarray(0, 16);
        const ct = bodyBytes.subarray(16);
        // Synthesize valid PKCS#7 final block so WebCrypto AES-CBC decrypts arbitrary TLS CBC padding cleanly
        const prevBlock = ct.length >= 16 ? ct.subarray(ct.length - 16) : iv16;
        const pkcs7Block = new Uint8Array(16).fill(16);
        const padKey = await crypto.subtle.importKey('raw', sWriteKeyBytes, 'AES-CBC', false, ['encrypt']);
        const synthBlock = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CBC', iv: prevBlock }, padKey, pkcs7Block)).subarray(0, 16);
        const rawDec = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-CBC', iv: iv16 }, sKey, concatBytes([ct, synthBlock])));
        const tlsPadLen = rawDec[rawDec.length - 1] + 1;
        const plainEnd = rawDec.length - tlsPadLen - meta.macLen;
        const plain = rawDec.subarray(0, plainEnd);
        srvSeq++;
        return plain;
      };

      await sendPlainRecord(0x14, new Uint8Array([0x01]), 0x0303);
      const cFinHash = await webHash(meta.hash, concatBytes(transcript));
      const cVerifyData = await tls12Prf(meta.hash, masterSecret, 'client finished', cFinHash, 12);
      const cFinMsg = concatBytes([new Uint8Array([0x14, 0x00, 0x00, 0x0c]), cVerifyData]);
      appendTranscript(cFinMsg);
      await sealCbcRecord(0x16, cFinMsg);

      while (true) {
        const r = await readRawRecord();
        if (r.contentType === 0x14) break;
      }
      const finRec = await readRawRecord();
      await openCbcRecord(0x16, finRec.body);

      encryptAppRecord = (plainChunk) => sealCbcRecord(0x17, plainChunk);
      decryptAppRecord = async (rec) => {
        if (rec.contentType === 0x15) return { closeNotify: true };
        if (rec.contentType === 0x17) return { data: await openCbcRecord(0x17, rec.body) };
        return null;
      };
    }
  }

  let writeChain = Promise.resolve();
  let closed = false;

  const tlsStream = new BufferedByteStream({
    transportKind: `${rawStream.transportKind}+tls`,
    remoteAddress: rawStream.remoteAddress,
    remotePort: rawStream.remotePort,
    writeFn: async (bytes) => {
      writeChain = writeChain.then(async () => {
        for (let off = 0; off < bytes.length; off += 16384) {
          await encryptAppRecord(bytes.subarray(off, Math.min(bytes.length, off + 16384)));
        }
      });
      return writeChain;
    },
    closeFn: async () => {
      closed = true;
      await rawStream.close();
    }
  });

  // Background TLS record reader pumping decrypted application bytes into `tlsStream`
  (async () => {
    try {
      // Flush any buffered post-ServerFinished bytes if TLS 1.2 hsBuffer had leftover? (Empty after ServerHelloDone)
      while (!closed) {
        const rec = await readRawRecord();
        const res = await decryptAppRecord(rec);
        if (!res) continue;
        if (res.closeNotify) {
          tlsStream.markClosed();
          break;
        }
        if (res.data && res.data.length > 0) {
          tlsStream.pushInbound(res.data);
        }
      }
    } catch (err) {
      if (!closed) tlsStream.markClosed(err);
    }
  })();

  return {
    stream: tlsStream,
    serverPublicKeyBytes,
    cipherSuite: sh.cipherSuite,
    tlsVersion: sh.negotiatedVersion === 0x0304 ? 'TLSv1.3' : 'TLSv1.2'
  };
}

// ============================================================================
// `[MS-CSSP]` CredSSP (v2–v6) + `[MS-NLMP]` NTLMv2 Network Level Authentication
// ============================================================================

const NTLM_FLAGS =
  0x00000001 | // NTLMSSP_NEGOTIATE_UNICODE
  0x00000004 | // NTLMSSP_REQUEST_TARGET
  0x00000010 | // NTLMSSP_NEGOTIATE_SIGN
  0x00000020 | // NTLMSSP_NEGOTIATE_SEAL
  0x00000200 | // NTLMSSP_NEGOTIATE_NTLM
  0x00008000 | // NTLMSSP_NEGOTIATE_ALWAYS_SIGN
  0x00080000 | // NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY
  0x02000000 | // NTLMSSP_NEGOTIATE_VERSION
  0x20000000 | // NTLMSSP_NEGOTIATE_128
  0x40000000 | // NTLMSSP_NEGOTIATE_KEY_EXCH
  0x80000000;  // NTLMSSP_NEGOTIATE_56

const NTLM_VERSION_BYTES = new Uint8Array([0x0a, 0x00, 0x63, 0x45, 0x00, 0x00, 0x00, 0x0f]); // Windows 10.0.17763 NTLMSSP rev 15

export function buildNtlmNegotiateMessage() {
  const sig = new TextEncoder().encode('NTLMSSP\0');
  const out = new Uint8Array(40);
  out.set(sig, 0);
  const dv = new DataView(out.buffer);
  dv.setUint32(8, 1, true); // MessageType = 1
  dv.setUint32(12, NTLM_FLAGS >>> 0, true);
  // DomainNameFields (16..24) = 0, WorkstationFields (24..32) = 0
  out.set(NTLM_VERSION_BYTES, 32);
  return out;
}

export function parseNtlmChallengeMessage(bytes) {
  const sig = new TextDecoder().decode(bytes.subarray(0, 7));
  if (sig !== 'NTLMSSP') throw new Error('Invalid NTLMSSP Challenge signature');
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const msgType = dv.getUint32(8, true);
  if (msgType !== 2) throw new Error(`Expected NTLMSSP Challenge (2), got ${msgType}`);
  const flags = dv.getUint32(20, true);
  const serverChallenge = bytes.slice(24, 32);
  let targetInfo = new Uint8Array(0);
  if ((flags & 0x00800000) !== 0 && bytes.length >= 48) {
    const tiLen = dv.getUint16(40, true);
    const tiOff = dv.getUint32(44, true);
    if (tiLen > 0 && tiOff + tiLen <= bytes.length) {
      targetInfo = bytes.slice(tiOff, tiOff + tiLen);
    }
  }
  return { flags, serverChallenge, targetInfo };
}

function patchTargetInfoAvPairs(serverTargetInfo, targetHost) {
  const pairs = [];
  let pos = 0;
  let timestampBytes = null;
  let existingFlags = 0;
  let sawFlags = false;
  let sawTargetName = false;

  while (pos + 4 <= serverTargetInfo.length) {
    const dv = new DataView(serverTargetInfo.buffer, serverTargetInfo.byteOffset + pos, 4);
    const avId = dv.getUint16(0, true);
    const avLen = dv.getUint16(2, true);
    pos += 4;
    if (avId === 0x0000) break; // MsvAvEOL
    const val = serverTargetInfo.slice(pos, pos + avLen);
    pos += avLen;
    if (avId === 0x0007 && val.length === 8) {
      timestampBytes = val;
    } else if (avId === 0x0006 && val.length === 4) {
      sawFlags = true;
      existingFlags = new DataView(val.buffer, val.byteOffset, 4).getUint32(0, true);
      pairs.push({ avId, val: u32le(existingFlags | 0x00000002) }); // MIC present
      continue;
    } else if (avId === 0x0009) {
      sawTargetName = true;
    }
    pairs.push({ avId, val });
  }

  if (!timestampBytes) {
    // Windows FILETIME: 100-ns intervals since 1601-01-01 UTC
    const ft = (BigInt(Date.now()) + 11644473600000n) * 10000n;
    timestampBytes = new Uint8Array(8);
    const tdv = new DataView(timestampBytes.buffer);
    tdv.setUint32(0, Number(ft & 0xffffffffn) >>> 0, true);
    tdv.setUint32(4, Number((ft >> 32n) & 0xffffffffn) >>> 0, true);
    pairs.push({ avId: 0x0007, val: timestampBytes });
  }
  if (!sawFlags) {
    pairs.push({ avId: 0x0006, val: u32le(0x00000002) }); // MIC present
  }
  if (!sawTargetName && targetHost) {
    pairs.push({ avId: 0x0009, val: encodeUtf16Le(`TERMSRV/${targetHost}`) });
  }

  const encoded = pairs.map((p) => concatBytes([u16le(p.avId), u16le(p.val.length), p.val]));
  encoded.push(new Uint8Array([0x00, 0x00, 0x00, 0x00])); // MsvAvEOL
  return { modifiedTargetInfo: concatBytes(encoded), timestampBytes };
}

export function ntowfv2(password, username, domain) {
  const ntHash = md4Bytes(encodeUtf16Le(password || ''));
  const userDom = encodeUtf16Le(`${String(username || '').toUpperCase()}${String(domain || '')}`);
  return hmacMd5(ntHash, userDom);
}

export function buildNtlmAuthenticateAndSessionKeys({
  negotiateMsg,
  challengeMsgBytes,
  domain = '',
  username = '',
  password = '',
  workstation = 'NEXUSTS',
  targetHost = '',
  clientChallenge8 = null,
  exportedSessionKey16 = null
}) {
  const chal = parseNtlmChallengeMessage(challengeMsgBytes);
  const responseKeyNT = ntowfv2(password, username, domain);
  const cChallenge = clientChallenge8 || crypto.getRandomValues(new Uint8Array(8));
  const { modifiedTargetInfo, timestampBytes } = patchTargetInfoAvPairs(chal.targetInfo, targetHost);

  // temp = 0x0101 + Z(6) + Time(8) + ClientChallenge(8) + Z(4) + ServerName(AvPairs) + Z(4)
  const temp = concatBytes([
    new Uint8Array([0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]),
    timestampBytes,
    cChallenge,
    new Uint8Array(4),
    modifiedTargetInfo,
    new Uint8Array(4)
  ]);

  const ntProofStr = hmacMd5(responseKeyNT, concatBytes([chal.serverChallenge, temp]));
  const ntChallengeResponse = concatBytes([ntProofStr, temp]);
  const lmChallengeResponse = new Uint8Array(24); // Z(24) when MsvAvTimestamp / NTLMv2 MIC present
  const sessionBaseKey = hmacMd5(responseKeyNT, ntProofStr);

  const exportedSessionKey = exportedSessionKey16 || crypto.getRandomValues(new Uint8Array(16));
  const encryptedRandomSessionKey = new Rc4Stream(sessionBaseKey).process(exportedSessionKey);

  const domBytes = encodeUtf16Le(domain);
  const usrBytes = encodeUtf16Le(username);
  const wksBytes = encodeUtf16Le(workstation);

  // Header is 88 bytes (64 fixed + 8 Version + 16 MIC)
  const headerLen = 88;
  let offset = headerLen;
  const place = (b) => {
    const field = { len: b.length, off: offset, bytes: b };
    offset += b.length;
    return field;
  };
  const fDom = place(domBytes);
  const fUsr = place(usrBytes);
  const fWks = place(wksBytes);
  const fLm = place(lmChallengeResponse);
  const fNt = place(ntChallengeResponse);
  const fSess = place(encryptedRandomSessionKey);

  const authMsg = new Uint8Array(offset);
  authMsg.set(new TextEncoder().encode('NTLMSSP\0'), 0);
  const dv = new DataView(authMsg.buffer);
  dv.setUint32(8, 3, true); // MessageType = 3

  const writeSecBuf = (pos, f) => {
    dv.setUint16(pos, f.len, true);
    dv.setUint16(pos + 2, f.len, true);
    dv.setUint32(pos + 4, f.off, true);
    authMsg.set(f.bytes, f.off);
  };
  writeSecBuf(12, fLm);
  writeSecBuf(20, fNt);
  writeSecBuf(28, fDom);
  writeSecBuf(36, fUsr);
  writeSecBuf(44, fWks);
  writeSecBuf(52, fSess);
  dv.setUint32(60, NTLM_FLAGS >>> 0, true);
  authMsg.set(NTLM_VERSION_BYTES, 64);
  // Bytes 72..88 are initially 0 -> compute MIC over NEGOTIATE + CHALLENGE + AUTHENTICATE(zero MIC)
  const mic = hmacMd5(exportedSessionKey, concatBytes([negotiateMsg, challengeMsgBytes, authMsg]));
  authMsg.set(mic, 72);

  // Derive `[MS-NLMP]` §3.4.5.2 & §3.4.5.3 Extended Session Security Sign & Seal keys
  const clientSigningKey = md5Bytes(
    concatBytes([exportedSessionKey, new TextEncoder().encode('session key to client-to-server signing key magic constant\0')])
  );
  const serverSigningKey = md5Bytes(
    concatBytes([exportedSessionKey, new TextEncoder().encode('session key to server-to-client signing key magic constant\0')])
  );
  const clientSealingKey = md5Bytes(
    concatBytes([exportedSessionKey, new TextEncoder().encode('session key to client-to-server sealing key magic constant\0')])
  );
  const serverSealingKey = md5Bytes(
    concatBytes([exportedSessionKey, new TextEncoder().encode('session key to server-to-client sealing key magic constant\0')])
  );

  const clientRc4 = new Rc4Stream(clientSealingKey);
  const serverRc4 = new Rc4Stream(serverSealingKey);
  let clientSeqNum = 0;
  let serverSeqNum = 0;

  const sealMessage = (plainBytes) => {
    const seq = clientSeqNum++;
    const seqBytes = u32le(seq);
    const sealedPayload = clientRc4.process(plainBytes);
    const mac = hmacMd5(clientSigningKey, concatBytes([seqBytes, plainBytes])).subarray(0, 8);
    const encChecksum = clientRc4.process(mac);
    // NTLMSSP_MESSAGE_SIGNATURE (16 bytes: Version=1, Checksum(8), SeqNum(4)) || sealedPayload
    return concatBytes([u32le(1), encChecksum, seqBytes, sealedPayload]);
  };

  const unsealMessage = (sealedToken) => {
    if (sealedToken.length < 16) throw new Error('CredSSP sealed token too short');
    const seq = serverSeqNum++;
    const seqBytes = u32le(seq);
    const encChecksum = sealedToken.subarray(4, 12);
    const cipherPayload = sealedToken.subarray(16);
    const plain = serverRc4.process(cipherPayload);
    const checksum = serverRc4.process(encChecksum);
    const expectedMac = hmacMd5(serverSigningKey, concatBytes([seqBytes, plain])).subarray(0, 8);
    if (checksum.some((b, i) => b !== expectedMac[i])) {
      throw new Error('CredSSP server NTLMv2 signature verification failed (invalid credentials or MITM)');
    }
    return plain;
  };

  return { authMsg, sealMessage, unsealMessage };
}

export function buildTsRequestDer({ version = 6, negoToken = null, authInfo = null, pubKeyAuth = null, clientNonce = null }) {
  const fields = [derExplicit(0, derInteger(version))];
  if (negoToken) {
    // NegoData ::= SEQUENCE OF SEQUENCE { negoToken [0] OCTET STRING }
    const item = derSequence([derExplicit(0, derOctetString(negoToken))]);
    fields.push(derExplicit(1, derSequence([item])));
  }
  if (authInfo) fields.push(derExplicit(2, derOctetString(authInfo)));
  if (pubKeyAuth) fields.push(derExplicit(3, derOctetString(pubKeyAuth)));
  if (clientNonce) fields.push(derExplicit(5, derOctetString(clientNonce)));
  return derSequence(fields);
}

export function parseTsRequestDer(derBytes) {
  const root = derReadElement(derBytes, 0);
  if (root.tag !== 0x30) throw new Error('Expected TSRequest ASN.1 SEQUENCE');
  let pos = 0;
  const out = { version: 2, negoToken: null, authInfo: null, pubKeyAuth: null, errorCode: null, clientNonce: null };
  while (pos < root.content.length) {
    const field = derReadElement(root.content, pos);
    pos = field.nextOffset;
    const ctxTag = field.tag & 0x1f;
    const inner = derReadElement(field.content, 0);
    if (ctxTag === 0 && inner.tag === 0x02) {
      let v = 0;
      for (const b of inner.content) v = (v << 8) | b;
      out.version = v;
    } else if (ctxTag === 1 && inner.tag === 0x30) {
      const firstSeq = derReadElement(inner.content, 0);
      const tokCtx = derReadElement(firstSeq.content, 0);
      const oct = derReadElement(tokCtx.content, 0);
      out.negoToken = oct.content;
    } else if (ctxTag === 2 && inner.tag === 0x04) {
      out.authInfo = inner.content;
    } else if (ctxTag === 3 && inner.tag === 0x04) {
      out.pubKeyAuth = inner.content;
    } else if (ctxTag === 4 && inner.tag === 0x02) {
      let code = 0;
      for (const b of inner.content) code = ((code << 8) | b) >>> 0;
      out.errorCode = code >>> 0;
    } else if (ctxTag === 5 && inner.tag === 0x04) {
      out.clientNonce = inner.content;
    }
  }
  return out;
}

export function buildTsCredentialsDer({ domain = '', username = '', password = '' }) {
  const pwCreds = derSequence([
    derExplicit(0, derOctetString(encodeUtf16Le(domain))),
    derExplicit(1, derOctetString(encodeUtf16Le(username))),
    derExplicit(2, derOctetString(encodeUtf16Le(password)))
  ]);
  return derSequence([
    derExplicit(0, derInteger(1)), // credType = 1 (TSPasswordCreds)
    derExplicit(1, derOctetString(pwCreds))
  ]);
}

async function readCompleteDerElement(stream) {
  let buf = await stream.readSome();
  while (true) {
    if (buf.length >= 2) {
      let lenOffset = 1;
      let len = buf[1];
      let headerReady = true;
      if (len & 0x80) {
        const n = len & 0x7f;
        if (buf.length < 2 + n) {
          headerReady = false;
        } else {
          len = 0;
          for (let i = 0; i < n; i++) len = (len << 8) | buf[2 + i];
          lenOffset = 1 + n;
        }
      }
      if (headerReady) {
        const total = 1 + lenOffset + len;
        if (buf.length >= total) {
          return buf.slice(0, total);
        }
        const rem = await stream.readExact(total - buf.length);
        return concatBytes([buf, rem]);
      }
    }
    const more = await stream.readSome();
    buf = concatBytes([buf, more]);
  }
}

/**
 * Executes full `[MS-CSSP]` CredSSP (v2–v6) + NTLMv2 NLA authentication over an established TLS `BufferedByteStream`.
 */
export async function performCredSspNla(
  tlsStream,
  { domain = '', username = '', password = '', serverPublicKeyBytes, targetHost = '' }
) {
  if (!serverPublicKeyBytes || serverPublicKeyBytes.length === 0) {
    throw new Error('Missing RDP server X.509 public key for CredSSP NLA binding');
  }

  // 1. Client -> Server: TSRequest #1 (NTLMSSP NEGOTIATE_MESSAGE)
  const negMsg = buildNtlmNegotiateMessage();
  await tlsStream.write(buildTsRequestDer({ version: 6, negoToken: negMsg }));

  // 2. Server -> Client: TSRequest #2 (NTLMSSP CHALLENGE_MESSAGE)
  const resp1Der = await readCompleteDerElement(tlsStream);
  const resp1 = parseTsRequestDer(resp1Der);
  if (resp1.errorCode !== null) {
    throw new Error(`CredSSP server rejected negotiation (NTSTATUS 0x${resp1.errorCode.toString(16)})`);
  }
  if (!resp1.negoToken) throw new Error('CredSSP server response missing NTLMSSP Challenge');

  const serverVersion = resp1.version || 2;
  const { authMsg, sealMessage, unsealMessage } = buildNtlmAuthenticateAndSessionKeys({
    negotiateMsg: negMsg,
    challengeMsgBytes: resp1.negoToken,
    domain,
    username,
    password,
    targetHost
  });

  // 3. Client -> Server: TSRequest #3 (NTLMSSP AUTHENTICATE_MESSAGE + pubKeyAuth)
  let clientNonce = null;
  let pubKeyAuthPlain;
  if (serverVersion >= 5) {
    clientNonce = crypto.getRandomValues(new Uint8Array(32));
    const c2sMagic = new TextEncoder().encode('CredSSP Client-To-Server Binding Hash\0');
    pubKeyAuthPlain = await webHash('SHA-256', concatBytes([c2sMagic, clientNonce, serverPublicKeyBytes]));
  } else {
    pubKeyAuthPlain = serverPublicKeyBytes;
  }
  const pubKeyAuthSealed = sealMessage(pubKeyAuthPlain);
  await tlsStream.write(
    buildTsRequestDer({
      version: Math.min(6, serverVersion),
      negoToken: authMsg,
      pubKeyAuth: pubKeyAuthSealed,
      clientNonce
    })
  );

  // 4. Server -> Client: TSRequest #4 (Server pubKeyAuth proof or NTSTATUS logon error!)
  const resp2Der = await readCompleteDerElement(tlsStream);
  const resp2 = parseTsRequestDer(resp2Der);
  if (resp2.errorCode !== null) {
    const hex = resp2.errorCode.toString(16).padStart(8, '0');
    if (resp2.errorCode === 0xc000006d || resp2.errorCode === 0xc000006a) {
      throw new Error(`RDP NLA logon failed: invalid username or password (0x${hex})`);
    }
    throw new Error(`RDP NLA rejected by Windows server (NTSTATUS 0x${hex})`);
  }
  if (!resp2.pubKeyAuth) throw new Error('CredSSP server response missing pubKeyAuth proof');

  const unsealedServerProof = unsealMessage(resp2.pubKeyAuth);
  if (serverVersion >= 5) {
    const s2cMagic = new TextEncoder().encode('CredSSP Server-To-Client Binding Hash\0');
    const expectedProof = await webHash('SHA-256', concatBytes([s2cMagic, clientNonce, serverPublicKeyBytes]));
    if (expectedProof.some((b, i) => b !== unsealedServerProof[i])) {
      throw new Error('CredSSP v5/v6 server public-key binding hash mismatch');
    }
  } else if (((unsealedServerProof[0] - 1) & 0xff) !== serverPublicKeyBytes[0]) {
    throw new Error('CredSSP v2-v4 server public-key proof mismatch');
  }

  // 5. Client -> Server: TSRequest #5 (Encrypted TSCredentials)
  const tsCredsDer = buildTsCredentialsDer({ domain, username, password });
  const sealedAuthInfo = sealMessage(tsCredsDer);
  await tlsStream.write(
    buildTsRequestDer({
      version: Math.min(6, serverVersion),
      authInfo: sealedAuthInfo
    })
  );
}
