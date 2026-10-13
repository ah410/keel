package com.arceusvault.keel.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSchemaTest {
    private val schema = Json.parseToJsonElement(
        """
        {"groups":[{"name":"Network","description":"Ports"}],
         "questions":[
          {"variable":"TZ","label":"Timezone","group":"Network","schema":{"type":"string","required":true,"enum":[{"value":"UTC","description":"UTC"},{"value":"Europe/Paris","description":"Paris"}]}},
          {"variable":"network","label":"","group":"Network","schema":{"type":"dict","attrs":[
            {"variable":"host_network","label":"Host Network","schema":{"type":"boolean","default":false}},
            {"variable":"web_port","label":"Web Port","schema":{"type":"dict","show_if":[["host_network","=",false]],"attrs":[
              {"variable":"port_number","label":"Port Number","schema":{"type":"int","min":1,"max":65535,"required":true}}]}}]}},
          {"variable":"password","label":"Password","group":"Network","schema":{"type":"string","private":true,"required":true}},
          {"variable":"envs","label":"Environment","group":"Network","schema":{"type":"list","items":[
            {"variable":"env","label":"Variable","schema":{"type":"dict","attrs":[
              {"variable":"name","label":"Name","schema":{"type":"string","required":true}}]}}]}}]}
        """,
    ).jsonObject
    private val form = AppForm.parse(schema)
    private val config = Json.parseToJsonElement(
        """{"TZ":"UTC","network":{"host_network":false,"web_port":{"port_number":8096}},"password":"s3cret","envs":[],"ix_context":{"app_name":"jf"}}""",
    ).jsonObject

    @Test
    fun `values for update keep only questions`() {
        val v = form.valuesFor(config)
        assertFalse("ix_context" in v)
        assertEquals(setOf("TZ", "network", "password", "envs"), v.keys)
    }

    @Test
    fun `show_if hides fields whose condition fails`() {
        val network = form.fields.first { it.variable == "network" }
        val port = network.attrs.first { it.variable == "web_port" }
        assertTrue(port.visibleWith(config["network"]!!.jsonObject))
        val hostNet = config["network"]!!.jsonObject.let { kotlinx.serialization.json.JsonObject(it + ("host_network" to JsonPrimitive(true))) }
        assertFalse(port.visibleWith(hostNet))
    }

    @Test
    fun `int limits and required fields are checked`() {
        val path = listOf(PathKey.Key("network"), PathKey.Key("web_port"), PathKey.Key("port_number"))
        val bad = form.valuesFor(config).with(path, JsonPrimitive(70000)).jsonObject
        assertEquals(listOf("Web Port › Port Number" to "At most 65535"), form.problems(bad))
        val text = form.valuesFor(config).with(path, JsonPrimitive("80a")).jsonObject
        assertEquals("Whole number", form.problems(text).single().second)
        val noPassword = form.valuesFor(config).with(listOf(PathKey.Key("password")), JsonPrimitive("")).jsonObject
        assertEquals(listOf("Password" to "Required"), form.problems(noPassword))
        assertTrue(form.problems(form.valuesFor(config)).isEmpty())
    }

    @Test
    fun `hidden branches are not validated`() {
        val v = form.valuesFor(config)
            .with(listOf(PathKey.Key("network"), PathKey.Key("host_network")), JsonPrimitive(true))
            .with(listOf(PathKey.Key("network"), PathKey.Key("web_port"), PathKey.Key("port_number")), JsonNull).jsonObject
        assertTrue(form.problems(v).isEmpty())
    }

    @Test
    fun `list entries can be added and removed`() {
        val envs = form.fields.first { it.variable == "envs" }
        val added = form.valuesFor(config).with(listOf(PathKey.Key("envs"), PathKey.Index(0)), envs.item!!.initialValue()).jsonObject
        assertEquals(1, added.at(listOf(PathKey.Key("envs")))!!.let { it as kotlinx.serialization.json.JsonArray }.size)
        assertEquals(listOf("Environment 1 › Name" to "Required"), form.problems(added))
        val removed = added.with(listOf(PathKey.Key("envs"), PathKey.Index(0)), null).jsonObject
        assertEquals(form.valuesFor(config), removed)
    }

    @Test
    fun `changed labels name the edited fields`() {
        val before = form.valuesFor(config)
        val after = before
            .with(listOf(PathKey.Key("TZ")), JsonPrimitive("Europe/Paris"))
            .with(listOf(PathKey.Key("network"), PathKey.Key("web_port"), PathKey.Key("port_number")), JsonPrimitive(8097)).jsonObject
        assertEquals(listOf("Timezone", "Web Port › Port Number"), form.changedLabels(before, after))
    }

    @Test
    fun `descriptions lose their html`() {
        val f = FormField.parse(Json.parseToJsonElement("""{"variable":"x","label":"X","description":"Line one</br>Line <b>two</b>","schema":{"type":"string"}}"""))
        assertEquals("Line one\nLine two", f!!.description)
        assertNull(f.problem(JsonPrimitive("anything")))
    }
}
