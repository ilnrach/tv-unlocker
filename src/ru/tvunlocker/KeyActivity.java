package ru.tvunlocker;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Экран «Заводские клавиши»: инжекция KeyEvent через заводской
 * IFactoryService.handleKeyEvents / handleKeyEventsBefore.
 * v3.4: добавлена МАТРИЦА форм событий (deviceId/scanCode/flags/source ×
 * коды клавиш, включая hardware-коды корпуса из factory_keypad.ini),
 * чтение mstarkeymap.csv и логирование результата.
 */
public class KeyActivity extends Activity {

    /** IP компьютера-приёмника, поменяйте на свой перед сборкой. */
    private static final String PC_UPLOAD_HOST = "192.168.1.100";

    private TextView status;
    private LinearLayout body;
    private Object svc;
    private Method mainM;
    private Method beforeM;
    private boolean connecting = false;
    private final StringBuilder keyLog = new StringBuilder();
    private final HashMap<Integer, Integer> scanMap = new HashMap<Integer, Integer>();
    private int shapeInUse = 1;
    private boolean scanMapLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Заводские клавиши v3.4");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(14, 18, 26));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(24, 24, 24, 48);
        scroll.addView(body);
        setContentView(scroll);

        TextView info = new TextView(this);
        info.setTextColor(Color.rgb(180, 200, 255));
        info.setTextSize(12);
        info.setTypeface(Typeface.MONOSPACE);
        info.setPadding(0, 0, 0, 12);
        info.setText("adb_enable.ini:\n" + readText("/vendor/tvconfig/config/adb/adb_enable.ini"));
        body.addView(info);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, 8, 0, 12);
        status.setText("Подключаюсь к заводскому сервису…");
        body.addView(status);

        addRow(new String[][]{
                {"[1 1 4 7]", "preset1147"},
                {"[MENU 1с ←↑←↑ BACK]", "presetCombo"},
                {"[SOURCE 1147]", "presetSource1147"}
        });
        addRow(new String[][]{
                {"M МАТРИЦА ФОРМ", "matrix"},
                {"CSV mstarkeymap", "csv"},
                {"KEYPAD ini", "keypadini"}
        });
        addRow(new String[][]{
                {"MENU", "82"}, {"MENU 1с", "menu1s"}, {"SOURCE", "177"}, {"SOURCE2", "243"}
        });
        addRow(new String[][]{
                {"1", "8"}, {"2", "9"}, {"3", "10"}, {"4", "11"}, {"5", "12"}
        });
        addRow(new String[][]{
                {"6", "13"}, {"7", "14"}, {"8", "15"}, {"9", "16"}, {"0", "7"}
        });
        addRow(new String[][]{
                {"←", "21"}, {"↑", "19"}, {"↓", "20"}, {"→", "22"}, {"OK", "23"}
        });
        addRow(new String[][]{
                {"BACK", "4"}, {"EXIT", "111"}
        });

        connect();
    }

    private void addRow(String[][] buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(4, 6, 4, 6);
        for (String[] b : buttons) {
            Button btn = new Button(this);
            btn.setText(b[0]);
            btn.setTextSize(13);
            btn.setTag(b[1]);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onKey(v.getTag().toString());
                }
            });
            row.addView(btn);
        }
        body.addView(row);
    }

    private void say(String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(s);
            }
        });
    }

    private String readText(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return "(нет)";
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String s = bo.toString("UTF-8");
            return s.length() > 700 ? s.substring(0, 700) + "…" : s;
        } catch (Throwable t) {
            return "(ошибка: " + t.getMessage() + ")";
        }
    }

    private void saveLog(String name, String content) {
        // 1. Download
        try {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream fo = new FileOutputStream(new File(dir, name));
            fo.write(content.getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {
        }
        // 2. Съёмные тома (флешки): /storage/*
        try {
            File root = new File("/storage");
            File[] list = root.listFiles();
            if (list != null) {
                for (File v : list) {
                    String n = v.getName().toLowerCase();
                    if (n.equals("emulated") || n.equals("self")) continue;
                    if (!v.isDirectory()) continue;
                    try {
                        File dir = new File(v, "TVUnlocker");
                        if (!dir.exists()) dir.mkdirs();
                        FileOutputStream fo = new FileOutputStream(new File(dir, name));
                        fo.write(content.getBytes("UTF-8"));
                        fo.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 3. Папка приложения
        try {
            FileOutputStream fo = new FileOutputStream(
                    new File(getExternalFilesDir(null), name));
            fo.write(content.getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {
        }
        // 4. Автоотправка на ПК
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    "http://" + PC_UPLOAD_HOST + ":8888/upload?name="
                            + java.net.URLEncoder.encode(name, "UTF-8")).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(2500);
            c.setReadTimeout(8000);
            byte[] data = content.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(data.length);
            java.io.OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            c.getResponseCode();
        } catch (Throwable ignored) {
        }
    }

    private void connect() {
        if (svc != null || connecting) return;
        connecting = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                final Object s = FactoryUnlock.connect(KeyActivity.this, sb);
                if (s == null) {
                    say(sb + "\nСоединения нет — клавиши не работают.");
                    connecting = false;
                    return;
                }
                svc = s;
                Class<?> iface = svc.getClass().getInterfaces().length > 0
                        ? svc.getClass().getInterfaces()[0] : svc.getClass();
                for (Method m : iface.getMethods()) {
                    String n = m.getName().toLowerCase();
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 1 && KeyEvent.class.isAssignableFrom(ps[0])) {
                        if (n.contains("before")) beforeM = m;
                        else if (n.contains("handlekeyevent")) mainM = m;
                    }
                }
                say("Подключено: " + iface.getName()
                        + "\nhandleKeyEvents=" + (mainM != null)
                        + ", handleKeyEventsBefore=" + (beforeM != null)
                        + "\nФорма событий: " + shapeInUse
                        + "\nЖми клавиши или M МАТРИЦА ФОРМ.");
                connecting = false;
            }
        }).start();
    }

    private void onKey(String tag) {
        if (svc == null) {
            say("Ещё подключаюсь…");
            connect();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (tag.equals("matrix")) { runMatrix(); return; }
                    if (tag.equals("csv")) { showCsv(); return; }
                    if (tag.equals("keypadini")) {
                        say("keypad.ini:\n" + readText(
                                "/vendor/tvconfig/config/factory_keypad.ini"));
                        return;
                    }
                    if (tag.startsWith("preset")) {
                        if (tag.equals("preset1147")) {
                            injectCode(new int[]{8, 8, 11, 14});
                        } else if (tag.equals("presetCombo")) {
                            injectKey(82, 1100);
                            Thread.sleep(300);
                            injectKey(21, 0); Thread.sleep(250);
                            injectKey(19, 0); Thread.sleep(250);
                            injectKey(21, 0); Thread.sleep(250);
                            injectKey(19, 0); Thread.sleep(250);
                            injectKey(4, 0);
                        } else {
                            injectKey(177, 0);
                            Thread.sleep(400);
                            injectCode(new int[]{8, 8, 11, 14});
                        }
                        saveLog("tvunlocker_keys.txt", keyLog.toString());
                        return;
                    }
                    if (tag.equals("menu1s")) {
                        injectKey(82, 1100);
                        saveLog("tvunlocker_keys.txt", keyLog.toString());
                        return;
                    }
                    injectKey(Integer.parseInt(tag), 0);
                    saveLog("tvunlocker_keys.txt", keyLog.toString());
                } catch (Throwable t) {
                    say("Ошибка: " + t.getMessage());
                }
            }
        }).start();
    }

    private void showCsv() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String csv = readText("/vendor/tvconfig/config/mstarkeymap.csv");
                keyLog.append("== mstarkeymap.csv ==\n").append(csv).append("\n\n");
                saveLog("tvunlocker_keys.txt", keyLog.toString());
                parseScanMap(csv);
                say("mstarkeymap.csv (первые 700 символов):\n"
                        + csv.substring(0, Math.min(700, csv.length()))
                        + "\n\nПрочитан и сохранён в лог.");
            }
        }).start();
    }

    /** Пытается вытащить пары scanCode->keyCode из CSV. */
    private void parseScanMap(String csv) {
        try {
            for (String line : csv.split("\r?\n")) {
                java.util.regex.Matcher m1 = java.util.regex.Pattern
                        .compile("(\\d+)\\D+(\\d+)").matcher(line);
                if (m1.find()) {
                    int scan = Integer.parseInt(m1.group(1));
                    int code = Integer.parseInt(m1.group(2));
                    if (code > 0 && code < 300 && scan > 0) scanMap.put(code, scan);
                }
            }
            scanMapLoaded = true;
        } catch (Throwable ignored) {
        }
    }

    /** Событие в заданной форме. */
    private KeyEvent makeEvent(int action, int code, int shape, long at) {
        int scan = scanMap.containsKey(code) ? scanMap.get(code) : 0;
        switch (shape) {
            case 2:
                return new KeyEvent(at, at, action, code, 0, 0, 0, scan,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            case 3:
                return new KeyEvent(at, at, action, code, 0, 0, 1, scan,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            case 4:
                return new KeyEvent(at, at, action, code, 0, 0, -1, scan,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            case 5:
                return new KeyEvent(at, at, action, code, 0, 0, 2, scan,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            default:
                return new KeyEvent(at, at, action, code, 0);
        }
    }

    private boolean sendKeyOnce(int keyCode, int shape, long holdMs) throws Exception {
        if (svc == null) throw new Exception("сервис не подключен");
        long now = SystemClock.uptimeMillis();
        boolean r1 = false;
        boolean r2 = false;
        if (beforeM != null) {
            KeyEvent d = makeEvent(KeyEvent.ACTION_DOWN, keyCode, shape, now);
            r1 = Boolean.TRUE.equals(beforeM.invoke(svc, d));
            KeyEvent u = makeEvent(KeyEvent.ACTION_UP, keyCode, shape, now + 60);
            beforeM.invoke(svc, u);
        }
        if (mainM != null) {
            KeyEvent d = makeEvent(KeyEvent.ACTION_DOWN, keyCode, shape, now + 30);
            r2 = Boolean.TRUE.equals(mainM.invoke(svc, d));
            if (holdMs > 0) Thread.sleep(holdMs);
            KeyEvent u = makeEvent(KeyEvent.ACTION_UP, keyCode, shape, now + holdMs + 90);
            mainM.invoke(svc, u);
        }
        return r1 || r2;
    }

    /** Инжектирует клавишу текущей формой (DOWN, holdMs, UP). */
    private void injectKey(int keyCode, long holdMs) throws Exception {
        boolean r = sendKeyOnce(keyCode, shapeInUse, holdMs);
        keyLog.append("key ").append(keyCode).append(" shape=").append(shapeInUse)
              .append(" -> ").append(r).append("\n");
        say("key " + keyCode + " (форма " + shapeInUse + "): " + (r ? "ПРИНЯТО!" : "false"));
    }

    private void injectCode(int[] codes) throws Exception {
        for (int c : codes) {
            injectKey(c, 0);
            Thread.sleep(200);
        }
        say("Код введён формой " + shapeInUse + ": смотри экран ТВ");
    }

    /** МАТРИЦА: коды × формы, поиск первой «ПРИНЯТО». */
    private void runMatrix() {
        say("Матрица форм… идёт перебор");
        StringBuilder sb = new StringBuilder("== МАТРИЦА ==\n");
        int[] codes = {8, 70, 161, 162, 163, 165, 166, 168, 82, 177, 19, 21, 23, 4};
        boolean found = false;
        int foundShape = 0, foundCode = 0;
        try {
            outer:
            for (int shape = 1; shape <= 5 && !found; shape++) {
                for (int code : codes) {
                    boolean r = false;
                    try {
                        r = sendKeyOnce(code, shape, 0);
                    } catch (Throwable t) {
                        sb.append("S").append(shape).append(" K").append(code)
                          .append(" ERR ").append(t.getMessage()).append("\n");
                        continue;
                    }
                    sb.append("S").append(shape).append(" K").append(code)
                      .append(" -> ").append(r).append("\n");
                    if (r) {
                        found = true;
                        foundShape = shape;
                        foundCode = code;
                        break outer;
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("ERR ").append(t).append("\n");
        }
        if (found) {
            shapeInUse = foundShape;
            sb.append("\n>>> НАЙДЕНО: форма ").append(foundShape)
              .append(" принимает код ").append(foundCode)
              .append("!\nТеперь жми [1 1 4 7] или пресеты — они пойдут этой формой.\n");
            say(">>> СЕРВИС ПРИНЯЛ: форма " + foundShape + ", код " + foundCode
                    + "\nТеперь жми [1 1 4 7] и пресеты!");
        } else {
            sb.append("\nНи одна форма не принята. Жми CSV mstarkeymap —\n");
            sb.append("вытащим реальные scan-коды пульта и повторим.\n");
            say("Матрица: ни одна форма не принята.\nЖми [CSV mstarkeymap] и пришли мне лог.");
        }
        keyLog.append(sb).append("\n");
        saveLog("tvunlocker_matrix.txt", keyLog.toString());
    }
}
