# PvP Leaderboard RuneLite Plugin

PvP Leaderboard is a RuneLite plugin that displays PvP rank icons or text above players, provides right‑click lookups, and shows profile stats from the PvP leaderboard service. It uses shard-based rank lookups and aggressive caching to devsecopsautomated.com for player lookups.



## Features
- Rank icons or text above players’ heads (tiers Bronze→Dragon, 3rd Age uses Armadyl godsword icon)
- Configurable icon size, white outline, X/Y offsets, and separate self/others offsets (defaults 0)
- Right‑click “pvp lookup” on players auto-opens the side panel and searches
- Nearby ranks used in overlays; unranked icon optional via Healer icon
- Rank number lookups use S3 shard files:
- Buckets: Overall, NH, Veng, Multi, DMM
- Side panel with profile stats, tier graph, rank progress, match history (paginated 100 per page)
- Optional Cognito login; authenticated match result POSTs & additional player information

## Configuration
Open RuneLite configuration for the plugin:
- Show Rank Icons: toggle icons/text overlay
- Show rank as text: display e.g. “Bronze 2” instead of an icon
- Rank Icon Size: default 24
- Rank Icon White Outline: ON by default
- Rank Icon Offset X (Self/Others): both default 0
- Rank Icon Offset Y: default 0
- Show your own rank: ON by default
- Rank Bucket: Overall, NH, Veng, Multi, DMM
- Nearby Leaderboard: removed per latest spec (was replaced earlier and then removed)
- Show unranked players: OFF by default (when ON, uses Healer icon)
- Grab player info only on login: OFF by default

## How lookups work
- Rank overlays: fetch rank from in-memory cache, backed by S3 downloads per bucket
- Rank numbers: shard files `https://devsecopsautomated.com/rank_idx/<bucket>/<pp>.json`
  - Prefer account_hash when available (from `/user`), else canonicalized name
  - In-memory cache with TTL ~60 minutes and 60s debounce per shard prefix
- Leaderboard JSON prewarm: on startup we pre-warm bucket caches to avoid first-hit lag

## Match history
- GET `/matches?player_id=<id>&limit=<N>[&next_token=...]`
- First page loads 100; “Load more” fetches next pages using `next_token`
- Per-profile in-memory cache keyed by normalized `player_id`
- UI updates charts and bucket bars incrementally

## Authentication
- Optional Cognito login opens a browser and exchanges code for tokens
- POST `/matchresult` includes JSON body in both authenticated and unauthenticated modes
  - Authenticated: `Authorization: Bearer <token>` + `x-account-hash`
  - Unauthenticated: HMAC headers (`x-client-id`, `x-timestamp`, `x-signature`)

## Credits
Author: Toyco / Ryan McCusker