// Unified Raw Socket Transport Layer for NexusTS ChromeOS:
// 1. Primary (Standalone Offline IWA): ChromeOS `Direct Sockets API` (`window.TCPSocket` / `window.UDPSocket`)
// 2. Fallback (Standard Offline PWA): Local Crostini `nexusts-local-relay` (`ws://127.0.0.1:44890`) or `SecureGatewayConfig` (`wss://`)

export const DEFAULT_LOCAL_RELAY_HTTP = 'http://127.0.0.1:44890';
export const DEFAULT_LOCAL_RELAY_WS = 'ws://127.0.0.1:44890';

export function hasDirectSocketsApi() {
  return typeof globalThis.TCPSocket === 'function';
}

export function isIsolatedWebAppContext() {
  return (
    (typeof globalThis.location !== 'undefined' && globalThis.location.protocol === 'isolated-app:') ||
    hasDirectSocketsApi()
  );
}

export async function probeRelayHealth(httpBase = DEFAULT_LOCAL_RELAY_HTTP, timeoutMs = 1200) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${httpBase.replace(/\/$/, '')}/health`, { signal: ctrl.signal });
    if (!res.ok) return { reachable: false };
    const data = await res.json();
    return { reachable: Boolean(data?.ok), version: data?.version || '', capabilities: data?.capabilities || [] };
  } catch {
    return { reachable: false };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Buffered async byte stream providing `readExact(n)`, `readSome()`, `readLine()`, `write(bytes)`, and `close()`
 * uniformly across ChromeOS `TCPSocket` streams, WebSocket binary streams, and in-memory test duplex pipes.
 */
export class BufferedByteStream {
  constructor({ writeFn, closeFn, transportKind = 'direct-sockets', remoteAddress = '', remotePort = 0 }) {
    this._chunks = [];
    this._bufferedBytes = 0;
    this._waiters = [];
    this._closed = false;
    this._error = null;
    this._writeFn = writeFn;
    this._closeFn = closeFn;
    this.transportKind = transportKind;
    this.remoteAddress = remoteAddress;
    this.remotePort = remotePort;
  }

  pushInbound(chunk) {
    if (this._closed || !chunk || chunk.length === 0) return;
    const bytes = chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk);
    this._chunks.push(bytes);
    this._bufferedBytes += bytes.length;
    this._notifyWaiters();
  }

  markClosed(err = null) {
    this._closed = true;
    this._error = err;
    this._notifyWaiters();
  }

  _notifyWaiters() {
    while (this._waiters.length > 0) {
      const w = this._waiters[0];
      if (this._bufferedBytes >= w.minBytes || this._closed) {
        this._waiters.shift();
        w.resolve();
      } else {
        break;
      }
    }
  }

  async _waitForBytes(minBytes) {
    if (this._bufferedBytes >= minBytes) return;
    if (this._closed) {
      throw this._error || new Error('Socket closed by remote peer');
    }
    await new Promise((resolve) => {
      this._waiters.push({ minBytes, resolve });
    });
    if (this._bufferedBytes < minBytes && this._closed) {
      throw this._error || new Error('Socket closed before reading expected bytes');
    }
  }

  async readSome() {
    await this._waitForBytes(1);
    const first = this._chunks.shift();
    this._bufferedBytes -= first.length;
    return first;
  }

  async readExact(length) {
    if (length === 0) return new Uint8Array(0);
    await this._waitForBytes(length);
    const out = new Uint8Array(length);
    let offset = 0;
    while (offset < length) {
      const head = this._chunks[0];
      const take = Math.min(head.length, length - offset);
      out.set(head.subarray(0, take), offset);
      offset += take;
      this._bufferedBytes -= take;
      if (take === head.length) {
        this._chunks.shift();
      } else {
        this._chunks[0] = head.subarray(take);
      }
    }
    return out;
  }

  async readLine(maxBytes = 16384) {
    const bytes = [];
    while (bytes.length < maxBytes) {
      const b = (await this.readExact(1))[0];
      bytes.push(b);
      if (b === 0x0a) break;
    }
    return new TextDecoder().decode(new Uint8Array(bytes));
  }

  async write(data) {
    if (this._closed) throw this._error || new Error('Cannot write to closed socket');
    const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
    await this._writeFn(bytes);
  }

  async close() {
    if (this._closed) return;
    this._closed = true;
    this._notifyWaiters();
    try {
      await this._closeFn?.();
    } catch {
      // Ignore close error
    }
  }
}

export async function openRawTcpStream(host, port, options = {}) {
  const cleanHost = String(host || '').trim();
  const numPort = Number(port);
  if (!cleanHost || !numPort) throw new Error(`Invalid endpoint: ${cleanHost}:${numPort}`);

  // 1. Custom injected factory (for unit/E2E tests)
  if (typeof options.streamFactory === 'function') {
    return await options.streamFactory(cleanHost, numPort, options);
  }

  // 2. Native ChromeOS Direct Sockets API (`window.TCPSocket`) when running as Isolated Web App (.swbn)
  if (hasDirectSocketsApi() && !options.forceRelay) {
    const sock = new globalThis.TCPSocket(cleanHost, numPort, {
      noDelay: true,
      keepAliveDelay: 30000
    });
    const { readable, writable, remoteAddress, remotePort } = await sock.opened;
    const reader = readable.getReader();
    const writer = writable.getWriter();

    const stream = new BufferedByteStream({
      transportKind: 'direct-sockets',
      remoteAddress: remoteAddress || cleanHost,
      remotePort: remotePort || numPort,
      writeFn: async (bytes) => {
        await writer.write(bytes);
      },
      closeFn: async () => {
        try { await writer.close(); } catch {}
        try { await reader.cancel(); } catch {}
        try { await sock.close(); } catch {}
      }
    });

    (async () => {
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          if (value) stream.pushInbound(value);
        }
        stream.markClosed();
      } catch (err) {
        stream.markClosed(err);
      }
    })();

    return stream;
  }

  // 3. WebSocket TCP Relay (`nexusts-local-relay` or `SecureGatewayConfig` WebSocket endpoint)
  let wsBase = options.relayWsBase || DEFAULT_LOCAL_RELAY_WS;
  if (options.gateway && options.gateway.kind === 'NEXUSTS_SECURE_GATEWAY' && options.gateway.host) {
    const scheme = options.gateway.verifyTlsCertificate === false ? 'ws' : 'wss';
    wsBase = `${scheme}://${options.gateway.host}:${options.gateway.port || 443}`;
  }

  const wsUrl = `${wsBase.replace(/\/$/, '')}/v1/tcp?host=${encodeURIComponent(cleanHost)}&port=${encodeURIComponent(numPort)}`;
  return await new Promise((resolve, reject) => {
    let settled = false;
    const ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';

    const stream = new BufferedByteStream({
      transportKind: 'websocket-relay',
      remoteAddress: cleanHost,
      remotePort: numPort,
      writeFn: async (bytes) => {
        if (ws.readyState !== WebSocket.OPEN) throw new Error('Relay WebSocket not open');
        ws.send(bytes);
      },
      closeFn: async () => {
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
          ws.close();
        }
      }
    });

    ws.onopen = () => {
      settled = true;
      resolve(stream);
    };
    ws.onmessage = (ev) => {
      if (ev.data instanceof ArrayBuffer) {
        stream.pushInbound(new Uint8Array(ev.data));
      } else if (typeof ev.data === 'string') {
        try {
          const parsed = JSON.parse(ev.data);
          if (parsed?.type === 'error') {
            stream.markClosed(new Error(parsed.message || 'Relay connection error'));
            return;
          }
        } catch {}
        stream.pushInbound(new TextEncoder().encode(ev.data));
      }
    };
    ws.onerror = () => {
      const err = new Error(
        `Direct Sockets unavailable in browser mode and local relay (${wsBase}) is not running. Install the ChromeOS Isolated Web App (.swbn) for standalone Direct Sockets or start nexusts-local-relay.`
      );
      if (!settled) {
        settled = true;
        reject(err);
      } else {
        stream.markClosed(err);
      }
    };
    ws.onclose = () => {
      stream.markClosed();
    };
  });
}

export async function sendRawUdpDatagram(host, port, payloadBytes, options = {}) {
  if (typeof globalThis.UDPSocket === 'function' && !options.forceRelay) {
    const udp = new globalThis.UDPSocket({
      remoteAddress: host,
      remotePort: Number(port)
    });
    const { writable } = await udp.opened;
    const writer = writable.getWriter();
    await writer.write({ data: payloadBytes });
    await writer.close();
    await udp.close();
    return { transportKind: 'direct-sockets', bytesSent: payloadBytes.length };
  }

  const httpBase = options.relayHttpBase || DEFAULT_LOCAL_RELAY_HTTP;
  const resp = await fetch(`${httpBase.replace(/\/$/, '')}/v1/net/wol`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mac: options.mac || '',
      broadcastAddress: host,
      port: Number(port) || 9,
      secureOnHex: options.secureOnHex || ''
    })
  });
  const data = await resp.json();
  if (!resp.ok || !data?.ok) {
    throw new Error(data?.error || 'UDP / Wake-on-LAN relay transmission failed');
  }
  return { transportKind: 'websocket-relay', bytesSent: data.bytesSent || payloadBytes.length };
}
