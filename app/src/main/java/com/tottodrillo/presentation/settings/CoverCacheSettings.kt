package com.tottodrillo.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tottodrillo.R
import com.tottodrillo.data.covers.CoverDiskCache
import com.tottodrillo.presentation.components.FrontCoverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun CoverCacheSettings() {
    val context = LocalContext.current.applicationContext
    val resolver = remember(context) { EntryPointAccessors.fromApplication(context, FrontCoverEntryPoint::class.java).frontCovers() }
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<CoverDiskCache.Usage?>(null) }
    var keep by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var limits by remember { mutableStateOf(false) }
    fun update(operation: suspend () -> CoverDiskCache.Usage) {
        if (busy) return
        busy = true; error = false
        scope.launch {
            try { usage = operation() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(resolver) { update { resolver.cacheUsage() } }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.cover_cache_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        usage?.let { current ->
            Text(stringResource(R.string.cover_cache_usage, current.count, String.format(Locale.getDefault(), "%.1f", current.bytes / 1048576.0)))
            Box {
                OutlinedButton(enabled = !busy, onClick = { limits = true }) {
                    Text(stringResource(R.string.cover_cache_limit, current.limitMiB))
                }
                DropdownMenu(expanded = limits, onDismissRequest = { limits = false }) {
                    listOf(64, 128, 256, 512, 1024).forEach { mib ->
                        DropdownMenuItem(text = { Text("$mib MiB") }, onClick = {
                            limits = false; update { resolver.cacheLimit(mib) }
                        })
                    }
                }
            }
            if (current.byPlatform.isNotEmpty()) {
                Text(stringResource(R.string.cover_cache_keep), style = MaterialTheme.typography.labelLarge)
                current.byPlatform.forEach { (platform, bytes) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        keep = if (platform in keep) keep - platform else keep + platform
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = platform in keep, enabled = !busy, onCheckedChange = { checked ->
                            keep = if (checked) keep + platform else keep - platform
                        })
                        Text(platform.uppercase(Locale.ROOT), modifier = Modifier.weight(1f))
                        Text(String.format(Locale.getDefault(), "%.1f MiB", bytes / 1048576.0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && current.count > 0, onClick = { confirm = true }) { Text(stringResource(R.string.cover_cache_clear)) }
                TextButton(enabled = !busy, onClick = { update { resolver.cacheUsage() } }) { Text(stringResource(R.string.cover_cache_refresh)) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error) Text(stringResource(R.string.cover_cache_error), color = MaterialTheme.colorScheme.error)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.cover_cache_title)) },
        text = { Text(stringResource(R.string.cover_cache_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = false; update { resolver.clearCovers(keep.toSet()) } }) { Text(stringResource(R.string.cover_cache_clear)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cover_cache_cancel)) } })
}
