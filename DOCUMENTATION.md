# WorkspaceFileManager — Project Documentation

**Package:** `com.example.workspacefilemanager`
**Min SDK:** 26 (Android 8.0) · **Target SDK:** 35
**Language:** Kotlin · **UI:** Jetpack Compose Material 3
**Total lines of code:** ~5,100 Kotlin lines across 14 source files

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Module Breakdown](#module-breakdown)
4. [Dependencies](#dependencies)
5. [Screen Reference](#screen-reference)
6. [Network Services](#network-services)
7. [AI Engine](#ai-engine)
8. [Build & Install](#build--install)
9. [Known Limitations](#known-limitations)
10. [Future Roadmap](#future-roadmap)

---

## Overview

WorkspaceFileManager is a full-featured Android file manager built entirely in Jetpack Compose. It integrates four major subsystems:

| Phase | subsystem | Description |
|-------|-----------|-------------|
| 1 | Network Protocol Hub | Embedded FTP server + SFTP/SMB clients |
| 2 | Analytics & Storage | Coroutine-based storage scanner with duplicate detection |
| 3 | Workspace Productivity | Markdown editor with live preview |
| 4 | On-Device AI | llama.cpp JNI for offline LLM inference |

---

## Architecture

```
MainActivity.kt                     ← Entry point, NavHost, shared ViewModel factories
├── WorkspaceApp()                  ← Root composable with NavigationBar + NavHost
│   ├── FileBrowserScreen           ← Internal + SAF file tree
│   ├── WorkspaceEditorScreen       ← Markdown editor + preview
│   ├── StorageAnalyticsScreen      ← Storage telemetry dashboard
│   ├── NetworkScreen               ← FTP / SFTP / SMB browser
│   ├── MediaPlayerScreen           ← Video/audio player (Media3)
│   ├── AiPanel                     ← llama.cpp chat interface
│   └── SettingsScreen              ← FTP toggle + app settings
│
Shared ViewModels (singleton, survive nav):
  ├── NetworkViewModel              ← FTP state, IP, connection
  ├── FileBrowserViewModel          ← Current directory, file list
  ├── WorkspaceEditorViewModel      ← Loaded file, markdown render
  └── AiViewModel                   ← Inference engine, chat history
```

**Key architectural decisions:**

- All ViewModels are created with `viewModel()` in `WorkspaceApp()`, making them scoped to the activity — they survive bottom-nav switches.
- `FtpServerService` is a `ForegroundService` (type `dataSync`) running Apache MINA FTP Server on a configurable port.
- The llama.cpp native library is compiled via CMake with NEON SIMD enabled for arm64-v8a.
- Coroutines (`Dispatchers.IO` for disk/network, `Dispatchers.Default` for CPU work) power all background scanning and inference.

---

## Module Breakdown

### `files/` — File Browser
| File | Lines | Purpose |
|------|-------|---------|
| `FileBrowserScreen.kt` | 388 | Main file-list UI; supports internal storage and SAF picks |
| — | — | Long-press shows action sheet (Open, Delete, Rename, Share) |
| — | — | Recursive directory traversal using `File.listFiles()` |

### `workspace/` — Markdown Editor
| File | Lines | Purpose |
|------|-------|---------|
| `WorkspaceEditor.kt` | 550 | Split-pane editor: raw text input + Markwon-rendered preview |
| `WorkspaceEditorViewModel.kt` | — | Manages currently loaded file path and rendered content |

### `storage/` — Analytics & Telemetry
| File | Lines | Purpose |
|------|-------|---------|
| `StorageAnalyticsScreen.kt` | 659 | Dashboard with storage bars, large-file list, duplicate detector |
| `StorageAnalyzer.kt` | 238 | Coroutine-based scanner using `DiskStats` and `ContentResolver` |

### `network/` — Network Protocol Hub
| File | Lines | Purpose |
|------|-------|---------|
| `FtpServerService.kt` | 211 | Foreground service wrapping Apache MINA `FtpServer` |
| `NetworkClients.kt` | 283 | SFTP (`JSch`) and SMB (`jcifs-ng`) connection helpers |
| `NetworkScreen.kt` | 587 | Tabbed UI for FTP status + SFTP/SMB browse dialogs |
| `NetworkViewModel.kt` | — | Shared state: `ftpRunning`, `ftpAddress`, `ftpPort` |

### `ai/` — On-Device Mini AI
| File | Lines | Purpose |
|------|-------|---------|
| `OfflineAIEngine.kt` | 145 | JNI wrapper around compiled `libllama.so` |
| `AIModelDownloader.kt` | 135 | OkHttp-based GGUF model fetch from Hugging Face |
| `AIModels.kt` | 137 | Model catalogue (TinyLlama, Phi-2, etc.) with sizes |
| `AiPanel.kt` | 903 | Full chat UI: message list, input bar, model selector |

### `media/` — Media Player
| File | Lines | Purpose |
|------|-------|---------|
| `MediaPlayerScreen.kt` | 471 | ExoPlayer/Media3 wrapper; supports video + audio playback |

### `ui/` — UI Components
| File | Lines | Purpose |
|------|-------|---------|
| `SettingsScreen.kt` | 184 | FTP server toggle; reads state from shared `NetworkViewModel` |
| `theme/WorkspaceTheme.kt` | — | Material 3 color scheme (light/dark aware) |

---

## Dependencies

### Core
| Library | Version | Role |
|---------|---------|------|
| Kotlin | 2.0.0 | Language |
| Gradle AGP | 8.5.2 | Build system |
| Compose BOM | 2024.06.00 | Keeps all Compose libs aligned |
| Material 3 | (BOM-managed) | UI components |
| Navigation Compose | 2.7.7 | Bottom-nav routing |
| Lifecycle ViewModel Compose | 2.8.3 | `viewModel()` factory |
| Coroutines | 1.8.1 | Async operations |

### Domain-Specific
| Library | Version | Role |
|---------|---------|------|
| Apache MINA FTP Server | 1.2.1 | Embedded FTP server |
| jcifs-ng | 2.1.4 | SMBv2/v3 client |
| JSch | 0.1.55 | SFTP client |
| Markwon | 4.6.2 | Markdown → Compose rendering |
| Media3 / ExoPlayer | 1.4.1 | Media playback |
| OkHttp | 4.12.0 | GGUF model downloads |
| llama.cpp (JNI) | — | Local LLM inference |

---

## Screen Reference

The app uses a 7-item bottom navigation bar:

| Tab | Route | Icon | Purpose |
|-----|-------|------|---------|
| Files | `files` | Folder | Browse device storage |
| Workspace | `workspace` | Edit | Markdown notes |
| Storage | `storage` | Analytics | Storage breakdown |
| Network | `network` | Wifi | FTP/SFTP/SMB hub |
| Media | `media` | PlayCircle | Video/audio player |
| AI | `ai` | AutoAwesome | Offline LLM chat |
| Settings | `settings` | Settings | FTP toggle |

---

## Network Services

### FTP Server
- **Protocol:** FTP (port configurable, default 2221)
- **Implementation:** Apache MINA `FtpServer` running as a `ForegroundService`
- **Auth:** Anonymous read-only by default; directory = app's cache dir
- **Activation:** Toggle in Network tab or Settings tab (shared `NetworkViewModel`)
- **Permissions required:** `INTERNET`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `MANAGE_EXTERNAL_STORAGE`

### SFTP Client
- **Library:** JSch 0.1.55
- **Auth:** Username/password or SSH key
- **Usage:** Browse remote directories, download files

### SMB Client
- **Library:** jcifs-ng 2.1.4
- **Auth:** NTLM (`NtlmPasswordAuthenticator`)
- **Usage:** Browse Windows/network shares

---

## AI Engine

### llama.cpp Integration
- Native library compiled from upstream `llama.cpp` via CMake
- ABI filters: `arm64-v8a` (device) + `x86_64` (emulator)
- NEON SIMD enabled for performance on ARM
- STL: `c++_shared` (standard shared lib)

### Model Download
- Models downloaded to `filesDir/ai/models/` via OkHttp
- Supports any standard GGUF file (.gguf)
- Pre-catalogued models: TinyLlama, Phi-2, Phi-3, Llama-3-8B variants

### Inference Flow
```
User types prompt → AiViewModel.enqueue() → OfflineAIEngine.translate()
→ Native llama.cpp → callback with response → displayed in chat UI
```

---

## Build & Install

```bash
# Debug build (requires Android SDK + NDK)
./gradlew :app:assembleDebug

# APK location
app/build/outputs/apk/debug/app-debug.apk

# Install to connected device
./gradlew :app:installDebug
```

**Prerequisites:**
- Android Studio Hedgehog+ (or equivalent)
- Android SDK 35
- Android NDK 25.x+
- CMake 3.22.1+

---

## Known Limitations

1. **SMB authentication** — jcifs-ng 2.x API differs from legacy 1.x; password auth uses `NtlmPasswordAuthenticator`, not `NtlmPasswordAuthentication`
2. **FTP anonymous only** — No password-protected FTP in v1; serves app cache directory
3. **AI model size** — Larger models (7B+) require >4 GB RAM; tested with <2GB models on mid-range devices
4. **No media shelf** — Gallery view for images not yet implemented
5. **No cloud sync** — Local storage only; no Google Drive / OneDrive integration

---

## Future Roadmap

| Phase | Feature | Status |
|-------|---------|--------|
| 5 | Cloud storage adapters (Drive, Dropbox) | Planned |
| 6 | Image gallery with camera capture | Planned |
| 7 | File encryption (AES-256) | Planned |
| 8 | Tabbed browsing (multi-folder) | Planned |
| 9 | Contextual share sheet integration | Planned |
| 10 | Widget / Quick-access shortcut | Planned |

---

*Generated 2026-11-03 · WorkspaceFileManager v1.0 · 14 Kotlin source files · 5,107 LOC*
