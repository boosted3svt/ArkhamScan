package com.arkhamscan

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arkhamscan.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as ScanApplication).database
    private val store = db.store()
    val cards = store.cards().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val copies = store.copies().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val exceptions = store.exceptions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val passes = store.passes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val routes = store.routes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val databaseReady = MutableStateFlow(false)
    val message = MutableStateFlow("Tap Download from ArkhamDB to get started.")
    val downloadingCatalog = MutableStateFlow(false)
    val suggestions = MutableStateFlow<List<CatalogCard>>(emptyList())
    val mode = MutableStateFlow(Mode.INTERACTIVE_RESOLUTION)
    val silent = MutableStateFlow(false)
    val speechRate = MutableStateFlow(1f)
    val capacity = MutableStateFlow(5)
    val cues = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val preferences = app.getSharedPreferences("settings", 0)
    val sessionId: String = preferences.getString("session", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("session", it).apply()
    }
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private var recognitionIndex: List<Pair<CatalogCard, String>> = emptyList()
    init {
        viewModelScope.launch { cards.collect { list -> recognitionIndex = list.map { it to it.title.lowercase().replace(nonWord, " ").trim() } } }
        capacity.value = preferences.getInt("capacity", 5)
        silent.value = preferences.getBoolean("silent", false)
        speechRate.value = preferences.getFloat("speechRate", 1f)
        work { store.session(SessionRecord(sessionId, System.currentTimeMillis(), "Collection session")); databaseReady.value = true }
    }
    fun configure(piles: Int, muted: Boolean, rate: Float) {
        capacity.value = piles.coerceIn(3, 32); silent.value = muted; speechRate.value = rate
        preferences.edit().putInt("capacity", capacity.value).putBoolean("silent", muted).putFloat("speechRate", rate).apply()
    }
    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { message.value = e.message ?: "Operation failed" }
    }
    fun downloadArkhamDb(client: ArkhamDbCatalog = ArkhamDbCatalog()) {
        if (downloadingCatalog.value) return
        downloadingCatalog.value = true
        work {
            try {
                message.value = "Downloading ArkhamDB card and pack metadata…"
                val imported = withContext(Dispatchers.IO) { client.download() }
                store.importCards(imported)
                message.value = "${imported.size} ArkhamDB printings ready. Search or scan in Interactive mode; the catalog now works offline too."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message.value = "Could not update ArkhamDB: ${e.message ?: "check your connection"}. Your inventory and previous catalog are unchanged." }
            finally { downloadingCatalog.value = false }
        }
    }
    fun importCatalog(uri: Uri) = work {
        require(!downloadingCatalog.value) { "Wait for the ArkhamDB download to finish" }
        val imported = withContext(Dispatchers.IO) {
            val data = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= CatalogImporter.MAX_BYTES) { "Catalog exceeds 16 MiB" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
                ?: error("Could not open catalog")
            CatalogImporter.parse(data)
        }
        store.importCards(imported)
        message.value = "Imported ${imported.size} printings. Retail manifests remain unverified."
    }
    /** OCR supplies suggestions only. No confidence calibration or image identity is fabricated. */
    fun recognizedText(text: String) {
        val normalized = " " + text.lowercase().replace(nonWord, " ").trim() + " "
        suggestions.value = recognitionIndex.asSequence().filter { (_, title) ->
            title.length >= 4 && normalized.contains(" $title ")
        }.take(8).map { it.first }.toList()
    }
    fun queuePresentation() = work {
        val id = UUID.randomUUID().toString()
        val candidates = JSONArray(suggestions.value.map { it.id }).toString()
        val pass = passes.value.firstOrNull { it.status == "active" }?.id
        store.addException(ExceptionRecord(id, sessionId, System.currentTimeMillis(), "UNVALIDATED_OCR", candidates, passId = pass))
        message.value = "Re-sort · OCR is experimental; ownership unchanged"
        if (!silent.value) cues.tryEmit("Re-sort")
    }
    fun confirm(card: CatalogCard, event: String, exceptionId: String? = null) = work {
        require(mode.value != Mode.RESORT_EXISTING) { "Re-sort mode cannot create owned copies" }
        if (store.confirm(card, event, sessionId, exceptionId)) {
            message.value = "Added ${card.title} · physical copy confirmed. Retail origin unverified."
            if (!silent.value) cues.tryEmit("Copy confirmed")
        }
    }
    fun dismiss(id: String, decision: String) = work { store.dismiss(id, decision); message.value = "Decision saved · ownership unchanged" }
    fun undo(id: String) = work { store.undo(id); message.value = "Copy removed · audit record retained" }

    fun makePlan(source: String = "inventoried collection", parent: SavedPass? = null, pile: Int? = null) = work {
        val active = copies.value.filter { it.status == "active" }
        require(active.isNotEmpty()) { "Confirm physical copies before planning a sorting pass" }
        val sourceCounts: Map<String, Int>
        val cardPacks: Map<String, String>
        if (parent != null && pile != null) {
            require(parent.status != "abandoned") { "This source pass was abandoned; plan from reconciled inventory instead" }
            val old = JSONObject(parent.json)
            val oldCounts = old.getJSONObject("sourceCounts")
            val oldPacks = old.getJSONObject("cardPacks")
            val allRouted = routes.value.filter { it.passId == parent.id }.groupingBy { it.cardId }.eachCount()
            require(oldCounts.keys().asSequence().all { allRouted[it] == oldCounts.getInt(it) }) { "Finish or reconcile the entire source pass before subdividing" }
            val oldPile = old.getJSONArray("piles").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.single { it.getInt("number") == pile } }
            val destinations = oldPile.getJSONArray("destinations").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.toSet() }
            sourceCounts = oldCounts.keys().asSequence().filter { oldPacks.getString(it) in destinations }.associateWith { oldCounts.getInt(it) }
            cardPacks = sourceCounts.keys.associateWith { oldPacks.getString(it) }
        } else {
            sourceCounts = active.groupingBy { it.cardId }.eachCount()
            cardPacks = active.associate { it.cardId to it.pack }
        }
        require(passes.value.none { it.status == "active" && it.id != parent?.id }) { "Finish the active pass first; its map is immutable" }
        val ds = sourceCounts.entries.groupBy { cardPacks.getValue(it.key) }.map { (pack, counts) ->
            Destination(pack, active.firstOrNull { it.pack == pack }?.packName ?: pack, counts.sumOf { it.value })
        }
        if (parent != null && pile != null) require(passes.value.none {
            val previous = JSONObject(it.json)
            previous.optString("parentId") == parent.id && previous.optInt("sourcePile", -1) == pile
        }) { "This source pile already has a saved child pass. Resume it instead." }
        val plan = PilePlanner.plan(ds, capacity.value, source, parent?.id ?: "root")
        val json = JSONObject().put("version", plan.version).put("source", source).put("capacity", plan.capacity)
            .put("parentId", parent?.id ?: JSONObject.NULL).put("sourcePile", pile ?: JSONObject.NULL)
            .put("instructions", plan.instructions).put("sourceCounts", JSONObject(sourceCounts)).put("cardPacks", JSONObject(cardPacks))
            .put("piles", jsonArray(plan.piles.map { p -> JSONObject().put("number", p.number).put("destinations", jsonArray(p.destinations.map {
                JSONObject().put("id", it.id).put("label", it.label).put("quantity", it.quantity)
            })) }))
        if (parent != null) store.completePass(parent.id)
        store.savePass(SavedPass(UUID.randomUUID().toString(), json.toString(), System.currentTimeMillis()))
        mode.value = Mode.RESORT_EXISTING
        message.value = "Sorting pass frozen. Catalog packs are provisional destinations, not verified retail products."
    }
    fun abandonPass(pass: SavedPass) = work {
        store.abandonPass(pass.id)
        message.value = "Pass abandoned; records retained. Reconcile physical source piles before planning again. Ownership unchanged."
    }
    fun finishPass(pass: SavedPass) = work {
        val counts = JSONObject(pass.json).getJSONObject("sourceCounts")
        val actual = routes.value.filter { it.passId == pass.id }.groupingBy { it.cardId }.eachCount()
        require(counts.keys().asSequence().all { (actual[it] ?: 0) == counts.getInt(it) }) { "Source count mismatch. Reconcile physical cards before completion." }
        store.completePass(pass.id); message.value = "Pass complete · ownership unchanged"
    }
    fun route(cardId: String, event: String) = work {
        val pass = passes.value.firstOrNull { it.status == "active" } ?: error("Create a sorting pass first")
        val json = JSONObject(pass.json)
        val pack = json.getJSONObject("cardPacks").optString(cardId)
        val piles = json.getJSONArray("piles")
        val pile = (0 until piles.length()).map { piles.getJSONObject(it) }.singleOrNull { p ->
            val ds = p.getJSONArray("destinations")
            (0 until ds.length()).any { ds.getJSONObject(it).getString("id") == pack }
        }?.getInt("number") ?: error("Card is outside this source pile")
        if (store.route(RouteRecord(event, pass.id, cardId, pile, System.currentTimeMillis()))) {
            message.value = "Pile $pile · existing copy routed; ownership unchanged"
            if (!silent.value) cues.tryEmit("Pile $pile")
        }
    }
    suspend fun exportJson(): String {
        return JSONObject().put("schemaVersion", 1).put("exportedAt", System.currentTimeMillis())
            .put("catalogSource", cards.value.firstOrNull()?.source ?: JSONObject.NULL)
            .put("copies", jsonArray(copies.value.map { it.toJson() }))
            .put("exceptions", jsonArray(exceptions.value.map { JSONObject().put("id", it.id).put("sessionId", it.sessionId)
                .put("at", it.createdAt).put("reason", it.reason).put("candidates", JSONArray(it.candidates)).put("status", it.status).put("passId", it.passId) }))
            .put("passes", jsonArray(passes.value.map { JSONObject().put("id", it.id).put("status", it.status).put("plan", JSONObject(it.json)) }))
            .put("routes", jsonArray(routes.value.map { JSONObject().put("id", it.id).put("passId", it.passId).put("cardId", it.cardId).put("pile", it.pile).put("at", it.at) }))
            .put("sessions", jsonArray(store.allSessions().map { JSONObject().put("id", it.id).put("name", it.name).put("startedAt", it.startedAt) }))
            .put("audit", jsonArray(store.allAudit().map { JSONObject().put("id", it.id).put("at", it.at).put("action", it.action).put("subject", it.subject).put("details", it.details) }))
            .toString(2)
    }
    fun exportCsv(): String {
        fun q(s: String): String {
            val safe = if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$s" else s
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        return "card_id,title,reference_pack,quantity,retail_provenance\r\n" + copies.value.filter { it.status == "active" }.groupBy { it.cardId }.values.joinToString("\r\n") {
            listOf(it.first().cardId, it.first().title, it.first().packName, it.size.toString(), "unverified").joinToString(",", transform = ::q)
        }
    }
    // The Application owns Room; rotation and ViewModel teardown must not close it.
}
