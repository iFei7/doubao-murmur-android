package com.doubao.murmur.tv.app.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.lifecycleScope
import com.doubao.murmur.tv.app.AppGraph
import com.doubao.murmur.tv.app.BuildConfig
import com.doubao.murmur.tv.app.R
import com.doubao.murmur.tv.app.ime.ImeHelper
import com.doubao.murmur.tv.asr.AsrEvent
import com.doubao.murmur.tv.asr.AsrFailure
import com.doubao.murmur.tv.auth.SessionRepository
import com.doubao.murmur.tv.audio.MicPermission
import com.doubao.murmur.tv.audio.MicProbe
import com.doubao.murmur.tv.ui.component.TvActionButton
import com.doubao.murmur.tv.ui.theme.MurmurColors
import com.doubao.murmur.tv.ui.theme.MurmurTvTheme
import com.doubao.murmur.tv.ui.theme.TvDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read-only report of everything that can make voice input fail on a TV.
 *
 * The target audience is the person holding the box, not a log viewer: TV boxes are rarely
 * reachable over `adb logcat` while a user is complaining, so every fact that decides "does this
 * work" is rendered on screen and copyable in one tap.
 *
 * The network self-test deliberately opens a real ASR session: it is the only way to tell "the box
 * has no network" apart from "the session expired two weeks ago", and those two have completely
 * different fixes.
 */
class DiagnosticsActivity : ComponentActivity() {

    private lateinit var sessionRepository: SessionRepository

    private var sections by mutableStateOf<List<DiagnosticsSection>>(emptyList())
    private var networkLines by mutableStateOf<List<String>>(emptyList())
    private var networkRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionRepository = AppGraph.sessionRepository(this)

        setContent {
            MurmurTvTheme {
                DiagnosticsScreen(
                    sections = sections,
                    networkLines = networkLines,
                    networkRunning = networkRunning,
                    onRunNetworkTest = ::runNetworkTest,
                    onCopy = ::copyReport,
                    onBack = { finish() },
                )
            }
        }

        lifecycleScope.launch { sections = collectSections() }
    }

    /** Snapshot of the device, the microphone, the IME and the stored session. */
    private suspend fun collectSections(): List<DiagnosticsSection> {
        val pm = packageManager
        val device = buildList {
            add("Build.MANUFACTURER = ${Build.MANUFACTURER}")
            add("Build.MODEL = ${Build.MODEL}")
            add("Build.DEVICE = ${Build.DEVICE}")
            add("Build.VERSION.SDK_INT = ${Build.VERSION.SDK_INT}")
            add("Build.VERSION.RELEASE = ${Build.VERSION.RELEASE}")
            add("FEATURE_LEANBACK = ${pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)}")
            add(
                "FEATURE_MICROPHONE = " +
                    pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            )
            add(
                "FEATURE_TOUCHSCREEN = " +
                    pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            )
            add("packageName = $packageName")
            add("versionName = ${BuildConfig.VERSION_NAME}")
            add("gitSha = ${BuildConfig.GIT_SHA}")
            add("channel = ${BuildConfig.BUILD_CHANNEL}")
        }

        val micGranted = MicPermission.hasRecordPermission(this)
        val mic = buildList {
            add("RECORD_AUDIO granted = $micGranted")
            if (micGranted) {
                val capability = withContext(Dispatchers.IO) {
                    runCatching { MicProbe.probe(this@DiagnosticsActivity) }.getOrNull()
                }
                if (capability == null) {
                    add("MicProbe.probe threw, see logcat")
                } else {
                    add("hasMicrophone = ${capability.hasMicrophone}")
                    add("captureSampleRate = ${capability.captureSampleRate}")
                    add("needsResampling = ${capability.needsResampling}")
                    addAll(capability.notes)
                }
            } else {
                add("probe skipped: permission missing")
            }
        }

        val ime = buildList {
            add("component = ${ImeHelper.imeId(this@DiagnosticsActivity)}")
            add("enabled = ${ImeHelper.isImeEnabled(this@DiagnosticsActivity)}")
            add("selected = ${ImeHelper.isImeSelected(this@DiagnosticsActivity)}")
            val candidates = ImeHelper.systemImeCandidates(this@DiagnosticsActivity)
            add("otherImeCount = ${candidates.size}")
            candidates.forEach { add("  $it") }
            addAll(ImeHelper.adbHint(packageName).lines())
        }

        val login = buildList {
            val loggedIn = runCatching { sessionRepository.isLoggedIn() }.getOrDefault(false)
            add("isLoggedIn = $loggedIn")
            // Never print cookie values or ids: presence only.
            add("storedCredentials = $loggedIn")
        }

        return listOf(
            DiagnosticsSection(getString(R.string.diagnostics_section_device), device),
            DiagnosticsSection(getString(R.string.diagnostics_section_mic), mic),
            DiagnosticsSection(getString(R.string.diagnostics_section_ime), ime),
            DiagnosticsSection(getString(R.string.diagnostics_section_login), login),
        )
    }

    /**
     * Opens one real ASR session and sends 200 ms of digital silence.
     *
     * Everything is wrapped: `AsrEngine` is a network object, and a diagnostics screen that crashes
     * on a broken box is worse than no diagnostics screen. The overall budget is 5 s so the UI can
     * never look hung.
     */
    private fun runNetworkTest() {
        if (networkRunning) {
            return
        }
        networkRunning = true
        networkLines = emptyList()
        lifecycleScope.launch {
            val lines = try {
                withTimeoutOrNull(OVERALL_TIMEOUT_MS) { probeNetwork() }
                    ?: listOf("TIMEOUT: no answer within $OVERALL_TIMEOUT_MS ms")
            } catch (e: Exception) {
                listOf("FATAL: ${e.javaClass.simpleName}: ${e.message}")
            }
            networkLines = lines
            networkRunning = false
        }
    }

    private suspend fun probeNetwork(): List<String> = coroutineScope {
        val lines = mutableListOf<String>()
        val credentials = runCatching { sessionRepository.current() }.getOrNull()
        if (credentials == null) {
            return@coroutineScope listOf("SKIP: no stored credentials, sign in first")
        }

        val engine = try {
            AppGraph.asrEngineFactory().invoke(credentials)
        } catch (e: Exception) {
            return@coroutineScope listOf("FATAL: engine init ${e.javaClass.simpleName}")
        }

        val events = mutableListOf<AsrEvent>()
        // UNDISPATCHED so the SharedFlow subscription is registered before start() can emit
        // Connected: the engine's flow has replay = 0, an event emitted before subscription is lost.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                engine.events.collect { events.add(it) }
            } catch (e: CancellationException) {
                // The engine flow is infinite; cancellation is how this collector ends.
                throw e
            } catch (e: Exception) {
                lines.add("Event collector failed: ${e.javaClass.simpleName}")
            }
        }

        try {
            engine.start()
            lines.add("Connected: socket opened")
            engine.feed(ByteArray(TRAILING_SILENCE_BYTES))
            val text = engine.stopAndAwaitFinal(FINAL_TIMEOUT_MS)
            lines.add("Final text length = ${text.length}")
            lines.add("Result: stream completed end to end")
        } catch (e: CancellationException) {
            // The 5 s budget ran out; the caller turns this into a timeout line.
            throw e
        } catch (e: Exception) {
            lines.add("Throwable: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            collector.cancel()
            runCatching { engine.close() }
        }

        events.map { it.describe() }.forEach { lines.add(it) }
        val failure = events.filterIsInstance<AsrEvent.Failed>().firstOrNull()?.failure
        lines.add("Verdict: ${failure?.verdict() ?: "no failure event seen"}")

        lines
    }

    private fun copyReport() {
        val text = buildString {
            sections.forEach { section ->
                appendLine("== ${section.title} ==")
                section.lines.forEach { appendLine(it) }
            }
            appendLine("== ${getString(R.string.diagnostics_section_network)} ==")
            if (networkLines.isEmpty()) {
                appendLine("not run")
            } else {
                networkLines.forEach { appendLine(it) }
            }
        }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("murmur-diagnostics", text))
        Toast.makeText(this, getString(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
    }

    private companion object {

        /** 16 kHz x 1 ch x 16 bit x 200 ms, the trailer the server needs to flush the last word. */
        const val TRAILING_SILENCE_BYTES = 6_400

        const val FINAL_TIMEOUT_MS = 3_000L

        const val OVERALL_TIMEOUT_MS = 5_000L
    }
}

/** Section header plus its technical lines, kept as plain strings so it can be copied verbatim. */
data class DiagnosticsSection(
    val title: String,
    val lines: List<String>,
)

/** Compact, credential-free rendering of one engine event. */
private fun AsrEvent.describe(): String = when (this) {
    is AsrEvent.Connected -> "event: Connected"
    is AsrEvent.Partial -> "event: Partial(len=${text.length})"
    is AsrEvent.Final -> "event: Final(len=${text.length})"
    is AsrEvent.Finished -> "event: Finished"
    is AsrEvent.Failed -> "event: Failed(${failure.javaClass.simpleName})"
}

/** Turns a protocol failure into the action the user has to take. */
private fun AsrFailure.verdict(): String = when (this) {
    is AsrFailure.LoginExpired -> "session expired, sign in again"
    is AsrFailure.TokenInvalid -> "credentials rejected, sign in again"
    is AsrFailure.Network -> "network unreachable"
    is AsrFailure.Timeout -> "no answer from the server"
    is AsrFailure.Server -> "server refused the request (code=$code)"
    else -> "unclassified failure"
}

@Composable
private fun DiagnosticsScreen(
    sections: List<DiagnosticsSection>,
    networkLines: List<String>,
    networkRunning: Boolean,
    onRunNetworkTest: () -> Unit,
    onCopy: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MurmurColors.Background)
            .padding(TvDimens.overscanHorizontal, TvDimens.overscanVertical),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TvActionButton(text = stringResource(R.string.action_back), onClick = onBack)
            TvActionButton(
                text = stringResource(R.string.diagnostics_action_run),
                onClick = onRunNetworkTest,
            )
            TvActionButton(
                text = stringResource(R.string.diagnostics_action_copy),
                onClick = onCopy,
            )
            Text(
                text = stringResource(R.string.diagnostics_title),
                color = MurmurColors.OnBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // The buttons stay pinned above the scroll area so they never scroll out of reach of the
        // DPAD while the user reads a long report.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            sections.forEach { section -> Section(section.title, section.lines) }

            Section(
                title = stringResource(R.string.diagnostics_section_network),
                lines = when {
                    networkRunning -> listOf(stringResource(R.string.diagnostics_network_running))
                    networkLines.isEmpty() ->
                        listOf(stringResource(R.string.diagnostics_network_hint))
                    else -> networkLines
                },
            )
        }
    }
}

@Composable
private fun Section(title: String, lines: List<String>) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Text(
            text = title,
            color = MurmurColors.Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        lines.forEach { line ->
            Text(
                text = line,
                color = MurmurColors.OnSurfaceMuted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
