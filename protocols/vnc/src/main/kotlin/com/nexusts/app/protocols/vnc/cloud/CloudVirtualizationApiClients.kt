package com.nexusts.app.protocols.vnc.cloud

import android.annotation.SuppressLint
import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.protocols.vnc.bridge.VncNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Serializable
enum class VmPowerAction {
    START,
    STOP,
    SAVE,
    REBOOT
}

@Serializable
data class HyperVPortStatus(
    val port: Int,
    val label: String,
    val open: Boolean,
    val latencyMs: Long = 0L
)

@Serializable
data class CloudManagedResource(
    val id: String,
    val name: String,
    val nodeOrZone: String,
    val powerState: String,
    val cpuRamSummary: String,
    val addressOrSessionHost: String,
    val preferredConsoleProtocol: VaultProtocolType,
    val isTerminalServicesSession: Boolean = false
)

@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
internal fun buildCloudHttpClient(allowSelfSignedTls: Boolean): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
    if (allowSelfSignedTls) {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, trustAll)
        builder.hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

/**
 * Dedicated Proxmox VE REST API v2 Client supporting both:
 * 1. Username/Password Ticket + CSRF handshake (`POST /api2/json/access/ticket` -> `Cookie: PVEAuthCookie=...` + `CSRFPreventionToken`)
 * 2. Pre-created API Tokens (`Authorization: PVEAPIToken=user@realm!tokenid=secret`)
 *
 * Defaults to `allowSelfSignedTls = true` because Proxmox VE `:8006` ships with a node-generated self-signed CA.
 */
class ProxmoxVeApiClient(
    val allowSelfSignedTls: Boolean = true,
    private val httpClientOverride: OkHttpClient? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient = httpClientOverride ?: buildCloudHttpClient(allowSelfSignedTls)

    data class ProxmoxTicketSession(
        val ticket: String,
        val csrfPreventionToken: String,
        val username: String
    )

    private sealed interface ProxmoxAuth {
        data class ApiToken(val headerValue: String) : ProxmoxAuth
        data class TicketCookie(val session: ProxmoxTicketSession) : ProxmoxAuth
    }

    fun authenticateTicket(
        baseUrl: String,
        username: String,
        password: String,
        realm: String = "pam"
    ): ProxmoxTicketSession {
        val base = CloudVirtualizationHubClient.normalizeProxmoxBaseUrl("", baseUrl)
        val qualifiedUser = if ('@' in username) {
            username.trim()
        } else {
            "${username.trim()}@${realm.trim().ifBlank { "pam" }}"
        }
        val form = "username=${urlEncode(qualifiedUser)}&password=${urlEncode(password)}"
        val req = Request.Builder()
            .url("$base/api2/json/access/ticket")
            .post(form.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Proxmox ticket login HTTP ${resp.code}")
            val dataObj = json.parseToJsonElement(resp.body?.string().orEmpty())
                .jsonObject["data"]?.jsonObject
                ?: error("Missing 'data' object in Proxmox /access/ticket response")
            val ticket = dataObj["ticket"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("Missing 'ticket' in Proxmox /access/ticket response")
            val csrf = dataObj["CSRFPreventionToken"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val resolvedUser = dataObj["username"]?.jsonPrimitive?.contentOrNull ?: qualifiedUser
            return ProxmoxTicketSession(
                ticket = ticket,
                csrfPreventionToken = csrf,
                username = resolvedUser
            )
        }
    }

    private fun resolveAuth(
        base: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): ProxmoxAuth {
        return when {
            secret.password.startsWith("PVEAPIToken=", ignoreCase = true) ->
                ProxmoxAuth.ApiToken(secret.password.trim())
            secret.username.contains('!') ->
                ProxmoxAuth.ApiToken("PVEAPIToken=${secret.username.trim()}=${secret.password}")
            else ->
                ProxmoxAuth.TicketCookie(
                    authenticateTicket(
                        baseUrl = base,
                        username = secret.username,
                        password = secret.password,
                        realm = settings.realmOrDomain
                    )
                )
        }
    }

    fun fetchInventory(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val base = CloudVirtualizationHubClient.normalizeProxmoxBaseUrl(host, settings.apiEndpointUrl)
        val auth = resolveAuth(base, settings, secret)
        val reqBuilder = Request.Builder()
            .url("$base/api2/json/cluster/resources?type=vm")
            .get()
        when (auth) {
            is ProxmoxAuth.ApiToken -> reqBuilder.header("Authorization", auth.headerValue)
            is ProxmoxAuth.TicketCookie -> reqBuilder.header("Cookie", "PVEAuthCookie=${auth.session.ticket}")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Proxmox VE HTTP ${resp.code}")
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val arr = root["data"]?.jsonArray ?: return emptyList()
            val effectiveHost = host.ifBlank {
                base.removePrefix("https://").removePrefix("http://").substringBefore(':')
            }
            return arr.map { elem ->
                val obj = elem.jsonObject
                val vmid = obj["vmid"]?.jsonPrimitive?.content ?: obj["id"]?.jsonPrimitive?.content.orEmpty()
                val name = obj["name"]?.jsonPrimitive?.content ?: "pve-$vmid"
                val node = obj["node"]?.jsonPrimitive?.content ?: settings.regionOrCluster.ifBlank { "pve" }
                val status = (obj["status"]?.jsonPrimitive?.content ?: "unknown").uppercase(Locale.ROOT)
                val maxCpu = obj["maxcpu"]?.jsonPrimitive?.content ?: "2"
                val maxMemBytes = obj["maxmem"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val ramGb = (maxMemBytes / (1024L * 1024L * 1024L)).coerceAtLeast(1L)
                val vmType = obj["type"]?.jsonPrimitive?.content ?: "qemu"
                val normalizedId = if ('/' in vmid) vmid else "$vmType/$vmid"
                CloudManagedResource(
                    id = normalizedId,
                    name = name,
                    nodeOrZone = node,
                    powerState = status,
                    cpuRamSummary = "$maxCpu vCPU • $ramGb GB ($vmType)",
                    addressOrSessionHost = effectiveHost,
                    preferredConsoleProtocol = if (vmType == "lxc") VaultProtocolType.SSH else VaultProtocolType.VNC
                )
            }
        }
    }

    fun controlVm(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction
    ): String {
        val base = CloudVirtualizationHubClient.normalizeProxmoxBaseUrl(host, settings.apiEndpointUrl)
        val auth = resolveAuth(base, settings, secret)
        val verb = when (action) {
            VmPowerAction.START -> "start"
            VmPowerAction.STOP -> "stop"
            VmPowerAction.SAVE -> "suspend"
            VmPowerAction.REBOOT -> "reboot"
        }
        val vmPath = if ('/' in resource.id) resource.id else "qemu/${resource.id.removePrefix("vm-")}"
        val reqBuilder = Request.Builder()
            .url("$base/api2/json/nodes/${resource.nodeOrZone}/$vmPath/status/$verb")
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        when (auth) {
            is ProxmoxAuth.ApiToken -> reqBuilder.header("Authorization", auth.headerValue)
            is ProxmoxAuth.TicketCookie -> {
                reqBuilder.header("Cookie", "PVEAuthCookie=${auth.session.ticket}")
                if (auth.session.csrfPreventionToken.isNotBlank()) {
                    reqBuilder.header("CSRFPreventionToken", auth.session.csrfPreventionToken)
                }
            }
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Proxmox power action HTTP ${resp.code}")
            val rawBody = resp.body?.string().orEmpty()
            val upid = runCatching {
                json.parseToJsonElement(rawBody).jsonObject["data"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.takeIf { it.isNotBlank() }
            return upid ?: "PVE UPID queued ($verb on ${resource.name})"
        }
    }

    private fun urlEncode(raw: String): String =
        URLEncoder.encode(raw, StandardCharsets.UTF_8.name())
}

/**
 * Production REST / SOAP / SigV4 Client Hub for Enterprise Cloud & Virtualization Hypervisors:
 * - **Proxmox VE** (`/api2/json/access/ticket` + `/api2/json/cluster/resources` + `/status/{start|stop|reboot}`)
 * - **VMware vSphere / ESXi** (`/api/session` + `/api/vcenter/vm` + `/power?action=...`)
 * - **AWS EC2** (`DescribeInstances` / `StartInstances` / `StopInstances` / `RebootInstances` signed via **AWS SigV4 `AWS4-HMAC-SHA256`**)
 * - **Microsoft Azure ARM** (`client_credentials` OAuth2 token exchange + `Microsoft.Compute/virtualMachines?api-version=2024-03-01&$expand=instanceView`)
 * - **Microsoft Hyper-V** (WinRM HTTP `5985` NTLMv2 + `[MS-WSMV]` SPNEGO Sealing, WinRM HTTPS `5986` NTLMv2, and OpenSSH `22` PowerShell)
 * - **Windows Terminal Services / RDS** (WinRM `Win32_TerminalServicesSession` active session listing & `Win32_Process/Create` logoff/disconnect)
 */
object CloudVirtualizationHubClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val strictHttpClient: OkHttpClient = buildCloudHttpClient(allowSelfSignedTls = false)
    private val selfSignedHttpClient: OkHttpClient = buildCloudHttpClient(allowSelfSignedTls = true)

    private fun clientFor(allowSelfSignedTls: Boolean): OkHttpClient =
        if (allowSelfSignedTls) selfSignedHttpClient else strictHttpClient

    suspend fun listInventory(
        protocol: VaultProtocolType,
        endpointHost: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        allowSelfSignedTls: Boolean = true
    ): Result<List<CloudManagedResource>> = withContext(Dispatchers.IO) {
        runCatching {
            when (protocol) {
                VaultProtocolType.PROXMOX_VE ->
                    ProxmoxVeApiClient(allowSelfSignedTls = allowSelfSignedTls)
                        .fetchInventory(endpointHost, settings, secret)
                VaultProtocolType.VMWARE_VSPHERE ->
                    fetchVsphereInventory(endpointHost, settings, secret, allowSelfSignedTls)
                VaultProtocolType.AWS_EC2 ->
                    fetchAwsEc2Inventory(settings, secret)
                VaultProtocolType.AZURE_VM ->
                    fetchAzureVmInventory(settings, secret)
                VaultProtocolType.HYPER_V ->
                    fetchHyperVInventory(endpointHost, settings, secret, allowSelfSignedTls)
                VaultProtocolType.WINDOWS_TERMINAL_SERVICES ->
                    fetchWindowsTerminalServicesSessions(endpointHost, settings, secret, allowSelfSignedTls)
                else -> emptyList()
            }
        }
    }

    suspend fun executePowerOrSessionAction(
        protocol: VaultProtocolType,
        endpointHost: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction,
        allowSelfSignedTls: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (protocol) {
                VaultProtocolType.PROXMOX_VE ->
                    ProxmoxVeApiClient(allowSelfSignedTls = allowSelfSignedTls)
                        .controlVm(endpointHost, settings, secret, resource, action)
                VaultProtocolType.VMWARE_VSPHERE ->
                    controlVsphereVm(endpointHost, settings, secret, resource, action, allowSelfSignedTls)
                VaultProtocolType.AWS_EC2 ->
                    controlAwsEc2Instance(settings, secret, resource, action)
                VaultProtocolType.AZURE_VM ->
                    controlAzureVm(settings, secret, resource, action)
                VaultProtocolType.HYPER_V ->
                    controlHyperVVm(endpointHost, settings, secret, resource, action, allowSelfSignedTls)
                VaultProtocolType.WINDOWS_TERMINAL_SERVICES ->
                    disconnectOrLogoffRdsSession(endpointHost, settings, secret, resource, action, allowSelfSignedTls)
                else -> "Unsupported provider ${protocol.name}"
            }
        }
    }

    internal fun normalizeProxmoxBaseUrl(host: String, apiEndpointUrl: String): String {
        val raw = apiEndpointUrl.trim().ifBlank { "https://${host.trim()}:8006" }
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
        return withScheme
            .trimEnd('/')
            .removeSuffix("/api2/json")
            .trimEnd('/')
    }

    // =========================================================================
    // 2. VMware vSphere / ESXi Cis Session + vCenter REST API
    // =========================================================================

    private fun normalizeHttpsEndpoint(host: String, apiEndpointUrl: String): String {
        val raw = apiEndpointUrl.trim().ifBlank { "https://${host.trim()}" }
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
        return withScheme.trimEnd('/')
    }

    private fun acquireVsphereSessionToken(
        base: String,
        secret: ResolvedSessionSecret,
        http: OkHttpClient
    ): String {
        val basic = Base64.getEncoder()
            .encodeToString("${secret.username}:${secret.password}".toByteArray(StandardCharsets.UTF_8))
        val sessionReq = Request.Builder()
            .url("$base/api/session")
            .header("Authorization", "Basic $basic")
            .post("".toRequestBody(null))
            .build()

        http.newCall(sessionReq).execute().use { resp ->
            if (!resp.isSuccessful) error("vSphere /api/session HTTP ${resp.code}")
            return resp.body?.string().orEmpty().trim().trim('"')
        }
    }

    private fun fetchVsphereInventory(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        allowSelfSignedTls: Boolean
    ): List<CloudManagedResource> {
        val http = clientFor(allowSelfSignedTls)
        val base = normalizeHttpsEndpoint(host, settings.apiEndpointUrl)
        val token = acquireVsphereSessionToken(base, secret, http)
        val req = Request.Builder()
            .url("$base/api/vcenter/vm")
            .header("vmware-api-session-id", token)
            .get()
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("vSphere /api/vcenter/vm HTTP ${resp.code}")
            val arr = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonArray
            return arr.map { elem ->
                val obj = elem.jsonObject
                val vmId = obj["vm"]?.jsonPrimitive?.content.orEmpty()
                val name = obj["name"]?.jsonPrimitive?.content ?: vmId
                val state = obj["power_state"]?.jsonPrimitive?.content ?: "UNKNOWN"
                val cpu = obj["cpu_count"]?.jsonPrimitive?.content ?: "2"
                val memMib = obj["memory_size_MiB"]?.jsonPrimitive?.content?.toLongOrNull() ?: 4096L
                CloudManagedResource(
                    id = vmId,
                    name = name,
                    nodeOrZone = settings.regionOrCluster.ifBlank { "vCenter" },
                    powerState = if (state.contains("POWERED_ON")) "RUNNING" else "STOPPED",
                    cpuRamSummary = "$cpu vCPU • ${memMib / 1024} GB",
                    addressOrSessionHost = host,
                    preferredConsoleProtocol = VaultProtocolType.VNC
                )
            }
        }
    }

    private fun controlVsphereVm(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction,
        allowSelfSignedTls: Boolean
    ): String {
        val http = clientFor(allowSelfSignedTls)
        val base = normalizeHttpsEndpoint(host, settings.apiEndpointUrl)
        val token = acquireVsphereSessionToken(base, secret, http)
        val act = when (action) {
            VmPowerAction.START -> "start"
            VmPowerAction.STOP -> "stop"
            VmPowerAction.SAVE -> "suspend"
            VmPowerAction.REBOOT -> "reset"
        }
        val req = Request.Builder()
            .url("$base/api/vcenter/vm/${resource.id}/power?action=$act")
            .header("vmware-api-session-id", token)
            .post("".toRequestBody(null))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("vSphere power HTTP ${resp.code}")
            return "vCenter VM ${resource.id} -> $act"
        }
    }

    // =========================================================================
    // 3. AWS EC2 Query API with Full AWS Signature Version 4 (`AWS4-HMAC-SHA256`)
    // =========================================================================

    private fun fetchAwsEc2Inventory(
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val region = settings.regionOrCluster.ifBlank { "us-east-1" }
        val xmlBody = executeAwsEc2SignedAction(
            region = region,
            accessKeyId = secret.username,
            secretAccessKey = secret.password,
            formQuery = "Action=DescribeInstances&Version=2016-11-15"
        )
        return parseEc2DescribeInstancesXml(xmlBody, region)
    }

    private fun controlAwsEc2Instance(
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction
    ): String {
        val region = settings.regionOrCluster.ifBlank { "us-east-1" }
        val ec2Action = when (action) {
            VmPowerAction.START -> "StartInstances"
            VmPowerAction.STOP, VmPowerAction.SAVE -> "StopInstances"
            VmPowerAction.REBOOT -> "RebootInstances"
        }
        executeAwsEc2SignedAction(
            region = region,
            accessKeyId = secret.username,
            secretAccessKey = secret.password,
            formQuery = "Action=$ec2Action&InstanceId.1=${resource.id}&Version=2016-11-15"
        )
        return "AWS EC2 $ec2Action -> ${resource.id}"
    }

    /**
     * Complete AWS Signature Version 4 (`AWS4-HMAC-SHA256`) request signer for EC2 POST endpoints.
     */
    internal fun executeAwsEc2SignedAction(
        region: String,
        accessKeyId: String,
        secretAccessKey: String,
        formQuery: String
    ): String {
        val service = "ec2"
        val host = "ec2.$region.amazonaws.com"
        val endpoint = "https://$host/"
        val contentType = "application/x-www-form-urlencoded; charset=utf-8"

        val now = Date()
        val amzDateFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateStampFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val amzDate = amzDateFmt.format(now)
        val dateStamp = dateStampFmt.format(now)

        val payloadHash = sha256Hex(formQuery.toByteArray(StandardCharsets.UTF_8))
        val canonicalHeaders = "content-type:$contentType\nhost:$host\nx-amz-date:$amzDate\n"
        val signedHeaders = "content-type;host;x-amz-date"
        val canonicalRequest = "POST\n/\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))}"

        val kSecret = ("AWS4$secretAccessKey").toByteArray(StandardCharsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        val kSigning = hmacSha256(kService, "aws4_request")
        val signatureHex = hmacSha256(kSigning, stringToSign).joinToString("") { "%02x".format(it) }

        val authorizationHeader =
            "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signatureHex"

        val req = Request.Builder()
            .url(endpoint)
            .header("Content-Type", contentType)
            .header("Host", host)
            .header("X-Amz-Date", amzDate)
            .header("Authorization", authorizationHeader)
            .post(formQuery.toRequestBody(contentType.toMediaType()))
            .build()

        strictHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("AWS EC2 HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    internal fun parseEc2DescribeInstancesXml(xml: String, region: String): List<CloudManagedResource> {
        val instancesSetRegex = Regex("<instancesSet>(.*?)</instancesSet>", setOf(RegexOption.DOT_MATCHES_ALL))
        val idRegex = Regex("<instanceId>(i-[a-zA-Z0-9]+)</instanceId>")
        val typeRegex = Regex("<instanceType>([^<]+)</instanceType>")
        val pubIpRegex = Regex("<ipAddress>([^<]+)</ipAddress>")
        val privIpRegex = Regex("<privateIpAddress>([^<]+)</privateIpAddress>")
        val dnsRegex = Regex("<dnsName>([^<]+)</dnsName>")
        val azRegex = Regex("<placement>.*?<availabilityZone>([^<]+)</availabilityZone>.*?</placement>", setOf(RegexOption.DOT_MATCHES_ALL))
        val stateRegex = Regex("<instanceState>.*?<name>([^<]+)</name>.*?</instanceState>", setOf(RegexOption.DOT_MATCHES_ALL))
        val platformRegex = Regex("<platform>([^<]+)</platform>")
        val tagSetRegex = Regex("<tagSet>(.*?)</tagSet>", setOf(RegexOption.DOT_MATCHES_ALL))
        val tagItemRegex = Regex("<item>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL))
        val tagKeyRegex = Regex("<key>([^<]*)</key>")
        val tagValueRegex = Regex("<value>([^<]*)</value>")

        val instanceBlocks = mutableListOf<String>()
        val instancesSets = instancesSetRegex.findAll(xml).toList()
        if (instancesSets.isNotEmpty()) {
            for (setMatch in instancesSets) {
                instanceBlocks += extractBalancedXmlItems(setMatch.groupValues[1])
            }
        } else {
            instanceBlocks += extractBalancedXmlItems(xml)
        }

        val results = mutableListOf<CloudManagedResource>()
        val seenIds = mutableSetOf<String>()
        for (block in instanceBlocks) {
            val instId = idRegex.find(block)?.groupValues?.get(1) ?: continue
            if (!seenIds.add(instId)) continue

            var displayName = instId
            val tagSetBody = tagSetRegex.find(block)?.groupValues?.get(1)
            if (tagSetBody != null) {
                for (tMatch in tagItemRegex.findAll(tagSetBody)) {
                    val tBlock = tMatch.groupValues[1]
                    val k = tagKeyRegex.find(tBlock)?.groupValues?.get(1)?.trim()
                    val v = tagValueRegex.find(tBlock)?.groupValues?.get(1)?.trim()
                    if (k == "Name" && !v.isNullOrBlank()) {
                        displayName = unescapeXml(v)
                        break
                    }
                }
            }

            val instType = typeRegex.find(block)?.groupValues?.get(1) ?: "m6i.large"
            val hostAddr = pubIpRegex.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: privIpRegex.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: dnsRegex.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ?: "0.0.0.0"
            val az = azRegex.find(block)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: region
            val state = (stateRegex.find(block)?.groupValues?.get(1) ?: "running").uppercase(Locale.ROOT)
            val isWindows = platformRegex.find(block)?.groupValues?.get(1)?.contains("windows", ignoreCase = true) == true

            results += CloudManagedResource(
                id = instId,
                name = displayName,
                nodeOrZone = az,
                powerState = state,
                cpuRamSummary = instType,
                addressOrSessionHost = hostAddr,
                preferredConsoleProtocol = if (isWindows) VaultProtocolType.RDP else VaultProtocolType.SSH
            )
        }
        return results
    }

    private fun extractBalancedXmlItems(section: String): List<String> {
        val items = mutableListOf<String>()
        val tokenRegex = Regex("<(/?)item(?:\\s[^>]*)?>")
        var depth = 0
        var blockStart = -1
        for (m in tokenRegex.findAll(section)) {
            val isClosing = m.groupValues[1] == "/"
            if (!isClosing) {
                if (depth == 0) {
                    blockStart = m.range.last + 1
                }
                depth++
            } else if (depth > 0) {
                depth--
                if (depth == 0 && blockStart >= 0 && blockStart <= m.range.first) {
                    items += section.substring(blockStart, m.range.first)
                    blockStart = -1
                }
            }
        }
        return items
    }

    // =========================================================================
    // 4. Microsoft Azure Compute ARM REST API (`2024-03-01` + OAuth2 `client_credentials`)
    // =========================================================================

    internal fun exchangeAzureAdClientCredentialsToken(
        tenantId: String,
        clientId: String,
        clientSecret: String,
        authorityBaseUrl: String = "https://login.microsoftonline.com",
        http: OkHttpClient = strictHttpClient
    ): String {
        val cleanTenant = tenantId.trim()
        require(cleanTenant.isNotEmpty()) { "Azure AD Tenant ID is required for client_credentials token exchange" }
        val tokenUrl = "${authorityBaseUrl.trimEnd('/')}/$cleanTenant/oauth2/v2.0/token"
        val form = buildString {
            append("grant_type=client_credentials")
            append("&client_id=").append(URLEncoder.encode(clientId.trim(), StandardCharsets.UTF_8.name()))
            append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8.name()))
            append("&scope=").append(URLEncoder.encode("https://management.azure.com/.default", StandardCharsets.UTF_8.name()))
        }
        val req = Request.Builder()
            .url(tokenUrl)
            .post(form.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Azure AD OAuth2 token HTTP ${resp.code}")
            val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            return obj["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("Missing access_token in Azure AD OAuth2 response")
        }
    }

    private fun resolveAzureBearerToken(
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): String {
        val tenantCandidate = secret.domain.ifBlank {
            settings.realmOrDomain.takeIf { it.isNotBlank() && !it.equals("pam", ignoreCase = true) }.orEmpty()
        }
        return if (secret.username.isNotBlank() && tenantCandidate.isNotBlank()) {
            exchangeAzureAdClientCredentialsToken(
                tenantId = tenantCandidate,
                clientId = secret.username,
                clientSecret = secret.password
            )
        } else {
            secret.password
        }
    }

    private fun fetchAzureVmInventory(
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val subId = settings.subscriptionOrAccountId.trim()
        require(subId.isNotBlank()) { "Azure Subscription ID required" }
        val bearer = resolveAzureBearerToken(settings, secret)
        val armBase = settings.apiEndpointUrl.trim()
            .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?.trimEnd('/')
            ?: "https://management.azure.com"
        val url = "$armBase/subscriptions/$subId/providers/Microsoft.Compute/virtualMachines?api-version=2024-03-01&\$expand=instanceView"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .get()
            .build()

        strictHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Azure ARM HTTP ${resp.code}")
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val arr = root["value"]?.jsonArray ?: return emptyList()
            return arr.map { elem ->
                val obj = elem.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
                val name = obj["name"]?.jsonPrimitive?.content ?: "azure-vm"
                val location = obj["location"]?.jsonPrimitive?.content ?: settings.regionOrCluster
                val props = obj["properties"] as? JsonObject
                val hwProfile = props?.get("hardwareProfile") as? JsonObject
                val vmSize = hwProfile?.get("vmSize")?.jsonPrimitive?.content ?: "Standard_D4s_v5"
                val storageProfile = props?.get("storageProfile") as? JsonObject
                val osDisk = storageProfile?.get("osDisk") as? JsonObject
                val osType = osDisk?.get("osType")?.jsonPrimitive?.content ?: "Linux"
                val osProfile = props?.get("osProfile") as? JsonObject
                val computerName = osProfile?.get("computerName")?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: name

                val statuses = (props?.get("instanceView") as? JsonObject)?.get("statuses")?.jsonArray
                val powerCode = statuses
                    ?.mapNotNull { st -> st.jsonObject["code"]?.jsonPrimitive?.contentOrNull }
                    ?.firstOrNull { code -> code.startsWith("PowerState/", ignoreCase = true) }
                    ?.substringAfter('/')
                    ?.lowercase(Locale.ROOT)
                val normalizedPowerState = when (powerCode) {
                    "running" -> "RUNNING"
                    "deallocated", "deallocating", "stopped", "stopping" -> "STOPPED"
                    "starting" -> "STARTING"
                    null -> (props?.get("provisioningState")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN").uppercase(Locale.ROOT)
                    else -> powerCode.uppercase(Locale.ROOT)
                }

                CloudManagedResource(
                    id = id,
                    name = name,
                    nodeOrZone = location,
                    powerState = normalizedPowerState,
                    cpuRamSummary = "$vmSize ($osType)",
                    addressOrSessionHost = computerName,
                    preferredConsoleProtocol = if (osType.equals("Windows", ignoreCase = true)) {
                        VaultProtocolType.RDP
                    } else {
                        VaultProtocolType.SSH
                    }
                )
            }
        }
    }

    private fun controlAzureVm(
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction
    ): String {
        val armAction = when (action) {
            VmPowerAction.START -> "start"
            VmPowerAction.STOP -> "powerOff"
            VmPowerAction.SAVE -> "deallocate"
            VmPowerAction.REBOOT -> "restart"
        }
        val bearer = resolveAzureBearerToken(settings, secret)
        val resourcePath = if (resource.id.startsWith("/")) {
            resource.id
        } else {
            val subId = settings.subscriptionOrAccountId.trim()
            val rg = settings.regionOrCluster.ifBlank { "default" }
            "/subscriptions/$subId/resourceGroups/$rg/providers/Microsoft.Compute/virtualMachines/${resource.id}"
        }
        val armBase = settings.apiEndpointUrl.trim()
            .takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?.trimEnd('/')
            ?: "https://management.azure.com"
        val url = "$armBase$resourcePath/$armAction?api-version=2024-03-01"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .post("".toRequestBody(null))
            .build()

        strictHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Azure VM $armAction HTTP ${resp.code}")
            return "Azure ARM $armAction accepted (${resource.name})"
        }
    }

    // =========================================================================
    // 5. Microsoft Hyper-V Multi-Transport Engine (WinRM 5985 / 5986 / SSH 22)
    //    & 6. Windows Terminal Services
    // =========================================================================

    fun sanitizedHyperVEndpoint(rawEndpoint: String, connectionHost: String): String {
        val trimmed = rawEndpoint.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase(Locale.ROOT)
        val cleanHost = connectionHost.trim().lowercase(Locale.ROOT)

        if (lower.contains(":8006") && !cleanHost.contains(":8006")) {
            return ""
        }
        if ((lower.contains("localhost") || lower.contains("127.0.0.1")) &&
            cleanHost.isNotEmpty() &&
            cleanHost != "localhost" &&
            cleanHost != "127.0.0.1"
        ) {
            return ""
        }
        val parsed = runCatching { URI(trimmed) }.getOrNull()
        if (parsed != null && parsed.port == -1) {
            val path = parsed.path.orEmpty().trim('/')
            if (path.isEmpty()) {
                return ""
            }
        }
        return trimmed
    }

    fun extractBareHyperVHost(rawHost: String): String {
        var h = rawHost.trim()
        if (h.startsWith("http://", true) || h.startsWith("https://", true)) {
            val parsed = runCatching { URI(h) }.getOrNull()
            if (!parsed?.host.isNullOrBlank()) return parsed!!.host
        }
        if ('/' in h) {
            h = h.substringBefore('/')
        }
        if (!h.startsWith('[') && h.count { it == ':' } == 1) {
            val portPart = h.substringAfter(':')
            if (portPart.toIntOrNull() != null) {
                h = h.substringBefore(':')
            }
        }
        return h.trim('[', ']', ' ')
    }

    suspend fun diagnoseHyperVPorts(host: String): List<HyperVPortStatus> = withContext(Dispatchers.IO) {
        val bareHost = extractBareHyperVHost(host)
        if (bareHost.isBlank()) return@withContext emptyList()
        val targets = listOf(
            2179 to "VMConnect",
            5985 to "WinRM HTTP",
            5986 to "WinRM HTTPS",
            22 to "OpenSSH",
            3389 to "Host RDP"
        )
        coroutineScope {
            targets.map { (port, label) ->
                async {
                    val nativeResult = runCatching {
                        val probeJson = VncNativeBridge.nativeProbeTcpPortJson(bareHost, port, 650)
                        if (probeJson.isNotBlank() && probeJson != "null") {
                            val obj = json.parseToJsonElement(probeJson).jsonObject
                            val open = obj["open"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                            val lat = obj["latency_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                            HyperVPortStatus(port = port, label = label, open = open, latencyMs = lat)
                        } else {
                            null
                        }
                    }.getOrNull()

                    nativeResult ?: run {
                        val startNs = System.nanoTime()
                        val open = runCatching {
                            Socket().use { sock ->
                                sock.connect(InetSocketAddress(bareHost, port), 650)
                                true
                            }
                        }.getOrDefault(false)
                        val latMs = if (open) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs) else 0L
                        HyperVPortStatus(port = port, label = label, open = open, latencyMs = latMs)
                    }
                }
            }.awaitAll()
        }
    }

    private data class WindowsIdentity(
        val username: String,
        val password: String,
        val domain: String,
        val privateKeyPem: String,
        val keyPassphrase: String
    )

    private fun resolveWindowsIdentity(
        secret: ResolvedSessionSecret,
        fallbackRealmOrDomain: String
    ): WindowsIdentity {
        val rawUser = secret.username.trim()
        val rawDom = secret.domain.trim()
        val fallbackDom = fallbackRealmOrDomain.trim().takeIf { !it.equals("pam", ignoreCase = true) }.orEmpty()
        if ('\\' in rawUser) {
            val domPart = rawUser.substringBefore('\\')
            val userPart = rawUser.substringAfter('\\')
            val effectiveDom = if (domPart == ".") "" else rawDom.ifBlank { domPart }
            return WindowsIdentity(
                username = userPart,
                password = secret.password,
                domain = effectiveDom,
                privateKeyPem = secret.privateKeyPem.trim(),
                keyPassphrase = secret.keyPassphrase
            )
        }
        val resolvedDom = when {
            rawDom.isNotEmpty() -> rawDom
            '@' in rawUser -> ""
            else -> fallbackDom
        }
        return WindowsIdentity(
            username = rawUser,
            password = secret.password,
            domain = if (resolvedDom == ".") "" else resolvedDom,
            privateKeyPem = secret.privateKeyPem.trim(),
            keyPassphrase = secret.keyPassphrase
        )
    }

    private fun normalizeWinRmUrl(host: String, apiEndpointUrl: String): String {
        val raw = apiEndpointUrl.trim().ifBlank { "https://${host.trim()}:5986/wsman" }
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
        val trimmed = withScheme.trimEnd('/')
        return if (trimmed.endsWith("/wsman", ignoreCase = true)) trimmed else "$trimmed/wsman"
    }

    fun parseHyperVWsmanXml(body: String, hostLabel: String): List<CloudManagedResource> {
        val systemBlockRegex = Regex(
            "<(?:\\w+:)?Msvm_ComputerSystem[^>]*>(.*?)</(?:\\w+:)?Msvm_ComputerSystem>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val elemRegex = Regex("<(?:\\w+:)?ElementName>([^<]+)</(?:\\w+:)?ElementName>")
        val nameRegex = Regex("<(?:\\w+:)?Name>([^<]+)</(?:\\w+:)?Name>")
        val captionRegex = Regex("<(?:\\w+:)?Caption>([^<]+)</(?:\\w+:)?Caption>")
        val stateRegex = Regex("<(?:\\w+:)?EnabledState>(\\d+)</(?:\\w+:)?EnabledState>")

        val blocks = systemBlockRegex.findAll(body).map { it.groupValues[1] }.toList()
        if (blocks.isNotEmpty()) {
            return blocks.mapNotNull { block ->
                val caption = captionRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                if (caption.contains("Hosting Computer System", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val vmGuid = nameRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                val vmName = elemRegex.find(block)?.groupValues?.get(1)?.trim()
                    ?: vmGuid.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val stateCode = stateRegex.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 2
                val power = when (stateCode) {
                    2 -> "RUNNING"
                    3, 6 -> "STOPPED"
                    32768 -> "PAUSED"
                    32769 -> "SAVED"
                    else -> "STATE_$stateCode"
                }
                val resolvedGuid = unescapeXml(vmGuid.ifBlank { vmName })
                CloudManagedResource(
                    id = resolvedGuid,
                    name = unescapeXml(vmName),
                    nodeOrZone = hostLabel,
                    powerState = power,
                    cpuRamSummary = "VMConnect :2179",
                    addressOrSessionHost = hostLabel,
                    preferredConsoleProtocol = VaultProtocolType.RDP
                )
            }
        }
        return emptyList()
    }

    fun parseHyperVPowerShellJson(raw: String, hostLabel: String): List<CloudManagedResource> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()

        val jsonSlice: String = run {
            val arrStart = trimmed.indexOf('[')
            val arrEnd = trimmed.lastIndexOf(']')
            val objStart = trimmed.indexOf('{')
            val objEnd = trimmed.lastIndexOf('}')
            when {
                arrStart >= 0 && arrEnd > arrStart && (objStart < 0 || arrStart < objStart) ->
                    trimmed.substring(arrStart, arrEnd + 1)
                objStart >= 0 && objEnd > objStart ->
                    trimmed.substring(objStart, objEnd + 1)
                else -> error("Unexpected PowerShell output: ${trimmed.take(180)}")
            }
        }

        val parsedElem = json.parseToJsonElement(jsonSlice)
        val objects: List<JsonObject> = when (parsedElem) {
            is JsonArray -> parsedElem.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(parsedElem)
            else -> emptyList()
        }

        return objects.map { obj ->
            val vmId = obj["Id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val vmName = obj["Name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val rawState = obj["State"]?.jsonPrimitive?.contentOrNull?.uppercase(Locale.ROOT).orEmpty()
            val power = when {
                "RUN" in rawState -> "RUNNING"
                "OFF" in rawState || "STOP" in rawState -> "STOPPED"
                "SAV" in rawState -> "SAVED"
                "PAUS" in rawState -> "PAUSED"
                rawState.isNotBlank() -> rawState
                else -> "UNKNOWN"
            }
            val cpus = (obj["CpuCount"]?.jsonPrimitive?.intOrNull ?: 2).coerceAtLeast(1)
            val memAssigned = obj["MemAssignedMB"]?.jsonPrimitive?.intOrNull ?: 0
            val memStartup = obj["MemStartupMB"]?.jsonPrimitive?.intOrNull ?: 0
            val memMb = if (memAssigned > 0) memAssigned else memStartup
            val memLabel = if (memMb >= 1024) {
                String.format(Locale.US, "%.1f GiB", memMb / 1024.0)
            } else if (memMb > 0) {
                "$memMb MiB"
            } else {
                "Dynamic RAM"
            }
            val resolvedId = vmId.ifBlank { UUID.randomUUID().toString() }
            CloudManagedResource(
                id = resolvedId,
                name = vmName.ifBlank { resolvedId },
                nodeOrZone = hostLabel,
                powerState = power,
                cpuRamSummary = "$cpus vCPU • $memLabel",
                addressOrSessionHost = hostLabel,
                preferredConsoleProtocol = VaultProtocolType.RDP
            )
        }
    }

    private fun extractWsmanEnumerationContext(xml: String): String? {
        val m = Regex("<(?:\\w+:)?EnumerationContext>([^<]+)</(?:\\w+:)?EnumerationContext>", RegexOption.IGNORE_CASE)
            .find(xml) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun extractWsmanFaultSummary(xml: String): String? {
        if (!xml.contains(":Fault>", ignoreCase = true) && !xml.contains("<Fault>", ignoreCase = true)) {
            return null
        }
        for (tag in listOf("Text", "Message", "FaultString")) {
            val m = Regex("<(?:\\w+:)?$tag[^>]*>([^<]+)</(?:\\w+:)?$tag>", RegexOption.IGNORE_CASE).find(xml)
            val msg = m?.groupValues?.get(1)?.trim()
            if (!msg.isNullOrEmpty()) return unescapeXml(msg).take(240)
        }
        return "WS-Management Fault returned by Hyper-V host"
    }

    private fun extractWmiReturnValue(xml: String): Int? =
        Regex("<(?:\\w+:)?ReturnValue>(\\d+)</(?:\\w+:)?ReturnValue>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.toIntOrNull()

    private fun buildWinRmPullSoap(toUrl: String, resourceUri: String, enumerationContext: String): String = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:wsman="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                    xmlns:wsen="http://schemas.xmlsoap.org/ws/2004/09/enumeration">
          <s:Header>
            <wsa:To>${escapeXml(toUrl)}</wsa:To>
            <wsman:ResourceURI s:mustUnderstand="true">$resourceUri</wsman:ResourceURI>
            <wsa:Action s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/09/enumeration/Pull</wsa:Action>
            <wsman:MaxEnvelopeSize s:mustUnderstand="true">512000</wsman:MaxEnvelopeSize>
            <wsa:MessageID>uuid:${UUID.randomUUID()}</wsa:MessageID>
            <wsa:ReplyTo>
              <wsa:Address s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:Address>
            </wsa:ReplyTo>
            <wsman:OperationTimeout>PT20.000S</wsman:OperationTimeout>
          </s:Header>
          <s:Body>
            <wsen:Pull>
              <wsen:EnumerationContext>${escapeXml(enumerationContext)}</wsen:EnumerationContext>
              <wsen:MaxElements>200</wsen:MaxElements>
            </wsen:Pull>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun buildHyperVStateChangeSoap(toUrl: String, vmId: String, stateCode: Int): String {
        val resourceUri = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem"
        return """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:wsman="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                        xmlns:p="$resourceUri">
              <s:Header>
                <wsa:To>${escapeXml(toUrl)}</wsa:To>
                <wsman:ResourceURI s:mustUnderstand="true">$resourceUri</wsman:ResourceURI>
                <wsa:Action s:mustUnderstand="true">$resourceUri/RequestStateChange</wsa:Action>
                <wsman:MaxEnvelopeSize s:mustUnderstand="true">512000</wsman:MaxEnvelopeSize>
                <wsa:MessageID>uuid:${UUID.randomUUID()}</wsa:MessageID>
                <wsa:ReplyTo>
                  <wsa:Address s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:Address>
                </wsa:ReplyTo>
                <wsman:SelectorSet>
                  <wsman:Selector Name="CreationClassName">Msvm_ComputerSystem</wsman:Selector>
                  <wsman:Selector Name="Name">${escapeXml(vmId)}</wsman:Selector>
                </wsman:SelectorSet>
                <wsman:OperationTimeout>PT20.000S</wsman:OperationTimeout>
              </s:Header>
              <s:Body>
                <p:RequestStateChange_INPUT>
                  <p:RequestedState>$stateCode</p:RequestedState>
                </p:RequestStateChange_INPUT>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun postWsmanHttpsNtlmOrBasic(
        wsmanUrl: String,
        spnHost: String,
        soapXml: String,
        identity: WindowsIdentity,
        http: OkHttpClient
    ): String {
        val soapMediaType = "application/soap+xml;charset=UTF-8".toMediaType()

        // Step 1: NTLMv2 over TLS (`Negotiate` / `NTLM`)
        if (identity.username.isNotBlank()) {
            val type1 = runCatching { VncNativeBridge.nativeNtlmBuildNegotiate() }.getOrDefault("")
            if (type1.isNotBlank()) {
                val negReq = Request.Builder()
                    .url(wsmanUrl)
                    .header("Authorization", "Negotiate $type1")
                    .header("Connection", "Keep-Alive")
                    .post("".toRequestBody(soapMediaType))
                    .build()

                http.newCall(negReq).execute().use { step1 ->
                    val step1Body = step1.body?.string().orEmpty()
                    if (step1.isSuccessful && step1Body.isNotBlank()) {
                        return step1Body
                    }
                    if (step1.code == 401) {
                        val authHeaders = step1.headers("WWW-Authenticate")
                        val challengePair = extractNtlmChallengeFromHeaders(authHeaders)
                        if (challengePair != null) {
                            val (scheme, challengeB64) = challengePair
                            val type3 = VncNativeBridge.nativeNtlmBuildAuthenticate(
                                challengeB64 = challengeB64,
                                username = identity.username,
                                password = identity.password,
                                domain = identity.domain,
                                spnHost = spnHost
                            )
                            val authReq = Request.Builder()
                                .url(wsmanUrl)
                                .header("Authorization", "$scheme $type3")
                                .header("Connection", "Keep-Alive")
                                .post(soapXml.toRequestBody(soapMediaType))
                                .build()
                            http.newCall(authReq).execute().use { step2 ->
                                val step2Body = step2.body?.string().orEmpty()
                                if (step2.isSuccessful) {
                                    return step2Body
                                }
                                if (step2.code == 401) {
                                    error("Hyper-V WinRM HTTPS NTLMv2 authentication rejected (HTTP 401)")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Step 2: Basic Auth over TLS fallback
        val principal = if (identity.domain.isBlank()) identity.username else "${identity.domain}\\${identity.username}"
        val basic = Base64.getEncoder()
            .encodeToString("$principal:${identity.password}".toByteArray(StandardCharsets.UTF_8))
        val basicReq = Request.Builder()
            .url(wsmanUrl)
            .apply {
                if (identity.username.isNotBlank()) {
                    header("Authorization", "Basic $basic")
                }
            }
            .post(soapXml.toRequestBody(soapMediaType))
            .build()

        http.newCall(basicReq).execute().use { resp ->
            if (!resp.isSuccessful) error("Hyper-V WinRM HTTPS HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun extractNtlmChallengeFromHeaders(headers: List<String>): Pair<String, String>? {
        for (hdr in headers) {
            for (part in hdr.split(',')) {
                val trimmed = part.trim()
                for (prefix in listOf("Negotiate ", "NTLM ")) {
                    if (trimmed.startsWith(prefix, ignoreCase = true)) {
                        val tok = trimmed.substring(prefix.length).trim()
                        if (tok.isNotEmpty() && ' ' !in tok) {
                            return prefix.trim() to tok
                        }
                    }
                }
            }
        }
        return null
    }

    private fun fetchHyperVWinRmHttp(
        host: String,
        port: Int,
        path: String,
        hostLabel: String,
        identity: WindowsIdentity
    ): List<CloudManagedResource> {
        val normPath = if (path.startsWith("/")) path else "/$path"
        val toUrl = "http://$host:$port$normPath"
        val resourceUri = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem"
        val enumerateSoap = buildWinRmEnumerateSoap(toUrl = toUrl, resourceUri = resourceUri)
        val firstXml = VncNativeBridge.nativeWinrmWsmanPost(
            host = host,
            port = port,
            path = normPath,
            username = identity.username,
            password = identity.password,
            domain = identity.domain,
            soapXml = enumerateSoap,
            timeoutMs = 10_000
        )
        extractWsmanFaultSummary(firstXml)?.let { error("WinRM SOAP Fault: $it") }
        var items = parseHyperVWsmanXml(firstXml, hostLabel)
        if (items.isEmpty() && !firstXml.contains("EndOfSequence", ignoreCase = true)) {
            val ctx = extractWsmanEnumerationContext(firstXml)
            if (!ctx.isNullOrBlank()) {
                val pullSoap = buildWinRmPullSoap(toUrl = toUrl, resourceUri = resourceUri, enumerationContext = ctx)
                val pullXml = VncNativeBridge.nativeWinrmWsmanPost(
                    host = host,
                    port = port,
                    path = normPath,
                    username = identity.username,
                    password = identity.password,
                    domain = identity.domain,
                    soapXml = pullSoap,
                    timeoutMs = 10_000
                )
                extractWsmanFaultSummary(pullXml)?.let { error("WinRM Pull Fault: $it") }
                items = parseHyperVWsmanXml(pullXml, hostLabel)
            }
        }
        return items
    }

    private fun fetchHyperVWinRmHttps(
        wsmanUrl: String,
        spnHost: String,
        hostLabel: String,
        identity: WindowsIdentity,
        http: OkHttpClient
    ): List<CloudManagedResource> {
        val resourceUri = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem"
        val enumerateSoap = buildWinRmEnumerateSoap(toUrl = wsmanUrl, resourceUri = resourceUri)
        val firstXml = postWsmanHttpsNtlmOrBasic(wsmanUrl, spnHost, enumerateSoap, identity, http)
        extractWsmanFaultSummary(firstXml)?.let { error("WinRM HTTPS SOAP Fault: $it") }
        var items = parseHyperVWsmanXml(firstXml, hostLabel)
        if (items.isEmpty() && !firstXml.contains("EndOfSequence", ignoreCase = true)) {
            val ctx = extractWsmanEnumerationContext(firstXml)
            if (!ctx.isNullOrBlank()) {
                val pullSoap = buildWinRmPullSoap(toUrl = wsmanUrl, resourceUri = resourceUri, enumerationContext = ctx)
                val pullXml = postWsmanHttpsNtlmOrBasic(wsmanUrl, spnHost, pullSoap, identity, http)
                items = parseHyperVWsmanXml(pullXml, hostLabel)
            }
        }
        return items
    }

    private fun runHyperVSshPowerShell(
        host: String,
        identity: WindowsIdentity,
        command: String
    ): String {
        val baseUser = identity.username.ifBlank { "Administrator" }
        val candidates = if (identity.domain.isNotBlank() && '\\' !in baseUser && '@' !in baseUser) {
            listOf("${identity.domain}\\$baseUser", baseUser)
        } else {
            listOf(baseUser)
        }
        var lastErr: Throwable? = null
        for (sshUser in candidates) {
            val res = runCatching {
                VncNativeBridge.nativeSshExecCommand(
                    host = host,
                    port = 22,
                    username = sshUser,
                    password = identity.password,
                    privateKeyPem = identity.privateKeyPem,
                    passphrase = identity.keyPassphrase,
                    command = command,
                    timeoutSecs = 15
                )
            }
            if (res.isSuccess) return res.getOrThrow()
            lastErr = res.exceptionOrNull()
        }
        throw lastErr ?: IllegalStateException("Windows OpenSSH command failed")
    }

    private fun fetchHyperVInventory(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        allowSelfSignedTls: Boolean
    ): List<CloudManagedResource> {
        val bareHost = extractBareHyperVHost(host.ifBlank { settings.apiEndpointUrl })
        require(bareHost.isNotBlank()) {
            "Hyper-V host address is empty. Edit the connection and set the Hyper-V Server IP or hostname."
        }
        val identity = resolveWindowsIdentity(secret, settings.realmOrDomain)
        require(identity.username.isNotBlank() || identity.privateKeyPem.isNotBlank()) {
            "Hyper-V requires Windows Administrator credentials. Assign or inherit a Vault Credential on this connection, or connect directly by VM GUID on TCP 2179."
        }

        val explicitEndpoint = sanitizedHyperVEndpoint(settings.apiEndpointUrl, bareHost)
        val httpsClient = clientFor(allowSelfSignedTls = true)

        if (explicitEndpoint.isNotBlank()) {
            val parsed = runCatching { URI(explicitEndpoint) }.getOrNull()
            val scheme = parsed?.scheme?.lowercase(Locale.ROOT).orEmpty()
            val endpointHost = parsed?.host?.takeIf { it.isNotBlank() } ?: bareHost
            val endpointPath = parsed?.path?.takeIf { it.isNotBlank() } ?: "/wsman"
            if (scheme == "http") {
                val port = if (parsed != null && parsed.port > 0) parsed.port else 5985
                return fetchHyperVWinRmHttp(endpointHost, port, endpointPath, bareHost, identity)
            } else if (scheme == "https") {
                val port = if (parsed != null && parsed.port > 0) parsed.port else 5986
                val wsmanUrl = normalizeWinRmUrl(endpointHost, "https://$endpointHost:$port$endpointPath")
                return fetchHyperVWinRmHttps(wsmanUrl, endpointHost, bareHost, identity, clientFor(allowSelfSignedTls))
            }
        }

        val transportErrors = mutableListOf<String>()

        if (identity.username.isNotBlank()) {
            // Tier 1: WinRM HTTP :5985 with NTLMv2 + [MS-WSMV] SPNEGO RC4 Sealing
            runCatching {
                fetchHyperVWinRmHttp(bareHost, 5985, "/wsman", bareHost, identity)
            }.onSuccess { return it }
                .onFailure { transportErrors += "WinRM :5985 (${it.message ?: it::class.simpleName})" }

            // Tier 2: WinRM HTTPS :5986 with 2-step NTLMv2 + Permissive TLS + Basic fallback
            runCatching {
                fetchHyperVWinRmHttps("https://$bareHost:5986/wsman", bareHost, bareHost, identity, httpsClient)
            }.onSuccess { return it }
                .onFailure { transportErrors += "WinRM :5986 (${it.message ?: it::class.simpleName})" }
        }

        // Tier 3: OpenSSH :22 PowerShell Get-VM fallback
        runCatching {
            val psCmd = "powershell.exe -NoProfile -NonInteractive -Command \"\$ErrorActionPreference='Stop'; Get-VM | ForEach-Object { [PSCustomObject]@{ Id = \$_.Id.Guid.ToString(); Name = \$_.Name; State = \$_.State.ToString(); CpuCount = [int]\$_.ProcessorCount; MemAssignedMB = [int](\$_.MemoryAssigned / 1MB); MemStartupMB = [int](\$_.MemoryStartup / 1MB) } } | ConvertTo-Json -Compress\""
            val rawOut = runHyperVSshPowerShell(bareHost, identity, psCmd)
            parseHyperVPowerShellJson(rawOut, bareHost)
        }.onSuccess { return it }
            .onFailure { transportErrors += "SSH :22 (${it.message ?: it::class.simpleName})" }

        error(
            "Unable to query Hyper-V host $bareHost. Enable WinRM (`Enable-PSRemoting -Force`) on TCP 5985/5986 or OpenSSH on TCP 22, or connect directly by VM GUID on TCP 2179. Details: ${transportErrors.joinToString(" • ")}"
        )
    }

    private fun validateHyperVStateChangeXml(xml: String, vmName: String, action: VmPowerAction) {
        extractWsmanFaultSummary(xml)?.let { error("Hyper-V SOAP Fault: $it") }
        val retCode = extractWmiReturnValue(xml)
        if (retCode != null && retCode != 0 && retCode != 4096) {
            val detail = when (retCode) {
                32769 -> "Access denied (32769)"
                32775 -> "Invalid VM state for ${action.name} (32775)"
                else -> "WMI return code $retCode"
            }
            error("Hyper-V ${action.name} on $vmName failed: $detail")
        }
    }

    private fun controlHyperVVm(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction,
        allowSelfSignedTls: Boolean
    ): String {
        val bareHost = extractBareHyperVHost(host.ifBlank { settings.apiEndpointUrl })
        val identity = resolveWindowsIdentity(secret, settings.realmOrDomain)
        val stateCode = when (action) {
            VmPowerAction.START -> 2
            VmPowerAction.STOP -> 3
            VmPowerAction.SAVE -> 32769
            VmPowerAction.REBOOT -> 10
        }

        val explicitEndpoint = sanitizedHyperVEndpoint(settings.apiEndpointUrl, bareHost)
        val httpsClient = clientFor(allowSelfSignedTls = true)

        if (explicitEndpoint.isNotBlank()) {
            val parsed = runCatching { URI(explicitEndpoint) }.getOrNull()
            val scheme = parsed?.scheme?.lowercase(Locale.ROOT).orEmpty()
            val endpointHost = parsed?.host?.takeIf { it.isNotBlank() } ?: bareHost
            val endpointPath = parsed?.path?.takeIf { it.isNotBlank() } ?: "/wsman"
            if (scheme == "http") {
                val port = if (parsed != null && parsed.port > 0) parsed.port else 5985
                val normPath = if (endpointPath.startsWith("/")) endpointPath else "/$endpointPath"
                val toUrl = "http://$endpointHost:$port$normPath"
                val soap = buildHyperVStateChangeSoap(toUrl, resource.id, stateCode)
                val xml = VncNativeBridge.nativeWinrmWsmanPost(
                    endpointHost, port, normPath, identity.username, identity.password, identity.domain, soap, 12_000
                )
                validateHyperVStateChangeXml(xml, resource.name, action)
                return "Hyper-V ${action.name} -> ${resource.name}"
            } else if (scheme == "https") {
                val port = if (parsed != null && parsed.port > 0) parsed.port else 5986
                val wsmanUrl = normalizeWinRmUrl(endpointHost, "https://$endpointHost:$port$endpointPath")
                val soap = buildHyperVStateChangeSoap(wsmanUrl, resource.id, stateCode)
                val xml = postWsmanHttpsNtlmOrBasic(wsmanUrl, endpointHost, soap, identity, clientFor(allowSelfSignedTls))
                validateHyperVStateChangeXml(xml, resource.name, action)
                return "Hyper-V ${action.name} -> ${resource.name}"
            }
        }

        val transportErrors = mutableListOf<String>()

        if (identity.username.isNotBlank()) {
            runCatching {
                val toUrl = "http://$bareHost:5985/wsman"
                val soap = buildHyperVStateChangeSoap(toUrl, resource.id, stateCode)
                val xml = VncNativeBridge.nativeWinrmWsmanPost(
                    bareHost, 5985, "/wsman", identity.username, identity.password, identity.domain, soap, 12_000
                )
                validateHyperVStateChangeXml(xml, resource.name, action)
                "Hyper-V ${action.name} -> ${resource.name}"
            }.onSuccess { return it }
                .onFailure { transportErrors += "WinRM :5985 (${it.message})" }

            runCatching {
                val wsmanUrl = "https://$bareHost:5986/wsman"
                val soap = buildHyperVStateChangeSoap(wsmanUrl, resource.id, stateCode)
                val xml = postWsmanHttpsNtlmOrBasic(wsmanUrl, bareHost, soap, identity, httpsClient)
                validateHyperVStateChangeXml(xml, resource.name, action)
                "Hyper-V ${action.name} -> ${resource.name}"
            }.onSuccess { return it }
                .onFailure { transportErrors += "WinRM :5986 (${it.message})" }
        }

        runCatching {
            val cleanGuid = resource.id.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }
            val safeName = resource.name.replace("'", "''")
            val selector = if (cleanGuid.length >= 32) "Get-VM -Id '$cleanGuid'" else "Get-VM -Name '$safeName'"
            val verb = when (action) {
                VmPowerAction.START -> "Start-VM -ErrorAction Stop"
                VmPowerAction.STOP -> "Stop-VM -TurnOff -Force -ErrorAction Stop"
                VmPowerAction.SAVE -> "Save-VM -ErrorAction Stop"
                VmPowerAction.REBOOT -> "Restart-VM -Force -ErrorAction Stop"
            }
            runHyperVSshPowerShell(
                bareHost,
                identity,
                "powershell.exe -NoProfile -NonInteractive -Command \"$selector | $verb\""
            )
            "Hyper-V ${action.name} (SSH) -> ${resource.name}"
        }.onSuccess { return it }
            .onFailure { transportErrors += "SSH :22 (${it.message})" }

        error("Hyper-V ${action.name} failed across all transports: ${transportErrors.joinToString(" | ")}")
    }

    private fun fetchWindowsTerminalServicesSessions(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        allowSelfSignedTls: Boolean
    ): List<CloudManagedResource> {
        val http = clientFor(allowSelfSignedTls)
        val url = normalizeWinRmUrl(host, settings.apiEndpointUrl)
        val resourceUri = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/TerminalServices/Win32_TerminalServicesSession"
        val soap = buildWinRmEnumerateSoap(toUrl = url, resourceUri = resourceUri)
        val basic = Base64.getEncoder()
            .encodeToString("${secret.username}:${secret.password}".toByteArray(StandardCharsets.UTF_8))

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Basic $basic")
            .post(soap.toRequestBody("application/soap+xml;charset=UTF-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Windows TS WinRM HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val sidRegex = Regex("<(?:\\w+:)?SessionId>(\\d+)</(?:\\w+:)?SessionId>")
            val userRegex = Regex("<(?:\\w+:)?UserName>([^<]+)</(?:\\w+:)?UserName>")
            val sids = sidRegex.findAll(body).map { it.groupValues[1] }.toList()
            val users = userRegex.findAll(body).map { it.groupValues[1] }.toList()
            return sids.mapIndexed { idx, sid ->
                val u = users.getOrElse(idx) { "Session-$sid" }
                CloudManagedResource(
                    id = sid,
                    name = "${unescapeXml(u)} (Session #$sid)",
                    nodeOrZone = host,
                    powerState = "ACTIVE",
                    cpuRamSummary = "RDP-Tcp#$sid",
                    addressOrSessionHost = host,
                    preferredConsoleProtocol = VaultProtocolType.RDP,
                    isTerminalServicesSession = true
                )
            }
        }
    }

    private fun disconnectOrLogoffRdsSession(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction,
        allowSelfSignedTls: Boolean
    ): String {
        val http = clientFor(allowSelfSignedTls)
        val url = normalizeWinRmUrl(host, settings.apiEndpointUrl)
        val basic = Base64.getEncoder()
            .encodeToString("${secret.username}:${secret.password}".toByteArray(StandardCharsets.UTF_8))
        val numericSessionId = resource.id.filter { it.isDigit() }
            .ifBlank { error("Invalid RDS Session ID: ${resource.id}") }
        val cmd = if (action == VmPowerAction.STOP) "logoff $numericSessionId" else "tsdiscon $numericSessionId"
        val resourceUri = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/Win32_Process"
        val soap = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:wsman="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                        xmlns:p="$resourceUri">
              <s:Header>
                <wsa:To>${escapeXml(url)}</wsa:To>
                <wsman:ResourceURI s:mustUnderstand="true">$resourceUri</wsman:ResourceURI>
                <wsa:Action s:mustUnderstand="true">$resourceUri/Create</wsa:Action>
                <wsman:MaxEnvelopeSize s:mustUnderstand="true">153600</wsman:MaxEnvelopeSize>
                <wsa:MessageID>uuid:${UUID.randomUUID()}</wsa:MessageID>
                <wsa:ReplyTo>
                  <wsa:Address s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:Address>
                </wsa:ReplyTo>
                <wsman:OperationTimeout>PT60.000S</wsman:OperationTimeout>
              </s:Header>
              <s:Body>
                <p:Create_INPUT>
                  <p:CommandLine>${escapeXml(cmd)}</p:CommandLine>
                </p:Create_INPUT>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Basic $basic")
            .post(soap.toRequestBody("application/soap+xml;charset=UTF-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("RDS session control HTTP ${resp.code}")
            return "RDS executed '$cmd' on $host"
        }
    }

    private fun buildWinRmEnumerateSoap(toUrl: String, resourceUri: String): String = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:wsman="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                    xmlns:wsen="http://schemas.xmlsoap.org/ws/2004/09/enumeration">
          <s:Header>
            <wsa:To>${escapeXml(toUrl)}</wsa:To>
            <wsman:ResourceURI s:mustUnderstand="true">$resourceUri</wsman:ResourceURI>
            <wsa:Action s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/09/enumeration/Enumerate</wsa:Action>
            <wsman:MaxEnvelopeSize s:mustUnderstand="true">512000</wsman:MaxEnvelopeSize>
            <wsa:MessageID>uuid:${UUID.randomUUID()}</wsa:MessageID>
            <wsa:ReplyTo>
              <wsa:Address s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:Address>
            </wsa:ReplyTo>
            <wsman:OperationTimeout>PT60.000S</wsman:OperationTimeout>
          </s:Header>
          <s:Body>
            <wsen:Enumerate>
              <wsman:OptimizeEnumeration/>
              <wsman:MaxElements>200</wsman:MaxElements>
            </wsen:Enumerate>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun escapeXml(input: String): String = buildString(input.length + 8) {
        for (ch in input) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun unescapeXml(input: String): String = input
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }
}
