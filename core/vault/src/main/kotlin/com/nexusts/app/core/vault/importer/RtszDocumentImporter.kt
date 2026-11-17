package com.nexusts.app.core.vault.importer

import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.ConnectionTemplate
import com.nexusts.app.core.vault.model.CredentialSourceKind
import com.nexusts.app.core.vault.model.CustomPropertyEntry
import com.nexusts.app.core.vault.model.DynamicFolder
import com.nexusts.app.core.vault.model.DynamicScriptInterpreter
import com.nexusts.app.core.vault.model.GatewayTransportKind
import com.nexusts.app.core.vault.model.InheritableProperties
import com.nexusts.app.core.vault.model.InheritanceMode
import com.nexusts.app.core.vault.model.KeySequenceTask
import com.nexusts.app.core.vault.model.NxtsVaultDocument
import com.nexusts.app.core.vault.model.PortForwardKind
import com.nexusts.app.core.vault.model.ProtocolSpecificSettings
import com.nexusts.app.core.vault.model.RdpAudioRedirectionMode
import com.nexusts.app.core.vault.model.RdpGfxCodecProfile
import com.nexusts.app.core.vault.model.RdpResizeBehavior
import com.nexusts.app.core.vault.model.SecureGatewayConfig
import com.nexusts.app.core.vault.model.SmartFolder
import com.nexusts.app.core.vault.model.SmartFolderFilter
import com.nexusts.app.core.vault.model.SshPortForwardRule
import com.nexusts.app.core.vault.model.VaultConnection
import com.nexusts.app.core.vault.model.VaultCredential
import com.nexusts.app.core.vault.model.VaultFolder
import com.nexusts.app.core.vault.model.VaultProtocolType
import com.nexusts.app.core.vault.model.VaultTodoItem
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Full Royal TS (Windows V2–V7) and Royal TSX (macOS V1–V6) `.rtsz` and `.rtsx` Compatibility Importer.
 *
 * Supports all real-world Royal TS / Royal TSX container & XML formats:
 * - GZIP-compressed `.rtsz` streams (`0x1F 0x8B`),
 * - ZIP-packaged `.rtsz` archives (`0x50 0x4B 0x03 0x04`), including WinZip AES & ZipCrypto Lockdown archives,
 * - Raw `.rtsx` / `.rtsz` XML in UTF-8, UTF-8 BOM, UTF-16LE, and UTF-16BE,
 * - Both flat `<RTSZDocument>` `<ParentID>` object tables and nested `<Objects>` / `<RoyalFolder>` trees,
 * - Automatic `<RoyalTrash>` deleted-object exclusion,
 * - Full macOS Royal TSX (`<RoyalTerminalConnection>`, `<RoyalWebConnection>`) and Windows Royal TS tag parity.
 */
class RtszDocumentImporter {

    private data class CollectedRoyalElement(
        val element: Element,
        val implicitParentId: String?
    )

    @JvmOverloads
    fun importRtszBytes(
        rawBytes: ByteArray,
        fallbackDocumentName: String = "Imported Royal TS Vault",
        archivePassword: CharArray? = null
    ): NxtsVaultDocument = importRtszBytesInternal(rawBytes, fallbackDocumentName, archivePassword)

    internal fun importRtszBytesInternal(
        rawBytes: ByteArray,
        fallbackDocumentName: String = "Imported Royal TS Vault",
        archivePassword: CharArray? = null,
        factoryProvider: () -> DocumentBuilderFactory = { DocumentBuilderFactory.newInstance() }
    ): NxtsVaultDocument {
        val xmlBytes = extractNormalizedUtf8Xml(rawBytes, archivePassword)
        val dom = runCatching {
            SecureXmlDomParser.parseXmlBytes(xmlBytes, normalizeDocument = true, factoryProvider = factoryProvider)
        }.getOrElse { ex ->
            throw IllegalArgumentException("Invalid Royal TS XML document: ${ex.message}", ex)
        }

        val rootTag = dom.documentElement.tagName
        if (rootTag.equals("KeePassFile", ignoreCase = true)) {
            throw IllegalArgumentException(
                "KeePass 2.x XML document detected. Use EnterpriseVaultFormatImporter instead of Royal TS .rtsz importer."
            )
        }

        val collectedElements = collectRoyalElements(dom.documentElement)

        val folders = mutableListOf<VaultFolder>()
        val smartFolders = mutableListOf<SmartFolder>()
        val connections = mutableListOf<VaultConnection>()
        val credentials = mutableListOf<VaultCredential>()
        val gateways = mutableListOf<SecureGatewayConfig>()
        val commandTasks = mutableListOf<CommandTask>()
        val keyTasks = mutableListOf<KeySequenceTask>()
        val dynamicFolders = mutableListOf<DynamicFolder>()
        val templates = mutableListOf<ConnectionTemplate>()
        val todoItems = mutableListOf<VaultTodoItem>()

        var documentId = UUID.randomUUID().toString()
        var docName = fallbackDocumentName.ifBlank { "Imported Royal TS Vault" }
        var docDescription = "Imported from Royal TS (.rtsz)"
        var docRevision = 1L
        var isRoyalPasswordEncryptedXml = false
        var rootInheritable = InheritableProperties(
            credentialMode = InheritanceMode.NONE_DIRECT,
            gatewayMode = InheritanceMode.NONE_DIRECT
        )

        val trashRootIds = mutableSetOf<String>()
        val rawParentById = mutableMapOf<String, String>()
        val credentialIdByName = mutableMapOf<String, String>()

        // Pass 1: Pre-scan <RoyalDocument>, <RoyalTrash>, <RoyalCredential> names, and parent links regardless of XML tag ordering
        for ((elem, implicitParentId) in collectedElements) {
            val tag = elem.tagName
            val elemId = elem.childText("ID").nonEmptyRoyalIdOrNull().orEmpty()
            val elemParent = elem.childText("ParentID").nonEmptyRoyalIdOrNull()
                ?: implicitParentId?.nonEmptyRoyalIdOrNull()
                ?: ""
            if (elemId.isNotBlank() && elemParent.isNotBlank()) {
                rawParentById[elemId] = elemParent
            }
            if (tag.equals("RoyalTrash", ignoreCase = true)) {
                if (elemId.isNotBlank()) {
                    trashRootIds += elemId
                }
                continue
            }
            if (tag.equals("RoyalCredential", ignoreCase = true) ||
                tag.equals("RoyalDynamicCredential", ignoreCase = true)
            ) {
                val credName = elem.childText("Name").ifBlank { elem.childText("DisplayName") }.trim()
                if (elemId.isNotBlank() && credName.isNotBlank()) {
                    credentialIdByName.putIfAbsent(credName.lowercase(), elemId)
                }
            }
            if (tag.equals("RoyalDocument", ignoreCase = true)) {
                if (elemId.isNotBlank()) documentId = elemId
                val parsedDocName = elem.childText("Name")
                if (parsedDocName.isNotBlank()) docName = parsedDocName
                val parsedDesc = elem.childText("Description").ifBlank { elem.childText("Notes") }
                if (parsedDesc.isNotBlank()) docDescription = parsedDesc
                elem.childText("Modified").toLongOrNull()?.let { docRevision = it }
                if (elem.childBoolean("Encrypted", "IsEncrypted", default = false) ||
                    elem.childText("PasswordHash").isNotBlank()
                ) {
                    isRoyalPasswordEncryptedXml = true
                }
            }
        }

        // Second mini-pass for <RoyalDocument> rootInheritable once credentialIdByName is populated
        for ((elem, _) in collectedElements) {
            if (elem.tagName.equals("RoyalDocument", ignoreCase = true)) {
                if (elem.hasDirectChild("CredentialMode") ||
                    elem.hasDirectChild("CredentialID") ||
                    elem.hasDirectChild("CredentialName") ||
                    elem.hasDirectChild("GatewayMode")
                ) {
                    val docExplicitCredId = elem.childText("CredentialID")
                        .ifBlank { elem.childText("CredentialId") }
                        .nonEmptyRoyalIdOrNull()
                        ?: elem.childText("CredentialName").trim().takeIf { it.isNotEmpty() }
                            ?.let { credentialIdByName[it.lowercase()] }
                    rootInheritable = parseInheritable(
                        elem = elem,
                        resolvedCredId = docExplicitCredId,
                        synthesizedInlineCredId = null,
                        defaultCredMode = InheritanceMode.NONE_DIRECT,
                        defaultGwMode = InheritanceMode.NONE_DIRECT
                    )
                }
                break
            }
        }

        fun isInsideRoyalTrash(objectId: String, directParentId: String?): Boolean {
            if (trashRootIds.isEmpty()) return false
            if (objectId in trashRootIds) return true
            val visited = mutableSetOf<String>()
            var cur = directParentId
            while (!cur.isNullOrBlank() && visited.add(cur)) {
                if (cur in trashRootIds) return true
                cur = rawParentById[cur]
            }
            return false
        }

        val seenIds = mutableSetOf<String>()

        // Pass 2: Materialize folders, smart folders, connections, templates, credentials, gateways, to-dos, and tasks
        for ((elem, implicitParentId) in collectedElements) {
            val tag = elem.tagName
            if (tag.equals("RoyalDocument", ignoreCase = true) ||
                tag.equals("RoyalTrash", ignoreCase = true) ||
                tag.equals("RoyalApplicationSetting", ignoreCase = true)
            ) {
                continue
            }

            val id = elem.childText("ID").nonEmptyRoyalIdOrNull() ?: UUID.randomUUID().toString()
            val parentIdRaw = elem.childText("ParentID").nonEmptyRoyalIdOrNull()
                ?: implicitParentId?.nonEmptyRoyalIdOrNull()

            if (isInsideRoyalTrash(id, parentIdRaw)) continue
            if (!seenIds.add("$tag:$id")) continue

            val name = elem.childText("Name")
                .ifBlank { elem.childText("DisplayName") }
                .ifBlank { elem.childText("Title") }
                .ifBlank { resolveRawEndpoint(elem).ifBlank { tag.removePrefix("Royal") } }

            val rawEndpoint = resolveRawEndpoint(elem)
            val (parsedHostFromUri, parsedPortFromUri) = extractHostAndOptionalPort(rawEndpoint)

            val isTemplate = elem.childBoolean("IsTemplate", "IsConnectionTemplate", default = false) ||
                tag.equals("RoyalTemplate", ignoreCase = true)
            val isFavorite = elem.childBoolean("Favorite", "IsFavorite", default = false)
            val colorHex = normalizeHexColor(elem.childText("Color"))
            val templateColorHex = normalizeHexColor(elem.childText("TemplateColor")) ?: colorHex

            val rawCredMode = elem.childText("CredentialMode").trim()
            val explicitCredId = elem.childText("CredentialID")
                .ifBlank { elem.childText("CredentialId") }
                .nonEmptyRoyalIdOrNull()
                ?: elem.childText("CredentialName").trim().takeIf { it.isNotEmpty() }
                    ?.let { credentialIdByName[it.lowercase()] }

            val isCredentialTag = tag.equals("RoyalCredential", ignoreCase = true) ||
                tag.equals("RoyalDynamicCredential", ignoreCase = true)

            val inlineUserRaw = elem.childText("CredentialUsername")
                .ifBlank { if (!isCredentialTag) elem.childText("UserName").ifBlank { elem.childText("Username") } else "" }
            val inlinePassCandidate = elem.childText("CredentialPassword")
                .ifBlank { if (!isCredentialTag) elem.childText("Password") else "" }
            val inlinePassRaw = sanitizeImportedSecret(inlinePassCandidate, isRoyalPasswordEncryptedXml)
            val inlineKeyPem = if (!isCredentialTag) {
                sanitizeImportedKeyContent(
                    elem.childText("CredentialKeyFileContent")
                        .ifBlank { elem.childText("CredentialKeyContent") }
                        .ifBlank { elem.childText("PrivateKeyContent") },
                    isRoyalPasswordEncryptedXml
                )
            } else {
                ""
            }

            val synthesizedInlineCredId: String? = if (
                !isCredentialTag &&
                (rawCredMode == "2" || rawCredMode == "1" || (explicitCredId == null && (inlineUserRaw.isNotBlank() || inlinePassRaw.isNotBlank() || inlineKeyPem.isNotBlank()))) &&
                explicitCredId == null &&
                (inlineUserRaw.isNotBlank() || inlinePassRaw.isNotBlank() || inlineKeyPem.isNotBlank())
            ) {
                val (inlineDomain, inlineUser) = splitDomainAndUsername(inlineUserRaw)
                val synId = "inline-cred-$id"
                credentials += VaultCredential(
                    id = synId,
                    parentFolderId = if (tag.equals("RoyalFolder", ignoreCase = true)) {
                        id
                    } else {
                        parentIdRaw?.takeIf { it != documentId }
                    },
                    name = "$name (Saved Credential)",
                    sourceKind = if (inlineKeyPem.isNotBlank()) {
                        CredentialSourceKind.SSH_KEY_PAIR
                    } else {
                        CredentialSourceKind.VAULT_INTERNAL
                    },
                    username = inlineUser,
                    domain = inlineDomain,
                    secretPassword = inlinePassRaw,
                    privateKeyPem = inlineKeyPem
                )
                synId
            } else {
                null
            }

            val resolvedCredId = synthesizedInlineCredId ?: explicitCredId
            val inheritable = parseInheritable(
                elem = elem,
                resolvedCredId = resolvedCredId,
                synthesizedInlineCredId = synthesizedInlineCredId,
                defaultCredMode = InheritanceMode.INHERIT_FROM_PARENT,
                defaultGwMode = InheritanceMode.INHERIT_FROM_PARENT,
                isTemplate = isTemplate
            )

            when {
                tag.equals("RoyalFolder", ignoreCase = true) -> {
                    if (id != documentId) {
                        val folderTags = parseTagsList(elem.childText("Tags"))
                        folders += VaultFolder(
                            id = id,
                            parentFolderId = parentIdRaw?.takeIf { it != documentId },
                            name = name,
                            description = elem.childText("Notes").ifBlank { elem.childText("Description") },
                            tags = folderTags,
                            inheritable = inheritable,
                            sortOrder = elem.childText("SortOrder").ifBlank { elem.childText("PositionNr") }.toIntOrNull() ?: 0
                        )
                    }
                }

                tag.equals("NexusSmartFolder", ignoreCase = true) ||
                    tag.equals("RoyalSmartFolder", ignoreCase = true) -> {
                    val includeTags = parseTagsList(elem.childText("IncludeTags").ifBlank { elem.childText("Tags") })
                    val excludeTags = parseTagsList(elem.childText("ExcludeTags"))
                    val includeProtocols = elem.childText("IncludeProtocols")
                        .split(';', ',')
                        .mapNotNull { raw ->
                            val trimmed = raw.trim()
                            VaultProtocolType.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                        }
                    smartFolders += SmartFolder(
                        id = id,
                        name = name,
                        filter = SmartFolderFilter(
                            includeTags = includeTags,
                            excludeTags = excludeTags,
                            includeProtocols = includeProtocols,
                            scopeFolderId = elem.childText("ScopeFolderId").nonEmptyRoyalIdOrNull(),
                            osFamilyFilter = elem.childText("OSFamilyFilter").takeIf { it.isNotBlank() },
                            hostPatternRegex = elem.childText("HostPatternRegex")
                                .ifBlank { elem.childText("SearchPattern") }
                                .takeIf { it.isNotBlank() },
                            environmentFilter = elem.childText("EnvironmentFilter").takeIf { it.isNotBlank() }
                        )
                    )
                }

                tag.equals("RoyalToDo", ignoreCase = true) ||
                    tag.equals("RoyalToDoItem", ignoreCase = true) -> {
                    todoItems += VaultTodoItem(
                        id = id,
                        targetObjectId = parentIdRaw?.takeIf { it != documentId },
                        title = name,
                        completed = elem.childBoolean("Completed", "IsCompleted", "Done", default = false),
                        priority = elem.childText("Priority").ifBlank { "NORMAL" },
                        dueIsoDate = elem.childText("DueDate")
                    )
                }

                isCredentialTag -> {
                    val keyContent = sanitizeImportedKeyContent(
                        elem.childText("KeyFileContent")
                            .ifBlank { elem.childText("CredentialKeyContent") }
                            .ifBlank { elem.childText("PrivateKeyContent") },
                        isRoyalPasswordEncryptedXml
                    )
                    val rawUser = elem.childText("UserName").ifBlank { elem.childText("Username") }
                    val explicitDomain = elem.childText("Domain")
                    val totpSecret = sanitizeImportedSecret(
                        elem.childText("OneTimePasswordSecret").ifBlank { elem.childText("TOTPSecret") },
                        isRoyalPasswordEncryptedXml
                    )
                    val (parsedDomain, parsedUser) = splitDomainAndUsername(rawUser)
                    val explicitKind = elem.childText("SourceKind").let { raw ->
                        CredentialSourceKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    }
                    val resolvedKind = explicitKind ?: if (keyContent.isNotBlank()) {
                        CredentialSourceKind.SSH_KEY_PAIR
                    } else {
                        CredentialSourceKind.VAULT_INTERNAL
                    }
                    credentials += VaultCredential(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        sourceKind = resolvedKind,
                        username = parsedUser,
                        domain = explicitDomain.ifBlank { parsedDomain },
                        secretPassword = sanitizeImportedSecret(elem.childText("Password"), isRoyalPasswordEncryptedXml),
                        privateKeyPem = keyContent,
                        publicKeyOpenSsh = elem.childText("PublicKeyOpenSsh"),
                        keyFingerprintSha256 = elem.childText("KeyFingerprintSha256"),
                        keyPassphrase = sanitizeImportedSecret(elem.childText("KeyPassphrase"), isRoyalPasswordEncryptedXml),
                        externalReferenceUri = elem.childText("ExternalReferenceUri"),
                        fido2CredentialIdBase64 = elem.childText("Fido2CredentialIdBase64"),
                        totpSecretBase32 = totpSecret
                    )
                }

                tag.equals("RoyalSecureGateway", ignoreCase = true) ||
                    tag.equals("RoyalSSHGateway", ignoreCase = true) ||
                    tag.equals("RoyalRDSGateway", ignoreCase = true) ||
                    tag.equals("RoyalManagementEndpoint", ignoreCase = true) -> {
                    val explicitGwKind = elem.childText("NexusGatewayKind").let { raw ->
                        GatewayTransportKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    }
                    val gwKind = explicitGwKind ?: when {
                        tag.equals("RoyalSSHGateway", ignoreCase = true) ->
                            GatewayTransportKind.SSH_TUNNEL_BASTION
                        tag.equals("RoyalRDSGateway", ignoreCase = true) ->
                            GatewayTransportKind.HTTP_CONNECT_PROXY
                        else ->
                            GatewayTransportKind.NEXUSTS_SECURE_GATEWAY
                    }
                    val defaultGwPort = when {
                        tag.equals("RoyalSSHGateway", ignoreCase = true) -> 22
                        else -> 443
                    }
                    val gwPort = elem.childInt("Port", "SSHPort", "RDGatewayPort", "RoyalServerPort")
                        ?: parsedPortFromUri
                        ?: defaultGwPort
                    gateways += SecureGatewayConfig(
                        id = id,
                        name = name,
                        kind = gwKind,
                        host = parsedHostFromUri.ifBlank { rawEndpoint },
                        port = gwPort,
                        credentialId = resolvedCredId,
                        verifyTlsCertificate = elem.childBoolean("VerifyTlsCertificate", default = true)
                    )
                }

                tag.equals("RoyalCommandTask", ignoreCase = true) -> {
                    commandTasks += CommandTask(
                        id = id,
                        name = name,
                        commandTemplate = elem.childText("Command").ifBlank { elem.childText("CommandLine") },
                        broadcastToAllSelectedSessions = elem.childBoolean("BroadcastToAll", default = false),
                        appendNewline = elem.childBoolean("AppendNewline", default = true)
                    )
                }

                tag.equals("RoyalKeySequenceTask", ignoreCase = true) -> {
                    keyTasks += KeySequenceTask(
                        id = id,
                        name = name,
                        macroSequence = elem.childText("KeySequence"),
                        keystrokeDelayMs = elem.childText("KeystrokeDelayMs")
                            .ifBlank { elem.childText("KeyStrokeDelay") }
                            .toIntOrNull() ?: 25
                    )
                }

                tag.equals("RoyalDynamicFolder", ignoreCase = true) -> {
                    val parsedInterp = elem.childText("Interpreter").let { raw ->
                        DynamicScriptInterpreter.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    } ?: DynamicScriptInterpreter.SHELL_SH
                    dynamicFolders += DynamicFolder(
                        id = id,
                        parentFolderId = parentIdRaw?.takeIf { it != documentId },
                        name = name,
                        interpreter = parsedInterp,
                        scriptOrEndpointPayload = elem.childText("Script").ifBlank { elem.childText("DynamicFolderScript") },
                        refreshIntervalMinutes = elem.childText("RefreshIntervalMinutes").toIntOrNull() ?: 30,
                        inheritable = inheritable
                    )
                }

                isRoyalConnectionOrTemplateTag(tag) -> {
                    val proto = resolveProtocolForConnectionElement(elem, tag, rawEndpoint)
                    val effectiveHost = when (proto) {
                        VaultProtocolType.WEB_PAGE -> rawEndpoint.ifBlank { parsedHostFromUri }
                        else -> parsedHostFromUri.ifBlank { rawEndpoint }
                    }
                    val settings = parseProtocolSettings(elem, proto, rawEndpoint, parsedPortFromUri)
                    if (isTemplate) {
                        val description = elem.childText("Description").ifBlank { elem.childText("Notes") }
                        templates += ConnectionTemplate(
                            id = id,
                            name = name,
                            description = description,
                            protocol = proto,
                            colorAccentHex = templateColorHex,
                            inheritable = inheritable,
                            settings = settings
                        )
                    } else {
                        val connTags = parseTagsList(elem.childText("Tags"))
                        val osFamily = elem.childText("OSFamily").ifBlank { defaultOsForProtocol(proto) }
                        connections += VaultConnection(
                            id = id,
                            parentFolderId = parentIdRaw?.takeIf { it != documentId },
                            name = name,
                            host = effectiveHost.ifBlank { name },
                            protocol = proto,
                            osFamily = osFamily,
                            tags = connTags,
                            notes = elem.childText("Notes").ifBlank { elem.childText("Description") },
                            favorite = isFavorite,
                            sortOrder = elem.childText("SortOrder").ifBlank { elem.childText("PositionNr") }.toIntOrNull() ?: 0,
                            inheritable = inheritable,
                            settings = settings
                        )
                    }
                }
            }
        }

        return com.nexusts.app.core.vault.error.VaultHierarchySanitizer.sanitizeVaultDocument(
            NxtsVaultDocument(
                documentId = documentId,
                name = docName,
                description = docDescription,
                revision = docRevision,
                rootInheritable = rootInheritable,
                folders = folders,
                smartFolders = smartFolders,
                dynamicFolders = dynamicFolders,
                connections = connections,
                credentials = credentials,
                gateways = gateways,
                commandTasks = commandTasks,
                keySequenceTasks = keyTasks,
                templates = templates,
                todoItems = todoItems
            )
        )
    }

    private fun collectRoyalElements(root: Element): List<CollectedRoyalElement> {
        val out = mutableListOf<CollectedRoyalElement>()

        if (root.tagName.equals("RoyalDocument", ignoreCase = true)) {
            out += CollectedRoyalElement(root, null)
        }

        fun visitChildren(parentElement: Element, currentFolderContextId: String?) {
            val nodes = parentElement.childNodes
            for (i in 0 until nodes.length) {
                val child = nodes.item(i)
                if (child.nodeType != Node.ELEMENT_NODE) continue
                val childElem = child as Element
                val tag = childElem.tagName

                when {
                    tag.equals("Objects", ignoreCase = true) ||
                        tag.equals("RoyalStore", ignoreCase = true) ||
                        tag.equals("RTSZDocument", ignoreCase = true) -> {
                        visitChildren(childElem, currentFolderContextId)
                    }

                    tag.equals("RoyalDocument", ignoreCase = true) -> {
                        out += CollectedRoyalElement(childElem, currentFolderContextId)
                        val docId = childElem.childText("ID").nonEmptyRoyalIdOrNull() ?: currentFolderContextId
                        visitChildren(childElem, docId)
                    }

                    tag.equals("RoyalFolder", ignoreCase = true) ||
                        tag.equals("RoyalTrash", ignoreCase = true) -> {
                        out += CollectedRoyalElement(childElem, currentFolderContextId)
                        val folderId = childElem.childText("ID").nonEmptyRoyalIdOrNull() ?: currentFolderContextId
                        visitChildren(childElem, folderId)
                    }

                    tag.startsWith("Royal", ignoreCase = true) ||
                        tag.startsWith("Nexus", ignoreCase = true) -> {
                        out += CollectedRoyalElement(childElem, currentFolderContextId)
                    }
                }
            }
        }

        visitChildren(root, null)
        return out
    }

    private fun isRoyalConnectionOrTemplateTag(tag: String): Boolean {
        if (tag.equals("RoyalTemplate", ignoreCase = true)) return true
        return tag.startsWith("Royal", ignoreCase = true) &&
            tag.endsWith("Connection", ignoreCase = true)
    }

    private fun resolveRawEndpoint(elem: Element): String {
        val candidates = listOf(
            "URI",
            "ComputerName",
            "Host",
            "HostName",
            "Server",
            "Address",
            "IPAddress",
            "WebPageUrl",
            "URL",
            "RDGatewayHost"
        )
        for (key in candidates) {
            val value = elem.childText(key)
            if (value.isNotBlank()) return value.trim()
        }
        return ""
    }

    private fun extractHostAndOptionalPort(rawEndpoint: String): Pair<String, Int?> {
        val trimmed = rawEndpoint.trim()
        if (trimmed.isEmpty()) return "" to null

        var withoutScheme = trimmed
        val schemeIdx = withoutScheme.indexOf("://")
        val hasHttpScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        if (schemeIdx > 0 && !hasHttpScheme) {
            withoutScheme = withoutScheme.substring(schemeIdx + 3)
            val atIdx = withoutScheme.lastIndexOf('@')
            if (atIdx >= 0 && atIdx < withoutScheme.lastIndex) {
                withoutScheme = withoutScheme.substring(atIdx + 1)
            }
            withoutScheme = withoutScheme.substringBefore('/')
        } else if (hasHttpScheme) {
            return trimmed to null
        }

        // Bracketed IPv6 `[2001:db8::1]:3389`
        if (withoutScheme.startsWith("[")) {
            val closeIdx = withoutScheme.indexOf(']')
            if (closeIdx > 1) {
                val ipv6 = withoutScheme.substring(1, closeIdx)
                val rem = withoutScheme.substring(closeIdx + 1)
                val port = if (rem.startsWith(":")) rem.drop(1).toIntOrNull()?.takeIf { it in 1..65535 } else null
                return ipv6 to port
            }
        }

        val firstColon = withoutScheme.indexOf(':')
        val lastColon = withoutScheme.lastIndexOf(':')
        if (firstColon > 0 && firstColon == lastColon && lastColon < withoutScheme.lastIndex) {
            val maybePort = withoutScheme.substring(lastColon + 1).toIntOrNull()
            if (maybePort != null && maybePort in 1..65535) {
                return withoutScheme.substring(0, lastColon) to maybePort
            }
        }
        return withoutScheme to null
    }

    private fun sanitizeImportedSecret(rawSecret: String, isRoyalEncryptedXml: Boolean): String {
        if (rawSecret.isEmpty()) return ""
        // When Royal TS `<Encrypted>True</Encrypted>` is enabled on the XML document, Royal TS encrypts
        // sensitive XML tags as Base64 AES ciphertexts that cannot be sent literally to SSH/RDP servers.
        if (isRoyalEncryptedXml && looksLikeEncryptedBase64Blob(rawSecret)) {
            return ""
        }
        return rawSecret
    }

    private fun sanitizeImportedKeyContent(rawKey: String, isRoyalEncryptedXml: Boolean): String {
        if (rawKey.isEmpty()) return ""
        if (rawKey.contains("-----BEGIN")) return rawKey
        val trimmed = rawKey.trim()
        val decodedPem = runCatching {
            val bytes = java.util.Base64.getMimeDecoder().decode(trimmed)
            val text = String(bytes, StandardCharsets.UTF_8)
            text.takeIf { it.contains("-----BEGIN") }
        }.getOrNull()
        if (decodedPem != null) return decodedPem
        if (isRoyalEncryptedXml && looksLikeEncryptedBase64Blob(trimmed)) return ""
        return rawKey
    }

    private fun looksLikeEncryptedBase64Blob(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length < 24 || trimmed.length % 4 != 0) return false
        return trimmed.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }

    private fun parseTagsList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun normalizeHexColor(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val prefixed = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return prefixed.takeIf {
            (it.length == 7 || it.length == 9) && it.drop(1).all { ch -> ch.digitToIntOrNull(16) != null }
        }
    }

    private fun parseInheritable(
        elem: Element,
        resolvedCredId: String?,
        synthesizedInlineCredId: String?,
        defaultCredMode: InheritanceMode,
        defaultGwMode: InheritanceMode,
        isTemplate: Boolean = false
    ): InheritableProperties {
        val rawCredMode = elem.childText("CredentialMode").trim()
        val credFromParent = elem.childBoolean("CredentialFromParent", default = false)
        val credMode = when {
            synthesizedInlineCredId != null -> InheritanceMode.OVERRIDE_EXPLICIT
            credFromParent -> InheritanceMode.INHERIT_FROM_PARENT
            rawCredMode == "0" -> InheritanceMode.NONE_DIRECT
            rawCredMode == "1" -> InheritanceMode.INHERIT_FROM_PARENT
            rawCredMode == "2" -> if (resolvedCredId != null) InheritanceMode.OVERRIDE_EXPLICIT else defaultCredMode
            rawCredMode == "3" -> if (resolvedCredId != null) InheritanceMode.OVERRIDE_EXPLICIT else InheritanceMode.PROMPT_AT_CONNECT
            rawCredMode == "4" -> if (resolvedCredId != null) InheritanceMode.OVERRIDE_EXPLICIT else InheritanceMode.INHERIT_FROM_PARENT
            resolvedCredId != null -> InheritanceMode.OVERRIDE_EXPLICIT
            else -> defaultCredMode
        }

        val gwId = elem.childText("SecureGatewayID").nonEmptyRoyalIdOrNull()
            ?: elem.childText("RDSGatewayID").nonEmptyRoyalIdOrNull()
            ?: elem.childText("RDGatewayID").nonEmptyRoyalIdOrNull()
            ?: elem.childText("ManagementEndpointID").nonEmptyRoyalIdOrNull()
        val gwFromParent = elem.childBoolean(
            "SecureGatewayFromParent",
            "RDSGatewayFromParent",
            "ManagementEndpointFromParent",
            default = false
        )
        val rawGwMode = elem.childText("GatewayMode")
            .ifBlank { elem.childText("SecureGatewayUsageMode") }
            .ifBlank { elem.childText("RDSGatewayUsageMode") }
            .trim()
        val gwMode = when {
            gwFromParent -> InheritanceMode.INHERIT_FROM_PARENT
            rawGwMode == "0" -> InheritanceMode.NONE_DIRECT
            rawGwMode == "2" || (rawGwMode == "1" && gwId != null) -> InheritanceMode.OVERRIDE_EXPLICIT
            rawGwMode == "3" -> InheritanceMode.PROMPT_AT_CONNECT
            rawGwMode == "4" || (rawGwMode == "1" && gwId == null) -> InheritanceMode.INHERIT_FROM_PARENT
            gwId != null -> InheritanceMode.OVERRIDE_EXPLICIT
            else -> defaultGwMode
        }

        val inheritableColor = if (isTemplate && elem.hasDirectChild("TemplateColor") && !elem.hasDirectChild("InheritableColor")) {
            null
        } else {
            normalizeHexColor(elem.childText("InheritableColor").ifBlank { elem.childText("Color") })
        }

        val customProps = (1..20).mapNotNull { idx ->
            val nameTag = "CustomField${idx}Name"
            val valTag = "CustomField$idx"
            val protTag = "CustomField${idx}Protected"
            val hasName = elem.hasDirectChild(nameTag)
            val v = elem.childText(valTag)
            val isProt = if (elem.hasDirectChild(protTag)) {
                elem.childBoolean(protTag, default = false)
            } else {
                idx in 11..20 || idx in 9..10
            }
            if (v.isNotBlank() || (hasName && isProt)) {
                val fieldLabel = elem.childText(nameTag).ifBlank { "CustomField$idx" }
                CustomPropertyEntry(
                    key = fieldLabel,
                    value = v,
                    isProtected = isProt
                )
            } else {
                null
            }
        }

        return InheritableProperties(
            credentialMode = credMode,
            explicitCredentialId = if (credMode == InheritanceMode.OVERRIDE_EXPLICIT) resolvedCredId else null,
            gatewayMode = gwMode,
            explicitGatewayId = if (gwMode == InheritanceMode.OVERRIDE_EXPLICIT) gwId else null,
            environmentBadge = elem.childText("EnvironmentBadge").takeIf { it.isNotBlank() },
            preConnectTaskId = elem.childText("PreConnectTaskID").nonEmptyRoyalIdOrNull()
                ?: elem.childText("PreConnectTaskId").nonEmptyRoyalIdOrNull()
                ?: elem.childText("ConnectTaskID").nonEmptyRoyalIdOrNull(),
            postDisconnectTaskId = elem.childText("PostDisconnectTaskID").nonEmptyRoyalIdOrNull()
                ?: elem.childText("PostDisconnectTaskId").nonEmptyRoyalIdOrNull()
                ?: elem.childText("DisconnectTaskID").nonEmptyRoyalIdOrNull(),
            displayColorAccentHex = inheritableColor,
            customProperties = customProps,
            requireConnectConfirmation = elem.childBoolean("RequireConnectConfirmation", default = false)
        )
    }

    private fun resolveProtocolForConnectionElement(
        elem: Element,
        tag: String,
        rawEndpoint: String
    ): VaultProtocolType {
        val explicitProtoName = elem.childText("NexusProtocolType").trim()
        if (explicitProtoName.isNotEmpty()) {
            VaultProtocolType.entries.firstOrNull { it.name.equals(explicitProtoName, ignoreCase = true) }
                ?.let { return it }
        }

        val lowerTag = tag.lowercase()
        return when {
            lowerTag == "royalrdsconnection" || lowerTag == "royaltemplate" -> {
                val hvMode = elem.childText("HyperVMode").trim()
                val hvInstance = elem.childText("HyperVInstance")
                    .ifBlank { elem.childText("VMId") }
                    .ifBlank { elem.childText("InstanceID") }
                    .trim()
                if (lowerTag == "royalrdsconnection" && (hvMode == "1" || (hvMode.equals("true", ignoreCase = true) && hvInstance.isEmpty()))) {
                    VaultProtocolType.HYPER_V
                } else {
                    VaultProtocolType.RDP
                }
            }

            lowerTag == "royalsshconnection" || lowerTag == "royalterminalconnection" -> {
                val termType = listOf(
                    elem.childText("TerminalConnectionType"),
                    elem.childText("ConnectionType"),
                    elem.childText("CustomTerminalType")
                ).joinToString(" ").lowercase()

                when {
                    elem.childBoolean("IsSerialPortConnection", default = false) ||
                        termType.contains("serial") ||
                        termType == "2" ||
                        (elem.hasDirectChild("BaudRate") && !termType.contains("ssh")) ->
                        VaultProtocolType.SERIAL
                    elem.hasDirectChild("Workload") || elem.hasDirectChild("ClusterEndpoint") ->
                        VaultProtocolType.KUBERNETES_POD
                    elem.childBoolean("IsTelnetConnection", default = false) ||
                        termType.contains("telnet") ||
                        termType == "1" ->
                        VaultProtocolType.TELNET
                    else -> VaultProtocolType.SSH
                }
            }

            lowerTag == "royalfiletransferconnection" -> {
                val connType = listOf(
                    elem.childText("ConnectionType"),
                    elem.childText("FileTransferConnectionType")
                ).joinToString(" ").uppercase()
                when {
                    connType.contains("SCP") || connType == "3" -> VaultProtocolType.SCP
                    connType.contains("FTPS") || connType == "2" -> VaultProtocolType.FTPS
                    connType.contains("FTP") || connType == "1" -> VaultProtocolType.FTP
                    else -> VaultProtocolType.SFTP
                }
            }

            lowerTag == "royalvncconnection" -> {
                if (elem.childBoolean("IsAppleRemoteDesktop", "UseAppleScreenSharing", "AppleScreenSharing", default = false)) {
                    VaultProtocolType.APPLE_SCREEN_SHARING
                } else {
                    VaultProtocolType.VNC
                }
            }

            lowerTag == "royalwebconnection" || lowerTag == "royalwebpageconnection" ->
                VaultProtocolType.WEB_PAGE

            lowerTag == "royaltelnetconnection" -> VaultProtocolType.TELNET
            lowerTag == "royalserialportconnection" -> VaultProtocolType.SERIAL
            lowerTag == "royalvmwareconnection" -> VaultProtocolType.VMWARE_VSPHERE
            lowerTag == "royalhypervconnection" -> VaultProtocolType.HYPER_V
            lowerTag == "royalproxmoxconnection" -> VaultProtocolType.PROXMOX_VE
            lowerTag == "royalawsconnection" -> VaultProtocolType.AWS_EC2
            lowerTag == "royalazureconnection" -> VaultProtocolType.AZURE_VM
            lowerTag == "royalcloudconnection" -> {
                if (elem.childText("SubscriptionOrAccountId").contains('-')) {
                    VaultProtocolType.AZURE_VM
                } else {
                    VaultProtocolType.AWS_EC2
                }
            }
            lowerTag == "royalterminalservicesconnection" ||
                lowerTag == "royalwindowsterminalservicesconnection" ||
                lowerTag == "royalwindowsservicesconnection" ||
                lowerTag == "royalwindowsprocessesconnection" ||
                lowerTag == "royalwindowseventsconnection" ||
                lowerTag == "royalperformanceconnection" ->
                VaultProtocolType.WINDOWS_TERMINAL_SERVICES
            lowerTag == "royalpowershellconnection" -> VaultProtocolType.SSH
            lowerTag == "royaldockerconnection" -> VaultProtocolType.DOCKER_CONTAINER
            lowerTag == "royalkubernetesconnection" || lowerTag == "royalcontainerconnection" -> {
                val ep = elem.childText("ClusterEndpoint").ifBlank { elem.childText("ClusterOrSocketEndpoint") }.ifBlank { rawEndpoint }
                if (ep.startsWith("unix://", true) || ep.startsWith("tcp://", true) || ep.contains(":2375") || ep.contains(":2376")) {
                    VaultProtocolType.DOCKER_CONTAINER
                } else {
                    VaultProtocolType.KUBERNETES_POD
                }
            }
            lowerTag == "royalnetworkutilityconnection" -> {
                if (elem.childText("WolMacAddress").isNotBlank() || elem.childText("MACAddress").isNotBlank()) {
                    VaultProtocolType.UTILITY_WAKE_ON_LAN
                } else {
                    VaultProtocolType.UTILITY_PING
                }
            }
            rawEndpoint.startsWith("http://", true) || rawEndpoint.startsWith("https://", true) ->
                VaultProtocolType.WEB_PAGE
            else -> VaultProtocolType.RDP
        }
    }

    private fun parseProtocolSettings(
        elem: Element,
        protocol: VaultProtocolType,
        uri: String,
        uriPortFallback: Int?
    ): ProtocolSpecificSettings = when (protocol) {
        VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES -> {
            val hvMode = elem.childText("HyperVMode").trim()
            val hvInstance = elem.childText("HyperVInstance")
                .ifBlank { elem.childText("VMId") }
                .ifBlank { elem.childText("InstanceID") }
                .trim()
                .trim('{', '}', '"', '\'')
            val isHyperVVm = hvMode == "2" || hvInstance.isNotBlank()
            val port = if (isHyperVVm) {
                elem.childInt("HyperVPort", "RDPPort", "Port", "ServerPort") ?: uriPortFallback ?: 2179
            } else {
                elem.childInt("RDPPort", "Port", "ServerPort") ?: uriPortFallback ?: 3389
            }
            val hvEnhancedSession = elem.childBoolean("HyperVEnhancedSessionMode", "EnhancedSessionMode", default = false)
            val audioModeRaw = elem.childText("AudioRedirectionMode").trim()
            val audioMode = when {
                audioModeRaw == "1" || audioModeRaw.contains("Remote", true) ->
                    RdpAudioRedirectionMode.PLAY_ON_REMOTE_HOST
                audioModeRaw == "2" || audioModeRaw.contains("None", true) || audioModeRaw.contains("DoNotPlay", true) ->
                    RdpAudioRedirectionMode.DO_NOT_PLAY
                else ->
                    RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE
            }
            val explicitScale = elem.childText("DesktopScaleFactor")
                .ifBlank { elem.childText("ScaleFactor") }
                .toIntOrNull()
                ?.coerceIn(100, 400)
            val scaleRaw = explicitScale ?: 200
            val deviceScale = elem.childText("DeviceScaleFactor").toIntOrNull()
                ?: if (scaleRaw >= 175) 180 else if (scaleRaw >= 125) 140 else 100
            val scaleCustomized = elem.childBoolean("HiDpiScaleCustomized", default = explicitScale != null)
            val resizeModeRaw = elem.childText("ResizeMode")
            val resizeBehavior = when {
                resizeModeRaw.contains("SmartSizing", true) || resizeModeRaw == "1" ->
                    RdpResizeBehavior.SMART_SCALE_VIEWPORT
                resizeModeRaw.contains("None", true) || resizeModeRaw.contains("Scrollbars", true) ->
                    RdpResizeBehavior.FIXED_SERVER_RESOLUTION
                resizeModeRaw.isNotBlank() ->
                    RdpResizeBehavior.DYNAMIC_DISP_DVC
                elem.childBoolean("SmartSizing", default = false) ->
                    RdpResizeBehavior.SMART_SCALE_VIEWPORT
                elem.hasDirectChild("SmartReconnect") && !elem.childBoolean("SmartReconnect", default = true) ->
                    RdpResizeBehavior.FIXED_SERVER_RESOLUTION
                else ->
                    RdpResizeBehavior.DYNAMIC_DISP_DVC
            }
            val monitorIds = elem.childText("SelectedMonitorIds")
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
            val driveUris = elem.childText("RedirectedDriveSafUris")
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val royalExplicitAvc = elem.childBoolean("RDPEnableAVC", default = false)
            val gfxCodecProfile = elem.childText("GfxCodecProfile")
                .takeIf { it.isNotEmpty() }
                ?.let { raw -> runCatching { RdpGfxCodecProfile.valueOf(raw) }.getOrNull() }
                ?: if (royalExplicitAvc) RdpGfxCodecProfile.AVC444_CHROMA else RdpGfxCodecProfile.ADAPTIVE_PROGRESSIVE

            ProtocolSpecificSettings.Rdp(
                port = port,
                connectToConsoleAdmin = elem.childBoolean("ConnectToAdministerOrConsole", "ConnectToConsole", default = false),
                hiDpiEnabled = elem.childBoolean("AutoAdjustRetinaDisplay", "AutomaticallyCalculateScaleFactor", "UseRetinaResolution", default = true),
                desktopScaleFactorPercent = scaleRaw,
                deviceScaleFactorPercent = deviceScale,
                hiDpiScaleCustomized = scaleCustomized,
                enableGfxPipeline = elem.childBoolean("EnableGfxPipeline", "EnableGraphicsPipeline", default = true),
                gfxCodecProfile = gfxCodecProfile,
                enableH264Avc444 = elem.childBoolean("EnableH264Avc444", default = gfxCodecProfile == RdpGfxCodecProfile.AVC444_CHROMA),
                enableRemoteFx = elem.childBoolean("EnableRemoteFx", default = true),
                resizeBehavior = resizeBehavior,
                customDesktopWidth = elem.childText("DesktopWidth").toIntOrNull()?.takeIf { it >= 320 } ?: 1920,
                customDesktopHeight = elem.childText("DesktopHeight").toIntOrNull()?.takeIf { it >= 240 } ?: 1080,
                multiMonitorSpanning = elem.childBoolean("UseMultimon", default = false),
                selectedMonitorIds = monitorIds,
                colorDepthBits = elem.childText("ColorDepth").toIntOrNull()?.takeIf { it in listOf(8, 15, 16, 24, 32) } ?: 32,
                redirectClipboard = elem.childBoolean("RedirectClipboard", default = true),
                audioRedirectionMode = audioMode,
                redirectAudioPlayback = if (elem.hasDirectChild("RedirectAudioPlayback")) {
                    elem.childBoolean("RedirectAudioPlayback", default = true)
                } else {
                    audioMode == RdpAudioRedirectionMode.PLAY_ON_THIS_DEVICE
                },
                redirectMicrophone = elem.childBoolean(
                    "RedirectMicrophone",
                    "RedirectAudioCapture",
                    "RedirectAudioRecording",
                    "AudioCaptureRedirectionMode",
                    default = false
                ),
                redirectDrives = elem.childBoolean("RedirectDrives", default = false),
                redirectedDriveSafUris = driveUris,
                redirectPrinters = elem.childBoolean("RedirectPrinters", default = false),
                redirectPorts = elem.childBoolean("RedirectPorts", default = false),
                redirectSmartcardYubiKey = elem.childBoolean("RedirectSmartCards", default = true),
                enableFontSmoothing = elem.childBoolean("AllowFontSmoothing", default = true),
                enableDesktopComposition = elem.childBoolean("AllowDesktopComposition", default = true),
                enableWallpaper = elem.childBoolean("AllowWallpaper", default = false),
                enableFullWindowDrag = elem.childBoolean("AllowFullWindowDrag", default = false),
                enableMenuAnimations = elem.childBoolean("AllowMenuAnimations", default = false),
                enableThemes = elem.childBoolean("AllowThemes", default = true),
                compressionEnabled = elem.childBoolean("Compression", default = true),
                remoteAppMode = elem.childBoolean("RemoteAppMode", default = false),
                remoteAppProgram = elem.childText("RemoteAppProgram").ifBlank { elem.childText("StartProgram") },
                remoteAppArguments = elem.childText("RemoteAppArguments"),
                rdGatewayHostname = elem.childText("RDGatewayHost").ifBlank { elem.childText("GatewayHostName") },
                rdGatewayPort = elem.childInt("RDGatewayPort") ?: 443,
                nlaSecurityRequired = elem.childBoolean("NetworkLevelAuthentication", default = true),
                tlsSecurity = elem.childBoolean("TlsSecurity", "TLSEncryption", default = true),
                restrictedAdminMode = elem.childBoolean("RestrictedAdminMode", default = false),
                ignoreCertificateWarnings = elem.childBoolean(
                    "IgnoreCertificateWarning",
                    "IgnoreCertificate",
                    default = isHyperVVm
                ),
                hyperVVmId = hvInstance,
                hyperVEnhancedSession = hvEnhancedSession
            )
        }

        VaultProtocolType.SSH, VaultProtocolType.TELNET -> {
            val defaultPort = if (protocol == VaultProtocolType.TELNET) 23 else 22
            ProtocolSpecificSettings.Ssh(
                port = elem.childInt("Port", "SSHPort", "ServerPort") ?: uriPortFallback ?: defaultPort,
                terminalType = elem.childText("TerminalType")
                    .ifBlank { elem.childText("TerminalName") }
                    .ifBlank { "xterm-256color" },
                bastionJumpConnectionId = elem.childText("BastionJumpConnectionId").nonEmptyRoyalIdOrNull(),
                enableAgentForwarding = elem.childBoolean("EnableAgentForwarding", "AllowAgentForwarding", default = false),
                enableX11Forwarding = elem.childBoolean("EnableX11Forwarding", "UseX11Forwarding", default = false),
                x11DisplayHost = elem.childText("X11DisplayHost").ifBlank { "127.0.0.1:0" },
                portForwards = decodePortForwards(elem.childText("PortForwardRules")),
                startupCommand = elem.childText("StartupCommand")
                    .ifBlank { elem.childText("InitialCommand") }
                    .ifBlank { elem.childText("CustomCommandOSX") },
                keepAliveIntervalSec = elem.childInt("KeepAliveIntervalSec", "KeepAliveInterval") ?: 30,
                scrollbackLines = elem.childInt("ScrollbackLines", "HistoryMaxLength") ?: 10_000,
                serialBaudRate = elem.childInt("SerialBaudRate", "BaudRate") ?: 115_200,
                serialDevicePath = elem.childText("SerialDevicePath").ifBlank { "/dev/ttyUSB0" }
            )
        }

        VaultProtocolType.SERIAL -> {
            ProtocolSpecificSettings.SerialUart(
                baudRate = elem.childInt("BaudRate", "SerialBaudRate") ?: 115_200,
                dataBits = elem.childInt("DataBits") ?: 8,
                stopBits = elem.childInt("StopBits") ?: 1,
                parity = elem.childText("Parity").ifBlank { "NONE" },
                flowControl = elem.childText("FlowControl").ifBlank { "NONE" },
                dtrEnabled = elem.childBoolean("DtrEnabled", default = true),
                rtsEnabled = elem.childBoolean("RtsEnabled", default = true),
                usbVendorIdFilter = elem.childInt("UsbVendorIdFilter") ?: 0,
                devicePathHint = elem.childText("DevicePathHint")
                    .ifBlank { elem.childText("SerialPort") }
                    .ifBlank { uri.ifBlank { "/dev/ttyUSB0" } },
                lineEnding = elem.childText("LineEnding").ifBlank { "CRLF" }
            )
        }

        VaultProtocolType.SFTP, VaultProtocolType.FTP, VaultProtocolType.FTPS, VaultProtocolType.SCP -> {
            val implicitFtps = elem.childBoolean("FtpsImplicitTls", "FTPSImplicitTls", default = false)
            val defaultPort = when (protocol) {
                VaultProtocolType.FTP -> 21
                VaultProtocolType.FTPS -> if (implicitFtps) 990 else 21
                else -> 22
            }
            val parsedPort = elem.childInt("Port", "SSHPort", "FTPPort") ?: uriPortFallback ?: defaultPort
            ProtocolSpecificSettings.FileTransfer(
                port = parsedPort,
                initialLocalDirectory = elem.childText("InitialLocalPath")
                    .ifBlank { elem.childText("InitialLocalDirectory") }
                    .ifBlank { elem.childText("LocalPath") },
                initialRemoteDirectory = elem.childText("InitialRemotePath")
                    .ifBlank { elem.childText("InitialRemoteDirectory") }
                    .ifBlank { elem.childText("RemotePath") },
                preserveTimestamps = elem.childBoolean("PreserveTimestamps", default = true),
                showHiddenDotfiles = elem.childBoolean("ShowHiddenDotfiles", "ShowHiddenFiles", default = true),
                maxConcurrentTransfers = elem.childInt("MaxConcurrentTransfers") ?: 4,
                ftpsImplicitTls = if (elem.hasDirectChild("FtpsImplicitTls") || elem.hasDirectChild("FTPSImplicitTls")) {
                    implicitFtps
                } else {
                    protocol == VaultProtocolType.FTPS && parsedPort == 990
                },
                passiveMode = elem.childBoolean("PassiveMode", default = true),
                verifyTls = elem.childBoolean("VerifyTls", default = false)
            )
        }

        VaultProtocolType.VNC, VaultProtocolType.APPLE_SCREEN_SHARING -> {
            val isArd = protocol == VaultProtocolType.APPLE_SCREEN_SHARING ||
                elem.childBoolean("IsAppleRemoteDesktop", "UseAppleScreenSharing", "AppleScreenSharing", "AppleArdDiffieHellmanAuth", default = false)
            val encodings = elem.childText("PreferredEncodings")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("ZRLE", "Tight", "CopyRect", "Hextile", "Raw") }
            ProtocolSpecificSettings.Vnc(
                port = elem.childInt("VNCPort", "Port") ?: uriPortFallback ?: 5900,
                preferredEncodings = encodings,
                compressionLevel = elem.childInt("CompressionLevel") ?: 6,
                jpegQualityLevel = elem.childInt("JpegQualityLevel") ?: 7,
                viewOnly = elem.childBoolean("ViewOnly", default = false),
                sharedSession = elem.childBoolean("SharedSession", default = true),
                appleArdDiffieHellmanAuth = if (elem.hasDirectChild("AppleArdDiffieHellmanAuth") || elem.hasDirectChild("AppleScreenSharing")) {
                    elem.childBoolean("AppleArdDiffieHellmanAuth", "AppleScreenSharing", default = isArd)
                } else {
                    isArd
                }
            )
        }

        VaultProtocolType.WEB_PAGE -> {
            val rawUrl = elem.childText("WebPageUrl")
                .ifBlank { elem.childText("WebUrl") }
                .ifBlank { elem.childText("URL") }
                .ifBlank { uri }
                .trim()
            val webUrl = when {
                rawUrl.isBlank() -> "https://localhost"
                rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true) -> rawUrl
                else -> "https://$rawUrl"
            }
            ProtocolSpecificSettings.WebPage(
                url = webUrl,
                isolatedStorageProfileId = elem.childText("IsolatedProfileId").ifBlank { UUID.randomUUID().toString() },
                autoFillEnabled = elem.childBoolean("AutoFill", "AutoFillEnabled", "EnableAutoFill", default = true),
                usernameCssSelector = elem.childText("UsernameCssSelector")
                    .ifBlank { "input[type='email'], input[name*='user'], input[type='text']" },
                passwordCssSelector = elem.childText("PasswordCssSelector")
                    .ifBlank { "input[type='password']" },
                submitCssSelector = elem.childText("SubmitCssSelector")
                    .ifBlank { "button[type='submit'], input[type='submit']" },
                injectHttpBasicAuth = elem.childBoolean("InjectHttpBasicAuth", "PassCredentialsForBasicAuthentication", default = true),
                pinnedCertSha256 = elem.childText("PinnedCertSha256"),
                ignoreSelfSignedInternalCert = elem.childBoolean(
                    "IgnoreSelfSignedCert",
                    "IgnoreSelfSignedInternalCert",
                    "IgnoreCertificateErrors",
                    default = true
                )
            )
        }

        VaultProtocolType.VMWARE_VSPHERE,
        VaultProtocolType.PROXMOX_VE,
        VaultProtocolType.HYPER_V,
        VaultProtocolType.AWS_EC2,
        VaultProtocolType.AZURE_VM -> {
            val apiUrl = if (elem.hasDirectChild("ApiEndpointUrl")) {
                elem.childText("ApiEndpointUrl")
            } else if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) {
                uri
            } else if (protocol in setOf(VaultProtocolType.HYPER_V, VaultProtocolType.AWS_EC2, VaultProtocolType.AZURE_VM)) {
                ""
            } else if (protocol == VaultProtocolType.PROXMOX_VE && uri.isNotBlank()) {
                if (uri.contains(':')) "https://$uri" else "https://$uri:8006"
            } else if (uri.isNotBlank()) {
                "https://$uri"
            } else {
                ""
            }
            val defaultRealm = if (protocol == VaultProtocolType.PROXMOX_VE) "pam" else ""
            ProtocolSpecificSettings.CloudVirtualization(
                apiEndpointUrl = apiUrl,
                regionOrCluster = elem.childText("RegionOrCluster"),
                subscriptionOrAccountId = elem.childText("SubscriptionOrAccountId"),
                realmOrDomain = if (elem.hasDirectChild("RealmOrDomain")) elem.childText("RealmOrDomain") else defaultRealm,
                autoSyncDynamicInventory = elem.childBoolean("AutoSyncDynamicInventory", default = true),
                verifyTls = elem.childBoolean("VerifyTls", default = false)
            )
        }

        VaultProtocolType.UTILITY_PING,
        VaultProtocolType.UTILITY_TRACEROUTE,
        VaultProtocolType.UTILITY_PORT_SCANNER,
        VaultProtocolType.UTILITY_WAKE_ON_LAN -> {
            ProtocolSpecificSettings.NetUtility(
                pingCount = elem.childInt("PingCount") ?: 10,
                pingIntervalMs = elem.childInt("PingIntervalMs") ?: 1000,
                tracerouteMaxHops = elem.childInt("TracerouteMaxHops") ?: 30,
                portScanRangeStart = elem.childInt("PortScanStart", "PortScanRangeStart") ?: 1,
                portScanRangeEnd = elem.childInt("PortScanEnd", "PortScanRangeEnd") ?: 1024,
                portScanConcurrency = elem.childInt("PortScanConcurrency") ?: 128,
                wolMacAddress = elem.childText("WolMacAddress")
                    .ifBlank { elem.childText("MACAddress") }
                    .ifBlank { elem.childText("PhysicalAddress") },
                wolBroadcastAddress = elem.childText("WolBroadcastIp")
                    .ifBlank { elem.childText("WolBroadcastAddress") }
                    .ifBlank { "255.255.255.255" },
                wolUdpPort = elem.childInt("WolUdpPort") ?: 9,
                wolSecureOnHex = elem.childText("WolSecureOnHex")
            )
        }

        VaultProtocolType.KUBERNETES_POD, VaultProtocolType.DOCKER_CONTAINER -> {
            val defaultEndpoint = if (protocol == VaultProtocolType.DOCKER_CONTAINER) {
                if (uri.isNotBlank()) "http://$uri:2375" else "http://127.0.0.1:2375"
            } else {
                if (uri.isNotBlank()) "https://$uri:6443" else "https://kubernetes.default.svc:6443"
            }
            ProtocolSpecificSettings.ContainerExec(
                clusterOrSocketEndpoint = when {
                    elem.hasDirectChild("ClusterEndpoint") -> elem.childText("ClusterEndpoint")
                    elem.hasDirectChild("ClusterOrSocketEndpoint") -> elem.childText("ClusterOrSocketEndpoint")
                    uri.startsWith("http://", true) || uri.startsWith("https://", true) ||
                        uri.startsWith("unix://", true) || uri.startsWith("tcp://", true) -> uri
                    else -> defaultEndpoint
                },
                contextName = elem.childText("ContextName"),
                namespace = if (elem.hasDirectChild("Namespace")) {
                    elem.childText("Namespace")
                } else {
                    "default"
                },
                podOrContainerName = elem.childText("Workload").ifBlank { elem.childText("PodOrContainerName") },
                subContainerName = elem.childText("ContainerName").ifBlank { elem.childText("SubContainerName") },
                shellCommand = elem.childText("Shell").ifBlank { elem.childText("ShellCommand") }.ifBlank { "/bin/sh" },
                followLogsOnOpen = elem.childBoolean("FollowLogs", "FollowLogsOnOpen", default = false),
                logTailLines = elem.childInt("LogTailLines") ?: 200,
                portForwards = decodePortForwards(elem.childText("PortForwardRules")),
                verifyTls = elem.childBoolean("VerifyTls", default = false)
            )
        }
    }

    private fun decodePortForwards(encoded: String): List<SshPortForwardRule> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(';').mapNotNull { segment ->
            val parts = segment.split('|')
            if (parts.size < 7) return@mapNotNull null
            val kind = PortForwardKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val bindPort = parts[3].toIntOrNull() ?: return@mapNotNull null
            val targetPort = parts[5].toIntOrNull() ?: return@mapNotNull null
            SshPortForwardRule(
                id = parts[0],
                kind = kind,
                bindAddress = parts[2],
                bindPort = bindPort,
                targetHost = parts[4],
                targetPort = targetPort,
                enabled = parts[6].equals("true", ignoreCase = true)
            )
        }
    }

    private fun defaultOsForProtocol(protocol: VaultProtocolType): String = when (protocol) {
        VaultProtocolType.RDP, VaultProtocolType.WINDOWS_TERMINAL_SERVICES, VaultProtocolType.HYPER_V -> "Windows"
        VaultProtocolType.APPLE_SCREEN_SHARING -> "macOS"
        else -> "Linux"
    }

    private fun splitDomainAndUsername(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val slashIdx = trimmed.indexOf('\\')
        return if (slashIdx > 0 && slashIdx < trimmed.lastIndex) {
            trimmed.substring(0, slashIdx).trim() to trimmed.substring(slashIdx + 1).trim()
        } else {
            "" to trimmed
        }
    }

    private fun extractNormalizedUtf8Xml(rawBytes: ByteArray, archivePassword: CharArray?): ByteArray {
        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException("Cannot import empty Royal TS .rtsz / .rtsx payload.")
        }

        // Reject binary KeePass .kdbx containers (magic 03 D9 A2 9A) with a clear actionable error
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x03.toByte() &&
            rawBytes[1] == 0xD9.toByte() &&
            rawBytes[2] == 0xA2.toByte() &&
            rawBytes[3] == 0x9A.toByte()
        ) {
            throw IllegalArgumentException(
                "Binary KeePass .kdbx database detected (03 D9 A2 9A). Import using EnterpriseVaultFormatImporter with your KDBX passphrase."
            )
        }

        // Reject encrypted NexusTS .nxts containers
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x4E.toByte() &&
            rawBytes[1] == 0x58.toByte() &&
            rawBytes[2] == 0x54.toByte() &&
            rawBytes[3] == 0x53.toByte()
        ) {
            throw IllegalArgumentException(
                "Encrypted NexusTS .nxts container detected. Open with NxtsVaultSerializer using your vault passphrase."
            )
        }

        val unwrappedBytes = unwrapArchiveOrCompression(rawBytes, archivePassword)
        val xmlText = decodeBomAwareXmlText(unwrappedBytes)
        val trimmedText = xmlText.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmedText.startsWith("<")) {
            throw IllegalArgumentException(
                "File is neither a valid Royal TS .rtsz archive nor a valid .rtsx XML document."
            )
        }

        val sanitizedXml = sanitizeDotNetXmlForStrictParser(trimmedText)
        return sanitizedXml.toByteArray(StandardCharsets.UTF_8)
    }

    private fun unwrapArchiveOrCompression(bytes: ByteArray, archivePassword: CharArray?): ByteArray {
        // 1. GZIP compressed .rtsz stream (magic 1F 8B) — used by Royal TS & Royal TSX V2-V7!
        if (isGzipStream(bytes)) {
            val gunzipped = runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrElse { ex ->
                throw IllegalArgumentException("Corrupted GZIP stream in Royal TS .rtsz file: ${ex.message}", ex)
            }
            return if (isZipStream(gunzipped) || isGzipStream(gunzipped)) {
                unwrapArchiveOrCompression(gunzipped, archivePassword)
            } else {
                gunzipped
            }
        }

        // 2. ZIP local file header magic PK\x03\x04
        if (isZipStream(bytes)) {
            if (EncryptedZipExtractor.isEncryptedZip(bytes)) {
                if (archivePassword == null || archivePassword.isEmpty()) {
                    throw IllegalArgumentException(
                        "Royal TS Lockdown .rtsz archive is password-encrypted. Please provide the archive password."
                    )
                }
                val decrypted = EncryptedZipExtractor.extractDecryptedXmlEntry(bytes, archivePassword)
                return if (isGzipStream(decrypted)) {
                    GZIPInputStream(ByteArrayInputStream(decrypted)).use { it.readBytes() }
                } else {
                    decrypted
                }
            }

            var xmlSniffCandidate: ByteArray? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryName = entry.name
                        val entryPayload = zis.readBytes()
                        val decompressedCandidate = if (isGzipStream(entryPayload)) {
                            runCatching {
                                GZIPInputStream(ByteArrayInputStream(entryPayload)).use { it.readBytes() }
                            }.getOrDefault(entryPayload)
                        } else {
                            entryPayload
                        }
                        if (entryName.endsWith(".rtsx", ignoreCase = true) ||
                            entryName.endsWith(".xml", ignoreCase = true)
                        ) {
                            return decompressedCandidate
                        }
                        if (xmlSniffCandidate == null && looksLikeXmlPayload(decompressedCandidate)) {
                            xmlSniffCandidate = decompressedCandidate
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            return xmlSniffCandidate ?: throw IllegalArgumentException(
                "ZIP archive is not a valid Royal TS .rtsz document (no XML payload found)."
            )
        }

        return bytes
    }

    private fun isGzipStream(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    private fun isZipStream(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    private fun decodeBomAwareXmlText(bytes: ByteArray): String {
        val (charset: Charset, offset: Int) = when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8 to 3

            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE to 2

            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE to 2

            bytes.size >= 4 && bytes[0] == 0x3C.toByte() && bytes[1] == 0x00.toByte() ->
                StandardCharsets.UTF_16LE to 0

            bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x3C.toByte() ->
                StandardCharsets.UTF_16BE to 0

            else -> StandardCharsets.UTF_8 to 0
        }
        return String(bytes, offset, bytes.size - offset, charset)
    }

    private fun sanitizeDotNetXmlForStrictParser(xml: String): String {
        // Rewrite encoding="utf-16..." prolog declaration since we normalize to UTF-8 bytes
        val fixedEncoding = UTF16_DECL_REGEX.replaceFirst(xml, "encoding=\"utf-8\"")
        // Strip invalid XML 1.0 character entity references (e.g. &#x1B; ANSI escape in Royal TS key tasks)
        val withoutBadEntities = INVALID_CHAR_REF_REGEX.replace(fixedEncoding, "")
        // Strip raw control characters disallowed by XML 1.0
        val sb = StringBuilder(withoutBadEntities.length)
        for (ch in withoutBadEntities) {
            val code = ch.code
            if (code == 0x09 || code == 0x0A || code == 0x0D || code >= 0x20) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun looksLikeXmlPayload(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val preview = decodeBomAwareXmlText(bytes.copyOfRange(0, minOf(bytes.size, 2048)))
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return preview.startsWith("<")
    }

    private fun Element.hasDirectChild(tagName: String): Boolean {
        val children = this.childNodes
        for (idx in 0 until children.length) {
            val child = children.item(idx)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName.equals(tagName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun Element.childText(tagName: String): String {
        val children = this.childNodes
        for (idx in 0 until children.length) {
            val child = children.item(idx)
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName.equals(tagName, ignoreCase = true)) {
                return child.textContent?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun Element.childInt(vararg tagNames: String): Int? {
        for (tag in tagNames) {
            val v = childText(tag).toIntOrNull()
            if (v != null && v > 0) return v
        }
        return null
    }

    private fun Element.childBoolean(vararg tagNames: String, default: Boolean): Boolean {
        for (tag in tagNames) {
            if (hasDirectChild(tag)) {
                val raw = childText(tag)
                if (raw.equals("true", ignoreCase = true) || raw == "1") return true
                if (raw.equals("false", ignoreCase = true) || raw == "0") return false
            }
        }
        return default
    }

    private fun String.nonEmptyRoyalIdOrNull(): String? {
        val trimmed = this.trim()
        if (trimmed.isEmpty()) return null
        val unbraced = trimmed.removeSurrounding("{", "}").trim()
        if (unbraced.isEmpty() || unbraced.equals(EMPTY_DOTNET_GUID, ignoreCase = true)) return null
        return trimmed
    }

    companion object {
        private const val EMPTY_DOTNET_GUID = "00000000-0000-0000-0000-000000000000"
        private val UTF16_DECL_REGEX = Regex("""encoding\s*=\s*["']utf-16[^"']*["']""", RegexOption.IGNORE_CASE)
        private val INVALID_CHAR_REF_REGEX = Regex(
            """&#x(?:0*[0-8bcef]|1[0-9a-f]);|&#(?:0*[0-8]|11|12|1[4-9]|2[0-9]|3[01]);""",
            RegexOption.IGNORE_CASE
        )
    }
}
