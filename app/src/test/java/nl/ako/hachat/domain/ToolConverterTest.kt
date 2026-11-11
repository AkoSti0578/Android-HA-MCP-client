package nl.ako.hachat.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.str
import nl.ako.hachat.data.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolConverterTest {
    private fun schema(json: String) = AppJson.parseToJsonElement(json).jsonObject

    @Test
    fun `converteert naam, beschrijving en input_schema`() {
        val tool = McpTool(
            name = "ha_get_state",
            description = "Get the state of an entity",
            inputSchema = schema("""{"type":"object","properties":{"entity_id":{"type":"string"}},"required":["entity_id"]}"""),
        )
        val out = ToolConverter.convert(listOf(tool))
        val def = out.definitions.single()
        assertEquals("ha_get_state", def.str("name"))
        assertEquals("Get the state of an entity", def.str("description"))
        assertEquals(tool.inputSchema, def["input_schema"])
        assertEquals(mapOf("ha_get_state" to "ha_get_state"), out.nameMap)
        assertFalse("eager_input_streaming alleen bij streaming", "eager_input_streaming" in def)
    }

    @Test
    fun `saneert ongeldige namen en houdt ze uniek met mapping terug`() {
        val tools = listOf(
            McpTool("home.lights/on", null, "a", JsonObject(emptyMap())),
            McpTool("home_lights_on", null, "b", JsonObject(emptyMap())),
            McpTool("x".repeat(80), null, "c", JsonObject(emptyMap())),
        )
        val out = ToolConverter.convert(tools)
        val names = out.definitions.map { it.str("name")!! }
        names.forEach { assertTrue(it, it.matches(Regex("^[a-zA-Z0-9_-]{1,64}$"))) }
        assertEquals(names.size, names.toSet().size)
        assertEquals(setOf("home.lights/on", "home_lights_on", "x".repeat(80)), out.nameMap.values.toSet())
        assertEquals("home.lights/on", out.nameMap.entries.first { it.value == "home.lights/on" }.value)
    }

    @Test
    fun `schema zonder type krijgt type object en lege properties`() {
        val out = ToolConverter.convert(listOf(McpTool("t", null, null, JsonObject(emptyMap()))))
        val s = out.definitions.single()["input_schema"]!!.jsonObject
        assertEquals("object", s.str("type"))
        assertTrue("properties" in s)
        // zonder beschrijving valt de naam terug
        assertEquals("t", out.definitions.single().str("description"))
    }

    @Test
    fun `sorteert tools op naam voor een stabiele prompt-cache`() {
        val tools = listOf("zeta", "alpha", "mid").map { McpTool(it, null, it, JsonObject(emptyMap())) }
        assertEquals(listOf("alpha", "mid", "zeta"), ToolConverter.convert(tools).definitions.map { it.str("name") })
        assertEquals(ToolConverter.convert(tools), ToolConverter.convert(tools.reversed()))
    }

    @Test
    fun `title wordt aan de beschrijving toegevoegd en eager streaming optioneel`() {
        val t = McpTool("ha_x", title = "Lampen bedienen", description = "Zet lampen aan of uit", inputSchema = JsonObject(emptyMap()))
        val def = ToolConverter.convert(listOf(t), eagerInputStreaming = true).definitions.single()
        assertEquals("Lampen bedienen\nZet lampen aan of uit", def.str("description"))
        assertEquals(JsonPrimitive(true), def["eager_input_streaming"])
    }
}
