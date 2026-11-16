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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    val isAdHocUri: Boolean = false,
    val parsedHost: String? = null,
    val parsedPort: Int? = null,
    val parsedUsername: String? = null,
    val parsedDomain: String? = null,
    val parsedProtocolSubtype: String? = null
)

enum class QuickLaunchPlacement {
    ACTIVE_PANE_TAB,
    SPLIT_RIGHT_PANE,
    DETACHED_NEW_WINDOW
}

/**
 * Parses ad-hoc quick connect expressions typed directly into the Ctrl/Cmd + K palette, e.g.:
 * - `ssh://root@10.20.0.5:2222` or `ssh deploy@prod-api.internal:22`
 * - `rdp://corp\admin@win-jump.corp.local:3389` or `wts://10.0.0.8`
 * - `nexusts://ssh/ubuntu@10.0.0.12:2222` or `nexusts://10.0.0.12:3389`
 * - `sftp://ubuntu@files.example.com`
 * - `vnc://192.168.1.50:5901` or `ard://mac-mini.local`
 * - `ping://1.1.1.1`, `traceroute://8.8.8.8`, `portscan://10.0.0.1`, `wol://00:11:22:33:44:55`
 * - `https://proxmox.internal:8006`
 * - `root@10.20.0.5:2222` or `CORP\admin@dc01.corp.local`
 */
object AdHocConnectionSyntaxParser {
    private data class ParsedTargetEndpoint(
        val host: String,
        val port: Int? = null,
        val username: String? = null,
        val domain: String? = null
    )

    private val knownProtos = setOf(
        "ssh", "rdp", "wts", "ts", "sftp", "scp", "ftp", "ftps", "vnc",
        "ard", "screensharing", "telnet", "serial", "k8s", "kubectl", "docker",
        "http", "https", "ping", "traceroute", "tracert", "mtr",
        "portscan", "scan", "wol", "wakeonlan", "proxmox", "pve", "vsphere", "esxi", "hyperv"
    )

    fun parseOrNull(rawQuery: String): QuickConnectCandidate? {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 4) return null

        // 1. nexusts:// deep-link scheme (e.g. nexusts://ssh/user@host:2222 or nexusts://host:3389)
        if (trimmed.startsWith("nexusts://", ignoreCase = true)) {
            val remainder = trimmed.substring("nexusts://".length).trimStart('/')
            if (remainder.isBlank()) return null
            val slashIdx = remainder.indexOf('/')
            val (protoToken, rawTarget) = if (slashIdx > 0 &&
                remainder.substring(0, slashIdx).lowercase() in knownProtos
            ) {
                remainder.substring(0, slashIdx).lowercase() to remainder.substring(slashIdx + 1)
            } else {
                val inferredEndpoint = parseAuthorityEndpoint(remainder.substringBefore('?'))
                val inferredProto = when (inferredEndpoint?.port) {
                    3389 -> "rdp"
                    5900, 5901 -> "vnc"
                    23 -> "telnet"
                    80 -> "http"
                    443, 8006, 8443 -> "https"
                    else -> if (inferredEndpoint?.domain != null) "rdp" else "ssh"
                }
                inferredProto to remainder
            }
            val cleanTarget = rawTarget.substringBefore('?').trimEnd('/')
            if (cleanTarget.isBlank()) return null
            val protocol = mapProtocolToken(protoToken)
            val subtype = mapProtocolSubtype(protoToken)
            val endpoint = if (protocol == SessionProtocolKind.WEB_PAGE) {
                val scheme = if (protoToken == "http") "http" else "https"
                ParsedTargetEndpoint(host = "$scheme://$cleanTarget")
            } else {
                parseAuthorityEndpoint(cleanTarget) ?: return null
            }
            return QuickConnectCandidate(
                id = "adhoc:$trimmed",
                title = "${protocol.name} ${endpoint.host}${endpoint.port?.let { ":$it" } ?: ""}",
                subtitle = cleanTarget,
                protocol = protocol,
                folderBreadcrumb = "NexusTS URI Quick Connect",
                isAdHocUri = true,
                parsedHost = endpoint.host,
                parsedPort = endpoint.port,
                parsedUsername = endpoint.username,
                parsedDomain = endpoint.domain,
                parsedProtocolSubtype = subtype
            )
        }

        // 2. Shorthand command syntax: "ssh root@10.20.0.5:2222", "ping 1.1.1.1", "traceroute 8.8.8.8"
        val shorthandRegex = Regex(
            "^(ssh|rdp|wts|ts|sftp|scp|ftp|ftps|vnc|ard|screensharing|telnet|serial|k8s|kubectl|docker|ping|traceroute|tracert|mtr|portscan|scan|wol|wakeonlan|proxmox|pve|vsphere|esxi|hyperv)\\s+([^\\s]+)$",
            RegexOption.IGNORE_CASE
        )
        shorthandRegex.matchEntire(trimmed)?.let { match ->
            val protoToken = match.groupValues[1].lowercase()
            val target = match.groupValues[2]
            val protocol = mapProtocolToken(protoToken)
            val subtype = mapProtocolSubtype(protoToken)
            val endpoint = parseAuthorityEndpoint(target) ?: ParsedTargetEndpoint(host = target)
            return QuickConnectCandidate(
                id = "adhoc:$protoToken://$target",
                title = "${protoToken.uppercase()} $target",
                subtitle = target,
                protocol = protocol,
                folderBreadcrumb = "Ad-Hoc Quick Connect",
                isAdHocUri = true,
                parsedHost = endpoint.host,
                parsedPort = endpoint.port,
                parsedUsername = endpoint.username,
                parsedDomain = endpoint.domain,
                parsedProtocolSubtype = subtype
            )
        }

        // 3. Explicit protocol URI: "ssh://...", "rdp://...", "ping://...", "ard://...", "https://..."
        val uriRegex = Regex(
            "^(ssh|rdp|wts|ts|sftp|scp|ftp|ftps|vnc|ard|screensharing|telnet|serial|k8s|kubectl|docker|http|https|ping|traceroute|tracert|mtr|portscan|scan|wol|wakeonlan|proxmox|pve|vsphere|esxi|hyperv)://(.+)$",
            RegexOption.IGNORE_CASE
        )
        uriRegex.matchEntire(trimmed)?.let { match ->
            val scheme = match.groupValues[1].lowercase()
            val target = match.groupValues[2].trim()
            if (target.isEmpty()) return null
            val protocol = mapProtocolToken(scheme)
            val subtype = mapProtocolSubtype(scheme)
            val endpoint = if (protocol == SessionProtocolKind.WEB_PAGE) {
                ParsedTargetEndpoint(host = trimmed)
            } else {
                parseAuthorityEndpoint(target.trimEnd('/')) ?: ParsedTargetEndpoint(host = target.trimEnd('/'))
            }
            return QuickConnectCandidate(
                id = "adhoc:$trimmed",
                title = trimmed,
                subtitle = target,
                protocol = protocol,
                folderBreadcrumb = "Ad-Hoc URI Connect",
                isAdHocUri = true,
                parsedHost = endpoint.host,
                parsedPort = endpoint.port,
                parsedUsername = endpoint.username,
                parsedDomain = endpoint.domain,
                parsedProtocolSubtype = subtype
            )
        }

        // 4. Bare user@host[:port] or domain\user@host[:port] expression without scheme
        if (!trimmed.contains(' ') && trimmed.contains('@') && !trimmed.startsWith('@') && !trimmed.endsWith('@')) {
            val endpoint = parseAuthorityEndpoint(trimmed)
            if (endpoint != null && endpoint.host.isNotBlank() && !endpoint.username.isNullOrBlank()) {
                val (inferredProto, inferredSubtype) = when {
                    endpoint.port == 3389 || endpoint.domain != null -> SessionProtocolKind.RDP to "RDP"
                    endpoint.port == 5900 || endpoint.port == 5901 -> SessionProtocolKind.VNC to "VNC"
                    endpoint.port == 23 -> SessionProtocolKind.TELNET to "TELNET"
                    endpoint.port == 21 -> SessionProtocolKind.FTP to "FTP"
                    endpoint.port == 990 -> SessionProtocolKind.FTPS to "FTPS"
                    else -> SessionProtocolKind.SSH to "SSH"
                }
                return QuickConnectCandidate(
                    id = "adhoc:${inferredProto.name.lowercase()}://$trimmed",
                    title = "${inferredProto.name} $trimmed",
                    subtitle = trimmed,
                    protocol = inferredProto,
                    folderBreadcrumb = "Ad-Hoc Quick Connect",
                    isAdHocUri = true,
                    parsedHost = endpoint.host,
                    parsedPort = endpoint.port,
                    parsedUsername = endpoint.username,
                    parsedDomain = endpoint.domain,
                    parsedProtocolSubtype = inferredSubtype
                )
            }
        }

        return null
    }

    private fun parseAuthorityEndpoint(rawTarget: String): ParsedTargetEndpoint? {
        val cleaned = rawTarget.trim()
        if (cleaned.isEmpty()) return null
        // Preserve serial device paths or k8s namespace/pod targets intact
        if (cleaned.startsWith("/dev/")) {
            return ParsedTargetEndpoint(host = cleaned)
        }

        val atIndex = cleaned.lastIndexOf('@')
        val userPart = if (atIndex > 0) cleaned.substring(0, atIndex) else null
        val hostPortPart = if (atIndex >= 0) cleaned.substring(atIndex + 1) else cleaned
        if (hostPortPart.isBlank()) return null

        var domain: String? = null
        var username: String? = null
        if (!userPart.isNullOrBlank()) {
            val slashIdx = userPart.indexOf('\\').takeIf { it >= 0 }
            if (slashIdx != null) {
                domain = userPart.substring(0, slashIdx).takeIf { it.isNotBlank() }
                username = userPart.substring(slashIdx + 1).takeIf { it.isNotBlank() }
            } else {
                username = userPart.substringBefore(':') // strip any accidental inline ":pass"
            }
        }

        val (host, port) = if (hostPortPart.startsWith('[')) {
            // IPv6 literal: [2001:db8::1]:2222 or [::1]
            val closeBracket = hostPortPart.indexOf(']')
            if (closeBracket <= 1) return null
            val ipv6Host = hostPortPart.substring(1, closeBracket)
            val after = hostPortPart.substring(closeBracket + 1)
            val parsedPort = if (after.startsWith(':')) {
                after.substring(1).substringBefore('/').toIntOrNull()?.takeIf { it in 1..65535 }
            } else {
                null
            }
            ipv6Host to parsedPort
        } else {
            val withoutPath = hostPortPart.substringBefore('/')
            val colonCount = withoutPath.count { it == ':' }
            if (colonCount == 1) {
                val h = withoutPath.substringBefore(':')
                val pStr = withoutPath.substringAfter(':')
                val p = pStr.toIntOrNull()?.takeIf { it in 1..65535 }
                if (p != null && h.isNotBlank()) {
                    h to p
                } else {
                    withoutPath to null
                }
            } else {
                hostPortPart to null
            }
        }

        if (host.isBlank()) return null
        return ParsedTargetEndpoint(
            host = host,
            port = port,
            username = username,
            domain = domain
        )
    }

    private fun mapProtocolToken(token: String): SessionProtocolKind = when (token) {
        "rdp", "wts", "ts" -> SessionProtocolKind.RDP
        "ssh" -> SessionProtocolKind.SSH
        "telnet" -> SessionProtocolKind.TELNET
        "serial" -> SessionProtocolKind.SERIAL
        "k8s", "kubectl" -> SessionProtocolKind.KUBERNETES_POD
        "docker" -> SessionProtocolKind.DOCKER_CONTAINER
        "sftp" -> SessionProtocolKind.SFTP
        "scp" -> SessionProtocolKind.SCP
        "ftp" -> SessionProtocolKind.FTP
        "ftps" -> SessionProtocolKind.FTPS
        "vnc" -> SessionProtocolKind.VNC
        "ard", "screensharing" -> SessionProtocolKind.APPLE_SCREEN_SHARING
        "ping", "traceroute", "tracert", "mtr", "portscan", "scan", "wol", "wakeonlan" ->
            SessionProtocolKind.NET_UTILITY
        "proxmox", "pve" -> SessionProtocolKind.PROXMOX_VE
        "vsphere", "esxi" -> SessionProtocolKind.VMWARE_VSPHERE
        "hyperv" -> SessionProtocolKind.HYPER_V
        else -> SessionProtocolKind.WEB_PAGE
    }

    private fun mapProtocolSubtype(token: String): String = when (token) {
        "rdp" -> "RDP"
        "wts", "ts" -> "WINDOWS_TERMINAL_SERVICES"
        "ssh" -> "SSH"
        "telnet" -> "TELNET"
        "serial" -> "SERIAL"
        "k8s", "kubectl" -> "KUBERNETES_POD"
        "docker" -> "DOCKER_CONTAINER"
        "sftp" -> "SFTP"
        "scp" -> "SCP"
        "ftp" -> "FTP"
        "ftps" -> "FTPS"
        "vnc" -> "VNC"
        "ard", "screensharing" -> "APPLE_SCREEN_SHARING"
        "ping" -> "UTILITY_PING"
        "traceroute", "tracert", "mtr" -> "UTILITY_TRACEROUTE"
        "portscan", "scan" -> "UTILITY_PORT_SCANNER"
        "wol", "wakeonlan" -> "UTILITY_WAKE_ON_LAN"
        "proxmox", "pve" -> "PROXMOX_VE"
        "vsphere", "esxi" -> "VMWARE_VSPHERE"
        "hyperv" -> "HYPER_V"
        else -> "WEB_PAGE"
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
    var shiftHeld by remember(visible) { mutableStateOf(false) }
    var altHeld by remember(visible) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

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
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    LaunchedEffect(selectedIndex, filtered.size) {
        if (selectedIndex in filtered.indices) {
            listState.animateScrollToItem(selectedIndex)
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
                        shiftHeld = event.isShiftPressed
                        altHeld = event.isAltPressed
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
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onDismiss
                                )
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
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .padding(vertical = 6.dp, horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(filtered, key = { _, item -> item.id }) { index, candidate ->
                            val isSelected = index == selectedIndex
                            val rowInteraction = remember { MutableInteractionSource() }
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
                                    .clickable(
                                        interactionSource = rowInteraction,
                                        indication = null
                                    ) {
                                        val placement = when {
                                            altHeld -> QuickLaunchPlacement.DETACHED_NEW_WINDOW
                                            shiftHeld -> QuickLaunchPlacement.SPLIT_RIGHT_PANE
                                            else -> QuickLaunchPlacement.ACTIVE_PANE_TAB
                                        }
                                        onLaunchCandidate(candidate, placement)
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
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        onLaunchCandidate(candidate, QuickLaunchPlacement.SPLIT_RIGHT_PANE)
                                        onDismiss()
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.VerticalSplit,
                                        contentDescription = "Split Right",
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onLaunchCandidate(candidate, QuickLaunchPlacement.DETACHED_NEW_WINDOW)
                                        onDismiss()
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription = "Detach Window",
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
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
