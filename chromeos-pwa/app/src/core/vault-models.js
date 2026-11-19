// Canonical `NxtsVaultDocument` v2 Model, Sanitizer, Cascading Property Inheritance Resolver,
// Smart/Dynamic Folder Evaluators, Quick-Connect URI Parser & Automation Macro Engine.
// 100% JSON wire-compatible (`_type` discriminator) with Android (`kotlinx.serialization`) & macOS (`Codable`).

export function randomUuid() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const b = globalThis.crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export const PROTOCOL_CATALOG = Object.freeze({
  RDP: { title: 'Remote Desktop (RDP)', badge: 'RDP', defaultPort: 3389, category: 'REMOTE_DESKTOP', settingsType: 'rdp' },
  WINDOWS_TERMINAL_SERVICES: { title: 'Windows Terminal Services', badge: 'WTS', defaultPort: 3389, category: 'REMOTE_DESKTOP', settingsType: 'rdp' },
  VNC: { title: 'VNC Remote Screen', badge: 'VNC', defaultPort: 5900, category: 'REMOTE_DESKTOP', settingsType: 'vnc' },
  APPLE_SCREEN_SHARING: { title: 'Apple Screen Sharing', badge: 'ARD', defaultPort: 5900, category: 'REMOTE_DESKTOP', settingsType: 'vnc' },
  SSH: { title: 'SSH Terminal', badge: 'SSH', defaultPort: 22, category: 'TERMINAL_CONTAINERS', settingsType: 'ssh' },
  TELNET: { title: 'Telnet', badge: 'TEL', defaultPort: 23, category: 'TERMINAL_CONTAINERS', settingsType: 'ssh' },
  SERIAL: { title: 'USB Serial / UART', badge: 'UART', defaultPort: 0, category: 'TERMINAL_CONTAINERS', settingsType: 'serial_uart' },
  KUBERNETES_POD: { title: 'Kubernetes Pod', badge: 'K8S', defaultPort: 6443, category: 'TERMINAL_CONTAINERS', settingsType: 'container_exec' },
  DOCKER_CONTAINER: { title: 'Docker Container', badge: 'OCI', defaultPort: 2375, category: 'TERMINAL_CONTAINERS', settingsType: 'container_exec' },
  SFTP: { title: 'SFTP File Transfer', badge: 'SFTP', defaultPort: 22, category: 'FILE_TRANSFER', settingsType: 'file_transfer' },
  SCP: { title: 'SCP Transfer', badge: 'SCP', defaultPort: 22, category: 'FILE_TRANSFER', settingsType: 'file_transfer' },
  FTP: { title: 'FTP', badge: 'FTP', defaultPort: 21, category: 'FILE_TRANSFER', settingsType: 'file_transfer' },
  FTPS: { title: 'FTPS (TLS)', badge: 'FTPS', defaultPort: 990, category: 'FILE_TRANSFER', settingsType: 'file_transfer' },
  WEB_PAGE: { title: 'Web Console (HTTP/HTTPS)', badge: 'WEB', defaultPort: 443, category: 'WEB_CLOUD', settingsType: 'web_page' },
  VMWARE_VSPHERE: { title: 'VMware vSphere', badge: 'ESXi', defaultPort: 443, category: 'WEB_CLOUD', settingsType: 'cloud_virtualization' },
  PROXMOX_VE: { title: 'Proxmox VE', badge: 'PVE', defaultPort: 8006, category: 'WEB_CLOUD', settingsType: 'cloud_virtualization' },
  HYPER_V: { title: 'Microsoft Hyper-V', badge: 'HV', defaultPort: 2179, category: 'REMOTE_DESKTOP', settingsType: 'rdp' },
  AWS_EC2: { title: 'AWS EC2', badge: 'EC2', defaultPort: 22, category: 'WEB_CLOUD', settingsType: 'cloud_virtualization' },
  AZURE_VM: { title: 'Microsoft Azure VM', badge: 'AZ', defaultPort: 22, category: 'WEB_CLOUD', settingsType: 'cloud_virtualization' },
  UTILITY_PING: { title: 'Ping Reachability', badge: 'PING', defaultPort: 22, category: 'NETWORK_UTILITIES', settingsType: 'net_utility' },
  UTILITY_TRACEROUTE: { title: 'Traceroute', badge: 'MTR', defaultPort: 80, category: 'NETWORK_UTILITIES', settingsType: 'net_utility' },
  UTILITY_PORT_SCANNER: { title: 'TCP Port Scanner', badge: 'SCAN', defaultPort: 0, category: 'NETWORK_UTILITIES', settingsType: 'net_utility' },
  UTILITY_WAKE_ON_LAN: { title: 'Wake-on-LAN', badge: 'WOL', defaultPort: 9, category: 'NETWORK_UTILITIES', settingsType: 'net_utility' }
});

export function defaultInheritableProperties(overrides = {}) {
  return {
    credentialMode: 'INHERIT_FROM_PARENT',
    explicitCredentialId: null,
    gatewayMode: 'INHERIT_FROM_PARENT',
    explicitGatewayId: null,
    environmentBadge: null,
    preConnectTaskId: null,
    postDisconnectTaskId: null,
    displayColorAccentHex: null,
    customProperties: [],
    requireConnectConfirmation: false,
    ...overrides
  };
}

export function defaultSettingsForProtocol(protocol = 'SSH', host = '') {
  const meta = PROTOCOL_CATALOG[protocol] || PROTOCOL_CATALOG.SSH;
  switch (meta.settingsType) {
    case 'rdp':
      return {
        _type: 'rdp',
        port: protocol === 'HYPER_V' ? 2179 : 3389,
        connectToConsoleAdmin: false,
        hiDpiEnabled: true,
        desktopScaleFactorPercent: 200,
        deviceScaleFactorPercent: 180,
        hiDpiScaleCustomized: false,
        enableGfxPipeline: true,
        gfxCodecProfile: 'ADAPTIVE_PROGRESSIVE',
        enableH264Avc444: false,
        enableRemoteFx: true,
        resizeBehavior: 'DYNAMIC_DISP_DVC',
        customDesktopWidth: 1920,
        customDesktopHeight: 1080,
        multiMonitorSpanning: false,
        selectedMonitorIds: [],
        colorDepthBits: 32,
        redirectClipboard: true,
        redirectClipboardCustomized: true,
        audioRedirectionMode: 'PLAY_ON_THIS_DEVICE',
        redirectAudioPlayback: true,
        redirectMicrophone: false,
        redirectDrives: false,
        redirectedDriveSafUris: [],
        redirectPrinters: false,
        redirectPorts: false,
        redirectSmartcardYubiKey: false,
        enableFontSmoothing: true,
        enableDesktopComposition: true,
        enableWallpaper: false,
        enableFullWindowDrag: false,
        enableMenuAnimations: false,
        enableThemes: true,
        compressionEnabled: true,
        remoteAppMode: false,
        remoteAppProgram: '',
        remoteAppArguments: '',
        rdGatewayHostname: '',
        rdGatewayPort: 443,
        nlaSecurityRequired: true,
        tlsSecurity: true,
        restrictedAdminMode: false,
        ignoreCertificateWarnings: true,
        hyperVVmId: '',
        hyperVEnhancedSession: true,
        hyperVEnhancedSessionCustomized: false
      };
    case 'ssh':
      return {
        _type: 'ssh',
        port: protocol === 'TELNET' ? 23 : 22,
        terminalType: 'xterm-256color',
        bastionJumpConnectionId: null,
        enableAgentForwarding: false,
        enableX11Forwarding: false,
        x11DisplayHost: '127.0.0.1:0',
        portForwards: [],
        startupCommand: '',
        keepAliveIntervalSec: 30,
        scrollbackLines: 10000,
        serialBaudRate: 115200,
        serialDevicePath: '/dev/ttyUSB0'
      };
    case 'file_transfer':
      return {
        _type: 'file_transfer',
        port: protocol === 'FTP' ? 21 : protocol === 'FTPS' ? 990 : 22,
        initialLocalDirectory: '',
        initialRemoteDirectory: '',
        preserveTimestamps: true,
        showHiddenDotfiles: true,
        maxConcurrentTransfers: 4,
        ftpsImplicitTls: protocol === 'FTPS',
        passiveMode: true,
        verifyTls: false
      };
    case 'vnc':
      return {
        _type: 'vnc',
        port: 5900,
        preferredEncodings: ['ZRLE', 'Tight', 'CopyRect', 'Hextile', 'Raw'],
        compressionLevel: 6,
        jpegQualityLevel: 7,
        viewOnly: false,
        sharedSession: true,
        appleArdDiffieHellmanAuth: protocol === 'APPLE_SCREEN_SHARING'
      };
    case 'web_page':
      return {
        _type: 'web_page',
        url: host && /^https?:\/\//i.test(host) ? host : host ? `https://${host}` : '',
        isolatedStorageProfileId: randomUuid(),
        autoFillEnabled: true,
        usernameCssSelector: "input[type='email'], input[name*='user'], input[type='text']",
        passwordCssSelector: "input[type='password']",
        submitCssSelector: "button[type='submit'], input[type='submit']",
        injectHttpBasicAuth: true,
        pinnedCertSha256: '',
        ignoreSelfSignedInternalCert: true
      };
    case 'cloud_virtualization':
      return {
        _type: 'cloud_virtualization',
        apiEndpointUrl: host && /^https?:\/\//i.test(host) ? host : host ? `https://${host}` : '',
        regionOrCluster: '',
        subscriptionOrAccountId: '',
        realmOrDomain: 'pam',
        autoSyncDynamicInventory: true,
        verifyTls: false
      };
    case 'net_utility':
      return {
        _type: 'net_utility',
        pingCount: 10,
        pingIntervalMs: 1000,
        tracerouteMaxHops: 30,
        portScanRangeStart: 1,
        portScanRangeEnd: 1024,
        portScanConcurrency: 64,
        wolMacAddress: '',
        wolBroadcastAddress: '255.255.255.255',
        wolUdpPort: 9,
        wolSecureOnHex: ''
      };
    case 'serial_uart':
      return {
        _type: 'serial_uart',
        baudRate: 115200,
        dataBits: 8,
        stopBits: 1,
        parity: 'NONE',
        flowControl: 'NONE',
        dtrEnabled: true,
        rtsEnabled: true,
        usbVendorIdFilter: 0,
        devicePathHint: '/dev/ttyUSB0',
        lineEnding: 'CRLF'
      };
    case 'container_exec':
      return {
        _type: 'container_exec',
        clusterOrSocketEndpoint: host || 'https://kubernetes.default.svc:6443',
        contextName: '',
        namespace: 'default',
        podOrContainerName: '',
        subContainerName: '',
        shellCommand: '/bin/sh',
        followLogsOnOpen: false,
        logTailLines: 200,
        portForwards: [],
        verifyTls: false
      };
    default:
      return { _type: 'ssh', port: 22, terminalType: 'xterm-256color' };
  }
}

export function createStarterVault(deviceName = 'ChromeOS-PWA') {
  const now = Date.now();
  return {
    documentId: randomUuid(),
    name: 'NexusTS Enterprise Vault',
    description: '',
    schemaVersion: 2,
    revision: 1,
    lastModifiedByDevice: deviceName,
    updatedAtEpochMs: now,
    vectorClock: { [deviceName]: 1 },
    rootInheritable: defaultInheritableProperties({
      credentialMode: 'NONE_DIRECT',
      gatewayMode: 'NONE_DIRECT'
    }),
    folders: [],
    smartFolders: [],
    dynamicFolders: [],
    connections: [],
    credentials: [],
    gateways: [],
    commandTasks: [],
    keySequenceTasks: [],
    templates: [],
    todoItems: [],
    recentConnectionIds: [],
    tombstonedObjectIds: []
  };
}

export function migrateLegacyDefaultRdpScale(doc) {
  const heal = (s) => {
    if (!s || s._type !== 'rdp') return s;
    const next = { ...s };
    if (!next.hiDpiScaleCustomized && next.hiDpiEnabled && (next.desktopScaleFactorPercent === 150 || next.desktopScaleFactorPercent === 100)) {
      next.desktopScaleFactorPercent = 200;
      next.deviceScaleFactorPercent = 180;
    }
    if (!next.redirectClipboardCustomized && next.redirectClipboard === false) {
      next.redirectClipboard = true;
    }
    if (!next.hyperVEnhancedSessionCustomized && next.hyperVVmId && !next.hyperVEnhancedSession) {
      next.hyperVEnhancedSession = true;
    }
    return next;
  };
  return {
    ...doc,
    connections: (doc.connections || []).map((c) => ({ ...c, settings: heal(c.settings) })),
    templates: (doc.templates || []).map((t) => ({ ...t, settings: heal(t.settings) }))
  };
}

export function sanitizeVaultDocument(raw) {
  const base = createStarterVault();
  if (!raw || typeof raw !== 'object') return base;
  const tombstones = Array.from(new Set(Array.isArray(raw.tombstonedObjectIds) ? raw.tombstonedObjectIds : []));
  const tombSet = new Set(tombstones);

  const folders = (Array.isArray(raw.folders) ? raw.folders : [])
    .filter((f) => f && f.id && !tombSet.has(f.id))
    .map((f) => ({
      id: String(f.id),
      parentFolderId: f.parentFolderId || null,
      name: String(f.name || 'Folder'),
      description: String(f.description || ''),
      tags: Array.isArray(f.tags) ? f.tags : [],
      inheritable: defaultInheritableProperties(f.inheritable || {}),
      sortOrder: Number(f.sortOrder || 0),
      updatedAtEpochMs: Number(f.updatedAtEpochMs || Date.now())
    }));

  const folderIds = new Set(folders.map((f) => f.id));
  // Break any accidental folder parent cycles & orphan folder refs
  for (const folder of folders) {
    if (folder.parentFolderId && !folderIds.has(folder.parentFolderId)) {
      folder.parentFolderId = null;
    }
    const seen = new Set([folder.id]);
    let cursor = folder.parentFolderId;
    while (cursor) {
      if (seen.has(cursor)) {
        folder.parentFolderId = null;
        break;
      }
      seen.add(cursor);
      const p = folders.find((x) => x.id === cursor);
      cursor = p ? p.parentFolderId : null;
    }
  }

  const connections = (Array.isArray(raw.connections) ? raw.connections : [])
    .filter((c) => c && c.id && !tombSet.has(c.id))
    .map((c) => {
      const protocol = PROTOCOL_CATALOG[c.protocol] ? c.protocol : 'SSH';
      const fallbackSettings = defaultSettingsForProtocol(protocol, c.host);
      const settings = c.settings && c.settings._type ? { ...fallbackSettings, ...c.settings } : fallbackSettings;
      return {
        id: String(c.id),
        parentFolderId: c.parentFolderId && folderIds.has(c.parentFolderId) ? c.parentFolderId : null,
        name: String(c.name || c.host || 'Server'),
        host: String(c.host || ''),
        protocol,
        osFamily: String(c.osFamily || (protocol === 'RDP' ? 'Windows' : 'Linux')),
        tags: Array.isArray(c.tags) ? c.tags : [],
        notes: String(c.notes || ''),
        favorite: Boolean(c.favorite),
        sortOrder: Number(c.sortOrder || 0),
        inheritable: defaultInheritableProperties(c.inheritable || {}),
        settings,
        updatedAtEpochMs: Number(c.updatedAtEpochMs || Date.now())
      };
    });

  return {
    documentId: String(raw.documentId || base.documentId),
    name: String(raw.name || base.name),
    description: String(raw.description || ''),
    schemaVersion: 2,
    revision: Number(raw.revision ?? 1),
    lastModifiedByDevice: String(raw.lastModifiedByDevice || 'ChromeOS-PWA'),
    updatedAtEpochMs: Number(raw.updatedAtEpochMs || Date.now()),
    vectorClock: raw.vectorClock && typeof raw.vectorClock === 'object' ? { ...raw.vectorClock } : { 'ChromeOS-PWA': 1 },
    rootInheritable: defaultInheritableProperties({
      credentialMode: 'NONE_DIRECT',
      gatewayMode: 'NONE_DIRECT',
      ...(raw.rootInheritable || {})
    }),
    folders,
    smartFolders: (Array.isArray(raw.smartFolders) ? raw.smartFolders : []).filter((s) => s && s.id && !tombSet.has(s.id)),
    dynamicFolders: (Array.isArray(raw.dynamicFolders) ? raw.dynamicFolders : []).filter((d) => d && d.id && !tombSet.has(d.id)),
    connections,
    credentials: (Array.isArray(raw.credentials) ? raw.credentials : []).filter((cr) => cr && cr.id && !tombSet.has(cr.id)),
    gateways: (Array.isArray(raw.gateways) ? raw.gateways : []).filter((g) => g && g.id && !tombSet.has(g.id)),
    commandTasks: (Array.isArray(raw.commandTasks) ? raw.commandTasks : []).filter((t) => t && t.id && !tombSet.has(t.id)),
    keySequenceTasks: (Array.isArray(raw.keySequenceTasks) ? raw.keySequenceTasks : []).filter((t) => t && t.id && !tombSet.has(t.id)),
    templates: (Array.isArray(raw.templates) ? raw.templates : []).filter((t) => t && t.id && !tombSet.has(t.id)),
    todoItems: (Array.isArray(raw.todoItems) ? raw.todoItems : []).filter((t) => t && t.id && !tombSet.has(t.id)),
    recentConnectionIds: Array.isArray(raw.recentConnectionIds) ? raw.recentConnectionIds.slice(0, 15) : [],
    tombstonedObjectIds: tombstones
  };
}

export function bumpVaultRevision(doc, deviceId = 'ChromeOS-PWA') {
  const prevClock = doc.vectorClock || {};
  const nextActorVal = Number(prevClock[deviceId] || 0) + 1;
  return {
    ...doc,
    revision: Number(doc.revision || 0) + 1,
    lastModifiedByDevice: deviceId,
    updatedAtEpochMs: Date.now(),
    vectorClock: {
      ...prevClock,
      [deviceId]: nextActorVal
    }
  };
}

/**
 * Cascading Property Inheritance (`Vault Root -> Ancestor Folders -> Connection`).
 */
export function resolveEffectiveConnectionProfile(connection, document) {
  const foldersById = new Map((document.folders || []).map((f) => [f.id, f]));
  const credentialsById = new Map((document.credentials || []).map((c) => [c.id, c]));
  const gatewaysById = new Map((document.gateways || []).map((g) => [g.id, g]));

  const chain = [];
  const visited = new Set();
  let cursor = connection.parentFolderId;
  while (cursor && !visited.has(cursor) && foldersById.has(cursor)) {
    visited.add(cursor);
    const f = foldersById.get(cursor);
    chain.push(f);
    cursor = f.parentFolderId;
  }
  chain.reverse();

  const stages = [
    { source: 'Vault Root', props: document.rootInheritable || defaultInheritableProperties() },
    ...chain.map((f) => ({ source: f.name, props: f.inheritable || defaultInheritableProperties() })),
    { source: connection.name, props: connection.inheritable || defaultInheritableProperties() }
  ];

  let credId = null;
  let credentialInheritedFrom = 'None (Direct)';
  let promptCredentialAtConnect = false;
  let gatewayId = null;
  let gatewayInheritedFrom = 'Direct';
  let effectiveEnvironmentBadge = null;
  let preConnectTaskId = null;
  let postDisconnectTaskId = null;
  let displayColorAccentHex = null;
  let requireConnectConfirmation = false;
  const effectiveCustomProperties = {};

  for (const { source, props } of stages) {
    switch (props.credentialMode) {
      case 'OVERRIDE_EXPLICIT':
        credId = props.explicitCredentialId || null;
        credentialInheritedFrom = source;
        promptCredentialAtConnect = false;
        break;
      case 'NONE_DIRECT':
        credId = null;
        credentialInheritedFrom = `None (${source})`;
        promptCredentialAtConnect = false;
        break;
      case 'PROMPT_AT_CONNECT':
        credId = null;
        credentialInheritedFrom = `Prompt (${source})`;
        promptCredentialAtConnect = true;
        break;
      default:
        break;
    }
    switch (props.gatewayMode) {
      case 'OVERRIDE_EXPLICIT':
        gatewayId = props.explicitGatewayId || null;
        gatewayInheritedFrom = source;
        break;
      case 'NONE_DIRECT':
      case 'PROMPT_AT_CONNECT':
        gatewayId = null;
        gatewayInheritedFrom = 'Direct';
        break;
      default:
        break;
    }
    if (props.environmentBadge?.trim()) effectiveEnvironmentBadge = props.environmentBadge.trim();
    if (props.preConnectTaskId?.trim()) preConnectTaskId = props.preConnectTaskId.trim();
    if (props.postDisconnectTaskId?.trim()) postDisconnectTaskId = props.postDisconnectTaskId.trim();
    if (props.displayColorAccentHex?.trim()) displayColorAccentHex = props.displayColorAccentHex.trim();
    if (props.requireConnectConfirmation) requireConnectConfirmation = true;
    for (const entry of props.customProperties || []) {
      if (entry?.key) effectiveCustomProperties[entry.key] = entry;
    }
  }

  return {
    connection,
    effectiveCredential: credId ? credentialsById.get(credId) || null : null,
    credentialInheritedFrom,
    promptCredentialAtConnect,
    effectiveGateway: gatewayId ? gatewaysById.get(gatewayId) || null : null,
    gatewayInheritedFrom,
    effectiveEnvironmentBadge,
    preConnectTaskId,
    postDisconnectTaskId,
    displayColorAccentHex,
    requireConnectConfirmation,
    effectiveCustomProperties
  };
}

export function resolveSubtreeFolderIds(rootFolderId, document) {
  const result = new Set([rootFolderId]);
  let added = true;
  while (added) {
    added = false;
    for (const f of document.folders || []) {
      if (!result.has(f.id) && f.parentFolderId && result.has(f.parentFolderId)) {
        result.add(f.id);
        added = true;
      }
    }
  }
  return result;
}

export function evaluateSmartFolder(smartFolder, document) {
  const f = smartFolder?.filter || {};
  let regex = null;
  if (f.hostPatternRegex?.trim()) {
    try {
      regex = new RegExp(f.hostPatternRegex.trim(), 'i');
    } catch {
      return [];
    }
  }
  const scopeSet = f.scopeFolderId?.trim() ? resolveSubtreeFolderIds(f.scopeFolderId.trim(), document) : null;
  const incTags = (f.includeTags || []).map((t) => t.toLowerCase());
  const excTags = (f.excludeTags || []).map((t) => t.toLowerCase());
  const incProtos = new Set(f.includeProtocols || []);

  return (document.connections || []).filter((conn) => {
    if (scopeSet && (!conn.parentFolderId || !scopeSet.has(conn.parentFolderId))) return false;
    if (incProtos.size > 0 && !incProtos.has(conn.protocol)) return false;
    const cTags = (conn.tags || []).map((t) => t.toLowerCase());
    if (incTags.length > 0 && !incTags.every((t) => cTags.includes(t))) return false;
    if (excTags.some((t) => cTags.includes(t))) return false;
    if (f.osFamilyFilter?.trim() && conn.osFamily.toLowerCase() !== f.osFamilyFilter.trim().toLowerCase()) return false;
    if (regex && !regex.test(conn.host) && !regex.test(conn.name)) return false;
    if (f.environmentFilter?.trim()) {
      const eff = resolveEffectiveConnectionProfile(conn, document);
      if ((eff.effectiveEnvironmentBadge || '').toLowerCase() !== f.environmentFilter.trim().toLowerCase()) return false;
    }
    return true;
  });
}

export function effectiveRdpPreconnectionBlob(rdpSettings) {
  let cleaned = String(rdpSettings?.hyperVVmId || '').trim();
  while (
    cleaned.length >= 2 &&
    ((cleaned.startsWith('{') && cleaned.endsWith('}')) ||
      (cleaned.startsWith('"') && cleaned.endsWith('"')) ||
      (cleaned.startsWith("'") && cleaned.endsWith("'")))
  ) {
    cleaned = cleaned.slice(1, -1).trim();
  }
  if (!cleaned) return '';
  if (rdpSettings?.hyperVEnhancedSession !== false && !/EnhancedMode=/i.test(cleaned)) {
    return `${cleaned};EnhancedMode=1`;
  }
  return cleaned;
}

export function effectiveConnectionPort(conn) {
  const s = conn?.settings || {};
  if (s._type === 'rdp') {
    const pcb = effectiveRdpPreconnectionBlob(s);
    if (pcb && (!s.port || s.port === 3389)) return 2179;
    return s.port > 0 ? s.port : 3389;
  }
  if (typeof s.port === 'number' && s.port > 0) return s.port;
  return PROTOCOL_CATALOG[conn?.protocol]?.defaultPort || 22;
}

/**
 * Ad-hoc Quick Connect (`Ctrl+K`) parser supporting `ssh://user@host:2222`, `rdp://CORP\admin@10.0.0.5`,
 * `vnc://host:5901`, `sftp://...`, `ftp://...`, `https://...`, `wol://AA:BB:CC:DD:EE:FF`, or bare `host:port`.
 */
export function parseQuickConnectInput(rawInput) {
  const raw = String(rawInput || '').trim();
  if (!raw) return null;

  const schemeMatch = raw.match(/^([a-z0-9+.-]+):\/\/(.*)$/i);
  let protocol = 'SSH';
  let remainder = raw;
  if (schemeMatch) {
    const scheme = schemeMatch[1].toLowerCase();
    remainder = schemeMatch[2];
    const map = {
      rdp: 'RDP',
      wts: 'WINDOWS_TERMINAL_SERVICES',
      ssh: 'SSH',
      sftp: 'SFTP',
      scp: 'SCP',
      ftp: 'FTP',
      ftps: 'FTPS',
      vnc: 'VNC',
      ard: 'APPLE_SCREEN_SHARING',
      telnet: 'TELNET',
      http: 'WEB_PAGE',
      https: 'WEB_PAGE',
      wol: 'UTILITY_WAKE_ON_LAN',
      ping: 'UTILITY_PING'
    };
    protocol = map[scheme] || 'SSH';
    if (scheme === 'http' || scheme === 'https') {
      const settings = defaultSettingsForProtocol('WEB_PAGE', raw);
      settings.url = raw;
      return { protocol: 'WEB_PAGE', host: remainder.split('/')[0], username: '', domain: '', port: scheme === 'http' ? 80 : 443, settings };
    }
    if (scheme === 'wol') {
      const settings = defaultSettingsForProtocol('UTILITY_WAKE_ON_LAN', '255.255.255.255');
      settings.wolMacAddress = remainder.trim();
      return { protocol: 'UTILITY_WAKE_ON_LAN', host: '255.255.255.255', username: '', domain: '', port: 9, settings };
    }
  }

  let username = '';
  let domain = '';
  let hostPort = remainder.split('/')[0];
  const atIdx = hostPort.lastIndexOf('@');
  if (atIdx > 0) {
    const userPart = decodeURIComponent(hostPort.slice(0, atIdx));
    hostPort = hostPort.slice(atIdx + 1);
    if (userPart.includes('\\')) {
      [domain, username] = userPart.split('\\', 2);
    } else {
      username = userPart;
    }
  }

  let host = hostPort;
  let port = PROTOCOL_CATALOG[protocol]?.defaultPort || 22;
  const ipv6Match = hostPort.match(/^\[([^\]]+)\](?::(\d+))?$/);
  if (ipv6Match) {
    host = ipv6Match[1];
    if (ipv6Match[2]) port = Number(ipv6Match[2]);
  } else {
    const colonIdx = hostPort.lastIndexOf(':');
    if (colonIdx > 0 && /^\d+$/.test(hostPort.slice(colonIdx + 1))) {
      host = hostPort.slice(0, colonIdx);
      port = Number(hostPort.slice(colonIdx + 1));
      if (!schemeMatch) {
        if (port === 3389 || port === 2179) protocol = 'RDP';
        else if (port === 5900 || port === 5901) protocol = 'VNC';
        else if (port === 21) protocol = 'FTP';
        else if (port === 23) protocol = 'TELNET';
        else if (port === 443 || port === 8006) protocol = 'WEB_PAGE';
      }
    }
  }

  const settings = defaultSettingsForProtocol(protocol, host);
  if ('port' in settings) settings.port = port;
  return { protocol, host, username, domain, port, settings };
}

/**
 * Tokenizes & expands KeySequenceTask / CommandTask variables (`$EffectiveUsername$`, `$Host$`, `{ENTER}`, `{WAIT:300}`).
 */
export function expandTaskVariables(template, profile) {
  const conn = profile?.connection || {};
  const cred = profile?.effectiveCredential || {};
  let out = String(template || '');
  const replacements = {
    $Host$: conn.host || '',
    $URI$: conn.host || '',
    $Name$: conn.name || '',
    $EffectiveUsername$: cred.username || '',
    $EffectiveDomain$: cred.domain || '',
    $EffectivePassword$: cred.secretPassword || ''
  };
  for (const [k, v] of Object.entries(replacements)) {
    out = out.split(k).join(v);
  }
  for (const [key, entry] of Object.entries(profile?.effectiveCustomProperties || {})) {
    out = out.split(`$CustomProperty.${key}$`).join(entry.value || '');
  }
  return out;
}

export function parseKeySequenceSteps(macroSequence, profile) {
  const expanded = expandTaskVariables(macroSequence, profile);
  const steps = [];
  const tokenRegex = /\{([A-Z0-9+_-]+)(?::(\d+))?\}/gi;
  let lastIndex = 0;
  let match;
  while ((match = tokenRegex.exec(expanded)) !== null) {
    if (match.index > lastIndex) {
      steps.push({ type: 'text', value: expanded.slice(lastIndex, match.index) });
    }
    const token = match[1].toUpperCase();
    const arg = match[2] ? Number(match[2]) : null;
    if (token === 'WAIT' || token === 'DELAY') {
      steps.push({ type: 'wait', ms: Math.min(Math.max(arg || 250, 10), 15000) });
    } else {
      steps.push({ type: 'key', key: token });
    }
    lastIndex = tokenRegex.lastIndex;
  }
  if (lastIndex < expanded.length) {
    steps.push({ type: 'text', value: expanded.slice(lastIndex) });
  }
  return steps;
}
