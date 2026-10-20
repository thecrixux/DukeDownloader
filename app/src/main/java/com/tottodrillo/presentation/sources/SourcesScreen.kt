package com.tottodrillo.presentation.sources

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tottodrillo.domain.model.Source
import java.io.File

/**
 * Schermata per gestire le sorgenti installate
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    sources: List<Source>,
    onNavigateBack: () -> Unit,
    onInstallSource: (File) -> Unit,
    onUninstallSource: (String) -> Unit,
    onToggleSource: (String, Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(com.tottodrillo.R.string.settings_sources)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(com.tottodrillo.R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (sources.isEmpty()) {
                EmptySourcesView()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sources) { source ->
                        SourceItem(
                            source = source,
                            onUninstall = { onUninstallSource(source.id) },
                            onToggle = { enabled -> onToggleSource(source.id, enabled) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySourcesView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(com.tottodrillo.R.string.sources_list_empty),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(com.tottodrillo.R.string.sources_no_sources_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SourceItem(
    source: Source,
    onUninstall: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    var showUninstallDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (source.description != null) {
                        Text(
                            text = source.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(com.tottodrillo.R.string.sources_version_label, source.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (source.author != null) {
                        Text(
                            text = stringResource(com.tottodrillo.R.string.settings_author_label, source.author.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Switch(
                    checked = true, // TODO: leggere da config
                    onCheckedChange = onToggle
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            TextButton(onClick = { showUninstallDialog = true }) {
                Text(stringResource(com.tottodrillo.R.string.sources_uninstall))
            }
        }
    }
    
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = { Text(stringResource(com.tottodrillo.R.string.sources_uninstall)) },
            text = { Text(stringResource(com.tottodrillo.R.string.source_uninstall_confirmation, source.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUninstall()
                        showUninstallDialog = false
                    }
                ) {
                    Text(stringResource(com.tottodrillo.R.string.sources_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) {
                    Text(stringResource(com.tottodrillo.R.string.cancel))
                }
            }
        )
    }
}
