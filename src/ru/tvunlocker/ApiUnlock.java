package ru.tvunlocker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import dalvik.system.DexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * v3.5: бинд по ACTION к сервисам CVTE TV API (com.cvte.tv.api.impl /
 * cvte-api-service.apk) — компонентные bind YaOS блокирует, action-подключение
 * подтверждено. TvApiService — вендорский API управления ТВ (у MTK API есть
 * setAdbStatus). После подключения: дескриптор → загрузка ИМЕННО этого
 * интерфейса из APK → полный список методов в лог → вызов только
 * adb/setting/shell-кандидатов. Слепые транзакции запрещены.
 */
public class ApiUnlock {

    private static final String API_PKG = "com.cvte.tv.api.impl";
    private static final String API_APK_FALLBACK = "com.cvte.factory.service";

    /** Поиск байтовой последовательности в массиве. */
    private static int indexOf(byte[] data, byte[] needle) {
        if (needle.length == 0 || needle.length > data.length) return -1;
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Поиск needle внутри zip-контейнера (apk/jar): каждый entry распаковывается. */
    private static boolean zipContains(String path, byte[] needle) throws Exception {
        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new FileInputStream(path));
        try {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName().toLowerCase();
                if (!(n.endsWith(".dex") || n.equals("resources.arsc") || n.endsWith(".bin"))) {
                    continue;
                }
                long cap = 150L * 1024 * 1024;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n2;
                long total = 0;
                while ((n2 = zis.read(buf)) > 0) {
                    bo.write(buf, 0, n2);
                    total += n2;
                    if (total > cap) break;
                }
                if (indexOf(bo.toByteArray(), needle) >= 0) {
                    return true;
                }
            }
        } finally {
            try {
                zis.close();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Сырой чанковый поиск по файлу (для odex/oat). */
    private static boolean rawContains(String path, byte[] needle) throws Exception {
        FileInputStream fin = new FileInputStream(path);
        try {
            long len = new File(path).length();
            byte[] prev = new byte[0];
            long done = 0;
            int CH = 4 * 1024 * 1024;
            while (done < len) {
                int want = (int) Math.min(CH, len - done);
                byte[] buf = new byte[want];
                int rd = 0;
                while (rd < want) {
                    int n = fin.read(buf, rd, want - rd);
                    if (n < 0) break;
                    rd += n;
                }
                byte[] window;
                if (prev.length > 0) {
                    window = new byte[prev.length + rd];
                    System.arraycopy(prev, 0, window, 0, prev.length);
                    System.arraycopy(buf, 0, window, prev.length, rd);
                } else {
                    window = buf;
                }
                if (indexOf(window, needle) >= 0) return true;
                int keep = Math.min(needle.length - 1, rd);
                prev = new byte[keep];
                System.arraycopy(buf, rd - keep, prev, 0, keep);
                done += rd;
                if (rd < want) break;
            }
        } finally {
            try {
                fin.close();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static final String[][] BIND_TARGETS = {
            {API_PKG, "com.cvte.tv.api.TV_API_SERVICE", "action"},
            {API_PKG, "android.intent.action.HOTKEYSERVICE", "action"},
            {API_PKG, "com.cvte.tv.api.HotkeyService", "action"},
            {API_PKG, "com.cvte.tv.api.TvApiService", "component"},
            {API_PKG, "com.cvte.tv.api.HotkeyService", "component"}
    };

    private static IBinder captured;
    private static CountDownLatch latch;

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

    private static class Bound {
        IBinder binder;
        ServiceConnection conn;
        Exception error;
        String target;
    }

    private static Bound doBind(Context ctx, String pkg, String target, String mode,
                                long timeoutMs) {
        final Bound b = new Bound();
        b.target = target;
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
        final Intent i = new Intent();
        if ("action".equals(mode)) i.setAction(target);
        else i.setClassName(pkg, target);
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
        if (err[0] != null) b.error = err[0];
        else if (!ok[0]) b.error = new Exception("bindService=false");
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

    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 TV-API unlock\n");
        sb.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n\n");
        sb.append("adb_enabled ДО: ").append(getInt(ctx)).append("\n\n");

        // APK, из которого грузим интерфейсы
        String src = null;
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(API_PKG, 0);
            src = ai.sourceDir;
        } catch (Throwable t) {
        }
        String srcFac = null;
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(API_APK_FALLBACK, 0);
            srcFac = ai.sourceDir;
        } catch (Throwable t) {
        }
        log(sb, "APK api.impl:      " + (src == null ? "НЕ НАЙДЕН" : src));
        log(sb, "APK factory.service: " + (srcFac == null ? "НЕ НАЙДЕН" : srcFac));

        // 1. Батарея bind'ов
        Bound good = null;
        ClassLoader goodCl = null;
        String goodDesc = null;
        for (String[] tgt : BIND_TARGETS) {
            try {
                log(sb, "\nbind[" + tgt[2] + "] " + tgt[1] + " …");
                Bound b = doBind(ctx, tgt[0], tgt[1], tgt[2], 6000);
                if (b.error != null || b.binder == null) {
                    log(sb, "  отказ: " + (b.error == null ? "таймаут" : rootMsg(b.error)));
                    continue;
                }
                String desc = null;
                try {
                    desc = b.binder.getInterfaceDescriptor();
                } catch (Throwable t2) {
                    desc = "(ошибка: " + rootMsg(t2) + ")";
                }
                log(sb, "  СОЕДИНЕНИЕ ЕСТЬ, descriptor=" + desc);
                good = b;
                goodDesc = desc;
                // выходим на первом соединении — интерфейс разберём по дескриптору
                break;
            } catch (Throwable t) {
                log(sb, "  ошибка: " + rootMsg(t));
            }
        }
        if (good == null || good.binder == null) {
            log(sb, "\nНи одного соединения — путь недоступен.");
            return sb.toString();
        }

        // 2. Поиск APK/библиотеки, содержащей класс интерфейса (байт-поиск),
        //    затем загрузка через DexClassLoader
        Object svc = null;
        Class<?> iface = null;
        try {
            ClassLoader cl = null;
            if (goodDesc != null && goodDesc.contains(".")) {
                byte[] needle = goodDesc.getBytes("UTF-8");
                ArrayList<String> containers = new ArrayList<String>();
                // а) /system/framework — jars и odex (там общие AIDL-библиотеки)
                File fwDir = new File("/system/framework");
                File[] items = fwDir.listFiles();
                if (items != null) {
                    for (File j : items) {
                        String n = j.getName().toLowerCase();
                        if (n.endsWith(".jar") || n.endsWith(".odex")) {
                            containers.add(j.getAbsolutePath());
                        }
                    }
                    File oat = new File(fwDir, "oat/arm64");
                    File[] odexs = oat.listFiles();
                    if (odexs != null) {
                        for (File j : odexs) {
                            if (j.getName().endsWith(".odex")) {
                                containers.add(j.getAbsolutePath());
                            }
                        }
                    }
                }
                // б) все установленные пакеты, путь/имя намекает на вендора
                for (PackageInfo pi : ctx.getPackageManager().getInstalledPackages(0)) {
                    if (pi == null || pi.applicationInfo == null) continue;
                    String s2 = pi.applicationInfo.sourceDir;
                    if (s2 == null) continue;
                    String l = s2.toLowerCase();
                    if (l.contains("cvte") || l.contains("tv") || l.contains("mediatek")
                            || l.contains("api") || l.contains("yandex")) {
                        containers.add(s2);
                    }
                }
                log(sb, "кандидатов-контейнеров: " + containers.size());
                long budget = 1200L * 1024 * 1024; // 1.2 ГБ суммарного чтения
                int CH = 4 * 1024 * 1024; // чанк 4 МБ
                for (String apk : containers) {
                    if (budget <= 0) {
                        log(sb, "бюджет чтения исчерпан");
                        break;
                    }
                    try {
                        File f = new File(apk);
                        long len = f.length();
                        if (len <= 0 || len > 300L * 1024 * 1024) continue;
                        budget -= len;
                        boolean has;
                        if (apk.toLowerCase().endsWith(".odex")) {
                            has = rawContains(apk, needle);
                        } else {
                            has = zipContains(apk, needle);
                        }
                        if (!has) continue;
                        log(sb, "НАЙДЕН интерфейс в: " + apk);
                        File odex = new File(ctx.getCacheDir(), "apidex");
                        if (!odex.exists()) odex.mkdirs();
                        DexClassLoader dcl = new DexClassLoader(apk,
                                odex.getAbsolutePath(), null,
                                ApiUnlock.class.getClassLoader());
                        Class<?> stub = dcl.loadClass(goodDesc + "$Stub");
                        iface = dcl.loadClass(goodDesc);
                        Method asi = stub.getDeclaredMethod("asInterface", IBinder.class);
                        svc = asi.invoke(null, good.binder);
                        cl = dcl;
                        log(sb, "Интерфейс " + goodDesc + " загружен из " + apk);
                        break;
                    } catch (Throwable t) {
                        log(sb, "контейнер " + apk + ": " + rootMsg(t));
                    }
                }
            }
            if (svc == null) {
                log(sb, "\nИнтерфейс по дескриптору не загрузился — путь недоступен.");
                try {
                    ctx.unbindService(good.conn);
                } catch (Throwable ignored) {
                }
                return sb.toString();
            }
        } catch (Throwable t) {
            log(sb, "ошибка: " + rootMsg(t));
            return sb.toString();
        }

        // 3. Полный список методов
        ArrayList<Method> cands = new ArrayList<Method>();
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
                if (m.getParameterTypes().length >= 1) {
                    if (n.contains("adb") || n.contains("shell") || n.contains("cmd")
                            || n.contains("prop") || n.contains("setting")
                            || n.contains("debug") || n.contains("exec")
                            || n.contains("env")) {
                        cands.add(m);
                    }
                }
            }
        } catch (Throwable t) {
            log(sb, "ошибка перечисления: " + rootMsg(t));
        }
        log(sb, "кандидатов: " + cands.size());

        // 4. Вызов adb/setting/shell-кандидатов
        sb.append("\n== Вызовы кандидатов ==\n");
        for (Method m : cands) {
            Class<?>[] ps = m.getParameterTypes();
            // тестовые вызовы: строки для String-параметров
            Object[] args = new Object[ps.length];
            boolean okArgs = true;
            for (int i = 0; i < ps.length; i++) {
                if (ps[i] == String.class) args[i] = (i == 0) ? "adb_enabled" : "1";
                else if (ps[i] == boolean.class) args[i] = Boolean.TRUE;
                else if (ps[i] == int.class) args[i] = 1;
                else if (ps[i] == long.class) args[i] = 1L;
                else if (ps[i] == float.class) args[i] = 1f;
                else if (ps[i] == double.class) args[i] = 1d;
                else if (ps[i] == byte[].class) args[i] = new byte[0];
                else { okArgs = false; break; }
            }
            if (!okArgs) continue;
            try {
                Object r = m.invoke(svc, args);
                log(sb, m.getName() + " -> "
                        + (r == null ? "(null/void)" : truncate(String.valueOf(r), 200)));
            } catch (Throwable t) {
                log(sb, m.getName() + " -> ERR " + rootMsg(t));
            }
            if (getInt(ctx) == 1) break;
        }

        // вторая волна: если есть set*-методы с (String,String) — пробуем пары
        sb.append("\n== Пары (key,value) для set*-методов ==\n");
        for (Method m : cands) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 2 && ps[0] == String.class && ps[1] == String.class
                    && m.getName().toLowerCase().startsWith("set")) {
                String[][] pairs = {
                        {"adb_enabled", "1"},
                        {"persist.service.adb.enable", "1"},
                        {"persist.adb.tcp.port", "5555"}
                };
                for (String[] pr : pairs) {
                    try {
                        Object r = m.invoke(svc, (Object) pr[0], (Object) pr[1]);
                        log(sb, m.getName() + "(" + pr[0] + "," + pr[1] + ") -> "
                                + (r == null ? "(null)" : truncate(String.valueOf(r), 100)));
                    } catch (Throwable t) {
                        log(sb, m.getName() + "(" + pr[0] + "," + pr[1]
                                + ") ERR " + rootMsg(t));
                    }
                    if (getInt(ctx) == 1) break;
                }
            }
            if (getInt(ctx) == 1) break;
        }

        try {
            ctx.unbindService(good.conn);
        } catch (Throwable ignored) {
        }

        int after = getInt(ctx);
        sb.append("\nadb_enabled ПОСЛЕ: ").append(after).append("\n");
        sb.append("порт 5555 (localhost): ")
          .append(tcpProbe("127.0.0.1", 5555) ? "ОТКРЫТ" : "закрыт").append("\n");
        if (after == 1) {
            sb.append("\n>>> РАЗБЛОКИРОВАНО! Выполните на компьютере: adb connect <IP>:5555\n");
        } else {
            sb.append("\nADB не включён — протокол выше покажет весь API-сервис\n");
            sb.append("(список методов пришлёт на ПК — по нему соберём точный вызов).\n");
        }
        return sb.toString();
    }
}
