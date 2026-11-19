package app.luma.standby.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.luma.standby.R
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.StoredPlacement
import app.luma.standby.domain.theme.currentTheme
import app.luma.standby.domain.widget.LumaWidget
import app.luma.standby.domain.widget.WidgetData
import app.luma.standby.domain.widget.WidgetPermission
import app.luma.standby.domain.widget.WidgetSlot
import app.luma.standby.platform.widgets.LumaNotificationListener
import app.luma.standby.platform.widgets.SystemWidgetSource
import app.luma.standby.platform.widgets.WidgetRenderer
import app.luma.standby.ui.clock.ClockView

@Composable
fun WidgetsScreen(
    settings: LumaSettings,
    onChange: ((LumaSettings) -> LumaSettings) -> Unit,
) {
    val context = LocalContext.current
    // Bumped on every resume: re-reads notification grant and local preview data
    // once, without polling.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    val notificationAccess = remember(resumeTick) { LumaNotificationListener.isEnabled(context) }
    val widgets = remember { selectableWidgets }
    val visibleIds = remember(widgets) { widgets.map { it.id }.toSet() }
    val placements = settings.widgetPlacements.filter { it.widgetId in visibleIds }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "preview") { WidgetPreview(settings, resumeTick) }

        item(key = "intro") {
            Text(
                text = stringResource(R.string.widgets_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        widgets.forEach { widget ->
            item(key = widget.id) {
                val slot = placements.firstOrNull { it.widgetId == widget.id }?.slot?.toSlotOrNull()
                WidgetPanel(
                    widget = widget,
                    slot = slot,
                    notificationAccess = notificationAccess,
                    slotAvailable = { candidate -> hasRoom(placements, widget.id, candidate) },
                    onSlot = { newSlot ->
                        onChange { current ->
                            current.copy(widgetPlacements = replacePlacement(current, widget.id, newSlot))
                        }
                    },
                    onOpenPermission = { LumaNotificationListener.openSettings(context) },
                )
            }
        }

        item(key = "privacy") {
            Text(
                text = stringResource(R.string.widgets_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        item(key = "weather_heading") {
            Text(
                text = stringResource(R.string.widgets_weather_heading),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        item(key = "weather_editor") {
            WeatherLocationEditor(
                city = settings.weatherCity,
                lat = settings.weatherLat,
                lon = settings.weatherLon,
                onApply = { name, lat, lon ->
                    onChange { it.copy(weatherCity = name, weatherLat = lat, weatherLon = lon) }
                    Toast.makeText(context, context.getString(R.string.widgets_weather_saved), Toast.LENGTH_SHORT)
                        .show()
                },
                onClear = { onChange { it.copy(weatherCity = null, weatherLat = null, weatherLon = null) } },
            )
        }
    }
}

/** One Canvas: the real ClockView plus the real renderer, fed only local battery and alarm. */
@Composable
private fun WidgetPreview(settings: LumaSettings, resumeTick: Int) {
    val context = LocalContext.current
    val theme = remember(settings.themeId, settings.customThemes) { settings.currentTheme() }
    val source = remember(context) { SystemWidgetSource(context) }
    val data = remember(resumeTick) {
        WidgetData(
            battery = runCatching { source.battery() }.getOrNull(),
            nextAlarm = runCatching { source.nextAlarm() }.getOrNull(),
        )
    }
    val tiles = remember(settings, data) { WidgetRenderer.tiles(settings, data, context = context) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.widgets_preview_heading),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Rail-compressed face: fixed height first, then width-derived ratio.
                // aspectRatio alone wins over heightIn in landscape and clips the face.
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> ClockView(ctx) },
                update = { view ->
                    view.applyState(
                        theme = theme,
                        use24HourOverride = settings.use24Hour,
                        showSecondsOverride = false,
                        burnInShift = false,
                        hint = null,
                    )
                    view.setWidgets(tiles)
                },
                onRelease = { view -> view.setWidgets(emptyList()) },
            )
        }
        if (data.battery == null && data.nextAlarm == null) {
            Text(
                text = stringResource(R.string.widgets_preview_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetPanel(
    widget: LumaWidget,
    slot: WidgetSlot?,
    notificationAccess: Boolean,
    slotAvailable: (WidgetSlot) -> Boolean,
    onSlot: (WidgetSlot?) -> Unit,
    onOpenPermission: () -> Unit,
) {
    val title = widgetTitleRes(widget.id)?.let { stringResource(it) } ?: return
    val needsNotification = widget.permission == WidgetPermission.NOTIFICATION_ACCESS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = slot?.let { stringResource(slotTitleRes(it)) } ?: stringResource(R.string.widgets_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (slot != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (needsNotification && !notificationAccess) {
                OutlinedButton(onClick = onOpenPermission) {
                    Text(stringResource(R.string.widget_permission_open))
                }
            }
        }
        if (needsNotification) {
            Text(
                text = stringResource(
                    if (notificationAccess) R.string.widget_permission_granted else R.string.widget_permission_notification,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = slot == null,
                onClick = { onSlot(null) },
                label = { Text(stringResource(R.string.widgets_off)) },
            )
            WidgetSlot.entries.forEach { candidate ->
                val enabled = slot == candidate || slotAvailable(candidate)
                FilterChip(
                    selected = slot == candidate,
                    enabled = enabled,
                    onClick = { onSlot(candidate) },
                    label = {
                        Text(
                            if (enabled) stringResource(slotTitleRes(candidate))
                            else stringResource(slotTitleRes(candidate)) + " · " + stringResource(R.string.widgets_slot_full),
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeatherLocationEditor(
    city: String?,
    lat: Double?,
    lon: Double?,
    onApply: (String, Double, Double) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val activePreset = cityPresets.firstOrNull { it.matches(lat, lon) }
    var customOpen by rememberSaveable(city, lat, lon) {
        mutableStateOf(city != null && activePreset == null)
    }
    var name by rememberSaveable(city) { mutableStateOf(city.orEmpty()) }
    var latText by rememberSaveable(lat) { mutableStateOf(lat?.toString().orEmpty()) }
    var lonText by rememberSaveable(lon) { mutableStateOf(lon?.toString().orEmpty()) }
    val latValue = parseCoordinate(latText, 90.0)
    val lonValue = parseCoordinate(lonText, 180.0)
    val coordsTyped = latText.isNotBlank() || lonText.isNotBlank()
    val valid = name.isNotBlank() && latValue != null && lonValue != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (city != null) {
            Text(
                text = stringResource(R.string.widgets_weather_current, city),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cityPresets.forEach { preset ->
                val label = stringResource(preset.nameRes)
                FilterChip(
                    selected = preset == activePreset,
                    onClick = { onApply(context.getString(preset.nameRes), preset.lat, preset.lon) },
                    label = { Text(label) },
                )
            }
            FilterChip(
                selected = customOpen,
                onClick = { customOpen = !customOpen },
                label = { Text(stringResource(R.string.widgets_weather_custom)) },
            )
        }
        if (customOpen) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.widgets_weather_city)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text(stringResource(R.string.widgets_weather_lat)) },
                    isError = latText.isNotBlank() && latValue == null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text(stringResource(R.string.widgets_weather_lon)) },
                    isError = lonText.isNotBlank() && lonValue == null,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (coordsTyped && (latValue == null || lonValue == null)) {
                Text(
                    text = stringResource(R.string.widgets_weather_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                enabled = valid,
                onClick = {
                    val la = latValue ?: return@OutlinedButton
                    val lo = lonValue ?: return@OutlinedButton
                    onApply(name.trim(), la, lo)
                },
            ) { Text(stringResource(R.string.widgets_weather_apply)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.widgets_weather_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (city != null) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.widgets_weather_clear)) }
            }
        }
    }
}

private fun String.toSlotOrNull(): WidgetSlot? = runCatching { WidgetSlot.valueOf(this) }.getOrNull()

/** Whether [candidate] can take [widgetId] without exceeding the rendered capacity. */
private fun hasRoom(placements: List<StoredPlacement>, widgetId: String, candidate: WidgetSlot): Boolean {
    val others = placements.filter { it.widgetId != widgetId }.mapNotNull { it.slot.toSlotOrNull() }
    return if (candidate.isRight()) others.count { it.isRight() } < RIGHT_CAPACITY
    else others.count { !it.isRight() } < LEFT_CAPACITY
}

private fun replacePlacement(
    settings: LumaSettings,
    widgetId: String,
    slot: WidgetSlot?,
): List<StoredPlacement> {
    val others = settings.widgetPlacements.filterNot { it.widgetId == widgetId }
    return if (slot == null) others else others + StoredPlacement(widgetId, slot.name)
}