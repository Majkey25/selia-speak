package cz.localvoice.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class VoiceAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val capture = AudioCapture()
    private val speechGate = SpeechGate()
    private lateinit var windowManager: WindowManager
    private lateinit var bubble: TextView
    private lateinit var cancelBubble: TextView
    private lateinit var undoBubble: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var cancelParams: WindowManager.LayoutParams
    private lateinit var undoParams: WindowManager.LayoutParams
    private var overlayAttached = false
    private var cancelAttached = false
    private var undoAttached = false
    private var undoHideJob: Job? = null
    private var copyDialog: AlertDialog? = null
    private var copyHideJob: Job? = null
    private var processingJob: Job? = null
    private val nativeWork = Mutex()
    private var state = State.HIDDEN
    private var session: DictationSession? = null
    private var undo: UndoSnapshot? = null
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scope.launch { refreshTarget() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        getSharedPreferences(SpeakSettings.PREFERENCES, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(settingsListener)
        createOverlay()
        ServiceConnection.update(true)
        refreshTarget()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) refreshTarget()
    }

    override fun onInterrupt() {
        dismissCopy()
        cancelSession()
        hideCancel()
        hideUndo(clearSnapshot = true)
        state = State.HIDDEN
        detachOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ServiceConnection.update(false)
        onInterrupt()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ServiceConnection.update(false)
        dismissCopy()
        getSharedPreferences(SpeakSettings.PREFERENCES, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(settingsListener)
        cancelSession()
        detachOverlay()
        hideUndo(clearSnapshot = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val bubbleSize = bubbleSize(UserSettings.bubble(this))
        params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt("overlay_x", resources.displayMetrics.widthPixels - dp(80))
                .coerceIn(0, (resources.displayMetrics.widthPixels - bubbleSize).coerceAtLeast(0))
            y = preferences.getInt("overlay_y", resources.displayMetrics.heightPixels / 2)
                .coerceIn(0, (resources.displayMetrics.heightPixels - bubbleSize).coerceAtLeast(0))
        }
        bubble = BubbleView(this).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.microphone_label)
            textSize = 12f
            setTextColor(Color.WHITE)
            elevation = dp(8).toFloat()
            contentDescription = getString(R.string.dictation_start)
            background = circle(Color.rgb(17, 17, 17))
            setOnClickListener { handleBubbleClick() }
            setOnTouchListener(DragListener())
        }
        bubble.alpha = UserSettings.bubble(this).opacityPercent / 100f
        cancelParams = WindowManager.LayoutParams(
            dp(48),
            dp(48),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        cancelBubble = BubbleView(this).apply {
            gravity = Gravity.CENTER
            text = "×"
            textSize = 24f
            setTextColor(Color.WHITE)
            contentDescription = getString(R.string.cancel_recording)
            background = circle(Color.rgb(65, 65, 65))
            setOnClickListener { cancelRecording() }
        }
        undoParams = WindowManager.LayoutParams(
            dp(48),
            dp(48),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        undoBubble = BubbleView(this).apply {
            gravity = Gravity.CENTER
            text = "↶"
            textSize = 20f
            setTextColor(Color.WHITE)
            elevation = dp(8).toFloat()
            contentDescription = getString(R.string.undo_last_insertion)
            background = circle(Color.rgb(17, 17, 17))
            setOnClickListener { undoLastEdit() }
        }
    }

    private fun handleBubbleClick() {
        when (state) {
            State.HIDDEN -> Unit
            State.READY -> startRecording()
            State.LISTENING -> stopAndProcess()
            State.PROCESSING -> toast(getString(R.string.dictation_processing))
        }
    }

    private fun startRecording() {
        dismissCopy()
        val config = runCatching { SpeakSettings.load(this) }.getOrElse {
            toast(getString(R.string.dictation_settings_invalid))
            return
        }
        if (!config.enabled || !keyboardVisible() || !UserSettings.accessibilityDisclosureAccepted(this)) return
        val facts = focusedEditable()?.toFacts()
        if (facts == null || !FieldTargetPolicy.isSupported(facts, excludedPackages())) {
            toast(getString(R.string.unsupported_field))
            resetTargetState()
            return
        }
        val selected = ModelCatalog.byId(config.activeModelId)
        if (selected == null || !ModelRepository(this).isInstalled(selected)) {
            toast(getString(R.string.dictation_model_required))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.dictation_microphone_required))
            return
        }
        val running = DictationSession(config, FieldDraft(FieldSnapshot.from(facts)))
        session = running
        hideUndo(clearSnapshot = true)
        try {
            val previewNeeded = config.routingEnabled ||
                config.livePreview && selected.streaming && running.draft.original.selectedText.isEmpty()
            if (previewNeeded) {
                running.recognition = scope.async(Dispatchers.Default) {
                    nativeWork.withLock { recognizeWhileRecording(running) }
                }
                running.recognition?.invokeOnCompletion { error ->
                    if (error != null && error !is CancellationException) scope.launch {
                        if (session === running && state == State.LISTENING) stopAndProcess()
                    }
                }
            } else running.previewDone.set(true)
            capture.start(this, scope, onSamples = { samples ->
                if (!running.previewDone.get() && running.input.trySend(samples).isFailure) {
                    running.overflow.set(true)
                    running.input.close()
                }
            }, onLimit = { if (session === running) stopAndProcess() })
            setState(State.LISTENING)
        } catch (_: RuntimeException) {
            cancelSession()
            toast(getString(R.string.dictation_microphone_failed))
            resetTargetState()
        }
    }

    private fun stopAndProcess() {
        if (state != State.LISTENING) return
        val running = session ?: return
        setState(State.PROCESSING)
        processingJob = scope.launch {
            var rawText: String? = null
            try {
                val samples = capture.stop()
                running.input.close()
                val speech = try {
                    withContext(Dispatchers.Default) { speechGate.trim(samples, AudioCapture.SAMPLE_RATE) }
                } catch (_: IllegalArgumentException) {
                    toast(getString(R.string.no_speech_detected))
                    restoreDraft(running.draft)
                    running.recognition?.cancel()
                    resetTargetState()
                    return@launch
                }
                val preview = running.recognition?.await()
                val result = preview?.result?.takeIf { !running.overflow.get() } ?: withContext(Dispatchers.Default) {
                    nativeWork.withLock {
                        val choice = preview?.choice ?: selectModel(running.config, null)
                        verifyModel(choice.model)
                        LocalRecognizer(this@VoiceAccessibilityService, choice.model,
                            choice.language.takeIf { choice.model.manualLanguage }).use {
                            it.transcribe(speech)
                        }
                    }
                }
                val transcript = result.text.trim().takeIf(String::isNotEmpty)
                    ?: throw IllegalArgumentException("No speech text")
                rawText = transcript
                val finalText = if (running.config.semanticEnabled) {
                    withContext(Dispatchers.Default) {
                        nativeWork.withLock {
                            val model = ModelCatalog.semanticModels.single()
                            verifyModel(model)
                            val repository = ModelRepository(this@VoiceAccessibilityService)
                            SemanticEngine(this@VoiceAccessibilityService, repository.file(model, "model.litertlm").path).use {
                                it.editSession(transcript,
                                    result.language ?: preview?.choice?.language ?: running.config.language.orEmpty())
                            }
                        }
                    }
                } else transcript
                coroutineContext.ensureActive()
                if (session !== running) return@launch
                if (applyDraft(running.draft, finalText)) {
                    setState(State.READY)
                    showUndo()
                } else {
                    restoreDraft(running.draft)
                    resetTargetState()
                    offerCopy(finalText)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (session !== running) return@launch
                restoreDraft(running.draft)
                toast(getString(if (rawText != null && running.config.semanticEnabled) {
                    R.string.dictation_semantic_failed
                } else if (running.config.routingEnabled) R.string.dictation_route_failed else R.string.dictation_failed))
                resetTargetState()
                rawText?.let(::offerCopy)
            } finally {
                running.input.close()
                running.recognition?.cancel()
                if (session === running) session = null
            }
        }
    }

    private fun cancelRecording() {
        if (state != State.LISTENING && state != State.PROCESSING) return
        cancelSession()
        toast(getString(R.string.recording_cancelled))
        resetTargetState()
    }

    private fun applyDraft(draft: FieldDraft, text: String): Boolean {
        val node = focusedEditable() ?: return false
        val current = node.toFacts() ?: return false
        if (!FieldTargetPolicy.isSupported(current, excludedPackages())) return false
        val result = draft.replacement(text, current) ?: return false
        if (!setField(node, result.text, result.cursor, result.cursor)) return false
        draft.acknowledge(result)
        undo = UndoSnapshot(after = draft.expected, before = draft.original)
        return true
    }

    private fun setField(node: AccessibilityNodeInfo, text: String, start: Int, end: Int): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            },
        )
        return true
    }

    private fun restoreDraft(draft: FieldDraft) {
        if (draft.expected == draft.original) return
        val node = focusedEditable() ?: return
        val facts = node.toFacts() ?: return
        if (FieldTargetPolicy.isSupported(facts, excludedPackages()) && draft.canRestore(facts)) {
            val original = draft.original.facts
            setField(node, original.text, original.selectionStart, original.selectionEnd)
        }
        hideUndo(clearSnapshot = true)
    }

    private fun cancelSession() {
        val running = session
        session = null
        capture.cancel()
        running?.input?.close()
        running?.recognition?.cancel()
        processingJob?.cancel()
        processingJob = null
        running?.let { restoreDraft(it.draft) }
    }

    private suspend fun verifyModel(model: ModelSpec) {
        check(ModelRepository(this).verify(model)) { "Model integrity check failed" }
        coroutineContext.ensureActive()
    }

    private fun selectModel(config: SpeakConfig, detected: String?): RecognitionChoice {
        val installed = ModelCatalog.asrModels.filter(ModelRepository(this)::isInstalled).map { it.id }.toSet()
        val model = SpeakSettings.selectModel(config, detected, installed)
        return RecognitionChoice(model, if (config.routingEnabled) detected else config.language)
    }

    private suspend fun recognizeWhileRecording(running: DictationSession): SessionRecognition {
        try {
            val prefix = mutableListOf<FloatArray>()
            var prefixSize = 0
            var language: String? = null
            if (running.config.routingEnabled) {
                while (prefixSize < LANGUAGE_PREFIX_SAMPLES) {
                    val chunk = running.input.receiveCatching().getOrNull() ?: break
                    prefix += chunk
                    prefixSize += chunk.size
                }
                check(prefixSize > AudioCapture.SAMPLE_RATE / 4) { "Not enough speech for language detection" }
                val samples = FloatArray(prefixSize)
                var offset = 0
                prefix.forEach { chunk -> chunk.copyInto(samples, offset); offset += chunk.size }
                val detector = requireNotNull(ModelCatalog.byId(running.config.detectorModelId))
                verifyModel(detector)
                language = SpeechLanguageDetector(this, detector).use { it.detect(samples) }
            }
            val choice = selectModel(running.config, language)
            if (!running.config.livePreview || !choice.model.streaming || running.draft.original.selectedText.isNotEmpty()) {
                return SessionRecognition(choice, null)
            }
            verifyModel(choice.model)
            val result = LocalRecognizer(this, choice.model,
                choice.language.takeIf { choice.model.manualLanguage }).use { recognizer ->
                recognizer.openStream().use streamUse@{ stream ->
                    prefix.forEach { stream.accept(it) }
                    var lastText = ""
                    for (chunk in running.input) {
                        coroutineContext.ensureActive()
                        if (running.overflow.get()) return@streamUse null
                        val partial = stream.accept(chunk)
                        if (partial.text.isNotBlank() && partial.text != lastText) {
                            withContext(Dispatchers.Main.immediate) {
                                if (session === running && state == State.LISTENING && !applyDraft(running.draft, partial.text)) {
                                    cancelSession()
                                    resetTargetState()
                                    toast(getString(R.string.target_lost))
                                }
                            }
                            lastText = partial.text
                        }
                    }
                    coroutineContext.ensureActive()
                    if (running.overflow.get()) null else stream.finish()
                }
            }
            return SessionRecognition(choice, result)
        } finally {
            running.previewDone.set(true)
            running.input.close()
        }
    }

    private fun undoLastEdit() {
        val saved = undo ?: return hideUndo(clearSnapshot = true)
        val node = focusedEditable()
        val current = node?.toFacts()
        if (current == null || !FieldTargetPolicy.isFresh(saved.after, current)) {
            toast(getString(R.string.dictation_undo_blocked))
            hideUndo(clearSnapshot = true)
            hideCancel()
            return resetTargetState()
        }
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                saved.before.facts.text,
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            toast(getString(R.string.dictation_undo_failed))
        } else {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                        saved.before.facts.selectionStart,
                    )
                    putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                        saved.before.facts.selectionEnd,
                    )
                },
            )
        }
        hideUndo(clearSnapshot = true)
        resetTargetState()
    }

    private fun focusedEditable(): AccessibilityNodeInfo? = windows
        .asSequence()
        .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        .firstOrNull { it.isEditable }

    private fun AccessibilityNodeInfo.toFacts(): FieldFacts? {
        val value = text?.toString().orEmpty()
        if (value.length > MAX_FIELD_CHARS) return null
        val start = textSelectionStart.takeIf { it >= 0 }?.coerceAtMost(value.length) ?: value.length
        val end = textSelectionEnd.takeIf { it >= start }?.coerceAtMost(value.length) ?: start
        val bounds = Rect().also(::getBoundsInScreen)
        return FieldFacts(
            packageName = packageName?.toString().orEmpty(),
            windowId = windowId,
            viewId = viewIdResourceName,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            text = value,
            selectionStart = start,
            selectionEnd = end,
            editable = isEditable,
            enabled = isEnabled,
            visible = isVisibleToUser,
            password = isPassword,
            inputType = inputType,
            className = className?.toString(),
            supportsSetText = actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
        )
    }

    private fun refreshTarget() {
        val config = runCatching { SpeakSettings.load(this) }.getOrNull()
        if (!UserSettings.accessibilityDisclosureAccepted(this) || config?.enabled != true) {
            cancelSession()
            hideUndo(clearSnapshot = true)
            hideCancel()
            state = State.HIDDEN
            detachOverlay()
            return
        }
        applyBubblePreferences()
        val facts = focusedEditable()?.toFacts()
        val running = session
        val supported = keyboardVisible() && facts != null && FieldTargetPolicy.isSupported(facts, excludedPackages())
        val sessionChanged = running != null &&
            (running.config != config || facts == null || !FieldTargetPolicy.isFresh(running.draft.expected, facts))
        if (sessionChanged) {
            cancelSession()
            toast(getString(R.string.target_lost))
            state = State.HIDDEN
        }
        if (!supported) {
            if (session != null) {
                cancelSession()
                toast(getString(R.string.target_lost))
            }
            hideUndo(clearSnapshot = true)
            state = State.HIDDEN
            detachOverlay()
            return
        }
        attachOverlay()
        if (state == State.HIDDEN) setState(State.READY)
    }

    private fun resetTargetState() {
        state = State.HIDDEN
        refreshTarget()
    }

    private fun keyboardVisible(): Boolean = windows.any { window ->
        window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
            !Rect().also(window::getBoundsInScreen).isEmpty
    }

    private fun attachOverlay() {
        if (overlayAttached || !::bubble.isInitialized) return
        windowManager.addView(bubble, params)
        overlayAttached = true
    }

    private fun detachOverlay() {
        hideCancel()
        if (!overlayAttached || !::bubble.isInitialized) return
        runCatching { windowManager.removeView(bubble) }
        overlayAttached = false
    }

    private fun showCancel() {
        if (cancelAttached || !overlayAttached || !::cancelBubble.isInitialized) return
        positionCancel()
        windowManager.addView(cancelBubble, cancelParams)
        cancelAttached = true
    }

    private fun hideCancel() {
        if (!cancelAttached || !::cancelBubble.isInitialized) return
        runCatching { windowManager.removeView(cancelBubble) }
        cancelAttached = false
    }

    private fun positionCancel() {
        val left = params.x - cancelParams.width - dp(8)
        cancelParams.x = if (left >= 0) left else {
            (params.x + params.width + dp(8)).coerceAtMost(
                resources.displayMetrics.widthPixels - cancelParams.width,
            )
        }
        cancelParams.y = params.y + (params.height - cancelParams.height) / 2
    }

    private fun showUndo() {
        if (undo == null || !overlayAttached || !::undoBubble.isInitialized) return
        hideUndo(clearSnapshot = false)
        positionUndo()
        windowManager.addView(undoBubble, undoParams)
        undoAttached = true
        undoHideJob = scope.launch {
            delay(UNDO_TIMEOUT_MS)
            undoHideJob = null
            removeUndoView()
            undo = null
        }
    }

    private fun hideUndo(clearSnapshot: Boolean) {
        undoHideJob?.cancel()
        undoHideJob = null
        removeUndoView()
        if (clearSnapshot) undo = null
    }

    private fun removeUndoView() {
        if (!undoAttached || !::undoBubble.isInitialized) return
        runCatching { windowManager.removeView(undoBubble) }
        undoAttached = false
    }

    private fun positionUndo() {
        undoParams.x = (params.x - undoParams.width - dp(8)).coerceAtLeast(0)
        undoParams.y = params.y
    }

    private fun applyBubblePreferences() {
        if (!::bubble.isInitialized) return
        val preferences = UserSettings.bubble(this)
        val size = bubbleSize(preferences)
        val changed = params.width != size || params.height != size
        params.width = size
        params.height = size
        bubble.alpha = preferences.opacityPercent / 100f
        if (overlayAttached && changed) windowManager.updateViewLayout(bubble, params)
    }

    private fun bubbleSize(preferences: BubblePreferences): Int = maxOf(
        dp(48),
        dp(58) * preferences.sizePercent / 100,
    )

    private fun excludedPackages(): Set<String> = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        .getStringSet(EXCLUDED_PACKAGES, emptySet())
        .orEmpty()

    private fun setState(value: State) {
        state = value
        if (!::bubble.isInitialized) return
        when (value) {
            State.HIDDEN -> {
                hideCancel()
                detachOverlay()
            }
            State.READY -> {
                hideCancel()
                configureBubble("MIC", Color.rgb(36, 91, 219), getString(R.string.dictation_start))
            }
            State.LISTENING -> {
                configureBubble("✓", Color.rgb(179, 38, 30), getString(R.string.dictation_finish))
                showCancel()
            }
            State.PROCESSING -> {
                configureBubble("…", Color.rgb(80, 80, 80), getString(R.string.dictation_local_processing))
                showCancel()
            }
        }
    }

    private fun configureBubble(label: String, color: Int, description: String) {
        bubble.text = label
        bubble.background = circle(color)
        bubble.contentDescription = description
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), Color.WHITE)
    }

    internal fun offerCopy(text: String) {
        dismissCopy()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.copy_result_title)
            .setMessage(getString(R.string.copy_result_message) + "\n\n" + text)
            .setPositiveButton(R.string.copy_result) { _, _ -> copy(text) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        copyDialog = dialog
        dialog.setOnDismissListener {
            if (copyDialog === dialog) {
                copyHideJob?.cancel()
                copyHideJob = null
                copyDialog = null
            }
        }
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.show()
        copyHideJob = scope.launch {
            delay(COPY_PREVIEW_TIMEOUT_MS)
            dialog.dismiss()
        }
    }

    private fun dismissCopy() {
        copyHideJob?.cancel()
        copyHideJob = null
        copyDialog?.dismiss()
        copyDialog = null
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.app_name), text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class DragListener : View.OnTouchListener {
        private val gesture = BubbleGesture(dp(6))
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var activeView: View? = null
        private val holdAction = Runnable {
            if (gesture.hold() == BubbleGesture.Action.HOLD_START && state == State.READY) startRecording()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    gesture.down()
                    activeView = view
                    view.postDelayed(holdAction, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downX).toInt()
                    val deltaY = (event.rawY - downY).toInt()
                    if (gesture.move(deltaX, deltaY) == BubbleGesture.Action.DRAG) {
                        view.removeCallbacks(holdAction)
                        params.x = (startX + deltaX).coerceIn(
                            0,
                            (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0),
                        )
                        params.y = (startY + deltaY).coerceIn(
                            0,
                            (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0),
                        )
                        windowManager.updateViewLayout(bubble, params)
                        if (undoAttached) {
                            positionUndo()
                            windowManager.updateViewLayout(undoBubble, undoParams)
                        }
                        if (cancelAttached) {
                            positionCancel()
                            windowManager.updateViewLayout(cancelBubble, cancelParams)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(holdAction)
                    when (gesture.up()) {
                        BubbleGesture.Action.TAP -> view.performClick()
                        BubbleGesture.Action.HOLD_END -> if (state == State.LISTENING) stopAndProcess()
                        BubbleGesture.Action.DRAG_END -> getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit {
                            putInt("overlay_x", params.x)
                            putInt("overlay_y", params.y)
                        }
                        else -> Unit
                    }
                    activeView = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    activeView?.removeCallbacks(holdAction)
                    if (gesture.up() == BubbleGesture.Action.HOLD_END && state == State.LISTENING) {
                        cancelRecording()
                    }
                    activeView = null
                }
            }
            return true
        }
    }

    private enum class State { HIDDEN, READY, LISTENING, PROCESSING }

    private class BubbleView(context: Context) : TextView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private data class UndoSnapshot(val after: FieldSnapshot, val before: FieldSnapshot)

    private data class RecognitionChoice(val model: ModelSpec, val language: String?)
    private data class SessionRecognition(val choice: RecognitionChoice, val result: RecognitionResult?)

    private class DictationSession(val config: SpeakConfig, val draft: FieldDraft) {
        val input = Channel<FloatArray>(64)
        val overflow = AtomicBoolean(false)
        val previewDone = AtomicBoolean(false)
        var recognition: Deferred<SessionRecognition>? = null
    }

    companion object {
        const val PREFERENCES = "local_voice"
        private const val EXCLUDED_PACKAGES = "excluded_packages"
        private const val UNDO_TIMEOUT_MS = 8_000L
        private const val COPY_PREVIEW_TIMEOUT_MS = 60_000L
        private const val LANGUAGE_PREFIX_SAMPLES = AudioCapture.SAMPLE_RATE * 3
    }
}
