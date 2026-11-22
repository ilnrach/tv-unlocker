package ru.tvunlocker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Пробивка (battery): легитимные попытки включить ADB и картирование
 * сервисных компонент собственного устройства.
 *
 * Только публичные API: Settings writes, queryBroadcastReceivers,
 * explicit sendBroadcast/startService, provider query. Никакой эксплуатации.
 */
public class Probe {

    private static final String[] KEYS = {
            "factory", "cvte", "mediatek", "mstar", "designmenu", "mainmenu",
            "development", "developer", "debug", "adb", "engineering",
            "servicemenu", "service_menu"
    };

    /** Пакетные префиксы платформ ТВ. */
    private static final String[] VENDOR_PREFIXES = {
            "com.cvte.", "cn.cvte.", "com.mediatek.", "com.mstar.", "com.stb.",
            "com.yandex.tv", "com.android.tv.settings", "com.coocaa."
    };

    /** Словарь candidate-action для поиска приёмников. */
    private static final String[] ACTIONS = {
            "mediatek.intent.action.MainmenuActivity",
            "mstar.tvsetting.factory.intent.action.MainmenuActivity",
            "com.cvte.intent.action.factory",
            "com.cvte.factory.at.START",
            "com.cvte.tv.intent.action.FACTORY",
            "com.cvte.tv.intent.action.debug",
            "com.cvte.adb.enable",
            "com.cvte.intent.action.adb",
            "com.mediatek.factorymode",
            "com.mediatek.tv.factory",
            "android.intent.action.FACTORY_TEST",
            "com.android.factorytest.intent.action.FACTORY",
            "cn.cvte.tv.factory.autotest.START",
            "com.coocaa.factory.intent.action.MAIN",
            "com.tcl.factory.FactoryMode",
            "com.yandex.tv.intent.action.developer",
            "com.yandex.tv.settings.DEBUG"
    };

    /** Опасные подстроки в имени компонента — их не трогаем. */
    private static final String RISKY =
            "reset|wipe|erase|shutdown|poweroff|power_off|reboot|restart|recovery"
            + "|burn|aging|shipping|format|upgrade|update|clear|wipe_data|omacp";

    private static boolean risky(String s) {
        String l = s.toLowerCase();
        for (String part : RISKY.split("\\|")) {
            if (l.contains(part)) return true;
        }
        return false;
    }

    private static boolean isVendor(String pkg) {
        for (String p : VENDOR_PREFIXES) {
            if (pkg.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean interesting(String pkg, String cls) {
        boolean vendor = isVendor(pkg);
        String hay = (pkg + " " + cls).toLowerCase();
        if (vendor) return true;
        for (String k : KEYS) {
            if (hay.contains(k)) return true;
        }
        return false;
    }

    private static int getAdb(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "adb_enabled", -1);
        } catch (Throwable t) {
            return -2;
        }
    }

    /** Выполняет всю пробивку, возвращает текстовый протокол. */
    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 probe\n");
        sb.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n");
        sb.append("Устройство: ").append(android.os.Build.MODEL).append("\n\n");

        PackageManager pm = ctx.getPackageManager();

        int before = getAdb(ctx);
        sb.append("adb_enabled ДО: ").append(before).append("\n\n");

        // ---- 1. Прямая запись adb_enabled ----
        sb.append("== 1. Прямая запись adb_enabled ==\n");
        try {
            Settings.Global.putInt(ctx.getContentResolver(), "adb_enabled", 1);
            sb.append("global: ЗАПИСЬ ПРОШЛА!\n");
        } catch (Throwable t) {
            sb.append("global: отказано — ").append(t.getClass().getSimpleName())
              .append(": ").append(t.getMessage()).append("\n");
        }
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), "adb_enabled", 1);
            sb.append("secure: ЗАПИСЬ ПРОШЛА!\n");
        } catch (Throwable t) {
            sb.append("secure: отказано — ").append(t.getClass().getSimpleName())
              .append(": ").append(t.getMessage()).append("\n");
        }
        sb.append("adb_enabled сейчас: ").append(getAdb(ctx)).append("\n\n");

        // ---- 2. Словарь candidate-action -> queryBroadcastReceivers ----
        sb.append("== 2. Поиск приёмников по словарю action ==\n");
        int matched = 0;
        for (String a : ACTIONS) {
            try {
                List<ResolveInfo> rl = pm.queryBroadcastReceivers(new Intent(a), 0);
                if (rl != null && !rl.isEmpty()) {
                    matched++;
                    sb.append("action ").append(a).append(" -> ").append(rl.size())
                      .append(" приёмник(ов):\n");
                    for (ResolveInfo ri : rl) {
                        if (ri == null || ri.activityInfo == null) continue;
                        sb.append("   ").append(ri.activityInfo.exported ? "E " : "C ")
                          .append(ri.activityInfo.packageName).append("/")
                          .append(ri.activityInfo.name).append("\n");
                    }
                }
            } catch (Throwable t) {
                sb.append("action ").append(a).append(" -> ошибка ").append(t.getMessage()).append("\n");
            }
        }
        if (matched == 0) sb.append("совпадений нет\n");
        sb.append("\n");

        // ---- 3. Явные broadcast к экспортированным интересным приёмникам ----
        sb.append("== 3. Явные broadcast к приёмникам (platform/keyword, без опасных) ==\n");
        List<PackageInfo> packs = null;
        try {
            packs = pm.getInstalledPackages(PackageManager.GET_RECEIVERS);
        } catch (Throwable t) {
            sb.append("ошибка перечисления: ").append(t.getMessage()).append("\n");
        }
        int sent = 0, denied = 0, skippedRisky = 0;
        if (packs != null) {
            for (PackageInfo pi : packs) {
                if (pi == null || pi.receivers == null) continue;
                String pkg = pi.packageName == null ? "" : pi.packageName;
                for (android.content.pm.ActivityInfo ai : pi.receivers) {
                    if (ai == null || ai.name == null) continue;
                    if (!interesting(pkg, ai.name)) continue;
                    if (risky(ai.name)) { skippedRisky++; continue; }
                    if (!ai.exported) continue;
                    try {
                        Intent i = new Intent();
                        i.setComponent(new ComponentName(pkg, ai.name));
                        ctx.sendBroadcast(i);
                        sent++;
                        sb.append("OK  ").append(pkg).append("/").append(ai.name).append("\n");
                    } catch (Throwable t) {
                        denied++;
                        sb.append("ERR ").append(pkg).append("/").append(ai.name)
                          .append(" — ").append(t.getMessage()).append("\n");
                    }
                    if (sent + denied >= 60) {
                        sb.append("... лимит 60 достигнут\n");
                        break;
                    }
                }
                if (sent + denied >= 60) break;
            }
        }
        sb.append("итого: отправлено ").append(sent).append(", отказано ").append(denied)
          .append(", пропущено опасных ").append(skippedRisky).append("\n\n");

        // ---- 4. Запуск экспортированных интересных сервисов ----
        sb.append("== 4. startService к сервисам (platform/keyword, без опасных) ==\n");
        List<PackageInfo> spacks = null;
        try {
            spacks = pm.getInstalledPackages(PackageManager.GET_SERVICES);
        } catch (Throwable t) {
            sb.append("ошибка перечисления: ").append(t.getMessage()).append("\n");
        }
        int started = 0, sdenied = 0;
        if (spacks != null) {
            outer:
            for (PackageInfo pi : spacks) {
                if (pi == null || pi.services == null) continue;
                String pkg = pi.packageName == null ? "" : pi.packageName;
                for (ServiceInfo si : pi.services) {
                    if (si == null || si.name == null) continue;
                    if (!interesting(pkg, si.name)) continue;
                    if (risky(si.name)) continue;
                    if (!si.exported) continue;
                    try {
                        Intent i = new Intent();
                        i.setComponent(new ComponentName(pkg, si.name));
                        ctx.startService(i);
                        started++;
                        sb.append("OK  ").append(pkg).append("/").append(si.name).append("\n");
                    } catch (Throwable t) {
                        sdenied++;
                        sb.append("ERR ").append(pkg).append("/").append(si.name)
                          .append(" — ").append(t.getMessage()).append("\n");
                    }
                    if (started + sdenied >= 40) {
                        sb.append("... лимит 40 достигнут\n");
                        break outer;
                    }
                }
            }
        }
        sb.append("итого: запущено ").append(started).append(", отказано ").append(sdenied).append("\n\n");

        // ---- 5. Чтение экспортированных интересных провайдеров ----
        sb.append("== 5. Чтение провайдеров (platform/keyword) ==\n");
        List<PackageInfo> ppacks = null;
        try {
            ppacks = pm.getInstalledPackages(PackageManager.GET_PROVIDERS);
        } catch (Throwable t) {
            sb.append("ошибка перечисления: ").append(t.getMessage()).append("\n");
        }
        int readable = 0, perror = 0;
        if (ppacks != null) {
            outer2:
            for (PackageInfo pi : ppacks) {
                if (pi == null || pi.providers == null) continue;
                String pkg = pi.packageName == null ? "" : pi.packageName;
                for (ProviderInfo pri : pi.providers) {
                    if (pri == null || pri.authority == null) continue;
                    if (!interesting(pkg, pri.authority) && !interesting(pkg, pri.name)) continue;
                    if (!pri.exported) continue;
                    if (risky(pri.authority) || risky(pri.name)) continue;
                    try {
                        android.database.Cursor c = ctx.getContentResolver()
                                .query(Uri.parse("content://" + pri.authority),
                                        null, null, null, null);
                        if (c != null) {
                            readable++;
                            sb.append("ЧИТАЕТСЯ  content://").append(pri.authority)
                              .append("  (строк: ").append(c.getCount()).append(")\n");
                            c.close();
                        } else {
                            sb.append("NULL      content://").append(pri.authority).append("\n");
                        }
                    } catch (Throwable t) {
                        perror++;
                        sb.append("ERR       content://").append(pri.authority)
                          .append(" — ").append(t.getMessage()).append("\n");
                    }
                    if (readable + perror >= 40) {
                        sb.append("... лимит 40 достигнут\n");
                        break outer2;
                    }
                }
            }
        }
        sb.append("итого: читается ").append(readable).append(", ошибок ").append(perror).append("\n\n");

        // ---- 6. Итог ----
        int after = getAdb(ctx);
        sb.append("adb_enabled ПОСЛЕ: ").append(after).append("\n");
        if (before != 1 && after == 1) {
            sb.append("\n>>> РАЗБЛОКИРОВАНО! Подключение: adb connect <IP>:5555\n");
        } else if (after == 1) {
            sb.append("\nADB уже включён — можно подключаться: adb connect <IP>:5555\n");
        } else {
            sb.append("\nADB не включён через приложение. Если выше всё 'отказано' —\n");
            sb.append("нужен путь через фабричное меню/сервисный код/прошивку.\n");
        }
        return sb.toString();
    }
}
