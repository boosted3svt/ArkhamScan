package com.arkhamscan

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.arkhamscan.core.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    // Bind the factory to this Application rather than the process-wide cached factory.
    private val vm: ScanViewModel by viewModels { androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application) }
    override fun onResume() { super.onResume(); vm.remote.setForeground(true) }
    override fun onPause() { vm.remote.setForeground(false); super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { ArkhamTheme { ArkhamApp(vm) } }
    }
}
private val Ink = Color(0xFF101B20)
private val Gold = Color(0xFFD7BD83)
@Composable fun ArkhamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Gold, secondary = Color(0xFF94C9BC),
        background = Color(0xFF0A1216), surface = Ink, surfaceVariant = Color(0xFF1E3036),
        onPrimary = Color(0xFF241D0E)), content = content)
}
@Composable private fun AudioFeedback(vm: ScanViewModel) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS } }
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 40) }
    val owner = LocalLifecycleOwner.current
    var offlineVoiceReady by remember { mutableStateOf(false) }
    val rate by vm.speechRate.collectAsStateWithLifecycle()
    LaunchedEffect(vm, ready, rate) {
        if (ready) {
            val voice = tts.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == "en" }
            offlineVoiceReady = voice != null
            if (voice != null) tts.voice = voice
            else vm.message.value = "No offline English voice found. Install one in Android text-to-speech settings; audio cues still work."
            tts.setSpeechRate(rate)
        }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.cues.collect { phrase ->
            tone.startTone(if (phrase == "Re-sort") ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK, 100)
            if (ready && offlineVoiceReady) tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        } }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { tts.stop(); tone.stopTone() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown(); tone.release() } }
}
private val tabs = listOf("Overview", "Scan", "Collection", "Re-sort", "Piles", "Settings")
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ArkhamApp(vm: ScanViewModel) {
    val discovery by vm.discovery.collectAsStateWithLifecycle()
    val bulkInventory by vm.bulkInventory.collectAsStateWithLifecycle()
    val discoveryUi = remember { DiscoveryUiState() }
    var showDiscovery by remember { mutableStateOf(false) }
    var reportScope by remember { mutableStateOf<String?>(null) }
    var reportOutstanding by remember { mutableStateOf(false) }
    val cards by vm.cards.collectAsStateWithLifecycle()
    val downloading by vm.downloadingCatalog.collectAsStateWithLifecycle()
    val copies by vm.copies.collectAsStateWithLifecycle()
    val exceptions by vm.exceptions.collectAsStateWithLifecycle()
    val passes by vm.passes.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val liveRuns by vm.liveRuns.collectAsStateWithLifecycle()
    val liveGroups by vm.liveGroups.collectAsStateWithLifecycle()
    val livePlacements by vm.livePlacements.collectAsStateWithLifecycle()
    val lastPile by vm.lastPile.collectAsStateWithLifecycle()
    val waitingForRemoval by vm.waitingForRemoval.collectAsStateWithLifecycle()
    val liveGrouping by vm.liveGrouping.collectAsStateWithLifecycle()
    val liveRun = liveRuns.firstOrNull()
    val active = copies.filter { it.status == "active" }
    val selectable = (cards + active.map { CatalogCard(it.cardId, it.title, "", it.pack, it.packName, "", "", it.source, it.catalogVersion) }).distinctBy { it.id }
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CatalogCard?>(null) }
    var exceptionId by remember { mutableStateOf<String?>(null) }
    var chosenPresentation by remember { mutableStateOf("") }
    var eventId by remember { mutableStateOf("") }
    var resetLiveMap by remember { mutableStateOf(false) }
    var undoCopy by remember { mutableStateOf<CopyRecord?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importCatalog = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importCatalog) }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { try {
            val data = vm.exportJson(); context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray()) } ?: error("Cannot open export")
            vm.message.value = "JSON audit export saved. Backup restore is not yet implemented."
        } catch (e: Exception) { vm.message.value = "Export failed: ${e.message}" } }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(vm.exportCsv().toByteArray()) } ?: error("Cannot open export")
            vm.message.value = "Collection CSV saved"
        } catch (e: Exception) { vm.message.value = "Export failed: ${e.message}" } }
    }
    val exportDiscovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val data = vm.discoveryCsv(reportScope, reportOutstanding)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data.toByteArray()) } ?: error("Cannot open export")
            }
            vm.message.value = "Reference progress CSV saved; overlapping scope totals must not be added together."
        } catch(e: Exception) { vm.message.value = "Export failed: ${e.message}" } }
    }
    fun choose(card: CatalogCard, queue: String? = null) { selected = card; chosenPresentation = vm.remote.presentation; exceptionId = queue; eventId = UUID.randomUUID().toString() }
    val listState = rememberLazyListState()
    LaunchedEffect(tab, showDiscovery, discoveryUi.selected) { listState.scrollToItem(0) }
    val remoteEnabled by vm.remote.enabled.collectAsStateWithLifecycle()
    val cameraRequest by vm.remote.cameraRequest.collectAsStateWithLifecycle()
    val previewView = remember(context) { androidx.camera.view.PreviewView(context).apply { scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER } }
    CaptureEngine(vm, previewView)
    LaunchedEffect(tab, remoteEnabled) { if (tab != 1 && !remoteEnabled) vm.remote.camera("stop") }
    DisposableEffect(remoteEnabled, cameraRequest) {
        val activity = context as? android.app.Activity
        if (remoteEnabled || cameraRequest == "running") activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    AudioFeedback(vm)
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text("ARKHAMSCAN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("THE COLLECTION DESK", style = MaterialTheme.typography.labelSmall, color = Gold) } },
            actions = { Text("ALPHA 05", modifier = Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelSmall, color = Gold) })
    }, bottomBar = {
        NavigationBar {
            val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.CameraAlt, Icons.Outlined.Style,
                Icons.Outlined.Rule, Icons.Outlined.AccountTree, Icons.Outlined.Settings)
            tabs.forEachIndexed { i, label -> NavigationBarItem(selected = tab == i, onClick = { tab = i },
                icon = { Icon(icons[i], contentDescription = label) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }) }
        }
    }) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(tabs[tab], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold) }
            item { Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            } }
            if (tab == 0 || tab == 5 || remoteEnabled) item { RemoteControls(vm) }
            when (tab) {
                0 -> {
                    item { Text("Put your collection in order.", style = MaterialTheme.typography.titleLarge)
                        Text("A private, offline desk for counting cards and planning manageable sorting passes.", color = Color(0xFFB1C1C6)) }
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("CONFIRMED COPIES", active.size.toString(), Modifier.weight(1f))
                        Metric("DISTINCT PRINTINGS", active.map { it.cardId }.toSet().size.toString(), Modifier.weight(1f))
                    } }
                    item { InfoCard("Connect to ArkhamDB", if (cards.isEmpty()) "Download card names, numbers, and set metadata directly from ArkhamDB. No database file, account, or API key needed. Card images stay on the reference website." else "${cards.size} reference printings available offline. You can refresh from ArkhamDB whenever you choose.")
                        Button(onClick = { vm.downloadArkhamDb() }, enabled = !downloading, modifier = Modifier.fillMaxWidth()) { Text(if (downloading) "Downloading…" else "Download from ArkhamDB") }
                        if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = { openPage(context, "https://arkhamdb.com", vm) }) { Text("Browse ArkhamDB website") }
                        TextButton(onClick = { importCatalog.launch(arrayOf("application/json", "text/*")) }, enabled = !downloading) { Text("Import a catalog file instead") } }
                    item { Button(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth(), enabled = cards.isNotEmpty()) { Text("Open scanning desk") } }
                    item { Button(onClick = { showDiscovery = true; tab = 2; discoveryUi.selected = null }, modifier = Modifier.fillMaxWidth()) { Text("Explore sets & cycles") }
                        Text("Discover the contents of an unknown bulk collection. No purchase history required.") }
                    item { InfoCard("Honest counts, deliberate decisions", "OCR suggests identities; you confirm physical copies. Repeat presentations and uncertain cards never add ownership automatically. Original retail provenance remains unverified.") }
                    item { InfoCard("Readiness", "This foundation has not been validated on physical cards or the Galaxy S26 Ultra. Automatic recognition, independently verified retail manifests, scenario dependency readiness, and backup restore are pending. Source-based discovery reports are available in Collection.") }
                }
                1 -> {
                    item {
                        Text("Workflow", style = MaterialTheme.typography.titleMedium)
                        Mode.entries.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = mode == m, onClick = { vm.mode.value = m })
                                Text(when(m) { Mode.RAPID_NEW_INVENTORY -> "Rapid · uncertain cards to Re-sort"; Mode.INTERACTIVE_RESOLUTION -> "Interactive · confirm physical copies"; Mode.RESORT_EXISTING -> "Re-sort · preserve ownership counts" })
                            }
                        }
                    }
                    item { CameraPanel(vm, mode, previewView) }
                    item { val suggested by vm.suggestions.collectAsStateWithLifecycle()
                        lastPile?.let { Text("Last confirmed placement", style = MaterialTheme.typography.labelSmall); Text(it, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Gold) }
                        Text("Held camera suggestions", style = MaterialTheme.typography.titleMedium)
                        Text(if (waitingForRemoval) "Remove this card before the next scan, or tap Next card to retry." else "Suggestions stay readable until you choose one or tap Next card. Check the exact printing before confirming.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { vm.nextCard() }) { Text("Next card / retry") }
                        if (suggested.isEmpty()) Text("No current title candidates. Search below to identify manually.")
                        suggested.forEach { card -> CardRow(card, active.count { it.cardId == card.id }) { choose(card) } }
                    }
                    item { OutlinedTextField(search, { search = it }, label = { Text("Search title, printed number, set, or code") }, modifier = Modifier.fillMaxWidth()) }
                    items(selectable.filter { search.isNotBlank() && matches(it, search) }.take(40), key = { it.id }) { c -> CardRow(c, active.count { it.cardId == c.id }) { choose(c) } }
                    item { if (mode == Mode.RESORT_EXISTING) Text("Select a card to route one already-counted copy through the active pass. No ownership will be created.") }
                }
                2 -> {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(!showDiscovery, onClick = { showDiscovery = false }, label = { Text("Cards & quantities") })
                        FilterChip(showDiscovery, onClick = { showDiscovery = true }, label = { Text("Sets & cycles") })
                    } }
                    if (showDiscovery) {
                        discoveryItems(vm, discovery, bulkInventory, exceptions.count { it.status in setOf("pending", "deferred") }, discoveryUi) { scopeId, outstanding ->
                            reportScope = scopeId; reportOutstanding = outstanding
                            exportDiscovery.launch(if (outstanding) "arkhamscan-outstanding.csv" else "arkhamscan-progress.csv")
                        }
                    } else {

                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { exportCsv.launch("arkhamscan-inventory.csv") }) { Text("Export CSV") }
                        OutlinedButton(onClick = { exportJson.launch("arkhamscan-audit.json") }) { Text("Export JSON") }
                    } }
                    item { InfoCard("Bulk collection progress", "Open Sets & cycles to compare cumulative ownership with source-listed contents. Percentages remain provisional until retail manifests are independently verified.") }
                    if (passes.any { it.status == "active" }) item { Text("Finish or abandon the active re-sort pass in Piles before editing quantities.", color = Gold) }
                    item { OutlinedTextField(search, { search = it }, label = { Text("Search collection") }, modifier = Modifier.fillMaxWidth()) }
                    if (copies.isEmpty()) item { EmptyState("Your collection starts here", "Download from ArkhamDB on Overview, then use the scanning desk to identify and confirm each physical copy.") }
                    items(copies.groupBy { it.cardId }.values.filter { search.isBlank() || it.first().title.contains(search, true) || it.first().packName.contains(search, true) }, key = { it.first().cardId }) { group ->
                        val c = group.first()
                        Surface(shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(c.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text("×${group.count { it.status == "active" }}", color = Gold) }
                            Text("${c.cardId} · ${c.packName}", style = MaterialTheme.typography.bodySmall)
                            Text("User-confirmed physical copies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val quantity = group.count { it.status == "active" }
                                val card = cards.firstOrNull { it.id == c.cardId } ?: CatalogCard(c.cardId, c.title, "", c.pack, c.packName, "", "", c.source, c.catalogVersion)
                                IconButton(onClick = { vm.adjustQuantity(card, -1) }, enabled = quantity > 0 && passes.none { it.status == "active" }) { Icon(Icons.Outlined.Remove, contentDescription = "Decrease ${c.title}") }
                                Text(quantity.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 12.dp))
                                IconButton(onClick = { vm.adjustQuantity(card, 1) }, enabled = passes.none { it.status == "active" }) { Icon(Icons.Outlined.Add, contentDescription = "Increase ${c.title}") }
                            }
                            Text("Quantity edits update ownership only; they do not move cards between physical piles.", style = MaterialTheme.typography.labelSmall)
                        } }
                    }

                    }
                }
                3 -> {
                    val current = exceptions.filter { it.sessionId == vm.sessionId }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("ROUTED THIS SESSION", current.size.toString(), Modifier.weight(1f))
                        Metric("PENDING RECORDS", current.count { it.status == "pending" || it.status == "deferred" }.toString(), Modifier.weight(1f))
                    } }
                    item { InfoCard("Pick up the physical Re-sort pile", "Records are recognition attempts, not unique physical cards. Re-scan each card or search manually. Reconcile any difference between the physical pile and this inbox; do not assume a one-to-one match.") }
                    item { Button(onClick = { vm.mode.value = Mode.INTERACTIVE_RESOLUTION; tab = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Open live resolution desk") } }
                    if (current.none { it.status == "pending" || it.status == "deferred" }) item { EmptyState("No pending attempts", "Rapid camera presentations requiring a decision will appear here. They never change ownership.") }
                    items(current.filter { it.status == "pending" || it.status == "deferred" }, key = { it.id }) { e ->
                        Surface(shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${e.status.uppercase()} · Unvalidated camera attempt", fontWeight = FontWeight.SemiBold)
                            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(e.createdAt)), style = MaterialTheme.typography.labelSmall)
                            Text("Choose only if you have independently identified the physical card. These candidates do not prove a match.", style = MaterialTheme.typography.bodySmall)
                            val ids = org.json.JSONArray(e.candidates).let { a -> (0 until a.length()).map { a.getString(it) } }
                            cards.filter { it.id in ids }.forEach { card -> TextButton(onClick = { vm.mode.value = Mode.INTERACTIVE_RESOLUTION; choose(card, e.id) }) { Text("Resolve as ${card.title} · ${card.id}") } }
                            Row { TextButton(onClick = { vm.dismiss(e.id, "already_counted") }) { Text("Already counted") }; TextButton(onClick = { vm.dismiss(e.id, "deferred") }) { Text("Defer") } }
                        } }
                    }
                }
                4 -> {
                    item {
                        InfoCard("Sort as you scan", "Each confirmed new copy gets a visible and spoken pile number. Existing group numbers stay fixed. The last numbered pile holds extra groups for a later split; the final table position is always Re-sort.")
                        Text("Grouping for the next fresh map", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(liveGrouping == "PACK", onClick = { vm.setLiveGrouping("PACK") }); Text("Retail reference pack")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(liveGrouping == "CYCLE", onClick = { vm.setLiveGrouping("CYCLE") }); Text("ArkhamDB cycle / chapter group")
                        }
                        Text("Reference packs and cycles are useful sorting groups, but identical reprints do not prove which box you bought. Refresh ArkhamDB for cycle metadata; older catalogs fall back to pack grouping.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { resetLiveMap = true }, enabled = cards.isNotEmpty() && passes.none { it.status == "active" }) { Text("Start fresh live pile map") }
                        liveRun?.let { run ->
                            Text("${if (run.state != "active") "SAVED MAP · not the current table" else "LIVE MAP"} · ${run.capacity} positions · ${if (run.grouping == "PACK") "reference packs" else "cycle groups"}", color = Gold)
                            Text("Capacity and grouping are fixed for this map. A fresh map requires a cleared table; inventory stays unchanged.", style = MaterialTheme.typography.bodySmall)
                            val groups = liveGroups.filter { it.runId == run.id }
                            val activeIds = active.map { it.id }.toSet()
                            groups.groupBy { it.pile }.toSortedMap().forEach { (number, members) ->
                                val quantity = livePlacements.count { it.runId == run.id && it.pile == number && it.copyId in activeIds }
                                val mixed = number == run.capacity - 1
                                Text("Pile $number · ${if (mixed) "Mixed groups — split later" else members.first().label}", style = MaterialTheme.typography.titleLarge)
                                Text("$quantity confirmed copies · " + members.joinToString(" · ") { it.label })
                                TextButton(onClick = {
                                    showDiscovery = true; tab = 2
                                    discoveryUi.kind = if (run.grouping == "CYCLE") "CYCLE" else "PACK"
                                    discoveryUi.selected = members.singleOrNull()?.groupId?.takeIf { id -> discovery.scopes.any { it.id == id } }
                                }) { Text("View cumulative group progress") }
                                if ((mixed || run.grouping == "CYCLE") && passes.none { saved -> val source = JSONObject(saved.json); saved.status != "abandoned" && source.optString("liveRunId") == run.id && source.optInt("livePile", -1) == number }) TextButton(onClick = { vm.makePlan(source = "live map ${run.id.take(8)} / Pile $number", liveSource = run.id to number) }, enabled = quantity > 0 && passes.none { it.status == "active" }) { Text("Split Pile $number by reference pack") }
                            }
                            Text("Position ${run.capacity}: Re-sort", color = MaterialTheme.colorScheme.secondary)
                            if (groups.isEmpty()) Text("Confirm a card on Scan to assign your first pile.")
                        } ?: Text("Start a fresh map on a cleared table. With no prior re-sort pass, the first card confirmation can also start it automatically.")
                    }
                    item { Text("Re-sort existing inventory", style = MaterialTheme.typography.titleLarge)
                        Text("Earlier inventory and collection quantity edits have no automatic physical placement. Use a separate count-preserving pass to sort those cards.", style = MaterialTheme.typography.bodySmall) }
                    item {
                        val cap by vm.capacity.collectAsStateWithLifecycle()
                        Text("Table capacity: $cap positions, including Re-sort")
                        val ds = active.groupBy { it.pack }.map { (p, cs) -> Destination(p, cs.first().packName, cs.size) }
                        if (ds.isNotEmpty()) {
                            val simulation = PilePlanner.simulate(ds, cap)
                            Text("Estimate: ${simulation.passes} passes · ${simulation.peakPiles} peak piles · ${simulation.reScans} additional presentations", color = Gold)
                            Text("Assumes known reference-pack destinations and no exceptions. This is a heuristic estimate, not an optimality guarantee.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { vm.makePlan() }, enabled = passes.none { it.status == "active" }) { Text("Freeze new sorting pass") }
                        } else Text("Confirm inventory first to simulate your actual collection.")
                    }
                    items(passes, key = { it.id }) { pass ->
                        val p = JSONObject(pass.json)
                        Surface(shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${pass.status.uppercase()} PASS · ${pass.id.take(8)}", color = Gold)
                            Text(p.getString("instructions"))
                            val ps = p.getJSONArray("piles")
                            for (i in 0 until ps.length()) {
                                val pile = ps.getJSONObject(i); val ds = pile.getJSONArray("destinations")
                                Text("Pile ${pile.getInt("number")}", style = MaterialTheme.typography.titleLarge)
                                Text((0 until ds.length()).joinToString(" · ") { ds.getJSONObject(it).getString("label") })
                                if (pass.status != "abandoned" && ds.length() > 1 && passes.none { child -> val cp = JSONObject(child.json); cp.optString("parentId") == pass.id && cp.optInt("sourcePile", -1) == pile.getInt("number") }) OutlinedButton(onClick = { vm.makePlan("${p.getString("source")} / Pile ${pile.getInt("number")}", pass, pile.getInt("number")) }) { Text("Subdivide this source pile") }
                            }
                            Text("Position ${p.getInt("capacity")}: Re-sort", color = MaterialTheme.colorScheme.secondary)
                            val source = p.getJSONObject("sourceCounts")
                            Text("${routes.count { it.passId == pass.id }} / ${source.keys().asSequence().sumOf { source.getInt(it) }} existing copies routed")
                            if (pass.status == "active") Button(onClick = { vm.mode.value = Mode.RESORT_EXISTING; tab = 1 }) { Text("Route existing copies") }
                            if (pass.status == "active") {
                                TextButton(onClick = { vm.finishPass(pass) }) { Text("Complete reconciled pass") }
                                TextButton(onClick = { vm.abandonPass(pass) }) { Text("Abandon pass · keep ownership") }
                            }
                        } }
                    }
                    if (passes.isNotEmpty()) item { Text("${passes.count { it.status == "completed" }} completed passes retained in the audit export.") }
                }
                5 -> {
                    item {
                        val cap by vm.capacity.collectAsStateWithLifecycle(); val muted by vm.silent.collectAsStateWithLifecycle(); val rate by vm.speechRate.collectAsStateWithLifecycle()
                        Text("Sorting space", style = MaterialTheme.typography.titleMedium)
                        Text("$cap simultaneous positions including Re-sort · applies to the next fresh map or pass")
                        Slider(value = cap.toFloat(), onValueChange = { vm.configure(it.toInt(), muted, rate) }, valueRange = 3f..12f, steps = 8)
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Silent mode", Modifier.weight(1f)); Switch(muted, { vm.configure(cap, it, rate) }) }
                        Text("Speech speed: ${"%.1f".format(rate)}×")
                        Slider(value = rate, onValueChange = { vm.configure(cap, muted, it) }, valueRange = .6f..1.5f)
                    }
                    item { InfoCard("Camera privacy", "Frames are processed in memory by bundled on-device OCR. No photos, crops, thumbnails, or video files are saved. Internet is used for the ArkhamDB catalog download. Camera frames are not sent to ArkhamDB; reference pages open in your browser. Android and CameraX may buffer frames internally. Camera screens block screenshots; pause when leaving the desk.") }
                    item { InfoCard("Catalog provenance", cards.firstOrNull()?.let { "${it.source}\nSHA-256: ${it.version}\nHistorical copies retain the catalog hash from confirmation. No reference image was matched; no image identity is invented." } ?: "No catalog yet. Use Download from ArkhamDB on Overview; no local file is needed.") }
                    item {
                        Button(onClick = { vm.downloadArkhamDb() }, enabled = !downloading) { Text(if (downloading) "Downloading…" else "Refresh from ArkhamDB") }
                        OutlinedButton(onClick = { importCatalog.launch(arrayOf("application/json", "text/*")) }, enabled = !downloading) { Text("Import a catalog file instead") }
                    }
                    item { InfoCard("Alpha boundaries", "English OCR; one local collection; foreground camera only. Independent retail manifest verification, scenario dependencies, reference-image matching, auto-accept calibration, restore, and device benchmarks remain future milestones. Discovery reports use clearly labeled source targets. Export JSON for an auditable record; it is not yet a restorable backup.") }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    selected?.let { card ->
        val count = active.count { it.cardId == card.id }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(if (mode == Mode.RESORT_EXISTING) "Route an existing copy?" else "Confirm a physical copy") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.title, fontWeight = FontWeight.Bold)
                Text("${card.id} · #${card.number} · ${card.packName}")
                Text(if (card.expectedQuantity > 0) "$count exact-printing copies · ${card.expectedQuantity} source-listed target" else "$count confirmed copies · copy target not provided")
                Text(if (mode == Mode.RESORT_EXISTING) "This routes one counted copy through the active source pass. Ownership stays unchanged." else "Select New physical copy only while holding an additional copy you want to count. A repeated presentation alone is not evidence of a new copy.")
                Text("Check the printing when identifiable. Source-declared equivalent printings also count in content reports.", style = MaterialTheme.typography.bodySmall)
                ArkhamDbCatalog.cardPage(card)?.let { page -> TextButton(onClick = { openPage(context, page, vm) }) { Text("View card on ArkhamDB") } }
            } }, confirmButton = { TextButton(onClick = {
                if (mode == Mode.RESORT_EXISTING) vm.route(card.id, eventId, chosenPresentation) else vm.confirm(card, eventId, exceptionId, chosenPresentation)
                selected = null
            }) { Text(if (mode == Mode.RESORT_EXISTING) "Route existing copy" else "New physical copy") } },
            dismissButton = { TextButton(onClick = { exceptionId?.let { vm.dismiss(it, "already_counted") }; selected = null }) { Text(if (exceptionId != null) "Already counted" else "Cancel") } })
    }
    if (resetLiveMap) AlertDialog(onDismissRequest = { resetLiveMap = false }, title = { Text("Start with a cleared table?") },
        text = { Text("Set aside or finish the previous physical piles first. A fresh map may reuse their numbers with different destinations. Your owned quantities and historical maps stay unchanged.") },
        confirmButton = { TextButton(onClick = { vm.startFreshPiles(); resetLiveMap = false }) { Text("Table cleared · start map") } },
        dismissButton = { TextButton(onClick = { resetLiveMap = false }) { Text("Keep current map") } })
    undoCopy?.let { copy -> AlertDialog(onDismissRequest = { undoCopy = null }, title = { Text("Remove one confirmed copy?") }, text = { Text("${copy.title}\nThis keeps an audit record and reduces ownership by one.") }, confirmButton = { TextButton(onClick = { vm.undo(copy.id); undoCopy = null }) { Text("Remove one") } }, dismissButton = { TextButton(onClick = { undoCopy = null }) { Text("Keep copy") } }) }
}
private fun matches(c: CatalogCard, s: String) = listOf(c.title, c.id, c.number, c.packName, c.encounter, c.type).any { it.contains(s, true) }
@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(16.dp)) { Text(value, style = MaterialTheme.typography.headlineLarge, color = Gold); Text(label, style = MaterialTheme.typography.labelSmall) } }
}
@Composable private fun InfoCard(title: String, text: String) {
    Surface(shape = RoundedCornerShape(14.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Gold); Text(text, style = MaterialTheme.typography.bodyMedium)
    } }
}
@Composable private fun EmptyState(title: String, text: String) { Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(text) } }
@Composable private fun CardRow(card: CatalogCard, count: Int, choose: () -> Unit) {
    Surface(onClick = choose, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(card.title, fontWeight = FontWeight.SemiBold); Text("${card.id} · #${card.number} · ${card.packName}", style = MaterialTheme.typography.bodySmall) }
            Text("×$count", color = Gold, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

private fun openPage(context: android.content.Context, address: String, vm: ScanViewModel) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
    catch (_: android.content.ActivityNotFoundException) { vm.message.value = "No web browser is installed to open ArkhamDB." }
}
