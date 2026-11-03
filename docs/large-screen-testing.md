# Testing on TV, desktop and large screens

## Android TV (leanback) emulator

```sh
sdkmanager "system-images;android-34;android-tv;x86"
avdmanager create avd -n animadroid_tv -k "system-images;android-34;android-tv;x86" -d tv_1080p
emulator -avd animadroid_tv
./gradlew installDebug
```

Check with the emulator's D-pad (arrow keys, Enter = centre, Backspace/Esc = Back):

- The app appears on the TV launcher with its banner (`drawable/tv_banner.xml`).
- Every card, episode row and button shows a focus ring; D-pad order is left-to-right, top-to-bottom.
- Search: the field takes focus, Down moves into the results grid, Back returns to the field.
- Player: centre while controls are hidden pauses and shows them; Left/Right seek 10 s; Up/Down reveal the controls; with controls visible the D-pad moves between buttons; Back hides the controls first, then exits.
- Text is about 15% larger than on a phone (`UiModeManager` reports television).

## Desktop / free-form windows (Googlebook)

Use Android Studio Canary's "Desktop" AVD, or the Resizable emulator with the Googlebook profile (160 ppi), and drag the window edges.

- Resizing, snapping and switching between compact, medium and expanded widths must not restart the activity. Playback must keep going without a rebuffer (`configChanges` covers screenSize, smallestScreenSize, screenLayout, orientation, keyboard, keyboardHidden and uiMode).
- At 600 dp or wider the bottom bar becomes a navigation rail. Search shows results beside details (`ListDetailSceneStrategy`). Content stops growing at 1240 dp, except in the player.
- Grids add columns as the window widens.
- Keyboard in the player: Space or K play/pause, Left/Right seek 10 s, Up/Down volume, M mute, F fullscreen, Esc leaves fullscreen. Meta+/ lists them in the Keyboard Shortcuts Helper.
- Mouse: cards and rows highlight on hover and show a hand cursor. Right-click or long-press on a library card, a continue-watching card, a recent search or a download row opens a context menu.

## Multi-instance

`MainActivity` stays `singleTask`. Two instances would share one Room database, one download service and one `MediaSession`, and the notification deep link (`onNewIntent`) assumes a single task. Multi-window and free-form still work (`resizeableActivity="true"`); only multiple copies of the app do not. To allow it later, switch to `standard` and make the player session per-instance.

## Not verifiable without a device

Focus order and ring visibility, hover and right-click behaviour, the Shortcuts Helper, and resize-without-restart need a manual pass. The key mapping (`PlayerKeysTest`) and TV mode detection (`TelevisionModeTest`) are unit tested.
