package com.extbridge.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * On-device daemon activation helper (no bundled adb binary, no Wireless-Debugging pairing).
 *
 * <p>Three activation paths are supported, all of them sharing the same hardened bootstrap
 * script (SHA-256 verified atomic jar swap + appops grant + shell drop):
 * <ul>
 *   <li><b>Shizuku</b> — {@link #shizukuBootstrapAsync} runs the script through Shizuku's
 *       privileged process (shell uid 2000 when Shizuku was started over ADB, root otherwise).</li>
 *   <li><b>Root</b> — {@link #rootBootstrapAsync} stages a verified temp jar via {@code su} and
 *       transitions to uid 2000 before launching.</li>
 *   <li><b>PC ADB</b> — {@link #buildPcAdbCommand()} emits the copy-paste one-liner users run
 *       from a computer.</li>
 * </ul>
 */
public final class AdbBootstrap {
    private static final String TAG = "ExtBridgeAdb";
    private static final String PREFS_NAME = "extbridge_prefs";
    private static final String KEY_AUTO_ROOT = "auto_root_enabled";
    private static final String KEY_AUTO_SHIZUKU = "auto_shizuku_enabled";

    private static final ExecutorService sExec = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    public interface ProgressListener {
        void onStep(String message);

        void onResult(boolean success, String message);
    }

    private AdbBootstrap() {}

    /**
     * Zero '$', zero backticks, zero escaped inner quotes: runs identically in Bash, Zsh, sh,
     * PowerShell and cmd.exe.
     */
    public static String buildPcAdbCommand() {
        return "adb shell \"rm -f /data/local/tmp/extb_src.apk /data/local/tmp/extbridge.jar.new && "
                + "pm path com.extbridge.helper | cut -d: -f2 | head -n1 | xargs cat > /data/local/tmp/extb_src.apk && "
                + "test -s /data/local/tmp/extb_src.apk && "
                + "unzip -p /data/local/tmp/extb_src.apk assets/extbridge.jar > /data/local/tmp/extbridge.jar.new && "
                + "rm -f /data/local/tmp/extb_src.apk && "
                + "test -s /data/local/tmp/extbridge.jar.new && "
                + "unzip -tq /data/local/tmp/extbridge.jar.new >/dev/null 2>&1 && "
                + "chmod 644 /data/local/tmp/extbridge.jar.new && "
                + "timeout 6 env CLASSPATH=/data/local/tmp/extbridge.jar.new app_process /data/local/tmp com.extbridge.daemon.ExtBridge --verify-helper >/dev/null 2>&1 && "
                + "(timeout 8 env CLASSPATH=/data/local/tmp/extbridge.jar.new app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true) && "
                + "(pgrep -f '^app_process.*ExtBridge' >/dev/null 2>&1 && (pkill -f '^app_process.*ExtBridge' 2>/dev/null || true) && sleep 1 && (pkill -f -l KILL '^app_process.*ExtBridge' 2>/dev/null || true) || true) && "
                + "rm -f /data/local/tmp/extbridge.jar && "
                + "mv -f /data/local/tmp/extbridge.jar.new /data/local/tmp/extbridge.jar && "
                + "test -s /data/local/tmp/extbridge.jar && "
                + "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow && "
                + "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow && "
                + "(appops set com.extbridge.helper 10021 allow 2>/dev/null || true) && "
                + "(appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true) && "
                + "(pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null || true) && "
                + "(dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1 || true) && "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge >/data/local/tmp/extbridge.log 2>&1 </dev/null &)\"";
    }

    // ---------------------------------------------------------------------------------------
    // Shizuku activation path
    // Shizuku already runs with shell (uid 2000) or root privileges, so the very same bootstrap
    // script that a computer would run over `adb shell` can be executed in-process instead.
    // ---------------------------------------------------------------------------------------

    public static void shizukuBootstrapAsync(
            final Context ctx,
            final boolean markAutoShizuku,
            final ProgressListener listener) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            try {
                shizukuBootstrapSync(appCtx, markAutoShizuku, listener);
            } catch (Throwable t) {
                Log.e(TAG, "shizukuBootstrap failed", t);
                postResult(listener, false, "Shizuku 激活异常: " + t.getMessage());
            }
        });
    }

    private static void shizukuBootstrapSync(
            Context appCtx,
            boolean markAutoShizuku,
            ProgressListener listener) {
        if (!ShizukuBootstrap.isRunning()) {
            postResult(listener, false, ShizukuBootstrap.isManagerInstalled(appCtx)
                    ? "Shizuku 服务未运行：请先打开 Shizuku 应用并启动服务后重试。"
                    : "未检测到 Shizuku：请先安装并启动 Shizuku，或改用 Root / 电脑 ADB 激活。");
            return;
        }
        if (!ShizukuBootstrap.hasPermission()) {
            postResult(listener, false, "尚未授予 Shizuku 权限：请先点击「授权 Shizuku」并允许后重试。");
            return;
        }

        postStep(listener, "已连接 Shizuku，正在以特权身份释放并启动守护进程...");
        String expectedSha = computeAssetJarSha256(appCtx);
        String bootstrapCmd = buildDaemonBootstrapScript(expectedSha);

        // Trace every command into /data/local/tmp so a silent early death (e.g. SIGTERM from the
        // remote process layer) can be diagnosed from the activation sheet / logcat.
        final String tracePath = "/data/local/tmp/extbridge_boot.trace";
        String wrappedCmd = "TRACE=" + tracePath + "; "
                + "if : 2>/dev/null > \"$TRACE\"; then set -x; exec 2>>\"$TRACE\"; fi; "
                + bootstrapCmd;

        CmdRes res = runShizuku(35000, wrappedCmd);
        String out = res.output == null ? "" : res.output.trim();
        if (!out.contains("LAUNCH_DONE")) {
            String trace = ShizukuBootstrap.execShellSync(
                    "tail -n 25 " + tracePath + " 2>/dev/null", 8000).output;
            String probe = ShizukuBootstrap.execShellSync(
                    "echo PROBE_OK; id; getenforce; ls -l /data/local/tmp/extbridge.jar 2>/dev/null", 8000).output;
            Log.w(TAG, "shizuku boot failed code=" + res.code + " out=" + out);
            Log.w(TAG, "shizuku boot trace:\n" + trace);
            Log.w(TAG, "shizuku probe:\n" + probe);
            postResult(listener, false, "Shizuku Shell 启动失败"
                    + (res.code != 0 ? " (exit " + res.code + ")" : "") + ":\n" + out
                    + "\n\n执行轨迹:\n" + (trace == null || trace.trim().isEmpty() ? "(空)" : trace.trim())
                    + "\n\n环境探测:\n" + (probe == null ? "" : probe.trim()));
            return;
        }

        if (markAutoShizuku) {
            setAutoShizukuEnabled(appCtx, true);
        }
        awaitDaemonBinderReady(appCtx, false, listener, "Shizuku 激活成功，ExtBridge 服务已就绪！");
    }

    /**
     * Executes a shell script through Shizuku's privileged process (uid 2000 when Shizuku was
     * started over ADB / Wireless Debugging, uid 0 when started by root; the daemon itself drops
     * root fail-closed).
     */
    private static CmdRes runShizuku(long timeoutMs, String shellCmd) {
        ShizukuBootstrap.ShellResult res = ShizukuBootstrap.execShellSync(shellCmd, timeoutMs);
        return new CmdRes(res.code, res.output);
    }

    public static boolean isAutoShizukuEnabled(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_AUTO_SHIZUKU, true);
    }

    public static void setAutoShizukuEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_AUTO_SHIZUKU, enabled).apply();
    }

    public static boolean isAutoRootEnabled(Context ctx) {
        if (ctx == null) return false;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_AUTO_ROOT, false);
    }

    public static void setAutoRootEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_AUTO_ROOT, enabled).apply();
    }

    public static boolean hasSuBinaryHint() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/vendor/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        };
        for (String p : paths) {
            try {
                File f = new File(p);
                if (f.exists()) return true;
            } catch (Throwable ignored) {}
        }
        String envPath = System.getenv("PATH");
        if (envPath != null) {
            for (String dir : envPath.split(":")) {
                if (dir == null || dir.isEmpty()) continue;
                try {
                    if (new File(dir, "su").exists()) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static String computeAssetJarSha256(Context ctx) {
        if (ctx == null) return "";
        try (InputStream in = ctx.getAssets().open("extbridge.jar")) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] dig = md.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : dig) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void rootBootstrapAsync(final Context ctx, final boolean markAutoRoot, final ProgressListener listener) {
        final Context appCtx = ctx.getApplicationContext();
        sExec.execute(() -> {
            postStep(listener, "正在请求 Root (su) 权限...");
            String[] staged = stageBridgeJarWithSha256(appCtx);
            if (staged == null || staged[0] == null || staged[1] == null || staged[1].isEmpty()) {
                postResult(listener, false, "无法从 APK 释放并校验 extbridge.jar");
                return;
            }
            String qStage = staged[0].replace("'", "");
            String expSha = staged[1].toLowerCase(Locale.US);
            byte[] rnd = new byte[8];
            new SecureRandom().nextBytes(rnd);
            StringBuilder nonce = new StringBuilder(16);
            for (byte b : rnd) {
                nonce.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            // Literal cryptographic-nonce path avoids environment variable wiping across `su 2000`
            String rootTmp = "/data/local/tmp/extb_" + nonce + ".jar";
            String shellInner = buildShellSwapAndLaunchScript("'" + rootTmp + "'", expSha);
            String qShellInner = shellInner.replace("'", "'\\''");
            // Root ONLY populates an O_CREAT|O_EXCL (`set -C`) temp jar verified against expSha
            // (with automatic `pm path` fallback when Magisk/KernelSU Mount Namespace is Isolated),
            // chowns it 2000:2000, and transitions to uid 2000 (`u:r:shell:s0`) before running app_process!
            String script = "UID_NOW=$(id -u 2>/dev/null || echo -1); "
                    + "if [ \"$UID_NOW\" != \"0\" ]; then echo NO_ROOT:$UID_NOW; exit 12; fi; "
                    + "if ! (umask 022; set -C; : > '" + rootTmp + "') 2>/dev/null || [ -L '" + rootTmp + "' ]; then "
                    + "rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_MKTEMP; exit 21; fi; "
                    + "if [ -f '" + qStage + "' ] && [ ! -L '" + qStage + "' ] && [ -s '" + qStage + "' ]; then "
                    + "cat '" + qStage + "' > '" + rootTmp + "' 2>/dev/null; fi; "
                    + "SRC_SHA=$(sha256sum '" + rootTmp + "' 2>/dev/null | cut -d' ' -f1); "
                    + "if [ \"$SRC_SHA\" != '" + expSha + "' ]; then "
                    + "APK=$(pm path com.extbridge.helper 2>/dev/null | head -n1 | cut -d: -f2); "
                    + "if [ -n \"$APK\" ] && [ -f \"$APK\" ] && [ ! -L \"$APK\" ]; then "
                    + "unzip -p \"$APK\" assets/extbridge.jar > '" + rootTmp + "' 2>/dev/null; fi; "
                    + "SRC_SHA=$(sha256sum '" + rootTmp + "' 2>/dev/null | cut -d' ' -f1); "
                    + "fi; "
                    + "if [ \"$SRC_SHA\" != '" + expSha + "' ] || ! unzip -tq '" + rootTmp + "' >/dev/null 2>&1; then "
                    + "rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_STAGE_HASH:$SRC_SHA; exit 21; fi; "
                    + "chmod 644 '" + rootTmp + "' && chown -h 2000:2000 '" + rootTmp + "' "
                    + "|| { rm -f '" + rootTmp + "' 2>/dev/null; echo ERR_STAGE_CHOWN; exit 21; }; "
                    + "run_as_shell() { "
                    + "su 2000 -c \"$1\" && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "su shell -c \"$1\" 2>/dev/null && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "su 2000 /system/bin/sh -c \"$1\" 2>/dev/null && return 0; rc=$?; [ \"$rc\" -ge 20 ] && [ \"$rc\" -le 29 ] && return \"$rc\"; "
                    + "/system/bin/sh -c \"$1\"; "
                    + "}; "
                    + "run_as_shell '" + qShellInner + "'; RC=$?; "
                    + "rm -f '" + rootTmp + "' 2>/dev/null; exit $RC";

            CmdRes res = runSu(25000, script);
            String out = res.output == null ? "" : res.output.trim();
            if (res.code != 0 || !out.contains("LAUNCH_DONE")) {
                String hint = out.contains("NO_ROOT")
                        ? "未获取到 Root 权限（请在 Magisk / KernelSU / APatch 弹窗中允许授权，或改用 Shizuku / 电脑 ADB 激活）。"
                        : ("Root 执行失败（设备可能未 Root 或未对 ExtBridge 授权 Root）：\n" + out);
                postResult(listener, false, hint);
                return;
            }

            if (markAutoRoot) {
                setAutoRootEnabled(appCtx, true);
            }
            awaitDaemonBinderReady(appCtx, true, listener, "Root 一键激活成功，ExtBridge 服务已就绪！");
        });
    }

    private static String[] stageBridgeJarWithSha256(Context ctx) {
        File outFile = new File(ctx.getFilesDir(), "extbridge_stage.jar");
        try (InputStream in = ctx.getAssets().open("extbridge.jar");
             OutputStream out = new FileOutputStream(outFile, false)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
            out.flush();
            outFile.setReadable(false, false);
            outFile.setReadable(true, true);
            outFile.setWritable(false, false);
            outFile.setWritable(true, true);
            byte[] dig = md.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : dig) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return new String[]{outFile.getAbsolutePath(), hex.toString()};
        } catch (Throwable t) {
            Log.w(TAG, "stageBridgeJar failed", t);
            return null;
        }
    }

    private static String buildShellSwapAndLaunchScript(String tmpJarExpr, String expectedSha256) {
        String cleanSha = (expectedSha256 == null) ? "" : expectedSha256.trim().toLowerCase(Locale.US);
        String shaGuard = cleanSha.matches("^[0-9a-f]{64}$")
                ? ("ACT_SHA=$(sha256sum " + tmpJarExpr + " 2>/dev/null | cut -d' ' -f1); "
                        + "if [ -n \"$ACT_SHA\" ] && [ \"$ACT_SHA\" != '" + cleanSha + "' ]; then "
                        + "rm -f " + tmpJarExpr + "; echo ERR_JAR_SHA256; exit 21; fi; ")
                : "";
        String postSwapShaGuard = cleanSha.matches("^[0-9a-f]{64}$")
                ? ("FINAL_SHA=$(sha256sum /data/local/tmp/extbridge.jar 2>/dev/null | cut -d' ' -f1); "
                        + "if [ -n \"$FINAL_SHA\" ] && [ \"$FINAL_SHA\" != '" + cleanSha + "' ]; then "
                        + "echo ERR_FINAL_SHA256; exit 22; fi; ")
                : "";
        return "if [ ! -f " + tmpJarExpr + " ] || [ -L " + tmpJarExpr + " ] || [ ! -s " + tmpJarExpr + " ] "
                + "|| ! unzip -tq " + tmpJarExpr + " >/dev/null 2>&1; then "
                + "rm -f " + tmpJarExpr + "; echo ERR_STAGE_JAR; exit 21; fi; "
                + shaGuard
                + "chmod 644 " + tmpJarExpr + " || { rm -f " + tmpJarExpr + "; echo ERR_CHMOD_JAR; exit 21; }; "
                + "(CLASSPATH=" + tmpJarExpr + " /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge --stop >/dev/null 2>&1 || true); "
                + "if pgrep -f '^(/system/bin/)?app_process .*com\\.extbridge\\.daemon\\.ExtBridge' >/dev/null 2>&1; then "
                + "(pkill -f '^(/system/bin/)?app_process .*com\\.extbridge\\.daemon\\.ExtBridge' 2>/dev/null || true); "
                + "sleep 1.0; "
                + "(pkill -f -l KILL '^(/system/bin/)?app_process .*com\\.extbridge\\.daemon\\.ExtBridge' 2>/dev/null || true); fi; "
                + "rm -f /data/local/tmp/extbridge.jar; "
                + "if ! mv -f " + tmpJarExpr + " /data/local/tmp/extbridge.jar || [ -L /data/local/tmp/extbridge.jar ] || [ ! -s /data/local/tmp/extbridge.jar ]; then "
                + "rm -f " + tmpJarExpr + "; echo ERR_SWAP_JAR; exit 22; fi; "
                + postSwapShaGuard
                + "chown -h 2000:2000 /data/local/tmp/extbridge.jar 2>/dev/null; "
                + "LOG_DST=/dev/null; "
                + "rm -f /data/local/tmp/extbridge.log 2>/dev/null; "
                + "if (umask 022; set -C; : > /data/local/tmp/extbridge.log) 2>/dev/null && [ -f /data/local/tmp/extbridge.log ] && [ ! -L /data/local/tmp/extbridge.log ]; then "
                + "chown -h 2000:2000 /data/local/tmp/extbridge.log 2>/dev/null; LOG_DST=/data/local/tmp/extbridge.log; fi; "
                + "appops set com.extbridge.helper SYSTEM_ALERT_WINDOW allow 2>/dev/null; "
                + "appops set com.extbridge.helper POST_NOTIFICATION allow 2>/dev/null; "
                + "appops set com.extbridge.helper RUN_ANY_IN_BACKGROUND allow 2>/dev/null; "
                + "appops set com.extbridge.helper 10021 allow 2>/dev/null; "
                + "appops set com.extbridge.helper ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; "
                + "pm grant com.extbridge.helper android.permission.POST_NOTIFICATIONS 2>/dev/null; "
                + "dumpsys deviceidle whitelist +com.extbridge.helper >/dev/null 2>&1; "
                + "CUR_CTX=$(id -Z 2>/dev/null || echo ''); "
                + "case \"$CUR_CTX\" in "
                + "*:shell:*) "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"$LOG_DST\" 2>&1 &); sleep 0.5 ;; "
                + "*) "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar setsid runcon u:r:shell:s0 /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"$LOG_DST\" 2>&1 &); sleep 0.5; "
                + "if ! pgrep -f '^(/system/bin/)?app_process .*com\\.extbridge\\.daemon\\.ExtBridge' >/dev/null 2>&1; then "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar setsid /system/bin/app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"$LOG_DST\" 2>&1 &); sleep 0.5; fi ;; "
                + "esac; "
                + "if ! pgrep -f '^(/system/bin/)?app_process .*com\\.extbridge\\.daemon\\.ExtBridge' >/dev/null 2>&1; then "
                + "(CLASSPATH=/data/local/tmp/extbridge.jar nohup app_process /data/local/tmp com.extbridge.daemon.ExtBridge </dev/null >>\"$LOG_DST\" 2>&1 &); sleep 0.6; fi; "
                + "if pgrep -f '^(/system/bin/)?app_process .*com\\.extbridge\\.daemon\\.ExtBridge' >/dev/null 2>&1; then echo LAUNCH_DONE; else echo ERR_DAEMON_DIED; [ \"$LOG_DST\" != \"/dev/null\" ] && cat \"$LOG_DST\" 2>/dev/null; exit 24; fi";
    }

    /**
     * Full privileged bootstrap script: extracts {@code assets/extbridge.jar} straight out of the
     * installed APK (via {@code pm path} + {@code unzip -p}) and runs the verified swap + launch.
     */
    public static String buildDaemonBootstrapScript(String expectedSha256) {
        // Fail-closed BEFORE stopping any running daemon or touching /data/local/tmp/extbridge.jar.
        // Also sweep stale `extbridge.XXXXXX` mktemp leftovers from earlier interrupted launches
        // (a killed bootstrap shell never reaches its own cleanup `rm`).
        return "rm -f /data/local/tmp/extbridge.?????? 2>/dev/null; "
                + "TMP_JAR=$(mktemp /data/local/tmp/extbridge.XXXXXX 2>/dev/null || echo /data/local/tmp/extbridge.jar.tmp.$$); "
                + "if [ -L \"$TMP_JAR\" ]; then rm -f \"$TMP_JAR\"; echo ERR_TMP_SYMLINK; exit 21; fi; "
                + "if ! ( APK=$(pm path com.extbridge.helper | head -n1 | cut -d: -f2); "
                + "[ -n \"$APK\" ] && unzip -p \"$APK\" assets/extbridge.jar > \"$TMP_JAR\" ); then "
                + "rm -f \"$TMP_JAR\"; echo ERR_STAGE_JAR; exit 21; fi; "
                + buildShellSwapAndLaunchScript("\"$TMP_JAR\"", expectedSha256);
    }

    private static void awaitDaemonBinderReady(Context appCtx, boolean fromRoot, ProgressListener listener, String successMsg) {
        postStep(listener, "守护进程已拉起，正在验证 Binder 连接...");
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline) {
            BridgeClient.requestBinder(appCtx);
            if (BridgeClient.getStatus().connected) {
                postResult(listener, true, successMsg);
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        String tailDiag = "";
        if (fromRoot) {
            CmdRes diag = runSu(3000, "tail -n 8 /data/local/tmp/extbridge.log 2>/dev/null");
            if (diag.output != null && !diag.output.trim().isEmpty()) {
                tailDiag = "\n日志摘要:\n" + diag.output.trim();
            }
        }
        postResult(listener, false, "守护进程命令已下发，但暂未收到 Binder 握手，请稍等几秒或查看 /data/local/tmp/extbridge.log" + tailDiag);
    }

    private static CmdRes runSu(long timeoutMs, String shellCmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", shellCmd).redirectErrorStream(true).start();
            try {
                p.getOutputStream().close();
            } catch (Throwable ignored) {}
            final StringBuilder sb = new StringBuilder();
            final Process proc = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() < 8192) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(line);
                        }
                    }
                } catch (Throwable ignored) {}
            });
            reader.start();

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                reader.join(500);
                return new CmdRes(-1, "Root 命令执行超时 (" + timeoutMs + "ms)\n" + sb);
            }
            reader.join(1000);
            return new CmdRes(p.exitValue(), sb.toString());
        } catch (Throwable t) {
            if (p != null) try { p.destroyForcibly(); } catch (Throwable ignored) {}
            return new CmdRes(-1, "未找到 su 或无 Root 权限: " + t.getMessage());
        }
    }

    private static final class CmdRes {
        final int code;
        final String output;

        CmdRes(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    private static void postStep(final ProgressListener l, final String msg) {
        if (l != null) sMain.post(() -> l.onStep(msg));
    }

    private static void postResult(final ProgressListener l, final boolean ok, final String msg) {
        if (l != null) sMain.post(() -> l.onResult(ok, msg));
    }
}
