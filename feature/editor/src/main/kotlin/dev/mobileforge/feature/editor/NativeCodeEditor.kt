package dev.mobileforge.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Plain-text fallback for devices whose WebView cannot load Monaco. */
@Composable
fun NativeCodeEditor(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier = modifier) {
        Text("Basic editor · Update Android System WebView for Monaco features",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
        BasicTextField(
            value = state.content,
            onValueChange = viewModel::editNativeContent,
            readOnly = state.readOnly || state.isLoading,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp)
                .semantics { contentDescription = "Code editor" },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
        )
    }
}
