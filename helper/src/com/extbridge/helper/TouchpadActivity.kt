package com.extbridge.helper

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.extbridge.helper.ui.ExtHyperTheme
import com.extbridge.helper.ui.ExtThemePrefs
import com.extbridge.helper.ui.ExtVectorIcons
import com.extbridge.helper.ui.HyperAmber
import com.extbridge.helper.ui.HyperBlue
import com.extbridge.helper.ui.HyperRed
import com.extbridge.helper.ui.isEffectiveDark
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class TouchpadActivity : ComponentActivity() {

    companion object {
        private val SPEEDS = floatArrayOf(1.0f, 1.6f, 2.3f, 3.2f)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ioExec = Executors.newSingleThreadExecutor()
    private val moveLock = Any()

    private var pendingDx = 0f
    private var pendingDy = 0f
    private var moveInFlight = false
    @Volatile
    private var tpDestroyed = false
    @Volatile
    private var remotePrimaryHeld = false
    private var trackpadViewRef: NativeTrackpadView? = null
    private var injectWarnToasted = false

    // Reactive UI State
    private var themeModeIdx by mutableIntStateOf(0)
    private var speedIdx by mutableIntStateOf(1)
    private var useCtrlV by mutableStateOf(false)
    private var oneHandPortrait by mutableStateOf(false)
    private var imeVisible by mutableStateOf(false)
    private var imeText by mutableStateOf("")
    private var dragLocked by mutableStateOf(false)
    private var inputToExt by mutableStateOf(true)
    private var injectDenied by mutableStateOf(false)

    private var virtW by mutableIntStateOf(2560)
    private var virtH by mutableIntStateOf(1440)
    private var curX by mutableIntStateOf(1280)
    private var curY by mutableIntStateOf(720)

    private val binderReadyCb = Runnable {
        handler.post {
            if (!tpDestroyed) refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ensureOnDisplay0()) return
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        themeModeIdx = ExtThemePrefs.getThemeModeIndex(this)
        BridgeService.ensureStarted(this)
        refreshStatus()

        setContent {
            ExtHyperTheme(themeModeIndex = themeModeIdx) {
                val dark = isEffectiveDark(themeModeIdx)
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                TouchpadScreen(dark = dark)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        themeModeIdx = ExtThemePrefs.getThemeModeIndex(this)
        BridgeClient.setOnBinderReady(binderReadyCb)
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this)
        refreshStatus()
    }

    private fun releaseHeldMouseIfNeeded() {
        trackpadViewRef?.resetTouchGestureState()
        val wasHeld = remotePrimaryHeld || dragLocked
        remotePrimaryHeld = false
        if (dragLocked) dragLocked = false
        if (wasHeld) {
            synchronized(moveLock) {
                pendingDx = 0f
                pendingDy = 0f
            }
            BridgeService.moveCursor(curX.toFloat(), curY.toFloat(), false)
            // Enqueue onto the exact same single-threaded FIFO ioExec as LEFT_DOWN (3) so LEFT_UP (4)
            // can never arrive before a pending LEFT_DOWN, and do not skip when tpDestroyed becomes true.
            runCatching {
                ioExec.execute {
                    runCatching { BridgeClient.sendMouse(4, 0f, 0f) }
                }
            }
        }
    }

    override fun onPause() {
        releaseHeldMouseIfNeeded()
        super.onPause()
        BridgeClient.removeOnBinderReady(binderReadyCb)
    }

    override fun onStop() {
        releaseHeldMouseIfNeeded()
        super.onStop()
    }

    override fun onDestroy() {
        releaseHeldMouseIfNeeded()
        tpDestroyed = true
        trackpadViewRef = null
        BridgeClient.removeOnBinderReady(binderReadyCb)
        handler.removeCallbacksAndMessages(null)
        ioExec.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun ensureOnDisplay0(): Boolean {
        return try {
            val d = display
            // Foldables: the cover panel is its own built-in display; never bounce between them,
            // only leave real external/virtual displays and land on the active built-in panel.
            if (d != null && !isBuiltinPanel(d)) {
                val i = Intent(this, TouchpadActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val opts = ActivityOptions.makeBasic().setLaunchDisplayId(BridgeService.getActiveBuiltinDisplayId(this))
                startActivity(i, opts.toBundle())
                finishAndRemoveTask()
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Built-in panel ids are the lowest ones (0 = inner screen, 1 = foldable cover panel). */
    private fun isBuiltinPanel(d: android.view.Display): Boolean = d.displayId in 0..5

    private fun refreshStatus() {
        Thread {
            val st = BridgeClient.getStatus()
            if (st.connected) {
                runOnUiThread {
                    if (tpDestroyed) return@runOnUiThread
                    virtW = st.virtW
                    virtH = st.virtH
                    curX = st.cursorX
                    curY = st.cursorY
                    inputToExt = (st.inputToExt == 1)
                    updateInjectState(st.injectStatus)
                }
            }
        }.start()
    }

    private fun updateInjectState(statusVal: Int) {
        val denied = (statusVal == -1)
        injectDenied = denied
        if (denied && !injectWarnToasted) {
            injectWarnToasted = true
            Toast.makeText(
                this,
                "系统拦截了外接屏点击注入，请开启「USB 调试（安全设置）」",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun toggleDragLock() {
        val next = !dragLocked
        dragLocked = next
        sendMouseSync(if (next) 3 else 4, 0f, 0f)
    }

    private fun performRightClickOrBack() {
        when (BridgeService.classifyRightClickTarget(curX, curY)) {
            1 -> return
            2 -> sendMouseSync(2, 1f, 0f)
            else -> sendMouseSync(2, 0f, 0f)
        }
    }

    private fun commitImeText() {
        val txt = imeText
        if (txt.isEmpty()) {
            Thread { BridgeClient.sendKey(KeyEvent.KEYCODE_ENTER, 0) }.start()
            return
        }
        val asciiOnly = txt.all { it.code in 0x20..0x7E }
        if (asciiOnly) {
            Thread { BridgeClient.sendAscii(txt) }.start()
        } else {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("extbridge", txt))
            val ctrlV = useCtrlV
            handler.postDelayed({
                Thread { BridgeClient.sendPaste(ctrlV) }.start()
            }, 35L)
        }
        imeText = ""
    }

    private fun applyMouseResultOnUi(type: Int, xy: IntArray?) {
        if (tpDestroyed || xy == null || xy.size < 2) return
        curX = xy[0]
        curY = xy[1]
        BridgeService.moveCursor(curX.toFloat(), curY.toFloat(), dragLocked || type == 3)
        if (xy.size >= 3) updateInjectState(xy[2])
    }

    private fun sendMouseSync(type: Int, a1: Float, a2: Float) {
        if (tpDestroyed || ioExec.isShutdown) return
        if (type == 3) {
            remotePrimaryHeld = true
        } else if (type == 4 || type == 1) {
            remotePrimaryHeld = false
        }
        if (type == 0) {
            var launch = false
            synchronized(moveLock) {
                pendingDx += a1
                pendingDy += a2
                if (!moveInFlight) {
                    moveInFlight = true
                    launch = true
                }
            }
            if (!launch) return
            try {
                ioExec.execute {
                    try {
                        while (!tpDestroyed && !ioExec.isShutdown) {
                            var dx: Float
                            var dy: Float
                            synchronized(moveLock) {
                                dx = pendingDx
                                dy = pendingDy
                                pendingDx = 0f
                                pendingDy = 0f
                                if (abs(dx) < 0.01f && abs(dy) < 0.01f) {
                                    moveInFlight = false
                                    return@execute
                                }
                            }
                            val xy = BridgeClient.sendMouse(0, dx, dy)
                            if (xy != null && xy.size >= 2) {
                                handler.post { applyMouseResultOnUi(0, xy) }
                            }
                        }
                    } finally {
                        synchronized(moveLock) { moveInFlight = false }
                    }
                }
            } catch (_: Throwable) {
                synchronized(moveLock) {
                    pendingDx = 0f
                    pendingDy = 0f
                    moveInFlight = false
                }
            }
            return
        }
        runCatching {
            ioExec.execute {
                if (tpDestroyed) return@execute
                val xy = BridgeClient.sendMouse(type, a1, a2)
                if (xy != null && xy.size >= 2) {
                    handler.post { applyMouseResultOnUi(type, xy) }
                }
            }
        }
    }

    @Composable
    private fun TouchpadScreen(dark: Boolean) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .systemBarsPadding()
                .imePadding(),
        ) {
            val isPortrait = maxHeight > maxWidth

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. Sleek Top Capsule Header
                Card(
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = ExtVectorIcons.ArrowBack,
                                contentDescription = "退出",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "大触摸板",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = "(${curX}, ${curY}) · ${virtW}×${virtH}",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ActionPill(
                            label = if (inputToExt) "扩展屏" else "本机屏",
                            icon = ExtVectorIcons.RouteSwap,
                            active = inputToExt,
                            onClick = {
                                Thread {
                                    val m = BridgeClient.toggleInput()
                                    runOnUiThread {
                                        if (m >= 0) inputToExt = (m == 1)
                                        BridgeService.refreshFromReceiver(this@TouchpadActivity)
                                    }
                                }.start()
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        ActionPill(
                            label = "键盘",
                            icon = ExtVectorIcons.Keyboard,
                            active = imeVisible,
                            onClick = {
                                imeVisible = !imeVisible
                                if (!imeVisible) {
                                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                                    imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                                }
                            },
                        )
                    }
                }

                // Conditional INJECT_EVENTS Warning
                if (injectDenied) {
                    Card(
                        colors = CardDefaults.defaultColors(color = HyperRed.copy(alpha = 0.14f)),
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = BridgeClient.getOemInjectHint(),
                            fontSize = 11.5.sp,
                            color = HyperRed,
                        )
                    }
                }

                // Optional One-Hand Portrait Thumb Reach Shelf
                if (isPortrait && oneHandPortrait && !imeVisible) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.62f),
                        cornerRadius = 18.dp,
                        insideMargin = PaddingValues(16.dp),
                        colors = CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        ),
                        onClick = { oneHandPortrait = false },
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "单手拇指热区模式",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "触控板与左右键已下移至底部 · 点此恢复全屏大触控板",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }

                // 2. Main Low-Latency Trackpad Canvas
                val padSurface = MiuixTheme.colorScheme.surfaceContainer.toArgb()
                val padStroke = MiuixTheme.colorScheme.outline.toArgb()
                val accentArgb = HyperBlue.toArgb()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            NativeTrackpadView(ctx).apply {
                                trackpadViewRef = this
                                applyThemeColors(padSurface, padStroke, accentArgb, dark)
                            }
                        },
                        update = { view ->
                            trackpadViewRef = view
                            view.applyThemeColors(padSurface, padStroke, accentArgb, dark)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = "单指移动 · 轻触左键 · 双指轻触右键 · 右侧滑轨/双指滚轮",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 14.dp, bottom = 10.dp),
                    )
                }

                // 3. Tactile Left / Drag-Lock / Right Physical Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TrackpadButton(
                        text = "左键点击",
                        active = false,
                        onClick = {
                            if (dragLocked) {
                                dragLocked = false
                                sendMouseSync(4, 0f, 0f)
                            } else {
                                sendMouseSync(1, 0f, 0f)
                            }
                        },
                        modifier = Modifier.weight(1.8f),
                    )
                    TrackpadButton(
                        text = if (dragLocked) "拖拽锁定" else "拖拽",
                        active = dragLocked,
                        activeColor = HyperAmber,
                        onClick = { toggleDragLock() },
                        modifier = Modifier.weight(1.2f),
                    )
                    TrackpadButton(
                        text = "右键 / 返回",
                        active = false,
                        onClick = { performRightClickOrBack() },
                        modifier = Modifier.weight(1.8f),
                    )
                }

                // 4. Clean Vector Bottom Action Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionPill(
                        label = "返回",
                        icon = ExtVectorIcons.ArrowBack,
                        active = false,
                        onClick = { Thread { BridgeClient.sendBack() }.start() },
                    )
                    ActionPill(
                        label = "扩展桌面",
                        icon = ExtVectorIcons.MonitorDex,
                        active = false,
                        onClick = { Thread { BridgeClient.relaunchLauncher() }.start() },
                    )
                    ActionPill(
                        label = "${SPEEDS[speedIdx]}x 速度",
                        icon = null,
                        active = false,
                        onClick = { speedIdx = (speedIdx + 1) % SPEEDS.size },
                    )
                    if (isPortrait) {
                        ActionPill(
                            label = if (oneHandPortrait) "单手" else "全屏",
                            icon = ExtVectorIcons.OneHand,
                            active = oneHandPortrait,
                            onClick = { oneHandPortrait = !oneHandPortrait },
                        )
                    }
                    ActionPill(
                        label = "一键黑屏",
                        icon = ExtVectorIcons.MoonDim,
                        active = BridgeService.isBlackoutActive(),
                        onClick = { BridgeService.toggleBlackoutFromUi(this@TouchpadActivity) },
                    )
                    ActionPill(
                        label = "副屏直控",
                        icon = ExtVectorIcons.SidePanel,
                        active = false,
                        onClick = {
                            startActivity(
                                Intent(this@TouchpadActivity, SideControlActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            )
                        },
                    )
                }

                // 5. Collapsible Remote IME & Shortcut Keys Dock
                AnimatedVisibility(visible = imeVisible) {
                    Card(
                        cornerRadius = 18.dp,
                        insideMargin = PaddingValues(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                                val keys = listOf(
                                    Triple("Enter", KeyEvent.KEYCODE_ENTER, 0),
                                    Triple("退格", KeyEvent.KEYCODE_DEL, 0),
                                    Triple("空格", KeyEvent.KEYCODE_SPACE, 0),
                                    Triple("Esc", KeyEvent.KEYCODE_ESCAPE, 0),
                                    Triple("Tab", KeyEvent.KEYCODE_TAB, 0),
                                    Triple("Ctrl+A", KeyEvent.KEYCODE_A, ctrl),
                                    Triple("Ctrl+C", KeyEvent.KEYCODE_C, ctrl),
                                    Triple("Ctrl+V", KeyEvent.KEYCODE_V, ctrl),
                                    Triple("↑", KeyEvent.KEYCODE_DPAD_UP, 0),
                                    Triple("↓", KeyEvent.KEYCODE_DPAD_DOWN, 0),
                                    Triple("←", KeyEvent.KEYCODE_DPAD_LEFT, 0),
                                    Triple("→", KeyEvent.KEYCODE_DPAD_RIGHT, 0),
                                    Triple("Home", KeyEvent.KEYCODE_HOME, 0),
                                )
                                keys.forEach { (lbl, kc, meta) ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(MiuixTheme.colorScheme.secondaryContainer)
                                            .clickable {
                                                Thread { BridgeClient.sendKey(kc, meta) }.start()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(lbl, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextField(
                                    value = imeText,
                                    onValueChange = { imeText = it },
                                    label = "输入中英文直达外接屏…",
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = { commitImeText() }),
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    text = if (useCtrlV) "Ctrl+V" else "PASTE",
                                    onClick = { useCtrlV = !useCtrlV },
                                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                )
                                TextButton(
                                    text = "发送",
                                    onClick = { commitImeText() },
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TrackpadButton(
        text: String,
        active: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        activeColor: Color = HyperBlue,
    ) {
        val bg = if (active) activeColor else MiuixTheme.colorScheme.surfaceContainer
        val fg = if (active) Color.White else MiuixTheme.colorScheme.onSurfaceContainer
        Card(
            modifier = modifier
                .height(46.dp)
                .border(
                    width = 1.dp,
                    color = if (active) activeColor else MiuixTheme.colorScheme.dividerLine,
                    shape = RoundedCornerShape(14.dp),
                ),
            cornerRadius = 14.dp,
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = bg, contentColor = fg),
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            onClick = onClick,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun ActionPill(
        label: String,
        icon: ImageVector?,
        active: Boolean,
        onClick: () -> Unit,
    ) {
        val bg = if (active) HyperBlue else MiuixTheme.colorScheme.surfaceContainer
        val fg = if (active) Color.White else MiuixTheme.colorScheme.onSurfaceContainer
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .border(
                    width = 1.dp,
                    color = if (active) HyperBlue else MiuixTheme.colorScheme.dividerLine,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = fg,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1,
            )
        }
    }

    // --- Ultra-Low-Latency Multi-Touch Trackpad Surface ---

    private inner class NativeTrackpadView(ctx: Context) : View(ctx) {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val railTickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bgDrawable = GradientDrawable()

        private var mode = 0 // 0=idle, 1=1finger, 2=2finger, 3=drag1finger, 4=rightEdgeScroll
        private var downTime = 0L
        private var twoFingerDownTime = 0L
        private var twoFingerScrolled = false
        private var lastTapUpTime = 0L
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var last2X = 0f
        private var last2Y = 0f
        private var totalMove = 0f
        private var scrollAccumV = 0f
        private var scrollAccumH = 0f
        private var touchVisX = -1f
        private var touchVisY = -1f

        private fun dpPx(v: Float): Float = v * resources.displayMetrics.density

        init {
            ringPaint.strokeWidth = dpPx(1.8f)
            railTickPaint.strokeWidth = dpPx(1.2f)
            background = bgDrawable
        }

        fun applyThemeColors(surfaceArgb: Int, strokeArgb: Int, accentArgb: Int, isDark: Boolean) {
            bgDrawable.setColor(surfaceArgb)
            bgDrawable.cornerRadius = dpPx(18f)
            bgDrawable.setStroke(dpPx(1f).toInt().coerceAtLeast(1), strokeArgb)

            val rgb = accentArgb and 0x00FFFFFF
            dotPaint.color = (if (isDark) 0x26000000 else 0x22000000) or rgb
            ringPaint.color = (0xBB000000.toInt()) or rgb
            railPaint.color = (if (isDark) 0x16000000 else 0x12000000) or rgb
            railTickPaint.color = 0x44000000 or rgb
            invalidate()
        }

        fun resetTouchGestureState() {
            mode = 0
            touchVisX = -1f
            touchVisY = -1f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            val railW = dpPx(26f).toInt()
            val step = dpPx(28f).toInt().coerceAtLeast(22)
            var y = step
            while (y < h - step / 2) {
                var x = step
                while (x < w - railW - 8) {
                    canvas.drawCircle(x.toFloat(), y.toFloat(), dpPx(1.15f), dotPaint)
                    x += step
                }
                y += step
            }
            val railLeft = (w - railW).coerceAtLeast(0).toFloat()
            canvas.drawRoundRect(
                railLeft,
                dpPx(12f),
                w - dpPx(4f),
                h - dpPx(12f),
                dpPx(8f),
                dpPx(8f),
                railPaint,
            )
            var ty = dpPx(26f)
            while (ty < h - dpPx(26f)) {
                canvas.drawLine(railLeft + dpPx(6f), ty, w - dpPx(10f), ty, railTickPaint)
                ty += dpPx(15f)
            }
            if (touchVisX >= 0f && touchVisY >= 0f) {
                canvas.drawCircle(touchVisX, touchVisY, dpPx(20f), ringPaint)
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val action = ev.actionMasked
            val cnt = ev.pointerCount
            val now = ev.eventTime
            val density = resources.displayMetrics.density.coerceAtLeast(1f)
            val tapSlop = dpPx(8f)
            val twoFingerTapSlop = dpPx(20f)
            val scrollStepV = dpPx(16f)
            val scrollStepH = dpPx(20f)
            val accelCap = dpPx(18f)
            val distFloor = dpPx(0.2f)
            val railW = dpPx(26f)

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downTime = now
                    downX = ev.x
                    downY = ev.y
                    lastX = downX
                    lastY = downY
                    totalMove = 0f
                    scrollAccumV = 0f
                    scrollAccumH = 0f
                    touchVisX = downX
                    touchVisY = downY
                    mode = when {
                        !dragLocked && (now - lastTapUpTime) < 240L -> {
                            sendMouseSync(3, 0f, 0f)
                            3
                        }
                        downX >= width - railW && width > railW * 3f -> 4
                        else -> 1
                    }
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (cnt == 2) {
                        if (mode == 3) sendMouseSync(4, 0f, 0f)
                        mode = 2
                        twoFingerDownTime = now
                        twoFingerScrolled = false
                        last2X = (ev.getX(0) + ev.getX(1)) * 0.5f
                        last2Y = (ev.getY(0) + ev.getY(1)) * 0.5f
                        scrollAccumV = 0f
                        scrollAccumH = 0f
                        totalMove = 0f
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    touchVisX = ev.getX(0)
                    touchVisY = ev.getY(0)
                    invalidate()
                    if (mode == 1 || mode == 3) {
                        val dx = ev.getX(0) - lastX
                        val dy = ev.getY(0) - lastY
                        lastX = ev.getX(0)
                        lastY = ev.getY(0)
                        val dist = hypot(dx, dy)
                        totalMove += dist
                        if (dist > distFloor) {
                            val padW = (width - railW).coerceAtLeast(320f)
                            val portraitGain = ((virtW / padW) * 0.65f).coerceIn(1.0f, 2.1f)
                            val dpiNorm = 2.0f / density
                            val accel = SPEEDS[speedIdx] * dpiNorm * portraitGain *
                                (1.0f + dist.coerceAtMost(accelCap) * (0.045f / density))
                            sendMouseSync(0, dx * accel, dy * accel)
                        }
                    } else if (mode == 4) {
                        val dx = ev.getX(0) - lastX
                        val dy = ev.getY(0) - lastY
                        lastX = ev.getX(0)
                        lastY = ev.getY(0)
                        totalMove += hypot(dx, dy)
                        scrollAccumV += dy / scrollStepV
                        if (abs(scrollAccumV) >= 0.25f) {
                            sendMouseSync(5, 0f, scrollAccumV)
                            scrollAccumV = 0f
                        }
                    } else if (mode == 2 && cnt >= 2) {
                        val cx = (ev.getX(0) + ev.getX(1)) * 0.5f
                        val cy = (ev.getY(0) + ev.getY(1)) * 0.5f
                        val dx = cx - last2X
                        val dy = cy - last2Y
                        last2X = cx
                        last2Y = cy
                        totalMove += hypot(dx, dy)
                        scrollAccumV += dy / scrollStepV
                        scrollAccumH += dx / scrollStepH
                        if (abs(scrollAccumV) >= 0.30f || abs(scrollAccumH) >= 0.30f) {
                            twoFingerScrolled = true
                            sendMouseSync(5, -scrollAccumH, scrollAccumV)
                            scrollAccumV = 0f
                            scrollAccumH = 0f
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (mode == 2 && !twoFingerScrolled && (now - twoFingerDownTime) < 360L && totalMove < twoFingerTapSlop) {
                        performRightClickOrBack()
                        mode = 0
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    touchVisX = -1f
                    touchVisY = -1f
                    invalidate()
                    if (mode == 3) {
                        sendMouseSync(4, 0f, 0f)
                    } else if ((mode == 1 || mode == 4) && (now - downTime) < 270L && totalMove < tapSlop) {
                        if (dragLocked) {
                            dragLocked = false
                            sendMouseSync(4, 0f, 0f)
                        } else {
                            sendMouseSync(1, 0f, 0f)
                            lastTapUpTime = now
                        }
                    }
                    mode = 0
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    touchVisX = -1f
                    touchVisY = -1f
                    if (mode == 3) sendMouseSync(4, 0f, 0f)
                    mode = 0
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
