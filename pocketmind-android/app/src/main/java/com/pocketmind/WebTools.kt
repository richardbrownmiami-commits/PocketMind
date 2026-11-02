package com.pocketmind

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** One bounded public search and one bounded public text-page fetch per turn. */
object WebTools {
    private const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/?q="
    private const val MAX_QUERY_CHARS = 240
    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val MAX_PAGE_CHARS = 8_000
    private const val MAX_REDIRECTS = 2
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val localSuffixes = listOf(".localhost", ".local", ".internal", ".home", ".lan", ".test", ".invalid", ".example", ".onion", ".i2p")

    data class SearchResult(val title: String, val snippet: String, val url: String)

    fun searchOnce(query: String): List<SearchResult> {
        val cleanQuery = query.trim().take(MAX_QUERY_CHARS)
        if (cleanQuery.isEmpty()) return emptyList()
        return try {
            val url = SEARCH_ENDPOINT + URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.name())
            val document = fetchHtmlSafe(url) ?: return emptyList()
            val results = LinkedHashMap<String, SearchResult>()
            for (anchor in document.select("a.result__a")) {
                val resolved = unwrapDuckDuckGoRedirect(anchor.attr("href")) ?: continue
                val safeUrl = canonicalPublicHttpsUrl(resolved) ?: continue
                if (safeUrl in results) continue
                val container = anchor.parents().firstOrNull { it.hasClass("result") } ?: anchor.parent()
                val snippet = container?.selectFirst(".result__snippet")?.text().orEmpty().take(400)
                results[safeUrl] = SearchResult(anchor.text().take(200), snippet, safeUrl)
                if (results.size == 2) break
            }
            results.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Returns sanitized body text, or null when the URL/request is not allowed or fails. */
    fun fetchOnce(url: String, maxChars: Int = MAX_PAGE_CHARS): String? {
        return try {
            val safeUrl = canonicalPublicHttpsUrl(url) ?: return null
            val document = fetchHtmlSafe(safeUrl) ?: return null
            document.select("script,style,noscript,svg,iframe,nav,header,footer,form,button,aside").remove()
            val cap = maxChars.coerceIn(0, MAX_PAGE_CHARS)
            (document.body()?.text() ?: "").take(cap)
        } catch (_: Exception) {
            null
        }
    }

    /** Normalize an HTTPS URL and reject any hostname resolving to a non-public address. */
    fun canonicalPublicHttpsUrl(raw: String): String? {
        return try {
            if (raw.any { it.isISOControl() } || '\\' in raw) return null
            val uri = URI(raw.trim())
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return null
            val rawHost = uri.host ?: return null
            if (':' in rawHost || rawHost.startsWith("[") || IPV4_LITERAL.matches(rawHost)) return null
            val host = IDN.toASCII(rawHost.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
            if (host.isEmpty() || host.length > 253 || '.' !in host) return null
            if (localSuffixes.any { host == it.removePrefix(".") || host.endsWith(it) }) return null

            // Check every DNS answer; the subsequent URL connection still has a residual
            // DNS-rebinding race because the platform resolves the hostname again.
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty() || addresses.any { !isPublicInternetAddress(it) }) return null

            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            "https://$host$path$query"
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchHtmlSafe(initialUrl: String): Document? {
        // Refuse to use a process-wide cookie store: this tool does not send or receive cookies.
        if (CookieHandler.getDefault() != null) return null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var current = initialUrl
        var redirects = 0
        while (true) {
            ensureBeforeDeadline(deadline)
            val safeUrl = canonicalPublicHttpsUrl(current) ?: return null
            val connection = (java.net.URL(safeUrl).openConnection() as? HttpsURLConnection) ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "PocketMind/0.1 (Android)")
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")

                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirects >= MAX_REDIRECTS) return null
                    val location = connection.getHeaderField("Location") ?: return null
                    val next = URI(safeUrl).resolve(location).toString()
                    current = canonicalPublicHttpsUrl(next) ?: return null
                    redirects++
                    continue
                }
                if (status !in 200..299) return null
                val mime = (connection.contentType ?: "").substringBefore(';').trim().lowercase()
                if (mime !in setOf("text/html", "application/xhtml+xml", "text/plain")) return null
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_RESPONSE_BYTES) return null

                val bytes = connection.inputStream.use { readBounded(it, deadline) } ?: return null
                return ByteArrayInputStream(bytes).use { Jsoup.parse(it, null, safeUrl) }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readBounded(input: java.io.InputStream, deadline: Long): ByteArray? {
        val output = ByteArrayOutputStream(minOf(MAX_RESPONSE_BYTES, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            ensureBeforeDeadline(deadline)
            val count = input.read(buffer)
            if (count < 0) break
            if (total + count > MAX_RESPONSE_BYTES) return null
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun ensureBeforeDeadline(deadline: Long) {
        if (System.nanoTime() >= deadline) throw SocketTimeoutException("Web request timed out")
    }

    private fun unwrapDuckDuckGoRedirect(href: String): String? {
        return try {
            val absolute = URI("https://duckduckgo.com/").resolve(href)
            val host = absolute.host?.lowercase().orEmpty()
            if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) {
                val value = absolute.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
                    val key = part.substringBefore('=')
                    if (key == "uddg") URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8.name()) else null
                }
                if (value != null) value else absolute.toString()
            } else {
                absolute.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isPublicInternetAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false

        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) return isPublicIpv4(bytes)
        if (address is Inet6Address) {
            if (bytes.size != 16) return false
            // Permit only global-unicast 2000::/3; reject documentation and transition ranges.
            if ((bytes[0] and 0xe0) != 0x20) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) return false // 2001:db8::/32
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x00 && bytes[3] == 0x00) return false // Teredo
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x00 && (bytes[3] and 0xf0) == 0x10) return false // ORCHID
            val mappedV4 = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
            if (mappedV4) return isPublicIpv4(bytes.drop(12))
            return true
        }
        return false
    }

    private fun isPublicIpv4(b: List<Int>): Boolean {
        if (b.size != 4) return false
        val a = b[0]; val second = b[1]; val third = b[2]
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false
        if (a == 100 && second in 64..127) return false // CGNAT 100.64/10
        if (a == 169 && second == 254) return false
        if (a == 172 && second in 16..31) return false
        if (a == 192 && second == 0) return false // IETF protocol assignments
        if (a == 192 && second == 0 && third == 2) return false // documentation
        if (a == 192 && second == 88 && third == 99) return false // deprecated 6to4 relay
        if (a == 192 && second == 168) return false
        if (a == 198 && second in 18..19) return false // benchmarking
        if (a == 198 && second == 51 && third == 100) return false // documentation
        if (a == 203 && second == 0 && third == 113) return false // documentation
        return true
    }
}
