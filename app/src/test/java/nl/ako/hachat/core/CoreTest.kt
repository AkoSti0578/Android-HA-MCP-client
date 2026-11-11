package nl.ako.hachat.core

import kotlinx.serialization.json.jsonObject
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CleartextPolicyTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `prive IPv4 en IPv6 ranges zijn toegestaan`() {
        listOf("10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.10", "100.64.0.1", "100.127.255.1", "127.0.0.1", "169.254.1.1")
            .forEach { assertTrue(it, CleartextPolicy.isCleartextAllowed(it)) }
        listOf("fd7a:115c:a1e0::1", "fe80::1", "::1").forEach { assertTrue(it, CleartextPolicy.isCleartextAllowed("[$it]")) }
    }

    @Test
    fun `publieke adressen zijn geweigerd`() {
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "1.1.1.1", "100.63.255.255").forEach {
            assertFalse(it, CleartextPolicy.isCleartextAllowed(it))
        }
        assertFalse(CleartextPolicy.isCleartextAllowed("[2001:4860:4860::8888]"))
    }

    @Test
    fun `lokale hostnamen ja, hostnamen naar internet nee`() {
        assertTrue(CleartextPolicy.isCleartextAllowed("homeassistant.local") { error("geen DNS nodig") })
        assertTrue(CleartextPolicy.isCleartextAllowed("ha.home.arpa") { error("geen DNS nodig") })
        assertTrue(CleartextPolicy.isCleartextAllowed("nas") { listOf(ip("192.168.1.20")) })
        assertFalse(CleartextPolicy.isCleartextAllowed("example.com") { listOf(ip("93.184.216.34")) })
        // gemengde resolutie (DNS-rebinding-achtig): weigeren
        assertFalse(CleartextPolicy.isCleartextAllowed("tricky") { listOf(ip("192.168.1.2"), ip("8.8.8.8")) })
        assertFalse(CleartextPolicy.isCleartextAllowed("unknown") { throw java.net.UnknownHostException() })
    }
}

class RedactionTest {
    @Test
    fun `MCP-URL wordt zonder geheim pad getoond`() {
        assertEquals("http://192.168.1.5:9583/…", Redaction.url("http://192.168.1.5:9583/private_abc123"))
        assertEquals("(ongeldige URL)", Redaction.url("geen url"))
        assertEquals("http://192.168.1.10:8123/api/mcp", Redaction.url("http://192.168.1.10:8123/api/mcp"))
    }

    @Test
    fun `geheimen worden uit teksten gefilterd`() {
        val url = "http://192.168.1.5:9583/private_abc123"
        val out = Redaction.scrub("fout bij $url met key sk-ant-xyz123", url, "sk-ant-xyz123")
        assertFalse(out.contains("private_abc123"))
        assertFalse(out.contains("sk-ant-xyz123"))
    }
}

class SseReaderTest {
    @Test
    fun `parseert events, meerregelige data en commentaar`() {
        val src = Buffer().writeUtf8(": keepalive\n\nevent: a\ndata: {\"x\":1}\n\ndata: regel1\ndata: regel2\n\nevent: b\ndata:zonder-spatie\n")
        val r = SseReader(src)
        assertEquals(SseEvent("a", "{\"x\":1}"), r.next())
        assertEquals(SseEvent(null, "regel1\nregel2"), r.next())
        assertEquals(SseEvent("b", "zonder-spatie"), r.next()) // laatste event zonder lege regel
        assertNull(r.next())
    }
}

class YamlWriterTest {
    @Test
    fun `automation-config als leesbare YAML`() {
        val json = AppJson.parseToJsonElement(
            """{"alias":"Lichten uit om 23:00","trigger":[{"platform":"time","at":"23:00:00"}],
               "condition":[],"action":[{"service":"light.turn_off","target":{"entity_id":["light.keuken","light.woonkamer"]}}],
               "mode":"single","enabled":true,"description":"Regel 1\nRegel 2"}""",
        )
        val expected = """
            alias: Lichten uit om 23:00
            trigger:
              - platform: time
                at: "23:00:00"
            condition: []
            action:
              - service: light.turn_off
                target:
                  entity_id:
                    - light.keuken
                    - light.woonkamer
            mode: single
            enabled: true
            description: |-
              Regel 1
              Regel 2
        """.trimIndent() + "\n"
        assertEquals(expected, YamlWriter.write(json))
    }

    @Test
    fun `strings die op YAML-types lijken worden gequote`() {
        val y = YamlWriter.write(AppJson.parseToJsonElement("""{"a":"on","b":"123","c":"","d":"x: y","e":"- z"}"""))
        assertEquals("a: \"on\"\nb: \"123\"\nc: \"\"\nd: \"x: y\"\ne: \"- z\"\n", y)
    }

    @Test
    fun `geneste lijsten`() {
        val y = YamlWriter.write(AppJson.parseToJsonElement("""[[1,2],{"a":[]}]"""))
        assertEquals("- - 1\n  - 2\n- a: []\n", y)
    }
}

class JsonExtTest {
    @Test
    fun `jsonOf laat nulls weg`() {
        val o = jsonOf("a" to 1, "b" to null, "c" to listOf("x"))
        assertEquals(setOf("a", "c"), o.keys)
        assertEquals("""{"a":1,"c":["x"]}""", o.toString())
        assertEquals(o, AppJson.parseToJsonElement(o.toString()).jsonObject)
    }
}
