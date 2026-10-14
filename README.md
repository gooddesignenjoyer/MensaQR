# Mensa QR

A tiny unofficial Android app that shows your daily Akafö student-discount QR
code instantly, without opening the website and logging in every time.

> **Unofficial.** This app is not affiliated with, endorsed by, or supported by
> Akafö or StudyLife. It talks to the same API the StudyLife website uses, which
> can change at any time. If it stops working, use the website.
> Use at own risk.

## Install

1. Download the latest `.apk` from the [Releases](https://github.com/gooddesignenjoyer/MensaQR/releases) page.
2. Open it on your phone and allow installing from that source when asked.

For automatic updates, add `https://github.com/gooddesignenjoyer/MensaQR` to
[Obtainium](https://github.com/ImranR98/Obtainium). It checks the Releases
page and installs new versions for you.

Requires Android 6.0 or newer.

## How it works

- **First start:** sign in with your StudyLife e-mail and password, then enter
  the code that's e-mailed to you.
- **Every day after that:** open the app and your QR code is there. The day's
  code is cached, so it also works without signal in the canteen.
- **When your session expires:** the app signs you in again by itself. You only
  type the new e-mail code.
- Screen brightness goes to maximum while the code is shown, so it scans easily.
- **Log out** under the code removes everything from your phone.

## Privacy

- Your e-mail and password are only ever sent to `akafoe.studylife.org`. There
  is no other server, no analytics, and no tracking. The whole app is one
  source file, [`MainActivity.kt`](app/src/main/java/io/github/gooddesignenjoyer/mensaqr/MainActivity.kt),
  so you can check this yourself.
- Your e-mail, password and session tokens are stored **encrypted** on your
  phone, with a key held in the Android Keystore. That's what lets re-login
  need only the e-mail code.
- Today's QR code and your name are stored unencrypted in the app's private
  storage, so the app opens instantly.
- App backups are disabled, so none of this ends up in cloud backups.

## Building it yourself

Needs JDK 17+ and the Android SDK with platform API 36.

```sh
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Without a `keystore.properties` file, release builds are signed
with your local debug key. That's fine for your own phone.

## License

MIT, see [LICENSE](LICENSE).
