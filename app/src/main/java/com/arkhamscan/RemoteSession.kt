package com.arkhamscan

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.arkhamscan.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Phone-owned state and commands shared with a transient browser controller. */
class RemoteSession(private val vm: ScanViewModel, private val scope: CoroutineScope) {
    val enabled = MutableStateFlow(false)
    val address = MutableStateFlow("")
    val foreground = MutableStateFlow(false)
    val cameraRequest = MutableStateFlow("stopped")
    val cameraStatus = MutableStateFlow("stopped")
    val ocrSummary = MutableStateFlow("")
    val cameraError = MutableStateFlow("")
    val cameraGeneration = MutableStateFlow(0)
    val previewAttached = MutableStateFlow(false)
    val torch = MutableStateFlow(false)
    val version = MutableStateFlow(1L)
    var presentation = UUID.randomUUID().toString(); private set
    var selected: String? = null; private set
    var commandBusy = false; private set
    private var cachedRevision = -1L
    private var cachedOwned: List<CopyRecord> = emptyList()
    private var server: RemoteHttpServer? = null
    private var watch: Job? = null
    @Volatile private var lastCommand = "{}"
    private val recentErrors = ArrayDeque<JSONObject>()
    private val counter = AtomicLong(1)
    @Synchronized fun changed() { version.value = counter.incrementAndGet() }
    fun newPresentation() { presentation = UUID.randomUUID().toString(); selected = null; changed() }
    fun error(subsystem: String, detail: String, commandId: String? = null) {
        // No HTTP headers, credentials, frames or request bodies enter diagnostics.
        synchronized(recentErrors) {
            if (recentErrors.size >= 30) recentErrors.removeFirst()
            recentErrors.addLast(JSONObject().put("at", System.currentTimeMillis()).put("subsystem", subsystem).put("message", detail.take(500)).put("commandId", commandId ?: JSONObject.NULL))
        }
        changed()
    }
    fun permissionGranted() = ContextCompat.checkSelfPermission(vm.getApplication(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun setForeground(value: Boolean) {
        foreground.value = value
        if (!value && cameraRequest.value == "running") cameraStatus.value = "blocked: bring phone app to foreground and unlock it"
        changed()
    }
    fun camera(action: String) {
        when (action) {
            "start", "resume", "restart" -> {
                require(foreground.value) { "Open and unlock the phone app before starting the camera." }
                require(permissionGranted()) { "Grant camera permission on the phone's Scan screen first." }
                if (action == "restart") { vm.nextCard(); cameraGeneration.value++ }
                cameraError.value = ""
                cameraStatus.value = "starting"
                cameraRequest.value = "running"
            }
            "pause" -> { cameraRequest.value = "paused"; cameraStatus.value = "paused" }
            "stop" -> { cameraRequest.value = "stopped"; cameraStatus.value = "stopped" }
            else -> error("Unknown camera action")
        }
        changed()
    }
    fun cameraFailure(detail: String) {
        cameraError.value = detail; cameraStatus.value = "error"; cameraRequest.value = "stopped"
        error("camera/OCR", detail)
    }
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startServer(port: Int = 8080) {
        if (enabled.value) return
        try {
            val host = RemoteHttpServer(vm.getApplication<Application>().assets, this, port)
            host.startServer()
            server = host
            address.value = host.addresses.joinToString("\n")
            enabled.value = true
            watch = scope.launch {
                merge(vm.cards.map { Unit }, vm.copies.map { Unit }, vm.exceptions.map { Unit }, vm.passes.map { Unit },
                    vm.routes.map { Unit }, vm.liveRuns.map { Unit }, vm.liveGroups.map { Unit }, vm.livePlacements.map { Unit },
                    vm.bulkInventory.map { Unit }, vm.discovery.map { Unit }, vm.message.map { Unit }, vm.suggestions.map { Unit },
                    vm.mode.map { Unit }, vm.lastPile.map { Unit }, cameraStatus.map { Unit }, cameraError.map { Unit }, vm.downloadingCatalog.map { Unit }, ocrSummary.sample(1000).map { Unit }).collect { changed() }
            }
            changed()
        } catch (e: Exception) { error("web server", "Could not listen: ${e.message}"); vm.message.value = "Could not start web server: ${e.message}" }
    }
    fun stopServer() {
        server?.stop(); server = null; watch?.cancel(); watch = null
        enabled.value = false; address.value = ""
        cameraRequest.value = "stopped"; cameraStatus.value = "stopped"; newPresentation()
    }
    suspend fun snapshot(): JSONObject = withContext(Dispatchers.Main.immediate) {
        vm.operationMutex.withLock {
            val bulk = vm.store.currentBulkInventory() ?: BulkInventory()
            if (cachedRevision != bulk.revision) {
                cachedOwned = vm.store.copies().first().filter { it.status == "active" }
                cachedRevision = bulk.revision
            }
            val owned = cachedOwned
            JSONObject().put("version", version.value).put("revision", bulk.revision).put("presentation", presentation)
                .put("selected", selected ?: JSONObject.NULL).put("camera", cameraStatus.value).put("cameraError", cameraError.value)
                .put("ocr", ocrSummary.value).put("foreground", foreground.value).put("permission", permissionGranted()).put("mode", vm.mode.value.name)
                .put("message", vm.message.value).put("lastPile", vm.lastPile.value ?: "").put("waitingForRemoval", vm.waitingForRemoval.value)
                .put("downloading", vm.downloadingCatalog.value).put("catalogSize", vm.cards.value.size)
                .put("totalCopies", owned.size).put("distinctCards", owned.map { it.cardId }.toSet().size)
                .put("candidates", JSONArray(vm.suggestions.value.map { cardJson(it, owned.count { c -> c.cardId == it.id }) }))
                .put("selectedCard", selected?.let { id -> vm.cards.value.firstOrNull { it.id == id }?.let { cardJson(it, owned.count { c -> c.cardId == id }) } } ?: JSONObject.NULL)
                .put("recentCopies", JSONArray(owned.take(12).map { JSONObject().put("id", it.id).put("title", it.title).put("packName", it.packName) }))
                .put("attempts", JSONArray(vm.exceptions.value.filter { it.status in setOf("pending", "deferred") }.take(30).map {
                    JSONObject().put("id", it.id).put("reason", it.reason).put("status", it.status).put("candidates", JSONArray(it.candidates)) }))
                .put("pendingAttempts", vm.exceptions.value.count { it.status in setOf("pending", "deferred") })
                .put("unindexedCopies", vm.discovery.value.unindexedCopies).put("runCapacity", vm.liveRuns.value.firstOrNull()?.capacity ?: vm.capacity.value)
                .put("run", vm.liveRuns.value.firstOrNull()?.id ?: "").put("capacity", vm.capacity.value).put("grouping", vm.liveGrouping.value)
                .put("piles", JSONArray(vm.liveGroups.value.filter { it.runId == vm.liveRuns.value.firstOrNull()?.id }.map { g ->
                    val ids = vm.livePlacements.value.filter { it.runId == g.runId && it.groupId == g.groupId }.map { it.copyId }.toSet()
                    JSONObject().put("pile", g.pile).put("label", g.label).put("copies", owned.count { it.id in ids }) }))
                .put("passes", JSONArray(vm.passes.value.filter { it.status == "active" }.map { p -> JSONObject().put("id", p.id).put("plan", JSONObject(p.json)).put("routed", vm.routes.value.count { it.passId == p.id }) }))
                .put("finished", bulk.finishedAt != null).put("cutoff", bulk.cutoff)
                .put("errors", diagnostics().getJSONArray("errors"))
        }
    }
    suspend fun exportAudit() = vm.exportJson()
    fun exportCollection() = vm.exportCsv()
    fun exportProgress(group: String?, outstanding: Boolean) = vm.discoveryCsv(group, outstanding)
    fun diagnostics(): JSONObject = JSONObject().put("appVersion", "0.5.1-alpha").put("camera", cameraStatus.value)
        .put("foreground", foreground.value).put("presentation", presentation).put("lastCommand", JSONObject(lastCommand))
        .put("errors", synchronized(recentErrors) { JSONArray(recentErrors.toList()) })

    suspend fun page(kind: String, query: String, offset: Int, group: String = ""): JSONObject = withContext(Dispatchers.Main.immediate) {
        val start = offset.coerceIn(0, 50_000); val q = query.take(150)
        when (kind) {
            "catalog", "collection" -> {
                val history = vm.store.copies().first()
                val owned = history.filter { it.status == "active" }.groupingBy { it.cardId }.eachCount()
                val available = (vm.cards.value + if (kind == "collection") history.map { historicalCard(it) } else emptyList()).distinctBy { it.id }
                val cs = available.filter { (kind == "catalog" || (owned[it.id] ?: 0) > 0) &&
                    listOf(it.title, it.id, it.number, it.packName).any { s -> s.contains(q, true) } }
                JSONObject().put("total", cs.size).put("offset", start).put("items", JSONArray(cs.drop(start).take(40).map { cardJson(it, owned[it.id] ?: 0) }))
            }
            "pile" -> {
                val parts = group.split('|'); require(parts.size == 2)
                val ids = vm.livePlacements.value.filter { it.runId == parts[0] && it.pile == parts[1].toInt() }.map { it.copyId }.toSet()
                val copies = vm.store.copies().first().filter { it.status == "active" && it.id in ids }.groupBy { it.cardId }
                val entries = copies.values.filter { it.first().title.contains(q, true) }
                JSONObject().put("total", entries.size).put("items", JSONArray(entries.drop(start).take(40).map { cs ->
                    JSONObject().put("id", cs.first().cardId).put("title", cs.first().title).put("packName", cs.first().packName).put("quantity", cs.size) }))
            }
            "progress" -> {
                val groups = vm.discovery.value.scopes.filter { it.inTarget && it.title.contains(q, true) && (group.isBlank() || it.kind == group) }
                JSONObject().put("total", groups.size).put("items", JSONArray(groups.drop(start).take(40).map { s ->
                    JSONObject().put("id", s.id).put("title", s.title).put("kind", s.kind).put("accounted", s.accounted)
                        .put("expected", s.expectedCopies ?: JSONObject.NULL).put("found", s.designsFound).put("designs", s.designsExpected)
                        .put("extras", s.extras).put("note", s.sourceNote) }))
            }
            "group" -> {
                val s = vm.discovery.value.scopes.firstOrNull { it.id == group } ?: error("Reference group unavailable")
                JSONObject().put("total", s.rows.size).put("title", s.title).put("note", s.sourceNote).put("items", JSONArray(s.rows.drop(start).take(40).map { r ->
                    JSONObject().put("id", r.id).put("title", r.title).put("expected", r.expected ?: JSONObject.NULL).put("observed", r.observed)
                        .put("exact", r.exact).put("outstanding", r.outstanding ?: JSONObject.NULL).put("extras", r.extras ?: JSONObject.NULL) }))
            }
            else -> error("Unknown view")
        }
    }
    private fun cardJson(c: CatalogCard, count: Int) = JSONObject().put("id", c.id).put("title", c.title).put("packName", c.packName)
        .put("number", c.number).put("quantity", count).put("expected", c.expectedQuantity.takeIf { it > 0 } ?: JSONObject.NULL)
        .put("url", ArkhamDbCatalog.cardPage(c) ?: JSONObject.NULL)

    suspend fun commandResult(id: String): JSONObject = withContext(Dispatchers.Main.immediate) {
        vm.operationMutex.withLock {
            vm.store.remoteCommand(id)?.let { JSONObject(it.result).put("committed", true) }
                ?: JSONObject().put("committed", false)
        }
    }
    /** Serialized with native operations. Mutation and durable acknowledgment share one Room transaction. */
    suspend fun command(body: JSONObject): JSONObject = withContext(Dispatchers.Main.immediate) {
        vm.operationMutex.withLock {
            val id = body.getString("id").also { require(it.matches(Regex("[A-Za-z0-9-]{16,80}"))) { "Invalid command ID" } }
            val action = body.getString("action")
            lastCommand = JSONObject().put("id", id).put("action", action).put("outcome", "checking").toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            vm.store.remoteCommand(id)?.let {
                require(it.requestHash == hash) { "Command ID was already used for a different request" }
                lastCommand = JSONObject().put("id", id).put("action", action).put("outcome", "committed").toString()
                return@withLock JSONObject(it.result).put("replayed", true)
            }
            commandBusy = true
            try {
                if (action in setOf("camera", "next", "select", "mode", "refresh", "settings")) {
                    when (action) {
                        "camera" -> camera(body.getString("value"))
                        "next" -> { requirePresentation(body); vm.nextCard() }
                        "select" -> { requirePresentation(body); requireCard(body.getString("card")); selected = body.getString("card"); changed() }
                        "mode" -> { vm.mode.value = Mode.valueOf(body.getString("value")); vm.nextCard() }
                        "refresh" -> { vm.downloadArkhamDb() }
                        "settings" -> { vm.configure(body.getInt("capacity"), vm.silent.value, vm.speechRate.value); vm.setLiveGrouping(body.getString("grouping")); vm.nextCard() }
                    }
                    lastCommand = JSONObject().put("id", id).put("action", action).put("outcome", "accepted").toString()
                    return@withLock JSONObject().put("ok", true).put("id", id).put("message", "Request accepted; live status reports readiness.")
                }
                val result = vm.db.withTransaction {
                    require(vm.store.currentBulkInventory()?.revision == body.getLong("revision")) { "Collection changed. Resynchronize and review before trying again." }
                    val event = "remote-$id"
                    when (action) {
                        "confirm" -> {
                            requirePresentation(body)
                            require(selected == body.getString("card")) { "Select and review this match before confirming." }
                            vm.confirmNow(requireCard(body.getString("card")), event, body.optString("attempt").ifBlank { null })
                        }
                        "adjust" -> vm.adjustQuantityNow(editableCard(body.getString("card")), body.getInt("delta"), event)
                        "undo" -> {
                            require(vm.store.activePasses() == 0) { "Finish or abandon the active re-sort pass first" }
                            val copyId = body.getString("copy")
                            require(vm.store.copies().first().any { it.id == copyId && it.status == "active" }) { "Copy is no longer active" }
                            vm.store.undo(copyId); vm.message.value = "Copy removed. Reconcile its physical pile; history retained."
                        }
                        "dismiss" -> { vm.store.dismiss(body.getString("attempt"), body.getString("decision")); vm.message.value = "Attempt decision saved; ownership unchanged." }
                        "route" -> { requirePresentation(body); vm.routeNow(body.getString("card"), event) }
                        "freshPiles" -> { require(body.optBoolean("tableCleared")) { "Confirm that the physical table is cleared" }; vm.startFreshPilesNow() }
                        "cutoff" -> vm.store.setCutoff(body.getString("value").trim())
                        "finishInventory" -> vm.store.finishBulkInventory(body.getLong("revision"))
                        "resumeInventory" -> vm.store.touchInventory()
                        "plan" -> {
                            require(body.optBoolean("tableCleared")) { "Set aside source cards and clear the table first" }
                            vm.makePlanNow(liveSource = if (body.has("run")) body.getString("run") to body.getInt("pile") else null)
                        }
                        "finishPass", "abandonPass" -> {
                            val p = vm.store.pass(body.getString("pass")) ?: error("Sorting pass unavailable")
                            if (action == "finishPass") vm.finishPassNow(p) else vm.abandonPassNow(p)
                        }
                        else -> error("Unknown command")
                    }
                    JSONObject().put("ok", true).put("id", id).put("message", vm.message.value).put("lastPile", vm.lastPile.value ?: "")
                        .also { vm.store.saveRemoteCommand(RemoteCommand(id, hash, it.toString(), System.currentTimeMillis())) }
                }
                lastCommand = JSONObject().put("id", id).put("action", action).put("outcome", "committed").toString()
                changed(); result
            } catch (e: Exception) { error("command:$action", e.message ?: "Operation failed", id); throw e }
            finally { commandBusy = false; changed() }
        }
    }
    private fun requirePresentation(b: JSONObject) { require(b.getString("presentation") == presentation) { "This scan is stale. Review the current presentation." } }
    private fun historicalCard(c: CopyRecord) = CatalogCard(c.cardId,c.title,"",c.pack,c.packName,"","",c.source,c.catalogVersion)
    private suspend fun editableCard(id: String) = vm.cards.value.firstOrNull { it.id == id }
        ?: vm.store.copies().first().firstOrNull { it.cardId == id }?.let(::historicalCard)
        ?: error("Card is absent from current catalog and ownership history")
    private fun requireCard(id: String) = vm.cards.value.firstOrNull { it.id == id } ?: error("Card is absent from current catalog; refresh or search again")
}
