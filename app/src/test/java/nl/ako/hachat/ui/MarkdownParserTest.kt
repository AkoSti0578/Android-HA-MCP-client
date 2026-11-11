package nl.ako.hachat.ui

import nl.ako.hachat.ui.markdown.Inline
import nl.ako.hachat.ui.markdown.MarkdownParser
import nl.ako.hachat.ui.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `blokken - koppen, lijsten, code, tabel, quote, regel`() {
        val md = """
            # Overzicht
            Er staan **3** lampen aan:

            - `light.keuken`
            - light.woonkamer
              met dimmer
            1. eerste
            2. tweede

            ```yaml
            alias: Test
            mode: single
            ```

            | Kamer | Temp |
            |:------|-----:|
            | Keuken | 21,5 |
            | Hal | 19 |

            > Let op
            ---
        """.trimIndent()
        val b = MarkdownParser.parse(md)
        assertEquals(MdBlock.Heading(1, "Overzicht"), b[0])
        assertEquals(MdBlock.Paragraph("Er staan **3** lampen aan:"), b[1])
        val list = b[2] as MdBlock.ListBlock
        assertEquals(4, list.items.size)
        assertEquals("light.woonkamer\nmet dimmer", list.items[1].text)
        assertTrue(list.items[2].ordered)
        assertEquals(MdBlock.Code("yaml", "alias: Test\nmode: single", closed = true), b[3])
        val table = b[4] as MdBlock.Table
        assertEquals(listOf("Kamer", "Temp"), table.header)
        assertEquals(listOf(MdBlock.Align.START, MdBlock.Align.END), table.alignments)
        assertEquals(listOf(listOf("Keuken", "21,5"), listOf("Hal", "19")), table.rows)
        assertEquals(MdBlock.Quote("Let op"), b[5])
        assertEquals(MdBlock.Rule, b[6])
    }

    @Test
    fun `onafgesloten codeblok tijdens streamen`() {
        val b = MarkdownParser.parse("Hier:\n```yaml\nalias: x")
        assertEquals(MdBlock.Code("yaml", "alias: x", closed = false), b[1])
    }

    @Test
    fun `inline opmaak en links`() {
        val i = MarkdownParser.parseInline("**vet** en *schuin* met `code` en [HA](https://www.home-assistant.io) ~~weg~~")
        assertEquals(Inline.Bold(listOf(Inline.Text("vet"))), i[0])
        assertEquals(Inline.Italic(listOf(Inline.Text("schuin"))), i[2])
        assertEquals(Inline.Code("code"), i[4])
        assertEquals(Inline.Link(listOf(Inline.Text("HA")), "https://www.home-assistant.io"), i[6])
        assertEquals(Inline.Strike(listOf(Inline.Text("weg"))), i[8])
    }

    @Test
    fun `underscores in entity_ids zijn geen cursief`() {
        val i = MarkdownParser.parseInline("Zet light_keuken_plafond en sensor_temp_hal")
        assertEquals(listOf(Inline.Text("Zet light_keuken_plafond en sensor_temp_hal")), i)
    }

    @Test
    fun `losse sterretjes en kale urls`() {
        assertEquals(listOf(Inline.Text("2 * 3 = 6")), MarkdownParser.parseInline("2 * 3 = 6"))
        val i = MarkdownParser.parseInline("zie https://example.com/x.")
        assertEquals(Inline.Link(listOf(Inline.Text("https://example.com/x")), "https://example.com/x"), i[1])
        assertEquals(Inline.Text("."), i[2])
    }
}
