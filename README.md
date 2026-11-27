# ⚡ Speed Force

**Fast. Private. Ad-free.**
A light browser for Android and Windows with built-in ad and tracker blocking and a downloader.
No news feed. No clutter.

## Download

Get the latest version from the [Releases](../../releases/latest) page.

| Device | File |
|---|---|
| Android (most phones) | `SpeedForce-Android-arm64.apk` |
| Android (if the file above will not install) | `SpeedForce-Android-universal.apk` |
| Windows 10 / 11 (installer) | `SpeedForce-Setup.exe` |
| Windows 10 / 11 (no install) | `SpeedForce-Windows-portable.zip` |

**Android:** open the APK on your phone and allow installing from that source. To update, install the new
APK over the old one. Your settings and download list are kept.

**Windows:** run `SpeedForce-Setup.exe`, or unzip the portable file into its own folder and run
`SpeedForce.exe`. Windows may warn about an unknown publisher because the app is not code-signed. It uses the Microsoft Edge WebView2
runtime, which most Windows 10 and 11 PCs already have.

## What it does

### Both versions
- **Blocks ads and trackers.** Choose *Aggressively block*, *Block*, or *Allow all*. You can switch
  protection off for a single site.
- **Stops popups and forced redirects**, and keeps a log of what it stopped.
- **Downloads videos** with a choice of quality, and **downloads other files** too.
- **Tries the secure (https) version** of a site first.
- **Rejects cookie banners** for you where they offer it, and never presses Accept.
- **Cookie guard** (off until you turn it on): deletes a site's cookies when you close it, except on sites you trust.
- **Tells you why a page did not load**, and can check from test servers in other countries whether the site opens there.
- **Tabs**, private browsing, history, bookmarks and a choice of search engine.
- **Light, dark or system theme.**
- **Auto-update:** checks GitHub on startup for a newer release and downloads it so you can install it without visiting GitHub.
- A clean start page with no news and no sponsored tiles.

### Android
- **Search with Google Lens:** pick a photo or screenshot, share one from another app, or use the camera.
- **Secure DNS** for private address lookups.
- Tab previews, and a desktop-site switch.

### Windows
- A single-window design with no extra title bar.
- **Menu, site information and Shields panels** with per-site script blocking, fingerprint protection and "forget me when I close this site".
- **Search by image:** drag, paste or browse for a picture and search it with Google Lens.
- **New window** and **new private window**.
- **Extensions:** load an unpacked extension from a folder.
- **Passwords and autofill**, off until you turn it on.
- **Try other countries through Tor:** when a page fails to load, you can test whether it opens through Tor from other countries and open it in a separate Tor window. Tor connections are slower, only use https, and many sites refuse Tor. The Tor window is not as protective as Tor Browser.
- Tabs you are not using go to sleep to save memory.

## Your privacy

- Your history, bookmarks, downloads and settings are stored on your own device.
- There is no Speed Force account.
- Websites, your search engine and the web engine of your system work as they do in any browser.
- If you use Google Lens search, the picture you choose is sent to Google.
- The country check runs only when you press its button. It sends the site's name to [Globalping](https://globalping.io), which also sees your internet address. It does not unblock sites.
- Nothing goes through Tor until you press the Tor button on a failed page. When you open a Tor window, it uses a temporary profile that is cleared when you close it.
- On startup (except in Tor windows), the app checks GitHub for a newer release version.

## Fair use

Only download content that you own or are allowed to save, and follow the terms of the site you use.

## Issues

If there are any issues or bugs found, kindly report them in the [Issues](../../issues) tab.

## For developers

| Folder | App |
|---|---|
| `SpeedForce-Android/` | Kotlin, Jetpack Compose, system WebView |
| `SpeedForce-Windows/` | C++, Microsoft WebView2 |

Signing keys, build output and the download engine are not in this repository. Filter lists and
third-party libraries keep their own licences. See [THIRD-PARTY.md](THIRD-PARTY.md).

## License

Speed Force is free software under the [GNU General Public License v3.0](LICENSE). Third-party parts keep
their own licences; see [THIRD-PARTY.md](THIRD-PARTY.md).
