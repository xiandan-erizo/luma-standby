package app.luma.standby.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.luma.standby.R
import app.luma.standby.domain.NightMode
import app.luma.standby.domain.model.DisplayMode
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.LumaTheme
import app.luma.standby.domain.model.resolveColors
import app.luma.standby.domain.theme.ThemeEditing
import app.luma.standby.domain.theme.ThemeImport
import app.luma.standby.domain.theme.currentTheme
import app.luma.standby.domain.theme.themes

@Composable
fun ThemesScreen(
    settings: LumaSettings,
    onSelectTheme: (String) -> Unit,
    onChange: ((LumaSettings) -> LumaSettings) -> Unit,
    onSaveTheme: (LumaTheme) -> Unit,
    onDeleteTheme: (String) -> Unit,
    encodeTheme: (LumaTheme) -> String,
) {
    val themes = remember(settings) { settings.themes() }
    val selected = remember(settings) { settings.currentTheme() }
    val context = LocalContext.current

    var editorTarget by remember { mutableStateOf<LumaTheme?>(null) }
    var editorDeletable by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(encodeTheme(selected).toByteArray())
            } != null
        }.getOrDefault(false)
        toast(
            context,
            context.getString(
                if (ok) R.string.themes_exported_file else R.string.themes_export_failed,
            ),
        )
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        handleImported(context, text, onSaveTheme)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    editorTarget = ThemeEditing.duplicate(selected, System.currentTimeMillis())
                    editorDeletable = false
                }) { Text(stringResource(R.string.themes_duplicate_edit)) }

                OutlinedButton(
                    enabled = ThemeEditing.isCustom(selected),
                    onClick = {
                        editorTarget = selected
                        editorDeletable = true
                    },
                ) { Text(stringResource(R.string.themes_edit)) }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    copyToClipboard(context, "luma-theme", encodeTheme(selected))
                    toast(context, context.getString(R.string.themes_exported))
                }) { Text(stringResource(R.string.themes_export)) }

                TextButton(onClick = {
                    handleImported(context, readClipboard(context), onSaveTheme)
                }) { Text(stringResource(R.string.themes_import)) }

                TextButton(onClick = { exportLauncher.launch("luma-theme.json") }) {
                    Text(stringResource(R.string.themes_export_file))
                }

                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain"))
                }) { Text(stringResource(R.string.themes_import_file)) }
            }
        }

        items(themes, key = { it.id }) { theme ->
            ThemeRow(
                theme = theme,
                selected = theme.id == settings.themeId,
                onClick = { onSelectTheme(theme.id) },
            )
        }

        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }

        item {
            SectionTitle(stringResource(R.string.section_mode))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DisplayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.mode == mode,
                        onClick = { onChange { it.copy(mode = mode) } },
                        label = { Text(modeLabel(mode)) },
                    )
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.section_clock))
            TriStateRow(
                label = stringResource(R.string.setting_use_24h),
                value = settings.use24Hour,
                onValue = { value -> onChange { it.copy(use24Hour = value) } },
            )
            TriStateRow(
                label = stringResource(R.string.setting_show_seconds),
                value = settings.showSeconds,
                onValue = { value -> onChange { it.copy(showSeconds = value) } },
            )
            SwitchRow(
                label = stringResource(R.string.setting_burn_in),
                checked = settings.burnInShift,
                onChecked = { value -> onChange { it.copy(burnInShift = value) } },
            )
        }

        item {
            SectionTitle(stringResource(R.string.section_brightness))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.brightness < 0f,
                    onClick = { onChange { it.copy(brightness = -1f) } },
                    label = { Text(stringResource(R.string.brightness_mode_system)) },
                )
                FilterChip(
                    selected = settings.brightness >= 0f,
                    onClick = {
                        if (settings.brightness < 0f) {
                            onChange { it.copy(brightness = 0.35f) }
                        }
                    },
                    label = { Text(stringResource(R.string.brightness_mode_manual)) },
                )
            }
            Text(
                text = if (settings.brightness < 0f) {
                    stringResource(R.string.setting_brightness_system)
                } else {
                    stringResource(R.string.brightness_manual, (settings.brightness * 100).toInt())
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = if (settings.brightness < 0f) 0.35f else settings.brightness,
                enabled = settings.brightness >= 0f,
                onValueChange = { value ->
                    onChange { it.copy(brightness = value.coerceIn(0.01f, 1f)) }
                },
                valueRange = 0.01f..1f,
            )
        }

        item {
            SectionTitle(stringResource(R.string.section_night))
            SwitchRow(
                label = stringResource(R.string.setting_auto_night),
                checked = settings.autoNight,
                onChecked = { value -> onChange { it.copy(autoNight = value) } },
            )
            if (settings.autoNight) {
                NightTimeRow(
                    label = stringResource(R.string.setting_night_start),
                    minutes = settings.nightStartMinutes,
                    onMinutes = { value -> onChange { it.copy(nightStartMinutes = value) } },
                )
                NightTimeRow(
                    label = stringResource(R.string.setting_night_end),
                    minutes = settings.nightEndMinutes,
                    onMinutes = { value -> onChange { it.copy(nightEndMinutes = value) } },
                )
                Text(
                    text = stringResource(R.string.setting_night_level) + ": " +
                        (settings.nightBrightness * 100).toInt() + "%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = settings.nightBrightness.coerceIn(0.02f, 0.5f),
                    onValueChange = { value ->
                        onChange { it.copy(nightBrightness = value.coerceIn(0.02f, 0.5f)) }
                    },
                    valueRange = 0.02f..0.5f,
                )
                Text(
                    text = stringResource(
                        R.string.night_ramp_label,
                        settings.nightRampMinutes,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.nightRampMinutes.toFloat(),
                    onValueChange = { value ->
                        onChange { it.copy(nightRampMinutes = value.toInt() / 15 * 15) }
                    },
                    valueRange = 0f..120f,
                    steps = 7,
                )
            }
        }
    }

    val target = editorTarget
    if (target != null) {
        ThemeEditorDialog(
            initial = target,
            deletable = editorDeletable && ThemeEditing.isCustom(target),
            onDismiss = { editorTarget = null },
            onSave = { saved ->
                onSaveTheme(saved)
                editorTarget = null
                toast(context, context.getString(R.string.themes_saved, saved.name))
            },
            onDelete = {
                onDeleteTheme(target.id)
                editorTarget = null
                toast(context, context.getString(R.string.themes_deleted, target.name))
            },
        )
    }
}

private fun handleImported(
    context: Context,
    raw: String?,
    onSaveTheme: (LumaTheme) -> Unit,
) {
    if (raw.isNullOrBlank()) {
        toast(context, context.getString(R.string.themes_import_invalid))
        return
    }
    when (val result = ThemeImport.parse(raw)) {
        is ThemeImport.Result.Ok -> {
            val theme = result.theme.copy(
                id = ThemeEditing.customId(System.currentTimeMillis()),
                builtIn = false,
            )
            onSaveTheme(theme)
            toast(context, context.getString(R.string.themes_imported, theme.name))
        }

        is ThemeImport.Result.Rejected -> toast(
            context,
            context.getString(R.string.themes_import_failed, result.reason),
        )
    }
}

@Composable
private fun NightTimeRow(label: String, minutes: Int, onMinutes: (Int) -> Unit) {
    var text by remember(minutes) { mutableStateOf(NightMode.formatHhMm(minutes)) }
    val valid = NightMode.parseHhMm(text) != null
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                NightMode.parseHhMm(value)?.let(onMinutes)
            },
            singleLine = true,
            isError = !valid,
            modifier = Modifier.size(width = 120.dp, height = 56.dp),
        )
    }
}

@Composable
private fun ThemeRow(theme: LumaTheme, selected: Boolean, onClick: () -> Unit) {
    val colors = remember(theme) { theme.resolveColors() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(colors.background))
                .border(1.dp, Color(colors.primary), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Aa",
                color = Color(colors.primary),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = theme.background + " / " + theme.primary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun TriStateRow(label: String, value: Boolean?, onValue: (Boolean?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = value == null,
                onClick = { onValue(null) },
                label = { Text(stringResource(R.string.tri_system)) },
            )
            FilterChip(
                selected = value == true,
                onClick = { onValue(true) },
                label = { Text(stringResource(R.string.tri_on)) },
            )
            FilterChip(
                selected = value == false,
                onClick = { onValue(false) },
                label = { Text(stringResource(R.string.tri_off)) },
            )
        }
    }
}

@Composable
private fun modeLabel(mode: DisplayMode): String = stringResource(
    when (mode) {
        DisplayMode.STANDARD -> R.string.mode_standard
        DisplayMode.ULTRA_LOW_POWER -> R.string.mode_ultra
        DisplayMode.SHOWCASE -> R.string.mode_showcase
    },
)

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun readClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
