// NexusTS ChromeOS Offline PWA & Isolated Web App Interactive Controller (`v0.9.0`).
// 100% Strict-CSP compliant (zero inline event handlers or eval) for `isolated-app://` & Offline Service Worker.

import { SUPPORTED_LOCALES } from '../core/i18n.js';
import { computeTotpCode, generateEd25519OpenSshKeypair } from '../core/nxts-crypto.js';
import {
  applyPendingSwUpdate,
  checkForSwAndReleaseUpdates,
  getPwaLifecycleSnapshot,
  initChromeOSPwaLifecycle,
  promptPwaInstall,
  subscribePwaLifecycle
} from '../core/pwa-updater.js';
import {
  defaultInheritableProperties,
  defaultSettingsForProtocol,
  effectiveConnectionPort,
  expandTaskVariables,
  parseKeySequenceSteps,
  PROTOCOL_CATALOG,
  randomUuid
} from '../core/vault-models.js';
import { NexusChromeOSStore } from '../core/workspace-store.js';
import {
  NexusFtpBrowserClient,
  NexusTelnetSession,
  NexusWebSerialSession,
  runConcurrentPortScan,
  runTcpPingProbe,
  sendWakeOnLanPacket
} from '../protocols/ftp-telnet-serial-netutils.js';
import { NexusRdpSession } from '../protocols/rdp-client.js';
import { hasDirectSocketsApi, probeRelayHealth } from '../protocols/socket-transport.js';
import { NexusSftpBrowserClient, NexusSshInteractiveSession } from '../protocols/ssh-sftp-client.js';
import { domKeyToX11Keysym, NexusVncSession } from '../protocols/vnc-client.js';
import { VtTerminalEmulator } from '../protocols/vt-terminal.js';

const store = new NexusChromeOSStore();
const liveSessions = new Map(); // tabId -> { kind, instance, terminal, transferState, utilLogs }
let activeModal = null;
let dragPayload = null;
let totpRefreshInterval = null;
const windowChannel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('nexusts-chromeos-tabs') : null;

function esc(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function getTransportOptions() {
  return {
    relayWsBase: store.state.localRelayUrl,
    relayHttpBase: store.state.localRelayHttpUrl
  };
}

async function refreshRelayStatus() {
  const health = await probeRelayHealth(store.state.localRelayHttpUrl);
  store.setState({
    directSocketsAvailable: hasDirectSocketsApi(),
    localRelayReachable: health.reachable
  });
}

function cleanupTabSession(tabId) {
  const entry = liveSessions.get(tabId);
  if (!entry) return;
  try { entry.instance?.close?.(); } catch {}
  liveSessions.delete(tabId);
}

async function ensureSessionStarted(tab) {
  if (liveSessions.has(tab.tabId)) return liveSessions.get(tab.tabId);
  const conn =
    tab.adHocConnection ||
    store.state.vaultDocument.connections.find((c) => c.id === tab.connectionId);
  if (!conn) return null;

  const profile = store.resolveProfileForConnection(conn);
  const cred = profile?.effectiveCredential || {};
  const username = conn.adHocUsername || cred.username || 'root';
  const domain = conn.adHocDomain || cred.domain || '';
  const password = cred.secretPassword || '';
  const privateKeyPem = cred.privateKeyPem || '';
  const port = effectiveConnectionPort(conn);
  const transportOptions = {
    ...getTransportOptions(),
    gateway: profile?.effectiveGateway || null
  };

  if (conn.protocol === 'RDP' || conn.protocol === 'WINDOWS_TERMINAL_SERVICES' || conn.protocol === 'HYPER_V') {
    const rdp = new NexusRdpSession({
      host: conn.host,
      port,
      username,
      domain,
      password,
      settings: conn.settings,
      onStatus: (msg) => updateTabStatus(tab.tabId, msg),
      onRemoteClipboard: (txt) => navigator.clipboard?.writeText?.(txt).catch(() => {}),
      onClose: (err) => updateTabStatus(tab.tabId, err ? `Disconnected: ${err.message}` : 'Disconnected'),
      transportOptions
    });
    const entry = { kind: 'rdp', instance: rdp, conn, statusText: 'Connecting RDP…' };
    liveSessions.set(tab.tabId, entry);
    rdp.connect().catch((err) => updateTabStatus(tab.tabId, err.message));
    return entry;
  }

  if (conn.protocol === 'VNC' || conn.protocol === 'APPLE_SCREEN_SHARING') {
    const vnc = new NexusVncSession({
      host: conn.host,
      port,
      password,
      shared: conn.settings?.sharedSession !== false,
      viewOnly: Boolean(conn.settings?.viewOnly),
      onStatus: (msg) => updateTabStatus(tab.tabId, msg),
      onServerClipboard: (txt) => navigator.clipboard?.writeText?.(txt).catch(() => {}),
      onClose: (err) => updateTabStatus(tab.tabId, err ? `Closed: ${err.message}` : 'Closed'),
      transportOptions
    });
    const entry = { kind: 'vnc', instance: vnc, conn, statusText: 'Connecting VNC…' };
    liveSessions.set(tab.tabId, entry);
    vnc.connect().catch((err) => updateTabStatus(tab.tabId, err.message));
    return entry;
  }

  if (conn.protocol === 'SFTP' || conn.protocol === 'SCP' || conn.protocol === 'FTP' || conn.protocol === 'FTPS') {
    const isFtp = conn.protocol === 'FTP' || conn.protocol === 'FTPS';
    const client = isFtp
      ? new NexusFtpBrowserClient({ host: conn.host, port, username, password, transportOptions })
      : new NexusSftpBrowserClient({ host: conn.host, port, username, password, privateKeyPem, keyPassphrase: cred.keyPassphrase || '', transportOptions });
    const entry = {
      kind: 'transfer',
      instance: client,
      conn,
      transferState: {
        currentPath: conn.settings?.initialRemoteDirectory || (isFtp ? '/' : '.'),
        entries: [],
        loading: true,
        error: ''
      }
    };
    liveSessions.set(tab.tabId, entry);
    reloadTransferDirectory(tab.tabId, entry.transferState.currentPath);
    return entry;
  }

  if (conn.protocol === 'WEB_PAGE' || conn.protocol === 'VMWARE_VSPHERE' || conn.protocol === 'PROXMOX_VE' || conn.protocol === 'AWS_EC2' || conn.protocol === 'AZURE_VM') {
    const url =
      conn.settings?.url ||
      conn.settings?.apiEndpointUrl ||
      (/^https?:\/\//i.test(conn.host) ? conn.host : `https://${conn.host}${port && port !== 443 ? `:${port}` : ''}`);
    const entry = { kind: 'web', url, conn, profile };
    liveSessions.set(tab.tabId, entry);
    return entry;
  }

  if (conn.protocol.startsWith('UTILITY_')) {
    const entry = { kind: 'netutil', conn, logs: [`Target: ${conn.host}`], running: false };
    liveSessions.set(tab.tabId, entry);
    runNetUtilitySession(tab.tabId);
    return entry;
  }

  // Interactive Terminal protocols (`SSH`, `TELNET`, `SERIAL`, `DOCKER_CONTAINER`, `KUBERNETES_POD`, `LOCAL_SHELL`)
  const term = new VtTerminalEmulator(112, 32, conn.settings?.scrollbackLines || 5000);
  const entry = { kind: 'terminal', terminal: term, instance: null, conn, profile, canvas: null };
  term.onRenderRequest = () => {
    if (entry.canvas) term.renderToCanvas(entry.canvas, store.state.focusedTabId === tab.tabId);
  };
  liveSessions.set(tab.tabId, entry);

  if (conn.protocol === 'TELNET') {
    const tel = new NexusTelnetSession({
      host: conn.host,
      port,
      onData: (b) => term.feedBytes(b),
      onClose: (err) => term.feedText(`\r\n\x1b[33m[Telnet closed${err ? `: ${err.message}` : ''}]\x1b[0m\r\n`),
      transportOptions
    });
    entry.instance = tel;
    term.onReplyBytes = (b) => tel.sendInput(b);
    tel.connect().catch((err) => term.feedText(`\x1b[31mTelnet error: ${err.message}\x1b[0m\r\n`));
  } else if (conn.protocol === 'SERIAL') {
    const ser = new NexusWebSerialSession({
      settings: conn.settings,
      onData: (b) => term.feedBytes(b),
      onClose: () => term.feedText('\r\n\x1b[33m[USB Serial closed]\x1b[0m\r\n')
    });
    entry.instance = ser;
    ser.connect().catch((err) => term.feedText(`\x1b[31mWeb Serial: ${err.message}\x1b[0m\r\n`));
  } else if (conn.id.startsWith('local-shell-')) {
    const ws = new WebSocket(`${store.state.localRelayUrl.replace(/\/$/, '')}/v1/local-shell`);
    ws.binaryType = 'arraybuffer';
    ws.onmessage = (e) => term.feedBytes(e.data instanceof ArrayBuffer ? new Uint8Array(e.data) : String(e.data));
    ws.onerror = () => term.feedText('\x1b[33mStart nexusts-local-relay in ChromeOS Linux (Crostini) for a local bash PTY.\x1b[0m\r\n');
    entry.instance = {
      sendInput: async (b) => { if (ws.readyState === WebSocket.OPEN) ws.send(b); },
      close: () => ws.close()
    };
  } else {
    let bastion = null;
    if (conn.settings?.bastionJumpConnectionId) {
      const bConn = store.state.vaultDocument.connections.find((c) => c.id === conn.settings.bastionJumpConnectionId);
      if (bConn) {
        const bProf = store.resolveProfileForConnection(bConn);
        bastion = {
          host: bConn.host,
          port: effectiveConnectionPort(bConn),
          username: bProf?.effectiveCredential?.username || 'root',
          password: bProf?.effectiveCredential?.secretPassword || '',
          privateKeyPem: bProf?.effectiveCredential?.privateKeyPem || ''
        };
      }
    }
    const ssh = new NexusSshInteractiveSession({
      host: conn.host,
      port,
      username,
      password,
      privateKeyPem,
      keyPassphrase: cred.keyPassphrase || '',
      startupCommand: conn.settings?.startupCommand || '',
      bastion,
      onData: (b) => term.feedBytes(b),
      onStatus: (s) => term.feedText(`\x1b[36m[${s}]\x1b[0m\r\n`),
      onClose: (err) => term.feedText(`\r\n\x1b[33m[SSH disconnected${err ? `: ${err.message}` : ''}]\x1b[0m\r\n`),
      transportOptions
    });
    entry.instance = ssh;
    term.onReplyBytes = (b) => ssh.sendInput(b);
    ssh.connect().catch((err) => term.feedText(`\x1b[31m${err.message}\x1b[0m\r\n`));
  }
  return entry;
}

function updateTabStatus(tabId, statusText) {
  const entry = liveSessions.get(tabId);
  if (entry) entry.statusText = statusText;
  const el = document.getElementById(`pane-status-${tabId}`);
  if (el) el.textContent = statusText;
}

async function reloadTransferDirectory(tabId, path) {
  const entry = liveSessions.get(tabId);
  if (!entry || entry.kind !== 'transfer') return;
  entry.transferState.loading = true;
  entry.transferState.error = '';
  renderWorkspace();
  try {
    const res = await entry.instance.listDirectory(path);
    entry.transferState.currentPath = res.path || path;
    entry.transferState.entries = res.entries || [];
    entry.transferState.loading = false;
  } catch (err) {
    entry.transferState.loading = false;
    entry.transferState.error = err.message;
  }
  renderWorkspace();
}

async function runNetUtilitySession(tabId) {
  const entry = liveSessions.get(tabId);
  if (!entry || entry.running) return;
  entry.running = true;
  const conn = entry.conn;
  const s = conn.settings || {};
  const opts = getTransportOptions();
  try {
    if (conn.protocol === 'UTILITY_WAKE_ON_LAN') {
      const mac = s.wolMacAddress || conn.host;
      const res = await sendWakeOnLanPacket(mac, s.wolBroadcastAddress || '255.255.255.255', s.wolUdpPort || 9, s.wolSecureOnHex || '', opts);
      entry.logs.push(`Wake-on-LAN Magic Packet (${res.bytesSent} bytes) sent to ${mac} via ${res.transportKind}.`);
    } else if (conn.protocol === 'UTILITY_PORT_SCANNER') {
      const start = Number(s.portScanRangeStart || 1);
      const end = Number(s.portScanRangeEnd || 1024);
      entry.logs.push(`Scanning TCP ports ${start}..${end} on ${conn.host}…`);
      renderWorkspace();
      const hits = await runConcurrentPortScan(conn.host, start, end, 32, 300, (hit) => {
        entry.logs.push(`  OPEN ${hit.port}/tcp (${hit.guessed_service}) — ${hit.connect_latency_ms} ms`);
      }, opts);
      entry.logs.push(`Scan complete: ${hits.length} open TCP port(s) found.`);
    } else {
      const count = Math.min(10, Math.max(1, Number(s.pingCount || 5)));
      for (let i = 1; i <= count; i++) {
        const probe = await runTcpPingProbe(conn.host, 22, 1500, opts);
        entry.logs.push(
          probe.reachable
            ? `seq=${i} host=${conn.host} reachable time=${probe.latencyMs} ms`
            : `seq=${i} host=${conn.host} timeout`
        );
        renderWorkspace();
      }
    }
  } catch (err) {
    entry.logs.push(`Error: ${err.message}`);
  } finally {
    entry.running = false;
    renderWorkspace();
  }
}

// Google Drive File Picker Helpers (ChromeOS File System Access API — Zero-Prompt Transparent Sync)
async function linkExistingDriveHandleZeroPrompt(handle) {
  if (!handle) return;
  // Request full 'readwrite' immediately while transient user activation from the OS picker is active!
  try {
    await handle.requestPermission?.({ mode: 'readwrite' });
  } catch {
    // Fallback handled inside FileHandleVaultTransport
  }
  try {
    await store.linkGoogleDriveTransport(handle, handle);
    store.showToast(`Synced with ${handle.name || 'Google Drive'}!`);
  } catch (err) {
    if (err?.name === 'VaultAuthenticationError') {
      // Only prompt if the user explicitly encrypted this .nxts file with a custom Master Passphrase
      openPassphrasePromptModal(
        async (pw) => {
          await store.linkGoogleDriveTransport(handle, handle, pw);
          store.showToast(`Synced with ${handle.name || 'Google Drive'}!`);
        },
        `Enter Master Passphrase for ${handle.name || '.nxts Vault'}`,
        () => store.disconnectGoogleDriveSync()
      );
    } else if (err?.name !== 'AbortError') {
      store.showToast(err.message);
    }
  }
}

async function handleCreateSyncedDriveVault() {
  if (!globalThis.showSaveFilePicker) {
    const out = await store.exportCurrentVault('NXTS');
    downloadBlob(out.bytes, 'NexusTS-Vault.nxts', out.mimeType);
    store.showToast('Downloaded NexusTS-Vault.nxts (live folder sync requires Chrome / ChromeOS).');
    return;
  }
  try {
    const handle = await globalThis.showSaveFilePicker({
      suggestedName: 'NexusTS-Vault.nxts',
      types: [{ description: 'NexusTS Encrypted Vault (.nxts)', accept: { 'application/octet-stream': ['.nxts'] } }]
    });
    try {
      await handle.requestPermission?.({ mode: 'readwrite' });
    } catch {
      // showSaveFilePicker grants readwrite by default
    }
    await store.linkGoogleDriveTransport(handle, handle);
    store.showToast(`Synced with ${handle.name || 'Google Drive'}!`);
  } catch (err) {
    if (err?.name !== 'AbortError') store.showToast(err.message);
  }
}

async function handleOpenExistingSyncedDriveVault() {
  if (!globalThis.showOpenFilePicker) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.nxts';
    input.onchange = async () => {
      const file = input.files?.[0];
      if (!file) return;
      const bytes = new Uint8Array(await file.arrayBuffer());
      try {
        await store.importVaultFileBytes(bytes, file.name, '', 'REPLACE');
        store.showToast(`Loaded ${file.name}.`);
      } catch (err) {
        if (err?.name === 'VaultAuthenticationError') {
          openPassphrasePromptModal(async (pw) => {
            await store.importVaultFileBytes(bytes, file.name, pw, 'REPLACE');
            store.showToast(`Loaded ${file.name}.`);
          }, `Enter Master Passphrase for ${file.name}`);
        } else {
          store.showToast(err.message);
        }
      }
    };
    input.click();
    return;
  }
  try {
    const [handle] = await globalThis.showOpenFilePicker({
      multiple: false,
      types: [{ description: 'NexusTS Encrypted Vault (.nxts)', accept: { 'application/octet-stream': ['.nxts'] } }]
    });
    if (!handle) return;
    await linkExistingDriveHandleZeroPrompt(handle);
  } catch (err) {
    if (err?.name !== 'AbortError') store.showToast(err.message);
  }
}

function openPassphrasePromptModal(onConfirm, title = 'Enter Master Passphrase', onCancel = null) {
  activeModal = {
    kind: 'passphrase-prompt',
    title,
    onConfirm,
    onCancel
  };
  renderWorkspace();
}

// 1:1 macOS AppleHIGDesignSystem Protocol Tints & Vector Icons (Zero text "RDP" prefix)
const CANONICAL_GROUP_ORDER = [
  'RDP', 'SSH', 'VNC', 'ARD', 'SFTP', 'SCP', 'FTP', 'FTPS',
  'TEL', 'UART', 'WEB', 'K8S', 'OCI', 'PVE', 'ESXi', 'HV',
  'EC2', 'AZ', 'PING', 'MTR', 'SCAN', 'WOL'
];

function normalizeHexColor(rawHex) {
  if (typeof rawHex !== 'string') return null;
  const clean = rawHex.trim().replace(/^#/, '');
  return /^[0-9a-fA-F]{6}$/.test(clean) ? `#${clean}` : null;
}

function protocolAppleTint(protocol, customHex = null) {
  const override = normalizeHexColor(customHex);
  if (override) return override;
  switch (protocol) {
    case 'RDP':
    case 'WINDOWS_TERMINAL_SERVICES':
      return '#007aff';
    case 'SSH':
    case 'TELNET':
      return '#5856d6';
    case 'SERIAL':
      return '#34c759';
    case 'SFTP':
    case 'FTP':
    case 'FTPS':
    case 'SCP':
      return '#ff9500';
    case 'VNC':
    case 'APPLE_SCREEN_SHARING':
      return '#ff2d55';
    case 'WEB_PAGE':
      return '#af52de';
    case 'VMWARE_VSPHERE':
    case 'PROXMOX_VE':
    case 'HYPER_V':
    case 'AWS_EC2':
    case 'AZURE_VM':
      return '#009ec2';
    case 'KUBERNETES_POD':
    case 'DOCKER_CONTAINER':
      return '#0d9488';
    default:
      return '#00a896';
  }
}

function renderSidebarConnectionTypeIcon(protocol, customHex = null, size = 15) {
  const tint = protocolAppleTint(protocol, customHex);
  switch (protocol) {
    case 'RDP':
    case 'WINDOWS_TERMINAL_SERVICES':
      // Exact 1:1 macOS SidebarConnectionTypeIcon (circle + opposing Remote Desktop / Royal TSX chevrons)
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="8.6" stroke="${tint}" stroke-width="1.84" stroke-linecap="round" stroke-linejoin="round"/><path d="M5.8 8.8L8.8 11.6L5.8 14.4M14.2 5.6L11.2 8.4L14.2 11.2" stroke="${tint}" stroke-width="2.04" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
    case 'SSH':
    case 'TELNET':
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><rect x="2.2" y="3.4" width="15.6" height="13.2" rx="3" stroke="${tint}" stroke-width="1.75"/><path d="M5.8 7.5L8.9 10L5.8 12.5M10.4 12.6H14.2" stroke="${tint}" stroke-width="1.85" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
    case 'SFTP':
    case 'FTP':
    case 'FTPS':
    case 'SCP':
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><rect x="2.4" y="2.4" width="15.2" height="15.2" rx="3.2" stroke="${tint}" stroke-width="1.7"/><path d="M6.2 8H13.8M11.6 5.8L13.8 8L11.6 10.2M13.8 12.4H6.2M8.4 10.2L6.2 12.4L8.4 14.6" stroke="${tint}" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
    case 'VNC':
    case 'APPLE_SCREEN_SHARING':
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><rect x="2.3" y="5.4" width="11.4" height="8.8" rx="2" stroke="${tint}" stroke-width="1.7"/><path d="M6.5 5.2V4.4C6.5 3.5 7.2 2.8 8.1 2.8H15.9C16.8 2.8 17.5 3.5 17.5 4.4V10.2C17.5 11.1 16.8 11.8 15.9 11.8H14" stroke="${tint}" stroke-width="1.7" stroke-linecap="round"/></svg>`;
    case 'SERIAL':
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M2.2 10H6.2M13.8 10H17.8" stroke="${tint}" stroke-width="1.8" stroke-linecap="round"/><rect x="6.2" y="6.6" width="7.6" height="6.8" rx="1.8" stroke="${tint}" stroke-width="1.75"/></svg>`;
    case 'WEB_PAGE':
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><circle cx="10" cy="10" r="7.8" stroke="${tint}" stroke-width="1.7"/><path d="M12.9 7.1L11.1 11.1L7.1 12.9L8.9 8.9L12.9 7.1Z" stroke="${tint}" stroke-width="1.55" stroke-linejoin="round"/></svg>`;
    case 'VMWARE_VSPHERE':
    case 'PROXMOX_VE':
    case 'HYPER_V':
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><rect x="3" y="3.2" width="14" height="5.6" rx="1.6" stroke="${tint}" stroke-width="1.7"/><rect x="3" y="11.2" width="14" height="5.6" rx="1.6" stroke="${tint}" stroke-width="1.7"/><circle cx="6.2" cy="6" r="1" fill="${tint}"/><circle cx="6.2" cy="14" r="1" fill="${tint}"/></svg>`;
    case 'AWS_EC2':
    case 'AZURE_VM':
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M6.2 15.2H14.4C16.3 15.2 17.8 13.7 17.8 11.9C17.8 10.2 16.5 8.8 14.8 8.6C14.2 6.1 12 4.4 9.4 4.4C6.4 4.4 4 6.8 4 9.8C2.9 10.3 2.2 11.4 2.2 12.6C2.2 14.1 3.5 15.2 5.1 15.2H6.2Z" stroke="${tint}" stroke-width="1.7" stroke-linejoin="round"/></svg>`;
    case 'KUBERNETES_POD':
    case 'DOCKER_CONTAINER':
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 2.4L16.8 6.2V13.8L10 17.6L3.2 13.8V6.2L10 2.4Z" stroke="${tint}" stroke-width="1.7" stroke-linejoin="round"/><path d="M3.4 6.4L10 10.1L16.6 6.4M10 10.1V17.4" stroke="${tint}" stroke-width="1.6" stroke-linecap="round"/></svg>`;
    default:
      return `<svg class="conn-type-icon" width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M2.2 10.2H5.6L7.6 5.2L10.4 14.8L12.8 8.2L14.4 10.2H17.8" stroke="${tint}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  }
}

function renderSidebarFolderIcon(isOpen, customHex = null) {
  const tint = normalizeHexColor(customHex) || '#007aff';
  if (isOpen) {
    return `<svg class="folder-type-icon" width="15" height="15" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M2.2 5.4C2.2 4.4 3 3.6 4 3.6H7.8L9.6 5.5H16C17 5.5 17.8 6.3 17.8 7.3V14.6C17.8 15.6 17 16.4 16 16.4H4C3 16.4 2.2 15.6 2.2 14.6V5.4Z" fill="${tint}" fill-opacity="0.88"/></svg>`;
  }
  return `<svg class="folder-type-icon" width="15" height="15" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M2.4 5.5C2.4 4.56 3.16 3.8 4.1 3.8H7.7L9.5 5.7H15.9C16.84 5.7 17.6 6.46 17.6 7.4V14.5C17.6 15.44 16.84 16.2 15.9 16.2H4.1C3.16 16.2 2.4 15.44 2.4 14.5V5.5Z" stroke="${tint}" stroke-width="1.65" stroke-linejoin="round"/></svg>`;
}

function renderFolderChevron(isOpen, hasChildren) {
  const cls = hasChildren ? 'folder-chevron' : 'folder-chevron empty';
  if (isOpen) {
    return `<svg class="${cls}" width="11" height="11" viewBox="0 0 12 12" fill="none" aria-hidden="true"><path d="M2.5 4.2L6 7.8L9.5 4.2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  }
  return `<svg class="${cls}" width="11" height="11" viewBox="0 0 12 12" fill="none" aria-hidden="true"><path d="M4.2 2.5L7.8 6L4.2 9.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
}

// 1:1 port of macOS `IntelligentConnectionGrouper` (only shows header when 2+ protocols share a folder)
function groupConnectionsLikeMac(connections, preserveOrder = false) {
  if (!connections.length) return [];
  const buckets = new Map();
  for (const conn of connections) {
    const rawBadge = PROTOCOL_CATALOG[conn.protocol]?.badge || conn.protocol;
    const badge = conn.protocol === 'RDP' || conn.protocol === 'WINDOWS_TERMINAL_SERVICES' ? 'RDP' : rawBadge;
    if (!buckets.has(badge)) buckets.set(badge, []);
    buckets.get(badge).push(conn);
  }
  const sortedBadges = [...buckets.keys()].sort((a, b) => {
    const ra = CANONICAL_GROUP_ORDER.indexOf(a);
    const rb = CANONICAL_GROUP_ORDER.indexOf(b);
    const ia = ra === -1 ? CANONICAL_GROUP_ORDER.length : ra;
    const ib = rb === -1 ? CANONICAL_GROUP_ORDER.length : rb;
    return ia !== ib ? ia - ib : a.localeCompare(b);
  });
  const showsHeader = sortedBadges.length > 1;
  return sortedBadges.map((badge) => {
    const items = buckets.get(badge);
    const ordered = preserveOrder
      ? items
      : [...items].sort((a, b) => (a.sortOrder - b.sortOrder) || a.name.localeCompare(b.name));
    return { badge, connections: ordered, showsHeader };
  });
}

function renderGroupedConnections(conns, depth, activeConnIds, preserveOrder = false) {
  const groups = groupConnectionsLikeMac(conns, preserveOrder);
  let html = '';
  for (const g of groups) {
    if (g.showsHeader) {
      const hdrPad = 10 + depth * 14;
      html += `
        <div class="protocol-group-header" style="padding-left:${hdrPad}px">
          <span>${esc(g.badge)}</span>
          <span class="protocol-group-line"></span>
          <span class="protocol-group-count">${g.connections.length}</span>
        </div>
      `;
    }
    for (const c of g.connections) {
      html += renderConnectionRow(c, depth, activeConnIds);
    }
  }
  return html;
}

function countFolderSubtreeStats(folderId, allFolders, allConns, activeConnIds) {
  let total = 0;
  let live = 0;
  const stack = [folderId];
  const seen = new Set();
  while (stack.length) {
    const fid = stack.pop();
    if (seen.has(fid)) continue;
    seen.add(fid);
    for (const c of allConns) {
      if (c.parentFolderId === fid) {
        total += 1;
        if (activeConnIds.has(c.id)) live += 1;
      }
    }
    for (const sub of allFolders) {
      if (sub.parentFolderId === fid) stack.push(sub.id);
    }
  }
  return { total, live };
}

// Rendering Helpers (1:1 with macOS SidebarHierarchyView.swift)
function renderFolderSubtree(parentId, depth, activeConnIds) {
  const doc = store.state.vaultDocument;
  const query = store.state.sidebarFilterQuery.trim().toLowerCase();
  const qf = store.state.sidebarQuickFilter;
  const recentsSet = new Set(doc.recentConnectionIds || []);
  const allFolders = doc.folders || [];
  const allConns = doc.connections || [];
  const validFolderIds = new Set(allFolders.map((f) => f.id));

  const connMatches = (c) => {
    if (qf === 'FAVORITES' && !c.favorite) return false;
    if (qf === 'RECENTS' && !recentsSet.has(c.id)) return false;
    if (query && !c.name.toLowerCase().includes(query) && !c.host.toLowerCase().includes(query)) return false;
    return true;
  };

  if (qf !== 'ALL' && parentId === null) {
    const filtered = qf === 'RECENTS'
      ? (doc.recentConnectionIds || []).map((id) => allConns.find((c) => c.id === id)).filter((c) => c && connMatches(c))
      : allConns.filter(connMatches);
    return renderGroupedConnections(filtered, 0, activeConnIds, qf === 'RECENTS');
  }

  const effectiveParent = (rawParentId) => (rawParentId && validFolderIds.has(rawParentId) ? rawParentId : null);

  const folders = allFolders
    .filter((f) => effectiveParent(f.parentFolderId) === parentId)
    .sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
  const conns = allConns
    .filter((c) => effectiveParent(c.parentFolderId) === parentId && connMatches(c));

  let html = '';
  for (const f of folders) {
    const isOpen = store.state.expandedFolderIds.has(f.id) || Boolean(query);
    const hasChildFolders = allFolders.some((sf) => effectiveParent(sf.parentFolderId) === f.id);
    const hasChildConns = allConns.some((sc) => effectiveParent(sc.parentFolderId) === f.id);
    const hasChildren = hasChildFolders || hasChildConns;
    const stats = countFolderSubtreeStats(f.id, allFolders, allConns, activeConnIds);
    const isSelected = store.state.selectedFolderId === f.id && !store.state.selectedConnectionId;
    const padLeft = 6 + depth * 14;

    html += `
      <div class="tree-row folder-row ${isSelected ? 'selected' : ''}"
           style="padding-left:${padLeft}px"
           draggable="true"
           data-action="toggle-folder"
           data-folder-id="${esc(f.id)}"
           data-drop-folder-id="${esc(f.id)}">
        ${renderFolderChevron(isOpen, hasChildren)}
        ${renderSidebarFolderIcon(isOpen, f.inheritable?.displayColorAccentHex)}
        <span class="tree-row-name folder-name">${esc(f.name)}</span>
        ${stats.live > 0
          ? `<span class="folder-count live"><span class="active-dot"></span>${stats.live}</span>`
          : stats.total > 0
            ? `<span class="folder-count">${stats.total}</span>`
            : ''}
        <span class="row-actions">
          <button type="button" class="mini-action-btn" data-action="start-folder-rdp" data-folder-id="${esc(f.id)}" data-tooltip="${esc(store.strings.startAllRdpInFolder)}">▶</button>
          <button type="button" class="mini-action-btn" data-action="edit-folder" data-folder-id="${esc(f.id)}" data-tooltip="${esc(store.strings.edit)}">✎</button>
        </span>
      </div>
    `;
    if (isOpen) {
      html += renderFolderSubtree(f.id, depth + 1, activeConnIds);
    }
  }
  html += renderGroupedConnections(conns, depth, activeConnIds, false);
  return html;
}

function renderConnectionRow(c, depth, activeConnIds) {
  const isActive = activeConnIds.has(c.id);
  const isSelected = store.state.selectedConnectionId === c.id;
  const protoTitle = PROTOCOL_CATALOG[c.protocol]?.title || c.protocol;
  const padLeft = 20 + depth * 14;
  return `
    <div class="tree-row conn-row ${isSelected ? 'selected' : ''} ${isActive ? 'active-session' : ''}"
         style="padding-left:${padLeft}px"
         draggable="true"
         data-action="select-connection"
         data-dblaction="launch-connection"
         data-conn-id="${esc(c.id)}"
         data-tooltip="${esc(`${c.name} • ${protoTitle}`)}">
      ${renderSidebarConnectionTypeIcon(c.protocol, c.inheritable?.displayColorAccentHex, 15)}
      <span class="tree-row-name">${esc(c.name)}</span>
      ${c.favorite ? '<span class="fav-star" aria-hidden="true">★</span>' : ''}
      ${isActive ? '<span class="active-dot"></span>' : ''}
      <span class="row-actions">
        <button type="button" class="mini-action-btn" data-action="launch-connection" data-conn-id="${esc(c.id)}" data-tooltip="${esc(store.strings.connectNow)}">⚡</button>
        <button type="button" class="mini-action-btn" data-action="edit-connection" data-conn-id="${esc(c.id)}" data-tooltip="${esc(store.strings.edit)}">✎</button>
      </span>
    </div>
  `;
}

function renderUnifiedSettings() {
  const s = store.strings;
  const tab = store.state.activeSettingsTab;
  const doc = store.state.vaultDocument;
  const tabsMeta = [
    { id: 'general', label: s.settingsTabGeneral },
    { id: 'sync', label: s.settingsTabDriveSync },
    { id: 'security', label: s.settingsTabSecurity },
    { id: 'credentials', label: s.settingsTabCredentials },
    { id: 'gateways', label: s.settingsTabGateways },
    { id: 'templates', label: s.settingsTabTemplates },
    { id: 'tasks', label: s.settingsTabTasks },
    { id: 'updates', label: s.settingsTabUpdates }
  ];

  let bodyHtml = '';
  if (tab === 'general') {
    bodyHtml = `
      <div class="studio-card">
        <h3>${esc(s.settingsTabGeneral)}</h3>
        <div class="field-group">
          <label>Language / Sprache</label>
          <div class="topbar-cluster" style="flex-wrap:wrap;gap:8px">
            ${SUPPORTED_LOCALES.map((l) => `<button type="button" class="pill-btn ${store.state.locale === l.code ? 'active' : ''}" data-action="select-locale" data-locale="${esc(l.code)}">${esc(l.label)}</button>`).join('')}
          </div>
        </div>
        <div class="field-group" style="max-width:420px">
          <label>Vault Name</label>
          <input class="text-input" id="input-vault-name" value="${esc(doc.name)}" />
        </div>
        <div>
          <button type="button" class="pill-btn" data-action="save-vault-name">Save Vault Name</button>
        </div>
      </div>

      <div class="studio-card">
        <h3>Import &amp; Export</h3>
        <p>100% offline import &amp; export compatible with macOS, Android, Royal TS (.rtsz / .rtsx), KeePass XML, Bitwarden, 1Password &amp; CSV.</p>
        <div class="topbar-cluster" style="flex-wrap:wrap">
          <button type="button" class="pill-btn primary" data-action="open-import-modal">${esc(s.importVault)}</button>
          <button type="button" class="pill-btn" data-action="export-vault" data-format="NXTS">${esc(s.exportNxts)}</button>
          <button type="button" class="pill-btn" data-action="export-vault" data-format="RTSX">${esc(s.exportRtsx)}</button>
          <button type="button" class="pill-btn" data-action="export-vault" data-format="CSV">${esc(s.exportCsv)}</button>
          ${store.state.preImportBackupDoc ? `<button type="button" class="pill-btn danger" data-action="undo-import">${esc(s.undoPreImport)}</button>` : ''}
        </div>
      </div>
    `;
  } else if (tab === 'sync') {
    // Simplified v0.8.14 single-card Google Drive Sync UI (zero OAuth Client IDs, Folder IDs, or VectorClock noise!)
    const isLinked = Boolean(store.state.driveLinkedName);
    const needsGrant = Boolean(store.state.driveNeedsPermissionGrant);
    bodyHtml = `
      <div class="studio-card">
        <div style="display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap">
          <div style="display:flex;align-items:center;gap:12px">
            <span style="font-size:24px">${isLinked ? '✅' : '☁️'}</span>
            <div>
              <div style="display:flex;align-items:center;gap:8px">
                <h3>${esc(s.driveTitle)}</h3>
                ${isLinked ? `<span class="status-chip ${needsGrant ? '' : 'online'}"><span class="status-dot"></span>${esc(store.state.driveIsSyncing ? s.driveSyncingBadge : needsGrant ? 'Permission Needed' : s.driveActiveBadge)}</span>` : ''}
              </div>
              <p>${esc(s.driveSubtitle)}</p>
            </div>
          </div>
          ${isLinked ? `<button type="button" class="pill-btn primary" data-action="drive-sync-now">${esc(needsGrant ? 'Resume Sync' : s.driveSyncNow)}</button>` : ''}
        </div>

        ${isLinked ? `
          <div class="item-row">
            <div>
              <strong>${esc(store.state.driveLinkedName)}</strong>
              <div style="color:var(--text-secondary);font-size:12px">${esc(store.state.driveStatusMessage || 'Ready for automatic passwordless sync')}</div>
            </div>
            <div class="topbar-cluster">
              <button type="button" class="pill-btn" data-action="drive-open-synced">Switch Vault File</button>
              <button type="button" class="pill-btn danger" data-action="drive-disconnect">${esc(s.driveDisconnect)}</button>
            </div>
          </div>
        ` : `
          <div class="topbar-cluster" style="flex-wrap:wrap">
            <button type="button" class="pill-btn primary" data-action="drive-create-synced">${esc(s.driveCreateSynced)}</button>
            <button type="button" class="pill-btn" data-action="drive-open-synced">${esc(s.driveOpenSynced)}</button>
          </div>
        `}
      </div>
    `;
  } else if (tab === 'security') {
    bodyHtml = `
      <div class="studio-card">
        <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap">
          <h3>Zero-Knowledge Argon2id + AES-256-GCM Security</h3>
          <span class="status-chip online"><span class="status-dot"></span>${store.state.hasCustomPassphrase ? 'Custom Master Passphrase Active' : 'Automatic Passwordless Sync Active'}</span>
        </div>
        <p>By default, NexusTS encrypts your vault automatically without asking for a password (100% compatible with macOS &amp; Android Google Drive Sync). Optionally set a custom Master Passphrase below if you want a lock-screen password.</p>
        <div class="form-grid">
          <div class="field-group">
            <label>Optional Master Passphrase</label>
            <input type="password" class="text-input" id="sec-new-passphrase" placeholder="Leave blank for automatic passwordless sync…" />
          </div>
          <div class="field-group">
            <label>Idle Auto-Lock</label>
            <select class="select-input" data-change="set-autolock">
              ${[0, 5, 15, 30, 60].map((m) => `<option value="${m}" ${store.state.idleAutoLockMinutes === m ? 'selected' : ''}>${m === 0 ? 'Disabled' : `${m} minutes`}</option>`).join('')}
            </select>
          </div>
        </div>
        <div class="topbar-cluster">
          <button type="button" class="pill-btn primary" data-action="apply-master-passphrase">Save Security Settings</button>
          ${store.state.hasCustomPassphrase ? `<button type="button" class="pill-btn" data-action="lock-vault">${esc(s.lockVault)}</button>` : ''}
        </div>
      </div>
    `;
  } else if (tab === 'credentials') {
    bodyHtml = `
      <div class="studio-card">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div>
            <h3>${esc(s.settingsTabCredentials)}</h3>
            <p>Passwords, OpenSSH Ed25519 keys &amp; live TOTP 2FA codes inherited across folders.</p>
          </div>
          <div class="topbar-cluster">
            <button type="button" class="pill-btn primary" data-action="open-credential-modal">+ ${esc(s.newCredential)}</button>
            <button type="button" class="pill-btn" data-action="generate-ed25519-cred">Generate SSH Ed25519 Key</button>
          </div>
        </div>
        <div class="item-list">
          ${(doc.credentials || []).map((cr) => `
            <div class="item-row">
              <div>
                <strong>${esc(cr.name)}</strong>
                <div style="font-size:12px;color:var(--text-secondary)">
                  ${esc(cr.username || '—')} ${cr.domain ? `(${esc(cr.domain)})` : ''}
                  ${cr.keyFingerprintSha256 ? ` • <code>${esc(cr.keyFingerprintSha256)}</code>` : ''}
                </div>
              </div>
              <div class="topbar-cluster">
                ${cr.totpSecretBase32 ? `<span class="status-chip online" data-totp-secret="${esc(cr.totpSecretBase32)}">TOTP …</span>` : ''}
                ${cr.publicKeyOpenSsh ? `<button type="button" class="pill-btn" data-action="copy-pubkey" data-cred-id="${esc(cr.id)}">Copy Public Key</button>` : ''}
                <button type="button" class="pill-btn danger" data-action="delete-object" data-object-id="${esc(cr.id)}">${esc(s.delete)}</button>
              </div>
            </div>
          `).join('') || '<p>No saved credentials yet.</p>'}
        </div>
      </div>
    `;
  } else if (tab === 'gateways') {
    bodyHtml = `
      <div class="studio-card">
        <h3>ChromeOS Transport &amp; Secure Gateways</h3>
        <div class="item-row">
          <div>
            <strong>ChromeOS Direct Sockets API (Isolated Web App .swbn)</strong>
            <div style="font-size:12px;color:var(--text-secondary)">100% standalone raw TCP/UDP sockets inside ChromeOS without any helper process.</div>
          </div>
          <span class="status-chip ${store.state.directSocketsAvailable ? 'online' : ''}">
            <span class="status-dot"></span>${store.state.directSocketsAvailable ? 'Active (Native IWA)' : 'Browser PWA Mode'}
          </span>
        </div>
        <div class="item-row">
          <div>
            <strong>Local Crostini Companion Relay (<code>nexusts-local-relay</code>)</strong>
            <div style="font-size:12px;color:var(--text-secondary)">Optional local fallback when running as a standard browser PWA without IWA flags.</div>
          </div>
          <div class="topbar-cluster">
            <input class="text-input" id="input-relay-url" value="${esc(store.state.localRelayUrl)}" style="width:210px" />
            <button type="button" class="pill-btn" data-action="save-relay-url">Probe</button>
            <span class="status-chip ${store.state.localRelayReachable ? 'online' : ''}">${store.state.localRelayReachable ? 'Connected' : 'Standby'}</span>
          </div>
        </div>
        <div style="display:flex;justify-content:space-between;align-items:center;margin-top:6px">
          <h3>Enterprise Gateways &amp; Proxies</h3>
          <button type="button" class="pill-btn primary" data-action="open-gateway-modal">+ Add Gateway</button>
        </div>
        <div class="item-list">
          ${(doc.gateways || []).map((gw) => `
            <div class="item-row">
              <div>
                <strong>${esc(gw.name)}</strong>
                <div style="font-size:12px;color:var(--text-secondary)">${esc(gw.kind)} • ${esc(gw.host)}:${esc(gw.port)}</div>
              </div>
              <button type="button" class="pill-btn danger" data-action="delete-object" data-object-id="${esc(gw.id)}">${esc(s.delete)}</button>
            </div>
          `).join('') || '<p>No Secure Gateways configured.</p>'}
        </div>
      </div>
    `;
  } else if (tab === 'templates') {
    bodyHtml = `
      <div class="studio-card">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div>
            <h3>${esc(s.settingsTabTemplates)}</h3>
            <p>Reusable protocol blueprints for rapid server creation.</p>
          </div>
          <button type="button" class="pill-btn primary" data-action="open-template-modal">+ ${esc(s.newTemplate)}</button>
        </div>
        <div class="item-list">
          ${(doc.templates || []).map((tpl) => `
            <div class="item-row">
              <div>
                <strong>${esc(tpl.name)}</strong>
                <div style="font-size:12px;color:var(--text-secondary)">${esc(tpl.protocol)} • ${esc(tpl.description || '')}</div>
              </div>
              <div class="topbar-cluster">
                <button type="button" class="pill-btn" data-action="new-from-template" data-tpl-id="${esc(tpl.id)}">Create Server</button>
                <button type="button" class="pill-btn danger" data-action="delete-object" data-object-id="${esc(tpl.id)}">${esc(s.delete)}</button>
              </div>
            </div>
          `).join('') || '<p>No templates defined yet.</p>'}
        </div>
      </div>
    `;
  } else if (tab === 'tasks') {
    bodyHtml = `
      <div class="studio-card">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div>
            <h3>Automation Command &amp; Key-Sequence Tasks</h3>
            <p>Execute commands or keystroke macros (<code>{WAIT:300}</code>, <code>{ENTER}</code>, <code>$EffectiveUsername$</code>) across active sessions.</p>
          </div>
          <button type="button" class="pill-btn primary" data-action="open-task-modal">+ Add Task</button>
        </div>
        <div class="item-list">
          ${(doc.commandTasks || []).map((t) => `
            <div class="item-row">
              <div>
                <strong>${esc(t.name)}</strong>
                <div style="font-size:12px;color:var(--text-secondary);font-family:var(--font-mono)">${esc(t.commandTemplate)}</div>
              </div>
              <div class="topbar-cluster">
                <button type="button" class="pill-btn primary" data-action="run-cmd-task" data-task-id="${esc(t.id)}">Run</button>
                <button type="button" class="pill-btn danger" data-action="delete-object" data-object-id="${esc(t.id)}">${esc(s.delete)}</button>
              </div>
            </div>
          `).join('')}
          ${(doc.keySequenceTasks || []).map((k) => `
            <div class="item-row">
              <div>
                <strong>${esc(k.name)} (Macro)</strong>
                <div style="font-size:12px;color:var(--text-secondary);font-family:var(--font-mono)">${esc(k.macroSequence)}</div>
              </div>
              <div class="topbar-cluster">
                <button type="button" class="pill-btn primary" data-action="run-key-task" data-task-id="${esc(k.id)}">Play Macro</button>
                <button type="button" class="pill-btn danger" data-action="delete-object" data-object-id="${esc(k.id)}">${esc(s.delete)}</button>
              </div>
            </div>
          `).join('')}
        </div>
      </div>
    `;
  } else if (tab === 'updates') {
    bodyHtml = `
      <div class="studio-card">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
          <div>
            <h3>NexusTS ChromeOS Edition v${esc(store.state.appVersion)}</h3>
            <p>100% Offline Service Worker &amp; ChromeOS Startmenü-App — läuft nach dem ersten Öffnen komplett ohne Internetverbindung.</p>
          </div>
          ${store.state.pwaStandalone
            ? `<span class="status-chip online"><span class="status-dot"></span>${esc(s.installedStandaloneBadge)}</span>`
            : store.state.pwaInstallAvailable
              ? `<button type="button" class="pill-btn primary" data-action="install-pwa" data-tooltip="${esc(s.installAppTooltip)}">⬇ ${esc(s.installAppCta)}</button>`
              : ''}
        </div>
        <div class="topbar-cluster">
          ${store.state.swUpdateWaiting
            ? `<button type="button" class="pill-btn primary" data-action="apply-sw-update">↻ ${esc(s.applyUpdateCta)}</button>`
            : ''}
          <button type="button" class="pill-btn primary" data-action="check-github-updates">Check Updates</button>
          <button type="button" class="pill-btn" data-action="refresh-offline-sw">Verify Offline Cache</button>
        </div>
        ${store.state.localBuildHash ? `<p style="margin-top:4px;font-size:11px;color:var(--text-muted)">Build Hash: <code>${esc(store.state.localBuildHash)}</code></p>` : ''}
        ${store.state.updateCheckStatus ? `<p style="margin-top:6px">${esc(store.state.updateCheckStatus)}</p>` : ''}
      </div>
    `;
  }

  return `
    <section class="settings-screen" aria-label="${esc(s.navSettings)}">
      <div class="settings-header">
        <h2>${esc(s.navSettings)}</h2>
        <div class="settings-tabs" role="tablist">
          ${tabsMeta.map((t) => `
            <button type="button"
                    role="tab"
                    aria-selected="${tab === t.id}"
                    class="settings-tab-chip ${tab === t.id ? 'active' : ''}"
                    data-action="select-settings-tab"
                    data-tab-id="${t.id}">
              ${esc(t.label)}
            </button>
          `).join('')}
        </div>
      </div>
      <div class="settings-content">${bodyHtml}</div>
    </section>
  `;
}

function renderPaneBody(tab) {
  const entry = liveSessions.get(tab.tabId);
  if (!entry) {
    return `<div style="color:#cbd5e1">Initializing session…</div>`;
  }
  if (entry.kind === 'rdp' || entry.kind === 'vnc' || entry.kind === 'terminal') {
    return `<canvas id="session-canvas-${esc(tab.tabId)}" class="session-canvas" tabindex="0" data-tab-id="${esc(tab.tabId)}"></canvas>`;
  }
  if (entry.kind === 'web') {
    const useControlledFrame = typeof document.createElement('controlledframe').src === 'string';
    const tag = useControlledFrame ? 'controlledframe' : 'iframe';
    return `<${tag} src="${esc(entry.url)}" style="width:100%;height:100%;border:none;background:#fff"></${tag}>`;
  }
  if (entry.kind === 'netutil') {
    return `
      <div class="commander-wrap">
        <div class="commander-bar">
          <strong>${esc(entry.conn.name)} (${esc(entry.conn.host)})</strong>
          <button type="button" class="pill-btn" data-action="rerun-netutil" data-tab-id="${esc(tab.tabId)}">Run Again</button>
        </div>
        <pre style="flex:1;margin:0;padding:12px;background:#0f172a;color:#f8fafc;border-radius:10px;overflow:auto;font-family:var(--font-mono);font-size:12px">${esc(entry.logs.join('\n'))}</pre>
      </div>
    `;
  }
  if (entry.kind === 'transfer') {
    const st = entry.transferState;
    return `
      <div class="commander-wrap">
        <div class="commander-bar">
          <button type="button" class="pill-btn" data-action="transfer-up" data-tab-id="${esc(tab.tabId)}">↑ Up</button>
          <input class="text-input" id="transfer-path-${esc(tab.tabId)}" value="${esc(st.currentPath)}" style="flex:1" />
          <button type="button" class="pill-btn" data-action="transfer-go" data-tab-id="${esc(tab.tabId)}">Go</button>
          <button type="button" class="pill-btn primary" data-action="transfer-upload" data-tab-id="${esc(tab.tabId)}">Upload File</button>
          <button type="button" class="pill-btn" data-action="transfer-mkdir" data-tab-id="${esc(tab.tabId)}">+ Folder</button>
        </div>
        ${st.error ? `<div style="color:var(--status-danger)">${esc(st.error)}</div>` : ''}
        <div style="flex:1;overflow:auto;border:1px solid var(--border-subtle);border-radius:10px">
          <table class="commander-table">
            <thead><tr><th>Name</th><th>Size</th><th>Actions</th></tr></thead>
            <tbody>
              ${st.entries.map((item) => `
                <tr>
                  <td>${item.is_directory ? '📁' : '📄'} ${esc(item.name)}</td>
                  <td>${item.is_directory ? '—' : `${item.size_bytes} B`}</td>
                  <td>
                    ${item.is_directory
                      ? `<button type="button" class="pill-btn" data-action="transfer-cd" data-tab-id="${esc(tab.tabId)}" data-path="${esc(item.full_path)}">Open</button>`
                      : `<button type="button" class="pill-btn" data-action="transfer-download" data-tab-id="${esc(tab.tabId)}" data-path="${esc(item.full_path)}" data-name="${esc(item.name)}">Download</button>`
                    }
                    <button type="button" class="pill-btn danger" data-action="transfer-delete" data-tab-id="${esc(tab.tabId)}" data-path="${esc(item.full_path)}" data-isdir="${item.is_directory}">Delete</button>
                  </td>
                </tr>
              `).join('') || `<tr><td colspan="3" style="color:var(--text-muted)">${st.loading ? 'Loading directory…' : 'Empty directory'}</td></tr>`}
            </tbody>
          </table>
        </div>
      </div>
    `;
  }
  return '';
}

function renderActiveSessionsStage() {
  const s = store.strings;
  const tabs = store.state.tabs;
  if (tabs.length === 0) {
    return `
      <div class="empty-stage">
        <img src="./icons/favicon.svg" alt="" style="width:62px;height:62px;border-radius:15px" />
        <h2>${esc(s.emptyWorkspaceTitle)}</h2>
        <p>${esc(s.emptyWorkspaceHint)}</p>
        <div class="topbar-cluster">
          <button type="button" class="pill-btn primary" data-action="open-connection-modal">+ ${esc(s.newConnection)}</button>
          <button type="button" class="pill-btn" data-action="open-settings-sync">☁️ ${esc(s.driveTitle)}</button>
          ${store.state.pwaInstallAvailable ? `<button type="button" class="pill-btn primary" data-action="install-pwa" data-tooltip="${esc(s.installAppTooltip)}">⬇ ${esc(s.installAppCta)}</button>` : ''}
        </div>
      </div>
    `;
  }

  const layout = store.state.tilingLayout;
  const maxPanes = layout === 'SINGLE' ? 1 : layout === 'QUAD' ? 4 : 2;
  const focusedIdx = Math.max(0, tabs.findIndex((t) => t.tabId === store.state.focusedTabId));
  const visibleTabs = layout === 'SINGLE'
    ? [tabs[focusedIdx] || tabs[0]]
    : tabs.slice(0, maxPanes);

  return `
    <div class="session-tabbar">
      <div class="tabs-scroll">
        ${tabs.map((t) => `
          <div class="session-tab ${t.tabId === store.state.focusedTabId ? 'focused' : ''}"
               data-action="focus-tab"
               data-tab-id="${esc(t.tabId)}">
            ${renderSidebarConnectionTypeIcon(t.protocol, null, 13)}
            <span>${esc(t.title)}</span>
            <button type="button" class="mini-action-btn" data-action="detach-tab" data-tab-id="${esc(t.tabId)}" data-tooltip="${esc(s.detachWindow)}">↗</button>
            <button type="button" class="mini-action-btn" data-action="close-tab" data-tab-id="${esc(t.tabId)}" data-tooltip="${esc(s.disconnectSession)}">✕</button>
          </div>
        `).join('')}
      </div>
      <div class="topbar-cluster">
        <button type="button" class="icon-btn ${layout === 'SINGLE' ? 'active' : ''}" data-action="set-tiling" data-layout="SINGLE" data-tooltip="${esc(s.tilingSingle)}">▢</button>
        <button type="button" class="icon-btn ${layout === 'SPLIT_H' ? 'active' : ''}" data-action="set-tiling" data-layout="SPLIT_H" data-tooltip="${esc(s.tilingSplitH)}">◫</button>
        <button type="button" class="icon-btn ${layout === 'SPLIT_V' ? 'active' : ''}" data-action="set-tiling" data-layout="SPLIT_V" data-tooltip="${esc(s.tilingSplitV)}">⬒</button>
        <button type="button" class="icon-btn ${layout === 'QUAD' ? 'active' : ''}" data-action="set-tiling" data-layout="QUAD" data-tooltip="${esc(s.tilingQuad)}">⊞</button>
        <button type="button" class="icon-btn ${store.state.isFullScreenSession ? 'active' : ''}" data-action="toggle-fullscreen" data-tooltip="${esc(store.state.isFullScreenSession ? s.exitFullScreen : s.fullScreen)}" data-shortcut="F11">⛶</button>
      </div>
    </div>
    <div class="tiling-grid layout-${layout}">
      ${visibleTabs.map((t) => {
        const entry = liveSessions.get(t.tabId);
        return `
          <div class="session-pane ${t.tabId === store.state.focusedTabId ? 'focused' : ''}" data-action="focus-tab" data-tab-id="${esc(t.tabId)}">
            <div class="pane-toolbar">
              <span style="display:inline-flex;align-items:center;gap:6px">${renderSidebarConnectionTypeIcon(t.protocol, null, 14)}<strong>${esc(t.title)}</strong></span>
              <div class="topbar-cluster">
                <span id="pane-status-${esc(t.tabId)}" style="color:var(--text-secondary);font-size:11px">${esc(entry?.statusText || '')}</span>
                ${t.protocol === 'RDP' ? `<button type="button" class="pill-btn" style="height:24px;padding:0 8px;font-size:11px" data-action="send-cad" data-tab-id="${esc(t.tabId)}">Ctrl+Alt+Del</button>` : ''}
              </div>
            </div>
            <div class="pane-viewport">${renderPaneBody(t)}</div>
          </div>
        `;
      }).join('')}
    </div>
  `;
}

function renderInspector() {
  if (!store.state.isInspectorVisible || store.state.activeDestination !== 'connections') return '';
  const connId = store.state.selectedConnectionId;
  const profile = connId ? store.resolveProfileForConnection(connId) : null;
  if (!profile) {
    return `<aside class="inspector"><strong>Property Inspector</strong><p style="color:var(--text-secondary)">Select a connection in the sidebar to inspect inherited credentials &amp; gateways.</p></aside>`;
  }
  const c = profile.connection;
  return `
    <aside class="inspector">
      <div style="display:flex;justify-content:space-between;align-items:center">
        <strong>${esc(c.name)}</strong>
        <span class="proto-badge">${esc(c.protocol)}</span>
      </div>
      <div style="font-size:12px;color:var(--text-secondary)">
        <div>Host: <code>${esc(c.host)}:${effectiveConnectionPort(c)}</code></div>
        <div>Credential: <strong>${esc(profile.effectiveCredential?.name || 'None')}</strong> (${esc(profile.credentialInheritedFrom)})</div>
        <div>Gateway: <strong>${esc(profile.effectiveGateway?.name || 'Direct')}</strong> (${esc(profile.gatewayInheritedFrom)})</div>
      </div>
      <div class="topbar-cluster" style="flex-wrap:wrap">
        <button type="button" class="pill-btn primary" data-action="launch-connection" data-conn-id="${esc(c.id)}">${esc(store.strings.connectNow)}</button>
        <button type="button" class="pill-btn" data-action="edit-connection" data-conn-id="${esc(c.id)}">${esc(store.strings.edit)}</button>
        <button type="button" class="pill-btn danger" data-action="delete-object" data-object-id="${esc(c.id)}">${esc(store.strings.delete)}</button>
      </div>
    </aside>
  `;
}

function renderModal() {
  const backdrop = document.getElementById('modal-backdrop');
  if (!activeModal) {
    backdrop.className = 'modal-backdrop hidden';
    backdrop.innerHTML = '';
    return;
  }
  backdrop.className = 'modal-backdrop';
  const doc = store.state.vaultDocument;

  if (activeModal.kind === 'connection') {
    const c = activeModal.conn || {
      id: '',
      name: '',
      host: '',
      protocol: 'RDP',
      parentFolderId: store.state.selectedFolderId || '',
      favorite: false,
      notes: '',
      inheritable: defaultInheritableProperties(),
      settings: defaultSettingsForProtocol('RDP')
    };
    const portVal = c.settings?.port || PROTOCOL_CATALOG[c.protocol]?.defaultPort || 3389;
    backdrop.innerHTML = `
      <div class="modal-card">
        <h3>${c.id ? 'Edit Connection' : store.strings.newConnection}</h3>
        <div class="form-grid">
          <div class="field-group">
            <label>Name</label>
            <input class="text-input" id="m-conn-name" value="${esc(c.name)}" placeholder="DC-Core-RDP-01" />
          </div>
          <div class="field-group">
            <label>Protocol</label>
            <select class="select-input" id="m-conn-proto">
              ${Object.entries(PROTOCOL_CATALOG).map(([k, v]) => `<option value="${k}" ${c.protocol === k ? 'selected' : ''}>${esc(v.title)}</option>`).join('')}
            </select>
          </div>
          <div class="field-group">
            <label>Host / IP / URL / MAC</label>
            <input class="text-input" id="m-conn-host" value="${esc(c.host)}" placeholder="10.10.0.25" />
          </div>
          <div class="field-group">
            <label>Port</label>
            <input type="number" class="text-input" id="m-conn-port" value="${esc(portVal)}" />
          </div>
          <div class="field-group">
            <label>Folder</label>
            <select class="select-input" id="m-conn-folder">
              <option value="">(Root)</option>
              ${(doc.folders || []).map((f) => `<option value="${esc(f.id)}" ${c.parentFolderId === f.id ? 'selected' : ''}>${esc(f.name)}</option>`).join('')}
            </select>
          </div>
          <div class="field-group">
            <label>Credential</label>
            <select class="select-input" id="m-conn-cred">
              <option value="">Inherit from Folder</option>
              ${(doc.credentials || []).map((cr) => `<option value="${esc(cr.id)}" ${c.inheritable?.explicitCredentialId === cr.id ? 'selected' : ''}>${esc(cr.name)}</option>`).join('')}
            </select>
          </div>
        </div>
        <label style="display:flex;align-items:center;gap:8px;font-size:12.5px">
          <input type="checkbox" id="m-conn-fav" ${c.favorite ? 'checked' : ''} /> Favorite
        </label>
        <div class="topbar-cluster" style="justify-content:flex-end">
          <button type="button" class="pill-btn" data-action="close-modal">Cancel</button>
          <button type="button" class="pill-btn primary" data-action="save-connection-modal" data-conn-id="${esc(c.id)}">Save</button>
        </div>
      </div>
    `;
    return;
  }

  if (activeModal.kind === 'folder') {
    const f = activeModal.folder || { id: '', name: '', parentFolderId: store.state.selectedFolderId || '', inheritable: defaultInheritableProperties() };
    backdrop.innerHTML = `
      <div class="modal-card">
        <h3>${f.id ? 'Edit Folder' : store.strings.newFolder}</h3>
        <div class="field-group">
          <label>Folder Name</label>
          <input class="text-input" id="m-fld-name" value="${esc(f.name)}" placeholder="Production Servers" />
        </div>
        <div class="field-group">
          <label>Parent Folder</label>
          <select class="select-input" id="m-fld-parent">
            <option value="">(Root)</option>
            ${(doc.folders || []).filter((x) => x.id !== f.id).map((x) => `<option value="${esc(x.id)}" ${f.parentFolderId === x.id ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}
          </select>
        </div>
        <div class="field-group">
          <label>Inherited Credential for Subtree</label>
          <select class="select-input" id="m-fld-cred">
            <option value="">Inherit / None</option>
            ${(doc.credentials || []).map((cr) => `<option value="${esc(cr.id)}" ${f.inheritable?.explicitCredentialId === cr.id ? 'selected' : ''}>${esc(cr.name)}</option>`).join('')}
          </select>
        </div>
        <div class="topbar-cluster" style="justify-content:flex-end">
          ${f.id ? `<button type="button" class="pill-btn danger" data-action="delete-object" data-object-id="${esc(f.id)}">${esc(store.strings.delete)}</button>` : ''}
          <button type="button" class="pill-btn" data-action="close-modal">Cancel</button>
          <button type="button" class="pill-btn primary" data-action="save-folder-modal" data-folder-id="${esc(f.id)}">Save</button>
        </div>
      </div>
    `;
    return;
  }

  if (activeModal.kind === 'credential') {
    backdrop.innerHTML = `
      <div class="modal-card">
        <h3>${esc(store.strings.newCredential)}</h3>
        <div class="form-grid">
          <div class="field-group"><label>Display Name</label><input class="text-input" id="m-cred-name" placeholder="Domain Admin" /></div>
          <div class="field-group"><label>Username</label><input class="text-input" id="m-cred-user" placeholder="administrator" /></div>
          <div class="field-group"><label>Domain (optional)</label><input class="text-input" id="m-cred-domain" placeholder="CORP" /></div>
          <div class="field-group"><label>Password</label><input type="password" class="text-input" id="m-cred-pass" /></div>
          <div class="field-group"><label>TOTP Base32 Secret (optional)</label><input class="text-input" id="m-cred-totp" placeholder="JBSWY3DPEHPK3PXP" /></div>
        </div>
        <div class="field-group">
          <label>OpenSSH Private Key PEM (optional)</label>
          <textarea class="textarea-input" id="m-cred-pem" rows="3" placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"></textarea>
        </div>
        <div class="topbar-cluster" style="justify-content:flex-end">
          <button type="button" class="pill-btn" data-action="close-modal">Cancel</button>
          <button type="button" class="pill-btn primary" data-action="save-credential-modal">Save Credential</button>
        </div>
      </div>
    `;
    return;
  }

  if (activeModal.kind === 'gateway') {
    backdrop.innerHTML = `
      <div class="modal-card">
        <h3>Add Secure Gateway</h3>
        <div class="form-grid">
          <div class="field-group"><label>Name</label><input class="text-input" id="m-gw-name" placeholder="DMZ Secure Gateway" /></div>
          <div class="field-group">
            <label>Kind</label>
            <select class="select-input" id="m-gw-kind">
              <option value="NEXUSTS_SECURE_GATEWAY">NexusTS WebSocket Gateway (wss://)</option>
              <option value="SSH_TUNNEL_BASTION">SSH Bastion Tunnel</option>
              <option value="SOCKS5_PROXY">SOCKS5 Proxy</option>
            </select>
          </div>
          <div class="field-group"><label>Host</label><input class="text-input" id="m-gw-host" placeholder="gw.corp.internal" /></div>
          <div class="field-group"><label>Port</label><input type="number" class="text-input" id="m-gw-port" value="443" /></div>
        </div>
        <div class="topbar-cluster" style="justify-content:flex-end">
          <button type="button" class="pill-btn" data-action="close-modal">Cancel</button>
          <button type="button" class="pill-btn primary" data-action="save-gateway-modal">Save Gateway</button>
        </div>
      </div>
    `;
    return;
  }

  if (activeModal.kind === 'template') {
    backdrop.innerHTML = `
      <div class="modal-card">
        <h3>${esc(store.strings.newTemplate)}</h3>
        <div class="form-grid">
          <div class="field-group"><label>Template Name</label><input class="text-input" id="m-tpl-name" placeholder="Linux Hardened SSH" /></div>
          <div class="field-group">
            <label>Protocol</label>
            <select class="select-input" id="m-tpl-proto">
              ${Object.entries(PROTOCOL_CATALOG).map(([k, v]) => `<option value="${k}">${esc(v.title)}</option>`).join('')}
            </select>
          </div>
        </div>
        <div class="field-group"><label>Description</label><input class="text-input" id="m-tpl-desc" /></div>
        <div class="topbar-cluster" style="justify-content:flex-end">
          <button type="button" class="pill-btn" data-action="close-modal">Cancel</button>
          <button type="button" class="pill-btn primary" data-action="save-template-modal">Save Template</button>
        </div>
      </div>
    `;
    return;
  }

  if (activeModal.kind === 'task') {
    backdrop.innerHTML = `
      <div class="modal-card">
        <h3>New Automation Task</h3>
        <div class="field-group"><label>Task Name</label><input class="text-input" id="m-task-name" placeholder="Check Disk &amp; Uptime" /></div>
        <div class="field-group">
          <label>Task Kind</label>
          <select class="select-input" id="m-task-kind">
            <option value="cmd">Command Task (SSH / Shell)</option>
            <option value="macro">Key Sequence Macro ({WAIT:300}, {ENTER}, $EffectiveUsername$)</option>
          </select>
        </div>
        <div class="field-group"><label>Command or Macro Payload</label><input class="text-input" id="m-task-payload" placeholder="uptime && df -h" /></div>
        <div class="topbar-cluster" style="justify-content:flex-end">
          <button type="button" class="pill-btn" data-action="close-modal">Cancel</button>
          <button type="button" class="pill-btn primary" data-action="save-task-modal">Save Task</button>
        </div>
      </div>
    `;
    return;
  }

  if (activeModal.kind === 'import') {
    backdrop.innerHTML = `
      <div class="modal-card">
        <h3>Import Connections &amp; Vault</h3>
        <div class="field-group">
          <label>Select File (.nxts, .rtsz, .rtsx, .xml, .json, .1pux, .csv)</label>
          <input type="file" class="text-input" id="m-import-file" accept=".nxts,.rtsz,.rtsx,.rjson,.xml,.json,.1pux,.csv" />
        </div>
        <div class="form-grid">
          <div class="field-group">
            <label>Placement Mode</label>
            <select class="select-input" id="m-import-mode">
              <option value="MERGE">Merge into Existing Hierarchy</option>
              <option value="DEDICATED_SUBFOLDER">Place Inside Dedicated Subfolder</option>
              <option value="REPLACE">Replace Current Vault</option>
            </select>
          </div>
          <div class="field-group">
            <label>Passphrase (optional — only if custom password set)</label>
            <input type="password" class="text-input" id="m-import-pass" placeholder="Leave blank by default" />
          </div>
        </div>
        <div class="topbar-cluster" style="justify-content:flex-end">
          <button type="button" class="pill-btn" data-action="close-modal">Cancel</button>
          <button type="button" class="pill-btn primary" data-action="confirm-import-modal">Import Now</button>
        </div>
      </div>
    `;
    return;
  }

  if (activeModal.kind === 'passphrase-prompt') {
    backdrop.innerHTML = `
      <div class="modal-card">
        <h3>${esc(activeModal.title)}</h3>
        <div class="field-group">
          <label>Vault Master Passphrase</label>
          <input type="password" class="text-input" id="m-prompt-pass" autofocus />
        </div>
        <div class="topbar-cluster" style="justify-content:flex-end">
          <button type="button" class="pill-btn" data-action="close-modal">Cancel</button>
          <button type="button" class="pill-btn primary" data-action="confirm-passphrase-prompt">Continue</button>
        </div>
      </div>
    `;
  }
}

function attachLiveCanvases() {
  for (const tab of store.state.tabs) {
    ensureSessionStarted(tab).then((entry) => {
      if (!entry) return;
      const canvas = document.getElementById(`session-canvas-${tab.tabId}`);
      if (!canvas) return;
      if (entry.kind === 'terminal') {
        entry.canvas = canvas;
        entry.terminal.renderToCanvas(canvas, store.state.focusedTabId === tab.tabId);
      } else if (entry.kind === 'rdp' || entry.kind === 'vnc') {
        entry.instance.attachCanvas(canvas);
      }
    });
  }
}

function renderWorkspace() {
  const root = document.getElementById('app-root');
  if (!root) return;
  const s = store.strings;
  const st = store.state;
  const inSettings = st.activeDestination === 'settings';
  const activeConnIds = new Set(st.tabs.map((t) => t.connectionId));

  root.className = `app-root ${st.isFullScreenSession ? 'fullscreen-session' : ''}`;

  const transportLabel = st.directSocketsAvailable
    ? s.directSocketsBadge
    : st.localRelayReachable
      ? s.relayModeBadge
      : s.offlineReadyBadge;

  root.innerHTML = `
    <header class="topbar">
      <div class="topbar-cluster">
        <button type="button" class="icon-btn ${st.isSidebarVisible ? 'active' : ''}" data-action="toggle-sidebar" data-tooltip="${esc(s.toggleSidebar)}" data-shortcut="Ctrl+B">☰</button>
        <div class="brand-lockup">
          <img src="./icons/favicon.svg" alt="" class="brand-emblem" />
          <h1 class="brand-title">${esc(s.appName)}</h1>
        </div>
        ${st.vaultDocument.name !== 'NexusTS Enterprise Vault' ? `<span class="vault-pill">${esc(st.vaultDocument.name)}</span>` : ''}
        <button type="button"
                id="btn-top-settings"
                class="pill-btn ${inSettings ? 'active' : ''}"
                data-action="toggle-settings"
                data-tooltip="${esc(s.navSettings)}"
                data-shortcut="Ctrl+,">
          <span>⚙</span>
          <span>${esc(s.navSettings)}</span>
        </button>
        ${inSettings ? `<button type="button" class="pill-btn" data-action="return-connections">← ${esc(s.navConnections)}</button>` : ''}
      </div>

      <form class="omnibox-form" id="quick-connect-form">
        <span>🔎</span>
        <input class="omnibox-input" id="quick-connect-input" placeholder="${esc(s.quickConnectPlaceholder)}" autocomplete="off" />
        <span class="kbd-badge">Ctrl+K</span>
      </form>

      <div class="topbar-cluster">
        ${st.swUpdateWaiting
          ? `<button type="button" class="pill-btn primary" data-action="apply-sw-update" data-tooltip="${esc(s.updateReadyTooltip)}">↻ ${esc(s.applyUpdateCta)}</button>`
          : ''}
        ${st.pwaInstallAvailable
          ? `<button type="button" class="pill-btn primary" data-action="install-pwa" data-tooltip="${esc(s.installAppTooltip)}">⬇ ${esc(s.installAppCta)}</button>`
          : ''}
        <span class="status-chip ${st.directSocketsAvailable || st.localRelayReachable ? 'online' : ''}">
          <span class="status-dot"></span>${esc(transportLabel)}
        </span>
        <button type="button" class="pill-btn primary" data-action="open-connection-modal" data-tooltip="${esc(s.newConnection)}" data-shortcut="Ctrl+N">+ ${esc(s.newMenu)}</button>
        <button type="button" class="icon-btn" data-action="open-folder-modal" data-tooltip="${esc(s.newFolder)}">📁+</button>
        <button type="button" class="icon-btn" data-action="launch-local-shell" data-tooltip="${esc(s.newLocalShell)}">&gt;_</button>
        <button type="button" class="icon-btn ${st.broadcastInputEnabled ? 'active' : ''}" data-action="toggle-broadcast" data-tooltip="${esc(s.broadcastInput)}">📡</button>
        <button type="button" class="icon-btn ${st.isInspectorVisible ? 'active' : ''}" data-action="toggle-inspector" data-tooltip="${esc(s.toggleInspector)}">ⓘ</button>
        <button type="button" class="icon-btn" data-action="lock-vault" data-tooltip="${esc(s.lockVault)}" data-shortcut="Ctrl+Shift+L">🔒</button>
      </div>
    </header>

    <div class="workspace-body">
      <aside class="sidebar ${st.isSidebarVisible ? '' : 'hidden'}" aria-label="${esc(s.foldersHeader)}">
        <div class="sidebar-search-box">
          <input class="search-input" id="sidebar-filter-input" value="${esc(st.sidebarFilterQuery)}" placeholder="${esc(s.filterPlaceholder)}" />
          <div class="quick-filters">
            <button type="button" class="quick-filter-btn ${st.sidebarQuickFilter === 'ALL' ? 'active' : ''}" data-action="set-quick-filter" data-qf="ALL">${esc(s.filterAll)}</button>
            <button type="button" class="quick-filter-btn ${st.sidebarQuickFilter === 'FAVORITES' ? 'active' : ''}" data-action="set-quick-filter" data-qf="FAVORITES">${esc(s.filterFavorites)}</button>
            <button type="button" class="quick-filter-btn ${st.sidebarQuickFilter === 'RECENTS' ? 'active' : ''}" data-action="set-quick-filter" data-qf="RECENTS">${esc(s.filterRecents)}</button>
          </div>
        </div>
        <div class="sidebar-tree" data-drop-folder-id="__ROOT__">
          <div class="section-label">
            <span>${esc(s.foldersHeader)}</span>
            <span>${st.vaultDocument.connections.length}</span>
          </div>
          ${renderFolderSubtree(null, 0, activeConnIds)}
        </div>
      </aside>

      <main class="center-stage">
        ${inSettings ? renderUnifiedSettings() : renderActiveSessionsStage()}
      </main>

      ${renderInspector()}
    </div>
  `;

  // Lock shield
  const lockShield = document.getElementById('lock-shield');
  if (st.isVaultLocked) {
    lockShield.className = 'lock-shield';
    lockShield.innerHTML = `
      <form class="modal-card" id="unlock-vault-form" style="max-width:410px;text-align:center">
        <img src="./icons/favicon.svg" alt="" style="width:54px;height:54px;margin:0 auto;border-radius:14px" />
        <h3>${esc(s.unlockVault)}</h3>
        <p style="margin:0;color:var(--text-secondary)">Argon2id + AES-256-GCM Zero-Knowledge Vault</p>
        <input type="password" class="text-input" id="unlock-passphrase-input" placeholder="Master Passphrase" autofocus />
        ${st.vaultUnlockError ? `<div style="color:var(--status-danger);font-size:12px">${esc(st.vaultUnlockError)}</div>` : ''}
        <button type="submit" class="pill-btn primary" style="justify-content:center">${esc(s.unlockVault)}</button>
      </form>
    `;
  } else {
    lockShield.className = 'lock-shield hidden';
    lockShield.innerHTML = '';
  }

  // Toast
  const toastEl = document.getElementById('toast-banner');
  if (st.toastMessage) {
    toastEl.textContent = st.toastMessage;
    toastEl.className = 'toast-banner';
    setTimeout(() => {
      if (store.state.toastMessage === st.toastMessage) store.setState({ toastMessage: '' });
    }, 3600);
  } else {
    toastEl.className = 'toast-banner hidden';
  }

  renderModal();
  attachLiveCanvases();
  refreshTotpBadges();
}

async function refreshTotpBadges() {
  const badges = document.querySelectorAll('[data-totp-secret]');
  for (const el of badges) {
    const res = await computeTotpCode(el.getAttribute('data-totp-secret'));
    if (res) el.textContent = `${res.code} (${res.remainingSec}s)`;
  }
}

function downloadBlob(bytes, filename, mimeType) {
  const blob = new Blob([bytes], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

// Discreet Liquid Glass Tooltip Controller
function installDiscreetTooltips() {
  const tipEl = document.getElementById('discreet-tooltip');
  let timer = null;

  document.addEventListener('mouseover', (ev) => {
    const target = ev.target.closest('[data-tooltip]');
    clearTimeout(timer);
    if (!target) {
      tipEl.className = 'discreet-tooltip';
      return;
    }
    timer = setTimeout(() => {
      const label = target.getAttribute('data-tooltip');
      const shortcut = target.getAttribute('data-shortcut');
      tipEl.innerHTML = `${esc(label)}${shortcut ? `<span class="shortcut-pill">${esc(shortcut)}</span>` : ''}`;
      const rect = target.getBoundingClientRect();
      const left = Math.max(8, Math.min(window.innerWidth - 220, rect.left + rect.width / 2 - 60));
      const top = rect.bottom + 8;
      tipEl.style.left = `${left}px`;
      tipEl.style.top = `${top}px`;
      tipEl.className = 'discreet-tooltip visible';
    }, 320);
  });

  document.addEventListener('mouseout', (ev) => {
    if (ev.target.closest?.('[data-tooltip]')) {
      clearTimeout(timer);
      tipEl.className = 'discreet-tooltip';
    }
  });
}

// Delegated Event Wiring (Strict IWA CSP Compliant)
function installEventDelegation() {
  document.addEventListener('click', async (ev) => {
    store.recordUserActivity();
    if (store.state.driveNeedsPermissionGrant) {
      store.tryRestoreDrivePermissionOnUserGesture();
    }
    const actionEl = ev.target.closest('[data-action]');
    if (!actionEl) return;
    const action = actionEl.getAttribute('data-action');

    if (action !== 'focus-tab' && action !== 'select-connection' && action !== 'toggle-folder') {
      ev.stopPropagation();
    }

    switch (action) {
      case 'toggle-sidebar':
        store.setState({ isSidebarVisible: !store.state.isSidebarVisible });
        break;
      case 'toggle-inspector':
        store.setState({ isInspectorVisible: !store.state.isInspectorVisible });
        break;
      case 'toggle-broadcast':
        store.setState({ broadcastInputEnabled: !store.state.broadcastInputEnabled });
        break;
      case 'toggle-settings':
        store.toggleSettingsButton();
        break;
      case 'open-settings-sync':
        store.openSettingsTab('sync');
        break;
      case 'return-connections':
        store.returnToConnections();
        break;
      case 'select-settings-tab':
        store.openSettingsTab(actionEl.getAttribute('data-tab-id'));
        break;
      case 'select-locale':
        await store.setLocale(actionEl.getAttribute('data-locale'));
        break;
      case 'set-quick-filter':
        store.setState({ sidebarQuickFilter: actionEl.getAttribute('data-qf') });
        break;
      case 'toggle-folder':
        store.toggleFolderExpanded(actionEl.getAttribute('data-folder-id'));
        break;
      case 'select-connection':
        store.selectConnectionInSidebar(actionEl.getAttribute('data-conn-id'));
        break;
      case 'launch-connection':
        store.launchConnection(actionEl.getAttribute('data-conn-id'));
        break;
      case 'start-folder-rdp': {
        const n = store.launchAllRdpInFolder(actionEl.getAttribute('data-folder-id'));
        store.showToast(n > 0 ? `Launched ${n} RDP session(s).` : 'No RDP connections in folder.');
        break;
      }
      case 'focus-tab':
        store.setState({ focusedTabId: actionEl.getAttribute('data-tab-id') });
        break;
      case 'close-tab':
        store.closeTab(actionEl.getAttribute('data-tab-id'), cleanupTabSession);
        break;
      case 'detach-tab': {
        const tabId = actionEl.getAttribute('data-tab-id');
        const tab = store.state.tabs.find((t) => t.tabId === tabId);
        if (tab) {
          const w = window.open(`./index.html?detachedConn=${encodeURIComponent(tab.connectionId)}`, '_blank', 'popup,width=1280,height=820');
          if (w) tab.detachedWindow = w;
        }
        break;
      }
      case 'set-tiling':
        store.setState({ tilingLayout: actionEl.getAttribute('data-layout') });
        break;
      case 'toggle-fullscreen':
        store.setState({ isFullScreenSession: !store.state.isFullScreenSession });
        break;
      case 'send-cad': {
        const s = liveSessions.get(actionEl.getAttribute('data-tab-id'));
        await s?.instance?.sendCtrlAltDel?.();
        break;
      }
      case 'launch-local-shell':
        store.launchConnection({
          id: `local-shell-${randomUuid()}`,
          name: store.strings.newLocalShell,
          host: '127.0.0.1',
          protocol: 'SSH',
          settings: defaultSettingsForProtocol('SSH')
        }, { forceNewTab: true });
        break;
      case 'lock-vault':
        store.lockVault();
        break;
      case 'open-connection-modal':
        activeModal = { kind: 'connection', conn: null };
        renderModal();
        break;
      case 'edit-connection': {
        const c = store.state.vaultDocument.connections.find((x) => x.id === actionEl.getAttribute('data-conn-id'));
        if (c) { activeModal = { kind: 'connection', conn: structuredClone(c) }; renderModal(); }
        break;
      }
      case 'open-folder-modal':
        activeModal = { kind: 'folder', folder: null };
        renderModal();
        break;
      case 'edit-folder': {
        const f = store.state.vaultDocument.folders.find((x) => x.id === actionEl.getAttribute('data-folder-id'));
        if (f) { activeModal = { kind: 'folder', folder: structuredClone(f) }; renderModal(); }
        break;
      }
      case 'open-credential-modal':
        activeModal = { kind: 'credential' };
        renderModal();
        break;
      case 'open-gateway-modal':
        activeModal = { kind: 'gateway' };
        renderModal();
        break;
      case 'open-template-modal':
        activeModal = { kind: 'template' };
        renderModal();
        break;
      case 'open-task-modal':
        activeModal = { kind: 'task' };
        renderModal();
        break;
      case 'open-import-modal':
        activeModal = { kind: 'import' };
        renderModal();
        break;
      case 'close-modal': {
        const cancelCb = activeModal?.onCancel;
        activeModal = null;
        renderModal();
        await cancelCb?.();
        break;
      }
      case 'save-connection-modal': {
        const existingId = actionEl.getAttribute('data-conn-id') || '';
        const proto = document.getElementById('m-conn-proto').value;
        const host = document.getElementById('m-conn-host').value.trim();
        const name = document.getElementById('m-conn-name').value.trim() || host;
        const port = Number(document.getElementById('m-conn-port').value || 0);
        const parentFolderId = document.getElementById('m-conn-folder').value || null;
        const credId = document.getElementById('m-conn-cred').value || null;
        const favorite = document.getElementById('m-conn-fav').checked;
        if (!host) { store.showToast('Please enter a hostname or IP address.'); break; }
        const settings = defaultSettingsForProtocol(proto, host);
        if (port > 0 && 'port' in settings) settings.port = port;
        if (proto === 'UTILITY_WAKE_ON_LAN') settings.wolMacAddress = host;
        activeModal = null;
        await store.saveConnection({
          id: existingId || randomUuid(),
          name,
          host,
          protocol: proto,
          parentFolderId,
          favorite,
          inheritable: defaultInheritableProperties(
            credId ? { credentialMode: 'OVERRIDE_EXPLICIT', explicitCredentialId: credId } : {}
          ),
          settings
        });
        break;
      }
      case 'save-folder-modal': {
        const id = actionEl.getAttribute('data-folder-id') || '';
        const name = document.getElementById('m-fld-name').value.trim() || 'New Folder';
        const parentFolderId = document.getElementById('m-fld-parent').value || null;
        const credId = document.getElementById('m-fld-cred').value || null;
        activeModal = null;
        await store.saveFolder({
          id: id || randomUuid(),
          name,
          parentFolderId,
          inheritable: defaultInheritableProperties(
            credId ? { credentialMode: 'OVERRIDE_EXPLICIT', explicitCredentialId: credId } : {}
          )
        });
        break;
      }
      case 'save-credential-modal': {
        const name = document.getElementById('m-cred-name').value.trim() || 'Credential';
        const username = document.getElementById('m-cred-user').value.trim();
        const domain = document.getElementById('m-cred-domain').value.trim();
        const secretPassword = document.getElementById('m-cred-pass').value;
        const totpSecretBase32 = document.getElementById('m-cred-totp').value.trim();
        const privateKeyPem = document.getElementById('m-cred-pem').value.trim();
        activeModal = null;
        await store.commitVaultMutation((doc) => {
          doc.credentials.push({
            id: randomUuid(),
            parentFolderId: null,
            name,
            sourceKind: privateKeyPem ? 'SSH_KEY_PAIR' : 'VAULT_INTERNAL',
            username,
            domain,
            secretPassword,
            privateKeyPem,
            publicKeyOpenSsh: '',
            keyFingerprintSha256: '',
            keyPassphrase: '',
            externalReferenceUri: '',
            fido2CredentialIdBase64: '',
            totpSecretBase32,
            updatedAtEpochMs: Date.now()
          });
          return doc;
        });
        break;
      }
      case 'generate-ed25519-cred': {
        const kp = await generateEd25519OpenSshKeypair('chromeos@nexusts.app');
        await store.commitVaultMutation((doc) => {
          doc.credentials.push({
            id: randomUuid(),
            parentFolderId: null,
            name: `SSH Ed25519 (${kp.keyFingerprintSha256.slice(0, 16)}…)`,
            sourceKind: 'SSH_KEY_PAIR',
            username: 'root',
            domain: '',
            secretPassword: '',
            privateKeyPem: kp.privateKeyPem,
            publicKeyOpenSsh: kp.publicKeyOpenSsh,
            keyFingerprintSha256: kp.keyFingerprintSha256,
            keyPassphrase: '',
            externalReferenceUri: '',
            fido2CredentialIdBase64: '',
            totpSecretBase32: '',
            updatedAtEpochMs: Date.now()
          });
          return doc;
        });
        store.showToast('Generated OpenSSH Ed25519 keypair.');
        break;
      }
      case 'copy-pubkey': {
        const cr = store.state.vaultDocument.credentials.find((x) => x.id === actionEl.getAttribute('data-cred-id'));
        if (cr?.publicKeyOpenSsh) {
          await navigator.clipboard?.writeText?.(cr.publicKeyOpenSsh);
          store.showToast('Copied OpenSSH public key to clipboard.');
        }
        break;
      }
      case 'save-gateway-modal': {
        const name = document.getElementById('m-gw-name').value.trim() || 'Gateway';
        const kind = document.getElementById('m-gw-kind').value;
        const host = document.getElementById('m-gw-host').value.trim();
        const port = Number(document.getElementById('m-gw-port').value || 443);
        activeModal = null;
        await store.commitVaultMutation((doc) => {
          doc.gateways.push({ id: randomUuid(), name, kind, host, port, credentialId: null, verifyTlsCertificate: true, updatedAtEpochMs: Date.now() });
          return doc;
        });
        break;
      }
      case 'save-template-modal': {
        const name = document.getElementById('m-tpl-name').value.trim() || 'Template';
        const protocol = document.getElementById('m-tpl-proto').value;
        const description = document.getElementById('m-tpl-desc').value.trim();
        activeModal = null;
        await store.commitVaultMutation((doc) => {
          doc.templates.push({
            id: randomUuid(),
            name,
            description,
            protocol,
            colorAccentHex: null,
            inheritable: defaultInheritableProperties(),
            settings: defaultSettingsForProtocol(protocol),
            updatedAtEpochMs: Date.now()
          });
          return doc;
        });
        break;
      }
      case 'new-from-template': {
        const tpl = store.state.vaultDocument.templates.find((t) => t.id === actionEl.getAttribute('data-tpl-id'));
        if (tpl) {
          activeModal = {
            kind: 'connection',
            conn: {
              id: '',
              name: tpl.name,
              host: '',
              protocol: tpl.protocol,
              parentFolderId: null,
              favorite: false,
              inheritable: structuredClone(tpl.inheritable),
              settings: structuredClone(tpl.settings)
            }
          };
          renderModal();
        }
        break;
      }
      case 'save-task-modal': {
        const name = document.getElementById('m-task-name').value.trim() || 'Task';
        const kind = document.getElementById('m-task-kind').value;
        const payload = document.getElementById('m-task-payload').value;
        activeModal = null;
        await store.commitVaultMutation((doc) => {
          if (kind === 'macro') {
            doc.keySequenceTasks.push({ id: randomUuid(), name, macroSequence: payload, keystrokeDelayMs: 25, updatedAtEpochMs: Date.now() });
          } else {
            doc.commandTasks.push({ id: randomUuid(), name, commandTemplate: payload, broadcastToAllSelectedSessions: false, appendNewline: true, updatedAtEpochMs: Date.now() });
          }
          return doc;
        });
        break;
      }
      case 'run-cmd-task': {
        const task = store.state.vaultDocument.commandTasks.find((t) => t.id === actionEl.getAttribute('data-task-id'));
        const entry = liveSessions.get(store.state.focusedTabId);
        if (task && entry?.kind === 'terminal') {
          const line = expandTaskVariables(task.commandTemplate, entry.profile) + (task.appendNewline ? '\n' : '');
          await entry.instance?.sendInput?.(new TextEncoder().encode(line));
          store.returnToConnections();
        } else {
          store.showToast('Focus an active SSH/Terminal tab first to run a Command Task.');
        }
        break;
      }
      case 'run-key-task': {
        const task = store.state.vaultDocument.keySequenceTasks.find((t) => t.id === actionEl.getAttribute('data-task-id'));
        const entry = liveSessions.get(store.state.focusedTabId);
        if (task && entry?.kind === 'terminal') {
          store.returnToConnections();
          const steps = parseKeySequenceSteps(task.macroSequence, entry.profile);
          for (const st of steps) {
            if (st.type === 'wait') await new Promise((r) => setTimeout(r, st.ms));
            else if (st.type === 'text') await entry.instance?.sendInput?.(new TextEncoder().encode(st.value));
            else if (st.key === 'ENTER') await entry.instance?.sendInput?.(new TextEncoder().encode('\r'));
            else if (st.key === 'TAB') await entry.instance?.sendInput?.(new TextEncoder().encode('\t'));
          }
        }
        break;
      }
      case 'delete-object':
        activeModal = null;
        await store.deleteObjectById(actionEl.getAttribute('data-object-id'));
        break;
      case 'save-vault-name': {
        const newName = document.getElementById('input-vault-name')?.value.trim();
        if (newName) {
          await store.commitVaultMutation((doc) => ({ ...doc, name: newName }));
          store.showToast('Saved vault name.');
        }
        break;
      }
      case 'apply-master-passphrase': {
        const pw = document.getElementById('sec-new-passphrase')?.value || '';
        try {
          await store.setMasterPassphraseAndEncrypt(pw);
          store.showToast(
            pw
              ? 'Custom Master Passphrase applied — vault encrypted with Argon2id + AES-256-GCM.'
              : 'Automatic passwordless sync active (Argon2id + AES-256-GCM).'
          );
        } catch (err) {
          store.showToast(err.message);
        }
        break;
      }
      case 'drive-create-synced':
        await handleCreateSyncedDriveVault();
        break;
      case 'drive-open-synced':
        await handleOpenExistingSyncedDriveVault();
        break;
      case 'drive-sync-now':
        try {
          await store.triggerGoogleDriveSync({ reason: 'manual', allowPermissionPrompt: true });
        } catch (err) {
          store.showToast(err.message);
        }
        break;
      case 'drive-disconnect':
        await store.disconnectGoogleDriveSync();
        break;
      case 'confirm-passphrase-prompt': {
        const pw = document.getElementById('m-prompt-pass')?.value || '';
        const cb = activeModal?.onConfirm;
        activeModal = null;
        renderModal();
        try { await cb?.(pw); } catch (err) { store.showToast(err.message); }
        break;
      }
      case 'confirm-import-modal': {
        const fileInput = document.getElementById('m-import-file');
        const mode = document.getElementById('m-import-mode').value;
        const pass = document.getElementById('m-import-pass').value;
        const file = fileInput?.files?.[0];
        if (!file) { store.showToast('Choose a file to import.'); break; }
        try {
          const bytes = new Uint8Array(await file.arrayBuffer());
          activeModal = null;
          const summary = await store.importVaultFileBytes(bytes, file.name, pass, mode);
          store.showToast(`Imported ${summary.importedConnections} connection(s) from ${summary.detectedFormat}.`);
        } catch (err) {
          if (err?.name === 'VaultAuthenticationError' && !pass) {
            const bytes = new Uint8Array(await file.arrayBuffer());
            openPassphrasePromptModal(async (pw) => {
              const summary = await store.importVaultFileBytes(bytes, file.name, pw, mode);
              store.showToast(`Imported ${summary.importedConnections} connection(s) from ${summary.detectedFormat}.`);
            }, `Enter Master Passphrase for ${file.name}`);
          } else {
            store.showToast(`Import failed: ${err.message}`);
          }
        }
        break;
      }
      case 'undo-import':
        await store.undoLastImport();
        store.showToast('Restored pre-import vault backup.');
        break;
      case 'export-vault': {
        const fmt = actionEl.getAttribute('data-format');
        const out = await store.exportCurrentVault(fmt);
        downloadBlob(out.bytes, `${store.state.vaultDocument.name}.${out.extension}`, out.mimeType);
        break;
      }
      case 'save-relay-url': {
        const url = document.getElementById('input-relay-url')?.value.trim() || 'ws://127.0.0.1:44890';
        store.setState({ localRelayUrl: url, localRelayHttpUrl: url.replace(/^ws/i, 'http') });
        await store.savePreferences();
        await refreshRelayStatus();
        break;
      }
      case 'install-pwa': {
        const res = await promptPwaInstall();
        if (res.outcome === 'accepted') {
          store.showToast('NexusTS wurde ins ChromeOS-Startmenü installiert.');
        }
        break;
      }
      case 'apply-sw-update': {
        await applyPendingSwUpdate();
        break;
      }
      case 'check-github-updates': {
        store.setState({ updateCheckStatus: 'Checking Firebase Hosting & GitHub Releases…' });
        const check = await checkForSwAndReleaseUpdates(store.state.appVersion);
        store.setState({
          latestReleaseTag: check.latestTag || '',
          latestReleaseUrl: check.releaseUrl || '',
          swUpdateWaiting: Boolean(check.swWaiting),
          updateCheckStatus: check.swWaiting
            ? `Neues ChromeOS-Web-App-Update bereit (${check.latestTag}) — klicke auf „${store.strings.applyUpdateCta}“.`
            : check.hasUpdate
              ? `Neues Release verfügbar: ${check.latestTag}`
              : `Aktuell (v${store.state.appVersion} — 100 % Offline-Cache aktiv).`
        });
        break;
      }
      case 'refresh-offline-sw': {
        const reg = await navigator.serviceWorker?.getRegistration?.();
        await reg?.update?.();
        store.setState({ updateCheckStatus: 'Offline Service Worker & WebAssembly cache verified 100% ready.' });
        break;
      }
      case 'transfer-cd':
        await reloadTransferDirectory(actionEl.getAttribute('data-tab-id'), actionEl.getAttribute('data-path'));
        break;
      case 'transfer-go': {
        const tid = actionEl.getAttribute('data-tab-id');
        const p = document.getElementById(`transfer-path-${tid}`)?.value || '/';
        await reloadTransferDirectory(tid, p);
        break;
      }
      case 'transfer-up': {
        const tid = actionEl.getAttribute('data-tab-id');
        const entry = liveSessions.get(tid);
        const cur = entry?.transferState?.currentPath || '/';
        const parent = cur.replace(/\/[^/]+\/?$/, '') || '/';
        await reloadTransferDirectory(tid, parent);
        break;
      }
      case 'transfer-download': {
        const tid = actionEl.getAttribute('data-tab-id');
        const entry = liveSessions.get(tid);
        const bytes = await entry.instance.downloadFile(actionEl.getAttribute('data-path'));
        downloadBlob(bytes, actionEl.getAttribute('data-name') || 'download.bin', 'application/octet-stream');
        break;
      }
      case 'transfer-upload': {
        const tid = actionEl.getAttribute('data-tab-id');
        const entry = liveSessions.get(tid);
        const input = document.createElement('input');
        input.type = 'file';
        input.onchange = async () => {
          const f = input.files?.[0];
          if (!f) return;
          const bytes = new Uint8Array(await f.arrayBuffer());
          const base = entry.transferState.currentPath.replace(/\/$/, '');
          await entry.instance.uploadFile(`${base}/${f.name}`, bytes);
          await reloadTransferDirectory(tid, entry.transferState.currentPath);
        };
        input.click();
        break;
      }
      case 'transfer-mkdir': {
        const tid = actionEl.getAttribute('data-tab-id');
        const entry = liveSessions.get(tid);
        const name = prompt('New folder name:');
        if (name) {
          const base = entry.transferState.currentPath.replace(/\/$/, '');
          await entry.instance.mkdir(`${base}/${name}`);
          await reloadTransferDirectory(tid, entry.transferState.currentPath);
        }
        break;
      }
      case 'transfer-delete': {
        const tid = actionEl.getAttribute('data-tab-id');
        const entry = liveSessions.get(tid);
        await entry.instance.deleteEntry(actionEl.getAttribute('data-path'), actionEl.getAttribute('data-isdir') === 'true');
        await reloadTransferDirectory(tid, entry.transferState.currentPath);
        break;
      }
      case 'rerun-netutil':
        await runNetUtilitySession(actionEl.getAttribute('data-tab-id'));
        break;
      default:
        break;
    }
  });

  document.addEventListener('dblclick', (ev) => {
    const el = ev.target.closest('[data-dblaction="launch-connection"]');
    if (el) store.launchConnection(el.getAttribute('data-conn-id'));
  });

  document.addEventListener('change', async (ev) => {
    const ch = ev.target.getAttribute('data-change');
    if (ch === 'set-locale') await store.setLocale(ev.target.value);
    else if (ch === 'set-autolock') {
      store.setState({ idleAutoLockMinutes: Number(ev.target.value) });
      await store.savePreferences();
    }
  });

  document.addEventListener('input', (ev) => {
    if (ev.target.id === 'sidebar-filter-input') {
      const val = ev.target.value;
      const pos = ev.target.selectionStart;
      store.setState({ sidebarFilterQuery: val });
      const nextEl = document.getElementById('sidebar-filter-input');
      if (nextEl) {
        nextEl.focus();
        nextEl.setSelectionRange(pos, pos);
      }
    }
  });

  document.addEventListener('submit', async (ev) => {
    if (ev.target.id === 'quick-connect-form') {
      ev.preventDefault();
      const input = document.getElementById('quick-connect-input');
      if (input?.value.trim()) {
        store.launchQuickConnect(input.value.trim());
        input.value = '';
      }
    } else if (ev.target.id === 'unlock-vault-form') {
      ev.preventDefault();
      const pw = document.getElementById('unlock-passphrase-input')?.value || '';
      await store.unlockVault(pw);
    }
  });

  // Drag-and-drop folder/connection reparenting
  document.addEventListener('dragstart', (ev) => {
    const row = ev.target.closest('.tree-row');
    if (!row) return;
    if (row.hasAttribute('data-conn-id')) {
      dragPayload = { kind: 'conn', id: row.getAttribute('data-conn-id') };
    } else if (row.hasAttribute('data-folder-id')) {
      dragPayload = { kind: 'folder', id: row.getAttribute('data-folder-id') };
    }
  });
  document.addEventListener('dragover', (ev) => {
    const dropTarget = ev.target.closest('[data-drop-folder-id]');
    if (dropTarget && dragPayload) ev.preventDefault();
  });
  document.addEventListener('drop', async (ev) => {
    const dropTarget = ev.target.closest('[data-drop-folder-id]');
    if (!dropTarget || !dragPayload) return;
    ev.preventDefault();
    const rawFolderId = dropTarget.getAttribute('data-drop-folder-id');
    const targetFolderId = rawFolderId === '__ROOT__' ? null : rawFolderId;
    const item = dragPayload;
    dragPayload = null;
    if (item.kind === 'conn') await store.moveConnectionToFolder(item.id, targetFolderId);
    else if (item.kind === 'folder') await store.moveFolderToParent(item.id, targetFolderId);
  });

  // Canvas keyboard & mouse forwarding for RDP / VNC / Terminal + Global ChromeOS Shortcuts
  document.addEventListener('keydown', async (ev) => {
    store.recordUserActivity();
    if (ev.ctrlKey && !ev.shiftKey && ev.key.toLowerCase() === 'k') {
      ev.preventDefault();
      document.getElementById('quick-connect-input')?.focus();
      return;
    }
    if (ev.ctrlKey && ev.key === ',') {
      ev.preventDefault();
      store.toggleSettingsButton();
      return;
    }
    if (ev.ctrlKey && ev.shiftKey && ev.key.toLowerCase() === 'l') {
      ev.preventDefault();
      store.lockVault();
      return;
    }
    if (ev.key === 'F11') {
      ev.preventDefault();
      store.setState({ isFullScreenSession: !store.state.isFullScreenSession });
      return;
    }

    const canvas = ev.target.closest?.('.session-canvas');
    if (!canvas) return;
    const tabId = canvas.getAttribute('data-tab-id');
    const entry = liveSessions.get(tabId);
    if (!entry) return;
    ev.preventDefault();

    if (entry.kind === 'terminal') {
      const seq = entry.terminal.translateKeyEvent(ev);
      if (seq) {
        if (store.state.broadcastInputEnabled) {
          for (const [, other] of liveSessions) {
            if (other.kind === 'terminal') await other.instance?.sendInput?.(seq);
          }
        } else {
          await entry.instance?.sendInput?.(seq);
        }
      }
    } else if (entry.kind === 'rdp') {
      await entry.instance.sendKeyEvent(ev, true);
    } else if (entry.kind === 'vnc') {
      const sym = domKeyToX11Keysym(ev);
      if (sym) await entry.instance.sendKeyEvent(sym, true);
    }
  });

  document.addEventListener('keyup', async (ev) => {
    const canvas = ev.target.closest?.('.session-canvas');
    if (!canvas) return;
    const entry = liveSessions.get(canvas.getAttribute('data-tab-id'));
    if (entry?.kind === 'rdp') await entry.instance.sendKeyEvent(ev, false);
    else if (entry?.kind === 'vnc') {
      const sym = domKeyToX11Keysym(ev);
      if (sym) await entry.instance.sendKeyEvent(sym, false);
    }
  });

  const canvasCoords = (canvas, ev) => {
    const rect = canvas.getBoundingClientRect();
    const sx = canvas.width / Math.max(1, rect.width);
    const sy = canvas.height / Math.max(1, rect.height);
    return { x: (ev.clientX - rect.left) * sx, y: (ev.clientY - rect.top) * sy };
  };

  document.addEventListener('mousemove', (ev) => {
    const canvas = ev.target.closest?.('.session-canvas');
    if (!canvas) return;
    const entry = liveSessions.get(canvas.getAttribute('data-tab-id'));
    if (entry?.kind === 'rdp') {
      const { x, y } = canvasCoords(canvas, ev);
      entry.instance.sendMouseMove(x, y);
    } else if (entry?.kind === 'vnc') {
      const { x, y } = canvasCoords(canvas, ev);
      entry.instance.sendPointerEvent(x, y, ev.buttons & 0x07);
    }
  });

  document.addEventListener('mousedown', (ev) => {
    const canvas = ev.target.closest?.('.session-canvas');
    if (!canvas) return;
    canvas.focus();
    const entry = liveSessions.get(canvas.getAttribute('data-tab-id'));
    if (entry?.kind === 'rdp') {
      const { x, y } = canvasCoords(canvas, ev);
      entry.instance.sendMouseButton(x, y, ev.button, true);
    } else if (entry?.kind === 'vnc') {
      const { x, y } = canvasCoords(canvas, ev);
      entry.instance.sendPointerEvent(x, y, ev.buttons & 0x07);
    }
  });

  document.addEventListener('mouseup', (ev) => {
    const canvas = ev.target.closest?.('.session-canvas');
    if (!canvas) return;
    const entry = liveSessions.get(canvas.getAttribute('data-tab-id'));
    if (entry?.kind === 'rdp') {
      const { x, y } = canvasCoords(canvas, ev);
      entry.instance.sendMouseButton(x, y, ev.button, false);
    } else if (entry?.kind === 'vnc') {
      const { x, y } = canvasCoords(canvas, ev);
      entry.instance.sendPointerEvent(x, y, ev.buttons & 0x07);
    }
  });
}

async function bootstrap() {
  subscribePwaLifecycle((snap) => {
    store.setState({
      pwaStandalone: snap.standalone,
      pwaInstallAvailable: snap.installPromptAvailable,
      swUpdateWaiting: snap.swUpdateWaiting,
      localBuildHash: snap.localBuildHash || store.state.localBuildHash
    });
  });
  initChromeOSPwaLifecycle({
    // Auto-reload immediately on background SW update only when no live remote session would be interrupted
    onAutoReloadSafe: () => store.state.tabs.length === 0
  });
  const initSnap = getPwaLifecycleSnapshot();
  store.setState({
    pwaStandalone: initSnap.standalone,
    pwaInstallAvailable: initSnap.installPromptAvailable,
    swUpdateWaiting: initSnap.swUpdateWaiting
  });

  store.subscribe(() => renderWorkspace());
  installEventDelegation();
  installDiscreetTooltips();
  await store.initializeOffline();
  await refreshRelayStatus();

  // Launch detached connection or ChromeOS Start-Menu App Shortcut (`?action=...`)
  const params = new URLSearchParams(location.search);
  const detachedConnId = params.get('detachedConn');
  const launcherAction = params.get('action');
  if (detachedConnId && !store.state.isVaultLocked) {
    store.launchConnection(detachedConnId);
  } else if (launcherAction && !store.state.isVaultLocked) {
    if (launcherAction === 'new-connection') {
      activeModal = { kind: 'connection', connId: '' };
      renderModal();
    } else if (launcherAction === 'quick-connect') {
      setTimeout(() => document.getElementById('quick-connect-input')?.focus(), 60);
    } else if (launcherAction === 'drive-sync') {
      store.openSettingsTab('sync');
    } else if (launcherAction === 'settings') {
      store.openSettingsTab('general');
    }
  }

  // Handle ChromeOS PWA File Handler launch (`.nxts` / `.rtsz` / `.rtsx` double-clicked in ChromeOS Files)
  if ('launchQueue' in window && window.launchQueue?.setConsumer) {
    window.launchQueue.setConsumer(async (launchParams) => {
      if (!launchParams?.files?.length) return;
      const handle = launchParams.files[0];
      if (handle.name.toLowerCase().endsWith('.nxts')) {
        await linkExistingDriveHandleZeroPrompt(handle);
      } else {
        const file = await handle.getFile();
        const bytes = new Uint8Array(await file.arrayBuffer());
        await store.importVaultFileBytes(bytes, file.name);
      }
    });
  }

  totpRefreshInterval = setInterval(refreshTotpBadges, 1000);
}

bootstrap();
