package com.arkhamscan

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.arkhamscan.core.GrowingPilePlanner
import com.arkhamscan.core.LiveGroup
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

@Entity(tableName = "cards")
data class CatalogCard(@PrimaryKey val id: String, val title: String, val number: String, val pack: String,
    val packName: String, val type: String, val encounter: String, val source: String, val version: String,
    @ColumnInfo(defaultValue = "''") val familyId: String = "", @ColumnInfo(defaultValue = "''") val familyName: String = "",
    @ColumnInfo(defaultValue = "-1") val expectedQuantity: Int = -1,
    @ColumnInfo(defaultValue = "''") val canonicalId: String = "",
    @ColumnInfo(defaultValue = "''") val encounterName: String = "")
@Entity(tableName = "copies")
data class CopyRecord(@PrimaryKey val id: String, val cardId: String, val title: String, val pack: String,
    val packName: String, val catalogVersion: String, val source: String, val createdAt: Long,
    val sessionId: String, val method: String, val status: String = "active", val referenceImageId: String? = null)
@Entity(tableName = "exceptions")
data class ExceptionRecord(@PrimaryKey val id: String, val sessionId: String, val createdAt: Long,
    val reason: String, val candidates: String, val status: String = "pending", val passId: String? = null)
@Entity(tableName = "audit")
data class AuditRecord(@PrimaryKey val id: String, val at: Long, val action: String, val subject: String, val details: String)
@Entity(tableName = "sessions")
data class SessionRecord(@PrimaryKey val id: String, val startedAt: Long, val name: String)
@Entity(tableName = "passes")
data class SavedPass(@PrimaryKey val id: String, val json: String, val createdAt: Long, val status: String = "active")
@Entity(tableName = "routes", indices = [Index("passId")])
data class RouteRecord(@PrimaryKey val id: String, val passId: String, val cardId: String, val pile: Int, val at: Long)

@Entity(tableName = "live_runs")
data class LiveRun(@PrimaryKey val id: String, val capacity: Int, val grouping: String,
    val catalogVersion: String, val createdAt: Long, val state: String = "active")
@Entity(tableName = "live_groups", primaryKeys = ["runId", "groupId"])
data class LivePileGroup(val runId: String, val groupId: String, val label: String, val pile: Int)
@Entity(tableName = "live_placements")
data class LivePlacement(@PrimaryKey val copyId: String, val runId: String, val groupId: String, val pile: Int, val at: Long)
data class ConfirmedPlacement(val added: Boolean, val run: LiveRun, val group: LivePileGroup)

@Entity(tableName = "reference_packs")
data class ReferencePack(@PrimaryKey val id: String, val title: String, val familyId: String, val familyName: String,
    val released: String, val members: String, val reprintType: String, val source: String, val version: String,
    val sourceKnown: Int = -1, val sourceTotal: Int = -1)
@Entity(tableName = "bulk_inventory")
data class BulkInventory(@PrimaryKey val id: Int = 1, val cutoff: String = "", val revision: Long = 0,
    val finishedAt: Long? = null, val finishedRevision: Long = -1)
data class CatalogSnapshot(val cards: List<CatalogCard>, val packs: List<ReferencePack>)

@Dao
abstract class Store {
    @Query("SELECT * FROM reference_packs ORDER BY title") abstract fun referencePacks(): Flow<List<ReferencePack>>
    @Query("SELECT * FROM bulk_inventory WHERE id=1") abstract fun bulkInventory(): Flow<BulkInventory?>
    @Query("SELECT * FROM bulk_inventory WHERE id=1") abstract suspend fun currentBulkInventory(): BulkInventory?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertBulkState(state: BulkInventory): Long
    @Query("UPDATE bulk_inventory SET revision=revision+1, finishedAt=NULL, finishedRevision=-1 WHERE id=1") abstract suspend fun reopenBulkState()
    @Query("UPDATE bulk_inventory SET cutoff=:cutoff WHERE id=1") abstract suspend fun writeCutoff(cutoff: String)
    @Query("UPDATE bulk_inventory SET finishedAt=:at, finishedRevision=revision WHERE id=1") abstract suspend fun finishBulkState(at: Long)
    @Query("DELETE FROM reference_packs") abstract suspend fun clearReferencePacks()
    @Insert abstract suspend fun insertReferencePacks(packs: List<ReferencePack>)
    @Transaction open suspend fun touchInventory() { insertBulkState(BulkInventory()); reopenBulkState() }
    @Transaction open suspend fun setCutoff(cutoff: String) {
        if (cutoff.isNotBlank()) java.time.LocalDate.parse(cutoff)
        insertBulkState(BulkInventory())
        if (currentBulkInventory()!!.cutoff != cutoff) {
            writeCutoff(cutoff); touchInventory()
            audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "REFERENCE_CUTOFF", "bulk", cutoff.ifBlank { "All known references" }))
        }
    }
    @Transaction open suspend fun finishBulkInventory(expectedRevision: Long) {
        insertBulkState(BulkInventory())
        require(currentBulkInventory()!!.revision == expectedRevision) { "Inventory changed during review. Review the latest counts and finish again." }
        finishBulkState(System.currentTimeMillis())
        audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "FINISH_BULK_INVENTORY", "bulk", "revision=$expectedRevision; outstanding entries are potentially missing, not proven absent"))
    }
    @Query("SELECT * FROM cards ORDER BY title, id") abstract fun cards(): Flow<List<CatalogCard>>
    @Query("SELECT * FROM copies ORDER BY createdAt DESC") abstract fun copies(): Flow<List<CopyRecord>>
    @Query("SELECT * FROM exceptions ORDER BY createdAt DESC") abstract fun exceptions(): Flow<List<ExceptionRecord>>
    @Query("SELECT * FROM passes ORDER BY createdAt DESC") abstract fun passes(): Flow<List<SavedPass>>
    @Query("SELECT * FROM routes") abstract fun routes(): Flow<List<RouteRecord>>
    @Query("SELECT * FROM audit ORDER BY at") abstract suspend fun allAudit(): List<AuditRecord>
    @Query("SELECT * FROM sessions") abstract suspend fun allSessions(): List<SessionRecord>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun session(s: SessionRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addCopy(c: CopyRecord): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertException(e: ExceptionRecord): Long
    @Transaction open suspend fun addException(e: ExceptionRecord): Long {
        val result = insertException(e)
        if (result != -1L) touchInventory()
        return result
    }
    @Insert abstract suspend fun audit(a: AuditRecord)
    @Insert abstract suspend fun insertCards(cards: List<CatalogCard>)
    @Query("DELETE FROM cards") abstract suspend fun clearCatalog()
    @Query("SELECT COUNT(*) FROM copies WHERE cardId=:card AND status='active'") abstract suspend fun quantity(card: String): Int
    @Query("SELECT * FROM exceptions WHERE id=:id") abstract suspend fun exception(id: String): ExceptionRecord?
    @Query("UPDATE exceptions SET status=:status WHERE id=:id AND status IN ('pending', 'deferred')") abstract suspend fun markException(id: String, status: String): Int
    @Query("UPDATE copies SET status='void' WHERE id=:id AND status='active'") abstract suspend fun voidCopy(id: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun savePass(p: SavedPass): Long
    @Query("UPDATE passes SET status='completed' WHERE id=:id") abstract suspend fun completePass(id: String)
    @Query("UPDATE passes SET status='abandoned' WHERE id=:id AND status='active'") abstract suspend fun abandon(id: String): Int
    @Transaction open suspend fun abandonPass(id: String) {
        if (abandon(id) > 0) audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "ABANDON_PASS", id, "Source requires physical reconciliation; ownership unchanged"))
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addRoute(r: RouteRecord): Long
    @Query("SELECT COUNT(*) FROM routes WHERE passId=:pass AND cardId=:card") abstract suspend fun routed(pass: String, card: String): Int
    @Query("SELECT COUNT(*) FROM routes WHERE id=:id") abstract suspend fun hasRoute(id: String): Int
    @Query("SELECT * FROM passes WHERE id=:id") abstract suspend fun pass(id: String): SavedPass?

    @Query("SELECT * FROM live_runs ORDER BY createdAt DESC") abstract fun liveRuns(): Flow<List<LiveRun>>
    @Query("SELECT * FROM live_groups ORDER BY pile, label") abstract fun liveGroups(): Flow<List<LivePileGroup>>
    @Query("SELECT * FROM live_placements") abstract fun livePlacements(): Flow<List<LivePlacement>>
    @Query("SELECT COUNT(*) FROM passes") abstract suspend fun totalPasses(): Int
    @Query("SELECT COUNT(*) FROM passes WHERE status='active' AND id != :parent") abstract suspend fun activePassesExcept(parent: String): Int
    @Query("SELECT * FROM live_runs WHERE state='active' LIMIT 1") abstract suspend fun activeLiveRun(): LiveRun?
    @Query("SELECT * FROM live_groups WHERE runId=:run") abstract suspend fun groupsForRun(run: String): List<LivePileGroup>
    @Query("SELECT * FROM live_placements WHERE copyId=:id") abstract suspend fun placement(id: String): LivePlacement?
    @Query("SELECT COUNT(*) FROM passes WHERE status='active'") abstract suspend fun activePasses(): Int
    @Query("UPDATE live_runs SET state='closed' WHERE state='active'") abstract suspend fun closeLiveRuns()
    @Insert abstract suspend fun insertLiveRun(run: LiveRun)
    @Insert abstract suspend fun insertLiveGroup(group: LivePileGroup)
    @Insert abstract suspend fun insertPlacement(placement: LivePlacement)
    @Query("SELECT * FROM copies WHERE cardId=:card AND status='active' ORDER BY createdAt DESC, id DESC LIMIT 1") abstract suspend fun latestCopy(card: String): CopyRecord?
    @Query("SELECT COUNT(*) FROM audit WHERE id=:id") abstract suspend fun hasAudit(id: String): Int
    @Transaction open suspend fun newLiveRun(capacity: Int, grouping: String, catalogVersion: String): LiveRun {
        require(activePasses() == 0) { "Finish or abandon the active re-sort pass first" }
        require(capacity in 3..32 && grouping in setOf("PACK", "CYCLE"))
        closeLiveRuns()
        val run = LiveRun(UUID.randomUUID().toString(), capacity, grouping, catalogVersion, System.currentTimeMillis())
        insertLiveRun(run)
        audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "START_LIVE_PILES", run.id, "$capacity positions; $grouping; earlier maps retained"))
        return run
    }
    @Transaction open suspend fun confirmAndPlace(card: CatalogCard, event: String, sessionId: String,
        capacity: Int, grouping: String, exceptionId: String? = null): ConfirmedPlacement? {
        require(activePasses() == 0) { "Finish or abandon the active re-sort pass before inventorying new cards" }
        val run = activeLiveRun() ?: run {
            require(totalPasses() == 0) { "After re-sorting, clear the table and start a fresh live pile map in Piles before adding cards." }
            newLiveRun(capacity, grouping, card.version)
        }
        require(run.catalogVersion == card.version) { "Catalog changed. Clear the table and start a fresh live pile map in Piles." }
        val groups = groupsForRun(run.id)
        val id = if (run.grouping == "CYCLE" && card.familyId.isNotBlank()) card.familyId else "pack:${card.pack}"
        val label = if (run.grouping == "CYCLE" && card.familyName.isNotBlank()) card.familyName else card.packName
        val route = GrowingPilePlanner.assign(groups.map { LiveGroup(it.groupId, it.label, it.pile) }, id, label, run.capacity)
        val group = LivePileGroup(run.id, route.id, route.label, route.pile)
        val added = confirm(card, event, sessionId, exceptionId)
        if (added) {
            if (groups.none { it.groupId == id }) insertLiveGroup(group)
            insertPlacement(LivePlacement(event, run.id, id, group.pile, System.currentTimeMillis()))
            audit(AuditRecord("pile-$event", System.currentTimeMillis(), "PLACE_NEW_COPY", event, "${run.id}; pile ${group.pile}; ${group.groupId}"))
        }
        return if (added || placement(event) != null) ConfirmedPlacement(added, run, group) else null
    }
    @Transaction open suspend fun beginPass(pass: SavedPass, parentId: String? = null) {
        require(activePassesExcept(parentId ?: "") == 0) { "Finish the active re-sort pass first" }
        closeLiveRuns() // Reusing table positions invalidates the original live table layout.
        if (parentId != null) completePass(parentId)
        savePass(pass)
        audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "START_RESORT_PASS", pass.id, "Source map frozen; prior live map closed. Clear table before future live inventory."))
    }
    @Transaction open suspend fun adjustQuantity(card: CatalogCard, delta: Int, event: String, sessionId: String): Boolean {
        require(delta in setOf(-1, 1))
        require(activePasses() == 0) { "Finish or abandon the active re-sort pass before editing quantities" }
        if (hasAudit("adjust-$event") > 0) return false
        var copyId = event
        if (delta > 0) {
            if (addCopy(CopyRecord(event, card.id, card.title, card.pack, card.packName, card.version, card.source,
                System.currentTimeMillis(), sessionId, "QUANTITY_ADJUSTMENT")) == -1L) return false
        } else {
            val copy = latestCopy(card.id) ?: return false
            copyId = copy.id
            if (voidCopy(copy.id) == 0) return false
        }
        audit(AuditRecord("adjust-$event", System.currentTimeMillis(), "ADJUST_QUANTITY", card.id, "delta=$delta; copy=$copyId; collection edit does not route a physical card"))
        touchInventory()
        return true
    }

    @Transaction open suspend fun importCards(cards: List<CatalogCard>, packs: List<ReferencePack> = emptyList()) {
        require(cards.isNotEmpty() && cards.map { it.id }.toSet().size == cards.size)
        clearCatalog(); insertCards(cards)
        clearReferencePacks()
        val references = packs.ifEmpty { cards.distinctBy { it.pack }.map { ReferencePack(it.pack, it.packName, it.familyId, it.familyName, "", "[]", "", it.source, it.version) } }
        require(references.map { it.id }.toSet().size == references.size)
        insertReferencePacks(references)
        touchInventory()
        audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "IMPORT_CATALOG", cards.first().version, "${cards.size} cards; no verified retail manifests"))
    }
    @Transaction open suspend fun confirm(card: CatalogCard, event: String, sessionId: String, exceptionId: String? = null): Boolean {
        // Optional exception linkage is an explicit user decision, never inferred from candidate identity.
        if (exceptionId != null && exception(exceptionId)?.status !in setOf("pending", "deferred")) return false
        val added = addCopy(CopyRecord(event, card.id, card.title, card.pack, card.packName, card.version,
            card.source, System.currentTimeMillis(), sessionId, "MANUAL_VERIFIED")) != -1L
        if (added) {
            touchInventory()
            audit(AuditRecord("confirm-$event", System.currentTimeMillis(), "NEW_COPY", event, "Explicit physical-copy confirmation; retail provenance unverified"))
            if (exceptionId != null) markException(exceptionId, "new_copy:$event")
        }
        return added
    }
    @Transaction open suspend fun dismiss(id: String, decision: String) {
        require(decision in setOf("already_counted", "deferred"))
        if (markException(id, decision) > 0) { touchInventory(); audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), decision.uppercase(), id, "User reconciled physical Re-sort card")) }
    }
    @Transaction open suspend fun undo(id: String) {
        if (voidCopy(id) > 0) { touchInventory(); audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "VOID_COPY", id, "User undo; historical record retained")) }
    }
    @Transaction open suspend fun route(record: RouteRecord): Boolean {
        if (hasRoute(record.id) > 0) return false
        val saved = pass(record.passId) ?: error("No active sorting pass")
        require(saved.status == "active") { "This pass is complete" }
        val plan = JSONObject(saved.json)
        val expected = plan.getJSONObject("sourceCounts").optInt(record.cardId, 0)
        require(routed(record.passId, record.cardId) < expected) { "Source quantity exhausted. Reconcile the source pile." }
        val piles = plan.getJSONArray("piles")
        val pack = plan.getJSONObject("cardPacks").getString(record.cardId)
        val valid = (0 until piles.length()).any { i ->
            val p = piles.getJSONObject(i)
            p.getInt("number") == record.pile && (0 until p.getJSONArray("destinations").length()).any { j ->
                p.getJSONArray("destinations").getJSONObject(j).getString("id") == pack
            }
        }
        require(valid) { "Card does not belong to this source destination" }
        return addRoute(record) != -1L
    }
}

@Database(entities = [CatalogCard::class, CopyRecord::class, ExceptionRecord::class, AuditRecord::class,
    SessionRecord::class, SavedPass::class, RouteRecord::class, LiveRun::class, LivePileGroup::class, LivePlacement::class, ReferencePack::class, BulkInventory::class], version = 3, exportSchema = true)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun store(): Store
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN familyId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cards ADD COLUMN familyName TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS live_runs (id TEXT NOT NULL, capacity INTEGER NOT NULL, grouping TEXT NOT NULL, catalogVersion TEXT NOT NULL, createdAt INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS live_groups (runId TEXT NOT NULL, groupId TEXT NOT NULL, label TEXT NOT NULL, pile INTEGER NOT NULL, PRIMARY KEY(runId, groupId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS live_placements (copyId TEXT NOT NULL, runId TEXT NOT NULL, groupId TEXT NOT NULL, pile INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(copyId))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN expectedQuantity INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE cards ADD COLUMN canonicalId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cards ADD COLUMN encounterName TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS reference_packs (id TEXT NOT NULL, title TEXT NOT NULL, familyId TEXT NOT NULL, familyName TEXT NOT NULL, released TEXT NOT NULL, members TEXT NOT NULL, reprintType TEXT NOT NULL, source TEXT NOT NULL, version TEXT NOT NULL, sourceKnown INTEGER NOT NULL, sourceTotal INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS bulk_inventory (id INTEGER NOT NULL, cutoff TEXT NOT NULL, revision INTEGER NOT NULL, finishedAt INTEGER, finishedRevision INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("INSERT OR IGNORE INTO bulk_inventory VALUES(1, '', 0, NULL, -1)")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, ScanDatabase::class.java, "arkhamscan.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}

object CatalogImporter {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun parse(bytes: ByteArray): List<CatalogCard> = snapshot(bytes).cards
    fun snapshot(bytes: ByteArray): CatalogSnapshot {
        require(bytes.size <= MAX_BYTES) { "Catalog exceeds 16 MiB" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == 1) { "Unsupported catalog schema" }
        val source = root.getString("source").trim()
        require(source.isNotBlank() && source.length < 2048)
        val version = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val cards = root.getJSONArray("cards")
        require(cards.length() in 1..50000) { "Expected 1–50,000 reference cards" }
        val parsedCards = (0 until cards.length()).map { i ->
            val c = cards.getJSONObject(i)
            fun field(k: String): String = c.getString(k).trim().also { require(it.isNotEmpty() && it.length <= 500) { "Invalid $k at card $i" } }
            CatalogCard(field("id"), field("title"), c.optString("number"), field("pack"), field("packName"),
                c.optString("type"), c.optString("encounter"), source, version, c.optString("familyId"), c.optString("familyName"), c.optInt("expectedQuantity", -1).also { require(it == -1 || it in 1..10000) { "Invalid expected copy quantity" } }, c.optString("canonicalId"), c.optString("encounterName"))
        }.also { require(it.map { c -> c.id }.toSet().size == it.size) { "Duplicate printing IDs" } }
        val ps = root.optJSONArray("packs")
        val parsedPacks = if (ps == null) emptyList() else (0 until ps.length()).map { i ->
            val p = ps.getJSONObject(i)
            val date = p.optString("released")
            if (date.isNotBlank()) java.time.LocalDate.parse(date)
            ReferencePack(p.getString("id"), p.getString("title"), p.optString("familyId"), p.optString("familyName"), date,
                (p.optJSONArray("members") ?: JSONArray()).toString(), p.optString("reprintType"), p.optString("source", source), version, p.optInt("sourceKnown", -1), p.optInt("sourceTotal", -1))
        }.also { require(it.map { p -> p.id }.toSet().size == it.size) { "Duplicate reference product IDs" } }
        if (parsedPacks.isNotEmpty()) require(parsedCards.all { c -> parsedPacks.any { it.id == c.pack } }) { "Card references unknown product" }
        return CatalogSnapshot(parsedCards, parsedPacks)
    }
}

fun jsonArray(items: Iterable<JSONObject>) = JSONArray().apply { items.forEach { put(it) } }
fun CopyRecord.toJson() = JSONObject().put("id", id).put("cardId", cardId).put("title", title).put("pack", pack)
    .put("packName", packName).put("catalogVersion", catalogVersion).put("source", source).put("createdAt", createdAt)
    .put("sessionId", sessionId).put("method", method).put("status", status).put("referenceImageId", referenceImageId ?: JSONObject.NULL)
