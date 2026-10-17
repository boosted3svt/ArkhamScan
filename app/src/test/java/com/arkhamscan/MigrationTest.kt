package com.arkhamscan

import android.content.Context
import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MigrationTest {
    @Test fun upgradingRealVersionOneDatabasePreservesCardsCopiesAndPendingAttempts(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-preserve.db"; context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("com.arkhamscan.ScanDatabase/1.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
        }
        val setup = schema.getJSONArray("setupQueries")
        for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
        fun insert(table: String, values: Map<String, String>) { old.insertOrThrow(table, null, ContentValues().apply { values.forEach { (k,v) -> put(k,v) } }) }
        insert("cards", mapOf("id" to "test:a", "title" to "Existing card", "number" to "1", "pack" to "p", "packName" to "Existing pack", "type" to "asset", "encounter" to "", "source" to "fixture", "version" to "v1"))
        insert("copies", mapOf("id" to "copy", "cardId" to "test:a", "title" to "Existing card", "pack" to "p", "packName" to "Existing pack", "catalogVersion" to "v1", "source" to "fixture", "createdAt" to "1", "sessionId" to "s", "method" to "MANUAL_VERIFIED", "status" to "active"))
        insert("exceptions", mapOf("id" to "pending", "sessionId" to "s", "createdAt" to "1", "reason" to "LOW_CONFIDENCE", "candidates" to "[]", "status" to "pending"))
        old.version=1; old.close()
        val upgraded = Room.databaseBuilder(context, ScanDatabase::class.java, name).addMigrations(ScanDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            assertEquals(1, upgraded.store().quantity("test:a"))
            assertEquals("", upgraded.store().cards().first().single().familyId)
            assertEquals("pending", upgraded.store().exception("pending")!!.status)
            assertTrue(upgraded.store().liveRuns().first().isEmpty())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
