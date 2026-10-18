package com.arkhamscan

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RemoteControls(vm: ScanViewModel) {
    var port by remember { mutableStateOf("8080") }
    val enabled by vm.remote.enabled.collectAsState()
    val address by vm.remote.address.collectAsState()
    val status by vm.remote.cameraStatus.collectAsState()
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("Run web server", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(enabled, {
                    if (it) {
                        val number = port.toIntOrNull()
                        if (number != null && number in 1024..65535) vm.remote.startServer(number)
                        else vm.message.value = "Choose a web server port from 1024 to 65535."
                    } else vm.remote.stopServer()
                })
            }
            if (!enabled) OutlinedTextField(port, { port = it.take(5) }, label = { Text("Local HTTP port") }, singleLine = true)
            Text("Control this mounted phone from a browser on your local network. Keep this app open and unlocked. Grant camera access once in Scan.")
            if (enabled) {
                SelectionContainer { Text(address) }
                Text("Open the address in a browser · $status")
                Text("Local HTTP · metadata only · no camera frames leave this phone", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
