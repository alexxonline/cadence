# Cadence

An Android audiobook player, built with Jetpack Compose, for audiobooks stored in your Google Drive.

- **Sign in with Google.** The app asks for read-only access to your Drive.
- **Pick a Drive folder** as your library. You can browse *My Drive* or *Shared with me*.
- **See your audiobooks.** The app detects single-file books and books split across several files.
- **Download books** into app-private storage, then play them offline.
- **Pick up where you left off.** The app saves, on the device, the book and file you played last and the position in every file you've played.
- **Play in the background** through a Media3 `MediaSessionService`. You get notification and lock-screen controls, headset and Bluetooth buttons, and playback pauses when you unplug headphones. You can skip back 10 s or forward 30 s, set the speed from 0.75× to 2×, and move to the next or previous file.

---

## Is an API key needed?

**No API key or client secret is embedded in the app, and no file has to be edited.**

Google sign-in uses Google Identity Services (`AuthorizationClient` from `play-services-auth`). Google identifies the app by its **package name and signing-certificate SHA-1**. You only need an **OAuth client of type "Android"** registered in a Google Cloud project, as described below. Until you register one, tapping *Sign in with Google* fails with an error such as `DEVELOPER_ERROR` / `10:`.

### One-time Google Cloud setup

1. Open the [Google Cloud console](https://console.cloud.google.com/) and create or select a project.
2. **Enable the Google Drive API:** *APIs & Services → Library → Google Drive API → Enable*.
3. **Configure the OAuth consent screen** (*Google Auth Platform → Branding / Audience / Data access*):
   - User type: **External**. Publishing status: **Testing**.
   - Add the scope `https://www.googleapis.com/auth/drive.readonly`.
   - Under *Test users*, add every Google account that will sign in. While the app is in Testing, only these accounts can use it.
4. **Create an OAuth client ID** (*Credentials → Create credentials → OAuth client ID*):
   - Application type: **Android**
   - Package name: `net.alexsaez.cadence`
   - SHA-1 certificate fingerprint of the key that signs your build. For the debug key, run one of these:

     ```bash
     ./gradlew signingReport
     ```

     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
     ```

   Release builds need a second Android client, or another SHA-1, for the release or upload key. If you distribute through Google Play, also add the **Play App Signing** key's SHA-1.
5. Build and run the app. Changes in the console can take a few minutes to take effect.

> **Publishing note:** `drive.readonly` is a *restricted* scope. A personal or testing-mode app works fine with the test users above. A public release, however, needs Google's OAuth app verification and a security assessment. That is also why the app doesn't use the narrower `drive.file` scope: `drive.file` only covers files the user opens through the Google Picker, and the Picker has no native Android version.

---

## Using the app

1. **Sign in with Google.** Google's account picker and consent screen appear. Approve read-only Drive access.
2. **Choose a folder.** Open *My Drive* or *Shared with me*, go to the folder that holds your audiobooks, and tap **Use "…"**. You can change it later with the folder icon in the library.
3. **Library.** Pull down or tap ↻ to rescan Drive. Each book shows the number of files, its size, its download state and your progress. The **Continue listening** card at the top resumes the book you played last.
4. **Open a book** and tap **Download to listen**. The download continues while you browse. When it finishes, press ▶. For multi-file books you can tap any file in the list; each file remembers its own position.

### How audiobooks are detected

The folder you selected is the library *root*:

| Drive layout | Result |
|---|---|
| `root/Moby Dick.m4b` | Single-file book "Moby Dick" |
| `root/The Hobbit - Part 1.mp3`, `root/The Hobbit - Part 2.mp3`, … | **One** multi-file book "The Hobbit": root files that share a name followed by a number (`Part`, `Chapter`, `CD`, `Disc`, `Track`… are optional) |
| `root/Dune/Dune 1.mp3 … Dune 10.mp3` | Multi-file book "Dune": all audio files in a folder, in **natural name order** (`2` comes before `10`) |
| `root/Dune/dune.m4b` | Single-file book "Dune": a folder with one file |
| `root/Foundation/CD 1/*.mp3`, `root/Foundation/CD 2/*.mp3` | One book "Foundation" spanning the numbered disc folders |
| `root/Asimov/I, Robot/*.mp3` | Folders without audio, such as author folders, are scanned recursively, up to 4 levels deep |

Recognized audio files are any `audio/*` MIME type plus the extensions `mp3, m4a, m4b, aac, ogg, oga, opus, flac, wav, wma, mka, amr`. Other files are ignored.

### What is stored on the device

| Data | Where |
|---|---|
| Selected folder, last book and file played, position of every file (in DataStore) | `files/datastore/playback.preferences_pb` |
| Downloaded audio | `files/audiobooks/<driveFileId>.<ext>` |
| Cached library scan, so the library also works offline | `files/library/` |
| Signed-in user's name and email | `shared_prefs/auth.xml` |

Positions are saved every 5 s while playing, on pause, when the app switches files or books, and when headphones are unplugged. **Signing out** revokes the Drive grant and deletes the downloads, the cached library and the selected folder. It keeps listening positions, which are keyed by Drive file id.

---

## Architecture

```
ui/  (Compose screens + ViewModels)  Login → FolderPicker → Library → Player
        │
di/AppContainer  — manual DI; the 3 "outside world" dependencies are constructor parameters
        │
├─ auth/AuthRepository         GoogleAuthRepository (Identity AuthorizationClient)    ← Google sign-in
├─ drive/DriveApi              DriveRestApi (OkHttp + kotlinx.serialization, Drive v3) ← Google Drive
├─ player/AudioPlayer          Media3AudioPlayer → PlaybackService (ExoPlayer)         ← audio hardware
├─ library/                    LibraryRepository (scan + cache), AudiobookGrouper, NaturalOrder
├─ download/DownloadRepository downloads into app storage, progress as StateFlow
├─ storage/PlaybackStore       DataStore: folder, last played, per-file positions
└─ player/PlaybackController   glue: resumes at saved positions, persists progress while playing
```

Stack: Kotlin 2.2, AGP 9.5 (built-in Kotlin), Compose Material 3, Navigation Compose, Media3 1.11, DataStore, play-services-auth 21.4, OkHttp 5. minSdk 24, targetSdk 37.

---

## Building

```bash
./gradlew assembleDebug
```

```bash
./gradlew installDebug
```

You need JDK 17+ (Gradle uses the toolchain in `gradle/gradle-daemon-jvm.properties`) and Android SDK 37.

---

## Tests

### Unit tests (JVM, no device)

```bash
./gradlew testDebugUnitTest
```

- `AudiobookGrouperTest`, `NaturalOrderTest`: the book-detection rules above.
- `DriveRestApiTest`: the real Drive client against a fake Drive HTTP server. Covers pagination, the bearer token, retrying on a 401, downloads with progress, and error handling.
- `PlaybackControllerTest`: resuming, saving positions periodically and on pause, marking files finished, switching books, and pausing on headphone unplug.

### Integration tests (instrumented, on an emulator or device)

```bash
./gradlew connectedDebugAndroidTest
```

`AudiobookAppTest` drives the **real app**: the Compose UI, ViewModels, the Drive REST client, downloads to disk, DataStore persistence and the playback controller. Only the three external systems are replaced by fakes:

| External system | Fake | How it's injected |
|---|---|---|
| Google sign-in | `FakeAuthRepository`: "picks" a test account without Google's UI, can simulate failures and remembered sessions | `CadenceTestRunner` swaps the `Application` for `TestCadenceApp`, and the `TestEnvironment` rule installs an `AppContainer` built with the fakes |
| Google Drive | `FakeGoogleDrive`: an in-memory file tree served by OkHttp `MockWebServer` that speaks the Drive v3 REST protocol (`files.list` with `q`, `files.get?alt=media`, `about`) and checks the OAuth bearer token | `DriveRestApi` is pointed at the mock server's URL. Debug builds allow cleartext to `localhost` only (`src/debug/res/xml/network_security_config.xml`) |
| Audio hardware | `FakeAudioPlayer`: a simulated device where time moves only when the test calls `advanceBy()`. It moves through the queue like a real player and can simulate unplugging headphones or losing audio focus | passed to `AppContainer` in place of `Media3AudioPlayer` |

Scenarios:

1. Sign in with Google → browse Drive → pick the folder → the library lists single-file, numbered-file, folder and multi-disc books, and ignores non-audio files and unrelated folders.
2. Sign-in failure shows an error.
3. Download a single-file book (bytes checked against Drive), play the local file, listen 65 s, pause. **Restart the app process** (every in-memory object is rebuilt; only disk survives) → *Continue listening* shows 1:05 and playback resumes at 65 000 ms without downloading again.
4. Multi-file book: files play in natural order (1, 2, 10); finishing file 1 marks it *Finished*; each file keeps its own position; after a restart the book resumes in file 2 at 0:30.
5. Unplugging headphones pauses playback and saves the position.
6. If Drive is down after a restart, the error is reported and the cached library is still shown.
7. Sign out returns to the login screen and deletes the downloads.

`Media3AudioPlayerTest` is an extra smoke test of the **production** player path on the device (Media3 controller → `PlaybackService` → ExoPlayer decoding a generated WAV file).

Shared fakes live in `app/src/sharedTest/java` and are compiled into both test source sets.

---

## Limitations / ideas

- Books play only after they are downloaded; there is no streaming.
- Cover art, chapter markers inside a single `.m4b` file, and sleep timers are not implemented.
- Downloads run in the app process. A WorkManager job would let them survive the process being killed.
