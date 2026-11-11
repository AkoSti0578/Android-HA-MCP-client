package nl.ako.hachat.ui.markdown

/** Blok-structuur van een Markdown-document (subset die Claude in de praktijk gebruikt). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String?, val code: String, val closed: Boolean) : MdBlock
    data class ListItem(val ordered: Boolean, val marker: String, val depth: Int, val text: String)
    data class ListBlock(val items: List<ListItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val alignments: List<Align>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock

    enum class Align { START, CENTER, END }
}

/** Inline-opmaak, als platte spans (renderer zet ze om naar AnnotatedString). */
sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Code(val code: String) : Inline
    data class Link(val children: List<Inline>, val url: String) : Inline
}

object MarkdownParser {
    private val fence = Regex("^\\s{0,3}(```+|~~~+)\\s*([^`\\s]*)?.*$")
    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val listItem = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val quote = Regex("^\\s{0,3}>\\s?(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        fun flushPara() {
            if (para.isNotEmpty()) { out += MdBlock.Paragraph(para.joinToString("\n").trim()); para.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            if (f != null) {
                flushPara()
                val marker = f.groupValues[1]
                val lang = f.groupValues[2].ifBlank { null }
                val code = mutableListOf<String>()
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trim().startsWith(marker) && lines[i].trim().trim(marker[0]).isEmpty()) { closed = true; break }
                    code += lines[i]; i++
                }
                out += MdBlock.Code(lang, code.joinToString("\n"), closed)
                i++
                continue
            }
            if (line.isBlank()) { flushPara(); i++; continue }
            val h = heading.matchEntire(line)
            if (h != null) {
                flushPara(); out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2]); i++; continue
            }
            if (rule.matches(line) && para.isEmpty()) { out += MdBlock.Rule; i++; continue }
            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushPara()
                val header = cells(line)
                val aligns = cells(lines[i + 1]).map {
                    val l = it.startsWith(":"); val r = it.endsWith(":")
                    when { l && r -> MdBlock.Align.CENTER; r -> MdBlock.Align.END; else -> MdBlock.Align.START }
                }
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += cells(lines[i]).let { r -> List(header.size) { idx -> r.getOrElse(idx) { "" } } }
                    i++
                }
                out += MdBlock.Table(header, List(header.size) { aligns.getOrElse(it) { MdBlock.Align.START } }, rows)
                continue
            }
            if (quote.matches(line)) {
                flushPara()
                val q = mutableListOf<String>()
                while (i < lines.size) {
                    val m = quote.matchEntire(lines[i]) ?: break
                    q += m.groupValues[1]; i++
                }
                out += MdBlock.Quote(q.joinToString("\n"))
                continue
            }
            val li = listItem.matchEntire(line)
            if (li != null) {
                flushPara()
                val items = mutableListOf<MdBlock.ListItem>()
                while (i < lines.size) {
                    val m = listItem.matchEntire(lines[i])
                    if (m != null) {
                        val indent = m.groupValues[1].replace("\t", "    ").length
                        val marker = m.groupValues[2]
                        items += MdBlock.ListItem(marker.first().isDigit(), marker, indent / 2, m.groupValues[3])
                        i++
                    } else if (lines[i].isNotBlank() && lines[i].startsWith("  ") && items.isNotEmpty()) {
                        val last = items.removeAt(items.lastIndex)
                        items += last.copy(text = last.text + "\n" + lines[i].trim()); i++
                    } else if (lines[i].isBlank() && i + 1 < lines.size && listItem.matches(lines[i + 1])) {
                        i++ // losse lijst: lege regel tussen items
                    } else break
                }
                out += MdBlock.ListBlock(normalizeDepth(items))
                continue
            }
            para += line
            i++
        }
        flushPara()
        return out
    }

    /** Diepte relatief maken (eerste niveau = 0, sprongen max 1). */
    private fun normalizeDepth(items: List<MdBlock.ListItem>): List<MdBlock.ListItem> {
        val levels = items.map { it.depth }.distinct().sorted()
        return items.map { it.copy(depth = levels.indexOf(it.depth)) }
    }

    private fun cells(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.substring(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < r.length) {
            val c = r[k]
            if (c == '\\' && k + 1 < r.length && r[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (c == '|') { out += cur.toString().trim(); cur.clear() } else cur.append(c)
            k++
        }
        out += cur.toString().trim()
        return out
    }

    /** Inline-parser: `code`, **vet**, *cursief*, ~~doorgehaald~~, [link](url), autolinks. */
    fun parseInline(text: String): List<Inline> = InlineParser(text).parse()

    private class InlineParser(private val s: String) {
        private var pos = 0

        fun parse(until: String? = null): List<Inline> {
            val out = mutableListOf<Inline>()
            val buf = StringBuilder()
            fun flush() { if (buf.isNotEmpty()) { out += Inline.Text(buf.toString()); buf.clear() } }
            while (pos < s.length) {
                if (until != null && s.startsWith(until, pos)) return out.also { flush() }
                val c = s[pos]
                when {
                    c == '\\' && pos + 1 < s.length && s[pos + 1] in "\\`*_~[]()#+-.!|>" -> { buf.append(s[pos + 1]); pos += 2 }
                    c == '`' -> {
                        val ticks = s.substring(pos).takeWhile { it == '`' }
                        val end = s.indexOf(ticks, pos + ticks.length)
                        if (end == -1) { buf.append(ticks); pos += ticks.length } else {
                            flush(); out += Inline.Code(s.substring(pos + ticks.length, end).trim()); pos = end + ticks.length
                        }
                    }
                    s.startsWith("**", pos) || s.startsWith("__", pos) -> {
                        val d = s.substring(pos, pos + 2)
                        if (closes(d, pos + 2)) {
                            flush(); pos += 2; val inner = parse(d); pos += 2; out += Inline.Bold(inner)
                        } else { buf.append(d); pos += 2 }
                    }
                    s.startsWith("~~", pos) -> {
                        if (closes("~~", pos + 2)) {
                            flush(); pos += 2; val inner = parse("~~"); pos += 2; out += Inline.Strike(inner)
                        } else { buf.append("~~"); pos += 2 }
                    }
                    (c == '*' || c == '_') && canOpenEmphasis(c) -> {
                        val d = c.toString()
                        if (closes(d, pos + 1)) {
                            flush(); pos += 1; val inner = parse(d); pos += 1; out += Inline.Italic(inner)
                        } else { buf.append(c); pos++ }
                    }
                    c == '[' -> {
                        val close = findMatching(pos)
                        if (close != -1 && close + 1 < s.length && s[close + 1] == '(') {
                            val urlEnd = s.indexOf(')', close + 2)
                            if (urlEnd != -1) {
                                flush()
                                val label = InlineParser(s.substring(pos + 1, close)).parse()
                                out += Inline.Link(label, s.substring(close + 2, urlEnd).trim())
                                pos = urlEnd + 1
                            } else { buf.append(c); pos++ }
                        } else { buf.append(c); pos++ }
                    }
                    s.startsWith("https://", pos) || s.startsWith("http://", pos) -> {
                        val end = s.indexOfFirst(pos) { it.isWhitespace() || it in "<>\"" }.let { if (it == -1) s.length else it }
                        val url = s.substring(pos, end).trimEnd('.', ',', ')', ';', ':', '!', '?')
                        flush(); out += Inline.Link(listOf(Inline.Text(url)), url); pos += url.length
                    }
                    else -> { buf.append(c); pos++ }
                }
            }
            flush()
            return out
        }

        private fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
            for (k in from until length) if (pred(this[k])) return k
            return -1
        }

        /** `_` binnen een woord (entity_id's!) is geen opmaak. */
        private fun canOpenEmphasis(c: Char): Boolean {
            val next = s.getOrNull(pos + 1) ?: return false
            if (next.isWhitespace()) return false
            if (c == '_') {
                val prev = s.getOrNull(pos - 1)
                if (prev != null && prev.isLetterOrDigit()) return false
            }
            return true
        }

        private fun closes(delim: String, from: Int): Boolean {
            var k = from
            while (true) {
                val idx = s.indexOf(delim, k)
                if (idx == -1 || idx == from) return false // niet gesloten of lege opmaak
                val before = s[idx - 1]
                val after = s.getOrNull(idx + delim.length)
                val validClose = !before.isWhitespace() &&
                    !(delim == "_" && after != null && after.isLetterOrDigit()) &&
                    !(delim.length == 1 && after == delim[0])
                if (validClose) return true
                k = idx + delim.length
            }
        }

        private fun findMatching(open: Int): Int {
            var depth = 0
            for (k in open until s.length) {
                when (s[k]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return k }
                }
            }
            return -1
        }
    }
}
