package nl.ako.hachat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.ako.hachat.data.settings.DEFAULT_SYSTEM_PROMPT
import nl.ako.hachat.data.settings.EFFORT_LEVELS
import nl.ako.hachat.ui.theme.LocalExtraColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenTools: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instellingen") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Terug") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Claude")
            SecretField(
                label = "Claude API key",
                savedHint = state.hints.apiKey,
                placeholder = "sk-ant-…",
                onSave = vm::saveApiKey,
                onClear = vm::clearApiKey,
            )
            ModelPicker(state, vm)
            NumberField("max_tokens", s.maxTokens) { v -> vm.update { it.copy(maxTokens = v) } }
            EffortPicker(state, vm)
            ToggleRow("Denkproces tonen", "Samenvatting van Claude's redenering (adaptive thinking) inklapbaar tonen.", s.showThinking) { v ->
                vm.update { it.copy(showThinking = v) }
            }
            ToggleRow(
                "Fallback bij weigering",
                "Als een veiligheidsfilter een verzoek ten onrechte weigert, probeert de API het automatisch op een ander model (server-side fallbacks).",
                s.refusalFallback,
            ) { v -> vm.update { it.copy(refusalFallback = v) } }
            ToggleRow("Streaming", "Antwoorden verschijnen live terwijl Claude schrijft.", s.streaming) { v -> vm.update { it.copy(streaming = v) } }

            HorizontalDivider()
            Section("Home Assistant (MCP)")
            McpSection(state, vm)

            HorizontalDivider()
            Section("Veiligheid")
            ToggleRow("Lees-modus", "Schrijf-tools worden niet naar Claude gestuurd: alleen opvragen, niets wijzigen.", s.readOnlyMode) { v ->
                vm.update { it.copy(readOnlyMode = v) }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenTools).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Tool-classificatie", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Welke tools lezen en welke schrijven (en dus bevestiging vragen).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
            }

            HorizontalDivider()
            Section("Weergave")
            ToggleRow("Tool-calls tonen", "Toon elke tool-aanroep inklapbaar in de chat.", s.showToolCalls) { v -> vm.update { it.copy(showToolCalls = v) } }

            HorizontalDivider()
            Section("System prompt")
            SystemPromptEditor(s.systemPrompt, onSave = { p -> vm.update { it.copy(systemPrompt = p) } }, onReset = vm::resetSystemPrompt)
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}

/** Geheim veld: na opslaan alleen een hint, nooit de waarde zelf. */
@Composable
private fun SecretField(
    label: String,
    savedHint: String?,
    placeholder: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    keyboard: KeyboardType = KeyboardType.Password,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    if (savedHint != null && !editing) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.CheckCircle, null, tint = LocalExtraColors.current.success, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text("Opgeslagen (versleuteld): $savedHint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { editing = true; value = "" }) { Text("Vervangen") }
            TextButton(onClick = onClear) { Text("Wissen") }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Verbergen" else "Tonen")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(value); value = ""; visible = false; editing = false }, enabled = value.isNotBlank()) { Text("Opslaan") }
                if (savedHint != null) TextButton(onClick = { editing = false; value = "" }) { Text("Annuleren") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    var open by remember { mutableStateOf(false) }
    val current = s.models.firstOrNull { it.id == s.model }
    LaunchedEffect(state.hints.apiKey != null) {
        if (state.hints.apiKey != null && s.models.isEmpty()) vm.refreshModels()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = current?.displayName?.let { "$it  ·  ${s.model}" } ?: s.model,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (s.models.isEmpty()) {
                    DropdownMenuItem(text = { Text("Nog geen modellen — tik op Verversen") }, onClick = { open = false })
                }
                s.models.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(m.displayName)
                                Text(m.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        },
                        onClick = { vm.update { it.copy(model = m.id) }; open = false },
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { vm.refreshModels() }, enabled = state.models != TaskState.Running) {
            if (state.models == TaskState.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Refresh, "Verversen")
        }
    }
    TaskResult(state.models)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortPicker(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    val info = s.models.firstOrNull { it.id == s.model }
    val levels = info?.effortLevels?.takeIf { it.isNotEmpty() } ?: EFFORT_LEVELS
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = s.effort ?: "standaard (model bepaalt)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Effort") },
            supportingText = {
                if (info != null && !info.supportsEffort) Text("Dit model ondersteunt geen effort; wordt niet meegestuurd.")
                else Text("Lager = sneller en goedkoper, hoger = grondiger.")
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("standaard (model bepaalt)") }, onClick = { vm.update { it.copy(effort = null) }; open = false })
            levels.forEach { l -> DropdownMenuItem(text = { Text(l) }, onClick = { vm.update { it.copy(effort = l) }; open = false }) }
        }
    }
}

@Composable
private fun McpSection(state: SettingsUiState, vm: SettingsViewModel) {
    var url by rememberSaveable { mutableStateOf("") }
    var headerName by rememberSaveable(state.settings.mcpHeaderName) { mutableStateOf(state.settings.mcpHeaderName) }
    var headerValue by remember { mutableStateOf("") }
    var showUrl by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(state.settings.mcpHeaderName.isNotEmpty()) }

    state.hints.mcpUrl?.let {
        Text("Opgeslagen (versleuteld): $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(if (state.hints.mcpUrl == null) "MCP-server URL" else "Nieuwe MCP-server URL (leeg = ongewijzigd)") },
        placeholder = { Text("http://192.168.1.10:9583/private_…") },
        singleLine = true,
        visualTransformation = if (showUrl) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showUrl = !showUrl }) {
                Icon(if (showUrl) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
            }
        },
        supportingText = { Text("Bevat een geheim pad, daarom versleuteld opgeslagen. http:// alleen binnen je eigen netwerk/VPN.") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        if (showAdvanced) "▾ Extra HTTP-header" else "▸ Extra HTTP-header (optioneel, bv. Authorization)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
    )
    if (showAdvanced) {
        OutlinedTextField(
            value = headerName, onValueChange = { headerName = it },
            label = { Text("Headernaam") }, placeholder = { Text("Authorization") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = headerValue, onValueChange = { headerValue = it },
            label = { Text(if (state.hints.mcpHeader == null) "Headerwaarde" else "Nieuwe headerwaarde (leeg = ongewijzigd)") },
            placeholder = { Text("Bearer …") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { state.hints.mcpHeader?.let { Text("Opgeslagen: $it") } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.hints.mcpHeader != null) TextButton(onClick = { vm.clearMcpHeader(); headerName = "" }) { Text("Header verwijderen") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.testConnection(url, headerName, headerValue) }, enabled = state.test != TaskState.Running) {
            if (state.test == TaskState.Running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
            }
            Text("Test verbinding")
        }
        Button(
            onClick = {
                vm.saveMcp(url.ifBlank { null }, headerName, headerValue.ifBlank { null })
                url = ""; headerValue = ""
            },
            enabled = url.isNotBlank() || headerValue.isNotBlank() || headerName != state.settings.mcpHeaderName,
        ) { Text("Opslaan") }
    }
    TaskResult(state.test)
}

@Composable
private fun TaskResult(t: TaskState) {
    when (t) {
        is TaskState.Ok -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = LocalExtraColors.current.success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(t.message, style = MaterialTheme.typography.bodySmall)
        }
        is TaskState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(t.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        else -> Unit
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.takeIf { it in 256..128_000 }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.toIntOrNull()?.let { it !in 256..128_000 } ?: true,
        supportingText = { Text("256 – 128.000. Let op: bij adaptive thinking telt het denken mee.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SystemPromptEditor(prompt: String, onSave: (String) -> Unit, onReset: () -> Unit) {
    var text by rememberSaveable(prompt) { mutableStateOf(prompt) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        minLines = 4,
        maxLines = 12,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(text) }, enabled = text != prompt && text.isNotBlank()) { Text("Opslaan") }
        TextButton(onClick = onReset, enabled = prompt != DEFAULT_SYSTEM_PROMPT) { Text("Standaard herstellen") }
    }
}
