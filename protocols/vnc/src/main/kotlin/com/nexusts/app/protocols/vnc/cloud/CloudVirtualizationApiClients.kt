package com.nexusts.app.protocols.vnc.cloud

import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
enum class VmPowerAction {
    START,
    STOP,
    REBOOT
}

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

/**
 * Production REST / SOAP / SigV4 Client Hub for Enterprise Cloud & Virtualization Hypervisors:
 * - **Proxmox VE** (`/api2/json/cluster/resources` + `/status/{start|stop|reboot}`)
 * - **VMware vSphere / ESXi** (`/api/session` + `/api/vcenter/vm` + `/power?action=...`)
 * - **AWS EC2** (`DescribeInstances` / `StartInstances` / `StopInstances` / `RebootInstances` signed via **AWS SigV4 `AWS4-HMAC-SHA256`**)
 * - **Microsoft Azure ARM** (`Microsoft.Compute/virtualMachines?api-version=2024-03-01&$expand=instanceView`)
 * - **Microsoft Hyper-V** (WinRM HTTPS `5986` `Msvm_ComputerSystem` CIM enumeration & `RequestStateChange`)
 * - **Windows Terminal Services / RDS** (WinRM `Win32_TerminalServicesSession` active session listing & `Win32_Process/Create` logoff/disconnect)
 */
object CloudVirtualizationHubClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun listInventory(
        protocol: VaultProtocolType,
        endpointHost: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): Result<List<CloudManagedResource>> = withContext(Dispatchers.IO) {
        runCatching {
            when (protocol) {
                VaultProtocolType.PROXMOX_VE -> fetchProxmoxInventory(endpointHost, settings, secret)
                VaultProtocolType.VMWARE_VSPHERE -> fetchVsphereInventory(endpointHost, settings, secret)
                VaultProtocolType.AWS_EC2 -> fetchAwsEc2Inventory(settings, secret)
                VaultProtocolType.AZURE_VM -> fetchAzureVmInventory(settings, secret)
                VaultProtocolType.HYPER_V -> fetchHyperVInventory(endpointHost, settings, secret)
                VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> fetchWindowsTerminalServicesSessions(endpointHost, settings, secret)
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
        action: VmPowerAction
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (protocol) {
                VaultProtocolType.PROXMOX_VE -> controlProxmoxVm(endpointHost, settings, secret, resource, action)
                VaultProtocolType.VMWARE_VSPHERE -> controlVsphereVm(endpointHost, settings, secret, resource, action)
                VaultProtocolType.AWS_EC2 -> controlAwsEc2Instance(settings, secret, resource, action)
                VaultProtocolType.AZURE_VM -> controlAzureVm(settings, secret, resource, action)
                VaultProtocolType.HYPER_V -> controlHyperVVm(endpointHost, settings, secret, resource, action)
                VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> disconnectOrLogoffRdsSession(endpointHost, settings, secret, resource, action)
                else -> "Unsupported provider ${protocol.name}"
            }
        }
    }

    // =========================================================================
    // 1. Proxmox VE REST API v2
    // =========================================================================

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

    private fun fetchProxmoxInventory(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val base = normalizeProxmoxBaseUrl(host, settings.apiEndpointUrl)
        val req = Request.Builder()
            .url("$base/api2/json/cluster/resources?type=vm")
            .header("Authorization", buildProxmoxAuthHeader(secret))
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Proxmox VE HTTP ${resp.code}")
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val arr = root["data"]?.jsonArray ?: return emptyList()
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
                    addressOrSessionHost = host,
                    preferredConsoleProtocol = if (vmType == "lxc") VaultProtocolType.SSH else VaultProtocolType.VNC
                )
            }
        }
    }

    private fun controlProxmoxVm(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction
    ): String {
        val base = normalizeProxmoxBaseUrl(host, settings.apiEndpointUrl)
        val verb = when (action) {
            VmPowerAction.START -> "start"
            VmPowerAction.STOP -> "stop"
            VmPowerAction.REBOOT -> "reboot"
        }
        val vmPath = if ('/' in resource.id) resource.id else "qemu/${resource.id.removePrefix("vm-")}"
        val req = Request.Builder()
            .url("$base/api2/json/nodes/${resource.nodeOrZone}/$vmPath/status/$verb")
            .header("Authorization", buildProxmoxAuthHeader(secret))
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Proxmox power action HTTP ${resp.code}")
            return "PVE UPID queued ($verb on ${resource.name})"
        }
    }

    private fun buildProxmoxAuthHeader(secret: ResolvedSessionSecret): String {
        return if (secret.password.startsWith("PVEAPIToken=")) {
            secret.password
        } else {
            "PVEAPIToken=${secret.username}=${secret.password}"
        }
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

    private fun acquireVsphereSessionToken(base: String, secret: ResolvedSessionSecret): String {
        val basic = Base64.getEncoder()
            .encodeToString("${secret.username}:${secret.password}".toByteArray(StandardCharsets.UTF_8))
        val sessionReq = Request.Builder()
            .url("$base/api/session")
            .header("Authorization", "Basic $basic")
            .post("".toRequestBody(null))
            .build()

        httpClient.newCall(sessionReq).execute().use { resp ->
            if (!resp.isSuccessful) error("vSphere /api/session HTTP ${resp.code}")
            return resp.body?.string().orEmpty().trim().trim('"')
        }
    }

    private fun fetchVsphereInventory(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val base = normalizeHttpsEndpoint(host, settings.apiEndpointUrl)
        val token = acquireVsphereSessionToken(base, secret)
        val req = Request.Builder()
            .url("$base/api/vcenter/vm")
            .header("vmware-api-session-id", token)
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
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
        action: VmPowerAction
    ): String {
        val base = normalizeHttpsEndpoint(host, settings.apiEndpointUrl)
        val token = acquireVsphereSessionToken(base, secret)
        val act = when (action) {
            VmPowerAction.START -> "start"
            VmPowerAction.STOP -> "stop"
            VmPowerAction.REBOOT -> "reset"
        }
        val req = Request.Builder()
            .url("$base/api/vcenter/vm/${resource.id}/power?action=$act")
            .header("vmware-api-session-id", token)
            .post("".toRequestBody(null))
            .build()

        httpClient.newCall(req).execute().use { resp ->
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
            VmPowerAction.STOP -> "StopInstances"
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

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("AWS EC2 HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /**
     * Depth-balanced EC2 `DescribeInstancesResponse` XML parser that extracts top-level `<item>` blocks
     * inside `<instancesSet>` without truncating on nested `<groupSet><item>`, `<networkInterfaceSet><item>`,
     * or `<tagSet><item>` elements.
     */
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
    // 4. Microsoft Azure Compute ARM REST API (`2024-03-01` + `$expand=instanceView`)
    // =========================================================================

    private fun fetchAzureVmInventory(
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val subId = settings.subscriptionOrAccountId.trim()
        require(subId.isNotBlank()) { "Azure Subscription ID required" }
        val url = "https://management.azure.com/subscriptions/$subId/providers/Microsoft.Compute/virtualMachines?api-version=2024-03-01&\$expand=instanceView"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${secret.password}")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
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
            VmPowerAction.REBOOT -> "restart"
        }
        val resourcePath = if (resource.id.startsWith("/")) {
            resource.id
        } else {
            val subId = settings.subscriptionOrAccountId.trim()
            val rg = settings.regionOrCluster.ifBlank { "default" }
            "/subscriptions/$subId/resourceGroups/$rg/providers/Microsoft.Compute/virtualMachines/${resource.id}"
        }
        val url = "https://management.azure.com$resourcePath/$armAction?api-version=2024-03-01"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${secret.password}")
            .post("".toRequestBody(null))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Azure VM $armAction HTTP ${resp.code}")
            return "Azure ARM $armAction accepted (${resource.name})"
        }
    }

    // =========================================================================
    // 5. Microsoft Hyper-V & 6. Windows Terminal Services (WinRM HTTPS 5986)
    // =========================================================================

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

    private fun fetchHyperVInventory(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val url = normalizeWinRmUrl(host, settings.apiEndpointUrl)
        val resourceUri = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem"
        val soap = buildWinRmEnumerateSoap(toUrl = url, resourceUri = resourceUri)
        val basic = Base64.getEncoder()
            .encodeToString("${secret.username}:${secret.password}".toByteArray(StandardCharsets.UTF_8))

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Basic $basic")
            .post(soap.toRequestBody("application/soap+xml;charset=UTF-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Hyper-V WinRM HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val systemBlockRegex = Regex("<(?:\\w+:)?Msvm_ComputerSystem[^>]*>(.*?)</(?:\\w+:)?Msvm_ComputerSystem>", setOf(RegexOption.DOT_MATCHES_ALL))
            val elemRegex = Regex("<(?:\\w+:)?ElementName>([^<]+)</(?:\\w+:)?ElementName>")
            val nameRegex = Regex("<(?:\\w+:)?Name>([^<]+)</(?:\\w+:)?Name>")
            val captionRegex = Regex("<(?:\\w+:)?Caption>([^<]+)</(?:\\w+:)?Caption>")
            val stateRegex = Regex("<(?:\\w+:)?EnabledState>(\\d+)</(?:\\w+:)?EnabledState>")

            val blocks = systemBlockRegex.findAll(body).map { it.groupValues[1] }.toList()
            if (blocks.isNotEmpty()) {
                return blocks.mapNotNull { block ->
                    val caption = captionRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                    if (caption.equals("Hosting Computer System", ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    val vmName = elemRegex.find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                    val vmGuid = nameRegex.find(block)?.groupValues?.get(1)?.trim() ?: vmName
                    val stateCode = stateRegex.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 2
                    val power = when (stateCode) {
                        2 -> "RUNNING"
                        3, 6 -> "STOPPED"
                        32768 -> "PAUSED"
                        32769 -> "SAVED"
                        else -> "UNKNOWN"
                    }
                    CloudManagedResource(
                        id = unescapeXml(vmGuid),
                        name = unescapeXml(vmName),
                        nodeOrZone = host,
                        powerState = power,
                        cpuRamSummary = "Hyper-V Gen2 VM",
                        addressOrSessionHost = host,
                        preferredConsoleProtocol = VaultProtocolType.RDP
                    )
                }
            }

            val names = elemRegex.findAll(body).map { it.groupValues[1] }.toList()
            val guids = nameRegex.findAll(body).map { it.groupValues[1] }.toList()
            return names.mapIndexed { index, vmName ->
                CloudManagedResource(
                    id = guids.getOrElse(index) { "hv-$index" },
                    name = unescapeXml(vmName),
                    nodeOrZone = host,
                    powerState = "RUNNING",
                    cpuRamSummary = "Hyper-V Gen2 VM",
                    addressOrSessionHost = host,
                    preferredConsoleProtocol = VaultProtocolType.RDP
                )
            }
        }
    }

    private fun controlHyperVVm(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret,
        resource: CloudManagedResource,
        action: VmPowerAction
    ): String {
        val stateCode = when (action) {
            VmPowerAction.START -> 2 // Enabled
            VmPowerAction.STOP -> 3  // Disabled
            VmPowerAction.REBOOT -> 10 // Reboot
        }
        val url = normalizeWinRmUrl(host, settings.apiEndpointUrl)
        val resourceUri = "http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem"
        val basic = Base64.getEncoder()
            .encodeToString("${secret.username}:${secret.password}".toByteArray(StandardCharsets.UTF_8))
        val invokeXml = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:wsman="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                        xmlns:p="$resourceUri">
              <s:Header>
                <wsa:To>${escapeXml(url)}</wsa:To>
                <wsman:ResourceURI s:mustUnderstand="true">$resourceUri</wsman:ResourceURI>
                <wsa:Action s:mustUnderstand="true">$resourceUri/RequestStateChange</wsa:Action>
                <wsman:MaxEnvelopeSize s:mustUnderstand="true">153600</wsman:MaxEnvelopeSize>
                <wsa:MessageID>uuid:${UUID.randomUUID()}</wsa:MessageID>
                <wsa:ReplyTo>
                  <wsa:Address s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:Address>
                </wsa:ReplyTo>
                <wsman:SelectorSet>
                  <wsman:Selector Name="Name">${escapeXml(resource.id)}</wsman:Selector>
                </wsman:SelectorSet>
                <wsman:OperationTimeout>PT60.000S</wsman:OperationTimeout>
              </s:Header>
              <s:Body>
                <p:RequestStateChange_INPUT>
                  <p:RequestedState>$stateCode</p:RequestedState>
                </p:RequestStateChange_INPUT>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Basic $basic")
            .post(invokeXml.toRequestBody("application/soap+xml;charset=UTF-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Hyper-V state change HTTP ${resp.code}")
            return "Hyper-V RequestedState=$stateCode -> ${resource.name}"
        }
    }

    private fun fetchWindowsTerminalServicesSessions(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
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

        httpClient.newCall(req).execute().use { resp ->
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
        action: VmPowerAction
    ): String {
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

        httpClient.newCall(req).execute().use { resp ->
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

