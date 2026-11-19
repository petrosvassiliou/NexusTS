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
  generateEd25519OpenSshKeypair,
  inspectUnencryptedRevisionHeader,
  INTERACTIVE_FAST_KDF_PARAMS,
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
import { NexusChromeOSStore, SETTINGS_TABS } from '../app/src/core/workspace-store.js';
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

  const hostingInfo = buildFirebaseHostingSite({ version: '0.9.1', releaseTag: 'v0.9.1' });
  assert.equal(hostingInfo.buildHash.length, 12);
  assert.ok(fs.existsSync(hostingInfo.versionJsonPath));

  const versionJson = JSON.parse(fs.readFileSync(hostingInfo.versionJsonPath, 'utf8'));
  assert.equal(versionJson.version, '0.9.1');
  assert.equal(versionJson.buildHash, hostingInfo.buildHash);

  const hostedSw = fs.readFileSync(`${hostingInfo.hostingDistDir}/sw.js`, 'utf8');
  assert.ok(hostedSw.includes(`nexusts-chromeos-offline-v0.9.1-${hostingInfo.buildHash}`));
  assert.equal(hostedSw.includes('.well-known'), false);
  assert.ok(hostedSw.includes("event.data.type === 'SKIP_WAITING'"));

  const hostedManifest = JSON.parse(fs.readFileSync(`${hostingInfo.hostingDistDir}/manifest.webmanifest`, 'utf8'));
  assert.equal(hostedManifest.display, 'standalone');
  assert.equal(hostedManifest.start_url, './?source=pwa');
  assert.equal(hostedManifest.shortcuts.length, 4);
  assert.equal(hostedManifest.version, '0.9.1');

  assert.equal(compareSemver('0.9.1', '0.9.0'), 1);
  assert.equal(compareSemver('0.9.0', '0.9.0-beta.1'), 1);
  assert.equal(canInstallPwa(), false);
  assert.equal(isRunningStandalone(), false);

  for (const loc of SUPPORTED_LOCALES) {
    const dict = resolveStrings(loc.code);
    assert.ok(dict.installAppCta.length > 0);
    assert.ok(dict.applyUpdateCta.length > 0);
  }
});

