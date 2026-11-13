package com.nexusts.app.core.vault.importer

import org.w3c.dom.Document
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Cross-platform XXE-hardened DOM XML parser compatible with BOTH:
 * - **Android Runtime (`ART` / `libcore` `org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl`)**, which
 *   throws [javax.xml.parsers.ParserConfigurationException] on Apache Xerces feature URIs such as
 *   `http://apache.org/xml/features/disallow-doctype-decl`, and
 * - **Desktop / CI OpenJDK (`Xerces`)**, which supports SAX & Xerces XXE-disabling features.
 *
 * Security is enforced in depth on ALL runtimes by:
 * 1. Rejecting `<!DOCTYPE` and `<!ENTITY` declarations up front before invoking the parser (ignoring `<![CDATA[...]>`),
 * 2. Configuring `isExpandEntityReferences = false`, `isValidating = false`, and `isNamespaceAware = false`,
 * 3. Applying optional JAXP/Xerces hardening features via `runCatching` when supported by the runtime provider, and
 * 4. Installing a strict no-op [EntityResolver] so external entities can never access network or filesystem resources.
 */
internal object SecureXmlDomParser {

    private val CDATA_BLOCK_REGEX = Regex("""<!\[CDATA\[.*?]]>""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val DOCTYPE_OR_ENTITY_REGEX = Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE)

    fun parseXmlBytes(
        xmlBytes: ByteArray,
        normalizeDocument: Boolean = true,
        factoryProvider: () -> DocumentBuilderFactory = { DocumentBuilderFactory.newInstance() }
    ): Document {
        rejectDoctypeAndEntityDeclarations(xmlBytes)

        val factory = factoryProvider().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }

        val builder = factory.newDocumentBuilder()
        runCatching {
            builder.setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
        }

        val document = builder.parse(ByteArrayInputStream(xmlBytes))
        if (normalizeDocument) {
            document.documentElement?.normalize()
        }
        return document
    }

    private fun rejectDoctypeAndEntityDeclarations(xmlBytes: ByteArray) {
        if (xmlBytes.isEmpty()) return
        val xmlString = decodeXmlTextForInspection(xmlBytes)
        if (!DOCTYPE_OR_ENTITY_REGEX.containsMatchIn(xmlString)) return

        val withoutCdata = CDATA_BLOCK_REGEX.replace(xmlString, "")
        if (DOCTYPE_OR_ENTITY_REGEX.containsMatchIn(withoutCdata)) {
            throw IllegalArgumentException(
                "XML DOCTYPE and ENTITY declarations are disallowed for security (XXE protection)."
            )
        }
    }

    private fun decodeXmlTextForInspection(bytes: ByteArray): String = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)

        bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)

        bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)

        bytes.size >= 4 && bytes[0] == 0x3C.toByte() && bytes[1] == 0x00.toByte() ->
            String(bytes, StandardCharsets.UTF_16LE)

        bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x3C.toByte() ->
            String(bytes, StandardCharsets.UTF_16BE)

        else -> String(bytes, StandardCharsets.UTF_8)
    }
}
