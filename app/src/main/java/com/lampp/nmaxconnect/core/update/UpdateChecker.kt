package com.lampp.nmaxconnect.core.update

import com.lampp.nmaxconnect.core.di.IoDispatcher
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** A published release newer than the installed app. [url] is the APK, or the release page when it has none. */
data class AppUpdate(val version: String, val notes: String, val url: String)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val body: String? = null,
    @SerialName("html_url") val page: String,
    val assets: List<Asset> = emptyList(),
) {
    @Serializable
    data class Asset(val name: String, @SerialName("browser_download_url") val url: String)
}

/** Latest GitHub release of this repo (public API, no key; 60 requests/hour per IP is plenty for a tap). */
class UpdateChecker @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** The newer release, or null when [current] is the latest (or nothing is published yet). Throws [IOException]. */
    suspend fun check(current: String): AppUpdate? = withContext(ioDispatcher) {
        val request = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null // no release yet
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("GitHub: empty body")
            val release = try {
                json.decodeFromString<GitHubRelease>(body)
            } catch (e: IllegalArgumentException) { // SerializationException extends it
                throw IOException("GitHub: unexpected response (${e.message})", e)
            }
            val version = release.tag.removePrefix("v")
            if (!isNewer(version, current)) return@withContext null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }?.url ?: release.page
            AppUpdate(version, plainNotes(release.body.orEmpty()), apk)
        }
    }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/lamphung-phuc/nmax_connect/releases/latest"
    }
}

/** GitHub release Markdown → readable plain text: drops heading/quote markers and bold/code marks, `- ` lists become `• `. */
fun plainNotes(markdown: String): String = markdown.lines().joinToString("\n") { line ->
    line.trimEnd()
        .replace(Regex("^#+\\s*"), "")
        .replace(Regex("^>\\s?"), "")
        .replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
        .replace("**", "").replace("__", "").replace("`", "")
}.replace(Regex("\n{3,}"), "\n\n").trim()

/** Dotted numeric compare ("1.10.0" > "1.9.2"); anything after '-' is ignored, missing parts count as 0. */
fun isNewer(latest: String, current: String): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
