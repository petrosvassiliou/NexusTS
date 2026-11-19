// Headless Reactive Workspace Store for NexusTS ChromeOS PWA / Isolated Web App.
// Enforces 1:1 parity with `NexusWorkspaceViewModel.kt` (Android) and `WorkspaceStore.swift` (macOS),
// including v0.8.14 sidebar active-tab switching, unified 8-tab Settings, and automatic Google Drive sync.

import { detectInitialLocale, resolveStrings } from './i18n.js';
import { applyVaultImportPlacement, exportVaultDocument, importEnterpriseVaultBytes } from './importers-exporters.js';
import { DEFAULT_KDF_PARAMS, sealVaultDocument, unsealVaultDocument } from './nxts-crypto.js';
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
    this._masterPassphrase = '';

    this.state = {
      initialized: false,
      locale: detectInitialLocale(),
      hasEncryptedLocalVault: false,
      isVaultLocked: false,
      isStarterUninitialized: true,
      vaultUnlockError: '',
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
      isFullScreenSession: false,
      remoteKeyCaptureEnabled: false,
      broadcastInputEnabled: false,

      // Sidebar state
      sidebarFilterQuery: '',
      sidebarQuickFilter: 'ALL', // 'ALL' | 'FAVORITES' | 'RECENTS'
      expandedFolderIds: new Set(),
      selectedConnectionId: null,
      selectedFolderId: null,
      selectedSmartFolderId: null,

      // Tiling & active tabs
      tilingLayout: 'SINGLE', // 'SINGLE' | 'SPLIT_H' | 'SPLIT_V' | 'QUAD'
      tabs: [], // [{ tabId, connectionId, title, protocol, status, detachedWindow }]
      focusedTabId: null,

      // Google Drive Sync state
      driveLinkedName: '',
      driveIsSyncing: false,
      driveStatusMessage: '',
      driveLastSyncedEpochMs: 0,

      // Transport & update status
      directSocketsAvailable: typeof globalThis.TCPSocket === 'function',
      localRelayReachable: false,
      localRelayUrl: 'ws://127.0.0.1:44890',
      localRelayHttpUrl: 'http://127.0.0.1:44890',
      appVersion: '0.9.0',
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
    if (typeof prefs.idleAutoLockMinutes === 'number') this.state.idleAutoLockMinutes = prefs.idleAutoLockMinutes;
    if (prefs.localRelayUrl) {
      this.state.localRelayUrl = prefs.localRelayUrl;
      this.state.localRelayHttpUrl = prefs.localRelayUrl.replace(/^ws/i, 'http');
    }

    const linkedMeta = await this.driveBridge.restorePersistedLink();
    if (linkedMeta) {
      this.state.driveLinkedName = linkedMeta.displayName || 'NexusTS-Vault.nxts';
      this.state.driveLastSyncedEpochMs = linkedMeta.lastSyncedEpochMs || 0;
    }

    const sealed = await loadEncryptedVaultFromOfflineStore();
    if (sealed && sealed.length > 0) {
      this.state.hasEncryptedLocalVault = true;
      this.state.isVaultLocked = true;
      this.state.isStarterUninitialized = false;
    } else {
      // Load unencrypted local draft if user hasn't set a master passphrase yet, or fresh starter vault
      const draft = await idbGet('vault.unencryptedDraft');
      if (draft && draft.documentId) {
        const sanitized = sanitizeVaultDocument(draft);
        this.state.vaultDocument = sanitized;
        this.state.isStarterUninitialized = false;
        this.state.expandedFolderIds = new Set(sanitized.folders.map((f) => f.id));
        this.state.selectedConnectionId = sanitized.connections[0]?.id || null;
      }
    }

    this.state.initialized = true;
    this._emit();

    // Automatic startup sync if vault is unlocked and a passphrase/link exists
    if (!this.state.isVaultLocked && this.driveBridge.isLinked() && this._masterPassphrase) {
      await this.triggerGoogleDriveSync({ reason: 'startup', silent: true });
    }
    this._startBackgroundWatchers();
  }

  _startBackgroundWatchers() {
    if (typeof globalThis.window === 'undefined') return;
    if (this._remoteWatchTimer) clearInterval(this._remoteWatchTimer);

    // Check every 6s for external Google Drive file updates + idle auto-lock
    this._remoteWatchTimer = setInterval(async () => {
      if (
        this.state.idleAutoLockMinutes > 0 &&
        !this.state.isVaultLocked &&
        this._masterPassphrase &&
        Date.now() - this.state.lastActivityEpochMs >= this.state.idleAutoLockMinutes * 60000
      ) {
        this.lockVault();
        return;
      }
      if (!this.state.isVaultLocked && this.driveBridge.isLinked() && this._masterPassphrase && !this._syncLocked) {
        const changed = await this.driveBridge.hasRemoteContentChanged();
        if (changed) {
          await this.triggerGoogleDriveSync({ reason: 'remote-change', silent: true });
        }
      }
    }, 6000);

    globalThis.window.addEventListener('focus', async () => {
      if (!this.state.isVaultLocked && this.driveBridge.isLinked() && this._masterPassphrase && !this._syncLocked) {
        if (await this.driveBridge.hasRemoteContentChanged()) {
          await this.triggerGoogleDriveSync({ reason: 'window-focus', silent: true });
        }
      }
    });
  }

  async savePreferences() {
    await idbSet('workspace.prefs', {
      locale: this.state.locale,
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
        focusedTabId: existingTab.tabId,
        activeDestination: 'connections'
      });
      return true;
    }

    this.setState({
      selectedConnectionId: connectionId,
      selectedFolderId: null,
      selectedSmartFolderId: null
    });
    return false;
  }

  toggleFolderExpanded(folderId) {
    const next = new Set(this.state.expandedFolderIds);
    if (next.has(folderId)) next.delete(folderId);
    else next.add(folderId);
    this.setState({ expandedFolderIds: next, selectedFolderId: folderId, selectedSmartFolderId: null });
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

  async setMasterPassphraseAndEncrypt(newPassphrase) {
    const clean = String(newPassphrase || '');
    if (clean.length < 4) throw new Error('Passphrase must be at least 4 characters');
    this._masterPassphrase = clean;
    const sealed = await sealVaultDocument(this.state.vaultDocument, clean, this.kdfParams);
    await saveEncryptedVaultToOfflineStore(sealed, {
      documentId: this.state.vaultDocument.documentId,
      revision: this.state.vaultDocument.revision
    });
    await idbSet('vault.unencryptedDraft', null);
    this.setState({
      hasEncryptedLocalVault: true,
      isVaultLocked: false,
      isStarterUninitialized: false,
      vaultUnlockError: ''
    });
    if (this.driveBridge.isLinked()) {
      this._scheduleDebouncedDriveSync();
    }
  }

  async unlockVault(passphrase) {
    try {
      const sealed = await loadEncryptedVaultFromOfflineStore();
      if (!sealed) {
        this._masterPassphrase = passphrase;
        this.setState({ isVaultLocked: false, vaultUnlockError: '' });
        return true;
      }
      const doc = await unsealVaultDocument(sealed, passphrase);
      this._masterPassphrase = passphrase;
      this.setState({
        vaultDocument: doc,
        isVaultLocked: false,
        isStarterUninitialized: false,
        vaultUnlockError: '',
        expandedFolderIds: new Set((doc.folders || []).map((f) => f.id)),
        selectedConnectionId: doc.connections[0]?.id || null
      });
      // Automatic Google Drive sync immediately upon unlock!
      if (this.driveBridge.isLinked()) {
        await this.triggerGoogleDriveSync({ reason: 'unlock', silent: true });
      }
      return true;
    } catch (err) {
      this.setState({ vaultUnlockError: err.message || 'Invalid passphrase' });
      return false;
    }
  }

  lockVault() {
    if (!this.state.hasEncryptedLocalVault && !this._masterPassphrase) {
      this.openSettingsTab('security');
      this.showToast('Set a Master Passphrase in Security settings first to lock your vault.');
      return;
    }
    this._masterPassphrase = '';
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
    if (triggerCloudSync && this.driveBridge.isLinked() && this._masterPassphrase) {
      this._scheduleDebouncedDriveSync();
    }
    return bumped;
  }

  async _persistLocalVault(doc) {
    if (this._masterPassphrase) {
      const sealed = await sealVaultDocument(doc, this._masterPassphrase, this.kdfParams);
      await saveEncryptedVaultToOfflineStore(sealed, {
        documentId: doc.documentId,
        revision: doc.revision
      });
      this.state.hasEncryptedLocalVault = true;
    } else {
      await idbSet('vault.unencryptedDraft', doc);
    }
  }

  _scheduleDebouncedDriveSync() {
    if (this._saveSyncTimer) clearTimeout(this._saveSyncTimer);
    this._saveSyncTimer = setTimeout(() => {
      this.triggerGoogleDriveSync({ reason: 'local-save', silent: true });
    }, 900);
  }

  async linkGoogleDriveTransport(transportOrHandle, rawHandle = null, passphraseIfNeeded = '') {
    if (passphraseIfNeeded && !this._masterPassphrase) {
      this._masterPassphrase = passphraseIfNeeded;
    }
    if (!this._masterPassphrase) {
      throw new Error('PassphraseRequired');
    }
    const meta = await this.driveBridge.linkTransport(
      transportOrHandle,
      rawHandle,
      this.state.isStarterUninitialized ? '' : this.state.vaultDocument.documentId
    );
    this.setState({
      driveLinkedName: meta.displayName,
      driveStatusMessage: 'Linked — syncing…'
    });
    return await this.triggerGoogleDriveSync({ reason: 'initial-link', allowPermissionPrompt: true });
  }

  async disconnectGoogleDriveSync() {
    await this.driveBridge.unlink();
    this.setState({
      driveLinkedName: '',
      driveStatusMessage: '',
      driveLastSyncedEpochMs: 0,
      driveIsSyncing: false
    });
  }

  async triggerGoogleDriveSync({ reason = 'manual', silent = false, allowPermissionPrompt = false } = {}) {
    if (!this.driveBridge.isLinked()) return null;
    if (!this._masterPassphrase) {
      if (!silent) this.setState({ driveStatusMessage: 'Unlock or set a vault passphrase to sync encrypted .nxts.' });
      return null;
    }
    if (this._syncLocked) {
      this._pendingSyncAfterCurrent = true;
      return null;
    }

    this._syncLocked = true;
    this.setState({ driveIsSyncing: true });
    try {
      const outcome = await this.driveBridge.synchronizeLinkedVault(
        this.state.vaultDocument,
        this._masterPassphrase,
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
      this.setState({
        vaultDocument: outcome.document,
        isStarterUninitialized: false,
        driveIsSyncing: false,
        driveLinkedName: info?.displayName || this.state.driveLinkedName,
        driveLastSyncedEpochMs: info?.lastSyncedEpochMs || Date.now(),
        driveStatusMessage: statusMap[outcome.kind] || 'Synced'
      });
      await this._persistLocalVault(outcome.document);
      return outcome;
    } catch (err) {
      this.setState({
        driveIsSyncing: false,
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
    const id = folderData.id || randomUuid();
    const res = await this.commitVaultMutation((doc) => {
      const idx = doc.folders.findIndex((f) => f.id === id);
      const folder = {
        id,
        parentFolderId: folderData.parentFolderId || null,
        name: String(folderData.name || 'New Folder'),
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

  async moveConnectionToFolder(connectionId, targetFolderId) {
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
    const pw = passphrase || this._masterPassphrase;
    if (format === 'NXTS' && !pw) {
      throw new Error('Enter a passphrase to encrypt the exported .nxts vault.');
    }
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
}
