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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    internal val db = (app as ScanApplication).database
    internal val store = db.store()
    internal val operationMutex = Mutex()
    val remote = RemoteSession(this, viewModelScope)
    val cards = store.cards().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val copies = store.copies().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val exceptions = store.exceptions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val passes = store.passes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val routes = store.routes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val liveRuns = store.liveRuns().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val liveGroups = store.liveGroups().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val livePlacements = store.livePlacements().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val liveGrouping = MutableStateFlow("PACK")
    val lastPile = MutableStateFlow<String?>(null)
    val waitingForRemoval = MutableStateFlow(false)
    private val candidateLatch = CandidateLatch()
    private var latestCandidates: List<CatalogCard> = emptyList()
    val referencePacks = store.referencePacks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bulkInventory = store.bulkInventory().map { it ?: BulkInventory() }.stateIn(viewModelScope, SharingStarted.Eagerly, BulkInventory())
    val discovery = combine(cards, copies, referencePacks, bulkInventory) { cs, owned, packs, bulk ->
        withContext(Dispatchers.Default) {
            DiscoveryEngine.build(cs.map { IndexCard(it.id, it.title, it.pack, it.expectedQuantity.takeIf { q -> q > 0 }, it.canonicalId.ifBlank { it.id }, it.encounter, it.encounterName) },
                packs.ifEmpty { cs.distinctBy { it.pack }.map { ReferencePack(it.pack, it.packName, it.familyId, it.familyName, "", "[]", "", it.source, it.version) } }.map { p -> IndexPack(p.id, p.title, p.familyId, p.familyName, p.released,
                    JSONArray(p.members).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }, p.reprintType) },
                owned.filter { it.status == "active" }.groupingBy { it.cardId }.eachCount(), bulk.cutoff)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryIndex(emptyList(), 0))
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
        liveGrouping.value = preferences.getString("liveGrouping", "PACK") ?: "PACK"
        silent.value = preferences.getBoolean("silent", false)
        speechRate.value = preferences.getFloat("speechRate", 1f)
        work { store.session(SessionRecord(sessionId, System.currentTimeMillis(), "Bulk collection inventory")); store.insertBulkState(BulkInventory()); databaseReady.value = true }
    }
    fun configure(piles: Int, muted: Boolean, rate: Float) {
        capacity.value = piles.coerceIn(3, 32); silent.value = muted; speechRate.value = rate
        preferences.edit().putInt("capacity", capacity.value).putBoolean("silent", muted).putFloat("speechRate", rate).apply()
    }
    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        try { operationMutex.withLock { block() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { message.value = e.message ?: "Operation failed"; remote.error("operation", message.value) }
    }
    fun downloadArkhamDb(client: ArkhamDbCatalog = ArkhamDbCatalog()) {
        if (downloadingCatalog.value) return
        downloadingCatalog.value = true
        viewModelScope.launch {
            try {
                message.value = "Downloading ArkhamDB card and pack metadata…"
                val imported = withContext(Dispatchers.IO) { client.downloadSnapshot() }
                operationMutex.withLock {
                    store.importCards(imported.cards, imported.packs)
                    remote.newPresentation()
                }
                message.value = "${imported.cards.size} ArkhamDB printings ready. Search or scan in Interactive mode; the catalog now works offline too."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { remote.error("catalog", e.message ?: "Download failed"); message.value = "Could not update ArkhamDB: ${e.message ?: "check your connection"}. Your inventory and previous catalog are unchanged." }
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
            CatalogImporter.snapshot(data)
        }
        store.importCards(imported.cards, imported.packs)
                remote.newPresentation()
        message.value = "Imported ${imported.cards.size} printings. Retail manifests remain unverified."
    }
    fun setCutoff(cutoff: String) = work {
        require(cutoff.trim().isBlank() || runCatching { java.time.LocalDate.parse(cutoff.trim()) }.isSuccess) { "Use YYYY-MM-DD or leave the cutoff blank." }
        store.setCutoff(cutoff.trim())
        message.value = "Reference cutoff updated. Ownership is unchanged; inventory review is open."
    }
    fun finishBulkInventory(revision: Long) = work {
        require(cards.value.isNotEmpty()) { "Download the reference index first" }
        store.finishBulkInventory(revision)
        message.value = "Inventory marked finished. Outstanding entries are potentially missing; source gaps and unresolved attempts still need review."
    }
    fun reopenInventory() = work { store.touchInventory(); message.value = "Inventory reopened. Outstanding entries mean not yet accounted for." }
    fun discoveryCsv(scopeId: String? = null, outstandingOnly: Boolean = false): String {
        fun q(s: String): String {
            val safe = if (s.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$s" else s
            return "\"${safe.replace("\"", "\"\"")}\""
        }
        val finished = bulkInventory.value.finishedAt != null
        val rows = discovery.value.scopes.filter { if (scopeId == null) it.inTarget else it.id == scopeId }.flatMap { scope -> scope.rows.filter { !outstandingOnly || (it.outstanding?.let { n -> n > 0 } ?: (it.observed == 0)) }.map { row ->
            listOf(scope.kind, scope.title, row.id, row.title, row.expected?.toString() ?: "unknown", row.observed.toString(), row.exact.toString(),
                row.outstanding?.toString() ?: "unknown", row.extras?.toString() ?: "unknown", when { row.expected?.let { row.observed >= it } == true -> "listed_quantity_accounted"; row.expected == null && row.observed > 0 -> "design_present_copy_target_unknown"; finished -> "potentially_missing"; else -> "not_yet_accounted_for" }, scope.sourceNote, bulkInventory.value.cutoff)
        } }
        return "scope_type,scope,logical_card_id,title,source_quantity,accounted_copies,exact_printing_copies,outstanding,extra,status,reference_note,cutoff\r\n" + rows.joinToString("\r\n") { it.joinToString(",", transform = ::q) }
    }
    /** OCR supplies suggestions only. No confidence calibration or image identity is fabricated. */
    fun recognizedText(text: String) {
        if (remote.commandBusy) return
        remote.ocrSummary.value = text.take(400)
        val normalized = " " + text.lowercase().replace(nonWord, " ").trim() + " "
        latestCandidates = recognitionIndex.asSequence().filter { (_, title) ->
            title.length >= 4 && normalized.contains(" $title ")
        }.take(8).map { it.first }.toList()
        val heldIds = candidateLatch.observe(latestCandidates.map { it.id }, android.os.SystemClock.elapsedRealtime())
        val byId = cards.value.associateBy { it.id }
        val held = heldIds.mapNotNull { byId[it] }
        if (held.map { it.id } != suggestions.value.map { it.id }) remote.newPresentation()
        suggestions.value = held
        waitingForRemoval.value = candidateLatch.waitingForRemoval
    }
    fun nextCard() {
        remote.newPresentation()
        lastPile.value = null
        candidateLatch.next(); suggestions.value = emptyList(); latestCandidates = emptyList(); waitingForRemoval.value = false
    }
    fun setLiveGrouping(grouping: String) {
        require(grouping in setOf("PACK", "CYCLE"))
        liveGrouping.value = grouping
        preferences.edit().putString("liveGrouping", grouping).apply()
    }
    fun startFreshPiles() = work { startFreshPilesNow() }
    internal suspend fun startFreshPilesNow() {
        val version = store.cards().first().firstOrNull()?.version ?: error("Download a reference catalog first")
        store.newLiveRun(capacity.value, liveGrouping.value, version)
        lastPile.value = null
        message.value = "Fresh live map started. Use the cleared table; prior maps and ownership are retained."
    }
    fun adjustQuantity(card: CatalogCard, delta: Int, event: String = UUID.randomUUID().toString()) = work { adjustQuantityNow(card, delta, event) }
    internal suspend fun adjustQuantityNow(card: CatalogCard, delta: Int, event: String = UUID.randomUUID().toString()) {
        if (store.adjustQuantity(card, delta, event, sessionId)) {
            val count = store.quantity(card.id)
            message.value = "${card.title}: $count owned. Quantity edits do not place a physical card in a pile."
        }
    }

    fun queuePresentation() = work {
        val id = UUID.randomUUID().toString()
        val candidates = JSONArray(latestCandidates.map { it.id }).toString()
        val pass = store.passes().first().firstOrNull { it.status == "active" }?.id
        store.addException(ExceptionRecord(id, sessionId, System.currentTimeMillis(), "UNVALIDATED_OCR", candidates, passId = pass))
        message.value = "Re-sort · OCR is experimental; ownership unchanged"
        if (!silent.value) cues.tryEmit("Re-sort")
    }
    fun confirm(card: CatalogCard, event: String, exceptionId: String? = null, expectedPresentation: String? = null) = work {
        require(expectedPresentation == null || expectedPresentation == remote.presentation) { "This selection is stale. Review the current card before confirming." }
        confirmNow(card, event, exceptionId)
    }
    internal suspend fun confirmNow(card: CatalogCard, event: String, exceptionId: String? = null) {
        require(mode.value != Mode.RESORT_EXISTING) { "Re-sort mode cannot create owned copies" }
        val placement = store.confirmAndPlace(card, event, sessionId, capacity.value, liveGrouping.value, exceptionId)
        require(placement != null) { "Attempt already resolved; review the current physical card." }
        if (placement.added) {
            val mixed = placement.group.pile == placement.run.capacity - 1
            val destination = if (mixed) "mixed groups · split this pile later" else placement.group.label
            lastPile.value = "Pile ${placement.group.pile} · $destination"
            message.value = "${card.title} added. Place it in Pile ${placement.group.pile} — $destination."
            remote.newPresentation()
            candidateLatch.consume(setOf(card.id)); suggestions.value = emptyList(); waitingForRemoval.value = true
            if (!silent.value) cues.tryEmit("Pile ${placement.group.pile}")
        }
    }

    fun dismiss(id: String, decision: String) = work { store.dismiss(id, decision); message.value = "Decision saved · ownership unchanged" }
    fun undo(id: String) = work { require(store.activePasses() == 0) { "Finish or abandon the active re-sort pass before undoing inventory" }; store.undo(id); message.value = "Copy removed · audit record retained" }

    fun makePlan(source: String = "inventoried collection", parent: SavedPass? = null, pile: Int? = null, liveSource: Pair<String, Int>? = null) = work { makePlanNow(source, parent, pile, liveSource) }
    internal suspend fun makePlanNow(source: String = "inventoried collection", parent: SavedPass? = null, pile: Int? = null, liveSource: Pair<String, Int>? = null) {
        val currentCopies = store.copies().first()
        val currentPlacements = store.livePlacements().first()
        val currentPasses = store.passes().first()
        val currentRoutes = store.routes().first()
        val sourceIds = liveSource?.let { (runId, number) -> currentPlacements.filter { it.runId == runId && it.pile == number }.map { it.copyId }.toSet() }
        val active = currentCopies.filter { it.status == "active" && (sourceIds == null || it.id in sourceIds) }
        require(active.isNotEmpty()) { "Confirm physical copies before planning a sorting pass" }
        val sourceCounts: Map<String, Int>
        val cardPacks: Map<String, String>
        if (parent != null && pile != null) {
            require(parent.status != "abandoned") { "This source pass was abandoned; plan from reconciled inventory instead" }
            val old = JSONObject(parent.json)
            val oldCounts = old.getJSONObject("sourceCounts")
            val oldPacks = old.getJSONObject("cardPacks")
            val allRouted = currentRoutes.filter { it.passId == parent.id }.groupingBy { it.cardId }.eachCount()
            require(oldCounts.keys().asSequence().all { allRouted[it] == oldCounts.getInt(it) }) { "Finish or reconcile the entire source pass before subdividing" }
            val oldPile = old.getJSONArray("piles").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.single { it.getInt("number") == pile } }
            val destinations = oldPile.getJSONArray("destinations").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.toSet() }
            sourceCounts = oldCounts.keys().asSequence().filter { oldPacks.getString(it) in destinations }.associateWith { oldCounts.getInt(it) }
            cardPacks = sourceCounts.keys.associateWith { oldPacks.getString(it) }
        } else {
            sourceCounts = active.groupingBy { it.cardId }.eachCount()
            cardPacks = active.associate { it.cardId to it.pack }
        }
        require(currentPasses.none { it.status == "active" && it.id != parent?.id }) { "Finish the active pass first; its map is immutable" }
        val ds = sourceCounts.entries.groupBy { cardPacks.getValue(it.key) }.map { (pack, counts) ->
            Destination(pack, active.firstOrNull { it.pack == pack }?.packName ?: pack, counts.sumOf { it.value })
        }
        if (parent != null && pile != null) require(currentPasses.none {
            val previous = JSONObject(it.json)
            previous.optString("parentId") == parent.id && previous.optInt("sourcePile", -1) == pile
        }) { "This source pile already has a saved child pass. Resume it instead." }
        val plan = PilePlanner.plan(ds, capacity.value, source, parent?.id ?: "root")
        if (liveSource != null) require(currentPasses.none {
            val previous = JSONObject(it.json)
            it.status != "abandoned" && previous.optString("liveRunId") == liveSource.first && previous.optInt("livePile", -1) == liveSource.second
        }) { "This source pile already has a saved re-sort pass" }
        val json = JSONObject().put("liveRunId", liveSource?.first ?: JSONObject.NULL).put("livePile", liveSource?.second ?: JSONObject.NULL).put("version", plan.version).put("source", source).put("capacity", plan.capacity)
            .put("parentId", parent?.id ?: JSONObject.NULL).put("sourcePile", pile ?: JSONObject.NULL)
            .put("instructions", plan.instructions).put("sourceCounts", JSONObject(sourceCounts)).put("cardPacks", JSONObject(cardPacks))
            .put("piles", jsonArray(plan.piles.map { p -> JSONObject().put("number", p.number).put("destinations", jsonArray(p.destinations.map {
                JSONObject().put("id", it.id).put("label", it.label).put("quantity", it.quantity)
            })) }))
        store.beginPass(SavedPass(UUID.randomUUID().toString(), json.toString(), System.currentTimeMillis()), parent?.id)
        mode.value = Mode.RESORT_EXISTING
        remote.newPresentation()
        lastPile.value = null
        message.value = "Sorting pass frozen. Catalog packs are provisional destinations, not verified retail products."
    }
    fun abandonPass(pass: SavedPass) = work { abandonPassNow(pass) }
    internal suspend fun abandonPassNow(pass: SavedPass) {
        store.abandonPass(pass.id)
        message.value = "Pass abandoned; records retained. Reconcile physical source piles before planning again. Ownership unchanged."
    }
    fun finishPass(pass: SavedPass) = work { finishPassNow(pass) }
    internal suspend fun finishPassNow(pass: SavedPass) {
        val counts = JSONObject(pass.json).getJSONObject("sourceCounts")
        val actual = store.routes().first().filter { it.passId == pass.id }.groupingBy { it.cardId }.eachCount()
        require(counts.keys().asSequence().all { (actual[it] ?: 0) == counts.getInt(it) }) { "Source count mismatch. Reconcile physical cards before completion." }
        store.completePass(pass.id); message.value = "Pass complete · ownership unchanged"
    }
    fun route(cardId: String, event: String, expectedPresentation: String? = null) = work {
        require(expectedPresentation == null || expectedPresentation == remote.presentation) { "This selection is stale. Review the current card." }
        routeNow(cardId, event)
    }
    internal suspend fun routeNow(cardId: String, event: String) {
        val pass = store.passes().first().firstOrNull { it.status == "active" } ?: error("Create a sorting pass first")
        val json = JSONObject(pass.json)
        val pack = json.getJSONObject("cardPacks").optString(cardId)
        val piles = json.getJSONArray("piles")
        val pile = (0 until piles.length()).map { piles.getJSONObject(it) }.singleOrNull { p ->
            val ds = p.getJSONArray("destinations")
            (0 until ds.length()).any { ds.getJSONObject(it).getString("id") == pack }
        }?.getInt("number") ?: error("Card is outside this source pile")
        if (store.route(RouteRecord(event, pass.id, cardId, pile, System.currentTimeMillis()))) {
            remote.newPresentation()
            candidateLatch.consume(setOf(cardId)); suggestions.value = emptyList(); waitingForRemoval.value = true
            lastPile.value = "Pile $pile · existing copy routed"
            message.value = "Pile $pile · existing copy routed; ownership unchanged"
            if (!silent.value) cues.tryEmit("Pile $pile")
        }
    }
    suspend fun exportJson(): String {
        return JSONObject().put("schemaVersion", 1).put("exportedAt", System.currentTimeMillis())
            .put("catalogSource", cards.value.firstOrNull()?.source ?: JSONObject.NULL)
            .put("remoteCommands", jsonArray(store.allRemoteCommands().map { JSONObject().put("id",it.id).put("requestHash",it.requestHash).put("result",JSONObject(it.result)).put("at",it.at) }))
            .put("copies", jsonArray(copies.value.map { it.toJson() }))
            .put("bulkInventory", JSONObject().put("cutoff", bulkInventory.value.cutoff).put("revision", bulkInventory.value.revision).put("finishedAt", bulkInventory.value.finishedAt ?: JSONObject.NULL).put("finishedRevision", bulkInventory.value.finishedRevision))
            .put("referencePacks", jsonArray(referencePacks.value.map { p -> JSONObject().put("id", p.id).put("title", p.title).put("familyId", p.familyId).put("familyName", p.familyName).put("released", p.released).put("members", JSONArray(p.members)).put("reprintType", p.reprintType).put("source", p.source).put("version", p.version) }))
            .put("referenceCards", jsonArray(cards.value.map { c -> JSONObject().put("id", c.id).put("title", c.title).put("pack", c.pack).put("encounter", c.encounter).put("encounterName", c.encounterName).put("canonicalId", c.canonicalId).put("expectedQuantity", c.expectedQuantity).put("version", c.version) }))
            .put("liveRuns", jsonArray(liveRuns.value.map { JSONObject().put("id", it.id).put("capacity", it.capacity).put("grouping", it.grouping).put("catalogVersion", it.catalogVersion).put("createdAt", it.createdAt).put("state", it.state) }))
            .put("liveGroups", jsonArray(liveGroups.value.map { JSONObject().put("runId", it.runId).put("groupId", it.groupId).put("label", it.label).put("pile", it.pile) }))
            .put("livePlacements", jsonArray(livePlacements.value.map { JSONObject().put("copyId", it.copyId).put("runId", it.runId).put("groupId", it.groupId).put("pile", it.pile).put("at", it.at) }))
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
    override fun onCleared() { remote.stopServer(); super.onCleared() }
    // The Application owns Room; rotation and ViewModel teardown must not close it.
}
