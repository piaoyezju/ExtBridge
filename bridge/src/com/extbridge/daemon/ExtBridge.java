package com.extbridge.daemon;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.WallpaperManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ExtBridge implements Runnable {
    private static final String BINDER_DESC = "com.extbridge.daemon.IBridgeControl";
    private static final String[] TRUSTED_HELPER_CERT_SHA256 = {
            // Official release certificate (GitHub Releases / CI, built from exthelper.p12)
            "e4f67b3012a16633006c64bfc9d5a95c7bdda10826bd729777c2a3066f49117c",
            // Local development certificate (locally built APK signed with a developer keystore).
            // Keeps self-signed builds usable without touching the official trust anchor.
            "cf854b8d925f5ccf6de524143a65abc0c2c1b910574777db138c91e3b0525bc3",
    };
    private static final Pattern PKG_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$");
    private static final Pattern CLS_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_$]+(\\.[a-zA-Z0-9_$]+)+$");
    private static final String CONF_PATH = "/data/local/tmp/extbridge.conf";
    private static final String APP_CONF_PATH = "/data/local/tmp/extbridge_apps.conf";
    private static final int VD_FLAGS_COMPAT = 0x44CB; // includes ROTATES_WITH_CONTENT (0x0080)
    private static final int VD_FLAGS_DEX_FIXED = 0x444B; // strips ROTATES_WITH_CONTENT -> lock landscape desktop!
    private static final Object sDisplayLock = new Object();
    private static final ExecutorService sBinderPushExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExtBinderPush");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService sBgIoExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExtBgIo");
        t.setDaemon(true);
        return t;
    });
    private static final ScheduledExecutorService sBgSchedExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ExtBgSched");
        t.setDaemon(true);
        return t;
    });

    private static Context sCtx;
    private static DisplayManager sDm;
    private static InputManager sIm;
    private static Object sIIm;
    private static Object sWm;
    private static Object sAtm;
    private static Handler sHandler;
    private static IBinder sCtlBinder;
    private static volatile IBinder sHelperProcToken;

    private static Method sMInjectInput;
    private static Method sMSetDisplayId;
    private static Method sMGetCursorPos;
    private static Method sMAddAssoc;
    private static Method sMRemAssoc;
    private static Method sMReplaceContent;
    private static Method sMMirrorDisplay;
    private static Method sMMoveDisplayToTop;
    private static Method sMStartActivity;
    private static Method sMFocusTopTask;
    private static Method sMSetUserPreferredMode;
    private static Object sSetUserPreferredTarget;
    private static int sSetUserPreferredArgCount = 0;

    private static volatile int sExtId = -1;
    private static volatile int sLastPushedExtId = -1;
    private static volatile int sVdId = -1;
    private static volatile String sVdUniqueId;
    private static volatile VirtualDisplay sVd;
    private static volatile Object sBbq;
    private static volatile Surface sSurf;
    private static volatile SurfaceTexture sSt;
    private static volatile Object sRootSc;
    private static volatile Object sBgSc;
    private static volatile Object sScaleSc;
    private static volatile Object sMirrorSc;
    private static volatile String sRenderMode = "NONE";

    private static volatile int sPhysW = 2560;
    private static volatile int sPhysH = 1440;
    private static volatile float sPhysFps = 60.0f;
    private static volatile int sVirtW = 2560;
    private static volatile int sVirtH = 1440;
    private static volatile int sCreatedVdW = 0;
    private static volatile int sCreatedVdH = 0;
    private static volatile int sVirtDpi = 240;
    private static volatile float sVirtFps = 60.0f;
    private static volatile int sVirtRot = 0; // 0=0°, 1=90° CW, 2=180°, 3=270° CW

    private static final Object sInputLock = new Object();
    private static final Object sCursorLock = new Object();
    private static final Object sSplitLock = new Object();
    private static final int EDGE_MARGIN_MIN_PX = 48;

    private static volatile int sInputToExt = 1;
    private static volatile int sLastExtInputCnt = -1;
    private static volatile String sLastExtInputSig = "";
    private static final ArrayList<String> sBoundDescs = new ArrayList<>();

    private static volatile float sCursorX = 1280f;
    private static volatile float sCursorY = 720f;
    private static volatile int sMouseButtons = 0;
    private static long sMouseDownTime = 0L;
    private static long sSideTouchDownTime = 0L;
    private static volatile int sLastInjectStatus = 1; // 1=ok, -1=SecurityException (INJECT_EVENTS denied), -2=other error
    private static volatile long sLastInjectDenyLogMs = 0L;
    private static volatile boolean sShuttingDown = false;
    private static volatile boolean sCleanupDoneOnShutdown = false;
    private static volatile boolean sForceRebuild = false;
    private static volatile boolean sSideVdAttempted = false;
    private static volatile int sLastSideVdAttemptMode = -1;

    private static volatile boolean sSplitActive = false;
    private static volatile float sSplitRatio = 0.50f;
    private static volatile String sSplitSlot0Pkg = "";
    private static volatile String sSplitSlot1Pkg = "";
    private static volatile int sSplitSlot0TaskId = -1;
    private static volatile int sSplitSlot1TaskId = -1;
    private static volatile long sLastSplitActionMs = 0L;
    private static final int SPLIT_GAP_PX = 8;
    private static volatile int sMirrorMode = 0;       // 0=DeX独立桌面, 1=原生镜像
    private static volatile int sMirrorSideMode = 1;   // 0=纯黑原生镜像, 1=贴边信息条, 2=宽边栏+系统小组件
    private static volatile VirtualDisplay sSideVd;
    private static volatile int sSideVdId = -1;
    private static volatile Object sSideBbq;
    private static volatile Surface sSideSurf;
    private static volatile Object sSideMirSc;
    private static volatile int sMirrorSrcId = Display.DEFAULT_DISPLAY;
    private static volatile Object sMir0WrapSc;
    private static volatile Object sMir0BgSc;
    private static volatile Object sMir0Sc;
    private static volatile Object sMirCursorWrapSc;
    private static volatile Object sMirCursorSc;
    private static volatile Object sMirCursorBbq;
    private static volatile Surface sMirCursorSurf;
    private static volatile boolean sMirCursorVisible = false;
    private static volatile boolean sMirCursorLastDrawnPress = false;
    private static volatile int sMirDragDisplayId = -1;
    private static volatile Runnable sMirCursorPollTask;
    private static final Runnable sMirCursorAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (sShuttingDown || sMirrorMode != 1 || sMirCursorWrapSc == null) return;
            if (sMirMouseCaptured || sMouseButtons != 0) {
                if (sHandler != null) sHandler.postDelayed(this, 3000L);
                return;
            }
            long idle = SystemClock.uptimeMillis() - sLastCursorMoveMs;
            if (idle >= 7800L) {
                updateMirrorCursorSurface(sCursorX, sCursorY, false, false);
            } else if (sHandler != null) {
                sHandler.postDelayed(this, Math.max(500L, 8000L - idle));
            }
        }
    };
    private static volatile long sLastTouchpadMs = 0L;
    private static volatile long sLastCursorMoveMs = 0L;
    private static volatile long sLastMirCapActionMs = 0L;
    private static volatile boolean sMirMouseCaptured = false;
    private static volatile boolean sJustExitedSideEdge = false;
    private static volatile long sMirEdgeCooldownUntilMs = 0L;
    private static volatile float sLastPollD0X = -1f;
    private static volatile float sLastPollD0Y = -1f;
    private static volatile boolean sHasLastPollD0 = false;
    private static volatile boolean sCachedExtMousePresent = false;
    private static volatile long sLastExtMouseCheckMs = 0L;
    private static volatile int sVerifiedHelperUid = -1;
    private static volatile long sLastHelperUidVerifyMs = 0L;
    private static volatile long sLastNeedBinderRxMs = 0L;
    private static volatile long sLastBinderPushAttemptMs = 0L;
    private static volatile long sHomeSanitizeIntervalMs = 15000L;

    private static final class MirrorGeom {
        final int d0W, d0H, mirX, mirY, mirW, mirH;
        MirrorGeom(int d0W, int d0H, int mirX, int mirY, int mirW, int mirH) {
            this.d0W = d0W;
            this.d0H = d0H;
            this.mirX = mirX;
            this.mirY = mirY;
            this.mirW = mirW;
            this.mirH = mirH;
        }
    }
    private static volatile MirrorGeom sMirGeom = new MirrorGeom(0, 0, 0, 0, 0, 0);

    private static Class<?> sScCls;
    private static Class<?> sScBuilderCls;
    private static Class<?> sTxCls;
    private static Constructor<?> sTxCtor;
    private static Method sTxSetLayer;
    private static Method sTxSetColor;
    private static Method sTxSetAlpha;
    private static Method sTxSetWindowCrop;
    private static Method sTxSetCrop;
    private static Method sTxSetPosition;
    private static Method sTxSetScale;
    private static Method sTxSetMatrix;
    private static Method sTxShow;
    private static Method sTxHide;
    private static Method sTxReparent;
    private static Method sTxRemove;
    private static Method sTxApply;
    private static Method sTxApplySync;
    private static Method sTxClose;
    private static Method sScRelease;
    private static Object sIDm;
    private static Method sMGetDisplayInfo;
    private static volatile int sD0W = 0;
    private static volatile int sD0H = 0;
    private static volatile int sMirX = 0;
    private static volatile int sMirY = 0;
    private static volatile int sMirW = 0;
    private static volatile int sMirH = 0;
    private static volatile int sLastAppliedMirSideMode = -1;
    private static volatile long sLastMirD0SizeCheckMs = 0L;
    private static volatile DisplayManager.DisplayListener sMirrorDisplayListener;
    private static volatile long sLastMirTelemetryMs = 0L;
    private static volatile String sCachedAudioRoute = "平板外放";
    private static volatile String sCachedAudioDetail = "";
    private static volatile String sCachedMediaPkg = "";
    private static volatile int sCachedMediaState = 0;
    private static volatile String sCachedMediaTitle = "";
    private static volatile String sCachedMediaSub = "";
    private static volatile String sCachedCalendarText = "";
    private static volatile String sCachedBattDetail = "";
    private static volatile String sCachedWifiDetail = "";
    private static volatile int sFullscreenPolicy = 1; // 1=强制横屏大屏全屏策略, 0=兼容
    private static volatile boolean sHasUserConf = false;
    private static volatile boolean sSavedSysPrefs = false;
    private static int sOrigForceResizable = 0;
    private static int sOrigNonResizableMw = 0;
    private static final HashMap<String, Integer> sCompatOverriddenModes = new HashMap<>();
    private static final HashMap<String, Integer> sAppModes = new HashMap<>(); // 0=default, 1=land_full, 2=port_compat (freeform disabled on DeX)

    private static volatile int sCachedWpId = Integer.MIN_VALUE;
    private static volatile byte[] sCachedWpBytes = null;
    private static volatile long sLastSysCheckMs = 0L;
    private static volatile long sLastHomeCheckMs = 0L;
    private static volatile long sLastHomeSanitizeMs = 0L;
    private static volatile String sCachedWifiSsid = "";
    private static volatile String sCachedBtName = "";
    private static final String DIM_BACKUP_PATH = "/data/local/tmp/extbridge_dim.bak";
    private static final String CTL_SOCKET_NAME = "com.extbridge.daemon.ctl";
    private static LocalServerSocket sCtlServerSocket;
    private static volatile boolean sScreenDimmed = false;
    private static int sSavedBrightMode = 0;
    private static int sSavedBrightInt = 100;
    private static float sSavedBrightFloat = -1f;

    private static boolean isTrustedDaemonServerUid(int uid) {
        return uid == 0 || uid == 1000 || uid == 2000;
    }

    private static void dropRootFailClosed() {
        int initUid = android.os.Process.myUid();
        int osUid = -1;
        try { osUid = android.system.Os.getuid(); } catch (Throwable ignored) {}
        if (initUid != 0 && osUid != 0) return;
        try {
            try {
                Class<?> lcCls = Class.forName("libcore.io.Libcore");
                Object osObj = lcCls.getField("os").get(null);
                if (osObj != null) {
                    for (Method m : osObj.getClass().getMethods()) {
                        if ("setgroups".equals(m.getName()) && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == int[].class) {
                            m.invoke(osObj, (Object) new int[]{2000});
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            android.system.Os.setgid(2000);
            android.system.Os.setuid(2000);
            int curUid = android.system.Os.getuid();
            int curEuid = android.system.Os.geteuid();
            int curGid = android.system.Os.getgid();
            int curEgid = android.system.Os.getegid();
            if (android.os.Process.myUid() != 2000 || curUid != 2000 || curEuid != 2000
                    || curGid != 2000 || curEgid != 2000) {
                System.err.println("EXTBRIDGE_FATAL_DROP_ROOT_INCOMPLETE uid=" + curUid + " gid=" + curGid);
                System.exit(1);
                return;
            }
            // Best-effort SELinux dynamic transition to u:r:shell:s0 if launched without runcon
            try (FileOutputStream attrOut = new FileOutputStream("/proc/thread-self/attr/current")) {
                attrOut.write("u:r:shell:s0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            } catch (Throwable ignored) {}
            System.out.println("EXTBRIDGE_ROOT_DROPPED_TO_SHELL uid=2000 gid=2000");
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_FATAL_DROP_ROOT_FAILED: " + t);
            System.exit(1);
        }
    }

    private static boolean ensureSingleInstanceAndStartCtlServer() {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                sCtlServerSocket = new LocalServerSocket(CTL_SOCKET_NAME);
                break;
            } catch (Throwable bindBusy) {
                LocalSocket client = null;
                boolean untrustedSquatter = false;
                try {
                    client = new LocalSocket();
                    client.connect(new LocalSocketAddress(CTL_SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
                    android.net.Credentials peer = client.getPeerCredentials();
                    if (peer == null || !isTrustedDaemonServerUid(peer.getUid())) {
                        System.err.println("EXTBRIDGE_CTL_SQUATTER_REJECTED peerUid=" + (peer == null ? -1 : peer.getUid()));
                        untrustedSquatter = true;
                    } else {
                        client.setSoTimeout(4500);
                        client.getOutputStream().write("SHUTDOWN\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        client.getOutputStream().flush();
                        BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        r.readLine();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (client != null) {
                        try { client.close(); } catch (Throwable ignored) {}
                    }
                }
                if (untrustedSquatter) {
                    return false;
                }
                SystemClock.sleep(200);
            }
        }
        if (sCtlServerSocket == null) return false;
        Thread ctlThread = new Thread(() -> {
            while (!sShuttingDown && sCtlServerSocket != null) {
                LocalSocket sock = null;
                try {
                    sock = sCtlServerSocket.accept();
                    sock.setSoTimeout(1500);
                    android.net.Credentials cred = sock.getPeerCredentials();
                    if (cred == null || !isTrustedCaller(cred.getUid())) {
                        continue;
                    }
                    BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    String cmd = br.readLine();
                    if ("SHUTDOWN".equals(cmd)) {
                        sShuttingDown = true;
                        try { cleanupDisplay(true); } catch (Throwable ignored) {}
                        try {
                            sock.getOutputStream().write("DONE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            sock.getOutputStream().flush();
                        } catch (Throwable ignored) {}
                        try { sCtlServerSocket.close(); } catch (Throwable ignored) {}
                        try { sock.close(); } catch (Throwable ignored) {}
                        SystemClock.sleep(40);
                        System.exit(0);
                        return;
                    } else if ("NEED_BINDER".equals(cmd)) {
                        enqueuePushBinderToHelper(false);
                        try {
                            sock.getOutputStream().write("OK\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            sock.getOutputStream().flush();
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {
                    if (sShuttingDown) break;
                } finally {
                    if (sock != null) {
                        try { sock.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }, "ExtCtlSocket");
        ctlThread.setDaemon(true);
        ctlThread.start();
        return true;
    }

    public static void main(String[] args) {
        try {
            dropRootFailClosed();
            if (args != null && args.length > 0 && "--verify-helper".equals(args[0])) {
                Looper.prepareMainLooper();
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Object at = atCls.getMethod("systemMain").invoke(null);
                sCtx = (Context) atCls.getMethod("getSystemContext").invoke(at);
                int vUid = getVerifiedHelperUid();
                System.exit(vUid > 0 ? 0 : 3);
                return;
            }
            if (args != null && args.length > 0 && "--stop".equals(args[0])) {
                LocalSocket client = null;
                try {
                    client = new LocalSocket();
                    client.connect(new LocalSocketAddress(CTL_SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
                    android.net.Credentials peer = client.getPeerCredentials();
                    if (peer != null && isTrustedDaemonServerUid(peer.getUid())) {
                        client.setSoTimeout(6500);
                        client.getOutputStream().write("SHUTDOWN\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        client.getOutputStream().flush();
                        new BufferedReader(new InputStreamReader(client.getInputStream())).readLine();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (client != null) {
                        try { client.close(); } catch (Throwable ignored) {}
                    }
                }
                System.exit(0);
                return;
            }
            if (!ensureSingleInstanceAndStartCtlServer()) {
                System.err.println("EXTBRIDGE_LOCK_BUSY another daemon instance still holds @" + CTL_SOCKET_NAME);
                System.exit(2);
                return;
            }
            Looper.prepareMainLooper();
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("systemMain").invoke(null);
            Context sysCtx = (Context) atCls.getMethod("getSystemContext").invoke(at);
            try {
                sCtx = sysCtx.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable t) {
                System.err.println("createPackageContext failed: " + t);
                sCtx = sysCtx;
            }
            sCtx = fixShellContext(sCtx);
            sDm = (DisplayManager) sCtx.getSystemService(Context.DISPLAY_SERVICE);
            sIm = (InputManager) sCtx.getSystemService(Context.INPUT_SERVICE);

            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getSvc = smCls.getMethod("getService", String.class);

            IBinder wmB = (IBinder) getSvc.invoke(null, "window");
            sWm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, wmB);

            IBinder imB = (IBinder) getSvc.invoke(null, "input");
            sIIm = Class.forName("android.hardware.input.IInputManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, imB);

            IBinder atmB = (IBinder) getSvc.invoke(null, "activity_task");
            sAtm = Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, atmB);

            initReflection();
            // Always recover stale external input associations and orphan brightness backup on startup
            unbindInput();
            restoreOrphanScreenDimBackup();
            loadConf();

            sCtlBinder = new BridgeBinder();
            sHandler = new Handler(Looper.getMainLooper());
            // Push Binder IMMEDIATELY on dedicated thread (<250ms) BEFORE queuing slow dumpsys/appops jobs!
            enqueuePushBinderToHelper(true);
            for (long retryDelayMs : new long[]{400L, 1100L, 2400L}) {
                sHandler.postDelayed(() -> {
                    if (!sShuttingDown && (sHelperProcToken == null || !sHelperProcToken.pingBinder())) {
                        enqueuePushBinderToHelper(true);
                    }
                }, retryDelayMs);
            }

            try {
                IntentFilter flt = new IntentFilter();
                flt.addAction("com.extbridge.daemon.NEED_BINDER");
                BroadcastReceiver needBinderRx = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        if (i == null || !"com.extbridge.daemon.NEED_BINDER".equals(i.getAction())) return;
                        if (Build.VERSION.SDK_INT >= 34) {
                            int senderUid = getSentFromUid();
                            if (!isTrustedCaller(senderUid)) return;
                        }
                        long now = SystemClock.uptimeMillis();
                        if (now - sLastNeedBinderRxMs < 500L) return;
                        sLastNeedBinderRxMs = now;
                        enqueuePushBinderToHelper(false);
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) {
                    sCtx.registerReceiver(needBinderRx, flt, Context.RECEIVER_EXPORTED);
                    if (sysCtx != null && sysCtx != sCtx) {
                        try { sysCtx.registerReceiver(needBinderRx, flt, Context.RECEIVER_EXPORTED); } catch (Throwable ignored) {}
                    }
                } else {
                    sCtx.registerReceiver(needBinderRx, flt);
                    if (sysCtx != null && sysCtx != sCtx) {
                        try { sysCtx.registerReceiver(needBinderRx, flt); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                sShuttingDown = true;
                cleanupDisplay(true);
            }));

            System.out.println("EXTBRIDGE_V3_DAEMON_STARTED cfg=" + sVirtW + "x" + sVirtH + "@" + sVirtDpi + "dpi," + sVirtFps + "fps");
            System.out.flush();

            sBgIoExec.execute(() -> {
                if (getVerifiedHelperUid() > 0) {
                    try { runCmdOut("appops", "set", "com.extbridge.helper", "10021", "allow"); } catch (Throwable ignored) {}
                    try { runCmdOut("appops", "set", "com.extbridge.helper", "ACCESS_RESTRICTED_SETTINGS", "allow"); } catch (Throwable ignored) {}
                    try { runCmdOut("appops", "set", "com.extbridge.helper", "SYSTEM_ALERT_WINDOW", "allow"); } catch (Throwable ignored) {}
                }
                grantAppWidgetBind();
                healStrayCompatOverrides(false);
                sanitizeSystemHomeTasks(true);
            });
            sHandler.post(new ExtBridge());
            Looper.loop();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void initReflection() {
        try {
            sMInjectInput = sIIm.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Throwable t) {
            for (Method m : sIIm.getClass().getMethods()) {
                if (m.getName().equals("injectInputEvent")) { sMInjectInput = m; break; }
            }
        }
        try {
            sMSetDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (Throwable ignored) {}
        try {
            sMGetCursorPos = sIIm.getClass().getMethod("getCursorPositionInLogicalDisplay", int.class);
        } catch (Throwable ignored) {}
        try {
            sMAddAssoc = InputManager.class.getMethod("addUniqueIdAssociationByDescriptor", String.class, String.class);
            sMRemAssoc = InputManager.class.getMethod("removeUniqueIdAssociationByDescriptor", String.class);
        } catch (Throwable ignored) {}
        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("replaceContentOnDisplay")) sMReplaceContent = m;
                else if (m.getName().equals("mirrorDisplay")) sMMirrorDisplay = m;
                else if (m.getName().equals("moveDisplayToTop") && m.getParameterCount() == 2) sMMoveDisplayToTop = m;
            }
        } catch (Throwable ignored) {}
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("startActivity") && m.getParameterCount() == 11) {
                    sMStartActivity = m;
                } else if (m.getName().equals("focusTopTask") && m.getParameterCount() == 1) {
                    sMFocusTopTask = m;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            try { dmgCls.getMethod("disableLocalDisplayInfoCaches").invoke(null); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder dmB = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "display");
            if (dmB != null) {
                sIDm = Class.forName("android.hardware.display.IDisplayManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, dmB);
                if (sIDm != null) {
                    sMGetDisplayInfo = sIDm.getClass().getMethod("getDisplayInfo", int.class);
                }
            }
        } catch (Throwable ignored) {}
        initUserPreferredModeReflection();
        initSurfaceReflection();
    }

    private static void initSurfaceReflection() {
        try {
            sScCls = Class.forName("android.view.SurfaceControl");
            sScBuilderCls = Class.forName("android.view.SurfaceControl$Builder");
            sTxCls = Class.forName("android.view.SurfaceControl$Transaction");
            sTxCtor = sTxCls.getConstructor();
            sTxSetLayer = sTxCls.getMethod("setLayer", sScCls, int.class);
            sTxSetColor = sTxCls.getMethod("setColor", sScCls, float[].class);
            sTxSetAlpha = sTxCls.getMethod("setAlpha", sScCls, float.class);
            sTxSetWindowCrop = sTxCls.getMethod("setWindowCrop", sScCls, int.class, int.class);
            try { sTxSetCrop = sTxCls.getMethod("setCrop", sScCls, Rect.class); } catch (Throwable ignored) {}
            sTxSetPosition = sTxCls.getMethod("setPosition", sScCls, float.class, float.class);
            sTxSetScale = sTxCls.getMethod("setScale", sScCls, float.class, float.class);
            sTxSetMatrix = sTxCls.getMethod("setMatrix", sScCls, float.class, float.class, float.class, float.class);
            sTxShow = sTxCls.getMethod("show", sScCls);
            sTxHide = sTxCls.getMethod("hide", sScCls);
            sTxReparent = sTxCls.getMethod("reparent", sScCls, sScCls);
            sTxRemove = sTxCls.getMethod("remove", sScCls);
            sTxApply = sTxCls.getMethod("apply");
            try { sTxApplySync = sTxCls.getMethod("apply", boolean.class); } catch (Throwable ignored) {}
            try { sTxClose = sTxCls.getMethod("close"); } catch (Throwable ignored) {}
            sScRelease = sScCls.getMethod("release");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static Object newSurfaceTx() throws Exception {
        if (sTxCtor == null) initSurfaceReflection();
        return sTxCtor.newInstance();
    }

    private static void closeSurfaceTx(Object tx) {
        if (tx == null) return;
        if (sTxClose != null) {
            try { sTxClose.invoke(tx); return; } catch (Throwable ignored) {}
        }
        if (tx instanceof AutoCloseable) {
            try { ((AutoCloseable) tx).close(); } catch (Throwable ignored) {}
        }
    }

    private static void initUserPreferredModeReflection() {
        // 1. DisplayManager (Android 17+ has 3-arg; some ROMs have 2-arg)
        try {
            sMSetUserPreferredMode = DisplayManager.class.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
            sSetUserPreferredTarget = sDm;
            sSetUserPreferredArgCount = 3;
            System.out.println("EXTBRIDGE_PHYS_API=DisplayManager(3)");
            return;
        } catch (Throwable ignored) {}
        try {
            sMSetUserPreferredMode = DisplayManager.class.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class);
            sSetUserPreferredTarget = sDm;
            sSetUserPreferredArgCount = 2;
            System.out.println("EXTBRIDGE_PHYS_API=DisplayManager(2)");
            return;
        } catch (Throwable ignored) {}

        // 2. DisplayManagerGlobal (Android 13-16 standard hidden API: 2-arg / 3-arg)
        try {
            Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgCls.getMethod("getInstance").invoke(null);
            if (dmg != null) {
                try {
                    sMSetUserPreferredMode = dmgCls.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class);
                    sSetUserPreferredTarget = dmg;
                    sSetUserPreferredArgCount = 2;
                    System.out.println("EXTBRIDGE_PHYS_API=DisplayManagerGlobal(2)");
                    return;
                } catch (Throwable ignored) {}
                try {
                    sMSetUserPreferredMode = dmgCls.getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
                    sSetUserPreferredTarget = dmg;
                    sSetUserPreferredArgCount = 3;
                    System.out.println("EXTBRIDGE_PHYS_API=DisplayManagerGlobal(3)");
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // 3. IDisplayManager Binder proxy directly (2-arg / 3-arg)
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder dmB = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "display");
            if (dmB != null) {
                Object idm = Class.forName("android.hardware.display.IDisplayManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, dmB);
                if (idm != null) {
                    try {
                        sMSetUserPreferredMode = idm.getClass().getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class);
                        sSetUserPreferredTarget = idm;
                        sSetUserPreferredArgCount = 2;
                        System.out.println("EXTBRIDGE_PHYS_API=IDisplayManager(2)");
                        return;
                    } catch (Throwable ignored) {}
                    try {
                        sMSetUserPreferredMode = idm.getClass().getMethod("setUserPreferredDisplayMode", int.class, Display.Mode.class, boolean.class);
                        sSetUserPreferredTarget = idm;
                        sSetUserPreferredArgCount = 3;
                        System.out.println("EXTBRIDGE_PHYS_API=IDisplayManager(3)");
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        System.err.println("EXTBRIDGE_PHYS_API=NONE (will fallback to cmd display)");
    }

    private static float clampFps(float fps, float def) {
        if (!Float.isFinite(fps) || fps < 15f) return def;
        return Math.min(360f, fps);
    }

    private static boolean isValidPackageName(String pkg) {
        return pkg != null && pkg.length() >= 3 && pkg.length() <= 160 && PKG_NAME_PATTERN.matcher(pkg).matches();
    }

    private static boolean isValidClassName(String cls) {
        return cls != null && cls.length() >= 3 && cls.length() <= 220 && CLS_NAME_PATTERN.matcher(cls).matches();
    }

    private static void resetCompatForPkg(String pkg) {
        if (!isValidPackageName(pkg)) return;
        String qp = pkg.replace("'", "");
        runCmdOut("sh", "-c", "am compat reset-all --no-kill '" + qp
                + "' >/dev/null 2>&1 || am compat reset-all '" + qp + "' >/dev/null 2>&1");
    }

    private static void loadConf() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine();
                if (line == null) return;
                String[] parts = line.trim().split(",");
                if (parts.length >= 4) {
                    int w = Integer.parseInt(parts[0].trim());
                    int h = Integer.parseInt(parts[1].trim());
                    int d = Integer.parseInt(parts[2].trim());
                    float fps = Float.parseFloat(parts[3].trim());
                    if (isValidDims(w, h) && d >= 80 && d <= 640) {
                        sVirtW = w;
                        sVirtH = h;
                        sVirtDpi = d;
                        sVirtFps = clampFps(fps, 60f);
                        sCursorX = sVirtW * 0.5f;
                        sCursorY = sVirtH * 0.5f;
                        sHasUserConf = true;
                    }
                    if (parts.length >= 5) {
                        sFullscreenPolicy = Integer.parseInt(parts[4].trim()) != 0 ? 1 : 0;
                    }
                    if (parts.length >= 6) {
                        sVirtRot = normalizeRot(Integer.parseInt(parts[5].trim()));
                    }
                    if (parts.length >= 7) {
                        sMirrorMode = 0; // never boot into mirror lock or hijack Display 0 launcher
                    }
                    if (parts.length >= 8) {
                        int sm = Integer.parseInt(parts[7].trim());
                        if (sm >= 0 && sm <= 3) sMirrorSideMode = sm;
                    }
                }
            }
        } catch (Throwable ignored) {}
        loadAppModes();
    }

    private static final String HEAL_MARKER_PATH = "/data/local/tmp/extbridge_healed_v2";

    private static void loadAppModes() {
        File f = new File(APP_CONF_PATH);
        boolean firstConf = !f.exists();
        boolean prunedLegacyFreeform = false;
        synchronized (sAppModes) {
            sAppModes.clear();
            if (firstConf) {
                sAppModes.put("com.phoenix.read", 1);
                saveAppModesLocked();
            } else {
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        try {
                            String t = line.trim();
                            int eq = t.indexOf('=');
                            if (eq <= 0) continue;
                            String pkg = t.substring(0, eq).trim();
                            if (!isValidPackageName(pkg)) continue;
                            int m = Integer.parseInt(t.substring(eq + 1).trim());
                            if (m == 1 || m == 2) {
                                if (sAppModes.size() < 512 || sAppModes.containsKey(pkg)) {
                                    sAppModes.put(pkg, m);
                                }
                            } else if (m == 3) {
                                prunedLegacyFreeform = true;
                            }
                        } catch (Throwable ignoredLine) {}
                    }
                } catch (Throwable ignored) {}
                if (prunedLegacyFreeform) {
                    saveAppModesLocked();
                }
            }
        }
        if (firstConf || !new File(HEAL_MARKER_PATH).exists()) {
            // Run stray compat healing asynchronously on sBgIoExec so first-boot startup never blocks Binder handshake
            sBgIoExec.execute(() -> {
                healStrayCompatOverrides(false);
                try (FileWriter fw = new FileWriter(HEAL_MARKER_PATH, false)) {
                    fw.write("1\n");
                } catch (Throwable ignored) {}
            });
        }
    }

    private static void healStrayCompatOverrides(boolean resetConfiguredToo) {
        try {
            HashSet<String> toReset = new HashSet<>();
            toReset.add("com.tencent.mm");
            toReset.add("com.quark.browser");
            toReset.add("com.xunmeng.pinduoduo");
            toReset.add("com.ss.android.ugc.aweme");
            toReset.add("com.baidu.netdisk");
            String dump = runCmdOut("sh", "-c",
                    "dumpsys platform_compat 2>/dev/null | grep -E 'ChangeId\\((254631730|310816437|236283604|265464455|263259275);'");
            if (dump != null) {
                for (String line : dump.split("\n")) {
                    int idx = line.indexOf("packageOverrides={");
                    if (idx < 0) continue;
                    int start = idx + "packageOverrides={".length();
                    int end = line.indexOf('}', start);
                    if (end <= start) continue;
                    String inner = line.substring(start, end);
                    for (String kv : inner.split(",")) {
                        int eq = kv.indexOf('=');
                        if (eq <= 0) continue;
                        String pkg = kv.substring(0, eq).trim();
                        if (isValidPackageName(pkg)) toReset.add(pkg);
                    }
                }
            }
            if (!resetConfiguredToo) {
                synchronized (sAppModes) {
                    for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                        int m = e.getValue() == null ? 0 : e.getValue();
                        if (m == 1 || m == 2) toReset.remove(e.getKey());
                    }
                }
            }
            for (String pkg : toReset) {
                resetCompatForPkg(pkg);
            }
        } catch (Throwable ignored) {}
    }

    private static void saveAppModesLocked() {
        try (FileWriter fw = new FileWriter(APP_CONF_PATH, false)) {
            for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                String k = e.getKey();
                int m = e.getValue() == null ? 0 : e.getValue();
                if (isValidPackageName(k) && (m == 1 || m == 2)) {
                    fw.write(k + "=" + m + "\n");
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int getAppMode(String pkg) {
        if (!isValidPackageName(pkg)) return 0;
        synchronized (sAppModes) {
            Integer v = sAppModes.get(pkg);
            return v != null ? v : 0;
        }
    }

    private static void saveConf() {
        try (FileWriter fw = new FileWriter(CONF_PATH, false)) {
            int persistMode = 0;
            fw.write(sVirtW + "," + sVirtH + "," + sVirtDpi + "," + sVirtFps + "," + sFullscreenPolicy + "," + sVirtRot + "," + persistMode + "," + sMirrorSideMode + "\n");
        } catch (Throwable ignored) {}
    }

    @Override
    public void run() {
        if (sShuttingDown) return;
        try {
            tick();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        if (!sShuttingDown && sHandler != null) {
            sHandler.postDelayed(this, 1500);
        }
    }

    private static Display findExternalDisplay() {
        Display[] displays = sDm.getDisplays();
        if (displays == null) return null;
        for (Display d : displays) {
            if (d == null) continue;
            // Display.TYPE_EXTERNAL == 2
            if (getDisplayType(d) == 2) return d;
        }
        return null;
    }

    private static int getDisplayType(Display d) {
        try {
            return (Integer) Display.class.getMethod("getType").invoke(d);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean isHelperProcessRunning() {
        int helperUid = getVerifiedHelperUid();
        if (helperUid <= 0) return false;
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder amB = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "activity");
            if (amB == null) return false;
            Object am = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, amB);
            if (am == null) return false;
            @SuppressWarnings("unchecked")
            List<ActivityManager.RunningAppProcessInfo> procs =
                    (List<ActivityManager.RunningAppProcessInfo>) am.getClass().getMethod("getRunningAppProcesses").invoke(am);
            if (procs == null) return false;
            for (ActivityManager.RunningAppProcessInfo info : procs) {
                if (info != null && info.uid == helperUid && "com.extbridge.helper".equals(info.processName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void tick() {
        long nowMs = SystemClock.uptimeMillis();
        if ((sHelperProcToken == null || !sHelperProcToken.pingBinder())
                && (nowMs - sLastBinderPushAttemptMs >= 2500L)
                && isHelperProcessRunning()) {
            enqueuePushBinderToHelper(false);
        }
        if (nowMs - sLastHomeCheckMs >= 3000L) {
            sLastHomeCheckMs = nowMs;
            sBgIoExec.execute(() -> sanitizeSystemHomeTasks(false));
        }
        synchronized (sDisplayLock) {
            if (sShuttingDown) return;
            Display ext = findExternalDisplay();
            if (ext == null) {
                sLastPushedExtId = -1;
                if (sExtId >= 0 || sVd != null || sMir0Sc != null || sSideVd != null) {
                    System.out.println("EXTBRIDGE_DETACHED");
                    cleanupDisplay(false);
                }
                return;
            }
            int extId = ext.getDisplayId();
            int prevPhysW = sPhysW;
            int prevPhysH = sPhysH;
            float prevPhysFps = sPhysFps;
            Display.Mode curMode = ext.getMode();
            if (curMode != null) {
                sPhysW = curMode.getPhysicalWidth();
                sPhysH = curMode.getPhysicalHeight();
                sPhysFps = curMode.getRefreshRate();
            }
            boolean physSizeChanged = (prevPhysW != sPhysW || prevPhysH != sPhysH);
            boolean physFpsChanged = Math.abs(prevPhysFps - sPhysFps) > 0.2f;

            if (sMirrorMode == 1) {
                if (sVd != null) {
                    cleanupDisplay(false);
                }
                if (sForceRebuild) {
                    sForceRebuild = false;
                    sSideVdAttempted = false;
                    cleanupDisplay(false);
                }
                if (physSizeChanged && (sMir0Sc != null || sSideVd != null)) {
                    sSideVdAttempted = false;
                    cleanupDisplay(false);
                }
                // Foldables expose the cover panel as its own logical display (Samsung Flip/Fold:
                // display 0 = inner, extra INTERNAL display = cover). Mirroring display 0 while the
                // device is folded mirrors a sleeping display and can even force the inner panel to
                // the top, blanking the cover screen - so follow whichever built-in panel is active.
                int activeSrcId = pickActiveBuiltinDisplayId();
                if (sMir0Sc != null && activeSrcId > 0 && activeSrcId != sMirrorSrcId) {
                    System.out.println("EXTBRIDGE_MIRROR_SRC_SWITCH " + sMirrorSrcId + " -> " + activeSrcId);
                    sSideVdAttempted = false;
                    cleanupDisplay(false);
                }
                boolean needSide = (sMirrorSideMode > 0);
                boolean stockFallback = "NATIVE_MIRROR_STOCK".equals(sRenderMode);
                boolean sideRetryNeeded = needSide && sSideVd == null && (!sSideVdAttempted || sLastSideVdAttemptMode != sMirrorSideMode);
                boolean needAttachMirror = (extId != sExtId)
                        || (!stockFallback && (sRootSc == null || sMir0Sc == null || sideRetryNeeded || (!needSide && sSideVd != null)));
                if (needAttachMirror) {
                    cleanupDisplay(false);
                    attachMirrorDisplay(extId);
                    sLastPushedExtId = extId;
                } else {
                    boolean geomUpdated = false;
                    if (!stockFallback) {
                        geomUpdated = updateMirrorGeometry(true);
                        reassert();
                        if (sSideVdId > 0) {
                            final int svd = sSideVdId;
                            sBgIoExec.execute(() -> rescueStrayTasksOnSideVd(svd));
                        }
                    }
                    if ((physSizeChanged || physFpsChanged) && !geomUpdated) {
                        notifyHelperSync();
                    }
                    if (extId != sLastPushedExtId) {
                        sLastPushedExtId = extId;
                        if (sHelperProcToken == null || !sHelperProcToken.pingBinder()) {
                            enqueuePushBinderToHelper(true);
                        }
                    }
                }
                return;
            }

            if (sMir0Sc != null || sSideVd != null || "NATIVE_MIRROR_STOCK".equals(sRenderMode)) {
                sSideVdAttempted = false;
                cleanupDisplay(false);
            }

            if (sForceRebuild && sVd != null) {
                sForceRebuild = false;
                cleanupDisplay(false);
            }

            if (extId != sExtId || sVd == null) {
                cleanupDisplay(false);
                attachDisplay(extId);
                scheduleRehomeExtLauncher(sVdId);
                sLastPushedExtId = extId;
            } else {
                // Safety net: an HDMI hotplug may make One UI drop the ext launcher on display 0,
                // which would flip a folded device to the (invisible) inner panel.
                enforceExtLauncherOffPhoneDisplay();
                if (physSizeChanged) {
                    applySurfaceTransform();
                }
                if (physSizeChanged || physFpsChanged) {
                    notifyHelperSync();
                }
                reassert();
                checkInput();
                enforceRunningExtTasksFullscreen(sFullscreenPolicy == 1);
                if (extId != sLastPushedExtId) {
                    sLastPushedExtId = extId;
                    if (sHelperProcToken == null || !sHelperProcToken.pingBinder()) {
                        enqueuePushBinderToHelper(true);
                    }
                }
            }
        }
    }

    private static boolean isValidDims(int w, int h) {
        return Math.max(w, h) >= 640 && Math.max(w, h) <= 7680
                && Math.min(w, h) >= 480 && Math.min(w, h) <= 4320;
    }

    private static int normalizeRot(int rot) {
        return ((rot % 4) + 4) % 4;
    }

    private static int computeAutoDpi(int w, int h) {
        int minEdge = Math.min(w, h);
        return Math.max(120, Math.min(280, Math.round(minEdge * 160f / 1440f)));
    }

    /**
     * Picks the best native panel EDID mode (preferring >=50Hz so 4K@30Hz compatibility timings
     * do not override true native 3440x1440@60Hz or 2560x1440@60Hz+ panel modes).
     */
    private static Display.Mode pickBestNativeEdidMode(Display ext) {
        if (ext == null) return null;
        try {
            Display.Mode[] modes = ext.getSupportedModes();
            if (modes == null || modes.length == 0) return null;
            boolean has50Plus = false;
            for (Display.Mode m : modes) {
                if (m != null && m.getRefreshRate() >= 49.5f && isValidDims(m.getPhysicalWidth(), m.getPhysicalHeight())) {
                    has50Plus = true;
                    break;
                }
            }
            Display.Mode best = null;
            long bestArea = -1L;
            float bestFpsScore = -1f;
            for (Display.Mode m : modes) {
                if (m == null) continue;
                int mw = m.getPhysicalWidth();
                int mh = m.getPhysicalHeight();
                float mf = m.getRefreshRate();
                if (!isValidDims(mw, mh)) continue;
                if (has50Plus && mf < 49.5f) continue;
                long area = (long) mw * (long) mh;
                // Prefer smooth refresh up to 120Hz cleanly without penalizing 60Hz native ultrawides
                float fpsScore = Math.min(mf, 120f);
                if (area > bestArea || (area == bestArea && fpsScore > bestFpsScore + 0.2f)) {
                    best = m;
                    bestArea = area;
                    bestFpsScore = fpsScore;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void attachDisplay(int extId) {
        sForceRebuild = false;
        try {
            if (!sHasUserConf) {
                Display extDisp = findExternalDisplay();
                Display.Mode bestNative = pickBestNativeEdidMode(extDisp);
                if (extDisp != null && bestNative != null) {
                    long curArea = (long) Math.max(0, sPhysW) * (long) Math.max(0, sPhysH);
                    long bestArea = (long) bestNative.getPhysicalWidth() * (long) bestNative.getPhysicalHeight();
                    if (bestArea > curArea || (bestArea == curArea && sPhysFps < 49.5f && bestNative.getRefreshRate() >= 49.5f)) {
                        if (invokeSetUserPreferredMode(extId, bestNative)) {
                            SystemClock.sleep(120);
                            Display refreshed = findExternalDisplay();
                            Display.Mode nm = (refreshed != null) ? refreshed.getMode() : null;
                            if (nm != null) {
                                sPhysW = nm.getPhysicalWidth();
                                sPhysH = nm.getPhysicalHeight();
                                sPhysFps = nm.getRefreshRate();
                            }
                        }
                    }
                }
                if (isValidDims(sPhysW, sPhysH)) {
                    boolean physSwap = (normalizeRot(sVirtRot) & 1) == 1;
                    sVirtW = physSwap ? sPhysH : sPhysW;
                    sVirtH = physSwap ? sPhysW : sPhysH;
                    sVirtDpi = computeAutoDpi(sVirtW, sVirtH);
                    sVirtFps = (sPhysFps >= 20f && sPhysFps <= 240f) ? sPhysFps : 60f;
                }
            }
            System.out.println("EXTBRIDGE_ATTACHING extId=" + extId + " phys=" + sPhysW + "x" + sPhysH + "@" + sPhysFps + " virt=" + sVirtW + "x" + sVirtH + "@" + sVirtDpi + " rot=" + (sVirtRot * 90));
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> bCls = Class.forName("android.view.SurfaceControl$Builder");

            Object bRoot = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bRoot, "ExtRoot");
            bCls.getMethod("setContainerLayer").invoke(bRoot);
            bCls.getMethod("setHidden", boolean.class).invoke(bRoot, false);
            sRootSc = bCls.getMethod("build").invoke(bRoot);

            Object bBg = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bBg, "ExtBg");
            bCls.getMethod("setColorLayer").invoke(bBg);
            bCls.getMethod("setParent", scCls).invoke(bBg, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bBg, false);
            sBgSc = bCls.getMethod("build").invoke(bBg);

            Object bScale = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bScale, "ExtScale");
            bCls.getMethod("setContainerLayer").invoke(bScale);
            bCls.getMethod("setParent", scCls).invoke(bScale, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bScale, false);
            sScaleSc = bCls.getMethod("build").invoke(bScale);

            boolean bbqOk = false;
            try {
                Object bMir = bCls.getConstructor().newInstance();
                bCls.getMethod("setName", String.class).invoke(bMir, "ExtDisplay");
                bCls.getMethod("setBLASTLayer").invoke(bMir);
                bCls.getMethod("setParent", scCls).invoke(bMir, sScaleSc);
                bCls.getMethod("setHidden", boolean.class).invoke(bMir, false);
                sMirrorSc = bCls.getMethod("build").invoke(bMir);

                Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                Constructor<?> bbqCtor = bbqCls.getConstructor(String.class, boolean.class);
                sBbq = bbqCtor.newInstance("ExtBBQ", true);
                bbqCls.getMethod("update", scCls, int.class, int.class, int.class).invoke(sBbq, sMirrorSc, sVirtW, sVirtH, 1);
                sSurf = (Surface) bbqCls.getMethod("createSurface").invoke(sBbq);
                bbqOk = (sSurf != null);
                sRenderMode = "BBQ";
            } catch (Throwable t) {
                t.printStackTrace();
                if (sMirrorSc != null) {
                    try {
                        Object txErr = newSurfaceTx();
                        try { sTxRemove.invoke(txErr, sMirrorSc); sTxApply.invoke(txErr); } finally { closeSurfaceTx(txErr); }
                        sScRelease.invoke(sMirrorSc);
                    } catch (Throwable ignored) {}
                    sMirrorSc = null;
                }
                sRenderMode = "MIRROR";
            }

            if (!bbqOk) {
                sSt = new SurfaceTexture(0);
                sSt.setDefaultBufferSize(sVirtW, sVirtH);
                sSurf = new Surface(sSt);
            }

            if (sSurf != null && Build.VERSION.SDK_INT >= 30) {
                try {
                    sSurf.setFrameRate(sVirtFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                } catch (Throwable ignored) {}
            }

            // Build VirtualDisplayConfig
            Class<?> vdcBCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object vdcB = vdcBCls.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance("RedmiKPad-Extended", sVirtW, sVirtH, sVirtDpi);
            int vdFlags = (sFullscreenPolicy == 1) ? VD_FLAGS_DEX_FIXED : VD_FLAGS_COMPAT;
            vdcBCls.getMethod("setFlags", int.class).invoke(vdcB, vdFlags);
            try { vdcBCls.getMethod("setHomeSupported", boolean.class).invoke(vdcB, false); } catch (Throwable ignored) {}
            try { vdcBCls.getMethod("setRequestedRefreshRate", float.class).invoke(vdcB, sVirtFps); } catch (Throwable ignored) {}
            vdcBCls.getMethod("setSurface", Surface.class).invoke(vdcB, sSurf);
            Object vdc = vdcBCls.getMethod("build").invoke(vdcB);

            sVd = (VirtualDisplay) DisplayManager.class
                    .getMethod("createVirtualDisplay", Class.forName("android.hardware.display.VirtualDisplayConfig"))
                    .invoke(sDm, vdc);

            if (sVd == null || sVd.getDisplay() == null) {
                System.out.println("EXTBRIDGE_CREATE_VD_FAILED");
                cleanupDisplay(false);
                return;
            }
            Display vdDisp = sVd.getDisplay();
            sVdId = vdDisp.getDisplayId();
            sCreatedVdW = sVirtW;
            sCreatedVdH = sVirtH;
            try {
                sVdUniqueId = (String) Display.class.getMethod("getUniqueId").invoke(vdDisp);
            } catch (Throwable t) {
                sVdUniqueId = null;
            }

            applyDisplayFullscreenPolicy(sVdId, sFullscreenPolicy == 1);

            if (!bbqOk && sMMirrorDisplay != null) {
                sMirrorSc = mirrorDisplaySafe(sVdId);
                if (sMirrorSc != null) {
                    Object tx0 = newSurfaceTx();
                    try {
                        sTxReparent.invoke(tx0, sMirrorSc, sScaleSc);
                        sTxApply.invoke(tx0);
                    } finally {
                        closeSurfaceTx(tx0);
                    }
                }
            }

            sExtId = extId;
            applySurfaceTransform();
            reassert();
            sCursorX = sVirtW * 0.5f;
            sCursorY = sVirtH * 0.5f;
            syncInput();
            launchLauncher();
            sBgIoExec.execute(() -> sanitizeSystemHomeTasks(false));
            enqueuePushBinderToHelper(true);
            if (sSplitActive && sHandler != null) {
                sHandler.postDelayed(() -> relayoutSplitTasks(false), 220L);
            } else if (sHandler != null) {
                sHandler.postDelayed(ExtBridge::ensureLauncherTaskFullscreenInBg, 220L);
            }
            notifyHelperSync();

            System.out.println("EXTBRIDGE_ATTACHED_OK vdId=" + sVdId + " render=" + sRenderMode);
            System.out.flush();
        } catch (Throwable t) {
            t.printStackTrace();
            cleanupDisplay(false);
        }
    }

    private static Object mirrorDisplaySafe(int displayId) {
        if (sMMirrorDisplay == null || sWm == null) return null;
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            if (sMMirrorDisplay.getParameterCount() == 2) {
                Object outSc = scCls.getConstructor().newInstance();
                Object ok = sMMirrorDisplay.invoke(sWm, displayId, outSc);
                return (ok instanceof Boolean && (Boolean) ok) ? outSc : null;
            }
            return sMMirrorDisplay.invoke(sWm, displayId);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    private static void registerMirrorDisplayListener() {
        if (sDm == null || sHandler == null || sMirrorDisplayListener != null) return;
        sMirrorDisplayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {
                if (sMirrorMode != 1 || sShuttingDown || sHandler == null) return;
                Runnable syncGeom = () -> {
                    if (sShuttingDown || sMirrorMode != 1 || sMir0Sc == null) return;
                    int active = pickActiveBuiltinDisplayId();
                    if (active > 0 && active != sMirrorSrcId) {
                        // Fold/unfold swapped the active built-in panel: rebuild from the new source
                        System.out.println("EXTBRIDGE_MIRROR_SRC_REBUILD " + sMirrorSrcId + " -> " + active);
                        sForceRebuild = true;
                        return;
                    }
                    updateMirrorGeometry(true);
                };
                sHandler.post(syncGeom);
                sHandler.postDelayed(syncGeom, 150L);
            }
        };
        try {
            sDm.registerDisplayListener(sMirrorDisplayListener, sHandler);
        } catch (Throwable t) {
            sMirrorDisplayListener = null;
        }
    }

    private static void unregisterMirrorDisplayListener() {
        if (sDm != null && sMirrorDisplayListener != null) {
            try { sDm.unregisterDisplayListener(sMirrorDisplayListener); } catch (Throwable ignored) {}
            sMirrorDisplayListener = null;
        }
    }

    private static void attachMirrorDisplay(int extId) {
        sForceRebuild = false;
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> bCls = Class.forName("android.view.SurfaceControl$Builder");

            int picked = pickActiveBuiltinDisplayId();
            final int srcDisplayId = (picked > 0) ? picked : sMirrorSrcId;
            Object mir0 = mirrorDisplaySafe(srcDisplayId);
            if (mir0 == null) {
                // Ultimate safety fallback to pure system mirror if mirrorDisplay(0) is unavailable on this ROM
                sExtId = extId;
                sVdId = -1;
                sSideVdId = -1;
                sRenderMode = "NATIVE_MIRROR_STOCK";
                try {
                    if (sWm != null && sMReplaceContent != null) {
                        sMReplaceContent.invoke(sWm, extId, null);
                    }
                } catch (Throwable t) {
                    System.err.println("EXTBRIDGE_REPLACE_CONTENT_NULL_ERR: " + t);
                }
                enqueuePushBinderToHelper(true);
                notifyHelperSync();
                System.out.println("EXTBRIDGE_MIRROR_FALLBACK_STOCK extId=" + extId);
                return;
            }

            Object bRoot = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bRoot, "ExtRoot");
            bCls.getMethod("setContainerLayer").invoke(bRoot);
            bCls.getMethod("setHidden", boolean.class).invoke(bRoot, false);
            sRootSc = bCls.getMethod("build").invoke(bRoot);

            Object bBg = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bBg, "ExtBg");
            bCls.getMethod("setColorLayer").invoke(bBg);
            bCls.getMethod("setParent", scCls).invoke(bBg, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bBg, false);
            sBgSc = bCls.getMethod("build").invoke(bBg);

            Object bMirWrap = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bMirWrap, "ExtMir0Clip");
            bCls.getMethod("setContainerLayer").invoke(bMirWrap);
            bCls.getMethod("setParent", scCls).invoke(bMirWrap, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bMirWrap, false);
            sMir0WrapSc = bCls.getMethod("build").invoke(bMirWrap);

            Object bMirBg = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bMirBg, "ExtMir0Bg");
            bCls.getMethod("setColorLayer").invoke(bMirBg);
            bCls.getMethod("setParent", scCls).invoke(bMirBg, sMir0WrapSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bMirBg, false);
            sMir0BgSc = bCls.getMethod("build").invoke(bMirBg);

            sMir0Sc = mir0;
            sMirrorSrcId = srcDisplayId;
            System.out.println("EXTBRIDGE_MIRROR_SRC id=" + srcDisplayId);
            Object txInit = newSurfaceTx();
            try {
                sTxReparent.invoke(txInit, sMir0Sc, sMir0WrapSc);
                sTxApply.invoke(txInit);
            } finally {
                closeSurfaceTx(txInit);
            }

            boolean sideReady = false;
            if (sMirrorSideMode > 0) {
                sSideVdAttempted = true;
                sLastSideVdAttemptMode = sMirrorSideMode;
                try {
                    Object bSide = bCls.getConstructor().newInstance();
                    bCls.getMethod("setName", String.class).invoke(bSide, "ExtMirrorSide");
                    bCls.getMethod("setBLASTLayer").invoke(bSide);
                    bCls.getMethod("setParent", scCls).invoke(bSide, sRootSc);
                    bCls.getMethod("setHidden", boolean.class).invoke(bSide, false);
                    sSideMirSc = bCls.getMethod("build").invoke(bSide);

                    Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                    sSideBbq = bbqCls.getConstructor(String.class, boolean.class).newInstance("ExtSideBBQ", true);
                    bbqCls.getMethod("update", scCls, int.class, int.class, int.class)
                            .invoke(sSideBbq, sSideMirSc, sPhysW, sPhysH, 1);
                    sSideSurf = (Surface) bbqCls.getMethod("createSurface").invoke(sSideBbq);

                    if (sSideSurf != null) {
                        int sideDpi = Math.max(120, Math.min(360, Math.round(Math.min(sPhysW, sPhysH) * 200f / 1440f)));
                        Class<?> vdcBCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
                        Method createVdM = DisplayManager.class.getMethod(
                                "createVirtualDisplay", Class.forName("android.hardware.display.VirtualDisplayConfig"));
                        // Prefer 0x1040B (with STEAL_TOP_FOCUS_DISABLED) then 0x40B (PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | TRUSTED):
                        // TRUSTED (0x400) is required so AppWidget PendingIntents do not hit ATM SecurityException
                        // and so NavAssistService attaches ExtMirrorUnified at TYPE_ACCESSIBILITY_OVERLAY (layer 311000).
                        int[] candidateFlags = new int[]{0x1040B, 0x40B};
                        int chosenFlags = 0;
                        for (int vdFlags : candidateFlags) {
                            try {
                                Object vdcB = vdcBCls.getConstructor(String.class, int.class, int.class, int.class)
                                        .newInstance("RedmiKPad-MirrorSide", sPhysW, sPhysH, sideDpi);
                                vdcBCls.getMethod("setFlags", int.class).invoke(vdcB, vdFlags);
                                try { vdcBCls.getMethod("setHomeSupported", boolean.class).invoke(vdcB, false); } catch (Throwable ignored) {}
                                try { vdcBCls.getMethod("setRequestedRefreshRate", float.class).invoke(vdcB, clampFps(sPhysFps, 60f)); } catch (Throwable ignored) {}
                                vdcBCls.getMethod("setSurface", Surface.class).invoke(vdcB, sSideSurf);
                                Object vdc = vdcBCls.getMethod("build").invoke(vdcB);
                                sSideVd = (VirtualDisplay) createVdM.invoke(sDm, vdc);
                                if (sSideVd != null && sSideVd.getDisplay() != null) {
                                    chosenFlags = vdFlags;
                                    break;
                                }
                            } catch (Throwable ignored) {
                                sSideVd = null;
                            }
                        }
                        if (sSideVd != null && sSideVd.getDisplay() != null) {
                            sSideVdId = sSideVd.getDisplay().getDisplayId();
                            applyDisplayFullscreenPolicy(sSideVdId, false);
                            sideReady = true;
                            System.out.println("EXTBRIDGE_SIDE_VD_OK id=" + sSideVdId + " flags=0x" + Integer.toHexString(chosenFlags));
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                if (!sideReady) {
                    try { if (sSideVd != null) { sSideVd.release(); sSideVd = null; } } catch (Throwable ignored) {}
                    try { if (sSideBbq != null) { sSideBbq.getClass().getMethod("destroy").invoke(sSideBbq); sSideBbq = null; } } catch (Throwable ignored) {}
                    try { if (sSideSurf != null) { sSideSurf.release(); sSideSurf = null; } } catch (Throwable ignored) {}
                    if (sSideMirSc != null) {
                        try {
                            Object txC = newSurfaceTx();
                            try {
                                sTxRemove.invoke(txC, sSideMirSc);
                                sTxApply.invoke(txC);
                            } finally {
                                closeSurfaceTx(txC);
                            }
                            sScRelease.invoke(sSideMirSc);
                        } catch (Throwable ignored) {}
                        sSideMirSc = null;
                    }
                    sSideVdId = -1;
                }
            }

            sExtId = extId;
            sVdId = -1;
            sMirDragDisplayId = -1;
            sCursorX = sPhysW * 0.5f;
            sCursorY = sPhysH * 0.5f;
            sRenderMode = sideReady ? "MIRROR_SIDEBAR" : "MIRROR_COMP";
            sLastAppliedMirSideMode = -1;
            initMirrorCursorOverlay(scCls, bCls);
            registerMirrorDisplayListener();
            updateMirrorGeometry(false);
            reassert();
            grantAppWidgetBind();
            startMirrorCursorPoller();
            enqueuePushBinderToHelper(true);
            notifyHelperSync();
            System.out.println("EXTBRIDGE_MIRROR_ATTACHED extId=" + extId + " sideVdId=" + sSideVdId
                    + " d0=" + sD0W + "x" + sD0H + " mir=" + sMirW + "x" + sMirH + "@" + sMirX + "," + sMirY);
            System.out.flush();
        } catch (Throwable t) {
            t.printStackTrace();
            cleanupDisplay(false);
        }
    }

    private static volatile long sLastMirCursorErrMs = 0L;

    private static void initMirrorCursorOverlay(Class<?> scCls, Class<?> bCls) {
        try {
            Object bWrap = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bWrap, "ExtMirCursorWrap");
            bCls.getMethod("setContainerLayer").invoke(bWrap);
            bCls.getMethod("setParent", scCls).invoke(bWrap, sRootSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bWrap, true);
            sMirCursorWrapSc = bCls.getMethod("build").invoke(bWrap);

            Object bCur = bCls.getConstructor().newInstance();
            bCls.getMethod("setName", String.class).invoke(bCur, "ExtMirCursor");
            bCls.getMethod("setBLASTLayer").invoke(bCur);
            bCls.getMethod("setParent", scCls).invoke(bCur, sMirCursorWrapSc);
            bCls.getMethod("setHidden", boolean.class).invoke(bCur, false);
            sMirCursorSc = bCls.getMethod("build").invoke(bCur);

            Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
            sMirCursorBbq = bbqCls.getConstructor(String.class, boolean.class).newInstance("ExtMirCursorBBQ", true);
            bbqCls.getMethod("update", scCls, int.class, int.class, int.class)
                    .invoke(sMirCursorBbq, sMirCursorSc, 48, 48, 1);
            sMirCursorSurf = (Surface) bbqCls.getMethod("createSurface").invoke(sMirCursorBbq);
            sMirCursorVisible = false;
            sMirCursorLastDrawnPress = false;
            drawMirrorCursorBitmap(false);
            updateMirrorCursorSurface(sCursorX, sCursorY, false, false);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void drawMirrorCursorBitmap(boolean pressing) {
        Surface surf = sMirCursorSurf;
        if (surf == null || !surf.isValid()) return;
        Canvas c = null;
        try {
            c = surf.lockCanvas(null);
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            if (pressing) {
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setColor(0xAA38BDF8);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(2.6f);
                c.drawCircle(7f, 5f, 9f, ring);
            }
            Path arrow = new Path();
            arrow.moveTo(6f, 4f);
            arrow.lineTo(6f, 34f);
            arrow.lineTo(13.5f, 26.5f);
            arrow.lineTo(19f, 38f);
            arrow.lineTo(23.5f, 36f);
            arrow.lineTo(18f, 24.5f);
            arrow.lineTo(28f, 24.5f);
            arrow.close();

            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFF0F172A);
            fill.setStyle(Paint.Style.FILL);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.8f);
            stroke.setStrokeJoin(Paint.Join.ROUND);

            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
            sMirCursorLastDrawnPress = pressing;
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try { surf.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
        }
    }

    private static void updateMirrorCursorSurface(float cx, float cy, boolean pressing, boolean show) {
        Object wrapSc = sMirCursorWrapSc;
        Object curSc = sMirCursorSc;
        if (sMirrorMode != 1 || curSc == null || wrapSc == null) return;
        Object tx = null;
        try {
            if (show && pressing != sMirCursorLastDrawnPress) {
                drawMirrorCursorBitmap(pressing);
            }
            float curScale = Math.max(0.8f, Math.min(2.2f, Math.min(sPhysW, sPhysH) / 1440f));
            tx = newSurfaceTx();
            sTxSetLayer.invoke(tx, wrapSc, 100);
            sTxSetMatrix.invoke(tx, wrapSc, curScale, 0f, 0f, curScale);
            sTxSetPosition.invoke(tx, wrapSc, cx - 6f * curScale, cy - 4f * curScale);
            sTxSetAlpha.invoke(tx, wrapSc, show ? 1.0f : 0.0f);
            sTxSetLayer.invoke(tx, curSc, 0);
            sTxSetWindowCrop.invoke(tx, curSc, 48, 48);
            sTxSetPosition.invoke(tx, curSc, 0f, 0f);
            sTxSetAlpha.invoke(tx, curSc, show ? 1.0f : 0.0f);
            if (show) {
                sTxShow.invoke(tx, curSc);
                sTxShow.invoke(tx, wrapSc);
                sMirCursorVisible = true;
                sLastCursorMoveMs = SystemClock.uptimeMillis();
                scheduleMirCursorAutoHide();
            } else {
                try {
                    sTxHide.invoke(tx, wrapSc);
                } catch (Throwable ignored) {}
                sMirCursorVisible = false;
            }
            sTxApply.invoke(tx);
        } catch (Throwable t) {
            long now = SystemClock.uptimeMillis();
            if (now - sLastMirCursorErrMs > 1000L) {
                sLastMirCursorErrMs = now;
                System.out.println("EXTBRIDGE_MIR_CUR_ERR " + t);
                t.printStackTrace(System.out);
                System.out.flush();
            }
        } finally {
            closeSurfaceTx(tx);
        }
    }

    private static void scheduleMirCursorAutoHide() {
        if (sHandler == null || sShuttingDown) return;
        sHandler.removeCallbacks(sMirCursorAutoHideRunnable);
        sHandler.postDelayed(sMirCursorAutoHideRunnable, 8000L);
    }

    private static boolean hasExternalCursorDevice() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastExtMouseCheckMs < 4000L) return sCachedExtMousePresent;
        sLastExtMouseCheckMs = now;
        boolean found = false;
        try {
            if (sIm != null) {
                for (int id : sIm.getInputDeviceIds()) {
                    InputDevice dev = sIm.getInputDevice(id);
                    if (dev != null && dev.isExternal() && !dev.isVirtual()) {
                        int src = dev.getSources();
                        if ((src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                                || (src & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) {
                            found = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        sCachedExtMousePresent = found;
        return found;
    }

    private static float[] computeEdgeEntryPoint(float ptX, float ptY, float prevX, float prevY, boolean fromHoverSensor) {
        MirrorGeom g = sMirGeom;
        if (sMirrorMode != 1 || sMirrorSideMode <= 0 || sSideVdId <= 0 || g.d0W <= 0 || g.d0H <= 0 || g.mirW <= 0 || g.mirH <= 0) {
            return null;
        }
        float mappedX = Math.max(0f, Math.min(sPhysW - 1f, g.mirX + ptX * (g.mirW / (float) g.d0W)));
        float mappedY = Math.max(0f, Math.min(sPhysH - 1f, g.mirY + ptY * (g.mirH / (float) g.d0H)));
        // Horizontal wings
        if (g.mirX >= EDGE_MARGIN_MIN_PX) {
            boolean hit = fromHoverSensor ? (ptX <= 4.5f) : (prevX > 2.5f && ptX <= 2.5f);
            if (hit) return new float[]{Math.max(0f, g.mirX - 10f), mappedY};
        }
        int rightMargin = sPhysW - (g.mirX + g.mirW);
        if (rightMargin >= EDGE_MARGIN_MIN_PX) {
            boolean hit = fromHoverSensor ? (ptX >= g.d0W - 5.5f) : (prevX < g.d0W - 3.5f && ptX >= g.d0W - 3.5f);
            if (hit) return new float[]{Math.min(sPhysW - 1f, g.mirX + g.mirW + 10f), mappedY};
        }
        // Vertical wings (when horizontal margin doesn't exist)
        if (sPhysW - g.mirW < EDGE_MARGIN_MIN_PX) {
            if (g.mirY >= EDGE_MARGIN_MIN_PX) {
                boolean hit = fromHoverSensor ? (ptY <= 4.5f) : (prevY > 2.5f && ptY <= 2.5f);
                if (hit) return new float[]{mappedX, Math.max(0f, g.mirY - 10f)};
            }
            int bottomMargin = sPhysH - (g.mirY + g.mirH);
            if (bottomMargin >= EDGE_MARGIN_MIN_PX) {
                boolean hit = fromHoverSensor ? (ptY >= g.d0H - 5.5f) : (prevY < g.d0H - 3.5f && ptY >= g.d0H - 3.5f);
                if (hit) return new float[]{mappedX, Math.min(sPhysH - 1f, g.mirY + g.mirH + 10f)};
            }
        }
        return null;
    }

    private static void notifyMirrorEdgeCapture(boolean enter, float cx, float cy) {
        try {
            if (sCtx == null || sShuttingDown) return;
            Bundle b = new Bundle();
            b.putBoolean("enter", enter);
            b.putFloat("cx", cx);
            b.putFloat("cy", cy);
            callHelperProvider("mirEdgeCapture", b);
        } catch (Throwable ignored) {}
    }

    private static void startMirrorCursorPoller() {
        stopMirrorCursorPoller();
        if (sHandler == null || sMGetCursorPos == null || sIIm == null) return;
        sHasLastPollD0 = false;
        sMirCursorPollTask = new Runnable() {
            private long lastActiveMoveMs = SystemClock.uptimeMillis();
            @Override
            public void run() {
                if (sShuttingDown || sMirrorMode != 1 || sMirCursorSc == null || sMirCursorPollTask != this) return;
                int delayMs = 250;
                long now = SystemClock.uptimeMillis();
                if (now - sLastMirD0SizeCheckMs >= 240L) {
                    sLastMirD0SizeCheckMs = now;
                    Point d0Pt = readDisplayRealSize(sMirrorSrcId);
                    if (d0Pt.x > 0 && d0Pt.y > 0 && (d0Pt.x != sD0W || d0Pt.y != sD0H)) {
                        updateMirrorGeometry(true);
                    }
                }
                MirrorGeom g = sMirGeom;
                if (!sMirMouseCaptured && hasExternalCursorDevice() && g.mirW > 0 && g.mirH > 0 && g.d0W > 0 && g.d0H > 0) {
                    long idleMs = now - lastActiveMoveMs;
                    delayMs = (idleMs < 500L) ? 16 : (idleMs < 2000L ? 50 : 150);
                    try {
                        Object res = sMGetCursorPos.invoke(sIIm, sMirrorSrcId);
                        if (res instanceof PointF) {
                            PointF pt = (PointF) res;
                            if (Float.isFinite(pt.x) && Float.isFinite(pt.y)
                                    && pt.x >= -1f && pt.y >= -1f && pt.x <= g.d0W + 1f && pt.y <= g.d0H + 1f) {
                                boolean moved = !sHasLastPollD0
                                        || Math.abs(pt.x - sLastPollD0X) > 0.25f
                                        || Math.abs(pt.y - sLastPollD0Y) > 0.25f;
                                if (moved) {
                                    lastActiveMoveMs = now;
                                    delayMs = 16;
                                    boolean firstSample = !sHasLastPollD0;
                                    float prevX = sLastPollD0X;
                                    float prevY = sLastPollD0Y;
                                    sLastPollD0X = pt.x;
                                    sLastPollD0Y = pt.y;
                                    sHasLastPollD0 = true;
                                    if (!firstSample && now - sLastTouchpadMs > 350L) {
                                        boolean awayFromEdge = (pt.x > 10f && pt.x < g.d0W - 10f && pt.y > 10f && pt.y < g.d0H - 10f);
                                        if (sJustExitedSideEdge && awayFromEdge) {
                                            sJustExitedSideEdge = false;
                                        }
                                        float[] entry = (now >= sMirEdgeCooldownUntilMs && !sJustExitedSideEdge)
                                                ? computeEdgeEntryPoint(pt.x, pt.y, prevX, prevY, false)
                                                : null;
                                        if (entry != null) {
                                            synchronized (sCursorLock) {
                                                sMirMouseCaptured = true;
                                                sLastMirCapActionMs = now;
                                                sCursorX = entry[0];
                                                sCursorY = entry[1];
                                            }
                                            updateMirrorCursorSurface(sCursorX, sCursorY, false, true);
                                            notifyMirrorEdgeCapture(true, entry[0], entry[1]);
                                        } else {
                                            float mx = g.mirX + Math.max(0f, Math.min(g.d0W, pt.x)) * (g.mirW / (float) Math.max(1, g.d0W));
                                            float my = g.mirY + Math.max(0f, Math.min(g.d0H, pt.y)) * (g.mirH / (float) Math.max(1, g.d0H));
                                            synchronized (sCursorLock) {
                                                sCursorX = Math.max(0f, Math.min(sPhysW - 1f, mx));
                                                sCursorY = Math.max(0f, Math.min(sPhysH - 1f, my));
                                            }
                                            updateMirrorCursorSurface(sCursorX, sCursorY, false, true);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if (sHandler != null && !sShuttingDown && sMirrorMode == 1 && sMirCursorPollTask == this) {
                    sHandler.postDelayed(this, delayMs);
                }
            }
        };
        sHandler.postDelayed(sMirCursorPollTask, 80);
    }

    private static void stopMirrorCursorPoller() {
        Runnable r = sMirCursorPollTask;
        sMirCursorPollTask = null;
        if (r != null && sHandler != null) {
            try { sHandler.removeCallbacks(r); } catch (Throwable ignored) {}
        }
        if (sHandler != null) {
            try { sHandler.removeCallbacks(sMirCursorAutoHideRunnable); } catch (Throwable ignored) {}
        }
        boolean wasCaptured;
        synchronized (sCursorLock) {
            wasCaptured = sMirMouseCaptured;
            sMirMouseCaptured = false;
            sMouseButtons = 0;
            sMouseDownTime = 0L;
            sSideTouchDownTime = 0L;
            sMirDragDisplayId = -1;
        }
        if (wasCaptured) {
            notifyMirrorEdgeCapture(false, sCursorX, sCursorY);
        }
        sHasLastPollD0 = false;
        sJustExitedSideEdge = false;
        sMirCursorVisible = false;
    }

    private static boolean isDisplayActiveOn(Display d) {
        if (d == null) return false;
        try {
            int st = d.getState();
            return st == Display.STATE_ON || st == Display.STATE_ON_SUSPEND;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The built-in panel the user is actually looking at right now.
     *
     * <p>Foldables (e.g. Galaxy Z Flip/Fold) register the cover panel as an extra
     * {@link Display#TYPE_INTERNAL} display while the inner panel keeps display id 0. When folded,
     * display 0 is OFF and only the extra panel is ON - mirroring display 0 there would show black
     * and can also drag focus back to the folded-off inner panel.
     *
     * @return display id of the active built-in panel, or -1 when no built-in panel is awake
     *         (screen off). Callers keep their current source in that case.
     */
    private static int pickActiveBuiltinDisplayId() {
        try {
            if (sDm == null) return Display.DEFAULT_DISPLAY;
            Display d0 = sDm.getDisplay(Display.DEFAULT_DISPLAY);
            if (isDisplayActiveOn(d0)) return Display.DEFAULT_DISPLAY;
            Display[] all = sDm.getDisplays();
            if (all != null) {
                for (Display d : all) {
                    if (d == null) continue;
                    int id = d.getDisplayId();
                    // Built-in panels always get the lowest ids (0 = inner, 1 = foldable cover);
                    // HDMI/Cast/VirtualDisplays are allocated later. Do not rely on the hidden
                    // Display#getType() here (reflection is unavailable in some processes).
                    if (id < 1 || id > 5) continue;
                    if (getDisplayType(d) == 2) continue; // never mirror an external display as "phone"
                    if (!isDisplayActiveOn(d)) continue;
                    return id;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static Point readDisplayRealSize(int displayId) {
        Point p = new Point(0, 0);
        try {
            if (sIDm != null && sMGetDisplayInfo != null) {
                Object di = sMGetDisplayInfo.invoke(sIDm, displayId);
                if (di != null) {
                    int lw = readIntField(di, "logicalWidth", 0);
                    int lh = readIntField(di, "logicalHeight", 0);
                    if (lw > 0 && lh > 0) {
                        p.set(lw, lh);
                        return p;
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            if (sDm != null) {
                Display d = sDm.getDisplay(displayId);
                if (d != null) {
                    d.getRealSize(p);
                }
            }
        } catch (Throwable ignored) {}
        return p;
    }

    private static boolean updateMirrorGeometry(boolean notifyOnChange) {
        try {
            if (sRootSc == null || sBgSc == null || sMir0Sc == null || sMir0WrapSc == null || sDm == null) return false;
            Point d0Pt = readDisplayRealSize(sMirrorSrcId);
            int curD0W = (d0Pt.x > 0 && d0Pt.y > 0) ? d0Pt.x : 1920;
            int curD0H = (d0Pt.x > 0 && d0Pt.y > 0) ? d0Pt.y : 1200;

            float fit = Math.min(sPhysW / (float) curD0W, sPhysH / (float) curD0H);
            int mirW = Math.max(1, Math.round(curD0W * fit));
            int mirH = Math.max(1, Math.round(curD0H * fit));
            int mirX = Math.max(0, (sPhysW - mirW) / 2);
            int mirY = Math.max(0, (sPhysH - mirH) / 2);
            if (sPhysW - mirW >= EDGE_MARGIN_MIN_PX) {
                if (sMirrorSideMode == 1) {
                    mirX = 0;
                } else if (sMirrorSideMode == 2) {
                    mirX = Math.max(0, sPhysW - mirW);
                }
            } else if (sPhysH - mirH >= EDGE_MARGIN_MIN_PX) {
                if (sMirrorSideMode == 1) {
                    mirY = 0;
                } else if (sMirrorSideMode == 2) {
                    mirY = Math.max(0, sPhysH - mirH);
                }
            }

            boolean changed = (curD0W != sD0W || curD0H != sD0H
                    || mirW != sMirW || mirH != sMirH || mirX != sMirX || mirY != sMirY
                    || sMirrorSideMode != sLastAppliedMirSideMode);
            if (!changed && notifyOnChange) return false;

            sD0W = curD0W;
            sD0H = curD0H;
            sMirW = mirW;
            sMirH = mirH;
            sMirX = mirX;
            sMirY = mirY;
            sMirGeom = new MirrorGeom(curD0W, curD0H, mirX, mirY, mirW, mirH);
            sLastAppliedMirSideMode = sMirrorSideMode;

            int leftW = Math.max(0, mirX);
            int rightW = Math.max(0, sPhysW - (mirX + mirW));
            int topH = Math.max(0, mirY);
            int bottomH = Math.max(0, sPhysH - (mirY + mirH));
            boolean hasHoriz = (leftW >= EDGE_MARGIN_MIN_PX || rightW >= EDGE_MARGIN_MIN_PX);
            boolean hasVert = !hasHoriz && (topH >= EDGE_MARGIN_MIN_PX || bottomH >= EDGE_MARGIN_MIN_PX);
            boolean singleWing = hasHoriz
                    ? ((leftW < EDGE_MARGIN_MIN_PX && rightW >= EDGE_MARGIN_MIN_PX)
                            || (rightW < EDGE_MARGIN_MIN_PX && leftW >= EDGE_MARGIN_MIN_PX))
                    : ((topH < EDGE_MARGIN_MIN_PX && bottomH >= EDGE_MARGIN_MIN_PX)
                            || (bottomH < EDGE_MARGIN_MIN_PX && topH >= EDGE_MARGIN_MIN_PX));

            Object tx = newSurfaceTx();
            try {
                sTxSetLayer.invoke(tx, sBgSc, -1);
                float[] bgRgb = (sMirrorSideMode > 0) ? new float[]{0.025f, 0.035f, 0.065f} : new float[]{0f, 0f, 0f};
                sTxSetColor.invoke(tx, sBgSc, bgRgb);
                sTxSetAlpha.invoke(tx, sBgSc, 1.0f);
                sTxSetWindowCrop.invoke(tx, sBgSc, sPhysW, sPhysH);
                sTxShow.invoke(tx, sBgSc);

                // Hard physical-pixel clip container for Display 0 mirror so rotation/transition overflow can NEVER bleed into sidebars
                sTxSetLayer.invoke(tx, sMir0WrapSc, 1);
                sTxSetAlpha.invoke(tx, sMir0WrapSc, 1.0f);
                sTxSetPosition.invoke(tx, sMir0WrapSc, (float) mirX, (float) mirY);
                sTxSetWindowCrop.invoke(tx, sMir0WrapSc, mirW, mirH);
                sTxShow.invoke(tx, sMir0WrapSc);

                if (sMir0BgSc != null) {
                    sTxSetLayer.invoke(tx, sMir0BgSc, -1);
                    sTxSetColor.invoke(tx, sMir0BgSc, new float[]{0f, 0f, 0f});
                    sTxSetAlpha.invoke(tx, sMir0BgSc, 1.0f);
                    sTxSetWindowCrop.invoke(tx, sMir0BgSc, mirW, mirH);
                    sTxShow.invoke(tx, sMir0BgSc);
                }

                sTxSetLayer.invoke(tx, sMir0Sc, 0);
                sTxSetAlpha.invoke(tx, sMir0Sc, 1.0f);
                sTxSetScale.invoke(tx, sMir0Sc, fit, fit);
                sTxSetWindowCrop.invoke(tx, sMir0Sc, curD0W, curD0H);
                sTxSetPosition.invoke(tx, sMir0Sc, 0f, 0f);
                sTxShow.invoke(tx, sMir0Sc);

                if (sSideMirSc != null) {
                    if (sMirrorSideMode <= 0 || (!hasHoriz && !hasVert)) {
                        try { sTxHide.invoke(tx, sSideMirSc); } catch (Throwable ignored) {}
                    } else {
                        sTxSetAlpha.invoke(tx, sSideMirSc, 1.0f);
                        sTxSetPosition.invoke(tx, sSideMirSc, 0f, 0f);
                        if (singleWing && sTxSetCrop != null) {
                            Rect wingCrop;
                            if (hasHoriz) {
                                wingCrop = (leftW < EDGE_MARGIN_MIN_PX)
                                        ? new Rect(mirX + mirW, 0, sPhysW, sPhysH)
                                        : new Rect(0, 0, leftW, sPhysH);
                            } else {
                                wingCrop = (topH < EDGE_MARGIN_MIN_PX)
                                        ? new Rect(0, mirY + mirH, sPhysW, sPhysH)
                                        : new Rect(0, 0, sPhysW, topH);
                            }
                            sTxSetLayer.invoke(tx, sSideMirSc, 2);
                            sTxSetCrop.invoke(tx, sSideMirSc, wingCrop);
                        } else {
                            sTxSetLayer.invoke(tx, sSideMirSc, 0);
                            if (sTxSetCrop != null) {
                                sTxSetCrop.invoke(tx, sSideMirSc, new Rect(0, 0, sPhysW, sPhysH));
                            } else {
                                sTxSetWindowCrop.invoke(tx, sSideMirSc, sPhysW, sPhysH);
                            }
                        }
                        sTxShow.invoke(tx, sSideMirSc);
                    }
                }

                sTxSetWindowCrop.invoke(tx, sRootSc, sPhysW, sPhysH);
                sTxApply.invoke(tx);
            } finally {
                closeSurfaceTx(tx);
            }

            if (notifyOnChange && changed) {
                notifyHelperSync();
            }
            return changed;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private static void applySurfaceTransform() {
        Object tx = null;
        try {
            if (sMirrorSc == null || sBgSc == null || sRootSc == null) return;
            tx = newSurfaceTx();

            sTxSetLayer.invoke(tx, sBgSc, 0);
            sTxSetColor.invoke(tx, sBgSc, new float[]{0f, 0f, 0f});
            sTxSetAlpha.invoke(tx, sBgSc, 1.0f);
            sTxSetWindowCrop.invoke(tx, sBgSc, sPhysW, sPhysH);
            sTxShow.invoke(tx, sBgSc);

            int rot = normalizeRot(sVirtRot);
            int rotW = ((rot & 1) == 1) ? sVirtH : sVirtW;
            int rotH = ((rot & 1) == 1) ? sVirtW : sVirtH;
            float sx = sPhysW / (float) Math.max(1, rotW);
            float sy = sPhysH / (float) Math.max(1, rotH);
            float scale = Math.min(sx, sy);

            Matrix mat = new Matrix();
            mat.setRotate(rot * 90f);
            mat.postScale(scale, scale);
            RectF bounds = new RectF(0f, 0f, sVirtW, sVirtH);
            mat.mapRect(bounds);
            float posX = (sPhysW - bounds.width()) * 0.5f - bounds.left;
            float posY = (sPhysH - bounds.height()) * 0.5f - bounds.top;
            float[] mv = new float[9];
            mat.getValues(mv);

            sTxSetLayer.invoke(tx, sScaleSc, 1);
            sTxSetMatrix.invoke(tx, sScaleSc, mv[Matrix.MSCALE_X], mv[Matrix.MSKEW_Y], mv[Matrix.MSKEW_X], mv[Matrix.MSCALE_Y]);
            sTxSetPosition.invoke(tx, sScaleSc, posX, posY);
            sTxShow.invoke(tx, sScaleSc);

            sTxSetLayer.invoke(tx, sMirrorSc, 0);
            sTxSetAlpha.invoke(tx, sMirrorSc, 1.0f);
            sTxSetWindowCrop.invoke(tx, sMirrorSc, sVirtW, sVirtH);
            sTxSetPosition.invoke(tx, sMirrorSc, 0f, 0f);
            sTxShow.invoke(tx, sMirrorSc);

            sTxSetWindowCrop.invoke(tx, sRootSc, sPhysW, sPhysH);
            sTxApply.invoke(tx);
            System.out.println("EXTBRIDGE_TRANSFORM virt=" + sVirtW + "x" + sVirtH + " rot=" + (rot * 90) + " phys=" + sPhysW + "x" + sPhysH + " scale=" + scale + " pos=" + posX + "," + posY);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            closeSurfaceTx(tx);
        }
    }

    private static void reassert() {
        Object tx = null;
        try {
            if (sExtId <= 0 || sRootSc == null || sWm == null || sMReplaceContent == null) return;
            sMReplaceContent.invoke(sWm, sExtId, sRootSc);
            tx = newSurfaceTx();
            sTxSetLayer.invoke(tx, sRootSc, Integer.MAX_VALUE);
            sTxSetPosition.invoke(tx, sRootSc, 0f, 0f);
            sTxShow.invoke(tx, sRootSc);
            sTxApply.invoke(tx);
        } catch (Throwable ignored) {
        } finally {
            closeSurfaceTx(tx);
        }
    }

    /**
     * Finishes leftover ExtBridge ext-desktop launcher tasks living on a VirtualDisplay before it is
     * released. Otherwise the platform migrates them onto the phone's active display (on foldables
     * that is the cover panel), so the "DeX desktop" would hijack the phone screen.
     */
    private static void removeExtLauncherTasksOnDisplay(int displayId) {
        if (displayId <= 0 || sAtm == null) return;
        try {
            Object res = sAtm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class)
                    .invoke(sAtm, displayId);
            if (!(res instanceof List)) return;
            List<?> infos = (List<?>) res;
            if (infos.isEmpty()) return;
            Method removeM = null;
            for (Method m : sAtm.getClass().getMethods()) {
                if ("removeTask".equals(m.getName()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == int.class) {
                    removeM = m;
                    break;
                }
            }
            if (removeM == null) return;
            for (Object info : infos) {
                if (info == null) continue;
                try {
                    Object top = info.getClass().getField("topActivity").get(info);
                    String cn = (top instanceof ComponentName) ? ((ComponentName) top).flattenToShortString() : "";
                    if (cn == null || !cn.startsWith("com.extbridge.helper/.LauncherActivity")) continue;
                    int taskId = readIntField(info, "taskId", -1);
                    if (taskId > 0) removeM.invoke(sAtm, taskId);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void cleanupDisplay(boolean unbindWm) {
        synchronized (sDisplayLock) {
            if (unbindWm && sShuttingDown && sCleanupDoneOnShutdown) {
                return;
            }
            if (unbindWm && sShuttingDown) {
                sCleanupDoneOnShutdown = true;
            }
            stopMirrorCursorPoller();
            unregisterMirrorDisplayListener();
            boolean extUnplugged = (findExternalDisplay() == null);
            if (extUnplugged) {
                sSideVdAttempted = false;
            }

            // 1. Restore main screen brightness FIRST so persistent low brightness can never survive a kill!
            if (unbindWm || sShuttingDown || extUnplugged) {
                if (sScreenDimmed || new File(DIM_BACKUP_PATH).exists()) {
                    setMainScreenDim(0);
                }
            }

            // 2. Unbind external input device associations SECOND (before VirtualDisplay release!)
            unbindInput();

            // 3. Detach & remove all SurfaceControl layers + VirtualDisplays (<10ms)
            int prevExtId = sExtId;
            int prevVdId = sVdId;
            try {
                if (unbindWm && prevExtId > 0 && sWm != null && sMReplaceContent != null) {
                    try {
                        sMReplaceContent.invoke(sWm, prevExtId, null);
                    } catch (Throwable t) {
                        System.err.println("EXTBRIDGE_UNBIND_WM_ERR extId=" + prevExtId + ": " + t);
                    }
                }
            } catch (Throwable ignored) {}
            try {
                if (sVd != null) {
                    // Kill the ext-desktop launcher first: a released VD would otherwise migrate it
                    // onto the phone's active display (cover panel when folded).
                    try { removeExtLauncherTasksOnDisplay(sVdId); } catch (Throwable ignored) {}
                    sVd.release();
                    sVd = null;
                }
            } catch (Throwable ignored) {}
            sCreatedVdW = 0;
            sCreatedVdH = 0;
            try {
                if (sSideVd != null) { sSideVd.release(); sSideVd = null; }
            } catch (Throwable ignored) {}
            try {
                if (sBbq != null) {
                    sBbq.getClass().getMethod("destroy").invoke(sBbq);
                    sBbq = null;
                }
            } catch (Throwable ignored) {}
            try {
                if (sSideBbq != null) {
                    sSideBbq.getClass().getMethod("destroy").invoke(sSideBbq);
                    sSideBbq = null;
                }
            } catch (Throwable ignored) {}
            try {
                if (sMirCursorBbq != null) {
                    sMirCursorBbq.getClass().getMethod("destroy").invoke(sMirCursorBbq);
                    sMirCursorBbq = null;
                }
            } catch (Throwable ignored) {}
            try {
                if (sSurf != null) { sSurf.release(); sSurf = null; }
            } catch (Throwable ignored) {}
            try {
                if (sSideSurf != null) { sSideSurf.release(); sSideSurf = null; }
            } catch (Throwable ignored) {}
            try {
                if (sMirCursorSurf != null) { sMirCursorSurf.release(); sMirCursorSurf = null; }
            } catch (Throwable ignored) {}
            try {
                if (sSt != null) { sSt.release(); sSt = null; }
            } catch (Throwable ignored) {}
            Object tx = null;
            try {
                tx = newSurfaceTx();
                Object[] scs = new Object[]{sMirCursorSc, sMirCursorWrapSc, sMir0Sc, sMir0BgSc, sMir0WrapSc, sSideMirSc, sMirrorSc, sScaleSc, sBgSc, sRootSc};
                for (Object sc : scs) {
                    if (sc != null) {
                        try { sTxReparent.invoke(tx, sc, null); } catch (Throwable ignored) {}
                        try { sTxRemove.invoke(tx, sc); } catch (Throwable ignored) {}
                    }
                }
                sMirCursorSc = null;
                sMirCursorWrapSc = null;
                sMir0Sc = null;
                sMir0BgSc = null;
                sMir0WrapSc = null;
                sSideMirSc = null;
                sMirrorSc = null;
                sScaleSc = null;
                sBgSc = null;
                sRootSc = null;
                if (sTxApplySync != null) {
                    try { sTxApplySync.invoke(tx, true); } catch (Throwable ignored) { sTxApply.invoke(tx); }
                } else {
                    sTxApply.invoke(tx);
                }
                for (Object sc : scs) {
                    if (sc != null && sScRelease != null) {
                        try { sScRelease.invoke(sc); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                closeSurfaceTx(tx);
            }
            sExtId = -1;
            sVdId = -1;
            sSideVdId = -1;
            sMirW = 0;
            sMirH = 0;
            sMirGeom = new MirrorGeom(0, 0, 0, 0, 0, 0);
            sLastAppliedMirSideMode = -1;
            sVdUniqueId = null;
            sRenderMode = "NONE";

            // 4. Exit split & reset per-app compat overrides last
            if (unbindWm || sShuttingDown || sMirrorMode == 1 || extUnplugged) {
                if (sSplitActive && prevVdId > 0) {
                    try { exitSplitAndRestore(false); } catch (Throwable ignored) {}
                } else {
                    synchronized (sSplitLock) {
                        clearSplitState();
                    }
                }
            }
            if (unbindWm || sShuttingDown || extUnplugged) {
                restoreGlobalFullscreenPrefs();
                resetAppFullscreenCompatAll();
            }
        }
    }

    private static volatile int sLastPhysResult = 0; // 0=ok/unchanged, 1=switched, -1=edid unsupported, -2=invoke failed, -3=system locked physical mode

    private static boolean invokeSetUserPreferredMode(int extDisplayId, Display.Mode chosenMode) {
        if (chosenMode == null) return false;
        if (sMSetUserPreferredMode != null && sSetUserPreferredTarget != null) {
            try {
                if (sSetUserPreferredArgCount == 3) {
                    sMSetUserPreferredMode.invoke(sSetUserPreferredTarget, extDisplayId, chosenMode, false);
                } else {
                    sMSetUserPreferredMode.invoke(sSetUserPreferredTarget, extDisplayId, chosenMode);
                }
                return true;
            } catch (Throwable t) {
                System.err.println("EXTBRIDGE_PHYS_INVOKE_FAILED: " + t);
                t.printStackTrace();
            }
        }
        try {
            String w = String.valueOf(chosenMode.getPhysicalWidth());
            String h = String.valueOf(chosenMode.getPhysicalHeight());
            String fps = String.format(java.util.Locale.US, "%.2f", chosenMode.getRefreshRate());
            String did = String.valueOf(extDisplayId);
            String out = runCmdOut("cmd", "display", "set-user-preferred-display-mode", w, h, fps, did, "false");
            if (out != null && out.toLowerCase().contains("error")) {
                out = runCmdOut("cmd", "display", "set-user-preferred-display-mode", w, h, fps, did);
            }
            System.out.println("EXTBRIDGE_PHYS_CMD_OUT=" + (out == null ? "" : out.trim()));
            return out == null || !out.toLowerCase().contains("error");
        } catch (Throwable t) {
            System.err.println("EXTBRIDGE_PHYS_CMD_FAILED: " + t);
        }
        return false;
    }

    private static void syncPhysicalModeIfChanged() {
        synchronized (sDisplayLock) {
            Display ext = findExternalDisplay();
            if (ext == null || ext.getDisplayId() != sExtId) return;
            Display.Mode curMode = ext.getMode();
            if (curMode == null) return;
            int cw = curMode.getPhysicalWidth();
            int ch = curMode.getPhysicalHeight();
            float cf = curMode.getRefreshRate();
            boolean sizeChanged = (cw != sPhysW || ch != sPhysH);
            boolean fpsChanged = Math.abs(cf - sPhysFps) > 0.2f;
            if (sizeChanged || fpsChanged) {
                sPhysW = cw;
                sPhysH = ch;
                sPhysFps = cf;
                if (sMirrorMode == 1 && sizeChanged) {
                    sForceRebuild = true;
                    sSideVdAttempted = false;
                    tick();
                    return;
                }
                if (sizeChanged) {
                    applySurfaceTransform();
                }
                reassert();
                notifyHelperSync();
            }
        }
    }

    private static boolean applyConfig(int reqW, int reqH, int reqDpi, int reqFpsX100, int physModeId, int reqRot) {
        synchronized (sDisplayLock) {
            try {
                sLastPhysResult = 0;
                if (!isValidDims(reqW, reqH) || reqDpi < 80 || reqDpi > 640) {
                    return false;
                }
                float fps = reqFpsX100 > 0 ? clampFps(reqFpsX100 / 100.0f, sVirtFps) : sVirtFps;
                sVirtW = reqW;
                sVirtH = reqH;
                sVirtDpi = reqDpi;
                sVirtFps = fps;
                if (reqRot >= 0) {
                    sVirtRot = normalizeRot(reqRot);
                }
                sHasUserConf = true;
                saveConf();

                // 1. Apply physical external display mode if supported & requested
                Display ext = findExternalDisplay();
                if (ext != null) {
                    Display.Mode chosenMode = null;
                    Display.Mode[] modes = ext.getSupportedModes();
                    if (modes != null && modes.length > 0) {
                        if (physModeId > 0) {
                            for (Display.Mode m : modes) {
                                if (m.getModeId() == physModeId) { chosenMode = m; break; }
                            }
                        } else if (reqFpsX100 > 0) {
                            int matchW = ((sVirtRot & 1) == 1) ? reqH : reqW;
                            int matchH = ((sVirtRot & 1) == 1) ? reqW : reqH;
                            float bestScore = Float.MAX_VALUE;
                            for (Display.Mode m : modes) {
                                float diff = Math.abs(m.getRefreshRate() - fps);
                                if (diff > 2.5f) continue;
                                int mw = m.getPhysicalWidth();
                                int mh = m.getPhysicalHeight();
                                boolean resMatch = (mw == matchW && mh == matchH) || (mw == matchH && mh == matchW);
                                long area = (long) mw * (long) mh;
                                float areaBias = Math.max(0f, 4.0f - (area / 2073600f));
                                float score = diff + (resMatch ? 0f : (5.0f + areaBias));
                                if (score < bestScore) {
                                    bestScore = score;
                                    chosenMode = m;
                                }
                            }
                            if (chosenMode == null) {
                                sLastPhysResult = -1;
                                System.out.println("EXTBRIDGE_PHYS_SKIP no EDID mode near " + fps + "Hz for " + matchW + "x" + matchH);
                            }
                        }
                        if (chosenMode != null) {
                            boolean invoked = invokeSetUserPreferredMode(ext.getDisplayId(), chosenMode);
                            if (invoked) {
                                SystemClock.sleep(120);
                                Display extNow = findExternalDisplay();
                                Display.Mode cur = (extNow != null) ? extNow.getMode() : null;
                                if (cur != null) {
                                    sPhysW = cur.getPhysicalWidth();
                                    sPhysH = cur.getPhysicalHeight();
                                    sPhysFps = cur.getRefreshRate();
                                }
                                boolean curMatched = cur == null
                                        || (((cur.getPhysicalWidth() == chosenMode.getPhysicalWidth()
                                                && cur.getPhysicalHeight() == chosenMode.getPhysicalHeight())
                                            || (cur.getPhysicalWidth() == chosenMode.getPhysicalHeight()
                                                && cur.getPhysicalHeight() == chosenMode.getPhysicalWidth()))
                                            && Math.abs(cur.getRefreshRate() - chosenMode.getRefreshRate()) <= 2.5f);
                                sLastPhysResult = curMatched ? 1 : -3;
                                if (sHandler != null) {
                                    sHandler.postDelayed(ExtBridge::syncPhysicalModeIfChanged, 250);
                                    sHandler.postDelayed(ExtBridge::syncPhysicalModeIfChanged, 700);
                                }
                                System.out.println("EXTBRIDGE_PHYS_REQ mode=" + chosenMode.getModeId()
                                        + " target=" + chosenMode.getPhysicalWidth() + "x" + chosenMode.getPhysicalHeight() + "@" + chosenMode.getRefreshRate()
                                        + " actual=" + sPhysW + "x" + sPhysH + "@" + sPhysFps + " res=" + sLastPhysResult);
                            } else {
                                sLastPhysResult = -2;
                            }
                        }
                    }
                }

                // 2. Live resize VirtualDisplay + BBQ + hardware scale without tearing down running apps!
                liveResizeOrRebuild();
                return true;
            } catch (Throwable t) {
                t.printStackTrace();
                return false;
            }
        }
    }

    private static int setRotation(int reqRot) {
        synchronized (sDisplayLock) {
            int next = (reqRot < 0) ? ((sVirtRot + 1) % 4) : normalizeRot(reqRot);
            boolean wantPortrait = (next & 1) == 1;
            if (wantPortrait && sVirtW > sVirtH) {
                int tmp = sVirtW;
                sVirtW = sVirtH;
                sVirtH = tmp;
            } else if (!wantPortrait && sVirtH > sVirtW) {
                int tmp = sVirtW;
                sVirtW = sVirtH;
                sVirtH = tmp;
            }
            sVirtRot = next;
            sHasUserConf = true;
            saveConf();
            liveResizeOrRebuild();
            return sVirtRot;
        }
    }

    private static ArrayList<Integer> collectExtUserTaskIds(int vdId) {
        ArrayList<Integer> tids = new ArrayList<>();
        if (vdId <= 0) return tids;
        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running != null) {
                // Iterate bottom-to-top so moving them to the new VD preserves z-order
                for (int i = running.size() - 1; i >= 0; i--) {
                    Object t = running.get(i);
                    if (t == null) continue;
                    int did = readIntField(t, "displayId", -1);
                    if (did != vdId) continue;
                    String pkg = extractTaskPkg(t);
                    if ("com.extbridge.helper".equals(pkg) || isProtectedSystemTaskPkg(pkg)) continue;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    if (tid > 0 && !tids.contains(tid)) tids.add(tid);
                }
            }
        } catch (Throwable ignored) {}
        return tids;
    }

    private static void liveResizeOrRebuild() {
        boolean orientationAspectFlipped = (sVd != null && sCreatedVdW > 0 && sCreatedVdH > 0)
                && ((sCreatedVdW > sCreatedVdH) != (sVirtW > sVirtH));
        if (orientationAspectFlipped) {
            // Android VirtualDisplayDevice letterboxes if landscape<->portrait flips via resize() while
            // display rotation is frozen at 0; rebuild VD natively at the new aspect and migrate tasks!
            ArrayList<Integer> prevTasks = collectExtUserTaskIds(sVdId);
            sForceRebuild = true;
            tick();
            if (sVdId > 0 && !prevTasks.isEmpty() && sAtm != null) {
                for (int tid : prevTasks) {
                    try {
                        for (Method m : sAtm.getClass().getMethods()) {
                            if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                                m.invoke(sAtm, tid, sVdId);
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            notifyHelperSync();
            return;
        }
        if (sVd != null && "BBQ".equals(sRenderMode) && sMirrorSc != null) {
            Object newBbq = null;
            Surface newSurf = null;
            boolean swapped = false;
            try {
                Class<?> scCls = Class.forName("android.view.SurfaceControl");
                Class<?> bbqCls = Class.forName("android.graphics.BLASTBufferQueue");
                Object oldBbq = sBbq;
                Surface oldSurf = sSurf;

                newBbq = bbqCls.getConstructor(String.class, boolean.class).newInstance("ExtBBQ", true);
                bbqCls.getMethod("update", scCls, int.class, int.class, int.class).invoke(newBbq, sMirrorSc, sVirtW, sVirtH, 1);
                newSurf = (Surface) bbqCls.getMethod("createSurface").invoke(newBbq);
                if (newSurf != null && Build.VERSION.SDK_INT >= 30) {
                    try { newSurf.setFrameRate(sVirtFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE); } catch (Throwable ignored) {}
                }
                sVd.resize(sVirtW, sVirtH, sVirtDpi);
                sVd.setSurface(newSurf);

                sBbq = newBbq;
                sSurf = newSurf;
                swapped = true;
                if (oldSurf != null) try { oldSurf.release(); } catch (Throwable ignored) {}
                if (oldBbq != null) try { bbqCls.getMethod("destroy").invoke(oldBbq); } catch (Throwable ignored) {}

                applySurfaceTransform();
                if (sHandler != null) sHandler.postDelayed(ExtBridge::applySurfaceTransform, 150);
                sCursorX = Math.min(sCursorX, sVirtW - 1f);
                sCursorY = Math.min(sCursorY, sVirtH - 1f);
                reassert();
                if (sSplitActive) {
                    if (sHandler != null) {
                        sHandler.postDelayed(() -> relayoutSplitTasks(false), 300L);
                        sHandler.postDelayed(() -> relayoutSplitTasks(false), 900L);
                        sHandler.postDelayed(() -> relayoutSplitTasks(false), 1850L);
                    } else {
                        relayoutSplitTasks(false);
                    }
                }
                notifyHelperSync();
                System.out.println("EXTBRIDGE_RESIZED " + sVirtW + "x" + sVirtH + "@" + sVirtDpi + "fps=" + sVirtFps + " rot=" + (sVirtRot * 90));
                return;
            } catch (Throwable t) {
                t.printStackTrace();
                if (!swapped) {
                    if (newSurf != null) try { newSurf.release(); } catch (Throwable ignored) {}
                    if (newBbq != null) try { newBbq.getClass().getMethod("destroy").invoke(newBbq); } catch (Throwable ignored) {}
                }
            }
        }
        sForceRebuild = true;
        notifyHelperSync();
    }

    private static void unbindInput() {
        synchronized (sInputLock) {
            try {
                if (sIm == null || sMRemAssoc == null) return;
                for (String desc : sBoundDescs) {
                    try { sMRemAssoc.invoke(sIm, desc); } catch (Throwable ignored) {}
                }
                sBoundDescs.clear();
                int[] ids = sIm.getInputDeviceIds();
                if (ids != null) {
                    for (int id : ids) {
                        InputDevice dev = sIm.getInputDevice(id);
                        if (dev == null || !dev.isExternal() || dev.isVirtual()) continue;
                        String desc = dev.getDescriptor();
                        if (desc != null) {
                            try { sMRemAssoc.invoke(sIm, desc); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void syncInput() {
        synchronized (sInputLock) {
            try {
                unbindInput();
                if (sExtId < 0 || sVdUniqueId == null || sIm == null) return;
                int[] ids = sIm.getInputDeviceIds();
                int extCnt = 0;
                ArrayList<String> descs = new ArrayList<>();
                for (int id : ids) {
                    InputDevice dev = sIm.getInputDevice(id);
                    if (dev == null || !dev.isExternal() || dev.isVirtual()) continue;
                    extCnt++;
                    String desc = dev.getDescriptor();
                    if (desc != null) descs.add(desc);
                    if (sInputToExt != 0 && sMAddAssoc != null && desc != null) {
                        try {
                            if (sMRemAssoc != null) {
                                try { sMRemAssoc.invoke(sIm, desc); } catch (Throwable ignored) {}
                            }
                            sMAddAssoc.invoke(sIm, desc, sVdUniqueId);
                            sBoundDescs.add(desc);
                        } catch (Throwable t) {
                            System.err.println("EXTBRIDGE_ASSOC_ERR desc=" + desc + " err=" + t);
                        }
                    }
                }
                java.util.Collections.sort(descs);
                sLastExtInputSig = String.join("|", descs);
                sLastExtInputCnt = extCnt;
                System.out.println("EXTBRIDGE_INPUT_MODE=" + sInputToExt + " count=" + extCnt);
                notifyHelperSync();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static void checkInput() {
        try {
            if (sInputToExt == 0 || sIm == null) return;
            int[] ids = sIm.getInputDeviceIds();
            int extCnt = 0;
            ArrayList<String> descs = new ArrayList<>();
            for (int id : ids) {
                InputDevice dev = sIm.getInputDevice(id);
                if (dev != null && dev.isExternal() && !dev.isVirtual()) {
                    extCnt++;
                    String desc = dev.getDescriptor();
                    if (desc != null) descs.add(desc);
                }
            }
            java.util.Collections.sort(descs);
            String sig = String.join("|", descs);
            if (extCnt != sLastExtInputCnt || !sig.equals(sLastExtInputSig)) {
                syncInput();
            }
        } catch (Throwable ignored) {}
    }

    private static boolean invokeInjectInput(InputEvent ev, int targetDisplayId) {
        return invokeInjectInputMode(ev, targetDisplayId, 0);
    }

    private static boolean invokeInjectInputMode(InputEvent ev, int targetDisplayId, int mode) {
        if (ev == null || targetDisplayId < 0 || sIIm == null || sMInjectInput == null) return false;
        try {
            if (sMSetDisplayId != null) sMSetDisplayId.invoke(ev, targetDisplayId);
            Object res = sMInjectInput.invoke(sIIm, ev, mode);
            boolean ok = !(res instanceof Boolean) || (Boolean) res;
            if (ok) sLastInjectStatus = 1;
            return ok;
        } catch (Throwable t) {
            if (mode != 0) {
                return invokeInjectInputMode(ev, targetDisplayId, 0);
            }
            Throwable root = (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            if (root instanceof SecurityException) {
                sLastInjectStatus = -1;
                long now = SystemClock.uptimeMillis();
                if (now - sLastInjectDenyLogMs > 5000L) {
                    sLastInjectDenyLogMs = now;
                    System.err.println("EXTBRIDGE_INJECT_SECURITY_DENIED: " + root.getMessage());
                }
            } else {
                sLastInjectStatus = -2;
                t.printStackTrace();
            }
            return false;
        }
    }

    private static void focusDisplayForKey(int dispId) {
        if (dispId <= 0) return;
        try {
            if (sMFocusTopTask != null) {
                sMFocusTopTask.invoke(sAtm, dispId);
            }
        } catch (Throwable ignored) {}
        try {
            if (sMMoveDisplayToTop != null) {
                sMMoveDisplayToTop.invoke(sWm, dispId, "ExtBridge");
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isHelperTopOnDisplay0() {
        try {
            List<?> tasks = invokeTaskQuery("getTasks");
            if (tasks != null) {
                for (Object t : tasks) {
                    if (t == null) continue;
                    int did = readIntField(t, "displayId", 0);
                    if (did != 0) continue;
                    if (!readBoolField(t, "isVisible", true)) continue;
                    return "com.extbridge.helper".equals(extractTaskPkg(t));
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void injectKey(int keyCode, int metaState) {
        try {
            int dispId = (sMirrorMode == 1) ? 0 : sVdId;
            if (dispId < 0 || sIIm == null || sMInjectInput == null) return;
            if (dispId == 0 && keyCode == KeyEvent.KEYCODE_BACK && metaState == 0 && isHelperTopOnDisplay0()) {
                return;
            }
            if (dispId > 0) {
                focusDisplayForKey(dispId);
            }
            long tDown = SystemClock.uptimeMillis();
            KeyEvent kd = new KeyEvent(tDown, tDown, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            boolean okDown = invokeInjectInputMode(kd, dispId, 2);
            long tUp = Math.max(tDown, SystemClock.uptimeMillis());
            KeyEvent ku = new KeyEvent(tDown, tUp, KeyEvent.ACTION_UP, keyCode, 0, metaState,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            invokeInjectInputMode(ku, dispId, 2);
            if (!okDown && keyCode == KeyEvent.KEYCODE_BACK && metaState == 0) {
                runCmdOut("cmd", "input", "-d", String.valueOf(dispId), "keyevent", "4");
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void injectAscii(String text) {
        try {
            int dispId = (sMirrorMode == 1) ? 0 : sVdId;
            if (dispId < 0 || sIIm == null || sMInjectInput == null || text == null || text.isEmpty()) return;
            KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            KeyEvent[] evs = kcm.getEvents(text.toCharArray());
            if (evs == null) return;
            for (KeyEvent e : evs) {
                invokeInjectInput(e, dispId);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static boolean isPointInMirrorMain(float cx, float cy) {
        MirrorGeom g = sMirGeom;
        return g.mirW > 0 && g.mirH > 0
                && cx >= g.mirX && cx < (g.mirX + g.mirW)
                && cy >= g.mirY && cy < (g.mirY + g.mirH);
    }

    private static int resolveMirrorTargetDisplay(float cx, float cy) {
        if (isPointInMirrorMain(cx, cy)) return 0;
        return (sSideVdId > 0) ? sSideVdId : 0;
    }

    private static float mapMirrorX(int targetDisp, float cx) {
        MirrorGeom g = sMirGeom;
        if (targetDisp == 0 && g.mirW > 0 && g.d0W > 0) {
            return Math.max(0f, Math.min(g.d0W - 1f, (cx - g.mirX) * g.d0W / (float) g.mirW));
        }
        return Math.max(0f, Math.min(sPhysW - 1f, cx));
    }

    private static float mapMirrorY(int targetDisp, float cy) {
        MirrorGeom g = sMirGeom;
        if (targetDisp == 0 && g.mirH > 0 && g.d0H > 0) {
            return Math.max(0f, Math.min(g.d0H - 1f, (cy - g.mirY) * g.d0H / (float) g.mirH));
        }
        return Math.max(0f, Math.min(sPhysH - 1f, cy));
    }

    private static void releaseStaleMouseDragOnHelperDeath() {
        synchronized (sCursorLock) {
            try {
                long now = SystemClock.uptimeMillis();
                if (sMouseButtons != 0) {
                    boolean mirror = (sMirrorMode == 1);
                    int disp = mirror
                            ? (sMirDragDisplayId >= 0 ? sMirDragDisplayId : resolveMirrorTargetDisplay(sCursorX, sCursorY))
                            : sVdId;
                    int prevBtns = sMouseButtons;
                    sMouseButtons = 0;
                    sMirDragDisplayId = -1;
                    sMouseDownTime = 0L;
                    if (disp >= 0 && sIIm != null && sMInjectInput != null) {
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0 && (prevBtns & MotionEvent.BUTTON_PRIMARY) != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, tx, ty, 0, prevBtns, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, tx, ty, 0, 0, 0f, 0f, disp);
                        }
                    }
                }
                if (sSideTouchDownTime > 0L) {
                    long downTime = sSideTouchDownTime;
                    sSideTouchDownTime = 0L;
                    if (sMirrorMode == 1 && sSideVdId > 0) {
                        dispatchTouchMotionWithDownTime(MotionEvent.ACTION_CANCEL, now, downTime, sCursorX, sCursorY, sSideVdId);
                    }
                }
                if (sMirMouseCaptured) {
                    sMirMouseCaptured = false;
                    updateMirrorCursorSurface(sCursorX, sCursorY, false, false);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void injectMouseAction(int type, float a1, float a2) {
        if (!Float.isFinite(a1) || !Float.isFinite(a2)) return;
        boolean triggerBackAfterLock = false;
        synchronized (sCursorLock) {
            try {
                boolean mirror = (sMirrorMode == 1);
                if ((!mirror && sVdId <= 0) || (mirror && (sPhysW <= 0 || sPhysH <= 0))) return;
                if (sIIm == null || sMInjectInput == null) return;
                sLastTouchpadMs = SystemClock.uptimeMillis();
                float maxX = mirror ? (sPhysW - 1f) : (sVirtW - 1f);
                float maxY = mirror ? (sPhysH - 1f) : (sVirtH - 1f);
                if (!Float.isFinite(sCursorX)) sCursorX = maxX * 0.5f;
                if (!Float.isFinite(sCursorY)) sCursorY = maxY * 0.5f;
                long now = SystemClock.uptimeMillis();
                switch (type) {
                    case 0: { // MOVE_REL
                        sCursorX = Math.max(0f, Math.min(maxX, sCursorX + a1));
                        sCursorY = Math.max(0f, Math.min(maxY, sCursorY + a2));
                        int disp = mirror
                                ? (sMirDragDisplayId >= 0 ? sMirDragDisplayId : resolveMirrorTargetDisplay(sCursorX, sCursorY))
                                : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0 && sMouseButtons != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_MOVE, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(sMouseButtons != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE,
                                    now, tx, ty, sMouseButtons, 0, 0f, 0f, disp);
                        }
                        break;
                    }
                    case 1: { // LEFT_CLICK
                        int disp = mirror ? resolveMirrorTargetDisplay(sCursorX, sCursorY) : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0) {
                            sMouseDownTime = now - 20;
                            dispatchTouchMotion(MotionEvent.ACTION_DOWN, now - 20, tx, ty, disp);
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, tx, ty, disp);
                            scheduleMirrorSideClickPostChecks();
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now - 25, tx, ty, 0, 0, 0f, 0f, disp);
                            sMouseDownTime = now - 20;
                            dispatchMouseMotion(MotionEvent.ACTION_DOWN, now - 20, tx, ty, MotionEvent.BUTTON_PRIMARY, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now - 20, tx, ty, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, tx, ty, 0, MotionEvent.BUTTON_PRIMARY, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, tx, ty, 0, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now, tx, ty, 0, 0, 0f, 0f, disp);
                        }
                        break;
                    }
                    case 2: { // RIGHT_CLICK / BACK (a1 > 0.5f => explicit context secondary click)
                        int disp = mirror ? resolveMirrorTargetDisplay(sCursorX, sCursorY) : sVdId;
                        boolean forceContextButton = (a1 > 0.5f)
                                || (mirror && disp == sSideVdId && sSideVdId > 0)
                                || (mirror && disp == 0 && isHelperTopOnDisplay0());
                        if (forceContextButton) {
                            float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                            float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now - 25, tx, ty, 0, 0, 0f, 0f, disp);
                            sMouseDownTime = now - 20;
                            dispatchMouseMotion(MotionEvent.ACTION_DOWN, now - 20, tx, ty, MotionEvent.BUTTON_SECONDARY, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now - 20, tx, ty, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, tx, ty, 0, MotionEvent.BUTTON_SECONDARY, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, tx, ty, 0, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now, tx, ty, 0, 0, 0f, 0f, disp);
                        } else {
                            triggerBackAfterLock = true;
                        }
                        break;
                    }
                    case 3: { // LEFT_DOWN (drag)
                        sMouseDownTime = now;
                        sMouseButtons = MotionEvent.BUTTON_PRIMARY;
                        int disp = mirror ? resolveMirrorTargetDisplay(sCursorX, sCursorY) : sVdId;
                        sMirDragDisplayId = mirror ? disp : -1;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_DOWN, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_DOWN, now, tx, ty, MotionEvent.BUTTON_PRIMARY, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now, tx, ty, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, 0f, 0f, disp);
                        }
                        break;
                    }
                    case 4: { // LEFT_UP (drag end)
                        int disp = mirror ? (sMirDragDisplayId >= 0 ? sMirDragDisplayId : resolveMirrorTargetDisplay(sCursorX, sCursorY)) : sVdId;
                        sMirDragDisplayId = -1;
                        sMouseButtons = 0;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, tx, ty, disp);
                            scheduleMirrorSideClickPostChecks();
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, tx, ty, 0, MotionEvent.BUTTON_PRIMARY, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, tx, ty, 0, 0, 0f, 0f, disp);
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now + 5, tx, ty, 0, 0, 0f, 0f, disp);
                        }
                        break;
                    }
                    case 5: { // SCROLL (hscroll=a1, vscroll=a2)
                        int disp = mirror ? resolveMirrorTargetDisplay(sCursorX, sCursorY) : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        dispatchMouseMotion(MotionEvent.ACTION_SCROLL, now, tx, ty, sMouseButtons, 0, a1, a2, disp);
                        break;
                    }
                    case 7: { // MOVE_ABS
                        sCursorX = Math.max(0f, Math.min(maxX, a1));
                        sCursorY = Math.max(0f, Math.min(maxY, a2));
                        int disp = mirror
                                ? (sMirDragDisplayId >= 0 ? sMirDragDisplayId : resolveMirrorTargetDisplay(sCursorX, sCursorY))
                                : sVdId;
                        float tx = mirror ? mapMirrorX(disp, sCursorX) : sCursorX;
                        float ty = mirror ? mapMirrorY(disp, sCursorY) : sCursorY;
                        if (mirror && disp == sSideVdId && sSideVdId > 0 && sMouseButtons != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_MOVE, now, tx, ty, disp);
                        } else {
                            dispatchMouseMotion(sMouseButtons != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE,
                                    now, tx, ty, sMouseButtons, 0, 0f, 0f, disp);
                        }
                        break;
                    }
                }
                if (mirror) {
                    updateMirrorCursorSurface(sCursorX, sCursorY, sMouseButtons != 0 || type == 3, true);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        if (triggerBackAfterLock) {
            injectKey(KeyEvent.KEYCODE_BACK, 0);
        }
    }

    private static void reassertMirrorSourceFocus() {
        try {
            if (sMirrorMode == 1 && sMirMouseCaptured && sWm != null && sMMoveDisplayToTop != null
                    && pickActiveBuiltinDisplayId() > 0) {
                sMMoveDisplayToTop.invoke(sWm, sMirrorSrcId, "extMirSideCap");
            }
        } catch (Throwable ignored) {}
    }

    private static void scheduleMirrorSideClickPostChecks() {
        if (sHandler == null || sShuttingDown) return;
        sHandler.postDelayed(ExtBridge::reassertMirrorSourceFocus, 22L);
        sHandler.postDelayed(ExtBridge::reassertMirrorSourceFocus, 85L);
        final int checkSideVdId = sSideVdId;
        if (checkSideVdId > 0) {
            sBgSchedExec.schedule(() -> rescueStrayTasksOnSideVd(checkSideVdId), 35L, TimeUnit.MILLISECONDS);
            sBgSchedExec.schedule(() -> rescueStrayTasksOnSideVd(checkSideVdId), 110L, TimeUnit.MILLISECONDS);
            sBgSchedExec.schedule(() -> rescueStrayTasksOnSideVd(checkSideVdId), 280L, TimeUnit.MILLISECONDS);
        }
    }

    private static void rescueStrayTasksOnSideVd(int sideVdId) {
        if (sideVdId <= 0 || sMirrorMode != 1 || sAtm == null) return;
        try {
            List<?> infos = (List<?>) sAtm.getClass()
                    .getMethod("getAllRootTaskInfosOnDisplay", int.class)
                    .invoke(sAtm, sideVdId);
            if (infos == null || infos.isEmpty()) return;
            Method moveM = null;
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                    moveM = m;
                    break;
                }
            }
            if (moveM == null) return;
            for (Object ti : infos) {
                if (ti == null) continue;
                int tid = readIntField(ti, "taskId", readIntField(ti, "id", -1));
                if (tid > 0) {
                    try { moveM.invoke(sAtm, tid, 0); } catch (Throwable ignored) {}
                }
            }
            reassertMirrorSourceFocus();
        } catch (Throwable ignored) {}
    }

    private static void handleSideDirectTouch(int action, float sx, float sy) {
        if (!Float.isFinite(sx) || !Float.isFinite(sy)) return;
        synchronized (sCursorLock) {
            try {
                if (sMirrorMode != 1 || sSideVdId <= 0 || sIIm == null || sMInjectInput == null) return;
                long now = SystemClock.uptimeMillis();
                if (action == MotionEvent.ACTION_DOWN) {
                    sSideTouchDownTime = now;
                }
                float cx = Math.max(0f, Math.min(Math.max(1, sPhysW) - 1f, sx));
                float cy = Math.max(0f, Math.min(Math.max(1, sPhysH) - 1f, sy));
                dispatchTouchMotionWithDownTime(action, now, sSideTouchDownTime > 0 ? sSideTouchDownTime : now, cx, cy, sSideVdId);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    sSideTouchDownTime = 0L;
                    if (action == MotionEvent.ACTION_UP) {
                        scheduleMirrorSideClickPostChecks();
                    }
                }
                if (sMirCursorVisible) {
                    updateMirrorCursorSurface(cx, cy, false, false);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static boolean handleCapturedMirrorMouse(int actionType, float a1, float a2, float a3) {
        if (!Float.isFinite(a1) || !Float.isFinite(a2) || !Float.isFinite(a3)) return false;
        synchronized (sCursorLock) {
            try {
                if (sMirrorMode != 1 || sSideVdId <= 0 || sPhysW <= 0 || sPhysH <= 0) {
                    sMirMouseCaptured = false;
                    return false;
                }
                if (actionType == 9) { // REASSERT_D0_FOCUS while keeping sidebar capture alive
                    if (!sMirMouseCaptured) return false;
                    reassertMirrorSourceFocus();
                    return true;
                }
                if (!sMirMouseCaptured && actionType != 5) {
                    return false;
                }
                long now = SystemClock.uptimeMillis();
                sLastMirCapActionMs = now;
                if (actionType == 5) {
                    // Explicit local interrupt (finger touch on Display 0 / ESC / mode exit):
                    // NEVER clamp sCursorX out of the sidebar to cause a phantom jump; release cleanly!
                    if (sMouseButtons != 0) {
                        if ((sMouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, sSideVdId);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, sCursorX, sCursorY, 0, sMouseButtons, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, 0, 0, 0f, 0f, sSideVdId);
                        }
                        sMouseButtons = 0;
                        sMouseDownTime = 0L;
                    }
                    sMirMouseCaptured = false;
                    sHasLastPollD0 = false;
                    sJustExitedSideEdge = false;
                    sMirEdgeCooldownUntilMs = now + 320L;
                    updateMirrorCursorSurface(sCursorX, sCursorY, false, false);
                    return false;
                }

                MirrorGeom g = sMirGeom;
                if (!Float.isFinite(sCursorX)) sCursorX = sPhysW * 0.5f;
                if (!Float.isFinite(sCursorY)) sCursorY = sPhysH * 0.5f;
                float nx = (actionType == 0) ? Math.max(0f, Math.min(sPhysW - 1f, sCursorX + a1)) : sCursorX;
                float ny = (actionType == 0) ? Math.max(0f, Math.min(sPhysH - 1f, sCursorY + a2)) : sCursorY;

                boolean hasHorizMargin = (sPhysW - g.mirW >= EDGE_MARGIN_MIN_PX);
                boolean hasVertMargin = !hasHorizMargin && (sPhysH - g.mirH >= EDGE_MARGIN_MIN_PX);
                boolean inHorizMain = !hasHorizMargin
                        || (nx >= g.mirX + (g.mirX >= EDGE_MARGIN_MIN_PX ? 4f : 0f)
                                && nx <= (g.mirX + g.mirW) - ((sPhysW - g.mirX - g.mirW >= EDGE_MARGIN_MIN_PX) ? 5f : 0f));
                boolean inVertMain = !hasVertMargin
                        || (ny >= g.mirY + (g.mirY >= EDGE_MARGIN_MIN_PX ? 4f : 0f)
                                && ny <= (g.mirY + g.mirH) - ((sPhysH - g.mirY - g.mirH >= EDGE_MARGIN_MIN_PX) ? 5f : 0f));
                boolean crossedBack = (actionType == 0) && (inHorizMain && inVertMain);
                if (crossedBack) {
                    if (sMouseButtons != 0) {
                        if ((sMouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, sSideVdId);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, sCursorX, sCursorY, 0, sMouseButtons, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, 0, 0, 0f, 0f, sSideVdId);
                        }
                        sMouseButtons = 0;
                        sMouseDownTime = 0L;
                    }
                    sCursorX = Math.max(g.mirX, Math.min(g.mirX + g.mirW - 1f, nx));
                    sCursorY = Math.max(g.mirY, Math.min(g.mirY + g.mirH - 1f, ny));
                    sMirMouseCaptured = false;
                    sHasLastPollD0 = false;
                    sJustExitedSideEdge = true;
                    sMirEdgeCooldownUntilMs = now + 700L;
                    updateMirrorCursorSurface(sCursorX, sCursorY, false, true);
                    return false;
                }

                sMirMouseCaptured = true;
                sCursorX = nx;
                sCursorY = ny;
                switch (actionType) {
                    case 0: { // MOVE_REL
                        if ((sMouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                            dispatchTouchMotion(MotionEvent.ACTION_MOVE, now, sCursorX, sCursorY, sSideVdId);
                        } else if (sMouseButtons != 0) {
                            dispatchMouseMotion(MotionEvent.ACTION_MOVE, now, sCursorX, sCursorY, sMouseButtons, 0, 0f, 0f, sSideVdId);
                        } else {
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now, sCursorX, sCursorY, 0, 0, 0f, 0f, sSideVdId);
                        }
                        break;
                    }
                    case 1: { // LEFT_DOWN
                        if (sMouseButtons == 0) {
                            sMouseDownTime = now;
                            sMouseButtons = MotionEvent.BUTTON_PRIMARY;
                            dispatchTouchMotion(MotionEvent.ACTION_DOWN, now, sCursorX, sCursorY, sSideVdId);
                            reassertMirrorSourceFocus();
                        }
                        break;
                    }
                    case 2: { // LEFT_UP
                        if ((sMouseButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                            sMouseButtons &= ~MotionEvent.BUTTON_PRIMARY;
                            dispatchTouchMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, sSideVdId);
                            sMouseDownTime = 0L;
                            scheduleMirrorSideClickPostChecks();
                        }
                        break;
                    }
                    case 4: { // SCROLL
                        dispatchMouseMotion(MotionEvent.ACTION_SCROLL, now, sCursorX, sCursorY, sMouseButtons, 0, a1, a3, sSideVdId);
                        break;
                    }
                    case 6: { // SECONDARY / MIDDLE DOWN (a1 = button bitmask)
                        int btn = Math.round(a1);
                        if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                            sMouseDownTime = now;
                            sMouseButtons |= btn;
                            dispatchMouseMotion(MotionEvent.ACTION_DOWN, now, sCursorX, sCursorY, sMouseButtons, 0, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_PRESS, now, sCursorX, sCursorY, sMouseButtons, btn, 0f, 0f, sSideVdId);
                        }
                        break;
                    }
                    case 7: { // SECONDARY / MIDDLE UP (a1 = button bitmask)
                        int btn = Math.round(a1);
                        if (btn == MotionEvent.BUTTON_SECONDARY || btn == MotionEvent.BUTTON_TERTIARY) {
                            sMouseButtons &= ~btn;
                            dispatchMouseMotion(MotionEvent.ACTION_BUTTON_RELEASE, now, sCursorX, sCursorY, sMouseButtons, btn, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_UP, now, sCursorX, sCursorY, sMouseButtons, 0, 0f, 0f, sSideVdId);
                            dispatchMouseMotion(MotionEvent.ACTION_HOVER_MOVE, now + 4, sCursorX, sCursorY, 0, 0, 0f, 0f, sSideVdId);
                            if (sMouseButtons == 0) sMouseDownTime = 0L;
                        }
                        break;
                    }
                }
                updateMirrorCursorSurface(sCursorX, sCursorY, sMouseButtons != 0 || actionType == 1, true);
                return true;
            } catch (Throwable t) {
                t.printStackTrace();
                sMirMouseCaptured = false;
                return false;
            }
        }
    }

    private static boolean triggerEdgeHoverEntry(float ptX, float ptY) {
        if (!Float.isFinite(ptX) || !Float.isFinite(ptY)) return false;
        synchronized (sCursorLock) {
            if (sMirMouseCaptured) return true;
            long now = SystemClock.uptimeMillis();
            if (now < sMirEdgeCooldownUntilMs || now - sLastTouchpadMs < 350L || sJustExitedSideEdge) return false;
            float[] entry = computeEdgeEntryPoint(ptX, ptY, ptX, ptY, true);
            if (entry == null) return false;
            sMirMouseCaptured = true;
            sLastMirCapActionMs = now;
            sCursorX = entry[0];
            sCursorY = entry[1];
            updateMirrorCursorSurface(sCursorX, sCursorY, false, true);
            return true;
        }
    }

    private static void dispatchTouchMotion(int action, long now, float x, float y, int targetDisplayId) {
        dispatchTouchMotionWithDownTime(action, now, sMouseDownTime > 0 ? sMouseDownTime : now, x, y, targetDisplayId);
    }

    private static void dispatchTouchMotionWithDownTime(int action, long now, long downTime, float x, float y, int targetDisplayId) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? 0.0f : 1.0f;
        coords[0].size = 1.0f;

        MotionEvent ev = MotionEvent.obtain(downTime, now, action,
                1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            invokeInjectInput(ev, targetDisplayId);
        } finally {
            ev.recycle();
        }
    }

    private static void dispatchMouseMotion(int action, long now, float x, float y, int buttonState, int actionButton, float hscroll, float vscroll, int targetDisplayId) throws Exception {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = (buttonState != 0) ? 1.0f : 0.0f;
        coords[0].size = 1.0f;
        if (action == MotionEvent.ACTION_SCROLL) {
            coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        }

        long downTime = (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_SCROLL) ? now : sMouseDownTime;
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, 1, props, coords,
                0, buttonState, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) {
            try { MotionEvent.class.getMethod("setActionButton", int.class).invoke(ev, actionButton); } catch (Throwable ignored) {}
        }
        try {
            invokeInjectInput(ev, targetDisplayId);
        } finally {
            ev.recycle();
        }
    }

    private static boolean isAllowedLaunchTarget(String pkg, String cls) {
        if (!isValidPackageName(pkg) || !isValidClassName(cls)) return false;
        if ("com.extbridge.helper".equals(pkg)) {
            return "com.extbridge.helper.LauncherActivity".equals(cls) && getVerifiedHelperUid() > 0;
        }
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)
                || "com.miui.home".equals(pkg) || "com.miui.securitycenter".equals(pkg)) {
            return false;
        }
        if (sCtx == null) return false;
        try {
            PackageManager pm = sCtx.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN, null)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(pkg, cls));
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            if (ris != null && !ris.isEmpty()) return true;
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, cls), 0);
            return ai != null && ai.enabled && ai.exported && (ai.permission == null || ai.permission.isEmpty());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Some OEM window managers (e.g. Samsung One UI on foldables) silently ignore
     * ActivityOptions#setLaunchDisplayId for shell-initiated launches and drop the activity on the
     * display the user is looking at (cover panel when folded). Re-home the task onto our
     * VirtualDisplay afterwards so the extended desktop never hijacks the phone screen.
     */
    private static void enforceLaunchedTaskOnVd(String pkg, String cls, int vdId) {
        if (vdId <= 0 || sAtm == null || pkg == null || cls == null) return;
        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running == null) return;
            for (Object t : running) {
                if (t == null) continue;
                if (!pkg.equals(extractTaskPkg(t))) continue;
                Object top = null;
                try { top = t.getClass().getField("topActivity").get(t); } catch (Throwable ignored) {}
                if (!(top instanceof ComponentName)) continue;
                ComponentName cn = (ComponentName) top;
                if (!cls.equals(cn.getClassName())) continue;
                int did = readIntField(t, "displayId", -1);
                int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                if (tid <= 0 || did == vdId || did < 0) continue;
                for (Method m : sAtm.getClass().getMethods()) {
                    if ("moveRootTaskToDisplay".equals(m.getName()) && m.getParameterCount() == 2) {
                        m.invoke(sAtm, tid, vdId);
                        System.out.println("EXTBRIDGE_TASK_REHOMED tid=" + tid + " " + pkg + "/" + cls
                                + " from=" + did + " to=" + vdId);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Re-homes the ext-desktop launcher onto the VD a few times right after a hotplug launch. */
    private static void scheduleRehomeExtLauncher(int vdId) {
        if (sHandler == null || vdId <= 0) return;
        for (long d : new long[]{80L, 160L, 320L, 640L, 1200L, 2000L}) {
            sHandler.postDelayed(() -> enforceLaunchedTaskOnVd(
                    "com.extbridge.helper", "com.extbridge.helper.LauncherActivity", vdId), d);
        }
    }

    /**
     * Safety net: in extended-desktop mode the ext launcher must never stay on a built-in panel.
     * OEMs occasionally drop it on display 0 after an HDMI hotplug, which makes One UI switch the
     * foldable to the (folded-off) inner panel.
     */
    private static void enforceExtLauncherOffPhoneDisplay() {
        if (sMirrorMode != 0 || sVdId <= 0) return;
        enforceLaunchedTaskOnVd("com.extbridge.helper", "com.extbridge.helper.LauncherActivity", sVdId);
    }

    private static boolean launchComponentOnVd(String pkg, String cls) {
        try {
            if (sVdId <= 0 || sAtm == null || sMStartActivity == null) return false;
            if (!isAllowedLaunchTarget(pkg, cls)) return false;
            boolean isExtLauncher = "com.extbridge.helper".equals(pkg) && "com.extbridge.helper.LauncherActivity".equals(cls);
            synchronized (sSplitLock) {
                if (sSplitActive) {
                    if (isExtLauncher) {
                        int emptySlot = getEmptySplitSlot();
                        if (emptySlot >= 0) {
                            syncLauncherForSplit(emptySlot, true);
                            return true;
                        }
                        ensureLauncherTaskFullscreenInBg();
                    } else if (!"com.extbridge.helper".equals(pkg)) {
                        int targetSlot = matchOrAutoFillSplitSlot(pkg);
                        if (targetSlot >= 0) {
                            return splitAssign(targetSlot, -1, pkg, cls);
                        }
                        exitSplitAndRestore(false);
                    }
                }
            }
            int preTid = isExtLauncher ? findExtLauncherTaskId() : findTaskIdForPkg(pkg);
            if (preTid > 0) {
                int preWm = getTaskWindowingMode(preTid);
                if (preWm != 1 && preWm != 0) {
                    detachFreeformAndEnsureFullscreen(preTid, true);
                }
            }
            int mode = getAppMode(pkg);
            applyAppModeCompat(pkg, mode, false);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(pkg, cls));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if ("com.extbridge.helper".equals(pkg)) {
                intent.putExtra("extId", sVdId);
            }
            ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode);
            Object rc = sMStartActivity.invoke(sAtm, null, "com.android.shell", null, intent, null, null, null, 0, 0, null, opts.toBundle());
            if (sHandler != null) {
                final int launchVd = sVdId;
                for (long d : new long[]{80L, 160L, 320L, 640L, 1200L, 2000L}) {
                    sHandler.postDelayed(() -> enforceLaunchedTaskOnVd(pkg, cls, launchVd), d);
                }
                sHandler.postDelayed(() -> enforceRunningExtTasksFullscreen(sFullscreenPolicy == 1), 180L);
            }
            return !(rc instanceof Integer) || (Integer) rc >= 0;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private static ActivityOptions makeExtLaunchOptions(int displayId) {
        return makeExtLaunchOptionsForMode(displayId, 0);
    }

    private static Rect calcFreeformRect() {
        int sw = Math.max(600, sVirtW);
        int sh = Math.max(600, sVirtH);
        boolean port = sh > sw;
        int w = Math.max(540, Math.min(sw - 120, Math.round(sw * (port ? 0.78f : 0.44f))));
        int h = Math.max(500, Math.min(sh - 120, Math.round(sh * (port ? 0.62f : 0.84f))));
        int left = (sw - w) / 2;
        int top = Math.max(24, (sh - h) / 2 - 20);
        return new Rect(left, top, left + w, top + h);
    }

    private static ActivityOptions makeExtLaunchOptionsForMode(int displayId, int mode) {
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(displayId);
        if (displayId > 0) {
            try {
                // Freeform small window mode (5) is disallowed in DeX / Extended mode:
                // always explicitly pass WINDOWING_MODE_FULLSCREEN (1) + null launchBounds so
                // Xiaomi Workstation Mode (MiuiFreeform) / force_desktop_mode cannot hijack tasks into Freeform (5).
                opts.setLaunchBounds(null);
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 1);
            } catch (Throwable ignored) {}
        }
        return opts;
    }

    private static boolean shouldCompatOverridePkg(String pkg) {
        if (!isValidPackageName(pkg)) return false;
        return !"android".equals(pkg)
                && !"com.extbridge.helper".equals(pkg)
                && !"com.android.settings".equals(pkg)
                && !"com.android.systemui".equals(pkg)
                && !"com.miui.home".equals(pkg)
                && !"com.miui.securitycenter".equals(pkg);
    }

    private static void setGlobalIntSafe(String key, int val) {
        try {
            if (sCtx != null && Settings.Global.putInt(sCtx.getContentResolver(), key, val)) return;
        } catch (Throwable ignored) {}
        runCmdOut("settings", "put", "global", key, String.valueOf(val));
    }

    private static void restoreGlobalFullscreenPrefs() {
        if (!sSavedSysPrefs) return;
        setGlobalIntSafe("force_resizable_activities", sOrigForceResizable);
        setGlobalIntSafe("enable_non_resizable_multi_window", sOrigNonResizableMw);
        sSavedSysPrefs = false;
    }

    private static void applyDisplayFullscreenPolicy(int vdId, boolean enable) {
        // Never mutate global Settings.Global force_resizable_activities/enable_non_resizable_multi_window
        // as those globally affect tablet/phone Display 0 apps (WeChat, Quark, Pinduoduo, etc.).
        restoreGlobalFullscreenPrefs();

        if (vdId <= 0 || sWm == null) return;
        // Keep system decors off on virtual display so Display 0 gesture navigation stays isolated.
        try {
            sWm.getClass().getMethod("setShouldShowSystemDecors", int.class, boolean.class)
                    .invoke(sWm, vdId, false);
        } catch (Throwable ignored) {}
        boolean ignoredApplied = false;
        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("setIgnoreOrientationRequest") && m.getParameterCount() == 2) {
                    m.invoke(sWm, vdId, enable);
                    ignoredApplied = true;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        if (!ignoredApplied) {
            runCmdOut("wm", "set-ignore-orientation-request", "-d", String.valueOf(vdId), enable ? "true" : "false");
        }

        boolean rotApplied = false;
        try {
            if (enable) {
                for (Method m : sWm.getClass().getMethods()) {
                    if (m.getName().equals("freezeDisplayRotation") && m.getParameterCount() == 3) {
                        m.invoke(sWm, vdId, 0, "ExtBridge");
                        rotApplied = true;
                        break;
                    } else if (m.getName().equals("freezeDisplayRotation") && m.getParameterCount() == 2) {
                        m.invoke(sWm, vdId, 0);
                        rotApplied = true;
                        break;
                    }
                }
            } else {
                for (Method m : sWm.getClass().getMethods()) {
                    if (m.getName().equals("thawDisplayRotation") && m.getParameterCount() == 2) {
                        m.invoke(sWm, vdId, "ExtBridge");
                        rotApplied = true;
                        break;
                    } else if (m.getName().equals("thawDisplayRotation") && m.getParameterCount() == 1) {
                        m.invoke(sWm, vdId);
                        rotApplied = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (!rotApplied) {
            if (enable) {
                runCmdOut("wm", "user-rotation", "-d", String.valueOf(vdId), "lock", "0");
            } else {
                runCmdOut("wm", "user-rotation", "-d", String.valueOf(vdId), "free");
            }
        }

        try {
            for (Method m : sWm.getClass().getMethods()) {
                if (m.getName().equals("setDisplayWindowingMode") && m.getParameterCount() == 2) {
                    // Always keep VirtualDisplay default container mode as FULLSCREEN (1), never UNDEFINED (0),
                    // otherwise force_desktop_mode_on_external_displays=1 defaults the display to FREEFORM (5).
                    m.invoke(sWm, vdId, 1);
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void applyAppModeCompat(String pkg, int mode, boolean forceApply) {
        if (!shouldCompatOverridePkg(pkg)) return;
        if (mode == 0 || mode == 3) {
            boolean wasOverridden;
            synchronized (sCompatOverriddenModes) {
                wasOverridden = sCompatOverriddenModes.remove(pkg) != null;
            }
            if (wasOverridden || forceApply) {
                resetCompatForPkg(pkg);
            }
            return;
        }
        synchronized (sCompatOverriddenModes) {
            Integer prev = sCompatOverriddenModes.get(pkg);
            if (!forceApply && prev != null && prev == mode) return;
            sCompatOverriddenModes.put(pkg, mode);
        }
        // Execute one `am compat` command per process (argv, no multi-command `sh -c` loop)
        // so a single slow command never kills the shell mid-loop.
        if (mode == 1) {
            String[] enableIds = {"254631730", "310816437", "265464455", "263259275", "208648326", "174042936"};
            String[] disableIds = {"265452344", "236283604"};
            for (String ch : enableIds) {
                runCmdOut("am", "compat", "enable", "--no-kill", ch, pkg);
            }
            for (String ch : disableIds) {
                runCmdOut("am", "compat", "disable", "--no-kill", ch, pkg);
            }
        } else {
            // mode == 2: portrait/phone ratio compat
            String[] disableIds = {"254631730", "310816437", "265464455"};
            String[] enableIds = {"265452344", "236283604"};
            for (String ch : disableIds) {
                runCmdOut("am", "compat", "disable", "--no-kill", ch, pkg);
            }
            for (String ch : enableIds) {
                runCmdOut("am", "compat", "enable", "--no-kill", ch, pkg);
            }
        }
    }

    private static void resetAppFullscreenCompatAll() {
        ArrayList<String> pkgs;
        synchronized (sCompatOverriddenModes) {
            if (sCompatOverriddenModes.isEmpty()) return;
            pkgs = new ArrayList<>(sCompatOverriddenModes.keySet());
            sCompatOverriddenModes.clear();
        }
        for (String pkg : pkgs) {
            resetCompatForPkg(pkg);
        }
    }

    private static void forceTaskWindowingMode(int taskId, int wmode) {
        if (taskId <= 0 || sAtm == null) return;
        if (applyWctForTask(taskId, null, wmode, null)) return;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("setTaskWindowingMode")) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                        m.invoke(sAtm, taskId, wmode);
                        return;
                    } else if (pt.length == 3 && pt[0] == int.class && pt[1] == int.class && pt[2] == boolean.class) {
                        m.invoke(sAtm, taskId, wmode, false);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int extractTaskWindowingMode(Object t) {
        if (t == null) return 0;
        int wm = readIntField(t, "windowingMode", 0);
        if (wm != 0) return wm;
        try {
            Object cfg = t.getClass().getField("configuration").get(t);
            if (cfg != null) {
                Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                if (wc != null) {
                    Object r = wc.getClass().getMethod("getWindowingMode").invoke(wc);
                    if (r instanceof Integer) return (Integer) r;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static boolean isLauncherTaskInfo(Object t) {
        if (t == null || !"com.extbridge.helper".equals(extractTaskPkg(t))) return false;
        try {
            Object top = null;
            try { top = t.getClass().getField("topActivity").get(t); } catch (Throwable ignored) {}
            if (top == null) {
                try { top = t.getClass().getField("baseActivity").get(t); } catch (Throwable ignored) {}
            }
            return (top instanceof ComponentName) && ((ComponentName) top).getClassName().contains("LauncherActivity");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void detachFreeformAndEnsureFullscreen(int taskId, boolean syncFullBounds) {
        if (taskId <= 0 || sAtm == null) return;
        if (syncFullBounds) {
            Rect fullBounds = new Rect(0, 0, Math.max(600, sVirtW), Math.max(600, sVirtH));
            // Must resize while in MULTI_WINDOW (6) so ATM.resizeTask resets SurfaceFlinger Task leash position & crop to (0,0,sVirtW,sVirtH) before entering FULLSCREEN (1)!
            if (getTaskWindowingMode(taskId) != 6) {
                if (!applyWctForTask(taskId, Boolean.FALSE, 6, null)) {
                    clearTaskAlwaysOnTop(taskId);
                    forceTaskWindowingMode(taskId, 6);
                }
            }
            resizeTaskBoundsOnly(taskId, fullBounds, true);
        }
        if (!applyWctForTask(taskId, Boolean.FALSE, 1, new Rect())) {
            clearTaskAlwaysOnTop(taskId);
            forceTaskWindowingMode(taskId, 1);
        }
    }

    private static void enforceRunningExtTasksFullscreen(boolean enforceCompat) {
        if (sVdId <= 0 || sAtm == null || sMirrorMode != 0) return;
        try {
            boolean splitOn;
            int emptySlot;
            String s0Pkg, s1Pkg;
            int s0Tid, s1Tid;
            synchronized (sSplitLock) {
                splitOn = sSplitActive;
                emptySlot = getEmptySplitSlot();
                s0Pkg = sSplitSlot0Pkg != null ? sSplitSlot0Pkg : "";
                s1Pkg = sSplitSlot1Pkg != null ? sSplitSlot1Pkg : "";
                s0Tid = sSplitSlot0TaskId;
                s1Tid = sSplitSlot1TaskId;
            }
            List<?> running = invokeTaskQuery("getTasks");
            if (running == null) return;
            if (splitOn && (SystemClock.uptimeMillis() - sLastSplitActionMs) > 2600L) {
                boolean s0Vis = s0Pkg.isEmpty();
                boolean s1Vis = s1Pkg.isEmpty();
                for (Object t : running) {
                    if (t == null) continue;
                    if (readIntField(t, "displayId", -1) != sVdId) continue;
                    if (!readBoolField(t, "isVisible", false)) continue;
                    String p = extractTaskPkg(t);
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    if (!s0Pkg.isEmpty() && (s0Pkg.equals(p) || (s0Tid > 0 && tid == s0Tid))) s0Vis = true;
                    if (!s1Pkg.isEmpty() && (s1Pkg.equals(p) || (s1Tid > 0 && tid == s1Tid))) s1Vis = true;
                }
                if (!s0Vis && !s1Vis) {
                    exitSplitAndRestore(false);
                    return;
                } else if (!s0Vis && !s0Pkg.isEmpty()) {
                    detachPkgFromSplit(s0Pkg, s0Tid);
                    return;
                } else if (!s1Vis && !s1Pkg.isEmpty()) {
                    detachPkgFromSplit(s1Pkg, s1Tid);
                    return;
                }
            }
            for (Object t : running) {
                if (t == null) continue;
                int did = readIntField(t, "displayId", -1);
                if (did != sVdId) continue;
                String pkg = extractTaskPkg(t);
                int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                boolean isLauncher = isLauncherTaskInfo(t);
                if (splitOn) {
                    if ((tid > 0 && (tid == s0Tid || tid == s1Tid))
                            || (!pkg.isEmpty() && (pkg.equals(s0Pkg) || pkg.equals(s1Pkg)))) {
                        continue;
                    }
                    if (isLauncher && emptySlot >= 0) {
                        continue;
                    }
                }
                int wmode = extractTaskWindowingMode(t);
                if (isLauncher) {
                    if (tid > 0 && wmode != 1 && wmode != 0) {
                        detachFreeformAndEnsureFullscreen(tid, true);
                    }
                    continue;
                }
                // Rescue any non-split DeX task left in Freeform (5) or Multi-Window (6) back to Fullscreen (1)
                if (tid > 0 && wmode != 1 && wmode != 0) {
                    detachFreeformAndEnsureFullscreen(tid, true);
                    wmode = 1;
                }
                if (!enforceCompat || !shouldCompatOverridePkg(pkg)) continue;
                int mode = getAppMode(pkg);
                applyAppModeCompat(pkg, mode, false);
            }
        } catch (Throwable ignored) {}
    }

    private static void launchLauncher() {
        launchComponentOnVd("com.extbridge.helper", "com.extbridge.helper.LauncherActivity");
    }

    private static boolean minimizeToExtHome(int taskId, String pkg) {
        if (sVdId <= 0 || sMirrorMode != 0) return false;
        if (sSplitActive) {
            exitSplitAndRestore(false);
        }
        int lTid = findExtLauncherTaskId();
        if (lTid > 0 && sAtm != null) {
            try {
                int prevWm = getTaskWindowingMode(lTid);
                detachFreeformAndEnsureFullscreen(lTid, prevWm != 1);
                ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, 1);
                Bundle b = opts.toBundle();
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                        Object rc = m.invoke(sAtm, lTid, b);
                        if (!(rc instanceof Integer) || (Integer) rc >= 0) {
                            if (sHandler != null) {
                                sHandler.postDelayed(() -> enforceRunningExtTasksFullscreen(sFullscreenPolicy == 1), 180L);
                            }
                            notifyHelperSync();
                            return true;
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        }
        boolean ok = launchComponentOnVd("com.extbridge.helper", "com.extbridge.helper.LauncherActivity");
        if (ok) notifyHelperSync();
        return ok;
    }

    private static boolean isTrustedHelperCert(String hex) {
        if (hex == null) return false;
        for (String trusted : TRUSTED_HELPER_CERT_SHA256) {
            if (trusted.equalsIgnoreCase(hex)) return true;
        }
        return false;
    }

    private static int getVerifiedHelperUid() {
        if (sCtx == null) return -1;
        try {
            PackageManager pm = sCtx.getPackageManager();
            int curUid = pm.getPackageUid("com.extbridge.helper", 0);
            if (curUid <= 0) {
                sVerifiedHelperUid = -1;
                return -1;
            }
            long now = SystemClock.uptimeMillis();
            if (curUid == sVerifiedHelperUid && (now - sLastHelperUidVerifyMs) < 5000L) {
                return curUid;
            }
            android.content.pm.PackageInfo pi = pm.getPackageInfo(
                    "com.extbridge.helper", PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = (pi != null) ? pi.signingInfo : null;
            if (si == null || si.hasMultipleSigners()) {
                sVerifiedHelperUid = -1;
                return -1;
            }
            Signature[] sigs = si.getApkContentsSigners();
            if (sigs == null || sigs.length != 1 || sigs[0] == null) {
                sVerifiedHelperUid = -1;
                return -1;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            if (!isTrustedHelperCert(hex.toString())) {
                System.err.println("EXTBRIDGE_SECURITY_REJECT untrusted com.extbridge.helper cert=" + hex);
                sVerifiedHelperUid = -1;
                return -1;
            }
            sVerifiedHelperUid = curUid;
            sLastHelperUidVerifyMs = now;
            return curUid;
        } catch (Throwable ignored) {
            sVerifiedHelperUid = -1;
            return -1;
        }
    }

    private static boolean isVerifiedHelperProviderOwner() {
        int helperUid = getVerifiedHelperUid();
        if (helperUid <= 0 || sCtx == null) return false;
        try {
            ProviderInfo pi = sCtx.getPackageManager().resolveContentProvider("com.extbridge.helper.provider", 0);
            return pi != null && pi.applicationInfo != null
                    && pi.applicationInfo.uid == helperUid
                    && "com.extbridge.helper".equals(pi.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Bundle callHelperProvider(String method, Bundle extras) {
        if (sCtx == null || method == null || !isVerifiedHelperProviderOwner()) return null;
        Bundle res = null;
        try {
            ContentProviderClient cpc = sCtx.getContentResolver()
                    .acquireUnstableContentProviderClient("com.extbridge.helper.provider");
            if (cpc != null) {
                try {
                    res = cpc.call(method, null, extras);
                } finally {
                    try { cpc.close(); } catch (Throwable ignored) {}
                }
            }
            if (res == null) {
                res = sCtx.getContentResolver().call(
                        Uri.parse("content://com.extbridge.helper.provider"), method, null, extras);
            }
        } catch (Throwable ignored) {}
        if (res == null || !res.getBoolean("ok", false)) {
            if (isVerifiedHelperProviderOwner()) {
                res = callProviderViaAm("com.extbridge.helper.provider", method, null, extras);
            }
        }
        return res;
    }

    private static void enqueuePushBinderToHelper(boolean forceImmediate) {
        long now = SystemClock.uptimeMillis();
        if (!forceImmediate && (now - sLastBinderPushAttemptMs) < 700L) return;
        sLastBinderPushAttemptMs = now;
        sBinderPushExec.execute(ExtBridge::pushBinderToHelper);
    }

    private static void sendBinderFallbackBroadcast(Bundle extras) {
        if (sCtx == null || extras == null || getVerifiedHelperUid() <= 0) return;
        try {
            Intent bi = new Intent("com.extbridge.helper.BINDER_PUSH");
            bi.setComponent(new ComponentName("com.extbridge.helper", "com.extbridge.helper.ControlReceiver"));
            bi.addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            bi.putExtras(extras);
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle opts = android.app.BroadcastOptions.makeBasic()
                        .setShareIdentityEnabled(true)
                        .toBundle();
                sCtx.sendBroadcast(bi, "android.permission.INJECT_EVENTS", opts);
            } else {
                sCtx.sendBroadcast(bi, "android.permission.INJECT_EVENTS");
            }
        } catch (Throwable ignored) {}
    }

    private static void pushBinderToHelper() {
        if (sCtx == null || sCtlBinder == null || sShuttingDown) return;
        if (getVerifiedHelperUid() <= 0) return;
        boolean providerDelivered = false;
        try {
            Bundle b = new Bundle();
            b.putBinder("cb", sCtlBinder);
            Bundle res = isVerifiedHelperProviderOwner() ? callHelperProvider("setBinder", b) : null;
            if (res != null && res.getBoolean("ok", false)) {
                providerDelivered = true;
                final IBinder tok = res.getBinder("proc");
                if (tok != null && tok != sHelperProcToken) {
                    sHelperProcToken = tok;
                    try {
                        tok.linkToDeath(() -> {
                            if (sHelperProcToken == tok) {
                                sHelperProcToken = null;
                                releaseStaleMouseDragOnHelperDeath();
                            }
                        }, 0);
                    } catch (Throwable ignored) {}
                }
            }
            if (!providerDelivered) {
                // Fallback channel when OEM ROM hooks block shell->app ContentProvider calls under Root/su
                sendBinderFallbackBroadcast(b);
            }
        } catch (Throwable t) {
            System.err.println("pushBinderToHelper failed: " + t);
            t.printStackTrace();
        }
        startHelperService();
    }

    private static Bundle callProviderViaAm(String authority, String method, String arg, Bundle extras) {
        IBinder tok = new Binder();
        Object am = null;
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            IBinder amB = (IBinder) smCls.getMethod("getService", String.class).invoke(null, "activity");
            am = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, amB);
            Object holder = am.getClass()
                    .getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                    .invoke(am, authority, 0, tok, "*extbridge*");
            if (holder == null) return null;
            Object prov = holder.getClass().getField("provider").get(holder);
            if (prov == null) return null;
            Method callAttr5 = null;
            Method callStr5 = null;
            Method callStr6 = null;
            StringBuilder sigs = new StringBuilder();
            for (Method m : prov.getClass().getMethods()) {
                if (!m.getName().equals("call")) continue;
                Class<?>[] pts = m.getParameterTypes();
                sigs.append(Arrays.toString(pts)).append("; ");
                if (pts.length == 5 && pts[0].getName().equals("android.content.AttributionSource")) callAttr5 = m;
                else if (pts.length == 5 && pts[0] == String.class) callStr5 = m;
                else if (pts.length == 6 && pts[0] == String.class) callStr6 = m;
            }
            if (callAttr5 != null) {
                Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
                Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
                asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
                Object attrSrc = asBuilderCls.getMethod("build").invoke(builder);
                return (Bundle) callAttr5.invoke(prov, attrSrc, authority, method, arg, extras);
            } else if (callStr5 != null) {
                return (Bundle) callStr5.invoke(prov, "com.android.shell", authority, method, arg, extras);
            } else if (callStr6 != null) {
                return (Bundle) callStr6.invoke(prov, "com.android.shell", null, authority, method, arg, extras);
            } else {
                System.err.println("callProviderViaAm no matching call overload: " + sigs);
            }
        } catch (Throwable t) {
            System.err.println("callProviderViaAm failed: " + t);
            t.printStackTrace();
        } finally {
            if (am != null) {
                try {
                    am.getClass().getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                            .invoke(am, authority, tok, 0);
                } catch (Throwable ignored) {
                    try {
                        am.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class)
                                .invoke(am, authority, tok);
                    } catch (Throwable ignored2) {}
                }
            }
        }
        return null;
    }

    private static Context fixShellContext(Context base) {
        try {
            Field fOp = base.getClass().getDeclaredField("mOpPackageName");
            fOp.setAccessible(true);
            fOp.set(base, "com.android.shell");
        } catch (Throwable ignored) {}
        try {
            Field fBase = base.getClass().getDeclaredField("mBasePackageName");
            fBase.setAccessible(true);
            fBase.set(base, "com.android.shell");
        } catch (Throwable ignored) {}
        try {
            Class<?> asBuilderCls = Class.forName("android.content.AttributionSource$Builder");
            Object builder = asBuilderCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
            asBuilderCls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
            Object attrSrc = asBuilderCls.getMethod("build").invoke(builder);
            Field fAttr = base.getClass().getDeclaredField("mAttributionSource");
            fAttr.setAccessible(true);
            fAttr.set(base, attrSrc);
        } catch (Throwable ignored) {}
        return base;
    }

    private static void startHelperService() {
        if (getVerifiedHelperUid() <= 0) return;
        try {
            Intent svc = new Intent();
            svc.setComponent(new ComponentName("com.extbridge.helper", "com.extbridge.helper.BridgeService"));
            if (Build.VERSION.SDK_INT >= 26) {
                sCtx.startForegroundService(svc);
            } else {
                sCtx.startService(svc);
            }
        } catch (Throwable ignored) {}
    }

    private static void notifyHelperSync() {
        try {
            if (sShuttingDown) return;
            if (sHelperProcToken == null || !sHelperProcToken.pingBinder()) return;
            sBgIoExec.execute(() -> {
                if (!sShuttingDown && sHelperProcToken != null && sHelperProcToken.pingBinder()) {
                    callHelperProvider("syncState", null);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static String getModesSummary() {
        try {
            Display ext = findExternalDisplay();
            if (ext == null) return "";
            Display.Mode[] modes = ext.getSupportedModes();
            if (modes == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Display.Mode m : modes) {
                if (sb.length() > 0) sb.append(";");
                sb.append(m.getModeId()).append(":")
                  .append(m.getPhysicalWidth()).append("x").append(m.getPhysicalHeight())
                  .append("@").append(String.format(java.util.Locale.US, "%.1f", m.getRefreshRate()));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isTrustedCaller(int callingUid) {
        if (callingUid < 0) return false;
        if (callingUid == android.os.Process.myUid()
                || callingUid == 0
                || callingUid == 1000
                || callingUid == 2000) {
            return true;
        }
        int verifiedUid = getVerifiedHelperUid();
        return verifiedUid > 0 && callingUid == verifiedUid;
    }

    private static class BridgeBinder extends Binder {
        BridgeBinder() {
            attachInterface(null, BINDER_DESC);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(BINDER_DESC);
                return true;
            }
            if (!isTrustedCaller(Binder.getCallingUid())) {
                throw new SecurityException("Untrusted ExtBridge caller uid=" + Binder.getCallingUid());
            }
            if (code >= 10 && code <= 99) {
                if (data == null) return false;
                data.enforceInterface(BINDER_DESC);
            }
            if (code == 10) {
                sInputToExt = (sInputToExt == 0) ? 1 : 0;
                syncInput();
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 11) {
                injectKey(KeyEvent.KEYCODE_BACK, 0);
                return true;
            } else if (code == 12) {
                boolean useHalfPicker;
                synchronized (sSplitLock) {
                    useHalfPicker = sSplitActive && getEmptySplitSlot() >= 0;
                }
                if (useHalfPicker) {
                    launchLauncher();
                } else {
                    minimizeToExtHome(-1, null);
                }
                return true;
            } else if (code == 13) {
                injectKey(KeyEvent.KEYCODE_HOME, 0);
                return true;
            } else if (code == 14) {
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 15) {
                if (reply != null) {
                    int cx, cy;
                    synchronized (sCursorLock) {
                        cx = Math.round(sCursorX);
                        cy = Math.round(sCursorY);
                    }
                    int splitAct, splitRatioPct;
                    String s0, s1;
                    synchronized (sSplitLock) {
                        splitAct = sSplitActive ? 1 : 0;
                        splitRatioPct = Math.round(sSplitRatio * 100f);
                        s0 = sSplitSlot0Pkg != null ? sSplitSlot0Pkg : "";
                        s1 = sSplitSlot1Pkg != null ? sSplitSlot1Pkg : "";
                    }
                    MirrorGeom g = sMirGeom;
                    reply.writeInt(sExtId);
                    reply.writeInt(sVdId);
                    reply.writeInt(sPhysW);
                    reply.writeInt(sPhysH);
                    reply.writeInt(Math.round(sPhysFps * 100f));
                    reply.writeInt(sMirrorMode == 1 ? sPhysW : sVirtW);
                    reply.writeInt(sMirrorMode == 1 ? sPhysH : sVirtH);
                    reply.writeInt(sVirtDpi);
                    reply.writeInt(Math.round(sVirtFps * 100f));
                    reply.writeInt(sInputToExt);
                    reply.writeInt(cx);
                    reply.writeInt(cy);
                    reply.writeInt(sLastExtInputCnt);
                    reply.writeString(getModesSummary());
                    reply.writeString(sRenderMode);
                    reply.writeInt(sMirrorMode);
                    reply.writeInt(sFullscreenPolicy);
                    reply.writeInt(sVirtRot);
                    reply.writeInt(sLastInjectStatus);
                    reply.writeInt(splitAct);
                    reply.writeInt(splitRatioPct);
                    reply.writeString(s0);
                    reply.writeString(s1);
                    reply.writeInt(sMirrorSideMode);
                    reply.writeInt(sSideVdId);
                    reply.writeInt(g.d0W);
                    reply.writeInt(g.d0H);
                    reply.writeInt(g.mirX);
                    reply.writeInt(g.mirY);
                    reply.writeInt(g.mirW);
                    reply.writeInt(g.mirH);
                }
                return true;
            } else if (code == 16) {
                int w = data.readInt();
                int h = data.readInt();
                int dpi = data.readInt();
                int fpsX100 = data.readInt();
                int physModeId = data.readInt();
                int reqRot = data.dataAvail() > 0 ? data.readInt() : -1;
                boolean ok = applyConfig(w, h, dpi, fpsX100, physModeId, reqRot);
                if (reply != null) {
                    reply.writeInt(ok ? 1 : 0);
                    reply.writeInt(sLastPhysResult);
                    reply.writeInt(sPhysW);
                    reply.writeInt(sPhysH);
                    reply.writeInt(Math.round(sPhysFps * 100f));
                }
                return true;
            } else if (code == 17) {
                int actionType = data.readInt();
                float a1 = data.readFloat();
                float a2 = data.readFloat();
                injectMouseAction(actionType, a1, a2);
                if (reply != null) {
                    reply.writeInt(Math.round(sCursorX));
                    reply.writeInt(Math.round(sCursorY));
                    reply.writeInt(sLastInjectStatus);
                }
                return true;
            } else if (code == 18) {
                int sub = data.readInt();
                int arg1 = data.readInt();
                int arg2 = data.readInt();
                String txt = data.readString();
                if (sub == 0) {
                    injectKey(arg1, arg2);
                } else if (sub == 1) {
                    injectAscii(txt);
                } else if (sub == 2) {
                    if (arg1 == 1) {
                        injectKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
                    } else {
                        injectKey(KeyEvent.KEYCODE_PASTE, 0);
                    }
                }
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 19) {
                int mode = data.readInt();
                sInputToExt = (mode != 0) ? 1 : 0;
                syncInput();
                if (reply != null) reply.writeInt(sInputToExt);
                return true;
            } else if (code == 20) {
                String pkg = data.readString();
                String cls = data.readString();
                boolean ok = launchComponentOnVd(pkg, cls);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 21) {
                sForceRebuild = true;
                sSideVdAttempted = false;
                tick();
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 22) {
                boolean force = data.readInt() == 1;
                byte[] wp = getWallpaperBytes(force);
                if (reply != null) reply.writeByteArray(wp);
                return true;
            } else if (code == 23) {
                writeTasksToParcel(reply);
                return true;
            } else if (code == 24) {
                int tid = data.readInt();
                String pkg = data.readString();
                boolean ok = switchToTaskOnVd(tid, pkg);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 25) {
                int tid = data.readInt();
                boolean ok = removeTaskById(tid);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 26) {
                writeSysStatusToParcel(reply);
                return true;
            } else if (code == 27) {
                int act = data.readInt();
                int arg = data.readInt();
                String str = data.readString();
                boolean ok = execSysAction(act, arg, str);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 28) {
                int req = data.readInt();
                int prev = sMirrorMode;
                sMirrorMode = (req < 0) ? (prev == 0 ? 1 : 0) : (req == 1 ? 1 : 0);
                if (prev != sMirrorMode) {
                    sSideVdAttempted = false;
                    synchronized (sSplitLock) {
                        if (sSplitActive) exitSplitAndRestore(false);
                    }
                }
                if (sMirrorMode != 1) {
                    saveConf();
                }
                tick();
                notifyHelperSync();
                if (reply != null) reply.writeInt(sMirrorMode);
                return true;
            } else if (code == 29) {
                int req = data.readInt();
                int next = (req < 0) ? (sFullscreenPolicy == 0 ? 1 : 0) : (req != 0 ? 1 : 0);
                if (next != sFullscreenPolicy) {
                    sFullscreenPolicy = next;
                    saveConf();
                    if (sFullscreenPolicy == 0) {
                        resetAppFullscreenCompatAll();
                    }
                    if (sVdId > 0) {
                        applyDisplayFullscreenPolicy(sVdId, sFullscreenPolicy == 1);
                    }
                    if (sMirrorMode != 1 && sVd != null) {
                        sForceRebuild = true;
                        tick();
                    }
                    notifyHelperSync();
                }
                if (reply != null) reply.writeInt(sFullscreenPolicy);
                return true;
            } else if (code == 30) {
                String pkg = data.readString();
                int mode = getAppMode(pkg);
                if (sMirrorMode != 1) {
                    applyAppModeCompat(pkg, mode, false);
                }
                if (reply != null) reply.writeInt(mode);
                return true;
            } else if (code == 31) {
                StringBuilder sb = new StringBuilder();
                synchronized (sAppModes) {
                    for (Map.Entry<String, Integer> e : sAppModes.entrySet()) {
                        if (e.getValue() == null || e.getValue() == 0) continue;
                        if (sb.length() > 0) sb.append(';');
                        sb.append(e.getKey()).append('=').append(e.getValue());
                    }
                }
                if (reply != null) reply.writeString(sb.toString());
                return true;
            } else if (code == 32) {
                String pkg = data.readString();
                int mode = Math.max(0, Math.min(2, data.readInt()));
                int restart = data.readInt();
                boolean applied = false;
                if (shouldCompatOverridePkg(pkg)) {
                    Intent launchIntent = null;
                    try {
                        if (sCtx != null) launchIntent = sCtx.getPackageManager().getLaunchIntentForPackage(pkg);
                    } catch (Throwable ignored) {}
                    synchronized (sAppModes) {
                        if (mode == 0) {
                            sAppModes.remove(pkg);
                            applied = true;
                        } else if (sAppModes.size() < 512 || sAppModes.containsKey(pkg)) {
                            sAppModes.put(pkg, mode);
                            applied = true;
                        }
                        if (applied) saveAppModesLocked();
                    }
                    if (applied) {
                        if (restart == 1 && launchIntent != null) {
                            runCmdOut("am", "force-stop", pkg);
                        }
                        applyAppModeCompat(pkg, mode, true);
                        if (restart == 1 && launchIntent != null && sVdId > 0 && sMirrorMode != 1) {
                            try {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode);
                                sMStartActivity.invoke(sAtm, null, "com.android.shell", null, launchIntent, null, null, null, 0, 0, null, opts.toBundle());
                            } catch (Throwable ignored) {}
                        }
                        notifyHelperSync();
                    }
                }
                if (reply != null) reply.writeInt(applied ? 1 : 0);
                return true;
            } else if (code == 33) {
                HashSet<String> allPkgs = new HashSet<>();
                synchronized (sAppModes) {
                    allPkgs.addAll(sAppModes.keySet());
                    sAppModes.clear();
                    saveAppModesLocked();
                }
                synchronized (sCompatOverriddenModes) {
                    allPkgs.addAll(sCompatOverriddenModes.keySet());
                    sCompatOverriddenModes.clear();
                }
                for (String p : allPkgs) {
                    resetCompatForPkg(p);
                }
                healStrayCompatOverrides(true);
                notifyHelperSync();
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 34) {
                int reqRot = data.readInt();
                int cur = setRotation(reqRot);
                if (reply != null) reply.writeInt(cur);
                return true;
            } else if (code == 35) {
                int action = data.readInt();
                int arg = data.readInt();
                String pkg = data.readString();
                String cls = data.readString();
                boolean ok = handleSplitAction(action, arg, pkg, cls);
                if (reply != null) {
                    MirrorGeom g = sMirGeom;
                    reply.writeInt(ok ? 1 : 0);
                    reply.writeInt(sSplitActive ? 1 : 0);
                    reply.writeInt(Math.round(sSplitRatio * 100f));
                    reply.writeString(sSplitSlot0Pkg != null ? sSplitSlot0Pkg : "");
                    reply.writeString(sSplitSlot1Pkg != null ? sSplitSlot1Pkg : "");
                    reply.writeInt(sMirrorSideMode);
                    reply.writeInt(sSideVdId);
                    reply.writeInt(g.d0W);
                    reply.writeInt(g.d0H);
                    reply.writeInt(g.mirX);
                    reply.writeInt(g.mirY);
                    reply.writeInt(g.mirW);
                    reply.writeInt(g.mirH);
                }
                return true;
            } else if (code == 36) {
                int tid = data.readInt();
                String pkg = data.readString();
                int enableFreeform = data.readInt();
                int res = setTaskLiveWindowMode(tid, pkg, enableFreeform);
                if (reply != null) reply.writeInt(res);
                return true;
            } else if (code == 37) {
                int tid = data.readInt();
                String pkg = data.readString();
                boolean ok = minimizeToExtHome(tid, pkg);
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 38) {
                int req = data.readInt();
                int prev = sMirrorSideMode;
                int next = (req < 0) ? ((prev + 1) % 4) : Math.max(0, Math.min(3, req));
                if (next != prev) {
                    sMirrorSideMode = next;
                    sSideVdAttempted = false;
                    saveConf();
                    if (sMirrorMode == 1) {
                        if ((prev == 0) != (next == 0)) {
                            sForceRebuild = true;
                        }
                        tick();
                    }
                    notifyHelperSync();
                }
                if (reply != null) reply.writeInt(sMirrorSideMode);
                return true;
            } else if (code == 39) {
                writeMirrorTelemetryToParcel(reply);
                return true;
            } else if (code == 40) {
                boolean ok = grantAppWidgetBind();
                if (reply != null) reply.writeInt(ok ? 1 : 0);
                return true;
            } else if (code == 41) {
                int action = data.readInt();
                float sx = data.readFloat();
                float sy = data.readFloat();
                handleSideDirectTouch(action, sx, sy);
                if (reply != null) reply.writeInt(1);
                return true;
            } else if (code == 42) {
                int actionType = data.readInt();
                float a1 = data.readFloat();
                float a2 = data.readFloat();
                float a3 = data.readFloat();
                boolean stay = handleCapturedMirrorMouse(actionType, a1, a2, a3);
                if (reply != null) {
                    reply.writeInt(stay ? 1 : 0);
                    reply.writeInt(Math.round(sCursorX));
                    reply.writeInt(Math.round(sCursorY));
                }
                return true;
            } else if (code == 43) {
                float px = data.readFloat();
                float py = data.readFloat();
                boolean entered = triggerEdgeHoverEntry(px, py);
                if (reply != null) {
                    reply.writeInt(entered ? 1 : 0);
                    reply.writeInt(Math.round(sCursorX));
                    reply.writeInt(Math.round(sCursorY));
                }
                return true;
            } else if (code == 44) {
                int reqTid = data.readInt();
                String pkg = data.readString();
                int modeFlag = data.readInt();
                if (reply != null) {
                    captureTaskSnapshotToParcel(reqTid, pkg, modeFlag, reply);
                }
                return true;
            } else if (code == 99) {
                sShuttingDown = true;
                cleanupDisplay(true);
                if (reply != null) reply.writeInt(1);
                new Thread(() -> {
                    SystemClock.sleep(80);
                    System.exit(0);
                }).start();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static volatile Object sTaskSnapshotMgr;
    private static volatile boolean sTaskSnapshotMgrChecked = false;

    private static Object getTaskSnapshotManager() {
        if (sTaskSnapshotMgr != null) return sTaskSnapshotMgr;
        if (sTaskSnapshotMgrChecked || sAtm == null) return null;
        sTaskSnapshotMgrChecked = true;
        try {
            Method m = sAtm.getClass().getMethod("getTaskSnapshotManager");
            sTaskSnapshotMgr = m.invoke(sAtm);
        } catch (Throwable ignored) {}
        return sTaskSnapshotMgr;
    }

    private static Object invokeSnapshotOnTarget(Object target, int tid, boolean live) {
        if (target == null || tid <= 0) return null;
        if (live) {
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (!m.getName().equals("takeTaskSnapshot")) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 4 && pt[0] == int.class && pt[1] == boolean.class
                            && pt[2] == boolean.class && pt[3] == boolean.class) {
                        Object s = m.invoke(target, tid, true, true, false);
                        if (s != null) return s;
                    } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class && pt[2] == boolean.class) {
                        Object s = m.invoke(target, tid, true, true);
                        if (s != null) return s;
                    } else if (pt.length == 2 && pt[0] == int.class && pt[1] == boolean.class) {
                        Object s = m.invoke(target, tid, true);
                        if (s != null) return s;
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals("getTaskSnapshot")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == int.class && pt[1] == long.class && pt[2] == int.class) {
                    // Android 17 ITaskSnapshotManager.getTaskSnapshot(taskId, latestCaptureTime=0, RESOLUTION_ANY=3)
                    Object s = m.invoke(target, tid, 0L, 3);
                    if (s != null) return s;
                } else if (pt.length == 2 && pt[0] == int.class && pt[1] == boolean.class) {
                    Object s = m.invoke(target, tid, true);
                    if (s != null) return s;
                } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class && pt[2] == boolean.class) {
                    Object s = m.invoke(target, tid, true, live);
                    if (s != null) return s;
                } else if (pt.length == 3 && pt[0] == int.class && pt[1] == boolean.class && pt[2] == int.class) {
                    Object s = m.invoke(target, tid, true, 3);
                    if (s != null) return s;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object invokeTaskSnapshotRaw(int tid, boolean live) {
        if (tid <= 0 || sAtm == null) return null;
        Object mgr = getTaskSnapshotManager();
        if (mgr != null) {
            Object snap = invokeSnapshotOnTarget(mgr, tid, live);
            if (snap != null) return snap;
        }
        return invokeSnapshotOnTarget(sAtm, tid, live);
    }

    private static boolean isProtectedSystemTaskPkg(String pkg) {
        if (!isValidPackageName(pkg) || shouldSkipTaskPkg(pkg)) return true;
        return "android".equals(pkg)
                || pkg.contains("permissioncontroller")
                || pkg.contains("packageinstaller")
                || pkg.contains("keychain")
                || pkg.contains("keyguard")
                || "com.android.phone".equals(pkg)
                || "com.android.server.telecom".equals(pkg);
    }

    private static boolean isTaskAllowedForExtControl(int taskId, String expectedPkg, boolean blockDisplay0Settings) {
        if (taskId <= 0) return false;
        try {
            for (String qName : new String[]{"getTasks", "getRecentTasks"}) {
                List<?> list = invokeTaskQuery(qName);
                if (list == null) continue;
                for (Object t : list) {
                    if (t == null) continue;
                    int tid = "getRecentTasks".equals(qName)
                            ? readIntField(t, "taskId", readIntField(t, "persistentId", readIntField(t, "id", -1)))
                            : readIntField(t, "taskId", readIntField(t, "id", -1));
                    if (tid != taskId) continue;
                    String tPkg = extractTaskPkg(t);
                    int did = readIntField(t, "displayId", -1);
                    if (isProtectedSystemTaskPkg(tPkg)) return false;
                    if (expectedPkg != null && !expectedPkg.isEmpty() && !expectedPkg.equals(tPkg)) return false;
                    if (sVdId > 0 && did == sVdId) return true;
                    if (blockDisplay0Settings && "com.android.settings".equals(tPkg)) return false;
                    return sCtx != null && sCtx.getPackageManager().getLaunchIntentForPackage(tPkg) != null;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void captureTaskSnapshotToParcel(int reqTid, String pkg, int modeFlag, Parcel reply) {
        String validPkg = isValidPackageName(pkg) ? pkg : null;
        int tid = reqTid > 0 ? reqTid : findTaskIdForPkg(validPkg);
        if (tid > 0 && !isTaskAllowedForExtControl(tid, validPkg, true)) {
            tid = -1;
        }
        Rect bounds = (tid > 0) ? getTaskBounds(tid) : null;
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            reply.writeInt(1);
            reply.writeInt(bounds.left);
            reply.writeInt(bounds.top);
            reply.writeInt(bounds.right);
            reply.writeInt(bounds.bottom);
        } else {
            reply.writeInt(0);
        }
        if (tid <= 0) {
            reply.writeInt(0);
            return;
        }
        boolean live = (modeFlag & 1) != 0;
        boolean forceJpeg = (modeFlag & 2) != 0;
        HardwareBuffer hb = null;
        Bitmap hwBmp = null;
        Bitmap swBmp = null;
        try {
            Object snap = invokeTaskSnapshotRaw(tid, live);
            if (snap == null) {
                reply.writeInt(0);
                return;
            }
            try {
                hb = (HardwareBuffer) snap.getClass().getMethod("getHardwareBuffer").invoke(snap);
            } catch (Throwable ignored) {}
            if (hb == null || hb.isClosed() || hb.getWidth() <= 0 || hb.getHeight() <= 0) {
                reply.writeInt(0);
                return;
            }
            ColorSpace cs = null;
            try {
                cs = (ColorSpace) snap.getClass().getMethod("getColorSpace").invoke(snap);
            } catch (Throwable ignored) {}

            if (!forceJpeg) {
                int csOrdinal = ColorSpace.Named.SRGB.ordinal();
                if (cs != null && cs.getId() >= 0 && cs.getId() < ColorSpace.Named.values().length) {
                    csOrdinal = cs.getId();
                }
                reply.writeInt(1);
                reply.writeInt(csOrdinal);
                hb.writeToParcel(reply, 0);
                return;
            }

            hwBmp = Bitmap.wrapHardwareBuffer(hb, cs != null ? cs : ColorSpace.get(ColorSpace.Named.SRGB));
            if (hwBmp == null) {
                reply.writeInt(0);
                return;
            }
            swBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
            if (swBmp == null) {
                reply.writeInt(0);
                return;
            }
            int w = swBmp.getWidth();
            int h = swBmp.getHeight();
            if (Math.max(w, h) > 600) {
                float sc = 600f / Math.max(w, h);
                Bitmap scaled = Bitmap.createScaledBitmap(swBmp,
                        Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), true);
                if (scaled != swBmp) {
                    swBmp.recycle();
                    swBmp = scaled;
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32768);
            swBmp.compress(Bitmap.CompressFormat.JPEG, 78, baos);
            byte[] jpeg = baos.toByteArray();
            reply.writeInt(2);
            reply.writeByteArray(jpeg);
        } catch (Throwable t) {
            reply.writeInt(0);
        } finally {
            if (swBmp != null && !swBmp.isRecycled()) {
                try { swBmp.recycle(); } catch (Throwable ignored) {}
            }
            if (hwBmp != null && !hwBmp.isRecycled()) {
                try { hwBmp.recycle(); } catch (Throwable ignored) {}
            }
            if (hb != null && !hb.isClosed()) {
                try { hb.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static byte[] getWallpaperBytes(boolean force) {
        Bitmap bmp = null;
        try {
            WallpaperManager wm = (WallpaperManager) sCtx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm == null) return null;
            int wpId = 0;
            try { wpId = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM); } catch (Throwable ignored) {}
            if (!force && sCachedWpBytes != null && wpId != 0 && wpId == sCachedWpId) {
                return sCachedWpBytes;
            }

            try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
                if (pfd != null) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, bounds);
                    int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                    int sample = 1;
                    while (maxDim / sample > 1600) sample *= 2;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
                }
            } catch (Throwable ignored) {}

            if (bmp == null) {
                try {
                    Drawable dr = wm.getDrawable();
                    if (dr != null) {
                        int dw = Math.max(1, dr.getIntrinsicWidth());
                        int dh = Math.max(1, dr.getIntrinsicHeight());
                        float sc = Math.min(1f, 1280f / Math.max(dw, dh));
                        int tw = Math.max(1, Math.round(dw * sc));
                        int th = Math.max(1, Math.round(dh * sc));
                        bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                        Canvas cv = new Canvas(bmp);
                        dr.setBounds(0, 0, tw, th);
                        dr.draw(cv);
                    }
                } catch (Throwable ignored) {}
            }

            if (bmp == null) return sCachedWpBytes;

            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (Math.max(bw, bh) > 1440) {
                float sc = 1440f / Math.max(bw, bh);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bw * sc)), Math.max(1, Math.round(bh * sc)), true);
                if (scaled != bmp) {
                    bmp.recycle();
                    bmp = scaled;
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            byte[] out = baos.toByteArray();
            if (out.length > 420_000) {
                baos.reset();
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                out = baos.toByteArray();
            }
            sCachedWpBytes = out;
            sCachedWpId = wpId;
            return out;
        } catch (Throwable t) {
            return sCachedWpBytes;
        } finally {
            if (bmp != null && !bmp.isRecycled()) {
                try { bmp.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static final class TaskSnap {
        int taskId;
        int rootTaskId;
        String pkg;
        int flags; // bit0=onExt, bit1=isRunning, bit2=isTopExt
    }

    private static int readIntField(Object obj, String name, int def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return def;
    }

    private static boolean readBoolField(Object obj, String name, boolean def) {
        if (obj == null) return def;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return def;
    }

    private static Object readObjField(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static String extractTaskPkg(Object info) {
        Object top = readObjField(info, "topActivity");
        if (top instanceof ComponentName) {
            String p = ((ComponentName) top).getPackageName();
            if (p != null && !p.isEmpty()) return p;
        }
        Object real = readObjField(info, "realActivity");
        if (real instanceof ComponentName) {
            String p = ((ComponentName) real).getPackageName();
            if (p != null && !p.isEmpty()) return p;
        }
        Object bi = readObjField(info, "baseIntent");
        if (bi instanceof Intent) {
            Intent intent = (Intent) bi;
            if (intent.getComponent() != null) return intent.getComponent().getPackageName();
            if (intent.getPackage() != null) return intent.getPackage();
        }
        Object ba = readObjField(info, "baseActivity");
        if (ba instanceof ComponentName) return ((ComponentName) ba).getPackageName();
        return null;
    }

    private static boolean shouldSkipTaskPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        return "com.extbridge.helper".equals(pkg)
                || "com.miui.home".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "com.miui.securitycenter".equals(pkg)
                || "com.android.launcher".equals(pkg)
                || "com.android.launcher3".equals(pkg);
    }

    private static List<?> invokeTaskQuery(String name) {
        if (sAtm == null) return null;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pt = m.getParameterTypes();
                Object res = null;
                if ("getTasks".equals(name)) {
                    if (pt.length == 1) res = m.invoke(sAtm, 25);
                    else if (pt.length == 2) res = m.invoke(sAtm, 25, false);
                    else if (pt.length == 3) res = m.invoke(sAtm, 25, false, true);
                    else if (pt.length == 4) res = m.invoke(sAtm, 25, false, true, -1);
                } else if ("getRecentTasks".equals(name) && pt.length == 3) {
                    res = m.invoke(sAtm, 25, 0, 0);
                }
                if (res instanceof List<?>) return (List<?>) res;
                if (res != null) {
                    try {
                        Object list = res.getClass().getMethod("getList").invoke(res);
                        if (list instanceof List<?>) return (List<?>) list;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void writeTasksToParcel(Parcel reply) {
        if (reply == null) return;
        LinkedHashMap<String, TaskSnap> map = new LinkedHashMap<>();
        try {
            List<?> running = invokeTaskQuery("getTasks");
            boolean firstOnExtMarked = false;
            if (running != null) {
                for (Object t : running) {
                    if (t == null) continue;
                    String pkg = extractTaskPkg(t);
                    if (shouldSkipTaskPkg(pkg)) continue;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int rtid = readIntField(t, "rootTaskId", tid);
                    int did = readIntField(t, "displayId", -1);
                    boolean onExt = (sVdId > 0 && did == sVdId);
                    boolean isVis = readBoolField(t, "isVisible", false);
                    int fl = 2; // isRunning
                    if (onExt) {
                        fl |= 1;
                        if (!firstOnExtMarked && isVis) {
                            fl |= 4;
                            firstOnExtMarked = true;
                        }
                    }
                    TaskSnap ts = map.get(pkg);
                    if (ts == null) {
                        ts = new TaskSnap();
                        ts.taskId = tid;
                        ts.rootTaskId = rtid;
                        ts.pkg = pkg;
                        ts.flags = fl;
                        map.put(pkg, ts);
                    } else {
                        ts.flags |= fl;
                        if (onExt && ts.taskId <= 0) ts.taskId = tid;
                    }
                }
            }

            List<?> recents = invokeTaskQuery("getRecentTasks");
            if (recents != null) {
                for (Object t : recents) {
                    if (t == null) continue;
                    String pkg = extractTaskPkg(t);
                    if (shouldSkipTaskPkg(pkg)) continue;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    int rtid = readIntField(t, "rootTaskId", tid);
                    int did = readIntField(t, "displayId", -1);
                    boolean isRun = readBoolField(t, "isRunning", false);
                    boolean onExt = (sVdId > 0 && did == sVdId);
                    int fl = (onExt ? 1 : 0) | (isRun ? 2 : 0);
                    TaskSnap ts = map.get(pkg);
                    if (ts == null) {
                        ts = new TaskSnap();
                        ts.taskId = tid;
                        ts.rootTaskId = rtid;
                        ts.pkg = pkg;
                        ts.flags = fl;
                        map.put(pkg, ts);
                    } else {
                        ts.flags |= fl;
                        if (ts.taskId <= 0 && tid > 0) ts.taskId = tid;
                    }
                }
            }
        } catch (Throwable ignored) {}

        ArrayList<TaskSnap> extList = new ArrayList<>();
        ArrayList<TaskSnap> otherList = new ArrayList<>();
        HashMap<Integer, Integer> liveWinModes = new HashMap<>();
        try {
            List<?> rootInfos = sVdId > 0
                    ? (List<?>) sAtm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(sAtm, sVdId)
                    : (List<?>) sAtm.getClass().getMethod("getAllRootTaskInfos").invoke(sAtm);
            if (rootInfos != null) {
                for (Object info : rootInfos) {
                    if (info == null) continue;
                    int rTid = info.getClass().getField("taskId").getInt(info);
                    int wm = 0;
                    try {
                        Object cfg = info.getClass().getField("configuration").get(info);
                        if (cfg != null) {
                            Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                            if (wc != null) wm = (int) wc.getClass().getMethod("getWindowingMode").invoke(wc);
                        }
                    } catch (Throwable ignored) {}
                    liveWinModes.put(rTid, wm);
                }
            }
        } catch (Throwable ignored) {}
        for (TaskSnap ts : map.values()) {
            Integer liveWm = liveWinModes.get(ts.taskId);
            boolean isFf = liveWm != null ? (liveWm == 5) : (getAppMode(ts.pkg) == 3);
            if (isFf) ts.flags |= 8;
            if (sSplitActive && ts.pkg != null
                    && (ts.pkg.equals(sSplitSlot0Pkg) || ts.pkg.equals(sSplitSlot1Pkg))) {
                ts.flags |= 16;
            }
            if ((ts.flags & 1) != 0) extList.add(ts);
            else otherList.add(ts);
        }
        ArrayList<TaskSnap> combined = new ArrayList<>(10);
        for (TaskSnap ts : extList) {
            if (combined.size() >= 10) break;
            combined.add(ts);
        }
        for (TaskSnap ts : otherList) {
            if (combined.size() >= 10) break;
            combined.add(ts);
        }

        reply.writeInt(combined.size());
        for (TaskSnap ts : combined) {
            reply.writeInt(ts.taskId);
            reply.writeString(ts.pkg);
            reply.writeInt(ts.flags);
        }
    }

    private static boolean switchToTaskOnVd(int taskId, String pkg) {
        if (sVdId <= 0) return false;
        String safePkg = isValidPackageName(pkg) ? pkg : null;
        if (safePkg != null && isProtectedSystemTaskPkg(safePkg) && !"com.android.settings".equals(safePkg)) {
            return false;
        }
        int safeTid = (taskId > 0 && isTaskAllowedForExtControl(taskId, safePkg, false)) ? taskId : -1;
        if (safeTid <= 0 && safePkg == null) return false;
        int mode = getAppMode(safePkg);
        try {
            synchronized (sSplitLock) {
                if (sSplitActive && safePkg != null && !"com.extbridge.helper".equals(safePkg)) {
                    int slot = matchOrAutoFillSplitSlot(safePkg);
                    if (slot >= 0) {
                        sLastSplitActionMs = SystemClock.uptimeMillis();
                        if (slot == 0) {
                            if (safeTid > 0) sSplitSlot0TaskId = safeTid;
                            sSplitSlot0Pkg = safePkg;
                        } else {
                            if (safeTid > 0) sSplitSlot1TaskId = safeTid;
                            sSplitSlot1Pkg = safePkg;
                        }
                        relayoutSplitTasks(true);
                        notifyHelperSync();
                        return true;
                    }
                    exitSplitAndRestore(false);
                }
            }
            applyAppModeCompat(safePkg, mode, false);
            if (safeTid > 0 && sAtm != null) {
                try {
                    for (Method m : sAtm.getClass().getMethods()) {
                        if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                            m.invoke(sAtm, safeTid, sVdId);
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
                int prevWm = getTaskWindowingMode(safeTid);
                detachFreeformAndEnsureFullscreen(safeTid, prevWm != 1 && prevWm != 0);

                ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode);
                Bundle b = opts.toBundle();
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                        Object rc = m.invoke(sAtm, safeTid, b);
                        if (!(rc instanceof Integer) || (Integer) rc >= 0) {
                            if (sHandler != null) {
                                sHandler.postDelayed(() -> enforceRunningExtTasksFullscreen(sFullscreenPolicy == 1), 180L);
                            }
                            return true;
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (safePkg != null) {
                Intent li = sCtx.getPackageManager().getLaunchIntentForPackage(safePkg);
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, mode);
                    sMStartActivity.invoke(sAtm, null, "com.android.shell", null, li, null, null, null, 0, 0, null, opts.toBundle());
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static float clampSplitRatio(float r) {
        return Math.max(0.25f, Math.min(0.75f, r));
    }

    private static Rect calcSplitRect(int slot, float ratio) {
        int sw = Math.max(600, sVirtW);
        int sh = Math.max(600, sVirtH);
        boolean portrait = sh > sw;
        float r = clampSplitRatio(ratio);
        int halfGap = SPLIT_GAP_PX / 2;
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

    private static int matchOrAutoFillSplitSlot(String pkg) {
        if (!sSplitActive || pkg == null || pkg.isEmpty()) return -1;
        if (pkg.equals(sSplitSlot0Pkg)) return 0;
        if (pkg.equals(sSplitSlot1Pkg)) return 1;
        if (sSplitSlot0Pkg == null || sSplitSlot0Pkg.isEmpty()) return 0;
        if (sSplitSlot1Pkg == null || sSplitSlot1Pkg.isEmpty()) return 1;
        return -1;
    }

    private static void clearSplitState() {
        sSplitActive = false;
        sSplitSlot0Pkg = "";
        sSplitSlot1Pkg = "";
        sSplitSlot0TaskId = -1;
        sSplitSlot1TaskId = -1;
    }

    private static int findTaskIdForPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return -1;
        List<?> running = invokeTaskQuery("getTasks");
        if (running != null) {
            for (Object t : running) {
                if (t == null) continue;
                int did = readIntField(t, "displayId", -1);
                if (did == sVdId && pkg.equals(extractTaskPkg(t))) {
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    if (tid > 0) return tid;
                }
            }
            for (Object t : running) {
                if (t == null) continue;
                if (pkg.equals(extractTaskPkg(t))) {
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    if (tid > 0) return tid;
                }
            }
        }
        List<?> recent = invokeTaskQuery("getRecentTasks");
        if (recent != null) {
            for (Object t : recent) {
                if (t == null) continue;
                if (pkg.equals(extractTaskPkg(t))) {
                    int tid = readIntField(t, "taskId", readIntField(t, "persistentId", readIntField(t, "id", -1)));
                    if (tid > 0) return tid;
                }
            }
        }
        return -1;
    }

    private static int getTaskWindowingMode(int taskId) {
        if (taskId <= 0 || sAtm == null) return 0;
        List<?> running = invokeTaskQuery("getTasks");
        if (running != null) {
            for (Object t : running) {
                if (t == null) continue;
                int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                if (tid == taskId) {
                    int wm = readIntField(t, "windowingMode", 0);
                    if (wm != 0) return wm;
                    try {
                        Object cfg = t.getClass().getField("configuration").get(t);
                        if (cfg != null) {
                            Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                            if (wc != null) {
                                return (Integer) wc.getClass().getMethod("getWindowingMode").invoke(wc);
                            }
                        }
                    } catch (Throwable ignored) {}
                    return wm;
                }
            }
        }
        return 0;
    }

    private static Object findRootTaskInfo(int taskId) {
        if (taskId <= 0 || sAtm == null) return null;
        try {
            if (sVdId > 0) {
                try {
                    List<?> infos = (List<?>) sAtm.getClass()
                            .getMethod("getAllRootTaskInfosOnDisplay", int.class)
                            .invoke(sAtm, sVdId);
                    if (infos != null) {
                        for (Object info : infos) {
                            if (info != null && info.getClass().getField("taskId").getInt(info) == taskId) {
                                return info;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            List<?> all = (List<?>) sAtm.getClass().getMethod("getAllRootTaskInfos").invoke(sAtm);
            if (all != null) {
                for (Object info : all) {
                    if (info != null && info.getClass().getField("taskId").getInt(info) == taskId) {
                        return info;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object findRootTaskToken(int taskId) {
        Object info = findRootTaskInfo(taskId);
        if (info == null) return null;
        try {
            return info.getClass().getField("token").get(info);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Rect getTaskBounds(int taskId) {
        Object info = findRootTaskInfo(taskId);
        if (info == null) return null;
        try {
            Object cfg = info.getClass().getField("configuration").get(info);
            if (cfg != null) {
                Object wc = cfg.getClass().getField("windowConfiguration").get(cfg);
                if (wc != null) {
                    Object b = wc.getClass().getMethod("getBounds").invoke(wc);
                    if (b instanceof Rect) return new Rect((Rect) b);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Atomically updates alwaysOnTop / windowingMode / bounds via WindowContainerTransaction. */
    private static boolean applyWctForTask(int taskId, Boolean alwaysOnTop, int windowingMode, Rect bounds) {
        if (taskId <= 0 || sAtm == null) return false;
        try {
            Object tok = findRootTaskToken(taskId);
            if (tok == null) return false;
            Object wo = sAtm.getClass().getMethod("getWindowOrganizerController").invoke(sAtm);
            if (wo == null) return false;
            Class<?> wctCls = Class.forName("android.window.WindowContainerTransaction");
            Class<?> tokCls = Class.forName("android.window.WindowContainerToken");
            Object wct = wctCls.getConstructor().newInstance();
            if (alwaysOnTop != null) {
                wctCls.getMethod("setAlwaysOnTop", tokCls, boolean.class).invoke(wct, tok, alwaysOnTop);
            }
            if (windowingMode >= 0) {
                wctCls.getMethod("setWindowingMode", tokCls, int.class).invoke(wct, tok, windowingMode);
            }
            if (bounds != null) {
                wctCls.getMethod("setBounds", tokCls, Rect.class).invoke(wct, tok, bounds);
            }
            wo.getClass().getMethod("applyTransaction", wctCls).invoke(wo, wct);
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static void clearTaskAlwaysOnTop(int taskId) {
        if (taskId <= 0 || sAtm == null) return;
        if (applyWctForTask(taskId, Boolean.FALSE, -1, null)) return;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("setTaskAlwaysOnTop") && m.getParameterCount() == 2) {
                    m.invoke(sAtm, taskId, false);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void invokeResizeTask(int taskId, Rect bounds) {
        if (taskId <= 0 || bounds == null || sAtm == null) return;
        boolean resized = false;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("resizeTask")) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 3 && pt[0] == int.class && pt[1] == Rect.class && pt[2] == int.class) {
                        m.invoke(sAtm, taskId, bounds, 0);
                        resized = true;
                        break;
                    } else if (pt.length == 2 && pt[0] == int.class && pt[1] == Rect.class) {
                        m.invoke(sAtm, taskId, bounds);
                        resized = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (!resized) {
            runCmdOut("cmd", "activity", "task", "resize", String.valueOf(taskId),
                    String.valueOf(bounds.left), String.valueOf(bounds.top),
                    String.valueOf(bounds.right), String.valueOf(bounds.bottom));
        }
    }

    private static void resizeTaskBoundsOnly(int taskId, Rect bounds) {
        resizeTaskBoundsOnly(taskId, bounds, false);
    }

    /**
     * Resizes task bounds via ATM resizeTask so both WM mBounds and SurfaceFlinger layer position update.
     * Note: WCT.setBounds updates WM mBounds without moving SurfaceFlinger transform, and resizeTask is a no-op
     * if WM mBounds already matches target, so forceSyncIfEqual nudges 2px first when needed.
     */
    private static void resizeTaskBoundsOnly(int taskId, Rect bounds, boolean forceSyncIfEqual) {
        if (taskId <= 0 || bounds == null || sAtm == null) return;
        if (forceSyncIfEqual) {
            Rect cur = getTaskBounds(taskId);
            if (cur != null && cur.equals(bounds)) {
                Rect nudge = new Rect(bounds);
                if (nudge.left >= 2) nudge.left -= 2;
                else if (nudge.right - nudge.left > 64) nudge.right -= 2;
                else nudge.bottom = Math.max(nudge.top + 64, nudge.bottom - 2);
                invokeResizeTask(taskId, nudge);
            }
        }
        invokeResizeTask(taskId, bounds);
    }

    /** Moves task to virtual display and places it in native MULTI_WINDOW (6), bypassing MIUI Freeform scaling. */
    private static void ensureTaskResizeableAndMultiWindow(int taskId, Rect bounds) {
        ensureTaskResizeableAndMultiWindow(taskId, bounds, false);
    }

    private static void ensureTaskResizeableAndMultiWindow(int taskId, Rect bounds, boolean forceSyncIfEqual) {
        if (taskId <= 0 || sAtm == null) return;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                    m.invoke(sAtm, taskId, sVdId);
                    break;
                }
            }
        } catch (Throwable ignored) {}
        boolean resModeOk = false;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("setTaskResizeable") && m.getParameterCount() == 2) {
                    m.invoke(sAtm, taskId, 2);
                    resModeOk = true;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        if (!resModeOk) {
            runCmdOut("cmd", "activity", "task", "resizeable", String.valueOf(taskId), "2");
        }
        // Detach MiuiFreeformModeController if task was previously FREEFORM (5)
        if (getTaskWindowingMode(taskId) == 5) {
            if (!applyWctForTask(taskId, Boolean.FALSE, 1, null)) {
                clearTaskAlwaysOnTop(taskId);
                forceTaskWindowingMode(taskId, 1);
            }
        }
        // Clear alwaysOnTop and enforce MULTI_WINDOW (6) via WCT, but resize via ATM resizeTask for SF transform sync
        if (!applyWctForTask(taskId, Boolean.FALSE, 6, null)) {
            clearTaskAlwaysOnTop(taskId);
            forceTaskWindowingMode(taskId, 6);
        }
        resizeTaskBoundsOnly(taskId, bounds, forceSyncIfEqual);
    }

    private static boolean bringSplitTaskFront(int taskId, Rect bounds) {
        if (taskId <= 0 || sAtm == null || sVdId <= 0) return false;
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(sVdId);
            opts.setLaunchBounds(bounds);
            try {
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 6);
            } catch (Throwable ignored) {}
            Bundle b = opts.toBundle();
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                    Object rc = m.invoke(sAtm, taskId, b);
                    return !(rc instanceof Integer) || (Integer) rc >= 0;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void applySplitSlot(int slot, int knownTid, String pkg, String cls, boolean launchIfMissing) {
        if (sVdId <= 0 || pkg == null || pkg.isEmpty()) return;
        applyAppModeCompat(pkg, 1, false);
        Rect bounds = calcSplitRect(slot, sSplitRatio);
        int tid = knownTid > 0 ? knownTid : findTaskIdForPkg(pkg);
        if (tid > 0) {
            if (slot == 0) sSplitSlot0TaskId = tid;
            else sSplitSlot1TaskId = tid;
            ensureTaskResizeableAndMultiWindow(tid, bounds, launchIfMissing);
            if (launchIfMissing) {
                if (bringSplitTaskFront(tid, bounds)) {
                    ensureTaskResizeableAndMultiWindow(tid, bounds, true);
                    if (sHandler != null) {
                        sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 180L);
                    }
                    return;
                }
            } else {
                return;
            }
        }
        if (!launchIfMissing || sMStartActivity == null || sCtx == null) return;
        try {
            Intent intent = null;
            if (cls != null && !cls.isEmpty()) {
                intent = new Intent();
                intent.setComponent(new ComponentName(pkg, cls));
            } else {
                intent = sCtx.getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (intent == null) return;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(sVdId);
            opts.setLaunchBounds(bounds);
            try {
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 6);
            } catch (Throwable ignored) {}
            sMStartActivity.invoke(sAtm, null, "com.android.shell", null, intent, null, null, null, 0, 0, null, opts.toBundle());
            if (sHandler != null) {
                sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 220L);
                sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 600L);
                sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 1200L);
                sHandler.postDelayed(() -> verifySplitSlotAsync(slot, pkg), 1850L);
            }
        } catch (Throwable ignored) {}
    }

    private static void verifySplitSlotAsync(int slot, String pkg) {
        synchronized (sSplitLock) {
            if (!sSplitActive || sVdId <= 0 || pkg == null || pkg.isEmpty()) return;
            String curPkg = (slot == 0) ? sSplitSlot0Pkg : sSplitSlot1Pkg;
            if (!pkg.equals(curPkg)) return;
            int prevTid = (slot == 0) ? sSplitSlot0TaskId : sSplitSlot1TaskId;
            int tid = findTaskIdForPkg(pkg);
            if (tid > 0) {
                if (slot == 0) sSplitSlot0TaskId = tid;
                else sSplitSlot1TaskId = tid;
                Rect bounds = calcSplitRect(slot, sSplitRatio);
                ensureTaskResizeableAndMultiWindow(tid, bounds, prevTid <= 0);
            }
        }
    }

    private static int getEmptySplitSlot() {
        if (!sSplitActive) return -1;
        boolean f0 = sSplitSlot0Pkg != null && !sSplitSlot0Pkg.isEmpty();
        boolean f1 = sSplitSlot1Pkg != null && !sSplitSlot1Pkg.isEmpty();
        if (f0 && !f1) return 1;
        if (!f0 && f1) return 0;
        return -1;
    }

    private static int findExtLauncherTaskId() {
        if (sVdId <= 0 || sAtm == null) return -1;
        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running != null) {
                for (Object t : running) {
                    if (t == null) continue;
                    int did = readIntField(t, "displayId", -1);
                    if (did != sVdId) continue;
                    String pkg = extractTaskPkg(t);
                    if (!"com.extbridge.helper".equals(pkg)) continue;
                    Object top = null;
                    try { top = t.getClass().getField("topActivity").get(t); } catch (Throwable ignored) {}
                    if (top == null) {
                        try { top = t.getClass().getField("baseActivity").get(t); } catch (Throwable ignored) {}
                    }
                    if (top instanceof ComponentName && ((ComponentName) top).getClassName().contains("LauncherActivity")) {
                        int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                        if (tid > 0) return tid;
                    }
                }
            }
            List<?> infos = (List<?>) sAtm.getClass()
                    .getMethod("getAllRootTaskInfosOnDisplay", int.class)
                    .invoke(sAtm, sVdId);
            if (infos != null) {
                for (Object info : infos) {
                    if (info == null) continue;
                    Object top = null;
                    try { top = info.getClass().getField("topActivity").get(info); } catch (Throwable ignored) {}
                    if (top instanceof ComponentName) {
                        ComponentName cn = (ComponentName) top;
                        if ("com.extbridge.helper".equals(cn.getPackageName())
                                && cn.getClassName().contains("LauncherActivity")) {
                            return info.getClass().getField("taskId").getInt(info);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void ensureLauncherTaskFullscreenInBg() {
        int lTid = findExtLauncherTaskId();
        if (lTid <= 0) return;
        int wm = getTaskWindowingMode(lTid);
        Rect b = getTaskBounds(lTid);
        if (wm == 1 && (b == null || b.isEmpty()
                || (b.left == 0 && b.top == 0 && b.width() == sVirtW && b.height() == sVirtH))) {
            return;
        }
        detachFreeformAndEnsureFullscreen(lTid, true);
    }

    private static void syncLauncherForSplit(int emptySlot, boolean bringFront) {
        if (!sSplitActive || emptySlot < 0 || sVdId <= 0 || sAtm == null) return;
        Rect bounds = calcSplitRect(emptySlot, sSplitRatio);
        int lTid = findExtLauncherTaskId();
        if (lTid > 0) {
            ensureTaskResizeableAndMultiWindow(lTid, bounds, bringFront);
            if (bringFront) {
                bringSplitTaskFront(lTid, bounds);
                ensureTaskResizeableAndMultiWindow(lTid, bounds, true);
                if (sHandler != null) {
                    sHandler.postDelayed(() -> verifySplitLauncherAsync(emptySlot), 180L);
                }
            }
            return;
        }
        if (!bringFront || sMStartActivity == null) return;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.extbridge.helper", "com.extbridge.helper.LauncherActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            intent.putExtra("extId", sVdId);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(sVdId);
            opts.setLaunchBounds(bounds);
            try {
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(opts, 6);
            } catch (Throwable ignored) {}
            sMStartActivity.invoke(sAtm, null, "com.android.shell", null, intent, null, null, null, 0, 0, null, opts.toBundle());
            if (sHandler != null) {
                sHandler.postDelayed(() -> verifySplitLauncherAsync(emptySlot), 220L);
                sHandler.postDelayed(() -> verifySplitLauncherAsync(emptySlot), 600L);
            }
        } catch (Throwable ignored) {}
    }

    private static void verifySplitLauncherAsync(int expectedSlot) {
        synchronized (sSplitLock) {
            if (!sSplitActive || sVdId <= 0 || getEmptySplitSlot() != expectedSlot) return;
            int lTid = findExtLauncherTaskId();
            if (lTid > 0) {
                ensureTaskResizeableAndMultiWindow(lTid, calcSplitRect(expectedSlot, sSplitRatio), true);
            }
        }
    }

    private static void relayoutSplitTasks(boolean bringFront) {
        synchronized (sSplitLock) {
            if (!sSplitActive || sVdId <= 0) return;
            sLastSplitActionMs = SystemClock.uptimeMillis();
            int emptySlot = getEmptySplitSlot();
            if (emptySlot >= 0) {
                syncLauncherForSplit(emptySlot, bringFront);
            } else {
                ensureLauncherTaskFullscreenInBg();
            }
            if (sSplitSlot0Pkg != null && !sSplitSlot0Pkg.isEmpty()) {
                applySplitSlot(0, sSplitSlot0TaskId, sSplitSlot0Pkg, null, bringFront);
            }
            if (sSplitSlot1Pkg != null && !sSplitSlot1Pkg.isEmpty()) {
                applySplitSlot(1, sSplitSlot1TaskId, sSplitSlot1Pkg, null, bringFront);
            }
        }
    }

    private static boolean splitAssign(int slot, int tid, String pkg, String cls) {
        return handleSplitAction(slot == 0 ? 1 : 2, tid, pkg, cls);
    }

    private static boolean handleSplitAction(int action, int arg, String pkg, String cls) {
        synchronized (sSplitLock) {
            if (sVdId <= 0 || sAtm == null) return false;
            sLastSplitActionMs = SystemClock.uptimeMillis();
            if (action == 1 || action == 2) {
                int slot = (action == 1) ? 0 : 1;
                if (!shouldCompatOverridePkg(pkg)) return false;
                String safeCls = null;
                if (cls != null && !cls.isEmpty()) {
                    if (!isAllowedLaunchTarget(pkg, cls)) return false;
                    safeCls = cls;
                } else if (sCtx == null || sCtx.getPackageManager().getLaunchIntentForPackage(pkg) == null) {
                    return false;
                }
                int safeArg = (arg > 0 && isTaskAllowedForExtControl(arg, pkg, false)) ? arg : -1;
                sSplitActive = true;
                if (slot == 0) {
                    sSplitSlot0Pkg = pkg;
                    sSplitSlot0TaskId = safeArg;
                    if (pkg.equals(sSplitSlot1Pkg)) {
                        sSplitSlot1Pkg = "";
                        sSplitSlot1TaskId = -1;
                    }
                } else {
                    sSplitSlot1Pkg = pkg;
                    sSplitSlot1TaskId = safeArg;
                    if (pkg.equals(sSplitSlot0Pkg)) {
                        sSplitSlot0Pkg = "";
                        sSplitSlot0TaskId = -1;
                    }
                }
                int emptySlot = getEmptySplitSlot();
                if (emptySlot >= 0) {
                    syncLauncherForSplit(emptySlot, true);
                } else {
                    ensureLauncherTaskFullscreenInBg();
                }
                if (slot == 0) {
                    applySplitSlot(0, sSplitSlot0TaskId, sSplitSlot0Pkg, safeCls, true);
                    if (sSplitSlot1Pkg != null && !sSplitSlot1Pkg.isEmpty()) {
                        applySplitSlot(1, sSplitSlot1TaskId, sSplitSlot1Pkg, null, true);
                    }
                } else {
                    applySplitSlot(1, sSplitSlot1TaskId, sSplitSlot1Pkg, safeCls, true);
                    if (sSplitSlot0Pkg != null && !sSplitSlot0Pkg.isEmpty()) {
                        applySplitSlot(0, sSplitSlot0TaskId, sSplitSlot0Pkg, null, true);
                    }
                }
                notifyHelperSync();
                return true;
            } else if (action == 3) {
                if (sSplitActive && arg == 0) {
                    return handleSplitAction(6, 0, null, null);
                }
                ArrayList<String> cands = new ArrayList<>();
                ArrayList<Integer> candTids = new ArrayList<>();
                List<?> running = invokeTaskQuery("getTasks");
                if (running != null) {
                    for (int pass = 0; pass < 2 && cands.size() < 2; pass++) {
                        boolean onlyExt = (pass == 0);
                        for (Object t : running) {
                            if (t == null) continue;
                            int did = readIntField(t, "displayId", -1);
                            if (onlyExt && did != sVdId) continue;
                            String p = extractTaskPkg(t);
                            if (isProtectedSystemTaskPkg(p) || !shouldCompatOverridePkg(p)) continue;
                            int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                            if (tid > 0 && !cands.contains(p)) {
                                cands.add(p);
                                candTids.add(tid);
                                if (cands.size() >= 2) break;
                            }
                        }
                    }
                }
                if (cands.size() < 2) {
                    List<?> recent = invokeTaskQuery("getRecentTasks");
                    if (recent != null) {
                        for (Object t : recent) {
                            if (t == null) continue;
                            String p = extractTaskPkg(t);
                            if (isProtectedSystemTaskPkg(p) || !shouldCompatOverridePkg(p)) continue;
                            int tid = readIntField(t, "taskId", readIntField(t, "persistentId", readIntField(t, "id", -1)));
                            if (tid > 0 && !cands.contains(p)) {
                                cands.add(p);
                                candTids.add(tid);
                                if (cands.size() >= 2) break;
                            }
                        }
                    }
                }
                if (cands.isEmpty()) return false;
                sSplitActive = true;
                sSplitSlot0Pkg = cands.get(0);
                sSplitSlot0TaskId = candTids.get(0);
                if (cands.size() >= 2) {
                    sSplitSlot1Pkg = cands.get(1);
                    sSplitSlot1TaskId = candTids.get(1);
                } else {
                    sSplitSlot1Pkg = "";
                    sSplitSlot1TaskId = -1;
                }
                relayoutSplitTasks(true);
                notifyHelperSync();
                return true;
            } else if (action == 4) {
                sSplitRatio = clampSplitRatio(arg / 100f);
                if (sSplitActive) {
                    relayoutSplitTasks(false);
                }
                notifyHelperSync();
                return true;
            } else if (action == 5) {
                String tmpPkg = sSplitSlot0Pkg;
                int tmpTid = sSplitSlot0TaskId;
                sSplitSlot0Pkg = sSplitSlot1Pkg;
                sSplitSlot0TaskId = sSplitSlot1TaskId;
                sSplitSlot1Pkg = tmpPkg;
                sSplitSlot1TaskId = tmpTid;
                if (sSplitActive) {
                    relayoutSplitTasks(true);
                }
                notifyHelperSync();
                return true;
            } else if (action == 6) {
                exitSplitAndRestore(true);
                return true;
            }
            return false;
        }
    }

    private static void restoreLeavingSplitTask(String leavingPkg, int leavingTid) {
        if ((leavingPkg == null || leavingPkg.isEmpty()) && leavingTid <= 0) return;
        int tid = leavingTid > 0 ? leavingTid : findTaskIdForPkg(leavingPkg);
        if (tid > 0 && getAppMode(leavingPkg) != 3) {
            detachFreeformAndEnsureFullscreen(tid, true);
        }
        if (leavingPkg != null && !leavingPkg.isEmpty()) {
            int cfgMode = getAppMode(leavingPkg);
            if (cfgMode != 1) {
                applyAppModeCompat(leavingPkg, cfgMode, false);
            }
        }
    }

    private static void detachPkgFromSplit(String pkg, int taskId) {
        synchronized (sSplitLock) {
            if (!sSplitActive) return;
            boolean changed = false;
            String old0Pkg = null, old1Pkg = null;
            int old0Tid = -1, old1Tid = -1;
            if ((taskId > 0 && taskId == sSplitSlot0TaskId) || (pkg != null && !pkg.isEmpty() && pkg.equals(sSplitSlot0Pkg))) {
                old0Pkg = sSplitSlot0Pkg;
                old0Tid = sSplitSlot0TaskId;
                sSplitSlot0Pkg = "";
                sSplitSlot0TaskId = -1;
                changed = true;
            }
            if ((taskId > 0 && taskId == sSplitSlot1TaskId) || (pkg != null && !pkg.isEmpty() && pkg.equals(sSplitSlot1Pkg))) {
                old1Pkg = sSplitSlot1Pkg;
                old1Tid = sSplitSlot1TaskId;
                sSplitSlot1Pkg = "";
                sSplitSlot1TaskId = -1;
                changed = true;
            }
            if (!changed) return;
            restoreLeavingSplitTask(old0Pkg, old0Tid);
            restoreLeavingSplitTask(old1Pkg, old1Tid);
            boolean has0 = sSplitSlot0Pkg != null && !sSplitSlot0Pkg.isEmpty();
            boolean has1 = sSplitSlot1Pkg != null && !sSplitSlot1Pkg.isEmpty();
            if (!has0 && !has1) {
                clearSplitState();
                ensureLauncherTaskFullscreenInBg();
            } else {
                relayoutSplitTasks(true);
            }
            notifyHelperSync();
        }
    }

    /** Restores split tasks back to fullscreen (unless configured freeform) and notifies helper. */
    private static void exitSplitAndRestore(boolean focusSlot0) {
        synchronized (sSplitLock) {
            int t0 = sSplitSlot0TaskId > 0 ? sSplitSlot0TaskId : findTaskIdForPkg(sSplitSlot0Pkg);
            int t1 = sSplitSlot1TaskId > 0 ? sSplitSlot1TaskId : findTaskIdForPkg(sSplitSlot1Pkg);
            String p0 = sSplitSlot0Pkg;
            String p1 = sSplitSlot1Pkg;
            clearSplitState();
            ensureLauncherTaskFullscreenInBg();
            restoreLeavingSplitTask(p1, t1);
            restoreLeavingSplitTask(p0, t0);
            if (t0 > 0 && getAppMode(p0) != 3 && focusSlot0) {
                switchToTaskOnVd(t0, p0);
            }
            notifyHelperSync();
        }
    }

    /** Switches a task from floating Freeform small window (5) back to Fullscreen (1); enabling Freeform is forbidden in DeX mode. */
    private static int setTaskLiveWindowMode(int taskId, String pkg, int enableFreeform) {
        if (sVdId <= 0 || sAtm == null) return -1;
        String safePkg = isValidPackageName(pkg) ? pkg : null;
        int tid = (taskId > 0 && isTaskAllowedForExtControl(taskId, safePkg, false))
                ? taskId
                : (safePkg != null ? findTaskIdForPkg(safePkg) : -1);
        boolean currentlyFf = tid > 0 && getTaskWindowingMode(tid) == 5;
        boolean wantFf = enableFreeform < 0 ? !currentlyFf : (enableFreeform != 0);
        if (wantFf) {
            return -2;
        }

        if (sSplitActive && safePkg != null) {
            detachPkgFromSplit(safePkg, tid);
        }

        if (safePkg != null) {
            synchronized (sAppModes) {
                if (Integer.valueOf(3).equals(sAppModes.get(safePkg))) {
                    sAppModes.remove(safePkg);
                    saveAppModesLocked();
                }
            }
            applyAppModeCompat(safePkg, getAppMode(safePkg), true);
        }

        if (tid > 0) {
            try {
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("moveRootTaskToDisplay") && m.getParameterCount() == 2) {
                        m.invoke(sAtm, tid, sVdId);
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            detachFreeformAndEnsureFullscreen(tid, true);
            try {
                Bundle b = makeExtLaunchOptionsForMode(sVdId, 1).toBundle();
                for (Method m : sAtm.getClass().getMethods()) {
                    if (m.getName().equals("startActivityFromRecents") && m.getParameterCount() == 2) {
                        m.invoke(sAtm, tid, b);
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            notifyHelperSync();
            return 0;
        }

        if (safePkg != null && shouldCompatOverridePkg(safePkg) && sCtx != null && sMStartActivity != null) {
            try {
                Intent li = sCtx.getPackageManager().getLaunchIntentForPackage(safePkg);
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions opts = makeExtLaunchOptionsForMode(sVdId, 1);
                    sMStartActivity.invoke(sAtm, null, "com.android.shell", null, li, null, null, null, 0, 0, null, opts.toBundle());
                    notifyHelperSync();
                    return 0;
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static boolean removeTaskById(int taskId) {
        if (!isTaskAllowedForExtControl(taskId, null, true)) {
            return false;
        }
        boolean ok = removeTaskRaw(taskId);
        if (ok && sSplitActive) {
            detachPkgFromSplit(null, taskId);
        } else if (ok) {
            notifyHelperSync();
        }
        return ok;
    }

    /**
     * Prevents MIUI Home's shared Flutter Dock state machine from being corrupted by stale or
     * secondary-display com.miui.home tasks (e.g. left over from earlier external-desktop builds).
     * Only inspects ExtBridge's own virtual displays (sVdId / sSideVdId) or startup stale configs,
     * with adaptive backoff so unrelated displays never trigger com.miui.home restarts.
     */
    private static void sanitizeSystemHomeTasks(boolean checkCreatedDisplayConfig) {
        long now = SystemClock.uptimeMillis();
        if (!checkCreatedDisplayConfig && (now - sLastHomeSanitizeMs < sHomeSanitizeIntervalMs)) return;

        int disp0Count = 0;
        boolean hasOurVirtualDisplayLeak = false;
        boolean homeTopOnDisp0 = false;
        boolean seenTopOnDisp0 = false;
        ArrayList<Integer> leakedTaskIds = new ArrayList<>();
        ArrayList<Integer> dupDisp0TaskIds = new ArrayList<>();

        try {
            List<?> running = invokeTaskQuery("getTasks");
            if (running != null) {
                for (Object t : running) {
                    if (t == null) continue;
                    String pkg = extractTaskPkg(t);
                    int did = readIntField(t, "displayId", -1);
                    if (did == 0 && !seenTopOnDisp0) {
                        seenTopOnDisp0 = true;
                        homeTopOnDisp0 = "com.miui.home".equals(pkg);
                    }
                    if (!"com.miui.home".equals(pkg)) continue;
                    int tid = readIntField(t, "taskId", readIntField(t, "id", -1));
                    boolean isOurVd = (sVdId > 0 && did == sVdId) || (sSideVdId > 0 && did == sSideVdId);
                    if (isOurVd || (checkCreatedDisplayConfig && did > 0 && (sExtId <= 0 || did != sExtId))) {
                        hasOurVirtualDisplayLeak = true;
                        if (tid > 0) leakedTaskIds.add(tid);
                    } else if (did == 0) {
                        Object top = readObjField(t, "topActivity");
                        Object base = readObjField(t, "baseActivity");
                        String topCls = (top instanceof ComponentName) ? ((ComponentName) top).getClassName() : "";
                        String baseCls = (base instanceof ComponentName) ? ((ComponentName) base).getClassName() : "";
                        boolean isLauncherTask = topCls.contains("Launcher") || baseCls.contains("Launcher")
                                || (topCls.isEmpty() && baseCls.isEmpty());
                        if (isLauncherTask) {
                            disp0Count++;
                            if (disp0Count > 1 && tid > 0) dupDisp0TaskIds.add(tid);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        boolean staleCreatedConfig = false;
        if (checkCreatedDisplayConfig && !hasOurVirtualDisplayLeak && dupDisp0TaskIds.isEmpty()) {
            staleCreatedConfig = hasStaleMiuiHomeCreatedConfig();
        }

        if (!hasOurVirtualDisplayLeak && dupDisp0TaskIds.isEmpty() && !staleCreatedConfig) {
            sHomeSanitizeIntervalMs = 15000L;
            return;
        }

        sLastHomeSanitizeMs = now;
        sHomeSanitizeIntervalMs = Math.min(60000L, sHomeSanitizeIntervalMs * 2L);
        System.out.println("EXTBRIDGE_SANITIZE_MIUI_HOME leaked=" + hasOurVirtualDisplayLeak
                + " dup0=" + dupDisp0TaskIds.size()
                + " staleCfg=" + staleCreatedConfig
                + " homeTop0=" + homeTopOnDisp0);

        for (int tid : leakedTaskIds) {
            removeTaskRaw(tid);
        }
        for (int tid : dupDisp0TaskIds) {
            removeTaskRaw(tid);
        }

        runCmdOut("am", "force-stop", "com.miui.home");
        if (homeTopOnDisp0) {
            runCmdOut("am", "start",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.HOME",
                    "--display", "0");
        }
    }

    private static boolean removeTaskRaw(int taskId) {
        if (taskId <= 0 || sAtm == null) return false;
        try {
            for (Method m : sAtm.getClass().getMethods()) {
                if (m.getName().equals("removeTask") && m.getParameterCount() == 1) {
                    Object r = m.invoke(sAtm, taskId);
                    return !(r instanceof Boolean) || (Boolean) r;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean hasStaleMiuiHomeCreatedConfig() {
        try {
            String dump = runCmdOut("dumpsys", "activity", "com.miui.home");
            if (dump == null || dump.isEmpty()) return false;
            int from = 0;
            while (true) {
                int cIdx = dump.indexOf("created_config:", from);
                if (cIdx < 0) break;
                int endIdx = dump.indexOf("override_config:", cIdx);
                String seg = (endIdx > cIdx)
                        ? dump.substring(cIdx, endIdx)
                        : dump.substring(cIdx, Math.min(dump.length(), cIdx + 2048));
                int mIdx = seg.indexOf("MiuiConfiguration");
                if (mIdx >= 0) {
                    int dIdx = seg.indexOf("displayId:", mIdx);
                    if (dIdx >= 0) {
                        int p = dIdx + "displayId:".length();
                        while (p < seg.length() && seg.charAt(p) == ' ') p++;
                        int q = p;
                        while (q < seg.length() && Character.isDigit(seg.charAt(q))) q++;
                        if (q > p) {
                            int cfgDid = Integer.parseInt(seg.substring(p, q));
                            if (cfgDid != 0 && (sExtId <= 0 || cfgDid != sExtId)) return true;
                        }
                    }
                }
                from = cIdx + 15;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String runCmdOut(String... cmd) {
        final StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try { p.getOutputStream().close(); } catch (Throwable ignored) {}
            final java.io.InputStream in = p.getInputStream();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (sb) {
                            if (sb.length() < 262144) {
                                sb.append(line).append('\n');
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }, "ExtCmdReader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = p.waitFor(3000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
            }
            reader.join(250L);
        } catch (Throwable ignored) {
        } finally {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }
        }
        synchronized (sb) {
            return sb.toString();
        }
    }

    private static void refreshSysStringsIfNeeded(boolean wifiOn, boolean btOn) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastSysCheckMs < 2500L) return;
        sLastSysCheckMs = now;

        String ssid = "";
        if (wifiOn) {
            try {
                String ws = runCmdOut("cmd", "wifi", "status");
                int idx = ws.indexOf("Wifi is connected to \"");
                if (idx >= 0) {
                    int s = idx + "Wifi is connected to \"".length();
                    int e = ws.indexOf('"', s);
                    if (e > s) ssid = ws.substring(s, e);
                }
            } catch (Throwable ignored) {}
        }
        sCachedWifiSsid = ssid;

        String btDev = "";
        if (btOn) {
            try {
                String bs = runCmdOut("dumpsys", "bluetooth_manager");
                String[] lines = bs.split("\n");
                for (String ln : lines) {
                    String t = ln.trim();
                    if (t.contains("mActiveDevice:") && !t.endsWith("null")) {
                        int sp = t.indexOf('(');
                        int ep = t.indexOf(')', sp + 1);
                        if (sp >= 0 && ep > sp) { btDev = t.substring(sp + 1, ep).trim(); break; }
                    }
                    if (t.startsWith("Connected:") || t.contains("state=CONNECTED")) {
                        int idx = t.indexOf("name=");
                        if (idx >= 0) {
                            int end = t.indexOf(',', idx);
                            btDev = (end > idx ? t.substring(idx + 5, end) : t.substring(idx + 5)).trim();
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        sCachedBtName = btDev;
    }

    private static void writeSysStatusToParcel(Parcel reply) {
        if (reply == null) return;
        boolean wifiOn = false;
        boolean btOn = false;
        boolean airplaneOn = false;
        boolean powerSaveOn = false;
        try {
            WifiManager wm = (WifiManager) sCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) wifiOn = wm.isWifiEnabled();
        } catch (Throwable ignored) {}
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null) btOn = ba.isEnabled();
        } catch (Throwable ignored) {}
        try {
            airplaneOn = Settings.Global.getInt(sCtx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Throwable ignored) {}
        try {
            PowerManager pm = (PowerManager) sCtx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) powerSaveOn = pm.isPowerSaveMode();
        } catch (Throwable ignored) {}

        refreshSysStringsIfNeeded(wifiOn, btOn);
        reply.writeInt(wifiOn ? 1 : 0);
        reply.writeString(sCachedWifiSsid);
        reply.writeInt(btOn ? 1 : 0);
        reply.writeString(sCachedBtName);
        reply.writeInt(airplaneOn ? 1 : 0);
        reply.writeInt(powerSaveOn ? 1 : 0);
        reply.writeInt(sScreenDimmed ? 1 : 0);
    }

    private static final Object sScreenDimLock = new Object();

    private static boolean writeDimBackupAtomic(int mode, int brightInt, float brightFloat) {
        File bak = new File(DIM_BACKUP_PATH);
        File tmp = new File(DIM_BACKUP_PATH + ".tmp");
        try {
            if (tmp.exists()) tmp.delete();
            byte[] payload = (mode + "|" + brightInt + "|" + brightFloat + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(payload);
                fos.flush();
                fos.getFD().sync();
            }
            tmp.setReadable(false, false);
            tmp.setReadable(true, true);
            tmp.setWritable(false, false);
            tmp.setWritable(true, true);
            if (bak.exists() && !tmp.renameTo(bak)) {
                bak.delete();
                if (!tmp.renameTo(bak)) return false;
            } else if (!bak.exists() && !tmp.renameTo(bak)) {
                return false;
            }
            return bak.exists() && bak.length() > 0;
        } catch (Throwable t) {
            try { tmp.delete(); } catch (Throwable ignored) {}
            return false;
        }
    }

    private static void restoreOrphanScreenDimBackup() {
        synchronized (sScreenDimLock) {
            File bak = new File(DIM_BACKUP_PATH);
            if (!bak.exists()) return;
            sSavedBrightMode = 0;
            sSavedBrightInt = 800;
            sSavedBrightFloat = 0.35f;
            try (BufferedReader br = new BufferedReader(new FileReader(bak))) {
                String ln = br.readLine();
                if (ln != null && !ln.trim().isEmpty()) {
                    String[] p = ln.trim().split("\\|");
                    if (p.length >= 1) sSavedBrightMode = Integer.parseInt(p[0].trim());
                    if (p.length >= 2) sSavedBrightInt = Integer.parseInt(p[1].trim());
                    if (p.length >= 3) {
                        float parsedF = Float.parseFloat(p[2].trim());
                        if (Float.isFinite(parsedF)) sSavedBrightFloat = parsedF;
                    }
                }
            } catch (Throwable parseErr) {
                // Corrupt backup: never delete without restoring safe visible brightness first!
                System.err.println("EXTBRIDGE_DIM_BACKUP_CORRUPT restoring safe default: " + parseErr);
            }
            sScreenDimmed = true;
            setMainScreenDim(0);
        }
    }

    private static boolean setMainScreenDim(int arg) {
        synchronized (sScreenDimLock) {
            try {
                boolean wantDim = (arg < 0) ? !sScreenDimmed : (arg == 1);
                if (!wantDim && !sScreenDimmed && !new File(DIM_BACKUP_PATH).exists()) {
                    return true;
                }
                if (wantDim) {
                    float minFloat = 0.00171f;
                    float curFloatFromBi = -1f;
                    try {
                        if (sDm != null) {
                            Display d0 = sDm.getDisplay(0);
                            if (d0 != null) {
                                Object bi = Display.class.getMethod("getBrightnessInfo").invoke(d0);
                                if (bi != null) {
                                    float bm = bi.getClass().getField("brightnessMinimum").getFloat(bi);
                                    if (bm > 0f && bm <= 0.1f) minFloat = bm;
                                    float cb = bi.getClass().getField("brightness").getFloat(bi);
                                    if (cb > 0f && cb <= 1f) curFloatFromBi = cb;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    if (!sScreenDimmed) {
                        sSavedBrightMode = 0;
                        sSavedBrightInt = 1024;
                        sSavedBrightFloat = curFloatFromBi;
                        try {
                            sSavedBrightMode = Settings.System.getInt(sCtx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                            sSavedBrightInt = Settings.System.getInt(sCtx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 1024);
                        } catch (Throwable ignored) {
                            String cur = runCmdOut("sh", "-c",
                                    "printf '%s|%s' \"$(settings get system screen_brightness_mode)\" "
                                    + "\"$(settings get system screen_brightness)\"");
                            if (cur != null) {
                                String[] parts = cur.trim().split("\\|");
                                if (parts.length >= 1) {
                                    try { sSavedBrightMode = Integer.parseInt(parts[0].trim()); } catch (Throwable ignored2) {}
                                }
                                if (parts.length >= 2) {
                                    try { sSavedBrightInt = Integer.parseInt(parts[1].trim()); } catch (Throwable ignored2) {}
                                }
                            }
                        }
                        if (!writeDimBackupAtomic(sSavedBrightMode, sSavedBrightInt, sSavedBrightFloat)) {
                            System.err.println("EXTBRIDGE_SCREEN_DIM_ABORT backup write failed");
                            return false;
                        }
                    }

                    runCmdOut("sh", "-c",
                            "settings put system screen_brightness_mode 0; "
                            + "settings put system screen_brightness 1; "
                            + "cmd display set-brightness " + String.format(java.util.Locale.US, "%.6f", minFloat) + " 2>/dev/null || true");
                    sScreenDimmed = true;
                    System.out.println("EXTBRIDGE_SCREEN_DIM=1 min=" + minFloat + " savedMode=" + sSavedBrightMode + " savedInt=" + sSavedBrightInt + " savedFloat=" + sSavedBrightFloat);
                    return true;
                } else {
                    int restoreInt = Math.max(20, sSavedBrightInt > 1 ? sSavedBrightInt : 800);
                    int restoreMode = (sSavedBrightMode == 1) ? 1 : 0;
                    try {
                        if (sCtx != null) {
                            Settings.System.putInt(sCtx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, restoreInt);
                            Settings.System.putInt(sCtx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, restoreMode);
                        }
                    } catch (Throwable ignored) {}
                    String floatCmd = (Float.isFinite(sSavedBrightFloat) && sSavedBrightFloat > 0.002f && sSavedBrightFloat <= 1f)
                            ? ("cmd display set-brightness " + String.format(java.util.Locale.US, "%.6f", sSavedBrightFloat) + " 2>/dev/null || true; ")
                            : "";
                    runCmdOut("sh", "-c",
                            "settings put system screen_brightness " + restoreInt + "; "
                            + floatCmd
                            + "settings put system screen_brightness_mode " + restoreMode);
                    int verifyInt = -1;
                    try {
                        if (sCtx != null) {
                            verifyInt = Settings.System.getInt(sCtx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
                        }
                    } catch (Throwable ignored) {}
                    if (verifyInt <= 1) {
                        String rd = runCmdOut("settings", "get", "system", "screen_brightness");
                        try { if (rd != null) verifyInt = Integer.parseInt(rd.trim()); } catch (Throwable ignored) {}
                    }
                    sScreenDimmed = false;
                    if (verifyInt > 1) {
                        try { new File(DIM_BACKUP_PATH).delete(); } catch (Throwable ignored) {}
                    } else {
                        System.err.println("EXTBRIDGE_SCREEN_DIM_RESTORE_UNVERIFIED keeping backup verifyInt=" + verifyInt);
                    }
                    System.out.println("EXTBRIDGE_SCREEN_DIM=0 restoreInt=" + restoreInt + " verifyInt=" + verifyInt + " restoreFloat=" + sSavedBrightFloat + " mode=" + restoreMode);
                    return verifyInt > 1;
                }
            } catch (Throwable t) {
                System.out.println("EXTBRIDGE_SCREEN_DIM_ERR=" + t);
                return false;
            }
        }
    }

    private static boolean isAllowedSettingsAction(String action) {
        if (action == null || action.isEmpty()) return false;
        switch (action) {
            case Settings.ACTION_SETTINGS:
            case Settings.ACTION_WIFI_SETTINGS:
            case Settings.ACTION_BLUETOOTH_SETTINGS:
            case Settings.ACTION_DISPLAY_SETTINGS:
            case Settings.ACTION_SOUND_SETTINGS:
            case Settings.ACTION_AIRPLANE_MODE_SETTINGS:
            case Settings.ACTION_BATTERY_SAVER_SETTINGS:
            case Settings.ACTION_CAST_SETTINGS:
            case Settings.ACTION_ACCESSIBILITY_SETTINGS:
            case Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS:
            case Settings.ACTION_MANAGE_OVERLAY_PERMISSION:
            case Settings.ACTION_APPLICATION_DETAILS_SETTINGS:
            case Settings.ACTION_WIRELESS_SETTINGS:
                return true;
            default:
                return false;
        }
    }

    private static boolean execSysAction(int act, int arg, String str) {
        sLastSysCheckMs = 0L;
        try {
            if (act == 6) { // Main screen minimum brightness / blackout toggle
                return setMainScreenDim(arg);
            } else if (act == 1) { // Wi-Fi toggle
                WifiManager wm = (WifiManager) sCtx.getSystemService(Context.WIFI_SERVICE);
                boolean target = (arg < 0) ? (wm == null || !wm.isWifiEnabled()) : (arg == 1);
                runCmdOut("cmd", "wifi", "set-wifi-enabled", target ? "enabled" : "disabled");
                return true;
            } else if (act == 2) { // Bluetooth toggle
                BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                boolean target = (arg < 0) ? (ba == null || !ba.isEnabled()) : (arg == 1);
                runCmdOut("cmd", "bluetooth_manager", target ? "enable" : "disable");
                return true;
            } else if (act == 3) { // Airplane toggle
                boolean cur = Settings.Global.getInt(sCtx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                boolean target = (arg < 0) ? !cur : (arg == 1);
                runCmdOut("cmd", "connectivity", "airplane-mode", target ? "enable" : "disable");
                return true;
            } else if (act == 4) { // Power saver toggle
                PowerManager pm = (PowerManager) sCtx.getSystemService(Context.POWER_SERVICE);
                boolean target = (arg < 0) ? (pm == null || !pm.isPowerSaveMode()) : (arg == 1);
                boolean done = false;
                if (pm != null) {
                    try {
                        Object res = PowerManager.class.getMethod("setPowerSaveModeEnabled", boolean.class).invoke(pm, target);
                        done = !(res instanceof Boolean) || (Boolean) res;
                    } catch (Throwable ignored) {}
                }
                if (!done) {
                    Settings.Global.putInt(sCtx.getContentResolver(), "low_power", target ? 1 : 0);
                }
                return true;
            } else if (act == 5 && isAllowedSettingsAction(str)) { // Open allowlisted Settings action on Display 0 or sVdId
                Intent i = new Intent(str);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                int targetDid = (arg == 0) ? 0 : (sVdId > 0 ? sVdId : 0);
                ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(targetDid);
                sMStartActivity.invoke(sAtm, null, "com.android.shell", null, i, null, null, null, 0, 0, null, opts.toBundle());
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean grantAppWidgetBind() {
        if (getVerifiedHelperUid() <= 0) return false;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder awB = (IBinder) sm.getMethod("getService", String.class).invoke(null, "appwidget");
            if (awB == null) return false;
            Class<?> iawStub = Class.forName("com.android.internal.appwidget.IAppWidgetService$Stub");
            Object aw = iawStub.getMethod("asInterface", IBinder.class).invoke(null, awB);
            if (aw == null) return false;
            aw.getClass().getMethod("setBindAppWidgetPermission", String.class, int.class, boolean.class)
                    .invoke(aw, "com.extbridge.helper", 0, true);
            Object has = aw.getClass().getMethod("hasBindAppWidgetPermission", String.class, int.class)
                    .invoke(aw, "com.extbridge.helper", 0);
            return (has instanceof Boolean) && (Boolean) has;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void refreshMirrorTelemetryIfNeeded() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastMirTelemetryMs < 1800L) return;
        sLastMirTelemetryMs = now;

        // 1. Audio output route + volume detail
        String route = "平板外放";
        String audioDet = "";
        try {
            AudioManager am = (AudioManager) sCtx.getSystemService(Context.AUDIO_SERVICE);
            int curVol = am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
            int maxVol = am != null ? Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) : 15;
            boolean muted = am != null && (curVol == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC));

            String audioDump = runCmdOut("sh", "-c", "dumpsys audio 2>/dev/null | grep -A 8 'STREAM_MUSIC:' | grep 'Devices:' | head -n 1");
            String devStr = audioDump != null ? audioDump.trim().toLowerCase() : "";
            String btName = sCachedBtName != null ? sCachedBtName : "";
            if (devStr.contains("hdmi") || devStr.contains("aux_digital")) {
                route = "HDMI / DP 外接音频";
            } else if (devStr.contains("bt_a2dp") || devStr.contains("ble_headset") || devStr.contains("bt_sco")) {
                route = btName.isEmpty() ? "蓝牙音频设备" : ("蓝牙 · " + btName);
            } else if (devStr.contains("usb")) {
                route = "USB-C 外置声卡";
            } else if (devStr.contains("wired") || devStr.contains("headphone") || devStr.contains("headset")) {
                route = "有线耳机";
            } else if (devStr.contains("speaker") || am != null) {
                // Double check connected AudioDeviceInfo if dumpsys line wasn't matched
                boolean hasHdmi = false, hasBt = false, hasUsb = false, hasWired = false;
                String extraDevName = "";
                if (am != null) {
                    AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                    if (outs != null) {
                        for (AudioDeviceInfo d : outs) {
                            if (d == null) continue;
                            int tp = d.getType();
                            if (tp == AudioDeviceInfo.TYPE_HDMI || tp == AudioDeviceInfo.TYPE_HDMI_ARC || tp == 10 || tp == 11) {
                                hasHdmi = true;
                            } else if (tp == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || tp == AudioDeviceInfo.TYPE_BLE_HEADSET || tp == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                                hasBt = true;
                                if (d.getProductName() != null) extraDevName = d.getProductName().toString();
                            } else if (tp == AudioDeviceInfo.TYPE_USB_DEVICE || tp == AudioDeviceInfo.TYPE_USB_HEADSET || tp == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                                hasUsb = true;
                            } else if (tp == AudioDeviceInfo.TYPE_WIRED_HEADSET || tp == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                                hasWired = true;
                            }
                        }
                    }
                }
                if (devStr.isEmpty()) {
                    if (hasBt) route = extraDevName.isEmpty() ? "蓝牙音频" : ("蓝牙 · " + extraDevName);
                    else if (hasUsb) route = "USB 音频";
                    else if (hasWired) route = "有线耳机";
                    else if (hasHdmi) route = "HDMI 外接音频";
                    else route = "平板扬声器";
                } else {
                    route = "平板扬声器";
                }
            }
            audioDet = muted ? "静音 (0%)" : ("媒体音量 " + Math.round(curVol * 100f / maxVol) + "% (" + curVol + "/" + maxVol + ")");
        } catch (Throwable ignored) {}
        sCachedAudioRoute = route;
        sCachedAudioDetail = audioDet;

        // 2. Now-playing MediaSession via dumpsys media_session
        String mPkg = "";
        int mState = 0;
        String mTitle = "";
        String mSub = "";
        try {
            String ms = runCmdOut("dumpsys", "media_session");
            if (ms != null) {
                String curPkg = "";
                int curState = 0;
                String curDesc = "";
                String bestPkg = "";
                int bestScore = -1;
                String bestTitle = "";
                String bestSub = "";
                int bestState = 0;

                for (String ln : ms.split("\n")) {
                    String t = ln.trim();
                    if (t.startsWith("package=")) {
                        curPkg = t.substring("package=".length()).trim();
                        curState = 0;
                        curDesc = "";
                    } else if (t.contains("state=PLAYING")) {
                        curState = 3;
                    } else if (t.contains("state=PAUSED")) {
                        curState = 2;
                    } else if (t.contains("state=STOPPED")) {
                        curState = 1;
                    } else if (t.startsWith("metadata:") && t.contains("description=")) {
                        int di = t.indexOf("description=");
                        curDesc = t.substring(di + "description=".length()).trim();
                        if (!curPkg.isEmpty() && !"com.android.server.telecom".equals(curPkg) && !curDesc.isEmpty() && !"null".equals(curDesc)) {
                            int score = (curState == 3 ? 30 : (curState == 2 ? 20 : 10));
                            if (score > bestScore) {
                                bestScore = score;
                                bestPkg = curPkg;
                                bestState = curState;
                                String[] segs = curDesc.split(",");
                                bestTitle = segs.length > 0 ? segs[0].trim() : curDesc;
                                if (segs.length > 1) {
                                    String s1 = segs[1].trim();
                                    bestSub = "null".equals(s1) ? "" : s1;
                                } else {
                                    bestSub = "";
                                }
                            }
                        }
                    }
                }
                if (bestScore >= 0) {
                    mPkg = bestPkg;
                    mState = bestState;
                    mTitle = bestTitle;
                    mSub = bestSub;
                }
            }
        } catch (Throwable ignored) {}
        sCachedMediaPkg = mPkg;
        sCachedMediaState = mState;
        sCachedMediaTitle = mTitle;
        sCachedMediaSub = mSub;

        // 3. Battery details via sticky ACTION_BATTERY_CHANGED intent (zero subprocess fork)
        String bDet = "";
        try {
            Intent bat = (sCtx != null) ? sCtx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)) : null;
            if (bat != null) {
                int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                int temp10 = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                int voltMv = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                String src = ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) ? "AC快充"
                        : (((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) ? "USB供电"
                        : (((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) ? "无线充电" : "电池供电"));
                if (temp10 > 0) src += String.format(Locale.US, " · %.1f℃", temp10 / 10f);
                if (voltMv > 1000) src += String.format(Locale.US, " · %.2fV", voltMv / 1000f);
                bDet = src;
            }
        } catch (Throwable ignored) {}
        sCachedBattDetail = bDet;

        // 4. Wi-Fi link speed / RSSI
        String wDet = "";
        try {
            WifiManager wm = (WifiManager) sCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getLinkSpeed() > 0) {
                    wDet = wi.getLinkSpeed() + " Mbps · " + wi.getRssi() + " dBm";
                }
            }
        } catch (Throwable ignored) {}
        sCachedWifiDetail = wDet;

        // 5. Today's calendar instances via ContentResolver
        String calText = "";
        try {
            java.util.Calendar calStart = java.util.Calendar.getInstance();
            long nowEpoch = calStart.getTimeInMillis();
            calStart.set(java.util.Calendar.HOUR_OF_DAY, 0);
            calStart.set(java.util.Calendar.MINUTE, 0);
            calStart.set(java.util.Calendar.SECOND, 0);
            long dayStart = calStart.getTimeInMillis();
            long dayEnd = dayStart + 48L * 3600L * 1000L; // Today + tomorrow
            Uri uri = Uri.parse("content://com.android.calendar/instances/when/" + dayStart + "/" + dayEnd);
            StringBuilder sb = new StringBuilder();
            try (Cursor c = sCtx.getContentResolver().query(uri,
                    new String[]{"title", "begin", "allDay"}, null, null, "begin ASC")) {
                if (c != null) {
                    java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm", Locale.CHINA);
                    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("MM/dd", Locale.CHINA);
                    int shown = 0;
                    while (c.moveToNext() && shown < 2) {
                        String title = c.getString(0);
                        long begin = c.getLong(1);
                        int allDay = c.getInt(2);
                        if (title == null || title.trim().isEmpty()) continue;
                        if (begin < nowEpoch - 3600_000L && allDay == 0) continue;
                        if (sb.length() > 0) sb.append("\n");
                        String when = (allDay == 1) ? (df.format(new java.util.Date(begin)) + " 全天")
                                : tf.format(new java.util.Date(begin));
                        sb.append(when).append(" ").append(title.trim());
                        shown++;
                    }
                }
            }
            calText = sb.toString();
        } catch (Throwable ignored) {}
        sCachedCalendarText = calText.isEmpty() ? "近两日暂无日历日程" : calText;
    }

    private static void writeMirrorTelemetryToParcel(Parcel reply) {
        if (reply == null) return;
        refreshMirrorTelemetryIfNeeded();
        reply.writeString(sCachedAudioRoute != null ? sCachedAudioRoute : "");
        reply.writeString(sCachedAudioDetail != null ? sCachedAudioDetail : "");
        reply.writeString(sCachedMediaPkg != null ? sCachedMediaPkg : "");
        reply.writeInt(sCachedMediaState);
        reply.writeString(sCachedMediaTitle != null ? sCachedMediaTitle : "");
        reply.writeString(sCachedMediaSub != null ? sCachedMediaSub : "");
        reply.writeString(sCachedCalendarText != null ? sCachedCalendarText : "");
        reply.writeString(sCachedBattDetail != null ? sCachedBattDetail : "");
        reply.writeString(sCachedWifiDetail != null ? sCachedWifiDetail : "");
    }
}
