package nl.ako.hachat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.ako.hachat.data.mcp.McpState
import nl.ako.hachat.data.settings.ToolOverride
import nl.ako.hachat.domain.ToolAccess
import nl.ako.hachat.ui.components.AccessChip
import nl.ako.hachat.ui.theme.MonoStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadTools() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tool-classificatie") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Terug") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "patterns") { PatternEditor(state, vm) }
            item(key = "summary") {
                val reads = state.tools.count { it.classification.access == ToolAccess.READ }
                val text = when (val m = state.mcp) {
                    is McpState.Connected -> "${state.tools.size} tools: $reads lezen, ${state.tools.size - reads} schrijven. Schrijf-tools vragen altijd om bevestiging."
                    is McpState.Failed -> m.message
                    McpState.Connecting -> "Tools laden…"
                    else -> "Niet verbonden met de MCP-server."
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(state.tools, key = { it.tool.name }) { row -> ToolRowView(row, onOverride = { vm.setOverride(row.tool.name, it) }) }
        }
    }
}

@Composable
private fun PatternEditor(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    var write by rememberSaveable(s.writePatterns) { mutableStateOf(s.writePatterns.joinToString(", ")) }
    var read by rememberSaveable(s.readPatterns) { mutableStateOf(s.readPatterns.joinToString(", ")) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Volgorde: handmatige keuze → schrijf-patroon → server-hint readOnlyHint → lees-patroon → onbekend = schrijven. " +
                "Patronen matchen op woorddelen van de toolnaam (gescheiden door _ - .).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(write, { write = it }, label = { Text("Schrijf-patronen") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(read, { read = it }, label = { Text("Lees-patronen") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.savePatterns(write, read) }) { Text("Opslaan") }
            TextButton(onClick = vm::resetPatterns) { Text("Standaard herstellen") }
        }
    }
}

@Composable
private fun ToolRowView(row: ToolRow, onOverride: (ToolOverride) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.tool.name, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.tool.description?.let {
                    Text(it.lineSequence().first(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(row.classification.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(8.dp))
            Box {
                TextButton(onClick = { menu = true }) {
                    AccessChip(row.classification.access)
                    if (row.override != ToolOverride.AUTO) Text(" ✎", style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Automatisch") }, onClick = { onOverride(ToolOverride.AUTO); menu = false })
                    DropdownMenuItem(text = { Text("Altijd lezen") }, onClick = { onOverride(ToolOverride.READ); menu = false })
                    DropdownMenuItem(text = { Text("Altijd schrijven (bevestigen)") }, onClick = { onOverride(ToolOverride.WRITE); menu = false })
                }
            }
        }
    }
}
