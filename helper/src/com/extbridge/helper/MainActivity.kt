package com.extbridge.helper

import android.app.ActivityOptions
import android.app.NotificationManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.extbridge.helper.ui.ActivationSheet
import com.extbridge.helper.ui.CustomMirrorColorDialog
import com.extbridge.helper.ui.CustomResolutionDialog
import com.extbridge.helper.ui.DevOptionsGuideDialog
import com.extbridge.helper.ui.DiagnosticsSheet
import com.extbridge.helper.ui.EdidAndRefreshSheet
import com.extbridge.helper.ui.ExtHyperTheme
import com.extbridge.helper.ui.ExtThemePrefs
import com.extbridge.helper.ui.ExtVectorIcons
import com.extbridge.helper.ui.HyperAmber
import com.extbridge.helper.ui.HyperBentoTile
import com.extbridge.helper.ui.HyperBlue
import com.extbridge.helper.ui.HyperGreen
import com.extbridge.helper.ui.HyperPurple
import com.extbridge.helper.ui.HyperRed
import com.extbridge.helper.ui.MirrorLayoutSelectorGrid
import com.extbridge.helper.ui.MirrorMemoDialog
import com.extbridge.helper.ui.MirrorPresetManagerSheet
import com.extbridge.helper.ui.MirrorWidgetSlotSheet
import com.extbridge.helper.ui.PermissionChecklistDialog
import com.extbridge.helper.ui.StatusPillBadge
import com.extbridge.helper.ui.WidgetCandidateItem
import com.extbridge.helper.ui.isEffectiveDark
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private val RES_PRESETS = arrayOf(
            intArrayOf(3840, 2160),
            intArrayOf(3440, 1440),
            intArrayOf(2560, 1600),
            intArrayOf(2560, 1440),
            intArrayOf(2560, 1080),
            intArrayOf(1920, 1200),
            intArrayOf(1920, 1080),
            intArrayOf(1600, 900),
            intArrayOf(1280, 720),
        )
        private val RES_DROPDOWN_LABELS = listOf(
            "3840×2160 · 4K 超采",
            "3440×1440 · 21:9 准4K",
            "2560×1600 · 2.5K 16:10",
            "2560×1440 · 2K 原生推荐",
            "2560×1080 · 21:9 带鱼屏",
            "1920×1200 · 16:10 宽屏",
            "1920×1080 · 1080p 标准",
            "1600×900 · 900p 轻量",
            "1280×720 · 720p 省流稳帧",
            "自定义宽高…",
        )
        private val ROT_LABELS = listOf(
            "0° 正常横屏",
            "90° 顺时针竖屏",
            "180° 倒置横屏",
            "270° 逆时针竖屏",
        )
        private val DPI_QUICK_PRESETS = listOf(120, 160, 200, 240, 280)

        private const val PREF_ONBOARDING = "extbridge_onboarding"
        private const val KEY_STARTUP_PERMS_DONE = "startup_perms_done"
        private const val KEY_NOTIF_ASKED = "notif_asked"
        private const val KEY_DEV_GUIDE_DONE = "dev_guide_done"

        private const val REQ_NOTIF_STARTUP = 3901
        private const val REQ_NOTIF_DEV_GATE = 3902
        private const val REQ_BIND_APPWIDGET = 4101
        private const val REQ_CONF_APPWIDGET = 4102

        private const val PERM_MODE_NONE = 0
        private const val PERM_MODE_STARTUP = 1
        private const val PERM_MODE_DEV_GATE = 2
    }

    data class AppRuleEntry(
        val pkg: String,
        val label: String,
        val icon: ImageBitmap?,
        val mode: Int,
    )

    private val handler = Handler(Looper.getMainLooper())

    // Reactive Compose state
    private var themeModeIdx by mutableIntStateOf(0)
    private var navTab by mutableIntStateOf(0)
    private var displaySubTab by mutableIntStateOf(0) // 0=DeX, 1=Mirror

    private var daemonStatus by mutableStateOf(BridgeClient.Status())
    private var screenDimmed by mutableStateOf(false)
    private var dockAutoHide by mutableStateOf(false)

    // DeX canvas editing state
    private var selW by mutableIntStateOf(2560)
    private var selH by mutableIntStateOf(1440)
    private var selDpi by mutableIntStateOf(240)
    private var selFps by mutableFloatStateOf(60f)
    private var selRot by mutableIntStateOf(0)
    private var selPhysModeId by mutableIntStateOf(-1)
    private var physNotice by mutableStateOf("")
    private var initedFromDaemon = false
    private var suppressDaemonSyncUntilMs = 0L
    private var lastPhysW = 0
    private var lastPhysH = 0
    private var lastPhysFps = 60f

    // Mirror sidebar state
    private var mirrorSideMode by mutableIntStateOf(1)
    private val mirrorPresets = mutableStateListOf<String>()
    private var mirrorThemeId by mutableStateOf("night")
    private var mirrorSpeedIdx by mutableIntStateOf(1)
    private var mirrorMemoPreview by mutableStateOf("")
    private var widgetSlot0Label by mutableStateOf<String?>(null)
    private var widgetSlot1Label by mutableStateOf<String?>(null)
    private var widgetSlot0Ratio by mutableIntStateOf(0)
    private var widgetSlot1Ratio by mutableIntStateOf(0)

    // Permission & Onboarding state
    private var notifGranted by mutableStateOf(true)
    private var overlayGranted by mutableStateOf(true)
    private var a11yGranted by mutableStateOf(true)
    private var pendingPermMode = PERM_MODE_NONE
    private var startupPermInProgress = false
    private var waitingSettingsReturn = false
    private var userSkippedActivation = false
    private var autoRootTried = false
    private var autoShizukuTried = false

    // Overlay Sheets & Dialogs visibility
    private var showDiagSheet by mutableStateOf(false)
    private var showActivationSheet by mutableStateOf(false)
    private var activationInitTab by mutableIntStateOf(0)
    private var showPermDialog by mutableStateOf(false)
    private var permDialogIsDevGate by mutableStateOf(false)
    private var showDevGuideDialog by mutableStateOf(false)
    private var showCustomResDialog by mutableStateOf(false)
    private var showEdidSheet by mutableStateOf(false)
    private var showPresetMgrSheet by mutableStateOf(false)
    private var showMemoDialog by mutableStateOf(false)
    private var showCustomColorDialog by mutableStateOf(false)
    private var showWidgetSheet by mutableStateOf(false)
    private var activeWidgetSlot by mutableIntStateOf(0)

    // Activation sheet live state
    private var autoRootEnabled by mutableStateOf(false)
    private var autoShizukuEnabled by mutableStateOf(true)
    private var hasSuHint by mutableStateOf(false)
    private var shizukuStatus by mutableStateOf("")
    private var shizukuStatusColor by mutableStateOf(HyperBlue)
    private var shizukuGranted by mutableStateOf(false)
    private var shizukuPermPendingActivate = false
    private var actWorking by mutableStateOf(false)
    private var actStatusText by mutableStateOf("")
    private var actStatusColor by mutableStateOf(HyperBlue)

    // Widget picker & App rules cache
    private var pendingWidgetSlot = -1
    private var pendingWidgetId = -1
    private val widgetCandidates = mutableStateListOf<WidgetCandidateItem>()
    private var widgetLoading by mutableStateOf(false)

    private val appRuleItems = mutableStateListOf<AppRuleEntry>()
    private var appRulesLoading by mutableStateOf(false)
    private var appRulesLoadedOnce = false

    private val binderReadyCb = Runnable {
        handler.post {
            if (!isFinishing && !isDestroyed) refreshAllState(syncFromDaemon = true)
        }
    }

    private val refreshLoop = object : Runnable {
        override fun run() {
            val connected = BridgeClient.getService() != null
            if (!connected) {
                BridgeClient.requestBinder(this@MainActivity)
            }
            refreshAllState(syncFromDaemon = false)
            handler.postDelayed(this, if (connected) 2000L else 1200L)
        }
    }

    private val autoPromptCheck = Runnable {
        if (isFinishing || startupPermInProgress || showPermDialog) return@Runnable
        if (BridgeClient.getService() != null) return@Runnable
        // Shizuku first: it needs no pairing code and no developer-options round trip.
        if (!autoShizukuTried && AdbBootstrap.isAutoShizukuEnabled(this) && ShizukuBootstrap.hasPermission()) {
            autoShizukuTried = true
            Toast.makeText(this, "正在通过 Shizuku 自动激活 ExtBridge 服务…", Toast.LENGTH_SHORT).show()
            AdbBootstrap.shizukuBootstrapAsync(this, false, object : AdbBootstrap.ProgressListener {
                override fun onStep(message: String?) {}
                override fun onResult(success: Boolean, message: String?) {
                    refreshAllState(syncFromDaemon = true)
                    if (!success && !isFinishing && !userSkippedActivation && !showActivationSheet) {
                        openActivationSheet()
                    }
                }
            })
            return@Runnable
        }
        if (!autoRootTried && AdbBootstrap.isAutoRootEnabled(this)) {
            autoRootTried = true
            Toast.makeText(this, "正在尝试自动 Root 激活 ExtBridge 服务…", Toast.LENGTH_SHORT).show()
            AdbBootstrap.rootBootstrapAsync(this, false, object : AdbBootstrap.ProgressListener {
                override fun onStep(message: String?) {}
                override fun onResult(success: Boolean, message: String?) {
                    refreshAllState(syncFromDaemon = true)
                    if (!success && !isFinishing && !userSkippedActivation && !showActivationSheet) {
                        openActivationSheet()
                    }
                }
            })
            return@Runnable
        }
        if (!userSkippedActivation && !showActivationSheet) {
            openActivationSheet()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ensureOnBuiltinDisplay()) return
        enableEdgeToEdge()

        themeModeIdx = ExtThemePrefs.getThemeModeIndex(this)
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)

        syncLocalPrefsToState()
        refreshAllState(syncFromDaemon = true)

        if (!onboardingPrefs().getBoolean(KEY_STARTUP_PERMS_DONE, false) && !hasAllCorePerms()) {
            startupPermInProgress = true
            handler.postDelayed({ startFirstLaunchPermissions() }, 220L)
        }

        val verName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "3.5.3"
        }.getOrDefault("3.5.3")

        setContent {
            ExtHyperTheme(themeModeIndex = themeModeIdx) {
                val dark = isEffectiveDark(themeModeIdx)
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                MainAppShell(versionName = verName)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BridgeClient.setOnBinderReady(binderReadyCb)
        if (BridgeClient.getService() == null) BridgeClient.requestBinder(this)
        BridgeService.sUserStopping = false
        BridgeService.ensureStarted(this)
        syncLocalPrefsToState()
        refreshShizukuState()

        if (waitingSettingsReturn) {
            waitingSettingsReturn = false
            BridgeService.refreshFromReceiver(this)
            refreshAllState(syncFromDaemon = false)
            if (pendingPermMode == PERM_MODE_STARTUP) {
                finishOrPromptStartupPerms()
            } else if (pendingPermMode == PERM_MODE_DEV_GATE) {
                if (hasAllCorePerms()) {
                    completeDevGateAndLaunch()
                } else {
                    openPermDialog(isDevGate = true)
                }
            }
        } else {
            refreshAllState(syncFromDaemon = false)
        }

        handler.removeCallbacks(refreshLoop)
        handler.post(refreshLoop)
        handler.removeCallbacks(autoPromptCheck)
        if (!startupPermInProgress) {
            handler.postDelayed(autoPromptCheck, 450L)
        }
    }

    override fun onPause() {
        super.onPause()
        BridgeClient.removeOnBinderReady(binderReadyCb)
        handler.removeCallbacks(refreshLoop)
        handler.removeCallbacks(autoPromptCheck)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Foldables expose the cover panel as an extra built-in display (Galaxy Z Flip: inner = 0,
     * cover = 1). The console must stay on whatever built-in panel the user is looking at, so only
     * bounce away from real external/virtual displays - and then land on the *active* built-in panel
     * instead of blindly display 0 (which is the folded-off, invisible panel while the phone is shut).
     */
    private fun ensureOnBuiltinDisplay(): Boolean {
        return try {
            val d = display ?: return false
            if (isBuiltinPanel(d)) return false
            val targetId = activeBuiltinDisplayId()
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(targetId)
            startActivity(i, opts.toBundle())
            finishAndRemoveTask()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Built-in panels are always the lowest display ids (0 = inner main screen, 1 = foldable cover
     * panel); external HDMI/Cast displays and VirtualDisplays are allocated much higher ids.
     * Deliberately avoids Display#getType(): it is a hidden API and reflection fails in the app
     * process, which previously made the cover screen look like an "external" display.
     */
    private fun isBuiltinPanel(d: Display): Boolean = d.displayId in 0..5

    private fun isPanelAwake(d: Display): Boolean =
        try {
            d.state == Display.STATE_ON || d.state == Display.STATE_ON_SUSPEND
        } catch (_: Throwable) {
            true
        }

    /** The built-in panel the user is currently looking at: inner screen, or the cover panel when folded. */
    private fun activeBuiltinDisplayId(): Int {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 0
        val d0 = dm.getDisplay(Display.DEFAULT_DISPLAY)
        if (d0 != null && isPanelAwake(d0)) return Display.DEFAULT_DISPLAY
        val awake = dm.displays?.filter { it != null && isPanelAwake(it) } ?: emptyList()
        return awake.filter { it.displayId in 1..5 }
            .minByOrNull { it.displayId }?.displayId ?: Display.DEFAULT_DISPLAY
    }

    private fun syncLocalPrefsToState() {
        dockAutoHide = BridgeService.isDockAutoHideEnabled(this)
        autoRootEnabled = AdbBootstrap.isAutoRootEnabled(this)
        autoShizukuEnabled = AdbBootstrap.isAutoShizukuEnabled(this)
        hasSuHint = AdbBootstrap.hasSuBinaryHint()
        shizukuGranted = ShizukuBootstrap.hasPermission()
        mirrorThemeId = BridgeService.getMirrorThemeId(this)
        mirrorSpeedIdx = BridgeService.getMirrorCursorSpeedIdx(this)
        mirrorMemoPreview = BridgeService.getMirrorMemoText(this)
        val slots = BridgeService.getMirrorPresetSlots(this)
        mirrorPresets.clear()
        mirrorPresets.addAll(slots)
        widgetSlot0Ratio = BridgeService.getMirrorWidgetRatio(this, 0)
        widgetSlot1Ratio = BridgeService.getMirrorWidgetRatio(this, 1)
        widgetSlot0Label = resolveBoundWidgetLabel(0)
        widgetSlot1Label = resolveBoundWidgetLabel(1)
    }

    private fun resolveBoundWidgetLabel(slot: Int): String? {
        val wid = BridgeService.getMirrorWidgetId(this, slot)
        if (wid <= 0) return null
        return runCatching {
            val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(wid) ?: return null
            val lbl = info.loadLabel(packageManager) ?: info.provider.shortClassName
            val rIdx = BridgeService.getMirrorWidgetRatio(this, slot)
            val rSuffix = if (rIdx in 1..BridgeClient.MIRROR_WIDGET_RATIO_LABELS.lastIndex) {
                " (${BridgeClient.MIRROR_WIDGET_RATIO_LABELS[rIdx]})"
            } else ""
            lbl + rSuffix
        }.getOrNull()
    }

    private fun refreshAllState(syncFromDaemon: Boolean) {
        notifGranted = checkNotifPerm()
        overlayGranted = Settings.canDrawOverlays(this)
        a11yGranted = checkA11yPerm()
        dockAutoHide = BridgeService.isDockAutoHideEnabled(this)

        Thread {
            val st = BridgeClient.getStatus()
            val sys = if (st.connected) BridgeClient.getSysStatus() else BridgeClient.SysStatus()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val prevMode = daemonStatus.mirrorMode
                val wasConnected = daemonStatus.connected
                daemonStatus = st
                screenDimmed = (sys.valid && sys.screenDimmed) || BridgeService.isBlackoutActive()

                if (st.connected) {
                    userSkippedActivation = false
                    if (showActivationSheet) {
                        showActivationSheet = false
                        Toast.makeText(this, "ExtBridge 核心服务已连接", Toast.LENGTH_SHORT).show()
                    }
                    val syncAllowed = android.os.SystemClock.uptimeMillis() >= suppressDaemonSyncUntilMs
                    if ((syncFromDaemon && !initedFromDaemon) || (!wasConnected)) {
                        initedFromDaemon = true
                        selW = st.virtW
                        selH = st.virtH
                        selDpi = st.virtDpi
                        selFps = st.virtFps
                        selRot = st.virtRot
                        displaySubTab = if (st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) 1 else 0
                    } else if (syncAllowed && selRot != st.virtRot) {
                        selW = st.virtW
                        selH = st.virtH
                        selRot = st.virtRot
                    }
                    if (prevMode != st.mirrorMode) {
                        displaySubTab = if (st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) 1 else 0
                    }
                    if (syncAllowed) {
                        mirrorSideMode = st.mirrorSideMode
                    }
                    if (st.physW > 0 && st.physH > 0) {
                        lastPhysW = st.physW
                        lastPhysH = st.physH
                        lastPhysFps = if (st.physFps > 10f) st.physFps else 60f
                    }
                }
            }
        }.start()
    }

    // --- UI Composable Hierarchy ---

    @Composable
    private fun MainAppShell(versionName: String) {
        val navItems = remember {
            listOf(
                Triple("控制台", ExtVectorIcons.Dashboard, "ExtBridge"),
                Triple("显示与边栏", ExtVectorIcons.DisplayTuning, "显示与边栏"),
                Triple("应用规则", ExtVectorIcons.AppRules, "分应用显示规则"),
                Triple("设置", ExtVectorIcons.SettingsGear, "设置与服务"),
            )
        }

        LaunchedEffect(navTab, daemonStatus.connected) {
            if (navTab == 2 && daemonStatus.connected && !appRulesLoadedOnce && !appRulesLoading) {
                loadAppRulesAsync()
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWide = maxWidth >= 760.dp
            val scrollBehavior = MiuixScrollBehavior()

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    if (!isWide) {
                        TopAppBar(
                            title = navItems[navTab].third,
                            subtitle = headerSubtitle(versionName),
                            scrollBehavior = scrollBehavior,
                            actions = { TopBarQuickActions() },
                        )
                    }
                },
                bottomBar = {
                    if (!isWide) {
                        NavigationBar {
                            navItems.forEachIndexed { idx, (label, icon, _) ->
                                NavigationBarItem(
                                    selected = (navTab == idx),
                                    onClick = { navTab = idx },
                                    icon = icon,
                                    label = label,
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                if (isWide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail(
                            header = {
                                Box(
                                    modifier = Modifier
                                        .padding(vertical = 8.dp)
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(HyperBlue.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = ExtVectorIcons.MonitorDex,
                                        contentDescription = "ExtBridge",
                                        tint = HyperBlue,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        ) {
                            navItems.forEachIndexed { idx, (label, icon, _) ->
                                NavigationRailItem(
                                    selected = (navTab == idx),
                                    onClick = { navTab = idx },
                                    icon = icon,
                                    label = label,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            SmallTopAppBar(
                                title = navItems[navTab].third,
                                subtitle = headerSubtitle(versionName),
                                actions = { TopBarQuickActions() },
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clipToBounds(),
                            ) {
                                ActiveTabContent(
                                    isWide = true,
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                    ) {
                        ActiveTabContent(
                            isWide = false,
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = innerPadding.calculateTopPadding() + 8.dp,
                                bottom = innerPadding.calculateBottomPadding() + 18.dp,
                            ),
                        )
                    }
                }

                AllOverlays()
            }
        }
    }

    private fun headerSubtitle(versionName: String): String {
        val st = daemonStatus
        return when {
            !st.connected -> "v$versionName · 核心服务未激活"
            st.mirrorMode == 1 && st.extId > 0 -> "v$versionName · 原生镜像 Display #${st.extId}"
            st.vdId > 0 -> "v$versionName · 扩展桌面 ${st.virtW}×${st.virtH}"
            else -> "v$versionName · 服务就绪，待接入显示器"
        }
    }

    @Composable
    private fun TopBarQuickActions() {
        if (!daemonStatus.connected) {
            IconButton(
                onClick = {
                    userSkippedActivation = false
                    openActivationSheet()
                },
            ) {
                Icon(
                    imageVector = ExtVectorIcons.BoltActivate,
                    contentDescription = "激活服务",
                    tint = HyperAmber,
                )
            }
        }
        IconButton(onClick = { showDiagSheet = true }) {
            Icon(
                imageVector = ExtVectorIcons.Diagnostics,
                contentDescription = "连接诊断",
            )
        }
    }

    @Composable
    private fun ActiveTabContent(
        isWide: Boolean,
        contentPadding: PaddingValues,
    ) {
        when (navTab) {
            0 -> DashboardPage(isWide = isWide, contentPadding = contentPadding)
            1 -> DisplayAndSidebarPage(contentPadding = contentPadding)
            2 -> AppRulesPage(contentPadding = contentPadding)
            else -> SettingsPage(contentPadding = contentPadding)
        }
    }

    @Composable
    private fun DashboardPage(
        isWide: Boolean,
        contentPadding: PaddingValues,
    ) {
        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds(),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DashboardPrimaryColumn(showJumpCard = false)
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1.08f)
                        .fillMaxHeight()
                        .clipToBounds(),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (daemonStatus.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) {
                                MirrorConfigSection()
                            } else {
                                DexConfigSection()
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DashboardPrimaryColumn(showJumpCard = true)
                    }
                }
            }
        }
    }

    @Composable
    private fun DashboardPrimaryColumn(showJumpCard: Boolean) {
        val st = daemonStatus
        val allPermsOk = notifGranted && overlayGranted && a11yGranted

        if (!allPermsOk) {
            Card(
                colors = CardDefaults.defaultColors(color = HyperAmber.copy(alpha = 0.14f)),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
                pressFeedbackType = PressFeedbackType.Sink,
                showIndication = true,
                onClick = { openPermDialog(isDevGate = false) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ExtVectorIcons.ShieldCheck,
                        contentDescription = null,
                        tint = HyperAmber,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "部分核心权限尚未开启",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = "点击一键核查无障碍、悬浮窗与通知权限",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    StatusPillBadge("去处理", HyperAmber)
                }
            }
        }

        // Hero Connection Card
        val (badgeLabel, badgeColor, headline, summary) = when {
            !st.connected -> QuadHero(
                "未激活",
                HyperRed,
                "守护进程尚未运行",
                "支持 Shizuku / Root 一键拉起或电脑单行 ADB 命令启动。",
            )
            st.mirrorMode == 1 && st.extId > 0 -> QuadHero(
                "镜像投屏",
                HyperPurple,
                "原生镜像 · Display #${st.extId}",
                "外接物理输出 ${st.physW}×${st.physH} @ ${st.physFps.roundToInt()}Hz · ${BridgeClient.mirrorSideModeLabel(st.mirrorSideMode)}",
            )
            st.vdId > 0 -> QuadHero(
                "扩展在线",
                HyperGreen,
                "DeX 扩展桌面 · Display #${st.vdId}",
                "渲染 ${st.virtW}×${st.virtH} (${BridgeClient.rotationLabel(st.virtRot)}) @ ${st.virtDpi}dpi · 物理 ${st.physW}×${st.physH}@${st.physFps.roundToInt()}Hz",
            )
            else -> QuadHero(
                "待机就绪",
                HyperBlue,
                "核心服务在线 · 待插入显示器",
                "已存预设 ${st.virtW}×${st.virtH} @ ${st.virtDpi}dpi · 接入 Type-C 外接屏自动点亮",
            )
        }

        Card(
            cornerRadius = 20.dp,
            insideMargin = PaddingValues(18.dp),
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            onClick = {
                if (!st.connected) {
                    userSkippedActivation = false
                    openActivationSheet()
                } else {
                    showDiagSheet = true
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = headline,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                StatusPillBadge(label = badgeLabel, dotColor = badgeColor)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            if (st.connected && st.injectStatus == -1) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HyperRed.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "点击注入被拦截：请开启开发者选项「USB 调试（安全设置）」",
                        fontSize = 12.sp,
                        color = HyperRed,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (!st.connected) {
                TextButton(
                    text = "立即激活后台服务",
                    onClick = {
                        userSkippedActivation = false
                        openActivationSheet()
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "连接诊断",
                        onClick = { showDiagSheet = true },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "刷新渲染",
                        onClick = {
                            Thread {
                                BridgeClient.rebuild()
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "已重建扩展渲染管线", Toast.LENGTH_SHORT).show()
                                    refreshAllState(false)
                                }
                            }.start()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Output Mode Switcher
        SmallTitle("输出模式", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
        TabRowWithContour(
            tabs = listOf("DeX 扩展桌面", "原生镜像投屏"),
            selectedTabIndex = if (st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) 1 else 0,
            onTabSelected = { idx ->
                val targetMode = if (idx == 1) BridgeClient.MIRROR_MODE_MIRROR else BridgeClient.MIRROR_MODE_DEX
                displaySubTab = idx
                switchOutputMode(targetMode)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 2x2 HyperOS Quick Action Bento Tiles
        SmallTitle("快捷控制", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val routeExt = st.connected && st.inputToExt == 1
            HyperBentoTile(
                title = "键鼠路由",
                subtitle = if (!st.connected) "连接后切换" else if (routeExt) "当前：外接扩展屏" else "当前：本机内屏",
                icon = ExtVectorIcons.RouteSwap,
                active = routeExt,
                onClick = {
                    Thread {
                        val next = BridgeClient.toggleInput()
                        runOnUiThread {
                            if (next >= 0) {
                                Toast.makeText(
                                    this@MainActivity,
                                    if (next == 1) "键鼠已切至：扩展屏" else "键鼠已切至：本机内屏",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            BridgeService.refreshFromReceiver(this@MainActivity)
                            refreshAllState(false)
                        }
                    }.start()
                },
                modifier = Modifier.weight(1f),
            )

            HyperBentoTile(
                title = "大触摸板",
                subtitle = "多指手势与键盘",
                icon = ExtVectorIcons.Touchpad,
                active = false,
                onClick = { launchOnDisplay0(TouchpadActivity::class.java) },
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HyperBentoTile(
                title = if (screenDimmed) "恢复主屏亮屏" else "一键黑屏",
                subtitle = if (screenDimmed) "双击主屏亦可唤醒" else "主屏压至最低亮度",
                icon = ExtVectorIcons.MoonDim,
                active = screenDimmed,
                onClick = {
                    BridgeService.toggleBlackoutFromUi(this@MainActivity)
                    handler.postDelayed({ refreshAllState(false) }, 160L)
                },
                modifier = Modifier.weight(1f),
            )

            if (st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) {
                HyperBentoTile(
                    title = "副屏直控",
                    subtitle = "触控操作镜像边栏",
                    icon = ExtVectorIcons.SidePanel,
                    active = false,
                    activeColor = HyperPurple,
                    onClick = { launchOnDisplay0(SideControlActivity::class.java) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                HyperBentoTile(
                    title = "打开扩展桌面",
                    subtitle = "唤起外接屏启动器",
                    icon = ExtVectorIcons.MonitorDex,
                    active = false,
                    onClick = {
                        Thread { BridgeClient.relaunchLauncher() }.start()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showJumpCard) {
            val isMirror = (st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR)
            Card {
                ArrowPreference(
                    title = if (isMirror) "配置镜像布局与融合边栏" else "调节分辨率、DPI 与旋转方向",
                    summary = if (isMirror) {
                        "${BridgeClient.mirrorSideModeLabel(mirrorSideMode)} · 已启用 ${mirrorPresets.size} 张信息卡"
                    } else {
                        "画布 ${selW}×${selH} · ${selDpi}dpi · ${BridgeClient.rotationLabel(selRot)}"
                    },
                    onClick = {
                        displaySubTab = if (isMirror) 1 else 0
                        navTab = 1
                    },
                )
            }
        }
    }

    private data class QuadHero(
        val badge: String,
        val color: Color,
        val title: String,
        val summary: String,
    )

    @Composable
    private fun DisplayAndSidebarPage(contentPadding: PaddingValues) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TabRowWithContour(
                        tabs = listOf("DeX 扩展参数", "原生镜像与边栏"),
                        selectedTabIndex = displaySubTab,
                        onTabSelected = { displaySubTab = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (displaySubTab == 0) {
                        DexConfigSection()
                    } else {
                        MirrorConfigSection()
                    }
                }
            }
        }
    }

    @Composable
    private fun DexConfigSection() {
        val presetIdx = remember(selW, selH) {
            val idx = RES_PRESETS.indexOfFirst { (pw, ph) ->
                (pw == selW && ph == selH) || (pw == selH && ph == selW)
            }
            if (idx >= 0) idx else RES_DROPDOWN_LABELS.lastIndex
        }
        val minPx = minOf(selW, selH)
        val swDp = (minPx * 160f / selDpi.coerceAtLeast(80)).roundToInt()
        val isTabletSw = swDp >= 600

        SmallTitle("桌面渲染画布与缩放", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
        Card {
            OverlayDropdownPreference(
                title = "渲染分辨率预设",
                summary = "当前 ${selW} × ${selH}（4K 可超采锐化，720p/900p 降载稳帧）",
                items = RES_DROPDOWN_LABELS,
                selectedIndex = presetIdx,
                onSelectedIndexChange = { idx ->
                    if (idx in RES_PRESETS.indices) {
                        suppressDaemonSyncUntilMs = android.os.SystemClock.uptimeMillis() + 2500L
                        val (pw, ph) = RES_PRESETS[idx]
                        val portrait = (selRot and 1) == 1
                        selW = if (portrait) ph else pw
                        selH = if (portrait) pw else ph
                        selPhysModeId = -1
                    } else {
                        showCustomResDialog = true
                    }
                },
            )
            SectionLine()
            ArrowPreference(
                title = "自定义宽高与横竖对调",
                summary = "${selW} × ${selH} px",
                onClick = { showCustomResDialog = true },
            )
            SectionLine()
            OverlayDropdownPreference(
                title = "输出桌面旋转方向",
                summary = "竖置显示器选 90° 或 270°",
                items = ROT_LABELS,
                selectedIndex = selRot.coerceIn(0, 3),
                onSelectedIndexChange = { nextRot ->
                    suppressDaemonSyncUntilMs = android.os.SystemClock.uptimeMillis() + 2500L
                    selRot = nextRot
                    val wantPortrait = (nextRot and 1) == 1
                    if ((wantPortrait && selW > selH) || (!wantPortrait && selH > selW)) {
                        val tmp = selW
                        selW = selH
                        selH = tmp
                    }
                    if (BridgeClient.getService() != null) {
                        applyChosenDexConfig()
                    }
                },
            )
            SectionLine()
            SliderPreference(
                title = "界面缩放密度 (DPI)",
                summary = "等效最小宽度 sw${swDp}dp · ${if (isTabletSw) "平板大屏布局 (sw≥600dp)" else "紧凑布局 (建议降低 DPI 触发大屏 UI)"}",
                value = selDpi.coerceIn(100, 320).toFloat(),
                valueRange = 100f..320f,
                valueText = "${selDpi} dpi",
                showKeyPoints = true,
                keyPoints = listOf(120f, 160f, 200f, 240f, 280f),
                onValueChange = { v ->
                    selDpi = ((v / 4f).roundToInt() * 4).coerceIn(100, 320)
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DPI_QUICK_PRESETS.forEach { d ->
                    val active = (selDpi == d)
                    TextButton(
                        text = "$d",
                        onClick = { selDpi = d },
                        colors = if (active) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (physNotice.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(HyperAmber.copy(alpha = 0.14f))
                        .padding(12.dp),
                ) {
                    Text(physNotice, fontSize = 12.sp, color = HyperAmber)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = "应用显示参数",
                    onClick = { applyChosenDexConfig() },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1.25f),
                )
                TextButton(
                    text = "恢复推荐默认",
                    onClick = { applyMonitorRecommendedDefault() },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SmallTitle("快捷预设与硬件信号", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
        Card {
            ArrowPreference(
                title = "手机外接推荐：1080p 横屏平板预设",
                summary = "一键设为 1920×1080 @ 240dpi (sw540~600dp 轻量稳帧)",
                onClick = {
                    selW = 1920
                    selH = 1080
                    selDpi = 240
                    selFps = 60f
                    selRot = 0
                    selPhysModeId = -1
                    applyChosenDexConfig()
                },
            )
            SectionLine()
            ArrowPreference(
                title = "外接物理信号与目标刷新率",
                summary = "目标 ${selFps.toInt()} Hz · 查看显示器 EDID 硬件档位",
                onClick = { showEdidSheet = true },
            )
        }
    }

    @Composable
    private fun MirrorConfigSection() {
        SmallTitle("画面对齐与融合边栏布局", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
        MirrorLayoutSelectorGrid(
            selectedMode = mirrorSideMode,
            onSelectMode = { mode ->
                suppressDaemonSyncUntilMs = android.os.SystemClock.uptimeMillis() + 1800L
                mirrorSideMode = mode
                Thread {
                    val applied = BridgeClient.setMirrorSideMode(mode)
                    runOnUiThread {
                        val finalMode = if (applied >= 0) applied else mode
                        mirrorSideMode = finalMode
                        Toast.makeText(
                            this@MainActivity,
                            "镜像布局：${BridgeClient.mirrorSideModeLabel(finalMode)}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        BridgeService.refreshFromReceiver(this@MainActivity)
                        refreshAllState(false)
                    }
                }.start()
            },
        )

        SmallTitle("边栏小组件与信息卡片", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
        Card {
            ArrowPreference(
                title = "打开副屏直控面板",
                summary = "在手机/平板屏幕上实时触控操作外接边栏与小组件",
                onClick = { launchOnDisplay0(SideControlActivity::class.java) },
            )
            SectionLine()
            ArrowPreference(
                title = "边栏系统小组件槽位 1",
                summary = widgetSlot0Label ?: "未绑定 · 点击嵌入系统时钟/天气/音乐小组件",
                onClick = { openWidgetSlotSheet(0) },
            )
            SectionLine()
            ArrowPreference(
                title = "边栏系统小组件槽位 2",
                summary = widgetSlot1Label ?: "未绑定 · 点击嵌入第二个系统小组件",
                onClick = { openWidgetSlotSheet(1) },
            )
            SectionLine()
            ArrowPreference(
                title = "边栏信息卡片管理",
                summary = "已启用 ${mirrorPresets.size} 张（${mirrorPresets.take(3).joinToString("、") { BridgeClient.mirrorPresetTitle(it) }}…）",
                onClick = { showPresetMgrSheet = true },
            )
            SectionLine()
            ArrowPreference(
                title = "编辑快捷桌面便签",
                summary = mirrorMemoPreview.ifBlank { "点击填写外接屏边栏待办或备忘" },
                onClick = { showMemoDialog = true },
            )
        }

        SmallTitle("副屏主题与鼠标手感", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
        Card {
            val themeIdx = BridgeClient.MIRROR_THEME_KEYS.indexOf(mirrorThemeId).coerceAtLeast(0)
            OverlayDropdownPreference(
                title = "副屏背景与文字主题",
                summary = "即时切换融合边栏配色风格",
                items = BridgeClient.MIRROR_THEME_TITLES.toList(),
                selectedIndex = themeIdx,
                onSelectedIndexChange = { idx ->
                    val key = BridgeClient.MIRROR_THEME_KEYS[idx]
                    if (key == "custom") {
                        showCustomColorDialog = true
                    } else {
                        BridgeService.setMirrorThemeId(this@MainActivity, key)
                        mirrorThemeId = key
                    }
                },
            )
            SectionLine()
            OverlayDropdownPreference(
                title = "副屏跨越鼠标灵敏度",
                summary = "调节外接鼠标滑入融合边栏时的移动速度",
                items = BridgeClient.MIRROR_SPEED_LABELS.toList(),
                selectedIndex = mirrorSpeedIdx.coerceIn(0, BridgeClient.MIRROR_SPEED_LABELS.lastIndex),
                onSelectedIndexChange = { idx ->
                    BridgeService.setMirrorCursorSpeedIdx(this@MainActivity, idx)
                    mirrorSpeedIdx = idx
                },
            )
        }
    }

    @Composable
    private fun AppRulesPage(contentPadding: PaddingValues) {
        var searchQuery by remember { mutableStateOf("") }
        val modeOptions = remember {
            listOf(
                "跟随系统默认",
                "强制横屏全屏",
                "竖屏居中兼容",
            )
        }

        if (!daemonStatus.connected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(insideMargin = PaddingValues(18.dp)) {
                    Text(
                        text = "需先激活 ExtBridge 核心服务",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "分应用横屏/竖屏兼容规则由守护进程实时维护并在扩展屏启动时生效。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(14.dp))
                    TextButton(
                        text = "立即激活服务",
                        onClick = {
                            userSkippedActivation = false
                            openActivationSheet()
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            return
        }

        val customCount = appRuleItems.count { it.mode > 0 }
        val filtered = remember(appRuleItems.toList(), searchQuery) {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) appRuleItems.toList()
            else appRuleItems.filter {
                it.label.lowercase(Locale.ROOT).contains(q) ||
                    it.pkg.lowercase(Locale.ROOT).contains(q)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = "搜索应用名称或包名（如：微信 / 夸克 / 红果）",
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = ExtVectorIcons.SearchLens,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 12.dp).size(18.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (appRulesLoading) "正在读取已安装应用…" else "共 ${appRuleItems.size} 款应用 · 已自定义 $customCount 款",
                        fontSize = 12.5.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (customCount > 0) {
                        TextButton(
                            text = "全部重置默认",
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            onClick = {
                                Thread {
                                    BridgeClient.resetAllAppModes()
                                    runOnUiThread {
                                        for (i in appRuleItems.indices) {
                                            val old = appRuleItems[i]
                                            if (old.mode != 0) appRuleItems[i] = old.copy(mode = 0)
                                        }
                                        BridgeService.refreshFromReceiver(this@MainActivity)
                                        Toast.makeText(this@MainActivity, "已恢复全部应用为系统默认", Toast.LENGTH_SHORT).show()
                                    }
                                }.start()
                            },
                        )
                    }
                }
            }

            items(filtered, key = { it.pkg }) { item ->
                Card {
                    OverlayDropdownPreference(
                        title = item.label,
                        summary = item.pkg,
                        items = modeOptions,
                        selectedIndex = item.mode.coerceIn(0, 2),
                        startAction = if (item.icon != null) {
                            {
                                Image(
                                    bitmap = item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                            }
                        } else null,
                        onSelectedIndexChange = { nextMode ->
                            if (nextMode != item.mode) {
                                val idx = appRuleItems.indexOfFirst { it.pkg == item.pkg }
                                if (idx >= 0) {
                                    appRuleItems[idx] = appRuleItems[idx].copy(mode = nextMode)
                                }
                                Thread {
                                    BridgeClient.setAppMode(item.pkg, nextMode, true)
                                    runOnUiThread {
                                        BridgeService.refreshFromReceiver(this@MainActivity)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "${item.label}：${BridgeClient.appModeLabel(nextMode)}",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }.start()
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun SettingsPage(contentPadding: PaddingValues) {
        val grantedCount = listOf(notifGranted, overlayGranted, a11yGranted).count { it }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallTitle("界面外观", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
            Card {
                OverlayDropdownPreference(
                    title = "主题外观模式",
                    summary = "切换 Xiaomi HyperOS 浅色或深色视觉风格",
                    items = ExtThemePrefs.THEME_LABELS,
                    selectedIndex = themeModeIdx,
                    onSelectedIndexChange = { idx ->
                        themeModeIdx = idx
                        ExtThemePrefs.setThemeModeIndex(this@MainActivity, idx)
                    },
                )
            }

            SmallTitle("扩展桌面行为", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
            Card {
                SwitchPreference(
                    title = "Dock 栏自动隐藏",
                    summary = "进入应用后闲置自动收起底部 Dock，鼠标移至底边唤醒",
                    checked = dockAutoHide,
                    onCheckedChange = { next ->
                        dockAutoHide = next
                        BridgeService.setDockAutoHideEnabled(this@MainActivity, next)
                    },
                )
                SectionLine()
                SwitchPreference(
                    title = "DeX 默认横屏大屏策略",
                    summary = "扩展桌面默认以平板横屏满幅窗口启动应用",
                    checked = daemonStatus.fullscreenPolicy == 1,
                    enabled = daemonStatus.connected,
                    onCheckedChange = { want ->
                        Thread {
                            BridgeClient.setFullscreenPolicy(if (want) 1 else 0)
                            runOnUiThread {
                                BridgeService.refreshFromReceiver(this@MainActivity)
                                refreshAllState(false)
                            }
                        }.start()
                    },
                )
            }

            SmallTitle("守护进程与权限健康", insideMargin = PaddingValues(horizontal = 6.dp, vertical = 2.dp))
            Card {
                ArrowPreference(
                    title = "激活 / 重连核心守护进程",
                    summary = if (daemonStatus.connected) "当前在线 · 点击查看 Shizuku / Root / ADB 引导" else "当前未连接 · 点击立即激活",
                    onClick = {
                        userSkippedActivation = false
                        openActivationSheet()
                    },
                )
                SectionLine()
                SwitchPreference(
                    title = "启动时自动尝试 Root 激活",
                    summary = "已授 Root 权限的设备打开软件时静默拉起守护进程",
                    checked = autoRootEnabled,
                    onCheckedChange = { next ->
                        autoRootEnabled = next
                        AdbBootstrap.setAutoRootEnabled(this@MainActivity, next)
                    },
                )
                SectionLine()
                SwitchPreference(
                    title = "启动时自动 Shizuku 激活",
                    summary = "已授权 Shizuku 时，打开软件自动拉起守护进程（无需配对码）",
                    checked = autoShizukuEnabled,
                    onCheckedChange = { next ->
                        autoShizukuEnabled = next
                        AdbBootstrap.setAutoShizukuEnabled(this@MainActivity, next)
                    },
                )
                SectionLine()
                ArrowPreference(
                    title = "运行权限健康检查",
                    summary = if (grantedCount == 3) "通知、悬浮窗、无障碍 3/3 已全部就绪" else "已就绪 $grantedCount/3 项 · 点击补齐缺失权限",
                    onClick = { openPermDialog(isDevGate = false) },
                )
                SectionLine()
                ArrowPreference(
                    title = "允许受限设置（解锁灰色无障碍开关）",
                    summary = "前往系统应用详情页右上角菜单开启受限设置",
                    onClick = { openAppDetailsForRestricted(PERM_MODE_NONE) },
                )
                SectionLine()
                ArrowPreference(
                    title = "连接与渲染诊断详情",
                    summary = "查看物理/虚拟 Display ID、BBQ 管线与输入注入状态",
                    onClick = { showDiagSheet = true },
                )
            }

            Spacer(Modifier.height(4.dp))
            TextButton(
                text = "停止后台服务并退出 ExtBridge",
                onClick = {
                    Toast.makeText(this@MainActivity, "正在停止 ExtBridge 守护进程并退出…", Toast.LENGTH_SHORT).show()
                    BridgeService.stopAllAndExit(this@MainActivity)
                },
                colors = ButtonDefaults.textButtonColors(
                    color = HyperRed.copy(alpha = 0.14f),
                    textColor = HyperRed,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
                }
            }
        }
    }

    @Composable
    private fun SectionLine() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(0.6.dp)
                .background(MiuixTheme.colorScheme.dividerLine),
        )
    }

    @Composable
    private fun AllOverlays() {
        DiagnosticsSheet(
            show = showDiagSheet,
            status = daemonStatus,
            onRebuild = {
                Thread { BridgeClient.rebuild() }.start()
                Toast.makeText(this, "已发送渲染重建指令", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showDiagSheet = false },
        )

        ActivationSheet(
            show = showActivationSheet,
            initialTab = activationInitTab,
            hasSuHint = hasSuHint,
            autoRootEnabled = autoRootEnabled,
            onAutoRootChange = { next ->
                autoRootEnabled = next
                AdbBootstrap.setAutoRootEnabled(this, next)
            },
            shizukuStatus = shizukuStatus,
            shizukuStatusColor = shizukuStatusColor,
            shizukuGranted = shizukuGranted,
            autoShizukuEnabled = autoShizukuEnabled,
            onAutoShizukuChange = { next ->
                autoShizukuEnabled = next
                AdbBootstrap.setAutoShizukuEnabled(this, next)
            },
            onRequestShizukuPerm = { requestShizukuPermission(false) },
            onOpenShizukuApp = { ShizukuBootstrap.openManager(this) },
            onRunShizuku = { triggerShizukuActivation() },
            actStatusText = actStatusText,
            actStatusColor = actStatusColor,
            working = actWorking,
            pcAdbCommand = remember { AdbBootstrap.buildPcAdbCommand() },
            onRunRoot = { triggerRootActivation() },
            onOpenDevOptions = { openDeveloperOptionsWithGate() },
            onCopyAdbCmd = {
                val cmd = AdbBootstrap.buildPcAdbCommand()
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("extbridge_adb", cmd))
                Toast.makeText(this, "已复制 ADB 激活命令到剪贴板", Toast.LENGTH_SHORT).show()
            },
            onSkip = {
                userSkippedActivation = true
                showActivationSheet = false
            },
        )

        PermissionChecklistDialog(
            show = showPermDialog,
            isDevGate = permDialogIsDevGate,
            notifOk = notifGranted,
            overlayOk = overlayGranted,
            a11yOk = a11yGranted,
            onGrantNotif = {
                requestNotifOrOpenSettings(if (permDialogIsDevGate) REQ_NOTIF_DEV_GATE else REQ_NOTIF_STARTUP)
            },
            onGrantOverlay = {
                openOverlayPermissionSettings(if (permDialogIsDevGate) PERM_MODE_DEV_GATE else PERM_MODE_STARTUP)
            },
            onGrantA11y = {
                openAccessibilitySettings(if (permDialogIsDevGate) PERM_MODE_DEV_GATE else PERM_MODE_STARTUP)
            },
            onOpenRestricted = {
                openAppDetailsForRestricted(if (permDialogIsDevGate) PERM_MODE_DEV_GATE else PERM_MODE_STARTUP)
            },
            onSkipOrContinue = {
                if (notifGranted && overlayGranted && a11yGranted) {
                    if (permDialogIsDevGate) completeDevGateAndLaunch() else completeStartupPerms(true)
                } else if (permDialogIsDevGate) {
                    pendingPermMode = PERM_MODE_NONE
                    showPermDialog = false
                    showDevGuideOrLaunchNow(forceDirect = true)
                } else {
                    completeStartupPerms(false)
                }
            },
        )

        DevOptionsGuideDialog(
            show = showDevGuideDialog,
            onConfirm = {
                showDevGuideDialog = false
                launchDeveloperOptionsNow()
            },
            onDismiss = { showDevGuideDialog = false },
        )

        CustomResolutionDialog(
            show = showCustomResDialog,
            initialW = selW,
            initialH = selH,
            onApply = { cw, ch ->
                selW = cw
                selH = ch
                selPhysModeId = -1
                if (BridgeClient.getService() != null) {
                    applyChosenDexConfig()
                }
            },
            onDismiss = { showCustomResDialog = false },
        )

        EdidAndRefreshSheet(
            show = showEdidSheet,
            selectedFps = selFps,
            modesRaw = daemonStatus.modesRaw,
            selectedPhysModeId = selPhysModeId,
            onSelectFps = { fps ->
                selFps = fps
                selPhysModeId = -1
                if (BridgeClient.getService() != null) {
                    applyChosenDexConfig()
                }
            },
            onSelectPhysMode = { modeId, pw, ph, pfps ->
                selPhysModeId = modeId
                val portrait = (selRot and 1) == 1
                selW = if (portrait) ph else pw
                selH = if (portrait) pw else ph
                selFps = pfps
                applyChosenDexConfig()
            },
            onDismiss = { showEdidSheet = false },
        )

        MirrorPresetManagerSheet(
            show = showPresetMgrSheet,
            slots = mirrorPresets.toList(),
            onUpdateSlots = { next ->
                BridgeService.setMirrorPresetSlots(this, next)
                mirrorPresets.clear()
                mirrorPresets.addAll(BridgeService.getMirrorPresetSlots(this))
            },
            onEditMemo = { showMemoDialog = true },
            onResetDefault = {
                BridgeService.setMirrorPresetSlots(this, null)
                mirrorPresets.clear()
                mirrorPresets.addAll(BridgeService.getMirrorPresetSlots(this))
                Toast.makeText(this, "已恢复默认 7 张边栏卡片", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showPresetMgrSheet = false },
        )

        MirrorMemoDialog(
            show = showMemoDialog,
            initialText = mirrorMemoPreview,
            onSave = { txt ->
                BridgeService.setMirrorMemoText(this, txt)
                mirrorMemoPreview = BridgeService.getMirrorMemoText(this)
                Toast.makeText(this, "便签内容已同步至外接边栏", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showMemoDialog = false },
        )

        CustomMirrorColorDialog(
            show = showCustomColorDialog,
            initialColorInt = BridgeService.getMirrorCustomBg(this),
            onApplyColor = { colorInt ->
                BridgeService.setMirrorCustomBg(this, colorInt)
                mirrorThemeId = "custom"
                Toast.makeText(this, "已应用自定义副屏颜色", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showCustomColorDialog = false },
        )

        MirrorWidgetSlotSheet(
            show = showWidgetSheet,
            slotIndex = activeWidgetSlot,
            boundWidgetLabel = if (activeWidgetSlot == 0) widgetSlot0Label else widgetSlot1Label,
            currentRatioIdx = if (activeWidgetSlot == 0) widgetSlot0Ratio else widgetSlot1Ratio,
            candidates = widgetCandidates.toList(),
            loading = widgetLoading,
            onChangeRatio = { rIdx ->
                BridgeService.setMirrorWidgetRatio(this, activeWidgetSlot, rIdx)
                syncLocalPrefsToState()
            },
            onClearWidget = {
                BridgeService.setMirrorWidgetId(this, activeWidgetSlot, -1)
                syncLocalPrefsToState()
                Toast.makeText(this, "已清除槽位 ${activeWidgetSlot + 1} 小组件", Toast.LENGTH_SHORT).show()
            },
            onPickCandidate = { info -> bindChosenMirrorWidget(activeWidgetSlot, info) },
            onDismiss = { showWidgetSheet = false },
        )
    }

    // --- Actions & Business Logic ---

    private fun launchOnDisplay0(cls: Class<*>) {
        runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(activeBuiltinDisplayId())
            startActivity(Intent(this, cls), opts.toBundle())
        }.onFailure {
            startActivity(Intent(this, cls))
        }
    }

    private fun switchOutputMode(mode: Int) {
        Thread {
            val applied = BridgeClient.setMirrorMode(mode)
            val m = if (applied >= 0) applied else mode
            runOnUiThread {
                Toast.makeText(this, "已切换为：${BridgeClient.mirrorModeLabel(m)}", Toast.LENGTH_SHORT).show()
                BridgeService.refreshFromReceiver(this)
                refreshAllState(false)
            }
        }.start()
    }

    private fun applyChosenDexConfig() {
        suppressDaemonSyncUntilMs = android.os.SystemClock.uptimeMillis() + 2500L
        val w = selW
        val h = selH
        val dpi = selDpi
        val fps = selFps
        val physId = selPhysModeId
        val rot = selRot
        Thread {
            val res = BridgeClient.applyConfigDetailed(w, h, dpi, fps, physId, rot)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!res.ok) {
                    physNotice = ""
                    Toast.makeText(this, "应用失败：请先激活后台核心服务", Toast.LENGTH_SHORT).show()
                } else if (res.physResult == -1 && res.physW > 0) {
                    physNotice = "画布已切为 ${w}×${h}；显示器不支持 ${fps.roundToInt()}Hz，物理输出保持 ${res.physW}×${res.physH}@${res.physFps.roundToInt()}Hz。"
                    Toast.makeText(this, physNotice, Toast.LENGTH_LONG).show()
                } else if ((res.physResult == -2 || res.physResult == -3) && res.physW > 0) {
                    physNotice = "画布已切为 ${w}×${h}；系统固件锁定物理输出 ${res.physW}×${res.physH}@${res.physFps.roundToInt()}Hz，已自动硬件缩放铺满。"
                    Toast.makeText(this, physNotice, Toast.LENGTH_LONG).show()
                } else {
                    physNotice = ""
                    val physNote = if (res.physW > 0) " · 物理 ${res.physW}×${res.physH}@${res.physFps.roundToInt()}Hz" else ""
                    Toast.makeText(
                        this,
                        "已应用：${w}×${h} · ${BridgeClient.rotationLabel(rot)} · ${dpi}dpi$physNote",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                BridgeService.refreshFromReceiver(this)
                refreshAllState(false)
            }
        }.start()
    }

    private fun applyMonitorRecommendedDefault() {
        var bestModeId = -1
        var bestW = 0
        var bestH = 0
        var bestFps = 60f

        val raw = daemonStatus.modesRaw
        if (raw.isNotBlank()) {
            val items = raw.split(";").mapNotNull { entry ->
                val kv = entry.split(":")
                if (kv.size != 2) return@mapNotNull null
                val id = kv[0].toIntOrNull() ?: return@mapNotNull null
                val rf = kv[1].split("@")
                if (rf.size != 2) return@mapNotNull null
                val wh = rf[0].split("x")
                if (wh.size != 2) return@mapNotNull null
                val pw = wh[0].toIntOrNull() ?: return@mapNotNull null
                val ph = wh[1].toIntOrNull() ?: return@mapNotNull null
                val pf = rf[1].toFloatOrNull() ?: 60f
                if (maxOf(pw, ph) < 640 || minOf(pw, ph) < 480) return@mapNotNull null
                Triple(id, pw to ph, pf)
            }
            val has50Plus = items.any { it.third >= 49.5f }
            var bestArea = -1L
            var bestFpsScore = -1f
            for ((id, wh, pf) in items) {
                if (has50Plus && pf < 49.5f) continue
                val area = wh.first.toLong() * wh.second.toLong()
                val fpsScore = pf.coerceAtMost(120f)
                if (area > bestArea || (area == bestArea && fpsScore > bestFpsScore + 0.2f)) {
                    bestArea = area
                    bestFpsScore = fpsScore
                    bestModeId = id
                    bestW = wh.first
                    bestH = wh.second
                    bestFps = pf
                }
            }
        }

        if (bestW <= 0 || bestH <= 0) {
            if (lastPhysW >= 640 && lastPhysH >= 480) {
                bestW = lastPhysW
                bestH = lastPhysH
                bestFps = lastPhysFps
            } else {
                bestW = 2560
                bestH = 1440
                bestFps = 60f
            }
        }

        val minEdge = minOf(bestW, bestH)
        val recDpi = when {
            minEdge >= 2000 -> 320
            minEdge >= 1360 -> 240
            minEdge >= 1000 -> 200
            else -> 160
        }

        selW = bestW
        selH = bestH
        selDpi = recDpi
        selFps = bestFps
        selRot = 0
        selPhysModeId = bestModeId
        applyChosenDexConfig()
    }

    private fun openActivationSheet() {
        hasSuHint = AdbBootstrap.hasSuBinaryHint()
        autoRootEnabled = AdbBootstrap.isAutoRootEnabled(this)
        autoShizukuEnabled = AdbBootstrap.isAutoShizukuEnabled(this)
        refreshShizukuState()
        activationInitTab = when {
            ShizukuBootstrap.isManagerInstalled(this) -> 0
            hasSuHint || autoRootEnabled -> 1
            else -> 0
        }
        actStatusText = ""
        showActivationSheet = true
    }

    private fun refreshShizukuState() {
        shizukuGranted = ShizukuBootstrap.hasPermission()
        shizukuStatus = ShizukuBootstrap.statusText(this)
        shizukuStatusColor = when {
            shizukuGranted -> HyperGreen
            ShizukuBootstrap.isRunning() -> HyperAmber
            else -> HyperRed
        }
    }

    private fun requestShizukuPermission(activateAfterGrant: Boolean) {
        shizukuPermPendingActivate = activateAfterGrant
        actStatusColor = HyperBlue
        actStatusText = "正在向 Shizuku 申请授权…"
        ShizukuBootstrap.requestPermissionAsync(this) { granted, message ->
            shizukuGranted = granted
            refreshShizukuState()
            actStatusText = message ?: ""
            actStatusColor = if (granted) HyperGreen else HyperRed
            if (granted && shizukuPermPendingActivate) {
                shizukuPermPendingActivate = false
                triggerShizukuActivation()
            }
        }
    }

    private fun triggerShizukuActivation() {
        if (actWorking) return
        if (!ShizukuBootstrap.hasPermission()) {
            // Missing (or revoked) permission: ask first, then continue automatically.
            requestShizukuPermission(true)
            return
        }
        actWorking = true
        actStatusColor = HyperBlue
        actStatusText = "正在通过 Shizuku 激活 ExtBridge 服务…"
        AdbBootstrap.shizukuBootstrapAsync(this, true, object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) {
                actStatusColor = HyperBlue
                actStatusText = message ?: ""
            }

            override fun onResult(success: Boolean, message: String?) {
                actWorking = false
                actStatusColor = if (success) HyperGreen else HyperRed
                actStatusText = message ?: ""
                if (success) {
                    autoShizukuEnabled = true
                }
                refreshShizukuState()
                refreshAllState(syncFromDaemon = true)
            }
        })
    }

    private fun triggerRootActivation() {
        if (actWorking) return
        actWorking = true
        actStatusColor = HyperBlue
        actStatusText = "正在请求 Root 权限并注册启动服务…"
        AdbBootstrap.rootBootstrapAsync(this, true, object : AdbBootstrap.ProgressListener {
            override fun onStep(message: String?) {
                actStatusColor = HyperBlue
                actStatusText = message ?: ""
            }

            override fun onResult(success: Boolean, message: String?) {
                actWorking = false
                actStatusColor = if (success) HyperGreen else HyperRed
                actStatusText = message ?: ""
                if (success) {
                    autoRootEnabled = true
                }
                refreshAllState(syncFromDaemon = true)
            }
        })
    }

    // --- Widget Picker & Activity Result ---

    private fun openWidgetSlotSheet(slot: Int) {
        activeWidgetSlot = slot
        syncLocalPrefsToState()
        showWidgetSheet = true
        if (widgetCandidates.isEmpty() && !widgetLoading) {
            widgetLoading = true
            Thread {
                BridgeClient.ensureAppWidgetPermission()
                val awm = AppWidgetManager.getInstance(this)
                val providers = awm.installedProviders ?: emptyList()
                val pm = packageManager
                val list = providers.map { info ->
                    val title = info.loadLabel(pm) ?: info.provider.shortClassName
                    val appLbl = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                    }.getOrDefault(info.provider.packageName)
                    val ic = runCatching {
                        info.loadIcon(this, DisplayMetrics.DENSITY_HIGH)
                            ?: pm.getApplicationIcon(info.provider.packageName)
                    }.getOrNull()
                    WidgetCandidateItem(
                        info = info,
                        title = title,
                        subtitle = "$appLbl · ${info.minWidth}×${info.minHeight}dp",
                        iconBitmap = drawableToImageBitmap(ic, 96),
                    )
                }.sortedBy { it.title.lowercase(Locale.ROOT) }

                runOnUiThread {
                    widgetCandidates.clear()
                    widgetCandidates.addAll(list)
                    widgetLoading = false
                }
            }.start()
        }
    }

    private fun bindChosenMirrorWidget(slot: Int, info: AppWidgetProviderInfo) {
        val host = AppWidgetHost(applicationContext, BridgeService.MIRROR_WIDGET_HOST_ID)
        val awm = AppWidgetManager.getInstance(this)
        val id = host.allocateAppWidgetId()
        val bound = runCatching { awm.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        if (bound) {
            finalizeOrConfigureWidget(slot, id, info, host)
        } else {
            pendingWidgetSlot = slot
            pendingWidgetId = id
            try {
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_BIND_APPWIDGET)
            } catch (_: Throwable) {
                runCatching { host.deleteAppWidgetId(id) }
                Toast.makeText(this, "系统小组件绑定失败（请先激活守护进程）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun finalizeOrConfigureWidget(slot: Int, widgetId: Int, info: AppWidgetProviderInfo?, host: AppWidgetHost) {
        var needConf = info?.configure != null
        if (needConf && Build.VERSION.SDK_INT >= 28 && info != null) {
            if ((info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0) {
                needConf = false
            }
        }
        if (needConf) {
            pendingWidgetSlot = slot
            pendingWidgetId = widgetId
            try {
                host.startAppWidgetConfigureActivityForResult(this, widgetId, 0, REQ_CONF_APPWIDGET, null)
                return
            } catch (_: Throwable) {}
        }
        BridgeService.setMirrorWidgetId(this, slot, widgetId)
        syncLocalPrefsToState()
        Toast.makeText(this, "已绑定至边栏槽位 ${slot + 1}", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BIND_APPWIDGET && pendingWidgetSlot >= 0) {
            val wid = pendingWidgetId
            val slot = pendingWidgetSlot
            pendingWidgetSlot = -1
            pendingWidgetId = -1
            val host = AppWidgetHost(applicationContext, BridgeService.MIRROR_WIDGET_HOST_ID)
            if (resultCode == RESULT_OK && wid > 0) {
                val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(wid)
                finalizeOrConfigureWidget(slot, wid, info, host)
            } else if (wid > 0) {
                runCatching { host.deleteAppWidgetId(wid) }
            }
        } else if (requestCode == REQ_CONF_APPWIDGET && pendingWidgetSlot >= 0) {
            val wid = pendingWidgetId
            val slot = pendingWidgetSlot
            pendingWidgetSlot = -1
            pendingWidgetId = -1
            if (resultCode == RESULT_OK && wid > 0) {
                BridgeService.setMirrorWidgetId(this, slot, wid)
                syncLocalPrefsToState()
            } else if (wid > 0) {
                runCatching { AppWidgetHost(applicationContext, BridgeService.MIRROR_WIDGET_HOST_ID).deleteAppWidgetId(wid) }
            }
        }
    }

    // --- Per-App Display Rules Loader ---

    private fun loadAppRulesAsync() {
        appRulesLoading = true
        Thread {
            val modes = BridgeClient.getAllAppModes()
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val ris = pm.queryIntentActivities(q, 0)
            val dedup = LinkedHashMap<String, AppRuleEntry>()
            for (ri in ris) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == packageName || dedup.containsKey(pkg)) continue
                val label = ri.loadLabel(pm).toString().ifBlank { pkg }
                val iconBmp = runCatching { drawableToImageBitmap(ri.loadIcon(pm), 88) }.getOrNull()
                val m = modes[pkg] ?: 0
                dedup[pkg] = AppRuleEntry(pkg = pkg, label = label, icon = iconBmp, mode = m)
            }
            val sorted = dedup.values.sortedWith(
                compareBy<AppRuleEntry> { if (it.mode > 0) 0 else 1 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )
            runOnUiThread {
                appRuleItems.clear()
                appRuleItems.addAll(sorted)
                appRulesLoading = false
                appRulesLoadedOnce = true
            }
        }.start()
    }

    private fun drawableToImageBitmap(d: Drawable?, maxPx: Int): ImageBitmap? {
        if (d == null) return null
        return runCatching {
            val w = d.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(maxPx) ?: maxPx
            val h = d.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(maxPx) ?: maxPx
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }

    // --- Permissions & Developer Options Gate ---

    private fun onboardingPrefs(): SharedPreferences =
        getSharedPreferences(PREF_ONBOARDING, MODE_PRIVATE)

    private fun checkNotifPerm(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        return Build.VERSION.SDK_INT < 24 || nm == null || nm.areNotificationsEnabled()
    }

    private fun checkA11yPerm(): Boolean {
        if (NavAssistService.isRunning()) return true
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled != 1) return false
            val svcs = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            svcs.contains("$packageName/.NavAssistService") ||
                svcs.contains("$packageName/${NavAssistService::class.java.name}")
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasAllCorePerms(): Boolean =
        checkNotifPerm() && Settings.canDrawOverlays(this) && checkA11yPerm()

    private fun openPermDialog(isDevGate: Boolean) {
        notifGranted = checkNotifPerm()
        overlayGranted = Settings.canDrawOverlays(this)
        a11yGranted = checkA11yPerm()
        pendingPermMode = if (isDevGate) PERM_MODE_DEV_GATE else PERM_MODE_STARTUP
        permDialogIsDevGate = isDevGate
        showPermDialog = true
    }

    private fun startFirstLaunchPermissions() {
        if (isFinishing) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED &&
            !onboardingPrefs().getBoolean(KEY_NOTIF_ASKED, false)
        ) {
            requestNotifOrOpenSettings(REQ_NOTIF_STARTUP)
            return
        }
        finishOrPromptStartupPerms()
    }

    private fun finishOrPromptStartupPerms() {
        if (isFinishing) return
        if (hasAllCorePerms()) {
            completeStartupPerms(true)
        } else {
            openPermDialog(isDevGate = false)
        }
    }

    private fun completeStartupPerms(allGranted: Boolean) {
        startupPermInProgress = false
        pendingPermMode = PERM_MODE_NONE
        onboardingPrefs().edit().putBoolean(KEY_STARTUP_PERMS_DONE, true).apply()
        showPermDialog = false
        if (allGranted) {
            BridgeService.refreshFromReceiver(this)
            Toast.makeText(this, "所需运行权限已全部就绪", Toast.LENGTH_SHORT).show()
        }
        refreshAllState(false)
        handler.removeCallbacks(autoPromptCheck)
        handler.postDelayed(autoPromptCheck, 200L)
    }

    private fun startSystemSettingsOnBuiltinDisplay(intent: Intent) {
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(activeBuiltinDisplayId())
        startActivity(intent, opts.toBundle())
    }

    private fun requestNotifOrOpenSettings(reqCode: Int) {
        pendingPermMode = if (reqCode == REQ_NOTIF_DEV_GATE) PERM_MODE_DEV_GATE else PERM_MODE_STARTUP
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            val sp = onboardingPrefs()
            val asked = sp.getBoolean(KEY_NOTIF_ASKED, false)
            val canPopup = !asked || shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS")
            sp.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
            if (canPopup) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), reqCode)
                return
            }
        }
        waitingSettingsReturn = true
        Toast.makeText(this, "请在系统设置中开启通知权限后返回", Toast.LENGTH_LONG).show()
        runCatching {
            startSystemSettingsOnBuiltinDisplay(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }.onFailure {
            runCatching {
                startSystemSettingsOnBuiltinDisplay(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        BridgeService.refreshFromReceiver(this)
        refreshAllState(false)
        if (requestCode == REQ_NOTIF_STARTUP) {
            finishOrPromptStartupPerms()
        } else if (requestCode == REQ_NOTIF_DEV_GATE) {
            if (hasAllCorePerms()) {
                completeDevGateAndLaunch()
            } else {
                openPermDialog(isDevGate = true)
            }
        }
    }

    private fun openSettingsWithPermReturn(mode: Int, toastMsg: String, primaryIntent: Intent) {
        if (mode != PERM_MODE_NONE) {
            pendingPermMode = mode
            waitingSettingsReturn = true
        }
        Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()
        runCatching { startSystemSettingsOnBuiltinDisplay(primaryIntent) }.onFailure {
            runCatching {
                startSystemSettingsOnBuiltinDisplay(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                )
            }
        }
    }

    private fun openOverlayPermissionSettings(mode: Int) {
        openSettingsWithPermReturn(
            mode,
            "请开启「显示在其他应用上层 / 悬浮窗」权限后返回",
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
        )
    }

    private fun openAccessibilitySettings(mode: Int) {
        openSettingsWithPermReturn(
            mode,
            "请在「无障碍 → 已下载的应用」开启「ExtBridge 平板辅助手势」后返回",
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    private fun openAppDetailsForRestricted(mode: Int) {
        openSettingsWithPermReturn(
            mode,
            "请点击应用详情右上角菜单「允许受限设置」后再开启无障碍",
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
    }

    private fun openDeveloperOptionsWithGate() {
        if (!hasAllCorePerms()) {
            openPermDialog(isDevGate = true)
            return
        }
        showDevGuideOrLaunchNow(forceDirect = false)
    }

    private fun completeDevGateAndLaunch() {
        pendingPermMode = PERM_MODE_NONE
        showPermDialog = false
        Toast.makeText(this, "权限已齐备，正在打开开发者选项", Toast.LENGTH_SHORT).show()
        showDevGuideOrLaunchNow(forceDirect = false)
    }

    private fun showDevGuideOrLaunchNow(forceDirect: Boolean) {
        val sp = onboardingPrefs()
        if (!forceDirect && !sp.getBoolean(KEY_DEV_GUIDE_DONE, false)) {
            sp.edit().putBoolean(KEY_DEV_GUIDE_DONE, true).apply()
            showDevGuideDialog = true
            return
        }
        launchDeveloperOptionsNow()
    }

    private fun launchDeveloperOptionsNow() {
        runCatching {
            startSystemSettingsOnBuiltinDisplay(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure {
            runCatching { startSystemSettingsOnBuiltinDisplay(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}
