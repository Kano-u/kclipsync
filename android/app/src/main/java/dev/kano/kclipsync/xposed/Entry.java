package dev.kano.kclipsync.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** libxposed API 102 entry for system_server. */
public class Entry extends XposedModule {
    private final AtomicBoolean installed = new AtomicBoolean(false);

    private void logBoth(int level, String message, Throwable throwable) {
        if (throwable != null) {
            Log.println(level, Common.TAG, message + "\n" + Log.getStackTraceString(throwable));
            log(Log.INFO, Common.TAG, message, throwable);
        } else {
            Log.println(level, Common.TAG, message);
            log(Log.INFO, Common.TAG, message);
        }
    }

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        logBoth(Log.INFO, "module loaded in " + param.getProcessName(), null);
    }

    @Override
    public void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam param) {
        install(param.getClassLoader());
    }

    private static boolean stringAt(Method method, int index) {
        return method.getParameterCount() > index && method.getParameterTypes()[index] == String.class;
    }

    private void install(ClassLoader classLoader) {
        if (!installed.compareAndSet(false, true)) return;
        List<String> hooked = new ArrayList<>();

        try {
            Class<?> service = classLoader.loadClass(Common.CLIPBOARD_SERVICE);
            for (Method method : service.getDeclaredMethods()) {
                switch (method.getName()) {
                    case "clipboardAccessAllowed" -> {
                        if (!stringAt(method, 1)) continue;
                        hook(method).setId("access").setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> Common.PACKAGE.equals(chain.getArg(1))
                                        ? Boolean.TRUE : chain.proceed());
                        hooked.add("clipboardAccessAllowed");
                    }
                    case "showAccessNotificationLocked" -> {
                        if (!stringAt(method, 0)) continue;
                        Object skip = method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                        hook(method).setId("toast").setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> Common.PACKAGE.equals(chain.getArg(0))
                                        ? skip : chain.proceed());
                        hooked.add("showAccessNotificationLocked");
                    }
                    default -> {
                    }
                }
            }
            Method setter = Common.clipSetter(service);
            if (setter != null) {
                int count = setter.getParameterCount();
                hook(setter).setId("push").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object[] args = new Object[count];
                            for (int i = 0; i < count; i++) args[i] = chain.getArg(i);
                            Common.onClipSet(args);
                            return result;
                        });
                hooked.add(setter.getName() + " -> push");
            }
        } catch (Throwable t) {
            logBoth(Log.ERROR, "clipboard hook failed", t);
        }

        try {
            Class<?> processRecord = classLoader.loadClass("com.android.server.am.ProcessRecord");
            for (Constructor<?> constructor : processRecord.getDeclaredConstructors()) {
                hook(constructor).setId("pr").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object record = chain.getThisObject();
                            if (Common.isOursForWatchdog(record)) Common.exempt(record);
                            return result;
                        });
            }
            hooked.add("ProcessRecord.<init> -> freeze exemption");
        } catch (Throwable t) {
            logBoth(Log.WARN, "ProcessRecord hook failed: " + t, null);
        }

        try {
            for (Method method : android.os.Process.class.getDeclaredMethods()) {
                if (!method.getName().equals("setProcessFrozen")
                        || !Modifier.isStatic(method.getModifiers())) continue;
                hook(method).setId("freeze").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> Boolean.TRUE.equals(chain.getArg(2))
                                && Common.isOurUid(chain.getArg(1), chain.getArg(0))
                                ? null : chain.proceed());
                hooked.add("Process.setProcessFrozen");
            }
        } catch (Throwable t) {
            logBoth(Log.WARN, "Process.setProcessFrozen hook failed: " + t, null);
        }

        int deathHooks = 0;
        try {
            Class<?> ams = classLoader.loadClass("com.android.server.am.ActivityManagerService");
            for (Method method : ams.getDeclaredMethods()) {
                String name = method.getName();
                if (!(name.equals("handleAppDiedLocked") || name.equals("appDiedLocked"))
                        || method.getParameterCount() < 1
                        || !method.getParameterTypes()[0].getSimpleName().equals("ProcessRecord")) {
                    continue;
                }
                hook(method).setId("died").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (Common.isOursForWatchdog(chain.getArg(0))) {
                                Common.scheduleCheck(Common.WATCHDOG_AFTER_DEATH_MS);
                            }
                            return result;
                        });
                deathHooks++;
            }
        } catch (Throwable t) {
            logBoth(Log.WARN, "service watchdog hook failed: " + t, null);
        }
        Common.pollMs = deathHooks > 0 ? Common.POLL_WITH_HOOK_MS : Common.POLL_WITHOUT_HOOK_MS;
        Common.scheduleCheck(Common.pollMs);
        hooked.add("watchdog (death hooks " + deathHooks + ")");

        logBoth(Log.INFO, "hooked: " + hooked, null);
        Common.markHooked();
    }
}
