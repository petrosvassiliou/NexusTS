// FTP / FTPS Dual-Pane Client, RFC 854/1073 Telnet (`IAC NAWS`), ChromeOS Web Serial (`navigator.serial`),
// IEEE 802 Wake-on-LAN (`UDPSocket` / Relay), TCP Ping & Concurrent Port Scanner.

import { DEFAULT_LOCAL_RELAY_HTTP, hasDirectSocketsApi, openRawTcpStream, sendRawUdpDatagram } from './socket-transport.js';

function parseSixHexBytes(raw) {
  const clean = String(raw || '').trim().replace(/[:.\-\s]/g, '');
  if (!/^[0-9a-fA-F]{12}$/.test(clean)) return null;
  const out = new Uint8Array(6);
  for (let i = 0; i < 6; i++) {
    out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

/**
 * Builds an IEEE 802 Wake-on-LAN Magic Packet (`0xFF * 6` + `MAC * 16` + optional 6-byte SecureOn).
 */
export function buildWolMagicPacket(macStr, secureOnHex = '') {
  const mac = parseSixHexBytes(macStr);
  if (!mac) throw new Error(`Invalid MAC address '${macStr}' (expected AA:BB:CC:DD:EE:FF)`);
  const sec = secureOnHex?.trim() ? parseSixHexBytes(secureOnHex) : null;
  if (secureOnHex?.trim() && !sec) throw new Error('Invalid 6-byte SecureOn hex password');

  const out = new Uint8Array(6 + 16 * 6 + (sec ? 6 : 0));
  out.fill(0xff, 0, 6);
  for (let i = 0; i < 16; i++) {
    out.set(mac, 6 + i * 6);
  }
  if (sec) out.set(sec, 102);
  return out;
}

export async function sendWakeOnLanPacket(macStr, broadcastAddress = '255.255.255.255', udpPort = 9, secureOnHex = '', options = {}) {
  const packet = buildWolMagicPacket(macStr, secureOnHex);
  const bcast = broadcastAddress?.trim() && !parseSixHexBytes(broadcastAddress) ? broadcastAddress.trim() : '255.255.255.255';
  return await sendRawUdpDatagram(bcast, udpPort || 9, packet, {
    ...options,
    mac: macStr,
    secureOnHex
  });
}

export async function runTcpPingProbe(host, port = 22, timeoutMs = 1500, options = {}) {
  const start = performance.now();
  if (!hasDirectSocketsApi() && !options.streamFactory) {
    const httpBase = options.relayHttpBase || DEFAULT_LOCAL_RELAY_HTTP;
    const res = await fetch(`${httpBase}/v1/net/ping`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ host, port, timeoutMs })
    });
    const data = await res.json();
    if (!data?.ok || !data?.hit) return { reachable: false, latencyMs: null };
    return {
      reachable: true,
      latencyMs: data.hit.connect_latency_ms,
      banner: data.hit.service_banner || null
    };
  }

  let stream = null;
  try {
    stream = await Promise.race([
      openRawTcpStream(host, port, options),
      new Promise((_, rej) => setTimeout(() => rej(new Error('Timeout')), timeoutMs))
    ]);
    const latencyMs = Math.max(1, Math.round(performance.now() - start));
    return { reachable: true, latencyMs, banner: null };
  } catch {
    return { reachable: false, latencyMs: null };
  } finally {
    await stream?.close();
  }
}

const WELL_KNOWN_PORTS = {
  21: 'ftp', 22: 'ssh', 23: 'telnet', 25: 'smtp', 53: 'dns', 80: 'http',
  110: 'pop3', 139: 'netbios', 143: 'imap', 443: 'https', 445: 'smb',
  993: 'imaps', 1433: 'mssql', 2179: 'hyperv-vmconnect', 3306: 'mysql',
  3389: 'rdp', 5432: 'postgresql', 5900: 'vnc', 5985: 'winrm', 6379: 'redis',
  6443: 'kubernetes', 8006: 'proxmox', 8080: 'http-alt', 8443: 'https-alt'
};

export async function runConcurrentPortScan(host, startPort = 1, endPort = 1024, concurrency = 32, timeoutMs = 350, onHit = null, options = {}) {
  if (!hasDirectSocketsApi() && !options.streamFactory) {
    const httpBase = options.relayHttpBase || DEFAULT_LOCAL_RELAY_HTTP;
    const res = await fetch(`${httpBase}/v1/net/portscan`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ host, startPort, endPort, timeoutMs })
    });
    const data = await res.json();
    if (!data?.ok) throw new Error(data?.error || 'Port scan failed');
    for (const h of data.hits || []) onHit?.(h);
    return data.hits || [];
  }

  const hits = [];
  const ports = [];
  for (let p = Math.max(1, startPort); p <= Math.min(65535, endPort); p++) ports.push(p);
  let cursor = 0;

  const worker = async () => {
    while (cursor < ports.length) {
      const p = ports[cursor++];
      const probe = await runTcpPingProbe(host, p, timeoutMs, options);
      if (probe.reachable) {
        const hit = {
          port: p,
          open: true,
          connect_latency_ms: probe.latencyMs,
          guessed_service: WELL_KNOWN_PORTS[p] || 'tcp',
          service_banner: probe.banner
        };
        hits.push(hit);
        onHit?.(hit);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, ports.length || 1) }, () => worker()));
  return hits.sort((a, b) => a.port - b.port);
}

/**
 * RFC 854 + RFC 1073 (`IAC NAWS`) Telnet Session Client over `openRawTcpStream`.
 */
const IAC = 255;
const DONT = 254;
const DO = 253;
const WONT = 252;
const WILL = 251;
const SB = 250;
const SE = 240;
const OPT_ECHO = 1;
const OPT_SGA = 3;
const OPT_NAWS = 31;

export class NexusTelnetSession {
  constructor({ host, port = 23, cols = 110, rows = 32, onData, onClose, transportOptions = {} }) {
    this.host = host;
    this.port = port;
    this.cols = cols;
    this.rows = rows;
    this.onData = onData;
    this.onClose = onClose;
    this.transportOptions = transportOptions;
    this.stream = null;
    this._nawsEnabled = false;
  }

  async connect() {
    this.stream = await openRawTcpStream(this.host, this.port, this.transportOptions);
    this._pumpInbound();
    return this;
  }

  async _pumpInbound() {
    try {
      while (true) {
        const chunk = await this.stream.readSome();
        const appBytes = [];
        const replies = [];
        for (let i = 0; i < chunk.length; i++) {
          const b = chunk[i];
          if (b === IAC && i + 1 < chunk.length) {
            const cmd = chunk[i + 1];
            if (cmd === IAC) {
              appBytes.push(IAC);
              i += 1;
            } else if ((cmd === DO || cmd === DONT || cmd === WILL || cmd === WONT) && i + 2 < chunk.length) {
              const opt = chunk[i + 2];
              if (cmd === DO) {
                if (opt === OPT_NAWS) {
                  this._nawsEnabled = true;
                  replies.push(IAC, WILL, OPT_NAWS);
                } else if (opt === OPT_SGA) {
                  replies.push(IAC, WILL, OPT_SGA);
                } else {
                  replies.push(IAC, WONT, opt);
                }
              } else if (cmd === WILL) {
                if (opt === OPT_ECHO || opt === OPT_SGA) {
                  replies.push(IAC, DO, opt);
                } else {
                  replies.push(IAC, DONT, opt);
                }
              }
              i += 2;
            } else if (cmd === SB) {
              while (i < chunk.length && !(chunk[i] === IAC && chunk[i + 1] === SE)) i++;
              i += 1;
            } else {
              i += 1;
            }
          } else {
            appBytes.push(b);
          }
        }
        if (replies.length > 0) {
          await this.stream.write(new Uint8Array(replies));
          if (this._nawsEnabled) await this.sendResize(this.cols, this.rows);
        }
        if (appBytes.length > 0) this.onData?.(new Uint8Array(appBytes));
      }
    } catch (err) {
      this.onClose?.(err);
    }
  }

  async sendInput(bytes) {
    if (!this.stream) return;
    // Escape literal 0xFF as IAC IAC per RFC 854
    const out = [];
    for (const b of bytes) {
      out.push(b);
      if (b === IAC) out.push(IAC);
    }
    await this.stream.write(new Uint8Array(out));
  }

  async sendResize(cols, rows) {
    this.cols = cols;
    this.rows = rows;
    if (!this.stream || !this._nawsEnabled) return;
    const pkt = new Uint8Array([
      IAC, SB, OPT_NAWS,
      (cols >>> 8) & 0xff, cols & 0xff,
      (rows >>> 8) & 0xff, rows & 0xff,
      IAC, SE
    ]);
    await this.stream.write(pkt);
  }

  async close() {
    await this.stream?.close();
  }
}

/**
 * Native ChromeOS Web Serial (`navigator.serial`) UART Session for USB-Serial adapters (`/dev/ttyUSB*`).
 */
export class NexusWebSerialSession {
  constructor({ settings = {}, onData, onClose }) {
    this.settings = settings;
    this.onData = onData;
    this.onClose = onClose;
    this.port = null;
    this.reader = null;
    this.writer = null;
  }

  static isSupported() {
    return typeof globalThis.navigator?.serial?.requestPort === 'function';
  }

  async connect() {
    if (!NexusWebSerialSession.isSupported()) {
      throw new Error('Web Serial API is not available in this browser context.');
    }
    this.port = await globalThis.navigator.serial.requestPort();
    await this.port.open({
      baudRate: Number(this.settings.baudRate || 115200),
      dataBits: Number(this.settings.dataBits || 8),
      stopBits: Number(this.settings.stopBits || 1),
      parity: String(this.settings.parity || 'none').toLowerCase(),
      flowControl: String(this.settings.flowControl || 'none').toLowerCase() === 'hardware' ? 'hardware' : 'none'
    });
    if (this.port.setSignals) {
      await this.port.setSignals({
        dataTerminalReady: this.settings.dtrEnabled !== false,
        requestToSend: this.settings.rtsEnabled !== false
      });
    }
    this.reader = this.port.readable.getReader();
    this.writer = this.port.writable.getWriter();
    (async () => {
      try {
        while (true) {
          const { value, done } = await this.reader.read();
          if (done) break;
          if (value) this.onData?.(value);
        }
        this.onClose?.();
      } catch (err) {
        this.onClose?.(err);
      }
    })();
    return this;
  }

  async sendInput(bytes) {
    if (!this.writer) return;
    await this.writer.write(bytes);
  }

  async close() {
    try { await this.reader?.cancel(); } catch {}
    try { await this.writer?.close(); } catch {}
    try { await this.port?.close(); } catch {}
  }
}

/**
 * Dual-Mode FTP Client:
 * - Standalone Direct Sockets (`openRawTcpStream` control + passive `EPSV`/`PASV` data socket)
 * - Or `nexusts-local-relay` `/v1/ftp` RPC fallback
 */
export class NexusFtpBrowserClient {
  constructor({ host, port = 21, username = 'anonymous', password = '', transportOptions = {} }) {
    this.host = host;
    this.port = port || 21;
    this.username = username || 'anonymous';
    this.password = password || '';
    this.transportOptions = transportOptions;
  }

  async _directSession(fn) {
    const ctrl = await openRawTcpStream(this.host, this.port, this.transportOptions);
    const readReply = async () => {
      const first = (await ctrl.readLine()).replace(/\r?\n$/, '');
      const code = Number(first.slice(0, 3));
      let text = first;
      if (first[3] === '-') {
        const endPrefix = `${first.slice(0, 3)} `;
        while (true) {
          const next = (await ctrl.readLine()).replace(/\r?\n$/, '');
          text += `\n${next}`;
          if (next.startsWith(endPrefix)) break;
        }
      }
      return { code, text };
    };
    const cmd = async (line) => {
      await ctrl.write(`${line.replace(/[\r\n]/g, '')}\r\n`);
      return await readReply();
    };
    const openPassive = async () => {
      const epsv = await cmd('EPSV');
      if (epsv.code === 229) {
        const m = epsv.text.match(/\(\|\|\|(\d+)\|\)/);
        if (m) return await openRawTcpStream(this.host, Number(m[1]), this.transportOptions);
      }
      const pasv = await cmd('PASV');
      if (pasv.code !== 227) throw new Error(`FTP PASV failed: ${pasv.text}`);
      const m = pasv.text.match(/\((\d+),(\d+),(\d+),(\d+),(\d+),(\d+)\)/);
      if (!m) throw new Error(`Cannot parse PASV reply: ${pasv.text}`);
      const dataPort = Number(m[5]) * 256 + Number(m[6]);
      return await openRawTcpStream(this.host, dataPort, this.transportOptions);
    };

    try {
      const greet = await readReply();
      if (greet.code !== 220) throw new Error(`Unexpected FTP greeting: ${greet.text}`);
      const u = await cmd(`USER ${this.username}`);
      if (u.code === 331 || u.code === 332) {
        const p = await cmd(`PASS ${this.password}`);
        if (p.code !== 230 && p.code !== 202) throw new Error(`FTP auth failed: ${p.text}`);
      } else if (u.code !== 230) {
        throw new Error(`FTP USER rejected: ${u.text}`);
      }
      await cmd('OPTS UTF8 ON');
      await cmd('TYPE I');
      return await fn({ ctrl, cmd, readReply, openPassive });
    } finally {
      try { await ctrl.write('QUIT\r\n'); } catch {}
      await ctrl.close();
    }
  }

  async _relayRpc(action, extra = {}) {
    const httpBase = this.transportOptions.relayHttpBase || DEFAULT_LOCAL_RELAY_HTTP;
    const res = await fetch(`${httpBase}/v1/ftp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        action,
        host: this.host,
        port: this.port,
        username: this.username,
        password: this.password,
        ...extra
      })
    });
    const data = await res.json();
    if (!res.ok || !data?.ok) throw new Error(data?.error || `FTP ${action} failed`);
    return data;
  }

  async listDirectory(dirPath = '/') {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      return await this._relayRpc('list', { path: dirPath });
    }
    return await this._directSession(async ({ cmd, readReply, openPassive }) => {
      if (dirPath && dirPath !== '.') await cmd(`CWD ${dirPath}`);
      const pwdRep = await cmd('PWD');
      const mPwd = pwdRep.text.match(/"([^"]+)"/);
      const currentPath = mPwd ? mPwd[1] : dirPath || '/';

      const dataSock = await openPassive();
      const listRep = await cmd('MLSD');
      let useMlsd = true;
      let dataStream = dataSock;
      if (listRep.code !== 125 && listRep.code !== 150) {
        await dataSock.close();
        useMlsd = false;
        dataStream = await openPassive();
        const l2 = await cmd('LIST');
        if (l2.code !== 125 && l2.code !== 150) {
          await dataStream.close();
          throw new Error(`FTP LIST rejected: ${l2.text}`);
        }
      }
      const chunks = [];
      try {
        while (true) chunks.push(await dataStream.readSome());
      } catch {}
      await dataStream.close();
      await readReply();

      const total = chunks.reduce((a, c) => a + c.length, 0);
      const raw = new Uint8Array(total);
      let pos = 0;
      for (const c of chunks) { raw.set(c, pos); pos += c.length; }
      const listing = new TextDecoder().decode(raw);
      const entries = [];
      for (const line of listing.split(/\r?\n/).filter((l) => l.trim())) {
        if (useMlsd) {
          const semiIdx = line.indexOf(' ');
          const factsStr = semiIdx > 0 ? line.slice(0, semiIdx) : '';
          const name = (semiIdx > 0 ? line.slice(semiIdx + 1) : line).trim();
          if (!name || name === '.' || name === '..') continue;
          const isDir = /type=(?:dir|cdir|pdir)/i.test(factsStr);
          const sizeM = factsStr.match(/size=(\d+)/i);
          entries.push({
            name,
            full_path: currentPath === '/' ? `/${name}` : `${currentPath}/${name}`,
            is_directory: isDir,
            size_bytes: sizeM ? Number(sizeM[1]) : 0,
            unix_mode: isDir ? 0o755 : 0o644
          });
        } else {
          const parts = line.trim().split(/\s+/);
          if (parts.length >= 9) {
            const name = parts.slice(8).join(' ');
            if (name === '.' || name === '..') continue;
            const isDir = parts[0].startsWith('d');
            entries.push({
              name,
              full_path: currentPath === '/' ? `/${name}` : `${currentPath}/${name}`,
              is_directory: isDir,
              size_bytes: Number(parts[4]) || 0,
              unix_mode: isDir ? 0o755 : 0o644
            });
          }
        }
      }
      return { ok: true, path: currentPath, entries };
    });
  }

  async downloadFile(remotePath) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      const res = await this._relayRpc('download', { path: remotePath });
      const bin = atob(res.contentBase64);
      const out = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    }
    return await this._directSession(async ({ cmd, readReply, openPassive }) => {
      const dataSock = await openPassive();
      const rep = await cmd(`RETR ${remotePath}`);
      if (rep.code !== 125 && rep.code !== 150) {
        await dataSock.close();
        throw new Error(`FTP RETR failed: ${rep.text}`);
      }
      const chunks = [];
      try {
        while (true) chunks.push(await dataSock.readSome());
      } catch {}
      await dataSock.close();
      await readReply();
      const out = new Uint8Array(chunks.reduce((a, c) => a + c.length, 0));
      let p = 0;
      for (const c of chunks) { out.set(c, p); p += c.length; }
      return out;
    });
  }

  async uploadFile(remotePath, bytes) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      let bin = '';
      for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
      return await this._relayRpc('upload', { path: remotePath, contentBase64: btoa(bin) });
    }
    return await this._directSession(async ({ cmd, readReply, openPassive }) => {
      const dataSock = await openPassive();
      const rep = await cmd(`STOR ${remotePath}`);
      if (rep.code !== 125 && rep.code !== 150) {
        await dataSock.close();
        throw new Error(`FTP STOR failed: ${rep.text}`);
      }
      await dataSock.write(bytes);
      await dataSock.close();
      await readReply();
      return { ok: true, bytesWritten: bytes.length };
    });
  }

  async mkdir(remotePath) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      return await this._relayRpc('mkdir', { path: remotePath });
    }
    return await this._directSession(async ({ cmd }) => {
      const rep = await cmd(`MKD ${remotePath}`);
      if (rep.code < 200 || rep.code >= 300) throw new Error(rep.text);
      return { ok: true };
    });
  }

  async deleteEntry(remotePath) {
    if (!hasDirectSocketsApi() && !this.transportOptions.streamFactory) {
      return await this._relayRpc('delete', { path: remotePath });
    }
    return await this._directSession(async ({ cmd }) => {
      const dele = await cmd(`DELE ${remotePath}`);
      if (dele.code >= 200 && dele.code < 300) return { ok: true };
      const rmd = await cmd(`RMD ${remotePath}`);
      if (rmd.code < 200 || rmd.code >= 300) throw new Error(rmd.text);
      return { ok: true };
    });
  }
}
