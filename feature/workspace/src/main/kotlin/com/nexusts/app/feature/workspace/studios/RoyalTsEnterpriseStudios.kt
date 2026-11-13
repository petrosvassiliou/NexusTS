package com.nexusts.app.feature.workspace.studios

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.components.ExpressiveMetricCard
import com.nexusts.app.core.ui.components.NexusBrandEmblem
import com.nexusts.app.core.ui.components.parseHexColorOrNull
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
    "#2ED3B7" to "Mint",
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
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = if (isSelected) {
                        swatchColor.copy(alpha = 0.20f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) swatchColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .clickable { onSelectHex(hex) }
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
    modifier: Modifier = Modifier
) {
    var newTodoTitle by remember { mutableStateOf("") }
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

    val isVaultCleanEmpty = activeConnections.isEmpty() && activeFolders.isEmpty() && activeTodos.isEmpty()

    if (isVaultCleanEmpty) {
        // Serene, spacious Material 3 Expressive Welcome State for a clean empty workspace
        Box(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
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
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    NexusBrandEmblem(size = 72.dp, cornerRadius = 20.dp)

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = strings.emptyWorkspaceTitle,
                        style = MaterialTheme.typography.headlineSmall,
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
                            .widthIn(max = 460.dp)
                            .fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
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
                        if (onImportVault != null) {
                            OutlinedButton(onClick = onImportVault) {
                                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.actionImportRtsz)
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("RDP", "SSH", "SFTP", "VNC", "HTTPS", "K8s", "Serial").forEach { proto ->
                            Surface(
                                shape = RoundedCornerShape(99.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = proto,
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Populated Vault Overview — responsive layout that never squishes titles or metrics at any width
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 680.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Expressive Hero Banner — stacks text cleanly above FlowRow actions so text always has 100% width
            val heroShape = rememberMorphingContainerShape(
                MorphContainerState.Resting,
                restingRadius = 26.dp,
                hoveredRadius = 30.dp
            )
            Surface(
                shape = heroShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NexusBrandEmblem(size = 44.dp, cornerRadius = 13.dp)
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onNewConnection) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.actionNewConnection)
                        }
                        FilledTonalButton(onClick = onOpenQuickConnect) {
                            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.quickConnectButton)
                        }
                        if (onImportVault != null) {
                            OutlinedButton(onClick = onImportVault) {
                                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.actionImportRtsz)
                            }
                        }
                    }
                }
            }

            // Adaptive Material 3 Expressive Metric Grid (4-col on wide windows, 2x2 on compact/medium panes)
            if (isWide) {
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
                    ExpressiveMetricCard(
                        label = strings.navTemplates,
                        value = vaultDocument.templates.size.toString(),
                        subtitle = strings.actionNewTemplate,
                        icon = Icons.Outlined.Bookmarks,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        onClick = onOpenTemplates,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveMetricCard(
                        label = strings.inspectorTodoItems,
                        value = pendingTodos.toString(),
                        subtitle = "${activeTodos.size}",
                        icon = Icons.Outlined.CheckCircle,
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveMetricCard(
                            label = strings.navTemplates,
                            value = vaultDocument.templates.size.toString(),
                            subtitle = strings.actionNewTemplate,
                            icon = Icons.Outlined.Bookmarks,
                            accentColor = MaterialTheme.colorScheme.tertiary,
                            onClick = onOpenTemplates,
                            modifier = Modifier.weight(1f)
                        )
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

            // Pinned Favorites Quick-Launch Strip
            if (favorites.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = strings.vaultSectionFavorites,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        favorites.forEach { fav ->
                            val accent = parseHexColorOrNull(fav.inheritable.displayColorAccentHex)
                                ?: MaterialTheme.colorScheme.primary
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .width(250.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onLaunchConnection(fav) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = fav.protocol.name,
                                            style = NexusTheme.monospace.keycapShortcut,
                                            color = accent
                                        )
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
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = fav.host,
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Adaptive Bottom Deck: Folders (only when folders exist) + To-Do Checklist (only when todos exist)
            if (activeFolders.isNotEmpty() || activeTodos.isNotEmpty()) {
                val foldersCard: @Composable (Modifier) -> Unit = { cardMod ->
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
                            Text(strings.vaultSectionFolders, style = MaterialTheme.typography.titleMedium)
                            activeFolders.forEach { folder ->
                                val childCount = activeConnections.count { it.parentFolderId == folder.id }
                                val folderAccent = parseHexColorOrNull(folder.inheritable.displayColorAccentHex)
                                    ?: MaterialTheme.colorScheme.primary
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Folder,
                                                contentDescription = null,
                                                tint = folderAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = folder.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "$childCount",
                                                    style = NexusTheme.monospace.keycapShortcut,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        FilledTonalButton(
                                            enabled = childCount > 0,
                                            onClick = { onConnectAllInFolder(folder.id) }
                                        ) {
                                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = strings.actionConnectAllFolder,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                        }
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onToggleTodo(todo.id) }
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
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
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
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
                }
            }
        }
    }
}

/**
 * Dialog to create a new reusable Royal TS [ConnectionTemplate].
 */
@Composable
fun NewTemplateDialog(
    visible: Boolean,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveTemplate: (ConnectionTemplate) -> Unit
) {
    if (!visible) return
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(VaultProtocolType.RDP) }
    var colorHex by remember { mutableStateOf<String?>("#5B9BFF") }
    var rdpAdminConsole by remember { mutableStateOf(true) }
    var rdpScalePercent by remember { mutableIntStateOf(150) }
    var protoMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.actionNewTemplate) },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(100, 125, 150, 175, 200).forEach { pct ->
                            FilterChip(
                                selected = rdpScalePercent == pct,
                                onClick = { rdpScalePercent = pct },
                                label = { Text("$pct%", style = MaterialTheme.typography.labelSmall) }
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
                            name = name.trim(),
                            description = description.trim(),
                            protocol = protocol,
                            colorAccentHex = colorHex,
                            inheritable = InheritableProperties(displayColorAccentHex = colorHex),
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

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = credChoice == 0,
                        onClick = { credChoice = 0 },
                        label = { Text(strings.credModeInheritFolder, style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = credChoice == 1,
                        onClick = { credChoice = 1 },
                        label = { Text(strings.credModeOverrideExplicit, style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = credChoice == 2,
                        onClick = { credChoice = 2 },
                        label = { Text("Ad-Hoc Login", style = MaterialTheme.typography.labelSmall) }
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

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = placement == QuickLaunchPlacement.ACTIVE_PANE_TAB,
                        onClick = { placement = QuickLaunchPlacement.ACTIVE_PANE_TAB },
                        label = { Text("Tab", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = placement == QuickLaunchPlacement.SPLIT_RIGHT_PANE,
                        onClick = { placement = QuickLaunchPlacement.SPLIT_RIGHT_PANE },
                        label = { Text(strings.splitRight, style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = placement == QuickLaunchPlacement.DETACHED_NEW_WINDOW,
                        onClick = { placement = QuickLaunchPlacement.DETACHED_NEW_WINDOW },
                        label = { Text(strings.detachNewWindow, style = MaterialTheme.typography.labelSmall) }
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
 * Interactive Creator for Royal TS [SmartFolder] Dynamic Rules.
 */
@Composable
fun NewSmartFolderDialog(
    visible: Boolean,
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveSmartFolder: (SmartFolder) -> Unit
) {
    if (!visible) return
    var name by remember { mutableStateOf("") }
    var osFilter by remember { mutableStateOf("") }
    var envFilter by remember { mutableStateOf("") }
    var hostRegex by remember { mutableStateOf("") }
    var tagsCsv by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        },
        title = { Text(strings.actionNewSmartFolder) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.dialogFieldName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "Any OS", "Windows" to "Windows", "Linux" to "Linux").forEach { (value, label) ->
                        FilterChip(
                            selected = osFilter == value,
                            onClick = { osFilter = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = envFilter,
                    onValueChange = { envFilter = it },
                    label = { Text("Environment (e.g. PROD, LAB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hostRegex,
                    onValueChange = { hostRegex = it },
                    label = { Text("Host Regex (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsCsv,
                    onValueChange = { tagsCsv = it },
                    label = { Text("Required Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val tags = tagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    onSaveSmartFolder(
                        SmartFolder(
                            name = name.trim(),
                            filter = SmartFolderFilter(
                                includeTags = tags,
                                osFamilyFilter = osFilter.ifBlank { null },
                                environmentFilter = envFilter.trim().ifBlank { null },
                                hostPatternRegex = hostRegex.trim().ifBlank { null }
                            )
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
 * Interactive Creator for Royal TS [DynamicFolder] Inventory (`JSON_HTTP_API` JSON array or HTTPS URL).
 */
@Composable
fun NewDynamicFolderDialog(
    visible: Boolean,
    folders: List<VaultFolder> = emptyList(),
    strings: WorkspaceLocalizedStrings,
    onDismiss: () -> Unit,
    onSaveDynamicFolder: (DynamicFolder) -> Unit
) {
    if (!visible) return
    var name by remember { mutableStateOf("") }
    var selectedParentId by remember { mutableStateOf<String?>(null) }
    var parentMenuOpen by remember { mutableStateOf(false) }
    var scriptPayload by remember {
        mutableStateOf(
            """
            [
              { "name": "k8s-edge-01", "host": "10.90.0.11", "protocol": "SSH" },
              { "name": "win-hyperv-01", "host": "10.90.0.25", "protocol": "RDP" }
            ]
            """.trimIndent()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Outlined.DynamicFeed, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        },
        title = { Text(strings.vaultSectionDynamicFolders) },
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
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && scriptPayload.isNotBlank(),
                onClick = {
                    onSaveDynamicFolder(
                        DynamicFolder(
                            parentFolderId = selectedParentId,
                            name = name.trim(),
                            interpreter = DynamicScriptInterpreter.JSON_HTTP_API,
                            scriptOrEndpointPayload = scriptPayload.trim()
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "\$CustomProperty.${prop.key}\$",
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
                AssistChip(
                    onClick = {
                        onSaveNotesAndCustomProps(
                            notesDraft,
                            conn.inheritable.customProperties,
                            conn.inheritable.displayColorAccentHex
                        )
                    },
                    label = { Text(strings.dialogActionSave, style = MaterialTheme.typography.labelSmall) }
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleTodo(todo.id) },
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

