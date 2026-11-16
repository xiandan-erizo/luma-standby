package app.luma.standby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.luma.standby.R
import app.luma.standby.platform.DeviceReport
import app.luma.standby.platform.display.RefreshSample
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    report: DeviceReport?,
    sampling: Boolean,
    sampleProgress: Float,
    sample: RefreshSample?,
    onRefresh: () -> Unit,
    onStartSample: () -> Unit,
) {
    // Resolved once, outside the LazyColumn content lambda (which is a plain
    // LazyListScope lambda, not a composable one).
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    val bool: (Boolean) -> String = { if (it) yes else no }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.diag_refresh)) }
        }

        if (report == null) {
            item { Text(stringResource(R.string.diag_collecting), modifier = Modifier.padding(top = 16.dp)) }
            return@LazyColumn
        }

        item { Section(stringResource(R.string.diag_device)) }
        item { Row2(stringResource(R.string.diag_app), report.appVersion) }
        item {
            Row2(
                stringResource(R.string.diag_android),
                report.androidRelease + " (API " + report.apiLevel + ")",
            )
        }
        item { Row2(stringResource(R.string.diag_patch), report.securityPatch) }
        item { Row2(stringResource(R.string.diag_soc), report.socLabel()) }
        item {
            Row2(
                stringResource(R.string.diag_hyperos_version),
                report.hyperOs.osVersionName.ifEmpty { "—" },
            )
        }
        item { Row2(stringResource(R.string.diag_looks_hyperos), bool(report.hyperOs.looksLikeHyperOs)) }

        val display = report.display
        item { Section(stringResource(R.string.diag_display)) }
        if (display == null) {
            item { Row2(stringResource(R.string.diag_display), stringResource(R.string.diag_unavailable)) }
        } else {
            item {
                Row2(
                    stringResource(R.string.diag_resolution),
                    display.width.toString() + " × " + display.height,
                )
            }
            item {
                Row2(
                    stringResource(R.string.diag_density),
                    display.densityDpi.toString() + " dpi",
                )
            }
            item {
                Row2(
                    stringResource(R.string.diag_current_refresh),
                    formatFloat(display.currentRefreshRate) + " Hz",
                )
            }
            item {
                Row2(
                    stringResource(R.string.diag_supported_rates),
                    display.supportedRefreshRates.joinToString(" · ") { formatFloat(it) },
                )
            }
            item {
                Row2(
                    stringResource(R.string.diag_arr),
                    display.arrSupported?.let { bool(it) }
                        ?: stringResource(R.string.diag_arr_unknown),
                )
            }
            item {
                Row2(
                    stringResource(R.string.diag_suggested_fps),
                    display.suggestedFrameRate?.let { formatFloat(it) } ?: "—",
                )
            }
            item {
                Row2(
                    stringResource(R.string.diag_modes),
                    display.modes.joinToString(" · ") {
                        it.id.toString() + "@" + formatFloat(it.refreshRate)
                    },
                )
            }
            item { Row2(stringResource(R.string.diag_rotation), display.rotation.toString()) }
        }

        item { Section(stringResource(R.string.diag_sample_title)) }
        item {
            OutlinedButton(onClick = onStartSample, enabled = !sampling) {
                Text(
                    if (sampling) {
                        stringResource(R.string.diag_sample_running)
                    } else {
                        stringResource(R.string.diag_sample_start)
                    },
                )
            }
        }
        if (sampling) {
            item {
                LinearProgressIndicator(
                    progress = { sampleProgress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        val result = sample
        if (result != null) {
            item { Row2(stringResource(R.string.diag_sample_count), result.samples.toString()) }
            item { Row2(stringResource(R.string.diag_sample_min), formatFloat(result.minHz) + " Hz") }
            item { Row2(stringResource(R.string.diag_sample_max), formatFloat(result.maxHz) + " Hz") }
            item { Row2(stringResource(R.string.diag_sample_avg), formatFloat(result.avgHz) + " Hz") }
            item {
                Row2(
                    stringResource(R.string.diag_sample_histogram),
                    result.histogram.joinToString(" · ") {
                        formatFloat(it.first) + "Hz ×" + it.second
                    },
                )
            }
        }

        item { Section(stringResource(R.string.diag_power)) }
        item { Row2(stringResource(R.string.diag_level), report.battery.levelPercent.toString() + "%") }
        item {
            Row2(
                stringResource(R.string.diag_charging),
                if (report.battery.charging) {
                    yes + " (" + report.battery.pluggedLabel + ")"
                } else {
                    no
                },
            )
        }
        item { Row2(stringResource(R.string.diag_status), report.battery.statusLabel) }
        item { Row2(stringResource(R.string.diag_temperature), formatFloat(report.battery.temperatureC) + " °C") }
        item { Row2(stringResource(R.string.diag_power_save), bool(report.powerSaveMode)) }
        item { Row2(stringResource(R.string.diag_device_idle), bool(report.deviceIdle)) }
        item { Row2(stringResource(R.string.diag_thermal), report.thermalLabel) }

        item { Section(stringResource(R.string.diag_dream)) }
        item { Row2(stringResource(R.string.diag_dream_registered), bool(report.dreamServiceAvailable)) }
        item {
            Row2(
                stringResource(R.string.diag_dream_selected),
                report.dreamEnabledByUser.ifEmpty { stringResource(R.string.not_set) },
            )
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            Text(
                text = stringResource(R.string.diag_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Label left, value right. Both sides get real weight: a long value (a mode
 * list, a refresh-rate list) wraps inside its own column instead of squeezing
 * the label to zero width, which is what the naive Row did.
 */
@Composable
private fun Row2(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

private fun DeviceReport.socLabel(): String {
    val manufacturer = android.os.Build.SOC_MANUFACTURER.orEmpty().trim()
    val model = android.os.Build.SOC_MODEL.orEmpty().trim()
    return (manufacturer + " " + model).trim().ifEmpty { "—" }
}

private fun formatFloat(value: Float): String = String.format(Locale.US, "%.2f", value)
