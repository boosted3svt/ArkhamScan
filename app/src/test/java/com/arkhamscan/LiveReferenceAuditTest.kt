package com.arkhamscan

import com.arkhamscan.core.*
import org.json.JSONArray
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LiveReferenceAuditTest {
    @Test fun actualArkhamDbResponsesProduceFullProductIndexWithoutInventingRepackQuantities() {
        val dir = File(requireNotNull(System.getProperty("arkhamscan.liveReferenceDir")))
        val rawCards = File(dir,"arkham-cards-live.json").readBytes()
        val rawPacks = File(dir,"arkham-packs-live.json").readBytes()
        val snapshot = ArkhamDbCatalog.parseSnapshot(rawCards, rawPacks)
        assertEquals(JSONArray(rawPacks.toString(Charsets.UTF_8)).length(),snapshot.packs.size)
        assertTrue(snapshot.cards.size>5000)
        assertFalse(snapshot.cards.any { it.id=="arkhamdb:01121b" }) // Hidden reverse face is not another physical copy.
        assertEquals("arkhamdb:01086", snapshot.cards.single { it.id=="arkhamdb:01586" }.canonicalId)
        val index=DiscoveryEngine.build(snapshot.cards.map { IndexCard(it.id,it.title,it.pack,it.expectedQuantity.takeIf { n -> n>0 },it.canonicalId,it.encounter,it.encounterName) },
            snapshot.packs.map { p -> IndexPack(p.id,p.title,p.familyId,p.familyName,p.released,JSONArray(p.members).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },p.reprintType) }, mapOf("arkhamdb:01586" to 2))
        val knife=index.scopes.single { it.id=="pack:core" }.rows.single { it.id=="arkhamdb:01086" }
        assertEquals(4,knife.expected);assertEquals(2,knife.observed);assertEquals(0,knife.exact)
        val repack=index.scopes.single { it.id=="pack:dwlp" }
        assertTrue(repack.rows.isNotEmpty());assertNull(repack.expectedCopies)
        assertFalse(repack.allListedAccounted)
        println("Live reference audit: ${snapshot.cards.size} visible printings; ${snapshot.packs.size} products; ${index.scopes.size} product/cycle/encounter views")
    }
}
