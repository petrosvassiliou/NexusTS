#!/usr/bin/env node
// Official Google `wbn` + `wbn-sign` ChromeOS Isolated Web App (`.swbn`) & Installer Packager (`v1.0.0`):
// 1. Audits strict Isolated Web App (IWA) Content-Security-Policy & 100% offline Service Worker asset precache.
// 2. Builds Web Bundle v2 (`b2`) using Google's official `wbn` `BundleBuilder('b2')` (5-element top-level CBOR structure).
// 3. Signs & cryptographically verifies `NexusTS-v1.0.0-ChromeOS-IWA.swbn` (Integrity Block v2 `2b\0\0`)
//    against BOTH Google's official `wbn` `Bundle` decoder and `wbn-sign` `SignedWebBundle.fromBytes(...).validateSignatures()`.
// 4. Stages `/downloads/NexusTS-v1.0.0-ChromeOS-IWA.swbn` & `/downloads/iwa-update-manifest.json` into Firebase Hosting.

import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Bundle, BundleBuilder } from 'wbn';
import { SignedWebBundle, WebBundleId } from 'wbn-sign';

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

// Canonical Google IWA response headers (matching `@google/rollup-plugin-webbundle` `iwaHeaderDefaults` + `trusted-types default`)
const IWA_RESPONSE_HEADERS = Object.freeze({
  'content-security-policy':
    "base-uri 'none'; default-src 'self'; object-src 'none'; frame-src 'self' https: blob: data:; connect-src 'self' https: wss: ws: http://127.0.0.1:*; script-src 'self' 'wasm-unsafe-eval'; img-src 'self' https: blob: data:; media-src 'self' https: blob: data:; font-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; require-trusted-types-for 'script'; trusted-types default; frame-ancestors 'self';",
  'cross-origin-embedder-policy': 'require-corp',
  'cross-origin-opener-policy': 'same-origin',
  'cross-origin-resource-policy': 'same-origin'
});

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
  const encodedPairs = entries
    .map(([k, v]) => ({ k, v }))
    .sort((a, b) => Buffer.compare(a.k, b.k));
  const flat = [];
  for (const p of encodedPairs) flat.push(p.k, p.v);
  return Buffer.concat([cborUint(5, encodedPairs.length), ...flat]);
}

export function computeIwaWebBundleIdFromEd25519Pub(rawPub32) {
  const spkiPrefix = Buffer.from('302a300506032b6570032100', 'hex');
  const pubKey = crypto.createPublicKey({
    key: Buffer.concat([spkiPrefix, Buffer.from(rawPub32)]),
    format: 'der',
    type: 'spki'
  });
  return new WebBundleId(pubKey).serialize();
}

/**
 * Builds a standards-compliant Web Bundle v2 (`b2` — 5-element top-level CBOR structure)
 * using Google's official `wbn` `BundleBuilder('b2')` and verifies it with `new Bundle(...)`.
 */
export function buildUnsignedWebBundleB2(files, { version = '1.0.0' } = {}) {
  const builder = new BundleBuilder('b2');

  for (const file of files) {
    let body = fs.readFileSync(file.full);
    if (file.rel === '.well-known/manifest.webmanifest' || file.rel === 'manifest.webmanifest') {
      const parsed = JSON.parse(body.toString('utf8'));
      parsed.version = version;
      body = Buffer.from(`${JSON.stringify(parsed, null, 2)}\n`, 'utf8');
    }
    const ext = path.extname(file.rel).toLowerCase();
    const contentType = MIME_BY_EXT[ext] || 'application/octet-stream';
    const headers = {
      ...IWA_RESPONSE_HEADERS,
      'content-type': contentType
    };

    builder.addExchange(`/${file.rel}`, 200, headers, body);
    if (file.rel === 'index.html') {
      builder.addExchange('/', 200, headers, body);
    }
  }

  const wbnUint8 = builder.createBundle();
  const wbnBuf = Buffer.from(wbnUint8.buffer, wbnUint8.byteOffset, wbnUint8.byteLength);

  // Verify immediately with Google's official `wbn` `Bundle` parser!
  const decoded = new Bundle(wbnBuf);
  if (decoded.version !== 'b2') {
    throw new Error(`Expected Web Bundle format b2, got ${decoded.version}`);
  }
  if (!decoded.urls.includes('/') || !decoded.urls.includes('/.well-known/manifest.webmanifest')) {
    throw new Error('Web Bundle missing required / or /.well-known/manifest.webmanifest exchange');
  }
  return wbnBuf;
}

/**
 * Signs an unsigned Web Bundle v2 (`b2`) with Integrity Block v2 (`2b\0\0`) Ed25519 and
 * verifies the output `.swbn` using Google's official `wbn-sign` `SignedWebBundle.fromBytes(...)`!
 */
export function signWebBundleIntegrityBlockV2(unsignedWbnBytes, privateKeyPem = null) {
  let keyPair;
  if (privateKeyPem) {
    const priv = crypto.createPrivateKey(privateKeyPem);
    const pub = crypto.createPublicKey(priv);
    keyPair = { privateKey: priv, publicKey: pub };
  } else {
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
  const webBundleId = new WebBundleId(keyPair.publicKey).serialize();

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

  const swbnBytes = Buffer.concat([signedIntegrityBlock, unsignedWbnBytes]);

  // Verify `.swbn` end-to-end with Google's official `wbn-sign` `SignedWebBundle` & `wbn` `Bundle`!
  const verifiedSwbn = SignedWebBundle.fromBytes(swbnBytes);
  const sigResults = verifiedSwbn.validateSignatures();
  if (!sigResults.length || !sigResults.every((r) => r.status === 'success' && r.isValid && r.derivedBundleId === webBundleId)) {
    throw new Error(`Official wbn-sign validation failed: ${JSON.stringify(sigResults)}`);
  }
  const innerBundle = new Bundle(verifiedSwbn.pureWebBundle);
  const manifestResp = innerBundle.getResponse('/.well-known/manifest.webmanifest');
  if (manifestResp.status !== 200) {
    throw new Error('Invalid /.well-known/manifest.webmanifest status in verified .swbn');
  }

  return {
    swbnBytes,
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

export function buildFirebaseHostingSite({ version = '1.0.0', releaseTag = 'v1.0.0' } = {}) {
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

  // Strip <link rel="manifest"> from hosted https:// landing HTML so ChromeOS users install the .swbn IWA, not a socket-less web tab
  const hostedIndexPath = path.join(HOSTING_DIST_DIR, 'index.html');
  const hostedIndexHtml = fs
    .readFileSync(hostedIndexPath, 'utf8')
    .replace(/\s*<link\s+rel="manifest"\s+href="[^"]+"\s*\/?>/i, '');
  fs.writeFileSync(hostedIndexPath, hostedIndexHtml);

  // Build and stage verified Signed Web Bundle (.swbn) & IWA Update Manifest directly inside hosting-dist/downloads/
  const allAppFiles = walkFiles(APP_DIR);
  const unsignedForHosting = buildUnsignedWebBundleB2(allAppFiles, { version });
  const signingPem = process.env.NEXUSTS_IWA_ED25519_PEM || null;
  const signedForHosting = signWebBundleIntegrityBlockV2(unsignedForHosting, signingPem);
  const downloadsDir = path.join(HOSTING_DIST_DIR, 'downloads');
  fs.mkdirSync(downloadsDir, { recursive: true });
  fs.writeFileSync(path.join(downloadsDir, `NexusTS-${releaseTag}-ChromeOS-IWA.swbn`), signedForHosting.swbnBytes);
  fs.writeFileSync(path.join(downloadsDir, 'NexusTS-ChromeOS-IWA.swbn'), signedForHosting.swbnBytes);
  fs.writeFileSync(
    path.join(downloadsDir, 'iwa-update-manifest.json'),
    `${JSON.stringify(
      {
        versions: [
          {
            version,
            src: `https://nexusts-prod.web.app/downloads/NexusTS-${releaseTag}-ChromeOS-IWA.swbn`
          }
        ]
      },
      null,
      2
    )}\n`
  );

  // Emit live version.json & GitHub Pages SPA 404.html + .nojekyll
  const versionMeta = {
    name: 'NexusTS ChromeOS Isolated Web App Installer',
    version,
    releaseTag,
    buildHash,
    webBundleId: signedForHosting.webBundleId,
    iwaOrigin: signedForHosting.origin,
    swbnUrl: `/downloads/NexusTS-${releaseTag}-ChromeOS-IWA.swbn`,
    updateManifestUrl: 'https://nexusts-prod.web.app/downloads/iwa-update-manifest.json',
    wasmBundled: hasWasm,
    builtAt: new Date().toISOString()
  };
  const versionJsonPath = path.join(HOSTING_DIST_DIR, 'version.json');
  fs.writeFileSync(versionJsonPath, `${JSON.stringify(versionMeta, null, 2)}\n`);
  fs.copyFileSync(hostedIndexPath, path.join(HOSTING_DIST_DIR, '404.html'));
  fs.writeFileSync(path.join(HOSTING_DIST_DIR, '.nojekyll'), '');

  return {
    hostingDistDir: HOSTING_DIST_DIR,
    hostingFileCount: appFiles.length + 5,
    precachedCount: audit.precachedCount,
    buildHash,
    webBundleId: signedForHosting.webBundleId,
    wasmBundled: hasWasm,
    versionJsonPath
  };
}

export function buildAllChromeOSArtifacts({ version = '1.0.0', releaseTag = 'v1.0.0' } = {}) {
  const hosting = buildFirebaseHostingSite({ version, releaseTag });
  fs.rmSync(DIST_DIR, { recursive: true, force: true });
  fs.mkdirSync(DIST_DIR, { recursive: true });

  const files = walkFiles(APP_DIR);
  const unsignedWbn = buildUnsignedWebBundleB2(files, { version });
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
  const tag = process.env.NEXUSTS_RELEASE_TAG || 'v1.0.0';
  const ver = tag.replace(/^v/, '');
  const hostingOnly = process.argv.includes('--hosting-only');
  const info = hostingOnly
    ? buildFirebaseHostingSite({ version: ver, releaseTag: tag })
    : buildAllChromeOSArtifacts({ version: ver, releaseTag: tag });
  console.log(JSON.stringify(info, null, 2));
}
