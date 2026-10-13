package com.arceusvault.keel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.arceusvault.keel.data.Action
import com.arceusvault.keel.data.Tier
import com.arceusvault.keel.ui.LocalAuthenticator
import com.arceusvault.keel.ui.ServerContext
import com.arceusvault.keel.ui.theme.PlexMono
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.coroutines.launch

/** Entry point for every write: checks the key's roles and walks the user through the action's [Tier]. */
class ActionLauncher internal constructor(private val server: ServerContext) {
    internal var pending by mutableStateOf<Pair<Action, () -> Unit>?>(null)

    /** False when the key's roles can't perform [action]; screens hide the control then. */
    fun can(action: Action) = action.allowedFor(server.roles)

    fun launch(action: Action, onSuccess: () -> Unit = {}) {
        if (action.tier == Tier.BENIGN) server.operations.run(server.api, action, onSuccess)
        else pending = action to onSuccess
    }

    internal fun confirm(action: Action, onSuccess: () -> Unit) {
        pending = null
        server.operations.run(server.api, action, onSuccess)
    }
}

val LocalActions = staticCompositionLocalOf<ActionLauncher> { error("No ActionHost") }

@Composable
fun ActionHost(server: ServerContext, content: @Composable () -> Unit) {
    val launcher = remember(server) { ActionLauncher(server) }
    CompositionLocalProvider(LocalActions provides launcher) {
        content()
        launcher.pending?.let { (action, onSuccess) ->
            ConfirmDialog(action, onDismiss = { launcher.pending = null }, onConfirmed = { launcher.confirm(action, onSuccess) })
        }
    }
}

@Composable
private fun ConfirmDialog(action: Action, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    val auth = LocalAuthenticator.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    val needsTyping = action.tier == Tier.CRITICAL && action.typeToConfirm != null
    val ready = !needsTyping || typed.trim() == action.typeToConfirm
    val dangerous = action.tier >= Tier.DESTRUCTIVE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                action.explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (needsTyping) {
                    Text(
                        buildAnnotatedString {
                            append("Type ")
                            withStyle(SpanStyle(fontFamily = PlexMono)) { append(action.typeToConfirm) }
                            append(" to confirm.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (dangerous) {
                    Text(
                        "You'll be asked to unlock with your fingerprint or PIN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.muted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    if (!dangerous) onConfirmed()
                    else scope.launch { if (auth.authenticate(action.title)) onConfirmed() }
                },
            ) {
                Text(
                    action.confirmLabel,
                    color = when {
                        !ready -> StatusColors.muted.copy(alpha = 0.5f)
                        dangerous -> StatusColors.bad
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Whether [action] is currently running, for disabling its button. */
@Composable
fun rememberRunning(server: ServerContext): Set<String> = server.operations.running.collectAsState().value
