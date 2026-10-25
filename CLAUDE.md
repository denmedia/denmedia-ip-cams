# DenMedia IP Cams

Android-приложение (Kotlin, Jetpack Compose, Material 3) для просмотра и управления IP-камерами в локальной сети:
живое видео по RTSP (Media3/ExoPlayer), PTZ, сохранённые положения, архив записей на SD-карте камеры,
события движения, обратная связь (talkback), виджеты домашнего экрана, передача камеры QR кодом.
Интерфейс, комментарии в коде и общение с пользователем — на русском.

- Пакет / applicationId: `com.example.denmediaipcams`; minSdk 33, targetSdk/compileSdk 37; AGP 9, Kotlin 2.2.
- Зависимости — в `gradle/libs.versions.toml`.
- Проект не под git. Файлы `*_REPORT*.md`, `BUGFIX_*.md`, `SUMMARY_*.md` и т. п. в корне — старые отчёты, не документация к текущему коду.

## Сборка и проверка

- `JAVA_HOME` не задан: использовать JDK Android Studio — `D:\Program Files\Android\Android Studio\jbr`.
- Сборка: `gradlew.bat :app:assembleDebug`. Обычно работает офлайн (`--offline`); новые зависимости один раз скачиваются без него.
- Юнит-тесты (JVM): `gradlew.bat :app:testDebugUnitTest --offline -q`, результаты — `app/build/test-results/testDebugUnitTest/`.
  `org.json` и другие Android-классы в JVM-тестах не работают (заглушки) — такие проверки пишутся инструментальными.
- Инструментальные тесты: `connectedDebugAndroidTest` офлайн не работает. Вместо него собрать
  `:app:assembleDebug :app:assembleDebugAndroidTest`, поставить оба APK (`adb install -r -t`) и запустить
  `adb shell am instrument -w -e class <Класс> com.example.denmediaipcams.test/androidx.test.runner.AndroidJUnitRunner`.
  Адреса, логины и пароли камер передаются через `-e`, в код не записываются. Тестовые методы с `runBlocking` объявлять как `runBlocking<Unit>`.
- **Где проверять:** в первую очередь — на реальном устройстве, подключённом к Android Studio
  (планшет Samsung SM-X916B по adb Wi‑Fi, `adb -s <serial>`). Эмулятор (`Medium_Phone_API_37.0`) — только если устройство не подключено.
  Планшет — рабочее устройство пользователя: перед каждым нажатием (`adb shell input tap`) проверять, что на переднем плане
  `com.example.denmediaipcams/.MainActivity`. `am instrument` перезапускает процесс приложения, поэтому после него приложение надо открыть заново.
- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. В Git Bash пути в аргументах adb портятся — ставить `MSYS_NO_PATHCONV=1`.
- Python на ПК нет; для разбора файлов и скриптов использовать node (`.mjs`, см. `tools/`).

## Структура `app/src/main/java/com/example/denmediaipcams`

- `model/Camera.kt` — `IpCamera` (все настройки камеры), протоколы, порты, пресеты, поворот видео.
- `data/` — `CameraRepository` (список камер в SharedPreferences), `CameraJson` (единый JSON-формат камеры),
  `CameraShareCode` (камера ↔ строка для QR кода), сохранение снимков и записей, кэш архива.
- `network/` — протоколы камер: ONVIF, Hikvision ISAPI, Dahua CGI, Hipcam, XMEye/DVRIP; поиск камер и портов,
  PTZ, архивы и драйверы записи, talkback, синхронизация времени.
- `player/` — сессии потоков, проигрывание архивов, конвертеры (HXVT, DVRIP → MP4), миниатюры.
- `ui/MainViewModel.kt` — состояние главного экрана; `MainActivity.kt` — `MainScreen`, все диалоги главного экрана.
- `ui/layouts/` — раскладки: `TabletLayout` (горизонтально, три колонки), `PhoneLayout`, `PortraitLayout`;
  общие части и `CameraScreenActions` — в `CameraScreenParts.kt`. Новое действие экрана добавляется в `CameraScreenActions`
  и выводится во всех раскладках.
- `ui/components/` — диалоги и элементы: `CameraEditDialog` (добавление/настройка камеры), окна архива,
  `CameraShareDialog` и `QrScanDialog` (QR код), `NetworkScanDialog` и др.
- `widget/` — виджеты «Камера: трансляция» и «Камеры: список» и их служба.

## Соглашения

- Комментарии и KDoc — по-русски, объясняют «почему», в стиле соседнего кода.
- Окна архива всех камер одинаковые: общие компоненты и `ArchiveProtocol`, неподдерживаемое камерой — скрывать, а не показывать неактивным.
- Ничего не отправлять на камеру само по себе при ручном добавлении: автоопределение — только для заготовки из поиска в сети.
  Камера из QR кода уже настроена (`draftConfigured`), для неё автоопределение тоже не запускается.
- Протокол XMEye/DVRIP включается только явно пользователем (может повесить камеру).
- Новые поля `IpCamera` добавлять в `CameraJson` (в обе стороны) — так они попадут и в сохранение, и в QR код.
  Значения по умолчанию в `CameraJson.fromJson` должны совпадать с умолчаниями `IpCamera`: QR код не передаёт поля со значением по умолчанию.
