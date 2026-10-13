package com.arceusvault.keel.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.arceusvault.keel.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.core.network.RawApiKey
import com.arceusvault.keel.data.AlertWatch
import com.arceusvault.keel.domain.ServerProfile
import com.arceusvault.keel.ui.components.KeyValue
import com.arceusvault.keel.ui.components.TnCard
import com.arceusvault.keel.ui.components.describeError
import com.arceusvault.keel.ui.components.formatAgo
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.coroutines.launch
import java.time.Instant

private val levelChoices = listOf("WARNING" to "Warnings and worse", "ERROR" to "Errors and worse", "CRITICAL" to "Critical only")

@Composable
fun AlertNotificationsCard(watch: AlertWatch, profile: ServerProfile) {
    val enabled by watch.enabled.collectAsState()
    var setup by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val ctx = LocalContext.current

    TnCard(title = "Alert notifications", subtitle = if (enabled) "On, checked every 15 minutes" else "Off") {
        if (!enabled) {
            Text(
                "Get notified about new server alerts while the app is closed. This needs a second API key that can only " +
                    "read alerts, because your main key is locked behind your fingerprint and can't be used in the background.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "The alert key is stored without a fingerprint lock. Anyone who extracted it could read your alert text and " +
                    "nothing else; the app refuses keys that can do more.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.muted,
            )
            OutlinedButton({ setup = true }) { Text("Set up") }
            return@TnCard
        }
        key(refresh) {
            if (!watch.canNotify()) {
                Text("Notifications are blocked for this app.", color = StatusColors.warn, style = MaterialTheme.typography.bodySmall)
                TextButton({ askPermission(permission::launch, ctx) }) { Text("Allow notifications") }
            }
            KeyValue("Last check", if (watch.lastCheck == 0L) "not yet" else formatAgo(Instant.ofEpochMilli(watch.lastCheck)))
            watch.lastError?.let { Text("Last check failed: $it", color = StatusColors.warn, style = MaterialTheme.typography.bodySmall) }
            levelChoices.forEach { (level, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(watch.minLevel == level) { watch.minLevel = level; refresh++ },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(watch.minLevel == level, null)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ scope.launch { runCatching { watch.check() }; refresh++ } }) { Text("Check now") }
                TextButton(watch::disable) { Text("Turn off", color = StatusColors.bad) }
            }
        }
    }

    if (setup) {
        SetupDialog(watch, profile, onDone = {
            setup = false
            if (!watch.canNotify()) askPermission(permission::launch, ctx)
        }, onDismiss = { setup = false })
    }
}

/** Android 13+ asks at runtime; older versions only have the per-app switch in system settings. */
private fun askPermission(request: (String) -> Unit, ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request(Manifest.permission.POST_NOTIFICATIONS)
    else ctx.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun SetupDialog(watch: AlertWatch, profile: ServerProfile, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = RawApiKey.parse(apiKey)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Alert-only API key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "In TrueNAS: create a privilege with only the ALERT_LIST_READ role, a group with that privilege, and a " +
                        "user in that group. Then create an API key for that user and paste it here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(username, { username = it.trim() }, label = { Text("Username") }, singleLine = true, enabled = !busy)
                OutlinedTextField(
                    apiKey, { apiKey = it.trim() },
                    label = { Text("API key") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = apiKey.isNotEmpty() && parsed == null,
                )
                Text("Connects to ${profile.displayAddress} with the same certificate pin.", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
                error?.let { Text(it, color = StatusColors.bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && username.isNotBlank() && parsed != null,
                onClick = {
                    val raw = RawApiKey.parse(apiKey) ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            watch.enable(profile, username, raw)
                            apiKey = ""
                            onDone()
                        } catch (e: Exception) {
                            error = describeError(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Checking..." else "Turn on") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
