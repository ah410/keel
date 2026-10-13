package com.arceusvault.keel.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    STORAGE("Storage", Icons.Default.Storage),
    APPS("Apps", Icons.Default.Apps),
    ALERTS("Alerts", Icons.Default.Notifications),
    MORE("More", Icons.Default.Menu),
}

/** A screen inside a tab. [arg] is the one parameter a detail screen needs (an app name, a dataset...). */
data class Route(val name: String, val arg: String? = null)

/** Per-tab back stacks, so switching tabs keeps your place the way the web UI's sidebar does. */
@Stable
class Navigator(initialTab: Tab, initialStacks: Map<Tab, List<Route>>) {
    var tab by mutableStateOf(initialTab)
        private set
    private val stacks = mutableStateMapOf<Tab, List<Route>>().apply { putAll(initialStacks) }

    /** The open sub-screen in the current tab, or null at the tab's root. */
    val current: Route? get() = stacks[tab]?.lastOrNull()

    val canGoBack: Boolean get() = !stacks[tab].isNullOrEmpty() || tab != Tab.DASHBOARD

    fun open(route: Route) {
        stacks[tab] = stacks[tab].orEmpty() + route
    }

    /** Switches tab; tapping the current tab again pops back to its root. */
    fun select(t: Tab) {
        if (t == tab) stacks[t] = emptyList() else tab = t
    }

    /** Jumps to a tab and opens [route] there, e.g. from a dashboard card. */
    fun go(t: Tab, route: Route? = null) {
        tab = t
        stacks[t] = listOfNotNull(route)
    }

    fun back(): Boolean {
        val s = stacks[tab]
        if (!s.isNullOrEmpty()) {
            stacks[tab] = s.dropLast(1)
            return true
        }
        if (tab != Tab.DASHBOARD) {
            tab = Tab.DASHBOARD
            return true
        }
        return false
    }

    internal fun save(): List<String> = listOf(tab.name) + stacks.flatMap { (t, routes) ->
        routes.map { listOf(t.name, it.name, it.arg ?: "").joinToString(SEP) }
    }

    companion object {
        private const val SEP = "\u0001"

        val saver = Saver<Navigator, List<String>>(
            save = { it.save() },
            restore = { saved ->
                val tab = runCatching { Tab.valueOf(saved.first()) }.getOrDefault(Tab.DASHBOARD)
                val stacks = saved.drop(1).mapNotNull { line ->
                    val parts = line.split(SEP)
                    val t = runCatching { Tab.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
                    t to Route(parts[1], parts.getOrNull(2)?.ifEmpty { null })
                }.groupBy({ it.first }, { it.second })
                Navigator(tab, stacks)
            },
        )
    }
}

@Composable
fun rememberNavigator(): Navigator = rememberSaveable(saver = Navigator.saver) { Navigator(Tab.DASHBOARD, emptyMap()) }

val LocalNavigator = compositionLocalOf<Navigator> { error("No Navigator") }
