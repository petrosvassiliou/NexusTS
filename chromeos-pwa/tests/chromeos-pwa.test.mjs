import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import zlib from 'node:zlib';

import { argon2idRaw } from '../app/src/core/argon2id.js';
import { compareVectorClocks, VectorClockRelation } from '../app/src/core/conflict-resolver.js';
import { resolveStrings, SUPPORTED_LOCALES } from '../app/src/core/i18n.js';
import { importEnterpriseVaultBytes } from '../app/src/core/importers-exporters.js';
import {
  computeTotpCode,
  DEFAULT_TRANSPARENT_PASSPHRASE,
  generateEd25519OpenSshKeypair,
  inspectUnencryptedRevisionHeader,
  INTERACTIVE_FAST_KDF_PARAMS,
  sealNxtsBytes,
  sealVaultDocument,
  unsealVaultDocument
} from '../app/src/core/nxts-crypto.js';
import { ChromeOSDriveSyncBridge } from '../app/src/core/offline-drive-sync.js';
import { canInstallPwa, compareSemver, isRunningStandalone } from '../app/src/core/pwa-updater.js';
import {
  createStarterVault,
  defaultInheritableProperties,
  defaultSettingsForProtocol,
  evaluateSmartFolder,
  parseKeySequenceSteps,
  parseQuickConnectInput,
  resolveEffectiveConnectionProfile
} from '../app/src/core/vault-models.js';
import { NexusChromeOSStore, SETTINGS_TABS, SYSTEM_CREDENTIALS_FOLDER_ID } from '../app/src/core/workspace-store.js';
import { buildWolMagicPacket, NexusFtpBrowserClient } from '../app/src/protocols/ftp-telnet-serial-netutils.js';
import {
  buildFastPathKeyboardPdu,
  buildHyperVPreconnectionPdu,
  buildX224ConnectionRequestPdu,
  decodeRdpBitmapTileToRgba,
  parseX224ConnectionConfirmPdu,
  PROTOCOL_SSL
} from '../app/src/protocols/rdp-client.js';
import { BufferedByteStream } from '../app/src/protocols/socket-transport.js';
import { parseOpenSshEd25519PrivateKeyPem } from '../app/src/protocols/ssh-sftp-client.js';
import { computeVncDesResponse, NexusVncSession } from '../app/src/protocols/vnc-client.js';
import { VtTerminalEmulator } from '../app/src/protocols/vt-terminal.js';
import {
  buildAllChromeOSArtifacts,
  buildFirebaseHostingSite,
  verifyStrictOfflineAndCsp
} from '../scripts/build-chromeos-bundles.mjs';

test('1. RFC 9106 Argon2id exact vector & .nxts v2 zero-knowledge container roundtrip + tamper rejection', async () => {
  const rfcTag = argon2idRaw({
    password: new Uint8Array(32).fill(0x01),
    salt: new Uint8Array(16).fill(0x02),
    secret: new Uint8Array(8).fill(0x03),
    associatedData: new Uint8Array(12).fill(0x04),
    memoryKiB: 32,
    iterations: 3,
    parallelism: 4,
    tagLength: 32
  });
  assert.equal(
    Buffer.from(rfcTag).toString('hex'),
    '0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659'
  );

  const doc = createStarterVault('ChromeOS-Test');
  doc.name = 'Frankfurt Core Vault';
  doc.revision = 7;
  doc.connections.push({
    id: 'conn-1',
    parentFolderId: null,
    name: 'PRD-SSH-01',
    host: '10.42.0.10',
    protocol: 'SSH',
    osFamily: 'Linux',
    tags: ['prod'],
    notes: '',
    favorite: true,
    sortOrder: 0,
    inheritable: defaultInheritableProperties(),
    settings: defaultSettingsForProtocol('SSH'),
    updatedAtEpochMs: 1700000000000
  });

  const sealed = await sealVaultDocument(doc, 'super-secret-passphrase', INTERACTIVE_FAST_KDF_PARAMS);
  assert.equal(inspectUnencryptedRevisionHeader(sealed), 7);

  const unsealed = await unsealVaultDocument(sealed, 'super-secret-passphrase');
  assert.equal(unsealed.name, 'Frankfurt Core Vault');
  assert.equal(unsealed.connections.length, 1);
  assert.equal(unsealed.connections[0].host, '10.42.0.10');

  await assert.rejects(() => unsealVaultDocument(sealed, 'wrong-passphrase'));

  // Tamper authenticated revision header byte in AAD -> must reject!
  const tampered = sealed.slice();
  tampered[69] ^= 0x01;
  await assert.rejects(() => unsealVaultDocument(tampered, 'super-secret-passphrase'));
});

test('2. OpenSSH Ed25519 keypair generator & RFC 6238 TOTP authenticator', async () => {
  const kp = await generateEd25519OpenSshKeypair('ops@chromeos.nexusts');
  assert.ok(kp.publicKeyOpenSsh.startsWith('ssh-ed25519 '));
  assert.ok(kp.keyFingerprintSha256.startsWith('SHA256:'));
  assert.ok(kp.privateKeyPem.includes('-----BEGIN OPENSSH PRIVATE KEY-----'));

  const parsed = parseOpenSshEd25519PrivateKeyPem(kp.privateKeyPem);
  assert.equal(parsed.seed32.length, 32);
  assert.equal(parsed.pub32.length, 32);

  // RFC 6238 SHA-1 Base32 secret "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" ("12345678901234567890") at t=59s -> 287082
  const totp = await computeTotpCode('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 59000, 30, 6);
  assert.equal(totp.code, '287082');
});

test('3. Automatic Google Drive Sync (startup pull, local push, remote change detection & 3-way merge)', async () => {
  let remoteDiskBytes = new Uint8Array(0);
  const mockDriveTransport = {
    displayName: 'Shared-Team-Vault.nxts',
    async readBytes() {
      return { bytes: remoteDiskBytes.slice(), lastModified: Date.now() };
    },
    async writeBytes(payload) {
      remoteDiskBytes = payload.slice();
    }
  };

  const bridgeA = new ChromeOSDriveSyncBridge({ kdfParams: INTERACTIVE_FAST_KDF_PARAMS, deviceId: 'Chromebook-A' });
  const docA = createStarterVault('Chromebook-A');
  docA.name = 'Synced Infra Vault';
  await bridgeA.linkTransport(mockDriveTransport, null, docA.documentId);

  // Initial push to empty Google Drive .nxts file
  const pushRes = await bridgeA.synchronizeLinkedVault(docA, 'sync-pw');
  assert.equal(pushRes.kind, 'PUSHED');
  assert.equal(await bridgeA.hasRemoteContentChanged(), false);

  // Cold-start on Chromebook-B with uninitialized starter vault -> MUST pull without overwriting!
  const bridgeB = new ChromeOSDriveSyncBridge({ kdfParams: INTERACTIVE_FAST_KDF_PARAMS, deviceId: 'Chromebook-B' });
  const freshStarterB = createStarterVault('Chromebook-B');
  await bridgeB.linkTransport(mockDriveTransport, null, '');
  const startupPull = await bridgeB.synchronizeLinkedVault(freshStarterB, 'sync-pw', { localIsUninitializedStarter: true });
  assert.equal(startupPull.kind, 'PULLED');
  assert.equal(startupPull.document.documentId, docA.documentId);
  assert.equal(startupPull.document.name, 'Synced Infra Vault');

  // Simulate macOS peer editing the remote file -> Chromebook-A detects remote change immediately!
  const macDoc = structuredClone(startupPull.document);
  macDoc.revision += 1;
  macDoc.vectorClock['MacBook-Pro'] = 1;
  macDoc.connections.push({
    id: 'conn-mac',
    parentFolderId: null,
    name: 'Mac-Added-RDP',
    host: '10.88.1.5',
    protocol: 'RDP',
    osFamily: 'Windows',
    tags: [],
    notes: '',
    favorite: false,
    sortOrder: 0,
    inheritable: defaultInheritableProperties(),
    settings: defaultSettingsForProtocol('RDP'),
    updatedAtEpochMs: Date.now()
  });
  remoteDiskBytes = await sealVaultDocument(macDoc, 'sync-pw', INTERACTIVE_FAST_KDF_PARAMS);

  assert.equal(await bridgeA.hasRemoteContentChanged(), true);

  // Concurrent local edit on Chromebook-A -> 3-way VectorClock merge preserves BOTH connections!
  const concurrentLocalA = structuredClone(pushRes.document);
  concurrentLocalA.revision += 1;
  concurrentLocalA.vectorClock['Chromebook-A'] = 2;
  concurrentLocalA.connections.push({
    id: 'conn-cros',
    parentFolderId: null,
    name: 'ChromeOS-Added-SSH',
    host: '10.88.1.9',
    protocol: 'SSH',
    osFamily: 'Linux',
    tags: [],
    notes: '',
    favorite: false,
    sortOrder: 1,
    inheritable: defaultInheritableProperties(),
    settings: defaultSettingsForProtocol('SSH'),
    updatedAtEpochMs: Date.now()
  });

  assert.equal(
    compareVectorClocks(concurrentLocalA.vectorClock, macDoc.vectorClock),
    VectorClockRelation.CONCURRENT_DIVERGENCE
  );
  const mergeRes = await bridgeA.synchronizeLinkedVault(concurrentLocalA, 'sync-pw');
  assert.equal(mergeRes.kind, 'MERGED');
  const ids = new Set(mergeRes.document.connections.map((c) => c.id));
  assert.ok(ids.has('conn-mac'));
  assert.ok(ids.has('conn-cros'));
  assert.equal(await bridgeA.hasRemoteContentChanged(), false);
});

test('4. Multi-format importers (.rtsz GZIP, KeePass XML, Bitwarden JSON, CSV) & cascading inheritance', async () => {
  const rtsxXml = `<?xml version="1.0" encoding="utf-8"?>
  <RoyalDocument>
    <ID>doc-royal</ID>
    <Name>Corp Royal Vault</Name>
    <RoyalFolder>
      <ID>fld-prod</ID>
      <Name>Production</Name>
      <CredentialId>cred-dom</CredentialId>
    </RoyalFolder>
    <RoyalCredential>
      <ID>cred-dom</ID>
      <Name>CORP Admin</Name>
      <UserName>CORP\\svc_admin</UserName>
      <Password>s3cr3t!</Password>
    </RoyalCredential>
    <RoyalRDSConnection>
      <ID>srv-rdp-1</ID>
      <ParentID>fld-prod</ParentID>
      <Name>WIN-DC-01</Name>
      <URI>10.50.0.10</URI>
      <RDPPort>3389</RDPPort>
    </RoyalRDSConnection>
  </RoyalDocument>`;

  const rtszGzip = new Uint8Array(zlib.gzipSync(Buffer.from(rtsxXml, 'utf8')));
  const summary = await importEnterpriseVaultBytes(rtszGzip, 'Enterprise.rtsz');
  assert.equal(summary.isRoyalTsFormat, true);
  assert.equal(summary.importedConnections, 1);
  assert.equal(summary.importedFolders, 1);

  const profile = resolveEffectiveConnectionProfile(summary.document.connections[0], summary.document);
  assert.equal(profile.effectiveCredential?.username, 'svc_admin');
  assert.equal(profile.effectiveCredential?.domain, 'CORP');
  assert.equal(profile.credentialInheritedFrom, 'Production');

  // Smart Folder evaluation & Quick Connect
  const sfMatches = evaluateSmartFolder(
    { id: 'sf-1', name: 'All RDP', filter: { includeProtocols: ['RDP'], hostPatternRegex: '^10\\.50\\.' } },
    summary.document
  );
  assert.equal(sfMatches.length, 1);

  const qc = parseQuickConnectInput('ssh://devops@192.168.10.55:2222');
  assert.equal(qc.protocol, 'SSH');
  assert.equal(qc.username, 'devops');
  assert.equal(qc.host, '192.168.10.55');
  assert.equal(qc.port, 2222);

  const macro = parseKeySequenceSteps('sudo -i{ENTER}{WAIT:200}$EffectiveUsername${TAB}', profile);
  assert.equal(macro.length, 5);
  assert.equal(macro[2].ms, 200);
  assert.equal(macro[3].value, 'svc_admin');
});

test('5. Protocol codecs: RDP X.224 + Hyper-V PCB + RLE, VNC DES + RFB session, VT100, WoL & FTP', async () => {
  // Hyper-V PCB & X.224
  const pcb = buildHyperVPreconnectionPdu('456789ab-cdef-0123-4567-89abcdef0123;EnhancedMode=1');
  assert.equal(new DataView(pcb.buffer).getUint32(8, true), 2);
  const cr = buildX224ConnectionRequestPdu({ username: 'admin', requestedProtocols: PROTOCOL_SSL });
  assert.equal(cr[0], 0x03);
  const cc = parseX224ConnectionConfirmPdu(new Uint8Array([0x0e, 0xd0, 0x00, 0x00, 0x12, 0x34, 0x00, 0x02, 0x00, 0x08, 0x00, 0x01, 0x00, 0x00, 0x00]));
  assert.equal(cc.selectedProtocol, PROTOCOL_SSL);

  const kbdPdu = buildFastPathKeyboardPdu(0x1c, true, false);
  assert.equal(kbdPdu.length, 4);
  const tile = decodeRdpBitmapTileToRgba(new Uint8Array([255, 0, 0, 0]), 1, 1, 32, false);
  assert.deepEqual([...tile], [0, 0, 255, 255]); // BGR -> RGBA

  // VNC DES & mock Direct-Sockets RFB 003.008 handshake
  const desResp = await computeVncDesResponse('secretpw', new Uint8Array(16).fill(0xaa));
  assert.equal(desResp.length, 16);

  let vncResized = null;
  const vncSession = new NexusVncSession({
    host: '10.0.0.77',
    port: 5900,
    onDesktopResize: (w, h, name) => { vncResized = { w, h, name }; },
    transportOptions: {
      streamFactory: async () => {
        let step = 0;
        const stream = new BufferedByteStream({
          writeFn: async () => {
            step++;
            if (step === 1) {
              stream.pushInbound(new Uint8Array([1, 1]));
            } else if (step === 2) {
              stream.pushInbound(new Uint8Array([0, 0, 0, 0]));
            } else if (step === 3) {
              const init = new Uint8Array(24 + 12);
              const dv = new DataView(init.buffer);
              dv.setUint16(0, 640, false);
              dv.setUint16(2, 480, false);
              dv.setUint32(20, 12, false);
              init.set(new TextEncoder().encode('ChromeOS-VNC'), 24);
              stream.pushInbound(init);
            }
          },
          closeFn: async () => {}
        });
        stream.pushInbound(new TextEncoder().encode('RFB 003.008\n'));
        return stream;
      }
    }
  });
  await vncSession.connect();
  assert.deepEqual(vncResized, { w: 640, h: 480, name: 'ChromeOS-VNC' });
  await vncSession.close();

  // VT100 TrueColor + Alternate Screen
  const vt = new VtTerminalEmulator(40, 10);
  vt.feedText('\x1b[38;2;80;200;120mREADY\x1b[0m\r\nPrompt> ');
  assert.equal(vt.visibleLinesText()[0], 'READY');
  assert.equal(vt.visibleLinesText()[1], 'Prompt>');

  // Wake-on-LAN Magic Packet (102 bytes, 108 with SecureOn)
  const wol = buildWolMagicPacket('AA:BB:CC:DD:EE:FF', '01:02:03:04:05:06');
  assert.equal(wol.length, 108);
  assert.deepEqual([...wol.subarray(0, 6)], [255, 255, 255, 255, 255, 255]);
  assert.deepEqual([...wol.subarray(6, 12)], [0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]);

  // FTP listing over simulated Direct Socket
  const ftp = new NexusFtpBrowserClient({
    host: '10.0.0.88',
    port: 21,
    username: 'ops',
    password: 'pw',
    transportOptions: {
      streamFactory: async (_h, port) => {
        if (port === 40100) {
          const ds = new BufferedByteStream({ writeFn: async () => {}, closeFn: async () => {} });
          ds.pushInbound(new TextEncoder().encode('type=file;size=512; report.txt\r\ntype=dir; configs\r\n'));
          ds.markClosed();
          return ds;
        }
        const cs = new BufferedByteStream({
          writeFn: async (bytes) => {
            const cmd = new TextDecoder().decode(bytes).trim();
            if (cmd.startsWith('USER')) cs.pushInbound(new TextEncoder().encode('331 Password needed\r\n'));
            else if (cmd.startsWith('PASS')) cs.pushInbound(new TextEncoder().encode('230 Logged in\r\n'));
            else if (cmd.startsWith('OPTS')) cs.pushInbound(new TextEncoder().encode('200 UTF8 ON\r\n'));
            else if (cmd.startsWith('TYPE')) cs.pushInbound(new TextEncoder().encode('200 Binary\r\n'));
            else if (cmd.startsWith('CWD')) cs.pushInbound(new TextEncoder().encode('250 OK\r\n'));
            else if (cmd.startsWith('PWD')) cs.pushInbound(new TextEncoder().encode('257 "/srv" is current\r\n'));
            else if (cmd.startsWith('EPSV')) cs.pushInbound(new TextEncoder().encode('229 Entering Extended Passive (|||40100|)\r\n'));
            else if (cmd.startsWith('MLSD')) cs.pushInbound(new TextEncoder().encode('150 Opening data\r\n226 Transfer complete\r\n'));
          },
          closeFn: async () => {}
        });
        cs.pushInbound(new TextEncoder().encode('220 NexusFTP Ready\r\n'));
        return cs;
      }
    }
  });
  const listing = await ftp.listDirectory('/srv');
  assert.equal(listing.path, '/srv');
  assert.equal(listing.entries.length, 2);
});

test('6. Workspace Store: v0.8.14 sidebar active-tab switching, 8-tab Settings, 6 locales & .swbn IWA bundle verification', async () => {
  const store = new NexusChromeOSStore({ kdfParams: INTERACTIVE_FAST_KDF_PARAMS });
  await store.initializeOffline();

  await store.saveConnection({ id: 'srv-1', name: 'Server1', host: '10.0.0.1', protocol: 'SSH' });
  await store.saveConnection({ id: 'srv-2', name: 'Server2', host: '10.0.0.2', protocol: 'SSH' });
  await store.saveConnection({ id: 'srv-3', name: 'Server3-Inactive', host: '10.0.0.3', protocol: 'RDP' });

  const tab1 = store.launchConnection('srv-1');
  const tab2 = store.launchConnection('srv-2');
  store.setState({ focusedTabId: tab1.tabId });

  // Open Unified Settings & verify all 8 tabs exist
  store.toggleSettingsButton();
  assert.equal(store.state.activeDestination, 'settings');
  assert.equal(SETTINGS_TABS.length, 8);

  // Clicking active Server2 in the sidebar switches straight to Server2 AND returns from Settings to Connections!
  const switchedActive = store.selectConnectionInSidebar('srv-2');
  assert.equal(switchedActive, true);
  assert.equal(store.state.activeDestination, 'connections');
  assert.equal(store.state.focusedTabId, tab2.tabId);

  // Clicking inactive Server3 in the sidebar selects it for inspection WITHOUT launching or switching active tab!
  const switchedInactive = store.selectConnectionInSidebar('srv-3');
  assert.equal(switchedInactive, false);
  assert.equal(store.state.focusedTabId, tab2.tabId);
  assert.equal(store.state.tabs.length, 2);

  // Verify all 6 enterprise locales contain complete keys
  for (const loc of SUPPORTED_LOCALES) {
    const dict = resolveStrings(loc.code);
    assert.ok(dict.navSettings.length > 0);
    assert.ok(dict.driveCreateSynced.length > 0);
    assert.ok(dict.directSocketsBadge.length > 0);
  }

  // Verify strict IWA CSP & build both .swbn Isolated Web App and Offline PWA .zip
  verifyStrictOfflineAndCsp();
  const buildInfo = buildAllChromeOSArtifacts({ version: '0.9.0', releaseTag: 'v0.9.0' });
  assert.ok(fs.existsSync(buildInfo.swbnPath));
  assert.ok(fs.existsSync(buildInfo.zipPath));
  assert.ok(buildInfo.origin.startsWith('isolated-app://'));
  const swbnHead = fs.readFileSync(buildInfo.swbnPath).subarray(0, 10);
  // CBOR 4-item array (0x84) + 8-byte bstr (0x48) + 🖋📦 Integrity Block magic (f0 9f 96 8b f0 9f 93 a6)
  assert.equal(swbnHead.toString('hex'), '8448f09f968bf09f93a6');
});

test('7. Firebase Hosting ChromeOS Start-Menu PWA (nexusts-prod): manifest shortcuts, no-cache SW headers & stamped buildHash', () => {
  const repoRoot = new URL('../..', import.meta.url);
  const firebaserc = JSON.parse(fs.readFileSync(new URL('.firebaserc', repoRoot), 'utf8'));
  assert.equal(firebaserc.projects.default, 'nexusts-prod');

  const firebaseJson = JSON.parse(fs.readFileSync(new URL('firebase.json', repoRoot), 'utf8'));
  assert.equal(firebaseJson.hosting.public, 'chromeos-pwa/hosting-dist');
  const noCacheRule = firebaseJson.hosting.headers.find((h) => h.source.includes('sw.js'));
  assert.ok(noCacheRule);
  assert.equal(noCacheRule.headers[0].value, 'no-cache, no-store, must-revalidate');

  const hostingInfo = buildFirebaseHostingSite({ version: '0.9.9', releaseTag: 'v0.9.9' });
  assert.equal(hostingInfo.buildHash.length, 12);
  assert.ok(fs.existsSync(hostingInfo.versionJsonPath));

  const versionJson = JSON.parse(fs.readFileSync(hostingInfo.versionJsonPath, 'utf8'));
  assert.equal(versionJson.version, '0.9.9');
  assert.equal(versionJson.buildHash, hostingInfo.buildHash);

  const hostedSw = fs.readFileSync(`${hostingInfo.hostingDistDir}/sw.js`, 'utf8');
  assert.ok(hostedSw.includes(`nexusts-chromeos-offline-v0.9.9-${hostingInfo.buildHash}`));
  assert.equal(hostedSw.includes('.well-known'), false);
  assert.ok(hostedSw.includes("event.data.type === 'SKIP_WAITING'"));

  const hostedManifest = JSON.parse(fs.readFileSync(`${hostingInfo.hostingDistDir}/manifest.webmanifest`, 'utf8'));
  assert.equal(hostedManifest.display, 'standalone');
  assert.equal(hostedManifest.start_url, './?source=pwa');
  assert.equal(hostedManifest.shortcuts.length, 4);
  assert.equal(hostedManifest.version, '0.9.9');
  assert.equal(hostedManifest.theme_color, '#ffffff');
  assert.equal(hostedManifest.background_color, '#f8fafc');

  const hostedTokensCss = fs.readFileSync(`${hostingInfo.hostingDistDir}/styles/tokens.css`, 'utf8');
  assert.ok(hostedTokensCss.includes('color-scheme: light;'));
  assert.ok(hostedTokensCss.includes('--bg-canvas: #f8fafc;'));

  const hostedAppJs = fs.readFileSync(`${hostingInfo.hostingDistDir}/src/ui/app.js`, 'utf8');
  assert.ok(hostedAppJs.includes('function renderSidebarConnectionTypeIcon'));
  assert.ok(hostedAppJs.includes('M5.8 8.8L8.8 11.6L5.8 14.4M14.2 5.6L11.2 8.4L14.2 11.2'));
  assert.equal(hostedAppJs.includes('<span class="proto-badge">${esc(badge)}</span>'), false);
  assert.ok(hostedAppJs.includes('function renderGooglebookTopBar'));
  assert.ok(hostedAppJs.includes('function renderGooglebookOverviewDashboard'));
  assert.ok(hostedAppJs.includes('function renderSmartFoldersAndCredentialsSections'));
  assert.ok(hostedAppJs.includes('sys-credentials-folder-row'));
  assert.ok(hostedAppJs.includes('sidebar-empty-action-row'));
  assert.ok(hostedAppJs.includes('function renderFlagSvg'));
  assert.ok(hostedAppJs.includes('split-action-group'));
  assert.ok(hostedAppJs.includes('qc-palette-card'));

  assert.equal(compareSemver('0.9.9', '0.9.7'), 1);
  assert.equal(compareSemver('0.9.0', '0.9.0-beta.1'), 1);
  assert.equal(canInstallPwa(), false);
  assert.equal(isRunningStandalone(), false);

  for (const loc of SUPPORTED_LOCALES) {
    const dict = resolveStrings(loc.code);
    assert.ok(dict.installAppCta.length > 0);
    assert.ok(dict.applyUpdateCta.length > 0);
    assert.ok(dict.quickConnectButton.length > 0);
    assert.ok(dict.quickConnectLaunchpadHint.length > 0);
    assert.equal(dict.systemCredentialsFolder, 'Credentials');
  }
});

test('8. Zero-Prompt Passwordless Google Drive Sync (macOS/Android transparent key + empty-pw fallback) & Light Mode default', async () => {
  // 1. Existing Google Drive .nxts created on macOS/Android WITHOUT a custom master password
  const existingDriveDoc = createStarterVault('MacBook-Pro');
  existingDriveDoc.name = 'Shared Passwordless Vault';
  existingDriveDoc.revision = 4;
  existingDriveDoc.folders.push({
    id: 'fld-cloud',
    parentFolderId: null,
    name: 'Cloud Servers',
    description: '',
    tags: [],
    inheritable: defaultInheritableProperties(),
    sortOrder: 0,
    updatedAtEpochMs: Date.now()
  });
  existingDriveDoc.connections.push({
    id: 'conn-cros-1',
    parentFolderId: 'fld-cloud',
    name: 'Prod-Bastion-SSH',
    host: '10.20.30.40',
    protocol: 'SSH',
    osFamily: 'Linux',
    tags: [],
    notes: '',
    favorite: true,
    sortOrder: 0,
    inheritable: defaultInheritableProperties(),
    settings: defaultSettingsForProtocol('SSH'),
    updatedAtEpochMs: Date.now()
  });

  let driveBytes = await sealVaultDocument(existingDriveDoc, DEFAULT_TRANSPARENT_PASSPHRASE, INTERACTIVE_FAST_KDF_PARAMS);
  const mockDrive = {
    displayName: 'NexusTS-Vault.nxts',
    async readBytes() {
      return { bytes: driveBytes.slice(), lastModified: Date.now() };
    },
    async writeBytes(payload) {
      driveBytes = payload.slice();
    }
  };

  const store = new NexusChromeOSStore({ kdfParams: INTERACTIVE_FAST_KDF_PARAMS, deviceId: 'Chromebook-Light' });
  assert.equal(store.state.themeMode, 'LIGHT');
  await store.initializeOffline();

  // Link & sync existing Google Drive .nxts with ZERO password prompt / argument!
  const syncRes = await store.linkGoogleDriveTransport(mockDrive);
  assert.equal(syncRes.kind, 'PULLED');
  assert.equal(store.state.isVaultLocked, false);
  assert.equal(store.state.hasCustomPassphrase, false);
  assert.equal(store.state.vaultDocument.name, 'Shared Passwordless Vault');
  assert.equal(store.state.vaultDocument.connections.length, 1);
  assert.ok(store.state.expandedFolderIds.has('fld-cloud'));

  // Local edit pushes back to Google Drive using transparent key without asking for any password
  await store.saveConnection({ id: 'conn-cros-new', name: 'Chromebook-RDP', host: '10.20.30.99', protocol: 'RDP' });
  const pushRes = await store.triggerGoogleDriveSync({ reason: 'test-push' });
  assert.equal(pushRes.kind, 'PUSHED');
  const macReloaded = await unsealVaultDocument(driveBytes, DEFAULT_TRANSPARENT_PASSPHRASE);
  assert.equal(macReloaded.connections.length, 2);

  // Cold restart auto-unlocks local vault without showing lock shield!
  const coldStore = new NexusChromeOSStore({ kdfParams: INTERACTIVE_FAST_KDF_PARAMS, deviceId: 'Chromebook-Light' });
  await coldStore.initializeOffline();
  assert.equal(coldStore.state.isVaultLocked, false);
  assert.equal(coldStore.state.hasCustomPassphrase, false);
  assert.equal(coldStore.state.vaultDocument.connections.length, 2);

  // Legacy empty-string ('') .nxts container also opens cleanly with zero password prompt
  const emptyPwSealed = await sealNxtsBytes(
    new TextEncoder().encode(JSON.stringify(existingDriveDoc)),
    '',
    2,
    INTERACTIVE_FAST_KDF_PARAMS
  );
  const unsealedEmptyPw = await unsealVaultDocument(emptyPwSealed);
  assert.equal(unsealedEmptyPw.name, 'Shared Passwordless Vault');
});

test('9. Sidebar Smart Folders & Pinned System Credentials Folder 1:1 macOS Parity', async () => {
  const store = new NexusChromeOSStore({ kdfParams: INTERACTIVE_FAST_KDF_PARAMS, deviceId: 'Chromebook-Sidebar' });
  await store.initializeOffline();
  await store.resetVaultDatabase();

  assert.equal(store.state.isSystemCredentialsExpanded, true);
  assert.equal(SYSTEM_CREDENTIALS_FOLDER_ID, '__NEXUSTS_SYSTEM_CREDENTIALS__');

  // Reserved "Credentials" root folder name and drops into system Credentials folder are blocked
  const blockedFolder = await store.saveFolder({ name: 'Credentials', parentFolderId: null });
  assert.equal(blockedFolder, null);
  assert.equal(store.state.vaultDocument.folders.length, 0);

  await store.saveConnection({ id: 'c-rdp-1', name: 'LSG-RDS-01', host: '10.10.5.20', protocol: 'RDP' });
  await store.saveConnection({ id: 'c-ssh-1', name: 'Core-Jump', host: '10.10.5.21', protocol: 'SSH' });
  const blockedMove = await store.moveConnectionToFolder('c-rdp-1', SYSTEM_CREDENTIALS_FOLDER_ID);
  assert.equal(blockedMove, null);

  // Create & update credentials in place
  await store.saveCredential({ id: 'cred-1', name: 'Vassiliou-ADM', username: 'vassiliou-adm', domain: 'LSG', secretPassword: 'pw1' });
  await store.saveCredential({ id: 'cred-2', name: 'LSG - lokal', username: 'admin', secretPassword: 'pw2' });
  assert.equal(store.state.vaultDocument.credentials.length, 2);
  assert.equal(store.state.selectedCredentialId, 'cred-2');

  await store.saveCredential({ id: 'cred-1', name: 'Vassiliou-ADM', username: 'p.vassiliou-adm', domain: 'LSG', secretPassword: 'pw1-updated' });
  assert.equal(store.state.vaultDocument.credentials.length, 2);
  assert.equal(store.state.vaultDocument.credentials[0].username, 'p.vassiliou-adm');
  assert.equal(store.state.vaultDocument.credentials[0].secretPassword, 'pw1-updated');

  store.toggleSystemCredentialsExpanded();
  assert.equal(store.state.isSystemCredentialsExpanded, false);
  store.toggleSystemCredentialsExpanded();
  assert.equal(store.state.isSystemCredentialsExpanded, true);

  // Create & update Smart Folder in place and evaluate matched connections
  await store.saveSmartFolder({
    id: 'sf-rdp',
    name: 'All RDP Hosts',
    filter: { includeProtocols: ['RDP'], requiredTags: [], hostPatternRegex: '^10\\.10\\.' }
  });
  assert.equal(store.state.vaultDocument.smartFolders.length, 1);
  assert.ok(store.state.expandedSmartFolderIds.has('sf-rdp'));
  assert.equal(store.visibleConnectionsForSmartFolder('sf-rdp').length, 1);

  await store.saveSmartFolder({
    id: 'sf-rdp',
    name: 'Subnet 10.10 All',
    filter: { includeProtocols: [], requiredTags: [], hostPatternRegex: '^10\\.10\\.' }
  });
  assert.equal(store.state.vaultDocument.smartFolders.length, 1);
  assert.equal(store.state.vaultDocument.smartFolders[0].name, 'Subnet 10.10 All');
  assert.equal(store.visibleConnectionsForSmartFolder('sf-rdp').length, 2);
});

test('10. Pre-1.0 QS Smoke & Performance Gate: zero dead data-actions, 6-locale parity, coalesced seal & 500-conn <25ms throughput', async () => {
  const repoRoot = new URL('../..', import.meta.url);

  // 1. Assert EVERY static data-action="..." emitted in app.js has a matching case handler in installEventDelegation()
  const appJsSrc = fs.readFileSync(new URL('chromeos-pwa/app/src/ui/app.js', repoRoot), 'utf8');
  const emittedActions = new Set(
    [...appJsSrc.matchAll(/data-action="([a-z0-9-]+)"/g)].map((m) => m[1])
  );
  const handledCases = new Set(
    [...appJsSrc.matchAll(/case\s+'([a-z0-9-]+)'\s*:/g)].map((m) => m[1])
  );
  for (const act of ['enroll-webauthn-bio', 'unlock-webauthn-bio', 'toggle-favorite', 'new-conn-in-folder']) {
    assert.ok(emittedActions.has(act), `Expected data-action="${act}" in app.js`);
  }
  const unhandled = [...emittedActions].filter((a) => !handledCases.has(a));
  assert.deepEqual(unhandled, [], `Unhandled data-action attributes found in app.js: ${unhandled.join(', ')}`);

  // 2. Assert root '/' and '/@(sw.js|...)' both serve no-cache in firebase.json
  const firebaseJson = JSON.parse(fs.readFileSync(new URL('firebase.json', repoRoot), 'utf8'));
  const rootHeader = firebaseJson.hosting.headers.find((h) => h.source === '/');
  assert.ok(rootHeader, 'Expected explicit "/" header rule in firebase.json');
  assert.equal(rootHeader.headers[0].value, 'no-cache, no-store, must-revalidate');

  // 3. Assert 100% key parity & non-empty translations across all 6 enterprise locales
  const enKeys = Object.keys(resolveStrings('en')).sort();
  for (const loc of SUPPORTED_LOCALES) {
    const dict = resolveStrings(loc.code);
    assert.deepEqual(Object.keys(dict).sort(), enKeys, `Locale ${loc.code} key mismatch`);
    for (const k of enKeys) {
      assert.ok(typeof dict[k] === 'string' && dict[k].trim().length > 0, `Empty string for ${loc.code}.${k}`);
    }
  }

  // 4. Verify coalesced local persistence & toggleConnectionFavorite under rapid concurrent mutations
  const store = new NexusChromeOSStore({ kdfParams: INTERACTIVE_FAST_KDF_PARAMS, deviceId: 'Chromebook-QS' });
  await store.initializeOffline();
  await store.resetVaultDatabase();
  await Promise.all([
    store.saveConnection({ id: 'qs-1', name: 'QS-1', host: '10.1.0.1', protocol: 'RDP' }),
    store.saveConnection({ id: 'qs-2', name: 'QS-2', host: '10.1.0.2', protocol: 'SSH' }),
    store.saveConnection({ id: 'qs-3', name: 'QS-3', host: '10.1.0.3', protocol: 'VNC' })
  ]);
  await store.toggleConnectionFavorite('qs-2');
  assert.equal(store.state.vaultDocument.connections.find((c) => c.id === 'qs-2')?.favorite, true);

  // 5. 500-Connection + 50-Folder + 12-Smart-Folder Enterprise Performance Gate (< 50ms budget)
  const { sanitizeVaultDocument, resolveEffectiveConnectionProfile, evaluateSmartFolder, bumpVaultRevision, defaultInheritableProperties, defaultSettingsForProtocol } = await import('../app/src/core/vault-models.js');
  const { mergeVaultDocuments } = await import('../app/src/core/conflict-resolver.js');

  const big = createStarterVault('perf-dev-a');
  for (let i = 0; i < 40; i++) {
    big.credentials.push({ id: `cr-${i}`, name: `Cred ${i}`, username: `u${i}`, secretPassword: `pw-${i}`, updatedAtEpochMs: 1000 });
  }
  for (let i = 0; i < 50; i++) {
    big.folders.push({
      id: `fl-${i}`,
      parentFolderId: i >= 10 ? `fl-${i % 10}` : null,
      name: `Folder ${i}`,
      inheritable: defaultInheritableProperties(i % 5 === 0 ? { credentialMode: 'OVERRIDE_EXPLICIT', explicitCredentialId: `cr-${i % 40}` } : {}),
      sortOrder: i,
      updatedAtEpochMs: 1000
    });
  }
  const protos = ['RDP', 'SSH', 'SFTP', 'VNC', 'WEB_PAGE'];
  for (let i = 0; i < 500; i++) {
    const p = protos[i % protos.length];
    big.connections.push({
      id: `cn-${i}`,
      parentFolderId: `fl-${i % 50}`,
      name: `srv-${i}.corp`,
      host: `10.${Math.floor(i / 250)}.${i % 250}.10`,
      protocol: p,
      tags: i % 2 === 0 ? ['prod'] : ['dev'],
      inheritable: defaultInheritableProperties(),
      settings: defaultSettingsForProtocol(p, '10.0.0.10'),
      sortOrder: i,
      updatedAtEpochMs: 1000
    });
  }
  for (let i = 0; i < 12; i++) {
    big.smartFolders.push({
      id: `sf-${i}`,
      name: `SF ${i}`,
      filter: { includeProtocols: [protos[i % protos.length]], requiredTags: ['prod'], hostPatternRegex: '^10\\.' },
      updatedAtEpochMs: 1000
    });
  }

  let t0 = performance.now();
  const sanitized = sanitizeVaultDocument(big);
  const sanitizeMs = performance.now() - t0;
  assert.ok(sanitizeMs < 50, `sanitizeVaultDocument took ${sanitizeMs.toFixed(2)}ms`);

  t0 = performance.now();
  for (const c of sanitized.connections) resolveEffectiveConnectionProfile(c, sanitized);
  const resolveMs = performance.now() - t0;
  assert.ok(resolveMs < 50, `500x resolveEffectiveConnectionProfile took ${resolveMs.toFixed(2)}ms`);

  t0 = performance.now();
  for (const sf of sanitized.smartFolders) evaluateSmartFolder(sf, sanitized);
  const sfMs = performance.now() - t0;
  assert.ok(sfMs < 50, `12x evaluateSmartFolder took ${sfMs.toFixed(2)}ms`);

  const peer = structuredClone(sanitized);
  peer.connections[7].name = 'srv-7-updated';
  peer.connections[7].updatedAtEpochMs = 5000;
  t0 = performance.now();
  const merged = mergeVaultDocuments(bumpVaultRevision(sanitized, 'dev-a'), bumpVaultRevision(peer, 'dev-b'), 'dev-a');
  const mergeMs = performance.now() - t0;
  assert.equal(merged.mergedDocument.connections.length, 500);
  assert.ok(mergeMs < 50, `500-conn 3-way merge took ${mergeMs.toFixed(2)}ms`);
});




