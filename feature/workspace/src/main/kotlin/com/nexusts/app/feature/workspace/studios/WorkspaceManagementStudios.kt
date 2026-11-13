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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.UploadFile
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.nexusts.app.core.vault.gateway.ActiveTunnelLease
import com.nexusts.app.core.vault.gateway.GatewayHealthReport
import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.PortForwardKind
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SshPortForwardRule
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import java.util.UUID

/**
 * 0. Folder Properties & Folder-Level Credential Editor Dialog.
 *    Allows saving credentials directly on a Folder (either selecting an existing vault credential
 *    or creating a new Domain\Username + Password credential inline on the Folder) so child RDP,
 *    SSH, SFTP, VNC, and Cloud connections inherit them automatically via `INHERIT_FROM_PARENT`.
 */
@Composable
fun FolderPropertiesAndCredentialDialog(
    visible: Boolean,
    editingFolder: VaultFolder?,
    allFolders: List<VaultFolder>,
    credentials: List<VaultCredential>,
    childConnectionCount: Int,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveFolderAndCredential: (VaultFolder, VaultCredential?) -> Unit,
    onDeleteFolder: ((String) -> Unit)? = null
) {
    if (!visible) return

    val folderId = remember(editingFolder) { editingFolder?.id ?: UUID.randomUUID().toString() }
    var folderName by remember(editingFolder) { mutableStateOf(editingFolder?.name.orEmpty()) }
    var parentFolderId by remember(editingFolder) { mutableStateOf(editingFolder?.parentFolderId) }
    var parentMenuOpen by remember { mutableStateOf(false) }

    // 0 = Create & Save New Folder Credential inline, 1 = Pick Existing Credential, 2 = Inherit Parent, 3 = Prompt
    var credAssignChoice by remember(editingFolder) {
        mutableIntStateOf(
            when (editingFolder?.inheritable?.credentialMode) {
                InheritanceMode.OVERRIDE_EXPLICIT -> 1
                InheritanceMode.INHERIT_FROM_PARENT -> 2
                InheritanceMode.PROMPT_AT_CONNECT -> 3
                null -> 2
                else -> 2
            }
        )
    }
    var selectedCredId by remember(editingFolder, credentials) {
        mutableStateOf(
            editingFolder?.inheritable?.explicitCredentialId ?: credentials.firstOrNull()?.id
        )
    }
    var credMenuOpen by remember { mutableStateOf(false) }

    // Inline Folder Credential creator fields
    var inlineCredName by remember(editingFolder) { mutableStateOf("") }
    var inlineDomain by remember(editingFolder) { mutableStateOf("") }
    var inlineUsername by remember(editingFolder) { mutableStateOf("") }
    var inlinePassword by remember(editingFolder) { mutableStateOf("") }
    var folderColorHex by remember(editingFolder) {
        mutableStateOf(editingFolder?.inheritable?.displayColorAccentHex)
    }

    val candidateParents = remember(allFolders, folderId) {
        val byId = allFolders.associateBy { it.id }
        fun isSelfOrDescendant(candidateId: String): Boolean {
            val visited = mutableSetOf<String>()
            var cur: String? = candidateId
            while (cur != null && visited.add(cur)) {
                if (cur == folderId) return true
                cur = byId[cur]?.parentFolderId
            }
            return false
        }
        allFolders.filterNot { isSelfOrDescendant(it.id) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(text = strings.dialogFolderTitle, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(strings.dialogFieldName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (candidateParents.isNotEmpty()) {
                    val parentLabel = candidateParents.firstOrNull { it.id == parentFolderId }?.name ?: "-"
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { parentMenuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${strings.dialogFieldFolder}: $parentLabel")
                        }
                        DropdownMenu(
                            expanded = parentMenuOpen,
                            onDismissRequest = { parentMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("-") },
                                onClick = {
                                    parentFolderId = null
                                    parentMenuOpen = false
                                }
                            )
                            candidateParents.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.name) },
                                    onClick = {
                                        parentFolderId = candidate.id
                                        parentMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                RoyalColorSwatchSelector(
                    label = strings.dialogFieldColorAccent,
                    selectedHex = folderColorHex,
                    onSelectHex = { folderColorHex = it }
                )

                HorizontalDivider()

                Text(
                    text = strings.dialogFieldCredentialMode,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = credAssignChoice == 2,
                            onClick = { credAssignChoice = 2 },
                            label = { Text(strings.credModeInheritFolder, style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = credAssignChoice == 0,
                            onClick = { credAssignChoice = 0 },
                            label = { Text(strings.credModeInlineNew, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = credAssignChoice == 1,
                            onClick = { credAssignChoice = 1 },
                            label = { Text(strings.credModeOverrideExplicit, style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = credAssignChoice == 3,
                            onClick = { credAssignChoice = 3 },
                            label = { Text(strings.credModePrompt, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                when (credAssignChoice) {
                    0 -> {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = inlineCredName,
                                    onValueChange = { inlineCredName = it },
                                    label = { Text(strings.dialogFieldName) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = inlineDomain,
                                    onValueChange = { inlineDomain = it },
                                    label = { Text(strings.dialogFieldDomain) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = inlineUsername,
                                    onValueChange = { inlineUsername = it },
                                    label = { Text(strings.dialogFieldUsername) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = inlinePassword,
                                    onValueChange = { inlinePassword = it },
                                    label = { Text(strings.vaultPassphraseLabel) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    1 -> {
                        val activeCred = credentials.firstOrNull { it.id == selectedCredId }
                        val activeCredLabel = activeCred?.let {
                            val userPart = if (it.domain.isNotBlank()) "${it.domain}\\${it.username}" else it.username
                            "${it.name} ($userPart)"
                        } ?: "-"
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { credMenuOpen = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(activeCredLabel)
                            }
                            DropdownMenu(
                                expanded = credMenuOpen,
                                onDismissRequest = { credMenuOpen = false }
                            ) {
                                credentials.forEach { cred ->
                                    val userPart = if (cred.domain.isNotBlank()) "${cred.domain}\\${cred.username}" else cred.username
                                    DropdownMenuItem(
                                        text = { Text("${cred.name} • $userPart") },
                                        onClick = {
                                            selectedCredId = cred.id
                                            credMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (childConnectionCount > 0) {
                    Text(
                        text = "Cascades automatically to $childConnectionCount child connection(s) using '${strings.credModeInheritFolder}'.",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        },
        confirmButton = {
            val inlineValid = credAssignChoice != 0 || inlineUsername.isNotBlank()
            Button(
                enabled = folderName.isNotBlank() && inlineValid,
                onClick = {
                    val newInlineCred = if (credAssignChoice == 0) {
                        val resolvedCredTitle = inlineCredName.trim().ifBlank {
                            "${folderName.trim()} (${if (inlineDomain.isNotBlank()) "${inlineDomain.trim()}\\" else ""}${inlineUsername.trim()})"
                        }
                        VaultCredential(
                            parentFolderId = folderId,
                            name = resolvedCredTitle,
                            sourceKind = CredentialSourceKind.VAULT_INTERNAL,
                            username = inlineUsername.trim(),
                            domain = inlineDomain.trim(),
                            secretPassword = inlinePassword
                        )
                    } else {
                        null
                    }

                    val baseInheritable = editingFolder?.inheritable ?: InheritableProperties()
                    val updatedInheritable = when (credAssignChoice) {
                        0 -> baseInheritable.copy(
                            credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitCredentialId = newInlineCred?.id,
                            displayColorAccentHex = folderColorHex
                        )
                        1 -> baseInheritable.copy(
                            credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                            explicitCredentialId = selectedCredId,
                            displayColorAccentHex = folderColorHex
                        )
                        3 -> baseInheritable.copy(
                            credentialMode = InheritanceMode.PROMPT_AT_CONNECT,
                            explicitCredentialId = null,
                            displayColorAccentHex = folderColorHex
                        )
                        else -> baseInheritable.copy(
                            credentialMode = InheritanceMode.INHERIT_FROM_PARENT,
                            explicitCredentialId = null,
                            displayColorAccentHex = folderColorHex
                        )
                    }

                    val savedFolder = (editingFolder?.copy(
                        parentFolderId = parentFolderId,
                        name = folderName.trim(),
                        inheritable = updatedInheritable
                    ) ?: VaultFolder(
                        id = folderId,
                        parentFolderId = parentFolderId,
                        name = folderName.trim(),
                        inheritable = updatedInheritable
                    ))

                    onSaveFolderAndCredential(savedFolder, newInlineCred)
                }
            ) {
                Text(strings.dialogActionSave)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (editingFolder != null && onDeleteFolder != null) {
                    TextButton(
                        onClick = { onDeleteFolder(editingFolder.id) }
                    ) {
                        Text(strings.actionDeleteItem, color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(strings.dialogActionCancel)
                }
            }
        }
    )
}

/**
 * 1. Expressive Connection Editor Dialog (`Ctrl/Cmd + N` or Edit Connection) supporting all 23 protocols,
 *    Folder-Level Credential & Gateway Inheritance preview/override, and the full 4-Tab Classic Royal TS RDP Suite.
 */
@Composable
fun NewOrEditConnectionDialog(
    visible: Boolean,
    initialConnection: VaultConnection?,
    folders: List<VaultFolder>,
    credentials: List<VaultCredential>,
    resolveFolderPreview: (String?) -> Pair<VaultCredential?, String>,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveConnection: (VaultConnection) -> Unit,
    sshConnections: List<VaultConnection> = emptyList(),
    gateways: List<SecureGatewayConfig> = emptyList()
) {
    if (!visible) return

    val initialRdp = (initialConnection?.settings as? ProtocolSpecificSettings.Rdp) ?: ProtocolSpecificSettings.Rdp()
    val initialSsh = (initialConnection?.settings as? ProtocolSpecificSettings.Ssh) ?: ProtocolSpecificSettings.Ssh()
    val initialVnc = (initialConnection?.settings as? ProtocolSpecificSettings.Vnc) ?: ProtocolSpecificSettings.Vnc()
    val initialFt = (initialConnection?.settings as? ProtocolSpecificSettings.FileTransfer) ?: ProtocolSpecificSettings.FileTransfer()
    val initialSerial = (initialConnection?.settings as? ProtocolSpecificSettings.SerialUart) ?: ProtocolSpecificSettings.SerialUart()
    val initialContainer = (initialConnection?.settings as? ProtocolSpecificSettings.ContainerExec) ?: ProtocolSpecificSettings.ContainerExec()

    var name by remember(initialConnection) { mutableStateOf(initialConnection?.name.orEmpty()) }
    var host by remember(initialConnection) { mutableStateOf(initialConnection?.host.orEmpty()) }
    var selectedProto by remember(initialConnection) {
        mutableStateOf(initialConnection?.protocol ?: VaultProtocolType.RDP)
    }
    var selectedFolderId by remember(initialConnection, folders) {
        mutableStateOf(initialConnection?.parentFolderId ?: folders.firstOrNull()?.id)
    }
    var credentialMode by remember(initialConnection) {
        mutableStateOf(initialConnection?.inheritable?.credentialMode ?: InheritanceMode.INHERIT_FROM_PARENT)
    }
    var explicitCredentialId by remember(initialConnection, credentials) {
        mutableStateOf(initialConnection?.inheritable?.explicitCredentialId ?: credentials.firstOrNull()?.id)
    }
    var gatewayMode by remember(initialConnection) {
        mutableStateOf(initialConnection?.inheritable?.gatewayMode ?: InheritanceMode.INHERIT_FROM_PARENT)
    }
    var explicitGatewayId by remember(initialConnection, gateways) {
        mutableStateOf(initialConnection?.inheritable?.explicitGatewayId ?: gateways.firstOrNull()?.id)
    }

    var protoMenuOpen by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }
    var credMenuOpen by remember { mutableStateOf(false) }
    var gwMenuOpen by remember { mutableStateOf(false) }
    var bastionMenuOpen by remember { mutableStateOf(false) }
    var activeRdpTab by remember(initialConnection) { mutableIntStateOf(0) }

    // VNC & File Transfer ports
    var vncPortText by remember(initialConnection) { mutableStateOf(initialVnc.port.toString()) }
    var ftPortText by remember(initialConnection) { mutableStateOf(initialFt.port.toString()) }

    // USB-C Hardware Serial / UART state
    var serialBaudRate by remember(initialConnection) { mutableIntStateOf(initialSerial.baudRate) }
    var serialParity by remember(initialConnection) { mutableStateOf(initialSerial.parity) }
    var serialFlowControl by remember(initialConnection) { mutableStateOf(initialSerial.flowControl) }
    var serialDtr by remember(initialConnection) { mutableStateOf(initialSerial.dtrEnabled) }
    var serialRts by remember(initialConnection) { mutableStateOf(initialSerial.rtsEnabled) }

    // Kubernetes Pod & Docker Container Exec state
    var containerNamespace by remember(initialConnection) { mutableStateOf(initialContainer.namespace) }
    var containerTargetName by remember(initialConnection) { mutableStateOf(initialContainer.podOrContainerName) }
    var containerShell by remember(initialConnection) { mutableStateOf(initialContainer.shellCommand) }
    var containerFollowLogs by remember(initialConnection) { mutableStateOf(initialContainer.followLogsOnOpen) }

    // SSH Port-Forwarding (-L / -R / -D), X11 & Bastion Jump state
    var sshPortText by remember(initialConnection) { mutableStateOf(initialSsh.port.toString()) }
    var sshAgentForward by remember(initialConnection) { mutableStateOf(initialSsh.enableAgentForwarding) }
    var sshX11Forward by remember(initialConnection) { mutableStateOf(initialSsh.enableX11Forwarding) }
    var sshBastionConnId by remember(initialConnection) { mutableStateOf(initialSsh.bastionJumpConnectionId) }
    var sshStartupCommand by remember(initialConnection) { mutableStateOf(initialSsh.startupCommand) }
    var sshPortForwards by remember(initialConnection) { mutableStateOf(initialSsh.portForwards) }
    var newTunnelKind by remember { mutableStateOf(PortForwardKind.LOCAL) }
    var newTunnelBindPort by remember { mutableStateOf("8080") }
    var newTunnelTargetHost by remember { mutableStateOf("127.0.0.1") }
    var newTunnelTargetPort by remember { mutableStateOf("80") }

    // Classic Royal TS RDP state
    var rdpPortText by remember(initialConnection) { mutableStateOf(initialRdp.port.toString()) }
    var connectToConsole by remember(initialConnection) { mutableStateOf(initialRdp.connectToConsoleAdmin) }
    var hiDpiEnabled by remember(initialConnection) { mutableStateOf(initialRdp.hiDpiEnabled) }
    var desktopScalePercent by remember(initialConnection) { mutableIntStateOf(initialRdp.desktopScaleFactorPercent) }
    var resizeBehavior by remember(initialConnection) { mutableStateOf(initialRdp.resizeBehavior) }
    var customWidthText by remember(initialConnection) { mutableStateOf(initialRdp.customDesktopWidth.toString()) }
    var customHeightText by remember(initialConnection) { mutableStateOf(initialRdp.customDesktopHeight.toString()) }
    var colorDepthBits by remember(initialConnection) { mutableIntStateOf(initialRdp.colorDepthBits) }
    var multiMonitorSpanning by remember(initialConnection) { mutableStateOf(initialRdp.multiMonitorSpanning) }
    var enableH264Avc444 by remember(initialConnection) { mutableStateOf(initialRdp.enableH264Avc444) }

    var redirectClipboard by remember(initialConnection) { mutableStateOf(initialRdp.redirectClipboard) }
    var audioRedirectionMode by remember(initialConnection) { mutableStateOf(initialRdp.audioRedirectionMode) }
    var redirectMicrophone by remember(initialConnection) { mutableStateOf(initialRdp.redirectMicrophone) }
    var redirectDrives by remember(initialConnection) { mutableStateOf(initialRdp.redirectDrives) }
    var redirectPrinters by remember(initialConnection) { mutableStateOf(initialRdp.redirectPrinters) }
    var redirectPorts by remember(initialConnection) { mutableStateOf(initialRdp.redirectPorts) }
    var redirectSmartcard by remember(initialConnection) { mutableStateOf(initialRdp.redirectSmartcardYubiKey) }

    var enableFontSmoothing by remember(initialConnection) { mutableStateOf(initialRdp.enableFontSmoothing) }
    var enableDesktopComposition by remember(initialConnection) { mutableStateOf(initialRdp.enableDesktopComposition) }
    var enableWallpaper by remember(initialConnection) { mutableStateOf(initialRdp.enableWallpaper) }
    var nlaSecurityRequired by remember(initialConnection) { mutableStateOf(initialRdp.nlaSecurityRequired) }
    var restrictedAdminMode by remember(initialConnection) { mutableStateOf(initialRdp.restrictedAdminMode) }
    var ignoreCertWarnings by remember(initialConnection) { mutableStateOf(initialRdp.ignoreCertificateWarnings) }
    var rdGatewayHost by remember(initialConnection) { mutableStateOf(initialRdp.rdGatewayHostname) }

    val (inheritedFolderCred, inheritedFolderSource) = remember(selectedFolderId, folders, credentials) {
        resolveFolderPreview(selectedFolderId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = strings.dialogConnTitle, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                                    when (proto) {
                                        VaultProtocolType.FTP -> ftPortText = "21"
                                        VaultProtocolType.FTPS -> ftPortText = "990"
                                        VaultProtocolType.SFTP, VaultProtocolType.SCP -> ftPortText = "22"
                                        VaultProtocolType.TELNET -> sshPortText = "23"
                                        else -> Unit
                                    }
                                    protoMenuOpen = false
                                }
                            )
                        }
                    }
                }

                // Always-visible core connection identity (Name, Host, Port, Parent Folder) across all tabs!
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.dialogFieldName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(strings.dialogFieldHost) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    when (selectedProto) {
                        VaultProtocolType.RDP -> {
                            OutlinedTextField(
                                value = rdpPortText,
                                onValueChange = { rdpPortText = it.filter(Char::isDigit).take(5) },
                                label = { Text(strings.dialogFieldPort) },
                                singleLine = true,
                                modifier = Modifier.width(92.dp)
                            )
                        }
                        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING -> {
                            OutlinedTextField(
                                value = vncPortText,
                                onValueChange = { vncPortText = it.filter(Char::isDigit).take(5) },
                                label = { Text(strings.dialogFieldPort) },
                                singleLine = true,
                                modifier = Modifier.width(92.dp)
                            )
                        }
                        VaultProtocolType.SFTP, VaultProtocolType.FTP,
                        VaultProtocolType.FTPS, VaultProtocolType.SCP -> {
                            OutlinedTextField(
                                value = ftPortText,
                                onValueChange = { ftPortText = it.filter(Char::isDigit).take(5) },
                                label = { Text(strings.dialogFieldPort) },
                                singleLine = true,
                                modifier = Modifier.width(92.dp)
                            )
                        }
                        VaultProtocolType.TELNET -> {
                            OutlinedTextField(
                                value = sshPortText,
                                onValueChange = { sshPortText = it.filter(Char::isDigit).take(5) },
                                label = { Text(strings.dialogFieldPort) },
                                singleLine = true,
                                modifier = Modifier.width(92.dp)
                            )
                        }
                        else -> Unit
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
                            DropdownMenuItem(
                                text = { Text("-") },
                                onClick = {
                                    selectedFolderId = null
                                    folderMenuOpen = false
                                }
                            )
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

                if (selectedProto == VaultProtocolType.RDP) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            0 to strings.rdpTabGeneral,
                            1 to strings.rdpTabDisplayHiDpi,
                            2 to strings.rdpTabResources,
                            3 to strings.rdpTabExperienceSecurity
                        ).forEach { (idx, label) ->
                            FilterChip(
                                selected = activeRdpTab == idx,
                                onClick = { activeRdpTab = idx },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // Credential & Gateway Inheritance (shown for non-RDP, or shown on Tab 0 for RDP)
                if (selectedProto != VaultProtocolType.RDP || activeRdpTab == 0) {
                    Text(
                        text = strings.dialogFieldCredentialMode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = credentialMode == InheritanceMode.INHERIT_FROM_PARENT,
                            onClick = { credentialMode = InheritanceMode.INHERIT_FROM_PARENT },
                            label = { Text(strings.credModeInheritFolder, style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = credentialMode == InheritanceMode.OVERRIDE_EXPLICIT,
                            onClick = { credentialMode = InheritanceMode.OVERRIDE_EXPLICIT },
                            label = { Text(strings.credModeOverrideExplicit, style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = credentialMode == InheritanceMode.PROMPT_AT_CONNECT,
                            onClick = { credentialMode = InheritanceMode.PROMPT_AT_CONNECT },
                            label = { Text(strings.credModePrompt, style = MaterialTheme.typography.labelSmall) }
                        )
                    }

                    when (credentialMode) {
                        InheritanceMode.INHERIT_FROM_PARENT -> {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val credDesc = inheritedFolderCred?.let {
                                        val userStr = if (it.domain.isNotBlank()) "${it.domain}\\${it.username}" else it.username
                                        "${it.name} ($userStr)"
                                    } ?: "-"
                                    Text(credDesc, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        text = String.format(strings.inspectorInheritedFromFormat, inheritedFolderSource),
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }

                        InheritanceMode.OVERRIDE_EXPLICIT -> {
                            val chosenCred = credentials.firstOrNull { it.id == explicitCredentialId }
                            val chosenCredLabel = chosenCred?.let {
                                val userStr = if (it.domain.isNotBlank()) "${it.domain}\\${it.username}" else it.username
                                "${it.name} ($userStr)"
                            } ?: "-"
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { credMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(chosenCredLabel)
                                }
                                DropdownMenu(
                                    expanded = credMenuOpen,
                                    onDismissRequest = { credMenuOpen = false }
                                ) {
                                    credentials.forEach { cred ->
                                        val userStr = if (cred.domain.isNotBlank()) "${cred.domain}\\${cred.username}" else cred.username
                                        DropdownMenuItem(
                                            text = { Text("${cred.name} • $userStr") },
                                            onClick = {
                                                explicitCredentialId = cred.id
                                                credMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        else -> Unit
                    }

                    if (gateways.isNotEmpty()) {
                        Text(
                            text = strings.inspectorEffectiveGateway,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = gatewayMode == InheritanceMode.INHERIT_FROM_PARENT,
                                onClick = { gatewayMode = InheritanceMode.INHERIT_FROM_PARENT },
                                label = { Text(strings.credModeInheritFolder, style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = gatewayMode == InheritanceMode.OVERRIDE_EXPLICIT,
                                onClick = { gatewayMode = InheritanceMode.OVERRIDE_EXPLICIT },
                                label = { Text(strings.credModeOverrideExplicit, style = MaterialTheme.typography.labelSmall) }
                            )
                            FilterChip(
                                selected = gatewayMode == InheritanceMode.NONE_DIRECT,
                                onClick = { gatewayMode = InheritanceMode.NONE_DIRECT },
                                label = { Text("Direct", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        if (gatewayMode == InheritanceMode.OVERRIDE_EXPLICIT) {
                            val chosenGw = gateways.firstOrNull { it.id == explicitGatewayId }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { gwMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Outlined.DeviceHub, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(chosenGw?.let { "${it.name} (${it.host}:${it.port})" } ?: "-")
                                }
                                DropdownMenu(
                                    expanded = gwMenuOpen,
                                    onDismissRequest = { gwMenuOpen = false }
                                ) {
                                    gateways.forEach { gw ->
                                        DropdownMenuItem(
                                            text = { Text("${gw.name} • ${gw.host}:${gw.port}") },
                                            onClick = {
                                                explicitGatewayId = gw.id
                                                gwMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (selectedProto == VaultProtocolType.RDP) {
                        RdpOptionSwitchRow(
                            label = strings.rdpOptConnectConsole,
                            checked = connectToConsole,
                            onCheckedChange = { connectToConsole = it }
                        )
                    }
                }

                // RDP Tab 1: Display & HiDPI Scaling
                if (selectedProto == VaultProtocolType.RDP && activeRdpTab == 1) {
                    RdpOptionSwitchRow(
                        label = strings.rdpOptHiDpiScaling,
                        checked = hiDpiEnabled,
                        onCheckedChange = { hiDpiEnabled = it }
                    )
                    if (hiDpiEnabled) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(100, 125, 150, 175, 200).forEach { scale ->
                                FilterChip(
                                    selected = desktopScalePercent == scale,
                                    onClick = { desktopScalePercent = scale },
                                    label = { Text("$scale%", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            RdpResizeBehavior.DYNAMIC_DISP_DVC to "Dynamic Resize (DISP_DVC)",
                            RdpResizeBehavior.SMART_SCALE_VIEWPORT to "Smart Scale",
                            RdpResizeBehavior.FIXED_SERVER_RESOLUTION to "Fixed Size"
                        ).forEach { (mode, modeLabel) ->
                            FilterChip(
                                selected = resizeBehavior == mode,
                                onClick = { resizeBehavior = mode },
                                label = { Text(modeLabel, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    if (resizeBehavior == RdpResizeBehavior.FIXED_SERVER_RESOLUTION) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customWidthText,
                                onValueChange = { customWidthText = it.filter(Char::isDigit) },
                                label = { Text("Width (px)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = customHeightText,
                                onValueChange = { customHeightText = it.filter(Char::isDigit) },
                                label = { Text("Height (px)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(32, 24, 16).forEach { depth ->
                            FilterChip(
                                selected = colorDepthBits == depth,
                                onClick = { colorDepthBits = depth },
                                label = { Text("$depth-bit Color", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    RdpOptionSwitchRow(
                        label = strings.rdpOptMultiMonitor,
                        checked = multiMonitorSpanning,
                        onCheckedChange = { multiMonitorSpanning = it }
                    )
                    RdpOptionSwitchRow(
                        label = "AVC444 / H.264 Hardware Acceleration",
                        checked = enableH264Avc444,
                        onCheckedChange = { enableH264Avc444 = it }
                    )
                }

                // RDP Tab 2: Local Resources (Clipboard, Audio Mode, Mic, Drives, Printers, Ports, Smartcard)
                if (selectedProto == VaultProtocolType.RDP && activeRdpTab == 2) {
                    RdpOptionSwitchRow(
                        label = strings.rdpOptClipboardSharing,
                        checked = redirectClipboard,
                        onCheckedChange = { redirectClipboard = it }
                    )

                    Text(
                        text = strings.rdpOptAudioRedirection,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = audioRedirectionMode == RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE,
                            onClick = { audioRedirectionMode = RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE },
                            label = { Text(strings.rdpAudioLocal, style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = audioRedirectionMode == RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST,
                            onClick = { audioRedirectionMode = RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST },
                            label = { Text(strings.rdpAudioRemote, style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = audioRedirectionMode == RdpAudioRedirectionMode.DO_NOT_PLAY,
                            onClick = { audioRedirectionMode = RdpAudioRedirectionMode.DO_NOT_PLAY },
                            label = { Text(strings.rdpAudioMute, style = MaterialTheme.typography.labelSmall) }
                        )
                    }

                    RdpOptionSwitchRow(
                        label = strings.rdpOptMicRedirection,
                        checked = redirectMicrophone,
                        onCheckedChange = { redirectMicrophone = it }
                    )
                    RdpOptionSwitchRow(
                        label = strings.rdpOptDriveRedirection,
                        checked = redirectDrives,
                        onCheckedChange = { redirectDrives = it }
                    )
                    RdpOptionSwitchRow(
                        label = strings.rdpOptPrinterRedirection,
                        checked = redirectPrinters,
                        onCheckedChange = { redirectPrinters = it }
                    )
                    RdpOptionSwitchRow(
                        label = strings.rdpOptPortsRedirection,
                        checked = redirectPorts,
                        onCheckedChange = { redirectPorts = it }
                    )
                    RdpOptionSwitchRow(
                        label = strings.rdpOptSmartcardRedirection,
                        checked = redirectSmartcard,
                        onCheckedChange = { redirectSmartcard = it }
                    )
                }

                // RDP Tab 3: Experience & Security (ClearType, Aero, Wallpaper, NLA, Restricted Admin, Cert, RD Gateway)
                if (selectedProto == VaultProtocolType.RDP && activeRdpTab == 3) {
                    RdpOptionSwitchRow(
                        label = strings.rdpOptClearType,
                        checked = enableFontSmoothing,
                        onCheckedChange = { enableFontSmoothing = it }
                    )
                    RdpOptionSwitchRow(
                        label = strings.rdpOptDesktopComposition,
                        checked = enableDesktopComposition,
                        onCheckedChange = { enableDesktopComposition = it }
                    )
                    RdpOptionSwitchRow(
                        label = strings.rdpOptWallpaper,
                        checked = enableWallpaper,
                        onCheckedChange = { enableWallpaper = it }
                    )
                    HorizontalDivider()
                    RdpOptionSwitchRow(
                        label = strings.rdpOptNlaSecurity,
                        checked = nlaSecurityRequired,
                        onCheckedChange = { nlaSecurityRequired = it }
                    )
                    RdpOptionSwitchRow(
                        label = strings.rdpOptRestrictedAdmin,
                        checked = restrictedAdminMode,
                        onCheckedChange = { restrictedAdminMode = it }
                    )
                    RdpOptionSwitchRow(
                        label = strings.rdpOptIgnoreCert,
                        checked = ignoreCertWarnings,
                        onCheckedChange = { ignoreCertWarnings = it }
                    )
                    OutlinedTextField(
                        value = rdGatewayHost,
                        onValueChange = { rdGatewayHost = it },
                        label = { Text(strings.rdpOptRdGateway) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── Visual SSH Port-Forwarding (-L / -R / -D), X11 & Bastion Studio ──
                if (selectedProto == VaultProtocolType.SSH) {
                    HorizontalDivider()
                    Text(
                        text = strings.sshSectionTunnels,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = sshPortText,
                            onValueChange = { sshPortText = it.filter(Char::isDigit) },
                            label = { Text("SSH Port") },
                            singleLine = true,
                            modifier = Modifier.width(110.dp)
                        )
                        OutlinedTextField(
                            value = sshStartupCommand,
                            onValueChange = { sshStartupCommand = it },
                            label = { Text("Startup Command (Optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    RdpOptionSwitchRow(
                        label = "SSH Agent Forwarding (ssh-agent / Ed25519)",
                        checked = sshAgentForward,
                        onCheckedChange = { sshAgentForward = it }
                    )
                    RdpOptionSwitchRow(
                        label = "X11 Display Forwarding (127.0.0.1:0)",
                        checked = sshX11Forward,
                        onCheckedChange = { sshX11Forward = it }
                    )

                    // Bastion Jump Host Picker
                    val candidateBastions = sshConnections.filter { it.id != initialConnection?.id }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val selectedBastionName = candidateBastions.firstOrNull { it.id == sshBastionConnId }
                            ?.let { "${it.name} (${it.host})" }
                            ?: "Direct (No Bastion Jump Host)"
                        OutlinedButton(
                            onClick = { bastionMenuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.DeviceHub, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ProxyJump: $selectedBastionName")
                        }
                        DropdownMenu(
                            expanded = bastionMenuOpen,
                            onDismissRequest = { bastionMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Direct (No Bastion Jump Host)") },
                                onClick = {
                                    sshBastionConnId = null
                                    bastionMenuOpen = false
                                }
                            )
                            candidateBastions.forEach { bastion ->
                                DropdownMenuItem(
                                    text = { Text("${bastion.name} (${bastion.host})") },
                                    onClick = {
                                        sshBastionConnId = bastion.id
                                        bastionMenuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    // Existing Port-Forwarding Rules List
                    sshPortForwards.forEach { rule ->
                        val cliFlag = when (rule.kind) {
                            PortForwardKind.LOCAL -> "-L ${rule.bindAddress}:${rule.bindPort}:${rule.targetHost}:${rule.targetPort}"
                            PortForwardKind.REMOTE -> "-R ${rule.bindAddress}:${rule.bindPort}:${rule.targetHost}:${rule.targetPort}"
                            PortForwardKind.DYNAMIC_SOCKS5 -> "-D ${rule.bindAddress}:${rule.bindPort} (SOCKS5)"
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = cliFlag,
                                    style = NexusTheme.monospace.logEntry,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { enabled ->
                                        sshPortForwards = sshPortForwards.map {
                                            if (it.id == rule.id) it.copy(enabled = enabled) else it
                                        }
                                    }
                                )
                                IconButton(
                                    onClick = {
                                        sshPortForwards = sshPortForwards.filterNot { it.id == rule.id }
                                    }
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = strings.actionDeleteItem)
                                }
                            }
                        }
                    }

                    // Visual Tunnel Rule Builder (-L / -R / -D)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    PortForwardKind.LOCAL to "-L Local",
                                    PortForwardKind.REMOTE to "-R Remote",
                                    PortForwardKind.DYNAMIC_SOCKS5 to "-D SOCKS5"
                                ).forEach { (kind, label) ->
                                    FilterChip(
                                        selected = newTunnelKind == kind,
                                        onClick = { newTunnelKind = kind },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = newTunnelBindPort,
                                    onValueChange = { newTunnelBindPort = it.filter(Char::isDigit) },
                                    label = { Text("Bind Port") },
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp)
                                )
                                if (newTunnelKind != PortForwardKind.DYNAMIC_SOCKS5) {
                                    OutlinedTextField(
                                        value = newTunnelTargetHost,
                                        onValueChange = { newTunnelTargetHost = it },
                                        label = { Text("Target Host") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = newTunnelTargetPort,
                                        onValueChange = { newTunnelTargetPort = it.filter(Char::isDigit) },
                                        label = { Text("Target Port") },
                                        singleLine = true,
                                        modifier = Modifier.width(96.dp)
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    val bindPort = newTunnelBindPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@FilledTonalButton
                                    val targetPort = if (newTunnelKind == PortForwardKind.DYNAMIC_SOCKS5) {
                                        0
                                    } else {
                                        newTunnelTargetPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@FilledTonalButton
                                    }
                                    sshPortForwards = sshPortForwards + SshPortForwardRule(
                                        kind = newTunnelKind,
                                        bindAddress = "127.0.0.1",
                                        bindPort = bindPort,
                                        targetHost = newTunnelTargetHost.trim().ifBlank { "127.0.0.1" },
                                        targetPort = targetPort,
                                        enabled = true
                                    )
                                }
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add SSH Tunnel Rule")
                            }
                        }
                    }
                }

                // USB-C Hardware Serial / UART Configuration Section
                if (selectedProto == VaultProtocolType.SERIAL) {
                    HorizontalDivider()
                    Text(
                        text = strings.serialSectionUart,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Baud Rate", style = MaterialTheme.typography.labelMedium)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).forEach { baud ->
                                    FilterChip(
                                        selected = serialBaudRate == baud,
                                        onClick = { serialBaudRate = baud },
                                        label = { Text("$baud", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("NONE", "EVEN", "ODD").forEach { p ->
                                    FilterChip(
                                        selected = serialParity == p,
                                        onClick = { serialParity = p },
                                        label = { Text("Parity: $p", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                                listOf("NONE", "RTS_CTS", "XON_XOFF").forEach { fc ->
                                    FilterChip(
                                        selected = serialFlowControl == fc,
                                        onClick = { serialFlowControl = fc },
                                        label = { Text("Flow: $fc", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            RdpOptionSwitchRow("Assert DTR Line on Connect", serialDtr) { serialDtr = it }
                            RdpOptionSwitchRow("Assert RTS Line on Connect", serialRts) { serialRts = it }
                        }
                    }
                }

                // Kubernetes Pod & Docker Container Exec Configuration Section
                if (selectedProto == VaultProtocolType.KUBERNETES_POD ||
                    selectedProto == VaultProtocolType.DOCKER_CONTAINER
                ) {
                    HorizontalDivider()
                    Text(
                        text = strings.containerSectionExec,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (selectedProto == VaultProtocolType.KUBERNETES_POD) {
                                OutlinedTextField(
                                    value = containerNamespace,
                                    onValueChange = { containerNamespace = it },
                                    label = { Text("Kubernetes Namespace") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = containerTargetName,
                                onValueChange = { containerTargetName = it },
                                label = {
                                    Text(
                                        if (selectedProto == VaultProtocolType.KUBERNETES_POD) {
                                            "Pod Name"
                                        } else {
                                            "Docker Container Name / ID"
                                        }
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("/bin/sh", "/bin/bash", "/bin/ash").forEach { sh ->
                                    FilterChip(
                                        selected = containerShell == sh,
                                        onClick = { containerShell = sh },
                                        label = { Text(sh, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            RdpOptionSwitchRow("Open Directly to Live Log Stream (--follow)", containerFollowLogs) {
                                containerFollowLogs = it
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
                    val configuredSettings: ProtocolSpecificSettings = when (selectedProto) {
                        VaultProtocolType.RDP -> initialRdp.copy(
                            port = rdpPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 3389,
                            connectToConsoleAdmin = connectToConsole,
                            hiDpiEnabled = hiDpiEnabled,
                            desktopScaleFactorPercent = desktopScalePercent,
                            deviceScaleFactorPercent = if (desktopScalePercent >= 175) 180 else if (desktopScalePercent >= 125) 140 else 100,
                            enableH264Avc444 = enableH264Avc444,
                            resizeBehavior = resizeBehavior,
                            customDesktopWidth = customWidthText.toIntOrNull()?.coerceIn(640, 8192) ?: 1920,
                            customDesktopHeight = customHeightText.toIntOrNull()?.coerceIn(480, 8192) ?: 1080,
                            multiMonitorSpanning = multiMonitorSpanning,
                            colorDepthBits = colorDepthBits,
                            redirectClipboard = redirectClipboard,
                            audioRedirectionMode = audioRedirectionMode,
                            redirectAudioPlayback = audioRedirectionMode == RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE,
                            redirectMicrophone = redirectMicrophone,
                            redirectDrives = redirectDrives,
                            redirectPrinters = redirectPrinters,
                            redirectPorts = redirectPorts,
                            redirectSmartcardYubiKey = redirectSmartcard,
                            enableFontSmoothing = enableFontSmoothing,
                            enableDesktopComposition = enableDesktopComposition,
                            enableWallpaper = enableWallpaper,
                            nlaSecurityRequired = nlaSecurityRequired,
                            restrictedAdminMode = restrictedAdminMode,
                            ignoreCertificateWarnings = ignoreCertWarnings,
                            rdGatewayHostname = rdGatewayHost.trim()
                        )

                        VaultProtocolType.SSH -> initialSsh.copy(
                            port = sshPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                            bastionJumpConnectionId = sshBastionConnId,
                            enableAgentForwarding = sshAgentForward,
                            enableX11Forwarding = sshX11Forward,
                            portForwards = sshPortForwards,
                            startupCommand = sshStartupCommand.trim()
                        )

                        VaultProtocolType.TELNET ->
                            ((initialConnection?.settings as? ProtocolSpecificSettings.Ssh)
                                ?: ProtocolSpecificSettings.Ssh(port = 23))
                                .copy(port = sshPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 23)

                        VaultProtocolType.SERIAL -> initialSerial.copy(
                            devicePathHint = host.trim(),
                            baudRate = serialBaudRate,
                            parity = serialParity,
                            flowControl = serialFlowControl,
                            dtrEnabled = serialDtr,
                            rtsEnabled = serialRts
                        )

                        VaultProtocolType.KUBERNETES_POD,
                        VaultProtocolType.DOCKER_CONTAINER -> initialContainer.copy(
                            clusterOrSocketEndpoint = if (host.startsWith("http")) host.trim() else "https://${host.trim()}",
                            namespace = containerNamespace.trim().ifBlank { "default" },
                            podOrContainerName = containerTargetName.trim().ifBlank { host.trim() },
                            shellCommand = containerShell,
                            followLogsOnOpen = containerFollowLogs
                        )

                        VaultProtocolType.SFTP, VaultProtocolType.FTP,
                        VaultProtocolType.FTPS, VaultProtocolType.SCP -> {
                            val defaultFtPort = when (selectedProto) {
                                VaultProtocolType.FTP -> 21
                                VaultProtocolType.FTPS -> 990
                                else -> 22
                            }
                            ((initialConnection?.settings as? ProtocolSpecificSettings.FileTransfer)
                                ?: ProtocolSpecificSettings.FileTransfer(port = defaultFtPort))
                                .copy(port = ftPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultFtPort)
                        }

                        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING ->
                            ((initialConnection?.settings as? ProtocolSpecificSettings.Vnc)
                                ?: ProtocolSpecificSettings.Vnc(
                                    appleArdDiffieHellmanAuth = selectedProto == VaultProtocolType.APPLE_SCREEN_SHARING
                                ))
                                .copy(port = vncPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 5900)

                        VaultProtocolType.WEB_PAGE -> {
                            val normalizedUrl = if (host.startsWith("http")) host.trim() else "https://${host.trim()}"
                            ((initialConnection?.settings as? ProtocolSpecificSettings.WebPage)
                                ?: ProtocolSpecificSettings.WebPage(url = normalizedUrl))
                                .copy(url = normalizedUrl)
                        }

                        VaultProtocolType.VMWARE_VSPHERE, VaultProtocolType.PROXMOX_VE,
                        VaultProtocolType.HYPER_V, VaultProtocolType.AWS_EC2,
                        VaultProtocolType.AZURE_VM, VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> {
                            val normalizedUrl = if (host.startsWith("http")) host.trim() else "https://${host.trim()}"
                            ((initialConnection?.settings as? ProtocolSpecificSettings.CloudVirtualization)
                                ?: ProtocolSpecificSettings.CloudVirtualization(apiEndpointUrl = normalizedUrl))
                                .copy(apiEndpointUrl = normalizedUrl)
                        }

                        VaultProtocolType.UTILITY_PING, VaultProtocolType.UTILITY_TRACEROUTE,
                        VaultProtocolType.UTILITY_PORT_SCANNER, VaultProtocolType.UTILITY_WAKE_ON_LAN ->
                            (initialConnection?.settings as? ProtocolSpecificSettings.NetUtility)
                                ?: ProtocolSpecificSettings.NetUtility()
                    }

                    val baseInheritable = initialConnection?.inheritable ?: InheritableProperties()
                    val resolvedInheritable = baseInheritable.copy(
                        credentialMode = credentialMode,
                        explicitCredentialId = if (credentialMode == InheritanceMode.OVERRIDE_EXPLICIT) {
                            explicitCredentialId
                        } else {
                            null
                        },
                        gatewayMode = gatewayMode,
                        explicitGatewayId = if (gatewayMode == InheritanceMode.OVERRIDE_EXPLICIT) {
                            explicitGatewayId
                        } else {
                            null
                        }
                    )

                    val savedConn = (initialConnection?.copy(
                        parentFolderId = selectedFolderId,
                        name = name.trim(),
                        host = host.trim(),
                        protocol = selectedProto,
                        inheritable = resolvedInheritable,
                        settings = configuredSettings
                    ) ?: VaultConnection(
                        parentFolderId = selectedFolderId,
                        name = name.trim(),
                        host = host.trim(),
                        protocol = selectedProto,
                        osFamily = if (selectedProto == VaultProtocolType.RDP) "Windows" else "Linux",
                        inheritable = resolvedInheritable,
                        settings = configuredSettings
                    ))

                    onSaveConnection(savedConn)
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

@Composable
private fun RdpOptionSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
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
    onUpdateCredential: (VaultCredential) -> Unit = {},
    onDeleteCredential: (String) -> Unit = {},
    onRequestImportVaultFile: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var showCredDialog by remember { mutableStateOf(false) }
    var editingCredential by remember { mutableStateOf<VaultCredential?>(null) }
    var credName by remember { mutableStateOf("") }
    var credDomain by remember { mutableStateOf("") }
    var credUser by remember { mutableStateOf("") }
    var credSecret by remember { mutableStateOf("") }
    var credTotpSecret by remember { mutableStateOf("") }
    var credKind by remember { mutableStateOf(CredentialSourceKind.VAULT_INTERNAL) }

    fun openForCreate() {
        editingCredential = null
        credName = ""
        credDomain = ""
        credUser = ""
        credSecret = ""
        credTotpSecret = ""
        credKind = CredentialSourceKind.VAULT_INTERNAL
        showCredDialog = true
    }

    fun openForEdit(cred: VaultCredential) {
        editingCredential = cred
        credName = cred.name
        credDomain = cred.domain
        credUser = cred.username
        credSecret = cred.secretPassword
        credTotpSecret = cred.totpSecretBase32
        credKind = cred.sourceKind
        showCredDialog = true
    }

    if (showCredDialog) {
        AlertDialog(
            onDismissRequest = { showCredDialog = false },
            title = { Text(if (editingCredential != null) strings.dialogActionSave else strings.studioActionAddCredential) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                        value = credDomain,
                        onValueChange = { credDomain = it },
                        label = { Text(strings.dialogFieldDomain) },
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
                    OutlinedTextField(
                        value = credTotpSecret,
                        onValueChange = { credTotpSecret = it },
                        label = { Text("${strings.inspectorTotpCode} (Base32)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = credName.isNotBlank(),
                    onClick = {
                        val existing = editingCredential
                        if (existing != null) {
                            onUpdateCredential(
                                existing.copy(
                                    name = credName.trim(),
                                    sourceKind = credKind,
                                    domain = credDomain.trim(),
                                    username = credUser.trim(),
                                    secretPassword = credSecret,
                                    totpSecretBase32 = credTotpSecret.trim(),
                                    externalReferenceUri = if (credKind != CredentialSourceKind.VAULT_INTERNAL) credUser.trim() else existing.externalReferenceUri
                                )
                            )
                        } else {
                            onCreateCredential(
                                VaultCredential(
                                    name = credName.trim(),
                                    sourceKind = credKind,
                                    domain = credDomain.trim(),
                                    username = credUser.trim(),
                                    secretPassword = credSecret,
                                    totpSecretBase32 = credTotpSecret.trim(),
                                    externalReferenceUri = if (credKind != CredentialSourceKind.VAULT_INTERNAL) credUser.trim() else ""
                                )
                            )
                        }
                        showCredDialog = false
                    }
                ) {
                    Text(strings.dialogActionSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCredDialog = false }) { Text(strings.dialogActionCancel) }
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
                OutlinedButton(onClick = onRequestImportVaultFile) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.studioActionImportPasswords)
                }
                FilledTonalButton(onClick = onGenerateEd25519Key) {
                    Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.inspectorGenerateSshKey)
                }
                Button(onClick = { openForCreate() }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.studioActionAddCredential)
                }
            }
        }

        HorizontalDivider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(credentials, key = { it.id }) { cred ->
                val userDisplay = if (cred.domain.isNotBlank()) {
                    "${cred.domain}\\${cred.username.ifBlank { "-" }}"
                } else {
                    cred.username.ifBlank { "-" }
                }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cred.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${cred.sourceKind.name} • $userDisplay" +
                                        if (cred.keyFingerprintSha256.isNotBlank()) " • ${cred.keyFingerprintSha256}" else "",
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (cred.username.isNotBlank()) {
                                    AssistChip(
                                        onClick = { clipboard.setText(AnnotatedString(cred.username)) },
                                        label = { Text("User", style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp)) }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                if (cred.secretPassword.isNotBlank()) {
                                    AssistChip(
                                        onClick = { clipboard.setText(AnnotatedString(cred.secretPassword)) },
                                        label = { Text("Secret", style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp)) }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                if (cred.publicKeyOpenSsh.isNotBlank()) {
                                    IconButton(
                                        onClick = { clipboard.setText(AnnotatedString(cred.publicKeyOpenSsh)) }
                                    ) {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy SSH Public Key", modifier = Modifier.size(18.dp))
                                    }
                                }
                                IconButton(onClick = { openForEdit(cred) }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Edit Credential", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onDeleteCredential(cred.id) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete Credential",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        if (cred.totpSecretBase32.isNotBlank()) {
                            LiveTotpAuthenticatorCard(
                                totpSecretBase32 = cred.totpSecretBase32,
                                title = strings.inspectorTotpCode
                            )
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
    activeLeases: List<ActiveTunnelLease> = emptyList(),
    credentials: List<VaultCredential> = emptyList(),
    onUpdateGateway: (SecureGatewayConfig) -> Unit = {},
    onDeleteGateway: (String) -> Unit = {},
    onOpenGatewayLease: (SecureGatewayConfig) -> Unit = {},
    onCloseGatewayLease: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showGwModal by remember { mutableStateOf(false) }
    var editingGw by remember { mutableStateOf<SecureGatewayConfig?>(null) }
    var gwName by remember { mutableStateOf("") }
    var gwHost by remember { mutableStateOf("") }
    var gwPort by remember { mutableStateOf("1080") }
    var gwKind by remember { mutableStateOf(GatewayTransportKind.SOCKS5_PROXY) }
    var gwCredentialId by remember { mutableStateOf<String?>(null) }
    var gwCredMenuOpen by remember { mutableStateOf(false) }

    fun openAddGateway() {
        editingGw = null
        gwName = ""
        gwHost = ""
        gwPort = "1080"
        gwKind = GatewayTransportKind.SOCKS5_PROXY
        gwCredentialId = null
        showGwModal = true
    }

    fun openEditGateway(gw: SecureGatewayConfig) {
        editingGw = gw
        gwName = gw.name
        gwHost = gw.host
        gwPort = gw.port.toString()
        gwKind = gw.kind
        gwCredentialId = gw.credentialId
        showGwModal = true
    }

    if (showGwModal) {
        AlertDialog(
            onDismissRequest = { showGwModal = false },
            title = { Text(if (editingGw != null) strings.dialogActionSave else strings.studioActionAddGateway) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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
                    if (credentials.isNotEmpty()) {
                        val selectedCredName = credentials.firstOrNull { it.id == gwCredentialId }?.name ?: "-"
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { gwCredMenuOpen = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("${strings.inspectorEffectiveCredential}: $selectedCredName")
                            }
                            DropdownMenu(
                                expanded = gwCredMenuOpen,
                                onDismissRequest = { gwCredMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("-") },
                                    onClick = {
                                        gwCredentialId = null
                                        gwCredMenuOpen = false
                                    }
                                )
                                credentials.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text(c.name) },
                                        onClick = {
                                            gwCredentialId = c.id
                                            gwCredMenuOpen = false
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
                    enabled = gwName.isNotBlank() && gwHost.isNotBlank(),
                    onClick = {
                        val existing = editingGw
                        val saved = (existing?.copy(
                            name = gwName.trim(),
                            kind = gwKind,
                            host = gwHost.trim(),
                            port = gwPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443,
                            credentialId = gwCredentialId
                        ) ?: SecureGatewayConfig(
                            name = gwName.trim(),
                            kind = gwKind,
                            host = gwHost.trim(),
                            port = gwPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443,
                            credentialId = gwCredentialId
                        ))
                        if (existing != null) onUpdateGateway(saved) else onAddGateway(saved)
                        showGwModal = false
                    }
                ) {
                    Text(strings.dialogActionSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGwModal = false }) { Text(strings.dialogActionCancel) }
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
            Button(onClick = { openAddGateway() }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.studioActionAddGateway)
            }
        }

        HorizontalDivider()

        if (activeLeases.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Active Loopback Relay Leases (${activeLeases.size})", style = MaterialTheme.typography.labelLarge)
                    activeLeases.forEach { lease ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${lease.localBindHost}:${lease.localBindPort} -> ${lease.remoteTargetHost}:${lease.remoteTargetPort} (${lease.gatewayKind.name})",
                                style = NexusTheme.monospace.keycapShortcut,
                                color = NexusTheme.extendedColors.statusConnected
                            )
                            TextButton(onClick = { onCloseGatewayLease(lease.leaseId) }) {
                                Text("Close Lease")
                            }
                        }
                    }
                }
            }
        }

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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onOpenGatewayLease(gw) }) {
                                Text("Bind Loopback")
                            }
                            FilledTonalButton(onClick = { onProbeGateway(gw) }) {
                                Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.studioActionProbeGateway)
                            }
                            IconButton(onClick = { openEditGateway(gw) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "Edit Gateway", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onDeleteGateway(gw.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete Gateway",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
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
    onAddKeySequenceTask: (KeySequenceTask) -> Unit = {},
    onDeleteCommandTask: (String) -> Unit = {},
    onDeleteKeySequenceTask: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showNewTaskModal by remember { mutableStateOf(false) }
    var taskName by remember { mutableStateOf("") }
    var taskCmd by remember { mutableStateOf("") }
    var taskBroadcast by remember { mutableStateOf(false) }

    var showNewMacroModal by remember { mutableStateOf(false) }
    var macroName by remember { mutableStateOf("") }
    var macroSequence by remember { mutableStateOf("\$EffectiveUsername\${TAB}\$EffectivePassword\${ENTER}") }

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
                    RdpOptionSwitchRow(
                        label = strings.actionBroadcastInput,
                        checked = taskBroadcast,
                        onCheckedChange = { taskBroadcast = it }
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
                                broadcastToAllSelectedSessions = taskBroadcast
                            )
                        )
                        taskName = ""
                        taskCmd = ""
                        taskBroadcast = false
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

    if (showNewMacroModal) {
        AlertDialog(
            onDismissRequest = { showNewMacroModal = false },
            title = { Text("Add Key Sequence Macro") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = macroName,
                        onValueChange = { macroName = it },
                        label = { Text(strings.dialogFieldName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = macroSequence,
                        onValueChange = { macroSequence = it },
                        label = { Text("Macro ({ENTER}, {TAB}, {WAIT:250}, \$EffectivePassword\$)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = macroName.isNotBlank() && macroSequence.isNotBlank(),
                    onClick = {
                        onAddKeySequenceTask(
                            KeySequenceTask(
                                name = macroName.trim(),
                                macroSequence = macroSequence
                            )
                        )
                        macroName = ""
                        showNewMacroModal = false
                    }
                ) {
                    Text(strings.dialogActionSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewMacroModal = false }) { Text(strings.dialogActionCancel) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { showNewMacroModal = true }) {
                    Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Key Sequence")
                }
                Button(onClick = { showNewTaskModal = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.studioActionAddTask)
                }
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
                                text = task.commandTemplate + if (task.broadcastToAllSelectedSessions) " • [Broadcast]" else "",
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(onClick = { onRunCommandTask(task) }) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.studioActionRunTask)
                            }
                            IconButton(onClick = { onDeleteCommandTask(task.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete Task",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(onClick = { onRunKeySequenceTask(macro) }) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.studioActionRunTask)
                            }
                            IconButton(onClick = { onDeleteKeySequenceTask(macro.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Delete Macro",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 5. Dual-Engine Live Google Drive Sync (Direct Persistent SAF + Drive REST API v3 OAuth 2.0),
 *    3-Way Vector Clock Conflict Merge, & Play-Protect-Safe GitHub Releases Update Studio (`v1.1.1`).
 */
@Composable
fun DriveSyncAndConflictStudioView(
    vaultDocument: NxtsVaultDocument,
    useSharedTeamDrive: Boolean,
    strings: WorkspaceLocalizedStrings,
    onSelectSyncZone: (Boolean) -> Unit,
    onTriggerDeltaSync: () -> Unit,
    initialOAuthWebClientId: String = "",
    initialSharedFolderId: String = "17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS",
    onSaveOAuthConfig: (String, String) -> Unit = { _, _ -> },
    driveSignedInEmail: String = "",
    driveLinkedSafLabel: String = "",
    driveSyncStatusBanner: String = "",
    driveIsSyncing: Boolean = false,
    driveAutoSyncOnSave: Boolean = true,
    onToggleAutoSyncOnSave: (Boolean) -> Unit = {},
    rememberVaultPassphrase: Boolean = false,
    onSaveVaultSyncPassphrase: (String, Boolean) -> Unit = { _, _ -> },
    onClearVaultSyncPassphrase: () -> Unit = {},
    onCreateLiveSafDriveVault: () -> Unit = {},
    onLinkExistingSafDriveVault: () -> Unit = {},
    onUnlinkSafDriveVault: () -> Unit = {},
    onAuthorizeAndSyncOAuthDrive: () -> Unit = {},
    onSignOutOAuthDrive: () -> Unit = {},
    githubReleaseBanner: String = "",
    githubReleaseUrl: String = "",
    onCheckGitHubReleases: () -> Unit = {},
    onOpenGitHubReleaseUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var webClientIdInput by remember(initialOAuthWebClientId) { mutableStateOf(initialOAuthWebClientId) }
    var sharedFolderInput by remember(initialSharedFolderId) {
        mutableStateOf(initialSharedFolderId.ifBlank { "17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS" })
    }
    var savedBanner by remember { mutableStateOf(false) }
    var syncPassInput by remember { mutableStateOf("") }
    var rememberPassToggle by remember(rememberVaultPassphrase) { mutableStateOf(rememberVaultPassphrase) }
    var passSavedBanner by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                enabled = !driveIsSyncing,
                onClick = onTriggerDeltaSync
            ) {
                Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (driveIsSyncing) "Syncing…" else strings.actionSyncNow)
            }
        }

        HorizontalDivider()

        if (driveSyncStatusBanner.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = driveSyncStatusBanner,
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
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
                    text = "Argon2id (64 MiB, t=3, p=4) + AES-256-GCM • Provisioned Folder: NexusTS Vaults (17C98pGqQUAoQwQ-OSLUHPj4_dtb4_wtS)",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Card 0: Zero-Knowledge Vault Encryption Passphrase (Android Keystore Backed)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Zero-Knowledge Vault Encryption Passphrase (Local + Live Drive Sync)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = syncPassInput,
                    onValueChange = {
                        syncPassInput = it
                        passSavedBanner = false
                    },
                    label = { Text(strings.vaultPassphraseLabel) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                RdpOptionSwitchRow(
                    label = "Store Vault Passphrase in Hardware-Backed Android Keystore (AES-256-GCM)",
                    checked = rememberPassToggle,
                    onCheckedChange = { rememberPassToggle = it }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        enabled = syncPassInput.length >= 6,
                        onClick = {
                            onSaveVaultSyncPassphrase(syncPassInput, rememberPassToggle)
                            syncPassInput = ""
                            passSavedBanner = true
                        }
                    ) {
                        Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Apply Vault Passphrase")
                    }
                    if (rememberVaultPassphrase) {
                        OutlinedButton(
                            onClick = {
                                onClearVaultSyncPassphrase()
                                rememberPassToggle = false
                                passSavedBanner = false
                            }
                        ) {
                            Text("Clear Keystore Passphrase")
                        }
                    }
                    if (passSavedBanner || rememberVaultPassphrase) {
                        Text(
                            text = if (rememberVaultPassphrase) "Keystore Protected" else "Session Active",
                            style = MaterialTheme.typography.labelMedium,
                            color = NexusTheme.extendedColors.statusConnected
                        )
                    }
                }
            }
        }

        // Card 1: Zero-Cloud-Console Live Google Drive SAF Sync (Works Immediately on Direct APK Installs!)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "1. Zero-Setup Live Google Drive Sync (Direct SAF — Recommended for Direct Installs)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (driveLinkedSafLabel.isNotBlank()) {
                        "Linked Live Google Drive Vault: $driveLinkedSafLabel"
                    } else {
                        "Select 'NexusTS Vaults' in Google Drive once to grant persistent two-way 3-way-merge sync — zero Cloud Console OAuth setup required."
                    },
                    style = NexusTheme.monospace.keycapShortcut,
                    color = if (driveLinkedSafLabel.isNotBlank()) {
                        NexusTheme.extendedColors.statusConnected
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onCreateLiveSafDriveVault) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.syncLinkDriveSafCreate)
                    }
                    FilledTonalButton(onClick = onLinkExistingSafDriveVault) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.syncLinkDriveSafOpen)
                    }
                    if (driveLinkedSafLabel.isNotBlank()) {
                        OutlinedButton(onClick = onUnlinkSafDriveVault) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Unlink")
                        }
                    }
                }
                RdpOptionSwitchRow(
                    label = "Auto-Sync Encrypted .nxts to Google Drive on Every Vault Save",
                    checked = driveAutoSyncOnSave,
                    onCheckedChange = onToggleAutoSyncOnSave
                )
            }
        }

        // Card 2: Google Drive REST API v3 (OAuth 2.0 AuthorizationClient — Web Client ID Optional)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "2. Google Drive REST API v3 & Team Drive Advisory Locking (OAuth 2.0)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (driveSignedInEmail.isNotBlank()) {
                            "Authorized Account: $driveSignedInEmail"
                        } else {
                            "com.nexusts.app • Release SHA-1: A6:AF:15:A9:BF:0B:FE:38:D4:66:46:3E:3E:72:A6:5B:4F:9C:4D:F6"
                        },
                        style = NexusTheme.monospace.keycapShortcut,
                        color = if (driveSignedInEmail.isNotBlank()) {
                            NexusTheme.extendedColors.statusConnected
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(
                        onClick = {
                            clipboard.setText(AnnotatedString("A6:AF:15:A9:BF:0B:FE:38:D4:66:46:3E:3E:72:A6:5B:4F:9C:4D:F6"))
                        },
                        label = { Text("Copy SHA-1") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onAuthorizeAndSyncOAuthDrive) {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.syncOAuthConnectButton)
                    }
                    if (driveSignedInEmail.isNotBlank()) {
                        OutlinedButton(onClick = onSignOutOAuthDrive) {
                            Text("Sign Out")
                        }
                    }
                }
                OutlinedTextField(
                    value = webClientIdInput,
                    onValueChange = {
                        webClientIdInput = it
                        savedBanner = false
                    },
                    label = { Text("Optional Enterprise OIDC Web Client ID (*.apps.googleusercontent.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sharedFolderInput,
                    onValueChange = {
                        sharedFolderInput = it
                        savedBanner = false
                    },
                    label = { Text("Google Drive Folder ID (Default: NexusTS Vaults)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            onSaveOAuthConfig(webClientIdInput.trim(), sharedFolderInput.trim())
                            savedBanner = true
                        }
                    ) {
                        Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save OAuth 2.0 Configuration")
                    }
                    if (savedBanner) {
                        Text(
                            text = "Saved to Credential Manager Sync Store",
                            style = MaterialTheme.typography.labelMedium,
                            color = NexusTheme.extendedColors.statusConnected
                        )
                    }
                }
            }
        }

        // Card 3: Direct-Install GitHub Releases Update Checker (Play Protect Compliant)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "3. Direct-Install GitHub Releases Update Checker (Play Protect Compliant)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = githubReleaseBanner.ifBlank {
                        "Checks https://github.com/petrosvassiliou/NexusTS/releases/latest over TLS with zero REQUEST_INSTALL_PACKAGES permissions."
                    },
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCheckGitHubReleases) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.syncCheckGitHubUpdates)
                    }
                    if (githubReleaseUrl.isNotBlank()) {
                        Button(onClick = { onOpenGitHubReleaseUrl(githubReleaseUrl) }) {
                            Text("Open Verified GitHub Release")
                        }
                    }
                }
            }
        }
    }
}


