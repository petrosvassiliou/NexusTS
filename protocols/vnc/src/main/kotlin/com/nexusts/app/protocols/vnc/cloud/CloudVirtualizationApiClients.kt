package com.nexusts.app.protocols.vnc.cloud

import com.nexusts.app.core.vault.credentials.ResolvedSessionSecret
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * - **Microsoft Azure ARM** (`Microsoft.Compute/virtualMachines?api-version=2024-03-01`)
 * - **Microsoft Hyper-V** (WinRM HTTPS `5986` `Msvm_ComputerSystem` CIM enumeration & `RequestStateChange`)
 * - **Windows Terminal Services / RDS** (WinRM `Win32_TerminalServicesSession` active session listing & logoff)
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

    private fun fetchProxmoxInventory(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val base = settings.apiEndpointUrl.ifBlank { "https://$host:8006" }.trimEnd('/')
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
                CloudManagedResource(
                    id = "$vmType/$vmid",
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
        val base = settings.apiEndpointUrl.ifBlank { "https://$host:8006" }.trimEnd('/')
        val verb = when (action) {
            VmPowerAction.START -> "start"
            VmPowerAction.STOP -> "stop"
            VmPowerAction.REBOOT -> "reboot"
        }
        val req = Request.Builder()
            .url("$base/api2/json/nodes/${resource.nodeOrZone}/${resource.id}/status/$verb")
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
        val base = settings.apiEndpointUrl.ifBlank { "https://$host" }.trimEnd('/')
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
        val base = settings.apiEndpointUrl.ifBlank { "https://$host" }.trimEnd('/')
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

    private fun parseEc2DescribeInstancesXml(xml: String, region: String): List<CloudManagedResource> {
        val itemRegex = Regex("<item>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL))
        val idRegex = Regex("<instanceId>(i-[a-z0-9]+)</instanceId>")
        val typeRegex = Regex("<instanceType>([^<]+)</instanceType>")
        val ipRegex = Regex("<privateIpAddress>([^<]+)</privateIpAddress>")
        val stateRegex = Regex("<instanceState>.*?<name>([^<]+)</name>.*?</instanceState>", setOf(RegexOption.DOT_MATCHES_ALL))
        val platformRegex = Regex("<platform>([^<]+)</platform>")

        val results = mutableListOf<CloudManagedResource>()
        val seenIds = mutableSetOf<String>()
        for (match in itemRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val instId = idRegex.find(block)?.groupValues?.get(1) ?: continue
            if (!seenIds.add(instId)) continue
            val instType = typeRegex.find(block)?.groupValues?.get(1) ?: "m6i.large"
            val privIp = ipRegex.find(block)?.groupValues?.get(1) ?: "0.0.0.0"
            val state = (stateRegex.find(block)?.groupValues?.get(1) ?: "running").uppercase(Locale.ROOT)
            val isWindows = platformRegex.find(block)?.groupValues?.get(1)?.contains("windows", ignoreCase = true) == true
            results += CloudManagedResource(
                id = instId,
                name = instId,
                nodeOrZone = region,
                powerState = state,
                cpuRamSummary = instType,
                addressOrSessionHost = privIp,
                preferredConsoleProtocol = if (isWindows) VaultProtocolType.RDP else VaultProtocolType.SSH
            )
        }
        return results
    }

    // =========================================================================
    // 4. Microsoft Azure Compute ARM REST API (`2024-03-01`)
    // =========================================================================

    private fun fetchAzureVmInventory(
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val subId = settings.subscriptionOrTenantId
        require(subId.isNotBlank()) { "Azure Subscription ID required" }
        val url = "https://management.azure.com/subscriptions/$subId/providers/Microsoft.Compute/virtualMachines?api-version=2024-03-01"
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
                CloudManagedResource(
                    id = id,
                    name = name,
                    nodeOrZone = location,
                    powerState = "RUNNING",
                    cpuRamSummary = "$vmSize ($osType)",
                    addressOrSessionHost = name,
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
        val url = "https://management.azure.com${resource.id}/$armAction?api-version=2024-03-01"
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

    private fun fetchHyperVInventory(
        host: String,
        settings: ProtocolSpecificSettings.CloudVirtualization,
        secret: ResolvedSessionSecret
    ): List<CloudManagedResource> {
        val url = settings.apiEndpointUrl.ifBlank { "https://$host:5986/wsman" }
        val soap = buildWinRmEnumerateSoap("http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem")
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
            val elemRegex = Regex("<p:ElementName>([^<]+)</p:ElementName>")
            val nameRegex = Regex("<p:Name>([^<]+)</p:Name>")
            val names = elemRegex.findAll(body).map { it.groupValues[1] }.toList()
            val guids = nameRegex.findAll(body).map { it.groupValues[1] }.toList()
            return names.mapIndexed { index, vmName ->
                CloudManagedResource(
                    id = guids.getOrElse(index) { "hv-$index" },
                    name = vmName,
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
        val url = settings.apiEndpointUrl.ifBlank { "https://$host:5986/wsman" }
        val basic = Base64.getEncoder()
            .encodeToString("${secret.username}:${secret.password}".toByteArray(StandardCharsets.UTF_8))
        val invokeXml = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
              <s:Body>
                <p:RequestStateChange_INPUT xmlns:p="http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem">
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
        val url = settings.apiEndpointUrl.ifBlank { "https://$host:5986/wsman" }
        val soap = buildWinRmEnumerateSoap("http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/TerminalServices/Win32_TerminalServicesSession")
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
            val sidRegex = Regex("<p:SessionId>(\\d+)</p:SessionId>")
            val userRegex = Regex("<p:UserName>([^<]+)</p:UserName>")
            val sids = sidRegex.findAll(body).map { it.groupValues[1] }.toList()
            val users = userRegex.findAll(body).map { it.groupValues[1] }.toList()
            return sids.mapIndexed { idx, sid ->
                val u = users.getOrElse(idx) { "Session-$sid" }
                CloudManagedResource(
                    id = sid,
                    name = "$u (Session #$sid)",
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
        val url = settings.apiEndpointUrl.ifBlank { "https://$host:5986/wsman" }
        val basic = Base64.getEncoder()
            .encodeToString("${secret.username}:${secret.password}".toByteArray(StandardCharsets.UTF_8))
        val cmd = if (action == VmPowerAction.STOP) "logoff ${resource.id}" else "tsdiscon ${resource.id}"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Basic $basic")
            .post("<CommandLine>$cmd</CommandLine>".toRequestBody("application/soap+xml;charset=UTF-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("RDS session control HTTP ${resp.code}")
            return "RDS executed '$cmd' on $host"
        }
    }

    private fun buildWinRmEnumerateSoap(resourceUri: String): String = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                    xmlns:wsman="http://schemas.dmtf.org/wbem/wsman/1/wsman.xsd"
                    xmlns:wsen="http://schemas.xmlsoap.org/ws/2004/09/enumeration">
          <s:Header>
            <wsa:Action s:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2004/09/enumeration/Enumerate</wsa:Action>
            <wsman:ResourceURI s:mustUnderstand="true">$resourceUri</wsman:ResourceURI>
          </s:Header>
          <s:Body><wsen:Enumerate/></s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }
}
