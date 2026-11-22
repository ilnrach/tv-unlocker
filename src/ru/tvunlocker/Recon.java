package ru.tvunlocker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Рекон прошивки: извлекает строки из манифестов и dex-файлов вендорских APK
 * (файлы /system читаемы всем), чтобы найти точные action-строки, команды
 * и таблицы провайдеров для адресного разблокировщика v3.
 */
public class Recon {

    /** Пакеты, чей dex читаем в первую очередь (самые перспективные). */
    private static final String[] PRIORITY_DEX = {
            "com.cvte.factory.at",
            "com.cvte.factory.service",
            "com.cvte.tv.api.impl",
            "com.cvte.tv.setting",
            "com.mediatek.backgrounddetection",
            "com.cvte.tv.globalui",
            "com.mediatek.tv.service",
            "com.cvte.tv.ucservice",
            "com.yandex.tv.settings",
            "com.android.tv.settings",
            "com.mediatek.tvinput"
    };

    /** Ключевые слова фильтра строк dex. */
    private static final String[] DEX_KEYWORDS = {
            "adb", "adbd", "factory", "debug", "ctl.start", "ctl.stop", "persist.",
            "content://", "setprop", "getprop", "insert into", "select ", "update ",
            "create table", "from ", "settings", "global", "provider", "action",
            "secret", "testmode", "engineer", "menu"
    };

    /** Кандидаты таблиц для провайдеров CVTE/MTK. */
    private static final String[] TABLE_CANDIDATES = {
            "factory", "setting", "settings", "tvsetting", "adb", "config",
            "common", "panel", "source", "menu", "option", "options", "system",
            "debug", "dev", "main", "tvpq", "pq", "sound", "audio", "channel",
            "factorytable", "factory_table", "fac", "database", "db", "items",
            "tv", "tuner", "misc", "customer", "vendor", "menu_config"
    };

    /** Провайдеры, у которых перебираем таблицы. */
    private static final String[] PROBE_AUTHORITIES = {
            "com.cvte.tv.setting.DataBaseProvider",
            "com.mediatek.tv.internal.data",
            "com.cvt.tvapicontentprovider",
            "tvlauncher.config"
    };

    private static boolean dexInteresting(String s) {
        String l = s.toLowerCase();
        for (String k : DEX_KEYWORDS) {
            if (l.contains(k)) return true;
        }
        return false;
    }

    private static boolean manifestInteresting(String pkg) {
        String l = pkg.toLowerCase();
        return l.contains("cvte") || l.contains("mediatek") || l.contains("mstar")
                || l.contains("yandex.tv") || l.contains("tv.settings")
                || l.contains("stb") || l.contains("coocaa") || l.contains("factory");
    }

    /** Выполняет рекон, возвращает текстовый протокол. */
    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 recon\n");
        sb.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n\n");

        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> packs;
        try {
            packs = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            sb.append("ошибка перечисления: ").append(t.getMessage());
            return sb.toString();
        }

        // --- Манифесты всех интересных пакетов ---
        sb.append("==================================================\n");
        sb.append("== ЧАСТЬ 1. СТРОКИ МАНИФЕСТОВ (все вендорские пакеты) ==\n");
        int dexDone = 0;
        for (PackageInfo pi : packs) {
            if (pi == null || pi.packageName == null) continue;
            String pkg = pi.packageName;
            boolean manifestTarget = manifestInteresting(pkg);
            boolean dexTarget = false;
            for (String p : PRIORITY_DEX) {
                if (p.equals(pkg)) { dexTarget = true; break; }
            }

            if (!manifestTarget && !dexTarget) continue;

            String src;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                src = ai.sourceDir;
            } catch (Throwable t) {
                continue;
            }
            if (src == null) continue;

            sb.append("\n==================================================\n");
            sb.append("== ПАКЕТ: ").append(pkg).append(" ==\n");
            sb.append("APK: ").append(src).append("\n");

            // Манифест
            try {
                byte[] mf = readZipEntry(src, "AndroidManifest.xml", 4 * 1024 * 1024);
                if (mf != null) {
                    LinkedHashSet<String> strings = new LinkedHashSet<String>();
                    scanUtf16(mf, strings);
                    scanAscii(mf, 4, strings);
                    sb.append("--- МАНИФЕСТ: строк ").append(strings.size()).append(" ---\n");
                    for (String s : strings) sb.append(s).append("\n");
                } else {
                    sb.append("МАНИФЕСТ: не найден в APK\n");
                }
            } catch (Throwable t) {
                sb.append("МАНИФЕСТ: ошибка ").append(t.getMessage()).append("\n");
            }

            // Dex (только приоритетные, с фильтром)
            if (dexTarget && dexDone < 12) {
                try {
                    StringBuilder dexOut = new StringBuilder();
                    int count = 0;
                    for (int d = 0; d < 4 && count < 800; d++) {
                        String entry = d == 0 ? "classes.dex" : "classes" + (d + 1) + ".dex";
                        byte[] dex = readZipEntry(src, entry, 64 * 1024 * 1024);
                        if (dex == null) break;
                        LinkedHashSet<String> strings = new LinkedHashSet<String>();
                        scanAscii(dex, 5, strings);
                        for (String s : strings) {
                            if (dexInteresting(s) && count < 800) {
                                dexOut.append(s).append("\n");
                                count++;
                            }
                        }
                    }
                    sb.append("--- DEX (фильтрованные строки, ").append(count).append(") ---\n");
                    sb.append(dexOut);
                    dexDone++;
                } catch (Throwable t) {
                    sb.append("DEX: ошибка ").append(t.getMessage()).append("\n");
                }
            }
        }

        // --- Перебор таблиц провайдеров ---
        sb.append("\n==================================================\n");
        sb.append("== ЧАСТЬ 2. ПЕРЕБОР ТАБЛИЦ ПРОВАЙДЕРОВ ==\n");
        for (String auth : PROBE_AUTHORITIES) {
            sb.append("\n-- ").append(auth).append(" --\n");
            for (String table : TABLE_CANDIDATES) {
                Uri uri = Uri.parse("content://" + auth + "/" + table);
                try {
                    Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
                    if (c != null) {
                        sb.append("  ЕСТЬ ТАБЛИЦА /").append(table)
                          .append("  строк: ").append(c.getCount()).append("\n");
                        // первые имена колонок
                        try {
                            StringBuilder cols = new StringBuilder();
                            for (String cn : c.getColumnNames()) cols.append(cn).append(",");
                            if (cols.length() > 0) cols.setLength(cols.length() - 1);
                            sb.append("    колонки: ").append(cols).append("\n");
                        } catch (Throwable ignored) {
                        }
                        c.close();
                    } else {
                        sb.append("  /").append(table).append(" -> null\n");
                    }
                } catch (Throwable t) {
                    String m = String.valueOf(t.getMessage());
                    if (m.length() > 120) m = m.substring(0, 120);
                    sb.append("  /").append(table).append(" -> ERR ").append(m).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /** Читает запись из zip/APK с ограничением размера. */
    private static byte[] readZipEntry(String apkPath, String entryName, int maxBytes)
            throws Exception {
        InputStream in = new FileInputStream(apkPath);
        try {
            ZipInputStream zis = new ZipInputStream(in);
            try {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.getName().equals(entryName)) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        long total = 0;
                        while ((n = zis.read(buf)) > 0) {
                            bo.write(buf, 0, n);
                            total += n;
                            if (total > maxBytes) break;
                        }
                        return bo.toByteArray();
                    }
                }
            } finally {
                zis.close();
            }
        } finally {
            in.close();
        }
        return null;
    }

    /** Извлекает UTF-16LE строки (AXML string pool). */
    private static void scanUtf16(byte[] b, LinkedHashSet<String> out) {
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i + 1 < b.length; i++) {
            int lo = b[i] & 0xFF;
            int hi = b[i + 1] & 0xFF;
            if (hi == 0 && lo >= 0x20 && lo < 0x7F) {
                cur.append((char) lo);
                i++;
            } else {
                if (cur.length() >= 4) out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() >= 4) out.add(cur.toString());
    }

    /** Извлекает ASCII/UTF-8 строки длиной >= minLen. */
    private static void scanAscii(byte[] b, int minLen, LinkedHashSet<String> out) {
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            if (v >= 0x20 && v < 0x7F) {
                cur.append((char) v);
            } else {
                if (cur.length() >= minLen) out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() >= minLen) out.add(cur.toString());
    }
}
