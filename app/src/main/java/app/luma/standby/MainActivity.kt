package app.luma.standby

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.theme.ThemeEditing
import app.luma.standby.domain.theme.ThemeRepository
import app.luma.standby.platform.DeviceDiagnostics
import app.luma.standby.platform.DeviceReport
import app.luma.standby.platform.display.DisplayController
import app.luma.standby.platform.display.RefreshRateSampler
import app.luma.standby.platform.display.RefreshSample
import app.luma.standby.platform.widgets.HeadsUpSuppressor
import app.luma.standby.ui.DiagnosticsScreen
import app.luma.standby.ui.LumaAppTheme
import app.luma.standby.ui.PreviewScreen
import app.luma.standby.ui.ThemesScreen
import app.luma.standby.ui.WidgetsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val headsUpSuppressor by lazy { HeadsUpSuppressor(this) }

    override fun onResume() {
        super.onResume()
        // If StandbyActivity was killed by an OS restart or app update before onPause ran,
        // heal our implicit DND rule immediately on returning to the app.
        headsUpSuppressor.restoreIfLeaked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = ThemeRepository(applicationContext)
        val diagnostics = DeviceDiagnostics(applicationContext)

        setContent {
            LumaAppTheme {
                val settings by repository.settings.collectAsStateWithLifecycle(
                    initialValue = LumaSettings(),
                )
                val scope = rememberCoroutineScope()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var report by remember { mutableStateOf<DeviceReport?>(null) }

                val sampler = remember { RefreshRateSampler(DisplayController(applicationContext)) }
                var sampling by remember { mutableStateOf(false) }
                var sampleProgress by remember { mutableFloatStateOf(0f) }
                var sample by remember { mutableStateOf<RefreshSample?>(null) }

                DisposableEffect(Unit) {
                    onDispose { sampler.stop() }
                }

                LaunchedEffect(tab) {
                    if (tab == 3) report = diagnostics.report()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(Modifier.fillMaxSize().systemBarsPadding()) {
                        Text(
                            text = stringResource(R.string.app_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                        )
                        TabRow(selectedTabIndex = tab) {
                            TabLabel(stringResource(R.string.tab_preview), tab == 0) { tab = 0 }
                            TabLabel(stringResource(R.string.tab_themes), tab == 1) { tab = 1 }
                            TabLabel(stringResource(R.string.tab_widgets), tab == 2) { tab = 2 }
                            TabLabel(stringResource(R.string.tab_diagnostics), tab == 3) { tab = 3 }
                        }
                        Box(Modifier.fillMaxSize()) {
                            when (tab) {
                                0 -> PreviewScreen(
                                    settings = settings,
                                    onStart = {
                                        runCatching {
                                            startActivity(Intent(this@MainActivity, StandbyActivity::class.java))
                                        }
                                    },
                                    onModeChange = { mode ->
                                        scope.launch { repository.update { it.copy(mode = mode) } }
                                    },
                                    onStyleChange = { style ->
                                        scope.launch {
                                            repository.update {
                                                val updated = ThemeEditing.withFaceStyle(
                                                    it,
                                                    style,
                                                    System.currentTimeMillis(),
                                                )
                                                ThemeEditing.upsert(it, updated)
                                            }
                                        }
                                    },
                                )

                                1 -> ThemesScreen(
                                    settings = settings,
                                    onChange = { transform ->
                                        scope.launch { repository.update(transform) }
                                    },
                                    onSaveTheme = { theme ->
                                        scope.launch {
                                            repository.update { ThemeEditing.upsert(it, theme) }
                                        }
                                    },
                                    onDeleteTheme = { id ->
                                        scope.launch {
                                            repository.update { ThemeEditing.remove(it, id) }
                                        }
                                    },
                                    encodeTheme = { theme -> repository.exportTheme(theme) },
                                )

                                2 -> WidgetsScreen(
                                    settings = settings,
                                    onChange = { transform ->
                                        scope.launch { repository.update(transform) }
                                    },
                                )

                                else -> DiagnosticsScreen(
                                    report = report,
                                    sampling = sampling,
                                    sampleProgress = sampleProgress,
                                    sample = sample,
                                    onRefresh = { report = diagnostics.report() },
                                    onStartSample = {
                                        sampling = true
                                        sampleProgress = 0f
                                        sample = null
                                        sampler.start(
                                            durationMs = 30_000L,
                                            onProgress = { progress -> sampleProgress = progress },
                                            onDone = { result ->
                                                sampling = false
                                                sample = result
                                                report = diagnostics.report()
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Tab(selected = selected, onClick = onClick, text = { Text(text) })
}
