package com.arkhamscan.core

import java.security.MessageDigest

/** A face/printing and its possible retail origins are deliberately separate identities. */
data class Card(val id: String, val title: String, val number: String, val pack: String,
    val type: String = "", val encounter: String = "", val equivalentFace: String? = null,
    val compatibleProducts: Set<String> = emptySet())
data class Product(val id: String, val title: String)
data class Manifest(val productId: String, val quantities: Map<String, Int>, val verified: Boolean)
data class Missing(val cardId: String, val expected: Int, val observed: Int) {
    val missing get() = (expected - observed).coerceAtLeast(0)
    val excess get() = (observed - expected).coerceAtLeast(0)
}
data class Completeness(val rows: List<Missing>, val verified: Boolean) {
    val fraction: Double? get() = if (!verified || rows.sumOf { it.expected } == 0) null
        else rows.sumOf { minOf(it.expected, it.observed) }.toDouble() / rows.sumOf { it.expected }
}
/** Only explicitly allocated copies may be passed here; compatible origins alone prove nothing. */
fun completeness(manifest: Manifest, allocatedCopies: Map<String, Int>): Completeness {
    require(manifest.quantities.values.all { it >= 0 } && allocatedCopies.values.all { it >= 0 })
    return Completeness((manifest.quantities.keys + allocatedCopies.keys).sorted().map {
        Missing(it, manifest.quantities[it] ?: 0, allocatedCopies[it] ?: 0)
    }, manifest.verified)
}

enum class Mode { RAPID_NEW_INVENTORY, INTERACTIVE_RESOLUTION, RESORT_EXISTING }
enum class Route { COMMIT_FIRST, RE_SORT, AWAIT_CONFIRMATION, ROUTE_EXISTING }
data class Evidence(val candidates: Set<String>, val validatedMatch: Boolean,
    val retailOriginResolved: Boolean, val freshFrame: Boolean)
fun decide(mode: Mode, evidence: Evidence, confirmedQuantity: Int): Route {
    require(confirmedQuantity >= 0)
    if (!evidence.freshFrame || !evidence.validatedMatch || evidence.candidates.size != 1 || !evidence.retailOriginResolved)
        return Route.RE_SORT
    return when (mode) {
        Mode.RESORT_EXISTING -> Route.ROUTE_EXISTING
        Mode.INTERACTIVE_RESOLUTION -> Route.AWAIT_CONFIRMATION
        Mode.RAPID_NEW_INVENTORY -> if (confirmedQuantity == 0) Route.COMMIT_FIRST else Route.RE_SORT
    }
}

/** Conservative OCR gate: only an empty view sustained for removalMs rearms it.
 * Recognition result changes do not prove removal. No images or OCR text are retained here. */
class PresentationGate(private val stableMs: Long = 650, private val removalMs: Long = 900) {
    private var latched = false
    private var visibleSince: Long? = null
    private var emptySince: Long? = null
    fun observe(hasCardText: Boolean, now: Long): Boolean {
        if (!hasCardText) {
            visibleSince = null
            if (emptySince == null) emptySince = now
            if (now - emptySince!! >= removalMs) latched = false
            return false
        }
        emptySince = null
        if (latched) return false
        if (visibleSince == null) visibleSince = now
        if (now - visibleSince!! < stableMs) return false
        latched = true
        return true
    }
}

data class Destination(val id: String, val label: String, val quantity: Int = 1, val category: String = "")
data class PhysicalPile(val number: Int, val destinations: List<Destination>) {
    val quantity get() = destinations.sumOf { it.quantity }
}
data class PassPlan(val version: String, val source: String, val piles: List<PhysicalPile>, val capacity: Int) {
    val instructions get() = "Pick up $source. Keep all other source piles separate. Clear $capacity table positions; " +
        "use numbered destinations below and reserve position $capacity for Re-sort. Ownership counts stay unchanged."
    fun pileFor(compatibleDestinations: Set<String>): Int? {
        if (compatibleDestinations.isEmpty()) return null
        return piles.singleOrNull { p -> compatibleDestinations.all { id -> p.destinations.any { it.id == id } } }?.number
    }
}
data class Simulation(val passes: Int, val peakPiles: Int, val presentations: Long, val reScans: Long)
object PilePlanner {
    fun plan(destinations: List<Destination>, maxPiles: Int? = null, source: String = "unsorted cards", history: String = "root"): PassPlan {
        require(destinations.isNotEmpty()) { "Choose at least one destination" }
        require(destinations.map { it.id }.toSet().size == destinations.size) { "Destination IDs must be unique" }
        require(destinations.all { it.quantity >= 0 })
        val capacity = maxPiles ?: 5
        require(capacity in 3..32) { "Use 3–32 positions, including Re-sort" }
        // Contiguous taxonomy groups favor recognizable categories; greedy weights balance later passes.
        val groups = MutableList(minOf(capacity - 1, destinations.size)) { mutableListOf<Destination>() }
        destinations.sortedWith(compareBy<Destination> { it.category }.thenByDescending { it.quantity }.thenBy { it.id }).forEach { d ->
            groups.minBy { it.sumOf { x -> x.quantity.toLong() } * 10000 + it.size }.add(d)
        }
        val piles = groups.mapIndexed { i, ds -> PhysicalPile(i + 1, ds.toList()) }
        val canonical = "$history|$source|$capacity|" + piles.joinToString(";") { p ->
            "${p.number}:" + p.destinations.joinToString(",") { "${it.id.length}:${it.id}:${it.quantity}" }
        }
        val version = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        return PassPlan(version, source, piles, capacity)
    }
    fun simulate(destinations: List<Destination>, maxPiles: Int? = null): Simulation {
        var passes = 0; var peak = 0; var presentations = 0L
        fun walk(ds: List<Destination>, source: String, history: String) {
            val plan = plan(ds, maxPiles, source, history)
            passes++; peak = maxOf(peak, plan.piles.size + 1)
            presentations += ds.sumOf { it.quantity.toLong() }
            plan.piles.filter { it.destinations.size > 1 }.forEach {
                walk(it.destinations, "$source / Pile ${it.number}", plan.version)
            }
        }
        if (destinations.isEmpty()) return Simulation(0, 0, 0, 0)
        walk(destinations, "unsorted cards", "root")
        return Simulation(passes, peak, presentations, presentations - destinations.sumOf { it.quantity.toLong() })
    }
}

/** Resumed passes use persisted ledgers, never an inventory insertion. */
class PassLedger(private val sourceQuantities: Map<String, Int>, entries: Map<String, String> = emptyMap()) {
    private val events = entries.toMutableMap()
    val routed get() = events.values.groupingBy { it }.eachCount()
    fun route(eventId: String, cardId: String): Boolean {
        if (events.containsKey(eventId)) return false
        require((routed[cardId] ?: 0) < (sourceQuantities[cardId] ?: 0)) { "Source count exhausted; reconcile this pile" }
        events[eventId] = cardId
        return true
    }
    fun snapshot(): Map<String, String> = events.toMap()
}

/** Presentation-only latch: holding a candidate never certifies a recognition match. */
class CandidateLatch(private val stableMs: Long = 200, private val removalMs: Long = 900) {
    private val pending = linkedMapOf<String, Pair<Long, Long>>()
    private val held = mutableListOf<String>()
    private var consumed = emptySet<String>()
    private var emptySince: Long? = null
    var waitingForRemoval = false; private set
    val candidates: List<String> get() = held.toList()
    fun observe(ids: List<String>, now: Long): List<String> {
        val fresh = ids.distinct().take(8)
        if (fresh.isEmpty()) {
            if (emptySince == null) emptySince = now
            pending.clear()
            if (waitingForRemoval && now - emptySince!! >= removalMs) { waitingForRemoval = false; consumed = emptySet() }
            return candidates // OCR dropouts never erase the buttons under a user's finger.
        }
        emptySince = null
        pending.keys.filter { it !in fresh }.toList().forEach { pending.remove(it) }
        fresh.forEach { id ->
            val previous = pending[id]
            pending[id] = if (previous == null || now - previous.second > 1500) now to now else previous.first to now
        }
        val stable = fresh.filter { now - pending.getValue(it).first >= stableMs }
        if (waitingForRemoval) {
            // A different title may indicate a changed card, but still requires manual confirmation.
            if (stable.isEmpty() || stable.any { it in consumed }) return candidates
            waitingForRemoval = false; consumed = emptySet()
        }
        if (held.isEmpty()) held.addAll(stable)
        // Freeze the entire list, including ordering, until selection or explicit Next card.
        return candidates
    }
    fun consume(ids: Set<String> = held.toSet()) { consumed = ids; held.clear(); pending.clear(); waitingForRemoval = true; emptySince = null }
    fun next() { held.clear(); pending.clear(); consumed = emptySet(); waitingForRemoval = false; emptySince = null }
}

data class LiveGroup(val id: String, val label: String, val pile: Int)
object GrowingPilePlanner {
    /** First-seen groups retain their slots. The last numbered slot is a bounded mixed pile. */
    fun assign(groups: List<LiveGroup>, id: String, label: String, capacity: Int): LiveGroup {
        require(capacity in 3..32 && id.isNotBlank() && label.isNotBlank())
        groups.firstOrNull { it.id == id }?.let { return it }
        val dedicated = (1 until capacity - 1).firstOrNull { n -> groups.none { it.pile == n } }
        return LiveGroup(id, label, dedicated ?: capacity - 1)
    }
}
