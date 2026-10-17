package com.arkhamscan

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

@Entity(tableName = "cards")
data class CatalogCard(@PrimaryKey val id: String, val title: String, val number: String, val pack: String,
    val packName: String, val type: String, val encounter: String, val source: String, val version: String)
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

@Dao
abstract class Store {
    @Query("SELECT * FROM cards ORDER BY title, id") abstract fun cards(): Flow<List<CatalogCard>>
    @Query("SELECT * FROM copies ORDER BY createdAt DESC") abstract fun copies(): Flow<List<CopyRecord>>
    @Query("SELECT * FROM exceptions ORDER BY createdAt DESC") abstract fun exceptions(): Flow<List<ExceptionRecord>>
    @Query("SELECT * FROM passes ORDER BY createdAt DESC") abstract fun passes(): Flow<List<SavedPass>>
    @Query("SELECT * FROM routes") abstract fun routes(): Flow<List<RouteRecord>>
    @Query("SELECT * FROM audit ORDER BY at") abstract suspend fun allAudit(): List<AuditRecord>
    @Query("SELECT * FROM sessions") abstract suspend fun allSessions(): List<SessionRecord>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun session(s: SessionRecord)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addCopy(c: CopyRecord): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addException(e: ExceptionRecord): Long
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

    @Transaction open suspend fun importCards(cards: List<CatalogCard>) {
        require(cards.isNotEmpty() && cards.map { it.id }.toSet().size == cards.size)
        clearCatalog(); insertCards(cards)
        audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "IMPORT_CATALOG", cards.first().version, "${cards.size} cards; no verified retail manifests"))
    }
    @Transaction open suspend fun confirm(card: CatalogCard, event: String, sessionId: String, exceptionId: String? = null): Boolean {
        // Optional exception linkage is an explicit user decision, never inferred from candidate identity.
        if (exceptionId != null && exception(exceptionId)?.status !in setOf("pending", "deferred")) return false
        val added = addCopy(CopyRecord(event, card.id, card.title, card.pack, card.packName, card.version,
            card.source, System.currentTimeMillis(), sessionId, "MANUAL_VERIFIED")) != -1L
        if (added) {
            audit(AuditRecord("confirm-$event", System.currentTimeMillis(), "NEW_COPY", event, "Explicit physical-copy confirmation; retail provenance unverified"))
            if (exceptionId != null) markException(exceptionId, "new_copy:$event")
        }
        return added
    }
    @Transaction open suspend fun dismiss(id: String, decision: String) {
        require(decision in setOf("already_counted", "deferred"))
        if (markException(id, decision) > 0) audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), decision.uppercase(), id, "User reconciled physical Re-sort card"))
    }
    @Transaction open suspend fun undo(id: String) {
        if (voidCopy(id) > 0) audit(AuditRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), "VOID_COPY", id, "User undo; historical record retained"))
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
    SessionRecord::class, SavedPass::class, RouteRecord::class], version = 1, exportSchema = true)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun store(): Store
    companion object { fun open(context: Context) = Room.databaseBuilder(context, ScanDatabase::class.java, "arkhamscan.db").build() }
}

object CatalogImporter {
    const val MAX_BYTES = 16 * 1024 * 1024
    fun parse(bytes: ByteArray): List<CatalogCard> {
        require(bytes.size <= MAX_BYTES) { "Catalog exceeds 16 MiB" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == 1) { "Unsupported catalog schema" }
        val source = root.getString("source").trim()
        require(source.isNotBlank() && source.length < 2048)
        val version = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val cards = root.getJSONArray("cards")
        require(cards.length() in 1..50000) { "Expected 1–50,000 reference cards" }
        return (0 until cards.length()).map { i ->
            val c = cards.getJSONObject(i)
            fun field(k: String): String = c.getString(k).trim().also { require(it.isNotEmpty() && it.length <= 500) { "Invalid $k at card $i" } }
            CatalogCard(field("id"), field("title"), c.optString("number"), field("pack"), field("packName"),
                c.optString("type"), c.optString("encounter"), source, version)
        }.also { require(it.map { c -> c.id }.toSet().size == it.size) { "Duplicate printing IDs" } }
    }
}

fun jsonArray(items: Iterable<JSONObject>) = JSONArray().apply { items.forEach { put(it) } }
fun CopyRecord.toJson() = JSONObject().put("id", id).put("cardId", cardId).put("title", title).put("pack", pack)
    .put("packName", packName).put("catalogVersion", catalogVersion).put("source", source).put("createdAt", createdAt)
    .put("sessionId", sessionId).put("method", method).put("status", status).put("referenceImageId", referenceImageId ?: JSONObject.NULL)
