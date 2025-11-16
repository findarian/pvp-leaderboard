# PvP Leaderboard RuneLite Plugin

PvP Leaderboard is a RuneLite plugin that displays PvP ranks above players, provides right‑click lookups, and shows profile stats from the PvP leaderboard service. Ranks are sourced from shard files hosted at `devsecopsautomated.com` and, when needed, from the API (AWS API Gateway) for new players not yet present in shards. The Overall bucket uses the API world rank and does not use shards.


## Features
- Rank display defaults to tier/division text (e.g., “Adamant 3”). You can switch to “Rank XYZ” or to an icon.
- Configurable icon size, white outline, and per‑player offsets (separate X/Y for Self and Others).
- Right‑click “pvp lookup” menu is OFF by default; forces an immediate API lookup and updates the overlay.
- Best/Worst Players Nearby overlays (separate, configurable count and font size, default off). Include local player.
- Side panel (Dashboard) with profile stats, tier graph, rank progress bars sized for the narrow sidebar, and match history (token‑paginated).
- Optional Cognito login for authenticated submissions; unauthenticated fallback is automatic.
- Automatic self‑rank refresh after matches, on login, and on bucket changes.
- No popup dialogs or plugin‑generated sounds.

## Configuration
Open RuneLite configuration for the plugin:
- Rank display mode: Text (tier/division), Rank Number, or Icon.
- Rank text size and overlay width; icon size and white outline.
- Rank icon/text offsets (Self/Others) X/Y.
- Best/Worst Players Nearby: enable, count (default 10), font size (default 15), width (default 180).
- Rank bucket: Overall (default), NH, Veng, Multi, DMM.
- Throttle level (0–10): controls how aggressively lookups are scheduled; level 10 enforces wider gaps.
- “pvp lookup” menu toggle (default OFF).

## How lookups work
- Primary source: shard files `https://devsecopsautomated.com/rank_idx/<bucket>/<pp>.json` with 1‑hour cache.
- Overall bucket: world rank is fetched from `/user`. If `/user` is unavailable, the last cached value remains until a later refresh succeeds.
- Negative cache/backoff:
  - Name misses → 1‑hour negative cache per bucket.
  - Per‑shard throttle → minimum 60s between fetches; single‑flight per shard.
  - Network fail‑fast: connect/read timeouts are short (≈3s/4s) and errors trigger temporary backoffs.
- API fallbacks:
  - Self: on shard miss, derive tier from `/user` or recent `/matches` quickly.
  - Others: after a fight, if an opponent isn’t in shards, schedule a delayed `/user` and show that rank until shards are fresh.
  - Right‑click “pvp lookup” forces an API override for that player until shards update.

## Match history
- GET `/matches?player_id=<id>&limit=<N>[&next_token=...]` with token‑based pagination.
- Per‑profile in‑memory cache keyed by normalized `player_id`.
- UI updates charts and bucket bars incrementally.

## Networking, performance & logging
- All network I/O runs off the client thread via RuneLite’s scheduler to avoid stutter.
- Short timeouts and backoffs prevent repeated attempts during outages.
- Overlay lookups are per‑tick rate‑limited with bounded concurrency to avoid client stutter.
- To enable debug logs, start RuneLite with JVM args, e.g. `-Dlogback.configurationFile=path\to\logback-debug.xml` (optional: `-ea`).

## Authentication
- Optional Cognito OAuth; tokens are refreshed in the background using the refresh token.
- Match results submit immediately after death; unauthenticated fallback if the authed path fails. The only INFO logs retained are path‑accepted lines per plugin‑hub guidance.

## Build
```bash
./gradlew clean build
```
Jar output is written to `build/libs/`.

## Install
1) Build the plugin:
   - Linux/macOS: `./gradlew clean build`
   - Windows: `.\gradlew.bat clean build`
2) Copy the jar from `build/libs/` to your RuneLite plugins directory:
   - Windows: `C:\Users\<you>\.runelite\plugins`
3) Restart RuneLite and enable “PvP Leaderboard” in the plugin list.

## Known issues
- The `damage_to_opponent` value may be inaccurate; this does not impact other functionality.

## Credits
Author: Toyco / Ryan McCusker
