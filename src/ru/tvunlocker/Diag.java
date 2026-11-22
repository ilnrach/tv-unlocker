package ru.tvunlocker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Диагностика 3.1: почему bind=false, командные интенты через startService,
 * broadcast к TvApiDebugBroadcastReceiver, чтение /vendor/tvconfig,
 * расширенный перебор таблиц и call() у DataBaseProvider.
 */
public class Diag {

    private static final String[] SERVICE_TARGETS = {
            "com.cvte.factory.service/com.cvte.factory.service.MainService",
            "com.cvte.factory.service/com.cvte.factory.service.KeyService",
            "com.cvte.factory.at/com.cvte.factory.at.job.MainService",
            "com.cvte.tv.api.impl/com.cvte.tv.api.TvApiService",
            "com.cvte.tv.api.impl/com.cvte.tv.api.HotkeyService",
            "com.cvte.tv.api.impl/com.cvte.tv.api.TV_API_SERVICE",
            "com.cvte.tv.setting/com.cvte.tv.setting.extra.InterfaceService",
            "com.cvte.tv.setting/com.cvte.tv.setting.quickmenu.QuickMenuService",
            "com.cvte.fac.menu/com.cvte.fac.menu.app.TvMenuWindowManagerService"
    };

    private static final String CMD = "settings put global adb_enabled 1";

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = String.valueOf(c.getMessage());
        return c.getClass().getSimpleName() + ": " + (m.length() > 130 ? m.substring(0, 130) : m);
    }

    private static int getInt(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "adb_enabled", -1);
        } catch (Throwable t) {
            return -2;
        }
    }

    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 diag\n");
        sb.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n");
        PackageManager pm = ctx.getPackageManager();

        // ===== А. Информация о сервисах =====
        sb.append("\n== А. ServiceInfo целевых сервисов ==\n");
        for (String t : SERVICE_TARGETS) {
            int sl = t.indexOf('/');
            String pkg = t.substring(0, sl);
            String cls = t.substring(sl + 1);
            try {
                ServiceInfo si = pm.getServiceInfo(new ComponentName(pkg, cls),
                        PackageManager.GET_META_DATA);
                sb.append(t).append("\n   exported=").append(si.exported)
                  .append(" permission=").append(si.permission == null ? "(нет)" : si.permission)
                  .append(" enabled=").append(si.enabled)
                  .append(" uid=").append(si.applicationInfo == null ? "?" : si.applicationInfo.uid)
                  .append("\n");
            } catch (Throwable t2) {
                sb.append(t).append("\n   НЕ НАЙДЕН (").append(rootMsg(t2)).append(")\n");
            }
        }

        // ===== Б. Варианты bind =====
        sb.append("\n== Б. Варианты bind ==\n");
        String[][] binds = {
                {"com.cvte.factory.service", "com.cvte.factory.service.MainService", "component"},
                {"com.cvte.factory.service", "com.cvte.factory.service.KeyService", "component"},
                {"com.cvte.factory.at", "com.cvte.factory.at.job.MainService", "component"},
                {"com.cvte.tv.setting", "com.cvte.tv.setting.extra.InterfaceService", "component"},
                {"com.cvte.tv.setting", "com.cvte.tv.setting.quickmenu.QuickMenuService", "component"},
                {"com.cvte.tv.api.impl", "com.cvte.tv.api.TvApiService", "component"},
                {"com.cvte.tv.api.impl", "com.cvte.tv.api.HotkeyService", "component"}
        };
        for (String[] b : binds) {
            sb.append("BIND(component) ").append(b[1])
              .append(" — v3.0 на ТВ подтвердило: false (YaOS блокирует компонентный bind)\n");
        }
        // bind через action
        String[][] actionBinds = {
                {"android.intent.action.CVTEFACSERVICE", "com.cvte.factory.service"},
                {"cvte.factory.intent.action.APIInit", "com.cvte.factory.service"}
        };
        for (String[] ab : actionBinds) {
            try {
                Intent i = new Intent(ab[0]);
                i.setPackage(ab[1]);
                ServiceConnection[] conn = new ServiceConnection[1];
                final boolean[] res = new boolean[1];
                final CountDownLatch latch = new CountDownLatch(1);
                conn[0] = new android.content.ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        res[0] = true;
                        latch.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                    }
                };
                final boolean[] ok = new boolean[1];
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        ok[0] = ctx.bindService(i, conn[0], Context.BIND_AUTO_CREATE);
                    } catch (Throwable t) {
                        ok[0] = false;
                    }
                    if (!ok[0]) latch.countDown();
                });
                latch.await(5, TimeUnit.SECONDS);
                try {
                    if (ok[0]) ctx.unbindService(conn[0]);
                } catch (Throwable ignored) {
                }
                sb.append("BIND(action ").append(ab[0]).append("): ")
                  .append(ok[0] ? "bindService=true, " : "bindService=false, ")
                  .append(res[0] ? "СОЕДИНЕНИЕ ЕСТЬ" : "соединения нет").append("\n");
            } catch (Throwable t) {
                sb.append("BIND(action ").append(ab[0]).append("): ERR ").append(rootMsg(t)).append("\n");
            }
        }

        // ===== В. Командные интенты через startService =====
        int adbBeforeCmds = getInt(ctx);
        sb.append("\n== В. startService с командами (проверка adb_enabled после каждой) ==\n");
        sb.append("adb_enabled ДО командных интентов: ").append(adbBeforeCmds).append("\n");
        String[] cmdTargets = {
                "com.cvte.factory.service/com.cvte.factory.service.MainService",
                "com.cvte.factory.service/com.cvte.factory.service.KeyService",
                "com.cvte.tv.api.impl/com.cvte.tv.api.TvApiService",
                "com.cvte.tv.api.impl/com.cvte.tv.api.HotkeyService"
        };
        String[] actions = {
                "android.intent.action.CVTEFACSERVICE",
                "cvte.factory.intent.action.APIInit",
                "StartBroadCastByAction",
                "StartActivityByAction",
                "execShellCmd",
                "actShellCommand"
        };
        int sent = 0;
        outer:
        for (String t : cmdTargets) {
            int sl = t.indexOf('/');
            String pkg = t.substring(0, sl);
            String cls = t.substring(sl + 1);
            for (String a : actions) {
                String[][] variants = {
                        {null},
                        {"cmd"},
                        {"action"}
                };
                for (String[] ex : variants) {
                    try {
                        Intent i = new Intent(a);
                        i.setClassName(pkg, cls);
                        if (ex[0] != null) i.putExtra(ex[0], CMD);
                        ctx.startService(i);
                        sent++;
                        sb.append("OK ").append(cls).append(" action=").append(a)
                          .append(" extra=").append(ex[0] == null ? "(нет)" : ex[0]).append("\n");
                    } catch (Throwable t2) {
                        sb.append("ERR ").append(cls).append(" action=").append(a)
                          .append(" — ").append(rootMsg(t2)).append("\n");
                    }
                    if (sent >= 48) {
                        sb.append("... лимит 48\n");
                        break outer;
                    }
                    if (adbBeforeCmds != 1 && getInt(ctx) == 1) {
                        sb.append(">>> adb_enabled стал 1 после: ").append(a)
                          .append(" extra=").append(ex[0]).append("\n");
                        break outer;
                    }
                }
            }
        }
        sb.append("adb_enabled после командных интентов: ").append(getInt(ctx)).append("\n\n");

        // ===== Г. Broadcast к TvApiDebugBroadcastReceiver с командами =====
        int adbBeforeBr = getInt(ctx);
        sb.append("== Г. Broadcast к TvApiDebugBroadcastReceiver ==\n");
        sb.append("adb_enabled ДО broadcast: ").append(adbBeforeBr).append("\n");
        String[][] brTargets = {
                {"com.cvte.tv.api.impl", "com.cvte.tv.api.base.TvApiDebugBroadcastReceiver"},
                {"com.cvte.tv.setting", "com.cvte.tv.setting.extra.TvExtraReceiver"}
        };
        String[] brActions = {
                "com.cvte.tv.api.action.DEBUG",
                "com.cvte.tv.api.debug",
                "com.cvte.tv.api.intent.action.DEBUG",
                "com.cvte.tv.api.HOTKEY",
                "android.intent.action.CVTEFACSERVICE",
                "cvte.tv.api.debug"
        };
        int brSent = 0;
        outer2:
        for (String[] bt : brTargets) {
            for (String a : brActions) {
                String[][] variants = {{null}, {"cmd"}, {"action"}};
                for (String[] ex : variants) {
                    try {
                        Intent i = new Intent(a);
                        i.setClassName(bt[0], bt[1]);
                        if (ex[0] != null) i.putExtra(ex[0], CMD);
                        ctx.sendBroadcast(i);
                        brSent++;
                        sb.append("OK ").append(bt[1]).append(" action=").append(a)
                          .append(" extra=").append(ex[0] == null ? "(нет)" : ex[0]).append("\n");
                    } catch (Throwable t2) {
                        sb.append("ERR ").append(bt[1]).append(" — ").append(rootMsg(t2)).append("\n");
                    }
                    if (brSent >= 24) {
                        sb.append("... лимит 24\n");
                        break outer2;
                    }
                    if (adbBeforeBr != 1 && getInt(ctx) == 1) {
                        sb.append(">>> adb_enabled стал 1 после broadcast: ").append(a).append("\n");
                        break outer2;
                    }
                }
            }
        }
        sb.append("adb_enabled после broadcast: ").append(getInt(ctx)).append("\n\n");

        // ===== Д. /vendor/tvconfig — конфиги заводского меню =====
        sb.append("\n== Д. /vendor/tvconfig (строки с adb/factory/debug/hotkey) ==\n");
        String[] roots = {"/vendor/tvconfig", "/vendor/etc/tvconfig", "/tvconfig"};
        int cfgLines = 0;
        for (String rootPath : roots) {
            File root = new File(rootPath);
            if (!root.exists() || !root.isDirectory()) {
                sb.append(rootPath).append(": нет\n");
                continue;
            }
            sb.append(rootPath).append(":\n");
            cfgLines += walkConfig(root, sb, 0, 4, 80, 400);
            if (cfgLines >= 400) break;
        }
        if (cfgLines == 0) sb.append("(совпадений нет)\n");
        File dbg = new File("/data/mtk_tvapi_debug");
        sb.append("/data/mtk_tvapi_debug: ").append(dbg.exists() ? "существует" : "нет").append("\n\n");

        // ===== Е. DataBaseProvider: расширенные таблицы + call() =====
        sb.append("== Е. DataBaseProvider: таблицы и call() ==\n");
        String auth = "com.cvte.tv.setting.DataBaseProvider";
        String[] tables = {
                "factory", "setting", "settings", "tvsetting", "adb", "config",
                "common", "panel", "source", "menu", "option", "options", "system",
                "debug", "dev", "main", "channel", "menu_config", "cfg", "param",
                "params", "property", "properties", "key_value", "kv", "value",
                "values", "info", "data", "item", "items", "config_root", "root",
                "fac", "factory_menu", "menuitem", "menu_item", "sys",
                "system_config", "setting_item", "db", "database"
        };
        for (String table : tables) {
            try {
                Cursor c = ctx.getContentResolver().query(
                        Uri.parse("content://" + auth + "/" + table),
                        null, null, null, null);
                if (c != null) {
                    StringBuilder cols = new StringBuilder();
                    for (String cn : c.getColumnNames()) cols.append(cn).append(",");
                    if (cols.length() > 0) cols.setLength(cols.length() - 1);
                    sb.append("  ЕСТЬ /").append(table).append(" строк=").append(c.getCount())
                      .append(" колонки=").append(cols).append("\n");
                    c.close();
                }
            } catch (Throwable t) {
                String m = String.valueOf(t.getMessage());
                if (m.length() > 80) m = m.substring(0, 80);
                if (!m.contains("Invalid tables")) {
                    sb.append("  /").append(table).append(" ERR ").append(m).append("\n");
                }
            }
        }
        sb.append("  (таблицы без «Invalid tables» показаны выше)\n");
        String[] callMethods = {
                "getSetting", "setSetting", "get", "set", "getValue", "setValue",
                "query", "exec", "call", "getAdbStatus", "setAdbStatus", "adb"
        };
        sb.append("  call():\n");
        for (String m : callMethods) {
            try {
                Bundle r = ctx.getContentResolver().call(
                        Uri.parse("content://" + auth), m, "adb_enabled", null);
                sb.append("  call(\"").append(m).append("\", \"adb_enabled\") -> ")
                  .append(r == null ? "null" : r.toString()).append("\n");
            } catch (Throwable t) {
                String mm = String.valueOf(t.getMessage());
                if (mm.length() > 80) mm = mm.substring(0, 80);
                sb.append("  call(\"").append(m).append("\") ERR ").append(mm).append("\n");
            }
        }

        int after = getInt(ctx);
        sb.append("\nadb_enabled ИТОГ: ").append(after);
        if (after == 1) {
            sb.append("\n>>> РАЗБЛОКИРОВАНО! Выполните на компьютере: adb connect <IP>:5555\n");
        } else {
            sb.append("\nADB не включён — протокол выше покажет, где отказ.\n");
        }
        return sb.toString();
    }

    private static int walkConfig(File dir, StringBuilder sb, int depth, int maxDepth,
                                  int maxFiles, int maxLines) {
        if (depth > maxDepth) return 0;
        int total = 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        for (File f : list) {
            if (total >= maxLines) break;
            if (f.isDirectory()) {
                total += walkConfig(f, sb, depth + 1, maxDepth, maxFiles, maxLines - total);
            } else if (f.isFile() && f.length() > 0 && f.length() < 512 * 1024) {
                try {
                    InputStream in = new FileInputStream(f);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    String content = bo.toString("UTF-8");
                    String lower = content.toLowerCase();
                    if (lower.contains("adb") || lower.contains("factory")
                            || lower.contains("debug") || lower.contains("hotkey")
                            || lower.contains("fackey") || lower.contains("keymap")) {
                        sb.append("  -- файл ").append(f.getAbsolutePath())
                          .append(" (").append(f.length()).append(" Б) --\n");
                        for (String line : content.split("\r?\n")) {
                            String l = line.toLowerCase();
                            if (l.contains("adb") || l.contains("factory")
                                    || l.contains("debug") || l.contains("hotkey")
                                    || l.contains("fackey") || l.contains("keymap")
                                    || l.contains("enable")) {
                                sb.append("    ").append(line.trim()).append("\n");
                                total++;
                                if (total >= maxLines) break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (total >= maxLines) break;
        }
        return total;
    }
}
