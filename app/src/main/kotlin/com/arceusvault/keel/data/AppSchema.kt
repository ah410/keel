package com.arceusvault.keel.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A catalog app's settings form, parsed from the questions schema the server sends with `include_app_schema`. */
data class AppForm(val groups: List<FormGroup>, val fields: List<FormField>) {
    /** The values app.update expects: only the top-level questions, never the server's ix_* context. */
    fun valuesFor(config: JsonObject): JsonObject =
        JsonObject(fields.mapNotNull { f -> config[f.variable]?.let { f.variable to it } }.toMap())

    companion object {
        fun parse(schema: JsonObject): AppForm {
            val groups = (schema["groups"] as? JsonArray).orEmpty().mapNotNull { g ->
                val o = g as? JsonObject ?: return@mapNotNull null
                FormGroup(o.str("name") ?: return@mapNotNull null, o.str("description"))
            }
            val fields = (schema["questions"] as? JsonArray).orEmpty().mapNotNull { FormField.parse(it) }
            return AppForm(groups, fields)
        }
    }
}

data class FormGroup(val name: String, val description: String?)

data class Choice(val value: JsonElement, val label: String)

/** One `show_if` condition: [variable] is a sibling in the same dict. */
data class Condition(val variable: String, val op: String, val value: JsonElement)

data class FormField(
    val variable: String,
    val label: String,
    val description: String?,
    val group: String?,
    val type: String,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val choices: List<Choice> = emptyList(),
    val private: Boolean = false,
    val immutable: Boolean = false,
    val hidden: Boolean = false,
    val editable: Boolean = true,
    val nullable: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val showIf: List<Condition> = emptyList(),
    val attrs: List<FormField> = emptyList(),
    val item: FormField? = null,
) {
    val readOnly: Boolean get() = immutable || !editable

    /** Whether the field applies given its sibling values. */
    fun visibleWith(siblings: JsonObject?): Boolean = !hidden && showIf.all { it.holds(siblings?.get(it.variable)) }

    /** A fresh value for a new list entry or a missing key. */
    fun initialValue(): JsonElement = default ?: when (type) {
        "dict" -> JsonObject(attrs.associate { it.variable to it.initialValue() })
        "list" -> JsonArray(emptyList())
        "boolean" -> JsonPrimitive(false)
        "int" -> if (nullable) JsonNull else JsonPrimitive(min?.toLong() ?: 0)
        else -> if (nullable) JsonNull else JsonPrimitive("")
    }

    /** A message when [value] breaks this field's rules, or null. Only scalar types are checked. */
    fun problem(value: JsonElement?): String? {
        val p = value as? JsonPrimitive
        val blank = value == null || value is JsonNull || (p?.isString == true && p.content.isEmpty())
        if (blank) return if (required && !nullable) "Required" else null
        return when (type) {
            "int" -> {
                val n = p?.takeUnless { it.isString }?.longOrNull ?: return "Whole number"
                when {
                    min != null && n < min -> "At least ${min.toLong()}"
                    max != null && n > max -> "At most ${max.toLong()}"
                    else -> null
                }
            }
            "string", "text", "path", "hostpath", "ipaddr", "uri", "cron" -> {
                val s = p?.contentOrNull ?: return null
                when {
                    choices.isNotEmpty() && choices.none { (it.value as? JsonPrimitive)?.contentOrNull == s } -> "Pick one of the options"
                    minLength != null && s.length < minLength -> "At least $minLength characters"
                    maxLength != null && s.length > maxLength -> "At most $maxLength characters"
                    else -> null
                }
            }
            else -> null
        }
    }

    companion object {
        fun parse(e: JsonElement): FormField? {
            val o = e as? JsonObject ?: return null
            val variable = o.str("variable") ?: return null
            val s = o["schema"] as? JsonObject ?: return null
            return FormField(
                variable = variable,
                label = o.str("label").orEmpty(),
                description = o.str("description")?.let(::plainText),
                group = o.str("group"),
                type = s.str("type") ?: "string",
                required = s.bool("required"),
                default = s["default"],
                choices = (s["enum"] as? JsonArray).orEmpty().mapNotNull { c ->
                    val co = c as? JsonObject ?: return@mapNotNull null
                    val v = co["value"] ?: return@mapNotNull null
                    Choice(v, co.str("description") ?: (v as? JsonPrimitive)?.contentOrNull ?: v.toString())
                },
                private = s.bool("private"),
                immutable = s.bool("immutable"),
                hidden = s.bool("hidden"),
                editable = (s["editable"] as? JsonPrimitive)?.booleanOrNull ?: true,
                nullable = s.bool("null"),
                min = (s["min"] as? JsonPrimitive)?.doubleOrNull,
                max = (s["max"] as? JsonPrimitive)?.doubleOrNull,
                minLength = (s["min_length"] as? JsonPrimitive)?.longOrNull?.toInt(),
                maxLength = (s["max_length"] as? JsonPrimitive)?.longOrNull?.toInt(),
                showIf = (s["show_if"] as? JsonArray).orEmpty().mapNotNull { c ->
                    val a = c as? JsonArray ?: return@mapNotNull null
                    if (a.size < 3) return@mapNotNull null
                    Condition((a[0] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null, (a[1] as? JsonPrimitive)?.contentOrNull ?: "=", a[2])
                },
                attrs = (s["attrs"] as? JsonArray).orEmpty().mapNotNull { parse(it) },
                item = (s["items"] as? JsonArray)?.firstOrNull()?.let { parse(it) },
            )
        }

        // Descriptions carry a little HTML ("</br>", "<a>"); show them as plain text.
        private fun plainText(s: String) = s.replace(Regex("<\\s*/?\\s*br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "").lines().joinToString("\n") { it.trim() }.trim()
    }
}

fun Condition.holds(actual: JsonElement?): Boolean {
    val a = actual ?: JsonNull
    fun num(e: JsonElement) = (e as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    return when (op) {
        "=" -> same(a, value)
        "!=" -> !same(a, value)
        "in" -> (value as? JsonArray)?.any { same(a, it) } ?: false
        "nin" -> (value as? JsonArray)?.none { same(a, it) } ?: true
        ">", ">=", "<", "<=" -> {
            val x = num(a) ?: return false
            val y = num(value) ?: return false
            when (op) { ">" -> x > y; ">=" -> x >= y; "<" -> x < y; else -> x <= y }
        }
        else -> true
    }
}

// Numbers compare by value so 8080 and 8080.0 match.
private fun same(a: JsonElement, b: JsonElement): Boolean {
    if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
        val x = a.doubleOrNull
        val y = b.doubleOrNull
        if (x != null && y != null) return x == y
    }
    return a == b
}

/** One step into a JSON tree: an object key or a list index. */
sealed interface PathKey {
    data class Key(val name: String) : PathKey
    data class Index(val i: Int) : PathKey
}

fun JsonElement?.at(path: List<PathKey>): JsonElement? = path.fold(this) { e, k ->
    when (k) {
        is PathKey.Key -> (e as? JsonObject)?.get(k.name)
        is PathKey.Index -> (e as? JsonArray)?.getOrNull(k.i)
    }
}

/** A copy of this tree with [value] at [path]; null removes a list entry. Missing objects are created. */
fun JsonElement?.with(path: List<PathKey>, value: JsonElement?): JsonElement {
    if (path.isEmpty()) return value ?: JsonNull
    return when (val k = path.first()) {
        is PathKey.Key -> {
            val o = (this as? JsonObject).orEmpty().toMutableMap()
            o[k.name] = o[k.name].with(path.drop(1), value)
            JsonObject(o)
        }
        is PathKey.Index -> {
            val l = (this as? JsonArray).orEmpty().toMutableList()
            if (path.size == 1 && value == null) { if (k.i in l.indices) l.removeAt(k.i) }
            else if (k.i == l.size) l.add(null.with(path.drop(1), value))
            else if (k.i in l.indices) l[k.i] = l[k.i].with(path.drop(1), value)
            JsonArray(l)
        }
    }
}

/** Labels of the visible fields whose value differs between [before] and [after], for the confirm dialog. */
fun AppForm.changedLabels(before: JsonObject, after: JsonObject): List<String> {
    val out = mutableListOf<String>()
    fun walk(fields: List<FormField>, b: JsonElement?, a: JsonElement?, prefix: String) {
        for (f in fields) {
            val bv = (b as? JsonObject)?.get(f.variable)
            val av = (a as? JsonObject)?.get(f.variable)
            if (bv == av) continue
            val name = listOf(prefix, f.label.ifBlank { f.variable }).filter { it.isNotBlank() }.joinToString(" › ")
            if (f.type == "dict" && f.attrs.isNotEmpty()) walk(f.attrs, bv, av, if (f.label.isBlank()) prefix else name)
            else out += name
        }
    }
    walk(fields, before, after, "")
    return out
}

/** Problems in visible fields, as (label, message) pairs; saving is blocked while any exist. */
fun AppForm.problems(values: JsonObject): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    fun walk(fields: List<FormField>, parent: JsonElement?, prefix: String) {
        for (f in fields) {
            val siblings = parent as? JsonObject
            if (!f.visibleWith(siblings) || f.readOnly) continue
            val v = siblings?.get(f.variable)
            val name = listOf(prefix, f.label.ifBlank { f.variable }).filter { it.isNotBlank() }.joinToString(" › ")
            when (f.type) {
                "dict" -> walk(f.attrs, v, if (f.label.isBlank()) prefix else name)
                "list" -> (v as? JsonArray).orEmpty().forEachIndexed { i, item ->
                    val itemField = f.item ?: return@forEachIndexed
                    if (itemField.type == "dict") walk(itemField.attrs, item, "$name ${i + 1}")
                    else itemField.problem(item)?.let { out += "$name ${i + 1}" to it }
                }
                else -> f.problem(v)?.let { out += name to it }
            }
        }
    }
    walk(fields, values, "")
    return out
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())
