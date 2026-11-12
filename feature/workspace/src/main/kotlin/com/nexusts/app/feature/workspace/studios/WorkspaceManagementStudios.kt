package com.nexusts.app.feature.workspace.studios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.gateway.GatewayHealthReport
import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings

/**
 * 1. Expressive Connection Editor Dialog (`Ctrl/Cmd + N`) supporting all 21 protocols,
 *    Folder hierarchy assignment, and Property Inheritance mode selection.
 */
@Composable
fun NewOrEditConnectionDialog(
    visible: Boolean,
    folders: List<VaultFolder>,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveConnection: (VaultConnection) -> Unit
) {
    if (!visible) return

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var selectedProto by remember { mutableStateOf(VaultProtocolType.SSH) }
    var selectedFolderId by remember { mutableStateOf(folders.firstOrNull()?.id) }
    var protoMenuOpen by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = strings.dialogConnTitle, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.dialogFieldName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(strings.dialogFieldHost) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Protocol Selector
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { protoMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${strings.dialogFieldProtocol}: ${selectedProto.name}")
                    }
                    DropdownMenu(
                        expanded = protoMenuOpen,
                        onDismissRequest = { protoMenuOpen = false }
                    ) {
                        VaultProtocolType.entries.forEach { proto ->
                            DropdownMenuItem(
                                text = { Text(proto.name) },
                                onClick = {
                                    selectedProto = proto
                                    protoMenuOpen = false
                                }
                            )
                        }
                    }
                }

                // Parent Folder Selector (Property Inheritance Cascade)
                if (folders.isNotEmpty()) {
                    val activeFolderName = folders.firstOrNull { it.id == selectedFolderId }?.name ?: "-"
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { folderMenuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${strings.dialogFieldFolder}: $activeFolderName")
                        }
                        DropdownMenu(
                            expanded = folderMenuOpen,
                            onDismissRequest = { folderMenuOpen = false }
                        ) {
                            folders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        selectedFolderId = folder.id
                                        folderMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && host.isNotBlank(),
                onClick = {
                    val defaultSettings: ProtocolSpecificSettings = when (selectedProto) {
                        VaultProtocolType.RDP -> ProtocolSpecificSettings.Rdp()
                        VaultProtocolType.SSH, VaultProtocolType.TELNET, VaultProtocolType.SERIAL ->
                            ProtocolSpecificSettings.Ssh()
                        VaultProtocolType.SFTP, VaultProtocolType.FTP,
                        VaultProtocolType.FTPS, VaultProtocolType.SCP ->
                            ProtocolSpecificSettings.FileTransfer()
                        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING ->
                            ProtocolSpecificSettings.Vnc(
                                appleArdDiffieHellmanAuth = selectedProto == VaultProtocolType.APPLE_SCREEN_SHARING
                            )
                        VaultProtocolType.WEB_PAGE ->
                            ProtocolSpecificSettings.WebPage(
                                url = if (host.startsWith("http")) host else "https://$host"
                            )
                        VaultProtocolType.VMWARE_VSPHERE, VaultProtocolType.PROXMOX_VE,
                        VaultProtocolType.HYPER_V, VaultProtocolType.AWS_EC2,
                        VaultProtocolType.AZURE_VM, VaultProtocolType.WINDOWS_TERMINAL_SERVICES ->
                            ProtocolSpecificSettings.CloudVirtualization(
                                apiEndpointUrl = if (host.startsWith("http")) host else "https://$host"
                            )
                        VaultProtocolType.UTILITY_PING, VaultProtocolType.UTILITY_TRACEROUTE,
                        VaultProtocolType.UTILITY_PORT_SCANNER, VaultProtocolType.UTILITY_WAKE_ON_LAN ->
                            ProtocolSpecificSettings.NetUtility()
                    }
                    onSaveConnection(
                        VaultConnection(
                            parentFolderId = selectedFolderId,
                            name = name.trim(),
                            host = host.trim(),
                            protocol = selectedProto,
                            inheritable = InheritableProperties(
                                credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                                gatewayMode = InheritanceMode.INHERIT_FROM_PARENT
                            ),
                            settings = defaultSettings
                        )
                    )
                    onDismiss()
                }
            ) {
                Text(strings.dialogActionSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.dialogActionCancel)
            }
        }
    )
}

/**
 * 2. Credential Vault & SSH Agent Studio supporting Internal Zero-Knowledge Credentials,
 *    OpenSSH Ed25519 Keygen, 1Password Connect, Bitwarden, KeePass (.kdbx), and FIDO2 Passkeys.
 */
@Composable
fun CredentialVaultStudioView(
    credentials: List<VaultCredential>,
    strings: WorkspaceLocalizedStrings,
    onGenerateEd25519Key: () -> Unit,
    onCreateCredential: (VaultCredential) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var showAddDialog by remember { mutableStateOf(false) }
    var credName by remember { mutableStateOf("") }
    var credUser by remember { mutableStateOf("") }
    var credSecret by remember { mutableStateOf("") }
    var credKind by remember { mutableStateOf(CredentialSourceKind.VAULT_INTERNAL) }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(strings.studioActionAddCredential) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            CredentialSourceKind.VAULT_INTERNAL,
                            CredentialSourceKind.ONEPASSWORD_CONNECT,
                            CredentialSourceKind.BITWARDEN_VAULT,
                            CredentialSourceKind.KEEPASS_KDBX,
                            CredentialSourceKind.HARDWARE_FIDO2_PASSKEY
                        ).forEach { kind ->
                            FilterChip(
                                selected = credKind == kind,
                                onClick = { credKind = kind },
                                label = { Text(kind.name.substringBefore('_'), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = credName,
                        onValueChange = { credName = it },
                        label = { Text(strings.dialogFieldName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = credUser,
                        onValueChange = { credUser = it },
                        label = { Text(strings.dialogFieldUsername) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = credSecret,
                        onValueChange = { credSecret = it },
                        label = { Text(strings.vaultPassphraseLabel) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = credName.isNotBlank(),
                    onClick = {
                        onCreateCredential(
                            VaultCredential(
                                name = credName.trim(),
                                sourceKind = credKind,
                                username = credUser.trim(),
                                secretPassword = credSecret,
                                externalReferenceUri = if (credKind != CredentialSourceKind.VAULT_INTERNAL) credUser.trim() else ""
                            )
                        )
                        credName = ""
                        credUser = ""
                        credSecret = ""
                        showAddDialog = false
                    }
                ) {
                    Text(strings.dialogActionSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(strings.dialogActionCancel) }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(strings.navCredentials, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = strings.studioCredentialsSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onGenerateEd25519Key) {
                    Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.inspectorGenerateSshKey)
                }
                Button(onClick = { showAddDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.studioActionAddCredential)
                }
            }
        }

        HorizontalDivider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(credentials, key = { it.id }) { cred ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cred.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${cred.sourceKind.name} • ${cred.username.ifBlank { "-" }}" +
                                    if (cred.keyFingerprintSha256.isNotBlank()) " • ${cred.keyFingerprintSha256}" else "",
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (cred.publicKeyOpenSsh.isNotBlank()) {
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(cred.publicKeyOpenSsh)) }
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 3. Secure Gateways & Proxies Studio (NexusTS Secure Gateway, SSH Bastion, SOCKS5, HTTP CONNECT).
 */
@Composable
fun SecureGatewayStudioView(
    gateways: List<SecureGatewayConfig>,
    healthReports: Map<String, GatewayHealthReport>,
    strings: WorkspaceLocalizedStrings,
    onProbeGateway: (SecureGatewayConfig) -> Unit,
    onAddGateway: (SecureGatewayConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddModal by remember { mutableStateOf(false) }
    var gwName by remember { mutableStateOf("") }
    var gwHost by remember { mutableStateOf("") }
    var gwPort by remember { mutableStateOf("1080") }
    var gwKind by remember { mutableStateOf(GatewayTransportKind.SOCKS5_PROXY) }

    if (showAddModal) {
        AlertDialog(
            onDismissRequest = { showAddModal = false },
            title = { Text(strings.studioActionAddGateway) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GatewayTransportKind.entries.forEach { kind ->
                            FilterChip(
                                selected = gwKind == kind,
                                onClick = {
                                    gwKind = kind
                                    gwPort = when (kind) {
                                        GatewayTransportKind.NEXUSTS_SECURE_GATEWAY -> "443"
                                        GatewayTransportKind.SSH_TUNNEL_BASTION -> "22"
                                        GatewayTransportKind.SOCKS5_PROXY -> "1080"
                                        GatewayTransportKind.HTTP_CONNECT_PROXY -> "8080"
                                    }
                                },
                                label = { Text(kind.name.substringBefore('_'), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = gwName,
                        onValueChange = { gwName = it },
                        label = { Text(strings.dialogFieldName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = gwHost,
                        onValueChange = { gwHost = it },
                        label = { Text(strings.dialogFieldHost) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = gwPort,
                        onValueChange = { gwPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(strings.dialogFieldPort) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = gwName.isNotBlank() && gwHost.isNotBlank(),
                    onClick = {
                        onAddGateway(
                            SecureGatewayConfig(
                                name = gwName.trim(),
                                kind = gwKind,
                                host = gwHost.trim(),
                                port = gwPort.toIntOrNull() ?: 443
                            )
                        )
                        gwName = ""
                        gwHost = ""
                        showAddModal = false
                    }
                ) {
                    Text(strings.dialogActionSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddModal = false }) { Text(strings.dialogActionCancel) }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.DeviceHub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(strings.navGateways, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = strings.studioGatewaysSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(onClick = { showAddModal = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.studioActionAddGateway)
            }
        }

        HorizontalDivider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(gateways, key = { it.id }) { gw ->
                val report = healthReports[gw.id]
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(gw.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${gw.kind.name} • ${gw.host}:${gw.port}" +
                                    (report?.let { " • ${it.latencyMs} ms (${it.protocolBanner})" }.orEmpty()),
                                style = NexusTheme.monospace.keycapShortcut,
                                color = if (report?.reachable == true) {
                                    NexusTheme.extendedColors.statusConnected
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        FilledTonalButton(onClick = { onProbeGateway(gw) }) {
                            Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.studioActionProbeGateway)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 4. Automation Command Tasks & Key Sequence Macros Studio (`{WAIT:300}`, `{ENTER}`, Broadcast).
 */
@Composable
fun AutomationTasksStudioView(
    commandTasks: List<CommandTask>,
    keySequenceTasks: List<KeySequenceTask>,
    strings: WorkspaceLocalizedStrings,
    onRunCommandTask: (CommandTask) -> Unit,
    onRunKeySequenceTask: (KeySequenceTask) -> Unit,
    onAddCommandTask: (CommandTask) -> Unit,
    modifier: Modifier = Modifier
) {
    var showNewTaskModal by remember { mutableStateOf(false) }
    var taskName by remember { mutableStateOf("") }
    var taskCmd by remember { mutableStateOf("") }

    if (showNewTaskModal) {
        AlertDialog(
            onDismissRequest = { showNewTaskModal = false },
            title = { Text(strings.studioActionAddTask) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = taskName,
                        onValueChange = { taskName = it },
                        label = { Text(strings.dialogFieldName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = taskCmd,
                        onValueChange = { taskCmd = it },
                        label = { Text(strings.drawerBroadcastPrompt) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = taskName.isNotBlank() && taskCmd.isNotBlank(),
                    onClick = {
                        onAddCommandTask(
                            CommandTask(
                                name = taskName.trim(),
                                commandTemplate = taskCmd.trim(),
                                broadcastToAllSelectedSessions = true
                            )
                        )
                        taskName = ""
                        taskCmd = ""
                        showNewTaskModal = false
                    }
                ) {
                    Text(strings.dialogActionSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewTaskModal = false }) { Text(strings.dialogActionCancel) }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(strings.navTasks, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = strings.studioTasksSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(onClick = { showNewTaskModal = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.studioActionAddTask)
            }
        }

        HorizontalDivider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(commandTasks, key = { it.id }) { task ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = task.commandTemplate,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(onClick = { onRunCommandTask(task) }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.studioActionRunTask)
                        }
                    }
                }
            }
            items(keySequenceTasks, key = { it.id }) { macro ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(macro.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = macro.macroSequence,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        FilledTonalButton(onClick = { onRunKeySequenceTask(macro) }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.studioActionRunTask)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 5. Google Drive API v3 Sync, Team Drive Sharing, Vector Clock Revision & Advisory Lock Studio.
 */
@Composable
fun DriveSyncAndConflictStudioView(
    vaultDocument: NxtsVaultDocument,
    useSharedTeamDrive: Boolean,
    strings: WorkspaceLocalizedStrings,
    onSelectSyncZone: (Boolean) -> Unit,
    onTriggerDeltaSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(strings.navDriveSync, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = strings.studioSyncSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(onClick = onTriggerDeltaSync) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.actionSyncNow)
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = !useSharedTeamDrive,
                onClick = { onSelectSyncZone(false) },
                label = { Text(strings.syncZoneAppData) }
            )
            FilterChip(
                selected = useSharedTeamDrive,
                onClick = { onSelectSyncZone(true) },
                label = { Text(strings.syncZoneTeamDrive) }
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(vaultDocument.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = String.format(strings.syncStatusSyncedFormat, vaultDocument.revision) +
                        " • VectorClock=${vaultDocument.vectorClock}",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = NexusTheme.extendedColors.statusConnected
                )
                Text(
                    text = "Argon2id (64 MiB, t=3, p=4) + AES-256-GCM • SQLCipher Local Cache Active",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
