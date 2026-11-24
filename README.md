# Meta Glasses

Source preview for Despia V3. The runtime update and automatic discovery rollout are awaiting release. Rebuild and install the native app to include these files; uploading them does not change an installed app.

Meta glasses through Meta's Wearables Device Access Toolkit 1.1.0: registration, devices and sessions, the camera stream, photos and clips with audio, speech, motion, inputs, voice, the Meta Ray-Ban Display and the audio route. Glasses hardware validation is pending.

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
- Meta glasses and the Meta AI app, with Developer Mode turned on for testing. Debug builds can use the simulated glasses instead.
- iOS 17.2 or later, Android 10 (API 29) or later.
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

This extension connects the phone to Meta glasses through Meta's Wearables Device Access Toolkit (DAT) 1.1.0, the latest release (20 November 2026). It covers what DAT offers on both platforms:

- **Registration** with the Meta AI app, including registration that Meta AI starts itself, and the glasses permissions (camera and microphone).
- **Devices and sessions.** The paired glasses with their link, battery, charging, worn, hinge, thermal and compatibility state. One device session is shared by every capability, and the page can pause and resume it.
- **Camera.** A live stream (with the glasses' audio if you ask), photos from the stream or as full still photos, and clips recorded as MP4 with H.264 video and AAC audio.
- **Speech, motion, inputs and voice.** Transcription on the glasses, motion sensor samples, the touch pad and buttons, and "Hey Meta" launches of your app.
- **Display.** View trees, video and clicks on Meta Ray-Ban Display.
- **Audio route.** Phone audio to and from the glasses as a Bluetooth headset.
- **Updates.** Opening the Meta AI firmware or glasses app update.

A simulated pair of glasses (the Mock Device Kit) stands in for real glasses in debug builds.

Live preview and saved media use local HTTP URLs. Your page can show the live stream in an `<img>`. The optional `frame` and `media_read` routes return base64 data through the bridge.

---

## Values to set before you build

Despia takes app identity from the existing build configuration. If your app has other extensions, list `"metaglasses"` beside them.

### Your own values

- **URL scheme.** `nxglasses` appears three times in `manifest.json`: in `MWDAT.AppLinkURLScheme` (as `nxglasses://`), in iOS `claims.url_schemes` and in Android `claims.intent_schemes`. Replace all three with one scheme of your own. The Meta AI app returns to this scheme after registration and after a permission.
- **Meta app credentials.**
  - For Developer Mode, keep `MetaAppID` as `"0"`, `ClientToken` and `TeamID` empty, and turn on Developer Mode in Meta AI (Settings, then your glasses).
  - For production, set `MetaAppID` and `ClientToken` from your app in Meta's Wearables Developer Center, and set `TeamID` to your Apple Developer Team ID. The same two Meta values go into the Android values `com.meta.wearable.mwdat.APPLICATION_ID` and `CLIENT_TOKEN` in `android.meta_data` (`"0"` in the extension).
  - These values ship inside your app, and `manifest.json` is served from your own origin, so anyone can read them. They are not secrets. Never put a Meta server-side secret in this file or anywhere in your web app.
  - You sign up with Meta and accept its terms on Meta's site yourself.
- **Purpose strings.** Set `NSBluetoothAlwaysUsageDescription`, `NSLocalNetworkUsageDescription` and `NSMicrophoneUsageDescription` with your other purpose strings in Despia. iOS asks for the microphone when `audio_route` opens the glasses as a headset.

---

## Using it from your web app

```javascript
import despia from 'despia-native';

const permissionReply = await despia(`extension://metaglasses/permission?prompt=${encodeURIComponent(true)}`, ['glassesPermission']);
const permission = permissionReply.glassesPermission;
if (permission === undefined) throw new Error('No native reply within 30 seconds');
if (!permission.ok) throw new Error(permission.code + ': ' + permission.error);

if (permission.data.camera !== 'granted') throw new Error('Register the glasses and grant camera access first');

const streamReply = await despia(`extension://metaglasses/start?quality=${encodeURIComponent('medium')}&fps=${encodeURIComponent(24)}&audio=${encodeURIComponent(true)}`, ['glassesStart']);
const stream = streamReply.glassesStart;
if (stream === undefined) throw new Error('No native reply within 30 seconds');
if (!stream.ok) throw new Error(stream.code + ': ' + stream.error);

document.querySelector('#glasses').src = stream.data.live;

const photoReply = await despia(`extension://metaglasses/photo?resolution=${encodeURIComponent('full')}`, ['glassesPhoto']);
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

Showing a view on Meta Ray-Ban Display sends a JSON view tree. Encode the JSON text as one value:

```javascript
import despia from 'despia-native';

const displayReply = await despia('extension://metaglasses/display_start', ['glassesDisplayStart']);
const display = displayReply.glassesDisplayStart;
if (display === undefined) throw new Error('No native reply within 30 seconds');
if (!display.ok) throw new Error(display.code + ': ' + display.error);

const view = {type: 'box', direction: 'column', spacing: 8, padding: 16, children: [
  {type: 'text', text: 'Next stop', style: 'heading'},
  {type: 'buttons', children: [{type: 'button', label: 'Done', id: 'done', primary: true}]},
]};
const sendReply = await despia('extension://metaglasses/display_send?content=' + encodeURIComponent(JSON.stringify(view)), ['glassesDisplaySend']);
const sent = sendReply.glassesDisplaySend;
if (sent === undefined) throw new Error('No native reply within 30 seconds');
if (!sent.ok) throw new Error(sent.code + ': ' + sent.error);
// A tap on the button arrives as the display event: {click: 'done'}.
```

Each route answers once in its own callback, listed in the tables below. Routes that take `device` use the glasses with that `id` from `devices`. Without it, DAT picks the active glasses.

### Routes: camera, registration and media

| Route | Parameters | Callback | Answer `data` | Answer variable |
| --- | --- | --- | --- | --- |
| `status` | none | `onGlassesStatus` | The state object: `sdkVersion`, `bluetooth`, `registration`, `devices`, `state`, `quality`, `fps`, `width`, `height`, `frames`, `clip`, `photoBusy`, `mock`, `session`, `device`, `audio`, `audioRate`, `audioChannels`, `speech`, `motion`, `inputs`, `display`, `voice`, with `live`, `frame` and `mediaBase` URLs while streaming | `glassesStatus` |
| `register`, `unregister` | none | `onGlassesRegister`, `onGlassesUnregister` | `registration`. The outcome follows as the `registration` event. Unregistering stops every session | `glassesRegister`, `glassesUnregister` |
| `registration_continue` | none | `onGlassesRegistrationContinue` | `flowId`, `answer: "continue"`. Accepts the registration that Meta AI started (the `registration_request` event) | `glassesRegistrationContinue` |
| `registration_cancel` | `return_to_meta_ai` (bool, iOS only, default `true`) | `onGlassesRegistrationCancel` | `flowId`, `answer: "cancel"`. Declines it; an existing registration is kept | `glassesRegistrationCancel` |
| `permission` | `prompt` (bool), `kind` (`camera` or `microphone`, default `camera`) | `onGlassesPermission` | `bluetooth`, `camera` and `microphone` (`granted`, `denied`, or `unknown` while the app is not registered) | `glassesPermission` |
| `start` | `quality` (`low`, `medium`, `high`), `fps` (2 to 30), `audio` (bool), `audio_rate` (`16000`, `44100`, `48000`), `audio_channels` (1 or 2), `device` | `onGlassesStart` | The state object with `live`, `frame` and `mediaBase` | `glassesStart` |
| `stop` | none | `onGlassesStop` | The state object. Stops the camera only; other capabilities keep the session | `glassesStop` |
| `photo` | `resolution` (`small`, `medium`, `large`, `full`), `quality` (`low`, `medium`, `high`) | `onGlassesPhoto` | The saved JPEG: `id`, `kind`, `url`, `width`, `height`. Also sent as the `media` event. Without parameters it captures from the running stream. With either parameter it takes a standalone still photo and reports `photo_progress`; a still needs the stream stopped, so while streaming it answers `busy` | `glassesPhoto` |
| `record_start` | `seconds` (1 to 60, required) | `onGlassesRecordStart` | `id`, `kind`, `seconds`, `audio` and the `url` of the clip being recorded. It ends by itself or with `record_stop` | `glassesRecordStart` |
| `record_stop` | none | `onGlassesRecordStop` | The finished clip, also sent as the `media` event (`failed` when it could not be written) | `glassesRecordStop` |
| `frame` | none | `onGlassesFrame` | `seq`, `width`, `height` and `jpeg` (base64) of the newest frame | `glassesFrame` |
| `media` | none | `onGlassesMediaList` | `items`: the saved photos and clips | `glassesMediaList` |
| `media_read` | `id` (required), `kind` (`jpg`, `mp4`, required), `chunk` | `onGlassesMediaRead` | One 49152-byte chunk as base64, with `eof` on the last | `glassesMediaRead` |
| `media_delete` | `id`, `kind` (both required) | `onGlassesMediaDelete` | `id`, `kind`, `deleted: true` | `glassesMediaDelete` |
| `open_update` | `target` (`firmware` or `glasses_app`, default `firmware`) | `onGlassesOpenUpdate` | `opened`: Meta AI shows the update | `glassesOpenUpdate` |

### Routes: devices, session and capabilities

| Route | Parameters | Callback | Answer `data` | Answer variable |
| --- | --- | --- | --- | --- |
| `devices` | none | `onGlassesDevices` | `devices`: each with `id`, `name`, `type`, `link`, `compatibility`, `battery`, `charging`, `worn`, `hinge`, `thermal`, `display` (bool) and `firmware` (`null` on iOS) | `glassesDevices` |
| `session_start` | `device` | `onGlassesSessionStart` | `session`, `device`. Opens the shared session without starting a capability | `glassesSessionStart` |
| `session_stop` | none | `onGlassesSessionStop` | The state object. Ends the session and every capability on it, with the camera | `glassesSessionStop` |
| `pause`, `resume` | none | `onGlassesPause`, `onGlassesResume` | `session`, `device` | `glassesPause`, `glassesResume` |
| `audio_route` | `mode` (`call`, `media` or `default`, default `call`) | `onGlassesAudioRoute` | `mode`, `inputs`, `outputs` and `glasses` (bool: the route reaches the glasses) | `glassesAudioRoute` |
| `speech_start`, `speech_stop` | `device` (start) | `onGlassesSpeechStart`, `onGlassesSpeechStop` | `state`, `locale` | `glassesSpeechStart`, `glassesSpeechStop` |
| `motion_start`, `motion_stop` | `device`, `rate` (`5`, `10`, `15`, `24`, `30` or `60` Hz, default `10`) (start) | `onGlassesMotionStart`, `onGlassesMotionStop` | `state`, `rate` | `glassesMotionStart`, `glassesMotionStop` |
| `inputs_start`, `inputs_stop` | `device`, `sources` (comma list of `captouch`, `neural_band`, `capture_button`, `action_button`, `neural_band_drag`; empty for all), `consume_back` (bool, default `true`) (start) | `onGlassesInputsStart`, `onGlassesInputsStop` | `state`, `consumeBack` | `glassesInputsStart`, `glassesInputsStop` |
| `voice_start`, `voice_stop` | `device` (start) | `onGlassesVoiceStart`, `onGlassesVoiceStop` | `state`, `device` | `glassesVoiceStart`, `glassesVoiceStop` |
| `voice_respond` | `id` (required), `success` (bool, default `true`), `output` | `onGlassesVoiceRespond` | `id`, `sent` | `glassesVoiceRespond` |
| `display_start`, `display_stop` | `device` (start) | `onGlassesDisplayStart`, `onGlassesDisplayStop` | `state`. `display_start` answers `state: started` once the display is started, as Meta's Display guide requires before any content is sent; it answers `display_failed` if the display stops first and `timeout` after 20 seconds. Glasses without a display answer `unsupported_device` | `glassesDisplayStart`, `glassesDisplayStop` |
| `display_send` | `content` (JSON view tree, required) | `onGlassesDisplaySend` | `sent: true`. Before the display is started, `display_send`, `display_video` and `display_clear` answer `not_active` | `glassesDisplaySend` |
| `display_video`, `display_video_stop` | `url` (an `https://` video URL, required) (video) | `onGlassesDisplayVideo`, `onGlassesDisplayVideoStop` | `playing` or `stopped: true` | `glassesDisplayVideo`, `glassesDisplayVideoStop` |
| `display_clear` | none | `onGlassesDisplayClear` | `cleared: true` | `glassesDisplayClear` |
| `mock` | `enabled` (bool, required), `model` (`rayban_meta`, `meta_rayban_display`, `oakley_meta_hstn`, `oakley_meta_vanguard`, `rayban_meta_optics`, `meta_glasses`) | `onGlassesMock` | Debug builds only. Release builds answer `mock_unavailable` before SDK initialization or teardown, including `enabled: false` | `glassesMock` |
| `mock_event` | `event` (required), `value` (int), `text` | `onGlassesMockEvent` | Debug builds only, like `mock`. Drives the simulated glasses: `power_on`, `power_off`, `don`, `doff`, `fold`, `unfold`, `battery`, `charging`, `thermal` (0 to 6), `tap`, `tap_and_hold`, `nav_up`, `nav_down`, `nav_left`, `nav_right`, `select`, `back`, `capture` (`text`: `hold` or `double`), `action_button`, `drag`, `transcription`, `speech_locale`, `voice_launch`, `photo_failure`, `app_update_required`, `firmware_update_required`, `camera_permission`, `microphone_permission`, `display_click` | `glassesMockEvent` |

### The display view tree

`display_send` takes one JSON object. Each node has a `type` and string, number or boolean attributes. Both platforms accept and refuse the same documents: at most 64 nodes, 8 levels deep, strings up to 512 characters and numbers up to 10000.

- `box`: `children`, `direction` (`row`, `column`, `row_reverse`, `column_reverse`), `spacing`, `padding`, `padding_top`, `padding_bottom`, `padding_start`, `padding_end`, `align`, `cross`, `wrap`, `background` (`none`, `card`), `id` (a box with an `id` is clickable).
- `text`: `text`, `style` (`heading`, `body`, `meta`), `color` (`primary`, `secondary`).
- `button`: `label`, `style` (`primary`, `secondary`, `outline`), `icon`, `id`, `primary`.
- `buttons`: `children` (buttons only), `align` (`start`, `center`, `end`).
- `icon`: `name` (DAT's icon names in snake case, such as `arrow_u_left`), `style` (`filled`, `outline`).
- `image`: `src` (an `https://` URL), `size` (`icon`, `fill`), `radius` (`none`, `small`, `medium`).
- Every node also takes `grow`, `shrink` and `self` (`start`, `center`, `end`, `stretch`).

### Events

| Event | Callback | Scope | When |
| --- | --- | --- | --- |
| `state` | `onGlassesState` | `document` | The stream state changed |
| `media` | `onGlassesMedia` | `document` | A photo or clip finished, or failed |
| `registration` | `onGlassesRegistration` | `document` | The Meta AI app answered the registration |
| `registration_request` | `onGlassesRegistrationRequest` | `document` | Meta AI started a registration: `flowId`, `protocolVersion`. Answer with `registration_continue` or `registration_cancel` |
| `device` | `onGlassesDevice` | `document` | Glasses were added, changed or removed: `id`, `change` (`added`, `state`, `compatibility`, `removed`) and the device fields |
| `session` | `onGlassesSession` | `document` | The session changed: `state` (`starting`, `started`, `paused`, `stopping`, `stopped`), `device`, and `error` with `message` or `reason` |
| `photo_progress` | `onGlassesPhotoProgress` | `document` | A still photo is being transferred: `received`, `total`, `fraction` |
| `speech` | `onGlassesSpeech` | `document` | Speech `state`, a transcription (`text`, `final`, `confidence`), a `locale`, or `error` |
| `motion` | `onGlassesMotion` | `document` | Motion `state`, or `samples` every 100 ms: `t`, `accel`, `gyro`, `mag`, `orientation` (x, y, z, w) and `source` |
| `input` | `onGlassesInput` | `document` | An input `state`, or an input: `kind` (`nav`, `select`, `back`, `capture`, `button`, ...), `source`, and `direction` or `press` |
| `voice` | `onGlassesVoice` | `document` | "Hey Meta" launched your app: `kind: "launch_app"`, `id`, `device`. Answer with `voice_respond` |
| `display` | `onGlassesDisplay` | `document` | The display `state`, a `click` with the node `id`, or a `video` state or error |
| `audio` | `onGlassesAudio` | `document` | The phone's audio route changed while `audio_route` is set |

On Android a failed registration arrives on the `registration` event with `error` and `message`. On iOS `register` answers with the error itself.

### Error codes

`audio_failed`, `bad_request`, `busy`, `capture_failed`, `device_limit` (too hot, battery low or at peak power), `display_failed`, `glasses_folded`, `init_failed`, `meta_ai_missing`, `mock_failed`, `mock_unavailable`, `no_device`, `not_active` (no session, or the capability is not on), `not_found`, `not_listening`, `not_recording`, `not_streaming`, `permission_denied`, `permission_failed`, `record_failed`, `registration_failed`, `registration_required`, `registration_unauthorized` (Meta refused this app without opening Meta AI), `server_failed`, `session_failed`, `stream_failed`, `timeout`, `unavailable` and `unsupported_device` (the glasses lack the capability, such as a display).

Errors from a capability on its event, or as a session error: `device_update_required`, `glasses_app_update_required`, `sdk_update_required`, `speech_failed`, `motion_failed`, `inputs_failed` and `voice_failed`. `update_suggested` is a warning on the `session` event; the session keeps running.

- Only on iOS: `platform_limit` (iOS before 17.2).
- Only on Android: `handler_error`, `permission_required` and `unavailable` (the activity is gone).

See also the standard codes.

---

## How the picture reaches the page

The extension runs a small HTTP server on `127.0.0.1`, on a port the system picks, under a fresh random token path. `start` answers with `data.live`, and your page sets it as an `<img src>`.

- `/<token>/live.mjpg` is a stream of JPEG frames. Each connection gets the newest frame only, so a slow page skips frames and never falls behind.
- `/<token>/frame.jpg` is the newest frame once.
- `/<token>/media/<id>.jpg` and `/<token>/media/<id>.mp4` are the saved photos and clips. They support byte ranges, so a `<video src>` can seek in a clip.

Each frame costs one JPEG encode on the phone. A clip is an MP4 with H.264 video from the same frames and, when `start` asked for `audio`, AAC audio from the glasses' microphones at the stream's sample rate and channel count.

If your WebView blocks local HTTP media, poll `frame` and draw `"data:image/jpeg;base64," + r.data.jpeg` when `r.data.seq` changed. Read saved media with `media_read?id=<id>&kind=mp4&chunk=<n>`: each chunk is 49152 bytes until `data.eof` is true.

## Registration and permissions

The app registers once with the Meta AI app. That opens Meta AI, which returns to your app's own URL scheme. The glasses camera and microphone permissions are asked in Meta AI too. Call `permission` with `prompt: true` first, then `register` when `camera` is `unknown`, then `permission` again. Meta AI can also start a registration itself: the page gets `registration_request` and answers it.

### iOS

- **Info.plist.** The manifest sets the `MWDAT` keys (`AppLinkURLScheme`, `MetaAppID`, `ClientToken`, `TeamID`), the background modes `bluetooth-central`, `bluetooth-peripheral` and `external-accessory`, the accessory protocol `com.meta.ar.wearable`, and `NSBonjourServices` with `_bonjour._tcp`.
- **Purpose strings.** `NSBluetoothAlwaysUsageDescription`, `NSLocalNetworkUsageDescription` and `NSMicrophoneUsageDescription`, under `requires.usage_descriptions`.
- **Package.** Meta's SwiftPM package 1.1.0 (`MWDATCore`, `MWDATCamera`, `MWDATDisplay`, `MWDATInputs`, `MWDATMotion`, `MWDATSpeech`, `MWDATMockDevice`), pinned in `ios/Package.resolved`. The route handler checks for iOS 17.2 before starting the toolkit. App launch on earlier iOS versions has not been tested.

### Android

- **Permissions.** `BLUETOOTH_CONNECT` (under `requires.permissions`) and `INTERNET`. The extension asks for `BLUETOOTH_CONNECT` before it starts the SDK. `audio_route` needs `MODIFY_AUDIO_SETTINGS`, which the Despia V3 app already declares; without it the route answers `permission_denied`.
- **Application meta-data.** `com.meta.wearable.mwdat.APPLICATION_ID` and `CLIENT_TOKEN`, from `android.meta_data` in the manifest.
- **Minimum Android.** `min_sdk` is 29 (Android 10). Below that, every route answers `platform_limit`.
- **Libraries.** `mwdat-core`, `mwdat-camera`, `mwdat-display`, `mwdat-inputs`, `mwdat-motion`, `mwdat-speech` and `mwdat-mockdevice` 1.1.0 from Maven Central, and `androidx.activity:activity:1.13.0`.
- **Exported component.** The Meta SDK adds a registration service that the Meta AI app binds to. It is listed in the build verification policy as Meta ships it.

---

## Platform limits

Sources: Meta's DAT 1.1.0 changelog for [iOS](https://github.com/meta/meta-wearables-dat-ios/blob/main/CHANGELOG.md) and [Android](https://github.com/meta/meta-wearables-dat-android/blob/main/CHANGELOG.md), release 1.1.0 of 20 November 2026, and the 1.1.0 SDK interfaces.

- **Experimental in DAT 1.1.0.** Speech, motion, inputs, voice, still photos and camera audio are marked experimental by Meta. Apps can build and test with them, but Meta does not yet allow publishing apps that use them. They may change in a later DAT release.
- **Sessions.** A session created before registration fails and is reported as `registration_required`. Unregistering stops every session. A refused stream start reports a hot device, a low battery or the peak power limit as `device_limit`. A photo from the stream times out after 10 seconds.
- **Still photos.** The stream and the standalone still photo share the glasses camera, so Meta asks apps to stop the stream before a still ([Camera Capture, iOS](https://github.com/meta/meta-wearables-dat-ios/blob/main/plugins/mwdat-ios/skills/camera-capture/SKILL.md) and [Android](https://github.com/meta/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/camera-capture/SKILL.md), DAT 1.1.0, read 24 November 2026). `photo` with `resolution` or `quality` answers `busy` while streaming; `photo` without them captures from the running stream.
- **Display.** Only Meta Ray-Ban Display has a display. Other glasses answer `unsupported_device`.
- **Phone audio.** DAT carries no phone audio. `audio_route` uses the glasses as a standard Bluetooth headset (HFP for calls, A2DP for media) through the platform's own audio routing.
- **Registration cancel.** On Android, DAT's `cancelRegistration()` has no choice to stay out of Meta AI, so `return_to_meta_ai` applies on iOS only.
- **Firmware version.** iOS DAT exposes no firmware version, so `firmware` is `null` on iOS. Android reports it.
- **Platform differences in events.** Android DAT reports extra voice `state` events and a `paused` motion state; iOS DAT has neither.
- **iOS.** Meta's toolkit needs iOS 17.2.
- **Android.** Meta's toolkit needs Android 10 (API 29).

## What needs real glasses

The automated tests ([TESTING.md](TESTING.md)) cover the routes, parameters and answers on both platforms without glasses, the debug simulator, the display tree rules (the same 24 documents on iOS and Android), the local media server and the clip writer. These still need real glasses paired through Meta AI: a real registration, the camera stream and photos from the glasses, audio from the glasses' microphones, sensor data, "Hey Meta", and a real Meta Ray-Ban Display.

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
