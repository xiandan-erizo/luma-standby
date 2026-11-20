package app.luma.standby.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.luma.standby.R
import app.luma.standby.domain.model.DisplayMode
import app.luma.standby.domain.model.FaceStyle
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.theme.currentTheme
import app.luma.standby.ui.clock.ClockView

@Composable
fun PreviewScreen(
    settings: LumaSettings,
    onStart: () -> Unit,
    onModeChange: (DisplayMode) -> Unit,
    onStyleChange: (FaceStyle) -> Unit = {},
) {
    val theme = remember(settings.themeId, settings.customThemes) { settings.currentTheme() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // The preview is the real renderer, not a mock: same ClockView, same
        // colours, same fonts, same date formatter as StandbyActivity.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> ClockView(ctx) },
                update = { view ->
                    view.applyState(
                        theme = theme,
                        use24HourOverride = settings.use24Hour,
                        showSecondsOverride = settings.showSeconds,
                        burnInShift = settings.burnInShift,
                        hint = null,
                    )
                },
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FaceStyle.entries.forEach { style ->
                FilterChip(
                    selected = theme.faceStyle == style,
                    onClick = { onStyleChange(style) },
                    label = { Text(styleLabel(style)) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DisplayMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.mode == mode,
                    onClick = { onModeChange(mode) },
                    label = { Text(modeLabel(mode)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart) { Text(stringResource(R.string.start_standby)) }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.preview_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun styleLabel(style: FaceStyle): String = stringResource(
    when (style) {
        FaceStyle.ANALOG -> R.string.style_analog
        FaceStyle.SPLIT -> R.string.style_split
        FaceStyle.FLOAT -> R.string.style_float
        FaceStyle.SOLAR -> R.string.style_solar
    },
)

@Composable
private fun modeLabel(mode: DisplayMode): String = stringResource(
    when (mode) {
        DisplayMode.STANDARD -> R.string.mode_standard
        DisplayMode.ULTRA_LOW_POWER -> R.string.mode_ultra
        DisplayMode.SHOWCASE -> R.string.mode_showcase
    },
)
