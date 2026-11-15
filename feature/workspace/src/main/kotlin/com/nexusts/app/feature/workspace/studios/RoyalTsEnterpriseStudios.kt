package com.nexusts.app.feature.workspace.studios

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder as FilledFolderIcon
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.components.ExpressiveMetricCard
import com.nexusts.app.core.ui.components.NexusActionChip
import com.nexusts.app.core.ui.components.NexusBrandEmblem
import com.nexusts.app.core.ui.components.NexusChoiceChip
import com.nexusts.app.core.ui.components.parseHexColorOrNull
import com.nexusts.app.core.ui.input.nexusDesktopPointerButtons
import com.nexusts.app.core.ui.input.nexusHoverTracker
import com.nexusts.app.core.ui.input.nexusInteractiveRow
import com.nexusts.app.core.ui.input.rememberDesktopHoverState
import com.nexusts.app.core.ui.theme.MorphContainerState
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.ui.theme.rememberMorphingContainerShape
import com.nexusts.app.core.ui.workspace.QuickLaunchPlacement
import com.nexusts.app.core.vault.credentials.TotpCodeGenerator
import com.nexusts.app.core.vault.inheritance.EffectiveConnectionProfile
import com.nexusts.app.core.vault.model.ConnectionTemplate
import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.DynamicScriptInterpreter
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import com.nexusts.app.feature.workspace.AdHocLaunchOverride
import com.nexusts.app.feature.workspace.i18n.WorkspaceLocalizedStrings
import kotlinx.coroutines.delay

val ROYAL_EXPRESSIVE_SWATCHES: List<Pair<String?, String>> = listOf(
    null to "Auto",
    "#5B9BFF" to "Cobalt",
    "#818CF8" to "Indigo",
    "#FFB347" to "Amber",
    "#38BDF8" to "Cyan",
    "#F472B6" to "Coral"
)

/**
 * Reusable Royal TS Color Accent Swatch Picker for Folders, Connections, Session Tabs, and Templates.
 */
@Composable
fun RoyalColorSwatchSelector(
    label: String,
    selectedHex: String?,
    onSelectHex: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ROYAL_EXPRESSIVE_SWATCHES.forEach { (hex, name) ->
                val isSelected = selectedHex?.equals(hex, ignoreCase = true) ?: (hex == null)
                val swatchColor = parseHexColorOrNull(hex) ?: MaterialTheme.colorScheme.outline
                val hover = rememberDesktopHoverState()
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered = hover.value.isHovered
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = when {
                        isSelected -> swatchColor.copy(alpha = if (isHovered) 0.28f else 0.20f)
                        isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = when {
                            isSelected -> swatchColor
                            isHovered -> swatchColor.copy(alpha = 0.65f)
                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        }
                    ),
                    modifier = Modifier
                        .nexusHoverTracker(hover, PointerIcon.Hand)
                        .clip(RoundedCornerShape(99.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelectHex(hex) }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * Live 1-second ticking RFC 6238 6-digit TOTP Authenticator Card with progress ring/bar and clipboard copy.
 */
@Composable
fun LiveTotpAuthenticatorCard(
    totpSecretBase32: String,
    title: String,
    modifier: Modifier = Modifier
) {
    if (totpSecretBase32.isBlank()) return
    val clipboard = LocalClipboardManager.current
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(totpSecretBase32) {
        while (true) {
            nowEpochMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val snapshot = remember(totpSecretBase32, nowEpochMs / 1_000L) {
        TotpCodeGenerator.generateTotp(totpSecretBase32, nowEpochMs)
    } ?: return

    val formattedCode = if (snapshot.code.length == 6) {
        "${snapshot.code.substring(0, 3)} ${snapshot.code.substring(3, 6)}"
    } else {
        snapshot.code
    }
    val fraction = (snapshot.secondsRemaining.toFloat() / snapshot.periodSeconds.toFloat()).coerceIn(0f, 1f)

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                    text = "${snapshot.secondsRemaining}s",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = if (snapshot.secondsRemaining <= 5) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formattedCode,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FilledTonalButton(
                    onClick = { clipboard.setText(AnnotatedString(snapshot.code)) }
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
            }

            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp)),
                color = if (snapshot.secondsRemaining <= 5) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

/**
 * Material 3 Expressive Vault & Folder Overview Dashboard (Royal TS Dashboard Parity).
 * Rendered in the main workspace canvas when zero protocol tabs are active.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpressiveVaultOverviewDashboard(
    vaultDocument: NxtsVaultDocument,
    activeSessionCount: Int,
    strings: WorkspaceLocalizedStrings,
    onOpenQuickConnect: () -> Unit,
    onNewConnection: () -> Unit,
    onOpenTemplates: () -> Unit,
    onLaunchConnection: (VaultConnection) -> Unit,
    onConnectWithOptions: (VaultConnection) -> Unit,
    onConnectAllInFolder: (String) -> Unit,
    onAddTodo: (String) -> Unit,
    onToggleTodo: (String) -> Unit,
    onDeleteTodo: (String) -> Unit = {},
    onImportVault: (() -> Unit)? = null,
    onNewFolder: () -> Unit = {},
    onNewRdpInFolder: (String) -> Unit = {},
    onNewSshInFolder: (String) -> Unit = {},
    onNewSubfolder: (String) -> Unit = {},
    onEditFolder: (VaultFolder) -> Unit = {},
    onDuplicateFolder: (VaultFolder) -> Unit = {},
    onDeleteFolder: (String) -> Unit = {},
    onDisconnectAllInFolder: (String) -> Unit = {},
    onLaunchConnectionInSplitRight: (VaultConnection) -> Unit = {},
    onLaunchConnectionInSplitDown: (VaultConnection) -> Unit = {},
    onLaunchConnectionDetached: (VaultConnection) -> Unit = {},
    onEditConnection: (VaultConnection) -> Unit = {},
    onDuplicateConnection: (VaultConnection) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    onDeleteConnection: (String) -> Unit = {},
    onDisconnectConnection: (String) -> Unit = {},
    onMoveConnectionToFolder: (VaultConnection) -> Unit = {},
    onLaunchLocalShell: () -> Unit = {},
    activeConnectionIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var newTodoTitle by remember { mutableStateOf("") }
    var overviewBgMenuOpen by remember { mutableStateOf(false) }

    val activeFolders = remember(vaultDocument.folders, vaultDocument.tombstonedObjectIds) {
        vaultDocument.folders.filterNot { it.id in vaultDocument.tombstonedObjectIds }
    }
    val activeConnections = remember(vaultDocument.connections, vaultDocument.tombstonedObjectIds) {
        vaultDocument.connections.filterNot { it.id in vaultDocument.tombstonedObjectIds }
    }
    val activeTodos = remember(vaultDocument.todoItems, vaultDocument.tombstonedObjectIds) {
        vaultDocument.todoItems.filterNot { it.id in vaultDocument.tombstonedObjectIds }
    }
    val favorites = remember(activeConnections) {
        activeConnections.filter { it.favorite }
    }
    val pendingTodos = remember(activeTodos) {
        activeTodos.count { !it.completed }
    }

    val folderSubtreeStats = remember(activeFolders, activeConnections, activeConnectionIds) {
        val childrenByParent = activeFolders.groupBy { it.parentFolderId }
        val connsByFolder = activeConnections.groupBy { it.parentFolderId }

        fun collectSubtreeFolderIds(rootId: String, visited: MutableSet<String>) {
            if (!visited.add(rootId)) return
            childrenByParent[rootId].orEmpty().forEach { child ->
                collectSubtreeFolderIds(child.id, visited)
            }
        }

        activeFolders.associate { folder ->
            val subtreeIds = mutableSetOf<String>()
            collectSubtreeFolderIds(folder.id, subtreeIds)
            val subtreeConns = subtreeIds.flatMap { fid -> connsByFolder[fid].orEmpty() }
            val activeInSubtree = subtreeConns.count { it.id in activeConnectionIds }
            folder.id to (subtreeConns.size to activeInSubtree)
        }
    }

    val isVaultCleanEmpty = activeConnections.isEmpty() && activeFolders.isEmpty() && activeTodos.isEmpty()

    if (isVaultCleanEmpty) {
        // Serene, spacious Google Material 3 Welcome State for a clean empty workspace
        Box(
            modifier = modifier
                .fillMaxSize()
                .nexusDesktopPointerButtons(onSecondaryClick = { overviewBgMenuOpen = true })
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            val welcomeShape = rememberMorphingContainerShape(
                MorphContainerState.Resting,
                restingRadius = 28.dp,
                hoveredRadius = 32.dp
            )
            Surface(
                shape = welcomeShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    NexusBrandEmblem(size = 76.dp, cornerRadius = 22.dp)

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = strings.emptyWorkspaceTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = strings.emptyWorkspaceSubtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .widthIn(max = 480.dp)
                            .fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(onClick = onNewConnection) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.actionNewConnection)
                        }
                        FilledTonalButton(onClick = onOpenQuickConnect) {
                            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.quickConnectButton)
                        }
                        OutlinedButton(onClick = onNewFolder) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.actionNewFolder)
                        }
                        if (onImportVault != null) {
                            OutlinedButton(onClick = onImportVault) {
                                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.actionImportRtsz)
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("RDP", "SSH", "SFTP", "VNC", "HTTPS", "K8s", "Serial").forEach { proto ->
                            Surface(
                                shape = RoundedCornerShape(99.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
                            ) {
                                Text(
                                    text = proto,
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = overviewBgMenuOpen,
                onDismissRequest = { overviewBgMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text(strings.actionNewConnection) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        overviewBgMenuOpen = false
                        onNewConnection()
                    }
                )
                DropdownMenuItem(
                    text = { Text(strings.actionNewFolder) },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        overviewBgMenuOpen = false
                        onNewFolder()
                    }
                )
                DropdownMenuItem(
                    text = { Text("${strings.quickConnectButton} (Ctrl+K)") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        overviewBgMenuOpen = false
                        onOpenQuickConnect()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Local Terminal") },
                    leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        overviewBgMenuOpen = false
                        onLaunchLocalShell()
                    }
                )
                if (onImportVault != null) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(strings.actionImportRtsz) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            overviewBgMenuOpen = false
                            onImportVault()
                        }
                    )
                }
            }
        }
        return
    }

    // Populated Vault Overview — spacious modern Google Material 3 layout
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .nexusDesktopPointerButtons(onSecondaryClick = { overviewBgMenuOpen = true })
    ) {
        val isWide = maxWidth >= 680.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Hero Workspace Header Card
            val heroShape = rememberMorphingContainerShape(
                MorphContainerState.Resting,
                restingRadius = 26.dp,
                hoveredRadius = 30.dp
            )
            Surface(
                shape = heroShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        NexusBrandEmblem(size = 50.dp, cornerRadius = 15.dp)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = vaultDocument.name,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = strings.emptyWorkspaceSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = onNewConnection) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.actionNewConnection)
                        }
                        FilledTonalButton(onClick = onOpenQuickConnect) {
                            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.quickConnectButton)
                        }
                        OutlinedButton(onClick = onNewFolder) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.actionNewFolder)
                        }
                        if (onImportVault != null) {
                            OutlinedButton(onClick = onImportVault) {
                                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.actionImportRtsz)
                            }
                        }
                    }
                }
            }

            // Clean, Uncluttered Google M3 Metric Summary Strip (3 balanced cards on wide screens, 4th only if templates/todos exist)
            val showFourthMetric = vaultDocument.templates.isNotEmpty() || activeTodos.isNotEmpty()
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ExpressiveMetricCard(
                        label = strings.navConnections,
                        value = activeConnections.size.toString(),
                        subtitle = strings.actionNewConnection,
                        icon = Icons.Outlined.Terminal,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = onNewConnection,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveMetricCard(
                        label = strings.vaultSectionFolders,
                        value = activeFolders.size.toString(),
                        subtitle = strings.actionNewFolder,
                        icon = Icons.Filled.FilledFolderIcon,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        onClick = onNewFolder,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveMetricCard(
                        label = strings.overviewActiveSessions,
                        value = activeSessionCount.toString(),
                        subtitle = strings.quickConnectButton,
                        icon = Icons.Outlined.PlayArrow,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        onClick = onOpenQuickConnect,
                        modifier = Modifier.weight(1f)
                    )
                    if (showFourthMetric) {
                        if (vaultDocument.templates.isNotEmpty()) {
                            ExpressiveMetricCard(
                                label = strings.navTemplates,
                                value = vaultDocument.templates.size.toString(),
                                subtitle = strings.actionNewTemplate,
                                icon = Icons.Outlined.Bookmarks,
                                accentColor = MaterialTheme.colorScheme.primary,
                                onClick = onOpenTemplates,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            ExpressiveMetricCard(
                                label = strings.inspectorTodoItems,
                                value = pendingTodos.toString(),
                                subtitle = "${activeTodos.size}",
                                icon = Icons.Outlined.CheckCircle,
                                accentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ExpressiveMetricCard(
                        label = strings.navConnections,
                        value = activeConnections.size.toString(),
                        subtitle = "${activeFolders.size} ${strings.vaultSectionFolders}",
                        icon = Icons.Outlined.Terminal,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = onNewConnection,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveMetricCard(
                        label = strings.overviewActiveSessions,
                        value = activeSessionCount.toString(),
                        subtitle = strings.quickConnectButton,
                        icon = Icons.Outlined.PlayArrow,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        onClick = onOpenQuickConnect,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Pinned Favorites Quick-Launch Strip
            if (favorites.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = strings.vaultSectionFavorites,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        favorites.forEach { fav ->
                            val accent = parseHexColorOrNull(fav.inheritable.displayColorAccentHex)
                                ?: MaterialTheme.colorScheme.primary
                            val isFavActive = fav.id in activeConnectionIds
                            val favHover = rememberDesktopHoverState()
                            val favInteraction = remember { MutableInteractionSource() }
                            var favMenuOpen by remember(fav.id) { mutableStateOf(false) }

                            Box {
                                val favShape = RoundedCornerShape(20.dp)
                                Box(
                                    modifier = Modifier
                                        .width(268.dp)
                                        .nexusHoverTracker(favHover, PointerIcon.Hand)
                                        .clip(favShape)
                                        .background(
                                            if (favHover.value.isHovered) {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainer
                                            },
                                            favShape
                                        )
                                        .border(
                                            1.dp,
                                            accent.copy(alpha = if (favHover.value.isHovered) 0.65f else 0.35f),
                                            favShape
                                        )
                                        .nexusInteractiveRow(
                                            rowKey = "fav-${fav.id}",
                                            onClick = {},
                                            onDoubleClick = { onLaunchConnection(fav) },
                                            onSecondaryClick = { favMenuOpen = true }
                                        )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (isFavActive) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xFF38BDF8))
                                                    )
                                                }
                                                Text(
                                                    text = fav.protocol.name,
                                                    style = NexusTheme.monospace.keycapShortcut,
                                                    color = accent
                                                )
                                            }
                                            TextButton(onClick = { onConnectWithOptions(fav) }) {
                                                Text(
                                                    text = strings.actionConnectWithOptions,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Text(
                                            text = fav.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = favMenuOpen,
                                    onDismissRequest = { favMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Connect") },
                                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onLaunchConnection(fav)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.actionConnectWithOptions) },
                                        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onConnectWithOptions(fav)
                                        }
                                    )
                                    if (isFavActive) {
                                        DropdownMenuItem(
                                            text = { Text(strings.actionDisconnectSession, color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Outlined.PowerSettingsNew,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            onClick = {
                                                favMenuOpen = false
                                                onDisconnectConnection(fav.id)
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(strings.splitRight) },
                                        leadingIcon = { Icon(Icons.Outlined.Splitscreen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onLaunchConnectionInSplitRight(fav)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.splitDown) },
                                        leadingIcon = { Icon(Icons.Outlined.Splitscreen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onLaunchConnectionInSplitDown(fav)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.detachNewWindow) },
                                        leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onLaunchConnectionDetached(fav)
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(strings.actionMoveToFolder) },
                                        leadingIcon = { Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onMoveConnectionToFolder(fav)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy Host (${fav.host})") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            clipboard.setText(AnnotatedString(fav.host))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.actionEditConnection) },
                                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onEditConnection(fav)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Duplicate") },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onDuplicateConnection(fav)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Remove from Favorites") },
                                        leadingIcon = { Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            favMenuOpen = false
                                            onToggleFavorite(fav.id)
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            favMenuOpen = false
                                            onDeleteConnection(fav.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Adaptive Bottom Deck: Folders (with unmistakable folder container cards & Disconnect All) + To-Do Checklist
            if (activeFolders.isNotEmpty() || activeTodos.isNotEmpty()) {
                val foldersCard: @Composable (Modifier) -> Unit = { cardMod ->
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = cardMod
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.FilledFolderIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(strings.vaultSectionFolders, style = MaterialTheme.typography.titleMedium)
                                }
                                IconButton(
                                    onClick = onNewFolder,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.CreateNewFolder,
                                        contentDescription = strings.actionNewFolder,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            activeFolders.forEach { folder ->
                                val (subtreeCount, subtreeActiveCount) = folderSubtreeStats[folder.id] ?: (0 to 0)
                                val folderAccent = parseHexColorOrNull(folder.inheritable.displayColorAccentHex)
                                    ?: MaterialTheme.colorScheme.primary
                                val folderHover = rememberDesktopHoverState()
                                val folderInteraction = remember { MutableInteractionSource() }
                                var folderMenuOpen by remember(folder.id) { mutableStateOf(false) }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (folderHover.value.isHovered) {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainer
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (folderHover.value.isHovered) {
                                                folderAccent.copy(alpha = 0.50f)
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .nexusHoverTracker(folderHover, PointerIcon.Hand)
                                            .nexusDesktopPointerButtons(onSecondaryClick = { folderMenuOpen = true })
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable(
                                                interactionSource = folderInteraction,
                                                indication = null,
                                                onClick = { onEditFolder(folder) }
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(11.dp),
                                                    color = folderAccent.copy(alpha = 0.16f),
                                                    modifier = Modifier.size(38.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Filled.FilledFolderIcon,
                                                            contentDescription = null,
                                                            tint = folderAccent,
                                                            modifier = Modifier.size(21.dp)
                                                        )
                                                    }
                                                }
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        text = folder.name,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Text(
                                                            text = "$subtreeCount ${strings.navConnections.lowercase()}",
                                                            style = NexusTheme.monospace.keycapShortcut,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        if (subtreeActiveCount > 0) {
                                                            Surface(
                                                                shape = RoundedCornerShape(99.dp),
                                                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                                            ) {
                                                                Text(
                                                                    text = "● $subtreeActiveCount active",
                                                                    style = NexusTheme.monospace.keycapShortcut,
                                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                FilledTonalIconButton(
                                                    onClick = { onNewRdpInFolder(folder.id) },
                                                    modifier = Modifier.size(34.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Add,
                                                        contentDescription = "New RDP Connection in ${folder.name}",
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                if (subtreeActiveCount > 0) {
                                                    OutlinedButton(
                                                        onClick = { onDisconnectAllInFolder(folder.id) }
                                                    ) {
                                                        Icon(
                                                            Icons.Outlined.PowerSettingsNew,
                                                            contentDescription = strings.actionDisconnectAllFolder,
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = strings.actionDisconnectAllFolder,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.error,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                                FilledTonalButton(
                                                    enabled = subtreeCount > 0,
                                                    onClick = { onConnectAllInFolder(folder.id) }
                                                ) {
                                                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = strings.actionConnectAllFolder,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = folderMenuOpen,
                                        onDismissRequest = { folderMenuOpen = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("+ New RDP Connection") },
                                            leadingIcon = { Icon(Icons.Outlined.DesktopWindows, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                folderMenuOpen = false
                                                onNewRdpInFolder(folder.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("+ New SSH Connection") },
                                            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                folderMenuOpen = false
                                                onNewSshInFolder(folder.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("+ New Subfolder") },
                                            leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                folderMenuOpen = false
                                                onNewSubfolder(folder.id)
                                            }
                                        )
                                        if (subtreeCount > 0) {
                                            DropdownMenuItem(
                                                text = { Text(strings.actionConnectAllFolder) },
                                                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                onClick = {
                                                    folderMenuOpen = false
                                                    onConnectAllInFolder(folder.id)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = strings.actionDisconnectAllFolder,
                                                        color = if (subtreeActiveCount > 0) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                        }
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Outlined.PowerSettingsNew,
                                                        contentDescription = null,
                                                        tint = if (subtreeActiveCount > 0) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        },
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
                                                onClick = {
                                                    folderMenuOpen = false
                                                    onDisconnectAllInFolder(folder.id)
                                                }
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(strings.actionEditFolderCredentials) },
                                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                folderMenuOpen = false
                                                onEditFolder(folder)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Duplicate Folder") },
                                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                folderMenuOpen = false
                                                onDuplicateFolder(folder)
                                            }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Delete Folder", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            onClick = {
                                                folderMenuOpen = false
                                                onDeleteFolder(folder.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val todosCard: @Composable (Modifier) -> Unit = { cardMod ->
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = cardMod
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(strings.inspectorTodoItems, style = MaterialTheme.typography.titleMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = newTodoTitle,
                                    onValueChange = { newTodoTitle = it },
                                    placeholder = { Text(strings.actionAddTodo, style = MaterialTheme.typography.bodySmall) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    enabled = newTodoTitle.isNotBlank(),
                                    onClick = {
                                        onAddTodo(newTodoTitle)
                                        newTodoTitle = ""
                                    }
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            activeTodos.forEach { todo ->
                                val todoHover = rememberDesktopHoverState()
                                val todoInteraction = remember { MutableInteractionSource() }
                                var todoMenuOpen by remember(todo.id) { mutableStateOf(false) }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .nexusHoverTracker(todoHover, PointerIcon.Hand)
                                            .nexusDesktopPointerButtons(onSecondaryClick = { todoMenuOpen = true })
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (todoHover.value.isHovered) {
                                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                                } else {
                                                    Color.Transparent
                                                }
                                            )
                                            .clickable(
                                                interactionSource = todoInteraction,
                                                indication = null,
                                                onClick = { onToggleTodo(todo.id) }
                                            )
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (todo.completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (todo.completed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = todo.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                textDecoration = if (todo.completed) TextDecoration.LineThrough else TextDecoration.None,
                                                color = if (todo.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = todo.priority,
                                                style = NexusTheme.monospace.keycapShortcut,
                                                color = if (todo.priority == "HIGH") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            IconButton(
                                                onClick = { onDeleteTodo(todo.id) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = "Delete To-Do",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = todoMenuOpen,
                                        onDismissRequest = { todoMenuOpen = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(if (todo.completed) "Mark Incomplete" else "Mark Complete") },
                                            leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                todoMenuOpen = false
                                                onToggleTodo(todo.id)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Copy Task Title") },
                                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                todoMenuOpen = false
                                                clipboard.setText(AnnotatedString(todo.title))
                                            }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            onClick = {
                                                todoMenuOpen = false
                                                onDeleteTodo(todo.id)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (isWide && activeFolders.isNotEmpty() && activeTodos.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        foldersCard(Modifier.weight(1f))
                        todosCard(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (activeFolders.isNotEmpty()) {
                            foldersCard(Modifier.fillMaxWidth())
                        }
                        if (activeTodos.isNotEmpty()) {
                            todosCard(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = overviewBgMenuOpen,
            onDismissRequest = { overviewBgMenuOpen = false }
        ) {
            DropdownMenuItem(
                text = { Text(strings.actionNewConnection) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    overviewBgMenuOpen = false
                    onNewConnection()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.actionNewFolder) },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    overviewBgMenuOpen = false
                    onNewFolder()
                }
            )
            DropdownMenuItem(
                text = { Text("${strings.quickConnectButton} (Ctrl+K)") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    overviewBgMenuOpen = false
                    onOpenQuickConnect()
                }
            )
            DropdownMenuItem(
                text = { Text("Local Terminal") },
                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    overviewBgMenuOpen = false
                    onLaunchLocalShell()
                }
            )
            DropdownMenuItem(
                text = { Text(strings.navTemplates) },
                leadingIcon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    overviewBgMenuOpen = false
                    onOpenTemplates()
                }
            )
            if (onImportVault != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(strings.actionImportRtsz) },
                    leadingIcon = { Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        overviewBgMenuOpen = false
                        onImportVault()
                    }
                )
            }
        }
    }
}

/**
 * Royal TS Connection Templates Studio (`"templates"` rail destination).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionTemplatesStudioView(
    templates: List<ConnectionTemplate>,
    strings: WorkspaceLocalizedStrings,
    onCreateConnectionFromTemplate: (ConnectionTemplate) -> Unit,
    onOpenNewTemplateDialog: () -> Unit,
    onDeleteTemplate: (String) -> Unit = {},
    onSaveTemplate: (ConnectionTemplate) -> Unit = {},
    onDuplicateTemplate: (ConnectionTemplate) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var editingTemplate by remember { mutableStateOf<ConnectionTemplate?>(null) }
    var bgMenuOpen by remember { mutableStateOf(false) }

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
                        imageVector = Icons.Outlined.Bookmarks,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(26.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.navTemplates, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = strings.studioTemplatesSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onOpenNewTemplateDialog) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.actionNewTemplate)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            if (templates.isEmpty()) {
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
                        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Bookmarks,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(34.dp)
                            )
                            Text(
                                text = strings.navTemplates,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = strings.studioTemplatesSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = onOpenNewTemplateDialog) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.actionNewTemplate)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(templates, key = { it.id }) { tpl ->
                        val accent = parseHexColorOrNull(tpl.colorAccentHex) ?: MaterialTheme.colorScheme.tertiary
                        val tplHover = rememberDesktopHoverState()
                        var tplMenuOpen by remember(tpl.id) { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (tplHover.value.isHovered) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                border = BorderStroke(
                                    1.dp,
                                    accent.copy(alpha = if (tplHover.value.isHovered) 0.55f else 0.35f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .nexusHoverTracker(tplHover, PointerIcon.Hand)
                                    .nexusDesktopPointerButtons(onSecondaryClick = { tplMenuOpen = true })
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(accent)
                                            )
                                            Text(tpl.name, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = tpl.protocol.name,
                                                style = NexusTheme.monospace.keycapShortcut,
                                                color = accent
                                            )
                                        }
                                        if (tpl.description.isNotBlank()) {
                                            Text(
                                                text = tpl.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        FilledTonalButton(onClick = { onCreateConnectionFromTemplate(tpl) }) {
                                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(strings.actionNewConnection)
                                        }
                                        IconButton(onClick = { editingTemplate = tpl }) {
                                            Icon(
                                                Icons.Outlined.Edit,
                                                contentDescription = "Edit Template",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(onClick = { onDeleteTemplate(tpl.id) }) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = "Delete Template",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = tplMenuOpen,
                                onDismissRequest = { tplMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(strings.actionNewConnection) },
                                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        tplMenuOpen = false
                                        onCreateConnectionFromTemplate(tpl)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit Template") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        tplMenuOpen = false
                                        editingTemplate = tpl
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicate Template") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        tplMenuOpen = false
                                        onDuplicateTemplate(tpl)
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete Template", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        tplMenuOpen = false
                                        onDeleteTemplate(tpl.id)
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
                text = { Text(strings.actionNewTemplate) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    bgMenuOpen = false
                    onOpenNewTemplateDialog()
                }
            )
        }
    }

    if (editingTemplate != null) {
        NewTemplateDialog(
            visible = true,
            editingTemplate = editingTemplate,
            strings = strings,
            onDismiss = { editingTemplate = null },
            onSaveTemplate = { updated ->
                onSaveTemplate(updated)
                editingTemplate = null
            }
        )
    }
}

/**
 * Dialog to create or edit a reusable Royal TS [ConnectionTemplate].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTemplateDialog(
    visible: Boolean,
    editingTemplate: ConnectionTemplate? = null,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveTemplate: (ConnectionTemplate) -> Unit
) {
    if (!visible) return
    val existingRdp = editingTemplate?.settings as? ProtocolSpecificSettings.Rdp
    var name by remember(editingTemplate) { mutableStateOf(editingTemplate?.name.orEmpty()) }
    var description by remember(editingTemplate) { mutableStateOf(editingTemplate?.description.orEmpty()) }
    var protocol by remember(editingTemplate) { mutableStateOf(editingTemplate?.protocol ?: VaultProtocolType.RDP) }
    var colorHex by remember(editingTemplate) { mutableStateOf<String?>(editingTemplate?.colorAccentHex ?: "#5B9BFF") }
    var rdpAdminConsole by remember(editingTemplate) { mutableStateOf(existingRdp?.connectToConsoleAdmin ?: true) }
    var rdpScalePercent by remember(editingTemplate) { mutableIntStateOf(existingRdp?.desktopScaleFactorPercent ?: 150) }
    var protoMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingTemplate != null) "Edit Template" else strings.actionNewTemplate) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.dialogFieldName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(strings.inspectorNotesDocs) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { protoMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${strings.dialogFieldProtocol}: ${protocol.name}")
                    }
                    DropdownMenu(
                        expanded = protoMenuOpen,
                        onDismissRequest = { protoMenuOpen = false }
                    ) {
                        VaultProtocolType.entries.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name) },
                                onClick = {
                                    protocol = candidate
                                    protoMenuOpen = false
                                }
                            )
                        }
                    }
                }
                RoyalColorSwatchSelector(
                    label = strings.dialogFieldColorAccent,
                    selectedHex = colorHex,
                    onSelectHex = { colorHex = it }
                )
                if (protocol == VaultProtocolType.RDP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.rdpOptConnectConsole, style = MaterialTheme.typography.bodySmall)
                        Switch(checked = rdpAdminConsole, onCheckedChange = { rdpAdminConsole = it })
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(100, 125, 150, 175, 200).forEach { pct ->
                            NexusChoiceChip(
                                selected = rdpScalePercent == pct,
                                onClick = { rdpScalePercent = pct },
                                label = "$pct%"
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val settings = when (protocol) {
                        VaultProtocolType.RDP -> ProtocolSpecificSettings.Rdp(
                            connectToConsoleAdmin = rdpAdminConsole,
                            hiDpiEnabled = rdpScalePercent > 100,
                            desktopScaleFactorPercent = rdpScalePercent
                        )
                        VaultProtocolType.SSH -> ProtocolSpecificSettings.Ssh()
                        VaultProtocolType.TELNET -> ProtocolSpecificSettings.Ssh(port = 23)
                        VaultProtocolType.SERIAL -> ProtocolSpecificSettings.SerialUart()
                        VaultProtocolType.KUBERNETES_POD,
                        VaultProtocolType.DOCKER_CONTAINER -> ProtocolSpecificSettings.ContainerExec()
                        VaultProtocolType.SFTP, VaultProtocolType.SCP -> ProtocolSpecificSettings.FileTransfer(port = 22)
                        VaultProtocolType.FTP -> ProtocolSpecificSettings.FileTransfer(port = 21)
                        VaultProtocolType.FTPS -> ProtocolSpecificSettings.FileTransfer(port = 990)
                        VaultProtocolType.VNC -> ProtocolSpecificSettings.Vnc()
                        VaultProtocolType.APPLE_SCREEN_SHARING -> ProtocolSpecificSettings.Vnc(appleArdDiffieHellmanAuth = true)
                        VaultProtocolType.WEB_PAGE -> ProtocolSpecificSettings.WebPage(url = "https://")
                        VaultProtocolType.UTILITY_PING,
                        VaultProtocolType.UTILITY_TRACEROUTE,
                        VaultProtocolType.UTILITY_PORT_SCANNER,
                        VaultProtocolType.UTILITY_WAKE_ON_LAN -> ProtocolSpecificSettings.NetUtility()
                        else -> ProtocolSpecificSettings.CloudVirtualization(apiEndpointUrl = "https://")
                    }
                    onSaveTemplate(
                        ConnectionTemplate(
                            id = editingTemplate?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            description = description.trim(),
                            protocol = protocol,
                            colorAccentHex = colorHex,
                            inheritable = (editingTemplate?.inheritable ?: InheritableProperties())
                                .copy(displayColorAccentHex = colorHex),
                            settings = settings
                        )
                    )
                }
            ) {
                Text(strings.dialogActionSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.dialogActionCancel) }
        }
    )
}

/**
 * Royal TS "Connect with Options…" One-Off Session Override Dialog.
 * Allows overriding Credentials (Vault or Ad-Hoc Domain\User\Password), RDP `/admin` Console,
 * Connection Template, and Target Window Placement without modifying the saved connection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectWithOptionsDialog(
    targetConnection: VaultConnection?,
    credentials: List<VaultCredential>,
    templates: List<ConnectionTemplate>,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onConnectWithOverride: (VaultConnection, AdHocLaunchOverride, QuickLaunchPlacement) -> Unit
) {
    if (targetConnection == null) return

    // 0 = Default Inherited/Saved, 1 = Pick Vault Credential, 2 = Ad-Hoc One-Time User/Pass
    var credChoice by remember(targetConnection) { mutableIntStateOf(0) }
    var selectedCredId by remember(targetConnection, credentials) {
        mutableStateOf(credentials.firstOrNull()?.id)
    }
    var adHocDomain by remember(targetConnection) { mutableStateOf("") }
    var adHocUsername by remember(targetConnection) { mutableStateOf("") }
    var adHocPassword by remember(targetConnection) { mutableStateOf("") }

    val matchingTemplates = remember(targetConnection, templates) {
        templates.filter { it.protocol == targetConnection.protocol }
    }
    var selectedTemplateId by remember(targetConnection) { mutableStateOf<String?>(null) }

    val initialConsole = (targetConnection.settings as? ProtocolSpecificSettings.Rdp)?.connectToConsoleAdmin ?: false
    var forceConsoleAdmin by remember(targetConnection) { mutableStateOf(initialConsole) }
    var placement by remember(targetConnection) { mutableStateOf(QuickLaunchPlacement.ACTIVE_PANE_TAB) }

    var credMenuOpen by remember { mutableStateOf(false) }
    var tplMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${strings.dialogConnectOptionsTitle} • ${targetConnection.name}")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "${targetConnection.protocol.name} -> ${targetConnection.host}",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.secondary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NexusChoiceChip(
                        selected = credChoice == 0,
                        onClick = { credChoice = 0 },
                        label = strings.credModeInheritFolder
                    )
                    NexusChoiceChip(
                        selected = credChoice == 1,
                        onClick = { credChoice = 1 },
                        label = strings.credModeOverrideExplicit
                    )
                    NexusChoiceChip(
                        selected = credChoice == 2,
                        onClick = { credChoice = 2 },
                        label = "Ad-Hoc Login"
                    )
                }

                when (credChoice) {
                    1 -> {
                        val chosen = credentials.firstOrNull { it.id == selectedCredId }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { credMenuOpen = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(chosen?.name ?: "-")
                            }
                            DropdownMenu(expanded = credMenuOpen, onDismissRequest = { credMenuOpen = false }) {
                                credentials.forEach { cred ->
                                    DropdownMenuItem(
                                        text = { Text("${cred.name} (${cred.username})") },
                                        onClick = {
                                            selectedCredId = cred.id
                                            credMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        OutlinedTextField(
                            value = adHocDomain,
                            onValueChange = { adHocDomain = it },
                            label = { Text(strings.dialogFieldDomain) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = adHocUsername,
                            onValueChange = { adHocUsername = it },
                            label = { Text(strings.dialogFieldUsername) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = adHocPassword,
                            onValueChange = { adHocPassword = it },
                            label = { Text(strings.vaultPassphraseLabel) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (matchingTemplates.isNotEmpty()) {
                    val chosenTpl = matchingTemplates.firstOrNull { it.id == selectedTemplateId }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { tplMenuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${strings.dialogFieldTemplate}: ${chosenTpl?.name ?: "-"}")
                        }
                        DropdownMenu(expanded = tplMenuOpen, onDismissRequest = { tplMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("-") },
                                onClick = {
                                    selectedTemplateId = null
                                    tplMenuOpen = false
                                }
                            )
                            matchingTemplates.forEach { tpl ->
                                DropdownMenuItem(
                                    text = { Text(tpl.name) },
                                    onClick = {
                                        selectedTemplateId = tpl.id
                                        tplMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (targetConnection.protocol == VaultProtocolType.RDP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.rdpOptConnectConsole, style = MaterialTheme.typography.bodySmall)
                        Switch(checked = forceConsoleAdmin, onCheckedChange = { forceConsoleAdmin = it })
                    }
                }

                HorizontalDivider()

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NexusChoiceChip(
                        selected = placement == QuickLaunchPlacement.ACTIVE_PANE_TAB,
                        onClick = { placement = QuickLaunchPlacement.ACTIVE_PANE_TAB },
                        label = "Tab"
                    )
                    NexusChoiceChip(
                        selected = placement == QuickLaunchPlacement.SPLIT_RIGHT_PANE,
                        onClick = { placement = QuickLaunchPlacement.SPLIT_RIGHT_PANE },
                        label = strings.splitRight
                    )
                    NexusChoiceChip(
                        selected = placement == QuickLaunchPlacement.DETACHED_NEW_WINDOW,
                        onClick = { placement = QuickLaunchPlacement.DETACHED_NEW_WINDOW },
                        label = strings.detachNewWindow
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val override = AdHocLaunchOverride(
                        overrideCredentialId = if (credChoice == 1) selectedCredId else null,
                        adHocUsername = if (credChoice == 2) adHocUsername.trim() else "",
                        adHocDomain = if (credChoice == 2) adHocDomain.trim() else "",
                        adHocPassword = if (credChoice == 2) adHocPassword else "",
                        forceRdpConsoleAdmin = if (targetConnection.protocol == VaultProtocolType.RDP) forceConsoleAdmin else null,
                        appliedTemplateId = selectedTemplateId
                    )
                    onConnectWithOverride(targetConnection, override, placement)
                }
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.dialogActionCancel) }
        }
    )
}

/**
 * Interactive Creator & Editor for Royal TS [SmartFolder] Intelligent Folder Rules.
 * Supports multi-protocol filter chips, folder subtree scope, include/exclude tags,
 * OS family, environment, live regex validation, and live matched-connection preview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewSmartFolderDialog(
    visible: Boolean,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveSmartFolder: (SmartFolder) -> Unit,
    existingSmartFolder: SmartFolder? = null,
    allFolders: List<VaultFolder> = emptyList(),
    validateRegex: (String?) -> String? = { null },
    previewMatches: (SmartFolderFilter) -> List<VaultConnection> = { emptyList() },
    onDeleteSmartFolder: ((String) -> Unit)? = null
) {
    if (!visible) return

    var name by remember(existingSmartFolder) { mutableStateOf(existingSmartFolder?.name.orEmpty()) }
    var selectedProtocols by remember(existingSmartFolder) {
        mutableStateOf<Set<VaultProtocolType>>(
            existingSmartFolder?.filter?.includeProtocols?.toSet() ?: emptySet()
        )
    }
    var scopeFolderId by remember(existingSmartFolder) {
        mutableStateOf(existingSmartFolder?.filter?.scopeFolderId)
    }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var osFilter by remember(existingSmartFolder) {
        mutableStateOf(existingSmartFolder?.filter?.osFamilyFilter.orEmpty())
    }
    var envFilter by remember(existingSmartFolder) {
        mutableStateOf(existingSmartFolder?.filter?.environmentFilter.orEmpty())
    }
    var hostRegex by remember(existingSmartFolder) {
        mutableStateOf(existingSmartFolder?.filter?.hostPatternRegex.orEmpty())
    }
    var includeTagsCsv by remember(existingSmartFolder) {
        mutableStateOf(existingSmartFolder?.filter?.includeTags?.joinToString(", ").orEmpty())
    }
    var excludeTagsCsv by remember(existingSmartFolder) {
        mutableStateOf(existingSmartFolder?.filter?.excludeTags?.joinToString(", ").orEmpty())
    }

    val regexError = remember(hostRegex, validateRegex) { validateRegex(hostRegex) }

    val draftFilter = remember(
        selectedProtocols,
        scopeFolderId,
        osFilter,
        envFilter,
        hostRegex,
        includeTagsCsv,
        excludeTagsCsv,
        regexError
    ) {
        SmartFolderFilter(
            includeTags = includeTagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            excludeTags = excludeTagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            includeProtocols = selectedProtocols.toList(),
            osFamilyFilter = osFilter.ifBlank { null },
            environmentFilter = envFilter.trim().ifBlank { null },
            hostPatternRegex = if (regexError == null) hostRegex.trim().ifBlank { null } else null,
            scopeFolderId = scopeFolderId
        )
    }

    val liveMatches = remember(draftFilter, previewMatches) {
        if (regexError != null) emptyList() else previewMatches(draftFilter)
    }

    val commonProtocols = remember {
        listOf(
            VaultProtocolType.RDP,
            VaultProtocolType.SSH,
            VaultProtocolType.SFTP,
            VaultProtocolType.VNC,
            VaultProtocolType.WEB_PAGE,
            VaultProtocolType.PROXMOX_VE,
            VaultProtocolType.VMWARE_VSPHERE,
            VaultProtocolType.KUBERNETES_POD,
            VaultProtocolType.DOCKER_CONTAINER,
            VaultProtocolType.SERIAL,
            VaultProtocolType.TELNET,
            VaultProtocolType.APPLE_SCREEN_SHARING
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        },
        title = {
            Text(
                if (existingSmartFolder != null) {
                    strings.actionEditSmartFolder
                } else {
                    strings.actionNewSmartFolder
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.dialogFieldName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Scope Folder Subtree Selector
                if (allFolders.isNotEmpty()) {
                    val scopeLabel = allFolders.firstOrNull { it.id == scopeFolderId }?.name
                        ?: strings.smartFolderAllFolders
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { scopeMenuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("${strings.smartFolderScopeLabel}: $scopeLabel")
                        }
                        DropdownMenu(
                            expanded = scopeMenuOpen,
                            onDismissRequest = { scopeMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(strings.smartFolderAllFolders) },
                                onClick = {
                                    scopeFolderId = null
                                    scopeMenuOpen = false
                                }
                            )
                            allFolders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        scopeFolderId = folder.id
                                        scopeMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Multi-Select Protocol Filter Chips
                Text(
                    text = strings.dialogFieldProtocol,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NexusChoiceChip(
                        selected = selectedProtocols.isEmpty(),
                        onClick = { selectedProtocols = emptySet() },
                        label = "All"
                    )
                    commonProtocols.forEach { proto ->
                        val active = proto in selectedProtocols
                        NexusChoiceChip(
                            selected = active,
                            onClick = {
                                selectedProtocols = if (active) {
                                    selectedProtocols - proto
                                } else {
                                    selectedProtocols + proto
                                }
                            },
                            label = proto.name
                        )
                    }
                }

                // OS Family Filter Chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "" to "Any OS",
                        "Windows" to "Windows",
                        "Linux" to "Linux",
                        "macOS" to "macOS"
                    ).forEach { (value, label) ->
                        NexusChoiceChip(
                            selected = osFilter == value,
                            onClick = { osFilter = value },
                            label = label
                        )
                    }
                }

                OutlinedTextField(
                    value = envFilter,
                    onValueChange = { envFilter = it },
                    label = { Text("Environment (e.g. PROD, STAGE, LAB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = includeTagsCsv,
                    onValueChange = { includeTagsCsv = it },
                    label = { Text("Include Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = excludeTagsCsv,
                    onValueChange = { excludeTagsCsv = it },
                    label = { Text(strings.smartFolderExcludeTagsLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = hostRegex,
                    onValueChange = { hostRegex = it },
                    label = { Text("Name / Host Regex (optional)") },
                    isError = regexError != null,
                    supportingText = if (regexError != null) {
                        {
                            Text(
                                text = "${strings.smartFolderInvalidRegexError}: $regexError",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Live Matched Connections Preview Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = String.format(strings.smartFolderPreviewCountFormat, liveMatches.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        if (liveMatches.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                liveMatches.take(6).forEach { conn ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        Text(
                                            text = "${conn.name} (${conn.protocol.name})",
                                            style = NexusTheme.monospace.keycapShortcut,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (liveMatches.size > 6) {
                                    Text(
                                        text = "+${liveMatches.size - 6}",
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && regexError == null,
                onClick = {
                    onSaveSmartFolder(
                        SmartFolder(
                            id = existingSmartFolder?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            filter = draftFilter
                        )
                    )
                }
            ) {
                Text(strings.dialogActionSave)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (existingSmartFolder != null && onDeleteSmartFolder != null) {
                    TextButton(
                        onClick = {
                            onDeleteSmartFolder(existingSmartFolder.id)
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
 * Interactive Creator & Editor for Royal TS [DynamicFolder] Inventory (`JSON_HTTP_API` JSON array or HTTPS URL).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewDynamicFolderDialog(
    visible: Boolean,
    folders: List<VaultFolder> = emptyList(),
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveDynamicFolder: (DynamicFolder) -> Unit,
    existingDynamicFolder: DynamicFolder? = null,
    onDeleteDynamicFolder: ((String) -> Unit)? = null
) {
    if (!visible) return
    var name by remember(existingDynamicFolder) { mutableStateOf(existingDynamicFolder?.name.orEmpty()) }
    var selectedParentId by remember(existingDynamicFolder) {
        mutableStateOf(existingDynamicFolder?.parentFolderId)
    }
    var interpreter by remember(existingDynamicFolder) {
        mutableStateOf(existingDynamicFolder?.interpreter ?: DynamicScriptInterpreter.JSON_HTTP_API)
    }
    var parentMenuOpen by remember { mutableStateOf(false) }
    var scriptPayload by remember(existingDynamicFolder) {
        mutableStateOf(existingDynamicFolder?.scriptOrEndpointPayload.orEmpty())
    }

    val jsonValidationError = remember(interpreter, scriptPayload) {
        val trimmed = scriptPayload.trim()
        if (trimmed.isEmpty()) {
            null
        } else if (interpreter == DynamicScriptInterpreter.JSON_HTTP_API &&
            !trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            runCatching {
                if (trimmed.startsWith("[")) {
                    org.json.JSONArray(trimmed)
                } else if (trimmed.startsWith("{")) {
                    org.json.JSONObject(trimmed)
                } else {
                    throw IllegalArgumentException("Expected HTTPS URL or JSON array/object.")
                }
            }.exceptionOrNull()?.localizedMessage ?: if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
                "Expected HTTPS URL or JSON array/object."
            } else {
                null
            }
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Outlined.DynamicFeed, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        },
        title = {
            Text(
                if (existingDynamicFolder != null) {
                    strings.actionEditDynamicFolder
                } else {
                    strings.actionNewDynamicFolder
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.dialogFieldName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DynamicScriptInterpreter.entries.forEach { candidate ->
                        NexusChoiceChip(
                            selected = interpreter == candidate,
                            onClick = { interpreter = candidate },
                            label = candidate.name
                        )
                    }
                }
                if (folders.isNotEmpty()) {
                    val parentLabel = folders.firstOrNull { it.id == selectedParentId }?.name ?: "-"
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
                                    selectedParentId = null
                                    parentMenuOpen = false
                                }
                            )
                            folders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        selectedParentId = folder.id
                                        parentMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = scriptPayload,
                    onValueChange = { scriptPayload = it },
                    label = { Text("JSON Inventory Array or https:// Inventory Endpoint") },
                    placeholder = {
                        Text(
                            text = "[ { \"name\": \"server-01\", \"host\": \"server.example.internal\", \"protocol\": \"SSH\" } ]",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    isError = jsonValidationError != null,
                    supportingText = if (jsonValidationError != null) {
                        {
                            Text(
                                text = jsonValidationError,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        null
                    },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && scriptPayload.isNotBlank() && jsonValidationError == null,
                onClick = {
                    onSaveDynamicFolder(
                        DynamicFolder(
                            id = existingDynamicFolder?.id ?: java.util.UUID.randomUUID().toString(),
                            parentFolderId = selectedParentId,
                            name = name.trim(),
                            interpreter = interpreter,
                            scriptOrEndpointPayload = scriptPayload.trim()
                        )
                    )
                }
            ) {
                Text(strings.dialogActionSave)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (existingDynamicFolder != null && onDeleteDynamicFolder != null) {
                    TextButton(
                        onClick = {
                            onDeleteDynamicFolder(existingDynamicFolder.id)
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
 * Extended Royal TS Property Inspector Cards:
 * 1. Live RFC 6238 6-digit TOTP Authenticator (when effective credential defines `totpSecretBase32`)
 * 2. Effective Custom Properties (`$CustomProperty.<Key>$` cascade from Folder + Connection)
 * 3. Notes & Markdown Documentation editor
 * 4. Scoped Connection To-Do Checklist
 */
@Composable
fun InspectorExtendedRoyalCards(
    profile: EffectiveConnectionProfile,
    todoItems: List<VaultTodoItem>,
    strings: WorkspaceLocalizedStrings,
    onSaveNotesAndCustomProps: (String, List<CustomPropertyEntry>, String?) -> Unit,
    onAddTodo: (String) -> Unit,
    onToggleTodo: (String) -> Unit,
    onDeleteCustomProperty: (String) -> Unit = {},
    onDeleteTodo: (String) -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    val conn = profile.connection
    var notesDraft by remember(conn.id, conn.notes) { mutableStateOf(conn.notes) }
    var newPropKey by remember(conn.id) { mutableStateOf("") }
    var newPropVal by remember(conn.id) { mutableStateOf("") }
    var newTodoText by remember(conn.id) { mutableStateOf("") }

    // 1. Live 2FA / TOTP Authenticator Card
    val totpSecret = profile.effectiveCredential?.totpSecretBase32.orEmpty()
    if (totpSecret.isNotBlank()) {
        LiveTotpAuthenticatorCard(
            totpSecretBase32 = totpSecret,
            title = strings.inspectorTotpCode
        )
    }

    // 2. Effective Custom Properties (`$CustomProperty.<Key>$`)
    val ownedPropKeys = remember(conn.inheritable.customProperties) {
        conn.inheritable.customProperties.map { it.key.lowercase() }.toSet()
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.inspectorCustomProperties, style = MaterialTheme.typography.labelMedium)
            if (profile.effectiveCustomProperties.isEmpty()) {
                Text(
                    text = "-",
                    style = NexusTheme.monospace.keycapShortcut,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                profile.effectiveCustomProperties.values.forEach { prop ->
                    val isOwnedByConn = prop.key.lowercase() in ownedPropKeys
                    var propMenuOpen by remember(prop.key) { mutableStateOf(false) }
                    val tokenStr = "\$CustomProperty.${prop.key}\$"

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .nexusDesktopPointerButtons(onSecondaryClick = { propMenuOpen = true }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = tokenStr,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (prop.isProtected) "••••••••" else prop.value,
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (isOwnedByConn) {
                                    IconButton(
                                        onClick = { onDeleteCustomProperty(prop.key) },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Delete Custom Property",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = propMenuOpen,
                            onDismissRequest = { propMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Token ($tokenStr)") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    propMenuOpen = false
                                    clipboard.setText(AnnotatedString(tokenStr))
                                }
                            )
                            if (!prop.isProtected) {
                                DropdownMenuItem(
                                    text = { Text("Copy Value") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        propMenuOpen = false
                                        clipboard.setText(AnnotatedString(prop.value))
                                    }
                                )
                            }
                            if (isOwnedByConn) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete Property", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        propMenuOpen = false
                                        onDeleteCustomProperty(prop.key)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newPropKey,
                    onValueChange = { newPropKey = it },
                    placeholder = { Text("Key", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = newPropVal,
                    onValueChange = { newPropVal = it },
                    placeholder = { Text("Value", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = newPropKey.isNotBlank(),
                    onClick = {
                        val updated = conn.inheritable.customProperties.filterNot {
                            it.key.equals(newPropKey.trim(), ignoreCase = true)
                        } + CustomPropertyEntry(key = newPropKey.trim(), value = newPropVal.trim())
                        onSaveNotesAndCustomProps(notesDraft, updated, conn.inheritable.displayColorAccentHex)
                        newPropKey = ""
                        newPropVal = ""
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    // 3. Notes & Documentation Card
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.inspectorNotesDocs, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = notesDraft,
                onValueChange = { notesDraft = it },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            if (notesDraft != conn.notes) {
                NexusActionChip(
                    onClick = {
                        onSaveNotesAndCustomProps(
                            notesDraft,
                            conn.inheritable.customProperties,
                            conn.inheritable.displayColorAccentHex
                        )
                    },
                    label = strings.dialogActionSave,
                    leadingIcon = Icons.Outlined.CheckCircle
                )
            }
        }
    }

    // 4. Scoped Connection To-Do Checklist
    val scopedTodos = todoItems.filter { it.targetObjectId == null || it.targetObjectId == conn.id }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.inspectorTodoItems, style = MaterialTheme.typography.labelMedium)
            scopedTodos.forEach { todo ->
                val todoInteraction = remember { MutableInteractionSource() }
                var todoMenuOpen by remember(todo.id) { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .nexusDesktopPointerButtons(onSecondaryClick = { todoMenuOpen = true })
                            .clickable(
                                interactionSource = todoInteraction,
                                indication = null,
                                onClick = { onToggleTodo(todo.id) }
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = todo.completed,
                                onCheckedChange = { onToggleTodo(todo.id) }
                            )
                            Text(
                                text = todo.title,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (todo.completed) TextDecoration.LineThrough else TextDecoration.None,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onDeleteTodo(todo.id) },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete To-Do",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = todoMenuOpen,
                        onDismissRequest = { todoMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (todo.completed) "Mark Incomplete" else "Mark Complete") },
                            leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                todoMenuOpen = false
                                onToggleTodo(todo.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy Task Title") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                todoMenuOpen = false
                                clipboard.setText(AnnotatedString(todo.title))
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                todoMenuOpen = false
                                onDeleteTodo(todo.id)
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = newTodoText,
                    onValueChange = { newTodoText = it },
                    placeholder = { Text(strings.actionAddTodo, style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    enabled = newTodoText.isNotBlank(),
                    onClick = {
                        onAddTodo(newTodoText)
                        newTodoText = ""
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}


