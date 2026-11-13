package com.nexusts.app.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class GitHubReleaseCheckResult(
    val latestTag: String,
    val currentVersion: String,
    val isUpdateAvailable: Boolean,
    val releaseHtmlUrl: String,
    val signedApkDownloadUrl: String?,
    val publishedAtIso: String
)

/**
 * Play-Protect-Compliant GitHub Releases Update Checker for Direct Non-Play-Store Installs (`v1.1`).
 *
 * Strictly zero `REQUEST_INSTALL_PACKAGES` permissions — queries the public GitHub Releases API over TLS
 * and opens the verified release download in the user's browser via `Intent.ACTION_VIEW` when requested.
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
                    val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull
                        ?: "https://github.com/$repositorySlug/releases/latest"
                    val publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    var apkAssetUrl: String? = null
                    (obj["assets"] as? JsonArray)?.forEach { assetEl ->
                        val asset = assetEl.jsonObject
                        val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val dl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                        if (name.endsWith("-release.apk", ignoreCase = true) && !dl.isNullOrBlank()) {
                            apkAssetUrl = dl
                        }
                    }

                    val normalizedLatest = tagName.removePrefix("v").trim()
                    GitHubReleaseCheckResult(
                        latestTag = tagName.ifBlank { "v$normalizedCurrent" },
                        currentVersion = currentVersionName,
                        isUpdateAvailable = isNewerSemanticVersion(normalizedLatest, normalizedCurrent),
                        releaseHtmlUrl = htmlUrl,
                        signedApkDownloadUrl = apkAssetUrl,
                        publishedAtIso = publishedAt
                    )
                }
            }
        }

    companion object {
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
