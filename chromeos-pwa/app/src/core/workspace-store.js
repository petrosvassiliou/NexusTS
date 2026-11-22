// Headless Reactive Workspace Store for NexusTS ChromeOS PWA / Isolated Web App.
// Enforces 1:1 parity with `NexusWorkspaceViewModel.kt` (Android) and `WorkspaceStore.swift` (macOS),
// including v0.8.14 sidebar active-tab switching, unified 8-tab Settings, and automatic Google Drive sync.

import { detectInitialLocale, resolveStrings } from './i18n.js';
import { applyVaultImportPlacement, exportVaultDocument, importEnterpriseVaultBytes } from './importers-exporters.js';
import {
  DEFAULT_KDF_PARAMS,
  DEFAULT_TRANSPARENT_PASSPHRASE,
  sealVaultDocument,
  unsealVaultDocument
} from './nxts-crypto.js';
import {
  ChromeOSDriveSyncBridge,
  idbGet,
  idbSet,
  loadEncryptedVaultFromOfflineStore,
  requestPersistentOfflineStorage,
  saveEncryptedVaultToOfflineStore
} from './offline-drive-sync.js';
import {
  bumpVaultRevision,
  createStarterVault,
  defaultInheritableProperties,
  defaultSettingsForProtocol,
  effectiveConnectionPort,
  evaluateSmartFolder,
  parseQuickConnectInput,
  randomUuid,
  resolveEffectiveConnectionProfile,
  resolveSubtreeFolderIds,
  sanitizeVaultDocument
} from './vault-models.js';

export const SYSTEM_CREDENTIALS_FOLDER_ID = '__NEXUSTS_SYSTEM_CREDENTIALS__';

export const SETTINGS_TABS = Object.freeze([
  'general',
  'sync',
  'security',
  'credentials',
  'gateways',
  'templates',
  'tasks',
  'updates'
]);

export class NexusChromeOSStore {
  constructor({ kdfParams = DEFAULT_KDF_PARAMS, deviceId = 'ChromeOS-PWA' } = {}) {
    this.kdfParams = kdfParams;
    this.deviceId = deviceId;
    this.driveBridge = new ChromeOSDriveSyncBridge({ kdfParams, deviceId });
    this._listeners = new Set();
    this._saveSyncTimer = null;
    this._remoteWatchTimer = null;
    this._syncLocked = false;
    this._pendingSyncAfterCurrent = false;
    this._masterPassphrase = DEFAULT_TRANSPARENT_PASSPHRASE;

    this.state = {
      initialized: false,
      locale: detectInitialLocale(),
      themeMode: 'LIGHT',
      hasEncryptedLocalVault: false,
      hasCustomPassphrase: false,
      isVaultLocked: false,
      isStarterUninitialized: true,
      vaultUnlockError: '',
      platformBiometricAvailable: false,
      biometricUnlockEnrolled: false,
      isBiometricPromptActive: false,
      idleAutoLockMinutes: 0,
      lastActivityEpochMs: Date.now(),

      vaultDocument: createStarterVault(deviceId),
      preImportBackupDoc: null,
      pendingImportSummary: null,

      // Navigation & layout
      activeDestination: 'connections', // 'connections' | 'settings'
      activeSettingsTab: 'general',
      isSidebarVisible: true,
      isInspectorVisible: false,
      isBottomDrawerVisible: false,
      isFullScreenSession: false,
      remoteKeyCaptureEnabled: false,
      broadcastInputEnabled: false,
      auditLogs: [],

      // Sidebar state
      sidebarFilterQuery: '',
      sidebarQuickFilter: 'ALL', // 'ALL' | 'FAVORITES' | 'RECENTS'
      expandedFolderIds: new Set(),
      expandedSmartFolderIds: new Set(),
      isSystemCredentialsExpanded: true,
      selectedConnectionId: null,
      selectedFolderId: null,
      selectedSmartFolderId: null,
      selectedCredentialId: null,

      // Tiling & active tabs
      tilingLayout: 'SINGLE', // 'SINGLE' | 'SPLIT_H' | 'SPLIT_V' | 'QUAD'
      tabs: [], // [{ tabId, connectionId, title, protocol, status, detachedWindow }]
      focusedTabId: null,

      // Google Drive Sync state
      driveLinkedName: '',
      driveIsSyncing: false,
      driveNeedsPermissionGrant: false,
      driveStatusMessage: '',
      driveLastSyncedEpochMs: 0,

      // Transport & update status
      directSocketsAvailable: typeof globalThis.TCPSocket === 'function',
      localRelayReachable: false,
      localRelayUrl: 'ws://127.0.0.1:44890',
      localRelayHttpUrl: 'http://127.0.0.1:44890',
      appVersion: '0.9.5',
      pwaStandalone: false,
      pwaInstallAvailable: false,
      swUpdateWaiting: false,
      localBuildHash: '',
      updateCheckStatus: '',
      latestReleaseTag: '',
      latestReleaseUrl: '',
      toastMessage: ''
    };
  }

  get strings() {
    return resolveStrings(this.state.locale);
  }

  subscribe(fn) {
    this._listeners.add(fn);
    return () => this._listeners.delete(fn);
  }

  _emit() {
    for (const fn of this._listeners) fn(this.state);
  }

  setState(patch) {
    Object.assign(this.state, patch);
    this._emit();
  }

  showToast(msg) {
    this.setState({ toastMessage: msg });
  }

  recordUserActivity() {
    this.state.lastActivityEpochMs = Date.now();
  }

  async initializeOffline() {
    await requestPersistentOfflineStorage();
    const prefs = (await idbGet('workspace.prefs')) || {};
    if (prefs.locale) this.state.locale = prefs.locale;
    if (prefs.themeMode) this.state.themeMode = prefs.themeMode;
    if (typeof prefs.idleAutoLockMinutes === 'number') this.state.idleAutoLockMinutes = prefs.idleAutoLockMinutes;
    if (prefs.localRelayUrl) {
      this.state.localRelayUrl = prefs.localRelayUrl;
      this.state.localRelayHttpUrl = prefs.localRelayUrl.replace(/^ws/i, 'http');
    }

    const linkedMeta = await this.driveBridge.restorePersistedLink();
    if (linkedMeta) {
      this.state.driveLinkedName = linkedMeta.displayName || 'NexusTS-Vault.nxts';
      this.state.driveLastSyncedEpochMs = linkedMeta.lastSyncedEpochMs || 0;
      const permState = await this.driveBridge.transport?.queryPermissionState?.('readwrite');
      if (permState && permState !== 'granted') {
        this.state.driveNeedsPermissionGrant = true;
        this.state.driveStatusMessage = 'Click "Sync Now" (or anywhere) to resume Google Drive sync.';
      }
    }

    const sealed = await loadEncryptedVaultFromOfflineStore();
    if (sealed && sealed.length > 0) {
      this.state.hasEncryptedLocalVault = true;
      this.state.isStarterUninitialized = false;
      // Just like macOS WorkspaceStore.swift: try transparent sync key automatically first!
      try {
        const autoDoc = await unsealVaultDocument(sealed, DEFAULT_TRANSPARENT_PASSPHRASE);
        this._masterPassphrase = DEFAULT_TRANSPARENT_PASSPHRASE;
        this.state.hasCustomPassphrase = false;
        this.state.isVaultLocked = false;
        this.state.vaultDocument = autoDoc;
        this.state.expandedFolderIds = new Set((autoDoc.folders || []).map((f) => f.id));
        this.state.selectedConnectionId = autoDoc.connections[0]?.id || null;
      } catch {
        // User set a custom Master Passphrase -> show lock shield
        this._masterPassphrase = '';
        this.state.hasCustomPassphrase = true;
        this.state.isVaultLocked = true;
      }
    } else {
      // Load unencrypted local draft if present, or fresh starter vault
      const draft = await idbGet('vault.unencryptedDraft');
      if (draft && draft.documentId) {
        const sanitized = sanitizeVaultDocument(draft);
        this.state.vaultDocument = sanitized;
        this.state.isStarterUninitialized = false;
        this.state.expandedFolderIds = new Set(sanitized.folders.map((f) => f.id));
        this.state.selectedConnectionId = sanitized.connections[0]?.id || null;
      }
    }

    if (typeof globalThis.PublicKeyCredential?.isUserVerifyingPlatformAuthenticatorAvailable === 'function') {
      try {
        this.state.platformBiometricAvailable = Boolean(
          await globalThis.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable()
        );
      } catch {
        this.state.platformBiometricAvailable = false;
      }
    }
    const storedBio = await idbGet('vault.webauthnBiometric');
    this.state.biometricUnlockEnrolled = Boolean(storedBio?.credentialIdBytes && storedBio?.ciphertext);

    this.state.initialized = true;
    this._emit();

    // Automatic startup sync if vault is unlocked and permission is granted
    if (
      !this.state.isVaultLocked &&
      this.driveBridge.isLinked() &&
      !this.state.driveNeedsPermissionGrant &&
      this._masterPassphrase
    ) {
      await this.triggerGoogleDriveSync({ reason: 'startup', silent: true });
    }
    this._startBackgroundWatchers();
  }

  async tryRestoreDrivePermissionOnUserGesture() {
    if (!this.state.driveNeedsPermissionGrant || !this.driveBridge.isLinked()) return false;
    try {
      const ok = await this.driveBridge.transport?.ensurePermission?.('readwrite', true);
      if (ok) {
        this.setState({ driveNeedsPermissionGrant: false });
        if (!this.state.isVaultLocked && this._masterPassphrase) {
          await this.triggerGoogleDriveSync({ reason: 'gesture-regrant', silent: true });
        }
        return true;
      }
    } catch {
      // Ignore if browser disallowed prompt on this event
    }
    return false;
  }

  _startBackgroundWatchers() {
    if (typeof globalThis.window === 'undefined') return;
    if (this._remoteWatchTimer) clearInterval(this._remoteWatchTimer);

    // Check every 6s for external Google Drive file updates + idle auto-lock
    this._remoteWatchTimer = setInterval(async () => {
      if (
        this.state.idleAutoLockMinutes > 0 &&
        !this.state.isVaultLocked &&
        this.state.hasCustomPassphrase &&
        Date.now() - this.state.lastActivityEpochMs >= this.state.idleAutoLockMinutes * 60000
      ) {
        this.lockVault();
        return;
      }
      if (
        !this.state.isVaultLocked &&
        this.driveBridge.isLinked() &&
        !this.state.driveNeedsPermissionGrant &&
        this._masterPassphrase &&
        !this._syncLocked
      ) {
        const changed = await this.driveBridge.hasRemoteContentChanged();
        if (changed) {
          await this.triggerGoogleDriveSync({ reason: 'remote-change', silent: true });
        }
      }
    }, 6000);

    globalThis.window.addEventListener('focus', async () => {
      if (
        !this.state.isVaultLocked &&
        this.driveBridge.isLinked() &&
        !this.state.driveNeedsPermissionGrant &&
        this._masterPassphrase &&
        !this._syncLocked
      ) {
        if (await this.driveBridge.hasRemoteContentChanged()) {
          await this.triggerGoogleDriveSync({ reason: 'window-focus', silent: true });
        }
      }
    });
  }

  async savePreferences() {
    await idbSet('workspace.prefs', {
      locale: this.state.locale,
      themeMode: this.state.themeMode,
      idleAutoLockMinutes: this.state.idleAutoLockMinutes,
      localRelayUrl: this.state.localRelayUrl
    });
  }

  async setLocale(localeCode) {
    this.setState({ locale: localeCode });
    await this.savePreferences();
  }

  /**
   * Opens Unified Settings (or toggles back to Connections when clicking the top-bar Settings button while already in Settings).
   */
  toggleSettingsButton() {
    if (this.state.activeDestination === 'settings') {
      this.setState({ activeDestination: 'connections' });
    } else {
      this.setState({ activeDestination: 'settings' });
    }
  }

  openSettingsTab(tabId = 'general') {
    const valid = SETTINGS_TABS.includes(tabId) ? tabId : 'general';
    this.setState({
      activeDestination: 'settings',
      activeSettingsTab: valid
    });
  }

  returnToConnections() {
    this.setState({ activeDestination: 'connections' });
  }

  /**
   * v0.8.14 Sidebar Click Behavior:
   * - Always selects `connectionId` for inspection.
   * - If `connectionId` ALREADY has an active session tab (or detached window), immediately switches to that tab
   *   and switches the main workspace back to `'connections'`!
   * - If `connectionId` is NOT active, does NOT launch a session and leaves the current active session tab untouched!
   */
  selectConnectionInSidebar(connectionId) {
    const existingTab = this.state.tabs.find((t) => t.connectionId === connectionId);
    if (existingTab) {
      if (existingTab.detachedWindow && !existingTab.detachedWindow.closed) {
        try { existingTab.detachedWindow.focus(); } catch {}
      }
      this.setState({
        selectedConnectionId: connectionId,
        selectedFolderId: null,
        selectedSmartFolderId: null,
        selectedCredentialId: null,
        focusedTabId: existingTab.tabId,
        activeDestination: 'connections'
      });
      return true;
    }

    this.setState({
      selectedConnectionId: connectionId,
      selectedFolderId: null,
      selectedSmartFolderId: null,
      selectedCredentialId: null
    });
    return false;
  }

  toggleFolderExpanded(folderId) {
    const next = new Set(this.state.expandedFolderIds);
    if (next.has(folderId)) next.delete(folderId);
    else next.add(folderId);
    this.setState({
      expandedFolderIds: next,
      selectedFolderId: folderId,
      selectedSmartFolderId: null,
      selectedCredentialId: null
    });
  }

  toggleSmartFolderExpanded(smartFolderId) {
    const next = new Set(this.state.expandedSmartFolderIds);
    if (next.has(smartFolderId)) next.delete(smartFolderId);
    else next.add(smartFolderId);
    this.setState({
      expandedSmartFolderIds: next,
      selectedSmartFolderId: smartFolderId,
      selectedFolderId: null,
      selectedConnectionId: null,
      selectedCredentialId: null
    });
  }

  toggleSystemCredentialsExpanded() {
    this.setState({
      isSystemCredentialsExpanded: !this.state.isSystemCredentialsExpanded,
      selectedFolderId: SYSTEM_CREDENTIALS_FOLDER_ID,
      selectedSmartFolderId: null,
      selectedConnectionId: null,
      selectedCredentialId: null
    });
  }

  selectCredentialInSidebar(credentialId) {
    this.setState({
      selectedCredentialId: credentialId,
      selectedFolderId: SYSTEM_CREDENTIALS_FOLDER_ID,
      selectedSmartFolderId: null,
      selectedConnectionId: null
    });
  }

  launchConnection(connectionOrId, { forceNewTab = false } = {}) {
    const doc = this.state.vaultDocument;
    const conn =
      typeof connectionOrId === 'string'
        ? doc.connections.find((c) => c.id === connectionOrId)
        : connectionOrId;
    if (!conn) return null;

    if (!forceNewTab) {
      const existing = this.state.tabs.find((t) => t.connectionId === conn.id);
      if (existing) {
        this.setState({
          focusedTabId: existing.tabId,
          selectedConnectionId: conn.id,
          activeDestination: 'connections'
        });
        return existing;
      }
    }

    const tab = {
      tabId: `tab-${randomUuid()}`,
      connectionId: conn.id,
      adHocConnection: doc.connections.some((c) => c.id === conn.id) ? null : conn,
      title: conn.name || conn.host,
      protocol: conn.protocol,
      port: effectiveConnectionPort(conn),
      status: 'connecting',
      createdAt: Date.now(),
      detachedWindow: null
    };

    const recents = [conn.id, ...(doc.recentConnectionIds || []).filter((id) => id !== conn.id)].slice(0, 12);
    const updatedDoc = { ...doc, recentConnectionIds: recents };

    this.setState({
      vaultDocument: updatedDoc,
      tabs: [...this.state.tabs, tab],
      focusedTabId: tab.tabId,
      selectedConnectionId: conn.id,
      activeDestination: 'connections'
    });
    return tab;
  }

  launchQuickConnect(rawInput) {
    const parsed = parseQuickConnectInput(rawInput);
    if (!parsed || !parsed.host) return null;
    const adHocConn = {
      id: `adhoc-${randomUuid()}`,
      parentFolderId: null,
      name: `${parsed.host}:${parsed.port}`,
      host: parsed.host,
      protocol: parsed.protocol,
      osFamily: parsed.protocol === 'RDP' ? 'Windows' : 'Linux',
      tags: ['adhoc'],
      notes: '',
      favorite: false,
      sortOrder: 0,
      inheritable: defaultInheritableProperties(),
      settings: parsed.settings,
      adHocUsername: parsed.username,
      adHocDomain: parsed.domain,
      updatedAtEpochMs: Date.now()
    };
    return this.launchConnection(adHocConn, { forceNewTab: true });
  }

  launchAllRdpInFolder(folderId) {
    const scopeIds = resolveSubtreeFolderIds(folderId, this.state.vaultDocument);
    const rdpConns = (this.state.vaultDocument.connections || []).filter(
      (c) =>
        c.parentFolderId &&
        scopeIds.has(c.parentFolderId) &&
        (c.protocol === 'RDP' || c.protocol === 'WINDOWS_TERMINAL_SERVICES' || c.protocol === 'HYPER_V')
    );
    for (const c of rdpConns) this.launchConnection(c);
    return rdpConns.length;
  }

  closeTab(tabId, onCleanupSession = null) {
    onCleanupSession?.(tabId);
    const remaining = this.state.tabs.filter((t) => t.tabId !== tabId);
    let nextFocus = this.state.focusedTabId;
    if (nextFocus === tabId) {
      nextFocus = remaining[remaining.length - 1]?.tabId || null;
    }
    this.setState({
      tabs: remaining,
      focusedTabId: nextFocus,
      isFullScreenSession: remaining.length === 0 ? false : this.state.isFullScreenSession
    });
  }

  disconnectAllInFolder(folderId, onCleanupSession = null) {
    const scopeIds = resolveSubtreeFolderIds(folderId, this.state.vaultDocument);
    const connIdsInFolder = new Set(
      (this.state.vaultDocument.connections || [])
        .filter((c) => c.parentFolderId && scopeIds.has(c.parentFolderId))
        .map((c) => c.id)
    );
    const toClose = this.state.tabs.filter((t) => connIdsInFolder.has(t.connectionId));
    for (const t of toClose) this.closeTab(t.tabId, onCleanupSession);
    return toClose.length;
  }

  async setMasterPassphraseAndEncrypt(newPassphrase, { enableBiometrics = true } = {}) {
    const clean = String(newPassphrase || '');
    const isCustom = clean.length > 0;
    if (isCustom && clean.length < 4) {
      throw new Error('Passphrase must be at least 4 characters (or leave blank to reset to transparent sync key).');
    }
    const effective = isCustom ? clean : DEFAULT_TRANSPARENT_PASSPHRASE;
    this._masterPassphrase = effective;
    const sealed = await sealVaultDocument(this.state.vaultDocument, effective, this.kdfParams);
    await saveEncryptedVaultToOfflineStore(sealed, {
      documentId: this.state.vaultDocument.documentId,
      revision: this.state.vaultDocument.revision
    });
    await idbSet('vault.unencryptedDraft', null);
    if (!isCustom || !enableBiometrics) {
      await idbSet('vault.webauthnBiometric', null);
      this.state.biometricUnlockEnrolled = false;
    } else if (this.state.platformBiometricAvailable) {
      try {
        await this.enrollWebAuthnBiometricUnlock(clean);
      } catch {
        // Non-fatal if user cancels WebAuthn ceremony while saving password
      }
    }
    this.setState({
      hasEncryptedLocalVault: true,
      hasCustomPassphrase: isCustom,
      isVaultLocked: false,
      isStarterUninitialized: false,
      vaultUnlockError: ''
    });
    if (this.driveBridge.isLinked()) {
      this._scheduleDebouncedDriveSync();
    }
  }

  async enrollWebAuthnBiometricUnlock(passphraseToSeal = this._masterPassphrase) {
    if (!passphraseToSeal || passphraseToSeal === DEFAULT_TRANSPARENT_PASSPHRASE) {
      return false;
    }
    if (!globalThis.navigator?.credentials?.create || !globalThis.crypto?.subtle) {
      return false;
    }
    const challenge = globalThis.crypto.getRandomValues(new Uint8Array(32));
    const userId = globalThis.crypto.getRandomValues(new Uint8Array(16));
    const cred = await globalThis.navigator.credentials.create({
      publicKey: {
        challenge,
        rp: { name: 'NexusTS Enterprise Vault' },
        user: {
          id: userId,
          name: 'vault@nexusts.local',
          displayName: this.state.vaultDocument.name || 'NexusTS Vault'
        },
        pubKeyCredParams: [
          { type: 'public-key', alg: -7 },
          { type: 'public-key', alg: -257 }
        ],
        authenticatorSelection: {
          authenticatorAttachment: 'platform',
          userVerification: 'required',
          residentKey: 'discouraged'
        },
        timeout: 60000
      }
    });
    if (!cred?.rawId) return false;
    const aesKey = await globalThis.crypto.subtle.generateKey(
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt']
    );
    const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
    const plainBytes = new TextEncoder().encode(passphraseToSeal);
    const ciphertextBuf = await globalThis.crypto.subtle.encrypt({ name: 'AES-GCM', iv }, aesKey, plainBytes);
    await idbSet('vault.webauthnBiometric', {
      credentialIdBytes: new Uint8Array(cred.rawId),
      aesKey,
      iv,
      ciphertext: new Uint8Array(ciphertextBuf)
    });
    this.setState({ biometricUnlockEnrolled: true });
    return true;
  }

  async unlockWithWebAuthnBiometrics({ automatic = false } = {}) {
    if (!this.state.isVaultLocked || this.state.isBiometricPromptActive) return false;
    if (automatic && this._autoBioDismissedInFocus) return false;
    const record = await idbGet('vault.webauthnBiometric');
    if (!record?.credentialIdBytes || !record?.aesKey || !record?.ciphertext) return false;
    if (!globalThis.navigator?.credentials?.get) return false;

    if (!automatic) {
      this._autoBioDismissedInFocus = false;
    }
    this.setState({ isBiometricPromptActive: true, vaultUnlockError: '' });
    try {
      const challenge = globalThis.crypto.getRandomValues(new Uint8Array(32));
      const assertion = await globalThis.navigator.credentials.get({
        publicKey: {
          challenge,
          allowCredentials: [{ type: 'public-key', id: record.credentialIdBytes }],
          userVerification: 'required',
          timeout: 60000
        }
      });
      if (!assertion) {
        this.setState({ isBiometricPromptActive: false });
        return false;
      }
      const plainBuf = await globalThis.crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: record.iv },
        record.aesKey,
        record.ciphertext
      );
      const pass = new TextDecoder().decode(plainBuf);
      this._autoBioDismissedInFocus = false;
      this.setState({ isBiometricPromptActive: false });
      return await this.unlockVault(pass);
    } catch (err) {
      const isCancel = err?.name === 'NotAllowedError' || err?.name === 'AbortError';
      if (automatic && isCancel) {
        this._autoBioDismissedInFocus = true;
      }
      this.setState({
        isBiometricPromptActive: false,
        vaultUnlockError: automatic || isCancel ? '' : (err?.message || 'Biometric unlock failed.')
      });
      return false;
    }
  }

  resetAutoBiometricDismissal() {
    this._autoBioDismissedInFocus = false;
  }

  async unlockVault(passphrase) {
    try {
      const effective = passphrase || DEFAULT_TRANSPARENT_PASSPHRASE;
      const sealed = await loadEncryptedVaultFromOfflineStore();
      if (!sealed) {
        this._masterPassphrase = effective;
        this._autoBioDismissedInFocus = false;
        this.setState({
          hasCustomPassphrase: effective !== DEFAULT_TRANSPARENT_PASSPHRASE,
          isVaultLocked: false,
          vaultUnlockError: ''
        });
        return true;
      }
      const doc = await unsealVaultDocument(sealed, effective);
      this._masterPassphrase = effective;
      this._autoBioDismissedInFocus = false;
      this.setState({
        vaultDocument: doc,
        hasCustomPassphrase: effective !== DEFAULT_TRANSPARENT_PASSPHRASE,
        isVaultLocked: false,
        isStarterUninitialized: false,
        vaultUnlockError: '',
        expandedFolderIds: new Set((doc.folders || []).map((f) => f.id)),
        selectedConnectionId: doc.connections[0]?.id || null
      });
      // Automatic Google Drive sync immediately upon unlock!
      if (this.driveBridge.isLinked() && !this.state.driveNeedsPermissionGrant) {
        await this.triggerGoogleDriveSync({ reason: 'unlock', silent: true });
      }
      return true;
    } catch (err) {
      this.setState({ vaultUnlockError: err.message || 'Invalid passphrase' });
      return false;
    }
  }

  lockVault() {
    if (!this.state.hasCustomPassphrase) {
      this.openSettingsTab('security');
      this.showToast('Set a Master Passphrase in Security settings first if you want to password-lock your vault.');
      return;
    }
    this._masterPassphrase = '';
    this._autoBioDismissedInFocus = false;
    this.setState({
      isVaultLocked: true,
      vaultUnlockError: ''
    });
  }

  async commitVaultMutation(mutatorFn, { triggerCloudSync = true } = {}) {
    const nextRaw = mutatorFn(structuredClone(this.state.vaultDocument));
    const bumped = bumpVaultRevision(sanitizeVaultDocument(nextRaw), this.deviceId);
    this.setState({
      vaultDocument: bumped,
      isStarterUninitialized: false
    });
    await this._persistLocalVault(bumped);
    if (triggerCloudSync && this.driveBridge.isLinked()) {
      this._scheduleDebouncedDriveSync();
    }
    return bumped;
  }

  async _persistLocalVault(doc) {
    const effective = this._masterPassphrase || DEFAULT_TRANSPARENT_PASSPHRASE;
    const sealed = await sealVaultDocument(doc, effective, this.kdfParams);
    await saveEncryptedVaultToOfflineStore(sealed, {
      documentId: doc.documentId,
      revision: doc.revision
    });
    this.state.hasEncryptedLocalVault = true;
  }

  _scheduleDebouncedDriveSync() {
    if (this._saveSyncTimer) clearTimeout(this._saveSyncTimer);
    this._saveSyncTimer = setTimeout(() => {
      this.triggerGoogleDriveSync({ reason: 'local-save', silent: true });
    }, 900);
  }

  async linkGoogleDriveTransport(transportOrHandle, rawHandle = null, passphraseIfNeeded = '') {
    if (passphraseIfNeeded) {
      this._masterPassphrase = passphraseIfNeeded;
      this.state.hasCustomPassphrase = passphraseIfNeeded !== DEFAULT_TRANSPARENT_PASSPHRASE;
    } else if (!this._masterPassphrase) {
      this._masterPassphrase = DEFAULT_TRANSPARENT_PASSPHRASE;
    }
    const meta = await this.driveBridge.linkTransport(
      transportOrHandle,
      rawHandle,
      this.state.isStarterUninitialized ? '' : this.state.vaultDocument.documentId
    );
    this.setState({
      driveLinkedName: meta.displayName,
      driveNeedsPermissionGrant: false,
      driveStatusMessage: 'Linked — syncing…'
    });
    return await this.triggerGoogleDriveSync({ reason: 'initial-link', allowPermissionPrompt: true });
  }

  async disconnectGoogleDriveSync() {
    await this.driveBridge.unlink();
    this.setState({
      driveLinkedName: '',
      driveNeedsPermissionGrant: false,
      driveStatusMessage: '',
      driveLastSyncedEpochMs: 0,
      driveIsSyncing: false
    });
  }

  async triggerGoogleDriveSync({ reason = 'manual', silent = false, allowPermissionPrompt = false } = {}) {
    if (!this.driveBridge.isLinked()) return null;
    if (this.state.isVaultLocked) {
      if (!silent) this.setState({ driveStatusMessage: 'Unlock your vault to sync with Google Drive.' });
      return null;
    }
    if (this._syncLocked) {
      this._pendingSyncAfterCurrent = true;
      return null;
    }

    const effectivePass = this._masterPassphrase || DEFAULT_TRANSPARENT_PASSPHRASE;
    this._syncLocked = true;
    this.setState({ driveIsSyncing: true });
    try {
      const outcome = await this.driveBridge.synchronizeLinkedVault(
        this.state.vaultDocument,
        effectivePass,
        {
          localIsUninitializedStarter: this.state.isStarterUninitialized,
          allowPermissionPrompt
        }
      );
      const info = this.driveBridge.getLinkedInfo();
      const statusMap = {
        PUSHED: `Synced to Google Drive (rev ${outcome.document.revision})`,
        PULLED: `Updated from Google Drive (rev ${outcome.document.revision})`,
        MERGED: `Merged changes across devices (rev ${outcome.document.revision})`,
        UP_TO_DATE: `Up to date (rev ${outcome.document.revision})`
      };
      const nextExpanded = new Set(this.state.expandedFolderIds);
      for (const f of outcome.document.folders || []) nextExpanded.add(f.id);
      this.setState({
        vaultDocument: outcome.document,
        isStarterUninitialized: false,
        expandedFolderIds: nextExpanded,
        selectedConnectionId: this.state.selectedConnectionId || outcome.document.connections?.[0]?.id || null,
        driveIsSyncing: false,
        driveNeedsPermissionGrant: false,
        driveLinkedName: info?.displayName || this.state.driveLinkedName,
        driveLastSyncedEpochMs: info?.lastSyncedEpochMs || Date.now(),
        driveStatusMessage: statusMap[outcome.kind] || 'Synced'
      });
      await this._persistLocalVault(outcome.document);
      return outcome;
    } catch (err) {
      const isPermErr = err?.name === 'DrivePermissionError';
      this.setState({
        driveIsSyncing: false,
        driveNeedsPermissionGrant: isPermErr ? true : this.state.driveNeedsPermissionGrant,
        driveStatusMessage: err.message || 'Google Drive sync error'
      });
      if (!silent) throw err;
      return null;
    } finally {
      this._syncLocked = false;
      if (this._pendingSyncAfterCurrent) {
        this._pendingSyncAfterCurrent = false;
        setTimeout(() => this.triggerGoogleDriveSync({ reason: 'follow-up', silent: true }), 150);
      }
    }
  }

  // CRUD helpers
  async saveConnection(connData) {
    return await this.commitVaultMutation((doc) => {
      const idx = doc.connections.findIndex((c) => c.id === connData.id);
      const normalized = {
        id: connData.id || randomUuid(),
        parentFolderId: connData.parentFolderId || null,
        name: String(connData.name || connData.host || 'Server'),
        host: String(connData.host || ''),
        protocol: connData.protocol || 'SSH',
        osFamily: connData.osFamily || (connData.protocol === 'RDP' ? 'Windows' : 'Linux'),
        tags: Array.isArray(connData.tags) ? connData.tags : [],
        notes: String(connData.notes || ''),
        favorite: Boolean(connData.favorite),
        sortOrder: typeof connData.sortOrder === 'number' ? connData.sortOrder : doc.connections.length,
        inheritable: defaultInheritableProperties(connData.inheritable || {}),
        settings: connData.settings || defaultSettingsForProtocol(connData.protocol || 'SSH', connData.host),
        updatedAtEpochMs: Date.now()
      };
      if (idx >= 0) doc.connections[idx] = normalized;
      else doc.connections.push(normalized);
      return doc;
    });
  }

  async deleteObjectById(objectId) {
    return await this.commitVaultMutation((doc) => {
      const tomb = new Set(doc.tombstonedObjectIds || []);
      tomb.add(objectId);
      doc.tombstonedObjectIds = [...tomb];
      doc.folders = doc.folders.filter((x) => x.id !== objectId);
      doc.smartFolders = doc.smartFolders.filter((x) => x.id !== objectId);
      doc.connections = doc.connections.filter((x) => x.id !== objectId);
      doc.credentials = doc.credentials.filter((x) => x.id !== objectId);
      doc.gateways = doc.gateways.filter((x) => x.id !== objectId);
      doc.templates = doc.templates.filter((x) => x.id !== objectId);
      doc.commandTasks = doc.commandTasks.filter((x) => x.id !== objectId);
      doc.keySequenceTasks = doc.keySequenceTasks.filter((x) => x.id !== objectId);
      doc.todoItems = doc.todoItems.filter((x) => x.id !== objectId);
      return doc;
    });
  }

  async saveFolder(folderData) {
    const cleanName = String(folderData.name || 'New Folder').trim() || 'New Folder';
    const parentFolderId = folderData.parentFolderId || null;
    if (
      folderData.id === SYSTEM_CREDENTIALS_FOLDER_ID ||
      parentFolderId === SYSTEM_CREDENTIALS_FOLDER_ID ||
      (!parentFolderId && cleanName.toLowerCase() === 'credentials')
    ) {
      this.showToast('"Credentials" is reserved for the system credentials folder at the bottom of the sidebar.');
      return null;
    }
    const id = folderData.id || randomUuid();
    const res = await this.commitVaultMutation((doc) => {
      const idx = doc.folders.findIndex((f) => f.id === id);
      const folder = {
        id,
        parentFolderId,
        name: cleanName,
        description: String(folderData.description || ''),
        tags: Array.isArray(folderData.tags) ? folderData.tags : [],
        inheritable: defaultInheritableProperties(folderData.inheritable || {}),
        sortOrder: idx >= 0 ? doc.folders[idx].sortOrder : doc.folders.length,
        updatedAtEpochMs: Date.now()
      };
      if (idx >= 0) doc.folders[idx] = folder;
      else doc.folders.push(folder);
      return doc;
    });
    const exp = new Set(this.state.expandedFolderIds);
    exp.add(id);
    this.setState({ expandedFolderIds: exp });
    return res;
  }

  async saveCredential(credData) {
    const id = credData.id || randomUuid();
    const res = await this.commitVaultMutation((doc) => {
      doc.credentials = doc.credentials || [];
      const idx = doc.credentials.findIndex((c) => c.id === id);
      const prev = idx >= 0 ? doc.credentials[idx] : null;
      const privateKeyPem = String(credData.privateKeyPem ?? prev?.privateKeyPem ?? '').trim();
      const credential = {
        id,
        parentFolderId: null,
        name: String(credData.name || prev?.name || 'Credential').trim() || 'Credential',
        sourceKind: privateKeyPem ? 'SSH_KEY_PAIR' : (credData.sourceKind || prev?.sourceKind || 'VAULT_INTERNAL'),
        username: String(credData.username ?? prev?.username ?? '').trim(),
        domain: String(credData.domain ?? prev?.domain ?? '').trim(),
        secretPassword: credData.secretPassword !== undefined ? String(credData.secretPassword) : (prev?.secretPassword || ''),
        privateKeyPem,
        publicKeyOpenSsh: String(credData.publicKeyOpenSsh ?? prev?.publicKeyOpenSsh ?? ''),
        keyFingerprintSha256: String(credData.keyFingerprintSha256 ?? prev?.keyFingerprintSha256 ?? ''),
        keyPassphrase: String(credData.keyPassphrase ?? prev?.keyPassphrase ?? ''),
        externalReferenceUri: String(credData.externalReferenceUri ?? prev?.externalReferenceUri ?? ''),
        fido2CredentialIdBase64: String(credData.fido2CredentialIdBase64 ?? prev?.fido2CredentialIdBase64 ?? ''),
        totpSecretBase32: String(credData.totpSecretBase32 ?? prev?.totpSecretBase32 ?? '').trim(),
        sortOrder: typeof credData.sortOrder === 'number' ? credData.sortOrder : (prev?.sortOrder ?? doc.credentials.length),
        updatedAtEpochMs: Date.now()
      };
      if (idx >= 0) doc.credentials[idx] = credential;
      else doc.credentials.push(credential);
      return doc;
    });
    this.setState({ isSystemCredentialsExpanded: true, selectedCredentialId: id });
    return res;
  }

  async moveConnectionToFolder(connectionId, targetFolderId) {
    if (targetFolderId === SYSTEM_CREDENTIALS_FOLDER_ID) {
      this.showToast('The Credentials system folder only holds credentials.');
      return null;
    }
    return await this.commitVaultMutation((doc) => {
      const conn = doc.connections.find((c) => c.id === connectionId);
      if (conn) {
        conn.parentFolderId = targetFolderId || null;
        conn.updatedAtEpochMs = Date.now();
      }
      return doc;
    });
  }

  async moveFolderToParent(folderId, newParentFolderId) {
    if (folderId === newParentFolderId) return;
    if (folderId === SYSTEM_CREDENTIALS_FOLDER_ID || newParentFolderId === SYSTEM_CREDENTIALS_FOLDER_ID) {
      this.showToast('The Credentials system folder only holds credentials.');
      return null;
    }
    return await this.commitVaultMutation((doc) => {
      const subtree = resolveSubtreeFolderIds(folderId, doc);
      if (newParentFolderId && subtree.has(newParentFolderId)) return doc;
      const fld = doc.folders.find((f) => f.id === folderId);
      if (fld) {
        fld.parentFolderId = newParentFolderId || null;
        fld.updatedAtEpochMs = Date.now();
      }
      return doc;
    });
  }

  async importVaultFileBytes(bytes, fileName, passphrase = '', placementMode = 'MERGE', subfolderName = '') {
    const summary = await importEnterpriseVaultBytes(bytes, fileName, passphrase);
    const backupCopy = structuredClone(this.state.vaultDocument);
    const merged = applyVaultImportPlacement(
      this.state.vaultDocument,
      summary.document,
      placementMode,
      subfolderName || summary.document.name
    );
    const bumped = bumpVaultRevision(merged, this.deviceId);
    this.setState({
      preImportBackupDoc: backupCopy,
      vaultDocument: bumped,
      isStarterUninitialized: false,
      expandedFolderIds: new Set((bumped.folders || []).map((f) => f.id))
    });
    await this._persistLocalVault(bumped);
    if (this.driveBridge.isLinked() && this._masterPassphrase) {
      this._scheduleDebouncedDriveSync();
    }
    return summary;
  }

  async undoLastImport() {
    if (!this.state.preImportBackupDoc) return false;
    const restored = bumpVaultRevision(this.state.preImportBackupDoc, this.deviceId);
    this.setState({
      vaultDocument: restored,
      preImportBackupDoc: null
    });
    await this._persistLocalVault(restored);
    if (this.driveBridge.isLinked() && this._masterPassphrase) {
      this._scheduleDebouncedDriveSync();
    }
    return true;
  }

  async exportCurrentVault(format = 'NXTS', passphrase = '') {
    const pw = passphrase || this._masterPassphrase || DEFAULT_TRANSPARENT_PASSPHRASE;
    return await exportVaultDocument(this.state.vaultDocument, format, pw, this.kdfParams);
  }

  resolveProfileForConnection(connOrId) {
    const doc = this.state.vaultDocument;
    const conn = typeof connOrId === 'string' ? doc.connections.find((c) => c.id === connOrId) : connOrId;
    if (!conn) return null;
    return resolveEffectiveConnectionProfile(conn, doc);
  }

  visibleConnectionsForSmartFolder(smartFolderId) {
    const sf = (this.state.vaultDocument.smartFolders || []).find((s) => s.id === smartFolderId);
    if (!sf) return [];
    return evaluateSmartFolder(sf, this.state.vaultDocument);
  }

  appendAuditLog(category, message) {
    const entry = {
      id: randomUuid(),
      timestamp: new Date().toLocaleTimeString(),
      category,
      message
    };
    this.setState({
      auditLogs: [entry, ...(this.state.auditLogs || [])].slice(0, 120)
    });
  }

  async addTodoItem(title) {
    const clean = String(title || '').trim();
    if (!clean) return;
    await this.commitVaultMutation((doc) => {
      doc.todoItems = doc.todoItems || [];
      doc.todoItems.push({
        id: randomUuid(),
        title: clean,
        completed: false,
        targetObjectId: null,
        updatedAtEpochMs: Date.now()
      });
      return doc;
    });
  }

  async toggleTodoItem(todoId) {
    await this.commitVaultMutation((doc) => {
      const item = (doc.todoItems || []).find((t) => t.id === todoId);
      if (item) {
        item.completed = !item.completed;
        item.updatedAtEpochMs = Date.now();
      }
      return doc;
    });
  }

  async saveSmartFolder(sfData) {
    const id = sfData.id || randomUuid();
    await this.commitVaultMutation((doc) => {
      doc.smartFolders = doc.smartFolders || [];
      const idx = doc.smartFolders.findIndex((s) => s.id === id);
      const entry = {
        id,
        name: String(sfData.name || 'Smart Folder').trim() || 'Smart Folder',
        filter: sfData.filter || {
          includeProtocols: [],
          requiredTags: [],
          hostPatternRegex: ''
        },
        updatedAtEpochMs: Date.now()
      };
      if (idx >= 0) doc.smartFolders[idx] = entry;
      else doc.smartFolders.push(entry);
      return doc;
    });
    const exp = new Set(this.state.expandedSmartFolderIds);
    exp.add(id);
    this.setState({ expandedSmartFolderIds: exp, selectedSmartFolderId: id });
  }

  async resetVaultDatabase() {
    await this.disconnectGoogleDriveSync();
    const fresh = createStarterVault(this.deviceId);
    this._masterPassphrase = DEFAULT_TRANSPARENT_PASSPHRASE;
    this._autoBioDismissedInFocus = false;
    await idbSet('vault.sealed', null);
    await idbSet('vault.unencryptedDraft', null);
    await idbSet('vault.webauthnBiometric', null);
    this.setState({
      vaultDocument: fresh,
      hasEncryptedLocalVault: false,
      hasCustomPassphrase: false,
      biometricUnlockEnrolled: false,
      isBiometricPromptActive: false,
      isVaultLocked: false,
      isStarterUninitialized: true,
      tabs: [],
      focusedTabId: null,
      selectedConnectionId: null,
      selectedFolderId: null,
      selectedSmartFolderId: null,
      selectedCredentialId: null,
      expandedFolderIds: new Set(),
      expandedSmartFolderIds: new Set(),
      isSystemCredentialsExpanded: true,
      toastMessage: 'NexusTS database has been reset to a clean empty vault.'
    });
  }
}
