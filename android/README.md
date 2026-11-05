# Optoflow for Android

A native Android shell around the Optoflow web app. The app loads the live
production site in a WebView, so every web deploy shows up in the app
immediately, and adds native features through a small JavaScript bridge.

## Configure

Edit `gradle.properties`:

| Property | Meaning |
| --- | --- |
| `optoflow.startUrl` | Page opened on launch. |
| `optoflow.allowedHosts` | Comma-separated hosts (subdomains included) that stay in the app. Anything else opens in a browser tab. |

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

## What the shell already does

- Splash screen, edge-to-edge layout, light/dark theme, back button through web history.
- Links outside the allowed hosts open in a Custom Tab; `tel:`, `mailto:`, `intent:` links open the right app.
- `<input type="file">` with a "take photo" option; in-page camera (`getUserMedia`) on allowed origins.
- Downloads: normal links via DownloadManager (with the session cookies); `blob:`/`data:` downloads saved to Downloads.
- `window.print()` opens Android printing (WebView normally ignores it).
- Offline screen with automatic reconnect; recovers from WebView renderer crashes.
- App data excluded from cloud backups and device transfer.
- `https://<start host>` links open in the app (verified once `/.well-known/assetlinks.json` is published).

## JavaScript bridge

On allowed origins the page gets `window.OptoflowNative` (it fires an
`optoflownativeready` event on `window` once installed). In a normal browser it
doesn't exist, so feature-detect. The user agent also contains `OptoflowAndroid/<version>`.

```js
if (window.OptoflowNative) {
  const info = await OptoflowNative.getAppInfo(); // { platform, appVersion, appBuild, osVersion, sdkInt, device, features }
  await OptoflowNative.share({ title, text, url });
  await OptoflowNative.openExternal('https://example.com');
  await OptoflowNative.print('Invoice 1042');
  await OptoflowNative.saveFile({ name: 'invoice.pdf', mimeType: 'application/pdf', base64 });
}
```

Every method returns a Promise and rejects with an `Error` on failure.
