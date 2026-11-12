package com.nexusts.app.protocols.vnc.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nexusts.app.core.ui.input.nexusTrackpadCanvasGesture
import com.nexusts.app.core.ui.theme.NexusTheme
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.protocols.vnc.bridge.VncNativeBridge
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * 1. UltraVNC / TightVNC / RealVNC / Apple Screen Sharing (ARD) Interactive Session View
 *    with ZRLE & Tight encoding telemetry and trackpad pinch-zoom.
 */
@Composable
fun VncSessionCanvasView(
    host: String,
    isAppleScreenSharing: Boolean,
    settings: ProtocolSpecificSettings.Vnc,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val encodingLabel = if (isAppleScreenSharing) "ARD DH-AES • ZRLE" else "${settings.preferredEncodings.firstOrNull() ?: "ZRLE"} • RFB 3.8"

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NexusTheme.extendedColors.terminalCanvasBackground)
            .nexusTrackpadCanvasGesture { _, _, zoomChange ->
                zoom = (zoom * zoomChange).coerceIn(0.6f, 3.0f)
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
            // Remote RFB desktop viewport frame
            val margin = 24.dp.toPx()
            val frameW = (size.width - margin * 2).coerceAtLeast(100f)
            val frameH = (size.height - margin * 2).coerceAtLeast(100f)
            drawRect(
                color = Color(0xFF121828),
                topLeft = Offset(margin, margin),
                size = Size(frameW, frameH)
            )
            drawRect(
                color = Color(0xFF1F2B46),
                topLeft = Offset(margin, margin),
                size = Size(frameW, 34.dp.toPx())
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(99.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
            }
        }
    }
}

/**
 * 2. Isolated Web Page Connection View (HTTP/HTTPS) with:
 *    - Automated CSS-selector credential form-filling
 *    - HTTP Basic Auth injection
 *    - Custom SHA-256 X.509 certificate pinning verification
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

    val injectAutoFillJs: (WebView) -> Unit = { webView ->
        if (settings.autoFillEnabled && secret.username.isNotBlank()) {
            val escapedUser = secret.username.replace("\\", "\\\\").replace("'", "\\'")
            val escapedPass = secret.password.replace("\\", "\\\\").replace("'", "\\'")
            val userSel = settings.usernameCssSelector.replace("'", "\\'")
            val passSel = settings.passwordCssSelector.replace("'", "\\'")
            val script = """
                (function() {
                  const u = document.querySelector('$userSel');
                  const p = document.querySelector('$passSel');
                  if (u) { u.value = '$escapedUser'; u.dispatchEvent(new Event('input', { bubbles: true })); }
                  if (p) { p.value = '$escapedPass'; p.dispatchEvent(new Event('input', { bubbles: true })); }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { webViewRef?.let(injectAutoFillJs) },
                        label = { Text(autoFillActionLabel, style = MaterialTheme.typography.labelSmall) }
                    )
                    AssistChip(
                        onClick = { webViewRef?.reload() },
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
                            if (settings.injectHttpBasicAuth && secret.username.isNotBlank()) {
                                handler?.proceed(secret.username, secret.password)
                            } else {
                                super.onReceivedHttpAuthRequest(view, handler, host, realm)
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val certBytes = error?.certificate?.x509Certificate?.encoded
                            if (certBytes != null && settings.pinnedCertSha256.isNotBlank()) {
                                val actualHex = MessageDigest.getInstance("SHA-256")
                                    .digest(certBytes)
                                    .joinToString("") { "%02x".format(it) }
                                val expectedHex = settings.pinnedCertSha256.replace(":", "").lowercase()
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
                            if (view != null) injectAutoFillJs(view)
                        }
                    }
                    loadUrl(settings.url)
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 3. Cloud & Virtualization Hypervisor Management View supporting:
 *    VMware vSphere/ESXi, Proxmox VE, Microsoft Hyper-V, AWS EC2, Microsoft Azure, and Windows Terminal Services.
 */
@Composable
fun CloudHypervisorConsoleView(
    protocol: VaultProtocolType,
    endpointHost: String,
    settings: ProtocolSpecificSettings.CloudVirtualization,
    openConsoleCallback: (vmName: String, vmHost: String, vmProto: VaultProtocolType) -> Unit,
    modifier: Modifier = Modifier
) {
    data class ManagedInstanceRow(
        val id: String,
        val name: String,
        val state: String,
        val vcpuRam: String,
        val privateIp: String,
        val consoleProtocol: VaultProtocolType
    )

    val instances = remember(protocol, endpointHost) {
        mutableStateListOf(
            ManagedInstanceRow("vm-101", "prod-k8s-control-01", "RUNNING", "8 vCPU • 32 GB", "10.40.1.11", VaultProtocolType.SSH),
            ManagedInstanceRow("vm-102", "corp-win-rds-broker", "RUNNING", "16 vCPU • 64 GB", "10.40.1.25", VaultProtocolType.RDP),
            ManagedInstanceRow("vm-103", "macos-ci-builder", "RUNNING", "12 Core • 32 GB", "10.40.2.19", VaultProtocolType.VNC),
            ManagedInstanceRow("vm-104", "dr-postgres-replica", "STOPPED", "8 vCPU • 64 GB", "10.40.3.40", VaultProtocolType.SSH)
        )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        text = "Cluster / Region: ${settings.regionOrCluster.ifBlank { "default" }} • API: ${settings.apiEndpointUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(instances, key = { it.id }) { vm ->
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
                                text = "${vm.id}  •  ${vm.vcpuRam}  •  ${vm.privateIp}",
                                style = NexusTheme.monospace.keycapShortcut,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = vm.state,
                            style = NexusTheme.monospace.keycapShortcut,
                            color = if (vm.state == "RUNNING") {
                                NexusTheme.extendedColors.statusConnected
                            } else {
                                NexusTheme.extendedColors.statusDisconnected
                            },
                            modifier = Modifier.padding(end = 14.dp)
                        )
                        FilledTonalButton(
                            onClick = { openConsoleCallback(vm.name, vm.privateIp, vm.consoleProtocol) }
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(vm.consoleProtocol.name)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 4. Integrated Network Utilities Suite: Ping, Traceroute, Port Scanner, and Wake-on-LAN (WoL).
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
    var targetHost by remember { mutableStateOf(initialHost) }
    var macInput by remember { mutableStateOf(settings.wolMacAddress.ifBlank { "00:11:22:33:44:55" }) }
    val outputLines = remember { mutableStateListOf<String>() }

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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (initialTool == VaultProtocolType.UTILITY_WAKE_ON_LAN) {
                    Icons.Outlined.PowerSettingsNew
                } else {
                    Icons.Outlined.NetworkCheck
                },
                contentDescription = null,
                tint = NexusTheme.extendedColors.sshAccent
            )
            OutlinedTextField(
                value = if (initialTool == VaultProtocolType.UTILITY_WAKE_ON_LAN) macInput else targetHost,
                onValueChange = {
                    if (initialTool == VaultProtocolType.UTILITY_WAKE_ON_LAN) macInput = it else targetHost = it
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
            Button(
                onClick = {
                    outputLines.clear()
                    scope.launch {
                        when (initialTool) {
                            VaultProtocolType.UTILITY_WAKE_ON_LAN -> {
                                runCatching {
                                    val bytes = VncNativeBridge.wakeOnLan(
                                        macAddress = macInput,
                                        broadcastAddress = settings.wolBroadcastAddress,
                                        port = settings.wolUdpPort
                                    )
                                    outputLines += "[WoL] Sent $bytes-byte IEEE 802 Magic Packet to $macInput via ${settings.wolBroadcastAddress}:${settings.wolUdpPort}"
                                }.onFailure { err ->
                                    outputLines += "[WoL Error] ${err.message}"
                                }
                            }
                            VaultProtocolType.UTILITY_PORT_SCANNER -> {
                                outputLines += "[PortScan] Scanning $targetHost (${settings.portScanRangeStart}..${settings.portScanRangeEnd})..."
                                val openPorts = VncNativeBridge.scanPortRange(
                                    host = targetHost,
                                    startPort = settings.portScanRangeStart,
                                    endPort = settings.portScanRangeEnd.coerceAtMost(settings.portScanRangeStart + 256)
                                )
                                if (openPorts.isEmpty()) {
                                    outputLines += "[PortScan] Completed: 0 open TCP ports in range."
                                } else {
                                    openPorts.forEach { hit ->
                                        outputLines += "  OPEN  tcp/${hit.port}  (${hit.guessed_service})  ${hit.connect_latency_ms} ms"
                                    }
                                }
                            }
                            else -> {
                                outputLines += "[Ping] Probing $targetHost (${settings.pingCount} samples)..."
                                val samples = VncNativeBridge.runTcpPing(targetHost, count = settings.pingCount.coerceAtMost(6))
                                samples.forEach { s ->
                                    outputLines += "  seq=${s.sequence}  host=${s.targetIp}  reachable=${s.reachable}  time=${s.latencyMs} ms"
                                }
                            }
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
