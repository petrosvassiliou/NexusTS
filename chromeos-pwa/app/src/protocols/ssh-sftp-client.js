// Dual-Mode SSHv2 (`RFC 4253 / 4252 / 4254`) + SFTP v3 (`draft-ietf-secsh-filexfer-02`) Engine:
// 1. Standalone Browser Direct-Sockets Mode (`openRawTcpStream` over `window.TCPSocket`):
//    Speaks real SSH-2.0 binary packet protocol directly in WebCrypto (`ecdh-sha2-nistp256` KEX,
//    `aes128-ctr` + `hmac-sha2-256` transport cipher, `password` & `ssh-ed25519` `publickey` auth,
//    interactive `pty-req` `xterm-256color` + `shell` + `window-change`, and SFTP v3 `SSH_FXP_*`!).
// 2. Local Crostini Relay Mode (`nexusts-local-relay` `/v1/ssh` & `/v1/sftp` powered by `russh`):
//    Used automatically in Standard PWA mode or when SSH Bastion Jump Host chaining is configured.

import { base64ToBytes, bytesToBase64, sha256Bytes } from '../core/nxts-crypto.js';
import { DEFAULT_LOCAL_RELAY_HTTP, DEFAULT_LOCAL_RELAY_WS, hasDirectSocketsApi, openRawTcpStream } from './socket-transport.js';

const SSH_MSG_DISCONNECT = 1;
const SSH_MSG_IGNORE = 2;
const SSH_MSG_UNIMPLEMENTED = 3;
const SSH_MSG_DEBUG = 4;
const SSH_MSG_SERVICE_REQUEST = 5;
const SSH_MSG_SERVICE_ACCEPT = 6;
const SSH_MSG_KEXINIT = 20;
const SSH_MSG_NEWKEYS = 21;
const SSH_MSG_KEX_ECDH_INIT = 30;
const SSH_MSG_KEX_ECDH_REPLY = 31;
const SSH_MSG_USERAUTH_REQUEST = 50;
const SSH_MSG_USERAUTH_FAILURE = 51;
const SSH_MSG_USERAUTH_SUCCESS = 52;
const SSH_MSG_USERAUTH_BANNER = 53;
const SSH_MSG_GLOBAL_REQUEST = 80;
const SSH_MSG_CHANNEL_OPEN = 90;
const SSH_MSG_CHANNEL_OPEN_CONFIRMATION = 91;
const SSH_MSG_CHANNEL_OPEN_FAILURE = 92;
const SSH_MSG_CHANNEL_WINDOW_ADJUST = 93;
const SSH_MSG_CHANNEL_DATA = 94;
const SSH_MSG_CHANNEL_EXTENDED_DATA = 95;
const SSH_MSG_CHANNEL_EOF = 96;
const SSH_MSG_CHANNEL_CLOSE = 97;
const SSH_MSG_CHANNEL_REQUEST = 98;
const SSH_MSG_CHANNEL_SUCCESS = 99;
const SSH_MSG_CHANNEL_FAILURE = 100;

const SSH_FXP_INIT = 1;
const SSH_FXP_VERSION = 2;
const SSH_FXP_OPEN = 3;
const SSH_FXP_CLOSE = 4;
const SSH_FXP_READ = 5;
const SSH_FXP_WRITE = 6;
const SSH_FXP_OPENDIR = 11;
const SSH_FXP_READDIR = 12;
const SSH_FXP_REMOVE = 13;
const SSH_FXP_MKDIR = 14;
const SSH_FXP_RMDIR = 15;
const SSH_FXP_REALPATH = 16;
const SSH_FXP_RENAME = 18;
const SSH_FXP_STATUS = 101;
const SSH_FXP_HANDLE = 102;
const SSH_FXP_DATA = 103;
const SSH_FXP_NAME = 104;

export class SshWriter {
  constructor() {
    this.parts = [];
  }
  u8(v) {
    this.parts.push(new Uint8Array([v & 0xff]));
    return this;
  }
  bool(v) {
    return this.u8(v ? 1 : 0);
  }
  u32(v) {
    const b = new Uint8Array(4);
    new DataView(b.buffer).setUint32(0, v >>> 0, false);
    this.parts.push(b);
    return this;
  }
  u64(v) {
    const big = BigInt(v);
    const b = new Uint8Array(8);
    const dv = new DataView(b.buffer);
    dv.setUint32(0, Number((big >> 32n) & 0xffffffffn) >>> 0, false);
    dv.setUint32(4, Number(big & 0xffffffffn) >>> 0, false);
    this.parts.push(b);
    return this;
  }
  raw(bytes) {
    this.parts.push(bytes);
    return this;
  }
  bytes(bytes) {
    this.u32(bytes.length);
    this.parts.push(bytes);
    return this;
  }
  str(s) {
    return this.bytes(new TextEncoder().encode(String(s ?? '')));
  }
  mpint(unsignedBytes) {
    let start = 0;
    while (start < unsignedBytes.length && unsignedBytes[start] === 0) start++;
    if (start === unsignedBytes.length) return this.u32(0);
    const trimmed = unsignedBytes.subarray(start);
    if (trimmed[0] & 0x80) {
      const padded = new Uint8Array(1 + trimmed.length);
      padded.set(trimmed, 1);
      return this.bytes(padded);
    }
    return this.bytes(trimmed);
  }
  finish() {
    const out = new Uint8Array(this.parts.reduce((a, p) => a + p.length, 0));
    let pos = 0;
    for (const p of this.parts) { out.set(p, pos); pos += p.length; }
    return out;
  }
}

export class SshReader {
  constructor(bytes) {
    this.buf = bytes;
    this.dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    this.pos = 0;
  }
  u8() {
    return this.buf[this.pos++];
  }
  bool() {
    return this.u8() !== 0;
  }
  u32() {
    const v = this.dv.getUint32(this.pos, false);
    this.pos += 4;
    return v;
  }
  u64() {
    const hi = this.u32();
    const lo = this.u32();
    return Number((BigInt(hi) << 32n) | BigInt(lo));
  }
  bytes() {
    const len = this.u32();
    const slice = this.buf.subarray(this.pos, this.pos + len);
    this.pos += len;
    return slice;
  }
  str() {
    return new TextDecoder().decode(this.bytes());
  }
}

function incrementCounter16(iv16, blocks = 1) {
  let carry = blocks >>> 0;
  for (let i = 15; i >= 0 && carry > 0; i--) {
    const sum = iv16[i] + (carry & 0xff);
    iv16[i] = sum & 0xff;
    carry = (carry >>> 8) + (sum >>> 8);
  }
}

async function deriveSshKeyMaterial(sharedMpint, exchangeHash, sessionId, letterChar, neededBytes) {
  const first = new SshWriter()
    .raw(sharedMpint)
    .raw(exchangeHash)
    .u8(letterChar.charCodeAt(0))
    .raw(sessionId)
    .finish();
  let k = await sha256Bytes(first);
  while (k.length < neededBytes) {
    const nextIn = new SshWriter().raw(sharedMpint).raw(exchangeHash).raw(k).finish();
    const nextBlock = await sha256Bytes(nextIn);
    const merged = new Uint8Array(k.length + nextBlock.length);
    merged.set(k, 0);
    merged.set(nextBlock, k.length);
    k = merged;
  }
  return k.subarray(0, neededBytes);
}

/**
 * Extracts raw 32-byte Ed25519 seed & 32-byte public key from an `openssh-key-v1` PEM.
 */
export function parseOpenSshEd25519PrivateKeyPem(pemText) {
  const b64 = String(pemText || '')
    .replace(/-----BEGIN OPENSSH PRIVATE KEY-----/g, '')
    .replace(/-----END OPENSSH PRIVATE KEY-----/g, '')
    .replace(/\s+/g, '');
  const blob = base64ToBytes(b64);
  const magic = 'openssh-key-v1\0';
  const head = new TextDecoder().decode(blob.subarray(0, magic.length));
  if (head !== magic) throw new Error('Unsupported SSH private key format (expected openssh-key-v1)');

  const outer = new SshReader(blob.subarray(magic.length));
  const cipherName = outer.str();
  if (cipherName !== 'none') throw new Error('Encrypted OpenSSH PEM requires unencrypted key in vault');
  outer.str(); // kdfname
  outer.bytes(); // kdfoptions
  const nKeys = outer.u32();
  if (nKeys < 1) throw new Error('Empty OpenSSH key');
  const pubBlob = outer.bytes();
  const privSection = outer.bytes();

  const inner = new SshReader(privSection);
  const c1 = inner.u32();
  const c2 = inner.u32();
  if (c1 !== c2) throw new Error('Corrupted OpenSSH private key checkint');
  const keyType = inner.str();
  if (keyType !== 'ssh-ed25519') throw new Error(`Expected ssh-ed25519 key, got ${keyType}`);
  const pub32 = inner.bytes();
  const expanded64 = inner.bytes();
  const seed32 = expanded64.slice(0, 32);
  return { pubBlob: pubBlob.slice(), pub32: pub32.slice(), seed32 };
}

function toBase64Url(bytes) {
  return bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function signSshEd25519(seed32, pub32, messageBytes) {
  const jwk = {
    kty: 'OKP',
    crv: 'Ed25519',
    d: toBase64Url(seed32),
    x: toBase64Url(pub32)
  };
  const key = await globalThis.crypto.subtle.importKey('jwk', jwk, { name: 'Ed25519' }, false, ['sign']);
  const sig = new Uint8Array(await globalThis.crypto.subtle.sign({ name: 'Ed25519' }, key, messageBytes));
  return new SshWriter().str('ssh-ed25519').bytes(sig).finish();
}

/**
 * Full Standalone RFC 4253/4252/4254 SSH-2.0 Client over `openRawTcpStream` (ChromeOS Direct Sockets!).
 */
export class DirectSsh2Connection {
  constructor(stream) {
    this.stream = stream;
    this.clientVersion = 'SSH-2.0-NexusTS_0.9.3_ChromeOS';
    this.serverVersion = '';
    this.seqOut = 0;
    this.seqIn = 0;
    this.sessionId = null;
    this.encOutKey = null;
    this.encOutIv = null;
    this.macOutKey = null;
    this.encInKey = null;
    this.encInIv = null;
    this.macInKey = null;
    this._writeChain = Promise.resolve();
  }

  async sendPacket(payload) {
    this._writeChain = this._writeChain.then(async () => {
      const seq = this.seqOut >>> 0;
      this.seqOut = (this.seqOut + 1) >>> 0;
      const blockSize = this.encOutKey ? 16 : 8;
      let padLen = blockSize - ((5 + payload.length) % blockSize);
      if (padLen < 4) padLen += blockSize;
      const pktLen = 1 + payload.length + padLen;
      const clear = new Uint8Array(4 + pktLen);
      new DataView(clear.buffer).setUint32(0, pktLen, false);
      clear[4] = padLen;
      clear.set(payload, 5);
      clear.set(globalThis.crypto.getRandomValues(new Uint8Array(padLen)), 5 + payload.length);

      if (!this.encOutKey) {
        await this.stream.write(clear);
        return;
      }

      const seqPrefix = new Uint8Array(4 + clear.length);
      new DataView(seqPrefix.buffer).setUint32(0, seq, false);
      seqPrefix.set(clear, 4);
      const mac = new Uint8Array(await globalThis.crypto.subtle.sign('HMAC', this.macOutKey, seqPrefix));

      const counterCopy = this.encOutIv.slice();
      const encrypted = new Uint8Array(
        await globalThis.crypto.subtle.encrypt(
          { name: 'AES-CTR', counter: counterCopy, length: 128 },
          this.encOutKey,
          clear
        )
      );
      incrementCounter16(this.encOutIv, clear.length / 16);

      const wire = new Uint8Array(encrypted.length + mac.length);
      wire.set(encrypted, 0);
      wire.set(mac, encrypted.length);
      await this.stream.write(wire);
    });
    return this._writeChain;
  }

  async readPacket() {
    while (true) {
      const seq = this.seqIn >>> 0;
      this.seqIn = (this.seqIn + 1) >>> 0;
      let clear;
      if (!this.encInKey) {
        const first4 = await this.stream.readExact(4);
        const pktLen = new DataView(first4.buffer, first4.byteOffset, 4).getUint32(0, false);
        if (pktLen < 2 || pktLen > 262144) throw new Error(`Invalid SSH packet length: ${pktLen}`);
        const rest = await this.stream.readExact(pktLen);
        clear = new Uint8Array(4 + pktLen);
        clear.set(first4, 0);
        clear.set(rest, 4);
      } else {
        const firstBlockEnc = await this.stream.readExact(16);
        const iv0 = this.encInIv.slice();
        const firstBlock = new Uint8Array(
          await globalThis.crypto.subtle.decrypt(
            { name: 'AES-CTR', counter: iv0, length: 128 },
            this.encInKey,
            firstBlockEnc
          )
        );
        incrementCounter16(this.encInIv, 1);
        const pktLen = new DataView(firstBlock.buffer).getUint32(0, false);
        if (pktLen < 12 || pktLen > 1048576) throw new Error(`Invalid encrypted SSH packet length: ${pktLen}`);
        const remBytes = 4 + pktLen - 16;
        let restClear = new Uint8Array(0);
        if (remBytes > 0) {
          const restEnc = await this.stream.readExact(remBytes);
          const ivRest = this.encInIv.slice();
          restClear = new Uint8Array(
            await globalThis.crypto.subtle.decrypt(
              { name: 'AES-CTR', counter: ivRest, length: 128 },
              this.encInKey,
              restEnc
            )
          );
          incrementCounter16(this.encInIv, remBytes / 16);
        }
        const macWire = await this.stream.readExact(32);
        clear = new Uint8Array(16 + restClear.length);
        clear.set(firstBlock, 0);
        clear.set(restClear, 16);

        const seqPrefix = new Uint8Array(4 + clear.length);
        new DataView(seqPrefix.buffer).setUint32(0, seq, false);
        seqPrefix.set(clear, 4);
        const ok = await globalThis.crypto.subtle.verify('HMAC', this.macInKey, macWire, seqPrefix);
        if (!ok) throw new Error('SSH HMAC-SHA2-256 integrity verification failed');
      }

      const padLen = clear[4];
      const payload = clear.slice(5, clear.length - padLen);
      const msgType = payload[0];
      if (msgType === SSH_MSG_IGNORE || msgType === SSH_MSG_DEBUG || msgType === SSH_MSG_USERAUTH_BANNER) {
        continue;
      }
      if (msgType === SSH_MSG_DISCONNECT) {
        const r = new SshReader(payload.subarray(1));
        r.u32();
        throw new Error(`SSH server disconnected: ${r.str()}`);
      }
      return payload;
    }
  }

  async performHandshakeAndAuth({ username, password = '', privateKeyPem = '' }) {
    // 1. Version exchange
    await this.stream.write(`${this.clientVersion}\r\n`);
    while (true) {
      const line = (await this.stream.readLine()).replace(/\r?\n$/, '');
      if (line.startsWith('SSH-2.0-') || line.startsWith('SSH-1.99-')) {
        this.serverVersion = line;
        break;
      }
    }

    // 2. KEXINIT
    const cookie = globalThis.crypto.getRandomValues(new Uint8Array(16));
    const clientKexInit = new SshWriter()
      .u8(SSH_MSG_KEXINIT)
      .raw(cookie)
      .str('ecdh-sha2-nistp256')
      .str('ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-256')
      .str('aes128-ctr')
      .str('aes128-ctr')
      .str('hmac-sha2-256')
      .str('hmac-sha2-256')
      .str('none')
      .str('none')
      .str('')
      .str('')
      .bool(false)
      .u32(0)
      .finish();
    await this.sendPacket(clientKexInit);

    const serverKexInit = await this.readPacket();
    if (serverKexInit[0] !== SSH_MSG_KEXINIT) throw new Error('Expected SSH_MSG_KEXINIT');

    // 3. ECDH P-256 Key Exchange (`ecdh-sha2-nistp256`)
    const ecdhPair = await globalThis.crypto.subtle.generateKey(
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    );
    const qcBytes = new Uint8Array(await globalThis.crypto.subtle.exportKey('raw', ecdhPair.publicKey));
    await this.sendPacket(new SshWriter().u8(SSH_MSG_KEX_ECDH_INIT).bytes(qcBytes).finish());

    const reply = await this.readPacket();
    if (reply[0] !== SSH_MSG_KEX_ECDH_REPLY) throw new Error('Expected SSH_MSG_KEX_ECDH_REPLY');
    const rr = new SshReader(reply.subarray(1));
    const ksBytes = rr.bytes();
    const qsBytes = rr.bytes();
    const _sigH = rr.bytes();

    const serverPubKey = await globalThis.crypto.subtle.importKey(
      'raw',
      qsBytes,
      { name: 'ECDH', namedCurve: 'P-256' },
      false,
      []
    );
    const sharedSecretRaw = new Uint8Array(
      await globalThis.crypto.subtle.deriveBits({ name: 'ECDH', public: serverPubKey }, ecdhPair.privateKey, 256)
    );
    const sharedMpint = new SshWriter().mpint(sharedSecretRaw).finish();

    const hInput = new SshWriter()
      .str(this.clientVersion)
      .str(this.serverVersion)
      .bytes(clientKexInit)
      .bytes(serverKexInit)
      .bytes(ksBytes)
      .bytes(qcBytes)
      .bytes(qsBytes)
      .raw(sharedMpint)
      .finish();
    const exchangeHash = await sha256Bytes(hInput);
    this.sessionId = exchangeHash;

    // 4. NEWKEYS
    await this.sendPacket(new Uint8Array([SSH_MSG_NEWKEYS]));
    const newKeysIn = await this.readPacket();
    if (newKeysIn[0] !== SSH_MSG_NEWKEYS) throw new Error('Expected SSH_MSG_NEWKEYS');

    const ivC2S = await deriveSshKeyMaterial(sharedMpint, exchangeHash, this.sessionId, 'A', 16);
    const ivS2C = await deriveSshKeyMaterial(sharedMpint, exchangeHash, this.sessionId, 'B', 16);
    const keyC2S = await deriveSshKeyMaterial(sharedMpint, exchangeHash, this.sessionId, 'C', 16);
    const keyS2C = await deriveSshKeyMaterial(sharedMpint, exchangeHash, this.sessionId, 'D', 16);
    const macC2S = await deriveSshKeyMaterial(sharedMpint, exchangeHash, this.sessionId, 'E', 32);
    const macS2C = await deriveSshKeyMaterial(sharedMpint, exchangeHash, this.sessionId, 'F', 32);

    this.encOutIv = ivC2S.slice();
    this.encInIv = ivS2C.slice();
    this.encOutKey = await globalThis.crypto.subtle.importKey('raw', keyC2S, { name: 'AES-CTR' }, false, ['encrypt']);
    this.encInKey = await globalThis.crypto.subtle.importKey('raw', keyS2C, { name: 'AES-CTR' }, false, ['decrypt']);
    this.macOutKey = await globalThis.crypto.subtle.importKey('raw', macC2S, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    this.macInKey = await globalThis.crypto.subtle.importKey('raw', macS2C, { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);

    // 5. Request `ssh-userauth`
    await this.sendPacket(new SshWriter().u8(SSH_MSG_SERVICE_REQUEST).str('ssh-userauth').finish());
    const svcAccept = await this.readPacket();
    if (svcAccept[0] !== SSH_MSG_SERVICE_ACCEPT) throw new Error('SSH service request rejected');

    // 6. Authenticate (Ed25519 private key first if provided, else password)
    const user = username || 'root';
    if (privateKeyPem?.trim()) {
      const { pubBlob, pub32, seed32 } = parseOpenSshEd25519PrivateKeyPem(privateKeyPem);
      const signData = new SshWriter()
        .bytes(this.sessionId)
        .u8(SSH_MSG_USERAUTH_REQUEST)
        .str(user)
        .str('ssh-connection')
        .str('publickey')
        .bool(true)
        .str('ssh-ed25519')
        .bytes(pubBlob)
        .finish();
      const sigBlob = await signSshEd25519(seed32, pub32, signData);
      seed32.fill(0);
      await this.sendPacket(
        new SshWriter()
          .u8(SSH_MSG_USERAUTH_REQUEST)
          .str(user)
          .str('ssh-connection')
          .str('publickey')
          .bool(true)
          .str('ssh-ed25519')
          .bytes(pubBlob)
          .bytes(sigBlob)
          .finish()
      );
      const authRep = await this.readPacket();
      if (authRep[0] === SSH_MSG_USERAUTH_SUCCESS) return;
      if (!password) throw new Error('SSH Ed25519 publickey authentication rejected');
    }

    await this.sendPacket(
      new SshWriter()
        .u8(SSH_MSG_USERAUTH_REQUEST)
        .str(user)
        .str('ssh-connection')
        .str('password')
        .bool(false)
        .str(password || '')
        .finish()
    );
    const passRep = await this.readPacket();
    if (passRep[0] !== SSH_MSG_USERAUTH_SUCCESS) {
      throw new Error(passRep[0] === SSH_MSG_USERAUTH_FAILURE ? 'SSH password authentication rejected' : 'Unexpected SSH auth reply');
    }
  }

  async openSessionChannel() {
    const localChannel = 0;
    await this.sendPacket(
      new SshWriter()
        .u8(SSH_MSG_CHANNEL_OPEN)
        .str('session')
        .u32(localChannel)
        .u32(2 * 1024 * 1024)
        .u32(32768)
        .finish()
    );
    while (true) {
      const pkt = await this.readPacket();
      if (pkt[0] === SSH_MSG_GLOBAL_REQUEST) continue;
      if (pkt[0] === SSH_MSG_CHANNEL_OPEN_FAILURE) throw new Error('SSH server refused session channel');
      if (pkt[0] === SSH_MSG_CHANNEL_OPEN_CONFIRMATION) {
        const r = new SshReader(pkt.subarray(1));
        r.u32(); // recipient
        const remoteChannel = r.u32();
        return { localChannel, remoteChannel };
      }
    }
  }
}

/**
 * Interactive SSHv2 Terminal Session supporting both Standalone Direct Sockets AND Crostini Relay (`/v1/ssh`).
 */
export class NexusSshInteractiveSession {
  constructor({
    host,
    port = 22,
    username = 'root',
    password = '',
    privateKeyPem = '',
    keyPassphrase = '',
    cols = 110,
    rows = 32,
    startupCommand = '',
    bastion = null,
    onData,
    onStatus,
    onClose,
    transportOptions = {}
  }) {
    this.host = host;
    this.port = port || 22;
    this.username = username || 'root';
    this.password = password || '';
    this.privateKeyPem = privateKeyPem || '';
    this.keyPassphrase = keyPassphrase || '';
    this.cols = cols;
    this.rows = rows;
    this.startupCommand = startupCommand || '';
    this.bastion = bastion;
    this.onData = onData;
    this.onStatus = onStatus;
    this.onClose = onClose;
    this.transportOptions = transportOptions;
    this._ws = null;
    this._directConn = null;
    this._remoteChannel = 0;
    this._closed = false;
  }

  async connect() {
    if ((hasDirectSocketsApi() || this.transportOptions.streamFactory) && !this.bastion && !this.transportOptions.forceRelay) {
      this.onStatus?.(`Connecting SSH-2.0 via Direct Sockets to ${this.host}:${this.port}…`);
      const stream = await openRawTcpStream(this.host, this.port, this.transportOptions);
      const ssh = new DirectSsh2Connection(stream);
      await ssh.performHandshakeAndAuth({
        username: this.username,
        password: this.password,
        privateKeyPem: this.privateKeyPem
      });
      const { remoteChannel } = await ssh.openSessionChannel();
      this._directConn = ssh;
      this._remoteChannel = remoteChannel;

      // Request PTY + interactive shell
      await ssh.sendPacket(
        new SshWriter()
          .u8(SSH_MSG_CHANNEL_REQUEST)
          .u32(remoteChannel)
          .str('pty-req')
          .bool(true)
          .str('xterm-256color')
          .u32(this.cols)
          .u32(this.rows)
          .u32(0)
          .u32(0)
          .bytes(new Uint8Array([0]))
          .finish()
      );
      await ssh.sendPacket(
        new SshWriter()
          .u8(SSH_MSG_CHANNEL_REQUEST)
          .u32(remoteChannel)
          .str('shell')
          .bool(true)
          .finish()
      );
      if (this.startupCommand.trim()) {
        await this.sendInput(new TextEncoder().encode(`${this.startupCommand.trim()}\n`));
      }
      this.onStatus?.(`Connected to ${this.username}@${this.host} (${ssh.serverVersion})`);
      this._pumpDirectChannel();
      return this;
    }

    // Relay WebSocket mode (`/v1/ssh`)
    const wsBase = this.transportOptions.relayWsBase || DEFAULT_LOCAL_RELAY_WS;
    await new Promise((resolve, reject) => {
      const ws = new WebSocket(`${wsBase.replace(/\/$/, '')}/v1/ssh`);
      ws.binaryType = 'arraybuffer';
      this._ws = ws;
      let ready = false;

      ws.onopen = () => {
        ws.send(JSON.stringify({
          host: this.host,
          port: this.port,
          username: this.username,
          password: this.password,
          privateKeyPem: this.privateKeyPem,
          keyPassphrase: this.keyPassphrase,
          cols: this.cols,
          rows: this.rows,
          startupCommand: this.startupCommand,
          bastion: this.bastion
        }));
      };
      ws.onmessage = (ev) => {
        if (ev.data instanceof ArrayBuffer) {
          this.onData?.(new Uint8Array(ev.data));
          return;
        }
        try {
          const msg = JSON.parse(ev.data);
          if (msg.type === 'ready') {
            ready = true;
            this.onStatus?.(msg.banner || 'SSH connected');
            resolve();
          } else if (msg.type === 'error') {
            const err = new Error(msg.message || 'SSH connection error');
            if (!ready) reject(err);
            else this.onClose?.(err);
          }
        } catch {
          this.onData?.(new TextEncoder().encode(ev.data));
        }
      };
      ws.onerror = () => {
        const err = new Error('SSH Relay unreachable. Install the ChromeOS Isolated Web App (.swbn) for standalone Direct Sockets or start nexusts-local-relay.');
        if (!ready) reject(err);
        else this.onClose?.(err);
      };
      ws.onclose = () => {
        if (!this._closed) this.onClose?.();
      };
    });
    return this;
  }

  async _pumpDirectChannel() {
    try {
      while (!this._closed && this._directConn) {
        const pkt = await this._directConn.readPacket();
        const type = pkt[0];
        if (type === SSH_MSG_CHANNEL_DATA) {
          const r = new SshReader(pkt.subarray(1));
          r.u32();
          this.onData?.(r.bytes());
        } else if (type === SSH_MSG_CHANNEL_EXTENDED_DATA) {
          const r = new SshReader(pkt.subarray(1));
          r.u32();
          r.u32();
          this.onData?.(r.bytes());
        } else if (type === SSH_MSG_CHANNEL_WINDOW_ADJUST) {
          // Keep window open
        } else if (type === SSH_MSG_CHANNEL_EOF || type === SSH_MSG_CHANNEL_CLOSE) {
          break;
        }
      }
      if (!this._closed) this.onClose?.();
    } catch (err) {
      if (!this._closed) this.onClose?.(err);
    }
  }

  async sendInput(bytes) {
    if (this._closed) return;
    if (this._directConn) {
      await this._directConn.sendPacket(
        new SshWriter()
          .u8(SSH_MSG_CHANNEL_DATA)
          .u32(this._remoteChannel)
          .bytes(bytes)
          .finish()
      );
    } else if (this._ws?.readyState === WebSocket.OPEN) {
      this._ws.send(bytes);
    }
  }

  async sendResize(cols, rows) {
    this.cols = cols;
    this.rows = rows;
    if (this._directConn) {
      await this._directConn.sendPacket(
        new SshWriter()
          .u8(SSH_MSG_CHANNEL_REQUEST)
          .u32(this._remoteChannel)
          .str('window-change')
          .bool(false)
          .u32(cols)
          .u32(rows)
          .u32(0)
          .u32(0)
          .finish()
      );
    } else if (this._ws?.readyState === WebSocket.OPEN) {
      this._ws.send(JSON.stringify({ type: 'resize', cols, rows }));
    }
  }

  async close() {
    this._closed = true;
    try { await this._directConn?.stream?.close(); } catch {}
    try { this._ws?.close(); } catch {}
  }
}

/**
 * SFTP v3 Dual-Pane Browser Client supporting both Standalone Direct Sockets AND Crostini Relay (`/v1/sftp`).
 */
export class NexusSftpBrowserClient {
  constructor({
    host,
    port = 22,
    username = 'root',
    password = '',
    privateKeyPem = '',
    keyPassphrase = '',
    transportOptions = {}
  }) {
    this.host = host;
    this.port = port || 22;
    this.username = username || 'root';
    this.password = password || '';
    this.privateKeyPem = privateKeyPem || '';
    this.keyPassphrase = keyPassphrase || '';
    this.transportOptions = transportOptions;
  }

  async _relayRpc(action, extra = {}) {
    const httpBase = this.transportOptions.relayHttpBase || DEFAULT_LOCAL_RELAY_HTTP;
    const res = await fetch(`${httpBase}/v1/sftp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action,
        host: this.host,
        port: this.port,
        username: this.username,
        password: this.password,
        privateKeyPem: this.privateKeyPem,
        keyPassphrase: this.keyPassphrase,
        ...extra
      })
    });
    const data = await res.json();
    if (!res.ok || !data?.ok) throw new Error(data?.error || `SFTP ${action} failed`);
    return data;
  }

  async _withDirectSftp(fn) {
    const stream = await openRawTcpStream(this.host, this.port, this.transportOptions);
    const ssh = new DirectSsh2Connection(stream);
    try {
      await ssh.performHandshakeAndAuth({
        username: this.username,
        password: this.password,
        privateKeyPem: this.privateKeyPem
      });
      const { remoteChannel } = await ssh.openSessionChannel();
      await ssh.sendPacket(
        new SshWriter()
          .u8(SSH_MSG_CHANNEL_REQUEST)
          .u32(remoteChannel)
          .str('subsystem')
          .bool(true)
          .str('sftp')
          .finish()
      );

      let sftpBuf = new Uint8Array(0);
      let reqSeq = 1;

      const sendFxp = async (fxpType, bodyBytes) => {
        const frame = new Uint8Array(5 + bodyBytes.length);
        new DataView(frame.buffer).setUint32(0, 1 + bodyBytes.length, false);
        frame[4] = fxpType;
        frame.set(bodyBytes, 5);
        await ssh.sendPacket(
          new SshWriter().u8(SSH_MSG_CHANNEL_DATA).u32(remoteChannel).bytes(frame).finish()
        );
      };

      const readFxp = async () => {
        while (true) {
          if (sftpBuf.length >= 4) {
            const fxpLen = new DataView(sftpBuf.buffer, sftpBuf.byteOffset, 4).getUint32(0, false);
            if (sftpBuf.length >= 4 + fxpLen) {
              const fxpType = sftpBuf[4];
              const body = sftpBuf.slice(5, 4 + fxpLen);
              sftpBuf = sftpBuf.slice(4 + fxpLen);
              return { fxpType, body };
            }
          }
          const pkt = await ssh.readPacket();
          if (pkt[0] === SSH_MSG_CHANNEL_DATA) {
            const r = new SshReader(pkt.subarray(1));
            r.u32();
            const chunk = r.bytes();
            const merged = new Uint8Array(sftpBuf.length + chunk.length);
            merged.set(sftpBuf, 0);
            merged.set(chunk, sftpBuf.length);
            sftpBuf = merged;
          } else if (pkt[0] === SSH_MSG_CHANNEL_EOF || pkt[0] === SSH_MSG_CHANNEL_CLOSE) {
            throw new Error('SFTP channel closed unexpectedly');
          }
        }
      };

      // SSH_FXP_INIT (version 3)
      await sendFxp(SSH_FXP_INIT, new SshWriter().u32(3).finish());
      const ver = await readFxp();
      if (ver.fxpType !== SSH_FXP_VERSION) throw new Error('Expected SSH_FXP_VERSION');

      return await fn({ sendFxp, readFxp, nextId: () => reqSeq++ });
    } finally {
      await stream.close();
    }
  }

  async listDirectory(dirPath = '.') {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      return await this._relayRpc('list', { path: dirPath });
    }
    return await this._withDirectSftp(async ({ sendFxp, readFxp, nextId }) => {
      await sendFxp(SSH_FXP_REALPATH, new SshWriter().u32(nextId()).str(dirPath || '.').finish());
      const realRep = await readFxp();
      let canonical = dirPath || '/';
      if (realRep.fxpType === SSH_FXP_NAME) {
        const r = new SshReader(realRep.body);
        r.u32();
        const cnt = r.u32();
        if (cnt > 0) canonical = r.str();
      }

      await sendFxp(SSH_FXP_OPENDIR, new SshWriter().u32(nextId()).str(canonical).finish());
      const hRep = await readFxp();
      if (hRep.fxpType !== SSH_FXP_HANDLE) throw new Error('SFTP OPENDIR rejected');
      const hr = new SshReader(hRep.body);
      hr.u32();
      const handle = hr.bytes();

      const entries = [];
      while (true) {
        await sendFxp(SSH_FXP_READDIR, new SshWriter().u32(nextId()).bytes(handle).finish());
        const dRep = await readFxp();
        if (dRep.fxpType === SSH_FXP_STATUS) break;
        if (dRep.fxpType === SSH_FXP_NAME) {
          const r = new SshReader(dRep.body);
          r.u32();
          const count = r.u32();
          for (let i = 0; i < count; i++) {
            const name = r.str();
            const _longname = r.str();
            const flags = r.u32();
            let size = 0;
            let mode = 0o644;
            let mtime = 0;
            if (flags & 0x01) size = r.u64();
            if (flags & 0x02) { r.u32(); r.u32(); }
            if (flags & 0x04) mode = r.u32();
            if (flags & 0x08) { r.u32(); mtime = r.u32(); }
            if (flags & 0x80000000) {
              const extCount = r.u32();
              for (let e = 0; e < extCount; e++) { r.bytes(); r.bytes(); }
            }
            if (name === '.' || name === '..') continue;
            const isDir = (mode & 0o170000) === 0o040000;
            entries.push({
              name,
              full_path: canonical === '/' ? `/${name}` : `${canonical.replace(/\/$/, '')}/${name}`,
              is_directory: isDir,
              size_bytes: size,
              unix_mode: mode & 0o7777,
              modified_epoch_sec: mtime
            });
          }
        }
      }
      await sendFxp(SSH_FXP_CLOSE, new SshWriter().u32(nextId()).bytes(handle).finish());
      await readFxp();
      return { ok: true, path: canonical, entries };
    });
  }

  async downloadFile(remotePath) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      const res = await this._relayRpc('download', { path: remotePath });
      return base64ToBytes(res.contentBase64);
    }
    return await this._withDirectSftp(async ({ sendFxp, readFxp, nextId }) => {
      // SSH_FXF_READ = 0x01
      await sendFxp(SSH_FXP_OPEN, new SshWriter().u32(nextId()).str(remotePath).u32(0x01).u32(0).finish());
      const hRep = await readFxp();
      if (hRep.fxpType !== SSH_FXP_HANDLE) throw new Error('SFTP OPEN for read failed');
      const hr = new SshReader(hRep.body);
      hr.u32();
      const handle = hr.bytes();

      const chunks = [];
      let offset = 0;
      while (true) {
        await sendFxp(SSH_FXP_READ, new SshWriter().u32(nextId()).bytes(handle).u64(offset).u32(32768).finish());
        const rep = await readFxp();
        if (rep.fxpType === SSH_FXP_DATA) {
          const dr = new SshReader(rep.body);
          dr.u32();
          const data = dr.bytes();
          chunks.push(data);
          offset += data.length;
        } else {
          break;
        }
      }
      await sendFxp(SSH_FXP_CLOSE, new SshWriter().u32(nextId()).bytes(handle).finish());
      await readFxp();
      const out = new Uint8Array(chunks.reduce((a, c) => a + c.length, 0));
      let p = 0;
      for (const c of chunks) { out.set(c, p); p += c.length; }
      return out;
    });
  }

  async uploadFile(remotePath, bytes) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      return await this._relayRpc('upload', { path: remotePath, contentBase64: bytesToBase64(bytes) });
    }
    return await this._withDirectSftp(async ({ sendFxp, readFxp, nextId }) => {
      // SSH_FXF_WRITE | SSH_FXF_CREAT | SSH_FXF_TRUNC = 0x02 | 0x08 | 0x10 = 0x1a
      await sendFxp(SSH_FXP_OPEN, new SshWriter().u32(nextId()).str(remotePath).u32(0x1a).u32(0).finish());
      const hRep = await readFxp();
      if (hRep.fxpType !== SSH_FXP_HANDLE) throw new Error('SFTP OPEN for write failed');
      const hr = new SshReader(hRep.body);
      hr.u32();
      const handle = hr.bytes();

      let offset = 0;
      while (offset < bytes.length) {
        const slice = bytes.subarray(offset, Math.min(bytes.length, offset + 24576));
        await sendFxp(SSH_FXP_WRITE, new SshWriter().u32(nextId()).bytes(handle).u64(offset).bytes(slice).finish());
        const st = await readFxp();
        if (st.fxpType !== SSH_FXP_STATUS) throw new Error('SFTP WRITE error');
        offset += slice.length;
      }
      await sendFxp(SSH_FXP_CLOSE, new SshWriter().u32(nextId()).bytes(handle).finish());
      await readFxp();
      return { ok: true, bytesWritten: bytes.length };
    });
  }

  async mkdir(remotePath) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      return await this._relayRpc('mkdir', { path: remotePath });
    }
    return await this._withDirectSftp(async ({ sendFxp, readFxp, nextId }) => {
      await sendFxp(SSH_FXP_MKDIR, new SshWriter().u32(nextId()).str(remotePath).u32(0).finish());
      await readFxp();
      return { ok: true };
    });
  }

  async deleteEntry(remotePath, isDir = false) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      return await this._relayRpc('delete', { path: remotePath, isDir });
    }
    return await this._withDirectSftp(async ({ sendFxp, readFxp, nextId }) => {
      await sendFxp(isDir ? SSH_FXP_RMDIR : SSH_FXP_REMOVE, new SshWriter().u32(nextId()).str(remotePath).finish());
      await readFxp();
      return { ok: true };
    });
  }

  async renameEntry(oldPath, newPath) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      return await this._relayRpc('rename', { path: oldPath, newPath });
    }
    return await this._withDirectSftp(async ({ sendFxp, readFxp, nextId }) => {
      await sendFxp(SSH_FXP_RENAME, new SshWriter().u32(nextId()).str(oldPath).str(newPath).finish());
      await readFxp();
      return { ok: true };
    });
  }
}
