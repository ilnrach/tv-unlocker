package ru.tvunlocker;

import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TV Unlocker: находит и открывает скрытые сервисные/фабричные компоненты,
 * показывает диагностику платформы, пробивает легитимные пути включения ADB
 * и выгружает все данные: на флешку и на ПК по сети.
 */
public class TVUnlocker extends ListActivity {

    private static final String WRITE_PERM = "android.permission.WRITE_EXTERNAL_STORAGE";
    private static final int REQ_WRITE = 1;

    private static final int ACTION_TOGGLE = 1;
    private static final int ACTION_INFO = 2;
    private static final int ACTION_REPORT = 3;
    private static final int ACTION_PROBE = 4;
    private static final int ACTION_SENDPC = 5;
    private static final int ACTION_RECON = 6;
    private static final int ACTION_UNLOCK = 7;
    private static final int ACTION_DIAG = 8;
    private static final int ACTION_KEYS = 9;
    private static final int ACTION_API = 10;
    private static final int ACTION_DRILL = 11;
    private static final int ACTION_UNLOCK2 = 12;
    private static final int ACTION_TUNE = 13;

    /** IP компьютера для приёма файлов. */
    private static final String PC_PRIMARY = "192.168.1.100"; // IP компьютера с запущенным приёмником, поменяйте на свой
    private static final String[] PC_CANDIDATES = { PC_PRIMARY, "127.0.0.1", "10.0.2.2" };
    private static final int PC_PORT = 8888;

    private static final String REPORT_NAME = "tvunlocker_report.txt";
    private static final String PROBE_NAME = "tvunlocker_probe.txt";

    /** Ключевые слова поиска: имя пакета или активити содержит любое из них. */
    private static final String[] KEYS = {
            "factory", "cvte", "mediatek", "mstar", "designmenu", "mainmenu",
            "development", "developer", "debug", "adb", "engineering",
            "servicemenu", "service_menu"
    };

    /** Префиксы пакетов платформы ТВ для режима полного дампа. */
    private static final String[] VENDOR_PREFIXES = {
            "com.cvte.", "cn.cvte.", "com.mediatek.", "com.mstar.", "com.stb.",
            "com.yandex.tv", "com.android.tv.settings", "com.coocaa."
    };

    private static class Item {
        String pkg;
        String cls;
        boolean exported;
        int action;

        @Override
        public String toString() {
            if (action > 0) return cls;
            return cls + (exported ? "" : "  [закрыта]") + "\n" + pkg;
        }
    }

    private ArrayAdapter<Item> adapter;
    private TextView header;

    private final List<Item> displayItems = new ArrayList<Item>();
    private final List<Item> serviceItems = new ArrayList<Item>();
    private final List<Item> vendorItems = new ArrayList<Item>();
    private int vendorExported = 0;
    private boolean showVendor = false;
    private boolean scanDone = false;
    private boolean probeAfterGrant = false;
    private boolean sendAfterGrant = false;
    private boolean reconAfterGrant = false;
    private boolean unlockAfterGrant = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        header = new TextView(this);
        header.setTextSize(20);
        header.setTextColor(Color.WHITE);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setPadding(28, 28, 28, 28);
        header.setText("TV Unlocker v4.3\nСканирую приложения…");

        getListView().addHeaderView(header, null, false);
        getListView().setBackgroundColor(Color.rgb(14, 18, 26));

        setListAdapter(adapter = new ArrayAdapter<Item>(
                this, android.R.layout.simple_list_item_1, displayItems) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView t = (TextView) v.findViewById(android.R.id.text1);
                t.setTextColor(Color.WHITE);
                t.setTextSize(17);
                t.setTypeface(Typeface.MONOSPACE);
                return v;
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                scan();
            }
        }).start();
    }

    private void scan() {
        PackageManager pm = getPackageManager();
        List<PackageInfo> packs;
        try {
            packs = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES);
        } catch (Exception e) {
            packs = null;
        }

        if (packs != null) {
            for (PackageInfo pi : packs) {
                if (pi == null || pi.activities == null) continue;
                String pkg = pi.packageName == null ? "" : pi.packageName;
                boolean vendor = isVendor(pkg);
                for (ActivityInfo ai : pi.activities) {
                    if (ai == null || ai.name == null) continue;

                    if (vendor) {
                        Item it = new Item();
                        it.pkg = pkg;
                        it.cls = ai.name;
                        it.exported = ai.exported;
                        if (it.exported) vendorExported++;
                        vendorItems.add(it);
                    }

                    String hay = (pkg + " " + ai.name).toLowerCase();
                    for (String k : KEYS) {
                        if (hay.contains(k)) {
                            Item it = new Item();
                            it.pkg = pkg;
                            it.cls = ai.name;
                            it.exported = ai.exported;
                            serviceItems.add(it);
                            break;
                        }
                    }
                }
            }
        }

        sortItems(serviceItems);
        sortItems(vendorItems);
        scanDone = true;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showService();
            }
        });
    }

    private static void sortItems(List<Item> list) {
        Collections.sort(list, new java.util.Comparator<Item>() {
            private int prio(String s) {
                s = s.toLowerCase();
                if (s.contains("factory") || s.contains("designmenu")
                        || s.contains("mainmenu") || s.contains("engineering")) return 0;
                if (s.contains("develop")) return 1;
                if (s.contains("debug") || s.contains("adb") || s.contains("cvte")
                        || s.contains("mediatek") || s.contains("mstar")) return 2;
                return 3;
            }
            @Override
            public int compare(Item a, Item b) {
                if (a.exported != b.exported) return a.exported ? -1 : 1;
                int pa = prio(a.cls + " " + a.pkg);
                int pb = prio(b.cls + " " + b.pkg);
                if (pa != pb) return pa - pb;
                int c = a.pkg.compareTo(b.pkg);
                return c != 0 ? c : a.cls.compareTo(b.cls);
            }
        });
    }

    private boolean isVendor(String pkg) {
        for (String p : VENDOR_PREFIXES) {
            if (pkg.startsWith(p)) return true;
        }
        return false;
    }

    private int countExported(List<Item> list) {
        int n = 0;
        for (Item it : list) if (it.exported) n++;
        return n;
    }

    private Item actionItem(int type, String label) {
        Item it = new Item();
        it.action = type;
        it.cls = label;
        return it;
    }

    private void showService() {
        showVendor = false;
        displayItems.clear();
        displayItems.add(actionItem(ACTION_TOGGLE,
                "► Показать ВСЕ активности платформы ТВ (CVTE/MTK/Yandex): "
                        + vendorItems.size() + " шт."));
        displayItems.add(actionItem(ACTION_INFO, "[i] Инфо о платформе и состоянии ADB"));
        displayItems.add(actionItem(ACTION_REPORT, "[S] Сохранить отчёты (на флешку и в Download)"));
        displayItems.add(actionItem(ACTION_PROBE,
                "[P] ПРОБИВКА ADB (настройки/приёмники/сервисы/провайдеры)"));
        displayItems.add(actionItem(ACTION_SENDPC,
                "[U] ОТПРАВИТЬ ВСЁ НА ПК " + PC_PRIMARY + ":" + PC_PORT));
        displayItems.add(actionItem(ACTION_RECON,
                "[R] РЕКОН: манифесты/dex прошивки (для v3)"));
        displayItems.add(actionItem(ACTION_UNLOCK,
                "[X] РАЗБЛОКИРОВКА (заводской execShellCmd)"));
        displayItems.add(actionItem(ACTION_DIAG,
                "[D] ДИАГНОСТИКА 3.1 (bind/команды/tvconfig/провайдеры)"));
        displayItems.add(actionItem(ACTION_KEYS,
                "[K] ЗАВОДСКИЕ КЛАВИШИ (инжекция: цифры/1147)"));
        displayItems.add(actionItem(ACTION_API,
                "[V] TV API: подключение к cvte-api-service"));
        displayItems.add(actionItem(ACTION_DRILL,
                "[F] ФАБРИКА: обход всех под-API TV (Debug/Properties/AutoTest)"));
        displayItems.add(actionItem(ACTION_UNLOCK2,
                "[G] РАБОЧАЯ РАЗБЛОЖИКА: ctl.start adbd через SystemProperties"));
        displayItems.add(actionItem(ACTION_TUNE,
                "[T] ТЮНИНГ картинки и звука"));
        displayItems.addAll(serviceItems);
        adapter.notifyDataSetChanged();
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3\n");
        sb.append("Модель: ").append(Build.MODEL).append("\n");
        sb.append("Сервисных активностей: ").append(serviceItems.size())
          .append(" (запускаемых: ").append(countExported(serviceItems)).append(")\n");
        sb.append("Стрелки — выбор, OK — запустить");
        header.setText(sb.toString());
        getListView().setSelection(0);
    }

    private void showVendor() {
        showVendor = true;
        displayItems.clear();
        displayItems.add(actionItem(ACTION_TOGGLE, "◄ Вернуться к сервисному списку"));
        displayItems.add(actionItem(ACTION_INFO, "[i] Инфо о платформе и состоянии ADB"));
        displayItems.add(actionItem(ACTION_REPORT, "[S] Сохранить отчёты (на флешку и в Download)"));
        displayItems.add(actionItem(ACTION_PROBE,
                "[P] ПРОБИВКА ADB (настройки/приёмники/сервисы/провайдеры)"));
        displayItems.add(actionItem(ACTION_SENDPC,
                "[U] ОТПРАВИТЬ ВСЁ НА ПК " + PC_PRIMARY + ":" + PC_PORT));
        displayItems.add(actionItem(ACTION_RECON,
                "[R] РЕКОН: манифесты/dex прошивки (для v3)"));
        displayItems.add(actionItem(ACTION_UNLOCK,
                "[X] РАЗБЛОКИРОВКА (заводской execShellCmd)"));
        displayItems.add(actionItem(ACTION_DIAG,
                "[D] ДИАГНОСТИКА 3.1 (bind/команды/tvconfig/провайдеры)"));
        displayItems.add(actionItem(ACTION_KEYS,
                "[K] ЗАВОДСКИЕ КЛАВИШИ (инжекция: цифры/1147)"));
        displayItems.add(actionItem(ACTION_API,
                "[V] TV API: подключение к cvte-api-service"));
        displayItems.add(actionItem(ACTION_DRILL,
                "[F] ФАБРИКА: обход всех под-API TV (Debug/Properties/AutoTest)"));
        displayItems.add(actionItem(ACTION_UNLOCK2,
                "[G] РАБОЧАЯ РАЗБЛОЖИКА: ctl.start adbd через SystemProperties"));
        displayItems.addAll(vendorItems);
        adapter.notifyDataSetChanged();
        StringBuilder sb = new StringBuilder();
        sb.append("ВСЕ активности платформы ТВ\n");
        sb.append("Всего: ").append(vendorItems.size())
          .append(" (запускаемых: ").append(vendorExported).append(")\n");
        sb.append("[закрыта] = система не даст запустить");
        header.setText(sb.toString());
        getListView().setSelection(0);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        int pos = position - getListView().getHeaderViewsCount();
        if (pos < 0 || pos >= displayItems.size()) return;
        Item it = displayItems.get(pos);

        if (it.action == ACTION_TOGGLE) {
            if (showVendor) showService();
            else showVendor();
            return;
        }
        if (it.action == ACTION_INFO) {
            try {
                startActivity(new Intent(this, InfoActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (it.action == ACTION_REPORT) {
            saveReport();
            return;
        }
        if (it.action == ACTION_PROBE) {
            runProbe();
            return;
        }
        if (it.action == ACTION_SENDPC) {
            sendPC();
            return;
        }
        if (it.action == ACTION_RECON) {
            runRecon();
            return;
        }
        if (it.action == ACTION_UNLOCK) {
            runUnlock();
            return;
        }
        if (it.action == ACTION_DIAG) {
            runDiag();
            return;
        }
        if (it.action == ACTION_KEYS) {
            try {
                startActivity(new Intent(this, KeyActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (it.action == ACTION_API) {
            runApi();
            return;
        }
        if (it.action == ACTION_DRILL) {
            runDrill();
            return;
        }
        if (it.action == ACTION_UNLOCK2) {
            runGold();
            return;
        }
        if (it.action == ACTION_TUNE) {
            runTune();
            return;
        }
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(it.pkg, it.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this,
                    "Не запускается: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ==================== Разрешение на запись ====================

    private boolean needWritePerm() {
        return Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(WRITE_PERM) != PackageManager.PERMISSION_GRANTED;
    }

    private void askWritePerm() {
        requestPermissions(new String[]{WRITE_PERM}, REQ_WRITE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if (requestCode == REQ_WRITE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (probeAfterGrant) {
                    probeAfterGrant = false;
                    doProbe();
                } else if (sendAfterGrant) {
                    sendAfterGrant = false;
                    doSendPC();
                } else if (reconAfterGrant) {
                    reconAfterGrant = false;
                    doRecon();
                } else if (unlockAfterGrant) {
                    unlockAfterGrant = false;
                    doUnlock();
                } else if (apiAfterGrant) {
                    apiAfterGrant = false;
                    doApi();
                } else if (drillAfterGrant) {
                    drillAfterGrant = false;
                    doDrill();
                } else if (goldAfterGrant) {
                    goldAfterGrant = false;
                    doGold();
                } else {
                    writeReport();
                }
            } else {
                Toast.makeText(this,
                        "Без разрешения не могу писать файлы",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ==================== Отчёт ====================

    private void saveReport() {
        if (!scanDone) {
            Toast.makeText(this, "Ещё сканирую, подождите", Toast.LENGTH_SHORT).show();
            return;
        }
        if (needWritePerm()) {
            askWritePerm();
            return;
        }
        writeReport();
    }

    private void writeReport() {
        Toast.makeText(this, "Собираю отчёт…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String content = buildReportContent();
                final StringBuilder paths = new StringBuilder();
                writeEverywhere(REPORT_NAME, content, paths);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this,
                                "Отчёт сохранён:\n" + paths, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ==================== Пробивка ====================

    private void runProbe() {
        if (needWritePerm()) {
            askWritePerm();
            probeAfterGrant = true;
            return;
        }
        doProbe();
    }

    private void doProbe() {
        Toast.makeText(this, "Пробиваю компоненты… до пары минут", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String report;
                try {
                    report = Probe.run(TVUnlocker.this);
                } catch (Throwable t) {
                    report = "ОШИБКА пробивки: " + t;
                }
                final StringBuilder paths = new StringBuilder();
                writeEverywhere(PROBE_NAME, report, paths);
                final boolean unlocked = report.contains("РАЗБЛОКИРОВАНО");
                final String msg = (unlocked
                        ? ">>> РАЗБЛОКИРОВАНО! adb_enabled=1, подключай adb connect <IP>:5555\n"
                        : "Пробивка завершена. adb_enabled=" + adbNow() + "\n")
                        + "Протокол:\n" + paths;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private int adbNow() {
        try {
            return android.provider.Settings.Global.getInt(
                    getContentResolver(), "adb_enabled", -1);
        } catch (Throwable t) {
            return -2;
        }
    }

    // ==================== ТЮНИНГ ====================

    private boolean tuneAfterGrant = false;

    private void runTune() {
        if (needWritePerm()) {
            askWritePerm();
            tuneAfterGrant = true;
            return;
        }
        doTune();
    }

    private void doTune() {
        Toast.makeText(this, "Тюнинг картинки/звука… до 3 минут", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String log;
                try {
                    log = ApiDrill.tune(TVUnlocker.this);
                } catch (Throwable t) {
                    log = "ОШИБКА: " + t;
                }
                final StringBuilder paths = new StringBuilder();
                writeEverywhere("tvunlocker_tune.txt", log, paths);
                String up = upload("tvunlocker_tune.txt", log);
                final String msg = "Тюнинг выполнен — протокол:\n" + paths + "\nНа ПК: " + up;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ==================== РАБОЧАЯ РАЗБЛОЖИКА (SystemProperties + KeyEvents) ====================

    private boolean goldAfterGrant = false;

    private void runGold() {
        if (needWritePerm()) {
            askWritePerm();
            goldAfterGrant = true;
            return;
        }
        doGold();
    }

    private void doGold() {
        Toast.makeText(this, "Рабочая разблокировка: свойства + клавиши…", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String log;
                try {
                    log = ApiDrill.gold(TVUnlocker.this);
                } catch (Throwable t) {
                    log = "ОШИБКА: " + t;
                }
                final StringBuilder paths = new StringBuilder();
                writeEverywhere("tvunlocker_gold.txt", log, paths);
                String up = upload("tvunlocker_gold.txt", log);
                final boolean unlocked = log.contains("РАЗБЛОКИРОВАНО");
                final String msg = (unlocked
                        ? ">>> РАЗБЛОКИРОВАНО! Выполните на компьютере: adb connect <IP>:5555\n"
                        : "Рабочая разблокировка выполнен — протокол:\n")
                        + paths + "\nНа ПК: " + up;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ==================== API Drill (обход под-API) ====================

    private boolean drillAfterGrant = false;

    private void runDrill() {
        if (needWritePerm()) {
            askWritePerm();
            drillAfterGrant = true;
            return;
        }
        doDrill();
    }

    private void doDrill() {
        Toast.makeText(this, "Обхожу все под-API платформы… до 5 минут",
                Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String log;
                try {
                    log = ApiDrill.run(TVUnlocker.this);
                } catch (Throwable t) {
                    log = "ОШИБКА: " + t;
                }
                final StringBuilder paths = new StringBuilder();
                writeEverywhere("tvunlocker_drill.txt", log, paths);
                String up = upload("tvunlocker_drill.txt", log);
                final boolean unlocked = log.contains("РАЗБЛОКИРОВАНО");
                final String msg = (unlocked
                        ? ">>> РАЗБЛОКИРОВАНО! Выполните на компьютере: adb connect <IP>:5555\n"
                        : "Обход под-API сохранён (полный список методов).\n")
                        + "Протокол:\n" + paths + "\nНа ПК: " + up;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ==================== TV API (cvte-api-service) ====================

    private boolean apiAfterGrant = false;

    private void runApi() {
        if (needWritePerm()) {
            askWritePerm();
            apiAfterGrant = true;
            return;
        }
        doApi();
    }

    private void doApi() {
        Toast.makeText(this, "Подключаюсь к TV API…", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String log;
                try {
                    log = ApiUnlock.run(TVUnlocker.this);
                } catch (Throwable t) {
                    log = "ОШИБКА: " + t;
                }
                final StringBuilder paths = new StringBuilder();
                writeEverywhere("tvunlocker_api.txt", log, paths);
                String up = upload("tvunlocker_api.txt", log);
                final boolean unlocked = log.contains("РАЗБЛОКИРОВАНО");
                final String msg = (unlocked
                        ? ">>> РАЗБЛОКИРОВАНО! Выполните на компьютере: adb connect <IP>:5555\n"
                        : "TV API-протокол сохранён (список методов внутри).\n")
                        + "Протокол:\n" + paths + "\nНа ПК: " + up;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ==================== Разблокировка ====================

    private void runUnlock() {
        if (needWritePerm()) {
            askWritePerm();
            unlockAfterGrant = true;
            return;
        }
        doUnlock();
    }

    private void doUnlock() {
        Toast.makeText(this, "Разблокирую через заводской сервис…", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String log;
                try {
                    log = FactoryUnlock.run(TVUnlocker.this);
                } catch (Throwable t) {
                    log = "ОШИБКА разблокировки: " + t;
                }
                final StringBuilder paths = new StringBuilder();
                writeEverywhere("tvunlocker_unlock.txt", log, paths);
                String up = upload("tvunlocker_unlock.txt", log);
                final boolean unlocked = log.contains("РАЗБЛОКИРОВАНО");
                final String msg = (unlocked
                        ? ">>> РАЗБЛОКИРОВАНО! adb_enabled=1 / порт 5555 открыт. Выполните на компьютере: adb connect <IP>:5555\n"
                        : "Заводская разблокировка не сработала — смотри протокол.\n")
                        + "Протокол:\n" + paths + "\nНа ПК: " + up;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ==================== Диагностика 3.1 ====================

    private boolean diagAfterGrant = false;

    private void runDiag() {
        if (needWritePerm()) {
            askWritePerm();
            diagAfterGrant = true;
            return;
        }
        doDiag();
    }

    private void doDiag() {
        Toast.makeText(this, "Диагностика… до 2-3 минут", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String log;
                try {
                    log = Diag.run(TVUnlocker.this);
                } catch (Throwable t) {
                    log = "ОШИБКА диагностики: " + t;
                }
                final StringBuilder paths = new StringBuilder();
                writeEverywhere("tvunlocker_diag.txt", log, paths);
                String up = upload("tvunlocker_diag.txt", log);
                final boolean unlocked = log.contains("РАЗБЛОКИРОВАНО");
                final String msg = (unlocked
                        ? ">>> РАЗБЛОКИРОВАНО! Выполните на компьютере: adb connect <IP>:5555\n"
                        : "Диагностика готова, adb_enabled=0. Смотри протокол.\n")
                        + "Протокол:\n" + paths + "\nНа ПК: " + up;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ==================== Рекон прошивки ====================

    private void runRecon() {
        if (needWritePerm()) {
            askWritePerm();
            reconAfterGrant = true;
            return;
        }
        doRecon();
    }

    private void doRecon() {
        Toast.makeText(this, "Рекон прошивки… читаю манифесты и dex, до пары минут",
                Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String recon;
                try {
                    recon = Recon.run(TVUnlocker.this);
                } catch (Throwable t) {
                    recon = "ОШИБКА рeкона: " + t;
                }
                final StringBuilder paths = new StringBuilder();
                writeEverywhere("tvunlocker_recon.txt", recon, paths);
                String up = upload("tvunlocker_recon.txt", recon);
                final String msg = "Рекон готов.\nПротокол:\n" + paths
                        + "\nНа ПК: " + up;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    // ==================== Отправка на ПК ====================

    private void sendPC() {
        if (needWritePerm()) {
            askWritePerm();
            sendAfterGrant = true;
            return;
        }
        doSendPC();
    }

    private void doSendPC() {
        Toast.makeText(this, "Собираю данные и отправляю на ПК…", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String content = buildReportContent();
                String probe;
                try {
                    probe = Probe.run(TVUnlocker.this);
                } catch (Throwable t) {
                    probe = "ОШИБКА пробивки: " + t;
                }

                final StringBuilder paths = new StringBuilder();
                writeEverywhere(REPORT_NAME, content, paths);
                writeEverywhere(PROBE_NAME, probe, paths);

                String up1 = upload(REPORT_NAME, content);
                String up2 = upload(PROBE_NAME, probe);
                final boolean ok = up1.startsWith("OK") && up2.startsWith("OK");
                final String msg;
                if (ok) {
                    msg = ">>> ОТПРАВЛЕНО НА ПК! (" + PC_PRIMARY + ":" + PC_PORT + ")\n"
                            + "Файлы приняты приёмником на компьютере.\nЗаписаны также:\n" + paths;
                } else {
                    msg = "На ПК не ушло (" + up1 + " | " + up2 + ").\n"
                            + "Файлы записаны:\n" + paths;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(TVUnlocker.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** POST файла на первый отвечающий IP из PC_CANDIDATES. */
    private String upload(String name, String content) {
        byte[] data;
        try {
            data = content.getBytes("UTF-8");
        } catch (Throwable t) {
            data = new byte[0];
        }
        for (String ip : PC_CANDIDATES) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        "http://" + ip + ":" + PC_PORT + "/upload?name="
                                + URLEncoder.encode(name, "UTF-8")).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(2500);
                c.setReadTimeout(10000);
                c.setFixedLengthStreamingMode(data.length);
                OutputStream os = c.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                StringBuilder resp = new StringBuilder();
                if (is != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(is));
                    String line;
                    while ((line = r.readLine()) != null) resp.append(line);
                    r.close();
                }
                if (code == 200) return "OK " + ip + " (" + resp + ")";
            } catch (Throwable ignored) {
                // пробуем следующий IP
            }
        }
        return "FAIL";
    }

    // ==================== Запись на все носители ====================

    /** Пишет файл в Download, на все съёмные тома (флешки) и в папку приложения. */
    private void writeEverywhere(String name, String content, StringBuilder paths) {
        byte[] data;
        try {
            data = content.getBytes("UTF-8");
        } catch (Throwable t) {
            data = new byte[0];
        }

        // 1. Основной Download (внутренняя память)
        try {
            File dir = Environment
                    .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.close();
            paths.append("Внутр. Download: ").append(f.getAbsolutePath()).append("\n");
        } catch (Throwable t) {
            paths.append("Внутр. Download: ошибка ").append(t.getMessage()).append("\n");
        }

        // 2. Съёмные тома: StorageManager (API 30+) + скан /storage
        ArrayList<File> vols = new ArrayList<File>();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.os.storage.StorageManager sm =
                        (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
                for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                    if (v != null && v.isRemovable()) {
                        File d = v.getDirectory();
                        if (d != null) vols.add(d);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File root = new File("/storage");
            File[] list = root.listFiles();
            if (list != null) {
                for (File f : list) {
                    String n = f.getName().toLowerCase();
                    if (n.equals("emulated") || n.equals("self")) continue;
                    if (f.isDirectory() && f.canExecute()) vols.add(f);
                }
            }
        } catch (Throwable ignored) {
        }

        for (File v : vols) {
            try {
                File dir = new File(v, "TVUnlocker");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.close();
                paths.append("ФЛЕШКА: ").append(f.getAbsolutePath()).append("\n");
            } catch (Throwable t) {
                paths.append("Флешка ").append(v.getName())
                     .append(": ошибка ").append(t.getMessage()).append("\n");
            }
        }

        // 3. Папка приложения (всегда доступна)
        try {
            File f = new File(getExternalFilesDir(null), name);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.close();
            paths.append("AppDir: ").append(f.getAbsolutePath()).append("\n");
        } catch (Throwable t) {
            paths.append("AppDir: ошибка ").append(t.getMessage()).append("\n");
        }
    }

    // ==================== Сборка отчёта ====================

    private String buildReportContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("TV Unlocker v4.3 report\n");
        sb.append("Дата: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date())).append("\n");
        sb.append("Модель: ").append(Build.MODEL).append("\n");
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
          .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");

        sb.append("== ADB ==\n");
        sb.append("adb_enabled: ").append(adbNow()).append("\n");
        String[] adbProps = {
                "ro.build.type", "ro.debuggable", "ro.secure", "ro.adb.secure",
                "persist.adb.tcp.port", "service.adb.tcp.port",
                "persist.service.adb.enable", "persist.usb.config"
        };
        for (String p : adbProps) {
            try {
                sb.append(p).append(": ").append(InfoActivity.prop(p)).append("\n");
            } catch (Throwable t) {
                sb.append(p).append(": err\n");
            }
        }

        sb.append("\n== GETPROP (полный дамп) ==\n");
        try {
            sb.append(InfoActivity.propAll());
        } catch (Throwable t) {
            sb.append("err ").append(t.getMessage()).append("\n");
        }

        sb.append("\n== ВСЕ АКТИВНОСТИ (E=запускаемая, C=закрытая) ==\n");
        PackageManager pm = getPackageManager();
        List<PackageInfo> packs = null;
        try {
            packs = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_PROVIDERS);
        } catch (Throwable t) {
            sb.append("err ").append(t.getMessage()).append("\n");
        }
        if (packs != null) {
            for (PackageInfo pi : packs) {
                if (pi == null) continue;
                sb.append("-- пакет ").append(pi.packageName).append("\n");
                if (pi.activities == null) continue;
                for (ActivityInfo ai : pi.activities) {
                    if (ai == null) continue;
                    sb.append(ai.exported ? "E " : "C ").append(ai.name).append("\n");
                }
            }
        }

        sb.append("\n== СЕРВИСЫ (E=экспортированный, C=закрытый) ==\n");
        if (packs != null) {
            for (PackageInfo pi : packs) {
                if (pi == null || pi.services == null) continue;
                sb.append("-- пакет ").append(pi.packageName).append("\n");
                for (android.content.pm.ServiceInfo si : pi.services) {
                    if (si != null) sb.append(si.exported ? "E " : "C ").append(si.name).append("\n");
                }
            }
        }

        sb.append("\n== ПРИЁМНИКИ (E/C) ==\n");
        if (packs != null) {
            for (PackageInfo pi : packs) {
                if (pi == null || pi.receivers == null) continue;
                sb.append("-- пакет ").append(pi.packageName).append("\n");
                for (ActivityInfo ai : pi.receivers) {
                    if (ai != null) sb.append(ai.exported ? "E " : "C ").append(ai.name).append("\n");
                }
            }
        }

        sb.append("\n== ПРОВАЙДЕРЫ (E/C authority) ==\n");
        if (packs != null) {
            for (PackageInfo pi : packs) {
                if (pi == null || pi.providers == null) continue;
                sb.append("-- пакет ").append(pi.packageName).append("\n");
                for (android.content.pm.ProviderInfo pri : pi.providers) {
                    if (pri != null) sb.append(pri.exported ? "E " : "C ")
                        .append(pri.authority).append("\n");
                }
            }
        }

        sb.append("\n== ВСЕ ПАКЕТЫ ==\n");
        List<ApplicationInfo> apps = null;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            sb.append("err ").append(t.getMessage()).append("\n");
        }
        if (apps != null) {
            for (ApplicationInfo a : apps) {
                if (a != null) sb.append(a.packageName).append("\n");
            }
        }

        return sb.toString();
    }
}
