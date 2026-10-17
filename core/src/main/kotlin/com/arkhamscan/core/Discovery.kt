package com.arkhamscan.core

import java.time.LocalDate

data class IndexCard(val id: String, val title: String, val pack: String, val quantity: Int?,
    val canonicalId: String = id, val encounter: String = "", val encounterName: String = encounter)
data class IndexPack(val id: String, val title: String, val familyId: String = "", val familyName: String = "",
    val released: String = "", val members: Set<String> = emptySet(), val reprintType: String = "")
data class DiscoveryRow(val id: String, val title: String, val printingIds: Set<String>, val expected: Int?,
    val observed: Int, val exact: Int) {
    val outstanding get() = expected?.let { (it - observed).coerceAtLeast(0) }
    val extras get() = expected?.let { (observed - it).coerceAtLeast(0) }
}
data class DiscoveryScope(val id: String, val title: String, val kind: String, val released: String,
    val rows: List<DiscoveryRow>, val membershipOnly: Boolean, val inTarget: Boolean, val sourceNote: String) {
    val expectedCopies: Int? get() = if (rows.isEmpty() || membershipOnly || rows.any { it.expected == null }) null else rows.sumOf { it.expected!! }
    val accounted get() = rows.sumOf { minOf(it.observed, it.expected ?: 0) }
    val listedObserved get() = rows.sumOf { it.observed }
    val designsFound get() = rows.count { it.observed > 0 }
    val designsExpected get() = rows.size
    val extras get() = rows.sumOf { it.extras ?: 0 }
    val fraction: Double? get() = expectedCopies?.takeIf { it > 0 }?.let { accounted.toDouble()/it }
    val discovered get() = designsFound > 0
    // Source-listed 100% is never a certification of a complete retail manifest.
    val allListedAccounted get() = fraction == 1.0
}
data class DiscoveryIndex(val scopes: List<DiscoveryScope>, val unindexedCopies: Int)

object DiscoveryEngine {
    fun build(cards: List<IndexCard>, packs: List<IndexPack>, owned: Map<String, Int>, cutoff: String = ""): DiscoveryIndex {
        require(owned.values.all { it >= 0 })
        if (cutoff.isNotBlank()) LocalDate.parse(cutoff)
        val byId = cards.associateBy { it.id }; val packById = packs.associateBy { it.id }
        fun canonical(card: IndexCard): String {
            var next = card; val visited = mutableSetOf<String>()
            while (next.canonicalId != next.id) {
                if (!visited.add(next.id)) return card.id // Malformed alias cycles never merge arbitrary identities.
                next = byId[next.canonicalId] ?: return next.canonicalId
            }
            return next.id
        }
        val canonicalFor = cards.associate { it.id to canonical(it) }
        val observed = owned.entries.groupBy { canonicalFor[it.key] ?: it.key }.mapValues { (_, xs) -> xs.sumOf { it.value } }
        fun eligible(pack: IndexPack) = cutoff.isBlank() || (pack.released.isNotBlank() && runCatching { LocalDate.parse(pack.released) <= LocalDate.parse(cutoff) }.getOrDefault(false))
        fun members(pack: IndexPack, seen: Set<String> = emptySet()): List<IndexCard> {
            if (pack.id in seen) return emptyList()
            val direct = cards.filter { it.pack == pack.id }
            val inherited = pack.members.flatMap { id -> packById[id]?.let { members(it, seen + pack.id) } ?: emptyList() }.filter {
                when(pack.reprintType) { "player" -> it.encounter.isBlank(); "campaign" -> it.encounter.isNotBlank(); else -> false }
            }
            return (direct + inherited).distinctBy { it.id }
        }
        fun rows(cs: List<IndexCard>, membershipOnly: Boolean): List<DiscoveryRow> = cs.groupBy { canonicalFor.getValue(it.id) }.map { (id, printings) ->
            val quantity = if (membershipOnly || printings.any { it.quantity == null || it.quantity <= 0 }) null else printings.maxOf { it.quantity!! }
            DiscoveryRow(id, printings.first().title, printings.map { it.id }.toSet(), quantity, observed[id] ?: 0,
                printings.sumOf { owned[it.id] ?: 0 })
        }.sortedBy { it.title }
        val scopes = mutableListOf<DiscoveryScope>()
        for (p in packs) scopes += DiscoveryScope("pack:${p.id}", p.title, "PACK", p.released, rows(members(p), p.members.isNotEmpty()),
            p.members.isNotEmpty(), eligible(p), if (p.members.isNotEmpty()) "Source-declared repackaging membership; new-box copy quantities are unverified. Design coverage only."
            else "Provisional source-listed copy quantities; not an independently verified retail manifest.")
        for ((id, ps) in packs.filter { it.familyId.isNotBlank() }.groupBy { it.familyId }) {
            val included = ps.filter(::eligible)
            // One logical content target per design across packaging/printing variants; never sum variant quantities.
            val cs = included.flatMap { p -> cards.filter { it.pack == p.id } }.distinctBy { it.id }
            scopes += DiscoveryScope(id, ps.first().familyName, "CYCLE", "", rows(cs, false), false, included.isNotEmpty(),
                "Logical cycle content: source-declared equivalent printings collapsed; maximum listed copies per design. Provisional, not a purchased-box total.")
        }
        for ((id, cs) in cards.filter { it.encounter.isNotBlank() && packById[it.pack]?.let(::eligible) == true }.groupBy { it.encounter })
            scopes += DiscoveryScope("encounter:$id", cs.first().encounterName.ifBlank { id }, "ENCOUNTER", "", rows(cs, false), false, true,
                "Source-listed encounter-set content. This does not establish which additional sets a scenario requires.")
        return DiscoveryIndex(scopes.sortedWith(compareByDescending<DiscoveryScope> { it.discovered }.thenBy { it.title }),
            owned.filterKeys { it !in byId }.values.sum())
    }
}
