package com.arkhamscan

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ArkhamDbCatalogTest {
    // Synthetic API fixtures; these are not reference data distributed with the app.
    private val packs = """[{"code":"p","name":"Test reference pack"}]""".toByteArray()
    private val records = """[
        {"code":"00001","name":"Test card","position":1,"pack_code":"p","type_code":"asset","text":"Not retained","imagesrc":"https://example.com/not-fetched.jpg"},
        {"code":"00002","name":"Test encounter","position":2,"pack_code":"p","type_code":"enemy","encounter_code":"test"},
        {"code":"00000","name":"Pseudo-card","hidden":true,"pack_code":"p"}
    ]""".toByteArray()
    @Test fun downloadRequestsPublicMetadataIncludingEncounterCardsWithoutImages() {
        val requests = mutableListOf<String>()
        val cards = ArkhamDbCatalog { address ->
            requests += address
            when (address) { ArkhamDbCatalog.PACKS_URL -> packs; ArkhamDbCatalog.CARDS_URL -> records; else -> error("Unexpected request") }
        }.download()
        assertEquals(listOf(ArkhamDbCatalog.PACKS_URL, ArkhamDbCatalog.CARDS_URL), requests)
        assertTrue(requests.last().contains("encounter=1"))
        assertEquals(2, cards.size)
        assertEquals("arkhamdb:00001", cards.first().id)
        assertEquals("Test reference pack", cards.first().packName)
        assertEquals("enemy", cards.last().type)
        assertEquals("test", cards.last().encounter)
        assertTrue(cards.all { it.source == ArkhamDbCatalog.CARDS_URL })
        assertEquals(64, cards.first().version.length)
    }
    @Test fun imageAndRulesChangesDoNotBecomeCachedReferenceData() {
        val first = ArkhamDbCatalog.parse(records, packs)
        val other = ArkhamDbCatalog.parse(records.toString(Charsets.UTF_8).replace("not-fetched.jpg", "different.jpg").replace("Not retained", "Different rules").toByteArray(), packs)
        assertEquals(first, other)
    }
    @Test fun changedCardFactsHaveNewProvenanceHash() {
        val first = ArkhamDbCatalog.parse(records, packs)
        val changed = ArkhamDbCatalog.parse(records.toString(Charsets.UTF_8).replace("Test card", "Updated title").toByteArray(), packs)
        assertNotEquals(first.first().version, changed.first().version)
    }
    @Test fun incompleteOrDuplicateCatalogFailsRatherThanSilentlyReplacingReference() {
        for (bad in listOf("[]".toByteArray(), """[{"code":"different","name":"Unknown pack"}]""".toByteArray())) {
            try { ArkhamDbCatalog.parse(records, bad); fail("Must reject inconsistent API responses") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
        val card = """{"code":"1","name":"Test","pack_code":"p"}"""
        try { ArkhamDbCatalog.parse("[$card,$card]".toByteArray(), packs); fail("Duplicate IDs must be rejected") } catch (_: IllegalArgumentException) {}
    }
    @Test fun cycleMembershipComesFromPackMetadataAndKeepsChaptersDistinct() {
        val packData = """[{"code":"p","name":"Test campaign","cycle_position":2,"position":1,"chapter":1},
            {"code":"q","name":"Test mythos","cycle_position":2,"position":2,"chapter":1},
            {"code":"r","name":"Other chapter","cycle_position":2,"position":1,"chapter":2}]""".toByteArray()
        val data = """[{"code":"1","name":"One","pack_code":"p"},{"code":"2","name":"Two","pack_code":"q"},{"code":"3","name":"Three","pack_code":"r"}]""".toByteArray()
        val parsed = ArkhamDbCatalog.parse(data, packData)
        assertEquals(parsed[0].familyId, parsed[1].familyId)
        assertEquals(parsed[0].familyName, parsed[1].familyName)
        assertNotEquals(parsed[0].familyId, parsed[2].familyId)
    }
    @Test fun broadPromoOrStandaloneCategoriesDoNotPretendToBeOneCampaign() {
        val packData = """[{"code":"p","name":"Test standalone","cycle_position":70,"position":1}]""".toByteArray()
        val parsed = ArkhamDbCatalog.parse("""[{"code":"1","name":"One","pack_code":"p"}]""".toByteArray(), packData)
        assertEquals("", parsed.single().familyId)
    }
    @Test fun apiFailureIsSurfacedWithoutInventingCatalogData() {
        try { ArkhamDbCatalog { error("network unavailable") }.download(); fail("Must report failure") }
        catch (e: IllegalStateException) { assertEquals("network unavailable", e.message) }
    }
    @Test fun referencePagesUseFixedHostAndValidPrintingIds() {
        val card = ArkhamDbCatalog.parse(records, packs).first()
        assertEquals("https://arkhamdb.com/card/00001", ArkhamDbCatalog.cardPage(card))
        assertNull(ArkhamDbCatalog.cardPage(card.copy(id = "arkhamdb:../bad")))
        assertNull(ArkhamDbCatalog.cardPage(card.copy(id = "other:123", source = "https://example.com")))
    }
}
