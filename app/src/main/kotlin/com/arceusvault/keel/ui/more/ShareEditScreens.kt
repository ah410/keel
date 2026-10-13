package com.arceusvault.keel.ui.more

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Action
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.Button
import com.arceusvault.keel.ui.components.Loadable
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.OutlinedButton
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.describeError
import com.arceusvault.keel.ui.theme.PlexMono
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Purposes worth offering from a phone; others (Veeam, private datasets...) keep working but aren't picked here.
private val smbPurposes = listOf(
    "DEFAULT_SHARE" to "Default",
    "MULTIPROTOCOL_SHARE" to "Multi-protocol (also used by NFS or apps)",
    "TIMEMACHINE_SHARE" to "Time Machine",
)

private val newSmb = JsonObject(
    mapOf(
        "name" to JsonPrimitive(""), "path" to JsonPrimitive("/mnt/"), "purpose" to JsonPrimitive("DEFAULT_SHARE"),
        "comment" to JsonPrimitive(""), "enabled" to JsonPrimitive(true), "readonly" to JsonPrimitive(false),
        "browsable" to JsonPrimitive(true), "access_based_share_enumeration" to JsonPrimitive(false),
    ),
)

private val newNfs = JsonObject(
    mapOf(
        "path" to JsonPrimitive("/mnt/"), "comment" to JsonPrimitive(""), "enabled" to JsonPrimitive(true),
        "ro" to JsonPrimitive(false), "networks" to JsonArray(emptyList()), "hosts" to JsonArray(emptyList()),
        "maproot_user" to JsonNull, "maproot_group" to JsonNull, "mapall_user" to JsonNull, "mapall_group" to JsonNull,
    ),
)

private val smbKeys = newSmb.keys
private val nfsKeys = newNfs.keys

/** Creates an SMB share when [id] is null, otherwise edits it. */
@Composable
fun SmbShareEditScreen(id: Long?, padding: PaddingValues) {
    val api = LocalServer.current.api
    ShareEditor(
        load = { if (id == null) newSmb else api.smbShare(id).filterKeys(smbKeys) },
        padding = padding,
        save = { original, edited ->
            if (id == null) Actions.shareSmbCreate(edited)
            else Actions.shareSmbUpdate(id, original.text("name"), edited.changesFrom(original).withOptionsReset(original))
        },
        delete = id?.let { { original: JsonObject -> Actions.shareSmbDelete(it, original.text("name")) } },
        problems = { v ->
            listOfNotNull(
                "Name is required".takeIf { v.text("name").isBlank() },
                "Name can't contain \\ / [ ] : | < > + = ; , * ? \"".takeIf { v.text("name").any { c -> c in "\\/[]:|<>+=;,*?\"" } },
                "Path must be under /mnt/".takeIf { !v.text("path").startsWith("/mnt/") || v.text("path").length <= 5 },
            )
        },
    ) { v, set, enabled ->
        Field("Name", v.text("name"), enabled) { set("name", JsonPrimitive(it)) }
        PathField(v.text("path"), enabled) { set("path", JsonPrimitive(it)) }
        val purpose = v.text("purpose")
        Picker(
            "Purpose",
            smbPurposes.firstOrNull { it.first == purpose }?.second ?: purpose.lowercase().replace('_', ' '),
            smbPurposes, enabled && (purpose.isEmpty() || smbPurposes.any { it.first == purpose }),
        ) { set("purpose", JsonPrimitive(it)) }
        Field("Description", v.text("comment"), enabled) { set("comment", JsonPrimitive(it)) }
        Toggle("Enabled", null, v.flag("enabled"), enabled) { set("enabled", JsonPrimitive(it)) }
        Toggle("Read-only", "Clients can't create or change files", v.flag("readonly"), enabled) { set("readonly", JsonPrimitive(it)) }
        Toggle("Visible in share list", null, v.flag("browsable"), enabled) { set("browsable", JsonPrimitive(it)) }
        Toggle("Only list for users with access", null, v.flag("access_based_share_enumeration"), enabled) {
            set("access_based_share_enumeration", JsonPrimitive(it))
        }
    }
}

/** Creates an NFS export when [id] is null, otherwise edits it. */
@Composable
fun NfsShareEditScreen(id: Long?, padding: PaddingValues) {
    val api = LocalServer.current.api
    ShareEditor(
        load = { if (id == null) newNfs else api.nfsShare(id).filterKeys(nfsKeys) },
        padding = padding,
        save = { original, edited ->
            if (id == null) Actions.shareNfsCreate(edited)
            else Actions.shareNfsUpdate(id, original.text("path"), edited.changesFrom(original))
        },
        delete = id?.let { { original: JsonObject -> Actions.shareNfsDelete(it, original.text("path")) } },
        problems = { v ->
            listOfNotNull(
                "Path must be under /mnt/".takeIf { !v.text("path").startsWith("/mnt/") || v.text("path").length <= 5 },
                "Networks need CIDR form, like 192.168.1.0/24".takeIf { v.list("networks").any { '/' !in it } },
            )
        },
    ) { v, set, enabled ->
        PathField(v.text("path"), enabled) { set("path", JsonPrimitive(it)) }
        Field("Description", v.text("comment"), enabled) { set("comment", JsonPrimitive(it)) }
        Toggle("Enabled", null, v.flag("enabled"), enabled) { set("enabled", JsonPrimitive(it)) }
        Toggle("Read-only", null, v.flag("ro"), enabled) { set("ro", JsonPrimitive(it)) }
        ListField("Allowed networks", "One per line, like 192.168.1.0/24. Empty allows all.", v.list("networks"), enabled) { set("networks", it) }
        ListField("Allowed hosts", "One IP or hostname per line. Empty allows all.", v.list("hosts"), enabled) { set("hosts", it) }
        Field("Map root user", v.text("maproot_user"), enabled, nullable = true) { set("maproot_user", it.orNull()) }
        Field("Map root group", v.text("maproot_group"), enabled, nullable = true) { set("maproot_group", it.orNull()) }
        Field("Map all users", v.text("mapall_user"), enabled, nullable = true) { set("mapall_user", it.orNull()) }
        Field("Map all groups", v.text("mapall_group"), enabled, nullable = true) { set("mapall_group", it.orNull()) }
    }
}

@Composable
private fun ShareEditor(
    load: suspend () -> JsonObject,
    padding: PaddingValues,
    save: (original: JsonObject, edited: JsonObject) -> Action,
    delete: ((JsonObject) -> Action)?,
    problems: (JsonObject) -> List<String>,
    fields: @Composable (JsonObject, set: (String, JsonElement) -> Unit, enabled: Boolean) -> Unit,
) {
    val actions = LocalActions.current
    val nav = LocalNavigator.current
    var state by remember { mutableStateOf<Loadable<JsonObject>>(Loadable.Loading) }
    LaunchedEffect(Unit) {
        state = try {
            Loadable.Ready(load())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Loadable.Error(describeError(e))
        }
    }
    LoadableContent(state) { original ->
        var values by remember(original) { mutableStateOf(original) }
        val canWrite = actions.can(save(original, values))
        val issues = problems(values)
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TnCard {
                    if (!canWrite) Text("This API key can't change shares.", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                    fields(values, { k, v -> values = JsonObject(values + (k to v)) }, canWrite)
                }
            }
            if (canWrite) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (values != original) issues.forEach { Text(it, color = StatusColors.bad, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        { actions.launch(save(original, values)) { nav.back() } },
                        Modifier.fillMaxWidth(),
                        enabled = values != original && issues.isEmpty(),
                    ) { Text(if (delete == null) "Create share" else "Save") }
                    delete?.let { d ->
                        OutlinedButton({ actions.launch(d(original)) { nav.back() } }, Modifier.fillMaxWidth()) {
                            Text("Delete share", color = StatusColors.bad)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, nullable: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, enabled = enabled, singleLine = true,
        placeholder = if (nullable) { { Text("None") } } else null,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PathField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var browsing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text("Path") }, enabled = enabled, singleLine = true,
        textStyle = TextStyle(fontFamily = PlexMono),
        trailingIcon = { IconButton({ browsing = true }, enabled = enabled) { Icon(Icons.Default.Folder, "Browse") } },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (browsing) FolderPicker(value, { onChange(it); browsing = false }, { browsing = false })
}

/** Walks the server's directories under /mnt; nothing above /mnt can be shared. */
@Composable
private fun FolderPicker(start: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val api = LocalServer.current.api
    var dir by remember { mutableStateOf(start.trimEnd('/').takeIf { it.startsWith("/mnt/") } ?: "/mnt") }
    var children by remember { mutableStateOf<Loadable<List<String>>>(Loadable.Loading) }
    LaunchedEffect(dir) {
        children = Loadable.Loading
        children = try {
            Loadable.Ready(api.listDirs(dir))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Loadable.Error(describeError(e))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dir, fontFamily = PlexMono, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (dir != "/mnt") Row(Modifier.fillMaxWidth().clickable { dir = dir.substringBeforeLast('/') }.padding(vertical = 10.dp)) { Text("..") }
                when (val c = children) {
                    Loadable.Loading -> Text("Loading...", color = StatusColors.muted)
                    is Loadable.Error -> Text(c.message, color = StatusColors.bad)
                    is Loadable.Ready -> {
                        if (c.value.isEmpty()) Text("No folders here", color = StatusColors.muted)
                        c.value.forEach { p ->
                            Row(Modifier.fillMaxWidth().clickable { dir = p }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, Modifier.padding(end = 8.dp), tint = StatusColors.muted)
                                Text(p.substringAfterLast('/'))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onPick(dir) }, enabled = dir != "/mnt") { Text("Use this folder") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Picker(label: String, current: String, options: List<Pair<String, String>>, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            current, {}, readOnly = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }, modifier = Modifier.fillMaxWidth(),
        )
        if (enabled) Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem({ Text(l) }, { onPick(v); open = false }) }
        }
    }
}

@Composable
private fun Toggle(label: String, hint: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.muted) }
        }
        Switch(checked, null, enabled = enabled)
    }
}

/** A list edited as one entry per line. */
@Composable
private fun ListField(label: String, hint: String, items: List<String>, enabled: Boolean, onChange: (JsonArray) -> Unit) {
    var text by remember { mutableStateOf(items.joinToString("\n")) }
    OutlinedTextField(
        text,
        { t -> text = t; onChange(JsonArray(t.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().map(::JsonPrimitive))) },
        label = { Text(label) }, enabled = enabled, minLines = 2,
        supportingText = { Text(hint) }, textStyle = TextStyle(fontFamily = PlexMono),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.flag(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.filterKeys(keys: Set<String>) = JsonObject(filterKeys { it in keys })
private fun String.orNull(): JsonElement = if (isBlank()) JsonNull else JsonPrimitive(trim())

/** Only the fields that changed, so an update never rewrites settings this screen doesn't show. */
internal fun JsonObject.changesFrom(original: JsonObject) = JsonObject(filter { (k, v) -> original[k] != v })

// Purpose-specific options don't carry over to another purpose; null asks the server for the new defaults.
private fun JsonObject.withOptionsReset(original: JsonObject) =
    if ("purpose" in this && this["purpose"] != original["purpose"]) JsonObject(this + ("options" to JsonNull)) else this
