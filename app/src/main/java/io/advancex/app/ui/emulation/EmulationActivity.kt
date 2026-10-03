// SPDX-License-Identifier: MPL-2.0
package io.advancex.app.ui.emulation

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.advancex.app.R
import io.advancex.app.bridge.AndroidImageCodec
import io.advancex.app.bridge.NativeSession
import io.advancex.app.data.AppSettings
import io.advancex.app.data.GameConfig
import io.advancex.app.data.GameEntry
import io.advancex.app.ui.common.BaseActivity
import io.advancex.app.ui.common.Nav
import io.advancex.app.ui.common.Palette
import io.advancex.app.ui.common.TextStyle
import io.advancex.app.ui.common.chip
import io.advancex.app.ui.common.dp
import io.advancex.app.ui.common.text
import io.advancex.app.ui.common.vertical
import io.advancex.app.ui.controls.ControlsEditorActivity
import io.advancex.core.emulator.BackgroundStyle
import io.advancex.core.emulator.EmulatorSession
import io.advancex.core.emulator.EnhancementHints
import io.advancex.core.emulator.FastForwardSpeed
import io.advancex.core.emulator.LaunchRequest
import io.advancex.core.emulator.Log
import io.advancex.core.emulator.OpResult
import io.advancex.core.emulator.PerformanceMode
import io.advancex.core.emulator.ResolvedSettings
import io.advancex.core.emulator.SettingsResolver
import io.advancex.core.emulator.VideoSettings
import io.advancex.core.input.ControlId
import io.advancex.core.input.Hotkey
import io.advancex.core.input.InputMixer
import io.advancex.core.input.DirectionalInput
import io.advancex.core.saves.StateSlot
import io.advancex.engine.EnginePlan
import io.advancex.engine.EngineRequest
import io.advancex.engine.replacements.ArgbImage
import io.advancex.engine.replacements.Scale2xUpscaler
import io.advancex.engine.safety.EnhancementGuard
import io.advancex.engine.studio.DumpedAsset
import io.advancex.engine.widescreen.WidescreenMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The game screen. Owns one [NativeSession]; all blocking session calls run
 * on the app-wide session thread so the UI never stalls and shutdown (which
 * writes saves) completes even if this activity goes away.
 */
class EmulationActivity : BaseActivity(), SurfaceHolder.Callback, TouchControlsView.Listener, InputManager.InputDeviceListener {

    private class Prepared(
        val entry: GameEntry,
        val config: GameConfig,
        val app: AppSettings,
        val plan: EnginePlan,
        val resolved: ResolvedSettings,
        val request: LaunchRequest,
        val replacements: List<Pair<Long, ArgbImage>>,
    )

    private lateinit var gameId: String
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controls: TouchControlsView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var osd: TextView
    private lateinit var indicator: TextView
    private lateinit var banner: TextView
    private var menu: PauseMenu? = null

    private val session by lazy { NativeSession(services.settings.value.audioBackend) }
    private var prepared: Prepared? = null
    private var resolved: ResolvedSettings? = null
    @Volatile private var ready = false
    private var quitting = false
    private var surfaceHolder: SurfaceHolder? = null
    private val mixer = InputMixer()
    private var fastForward = false
    private var ffSpeed = FastForwardSpeed.X3
    private var rewinding = false
    private var triggerFf = false
    private var triggerRewind = false
    private var touchVisibleByUser = true
    private var controllerConnected = false
    private var playStartedAt = 0L
    private var playAccumulatedMs = 0L
    private var statsJob: Job? = null
    private var dumpJob: Job? = null
    private val replacementKeys = mutableSetOf<Long>()
    private val inputManager by lazy { getSystemService(InputManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameId = intent.getStringExtra(Nav.EXTRA_GAME_ID) ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        hideSystemBars()
        inputManager.registerInputDeviceListener(this, null)
        controllerConnected = hasController()
        startSession()
    }

    // --- Views ---------------------------------------------------------------------

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@EmulationActivity) }
        controls = TouchControlsView(this).apply { listener = this@EmulationActivity }
        osd = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
        }
        indicator = chip("", Palette.cyan(this), filled = true).apply { visibility = View.GONE }
        banner = text(getString(R.string.emu_save_failed), TextStyle.BODY, Color.WHITE).apply {
            setBackgroundColor(0xE0B91C1C.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        loadingText = text("", TextStyle.BODY_SECONDARY).apply { gravity = Gravity.CENTER }
        loading = vertical {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(ProgressBar(context).apply { isIndeterminate = true })
            addView(loadingText)
        }
        root.addView(surfaceView)
        root.addView(controls)
        root.addView(osd, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        root.addView(indicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) root.post { layoutGame() }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            root.post { layoutGame() }
            insets
        }
        setContentView(root)
    }

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    /** Positions the game surface and the touch-controls area for the current orientation. */
    private fun layoutGame() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val insets = root.rootWindowInsets
        if (insets != null && Build.VERSION.SDK_INT >= 30) {
            val cut = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            insetTop = cut.top; insetBottom = cut.bottom; insetLeft = cut.left; insetRight = cut.right
        } else if (insets != null && Build.VERSION.SDK_INT >= 28) {
            val cut = insets.displayCutout
            insetTop = cut?.safeInsetTop ?: 0; insetBottom = cut?.safeInsetBottom ?: 0
            insetLeft = cut?.safeInsetLeft ?: 0; insetRight = cut?.safeInsetRight ?: 0
        }
        val areas = GameLayout.compute(w, h, insetTop, insetBottom, insetLeft, insetRight)
        surfaceView.layoutParams = FrameLayout.LayoutParams(areas.game.width(), areas.game.height()).apply {
            leftMargin = areas.game.left
            topMargin = areas.game.top
        }
        controls.layoutParams = FrameLayout.LayoutParams(areas.controls.width(), areas.controls.height()).apply {
            leftMargin = areas.controls.left
            topMargin = areas.controls.top
        }
        val orientation = GameLayout.orientationOf(w, h)
        val settings = services.settings.value
        controls.layoutModel = settings.touchLayout(orientation).normalized()
        updateControlsVisibility()
        (osd.layoutParams as FrameLayout.LayoutParams).setMargins(dp(8) + insetLeft, dp(8) + insetTop, 0, 0)
        (indicator.layoutParams as FrameLayout.LayoutParams).topMargin = dp(10) + insetTop
        root.requestLayout()
    }

    private fun updateControlsVisibility() {
        val hide = !touchVisibleByUser || (controllerConnected && services.settings.value.hideTouchWithController)
        controls.visibility = if (hide) View.GONE else View.VISIBLE
        if (hide) {
            mixer.set(InputMixer.TOUCH, 0)
            pushKeys()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.post { layoutGame() }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // --- Session lifecycle ---------------------------------------------------------------

    private fun startSession() {
        val entry = services.library.get(gameId)
        loadingText.text = getString(R.string.emu_loading, entry?.title ?: gameId)
        scope.launch {
            val prep = withContext(Dispatchers.IO) { runCatching { prepare() } }
            val p = prep.getOrElse { e ->
                Log.e(TAG, "Preparation failed", e)
                fail(e.message ?: e.toString())
                return@launch
            }
            prepared = p
            resolved = p.resolved
            val result = withContext(services.sessionDispatcher) { session.open(p.request) }
            if (result is OpResult.Error) {
                fail(result.message)
                return@launch
            }
            onSessionOpened(p)
        }
    }

    private fun prepare(): Prepared {
        val entry = services.library.get(gameId) ?: error("This game is no longer in your library.")
        val romFile = services.library.romFile(entry)
        if (!romFile.isFile) error("The ROM file is missing. Please import the game again.")
        val config = services.gameConfigs.get(gameId)
        val app = services.settings.value
        val global = app.emulation
        val wantsAdvance = (config.overrides.advanceMode ?: global.advanceModeDefault) && !config.overrides.safeMode
        val identity = entry.identity()
        val plan = services.engine.plan(
            EngineRequest(identity, wantsAdvance, config.patchToggles, config.replacementsEnabled, config.widescreen),
        )
        var resolved = SettingsResolver.resolve(global, config.overrides, EnhancementHints(plan.videoHints))
        if (resolved.advanceMode && plan.widescreen.mode == WidescreenMode.DISPLAY && resolved.performance != PerformanceMode.BATTERY_SAVER) {
            resolved = resolved.copy(video = resolved.video.copy(background = plan.widescreen.background ?: BackgroundStyle.AMBIENT))
        }
        resolved.notes.forEach { Log.i(TAG, it) }
        plan.messages.forEach { Log.i(TAG, (if (it.warning) "warning: " else "") + it.text) }

        services.saveLayout.ensureDirs(gameId)
        // Never lose a save silently: snapshot the cartridge save before every session.
        runCatching { services.saves.snapshotCartridge(gameId) }.onFailure { Log.w(TAG, "Backup failed", it) }

        val bios = services.biosFile.takeIf { global.useUserBios && it.isFile }
        val request = LaunchRequest(romFile, gameId, services.saveLayout.cartridgeSave(gameId), bios, resolved, plan.patchBlob)

        val replacements = mutableListOf<Pair<Long, ArgbImage>>()
        var budget = 16 * 1024 * 1024 // pixels
        for (r in plan.replacements) {
            val bytes = r.root.read(r.path) ?: continue
            val image = services.assetCache.getOrPut(bytes, "decode-v1") { AndroidImageCodec.decode(bytes) } ?: continue
            budget -= image.width * image.height
            if (budget < 0) break
            replacements += r.key to image
        }
        return Prepared(entry, config, app, plan, resolved, request, replacements)
    }

    private fun fail(message: String) {
        loading.visibility = View.GONE
        showMessage(getString(R.string.emu_failed), message) { finish() }
    }

    private suspend fun onSessionOpened(p: Prepared) {
        ffSpeed = p.resolved.fastForward
        if (p.app.showPerformanceOverlay) osd.visibility = View.VISIBLE
        for ((key, image) in p.replacements) session.addReplacementTexture(key, image)
        replacementKeys += p.replacements.map { it.first }
        session.setReplacementKeys(replacementKeys.toLongArray())
        val captureSprites = p.resolved.advanceMode && (p.app.captureSprites || p.config.autoUpscaleSprites)
        if (captureSprites) {
            session.setAssetDump(true)
            startDumpPolling(p)
        }

        val slot = intent.getIntExtra(Nav.EXTRA_STATE_SLOT, -1)
        val resumeAuto = intent.getBooleanExtra(Nav.EXTRA_RESUME_AUTO, false)
        val stateFile = when {
            slot in 0..StateSlot.MAX -> services.saveLayout.stateFile(gameId, StateSlot(slot))
            resumeAuto -> services.saveLayout.stateFile(gameId, StateSlot.AUTO)
            else -> null
        }
        if (stateFile != null && stateFile.isFile) {
            val r = withContext(services.sessionDispatcher) { session.loadState(stateFile) }
            when (r) {
                is OpResult.Ok -> toast(if (resumeAuto) getString(R.string.emu_resumed) else getString(R.string.emu_state_loaded, StateSlot(slot).label))
                is OpResult.Error -> showMessage(getString(R.string.emu_menu_load_state), r.message)
            }
        }

        withContext(Dispatchers.IO) {
            services.guard.sessionStarted(
                EnhancementGuard.Marker(gameId, p.resolved.advanceMode, p.plan.activeFeatures, System.currentTimeMillis()),
            )
        }
        if (p.resolved.performance == PerformanceMode.QUALITY && Build.VERSION.SDK_INT >= 24) {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(true)
        }

        session.start()
        surfaceHolder?.let { session.setSurface(it.surface) }
        ready = true
        loading.visibility = View.GONE
        if (!hasWindowFocus() && !isResumedCompat()) session.setPaused(true) else onPlaying()
        startStatsLoop()
    }

    private var resumed = false
    private fun isResumedCompat() = resumed

    private fun onPlaying() {
        if (menu != null) return
        session.setPaused(false)
        if (playStartedAt == 0L) playStartedAt = SystemClock.elapsedRealtime()
    }

    private fun onPaused() {
        if (playStartedAt != 0L) {
            playAccumulatedMs += SystemClock.elapsedRealtime() - playStartedAt
            playStartedAt = 0L
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        if (ready) onPlaying()
    }

    override fun onPause() {
        resumed = false
        if (ready) {
            session.setPaused(true)
            onPaused()
            services.sessionExecutor.execute { session.flushSave() }
        }
        super.onPause()
    }

    override fun onStop() {
        // Keep a resume point in case the system kills the app in the background.
        if (ready && !quitting && services.settings.value.emulation.autoSaveStateOnExit) {
            val file = services.saveLayout.stateFile(gameId, StateSlot.AUTO)
            services.sessionExecutor.execute { saveStateWithScreenshot(file, StateSlot.AUTO) }
        }
        super.onStop()
    }

    override fun onDestroy() {
        inputManager.unregisterInputDeviceListener(this)
        statsJob?.cancel()
        dumpJob?.cancel()
        if (!quitting) {
            // Finished without the menu (e.g. task removed): still persist everything.
            val playMs = currentPlayMs()
            services.sessionExecutor.execute {
                if (ready) {
                    session.setPaused(true)
                    session.flushSave()
                }
                session.close()
                recordPlay(playMs)
                services.guard.sessionEnded()
            }
        }
        super.onDestroy()
    }

    private fun currentPlayMs(): Long =
        playAccumulatedMs + if (playStartedAt != 0L) SystemClock.elapsedRealtime() - playStartedAt else 0L

    private fun recordPlay(playMs: Long) {
        runCatching {
            services.library.update(gameId) { it.copy(lastPlayedAtMs = System.currentTimeMillis(), playTimeMs = it.playTimeMs + playMs) }
        }
    }

    /** Saves state + thumbnail. Runs on the session thread. */
    private fun saveStateWithScreenshot(file: File, slot: StateSlot): OpResult {
        val result = session.saveState(file)
        if (result.isOk) writeScreenshot(services.saveLayout.screenshotFile(gameId, slot))
        return result
    }

    private fun writeScreenshot(target: File): Boolean {
        val pixels = IntArray(EmulatorSession.SCREEN_WIDTH * EmulatorSession.SCREEN_HEIGHT)
        if (!session.captureFrame(pixels)) return false
        val bmp = Bitmap.createBitmap(pixels, EmulatorSession.SCREEN_WIDTH, EmulatorSession.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
        target.parentFile?.mkdirs()
        val ok = runCatching { FileOutputStream(target).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess
        bmp.recycle()
        io.advancex.app.ui.common.CoverView.invalidate(target)
        return ok
    }

    fun quitGame() {
        if (quitting) return
        quitting = true
        menu?.dismiss()
        menu = null
        loadingText.text = getString(R.string.action_save) + "\u2026"
        loading.visibility = View.VISIBLE
        val playMs = currentPlayMs()
        onPaused()
        scope.launch {
            withContext(services.sessionDispatcher) {
                if (ready) {
                    session.setPaused(true)
                    if (services.settings.value.emulation.autoSaveStateOnExit) {
                        saveStateWithScreenshot(services.saveLayout.stateFile(gameId, StateSlot.AUTO), StateSlot.AUTO)
                    }
                    services.library.get(gameId)?.let { writeScreenshot(services.library.autoCover(it)) }
                    session.flushSave()
                }
                session.close()
                recordPlay(playMs)
                services.guard.sessionEnded()
            }
            finish()
        }
    }

    // --- Surface ---------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        if (ready) session.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceHolder = holder
        if (ready) session.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
        // Blocks until the render thread released the surface (required).
        session.setSurface(null)
    }

    // --- Input -----------------------------------------------------------------------------

    private fun pushKeys() = session.setKeys(mixer.mask())

    override fun onButtons(mask: Int) {
        mixer.set(InputMixer.TOUCH, mask)
        pushKeys()
    }

    override fun onHotkey(id: ControlId, pressed: Boolean) {
        when (id) {
            ControlId.FAST_FORWARD -> if (pressed) setFastForward(!fastForward)
            ControlId.REWIND -> setRewind(pressed)
            ControlId.MENU -> if (pressed) toggleMenu()
            else -> Unit
        }
    }

    private fun handleHotkey(hotkey: Hotkey, pressed: Boolean) {
        when (hotkey) {
            Hotkey.FAST_FORWARD_HOLD -> setFastForward(pressed)
            Hotkey.FAST_FORWARD_TOGGLE -> if (pressed) setFastForward(!fastForward)
            Hotkey.REWIND_HOLD -> setRewind(pressed)
            Hotkey.QUICK_SAVE -> if (pressed) saveToSlot(StateSlot(1))
            Hotkey.QUICK_LOAD -> if (pressed) loadFromSlot(StateSlot(1))
            Hotkey.MENU -> if (pressed) toggleMenu()
        }
    }

    private fun setFastForward(active: Boolean) {
        if (!ready) return
        fastForward = active
        session.setFastForward(active, ffSpeed)
        updateIndicator()
    }

    private fun setRewind(active: Boolean) {
        if (!ready) return
        if (active && resolved?.rewind?.enabled != true) {
            toast(getString(R.string.emu_rewind_off))
            return
        }
        rewinding = active
        session.setRewinding(active)
        updateIndicator()
    }

    private fun updateIndicator() {
        indicator.text = when {
            rewinding -> "◀◀  " + getString(R.string.emu_rewinding)
            fastForward -> "▶▶  " + ffSpeed.label
            else -> ""
        }
        indicator.visibility = if (rewinding || fastForward) View.VISIBLE else View.GONE
    }

    private fun isFromController(event: android.view.InputEvent): Boolean {
        val s = event.source
        return s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!ready || menu != null) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        val settings = services.settings.value
        val map = if (isFromController(event)) settings.gamepadMap else settings.keyboardMap
        val action = map.actionFor(event.keyCode) ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down && event.repeatCount > 0) return true
        val source = if (isFromController(event)) InputMixer.GAMEPAD else InputMixer.KEYBOARD
        action.button?.let { b ->
            if (down) mixer.press(source, b) else mixer.release(source, b)
            pushKeys()
        }
        action.hotkey?.let { handleHotkey(it, down) }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!ready || menu != null || event.action != MotionEvent.ACTION_MOVE || !isFromController(event)) {
            return super.dispatchGenericMotionEvent(event)
        }
        val stick = DirectionalInput.fromAxes(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        val hat = DirectionalInput.fromAxes(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), 0.5f)
        mixer.set(InputMixer.STICK, stick or hat)
        pushKeys()
        val l = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        val r = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        if (r != triggerFf) {
            triggerFf = r
            handleHotkey(Hotkey.FAST_FORWARD_HOLD, r)
        }
        if (l != triggerRewind) {
            triggerRewind = l
            if (resolved?.rewind?.enabled == true) handleHotkey(Hotkey.REWIND_HOLD, l)
        }
        return true
    }

    private fun hasController(): Boolean = inputManager.inputDeviceIds.any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    override fun onInputDeviceAdded(deviceId: Int) = onDevicesChanged()
    override fun onInputDeviceRemoved(deviceId: Int) = onDevicesChanged()
    override fun onInputDeviceChanged(deviceId: Int) = onDevicesChanged()

    private fun onDevicesChanged() {
        controllerConnected = hasController()
        mixer.set(InputMixer.GAMEPAD, 0)
        mixer.set(InputMixer.STICK, 0)
        pushKeys()
        updateControlsVisibility()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!ready) {
            if (!quitting) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            return
        }
        toggleMenu()
    }

    // --- Menu ------------------------------------------------------------------------------

    fun toggleMenu() {
        if (!ready || quitting) return
        val current = menu
        if (current != null) {
            current.dismiss()
            menu = null
            onPlaying()
            return
        }
        mixer.clear()
        pushKeys()
        if (fastForward) setFastForward(false)
        if (rewinding) setRewind(false)
        session.setPaused(true)
        onPaused()
        menu = PauseMenu(this, root, menuModel()).also { it.show() }
    }

    internal fun closeMenu() {
        if (menu != null) toggleMenu()
    }

    private fun menuModel(): PauseMenu.Model {
        val p = prepared!!
        val advance = resolved?.advanceMode == true
        return PauseMenu.Model(
            title = p.entry.title,
            advanceMode = advance,
            ffSpeed = ffSpeed,
            rewindEnabled = resolved?.rewind?.enabled == true,
            touchVisible = touchVisibleByUser,
            currentVideo = resolved?.video ?: VideoSettings(),
        )
    }

    internal fun menuSave() = pickSlot(save = true)
    internal fun menuLoad() = pickSlot(save = false)

    private fun pickSlot(save: Boolean) {
        SlotPicker(this, services.saves.slots(gameId), save) { slot ->
            if (save) saveToSlot(slot) else loadFromSlot(slot)
        }.show()
    }

    private fun saveToSlot(slot: StateSlot) {
        val file = services.saveLayout.stateFile(gameId, slot)
        scope.launch {
            val r = withContext(services.sessionDispatcher) { saveStateWithScreenshot(file, slot) }
            when (r) {
                is OpResult.Ok -> toast(getString(R.string.emu_state_saved, slot.label))
                is OpResult.Error -> showMessage(getString(R.string.emu_menu_save_state), r.message)
            }
        }
    }

    private fun loadFromSlot(slot: StateSlot) {
        val file = services.saveLayout.stateFile(gameId, slot)
        if (!file.isFile) return
        scope.launch {
            val r = withContext(services.sessionDispatcher) { session.loadState(file) }
            when (r) {
                is OpResult.Ok -> {
                    toast(getString(R.string.emu_state_loaded, slot.label))
                    closeMenu()
                }
                is OpResult.Error -> showMessage(getString(R.string.emu_menu_load_state), r.message)
            }
        }
    }

    internal fun menuSetSpeed(speed: FastForwardSpeed) {
        ffSpeed = speed
        services.settings.update { it.copy(emulation = it.emulation.copy(fastForward = speed)) }
    }

    internal fun menuSetVideo(video: VideoSettings) {
        val config = services.gameConfigs.update(gameId) { it.copy(overrides = it.overrides.copy(video = video)) }
        val p = prepared ?: return
        val r = SettingsResolver.resolve(services.settings.value.emulation, config.overrides, EnhancementHints(p.plan.videoHints))
        resolved = r
        session.setVideo(r.video)
    }

    internal fun menuToggleTouch(visible: Boolean) {
        touchVisibleByUser = visible
        updateControlsVisibility()
    }

    internal fun menuEditControls() {
        val orientation = GameLayout.orientationOf(root.width, root.height)
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(this, ControlsEditorActivity::class.java).putExtra(ControlsEditorActivity.EXTRA_ORIENTATION, orientation.name),
            REQUEST_EDIT_CONTROLS,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_EDIT_CONTROLS) root.post { layoutGame() }
    }

    internal fun menuReset() {
        confirm(getString(R.string.emu_menu_reset), getString(R.string.emu_reset_confirm), getString(R.string.action_reset)) {
            services.sessionExecutor.execute { session.reset() }
            closeMenu()
        }
    }

    internal fun menuRestartWithAdvance(enable: Boolean) {
        services.gameConfigs.update(gameId) { it.copy(overrides = it.overrides.copy(advanceMode = enable, safeMode = false)) }
        // Save a resume point first so the restart continues from here.
        val file = services.saveLayout.stateFile(gameId, StateSlot.AUTO)
        scope.launch {
            withContext(services.sessionDispatcher) { saveStateWithScreenshot(file, StateSlot.AUTO) }
            quitting = true
            menu?.dismiss()
            menu = null
            val playMs = currentPlayMs()
            withContext(services.sessionDispatcher) {
                session.flushSave()
                session.close()
                recordPlay(playMs)
                services.guard.sessionEnded()
            }
            finish()
            Nav.play(this@EmulationActivity, gameId, resumeAuto = true)
        }
    }

    // --- Background loops ------------------------------------------------------------------

    private fun startStatsLoop() {
        statsJob = scope.launch {
            while (isActive) {
                val stats = withContext(Dispatchers.Default) { session.stats() }
                if (osd.visibility == View.VISIBLE) {
                    osd.text = String.format(java.util.Locale.US, "%.1f fps · %3.0f%% · %s %dHz · buf %d · xrun %d%s",
                        stats.fps, stats.speed * 100, stats.audioBackend, stats.audioSampleRate, stats.audioBufferedFrames,
                        stats.audioUnderrunFrames, if (stats.rewindSeconds > 0) String.format(java.util.Locale.US, " · rw %.0fs", stats.rewindSeconds) else "")
                }
                banner.visibility = if (stats.saveWriteFailed) View.VISIBLE else View.GONE
                delay(500)
            }
        }
    }

    /** Advance Studio capture and experimental auto-upscaling of captured sprites. */
    private fun startDumpPolling(p: Prepared) {
        val upscaler = Scale2xUpscaler()
        dumpJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                var newKeys = false
                while (true) {
                    val (key, image) = session.pollDumpedSprite() ?: break
                    if (p.app.captureSprites) runCatching { services.studio.write(gameId, DumpedAsset(key, image)) }
                    if (p.config.autoUpscaleSprites && key !in p.replacements.map { it.first }) {
                        val raw = java.nio.ByteBuffer.allocate(image.pixels.size * 4).apply { asIntBuffer().put(image.pixels) }.array()
                        val hd = services.assetCache.getOrPut(raw, "scale2x-x2-v1") {
                            upscaler.upscale(image).let { upscaler.upscale(it) }
                        }
                        if (hd != null) {
                            session.addReplacementTexture(key, hd)
                            synchronized(replacementKeys) { replacementKeys += key }
                            newKeys = true
                        }
                    }
                }
                if (newKeys) session.setReplacementKeys(synchronized(replacementKeys) { replacementKeys.toLongArray() })
                delay(500)
            }
        }
    }

    companion object {
        private const val TAG = "Emulation"
        private const val REQUEST_EDIT_CONTROLS = 4501
    }
}
