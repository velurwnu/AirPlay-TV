<div align="center">

<img src="banner.png" width="320" alt="AirPlay TV Banner">

# AirPlay TV & Music TV for Android TV

**English** | [Русский](#airplay-tv-ru)

</div>

---

## AirPlay TV & Music TV for Android TV

Two complementary open-source applications for Android TV 8+ (`arm64-v8a`) and Yandex Station devices: a fully featured **AirPlay Receiver** and a native-like **Apple Music TV** interface.

---

### 1. AirPlay TV (Module `airplay`)

A standalone receiver app (`com.amusic.airplay`) that makes your Android TV appear as an Apple TV in the AirPlay menu of iPhone, iPad, and Mac.

#### Key Features
- **Lossless Audio & Apple Music-Style Synced Lyrics:** Streams ALAC and AAC audio. Displays album artwork, track title, artist, album, timeline, and **live synchronized lyrics** with active line highlight and smooth auto-scrolling. Lyrics are fetched in parallel from **NetEase** and **LRCLIB** and cached locally on disk. Compensates for AirPlay's ~2-second audio buffer delay so screen changes stay perfectly in sync with sound.
- **Phonetic Pronunciation (Romanization):** Displays Latin pronunciation guides underneath Asian scripts: Korean (official Revised Romanization), Chinese (Pinyin via built-in table), Japanese (Romaji). Can be toggled in settings.
- **Bi-Directional TV Remote Control (DACP):** Control volume directly from iPhone; TV remote controls playback on your Apple device (OK = Play/Pause, ◀ / ▶ = Previous/Next track).
- **Screen Mirroring:** Hardware-accelerated mirroring (H.264, optional H.265 up to 4K) using Android's native `MediaCodec`.
- **AirPlay Video Casting:** Plays HLS video streams directly from YouTube, Safari, and other media apps via `Media3 ExoPlayer`, with remote seeking (±10s).
- **Background Operation & PIN Pairing:** `ReceiverService` runs as a foreground service with Wi-Fi multicast lock, waking the TV screen automatically upon incoming connection. Supports Apple TV one-time PIN pairing, custom TV names, and auto-start on boot.
- **Core Engine:** Built on top of [UxPlay](https://github.com/FDH2/UxPlay) (AirPlay protocol, FairPlay, pairing, mDNS) compiled via Android NDK, with native Android audio (`AudioTrack`), video (`MediaCodec`), and playback (`ExoPlayer`).

---

### 2. Music TV (Module `app`)

A lightweight Liquid Glass TV interface injected over `music.apple.com`. Sign in directly with your personal Apple ID without needing an Apple Developer API token. Apple handles Widevine DRM and data natively.

- Glass tab bar: **Home / New / Library / Search** (radio hidden).
- Full D-pad remote navigation over links, buttons, media keys, and Back button.
- Native Liquid Glass aesthetics with GPU-friendly gradients and highlights.

---

### Building

Requires Android Studio with NDK 29.0.14206865 and CMake 3.31.6:

```bash
# Build AirPlay Receiver APK (Release)
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :airplay:assembleRelease

# Build Music TV App (Debug)
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Outputs:
- AirPlay TV: `airplay/build/outputs/apk/release/airplay-release.apk`
- Music TV: `app/build/outputs/apk/debug/app-debug.apk`

---

### Installation via ADB

Enable Developer Options and Network Debugging on your Android TV:

```bash
adb connect <TV_IP_ADDRESS>:5555
adb install -r airplay/build/outputs/apk/release/airplay-release.apk
```

#### Useful ADB Commands
- Set custom ports:
  ```bash
  adb shell am start -n com.amusic.airplay/.MainActivity --ei base_port 47000
  ```
- Change AirPlay display name:
  ```bash
  adb shell am start -n com.amusic.airplay/.MainActivity --es name "'Living Room'"
  ```
- Test "Now Playing" screen without an Apple device:
  ```bash
  adb shell am start -n com.amusic.airplay/.MainActivity --es demo "'Dua Lipa|Levitating|Future Nostalgia|203|60'"
  ```

---

<br>

<a name="airplay-tv-ru"></a>

<div align="center">

[English](#airplay-tv--music-tv-for-android-tv) | **Русский**

</div>

---

# Music TV — Apple Music для Android TV (arm64-v8a) [RU]

WebView-обёртка над **music.apple.com**. Вход по своему Apple ID прямо на сайте,
токен разработчика не нужен. Сайт делает всё сам: вход, данные, воспроизведение (DRM Widevine).
Поверх него внедряется TV-слой (`app/src/main/assets/web`):

- стеклянный таб-бар **Главное / Новое / Медиатека / Поиск** (радио скрыто);
- в Медиатеке подвкладки **Песни / Альбомы / Артисты** и кнопки «Всё» / «Перемешать»;
- на Главной блок **«Слушать всё» / «Перемешать»** для всей медиатеки, полки
  **Made for You** и **Heavy Rotation** закреплены наверху;
- навигация пультом (D-pad) по ссылкам и кнопкам сайта, Back, медиа-клавиши пульта;
- лёгкое «жидкое стекло»: тяжёлые `backdrop-filter` сайта отключены, вместо них градиенты и блики.

TV-слой не читает токены и не обращается к API Apple: он только меняет оформление и нажимает кнопки сайта.

## Файлы

| Файл | Что делает |
|---|---|
| `app/.../MainActivity.kt` | WebView с music.apple.com, внедрение TV-слоя, DRM, окно входа Apple ID, Back |
| `web/tv.css` | тема поверх сайта: стекло, скрытие боковой панели и футера, фокус, плеер |
| `web/tv.js` | таб-бар, переходы по разделам, навигация пультом, «Слушать всё / Перемешать» |
| `web/config.js` | настоящее размытие вкл/выкл, плавный скролл, маршруты медиатеки, названия закрепляемых полок |

## Сборка

Нужна Android Studio (в ней уже есть JDK и SDK).

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Либо откройте папку в Android Studio и нажмите ▶ Run.

## Установка на ТВ

На телевизоре: *Параметры разработчика → Отладка по сети (ADB)*. Затем, подставив IP телевизора:

```bash
adb connect 192.168.1.50:5555
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Если Apple поменяет сайт

TV-слой опирается на классы и `data-testid` сайта (`player-bar`, `product-lockup`,
`primary-actions__button--shuffle`, `--web-navigation-width` и т.п.). Если после обновления сайта
что-то съехало, правки нужны в `tv.css` / `tv.js`. Названия закрепляемых полок задаются в `config.js` → `pinShelves`.

---

# AirPlay для ТВ — приёмник AirPlay для Android TV (модуль `airplay`)

Отдельное приложение (`com.amusic.airplay`, Android 8+, arm64-v8a): телевизор появляется в списке AirPlay
на iPhone/iPad/Mac как Apple TV.

- **Музыка с iPhone**: звук (ALAC / AAC), на экране обложка, название, исполнитель — альбом,
  таймлайн с прошедшим/оставшимся временем и **текст песни** в стиле Apple Music: синхронизированный,
  текущая строка подсвечена. Текст ищется по названию и исполнителю (NetEase, LRCLIB) и кешируется.
  Экран меняет песню вместе со звуком (метаданные приходят ~2 с раньше звука, приложение это учитывает).
- **Произношение** под корейским, китайским и японским текстом: корейский — латиницей по официальной
  романизации, китайский — пиньинем (встроенная таблица), японский — ромадзи от NetEase
  (строки с кандзи без ромадзи от NetEase остаются без произношения). Отключается в настройках.
- **Громкость** с iPhone; **пульт ТВ управляет iPhone** (DACP, как у Apple TV): OK — пауза, ◀ ▶ — трек.
- **Повтор экрана** (H.264, по желанию H.265 до 4K — если у ТВ есть аппаратный декодер).
- **Видео из приложений** (YouTube и др., значок AirPlay в плеере): ТВ сам играет поток HLS,
  перемотка и пауза с iPhone, на пульте ◀ ▶ — ±10 с.
- Код при первом подключении (как «Требовать код» на Apple TV), переименование, автозапуск при включении ТВ,
  работа в фоне: при подключении iPhone экран открывается сам.

В основе — ядро [UxPlay](https://github.com/FDH2/UxPlay) (протокол AirPlay, FairPlay, сопряжение, mDNS),
собранное под Android через NDK, вывод звука и видео — свои (AudioTrack, MediaCodec, ExoPlayer).

## Сборка и установка

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :airplay:assembleRelease
```

```bash
adb install -r airplay/build/outputs/apk/release/airplay-release.apk
```

Нужны NDK 29.0.14206865 и CMake 3.31.6 (Android Studio → SDK Manager → SDK Tools). OpenSSL подтягивается из Maven (prefab).

Настройки — кнопка «Настройки» на стартовом экране или клавиша MENU на пульте.

## Параметры через adb

Фиксированные порты (для файрвола или проброса): TCP N, N+1 и UDP N…N+2; 0 — любые свободные.

```bash
adb shell am start -n com.amusic.airplay/.MainActivity --ei base_port 47000
```

Имя телевизора в списке AirPlay (то же можно сделать в настройках):

```bash
adb shell am start -n com.amusic.airplay/.MainActivity --es name "'Гостиная'"
```

Проверить экран «Сейчас играет» без iPhone (исполнитель|название|альбом|длина, с|с какой секунды):

```bash
adb shell am start -n com.amusic.airplay/.MainActivity --es demo "'Dua Lipa|Levitating|Future Nostalgia|203|60'"
```

## Ограничения

- Это AirPlay «как у Apple TV 3-го поколения + повтор экрана»: нет мультирума AirPlay 2 (несколько колонок
  сразу), нет буферизованного звука и синхронизации PTP, ТВ не добавляется в «Дом».
- **Приложение «Музыка» на Mac** не может играть на этот приёмник: оно шифрует звук закрытым вариантом FairPlay
  («тип 2»), который не поддерживает ни одна открытая реализация. iPhone и iPad используют поддерживаемый вариант.
- Проверено на эмуляторе Android TV: запуск, обнаружение с Mac, RTSP/FairPlay-рукопожатие, поиск пульта DACP,
  экран «Сейчас играет» с текстом (через demo). С настоящим iPhone не проверялось.
- Телевизор и iPhone должны быть в одной сети; роутер не должен блокировать mDNS (UDP 5353) и трафик между устройствами.

## Лицензии

Ядро UxPlay — GPL-3.0, поэтому модуль `airplay` целиком распространяется под GPL-3.0.
libplist — LGPL-2.1, декодер ALAC — Apache-2.0 (тексты лицензий и происхождение — в `airplay/src/main/cpp/*/`),
OpenSSL 1.1.1 (пакет `com.android.ndk.thirdparty:openssl`) — лицензия OpenSSL/SSLeay.
