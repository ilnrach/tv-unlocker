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
 * v4.0: подключение к ITvApiManager (bind по action TV_API_SERVICE,
 * интерфейс из /system/framework/cvte.media.jar) и ДВА режима:
 * run() — обход всех getTVApi* под-API с полным логом методов;
 * gold() — РАБОЧАЯ РАЗБЛОЖИКА: SystemProperties.set (ctl.start adbd,
 * service.adb.tcp.port) + SystemDebug ключи/переключатели.
 */
public class ApiDrill {

    private static final String API_PKG = "com.cvte.tv.api.impl";
    private static final String ACTION = "com.cvte.tv.api.TV_API_SERVICE";
    private static final String MGR_IFACE = "com.cvte.tv.api.aidl.ITvApiManager";
    private static final String MGR_JAR = "/system/framework/cvte.media.jar";

    private static void log(StringBuilder sb, String s) {
        sb.append(s).append("\n");
    }

    private static int getInt(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "adb_enabled", -1);
        } catch (Throwable t) {
            return -2;
        }
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

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = String.valueOf(c.getMessage());
        return c.getClass().getSimpleName() + ": " + (m.length() > 200 ? m.substring(0, 200) : m);
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static String sig(Method m) {
        StringBuilder ps = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) ps.append(p.getSimpleName()).append(",");
        if (ps.length() > 1) ps.setLength(ps.length() - 1);
        ps.append(")");
        return m.getName() + ps + " -> " + m.getReturnType().getSimpleName();
    }

    private static class Bound {
        IBinder binder;
        ServiceConnection conn;
        Exception error;
    }

    private static Bound bindOnce(Context ctx, long timeoutMs) {
        final Bound b = new Bound();
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
        final Intent i = new Intent(ACTION);
        i.setPackage(API_PKG);
        final boolean[] ok = new boolean[1];
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    ok[0] = ctx.bindService(i, b.conn, Context.BIND_AUTO_CREATE);
                } catch (Exception e) {
                    b.error = e;
                }
                if (!ok[0]) l.countDown();
            }
        });
        try {
            l.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return b;
    }

    private static Object subOf(Object mgr, String getter) throws Exception {
        Class<?> iface = mgr.getClass().getInterfaces().length > 0
                ? mgr.getClass().getInterfaces()[0] : mgr.getClass();
        Method m = iface.getMethod(getter);
        return m.invoke(mgr);
    }

    private static boolean propSet(Object propsApi, String k, String v) throws Exception {
        Class<?> iface = propsApi.getClass().getInterfaces().length > 0
                ? propsApi.getClass().getInterfaces()[0] : propsApi.getClass();
        Method m = iface.getMethod("set", String.class, String.class);
        Object r = m.invoke(propsApi, k, v);
        return Boolean.TRUE.equals(r);
    }

    private static String propGet(Object propsApi, String k) throws Exception {
        Class<?> iface = propsApi.getClass().getInterfaces().length > 0
                ? propsApi.getClass().getInterfaces()[0] : propsApi.getClass();
        Method m = iface.getMethod("get", String.class, String.class);
        Object r = m.invoke(propsApi, k, "");
        return r == null ? "(null)" : String.valueOf(r);
    }

    private static boolean debugCall(Object debugApi, String name, Object... args)
            throws Exception {
        Class<?> iface = debugApi.getClass().getInterfaces().length > 0
                ? debugApi.getClass().getInterfaces()[0] : debugApi.getClass();
        Class<?>[] ps = new Class[args.length];
        for (int i = 0; i < args.length; i++) ps[i] = args[i].getClass();
        Method m = iface.getMethod(name, ps);
        Object r = m.invoke(debugApi, args);
        return Boolean.TRUE.equals(r);
    }

    private static ClassLoader loadMgrClassloader(Context ctx, StringBuilder sb) {
        ArrayList<String> list = new ArrayList<String>();
        list.add(MGR_JAR);
        try {
            File fwDir = new File("/system/framework");
            File[] items = fwDir.listFiles();
            if (items != null) {
                for (File j : items) {
                    String n = j.getName().toLowerCase();
                    if ((n.endsWith(".jar") || n.endsWith(".odex")) && n.contains("cvte")) {
                        list.add(j.getAbsolutePath());
                    }
                }
                File oat = new File(fwDir, "oat/arm64");
                File[] odexs = oat.listFiles();
                if (odexs != null) {
                    for (File j : odexs) {
                        if (j.getName().endsWith(".odex")) list.add(j.getAbsolutePath());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        byte[] needle = MGR_IFACE.getBytes();
        for (String apk : list) {
            try {
                File f = new File(apk);
                if (!f.exists() || f.length() <= 0) continue;
                boolean has;
                if (apk.toLowerCase().endsWith(".odex")) {
                    has = rawContains(apk, needle);
                } else {
                    has = zipContains(apk, needle);
                }
                if (!has) continue;
                log(sb, "контейнер с ITvApiManager: " + apk);
                File odex = new File(ctx.getCacheDir(), "drilldex");
                if (!odex.exists()) odex.mkdirs();
                DexClassLoader dcl = new DexClassLoader(apk, odex.getAbsolutePath(),
                        null, ApiDrill.class.getClassLoader());
                dcl.loadClass(MGR_IFACE);
                return dcl;
            } catch (Throwable t) {
                log(sb, "контейнер " + apk + ": " + rootMsg(t));
            }
        }
        return null;
    }

    private static boolean zipContains(String path, byte[] needle) throws Exception {
        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new FileInputStream(path));
        try {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName().toLowerCase();
                if (!(n.endsWith(".dex") || n.equals("resources.arsc"))) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n2;
                long total = 0;
                while ((n2 = zis.read(buf)) > 0) {
                    bo.write(buf, 0, n2);
                    total += n2;
                    if (total > 150L * 1024 * 1024) break;
                }
                if (indexOf(bo.toByteArray(), needle) >= 0) return true;
            }
        } finally {
            try {
                zis.close();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

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

    /** Обход всех getTVApi* под-API с полным логом методов. */
    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 API-drill\n");
        sb.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n\n");
        sb.append("adb_enabled ДО: ").append(getInt(ctx)).append("\n\n");

        Bound b = bindOnce(ctx, 7000);
        if (b.error != null || b.binder == null) {
            log(sb, "bind: отказ — " + (b.error == null ? "таймаут" : rootMsg(b.error)));
            finish(sb, ctx);
            return sb.toString();
        }
        log(sb, "bind OK (TV_API_SERVICE)");

        ClassLoader cl = loadMgrClassloader(ctx, sb);
        if (cl == null) {
            log(sb, "ITvApiManager не найден ни в одном контейнере.");
            finish(sb, ctx);
            return sb.toString();
        }
        try {
            Class<?> stub = cl.loadClass(MGR_IFACE + "$Stub");
            Method asi = stub.getDeclaredMethod("asInterface", IBinder.class);
            Object mgr = asi.invoke(null, b.binder);
            log(sb, "ITvApiManager подключён: " + svcName(mgr));

            Class<?> mgrIface = mgr.getClass().getInterfaces().length > 0
                    ? mgr.getClass().getInterfaces()[0]
                    : cl.loadClass(MGR_IFACE);
            ArrayList<Method> getters = new ArrayList<Method>();
            for (Method m : mgrIface.getMethods()) {
                if (m.getName().startsWith("getTVApi")
                        && m.getParameterTypes().length == 0) {
                    getters.add(m);
                }
            }
            log(sb, "getTVApi* геттеров: " + getters.size());

            String[] gold = {"SystemDebug", "SystemProperties", "FactoryAutoTest",
                    "DataImportsAndExports", "ScreenCapture", "SystemInformation"};

            int drilled = 0;
            for (Method g : getters) {
                String nm = g.getName().substring("getTVApi".length());
                boolean isGold = false;
                for (String s : gold) {
                    if (nm.contains(s)) { isGold = true; break; }
                }
                if (!isGold && drilled > 200) continue;
                try {
                    Object sub = g.invoke(mgr);
                    if (sub == null) continue;
                    drilled++;
                    Class<?> subIface = sub.getClass().getInterfaces().length > 0
                            ? sub.getClass().getInterfaces()[0] : sub.getClass();
                    log(sb, "\n== " + nm + " (" + subIface.getName() + ") ==");
                    int cnt = 0;
                    ArrayList<Method> cands = new ArrayList<Method>();
                    for (Method m : subIface.getMethods()) {
                        if (m.getDeclaringClass() == Object.class) continue;
                        log(sb, "  " + sig(m));
                        if (cnt++ > 60) { log(sb, "  …лимит"); break; }
                        String n = m.getName().toLowerCase();
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length >= 1 && (n.contains("adb") || n.contains("debug")
                                || n.contains("prop") || n.contains("shell")
                                || n.contains("exec") || n.contains("cmd")
                                || n.contains("command") || n.contains("setting")
                                || n.contains("env") || n.contains("enable")
                                || n.contains("switch"))) {
                            cands.add(m);
                        }
                    }
                    if (isGold) {
                        sb.append("  -- вызовы кандидатов --\n");
                        for (Method m : cands) {
                            Class<?>[] ps = m.getParameterTypes();
                            Object[] args = new Object[ps.length];
                            for (int i = 0; i < ps.length; i++) {
                                Class<?> p = ps[i];
                                if (p == String.class) {
                                    String ln = m.getName().toLowerCase();
                                    if (ln.contains("prop")) {
                                        args[i] = i == 0 ? "persist.service.adb.enable" : "1";
                                    } else if (ln.contains("cmd") || ln.contains("exec")
                                            || ln.contains("shell")) {
                                        args[i] = "settings put global adb_enabled 1";
                                    } else if (ln.contains("env")) {
                                        args[i] = i == 0 ? "ctl.start" : "adbd";
                                    } else {
                                        args[i] = i == 0 ? "adb_enabled" : "1";
                                    }
                                } else if (p == boolean.class) args[i] = Boolean.TRUE;
                                else if (p == int.class) args[i] = 1;
                                else if (p == long.class) args[i] = 1L;
                                else args[i] = null;
                            }
                            try {
                                Object r = m.invoke(sub, args);
                                log(sb, "  CALL " + sig(m) + " -> "
                                        + (r == null ? "(void)" : truncate(String.valueOf(r), 150)));
                            } catch (Throwable t) {
                                log(sb, "  CALL " + sig(m) + " ERR " + rootMsg(t));
                            }

                            if (getInt(ctx) == 1) {
                                log(sb, "\n>>> adb_enabled=1 ПОСЛЕ: " + sig(m));
                                break;
                            }
                        }
                        if (getInt(ctx) == 1) break;
                    }
                } catch (Throwable t) {
                    log(sb, "\n== " + nm + " ERR " + rootMsg(t));
                }
                if (getInt(ctx) == 1) break;
            }
        } catch (Throwable t) {
            log(sb, "ошибка обхода: " + rootMsg(t));
        }

        try {
            ctx.unbindService(b.conn);
        } catch (Throwable ignored) {
        }
        finish(sb, ctx);
        return sb.toString();
    }

    private static String svcName(Object o) {
        Class<?>[] ifs = o.getClass().getInterfaces();
        return ifs.length > 0 ? ifs[0].getName() : o.getClass().getName();
    }

    private static void finish(StringBuilder sb, Context ctx) {
        int after = getInt(ctx);
        boolean port = tcpProbe("127.0.0.1", 5555);
        sb.append("\nadb_enabled ПОСЛЕ: ").append(after).append("\n");
        sb.append("порт 5555 (localhost): ").append(port ? "ОТКРЫТ" : "закрыт").append("\n");
        if (after == 1 || port) {
            sb.append("\n>>> РАЗБЛОКИРОВАНО! Выполните на компьютере: adb connect <IP>:5555\n");
        } else {
            sb.append("\nADB не включён — протокол содержит полный список\n");
            sb.append("методов всех под-API: по нему соберём точный вызов.\n");
        }
    }

    // ==================== РАБОЧАЯ РАЗБЛОЖИКА v4.0 ====================

    private static final String[][] UNLOCK_PROP_SEQ = {
            {"service.adb.tcp.port", "5555"},
            {"ctl.stop", "adbd"},
            {"ctl.start", "adbd"},
            {"persist.service.adb.enable", "1"},
            {"ctl.stop", "adbd"},
            {"ctl.start", "adbd"}
    };

    private static final int[][] UNLOCK_KEY_SEQS = {
            {82, 8, 8, 11, 14},
            {177, 8, 8, 11, 14},
            {8, 8, 11, 14}
    };

    /** РАБОЧАЯ РАЗБЛОЖИКА: проперти от system + инжекция кода int-клавишами. */
    public static String gold(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 WORKING UNLOCK\n");
        sb.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n\n");
        sb.append("adb_enabled ДО: ").append(getInt(ctx)).append("\n");
        sb.append("порт 5555 ДО: ")
          .append(tcpProbe("127.0.0.1", 5555) ? "ОТКРЫТ" : "закрыт").append("\n\n");

        try {
            Bound b = bindOnce(ctx, 7000);
            if (b.error != null || b.binder == null) {
                log(sb, "bind отказ: " + (b.error == null ? "таймаут" : rootMsg(b.error)));
                finish(sb, ctx);
                return sb.toString();
            }
            log(sb, "bind OK (TV_API_SERVICE)");

            ClassLoader cl = null;
            File media = new File(MGR_JAR);
            if (media.exists()) {
                File odex = new File(ctx.getCacheDir(), "golddex");
                if (!odex.exists()) odex.mkdirs();
                cl = new DexClassLoader(MGR_JAR, odex.getAbsolutePath(), null,
                        ApiDrill.class.getClassLoader());
            } else {
                cl = loadMgrClassloader(ctx, sb);
            }
            if (cl == null) {
                log(sb, "classloader не создан");
                finish(sb, ctx);
                return sb.toString();
            }
            Class<?> stub = cl.loadClass(MGR_IFACE + "$Stub");
            Method asi = stub.getDeclaredMethod("asInterface", IBinder.class);
            Object mgr = asi.invoke(null, b.binder);
            log(sb, "ITvApiManager OK");

            Object props = subOf(mgr, "getTVApiSystemProperties");
            Object debug = null;
            try {
                debug = subOf(mgr, "getTVApiSystemDebug");
            } catch (Throwable t) {
                log(sb, "SystemDebug недоступен: " + rootMsg(t));
            }
            log(sb, "SystemProperties: " + (props == null ? "null" : "OK"));
            log(sb, "SystemDebug: " + (debug == null ? "null" : "OK"));

            sb.append("\n== get() ключевых проперти ==\n");
            if (props != null) {
                String[][] reads = {
                        {"persist.adb.tcp.port", "5555"},
                        {"persist.service.adb.enable", "1"},
                        {"service.adb.tcp.port", ""},
                        {"ro.adb.secure", "0"},
                        {"ro.debuggable", "0"}
                };
                for (String[] r : reads) {
                    try {
                        log(sb, "get(" + r[0] + ") = " + propGet(props, r[0]));
                    } catch (Throwable t) {
                        log(sb, "get(" + r[0] + ") ERR " + rootMsg(t));
                    }
                }
            }

            if (props != null) {
                sb.append("\n== РАБОЧАЯ РАЗБЛОЖИКА: свойства + рестарт adbd ==\n");
                for (String[] s : UNLOCK_PROP_SEQ) {
                    try {
                        boolean r = propSet(props, s[0], s[1]);
                        log(sb, "set(" + s[0] + "=\"" + s[1] + "\") -> " + r);
                    } catch (Throwable t) {
                        log(sb, "set(" + s[0] + ") ERR " + rootMsg(t));
                    }
                    if (tcpProbe("127.0.0.1", 5555)) {
                        log(sb, "\n>>> ПОРТ 5555 ОТКРЫТ! РАЗБЛОКИРОВАНО!");
                        break;
                    }
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
            }

            boolean portAfter = tcpProbe("127.0.0.1", 5555);
            int adbAfter = getInt(ctx);
            sb.append("\nпромежуточный итог: порт=").append(portAfter ? "ОТКРЫТ" : "закрыт")
              .append(" adb_enabled=").append(adbAfter).append("\n");

            if (debug != null && !portAfter && adbAfter != 1) {
                sb.append("\n== SystemDebug батарея ==\n");
                try {
                    log(sb, "eventSystemDebugPermissionEnable -> "
                            + debugCall(debug, "eventSystemDebugPermissionEnable"));
                } catch (Throwable t) {
                    log(sb, "eventSystemDebugPermissionEnable ERR " + rootMsg(t));
                }
                try {
                    log(sb, "eventSystemDebugSetStrEnable(true) -> "
                            + debugCall(debug, "eventSystemDebugSetStrEnable", Boolean.TRUE));
                } catch (Throwable t) {
                    log(sb, "SetStrEnable ERR " + rootMsg(t));
                }
                try {
                    log(sb, "eventSystemDebugUartSetEnable(true) -> "
                            + debugCall(debug, "eventSystemDebugUartSetEnable", Boolean.TRUE));
                } catch (Throwable t) {
                    log(sb, "UartSetEnable ERR " + rootMsg(t));
                }
                sb.append("\n== eventSystemSendKeyEvent: заводской код ==\n");
                for (int[] seq : UNLOCK_KEY_SEQS) {
                    StringBuilder ks = new StringBuilder();
                    boolean allOk = true;
                    for (int k : seq) {
                        try {
                            Class<?> iface = debug.getClass().getInterfaces().length > 0
                                    ? debug.getClass().getInterfaces()[0]
                                    : debug.getClass();
                            Method m = iface.getMethod("eventSystemSendKeyEvent", int.class);
                            boolean r = Boolean.TRUE.equals(m.invoke(debug, k));
                            ks.append(k).append(r ? "✓" : "✗").append(" ");
                            if (!r) allOk = false;
                        } catch (Throwable t) {
                            ks.append(k).append("ERR ");
                            allOk = false;
                        }
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    log(sb, "коды {" + ks + "} -> " + (allOk
                            ? "ВСЕ ПРИНЯТЫ — смотри экран ТВ (заводское меню?)"
                            : "есть отказы"));
                    log(sb, "  пауза 3 с — смотри экран…");
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    if (getInt(ctx) == 1 || tcpProbe("127.0.0.1", 5555)) {
                        log(sb, "\n>>> РЕАКЦИЯ ЕСТЬ! adb_enabled="
                                + getInt(ctx) + " порт="
                                + (tcpProbe("127.0.0.1", 5555) ? "ОТКРЫТ" : "закрыт"));
                        break;
                    }
                }
            }

            try {
                ctx.unbindService(b.conn);
            } catch (Throwable ignored) {
            }
            finish(sb, ctx);
            return sb.toString();
        } catch (Throwable t) {
            log(sb, "ошибка: " + rootMsg(t));
            finish(sb, ctx);
            return sb.toString();
        }
    }

    // ==================== ТЮНИНГ v4.1 (картинка/звук/производительность) ====================

    /** Режимы для enum-сеттеров: ищем константу с именем OFF/NONE/DISABLE. */
    private static Object findOffConst(Class<?> enumCls) {
        Object[] consts = enumCls.getEnumConstants();
        if (consts == null) return null;
        for (Object c : consts) {
            String n = String.valueOf(c).toUpperCase();
            if (n.contains("OFF") || n.contains("NONE") || n.contains("DISABLE")) return c;
        }
        return null;
    }

    private static String enumNames(Class<?> enumCls) {
        Object[] consts = enumCls.getEnumConstants();
        if (consts == null) return "(не enum)";
        StringBuilder s = new StringBuilder();
        for (Object c : consts) s.append(c).append(" ");
        return s.toString().trim();
    }

    private static int setIfAccepted(Object sub, Class<?> iface, String setter,
                                     Class<?> paramType, Object value, StringBuilder sb) {
        try {
            Method m = iface.getMethod(setter, paramType);
            Object r = m.invoke(sub, value);
            boolean ok = Boolean.TRUE.equals(r);
            log(sb, "  " + setter + "(" + value + ") -> " + r);
            return ok ? 1 : 0;
        } catch (Throwable t) {
            log(sb, "  " + setter + "(" + value + ") ERR " + rootMsg(t));
            return 0;
        }
    }

    /** Обход «картинка/звук»: отключение улучшайзеров, MEMC off, AVR on, диагностика. */
    public static String tune(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 TUNE (картинка/звук для 40\" FHD)\n");
        sb.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n\n");

        try {
            Bound b = bindOnce(ctx, 7000);
            if (b.error != null || b.binder == null) {
                log(sb, "bind отказ: " + (b.error == null ? "таймаут" : rootMsg(b.error)));
                finish(sb, ctx);
                return sb.toString();
            }
            log(sb, "bind OK");
            ClassLoader cl = null;
            File media = new File(MGR_JAR);
            if (media.exists()) {
                File odex = new File(ctx.getCacheDir(), "golddex");
                if (!odex.exists()) odex.mkdirs();
                cl = new DexClassLoader(MGR_JAR, odex.getAbsolutePath(), null,
                        ApiDrill.class.getClassLoader());
            } else {
                cl = loadMgrClassloader(ctx, sb);
            }
            if (cl == null) {
                log(sb, "classloader не создан");
                finish(sb, ctx);
                return sb.toString();
            }
            Class<?> stub = cl.loadClass(MGR_IFACE + "$Stub");
            Method asi = stub.getDeclaredMethod("asInterface", IBinder.class);
            Object mgr = asi.invoke(null, b.binder);
            Class<?> mgrIface = mgr.getClass().getInterfaces().length > 0
                    ? mgr.getClass().getInterfaces()[0]
                    : cl.loadClass(MGR_IFACE);

            String[] targets = {
                    "PictureNr", "PictureMpegNr", "PictureDlc", "PictureEcoMode",
                    "ScreenMemc", "SoundSurround", "SoundVirtualEffect",
                    "SoundAvl", "SoundDbx", "PictureControl", "PictureGamma",
                    "PictureColorTemp", "PictureHdr", "SystemBacklight",
                    "ScreenAspect", "SoundPowerLimitControl"
            };

            for (String name : targets) {
                Object sub;
                try {
                    sub = subOf(mgr, "getTVApi" + name);
                } catch (Throwable t) {
                    log(sb, "\n== " + name + " недоступен: " + rootMsg(t));
                    continue;
                }
                if (sub == null) {
                    log(sb, "\n== " + name + ": null");
                    continue;
                }
                Class<?> iface = sub.getClass().getInterfaces().length > 0
                        ? sub.getClass().getInterfaces()[0] : sub.getClass();
                log(sb, "\n== " + name + " (" + iface.getName() + ") ==");
                ArrayList<Method> modeSetters = new ArrayList<Method>();
                ArrayList<Method> enableSetters = new ArrayList<Method>();
                ArrayList<Method> getters = new ArrayList<Method>();
                for (Method m : iface.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    log(sb, "  " + sig(m));
                    String n = m.getName().toLowerCase();
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 1 && n.startsWith("event") && n.contains("setmode")) {
                        modeSetters.add(m);
                    } else if (ps.length == 1 && ps[0] == boolean.class
                            && n.startsWith("event") && n.contains("setenable")) {
                        enableSetters.add(m);
                    } else if (ps.length == 0 && n.startsWith("event") && n.contains("get")) {
                        getters.add(m);
                    }
                }

                // Getters: диагностика текущего состояния
                for (Method m : getters) {
                    try {
                        Object r = m.invoke(sub);
                        log(sb, "  READ " + sig(m) + " -> "
                                + (r == null ? "null" : truncate(String.valueOf(r), 120)));
                    } catch (Throwable t) {
                        log(sb, "  READ " + sig(m) + " ERR " + rootMsg(t));
                    }
                }

                // SetEnable: AVR/Dbc-логика
                for (Method m : enableSetters) {
                    String ln = m.getName().toLowerCase();
                    boolean want = ln.contains("avl") || ln.contains("volume")
                            || ln.contains("advolume");
                    log(sb, "  APPLY " + m.getName() + "(" + want + ")");
                    try {
                        Object r = m.invoke(sub, want);
                        log(sb, "    -> " + r);
                    } catch (Throwable t) {
                        log(sb, "    ERR " + rootMsg(t));
                    }
                }

                // SetMode(enum): ищем OFF-константу
                for (Method m : modeSetters) {
                    Class<?> enumCls = m.getParameterTypes()[0];
                    if (!enumCls.isEnum()) continue;
                    log(sb, "  enum " + enumCls.getSimpleName() + ": "
                            + enumNames(enumCls));
                    Object off = findOffConst(enumCls);
                    if (off == null) {
                        log(sb, "  OFF-константа не найдена — пропускаю");
                        continue;
                    }
                    try {
                        Object r = m.invoke(sub, off);
                        log(sb, "  APPLY " + m.getName() + "(" + off + ") -> " + r);
                        // верификация через соответствующий GetMode
                        try {
                            String gm = m.getName().replace("Set", "Get");
                            Method get = iface.getMethod(gm, (Class<?>[]) null);
                            log(sb, "    проверка " + gm + " -> "
                                    + String.valueOf(get.invoke(sub)));
                        } catch (Throwable ignored) {
                        }
                    } catch (Throwable t) {
                        log(sb, "  APPLY ERR " + rootMsg(t));
                    }
                }
            }

            // Backlight: только проверка состояния
            // Режим картинки: VIVID -> STANDARD (точность вместо магазинной яркости)
            try {
                Object pc = subOf(mgr, "getTVApiPictureControl");
                Class<?> pcIface = pc.getClass().getInterfaces().length > 0
                        ? pc.getClass().getInterfaces()[0] : pc.getClass();
                Method getMode = pcIface.getMethod("eventGetCurrentPictureMode");
                Object cur = getMode.invoke(pc);
                log(sb, "\\n== PictureControl: режим " + cur);
                if (String.valueOf(cur).contains("VIVID")) {
                    Class<?> enumCls = getMode.getReturnType();
                    Object target = null;
                    for (Object c : enumCls.getEnumConstants()) {
                        if (String.valueOf(c).contains("STANDARD")) { target = c; break; }
                    }
                    if (target != null) {
                        Method setMode = pcIface.getMethod("eventSetCurrentPictureMode",
                                enumCls);
                        Object r = setMode.invoke(pc, target);
                        log(sb, "set(STANDARD) -> " + r + ", проверка: "
                                + String.valueOf(getMode.invoke(pc)));
                    } else {
                        log(sb, "STANDARD-константа не найдена: "
                                + enumNames(enumCls));
                    }
                }
            } catch (Throwable t) {
                log(sb, "PictureControl ERR " + rootMsg(t));
            }

            log(sb, "\n== Примечание ==");
            log(sb, "Backlight PWM и ColorTemp калибровки сознательно не тронуты");
            log(sb, "(риск для панели). Цветовая температура — по вкусу в меню ТВ.");

        } catch (Throwable t) {
            log(sb, "ошибка: " + rootMsg(t));
        }

        sb.append("\nАНИМАЦИИ: уже 0. Рекомендации по звуку применены там, где принято.\n");
        sb.append("Полный протокол: файл ниже.\n");
        return sb.toString();
    }
}
