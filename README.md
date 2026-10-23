<div align="center">

# 🦆 DukeDownloader

### Your retro library starts here.

**Discover · Explore · Download**

[![Android](https://img.shields.io/badge/Platform-Android-18181B?logo=android&logoColor=FFB800)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-18181B?logo=kotlin&logoColor=FFB800)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-FFB800?labelColor=101012)](LICENSE)
[![Development](https://img.shields.io/badge/Status-Active%20development-FF8A00?labelColor=101012)](https://github.com/thecrixux/DukeDownloader)

**A Duke family project · PS2EMU**

</div>

---

## 🎮 What is DukeDownloader?

**DukeDownloader** is an Android app for exploring retro-game catalogs and managing downloads from configurable external sources. It's a customized fork of [Tottodrillo](https://github.com/mccoy88f/Tottodrillo), with its own identity and continuing improvements.

Search for a title, browse platforms, inspect artwork and manage downloads without juggling multiple apps.

> [!NOTE]
> DukeDownloader is under active development. Source availability, cover-art accuracy and compatibility depend on external services and the current build.

## ✨ Highlights

| Feature | Description |
|---|---|
| 🔎 **Multi-source search** | Browse and search across configured catalogs |
| 🎮 **Multi-platform discovery** | Consoles and arcade systems, depending on enabled sources |
| 🖼️ **Game artwork** | Cover lookup, regional matching and graceful fallbacks; continually being improved |
| ⚡ **Download manager** | Background downloads, progress tracking and support for segmented downloading |
| 🧩 **Extensible sources** | Source packages and integrations based on the Tottodrillo architecture |
| 📁 **File management** | Configurable download folder and local file operations |
| 🌍 **Multilingual UI** | Localized interface for supported languages |

## 🟡 Duke Gold design

A dark interface with the signature golden

*Dark surfaces. Golden details. Retro soul.* 🦆

## 🛠️ Technology

**Kotlin · Jetpack Compose · Material 3 · Hilt · Retrofit / OkHttp · WorkManager · Chaquopy / Python**

The source system supports multiple integration methods inherited from Tottodrillo. Individual website integrations may require updates as those websites change.

## 💻 Build locally (Windows / PowerShell)

**Requirements:** JDK 17, Android SDK Platform 34, compatible Android Build-Tools, Python 3.11 and Git.

```powershell
git clone https://github.com/thecrixux/DukeDownloader.git
Set-Location DukeDownloader
java -version
py -3.11 --version
```

Use the build scripts or Gradle wrapper included in the current version of the project. Build and sign Release APKs **locally** with your own keystore. Never commit signing keys, passwords, SDK-specific paths or generated build files.

## 🚧 Project status

The project is evolving. Not all games have artwork, and external download sources can change or become unavailable. Issues and device testing help us improve reliability.

[**Report a bug**](https://github.com/thecrixux/DukeDownloader/issues) · [**Browse source**](https://github.com/thecrixux/DukeDownloader)

## 🙏 Credits & license

DukeDownloader is based on **[Tottodrillo by mccoy88f](https://github.com/mccoy88f/Tottodrillo)**. Thanks to its author and contributors for the original architecture and source-management foundation.

Original source-development documentation: **[Tottodrillo-Source](https://github.com/mccoy88f/Tottodrillo-Source)**.

See [LICENSE](LICENSE) (MIT) and retain upstream / third-party notices where required.

## ⚖️ Disclaimer

This project does **not** ship commercial game ROMs. Users must ensure they have the legal right to access and download any content and comply with applicable copyright laws. External websites and sources are independent of DukeDownloader.

---

<div align="center">

**Made for retro gaming. Powered by curiosity.** 🦆

**DukeDownloader · PS2EMU**

</div>
