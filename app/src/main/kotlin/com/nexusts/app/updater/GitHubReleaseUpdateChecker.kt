package com.nexusts.app.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class GitHubReleaseCheckResult(
    val latestTag: String,
    val currentVersion: String,
    val isUpdateAvailable: Boolean,
    val releaseHtmlUrl: String,
    val signedApkDownloadUrl: String?,
    val publishedAtIso: String,
    val releaseTitle: String = "",
    val releaseNotes: String = "",
    val apkFileName: String = "",
    val apkSizeBytes: Long = 0L,
    val expectedApkSha256Hex: String? = null
)

/**
 * Google Play Protect–Compliant GitHub Releases Auto-Update Checker (`v1.1.5`).
 *
 * Security Guarantees:
 * - Strictly ZERO `REQUEST_INSTALL_PACKAGES` permissions in `AndroidManifest.xml`.
 * - Enforces HTTPS-only GitHub domain allowlist (`api.github.com`, `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`).
 * - Downloads and parses `SHA256SUMS.txt` alongside release metadata so every APK can be cryptographically verified before handoff.
 */
class GitHubReleaseUpdateChecker(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val repositorySlug: String = "petrosvassiliou/NexusTS"
) {
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun checkLatestRelease(currentVersionName: String): Result<GitHubReleaseCheckResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalizedCurrent = currentVersionName.removePrefix("v").trim()
                val isBetaChannel = normalizedCurrent.contains('-') &&
                    !normalizedCurrent.substringAfter('-').equals("dev", ignoreCase = true)

                val obj = (if (isBetaChannel) {
                    fetchNewestReleaseObjectFromFeed(currentVersionName)
                } else {
                    null
                }) ?: fetchLatestStableReleaseObject(currentVersionName)

                val tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val releaseTitle = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val releaseNotes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(::isAllowedGitHubHttpsUrl)
                    ?: "https://github.com/$repositorySlug/releases/latest"
                val publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty()

                var apkAssetUrl: String? = null
                var apkAssetName = ""
                var apkAssetSize = 0L
                var sha256SumsUrl: String? = null

                (obj["assets"] as? JsonArray)?.forEach { assetEl ->
                    val asset = assetEl.jsonObject
                    val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val dl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    if (!dl.isNullOrBlank() && isAllowedGitHubHttpsUrl(dl)) {
                        if (name.endsWith("-release.apk", ignoreCase = true)) {
                            apkAssetUrl = dl
                            apkAssetName = name
                            apkAssetSize = size
                        } else if (name.equals("SHA256SUMS.txt", ignoreCase = true)) {
                            sha256SumsUrl = dl
                        }
                    }
                }

                val expectedSha256 = sha256SumsUrl?.let { url ->
                    runCatching { fetchExpectedSha256(url, apkAssetName, currentVersionName) }.getOrNull()
                }

                val normalizedLatest = tagName.removePrefix("v").trim()
                GitHubReleaseCheckResult(
                    latestTag = tagName.ifBlank { "v$normalizedCurrent" },
                    currentVersion = currentVersionName,
                    isUpdateAvailable = isNewerSemanticVersion(normalizedLatest, normalizedCurrent),
                    releaseHtmlUrl = htmlUrl,
                    signedApkDownloadUrl = apkAssetUrl,
                    publishedAtIso = publishedAt,
                    releaseTitle = releaseTitle.ifBlank { tagName },
                    releaseNotes = releaseNotes.trim(),
                    apkFileName = apkAssetName,
                    apkSizeBytes = apkAssetSize,
                    expectedApkSha256Hex = expectedSha256
                )
            }
        }

    private fun fetchLatestStableReleaseObject(currentVersionName: String): kotlinx.serialization.json.JsonObject {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repositorySlug/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NexusTS/$currentVersionName (Googlebook-OS)")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("GitHub Releases API returned HTTP ${resp.code}")
            }
            return lenientJson.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
        }
    }

    private fun fetchNewestReleaseObjectFromFeed(currentVersionName: String): kotlinx.serialization.json.JsonObject? =
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repositorySlug/releases?per_page=15")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "NexusTS/$currentVersionName (Googlebook-OS)")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val arr = lenientJson.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray ?: return null
                arr.map { it.jsonObject }
                    .filter { it["draft"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != true }
                    .filterNot {
                        val tag = it["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        !isLegacyPreRebaseVersion(currentVersionName) && isLegacyPreRebaseVersion(tag)
                    }
                    .maxWithOrNull { a, b ->
                        val tagA = a["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val tagB = b["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        when {
                            isNewerSemanticVersion(tagA, tagB) -> 1
                            isNewerSemanticVersion(tagB, tagA) -> -1
                            else -> 0
                        }
                    }
            }
        }.getOrNull()

    private fun fetchExpectedSha256(
        sumsUrl: String,
        apkFileName: String,
        currentVersionName: String
    ): String? {
        if (!isAllowedGitHubHttpsUrl(sumsUrl)) return null
        val req = Request.Builder()
            .url(sumsUrl)
            .header("User-Agent", "NexusTS/$currentVersionName (Googlebook-OS)")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string().orEmpty()
            return parseSha256SumsForAsset(text, apkFileName)
        }
    }

    companion object {
        private val ALLOWED_GITHUB_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )

        /**
         * Historical tags published prior to rebasing NexusTS (`1.1.0`–`1.2.1-beta.2`).
         * Ignored until post-1.0 releases reach `1.1.0` so historical pre-rebase tags are never
         * falsely flagged over `1.0.x`.
         */
        private val LEGACY_PRE_REBASE_VERSIONS = setOf(
            "1.1.0", "1.1.1", "1.1.2", "1.1.3", "1.1.4",
            "1.1.5", "1.1.6", "1.1.7", "1.1.8", "1.1.9",
            "1.2.0", "1.2.1-beta.1", "1.2.1-beta.2"
        )

        private val SHA256_LINE_REGEX = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

        internal fun isLegacyPreRebaseVersion(rawVersion: String): Boolean {
            val cleaned = rawVersion.removePrefix("v").removePrefix("V").trim()
            return cleaned in LEGACY_PRE_REBASE_VERSIONS
        }

        internal fun isAllowedGitHubHttpsUrl(url: String): Boolean {
            val parsed = url.trim().toHttpUrlOrNull() ?: return false
            if (!parsed.scheme.equals("https", ignoreCase = true)) return false
            val host = parsed.host.lowercase()
            return host in ALLOWED_GITHUB_HOSTS
        }

        internal fun parseSha256SumsForAsset(sumsContent: String, preferredApkName: String = ""): String? {
            val entries = sumsContent.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val match = SHA256_LINE_REGEX.matchEntire(line) ?: return@mapNotNull null
                    val digest = match.groupValues[1].lowercase()
                    val fileRef = match.groupValues[2].trim().substringAfterLast('/')
                    fileRef to digest
                }
                .toList()

            if (preferredApkName.isNotBlank()) {
                entries.firstOrNull { (name, _) -> name.equals(preferredApkName, ignoreCase = true) }
                    ?.let { return it.second }
            }
            return entries.firstOrNull { (name, _) -> name.endsWith("-release.apk", ignoreCase = true) }?.second
        }

        internal fun isNewerSemanticVersion(candidate: String, current: String): Boolean {
            val candTrim = candidate.removePrefix("v").removePrefix("V").trim()
            val currTrim = current.removePrefix("v").removePrefix("V").trim()
            if (candTrim.isBlank() || currTrim.isBlank()) return false
            if (!isLegacyPreRebaseVersion(currTrim) && isLegacyPreRebaseVersion(candTrim)) {
                return false
            }

            val candCore = candTrim.substringBefore('-').substringBefore('+')
            val currCore = currTrim.substringBefore('-').substringBefore('+')
            val candPre = candTrim.substringBefore('+').substringAfter('-', "")
            val currPre = currTrim.substringBefore('+').substringAfter('-', "")

            val candParts = candCore.split('.').map { it.toIntOrNull() ?: 0 }
            val currParts = currCore.split('.').map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(candParts.size, currParts.size)
            for (i in 0 until maxLen) {
                val c = candParts.getOrElse(i) { 0 }
                val cur = currParts.getOrElse(i) { 0 }
                if (c > cur) return true
                if (c < cur) return false
            }
            // Same numeric core (e.g. 1.2.1 vs 1.2.1-beta.1): stable > pre-release (except local -dev debug suffix if same release)
            if (candPre.isEmpty() && currPre.isNotEmpty() && !currPre.equals("dev", ignoreCase = true)) {
                return true
            }
            if (candPre.isNotEmpty() && currPre.isEmpty()) {
                return false
            }
            if (candPre.isEmpty() && currPre.isEmpty()) {
                return false
            }
            // Compare dot-separated SemVer 2.0.0 pre-release identifiers (numeric segments compared numerically: beta.10 > beta.2)
            val candPreSegs = candPre.split('.')
            val currPreSegs = currPre.split('.')
            val minSegs = minOf(candPreSegs.size, currPreSegs.size)
            for (i in 0 until minSegs) {
                val cs = candPreSegs[i]
                val us = currPreSegs[i]
                if (cs == us) continue
                val cn = cs.toLongOrNull()
                val un = us.toLongOrNull()
                return when {
                    cn != null && un != null -> cn > un
                    cn != null && un == null -> false // SemVer 2.0: numeric identifiers have lower precedence than non-numeric
                    cn == null && un != null -> true
                    else -> cs > us
                }
            }
            return candPreSegs.size > currPreSegs.size
        }
    }
}
