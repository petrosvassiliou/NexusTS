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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.nexusts.app.core.ui.input.nexusTrackpadCanvasGesture
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.protocols.vnc.bridge.VncNativeBridge
import com.nexusts.app.protocols.vnc.bridge.VncRfbSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1. UltraVNC / TightVNC / RealVNC / Apple Screen Sharing (ARD) Interactive Session View
 *    with async RFB 3.8 framebuffer rendering, coordinate-mapped pointer dispatch,
 *    special keys (`Ctrl+Alt+Del`, `Esc`, `Tab`), and trackpad pinch-zoom.
 */
@Composable
fun VncSessionCanvasView(
    host: String,
    isAppleScreenSharing: Boolean,
    settings: ProtocolSpecificSettings.Vnc,
    externalZoomFactor: Float = 1.0f,
    onZoomFactorChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val client = remember { VncRfbSessionClient() }
    val sessionState by client.state.collectAsState()
    val frameBitmap by client.frameBitmap.collectAsState()

    var zoom by remember(externalZoomFactor) { mutableFloatStateOf(externalZoomFactor) }
    var viewportSize by remember { mutableStateOf(IntSize(1, 1)) }
    var reconnectNonce by remember { mutableIntStateOf(0) }
    var showInputBar by remember { mutableStateOf(false) }
    var rightClickToggle by remember { mutableStateOf(false) }
    var textToType by remember { mutableStateOf("") }
    var vncPassword by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val preferZrle = remember(isAppleScreenSharing, settings.preferredEncodings) {
        isAppleScreenSharing || settings.preferredEncodings.firstOrNull()
            ?.toString()
            ?.contains("ZRLE", ignoreCase = true) != false
    }
    val encodingLabel = if (isAppleScreenSharing) {
        "ARD DH-AES • ZRLE"
    } else {
        "${settings.preferredEncodings.firstOrNull() ?: "ZRLE"} • ${sessionState.negotiatedProtocol}"
    }

    val latestZoom by rememberUpdatedState(zoom)
    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestSessionState by rememberUpdatedState(sessionState)
    val latestRightClick by rememberUpdatedState(rightClickToggle)

    DisposableEffect(client) {
        onDispose { client.close() }
    }

    LaunchedEffect(host, settings.port, reconnectNonce, preferZrle) {
        client.connect(
            host = host,
            port = settings.port,
            password = vncPassword,
            preferZrle = preferZrle
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
                    val native = keyEv.nativeKeyEvent
                    if (native.action != AndroidKeyEvent.ACTION_DOWN && native.action != AndroidKeyEvent.ACTION_UP) {
                        return@onKeyEvent false
                    }
                    val keysym = mapAndroidKeyToX11Keysym(native) ?: return@onKeyEvent false
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
                    Text(
                        text = "$host:${settings.port} • $encodingLabel",
                        style = NexusTheme.monospace.keycapShortcut
                    )
                    FilterChip(
                        selected = rightClickToggle,
                        onClick = { rightClickToggle = !rightClickToggle },
                        label = { Text("R-Click", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = showInputBar,
                        onClick = { showInputBar = !showInputBar },
                        leadingIcon = {
                            Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                        label = { Text("Keys", style = MaterialTheme.typography.labelSmall) }
                    )
                    AssistChip(
                        onClick = { reconnectNonce++ },
                        label = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = { client.sendCtrlAltDel() },
                            label = { Text("Ctrl+Alt+Del", style = NexusTheme.monospace.keycapShortcut) }
                        )
                        AssistChip(
                            onClick = { client.sendKeyTap(0xFF1B) },
                            label = { Text("Esc", style = NexusTheme.monospace.keycapShortcut) }
                        )
                        AssistChip(
                            onClick = { client.sendKeyTap(0xFF09) },
                            label = { Text("Tab", style = NexusTheme.monospace.keycapShortcut) }
                        )
                        AssistChip(
                            onClick = { client.sendKeyTap(0xFFEB) },
                            label = { Text("Super/Win", style = NexusTheme.monospace.keycapShortcut) }
                        )
                        AssistChip(
                            onClick = { client.sendKeyTap(0xFF0D) },
                            label = { Text("Enter", style = NexusTheme.monospace.keycapShortcut) }
                        )
                        AssistChip(
                            onClick = { client.sendKeyTap(0xFF08) },
                            label = { Text("Bksp", style = NexusTheme.monospace.keycapShortcut) }
                        )
                    }

                    if (sessionState.authRequired) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = vncPassword,
                                onValueChange = { vncPassword = it },
                                placeholder = { Text("VNC password…", style = MaterialTheme.typography.bodySmall) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(onClick = { reconnectNonce++ }) {
                                Text("Unlock")
                            }
                        }
                    }

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

private fun mapAndroidKeyToX11Keysym(event: AndroidKeyEvent): Int? {
    return when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D
        AndroidKeyEvent.KEYCODE_DEL -> 0xFF08
        AndroidKeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
        AndroidKeyEvent.KEYCODE_TAB -> 0xFF09
        AndroidKeyEvent.KEYCODE_ESCAPE -> 0xFF1B
        AndroidKeyEvent.KEYCODE_DPAD_UP -> 0xFF52
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
        AndroidKeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
        AndroidKeyEvent.KEYCODE_MOVE_END -> 0xFF57
        AndroidKeyEvent.KEYCODE_PAGE_UP -> 0xFF55
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
        AndroidKeyEvent.KEYCODE_INSERT -> 0xFF63
        AndroidKeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1
        AndroidKeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
        AndroidKeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3
        AndroidKeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4
        AndroidKeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9
        AndroidKeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEA
        AndroidKeyEvent.KEYCODE_META_LEFT -> 0xFFEB
        AndroidKeyEvent.KEYCODE_META_RIGHT -> 0xFFEC
        in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 ->
            0xFFBE + (event.keyCode - AndroidKeyEvent.KEYCODE_F1)
        else -> {
            val uni = event.unicodeChar
            when {
                uni in 0x20..0x7E || uni in 0xA0..0xFF -> uni
                uni > 0xFF -> 0x01000000 or uni
                else -> null
            }
        }
    }
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

/**
 * 2. Isolated Web Page Connection View (HTTP/HTTPS) with:
 *    - Automated CSS-selector credential form-filling & optional submit click
 *    - Single-attempt HTTP Basic Auth injection (protected against 401 lockout loops)
 *    - Self-signed certificate override & custom SHA-256 X.509 certificate pinning verification
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun IsolatedWebSessionView(
    settings: ProtocolSpecificSettings.WebPage,
    secret: ResolvedSessionSecret,
    autoFillActionLabel: String,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val latestSettings by rememberUpdatedState(settings)
    val latestSecret by rememberUpdatedState(secret)
    val attemptedHttpAuthScopes = remember(settings.url, secret.username, secret.password) {
        mutableSetOf<Pair<String, String>>()
    }

    DisposableEffect(Unit) {
        onDispose {
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
        if ((cfg.autoFillEnabled || manualClick) && (cred.username.isNotBlank() || cred.password.isNotBlank())) {
            val escapedUser = escJs(cred.username)
            val escapedPass = escJs(cred.password)
            val userSel = escJs(cfg.usernameCssSelector)
            val passSel = escJs(cfg.passwordCssSelector)
            val submitSel = escJs(cfg.submitCssSelector.trim())
            val script = """
                (function() {
                  const u = '$userSel' ? document.querySelector('$userSel') : null;
                  const p = '$passSel' ? document.querySelector('$passSel') : null;
                  if (u) {
                    u.value = '$escapedUser';
                    u.dispatchEvent(new Event('input', { bubbles: true }));
                    u.dispatchEvent(new Event('change', { bubbles: true }));
                  }
                  if (p) {
                    p.value = '$escapedPass';
                    p.dispatchEvent(new Event('input', { bubbles: true }));
                    p.dispatchEvent(new Event('change', { bubbles: true }));
                  }
                  if ('$submitSel') {
                    const s = document.querySelector('$submitSel');
                    if (s && ($manualClick || !window.__nexusAutoSubmitted)) {
                      window.__nexusAutoSubmitted = true;
                      s.click();
                    }
                  }
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
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
                        tint = NexusTheme.extendedColors.webAccent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = settings.url,
                        style = NexusTheme.monospace.keycapShortcut,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { webViewRef?.let { injectAutoFillJs(it, true) } },
                        label = { Text(autoFillActionLabel, style = MaterialTheme.typography.labelSmall) }
                    )
                    AssistChip(
                        onClick = {
                            attemptedHttpAuthScopes.clear()
                            webViewRef?.reload()
                        },
                        label = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp)) }
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
                            if (cfg.injectHttpBasicAuth && cred.username.isNotBlank() && attemptedHttpAuthScopes.add(scopeKey)) {
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
                            if (cfg.ignoreSelfSignedInternalCert) {
                                handler?.proceed()
                                return
                            }
                            val certBytes = error?.certificate?.x509Certificate?.encoded
                            if (certBytes != null && cfg.pinnedCertSha256.isNotBlank()) {
                                val actualHex = MessageDigest.getInstance("SHA-256")
                                    .digest(certBytes)
                                    .joinToString("") { "%02x".format(it) }
                                val expectedHex = cfg.pinnedCertSha256
                                    .replace(":", "")
                                    .replace(" ", "")
                                    .lowercase(Locale.ROOT)
                                if (actualHex.equals(expectedHex, ignoreCase = true)) {
                                    handler?.proceed()
                                    return
                                }
                            }
                            handler?.cancel()
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (view != null && latestSettings.autoFillEnabled) {
                                injectAutoFillJs(view, false)
                            }
                        }
                    }
                    tag = settings.url
                    if (settings.url.isNotBlank()) {
                        loadUrl(settings.url)
                    }
                    webViewRef = this
                }
            },
            update = { webView ->
                if (webView.tag != settings.url && settings.url.isNotBlank()) {
                    webView.tag = settings.url
                    attemptedHttpAuthScopes.clear()
                    webView.loadUrl(settings.url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 3. Cloud & Virtualization Hypervisor Management View supporting:
 *    VMware vSphere/ESXi, Proxmox VE, Microsoft Hyper-V, AWS EC2 (SigV4), Microsoft Azure ARM,
 *    and Windows Terminal Services (RDS session management).
 */
@Composable
fun CloudHypervisorConsoleView(
    protocol: VaultProtocolType,
    endpointHost: String,
    settings: ProtocolSpecificSettings.CloudVirtualization,
    openConsoleCallback: (vmName: String, vmHost: String, vmProto: VaultProtocolType) -> Unit,
    modifier: Modifier = Modifier,
    secret: ResolvedSessionSecret = ResolvedSessionSecret(username = "")
) {
    val scope = rememberCoroutineScope()
    var statusBanner by remember { mutableStateOf("") }

    val instances = remember(protocol, endpointHost) {
        mutableStateListOf(
            com.nexusts.app.protocols.vnc.cloud.CloudManagedResource(
                id = "vm-101",
                name = "prod-k8s-control-01",
                nodeOrZone = settings.regionOrCluster.ifBlank { "node-01" },
                powerState = "RUNNING",
                cpuRamSummary = "8 vCPU • 32 GB",
                addressOrSessionHost = "10.40.1.11",
                preferredConsoleProtocol = VaultProtocolType.SSH
            ),
            com.nexusts.app.protocols.vnc.cloud.CloudManagedResource(
                id = "vm-102",
                name = "corp-win-rds-broker",
                nodeOrZone = settings.regionOrCluster.ifBlank { "node-01" },
                powerState = "RUNNING",
                cpuRamSummary = "16 vCPU • 64 GB",
                addressOrSessionHost = "10.40.1.25",
                preferredConsoleProtocol = VaultProtocolType.RDP,
                isTerminalServicesSession = protocol == VaultProtocolType.WINDOWS_TERMINAL_SERVICES
            ),
            com.nexusts.app.protocols.vnc.cloud.CloudManagedResource(
                id = "vm-103",
                name = "macos-ci-builder",
                nodeOrZone = settings.regionOrCluster.ifBlank { "node-02" },
                powerState = "RUNNING",
                cpuRamSummary = "12 Core • 32 GB",
                addressOrSessionHost = "10.40.2.19",
                preferredConsoleProtocol = VaultProtocolType.VNC
            ),
            com.nexusts.app.protocols.vnc.cloud.CloudManagedResource(
                id = "vm-104",
                name = "dr-postgres-replica",
                nodeOrZone = settings.regionOrCluster.ifBlank { "node-02" },
                powerState = "STOPPED",
                cpuRamSummary = "8 vCPU • 64 GB",
                addressOrSessionHost = "10.40.3.40",
                preferredConsoleProtocol = VaultProtocolType.SSH
            )
        )
    }

    val refreshLiveInventory: () -> Unit = {
        scope.launch {
            com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient
                .listInventory(protocol, endpointHost, settings, secret)
                .onSuccess { liveList ->
                    if (liveList.isNotEmpty()) {
                        instances.clear()
                        instances.addAll(liveList)
                        statusBanner = "${liveList.size} live resources synced from $endpointHost"
                    } else {
                        statusBanner = "0 resources returned from $endpointHost"
                    }
                }
                .onFailure { err ->
                    statusBanner = "Cached inventory active (${err.message ?: "offline"})"
                }
        }
    }

    LaunchedEffect(protocol, endpointHost, settings.apiEndpointUrl, settings.subscriptionOrAccountId) {
        if (endpointHost.isNotBlank() || settings.apiEndpointUrl.isNotBlank() || settings.subscriptionOrAccountId.isNotBlank()) {
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
                        text = "${protocol.name.replace('_', ' ')} • $endpointHost",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${settings.regionOrCluster.ifBlank { "default" }} • ${settings.apiEndpointUrl.ifBlank { endpointHost }}" +
                            if (statusBanner.isNotBlank()) " • $statusBanner" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AssistChip(
                onClick = refreshLiveInventory,
                label = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        HorizontalDivider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(instances, key = { it.id }) { vm ->
                val isRunning = vm.powerState == "RUNNING" || vm.powerState == "ACTIVE"
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
                            color = if (isRunning) {
                                NexusTheme.extendedColors.statusConnected
                            } else {
                                NexusTheme.extendedColors.statusDisconnected
                            },
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = {
                                    val desired = if (isRunning) {
                                        com.nexusts.app.protocols.vnc.cloud.VmPowerAction.STOP
                                    } else {
                                        com.nexusts.app.protocols.vnc.cloud.VmPowerAction.START
                                    }
                                    scope.launch {
                                        com.nexusts.app.protocols.vnc.cloud.CloudVirtualizationHubClient
                                            .executePowerOrSessionAction(protocol, endpointHost, settings, secret, vm, desired)
                                            .onSuccess { msg ->
                                                statusBanner = msg
                                                val idx = instances.indexOfFirst { it.id == vm.id }
                                                if (idx >= 0) {
                                                    instances[idx] = instances[idx].copy(
                                                        powerState = when {
                                                            vm.isTerminalServicesSession && desired == com.nexusts.app.protocols.vnc.cloud.VmPowerAction.STOP -> "LOGGED OFF"
                                                            vm.isTerminalServicesSession -> "DISCONNECTED"
                                                            desired == com.nexusts.app.protocols.vnc.cloud.VmPowerAction.START -> "RUNNING"
                                                            else -> "STOPPED"
                                                        }
                                                    )
                                                }
                                            }
                                            .onFailure { err ->
                                                statusBanner = "Action failed: ${err.message ?: "API error"}"
                                            }
                                    }
                                },
                                label = {
                                    Icon(
                                        imageVector = Icons.Outlined.PowerSettingsNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            )
                            FilledTonalButton(
                                onClick = {
                                    openConsoleCallback(vm.name, vm.addressOrSessionHost, vm.preferredConsoleProtocol)
                                }
                            ) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(vm.preferredConsoleProtocol.name)
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
                AssistChip(
                    onClick = { activeMode = mode },
                    label = {
                        Text(
                            text = mode.badge,
                            style = NexusTheme.monospace.keycapShortcut,
                            color = if (activeMode == mode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
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
                                    outputLines += "[Traceroute] Tracing route to $targetHost (max 16 hops, ICMP/UDP/TCP TTL probe)..."
                                    runCatching {
                                        val hops = VncNativeBridge.runTraceroute(
                                            host = targetHost,
                                            probePort = 443,
                                            maxHops = 16
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

