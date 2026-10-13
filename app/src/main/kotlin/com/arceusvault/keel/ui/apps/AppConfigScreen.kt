package com.arceusvault.keel.ui.apps

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.data.AppEditData
import com.arceusvault.keel.data.AppForm
import com.arceusvault.keel.data.FormField
import com.arceusvault.keel.data.PathKey
import com.arceusvault.keel.data.at
import com.arceusvault.keel.data.changedLabels
import com.arceusvault.keel.data.problems
import com.arceusvault.keel.data.with
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.Button
import com.arceusvault.keel.ui.components.CenteredMessage
import com.arceusvault.keel.ui.components.Loadable
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.OutlinedButton
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.describeError
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.PlexMono
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Edits an installed app's settings: a form built from the catalog schema, or the compose file for custom apps. */
@Composable
fun AppConfigScreen(name: String, padding: PaddingValues) {
    val api = LocalServer.current.api
    var state by remember { mutableStateOf<Loadable<AppEditData>>(Loadable.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(name, attempt) {
        state = Loadable.Loading
        state = try {
            Loadable.Ready(api.appForEdit(name))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Loadable.Error(describeError(e))
        }
    }
    LoadableContent(state) { data ->
        when {
            data.custom -> ComposeEditor(data, padding)
            data.form != null -> FormEditor(data, data.form, padding)
            else -> CenteredMessage(
                "Can't edit this app here",
                "The server didn't send a settings form for $name. Edit it in the web interface.",
                action = "Retry" to { attempt++ },
            )
        }
    }
}

@Composable
private fun FormEditor(data: AppEditData, form: AppForm, padding: PaddingValues) {
    val actions = LocalActions.current
    val nav = LocalNavigator.current
    val running = rememberRunning(LocalServer.current)
    val original = remember(data) { form.valuesFor(data.config) }
    var values by remember(data) { mutableStateOf(original) }
    var showProblems by remember { mutableStateOf(false) }
    val canWrite = actions.can(Actions.appUpdate(data.name, original, emptyList()))
    val onChange: (List<PathKey>, JsonElement?) -> Unit = { path, v -> values = values.with(path, v) as JsonObject }

    // Questions grouped the way the schema orders its groups; ungrouped ones go last.
    val sections = remember(form) {
        val named = form.groups.map { g -> g.name to form.fields.filter { it.group == g.name } }
        val rest = form.fields.filter { f -> form.groups.none { it.name == f.group } }
        (named + listOf("Other" to rest)).filter { (_, fields) -> fields.isNotEmpty() }
    }

    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!canWrite) item {
            Text("This API key can view these settings but not change them.", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
        }
        items(sections, key = { it.first }) { (group, fields) ->
            TnCard(title = group) {
                FieldList(fields, emptyList(), values, values, onChange, canWrite, showProblems)
            }
        }
        if (canWrite) item {
            val problems = form.problems(values)
            val changes = form.changedLabels(original, values)
            val key = Actions.appUpdate(data.name, values, changes).key
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showProblems && problems.isNotEmpty()) {
                    Text(
                        "Fix these first: " + problems.take(5).joinToString { "${it.first} (${it.second.lowercase()})" },
                        color = StatusColors.bad, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    {
                        if (problems.isNotEmpty()) showProblems = true
                        else actions.launch(Actions.appUpdate(data.name, values, changes)) { nav.back() }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = values != original && key !in running,
                ) { Text(if (values == original) "No changes" else "Save and redeploy") }
                if (values != original) OutlinedButton({ values = original; showProblems = false }, Modifier.fillMaxWidth()) { Text("Discard changes") }
            }
        }
    }
}

@Composable
private fun FieldList(
    fields: List<FormField>,
    parentPath: List<PathKey>,
    parent: JsonElement?,
    root: JsonObject,
    onChange: (List<PathKey>, JsonElement?) -> Unit,
    enabled: Boolean,
    showProblems: Boolean,
) {
    val siblings = parent as? JsonObject
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fields.filter { it.visibleWith(siblings) }.forEach { f ->
            FieldEditor(f, parentPath + PathKey.Key(f.variable), siblings?.get(f.variable), root, onChange, enabled, showProblems)
        }
    }
}

@Composable
private fun FieldEditor(
    f: FormField,
    path: List<PathKey>,
    value: JsonElement?,
    root: JsonObject,
    onChange: (List<PathKey>, JsonElement?) -> Unit,
    enabled: Boolean,
    showProblems: Boolean,
) {
    val canEdit = enabled && !f.readOnly
    val label = f.label.ifBlank { f.variable }
    when (f.type) {
        "dict" -> {
            if (f.label.isBlank()) {
                FieldList(f.attrs, path, value ?: root.at(path), root, onChange, enabled, showProblems)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    f.description?.let { Hint(it) }
                    Box(Modifier.padding(start = 12.dp)) {
                        FieldList(f.attrs, path, value, root, onChange, enabled, showProblems)
                    }
                }
            }
        }
        "list" -> ListEditor(f, path, value as? JsonArray, root, onChange, canEdit, showProblems)
        "boolean" -> {
            val on = (value as? JsonPrimitive)?.booleanOrNull ?: false
            Row(
                Modifier.fillMaxWidth().toggleable(on, enabled = canEdit, role = Role.Switch) { onChange(path, JsonPrimitive(it)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    f.description?.let { Hint(it) }
                }
                Switch(on, null, enabled = canEdit)
            }
        }
        else -> {
            if (f.choices.isNotEmpty()) ChoiceField(f, label, value, canEdit) { onChange(path, it) }
            else TextValueField(f, label, value, canEdit, showProblems) { onChange(path, it) }
        }
    }
}

@Composable
private fun ListEditor(
    f: FormField,
    path: List<PathKey>,
    list: JsonArray?,
    root: JsonObject,
    onChange: (List<PathKey>, JsonElement?) -> Unit,
    canEdit: Boolean,
    showProblems: Boolean,
) {
    val item = f.item ?: return
    val entries = list.orEmpty()
    val lines = LocalPalette.current.lines
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(f.label.ifBlank { f.variable }, style = MaterialTheme.typography.titleSmall)
        f.description?.let { Hint(it) }
        entries.forEachIndexed { i, entry ->
            val itemPath = path + PathKey.Index(i)
            Column(Modifier.fillMaxWidth().border(1.dp, lines, MaterialTheme.shapes.small).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.label.ifBlank { "Item" }} ${i + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = StatusColors.muted)
                    if (canEdit) IconButton({ onChange(itemPath, null) }) { Icon(Icons.Default.Close, "Remove") }
                }
                if (item.type == "dict") FieldList(item.attrs, itemPath, entry, root, onChange, canEdit, showProblems)
                else FieldEditor(item.copy(label = ""), itemPath, entry, root, onChange, canEdit, showProblems)
            }
        }
        if (canEdit) TextButton({ onChange(path + PathKey.Index(entries.size), item.initialValue()) }) {
            Icon(Icons.Default.Add, null)
            Text("Add ${item.label.ifBlank { "item" }.lowercase()}")
        }
    }
}

@Composable
private fun ChoiceField(f: FormField, label: String, value: JsonElement?, canEdit: Boolean, onPick: (JsonElement) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = f.choices.firstOrNull { it.value == value }?.label ?: (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    Column {
        Box {
            OutlinedTextField(
                current, {}, readOnly = true, enabled = canEdit, label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                modifier = Modifier.fillMaxWidth(),
            )
            // The read-only field swallows taps, so an overlay opens the menu.
            if (canEdit) Box(Modifier.matchParentSize().clickable { open = true })
            DropdownMenu(open, { open = false }) {
                f.choices.forEach { c ->
                    DropdownMenuItem({ Text(c.label) }, { onPick(c.value); open = false })
                }
            }
        }
        f.description?.let { Hint(it) }
    }
}

@Composable
private fun TextValueField(f: FormField, label: String, value: JsonElement?, canEdit: Boolean, showProblems: Boolean, onValue: (JsonElement) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    val text = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
    val problem = f.problem(value)
    val mono = f.type in setOf("path", "hostpath", "cron", "ipaddr", "uri")
    OutlinedTextField(
        text,
        { t ->
            onValue(
                when {
                    t.isEmpty() && f.nullable -> JsonNull
                    f.type == "int" -> t.trim().toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(t)
                    else -> JsonPrimitive(t)
                },
            )
        },
        label = { Text(label + if (f.required) " *" else "") },
        enabled = canEdit,
        singleLine = f.type != "text",
        minLines = if (f.type == "text") 3 else 1,
        textStyle = if (mono) TextStyle(fontFamily = PlexMono) else MaterialTheme.typography.bodyLarge,
        isError = problem != null && (showProblems || text.isNotEmpty()),
        supportingText = (if (problem != null && (showProblems || text.isNotEmpty())) problem else f.description)?.let { { Text(it) } },
        visualTransformation = if (f.private && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (f.private) {
            { IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (reveal) "Hide" else "Show") } }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                f.type == "int" -> KeyboardType.Number
                f.private -> KeyboardType.Password
                f.type == "uri" -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)

private val pretty = Json { prettyPrint = true }

/** Custom apps are a compose file. JSON is valid YAML, so it's edited as JSON and saved as an object. */
@Composable
private fun ComposeEditor(data: AppEditData, padding: PaddingValues) {
    val actions = LocalActions.current
    val nav = LocalNavigator.current
    val original = remember(data) { pretty.encodeToString(JsonObject.serializer(), data.config) }
    var text by remember(data) { mutableStateOf(original) }
    val parsed = remember(text) { runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() }
    val canWrite = actions.can(Actions.appUpdateCompose(data.name, data.config))
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TnCard(title = "Compose file", subtitle = "Shown as JSON, which Docker Compose accepts as YAML") {
                Hint("Environment values here can include passwords and tokens.")
                OutlinedTextField(
                    text, { text = it },
                    enabled = canWrite,
                    textStyle = TextStyle(fontFamily = PlexMono, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    isError = parsed == null,
                    supportingText = if (parsed == null) { { Text("Not valid JSON") } } else null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                )
            }
        }
        if (canWrite) item {
            Button(
                { parsed?.let { actions.launch(Actions.appUpdateCompose(data.name, it)) { nav.back() } } },
                Modifier.fillMaxWidth(),
                enabled = parsed != null && text != original,
            ) { Text(if (text == original) "No changes" else "Save and redeploy") }
        }
    }
}

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())
