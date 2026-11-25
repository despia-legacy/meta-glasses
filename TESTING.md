# Meta Glasses tests

Three levels: repository tests that need no phone, the debug simulator flow on an emulator or simulator, and the checks that need real glasses. Meta's Wearables Device Access Toolkit (DAT) 1.1.0 is the SDK under test on both platforms.

## 1. Repository tests (no phone)

```bash
python3 -m unittest discover -s tests -v
```

- `tests/test_glasses_parity.py` runs the same 24 display view tree documents (`tests/metaglasses/display/display-vectors.json`) through the Swift and the Java parser and expects identical answers. It also checks that every route, parameter and event in `manifest.json` is handled on both platforms.
- `tests/test_glasses_completion.py` checks the local media server's request lifetime on macOS Network.framework, the Android clip writer's codec and muxer completion against the real `android.jar`, and that release builds refuse the mock routes before the SDK starts.
- `tests/test_glasses_clip.py` compiles the iOS clip writer (`GlassesClipWriter`) for macOS and records decoded frames into a finished MP4, as `record_start` does on a stream.

Requirements: a JDK 17 or later for the Java checks, `swiftc` on macOS for the Swift checks, and an Android SDK with platform 36 (`ANDROID_HOME`, `ANDROID_SDK_ROOT` or `DESPIA_NX_ANDROID_JAR`). A check whose tool is missing is reported as skipped, not passed.

## 2. Debug simulator flow (emulator or simulator, no glasses)

The `mock` route turns on DAT's mock device kit in debug builds; release builds answer `mock_unavailable`. The flow below is the one a page runs: mock on (`meta_rayban_display`), status, devices, permission, session start, stream start with audio, frame, a stream photo, a refused still while streaming (`busy`), a 2 second clip, media, speech with a simulated transcription, motion, inputs (tap, nav up, capture hold; the tap is the glasses' pause gesture, so the flow resumes the session), voice launch, the display (a view tree, a simulated click, a refused tree, clear), pause and resume, battery and thermal events, stop, a still photo (`resolution` and `quality`), session stop, mock off.

### Android: native test

`tests/metaglasses/android/GlassesMockFlowTest.java` runs that flow in order through the extension's own entry point on a real Android runtime, the same calls with the same value types as the page, each waiting for its one answer, with events collected.

1. Build your Despia V3 Android app with this extension, in a debug build.
2. Copy the test into the app's `app/src/androidTest/java/despia/ext/metaglasses/flow/`.
3. Build the app and its test APK: `./gradlew :app:assemble<Variant>Debug :app:assemble<Variant>DebugAndroidTest`.
4. Install both on an emulator (Android 10 or later), then run
   `adb shell am instrument -w -e class despia.ext.metaglasses.flow.GlassesMockFlowTest <your.package>.test/androidx.test.runner.AndroidJUnitRunner`.
5. Pass is `OK (1 test)`. Every call and event is logged under the tag `GlassesMockFlow`.

### iOS and Android: from a page

Serve a page in a debug build that calls the routes in the order above with the Despia JS SDK (see the README's "Using it from your web app"), and check each answer's `ok` and `code`. On iOS, use a simulator with iOS 17.2 or later.

On the iOS simulator (DAT 1.1.0 mock, Mini simulator iOS 26.5, 25 Nov 2026) a stream started with `audio: true` ended about 6 seconds in with DAT's stream `timeout` error (the state event carries `error: timeout`), while the same flow with `audio: false` streams, takes stream photos and records clips; the Android mock streams with audio. Camera audio on iOS is therefore proven on real glasses (section 3), not on the simulator.

## 3. Real glasses (not automated)

These need a phone, the Meta AI app and paired Meta glasses. Run them on a debug build with Developer Mode on in Meta AI (Settings, then your glasses), or a build with your own Meta app credentials:

1. `permission` with `prompt: true`, then `register`: Meta AI opens and returns to your URL scheme, and `status.registration` becomes `registered`.
2. `permission` and `permission?kind=microphone` grant the glasses camera and microphone in Meta AI.
3. `devices` lists the glasses with link, battery, worn and hinge state.
4. `session_start`, then `start` with `audio=true`: the page shows the live picture and `frame` advances.
5. `photo` while streaming, `record_start` and `record_stop` (the clip plays with sound), then `stop` and `photo?resolution=full&quality=high` for a still.
6. `speech_start`, `motion_start`, `inputs_start` and `voice_start` ("Hey Meta"): events arrive from the glasses.
7. On Meta Ray-Ban Display: `display_start`, `display_send` with a view tree, a tap on a button, `display_video`, `display_clear`.
8. `audio_route?mode=media` plays phone audio on the glasses; `mode=default` returns it.
9. `stop`, `session_stop`, `unregister`.

Run them on both iOS and Android.
