package com.arkhamscan

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** Requests public metadata only. This client has no access to camera frames. */
class ArkhamDbCatalog(private val get: (String) -> ByteArray = ::downloadJson) {
    fun download(): List<CatalogCard> {
        // The encounter flag is documented by ArkhamDB's public API controller.
        val packs = get(PACKS_URL)
        val cards = get(CARDS_URL)
        return parse(cards, packs)
    }
    companion object {
        const val PACKS_URL = "https://arkhamdb.com/api/public/packs/"
        const val CARDS_URL = "https://arkhamdb.com/api/public/cards/?encounter=1"
        fun parse(cardsBytes: ByteArray, packsBytes: ByteArray): List<CatalogCard> {
            require(cardsBytes.size <= CatalogImporter.MAX_BYTES && packsBytes.size <= CatalogImporter.MAX_BYTES) { "ArkhamDB response exceeds size limit" }
            val packs = JSONArray(packsBytes.toString(Charsets.UTF_8))
            val packRecords = (0 until packs.length()).map { packs.getJSONObject(it) }
            val families = packRecords.filter { it.has("cycle_position") }.groupBy { "${it.optInt("chapter", 1)}:${it.getInt("cycle_position")}" }
            val names = (0 until packs.length()).associate { i -> packs.getJSONObject(i).let { it.getString("code") to it.getString("name") } }
            require(names.isNotEmpty()) { "ArkhamDB returned no packs" }
            val cards = JSONArray(cardsBytes.toString(Charsets.UTF_8))
            require(cards.length() in 1..50000) { "ArkhamDB returned no cards or too many records" }
            val normalized = JSONArray()
            for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i)
                if (c.optBoolean("hidden") || c.optString("name").isBlank()) continue
                val code = c.getString("code")
                require(code.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid ArkhamDB card code" }
                val pack = c.getString("pack_code")
                val packInfo = packRecords.firstOrNull { it.getString("code") == pack }
                val familyKey = packInfo?.takeIf { it.has("cycle_position") && it.getInt("cycle_position") !in setOf(50, 60, 61, 70, 80, 90) }?.let { "${it.optInt("chapter", 1)}:${it.getInt("cycle_position")}" }
                val family = familyKey?.let { key -> families[key]?.sortedWith(compareBy<JSONObject> { it.optInt("position", Int.MAX_VALUE) }.thenBy { it.getString("code") })?.firstOrNull() }
                val packName = names[pack] ?: error("Unknown ArkhamDB pack: $pack. Previous catalog kept; try updating later.")
                normalized.put(JSONObject().put("id", "arkhamdb:$code").put("title", c.getString("name"))
                    .put("number", if (c.isNull("position")) "" else c.get("position").toString())
                    .put("pack", pack).put("packName", packName).put("type", c.optString("type_code", ""))
                    .put("encounter", c.optString("encounter_code", ""))
                    .put("familyId", familyKey?.let { "arkhamdb-cycle:$it" } ?: "")
                    .put("familyName", family?.getString("name")?.let { "$it · cycle group" } ?: ""))
            }
            // Deliberately omit image URLs/bytes, rules, flavor, deck limits and expected quantities.
            val catalog = JSONObject().put("schemaVersion", 1).put("source", CARDS_URL).put("cards", normalized)
            return CatalogImporter.parse(catalog.toString().toByteArray(Charsets.UTF_8))
        }
        fun cardPage(card: CatalogCard): String? {
            val code = card.id.removePrefix("arkhamdb:")
            val fromArkhamDb = card.id.startsWith("arkhamdb:") || runCatching { URL(card.source).host == "arkhamdb.com" }.getOrDefault(false)
            return if (fromArkhamDb && code.matches(Regex("[A-Za-z0-9_-]{1,64}"))) "https://arkhamdb.com/card/$code" else null
        }
    }
}

/** HTTPS GET, platform TLS checks, bounded responses, finite timeouts, no persistent HTTP cache. */
private fun downloadJson(address: String): ByteArray {
    require(address == ArkhamDbCatalog.CARDS_URL || address == ArkhamDbCatalog.PACKS_URL)
    var url = URL(address)
    repeat(4) {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ArkhamScan/0.3.0 (personal collection catalog)")
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val next = URL(url, connection.getHeaderField("Location") ?: error("Missing redirect target"))
                require(next.protocol == "https" && next.host in setOf("arkhamdb.com", "www.arkhamdb.com") && next.userInfo == null && next.port in listOf(-1, 443)) { "Unexpected ArkhamDB redirect" }
                url = next
            } else {
                require(status == 200) { "ArkhamDB returned HTTP $status. Check your connection and try again; your saved catalog is unchanged." }
                require(connection.contentType.orEmpty().substringBefore(';').trim() in setOf("application/json", "text/json")) { "ArkhamDB did not return JSON. Your saved catalog is unchanged." }
                val raw = connection.inputStream
                val stream = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                return stream.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(output.size() + n <= CatalogImporter.MAX_BYTES) { "ArkhamDB response exceeds size limit" }
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
            }
        } finally { connection.disconnect() }
    }
    error("Too many ArkhamDB redirects")
}
