// ChromeOS Start-Menu Install Manager & Live Service Worker Auto-Updater.
// Modeled 1:1 on `thingsbook-android/chromeos-pwa/src/data/pwaUpdater.ts`:
// 1. Captures `beforeinstallprompt` & `appinstalled` so ChromeOS users can install NexusTS
//    into the ChromeOS App Launcher (Startmenü) & Shelf with 1 click and zero manual flags.
// 2. Registers `./sw.js` (`updateViaCache: 'none'`), checks for new Firebase Hosting deploys
//    on startup / focus / 30-min interval via SW update + `version.json` + GitHub Releases,
//    and applies updates seamlessly in-place (`SKIP_WAITING` -> reload).

let deferredInstallPrompt = null;
let swRegistrationRef = null;
let swUpdateWaiting = false;
let localBuildHash = '';
const stateListeners = new Set();

function notifyListeners() {
  const snapshot = getPwaLifecycleSnapshot();
  for (const fn of stateListeners) {
    try {
      fn(snapshot);
    } catch {
      // Ignore subscriber error
    }
  }
}

export function subscribePwaLifecycle(listener) {
  stateListeners.add(listener);
  return () => stateListeners.delete(listener);
}

export function isRunningStandalone() {
  if (typeof globalThis.window === 'undefined') return false;
  try {
    if (globalThis.location?.protocol === 'isolated-app:') return true;
    if (globalThis.navigator?.standalone === true) return true;
    return Boolean(
      globalThis.matchMedia?.('(display-mode: standalone)')?.matches ||
        globalThis.matchMedia?.('(display-mode: window-controls-overlay)')?.matches ||
        globalThis.matchMedia?.('(display-mode: fullscreen)')?.matches
    );
  } catch {
    return false;
  }
}

export function canInstallPwa() {
  return Boolean(deferredInstallPrompt) && !isRunningStandalone();
}

export function getPwaLifecycleSnapshot() {
  return {
    standalone: isRunningStandalone(),
    installPromptAvailable: canInstallPwa(),
    swRegistered: Boolean(swRegistrationRef),
    swUpdateWaiting,
    localBuildHash
  };
}

export async function promptPwaInstall() {
  if (!deferredInstallPrompt) {
    return { outcome: 'unavailable' };
  }
  const promptEvent = deferredInstallPrompt;
  deferredInstallPrompt = null;
  notifyListeners();
  try {
    await promptEvent.prompt();
    const choice = await promptEvent.userChoice;
    const outcome = choice?.outcome || 'dismissed';
    if (outcome !== 'accepted') {
      // Keep clean state until Chrome re-emits beforeinstallprompt
      deferredInstallPrompt = null;
    }
    notifyListeners();
    return { outcome };
  } catch {
    notifyListeners();
    return { outcome: 'error' };
  }
}

export function compareSemver(a, b) {
  const clean = (v) =>
    String(v || '0.0.0')
      .trim()
      .replace(/^[vV]/, '')
      .split('-')[0]
      .split('.')
      .map((n) => Number.parseInt(n, 10) || 0);
  const pa = clean(a);
  const pb = clean(b);
  const len = Math.max(pa.length, pb.length, 3);
  for (let i = 0; i < len; i++) {
    const da = pa[i] || 0;
    const db = pb[i] || 0;
    if (da > db) return 1;
    if (da < db) return -1;
  }
  const preA = String(a || '').includes('-');
  const preB = String(b || '').includes('-');
  if (!preA && preB) return 1;
  if (preA && !preB) return -1;
  return 0;
}

export function initChromeOSPwaLifecycle({ onAutoReloadSafe } = {}) {
  if (typeof window === 'undefined') return;

  window.addEventListener('beforeinstallprompt', (ev) => {
    ev.preventDefault();
    deferredInstallPrompt = ev;
    notifyListeners();
  });

  window.addEventListener('appinstalled', () => {
    deferredInstallPrompt = null;
    notifyListeners();
  });

  try {
    window.matchMedia?.('(display-mode: standalone)')?.addEventListener?.('change', () => {
      notifyListeners();
    });
  } catch {
    // Ignore older matchMedia engines
  }

  // Probe bundled version.json once to record the initial local buildHash
  fetch('./version.json', { cache: 'no-store' })
    .then((r) => (r.ok ? r.json() : null))
    .then((meta) => {
      if (meta?.buildHash) {
        localBuildHash = String(meta.buildHash);
        notifyListeners();
      }
    })
    .catch(() => {});

  if (!('serviceWorker' in navigator) || location.protocol === 'isolated-app:') {
    return;
  }

  let refreshing = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (refreshing) return;
    // Only auto-reload immediately when the caller confirms no active remote session would be interrupted
    if (typeof onAutoReloadSafe === 'function' && !onAutoReloadSafe()) {
      swUpdateWaiting = true;
      notifyListeners();
      return;
    }
    refreshing = true;
    window.location.reload();
  });

  navigator.serviceWorker
    .register('./sw.js', { updateViaCache: 'none' })
    .then((registration) => {
      swRegistrationRef = registration;
      if (registration.waiting) {
        swUpdateWaiting = true;
      }
      registration.addEventListener('updatefound', () => {
        const installing = registration.installing;
        if (!installing) return;
        installing.addEventListener('statechange', () => {
          if (installing.state === 'installed' && navigator.serviceWorker.controller) {
            swUpdateWaiting = true;
            notifyListeners();
          }
        });
      });
      notifyListeners();
    })
    .catch(() => {
      // Gracefully ignore SW registration errors in restricted preview frames
    });

  // Periodic & focus-triggered update checks (matching Thingsbook ChromeOS behavior)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      swRegistrationRef?.update?.().catch(() => {});
    }
  });
  setInterval(() => {
    swRegistrationRef?.update?.().catch(() => {});
  }, 30 * 60 * 1000);
}

export async function checkForSwAndReleaseUpdates(currentVersion = '0.9.4') {
  let swWaiting = swUpdateWaiting;
  let remoteBuildChanged = false;
  let hostedVersion = currentVersion;

  try {
    if (swRegistrationRef) {
      await swRegistrationRef.update();
      if (swRegistrationRef.waiting) {
        swWaiting = true;
        swUpdateWaiting = true;
        notifyListeners();
      }
    }
  } catch {
    // Offline: ignore
  }

  // 1. Check Firebase Hosting live `version.json` (never cached due to firebase.json headers)
  try {
    const vRes = await fetch(`./version.json?_nxts_probe=${Date.now()}`, {
      cache: 'no-store',
      headers: { 'Cache-Control': 'no-cache' }
    });
    if (vRes.ok) {
      const meta = await vRes.json();
      if (meta?.version) hostedVersion = String(meta.version).replace(/^[vV]/, '');
      if (meta?.buildHash && localBuildHash && String(meta.buildHash) !== localBuildHash) {
        remoteBuildChanged = true;
        swWaiting = true;
        swUpdateWaiting = true;
        notifyListeners();
      }
    }
  } catch {
    // Offline or local dev without version.json
  }

  // 2. Check GitHub Releases API (with raw.githubusercontent.com fallback)
  try {
    const res = await fetch('https://api.github.com/repos/petrosvassiliou/NexusTS/releases/latest', {
      cache: 'no-store',
      headers: { Accept: 'application/vnd.github+json' }
    });
    if (res.ok) {
      const data = await res.json();
      const rawTag = String(data?.tag_name || '').trim();
      const cleanTag = rawTag.replace(/^[vV]/, '') || currentVersion;
      const newer = compareSemver(cleanTag, currentVersion) > 0;
      return {
        hasUpdate: newer || swWaiting || remoteBuildChanged,
        latestVersion: newer ? cleanTag : hostedVersion,
        latestTag: rawTag || `v${hostedVersion}`,
        releaseUrl: typeof data?.html_url === 'string' ? data.html_url : '',
        swWaiting: swWaiting || remoteBuildChanged
      };
    }
  } catch {
    // Fall through to raw manifest check
  }

  try {
    const rawRes = await fetch(
      'https://raw.githubusercontent.com/petrosvassiliou/NexusTS/main/chromeos-pwa/app/manifest.webmanifest',
      { cache: 'no-store' }
    );
    if (rawRes.ok) {
      const manifest = await rawRes.json();
      const cleanVer = String(manifest?.version || currentVersion).replace(/^[vV]/, '');
      const newer = compareSemver(cleanVer, currentVersion) > 0;
      return {
        hasUpdate: newer || swWaiting || remoteBuildChanged,
        latestVersion: newer ? cleanVer : hostedVersion,
        latestTag: `v${newer ? cleanVer : hostedVersion}`,
        releaseUrl: newer ? `https://github.com/petrosvassiliou/NexusTS/releases/tag/v${cleanVer}` : '',
        swWaiting: swWaiting || remoteBuildChanged
      };
    }
  } catch {
    // Offline: return local SW state
  }

  return {
    hasUpdate: swWaiting || remoteBuildChanged,
    latestVersion: hostedVersion,
    latestTag: `v${hostedVersion}`,
    releaseUrl: '',
    swWaiting: swWaiting || remoteBuildChanged
  };
}

export async function applyPendingSwUpdate() {
  if (typeof window === 'undefined') return;
  try {
    if (swRegistrationRef) {
      await swRegistrationRef.update();
      if (swRegistrationRef.waiting) {
        swRegistrationRef.waiting.postMessage({ type: 'SKIP_WAITING' });
        await new Promise((r) => setTimeout(r, 180));
      }
    }
  } catch {
    // Fallback to direct reload
  }
  window.location.reload();
}
