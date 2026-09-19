package dev.kano.kclipsync;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

/** Root-side keep-alive commands, mirroring the reference app's proven set. */
public final class Root {
    private Root() {}

    private static final String[] COMMANDS = {
            "dumpsys deviceidle whitelist +%p",
            "cmd appops set %p RUN_IN_BACKGROUND allow",
            "cmd appops set %p RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set %p START_FOREGROUND allow",
            "cmd appops set %p SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS allow",
            "am set-standby-bucket %p exempted || am set-standby-bucket %p active",
    };

    public static boolean keepAlive(String packageName) {
        StringBuilder script = new StringBuilder();
        for (String command : COMMANDS) {
            script.append(command.replace("%p", packageName)).append(" 2>&1\n");
        }
        script.append("echo __done__\n");
        try {
            // The app process may not inherit the shell's PATH on Android 15; KSU/Magisk's
            // binary is at /system/bin/su. Prefer the absolute path and fall back to PATH.
            ProcessBuilder builder = new ProcessBuilder("/system/bin/su");
            builder.redirectErrorStream(true);
            Process process;
            try {
                process = builder.start();
            } catch (Exception e) {
                process = Runtime.getRuntime().exec("su");
            }
            try (Writer writer = new OutputStreamWriter(process.getOutputStream())) {
                writer.write(script.toString());
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
                LogStore.warn("root keep-alive timed out");
                return false;
            }
            if (process.exitValue() != 0) {
                LogStore.warn("root keep-alive denied (exit " + process.exitValue() + ")");
                return false;
            }
            LogStore.info("root keep-alive applied");
            return true;
        } catch (Exception e) {
            LogStore.warn("root keep-alive unavailable: " + e.getMessage());
            return false;
        }
    }
}
