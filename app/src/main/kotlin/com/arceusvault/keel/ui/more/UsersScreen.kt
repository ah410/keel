package com.arceusvault.keel.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.ListRow
import com.arceusvault.keel.ui.components.LoadableContent
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.rememberPolled
import com.arceusvault.keel.ui.theme.StatusColors

/** Read-only list of local users. Password hashes and SSH keys are never requested. */
@Composable
fun UsersScreen(padding: PaddingValues) {
    val api = LocalServer.current.api
    var builtin by rememberSaveable { mutableStateOf(false) }
    val users = rememberPolled(api, 120_000) { api.users() }
    LoadableContent(users) { all ->
        val list = all.filter { builtin || !it.builtin }.sortedBy { it.uid }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { FilterChip(builtin, { builtin = !builtin }, label = { Text("Show built-in users") }) }
            item {
                TnCard(title = "Users", subtitle = "${list.size} shown", padded = false) {
                    list.forEachIndexed { i, u ->
                        ListRow(
                            u.username,
                            subtitle = listOfNotNull(
                                u.fullName.ifBlank { null },
                                "uid ${u.uid}",
                                u.roles.takeIf { it.isNotEmpty() }?.joinToString { it.lowercase().replace('_', ' ') },
                                u.apiKeys.size.takeIf { it > 0 }?.let { "$it API key${if (it > 1) "s" else ""}" },
                            ).joinToString(" · "),
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (u.locked) Pill("Locked", StatusColors.warn)
                                    if (u.twoFactor) Pill("2FA", StatusColors.ok)
                                    if (u.smb) Pill("SMB", StatusColors.info)
                                }
                            },
                            divider = i != list.lastIndex,
                        )
                    }
                }
            }
        }
    }
}
