# Grabbit

A small Android app that wraps [yt-dlp](https://github.com/yt-dlp/yt-dlp): paste a link, pick a format
(MP4, M4A, MP3, WEBM, OPUS) and the file is downloaded locally to `Downloads/Grabbit`.
It uses [youtubedl-android](https://github.com/yausername/youtubedl-android), which bundles yt-dlp and ffmpeg.

## Features

- Native Android app (Kotlin + Jetpack Compose), dark UI
- Choose the output format at download time
- Works as a share target: share a link to Grabbit from any app
- Built-in yt-dlp updater (Settings → Update yt-dlp), so it keeps working when sites change
- Night / Day / AMOLED (pure black) theme, switchable in Settings
- Choose where files are saved (Settings → Save location); default is `Downloads/Grabbit`
- Video quality picker (Best, 1080p, 720p, 480p, 360p) for MP4 and WEBM
- Downloads run in a foreground service, so they continue in the background, with a progress notification and a Cancel button
- Separate APKs per architecture: `arm64-v8a` (almost all modern phones) and `armeabi-v7a` (old 32-bit devices)
- Requires Android 10 (API 29) or newer

## Disclaimer

Grabbit is a general-purpose front end for yt-dlp. Use it only for content you own, content that is
licensed for free redistribution, or content you have explicit permission to download. Downloading
copyrighted material without permission may violate copyright law and the terms of service of the
sites you use. You are solely responsible for how you use this app. Grabbit is not affiliated with
YouTube or any other service.

## Building

GitHub Actions builds a signed release APK on every push to `main` and publishes it as a GitHub release.
To build locally you need JDK 17, the Android SDK and Gradle 8.9:

```
gradle assembleRelease
```

## License

MIT, see [LICENSE](LICENSE).
