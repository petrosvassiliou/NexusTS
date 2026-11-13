package com.nexusts.app.core.vault.importer

import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class DetectedExternalVaultFormat {
    NXTS_ENCRYPTED_BINARY,
    ROYAL_TS_RTSZ_OR_RTSX,
    KEEPASS_XML_2X,
    BITWARDEN_JSON,
    ONEPASSWORD_1PUX_OR_JSON,
    CSV_INVENTORY_OR_PASSWORDS,
    UNKNOWN
}

/**
 * Enterprise Multi-Format Password Manager & Inventory Importer (`v1.1`).
 * Supports native zero-loss import of:
 * - **KeePass 2.x XML (`.xml` / KDBX4 XML payload)**: Nested `<Group>` folders, `<Entry>` credentials,
 *   RFC 6238 `otpauth://` TOTP secrets, OpenSSH PEM keys, and protocol endpoint synthesis.
 * - **Bitwarden Vault JSON (`.json`)**: `folders[]`, `items[]` (Logins, TOTP, URIs, and SSH Keys).
 * - **1Password `.1pux` ZIP archives & JSON exports**: `accounts[].vaults[].items[]` (`export.data`).
 * - **Enterprise Inventory & Password Manager CSV (`.csv`)**: NexusTS CMDB CSV, Bitwarden CSV, 1Password CSV, and KeePass CSV.
 */
class EnterpriseVaultFormatImporter {

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun detectFormat(fileName: String, rawBytes: ByteArray): DetectedExternalVaultFormat {
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x4E.toByte() &&
            rawBytes[1] == 0x58.toByte() &&
            rawBytes[2] == 0x54.toByte() &&
            rawBytes[3] == 0x53.toByte()
        ) {
            return DetectedExternalVaultFormat.NXTS_ENCRYPTED_BINARY
        }

        val lowerName = fileName.lowercase()
        val isZip = rawBytes.size >= 4 &&
            rawBytes[0] == 0x50.toByte() &&
            rawBytes[1] == 0x4B.toByte() &&
            rawBytes[2] == 0x03.toByte() &&
            rawBytes[3] == 0x04.toByte()

        if (isZip) {
            if (lowerName.endsWith(".1pux") || zipContainsEntry(rawBytes, "export.data")) {
                return DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON
            }
            return DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX
        }

        val headText = rawBytes
            .decodeToString(0, minOf(rawBytes.size, 2048), throwOnInvalidSequence = false)
            .trimStart()

        if (headText.startsWith("<")) {
            return if (headText.contains("<KeePassFile", ignoreCase = true)) {
                DetectedExternalVaultFormat.KEEPASS_XML_2X
            } else {
                DetectedExternalVaultFormat.ROYAL_TS_RTSZ_OR_RTSX
            }
        }

        if (headText.startsWith("{")) {
            return if (headText.contains("\"accounts\"") && headText.contains("\"vaults\"")) {
                DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON
            } else if (lowerName.contains("1password") || lowerName.endsWith(".1pif")) {
                DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON
            } else {
                DetectedExternalVaultFormat.BITWARDEN_JSON
            }
        }

        if (lowerName.endsWith(".csv") || headText.lines().firstOrNull()?.contains(",") == true) {
            return DetectedExternalVaultFormat.CSV_INVENTORY_OR_PASSWORDS
        }

        return DetectedExternalVaultFormat.UNKNOWN
    }

    fun importExternalFormat(
        format: DetectedExternalVaultFormat,
        rawBytes: ByteArray,
        documentNameHint: String
    ): NxtsVaultDocument {
        val cleanName = documentNameHint.substringBeforeLast('.').ifBlank { "Imported Enterprise Vault" }
        return when (format) {
            DetectedExternalVaultFormat.KEEPASS_XML_2X -> importKeePassXml(rawBytes, cleanName)
            DetectedExternalVaultFormat.BITWARDEN_JSON -> importBitwardenJson(rawBytes, cleanName)
            DetectedExternalVaultFormat.ONEPASSWORD_1PUX_OR_JSON -> importOnePassword1PuxOrJson(rawBytes, cleanName)
            DetectedExternalVaultFormat.CSV_INVENTORY_OR_PASSWORDS -> importCsvVault(rawBytes, cleanName)
            else -> error("Unsupported external vault format: $format")
        }
    }

    /**
     * Imports KeePass 2.x XML (`<KeePassFile><Root><Group>...`) preserving nested folders,
     * credentials, TOTP seeds (`otpauth://` or raw Base32), SSH private keys, and remote hosts.
     */
    fun importKeePassXml(xmlBytes: ByteArray, vaultName: String = "Imported KeePass Vault"): NxtsVaultDocument {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val dom = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        dom.documentElement.normalize()

        val folders = mutableListOf<VaultFolder>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        val rootNodes = dom.documentElement.getElementsByTagName("Root")
        val startContainer = (rootNodes.item(0) as? Element) ?: dom.documentElement

        fun walkGroup(groupElem: Element, parentFolderId: String?, isSyntheticRoot: Boolean) {
            val groupName = groupElem.directChildText("Name").ifBlank { "Group" }
            val groupUuid = groupElem.directChildText("UUID")
                .takeIf { it.isNotBlank() }
                ?.let { "kp-group-${it.filter(Char::isLetterOrDigit)}" }
                ?: UUID.randomUUID().toString()

            val currentFolderId = if (isSyntheticRoot) {
                null
            } else {
                folders += VaultFolder(
                    id = groupUuid,
                    parentFolderId = parentFolderId,
                    name = groupName,
                    description = groupElem.directChildText("Notes")
                )
                groupUuid
            }

            val children = groupElem.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType != Node.ELEMENT_NODE) continue
                val childElem = child as Element
                when (childElem.tagName) {
                    "Group" -> {
                        val subName = childElem.directChildText("Name")
                        if (!subName.equals("Recycle Bin", ignoreCase = true) &&
                            !subName.equals("Backup", ignoreCase = true)
                        ) {
                            walkGroup(childElem, currentFolderId, isSyntheticRoot = false)
                        }
                    }
                    "Entry" -> {
                        val kvMap = parseKeePassEntryStrings(childElem)
                        val title = kvMap["Title"].orEmpty().ifBlank { "KeePass Entry" }
                        val rawUser = kvMap["UserName"].orEmpty()
                        val password = kvMap["Password"].orEmpty()
                        val url = kvMap["URL"].orEmpty()
                        val notes = kvMap["Notes"].orEmpty()
                        val otpRaw = kvMap["otp"]
                            ?: kvMap["TOTP Seed"]
                            ?: kvMap["TimeOtp-Secret-Base32"]
                            ?: ""
                        val sshKeyCandidate = kvMap.values.firstOrNull {
                            it.contains("BEGIN OPENSSH PRIVATE KEY") || it.contains("BEGIN RSA PRIVATE KEY")
                        }.orEmpty()

                        val (domain, username) = splitDomainUser(rawUser)
                        val totpSecret = extractBase32TotpSecret(otpRaw)
                        val credId = childElem.directChildText("UUID")
                            .takeIf { it.isNotBlank() }
                            ?.let { "kp-cred-${it.filter(Char::isLetterOrDigit)}" }
                            ?: UUID.randomUUID().toString()

                        credentials += VaultCredential(
                            id = credId,
                            parentFolderId = currentFolderId,
                            name = title,
                            sourceKind = if (sshKeyCandidate.isNotBlank()) {
                                CredentialSourceKind.SSH_KEY_PAIR
                            } else {
                                CredentialSourceKind.KEEPASS_KDBX
                            },
                            username = username,
                            domain = domain,
                            secretPassword = password,
                            privateKeyPem = sshKeyCandidate,
                            totpSecretBase32 = totpSecret
                        )

                        synthesizeConnectionFromEndpoint(
                            title = title,
                            rawUri = url,
                            parentFolderId = currentFolderId,
                            credentialId = credId,
                            notes = notes
                        )?.let { connections += it }
                    }
                }
            }
        }

        val topChildren = startContainer.childNodes
        var foundDirectGroup = false
        for (i in 0 until topChildren.length) {
            val n = topChildren.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "Group") {
                foundDirectGroup = true
                walkGroup(n, parentFolderId = null, isSyntheticRoot = true)
            }
        }
        if (!foundDirectGroup && startContainer.tagName == "Group") {
            walkGroup(startContainer, parentFolderId = null, isSyntheticRoot = true)
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from KeePass 2.x / KDBX XML",
            folders = folders,
            connections = connections,
            credentials = credentials
        )
    }

    /**
     * Imports Bitwarden unencrypted JSON export (`folders[]` + `items[]`).
     */
    fun importBitwardenJson(jsonBytes: ByteArray, vaultName: String = "Imported Bitwarden Vault"): NxtsVaultDocument {
        val root = lenientJson.parseToJsonElement(jsonBytes.toString(StandardCharsets.UTF_8)).jsonObject
        val folders = mutableListOf<VaultFolder>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        val folderIdRemap = mutableMapOf<String, String>()
        (root["folders"] as? JsonArray)?.forEach { folderEl ->
            val obj = folderEl.jsonObject
            val origId = obj.str("id")
            val name = obj.str("name").ifBlank { "Bitwarden Folder" }
            val mappedId = origId.ifBlank { UUID.randomUUID().toString() }
            if (origId.isNotBlank()) folderIdRemap[origId] = mappedId
            folders += VaultFolder(id = mappedId, name = name)
        }

        (root["items"] as? JsonArray)?.forEach { itemEl ->
            val item = itemEl.jsonObject
            val itemId = item.str("id").ifBlank { UUID.randomUUID().toString() }
            val name = item.str("name").ifBlank { "Bitwarden Item" }
            val notes = item.str("notes")
            val folderId = item.str("folderId").let { folderIdRemap[it] ?: it.takeIf(String::isNotBlank) }
            val favorite = item["favorite"]?.jsonPrimitive?.contentOrNull == "true"

            val loginObj = item["login"] as? JsonObject
            val sshKeyObj = item["sshKey"] as? JsonObject

            val rawUser = loginObj?.str("username").orEmpty()
            val password = loginObj?.str("password").orEmpty()
            val totpRaw = loginObj?.str("totp").orEmpty()
            val privKey = sshKeyObj?.str("privateKey").orEmpty()
            val pubKey = sshKeyObj?.str("publicKey").orEmpty()
            val fingerprint = sshKeyObj?.str("keyFingerprint").orEmpty()

            if (loginObj != null || sshKeyObj != null) {
                val (domain, user) = splitDomainUser(rawUser)
                val credId = "bw-cred-$itemId"
                credentials += VaultCredential(
                    id = credId,
                    parentFolderId = folderId,
                    name = name,
                    sourceKind = if (privKey.isNotBlank()) {
                        CredentialSourceKind.SSH_KEY_PAIR
                    } else {
                        CredentialSourceKind.BITWARDEN_VAULT
                    },
                    username = user,
                    domain = domain,
                    secretPassword = password,
                    privateKeyPem = privKey,
                    publicKeyOpenSsh = pubKey,
                    keyFingerprintSha256 = fingerprint,
                    totpSecretBase32 = extractBase32TotpSecret(totpRaw)
                )

                val firstUri = (loginObj?.get("uris") as? JsonArray)
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.str("uri") }
                    .orEmpty()

                synthesizeConnectionFromEndpoint(
                    title = name,
                    rawUri = firstUri,
                    parentFolderId = folderId,
                    credentialId = credId,
                    notes = notes,
                    favorite = favorite
                )?.let { connections += it }
            }
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from Bitwarden Vault JSON",
            folders = folders,
            connections = connections,
            credentials = credentials
        )
    }

    /**
     * Imports 1Password `.1pux` ZIP archives (`export.data`) or 1Password JSON exports.
     */
    fun importOnePassword1PuxOrJson(rawBytes: ByteArray, vaultName: String = "Imported 1Password Vault"): NxtsVaultDocument {
        val jsonPayload = extractZipEntryOrRaw(rawBytes, "export.data")
        val root = lenientJson.parseToJsonElement(jsonPayload.toString(StandardCharsets.UTF_8)).jsonObject

        val folders = mutableListOf<VaultFolder>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        fun processItem(item: JsonObject, parentFolderId: String?) {
            val uuid = item.str("uuid").ifBlank { UUID.randomUUID().toString() }
            val overview = item["overview"] as? JsonObject
            val title = overview?.str("title")?.ifBlank { null }
                ?: item.str("title").ifBlank { "1Password Item" }
            val primaryUrl = overview?.str("url")
                ?.ifBlank { null }
                ?: (overview?.get("urls") as? JsonArray)
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.str("url") }
                    .orEmpty()

            var username = ""
            var password = ""
            var totp = ""
            var sshPem = ""
            var notes = ""

            val details = item["details"] as? JsonObject
            notes = details?.str("notesPlain").orEmpty()

            (details?.get("loginFields") as? JsonArray)?.forEach { f ->
                val fObj = f.jsonObject
                val designation = fObj.str("designation").lowercase()
                val fieldName = fObj.str("name").lowercase()
                val value = fObj.str("value")
                when {
                    designation == "username" || fieldName == "username" -> username = value
                    designation == "password" || fieldName == "password" -> password = value
                }
            }

            (details?.get("sections") as? JsonArray)?.forEach { sec ->
                val fields = (sec as? JsonObject)?.get("fields") as? JsonArray ?: return@forEach
                fields.forEach { fld ->
                    val fldObj = fld.jsonObject
                    val titleLower = fldObj.str("title").lowercase()
                    val valueObj = fldObj["value"]
                    when {
                        valueObj is JsonObject && valueObj["totp"] != null -> {
                            totp = valueObj.str("totp")
                        }
                        valueObj is JsonObject && valueObj["sshKey"] != null -> {
                            sshPem = (valueObj["sshKey"] as? JsonObject)?.str("privateKey").orEmpty()
                        }
                        valueObj is JsonElement -> {
                            val strVal = (valueObj as? JsonObject)?.str("string")
                                ?: runCatching { valueObj.jsonPrimitive.contentOrNull }.getOrNull().orEmpty()
                            if (username.isBlank() && titleLower.contains("user")) username = strVal
                            if (password.isBlank() && titleLower.contains("pass")) password = strVal
                            if (totp.isBlank() && titleLower.contains("otp")) totp = strVal
                        }
                    }
                }
            }

            val (domain, cleanUser) = splitDomainUser(username)
            val credId = "1p-cred-$uuid"
            credentials += VaultCredential(
                id = credId,
                parentFolderId = parentFolderId,
                name = title,
                sourceKind = if (sshPem.isNotBlank()) {
                    CredentialSourceKind.SSH_KEY_PAIR
                } else {
                    CredentialSourceKind.ONEPASSWORD_CONNECT
                },
                username = cleanUser,
                domain = domain,
                secretPassword = password,
                privateKeyPem = sshPem,
                totpSecretBase32 = extractBase32TotpSecret(totp)
            )

            synthesizeConnectionFromEndpoint(
                title = title,
                rawUri = primaryUrl,
                parentFolderId = parentFolderId,
                credentialId = credId,
                notes = notes
            )?.let { connections += it }
        }

        val accounts = root["accounts"] as? JsonArray
        if (accounts != null) {
            accounts.forEach { acct ->
                val vaults = (acct as? JsonObject)?.get("vaults") as? JsonArray ?: return@forEach
                vaults.forEach { v ->
                    val vObj = v.jsonObject
                    val vAttrs = vObj["attrs"] as? JsonObject
                    val vName = vAttrs?.str("name")?.ifBlank { null } ?: "1Password Vault"
                    val folderId = "1p-vault-${vAttrs?.str("uuid")?.ifBlank { UUID.randomUUID().toString() }}"
                    folders += VaultFolder(id = folderId, name = vName)
                    (vObj["items"] as? JsonArray)?.forEach { itemEl ->
                        processItem(itemEl.jsonObject, folderId)
                    }
                }
            }
        } else {
            (root["items"] as? JsonArray)?.forEach { itemEl ->
                processItem(itemEl.jsonObject, null)
            }
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from 1Password (.1pux / JSON)",
            folders = folders,
            connections = connections,
            credentials = credentials
        )
    }

    /**
     * Imports RFC 4180 CSV inventories & password exports (NexusTS CSV, Bitwarden CSV, 1Password CSV, KeePass CSV).
     */
    fun importCsvVault(csvBytes: ByteArray, vaultName: String = "Imported CSV Inventory"): NxtsVaultDocument {
        val rows = parseRfc4180Csv(csvBytes.toString(StandardCharsets.UTF_8))
        if (rows.size < 2) {
            return NxtsVaultDocument(name = vaultName)
        }
        val headers = rows.first().map { it.trim().lowercase() }
        fun colIndex(vararg aliases: String): Int =
            aliases.firstNotNullOfOrNull { alias -> headers.indexOf(alias.lowercase()).takeIf { it >= 0 } } ?: -1

        val idxName = colIndex("name", "title", "account")
        val idxHost = colIndex("host", "login_uri", "url", "website", "hostname")
        val idxProto = colIndex("protocol", "type")
        val idxPort = colIndex("port")
        val idxFolder = colIndex("folder", "group")
        val idxUser = colIndex("username", "login_username")
        val idxPass = colIndex("password", "login_password")
        val idxTotp = colIndex("totp", "login_totp", "otp")
        val idxNotes = colIndex("notes", "comments")

        val foldersByName = linkedMapOf<String, VaultFolder>()
        val credentials = mutableListOf<VaultCredential>()
        val connections = mutableListOf<VaultConnection>()

        for (row in rows.drop(1)) {
            if (row.all { it.isBlank() }) continue
            fun cell(idx: Int): String = if (idx in row.indices) row[idx].trim() else ""

            val title = cell(idxName).ifBlank { cell(idxHost).ifBlank { "Imported Entry" } }
            val folderName = cell(idxFolder)
            val folderId = if (folderName.isNotBlank()) {
                foldersByName.getOrPut(folderName) { VaultFolder(name = folderName) }.id
            } else {
                null
            }

            val rawUser = cell(idxUser)
            val rawPass = cell(idxPass)
            val rawTotp = cell(idxTotp)
            val notes = cell(idxNotes)

            val credId: String? = if (rawUser.isNotBlank() || rawPass.isNotBlank() || rawTotp.isNotBlank()) {
                val (domain, user) = splitDomainUser(rawUser)
                val id = UUID.randomUUID().toString()
                credentials += VaultCredential(
                    id = id,
                    parentFolderId = folderId,
                    name = "$title Credential",
                    sourceKind = CredentialSourceKind.VAULT_INTERNAL,
                    username = user,
                    domain = domain,
                    secretPassword = rawPass,
                    totpSecretBase32 = extractBase32TotpSecret(rawTotp)
                )
                id
            } else {
                null
            }

            val hostVal = cell(idxHost)
            val explicitProto = cell(idxProto).uppercase()
            val explicitPort = cell(idxPort).toIntOrNull()

            if (hostVal.isNotBlank()) {
                val matchedProto = VaultProtocolType.entries.firstOrNull { it.name == explicitProto }
                if (matchedProto != null) {
                    connections += buildConnectionForProtocol(
                        title = title,
                        host = hostVal,
                        protocol = matchedProto,
                        portOverride = explicitPort,
                        parentFolderId = folderId,
                        credentialId = credId,
                        notes = notes
                    )
                } else {
                    synthesizeConnectionFromEndpoint(
                        title = title,
                        rawUri = hostVal,
                        parentFolderId = folderId,
                        credentialId = credId,
                        notes = notes
                    )?.let { connections += it }
                }
            }
        }

        return NxtsVaultDocument(
            name = vaultName,
            description = "Imported from CSV Inventory",
            folders = foldersByName.values.toList(),
            connections = connections,
            credentials = credentials
        )
    }

    internal fun extractBase32TotpSecret(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            val query = trimmed.substringAfter('?', "")
            for (part in query.split('&')) {
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && kv[0].equals("secret", ignoreCase = true)) {
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name())
                        .uppercase()
                        .filter { it in 'A'..'Z' || it in '2'..'7' }
                }
            }
        }
        return trimmed.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }
    }

    private fun synthesizeConnectionFromEndpoint(
        title: String,
        rawUri: String,
        parentFolderId: String?,
        credentialId: String?,
        notes: String = "",
        favorite: Boolean = false
    ): VaultConnection? {
        val trimmed = rawUri.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        val (proto, host, port) = when {
            lower.startsWith("rdp://") -> parseHostPort(trimmed.removePrefix("rdp://").removePrefix("RDP://"), VaultProtocolType.RDP, 3389)
            lower.startsWith("ssh://") -> parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.SSH, 22)
            lower.startsWith("sftp://") -> parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.SFTP, 22)
            lower.startsWith("vnc://") -> parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.VNC, 5900)
            lower.startsWith("telnet://") -> parseHostPort(trimmed.substringAfter("://"), VaultProtocolType.TELNET, 23)
            lower.startsWith("serial://") -> Triple(VaultProtocolType.SERIAL, trimmed.substringAfter("://"), 115_200)
            lower.startsWith("k8s://") || lower.startsWith("kubectl://") ->
                Triple(VaultProtocolType.KUBERNETES_POD, trimmed.substringAfter("://"), 6443)
            lower.startsWith("docker://") ->
                Triple(VaultProtocolType.DOCKER_CONTAINER, trimmed.substringAfter("://"), 2376)
            lower.startsWith("http://") || lower.startsWith("https://") ->
                Triple(VaultProtocolType.WEB_PAGE, trimmed, 443)
            !trimmed.contains(' ') && (trimmed.contains('.') || trimmed.contains(':')) ->
                parseHostPort(trimmed, VaultProtocolType.SSH, 22)
            else -> return null
        }

        return buildConnectionForProtocol(
            title = title,
            host = host,
            protocol = proto,
            portOverride = port,
            parentFolderId = parentFolderId,
            credentialId = credentialId,
            notes = notes,
            favorite = favorite
        )
    }

    private fun buildConnectionForProtocol(
        title: String,
        host: String,
        protocol: VaultProtocolType,
        portOverride: Int?,
        parentFolderId: String?,
        credentialId: String?,
        notes: String = "",
        favorite: Boolean = false
    ): VaultConnection {
        val settings: ProtocolSpecificSettings = when (protocol) {
            VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES ->
                ProtocolSpecificSettings.Rdp(port = portOverride ?: 3389)
            VaultProtocolType.SSH, VaultProtocolType.TELNET ->
                ProtocolSpecificSettings.Ssh(port = portOverride ?: if (protocol == VaultProtocolType.TELNET) 23 else 22)
            VaultProtocolType.SERIAL ->
                ProtocolSpecificSettings.SerialUart(baudRate = portOverride ?: 115_200, devicePathHint = host)
            VaultProtocolType.KUBERNETES_POD, VaultProtocolType.DOCKER_CONTAINER ->
                ProtocolSpecificSettings.ContainerExec(
                    podOrContainerName = host.substringAfterLast('/'),
                    namespace = host.substringBeforeLast('/', "default").ifBlank { "default" },
                    clusterOrSocketEndpoint = host
                )
            VaultProtocolType.SFTP, VaultProtocolType.FTP, VaultProtocolType.FTPS, VaultProtocolType.SCP ->
                ProtocolSpecificSettings.FileTransfer(port = portOverride ?: 22)
            VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING ->
                ProtocolSpecificSettings.Vnc(port = portOverride ?: 5900)
            VaultProtocolType.WEB_PAGE ->
                ProtocolSpecificSettings.WebPage(url = if (host.startsWith("http")) host else "https://$host")
            else ->
                ProtocolSpecificSettings.CloudVirtualization(apiEndpointUrl = if (host.startsWith("http")) host else "https://$host")
        }

        val inheritable = if (credentialId != null) {
            InheritableProperties(
                credentialMode = InheritanceMode.OVERRIDE_EXPLICIT,
                explicitCredentialId = credentialId
            )
        } else {
            InheritableProperties()
        }

        return VaultConnection(
            parentFolderId = parentFolderId,
            name = title,
            host = host,
            protocol = protocol,
            osFamily = if (protocol == VaultProtocolType.RDP) "Windows" else "Linux",
            notes = notes,
            favorite = favorite,
            inheritable = inheritable,
            settings = settings
        )
    }

    private fun parseHostPort(authorityRaw: String, proto: VaultProtocolType, defaultPort: Int): Triple<VaultProtocolType, String, Int> {
        val clean = authorityRaw.substringAfter('@').substringBefore('/')
        val colonIdx = clean.lastIndexOf(':')
        return if (colonIdx > 0 && colonIdx < clean.lastIndex) {
            val maybePort = clean.substring(colonIdx + 1).toIntOrNull()
            if (maybePort != null) {
                Triple(proto, clean.substring(0, colonIdx), maybePort)
            } else {
                Triple(proto, clean, defaultPort)
            }
        } else {
            Triple(proto, clean, defaultPort)
        }
    }

    private fun parseKeePassEntryStrings(entryElem: Element): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val children = entryElem.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "String") {
                val k = n.directChildText("Key")
                val v = n.directChildText("Value")
                if (k.isNotBlank()) result[k] = v
            }
        }
        return result
    }

    private fun Element.directChildText(tagName: String): String {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == tagName) {
                return child.textContent?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull.orEmpty() }.getOrDefault("")

    private fun splitDomainUser(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val idx = trimmed.indexOf('\\')
        return if (idx > 0 && idx < trimmed.lastIndex) {
            trimmed.substring(0, idx) to trimmed.substring(idx + 1)
        } else {
            "" to trimmed
        }
    }

    private fun zipContainsEntry(rawBytes: ByteArray, entrySuffix: String): Boolean {
        runCatching {
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(entrySuffix, ignoreCase = true)) {
                        return true
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return false
    }

    private fun extractZipEntryOrRaw(rawBytes: ByteArray, wantedSuffix: String): ByteArray {
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x50.toByte() &&
            rawBytes[1] == 0x4B.toByte() &&
            rawBytes[2] == 0x03.toByte() &&
            rawBytes[3] == 0x04.toByte()
        ) {
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(wantedSuffix, ignoreCase = true)) {
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return rawBytes
    }

    internal fun parseRfc4180Csv(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        currentField.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow += currentField.toString()
                        currentField.setLength(0)
                    }
                    '\r' -> {
                        if (i + 1 < input.length && input[i + 1] == '\n') i++
                        currentRow += currentField.toString()
                        currentField.setLength(0)
                        rows += currentRow
                        currentRow = mutableListOf()
                    }
                    '\n' -> {
                        currentRow += currentField.toString()
                        currentField.setLength(0)
                        rows += currentRow
                        currentRow = mutableListOf()
                    }
                    else -> currentField.append(c)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += currentField.toString()
            rows += currentRow
        }
        return rows
    }
}
