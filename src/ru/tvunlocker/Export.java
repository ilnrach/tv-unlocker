package ru.tvunlocker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v4.4: выгрузка прошивки на флешку без root — системные APK, jar из framework
 * и читаемые конфиги. Для прошивок, которых нет в открытом доступе: по выгрузке
 * прошивку разбирают на компьютере (манифесты, сервисы, адреса обновлений).
 */
public class Export {

    /** Получает строку прогресса для показа на экране. */
    public interface Progress {
        void update(String line);
    }

    private static final long MB = 1024L * 1024;

    private static final long MAX_APK = 300 * MB;
    private static final long MAX_JAR = 80 * MB;
    private static final long MAX_CFG = 5 * MB;
    private static final long MAX_CFG_TOTAL = 200 * MB;
    private static final int MAX_CFG_FILES = 4000;
    /** Не заполняем носитель до конца. */
    private static final long FREE_RESERVE = 200 * MB;
    private static final int MAX_TREE_LINES = 30000;
    private static final int MAX_CFG_DEPTH = 5;

    private static final String[] JAR_DIRS = {
            "/system/framework", "/system_ext/framework", "/product/framework", "/vendor/framework"
    };
    private static final String[] CFG_DIRS = {
            "/system/etc", "/vendor/etc", "/product/etc", "/odm/etc", "/system_ext/etc",
            "/system/usr/keylayout", "/system/usr/idc", "/vendor/usr/keylayout",
            "/product/usr/keylayout"
    };
    private static final String[] PROP_FILES = {
            "/system/build.prop", "/system/default.prop", "/system/etc/prop.default",
            "/vendor/build.prop", "/vendor/default.prop", "/odm/etc/build.prop",
            "/product/build.prop", "/product/etc/build.prop", "/system_ext/build.prop"
    };
    private static final String[] PROC_FILES = {
            "/proc/version", "/proc/cmdline", "/proc/mounts", "/proc/partitions",
            "/proc/cpuinfo", "/proc/meminfo"
    };
    /** {каталог, глубина} для tree.txt. */
    private static final String[][] LIST_DIRS = {
            {"/system/app", "3"}, {"/system/priv-app", "3"}, {"/system/framework", "1"},
            {"/system/etc", "3"}, {"/system/lib", "1"}, {"/system/lib64", "1"},
            {"/system/bin", "1"}, {"/system/xbin", "1"}, {"/system/usr", "3"},
            {"/vendor/app", "3"}, {"/vendor/priv-app", "3"}, {"/vendor/etc", "4"},
            {"/vendor/firmware", "1"}, {"/vendor/bin", "1"}, {"/vendor/lib", "1"},
            {"/vendor/lib64", "1"},
            {"/product/app", "3"}, {"/product/priv-app", "3"}, {"/product/etc", "3"},
            {"/product/overlay", "2"}, {"/product/lib", "1"}, {"/product/lib64", "1"},
            {"/product/framework", "1"},
            {"/system_ext/app", "3"}, {"/system_ext/priv-app", "3"}, {"/system_ext/etc", "3"},
            {"/system_ext/framework", "1"},
            {"/odm/etc", "3"}, {"/odm/app", "3"}, {"/odm/lib", "1"}, {"/odm/lib64", "1"},
            {"/oem", "3"}, {"/cache", "2"}, {"/data/local/tmp", "1"},
            {"/dev/block/by-name", "1"}, {"/storage/emulated/0", "1"}
    };
    private static final String CFG_EXT =
            "xml prop rc conf cfg ini kl idc json txt pem te fec list csv sh mk avb cm wf vm perm version types";

    private static final class Copied {
        long bytes;
        String error;
    }

    private final StringBuilder log = new StringBuilder();
    private final StringBuilder packages = new StringBuilder();
    private final StringBuilder tree = new StringBuilder();
    private int treeLines = 0;

    private File base;
    private int apkOk, apkFail, jarOk, jarFail, cfgOk, cfgFail;
    private long apkBytes, jarBytes, cfgBytes;

    /**
     * Выгружает прошивку в base. Возвращает первую строку-итог для показа на экране
     * и путь; полный протокол — в tvunlocker_export.txt внутри base.
     */
    public static String run(File base, String where, Context ctx, Progress progress) {
        return new Export().go(ctx, base, where, progress);
    }

    private String go(Context ctx, File base, String where, Progress progress) {
        this.base = base;
        log.append("TV Unlocker v4.4 export\n");
        log.append("Дата: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date())).append("\n");
        log.append("Модель: ").append(Build.MODEL).append("\n");
        log.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
        log.append("Куда: ").append(base.getAbsolutePath()).append("\n\n");

        try {
            if (!base.exists() && !base.mkdirs()) {
                throw new IOException("не удалось создать каталог " + base);
            }
            copyListed(PROP_FILES, "cfg");
            copyListed(PROC_FILES, "proc");
            tick(progress, "системные APK");
            copyApks(ctx, progress);
            tick(progress, "framework");
            copyJars();
            tick(progress, "конфиги");
            copyCfgs(progress);
            tick(progress, "дерево каталогов");
            makeTree();
            writeText(new File(base, "packages.txt"), packages.toString());
        } catch (Throwable t) {
            log.append("ОШИБКА: ").append(t).append("\n");
        }

        String summary = "Готово: APK " + apkOk + (apkFail > 0 ? " (не скопировано " + apkFail + ")" : "")
                + ", jar " + jarOk + ", конфигов " + cfgOk
                + "; " + ((apkBytes + jarBytes + cfgBytes) / MB) + " МБ -> " + where;
        log.append("\n").append(summary).append("\n");
        writeText(new File(base, "tvunlocker_export.txt"), log.toString());
        return summary + "\n" + base.getAbsolutePath();
    }

    private static void tick(Progress progress, String line) {
        if (progress != null) progress.update(line);
    }

    private void copyApks(Context ctx, Progress progress) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> packs;
        try {
            packs = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            log.append("список пакетов недоступен: ").append(t).append("\n");
            return;
        }
        Collections.sort(packs, new Comparator<PackageInfo>() {
            @Override
            public int compare(PackageInfo a, PackageInfo b) {
                return a.packageName.compareTo(b.packageName);
            }
        });

        File apks = new File(base, "apks");
        int seen = 0;
        for (PackageInfo pi : packs) {
            ApplicationInfo app = pi.applicationInfo;
            if (pi.packageName == null || app == null || app.sourceDir == null) continue;

            packages.append(pi.packageName)
                    .append(" | uid ").append(app.uid)
                    .append(" | sdk ").append(app.targetSdkVersion)
                    .append(" | ").append(pi.versionName).append(" (").append(pi.versionCode).append(")")
                    .append(" | ").append(describe(app))
                    .append(" | native ").append(app.nativeLibraryDir)
                    .append(" | ").append(app.sourceDir)
                    .append(meta(app))
                    .append("\n");

            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!system || "ru.tvunlocker".equals(pi.packageName)) continue;

            List<String> sources = new ArrayList<String>();
            sources.add(app.sourceDir);
            if (app.splitSourceDirs != null) {
                for (String s : app.splitSourceDirs) if (s != null) sources.add(s);
            }
            File dir = new File(apks, pi.packageName);
            for (String src : sources) {
                Copied r = copy(new File(src), new File(dir, new File(src).getName()), MAX_APK);
                if (r.error == null) {
                    apkOk++;
                    apkBytes += r.bytes;
                } else {
                    apkFail++;
                    log.append("APK не скопирован: ").append(src).append(" — ").append(r.error).append("\n");
                }
            }
            if (++seen % 5 == 0) tick(progress, "APK " + seen + "/" + packs.size());
        }
        log.append("пакетов в списке: ").append(packs.size())
           .append(", APK скопировано: ").append(apkOk)
           .append(", не скопировано: ").append(apkFail).append("\n");
    }

    private static String describe(ApplicationInfo app) {
        StringBuilder s = new StringBuilder();
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) s.append("S");
        if ((app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) s.append("U");
        if ((app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) s.append("D");
        if ((app.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0) s.append("T");
        if (app.sourceDir != null && app.sourceDir.contains("/priv-app/")) s.append("P");
        return s.length() == 0 ? "-" : s.toString();
    }

    /** meta-data из манифеста: там бывают каналы и адреса обновлений. */
    private static String meta(ApplicationInfo app) {
        Bundle md = app.metaData;
        if (md == null || md.isEmpty()) return "";
        StringBuilder s = new StringBuilder(" | meta");
        for (String key : md.keySet()) {
            Object v = md.get(key);
            String text = (v instanceof String || v instanceof Integer || v instanceof Boolean)
                    ? String.valueOf(v) : (v == null ? "null" : v.getClass().getSimpleName());
            if (text.length() > 200) text = text.substring(0, 200) + "…";
            s.append(' ').append(key).append('=').append(text).append(';');
            if (s.length() > 2000) { s.append("…"); break; }
        }
        return s.toString();
    }

    private void copyJars() {
        File jars = new File(base, "jars");
        for (String dirPath : JAR_DIRS) {
            File[] files = new File(dirPath).listFiles();
            if (files == null) continue;
            for (File src : files) {
                if (!src.isFile() || !src.getName().endsWith(".jar")) continue;
                String name = dirPath.substring(1).replace('/', '_') + "__" + src.getName();
                Copied r = copy(src, new File(jars, name), MAX_JAR);
                if (r.error == null) {
                    jarOk++;
                    jarBytes += r.bytes;
                } else {
                    jarFail++;
                    log.append("jar не скопирован: ").append(src.getAbsolutePath())
                       .append(" — ").append(r.error).append("\n");
                }
            }
        }
        log.append("jar скопировано: ").append(jarOk)
           .append(", не скопировано: ").append(jarFail).append("\n");
    }

    private void copyCfgs(Progress progress) {
        for (String root : CFG_DIRS) {
            walk(new File(root), 0, progress);
        }
        log.append("конфигов скопировано: ").append(cfgOk)
           .append(", не скопировано: ").append(cfgFail).append("\n");
    }

    private void walk(File dir, int depth, Progress progress) {
        if (depth > MAX_CFG_DEPTH || cfgOk >= MAX_CFG_FILES || cfgBytes >= MAX_CFG_TOTAL) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File src : kids) {
            if (cfgOk >= MAX_CFG_FILES || cfgBytes >= MAX_CFG_TOTAL) return;
            try {
                if (src.isDirectory()) {
                    walk(src, depth + 1, progress);
                    continue;
                }
                if (!src.isFile() || !wantedCfg(src.getName(), src.length())) continue;
                String rel = src.getAbsolutePath().substring(1);
                Copied r = copy(src, new File(new File(base, "cfg"), rel), MAX_CFG);
                if (r.error == null) {
                    cfgOk++;
                    cfgBytes += r.bytes;
                    if (cfgOk % 200 == 0) tick(progress, "конфиги " + cfgOk);
                } else {
                    cfgFail++;
                }
            } catch (Throwable ignored) {
                // один сбойный файл не должен останавливать выгрузку
            }
        }
    }

    private static boolean wantedCfg(String name, long len) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return len <= MB;   // fstab, hosts и другие файлы без расширения
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        return (" " + CFG_EXT + " ").contains(" " + ext + " ");
    }

    /** Копирует небольшие служебные файлы (build.prop, /proc/...) в подкаталог. */
    private void copyListed(String[] paths, String sub) {
        for (String p : paths) {
            Copied r = copy(new File(p), new File(new File(base, sub), p.substring(1).replace('/', '_')), MAX_CFG);
            if (r.error == null) {
                cfgOk++;
                cfgBytes += r.bytes;
            }
        }
    }

    private void makeTree() {
        for (String[] e : LIST_DIRS) {
            tree.append("== ").append(e[0]).append("\n");
            File dir = new File(e[0]);
            File[] kids = dir.listFiles();
            if (kids == null) {
                tree.append("?\t").append(e[0]).append(" (нет доступа или каталога)\n");
                continue;
            }
            listDir(dir, Integer.parseInt(e[1]));
        }
        writeText(new File(base, "tree.txt"), tree.toString());
    }

    private void listDir(File dir, int depth) {
        if (depth < 0 || treeLines >= MAX_TREE_LINES) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (File f : kids) {
            if (treeLines++ >= MAX_TREE_LINES) return;
            String p = f.getAbsolutePath();
            try {
                if (f.isDirectory()) {
                    tree.append("<dir>\t").append(p).append("\n");
                    listDir(f, depth - 1);
                } else {
                    tree.append(f.length()).append('\t').append(p).append("\n");
                }
            } catch (Throwable t) {
                tree.append("?\t").append(p).append("\n");
            }
        }
    }

    /** Копирует файл потоками (память не зависит от размера); ошибка — в поле error. */
    private static Copied copy(File src, File dst, long maxBytes) {
        Copied r = new Copied();
        InputStream in = null;
        OutputStream out = null;
        try {
            long len = src.length();
            if (len <= 0) throw new IOException("пустой или недоступный");
            if (len > maxBytes) throw new IOException("больше " + (maxBytes / MB) + " МБ");
            File parent = dst.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) throw new IOException("каталог не создан");
            if (parent.getUsableSpace() < len + FREE_RESERVE) throw new IOException("мало места на носителе");
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > maxBytes) throw new IOException("файл вырос при копировании");
            }
            out.flush();
            r.bytes = total;
        } catch (Throwable t) {
            dst.delete();
            r.error = String.valueOf(t.getMessage() == null ? t : t.getMessage());
        } finally {
            close(in);
            close(out);
        }
        return r;
    }

    private static void writeText(File f, String text) {
        OutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) return;
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            close(out);
        }
    }

    private static void close(InputStream s) {
        try {
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }

    private static void close(OutputStream s) {
        try {
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }
}
