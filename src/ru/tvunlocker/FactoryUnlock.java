package ru.tvunlocker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Работа с заводским AIDL-сервисом CVTE через bind по ACTION
 * (компонентный bind YaOS блокирует, action-подключение подтверждено).
 * Дескриптор binder'а проверяется ДО транзакций; интерфейс загружается
 * из APK завода по имени дескриптора. Слепые транзакции запрещены.
 */
public class FactoryUnlock {

    private static final String FAC_PKG = "com.cvte.factory.service";
    private static final String IFACE = "com.cvte.factory.service.IFactoryService";
    private static final String[] BIND_ACTIONS = {
            "android.intent.action.CVTEFACSERVICE",
            "cvte.factory.intent.action.APIInit"
    };

    private static final String[] COMMANDS = {
            "settings put global adb_enabled 1",
            "settings put secure adb_enabled 1",
            "setprop ctl.start adbd",
            "setprop persist.service.adb.enable 1"
    };

    private static IBinder captured;
    private static CountDownLatch latch;
    private static ServiceConnection activeConn;

    private static void log(StringBuilder sb, String s) {
        sb.append(s).append("\n");
    }

    private static boolean tcpProbe(String host, int port) {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            s.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Класс-держатель соединения. */
    private static class Bound {
        IBinder binder;
        ServiceConnection conn;
        String via;
        Exception error;
    }

    /** bind по action на главном потоке. */
    private static Bound bindVia(Context ctx, String action, String pkg, long timeoutMs) {
        final Bound b = new Bound();
        b.via = action;
        final CountDownLatch l = new CountDownLatch(1);
        b.conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                b.binder = service;
                l.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        final Intent i = new Intent(action);
        i.setPackage(pkg);
        final boolean[] ok = new boolean[1];
        final Exception[] err = new Exception[1];
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    ok[0] = ctx.bindService(i, b.conn, Context.BIND_AUTO_CREATE);
                } catch (Exception e) {
                    err[0] = e;
                }
                if (!ok[0]) l.countDown();
            }
        });
        try {
            l.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (err[0] != null) {
            b.error = err[0];
        } else if (!ok[0]) {
            b.error = new Exception("bindService вернул false");
        }
        return b;
    }

    private static int getInt(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "adb_enabled", -1);
        } catch (Throwable t) {
            return -2;
        }
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = String.valueOf(c.getMessage());
        return c.getClass().getSimpleName() + ": " + (m.length() > 160 ? m.substring(0, 160) : m);
    }

    /**
     * Подключается к заводскому сервису и возвращает прокси его AIDL-интерфейса
     * (или null). Лог пишет в sb. Если вернулся не IFactoryService — загружает
     * интерфейс, соответствующий дескриптору binder'а.
     */
    public static Object connect(Context ctx, StringBuilder sb) {
        // 1. APK завода
        String src = sourceOf(ctx, FAC_PKG);
        String srcAt = sourceOf(ctx, "com.cvte.factory.at");
        log(sb, "APK factory.service: " + (src == null ? "НЕ НАЙДЕН" : src));
        log(sb, "APK factory.at:     " + (srcAt == null ? "НЕ НАЙДЕН" : srcAt));
        if (src == null && srcAt == null) {
            log(sb, "Заводские пакеты не найдены.");
            return null;
        }

        // 2. DexClassLoader + IFactoryService$Stub (нужен DESCRIPTOR)
        ClassLoader cl = null;
        Class<?> iface = null;
        Class<?> stub = null;
        String descriptor = null;
        for (String apk : new String[]{src, srcAt}) {
            if (apk == null) continue;
            try {
                File odex = new File(ctx.getCacheDir(), "fuildex");
                if (!odex.exists()) odex.mkdirs();
                DexClassLoader dcl = new DexClassLoader(apk, odex.getAbsolutePath(),
                        null, FactoryUnlock.class.getClassLoader());
                Class<?> i2 = dcl.loadClass(IFACE);
                Class<?> s2 = dcl.loadClass(IFACE + "$Stub");
                cl = dcl;
                iface = i2;
                stub = s2;
                try {
                    java.lang.reflect.Field f = s2.getDeclaredField("DESCRIPTOR");
                    f.setAccessible(true);
                    descriptor = (String) f.get(null);
                } catch (Throwable t) {
                    descriptor = IFACE;
                }
                log(sb, "IFactoryService загружен из " + apk + ", descriptor=" + descriptor);
                break;
            } catch (Throwable t) {
                log(sb, "DexClassLoader " + apk + ": " + rootMsg(t));
            }
        }
        if (stub == null) {
            log(sb, "Интерфейс не загрузился из APK.");
            return null;
        }

        // 3. bind по action + проверка дескриптора binder'а
        Bound bound = null;
        Class<?> boundIface = null;
        Class<?> boundStub = null;
        for (String action : BIND_ACTIONS) {
            try {
                log(sb, "bind(action " + action + ") …");
                Bound b = bindVia(ctx, action, FAC_PKG, 7000);
                if (b.error != null || b.binder == null) {
                    log(sb, "  отказ: " + (b.error == null ? "таймаут/нет соединения"
                            : rootMsg(b.error)));
                    continue;
                }
                String binderDesc = null;
                try {
                    binderDesc = b.binder.getInterfaceDescriptor();
                } catch (Throwable t) {
                    binderDesc = "(ошибка: " + rootMsg(t) + ")";
                }
                log(sb, "  СОЕДИНЕНИЕ ЕСТЬ, binder descriptor = " + binderDesc);
                if (descriptor != null && descriptor.equals(binderDesc)) {
                    bound = b;
                    boundIface = iface;
                    boundStub = stub;
                    log(sb, "  дескриптор совпал — это IFactoryService!");
                    break;
                }
                // Чужой интерфейс: загрузим его собственный Stub по дескриптору
                if (binderDesc != null && binderDesc.contains(".")) {
                    try {
                        Class<?> altStub = cl.loadClass(binderDesc + "$Stub");
                        Class<?> altIface = cl.loadClass(binderDesc);
                        log(sb, "  чужой интерфейс " + binderDesc
                                + " — загружен его Stub");
                        bound = b;
                        boundIface = altIface;
                        boundStub = altStub;
                        break;
                    } catch (Throwable t) {
                        log(sb, "  чужой интерфейс " + binderDesc
                                + " не загрузился: " + rootMsg(t));
                    }
                }
                try {
                    ctx.unbindService(b.conn);
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                log(sb, "  ошибка bind: " + rootMsg(t));
            }
        }
        if (bound == null || bound.binder == null) {
            log(sb, "Ни одно соединение не подошло.");
            return null;
        }

        // 4. asInterface
        try {
            Method asi = boundStub.getDeclaredMethod("asInterface", IBinder.class);
            Object svc = asi.invoke(null, bound.binder);
            log(sb, "asInterface OK: " + svc.getClass().getName());
            return svc;
        } catch (Throwable t) {
            try {
                ctx.unbindService(bound.conn);
            } catch (Throwable ignored) {
            }
            log(sb, "asInterface FAIL: " + rootMsg(t));
            return null;
        }
    }

    /** Выполняет разблокировку командами, возвращает протокол. */
    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 factory-unlock\n");
        sb.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n\n");

        int adbBefore = getInt(ctx);
        sb.append("adb_enabled ДО: ").append(adbBefore).append("\n");
        sb.append("порт 5555 (localhost) ДО: ")
          .append(tcpProbe("127.0.0.1", 5555) ? "ОТКРЫТ" : "закрыт").append("\n\n");

        Object svc = connect(ctx, sb);
        if (svc == null) {
            sb.append("\nПуть недоступен (см. выше).\n");
            return sb.toString();
        }

        // Методы, ищем shell-кандидаты
        Class<?> iface = svc.getClass().getInterfaces().length > 0
                ? svc.getClass().getInterfaces()[0] : svc.getClass();
        ArrayList<Method> shellMethods = new ArrayList<Method>();
        try {
            log(sb, "\nМетоды " + iface.getName() + ":");
            for (Method m : iface.getMethods()) {
                StringBuilder ps = new StringBuilder("(");
                for (Class<?> p : m.getParameterTypes()) {
                    ps.append(p.getSimpleName()).append(",");
                }
                if (ps.length() > 1) ps.setLength(ps.length() - 1);
                ps.append(")");
                log(sb, "  " + m.getName() + ps + " -> " + m.getReturnType().getSimpleName());
                String n = m.getName().toLowerCase();
                if ((n.contains("shell") || n.contains("cmd") || n.contains("command"))
                        && m.getParameterTypes().length >= 1) {
                    shellMethods.add(m);
                }
            }
        } catch (Throwable t) {
            log(sb, "ошибка перечисления: " + rootMsg(t));
        }
        log(sb, "shell-кандидаты: " + shellMethods.size());

        if (!shellMethods.isEmpty()) {
            sb.append("\n== Тест исполнения (id) ==\n");
            for (Method m : shellMethods) {
                try {
                    Object r = invokeWith(m, svc, "id");
                    log(sb, m.getName() + "(\"id\") -> "
                            + (r == null ? "(void/без вывода)" : truncate(String.valueOf(r), 200)));
                } catch (Throwable t) {
                    log(sb, m.getName() + "(\"id\") -> ERR " + rootMsg(t));
                }
            }

            sb.append("\n== Команды разблокировки ==\n");
            for (String cmd : COMMANDS) {
                for (Method m : shellMethods) {
                    try {
                        Object r = invokeWith(m, svc, cmd);
                        log(sb, cmd + "  [" + m.getName() + "] -> "
                                + (r == null ? "(void)" : truncate(String.valueOf(r), 200)));
                    } catch (Throwable t) {
                        log(sb, cmd + "  [" + m.getName() + "] -> ERR " + rootMsg(t));
                    }
                    if (getInt(ctx) == 1) break;
                }
                if (getInt(ctx) == 1) break;
            }
        } else {
            sb.append("\nShell-методов нет — используйте инжекцию клавиш (KeyActivity).\n");
        }

        // Итог
        int adbAfter = getInt(ctx);
        boolean portAfter = tcpProbe("127.0.0.1", 5555);
        sb.append("\nadb_enabled ПОСЛЕ: ").append(adbAfter).append("\n");
        sb.append("порт 5555 (localhost) ПОСЛЕ: ")
          .append(portAfter ? "ОТКРЫТ" : "закрыт").append("\n");
        if (adbAfter == 1 || portAfter) {
            sb.append("\n>>> РАЗБЛОКИРОВАНО! Подключение с ПК: adb connect <IP телевизора>:5555\n");
        } else {
            sb.append("\nADB не включён — смотри протокол: на каком шаге отказ.\n");
        }
        return sb.toString();
    }

    private static Object invokeWith(Method m, Object svc, String cmd) throws Exception {
        Class<?>[] ps = m.getParameterTypes();
        Object[] args = new Object[ps.length];
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] == String.class) args[i] = cmd;
            else if (ps[i] == int.class) args[i] = 0;
            else if (ps[i] == boolean.class) args[i] = Boolean.FALSE;
            else args[i] = null;
        }
        return m.invoke(svc, args);
    }

    private static String sourceOf(Context ctx, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.sourceDir;
        } catch (Throwable t) {
            return null;
        }
    }
}
