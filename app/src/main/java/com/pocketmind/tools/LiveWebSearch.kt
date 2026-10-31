package com.pocketmind.tools

import android.content.Context
import android.net.Uri
import android.text.Html
import android.webkit.WebSettings
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal data class LiveSearchHit(
    val title: String,
    val url: String,
    val snippet: String
)

/** A per-message cancellation signal that can interrupt an in-flight search connection. */
internal class SearchCancellation {
    private val cancelled = AtomicBoolean(false)
    private val searchStarted = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpsURLConnection?>(null)

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        activeConnection.get()?.disconnect()
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("Web search was cancelled")
    }

    fun beginSearch() {
        throwIfCancelled()
        if (!searchStarted.compareAndSet(false, true)) {
            throw IOException("Only one web search is allowed per message")
        }
    }

    fun attach(connection: HttpsURLConnection) {
        activeConnection.set(connection)
        if (isCancelled) connection.disconnect()
    }

    fun detach(connection: HttpsURLConnection) {
        activeConnection.compareAndSet(connection, null)
    }
}

/** Network access for explicit and model-requested web.search calls through the validated tool runtime. */
internal object LiveWebSearch {
    private const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/?q="
    private const val MAX_SEARCH_CHARS = 240
    private const val MAX_RESPONSE_CHARS = 180_000
    private const val MAX_SOURCE_URL_CHARS = 512

    private val attributeRegex = Regex("""(?i)([a-z][a-z0-9_:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    fun search(context: Context, query: String, limit: Int, cancellation: SearchCancellation): List<LiveSearchHit> {
        cancellation.throwIfCancelled()
        val normalized = query.trim().take(MAX_SEARCH_CHARS)
        if (normalized.isEmpty()) throw IOException("A search query is required")
        cancellation.beginSearch()
        val userAgent = WebSettings.getDefaultUserAgent(context)
        val encodedQuery = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name())
        val html = getSearchPage(encodedQuery, userAgent, cancellation)
            ?: throw IOException("The search provider returned no page")
        cancellation.throwIfCancelled()

        val anchors = elementsWithClass(html, "result__a")
        val snippets = elementsWithClass(html, "result__snippet")
        val results = anchors.mapIndexedNotNull { index, element ->
            val href = attribute(element.first, "href") ?: return@mapIndexedNotNull null
            val resultUrl = sourceUrl(href) ?: return@mapIndexedNotNull null
            val title = plainText(element.second).take(120)
            if (title.isBlank()) return@mapIndexedNotNull null
            val snippet = snippets.getOrNull(index)?.second?.let(::plainText)?.take(180).orEmpty()
            LiveSearchHit(title, resultUrl, snippet)
        }.distinctBy { it.url }.take(limit.coerceIn(1, 2))

        if (results.isEmpty() && Regex("(?i)anomaly-modal|captcha|unusual traffic").containsMatchIn(html)) {
            throw IOException("The search provider temporarily blocked this request")
        }
        cancellation.throwIfCancelled()
        return results
    }

    private fun getSearchPage(encodedQuery: String, userAgent: String, cancellation: SearchCancellation): String? {
        cancellation.throwIfCancelled()
        val connection = (URL(SEARCH_ENDPOINT + encodedQuery).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 7_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("User-Agent", userAgent)
        }
        cancellation.attach(connection)
        return try {
            cancellation.throwIfCancelled()
            val status = connection.responseCode
            cancellation.throwIfCancelled()
            if (status == 202) throw IOException("The search provider temporarily blocked this request")
            if (status !in 200..299) return null
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (contentType !in setOf("text/html", "application/xhtml+xml")) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(4096)
                while (output.length < MAX_RESPONSE_CHARS) {
                    cancellation.throwIfCancelled()
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - output.length))
                    if (count < 0) break
                    output.append(buffer, 0, count)
                }
                cancellation.throwIfCancelled()
                output.toString()
            }
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }

    private fun elementsWithClass(html: String, className: String): List<Pair<String, String>> {
        // Search-result snippets are commonly <td>/<div> elements, not anchors.
        // Parsing only <a> tags silently discarded every snippet and made the
        // agent treat valid search hits as title-only results.
        val elementRegex = Regex("""(?is)<([a-z][a-z0-9]*)\b([^>]*)>(.*?)</\1\s*>""")
        return elementRegex.findAll(html).mapNotNull { match ->
            val attributes = match.groupValues[2]
            val classes = attribute(attributes, "class")?.split(Regex("\\s+")) ?: return@mapNotNull null
            if (className !in classes) null else attributes to match.groupValues[3]
        }.toList()
    }

    private fun attribute(attributes: String, name: String): String? =
        attributeRegex.findAll(attributes).firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
            ?.let { match -> (2..4).asSequence().map { match.groupValues[it] }.firstOrNull { it.isNotEmpty() } }

    private fun sourceUrl(rawHref: String): String? {
        val decodedHref = rawHref.replace("&amp;", "&")
        val redirectUri = Uri.parse(if (decodedHref.startsWith("//")) "https:$decodedHref" else decodedHref)
        val redirectHost = redirectUri.host?.lowercase(Locale.ROOT).orEmpty()
        val candidate = if ((redirectHost == "duckduckgo.com" || redirectHost.endsWith(".duckduckgo.com")) && redirectUri.path?.startsWith("/l/") == true) {
            redirectUri.getQueryParameter("uddg") ?: return null
        } else {
            decodedHref
        }
        val uri = Uri.parse(candidate)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return null
        return uri.toString().takeIf { it.length <= MAX_SOURCE_URL_CHARS }
    }

    private fun plainText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
}
