# Optoflow for Android

A native Android shell around the Optoflow web app. The app loads the live
production site in a WebView, so every web deploy shows up in the app
immediately, and adds native features through a small JavaScript bridge.

## Configure

Edit `gradle.properties`:

| Property | Meaning |
| --- | --- |
| `optoflow.startUrl` | Page opened on first launch. After that the app reopens the last clinic subdomain used. |
| `optoflow.allowedHosts` | Hosts that stay in the app; subdomains (`clinic-name.optoflow.ir`) included. Anything else, and patient portal links under `/p/`, open in the browser. |
| `optoflow.sharePath` | Web route opened when files are shared into the app (see "Share → Optoflow"). |

## Build

Requires JDK 17+ and the Android SDK (`sdk.dir` in `local.properties`, or `ANDROID_HOME`).

```sh
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # needs keystore.properties (see below)
```

Release signing reads `keystore.properties` (not committed):

```properties
storeFile=optoflow-release.jks
storePassword=...
keyAlias=optoflow
keyPassword=...
```

Keep the release keystore safe: updates to an installed app must be signed with the same key.

## What the shell does

- Persian, RTL, light-only native screens; status and navigation bars take the page's `theme-color` (or background).
- Android 8+ (minSdk 26). Warns once if the phone's Android System WebView is older than Chrome 111.
- Back goes through web history; on the first screen, press back twice to exit.
- `tel:` opens the dialler; `sms:` (with `?body=`) opens the messaging app with the text filled in; other sites open in a browser tab.
- `<input type="file">` with a "take photo" option; in-page camera and **microphone** (Hamyar voice) on Optoflow origins.
- Downloads (including `blob:`/`data:`) saved to Downloads; `window.print()` opens Android printing.
- Offline screen («اتصال برقرار نیست») with automatic reconnect; recovers from WebView renderer crashes.
- `/reset-db-with-demo` is never prefetched or deep-linked; opening it asks for confirmation first.
- **App lock**: fingerprint/face/PIN on cold start and after 5 minutes in the background (only if the phone has a screen lock). Recent-apps thumbnail hidden on Android 13+.
- **Share → Optoflow**: images, audio, PDFs and text shared from Telegram, WhatsApp, Eitaa, Bale etc. are held by the app and `optoflow.sharePath` is opened on the user's clinic subdomain.
- App data excluded from cloud backups and device transfer.
- Links to `optoflow.ir` and `*.optoflow.ir` open in the app (automatic once `/.well-known/assetlinks.json` is published).

## JavaScript bridge

On Optoflow origins the page gets `window.OptoflowNative` (it fires an
`optoflownativeready` event on `window` once installed). In a normal browser it
doesn't exist, so feature-detect. The user agent also contains `OptoflowAndroid/<version>`.

```js
if (window.OptoflowNative) {
  const info = await OptoflowNative.getAppInfo(); // { platform, appVersion, appBuild, osVersion, sdkInt, device, features }
  await OptoflowNative.share({ title, text, url });
  await OptoflowNative.openExternal('https://example.com');
  await OptoflowNative.print('فاکتور ۱۰۴۲');
  await OptoflowNative.saveFile({ name: 'invoice.pdf', mimeType: 'application/pdf', base64 });

  // Share → Optoflow
  if (await OptoflowNative.hasSharedItems()) {
    const { text, subject, files } = await OptoflowNative.takeSharedItems(); // files: [{ name, mimeType, size, base64 }]
  }

  // App lock settings
  await OptoflowNative.getAppLock();                       // { available, enabled, timeoutMinutes }
  await OptoflowNative.setAppLock({ enabled: true, timeoutMinutes: 5 });
  await OptoflowNative.lockNow();
}
```

Every method returns a Promise and rejects with an `Error` on failure.
