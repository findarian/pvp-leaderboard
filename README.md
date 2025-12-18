# PvP Leaderboard RuneLite Plugin

## Overview
This plugin submits matches when someone dies, either you or the opponent. Once the match is submitted a calculation is done in the backend of https://devsecopsautomated.com/ and your rating is updated.

The backend is able to determine based off off the data sent if the match was NH / Veng / Multi / DMM. Each one of these has their own separate MMR values. Combining them all together in an average gives you your Overall rating.

## Screenshots

## Identity & Smurf Detection
This plugin is able to detect smurfs and alt accounts and prevents other people submitting matches for you.


## Configuration
Open RuneLite configuration for the plugin:
“pvp lookup” menu toggle (default OFF).
 - You will see this option when right clicking players, and it will open up the side panel for the plugin and submit a request to get a player's rank in each style and their recent match history

MMR change
-You can choose to offset to have this "xp drop" done wherever, but it is linked to your XP drop by default. 
-You can also change how long it stays on screen 1-10 seconds.

## How lookups work
- **Shard key**: First 2 characters of lowercase player name (e.g., `toyco` -> `to.json`, `MOH JO JOJO` -> `mo.json`)
- **Primary source**: shard files `https://devsecopsautomated.com/rank_idx/<bucket>/<shard_key>.json` with 1‑hour cache.
- **Negative cache/backoff**:
  - Name misses → 1‑hour negative cache per bucket.
  - Per‑shard throttle → minimum 60s between fetches; single‑flight per shard.
  - Network fail‑fast: connect/read timeouts are short (≈3s/4s) and errors trigger temporary backoffs.

## Match history
- You see the last 100 matches of a player that you look up

## Networking, performance & logging
Requests are done to the website or the API.

## Authentication
- Optional Cognito OAuth; tokens are refreshed in the background using the refresh token.
- This gives you additional stats for plugin lookup and on the website

## Credits
Author: Toyco / Ryan McCusker
