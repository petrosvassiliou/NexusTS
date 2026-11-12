package com.nexusts.app.core.ui.workspace

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexusts.app.core.ui.theme.NexusTheme

@Immutable
data class QuickConnectCandidate(
    val id: String,
    val title: String,
    val subtitle: String,
    val protocol: SessionProtocolKind,
    val folderBreadcrumb: String,
    val tags: List<String> = emptyList(),
    val isAdHocUri: Boolean = false
)

enum class QuickLaunchPlacement {
    ACTIVE_PANE_TAB,
    SPLIT_RIGHT_PANE,
    DETACHED_NEW_WINDOW
}

/**
 * Parses ad-hoc quick connect expressions typed directly into the Ctrl/Cmd + K palette, e.g.:
 * - `ssh://root@10.20.0.5:2222` or `ssh deploy@prod-api.internal`
 * - `rdp://corp\admin@win-jump.corp.local:3389`
 * - `sftp://ubuntu@files.example.com`
 * - `vnc://192.168.1.50:5901`
 * - `https://proxmox.internal:8006`
 */
object AdHocConnectionSyntaxParser {
    fun parseOrNull(rawQuery: String): QuickConnectCandidate? {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 4) return null

        val shorthandRegex = Regex("^(ssh|rdp|sftp|scp|ftp|ftps|vnc|telnet)\\s+([^\\s]+)$", RegexOption.IGNORE_CASE)
        shorthandRegex.matchEntire(trimmed)?.let { match ->
            val protoToken = match.groupValues[1].lowercase()
            val target = match.groupValues[2]
            val protocol = mapProtocolToken(protoToken)
            return QuickConnectCandidate(
                id = "adhoc:$protoToken://$target",
                title = "${protoToken.uppercase()} $target",
                subtitle = target,
                protocol = protocol,
                folderBreadcrumb = "Ad-Hoc Quick Connect",
                isAdHocUri = true
            )
        }

        val uriRegex = Regex("^(ssh|rdp|sftp|scp|ftp|ftps|vnc|http|https)://(.+)$", RegexOption.IGNORE_CASE)
        uriRegex.matchEntire(trimmed)?.let { match ->
            val scheme = match.groupValues[1].lowercase()
            val target = match.groupValues[2]
            val protocol = mapProtocolToken(scheme)
            return QuickConnectCandidate(
                id = "adhoc:$trimmed",
                title = trimmed,
                subtitle = target,
                protocol = protocol,
                folderBreadcrumb = "Ad-Hoc URI Connect",
                isAdHocUri = true
            )
        }
        return null
    }

    private fun mapProtocolToken(token: String): SessionProtocolKind = when (token) {
        "rdp" -> SessionProtocolKind.RDP
        "ssh" -> SessionProtocolKind.SSH
        "telnet" -> SessionProtocolKind.TELNET
        "sftp" -> SessionProtocolKind.SFTP
        "scp" -> SessionProtocolKind.SCP
        "ftp" -> SessionProtocolKind.FTP
        "ftps" -> SessionProtocolKind.FTPS
        "vnc" -> SessionProtocolKind.VNC
        else -> SessionProtocolKind.WEB_PAGE
    }
}

/**
 * Floating Quick-Connect Command Palette (`Ctrl/Cmd + K`) with fuzzy vault search,
 * ad-hoc URI parsing, arrow-key navigation, and multi-target launch modifiers:
 * - `Enter`: Open in Active Tab
 * - `Shift + Enter`: Split Horizontally & Connect
 * - `Alt + Enter`: Launch in Detached Native Googlebook OS Window
 */
@Composable
fun QuickConnectCommandPalette(
    visible: Boolean,
    vaultCandidates: List<QuickConnectCandidate>,
    placeholderText: String,
    footerHintText: String,
    onDismiss: () -> Unit,
    onLaunchCandidate: (QuickConnectCandidate, QuickLaunchPlacement) -> Unit
) {
    val motion = NexusTheme.motion
    var query by remember(visible) { mutableStateOf("") }
    var selectedIndex by remember(visible, query) { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(query, vaultCandidates) {
        val q = query.trim().lowercase()
        val adHoc = AdHocConnectionSyntaxParser.parseOrNull(query)
        val matched = if (q.isEmpty()) {
            vaultCandidates.take(25)
        } else {
            vaultCandidates.filter { cand ->
                cand.title.lowercase().contains(q) ||
                    cand.subtitle.lowercase().contains(q) ||
                    cand.folderBreadcrumb.lowercase().contains(q) ||
                    cand.tags.any { it.lowercase().contains(q) } ||
                    cand.protocol.name.lowercase().contains(q)
            }.take(25)
        }
        if (adHoc != null) listOf(adHoc) + matched else matched
    }

    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = motion.effectsFastFloat),
        exit = fadeOut(animationSpec = motion.effectsFastFloat)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(top = 84.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 360.dp, max = 680.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* Consume clicks inside modal so backdrop scrim does not dismiss */ }
                    )
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            AndroidKeyEvent.KEYCODE_ESCAPE -> {
                                onDismiss()
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (filtered.isNotEmpty()) {
                                    selectedIndex = (selectedIndex + 1) % filtered.size
                                }
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                if (filtered.isNotEmpty()) {
                                    selectedIndex = (selectedIndex - 1 + filtered.size) % filtered.size
                                }
                                true
                            }
                            AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                val chosen = filtered.getOrNull(selectedIndex)
                                if (chosen != null) {
                                    val placement = when {
                                        event.isAltPressed -> QuickLaunchPlacement.DETACHED_NEW_WINDOW
                                        event.isShiftPressed -> QuickLaunchPlacement.SPLIT_RIGHT_PANE
                                        else -> QuickLaunchPlacement.ACTIVE_PANE_TAB
                                    }
                                    onLaunchCandidate(chosen, placement)
                                    onDismiss()
                                }
                                true
                            }
                            else -> false
                        }
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
                shadowElevation = 20.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        },
                        trailingIcon = {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Text(
                                    text = "ESC",
                                    style = NexusTheme.monospace.keycapShortcut,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        placeholder = {
                            Text(
                                text = placeholderText,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        shape = MaterialTheme.shapes.large,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.secondary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .padding(vertical = 6.dp, horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(filtered, key = { _, item -> item.id }) { index, candidate ->
                            val isSelected = index == selectedIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    )
                                    .clickable {
                                        onLaunchCandidate(candidate, QuickLaunchPlacement.ACTIVE_PANE_TAB)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (candidate.isAdHocUri) {
                                        Icons.Outlined.Bolt
                                    } else {
                                        protocolIcon(candidate.protocol)
                                    },
                                    contentDescription = null,
                                    tint = protocolAccentColor(candidate.protocol),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    Text(
                                        text = "${candidate.folderBreadcrumb}  •  ${candidate.subtitle}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Text(
                                        text = candidate.protocol.name,
                                        style = NexusTheme.monospace.keycapShortcut,
                                        color = protocolAccentColor(candidate.protocol),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Launch,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = footerHintText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "↵ Tab   ⇧↵ Split   ⌥↵ Window",
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}
