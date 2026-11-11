package nl.ako.hachat.domain

import kotlinx.serialization.json.JsonObject
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.data.mcp.McpTool
import nl.ako.hachat.data.settings.ToolOverride
import nl.ako.hachat.data.settings.ToolPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolClassifierTest {
    private val classifier = ToolClassifier(ToolPatterns.DEFAULT_WRITE, ToolPatterns.DEFAULT_READ)
    private fun tool(name: String, annotations: JsonObject? = null) = McpTool(name, null, null, JsonObject(emptyMap()), annotations)
    private fun access(name: String, c: ToolClassifier = classifier) = c.classify(tool(name)).access

    @Test
    fun `typische ha-mcp lees-tools zijn lezen`() {
        listOf(
            "ha_get_state", "ha_search_entities", "ha_get_history", "ha_eval_template", "ha_get_overview",
            "ha_config_get_automation", "ha_list_areas", "ha_get_logbook", "ha_deep_search", "ha_get_statistics",
        ).forEach { assertEquals(it, ToolAccess.READ, access(it)) }
    }

    @Test
    fun `typische ha-mcp schrijf-tools zijn schrijven`() {
        listOf(
            "ha_call_service", "ha_config_set_automation", "ha_config_remove_automation", "ha_bulk_control",
            "ha_restart", "ha_delete_entity", "ha_backup_create", "ha_config_set_script", "ha_set_entity",
        ).forEach { assertEquals(it, ToolAccess.WRITE, access(it)) }
    }

    @Test
    fun `onbekende tools zijn uit voorzorg schrijven`() {
        assertEquals(ToolAccess.WRITE, access("ha_frobnicate"))
    }

    @Test
    fun `patronen matchen alleen op woordgrenzen`() {
        assertTrue(ToolClassifier.matches("ha_config_set_automation", "set"))
        assertFalse(ToolClassifier.matches("ha_reset_counter", "set"))
        assertFalse(ToolClassifier.matches("asset_list", "set"))
        assertTrue(ToolClassifier.matches("ha_call_service", "call_service"))
        assertTrue(ToolClassifier.matches("ha.get-state", "get"))
    }

    @Test
    fun `schrijf-patroon wint van lees-patroon in dezelfde naam`() {
        // "get" en "set" komen beide voor: veilig = schrijven
        assertEquals(ToolAccess.WRITE, access("ha_get_and_set_value"))
    }

    @Test
    fun `readOnlyHint maakt onbekende tool lezen maar overstemt geen schrijf-patroon`() {
        val ro = jsonOf("readOnlyHint" to true)
        assertEquals(ToolAccess.READ, classifier.classify(tool("ha_frobnicate", ro)).access)
        assertEquals(ToolAccess.WRITE, classifier.classify(tool("ha_delete_all", ro)).access)
    }

    @Test
    fun `handmatige override wint altijd`() {
        val c = ToolClassifier(
            ToolPatterns.DEFAULT_WRITE, ToolPatterns.DEFAULT_READ,
            mapOf("ha_call_service" to ToolOverride.READ, "ha_get_state" to ToolOverride.WRITE),
        )
        assertEquals(ToolAccess.READ, access("ha_call_service", c))
        assertEquals(ToolAccess.WRITE, access("ha_get_state", c))
    }

    @Test
    fun `configureerbare patronen worden geparsed`() {
        assertEquals(listOf("set", "call_service", "foo"), ToolPatterns.parse(" set, call_service\nFOO ,, set"))
        val c = ToolClassifier(listOf("foo"), listOf("bar"))
        assertEquals(ToolAccess.WRITE, access("x_foo", c))
        assertEquals(ToolAccess.READ, access("x_bar", c))
        assertEquals(ToolAccess.WRITE, access("x_get", c)) // niet in eigen lijst → onbekend
    }
}
