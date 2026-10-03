// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import io.advancex.app.BuildInfo
import io.advancex.app.R
import io.advancex.app.bridge.NativeBridge
import io.advancex.app.data.AppSettings
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.Format
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.add
import io.advancex.app.ui.common.card
import io.advancex.app.ui.common.choiceRow
import io.advancex.app.ui.common.dangerButton
import io.advancex.app.ui.common.secondaryButton
import io.advancex.app.ui.common.sectionHeader
import io.advancex.app.ui.common.settingRow
import io.advancex.app.ui.common.sliderRow
import io.advancex.app.ui.common.switchRow
import io.advancex.app.ui.common.text
import io.advancex.core.emulator.EmulationSettings
import io.advancex.core.emulator.FastForwardSpeed
import io.advancex.core.emulator.PerformanceMode
import io.advancex.core.saves.AtomicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : BaseActivity() {
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn {}
        setScreen(getString(R.string.settings_title), column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private val settings: AppSettings get() = services.settings.value
    private val emu: EmulationSettings get() = settings.emulation

    private fun updateEmu(rerender: Boolean = true, transform: (EmulationSettings) -> EmulationSettings) {
        services.settings.update { it.copy(emulation = transform(it.emulation)) }
        if (rerender) render()
    }

    private fun render() {
        column.removeAllViews()

        column.addView(sectionHeader(getString(R.string.settings_general)))
        column.add(switchRow(getString(R.string.settings_advance_default), getString(R.string.settings_advance_default_body),
            emu.advanceModeDefault, R.drawable.ic_advance) { on -> updateEmu { it.copy(advanceModeDefault = on) } }, bottomMarginDp = 8)
        column.add(switchRow(getString(R.string.settings_autosave), getString(R.string.settings_autosave_body),
            emu.autoSaveStateOnExit, R.drawable.ic_saves) { on -> updateEmu { it.copy(autoSaveStateOnExit = on) } })

        column.addView(sectionHeader(getString(R.string.settings_video)))
        column.addView(settingRow(getString(R.string.settings_graphics_global), getString(R.string.settings_graphics_global_body),
            R.drawable.ic_graphics) { Nav.graphics(this) })

        column.addView(sectionHeader(getString(R.string.settings_audio)))
        column.add(sliderRow(getString(R.string.settings_volume), 100, (emu.audio.volume * 100).roundToInt(), { "$it%" }) { v ->
            updateEmu(false) { it.copy(audio = it.audio.copy(volume = v / 100f)) }
        }, bottomMarginDp = 8)
        column.add(switchRow(getString(R.string.settings_mute), null, emu.audio.muted) { on ->
            updateEmu { it.copy(audio = it.audio.copy(muted = on)) }
        }, bottomMarginDp = 8)
        val latencies = listOf(32, 64, 100, 160)
        column.add(choiceRow(getString(R.string.settings_latency), getString(R.string.settings_latency_body),
            listOf("Low (32 ms)", "Normal (64 ms)", "High (100 ms)", "Very high (160 ms)"),
            latencies.indexOfFirst { it >= emu.audio.latencyMs }.coerceAtLeast(0)) { i ->
            updateEmu { it.copy(audio = it.audio.copy(latencyMs = latencies[i])) }
        }, bottomMarginDp = 8)
        column.addView(choiceRow(getString(R.string.settings_audio_backend), null, listOf("Automatic", "AAudio", "OpenSL ES"),
            settings.audioBackend.coerceIn(0, 2)) { i -> services.settings.update { it.copy(audioBackend = i) } })

        column.addView(sectionHeader(getString(R.string.settings_performance)))
        val modes = PerformanceMode.entries
        column.add(choiceRow(getString(R.string.settings_performance_mode), emu.performance.description, modes.map { it.label },
            modes.indexOf(emu.performance)) { i -> updateEmu { it.copy(performance = modes[i]) } }, bottomMarginDp = 8)
        val speeds = FastForwardSpeed.entries
        column.addView(choiceRow(getString(R.string.settings_fast_forward), null, speeds.map { it.label }, speeds.indexOf(emu.fastForward)) { i ->
            updateEmu { it.copy(fastForward = speeds[i]) }
        })

        column.addView(sectionHeader(getString(R.string.settings_rewind)))
        column.add(switchRow(getString(R.string.settings_rewind_enable), getString(R.string.settings_rewind_body), emu.rewind.enabled,
            R.drawable.ic_rewind) { on -> updateEmu { it.copy(rewind = it.rewind.copy(enabled = on)) } }, bottomMarginDp = 8)
        if (emu.rewind.enabled) {
            val durations = listOf(15, 30, 60, 120)
            column.add(choiceRow(getString(R.string.settings_rewind_duration), null, durations.map { "$it s" },
                durations.indexOfFirst { it >= emu.rewind.durationSeconds }.coerceAtLeast(0)) { i ->
                updateEmu { it.copy(rewind = it.rewind.copy(durationSeconds = durations[i])) }
            }, bottomMarginDp = 8)
            val memory = listOf(32, 64, 128, 256)
            column.add(choiceRow(getString(R.string.settings_rewind_memory), null, memory.map { "$it MB" },
                memory.indexOfFirst { it >= emu.rewind.maxMemoryMb }.coerceAtLeast(0)) { i ->
                updateEmu { it.copy(rewind = it.rewind.copy(maxMemoryMb = memory[i])) }
            }, bottomMarginDp = 8)
            val intervals = listOf(3, 6, 12)
            column.addView(choiceRow(getString(R.string.settings_rewind_frequency), null, listOf("Smooth", "Normal", "Light"),
                intervals.indexOfFirst { it >= emu.rewind.intervalFrames }.coerceAtLeast(0)) { i ->
                updateEmu { it.copy(rewind = it.rewind.copy(intervalFrames = intervals[i])) }
            })
        }

        column.addView(sectionHeader(getString(R.string.settings_controls)))
        column.addView(settingRow(getString(R.string.settings_controls), getString(R.string.settings_controls_body), R.drawable.ic_controls) {
            Nav.controls(this)
        })

        column.addView(sectionHeader(getString(R.string.settings_bios)))
        val bios = services.biosFile
        column.addView(card {
            addView(text(getString(if (bios.isFile) R.string.settings_bios_installed else R.string.settings_bios_none), TextStyle.BODY_SECONDARY))
            if (bios.isFile) {
                add(dangerButton(getString(R.string.settings_bios_remove)) {
                    bios.delete()
                    updateEmu { it.copy(useUserBios = false) }
                }, topMarginDp = 12)
            } else {
                add(secondaryButton(getString(R.string.settings_bios_import), R.drawable.ic_import) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_BIOS)
                }, topMarginDp = 12)
            }
        })

        column.addView(sectionHeader(getString(R.string.app_name)))
        column.add(settingRow(getString(R.string.settings_mods), getString(R.string.home_mods_hint), R.drawable.ic_mods) { Nav.mods(this) },
            bottomMarginDp = 8)
        column.addView(settingRow(getString(R.string.settings_studio), getString(R.string.settings_studio_body), R.drawable.ic_studio) {
            Nav.studio(this)
        })

        column.addView(sectionHeader(getString(R.string.settings_storage)))
        val cacheSize = services.assetCache.sizeBytes()
        column.addView(settingRow(getString(R.string.settings_cache), Format.bytes(cacheSize), R.drawable.ic_folder,
            secondaryButton(getString(R.string.settings_cache_clear)) {
                services.assetCache.clear()
                render()
            }))

        column.addView(sectionHeader(getString(R.string.settings_diagnostics)))
        column.add(switchRow(getString(R.string.settings_perf_overlay), getString(R.string.settings_perf_overlay_body),
            settings.showPerformanceOverlay, R.drawable.ic_speed) { on ->
            services.settings.update { it.copy(showPerformanceOverlay = on) }
        }, bottomMarginDp = 8)
        val crashes = services.logs.crashReports().size
        column.add(settingRow(getString(R.string.settings_export_logs), getString(R.string.settings_crash_reports, crashes),
            R.drawable.ic_info) {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "advancex-diagnostics.txt")
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_LOGS)
        }, bottomMarginDp = 8)
        column.addView(settingRow(getString(R.string.settings_clear_logs), null, R.drawable.ic_delete) {
            confirm(getString(R.string.settings_clear_logs), getString(R.string.settings_crash_reports, crashes), getString(R.string.action_delete)) {
                services.logs.clear()
                render()
            }
        })

        column.addView(sectionHeader(getString(R.string.settings_about)))
        val native = runCatching { NativeBridge.version() }.getOrDefault("native library unavailable")
        column.add(settingRow(getString(R.string.settings_version, BuildInfo.VERSION_NAME), native, R.drawable.ic_advance), bottomMarginDp = 8)
        column.addView(settingRow(getString(R.string.settings_licenses), "mGBA (MPL-2.0), kotlinx libraries (Apache-2.0) and more",
            R.drawable.ic_info) { Nav.licenses(this, getString(R.string.settings_licenses)) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_BIOS -> scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    if (bytes == null || bytes.size != 16384) false
                    else {
                        AtomicFiles.write(services.biosFile, bytes)
                        true
                    }
                }
                if (ok) updateEmu { it.copy(useUserBios = true) } else showMessage(getString(R.string.settings_bios), getString(R.string.settings_bios_invalid))
            }
            REQUEST_LOGS -> scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openOutputStream(uri)?.use { it.write(services.logs.exportText().toByteArray()) } }.isSuccess
                }
                toast(if (ok) getString(R.string.action_done) else "Export failed")
            }
        }
    }

    companion object {
        private const val REQUEST_BIOS = 4401
        private const val REQUEST_LOGS = 4402
    }
}
