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
                    val body = resp.body?.string().orEmpty()
                    val obj = lenientJson.parseToJsonElement(body).jsonObject
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
        }

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

        private val SHA256_LINE_REGEX = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

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
            val candTrim = candidate.removePrefix("v").trim()
            val currTrim = current.removePrefix("v").trim()
            if (candTrim.isBlank() || currTrim.isBlank()) return false

            val candCore = candTrim.substringBefore('-').substringBefore('+')
            val currCore = currTrim.substringBefore('-').substringBefore('+')
            val candPre = candTrim.substringAfter('-', "").substringBefore('+')
            val currPre = currTrim.substringAfter('-', "").substringBefore('+')

            val candParts = candCore.split('.').map { it.toIntOrNull() ?: 0 }
            val currParts = currCore.split('.').map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(candParts.size, currParts.size)
            for (i in 0 until maxLen) {
                val c = candParts.getOrElse(i) { 0 }
                val cur = currParts.getOrElse(i) { 0 }
                if (c > cur) return true
                if (c < cur) return false
            }
            // Same numeric core (e.g. 1.1.1 vs 1.1.1-dev or 1.1.1-alpha1): stable > pre-release (except local -dev debug suffix if same release)
            if (candPre.isEmpty() && currPre.isNotEmpty() && !currPre.equals("dev", ignoreCase = true)) {
                return true
            }
            if (candPre.isNotEmpty() && currPre.isEmpty()) {
                return false
            }
            return candPre > currPre && candPre.isNotEmpty()
        }
    }
}
