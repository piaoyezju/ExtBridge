package com.extbridge.helper;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class BridgeService extends Service {
    public static final String ACTION_EXIT_ALL = "com.extbridge.helper.EXIT_ALL";
    private static final String CH_ID = "extbridge_ctrl_v3";
    private static final int NOTIF_ID = 301;
    private static volatile BridgeService sInstance;
    public static volatile boolean sUserStopping = false;

    public static boolean isRunning() {
        return sInstance != null;
    }

    public static boolean isBlackoutActive() {
        BridgeService inst = sInstance;
        return inst != null && inst.mBlackoutActive;
    }

    public static void toggleBlackoutFromUi(Context ctx) {
        if (ctx != null) ensureStarted(ctx);
        BridgeService inst = sInstance;
        if (inst != null && inst.mHandler != null) {
            inst.mHandler.post(() -> {
                BridgeClient.Status st = inst.mLastStatus;
                if (st != null && st.connected && st.mirrorMode == BridgeClient.MIRROR_MODE_MIRROR) {
                    if (inst.mBlackoutActive) inst.setDexBlackout(false);
                    inst.execSysAndRefresh(BridgeClient.SYS_SCREEN_DIM, -1, null);
                } else {
                    inst.toggleDexBlackout();
                }
            });
        } else {
            new Thread(() -> BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, -1, null)).start();
        }
    }

    public static void performAssignSplitFromUi(int slot, int taskId, String pkg, String cls) {
        BridgeService inst = sInstance;
        if (inst != null && inst.mHandler != null && inst.mStageAnimView != null) {
            int action = (slot == 0) ? BridgeClient.SPLIT_ASSIGN_LEFT_TOP : BridgeClient.SPLIT_ASSIGN_RIGHT_BOTTOM;
            inst.mHandler.post(() -> inst.performEnterOrAssignSplitAnimated(action, slot, taskId, pkg, cls));
        } else {
            new Thread(() -> {
                BridgeClient.assignSplitSlot(slot, pkg, cls);
                if (inst != null && inst.mHandler != null) {
                    inst.mHandler.post(inst::pollRemoteStateAsync);
                }
            }).start();
        }
    }
    private static ViewGroup sLauncherHost;
    private static int sLauncherHostDisplayId = -1;

    private Handler mHandler;
    private final ExecutorService mBg = Executors.newSingleThreadExecutor();
    private final ExecutorService mInputBg = Executors.newSingleThreadExecutor();
    private volatile boolean mDestroyed = false;
    private final AtomicLong mPollSeq = new AtomicLong(0);
    private String mLastNotifSig = "";
    private NotificationManager mNm;
    private DisplayManager mDm;
    private AudioManager mAm;

    private int mAttachedVdId = -1;
    private int mOverlayW = 0;
    private int mOverlayH = 0;
    private int mOverlayDpi = 0;
    private Context mExtCtx;
    private WindowManager mExtWm;
    private boolean mUsingA11yOverlay = false;
    private boolean mInLauncherHost = false;
    private boolean mLastObservedA11y = false;
    private int mWinType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    // Dock window (macOS style centered frosted glass)
    private static final String PREF_NAME = "ext_prefs";
    private static final String PREF_DOCK_AUTO_HIDE = "dock_auto_hide";
    private static final long DOCK_AUTO_HIDE_DELAY_MS = 2600L;

    private LinearLayout mDockRoot;
    private WindowManager.LayoutParams mDockLp;
    private boolean mDockHidden = false;
    private boolean mDockAutoHide = true;
    private boolean mAutoHideScheduled = false;
    private boolean mIsOnLauncherHome = true;
    private boolean mDockHovered = false;
    private FrameLayout mDockEdgeTrigger;
    private View mDockEdgePill;
    private WindowManager.LayoutParams mDockEdgeLp;
    private TextView mBtnDockSplit;
    private TextView mBtnToggle;
    private LinearLayout mTaskStrip;
    private View mTaskDivider;
    private LinearLayout mTrayBtn;
    private MiniIconView mTrayWifiIcon;
    private MiniIconView mTrayVolIcon;
    private MiniBatteryView mTrayBattView;
    private TextView mTrayBattPct;

    // Split-screen floating divider & popover menu
    private StageTransitionView mStageAnimView;
    private WindowManager.LayoutParams mStageAnimLp;
    private boolean mForceWindowOverlaysDuringAnim = false;
    private ValueAnimator mSplitDividerAnimator;
    private float mDisplayedSplitRatio = 0.50f;
    private FrameLayout mSplitDividerRoot;
    private TextView mSplitDividerPill;
    private WindowManager.LayoutParams mSplitDividerLp;
    private boolean mSplitDividerPortrait = false;
    private boolean mSplitDragging = false;
    private LinearLayout mSplitMenuRoot;
    private WindowManager.LayoutParams mSplitMenuLp;
    private boolean mSplitMenuOpen = false;
    private TextView mSplitSwapBtn;
    private final TextView[] mSplitRatioBtns = new TextView[5];
    private static final int[] SPLIT_PRESET_PCTS = {30, 40, 50, 60, 70};

    // Dock app context bubble (Android long-press / right-click shortcut popup)
    private LinearLayout mTaskBubbleRoot;
    private LinearLayout mTaskBubbleCard;
    private View mBubbleTriangle;
    private WindowManager.LayoutParams mTaskBubbleLp;
    private boolean mTaskBubbleOpen = false;
    private long mLastBubbleOpenMs = 0L;
    private ImageView mBubbleIcon;
    private TextView mBubbleTitle;
    private TextView mBubbleModeTag;
    private TextView mBubbleFreeformBtn;
    private TextView mBubbleSplit0Btn;
    private TextView mBubbleSplit1Btn;
    private TextView mBubbleCloseBtn;
    private BridgeClient.TaskEntry mBubbleTask;
    private String mBubbleLabel = "";
    private int mBubbleAnchorCenterX = 0;

    // Win11 Quick Settings Flyout window (no brightness slider)
    private FrameLayout mPanelRoot;
    private LinearLayout mPanelCard;
    private WindowManager.LayoutParams mPanelLp;
    private boolean mPanelOpen = false;
    private boolean mTrayPressed = false;
    private final Runnable mAutoHideDockTask = () -> {
        mAutoHideScheduled = false;
        if (mDockAutoHide && !mIsOnLauncherHome && !mPanelOpen && !mSplitMenuOpen && !mDockHovered && !mTrayPressed) {
            applyDockHiddenAnimated(true);
        }
    };
    private long mLastTrayUpMs = 0L;
    private long mLastTrayToggleMs = 0L;
    private final Runnable mOutsideDismissTask = () -> {
        long now = SystemClock.uptimeMillis();
        if (mPanelOpen && !mTrayPressed
                && (now - mLastTrayUpMs) > 220L
                && (now - mLastTrayToggleMs) > 220L) {
            setPanelOpen(false);
        }
    };
    private QuickTile mTileWifi;
    private QuickTile mTileBt;
    private QuickTile mTileInput;
    private QuickTile mTileAirplane;
    private QuickTile mTileTouchpad;
    private QuickTile mTilePowerSave;
    private MiniIconView mVolSpeakerIcon;
    private SeekBar mVolSeekBar;
    private boolean mSeekingVol = false;
    private MiniBatteryView mPanelBattView;
    private TextView mPanelBattText;
    private TextView mBtnPanelFullscreen;
    private TextView mBtnPanelDex;
    private TextView mBtnPanelMirror;
    private TextView mBtnPanelRotate;
    private TextView mBtnPanelSplit;
    private TextView mBtnPanelHideDock;
    private TextView mBtnPanelBlackout;
    private FrameLayout mBlackoutView;
    private WindowManager mBlackoutWm;
    private Runnable mBlackoutHideHintTask;
    private volatile boolean mBlackoutActive = false;
    private long mLastBlackoutTapMs = 0L;

    // Software mouse cursor window (always topmost)
    private CursorView mCursorView;
    private WindowManager.LayoutParams mCursorLp;
    private boolean mCursorVisible = false;

    private final LruCache<String, Drawable> mIconCache = new LruCache<>(48);
    private final LruCache<String, String> mLabelCache = new LruCache<>(64);
    private String mLastTaskSig = "";
    private volatile BridgeClient.Status mLastStatus = new BridgeClient.Status();
    private volatile BridgeClient.SysStatus mLastSys = new BridgeClient.SysStatus();
    private volatile BridgeClient.MirrorTelemetry mLastMirTel = new BridgeClient.MirrorTelemetry();
    private int mBattPct = 100;
    private boolean mBattCharging = false;

    // Mirror-mode Left & Right Telemetry + AppWidget Sidebars
    public static final int MIRROR_WIDGET_HOST_ID = 0xEB01;
    private static final String PREF_MIRROR_WIDGET_LEFT = "mirror_widget_left_id";
    private static final String PREF_MIRROR_WIDGET_RIGHT = "mirror_widget_right_id";
    private static final String PREF_MIRROR_PRESETS = "mirror_preset_slots";
    private static final String PREF_MIRROR_MEMO = "mirror_memo_text";
    private static final String PREF_MIRROR_THEME = "mirror_theme_id";
    private static final String PREF_MIRROR_CUSTOM_BG = "mirror_custom_bg";
    private static final String PREF_MIRROR_CURSOR_SPEED = "mirror_cursor_speed_idx";
    private static final String PREF_MIRROR_WIDGET_RATIO_L = "mirror_widget_ratio_l";
    private static final String PREF_MIRROR_WIDGET_RATIO_R = "mirror_widget_ratio_r";
    private static final Object sCapMoveLock = new Object();
    private static float sCapPendingDx = 0f;
    private static float sCapPendingDy = 0f;
    private static boolean sCapMoveInFlight = false;
    private AppWidgetManager mAppWidgetMgr;
    private AppWidgetHost mAppWidgetHost;
    private boolean mWidgetHostListening = false;
    private int mSideAttachedVdId = -1;
    private volatile int mSidePhysW = 0;
    private volatile int mSidePhysH = 0;
    private int mSideMirX = 0;
    private int mSideMirY = 0;
    private int mSideMirW = 0;
    private int mSideMirH = 0;
    private int mSideModeAttached = -1;
    private int mSideAttachedLeftWidgetId = -1;
    private int mSideAttachedRightWidgetId = -1;
    private String mSidePresetSigAttached = "";
    private String mSideMemoAttached = "";
    private String mSideThemeSigAttached = "";
    private String mSideWidgetRatioSigAttached = "";
    private BridgeClient.MirrorTheme mSideTheme = BridgeClient.resolveMirrorTheme("night", 0xFF0F172A);
    private Context mSideCtx;
    private WindowManager mSideWm;
    private LinearLayout mMirLeftRoot;
    private LinearLayout mMirRightRoot;
    private int mSideLeftX = 0, mSideLeftY = 0, mSideLeftW = 0, mSideLeftH = 0;
    private int mSideRightX = 0, mSideRightY = 0, mSideRightW = 0, mSideRightH = 0;
    private FrameLayout mEdgeSensorView;
    private FrameLayout mEdgeSensorView2;
    private WindowManager mEdgeD0Wm;
    private WindowManager.LayoutParams mEdgeLp;
    private WindowManager.LayoutParams mEdgeLp2;
    private boolean mEdgeCapturing = false;
    private boolean mEdgePointerCaptured = false;
    private boolean mOverlayPointerCaptureSupported = false;
    private int mEdgeReacquireAttempts = 0;
    private final AtomicBoolean mEdgeHoverInFlight = new AtomicBoolean(false);
    private String mEdgeSensorKey = "";
    private static final long[] EDGE_REACQUIRE_DELAYS_MS = {20L, 45L, 90L, 150L, 240L, 360L, 520L, 720L};
    private final Runnable mEdgeCaptureFallbackTask = () -> {
        if (mEdgeCapturing && !mEdgePointerCaptured && !mOverlayPointerCaptureSupported) {
            revertEdgeOverlayToSensor();
            SideMouseCaptureActivity.launch(BridgeService.this);
        }
    };
    private final Runnable mEdgeReacquireTask = new Runnable() {
        @Override
        public void run() {
            if (mDestroyed || !mEdgeCapturing || mEdgeSensorView == null || mEdgeD0Wm == null || mEdgeLp == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && mEdgeSensorView.hasPointerCapture()) {
                mEdgePointerCaptured = true;
                mOverlayPointerCaptureSupported = true;
                mEdgeReacquireAttempts = 0;
                return;
            }
            if (mEdgeReacquireAttempts >= EDGE_REACQUIRE_DELAYS_MS.length) {
                exitSidePointerCapture();
                return;
            }
            long nextDelay = EDGE_REACQUIRE_DELAYS_MS[mEdgeReacquireAttempts++];
            safeInputExecute(() -> {
                int[] res = BridgeClient.sendCapturedMirrorMouse(9, 0f, 0f, 0f);
                if (res[0] == 0) {
                    safePost(() -> revertEdgeOverlayToSensor());
                }
            });
            try {
                int activeFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                if (!mEdgeSensorView.hasWindowFocus()) {
                    // Pulse FLAG_NOT_FOCUSABLE -> focusable so WMS re-evaluates top Z-order focus
                    // in favor of ExtMirEdgeSensor even after an Activity launch stole mFocusedApp!
                    mEdgeLp.flags = activeFlags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    mEdgeD0Wm.updateViewLayout(mEdgeSensorView, mEdgeLp);
                }
                mEdgeLp.flags = activeFlags;
                mEdgeD0Wm.updateViewLayout(mEdgeSensorView, mEdgeLp);
                mEdgeSensorView.setFocusable(true);
                mEdgeSensorView.setFocusableInTouchMode(true);
                mEdgeSensorView.requestFocus();
                if (Build.VERSION.SDK_INT >= 26) {
                    mEdgeSensorView.requestPointerCapture();
                    if (mEdgeSensorView.hasPointerCapture()) {
                        mEdgePointerCaptured = true;
                        mOverlayPointerCaptureSupported = true;
                        mEdgeReacquireAttempts = 0;
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            if (mHandler != null && mEdgeCapturing && !mEdgePointerCaptured) {
                mHandler.removeCallbacks(this);
                mHandler.postDelayed(this, nextDelay);
            }
        }
    };
    private MiniBatteryView mMirBattIcon;
    private TextView mMirBattMain;
    private TextView mMirBattSub;
    private MiniIconView mMirWifiIcon;
    private TextView mMirWifiMain;
    private TextView mMirWifiSub;
    private MiniIconView mMirBtIcon;
    private TextView mMirBtMain;
    private TextView mMirBtSub;
    private MiniIconView mMirAudioIcon;
    private TextView mMirAudioRoute;
    private TextView mMirAudioSub;
    private ProgressBar mMirVolProg;
    private TextView mMirMediaBadge;
    private TextView mMirMediaTitle;
    private TextView mMirMediaSub;
    private TextView mMirCalBody;
    private TextView mMirPerfMain;
    private TextView mMirPerfSub;
    private ProgressBar mMirPerfProg;
    private TextView mMirNetMain;
    private TextView mMirNetSub;
    private long mLastNetSampleMs = 0L;
    private long mLastRxBytes = -1L;
    private long mLastTxBytes = -1L;
    private TextView mMirStorageMain;
    private TextView mMirStorageSub;
    private ProgressBar mMirStorageProg;
    private TextView mMirPomoTime;
    private TextView mMirPomoToggleBtn;
    private boolean mPomoRunning = false;
    private long mPomoRemainSec = 25 * 60;
    private long mPomoLastTickMs = 0L;
    private TextView mMirMemoBody;
    private TextView mMirDimMain;
    private TextView mMirDimBtn;
    private TextView mMirQuickDimBtn;
    private FrameLayout mMirLeftWidgetBox;
    private FrameLayout mMirRightWidgetBox;

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            safePost(BridgeService.this::pollRemoteStateAsync);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            safePost(() -> {
                BridgeClient.Status st = mLastStatus;
                boolean singleLeft = (mDm != null && mDm.getDisplays().length <= 1);
                if (displayId == mAttachedVdId
                        || (st != null && (displayId == st.extId || displayId == st.vdId))
                        || singleLeft) {
                    if (mBlackoutActive) {
                        setDexBlackout(false);
                    }
                    if (displayId == mAttachedVdId || singleLeft) {
                        detachExtOverlays(false);
                    }
                }
                if (displayId == mSideAttachedVdId || singleLeft) {
                    detachMirrorSidebars();
                }
                pollRemoteStateAsync();
            });
        }

        @Override
        public void onDisplayChanged(int displayId) {}
    };

    private final BroadcastReceiver mSysRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(act) || Intent.ACTION_USER_PRESENT.equals(act)) {
                safePost(() -> {
                    if (mBlackoutActive) {
                        setDexBlackout(false);
                    }
                });
                return;
            }
            if (Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                readBatteryIntent(intent);
            }
            if (mHandler != null) {
                mHandler.post(() -> {
                    refreshLocalHardwareViews();
                    if (!Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                        pollRemoteStateAsync();
                    }
                });
            }
        }
    };

    private volatile double mCachedMemAvailGb = 0.0;
    private volatile double mCachedMemTotalGb = 0.0;
    private volatile int mCachedMemPct = 50;
    private volatile double mCachedStorageAvailGb = 0.0;
    private volatile double mCachedStorageTotalGb = 0.0;
    private volatile int mCachedStoragePct = 50;
    private volatile int mCachedD0BrightPct = 50;
    private volatile double mCachedRxRate = 0.0;
    private volatile double mCachedTxRate = 0.0;
    private volatile double mCachedRxTotalMb = 0.0;
    private volatile boolean mMirrorMetricsValid = false;
    private final Runnable mPomoTickTask = new Runnable() {
        @Override
        public void run() {
            if (mDestroyed || !mPomoRunning || mHandler == null) return;
            if (mMirPomoTime != null) {
                refreshMirrorSideViews(mLastStatus);
            }
            if (mPomoRunning && !mDestroyed) {
                mHandler.postDelayed(this, 1000L);
            }
        }
    };

    private void safeBgExecute(Runnable r) {
        if (mDestroyed || r == null || mBg.isShutdown()) return;
        try {
            mBg.execute(() -> {
                if (!mDestroyed) r.run();
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void safeInputExecute(Runnable r) {
        if (mDestroyed || r == null || mInputBg.isShutdown()) return;
        try {
            mInputBg.execute(() -> {
                if (!mDestroyed) r.run();
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private void safePost(Runnable r) {
        if (mDestroyed || mHandler == null || r == null) return;
        mHandler.post(() -> {
            if (!mDestroyed) r.run();
        });
    }

    private final Runnable mHideCursorTask = () -> setCursorVisible(false);
    private final Runnable mPollTask = new Runnable() {
        @Override
        public void run() {
            if (mDestroyed || mHandler == null) return;
            pollRemoteStateAsync();
            BridgeClient.Status st = mLastStatus;
            long delay = (st != null && st.connected && (st.vdId > 0 || st.mirrorMode == 1)) ? 2200L : 6000L;
            mHandler.postDelayed(this, delay);
        }
    };

    public static boolean isDockAutoHideEnabled(Context ctx) {
        if (sInstance != null) return sInstance.mDockAutoHide;
        if (ctx == null) return true;
        return ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_DOCK_AUTO_HIDE, true);
    }

    public static void setDockAutoHideEnabled(Context ctx, boolean enabled) {
        if (ctx != null) {
            ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(PREF_DOCK_AUTO_HIDE, enabled).apply();
        }
        BridgeService svc = sInstance;
        if (svc == null) return;
        Runnable r = () -> {
            svc.mDockAutoHide = enabled;
            svc.updateDockAutoHideUi();
            if (!enabled) {
                svc.mHandler.removeCallbacks(svc.mAutoHideDockTask);
                svc.applyDockHiddenAnimated(false);
            } else {
                svc.evaluateDockAutoHide(false);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else if (svc.mHandler != null) svc.mHandler.post(r);
    }

    public static void ensureStarted(Context ctx) {
        if (sUserStopping || ctx == null) return;
        BridgeService running = sInstance;
        if (running != null && !running.mDestroyed) {
            refreshFromReceiver(ctx);
            return;
        }
        try {
            Intent i = new Intent(ctx, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {}
    }

    public static void refreshFromReceiver(Context ctx) {
        if (sUserStopping) return;
        BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.post(svc::pollRemoteStateAsync);
        }
    }

    public static void stopAllAndExit(Context ctx) {
        sUserStopping = true;
        SideMouseCaptureActivity.finishActiveIfAny();
        final Context appCtx = (ctx != null) ? ctx.getApplicationContext() : sInstance;
        final BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.removeCallbacksAndMessages(null);
            svc.mHandler.post(() -> {
                try { svc.detachExtOverlays(false); } catch (Throwable ignored) {}
                try { svc.detachMirrorSidebars(); } catch (Throwable ignored) {}
                try {
                    if (svc.mNm != null) svc.mNm.cancel(NOTIF_ID);
                } catch (Throwable ignored) {}
                try {
                    if (Build.VERSION.SDK_INT >= 24) {
                        svc.stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        svc.stopForeground(true);
                    }
                } catch (Throwable ignored) {}
            });
        } else if (appCtx != null) {
            try {
                NotificationManager nm = (NotificationManager) appCtx.getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIF_ID);
            } catch (Throwable ignored) {}
        }

        if (appCtx != null) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) appCtx.getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    List<android.app.ActivityManager.AppTask> tasks = am.getAppTasks();
                    if (tasks != null) {
                        for (android.app.ActivityManager.AppTask t : tasks) {
                            try { t.finishAndRemoveTask(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        new Thread(() -> {
            try {
                for (int i = 0; i < 8 && BridgeClient.getService() == null; i++) {
                    Thread.sleep(200);
                }
                BridgeClient.shutdownDaemon(appCtx);
            } catch (Throwable ignored) {}
            try { Thread.sleep(160); } catch (Throwable ignored) {}
            if (svc != null) {
                try { svc.stopSelf(); } catch (Throwable ignored) {}
            }
            try { Thread.sleep(80); } catch (Throwable ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }, "ExtBridgeExit").start();
    }

    public static void moveCursor(float x, float y, boolean pressing) {
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == svc.mHandler.getLooper()) {
            svc.updateCursorOnExt(x, y, pressing);
        } else {
            svc.mHandler.post(() -> svc.updateCursorOnExt(x, y, pressing));
        }
    }

    private void enterForegroundNow(BridgeClient.Status st) {
        try {
            Notification n = buildNotification(st != null ? st : new BridgeClient.Status());
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mDockAutoHide = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_DOCK_AUTO_HIDE, true);
        mHandler = new Handler(Looper.getMainLooper());
        mNm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mDm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        mAm = (AudioManager) getSystemService(AUDIO_SERVICE);

        createNotificationChannel();
        enterForegroundNow(mLastStatus);

        if (mDm != null) {
            try { mDm.registerDisplayListener(mDisplayListener, mHandler); } catch (Throwable ignored) {}
        }

        IntentFilter flt = new IntentFilter();
        flt.addAction(Intent.ACTION_BATTERY_CHANGED);
        flt.addAction(Intent.ACTION_SCREEN_OFF);
        flt.addAction(Intent.ACTION_USER_PRESENT);
        flt.addAction("android.media.VOLUME_CHANGED_ACTION");
        flt.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        flt.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        Intent sticky = (Build.VERSION.SDK_INT >= 33)
                ? registerReceiver(mSysRx, flt, RECEIVER_NOT_EXPORTED)
                : registerReceiver(mSysRx, flt);
        if (sticky != null) readBatteryIntent(sticky);

        NavAssistService.setOnStateChanged(() -> safePost(() -> {
            detachExtOverlays(false);
            pollRemoteStateAsync();
        }));

        mHandler.post(mPollTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always satisfy startForegroundService() contract on EVERY onStartCommand invocation.
        // Note: Binder handle is ONLY accepted via UID-authenticated BridgeProvider.call("setBinder"),
        // never from unauthenticated exported startService(Intent) extras.
        enterForegroundNow(mLastStatus);
        if (sUserStopping) {
            stopAllAndExit(this);
            return START_NOT_STICKY;
        }
        final Context appCtx = getApplicationContext();
        safeBgExecute(() -> {
            if (BridgeClient.getService() == null) {
                BridgeClient.requestBinder(appCtx);
            }
        });
        pollRemoteStateAsync();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        sInstance = null;
        NavAssistService.setOnStateChanged(null);
        if (mDm != null) {
            try { mDm.unregisterDisplayListener(mDisplayListener); } catch (Throwable ignored) {}
        }
        try { unregisterReceiver(mSysRx); } catch (Throwable ignored) {}
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
        if (mBlackoutActive) {
            mBlackoutActive = false;
            try {
                new Thread(() -> BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, 0, null),
                        "ExtDimRestore").start();
            } catch (Throwable ignored) {}
        }
        removeBlackoutOverlay();
        detachExtOverlays(false);
        detachMirrorSidebars();
        SideMouseCaptureActivity.finishActiveIfAny();
        resetCapturedMouseMoveState();
        mInputBg.shutdownNow();
        mBg.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void readBatteryIntent(Intent bi) {
        int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (lvl >= 0 && scale > 0) {
            mBattPct = Math.max(0, Math.min(100, Math.round(lvl * 100f / scale)));
        }
        mBattCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "ExtBridge 外接屏控制", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("通知栏一键切换平板/扩展屏键鼠、进入大触摸板及分辨率控制台");
            ch.setShowBadge(false);
            mNm.createNotificationChannel(ch);
        }
    }

    /**
     * Active built-in panel: display 0 when unfolded, the foldable cover panel (e.g. 1) when folded.
     * Built-in panels always have the lowest ids; HDMI/Cast/VirtualDisplays come later, so no hidden
     * Display#getType() reflection is needed here (it fails in the app process).
     */
    public static int getActiveBuiltinDisplayId(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return Display.DEFAULT_DISPLAY;
            Display d0 = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (isPanelAwake(d0)) return Display.DEFAULT_DISPLAY;
            int best = -1;
            for (Display d : dm.getDisplays()) {
                if (d == null) continue;
                int id = d.getDisplayId();
                if (id < 1 || id > 5) continue;
                if (!isPanelAwake(d)) continue;
                if (best < 0 || id < best) best = id;
            }
            if (best > 0) return best;
        } catch (Throwable ignored) {}
        return Display.DEFAULT_DISPLAY;
    }

    private static boolean isPanelAwake(Display d) {
        if (d == null) return false;
        try {
            int st = d.getState();
            return st == Display.STATE_ON || st == Display.STATE_ON_SUSPEND;
        } catch (Throwable t) {
            return true;
        }
    }

    private Notification buildNotification(BridgeClient.Status st) {
        ActivityOptions opts0 = ActivityOptions.makeBasic();
        opts0.setLaunchDisplayId(getActiveBuiltinDisplayId(this));
        Bundle optBundle0 = opts0.toBundle();

        Intent mainIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piMain = PendingIntent.getActivity(this, 1, mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, optBundle0);

        Intent tpIntent = new Intent(this, TouchpadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piTp = PendingIntent.getActivity(this, 2, tpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, optBundle0);

        Intent sideCtrlIntent = new Intent(this, SideControlActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piSideCtrl = PendingIntent.getActivity(this, 7, sideCtrlIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, optBundle0);

        Intent toggleIntent = new Intent(this, ControlReceiver.Internal.class)
                .setAction("com.extbridge.helper.TOGGLE_INPUT");
        PendingIntent piToggle = PendingIntent.getBroadcast(this, 3, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent extHomeIntent = new Intent(this, ControlReceiver.Internal.class)
                .setAction("com.extbridge.helper.LAUNCH_EXT_HOME");
        PendingIntent piExtHome = PendingIntent.getBroadcast(this, 4, extHomeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent mirrorIntent = new Intent(this, ControlReceiver.Internal.class)
                .setAction("com.extbridge.helper.TOGGLE_MIRROR")
                .putExtra("mode", 0);
        PendingIntent piMirror = PendingIntent.getBroadcast(this, 5, mirrorIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent exitIntent = new Intent(this, ControlReceiver.Internal.class)
                .setAction(ACTION_EXIT_ALL);
        PendingIntent piExit = PendingIntent.getBroadcast(this, 6, exitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean mirrorActive = st.connected && st.mirrorMode == 1 && st.extId > 0;
        boolean standbyOrDisconnected = !st.connected || (!mirrorActive && st.vdId <= 0);
        String title;
        String body;
        String toggleLabel;
        if (!st.connected) {
            title = "ExtBridge 未连接后台服务";
            body = "点击打开控制台激活，或点下方「⏻ 停止并退出」关闭常驻";
            toggleLabel = "⇄ 切换键鼠";
        } else if (mirrorActive) {
            title = "ExtBridge 原生镜像模式 · 物理 Display #" + st.extId;
            body = "外接屏正在同步手机/平板主屏 | 点击右侧「切回DeX」恢复独立大屏桌面";
            toggleLabel = st.inputToExt == 1 ? "⇄ 切到本机" : "⇄ 切回外接";
        } else if (st.vdId <= 0) {
            title = "ExtBridge 待机中 · 等待 USB-C 外接屏";
            body = "预设 " + st.virtW + "×" + st.virtH + " (" + st.virtDpi + "dpi) | 不用时可点「⏻ 停止并退出」";
            toggleLabel = st.inputToExt == 1 ? "⇄ 切到平板" : "⇄ 切回扩展屏";
        } else {
            String target = st.inputToExt == 1 ? "外接扩展屏" : "平板本机";
            String modeTag = mUsingA11yOverlay ? "无障碍B" : "兼容A";
            title = "ExtBridge 已挂载(" + modeTag + ") · "
                    + st.virtW + "×" + st.virtH + " @" + Math.round(st.virtFps) + "Hz";
            body = "键鼠控制：【" + target + "】 | 点通知本体进控制台，右侧按钮一键回桌面";
            toggleLabel = st.inputToExt == 1 ? "键鼠切回本机" : "键鼠切至扩展屏";
        }

        Notification.Builder nb = new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_extbridge)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piMain);
        if (standbyOrDisconnected) {
            nb.addAction(new Notification.Action.Builder(null, "打开控制台", piMain).build());
            nb.addAction(new Notification.Action.Builder(null, "停止并退出", piExit).build());
        } else {
            String act1Label = mirrorActive ? "切回 DeX" : toggleLabel;
            PendingIntent act1Pi = mirrorActive ? piMirror : piToggle;
            String act3Label = mirrorActive ? "副屏直控" : "回扩展桌面";
            PendingIntent act3Pi = mirrorActive ? piSideCtrl : piExtHome;
            nb.addAction(new Notification.Action.Builder(null, act1Label, act1Pi).build());
            nb.addAction(new Notification.Action.Builder(null, "大触摸板", piTp).build());
            nb.addAction(new Notification.Action.Builder(null, act3Label, act3Pi).build());
        }
        return nb.build();
    }

    private void notifyIfChanged(BridgeClient.Status st) {
        if (mNm == null || st == null) return;
        String sig = (st.connected ? 1 : 0) + ":" + st.mirrorMode + ":" + st.extId + ":" + st.vdId
                + ":" + st.virtW + "x" + st.virtH + "@" + st.virtDpi + ":" + Math.round(st.virtFps)
                + ":" + st.inputToExt + ":" + (mUsingA11yOverlay ? 1 : 0);
        if (!sig.equals(mLastNotifSig)) {
            mLastNotifSig = sig;
            mNm.notify(NOTIF_ID, buildNotification(st));
        }
    }

    private void sampleMirrorLocalMetrics() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long used = Math.max(0L, mi.totalMem - mi.availMem);
                mCachedMemPct = mi.totalMem > 0 ? Math.round(used * 100f / mi.totalMem) : 50;
                mCachedMemAvailGb = mi.availMem / (1024.0 * 1024.0 * 1024.0);
                mCachedMemTotalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0);
            }
        } catch (Throwable ignored) {}
        try {
            long nowMs = SystemClock.elapsedRealtime();
            long rx = TrafficStats.getTotalRxBytes();
            long tx = TrafficStats.getTotalTxBytes();
            if (rx >= 0 && tx >= 0) {
                if (mLastNetSampleMs > 0 && mLastRxBytes >= 0 && mLastTxBytes >= 0 && nowMs > mLastNetSampleMs) {
                    double sec = (nowMs - mLastNetSampleMs) / 1000.0;
                    mCachedRxRate = Math.max(0.0, (rx - mLastRxBytes) / sec);
                    mCachedTxRate = Math.max(0.0, (tx - mLastTxBytes) / sec);
                }
                mCachedRxTotalMb = rx / (1024.0 * 1024.0);
                mLastNetSampleMs = nowMs;
                mLastRxBytes = rx;
                mLastTxBytes = tx;
            }
        } catch (Throwable ignored) {}
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long total = sf.getTotalBytes();
            long avail = sf.getAvailableBytes();
            long used = Math.max(0L, total - avail);
            mCachedStoragePct = total > 0 ? Math.round(used * 100f / total) : 50;
            mCachedStorageAvailGb = avail / (1024.0 * 1024.0 * 1024.0);
            mCachedStorageTotalGb = total / (1024.0 * 1024.0 * 1024.0);
            int br = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            mCachedD0BrightPct = Math.max(1, Math.min(100, Math.round(br * 100f / 255f)));
        } catch (Throwable ignored) {}
        mMirrorMetricsValid = true;
    }

    private void pollRemoteStateAsync() {
        if (mDestroyed) return;
        final long seq = mPollSeq.incrementAndGet();
        safeBgExecute(() -> {
            BridgeClient.Status st = BridgeClient.getStatus();
            List<BridgeClient.TaskEntry> tasks = (st.connected && st.mirrorMode == 0 && st.vdId > 0)
                    ? BridgeClient.getTasks() : null;
            boolean needSys = st.connected && (st.vdId > 0 || (st.mirrorMode == 1 && st.sideVdId > 0));
            BridgeClient.SysStatus sys = needSys ? BridgeClient.getSysStatus() : mLastSys;
            boolean inMirrorSide = st.connected && st.mirrorMode == 1 && st.sideVdId > 0;
            BridgeClient.MirrorTelemetry mt = inMirrorSide
                    ? BridgeClient.getMirrorTelemetry() : mLastMirTel;
            if (inMirrorSide) {
                sampleMirrorLocalMetrics();
            }
            safePost(() -> {
                if (seq == mPollSeq.get()) {
                    applyStateOnUi(st, tasks, sys, mt);
                }
            });
        });
    }

    private void applyStateOnUi(
            BridgeClient.Status st,
            List<BridgeClient.TaskEntry> tasks,
            BridgeClient.SysStatus sys,
            BridgeClient.MirrorTelemetry mt) {
        if (mDestroyed) return;
        mLastStatus = st;
        if (sys != null && sys.valid) mLastSys = sys;
        if (mt != null && mt.valid) mLastMirTel = mt;
        notifyIfChanged(st);

        boolean a11yNow = NavAssistService.isRunning();
        if (st.connected && st.mirrorMode == 1) {
            if (mBlackoutActive) {
                setDexBlackout(false);
            }
            detachExtOverlays(false);
            if ((a11yNow || Settings.canDrawOverlays(this))
                    && st.sideVdId > 0 && st.mirrorSideMode > 0 && st.mirW > 0) {
                int lw = getMirrorWidgetId(this, 0);
                int rw = getMirrorWidgetId(this, 1);
                String presetSig = getMirrorPresetSlotsRaw(this);
                String memoTxt = getMirrorMemoText(this);
                String themeSig = getMirrorThemeId(this) + ":" + Integer.toHexString(getMirrorCustomBg(this));
                String ratioSig = getMirrorWidgetRatio(this, 0) + ":" + getMirrorWidgetRatio(this, 1);
                if ((mMirLeftRoot == null && mMirRightRoot == null)
                        || st.sideVdId != mSideAttachedVdId
                        || st.physW != mSidePhysW
                        || st.physH != mSidePhysH
                        || st.mirX != mSideMirX
                        || st.mirY != mSideMirY
                        || st.mirW != mSideMirW
                        || st.mirH != mSideMirH
                        || st.mirrorSideMode != mSideModeAttached
                        || a11yNow != mLastObservedA11y
                        || lw != mSideAttachedLeftWidgetId
                        || rw != mSideAttachedRightWidgetId
                        || !presetSig.equals(mSidePresetSigAttached)
                        || !memoTxt.equals(mSideMemoAttached)
                        || !themeSig.equals(mSideThemeSigAttached)
                        || !ratioSig.equals(mSideWidgetRatioSigAttached)) {
                    attachMirrorSidebars(st, lw, rw);
                }
                refreshMirrorSideViews(st);
                syncMirrorEdgeSensor(st);
            } else {
                detachMirrorEdgeSensor();
                detachMirrorSidebars();
            }
            return;
        }

        detachMirrorEdgeSensor();
        detachMirrorSidebars();

        if (!st.connected || st.vdId <= 0) {
            if (mBlackoutActive) {
                setDexBlackout(false);
            }
            detachExtOverlays(false);
            return;
        }
        if (!a11yNow && !Settings.canDrawOverlays(this)) return;

        int targetOverlayDpi = computeOverlayDpi(st);
        if (st.vdId != mAttachedVdId
                || a11yNow != mLastObservedA11y
                || targetOverlayDpi != mOverlayDpi) {
            attachExtOverlays(st);
        } else if (st.virtW != mOverlayW || st.virtH != mOverlayH) {
            mOverlayW = st.virtW;
            mOverlayH = st.virtH;
            mLastTaskSig = "";
            if (mStageAnimView != null && mStageAnimLp != null && mExtWm != null) {
                mStageAnimLp.width = mOverlayW;
                mStageAnimLp.height = mOverlayH;
                try { mExtWm.updateViewLayout(mStageAnimView, mStageAnimLp); } catch (Throwable ignored) {}
            }
            repositionDock();
            repositionPanel();
            repositionSplitDivider();
            repositionSplitMenu();
        }

        boolean launcherTop = (tasks != null);
        if (launcherTop) {
            for (BridgeClient.TaskEntry t : tasks) {
                if (t.isTopExt()) {
                    if (!"com.extbridge.helper".equals(t.pkg)) {
                        launcherTop = false;
                    }
                    break;
                }
            }
        }
        mIsOnLauncherHome = mInLauncherHost || launcherTop;

        updateInputToggleUi(st.inputToExt);
        if (tasks != null) updateTaskStrip(tasks);
        refreshLocalHardwareViews();
        refreshSysViews();
        if (mBtnPanelFullscreen != null) {
            boolean fsOn = st.fullscreenPolicy == 1;
            mBtnPanelFullscreen.setText(fsOn ? "桌面横屏: 开" : "桌面横屏: 关");
            HyperUi.styleHyperChip(mBtnPanelFullscreen, fsOn);
            HyperUi.setStartIcon(mBtnPanelFullscreen, HyperUi.ICON_FULLSCREEN,
                    fsOn ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        }
        if (mBtnPanelRotate != null) {
            boolean rotActive = st.virtRot != 0;
            mBtnPanelRotate.setText("旋转: " + BridgeClient.rotationLabel(st.virtRot));
            HyperUi.styleHyperChip(mBtnPanelRotate, rotActive);
            HyperUi.setStartIcon(mBtnPanelRotate, HyperUi.ICON_ROTATE,
                    rotActive ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        }
        updatePanelModeChips(st.mirrorMode);
        updateDockAutoHideUi();
        updateBlackoutUi();
        updateSplitUi(st);
        evaluateDockAutoHide(false);
    }

    private void attachExtOverlays(BridgeClient.Status st) {
        detachExtOverlays(true);
        Display target = null;
        for (Display d : mDm.getDisplays()) {
            if (d.getDisplayId() == st.vdId) { target = d; break; }
        }
        if (target == null) return;

        NavAssistService a11y = NavAssistService.getInstance();
        mLastObservedA11y = (a11y != null);
        if (a11y != null) {
            try {
                attachWithContext(a11y.createDisplayContext(target),
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, true, st);
                mLastNotifSig = "";
                notifyIfChanged(st);
                return;
            } catch (Throwable t) {
                t.printStackTrace();
                detachExtOverlays(true);
                mLastObservedA11y = true;
            }
        }
        if (Settings.canDrawOverlays(this)) {
            try {
                attachWithContext(createDisplayContext(target),
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false, st);
                mLastNotifSig = "";
                notifyIfChanged(st);
            } catch (Throwable t) {
                t.printStackTrace();
                detachExtOverlays(true);
            }
        }
    }

    private static int computeOverlayDpi(BridgeClient.Status st) {
        int reqDpi = (st != null && st.virtDpi > 0) ? st.virtDpi : 240;
        int virtW = (st != null && st.virtW > 0) ? st.virtW : 2560;
        int virtH = (st != null && st.virtH > 0) ? st.virtH : 1440;
        int minEdge = Math.min(virtW, virtH);
        int maxDpiForEdge = Math.max(80, Math.round(minEdge * 160f / 720f));
        return Math.max(80, Math.min(reqDpi, maxDpiForEdge));
    }

    private void attachWithContext(Context dispCtx, int winType, boolean isA11y, BridgeClient.Status st) {
        int overlayDpi = computeOverlayDpi(st);
        Context uiCtx = dispCtx;
        try {
            android.content.res.Configuration cfg = new android.content.res.Configuration(
                    dispCtx.getResources().getConfiguration());
            boolean needCfg = false;
            if (cfg.densityDpi != overlayDpi) {
                cfg.densityDpi = overlayDpi;
                needCfg = true;
            }
            if (cfg.fontScale != 1.0f) {
                cfg.fontScale = 1.0f;
                needCfg = true;
            }
            if (needCfg) {
                uiCtx = dispCtx.createConfigurationContext(cfg);
            }
        } catch (Throwable ignored) {}
        mExtCtx = uiCtx;
        mExtWm = (WindowManager) dispCtx.getSystemService(WINDOW_SERVICE);
        mWinType = winType;
        mUsingA11yOverlay = isA11y;
        mAttachedVdId = st.vdId;
        mOverlayW = st.virtW;
        mOverlayH = st.virtH;
        mOverlayDpi = overlayDpi;
        mLastTaskSig = "";

        buildStageAnimOverlay(mExtCtx, st); // Added FIRST so stage transition sits below Dock & Cursor, above Activities
        buildSplitOverlays(mExtCtx, st);
        buildDockEdgeTriggerOverlay(mExtCtx);
        buildDockOverlay(mExtCtx, st);
        buildTaskBubbleOverlay(mExtCtx);
        buildWin11PanelOverlay(mExtCtx, st);
        buildCursorOverlay(mExtCtx, st); // Added LAST so cursor stays above Dock & Flyout
        syncOverlayHostSurface(true);
    }

    private void buildStageAnimOverlay(Context ctx, BridgeClient.Status st) {
        mStageAnimView = new StageTransitionView(ctx);
        mStageAnimLp = new WindowManager.LayoutParams(
                Math.max(600, st.virtW),
                Math.max(600, st.virtH),
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        mStageAnimLp.gravity = Gravity.TOP | Gravity.LEFT;
        mStageAnimLp.x = 0;
        mStageAnimLp.y = 0;
        if (mExtWm != null) {
            // Propagate BadTokenException / SecurityException so attachExtOverlays can fall back cleanly
            mExtWm.addView(mStageAnimView, mStageAnimLp);
        }
    }

    public static void setDockHidden(boolean hidden) {
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.applyDockHidden(hidden);
        } else if (svc.mHandler != null) {
            svc.mHandler.post(() -> svc.applyDockHidden(hidden));
        }
    }

    public static void setLauncherOverlayHost(ViewGroup host, int displayId) {
        sLauncherHost = host;
        sLauncherHostDisplayId = displayId;
        BridgeService svc = sInstance;
        if (svc == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.syncOverlayHostSurface(false);
        } else if (svc.mHandler != null) {
            svc.mHandler.post(() -> svc.syncOverlayHostSurface(false));
        }
    }

    public static void clearLauncherOverlayHost(ViewGroup host) {
        if (sLauncherHost == host) {
            sLauncherHost = null;
            sLauncherHostDisplayId = -1;
            BridgeService svc = sInstance;
            if (svc == null) return;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                svc.syncOverlayHostSurface(false);
            } else if (svc.mHandler != null) {
                svc.mHandler.post(() -> svc.syncOverlayHostSurface(false));
            }
        }
    }

    public static void dismissPanelOnHostTouch(float winX, float winY) {
        BridgeService svc = sInstance;
        if (svc != null) {
            svc.handleHostTouch(winX, winY);
        }
    }

    private boolean shouldHostInLauncher() {
        return !mForceWindowOverlaysDuringAnim
                && (mStageAnimView == null || !mStageAnimView.isAnimating())
                && !mUsingA11yOverlay
                && mAttachedVdId > 0
                && sLauncherHost != null
                && sLauncherHostDisplayId == mAttachedVdId
                && (mLastStatus == null || mLastStatus.splitActive == 0);
    }

    private void removeOverlayView(View v) {
        if (v == null) return;
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(v);
        } else if (p != null && mExtWm != null) {
            try { mExtWm.removeViewImmediate(v); } catch (Throwable ignored) {}
        }
    }

    private static void safeAddToHost(ViewGroup host, View child, ViewGroup.LayoutParams lp) {
        if (host == null || child == null) return;
        if (child.getParent() != null) {
            ViewParent p = child.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(child);
        }
        if (child.getParent() == null) {
            host.addView(child, lp);
        }
    }

    private void syncOverlayHostSurface(boolean forceAttach) {
        if (mDockRoot == null || mPanelRoot == null || mCursorView == null || mExtCtx == null) return;
        boolean wantHost = shouldHostInLauncher();
        if (!forceAttach && wantHost == mInLauncherHost) {
            ViewParent p = mDockRoot.getParent();
            boolean rightParent = wantHost ? (p == sLauncherHost) : (p != null && !(p instanceof ViewGroup));
            if (rightParent) return;
        }
        // Prevent detaching mid-close-animation from stranding an alpha=0 VISIBLE touch-eating popover
        if (!mSplitMenuOpen && mSplitMenuRoot != null) {
            mSplitMenuRoot.animate().cancel();
            mSplitMenuRoot.setVisibility(View.GONE);
            mSplitMenuRoot.setAlpha(1f);
            mSplitMenuRoot.setTranslationY(0f);
            mSplitMenuRoot.setScaleX(1f);
            mSplitMenuRoot.setScaleY(1f);
        }
        if (!mTaskBubbleOpen && mTaskBubbleRoot != null) {
            if (mTaskBubbleCard != null) mTaskBubbleCard.animate().cancel();
            mTaskBubbleRoot.animate().cancel();
            mTaskBubbleRoot.setVisibility(View.GONE);
            mTaskBubbleRoot.setAlpha(1f);
        }
        if (!mPanelOpen && mPanelRoot != null) {
            if (mPanelCard != null) {
                mPanelCard.animate().cancel();
                mPanelCard.setAlpha(1f);
                mPanelCard.setTranslationY(0f);
                mPanelCard.setScaleX(1f);
                mPanelCard.setScaleY(1f);
            }
            mPanelRoot.setVisibility(View.GONE);
        }
        if (mDockHidden && mDockRoot != null) {
            mDockRoot.animate().cancel();
            mDockRoot.setVisibility(View.GONE);
            mDockRoot.setTranslationY(0f);
            mDockRoot.setAlpha(1f);
        }

        removeOverlayView(mSplitDividerRoot);
        removeOverlayView(mDockEdgeTrigger);
        removeOverlayView(mDockRoot);
        removeOverlayView(mSplitMenuRoot);
        removeOverlayView(mTaskBubbleRoot);
        removeOverlayView(mPanelRoot);
        removeOverlayView(mCursorView);
        mInLauncherHost = wantHost;
        if (wantHost) {
            if (mSplitDividerRoot != null) {
                FrameLayout.LayoutParams sdlp = new FrameLayout.LayoutParams(
                        mSplitDividerLp != null ? mSplitDividerLp.width : dp(mExtCtx, 40),
                        mSplitDividerLp != null ? mSplitDividerLp.height : dp(mExtCtx, 84),
                        Gravity.TOP | Gravity.LEFT);
                safeAddToHost(sLauncherHost, mSplitDividerRoot, sdlp);
            }
            if (mDockEdgeTrigger != null) {
                FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                        dp(mExtCtx, 220), dp(mExtCtx, 16), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                safeAddToHost(sLauncherHost, mDockEdgeTrigger, elp);
            }
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    mDockLp != null ? mDockLp.width : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            dlp.bottomMargin = dp(mExtCtx, 10);
            safeAddToHost(sLauncherHost, mDockRoot, dlp);

            if (mSplitMenuRoot != null) {
                FrameLayout.LayoutParams smlp = new FrameLayout.LayoutParams(
                        dp(mExtCtx, 296), ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.LEFT);
                safeAddToHost(sLauncherHost, mSplitMenuRoot, smlp);
            }

            if (mTaskBubbleRoot != null) {
                FrameLayout.LayoutParams tblp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.LEFT);
                safeAddToHost(sLauncherHost, mTaskBubbleRoot, tblp);
            }

            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    dp(mExtCtx, 354), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT);
            if (mPanelLp != null) {
                plp.leftMargin = mPanelLp.x;
                plp.topMargin = mPanelLp.y;
            }
            safeAddToHost(sLauncherHost, mPanelRoot, plp);

            mCursorView.setClickable(false);
            mCursorView.setFocusable(false);
            mCursorView.setElevation(dp(mExtCtx, 48));
            mCursorView.setTranslationZ(dp(mExtCtx, 48));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    44, 44, Gravity.TOP | Gravity.LEFT);
            safeAddToHost(sLauncherHost, mCursorView, clp);
            mCursorView.bringToFront();
            if (mCursorLp != null) {
                mCursorView.setTranslationX(mCursorLp.x);
                mCursorView.setTranslationY(mCursorLp.y);
            }
            sLauncherHost.post(() -> {
                if (mInLauncherHost) {
                    repositionDock();
                    repositionSplitDivider();
                    repositionSplitMenu();
                    if (mTaskBubbleOpen) repositionTaskBubble();
                    if (mPanelOpen) repositionPanel();
                }
            });
        } else if (mExtWm != null) {
            mCursorView.setTranslationX(0f);
            mCursorView.setTranslationY(0f);
            if (mSplitDividerRoot != null && mSplitDividerLp != null) {
                try { mExtWm.addView(mSplitDividerRoot, mSplitDividerLp); } catch (Throwable ignored) {}
            }
            if (mDockEdgeTrigger != null && mDockEdgeLp != null) {
                try { mExtWm.addView(mDockEdgeTrigger, mDockEdgeLp); } catch (Throwable ignored) {}
            }
            try { mExtWm.addView(mDockRoot, mDockLp); } catch (Throwable ignored) {}
            if (mSplitMenuRoot != null && mSplitMenuLp != null) {
                try { mExtWm.addView(mSplitMenuRoot, mSplitMenuLp); } catch (Throwable ignored) {}
            }
            if (mTaskBubbleRoot != null && mTaskBubbleLp != null) {
                try { mExtWm.addView(mTaskBubbleRoot, mTaskBubbleLp); } catch (Throwable ignored) {}
            }
            try { mExtWm.addView(mPanelRoot, mPanelLp); } catch (Throwable ignored) {}
            try { mExtWm.addView(mCursorView, mCursorLp); } catch (Throwable ignored) {}
        }
        syncDockEdgeVisibility();
        repositionDock();
        repositionSplitDivider();
        repositionSplitMenu();
        if (mTaskBubbleOpen) repositionTaskBubble();
        if (mPanelOpen) repositionPanel();
        evaluateDockAutoHide(false);
    }

    private void handleHostTouch(float winX, float winY) {
        if (!mInLauncherHost) return;
        if (mPanelOpen && mPanelCard != null
                && !isPointInView(mPanelCard, winX, winY) && !isPointInView(mTrayBtn, winX, winY)) {
            setPanelOpen(false);
        }
        if (mTaskBubbleOpen && mTaskBubbleCard != null
                && !isPointInView(mTaskBubbleCard, winX, winY)) {
            setTaskBubbleOpen(false);
        }
    }

    private static boolean isPointInView(View v, float winX, float winY) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return winX >= loc[0] && winX <= (loc[0] + v.getWidth())
                && winY >= loc[1] && winY <= (loc[1] + v.getHeight());
    }

    private static boolean isPointInViewOnScreen(View v, float screenX, float screenY) {
        if (v == null || v.getVisibility() != View.VISIBLE || !v.isShown()
                || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return screenX >= loc[0] && screenX <= (loc[0] + v.getWidth())
                && screenY >= loc[1] && screenY <= (loc[1] + v.getHeight());
    }

    /**
     * Classifies what virtual-touchpad right-click / 2-finger tap should do at (curX, curY):
     *   1 = handled locally (dismissed an open Dock popover or toggled Split Menu)
     *   2 = dispatch BUTTON_SECONDARY context-click (Dock task icon or DeX Desktop app icon)
     *   0 = navigate Back on external display
     */
    public static int classifyRightClickTarget(int curX, int curY) {
        BridgeService svc = sInstance;
        if (svc == null) return 0;
        if (svc.mPanelOpen || svc.mSplitMenuOpen || svc.mTaskBubbleOpen) {
            Runnable closeTask = () -> {
                svc.setPanelOpen(false);
                svc.setSplitMenuOpen(false);
                svc.setTaskBubbleOpen(false);
            };
            if (Looper.myLooper() == Looper.getMainLooper()) closeTask.run();
            else if (svc.mHandler != null) svc.mHandler.post(closeTask);
            return 1;
        }
        BridgeClient.Status st = svc.mLastStatus;
        if (st != null && st.connected && st.mirrorMode == BridgeClient.MIRROR_MODE_DEX) {
            if (!svc.mDockHidden && svc.mDockRoot != null && svc.mDockRoot.getVisibility() == View.VISIBLE) {
                if (svc.mTaskStrip != null && isPointInViewOnScreen(svc.mTaskStrip, curX, curY)) {
                    return 2;
                }
                if (svc.mBtnDockSplit != null && isPointInViewOnScreen(svc.mBtnDockSplit, curX, curY)) {
                    Runnable toggleSplitMenu = () -> svc.setSplitMenuOpen(!svc.mSplitMenuOpen);
                    if (Looper.myLooper() == Looper.getMainLooper()) toggleSplitMenu.run();
                    else if (svc.mHandler != null) svc.mHandler.post(toggleSplitMenu);
                    return 1;
                }
            }
            if ((svc.mIsOnLauncherHome || st.splitActive == 1)
                    && LauncherActivity.shouldConsumeRightClickAt(curX, curY)) {
                return 2;
            }
        }
        return 0;
    }

    private void buildDockOverlay(Context ctx, BridgeClient.Status st) {
        mDockRoot = new LinearLayout(ctx);
        mDockRoot.setOrientation(LinearLayout.HORIZONTAL);
        mDockRoot.setGravity(Gravity.CENTER_VERTICAL);
        mDockRoot.setBaselineAligned(false);
        mDockRoot.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        mDockRoot.setElevation(dp(ctx, 10));
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(HyperUi.DOCK_BG);
        dockBg.setCornerRadius(dp(ctx, 22));
        dockBg.setStroke(1, HyperUi.DOCK_STROKE);
        mDockRoot.setBackground(dockBg);
        mDockRoot.setOnHoverListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_HOVER_ENTER || act == MotionEvent.ACTION_HOVER_MOVE) {
                mDockHovered = true;
                cancelAutoHideTimer();
            } else if (act == MotionEvent.ACTION_HOVER_EXIT) {
                mDockHovered = false;
                evaluateDockAutoHide(false);
            }
            return false;
        });

        // Left: App Launcher/Search, Back, Split, Input Switch
        TextView btnHome = makeDockBtn(ctx, "桌面", 0x2AFFFFFF, Color.WHITE);
        HyperUi.setStartIcon(btnHome, HyperUi.ICON_APPS, Color.WHITE, 12.5f, 4f);
        attachTactilePress(btnHome, 0.90f);
        btnHome.setOnClickListener(v -> {
            setPanelOpen(false);
            setSplitMenuOpen(false);
            setTaskBubbleOpen(false);
            performHomeButtonAnimated(btnHome);
        });
        mDockRoot.addView(btnHome);

        TextView btnBack = makeDockBtn(ctx, "返回", 0x22FFFFFF, Color.WHITE);
        HyperUi.setStartIcon(btnBack, HyperUi.ICON_CHEVRON_LEFT, Color.WHITE, 12.5f, 3f);
        attachTactilePress(btnBack, 0.90f);
        btnBack.setOnClickListener(v -> safeInputExecute(BridgeClient::sendBack));
        mDockRoot.addView(btnBack);

        mBtnDockSplit = makeDockBtn(ctx, "分屏", 0x22FFFFFF, Color.WHITE);
        HyperUi.setStartIcon(mBtnDockSplit, HyperUi.ICON_SPLIT, Color.WHITE, 12.5f, 4f);
        attachTactilePress(mBtnDockSplit, 0.90f);
        mBtnDockSplit.setOnClickListener(v -> triggerQuickSplitOrMenu());
        mBtnDockSplit.setOnLongClickListener(v -> {
            setSplitMenuOpen(!mSplitMenuOpen);
            return true;
        });
        mDockRoot.addView(mBtnDockSplit);

        mBtnToggle = makeDockBtn(ctx, st.inputToExt == 1 ? "外接" : "本机",
                st.inputToExt == 1 ? HyperUi.HYPER_BLUE : 0xCC3B4252, Color.WHITE);
        HyperUi.setStartIcon(mBtnToggle, HyperUi.ICON_SWAP, Color.WHITE, 12.5f, 4f);
        attachTactilePress(mBtnToggle, 0.90f);
        mBtnToggle.setOnClickListener(v -> safeInputExecute(() -> {
            int m = BridgeClient.toggleInput();
            safePost(() -> {
                updateInputToggleUi(m);
                Toast.makeText(BridgeService.this, m == 1 ? "键鼠 -> 扩展屏" : "键鼠 -> 平板", Toast.LENGTH_SHORT).show();
            });
        }));
        mDockRoot.addView(mBtnToggle);

        mTaskDivider = makeVerticalDivider(ctx);
        mTaskDivider.setVisibility(View.GONE);
        mDockRoot.addView(mTaskDivider);

        // Center: Multitasking running/recent apps strip
        mTaskStrip = new LinearLayout(ctx);
        mTaskStrip.setOrientation(LinearLayout.HORIZONTAL);
        mTaskStrip.setGravity(Gravity.CENTER_VERTICAL);
        mDockRoot.addView(mTaskStrip);

        mDockRoot.addView(makeVerticalDivider(ctx));

        // Right: Win11 System Tray Pill (Wi-Fi, Vol, Battery %, Clock + Date)
        mTrayBtn = new LinearLayout(ctx);
        mTrayBtn.setOrientation(LinearLayout.HORIZONTAL);
        mTrayBtn.setGravity(Gravity.CENTER_VERTICAL);
        mTrayBtn.setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4));
        updateTrayPillBackground(false);
        mTrayBtn.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                mTrayPressed = true;
                mHandler.removeCallbacks(mOutsideDismissTask);
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                mTrayPressed = false;
                mLastTrayUpMs = SystemClock.uptimeMillis();
            }
            return false;
        });
        mTrayBtn.setOnClickListener(v -> {
            mHandler.removeCallbacks(mOutsideDismissTask);
            mLastTrayToggleMs = SystemClock.uptimeMillis();
            setPanelOpen(!mPanelOpen);
        });

        mTrayWifiIcon = new MiniIconView(ctx, MiniIconView.KIND_WIFI, Color.WHITE);
        LinearLayout.LayoutParams wiLp = new LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16));
        wiLp.rightMargin = dp(ctx, 6);
        mTrayBtn.addView(mTrayWifiIcon, wiLp);

        mTrayVolIcon = new MiniIconView(ctx, MiniIconView.KIND_VOLUME, Color.WHITE);
        LinearLayout.LayoutParams voLp = new LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16));
        voLp.rightMargin = dp(ctx, 6);
        mTrayBtn.addView(mTrayVolIcon, voLp);

        mTrayBattView = new MiniBatteryView(ctx);
        LinearLayout.LayoutParams bvLp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 12));
        bvLp.rightMargin = dp(ctx, 4);
        mTrayBtn.addView(mTrayBattView, bvLp);

        mTrayBattPct = new TextView(ctx);
        mTrayBattPct.setTextColor(Color.WHITE);
        mTrayBattPct.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f);
        mTrayBattPct.getPaint().setFakeBoldText(true);
        enforceSingleLine(mTrayBattPct);
        LinearLayout.LayoutParams bpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bpLp.rightMargin = dp(ctx, 9);
        mTrayBtn.addView(mTrayBattPct, bpLp);

        LinearLayout clockCol = new LinearLayout(ctx);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        TextClock tcTime = new TextClock(ctx);
        tcTime.setFormat24Hour("HH:mm");
        tcTime.setFormat12Hour("HH:mm");
        tcTime.setTextColor(Color.WHITE);
        tcTime.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        tcTime.getPaint().setFakeBoldText(true);
        enforceSingleLine(tcTime);
        clockCol.addView(tcTime);

        TextClock tcDate = new TextClock(ctx);
        tcDate.setFormat24Hour("yyyy/M/d");
        tcDate.setFormat12Hour("yyyy/M/d");
        tcDate.setTextColor(0xFFCBD5E1);
        tcDate.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f);
        enforceSingleLine(tcDate);
        clockCol.addView(tcDate);

        mTrayBtn.addView(clockCol);
        mDockRoot.addView(mTrayBtn);

        mDockLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mDockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mDockLp.x = 0;
        mDockLp.y = dp(ctx, 10);

        mDockRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                repositionDock();
                if (mPanelOpen) repositionPanel();
            }
        });
    }

    private void buildWin11PanelOverlay(Context ctx, BridgeClient.Status st) {
        mPanelRoot = new FrameLayout(ctx);
        mPanelRoot.setClipToPadding(false);
        mPanelRoot.setClipChildren(false);
        mPanelRoot.setPadding(dp(ctx, 8), dp(ctx, 14), dp(ctx, 8), dp(ctx, 8));

        mPanelCard = new LinearLayout(ctx);
        mPanelCard.setOrientation(LinearLayout.VERTICAL);
        mPanelCard.setElevation(dp(ctx, 18));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(HyperUi.FLYOUT_BG);
        panelBg.setCornerRadius(dp(ctx, 22));
        panelBg.setStroke(1, HyperUi.FLYOUT_STROKE);
        mPanelCard.setBackground(panelBg);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 14));

        // Row 1 of 3x2 grid: Wi-Fi, Bluetooth, Input Target
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(ctx, 10);

        mTileWifi = new QuickTile(ctx, MiniIconView.KIND_WIFI, "Wi-Fi", true,
                () -> execSysAndRefresh(BridgeClient.SYS_WIFI, -1, null),
                () -> openExtSettings(Settings.ACTION_WIFI_SETTINGS));
        mTileBt = new QuickTile(ctx, MiniIconView.KIND_BT, "蓝牙", true,
                () -> execSysAndRefresh(BridgeClient.SYS_BT, -1, null),
                () -> openExtSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        mTileInput = new QuickTile(ctx, MiniIconView.KIND_DISPLAY_SWITCH, "外接控制", false,
                () -> mBg.execute(() -> {
                    int m = BridgeClient.toggleInput();
                    mHandler.post(() -> {
                        updateInputToggleUi(m);
                        pollRemoteStateAsync();
                    });
                }), null);
        addGridTile(row1, mTileWifi.root, true);
        addGridTile(row1, mTileBt.root, true);
        addGridTile(row1, mTileInput.root, false);
        body.addView(row1, rowLp);

        // Row 2 of 3x2 grid: Airplane, Touchpad, Battery Saver
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.bottomMargin = dp(ctx, 16);

        mTileAirplane = new QuickTile(ctx, MiniIconView.KIND_AIRPLANE, "飞行模式", false,
                () -> execSysAndRefresh(BridgeClient.SYS_AIRPLANE, -1, null), null);
        mTileTouchpad = new QuickTile(ctx, MiniIconView.KIND_TOUCHPAD, "大触摸板", true,
                this::openTabletTouchpad, this::openTabletTouchpad);
        mTilePowerSave = new QuickTile(ctx, MiniIconView.KIND_BATTERY_SAVER, "节能模式", false,
                () -> execSysAndRefresh(BridgeClient.SYS_POWER_SAVE, -1, null), null);
        addGridTile(row2, mTileAirplane.root, true);
        addGridTile(row2, mTileTouchpad.root, true);
        addGridTile(row2, mTilePowerSave.root, false);
        body.addView(row2, row2Lp);

        // Volume Slider row (no brightness slider, per user preference)
        LinearLayout volRow = new LinearLayout(ctx);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volRow.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 2));

        mVolSpeakerIcon = new MiniIconView(ctx, MiniIconView.KIND_VOLUME, Color.WHITE);
        mVolSpeakerIcon.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        mVolSpeakerIcon.setOnClickListener(v -> {
            if (mAm != null) {
                try {
                    mAm.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                } catch (Throwable ignored) {}
                refreshLocalHardwareViews();
            }
        });
        volRow.addView(mVolSpeakerIcon, new LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)));

        mVolSeekBar = new SeekBar(ctx);
        int accent = HyperUi.HYPER_BLUE;
        if (mVolSeekBar.getProgressDrawable() != null) {
            mVolSeekBar.getProgressDrawable().setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        }
        if (mVolSeekBar.getThumb() != null) {
            mVolSeekBar.getThumb().setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        if (mAm != null) {
            mVolSeekBar.setMax(Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        }
        mVolSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mAm != null) {
                    try {
                        mAm.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    } catch (Throwable ignored) {}
                    refreshTrayIconsOnly();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { mSeekingVol = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { mSeekingVol = false; refreshLocalHardwareViews(); }
        });
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        skLp.leftMargin = dp(ctx, 4);
        skLp.rightMargin = dp(ctx, 4);
        volRow.addView(mVolSeekBar, skLp);

        TextView btnAudioMore = new TextView(ctx);
        btnAudioMore.setText("音效");
        btnAudioMore.setTextColor(0xFFCBD5E1);
        btnAudioMore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnAudioMore.setGravity(Gravity.CENTER_VERTICAL);
        btnAudioMore.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
        HyperUi.setStartIcon(btnAudioMore, HyperUi.ICON_SLIDERS, 0xFFCBD5E1, 13f, 4f);
        btnAudioMore.setOnClickListener(v -> openExtSettings(Settings.ACTION_SOUND_SETTINGS));
        volRow.addView(btnAudioMore);

        body.addView(volRow);

        // Display output mode selector (DeX / Mirror)
        LinearLayout modeRow = new LinearLayout(ctx);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mrLp.topMargin = dp(ctx, 10);
        modeRow.setLayoutParams(mrLp);

        mBtnPanelDex = makeFooterChip(ctx, "DeX 桌面");
        mBtnPanelDex.setSingleLine(true);
        mBtnPanelDex.setGravity(Gravity.CENTER);
        mBtnPanelDex.setOnClickListener(v -> switchPanelMode(BridgeClient.MIRROR_MODE_DEX));
        LinearLayout.LayoutParams mp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mp1.rightMargin = dp(ctx, 6);
        modeRow.addView(mBtnPanelDex, mp1);

        mBtnPanelMirror = makeFooterChip(ctx, "原生镜像");
        mBtnPanelMirror.setSingleLine(true);
        mBtnPanelMirror.setGravity(Gravity.CENTER);
        mBtnPanelMirror.setOnClickListener(v -> switchPanelMode(BridgeClient.MIRROR_MODE_MIRROR));
        LinearLayout.LayoutParams mp3 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modeRow.addView(mBtnPanelMirror, mp3);

        updatePanelModeChips(st.mirrorMode);
        body.addView(modeRow);

        // DeX policy & action strips
        LinearLayout dexRow = new LinearLayout(ctx);
        dexRow.setOrientation(LinearLayout.HORIZONTAL);
        dexRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        drLp.topMargin = dp(ctx, 6);
        dexRow.setLayoutParams(drLp);

        boolean initFs = st.fullscreenPolicy == 1;
        mBtnPanelFullscreen = makeFooterChip(ctx, initFs ? "桌面横屏: 开" : "桌面横屏: 关");
        mBtnPanelFullscreen.setSingleLine(true);
        mBtnPanelFullscreen.setGravity(Gravity.CENTER);
        HyperUi.styleHyperChip(mBtnPanelFullscreen, initFs);
        HyperUi.setStartIcon(mBtnPanelFullscreen, HyperUi.ICON_FULLSCREEN,
                initFs ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        mBtnPanelFullscreen.setOnClickListener(v -> mBg.execute(() -> {
            int next = BridgeClient.toggleFullscreenPolicy();
            mHandler.post(() -> {
                Toast.makeText(BridgeService.this,
                        next == 1 ? "已开启：DeX 默认横屏大屏桌面" : "已关闭：DeX 默认横屏锁定",
                        Toast.LENGTH_SHORT).show();
                pollRemoteStateAsync();
            });
        }));
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dp1.rightMargin = dp(ctx, 6);
        dexRow.addView(mBtnPanelFullscreen, dp1);

        boolean initRot = st.virtRot != 0;
        mBtnPanelRotate = makeFooterChip(ctx, "旋转: " + BridgeClient.rotationLabel(st.virtRot));
        mBtnPanelRotate.setSingleLine(true);
        mBtnPanelRotate.setGravity(Gravity.CENTER);
        HyperUi.styleHyperChip(mBtnPanelRotate, initRot);
        HyperUi.setStartIcon(mBtnPanelRotate, HyperUi.ICON_ROTATE,
                initRot ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        mBtnPanelRotate.setOnClickListener(v -> mBg.execute(() -> {
            int next = BridgeClient.cycleRotation();
            mHandler.post(() -> {
                Toast.makeText(BridgeService.this,
                        "输出旋转：" + BridgeClient.rotationLabel(next),
                        Toast.LENGTH_SHORT).show();
                pollRemoteStateAsync();
            });
        }));
        LinearLayout.LayoutParams dp3a = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dexRow.addView(mBtnPanelRotate, dp3a);

        body.addView(dexRow);

        LinearLayout dexRow2 = new LinearLayout(ctx);
        dexRow2.setOrientation(LinearLayout.HORIZONTAL);
        dexRow2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dr2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dr2Lp.topMargin = dp(ctx, 6);
        dexRow2.setLayoutParams(dr2Lp);

        boolean initSplit = st.splitActive == 1;
        mBtnPanelSplit = makeFooterChip(ctx, initSplit ? "退出分屏" : "一键分屏");
        mBtnPanelSplit.setSingleLine(true);
        mBtnPanelSplit.setGravity(Gravity.CENTER);
        HyperUi.styleHyperChip(mBtnPanelSplit, initSplit);
        HyperUi.setStartIcon(mBtnPanelSplit, HyperUi.ICON_SPLIT,
                initSplit ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        mBtnPanelSplit.setOnClickListener(v -> {
            setPanelOpen(false);
            if (mLastStatus.splitActive == 1) {
                performExitSplitAnimated();
            } else {
                triggerQuickSplitOrMenu();
            }
        });
        LinearLayout.LayoutParams dp3s = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dp3s.rightMargin = dp(ctx, 6);
        dexRow2.addView(mBtnPanelSplit, dp3s);

        mBtnPanelHideDock = makeFooterChip(ctx, mDockAutoHide ? "Dock 自动隐藏" : "Dock 固定显示");
        mBtnPanelHideDock.setSingleLine(true);
        mBtnPanelHideDock.setGravity(Gravity.CENTER);
        HyperUi.styleHyperChip(mBtnPanelHideDock, mDockAutoHide);
        HyperUi.setStartIcon(mBtnPanelHideDock, HyperUi.ICON_PIN,
                mDockAutoHide ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        mBtnPanelHideDock.setOnClickListener(v -> {
            boolean next = !mDockAutoHide;
            setDockAutoHideEnabled(BridgeService.this, next);
            Toast.makeText(BridgeService.this,
                    next ? "已开启 Dock 自动隐藏（鼠标移至底边唤醒）"
                         : "已关闭自动隐藏：Dock 固定显示",
                    Toast.LENGTH_SHORT).show();
        });
        mBtnPanelHideDock.setOnLongClickListener(v -> {
            applyDockHidden(true);
            Toast.makeText(BridgeService.this, "Dock 已暂时收起，触碰底边触发条可唤醒", Toast.LENGTH_SHORT).show();
            return true;
        });
        LinearLayout.LayoutParams dp3b = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dexRow2.addView(mBtnPanelHideDock, dp3b);

        body.addView(dexRow2);

        LinearLayout dexRow3 = new LinearLayout(ctx);
        dexRow3.setOrientation(LinearLayout.HORIZONTAL);
        dexRow3.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dr3Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dr3Lp.topMargin = dp(ctx, 6);
        dexRow3.setLayoutParams(dr3Lp);

        mBtnPanelBlackout = makeFooterChip(ctx, mBlackoutActive ? "主屏已黑屏 · 点此恢复亮屏" : "一键黑屏 (主屏最低亮度)");
        mBtnPanelBlackout.setSingleLine(true);
        mBtnPanelBlackout.setGravity(Gravity.CENTER);
        mBtnPanelBlackout.setOnClickListener(v -> toggleDexBlackout());
        dexRow3.addView(mBtnPanelBlackout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        updateBlackoutUi();
        body.addView(dexRow3);

        mPanelCard.addView(body);

        // Footer Bar (Battery % left, Control Center + Settings right)
        LinearLayout footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 14), dp(ctx, 11));
        GradientDrawable footBg = new GradientDrawable();
        footBg.setColor(0x38000000);
        float r = dp(ctx, 22);
        footBg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        footer.setBackground(footBg);

        mPanelBattView = new MiniBatteryView(ctx);
        LinearLayout.LayoutParams pbvLp = new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 13));
        pbvLp.rightMargin = dp(ctx, 7);
        footer.addView(mPanelBattView, pbvLp);

        mPanelBattText = new TextView(ctx);
        mPanelBattText.setTextColor(0xFFE2E8F0);
        mPanelBattText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.addView(mPanelBattText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnCtrl = makeFooterChip(ctx, "控制台");
        HyperUi.setStartIcon(btnCtrl, HyperUi.ICON_SLIDERS, 0xFFD8DEE9, 13f, 4f);
        btnCtrl.setOnClickListener(v -> openTabletMain());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = dp(ctx, 8);
        footer.addView(btnCtrl, cLp);

        TextView btnSysSet = makeFooterChip(ctx, "设置");
        HyperUi.setStartIcon(btnSysSet, HyperUi.ICON_SETTINGS, 0xFFD8DEE9, 13f, 4f);
        btnSysSet.setOnClickListener(v -> openExtSettings(Settings.ACTION_SETTINGS));
        footer.addView(btnSysSet);

        mPanelCard.addView(footer);
        mPanelRoot.addView(mPanelCard, new FrameLayout.LayoutParams(
                dp(ctx, 338), FrameLayout.LayoutParams.WRAP_CONTENT));
        mPanelRoot.setVisibility(View.GONE);
        mPanelOpen = false;

        mPanelLp = new WindowManager.LayoutParams(
                dp(ctx, 354),
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mPanelLp.gravity = Gravity.TOP | Gravity.LEFT;
        mPanelLp.x = Math.max(16, st.virtW - dp(ctx, 366));
        mPanelLp.y = Math.max(16, st.virtH - dp(ctx, 410));

        mPanelRoot.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE && mPanelOpen) {
                long now = SystemClock.uptimeMillis();
                if (!mTrayPressed && (now - mLastTrayUpMs) > 220L && (now - mLastTrayToggleMs) > 220L) {
                    mHandler.removeCallbacks(mOutsideDismissTask);
                    mHandler.postDelayed(mOutsideDismissTask, 70L);
                }
                return false;
            }
            return false;
        });
        mPanelRoot.addOnLayoutChangeListener((v, l, t, rt, b, ol, ot, or, ob) -> {
            if ((rt - l) != (or - ol) || (b - t) != (ob - ot)) {
                repositionPanel();
            }
        });
    }

    private void buildDockEdgeTriggerOverlay(Context ctx) {
        mDockEdgeTrigger = new FrameLayout(ctx);
        mDockEdgeTrigger.setClickable(true);
        mDockEdgeTrigger.setFocusable(false);

        mDockEdgePill = new View(ctx);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0x88FFFFFF);
        pillBg.setCornerRadius(dp(ctx, 2.5f));
        pillBg.setStroke(1, 0x44000000);
        mDockEdgePill.setBackground(pillBg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                dp(ctx, 108), dp(ctx, 4), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = dp(ctx, 4);
        mDockEdgeTrigger.addView(mDockEdgePill, plp);

        mDockEdgeTrigger.setOnHoverListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_HOVER_ENTER || act == MotionEvent.ACTION_HOVER_MOVE) {
                wakeDockFromEdge();
            }
            return false;
        });
        mDockEdgeTrigger.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                wakeDockFromEdge();
                return true;
            }
            return false;
        });

        mDockEdgeLp = new WindowManager.LayoutParams(
                dp(ctx, 220),
                dp(ctx, 16),
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mDockEdgeLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mDockEdgeLp.x = 0;
        mDockEdgeLp.y = 0;
        mDockEdgeTrigger.setVisibility(View.GONE);
    }

    private void buildSplitOverlays(Context ctx, BridgeClient.Status st) {
        mSplitDividerPortrait = (mOverlayH > mOverlayW);
        mSplitDividerRoot = new FrameLayout(ctx);
        mSplitDividerRoot.setVisibility(View.GONE);

        mSplitDividerPill = new TextView(ctx);
        mSplitDividerPill.setTextColor(0xFFF8FAFC);
        mSplitDividerPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        mSplitDividerPill.getPaint().setFakeBoldText(true);
        mSplitDividerPill.setGravity(Gravity.CENTER);
        mSplitDividerPill.setElevation(dp(ctx, 12));
        GradientDrawable divBg = new GradientDrawable();
        divBg.setColor(0xE00F172A);
        divBg.setCornerRadius(dp(ctx, 12));
        divBg.setStroke(dp(ctx, 1), 0x8838BDF8);
        mSplitDividerPill.setBackground(divBg);
        mSplitDividerRoot.addView(mSplitDividerPill, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        final float[] dragState = new float[5]; // [downRawX, downRawY, startRatio, curRatio, moved]
        final int touchSlop = dp(ctx, 6);
        mSplitDividerRoot.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            boolean port = mOverlayH > mOverlayW;
            if (act == MotionEvent.ACTION_DOWN) {
                mSplitDragging = true;
                dragState[0] = ev.getRawX();
                dragState[1] = ev.getRawY();
                int rPct = mLastStatus != null && mLastStatus.splitRatioPct > 0 ? mLastStatus.splitRatioPct : 50;
                dragState[2] = Math.max(0.25f, Math.min(0.75f, rPct / 100f));
                dragState[3] = dragState[2];
                dragState[4] = 0f;
                return true;
            } else if (act == MotionEvent.ACTION_MOVE) {
                float dx = ev.getRawX() - dragState[0];
                float dy = ev.getRawY() - dragState[1];
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    dragState[4] = 1f;
                }
                int axis = Math.max(600, port ? mOverlayH : mOverlayW);
                float deltaRatio = (port ? dy : dx) / (float) axis;
                float r = Math.max(0.25f, Math.min(0.75f, dragState[2] + deltaRatio));
                dragState[3] = r;
                positionSplitDividerAt(r, true);
                return true;
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                mSplitDragging = false;
                if (dragState[4] < 0.5f && act == MotionEvent.ACTION_UP) {
                    setSplitMenuOpen(!mSplitMenuOpen);
                } else {
                    int pct = Math.max(25, Math.min(75, Math.round(dragState[3] * 100f)));
                    performSplitRatioAnimated(pct, true);
                }
                return true;
            }
            return false;
        });

        mSplitDividerLp = new WindowManager.LayoutParams(
                mSplitDividerPortrait ? dp(ctx, 76) : dp(ctx, 24),
                mSplitDividerPortrait ? dp(ctx, 24) : dp(ctx, 76),
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mSplitDividerLp.gravity = Gravity.TOP | Gravity.LEFT;
        mSplitDividerLp.x = Math.max(0, mOverlayW / 2 - dp(ctx, 12));
        mSplitDividerLp.y = Math.max(0, mOverlayH / 2 - dp(ctx, 38));

        // Compact Split Control Popover Menu
        mSplitMenuRoot = new LinearLayout(ctx);
        mSplitMenuRoot.setOrientation(LinearLayout.VERTICAL);
        mSplitMenuRoot.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        mSplitMenuRoot.setElevation(dp(ctx, 16));
        mSplitMenuRoot.setVisibility(View.GONE);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(HyperUi.FLYOUT_BG);
        menuBg.setCornerRadius(dp(ctx, 20));
        menuBg.setStroke(1, HyperUi.FLYOUT_STROKE);
        mSplitMenuRoot.setBackground(menuBg);

        TextView title = new TextView(ctx);
        title.setText("分屏比例与控制");
        title.setTextColor(0xFFF1F5F9);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        title.getPaint().setFakeBoldText(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        HyperUi.setStartIcon(title, HyperUi.ICON_SPLIT, HyperUi.HYPER_BLUE, 13.5f, 5f);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.bottomMargin = dp(ctx, 8);
        mSplitMenuRoot.addView(title, tLp);

        LinearLayout ratiosRow = new LinearLayout(ctx);
        ratiosRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SPLIT_PRESET_PCTS.length; i++) {
            final int pct = SPLIT_PRESET_PCTS[i];
            String lbl = (pct / 10) + ":" + ((100 - pct) / 10);
            TextView btn = makeFooterChip(ctx, lbl);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            btn.setSingleLine(true);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
            btn.setOnClickListener(v -> {
                setSplitMenuOpen(false);
                performSplitRatioAnimated(pct, false);
            });
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i < SPLIT_PRESET_PCTS.length - 1) bLp.rightMargin = dp(ctx, 5);
            ratiosRow.addView(btn, bLp);
            mSplitRatioBtns[i] = btn;
        }
        mSplitMenuRoot.addView(ratiosRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actRow = new LinearLayout(ctx);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(ctx, 8);

        mSplitSwapBtn = makeFooterChip(ctx, mSplitDividerPortrait ? "上下互换" : "左右互换");
        mSplitSwapBtn.setGravity(Gravity.CENTER);
        HyperUi.setStartIcon(mSplitSwapBtn, HyperUi.ICON_SWAP, 0xFFE2E8F0, 12.5f, 4f);
        mSplitSwapBtn.setOnClickListener(v -> {
            setSplitMenuOpen(false);
            performSplitSwapAnimated();
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        swLp.rightMargin = dp(ctx, 6);
        actRow.addView(mSplitSwapBtn, swLp);

        TextView exitBtn = makeFooterChip(ctx, "退出分屏");
        exitBtn.setGravity(Gravity.CENTER);
        exitBtn.setTextColor(0xFFFCA5A5);
        HyperUi.setStartIcon(exitBtn, HyperUi.ICON_CLOSE, 0xFFFCA5A5, 12.5f, 4f);
        exitBtn.setOnClickListener(v -> {
            setSplitMenuOpen(false);
            performExitSplitAnimated();
        });
        actRow.addView(exitBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mSplitMenuRoot.addView(actRow, aLp);

        mSplitMenuLp = new WindowManager.LayoutParams(
                dp(ctx, 296),
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mSplitMenuLp.gravity = Gravity.TOP | Gravity.LEFT;
        mSplitMenuRoot.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE && mSplitMenuOpen) {
                setSplitMenuOpen(false);
            }
            return false;
        });
    }

    private void cancelAutoHideTimer() {
        mHandler.removeCallbacks(mAutoHideDockTask);
        mAutoHideScheduled = false;
    }

    private void applyDockHidden(boolean hidden) {
        cancelAutoHideTimer();
        mDockHidden = hidden;
        if (hidden) {
            setPanelOpen(false);
            setSplitMenuOpen(false);
            setTaskBubbleOpen(false);
        }
        if (mDockRoot != null) {
            mDockRoot.animate().cancel();
            mDockRoot.setAlpha(1f);
            mDockRoot.setTranslationY(0f);
            mDockRoot.setVisibility(hidden ? View.GONE : View.VISIBLE);
            if (!hidden) repositionDock();
        }
        syncDockEdgeVisibility();
        bringCursorToFront();
    }

    private void applyDockHiddenAnimated(boolean hidden) {
        if (mDockRoot == null || mExtCtx == null) {
            applyDockHidden(hidden);
            return;
        }
        if (hidden == mDockHidden && ((hidden && mDockRoot.getVisibility() != View.VISIBLE)
                || (!hidden && mDockRoot.getVisibility() == View.VISIBLE))) {
            syncDockEdgeVisibility();
            return;
        }
        cancelAutoHideTimer();
        mDockHidden = hidden;
        mDockRoot.animate().cancel();
        if (hidden) {
            setPanelOpen(false);
            setSplitMenuOpen(false);
            setTaskBubbleOpen(false);
            syncDockEdgeVisibility();
            float slideDist = dp(mExtCtx, 74);
            mDockRoot.animate()
                    .translationY(slideDist)
                    .alpha(0f)
                    .setDuration(200L)
                    .setInterpolator(new AccelerateInterpolator(1.3f))
                    .withEndAction(() -> {
                        if (mDockHidden && mDockRoot != null) {
                            mDockRoot.setVisibility(View.GONE);
                            mDockRoot.setTranslationY(0f);
                            mDockRoot.setAlpha(1f);
                        }
                        bringCursorToFront();
                    })
                    .start();
        } else {
            syncDockEdgeVisibility();
            mDockRoot.setVisibility(View.VISIBLE);
            repositionDock();
            mDockRoot.setTranslationY(dp(mExtCtx, 64));
            mDockRoot.setAlpha(0f);
            mDockRoot.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200L)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(this::bringCursorToFront)
                    .start();
        }
    }

    private void syncDockEdgeVisibility() {
        if (mDockEdgeTrigger != null) {
            mDockEdgeTrigger.setVisibility(mDockHidden ? View.VISIBLE : View.GONE);
        }
    }

    private void wakeDockFromEdge() {
        if (mDockHidden) {
            applyDockHiddenAnimated(false);
        }
        evaluateDockAutoHide(true);
    }

    private void evaluateDockAutoHide(boolean forceDelay) {
        if (!mDockAutoHide || mIsOnLauncherHome) {
            mHandler.removeCallbacks(mAutoHideDockTask);
            mAutoHideScheduled = false;
            if (mDockHidden) applyDockHiddenAnimated(false);
            return;
        }
        if (mPanelOpen || mSplitMenuOpen || mTaskBubbleOpen || mDockHovered || mTrayPressed) {
            mHandler.removeCallbacks(mAutoHideDockTask);
            mAutoHideScheduled = false;
            return;
        }
        if (mDockHidden && !forceDelay) return;
        if (!forceDelay && mAutoHideScheduled) return;
        mHandler.removeCallbacks(mAutoHideDockTask);
        mAutoHideScheduled = true;
        mHandler.postDelayed(mAutoHideDockTask, DOCK_AUTO_HIDE_DELAY_MS);
    }

    private void switchPanelMode(int mode) {
        setPanelOpen(false);
        Toast.makeText(BridgeService.this,
                "正在切换为【" + BridgeClient.mirrorModeLabel(mode) + "】...",
                Toast.LENGTH_SHORT).show();
        safeBgExecute(() -> {
            BridgeClient.setMirrorMode(mode);
            safePost(this::pollRemoteStateAsync);
        });
    }

    private void updatePanelModeChips(int mirrorMode) {
        boolean isDex = mirrorMode == BridgeClient.MIRROR_MODE_DEX;
        boolean isMir = mirrorMode == BridgeClient.MIRROR_MODE_MIRROR;
        if (mBtnPanelDex != null) {
            HyperUi.styleHyperChip(mBtnPanelDex, isDex);
            HyperUi.setStartIcon(mBtnPanelDex, HyperUi.ICON_DEX, isDex ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        }
        if (mBtnPanelMirror != null) {
            HyperUi.styleHyperChip(mBtnPanelMirror, isMir);
            HyperUi.setStartIcon(mBtnPanelMirror, HyperUi.ICON_MIRROR, isMir ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        }
    }

    private void updateDockAutoHideUi() {
        if (mBtnPanelHideDock != null) {
            mBtnPanelHideDock.setText(mDockAutoHide ? "Dock 自动隐藏" : "Dock 固定显示");
            HyperUi.styleHyperChip(mBtnPanelHideDock, mDockAutoHide);
            HyperUi.setStartIcon(mBtnPanelHideDock, HyperUi.ICON_PIN,
                    mDockAutoHide ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        }
    }

    private void triggerQuickSplitOrMenu() {
        setPanelOpen(false);
        if (mLastStatus != null && mLastStatus.splitActive == 1) {
            setSplitMenuOpen(!mSplitMenuOpen);
            return;
        }
        performEnterOrAssignSplitAnimated(BridgeClient.SPLIT_TOGGLE_QUICK, -1, 0, null, null);
    }

    private void setSplitMenuOpen(boolean open) {
        if (open && (mLastStatus == null || mLastStatus.splitActive == 0)) open = false;
        if (mSplitMenuOpen == open && (mSplitMenuRoot == null || (open && mSplitMenuRoot.getVisibility() == View.VISIBLE))) {
            return;
        }
        mSplitMenuOpen = open;
        if (open) setTaskBubbleOpen(false);
        if (mSplitMenuRoot != null && mExtCtx != null) {
            mSplitMenuRoot.animate().cancel();
            if (open) {
                mSplitMenuRoot.setVisibility(View.VISIBLE);
                repositionSplitMenu();
                mHandler.removeCallbacks(mAutoHideDockTask);
                mSplitMenuRoot.setAlpha(0f);
                mSplitMenuRoot.setTranslationY(dp(mExtCtx, 10));
                mSplitMenuRoot.setScaleX(0.92f);
                mSplitMenuRoot.setScaleY(0.92f);
                mSplitMenuRoot.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .withEndAction(this::bringCursorToFront)
                        .start();
            } else {
                evaluateDockAutoHide(false);
                if (mSplitMenuRoot.getVisibility() == View.VISIBLE) {
                    mSplitMenuRoot.animate()
                            .alpha(0f)
                            .translationY(dp(mExtCtx, 8))
                            .scaleX(0.94f)
                            .scaleY(0.94f)
                            .setDuration(135L)
                            .setInterpolator(new AccelerateInterpolator(1.3f))
                            .withEndAction(() -> {
                                if (!mSplitMenuOpen && mSplitMenuRoot != null) {
                                    mSplitMenuRoot.setVisibility(View.GONE);
                                    mSplitMenuRoot.setAlpha(1f);
                                    mSplitMenuRoot.setTranslationY(0f);
                                    mSplitMenuRoot.setScaleX(1f);
                                    mSplitMenuRoot.setScaleY(1f);
                                }
                            })
                            .start();
                } else {
                    mSplitMenuRoot.setVisibility(View.GONE);
                }
            }
        }
        bringCursorToFront();
    }

    private void updateSplitUi(BridgeClient.Status st) {
        boolean active = st != null && st.connected && st.vdId > 0 && st.mirrorMode != 1 && st.splitActive == 1;
        syncOverlayHostSurface(false);
        if (mBtnDockSplit != null) {
            mBtnDockSplit.setText("分屏");
            HyperUi.setStartIcon(mBtnDockSplit, HyperUi.ICON_SPLIT, Color.WHITE, 12.5f, 4f);
            GradientDrawable bg = (GradientDrawable) mBtnDockSplit.getBackground();
            bg.setColor(active ? HyperUi.HYPER_BLUE : 0x22FFFFFF);
        }
        if (mBtnPanelSplit != null) {
            mBtnPanelSplit.setText(active ? "退出分屏" : "一键分屏");
            HyperUi.styleHyperChip(mBtnPanelSplit, active);
            HyperUi.setStartIcon(mBtnPanelSplit, HyperUi.ICON_SPLIT,
                    active ? Color.WHITE : 0xFFD8DEE9, 13f, 5f);
        }
        if (mSplitSwapBtn != null) {
            mSplitSwapBtn.setText(mOverlayH > mOverlayW ? "上下互换" : "左右互换");
            HyperUi.setStartIcon(mSplitSwapBtn, HyperUi.ICON_SWAP, 0xFFE2E8F0, 12.5f, 4f);
        }
        int curPct = (st != null && st.splitRatioPct > 0) ? st.splitRatioPct : 50;
        for (int i = 0; i < SPLIT_PRESET_PCTS.length; i++) {
            TextView tv = mSplitRatioBtns[i];
            if (tv == null) continue;
            boolean sel = Math.abs(curPct - SPLIT_PRESET_PCTS[i]) <= 3;
            HyperUi.styleHyperChip(tv, sel);
        }

        boolean singleEmpty = st != null
                && ((st.splitSlot0Pkg == null || st.splitSlot0Pkg.isEmpty())
                        != (st.splitSlot1Pkg == null || st.splitSlot1Pkg.isEmpty()));
        boolean hasTwoOrSlot = active && (!mIsOnLauncherHome || singleEmpty);
        if (mSplitDividerRoot != null) {
            if (hasTwoOrSlot) {
                float targetRatio = Math.max(25, Math.min(75, curPct)) / 100f;
                boolean wasHidden = mSplitDividerRoot.getVisibility() != View.VISIBLE;
                if (wasHidden) {
                    mSplitDividerRoot.animate().cancel();
                    mSplitDividerRoot.setVisibility(View.VISIBLE);
                    positionSplitDividerAt(targetRatio, false);
                    mSplitDividerRoot.setAlpha(0f);
                    mSplitDividerRoot.setScaleX(0.55f);
                    mSplitDividerRoot.setScaleY(0.55f);
                    mSplitDividerRoot.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(230L)
                            .setInterpolator(new OvershootInterpolator(1.5f))
                            .start();
                } else if (!mSplitDragging && (mSplitDividerAnimator == null || !mSplitDividerAnimator.isRunning())) {
                    positionSplitDividerAt(targetRatio, false);
                }
            } else {
                if (mSplitDividerRoot.getVisibility() == View.VISIBLE) {
                    mSplitDividerRoot.animate().cancel();
                    mSplitDividerRoot.setVisibility(View.GONE);
                    mSplitDividerRoot.setAlpha(1f);
                    mSplitDividerRoot.setScaleX(1f);
                    mSplitDividerRoot.setScaleY(1f);
                }
                if (mSplitMenuOpen) setSplitMenuOpen(false);
            }
        }
        bringCursorToFront();
    }

    private void repositionSplitDivider() {
        int pct = (mLastStatus != null && mLastStatus.splitRatioPct > 0) ? mLastStatus.splitRatioPct : 50;
        positionSplitDividerAt(Math.max(25, Math.min(75, pct)) / 100f, mSplitDragging);
    }

    private void positionSplitDividerAt(float ratio, boolean dragging) {
        if (mSplitDividerRoot == null || mSplitDividerLp == null || mExtCtx == null) return;
        int surfW = Math.max(600, mOverlayW);
        int surfH = Math.max(600, mOverlayH);
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0 && sLauncherHost.getHeight() > 0) {
            surfW = sLauncherHost.getWidth();
            surfH = sLauncherHost.getHeight();
        }
        boolean port = surfH > surfW;
        int w = port ? dp(mExtCtx, 80) : dp(mExtCtx, 26);
        int h = port ? dp(mExtCtx, 26) : dp(mExtCtx, 80);
        float r = Math.max(0.25f, Math.min(0.75f, ratio));
        mDisplayedSplitRatio = r;
        int pct = Math.round(r * 100f);

        int x = port ? (surfW - w) / 2 : Math.max(0, Math.min(surfW - w, Math.round(surfW * r) - w / 2));
        int y = port ? Math.max(0, Math.min(surfH - h, Math.round(surfH * r) - h / 2)) : (surfH - h) / 2;

        if (mSplitDividerPill != null) {
            mSplitDividerPill.setText(port ? ("⋯ " + pct + "%") : ("⋮\n" + pct));
            Drawable bg = mSplitDividerPill.getBackground();
            if (bg instanceof GradientDrawable) {
                ((GradientDrawable) bg).setStroke(dp(mExtCtx, dragging ? 2 : 1), dragging ? 0xFF38BDF8 : 0x8838BDF8);
                ((GradientDrawable) bg).setColor(dragging ? 0xF50284C7 : 0xE00F172A);
            }
        }

        mSplitDividerLp.width = w;
        mSplitDividerLp.height = h;
        mSplitDividerLp.x = x;
        mSplitDividerLp.y = y;
        mSplitDividerPortrait = port;

        if (mInLauncherHost) {
            ViewGroup.LayoutParams raw = mSplitDividerRoot.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                flp.gravity = Gravity.TOP | Gravity.LEFT;
                flp.width = w;
                flp.height = h;
                flp.leftMargin = x;
                flp.topMargin = y;
                mSplitDividerRoot.setLayoutParams(flp);
            }
        } else if (mExtWm != null) {
            try { mExtWm.updateViewLayout(mSplitDividerRoot, mSplitDividerLp); } catch (Throwable ignored) {}
        }
    }

    private void repositionSplitMenu() {
        if (mSplitMenuRoot == null || mSplitMenuLp == null || mExtCtx == null) return;
        int surfW = Math.max(600, mOverlayW);
        int surfH = Math.max(600, mOverlayH);
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0 && sLauncherHost.getHeight() > 0) {
            surfW = sLauncherHost.getWidth();
            surfH = sLauncherHost.getHeight();
        }
        int fixedMenuW = dp(mExtCtx, 296);
        mSplitMenuRoot.measure(
                View.MeasureSpec.makeMeasureSpec(fixedMenuW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(surfH, View.MeasureSpec.AT_MOST));
        int mw = fixedMenuW;
        int mh = Math.max(dp(mExtCtx, 96), mSplitMenuRoot.getMeasuredHeight());
        int pct = (mLastStatus != null && mLastStatus.splitRatioPct > 0) ? mLastStatus.splitRatioPct : 50;
        float r = Math.max(0.25f, Math.min(0.75f, pct / 100f));
        boolean port = surfH > surfW;
        int x = port ? (surfW - mw) / 2 : Math.max(dp(mExtCtx, 12), Math.min(surfW - mw - dp(mExtCtx, 12), Math.round(surfW * r) - mw / 2));
        int y = port ? Math.max(dp(mExtCtx, 12), Math.min(surfH - mh - dp(mExtCtx, 80), Math.round(surfH * r) - mh - dp(mExtCtx, 22)))
                     : Math.max(dp(mExtCtx, 12), (surfH - mh) / 2 - dp(mExtCtx, 90));
        mSplitMenuLp.x = x;
        mSplitMenuLp.y = y;
        if (mInLauncherHost) {
            ViewGroup.LayoutParams raw = mSplitMenuRoot.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                flp.gravity = Gravity.TOP | Gravity.LEFT;
                flp.leftMargin = x;
                flp.topMargin = y;
                mSplitMenuRoot.setLayoutParams(flp);
            }
        } else if (mExtWm != null) {
            try { mExtWm.updateViewLayout(mSplitMenuRoot, mSplitMenuLp); } catch (Throwable ignored) {}
        }
    }

    private void bringCursorToFront() {
        if (mCursorView == null) return;
        try {
            if (mInLauncherHost) {
                mCursorView.bringToFront();
            } else if (mExtWm != null && mCursorView.getParent() != null) {
                mCursorView.setElevation(dp(mExtCtx != null ? mExtCtx : this, 48));
            }
        } catch (Throwable ignored) {}
    }

    private void buildCursorOverlay(Context ctx, BridgeClient.Status st) {
        mCursorView = new CursorView(ctx);
        mCursorView.setElevation(dp(ctx, 48));
        mCursorView.setTranslationZ(dp(ctx, 48));
        mCursorLp = new WindowManager.LayoutParams(
                44, 44,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mCursorLp.gravity = Gravity.TOP | Gravity.LEFT;
        mCursorLp.x = Math.round(st.cursorX) - 6;
        mCursorLp.y = Math.round(st.cursorY) - 4;
        mCursorView.setVisibility(View.GONE);
    }

    private void addGridTile(LinearLayout row, View tileRoot, boolean marginRight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (marginRight && mExtCtx != null) lp.rightMargin = dp(mExtCtx, 10);
        row.addView(tileRoot, lp);
    }

    private TextView makeFooterChip(Context ctx, String txt) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(0xFFD8DEE9);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6));
        HyperUi.styleHyperChip(tv, false);
        return tv;
    }

    private void setPanelOpen(boolean open) {
        mHandler.removeCallbacks(mOutsideDismissTask);
        if (mPanelOpen == open && (mPanelRoot == null || (open && mPanelRoot.getVisibility() == View.VISIBLE))) {
            return;
        }
        mPanelOpen = open;
        updateTrayPillBackground(open);
        if (open) {
            setSplitMenuOpen(false);
            setTaskBubbleOpen(false);
            cancelAutoHideTimer();
            if (mDockHidden) applyDockHiddenAnimated(false);
        } else {
            evaluateDockAutoHide(false);
        }
        if (mPanelRoot == null || mPanelCard == null || mExtCtx == null) return;

        mPanelCard.animate().cancel();
        if (open) {
            mPanelRoot.setVisibility(View.VISIBLE);
            repositionPanel();
            refreshLocalHardwareViews();
            pollRemoteStateAsync();
            mPanelCard.setAlpha(0f);
            mPanelCard.setTranslationY(dp(mExtCtx, 18));
            mPanelCard.setScaleX(0.94f);
            mPanelCard.setScaleY(0.94f);
            mPanelCard.post(() -> {
                if (!mPanelOpen || mPanelCard == null) return;
                int w = mPanelCard.getWidth();
                int h = mPanelCard.getHeight();
                if (w > 0 && h > 0) {
                    mPanelCard.setPivotX(w * 0.85f);
                    mPanelCard.setPivotY(h);
                }
                mPanelCard.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200L)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .withEndAction(this::bringCursorToFront)
                        .start();
            });
        } else {
            int w = mPanelCard.getWidth();
            int h = mPanelCard.getHeight();
            if (w > 0 && h > 0) {
                mPanelCard.setPivotX(w * 0.85f);
                mPanelCard.setPivotY(h);
            }
            mPanelCard.animate()
                    .alpha(0f)
                    .translationY(dp(mExtCtx, 14))
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(150L)
                    .setInterpolator(new AccelerateInterpolator(1.3f))
                    .withEndAction(() -> {
                        if (!mPanelOpen && mPanelRoot != null) {
                            mPanelRoot.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void buildTaskBubbleOverlay(Context ctx) {
        mTaskBubbleRoot = new LinearLayout(ctx);
        mTaskBubbleRoot.setOrientation(LinearLayout.VERTICAL);
        mTaskBubbleRoot.setGravity(Gravity.LEFT);
        mTaskBubbleRoot.setClipChildren(false);
        mTaskBubbleRoot.setClipToPadding(false);
        mTaskBubbleRoot.setVisibility(View.GONE);

        mTaskBubbleCard = new LinearLayout(ctx);
        mTaskBubbleCard.setOrientation(LinearLayout.VERTICAL);
        mTaskBubbleCard.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        mTaskBubbleCard.setElevation(dp(ctx, 18));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xF20F172A);
        cardBg.setCornerRadius(dp(ctx, 16));
        cardBg.setStroke(1, 0x55FFFFFF);
        mTaskBubbleCard.setBackground(cardBg);

        // Header: icon + app title + current state chip
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(ctx, 6), dp(ctx, 3), dp(ctx, 6), dp(ctx, 7));

        mBubbleIcon = new ImageView(ctx);
        mBubbleIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        icLp.rightMargin = dp(ctx, 8);
        header.addView(mBubbleIcon, icLp);

        mBubbleTitle = new TextView(ctx);
        mBubbleTitle.setTextColor(0xFFF8FAFC);
        mBubbleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        mBubbleTitle.getPaint().setFakeBoldText(true);
        mBubbleTitle.setSingleLine(true);
        mBubbleTitle.setEllipsize(TextUtils.TruncateAt.END);
        mBubbleTitle.setMaxWidth(dp(ctx, 130));
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ttLp.rightMargin = dp(ctx, 6);
        header.addView(mBubbleTitle, ttLp);

        mBubbleModeTag = new TextView(ctx);
        mBubbleModeTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        mBubbleModeTag.setTextColor(0xFFBAE6FD);
        mBubbleModeTag.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(0x440284C7);
        tagBg.setCornerRadius(dp(ctx, 8));
        mBubbleModeTag.setBackground(tagBg);
        header.addView(mBubbleModeTag);
        mTaskBubbleCard.addView(header);

        View sep = new View(ctx);
        sep.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        sepLp.bottomMargin = dp(ctx, 5);
        mTaskBubbleCard.addView(sep, sepLp);

        // Action 1: Exit accidental Freeform small window back to fullscreen (enabling Freeform is disabled in DeX)
        mBubbleFreeformBtn = makeBubbleRowBtn(ctx, "退出小窗（恢复全屏）", 0xFFE2E8F0, HyperUi.CHIP_ACTIVE_BG);
        HyperUi.setStartIcon(mBubbleFreeformBtn, HyperUi.ICON_WINDOW, 0xFFE2E8F0, 13f, 5f);
        mBubbleFreeformBtn.setVisibility(View.GONE);
        mBubbleFreeformBtn.setOnClickListener(v -> {
            BridgeClient.TaskEntry target = mBubbleTask;
            String targetLbl = mBubbleLabel;
            setTaskBubbleOpen(false);
            if (target == null || !target.isFreeform()) return;
            safeBgExecute(() -> {
                int res = BridgeClient.toggleTaskFreeform(target.taskId, target.pkg, 0);
                safePost(() -> {
                    Toast.makeText(BridgeService.this,
                            res == 0 ? ("已恢复全屏：" + targetLbl) : ("恢复全屏失败：" + targetLbl),
                            Toast.LENGTH_SHORT).show();
                    pollRemoteStateAsync();
                });
            });
        });
        mTaskBubbleCard.addView(mBubbleFreeformBtn);

        // Action 2: Quick split left/top or right/bottom
        LinearLayout splitRow = new LinearLayout(ctx);
        splitRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(ctx, 4);

        mBubbleSplit0Btn = makeBubbleRowBtn(ctx, "放入左屏", 0xFFBAE6FD, HyperUi.CHIP_ACTIVE_BG);
        mBubbleSplit0Btn.setGravity(Gravity.CENTER);
        mBubbleSplit0Btn.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        HyperUi.setStartIcon(mBubbleSplit0Btn, HyperUi.ICON_SPLIT, 0xFFBAE6FD, 12.5f, 4f);
        mBubbleSplit0Btn.setOnClickListener(v -> {
            BridgeClient.TaskEntry target = mBubbleTask;
            setTaskBubbleOpen(false);
            if (target == null) return;
            performEnterOrAssignSplitAnimated(BridgeClient.SPLIT_ASSIGN_LEFT_TOP, 0, target.taskId, target.pkg, null);
        });
        LinearLayout.LayoutParams s0Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        s0Lp.rightMargin = dp(ctx, 5);
        splitRow.addView(mBubbleSplit0Btn, s0Lp);

        mBubbleSplit1Btn = makeBubbleRowBtn(ctx, "放入右屏", 0xFFBAE6FD, HyperUi.CHIP_ACTIVE_BG);
        mBubbleSplit1Btn.setGravity(Gravity.CENTER);
        mBubbleSplit1Btn.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        HyperUi.setStartIcon(mBubbleSplit1Btn, HyperUi.ICON_SPLIT, 0xFFBAE6FD, 12.5f, 4f);
        mBubbleSplit1Btn.setOnClickListener(v -> {
            BridgeClient.TaskEntry target = mBubbleTask;
            setTaskBubbleOpen(false);
            if (target == null) return;
            performEnterOrAssignSplitAnimated(BridgeClient.SPLIT_ASSIGN_RIGHT_BOTTOM, 1, target.taskId, target.pkg, null);
        });
        splitRow.addView(mBubbleSplit1Btn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mTaskBubbleCard.addView(splitRow, srLp);

        // Action 3: Close app
        mBubbleCloseBtn = makeBubbleRowBtn(ctx, "关闭应用", 0xFFFCA5A5, 0x36EF4444);
        HyperUi.setStartIcon(mBubbleCloseBtn, HyperUi.ICON_CLOSE, 0xFFFCA5A5, 12.5f, 5f);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clLp.topMargin = dp(ctx, 4);
        mBubbleCloseBtn.setOnClickListener(v -> {
            BridgeClient.TaskEntry target = mBubbleTask;
            String targetLbl = mBubbleLabel;
            setTaskBubbleOpen(false);
            if (target == null) return;
            safeBgExecute(() -> {
                boolean ok = target.taskId > 0 && BridgeClient.removeTask(target.taskId);
                safePost(() -> {
                    Toast.makeText(BridgeService.this,
                            ok ? ("已关闭：" + targetLbl) : ("暂无运行任务：" + targetLbl),
                            Toast.LENGTH_SHORT).show();
                    pollRemoteStateAsync();
                });
            });
        });
        mTaskBubbleCard.addView(mBubbleCloseBtn, clLp);

        mTaskBubbleRoot.addView(mTaskBubbleCard, new LinearLayout.LayoutParams(
                dp(ctx, 214), LinearLayout.LayoutParams.WRAP_CONTENT));

        // Downward pointer triangle like Android launcher shortcut bubble
        mBubbleTriangle = new View(ctx) {
            private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path mPath = new Path();
            {
                mFill.setColor(0xF20F172A);
                mFill.setStyle(Paint.Style.FILL);
                mStroke.setColor(0x55FFFFFF);
                mStroke.setStyle(Paint.Style.STROKE);
                mStroke.setStrokeWidth(1.5f);
            }
            @Override
            protected void onDraw(Canvas canvas) {
                float w = getWidth();
                float h = getHeight();
                mPath.reset();
                mPath.moveTo(0f, 0f);
                mPath.lineTo(w, 0f);
                mPath.lineTo(w * 0.5f, h);
                mPath.close();
                canvas.drawPath(mPath, mFill);
                canvas.drawLine(0f, 0f, w * 0.5f, h, mStroke);
                canvas.drawLine(w, 0f, w * 0.5f, h, mStroke);
            }
        };
        mTaskBubbleRoot.addView(mBubbleTriangle, new LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 9)));

        mTaskBubbleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                mWinType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mTaskBubbleLp.gravity = Gravity.TOP | Gravity.LEFT;
        mTaskBubbleRoot.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE && mTaskBubbleOpen) {
                setTaskBubbleOpen(false);
            }
            return false;
        });
    }

    private TextView makeBubbleRowBtn(Context ctx, String text, int textColor, int hoverColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setSingleLine(true);
        tv.setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7));

        GradientDrawable hi = new GradientDrawable();
        hi.setColor(hoverColor);
        hi.setCornerRadius(dp(ctx, 10));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x16FFFFFF);
        normal.setCornerRadius(dp(ctx, 10));
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, hi);
        sld.addState(new int[]{android.R.attr.state_hovered}, hi);
        sld.addState(new int[]{}, normal);
        tv.setBackground(sld);
        return tv;
    }

    private void showTaskBubble(View anchor, BridgeClient.TaskEntry t, String label, Drawable icon) {
        if (mTaskBubbleRoot == null || mExtCtx == null || t == null) return;
        mLastBubbleOpenMs = SystemClock.uptimeMillis();
        mBubbleTask = t;
        mBubbleLabel = (label != null && !label.isEmpty()) ? label : t.pkg;
        setPanelOpen(false);
        setSplitMenuOpen(false);

        if (mBubbleIcon != null) mBubbleIcon.setImageDrawable(cloneDrawable(icon));
        if (mBubbleTitle != null) mBubbleTitle.setText(mBubbleLabel);
        if (mBubbleModeTag != null) {
            mBubbleModeTag.setText(t.isFreeform() ? "小窗" : (t.isInSplit() ? "分屏" : (t.isRunning() ? "运行中" : "后台")));
        }
        if (mBubbleFreeformBtn != null) {
            mBubbleFreeformBtn.setVisibility(t.isFreeform() ? View.VISIBLE : View.GONE);
            mBubbleFreeformBtn.setText("退出小窗（恢复全屏）");
        }
        boolean port = mOverlayH > mOverlayW;
        if (mBubbleSplit0Btn != null) mBubbleSplit0Btn.setText(port ? "放入上屏" : "放入左屏");
        if (mBubbleSplit1Btn != null) mBubbleSplit1Btn.setText(port ? "放入下屏" : "放入右屏");
        if (mBubbleCloseBtn != null) {
            mBubbleCloseBtn.setEnabled(t.taskId > 0);
            mBubbleCloseBtn.setAlpha(t.taskId > 0 ? 1f : 0.45f);
        }

        // Compute anchor horizontal center on virtual display
        int surfW = Math.max(600, mOverlayW);
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0) {
            surfW = sLauncherHost.getWidth();
        }
        int anchorCx = surfW / 2;
        if (anchor != null && mDockRoot != null && mDockRoot.getWidth() > 0) {
            int[] dockLoc = new int[2];
            int[] slotLoc = new int[2];
            mDockRoot.getLocationOnScreen(dockLoc);
            anchor.getLocationOnScreen(slotLoc);
            int relX = slotLoc[0] - dockLoc[0];
            int dockLeft = mInLauncherHost ? mDockRoot.getLeft() : (surfW - mDockRoot.getWidth()) / 2;
            anchorCx = dockLeft + relX + anchor.getWidth() / 2;
        }
        mBubbleAnchorCenterX = anchorCx;
        setTaskBubbleOpen(true);
    }

    private void setTaskBubbleOpen(boolean open) {
        if (open && mBubbleTask == null) open = false;
        if (mTaskBubbleOpen == open && (mTaskBubbleRoot == null || (open && mTaskBubbleRoot.getVisibility() == View.VISIBLE))) {
            return;
        }
        mTaskBubbleOpen = open;
        if (mTaskBubbleRoot != null) {
            if (open) {
                mTaskBubbleRoot.setVisibility(View.VISIBLE);
                repositionTaskBubble();
                mHandler.removeCallbacks(mAutoHideDockTask);
                if (mTaskBubbleCard != null) {
                    mTaskBubbleCard.animate().cancel();
                    mTaskBubbleCard.setAlpha(0f);
                    mTaskBubbleCard.setTranslationY(dp(mExtCtx, 10));
                    mTaskBubbleCard.setScaleX(0.92f);
                    mTaskBubbleCard.setScaleY(0.92f);
                    mTaskBubbleCard.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(155L)
                            .setInterpolator(new DecelerateInterpolator(1.5f))
                            .withEndAction(this::bringCursorToFront)
                            .start();
                }
            } else {
                evaluateDockAutoHide(false);
                if (mTaskBubbleCard != null && mTaskBubbleRoot.getVisibility() == View.VISIBLE && mExtCtx != null) {
                    mTaskBubbleCard.animate().cancel();
                    mTaskBubbleCard.animate()
                            .alpha(0f)
                            .translationY(dp(mExtCtx, 8))
                            .scaleX(0.93f)
                            .scaleY(0.93f)
                            .setDuration(125L)
                            .setInterpolator(new AccelerateInterpolator(1.3f))
                            .withEndAction(() -> {
                                if (!mTaskBubbleOpen && mTaskBubbleRoot != null) {
                                    mTaskBubbleRoot.setVisibility(View.GONE);
                                    if (mTaskBubbleCard != null) {
                                        mTaskBubbleCard.setAlpha(1f);
                                        mTaskBubbleCard.setTranslationY(0f);
                                        mTaskBubbleCard.setScaleX(1f);
                                        mTaskBubbleCard.setScaleY(1f);
                                    }
                                }
                            })
                            .start();
                } else {
                    mTaskBubbleRoot.setVisibility(View.GONE);
                }
            }
        }
        bringCursorToFront();
    }

    private void repositionTaskBubble() {
        if (mTaskBubbleRoot == null || mTaskBubbleLp == null || mExtCtx == null) return;
        int surfW = Math.max(600, mOverlayW);
        int surfH = Math.max(600, mOverlayH);
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0 && sLauncherHost.getHeight() > 0) {
            surfW = sLauncherHost.getWidth();
            surfH = sLauncherHost.getHeight();
        }
        mTaskBubbleRoot.measure(
                View.MeasureSpec.makeMeasureSpec(surfW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(surfH, View.MeasureSpec.AT_MOST));
        int bw = Math.max(dp(mExtCtx, 214), mTaskBubbleRoot.getMeasuredWidth());
        int bh = Math.max(dp(mExtCtx, 140), mTaskBubbleRoot.getMeasuredHeight());

        int dockH = (mDockRoot != null && mDockRoot.getHeight() > 0) ? mDockRoot.getHeight() : dp(mExtCtx, 46);
        int anchorTop = (mInLauncherHost && mDockRoot != null && mDockRoot.getHeight() > 0)
                ? mDockRoot.getTop()
                : (surfH - dp(mExtCtx, 10) - dockH);

        int margin = dp(mExtCtx, 12);
        int x = Math.max(margin, Math.min(surfW - bw - margin, mBubbleAnchorCenterX - bw / 2));
        int y = Math.max(margin, anchorTop - bh - dp(mExtCtx, 4));

        if (mBubbleTriangle != null) {
            int triW = dp(mExtCtx, 18);
            int triX = Math.max(dp(mExtCtx, 16), Math.min(bw - triW - dp(mExtCtx, 16), (mBubbleAnchorCenterX - x) - triW / 2));
            mBubbleTriangle.setTranslationX(triX);
        }

        mTaskBubbleLp.x = x;
        mTaskBubbleLp.y = y;
        if (mInLauncherHost) {
            ViewGroup.LayoutParams raw = mTaskBubbleRoot.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                flp.gravity = Gravity.TOP | Gravity.LEFT;
                flp.leftMargin = x;
                flp.topMargin = y;
                mTaskBubbleRoot.setLayoutParams(flp);
            }
        } else if (mExtWm != null) {
            try { mExtWm.updateViewLayout(mTaskBubbleRoot, mTaskBubbleLp); } catch (Throwable ignored) {}
        }
    }

    private void updateTrayPillBackground(boolean active) {
        if (mTrayBtn == null || mExtCtx == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0x45A6D8E8 : 0x1EFFFFFF);
        bg.setCornerRadius(dp(mExtCtx, 14));
        if (active) bg.setStroke(1, 0x80A6D8E8);
        mTrayBtn.setBackground(bg);
    }

    private void repositionDock() {
        if (mDockRoot == null || mDockLp == null || mExtCtx == null) return;
        try {
            int surfW = mOverlayW;
            if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0) {
                surfW = sLauncherHost.getWidth();
            }
            int dw = mDockRoot.getWidth();
            int maxW = Math.max(200, surfW - dp(mExtCtx, 20));
            int targetW = dw > maxW ? maxW : WindowManager.LayoutParams.WRAP_CONTENT;
            int bottomMargin = dp(mExtCtx, 10);
            mDockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mDockLp.width = targetW;
            mDockLp.x = 0;
            mDockLp.y = bottomMargin;
            if (mInLauncherHost) {
                ViewGroup.LayoutParams raw = mDockRoot.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                    int wantGravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    if (flp.width != targetW || flp.gravity != wantGravity
                            || flp.leftMargin != 0 || flp.topMargin != 0 || flp.bottomMargin != bottomMargin) {
                        flp.gravity = wantGravity;
                        flp.width = targetW;
                        flp.leftMargin = 0;
                        flp.topMargin = 0;
                        flp.bottomMargin = bottomMargin;
                        mDockRoot.setLayoutParams(flp);
                    }
                }
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mDockRoot, mDockLp);
            }
        } catch (Throwable ignored) {}
    }

    private void repositionPanel() {
        if (mPanelRoot == null || mPanelLp == null || mExtCtx == null) return;
        try {
            int surfW = mOverlayW;
            int surfH = mOverlayH;
            if (mInLauncherHost && sLauncherHost != null) {
                if (sLauncherHost.getWidth() > 0) surfW = sLauncherHost.getWidth();
                if (sLauncherHost.getHeight() > 0) surfH = sLauncherHost.getHeight();
            }
            int pw = mPanelRoot.getWidth();
            int ph = mPanelRoot.getHeight();
            if (pw <= 0) pw = dp(mExtCtx, 338);
            if (ph <= 0) ph = dp(mExtCtx, 320);

            int dockW = (mDockRoot != null && mDockRoot.getWidth() > 0) ? mDockRoot.getWidth() : dp(mExtCtx, 520);
            int dockH = (mDockRoot != null && mDockRoot.getHeight() > 0) ? mDockRoot.getHeight() : dp(mExtCtx, 46);
            int dockBottomMargin = dp(mExtCtx, 10);
            int anchorRight = (mInLauncherHost && mDockRoot != null && mDockRoot.getWidth() > 0)
                    ? mDockRoot.getRight()
                    : ((surfW + dockW) / 2);
            int anchorTop = (mInLauncherHost && mDockRoot != null && mDockRoot.getHeight() > 0)
                    ? mDockRoot.getTop()
                    : (surfH - dockBottomMargin - dockH);

            int x = Math.min(surfW - pw - dp(mExtCtx, 14), Math.max(dp(mExtCtx, 14), anchorRight - pw));
            int y = Math.max(dp(mExtCtx, 14), anchorTop - ph - dp(mExtCtx, 10));
            mPanelLp.x = x;
            mPanelLp.y = y;
            if (mInLauncherHost) {
                ViewGroup.LayoutParams raw = mPanelRoot.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) raw;
                    if (flp.leftMargin != x || flp.topMargin != y) {
                        flp.gravity = Gravity.TOP | Gravity.LEFT;
                        flp.leftMargin = x;
                        flp.topMargin = y;
                        mPanelRoot.setLayoutParams(flp);
                    }
                }
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mPanelRoot, mPanelLp);
            }
        } catch (Throwable ignored) {}
    }

    private void updateInputToggleUi(int inputToExt) {
        if (mBtnToggle != null) {
            mBtnToggle.setText(inputToExt == 1 ? "外接" : "本机");
            HyperUi.setStartIcon(mBtnToggle, HyperUi.ICON_SWAP, Color.WHITE, 12.5f, 4f);
            GradientDrawable gd = (GradientDrawable) mBtnToggle.getBackground();
            gd.setColor(inputToExt == 1 ? HyperUi.HYPER_BLUE : 0xCC3B4252);
        }
        if (mTileInput != null) {
            mTileInput.setActive(inputToExt == 1, inputToExt == 1 ? "外接扩展屏" : "本机内屏");
        }
    }

    private void updateTaskStrip(List<BridgeClient.TaskEntry> tasks) {
        if (mTaskStrip == null || mExtCtx == null) return;
        int surfW = mOverlayW;
        if (mInLauncherHost && sLauncherHost != null && sLauncherHost.getWidth() > 0) {
            surfW = sLauncherHost.getWidth();
        }
        int fixedW = dp(mExtCtx, 390);
        int slotW = Math.max(1, dp(mExtCtx, 42));
        int availW = Math.max(0, surfW - dp(mExtCtx, 28) - fixedW);
        int maxShown = Math.max(2, Math.min(9, availW / slotW));
        int limit = Math.min(maxShown, tasks.size());

        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            BridgeClient.TaskEntry t = tasks.get(i);
            sig.append(t.taskId).append(':').append(t.pkg).append(':').append(t.flags).append(';');
        }
        String s = sig.toString();
        if (s.equals(mLastTaskSig)) return;
        mLastTaskSig = s;

        mTaskStrip.removeAllViews();
        if (mTaskDivider != null) {
            mTaskDivider.setVisibility(limit > 0 ? View.VISIBLE : View.GONE);
        }

        PackageManager pm = getPackageManager();
        for (int i = 0; i < limit; i++) {
            BridgeClient.TaskEntry t = tasks.get(i);
            Drawable icon = getCachedIcon(pm, t.pkg);
            String label = getCachedLabel(pm, t.pkg);

            LinearLayout slot = new LinearLayout(mExtCtx);
            slot.setTag(t.pkg);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER);
            slot.setPadding(dp(mExtCtx, 6), dp(mExtCtx, 3), dp(mExtCtx, 6), dp(mExtCtx, 3));
            GradientDrawable slotBg = new GradientDrawable();
            slotBg.setCornerRadius(dp(mExtCtx, 10));
            if (t.isTopExt()) {
                slotBg.setColor(0x36FFFFFF);
            } else if (t.onExt()) {
                slotBg.setColor(0x18FFFFFF);
            } else {
                slotBg.setColor(Color.TRANSPARENT);
            }
            slot.setBackground(slotBg);

            ImageView iv = new ImageView(mExtCtx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (icon != null) iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(mExtCtx, 28), dp(mExtCtx, 28));
            slot.addView(iv, ivLp);

            View dot = new View(mExtCtx);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setCornerRadius(dp(mExtCtx, 2));
            if (t.isTopExt()) {
                dotBg.setColor(0xFF38BDF8);
            } else if (t.onExt()) {
                dotBg.setColor(0xFF94A3B8);
            } else if (t.isRunning()) {
                dotBg.setColor(0x7794A3B8);
            } else {
                dotBg.setColor(Color.TRANSPARENT);
            }
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    t.isTopExt() ? dp(mExtCtx, 14) : dp(mExtCtx, 6), dp(mExtCtx, 3));
            dotLp.topMargin = dp(mExtCtx, 2);
            slot.addView(dot, dotLp);

            final boolean[] secDown = {false};
            final Runnable openBubble = () -> {
                long now = SystemClock.uptimeMillis();
                if (now - mLastBubbleOpenMs < 240L) return;
                showTaskBubble(slot, t, label, icon);
            };
            slot.setOnClickListener(v -> {
                setPanelOpen(false);
                setTaskBubbleOpen(false);
                boolean shouldMinimize = t.isTopExt() && !t.isInSplit()
                        && mLastStatus != null && mLastStatus.mirrorMode == BridgeClient.MIRROR_MODE_DEX;
                if (shouldMinimize) {
                    performMinimizeTaskAnimated(slot, t, label, icon);
                } else {
                    performRestoreTaskAnimated(slot, t, label, icon);
                }
            });
            slot.setOnTouchListener((v, ev) -> {
                if (ev == null) return false;
                int act = ev.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN
                        && (ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    secDown[0] = true;
                    openBubble.run();
                    return true;
                }
                if (secDown[0]) {
                    if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        secDown[0] = false;
                    }
                    return true;
                }
                if (act == MotionEvent.ACTION_DOWN) {
                    v.animate().cancel();
                    v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80L).start();
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(170L)
                            .setInterpolator(new OvershootInterpolator(1.9f)).start();
                }
                return false;
            });
            slot.setOnGenericMotionListener((v, ev) -> {
                if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                        && ev.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                    openBubble.run();
                    return true;
                }
                return false;
            });
            if (Build.VERSION.SDK_INT >= 23) {
                slot.setOnContextClickListener(v -> {
                    openBubble.run();
                    return true;
                });
            }
            slot.setOnLongClickListener(v -> {
                openBubble.run();
                return true;
            });

            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sLp.leftMargin = dp(mExtCtx, 2);
            sLp.rightMargin = dp(mExtCtx, 2);
            mTaskStrip.addView(slot, sLp);
        }
    }

    // --- HyperOS 2.0 Window Minimize / Restore & Split-Screen Choreography ---

    private static void attachTactilePress(View v, float pressedScale) {
        if (v == null) return;
        v.setOnTouchListener((view, ev) -> {
            if (ev == null) return false;
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                view.animate().cancel();
                view.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(80L).start();
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                view.animate().cancel();
                view.animate().scaleX(1f).scaleY(1f).setDuration(165L)
                        .setInterpolator(new OvershootInterpolator(1.8f)).start();
            }
            return false;
        });
    }

    private Rect getFullStageRect() {
        int sw = Math.max(600, mOverlayW);
        int sh = Math.max(600, mOverlayH);
        return new Rect(0, 0, sw, sh);
    }

    private Rect computeLocalSplitRect(int slot, float ratio) {
        int sw = Math.max(600, mOverlayW);
        int sh = Math.max(600, mOverlayH);
        boolean portrait = sh > sw;
        float r = Math.max(0.25f, Math.min(0.75f, ratio));
        int halfGap = 4;
        if (!portrait) {
            int div = Math.max(Math.round(sw * 0.25f), Math.min(Math.round(sw * 0.75f), Math.round(sw * r)));
            return slot == 0
                    ? new Rect(0, 0, Math.max(240, div - halfGap), sh)
                    : new Rect(Math.min(sw - 240, div + halfGap), 0, sw, sh);
        } else {
            int div = Math.max(Math.round(sh * 0.25f), Math.min(Math.round(sh * 0.75f), Math.round(sh * r)));
            return slot == 0
                    ? new Rect(0, 0, sw, Math.max(240, div - halfGap))
                    : new Rect(0, Math.min(sh - 240, div + halfGap), sw, sh);
        }
    }

    private View findDockSlotByPkg(String pkg) {
        if (mTaskStrip == null || pkg == null || pkg.isEmpty()) return null;
        int n = mTaskStrip.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = mTaskStrip.getChildAt(i);
            if (child != null && pkg.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    private RectF resolveDockIconRectOnScreen(View preferredSlot, String pkg) {
        int sw = Math.max(600, mOverlayW);
        int sh = Math.max(600, mOverlayH);
        View slot = preferredSlot != null ? preferredSlot : findDockSlotByPkg(pkg);
        View target = slot;
        if (slot instanceof ViewGroup && ((ViewGroup) slot).getChildCount() > 0) {
            target = ((ViewGroup) slot).getChildAt(0);
        }
        if (target != null && target.isShown() && target.getWidth() > 0 && target.getHeight() > 0) {
            int[] loc = new int[2];
            target.getLocationOnScreen(loc);
            if (loc[0] >= 0 && loc[1] >= 0 && loc[0] < sw && loc[1] < sh) {
                return new RectF(loc[0], loc[1], loc[0] + target.getWidth(), loc[1] + target.getHeight());
            }
        }
        int sz = mExtCtx != null ? dp(mExtCtx, 28) : 36;
        int bottomPad = mExtCtx != null ? dp(mExtCtx, 18) : 24;
        float cx = sw * 0.5f;
        float cy = sh - bottomPad - sz * 0.5f;
        return new RectF(cx - sz * 0.5f, cy - sz * 0.5f, cx + sz * 0.5f, cy + sz * 0.5f);
    }

    private void bounceDockSlot(String pkg, View fallbackView, boolean downwardCushion) {
        View v = findDockSlotByPkg(pkg);
        if (v == null) v = fallbackView;
        if (v == null || mExtCtx == null) return;
        final View target = v;
        float dy = downwardCushion ? dp(mExtCtx, 4.5f) : -dp(mExtCtx, 4f);
        float sc = downwardCushion ? 0.85f : 1.12f;
        target.animate().cancel();
        target.animate()
                .translationY(dy)
                .scaleX(sc)
                .scaleY(sc)
                .setDuration(85L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> target.animate()
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(190L)
                        .setInterpolator(new OvershootInterpolator(2.1f))
                        .start())
                .start();
    }

    private void beginWindowOverlayStage() {
        mForceWindowOverlaysDuringAnim = true;
        syncOverlayHostSurface(false);
        bringCursorToFront();
    }

    private void endWindowOverlayStage() {
        mForceWindowOverlaysDuringAnim = false;
        syncOverlayHostSurface(false);
        bringCursorToFront();
    }

    private StageTransitionView.CardSpec buildCardSpec(
            String pkg,
            BridgeClient.TaskSnapshotData snap,
            Rect startR,
            Rect endR) {
        StageTransitionView.CardSpec spec = new StageTransitionView.CardSpec();
        if (snap != null && snap.bitmap != null) {
            spec.snapshot = snap.bitmap;
            spec.ownSnapshot = snap.ownBitmap;
        }
        if (pkg != null && !pkg.isEmpty() && !"com.extbridge.helper".equals(pkg)) {
            PackageManager pm = getPackageManager();
            spec.icon = getCachedIcon(pm, pkg);
            spec.label = getCachedLabel(pm, pkg);
        } else {
            spec.label = "扩展桌面";
        }
        spec.setRects(startR, endR);
        return spec;
    }

    private static void recycleUnusedCard(StageTransitionView.CardSpec c) {
        if (c != null && c.ownSnapshot && c.snapshot != null && !c.snapshot.isRecycled()) {
            try { c.snapshot.recycle(); } catch (Throwable ignored) {}
        }
        if (c != null) c.snapshot = null;
    }

    private void performMinimizeTaskAnimated(View slotView, BridgeClient.TaskEntry t, String label, Drawable icon) {
        if (mStageAnimView == null || mExtCtx == null || t == null) {
            safeBgExecute(() -> {
                BridgeClient.minimizeToExtHome(t != null ? t.taskId : -1, t != null ? t.pkg : null);
                safePost(this::pollRemoteStateAsync);
            });
            return;
        }
        final RectF dockIconRect = resolveDockIconRectOnScreen(slotView, t.pkg);
        final Rect fullRect = getFullStageRect();
        final Drawable animIcon = icon != null ? cloneDrawable(icon) : getCachedIcon(getPackageManager(), t.pkg);
        final String animLabel = (label != null && !label.isEmpty()) ? label : getCachedLabel(getPackageManager(), t.pkg);

        safeBgExecute(() -> {
            BridgeClient.TaskSnapshotData snap = BridgeClient.captureTaskSnapshot(t.taskId, t.pkg, true);
            Rect startBounds = (snap != null && snap.bounds != null && snap.bounds.width() > 60)
                    ? snap.bounds : fullRect;

            StageTransitionView.CardSpec card = new StageTransitionView.CardSpec();
            if (snap != null && snap.bitmap != null) {
                card.snapshot = snap.bitmap;
                card.ownSnapshot = snap.ownBitmap;
            }
            card.icon = animIcon;
            card.label = animLabel;
            card.startRect.set(startBounds);
            card.endRect.set(dockIconRect);

            CountDownLatch frame0Latch = new CountDownLatch(1);
            safePost(() -> {
                StageTransitionView anim = mStageAnimView;
                if (anim == null || !anim.isAttachedToWindow() || mExtCtx == null) {
                    recycleUnusedCard(card);
                    frame0Latch.countDown();
                    return;
                }
                beginWindowOverlayStage();
                anim.playMinimizeToDock(
                        card,
                        () -> bounceDockSlot(t.pkg, slotView, true),
                        () -> {
                            endWindowOverlayStage();
                            pollRemoteStateAsync();
                        });
                frame0Latch.countDown();
            });
            try { frame0Latch.await(45L, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            BridgeClient.minimizeToExtHome(t.taskId, t.pkg);
            mHandler.postDelayed(this::pollRemoteStateAsync, 260L);
        });
    }

    private void performHomeButtonAnimated(View homeBtn) {
        BridgeClient.Status st = mLastStatus;
        boolean canAnimMinimize = mStageAnimView != null && mExtCtx != null
                && !mIsOnLauncherHome
                && st != null && st.connected && st.mirrorMode == BridgeClient.MIRROR_MODE_DEX;
        if (!canAnimMinimize) {
            safeBgExecute(() -> {
                BridgeClient.relaunchLauncher();
                safePost(this::pollRemoteStateAsync);
            });
            return;
        }
        if (st.splitActive == 1) {
            boolean singleEmpty = ((st.splitSlot0Pkg == null || st.splitSlot0Pkg.isEmpty())
                    != (st.splitSlot1Pkg == null || st.splitSlot1Pkg.isEmpty()));
            if (!singleEmpty) {
                performExitSplitToHomeAnimated(homeBtn);
                return;
            }
            safeBgExecute(() -> {
                BridgeClient.relaunchLauncher();
                safePost(this::pollRemoteStateAsync);
            });
            return;
        }

        safeBgExecute(() -> {
            List<BridgeClient.TaskEntry> tasks = BridgeClient.getTasks();
            BridgeClient.TaskEntry topExt = null;
            if (tasks != null) {
                for (BridgeClient.TaskEntry te : tasks) {
                    if (te != null && te.isTopExt() && !"com.extbridge.helper".equals(te.pkg)) {
                        topExt = te;
                        break;
                    }
                }
            }
            if (topExt == null) {
                BridgeClient.relaunchLauncher();
                safePost(this::pollRemoteStateAsync);
                return;
            }
            final BridgeClient.TaskEntry target = topExt;
            PackageManager pm = getPackageManager();
            final Drawable icon = getCachedIcon(pm, target.pkg);
            final String label = getCachedLabel(pm, target.pkg);
            safePost(() -> performMinimizeTaskAnimated(findDockSlotByPkg(target.pkg) != null
                    ? findDockSlotByPkg(target.pkg) : homeBtn, target, label, icon));
        });
    }

    private void performExitSplitToHomeAnimated(View anchorBtn) {
        Context extCtx = mExtCtx;
        if (mStageAnimView == null || extCtx == null) {
            safeBgExecute(() -> {
                BridgeClient.relaunchLauncher();
                safePost(this::pollRemoteStateAsync);
            });
            return;
        }
        final int cornerStart = dp(extCtx, 10);
        final int cornerEnd = dp(extCtx, 9);
        final RectF anchorRect = resolveDockIconRectOnScreen(anchorBtn, null);
        final Rect fullRect = getFullStageRect();
        final String p0 = mLastStatus != null ? mLastStatus.splitSlot0Pkg : "";
        final String p1 = mLastStatus != null ? mLastStatus.splitSlot1Pkg : "";
        final float ratio = mDisplayedSplitRatio;

        safeBgExecute(() -> {
            BridgeClient.TaskSnapshotData s0 = !TextUtils.isEmpty(p0)
                    ? BridgeClient.captureTaskSnapshot(-1, p0, true) : null;
            BridgeClient.TaskSnapshotData s1 = !TextUtils.isEmpty(p1)
                    ? BridgeClient.captureTaskSnapshot(-1, p1, true) : null;
            Rect r0 = (s0 != null && s0.bounds != null) ? s0.bounds : computeLocalSplitRect(0, ratio);
            Rect r1 = (s1 != null && s1.bounds != null) ? s1.bounds : computeLocalSplitRect(1, ratio);

            StageTransitionView.CardSpec c0 = !TextUtils.isEmpty(p0)
                    ? buildCardSpec(p0, s0, r0, new Rect(Math.round(anchorRect.left), Math.round(anchorRect.top),
                            Math.round(anchorRect.right), Math.round(anchorRect.bottom)))
                    : null;
            StageTransitionView.CardSpec c1 = !TextUtils.isEmpty(p1)
                    ? buildCardSpec(p1, s1, r1, new Rect(Math.round(anchorRect.left), Math.round(anchorRect.top),
                            Math.round(anchorRect.right), Math.round(anchorRect.bottom)))
                    : null;
            if (c0 != null) {
                c0.mode = StageTransitionView.MODE_MINIMIZE_TO_ICON;
                c0.startCornerPx = cornerStart;
                c0.endCornerPx = cornerEnd;
            }
            if (c1 != null) {
                c1.mode = StageTransitionView.MODE_MINIMIZE_TO_ICON;
                c1.startCornerPx = cornerStart;
                c1.endCornerPx = cornerEnd;
            }
            final StageTransitionView.CardSpec fc0 = c0 != null ? c0 : buildCardSpec(null, null, fullRect, fullRect);
            final StageTransitionView.CardSpec fc1 = c1;
            CountDownLatch latch = new CountDownLatch(1);
            safePost(() -> {
                StageTransitionView anim = mStageAnimView;
                if (anim == null || !anim.isAttachedToWindow() || mExtCtx == null) {
                    recycleUnusedCard(fc0);
                    recycleUnusedCard(fc1);
                    latch.countDown();
                    return;
                }
                beginWindowOverlayStage();
                anim.playSplitTransition(fc0, fc1, mOverlayH > mOverlayW, 250L, false, () -> {
                    endWindowOverlayStage();
                    pollRemoteStateAsync();
                });
                anim.notifyBackendReady(anim.getActiveToken());
                latch.countDown();
            });
            try { latch.await(45L, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            BridgeClient.relaunchLauncher();
            mHandler.postDelayed(this::pollRemoteStateAsync, 260L);
        });
    }

    private void performRestoreTaskAnimated(View slotView, BridgeClient.TaskEntry t, String label, Drawable icon) {
        if (mStageAnimView == null || mExtCtx == null || t == null) {
            safeBgExecute(() -> {
                BridgeClient.switchToTask(t != null ? t.taskId : -1, t != null ? t.pkg : null);
                safePost(this::pollRemoteStateAsync);
            });
            return;
        }
        bounceDockSlot(t.pkg, slotView, false);
        final RectF dockIconRect = resolveDockIconRectOnScreen(slotView, t.pkg);
        BridgeClient.Status st = mLastStatus;
        int splitTargetSlot = -1;
        if (st != null && st.splitActive == 1 && t.pkg != null && !"com.extbridge.helper".equals(t.pkg)) {
            if (t.pkg.equals(st.splitSlot0Pkg)) splitTargetSlot = 0;
            else if (t.pkg.equals(st.splitSlot1Pkg)) splitTargetSlot = 1;
            else if (TextUtils.isEmpty(st.splitSlot0Pkg)) splitTargetSlot = 0;
            else if (TextUtils.isEmpty(st.splitSlot1Pkg)) splitTargetSlot = 1;
        }
        final boolean targetIsSplit = (splitTargetSlot >= 0);
        final Rect targetRect = targetIsSplit
                ? computeLocalSplitRect(splitTargetSlot, mDisplayedSplitRatio)
                : getFullStageRect();
        final Drawable animIcon = icon != null ? cloneDrawable(icon) : getCachedIcon(getPackageManager(), t.pkg);
        final String animLabel = (label != null && !label.isEmpty()) ? label : getCachedLabel(getPackageManager(), t.pkg);

        safeBgExecute(() -> {
            BridgeClient.TaskSnapshotData snap = BridgeClient.captureTaskSnapshot(t.taskId, t.pkg, false);
            StageTransitionView.CardSpec card = new StageTransitionView.CardSpec();
            if (snap != null && snap.bitmap != null) {
                card.snapshot = snap.bitmap;
                card.ownSnapshot = snap.ownBitmap;
            }
            card.icon = animIcon;
            card.label = animLabel;
            card.startRect.set(dockIconRect);
            card.endRect.set(targetRect);

            final int[] tokenBox = {-1};
            CountDownLatch latch = new CountDownLatch(1);
            safePost(() -> {
                StageTransitionView anim = mStageAnimView;
                if (anim == null || !anim.isAttachedToWindow() || mExtCtx == null) {
                    recycleUnusedCard(card);
                    latch.countDown();
                    return;
                }
                beginWindowOverlayStage();
                anim.playRestoreFromDock(card, targetIsSplit, () -> {
                    endWindowOverlayStage();
                    pollRemoteStateAsync();
                });
                tokenBox[0] = anim.getActiveToken();
                latch.countDown();
            });
            try { latch.await(45L, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            BridgeClient.switchToTask(t.taskId, t.pkg);
            StageTransitionView animNow = mStageAnimView;
            if (animNow != null && tokenBox[0] > 0) animNow.notifyBackendReady(tokenBox[0]);
            mHandler.postDelayed(this::pollRemoteStateAsync, 180L);
        });
    }

    private void animateSplitDividerTo(float targetRatio, long durationMs) {
        if (mSplitDividerAnimator != null) {
            mSplitDividerAnimator.cancel();
            mSplitDividerAnimator = null;
        }
        float startRatio = mDisplayedSplitRatio;
        float clamped = Math.max(0.25f, Math.min(0.75f, targetRatio));
        if (Math.abs(clamped - startRatio) < 0.005f || mSplitDividerRoot == null) {
            positionSplitDividerAt(clamped, false);
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(startRatio, clamped);
        va.setDuration(durationMs);
        va.setInterpolator(new PathInterpolator(0.20f, 0.96f, 0.28f, 1.0f));
        va.addUpdateListener(anim -> positionSplitDividerAt((float) anim.getAnimatedValue(), false));
        mSplitDividerAnimator = va;
        va.start();
    }

    private void performEnterOrAssignSplitAnimated(int action, int slotHint, int taskId, String pkg, String cls) {
        Context extCtx = mExtCtx;
        if (mStageAnimView == null || extCtx == null) {
            safeBgExecute(() -> {
                BridgeClient.SplitResult sr = BridgeClient.splitActionDetailed(action, taskId, pkg, cls);
                safePost(() -> {
                    if (action == BridgeClient.SPLIT_TOGGLE_QUICK && !sr.ok) {
                        Toast.makeText(BridgeService.this,
                                "请先打开应用，或在桌面长按/右键图标选择「分屏：放入左/右屏」",
                                Toast.LENGTH_LONG).show();
                    }
                    pollRemoteStateAsync();
                });
            });
            return;
        }

        final int dp16 = dp(extCtx, 16);
        final int dp8 = dp(extCtx, 8);
        final boolean portrait = mOverlayH > mOverlayW;
        final Rect fullRect = getFullStageRect();
        final BridgeClient.Status preSt = mLastStatus;
        final boolean wasSplit = (preSt != null && preSt.splitActive == 1);
        final float curRatio = (preSt != null && preSt.splitRatioPct > 0) ? (preSt.splitRatioPct / 100f) : 0.50f;
        final String preSlot0 = (preSt != null && preSt.splitSlot0Pkg != null) ? preSt.splitSlot0Pkg : "";
        final String preSlot1 = (preSt != null && preSt.splitSlot1Pkg != null) ? preSt.splitSlot1Pkg : "";

        safeBgExecute(() -> {
            // 1. Capture pre-transition snapshot(s) BEFORE split changes WM bounds so there is zero black resize flash
            String topVisiblePkg = "";
            int topVisibleTid = -1;
            String secondCandPkg = "";
            int secondCandTid = -1;
            List<BridgeClient.TaskEntry> preTasks = BridgeClient.getTasks();
            if (preTasks != null) {
                for (BridgeClient.TaskEntry te : preTasks) {
                    if (te == null || "com.extbridge.helper".equals(te.pkg) || "android".equals(te.pkg)) continue;
                    if (TextUtils.isEmpty(topVisiblePkg) && te.isTopExt()) {
                        topVisiblePkg = te.pkg;
                        topVisibleTid = te.taskId;
                    } else if (TextUtils.isEmpty(secondCandPkg)) {
                        secondCandPkg = te.pkg;
                        secondCandTid = te.taskId;
                    }
                }
                if (TextUtils.isEmpty(topVisiblePkg)) {
                    for (BridgeClient.TaskEntry te : preTasks) {
                        if (te == null || "com.extbridge.helper".equals(te.pkg) || "android".equals(te.pkg)) continue;
                        topVisiblePkg = te.pkg;
                        topVisibleTid = te.taskId;
                        break;
                    }
                }
            }

            if (action == BridgeClient.SPLIT_TOGGLE_QUICK && TextUtils.isEmpty(topVisiblePkg) && TextUtils.isEmpty(secondCandPkg)) {
                safePost(() -> Toast.makeText(BridgeService.this,
                        "请先打开应用，或在桌面长按/右键图标选择「分屏：放入左/右屏」",
                        Toast.LENGTH_LONG).show());
                return;
            }

            // Predict resulting slot0 and slot1 before calling splitActionDetailed so Frame 0 covers screen immediately
            String pred0, pred1;
            if (action == BridgeClient.SPLIT_TOGGLE_QUICK) {
                pred0 = topVisiblePkg;
                pred1 = secondCandPkg;
            } else if (action == BridgeClient.SPLIT_ASSIGN_LEFT_TOP) {
                pred0 = pkg != null ? pkg : "";
                pred1 = (wasSplit && !pred0.equals(preSlot1)) ? preSlot1 : "";
            } else {
                pred1 = pkg != null ? pkg : "";
                pred0 = (wasSplit && !pred1.equals(preSlot0)) ? preSlot0 : "";
            }

            BridgeClient.TaskSnapshotData snap0 = null;
            if (!TextUtils.isEmpty(pred0)) {
                boolean isLive0 = pred0.equals(topVisiblePkg) || (wasSplit && pred0.equals(preSlot0));
                snap0 = BridgeClient.captureTaskSnapshot(pred0.equals(topVisiblePkg) ? topVisibleTid : -1, pred0, isLive0);
            }
            BridgeClient.TaskSnapshotData snap1 = null;
            if (!TextUtils.isEmpty(pred1)) {
                boolean isLive1 = pred1.equals(topVisiblePkg) || (wasSplit && pred1.equals(preSlot1));
                snap1 = BridgeClient.captureTaskSnapshot(pred1.equals(topVisiblePkg) ? topVisibleTid : secondCandTid, pred1, isLive1);
            }

            float targetRatio = Math.max(0.25f, Math.min(0.75f, curRatio));
            Rect end0 = computeLocalSplitRect(0, targetRatio);
            Rect end1 = computeLocalSplitRect(1, targetRatio);

            Rect start0 = (wasSplit && pred0.equals(preSlot0)) ? computeLocalSplitRect(0, curRatio)
                    : ((!TextUtils.isEmpty(pred0) && pred0.equals(topVisiblePkg)) ? fullRect : offsetSlideInRect(end0, portrait, true));
            Rect start1 = (wasSplit && pred1.equals(preSlot1)) ? computeLocalSplitRect(1, curRatio)
                    : ((!TextUtils.isEmpty(pred1) && pred1.equals(topVisiblePkg)) ? fullRect : offsetSlideInRect(end1, portrait, false));

            StageTransitionView.CardSpec c0 = buildCardSpec(pred0, snap0, start0, end0);
            c0.startCornerPx = start0.equals(fullRect) ? 0f : dp16;
            c0.endCornerPx = dp8;
            c0.startAlpha = start0.equals(fullRect) || (wasSplit && pred0.equals(preSlot0)) ? 1f : 0.30f;

            StageTransitionView.CardSpec c1 = buildCardSpec(pred1, snap1, start1, end1);
            c1.startCornerPx = start1.equals(fullRect) ? 0f : dp16;
            c1.endCornerPx = dp8;
            c1.startAlpha = start1.equals(fullRect) || (wasSplit && pred1.equals(preSlot1)) ? 1f : 0.30f;

            final int[] tokenBox = {-1};
            CountDownLatch frame0Latch = new CountDownLatch(1);
            safePost(() -> {
                StageTransitionView anim = mStageAnimView;
                if (anim == null || !anim.isAttachedToWindow() || mExtCtx == null) {
                    recycleUnusedCard(c0);
                    recycleUnusedCard(c1);
                    frame0Latch.countDown();
                    return;
                }
                beginWindowOverlayStage();
                anim.playSplitTransition(c0, c1, portrait, 285L, false, () -> {
                    endWindowOverlayStage();
                    pollRemoteStateAsync();
                });
                tokenBox[0] = anim.getActiveToken();
                frame0Latch.countDown();
            });
            try { frame0Latch.await(45L, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            // 2. Now resize tasks underneath our active StageTransitionView (zero black resize flash!)
            BridgeClient.SplitResult sr = BridgeClient.splitActionDetailed(action, taskId, pkg, cls);
            final int expectedToken = tokenBox[0];
            safePost(() -> {
                if (sr.ok && action == BridgeClient.SPLIT_TOGGLE_QUICK) {
                    Toast.makeText(BridgeService.this,
                            portrait ? "已开启上下分屏（拖动分割条可调比例）" : "已开启左右分屏（拖动分割条可调比例）",
                            Toast.LENGTH_SHORT).show();
                } else if (!sr.ok && action == BridgeClient.SPLIT_TOGGLE_QUICK) {
                    Toast.makeText(BridgeService.this,
                            "请先打开应用，或在桌面长按/右键图标选择「分屏：放入左/右屏」",
                            Toast.LENGTH_LONG).show();
                }
                pollRemoteStateAsync();
                // Allow multi-window Activities ~130ms to finish redrawing their new half-bounds before dissolving
                mHandler.postDelayed(() -> {
                    StageTransitionView animNow = mStageAnimView;
                    if (animNow != null && expectedToken > 0) animNow.notifyBackendReady(expectedToken);
                }, 130L);
            });
        });
    }

    private Rect offsetSlideInRect(Rect target, boolean portrait, boolean firstSlot) {
        Context extCtx = mExtCtx;
        int shift = extCtx != null ? dp(extCtx, 56) : 72;
        int insetW = Math.round(target.width() * 0.04f);
        int insetH = Math.round(target.height() * 0.04f);
        Rect r = new Rect(target.left + insetW, target.top + insetH,
                target.right - insetW, target.bottom - insetH);
        if (!portrait) {
            r.offset(firstSlot ? -shift : shift, 0);
        } else {
            r.offset(0, firstSlot ? -shift : shift);
        }
        return r;
    }

    private void performSplitRatioAnimated(int pct, boolean fromDragRelease) {
        int clampedPct = Math.max(25, Math.min(75, pct));
        float oldRatio = mDisplayedSplitRatio;
        float newRatio = clampedPct / 100f;
        if (mLastStatus != null) mLastStatus.splitRatioPct = clampedPct;

        long dur = fromDragRelease ? 180L : 240L;
        animateSplitDividerTo(newRatio, dur);

        Context extCtx = mExtCtx;
        if (mStageAnimView == null || extCtx == null || Math.abs(newRatio - oldRatio) < 0.015f || fromDragRelease) {
            safeBgExecute(() -> {
                BridgeClient.setSplitRatio(clampedPct);
                safePost(() -> {
                    Toast.makeText(BridgeService.this,
                            "分屏比例：" + clampedPct + "% : " + (100 - clampedPct) + "%", Toast.LENGTH_SHORT).show();
                    pollRemoteStateAsync();
                });
            });
            return;
        }

        final int dp8 = dp(extCtx, 8);
        final int dp6 = dp(extCtx, 6);
        final String p0 = (mLastStatus != null && mLastStatus.splitSlot0Pkg != null) ? mLastStatus.splitSlot0Pkg : "";
        final String p1 = (mLastStatus != null && mLastStatus.splitSlot1Pkg != null) ? mLastStatus.splitSlot1Pkg : "";
        final boolean portrait = mOverlayH > mOverlayW;
        final Rect old0 = computeLocalSplitRect(0, oldRatio);
        final Rect old1 = computeLocalSplitRect(1, oldRatio);
        final Rect new0 = computeLocalSplitRect(0, newRatio);
        final Rect new1 = computeLocalSplitRect(1, newRatio);

        safeBgExecute(() -> {
            BridgeClient.TaskSnapshotData s0 = !TextUtils.isEmpty(p0)
                    ? BridgeClient.captureTaskSnapshot(-1, p0, true) : null;
            BridgeClient.TaskSnapshotData s1 = !TextUtils.isEmpty(p1)
                    ? BridgeClient.captureTaskSnapshot(-1, p1, true) : null;

            StageTransitionView.CardSpec c0 = buildCardSpec(p0, s0, old0, new0);
            c0.startCornerPx = dp8;
            c0.endCornerPx = dp6;

            StageTransitionView.CardSpec c1 = buildCardSpec(p1, s1, old1, new1);
            c1.startCornerPx = dp8;
            c1.endCornerPx = dp6;

            final int[] tokenBox = {-1};
            CountDownLatch latch = new CountDownLatch(1);
            safePost(() -> {
                StageTransitionView anim = mStageAnimView;
                if (anim == null || !anim.isAttachedToWindow() || mExtCtx == null) {
                    recycleUnusedCard(c0);
                    recycleUnusedCard(c1);
                    latch.countDown();
                    return;
                }
                beginWindowOverlayStage();
                anim.playSplitTransition(c0, c1, portrait, dur, false, () -> {
                    endWindowOverlayStage();
                    pollRemoteStateAsync();
                });
                tokenBox[0] = anim.getActiveToken();
                latch.countDown();
            });
            try { latch.await(40L, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            BridgeClient.setSplitRatio(clampedPct);
            StageTransitionView animNow = mStageAnimView;
            if (animNow != null && tokenBox[0] > 0) animNow.notifyBackendReady(tokenBox[0]);
            safePost(() -> Toast.makeText(BridgeService.this,
                    "分屏比例：" + clampedPct + "% : " + (100 - clampedPct) + "%", Toast.LENGTH_SHORT).show());
        });
    }

    private void performSplitSwapAnimated() {
        Context extCtx = mExtCtx;
        if (mStageAnimView == null || extCtx == null || mLastStatus == null) {
            safeBgExecute(() -> {
                BridgeClient.swapSplitSlots();
                safePost(this::pollRemoteStateAsync);
            });
            return;
        }
        final int dp8 = dp(extCtx, 8);
        final String p0 = mLastStatus.splitSlot0Pkg != null ? mLastStatus.splitSlot0Pkg : "";
        final String p1 = mLastStatus.splitSlot1Pkg != null ? mLastStatus.splitSlot1Pkg : "";
        final boolean portrait = mOverlayH > mOverlayW;
        final float ratio = mDisplayedSplitRatio;
        final Rect r0 = computeLocalSplitRect(0, ratio);
        final Rect r1 = computeLocalSplitRect(1, ratio);

        if (mSplitDividerPill != null) {
            mSplitDividerPill.animate().cancel();
            mSplitDividerPill.animate()
                    .scaleX(1.16f).scaleY(1.16f).setDuration(130L)
                    .withEndAction(() -> {
                        if (mSplitDividerPill != null) {
                            mSplitDividerPill.animate().scaleX(1f).scaleY(1f).setDuration(170L)
                                    .setInterpolator(new OvershootInterpolator(1.6f)).start();
                        }
                    }).start();
        }

        safeBgExecute(() -> {
            BridgeClient.TaskSnapshotData s0 = !TextUtils.isEmpty(p0)
                    ? BridgeClient.captureTaskSnapshot(-1, p0, true) : null;
            BridgeClient.TaskSnapshotData s1 = !TextUtils.isEmpty(p1)
                    ? BridgeClient.captureTaskSnapshot(-1, p1, true) : null;

            StageTransitionView.CardSpec cOver = buildCardSpec(p0, s0, r0, r1);
            cOver.mode = StageTransitionView.MODE_SWAP_OVER;
            cOver.startCornerPx = dp8;
            cOver.endCornerPx = dp8;

            StageTransitionView.CardSpec cUnder = buildCardSpec(p1, s1, r1, r0);
            cUnder.mode = StageTransitionView.MODE_SWAP_UNDER;
            cUnder.startCornerPx = dp8;
            cUnder.endCornerPx = dp8;

            final int[] tokenBox = {-1};
            CountDownLatch latch = new CountDownLatch(1);
            safePost(() -> {
                StageTransitionView anim = mStageAnimView;
                if (anim == null || !anim.isAttachedToWindow() || mExtCtx == null) {
                    recycleUnusedCard(cOver);
                    recycleUnusedCard(cUnder);
                    latch.countDown();
                    return;
                }
                beginWindowOverlayStage();
                anim.playSplitTransition(cOver, cUnder, portrait, 300L, true, () -> {
                    endWindowOverlayStage();
                    pollRemoteStateAsync();
                });
                tokenBox[0] = anim.getActiveToken();
                latch.countDown();
            });
            try { latch.await(45L, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            BridgeClient.swapSplitSlots();
            StageTransitionView animNow = mStageAnimView;
            if (animNow != null && tokenBox[0] > 0) animNow.notifyBackendReady(tokenBox[0]);
        });
    }

    private void performExitSplitAnimated() {
        if (mStageAnimView == null || mExtCtx == null || mLastStatus == null) {
            safeBgExecute(() -> {
                BridgeClient.exitSplitMode();
                safePost(() -> {
                    Toast.makeText(BridgeService.this, "已退出分屏", Toast.LENGTH_SHORT).show();
                    pollRemoteStateAsync();
                });
            });
            return;
        }
        final String p0 = mLastStatus.splitSlot0Pkg != null ? mLastStatus.splitSlot0Pkg : "";
        final String p1 = mLastStatus.splitSlot1Pkg != null ? mLastStatus.splitSlot1Pkg : "";
        final boolean portrait = mOverlayH > mOverlayW;
        final float ratio = mDisplayedSplitRatio;
        final Rect r0 = computeLocalSplitRect(0, ratio);
        final Rect r1 = computeLocalSplitRect(1, ratio);
        final Rect fullRect = getFullStageRect();
        final boolean expandSlot0 = !TextUtils.isEmpty(p0) || TextUtils.isEmpty(p1);

        if (mSplitDividerRoot != null && mSplitDividerRoot.getVisibility() == View.VISIBLE) {
            mSplitDividerRoot.animate().cancel();
            mSplitDividerRoot.animate()
                    .alpha(0f).scaleX(0.55f).scaleY(0.55f).setDuration(150L)
                    .withEndAction(() -> {
                        if (mSplitDividerRoot != null) {
                            mSplitDividerRoot.setVisibility(View.GONE);
                            mSplitDividerRoot.setAlpha(1f);
                            mSplitDividerRoot.setScaleX(1f);
                            mSplitDividerRoot.setScaleY(1f);
                        }
                    }).start();
        }

        safeBgExecute(() -> {
            BridgeClient.TaskSnapshotData s0 = !TextUtils.isEmpty(p0)
                    ? BridgeClient.captureTaskSnapshot(-1, p0, true) : null;
            BridgeClient.TaskSnapshotData s1 = !TextUtils.isEmpty(p1)
                    ? BridgeClient.captureTaskSnapshot(-1, p1, true) : null;

            StageTransitionView.CardSpec expCard;
            StageTransitionView.CardSpec recCard;
            if (expandSlot0) {
                expCard = buildCardSpec(p0, s0, r0, fullRect);
                Rect recEnd = offsetSlideInRect(r1, portrait, false);
                recCard = !TextUtils.isEmpty(p1) ? buildCardSpec(p1, s1, r1, recEnd) : null;
            } else {
                expCard = buildCardSpec(p1, s1, r1, fullRect);
                Rect recEnd = offsetSlideInRect(r0, portrait, true);
                recCard = !TextUtils.isEmpty(p0) ? buildCardSpec(p0, s0, r0, recEnd) : null;
            }

            final int[] tokenBox = {-1};
            CountDownLatch latch = new CountDownLatch(1);
            safePost(() -> {
                StageTransitionView anim = mStageAnimView;
                if (anim == null || !anim.isAttachedToWindow() || mExtCtx == null) {
                    recycleUnusedCard(expCard);
                    recycleUnusedCard(recCard);
                    latch.countDown();
                    return;
                }
                beginWindowOverlayStage();
                anim.playSplitExit(expCard, recCard, () -> {
                    endWindowOverlayStage();
                    pollRemoteStateAsync();
                });
                tokenBox[0] = anim.getActiveToken();
                latch.countDown();
            });
            try { latch.await(45L, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            BridgeClient.exitSplitMode();
            StageTransitionView animNow = mStageAnimView;
            if (animNow != null && tokenBox[0] > 0) animNow.notifyBackendReady(tokenBox[0]);
            safePost(() -> Toast.makeText(BridgeService.this, "已退出分屏", Toast.LENGTH_SHORT).show());
        });
    }

    private void refreshLocalHardwareViews() {
        refreshTrayIconsOnly();
        if (mAm != null && mVolSeekBar != null && !mSeekingVol) {
            int max = Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = mAm.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (mVolSeekBar.getMax() != max) mVolSeekBar.setMax(max);
            mVolSeekBar.setProgress(cur);
        }
        if (mTrayBattView != null) mTrayBattView.setBattery(mBattPct, mBattCharging);
        if (mTrayBattPct != null) mTrayBattPct.setText(mBattPct + "%");
        if (mPanelBattView != null) mPanelBattView.setBattery(mBattPct, mBattCharging);
        if (mPanelBattText != null) {
            mPanelBattText.setText(mBattPct + "%" + (mBattCharging ? " · 充电中" : ""));
        }
    }

    private void refreshTrayIconsOnly() {
        boolean muted = false;
        int volPct = 50;
        if (mAm != null) {
            int max = Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = mAm.getStreamVolume(AudioManager.STREAM_MUSIC);
            try { muted = mAm.isStreamMute(AudioManager.STREAM_MUSIC) || cur == 0; } catch (Throwable ignored) {}
            volPct = Math.round(cur * 100f / max);
        }
        if (mTrayVolIcon != null) mTrayVolIcon.setVolumeLevel(muted ? 0 : (volPct > 50 ? 2 : 1));
        if (mVolSpeakerIcon != null) mVolSpeakerIcon.setVolumeLevel(muted ? 0 : (volPct > 50 ? 2 : 1));
    }

    private void refreshSysViews() {
        if (!mLastSys.valid) return;
        if (mTrayWifiIcon != null) {
            mTrayWifiIcon.setActiveState(mLastSys.wifiOn);
        }
        if (mTileWifi != null) {
            String sub = mLastSys.wifiOn
                    ? (mLastSys.wifiSsid.isEmpty() ? "已开启" : mLastSys.wifiSsid)
                    : "Wi-Fi";
            mTileWifi.setActive(mLastSys.wifiOn, sub);
        }
        if (mTileBt != null) {
            String sub = mLastSys.btOn
                    ? (mLastSys.btName.isEmpty() ? "未连接" : mLastSys.btName)
                    : "蓝牙";
            mTileBt.setActive(mLastSys.btOn, sub);
        }
        if (mTileAirplane != null) {
            mTileAirplane.setActive(mLastSys.airplaneOn, "飞行模式");
        }
        if (mTilePowerSave != null) {
            mTilePowerSave.setActive(mLastSys.powerSaveOn, "节能模式");
        }
    }

    private void execSysAndRefresh(int act, int arg, String str) {
        safeBgExecute(() -> {
            BridgeClient.execSysAction(act, arg, str);
            safePost(() -> mHandler.postDelayed(() -> safeBgExecute(() -> {
                BridgeClient.SysStatus sys = BridgeClient.getSysStatus();
                safePost(() -> {
                    if (sys != null && sys.valid) mLastSys = sys;
                    refreshSysViews();
                    refreshMirrorSideViews(mLastStatus);
                });
            }), 220L));
        });
    }

    private void openExtSettings(String action) {
        setPanelOpen(false);
        if (mUsingA11yOverlay) {
            // Scheme B: accessibility overlay remains visible inside Settings on external display
            safeBgExecute(() -> BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, -1, action));
        } else {
            // Scheme A fallback: open Settings on tablet Display 0 so external screen isn't trapped
            Toast.makeText(this, "未开启无障碍：已在平板打开系统设置", Toast.LENGTH_SHORT).show();
            safeBgExecute(() -> BridgeClient.execSysAction(BridgeClient.SYS_OPEN_SETTINGS, 0, action));
        }
    }

    private void openTabletMain() {
        setPanelOpen(false);
        try {
            Intent mi = new Intent(BridgeService.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o0 = ActivityOptions.makeBasic();
            o0.setLaunchDisplayId(getActiveBuiltinDisplayId(this));
            startActivity(mi, o0.toBundle());
        } catch (Throwable ignored) {}
    }

    private void openTabletTouchpad() {
        setPanelOpen(false);
        try {
            Intent ti = new Intent(BridgeService.this, TouchpadActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions o0 = ActivityOptions.makeBasic();
            o0.setLaunchDisplayId(getActiveBuiltinDisplayId(this));
            startActivity(ti, o0.toBundle());
        } catch (Throwable ignored) {}
    }

    private Drawable cloneDrawable(Drawable d) {
        if (d == null) return null;
        try {
            Drawable.ConstantState cs = d.getConstantState();
            if (cs != null) {
                return cs.newDrawable(getResources()).mutate();
            }
        } catch (Throwable ignored) {}
        return d;
    }

    private Drawable getCachedIcon(PackageManager pm, String pkg) {
        Drawable d = mIconCache.get(pkg);
        if (d != null) return cloneDrawable(d);
        try {
            d = pm.getApplicationIcon(pkg);
            if (d != null) {
                mIconCache.put(pkg, d);
                return cloneDrawable(d);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String getCachedLabel(PackageManager pm, String pkg) {
        String lbl = mLabelCache.get(pkg);
        if (lbl != null) return lbl;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null) {
                lbl = cs.toString();
                mLabelCache.put(pkg, lbl);
                return lbl;
            }
        } catch (Throwable ignored) {}
        return pkg;
    }

    private void updateCursorOnExt(float x, float y, boolean pressing) {
        if (mCursorView == null || mCursorLp == null) return;
        try {
            if (!mCursorVisible) {
                mCursorVisible = true;
                mCursorView.setVisibility(View.VISIBLE);
            }
            mCursorView.setPressing(pressing);
            mCursorLp.x = Math.round(x) - 6;
            mCursorLp.y = Math.round(y) - 4;
            if (mInLauncherHost) {
                mCursorView.setTranslationX(mCursorLp.x);
                mCursorView.setTranslationY(mCursorLp.y);
                mCursorView.bringToFront();
            } else if (mExtWm != null) {
                mExtWm.updateViewLayout(mCursorView, mCursorLp);
            }
            mHandler.removeCallbacks(mHideCursorTask);
            mHandler.postDelayed(mHideCursorTask, 7000);
            if (mExtCtx != null && mDockAutoHide && !mIsOnLauncherHome && mOverlayH > 0) {
                int edgeZone = dp(mExtCtx, 18);
                int dockZone = dp(mExtCtx, 82);
                if (mDockHidden && y >= (mOverlayH - edgeZone)
                        && Math.abs(x - mOverlayW * 0.5f) <= dp(mExtCtx, 190)) {
                    wakeDockFromEdge();
                } else if (!mDockHidden && y >= (mOverlayH - dockZone)) {
                    mHandler.removeCallbacks(mAutoHideDockTask);
                    evaluateDockAutoHide(true);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void setCursorVisible(boolean vis) {
        mCursorVisible = vis;
        if (mCursorView != null) {
            mCursorView.setVisibility(vis ? View.VISIBLE : View.GONE);
        }
    }

    private void toggleDexBlackout() {
        boolean next = !mBlackoutActive;
        setDexBlackout(next);
        if (next) {
            setPanelOpen(false);
        }
    }

    private void setDexBlackout(boolean on) {
        if (on && mBlackoutActive && mBlackoutView != null) {
            updateBlackoutUi();
            return;
        }
        if (on) {
            BridgeClient.Status st = mLastStatus;
            if (st == null || !st.connected || st.mirrorMode != BridgeClient.MIRROR_MODE_DEX || st.vdId <= 0) {
                Toast.makeText(this, "仅在 DeX 扩展桌面已连接外接屏时可用", Toast.LENGTH_SHORT).show();
                updateBlackoutUi();
                return;
            }
            if (!attachBlackoutOverlay()) {
                mBlackoutActive = false;
                updateBlackoutUi();
                Toast.makeText(this, "请开启无障碍或悬浮窗权限后使用一键黑屏", Toast.LENGTH_SHORT).show();
                return;
            }
            mBlackoutActive = true;
        } else {
            mBlackoutActive = false;
            removeBlackoutOverlay();
        }
        updateBlackoutUi();
        final int arg = on ? 1 : 0;
        safeBgExecute(() -> {
            BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, arg, null);
            safePost(this::pollRemoteStateAsync);
        });
    }

    private boolean attachBlackoutOverlay() {
        if (mBlackoutView != null) return true;
        try {
            Display disp0 = (mDm != null) ? mDm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (disp0 == null) return false;
            NavAssistService a11y = NavAssistService.getInstance();
            int type;
            Context ctx0;
            if (a11y != null) {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
                ctx0 = a11y.createDisplayContext(disp0);
            } else if (Settings.canDrawOverlays(this)) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                ctx0 = createDisplayContext(disp0);
            } else {
                return false;
            }
            WindowManager wm0 = (WindowManager) ctx0.getSystemService(WINDOW_SERVICE);
            if (wm0 == null && a11y != null) wm0 = (WindowManager) a11y.getSystemService(WINDOW_SERVICE);
            if (wm0 == null) wm0 = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm0 == null) return false;

            final FrameLayout root = new FrameLayout(ctx0);
            root.setBackgroundColor(Color.BLACK);
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

            final TextView hint = new TextView(ctx0);
            hint.setText("主屏已进入省电黑屏（最低亮度）\n双击屏幕任意位置 · 或锁屏再解锁立即恢复");
            hint.setTextColor(0x99FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(dp(ctx0, 4), 1f);
            hint.setPadding(dp(ctx0, 24), dp(ctx0, 16), dp(ctx0, 24), dp(ctx0, 16));
            FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            root.addView(hint, hLp);

            if (mBlackoutHideHintTask != null && mHandler != null) {
                mHandler.removeCallbacks(mBlackoutHideHintTask);
            }
            mBlackoutHideHintTask = () -> {
                if (hint.getParent() != null) hint.setVisibility(View.GONE);
            };
            mLastBlackoutTapMs = 0L;
            root.setOnTouchListener((v, ev) -> {
                if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    long now = SystemClock.uptimeMillis();
                    if (now - mLastBlackoutTapMs <= 450L) {
                        if (mHandler != null && mBlackoutHideHintTask != null) {
                            mHandler.removeCallbacks(mBlackoutHideHintTask);
                        }
                        setDexBlackout(false);
                        return true;
                    }
                    mLastBlackoutTapMs = now;
                    hint.setVisibility(View.VISIBLE);
                    if (mHandler != null && mBlackoutHideHintTask != null) {
                        mHandler.removeCallbacks(mBlackoutHideHintTask);
                        mHandler.postDelayed(mBlackoutHideHintTask, 2600L);
                    }
                    return true;
                }
                return true;
            });
            if (mHandler != null) mHandler.postDelayed(mBlackoutHideHintTask, 3200L);

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type, flags, PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            lp.setTitle("ExtBlackout");
            wm0.addView(root, lp);
            mBlackoutView = root;
            mBlackoutWm = wm0;
            return true;
        } catch (Throwable t) {
            if (mHandler != null && mBlackoutHideHintTask != null) {
                mHandler.removeCallbacks(mBlackoutHideHintTask);
            }
            mBlackoutHideHintTask = null;
            t.printStackTrace();
            return false;
        }
    }

    private void removeBlackoutOverlay() {
        if (mHandler != null && mBlackoutHideHintTask != null) {
            mHandler.removeCallbacks(mBlackoutHideHintTask);
        }
        mBlackoutHideHintTask = null;
        if (mBlackoutView != null && mBlackoutWm != null) {
            try { mBlackoutWm.removeViewImmediate(mBlackoutView); } catch (Throwable ignored) {}
        }
        mBlackoutView = null;
        mBlackoutWm = null;
    }

    private void updateBlackoutUi() {
        if (mBtnPanelBlackout != null) {
            mBtnPanelBlackout.setText(mBlackoutActive ? "主屏已黑屏 · 点此恢复亮屏" : "一键黑屏 (主屏最低亮度)");
            HyperUi.styleHyperChip(mBtnPanelBlackout, mBlackoutActive);
            HyperUi.setStartIcon(mBtnPanelBlackout,
                    mBlackoutActive ? HyperUi.ICON_SUN : HyperUi.ICON_MOON,
                    mBlackoutActive ? Color.WHITE : 0xFFD8DEE9, 13.5f, 5f);
        }
    }

    private void detachExtOverlays(boolean keepBlackout) {
        cancelAutoHideTimer();
        if (!keepBlackout) {
            if (mBlackoutActive) {
                setDexBlackout(false);
            } else {
                removeBlackoutOverlay();
            }
        }
        if (mSplitDividerAnimator != null) {
            mSplitDividerAnimator.cancel();
            mSplitDividerAnimator = null;
        }
        if (mStageAnimView != null) {
            mStageAnimView.cancelActiveAnimation();
        }
        mForceWindowOverlaysDuringAnim = false;
        removeOverlayView(mStageAnimView);
        removeOverlayView(mSplitDividerRoot);
        removeOverlayView(mSplitMenuRoot);
        removeOverlayView(mTaskBubbleRoot);
        removeOverlayView(mDockEdgeTrigger);
        removeOverlayView(mDockRoot);
        removeOverlayView(mPanelRoot);
        removeOverlayView(mCursorView);
        mInLauncherHost = false;
        mExtWm = null;
        mExtCtx = null;
        mUsingA11yOverlay = false;
        mWinType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mStageAnimView = null;
        mStageAnimLp = null;
        mDockRoot = null;
        mDockEdgeTrigger = null;
        mDockEdgePill = null;
        mDockEdgeLp = null;
        mDockHidden = false;
        mDockHovered = false;
        mSplitDividerRoot = null;
        mSplitDividerPill = null;
        mSplitDividerLp = null;
        mSplitDragging = false;
        mSplitMenuRoot = null;
        mSplitMenuLp = null;
        mSplitMenuOpen = false;
        mTaskBubbleRoot = null;
        mTaskBubbleCard = null;
        mBubbleTriangle = null;
        mTaskBubbleLp = null;
        mTaskBubbleOpen = false;
        mBubbleIcon = null;
        mBubbleTitle = null;
        mBubbleModeTag = null;
        mBubbleFreeformBtn = null;
        mBubbleSplit0Btn = null;
        mBubbleSplit1Btn = null;
        mBubbleCloseBtn = null;
        mBubbleTask = null;
        mSplitSwapBtn = null;
        java.util.Arrays.fill(mSplitRatioBtns, null);
        mBtnDockSplit = null;
        mBtnPanelSplit = null;
        mPanelRoot = null;
        mPanelCard = null;
        mPanelOpen = false;
        mTrayPressed = false;
        mHandler.removeCallbacks(mOutsideDismissTask);
        mBtnToggle = null;
        mBtnPanelFullscreen = null;
        mBtnPanelDex = null;
        mBtnPanelMirror = null;
        mBtnPanelHideDock = null;
        mBtnPanelBlackout = null;
        mTaskStrip = null;
        mTaskDivider = null;
        mTrayBtn = null;
        mTrayWifiIcon = null;
        mTrayVolIcon = null;
        mTrayBattView = null;
        mTrayBattPct = null;
        mTileWifi = null;
        mTileBt = null;
        mTileInput = null;
        mTileAirplane = null;
        mTileTouchpad = null;
        mTilePowerSave = null;
        mVolSpeakerIcon = null;
        mVolSeekBar = null;
        mPanelBattView = null;
        mPanelBattText = null;
        mCursorView = null;
        mCursorVisible = false;
        mAttachedVdId = -1;
        mOverlayW = 0;
        mOverlayH = 0;
        mOverlayDpi = 0;
        mLastTaskSig = "";
    }

    private static int dp(Context ctx, float dpVal) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dpVal, ctx.getResources().getDisplayMetrics()));
    }

    private static View makeVerticalDivider(Context ctx) {
        View div = new View(ctx);
        div.setBackgroundColor(0x38FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, dp(ctx, 22));
        lp.leftMargin = dp(ctx, 7);
        lp.rightMargin = dp(ctx, 7);
        div.setLayoutParams(lp);
        return div;
    }

    private static void enforceSingleLine(TextView tv) {
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setIncludeFontPadding(false);
        tv.setEllipsize(TextUtils.TruncateAt.END);
    }

    private static TextView makeDockBtn(Context ctx, String txt, int color, int textColor) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        tv.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        enforceSingleLine(tv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(ctx, 13));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static class QuickTile {
        final LinearLayout root;
        final LinearLayout box;
        final View leftBtn;
        final View rightBtn;
        final View splitSep;
        final MiniIconView icon;
        final TextView arrow;
        final TextView label;
        final boolean hasArrow;
        final Context ctx;
        boolean active = false;

        QuickTile(Context ctx, int iconKind, String initialLabel, boolean hasArrow,
                  Runnable onToggle, Runnable onMore) {
            this.ctx = ctx;
            this.hasArrow = hasArrow;

            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44));
            boxLp.bottomMargin = dp(ctx, 5);
            root.addView(box, boxLp);

            LinearLayout leftWrap = new LinearLayout(ctx);
            leftWrap.setGravity(Gravity.CENTER);
            leftWrap.setOnClickListener(v -> { if (onToggle != null) onToggle.run(); });
            icon = new MiniIconView(ctx, iconKind, Color.WHITE);
            leftWrap.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)));
            box.addView(leftWrap, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            leftBtn = leftWrap;

            if (hasArrow) {
                splitSep = new View(ctx);
                splitSep.setBackgroundColor(0x28FFFFFF);
                box.addView(splitSep, new LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT));

                TextView arr = new TextView(ctx);
                arr.setText("›");
                arr.setTextColor(Color.WHITE);
                arr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                arr.setGravity(Gravity.CENTER);
                arr.setOnClickListener(v -> { if (onMore != null) onMore.run(); });
                box.addView(arr, new LinearLayout.LayoutParams(dp(ctx, 32), LinearLayout.LayoutParams.MATCH_PARENT));
                arrow = arr;
                rightBtn = arr;
            } else {
                splitSep = null;
                arrow = null;
                rightBtn = null;
            }

            label = new TextView(ctx);
            label.setText(initialLabel);
            label.setTextColor(0xFFE2E8F0);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            setActive(false, initialLabel);
        }

        private final GradientDrawable bg = new GradientDrawable();
        private boolean bgInit = false;

        void setActive(boolean on, String caption) {
            if (caption != null) label.setText(caption);
            if (bgInit && on == active) return;
            active = on;
            bgInit = true;

            bg.setColor(on ? HyperUi.HYPER_BLUE : HyperUi.TILE_INACTIVE);
            bg.setCornerRadius(dp(ctx, 14));
            bg.setStroke(1, on ? 0x48FFFFFF : 0x1AFFFFFF);
            box.setBackground(bg);

            int fg = Color.WHITE;
            icon.setTint(fg);
            if (arrow != null) arrow.setTextColor(fg);
            if (splitSep != null) splitSep.setBackgroundColor(on ? 0x38FFFFFF : 0x22FFFFFF);
        }
    }

    private static class MiniIconView extends View {
        static final int KIND_WIFI = 1;
        static final int KIND_BT = 2;
        static final int KIND_VOLUME = 3;
        static final int KIND_AIRPLANE = 4;
        static final int KIND_BATTERY_SAVER = 5;
        static final int KIND_DISPLAY_SWITCH = 6;
        static final int KIND_TOUCHPAD = 7;

        private final int mKind;
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mArcRect = new RectF();
        private final Path mPath = new Path();
        private int mColor;
        private int mVolLevel = 2; // 0=mute, 1=low, 2=high
        private boolean mActiveState = true;

        MiniIconView(Context ctx, int kind, int color) {
            super(ctx);
            mKind = kind;
            mColor = color;
        }

        void setTint(int c) {
            if (mColor != c) { mColor = c; invalidate(); }
        }

        void setVolumeLevel(int lvl) {
            if (mVolLevel != lvl) { mVolLevel = lvl; invalidate(); }
        }

        void setActiveState(boolean a) {
            if (mActiveState != a) { mActiveState = a; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            if (w <= 0 || h <= 0) return;
            c.save();
            c.translate(getPaddingLeft(), getPaddingTop());

            mP.setColor(mActiveState ? mColor : (mColor & 0x66FFFFFF));
            mP.setStrokeCap(Paint.Cap.ROUND);
            mP.setStrokeJoin(Paint.Join.ROUND);

            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float s = Math.min(w, h);

            if (mKind == KIND_WIFI) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.8f, s * 0.11f));
                float oy = h * 0.80f;
                mArcRect.set(cx - s * 0.44f, oy - s * 0.62f, cx + s * 0.44f, oy + s * 0.26f);
                c.drawArc(mArcRect, -135, 90, false, mP);
                mArcRect.set(cx - s * 0.28f, oy - s * 0.40f, cx + s * 0.28f, oy + s * 0.16f);
                c.drawArc(mArcRect, -135, 90, false, mP);
                mP.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, oy - s * 0.04f, Math.max(1.5f, s * 0.09f), mP);
                if (!mActiveState) {
                    mP.setColor(mColor);
                    mP.setStyle(Paint.Style.STROKE);
                    c.drawLine(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f, mP);
                }
            } else if (mKind == KIND_BT) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                mPath.reset();
                mPath.moveTo(cx - s * 0.16f, cy - s * 0.20f);
                mPath.lineTo(cx + s * 0.18f, cy + s * 0.16f);
                mPath.lineTo(cx, cy + s * 0.36f);
                mPath.lineTo(cx, cy - s * 0.36f);
                mPath.lineTo(cx + s * 0.18f, cy - s * 0.16f);
                mPath.lineTo(cx - s * 0.16f, cy + s * 0.20f);
                c.drawPath(mPath, mP);
            } else if (mKind == KIND_VOLUME) {
                mP.setColor(mColor);
                mP.setStyle(Paint.Style.FILL);
                mPath.reset();
                mPath.moveTo(w * 0.14f, h * 0.38f);
                mPath.lineTo(w * 0.32f, h * 0.38f);
                mPath.lineTo(w * 0.54f, h * 0.20f);
                mPath.lineTo(w * 0.54f, h * 0.80f);
                mPath.lineTo(w * 0.32f, h * 0.62f);
                mPath.lineTo(w * 0.14f, h * 0.62f);
                mPath.close();
                c.drawPath(mPath, mP);

                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.6f, s * 0.10f));
                if (mVolLevel <= 0) {
                    c.drawLine(w * 0.64f, h * 0.36f, w * 0.86f, h * 0.64f, mP);
                    c.drawLine(w * 0.86f, h * 0.36f, w * 0.64f, h * 0.64f, mP);
                } else {
                    mArcRect.set(w * 0.50f, h * 0.32f, w * 0.72f, h * 0.68f);
                    c.drawArc(mArcRect, -50, 100, false, mP);
                    if (mVolLevel >= 2) {
                        mArcRect.set(w * 0.52f, h * 0.18f, w * 0.88f, h * 0.82f);
                        c.drawArc(mArcRect, -52, 104, false, mP);
                    }
                }
            } else if (mKind == KIND_AIRPLANE) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.8f, s * 0.10f));
                c.drawLine(w * 0.15f, cy, w * 0.85f, cy, mP);
                c.drawLine(w * 0.42f, h * 0.16f, w * 0.56f, cy, mP);
                c.drawLine(w * 0.42f, h * 0.84f, w * 0.56f, cy, mP);
                c.drawLine(w * 0.18f, h * 0.34f, w * 0.26f, cy, mP);
                c.drawLine(w * 0.18f, h * 0.66f, w * 0.26f, cy, mP);
            } else if (mKind == KIND_BATTERY_SAVER) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                mArcRect.set(w * 0.16f, h * 0.26f, w * 0.82f, h * 0.74f);
                c.drawRoundRect(mArcRect, s * 0.1f, s * 0.1f, mP);
                c.drawLine(w * 0.85f, h * 0.40f, w * 0.85f, h * 0.60f, mP);
                c.drawLine(w * 0.38f, h * 0.50f, w * 0.60f, h * 0.50f, mP);
                c.drawLine(w * 0.49f, h * 0.38f, w * 0.49f, h * 0.62f, mP);
            } else if (mKind == KIND_DISPLAY_SWITCH) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                mArcRect.set(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.68f);
                c.drawRoundRect(mArcRect, s * 0.1f, s * 0.1f, mP);
                c.drawLine(w * 0.34f, h * 0.82f, w * 0.66f, h * 0.82f, mP);
                c.drawLine(cx, h * 0.68f, cx, h * 0.82f, mP);
            } else if (mKind == KIND_TOUCHPAD) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Math.max(1.7f, s * 0.10f));
                mArcRect.set(w * 0.16f, h * 0.18f, w * 0.84f, h * 0.82f);
                c.drawRoundRect(mArcRect, s * 0.12f, s * 0.12f, mP);
                c.drawLine(w * 0.16f, h * 0.64f, w * 0.84f, h * 0.64f, mP);
                c.drawLine(cx, h * 0.64f, cx, h * 0.82f, mP);
            }
            c.restore();
        }
    }

    private static class MiniBatteryView extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private int mPct = 100;
        private boolean mCharging = false;

        MiniBatteryView(Context ctx) {
            super(ctx);
        }

        void setBattery(int pct, boolean charging) {
            if (mPct != pct || mCharging != charging) {
                mPct = pct;
                mCharging = charging;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float bodyR = w * 0.86f;
            float stroke = Math.max(1.4f, h * 0.11f);
            mP.setStyle(Paint.Style.STROKE);
            mP.setStrokeWidth(stroke);
            mP.setColor(0xFFE2E8F0);
            mRect.set(stroke, stroke, bodyR, h - stroke);
            c.drawRoundRect(mRect, h * 0.18f, h * 0.18f, mP);

            mP.setStyle(Paint.Style.FILL);
            mRect.set(bodyR + stroke * 0.5f, h * 0.30f, w - 1f, h * 0.70f);
            c.drawRoundRect(mRect, 1.5f, 1.5f, mP);

            int fillCol = mCharging ? 0xFF4ADE80 : (mPct <= 20 ? 0xFFF87171 : 0xFFE2E8F0);
            mP.setColor(fillCol);
            float pad = stroke * 1.6f;
            float avail = Math.max(0f, bodyR - pad * 2f);
            mRect.set(pad, pad, pad + avail * (mPct / 100f), h - pad);
            c.drawRoundRect(mRect, h * 0.10f, h * 0.10f, mP);
        }
    }

    private static class CursorView extends View {
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mArrow = new Path();
        private boolean mPressing = false;

        CursorView(Context ctx) {
            super(ctx);
            mFill.setColor(0xFF0F172A);
            mFill.setStyle(Paint.Style.FILL);
            mStroke.setColor(Color.WHITE);
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeWidth(2.6f);
            mStroke.setStrokeJoin(Paint.Join.ROUND);

            mRing.setColor(0x9938BDF8);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(2.5f);

            mArrow.moveTo(6f, 4f);
            mArrow.lineTo(6f, 34f);
            mArrow.lineTo(13.5f, 26.5f);
            mArrow.lineTo(19f, 38f);
            mArrow.lineTo(23.5f, 36f);
            mArrow.lineTo(18f, 24.5f);
            mArrow.lineTo(28f, 24.5f);
            mArrow.close();
        }

        void setPressing(boolean p) {
            if (mPressing != p) {
                mPressing = p;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mPressing) {
                canvas.drawCircle(6f, 6f, 10f, mRing);
            }
            canvas.drawPath(mArrow, mFill);
            canvas.drawPath(mArrow, mStroke);
        }
    }

    public static List<String> getMirrorPresetSlots(Context ctx) {
        String raw = getMirrorPresetSlotsRaw(ctx);
        ArrayList<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String k = s.trim();
            if (!k.isEmpty() && BridgeClient.mirrorPresetIndex(k) >= 0) {
                out.add(k);
            }
        }
        if (out.isEmpty()) {
            out.addAll(Arrays.asList(BridgeClient.DEFAULT_MIRROR_PRESETS.split(",")));
        }
        return out;
    }

    public static String getMirrorPresetSlotsRaw(Context ctx) {
        if (ctx == null) return BridgeClient.DEFAULT_MIRROR_PRESETS;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String v = sp.getString(PREF_MIRROR_PRESETS, BridgeClient.DEFAULT_MIRROR_PRESETS);
        if (!sp.getBoolean("migrated_screendim_v340", false)) {
            String cur = sp.getString(PREF_MIRROR_PRESETS, null);
            if (cur != null && !cur.trim().isEmpty()
                    && !Arrays.asList(cur.split(",")).contains("screendim")) {
                v = cur.trim() + ",screendim";
                sp.edit().putString(PREF_MIRROR_PRESETS, v)
                        .putBoolean("migrated_screendim_v340", true).apply();
            } else {
                sp.edit().putBoolean("migrated_screendim_v340", true).apply();
            }
        }
        return (v == null || v.trim().isEmpty()) ? BridgeClient.DEFAULT_MIRROR_PRESETS : v;
    }

    public static void setMirrorPresetSlots(Context ctx, List<String> slots) {
        if (ctx == null) return;
        StringBuilder sb = new StringBuilder();
        if (slots != null) {
            for (String s : slots) {
                String k = s != null ? s.trim() : "";
                if (!k.isEmpty() && BridgeClient.mirrorPresetIndex(k) >= 0) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(k);
                }
            }
        }
        String joined = sb.length() > 0 ? sb.toString() : BridgeClient.DEFAULT_MIRROR_PRESETS;
        ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putString(PREF_MIRROR_PRESETS, joined).apply();
        BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.post(svc::pollRemoteStateAsync);
        }
    }

    public static String getMirrorMemoText(Context ctx) {
        if (ctx == null) return "• 点击控制台可随时编辑便签文字\n• 支持虚拟触控板点按滑控小组件";
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String v = sp.getString(PREF_MIRROR_MEMO, "• 点击控制台可随时编辑便签文字\n• 支持虚拟触控板点按滑控小组件");
        return v != null ? v : "";
    }

    public static void setMirrorMemoText(Context ctx, String txt) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putString(PREF_MIRROR_MEMO, txt != null ? txt : "").apply();
        BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.post(svc::pollRemoteStateAsync);
        }
    }

    public static int getMirrorWidgetId(Context ctx, int slot) {
        if (ctx == null) return -1;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        return sp.getInt(slot == 0 ? PREF_MIRROR_WIDGET_LEFT : PREF_MIRROR_WIDGET_RIGHT, -1);
    }

    public static void setMirrorWidgetId(Context ctx, int slot, int widgetId) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String key = (slot == 0) ? PREF_MIRROR_WIDGET_LEFT : PREF_MIRROR_WIDGET_RIGHT;
        int prevId = sp.getInt(key, -1);
        if (prevId > 0 && prevId != widgetId) {
            try {
                BridgeService svc = sInstance;
                AppWidgetHost host = (svc != null && svc.mAppWidgetHost != null)
                        ? svc.mAppWidgetHost
                        : new AppWidgetHost(ctx.getApplicationContext(), MIRROR_WIDGET_HOST_ID);
                host.deleteAppWidgetId(prevId);
            } catch (Throwable ignored) {}
        }
        sp.edit().putInt(key, widgetId).apply();
        BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.post(svc::pollRemoteStateAsync);
        }
    }

    public static String getMirrorThemeId(Context ctx) {
        if (ctx == null) return "night";
        String id = ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(PREF_MIRROR_THEME, "night");
        return (id != null && !id.isEmpty()) ? id : "night";
    }

    public static void setMirrorThemeId(Context ctx, String id) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putString(PREF_MIRROR_THEME, id != null ? id : "night").apply();
        BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.post(svc::pollRemoteStateAsync);
        }
    }

    public static int getMirrorCustomBg(Context ctx) {
        if (ctx == null) return 0xFF1E293B;
        return ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(PREF_MIRROR_CUSTOM_BG, 0xFF1E293B);
    }

    public static void setMirrorCustomBg(Context ctx, int bgColor) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_MIRROR_THEME, "custom")
                .putInt(PREF_MIRROR_CUSTOM_BG, 0xFF000000 | (bgColor & 0x00FFFFFF))
                .apply();
        BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.post(svc::pollRemoteStateAsync);
        }
    }

    public static BridgeClient.MirrorTheme getActiveMirrorTheme(Context ctx) {
        return BridgeClient.resolveMirrorTheme(getMirrorThemeId(ctx), getMirrorCustomBg(ctx));
    }

    public static int getMirrorCursorSpeedIdx(Context ctx) {
        if (ctx == null) return 2;
        int idx = ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(PREF_MIRROR_CURSOR_SPEED, 2);
        return Math.max(0, Math.min(BridgeClient.MIRROR_SPEED_MULTS.length - 1, idx));
    }

    public static float getMirrorCursorSpeedMult(Context ctx) {
        return BridgeClient.MIRROR_SPEED_MULTS[getMirrorCursorSpeedIdx(ctx)];
    }

    public static void setMirrorCursorSpeedIdx(Context ctx, int idx) {
        if (ctx == null) return;
        int clamped = Math.max(0, Math.min(BridgeClient.MIRROR_SPEED_MULTS.length - 1, idx));
        ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putInt(PREF_MIRROR_CURSOR_SPEED, clamped).apply();
    }

    public static int getMirrorWidgetRatio(Context ctx, int slot) {
        if (ctx == null) return 0;
        String k = (slot == 0) ? PREF_MIRROR_WIDGET_RATIO_L : PREF_MIRROR_WIDGET_RATIO_R;
        int mode = ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(k, 0);
        return Math.max(0, Math.min(BridgeClient.MIRROR_WIDGET_RATIO_LABELS.length - 1, mode));
    }

    public static void setMirrorWidgetRatio(Context ctx, int slot, int mode) {
        if (ctx == null) return;
        String k = (slot == 0) ? PREF_MIRROR_WIDGET_RATIO_L : PREF_MIRROR_WIDGET_RATIO_R;
        int clamped = Math.max(0, Math.min(BridgeClient.MIRROR_WIDGET_RATIO_LABELS.length - 1, mode));
        ctx.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putInt(k, clamped).apply();
        BridgeService svc = sInstance;
        if (svc != null && svc.mHandler != null) {
            svc.mHandler.post(svc::pollRemoteStateAsync);
        }
    }

    private void ensureWidgetHost() {
        if (mAppWidgetMgr == null) {
            mAppWidgetMgr = AppWidgetManager.getInstance(this);
        }
        if (mAppWidgetHost == null) {
            mAppWidgetHost = new AppWidgetHost(getApplicationContext(), MIRROR_WIDGET_HOST_ID);
        }
    }

    private void attachMirrorSidebars(BridgeClient.Status st, int leftWidgetId, int rightWidgetId) {
        if (!attachMirrorSidebarsInternal(st, leftWidgetId, rightWidgetId, true)
                && Settings.canDrawOverlays(this)) {
            attachMirrorSidebarsInternal(st, leftWidgetId, rightWidgetId, false);
        }
    }

    private boolean attachMirrorSidebarsInternal(
            BridgeClient.Status st, int leftWidgetId, int rightWidgetId, boolean preferA11y) {
        Display target = null;
        for (Display d : mDm.getDisplays()) {
            if (d != null && d.getDisplayId() == st.sideVdId) {
                target = d;
                break;
            }
        }
        if (target == null) {
            if (mSideAttachedVdId != -1 && mSideAttachedVdId != st.sideVdId) {
                detachMirrorSidebars();
            }
            return true;
        }

        int leftW = Math.max(0, st.mirX);
        int rightW = Math.max(0, st.physW - (st.mirX + st.mirW));
        int topH = Math.max(0, st.mirY);
        int bottomH = Math.max(0, st.physH - (st.mirY + st.mirH));

        final int minWing = 48;
        boolean hasHoriz = (leftW >= minWing || rightW >= minWing);
        boolean hasVert = !hasHoriz && (topH >= minWing || bottomH >= minWing);
        if (!hasHoriz && !hasVert) {
            detachMirrorSidebars();
            return true;
        }

        boolean singleWing = hasHoriz
                ? ((leftW < minWing && rightW >= minWing) || (rightW < minWing && leftW >= minWing))
                : ((topH < minWing && bottomH >= minWing) || (bottomH < minWing && topH >= minWing));

        NavAssistService a11y = NavAssistService.getInstance();
        boolean displayTrusted = (target.getFlags() & 0x80 /* Display.FLAG_TRUSTED */) != 0;
        int winType;
        Context dispCtx;
        if (preferA11y) {
            if (!displayTrusted || a11y == null) {
                return false;
            }
            winType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            dispCtx = a11y.createDisplayContext(target);
        } else if (Settings.canDrawOverlays(this)) {
            winType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            dispCtx = createDisplayContext(target);
        } else {
            return true;
        }

        // Resolution-agnostic DPI scaling across 720p/1080p/2K/4K, 16:9/16:10/3:2/21:9 & portrait monitors
        float resScale = Math.max(0.50f, Math.min(1.65f, Math.min(st.physW, st.physH) / 1440f));
        float baseDpi;
        if (hasVert) {
            int refH = Math.max(topH, bottomH);
            float normH = refH / resScale;
            baseDpi = (normH >= 700f) ? 265f : Math.max(145f, Math.min(235f, normH * 190f / 400f));
        } else {
            int refW = Math.max(leftW, rightW);
            float normW = refW / resScale;
            if (singleWing) {
                if (normW >= 700f) baseDpi = 270f;
                else if (normW >= 380f) baseDpi = 220f;
                else baseDpi = Math.max(145f, Math.min(190f, normW * 164f / 256f));
            } else {
                baseDpi = (normW >= 400f) ? 235f : Math.max(115f, Math.min(155f, normW * 132f / 128f));
            }
        }
        int sideDpi = Math.max(120, Math.min(360, Math.round(baseDpi * resScale)));

        Context uiCtx = dispCtx;
        try {
            android.content.res.Configuration cfg = new android.content.res.Configuration(
                    dispCtx.getResources().getConfiguration());
            cfg.densityDpi = sideDpi;
            cfg.fontScale = 1.0f;
            uiCtx = dispCtx.createConfigurationContext(cfg);
        } catch (Throwable ignored) {}

        List<String> presetKeys = getMirrorPresetSlots(this);
        String presetSig = getMirrorPresetSlotsRaw(this);
        String memoTxt = getMirrorMemoText(this);
        String themeId = getMirrorThemeId(this);
        int customBg = getMirrorCustomBg(this);
        mSideTheme = BridgeClient.resolveMirrorTheme(themeId, customBg);
        String themeSig = themeId + ":" + Integer.toHexString(customBg);
        String ratioSig = getMirrorWidgetRatio(this, 0) + ":" + getMirrorWidgetRatio(this, 1);

        final LinearLayout oldLeftRoot = mMirLeftRoot;
        final LinearLayout oldRightRoot = mMirRightRoot;
        final WindowManager oldWm = mSideWm;
        if (mHandler != null) mHandler.removeCallbacks(mPomoTickTask);
        resetMirrorSideChildRefs();

        WindowManager newWm = (WindowManager) dispCtx.getSystemService(WINDOW_SERVICE);
        ensureWidgetHost();
        if ((leftWidgetId > 0 || rightWidgetId > 0) && mAppWidgetHost != null && !mWidgetHostListening) {
            try {
                mAppWidgetHost.startListening();
                mWidgetHostListening = true;
            } catch (Throwable ignored) {}
        }

        int baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        LinearLayout newLeftRoot = null;
        LinearLayout newRightRoot = null;
        int nLx = 0, nLy = 0, nLw = 0, nLh = 0;
        int nRx = 0, nRy = 0, nRw = 0, nRh = 0;

        try {
            if (singleWing) {
                int sx = hasHoriz ? ((leftW < minWing) ? (st.mirX + st.mirW) : 0) : 0;
                int sy = hasVert ? ((topH < minWing) ? (st.mirY + st.mirH) : 0) : 0;
                int sw = hasHoriz ? ((leftW < minWing) ? rightW : leftW) : st.physW;
                int sh = hasVert ? ((topH < minWing) ? bottomH : topH) : st.physH;
                newLeftRoot = buildMirrorUnifiedSidebar(uiCtx, sw, sh, leftWidgetId, rightWidgetId, presetKeys);
                nLx = sx; nLy = sy; nLw = sw; nLh = sh;
                WindowManager.LayoutParams lpU = new WindowManager.LayoutParams(
                        sw, sh, winType, baseFlags, PixelFormat.OPAQUE);
                lpU.gravity = Gravity.TOP | Gravity.LEFT;
                lpU.x = sx;
                lpU.y = sy;
                lpU.setTitle("ExtMirrorUnified");
                newWm.addView(newLeftRoot, lpU);
            } else {
                int mid = (presetKeys.size() + 1) / 2;
                List<String> firstHalf = new ArrayList<>(presetKeys.subList(0, Math.min(mid, presetKeys.size())));
                List<String> secondHalf = new ArrayList<>(presetKeys.subList(Math.min(mid, presetKeys.size()), presetKeys.size()));
                if (hasHoriz) {
                    if (leftW >= minWing) {
                        newLeftRoot = buildMirrorDualWing(uiCtx, leftW, st.physH, leftWidgetId, true, firstHalf);
                        nLx = 0; nLy = 0; nLw = leftW; nLh = st.physH;
                        WindowManager.LayoutParams lpL = new WindowManager.LayoutParams(
                                leftW, st.physH, winType, baseFlags, PixelFormat.OPAQUE);
                        lpL.gravity = Gravity.TOP | Gravity.LEFT;
                        lpL.x = 0;
                        lpL.y = 0;
                        lpL.setTitle("ExtMirrorLeft");
                        newWm.addView(newLeftRoot, lpL);
                    }
                    if (rightW >= minWing) {
                        newRightRoot = buildMirrorDualWing(uiCtx, rightW, st.physH, rightWidgetId, false, secondHalf);
                        nRx = st.mirX + st.mirW; nRy = 0; nRw = rightW; nRh = st.physH;
                        WindowManager.LayoutParams lpR = new WindowManager.LayoutParams(
                                rightW, st.physH, winType, baseFlags, PixelFormat.OPAQUE);
                        lpR.gravity = Gravity.TOP | Gravity.LEFT;
                        lpR.x = st.mirX + st.mirW;
                        lpR.y = 0;
                        lpR.setTitle("ExtMirrorRight");
                        newWm.addView(newRightRoot, lpR);
                    }
                } else {
                    if (topH >= minWing) {
                        newLeftRoot = buildMirrorDualWing(uiCtx, st.physW, topH, leftWidgetId, true, firstHalf);
                        nLx = 0; nLy = 0; nLw = st.physW; nLh = topH;
                        WindowManager.LayoutParams lpT = new WindowManager.LayoutParams(
                                st.physW, topH, winType, baseFlags, PixelFormat.OPAQUE);
                        lpT.gravity = Gravity.TOP | Gravity.LEFT;
                        lpT.x = 0;
                        lpT.y = 0;
                        lpT.setTitle("ExtMirrorTop");
                        newWm.addView(newLeftRoot, lpT);
                    }
                    if (bottomH >= minWing) {
                        newRightRoot = buildMirrorDualWing(uiCtx, st.physW, bottomH, rightWidgetId, false, secondHalf);
                        nRx = 0; nRy = st.mirY + st.mirH; nRw = st.physW; nRh = bottomH;
                        WindowManager.LayoutParams lpB = new WindowManager.LayoutParams(
                                st.physW, bottomH, winType, baseFlags, PixelFormat.OPAQUE);
                        lpB.gravity = Gravity.TOP | Gravity.LEFT;
                        lpB.x = 0;
                        lpB.y = st.mirY + st.mirH;
                        lpB.setTitle("ExtMirrorBottom");
                        newWm.addView(newRightRoot, lpB);
                    }
                }
            }

            if (oldLeftRoot != null && oldWm != null) {
                try { oldWm.removeViewImmediate(oldLeftRoot); } catch (Throwable ignored) {}
            }
            if (oldRightRoot != null && oldWm != null) {
                try { oldWm.removeViewImmediate(oldRightRoot); } catch (Throwable ignored) {}
            }

            mLastObservedA11y = (a11y != null);
            mSideCtx = uiCtx;
            mSideWm = newWm;
            mMirLeftRoot = newLeftRoot;
            mMirRightRoot = newRightRoot;
            mSideLeftX = nLx; mSideLeftY = nLy; mSideLeftW = nLw; mSideLeftH = nLh;
            mSideRightX = nRx; mSideRightY = nRy; mSideRightW = nRw; mSideRightH = nRh;
            mSideAttachedVdId = st.sideVdId;
            mSidePhysW = st.physW;
            mSidePhysH = st.physH;
            mSideMirX = st.mirX;
            mSideMirY = st.mirY;
            mSideMirW = st.mirW;
            mSideMirH = st.mirH;
            mSideModeAttached = st.mirrorSideMode;
            mSideAttachedLeftWidgetId = leftWidgetId;
            mSideAttachedRightWidgetId = rightWidgetId;
            mSidePresetSigAttached = presetSig;
            mSideMemoAttached = memoTxt;
            mSideThemeSigAttached = themeSig;
            mSideWidgetRatioSigAttached = ratioSig;
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            if (newLeftRoot != null && newWm != null) {
                try { newWm.removeViewImmediate(newLeftRoot); } catch (Throwable ignored) {}
            }
            if (newRightRoot != null && newWm != null) {
                try { newWm.removeViewImmediate(newRightRoot); } catch (Throwable ignored) {}
            }
            if (!preferA11y) {
                detachMirrorSidebars();
                mLastObservedA11y = (a11y != null);
            }
            return false;
        }
    }

    private LinearLayout makeMirrorCard(Context ctx, boolean compact) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mSideTheme.cardBgColor);
        bg.setCornerRadius(dp(ctx, compact ? 11 : 14));
        bg.setStroke(1, mSideTheme.cardStrokeColor);
        card.setBackground(bg);
        int ph = dp(ctx, compact ? 9 : 13);
        int pv = dp(ctx, compact ? 8 : 11);
        card.setPadding(ph, pv, ph, pv);
        return card;
    }

    private TextView makeMirPillBtn(Context ctx, String text, int bgColor, int fgColor, boolean compact) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextColor(fgColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 11.5f : 12.5f);
        btn.getPaint().setFakeBoldText(true);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(ctx, 8));
        bg.setStroke(1, mSideTheme.cardStrokeColor);
        btn.setBackground(bg);
        btn.setPadding(dp(ctx, compact ? 7 : 10), dp(ctx, compact ? 4 : 6), dp(ctx, compact ? 7 : 10), dp(ctx, compact ? 4 : 6));
        return btn;
    }

    private void setMirPillBg(TextView btn, int bgColor) {
        if (btn == null) return;
        android.graphics.drawable.Drawable d = btn.getBackground();
        if (d instanceof GradientDrawable) {
            ((GradientDrawable) d).setColor(bgColor);
        }
    }

    private TextView makeMirLabel(Context ctx, String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.getPaint().setFakeBoldText(true);
        return tv;
    }

    private LinearLayout buildMirPresetCard(Context ctx, String key, boolean compact) {
        if ("battery".equals(key)) return buildMirBattCard(ctx, compact);
        if ("wifi".equals(key)) return buildMirWifiCard(ctx, compact);
        if ("bt".equals(key)) return buildMirBtCard(ctx, compact);
        if ("audio".equals(key)) return buildMirAudioCard(ctx, compact);
        if ("media".equals(key)) return buildMirMediaCard(ctx, compact);
        if ("calendar".equals(key)) return buildMirCalCard(ctx, compact);
        if ("perf".equals(key)) return buildMirPerfCard(ctx, compact);
        if ("netspeed".equals(key)) return buildMirNetCard(ctx, compact);
        if ("storage".equals(key)) return buildMirStorageCard(ctx, compact);
        if ("pomodoro".equals(key)) return buildMirPomodoroCard(ctx, compact);
        if ("memo".equals(key)) return buildMirMemoCard(ctx, compact);
        if ("quickctrl".equals(key)) return buildMirQuickCtrlCard(ctx, compact);
        if ("screendim".equals(key)) return buildMirScreenDimCard(ctx, compact);
        return buildMirClockCard(ctx, compact);
    }

    private LinearLayout buildMirClockCard(Context ctx, boolean compact) {
        LinearLayout clockCard = makeMirrorCard(ctx, compact);
        TextClock clk = new TextClock(ctx);
        clk.setFormat24Hour("HH:mm");
        clk.setFormat12Hour("HH:mm");
        clk.setTextColor(mSideTheme.titleColor);
        clk.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 30f : 36f);
        clk.getPaint().setFakeBoldText(true);
        clk.setGravity(compact ? Gravity.CENTER : Gravity.LEFT);
        clockCard.addView(clk, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextClock dateClk = new TextClock(ctx);
        dateClk.setFormat24Hour("M/d EEEE");
        dateClk.setFormat12Hour("M/d EEEE");
        dateClk.setTextColor(mSideTheme.subColor);
        dateClk.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12.5f : 14.5f);
        dateClk.setGravity(compact ? Gravity.CENTER : Gravity.LEFT);
        clockCard.addView(dateClk, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return clockCard;
    }

    private LinearLayout buildMirBattCard(Context ctx, boolean compact) {
        LinearLayout battCard = makeMirrorCard(ctx, compact);
        LinearLayout battRow = new LinearLayout(ctx);
        battRow.setOrientation(LinearLayout.HORIZONTAL);
        battRow.setGravity(Gravity.CENTER_VERTICAL);
        mMirBattIcon = new MiniBatteryView(ctx);
        battRow.addView(mMirBattIcon, new LinearLayout.LayoutParams(dp(ctx, compact ? 24 : 28), dp(ctx, compact ? 14 : 16)));
        mMirBattMain = makeMirLabel(ctx, "100%", compact ? 15.5f : 17.5f, mSideTheme.lightMode ? 0xFF16A34A : 0xFF4ADE80, true);
        LinearLayout.LayoutParams bmLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bmLp.leftMargin = dp(ctx, 7);
        battRow.addView(mMirBattMain, bmLp);
        battCard.addView(battRow);

        mMirBattSub = makeMirLabel(ctx, "电池状态读取中", compact ? 11.5f : 13f, mSideTheme.subColor, false);
        mMirBattSub.setMaxLines(2);
        mMirBattSub.setPadding(0, dp(ctx, 3), 0, 0);
        battCard.addView(mMirBattSub);
        return battCard;
    }

    private LinearLayout buildMirWifiCard(Context ctx, boolean compact) {
        LinearLayout wifiCard = makeMirrorCard(ctx, compact);
        LinearLayout wifiRow = new LinearLayout(ctx);
        wifiRow.setOrientation(LinearLayout.HORIZONTAL);
        wifiRow.setGravity(Gravity.CENTER_VERTICAL);
        mMirWifiIcon = new MiniIconView(ctx, 1, mSideTheme.accentColor);
        wifiRow.addView(mMirWifiIcon, new LinearLayout.LayoutParams(dp(ctx, compact ? 18 : 20), dp(ctx, compact ? 18 : 20)));
        mMirWifiMain = makeMirLabel(ctx, "Wi-Fi", compact ? 14f : 15.5f, mSideTheme.titleColor, true);
        mMirWifiMain.setSingleLine(true);
        mMirWifiMain.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams wmLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wmLp.leftMargin = dp(ctx, 7);
        wifiRow.addView(mMirWifiMain, wmLp);
        wifiCard.addView(wifiRow);

        mMirWifiSub = makeMirLabel(ctx, "检测中", compact ? 11.5f : 13f, mSideTheme.subColor, false);
        mMirWifiSub.setMaxLines(2);
        mMirWifiSub.setEllipsize(TextUtils.TruncateAt.END);
        mMirWifiSub.setPadding(0, dp(ctx, 3), 0, 0);
        wifiCard.addView(mMirWifiSub);
        return wifiCard;
    }

    private LinearLayout buildMirBtCard(Context ctx, boolean compact) {
        LinearLayout btCard = makeMirrorCard(ctx, compact);
        LinearLayout btRow = new LinearLayout(ctx);
        btRow.setOrientation(LinearLayout.HORIZONTAL);
        btRow.setGravity(Gravity.CENTER_VERTICAL);
        mMirBtIcon = new MiniIconView(ctx, 2, mSideTheme.lightMode ? 0xFF4F46E5 : 0xFF818CF8);
        btRow.addView(mMirBtIcon, new LinearLayout.LayoutParams(dp(ctx, compact ? 18 : 20), dp(ctx, compact ? 18 : 20)));
        mMirBtMain = makeMirLabel(ctx, "蓝牙", compact ? 14f : 15.5f, mSideTheme.titleColor, true);
        mMirBtMain.setSingleLine(true);
        mMirBtMain.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams btmLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btmLp.leftMargin = dp(ctx, 7);
        btRow.addView(mMirBtMain, btmLp);
        btCard.addView(btRow);

        mMirBtSub = makeMirLabel(ctx, "无已连设备", compact ? 11.5f : 13f, mSideTheme.subColor, false);
        mMirBtSub.setMaxLines(2);
        mMirBtSub.setEllipsize(TextUtils.TruncateAt.END);
        mMirBtSub.setPadding(0, dp(ctx, 3), 0, 0);
        btCard.addView(mMirBtSub);
        return btCard;
    }

    private LinearLayout buildMirAudioCard(Context ctx, boolean compact) {
        LinearLayout audCard = makeMirrorCard(ctx, compact);
        LinearLayout audRow = new LinearLayout(ctx);
        audRow.setOrientation(LinearLayout.HORIZONTAL);
        audRow.setGravity(Gravity.CENTER_VERTICAL);
        mMirAudioIcon = new MiniIconView(ctx, MiniIconView.KIND_VOLUME, mSideTheme.accentColor);
        audRow.addView(mMirAudioIcon, new LinearLayout.LayoutParams(dp(ctx, compact ? 18 : 20), dp(ctx, compact ? 18 : 20)));
        mMirAudioRoute = makeMirLabel(ctx, "音频通道", compact ? 13.5f : 15f, mSideTheme.titleColor, true);
        mMirAudioRoute.setMaxLines(2);
        mMirAudioRoute.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        arLp.leftMargin = dp(ctx, 7);
        audRow.addView(mMirAudioRoute, arLp);
        audCard.addView(audRow);

        mMirAudioSub = makeMirLabel(ctx, "媒体音量", compact ? 11.5f : 12.5f, mSideTheme.subColor, false);
        mMirAudioSub.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
        audCard.addView(mMirAudioSub);

        mMirVolProg = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        mMirVolProg.setMax(100);
        mMirVolProg.setProgress(50);
        try {
            mMirVolProg.getProgressDrawable().setColorFilter(mSideTheme.accentColor, PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) {}
        audCard.addView(mMirVolProg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6)));

        LinearLayout ctrlRow = new LinearLayout(ctx);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctrlRow.setPadding(0, dp(ctx, 6), 0, 0);
        TextView btnVolDown = makeMirPillBtn(ctx, "音量 -", mSideTheme.pillBgColor, mSideTheme.titleColor, compact);
        btnVolDown.setOnClickListener(v -> adjustMirVolume(AudioManager.ADJUST_LOWER));
        TextView btnMute = makeMirPillBtn(ctx, "静音", mSideTheme.pillBgColor, mSideTheme.titleColor, compact);
        btnMute.setOnClickListener(v -> adjustMirVolume(AudioManager.ADJUST_TOGGLE_MUTE));
        TextView btnVolUp = makeMirPillBtn(ctx, "音量 +", mSideTheme.pillBgColor, mSideTheme.titleColor, compact);
        btnVolUp.setOnClickListener(v -> adjustMirVolume(AudioManager.ADJUST_RAISE));
        LinearLayout.LayoutParams bLp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bLp1.rightMargin = dp(ctx, 4);
        LinearLayout.LayoutParams bLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bLp2.rightMargin = dp(ctx, 4);
        ctrlRow.addView(btnVolDown, bLp1);
        ctrlRow.addView(btnMute, bLp2);
        ctrlRow.addView(btnVolUp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        audCard.addView(ctrlRow);
        return audCard;
    }

    private void adjustMirVolume(int dir) {
        try {
            if (mAm != null) {
                mAm.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);
                refreshMirrorSideViews(mLastStatus);
            }
        } catch (Throwable ignored) {}
    }

    private LinearLayout buildMirMediaCard(Context ctx, boolean compact) {
        LinearLayout mediaCard = makeMirrorCard(ctx, compact);
        mMirMediaBadge = makeMirLabel(ctx, "媒体播放", compact ? 12f : 13.5f,
                mSideTheme.lightMode ? 0xFF7C3AED : 0xFFA78BFA, true);
        HyperUi.setStartIcon(mMirMediaBadge, HyperUi.ICON_MEDIA,
                mSideTheme.lightMode ? 0xFF7C3AED : 0xFFA78BFA, 13f, 5f);
        mediaCard.addView(mMirMediaBadge);

        mMirMediaTitle = makeMirLabel(ctx, "当前暂无播放媒体", compact ? 13.5f : 15f, mSideTheme.titleColor, true);
        mMirMediaTitle.setMaxLines(2);
        mMirMediaTitle.setEllipsize(TextUtils.TruncateAt.END);
        mMirMediaTitle.setPadding(0, dp(ctx, 3), 0, 0);
        mediaCard.addView(mMirMediaTitle);

        mMirMediaSub = makeMirLabel(ctx, "", compact ? 11.5f : 12.5f, mSideTheme.subColor, false);
        mMirMediaSub.setMaxLines(2);
        mMirMediaSub.setEllipsize(TextUtils.TruncateAt.END);
        mMirMediaSub.setPadding(0, dp(ctx, 2), 0, 0);
        mediaCard.addView(mMirMediaSub);

        LinearLayout mediaBtns = new LinearLayout(ctx);
        mediaBtns.setOrientation(LinearLayout.HORIZONTAL);
        mediaBtns.setPadding(0, dp(ctx, 6), 0, 0);
        TextView btnPrev = makeMirPillBtn(ctx, "上一曲", mSideTheme.pillBgColor, mSideTheme.titleColor, compact);
        btnPrev.setOnClickListener(v -> sendMirMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        TextView btnPlay = makeMirPillBtn(ctx, "播放/暂停", HyperUi.HYPER_BLUE, 0xFFF8FAFC, compact);
        btnPlay.setOnClickListener(v -> sendMirMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        TextView btnNext = makeMirPillBtn(ctx, "下一曲", mSideTheme.pillBgColor, mSideTheme.titleColor, compact);
        btnNext.setOnClickListener(v -> sendMirMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p1.rightMargin = dp(ctx, 4);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f);
        p2.rightMargin = dp(ctx, 4);
        mediaBtns.addView(btnPrev, p1);
        mediaBtns.addView(btnPlay, p2);
        mediaBtns.addView(btnNext, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mediaCard.addView(mediaBtns);
        return mediaCard;
    }

    private void sendMirMediaKey(int keyCode) {
        try {
            if (mAm != null) {
                mAm.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                mAm.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                if (mHandler != null) mHandler.postDelayed(this::pollRemoteStateAsync, 350);
            }
        } catch (Throwable ignored) {}
    }

    private LinearLayout buildMirCalCard(Context ctx, boolean compact) {
        LinearLayout calCard = makeMirrorCard(ctx, compact);
        int calCol = mSideTheme.lightMode ? 0xFF059669 : 0xFF34D399;
        TextView calHdr = makeMirLabel(ctx, "日程日历", compact ? 12.5f : 14f, calCol, true);
        HyperUi.setStartIcon(calHdr, HyperUi.ICON_CALENDAR, calCol, 13f, 5f);
        calCard.addView(calHdr);
        mMirCalBody = makeMirLabel(ctx, "近两日暂无日程", compact ? 12f : 13f, mSideTheme.subColor, false);
        mMirCalBody.setMaxLines(compact ? 3 : 4);
        mMirCalBody.setEllipsize(TextUtils.TruncateAt.END);
        mMirCalBody.setPadding(0, dp(ctx, 3), 0, 0);
        calCard.addView(mMirCalBody);
        return calCard;
    }

    private LinearLayout buildMirPerfCard(Context ctx, boolean compact) {
        LinearLayout card = makeMirrorCard(ctx, compact);
        TextView hdr = makeMirLabel(ctx, "内存与系统负载", compact ? 12f : 13.5f, mSideTheme.accentColor, true);
        HyperUi.setStartIcon(hdr, HyperUi.ICON_CPU, mSideTheme.accentColor, 13f, 5f);
        card.addView(hdr);
        mMirPerfMain = makeMirLabel(ctx, "RAM 检测中…", compact ? 14f : 15.5f, mSideTheme.titleColor, true);
        mMirPerfMain.setPadding(0, dp(ctx, 3), 0, 0);
        card.addView(mMirPerfMain);
        mMirPerfSub = makeMirLabel(ctx, "CPU 核心状态", compact ? 11.5f : 12.5f, mSideTheme.subColor, false);
        mMirPerfSub.setPadding(0, dp(ctx, 2), 0, dp(ctx, 4));
        card.addView(mMirPerfSub);
        mMirPerfProg = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        mMirPerfProg.setMax(100);
        mMirPerfProg.setProgress(50);
        try { mMirPerfProg.getProgressDrawable().setColorFilter(mSideTheme.lightMode ? 0xFF16A34A : 0xFF22C55E, PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) {}
        card.addView(mMirPerfProg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6)));
        return card;
    }

    private LinearLayout buildMirNetCard(Context ctx, boolean compact) {
        LinearLayout card = makeMirrorCard(ctx, compact);
        TextView hdr = makeMirLabel(ctx, "实时网速监控", compact ? 12f : 13.5f, mSideTheme.accentColor, true);
        HyperUi.setStartIcon(hdr, HyperUi.ICON_NET, mSideTheme.accentColor, 13f, 5f);
        card.addView(hdr);
        mMirNetMain = makeMirLabel(ctx, "↓ 0 KB/s · ↑ 0 KB/s", compact ? 13.5f : 15f, mSideTheme.titleColor, true);
        mMirNetMain.setPadding(0, dp(ctx, 3), 0, 0);
        card.addView(mMirNetMain);
        mMirNetSub = makeMirLabel(ctx, "实时流量统计中", compact ? 11.5f : 12.5f, mSideTheme.subColor, false);
        mMirNetSub.setPadding(0, dp(ctx, 2), 0, 0);
        card.addView(mMirNetSub);
        return card;
    }

    private LinearLayout buildMirStorageCard(Context ctx, boolean compact) {
        LinearLayout card = makeMirrorCard(ctx, compact);
        int stCol = mSideTheme.lightMode ? 0xFFD97706 : 0xFFFBBF24;
        TextView hdr = makeMirLabel(ctx, "存储与屏幕亮度", compact ? 12f : 13.5f, stCol, true);
        HyperUi.setStartIcon(hdr, HyperUi.ICON_STORAGE, stCol, 13f, 5f);
        card.addView(hdr);
        mMirStorageMain = makeMirLabel(ctx, "存储计算中…", compact ? 14f : 15.5f, mSideTheme.titleColor, true);
        mMirStorageMain.setPadding(0, dp(ctx, 3), 0, 0);
        card.addView(mMirStorageMain);
        mMirStorageSub = makeMirLabel(ctx, "亮度读取中", compact ? 11.5f : 12.5f, mSideTheme.subColor, false);
        mMirStorageSub.setPadding(0, dp(ctx, 2), 0, dp(ctx, 4));
        card.addView(mMirStorageSub);
        mMirStorageProg = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        mMirStorageProg.setMax(100);
        mMirStorageProg.setProgress(40);
        try { mMirStorageProg.getProgressDrawable().setColorFilter(stCol, PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) {}
        card.addView(mMirStorageProg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6)));
        return card;
    }

    private LinearLayout buildMirPomodoroCard(Context ctx, boolean compact) {
        LinearLayout card = makeMirrorCard(ctx, compact);
        int pmCol = mSideTheme.lightMode ? 0xFFDB2777 : 0xFFF472B6;
        TextView hdr = makeMirLabel(ctx, "专注番茄钟", compact ? 12f : 13.5f, pmCol, true);
        HyperUi.setStartIcon(hdr, HyperUi.ICON_TIMER, pmCol, 13f, 5f);
        card.addView(hdr);
        mMirPomoTime = makeMirLabel(ctx, formatCountdown(mPomoRemainSec), compact ? 24f : 28f, mSideTheme.titleColor, true);
        mMirPomoTime.setPadding(0, dp(ctx, 2), 0, dp(ctx, 4));
        card.addView(mMirPomoTime);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        mMirPomoToggleBtn = makeMirPillBtn(ctx, mPomoRunning ? "暂停" : "开始专注", HyperUi.HYPER_BLUE, Color.WHITE, compact);
        mMirPomoToggleBtn.setOnClickListener(v -> {
            mPomoRunning = !mPomoRunning;
            mPomoLastTickMs = SystemClock.elapsedRealtime();
            if (mHandler != null) {
                mHandler.removeCallbacks(mPomoTickTask);
                if (mPomoRunning) mHandler.postDelayed(mPomoTickTask, 1000L);
            }
            refreshMirrorSideViews(mLastStatus);
        });
        TextView btnReset = makeMirPillBtn(ctx, "重置 25m", mSideTheme.pillBgColor, mSideTheme.titleColor, compact);
        btnReset.setOnClickListener(v -> {
            mPomoRunning = false;
            mPomoRemainSec = 25 * 60;
            mPomoLastTickMs = SystemClock.elapsedRealtime();
            if (mHandler != null) mHandler.removeCallbacks(mPomoTickTask);
            refreshMirrorSideViews(mLastStatus);
        });
        if (mPomoRunning && mHandler != null) {
            mHandler.removeCallbacks(mPomoTickTask);
            mHandler.postDelayed(mPomoTickTask, 1000L);
        }
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f);
        lp1.rightMargin = dp(ctx, 5);
        row.addView(mMirPomoToggleBtn, lp1);
        row.addView(btnReset, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        return card;
    }

    private LinearLayout buildMirMemoCard(Context ctx, boolean compact) {
        LinearLayout card = makeMirrorCard(ctx, compact);
        int mmCol = mSideTheme.lightMode ? 0xFF0D9488 : 0xFF2DD4BF;
        TextView hdr = makeMirLabel(ctx, "快捷桌面便签", compact ? 12f : 13.5f, mmCol, true);
        HyperUi.setStartIcon(hdr, HyperUi.ICON_MEMO, mmCol, 13f, 5f);
        card.addView(hdr);
        mMirMemoBody = makeMirLabel(ctx, getMirrorMemoText(this), compact ? 12f : 13f, mSideTheme.subColor, false);
        mMirMemoBody.setMaxLines(4);
        mMirMemoBody.setEllipsize(TextUtils.TruncateAt.END);
        mMirMemoBody.setPadding(0, dp(ctx, 4), 0, 0);
        card.addView(mMirMemoBody);
        return card;
    }

    private void toggleMirrorMainScreenDim() {
        mBg.execute(() -> {
            BridgeClient.execSysAction(BridgeClient.SYS_SCREEN_DIM, -1, null);
            BridgeClient.SysStatus sys = BridgeClient.getSysStatus();
            if (mHandler != null) mHandler.post(() -> {
                if (sys != null && sys.valid) mLastSys = sys;
                refreshMirrorSideViews(mLastStatus);
                pollRemoteStateAsync();
            });
        });
    }

    private LinearLayout buildMirScreenDimCard(Context ctx, boolean compact) {
        LinearLayout card = makeMirrorCard(ctx, compact);
        boolean dimmed = mLastSys.valid && mLastSys.screenDimmed;
        int dmCol = mSideTheme.lightMode ? 0xFFB45309 : 0xFFFBBF24;
        TextView hdr = makeMirLabel(ctx, "主屏最低亮度", compact ? 12f : 13.5f, dmCol, true);
        HyperUi.setStartIcon(hdr, HyperUi.ICON_MOON, dmCol, 13f, 5f);
        card.addView(hdr);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 4), 0, 0);

        mMirDimMain = makeMirLabel(ctx,
                dimmed ? "主屏状态：最低亮度" : "主屏状态：正常亮度",
                compact ? 11.5f : 12.5f,
                dimmed ? 0xFFFDE047 : mSideTheme.subColor, false);
        row.addView(mMirDimMain, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mMirDimBtn = makeMirPillBtn(ctx, dimmed ? "恢复亮度" : "最低亮度",
                dimmed ? HyperUi.HYPER_BLUE : 0xFF334155, Color.WHITE, compact);
        mMirDimBtn.setOnClickListener(v -> toggleMirrorMainScreenDim());
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.leftMargin = dp(ctx, 6);
        row.addView(mMirDimBtn, bLp);

        card.addView(row);
        return card;
    }

    private LinearLayout buildMirQuickCtrlCard(Context ctx, boolean compact) {
        LinearLayout card = makeMirrorCard(ctx, compact);
        TextView hdr = makeMirLabel(ctx, "镜像快捷控制", compact ? 12f : 13.5f, mSideTheme.accentColor, true);
        HyperUi.setStartIcon(hdr, HyperUi.ICON_SLIDERS, mSideTheme.accentColor, 13f, 5f);
        card.addView(hdr);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 6), 0, 0);
        TextView bDex = makeMirPillBtn(ctx, "回 DeX", HyperUi.HYPER_BLUE, Color.WHITE, compact);
        bDex.setOnClickListener(v -> mBg.execute(() -> {
            BridgeClient.setMirrorMode(BridgeClient.MIRROR_MODE_DEX);
            if (mHandler != null) mHandler.post(this::pollRemoteStateAsync);
        }));
        TextView bAlign = makeMirPillBtn(ctx, "换边", mSideTheme.accentBtnColor, Color.WHITE, compact);
        bAlign.setOnClickListener(v -> mBg.execute(() -> {
            BridgeClient.setMirrorSideMode(-1);
            if (mHandler != null) mHandler.post(this::pollRemoteStateAsync);
        }));
        TextView bInp = makeMirPillBtn(ctx, "切键鼠", 0xFF0F766E, Color.WHITE, compact);
        bInp.setOnClickListener(v -> mBg.execute(() -> {
            BridgeClient.toggleInput();
            if (mHandler != null) mHandler.post(this::pollRemoteStateAsync);
        }));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(ctx, 4);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.rightMargin = dp(ctx, 4);
        row.addView(bDex, lp1);
        row.addView(bAlign, lp2);
        row.addView(bInp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);

        boolean dimmed = mLastSys.valid && mLastSys.screenDimmed;
        mMirQuickDimBtn = makeMirPillBtn(ctx,
                dimmed ? "主屏已最低亮度 · 点此恢复" : "主屏最低亮度（省电防刺眼）",
                dimmed ? HyperUi.HYPER_BLUE : 0xFF334155, Color.WHITE, compact);
        mMirQuickDimBtn.setOnClickListener(v -> toggleMirrorMainScreenDim());
        LinearLayout.LayoutParams dimLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dimLp.topMargin = dp(ctx, 5);
        card.addView(mMirQuickDimBtn, dimLp);
        return card;
    }

    private LinearLayout.LayoutParams mirCardLp(Context ctx, int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, bottomMarginDp);
        return lp;
    }

    private void addPresetCardsGrid(Context ctx, LinearLayout container, List<String> keys, int cols, boolean compact, int gapDp) {
        if (keys == null || keys.isEmpty()) return;
        if (cols <= 1) {
            for (int i = 0; i < keys.size(); i++) {
                boolean last = (i == keys.size() - 1);
                container.addView(buildMirPresetCard(ctx, keys.get(i), compact), mirCardLp(ctx, last ? 0 : gapDp));
            }
            return;
        }
        int i = 0;
        while (i < keys.size()) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setMeasureWithLargestChildEnabled(true);
            int remaining = keys.size() - i;
            if (remaining >= 2) {
                LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                c1.rightMargin = dp(ctx, gapDp);
                row.addView(buildMirPresetCard(ctx, keys.get(i), compact), c1);
                row.addView(buildMirPresetCard(ctx, keys.get(i + 1), compact),
                        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
                i += 2;
            } else {
                row.addView(buildMirPresetCard(ctx, keys.get(i), compact),
                        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                i += 1;
            }
            container.addView(row, mirCardLp(ctx, i >= keys.size() ? 0 : gapDp));
        }
    }

    private LinearLayout buildMirrorUnifiedSidebar(
            Context ctx, int wingW, int wingH, int leftWidgetId, int rightWidgetId, List<String> presets) {
        LinearLayout outer = new LinearLayout(ctx);
        outer.setBackgroundColor(0xFF000000 | mSideTheme.bgColor);
        outer.setClipChildren(true);
        outer.setClipToPadding(true);
        float density = Math.max(0.5f, ctx.getResources().getDisplayMetrics().density);
        float dpW = wingW / density;
        float dpH = wingH / density;
        boolean wideBanner = dpW > dpH * 1.35f && dpH < 560f;
        boolean wideBoard = dpW >= 460f;
        boolean hasAnyWidget = (leftWidgetId > 0 || rightWidgetId > 0);

        if (wideBanner) {
            outer.setOrientation(LinearLayout.HORIZONTAL);
            int pad = dp(ctx, 10);
            outer.setPadding(pad, pad, pad, pad);

            ScrollView sv = new ScrollView(ctx);
            sv.setVerticalScrollBarEnabled(false);
            LinearLayout cardsBox = new LinearLayout(ctx);
            cardsBox.setOrientation(LinearLayout.VERTICAL);
            addPresetCardsGrid(ctx, cardsBox, presets, dpW >= 680f ? 2 : 1, true, 7);
            sv.addView(cardsBox);
            float cardsWFrac = hasAnyWidget ? 0.52f : 0.76f;
            float widgetsWFrac = 1.0f - cardsWFrac;
            LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, cardsWFrac);
            svLp.rightMargin = dp(ctx, 10);
            outer.addView(sv, svLp);

            LinearLayout widgetRow = new LinearLayout(ctx);
            widgetRow.setOrientation(LinearLayout.HORIZONTAL);
            outer.addView(widgetRow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, widgetsWFrac));
            int both = (leftWidgetId > 0 && rightWidgetId > 0) ? 2 : 1;
            int widgetGap = both == 2 ? dp(ctx, 8) : 0;
            int availW = Math.round(((wingW - pad * 2 - dp(ctx, 10)) * widgetsWFrac - widgetGap) / (float) both);
            int availH = Math.max(dp(ctx, 80), wingH - pad * 2);
            appendUnifiedWidgetSlotsHorizontal(ctx, widgetRow, leftWidgetId, rightWidgetId, availW, availH, 8);
        } else if (wideBoard) {
            outer.setOrientation(LinearLayout.HORIZONTAL);
            int pad = dp(ctx, 14);
            outer.setPadding(pad, pad, pad, pad);

            ScrollView sv = new ScrollView(ctx);
            sv.setVerticalScrollBarEnabled(false);
            LinearLayout infoCol = new LinearLayout(ctx);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            addPresetCardsGrid(ctx, infoCol, presets, dpW >= 780f ? 2 : 1, false, 9);
            sv.addView(infoCol);
            float infoFrac = hasAnyWidget ? 0.47f : 0.76f;
            float widgetFrac = 1.0f - infoFrac;
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, infoFrac);
            infoLp.rightMargin = dp(ctx, 12);
            outer.addView(sv, infoLp);

            LinearLayout widgetCol = new LinearLayout(ctx);
            widgetCol.setOrientation(LinearLayout.VERTICAL);
            outer.addView(widgetCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, widgetFrac));

            int colAvailPx = Math.round((wingW - pad * 2 - dp(ctx, 12)) * widgetFrac);
            int colAvailH = Math.max(dp(ctx, 120), wingH - pad * 2);
            appendUnifiedWidgetSlots(ctx, widgetCol, leftWidgetId, rightWidgetId, colAvailPx, colAvailH, false, 10);
        } else {
            outer.setOrientation(LinearLayout.VERTICAL);
            int padH = dp(ctx, dpW < 180f ? 6 : 8);
            int padV = dp(ctx, 10);
            outer.setPadding(padH, padV, padH, padV);

            boolean compactCards = dpW < 290f;
            int gap = dpW < 220f ? 6 : 7;
            int innerW = Math.max(dp(ctx, 60), wingW - padH * 2);
            int innerH = Math.max(dp(ctx, 120), wingH - padV * 2);

            LinearLayout cardsBlock = new LinearLayout(ctx);
            cardsBlock.setOrientation(LinearLayout.VERTICAL);
            addPresetCardsGrid(ctx, cardsBlock, presets, 1, compactCards, gap);
            cardsBlock.measure(
                    View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(innerH, View.MeasureSpec.AT_MOST));
            int cardsMeasuredH = cardsBlock.getMeasuredHeight();
            int remainH = innerH - cardsMeasuredH - dp(ctx, 8);
            boolean needScroll = dpH < 660f
                    || (hasAnyWidget && remainH < dp(ctx, (leftWidgetId > 0 && rightWidgetId > 0) ? 260 : 170))
                    || (!hasAnyWidget && cardsMeasuredH > innerH);

            if (needScroll) {
                ScrollView sv = new ScrollView(ctx);
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(cardsBlock);
                float cardsWeight = hasAnyWidget ? 0.52f : 1.0f;
                LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, cardsWeight);
                if (hasAnyWidget) svLp.bottomMargin = dp(ctx, 8);
                outer.addView(sv, svLp);

                if (hasAnyWidget) {
                    LinearLayout widgetHolder = new LinearLayout(ctx);
                    widgetHolder.setOrientation(LinearLayout.VERTICAL);
                    outer.addView(widgetHolder, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.48f));
                    int wAvailH = Math.max(dp(ctx, 90), Math.round((innerH - dp(ctx, 8)) * 0.48f));
                    appendUnifiedWidgetSlots(ctx, widgetHolder, leftWidgetId, rightWidgetId, innerW, wAvailH, compactCards, 8);
                }
            } else {
                outer.addView(cardsBlock, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                View spacer = new View(ctx);
                outer.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)));
                LinearLayout widgetHolder = new LinearLayout(ctx);
                widgetHolder.setOrientation(LinearLayout.VERTICAL);
                outer.addView(widgetHolder, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
                appendUnifiedWidgetSlots(ctx, widgetHolder, leftWidgetId, rightWidgetId, innerW, Math.max(dp(ctx, 90), remainH), compactCards, 8);
            }
        }
        return outer;
    }

    private void appendUnifiedWidgetSlots(
            Context ctx, LinearLayout container, int leftWidgetId, int rightWidgetId,
            int availWpx, int availHpx, boolean compact, int gapDp) {
        mMirLeftWidgetBox = null;
        mMirRightWidgetBox = null;
        boolean hasL = leftWidgetId > 0;
        boolean hasR = rightWidgetId > 0;
        int eachH = (hasL && hasR)
                ? Math.max(dp(ctx, 72), (availHpx - dp(ctx, gapDp)) / 2)
                : Math.max(dp(ctx, 72), availHpx);

        if (hasL || !hasR) {
            mMirLeftWidgetBox = new FrameLayout(ctx);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            if (hasR) lLp.bottomMargin = dp(ctx, gapDp);
            container.addView(mMirLeftWidgetBox, lLp);
            mountSidebarAppWidget(ctx, mMirLeftWidgetBox, leftWidgetId, 0, availWpx, eachH, compact);
        }
        if (hasR) {
            mMirRightWidgetBox = new FrameLayout(ctx);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            container.addView(mMirRightWidgetBox, rLp);
            mountSidebarAppWidget(ctx, mMirRightWidgetBox, rightWidgetId, 1, availWpx, eachH, compact);
        }
    }

    private void appendUnifiedWidgetSlotsHorizontal(
            Context ctx, LinearLayout container, int leftWidgetId, int rightWidgetId,
            int availWpx, int availHpx, int gapDp) {
        mMirLeftWidgetBox = null;
        mMirRightWidgetBox = null;
        boolean hasL = leftWidgetId > 0;
        boolean hasR = rightWidgetId > 0;
        if (hasL || !hasR) {
            mMirLeftWidgetBox = new FrameLayout(ctx);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (hasR) lLp.rightMargin = dp(ctx, gapDp);
            container.addView(mMirLeftWidgetBox, lLp);
            mountSidebarAppWidget(ctx, mMirLeftWidgetBox, leftWidgetId, 0, availWpx, availHpx, true);
        }
        if (hasR) {
            mMirRightWidgetBox = new FrameLayout(ctx);
            container.addView(mMirRightWidgetBox, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            mountSidebarAppWidget(ctx, mMirRightWidgetBox, rightWidgetId, 1, availWpx, availHpx, true);
        }
    }

    private LinearLayout buildMirrorDualWing(
            Context ctx, int wingW, int wingH, int widgetId, boolean isLeft, List<String> presets) {
        float density = Math.max(0.5f, ctx.getResources().getDisplayMetrics().density);
        float dpW = wingW / density;
        boolean horizWing = dpW > (wingH / density) * 1.4f;
        boolean compact = dpW < 230f || horizWing;
        int slot = isLeft ? 0 : 1;

        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(horizWing ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xFF000000 | mSideTheme.bgColor);
        outer.setClipChildren(true);
        outer.setClipToPadding(true);
        int padH = dp(ctx, compact ? 6 : 12);
        int padV = dp(ctx, compact ? 8 : 14);
        outer.setPadding(padH, padV, padH, padV);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout cardsCol = new LinearLayout(ctx);
        cardsCol.setOrientation(LinearLayout.VERTICAL);
        addPresetCardsGrid(ctx, cardsCol, presets, horizWing && dpW >= 540f ? 2 : 1, compact, compact ? 7 : 9);
        sv.addView(cardsCol);

        FrameLayout wBox = new FrameLayout(ctx);
        if (isLeft) mMirLeftWidgetBox = wBox;
        else mMirRightWidgetBox = wBox;

        int innerW = Math.max(dp(ctx, 56), wingW - padH * 2);
        int innerH = Math.max(dp(ctx, 80), wingH - padV * 2);
        float cardsFrac = (widgetId > 0) ? (horizWing ? 0.55f : 0.54f) : 0.80f;
        float widgetFrac = 1.0f - cardsFrac;
        if (horizWing) {
            LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, cardsFrac);
            svLp.rightMargin = dp(ctx, 8);
            outer.addView(sv, svLp);
            outer.addView(wBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, widgetFrac));
            int availW = Math.round(Math.max(dp(ctx, 56), innerW - dp(ctx, 8)) * widgetFrac);
            mountSidebarAppWidget(ctx, wBox, widgetId, slot, availW, innerH, compact);
        } else {
            LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, cardsFrac);
            svLp.bottomMargin = dp(ctx, 8);
            outer.addView(sv, svLp);
            outer.addView(wBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, widgetFrac));
            int availH = Math.round(Math.max(dp(ctx, 72), innerH - dp(ctx, 8)) * widgetFrac);
            mountSidebarAppWidget(ctx, wBox, widgetId, slot, innerW, availH, compact);
        }
        return outer;
    }

    private void mountSidebarAppWidget(
            Context ctx, FrameLayout box, int widgetId, int slot, int availWpx, int availHpx, boolean compact) {
        if (box == null) return;
        box.removeAllViews();
        box.setClipChildren(true);
        box.setClipToPadding(true);
        if (widgetId > 0 && (availWpx < dp(ctx, 90) || availHpx < dp(ctx, 72))) {
            LinearLayout smallCard = makeMirrorCard(ctx, true);
            smallCard.setGravity(Gravity.CENTER);
            TextView stv = makeMirLabel(ctx, "空间较窄\n未挂载小组件", 11f, mSideTheme.subColor, false);
            stv.setGravity(Gravity.CENTER);
            smallCard.addView(stv);
            box.addView(smallCard, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));
            return;
        }
        if (widgetId > 0 && mAppWidgetMgr != null && mAppWidgetHost != null) {
            try {
                AppWidgetProviderInfo info = mAppWidgetMgr.getAppWidgetInfo(widgetId);
                if (info != null) {
                    AppWidgetHostView hv = mAppWidgetHost.createView(ctx, widgetId, info);
                    hv.setAppWidget(widgetId, info);
                    float density = Math.max(0.5f, ctx.getResources().getDisplayMetrics().density);
                    int ratioMode = getMirrorWidgetRatio(this, slot);
                    float ratio;
                    if (ratioMode == 1) {
                        ratio = 1.0f;
                    } else if (ratioMode == 2) {
                        ratio = 0.75f;
                    } else if (ratioMode == 3) {
                        ratio = 16f / 9f;
                    } else if (ratioMode == 4) {
                        ratio = 0f; // fill slot
                    } else {
                        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
                            ratio = info.targetCellWidth / (float) info.targetCellHeight;
                        } else if (info.minWidth > 0 && info.minHeight > 0) {
                            ratio = info.minWidth / (float) info.minHeight;
                        } else {
                            ratio = 1.15f;
                        }
                        ratio = Math.max(0.45f, Math.min(2.4f, ratio));
                    }

                    int fitW = availWpx;
                    int fitH = availHpx;
                    if (ratio > 0.01f) {
                        fitH = Math.round(fitW / ratio);
                        if (fitH > availHpx) {
                            fitH = availHpx;
                            fitW = Math.round(fitH * ratio);
                        }
                        fitW = Math.max(Math.min(availWpx, dp(ctx, 88)), Math.min(availWpx, fitW));
                        fitH = Math.max(Math.min(availHpx, dp(ctx, 72)), Math.min(availHpx, fitH));
                    }

                    int dpFitW = Math.max(60, Math.round(fitW / density));
                    int dpFitH = Math.max(60, Math.round(fitH / density));
                    try {
                        Bundle opts = new Bundle();
                        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, dpFitW);
                        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, dpFitW);
                        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, dpFitH);
                        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, dpFitH);
                        if (Build.VERSION.SDK_INT >= 31) {
                            ArrayList<android.util.SizeF> sizes = new ArrayList<>();
                            sizes.add(new android.util.SizeF(dpFitW, dpFitH));
                            opts.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes);
                        }
                        hv.updateAppWidgetOptions(opts);
                    } catch (Throwable ignored) {}
                    GradientDrawable wBg = new GradientDrawable();
                    wBg.setColor(mSideTheme.widgetBgColor);
                    wBg.setCornerRadius(dp(ctx, 12));
                    wBg.setStroke(1, mSideTheme.cardStrokeColor);
                    hv.setBackground(wBg);
                    hv.setClipChildren(true);
                    hv.setClipToPadding(true);
                    hv.setClipToOutline(true);
                    hv.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
                    FrameLayout.LayoutParams hvLp = new FrameLayout.LayoutParams(
                            fitW, fitH, Gravity.CENTER);
                    box.addView(hv, hvLp);
                    return;
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        // Subtle placeholder card showing how to add system widgets
        LinearLayout hint = makeMirrorCard(ctx, compact);
        hint.setGravity(Gravity.CENTER);
        TextView ht = makeMirLabel(ctx,
                compact ? "系统小组件\n可在控制台绑定" : "暂无绑定系统小组件\n可在 ExtBridge 控制台\n添加天气/时钟/日历等小组件",
                compact ? 11.5f : 13f, mSideTheme.subColor, false);
        ht.setGravity(Gravity.CENTER);
        hint.addView(ht);
        FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        box.addView(hint, hLp);
    }

    private static String formatSpeedRate(double bytesPerSec) {
        if (bytesPerSec < 1024.0) return String.format(Locale.US, "%.0f B/s", Math.max(0.0, bytesPerSec));
        if (bytesPerSec < 1024.0 * 1024.0) return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    private static String formatCountdown(long totalSec) {
        long s = Math.max(0L, totalSec);
        long m = s / 60L;
        long r = s % 60L;
        return String.format(Locale.US, "%02d:%02d", m, r);
    }

    private void refreshMirrorSideViews(BridgeClient.Status st) {
        if (mMirBattIcon != null) mMirBattIcon.setBattery(mBattPct, mBattCharging);
        if (mMirBattMain != null) {
            mMirBattMain.setText(mBattPct + "%" + (mBattCharging ? " · 充电中" : ""));
            int chgCol = mSideTheme.lightMode ? 0xFF16A34A : 0xFF4ADE80;
            int lowCol = mSideTheme.lightMode ? 0xFFDC2626 : 0xFFF87171;
            mMirBattMain.setTextColor(mBattCharging ? chgCol : (mBattPct <= 20 ? lowCol : mSideTheme.titleColor));
        }
        if (mMirBattSub != null) {
            String bd = (mLastMirTel != null && !mLastMirTel.battDetail.isEmpty())
                    ? mLastMirTel.battDetail : (mBattCharging ? "充电中" : "电池供电");
            mMirBattSub.setText(bd);
        }
        if (mMirWifiIcon != null) mMirWifiIcon.setActiveState(mLastSys.valid && mLastSys.wifiOn);
        if (mMirWifiMain != null) {
            if (!mLastSys.valid || !mLastSys.wifiOn) mMirWifiMain.setText("Wi-Fi 已关闭");
            else mMirWifiMain.setText(mLastSys.wifiSsid.isEmpty() ? "Wi-Fi 未连接" : mLastSys.wifiSsid);
        }
        if (mMirWifiSub != null) {
            String wd = (mLastMirTel != null && !mLastMirTel.wifiDetail.isEmpty())
                    ? mLastMirTel.wifiDetail : ((mLastSys.valid && mLastSys.wifiOn) ? "已连接" : "点击系统设置开启");
            mMirWifiSub.setText(wd);
        }
        if (mMirBtIcon != null) mMirBtIcon.setActiveState(mLastSys.valid && mLastSys.btOn);
        if (mMirBtMain != null) {
            mMirBtMain.setText((mLastSys.valid && mLastSys.btOn) ? "蓝牙已开启" : "蓝牙已关闭");
        }
        if (mMirBtSub != null) {
            if (!mLastSys.valid || !mLastSys.btOn) mMirBtSub.setText("未启用");
            else mMirBtSub.setText(mLastSys.btName.isEmpty() ? "暂未连接音频/外设" : mLastSys.btName);
        }

        if (mMirAudioRoute != null) {
            String route = (mLastMirTel != null && !mLastMirTel.audioRoute.isEmpty())
                    ? mLastMirTel.audioRoute : "平板扬声器";
            mMirAudioRoute.setText(route);
        }
        if (mAm != null && mMirVolProg != null) {
            int max = Math.max(1, mAm.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int cur = mAm.getStreamVolume(AudioManager.STREAM_MUSIC);
            int pct = Math.round(cur * 100f / max);
            mMirVolProg.setProgress(pct);
            if (mMirAudioIcon != null) mMirAudioIcon.setVolumeLevel(cur == 0 ? 0 : (pct > 50 ? 2 : 1));
            if (mMirAudioSub != null) {
                String ad = (mLastMirTel != null && !mLastMirTel.audioDetail.isEmpty())
                        ? mLastMirTel.audioDetail : ("媒体音量 " + pct + "%");
                mMirAudioSub.setText(ad);
            }
        }

        if (mMirMediaBadge != null && mMirMediaTitle != null && mMirMediaSub != null) {
            if (mLastMirTel != null && !mLastMirTel.mediaTitle.isEmpty()) {
                String stTag = (mLastMirTel.mediaState == 3) ? "正在播放"
                        : (mLastMirTel.mediaState == 2 ? "已暂停" : "最近播放");
                String appName = resolvePkgLabel(mLastMirTel.mediaPkg);
                mMirMediaBadge.setText(appName.isEmpty() ? stTag : (stTag + " · " + appName));
                mMirMediaTitle.setText(mLastMirTel.mediaTitle);
                mMirMediaSub.setText(mLastMirTel.mediaSubtitle);
                mMirMediaSub.setVisibility(mLastMirTel.mediaSubtitle.isEmpty() ? View.GONE : View.VISIBLE);
            } else {
                mMirMediaBadge.setText("媒体播放");
                mMirMediaTitle.setText("当前暂无媒体会话");
                mMirMediaSub.setVisibility(View.GONE);
            }
        }
        if (mMirCalBody != null) {
            String cal = (mLastMirTel != null && !mLastMirTel.calendarText.isEmpty())
                    ? mLastMirTel.calendarText : "近两日暂无日历日程";
            mMirCalBody.setText(cal);
        }

        if (mMirPerfMain != null && mMirPerfSub != null && mMirPerfProg != null && mMirrorMetricsValid) {
            long upMin = SystemClock.elapsedRealtime() / 60000L;
            mMirPerfMain.setText(String.format(Locale.US, "可用 %.1f / %.0f GB", mCachedMemAvailGb, mCachedMemTotalGb));
            mMirPerfSub.setText(String.format(Locale.US, "已用 %d%% · %d核心 · 运行 %dh%02dm",
                    mCachedMemPct, Runtime.getRuntime().availableProcessors(), upMin / 60L, upMin % 60L));
            mMirPerfProg.setProgress(mCachedMemPct);
        }

        if (mMirNetMain != null && mMirNetSub != null && mMirrorMetricsValid) {
            mMirNetMain.setText("↓ " + formatSpeedRate(mCachedRxRate));
            mMirNetSub.setText("↑ " + formatSpeedRate(mCachedTxRate)
                    + String.format(Locale.US, " · 累计 %.1f MB", mCachedRxTotalMb));
        }

        if (mMirStorageMain != null && mMirStorageSub != null && mMirStorageProg != null && mMirrorMetricsValid) {
            mMirStorageMain.setText(String.format(Locale.US, "剩 %.1f GB (总 %.0fG)",
                    mCachedStorageAvailGb, mCachedStorageTotalGb));
            mMirStorageSub.setText("已用 " + mCachedStoragePct + "% · 主屏亮度 " + mCachedD0BrightPct + "%");
            mMirStorageProg.setProgress(mCachedStoragePct);
        }

        if (mMirPomoTime != null) {
            if (mPomoRunning) {
                long now = SystemClock.elapsedRealtime();
                if (mPomoLastTickMs > 0) {
                    long deltaSec = (now - mPomoLastTickMs) / 1000L;
                    if (deltaSec > 0) {
                        mPomoRemainSec = Math.max(0L, mPomoRemainSec - deltaSec);
                        mPomoLastTickMs += deltaSec * 1000L;
                        if (mPomoRemainSec == 0L) {
                            mPomoRunning = false;
                            if (mHandler != null) mHandler.removeCallbacks(mPomoTickTask);
                        }
                    }
                } else {
                    mPomoLastTickMs = now;
                }
            }
            mMirPomoTime.setText(formatCountdown(mPomoRemainSec));
            if (mMirPomoToggleBtn != null) {
                mMirPomoToggleBtn.setText(mPomoRunning ? "暂停" : "开始专注");
            }
        }

        if (mMirMemoBody != null) {
            mMirMemoBody.setText(getMirrorMemoText(this));
        }

        boolean dimmed = mLastSys.valid && mLastSys.screenDimmed;
        if (mMirDimMain != null) {
            mMirDimMain.setText(dimmed ? "主屏状态：最低亮度" : "主屏状态：正常亮度");
            mMirDimMain.setTextColor(dimmed
                    ? (mSideTheme.lightMode ? 0xFFB45309 : 0xFFFDE047)
                    : mSideTheme.subColor);
        }
        if (mMirDimBtn != null) {
            mMirDimBtn.setText(dimmed ? "恢复亮度" : "最低亮度");
            setMirPillBg(mMirDimBtn, dimmed ? HyperUi.HYPER_BLUE : 0xFF334155);
        }
        if (mMirQuickDimBtn != null) {
            mMirQuickDimBtn.setText(dimmed ? "主屏已最低亮度 · 点此恢复" : "主屏最低亮度（省电防刺眼）");
            setMirPillBg(mMirQuickDimBtn, dimmed ? HyperUi.HYPER_BLUE : 0xFF334155);
        }
    }

    private String resolvePkgLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = mLabelCache.get(pkg);
        if (cached != null) return cached;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence lbl = pm.getApplicationLabel(ai);
            String res = lbl != null ? lbl.toString() : pkg;
            mLabelCache.put(pkg, res);
            return res;
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    private void resetMirrorSideChildRefs() {
        mMirBattIcon = null;
        mMirBattMain = null;
        mMirBattSub = null;
        mMirWifiIcon = null;
        mMirWifiMain = null;
        mMirWifiSub = null;
        mMirBtIcon = null;
        mMirBtMain = null;
        mMirBtSub = null;
        mMirAudioIcon = null;
        mMirAudioRoute = null;
        mMirAudioSub = null;
        mMirVolProg = null;
        mMirMediaBadge = null;
        mMirMediaTitle = null;
        mMirMediaSub = null;
        mMirCalBody = null;
        mMirPerfMain = null;
        mMirPerfSub = null;
        mMirPerfProg = null;
        mMirNetMain = null;
        mMirNetSub = null;
        mMirStorageMain = null;
        mMirStorageSub = null;
        mMirStorageProg = null;
        mMirPomoTime = null;
        mMirPomoToggleBtn = null;
        mMirMemoBody = null;
        mMirDimMain = null;
        mMirDimBtn = null;
        mMirQuickDimBtn = null;
        mMirLeftWidgetBox = null;
        mMirRightWidgetBox = null;
    }

    private void detachMirrorSidebars() {
        if (mHandler != null) mHandler.removeCallbacks(mPomoTickTask);
        if (mMirLeftRoot != null && mSideWm != null) {
            try { mSideWm.removeViewImmediate(mMirLeftRoot); } catch (Throwable ignored) {}
        }
        if (mMirRightRoot != null && mSideWm != null) {
            try { mSideWm.removeViewImmediate(mMirRightRoot); } catch (Throwable ignored) {}
        }
        if (mWidgetHostListening && mAppWidgetHost != null) {
            try { mAppWidgetHost.stopListening(); } catch (Throwable ignored) {}
            mWidgetHostListening = false;
        }
        mMirLeftRoot = null;
        mMirRightRoot = null;
        mSideLeftX = 0; mSideLeftY = 0; mSideLeftW = 0; mSideLeftH = 0;
        mSideRightX = 0; mSideRightY = 0; mSideRightW = 0; mSideRightH = 0;
        detachMirrorEdgeSensor();
        mSideWm = null;
        mSideCtx = null;
        mSideAttachedVdId = -1;
        mSidePhysW = 0;
        mSidePhysH = 0;
        mSideMirX = 0;
        mSideMirY = 0;
        mSideMirW = 0;
        mSideMirH = 0;
        mSideModeAttached = -1;
        mSideAttachedLeftWidgetId = -1;
        mSideAttachedRightWidgetId = -1;
        mSidePresetSigAttached = "";
        mSideMemoAttached = "";
        mSideThemeSigAttached = "";
        mSideWidgetRatioSigAttached = "";
        resetMirrorSideChildRefs();
    }

    // ---- Live Mirror Sidebar Reflection & Touch Control API (used by SideControlActivity) ----

    public static int[] getMirrorWingMeta(int wingSlot) {
        BridgeService svc = sInstance;
        if (svc == null || svc.mSideAttachedVdId <= 0) return null;
        boolean hasDual = (svc.mMirLeftRoot != null && svc.mMirRightRoot != null
                && svc.mSideLeftW > 0 && svc.mSideRightW > 0);
        boolean useRight = (wingSlot == 1 && svc.mMirRightRoot != null && svc.mSideRightW > 0);
        int wx = useRight ? svc.mSideRightX : svc.mSideLeftX;
        int wy = useRight ? svc.mSideRightY : svc.mSideLeftY;
        int ww = useRight ? svc.mSideRightW : svc.mSideLeftW;
        int wh = useRight ? svc.mSideRightH : svc.mSideLeftH;
        if (ww <= 0 || wh <= 0) return null;
        return new int[]{
                wx, wy, ww, wh,
                svc.mSidePhysW, svc.mSidePhysH,
                svc.mSideAttachedVdId, hasDual ? 1 : 0,
                svc.mSideModeAttached
        };
    }

    public static boolean renderMirrorWingToCanvas(Canvas canvas, int dstW, int dstH, int wingSlot) {
        BridgeService svc = sInstance;
        if (svc == null || canvas == null || dstW <= 0 || dstH <= 0) return false;
        boolean useRight = (wingSlot == 1 && svc.mMirRightRoot != null && svc.mSideRightW > 0);
        LinearLayout root = useRight ? svc.mMirRightRoot : svc.mMirLeftRoot;
        int ww = useRight ? svc.mSideRightW : svc.mSideLeftW;
        int wh = useRight ? svc.mSideRightH : svc.mSideLeftH;
        if (root == null || ww <= 0 || wh <= 0) return false;
        int save = canvas.save();
        try {
            canvas.scale(dstW / (float) ww, dstH / (float) wh);
            root.draw(canvas);
            return true;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    // ---- Physical Mouse Edge-Crossing Sensor & Pointer Capture on Display 0 ----

    public static void onEdgeCaptureFromDaemon(boolean enter) {
        BridgeService svc = sInstance;
        if (svc == null || svc.mHandler == null) return;
        svc.mHandler.post(() -> {
            if (enter) svc.enterSidePointerCapture();
            else svc.exitSidePointerCapture();
        });
    }

    private WindowManager.LayoutParams buildEdgeSensorLp(int ex, int ey, int ew, int eh, int winType, String title) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ew, eh, winType, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = ex;
        lp.y = ey;
        lp.setTitle(title);
        return lp;
    }

    private void attachEdgeHoverListener(View view, WindowManager.LayoutParams lp) {
        view.setOnGenericMotionListener((v, ev) -> {
            if (!mDestroyed && !mEdgeCapturing && ev != null
                    && ev.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE
                    && (ev.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                    && mEdgeHoverInFlight.compareAndSet(false, true)) {
                final float px = lp.x + ev.getX();
                final float py = lp.y + ev.getY();
                safeInputExecute(() -> {
                    try {
                        int[] res = BridgeClient.notifyMirrorEdgeHover(px, py);
                        if (res[0] == 1) {
                            safePost(this::enterSidePointerCapture);
                        }
                    } finally {
                        mEdgeHoverInFlight.set(false);
                    }
                });
            }
            return false;
        });
    }

    private void syncMirrorEdgeSensor(BridgeClient.Status st) {
        if (st == null || st.mirrorMode != 1 || st.sideVdId <= 0 || st.mirrorSideMode <= 0
                || st.d0W <= 0 || st.d0H <= 0 || st.mirW <= 0 || st.mirH <= 0) {
            detachMirrorEdgeSensor();
            return;
        }
        boolean hasLeft = st.mirX >= 48;
        boolean hasRight = (st.physW - (st.mirX + st.mirW)) >= 48;
        boolean hasTop = (st.physW - st.mirW < 48) && st.mirY >= 48;
        boolean hasBottom = (st.physW - st.mirW < 48) && (st.physH - (st.mirY + st.mirH)) >= 48;
        if (!hasLeft && !hasRight && !hasTop && !hasBottom) {
            detachMirrorEdgeSensor();
            return;
        }
        if (mEdgeCapturing) return;

        int ex, ey, ew, eh;
        int ex2 = -1, ey2 = -1, ew2 = 0, eh2 = 0;
        if (hasLeft && hasRight) {
            ex = 0; ey = 0; ew = 5; eh = st.d0H;
            ex2 = Math.max(0, st.d0W - 5); ey2 = 0; ew2 = 5; eh2 = st.d0H;
        } else if (hasLeft) {
            ex = 0; ey = 0; ew = 5; eh = st.d0H;
        } else if (hasRight) {
            ex = Math.max(0, st.d0W - 5); ey = 0; ew = 5; eh = st.d0H;
        } else if (hasTop && hasBottom) {
            ex = 0; ey = 0; ew = st.d0W; eh = 5;
            ex2 = 0; ey2 = Math.max(0, st.d0H - 5); ew2 = st.d0W; eh2 = 5;
        } else if (hasTop) {
            ex = 0; ey = 0; ew = st.d0W; eh = 5;
        } else {
            ex = 0; ey = Math.max(0, st.d0H - 5); ew = st.d0W; eh = 5;
        }

        NavAssistService a11y = NavAssistService.getInstance();
        int edgeWinType;
        Context edgeCtx;
        if (a11y != null) {
            edgeWinType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            Display d0 = (mDm != null) ? mDm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            edgeCtx = (d0 != null) ? a11y.createDisplayContext(d0) : a11y;
        } else if (Settings.canDrawOverlays(this)) {
            edgeWinType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            edgeCtx = this;
        } else {
            detachMirrorEdgeSensor();
            return;
        }

        String key = ex + "," + ey + "," + ew + "," + eh + ";" + ex2 + "," + ey2 + "," + ew2 + "," + eh2 + "," + edgeWinType;
        if (mEdgeSensorView != null && key.equals(mEdgeSensorKey)) return;

        detachMirrorEdgeSensor();
        try {
            WindowManager wm0 = (WindowManager) edgeCtx.getSystemService(WINDOW_SERVICE);
            if (wm0 == null && a11y != null) wm0 = (WindowManager) a11y.getSystemService(WINDOW_SERVICE);
            if (wm0 == null) wm0 = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm0 == null) return;
            mEdgeD0Wm = wm0;
            mEdgeLp = buildEdgeSensorLp(ex, ey, ew, eh, edgeWinType, "ExtMirEdgeSensor");

            mEdgeSensorView = new FrameLayout(edgeCtx) {
                @Override
                public void onWindowFocusChanged(boolean hasFocus) {
                    super.onWindowFocusChanged(hasFocus);
                    if (hasFocus && mEdgeCapturing && Build.VERSION.SDK_INT >= 26) {
                        if (mHandler != null) mHandler.removeCallbacks(mEdgeReacquireTask);
                        post(() -> {
                            if (mEdgeCapturing) {
                                try {
                                    requestPointerCapture();
                                    if (hasPointerCapture()) {
                                        mEdgePointerCaptured = true;
                                        mOverlayPointerCaptureSupported = true;
                                        mEdgeReacquireAttempts = 0;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                    } else if (!hasFocus && mEdgeCapturing) {
                        // Transient focus blip (e.g. AppWidget trampoline Activity): seamlessly reacquire!
                        mEdgePointerCaptured = false;
                        scheduleEdgePointerReacquire();
                    }
                }

                @Override
                public void onPointerCaptureChange(boolean hasCapture) {
                    super.onPointerCaptureChange(hasCapture);
                    if (hasCapture) {
                        mEdgePointerCaptured = true;
                        mOverlayPointerCaptureSupported = true;
                        mEdgeReacquireAttempts = 0;
                        if (mHandler != null) {
                            mHandler.removeCallbacks(mEdgeCaptureFallbackTask);
                            mHandler.removeCallbacks(mEdgeReacquireTask);
                        }
                    } else if (mEdgeCapturing) {
                        // Do not drop sidebar capture or jump cursor when an AppWidget steals focus momentarily
                        mEdgePointerCaptured = false;
                        scheduleEdgePointerReacquire();
                    }
                }

                @Override
                public boolean dispatchKeyEvent(KeyEvent event) {
                    if (mEdgeCapturing && event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                        int kc = event.getKeyCode();
                        if (kc == KeyEvent.KEYCODE_ESCAPE || kc == KeyEvent.KEYCODE_BACK) {
                            safePost(BridgeService.this::exitSidePointerCapture);
                            return true;
                        }
                    }
                    return super.dispatchKeyEvent(event);
                }
            };
            mEdgeSensorView.setBackgroundColor(Color.TRANSPARENT);
            mEdgeSensorView.setOnTouchListener((v, ev) -> {
                if (mEdgeCapturing && ev != null) {
                    int act = ev.getActionMasked();
                    boolean outside = (act == MotionEvent.ACTION_OUTSIDE);
                    boolean finger = (ev.getSource() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
                            || (ev.getPointerCount() > 0 && ev.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE);
                    if (outside || finger) {
                        exitSidePointerCapture();
                        return false;
                    }
                }
                return false;
            });
            attachEdgeHoverListener(mEdgeSensorView, mEdgeLp);
            if (Build.VERSION.SDK_INT >= 26) {
                mEdgeSensorView.setOnCapturedPointerListener((v, ev) ->
                        handleCapturedPointerEventStatic(ev, mInputBg, () -> safePost(this::revertEdgeOverlayToSensor)));
            }
            mEdgeD0Wm.addView(mEdgeSensorView, mEdgeLp);

            if (ex2 >= 0 && ew2 > 0 && eh2 > 0) {
                mEdgeLp2 = buildEdgeSensorLp(ex2, ey2, ew2, eh2, edgeWinType, "ExtMirEdgeSensor2");
                mEdgeSensorView2 = new FrameLayout(edgeCtx);
                mEdgeSensorView2.setBackgroundColor(Color.TRANSPARENT);
                attachEdgeHoverListener(mEdgeSensorView2, mEdgeLp2);
                mEdgeD0Wm.addView(mEdgeSensorView2, mEdgeLp2);
            }
            mEdgeSensorKey = key;
        } catch (Throwable ignored) {
            detachMirrorEdgeSensor();
        }
    }

    private void scheduleEdgePointerReacquire() {
        if (mDestroyed || !mEdgeCapturing || mHandler == null) return;
        mEdgeReacquireAttempts = 0;
        mHandler.removeCallbacks(mEdgeReacquireTask);
        mHandler.postDelayed(mEdgeReacquireTask, 18L);
    }

    private void notifyDaemonCaptureExitIfNeeded() {
        if (mEdgeCapturing || mEdgePointerCaptured) {
            safeInputExecute(() -> BridgeClient.sendCapturedMirrorMouse(5, 0f, 0f, 0f));
        }
    }

    private void enterSidePointerCapture() {
        if (mDestroyed || mEdgeCapturing) return;
        if (Build.VERSION.SDK_INT < 26 || mEdgeSensorView == null || mEdgeD0Wm == null || mEdgeLp == null) {
            SideMouseCaptureActivity.launch(this);
            return;
        }
        mEdgeCapturing = true;
        mEdgePointerCaptured = false;
        mEdgeReacquireAttempts = 0;
        if (mHandler != null) mHandler.removeCallbacks(mEdgeReacquireTask);
        try {
            mEdgeLp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            mEdgeLp.width = 1;
            mEdgeLp.height = 1;
            mEdgeLp.x = 0;
            mEdgeLp.y = 0;
            mEdgeD0Wm.updateViewLayout(mEdgeSensorView, mEdgeLp);
            mEdgeSensorView.setFocusable(true);
            mEdgeSensorView.setFocusableInTouchMode(true);
            mEdgeSensorView.requestFocus();
            mEdgeSensorView.post(() -> {
                if (!mEdgeCapturing || mEdgeSensorView == null) return;
                try {
                    mEdgeSensorView.requestPointerCapture();
                    if (mEdgeSensorView.hasPointerCapture()) {
                        mEdgePointerCaptured = true;
                        mOverlayPointerCaptureSupported = true;
                    }
                } catch (Throwable ignored) {}
            });
            mHandler.removeCallbacks(mEdgeCaptureFallbackTask);
            if (!mOverlayPointerCaptureSupported) {
                mHandler.postDelayed(mEdgeCaptureFallbackTask, 280L);
            } else {
                mHandler.postDelayed(mEdgeReacquireTask, 65L);
            }
        } catch (Throwable ignored) {
            notifyDaemonCaptureExitIfNeeded();
            revertEdgeOverlayToSensor();
            SideMouseCaptureActivity.launch(this);
        }
    }

    private void revertEdgeOverlayToSensor() {
        mEdgeCapturing = false;
        mEdgePointerCaptured = false;
        mEdgeReacquireAttempts = 0;
        resetCapturedMouseMoveState();
        if (mHandler != null) {
            mHandler.removeCallbacks(mEdgeCaptureFallbackTask);
            mHandler.removeCallbacks(mEdgeReacquireTask);
        }
        if (mEdgeSensorView != null && mEdgeD0Wm != null && mEdgeLp != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26) mEdgeSensorView.releasePointerCapture();
            } catch (Throwable ignored) {}
            try {
                mEdgeSensorView.setFocusable(false);
                mEdgeSensorView.setFocusableInTouchMode(false);
                mEdgeLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mEdgeD0Wm.updateViewLayout(mEdgeSensorView, mEdgeLp);
            } catch (Throwable ignored) {}
            try {
                mEdgeSensorKey = "";
                if (mLastStatus != null) syncMirrorEdgeSensor(mLastStatus);
            } catch (Throwable ignored) {}
        }
    }

    private void exitSidePointerCapture() {
        notifyDaemonCaptureExitIfNeeded();
        SideMouseCaptureActivity.finishActiveIfAny();
        revertEdgeOverlayToSensor();
    }

    private void detachMirrorEdgeSensor() {
        notifyDaemonCaptureExitIfNeeded();
        SideMouseCaptureActivity.finishActiveIfAny();
        resetCapturedMouseMoveState();
        mEdgeCapturing = false;
        mEdgePointerCaptured = false;
        mEdgeReacquireAttempts = 0;
        mEdgeSensorKey = "";
        mEdgeHoverInFlight.set(false);
        if (mHandler != null) {
            mHandler.removeCallbacks(mEdgeCaptureFallbackTask);
            mHandler.removeCallbacks(mEdgeReacquireTask);
        }
        if (mEdgeSensorView2 != null && mEdgeD0Wm != null) {
            try { mEdgeD0Wm.removeViewImmediate(mEdgeSensorView2); } catch (Throwable ignored) {}
        }
        mEdgeSensorView2 = null;
        mEdgeLp2 = null;
        if (mEdgeSensorView != null && mEdgeD0Wm != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26) mEdgeSensorView.releasePointerCapture();
            } catch (Throwable ignored) {}
            try { mEdgeD0Wm.removeViewImmediate(mEdgeSensorView); } catch (Throwable ignored) {}
        }
        mEdgeSensorView = null;
        mEdgeLp = null;
        mEdgeD0Wm = null;
    }

    public static void resetCapturedMouseMoveState() {
        synchronized (sCapMoveLock) {
            sCapPendingDx = 0f;
            sCapPendingDy = 0f;
            sCapMoveInFlight = false;
        }
    }

    public static boolean handleCapturedPointerEventStatic(
            MotionEvent ev,
            java.util.concurrent.ExecutorService exec,
            Runnable onExitToMain) {
        if (ev == null || exec == null || exec.isShutdown()) return false;
        BridgeService svc = sInstance;
        if (svc != null && Build.VERSION.SDK_INT >= 26 && svc.mEdgeSensorView != null && svc.mEdgeSensorView.hasPointerCapture()) {
            svc.mEdgePointerCaptured = true;
        }
        int act = ev.getActionMasked();
        if (act == MotionEvent.ACTION_MOVE || act == MotionEvent.ACTION_HOVER_MOVE) {
            float rx = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
            float ry = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            if (rx == 0f && ry == 0f && (ev.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE) {
                rx = ev.getX();
                ry = ev.getY();
            }
            if (rx != 0f || ry != 0f) {
                boolean needLaunch = false;
                synchronized (sCapMoveLock) {
                    sCapPendingDx += rx;
                    sCapPendingDy += ry;
                    if (!sCapMoveInFlight) {
                        sCapMoveInFlight = true;
                        needLaunch = true;
                    }
                }
                if (needLaunch) {
                    try {
                        exec.execute(() -> {
                            try {
                                while (!exec.isShutdown()) {
                                    float curDx, curDy;
                                    synchronized (sCapMoveLock) {
                                        curDx = sCapPendingDx;
                                        curDy = sCapPendingDy;
                                        sCapPendingDx = 0f;
                                        sCapPendingDy = 0f;
                                        if (Math.abs(curDx) < 0.01f && Math.abs(curDy) < 0.01f) {
                                            sCapMoveInFlight = false;
                                            break;
                                        }
                                    }
                                    BridgeService curSvc = sInstance;
                                    float userMult = getMirrorCursorSpeedMult(curSvc);
                                    float resMult = 1.0f;
                                    if (curSvc != null && curSvc.mSidePhysW > 0 && curSvc.mSidePhysH > 0) {
                                        resMult = Math.max(0.85f, Math.min(2.2f,
                                                Math.min(curSvc.mSidePhysW, curSvc.mSidePhysH) / 1440f));
                                    }
                                    float mag = (float) Math.hypot(curDx, curDy);
                                    float accel = 1.0f + Math.min(1.45f, mag * 0.045f);
                                    float gain = userMult * resMult * accel;
                                    int[] res = BridgeClient.sendCapturedMirrorMouse(
                                            0, curDx * gain, curDy * gain, 0f);
                                    if (res[0] == 0) {
                                        synchronized (sCapMoveLock) {
                                            sCapPendingDx = 0f;
                                            sCapPendingDy = 0f;
                                            sCapMoveInFlight = false;
                                        }
                                        if (onExitToMain != null) onExitToMain.run();
                                        break;
                                    }
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                synchronized (sCapMoveLock) {
                                    sCapMoveInFlight = false;
                                }
                            }
                        });
                    } catch (RejectedExecutionException ignored) {
                        synchronized (sCapMoveLock) {
                            sCapPendingDx = 0f;
                            sCapPendingDy = 0f;
                            sCapMoveInFlight = false;
                        }
                    }
                }
            }
            return true;
        }
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_BUTTON_PRESS) {
            final int btn = ev.getActionButton();
            try {
                if (btn == 0 || btn == MotionEvent.BUTTON_PRIMARY) {
                    exec.execute(() -> BridgeClient.sendCapturedMirrorMouse(1, 0f, 0f, 0f));
                    return true;
                } else if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                    exec.execute(() -> BridgeClient.sendCapturedMirrorMouse(6, btn, 0f, 0f));
                    return true;
                }
            } catch (RejectedExecutionException ignored) {}
        }
        if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_BUTTON_RELEASE) {
            final int btn = ev.getActionButton();
            try {
                if (btn == 0 || btn == MotionEvent.BUTTON_PRIMARY) {
                    exec.execute(() -> BridgeClient.sendCapturedMirrorMouse(2, 0f, 0f, 0f));
                    return true;
                } else if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                    exec.execute(() -> BridgeClient.sendCapturedMirrorMouse(7, btn, 0f, 0f));
                    return true;
                }
            } catch (RejectedExecutionException ignored) {}
        }
        if (act == MotionEvent.ACTION_SCROLL) {
            final float h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL);
            final float v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            try {
                exec.execute(() -> BridgeClient.sendCapturedMirrorMouse(4, h, 0f, v));
            } catch (RejectedExecutionException ignored) {}
            return true;
        }
        return false;
    }
}
