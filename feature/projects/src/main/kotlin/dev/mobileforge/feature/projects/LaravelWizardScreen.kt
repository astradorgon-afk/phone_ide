package dev.mobileforge.feature.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mobileforge.core.designsystem.icon.ForgeIcons
import dev.mobileforge.core.designsystem.theme.LocalForgeCodeTypography
import dev.mobileforge.core.model.WorkspaceId

/**
 * Guided Laravel creation: check, consent, download, finish.
 *
 * The CONFIRM screen is the point — it states the exact command, the download size and
 * the time cost before anything runs, because `create-project` executes third-party
 * install scripts and that consent must be explicit, not implied by tapping Create.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaravelWizardScreen(
    viewModel: LaravelWizardViewModel,
    projectName: String,
    onOpenWorkspace: (WorkspaceId) -> Unit,
    onOpenToolchains: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Leaving mid-download would orphan the process; Stop first, then back out.
    BackHandler(enabled = state.running) { }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("New Laravel project") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.running) {
                        Icon(ForgeIcons.Back, contentDescription = "Back to projects")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.phase) {
                WizardPhase.CHECKS -> {
                    if (state.checks.isEmpty()) {
                        item { CircularProgressIndicator() }
                    } else {
                        item {
                            Text(
                                "Step 1 of 2 — checks. “$projectName” needs a few things " +
                                    "before anything downloads.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        items(state.checks, key = { it.label }) { check ->
                            CheckRow(check = check)
                        }
                        val missingTools = state.checks.any {
                            !it.passed && (it.label == "PHP toolchain" || it.label == "Composer")
                        }
                        if (missingTools) {
                            item {
                                Button(onClick = onOpenToolchains) {
                                    Text("Install missing tools")
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = viewModel::recheck) {
                                Text("Check again")
                            }
                        }
                    }
                }

                WizardPhase.CONFIRM -> {
                    item {
                        Text(
                            "Step 2 of 2 — confirm. This downloads the real Laravel " +
                                "framework — app, routes, config and vendor — about 200 MB " +
                                "over the network, taking several minutes. Its install " +
                                "scripts will run as part of the download. Afterwards the " +
                                "wizard copies .env, generates the app key and opens the " +
                                "project; nothing else asks you anything.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        Text(
                            "composer create-project laravel/laravel $projectName",
                            style = LocalForgeCodeTypography.current.code,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        Button(onClick = viewModel::start) {
                            Text("Download and create")
                        }
                    }
                    item {
                        OutlinedButton(onClick = onBack) { Text("Cancel") }
                    }
                }

                WizardPhase.RUNNING -> {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                state.stepLabel ?: "Working…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    item {
                        Button(onClick = viewModel::stop) { Text("Stop") }
                    }
                    itemsIndexed(state.log, key = { index, _ -> index }) { _, line ->
                        Text(
                            line,
                            style = LocalForgeCodeTypography.current.codeSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                WizardPhase.DONE -> {
                    item {
                        Text(
                            "“$projectName” is ready — framework, .env and application key " +
                                "all in place.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    item {
                        Button(onClick = {
                            state.workspaceId?.let(onOpenWorkspace)
                        }) { Text("Open project") }
                    }
                }

                WizardPhase.FAILED -> {
                    state.error?.let { error ->
                        item {
                            Text(
                                error.message,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        error.detail?.let {
                            item {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        error.recovery?.let {
                            item { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    if (state.log.isNotEmpty()) {
                        item { HorizontalDivider() }
                        val tail = state.log.takeLast(30)
                        itemsIndexed(tail, key = { index, _ -> index }) { _, line ->
                            Text(
                                line,
                                style = LocalForgeCodeTypography.current.codeSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        OutlinedButton(onClick = onBack) { Text("Back to projects") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: WizardCheck) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (check.passed) ForgeIcons.CheckCircle else ForgeIcons.Warning,
            contentDescription = null,
            tint = if (check.passed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.titleMedium)
            Text(
                check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
