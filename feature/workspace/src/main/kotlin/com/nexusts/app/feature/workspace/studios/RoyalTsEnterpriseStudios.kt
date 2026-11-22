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
import androidx.compose.material.icons.automirrored.outlined.Logout
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
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.nexusts.app.core.vault.error.VaultHierarchySanitizer
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import com.nexusts.app.feature.workspace.AdHocLaunchOverride
import com.nexusts.app.feature.workspace.i18n.NexusLocaleController
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
    onLogOffConnection: (String) -> Unit = {},
    onMoveConnectionToFolder: (VaultConnection) -> Unit = {},
    onLaunchLocalShell: () -> Unit = {},
    activeConnectionIds: Set<String> = emptySet(),
    detachedWindowCount: Int = 0,
    isDriveLinked: Boolean = false,
    syncStatusSummary: String = "Local encrypted vault",
    isSidebarVisible: Boolean = true,
    isInspectorVisible: Boolean = false,
    broadcastEnabled: Boolean = false,
    onOpenCredentials: () -> Unit = {},
    onOpenCloudSync: () -> Unit = {},
    onToggleSidebar: () -> Unit = {},
    onToggleInspector: () -> Unit = {},
    onToggleBroadcast: () -> Unit = {},
    onSplitRight: () -> Unit = {},
    onSplitDown: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    @Suppress("UNUSED_PARAMETER")
    val ignoredUnusedCallbacks = listOf<Any>(
        onConnectAllInFolder,
        onAddTodo,
        onToggleTodo,
        onDeleteTodo,
        onNewRdpInFolder,
        onNewSshInFolder,
        onNewSubfolder,
        onEditFolder,
        onDuplicateFolder,
        onDeleteFolder,
        onDisconnectAllInFolder
    )

    val clipboard = LocalClipboardManager.current
    var overviewBgMenuOpen by remember { mutableStateOf(false) }

    val activeConnections = remember(vaultDocument.connections, vaultDocument.tombstonedObjectIds) {
        vaultDocument.connections.filter {
            it.id !in vaultDocument.tombstonedObjectIds &&
                it.parentFolderId != VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        }
    }
    val activeFolders = remember(vaultDocument.folders, vaultDocument.tombstonedObjectIds) {
        vaultDocument.folders.filter {
            it.id !in vaultDocument.tombstonedObjectIds &&
                it.id != VaultHierarchySanitizer.SYSTEM_CREDENTIALS_FOLDER_ID
        }
    }
    val activeCredentials = remember(vaultDocument.credentials, vaultDocument.tombstonedObjectIds) {
        vaultDocument.credentials.filter { it.id !in vaultDocument.tombstonedObjectIds }
    }
    val favorites = remember(activeConnections) {
        activeConnections.filter { it.favorite }
    }

    val langCode = remember(strings.appName, strings.actionNewConnection) {
        NexusLocaleController.currentLanguageTag().take(2).lowercase()
    }
    val localTerminalLabel = remember(langCode) {
        when (langCode) {
            "de" -> "Lokales Terminal"
            "fr" -> "Terminal local"
            "it" -> "Terminale locale"
            "es" -> "Terminal local"
            "el" -> "Τοπικό τερματικό"
            else -> "Local Terminal"
        }
    }
    val shortcutsHeading = remember(langCode) {
        when (langCode) {
            "de" -> "Tastaturkurzbefehle"
            "fr" -> "Raccourcis clavier"
            "it" -> "Scorciatoie da tastiera"
            "es" -> "Atajos de teclado"
            "el" -> "Συντομεύσεις πληκτρολογίου"
            else -> "Keyboard Shortcuts"
        }
    }
    val sidebarShortcutLabel = remember(langCode) {
        when (langCode) {
            "de" -> "Seitenleiste"
            "fr" -> "Barre latérale"
            "it" -> "Barra laterale"
            "es" -> "Barra lateral"
            "el" -> "Πλαϊνή μπάρα"
            else -> "Sidebar"
        }
    }

    data class OverviewShortcutSpec(
        val label: String,
        val keys: String,
        val isActive: Boolean,
        val onClick: () -> Unit
    )

    val shortcutItems = listOf(
        OverviewShortcutSpec(strings.quickConnectButton, "Ctrl+K", false, onOpenQuickConnect),
        OverviewShortcutSpec(localTerminalLabel, "Ctrl+T", false, onLaunchLocalShell),
        OverviewShortcutSpec(strings.actionNewConnection, "Ctrl+N", false, onNewConnection),
        OverviewShortcutSpec(strings.splitRight, "Ctrl+D", false, onSplitRight),
        OverviewShortcutSpec(strings.splitDown, "Ctrl+Shift+D", false, onSplitDown),
        OverviewShortcutSpec(strings.actionBroadcastInput, "Ctrl+Shift+B", broadcastEnabled, onToggleBroadcast),
        OverviewShortcutSpec(strings.inspectorTitle, "Ctrl+I", isInspectorVisible, onToggleInspector),
        OverviewShortcutSpec(sidebarShortcutLabel, "Ctrl+B", isSidebarVisible, onToggleSidebar)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .nexusDesktopPointerButtons(onSecondaryClick = { overviewBgMenuOpen = true })
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. Apple HIG Hero Header Row
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWide = maxWidth >= 560.dp
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            NexusBrandEmblem(size = 48.dp, cornerRadius = 11.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = vaultDocument.name,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Zero-Knowledge Argon2id + AES-256-GCM Vault • Revision ${vaultDocument.revision}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onLaunchLocalShell,
                                shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF0F172A)
                                ),
                                border = BorderStroke(1.dp, Color(0xFF0F172A).copy(alpha = 0.14f))
                            ) {
                                Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(localTerminalLabel)
                            }
                            Button(
                                onClick = onNewConnection,
                                shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF007AFF),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.actionNewConnection)
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            NexusBrandEmblem(size = 44.dp, cornerRadius = 11.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = vaultDocument.name,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Zero-Knowledge Argon2id + AES-256-GCM Vault • Revision ${vaultDocument.revision}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onLaunchLocalShell,
                                shape = RoundedCornerShape(9.dp)
                            ) {
                                Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(localTerminalLabel)
                            }
                            Button(
                                onClick = onNewConnection,
                                shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF007AFF),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.actionNewConnection)
                            }
                        }
                    }
                }
            }

            // 2. 4 Pastel Summary Metric Cards (1:1 with macOS OverviewStatCard)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                MacStyleOverviewStatCard(
                    title = strings.navConnections,
                    value = activeConnections.size.toString(),
                    subtitle = strings.ovFoldersCount(activeFolders.size),
                    icon = Icons.Outlined.DesktopWindows,
                    tint = Color(0xFF007AFF),
                    onClick = onOpenQuickConnect
                )
                MacStyleOverviewStatCard(
                    title = strings.overviewActiveSessions,
                    value = activeSessionCount.toString(),
                    subtitle = strings.ovDetachedWindows(detachedWindowCount),
                    icon = Icons.Outlined.Splitscreen,
                    tint = Color(0xFF34C759),
                    onClick = {
                        if (activeSessionCount == 0) onLaunchLocalShell()
                    }
                )
                MacStyleOverviewStatCard(
                    title = strings.ovCredentialsTotp,
                    value = activeCredentials.size.toString(),
                    subtitle = strings.ovSshAndPasswords,
                    icon = Icons.Outlined.Key,
                    tint = Color(0xFFFF9500),
                    onClick = onOpenCredentials
                )
                MacStyleOverviewStatCard(
                    title = strings.navDriveSync,
                    value = if (isDriveLinked) strings.ovLinked else strings.ovLocal,
                    subtitle = syncStatusSummary,
                    icon = Icons.Outlined.CloudSync,
                    tint = Color(0xFF00A8C6),
                    onClick = onOpenCloudSync
                )
            }

            // 3. Favorites Quick Launch Grid (when favorites exist)
            if (favorites.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = strings.vaultSectionFavorites,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        favorites.forEach { fav ->
                            val accent = parseHexColorOrNull(fav.inheritable.displayColorAccentHex)
                                ?: Color(0xFF007AFF)
                            val isFavActive = fav.id in activeConnectionIds
                            val favHover = rememberDesktopHoverState()
                            var favMenuOpen by remember(fav.id) { mutableStateOf(false) }

                            Box {
                                val favShape = RoundedCornerShape(14.dp)
                                Row(
                                    modifier = Modifier
                                        .width(268.dp)
                                        .nexusHoverTracker(favHover, PointerIcon.Hand)
                                        .clip(favShape)
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    accent.copy(alpha = if (favHover.value.isHovered) 0.13f else 0.07f),
                                                    Color.White
                                                )
                                            ),
                                            favShape
                                        )
                                        .border(
                                            1.dp,
                                            accent.copy(alpha = if (favHover.value.isHovered) 0.50f else 0.22f),
                                            favShape
                                        )
                                        .nexusInteractiveRow(
                                            rowKey = "fav-${fav.id}",
                                            onClick = {},
                                            onDoubleClick = { onLaunchConnection(fav) },
                                            onSecondaryClick = { favMenuOpen = true }
                                        )
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(accent.copy(alpha = 0.14f))
                                            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(9.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = fav.protocol.shortBadge,
                                            style = NexusTheme.monospace.keycapShortcut,
                                            color = accent
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = fav.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isFavActive) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF34C759))
                                                )
                                            }
                                        }
                                        Text(
                                            text = fav.protocol.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = { onLaunchConnection(fav) },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.OpenInNew,
                                            contentDescription = "Connect",
                                            tint = Color(0xFF007AFF),
                                            modifier = Modifier.size(16.dp)
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
                                        if (fav.protocol == VaultProtocolType.RDP ||
                                            fav.protocol == VaultProtocolType.WINDOWS_TERMINAL_SERVICES
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(strings.actionLogOffSession, color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.AutoMirrored.Outlined.Logout,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
                                                onClick = {
                                                    favMenuOpen = false
                                                    onLogOffConnection(fav.id)
                                                }
                                            )
                                        }
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

            // 4. Interactive Keyboard Shortcut Cheat Sheet (1:1 with macOS ShortcutLegendItem)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = shortcutsHeading,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = Color.White.copy(alpha = 0.76f),
                    border = BorderStroke(1.dp, Color(0xFF0F172A).copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        shortcutItems.forEach { item ->
                            val scHover = rememberDesktopHoverState()
                            val activeBlue = Color(0xFF007AFF)
                            val shape = RoundedCornerShape(8.dp)
                            Row(
                                modifier = Modifier
                                    .widthIn(min = 185.dp)
                                    .nexusHoverTracker(scHover, PointerIcon.Hand)
                                    .clip(shape)
                                    .background(
                                        when {
                                            item.isActive -> activeBlue.copy(alpha = 0.13f)
                                            scHover.value.isHovered -> Color(0xFF0F172A).copy(alpha = 0.07f)
                                            else -> Color(0xFF0F172A).copy(alpha = 0.03f)
                                        },
                                        shape
                                    )
                                    .border(
                                        0.8.dp,
                                        if (item.isActive) activeBlue.copy(alpha = 0.35f) else Color(0xFF0F172A).copy(alpha = 0.08f),
                                        shape
                                    )
                                    .clickable(onClick = item.onClick)
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (item.isActive) Color(0xFF0066D6) else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = Color.White.copy(alpha = 0.88f),
                                    border = BorderStroke(
                                        0.8.dp,
                                        if (item.isActive) activeBlue.copy(alpha = 0.25f) else Color(0xFF0F172A).copy(alpha = 0.10f)
                                    )
                                ) {
                                    Text(
                                        text = item.keys,
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = if (item.isActive) Color(0xFF0066D6) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
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
                text = { Text(localTerminalLabel) },
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

@Composable
private fun MacStyleOverviewStatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    val hover = rememberDesktopHoverState()
    val isHovered = hover.value.isHovered
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .width(205.dp)
            .nexusHoverTracker(hover, PointerIcon.Hand)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        tint.copy(alpha = if (isHovered) 0.16f else 0.11f),
                        Color.White.copy(alpha = 0.92f)
                    )
                ),
                shape
            )
            .border(
                1.dp,
                tint.copy(alpha = if (isHovered) 0.36f else 0.22f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF0F172A),
                    maxLines = 1
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFF0F172A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
    var rdpScalePercent by remember(editingTemplate) { mutableIntStateOf(existingRdp?.desktopScaleFactorPercent ?: 200) }

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
                NexusProtocolSelectorField(
                    label = strings.dialogFieldProtocol,
                    cancelLabel = strings.dialogActionCancel,
                    selectedProtocol = protocol,
                    onSelectProtocol = { protocol = it }
                )
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
                                label = if (pct == 200) "200% (Default)" else "$pct%"
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
                            desktopScaleFactorPercent = rdpScalePercent,
                            deviceScaleFactorPercent = if (rdpScalePercent >= 175) 180 else if (rdpScalePercent >= 125) 140 else 100,
                            hiDpiScaleCustomized = true
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
                    text = "${targetConnection.protocol.displayName} -> ${targetConnection.host}",
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
                        val credOptions = remember(credentials) {
                            credentials.map { cred ->
                                NexusSelectOption<String?>(
                                    value = cred.id,
                                    title = cred.name,
                                    subtitle = cred.username.takeIf { it.isNotBlank() }
                                )
                            }
                        }
                        NexusDialogSelectField(
                            buttonText = chosen?.name ?: "-",
                            dialogTitle = strings.inspectorEffectiveCredential,
                            cancelLabel = strings.dialogActionCancel,
                            selectedValue = selectedCredId,
                            options = credOptions,
                            leadingIcon = Icons.Outlined.Key,
                            onSelect = { selectedCredId = it }
                        )
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
                    val tplOptions = remember(matchingTemplates) {
                        buildList {
                            add(NexusSelectOption<String?>(value = null, title = "-"))
                            matchingTemplates.forEach { tpl ->
                                add(
                                    NexusSelectOption(
                                        value = tpl.id,
                                        title = tpl.name,
                                        subtitle = tpl.description.takeIf { it.isNotBlank() }
                                    )
                                )
                            }
                        }
                    }
                    NexusDialogSelectField(
                        buttonText = "${strings.dialogFieldTemplate}: ${chosenTpl?.name ?: "-"}",
                        dialogTitle = strings.dialogFieldTemplate,
                        cancelLabel = strings.dialogActionCancel,
                        selectedValue = selectedTemplateId,
                        options = tplOptions,
                        leadingIcon = Icons.Outlined.Bookmarks,
                        onSelect = { selectedTemplateId = it }
                    )
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
                    val scopeOptions = remember(allFolders, strings.smartFolderAllFolders) {
                        buildList {
                            add(NexusSelectOption<String?>(value = null, title = strings.smartFolderAllFolders))
                            allFolders.forEach { folder ->
                                add(NexusSelectOption(value = folder.id, title = folder.name))
                            }
                        }
                    }
                    NexusDialogSelectField(
                        buttonText = "${strings.smartFolderScopeLabel}: $scopeLabel",
                        dialogTitle = strings.smartFolderScopeLabel,
                        cancelLabel = strings.dialogActionCancel,
                        selectedValue = scopeFolderId,
                        options = scopeOptions,
                        leadingIcon = Icons.Outlined.Folder,
                        onSelect = { scopeFolderId = it }
                    )
                }

                // Multi-Select Protocol Filter Chips (all 23 VaultProtocolType entries)
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
                    VaultProtocolType.entries.forEach { proto ->
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
                            label = proto.displayName
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
                                            text = "${conn.name} (${conn.protocol.shortBadge})",
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


