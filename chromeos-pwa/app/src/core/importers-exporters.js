// Multi-Format Enterprise Vault Importer & Exporter:
// Supports `.nxts` v2, Royal TS (`.rtsz` ZIP/GZIP, `.rtsx` XML, `.rjson`), KeePass 2.x XML,
// Bitwarden JSON, 1Password (`.1pux` ZIP / JSON), and RFC 4180 CSV — 100% offline in Browser & Node 24.

import {
  DEFAULT_TRANSPARENT_PASSPHRASE,
  hasNxtsMagic,
  sealVaultDocument,
  unsealVaultDocument
} from './nxts-crypto.js';
import {
  createStarterVault,
  defaultInheritableProperties,
  defaultSettingsForProtocol,
  parseQuickConnectInput,
  randomUuid,
  sanitizeVaultDocument
} from './vault-models.js';

async function gunzipBytes(bytes) {
  if (typeof DecompressionStream !== 'undefined') {
    const ds = new DecompressionStream('gzip');
    const writer = ds.writable.getWriter();
    writer.write(bytes);
    writer.close();
    const out = await new Response(ds.readable).arrayBuffer();
    return new Uint8Array(out);
  }
  const zlib = await import('node:zlib');
  return new Uint8Array(zlib.gunzipSync(bytes));
}

async function inflateRawBytes(bytes) {
  if (typeof DecompressionStream !== 'undefined') {
    const ds = new DecompressionStream('deflate-raw');
    const writer = ds.writable.getWriter();
    writer.write(bytes);
    writer.close();
    const out = await new Response(ds.readable).arrayBuffer();
    return new Uint8Array(out);
  }
  const zlib = await import('node:zlib');
  return new Uint8Array(zlib.inflateRawSync(bytes));
}

async function extractZipEntryMatching(zipBytes, suffixList) {
  const view = new DataView(zipBytes.buffer, zipBytes.byteOffset, zipBytes.byteLength);
  let offset = 0;
  while (offset + 30 <= zipBytes.length) {
    const sig = view.getUint32(offset, true);
    if (sig !== 0x04034b50) break;
    const method = view.getUint16(offset + 8, true);
    const compSize = view.getUint32(offset + 18, true);
    const nameLen = view.getUint16(offset + 26, true);
    const extraLen = view.getUint16(offset + 28, true);
    const nameBytes = zipBytes.subarray(offset + 30, offset + 30 + nameLen);
    const entryName = new TextDecoder().decode(nameBytes);
    const dataStart = offset + 30 + nameLen + extraLen;
    const dataEnd = dataStart + compSize;
    if (dataEnd > zipBytes.length) break;

    const lower = entryName.toLowerCase();
    if (suffixList.some((sfx) => lower.endsWith(sfx))) {
      const slice = zipBytes.subarray(dataStart, dataEnd);
      if (method === 0) return slice.slice();
      if (method === 8) return await inflateRawBytes(slice);
    }
    offset = dataEnd;
  }
  return null;
}

function decodeXmlEntities(str) {
  return String(str || '')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

function encodeXmlEntities(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function extractXmlTagText(block, tagName) {
  const re = new RegExp(`<${tagName}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tagName}>`, 'i');
  const m = block.match(re);
  return m ? decodeXmlEntities(m[1].trim()) : '';
}

function mapRoyalNodeToProtocol(tagName, block) {
  const lower = tagName.toLowerCase();
  if (lower.includes('rdsconnection') || lower.includes('rdpconnection')) return 'RDP';
  if (lower.includes('terminalconnection')) {
    const sub = extractXmlTagText(block, 'ConnectionType').toLowerCase();
    if (sub.includes('telnet')) return 'TELNET';
    if (sub.includes('serial')) return 'SERIAL';
    return 'SSH';
  }
  if (lower.includes('filetransferconnection')) {
    const sub = extractXmlTagText(block, 'ConnectionType').toLowerCase();
    if (sub.includes('ftp') && sub.includes('ssl')) return 'FTPS';
    if (sub.includes('ftp') && !sub.includes('sftp')) return 'FTP';
    if (sub.includes('scp')) return 'SCP';
    return 'SFTP';
  }
  if (lower.includes('vncconnection')) return 'VNC';
  if (lower.includes('webconnection')) return 'WEB_PAGE';
  return 'RDP';
}

export function importRoyalTsXmlText(xmlText, fallbackName = 'Royal TS Vault') {
  if (!/<RoyalDocument|<RTSZDocument|<RoyalFolder|<RoyalRDSConnection|<RoyalSSHConnection|<RoyalTerminalConnection/i.test(xmlText)) {
    throw new Error('Selected XML file is not a valid Royal TS (.rtsx / .rtsz) document.');
  }

  const doc = createStarterVault('ChromeOS-PWA');
  doc.name = fallbackName || 'Imported Royal TS Vault';

  const folderBlocks = [...xmlText.matchAll(/<RoyalFolder\b[^>]*>([\s\S]*?)<\/RoyalFolder>/gi)];
  const builtInRootIds = new Set();
  for (const [, block] of folderBlocks) {
    const id = extractXmlTagText(block, 'ID') || randomUuid();
    const name = extractXmlTagText(block, 'Name') || 'Folder';
    const parentId = extractXmlTagText(block, 'ParentID') || null;
    if (!parentId && (name === 'Connections' || name === 'Verbindungen' || name === fallbackName)) {
      builtInRootIds.add(id);
      continue;
    }
    const credId = extractXmlTagText(block, 'CredentialId') || null;
    doc.folders.push({
      id,
      parentFolderId: parentId && !builtInRootIds.has(parentId) ? parentId : null,
      name,
      description: extractXmlTagText(block, 'Description'),
      tags: [],
      inheritable: defaultInheritableProperties(
        credId ? { credentialMode: 'OVERRIDE_EXPLICIT', explicitCredentialId: credId } : {}
      ),
      sortOrder: doc.folders.length,
      updatedAtEpochMs: Date.now()
    });
  }
  // Clean parent refs pointing to unwrapped built-in root
  for (const f of doc.folders) {
    if (f.parentFolderId && builtInRootIds.has(f.parentFolderId)) f.parentFolderId = null;
  }

  const credBlocks = [...xmlText.matchAll(/<RoyalCredential\b[^>]*>([\s\S]*?)<\/RoyalCredential>/gi)];
  for (const [, block] of credBlocks) {
    const id = extractXmlTagText(block, 'ID') || randomUuid();
    const rawUser = extractXmlTagText(block, 'UserName');
    let domain = extractXmlTagText(block, 'Domain');
    let username = rawUser;
    if (!domain && rawUser.includes('\\')) {
      [domain, username] = rawUser.split('\\', 2);
    }
    doc.credentials.push({
      id,
      parentFolderId: null,
      name: extractXmlTagText(block, 'Name') || username || 'Royal Credential',
      sourceKind: extractXmlTagText(block, 'KeyFileContent') ? 'SSH_KEY_PAIR' : 'VAULT_INTERNAL',
      username,
      domain,
      secretPassword: extractXmlTagText(block, 'Password'),
      privateKeyPem: extractXmlTagText(block, 'KeyFileContent'),
      publicKeyOpenSsh: '',
      keyFingerprintSha256: '',
      keyPassphrase: extractXmlTagText(block, 'Passphrase'),
      externalReferenceUri: '',
      fido2CredentialIdBase64: '',
      totpSecretBase32: '',
      updatedAtEpochMs: Date.now()
    });
  }

  const connNodeRe = /<(Royal(?:RDS|RDP|SSH|Terminal|FileTransfer|VNC|Web)Connection)\b[^>]*>([\s\S]*?)<\/\1>/gi;
  for (const [, tag, block] of xmlText.matchAll(connNodeRe)) {
    const id = extractXmlTagText(block, 'ID') || randomUuid();
    const protocol = mapRoyalNodeToProtocol(tag, block);
    const host = extractXmlTagText(block, 'URI') || extractXmlTagText(block, 'ComputerName') || '';
    if (!host) continue;
    const rawParent = extractXmlTagText(block, 'ParentID') || null;
    const parentFolderId = rawParent && !builtInRootIds.has(rawParent) ? rawParent : null;
    const portNum = Number(extractXmlTagText(block, 'Port') || extractXmlTagText(block, 'RDPPort') || 0);
    const settings = defaultSettingsForProtocol(protocol, host);
    if (portNum > 0 && 'port' in settings) settings.port = portNum;
    if (settings._type === 'rdp') {
      const vmId = extractXmlTagText(block, 'PreConnectionBlob') || extractXmlTagText(block, 'HyperVInstanceID');
      if (vmId) settings.hyperVVmId = vmId;
    }

    let credId = extractXmlTagText(block, 'CredentialId') || null;
    const inlineUser = extractXmlTagText(block, 'UserName');
    const inlinePass = extractXmlTagText(block, 'Password');
    if (!credId && (inlineUser || inlinePass)) {
      credId = `cred-inline-${id}`;
      let domain = '';
      let username = inlineUser;
      if (inlineUser.includes('\\')) [domain, username] = inlineUser.split('\\', 2);
      doc.credentials.push({
        id: credId,
        parentFolderId: null,
        name: `${extractXmlTagText(block, 'Name') || host} (${username || 'user'})`,
        sourceKind: 'VAULT_INTERNAL',
        username,
        domain,
        secretPassword: inlinePass,
        privateKeyPem: '',
        publicKeyOpenSsh: '',
        keyFingerprintSha256: '',
        keyPassphrase: '',
        externalReferenceUri: '',
        fido2CredentialIdBase64: '',
        totpSecretBase32: '',
        updatedAtEpochMs: Date.now()
      });
    }

    doc.connections.push({
      id,
      parentFolderId,
      name: extractXmlTagText(block, 'Name') || host,
      host,
      protocol,
      osFamily: protocol === 'RDP' ? 'Windows' : 'Linux',
      tags: [],
      notes: extractXmlTagText(block, 'Description'),
      favorite: extractXmlTagText(block, 'Favorite').toLowerCase() === 'true',
      sortOrder: doc.connections.length,
      inheritable: defaultInheritableProperties(
        credId ? { credentialMode: 'OVERRIDE_EXPLICIT', explicitCredentialId: credId } : {}
      ),
      settings,
      updatedAtEpochMs: Date.now()
    });
  }

  const sanitized = sanitizeVaultDocument(doc);
  return {
    document: sanitized,
    detectedFormat: 'Royal TS (.rtsx/.rtsz)',
    isRoyalTsFormat: true,
    importedConnections: sanitized.connections.length,
    importedFolders: sanitized.folders.length,
    importedCredentials: sanitized.credentials.length
  };
}

function importKeePassXmlText(xmlText, vaultName = 'KeePass Vault') {
  const doc = createStarterVault('ChromeOS-PWA');
  doc.name = vaultName;

  for (const [, entryBlock] of xmlText.matchAll(/<Entry>([\s\S]*?)<\/Entry>/gi)) {
    const kv = {};
    for (const [, strBlock] of entryBlock.matchAll(/<String>([\s\S]*?)<\/String>/gi)) {
      const k = extractXmlTagText(strBlock, 'Key');
      const v = extractXmlTagText(strBlock, 'Value');
      if (k) kv[k] = v;
    }
    const id = randomUuid();
    const title = kv.Title || kv.URL || 'KeePass Entry';
    const credId = `cred-kp-${id}`;
    doc.credentials.push({
      id: credId,
      parentFolderId: null,
      name: title,
      sourceKind: 'VAULT_INTERNAL',
      username: kv.UserName || '',
      domain: '',
      secretPassword: kv.Password || '',
      privateKeyPem: '',
      publicKeyOpenSsh: '',
      keyFingerprintSha256: '',
      keyPassphrase: '',
      externalReferenceUri: '',
      fido2CredentialIdBase64: '',
      totpSecretBase32: kv.otp || kv.TOTP || '',
      updatedAtEpochMs: Date.now()
    });
    if (kv.URL) {
      const parsed = parseQuickConnectInput(kv.URL);
      if (parsed) {
        doc.connections.push({
          id: `conn-kp-${id}`,
          parentFolderId: null,
          name: title,
          host: parsed.host,
          protocol: parsed.protocol,
          osFamily: parsed.protocol === 'RDP' ? 'Windows' : 'Linux',
          tags: ['keepass'],
          notes: kv.Notes || '',
          favorite: false,
          sortOrder: doc.connections.length,
          inheritable: defaultInheritableProperties({
            credentialMode: 'OVERRIDE_EXPLICIT',
            explicitCredentialId: credId
          }),
          settings: parsed.settings,
          updatedAtEpochMs: Date.now()
        });
      }
    }
  }
  const sanitized = sanitizeVaultDocument(doc);
  return {
    document: sanitized,
    detectedFormat: 'KeePass 2.x XML',
    isRoyalTsFormat: false,
    importedConnections: sanitized.connections.length,
    importedFolders: sanitized.folders.length,
    importedCredentials: sanitized.credentials.length
  };
}

function importBitwardenJsonObj(root) {
  const doc = createStarterVault('ChromeOS-PWA');
  doc.name = 'Bitwarden Imported Vault';
  doc.folders = (root.folders || []).map((f, i) => ({
    id: String(f.id || randomUuid()),
    parentFolderId: null,
    name: String(f.name || 'Folder'),
    description: '',
    tags: [],
    inheritable: defaultInheritableProperties(),
    sortOrder: i,
    updatedAtEpochMs: Date.now()
  }));
  for (const item of root.items || []) {
    if (!item?.login) continue;
    const id = String(item.id || randomUuid());
    const credId = `cred-bw-${id}`;
    const title = String(item.name || 'Bitwarden Entry');
    doc.credentials.push({
      id: credId,
      parentFolderId: item.folderId || null,
      name: title,
      sourceKind: 'VAULT_INTERNAL',
      username: String(item.login.username || ''),
      domain: '',
      secretPassword: String(item.login.password || ''),
      privateKeyPem: '',
      publicKeyOpenSsh: '',
      keyFingerprintSha256: '',
      keyPassphrase: '',
      externalReferenceUri: '',
      fido2CredentialIdBase64: '',
      totpSecretBase32: String(item.login.totp || ''),
      updatedAtEpochMs: Date.now()
    });
    const rawUri = item.login.uris?.[0]?.uri;
    if (rawUri) {
      const parsed = parseQuickConnectInput(rawUri);
      doc.connections.push({
        id: `conn-bw-${id}`,
        parentFolderId: item.folderId || null,
        name: title,
        host: parsed?.host || rawUri,
        protocol: parsed?.protocol || 'WEB_PAGE',
        osFamily: 'Linux',
        tags: [],
        notes: String(item.notes || ''),
        favorite: Boolean(item.favorite),
        sortOrder: doc.connections.length,
        inheritable: defaultInheritableProperties({
          credentialMode: 'OVERRIDE_EXPLICIT',
          explicitCredentialId: credId
        }),
        settings: parsed?.settings || defaultSettingsForProtocol('WEB_PAGE', rawUri),
        updatedAtEpochMs: Date.now()
      });
    }
  }
  const sanitized = sanitizeVaultDocument(doc);
  return {
    document: sanitized,
    detectedFormat: 'Bitwarden JSON',
    isRoyalTsFormat: false,
    importedConnections: sanitized.connections.length,
    importedFolders: sanitized.folders.length,
    importedCredentials: sanitized.credentials.length
  };
}

function importOnePasswordJsonObj(root, label = '1Password JSON') {
  const doc = createStarterVault('ChromeOS-PWA');
  doc.name = '1Password Imported Vault';
  for (const acc of root.accounts || [root]) {
    for (const v of acc.vaults || []) {
      const fldId = `fld-1p-${v.attrs?.uuid || randomUuid()}`;
      doc.folders.push({
        id: fldId,
        parentFolderId: null,
        name: String(v.attrs?.name || '1Password Vault'),
        description: '',
        tags: [],
        inheritable: defaultInheritableProperties(),
        sortOrder: doc.folders.length,
        updatedAtEpochMs: Date.now()
      });
      for (const item of v.items || []) {
        const uuid = item.uuid || randomUuid();
        const title = item.overview?.title || '1Password Item';
        const fields = item.details?.loginFields || [];
        const username = fields.find((f) => f.designation === 'username')?.value || '';
        const secretPassword = fields.find((f) => f.designation === 'password')?.value || '';
        const credId = `cred-1p-${uuid}`;
        doc.credentials.push({
          id: credId,
          parentFolderId: fldId,
          name: title,
          sourceKind: 'VAULT_INTERNAL',
          username: String(username),
          domain: '',
          secretPassword: String(secretPassword),
          privateKeyPem: '',
          publicKeyOpenSsh: '',
          keyFingerprintSha256: '',
          keyPassphrase: '',
          externalReferenceUri: '',
          fido2CredentialIdBase64: '',
          totpSecretBase32: '',
          updatedAtEpochMs: Date.now()
        });
        const rawUrl = item.overview?.url || item.overview?.urls?.[0]?.url;
        if (rawUrl) {
          const parsed = parseQuickConnectInput(rawUrl);
          doc.connections.push({
            id: `conn-1p-${uuid}`,
            parentFolderId: fldId,
            name: title,
            host: parsed?.host || rawUrl,
            protocol: parsed?.protocol || 'WEB_PAGE',
            osFamily: 'Linux',
            tags: [],
            notes: String(item.details?.notesPlain || ''),
            favorite: false,
            sortOrder: doc.connections.length,
            inheritable: defaultInheritableProperties({
              credentialMode: 'OVERRIDE_EXPLICIT',
              explicitCredentialId: credId
            }),
            settings: parsed?.settings || defaultSettingsForProtocol('WEB_PAGE', rawUrl),
            updatedAtEpochMs: Date.now()
          });
        }
      }
    }
  }
  const sanitized = sanitizeVaultDocument(doc);
  return {
    document: sanitized,
    detectedFormat: label,
    isRoyalTsFormat: false,
    importedConnections: sanitized.connections.length,
    importedFolders: sanitized.folders.length,
    importedCredentials: sanitized.credentials.length
  };
}

function parseCsvRows(text) {
  const rows = [];
  let row = [];
  let cur = '';
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"' && text[i + 1] === '"') {
        cur += '"';
        i++;
      } else if (ch === '"') {
        inQuotes = false;
      } else {
        cur += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === ',') {
      row.push(cur);
      cur = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++;
      row.push(cur);
      if (row.some((c) => c.trim().length > 0)) rows.push(row);
      row = [];
      cur = '';
    } else {
      cur += ch;
    }
  }
  if (cur.length > 0 || row.length > 0) {
    row.push(cur);
    if (row.some((c) => c.trim().length > 0)) rows.push(row);
  }
  return rows;
}

function importCsvInventoryText(text, vaultName = 'CSV Inventory') {
  const rows = parseCsvRows(text);
  const doc = createStarterVault('ChromeOS-PWA');
  doc.name = vaultName;
  if (rows.length < 2) {
    return { document: doc, detectedFormat: 'CSV Inventory', isRoyalTsFormat: false, importedConnections: 0, importedFolders: 0, importedCredentials: 0 };
  }
  const headers = rows[0].map((h) => h.trim().toLowerCase());
  const idx = (names) => headers.findIndex((h) => names.includes(h));
  const iName = idx(['name', 'title', 'server']);
  const iHost = idx(['host', 'hostname', 'ip', 'uri', 'url']);
  const iProto = idx(['protocol', 'type']);
  const iPort = idx(['port']);
  const iFolder = idx(['folder', 'group']);
  const iUser = idx(['username', 'user', 'login']);
  const iPass = idx(['password', 'secret']);

  const folderByName = new Map();
  for (const r of rows.slice(1)) {
    const host = (iHost >= 0 ? r[iHost] : r[1] || '').trim();
    if (!host) continue;
    const name = (iName >= 0 ? r[iName] : host).trim() || host;
    const protoRaw = (iProto >= 0 ? r[iProto] : 'SSH').trim().toUpperCase();
    const protocol = ['RDP', 'SSH', 'SFTP', 'FTP', 'FTPS', 'SCP', 'VNC', 'TELNET', 'WEB_PAGE'].includes(protoRaw) ? protoRaw : 'SSH';
    let folderId = null;
    if (iFolder >= 0 && r[iFolder]?.trim()) {
      const fn = r[iFolder].trim();
      if (!folderByName.has(fn)) {
        const fId = randomUuid();
        folderByName.set(fn, fId);
        doc.folders.push({
          id: fId,
          parentFolderId: null,
          name: fn,
          description: '',
          tags: [],
          inheritable: defaultInheritableProperties(),
          sortOrder: doc.folders.length,
          updatedAtEpochMs: Date.now()
        });
      }
      folderId = folderByName.get(fn);
    }
    let credId = null;
    const u = iUser >= 0 ? (r[iUser] || '').trim() : '';
    const pw = iPass >= 0 ? (r[iPass] || '').trim() : '';
    if (u || pw) {
      credId = randomUuid();
      doc.credentials.push({
        id: credId,
        parentFolderId: folderId,
        name: `${name} (${u || 'user'})`,
        sourceKind: 'VAULT_INTERNAL',
        username: u,
        domain: '',
        secretPassword: pw,
        privateKeyPem: '',
        publicKeyOpenSsh: '',
        keyFingerprintSha256: '',
        keyPassphrase: '',
        externalReferenceUri: '',
        fido2CredentialIdBase64: '',
        totpSecretBase32: '',
        updatedAtEpochMs: Date.now()
      });
    }
    const settings = defaultSettingsForProtocol(protocol, host);
    const portNum = iPort >= 0 ? Number(r[iPort]) : 0;
    if (portNum > 0 && 'port' in settings) settings.port = portNum;

    doc.connections.push({
      id: randomUuid(),
      parentFolderId: folderId,
      name,
      host,
      protocol,
      osFamily: protocol === 'RDP' ? 'Windows' : 'Linux',
      tags: [],
      notes: '',
      favorite: false,
      sortOrder: doc.connections.length,
      inheritable: defaultInheritableProperties(
        credId ? { credentialMode: 'OVERRIDE_EXPLICIT', explicitCredentialId: credId } : {}
      ),
      settings,
      updatedAtEpochMs: Date.now()
    });
  }
  const sanitized = sanitizeVaultDocument(doc);
  return {
    document: sanitized,
    detectedFormat: 'CSV Inventory',
    isRoyalTsFormat: false,
    importedConnections: sanitized.connections.length,
    importedFolders: sanitized.folders.length,
    importedCredentials: sanitized.credentials.length
  };
}

export async function importEnterpriseVaultBytes(bytes, fileName = 'import', passphrase = '') {
  const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const ext = (fileName.split('.').pop() || '').toLowerCase();
  const baseName = fileName.replace(/\.[^.]+$/, '') || 'Imported Vault';

  if (hasNxtsMagic(data) || ext === 'nxts') {
    const doc = await unsealVaultDocument(data, passphrase || DEFAULT_TRANSPARENT_PASSPHRASE);
    return {
      document: doc,
      detectedFormat: 'NexusTS v2 (.nxts)',
      isRoyalTsFormat: false,
      importedConnections: doc.connections.length,
      importedFolders: doc.folders.length,
      importedCredentials: doc.credentials.length
    };
  }

  // ZIP container (.rtsz or .1pux)
  if (data.length >= 4 && data[0] === 0x50 && data[1] === 0x4b && data[2] === 0x03 && data[3] === 0x04) {
    const onePux = await extractZipEntryMatching(data, ['export.data']);
    if (onePux) {
      return importOnePasswordJsonObj(JSON.parse(new TextDecoder().decode(onePux)), '1Password (.1pux)');
    }
    const rtsx = await extractZipEntryMatching(data, ['.rtsx', '.xml']);
    if (rtsx) {
      return importRoyalTsXmlText(new TextDecoder().decode(rtsx), baseName);
    }
  }

  // GZIP container (.rtsz GZIP variant)
  if (data.length >= 2 && data[0] === 0x1f && data[1] === 0x8b) {
    const unpacked = await gunzipBytes(data);
    return importRoyalTsXmlText(new TextDecoder().decode(unpacked), baseName);
  }

  const text = new TextDecoder().decode(data).trim();
  if (text.startsWith('<') || ext === 'rtsx' || ext === 'xml') {
    if (text.includes('<KeePassFile')) {
      return importKeePassXmlText(text, baseName);
    }
    return importRoyalTsXmlText(text, baseName);
  }

  if (text.startsWith('{')) {
    const obj = JSON.parse(text);
    if (obj.documentId && obj.schemaVersion) {
      const doc = sanitizeVaultDocument(obj);
      return {
        document: doc,
        detectedFormat: 'NexusTS JSON',
        isRoyalTsFormat: false,
        importedConnections: doc.connections.length,
        importedFolders: doc.folders.length,
        importedCredentials: doc.credentials.length
      };
    }
    if (Array.isArray(obj.items)) {
      return importBitwardenJsonObj(obj);
    }
    if (Array.isArray(obj.accounts) || Array.isArray(obj.vaults)) {
      return importOnePasswordJsonObj(obj, '1Password JSON');
    }
    if (Array.isArray(obj.Objects) || ext === 'rjson') {
      const doc = createStarterVault('ChromeOS-PWA');
      doc.name = baseName;
      for (const item of obj.Objects || []) {
        const host = String(item.URI || item.Host || '');
        if (!host) continue;
        const typeStr = String(item.Type || 'SSH').toUpperCase();
        const protocol = typeStr.includes('RDP') ? 'RDP' : typeStr.includes('VNC') ? 'VNC' : 'SSH';
        const settings = defaultSettingsForProtocol(protocol, host);
        if (item.Port) settings.port = Number(item.Port);
        doc.connections.push({
          id: String(item.ID || randomUuid()),
          parentFolderId: null,
          name: String(item.Name || host),
          host,
          protocol,
          osFamily: protocol === 'RDP' ? 'Windows' : 'Linux',
          tags: [],
          notes: String(item.Description || ''),
          favorite: false,
          sortOrder: doc.connections.length,
          inheritable: defaultInheritableProperties(),
          settings,
          updatedAtEpochMs: Date.now()
        });
      }
      const sanitized = sanitizeVaultDocument(doc);
      return {
        document: sanitized,
        detectedFormat: 'Royal JSON (.rjson)',
        isRoyalTsFormat: true,
        importedConnections: sanitized.connections.length,
        importedFolders: 0,
        importedCredentials: 0
      };
    }
  }

  return importCsvInventoryText(text, baseName);
}

/**
 * Merges an imported document into `existingDoc` according to placement mode:
 * - `'MERGE'`: merges into root hierarchy preserving existing items
 * - `'DEDICATED_SUBFOLDER'`: wraps imported root items inside a dedicated folder (`subfolderName`)
 * - `'REPLACE'`: replaces vault contents cleanly
 */
export function applyVaultImportPlacement(existingDoc, importedDoc, mode = 'MERGE', subfolderName = 'Imported Connections') {
  if (mode === 'REPLACE') {
    return sanitizeVaultDocument({
      ...importedDoc,
      documentId: existingDoc.documentId,
      revision: Number(existingDoc.revision || 1) + 1
    });
  }

  let foldersToAdd = (importedDoc.folders || []).map((f) => ({ ...f }));
  let connsToAdd = (importedDoc.connections || []).map((c) => ({ ...c }));

  if (mode === 'DEDICATED_SUBFOLDER') {
    const wrapperId = randomUuid();
    foldersToAdd = [
      {
        id: wrapperId,
        parentFolderId: null,
        name: subfolderName || importedDoc.name || 'Imported Vault',
        description: '',
        tags: [],
        inheritable: defaultInheritableProperties(),
        sortOrder: (existingDoc.folders || []).length,
        updatedAtEpochMs: Date.now()
      },
      ...foldersToAdd.map((f) => ({
        ...f,
        parentFolderId: f.parentFolderId || wrapperId
      }))
    ];
    connsToAdd = connsToAdd.map((c) => ({
      ...c,
      parentFolderId: c.parentFolderId || wrapperId
    }));
  }

  const dedupMerge = (baseArr = [], incomingArr = []) => {
    const map = new Map(baseArr.map((x) => [x.id, x]));
    for (const item of incomingArr) map.set(item.id, item);
    return [...map.values()];
  };

  return sanitizeVaultDocument({
    ...existingDoc,
    revision: Number(existingDoc.revision || 1) + 1,
    folders: dedupMerge(existingDoc.folders, foldersToAdd),
    connections: dedupMerge(existingDoc.connections, connsToAdd),
    credentials: dedupMerge(existingDoc.credentials, importedDoc.credentials),
    gateways: dedupMerge(existingDoc.gateways, importedDoc.gateways),
    templates: dedupMerge(existingDoc.templates, importedDoc.templates),
    commandTasks: dedupMerge(existingDoc.commandTasks, importedDoc.commandTasks),
    keySequenceTasks: dedupMerge(existingDoc.keySequenceTasks, importedDoc.keySequenceTasks)
  });
}

export async function exportVaultDocument(doc, format = 'NXTS', passphrase = '', kdfParams) {
  if (format === 'NXTS') {
    const bytes = await sealVaultDocument(doc, passphrase, kdfParams);
    return { bytes, mimeType: 'application/octet-stream', extension: 'nxts' };
  }
  if (format === 'CSV') {
    const foldersById = new Map((doc.folders || []).map((f) => [f.id, f.name]));
    const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
    const lines = ['Name,Host,Protocol,Port,Folder,Tags,Notes'];
    for (const c of doc.connections || []) {
      lines.push([
        esc(c.name),
        esc(c.host),
        esc(c.protocol),
        esc(c.settings?.port || ''),
        esc(c.parentFolderId ? foldersById.get(c.parentFolderId) || '' : ''),
        esc((c.tags || []).join(';')),
        esc(c.notes || '')
      ].join(','));
    }
    return {
      bytes: new TextEncoder().encode(lines.join('\n')),
      mimeType: 'text/csv;charset=utf-8',
      extension: 'csv'
    };
  }
  // Royal TS .rtsx XML export
  const parts = ['<?xml version="1.0" encoding="utf-8"?>', '<RoyalDocument>', `  <ID>${encodeXmlEntities(doc.documentId)}</ID>`, `  <Name>${encodeXmlEntities(doc.name)}</Name>`];
  for (const f of doc.folders || []) {
    parts.push('  <RoyalFolder>', `    <ID>${encodeXmlEntities(f.id)}</ID>`, `    <Name>${encodeXmlEntities(f.name)}</Name>`);
    if (f.parentFolderId) parts.push(`    <ParentID>${encodeXmlEntities(f.parentFolderId)}</ParentID>`);
    parts.push('  </RoyalFolder>');
  }
  for (const c of doc.connections || []) {
    const tag = c.protocol === 'RDP' ? 'RoyalRDSConnection' : c.protocol === 'VNC' ? 'RoyalVNCConnection' : 'RoyalSSHConnection';
    parts.push(`  <${tag}>`, `    <ID>${encodeXmlEntities(c.id)}</ID>`, `    <Name>${encodeXmlEntities(c.name)}</Name>`, `    <URI>${encodeXmlEntities(c.host)}</URI>`);
    if (c.parentFolderId) parts.push(`    <ParentID>${encodeXmlEntities(c.parentFolderId)}</ParentID>`);
    if (c.settings?.port) parts.push(`    <Port>${c.settings.port}</Port>`);
    parts.push(`  </${tag}>`);
  }
  parts.push('</RoyalDocument>');
  return {
    bytes: new TextEncoder().encode(parts.join('\n')),
    mimeType: 'application/xml;charset=utf-8',
    extension: 'rtsx'
  };
}
