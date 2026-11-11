package nl.ako.hachat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import nl.ako.hachat.ui.components.CodeBlock

/** Rendert Markdown met Material 3-typografie; codeblokken krijgen een kopieerknop. */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { MdBlockView(it) }
    }
}

@Composable
private fun MdBlockView(block: MdBlock) {
    val typo = MaterialTheme.typography
    when (block) {
        is MdBlock.Heading -> Text(
            inline(block.text),
            style = when (block.level) {
                1 -> typo.titleLarge
                2 -> typo.titleMedium
                else -> typo.titleSmall
            }.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 4.dp),
        )
        is MdBlock.Paragraph -> Text(inline(block.text), style = typo.bodyLarge)
        is MdBlock.Code -> CodeBlock(code = block.code, language = block.language)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)))
            Column(Modifier.padding(start = 10.dp)) {
                MarkdownView(block.text)
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var counter = 0
            block.items.forEach { item ->
                counter = if (item.ordered && item.depth == 0) counter + 1 else counter
                Row(Modifier.padding(start = (item.depth * 16).dp)) {
                    val bullet = if (item.ordered) item.marker.trimEnd('.', ')') + "." else if (item.depth == 0) "•" else "◦"
                    Text(bullet, style = typo.bodyLarge, modifier = Modifier.widthIn(min = 20.dp))
                    Text(inline(item.text), style = typo.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
        is MdBlock.Table -> TableView(block)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun TableView(table: MdBlock.Table) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            TableRow(table.header, table.alignments, header = true)
            table.rows.forEach { row ->
                HorizontalDivider(color = border)
                TableRow(row, table.alignments, header = false)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, aligns: List<MdBlock.Align>, header: Boolean) {
    val bg = if (header) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Row(Modifier.background(bg)) {
        cells.forEachIndexed { i, c ->
            Text(
                inline(c),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal),
                textAlign = when (aligns.getOrNull(i)) {
                    MdBlock.Align.CENTER -> TextAlign.Center
                    MdBlock.Align.END -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = Modifier.widthIn(min = 72.dp, max = 260.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun inline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val link = MaterialTheme.colorScheme.primary
    val content = LocalContentColor.current
    return remember(text, codeBg, link, content) {
        buildAnnotatedString { appendInlines(MarkdownParser.parseInline(text), codeBg, link) }
    }
}

private fun AnnotatedString.Builder.appendInlines(items: List<Inline>, codeBg: Color, link: Color) {
    for (it in items) when (it) {
        is Inline.Text -> append(it.text)
        is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(it.children, codeBg, link) }
        is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(it.children, codeBg, link) }
        is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(it.children, codeBg, link) }
        is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" ${it.code} ") }
        is Inline.Link -> withLink(
            LinkAnnotation.Url(it.url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))),
        ) { appendInlines(it.children, codeBg, link) }
    }
}

