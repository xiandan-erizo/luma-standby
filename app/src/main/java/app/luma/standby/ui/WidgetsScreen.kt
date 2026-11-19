package app.luma.standby.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.luma.standby.R
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.StoredPlacement
import app.luma.standby.domain.widget.BuiltinWidgets
import app.luma.standby.domain.widget.WidgetPermission
import app.luma.standby.domain.widget.WidgetSlot
import app.luma.standby.platform.widgets.LumaNotificationListener

@Composable
fun WidgetsScreen(
    settings: LumaSettings,
    onChange: ((LumaSettings) -> LumaSettings) -> Unit,
) {
    val context = LocalContext.current
    val notificationAccess = remember(settings) { LumaNotificationListener.isEnabled(context) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.widgets_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BuiltinWidgets.all.forEach { widget ->
            item(key = widget.id) {
                val slot = settings.widgetPlacements
                    .firstOrNull { it.widgetId == widget.id }?.slot
                    ?.let { runCatching { WidgetSlot.valueOf(it) }.getOrNull() }

                WidgetRow(
                    title = widget.label,
                    subtitle = when (widget.permission) {
                        WidgetPermission.NONE -> null
                        WidgetPermission.NOTIFICATION_ACCESS -> {
                            if (notificationAccess) {
                                stringResource(R.string.widget_permission_granted)
                            } else {
                                stringResource(R.string.widget_permission_notification)
                            }
                        }

                        WidgetPermission.CALENDAR -> stringResource(R.string.widget_permission_calendar)
                        WidgetPermission.LOCATION -> stringResource(R.string.widget_permission_location)
                    },
                    slot = slot,
                    onSlot = { newSlot ->
                        onChange { current ->
                            current.copy(widgetPlacements = replacePlacement(current, widget.id, newSlot))
                        }
                    },
                    onOpenPermission = when (widget.permission) {
                        WidgetPermission.NOTIFICATION_ACCESS -> {
                            { LumaNotificationListener.openSettings(context) }
                        }

                        else -> null
                    },
                )
            }
        }

        item {
            Text(
                text = stringResource(R.string.widgets_weather_heading),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }

        item {
            WeatherCityEditor(
                city = settings.weatherCity,
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

@Composable
private fun WidgetRow(
    title: String,
    subtitle: String?,
    slot: WidgetSlot?,
    onSlot: (WidgetSlot?) -> Unit,
    onOpenPermission: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { onSlot(null) }, enabled = slot != null) {
                Text(stringResource(R.string.widgets_off))
            }
        }
        if (subtitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onOpenPermission != null) {
                    OutlinedButton(onClick = onOpenPermission) {
                        Text(stringResource(R.string.widget_permission_open))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetSlot.entries.forEach { candidate ->
                FilterChip(
                    selected = slot == candidate,
                    onClick = { onSlot(candidate) },
                    label = { Text(slotLabel(candidate)) },
                )
            }
        }
    }
}

@Composable
private fun WeatherCityEditor(
    city: String?,
    onApply: (String, Double, Double) -> Unit,
    onClear: () -> Unit,
) {
    var name by remember(city) { mutableStateOf(city.orEmpty()) }
    var lat by remember(city) { mutableStateOf("") }
    var lon by remember(city) { mutableStateOf("") }
    val latValue = lat.toDoubleOrNull()
    val lonValue = lon.toDoubleOrNull()
    val valid = name.isNotBlank() && latValue != null && lonValue != null &&
        latValue in -90.0..90.0 && lonValue in -180.0..180.0

    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.widgets_weather_city)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = lat,
                onValueChange = { lat = it },
                label = { Text(stringResource(R.string.widgets_weather_lat)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lon,
                onValueChange = { lon = it },
                label = { Text(stringResource(R.string.widgets_weather_lon)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row {
            OutlinedButton(
                enabled = valid,
                onClick = { onApply(name.trim(), latValue ?: 0.0, lonValue ?: 0.0) },
            ) { Text(stringResource(R.string.widgets_weather_apply)) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.widgets_weather_clear)) }
        }
        Text(
            text = stringResource(R.string.widgets_weather_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun replacePlacement(
    settings: LumaSettings,
    widgetId: String,
    slot: WidgetSlot?,
): List<StoredPlacement> {
    val others = settings.widgetPlacements.filterNot { it.widgetId == widgetId }
    return if (slot == null) others else others + StoredPlacement(widgetId, slot.name)
}

@Composable
private fun slotLabel(slot: WidgetSlot): String = stringResource(
    when (slot) {
        WidgetSlot.TOP_RIGHT -> R.string.widget_slot_top_right
        WidgetSlot.BOTTOM_RIGHT -> R.string.widget_slot_bottom_right
        WidgetSlot.BOTTOM_LEFT -> R.string.widget_slot_bottom_left
    },
)

