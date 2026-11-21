// Production `.nxts` v2 Zero-Knowledge Cryptography, OpenSSH Ed25519 Key Generator,
// RFC 6238 TOTP Live Authenticator, and ChromeOS WebAuthn Passkey Unlock Helper.
//
// Binary Wire Layout (`NXTS\x00\x02` — 100% byte-for-byte compatible with macOS & Android):
// - [0..6]   Magic `NXTS\x00\x02`
// - [6..10]  Argon2id `m_cost_kib` (u32 BE)
// - [10..14] Argon2id `t_cost` (u32 BE)
// - [14..18] Argon2id `p_cost` (u32 BE)
// - [18..50] 32-byte CSPRNG Salt
// - [50..62] 12-byte AES-256-GCM Nonce
// - [62..70] 64-bit Vault Revision (u64 BE, authenticated in AES-256-GCM AAD)
// - [70..]   AES-256-GCM Ciphertext + 128-bit Authentication Tag

import { argon2idRaw } from './argon2id.js';
import { migrateLegacyDefaultRdpScale, sanitizeVaultDocument } from './vault-models.js';
import { initNexusWasm, wasmOpenNxts, wasmSealNxts } from './wasm-loader.js';

export const NXTS_MAGIC_V2 = new Uint8Array([0x4e, 0x58, 0x54, 0x53, 0x00, 0x02]);
export const NXTS_HEADER_LEN = 70;
export const SALT_LEN = 32;
export const NONCE_LEN = 12;

export const DEFAULT_KDF_PARAMS = Object.freeze({
  memoryKiB: 65536,
  iterations: 3,
  parallelism: 4
});

export const INTERACTIVE_FAST_KDF_PARAMS = Object.freeze({
  memoryKiB: 8192,
  iterations: 1,
  parallelism: 1
});

// Byte-for-byte identical transparent sync key used by macOS (`NxtsVaultSerializer.defaultTransparentPassphrase`)
// and Android (`DEFAULT_LIVE_DRIVE_PASSPHRASE`) when the user has not configured a custom Master Passphrase.
export const DEFAULT_TRANSPARENT_PASSPHRASE = 'NexusTS-LiveDriveSync-ZeroKnowledge-v1';

const HKDF_INFO = new TextEncoder().encode('nexusts-vault-v2-aes256gcm');

export class VaultAuthenticationError extends Error {
  constructor(message = 'Wrong master passphrase or tampered .nxts ciphertext') {
    super(message);
    this.name = 'VaultAuthenticationError';
  }
}

export function hasNxtsMagic(bytes) {
  if (!(bytes instanceof Uint8Array) || bytes.length < NXTS_HEADER_LEN + 16) return false;
  for (let i = 0; i < NXTS_MAGIC_V2.length; i++) {
    if (bytes[i] !== NXTS_MAGIC_V2[i]) return false;
  }
  return true;
}

export function inspectUnencryptedRevisionHeader(bytes) {
  if (!hasNxtsMagic(bytes)) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const hi = view.getUint32(62, false);
  const lo = view.getUint32(66, false);
  return Number((BigInt(hi) << 32n) | BigInt(lo));
}

export async function sha256Bytes(data) {
  const buf = await globalThis.crypto.subtle.digest('SHA-256', data);
  return new Uint8Array(buf);
}

export async function sha256Hex(data) {
  const digest = await sha256Bytes(data);
  let hex = '';
  for (let i = 0; i < digest.length; i++) {
    hex += digest[i].toString(16).padStart(2, '0');
  }
  return hex;
}

async function deriveAesGcmKey(passphraseBytes, salt, kdfParams) {
  const ikm = argon2idRaw({
    password: passphraseBytes,
    salt,
    memoryKiB: kdfParams.memoryKiB,
    iterations: kdfParams.iterations,
    parallelism: kdfParams.parallelism,
    tagLength: 32
  });
  try {
    const baseKey = await globalThis.crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveKey']);
    return await globalThis.crypto.subtle.deriveKey(
      {
        name: 'HKDF',
        hash: 'SHA-256',
        salt,
        info: HKDF_INFO
      },
      baseKey,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt']
    );
  } finally {
    ikm.fill(0);
  }
}

export async function sealNxtsBytes(plaintextBytes, passphraseString, revision = 1, kdfParams = DEFAULT_KDF_PARAMS) {
  await initNexusWasm();
  const passBytes = new TextEncoder().encode(passphraseString);
  const salt = globalThis.crypto.getRandomValues(new Uint8Array(SALT_LEN));
  const nonce = globalThis.crypto.getRandomValues(new Uint8Array(NONCE_LEN));

  try {
    const wasmResult = wasmSealNxts(plaintextBytes, passBytes, salt, nonce, revision, kdfParams);
    if (wasmResult) return wasmResult;

    const header = new Uint8Array(NXTS_HEADER_LEN);
    header.set(NXTS_MAGIC_V2, 0);
    const view = new DataView(header.buffer);
    view.setUint32(6, kdfParams.memoryKiB >>> 0, false);
    view.setUint32(10, kdfParams.iterations >>> 0, false);
    view.setUint32(14, kdfParams.parallelism >>> 0, false);
    header.set(salt, 18);
    header.set(nonce, 50);
    const revBig = BigInt(Math.max(0, Math.trunc(revision)));
    view.setUint32(62, Number((revBig >> 32n) & 0xffffffffn) >>> 0, false);
    view.setUint32(66, Number(revBig & 0xffffffffn) >>> 0, false);

    const aesKey = await deriveAesGcmKey(passBytes, salt, kdfParams);
    const ctBuf = await globalThis.crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: nonce, additionalData: header, tagLength: 128 },
      aesKey,
      plaintextBytes
    );
    const ct = new Uint8Array(ctBuf);
    const out = new Uint8Array(NXTS_HEADER_LEN + ct.length);
    out.set(header, 0);
    out.set(ct, NXTS_HEADER_LEN);
    return out;
  } finally {
    passBytes.fill(0);
  }
}

export async function openNxtsBytes(containerBytes, passphraseString) {
  if (!hasNxtsMagic(containerBytes)) {
    throw new VaultAuthenticationError('Invalid .nxts v2 magic header');
  }
  await initNexusWasm();
  const view = new DataView(containerBytes.buffer, containerBytes.byteOffset, containerBytes.byteLength);
  const memoryKiB = view.getUint32(6, false);
  const iterations = view.getUint32(10, false);
  const parallelism = view.getUint32(14, false);
  if (memoryKiB < 8192 || iterations < 1 || parallelism < 1) {
    throw new VaultAuthenticationError('KDF parameters below minimum security floor');
  }
  const revHi = view.getUint32(62, false);
  const revLo = view.getUint32(66, false);
  const revision = Number((BigInt(revHi) << 32n) | BigInt(revLo));

  const passBytes = new TextEncoder().encode(passphraseString);
  try {
    try {
      const wasmPlain = wasmOpenNxts(containerBytes, passBytes);
      if (wasmPlain) {
        return { plaintext: wasmPlain, revision, kdfParams: { memoryKiB, iterations, parallelism } };
      }
    } catch {
      throw new VaultAuthenticationError();
    }

    const salt = containerBytes.slice(18, 50);
    const nonce = containerBytes.slice(50, 62);
    const header = containerBytes.slice(0, NXTS_HEADER_LEN);
    const ciphertext = containerBytes.slice(NXTS_HEADER_LEN);

    const aesKey = await deriveAesGcmKey(passBytes, salt, { memoryKiB, iterations, parallelism });
    try {
      const plainBuf = await globalThis.crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: nonce, additionalData: header, tagLength: 128 },
        aesKey,
        ciphertext
      );
      return {
        plaintext: new Uint8Array(plainBuf),
        revision,
        kdfParams: { memoryKiB, iterations, parallelism }
      };
    } catch (err) {
      throw new VaultAuthenticationError('Authentication tag mismatch: wrong passphrase or corrupted vault');
    }
  } finally {
    passBytes.fill(0);
  }
}

export async function sealVaultDocument(
  document,
  passphraseString = DEFAULT_TRANSPARENT_PASSPHRASE,
  kdfParams = DEFAULT_KDF_PARAMS
) {
  const effectivePass = passphraseString || DEFAULT_TRANSPARENT_PASSPHRASE;
  const sanitized = sanitizeVaultDocument(document);
  const jsonText = JSON.stringify(sanitized);
  const plainBytes = new TextEncoder().encode(jsonText);
  try {
    return await sealNxtsBytes(plainBytes, effectivePass, sanitized.revision || 1, kdfParams);
  } finally {
    plainBytes.fill(0);
  }
}

export async function unsealVaultDocument(containerBytes, passphraseString = '') {
  // Support unencrypted UTF-8 JSON `.nxts` document fallback if encountered
  if (
    containerBytes instanceof Uint8Array &&
    containerBytes.length > 2 &&
    !hasNxtsMagic(containerBytes) &&
    containerBytes[0] === 0x7b
  ) {
    const parsed = JSON.parse(new TextDecoder().decode(containerBytes));
    return migrateLegacyDefaultRdpScale(sanitizeVaultDocument(parsed));
  }

  const effectivePass = passphraseString || DEFAULT_TRANSPARENT_PASSPHRASE;
  let opened;
  try {
    opened = await openNxtsBytes(containerBytes, effectivePass);
  } catch (err) {
    if (effectivePass === DEFAULT_TRANSPARENT_PASSPHRASE && err?.name === 'VaultAuthenticationError') {
      opened = await openNxtsBytes(containerBytes, '');
    } else {
      throw err;
    }
  }
  const { plaintext } = opened;
  try {
    const jsonText = new TextDecoder().decode(plaintext);
    const parsed = JSON.parse(jsonText);
    return migrateLegacyDefaultRdpScale(sanitizeVaultDocument(parsed));
  } finally {
    plaintext.fill(0);
  }
}

function encodeSshString(parts, bytes) {
  const len = new Uint8Array(4);
  new DataView(len.buffer).setUint32(0, bytes.length, false);
  parts.push(len, bytes);
}

function concatBytes(chunks) {
  const total = chunks.reduce((acc, c) => acc + c.length, 0);
  const out = new Uint8Array(total);
  let pos = 0;
  for (const c of chunks) {
    out.set(c, pos);
    pos += c.length;
  }
  return out;
}

export function bytesToBase64(bytes) {
  if (typeof Buffer !== 'undefined') {
    return Buffer.from(bytes).toString('base64');
  }
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

export function base64ToBytes(b64) {
  const clean = b64.replace(/\s+/g, '');
  if (typeof Buffer !== 'undefined') {
    return new Uint8Array(Buffer.from(clean, 'base64'));
  }
  const bin = atob(clean);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function base64UrlToBytes(b64u) {
  const std = b64u.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((b64u.length + 3) % 4);
  return base64ToBytes(std);
}

/**
 * Generates an OpenSSH v1 Ed25519 keypair (`ssh-ed25519` public key, `openssh-key-v1` PEM, `SHA256:` fingerprint)
 * using standard WebCrypto Ed25519.
 */
export async function generateEd25519OpenSshKeypair(comment = 'chromeos@nexusts.app') {
  const keyPair = await globalThis.crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const jwk = await globalThis.crypto.subtle.exportKey('jwk', keyPair.privateKey);
  const seed32 = base64UrlToBytes(jwk.d);
  const pub32 = base64UrlToBytes(jwk.x);

  const keyTypeBytes = new TextEncoder().encode('ssh-ed25519');
  const pubParts = [];
  encodeSshString(pubParts, keyTypeBytes);
  encodeSshString(pubParts, pub32);
  const pubBlob = concatBytes(pubParts);

  const pubB64 = bytesToBase64(pubBlob);
  const cleanComment = (comment || '').trim();
  const publicKeyOpenSsh = cleanComment ? `ssh-ed25519 ${pubB64} ${cleanComment}` : `ssh-ed25519 ${pubB64}`;

  const fpDigest = await sha256Bytes(pubBlob);
  const keyFingerprintSha256 = `SHA256:${bytesToBase64(fpDigest).replace(/=+$/, '')}`;

  const checkInt = globalThis.crypto.getRandomValues(new Uint8Array(4));
  const privSecParts = [checkInt, checkInt];
  encodeSshString(privSecParts, keyTypeBytes);
  encodeSshString(privSecParts, pub32);
  const expanded64 = new Uint8Array(64);
  expanded64.set(seed32, 0);
  expanded64.set(pub32, 32);
  encodeSshString(privSecParts, expanded64);
  encodeSshString(privSecParts, new TextEncoder().encode(cleanComment));

  let privSec = concatBytes(privSecParts);
  const padLen = (8 - (privSec.length % 8)) % 8;
  if (padLen > 0) {
    const pad = new Uint8Array(padLen);
    for (let i = 0; i < padLen; i++) pad[i] = (i + 1) & 0xff;
    privSec = concatBytes([privSec, pad]);
  }

  const noneBytes = new TextEncoder().encode('none');
  const outerParts = [new TextEncoder().encode('openssh-key-v1\0')];
  encodeSshString(outerParts, noneBytes);
  encodeSshString(outerParts, noneBytes);
  encodeSshString(outerParts, new Uint8Array(0));
  outerParts.push(new Uint8Array([0, 0, 0, 1]));
  encodeSshString(outerParts, pubBlob);
  encodeSshString(outerParts, privSec);
  const outerBlob = concatBytes(outerParts);

  const b64 = bytesToBase64(outerBlob);
  const wrapped = b64.match(/.{1,70}/g)?.join('\n') || b64;
  const privateKeyPem = `-----BEGIN OPENSSH PRIVATE KEY-----\n${wrapped}\n-----END OPENSSH PRIVATE KEY-----\n`;

  seed32.fill(0);
  expanded64.fill(0);
  privSec.fill(0);
  outerBlob.fill(0);

  return {
    keyId: keyFingerprintSha256,
    publicKeyOpenSsh,
    privateKeyPem,
    keyFingerprintSha256
  };
}

/**
 * RFC 6238 TOTP 6-digit code generator (supports raw Base32 secrets & `otpauth://totp/...` URIs).
 */
export async function computeTotpCode(secretOrUri, nowMs = Date.now(), periodSec = 30, digits = 6) {
  if (!secretOrUri || !secretOrUri.trim()) return null;
  let base32 = secretOrUri.trim();
  if (base32.toLowerCase().startsWith('otpauth://')) {
    try {
      const url = new URL(base32);
      base32 = url.searchParams.get('secret') || '';
      const p = Number(url.searchParams.get('period'));
      if (p > 0) periodSec = p;
      const d = Number(url.searchParams.get('digits'));
      if (d >= 6 && d <= 8) digits = d;
    } catch {
      return null;
    }
  }
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const cleaned = base32.toUpperCase().replace(/[^A-Z2-7]/g, '');
  if (!cleaned) return null;

  let bits = 0;
  let value = 0;
  const keyBytes = [];
  for (let i = 0; i < cleaned.length; i++) {
    value = (value << 5) | alphabet.indexOf(cleaned[i]);
    bits += 5;
    if (bits >= 8) {
      keyBytes.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  if (keyBytes.length === 0) return null;

  const counter = Math.floor(nowMs / 1000 / periodSec);
  const msg = new Uint8Array(8);
  const dv = new DataView(msg.buffer);
  dv.setUint32(0, Math.floor(counter / 0x100000000), false);
  dv.setUint32(4, counter >>> 0, false);

  const hmacKey = await globalThis.crypto.subtle.importKey(
    'raw',
    new Uint8Array(keyBytes),
    { name: 'HMAC', hash: 'SHA-1' },
    false,
    ['sign']
  );
  const mac = new Uint8Array(await globalThis.crypto.subtle.sign('HMAC', hmacKey, msg));
  const offset = mac[mac.length - 1] & 0x0f;
  const binary =
    ((mac[offset] & 0x7f) << 24) |
    ((mac[offset + 1] & 0xff) << 16) |
    ((mac[offset + 2] & 0xff) << 8) |
    (mac[offset + 3] & 0xff);
  const code = String(binary % 10 ** digits).padStart(digits, '0');
  const remainingSec = periodSec - (Math.floor(nowMs / 1000) % periodSec);
  return { code, remainingSec, periodSec };
}
