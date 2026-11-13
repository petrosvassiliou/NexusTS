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
    onSaveFolderAndCredential: (VaultFolder, VaultCredential?) -> Unit
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
                else -> 0
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
                            selected = credAssignChoice == 0,
                            onClick = { credAssignChoice = 0 },
                            label = { Text(strings.credModeInlineNew, style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = credAssignChoice == 1,
                            onClick = { credAssignChoice = 1 },
                            label = { Text(strings.credModeOverrideExplicit, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
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
            TextButton(onClick = onDismiss) {
                Text(strings.dialogActionCancel)
            }
        }
    )
}

/**
 * 1. Expressive Connection Editor Dialog (`Ctrl/Cmd + N` or Edit Connection) supporting all 21 protocols,
 *    Folder-Level Credential Inheritance preview/override, and the full 4-Tab Classic Royal TS RDP Suite:
 *    - General & Console (`/admin`)
 *    - Display & HiDPI Scaling (`100%–200%`, Dynamic Resize `DISP_DVC`, Color Depth, Multi-Monitor)
 *    - Local Resources (Clipboard Sharing, 3-Mode Audio Redirection, Mic, Drives, Printers, Serial Ports, Smartcard)
 *    - Experience & Security (ClearType Font Smoothing, Aero Desktop Composition, Wallpaper, NLA, Restricted Admin, RD Gateway)
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
    sshConnections: List<VaultConnection> = emptyList()
) {
    if (!visible) return

    val initialRdp = (initialConnection?.settings as? ProtocolSpecificSettings.Rdp) ?: ProtocolSpecificSettings.Rdp()
    val initialSsh = (initialConnection?.settings as? ProtocolSpecificSettings.Ssh) ?: ProtocolSpecificSettings.Ssh()

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

    var protoMenuOpen by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }
    var credMenuOpen by remember { mutableStateOf(false) }
    var bastionMenuOpen by remember { mutableStateOf(false) }
    var activeRdpTab by remember(initialConnection) { mutableIntStateOf(0) }

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
                                    protoMenuOpen = false
                                }
                            )
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

                // General Fields (always shown for non-RDP, or shown on Tab 0 for RDP)
                if (selectedProto != VaultProtocolType.RDP || activeRdpTab == 0) {
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
                        if (selectedProto == VaultProtocolType.RDP) {
                            OutlinedTextField(
                                value = rdpPortText,
                                onValueChange = { rdpPortText = it.filter(Char::isDigit) },
                                label = { Text(strings.dialogFieldPort) },
                                singleLine = true,
                                modifier = Modifier.width(92.dp)
                            )
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

                    // Credential Inheritance / Folder Credential Application
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

                        VaultProtocolType.TELNET, VaultProtocolType.SERIAL ->
                            (initialConnection?.settings as? ProtocolSpecificSettings.Ssh)
                                ?: ProtocolSpecificSettings.Ssh()

                        VaultProtocolType.SFTP, VaultProtocolType.FTP,
                        VaultProtocolType.FTPS, VaultProtocolType.SCP ->
                            (initialConnection?.settings as? ProtocolSpecificSettings.FileTransfer)
                                ?: ProtocolSpecificSettings.FileTransfer()

                        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING ->
                            (initialConnection?.settings as? ProtocolSpecificSettings.Vnc)
                                ?: ProtocolSpecificSettings.Vnc(
                                    appleArdDiffieHellmanAuth = selectedProto == VaultProtocolType.APPLE_SCREEN_SHARING
                                )

                        VaultProtocolType.WEB_PAGE ->
                            (initialConnection?.settings as? ProtocolSpecificSettings.WebPage)
                                ?: ProtocolSpecificSettings.WebPage(
                                    url = if (host.startsWith("http")) host else "https://$host"
                                )

                        VaultProtocolType.VMWARE_VSPHERE, VaultProtocolType.PROXMOX_VE,
                        VaultProtocolType.HYPER_V, VaultProtocolType.AWS_EC2,
                        VaultProtocolType.AZURE_VM, VaultProtocolType.WINDOWS_TERMINAL_SERVICES ->
                            (initialConnection?.settings as? ProtocolSpecificSettings.CloudVirtualization)
                                ?: ProtocolSpecificSettings.CloudVirtualization(
                                    apiEndpointUrl = if (host.startsWith("http")) host else "https://$host"
                                )

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
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var showAddDialog by remember { mutableStateOf(false) }
    var credName by remember { mutableStateOf("") }
    var credUser by remember { mutableStateOf("") }
    var credSecret by remember { mutableStateOf("") }
    var credTotpSecret by remember { mutableStateOf("") }
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
                        onCreateCredential(
                            VaultCredential(
                                name = credName.trim(),
                                sourceKind = credKind,
                                username = credUser.trim(),
                                secretPassword = credSecret,
                                totpSecretBase32 = credTotpSecret.trim(),
                                externalReferenceUri = if (credKind != CredentialSourceKind.VAULT_INTERNAL) credUser.trim() else ""
                            )
                        )
                        credName = ""
                        credUser = ""
                        credSecret = ""
                        credTotpSecret = ""
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
    initialOAuthWebClientId: String = "",
    initialSharedFolderId: String = "",
    onSaveOAuthConfig: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var webClientIdInput by remember(initialOAuthWebClientId) { mutableStateOf(initialOAuthWebClientId) }
    var sharedFolderInput by remember(initialSharedFolderId) { mutableStateOf(initialSharedFolderId) }
    var savedBanner by remember { mutableStateOf(false) }

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

        // Enterprise OAuth 2.0 Web Client ID & Team Drive Provisioning Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Google Cloud OAuth 2.0 & Credential Manager Provisioning",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "com.nexusts.app • Release SHA-1: A6:AF:15:A9:BF:0B:FE:38:D4:66:46:3E:3E:72:A6:5B:4F:9C:4D:F6",
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                OutlinedTextField(
                    value = webClientIdInput,
                    onValueChange = {
                        webClientIdInput = it
                        savedBanner = false
                    },
                    label = { Text("OAuth 2.0 Web Client ID (*.apps.googleusercontent.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (useSharedTeamDrive) {
                    OutlinedTextField(
                        value = sharedFolderInput,
                        onValueChange = {
                            sharedFolderInput = it
                            savedBanner = false
                        },
                        label = { Text("Shared Google Drive / Team Drive Folder ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
    }
}
