// 3-Way Vector-Clock & Object-Level LWW Delta Merger (`VaultDeltaConflictResolver`).
// 100% parity with `VaultDeltaConflictResolver.kt` & `CloudSyncEngine.swift`.

import { sanitizeVaultDocument } from './vault-models.js';

export const VectorClockRelation = Object.freeze({
  IDENTICAL: 'IDENTICAL',
  LOCAL_AHEAD: 'LOCAL_AHEAD',
  REMOTE_AHEAD: 'REMOTE_AHEAD',
  CONCURRENT_DIVERGENCE: 'CONCURRENT_DIVERGENCE'
});

export function compareVectorClocks(localClock = {}, remoteClock = {}) {
  const actors = new Set([...Object.keys(localClock), ...Object.keys(remoteClock)]);
  let localGreater = false;
  let remoteGreater = false;
  for (const actor of actors) {
    const l = Number(localClock[actor] || 0);
    const r = Number(remoteClock[actor] || 0);
    if (l > r) localGreater = true;
    if (r > l) remoteGreater = true;
  }
  if (!localGreater && !remoteGreater) return VectorClockRelation.IDENTICAL;
  if (localGreater && !remoteGreater) return VectorClockRelation.LOCAL_AHEAD;
  if (remoteGreater && !localGreater) return VectorClockRelation.REMOTE_AHEAD;
  return VectorClockRelation.CONCURRENT_DIVERGENCE;
}

function stableJsonEqual(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function mergeById(kind, localList = [], remoteList = [], tombstones, nameField, conflictsOut) {
  const localMap = new Map(localList.map((x) => [x.id, x]));
  const remoteMap = new Map(remoteList.map((x) => [x.id, x]));
  const allIds = [...new Set([...localMap.keys(), ...remoteMap.keys()])];
  const merged = [];

  for (const id of allIds) {
    if (tombstones.has(id)) continue;
    const l = localMap.get(id);
    const r = remoteMap.get(id);
    if (l && !r) {
      merged.push(l);
    } else if (!l && r) {
      merged.push(r);
    } else if (l && r) {
      if (stableJsonEqual(l, r)) {
        merged.push(l);
      } else {
        const lt = Number(l.updatedAtEpochMs || 0);
        const rt = Number(r.updatedAtEpochMs || 0);
        conflictsOut.push({
          objectId: id,
          objectKind: kind,
          localName: String(l[nameField] || id),
          remoteName: String(r[nameField] || id),
          localUpdatedEpochMs: lt,
          remoteUpdatedEpochMs: rt
        });
        merged.push(rt > lt ? r : l);
      }
    }
  }
  return merged;
}

export function mergeVaultDocuments(localDoc, remoteDoc, localDeviceId = 'ChromeOS-PWA') {
  const tombstones = new Set([
    ...(localDoc.tombstonedObjectIds || []),
    ...(remoteDoc.tombstonedObjectIds || [])
  ]);
  const conflicts = [];

  const mergedFolders = mergeById('Folder', localDoc.folders, remoteDoc.folders, tombstones, 'name', conflicts);
  const mergedSmart = mergeById('SmartFolder', localDoc.smartFolders, remoteDoc.smartFolders, tombstones, 'name', conflicts);
  const mergedDynamic = mergeById('DynamicFolder', localDoc.dynamicFolders, remoteDoc.dynamicFolders, tombstones, 'name', conflicts);
  const mergedConnections = mergeById('Connection', localDoc.connections, remoteDoc.connections, tombstones, 'name', conflicts);
  const mergedCredentials = mergeById('Credential', localDoc.credentials, remoteDoc.credentials, tombstones, 'name', conflicts);
  const mergedGateways = mergeById('SecureGateway', localDoc.gateways, remoteDoc.gateways, tombstones, 'name', conflicts);
  const mergedCmdTasks = mergeById('CommandTask', localDoc.commandTasks, remoteDoc.commandTasks, tombstones, 'name', conflicts);
  const mergedKeyTasks = mergeById('KeySequenceTask', localDoc.keySequenceTasks, remoteDoc.keySequenceTasks, tombstones, 'name', conflicts);
  const mergedTemplates = mergeById('ConnectionTemplate', localDoc.templates, remoteDoc.templates, tombstones, 'name', conflicts);
  const mergedTodos = mergeById('VaultTodoItem', localDoc.todoItems, remoteDoc.todoItems, tombstones, 'title', conflicts);

  const mergedRecents = [...new Set([...(localDoc.recentConnectionIds || []), ...(remoteDoc.recentConnectionIds || [])])]
    .filter((id) => !tombstones.has(id))
    .slice(0, 12);

  const actors = new Set([
    ...Object.keys(localDoc.vectorClock || {}),
    ...Object.keys(remoteDoc.vectorClock || {}),
    localDeviceId
  ]);
  const nextVectorClock = {};
  for (const actor of actors) {
    const base = Math.max(Number(localDoc.vectorClock?.[actor] || 0), Number(remoteDoc.vectorClock?.[actor] || 0));
    nextVectorClock[actor] = actor === localDeviceId ? base + 1 : base;
  }

  const nextRev = Math.max(Number(localDoc.revision || 1), Number(remoteDoc.revision || 1)) + 1;
  const mergedDocument = sanitizeVaultDocument({
    ...remoteDoc,
    ...localDoc,
    documentId: remoteDoc.documentId || localDoc.documentId,
    name: remoteDoc.updatedAtEpochMs > localDoc.updatedAtEpochMs ? remoteDoc.name : localDoc.name,
    revision: nextRev,
    lastModifiedByDevice: localDeviceId,
    updatedAtEpochMs: Date.now(),
    vectorClock: nextVectorClock,
    folders: mergedFolders,
    smartFolders: mergedSmart,
    dynamicFolders: mergedDynamic,
    connections: mergedConnections,
    credentials: mergedCredentials,
    gateways: mergedGateways,
    commandTasks: mergedCmdTasks,
    keySequenceTasks: mergedKeyTasks,
    templates: mergedTemplates,
    todoItems: mergedTodos,
    recentConnectionIds: mergedRecents,
    tombstonedObjectIds: [...tombstones]
  });

  return { mergedDocument, detectedConflicts: conflicts };
}
