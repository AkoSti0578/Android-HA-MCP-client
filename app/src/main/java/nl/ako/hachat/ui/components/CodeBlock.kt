package nl.ako.hachat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.ako.hachat.ui.theme.LocalExtraColors
import nl.ako.hachat.ui.theme.MonoStyle

/** Codeblok met taal-label, eenvoudige YAML/JSON-kleuring en kopieerknop. */
@Composable
fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val bg = LocalExtraColors.current.codeBackground
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f)).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (language ?: "code").lowercase(),
                style = MonoStyle.copy(color = Color(0xFFBFB2AA)),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) {
                Icon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = if (copied) "Gekopieerd" else "Kopiëren",
                    tint = if (copied) Color(0xFF81C995) else Color(0xFFBFB2AA),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            highlight(code, language),
            style = MonoStyle.copy(color = Color(0xFFEDE0D8)),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}

private val keyColor = Color(0xFFFFB68A)
private val stringColor = Color(0xFFA8D5A2)
private val numberColor = Color(0xFFFFC56B)
private val commentColor = Color(0xFF8C7F77)

/** Lichte syntax-kleuring voor YAML en JSON (regel-gebaseerd, geen volledige lexer). */
fun highlight(code: String, language: String?): AnnotatedString {
    val lang = language?.lowercase()
    if (lang !in setOf("yaml", "yml", "json", null)) return AnnotatedString(code)
    val yamlKey = Regex("^(\\s*-?\\s*)([\\w.\\-\"']+)(:)(\\s|$)")
    val jsonKey = Regex("\"([^\"]+)\"(\\s*:)")
    return buildAnnotatedString {
        code.lines().forEachIndexed { idx, line ->
            if (idx > 0) append("\n")
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#") -> withStyle(SpanStyle(color = commentColor)) { append(line) }
                lang == "json" || (lang == null && trimmed.startsWith("\"")) -> {
                    var last = 0
                    jsonKey.findAll(line).forEach { m ->
                        appendValue(line.substring(last, m.range.first))
                        withStyle(SpanStyle(color = keyColor)) { append(m.groupValues[0].removeSuffix(m.groupValues[2])) }
                        append(m.groupValues[2])
                        last = m.range.last + 1
                    }
                    appendValue(line.substring(last))
                }
                else -> {
                    val m = yamlKey.find(line)
                    if (m != null) {
                        append(m.groupValues[1])
                        withStyle(SpanStyle(color = keyColor)) { append(m.groupValues[2]) }
                        append(m.groupValues[3])
                        appendValue(line.substring(m.range.last + 1 - m.groupValues[4].length))
                    } else appendValue(line)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendValue(v: String) {
    val t = v.trim().trimEnd(',')
    when {
        t.startsWith("\"") || t.startsWith("'") -> withStyle(SpanStyle(color = stringColor)) { append(v) }
        t.toDoubleOrNull() != null || t in setOf("true", "false", "null") -> withStyle(SpanStyle(color = numberColor)) { append(v) }
        else -> append(v)
    }
}
