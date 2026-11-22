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
- Optional embedded cover art (MP4, M4A, MP3, OPUS)
- Optional "Save video and audio separately" for MP4 and WEBM downloads
- Optional "Download only a section": give a start and end time (`m:ss` or `h:mm:ss`) and only that part is downloaded
- Remembers your last chosen format and cover art setting
- Real song title detection for audio downloads (M4A, MP3, OPUS), free and without an account, see below (can be turned off in Settings)
- Downloads run in a foreground service, so they continue in the background, with a progress notification and a Cancel button
- Separate APKs per architecture: `arm64-v8a` (almost all modern phones) and `armeabi-v7a` (old 32-bit devices)
- Requires Android 10 (API 29) or newer

## Song recognition

When you download audio, Grabbit can identify the song the way Shazam does, but at several points of the track:

1. It cuts 5 short excerpts (12 seconds each) from different positions of the downloaded file, using the bundled ffmpeg.
2. Each excerpt is turned into a Shazam fingerprint on the phone and sent to Shazam's public discovery endpoint. No account, API key or payment is needed, and only the fingerprint is sent, never the audio.
3. If more than 50% of the searches (at least 3 out of 5) return the same artist and title, the file's tags are rewritten with the recognised title, artist, album and year (no re-encoding).
4. Otherwise the file keeps the metadata that yt-dlp downloaded.

The file name and the cover art are not changed. Recognition only runs on audio formats, and you can switch it on or off in
Settings → Song recognition.

This endpoint is not an official Shazam API: it may change, rate-limit or stop working at any time, and its use may not be
covered by Shazam's terms of service. If that happens the downloads still work, they just keep the original metadata.
The fingerprint code comes from [songprint](https://github.com/hitenpatel/songprint) (MIT, see `licenses/`), which is based on
the format documented by [SongRec](https://github.com/marin-m/SongRec).

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
