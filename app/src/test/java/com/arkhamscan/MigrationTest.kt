package com.arkhamscan

import android.content.Context
import android.content.ContentValues
import androidx.room.Room
import androidx.room.withTransaction
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
        val upgraded = Room.databaseBuilder(context, ScanDatabase::class.java, name).addMigrations(ScanDatabase.MIGRATION_1_2, ScanDatabase.MIGRATION_2_3, ScanDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(1, upgraded.store().quantity("test:a"))
            assertEquals("", upgraded.store().cards().first().single().familyId)
            assertEquals("pending", upgraded.store().exception("pending")!!.status)
            assertTrue(upgraded.store().liveRuns().first().isEmpty())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
    @Test fun upgradingSchemaTwoRetainsLivePileAssignmentsAndDoesNotInventReferenceQuantities(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-live-v2.db"; context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("com.arkhamscan.ScanDatabase/2.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val table=entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
            val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
            for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
        }
        val setup=schema.getJSONArray("setupQueries")
        for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
        old.execSQL("INSERT INTO live_runs VALUES('run',5,'PACK','v1',1,'active')")
        old.execSQL("INSERT INTO live_groups VALUES('run','pack:p','Existing group',1)")
        old.execSQL("INSERT INTO live_placements VALUES('existing-copy','run','pack:p',1,1)")
        old.execSQL("INSERT INTO cards(id,title,number,pack,packName,type,encounter,source,version,familyId,familyName) VALUES('test:a','Existing','1','p','Pack','asset','','fixture','v1','family','Family')")
        old.version=2;old.close()
        val upgraded=Room.databaseBuilder(context,ScanDatabase::class.java,name).addMigrations(ScanDatabase.MIGRATION_2_3, ScanDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals("run",upgraded.store().activeLiveRun()!!.id)
            assertEquals("existing-copy",upgraded.store().livePlacements().first().single().copyId)
            assertEquals(-1,upgraded.store().cards().first().single().expectedQuantity)
            assertEquals("family",upgraded.store().cards().first().single().familyId)
            assertEquals("",upgraded.store().currentBulkInventory()!!.cutoff)
            assertTrue(upgraded.store().referencePacks().first().isEmpty())
        } finally { upgraded.close();context.deleteDatabase(name) }
    }

    @Test fun upgradingSchemaThreePreservesCollectionAndAtomicCommandOutcomesSurviveReopen(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration-remote-v3.db";context.deleteDatabase(name)
        val schema=javaClass.classLoader!!.getResourceAsStream("com.arkhamscan.ScanDatabase/3.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val old=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
        val entities=schema.getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val e=entities.getJSONObject(i);val table=e.getString("tableName")
            old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
            val indices=e.optJSONArray("indices") ?: org.json.JSONArray()
            for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
        }
        val setup=schema.getJSONArray("setupQueries")
        for(i in 0 until setup.length())old.execSQL(setup.getString(i))
        old.execSQL("INSERT INTO cards(id,title,number,pack,packName,type,encounter,source,version,expectedQuantity) VALUES('test:a','Existing','1','p','Pack','asset','','fixture','v1',3)")
        old.execSQL("INSERT INTO copies(id,cardId,title,pack,packName,catalogVersion,source,createdAt,sessionId,method,status) VALUES('existing','test:a','Existing','p','Pack','v1','fixture',1,'s','MANUAL_VERIFIED','active')")
        old.execSQL("INSERT INTO bulk_inventory VALUES(1,'2020-01-01',99,5,99)")
        old.version=3;old.close()
        fun open()=Room.databaseBuilder(context,ScanDatabase::class.java,name).addMigrations(ScanDatabase.MIGRATION_3_4).build()
        var upgraded=open()
        try {
            assertEquals(1,upgraded.store().quantity("test:a"))
            assertEquals(99L,upgraded.store().currentBulkInventory()!!.revision)
            assertEquals(5L,upgraded.store().currentBulkInventory()!!.finishedAt)
            val card=upgraded.store().cards().first().single()
            try {
                upgraded.withTransaction {
                    upgraded.store().adjustQuantity(card,1,"rollback","s")
                    upgraded.store().saveRemoteCommand(RemoteCommand("rolled-back","hash","{}",1))
                    throw IllegalStateException("simulate failure before commit")
                }
            } catch (_:IllegalStateException) {}
            assertEquals(1,upgraded.store().quantity("test:a"));assertNull(upgraded.store().remoteCommand("rolled-back"))
            upgraded.withTransaction {
                upgraded.store().adjustQuantity(card,1,"committed","s")
                upgraded.store().saveRemoteCommand(RemoteCommand("saved","hash","{\"ok\":true}",2))
            }
            upgraded.close();upgraded=open()
            assertEquals(2,upgraded.store().quantity("test:a"))
            assertEquals("hash",upgraded.store().remoteCommand("saved")!!.requestHash)
            assertNull(upgraded.store().currentBulkInventory()!!.finishedAt)
        } finally { upgraded.close();context.deleteDatabase(name) }
    }

}
