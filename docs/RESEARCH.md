# Замечания по исследованию

Заметки для тех, кто будет адаптировать инструмент под другую прошивку.
Порядок пунктов отражает то, что проверялось на Hyundai H-LED40BS5003
(YaOS 4.x / Android 11, CVTE TP.MT9256.PB722).

## Сеть

- Полное сканирование портов нашло единственный открытый — 1961. Это служба
  Алисы (YaOS), не HTTP и не ADB; вручную подключить adbd извне невозможно.
- USB-порты телевизора работают только в режиме host, кабелем ПК—ТВ
  ADB не поднимается.

## Меню разработчика

- Семь нажатий на номер сборки не включают режим разработчика (пункт вырезан).
- `DevelopmentActivity` (`com.android.tv.settings.system.development.*`)
  существует, но открывается пустым: ветки отладки отключены в user-сборке.
- `eventSystemDebug*` в `ITvApiSystemDebugAidl` принимает вызовы, но на
  включение adbd не влияет (влияет только на отладочные флаги платформы).

## Bind к вендорским сервисам

- `bindService` с явным ComponentName к system-uid сервисам (`com.cvte.*`,
  uid 1000, exported=true, без permission) возвращает `false` без исключения —
  YaOS режет именно этот путь.
- **bind по action-строке проходит**: `com.cvte.tv.api.TV_API_SERVICE`,
  `android.intent.action.CVTEFACSERVICE`, `cvte.factory.intent.action.APIInit`.
- Дескриптор binder'а (`getInterfaceDescriptor`) проверяется до любой
  транзакции; интерфейс подгружается `DexClassLoader` из APK сервиса
  или из `/system/framework/*.jar` (поиск по распакованному содержимому —
  в APK строки внутри сжатых dex «сырым» поиском не видны).
- Слепые транзакции по коду метода недопустимы: в `IFactoryService`
  рядом с shell-методами сидят `autoBurn*`, записывающие MAC и ключи.

## Что включило ADB

`ITvApiManager.getTVApiSystemProperties()` возвращает `set(String,String)`,
исполняемый от system-uid. Последовательность:

```
set("service.adb.tcp.port", "5555")
set("ctl.stop", "adbd")
set("ctl.start", "adbd")
```

— поднимает adbd на TCP 5555 в обход `adb_enabled=0`. Поскольку в прошивке
`ro.adb.secure=0`, подключение с ПК не требует подтверждения на экране.
После подключения `settings put global adb_enabled 1` закрепляет состояние.

## Не сработало (чтобы не повторять)

- Прямая запись `adb_enabled` в Settings.Global/Secure — `WRITE_SECURE_SETTINGS`,
  signature-уровень.
- Broadcast по словарю action-строк к вендорским приёмникам — доставляются,
  но на adbd не влияют.
- `content://com.cvte.tv.setting.DataBaseProvider` — отвечает «Invalid tables»
  на 41 таблицу-кандидат; `call()` возвращает null.
- `eventSystemSendKeyEvent(int)` и `handleKeyEvents(KeyEvent)` — сервис
  отвечает `false` на любые синтетические события (5 форм KeyEvent × 14 кодов).
- Чтение `/vendor/tvconfig` — конфиги читаемы, но изменение требует записи
  в read-only раздел.

## Флешка с прошивкой

Официальный SOS-пакет (`allupgrade516_8GB_1GB_sos.bin`) полностью разбирается:
текстовый mboot-скрипт с маркерами `# File Partition:` + бинарные блобы,
`setenv verify n` (проверки нет), bootargs патчатся same-length заменой.
Однако для плат MT9256 кастомных сборок с ADB в открытом доступе нет,
а заводские — та же закрытая YaOS, поэтому правка пакета здесь не потребовалась.
