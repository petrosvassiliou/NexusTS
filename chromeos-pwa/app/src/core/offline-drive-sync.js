// 100% Offline Vault Storage (`IndexedDB` + `OPFS`) & ChromeOS File System Access Google Drive Sync Bridge.
//
// On ChromeOS, Personal Google Drive ("My Drive" / "Meine Ablage") and Shared Team Drives are mounted
// natively inside the ChromeOS Files app — including offline-pinned `.nxts` files!
// Persisting the `FileSystemFileHandle` in `IndexedDB` enables 100% offline-capable & automatic sync:
// 1. Automatically on app startup / vault unlock
// 2. Automatically on local vault edits (debounced 900ms)
// 3. Automatically whenever remote/external `.nxts` file changes are detected (`lastModified` + SHA-256 watcher + window focus)

import { compareVectorClocks, mergeVaultDocuments, VectorClockRelation } from './conflict-resolver.js';
import { DEFAULT_KDF_PARAMS, sealVaultDocument, sha256Hex, unsealVaultDocument } from './nxts-crypto.js';

const IDB_NAME = 'nexusts-chromeos-offline-v1';
const IDB_VERSION = 1;
const STORE_KV = 'kv';
const STORE_HANDLES = 'handles';

function openIdb() {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(IDB_NAME, IDB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_KV)) db.createObjectStore(STORE_KV);
      if (!db.objectStoreNames.contains(STORE_HANDLES)) db.createObjectStore(STORE_HANDLES);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

// In-memory fallback map when running inside headless Node unit tests
const memoryKv = new Map();
const memoryHandles = new Map();

export async function idbGet(key, storeName = STORE_KV) {
  const db = await openIdb();
  if (!db) return (storeName === STORE_HANDLES ? memoryHandles : memoryKv).get(key) ?? null;
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, 'readonly');
    const req = tx.objectStore(storeName).get(key);
    req.onsuccess = () => resolve(req.result ?? null);
    req.onerror = () => reject(req.error);
  });
}

export async function idbSet(key, value, storeName = STORE_KV) {
  const db = await openIdb();
  if (!db) {
    (storeName === STORE_HANDLES ? memoryHandles : memoryKv).set(key, value);
    return;
  }
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, 'readwrite');
    tx.objectStore(storeName).put(value, key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

export async function idbDelete(key, storeName = STORE_KV) {
  const db = await openIdb();
  if (!db) {
    (storeName === STORE_HANDLES ? memoryHandles : memoryKv).delete(key);
    return;
  }
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, 'readwrite');
    tx.objectStore(storeName).delete(key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

export async function requestPersistentOfflineStorage() {
  try {
    if (globalThis.navigator?.storage?.persist) {
      await globalThis.navigator.storage.persist();
    }
  } catch {
    // Ignore in restricted contexts
  }
}

export async function saveEncryptedVaultToOfflineStore(sealedBytes, meta = {}) {
  await idbSet('vault.sealed', sealedBytes);
  await idbSet('vault.meta', {
    ...meta,
    savedAtEpochMs: Date.now()
  });
  // Mirror to Origin Private File System (OPFS) when available on ChromeOS for crash-safe durability
  try {
    if (globalThis.navigator?.storage?.getDirectory) {
      const root = await globalThis.navigator.storage.getDirectory();
      const fh = await root.getFileHandle('nexusts-vault.nxts', { create: true });
      const writable = await fh.createWritable();
      await writable.write(sealedBytes);
      await writable.close();
    }
  } catch {
    // IndexedDB already persisted
  }
}

export async function loadEncryptedVaultFromOfflineStore() {
  const bytes = await idbGet('vault.sealed');
  if (bytes instanceof Uint8Array && bytes.length > 0) return bytes;
  if (bytes instanceof ArrayBuffer && bytes.byteLength > 0) return new Uint8Array(bytes);
  try {
    if (globalThis.navigator?.storage?.getDirectory) {
      const root = await globalThis.navigator.storage.getDirectory();
      const fh = await root.getFileHandle('nexusts-vault.nxts');
      const file = await fh.getFile();
      const buf = await file.arrayBuffer();
      if (buf.byteLength > 0) return new Uint8Array(buf);
    }
  } catch {
    // No OPFS vault yet
  }
  return null;
}

/**
 * Transport interface for reading/writing a linked `.nxts` file in ChromeOS Google Drive / Files app
 * (or injected mock transport in automated E2E tests).
 */
export class FileHandleVaultTransport {
  constructor(fileHandle) {
    this.fileHandle = fileHandle;
  }

  get displayName() {
    return this.fileHandle?.name || 'NexusTS-Vault.nxts';
  }

  async ensurePermission(mode = 'readwrite', allowPrompt = false) {
    if (!this.fileHandle?.queryPermission) return true;
    const state = await this.fileHandle.queryPermission({ mode });
    if (state === 'granted') return true;
    if (!allowPrompt || !this.fileHandle.requestPermission) return false;
    const requested = await this.fileHandle.requestPermission({ mode });
    return requested === 'granted';
  }

  async readBytes(allowPrompt = false) {
    const ok = await this.ensurePermission('read', allowPrompt);
    if (!ok) throw new Error('File permission needs one-click re-grant in ChromeOS Files.');
    const file = await this.fileHandle.getFile();
    const buf = await file.arrayBuffer();
    return {
      bytes: new Uint8Array(buf),
      lastModified: file.lastModified || 0
    };
  }

  async writeBytes(bytes, allowPrompt = false) {
    const ok = await this.ensurePermission('readwrite', allowPrompt);
    if (!ok) throw new Error('Write permission required for linked Google Drive .nxts file.');
    const writable = await this.fileHandle.createWritable();
    await writable.write(bytes);
    await writable.close();
  }
}

function alignLocalClockWithoutBump(doc, deviceId) {
  const clock = { ...(doc.vectorClock || {}) };
  if (!clock[deviceId]) clock[deviceId] = Math.max(1, Number(doc.revision || 1));
  return { ...doc, vectorClock: clock };
}

/**
 * Automatic Google Drive / ChromeOS File-System Sync Bridge (1:1 parity with `SafLiveGoogleDriveBridge.kt`
 * and `MacCloudBookmarkSyncBridge`).
 */
export class ChromeOSDriveSyncBridge {
  constructor({ kdfParams = DEFAULT_KDF_PARAMS, deviceId = 'ChromeOS-PWA' } = {}) {
    this.kdfParams = kdfParams;
    this.deviceId = deviceId;
    this.transport = null;
    this.syncMeta = null;
    this._syncInFlight = false;
    this._pendingFollowUp = false;
  }

  async restorePersistedLink() {
    const handle = await idbGet('drive.fileHandle', STORE_HANDLES);
    const meta = await idbGet('drive.syncMeta', STORE_KV);
    if (handle) {
      this.transport = handle instanceof FileHandleVaultTransport ? handle : new FileHandleVaultTransport(handle);
      this.syncMeta = meta || {
        displayName: this.transport.displayName,
        lastSyncedRevision: 0,
        lastSyncedRemoteSha256: '',
        lastSyncedEpochMs: 0,
        linkedDocumentId: ''
      };
      return this.syncMeta;
    }
    return null;
  }

  async linkTransport(transportOrHandle, rawHandleForPersistence = null, documentId = '') {
    const transport =
      transportOrHandle instanceof FileHandleVaultTransport || typeof transportOrHandle.readBytes === 'function'
        ? transportOrHandle
        : new FileHandleVaultTransport(transportOrHandle);
    this.transport = transport;
    this.syncMeta = {
      displayName: transport.displayName || 'NexusTS-Vault.nxts',
      lastSyncedRevision: 0,
      lastSyncedRemoteSha256: '',
      lastSyncedEpochMs: 0,
      linkedDocumentId: documentId || ''
    };
    await idbSet('drive.fileHandle', rawHandleForPersistence || transportOrHandle, STORE_HANDLES);
    await idbSet('drive.syncMeta', this.syncMeta, STORE_KV);
    return this.syncMeta;
  }

  async unlink() {
    this.transport = null;
    this.syncMeta = null;
    await idbDelete('drive.fileHandle', STORE_HANDLES);
    await idbDelete('drive.syncMeta', STORE_KV);
  }

  isLinked() {
    return Boolean(this.transport && this.syncMeta);
  }

  getLinkedInfo() {
    return this.syncMeta ? { ...this.syncMeta } : null;
  }

  async _recordSuccess(revision, remoteSha, docId) {
    this.syncMeta = {
      displayName: this.transport?.displayName || this.syncMeta?.displayName || 'NexusTS-Vault.nxts',
      lastSyncedRevision: Number(revision || 1),
      lastSyncedRemoteSha256: remoteSha,
      lastSyncedEpochMs: Date.now(),
      linkedDocumentId: docId || ''
    };
    await idbSet('drive.syncMeta', this.syncMeta, STORE_KV);
  }

  async hasRemoteContentChanged() {
    if (!this.isLinked()) return false;
    try {
      const { bytes } = await this.transport.readBytes(false);
      if (!bytes || bytes.length === 0) return false;
      const currentSha = await sha256Hex(bytes);
      return !this.syncMeta.lastSyncedRemoteSha256 || currentSha !== this.syncMeta.lastSyncedRemoteSha256;
    } catch {
      return false;
    }
  }

  async synchronizeLinkedVault(
    currentDocument,
    passphraseString,
    { localIsUninitializedStarter = false, allowPermissionPrompt = false } = {}
  ) {
    if (!this.isLinked()) {
      throw new Error('No Google Drive .nxts vault file is linked yet.');
    }

    const { bytes: remoteBytes } = await this.transport.readBytes(allowPermissionPrompt);

    // Case 1: Newly created empty .nxts file in Google Drive -> push current vault
    if (!remoteBytes || remoteBytes.length === 0) {
      const aligned = alignLocalClockWithoutBump(currentDocument, this.deviceId);
      const sealed = await sealVaultDocument(aligned, passphraseString, this.kdfParams);
      await this.transport.writeBytes(sealed, allowPermissionPrompt);
      const sha = await sha256Hex(sealed);
      await this._recordSuccess(aligned.revision, sha, aligned.documentId);
      return { kind: 'PUSHED', document: aligned, bytesWritten: sealed.length, conflicts: [] };
    }

    const remoteSha = await sha256Hex(remoteBytes);
    const sameKnownDocId =
      !localIsUninitializedStarter &&
      Boolean(this.syncMeta.linkedDocumentId) &&
      currentDocument.documentId === this.syncMeta.linkedDocumentId;

    // Case 2: Fast-path when remote hash matches last synced hash
    if (sameKnownDocId && this.syncMeta.lastSyncedRemoteSha256 && remoteSha === this.syncMeta.lastSyncedRemoteSha256) {
      if (Number(currentDocument.revision || 1) > Number(this.syncMeta.lastSyncedRevision || 0)) {
        const aligned = alignLocalClockWithoutBump(currentDocument, this.deviceId);
        const sealed = await sealVaultDocument(aligned, passphraseString, this.kdfParams);
        await this.transport.writeBytes(sealed, allowPermissionPrompt);
        const newSha = await sha256Hex(sealed);
        await this._recordSuccess(aligned.revision, newSha, aligned.documentId);
        return { kind: 'PUSHED', document: aligned, bytesWritten: sealed.length, conflicts: [] };
      }
      return { kind: 'UP_TO_DATE', document: currentDocument, conflicts: [] };
    }

    // Decrypt remote Google Drive .nxts container
    const remoteDoc = await unsealVaultDocument(remoteBytes, passphraseString);

    // Cold-start / uninitialized starter guard: pull remote cleanly without polluting it!
    if (localIsUninitializedStarter || currentDocument.documentId !== remoteDoc.documentId) {
      await this._recordSuccess(remoteDoc.revision, remoteSha, remoteDoc.documentId);
      return { kind: 'PULLED', document: remoteDoc, conflicts: [] };
    }

    // Remote changed while local had zero unsynced edits -> clean fast-forward pull!
    if (
      this.syncMeta.lastSyncedRemoteSha256 &&
      remoteSha !== this.syncMeta.lastSyncedRemoteSha256 &&
      Number(currentDocument.revision || 1) <= Number(this.syncMeta.lastSyncedRevision || 0)
    ) {
      await this._recordSuccess(remoteDoc.revision, remoteSha, remoteDoc.documentId);
      return { kind: 'PULLED', document: remoteDoc, conflicts: [] };
    }

    const relation = compareVectorClocks(currentDocument.vectorClock, remoteDoc.vectorClock);
    if (relation === VectorClockRelation.IDENTICAL || relation === VectorClockRelation.REMOTE_AHEAD) {
      await this._recordSuccess(remoteDoc.revision, remoteSha, remoteDoc.documentId);
      return { kind: 'PULLED', document: remoteDoc, conflicts: [] };
    }
    if (relation === VectorClockRelation.LOCAL_AHEAD) {
      const sealed = await sealVaultDocument(currentDocument, passphraseString, this.kdfParams);
      await this.transport.writeBytes(sealed, allowPermissionPrompt);
      const newSha = await sha256Hex(sealed);
      await this._recordSuccess(currentDocument.revision, newSha, currentDocument.documentId);
      return { kind: 'PUSHED', document: currentDocument, bytesWritten: sealed.length, conflicts: [] };
    }

    // Concurrent edits across devices -> 3-way object-level VectorClock merge!
    const { mergedDocument, detectedConflicts } = mergeVaultDocuments(currentDocument, remoteDoc, this.deviceId);
    const sealed = await sealVaultDocument(mergedDocument, passphraseString, this.kdfParams);
    await this.transport.writeBytes(sealed, allowPermissionPrompt);
    const newSha = await sha256Hex(sealed);
    await this._recordSuccess(mergedDocument.revision, newSha, mergedDocument.documentId);
    return { kind: 'MERGED', document: mergedDocument, conflicts: detectedConflicts };
  }
}
