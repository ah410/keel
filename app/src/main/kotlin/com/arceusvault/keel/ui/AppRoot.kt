package com.arceusvault.keel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arceusvault.keel.AppContainer
import com.arceusvault.keel.data.SessionManager
import com.arceusvault.keel.domain.ConnectionMode
import com.arceusvault.keel.ui.apps.AppConfigScreen
import com.arceusvault.keel.ui.apps.AppDetailScreen
import com.arceusvault.keel.ui.apps.AppLogsScreen
import com.arceusvault.keel.ui.apps.AppsScreen
import com.arceusvault.keel.ui.components.ActionHost
import com.arceusvault.keel.ui.components.Pill
import com.arceusvault.keel.ui.lock.ConnectingScreen
import com.arceusvault.keel.ui.lock.FailedScreen
import com.arceusvault.keel.ui.lock.LockScreen
import com.arceusvault.keel.ui.more.JobsScreen
import com.arceusvault.keel.ui.more.MoreScreen
import com.arceusvault.keel.ui.more.NfsShareEditScreen
import com.arceusvault.keel.ui.more.SharesScreen
import com.arceusvault.keel.ui.more.SmbShareEditScreen
import com.arceusvault.keel.ui.more.UsersScreen
import com.arceusvault.keel.ui.screens.AlertsScreen
import com.arceusvault.keel.ui.screens.DashboardScreen
import com.arceusvault.keel.ui.screens.StorageScreen
import com.arceusvault.keel.ui.settings.SettingsScreen
import com.arceusvault.keel.ui.setup.SetupFlow
import com.arceusvault.keel.ui.setup.SetupViewModel
import com.arceusvault.keel.ui.storage.DatasetScreen
import com.arceusvault.keel.ui.storage.DatasetsScreen
import com.arceusvault.keel.ui.storage.ProtectionScreen
import com.arceusvault.keel.ui.system.AuditScreen
import com.arceusvault.keel.ui.system.BootEnvironmentsScreen
import com.arceusvault.keel.ui.system.NetworkScreen
import com.arceusvault.keel.ui.system.ReportingScreen
import com.arceusvault.keel.ui.system.ServicesScreen
import com.arceusvault.keel.ui.system.SystemScreen
import com.arceusvault.keel.ui.system.UpdateScreen
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.StatusColors
import com.arceusvault.keel.ui.virt.ContainersScreen
import com.arceusvault.keel.ui.virt.VmsScreen

@Composable
fun AppRoot(container: AppContainer) {
    when (val s = container.session.state.collectAsState().value) {
        SessionManager.State.NotConfigured -> SetupFlow(viewModel { SetupViewModel(container) })
        SessionManager.State.Locked -> LockScreen(container.session)
        is SessionManager.State.Connecting -> ConnectingScreen(s)
        is SessionManager.State.Failed -> FailedScreen(s, container.session)
        is SessionManager.State.Connected -> MainScaffold(container, s)
        is SessionManager.State.AddingServer -> SetupFlow(viewModel(key = "add-${s.id}") { SetupViewModel(container, adding = true) })
    }
}

/** Screen titles for the top bar; the arg is shown for detail screens. */
private fun titleOf(route: Route): String = when (route.name) {
    "app" -> route.arg ?: "App"
    "app-config" -> "Settings: ${route.arg}"
    "share-smb" -> if (route.arg.isNullOrEmpty()) "New SMB share" else "Edit SMB share"
    "share-nfs" -> if (route.arg.isNullOrEmpty()) "New NFS share" else "Edit NFS share"
    "app-logs" -> "Logs: ${route.arg?.substringBefore(LOG_SEP)}"
    "datasets" -> "Datasets"
    "dataset" -> route.arg?.substringAfterLast('/') ?: "Dataset"
    "protection" -> "Data Protection"
    "shares" -> "Shares"
    "users" -> "Credentials"
    "containers" -> "Containers"
    "vms" -> "Virtual Machines"
    "reporting" -> "Reporting"
    "system" -> "System"
    "services" -> "Services"
    "update" -> "Update"
    "bootenvs" -> "Boot Environments"
    "network" -> "Network"
    "audit" -> "Audit Log"
    "jobs" -> "Task Manager"
    "settings" -> "App Settings"
    else -> route.name
}

const val LOG_SEP = "\u0002"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(container: AppContainer, state: SessionManager.State.Connected) {
    val nav = rememberNavigator()
    val p = LocalPalette.current
    val server = remember(state) { ServerContext(state.api, state.roles, state.profile, container.operations, state.demo) }
    val snackbar = remember { SnackbarHostState() }
    val jobs by state.api.jobs.jobs.collectAsState()
    val activeJobs = jobs.count { !it.finished }
    // The web UI's header shows the hostname; fall back to the address until it loads.
    val hostname by produceState<String?>(null, state.api) { value = runCatching { state.api.systemInfo().hostname }.getOrNull() }
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    LaunchedEffect(container.operations) {
        container.operations.notices.collect { snackbar.showSnackbar(it.text, withDismissAction = it.error) }
    }

    CompositionLocalProvider(LocalServer provides server, LocalNavigator provides nav) {
        ActionHost(server) {
            Scaffold(
                containerColor = p.bg1,
                snackbarHost = {
                    SnackbarHost(snackbar) { data ->
                        Snackbar(data, containerColor = p.altBg2, contentColor = p.fg1, actionColor = p.primary, dismissActionContentColor = p.fg1)
                    }
                },
                topBar = {
                    val route = nav.current
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = p.topbar,
                            titleContentColor = p.topbarText,
                            navigationIconContentColor = p.topbarText,
                            actionIconContentColor = p.topbarText,
                        ),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    route?.let(::titleOf) ?: if (nav.tab == Tab.DASHBOARD) hostname ?: state.profile.host else nav.tab.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                // Persistent badge whenever the connection crosses the internet.
                                if (state.profile.mode == ConnectionMode.DIRECT) Pill("Direct", StatusColors.warn)
                                if (state.demo) Pill("Demo", StatusColors.info)
                            }
                        },
                        navigationIcon = {
                            if (route != null) IconButton(nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        },
                        actions = {
                            if (activeJobs > 0) {
                                IconButton({ nav.go(Tab.MORE, Route("jobs")) }) {
                                    BadgedBox(badge = { Badge { Text("$activeJobs") } }) {
                                        CircularProgressIndicator(Modifier.size(20.dp), color = p.topbarText, strokeWidth = 2.dp)
                                    }
                                }
                            }
                            IconButton(container.session::lock) { Icon(Icons.Default.Lock, "Lock now") }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = p.bg2, contentColor = p.fg1) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = nav.tab == t,
                                onClick = { nav.select(t) },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = p.primary,
                                    selectedTextColor = p.primary,
                                    indicatorColor = p.primary.copy(alpha = 0.16f),
                                    unselectedIconColor = p.muted,
                                    unselectedTextColor = p.muted,
                                ),
                            )
                        }
                    }
                },
            ) { inner ->
                val padding = inner.plus(12.dp)
                Box(Modifier.consumeWindowInsets(inner)) {
                    Screen(container, nav, padding)
                }
            }
        }
    }
}

@Composable
private fun Screen(container: AppContainer, nav: Navigator, padding: PaddingValues) {
    val route = nav.current
    if (route == null) {
        when (nav.tab) {
            Tab.DASHBOARD -> DashboardScreen(padding)
            Tab.STORAGE -> StorageScreen(padding)
            Tab.APPS -> AppsScreen(padding)
            Tab.ALERTS -> AlertsScreen(padding)
            Tab.MORE -> MoreScreen(padding)
        }
        return
    }
    val arg = route.arg.orEmpty()
    when (route.name) {
        "app" -> AppDetailScreen(arg, padding)
        "app-config" -> AppConfigScreen(arg, padding)
        "share-smb" -> SmbShareEditScreen(arg.toLongOrNull(), padding)
        "share-nfs" -> NfsShareEditScreen(arg.toLongOrNull(), padding)
        "app-logs" -> AppLogsScreen(arg.substringBefore(LOG_SEP), arg.substringAfter(LOG_SEP, ""), padding)
        "datasets" -> DatasetsScreen(padding)
        "dataset" -> DatasetScreen(arg, padding)
        "protection" -> ProtectionScreen(padding)
        "shares" -> SharesScreen(padding)
        "users" -> UsersScreen(padding)
        "containers" -> ContainersScreen(padding)
        "vms" -> VmsScreen(padding)
        "reporting" -> ReportingScreen(padding)
        "system" -> SystemScreen(padding)
        "services" -> ServicesScreen(padding)
        "update" -> UpdateScreen(padding)
        "bootenvs" -> BootEnvironmentsScreen(padding)
        "network" -> NetworkScreen(padding)
        "audit" -> AuditScreen(padding)
        "jobs" -> JobsScreen(padding)
        "settings" -> SettingsScreen(container, padding)
        else -> Text("Unknown screen ${route.name}")
    }
}

@Composable
fun PaddingValues.plus(extra: Dp): PaddingValues {
    val dir = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(dir) + extra,
        end = calculateEndPadding(dir) + extra,
        top = calculateTopPadding() + extra,
        bottom = calculateBottomPadding() + extra,
    )
}
