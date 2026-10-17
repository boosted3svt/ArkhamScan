package com.arkhamscan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arkhamscan.core.*

@Stable
class DiscoveryUiState {
    var kind by mutableStateOf("PACK")
    var search by mutableStateOf("")
    var discoveredOnly by mutableStateOf(false)
    var includeExcluded by mutableStateOf(false)
    var outstandingOnly by mutableStateOf(false)
    var selected by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.discoveryItems(vm: ScanViewModel, index: DiscoveryIndex, bulk: BulkInventory,
    pending: Int, state: DiscoveryUiState, export: (String?, Boolean) -> Unit) {
    val finished = bulk.finishedAt != null && bulk.finishedRevision == bulk.revision
    val scope = index.scopes.firstOrNull { it.id == state.selected }
    item { DiscoveryControls(vm, bulk, pending, index) }
    item { Text("Content views can overlap. Equivalent printings count toward logical content, with exact-printing counts shown separately. Do not add box and cycle totals together.", style = MaterialTheme.typography.bodySmall) }
    if (scope != null) {
        item {
            TextButton(onClick = { state.selected = null }) { Text("Back to set index") }
            Text(scope.title, style = MaterialTheme.typography.headlineSmall)
            ScopeSummary(scope)
            Text(scope.sourceNote, style = MaterialTheme.typography.bodySmall)
            Text(if (finished) "Outstanding entries: potentially missing. Source gaps or unresolved cards may explain them." else "Outstanding entries: not yet accounted for. Keep scanning the bins.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export(scope.id, false) }) { Text("Progress CSV") }
                OutlinedButton(onClick = { export(scope.id, true) }) { Text("Outstanding CSV") }
            }
            FilterChip(selected = state.outstandingOnly, onClick = { state.outstandingOnly = !state.outstandingOnly }, label = { Text("Outstanding only") })
        }
        if (scope.rows.isEmpty()) item { Text("Reference incomplete: no physical-card records are available for this product. No percentage or missing-card claim is possible.") }
        items(scope.rows.filter { !state.outstandingOnly || (it.outstanding?.let { n -> n > 0 } ?: (it.observed == 0)) }, key = { "reference-row:${scope.id}:${it.id}" }) { row ->
            Surface(shape = RoundedCornerShape(12.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(row.title, fontWeight = FontWeight.SemiBold)
                Text(if (row.expected != null) "${row.observed} accounted for / ${row.expected} source-listed copies" else "${row.observed} accounted for · physical copy target unknown")
                Text("${row.exact} exact-printing copies · ${(row.observed-row.exact).coerceAtLeast(0)} source-declared equivalent copies", style = MaterialTheme.typography.bodySmall)
                row.outstanding?.takeIf { it > 0 }?.let { Text("$it ${if (finished) "potentially missing" else "not yet accounted for"}", color = MaterialTheme.colorScheme.primary) }
                row.extras?.takeIf { it > 0 }?.let { Text("$it extra relative to this target") }
                Text(row.printingIds.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            } }
        }
    } else {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PACK" to "Boxes / sets", "CYCLE" to "Cycles", "ENCOUNTER" to "Encounter sets").forEach { (kind, title) ->
                    FilterChip(selected = state.kind == kind, onClick = { state.kind = kind }, label = { Text(title) })
                }
            }
            OutlinedTextField(state.search, { state.search = it }, label = { Text("Search reference index") }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.discoveredOnly, onClick = { state.discoveredOnly = !state.discoveredOnly }, label = { Text("Discovered only") })
                FilterChip(state.includeExcluded, onClick = { state.includeExcluded = !state.includeExcluded }, label = { Text("Include outside cutoff") })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export(null, false) }) { Text("Progress CSV") }
                OutlinedButton(onClick = { export(null, true) }) { Text("Outstanding CSV") }
            }
        }
        val scopes = index.scopes.filter { it.kind == state.kind && (state.includeExcluded || it.inTarget) && (!state.discoveredOnly || it.discovered) && it.title.contains(state.search, true) }
        item { Text("${scopes.size} reference groups · ${scopes.count { it.discovered }} with contents discovered") }
        if (index.scopes.isEmpty()) item { Text("Download or refresh ArkhamDB to build the set index. You do not need purchase history or a database file.") }
        items(scopes, key = { "reference-scope:${it.id}" }) { s ->
            Surface(onClick = { state.selected = s.id }, shape = RoundedCornerShape(12.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium)
                ScopeSummary(s)
                Text(if (s.discovered) "Contents discovered · purchase history not required" else "Not discovered yet · ownership unknown", style = MaterialTheme.typography.bodySmall)
                if (!s.inTarget) Text("Outside cutoff or release date unknown", color = MaterialTheme.colorScheme.secondary)
                if (s.released.isNotBlank()) Text("Reference release: ${s.released}", style = MaterialTheme.typography.labelSmall)
            } }
        }
    }
}

@Composable
private fun ScopeSummary(scope: DiscoveryScope) {
    val expected = scope.expectedCopies
    if (expected != null && expected > 0) {
        Text("${scope.accounted*100/expected}% · ${scope.accounted}/$expected source-listed copies", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(progress = { scope.accounted.toFloat()/expected }, modifier = Modifier.fillMaxWidth())
        Text("Provisional reference · ${scope.designsFound}/${scope.designsExpected} logical designs found", style = MaterialTheme.typography.bodySmall)
        if (scope.allListedAccounted) Text("All listed quantities accounted for; full retail manifest unverified.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("${scope.designsFound}/${scope.designsExpected} listed designs found", fontWeight = FontWeight.Bold)
        Text(if (scope.membershipOnly) "Repackaged-content view · new-box copy quantities unverified" else "Reference incomplete · copy target unknown", style = MaterialTheme.typography.bodySmall)
    }
    if (scope.extras > 0) Text("${scope.extras} extra copies relative to this reference")
}

@Composable
private fun DiscoveryControls(vm: ScanViewModel, bulk: BulkInventory, pending: Int, index: DiscoveryIndex) {
    var cutoff by remember(bulk.cutoff) { mutableStateOf(bulk.cutoff) }
    var reviewRevision by remember { mutableStateOf<Long?>(null) }
    val finished = bulk.finishedAt != null && bulk.finishedRevision == bulk.revision
    val downloading by vm.downloadingCatalog.collectAsStateWithLifecycle()
    Button(onClick = { vm.downloadArkhamDb() }, enabled = !downloading) { Text(if (downloading) "Downloading reference…" else "Download / refresh reference index") }
    Text("Bulk collection discovery", style = MaterialTheme.typography.titleLarge)
    Text("Scan the bins over as many sessions as needed. Nothing here assumes you bought or own a complete box.")
    OutlinedTextField(cutoff, { cutoff = it }, label = { Text("Reference cutoff: YYYY-MM-DD (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Text("Leave blank if the lot's date is unknown. A cutoff limits reference targets, not your owned cards. Undated references are excluded when a cutoff is set.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { vm.setCutoff(cutoff) }) { Text("Apply reference cutoff") }
    Text(if (finished) "Inventory marked finished · outstanding entries are potentially missing" else "Inventory in progress · outstanding entries are not yet accounted for", color = MaterialTheme.colorScheme.primary)
    if (pending > 0) Text("$pending unresolved attempt records still need review; they are not unique physical-card counts.")
    if (index.unindexedCopies > 0) Text("${index.unindexedCopies} owned copies are absent from the current reference. Their ownership is retained and is not guessed into another set.")
    if (finished) OutlinedButton(onClick = { vm.reopenInventory() }) { Text("Resume inventory") }
    else OutlinedButton(onClick = { reviewRevision = bulk.revision }) { Text("Mark bins fully inventoried") }
    Text("Counts are source-based estimates until manifests are independently verified. Extra copies cannot fill gaps for other cards. No overall all-catalog percentage is claimed.", style = MaterialTheme.typography.bodySmall)
    reviewRevision?.let { revision -> AlertDialog(onDismissRequest = { reviewRevision = null }, title = { Text("Finished inventorying all bins?") },
        text = { Text("Outstanding reference entries will be labeled potentially missing, not proven absent. $pending unresolved attempt records remain. Future count edits, new scans, reference refreshes, or cutoff changes reopen review automatically.") },
        confirmButton = { TextButton(onClick = { vm.finishBulkInventory(revision); reviewRevision = null }) { Text("Finish inventory review") } },
        dismissButton = { TextButton(onClick = { reviewRevision = null }) { Text("Keep scanning") } }) }
}
