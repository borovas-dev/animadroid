# AniList / MyAnimeList tracking

Watched episodes (past 85%) are pushed to AniList and/or MyAnimeList. The feature is off until the owner registers the
apps and builds with the client ids. Without ids, Settings > Tracking shows "Not configured in this build" and nothing else changes.

## Registering the apps (no secrets are used or stored in the repo)

| Service | Where | Redirect URI | Notes |
|---|---|---|---|
| AniList | https://anilist.co/settings/developer > Create New Client | `animadroid://auth/anilist` | Implicit grant (token in the URL fragment); no client secret needed. Tokens last one year, then the user reconnects. |
| MyAnimeList | https://myanimelist.net/apiconfig > Create ID | `animadroid://auth/mal` | App Type: `other`. Auth code + PKCE (`code_challenge_method=plain`); no client secret needed. Access tokens are refreshed automatically. |

Pass the ids as Gradle properties (`~/.gradle/gradle.properties` or `-P` on the command line, or CI secrets):

```
ANILIST_CLIENT_ID=12345
MAL_CLIENT_ID=0123456789abcdef0123456789abcdef
```

They become `BuildConfig.ANILIST_CLIENT_ID` / `MAL_CLIENT_ID` (client ids are public identifiers, not secrets).

## Behaviour

- Tokens are AES-GCM encrypted with an Android Keystore key (`sync_secure` prefs); never in settings JSON or backups.
- Updates are queued in `filesDir/sync_queue.json`, coalesced per show (furthest episode wins), retried by WorkManager once online.
- Remote progress is never lowered; status becomes COMPLETED/completed when the episode reaches the tracker's episode count.
- Shows are matched through their MAL id (provider-supplied or resolved from the title); shows without one are skipped.
