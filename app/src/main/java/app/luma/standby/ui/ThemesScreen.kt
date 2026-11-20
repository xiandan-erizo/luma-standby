package app.luma.standby.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.luma.standby.R
import app.luma.standby.domain.NightMode
import app.luma.standby.domain.model.DisplayMode
import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.LumaTheme
import app.luma.standby.domain.model.resolveColors
import app.luma.standby.domain.theme.PalettePresets
import app.luma.standby.domain.theme.ThemeEditing
import app.luma.standby.domain.theme.currentTheme
import app.luma.standby.ui.clock.ClockView

private enum class ThemeTab { COLORS, FONTS }

@Composable
fun ThemesScreen(
    settings: LumaSettings,
    onChange: ((LumaSettings) -> LumaSettings) -> Unit,
    onSaveTheme: (LumaTheme) -> Unit,
    onDeleteTheme: (String) -> Unit,
    encodeTheme: (LumaTheme) -> String,
) {
    val current = remember(settings.themeId, settings.customThemes) { settings.currentTheme() }
    val colors = remember(current) { current.resolveColors() }
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var editorTarget by remember { mutableStateOf<LumaTheme?>(null) }
    var editorDeletable by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(encodeTheme(current).toByteArray())
            } != null
        }.getOrDefault(false)
        toast(
            context,
            context.getString(if (ok) R.string.themes_exported_file else R.string.themes_export_failed),
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
    ) {
        item {
            // Live preview: the real renderer, so the palette and the typeface are
            // judged on the same pixels the clock will use.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Rail-compressed face: fixed height first, then width-derived ratio.
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(2.17f),
                    factory = { ctx -> ClockView(ctx) },
                    update = { view ->
                        view.applyState(
                            theme = current,
                            use24HourOverride = settings.use24Hour,
                            showSecondsOverride = settings.showSeconds,
                            burnInShift = settings.burnInShift,
                            hint = null,
                        )
                    },
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                app.luma.standby.domain.model.FaceStyle.entries.forEach { style ->
                    FilterChip(
                        selected = current.faceStyle == style,
                        onClick = {
                            onSaveTheme(
                                ThemeEditing.withFaceStyle(
                                    settings,
                                    style,
                                    System.currentTimeMillis(),
                                ),
                            )
                        },
                        label = {
                            Text(
                                stringResource(
                                    when (style) {
                                        app.luma.standby.domain.model.FaceStyle.ANALOG -> R.string.style_analog
                                        app.luma.standby.domain.model.FaceStyle.SPLIT -> R.string.style_split
                                        app.luma.standby.domain.model.FaceStyle.FLOAT -> R.string.style_float
                                        app.luma.standby.domain.model.FaceStyle.SOLAR -> R.string.style_solar
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = tab == ThemeTab.COLORS.ordinal,
                    onClick = { tab = ThemeTab.COLORS.ordinal },
                    label = { Text(stringResource(R.string.section_colors)) },
                )
                FilterChip(
                    selected = tab == ThemeTab.FONTS.ordinal,
                    onClick = { tab = ThemeTab.FONTS.ordinal },
                    label = { Text(stringResource(R.string.section_fonts)) },
                )
            }
        }

        if (tab == ThemeTab.COLORS.ordinal) {
            item {
                // Grouped like the reference sheet: dark surfaces, OLED near-black,
                // then light. Twelve swatches in one flat grid was unreadable.
                val rows = PalettePresets.all.chunked(6)
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            row.forEach { palette ->
                                PaletteSwatch(
                                    palette = palette,
                                    current = current,
                                    onClick = {
                                        onSaveTheme(
                                            ThemeEditing.withPalette(
                                                settings,
                                                palette,
                                                System.currentTimeMillis(),
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    FontChoice.entries.forEach { choice ->
                        FontCard(
                            choice = choice,
                            colors = colors,
                            selected = current.font == choice,
                            onClick = {
                                onSaveTheme(
                                    ThemeEditing.withFont(settings, choice, System.currentTimeMillis()),
                                )
                            },
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    editorTarget = ThemeEditing.duplicate(current, System.currentTimeMillis())
                    editorDeletable = false
                }) { Text(stringResource(R.string.themes_duplicate_edit)) }

                OutlinedButton(
                    enabled = ThemeEditing.isCustom(current),
                    onClick = {
                        editorTarget = current
                        editorDeletable = true
                    },
                ) { Text(stringResource(R.string.themes_edit)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    copyToClipboard(context, "luma-theme", encodeTheme(current))
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

        item { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }

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
                        if (settings.brightness < 0f) onChange { it.copy(brightness = 0.1f) }
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
                value = if (settings.brightness < 0f) 0.1f else settings.brightness,
                enabled = settings.brightness >= 0f,
                onValueChange = { value ->
                    // The platform caps app brightness near 0.10 on this build, so
                    // the slider's useful travel is 0.01..0.30, not 0..1.
                    onChange { it.copy(brightness = value.coerceIn(0.01f, 0.3f)) }
                },
                valueRange = 0.01f..0.3f,
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
                    value = settings.nightBrightness.coerceIn(0.02f, 0.3f),
                    onValueChange = { value ->
                        onChange { it.copy(nightBrightness = value.coerceIn(0.02f, 0.3f)) }
                    },
                    valueRange = 0.02f..0.3f,
                )
                Text(
                    text = stringResource(R.string.night_ramp_label, settings.nightRampMinutes),
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

@Composable
private fun PaletteSwatch(palette: LumaTheme, current: LumaTheme, onClick: () -> Unit) {
    val colors = remember(palette) { palette.resolveColors() }
    val selected = current.background.equals(palette.background, ignoreCase = true) &&
        current.primary.equals(palette.primary, ignoreCase = true) &&
        current.accent.equals(palette.accent, ignoreCase = true)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(6.dp).clickable(onClick = onClick),
    ) {
        Canvas(Modifier.size(58.dp)) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val bounds = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
            drawCircle(Color(colors.background), radius = radius, center = center)
            val primaryArc = Path().apply {
                moveTo(center.x, center.y)
                arcTo(bounds, -45f, 120f, false)
                close()
            }
            val accentArc = Path().apply {
                moveTo(center.x, center.y)
                arcTo(bounds, 75f, 110f, false)
                close()
            }
            drawPath(primaryArc, Color(colors.primary))
            drawPath(accentArc, Color(colors.accent))
            drawCircle(
                color = if (selected) Color(0xFFEDE9E2) else Color(0x66FFFFFF),
                radius = radius,
                center = center,
                style = Stroke(width = if (selected) 5f else 2f),
            )
        }
        Text(
            text = palette.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(92.dp).padding(top = 4.dp),
        )
    }
}

@Composable
private fun FontCard(
    choice: FontChoice,
    colors: app.luma.standby.domain.model.ResolvedColors,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(colors.background))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { ctx ->
                TextView(ctx).apply {
                    includeFontPadding = false
                    textSize = 34f
                }
            },
            update = { view ->
                view.typeface = app.luma.standby.ui.clock.LumaFonts.resolve(view.context, choice)
                view.text = "19:25"
                view.setTextColor(colors.primary)
            },
        )
        Text(
            text = stringResource(when (choice) {
                FontChoice.DISPLAY -> R.string.font_display
                FontChoice.CONDENSED -> R.string.font_condensed
                FontChoice.LIGHT -> R.string.font_light
                FontChoice.ROUNDED -> R.string.font_rounded
                FontChoice.SANS -> R.string.font_sans
                FontChoice.SERIF -> R.string.font_serif
                FontChoice.MONO -> R.string.font_mono
            }),
            style = MaterialTheme.typography.labelMedium,
            color = Color(colors.secondary),
        )
        if (selected) {
            Text(
                text = "  ✓",
                style = MaterialTheme.typography.labelLarge,
                color = Color(colors.accent),
            )
        }
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
    when (val result = app.luma.standby.domain.theme.ThemeImport.parse(raw)) {
        is app.luma.standby.domain.theme.ThemeImport.Result.Ok -> {
            val theme = result.theme.copy(
                id = ThemeEditing.customId(System.currentTimeMillis()),
                builtIn = false,
            )
            onSaveTheme(theme)
            toast(context, context.getString(R.string.themes_imported, theme.name))
        }

        is app.luma.standby.domain.theme.ThemeImport.Result.Rejected -> toast(
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