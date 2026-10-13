package com.arceusvault.keel.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.arceusvault.keel.data.LogLine
import com.arceusvault.keel.ui.LocalServer
import com.arceusvault.keel.ui.components.describeError
import com.arceusvault.keel.ui.theme.PlexMono
import com.arceusvault.keel.ui.theme.LocalPalette
import com.arceusvault.keel.ui.theme.StatusColors
import kotlin.coroutines.cancellation.CancellationException

private const val MAX_LINES = 3000

/**
 * Follows one container's log. Lines stay in memory only while this screen is open; nothing is
 * written to disk, and the window can't be screenshotted (FLAG_SECURE).
 */
@Composable
fun AppLogsScreen(app: String, containerId: String, padding: PaddingValues) {
    val api = LocalServer.current.api
    val p = LocalPalette.current
    val lines = remember(containerId) { mutableStateListOf<LogLine>() }
    var error by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var wrap by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(containerId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            lines.clear()
            try {
                api.appLogs(app, containerId).collect { line ->
                    lines.add(line)
                    if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describeError(e)
            }
        }
    }

    val shown = if (filter.isBlank()) lines else lines.filter { it.text.contains(filter, ignoreCase = true) }
    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(follow, { follow = !follow }, label = { Text("Follow") })
            FilterChip(wrap, { wrap = !wrap }, label = { Text("Wrap") })
            Text("${lines.size} lines", style = MaterialTheme.typography.bodySmall, color = StatusColors.muted)
        }
        OutlinedTextField(
            filter, { filter = it },
            placeholder = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = StatusColors.bad, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().weight(1f).background(p.topbar, MaterialTheme.shapes.small),
            contentPadding = PaddingValues(8.dp),
        ) {
            itemsIndexed(shown) { _, line ->
                Text(
                    line.text.trimEnd(),
                    fontFamily = PlexMono,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = logColor(line.text),
                    softWrap = wrap,
                    overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
                    maxLines = if (wrap) Int.MAX_VALUE else 1,
                )
            }
        }
    }
}

private val errorWords = Regex("\\b(ERROR|ERR|FATAL|FTL|CRIT|CRITICAL|PANIC|EXCEPTION)\\b|level=(error|fatal)", RegexOption.IGNORE_CASE)
private val warnWords = Regex("\\b(WARN|WARNING|WRN)\\b|level=warn", RegexOption.IGNORE_CASE)

@Composable
private fun logColor(text: String) = when {
    errorWords.containsMatchIn(text) -> StatusColors.bad
    warnWords.containsMatchIn(text) -> StatusColors.warn
    else -> LocalPalette.current.topbarText
}
