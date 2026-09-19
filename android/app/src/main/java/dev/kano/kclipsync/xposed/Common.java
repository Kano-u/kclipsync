package dev.kano.kclipsync.xposed;

import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/**
 * Hook body for system_server. The library-specific Entry class calls into here so this class can
 * remain independent of the Xposed API surface.
 */
final class Common {
    static final String TAG = "KClipSync";
    static final String PACKAGE = "dev.kano.kclipsync";
    static final String CLIPBOARD_SERVICE = "com.android.server.clipboard.ClipboardService";
    static final String SERVICE = PACKAGE + ".SyncService";
    static final String ACTION_CLIP = PACKAGE + ".CLIP";
    static final String ACTION_HOOK_STATUS = PACKAGE + ".HOOK_STATUS";
    static final String EXTRA_KEEPALIVE = "keepalive";
    static final String AUTOSTART = PACKAGE + ".BootReceiver";
    static final long WATCHDOG_AFTER_DEATH_MS = 3_000;
    static final long POLL_WITH_HOOK_MS = 60_000;
    static final long POLL_WITHOUT_HOOK_MS = 15_000;
    static volatile long pollMs = POLL_WITHOUT_HOOK_MS;

    private static volatile boolean systemKeepAlive;

    private static final android.os.HandlerThread WORKER =
            new android.os.HandlerThread("kclipsync-hook", android.os.Process.THREAD_PRIORITY_BACKGROUND);

    private Common() {}

    static android.os.Handler handler() {
        synchronized (WORKER) {
            if (!WORKER.isAlive()) WORKER.start();
        }
        return new android.os.Handler(WORKER.getLooper());
    }

    static android.content.Context systemContext() {
        try {
            Object thread = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            return thread == null ? null : (android.content.Context) thread.getClass()
                    .getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }

    static void markHooked() {
        Log.i(TAG, "system_server clipboard hooks installed");
        writeHookMarker();
        handler().post(() -> {
            systemKeepAlive = applySystemKeepAlive();
            notifyService(true, systemKeepAlive);
        });
    }

    /**
     * KernelSU only exposes su in the shell mount namespace on this device, so the app process
     * cannot run it. system_server has system UID and can apply the same power exemptions.
     */
    private static boolean applySystemKeepAlive() {
        String script = "dumpsys deviceidle whitelist +" + PACKAGE
                + "; cmd appops set " + PACKAGE + " RUN_IN_BACKGROUND allow"
                + "; cmd appops set " + PACKAGE + " RUN_ANY_IN_BACKGROUND allow"
                + "; cmd appops set " + PACKAGE + " START_FOREGROUND allow"
                + "; cmd appops set " + PACKAGE + " SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS allow"
                + "; am set-standby-bucket " + PACKAGE + " exempted"
                + " || am set-standby-bucket " + PACKAGE + " active";
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", script)
                    .redirectErrorStream(true).start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                }
            }
            boolean ok = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                    && process.exitValue() == 0;
            Log.i(TAG, "system_server keep-alive " + (ok ? "applied" : "failed"));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "system_server keep-alive unavailable: " + t);
            return false;
        }
    }

    private static void notifyService(boolean hooked, boolean keepalive) {
        try {
            android.content.Context context = systemContext();
            if (context == null) return;
            context.startService(new android.content.Intent(ACTION_HOOK_STATUS)
                    .setClassName(PACKAGE, SERVICE)
                    .putExtra(EXTRA_KEEPALIVE, hooked && keepalive));
        } catch (Throwable ignored) {
        }
    }

    /** Best-effort marker in app storage; system_server may be unable to create it. */
    private static void writeHookMarker() {
        try {
            java.io.File marker = new java.io.File("/data/data/" + PACKAGE + "/files/xposed_hook");
            try (FileOutputStream out = new FileOutputStream(marker, false)) {
                out.write((System.currentTimeMillis() + " " + android.os.Process.myPid() + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    static Method clipSetter(Class<?> service) {
        Method fallback = null;
        for (Method method : service.getDeclaredMethods()) {
            if (method.getName().equals("setPrimaryClipInternalLocked")) return method;
            if (method.getName().startsWith("setPrimaryClipInternal") && fallback == null) {
                fallback = method;
            }
        }
        return fallback;
    }

    static void onClipSet(Object[] args) {
        try {
            android.content.ClipData clip = null;
            boolean ours = false;
            for (Object arg : args) {
                if (arg instanceof android.content.ClipData) clip = (android.content.ClipData) arg;
                else if (PACKAGE.equals(arg)) ours = true;
            }
            if (clip != null && !ours) pushClip(clip);
        } catch (Throwable ignored) {
        }
    }

    private static void pushClip(android.content.ClipData clip) {
        handler().post(() -> {
            android.content.Context context = systemContext();
            if (context == null) return;
            android.content.Intent intent = new android.content.Intent(ACTION_CLIP)
                    .setClassName(PACKAGE, SERVICE)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(clip);
            try {
                context.startService(intent);
            } catch (Throwable t) {
                // A near-limit ClipData can exceed the Binder transaction size. Send a bare
                // trigger in that case; SyncService re-reads the clipboard locally instead.
                try {
                    context.startService(new android.content.Intent(ACTION_CLIP)
                            .setClassName(PACKAGE, SERVICE)
                            .putExtra("fetch", true));
                } catch (Throwable fallbackFailure) {
                    Log.w(TAG, "clipboard push failed: " + fallbackFailure);
                }
            }
        });
    }

    private static boolean isOurs(Object processRecord) {
        if (processRecord == null) return false;
        try {
            Object name = field(processRecord, "processName");
            if (name instanceof String && ((String) name).startsWith(PACKAGE)) return true;
            Object info = field(processRecord, "info");
            return info != null && PACKAGE.equals(field(info, "packageName"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile int ourUid = -1;
    private static volatile long uidLookupAt;

    static boolean isOurUid(Object uidArg, Object pidArg) {
        int uid = uidArg instanceof Integer ? (Integer) uidArg : -1;
        long now = android.os.SystemClock.elapsedRealtime();
        if (ourUid < 0 || now - uidLookupAt > 600_000L) {
            uidLookupAt = now;
            try {
                android.content.Context context = systemContext();
                ourUid = context == null ? -1 : context.getPackageManager().getPackageUid(PACKAGE, 0);
            } catch (Throwable t) {
                ourUid = -1;
            }
        }
        if (ourUid >= 0) return uid == ourUid;
        return pidArg instanceof Integer && isOurPid((Integer) pidArg);
    }

    private static boolean isOurPid(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] bytes = new byte[256];
            int count = in.read(bytes);
            if (count <= 0) return false;
            int end = 0;
            while (end < count && bytes[end] != 0) end++;
            return new String(bytes, 0, end).startsWith(PACKAGE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    static void exempt(Object processRecord) {
        try {
            Object opt = field(processRecord, "mOptRecord");
            if (opt == null) return;
            for (String setter : new String[]{"setShouldNotFreeze", "setFreezeExempt"}) {
                try {
                    Method method = opt.getClass().getDeclaredMethod(setter, boolean.class);
                    method.setAccessible(true);
                    method.invoke(opt, true);
                    return;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean isOursForWatchdog(Object processRecord) {
        return isOurs(processRecord);
    }

    private static boolean autoStartWanted(android.content.Context context) {
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(
                    new android.content.ComponentName(PACKAGE, AUTOSTART));
            return state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean serviceRunning(android.content.Context context) {
        android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
        if (manager == null) return true;
        for (android.app.ActivityManager.RunningServiceInfo info :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            if (SERVICE.equals(info.service.getClassName())) return true;
        }
        return false;
    }

    private static void unstop(android.content.Context context) {
        try {
            Object manager = Class.forName("android.app.AppGlobals")
                    .getMethod("getPackageManager").invoke(null);
            int user = android.os.Process.myUserHandle().hashCode();
            manager.getClass().getMethod("setPackageStoppedState", String.class, boolean.class, int.class)
                    .invoke(manager, PACKAGE, false, user);
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            try {
                android.content.Context context = systemContext();
                if (context != null) {
                    android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
                    boolean unlocked = users == null || users.isUserUnlocked();
                    if (unlocked && autoStartWanted(context) && !serviceRunning(context)) {
                        unstop(context);
                        context.startForegroundService(new android.content.Intent()
                                .setClassName(PACKAGE, SERVICE));
                        Log.i(TAG, "watchdog started SyncService");
                    }
                    notifyService(true, systemKeepAlive);
                }
            } catch (Throwable t) {
                Log.w(TAG, "watchdog: " + t);
            }
            handler().postDelayed(this, pollMs);
        }
    };

    static void scheduleCheck(long delayMs) {
        android.os.Handler handler = handler();
        handler.removeCallbacks(CHECK);
        handler.postDelayed(CHECK, delayMs);
    }
}
