package dev.mobileforge.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.component.EmptyState
import dev.mobileforge.core.designsystem.component.ErrorBanner
import dev.mobileforge.core.designsystem.icon.ForgeIcons
import dev.mobileforge.core.designsystem.theme.LocalForgeCodeTypography
import dev.mobileforge.runtime.laravel.EnvFile

/**
 * Structured `.env` editing.
 *
 * Secrets stay masked until the user taps Show on that specific entry — the brief forbids
 * displaying them automatically, and a masked row is deliberately not a text field, so the
 * mask itself can never be edited into the value and saved back over the real secret.
 *
 * A missing `.env` is reported plainly with the honest next step, never a create button that
 * would pretend scaffolding exists: copying `.env.example` is a terminal task for now.
 */
@Composable
fun EnvPane(
    viewModel: EnvViewModel,
    readOnly: Boolean,
    hasEnvExample: Boolean,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Loads once; later calls only refresh readOnly/hasEnvExample without re-reading secrets.
    LaunchedEffect(readOnly, hasEnvExample) {
        viewModel.open(readOnly = readOnly, hasEnvExample = hasEnvExample)
    }
    val savedRevision = state.savedRevision
    LaunchedEffect(savedRevision) {
        if (savedRevision > 0) onSaved()
    }

    if (!state.opened || state.isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        state.error?.let { ErrorBanner(it, onDismiss = viewModel::dismissError) }

        if (!state.found) {
            EmptyState(
                icon = ForgeIcons.Edit,
                title = "No .env file",
                message = if (state.hasEnvExample) {
                    "This project has no .env file. Copy .env.example to .env " +
                        "from the terminal to create one."
                } else {
                    "This project has no .env file."
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SaveBar(
                isDirty = state.isDirty,
                isSaving = state.isSaving,
                readOnly = state.readOnly,
                onSave = viewModel::save,
            )
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.entries, key = { it.key }) { entry ->
                    EnvRow(
                        entry = entry,
                        revealed = entry.key in state.revealed,
                        readOnly = state.readOnly,
                        onReveal = { viewModel.reveal(entry.key) },
                        onConceal = { viewModel.conceal(entry.key) },
                        onEdit = { viewModel.setValue(entry.key, it) },
                    )
                    HorizontalDivider()
                }
                item {
                    AddRow(viewModel = viewModel, readOnly = state.readOnly)
                }
            }
        }
    }
}

@Composable
private fun SaveBar(isDirty: Boolean, isSaving: Boolean, readOnly: Boolean, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = ".env",
            style = LocalForgeCodeTypography.current.code,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isDirty) {
            Text(
                text = "Unsaved changes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (isSaving) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Button(onClick = onSave, enabled = isDirty && !readOnly) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun EnvRow(
    entry: EnvFile.Entry,
    revealed: Boolean,
    readOnly: Boolean,
    onReveal: () -> Unit,
    onConceal: () -> Unit,
    onEdit: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.key,
                style = LocalForgeCodeTypography.current.code,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (entry.isSensitive) {
                Text(
                    text = "secret",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { if (revealed) onConceal() else onReveal() }) {
                    Text(if (revealed) "Hide" else "Show")
                }
            }
        }
        if (entry.isSensitive && !revealed) {
            Text(
                text = entry.maskedValue,
                style = LocalForgeCodeTypography.current.code,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = entry.value,
                onValueChange = onEdit,
                enabled = !readOnly,
                singleLine = true,
                textStyle = LocalForgeCodeTypography.current.code,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AddRow(viewModel: EnvViewModel, readOnly: Boolean) {
    var newKey by rememberSaveable { mutableStateOf("") }
    var newValue by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Add a key",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = newKey,
            onValueChange = { newKey = it },
            enabled = !readOnly,
            label = { Text("Key") },
            singleLine = true,
            textStyle = LocalForgeCodeTypography.current.code,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newValue,
            onValueChange = { newValue = it },
            enabled = !readOnly,
            label = { Text("Value") },
            singleLine = true,
            textStyle = LocalForgeCodeTypography.current.code,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // Validation runs synchronously in the view model, so a clean error state right
            // after the call means the entry landed and the fields can reset.
            Button(onClick = {
                viewModel.addEntry(newKey, newValue)
                if (viewModel.state.value.error == null) {
                    newKey = ""
                    newValue = ""
                }
            }, enabled = !readOnly && newKey.isNotBlank()) {
                Text("Add")
            }
        }
    }
}
