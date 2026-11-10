# Workspace File Manager

An all-in-one, **offline-first file, media and knowledge workspace for Android**, built with Kotlin and Jetpack Compose (Material 3). It combines a root-capable file manager, in-app viewers for PDF/DOCX/EPUB/images, a media library and player, a block-based notes/workspace editor, storage analytics with duplicate detection, network file access (SFTP, SMB, and a built-in FTP server), and a local LLM assistant running entirely on the device through `llama.cpp`.

No accounts, no cloud, no telemetry. Every feature — including the AI assistant — works with no network connection once the optional model file has been downloaded.

- **Package / applicationId:** `com.example.workspacefilemanager`
- **Version:** 1.0 (`versionCode` 1)
- **Min SDK:** 26 (Android 8.0) · **Target / compile SDK:** 35 (Android 15)
- **Language / UI:** Kotlin 2.x, Jetpack Compose + Material 3
- **Native:** C++17, `llama.cpp` via CMake/NDK (arm64-v8a, x86_64)

---

## Table of contents

1. [Feature overview](#feature-overview)
2. [Screens and navigation](#screens-and-navigation)
3. [Architecture](#architecture)
4. [Package map](#package-map)
5. [On-device AI](#on-device-ai)
6. [Network features](#network-features)
7. [File format support](#file-format-support)
8. [Storage model and app data](#storage-model-and-app-data)
9. [Permissions](#permissions)
10. [Build and run](#build-and-run)
11. [Design system](#design-system)
12. [Known limitations and unfinished work](#known-limitations-and-unfinished-work)
13. [Troubleshooting](#troubleshooting)

---

## Feature overview

### File management
- Browse the whole filesystem starting at primary external storage; the app bar shows the current folder name plus folder/file counts, with one-tap up-navigation.
- Live search within a folder, and sorting by name, size, modification date or type (ascending/descending); folders always sort first.
- Create folder, rename, copy, cut/move (with clipboard), delete, share, and "open with" another app.
- Starred files (persistent favorites) and a **Safe folder** in app-private storage that hides files from other apps and the gallery.
- Honest, actionable operation results: deletes fall back to `MediaStore` deletion, then report a permission-specific error instead of failing silently.
- Thumbnails for images and video frames, plus per-type leading icons.

### Viewers
- **PDF** — native `PdfRenderer`, page-by-page lazy rendering at screen width, byte-bounded LRU bitmap cache, pinch-to-zoom.
- **DOCX** — reads `word/document.xml` from the OOXML zip and renders paragraph text with formatting preserved line-by-line.
- **EPUB** — extracts chapters from the zip and renders them with a Markdown/HTML preview (Markwon).
- **Images** — full-screen viewer with pinch-zoom, double-tap zoom, and horizontal paging through every image in the same folder.
- **Text / code / Markdown** — opened in the Workspace editor, parsed into typed blocks.

### Media
- Media library built on `MediaStore`: All / Photos / Videos / Audio tabs, search, multi-select, and a rescan action.
- Media home dashboard with recent items, category cards (Downloads, Images, Videos, Audio, Documents, Apps), starred files, safe folder, per-app storage usage, and a storage summary card.
- Playback with **Media3/ExoPlayer**, including HLS streams; audio list grouped by day.
- Full-screen player with transport controls, seek bar, and playlist navigation across the visible items.

### Workspace (block editor + AI)
- Notion-style block editor with block types: Text, H1–H3, Bulleted list, Numbered list, To-do, Quote, Code, Divider.
- Per-block actions: change type, duplicate, delete, move up/down; insert after any block.
- Markdown/plain-text files are parsed into blocks (`#`, `##`, `###`, `>`, `- [ ]`, `- [x]`, `-`/`*`, `1.`, fenced code).
- Debounced autosave (700 ms) plus flush on `ON_STOP`, on dispose, and in `onCleared`, so content survives process death.
- AI actions on the document (e.g. summarise) using the local model.

### Storage analytics
- Recursive folder-size scan with a ranked top-30 bar chart.
- Duplicate finder: size grouping followed by SHA-256 hashing, with multi-select cleanup and freed-space accounting.
- Large-file finder with select-all and batch delete.
- Optional AI "tidy up" suggestion generated from the scan results.

### Network hub
- **Embedded FTP server** as a foreground service (`Apache MINA FTP Server`), serving external storage read/write on an auto-selected port starting at 2221, with the address shown in a notification.
- **SFTP client** (`JSch`): connect, list, download, upload, delete.
- **SMB 2/3 client** (`jcifs-ng`): anonymous or NTLM-authenticated share browsing, download, upload, delete.

### On-device AI
- Local GGUF chat assistant powered by `llama.cpp` compiled into the app via JNI.
- Curated model catalog with one-tap download from Hugging Face, resumable-safe `.part` downloads, GGUF magic-number verification, real HTTP error reporting, and cancellation.
- Streaming token-by-token output with stop, elapsed time, and token count.
- Configurable persona, custom system prompt, and persistent "memory" text appended to the system prompt.

### Settings
- FTP server toggle, server address, and port.
- Theme selection (System / Light / Dark) applied immediately and persisted.
- Import a `.gguf` model file; open the models folder path.
- Clear app data (settings, cached models, workspace document) and manage "All files access".

---

## Screens and navigation

`MainActivity` hosts a single `Scaffold` with a 7-item `NavigationBar` and a Compose `NavHost`. Viewer routes hide the bottom bar.

| Route | Screen | Notes |
|---|---|---|
| `files` | `FileBrowserScreen` | Start destination; root of external storage |
| `workspace` | `WorkspaceEditorScreen` | Block editor; shared with AI `ViewModel` |
| `storage` | `StorageAnalyticsScreen` | Folder sizes, duplicates, large files |
| `network` | `NetworkScreen` | FTP / SFTP / SMB |
| `media?path={path}` | `MediaPlayerScreen` | Media home + player; optional initial file |
| `ai` | `AiPanel` | Model management and chat |
| `settings` | `SettingsScreen` | App configuration |
| `pdf_viewer?path=` | `PdfViewerScreen` | Full-screen viewer |
| `docx_viewer?path=` | `DocxViewerScreen` | Full-screen viewer |
| `epub_viewer?path=` | `EpubViewerScreen` | Full-screen viewer |
| `image_viewer?path=` | `ImageViewerScreen` | Full-screen viewer |

**File routing.** `openFileWithAppropriateHandler` in `MainActivity.kt` inspects the extension and dispatches to the matching in-app viewer: images → image viewer, `pdf` → PDF, `doc`/`docx` → DOCX, `epub` → EPUB, audio/video → media player, and a set of text/code extensions (`txt, md, log, csv, json, xml, yaml/yml, py, js, ts, jsx, tsx, java, kt, kts, c, cpp, h, hpp, cs, go, rs, rb, sh, bat, toml`) → the Workspace editor. Anything else is handed to another installed app through `FileProvider`.

Optional path arguments are URL-encoded on navigation and `Uri.decode`d on arrival, so file names with spaces or special characters work.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ Presentation (Jetpack Compose, Material 3)                       │
│  MainActivity → NavHost → 7 tab screens + 4 viewer screens       │
│  Each screen owns or receives a ViewModel exposing StateFlow     │
└───────────────┬──────────────────────────────────────────────────┘
                │ collectAsState()
┌───────────────▼──────────────────────────────────────────────────┐
│ ViewModels (lifecycle-scoped, coroutine-driven)                  │
│  FileBrowserViewModel · WorkspaceEditorViewModel · AiViewModel   │
│  NetworkViewModel · MediaPlayerViewModel · MediaHomeViewModel    │
│  StorageAnalyticsViewModel · PdfViewer/Docx/Epub ViewModels      │
└───────────────┬──────────────────────────────────────────────────┘
                │ suspend / Flow on Dispatchers.IO or .Default
┌───────────────▼──────────────────────────────────────────────────┐
│ Platform & domain services (singletons / plain classes)          │
│  FileActions · Starred/SafeFolder · VideoThumbs                  │
│  AIModelDownloader · OfflineAIEngine · StudyEngine               │
│  NetworkClientRepository (SFTP) · SmbClient · FtpServerService   │
└───────────────┬──────────────────────────────────────────────────┘
                │ JNI
┌───────────────▼──────────────────────────────────────────────────┐
│ Native layer (C++17)                                             │
│  native-llama-bridge.cpp → libworkspaceai.so → llama.cpp (CPU)   │
└──────────────────────────────────────────────────────────────────┘
```

**Conventions used throughout**

- **Unidirectional state.** ViewModels hold `MutableStateFlow` and expose read-only `StateFlow`; composables collect them. No mutable Compose state leaks into ViewModels.
- **Main-thread safety.** All filesystem, network, hashing, PDF and inference work runs on `Dispatchers.IO` or `Dispatchers.Default`.
- **Structured concurrency.** Long jobs live in `viewModelScope`; cancelling the screen cancels the work.
- **Single source of truth for side effects.** Filesystem mutations and intents are centralised in `util/FileActions.kt` rather than reimplemented per screen (this fixed `FileUriExposedException` and silent delete failures).
- **Persistence.** Small settings in `SharedPreferences`; the workspace document as a JSON file; models in app-specific external storage.

---

## Package map

```
src/main/
├── AndroidManifest.xml
├── cpp/
│   ├── CMakeLists.txt              # builds libworkspaceai.so + llama.cpp
│   ├── native-llama-bridge.cpp     # JNI: load / generate / cancel / free
│   ├── LLAMA_SETUP.md              # how to vendor llama.cpp + get a GGUF
│   └── llama.cpp/                  # vendored upstream source tree
├── res/
│   ├── values/{strings,themes}.xml
│   └── xml/{file_paths,backup_rules,data_extraction_rules}.xml
└── java/com/example/workspacefilemanager/
    ├── MainActivity.kt             # nav graph, permission bootstrap, file routing
    ├── ai/
    │   ├── AIModels.kt             # curated GGUF catalog + storage paths
    │   ├── AIModelDownloader.kt    # OkHttp download, .part file, GGUF check
    │   ├── OfflineAIEngine.kt      # Kotlin facade over the JNI bridge
    │   └── AiPanel.kt              # AiViewModel + chat/model UI
    ├── files/
    │   ├── FileBrowserScreen.kt    # FileBrowserViewModel + browser UI
    │   ├── FileOpeners.kt          # PDF / DOCX / EPUB viewers
    │   └── ImageViewerScreen.kt    # zoomable, swipeable gallery viewer
    ├── media/
    │   ├── MediaPlayerScreen.kt    # MediaStore model, grid, ExoPlayer screen
    │   └── MediaHomeScreen.kt      # dashboard: recents, categories, apps, safe
    ├── network/
    │   ├── NetworkClients.kt       # SFTP (JSch) + SMB (jcifs-ng) clients
    │   ├── FtpServerService.kt     # foreground FTP server (Apache MINA)
    │   ├── NetworkViewModel.kt     # connection + listing state
    │   └── NetworkScreen.kt        # FTP toggle, connect dialogs, browsers
    ├── storage/
    │   ├── StorageAnalyticsScreen.kt # ViewModel, scan, chart, cleanup UI
    │   └── StorageAnalyzer.kt      # standalone Flow-based scanner (unused)
    ├── study/
    │   ├── StudyMode.kt            # question model (MCQ/TF/short/cloze)
    │   ├── StudyEngine.kt          # extractive question generation
    │   └── StudyScreen.kt          # quiz UI (not yet wired into navigation)
    ├── ui/
    │   ├── SettingsScreen.kt
    │   └── theme/WorkspaceTheme.kt # light/dark schemes + dynamic color
    ├── util/
    │   ├── FileActions.kt          # MIME, URIs, share, delete/copy/move, format
    │   ├── Starred.kt              # Starred + SafeFolder
    │   └── VideoThumbs.kt          # cached video frame thumbnails
    └── workspace/
        └── WorkspaceEditor.kt      # block model, ViewModel, editor UI
```

### Key types

| Type | Location | Role |
|---|---|---|
| `Screen` | `MainActivity.kt` | Sealed nav destinations with label + icon |
| `FileItem` | `files/FileBrowserScreen.kt` | Immutable file row model (name, size, date, ext, isDir) |
| `SortMode` | `files/FileBrowserScreen.kt` | Name / Size / Modified / Type |
| `ClipboardEntry` | `files/FileBrowserScreen.kt` | Pending cut-or-copy source |
| `Block` / `BlockType` / `WorkspaceDoc` | `workspace/WorkspaceEditor.kt` | Editor document model with JSON (de)serialisation |
| `ChatMessage` / `ModelStatus` | `ai/AiPanel.kt` | Chat transcript and model lifecycle states |
| `AIModels.Model` | `ai/AIModels.kt` | Catalog entry (repo, filename, size, context, tags) |
| `MediaEntry` / `MediaKind` / `MediaTab` | `media/MediaPlayerScreen.kt` | MediaStore item and filtering |
| `Category` / `HomeSection` | `media/MediaHomeScreen.kt` | Dashboard categories and sub-screens |
| `SftpEntry` / `SmbEntry` | `network/NetworkClients.kt` | Remote directory listings |
| `StudyMode` / `Question` | `study/StudyMode.kt` | Study quiz contract |

---

## On-device AI

### Pipeline

```
AIModels catalog ──► AIModelDownloader ──► <externalFilesDir>/models/*.gguf
                                                │
AiViewModel.ask() ──► OfflineAIEngine.generate ──► JNI ──► llama.cpp (CPU)
        ▲                       │
        └── streaming tokens ◄──┘  (background thread, cancellable)
```

### Model catalog

| Model | Repo | Quant | Size | Context |
|---|---|---|---|---|
| SmolLM2 360M | `bartowski/SmolLM2-360M-Instruct-GGUF` | Q4_K_M | ~271 MB | 2048 |
| Qwen2.5 0.5B | `Qwen/Qwen2.5-0.5B-Instruct-GGUF` | Q4_K_M | ~491 MB | 4096 |
| Llama 3.2 1B | `bartowski/Llama-3.2-1B-Instruct-GGUF` | Q4_K_M | ~808 MB | 4096 |
| Qwen2.5 1.5B | `bartowski/Qwen2.5-1.5B-Instruct-GGUF` | Q4_K_M | ~986 MB | 4096 |
| Gemma 2 2B | `bartowski/gemma-2-2b-it-GGUF` | Q4_K_M | ~1.7 GB | 4096 |
| Phi-3 Mini 4K | `bartowski/Phi-3-mini-4k-instruct-gguf` | Q4_K_M | ~2.4 GB | 4096 |

Models are ordered smallest-first (small models are the ones that stay usable on a phone CPU). Any other `.gguf` can be imported from Settings or picked with the in-app GGUF browser.

### Download reliability

`AIModelDownloader` writes to `<name>.part`, verifies the `GGUF` magic bytes, then renames — so an HTML error page or a dropped connection can never be mistaken for a model. It also:

- checks free space via `StatFs` before starting,
- reports HTTP status in plain language (404 = wrong filename, 401/403 = gated repo),
- keeps the OkHttp `Call` so Cancel actually aborts the transfer,
- throttles progress callbacks to ~200 ms.

### Native bridge API (`libworkspaceai.so`)

`OfflineAIEngine` declares these JNI functions, implemented in `native-llama-bridge.cpp`:

| Declaration | Purpose |
|---|---|
| `loadModel(path, progressCallback): Boolean` | Load GGUF, report 0–100 % progress |
| `generateStream(system, user, maxTokens, callback): String` | Stream tokens; returns the full answer |
| `cancelGeneration()` | Stop the in-flight loop (lock-free flag) |
| `freeModel()` | Release native memory |
| `isModelLoaded(): Boolean` | Health/state check |
| `recommendedThreads(): Int` | Core count chosen for the device |

Native-layer behaviour worth knowing:

- Threads = `hardware_concurrency() - 2`, clamped to **2–6** (phones throttle at 100 % CPU).
- KV cache is cleared before every request, so later answers are not progressively slower.
- Prompts are decoded in `n_batch`-sized chunks; a single oversized decode silently fails.
- The model's own chat template is applied, avoiding the garbage output you get when one model's special tokens are used on another.
- Token pieces that split a UTF-8 character are buffered until complete before crossing JNI.
- Limits: `MAX_CONTEXT_TOKENS = 4096`, `MAX_NEW_TOKENS = 512`, `BATCH_SIZE = 512`; Kotlin default answer cap is 320 tokens.
- Generation is serialised by a mutex that also guards load/free; **cancel deliberately does not take that lock**.

The model is **not** loaded at app start (a 0.3–2 GB load would make the app feel broken); it loads when the AI screen becomes visible, or on demand.

---

## Network features

### Embedded FTP server

`FtpServerService` is a `dataSync` foreground service wrapping `Apache MINA FTP Server`.

- Root directory: `Environment.getExternalStorageDirectory()` (falls back to app `filesDir` if unreadable).
- Anonymous user with write permission; a user properties file is written to app-private storage.
- Port: first free port from **2221** upward (probed with `ServerSocket`), idle timeout 600 s.
- IPv4 address derived from `WifiManager`; the `ftp://ip:port` address is shown in an ongoing notification.
- Started/stopped with `ACTION_START_SERVER` / `ACTION_STOP_SERVER` from `NetworkViewModel`.

> The server is intentionally simple and unauthenticated: it is meant for a trusted LAN, and it exposes the whole of shared storage to anyone who can reach the port.

### SFTP and SMB

- `NetworkClientRepository` wraps JSch: password auth, `StrictHostKeyChecking=no`, 10 s connect / 5 s channel timeouts, and `ls`/`get`/`put`/`rm`.
- `SmbClient` wraps jcifs-ng with a `SingletonContext`; adds NTLM credentials only when a username is supplied, and supports `listFiles` on a share or path, plus stream copy for download/upload and `delete()`.

Both clients return empty lists / `false` on failure and log the reason; the UI surfaces a toast.

---

## File format support

| Category | Extensions | Handler |
|---|---|---|
| PDF | `pdf` | `PdfRenderer` viewer |
| Word | `doc`, `docx` | `docx` is a zip; text is extracted from `word/document.xml`. Legacy binary `doc` also routes to this viewer, so it will show an extraction error — open it with another app |
| E-book | `epub` | Zip + chapter HTML, Markwon rendering |
| Images | `jpg jpeg png gif webp bmp heic heif svg` | Coil-backed viewer, gallery paging |
| Video | `mp4 mkv avi mov webm 3gp m4v` | ExoPlayer (HLS manifests supported) |
| Audio | `mp3 aac flac wav ogg m4a opus` | ExoPlayer |
| Text / code | `txt md log csv json xml yaml yml py js ts jsx tsx java kt kts c cpp h hpp cs go rs rb sh bat toml` | Workspace block editor |
| Share/open | any | `FileProvider` content URI + system chooser |

MIME mapping for ~35 extensions lives in `FileActions.mimeTypeForName`.

---

## Storage model and app data

| What | Where | Notes |
|---|---|---|
| Downloaded / imported models | `<externalFilesDir>/models/` | Falls back to internal `filesDir/models`; ~0.3–2.4 GB each |
| Workspace document | `filesDir/workspace_doc.json` | Single active document, autosaved |
| Safe folder | `filesDir/safe_folder/` | App-private; invisible to gallery/other apps |
| Starred paths | `SharedPreferences("starred_files")` | Set of absolute paths; missing files pruned |
| Theme selection | `SharedPreferences("app_settings")` | `theme_selection`: 0 system, 1 light, 2 dark |
| AI settings | `SharedPreferences("ai_prefs")` | model path, memory, system prompt, persona |
| FTP users file | `filesDir/ftpusers.properties` | Written by the service |
| FileProvider roots | `res/xml/file_paths.xml` | External storage, external files, internal files, cache |

**App data reset** (Settings → Clear App Data) wipes settings, cached models and the workspace document; it does not touch user files in shared storage.

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Model downloads, SFTP/SMB, FTP address discovery |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep the FTP server alive |
| `MANAGE_EXTERNAL_STORAGE` | Full filesystem browsing and deletion on Android 11+ (requested via Settings screen) |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=29`) | Legacy access on Android 8–10 |

On launch, `MainActivity` requests "All files access" on API 30+ and `READ_EXTERNAL_STORAGE` on API 26–29. Without it, browsing still works where the OS allows, but delete/move operations return a permission-specific message rather than failing silently.

---

## Build and run

### Requirements

- Android Studio (Ladybug or newer recommended) with SDK **35** and Build Tools matching AGP.
- **JDK 17** (source/target compatibility is 17).
- Android **NDK** with CMake **3.22.1** (installed via SDK Manager) — needed for the AI native library.
- A device or emulator on API 26+. `arm64-v8a` and `x86_64` are packaged.

### Vendor `llama.cpp` first

The native build expects the upstream source tree at `src/main/cpp/llama.cpp`:

```bash
cd src/main/cpp
git clone https://github.com/ggml-org/llama.cpp.git llama.cpp
```

`CMakeLists.txt` adds that tree via `add_subdirectory`, disables every GPU backend (CUDA, Metal, OpenCL, HIP, SYCL, Vulkan, Kompute, RPC, WebGPU, WebNN, BLAS), builds `libworkspaceai.so` from `native-llama-bridge.cpp`, and links it against `llama`, `android` and `log`. The build uses `-std=c++17 -O2`, `c++_shared` STL and ARM NEON.

### Gradle

```bash
# From the repository root (the root build.gradle.kts is the app module)
./gradlew assembleDebug
./gradlew installDebug
```

> **Repository state note:** this checkout contains a single module whose build script sits at the repository root (`build.gradle.kts`, namespace `com.example.workspacefilemanager`), along with a compiled artefact at `build/outputs/apk/debug/app-debug.apk`. There is currently **no `settings.gradle(.kts)` and no Gradle wrapper** in the tree; to build in Android Studio either open it and let Studio generate the missing Gradle scaffolding, or add a `settings.gradle.kts` declaring the root project and `gradle wrapper` files.

Alternatively build the module directly if you have a local Gradle install:

```bash
gradle assembleDebug
```

### Getting a model onto the device

1. Open the **AI** tab and pick a model from the catalog (recommended: **Qwen2.5 0.5B** or **Llama 3.2 1B**), or
2. Settings → **Import .gguf model**, or
3. Copy a `.gguf` file to the phone and select it in the AI screen's file browser.

Then tap **Load model** and wait for the progress to reach 100 %. `src/main/cpp/LLAMA_SETUP.md` documents the manual `adb push` route.

### Testing

JUnit and Compose UI test dependencies are declared, but this tree contains **no test sources** for the app module; treat test coverage as absent.

---

## Design system

- Material 3 with a hand-tuned blue-violet palette (`WorkspaceTheme.kt`): primary `#1B6EF3` in light mode, `#AEC6FF` in dark, matched containers, surfaces and error colors.
- **Dynamic color** (Material You) is enabled by default on Android 12+; the static schemes are the fallback.
- Theme mode follows the system unless the user picks Light or Dark in Settings; the choice applies immediately and is persisted, and status-bar icon appearance is updated via `WindowInsetsControllerCompat`.
- Typography is the stock Material 3 scale; the 7-tab bottom bar uses `labelSmall` with `softWrap = false` so labels stay on one line at ~57 dp per tab.

---

## Known limitations and unfinished work

These are current facts about the codebase, not aspirations:

- **`StudyScreen` is not wired into navigation.** The quiz UI, `StudyMode` and `StudyEngine` compile but nothing navigates to them, and `MainActivity` has no Study tab. The engine is also purely extractive and heuristic (it blanks keywords and forms distractors by shuffling), so generated questions are placeholders rather than a real quiz.
- **`StorageAnalyzer.kt` is unused.** `StorageAnalyticsScreen` implements its own scanning with `walkTopDown` and inline hashing. The Flow-based class remains as dead code.
- **`app/` is a stale duplicate scaffold** containing only an `AndroidManifest.xml` and `res/xml/file_paths.xml`; it is not part of the Gradle build (the build script is at the root and `src/` is the source set).
- **`updates/` is not app source.** It holds device screenshots and an extracted third-party APK (`extracted_apk/`, an UpToDown bundle with Firebase/GMS classes) — unrelated build/QA leftovers and a large contributor to repository size.
- **No Gradle wrapper / `settings.gradle`** in the tree (see build notes above).
- **The FTP server is anonymous and read-write** over all of external storage, with no TLS and no user accounts. LAN-trusted use only.
- **SMB browsing returns names only** (no paging or path-aware credential scoping beyond the supplied share/path), and SFTP disables host-key checking.
- **AI is CPU-only and single-conversation.** No GPU/NNAPI backend is enabled, 512-token answers are the hard ceiling, and conversation history is not persisted across app restarts.
- **Model memory** is not released when leaving the AI tab; `freeModel()` is available but only called explicitly.
- **No instrumentation tests, no CI configuration, no lint baseline.**

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "No app installed that can open …" | No external handler; in-app viewers cover PDF/DOCX/EPUB/images/media/text. Otherwise install a viewer. |
| Delete does nothing / "Permission denied" | Grant **All files access** (Settings → Storage Permissions) or Android has locked the folder (e.g. `Android/data`). |
| AI tab shows "No model loaded" | Download or import a `.gguf`, then Load. Check the model directory path in Settings → Models folder. |
| Download ends immediately with an error | 404 means the filename is wrong; 401/403 means the Hugging Face repo is gated. The catalog URLs are validated, custom URLs are not. |
| Answer is very slow after the second question | Should not happen — the bridge clears the KV cache per request. If it does, the device is likely thermally throttled. |
| FTP address unreachable | Phone and client must be on the same Wi-Fi; some APs isolate clients. Port starts at 2221 and moves up if busy. |
| PDF viewer blank or OOM | Pages render lazily at screen width with a byte-bounded LRU; if a page still fails, the bitmap cache is capped at ~1/8 of max heap. |
| Model download fills storage | Free space is checked before download; models live in app-specific external storage and are removed by Clear App Data. |
| App name is "Workspace File Manager" | `res/values/strings.xml` → `app_name`. |

---

*Documentation generated from a full read of the app module's sources (`src/main/**`, `build.gradle.kts`, manifest, native CMake and JNI bridge).*
