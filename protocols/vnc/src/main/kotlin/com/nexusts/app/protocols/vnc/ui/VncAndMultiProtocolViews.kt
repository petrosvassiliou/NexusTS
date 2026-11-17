package com.nexusts.app.protocols.vnc.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.KeyEvent as AndroidKeyEvent
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nexusts.app.core.ui.components.NexusActionChip
import com.nexusts.app.core.ui.components.NexusChoiceChip
import com.nexusts.app.core.ui.input.nexusTrackpadCanvasGesture
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.protocols.vnc.bridge.VncNativeBridge
import com.nexusts.app.protocols.vnc.bridge.VncRfbSessionClient
import com.nexusts.app.protocols.vnc.cloud.CloudManagedResource
import com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient
import com.nexusts.app.protocols.vnc.cloud.HyperVPortStatus
import com.nexusts.app.protocols.vnc.cloud.VmPowerAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1. UltraVNC / TightVNC / RealVNC / Apple Screen Sharing (ARD) Interactive Session View
 *    with async RFB 3.8 + ARD Type 30 DH-AES rendering, single-allocation RGBA frame buffer,
 *    coordinate-mapped pointer dispatch, `viewOnly` & `sharedSession` enforcement,
 *    special keys (`Ctrl+Alt+Del`, `Esc`, `Tab`), and trackpad pinch-zoom.
 */
@Composable
fun VncSessionCanvasView(
    host: String,
    isAppleScreenSharing: Boolean,
    settings: ProtocolSpecificSettings.Vnc,
    externalZoomFactor: Float = 1.0f,
    onZoomFactorChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    secret: ResolvedSessionSecret = ResolvedSessionSecret(),
    adHocPort: Int? = null,
    retainedClient: VncRfbSessionClient? = null
) {
    val client = remember(retainedClient) { retainedClient ?: VncRfbSessionClient() }
    val sessionState by client.state.collectAsState()
    val frameBitmap by client.frameBitmap.collectAsState()
    val frameRevision by client.frameRevision.collectAsState()

    val effectivePort = adHocPort ?: settings.port
    val effectiveArd = isAppleScreenSharing || settings.appleArdDiffieHellmanAuth

    var zoom by remember(externalZoomFactor) { mutableFloatStateOf(externalZoomFactor) }
    var viewportSize by remember { mutableStateOf(IntSize(1, 1)) }
    var reconnectNonce by remember { mutableIntStateOf(0) }
    var lastAppliedNonce by remember { mutableIntStateOf(0) }
    var showInputBar by remember { mutableStateOf(false) }
    var rightClickToggle by remember { mutableStateOf(false) }
    var textToType by remember { mutableStateOf("") }
    var vncUsername by remember(secret.username) { mutableStateOf(secret.username) }
    var vncPassword by remember(secret.password) { mutableStateOf(secret.password) }

    val focusRequester = remember { FocusRequester() }
    val preferZrle = remember(effectiveArd, settings.preferredEncodings) {
        effectiveArd || settings.preferredEncodings.firstOrNull()
            ?.contains("ZRLE", ignoreCase = true) != false
    }
    val encodingLabel = if (effectiveArd) {
        "ARD DH-AES • ZRLE"
    } else {
        "${settings.preferredEncodings.firstOrNull() ?: "ZRLE"} • ${sessionState.negotiatedProtocol}"
    }

    val latestZoom by rememberUpdatedState(zoom)
    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestSessionState by rememberUpdatedState(sessionState)
    val latestRightClick by rememberUpdatedState(rightClickToggle)
    val latestViewOnly by rememberUpdatedState(settings.viewOnly)

    DisposableEffect(client, retainedClient) {
        onDispose {
            if (retainedClient == null) {
                client.close()
            }
        }
    }

    LaunchedEffect(
        client,
        host,
        effectivePort,
        reconnectNonce,
        preferZrle,
        settings.sharedSession,
        settings.viewOnly,
        effectiveArd
    ) {
        val force = reconnectNonce != lastAppliedNonce
        lastAppliedNonce = reconnectNonce
        client.connect(
            host = host,
            port = effectivePort,
            password = vncPassword,
            preferZrle = preferZrle,
            username = vncUsername,
            sharedSession = settings.sharedSession,
            viewOnly = settings.viewOnly,
            isAppleScreenSharing = effectiveArd,
            forceReconnect = force
        )
    }

    LaunchedEffect(sessionState.authRequired) {
        if (sessionState.authRequired) {
            showInputBar = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportSize = it }
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEv ->
                    if (latestViewOnly) return@onKeyEvent false
                    val native = keyEv.nativeKeyEvent
                    if (native.action != AndroidKeyEvent.ACTION_DOWN && native.action != AndroidKeyEvent.ACTION_UP) {
                        return@onKeyEvent false
                    }
                    val keysym = VncNativeBridge.mapAndroidKeyCodeToX11Keysym(native.keyCode, native.unicodeChar)
                        ?: return@onKeyEvent false
                    client.sendKeyEvent(down = native.action == AndroidKeyEvent.ACTION_DOWN, keysym = keysym)
                    true
                }
                .nexusTrackpadCanvasGesture { _, _, zoomChange ->
                    val nextZoom = (zoom * zoomChange).coerceIn(0.6f, 3.0f)
                    zoom = nextZoom
                    onZoomFactorChanged(nextZoom)
                }
                .pointerInput(client) {
                    awaitPointerEventScope {
                        var activeMask = 0
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size != 1) continue
                            val change = ev.changes.first()
                            if (latestViewOnly) continue
                            val (rx, ry) = mapViewportToVncCoordinates(
                                rawPos = change.position,
                                viewportSize = latestViewportSize,
                                fbWidth = latestSessionState.framebufferWidth,
                                fbHeight = latestSessionState.framebufferHeight,
                                zoom = latestZoom
                            )

                            when (ev.type) {
                                PointerEventType.Press -> {
                                    if (!showInputBar) {
                                        runCatching { focusRequester.requestFocus() }
                                    }
                                    activeMask = when {
                                        latestRightClick || ev.buttons.isSecondaryPressed -> 4
                                        ev.buttons.isTertiaryPressed -> 2
                                        else -> 1
                                    }
                                    client.sendPointerEvent(activeMask, rx, ry)
                                }

                                PointerEventType.Move -> {
                                    client.sendPointerEvent(activeMask, rx, ry)
                                }

                                PointerEventType.Release -> {
                                    activeMask = 0
                                    client.sendPointerEvent(0, rx, ry)
                                }

                                PointerEventType.Scroll -> {
                                    val scrollY = change.scrollDelta.y
                                    if (abs(scrollY) > 0.01f) {
                                        val wheelBtn = if (scrollY < 0f) 8 else 16
                                        client.sendPointerEvent(wheelBtn, rx, ry)
                                        client.sendPointerEvent(0, rx, ry)
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    }
            ) {
                // Read frameRevision so Compose redraws when the single reusable Bitmap mutates in place
                val observedRevision = frameRevision
                if (observedRevision < 0L) return@Canvas

                val desktopRect = computeFittedVncDesktopRect(
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    fbWidth = sessionState.framebufferWidth,
                    fbHeight = sessionState.framebufferHeight
                )
                val bmp = frameBitmap
                if (bmp != null) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(desktopRect.left.roundToInt(), desktopRect.top.roundToInt()),
                        dstSize = IntSize(
                            desktopRect.width.roundToInt().coerceAtLeast(1),
                            desktopRect.height.roundToInt().coerceAtLeast(1)
                        )
                    )
                } else {
                    drawRect(
                        color = Color(0xFF121828),
                        topLeft = desktopRect.topLeft,
                        size = desktopRect.size
                    )
                    drawRect(
                        color = Color(0xFF1F2B46),
                        topLeft = desktopRect.topLeft,
                        size = Size(desktopRect.width, 34.dp.toPx().coerceAtMost(desktopRect.height))
                    )
                }
            }

            if (!sessionState.connected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (sessionState.connecting) "Connecting RFB 3.8 Session…" else sessionState.statusMessage,
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!sessionState.connecting) {
                            FilledTonalButton(onClick = { reconnectNonce++ }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reconnect")
                            }
                        }
                    }
                }
            }

            // Top HUD Overlay
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(99.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = when {
                                    sessionState.connected -> NexusTheme.extendedColors.statusConnected
                                    sessionState.connecting -> Color(0xFFFFB86B)
                                    else -> NexusTheme.extendedColors.statusDisconnected
                                },
                                shape = CircleShape
                            )
                    )
                    Icon(
                        imageVector = Icons.Outlined.SmartDisplay,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = NexusTheme.extendedColors.vncAccent
                    )
                    val roTag = if (settings.viewOnly) " • VIEW ONLY" else ""
                    Text(
                        text = "$host:$effectivePort • $encodingLabel$roTag",
                        style = NexusTheme.monospace.keycapShortcut
                    )
                    if (!settings.viewOnly) {
                        NexusChoiceChip(
                            selected = rightClickToggle,
                            onClick = { rightClickToggle = !rightClickToggle },
                            label = "R-Click",
                            showCheckmarkWhenSelected = false
                        )
                        NexusChoiceChip(
                            selected = showInputBar,
                            onClick = { showInputBar = !showInputBar },
                            leadingIcon = Icons.Outlined.Keyboard,
                            label = "Keys",
                            showCheckmarkWhenSelected = false
                        )
                    }
                    IconButton(
                        onClick = { reconnectNonce++ },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reconnect", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (showInputBar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!settings.viewOnly) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            NexusActionChip(
                                onClick = { client.sendCtrlAltDel() },
                                label = "Ctrl+Alt+Del",
                                monospace = true
                            )
                            NexusActionChip(
                                onClick = { client.sendKeyTap(0xFF1B) },
                                label = "Esc",
                                monospace = true
                            )
                            NexusActionChip(
                                onClick = { client.sendKeyTap(0xFF09) },
                                label = "Tab",
                                monospace = true
                            )
                            NexusActionChip(
                                onClick = { client.sendKeyTap(0xFFEB) },
                                label = "Super/Win",
                                monospace = true
                            )
                            NexusActionChip(
                                onClick = { client.sendKeyTap(0xFF0D) },
                                label = "Enter",
                                monospace = true
                            )
                            NexusActionChip(
                                onClick = { client.sendKeyTap(0xFF08) },
                                label = "Bksp",
                                monospace = true
                            )
                        }
                    }

                    if (sessionState.authRequired) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (effectiveArd) {
                                OutlinedTextField(
                                    value = vncUsername,
                                    onValueChange = { vncUsername = it },
                                    placeholder = { Text("macOS username…", style = MaterialTheme.typography.bodySmall) },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.8f)
                                )
                            }
                            OutlinedTextField(
                                value = vncPassword,
                                onValueChange = { vncPassword = it },
                                placeholder = { Text("VNC / ARD password…", style = MaterialTheme.typography.bodySmall) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(onClick = { reconnectNonce++ }) {
                                Text("Unlock")
                            }
                        }
                    }

                    if (!settings.viewOnly) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = textToType,
                                onValueChange = { textToType = it },
                                placeholder = { Text("Type text to remote VNC desktop…", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (textToType.isNotEmpty()) {
                                        client.sendText(textToType)
                                        textToType = ""
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Send")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun computeFittedVncDesktopRect(
    canvasWidth: Float,
    canvasHeight: Float,
    fbWidth: Int,
    fbHeight: Int
): Rect {
    val safeFbW = fbWidth.coerceAtLeast(1).toFloat()
    val safeFbH = fbHeight.coerceAtLeast(1).toFloat()
    val pad = 16f
    val availW = (canvasWidth - pad * 2f).coerceAtLeast(64f)
    val availH = (canvasHeight - pad * 2f).coerceAtLeast(64f)
    val scale = minOf(availW / safeFbW, availH / safeFbH).coerceAtLeast(0.01f)
    val dstW = safeFbW * scale
    val dstH = safeFbH * scale
    val left = (canvasWidth - dstW) / 2f
    val top = (canvasHeight - dstH) / 2f
    return Rect(left, top, left + dstW, top + dstH)
}

private fun mapViewportToVncCoordinates(
    rawPos: Offset,
    viewportSize: IntSize,
    fbWidth: Int,
    fbHeight: Int,
    zoom: Float
): Pair<Int, Int> {
    val vw = viewportSize.width.coerceAtLeast(1).toFloat()
    val vh = viewportSize.height.coerceAtLeast(1).toFloat()
    val safeZoom = zoom.coerceAtLeast(0.1f)
    val cx = vw / 2f
    val cy = vh / 2f
    val unzoomedX = cx + (rawPos.x - cx) / safeZoom
    val unzoomedY = cy + (rawPos.y - cy) / safeZoom

    val rect = computeFittedVncDesktopRect(vw, vh, fbWidth, fbHeight)
    val normX = ((unzoomedX - rect.left) / rect.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val normY = ((unzoomedY - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val rx = (normX * (fbWidth - 1).coerceAtLeast(0)).roundToInt()
    val ry = (normY * (fbHeight - 1).coerceAtLeast(0)).roundToInt()
    return rx to ry
}

private fun escJs(raw: String): String = buildString(raw.length + 8) {
    for (ch in raw) {
        when (ch) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(ch)
        }
    }
}

internal fun normalizeWebSessionUrl(
    configuredUrl: String,
    adHocHost: String? = null,
    adHocPort: Int? = null
): String {
    val rawCandidate = if (!adHocHost.isNullOrBlank()) {
        val h = adHocHost.trim()
        if (adHocPort != null && !h.contains("://") && !h.substringAfterLast(']').contains(':')) {
            val scheme = if (adHocPort == 80) "http://" else "https://"
            "$scheme$h:$adHocPort"
        } else {
            h
        }
    } else {
        configuredUrl.trim()
    }
    if (rawCandidate.isBlank()) return ""
    return when {
        rawCandidate.startsWith("https://", ignoreCase = true) ||
            rawCandidate.startsWith("http://", ignoreCase = true) ||
            rawCandidate.startsWith("about:", ignoreCase = true) -> rawCandidate
        else -> "https://$rawCandidate"
    }
}

internal fun verifyPinnedCertificateSha256(
    cert: X509Certificate?,
    pinnedSha256Raw: String
): Boolean {
    val cleanedPin = pinnedSha256Raw
        .trim()
        .removePrefix("sha256/")
        .removePrefix("SHA256:")
        .removePrefix("sha256:")
        .replace(":", "")
        .replace("-", "")
        .replace(" ", "")
    if (cleanedPin.isEmpty()) return true
    if (cert == null) return false

    val sha256 = MessageDigest.getInstance("SHA-256")
    val leafDerDigest = sha256.digest(cert.encoded)
    val leafHex = bytesToLowerHex(leafDerDigest)
    val leafBase64 = Base64.getEncoder().encodeToString(leafDerDigest)

    val spkiEncoded = runCatching { cert.publicKey?.encoded }.getOrNull()
    val spkiDigest = spkiEncoded?.let { MessageDigest.getInstance("SHA-256").digest(it) }
    val spkiHex = spkiDigest?.let { bytesToLowerHex(it) }.orEmpty()
    val spkiBase64 = spkiDigest?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()

    return leafHex.equals(cleanedPin, ignoreCase = true) ||
        spkiHex.equals(cleanedPin, ignoreCase = true) ||
        leafBase64 == cleanedPin ||
        spkiBase64 == cleanedPin
}

private fun bytesToLowerHex(bytes: ByteArray): String {
    val hexChars = "0123456789abcdef"
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        out[i * 2] = hexChars[v ushr 4]
        out[i * 2 + 1] = hexChars[v and 0x0F]
    }
    return String(out)
}

/**
 * 2. Isolated Web Page Connection View (HTTP/HTTPS) with:
 *    - `adHocHost`/`adHocPort` resolution & automatic `https://` prefixing on bare hostnames
 *    - Automated CSS-selector credential form-filling & deferred `pendingPassword` clearing
 *    - Single-attempt HTTP Basic Auth injection (`injectHttpBasicAuth`)
 *    - Strict leaf DER / SPKI SHA-256 X.509 certificate pinning (`pinnedCertSha256`)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IsolatedWebSessionView(
    settings: ProtocolSpecificSettings.WebPage,
    secret: ResolvedSessionSecret,
    autoFillActionLabel: String,
    modifier: Modifier = Modifier,
    adHocHost: String? = null,
    adHocPort: Int? = null
) {
    val effectiveUrl = remember(settings.url, adHocHost, adHocPort) {
        normalizeWebSessionUrl(settings.url, adHocHost, adHocPort)
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var tlsPinErrorBanner by remember(effectiveUrl, settings.pinnedCertSha256) { mutableStateOf("") }
    var pendingPassword by remember(secret.password, effectiveUrl) {
        mutableStateOf<String?>(secret.password.takeIf { it.isNotEmpty() })
    }

    val latestSettings by rememberUpdatedState(settings)
    val latestSecret by rememberUpdatedState(secret)
    val latestEffectiveUrl by rememberUpdatedState(effectiveUrl)
    val attemptedHttpAuthScopes = remember(effectiveUrl, secret.username, secret.password) {
        mutableSetOf<Pair<String, String>>()
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingPassword = null
            webViewRef?.apply {
                stopLoading()
                webChromeClient = null
                destroy()
            }
            webViewRef = null
        }
    }

    val injectAutoFillJs: (WebView, Boolean) -> Unit = { webView, manualClick ->
        val cfg = latestSettings
        val cred = latestSecret
        val activePassword = if (manualClick) {
            cred.password.also { if (it.isNotEmpty()) pendingPassword = it }
        } else {
            pendingPassword.orEmpty()
        }

        if ((cfg.autoFillEnabled || manualClick) && (cred.username.isNotBlank() || activePassword.isNotBlank())) {
            val escapedUser = escJs(cred.username)
            val escapedPass = escJs(activePassword)
            val userSel = escJs(cfg.usernameCssSelector)
            val passSel = escJs(cfg.passwordCssSelector)
            val submitSel = escJs(cfg.submitCssSelector.trim())
            val script = """
                (function() {
                  const u = '$userSel' ? document.querySelector('$userSel') : null;
                  const p = '$passSel' ? document.querySelector('$passSel') : null;
                  let filledPassword = false;
                  if (u && '$escapedUser'.length > 0) {
                    u.value = '$escapedUser';
                    u.dispatchEvent(new Event('input', { bubbles: true }));
                    u.dispatchEvent(new Event('change', { bubbles: true }));
                  }
                  if (p && '$escapedPass'.length > 0) {
                    p.value = '$escapedPass';
                    p.dispatchEvent(new Event('input', { bubbles: true }));
                    p.dispatchEvent(new Event('change', { bubbles: true }));
                    filledPassword = true;
                  }
                  if ('$submitSel' && (u || filledPassword)) {
                    const s = document.querySelector('$submitSel');
                    if (s && ($manualClick || !window.__nexusAutoSubmitted)) {
                      window.__nexusAutoSubmitted = true;
                      s.click();
                    }
                  }
                  return filledPassword ? 'FILLED_PASSWORD' : 'NO_PASSWORD_INPUT';
                })();
            """.trimIndent()
            webView.evaluateJavascript(script) { rawResult ->
                val unquoted = rawResult?.trim()?.trim('"').orEmpty()
                if (unquoted == "FILLED_PASSWORD") {
                    pendingPassword = null
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (tlsPinErrorBanner.isNotBlank()) {
                                NexusTheme.extendedColors.statusDisconnected
                            } else {
                                NexusTheme.extendedColors.webAccent
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = effectiveUrl.ifBlank { settings.url },
                            style = NexusTheme.monospace.keycapShortcut,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NexusActionChip(
                            onClick = { webViewRef?.let { injectAutoFillJs(it, true) } },
                            label = autoFillActionLabel
                        )
                        IconButton(
                            onClick = {
                                attemptedHttpAuthScopes.clear()
                                tlsPinErrorBanner = ""
                                pendingPassword = latestSecret.password.takeIf { it.isNotEmpty() }
                                webViewRef?.let { wv ->
                                    if (latestEffectiveUrl.isNotBlank() && wv.url == "about:blank") {
                                        wv.loadUrl(latestEffectiveUrl)
                                    } else {
                                        wv.reload()
                                    }
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Reload", modifier = Modifier.size(15.dp))
                        }
                    }
                }
                if (tlsPinErrorBanner.isNotBlank()) {
                    Text(
                        text = tlsPinErrorBanner,
                        style = NexusTheme.monospace.keycapShortcut,
                        color = Color(0xFFFF6E6E),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                    )
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = true
                    this.settings.allowFileAccess = false
                    this.settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?
                        ) {
                            val cfg = latestSettings
                            val cred = latestSecret
                            val scopeKey = (host.orEmpty()) to (realm.orEmpty())
                            if (cfg.injectHttpBasicAuth &&
                                (cred.username.isNotBlank() || cred.password.isNotBlank()) &&
                                attemptedHttpAuthScopes.add(scopeKey)
                            ) {
                                handler?.proceed(cred.username, cred.password)
                            } else {
                                handler?.cancel()
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val cfg = latestSettings
                            if (cfg.pinnedCertSha256.isNotBlank()) {
                                val leafCert = error?.certificate?.x509Certificate
                                if (verifyPinnedCertificateSha256(leafCert, cfg.pinnedCertSha256)) {
                                    tlsPinErrorBanner = ""
                                    handler?.proceed()
                                } else {
                                    tlsPinErrorBanner = "TLS Certificate Pin Mismatch — connection blocked"
                                    handler?.cancel()
                                }
                                return
                            }
                            if (cfg.ignoreSelfSignedInternalCert) {
                                handler?.proceed()
                            } else {
                                handler?.cancel()
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (view == null || url == "about:blank") return
                            val cfg = latestSettings
                            if (cfg.pinnedCertSha256.isNotBlank() && url.orEmpty().startsWith("https://", ignoreCase = true)) {
                                val activeCert = view.certificate?.x509Certificate
                                if (!verifyPinnedCertificateSha256(activeCert, cfg.pinnedCertSha256)) {
                                    tlsPinErrorBanner = "TLS Certificate Pin Mismatch — connection blocked"
                                    view.stopLoading()
                                    view.loadUrl("about:blank")
                                    return
                                }
                                tlsPinErrorBanner = ""
                            }
                            if (cfg.autoFillEnabled) {
                                injectAutoFillJs(view, false)
                            }
                        }
                    }
                    tag = effectiveUrl
                    if (effectiveUrl.isNotBlank()) {
                        loadUrl(effectiveUrl)
                    }
                    webViewRef = this
                }
            },
            update = { webView ->
                if (webView.tag != effectiveUrl && effectiveUrl.isNotBlank()) {
                    webView.tag = effectiveUrl
                    attemptedHttpAuthScopes.clear()
                    tlsPinErrorBanner = ""
                    pendingPassword = latestSecret.password.takeIf { it.isNotEmpty() }
                    webView.loadUrl(effectiveUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 3. Cloud & Virtualization Hypervisor Management View supporting:
 *    VMware vSphere/ESXi, Proxmox VE, Microsoft Hyper-V, AWS EC2 (SigV4), and Microsoft Azure ARM.
 */
@Composable
fun CloudHypervisorConsoleView(
    protocol: VaultProtocolType,
    endpointHost: String,
    settings: ProtocolSpecificSettings.CloudVirtualization,
    openConsoleCallback: (vmName: String, vmHost: String, vmProto: VaultProtocolType) -> Unit,
    modifier: Modifier = Modifier,
    secret: ResolvedSessionSecret = ResolvedSessionSecret(username = ""),
    openHyperVVmConsoleCallback: ((vmId: String, vmName: String, enhancedSession: Boolean) -> Unit)? = null,
    openHostRdpCallback: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val isHyperV = protocol == VaultProtocolType.HYPER_V
    val sanitizedEndpoint = remember(protocol, endpointHost, settings.apiEndpointUrl) {
        if (isHyperV) {
            CloudVirtualizationHubClient.sanitizedHyperVEndpoint(settings.apiEndpointUrl, endpointHost)
        } else {
            settings.apiEndpointUrl
        }
    }
    var statusBanner by remember { mutableStateOf("") }
    var isErrorBanner by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var hyperVEnhancedSession by remember { mutableStateOf(false) }
    var directVmGuidInput by remember { mutableStateOf("") }

    val instances = remember(protocol, endpointHost) {
        mutableStateListOf<CloudManagedResource>()
    }
    val hyperVPorts = remember(protocol, endpointHost) {
        mutableStateListOf<HyperVPortStatus>()
    }

    val refreshLiveInventory: () -> Unit = {
        scope.launch {
            isSyncing = true
            isErrorBanner = false
            if (isHyperV && endpointHost.isNotBlank()) {
                val probed = CloudVirtualizationHubClient.diagnoseHyperVPorts(endpointHost)
                hyperVPorts.clear()
                hyperVPorts.addAll(probed)
            }
            val effectiveSettings = if (isHyperV && sanitizedEndpoint != settings.apiEndpointUrl) {
                settings.copy(apiEndpointUrl = sanitizedEndpoint)
            } else {
                settings
            }
            CloudVirtualizationHubClient
                .listInventory(protocol, endpointHost, effectiveSettings, secret)
                .onSuccess { liveList ->
                    instances.clear()
                    instances.addAll(liveList)
                    val sourceLabel = endpointHost.ifBlank { sanitizedEndpoint.ifBlank { "hypervisor" } }
                    statusBanner = "${liveList.size} live resources synced from $sourceLabel"
                    isErrorBanner = false
                }
                .onFailure { err ->
                    statusBanner = "Inventory sync failed: ${err.message ?: "endpoint unreachable"}"
                    isErrorBanner = true
                }
            isSyncing = false
        }
    }

    LaunchedEffect(protocol, endpointHost, sanitizedEndpoint, settings.subscriptionOrAccountId, secret.username, secret.password) {
        if (endpointHost.isNotBlank() || sanitizedEndpoint.isNotBlank() || settings.subscriptionOrAccountId.isNotBlank()) {
            refreshLiveInventory()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                Icon(
                    imageVector = Icons.Outlined.CloudQueue,
                    contentDescription = null,
                    tint = NexusTheme.extendedColors.cloudAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "${protocol.displayName} • ${endpointHost.ifBlank { sanitizedEndpoint }}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    val transportLabel = if (isHyperV) {
                        sanitizedEndpoint.ifBlank { "WinRM 5985/5986 • OpenSSH 22 • VMConnect 2179" }
                    } else {
                        "${settings.regionOrCluster.ifBlank { "default" }} • ${sanitizedEndpoint.ifBlank { endpointHost }}"
                    }
                    Text(
                        text = transportLabel + if (statusBanner.isNotBlank()) " • $statusBanner" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErrorBanner) Color(0xFFFFB86B) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isHyperV) {
                    NexusChoiceChip(
                        selected = hyperVEnhancedSession,
                        onClick = { hyperVEnhancedSession = !hyperVEnhancedSession },
                        label = "Enhanced Session",
                        showCheckmarkWhenSelected = true
                    )
                    if (openHostRdpCallback != null && endpointHost.isNotBlank()) {
                        NexusActionChip(
                            onClick = openHostRdpCallback,
                            leadingIcon = Icons.Outlined.SmartDisplay,
                            label = "Host RDP (3389)"
                        )
                    }
                }
                NexusActionChip(
                    onClick = refreshLiveInventory,
                    leadingIcon = Icons.Outlined.Refresh,
                    label = if (isSyncing) "Syncing…" else "Sync"
                )
            }
        }

        if (isHyperV) {
            if (hyperVPorts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    hyperVPorts.forEach { portInfo ->
                        val pillColor = if (portInfo.open) {
                            NexusTheme.extendedColors.statusConnected
                        } else {
                            NexusTheme.extendedColors.statusDisconnected
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = pillColor.copy(alpha = 0.14f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(pillColor, CircleShape)
                                )
                                Text(
                                    text = "${portInfo.label} :${portInfo.port}" +
                                        if (portInfo.open && portInfo.latencyMs > 0) " (${portInfo.latencyMs}ms)" else "",
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = pillColor
                                )
                            }
                        }
                    }
                }
            }

            if (openHyperVVmConsoleCallback != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = directVmGuidInput,
                        onValueChange = { directVmGuidInput = it },
                        label = { Text("Connect VM directly by Hyper-V VM ID (GUID) on TCP 2179") },
                        placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    val trimmedGuid = directVmGuidInput.trim().trim('{', '}')
                    FilledTonalButton(
                        enabled = trimmedGuid.isNotBlank() && endpointHost.isNotBlank(),
                        onClick = {
                            openHyperVVmConsoleCallback(
                                trimmedGuid,
                                "VM ${trimmedGuid.take(8)}",
                                hyperVEnhancedSession
                            )
                        }
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("VMConnect 2179")
                    }
                }
            }
        }

        HorizontalDivider()

        if (instances.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        isSyncing -> "Querying live ${protocol.displayName} inventory…"
                        isErrorBanner -> statusBanner
                        else -> "No virtual machines or active resources returned from ${endpointHost.ifBlank { sanitizedEndpoint.ifBlank { "configured endpoint" } }}."
                    },
                    style = NexusTheme.monospace.keycapShortcut,
                    color = if (isErrorBanner) Color(0xFFFFB86B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(instances, key = { it.id }) { vm ->
                    val isRunning = vm.powerState == "RUNNING" || vm.powerState == "ACTIVE"
                    val isSaved = vm.powerState == "SAVED" || vm.powerState == "PAUSED"
                    val invokePowerAction: (VmPowerAction) -> Unit = { desired ->
                        scope.launch {
                            val effectiveSettings = if (isHyperV && sanitizedEndpoint != settings.apiEndpointUrl) {
                                settings.copy(apiEndpointUrl = sanitizedEndpoint)
                            } else {
                                settings
                            }
                            CloudVirtualizationHubClient
                                .executePowerOrSessionAction(protocol, endpointHost, effectiveSettings, secret, vm, desired)
                                .onSuccess { msg ->
                                    statusBanner = msg
                                    isErrorBanner = false
                                    val idx = instances.indexOfFirst { it.id == vm.id }
                                    if (idx >= 0) {
                                        instances[idx] = instances[idx].copy(
                                            powerState = when {
                                                vm.isTerminalServicesSession && desired == VmPowerAction.STOP -> "LOGGED OFF"
                                                vm.isTerminalServicesSession -> "DISCONNECTED"
                                                desired == VmPowerAction.START || desired == VmPowerAction.REBOOT -> "RUNNING"
                                                desired == VmPowerAction.SAVE -> "SAVED"
                                                else -> "STOPPED"
                                            }
                                        )
                                    }
                                }
                                .onFailure { err ->
                                    statusBanner = "Action failed: ${err.message ?: "API error"}"
                                    isErrorBanner = true
                                }
                        }
                    }

                    Surface(
                        shape = MaterialTheme.shapes.medium,
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
                                Text(text = vm.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${vm.id}  •  ${vm.nodeOrZone}  •  ${vm.cpuRamSummary}  •  ${vm.addressOrSessionHost}",
                                    style = NexusTheme.monospace.keycapShortcut,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = vm.powerState,
                                style = NexusTheme.monospace.keycapShortcut,
                                color = when {
                                    isRunning -> NexusTheme.extendedColors.statusConnected
                                    isSaved -> Color(0xFFFFB86B)
                                    else -> NexusTheme.extendedColors.statusDisconnected
                                },
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        invokePowerAction(if (isRunning) VmPowerAction.STOP else VmPowerAction.START)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PowerSettingsNew,
                                        contentDescription = if (isRunning) "Stop / Turn Off" else "Start",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (isHyperV && isRunning) {
                                    NexusActionChip(
                                        onClick = { invokePowerAction(VmPowerAction.SAVE) },
                                        label = "Save"
                                    )
                                    NexusActionChip(
                                        onClick = { invokePowerAction(VmPowerAction.REBOOT) },
                                        leadingIcon = Icons.Outlined.Refresh,
                                        label = "Restart"
                                    )
                                }
                                FilledTonalButton(
                                    onClick = {
                                        if (isHyperV && openHyperVVmConsoleCallback != null) {
                                            openHyperVVmConsoleCallback(vm.id, vm.name, hyperVEnhancedSession)
                                        } else {
                                            openConsoleCallback(vm.name, vm.addressOrSessionHost, vm.preferredConsoleProtocol)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isHyperV && openHyperVVmConsoleCallback != null) "VMConnect" else vm.preferredConsoleProtocol.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class NetUtilityMode(val badge: String) {
    PING("PING"),
    TRACEROUTE("TRACEROUTE"),
    PORT_SCAN("PORT SCAN"),
    WAKE_ON_LAN("WAKE-ON-LAN"),
    DNS_LOOKUP("DNS LOOKUP"),
    SUBNET_CALC("SUBNET CALC");

    companion object {
        fun fromProtocol(protocol: VaultProtocolType): NetUtilityMode = when {
            protocol == VaultProtocolType.UTILITY_TRACEROUTE -> TRACEROUTE
            protocol == VaultProtocolType.UTILITY_PORT_SCANNER -> PORT_SCAN
            protocol == VaultProtocolType.UTILITY_WAKE_ON_LAN -> WAKE_ON_LAN
            protocol.name.contains("DNS", ignoreCase = true) -> DNS_LOOKUP
            protocol.name.contains("SUBNET", ignoreCase = true) -> SUBNET_CALC
            else -> PING
        }
    }
}

/**
 * 4. Integrated Network Utilities Suite: Ping, Multi-Hop Traceroute, Port Scanner (with Banner Grabbing),
 *    IEEE 802 Wake-on-LAN (WoL + SecureOn), DNS Lookup, and IPv4 Subnet Calculator.
 */
@Composable
fun NetworkUtilitiesView(
    initialTool: VaultProtocolType,
    initialHost: String,
    settings: ProtocolSpecificSettings.NetUtility,
    executeLabel: String,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var activeMode by remember(initialTool) { mutableStateOf(NetUtilityMode.fromProtocol(initialTool)) }
    var targetHost by remember { mutableStateOf(initialHost) }
    var macInput by remember { mutableStateOf(settings.wolMacAddress.ifBlank { "00:11:22:33:44:55" }) }
    var subnetInput by remember {
        mutableStateOf(
            if (initialHost.contains('/')) initialHost else "${initialHost.ifBlank { "192.168.1.0" }}/24"
        )
    }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val outputLines = remember { mutableStateListOf<String>() }

    DisposableEffect(Unit) {
        onDispose { activeJob?.cancel() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NetUtilityMode.entries.forEach { mode ->
                NexusChoiceChip(
                    selected = activeMode == mode,
                    onClick = { activeMode = mode },
                    label = mode.badge,
                    monospace = true,
                    showCheckmarkWhenSelected = false
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (activeMode == NetUtilityMode.WAKE_ON_LAN) {
                    Icons.Outlined.PowerSettingsNew
                } else {
                    Icons.Outlined.NetworkCheck
                },
                contentDescription = null,
                tint = NexusTheme.extendedColors.sshAccent
            )
            OutlinedTextField(
                value = when (activeMode) {
                    NetUtilityMode.WAKE_ON_LAN -> macInput
                    NetUtilityMode.SUBNET_CALC -> subnetInput
                    else -> targetHost
                },
                onValueChange = { next ->
                    when (activeMode) {
                        NetUtilityMode.WAKE_ON_LAN -> macInput = next
                        NetUtilityMode.SUBNET_CALC -> subnetInput = next
                        else -> targetHost = next
                    }
                },
                placeholder = {
                    Text(
                        when (activeMode) {
                            NetUtilityMode.WAKE_ON_LAN -> "00:11:22:33:44:55"
                            NetUtilityMode.SUBNET_CALC -> "192.168.1.0/24 or 10.0.0.1/255.255.252.0"
                            else -> "hostname or IP address"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
            if (isRunning) {
                FilledTonalButton(
                    onClick = {
                        activeJob?.cancel()
                    }
                ) {
                    Text("Cancel")
                }
            }
            Button(
                enabled = !isRunning,
                onClick = {
                    activeJob?.cancel()
                    outputLines.clear()
                    isRunning = true
                    activeJob = scope.launch {
                        try {
                            when (activeMode) {
                                NetUtilityMode.WAKE_ON_LAN -> {
                                    runCatching {
                                        val bytes = VncNativeBridge.wakeOnLan(
                                            macAddress = macInput,
                                            broadcastAddress = settings.wolBroadcastAddress,
                                            port = settings.wolUdpPort,
                                            secureOnHex = settings.wolSecureOnHex
                                        )
                                        val secTag = if (settings.wolSecureOnHex.isNotBlank()) " + SecureOn" else ""
                                        outputLines += "[WoL] Sent $bytes-byte IEEE 802 Magic Packet$secTag to $macInput via ${settings.wolBroadcastAddress}:${settings.wolUdpPort}"
                                    }.onFailure { err ->
                                        if (err is CancellationException) throw err
                                        outputLines += "[WoL Error] ${err.message}"
                                    }
                                }

                                NetUtilityMode.TRACEROUTE -> {
                                    val maxHops = settings.tracerouteMaxHops.coerceIn(1, 30)
                                    outputLines += "[Traceroute] Tracing route to $targetHost (max $maxHops hops, ICMP/UDP/TCP TTL probe)..."
                                    runCatching {
                                        val hops = VncNativeBridge.runTraceroute(
                                            host = targetHost,
                                            probePort = 443,
                                            maxHops = maxHops
                                        )
                                        hops.forEach { hop ->
                                            val rttFormatted = String.format(Locale.US, "%.2f ms", hop.rttMs)
                                            val destTag = if (hop.reachedDestination) " [DESTINATION]" else ""
                                            outputLines += String.format(
                                                Locale.US,
                                                "  %2d  %-26s  %s%s",
                                                hop.hopNumber,
                                                hop.routerAddress,
                                                if (hop.timedOut) "* * *" else rttFormatted,
                                                destTag
                                            )
                                        }
                                    }.onFailure { err ->
                                        if (err is CancellationException) throw err
                                        outputLines += "[Traceroute Error] ${err.message}"
                                    }
                                }

                                NetUtilityMode.PORT_SCAN -> {
                                    outputLines += "[PortScan] Scanning $targetHost (${settings.portScanRangeStart}..${settings.portScanRangeEnd}, concurrency=${settings.portScanConcurrency})..."
                                    runCatching {
                                        val openPorts = VncNativeBridge.scanPortRange(
                                            host = targetHost,
                                            startPort = settings.portScanRangeStart,
                                            endPort = settings.portScanRangeEnd,
                                            concurrency = settings.portScanConcurrency
                                        )
                                        if (openPorts.isEmpty()) {
                                            outputLines += "[PortScan] Completed: 0 open TCP ports in range ${settings.portScanRangeStart}..${settings.portScanRangeEnd}."
                                        } else {
                                            openPorts.forEach { hit ->
                                                val bannerSuffix = hit.service_banner?.let { "  banner=\"$it\"" }.orEmpty()
                                                outputLines += "  OPEN  tcp/${hit.port}  (${hit.guessed_service})  ${hit.connect_latency_ms} ms$bannerSuffix"
                                            }
                                            outputLines += "[PortScan] Completed: ${openPorts.size} open port(s) discovered."
                                        }
                                    }.onFailure { err ->
                                        if (err is CancellationException) throw err
                                        outputLines += "[PortScan Error] ${err.message}"
                                    }
                                }

                                NetUtilityMode.DNS_LOOKUP -> {
                                    runCatching {
                                        outputLines.addAll(VncNativeBridge.resolveDnsRecords(targetHost))
                                    }.onFailure { err ->
                                        if (err is CancellationException) throw err
                                        outputLines += "[DNS Error] ${err.message}"
                                    }
                                }

                                NetUtilityMode.SUBNET_CALC -> {
                                    runCatching {
                                        outputLines.addAll(VncNativeBridge.calculateIpv4Subnet(subnetInput))
                                    }.onFailure { err ->
                                        if (err is CancellationException) throw err
                                        outputLines += "[Subnet Error] ${err.message}"
                                    }
                                }

                                NetUtilityMode.PING -> {
                                    outputLines += "[Ping] Probing $targetHost (${settings.pingCount} samples, interval=${settings.pingIntervalMs} ms)..."
                                    runCatching {
                                        val samples = VncNativeBridge.runTcpPing(
                                            host = targetHost,
                                            count = settings.pingCount,
                                            intervalMs = settings.pingIntervalMs.toLong()
                                        )
                                        samples.forEach { s ->
                                            outputLines += "  seq=${s.sequence}  host=${s.targetIp}  reachable=${s.reachable}  time=${s.latencyMs} ms"
                                        }
                                        val okCount = samples.count { it.reachable }
                                        outputLines += "[Ping] Summary: $okCount/${samples.size} received (${100 - (okCount * 100 / samples.size.coerceAtLeast(1))}% loss)"
                                    }.onFailure { err ->
                                        if (err is CancellationException) throw err
                                        outputLines += "[Ping Error] ${err.message}"
                                    }
                                }
                            }
                        } catch (_: CancellationException) {
                            outputLines += "[Cancelled] Operation stopped by user."
                        } finally {
                            isRunning = false
                        }
                    }
                }
            ) {
                Text(executeLabel)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            color = NexusTheme.extendedColors.terminalCanvasBackground
        ) {
            LazyColumn(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(outputLines) { line ->
                    Text(
                        text = line,
                        style = NexusTheme.monospace.logEntry,
                        color = Color(0xFFE2E6F3)
                    )
                }
            }
        }
    }
}
