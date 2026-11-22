#!/usr/bin/env node
// Deterministic ChromeOS Dual-Packager (`v0.9.7`):
// 1. Audits strict Isolated Web App (IWA) Content-Security-Policy & 100% offline Service Worker asset precache.
// 2. Packages `NexusTS-ChromeOS-Offline-PWA.zip` (Standard Offline PWA).
// 3. Packages `NexusTS-ChromeOS-IWA.swbn` (Signed Web Bundle v2 `b2` + Ed25519 Integrity Block v2 `2b`
//    with RFC 4648 Base32 `isolated-app://<web-bundle-id>` origin) & `update-manifest.json`!

import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(__dirname, '..');
const APP_DIR = path.join(ROOT_DIR, 'app');
const DIST_DIR = path.join(ROOT_DIR, 'dist');
const HOSTING_DIST_DIR = path.join(ROOT_DIR, 'hosting-dist');

const MIME_BY_EXT = {
  '.html': 'text/html;charset=utf-8',
  '.css': 'text/css;charset=utf-8',
  '.js': 'text/javascript;charset=utf-8',
  '.mjs': 'text/javascript;charset=utf-8',
  '.json': 'application/json;charset=utf-8',
  '.webmanifest': 'application/manifest+json;charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.wasm': 'application/wasm'
};

function walkFiles(dir, relPrefix = '') {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = relPrefix ? `${relPrefix}/${entry.name}` : entry.name;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walkFiles(full, rel));
    else if (entry.isFile()) out.push({ rel, full });
  }
  return out.sort((a, b) => a.rel.localeCompare(b.rel));
}

// Minimal Deterministic RFC 8949 CBOR Encoder for Web Bundle v2 (`b2`) & Integrity Block v2 (`2b`)
function cborUint(major, n) {
  const big = BigInt(n);
  const prefix = major << 5;
  if (big < 24n) return Buffer.from([prefix | Number(big)]);
  if (big <= 0xffn) return Buffer.from([prefix | 24, Number(big)]);
  if (big <= 0xffffn) {
    const b = Buffer.alloc(3);
    b[0] = prefix | 25;
    b.writeUInt16BE(Number(big), 1);
    return b;
  }
  if (big <= 0xffffffffn) {
    const b = Buffer.alloc(5);
    b[0] = prefix | 26;
    b.writeUInt32BE(Number(big), 1);
    return b;
  }
  const b = Buffer.alloc(9);
  b[0] = prefix | 27;
  b.writeBigUInt64BE(big, 1);
  return b;
}

function cborBytes(buf) {
  const b = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
  return Buffer.concat([cborUint(2, b.length), b]);
}

function cborText(str) {
  const b = Buffer.from(str, 'utf8');
  return Buffer.concat([cborUint(3, b.length), b]);
}

function cborArray(encodedItems) {
  return Buffer.concat([cborUint(4, encodedItems.length), ...encodedItems]);
}

function cborMap(entries) {
  // RFC 8949 §4.2.1 Deterministic CBOR canonical key ordering: sort by encoded key bytewise lexicographic order!
  const encodedPairs = entries
    .map(([k, v]) => ({ k, v }))
    .sort((a, b) => Buffer.compare(a.k, b.k));
  const flat = [];
  for (const p of encodedPairs) flat.push(p.k, p.v);
  return Buffer.concat([cborUint(5, encodedPairs.length), ...flat]);
}

function base32LowerNoPad(bytes) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz234567';
  let bits = 0;
  let value = 0;
  let out = '';
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += alphabet[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) {
    out += alphabet[(value << (5 - bits)) & 31];
  }
  return out;
}

export function computeIwaWebBundleIdFromEd25519Pub(rawPub32) {
  // ChromeOS IWA Web Bundle ID: 32-byte Ed25519 public key + suffix [0x00, 0x01, 0x02] encoded in lowercase Base32
  const full = Buffer.concat([rawPub32, Buffer.from([0x00, 0x01, 0x02])]);
  return base32LowerNoPad(full);
}

export function buildUnsignedWebBundleB2(files) {
  // Web Bundle v2 (`b2`): [ b"🌐📦", b"b2\0\0", primaryUrl, sectionLengthsBytes, sectionsArray, length8Bytes ]
  const responseItems = [];
  const indexEntries = [];

  for (const file of files) {
    const body = fs.readFileSync(file.full);
    const ext = path.extname(file.rel).toLowerCase();
    const contentType = MIME_BY_EXT[ext] || 'application/octet-stream';
    const headersCbor = cborMap([
      [cborBytes(Buffer.from(':status')), cborBytes(Buffer.from('200'))],
      [cborBytes(Buffer.from('content-type')), cborBytes(Buffer.from(contentType))],
      [cborBytes(Buffer.from('cross-origin-opener-policy')), cborBytes(Buffer.from('same-origin'))],
      [cborBytes(Buffer.from('cross-origin-embedder-policy')), cborBytes(Buffer.from('require-corp'))],
      [cborBytes(Buffer.from('cross-origin-resource-policy')), cborBytes(Buffer.from('same-origin'))]
    ]);
    const respCbor = cborArray([cborBytes(headersCbor), cborBytes(body)]);
    responseItems.push({ relPath: `/${file.rel}`, respCbor });
    if (file.rel === 'index.html') {
      responseItems.push({ relPath: '/', respCbor });
    }
  }

  // Compute exact byte offsets inside the `responses` CBOR array
  const responsesHeader = cborUint(4, responseItems.length);
  let currentOffset = responsesHeader.length;
  const encodedResponses = [];
  for (const item of responseItems) {
    indexEntries.push([
      cborText(item.relPath),
      cborArray([cborUint(0, currentOffset), cborUint(0, item.respCbor.length)])
    ]);
    encodedResponses.push(item.respCbor);
    currentOffset += item.respCbor.length;
  }

  const indexSection = cborMap(indexEntries);
  const responsesSection = Buffer.concat([responsesHeader, ...encodedResponses]);

  const sectionLengthsCbor = cborArray([
    cborText('index'),
    cborUint(0, indexSection.length),
    cborText('responses'),
    cborUint(0, responsesSection.length)
  ]);

  const magic = Buffer.from([0xf0, 0x9f, 0x8c, 0x90, 0xf0, 0x9f, 0x93, 0xa6]); // 🌐📦
  const version = Buffer.from([0x62, 0x32, 0x00, 0x00]); // "b2\0\0"
  const primaryUrl = cborText(''); // IWA relative URLs require empty primaryUrl in b2
  const sections = cborArray([indexSection, responsesSection]);
  const placeholderLen = Buffer.concat([Buffer.from([0x48]), Buffer.alloc(8)]);

  const prefix = Buffer.concat([
    cborUint(4, 6),
    cborBytes(magic),
    cborBytes(version),
    primaryUrl,
    cborBytes(sectionLengthsCbor),
    sections
  ]);
  const totalLen = BigInt(prefix.length + placeholderLen.length);
  const lenField = Buffer.alloc(9);
  lenField[0] = 0x48; // 8-byte bstr
  lenField.writeBigUInt64BE(totalLen, 1);

  return Buffer.concat([prefix, lenField]);
}

export function signWebBundleIntegrityBlockV2(unsignedWbnBytes, privateKeyPem = null) {
  let keyPair;
  if (privateKeyPem) {
    const priv = crypto.createPrivateKey(privateKeyPem);
    const pub = crypto.createPublicKey(priv);
    keyPair = { privateKey: priv, publicKey: pub };
  } else {
    // Deterministic NexusTS IWA development/release Ed25519 signing seed (overridable via NEXUSTS_IWA_ED25519_PEM secret)
    const detSeed = crypto.createHash('sha256').update('nexusts-chromeos-iwa-signing-key-v1').digest();
    const pkcs8Prefix = Buffer.from('302e020100300506032b657004220420', 'hex');
    const priv = crypto.createPrivateKey({
      key: Buffer.concat([pkcs8Prefix, detSeed]),
      format: 'der',
      type: 'pkcs8'
    });
    const pub = crypto.createPublicKey(priv);
    keyPair = { privateKey: priv, publicKey: pub };
  }

  const spkiDer = keyPair.publicKey.export({ format: 'der', type: 'spki' });
  const rawPub32 = spkiDer.subarray(spkiDer.length - 32);
  const webBundleId = computeIwaWebBundleIdFromEd25519Pub(rawPub32);

  const ibMagic = Buffer.from([0xf0, 0x9f, 0x96, 0x8b, 0xf0, 0x9f, 0x93, 0xa6]); // 🖋📦
  const ibVersion = Buffer.from([0x32, 0x62, 0x00, 0x00]); // "2b\0\0"
  const ibAttributes = cborMap([[cborText('webBundleId'), cborText(webBundleId)]]);
  const emptyIntegrityBlock = cborArray([
    cborBytes(ibMagic),
    cborBytes(ibVersion),
    ibAttributes,
    cborArray([])
  ]);

  const sigAttributes = cborMap([[cborText('ed25519PublicKey'), cborBytes(rawPub32)]]);
  const wbnSha512 = crypto.createHash('sha512').update(unsignedWbnBytes).digest();

  const lenBuf = (b) => {
    const out = Buffer.alloc(8);
    out.writeBigUInt64BE(BigInt(b.length), 0);
    return out;
  };
  const dataToSign = Buffer.concat([
    lenBuf(wbnSha512), wbnSha512,
    lenBuf(emptyIntegrityBlock), emptyIntegrityBlock,
    lenBuf(sigAttributes), sigAttributes
  ]);
  const edSig = crypto.sign(null, dataToSign, keyPair.privateKey);

  const signedIntegrityBlock = cborArray([
    cborBytes(ibMagic),
    cborBytes(ibVersion),
    ibAttributes,
    cborArray([cborArray([sigAttributes, cborBytes(edSig)])])
  ]);

  return {
    swbnBytes: Buffer.concat([signedIntegrityBlock, unsignedWbnBytes]),
    webBundleId,
    origin: `isolated-app://${webBundleId}/`
  };
}

export function verifyStrictOfflineAndCsp() {
  const html = fs.readFileSync(path.join(APP_DIR, 'index.html'), 'utf8');
  if (/<script(?![^>]*\bsrc=)/i.test(html)) {
    throw new Error('IWA CSP violation: inline <script> tag detected in index.html');
  }
  if (/\son[a-z]+\s*=/i.test(html)) {
    throw new Error('IWA CSP violation: inline event attribute detected in index.html');
  }
  if (/\b(?:src|href)\s*=\s*["']https?:\/\//i.test(html)) {
    throw new Error('Offline violation: external network asset URL detected in index.html');
  }

  // Verify every precached asset in sw.js exists on disk
  const swText = fs.readFileSync(path.join(APP_DIR, 'sw.js'), 'utf8');
  const match = swText.match(/const PRECACHE_ASSETS\s*=\s*\[([\s\S]*?)\];/);
  if (!match) throw new Error('PRECACHE_ASSETS array missing in sw.js');
  const paths = [...match[1].matchAll(/'([^']+)'/g)].map((m) => m[1]);
  for (const rel of paths) {
    if (rel === './') continue;
    const diskPath = path.join(APP_DIR, rel.replace(/^\.\//, ''));
    if (!fs.existsSync(diskPath)) {
      throw new Error(`Missing offline precache asset referenced in sw.js: ${rel}`);
    }
  }
  return { precachedCount: paths.length };
}

export function buildFirebaseHostingSite({ version = '0.9.7', releaseTag = 'v0.9.7' } = {}) {
  const audit = verifyStrictOfflineAndCsp();
  fs.rmSync(HOSTING_DIST_DIR, { recursive: true, force: true });
  fs.mkdirSync(HOSTING_DIST_DIR, { recursive: true });

  const appFiles = walkFiles(APP_DIR).filter((f) => !f.rel.startsWith('.well-known/'));
  const hasher = crypto.createHash('sha256');

  for (const file of appFiles) {
    const dest = path.join(HOSTING_DIST_DIR, file.rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    const bytes = fs.readFileSync(file.full);
    fs.writeFileSync(dest, bytes);
    hasher.update(file.rel);
    hasher.update(bytes);
  }

  const buildHash = hasher.digest('hex').slice(0, 12);
  const hasWasm = fs.existsSync(path.join(HOSTING_DIST_DIR, 'wasm', 'nexusts_wasm.wasm'));

  // Stamp deterministic content-addressed CACHE_NAME & Firebase-clean PRECACHE_ASSETS into hosting-dist/sw.js
  const swDestPath = path.join(HOSTING_DIST_DIR, 'sw.js');
  let swContent = fs.readFileSync(swDestPath, 'utf8');
  swContent = swContent.replace(
    /const CACHE_NAME\s*=\s*'[^']+';/,
    `const CACHE_NAME = 'nexusts-chromeos-offline-v${version}-${buildHash}';`
  );
  swContent = swContent.replace(/\s*'\.\/\.well-known\/manifest\.webmanifest',?\n/, '\n');
  if (hasWasm && !swContent.includes("'./wasm/nexusts_wasm.wasm',")) {
    swContent = swContent.replace(
      "'./src/ui/app.js'",
      "'./src/ui/app.js',\n  './wasm/nexusts_wasm.wasm'"
    );
  }
  fs.writeFileSync(swDestPath, swContent);

  // Stamp version into hosting-dist/manifest.webmanifest
  const manifestDestPath = path.join(HOSTING_DIST_DIR, 'manifest.webmanifest');
  const manifestObj = JSON.parse(fs.readFileSync(manifestDestPath, 'utf8'));
  manifestObj.version = version;
  fs.writeFileSync(manifestDestPath, `${JSON.stringify(manifestObj, null, 2)}\n`);

  // Emit live version.json & GitHub Pages SPA 404.html + .nojekyll
  const versionMeta = {
    name: 'NexusTS ChromeOS PWA',
    version,
    releaseTag,
    buildHash,
    wasmBundled: hasWasm,
    builtAt: new Date().toISOString()
  };
  const versionJsonPath = path.join(HOSTING_DIST_DIR, 'version.json');
  fs.writeFileSync(versionJsonPath, `${JSON.stringify(versionMeta, null, 2)}\n`);
  fs.copyFileSync(path.join(HOSTING_DIST_DIR, 'index.html'), path.join(HOSTING_DIST_DIR, '404.html'));
  fs.writeFileSync(path.join(HOSTING_DIST_DIR, '.nojekyll'), '');

  return {
    hostingDistDir: HOSTING_DIST_DIR,
    hostingFileCount: appFiles.length + 2,
    precachedCount: audit.precachedCount,
    buildHash,
    wasmBundled: hasWasm,
    versionJsonPath
  };
}

export function buildAllChromeOSArtifacts({ version = '0.9.7', releaseTag = 'v0.9.7' } = {}) {
  const hosting = buildFirebaseHostingSite({ version, releaseTag });
  fs.mkdirSync(DIST_DIR, { recursive: true });

  const files = walkFiles(APP_DIR);
  const unsignedWbn = buildUnsignedWebBundleB2(files);
  const signingPem = process.env.NEXUSTS_IWA_ED25519_PEM || null;
  const { swbnBytes, webBundleId, origin } = signWebBundleIntegrityBlockV2(unsignedWbn, signingPem);

  const swbnPath = path.join(DIST_DIR, `NexusTS-${releaseTag}-ChromeOS-IWA.swbn`);
  fs.writeFileSync(swbnPath, swbnBytes);

  const updateManifest = {
    versions: [
      {
        version,
        src: `https://github.com/petrosvassiliou/NexusTS/releases/download/${releaseTag}/NexusTS-${releaseTag}-ChromeOS-IWA.swbn`
      }
    ]
  };
  const updateManifestPath = path.join(DIST_DIR, `NexusTS-${releaseTag}-ChromeOS-IWA-update-manifest.json`);
  fs.writeFileSync(updateManifestPath, JSON.stringify(updateManifest, null, 2));

  const zipPath = path.join(DIST_DIR, `NexusTS-${releaseTag}-ChromeOS-Offline-PWA.zip`);
  if (fs.existsSync(zipPath)) fs.unlinkSync(zipPath);
  execFileSync('zip', ['-r', '-q', zipPath, '.'], { cwd: APP_DIR });

  return {
    precachedCount: hosting.precachedCount,
    bundledFileCount: files.length,
    hostingDistDir: hosting.hostingDistDir,
    hostingBuildHash: hosting.buildHash,
    wasmBundled: hosting.wasmBundled,
    webBundleId,
    origin,
    swbnPath,
    swbnSizeBytes: swbnBytes.length,
    zipPath,
    zipSizeBytes: fs.statSync(zipPath).size,
    updateManifestPath
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const tag = process.env.NEXUSTS_RELEASE_TAG || 'v0.9.7';
  const ver = tag.replace(/^v/, '');
  const hostingOnly = process.argv.includes('--hosting-only');
  const info = hostingOnly
    ? buildFirebaseHostingSite({ version: ver, releaseTag: tag })
    : buildAllChromeOSArtifacts({ version: ver, releaseTag: tag });
  console.log(JSON.stringify(info, null, 2));
}
