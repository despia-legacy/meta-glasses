# Meta Glasses

Source preview for Despia V3. The runtime update and automatic discovery rollout are awaiting release. Rebuild and install the native app to include these files; uploading them does not change an installed app.

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
- `metaglasses.policy.json`: the build verification policy. It records declarations used by signed build verification. It is not served from your site and does not go into `public/despia/`.

## Requirements

- A Despia V3 app and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- Meta glasses and the Meta AI app, with Developer Mode turned on for testing.
- A Meta Wearables Developer Center app for production use.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/metaglasses/` in your web app. The folder name is the extension id, so pages call `extension://metaglasses/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"metaglasses"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Rebuild and install the native app with these extension files. Any change to the manifest or native source requires another native build. It cannot be applied over the air.

Signed build verification binds source hashes, dependency pins, app identity and final artifacts. It is separate from human code review and does not require a per-app enrollment switch.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Call the published SDK directly and watch the exact answer variable declared in `manifest.json`. The SDK resolves to an object keyed by that variable; the native envelope is inside it. Check both a missing reply and `ok: false` before using `data`. Native errors resolve through the SDK; they do not reject its promise. A single watched variable becomes `undefined` after 30 seconds without a reply.

Await calls that use the same answer variable sequentially. The SDK's command queue does not correlate concurrent responses. Encode every query value with `encodeURIComponent`, including JSON text. Spaces become `%20`; a literal plus becomes `%2B`. Do not use unmodified `URLSearchParams` form encoding, which uses plus for spaces.

Importing the SDK is safe during server rendering. Run native calls only in browser click handlers or mounted client hooks, including client components in Next.js. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features. Optional event handlers use the declared callback on `globalThis`, not an SDK subscription API. Save and restore any callback your app already owns when a component mounts and unmounts.

## Calling it

```javascript
import despia from 'despia-native';

const answerReply = await despia('extension://metaglasses/status', ['glassesStatus']);
const answer = answerReply.glassesStatus;
if (answer === undefined) throw new Error('No native reply within 30 seconds');
if (!answer.ok) throw new Error(answer.code + ': ' + answer.error);

console.log(answer.data);
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
import despia from 'despia-native';

const permissionReply = await despia(`extension://metaglasses/permission?prompt=${encodeURIComponent(true)}`, ['glassesPermission']);
const permission = permissionReply.glassesPermission;
if (permission === undefined) throw new Error('No native reply within 30 seconds');
if (!permission.ok) throw new Error(permission.code + ': ' + permission.error);

if (permission.data.camera !== 'granted') throw new Error('Register the glasses and grant camera access first');

const streamReply = await despia(`extension://metaglasses/start?quality=${encodeURIComponent('medium')}&fps=${encodeURIComponent(24)}`, ['glassesStart']);
const stream = streamReply.glassesStart;
if (stream === undefined) throw new Error('No native reply within 30 seconds');
if (!stream.ok) throw new Error(stream.code + ': ' + stream.error);

document.querySelector('#glasses').src = stream.data.live;

const photoReply = await despia('extension://metaglasses/photo', ['glassesPhoto']);
const photo = photoReply.glassesPhoto;
if (photo === undefined) throw new Error('No native reply within 30 seconds');
if (!photo.ok) throw new Error(photo.code + ': ' + photo.error);

document.querySelector('#photo').src = photo.data.url;
const previousGlassesMedia = globalThis.onGlassesMedia;
const handleGlassesMedia = media => {
  try {
    if (typeof previousGlassesMedia === 'function') previousGlassesMedia(media);
  } finally {
    if (media.kind === 'mp4' && !media.failed) document.querySelector('#clip').src = media.url;
  }
};
globalThis.onGlassesMedia = handleGlassesMedia;
const cleanupGlassesMedia = () => {
  if (globalThis.onGlassesMedia !== handleGlassesMedia) return;
  if (previousGlassesMedia === undefined) delete globalThis.onGlassesMedia;
  else globalThis.onGlassesMedia = previousGlassesMedia;
};
// Call cleanupGlassesMedia() when this component unmounts.

// Remove or restore your event callback on unmount. Stop streaming with the stop route.
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
- **Exported component.** The Meta SDK adds a registration service that the Meta AI app binds to. It is listed in the build verification policy as Meta ships it.

---

## Platform limits

- **Meta DAT 1.1.0.** A session created before registration fails and is reported as `registration_required`. Unregistering stops every session. A refused stream start reports a hot device, a low battery or the peak power limit as `device_limit`. A photo times out after 10 seconds.
- **Photo.** The `photo` implementation captures from the running stream.
- **Clips.** DAT offers no video recording, so the clip is the app's own encoding of the stream's frames.
- **iOS.** Meta's toolkit needs iOS 17.2.
- **Android.** Meta's toolkit needs Android 10 (API 29).

## License

[Despia V3 Extension Source-Available Commercial License](LICENSE), version 1.4.
This template is proprietary and source available.

You may inspect, fork, customize, build and run it for noncommercial development,
evaluation and testing without a paid Despia license, including work on an app
you plan to release commercially later.

Production or commercial use requires a valid Despia project and an applicable
Despia license for that application and operating system. A Solo Pack covers
its licensed OS, iOS or Android. A Hybrid Pack covers both. Your applicable
license includes all provided V3 templates you have lawful access to and any
number of your own extensions, with no separate extension purchase or
per-extension fee.

Keep the license and applicable notices. Third-party material keeps its own
terms. Experimental V3 extensions do not include a commitment to maintenance
or a V4 port. See [LICENSE](LICENSE) for the full terms and [Despia](https://despia.com)
for licensing.
