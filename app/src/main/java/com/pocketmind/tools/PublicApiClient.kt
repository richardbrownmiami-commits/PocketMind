package com.pocketmind.tools

import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.InetAddress
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Read-only discovery against the public APIs.guru catalog. */
internal object PublicApiClient {
    fun discover(query: String, limit: Int): String {
        val terms = query.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
        if (terms.isEmpty()) throw IOException("Enter an API topic or provider name.")
        val providersConnection = open("https://api.apis.guru/v2/providers.json")
        val providersText = try { readBounded(providersConnection, 250000) } finally { providersConnection.disconnect() }
        val providers = JSONObject(providersText).optJSONArray("data") ?: throw IOException("Unexpected API catalog response.")
        val matches = (0 until providers.length()).map { providers.getString(it) }.mapNotNull { provider ->
            val tokens = provider.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).toSet()
            val score = terms.sumOf { term -> if (term in tokens) 4 else if (tokens.any { it.contains(term) || term.contains(it) }) 1 else 0 }
            if (score > 0) provider to score else null
        }.sortedByDescending { it.second }.take(3)
        val found = mutableListOf<String>()
        for ((provider, _) in matches) {
            if (!provider.matches(Regex("[A-Za-z0-9.-]{1,255}"))) continue
            val connection = try { open("https://api.apis.guru/v2/$provider.json") } catch (_: Exception) { continue }
            val body = try { readBounded(connection, 250000) } catch (_: Exception) { "" } finally { connection.disconnect() }
            val apis = try { JSONObject(body).optJSONObject(provider) } catch (_: Exception) { null } ?: continue
            val keys = apis.keys()
            while (keys.hasNext() && found.size < limit.coerceIn(1, 3)) {
                val id = keys.next()
                val api = apis.optJSONObject(id) ?: continue
                val preferred = api.optString("preferred")
                val version = api.optJSONObject("versions")?.optJSONObject(preferred) ?: continue
                val info = version.optJSONObject("info") ?: continue
                val title = info.optString("title", id).take(100)
                val description = info.optString("description").replace(Regex("\\s+"), " ").take(160)
                val spec = version.optString("swaggerUrl").takeIf { it.startsWith("https://") }
                found += buildString {
                    append(title).append(" [").append(id).append("]")
                    if (description.isNotBlank()) append("\nDescription: ").append(description)
                    if (spec != null) append("\nOpenAPI spec: ").append(spec)
                }
            }
        }
        return if (found.isEmpty()) "No catalog matches found. Try a provider name or a more specific topic." else
            "Public API catalog matches (check provider authentication and pricing):\n" + found.joinToString("\n\n").take(3000)
    }


    fun get(urlText: String): String {
        val uri = Uri.parse(urlText.trim())
        val host = uri.host?.lowercase(Locale.ROOT) ?: throw IOException("A complete HTTPS URL is required.")
        if (!uri.scheme.equals("https", true) || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || host.isBlank() ||
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.matches(Regex("[0-9.]+")) || host.contains(":") || urlText.length > 2048) {
            throw IOException("Only public HTTPS URLs on the standard port are allowed.")
        }
        val addresses = try { InetAddress.getAllByName(host) } catch (_: Exception) {
            throw IOException("The API hostname could not be resolved.")
        }
        if (addresses.isEmpty() || addresses.any {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                    it.isSiteLocalAddress || it.isMulticastAddress
            }) throw IOException("Private and local network addresses are blocked.")
        val connection = open(uri.toString())
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("The API returned HTTP $status.")
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (type != "application/json" && type != "text/plain" && !type.endsWith("+json")) {
                throw IOException("Only JSON and plain-text API responses are supported.")
            }
            readBounded(connection, 32768)
        } finally { connection.disconnect() }
    }

    private fun readBounded(connection: HttpsURLConnection, maxBytes: Int): String {
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (output.size() < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            String(output.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    private fun open(url: String): HttpsURLConnection = (URL(url).openConnection() as HttpsURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 7000
        readTimeout = 7000
        instanceFollowRedirects = false
        useCaches = false
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "PocketMind-Android/0.1")
    }
}
