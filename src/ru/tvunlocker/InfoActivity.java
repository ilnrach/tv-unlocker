package ru.tvunlocker;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Экран диагностики: платформа устройства и состояние ADB.
 */
public class InfoActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Платформа и ADB");

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(14, 18, 26));
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(24, 24, 24, 48);
        t.setText(build());
        sv.addView(t);
        setContentView(sv);
    }

    private String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("== Устройство ==\n");
        sb.append("Модель: ").append(android.os.Build.MODEL).append("\n");
        sb.append("Производитель: ").append(android.os.Build.MANUFACTURER).append("\n");
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
          .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("Fingerprint: ").append(android.os.Build.FINGERPRINT).append("\n\n");

        sb.append("== ADB ==\n");
        try {
            int v = android.provider.Settings.Global.getInt(
                    getContentResolver(), "adb_enabled", -1);
            sb.append("adb_enabled: ").append(v)
              .append(v == 1 ? " (включён)" : " (ВЫКЛЮЧЕН — нужно включить!)").append("\n");
        } catch (Throwable t) {
            sb.append("adb_enabled: ошибка чтения\n");
        }
        String[] props = {
                "ro.build.type", "ro.debuggable", "ro.secure", "ro.adb.secure",
                "persist.adb.tcp.port", "service.adb.tcp.port",
                "persist.service.adb.enable", "persist.usb.config",
                "ro.product.manufacturer", "ro.product.device", "ro.product.board",
                "ro.board.platform", "ro.hardware", "ro.soc.model", "ro.chipname"
        };
        for (String p : props) {
            sb.append(p).append(": ").append(safeProp(p)).append("\n");
        }

        sb.append("\nПодсказка: если adb_enabled=0 — включайте через\n");
        sb.append("фабричное меню (пункт factory в списке) или код пульта.\n");
        return sb.toString();
    }

    private static String safeProp(String name) {
        try {
            String v = prop(name);
            return v.isEmpty() ? "(нет)" : v;
        } catch (Throwable t) {
            return "(ошибка)";
        }
    }

    /** Читает одну системную проперти через getprop. */
    static String prop(String name) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"getprop", name});
        String s = readAll(p);
        try { p.waitFor(); } catch (InterruptedException ignored) {}
        return s.trim();
    }

    /** Полный дамп пропerty (getprop без аргументов). */
    static String propAll() throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"getprop"});
        String s = readAll(p);
        try { p.waitFor(); } catch (InterruptedException ignored) {}
        return s;
    }

    private static String readAll(Process p) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        r.close();
        return sb.toString();
    }
}
