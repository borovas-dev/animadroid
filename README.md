# animadroid

A native Android port of [ani-cli](https://github.com/pystardust/ani-cli), built with Kotlin, Jetpack Compose and **Material 3 Expressive**.

- Search, browse and stream anime through pluggable providers (hianime first, ported from ani-cli v5)
- Native Media3/ExoPlayer player with PiP, background playback and quality selection
- [ani-skip](https://github.com/synacktraa/ani-skip) integration to skip openings/endings (optional)
- Local history: recent searches and exact resume position per episode
- Offline downloads, backup/export (including ani-cli `ani-hsts` import)
- Optional on-device recommendations with Gemma 4 E2B via LiteRT-LM (downloaded on opt-in; no cloud inference)

## Build
Requires JDK 21+ and Android SDK platform 37.2.
```
./gradlew :app:assembleDebug
```

## Testing
- Unit tests: `./gradlew testDebugUnitTest`
- UI tests: `./run-ui-tests.sh [.qa]` runs `app/src/androidTest` one test per instrumentation process (DataStore allows one instance per file per process). Compose tests drive the clock manually: the app has endless animations and the Cloudflare solver polls forever, so auto-advance would never reach idle.
- QA hook (debug builds only): `adb shell am start -n <appId>/com.borovas.animadroid.MainActivity --ez debug_force_cf true` makes every non-image request fail as a Cloudflare challenge until the in-app verification sheet finishes loading.
- Hilt/KSP: the occasional `MissingBinding` after a clean build of `:provider:hianime` / `:feature:aniskip` did not reproduce here (`./gradlew clean :app:assembleDebug` succeeds). If it appears, run `./gradlew --stop`, delete `.gradle/` and `*/build/`, and rebuild; the cause is a stale KSP incremental cache.

## Modules
`app` · `core/{model,network,database,datastore,designsystem}` · `provider/{api,hianime}` · `feature/{search,details,player,aniskip,library,downloads,settings,recommend}`

## Disclaimer
animadroid does not host any content; it only scrapes publicly available third-party sites, like ani-cli. See ani-cli's disclaimer.

## License
GPL-3.0 — see [LICENSE](LICENSE).
