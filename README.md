<div align="center">

<img src="LauncherV15/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="104" alt="BareLauncher icon" />

# BareLauncher

**The launcher your TV actually deserves — fast, minimal, out of your way.**

A tiny, privacy-first home screen for Android TV, Google TV & Fire TV.
No ads. No telemetry. No recommendations. Just your apps.

[![Latest release](https://img.shields.io/github/v/release/f102mithunysypdlcjr-pixel/BareLauncherv3?style=for-the-badge&color=22c55e&label=latest)](../../releases/latest)
[![Download APK](https://img.shields.io/badge/Download-APK-09090b?style=for-the-badge&logo=android&logoColor=22c55e)](../../releases/latest/download/BareLauncher.apk)
[![Downloader code 3465597](https://img.shields.io/badge/Downloader-3465597-FF6B2C?style=for-the-badge&logo=amazonfiretv&logoColor=white)](https://aftv.news/3465597)
[![Website](https://img.shields.io/badge/Website-Live-3A86FF?style=for-the-badge)](https://f102mithunysypdlcjr-pixel.github.io/BareLauncherv3/)
[![License](https://img.shields.io/badge/License-PolyForm%20NC%201.0-8b5cf6?style=for-the-badge)](./LICENSE)

</div>

---

Most TV launchers are heavy, online, and crowded with promotions. BareLauncher is the opposite: a quiet, instant home screen that stays out of your way and respects your hardware. The whole APK is **~140 KB** with **zero runtime dependencies**.

> **instant navigation · clean UI · low memory · zero distractions**

## Screenshots

<sub><i>Click any image to open full size · scroll horizontally →</i></sub>

<div align="center">
<table>
  <tr>
    <td align="center">
      <a href="https://github.com/user-attachments/assets/48a3ac80-d467-47ba-b264-838db271ceaf" target="_blank" rel="noopener">
        <img src="https://github.com/user-attachments/assets/48a3ac80-d467-47ba-b264-838db271ceaf" alt="BareLauncher home screen" width="460" loading="lazy" />
      </a><br/><sub><b>Home</b></sub>
    </td>
    <td align="center">
      <a href="https://github.com/user-attachments/assets/5b402ada-94e0-4d23-a890-0e3528ca3714" target="_blank" rel="noopener">
        <img src="https://github.com/user-attachments/assets/5b402ada-94e0-4d23-a890-0e3528ca3714" alt="BareLauncher app drawer" width="460" loading="lazy" />
      </a><br/><sub><b>App drawer</b></sub>
    </td>
    <td align="center">
      <a href="https://github.com/user-attachments/assets/f07f346a-eb6f-462a-b168-5dbf6160c164" target="_blank" rel="noopener">
        <img src="https://github.com/user-attachments/assets/f07f346a-eb6f-462a-b168-5dbf6160c164" alt="BareLauncher reorder and manage menu" width="460" loading="lazy" />
      </a><br/><sub><b>Reorder &amp; manage</b></sub>
    </td>
    <td align="center">
      <a href="https://github.com/user-attachments/assets/5a8b4e3a-493c-47e4-9495-c8320fe9d32f" target="_blank" rel="noopener">
        <img src="https://github.com/user-attachments/assets/5a8b4e3a-493c-47e4-9495-c8320fe9d32f" alt="BareLauncher button remapper" width="460" loading="lazy" />
      </a><br/><sub><b>Button shortcuts</b></sub>
    </td>
  </tr>
</table>
</div>

---

## Features

| | |
|---|---|
| ⚡ **Instant navigation** | Zero-lag scrolling on a TV remote, via a custom recycling shelf — no `RecyclerView`. |
| 🗂️ **Home row + app drawer** | Favourites on the home screen, a pull-down drawer for the rest. Reorder freely with the D-pad. |
| 🎛️ **Button shortcuts** | Assign remote colour/menu keys to any app, right from the home screen — no Accessibility Service. |
| 🗃️ **Manage apps** | Long-press any app to move, hide, open App info, or uninstall. Hidden apps stay installed, just off your home screen. |
| 🖼️ **Wallpaper support** | Any wallpaper, rendered with hardware bitmaps that save 8–32 MB of RAM. |
| 🕒 **3-state clock** | Full (time + day/date), time-only, or off. Locale-aware, 12/24-hour, instant timezone updates. |
| 📶 **Wi-Fi & settings pills** | Live Wi-Fi indicator, plus a gear pill — long-press it to open system settings instantly. |
| ℹ️ **About screen** | Version, links and a Ko-fi QR generated **on-device** — no library, no network. |
| 🚫 **Zero telemetry** | No analytics, no crash reporting, no background calls. Your usage stays on your device. |
| 🚀 **Cold-start cache** | Wallpaper, shelf, and icons paint in the very first frame after the first launch. |

---

## Why it's so small

**~140 KB**, one universal APK, every Android architecture. The size is a result of the engineering, not the goal:

- Zero runtime AndroidX — no AppCompat, Material, RecyclerView, ConstraintLayout, Lifecycle.
- Fully programmatic UI — no XML layouts, no inflated resource bloat.
- Custom recycling shelf instead of `RecyclerView`.
- R8 minification + resource shrinking on every release.
- Pure Java; no Kotlin standard library shipped.
- No native code, no `lib/` folder — same APK on ARMv7, ARMv8 and x86_64.

---

## Compatibility

- **Android TV / Google TV** — all modern devices (Android 8.0 / API 26+)
- **Fire TV Stick** — 4K, 4K Max, Lite
- **Fire TV Cube** — all generations
- **Mi Box / Mi Stick** and other Android TV boxes

---

## Install

### Downloader

Open the **Downloader** app (by AFTVnews) and enter the code below. It always installs the **latest release** — no computer, no USB stick.

<div align="center">

<a href="https://aftv.news/3465597" target="_blank" rel="noopener">
  <img src="https://img.shields.io/badge/AFTVnews%20Downloader-Code%203465597-FF6B2C?style=for-the-badge&logo=amazonfiretv&logoColor=white" alt="BareLauncher Downloader code 3465597 — always the latest release" height="44" />
</a>

</div>

### Or sideload the APK manually

1. Download **[`BareLauncher.apk`](../../releases/latest/download/BareLauncher.apk)** from the latest release.
2. Move it to your TV — USB stick, network share, or an app like *LocalSend*.
3. Allow installs from unknown sources when prompted.
4. Open the APK and install.

> Not on the Play Store or Amazon Appstore — sideload only.

**Make it your default launcher** — BareLauncher doesn't hijack your home button via Accessibility Services. Set it as home with:

- **Launcher Manager (XDA)** — recommended; works on Google TV, Android TV and Fire TV.
- **Button Mapper** — remap the remote's home button to open BareLauncher.

---

## Philosophy

Every line has to justify its performance cost. No needless animations, no background junk, no overdesigned UI — just a launcher that feels **instant**.

---

## Support

BareLauncher is **completely free** and always will be. If it made your TV feel fast again, a coffee genuinely helps keep it going. ☕

<div align="center">
<a href="https://ko-fi.com/barelauncher" target="_blank" rel="noopener">
  <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support BareLauncher on Ko-fi" />
</a>
</div>

---

## License

**PolyForm Noncommercial License 1.0.0** — free for personal and non-commercial use. See [LICENSE](./LICENSE) and [NOTICE.md](./NOTICE.md).

<div align="center">
<sub>Made with care for people who just want their TV to work.</sub>
</div>
