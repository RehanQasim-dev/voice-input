package org.futo.voiceinput

import android.content.Context
import android.content.res.Configuration
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.futo.voiceinput.migration.scheduleModelMigrationJob
import org.futo.voiceinput.theme.UixThemeAuto
import org.futo.voiceinput.updates.scheduleUpdateCheckingJob

val SupportsNavbarExtension = Build.VERSION.SDK_INT >= 28

@Composable
fun navBarHeight(): Dp = with(LocalDensity.current) {
    if(SupportsNavbarExtension) {
        WindowInsets.systemBars.getBottom(this).toDp()
    } else {
        0.dp
    }
}


object PillPositionState {
    var offset: Offset by mutableStateOf(Offset.Unspecified)
    var size: IntSize by mutableStateOf(IntSize.Zero)
    var backspaceOffset: Offset by mutableStateOf(Offset.Unspecified)
    var enterOffset: Offset by mutableStateOf(Offset.Unspecified)
    var onInsetsRefreshRequest: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onEnter: (() -> Unit)? = null

    fun notifyMoved() {
        onInsetsRefreshRequest?.invoke()
    }
}

private const val PILL_DEFAULT_BOTTOM_MARGIN_DP = 12
private const val BUTTON_DEFAULT_BOTTOM_MARGIN_DP = 12
const val BACKSPACE_DEFAULT_CENTER_X_FRACTION = 0.30f
const val ENTER_DEFAULT_CENTER_X_FRACTION = 0.70f

@Composable
private fun DraggableActionButton(
    glyph: String,
    size: Dp,
    stored: Offset,
    defaultPos: Offset,
    maxX: Float,
    maxY: Float,
    assign: (Offset) -> Unit,
    onTap: () -> Unit
) {
    val pos = if (stored.isUnspecified) defaultPos else stored

    Surface(
        modifier = Modifier
            .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
            .size(size)
            .recognizerSurfaceClickable(
                disabled = false,
                onPauseVAD = { },
                onTap = onTap,
                onLongPress = { },
                onDrag = { delta ->
                    val current = if (stored.isUnspecified) defaultPos else stored
                    assign(
                        Offset(
                            x = (current.x + delta.x).coerceIn(0f, maxX),
                            y = (current.y + delta.y).coerceIn(0f, maxY)
                        )
                    )
                    PillPositionState.notifyMoved()
                }
            ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(percent = 50)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                glyph,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RecognizerInputMethodWindow(switchBack: (() -> Unit)? = null, allowClick: Boolean = false, onPauseVAD: (Boolean) -> Unit = { }, onFinish: () -> Unit = { }, onTap: () -> Unit = { }, content: @Composable ColumnScope.() -> Unit) {
    UixThemeAuto(false) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val pillSize = 56.dp
            val pillSizePx = with(density) { pillSize.toPx() }

            val defaultPos = with(density) {
                Offset(
                    x = (maxWidth - pillSize).toPx() / 2f,
                    y = (maxHeight - pillSize - PILL_DEFAULT_BOTTOM_MARGIN_DP.dp).toPx()
                )
            }

            val rootWidthPx = with(density) { maxWidth.toPx() }
            val rootHeightPx = with(density) { maxHeight.toPx() }
            val maxX = (rootWidthPx - pillSizePx).coerceAtLeast(0f)
            val maxY = (rootHeightPx - pillSizePx).coerceAtLeast(0f)

            val rawPos = if (PillPositionState.offset.isUnspecified) defaultPos else PillPositionState.offset
            val pos = Offset(rawPos.x.coerceIn(0f, maxX), rawPos.y.coerceIn(0f, maxY))

            val buttonSize = 44.dp
            val buttonSizePx = with(density) { buttonSize.toPx() }
            val buttonMaxX = (rootWidthPx - buttonSizePx).coerceAtLeast(0f)
            val buttonMaxY = (rootHeightPx - buttonSizePx).coerceAtLeast(0f)
            val buttonBottomY = with(density) {
                rootHeightPx - buttonSizePx - BUTTON_DEFAULT_BOTTOM_MARGIN_DP.dp.toPx()
            }
            val backspaceDefault = Offset(
                x = rootWidthPx * BACKSPACE_DEFAULT_CENTER_X_FRACTION - buttonSizePx / 2f,
                y = buttonBottomY
            )
            val enterDefault = Offset(
                x = rootWidthPx * ENTER_DEFAULT_CENTER_X_FRACTION - buttonSizePx / 2f,
                y = buttonBottomY
            )

            Surface(
                modifier = Modifier
                    .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .size(pillSize)
                    .onSizeChanged { PillPositionState.size = it }
                    .recognizerSurfaceClickable(
                        disabled = !allowClick,
                        onPauseVAD = onPauseVAD,
                        onTap = onTap,
                        onLongPress = { switchBack?.invoke() },
                        onDrag = { delta ->
                            val current = if (PillPositionState.offset.isUnspecified) defaultPos else PillPositionState.offset

                            PillPositionState.offset = Offset(
                                x = (current.x + delta.x).coerceIn(0f, maxX),
                                y = (current.y + delta.y).coerceIn(0f, maxY)
                            )
                            PillPositionState.notifyMoved()
                        }
                    ),
                color = Color.Transparent,
                shape = RoundedCornerShape(percent = 50)
            ) {
                Column {
                    content()
                }
            }

            DraggableActionButton(
                glyph = "⌫",
                size = buttonSize,
                stored = PillPositionState.backspaceOffset,
                defaultPos = backspaceDefault,
                maxX = buttonMaxX,
                maxY = buttonMaxY,
                assign = { PillPositionState.backspaceOffset = it },
                onTap = { PillPositionState.onBackspace?.invoke() }
            )

            DraggableActionButton(
                glyph = "↵",
                size = buttonSize,
                stored = PillPositionState.enterOffset,
                defaultPos = enterDefault,
                maxX = buttonMaxX,
                maxY = buttonMaxY,
                assign = { PillPositionState.enterOffset = it },
                onTap = { PillPositionState.onEnter?.invoke() }
            )
        }
    }
}


@Preview
@Composable
fun RecognizeIMELoadingPreview() {
    RecognizerInputMethodWindow(switchBack = { }) {
        RecognizeLoadingCircle()
    }
}

@Preview
@Composable
fun PreviewRecognizeViewLoadedIME() {
    RecognizerInputMethodWindow(switchBack = { }) {
        InnerRecognize()
    }
}
@Preview
@Composable
fun PreviewRecognizeViewNoMicIME() {
    RecognizerInputMethodWindow(switchBack = { }) {
        RecognizeMicError(openSettings = { })
    }
}


val punctuationChars = setOf('!', '?', '.', ',')
class VoiceInputMethodService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val mSavedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = mSavedStateRegistryController.savedStateRegistry

    private val mLifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle
        get() = mLifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore
        get() = store

    private fun handleLifecycleEvent(event: Lifecycle.Event) =
        mLifecycleRegistry.handleLifecycleEvent(event)

    private val inputMethodManager: InputMethodManager
        get() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    override fun onCreate() {
        super.onCreate()
        mSavedStateRegistryController.performRestore(null)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        scheduleUpdateCheckingJob(applicationContext)
        scheduleModelMigrationJob(applicationContext)
    }

    private val recognizer = object : RecognizerView() {
        override val context: Context
            get() = this@VoiceInputMethodService
        override val lifecycleScope: LifecycleCoroutineScope
            get() = this@VoiceInputMethodService.lifecycle.coroutineScope

        override val autoStartRecording: Boolean = false

        private val currentContent: MutableState<@Composable () -> Unit> = mutableStateOf( { } )
        override fun setContent(content: @Composable () -> Unit) {
            currentContent.value = content
            composeView?.setContent { content() }
        }

        fun refreshContent() {
            composeView?.setContent { currentContent.value() }
        }

        override fun onCancel() {
            needsInitialization = true
            reset()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                inputMethodManager.switchToLastInputMethod(window.window!!.attributes.token)
            }
        }

        var prevText: CharSequence? = null
        var nextText: CharSequence? = null
        override fun decodingStarted() {
            this@VoiceInputMethodService.currentInputConnection.also {
                prevText = it.getTextBeforeCursor(1, 0)
                nextText = it.getTextAfterCursor(1, 0)
            }
        }

        override fun sendResult(result: String) {
            this@VoiceInputMethodService.currentInputConnection.also {
                var modifiedResult = result

                // Insert space automatically if ended at punctuation
                // TODO: Could send text before cursor as whisper prompt

                if(!prevText.isNullOrBlank()) {
                    val lastChar = prevText?.last()

                    if (punctuationChars.contains(lastChar)) {
                        modifiedResult = " $result"
                    }
                }

                /*
                if(!nextText.isNullOrBlank()) {
                    val oldPunctuation = nextText?.first()
                    val newPunctuation = result.last()

                    if (punctuationChars.contains(oldPunctuation) && punctuationChars.contains(newPunctuation)) {
                        it.deleteSurroundingText(0, 1)
                    }
                }
                */

                it.commitText(modifiedResult, 1)
            }

            reset()
            showIdle()
        }

        override fun sendPartialResult(result: String): Boolean {
            if(this@VoiceInputMethodService.currentInputConnection != null) {
                this@VoiceInputMethodService.currentInputConnection.setComposingText(result, 1)
                return true
            } else {
                return false
            }
        }

        override fun requestPermission() {
            // We can't ask for permission from a service
            // TODO: We could launch an activity and request it that way

            permissionResultRejected()
        }

        @Composable
        override fun Window(onClose: () -> Unit, allowClick: Boolean, onPauseVAD: (Boolean) -> Unit, onFinish: () -> Unit, onTap: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
            RecognizerInputMethodWindow(switchBack = onClose, onPauseVAD = onPauseVAD, onFinish = onFinish, onTap = onTap, allowClick = allowClick) {
                content()
            }
        }
    }

    private fun setOwners() {
        val decorView = window.window?.decorView
        if (decorView?.findViewTreeLifecycleOwner() == null) {
            decorView?.setViewTreeLifecycleOwner(this)
        }
        if (decorView?.findViewTreeViewModelStoreOwner() == null) {
            decorView?.setViewTreeViewModelStoreOwner(this)
        }
        if (decorView?.findViewTreeSavedStateRegistryOwner() == null) {
            decorView?.setViewTreeSavedStateRegistryOwner(this)
        }

        window.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private var composeView: ComposeView? = null

    override fun onCreateInputView(): View {
        // The input view is the main view where the user inputs text via keyclicks, handwriting,
        // gestures, or in this case there is a voice input menu.
        window.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.window?.setFormat(android.graphics.PixelFormat.TRANSLUCENT)

        PillPositionState.onInsetsRefreshRequest = {
            window.window?.decorView?.requestLayout()
        }

        PillPositionState.onBackspace = {
            val ic = currentInputConnection
            if (ic != null) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
        }

        PillPositionState.onEnter = {
            val ic = currentInputConnection
            if (ic != null) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }

        composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setParentCompositionContext(null)

            this@VoiceInputMethodService.setOwners()
        }

        updateNavigationBarVisibility()
        return composeView!!
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        val lp = win.attributes
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        win.attributes = lp
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)

        val dm = resources.displayMetrics
        val density = dm.density
        val pillPx = 56f * density
        val buttonPx = 44f * density
        val marginPx = PILL_DEFAULT_BOTTOM_MARGIN_DP * density

        fun rectFor(offset: Offset, sizePx: Float, defaultCenterXFraction: Float): android.graphics.Rect {
            val side = sizePx.toInt()
            val left = if (offset.isUnspecified) {
                ((dm.widthPixels * defaultCenterXFraction) - sizePx / 2f).toInt()
            } else {
                offset.x.toInt()
            }
            val top = if (offset.isUnspecified) {
                (dm.heightPixels - sizePx - marginPx).toInt()
            } else {
                offset.y.toInt()
            }
            return android.graphics.Rect(left, top, left + side, top + side)
        }

        val region = Region()
        region.union(rectFor(PillPositionState.offset, pillPx, 0.50f))
        region.union(rectFor(PillPositionState.backspaceOffset, buttonPx, BACKSPACE_DEFAULT_CENTER_X_FRACTION))
        region.union(rectFor(PillPositionState.enterOffset, buttonPx, ENTER_DEFAULT_CENTER_X_FRACTION))

        val decorHeight = window.window?.decorView?.height ?: 0
        val zeroClaim = if (decorHeight > 0) decorHeight else dm.heightPixels

        outInsets.contentTopInsets = zeroClaim
        outInsets.visibleTopInsets = zeroClaim
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(region)
    }

    override fun onCreateCandidatesView(): View? {
        // The candidates view shows potential word corrections or suggestions for the user to select.
        // Return null, as the voice input does not need this.
        return null
    }

    private fun updateNavigationBarVisibility() {
        if(SupportsNavbarExtension) {
            window.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
    }

    private var needsInitialization = true
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> {
                // number
            }
            InputType.TYPE_CLASS_DATETIME -> {
                // date time ??
            }
            InputType.TYPE_CLASS_PHONE -> {
                // phone number
                // could add whisper prompt like "My phone number is "
            }
            InputType.TYPE_CLASS_TEXT -> {
                // text :)
                if(info.inputType == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                    // ...
                }
            }
        }

        if(needsInitialization) {
            needsInitialization = false
            recognizer.reset()
            recognizer.init()
        } else {
            println("Continuing recording, likely due to landscape/portrait switch")
            recognizer.refreshContent()
        }
        // TODO: Idle state
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        println("Finish input view")
        recognizer.reset()

        needsInitialization = true
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNavigationBarVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()

        println("Destroy")
        recognizer.releaseModel()
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}