package com.extbridge.helper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku front-end: liveness/permission probing, permission request and status text.
 *
 * <p>ExtBridge no longer needs Wireless-Debugging pairing (6-digit code) when the user has
 * Shizuku running: Shizuku exposes the same privileged (shell/root) execution context the
 * pairing + `adb connect` round-trip used to provide, so the daemon can be released and
 * launched directly from inside the app.
 */
public final class ShizukuBootstrap {
    private static final String TAG = "ExtBridgeShizuku";

    /** Request code used by {@link Shizuku#requestPermission(int)}. */
    public static final int PERM_REQUEST_CODE = 4317;

    /** Original Shizuku manager package. */
    private static final String PKG_MANAGER = "moe.shizuku.manager";
    /** Older / fork package name still used by some builds. */
    private static final String PKG_MANAGER_LEGACY = "moe.shizuku.privileged.api";
    private static final String DOWNLOAD_URL = "https://shizuku.rikka.app/download/";

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    public interface PermissionCallback {
        void onResult(boolean granted, String message);
    }

    private static volatile PermissionCallback sPermCallback;
    private static volatile boolean sListenerAdded = false;

    private static final Shizuku.OnRequestPermissionResultListener PERM_LISTENER =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != PERM_REQUEST_CODE) return;
                    boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    final PermissionCallback cb = sPermCallback;
                    sPermCallback = null;
                    if (cb != null) {
                        post(cb, granted, granted
                                ? "Shizuku 授权成功，可以开始激活 ExtBridge 服务"
                                : "Shizuku 授权被拒绝，请在 Shizuku 应用中手动允许 ExtBridge");
                    }
                }
            };

    private ShizukuBootstrap() {}

    /** Result of a privileged shell execution. */
    public static final class ShellResult {
        public final int code;
        public final String output;

        public ShellResult(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    /** True when the Shizuku server binder is reachable (service running and manager alive). */
    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku API version reported by the running server, or -1 when unavailable. */
    public static int apiVersion() {
        try {
            return isRunning() ? Shizuku.getVersion() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean hasPermission() {
        try {
            if (!isRunning()) return false;
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isManagerInstalled(Context ctx) {
        if (ctx == null) return false;
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : new String[]{PKG_MANAGER, PKG_MANAGER_LEGACY}) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Human readable status used by the activation sheet. */
    public static String statusText(Context ctx) {
        if (!isRunning()) {
            if (isManagerInstalled(ctx)) {
                return "已安装 Shizuku，但服务未运行：请打开 Shizuku 应用并按提示启动服务（无线调试或 Root 方式）。";
            }
            return "未检测到 Shizuku：请先安装并启动 Shizuku，或改用下方的 Root / 电脑 ADB 方式激活。";
        }
        String ver = "v" + ShizukuVersionText();
        if (hasPermission()) {
            String uid = uidLabel();
            return "已连接 Shizuku (" + ver + (uid.isEmpty() ? "" : " · " + uid) + ")，已授权。";
        }
        return "Shizuku 服务运行中 (" + ver + ")，尚未授权：点击下方「授权 Shizuku」按钮。";
    }

    private static String ShizukuVersionText() {
        try {
            return String.valueOf(Shizuku.getVersion());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String selinux() {
        try {
            return String.valueOf(Shizuku.getSELinuxContext());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String uidLabel() {
        try {
            int uid = Shizuku.getUid();
            if (uid == 0) return "root 身份";
            if (uid == 2000) return "shell 身份";
            return "uid " + uid;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Requests the Shizuku permission. The callback is always invoked on the main thread,
     * including the "already granted" and "service missing" shortcut paths.
     */
    public static void requestPermissionAsync(final Context ctx, final PermissionCallback cb) {
        sPermCallback = cb;
        if (!isRunning()) {
            sPermCallback = null;
            String msg = isManagerInstalled(ctx)
                    ? "Shizuku 服务未运行：请先打开 Shizuku 并启动服务"
                    : "未检测到 Shizuku：请先安装 Shizuku 应用";
            if (cb != null) post(cb, false, msg);
            return;
        }
        if (hasPermission()) {
            sPermCallback = null;
            if (cb != null) post(cb, true, "Shizuku 权限已授权");
            return;
        }
        ensurePermissionListener();
        try {
            Shizuku.requestPermission(PERM_REQUEST_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
            sPermCallback = null;
            if (cb != null) post(cb, false, "请求 Shizuku 权限失败: " + t.getMessage());
        }
    }

    private static synchronized void ensurePermissionListener() {
        if (sListenerAdded) return;
        try {
            Shizuku.addRequestPermissionResultListener(PERM_LISTENER);
            sListenerAdded = true;
        } catch (Throwable t) {
            // Binder not ready yet; requestPermissionAsync will retry on the next attempt.
            Log.w(TAG, "addRequestPermissionResultListener failed", t);
        }
    }

    /** Opens the Shizuku manager app (or the download page when it is not installed). */
    public static void openManager(Context ctx) {
        if (ctx == null) return;
        Intent launch = null;
        for (String pkg : new String[]{PKG_MANAGER, PKG_MANAGER_LEGACY}) {
            try {
                launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) break;
            } catch (Throwable ignored) {
            }
        }
        try {
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                return;
            }
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(web);
        } catch (Throwable t) {
            Log.w(TAG, "openManager failed", t);
        }
    }

    /**
     * Runs a shell script with Shizuku's privilege (UID 2000 when Shizuku was started over ADB,
     * UID 0 when it was started as root) and returns the combined output.
     *
     * <p>Uses the IShizukuService#newProcess binder transaction (what Shizuku#newProcess used to
     * wrap) and blocks until the process exits or {@code timeoutMs} elapses.
     */
    public static ShellResult execShellSync(String command, long timeoutMs) {
        if (command == null) return new ShellResult(-1, "命令为空");
        IBinder binder;
        try {
            binder = Shizuku.getBinder();
        } catch (Throwable t) {
            binder = null;
        }
        if (binder == null) {
            return new ShellResult(-1, "Shizuku 服务未连接");
        }

        IRemoteProcess proc;
        try {
            Log.i(TAG, "execShell: len=" + command.length() + " uid=" + uidLabel() + " ctx=" + selinux());
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            proc = service.newProcess(new String[]{"/system/bin/sh", "-c", command}, null, null);
            Log.i(TAG, "execShell: newProcess ok");
        } catch (Throwable t) {
            Log.e(TAG, "newProcess failed", t);
            return new ShellResult(-1, "Shizuku 无法创建特权进程: " + t.getMessage());
        }
        if (proc == null) {
            return new ShellResult(-1, "Shizuku 无法创建特权进程");
        }

        final StringBuilder outBuf = new StringBuilder();
        final StringBuilder errBuf = new StringBuilder();
        Thread outReader = null;
        Thread errReader = null;
        try {
            outReader = startReader(proc.getInputStream(), outBuf, "ExtShizukuOut");
            errReader = startReader(proc.getErrorStream(), errBuf, "ExtShizukuErr");
        } catch (Throwable t) {
            Log.w(TAG, "stream setup failed", t);
        }

        long deadline = System.currentTimeMillis() + Math.max(1500L, timeoutMs);
        boolean finished = joinReader(outReader, deadline);
        finished = joinReader(errReader, deadline) && finished;
        int code = -1;
        if (!finished) {
            try { proc.destroy(); } catch (Throwable ignored) {}
            joinReader(outReader, System.currentTimeMillis() + 500L);
            joinReader(errReader, System.currentTimeMillis() + 500L);
            return new ShellResult(-1, "Shizuku 命令执行超时 (" + timeoutMs + "ms)\n" + mergeOutput(outBuf, errBuf));
        }
        try {
            code = proc.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "waitFor failed", t);
        }
        String merged = mergeOutput(outBuf, errBuf);
        Log.i(TAG, "execShell: code=" + code + " outLen=" + merged.length());
        return new ShellResult(code, merged);
    }

    private static Thread startReader(final ParcelFileDescriptor pfd, final StringBuilder sink, String name) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (pfd == null) return;
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                     BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (sink) {
                            if (sink.length() < 8192) {
                                if (sink.length() > 0) sink.append('\n');
                                sink.append(line);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean joinReader(Thread t, long deadlineMs) {
        if (t == null) return true;
        try {
            long remaining = Math.max(0L, deadlineMs - System.currentTimeMillis());
            t.join(remaining);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    private static String mergeOutput(StringBuilder outBuf, StringBuilder errBuf) {
        String out = outBuf.toString().trim();
        String err = errBuf.toString().trim();
        if (err.isEmpty()) return out;
        if (out.isEmpty()) return err;
        return out + "\n" + err;
    }

    private static void post(final PermissionCallback cb, final boolean granted, final String msg) {
        sMain.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(granted, msg);
            }
        });
    }
}
