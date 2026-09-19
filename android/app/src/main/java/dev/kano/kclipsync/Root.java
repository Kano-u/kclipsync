package dev.kano.kclipsync;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

/**
 * Root-side keep-alive commands. On Android 15 an app process usually cannot see /system/bin/su
 * even though the adb shell can, because KernelSU only mounts it into the shell mount namespace.
 * We try every known location and log a precise reason when none is usable; the system_server
 * Xposed hook remains the primary defence against freezing.
 */
public final class Root {
    private Root() {}

    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "su",
    };

    private static final String[] COMMANDS = {
            "dumpsys deviceidle whitelist +%p",
            "cmd appops set %p RUN_IN_BACKGROUND allow",
            "cmd appops set %p RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set %p START_FOREGROUND allow",
            "cmd appops set %p SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS allow",
            "am set-standby-bucket %p exempted || am set-standby-bucket %p active",
    };

    public static boolean keepAlive(String packageName) {
        String script = buildScript(packageName);
        StringBuilder errors = new StringBuilder();
        for (String su : SU_PATHS) {
            try {
                ProcessBuilder builder = new ProcessBuilder(su);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                if (run(process, script)) {
                    LogStore.info("root keep-alive applied via " + su);
                    return true;
                }
            } catch (Exception e) {
                errors.append(su).append(": ").append(e.getMessage()).append("; ");
            }
        }
        LogStore.warn("root keep-alive unavailable: " + errors);
        return false;
    }

    private static String buildScript(String packageName) {
        StringBuilder script = new StringBuilder();
        script.append("export PATH=/system/bin:/system/xbin:/product/bin:/vendor/bin:$PATH\n");
        for (String command : COMMANDS) {
            script.append(command.replace("%p", packageName)).append(" 2>&1\n");
        }
        script.append("echo __done__\n");
        return script.toString();
    }

    /** Writes the script to su's stdin and confirms it finished with exit status 0. */
    private static boolean run(Process process, String script) throws Exception {
        try (Writer writer = new OutputStreamWriter(process.getOutputStream())) {
            writer.write(script);
            writer.write("exit\n");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("__done__".equals(line)) break;
            }
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy();
            return false;
        }
        return process.exitValue() == 0;
    }
}
