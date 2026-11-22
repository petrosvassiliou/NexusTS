// Offline Wasm Accelerator Loader for `wasm/nexusts_wasm.wasm`
// Transparently accelerates Argon2id + AES-256-GCM `.nxts` sealing/opening, Ed25519 OpenSSH keygen,
// and RFB VNC DES challenge authentication when `nexusts_wasm.wasm` is bundled.

let wasmExports = null;
let loadAttempted = false;

export async function initNexusWasm(customUrl = './wasm/nexusts_wasm.wasm') {
  if (wasmExports) return wasmExports;
  if (loadAttempted) return null;
  loadAttempted = true;
  try {
    if (typeof WebAssembly === 'undefined') return null;
    if (typeof fetch === 'function') {
      try {
        const resp = await fetch(customUrl);
        if (resp && resp.ok) {
          const bytes = await resp.arrayBuffer();
          const { instance } = await WebAssembly.instantiate(bytes, {});
          wasmExports = instance.exports;
          return wasmExports;
        }
      } catch {
        // Fall through to Node.js local file check if running in Node test runner
      }
    }
    if (typeof process !== 'undefined' && process.versions?.node) {
      const [{ readFile }, { fileURLToPath }] = await Promise.all([
        import('node:fs/promises'),
        import('node:url')
      ]);
      const candidates = [
        new URL('../../wasm/nexusts_wasm.wasm', import.meta.url),
        new URL('../../../hosting-dist/wasm/nexusts_wasm.wasm', import.meta.url)
      ];
      for (const candidate of candidates) {
        try {
          const buf = await readFile(fileURLToPath(candidate));
          const { instance } = await WebAssembly.instantiate(buf, {});
          wasmExports = instance.exports;
          return wasmExports;
        } catch {
          // Try next candidate
        }
      }
    }
    return null;
  } catch {
    return null;
  }
}

export function getNexusWasm() {
  return wasmExports;
}

function writeSlice(exports, bytes) {
  const len = bytes.length;
  const ptr = exports.nxts_wasm_alloc(Math.max(len, 1));
  if (len > 0) {
    new Uint8Array(exports.memory.buffer, ptr, len).set(bytes);
  }
  return { ptr, cap: Math.max(len, 1) };
}

function readResultAndClear(exports) {
  const ptr = exports.nxts_wasm_result_ptr();
  const len = exports.nxts_wasm_result_len();
  const copy = new Uint8Array(exports.memory.buffer, ptr, len).slice();
  exports.nxts_wasm_clear_result();
  return copy;
}

export function wasmSealNxts(plainBytes, passBytes, salt32, nonce12, revision, kdf) {
  if (!wasmExports) return null;
  const p = writeSlice(wasmExports, plainBytes);
  const pw = writeSlice(wasmExports, passBytes);
  const s = writeSlice(wasmExports, salt32);
  const n = writeSlice(wasmExports, nonce12);
  try {
    const revBig = BigInt(revision);
    const revHi = Number((revBig >> 32n) & 0xffffffffn) >>> 0;
    const revLo = Number(revBig & 0xffffffffn) >>> 0;
    const rc = wasmExports.nxts_wasm_seal_vault(
      p.ptr, plainBytes.length,
      pw.ptr, passBytes.length,
      s.ptr, n.ptr,
      revHi, revLo,
      kdf.memoryKiB >>> 0,
      kdf.iterations >>> 0,
      kdf.parallelism >>> 0
    );
    if (rc < 0) return null;
    return readResultAndClear(wasmExports);
  } finally {
    wasmExports.nxts_wasm_free(p.ptr, p.cap);
    wasmExports.nxts_wasm_free(pw.ptr, pw.cap);
    wasmExports.nxts_wasm_free(s.ptr, s.cap);
    wasmExports.nxts_wasm_free(n.ptr, n.cap);
  }
}

export function wasmOpenNxts(containerBytes, passBytes) {
  if (!wasmExports) return null;
  const c = writeSlice(wasmExports, containerBytes);
  const pw = writeSlice(wasmExports, passBytes);
  try {
    const rc = wasmExports.nxts_wasm_open_vault(c.ptr, containerBytes.length, pw.ptr, passBytes.length);
    if (rc < 0) throw new Error('VaultAuthenticationException');
    return readResultAndClear(wasmExports);
  } finally {
    wasmExports.nxts_wasm_free(c.ptr, c.cap);
    wasmExports.nxts_wasm_free(pw.ptr, pw.cap);
  }
}

export function wasmVncDesEncrypt(pass8, challenge16) {
  if (!wasmExports || !wasmExports.nxts_wasm_vnc_des_response) return null;
  const p = writeSlice(wasmExports, pass8);
  const c = writeSlice(wasmExports, challenge16);
  try {
    const rc = wasmExports.nxts_wasm_vnc_des_response(p.ptr, c.ptr);
    if (rc < 0) return null;
    return readResultAndClear(wasmExports);
  } finally {
    wasmExports.nxts_wasm_free(p.ptr, p.cap);
    wasmExports.nxts_wasm_free(c.ptr, c.cap);
  }
}
