package com.nexusts.app.feature.workspace.studios

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.components.NexusActionChip
import com.nexusts.app.core.ui.components.NexusChoiceChip
import com.nexusts.app.core.ui.components.NexusOutlinedButton
import com.nexusts.app.core.ui.components.NexusPrimaryButton
import com.nexusts.app.core.ui.components.NexusTonalButton
import com.nexusts.app.core.ui.input.nexusDesktopPointerButtons
import com.nexusts.app.core.ui.input.nexusHoverTracker
import com.nexusts.app.core.ui.input.rememberDesktopHoverState
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.error.VaultHierarchySanitizer
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
import com.nexusts.app.core.vault.model.RdpGfxCodecProfile
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SshPortForwardRule
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolCategory
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import java.util.UUID

/**
 * 0. Folder Properties & Folder-Level Credential Editor Dialog.
 *    Allows saving credentials directly on a Folder (either selecting an existing vault credential
 *    or creating a new Domain\Username + Password credential inline on the Folder) so child RDP,
 *    SSH, SFTP, VNC, and Cloud connections inherit them automatically via `INHERIT_FROM_PARENT`.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    onDeleteFolder: ((String) -> Unit)? = null,
    defaultParentFolderId: String? = null
) {
    if (!visible) return

    val folderId = remember(editingFolder) { editingFolder?.id ?: UUID.randomUUID().toString() }
    var folderName by remember(editingFolder) { mutableStateOf(editingFolder?.name.orEmpty()) }
    var parentFolderId by remember(editingFolder, defaultParentFolderId) {
        mutableStateOf(editingFolder?.parentFolderId ?: defaultParentFolderId)
    }

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

    val reservedRootNameError = remember(folderName, parentFolderId, strings) {
        if (parentFolderId == null && VaultHierarchySanitizer.isReservedCredentialsFolderName(folderName)) {
            strings.credentialsSystemFolderReservedError
        } else {
            null
        }
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
                    isError = reservedRootNameError != null,
                    supportingText = if (reservedRootNameError != null) {
                        {
                            Text(
                                text = reservedRootNameError,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (candidateParents.isNotEmpty()) {
                    val parentLabel = candidateParents.firstOrNull { it.id == parentFolderId }?.name ?: "-"
                    val parentOptions = remember(candidateParents) {
                        buildList {
                            add(NexusSelectOption<String?>(value = null, title = "-"))
                            candidateParents.forEach { candidate ->
                                add(NexusSelectOption(value = candidate.id, title = candidate.name))
                            }
                        }
                    }
                    NexusDialogSelectField(
                        buttonText = "${strings.dialogFieldFolder}: $parentLabel",
                        dialogTitle = strings.dialogFieldFolder,
                        cancelLabel = strings.dialogActionCancel,
                        selectedValue = parentFolderId,
                        options = parentOptions,
                        leadingIcon = Icons.Outlined.Folder,
                        onSelect = { parentFolderId = it }
                    )
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

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NexusChoiceChip(
                        selected = credAssignChoice == 2,
                        onClick = { credAssignChoice = 2 },
                        label = strings.credModeInheritFolder
                    )
                    NexusChoiceChip(
                        selected = credAssignChoice == 0,
                        onClick = { credAssignChoice = 0 },
                        label = strings.credModeInlineNew
                    )
                    NexusChoiceChip(
                        selected = credAssignChoice == 1,
                        onClick = { credAssignChoice = 1 },
                        label = strings.credModeOverrideExplicit
                    )
                    NexusChoiceChip(
                        selected = credAssignChoice == 3,
                        onClick = { credAssignChoice = 3 },
                        label = strings.credModePrompt
                    )
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
                        val credOptions = remember(credentials) {
                            credentials.map { cred ->
                                val userPart = if (cred.domain.isNotBlank()) "${cred.domain}\\${cred.username}" else cred.username
                                NexusSelectOption<String?>(
                                    value = cred.id,
                                    title = cred.name,
                                    subtitle = userPart
                                )
                            }
                        }
                        NexusDialogSelectField(
                            buttonText = activeCredLabel,
                            dialogTitle = strings.inspectorEffectiveCredential,
                            cancelLabel = strings.dialogActionCancel,
                            selectedValue = selectedCredId,
                            options = credOptions,
                            leadingIcon = Icons.Outlined.Key,
                            onSelect = { selectedCredId = it }
                        )
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
                enabled = folderName.isNotBlank() && inlineValid && reservedRootNameError == null,
                onClick = {
                    val newInlineCred = if (credAssignChoice == 0) {
                        val resolvedCredTitle = inlineCredName.trim().ifBlank {
                            "${folderName.trim()} (${if (inlineDomain.isNotBlank()) "${inlineDomain.trim()}\\" else ""}${inlineUsername.trim()})"
                        }
                        VaultCredential(
                            parentFolderId = null,
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
@OptIn(ExperimentalLayoutApi::class)
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
    gateways: List<SecureGatewayConfig> = emptyList(),
    defaultFolderId: String? = null,
    defaultProtocol: VaultProtocolType = VaultProtocolType.RDP,
    onPickSafSharedFolder: (((String) -> Unit) -> Unit)? = null
) {
    if (!visible) return

    val initialRdp = (initialConnection?.settings as? ProtocolSpecificSettings.Rdp) ?: ProtocolSpecificSettings.Rdp()
    val initialSsh = (initialConnection?.settings as? ProtocolSpecificSettings.Ssh) ?: ProtocolSpecificSettings.Ssh()
    val initialVnc = (initialConnection?.settings as? ProtocolSpecificSettings.Vnc) ?: ProtocolSpecificSettings.Vnc(
        appleArdDiffieHellmanAuth = (initialConnection?.protocol ?: defaultProtocol) == VaultProtocolType.APPLE_SCREEN_SHARING
    )
    val initialFt = (initialConnection?.settings as? ProtocolSpecificSettings.FileTransfer) ?: ProtocolSpecificSettings.FileTransfer()
    val initialSerial = (initialConnection?.settings as? ProtocolSpecificSettings.SerialUart) ?: ProtocolSpecificSettings.SerialUart()
    val initialContainer = (initialConnection?.settings as? ProtocolSpecificSettings.ContainerExec) ?: ProtocolSpecificSettings.ContainerExec()
    val initialWeb = (initialConnection?.settings as? ProtocolSpecificSettings.WebPage) ?: ProtocolSpecificSettings.WebPage()
    val initialCloud = (initialConnection?.settings as? ProtocolSpecificSettings.CloudVirtualization) ?: ProtocolSpecificSettings.CloudVirtualization()
    val initialNet = (initialConnection?.settings as? ProtocolSpecificSettings.NetUtility) ?: ProtocolSpecificSettings.NetUtility()

    var name by remember(initialConnection) { mutableStateOf(initialConnection?.name.orEmpty()) }
    var host by remember(initialConnection) { mutableStateOf(initialConnection?.host.orEmpty()) }
    var selectedProto by remember(initialConnection, defaultProtocol) {
        mutableStateOf(initialConnection?.protocol ?: defaultProtocol)
    }
    var selectedFolderId by remember(initialConnection, defaultFolderId) {
        mutableStateOf(initialConnection?.parentFolderId ?: defaultFolderId)
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

    var activeRdpTab by remember(initialConnection) { mutableIntStateOf(0) }

    // VNC & File Transfer state
    var vncPortText by remember(initialConnection) { mutableStateOf(initialVnc.port.toString()) }
    var vncArdAuth by remember(initialConnection, defaultProtocol) {
        mutableStateOf(
            if (initialConnection != null) {
                initialVnc.appleArdDiffieHellmanAuth || initialConnection.protocol == VaultProtocolType.APPLE_SCREEN_SHARING
            } else {
                defaultProtocol == VaultProtocolType.APPLE_SCREEN_SHARING
            }
        )
    }
    var vncViewOnly by remember(initialConnection) { mutableStateOf(initialVnc.viewOnly) }
    var vncSharedSession by remember(initialConnection) { mutableStateOf(initialVnc.sharedSession) }

    var ftPortText by remember(initialConnection, defaultProtocol) {
        val defaultFt = when {
            initialConnection != null -> initialFt.port
            defaultProtocol == VaultProtocolType.FTP -> 21
            defaultProtocol == VaultProtocolType.FTPS -> 990
            else -> initialFt.port
        }
        mutableStateOf(defaultFt.toString())
    }
    var ftImplicitTls by remember(initialConnection) { mutableStateOf(initialFt.ftpsImplicitTls) }
    var ftRemoteDir by remember(initialConnection) { mutableStateOf(initialFt.initialRemoteDirectory) }
    var ftPassiveMode by remember(initialConnection) { mutableStateOf(initialFt.passiveMode) }
    var ftVerifyTls by remember(initialConnection) { mutableStateOf(initialFt.verifyTls) }

    // Web, Cloud & Network Utilities state
    var webIgnoreSelfSigned by remember(initialConnection) { mutableStateOf(initialWeb.ignoreSelfSignedInternalCert) }
    var webAutoFill by remember(initialConnection) { mutableStateOf(initialWeb.autoFillEnabled) }
    var webInjectBasicAuth by remember(initialConnection) { mutableStateOf(initialWeb.injectHttpBasicAuth) }

    var cloudApiEndpoint by remember(initialConnection) { mutableStateOf(initialCloud.apiEndpointUrl) }
    var cloudRegionOrCluster by remember(initialConnection) { mutableStateOf(initialCloud.regionOrCluster) }
    var cloudSubscriptionId by remember(initialConnection) { mutableStateOf(initialCloud.subscriptionOrAccountId) }
    var cloudRealmOrTenant by remember(initialConnection) { mutableStateOf(initialCloud.realmOrDomain) }
    var cloudVerifyTls by remember(initialConnection) { mutableStateOf(initialCloud.verifyTls) }

    var netWolMac by remember(initialConnection) { mutableStateOf(initialNet.wolMacAddress) }
    var netWolBroadcast by remember(initialConnection) { mutableStateOf(initialNet.wolBroadcastAddress) }
    var netPortStartText by remember(initialConnection) { mutableStateOf(initialNet.portScanRangeStart.toString()) }
    var netPortEndText by remember(initialConnection) { mutableStateOf(initialNet.portScanRangeEnd.toString()) }
    var netPingCountText by remember(initialConnection) { mutableStateOf(initialNet.pingCount.toString()) }
    var netMaxHopsText by remember(initialConnection) { mutableStateOf(initialNet.tracerouteMaxHops.toString()) }

    // USB-C Hardware Serial / UART state
    var serialBaudRate by remember(initialConnection) { mutableIntStateOf(initialSerial.baudRate) }
    var serialParity by remember(initialConnection) { mutableStateOf(initialSerial.parity) }
    var serialFlowControl by remember(initialConnection) { mutableStateOf(initialSerial.flowControl) }
    var serialDtr by remember(initialConnection) { mutableStateOf(initialSerial.dtrEnabled) }
    var serialRts by remember(initialConnection) { mutableStateOf(initialSerial.rtsEnabled) }

    // Kubernetes Pod & Docker Container Exec state
    var containerEndpoint by remember(initialConnection, defaultProtocol) {
        val defaultEp = if (initialConnection == null && defaultProtocol == VaultProtocolType.KUBERNETES_POD) {
            ""
        } else {
            initialContainer.clusterOrSocketEndpoint
        }
        mutableStateOf(defaultEp)
    }
    var containerNamespace by remember(initialConnection) { mutableStateOf(initialContainer.namespace) }
    var containerTargetName by remember(initialConnection) { mutableStateOf(initialContainer.podOrContainerName) }
    var containerSubName by remember(initialConnection) { mutableStateOf(initialContainer.subContainerName) }
    var containerShell by remember(initialConnection) { mutableStateOf(initialContainer.shellCommand) }
    var containerFollowLogs by remember(initialConnection) { mutableStateOf(initialContainer.followLogsOnOpen) }
    var containerVerifyTls by remember(initialConnection) { mutableStateOf(initialContainer.verifyTls) }

    // SSH Port-Forwarding (-L / -R / -D), X11 & Bastion Jump state
    var sshPortText by remember(initialConnection, defaultProtocol) {
        val defaultSshPort = if ((initialConnection?.protocol ?: defaultProtocol) == VaultProtocolType.TELNET && initialConnection == null) {
            23
        } else {
            initialSsh.port
        }
        mutableStateOf(defaultSshPort.toString())
    }
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
    var gfxCodecProfile by remember(initialConnection) { mutableStateOf(initialRdp.gfxCodecProfile) }

    var redirectClipboard by remember(initialConnection) { mutableStateOf(initialRdp.redirectClipboard) }
    var audioRedirectionMode by remember(initialConnection) { mutableStateOf(initialRdp.audioRedirectionMode) }
    var redirectMicrophone by remember(initialConnection) { mutableStateOf(initialRdp.redirectMicrophone) }
    var redirectDrives by remember(initialConnection) {
        mutableStateOf(initialRdp.redirectDrives || initialRdp.redirectedDriveSafUris.isNotEmpty())
    }
    var redirectedDriveSafUris by remember(initialConnection) {
        mutableStateOf(initialRdp.redirectedDriveSafUris)
    }
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
    var hyperVVmId by remember(initialConnection) { mutableStateOf(initialRdp.hyperVVmId) }
    var hyperVEnhancedSession by remember(initialConnection) { mutableStateOf(initialRdp.hyperVEnhancedSession) }

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
                // Protocol Selector (top-level grouped & searchable modal picker — never clipped by AlertDialog window)
                NexusProtocolSelectorField(
                    label = strings.dialogFieldProtocol,
                    cancelLabel = strings.dialogActionCancel,
                    selectedProtocol = selectedProto,
                    onSelectProtocol = { proto ->
                        selectedProto = proto
                        when (proto) {
                            VaultProtocolType.FTP -> ftPortText = "21"
                            VaultProtocolType.FTPS -> ftPortText = if (ftImplicitTls) "990" else "21"
                            VaultProtocolType.SFTP, VaultProtocolType.SCP -> ftPortText = "22"
                            VaultProtocolType.TELNET -> sshPortText = "23"
                            VaultProtocolType.SSH -> if (sshPortText == "23") sshPortText = "22"
                            VaultProtocolType.APPLE_SCREEN_SHARING -> vncArdAuth = true
                            VaultProtocolType.VNC -> vncArdAuth = false
                            VaultProtocolType.KUBERNETES_POD -> {
                                if (containerEndpoint == "unix:///var/run/docker.sock") containerEndpoint = ""
                            }
                            VaultProtocolType.DOCKER_CONTAINER -> {
                                if (containerEndpoint.isBlank()) containerEndpoint = "unix:///var/run/docker.sock"
                            }
                            else -> Unit
                        }
                    }
                )

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
                        VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> {
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
                    val folderOptions = remember(folders) {
                        buildList {
                            add(NexusSelectOption<String?>(value = null, title = "-"))
                            folders.forEach { folder ->
                                add(NexusSelectOption(value = folder.id, title = folder.name))
                            }
                        }
                    }
                    NexusDialogSelectField(
                        buttonText = "${strings.dialogFieldFolder}: $activeFolderName",
                        dialogTitle = strings.dialogFieldFolder,
                        cancelLabel = strings.dialogActionCancel,
                        selectedValue = selectedFolderId,
                        options = folderOptions,
                        leadingIcon = Icons.Outlined.Folder,
                        onSelect = { selectedFolderId = it }
                    )
                }

                val isRdpOrWts = selectedProto == VaultProtocolType.RDP ||
                    selectedProto == VaultProtocolType.WINDOWS_TERMINAL_SERVICES
                if (isRdpOrWts) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            0 to strings.rdpTabGeneral,
                            1 to strings.rdpTabDisplayHiDpi,
                            2 to strings.rdpTabResources,
                            3 to strings.rdpTabExperienceSecurity
                        ).forEach { (idx, label) ->
                            NexusChoiceChip(
                                selected = activeRdpTab == idx,
                                onClick = { activeRdpTab = idx },
                                label = label
                            )
                        }
                    }
                }

                // Credential & Gateway Inheritance (shown for non-RDP, or shown on Tab 0 for RDP)
                if (!isRdpOrWts || activeRdpTab == 0) {
                    Text(
                        text = strings.dialogFieldCredentialMode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NexusChoiceChip(
                            selected = credentialMode == InheritanceMode.INHERIT_FROM_PARENT,
                            onClick = { credentialMode = InheritanceMode.INHERIT_FROM_PARENT },
                            label = strings.credModeInheritFolder
                        )
                        NexusChoiceChip(
                            selected = credentialMode == InheritanceMode.OVERRIDE_EXPLICIT,
                            onClick = { credentialMode = InheritanceMode.OVERRIDE_EXPLICIT },
                            label = strings.credModeOverrideExplicit
                        )
                        NexusChoiceChip(
                            selected = credentialMode == InheritanceMode.PROMPT_AT_CONNECT,
                            onClick = { credentialMode = InheritanceMode.PROMPT_AT_CONNECT },
                            label = strings.credModePrompt
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
                            val credOptions = remember(credentials) {
                                credentials.map { cred ->
                                    val userStr = if (cred.domain.isNotBlank()) "${cred.domain}\\${cred.username}" else cred.username
                                    NexusSelectOption<String?>(
                                        value = cred.id,
                                        title = cred.name,
                                        subtitle = userStr
                                    )
                                }
                            }
                            NexusDialogSelectField(
                                buttonText = chosenCredLabel,
                                dialogTitle = strings.inspectorEffectiveCredential,
                                cancelLabel = strings.dialogActionCancel,
                                selectedValue = explicitCredentialId,
                                options = credOptions,
                                leadingIcon = Icons.Outlined.Key,
                                onSelect = { explicitCredentialId = it }
                            )
                        }

                        else -> Unit
                    }

                    if (gateways.isNotEmpty()) {
                        Text(
                            text = strings.inspectorEffectiveGateway,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            NexusChoiceChip(
                                selected = gatewayMode == InheritanceMode.INHERIT_FROM_PARENT,
                                onClick = { gatewayMode = InheritanceMode.INHERIT_FROM_PARENT },
                                label = strings.credModeInheritFolder
                            )
                            NexusChoiceChip(
                                selected = gatewayMode == InheritanceMode.OVERRIDE_EXPLICIT,
                                onClick = { gatewayMode = InheritanceMode.OVERRIDE_EXPLICIT },
                                label = strings.credModeOverrideExplicit
                            )
                            NexusChoiceChip(
                                selected = gatewayMode == InheritanceMode.NONE_DIRECT,
                                onClick = { gatewayMode = InheritanceMode.NONE_DIRECT },
                                label = "Direct"
                            )
                        }
                        if (gatewayMode == InheritanceMode.OVERRIDE_EXPLICIT) {
                            val chosenGw = gateways.firstOrNull { it.id == explicitGatewayId }
                            val gwOptions = remember(gateways) {
                                gateways.map { gw ->
                                    NexusSelectOption<String?>(
                                        value = gw.id,
                                        title = gw.name,
                                        subtitle = "${gw.host}:${gw.port}"
                                    )
                                }
                            }
                            NexusDialogSelectField(
                                buttonText = chosenGw?.let { "${it.name} (${it.host}:${it.port})" } ?: "-",
                                dialogTitle = strings.inspectorEffectiveGateway,
                                cancelLabel = strings.dialogActionCancel,
                                selectedValue = explicitGatewayId,
                                options = gwOptions,
                                leadingIcon = Icons.Outlined.DeviceHub,
                                onSelect = { explicitGatewayId = it }
                            )
                        }
                    }

                    if (isRdpOrWts) {
                        RdpOptionSwitchRow(
                            label = strings.rdpOptClipboardSharing,
                            checked = redirectClipboard,
                            onCheckedChange = { redirectClipboard = it }
                        )
                        RdpOptionSwitchRow(
                            label = strings.rdpOptConnectConsole,
                            checked = connectToConsole,
                            onCheckedChange = { connectToConsole = it }
                        )
                        OutlinedTextField(
                            value = hyperVVmId,
                            onValueChange = { newGuid ->
                                val wasBlank = hyperVVmId.isBlank()
                                val nowBlank = newGuid.isBlank()
                                hyperVVmId = newGuid
                                if (wasBlank && !nowBlank) {
                                    if (rdpPortText == "3389") rdpPortText = "2179"
                                    ignoreCertWarnings = true
                                    if (!initialRdp.hyperVEnhancedSessionCustomized) {
                                        hyperVEnhancedSession = true
                                    }
                                } else if (!wasBlank && nowBlank && rdpPortText == "2179") {
                                    rdpPortText = "3389"
                                }
                            },
                            label = { Text("Hyper-V VM ID (GUID — Optional TCP 2179)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (hyperVVmId.isNotBlank()) {
                            RdpOptionSwitchRow(
                                label = "Hyper-V Enhanced Session Mode (VMBus)",
                                checked = hyperVEnhancedSession,
                                onCheckedChange = { hyperVEnhancedSession = it }
                            )
                        }
                    }
                }

                // RDP Tab 1: Display & HiDPI Scaling
                if (isRdpOrWts && activeRdpTab == 1) {
                    RdpOptionSwitchRow(
                        label = strings.rdpOptHiDpiScaling,
                        checked = hiDpiEnabled,
                        onCheckedChange = { hiDpiEnabled = it }
                    )
                    if (hiDpiEnabled) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(100, 125, 150, 175, 200).forEach { scale ->
                                NexusChoiceChip(
                                    selected = desktopScalePercent == scale,
                                    onClick = { desktopScalePercent = scale },
                                    label = if (scale == 200) "200% (Default)" else "$scale%"
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            RdpResizeBehavior.DYNAMIC_DISP_DVC to "Dynamic Resize (DISP_DVC)",
                            RdpResizeBehavior.SMART_SCALE_VIEWPORT to "Smart Scale",
                            RdpResizeBehavior.FIXED_SERVER_RESOLUTION to "Fixed Size"
                        ).forEach { (mode, modeLabel) ->
                            NexusChoiceChip(
                                selected = resizeBehavior == mode,
                                onClick = { resizeBehavior = mode },
                                label = modeLabel
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

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(32, 24, 16).forEach { depth ->
                            NexusChoiceChip(
                                selected = colorDepthBits == depth,
                                onClick = { colorDepthBits = depth },
                                label = "$depth-bit Color"
                            )
                        }
                    }

                    RdpOptionSwitchRow(
                        label = strings.rdpOptMultiMonitor,
                        checked = multiMonitorSpanning,
                        onCheckedChange = { multiMonitorSpanning = it }
                    )

                    Text(
                        text = "Graphics Codec (RDPGFX)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NexusChoiceChip(
                            selected = gfxCodecProfile == RdpGfxCodecProfile.ADAPTIVE_PROGRESSIVE,
                            onClick = { gfxCodecProfile = RdpGfxCodecProfile.ADAPTIVE_PROGRESSIVE },
                            label = "Adaptive Progressive GFX (Fastest)"
                        )
                        NexusChoiceChip(
                            selected = gfxCodecProfile == RdpGfxCodecProfile.AVC420_VIDEO,
                            onClick = { gfxCodecProfile = RdpGfxCodecProfile.AVC420_VIDEO },
                            label = "H.264 AVC420"
                        )
                        NexusChoiceChip(
                            selected = gfxCodecProfile == RdpGfxCodecProfile.AVC444_CHROMA,
                            onClick = { gfxCodecProfile = RdpGfxCodecProfile.AVC444_CHROMA },
                            label = "H.264 AVC444"
                        )
                    }
                }

                // RDP Tab 2: Local Resources (Clipboard, Audio Mode, Mic, Drives, Printers, Ports, Smartcard)
                if (isRdpOrWts && activeRdpTab == 2) {
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NexusChoiceChip(
                            selected = audioRedirectionMode == RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE,
                            onClick = { audioRedirectionMode = RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE },
                            label = strings.rdpAudioLocal
                        )
                        NexusChoiceChip(
                            selected = audioRedirectionMode == RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST,
                            onClick = { audioRedirectionMode = RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST },
                            label = strings.rdpAudioRemote
                        )
                        NexusChoiceChip(
                            selected = audioRedirectionMode == RdpAudioRedirectionMode.DO_NOT_PLAY,
                            onClick = { audioRedirectionMode = RdpAudioRedirectionMode.DO_NOT_PLAY },
                            label = strings.rdpAudioMute
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
                    if (redirectDrives || redirectedDriveSafUris.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "RDPDR Shared Folders (\\\\tsclient\\<Folder>)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (redirectedDriveSafUris.isEmpty()) {
                                    Text(
                                        text = "Using sandboxed NexusTS exchange drive (\\\\tsclient\\NexusTS), or choose a local Googlebook OS / Android folder:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    redirectedDriveSafUris.forEach { safUri ->
                                        val decodedLabel = remember(safUri) {
                                            java.net.URLDecoder.decode(safUri, Charsets.UTF_8.name())
                                                .substringAfterLast(':')
                                                .substringAfterLast('/')
                                                .ifBlank { safUri.takeLast(32) }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Folder,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = decodedLabel,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    redirectedDriveSafUris = redirectedDriveSafUris - safUri
                                                },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Delete,
                                                    contentDescription = "Remove shared folder",
                                                    modifier = Modifier.size(15.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                                if (onPickSafSharedFolder != null) {
                                    OutlinedButton(
                                        onClick = {
                                            onPickSafSharedFolder { pickedUri ->
                                                redirectDrives = true
                                                if (pickedUri.isNotBlank() && pickedUri !in redirectedDriveSafUris) {
                                                    redirectedDriveSafUris = redirectedDriveSafUris + pickedUri
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Select Shared Folder (SAF)…")
                                    }
                                }
                            }
                        }
                    }
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
                if (isRdpOrWts && activeRdpTab == 3) {
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
                    val selectedBastionName = candidateBastions.firstOrNull { it.id == sshBastionConnId }
                        ?.let { "${it.name} (${it.host})" }
                        ?: "Direct (No Bastion Jump Host)"
                    val bastionOptions = remember(candidateBastions) {
                        buildList {
                            add(NexusSelectOption<String?>(value = null, title = "Direct (No Bastion Jump Host)"))
                            candidateBastions.forEach { bastion ->
                                add(
                                    NexusSelectOption(
                                        value = bastion.id,
                                        title = bastion.name,
                                        subtitle = bastion.host
                                    )
                                )
                            }
                        }
                    }
                    NexusDialogSelectField(
                        buttonText = "ProxyJump: $selectedBastionName",
                        dialogTitle = "ProxyJump Bastion",
                        cancelLabel = strings.dialogActionCancel,
                        selectedValue = sshBastionConnId,
                        options = bastionOptions,
                        leadingIcon = Icons.Outlined.DeviceHub,
                        onSelect = { sshBastionConnId = it }
                    )

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
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    PortForwardKind.LOCAL to "-L Local",
                                    PortForwardKind.REMOTE to "-R Remote",
                                    PortForwardKind.DYNAMIC_SOCKS5 to "-D SOCKS5"
                                ).forEach { (kind, label) ->
                                    NexusChoiceChip(
                                        selected = newTunnelKind == kind,
                                        onClick = { newTunnelKind = kind },
                                        label = label
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
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).forEach { baud ->
                                    NexusChoiceChip(
                                        selected = serialBaudRate == baud,
                                        onClick = { serialBaudRate = baud },
                                        label = "$baud"
                                    )
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("NONE", "EVEN", "ODD").forEach { p ->
                                    NexusChoiceChip(
                                        selected = serialParity == p,
                                        onClick = { serialParity = p },
                                        label = "Parity: $p"
                                    )
                                }
                                listOf("NONE", "RTS_CTS", "XON_XOFF").forEach { fc ->
                                    NexusChoiceChip(
                                        selected = serialFlowControl == fc,
                                        onClick = { serialFlowControl = fc },
                                        label = "Flow: $fc"
                                    )
                                }
                            }
                            RdpOptionSwitchRow("Assert DTR Line on Connect", serialDtr) { serialDtr = it }
                            RdpOptionSwitchRow("Assert RTS Line on Connect", serialRts) { serialRts = it }
                        }
                    }
                }

                // File Transfer (SFTP / FTP / FTPS / SCP) Configuration Section
                if (selectedProto == VaultProtocolType.SFTP ||
                    selectedProto == VaultProtocolType.FTP ||
                    selectedProto == VaultProtocolType.FTPS ||
                    selectedProto == VaultProtocolType.SCP
                ) {
                    HorizontalDivider()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = ftRemoteDir,
                                onValueChange = { ftRemoteDir = it },
                                label = { Text("Initial Remote Directory (blank = login home / PWD)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (selectedProto == VaultProtocolType.FTP || selectedProto == VaultProtocolType.FTPS) {
                                RdpOptionSwitchRow("Passive Data Channel (EPSV / PASV)", ftPassiveMode) {
                                    ftPassiveMode = it
                                }
                            }
                            if (selectedProto == VaultProtocolType.FTPS) {
                                RdpOptionSwitchRow("Implicit TLS (TCP 990) — off for Explicit AUTH TLS (TCP 21)", ftImplicitTls) { implicit ->
                                    ftImplicitTls = implicit
                                    if (implicit && ftPortText == "21") ftPortText = "990"
                                    if (!implicit && ftPortText == "990") ftPortText = "21"
                                }
                                RdpOptionSwitchRow("Strict TLS Certificate Verification", ftVerifyTls) {
                                    ftVerifyTls = it
                                }
                            }
                        }
                    }
                }

                // VNC & Apple Screen Sharing (ARD) Configuration Section
                if (selectedProto == VaultProtocolType.VNC ||
                    selectedProto == VaultProtocolType.APPLE_SCREEN_SHARING
                ) {
                    HorizontalDivider()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RdpOptionSwitchRow("Apple Screen Sharing (ARD Type 30 DH-AES Auth)", vncArdAuth) {
                                vncArdAuth = it
                            }
                            RdpOptionSwitchRow("Shared Session (Keep Existing Viewers)", vncSharedSession) {
                                vncSharedSession = it
                            }
                            RdpOptionSwitchRow("View Only (Read-Only Observe Mode)", vncViewOnly) {
                                vncViewOnly = it
                            }
                        }
                    }
                }

                // Isolated Web Page Configuration Section
                if (selectedProto == VaultProtocolType.WEB_PAGE) {
                    HorizontalDivider()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RdpOptionSwitchRow("Accept Self-Signed / Internal LAN Certificates", webIgnoreSelfSigned) {
                                webIgnoreSelfSigned = it
                            }
                            RdpOptionSwitchRow("Auto-Fill Login Forms", webAutoFill) {
                                webAutoFill = it
                            }
                            RdpOptionSwitchRow("Inject HTTP Basic Auth Challenge", webInjectBasicAuth) {
                                webInjectBasicAuth = it
                            }
                        }
                    }
                }

                // Cloud & Hypervisor Management Configuration Section
                if (selectedProto == VaultProtocolType.VMWARE_VSPHERE ||
                    selectedProto == VaultProtocolType.PROXMOX_VE ||
                    selectedProto == VaultProtocolType.HYPER_V ||
                    selectedProto == VaultProtocolType.AWS_EC2 ||
                    selectedProto == VaultProtocolType.AZURE_VM
                ) {
                    HorizontalDivider()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = cloudRegionOrCluster,
                                onValueChange = { cloudRegionOrCluster = it },
                                label = {
                                    Text(
                                        when (selectedProto) {
                                            VaultProtocolType.AWS_EC2 -> "AWS Region (e.g. us-east-1, eu-central-1)"
                                            VaultProtocolType.AZURE_VM -> "Resource Group Filter (optional — blank lists all)"
                                            else -> "Cluster / Datacenter / Node (optional)"
                                        }
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (selectedProto == VaultProtocolType.AZURE_VM || selectedProto == VaultProtocolType.AWS_EC2) {
                                OutlinedTextField(
                                    value = cloudSubscriptionId,
                                    onValueChange = { cloudSubscriptionId = it },
                                    label = {
                                        Text(
                                            if (selectedProto == VaultProtocolType.AZURE_VM) {
                                                "Azure Subscription ID"
                                            } else {
                                                "AWS Account ID / STS Session Token (optional)"
                                            }
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (selectedProto == VaultProtocolType.AZURE_VM ||
                                selectedProto == VaultProtocolType.PROXMOX_VE ||
                                selectedProto == VaultProtocolType.HYPER_V
                            ) {
                                OutlinedTextField(
                                    value = cloudRealmOrTenant,
                                    onValueChange = { cloudRealmOrTenant = it },
                                    label = {
                                        Text(
                                            when (selectedProto) {
                                                VaultProtocolType.AZURE_VM -> "Entra ID Tenant ID (for Service Principal OAuth2)"
                                                VaultProtocolType.PROXMOX_VE -> "Proxmox Realm (pam / pve)"
                                                else -> "Active Directory / NTLM Domain (optional)"
                                            }
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = cloudApiEndpoint,
                                onValueChange = { cloudApiEndpoint = it },
                                label = { Text("Custom API Endpoint URL Override (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            RdpOptionSwitchRow("Strict TLS Certificate Verification", cloudVerifyTls) {
                                cloudVerifyTls = it
                            }
                        }
                    }
                }

                // Network Utilities Configuration Section
                if (selectedProto == VaultProtocolType.UTILITY_PING ||
                    selectedProto == VaultProtocolType.UTILITY_TRACEROUTE ||
                    selectedProto == VaultProtocolType.UTILITY_PORT_SCANNER ||
                    selectedProto == VaultProtocolType.UTILITY_WAKE_ON_LAN
                ) {
                    HorizontalDivider()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (selectedProto) {
                                VaultProtocolType.UTILITY_WAKE_ON_LAN -> {
                                    OutlinedTextField(
                                        value = netWolMac,
                                        onValueChange = { netWolMac = it },
                                        label = { Text("Target MAC Address (00:11:22:33:44:55)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = netWolBroadcast,
                                        onValueChange = { netWolBroadcast = it },
                                        label = { Text("Broadcast IP Address") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                VaultProtocolType.UTILITY_PORT_SCANNER -> {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = netPortStartText,
                                            onValueChange = { netPortStartText = it.filter(Char::isDigit).take(5) },
                                            label = { Text("Start Port") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = netPortEndText,
                                            onValueChange = { netPortEndText = it.filter(Char::isDigit).take(5) },
                                            label = { Text("End Port") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                VaultProtocolType.UTILITY_TRACEROUTE -> {
                                    OutlinedTextField(
                                        value = netMaxHopsText,
                                        onValueChange = { netMaxHopsText = it.filter(Char::isDigit).take(2) },
                                        label = { Text("Max Hops (1–30)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                else -> {
                                    OutlinedTextField(
                                        value = netPingCountText,
                                        onValueChange = { netPingCountText = it.filter(Char::isDigit).take(3) },
                                        label = { Text("Ping Sample Count") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
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
                            OutlinedTextField(
                                value = containerEndpoint,
                                onValueChange = { containerEndpoint = it },
                                label = {
                                    Text(
                                        if (selectedProto == VaultProtocolType.KUBERNETES_POD) {
                                            "Cluster API URL (blank uses Host field)"
                                        } else {
                                            "Docker Endpoint (unix:///var/run/docker.sock over SSH, or http://host:2375)"
                                        }
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                                            "Pod Name (blank discovers pods on connect)"
                                        } else {
                                            "Docker Container Name / ID (blank lists containers)"
                                        }
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (selectedProto == VaultProtocolType.KUBERNETES_POD) {
                                OutlinedTextField(
                                    value = containerSubName,
                                    onValueChange = { containerSubName = it },
                                    label = { Text("Container Name inside Multi-Container Pod (optional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("/bin/sh", "/bin/bash", "/bin/ash").forEach { sh ->
                                    NexusChoiceChip(
                                        selected = containerShell == sh,
                                        onClick = { containerShell = sh },
                                        label = sh
                                    )
                                }
                            }
                            RdpOptionSwitchRow("Open Directly to Live Log Stream (--follow)", containerFollowLogs) {
                                containerFollowLogs = it
                            }
                            RdpOptionSwitchRow("Strict TLS Certificate Verification", containerVerifyTls) {
                                containerVerifyTls = it
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val resolvedSaveHost = when {
                host.isNotBlank() -> host.trim()
                selectedProto == VaultProtocolType.AWS_EC2 ->
                    "ec2.${cloudRegionOrCluster.trim().ifBlank { "us-east-1" }}.amazonaws.com"
                selectedProto == VaultProtocolType.AZURE_VM -> "management.azure.com"
                selectedProto == VaultProtocolType.UTILITY_WAKE_ON_LAN && netWolMac.isNotBlank() ->
                    netWolMac.trim()
                else -> ""
            }
            Button(
                enabled = name.isNotBlank() && resolvedSaveHost.isNotBlank(),
                onClick = {
                    val configuredSettings: ProtocolSpecificSettings = when (selectedProto) {
                        VaultProtocolType.RDP,
                        VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> {
                            val trimmedVmId = hyperVVmId.trim().trim('{', '}')
                            val parsedPort = rdpPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 3389
                            val effectivePort = if (trimmedVmId.isNotBlank() && parsedPort == 3389) 2179 else parsedPort
                            initialRdp.copy(
                                port = effectivePort,
                                connectToConsoleAdmin = connectToConsole,
                                hiDpiEnabled = hiDpiEnabled,
                                desktopScaleFactorPercent = desktopScalePercent,
                                deviceScaleFactorPercent = if (desktopScalePercent >= 175) 180 else if (desktopScalePercent >= 125) 140 else 100,
                                hiDpiScaleCustomized = true,
                                gfxCodecProfile = gfxCodecProfile,
                                enableH264Avc444 = gfxCodecProfile == RdpGfxCodecProfile.AVC444_CHROMA,
                                resizeBehavior = resizeBehavior,
                                customDesktopWidth = customWidthText.toIntOrNull()?.coerceIn(640, 8192) ?: 1920,
                                customDesktopHeight = customHeightText.toIntOrNull()?.coerceIn(480, 8192) ?: 1080,
                                multiMonitorSpanning = multiMonitorSpanning,
                                colorDepthBits = colorDepthBits,
                                redirectClipboard = redirectClipboard,
                                redirectClipboardCustomized = true,
                                audioRedirectionMode = audioRedirectionMode,
                                redirectAudioPlayback = audioRedirectionMode == RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE,
                                redirectMicrophone = redirectMicrophone,
                                redirectDrives = redirectDrives || redirectedDriveSafUris.isNotEmpty(),
                                redirectedDriveSafUris = if (redirectDrives) redirectedDriveSafUris else emptyList(),
                                redirectPrinters = redirectPrinters,
                                redirectPorts = redirectPorts,
                                redirectSmartcardYubiKey = redirectSmartcard,
                                enableFontSmoothing = enableFontSmoothing,
                                enableDesktopComposition = enableDesktopComposition,
                                enableWallpaper = enableWallpaper,
                                nlaSecurityRequired = nlaSecurityRequired,
                                restrictedAdminMode = restrictedAdminMode,
                                ignoreCertificateWarnings = ignoreCertWarnings,
                                rdGatewayHostname = rdGatewayHost.trim(),
                                hyperVVmId = trimmedVmId,
                                hyperVEnhancedSession = if (trimmedVmId.isNotBlank()) hyperVEnhancedSession else true,
                                hyperVEnhancedSessionCustomized = trimmedVmId.isNotBlank()
                            )
                        }

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
                            devicePathHint = resolvedSaveHost,
                            baudRate = serialBaudRate,
                            parity = serialParity,
                            flowControl = serialFlowControl,
                            dtrEnabled = serialDtr,
                            rtsEnabled = serialRts
                        )

                        VaultProtocolType.KUBERNETES_POD,
                        VaultProtocolType.DOCKER_CONTAINER -> {
                            val rawCluster = containerEndpoint.trim()
                            val resolvedCluster = when {
                                rawCluster.isNotBlank() -> rawCluster
                                resolvedSaveHost.startsWith("http://", ignoreCase = true) ||
                                    resolvedSaveHost.startsWith("https://", ignoreCase = true) ||
                                    resolvedSaveHost.startsWith("tcp://", ignoreCase = true) ||
                                    resolvedSaveHost.startsWith("unix://", ignoreCase = true) ||
                                    resolvedSaveHost.startsWith("ssh://", ignoreCase = true) -> resolvedSaveHost
                                resolvedSaveHost.endsWith(":2375") -> "http://$resolvedSaveHost"
                                selectedProto == VaultProtocolType.DOCKER_CONTAINER -> "unix:///var/run/docker.sock"
                                else -> "https://$resolvedSaveHost"
                            }
                            initialContainer.copy(
                                clusterOrSocketEndpoint = resolvedCluster,
                                namespace = containerNamespace.trim().ifBlank { "default" },
                                podOrContainerName = containerTargetName.trim(),
                                subContainerName = containerSubName.trim(),
                                shellCommand = containerShell,
                                followLogsOnOpen = containerFollowLogs,
                                verifyTls = containerVerifyTls
                            )
                        }

                        VaultProtocolType.SFTP, VaultProtocolType.FTP,
                        VaultProtocolType.FTPS, VaultProtocolType.SCP -> {
                            val defaultFtPort = when (selectedProto) {
                                VaultProtocolType.FTP -> 21
                                VaultProtocolType.FTPS -> if (ftImplicitTls) 990 else 21
                                else -> 22
                            }
                            initialFt.copy(
                                port = ftPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultFtPort,
                                initialRemoteDirectory = ftRemoteDir.trim(),
                                ftpsImplicitTls = if (selectedProto == VaultProtocolType.FTPS) ftImplicitTls else false,
                                passiveMode = ftPassiveMode,
                                verifyTls = ftVerifyTls
                            )
                        }

                        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING ->
                            initialVnc.copy(
                                port = vncPortText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 5900,
                                appleArdDiffieHellmanAuth = vncArdAuth || selectedProto == VaultProtocolType.APPLE_SCREEN_SHARING,
                                sharedSession = vncSharedSession,
                                viewOnly = vncViewOnly
                            )

                        VaultProtocolType.WEB_PAGE -> {
                            val normalizedUrl = if (resolvedSaveHost.startsWith("http", ignoreCase = true)) {
                                resolvedSaveHost
                            } else {
                                "https://$resolvedSaveHost"
                            }
                            initialWeb.copy(
                                url = normalizedUrl,
                                autoFillEnabled = webAutoFill,
                                injectHttpBasicAuth = webInjectBasicAuth,
                                ignoreSelfSignedInternalCert = webIgnoreSelfSigned
                            )
                        }

                        VaultProtocolType.HYPER_V -> {
                            val customApi = cloudApiEndpoint.trim()
                            val customUrl = when {
                                customApi.startsWith("http://", ignoreCase = true) ||
                                    customApi.startsWith("https://", ignoreCase = true) ->
                                    if (customApi.contains(":5985") || customApi.contains(":5986")) customApi else ""
                                resolvedSaveHost.startsWith("http://", ignoreCase = true) ||
                                    resolvedSaveHost.startsWith("https://", ignoreCase = true) -> resolvedSaveHost
                                else -> ""
                            }
                            initialCloud.copy(
                                apiEndpointUrl = customUrl,
                                regionOrCluster = cloudRegionOrCluster.trim(),
                                subscriptionOrAccountId = cloudSubscriptionId.trim(),
                                realmOrDomain = cloudRealmOrTenant.trim(),
                                verifyTls = cloudVerifyTls
                            )
                        }

                        VaultProtocolType.PROXMOX_VE -> {
                            val customApi = cloudApiEndpoint.trim()
                            val baseTarget = customApi.ifBlank { resolvedSaveHost }
                            val normalizedUrl = when {
                                baseTarget.startsWith("http://", ignoreCase = true) ||
                                    baseTarget.startsWith("https://", ignoreCase = true) -> {
                                    val afterScheme = baseTarget.substringAfter("://").substringBefore('/')
                                    if (afterScheme.substringAfterLast(']').contains(':')) baseTarget else "${baseTarget.trimEnd('/')}:8006"
                                }
                                baseTarget.substringAfterLast(']').contains(':') -> "https://$baseTarget"
                                else -> "https://$baseTarget:8006"
                            }
                            initialCloud.copy(
                                apiEndpointUrl = normalizedUrl,
                                regionOrCluster = cloudRegionOrCluster.trim(),
                                subscriptionOrAccountId = cloudSubscriptionId.trim(),
                                realmOrDomain = cloudRealmOrTenant.trim().ifBlank { "pam" },
                                verifyTls = cloudVerifyTls
                            )
                        }

                        VaultProtocolType.VMWARE_VSPHERE -> {
                            val customApi = cloudApiEndpoint.trim()
                            val baseTarget = customApi.ifBlank { resolvedSaveHost }
                            val normalizedUrl = if (baseTarget.startsWith("http", ignoreCase = true)) {
                                baseTarget
                            } else {
                                "https://$baseTarget"
                            }
                            initialCloud.copy(
                                apiEndpointUrl = normalizedUrl,
                                regionOrCluster = cloudRegionOrCluster.trim(),
                                subscriptionOrAccountId = cloudSubscriptionId.trim(),
                                realmOrDomain = cloudRealmOrTenant.trim(),
                                verifyTls = cloudVerifyTls
                            )
                        }

                        VaultProtocolType.AWS_EC2,
                        VaultProtocolType.AZURE_VM -> {
                            val explicitCustomEndpoint = cloudApiEndpoint.trim().takeIf {
                                it.startsWith("http://", ignoreCase = true) ||
                                    it.startsWith("https://", ignoreCase = true)
                            }.orEmpty()
                            val defaultRegion = if (selectedProto == VaultProtocolType.AWS_EC2) {
                                cloudRegionOrCluster.trim().ifBlank { "us-east-1" }
                            } else {
                                cloudRegionOrCluster.trim()
                            }
                            initialCloud.copy(
                                apiEndpointUrl = explicitCustomEndpoint,
                                regionOrCluster = defaultRegion,
                                subscriptionOrAccountId = cloudSubscriptionId.trim(),
                                realmOrDomain = cloudRealmOrTenant.trim(),
                                verifyTls = cloudVerifyTls
                            )
                        }

                        VaultProtocolType.UTILITY_PING, VaultProtocolType.UTILITY_TRACEROUTE,
                        VaultProtocolType.UTILITY_PORT_SCANNER, VaultProtocolType.UTILITY_WAKE_ON_LAN -> {
                            val startPort = netPortStartText.toIntOrNull()?.coerceIn(1, 65535) ?: 1
                            val endPort = (netPortEndText.toIntOrNull()?.coerceIn(1, 65535) ?: 1024).coerceAtLeast(startPort)
                            initialNet.copy(
                                pingCount = netPingCountText.toIntOrNull()?.coerceIn(1, 100) ?: 4,
                                tracerouteMaxHops = netMaxHopsText.toIntOrNull()?.coerceIn(1, 30) ?: 30,
                                portScanRangeStart = startPort,
                                portScanRangeEnd = endPort,
                                wolMacAddress = netWolMac.trim().ifBlank {
                                    if (selectedProto == VaultProtocolType.UTILITY_WAKE_ON_LAN) resolvedSaveHost else ""
                                },
                                wolBroadcastAddress = netWolBroadcast.trim().ifBlank { "255.255.255.255" }
                            )
                        }
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
                        host = resolvedSaveHost,
                        protocol = selectedProto,
                        inheritable = resolvedInheritable,
                        settings = configuredSettings
                    ) ?: VaultConnection(
                        parentFolderId = selectedFolderId,
                        name = name.trim(),
                        host = resolvedSaveHost,
                        protocol = selectedProto,
                        osFamily = if (selectedProto == VaultProtocolType.RDP ||
                            selectedProto == VaultProtocolType.WINDOWS_TERMINAL_SERVICES ||
                            selectedProto == VaultProtocolType.HYPER_V
                        ) {
                            "Windows"
                        } else {
                            "Linux"
                        },
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
    val hover = rememberDesktopHoverState()
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (hover.value.isHovered) {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                }
            )
            .nexusHoverTracker(hover, PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
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
 * Standalone Credential Creator & Editor Dialog used by both [CredentialVaultStudioView]
 * and the bottom-pinned `"Credentials"` system folder in the left sidebar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CredentialEditorDialog(
    visible: Boolean,
    existingCredential: VaultCredential?,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveCredential: (VaultCredential) -> Unit,
    onDeleteCredential: ((String) -> Unit)? = null
) {
    if (!visible) return
    var credName by remember(existingCredential) { mutableStateOf(existingCredential?.name.orEmpty()) }
    var credDomain by remember(existingCredential) { mutableStateOf(existingCredential?.domain.orEmpty()) }
    var credUser by remember(existingCredential) { mutableStateOf(existingCredential?.username.orEmpty()) }
    var credSecret by remember(existingCredential) { mutableStateOf(existingCredential?.secretPassword.orEmpty()) }
    var credPrivateKeyPem by remember(existingCredential) { mutableStateOf(existingCredential?.privateKeyPem.orEmpty()) }
    var credKeyPassphrase by remember(existingCredential) { mutableStateOf(existingCredential?.keyPassphrase.orEmpty()) }
    var credTotpSecret by remember(existingCredential) { mutableStateOf(existingCredential?.totpSecretBase32.orEmpty()) }
    var credKind by remember(existingCredential) {
        mutableStateOf(existingCredential?.sourceKind ?: CredentialSourceKind.VAULT_INTERNAL)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text(
                if (existingCredential != null) {
                    "${strings.dialogActionSave} • ${existingCredential.name}"
                } else {
                    strings.studioActionAddCredential
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        CredentialSourceKind.VAULT_INTERNAL to "VAULT",
                        CredentialSourceKind.SSH_KEY_PAIR to "SSH KEY",
                        CredentialSourceKind.ONEPASSWORD_CONNECT to "1PASSWORD",
                        CredentialSourceKind.BITWARDEN_VAULT to "BITWARDEN",
                        CredentialSourceKind.KEEPASS_KDBX to "KEEPASS",
                        CredentialSourceKind.HARDWARE_FIDO2_PASSKEY to "FIDO2"
                    ).forEach { (kind, label) ->
                        NexusChoiceChip(
                            selected = credKind == kind,
                            onClick = { credKind = kind },
                            label = label
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
                if (credKind == CredentialSourceKind.SSH_KEY_PAIR ||
                    credKind == CredentialSourceKind.VAULT_INTERNAL ||
                    credPrivateKeyPem.isNotBlank()
                ) {
                    OutlinedTextField(
                        value = credPrivateKeyPem,
                        onValueChange = { credPrivateKeyPem = it },
                        label = { Text("SSH Private Key (OpenSSH / PEM — Optional)") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (credPrivateKeyPem.isNotBlank() || credKind == CredentialSourceKind.SSH_KEY_PAIR) {
                        OutlinedTextField(
                            value = credKeyPassphrase,
                            onValueChange = { credKeyPassphrase = it },
                            label = { Text("SSH Private Key Passphrase (Optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
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
                    val isExternalVaultKind = credKind != CredentialSourceKind.VAULT_INTERNAL &&
                        credKind != CredentialSourceKind.SSH_KEY_PAIR
                    val effectiveKind = if (credKind == CredentialSourceKind.VAULT_INTERNAL &&
                        credPrivateKeyPem.trim().isNotBlank() &&
                        credSecret.isBlank()
                    ) {
                        CredentialSourceKind.SSH_KEY_PAIR
                    } else {
                        credKind
                    }
                    val saved = if (existingCredential != null) {
                        existingCredential.copy(
                            parentFolderId = null,
                            name = credName.trim(),
                            sourceKind = effectiveKind,
                            domain = credDomain.trim(),
                            username = credUser.trim(),
                            secretPassword = credSecret,
                            privateKeyPem = credPrivateKeyPem.trim(),
                            keyPassphrase = credKeyPassphrase,
                            totpSecretBase32 = credTotpSecret.trim(),
                            externalReferenceUri = if (isExternalVaultKind) {
                                credUser.trim()
                            } else {
                                existingCredential.externalReferenceUri
                            }
                        )
                    } else {
                        VaultCredential(
                            parentFolderId = null,
                            name = credName.trim(),
                            sourceKind = effectiveKind,
                            domain = credDomain.trim(),
                            username = credUser.trim(),
                            secretPassword = credSecret,
                            privateKeyPem = credPrivateKeyPem.trim(),
                            keyPassphrase = credKeyPassphrase,
                            totpSecretBase32 = credTotpSecret.trim(),
                            externalReferenceUri = if (isExternalVaultKind) {
                                credUser.trim()
                            } else {
                                ""
                            }
                        )
                    }
                    onSaveCredential(saved)
                }
            ) {
                Text(strings.dialogActionSave)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (existingCredential != null && onDeleteCredential != null) {
                    TextButton(
                        onClick = {
                            onDeleteCredential(existingCredential.id)
                            onDismiss()
                        }
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
 * 2. Credential Vault & SSH Agent Studio supporting Internal Zero-Knowledge Credentials,
 *    OpenSSH Ed25519 Keygen, 1Password Connect, Bitwarden, KeePass (.kdbx), and FIDO2 Passkeys.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CredentialVaultStudioView(
    credentials: List<VaultCredential>,
    strings: WorkspaceLocalizedStrings,
    onGenerateEd25519Key: () -> Unit,
    onCreateCredential: (VaultCredential) -> Unit,
    onUpdateCredential: (VaultCredential) -> Unit = {},
    onDuplicateCredential: (VaultCredential) -> Unit = {},
    onDeleteCredential: (String) -> Unit = {},
    onRequestImportVaultFile: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var showCredDialog by remember { mutableStateOf(false) }
    var editingCredential by remember { mutableStateOf<VaultCredential?>(null) }
    var bgMenuOpen by remember { mutableStateOf(false) }

    fun openForCreate() {
        editingCredential = null
        showCredDialog = true
    }

    fun openForEdit(cred: VaultCredential) {
        editingCredential = cred
        showCredDialog = true
    }

    CredentialEditorDialog(
        visible = showCredDialog,
        existingCredential = editingCredential,
        strings = strings,
        onDismiss = { showCredDialog = false },
        onSaveCredential = { saved ->
            if (editingCredential != null) {
                onUpdateCredential(saved)
            } else {
                onCreateCredential(saved)
            }
            showCredDialog = false
        },
        onDeleteCredential = onDeleteCredential
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .nexusDesktopPointerButtons(onSecondaryClick = { bgMenuOpen = true })
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.navCredentials, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = strings.studioCredentialsSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { openForCreate() }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.studioActionAddCredential)
                    }
                    FilledTonalButton(onClick = onGenerateEd25519Key) {
                        Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.inspectorGenerateSshKey)
                    }
                    OutlinedButton(onClick = onRequestImportVaultFile) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.studioActionImportPasswords)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            if (credentials.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = strings.navCredentials,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = strings.studioCredentialsSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = { openForCreate() }) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.studioActionAddCredential)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(credentials, key = { it.id }) { cred ->
                        val userDisplay = if (cred.domain.isNotBlank()) {
                            "${cred.domain}\\${cred.username.ifBlank { "-" }}"
                        } else {
                            cred.username.ifBlank { "-" }
                        }
                        val cardHover = rememberDesktopHoverState()
                        var credMenuOpen by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = if (cardHover.value.isHovered) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (cardHover.value.isHovered) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                                    } else {
                                        Color.Transparent
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .nexusHoverTracker(cardHover, PointerIcon.Default)
                                    .nexusDesktopPointerButtons(onSecondaryClick = { credMenuOpen = true })
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
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (cred.username.isNotBlank()) {
                                                NexusActionChip(
                                                    onClick = { clipboard.setText(AnnotatedString(cred.username)) },
                                                    label = "User",
                                                    leadingIcon = Icons.Outlined.ContentCopy
                                                )
                                            }
                                            if (cred.secretPassword.isNotBlank()) {
                                                NexusActionChip(
                                                    onClick = { clipboard.setText(AnnotatedString(cred.secretPassword)) },
                                                    label = "Secret",
                                                    leadingIcon = Icons.Outlined.ContentCopy
                                                )
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

                            DropdownMenu(
                                expanded = credMenuOpen,
                                onDismissRequest = { credMenuOpen = false }
                            ) {
                                if (cred.username.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("Copy Username") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            credMenuOpen = false
                                            clipboard.setText(AnnotatedString(cred.username))
                                        }
                                    )
                                }
                                if (cred.secretPassword.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("Copy Password / Secret") },
                                        leadingIcon = { Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            credMenuOpen = false
                                            clipboard.setText(AnnotatedString(cred.secretPassword))
                                        }
                                    )
                                }
                                if (cred.publicKeyOpenSsh.isNotBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("Copy SSH Public Key") },
                                        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            credMenuOpen = false
                                            clipboard.setText(AnnotatedString(cred.publicKeyOpenSsh))
                                        }
                                    )
                                }
                                if (cred.username.isNotBlank() || cred.secretPassword.isNotBlank() || cred.publicKeyOpenSsh.isNotBlank()) {
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text("Edit Credential") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        credMenuOpen = false
                                        openForEdit(cred)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicate Credential") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        credMenuOpen = false
                                        onDuplicateCredential(cred)
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete Credential", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        credMenuOpen = false
                                        onDeleteCredential(cred.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = bgMenuOpen,
            onDismissRequest = { bgMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(strings.studioActionAddCredential) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    bgMenuOpen = false
                    openForCreate()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.inspectorGenerateSshKey) },
                leadingIcon = { Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    bgMenuOpen = false
                    onGenerateEd25519Key()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.studioActionImportPasswords) },
                leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    bgMenuOpen = false
                    onRequestImportVaultFile()
                }
            )
        }
    }
}

/**
 * 3. Secure Gateways & Proxies Studio (NexusTS Secure Gateway, SSH Bastion, SOCKS5, HTTP CONNECT).
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val clipboard = LocalClipboardManager.current
    var showGwModal by remember { mutableStateOf(false) }
    var editingGw by remember { mutableStateOf<SecureGatewayConfig?>(null) }
    var gwName by remember { mutableStateOf("") }
    var gwHost by remember { mutableStateOf("") }
    var gwPort by remember { mutableStateOf("1080") }
    var gwKind by remember { mutableStateOf(GatewayTransportKind.SOCKS5_PROXY) }
    var gwCredentialId by remember { mutableStateOf<String?>(null) }
    var bgMenuOpen by remember { mutableStateOf(false) }

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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GatewayTransportKind.entries.forEach { kind ->
                            NexusChoiceChip(
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
                                label = kind.name.substringBefore('_')
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
                        val gwCredOptions = remember(credentials) {
                            buildList {
                                add(NexusSelectOption<String?>(value = null, title = "-"))
                                credentials.forEach { c ->
                                    add(
                                        NexusSelectOption(
                                            value = c.id,
                                            title = c.name,
                                            subtitle = c.username.takeIf { it.isNotBlank() }
                                        )
                                    )
                                }
                            }
                        }
                        NexusDialogSelectField(
                            buttonText = "${strings.inspectorEffectiveCredential}: $selectedCredName",
                            dialogTitle = strings.inspectorEffectiveCredential,
                            cancelLabel = strings.dialogActionCancel,
                            selectedValue = gwCredentialId,
                            options = gwCredOptions,
                            leadingIcon = Icons.Outlined.Key,
                            onSelect = { gwCredentialId = it }
                        )
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .nexusDesktopPointerButtons(onSecondaryClick = { bgMenuOpen = true })
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeviceHub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(26.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.navGateways, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = strings.studioGatewaysSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openAddGateway() }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.studioActionAddGateway)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            if (activeLeases.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Active Loopback Relay Leases (${activeLeases.size})", style = MaterialTheme.typography.labelLarge)
                        activeLeases.forEach { lease ->
                            var leaseMenuOpen by remember { mutableStateOf(false) }
                            val localEndpoint = "${lease.localBindHost}:${lease.localBindPort}"
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .nexusDesktopPointerButtons(onSecondaryClick = { leaseMenuOpen = true }),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "$localEndpoint -> ${lease.remoteTargetHost}:${lease.remoteTargetPort} (${lease.gatewayKind.name})",
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = NexusTheme.extendedColors.statusConnected
                                    )
                                    TextButton(onClick = { onCloseGatewayLease(lease.leaseId) }) {
                                        Text("Close Lease")
                                    }
                                }
                                DropdownMenu(
                                    expanded = leaseMenuOpen,
                                    onDismissRequest = { leaseMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Copy Local Relay Address") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            leaseMenuOpen = false
                                            clipboard.setText(AnnotatedString(localEndpoint))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Close Lease", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            leaseMenuOpen = false
                                            onCloseGatewayLease(lease.leaseId)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (gateways.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeviceHub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = strings.navGateways,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = strings.studioGatewaysSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = { openAddGateway() }) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.studioActionAddGateway)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(gateways, key = { it.id }) { gw ->
                        val report = healthReports[gw.id]
                        val cardHover = rememberDesktopHoverState()
                        var gwMenuOpen by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = if (cardHover.value.isHovered) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (cardHover.value.isHovered) {
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.40f)
                                    } else {
                                        Color.Transparent
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .nexusHoverTracker(cardHover, PointerIcon.Default)
                                    .nexusDesktopPointerButtons(onSecondaryClick = { gwMenuOpen = true })
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

                            DropdownMenu(
                                expanded = gwMenuOpen,
                                onDismissRequest = { gwMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(strings.studioActionProbeGateway) },
                                    leadingIcon = { Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        gwMenuOpen = false
                                        onProbeGateway(gw)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bind Loopback Relay") },
                                    leadingIcon = { Icon(Icons.Outlined.DeviceHub, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        gwMenuOpen = false
                                        onOpenGatewayLease(gw)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy Endpoint") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        gwMenuOpen = false
                                        clipboard.setText(AnnotatedString("${gw.host}:${gw.port}"))
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Edit Gateway") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        gwMenuOpen = false
                                        openEditGateway(gw)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete Gateway", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        gwMenuOpen = false
                                        onDeleteGateway(gw.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = bgMenuOpen,
            onDismissRequest = { bgMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(strings.studioActionAddGateway) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    bgMenuOpen = false
                    openAddGateway()
                }
            )
        }
    }
}

/**
 * 4. Automation Command Tasks & Key Sequence Macros Studio (`{WAIT:300}`, `{ENTER}`, Broadcast).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationTasksStudioView(
    commandTasks: List<CommandTask>,
    keySequenceTasks: List<KeySequenceTask>,
    strings: WorkspaceLocalizedStrings,
    onRunCommandTask: (CommandTask) -> Unit,
    onRunKeySequenceTask: (KeySequenceTask) -> Unit,
    onAddCommandTask: (CommandTask) -> Unit,
    onUpdateCommandTask: (CommandTask) -> Unit = {},
    onAddKeySequenceTask: (KeySequenceTask) -> Unit = {},
    onUpdateKeySequenceTask: (KeySequenceTask) -> Unit = {},
    onDeleteCommandTask: (String) -> Unit = {},
    onDeleteKeySequenceTask: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var showTaskModal by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<CommandTask?>(null) }
    var taskName by remember { mutableStateOf("") }
    var taskCmd by remember { mutableStateOf("") }
    var taskBroadcast by remember { mutableStateOf(false) }

    var showMacroModal by remember { mutableStateOf(false) }
    var editingMacro by remember { mutableStateOf<KeySequenceTask?>(null) }
    var macroName by remember { mutableStateOf("") }
    var macroSequence by remember { mutableStateOf("\$EffectiveUsername\${TAB}\$EffectivePassword\${ENTER}") }
    var bgMenuOpen by remember { mutableStateOf(false) }

    fun openAddTask() {
        editingTask = null
        taskName = ""
        taskCmd = ""
        taskBroadcast = false
        showTaskModal = true
    }

    fun openEditTask(task: CommandTask) {
        editingTask = task
        taskName = task.name
        taskCmd = task.commandTemplate
        taskBroadcast = task.broadcastToAllSelectedSessions
        showTaskModal = true
    }

    fun openAddMacro() {
        editingMacro = null
        macroName = ""
        macroSequence = "\$EffectiveUsername\${TAB}\$EffectivePassword\${ENTER}"
        showMacroModal = true
    }

    fun openEditMacro(macro: KeySequenceTask) {
        editingMacro = macro
        macroName = macro.name
        macroSequence = macro.macroSequence
        showMacroModal = true
    }

    if (showTaskModal) {
        AlertDialog(
            onDismissRequest = { showTaskModal = false },
            title = { Text(if (editingTask != null) "Edit Command Task" else strings.studioActionAddTask) },
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
                        val existing = editingTask
                        if (existing != null) {
                            onUpdateCommandTask(
                                existing.copy(
                                    name = taskName.trim(),
                                    commandTemplate = taskCmd.trim(),
                                    broadcastToAllSelectedSessions = taskBroadcast
                                )
                            )
                        } else {
                            onAddCommandTask(
                                CommandTask(
                                    name = taskName.trim(),
                                    commandTemplate = taskCmd.trim(),
                                    broadcastToAllSelectedSessions = taskBroadcast
                                )
                            )
                        }
                        showTaskModal = false
                    }
                ) {
                    Text(strings.dialogActionSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTaskModal = false }) { Text(strings.dialogActionCancel) }
            }
        )
    }

    if (showMacroModal) {
        AlertDialog(
            onDismissRequest = { showMacroModal = false },
            title = { Text(if (editingMacro != null) "Edit Key Sequence Macro" else "Add Key Sequence Macro") },
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
                        val existing = editingMacro
                        if (existing != null) {
                            onUpdateKeySequenceTask(
                                existing.copy(
                                    name = macroName.trim(),
                                    macroSequence = macroSequence
                                )
                            )
                        } else {
                            onAddKeySequenceTask(
                                KeySequenceTask(
                                    name = macroName.trim(),
                                    macroSequence = macroSequence
                                )
                            )
                        }
                        showMacroModal = false
                    }
                ) {
                    Text(strings.dialogActionSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMacroModal = false }) { Text(strings.dialogActionCancel) }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .nexusDesktopPointerButtons(onSecondaryClick = { bgMenuOpen = true })
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(26.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.navTasks, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = strings.studioTasksSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { openAddTask() }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.studioActionAddTask)
                    }
                    FilledTonalButton(onClick = { openAddMacro() }) {
                        Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Key Sequence")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            if (commandTasks.isEmpty() && keySequenceTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = strings.navTasks,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = strings.studioTasksSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = { openAddTask() }) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.studioActionAddTask)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(commandTasks, key = { it.id }) { task ->
                        val cardHover = rememberDesktopHoverState()
                        var taskMenuOpen by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = if (cardHover.value.isHovered) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (cardHover.value.isHovered) {
                                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.40f)
                                    } else {
                                        Color.Transparent
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .nexusHoverTracker(cardHover, PointerIcon.Default)
                                    .nexusDesktopPointerButtons(onSecondaryClick = { taskMenuOpen = true })
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
                                        IconButton(onClick = { openEditTask(task) }) {
                                            Icon(Icons.Outlined.Edit, contentDescription = "Edit Task", modifier = Modifier.size(18.dp))
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

                            DropdownMenu(
                                expanded = taskMenuOpen,
                                onDismissRequest = { taskMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(strings.studioActionRunTask) },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        taskMenuOpen = false
                                        onRunCommandTask(task)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit Command Task") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        taskMenuOpen = false
                                        openEditTask(task)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy Command") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        taskMenuOpen = false
                                        clipboard.setText(AnnotatedString(task.commandTemplate))
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete Task", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        taskMenuOpen = false
                                        onDeleteCommandTask(task.id)
                                    }
                                )
                            }
                        }
                    }
                    items(keySequenceTasks, key = { it.id }) { macro ->
                        val cardHover = rememberDesktopHoverState()
                        var macroMenuOpen by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = if (cardHover.value.isHovered) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (cardHover.value.isHovered) {
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.40f)
                                    } else {
                                        Color.Transparent
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .nexusHoverTracker(cardHover, PointerIcon.Default)
                                    .nexusDesktopPointerButtons(onSecondaryClick = { macroMenuOpen = true })
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
                                        IconButton(onClick = { openEditMacro(macro) }) {
                                            Icon(Icons.Outlined.Edit, contentDescription = "Edit Macro", modifier = Modifier.size(18.dp))
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

                            DropdownMenu(
                                expanded = macroMenuOpen,
                                onDismissRequest = { macroMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(strings.studioActionRunTask) },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        macroMenuOpen = false
                                        onRunKeySequenceTask(macro)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit Macro") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        macroMenuOpen = false
                                        openEditMacro(macro)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy Macro") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        macroMenuOpen = false
                                        clipboard.setText(AnnotatedString(macro.macroSequence))
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete Macro", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        macroMenuOpen = false
                                        onDeleteKeySequenceTask(macro.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = bgMenuOpen,
            onDismissRequest = { bgMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(strings.studioActionAddTask) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    bgMenuOpen = false
                    openAddTask()
                }
            )
            DropdownMenuItem(
                text = { Text("Add Key Sequence") },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    bgMenuOpen = false
                    openAddMacro()
                }
            )
        }
    }
}

/**
 * 5. Dual-Engine Live Google Drive Sync (Direct Persistent SAF + Drive REST API v3 OAuth 2.0),
 *    3-Way Vector Clock Conflict Merge, & Play-Protect-Safe GitHub Releases Update Studio (`v1.1.3`).
 */
@OptIn(ExperimentalLayoutApi::class)
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
    onRequestResetDatabase: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isLinked = driveLinkedSafLabel.isNotBlank() || driveSignedInEmail.isNotBlank()
    val linkedDisplayTitle = when {
        driveLinkedSafLabel.isNotBlank() -> driveLinkedSafLabel
        driveSignedInEmail.isNotBlank() -> driveSignedInEmail
        else -> ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isLinked) {
                                NexusTheme.extendedColors.statusConnected.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isLinked) Icons.Outlined.CheckCircle else Icons.Outlined.CloudSync,
                                    contentDescription = null,
                                    tint = if (isLinked) {
                                        NexusTheme.extendedColors.statusConnected
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = strings.navDriveSync,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                if (isLinked) {
                                    Surface(
                                        shape = RoundedCornerShape(99.dp),
                                        color = NexusTheme.extendedColors.statusConnected.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = if (driveIsSyncing) "Syncing…" else "Active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NexusTheme.extendedColors.statusConnected,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Automatically syncs on startup, when you save changes, and when remote changes are detected in Google Drive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isLinked) {
                        Spacer(modifier = Modifier.width(12.dp))
                        NexusPrimaryButton(
                            enabled = !driveIsSyncing,
                            onClick = onTriggerDeltaSync,
                            label = if (driveIsSyncing) "Syncing…" else strings.actionSyncNow,
                            leadingIcon = Icons.Outlined.CloudSync
                        )
                    }
                }

                HorizontalDivider()

                if (isLinked) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = linkedDisplayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = driveSyncStatusBanner.ifBlank {
                                String.format(strings.syncStatusSyncedFormat, vaultDocument.revision)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            NexusTonalButton(
                                onClick = onLinkExistingSafDriveVault,
                                label = strings.syncLinkDriveSafOpen,
                                leadingIcon = Icons.Outlined.Folder
                            )
                            NexusOutlinedButton(
                                onClick = {
                                    if (driveLinkedSafLabel.isNotBlank()) {
                                        onUnlinkSafDriveVault()
                                    } else {
                                        onSignOutOAuthDrive()
                                    }
                                },
                                label = "Disconnect",
                                leadingIcon = Icons.Outlined.Delete
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Pick or create a vault file in Google Drive once. NexusTS keeps all your connections, folders, and credentials encrypted and synchronized automatically across Chromebook, Android, and macOS.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (driveSyncStatusBanner.isNotBlank()) {
                            Text(
                                text = driveSyncStatusBanner,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NexusPrimaryButton(
                                onClick = onCreateLiveSafDriveVault,
                                label = strings.syncLinkDriveSafCreate,
                                leadingIcon = Icons.Outlined.Add
                            )
                            NexusTonalButton(
                                onClick = onLinkExistingSafDriveVault,
                                label = strings.syncLinkDriveSafOpen,
                                leadingIcon = Icons.Outlined.Folder
                            )
                        }
                    }
                }
            }
        }
    }
}

internal class NexusSelectOption<out T>(
    val value: T,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null
)

@Composable
internal fun <T> NexusDialogSelectField(
    buttonText: String,
    dialogTitle: String,
    cancelLabel: String,
    selectedValue: T,
    options: List<NexusSelectOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    var dialogOpen by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { dialogOpen = true },
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = buttonText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (dialogOpen) {
        var searchQuery by remember { mutableStateOf("") }
        val filteredOptions = remember(options, searchQuery) {
            val q = searchQuery.trim()
            if (q.isEmpty()) {
                options
            } else {
                options.filter { opt ->
                    opt.title.contains(q, ignoreCase = true) ||
                        (opt.subtitle?.contains(q, ignoreCase = true) == true) ||
                        (opt.badge?.contains(q, ignoreCase = true) == true)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = {
                Text(
                    text = dialogTitle,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (options.size > 6) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter…") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filteredOptions.forEach { option ->
                            val isSelected = option.value == selectedValue
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSelect(option.value)
                                        dialogOpen = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (option.badge != null) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                                        ) {
                                            Text(
                                                text = option.badge,
                                                style = NexusTheme.monospace.keycapShortcut,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = option.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!option.subtitle.isNullOrBlank()) {
                                            Text(
                                                text = option.subtitle,
                                                style = NexusTheme.monospace.keycapShortcut,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(cancelLabel)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NexusProtocolSelectorField(
    label: String,
    cancelLabel: String,
    selectedProtocol: VaultProtocolType,
    onSelectProtocol: (VaultProtocolType) -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerOpen by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { pickerOpen = true },
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = selectedProtocol.shortBadge,
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "$label: ${selectedProtocol.displayName}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (pickerOpen) {
        var searchQuery by remember { mutableStateOf("") }
        var selectedCategory by remember { mutableStateOf<VaultProtocolCategory?>(null) }

        val matchingProtocols = remember(searchQuery, selectedCategory) {
            val q = searchQuery.trim()
            VaultProtocolType.entries.filter { proto ->
                val categoryMatch = selectedCategory == null || proto.category == selectedCategory
                val queryMatch = q.isEmpty() ||
                    proto.displayName.contains(q, ignoreCase = true) ||
                    proto.shortBadge.contains(q, ignoreCase = true) ||
                    proto.name.contains(q, ignoreCase = true) ||
                    proto.defaultPortHint.contains(q, ignoreCase = true)
                categoryMatch && queryMatch
            }
        }

        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("RDP, SSH, SFTP, VNC, Ping, Docker…") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NexusChoiceChip(
                            selected = selectedCategory == null,
                            onClick = { selectedCategory = null },
                            label = "All (${VaultProtocolType.entries.size})"
                        )
                        VaultProtocolCategory.entries.forEach { category ->
                            NexusChoiceChip(
                                selected = selectedCategory == category,
                                onClick = {
                                    selectedCategory = if (selectedCategory == category) null else category
                                },
                                label = category.title
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        VaultProtocolCategory.entries.forEach { category ->
                            val categoryItems = matchingProtocols.filter { it.category == category }
                            if (categoryItems.isNotEmpty()) {
                                Text(
                                    text = category.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp)
                                )
                                categoryItems.forEach { proto ->
                                    val isSelected = proto == selectedProtocol
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                onSelectProtocol(proto)
                                                pickerOpen = false
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                                },
                                                modifier = Modifier.widthIn(min = 46.dp)
                                            ) {
                                                Text(
                                                    text = proto.shortBadge,
                                                    style = NexusTheme.monospace.keycapShortcut,
                                                    color = if (isSelected) {
                                                        MaterialTheme.colorScheme.onPrimary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                            Text(
                                                text = proto.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = proto.defaultPortHint,
                                                style = NexusTheme.monospace.keycapShortcut,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
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
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text(cancelLabel)
                }
            }
        )
    }
}

