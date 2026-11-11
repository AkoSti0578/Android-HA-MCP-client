package nl.ako.hachat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import nl.ako.hachat.core.PrettyJson
import nl.ako.hachat.core.YamlWriter
import nl.ako.hachat.domain.Notice
import nl.ako.hachat.domain.ToolAccess
import nl.ako.hachat.domain.ToolStatus
import nl.ako.hachat.domain.Usage
import nl.ako.hachat.ui.chat.ChatItem
import nl.ako.hachat.ui.theme.LocalExtraColors
import nl.ako.hachat.ui.theme.MonoStyle
import java.text.NumberFormat
import java.util.Locale

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            modifier = Modifier.padding(start = 48.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
fun ThinkingCard(item: ChatItem.Thinking) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(6.dp))
            Text(
                if (item.streaming) "Denkt na…" else "Denkproces",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
        if (open) Text(
            item.text,
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp, top = 4.dp),
        )
    }
}

@Composable
fun AccessChip(access: ToolAccess?) {
    if (access == null) return
    val extra = LocalExtraColors.current
    val (label, color) = when (access) {
        ToolAccess.READ -> "lezen" to extra.read
        ToolAccess.WRITE -> "schrijven" to extra.write
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** Is dit een automation/script-config? Dan standaard als YAML tonen. */
fun prefersYaml(toolName: String): Boolean =
    Regex("automation|script|blueprint|scene|dashboard", RegexOption.IGNORE_CASE).containsMatchIn(toolName)

private fun format(input: JsonElement?, yaml: Boolean): String = when {
    input == null -> ""
    yaml -> YamlWriter.write(input).trimEnd()
    else -> PrettyJson.encodeToString(JsonElement.serializer(), input)
}

/** Inklapbare kaart voor een (afgeronde of lopende) tool-call. */
@Composable
fun ToolCallCard(item: ChatItem.ToolCallItem) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val extra = LocalExtraColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (item.status) {
                ToolStatus.RUNNING, null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                ToolStatus.AWAITING_APPROVAL -> Icon(Icons.Outlined.PanTool, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                ToolStatus.DONE -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = extra.success)
                ToolStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                ToolStatus.REJECTED -> Icon(Icons.Outlined.Block, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                item.name,
                style = MonoStyle,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            AccessChip(item.access)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Argumenten", style = MaterialTheme.typography.labelMedium)
                val args = item.input?.let { format(it, prefersYaml(item.name)) } ?: item.partialInput.orEmpty()
                CodeBlock(args.ifBlank { "{}" }, if (item.input != null && prefersYaml(item.name)) "yaml" else "json")
                when {
                    item.status == ToolStatus.REJECTED -> Text("Geweigerd — niet uitgevoerd.", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                    item.result != null -> {
                        Text(if (item.isError) "Fout" else "Resultaat", style = MaterialTheme.typography.labelMedium)
                        ResultText(item.result, item.isError)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultText(result: String, isError: Boolean) {
    var full by remember { mutableStateOf(false) }
    val limit = 1500
    val shown = if (full || result.length <= limit) result else result.take(limit) + "…"
    val pretty = remember(shown) { prettyIfJson(shown) }
    Text(
        pretty,
        style = MonoStyle,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (result.length > limit) {
        Text(
            if (full) "Minder tonen" else "Alles tonen (${result.length} tekens)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { full = !full }.padding(vertical = 4.dp),
        )
    }
}

private fun prettyIfJson(s: String): String {
    val t = s.trim()
    if (!(t.startsWith("{") || t.startsWith("["))) return s
    return runCatching { PrettyJson.encodeToString(JsonElement.serializer(), PrettyJson.parseToJsonElement(t)) }.getOrDefault(s)
}

/** Bevestigingskaart voor een schrijf-tool. */
@Composable
fun ApprovalCard(toolName: String, input: JsonObject, onApprove: () -> Unit, onReject: () -> Unit) {
    var yaml by rememberSaveable(toolName) { mutableStateOf(prefersYaml(toolName)) }
    val warn = LocalExtraColors.current.write
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.5.dp, warn.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, tint = warn)
                Spacer(Modifier.width(8.dp))
                Text("Bevestiging nodig", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text("Claude wil een wijziging uitvoeren in Home Assistant:", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Build, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(toolName, style = MonoStyle, fontWeight = FontWeight.SemiBold)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = yaml, onClick = { yaml = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("YAML") }
                SegmentedButton(selected = !yaml, onClick = { yaml = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("JSON") }
            }
            CodeBlock(format(input, yaml), if (yaml) "yaml" else "json")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Weigeren") }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("Uitvoeren") }
            }
        }
    }
}

@Composable
fun NoticeRow(kind: Notice.Kind, text: String, onRetry: (() -> Unit)? = null) {
    val (icon, color) = when (kind) {
        Notice.Kind.INFO -> Icons.Outlined.Info to MaterialTheme.colorScheme.outline
        Notice.Kind.WARNING -> Icons.Outlined.Warning to MaterialTheme.colorScheme.tertiary
        Notice.Kind.ERROR -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        Notice.Kind.REFUSAL -> Icons.Outlined.Block to MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onRetry != null) {
                Text(
                    "Opnieuw",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(onClick = onRetry).padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
fun FallbackRow(from: String?, to: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.CallSplit, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(6.dp))
        Text(
            "Overgeschakeld van ${from ?: "?"} naar ${to ?: "?"} (weigering)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale("nl", "NL"))

@Composable
fun UsageRow(usage: Usage, steps: Int, model: String?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        val c = MaterialTheme.colorScheme.outline
        Text("↑ ${nf.format(usage.inputTokens)} in", style = style, color = c)
        Text("↓ ${nf.format(usage.outputTokens)} uit", style = style, color = c)
        if (usage.cacheReadTokens > 0) Text("⚡ ${nf.format(usage.cacheReadTokens)} cache", style = style, color = LocalExtraColors.current.success)
        if (usage.cacheWriteTokens > 0) Text("✎ ${nf.format(usage.cacheWriteTokens)} cache-write", style = style, color = c)
        if (steps > 1) Text("$steps stappen", style = style, color = c)
        model?.let { Text(it, style = style, color = c) }
    }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, onSuggestion: (String) -> Unit) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Lightbulb, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Wat wil je weten of regelen in de Gieterij?", style = MaterialTheme.typography.titleMedium)
        val suggestions = listOf(
            "Welke lampen staan er nu aan?",
            "Wat is de temperatuur in alle kamers?",
            "Maak een automation die om 23:00 alle lichten uitzet",
            "Welke automations zijn er en wanneer liepen ze voor het laatst?",
        )
        suggestions.forEach { s ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().clickable { onSuggestion(s) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Visibility, null, Modifier.size(16.dp), tint = Color.Unspecified.takeOrElse(MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(8.dp))
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun Color.takeOrElse(other: Color) = if (this == Color.Unspecified) other else this
