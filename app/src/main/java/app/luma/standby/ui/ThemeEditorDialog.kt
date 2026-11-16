package app.luma.standby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.luma.standby.R
import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaTheme
import app.luma.standby.domain.theme.ThemeEditing

@Composable
fun ThemeEditorDialog(
    initial: LumaTheme,
    deletable: Boolean,
    onDismiss: () -> Unit,
    onSave: (LumaTheme) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val problems = ThemeEditing.validateAll(draft)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(stringResource(R.string.editor_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorField(stringResource(R.string.editor_background), draft.background) {
                    draft = draft.copy(background = it)
                }
                ColorField(stringResource(R.string.editor_primary), draft.primary) {
                    draft = draft.copy(primary = it)
                }
                ColorField(stringResource(R.string.editor_secondary), draft.secondary) {
                    draft = draft.copy(secondary = it)
                }
                ColorField(stringResource(R.string.editor_accent), draft.accent) {
                    draft = draft.copy(accent = it)
                }

                Text(
                    text = stringResource(R.string.editor_font),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row {
                    FontChip(FontChoice.ROUNDED, draft.font, R.string.font_rounded) {
                        draft = draft.copy(font = it)
                    }
                    FontChip(FontChoice.SANS, draft.font, R.string.font_sans) {
                        draft = draft.copy(font = it)
                    }
                }
                Row {
                    FontChip(FontChoice.SERIF, draft.font, R.string.font_serif) {
                        draft = draft.copy(font = it)
                    }
                    FontChip(FontChoice.MONO, draft.font, R.string.font_mono) {
                        draft = draft.copy(font = it)
                    }
                }

                SwitchLine(stringResource(R.string.editor_gradient), draft.gradient) {
                    draft = draft.copy(gradient = it)
                }
                if (draft.gradient) {
                    ColorField(stringResource(R.string.editor_gradient_end), draft.gradientEnd) {
                        draft = draft.copy(gradientEnd = it)
                    }
                }

                SwitchLine(stringResource(R.string.editor_show_seconds), draft.showSeconds) {
                    draft = draft.copy(showSeconds = it)
                }

                if (problems.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.editor_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problems.isEmpty(),
                onClick = { onSave(draft) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (deletable) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun FontChip(
    choice: FontChoice,
    current: FontChoice,
    labelRes: Int,
    onChange: (FontChoice) -> Unit,
) {
    FilterChip(
        selected = current == choice,
        onClick = { onChange(choice) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ColorField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = ThemeEditing.validateHex(value) == null,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
