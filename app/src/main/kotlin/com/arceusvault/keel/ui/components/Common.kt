package com.arceusvault.keel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.arceusvault.keel.ui.theme.PlexMono
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Error(val message: String) : Loadable<Nothing>

    val valueOrNull: T? get() = (this as? Ready<T>)?.value
}

/** Holder for a loader that screens can trigger again, e.g. after an action. */
class Refresher {
    var tick by mutableIntStateOf(0)
        private set

    fun refresh() {
        tick++
    }
}

@Composable
fun rememberRefresher() = remember { Refresher() }

/**
 * Polls [load] every [periodMs] while the screen is visible and the app is in the foreground.
 * Keeps showing the last good value if a later refresh fails. [key] restarts loading.
 */
@Composable
fun <T> rememberPolled(key: Any?, periodMs: Long, refresher: Refresher? = null, load: suspend () -> T): Loadable<T> {
    var state by remember(key) { mutableStateOf<Loadable<T>>(Loadable.Loading) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, refresher?.tick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    state = Loadable.Ready(load())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (state !is Loadable.Ready) state = Loadable.Error(describeError(e))
                }
                delay(periodMs)
            }
        }
    }
    return state
}

/** Collects a server-pushed [Flow] while visible, restarting with a backoff if it fails. */
@Composable
fun <T> rememberLive(key: Any?, flow: () -> Flow<T>): Loadable<T> {
    var state by remember(key) { mutableStateOf<Loadable<T>>(Loadable.Loading) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var backoff = 2_000L
            while (true) {
                try {
                    flow().collect { state = Loadable.Ready(it); backoff = 2_000L }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (state !is Loadable.Ready) state = Loadable.Error(describeError(e))
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }
    return state
}

@Composable
fun <T> LoadableContent(state: Loadable<T>, content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Loadable.Error -> CenteredMessage("Couldn't load", state.message)
        is Loadable.Ready -> content(state.value)
    }
}

/** For dialogs and cards, where a full-screen spinner would be out of place. */
@Composable
fun <T> LoadableInline(state: Loadable<T>, content: @Composable (T) -> Unit) {
    when (state) {
        Loadable.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        is Loadable.Error -> Text(state.message, color = StatusColors.bad, style = MaterialTheme.typography.bodyMedium)
        is Loadable.Ready -> content(state.value)
    }
}

@Composable
fun CenteredMessage(title: String, body: String? = null, icon: ImageVector? = null, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let { Icon(it, null, Modifier.size(48.dp).padding(bottom = 12.dp), tint = StatusColors.muted) }
        Text(title, style = MaterialTheme.typography.titleMedium)
        body?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = StatusColors.muted, modifier = Modifier.padding(top = 8.dp))
        }
        action?.let { (label, onClick) -> Button(onClick, Modifier.padding(top = 16.dp)) { Text(label) } }
    }
}

/**
 * Keel's card: a flat rounded bg2 surface with a semibold title and a divider under the header.
 */
@Composable
fun TnCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Surface(
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = MaterialTheme.shapes.medium,
        color = p.bg2,
        contentColor = p.fg1,
    ) {
        Column {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted) }
                    }
                    actions?.invoke(this)
                }
                HorizontalDivider(color = p.lines)
            }
            Column(
                if (padded) Modifier.padding(16.dp) else Modifier,
                verticalArrangement = Arrangement.spacedBy(if (padded) 8.dp else 0.dp),
                content = content,
            )
        }
    }
}

/** Old name kept for screens that only need a titled card. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    TnCard(modifier, title = title) { content() }
}

/** A tappable row inside an unpadded [TnCard], separated by dividers. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
) {
    val p = LocalPalette.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            trailing?.invoke()
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), color = p.lines)
    }
}

/** Uppercase label above a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelMedium,
        color = LocalPalette.current.muted,
        modifier = modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.muted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = PlexMono) else it },
            modifier = Modifier.padding(start = 16.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A labelled bar; turns orange then red as it fills. */
@Composable
fun Meter(label: String, fraction: Double, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.muted)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        UsageBar(fraction)
    }
}

@Composable
fun UsageBar(fraction: Double, modifier: Modifier = Modifier) {
    val f = fraction.toFloat().coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { f },
        color = usageColor(f),
        trackColor = LocalPalette.current.lines,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = modifier.fillMaxWidth().height(6.dp),
    )
}

@Composable
fun usageColor(f: Float): Color = when {
    f > 0.9f -> StatusColors.bad
    f > 0.8f -> StatusColors.warn
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun statusColor(status: String?): Color = when (status?.uppercase(Locale.ROOT)) {
    "ONLINE", "RUNNING", "HEALTHY", "SUCCESS", "FINISHED", "ACTIVE", "UP", "LINK_STATE_UP" -> StatusColors.ok
    "DEGRADED", "WARNING", "DEPLOYING", "STOPPING", "WAITING", "PENDING", "SCANNING", "STARTING" -> StatusColors.warn
    "FAULTED", "UNAVAIL", "REMOVED", "ERROR", "CRITICAL", "ALERT", "EMERGENCY", "CRASHED", "FAILED", "ABORTED" -> StatusColors.bad
    "STOPPED", "OFFLINE", "EXITED", "DOWN", "LINK_STATE_DOWN", "HOLD", "NOTICE", "INFO" -> StatusColors.muted
    else -> StatusColors.info
}

fun formatBytes(bytes: Long?): String {
    bytes ?: return "n/a"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.ROOT, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

/** Network rates in bits per second, as the web UI shows them. */
fun formatBitRate(bytesPerSecond: Double): String {
    var v = bytesPerSecond * 8
    val units = listOf("b/s", "Kb/s", "Mb/s", "Gb/s")
    var i = 0
    while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
    return String.format(Locale.ROOT, if (v >= 100 || i == 0) "%.0f %s" else "%.1f %s", v, units[i])
}

fun formatPercent(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "n/a"

fun formatUptime(seconds: Double): String {
    val s = seconds.toLong()
    val d = s / 86_400
    val h = (s % 86_400) / 3_600
    val m = (s % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

/** "3 min ago", "2 days ago". */
fun formatAgo(t: Instant?, now: Instant = Instant.now()): String {
    t ?: return "never"
    val d = Duration.between(t, now)
    return when {
        d.isNegative -> "just now"
        d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        d.toDays() == 1L -> "1 day ago"
        d.toDays() < 60 -> "${d.toDays()} days ago"
        else -> "${d.toDays() / 30} months ago"
    }
}
