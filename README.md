# Meta Glasses

Source preview for Despia V3. This snapshot requires the runtime update currently awaiting release and an approved extension-enabled native build. Uploading these files does not change an installed app.

Camera access and live streaming for supported Meta glasses through a native extension. Glasses hardware validation is pending.

This ready-made extension is part of Despia V3 Extensions. First-party V4 support comes through the new package registry, with a different package shape.

Source preview. The runtime update for this extension is awaiting release.

[V3 extension documentation](https://setup.despia.com/experiments/v3-extensions) and [extension skill](https://github.com/despia-legacy/extension-skill).

## Web frameworks and app builders

Use the Despia SDK with React, Vue, Svelte, Next.js, TanStack or HTML5. Projects built with Lovable, Base44, Replit and other app builders need custom JavaScript and static file hosting. Claude Code, Codex and Cursor can edit the same extension files.

## What is included

- `manifest.json`: the extension's routes, parameters, events and native files.
- `ios/` and `android/`: the Swift and Kotlin or Java sources and resources the manifest names.
- `extensions.example.json`: the entry for `public/despia/extensions.json`.
- `metaglasses.policy.json`: the approval policy. It is a file you send to Despia with your approval request, together with the extension files. It is not served from your site and does not go into `public/despia/`.

## Requirements

- A Despia V3 app with V3 Extensions enabled, and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- Meta glasses and the Meta AI app, with Developer Mode turned on for testing.
- A Meta Wearables Developer Center app for production use.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/metaglasses/` in your web app. The folder name is the extension id, so pages call `extension://metaglasses/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"metaglasses"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Request approval and a new native build in Despia, and send `metaglasses.policy.json` with the request. Despia reviews and approves these exact files before a build includes them, and any change to the manifest or to a native file needs a new approval and a new native build. It cannot be applied over the air.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Copy `example/src/lib/despia-extension.js` to your app's `src/lib/` and adjust the import path if needed. It imports `despia` from `despia-native`, calls the SDK and returns the native answer's `data`. It checks native error envelopes and throws an error with their `code`. The SDK itself resolves native `ok: false` envelopes; it does not reject them. A single watched value becomes `undefined` after 30 seconds, which this helper reports as `sdk_timeout`.

Each helper call forwards a unique native callback into a separate watched value, so overlapping calls do not share response globals. `onExtensionEvent` registers the declared native event callbacks and returns a cleanup function. Events are callbacks, not values you can poll from the SDK proxy.

Importing the SDK is safe during server rendering. Run calls and event subscriptions only from browser click handlers or mounted client hooks. React and Vue can use the browser APIs used by this helper; in Next.js and other server-rendered apps, keep these calls in client code. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features.

## Calling it

```javascript
import { extensionCall } from './lib/despia-extension.js';

const answer = await extensionCall('metaglasses', 'status');
console.log(answer);
```

Calls use `extension://metaglasses/<route>?<params>`. Each route answers once, in the page that made the call: the runtime sets the route's answer variable in the page, then calls the route's callback. Only declared parameters are accepted. Keep large data on the native side and pass identifiers through the bridge. A call URL is at most 8192 characters.

Every answer has the same shape: `ok`, `provider`, `platform`, `runtime` (`3`), `bridge` (`1`), `extension`, `route`, `data` (your handler's data on success, `null` on error), and on error `error` (a message) and `code`.

Standard error codes: `bad_request` (an undeclared or missing parameter, or a value of the wrong type), `not_registered` (this build does not contain the extension or route), `forbidden_frame` (the call did not come from the top-level page), `forbidden_origin` (an origin that is not allowed), `rate_limited` (more than 20 calls per second to one extension), `timeout` (no answer within the route's `timeout_ms`), `unavailable`, `platform_limit` (this platform or OS version has no such capability), `handler_error`, plus the codes this extension lists below.

---

## About

This extension connects the phone to Meta glasses through Meta's Wearables Device Access Toolkit (DAT). Your page starts and stops the glasses' camera stream and shows it live. It can take a photo and record a short clip. A simulated pair of glasses (the Mock Device Kit) stands in for real glasses in debug builds.

Live preview and saved media use local HTTP URLs. Your page can show the live stream in an `<img>`. The optional `frame` and `media_read` routes return base64 data through the bridge.

---

## Values to set before you build

Despia takes app identity from the existing build configuration. If your app has other extensions, list `"metaglasses"` beside them.

### Your own values

- **URL scheme.** `nxglasses` appears three times in `manifest.json`: in `MWDAT.AppLinkURLScheme` (as `nxglasses://`), in iOS `claims.url_schemes` and in Android `claims.intent_schemes`. Replace all three with one scheme of your own. The Meta AI app returns to this scheme after registration and after the camera permission.
- **Meta app credentials.**
  - For Developer Mode, keep `MetaAppID` as `"0"`, `ClientToken` and `TeamID` empty, and turn on Developer Mode in Meta AI (Settings, then your glasses).
  - For production, set `MetaAppID` and `ClientToken` from your app in Meta's Wearables Developer Center, and set `TeamID` to your Apple Developer Team ID. The same two Meta values go into the Android values `com.meta.wearable.mwdat.APPLICATION_ID` and `CLIENT_TOKEN` in `android.meta_data` (`"0"` in the extension).
  - These values ship inside your app, and `manifest.json` is served from your own origin, so anyone can read them. They are not secrets. Never put a Meta server-side secret in this file or anywhere in your web app.
  - You sign up with Meta and accept its terms on Meta's site yourself.
- **Purpose strings.** Set `NSBluetoothAlwaysUsageDescription` and `NSLocalNetworkUsageDescription` with your other purpose strings in Despia.

---

## Using it from your web app

```javascript
import { extensionCall, onExtensionEvent } from './lib/despia-extension.js';
const glasses = (route, params) => extensionCall('metaglasses', route, params);
const image = document.querySelector('#glasses');
const clip = document.querySelector('#clip');
const stopState = onExtensionEvent('onGlassesState', state => {
  if (state.state !== 'streaming') image.removeAttribute('src');
});
const stopMedia = onExtensionEvent('onGlassesMedia', media => {
  if (media.kind === 'mp4' && !media.failed) clip.src = media.url;
});
const stopRegistration = onExtensionEvent('onGlassesRegistration', registration => console.log(registration));

export async function startCamera() {
  let permission = await glasses('permission', { prompt: true });
  if (permission.camera === 'unknown') {
    await glasses('register');
    return; // After registration completes in Meta AI, run startCamera() again.
  }
  if (permission.camera !== 'granted') return;
  const stream = await glasses('start', { quality: 'medium', fps: 24 });
  image.src = stream.live;
}
export async function takePhoto() {
  const photo = await glasses('photo');
  document.querySelector('#photo').src = photo.url;
}
export async function stopCamera() {
  await glasses('stop'); image.removeAttribute('src');
}
// await glasses('record_start', { seconds: 5 });
// On unmount: stopState(); stopMedia(); stopRegistration();
```

Each route answers once in its own callback, listed in the table below.

### Routes

| Route                       | Parameters                                             | Callback                                    | Answer `data` | Answer variable |
| --------------------------- | ------------------------------------------------------ | ------------------------------------------- | ------------- | --- |
| `status`                    | none                                                   | `onGlassesStatus`                           | The state object, with `live`, `frame` and `mediaBase` URLs while streaming | `glassesStatus` |
| `register`, `unregister`    | none                                                   | `onGlassesRegister`, `onGlassesUnregister`  | `registration`. The outcome follows as the `registration` event | `glassesRegister`, `glassesUnregister` |
| `permission`                | `prompt` (bool)                                        | `onGlassesPermission`                       | `bluetooth` and `camera` (`granted`, `denied`, or `unknown` while the app is not registered) | `glassesPermission` |
| `start`                     | `quality` (`low`, `medium`, `high`), `fps` (2 to 30)   | `onGlassesStart`                            | The state object with `live`, `frame` and `mediaBase` | `glassesStart` |
| `stop`                      | none                                                   | `onGlassesStop`                             | The state object | `glassesStop` |
| `photo`                     | none                                                   | `onGlassesPhoto`                            | The saved JPEG: `id`, `kind`, `url`, `width`, `height`. Also sent as the `media` event | `glassesPhoto` |
| `record_start`              | `seconds` (1 to 60, required)                          | `onGlassesRecordStart`                      | `id`, `kind`, `seconds` and the `url` of the clip being recorded. It ends by itself or with `record_stop` | `glassesRecordStart` |
| `record_stop`               | none                                                   | `onGlassesRecordStop`                       | The finished clip, also sent as the `media` event (`failed` when it could not be written) | `glassesRecordStop` |
| `frame`                     | none                                                   | `onGlassesFrame`                            | `seq`, `width`, `height` and `jpeg` (base64) of the newest frame | `glassesFrame` |
| `media`                     | none                                                   | `onGlassesMediaList`                        | `items`: the saved photos and clips | `glassesMediaList` |
| `media_read`                | `id` (required), `kind` (`jpg`, `mp4`, required), `chunk` | `onGlassesMediaRead`                     | One 49152-byte chunk as base64, with `eof` on the last | `glassesMediaRead` |
| `media_delete`              | `id`, `kind` (both required)                           | `onGlassesMediaDelete`                      | `id`, `kind`, `deleted: true` | `glassesMediaDelete` |
| `mock`                      | `enabled` (bool, required)                             | `onGlassesMock`                             | Debug builds only. Release builds answer `mock_unavailable` before SDK initialization or teardown, including `enabled: false` | `glassesMock` |

### Events

| Event          | Callback                 | Scope      | When                                                              |
| -------------- | ------------------------ | ---------- | ----------------------------------------------------------------- |
| `state`        | `onGlassesState`         | `document` | The stream state changed                                          |
| `media`        | `onGlassesMedia`         | `document` | A photo or clip finished, or failed                               |
| `registration` | `onGlassesRegistration`  | `document` | The Meta AI app answered the registration                         |

On Android a failed registration arrives on the `registration` event with `error` and `message`. On iOS `register` answers with the error itself.

### Error codes

`bad_request`, `busy`, `capture_failed`, `device_limit` (too hot, battery low or at peak power), `glasses_folded`, `init_failed`, `meta_ai_missing`, `mock_failed`, `mock_unavailable`, `no_device`, `not_found`, `not_recording`, `not_streaming`, `permission_denied`, `permission_failed`, `record_failed`, `registration_failed`, `registration_required`, `registration_unauthorized` (Meta refused this app without opening Meta AI), `server_failed`, `session_failed`, `stream_failed` and `timeout`.

- Only on iOS: `platform_limit` (iOS before 17.2).
- Only on Android: `handler_error`, `permission_required` and `unavailable` (the activity is gone).

See also the standard codes.

---

## How the picture reaches the page

The extension runs a small HTTP server on `127.0.0.1`, on a port the system picks, under a fresh random token path. `start` answers with `data.live`, and your page sets it as an `<img src>`.

- `/<token>/live.mjpg` is a stream of JPEG frames. Each connection gets the newest frame only, so a slow page skips frames and never falls behind.
- `/<token>/frame.jpg` is the newest frame once.
- `/<token>/media/<id>.jpg` and `/<token>/media/<id>.mp4` are the saved photos and clips. They support byte ranges, so a `<video src>` can seek in a clip.

Each frame costs one JPEG encode on the phone. A clip is H.264 MP4 written from the same frames.

If your WebView blocks local HTTP media, poll `frame` and draw `"data:image/jpeg;base64," + r.data.jpeg` when `r.data.seq` changed. Read saved media with `media_read?id=<id>&kind=mp4&chunk=<n>`: each chunk is 49152 bytes until `data.eof` is true.

## Registration and permissions

The app registers once with the Meta AI app. That opens Meta AI, which returns to your app's own URL scheme. The glasses camera permission is asked in Meta AI too. Call `permission` with `prompt: true` first, then `register` when `camera` is `unknown`, then `permission` again.

### iOS

- **Info.plist.** The manifest sets the `MWDAT` keys (`AppLinkURLScheme`, `MetaAppID`, `ClientToken`, `TeamID`), the background modes `bluetooth-central`, `bluetooth-peripheral` and `external-accessory`, the accessory protocol `com.meta.ar.wearable`, and `NSBonjourServices` with `_bonjour._tcp`.
- **Purpose strings.** `NSBluetoothAlwaysUsageDescription` and `NSLocalNetworkUsageDescription`, under `requires.usage_descriptions`.
- **Package.** Meta's SwiftPM package, pinned in `ios/Package.resolved`. The extension answers `platform_limit` below iOS 17.2, and the app still launches on earlier iOS versions.

### Android

- **Permissions.** `BLUETOOTH_CONNECT` (under `requires.permissions`) and `INTERNET`. The extension asks for `BLUETOOTH_CONNECT` before it starts the SDK.
- **Application meta-data.** `com.meta.wearable.mwdat.APPLICATION_ID` and `CLIENT_TOKEN`, from `android.meta_data` in the manifest.
- **Minimum Android.** `min_sdk` is 29 (Android 10). Below that, every route answers `platform_limit`.
- **Libraries.** `mwdat-core`, `mwdat-camera` and `mwdat-mockdevice` 1.1.0 from Maven Central, and `androidx.activity:activity:1.13.0`.
- **Exported component.** The Meta SDK adds a registration service that the Meta AI app binds to. It is listed in the approval policy as Meta ships it.

---

## Platform limits

- **Meta DAT 1.1.0.** A session created before registration fails and is reported as `registration_required`. Unregistering stops every session. A refused stream start reports a hot device, a low battery or the peak power limit as `device_limit`. A photo times out after 10 seconds.
- **Photo.** The `photo` implementation captures from the running stream.
- **Clips.** DAT offers no video recording, so the clip is the app's own encoding of the stream's frames.
- **iOS.** Meta's toolkit needs iOS 17.2.
- **Android.** Meta's toolkit needs Android 10 (API 29).

## License

[Despia V3 Extension Source-Available Commercial License](LICENSE).
You may inspect the source. Using or modifying this extension requires a valid
Despia commercial license and a valid Despia project for the particular app.
The standard price is **USD 249 per application per operating system**.
iOS and Android require separate licenses. See [Despia](https://despia.com).
Third-party SDKs keep their own licenses. Experimental V3 extensions do not
include a commitment to maintenance or a V4 port.

## Source and licensing

Browse, clone or fork this source to inspect it. Public GitHub forks remain public. For private development, clone into a private workspace or private repository. Modification, building, execution and deployment require a valid Despia commercial license and a valid Despia project for the application. The standard price is USD 249 per application per operating system. Licensed users may customize the extension or use it as a reference for new functionality in that licensed app. Keep the license and applicable notices. See [LICENSE](LICENSE) for the terms.
