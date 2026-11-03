# Remote provider config

`providers.json` is fetched by the app at start from
`https://raw.githubusercontent.com/borovas-dev/animadroid/main/config/providers.json` (ETag cached in `filesDir`,
bundled fallback in `core/remoteconfig/src/main/resources/providers.json`). The detached Ed25519 signature
`providers.json.sig` (base64 of the raw 64-byte signature) must sit next to it. If verification fails the app keeps
its cache / bundled copy. Debug builds also accept a document with no `.sig`; a wrong `.sig` is always rejected.
`revision` must only ever increase (older documents are ignored).

## Schema

```json
{ "revision": 1,
  "providers": { "<id>": {
      "enabled": true,
      "baseUrls": ["https://primary", "https://mirror"],
      "gfwBlocked": false,
      "minAppVersion": "0.1.0",
      "params": { "server": "ZokoAnime", "xorKey": "..." } } } }
```
All fields are optional. `enabled` reads false in the app when it is older than `minAppVersion`.

## Owner setup (one time)

```sh
tools/sign-config.sh keygen        # prints the base64 public key; private key stays in ~/.config/animadroid/
```
Signing is currently OFF (`RemoteConfigKeys.ALLOW_UNSIGNED_CONFIG = true`): release builds accept an unsigned config, so
you can edit `config/providers.json` and push it to `main` without a key. To enforce signing later, set that to false, then:

Paste the public key into `RemoteConfigKeys.PUBLIC_KEY_BASE64` (currently a PLACEHOLDER, so release builds ignore
remote documents until you do) and ship a release. Back up the private key; losing it needs an app release.

## Editing the config

1. Edit `config/providers.json`, bump `revision`.
2. `tools/sign-config.sh sign` (writes `providers.json.sig`, copies the file into the bundled resources;
   a unit test fails if the two copies differ).
3. Commit `providers.json`, `providers.json.sig` and the bundled copy to `main`.

## Provider usage

```kotlin
class XProvider @Inject constructor(private val remote: RemoteConfigRepository, ...) {
    private val cfg get() = remote.forProvider("xifan")   // never null; defaults = enabled, no overrides
    private val base get() = cfg.baseUrlOr("https://anime.xifanacg.com")
    private val server get() = cfg.param("server", "ZokoAnime")
}
```
Read `cfg` on each request (not in `init`) so a refresh applies without a restart. Honor `cfg.enabled`, and try
`cfg.mirrorsOr(listOf(default))` in order. Add `implementation(project(":core:remoteconfig"))` to the provider module.
