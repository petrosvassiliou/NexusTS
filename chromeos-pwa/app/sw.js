// NexusTS 100% Offline-First & Auto-Updating Service Worker (`v0.9.3`).
// Precaches every HTML, CSS, ES module, WebAssembly binary, manifest, and icon on install
// so NexusTS boots and operates completely offline from the ChromeOS Startmenü (App Launcher)
// while updating seamlessly whenever a new build is deployed to Firebase Hosting.

const CACHE_NAME = 'nexusts-chromeos-offline-v0.9.3';

const PRECACHE_ASSETS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './.well-known/manifest.webmanifest',
  './styles/tokens.css',
  './styles/workspace.css',
  './icons/favicon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './src/core/argon2id.js',
  './src/core/wasm-loader.js',
  './src/core/nxts-crypto.js',
  './src/core/vault-models.js',
  './src/core/conflict-resolver.js',
  './src/core/importers-exporters.js',
  './src/core/offline-drive-sync.js',
  './src/core/i18n.js',
  './src/core/pwa-updater.js',
  './src/core/workspace-store.js',
  './src/protocols/socket-transport.js',
  './src/protocols/vt-terminal.js',
  './src/protocols/ftp-telnet-serial-netutils.js',
  './src/protocols/vnc-client.js',
  './src/protocols/rdp-client.js',
  './src/protocols/ssh-sftp-client.js',
  './src/ui/app.js'
];

function withIsolationHeaders(response) {
  if (!response || response.status === 0 || response.type === 'opaque') return response;
  const headers = new Headers(response.headers);
  headers.set('Cross-Origin-Opener-Policy', 'same-origin');
  headers.set('Cross-Origin-Embedder-Policy', 'credentialless');
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers
  });
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE_NAME);
      await cache.addAll(
        PRECACHE_ASSETS.map((assetUrl) => new Request(assetUrl, { cache: 'reload' }))
      );
      // Precache Wasm accelerator if built & present
      try {
        const wasmResp = await fetch(new Request('./wasm/nexusts_wasm.wasm', { cache: 'reload' }));
        if (wasmResp.ok) await cache.put('./wasm/nexusts_wasm.wasm', wasmResp);
      } catch {}
      await self.skipWaiting();
    })()
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)));
      await self.clients.claim();
    })()
  );
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // Never intercept local relay, GitHub API checks, or live version.json probes
  if (url.origin !== self.location.origin) return;
  if (url.pathname.endsWith('/version.json')) return;

  event.respondWith(
    (async () => {
      const cache = await caches.open(CACHE_NAME);
      const cached = await cache.match(req, { ignoreSearch: true });
      if (cached) {
        return withIsolationHeaders(cached);
      }
      try {
        const netResp = await fetch(req);
        if (netResp && netResp.ok) {
          await cache.put(req, netResp.clone());
        }
        return withIsolationHeaders(netResp);
      } catch {
        const fallback = await cache.match('./index.html');
        return withIsolationHeaders(fallback);
      }
    })()
  );
});
