package com.arceusvault.keel.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Action
import com.arceusvault.keel.data.Actions
import com.arceusvault.keel.ui.LocalNavigator
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.Route
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.Loadable
import com.arceusvault.keel.ui.components.LocalActions
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.components.rememberRefresher
import com.arceusvault.keel.ui.components.rememberRunning
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.serialization.json.JsonObject

@Composable
fun SharesScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    val refresher = rememberRefresher()
    val nav = LocalNavigator.current
    val smb = rememberPolled(api, 60_000, refresher) { api.smbShares() }
    val nfs = rememberPolled(api, 60_000, refresher) { api.nfsShares() }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TnCard(title = "SMB", subtitle = "Windows (SMB) Shares", padded = false, actions = addButton(Actions.shareSmbCreate(JsonObject(emptyMap()))) { nav.open(Route("share-smb")) }) {
                Body(smb, "No SMB shares") { list ->
                    list.forEachIndexed { i, s ->
                        ShareRow(s.name, listOfNotNull(s.path, s.comment.ifBlank { null }).joinToString(" · "), s.enabled, s.readonly, s.locked == true,
                            Actions.shareSmbEnabled(s.id, s.name, !s.enabled), i == list.lastIndex, refresher::refresh) { nav.open(Route("share-smb", s.id.toString())) }
                    }
                }
            }
        }
        item {
            TnCard(title = "NFS", subtitle = "Unix (NFS) Shares", padded = false, actions = addButton(Actions.shareNfsCreate(JsonObject(emptyMap()))) { nav.open(Route("share-nfs")) }) {
                Body(nfs, "No NFS shares") { list ->
                    list.forEachIndexed { i, s ->
                        val who = (s.networks + s.hosts).ifEmpty { listOf("everyone") }.joinToString()
                        ShareRow(s.path, listOfNotNull(who, s.comment.ifBlank { null }).joinToString(" · "), s.enabled, s.ro, s.locked == true,
                            Actions.shareNfsEnabled(s.id, s.path, !s.enabled), i == list.lastIndex, refresher::refresh) { nav.open(Route("share-nfs", s.id.toString())) }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> Body(state: Loadable<List<T>>, empty: String, content: @Composable (List<T>) -> Unit) {
    when (state) {
        Loadable.Loading -> Text("Loading...", Modifier.padding(16.dp), color = StatusColors.muted)
        is Loadable.Error -> Text(state.message, Modifier.padding(16.dp), color = StatusColors.muted)
        is Loadable.Ready -> if (state.value.isEmpty()) Text(empty, Modifier.padding(16.dp), color = StatusColors.muted) else content(state.value)
    }
}

@Composable
private fun ShareRow(title: String, subtitle: String, enabled: Boolean, readOnly: Boolean, locked: Boolean, toggle: Action, last: Boolean, onChanged: () -> Unit, onOpen: () -> Unit) {
    val server = LocalServer.current
    val actions = LocalActions.current
    val running = rememberRunning(server)
    ListRow(
        title,
        subtitle = subtitle,
        trailing = {
            when {
                locked -> Pill("Locked", StatusColors.warn)
                readOnly -> Pill("Read-only", StatusColors.muted)
            }
            Switch(enabled, { actions.launch(toggle, onChanged) }, enabled = actions.can(toggle) && toggle.key !in running)
        },
        divider = !last,
        onClick = onOpen,
    )
}

/** The "+" in a card header, shown only when the key may create that kind of share. */
@Composable
private fun addButton(create: Action, onClick: () -> Unit): (@Composable RowScope.() -> Unit)? {
    if (!LocalActions.current.can(create)) return null
    return { IconButton(onClick) { Icon(Icons.Default.Add, "Add share") } }
}
