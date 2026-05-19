# Backend Handoff — Lobby Socket Protocol (Phase 2)

> **Audience.** The backend session implementing `p2-plugin-service`'s server side at `wss://api.pvp-leaderboard.com/ws/prod` (i.e. `OSRS-MMR/lambda_code/lobby_handler.py` + `backend/core/lobby.py` + new DynamoDB tables + env vars).
>
> **Source of truth.** [`docs/SOCKET_LOBBY_ARCHITECTURE.md`](./SOCKET_LOBBY_ARCHITECTURE.md) is canonical, but it predates the UI iteration that locked in the build-type picker, the per-style sub-location rules, the MeetAt world resolver, the mod-flag flow, and the current-vs-peak decision. **This document overrides the architecture** for every field/decision listed in [§2 Locked Decisions](#2-locked-decisions). When this file and `SOCKET_LOBBY_ARCHITECTURE.md` disagree, this file wins until the architecture is rewritten.
>
> **Plugin-side state.** The seam is complete:
> - Plugin gate, lobby roster, fight-setup, mutual-confirm, and Meet-At views are all wired to a `com.pvp.leaderboard.lobby.LobbyService` interface.
> - Production wiring at [`DashboardPanel.createMatchmakingCard`](../src/main/java/com/pvp/leaderboard/ui/DashboardPanel.java) is currently a `NoOpLobbyService` placeholder — the lobby renders empty until the real `WebSocketLobbyService` lands.
> - Mock data + auto-accept/auto-confirm timers live in `DevLobbyFixture` (test-only, used by `DevLobbyFixtureTest`). No mock data reaches production jars.
>
> **What this document gives you.** The exact wire-payload shape per command, the per-table column additions, the per-style sub-location enum, the validation rules per command, the error-code matrix, and a checklist you can tick off as you implement.

---

## 1. Context

The plugin-side lobby UX is fully built and visible behind the gate. It does the following entirely client-side under `DevLobbyFixture` mock data:

1. User passes the gate: picks one region (advertised only), 1–4 styles they're willing to play (subset of NH/Veng/Multi/DMM), 1–3 builds (subset of Main/Zerker/Pure), and a min/max rank range on the slider.
2. Lobby renders 50 mock members. Each member row shows: name, rank chip, region chip, advertised-style chips, advertised-build chips, optional `[MOD]` chip, and a `[Fight]` button.
3. Receiver-side: any pending `IncomingInvite` from a sender whose rank falls inside the slider renders as a card at the top of the roster with `[Accept Fight]` / `[Decline Fight]` and a live countdown.
4. Sender-side: clicking `[Fight]` enters a Pick Style → (Pick Sub-location for NH/Multi) → Pick Build → `submitOutgoingInvite()` flow. The row's chip flips to `[Invited M:SS]` until the opponent accepts.
5. Either side: the server's `lobby/fight_proposed` push transitions both panels to the ConfirmFight view (30 s window). Both must click Confirm Fight.
6. Both confirmed → terminal MeetAt view with `World: X` and `Meet at: Y`.
7. `Find a new match` exits the fight setup at any step.

**What the backend must implement** is the server side of every operation above. The plugin sends commands; the server pushes events back. Nothing client-side is authoritative — the plugin is purely presentational.

---

## 2. Locked Decisions

Captured verbatim from the 2026-05-14 design session. These override `SOCKET_LOBBY_ARCHITECTURE.md` where they differ.

### Q1 — Build types (Main / Zerker / Pure)

**Question:** Build types are not in the architecture at all. How should backend support them?

**Answer:** *"This should be added to the architecture. The plugin is how I want it to look, and the backend is meant only to support it. Any information that is happening should be over the socket. How this information looks I will leave up to you to follow best practice and ask me questions if it's unclear."*

**Implementation:**
- Add `builds: StringSet` column to `OSRS-LobbyMembers` carrying a subset of `{main, zerker, pure}`. Member row is invalid (and `lobby/join` is rejected with `INVALID_BUILD`) if the set is empty.
- Add `build: String` column (single value) to `OSRS-LobbyInvites`. The sender picks which one of the receiver's advertised builds they want to fight.
- Add `build: String` column (single value) to `OSRS-FightSessions`. Carried through to `lobby/match_found.build` so the Meet-At view can render `<Style> - <Build> @ <Place>`.
- Add `ALLOWED_BUILDS=main,zerker,pure` Lambda env var alongside the existing `ALLOWED_STYLES` / `ALLOWED_LOCATIONS`.
- `lobby/invite` validation: server rejects with `INVALID_BUILD` if the picked build is not in the **receiver's** advertised `builds`.

### Q2 — Multi / DMM sub-locations

**Question:** `ALLOWED_LOCATIONS` in the architecture is `arena, wildy, ffa, other`. The plugin needs Multi's `wilderness` / `clan_wars` and the Veng / DMM "no sub-location" case. Pick the shape.

**Answer:** **Per-style enum.**

**Implementation:** Replace the single `ALLOWED_LOCATIONS` env var with one per style:

| Env var | Allowed values |
|---|---|
| `ALLOWED_LOCATIONS_NH` | `arena, wildy, ffa` |
| `ALLOWED_LOCATIONS_VENG` | `none` |
| `ALLOWED_LOCATIONS_MULTI` | `wilderness, clan_wars` |
| `ALLOWED_LOCATIONS_DMM` | `none` |

Server-side validator at `lobby/invite`: `location ∈ ALLOWED_LOCATIONS_<style.upper()>` → else `INVALID_LOCATION`. For Veng / DMM the wire MUST send `location: "none"` (not empty string, not null) — the plugin already normalises to `"none"` in `submitOutgoingInvite` for these two styles.

### Q3 — MeetAt world + meeting place

**Question:** MeetAt resolution: who decides the world number and the meeting place (Arena / Ferox / GE)?

**Answer:** **Server resolves both.** `lobby/match_found` payload includes `world: int` and `meeting_place: string` — plugin renders verbatim.

**Implementation:**
- Backend has authoritative world tables (NH region→world map, Veng member-world pool, DMM W345 fixed, etc.). When `confirm_fight` is the second confirm and the session promotes to `match_found`, the server resolves the world per the rules below and the meeting place per the (style, location) tuple, then broadcasts both in the push.
- World resolution rules:
  - `style=nh, location=arena` → region-specific Arena world (e.g. NA-W578, EU-W558, AUS-W370). If both players share a region, use that region's world. If different, pick deterministically (e.g. lower-UUID wins).
  - `style=nh, location=wildy|ffa` → any active wildy world; same region-tie-break rule.
  - `style=veng, location=none` → random member world (existing backend member-world list).
  - `style=multi, location=wilderness|clan_wars` → any active wildy world; same tie-break.
  - `style=dmm, location=none` → W345 (fixed).
- Meeting-place strings (server returns these verbatim; the plugin already knows how to render them):
  - `nh+arena` → `"Arena"`
  - `nh+wildy` → `"Ferox Enclave"`
  - `nh+ffa` → `"Ferox Enclave"`
  - `veng+none` → `"Grand Exchange"`
  - `multi+wilderness` → `"Ferox Enclave"`
  - `multi+clan_wars` → `"Ferox Enclave"`
  - `dmm+none` → `"Grand Exchange"`

### Q4 — `isMod` flag

**Question:** The plugin's `LobbyMember` has an `isMod` flag (renders a `[MOD]` chip). Chat and `OSRS-LobbyModerators` are deferred. What do we do with `isMod`?

**Answer:** *"When players in the lobby are looked up it is done via sharding. In this sharding it should be added if someone is a 'mod' or not. This is something that won't change frequently and should be added in via a dynamodb table where I add in specific names that are mods. If there is a better way of doing this over the socket, let me know. There will be very few people with mod status (less than 10 in total)."*

**Implementation (recommended — socket-push):**
- New DynamoDB table: `OSRS-LobbyMods` — PK `uuid`, attrs `added_at`, `added_by`, `notes`. Operator-curated (no plugin write path). Expected size ≤ 10 rows.
- `lobby_handler` keeps the full UUID set hot in-memory per Lambda execution context. Cold-start fetches all rows; warm invocations refresh every 5 min via a TTL check (`now - last_refresh_at > 300s`). At ≤ 10 rows this is a single `Scan` that completes in milliseconds.
- `lobby/roster` push: each row carries `is_mod: bool` (server stamps from the in-memory set).
- **Why not shards?** The original suggestion was shard-baked `is_mod` (mirroring how rank info is already cached). That works for the player-lookup page (which already reads shards), but for the lobby roster — which is socket-pushed — adding a separate shard lookup per row would be wasteful when the server already knows. We recommend baking `is_mod` directly into the socket push for the lobby and keeping shards in scope only if/when the player-lookup page also needs the flag.
- Open question for you: should `is_mod` ALSO go into shards (for the player-lookup page hover-tooltip)? If yes, add it to `leaderboard_cache_writer.py` as a separate (small) task — but it's not blocking for the lobby.

### Q5 — Current vs. peak rating

**Question:** How should `LobbyMember.peakRankIdx` be sourced? (Plugin needs ONE rank-index per row for the slider greying.)

**Answer:** *"I actually want to use the current rating rather than peak rating for the lobby."*

**Implementation:**
- **Phase 1.5 (shard schema bump for `peak_mmr`) in `SOCKET_LOBBY_ARCHITECTURE.md` is no longer required for the lobby** — current MMR is already in shards. Phase 1.5 is dropped from the critical path. If you want to keep `peak_mmr` in shards for an unrelated future feature (e.g. tournament seeding), that's fine, but the lobby doesn't need it.
- `OSRS-LobbyMembers` row: replace `peak_per_bucket: Map<bucket, mmr>` (architecture's name) with `current_mmr_per_bucket: Map<bucket, mmr>`. Anti-smurf gate at `lobby/join` uses **current overall MMR**, not peak.
- `lobby/roster` push: each row carries `current_mmr_per_bucket: Map<bucket, int>` AND a pre-resolved `rank_idx: int` (the integer index into the plugin's `RANK_LABELS` table for the viewer's current sort bucket — see [§3](#3-data-types) for the field).
- Rename env var: `LOBBY_MIN_PEAK_OVERALL_MMR` → `LOBBY_MIN_CURRENT_OVERALL_MMR` (same default value 940 unless you want to retune for current-rating semantics).

### Q6 — `pingedYou` flag

**Question:** `pingedYou` (the [Lookup] chip on rows for senders who invited me): server-pushed or client-derived?

**Answer:** **Client-derived.** Panel tracks it via incoming `lobby/invite_received` and `lobby/invite_cancelled` pushes.

**Implementation:** `lobby/roster` rows do NOT include a `pinged_you` field. On reconnect, the server replays any outstanding incoming invites as `lobby/invite_received` pushes so the plugin re-derives state.

### Q7 — Block list visibility

**Question:** Block-list visibility. The UI needs to know "who have I blocked" so it can offer Unblock.

**Answer:** **Snapshot on join + delta pushes.** Plus: blocked users render greyed-out on the roster (not hidden); clicking a blocked row routes to the player-lookup page where Unblock lives.

**Implementation:**
- New server-only outbound cmds (add to the existing list in `SOCKET_LOBBY_ARCHITECTURE.md` §Phase 2 "Server-only outbound cmds"):
  - `lobby/block_list_snapshot` — pushed once on `lobby/join` success. Payload: `{"blocked_uuids": [<uuid>, ...]}`.
  - `lobby/block_added` — pushed when `lobby/block` succeeds. Payload: `{"blocked_uuid": "<uuid>"}` (field name finalized 2026-05-18 per the shipped `lobby_handler.py`; the older `WEBSOCKET_PROTOCOL.md` table that names it `uuid` is stale).
  - `lobby/block_removed` — pushed when `lobby/unblock` succeeds. Payload: `{"blocked_uuid": "<uuid>"}`.
- **Mutual-hide direction:** the architecture's "mutual hide" is now **one-directional**.
  - If I have X blocked: X stays on my roster (greyed by the plugin via `block_list_snapshot`). I can right-click → Unblock (in player-lookup).
  - If X has me blocked: I never see X (server filters X from my `lobby/roster` push).
- `lobby/block` server-side: still cancels any pending invite OR fight session between the two parties (architecture rule preserved).

### Q8 — Style advertisement

**Question:** Style advertisement: does the server store the full Set of styles a member is willing to play, or just one sort_bucket?

**Answer:** *"All member should be displayed, but if they don't match the styles you have set, then the fight button is greyed out and not clickable. Players can reset options to have more options available. When someone is blocked, I just want their entire card to be greyed out but still there. Clicking that card will take them to the player lookup and they can click unblock there if they would like to in order to send and recieve matches from them. I only care about the NH/veng/multi/dmm styles and Main/Zerker/Pure. Outside of that I don't want the fight option to be greyed out."*

**Implementation:**
- `OSRS-LobbyMembers` row carries `styles: StringSet` (advertised) — subset of `{nh, veng, multi, dmm}`. **No** separate `sort_bucket` column. Drop the architecture's `sort_bucket` field entirely (the plugin sorts client-side and doesn't need a server-tracked preference).
- Server does NOT enforce style-overlap between sender and receiver at `lobby/invite` time. (Plugin grays the Fight button client-side as a UX gate, but if someone bypasses the UI and sends an invite anyway, the server accepts it as long as the rank-range gate + build gate pass.)
- Greying rules summary (plugin-side; this is what the panel does — listed here so the wire shape supports it):

| Rule | Effect on opponent's row | Source of truth |
|---|---|---|
| Their `styles` ∩ my `styles` = ∅ | `[Fight]` button greyed | `lobby/roster.styles` per row |
| Their `builds` ∩ my `builds` = ∅ | `[Fight]` button greyed | `lobby/roster.builds` per row |
| Their `rank_idx` outside my `[minRankIdx, maxRankIdx]` slider | Row hidden entirely | `lobby/roster.rank_idx` + plugin slider |
| Their `uuid` in my `lobby/block_list_snapshot` | Whole row greyed, click → player-lookup | server snapshot |
| They have me blocked | Row not present at all | server-side filter on `lobby/roster` |

### Q9 — Placeholder wiring

**Question:** Right now `DashboardPanel` hard-wires `new DevLobbyFixture()` into production. Which placeholder until `WebSocketLobbyService` lands?

**Answer:** *"Write a placeholder and mark as a todo in the handoff for a requirement from the backend in order to support the plugin."*

**Implementation:** Done plugin-side. `NoOpLobbyService` is wired at [`DashboardPanel.createMatchmakingCard`](../src/main/java/com/pvp/leaderboard/ui/DashboardPanel.java). Every method is a no-op; no events are pushed. The lobby renders empty until `WebSocketLobbyService` replaces it.

**Backend requirement to unblock this:** the WebSocket endpoint at `wss://api.pvp-leaderboard.com/ws/prod` must accept the commands in [§4](#4-wire-commands-plugin--server) and emit the pushes in [§5](#5-wire-pushes-server--plugin). Once that contract is met, swapping `new NoOpLobbyService()` → `new WebSocketLobbyService(webSocketManager)` is the only plugin-side change needed.

---

## 3. Data Types (canonical wire shape)

> All instants are wall-clock **epoch milliseconds** (not seconds). All UUIDs are v4 strings. Sets serialise as JSON arrays. Maps serialise as JSON objects keyed by the enum value's lowercase wire form.

### `LobbyMember` (each row on `lobby/roster`)

| Field | JSON type | Notes |
|---|---|---|
| `uuid` | string (v4) | OSRS client UUID stamped at `$connect` |
| `name` | string | OSRS display name (canonical) |
| `styles` | string[] | Subset of `{"nh","veng","multi","dmm"}`. At least 1. |
| `builds` | string[] | Subset of `{"main","zerker","pure"}`. At least 1. |
| `current_mmr_per_bucket` | object | `{"nh":1234,"veng":1100,"multi":900,"dmm":800,"overall":1050}` — every key present, missing buckets sent as 0. |
| `rank_idx` | int | Pre-resolved index into the plugin's `RANK_LABELS` table for the viewer's current sort bucket. Server computes per-viewer via `RankUtils.THRESHOLDS`-equivalent. |
| `region` | string | One of `{"NA-E","NA-W","EU","AUS","ANY"}` (matches plugin's `REGION_CODES`). |
| `is_mod` | bool | True iff `uuid ∈ OSRS-LobbyMods`. |

### `IncomingInvite` (payload of `lobby/invite_received`)

| Field | JSON type | Notes |
|---|---|---|
| `invite_id` | string (v4) | Server-minted. Plugin uses it as the key for `lobby/accept` / `lobby/decline`. |
| `sender` | LobbyMember | Full member object (NOT a uuid reference). |
| `style` | string | One of `{"nh","veng","multi","dmm"}`. |
| `build` | string | One of `{"main","zerker","pure"}`. **Which of MY (the receiver's) advertised builds the sender wants to fight.** |
| `location` | string | Per-style allowlist (see §Q2). |
| `expires_at_epoch_ms` | int64 | Absolute deadline (server clock). |

### `FightSession` (payload of `lobby/fight_proposed`)

| Field | JSON type | Notes |
|---|---|---|
| `fight_session_id` | string (v4) | Server-minted. Plugin uses it as the key for `lobby/confirm`. |
| `opponent` | LobbyMember | Full member object (the OTHER player, from the local user's perspective). |
| `style` | string | Same encoding as above. |
| `build` | string | The build the sender picked (NOT necessarily the same as the receiver's build). |
| `location` | string | Same encoding as above. |
| `confirm_expires_at_epoch_ms` | int64 | Absolute 30 s deadline (server clock). |

### `MatchInfo` (payload of `lobby/match_found`)

| Field | JSON type | Notes |
|---|---|---|
| `fight_session_id` | string (v4) | Same id as the proposed-session that promoted to match-found. |
| `opponent` | LobbyMember | Full member object. |
| `style` | string | Same encoding. |
| `build` | string | Same encoding. |
| `location` | string | Same encoding. |
| `world` | string | World number, e.g. `"W370"`, `"W578"`. Stringified so leading-W stays. |
| `meeting_place` | string | Free-text per the table in §Q3. |

---

## 4. Wire Commands (Plugin → Server)

Every command is a JSON object with `{"cmd": "<command>", "data": {...}}`. The connection UUID is implicit on the socket (server reads it from the `OSRS-Connections` row keyed by `connectionId`).

### `lobby/join`

Plugin sends after the user passes the gate. Server responds with `lobby/roster` and `lobby/block_list_snapshot` (and zero-or-more `lobby/invite_received` replay pushes).

```json
{ "cmd": "lobby/join", "data": {
    "region": "NA-E",
    "styles": ["nh","veng"],
    "builds": ["main","pure"],
    "min_rank_idx": 0,
    "max_rank_idx": 27
} }
```

| Field | Required | Validation | Error code on fail |
|---|---|---|---|
| `region` | yes | ∈ `ALLOWED_REGIONS` (preserved from architecture, lowercase the plugin's `"NA-E"` etc. into `"na-e"` server-side OR accept the plugin's casing — locked in your impl) | `UNKNOWN_REGION` |
| `styles` | yes | Non-empty, subset of `{nh,veng,multi,dmm}` | `INVALID_STYLE` |
| `builds` | yes | Non-empty, subset of `{main,zerker,pure}` | `INVALID_BUILD` |
| `min_rank_idx` | yes | 0 ≤ x < `len(RANK_LABELS)` | `INVALID_DISPLAY_RANK` |
| `max_rank_idx` | yes | min ≤ max < `len(RANK_LABELS)` | `INVALID_DISPLAY_RANK` |
| (current overall MMR) | server-side | ≥ `LOBBY_MIN_CURRENT_OVERALL_MMR` | `SMURF_GUARD` |

### `lobby/leave`

Idempotent. Server deletes the member row + cancels every outstanding invite involving the UUID.

```json
{ "cmd": "lobby/leave", "data": {} }
```

### `lobby/invite`

```json
{ "cmd": "lobby/invite", "data": {
    "to_uuid": "<opponent-uuid>",
    "style": "nh",
    "build": "main",
    "location": "arena"
} }
```

| Field | Required | Validation | Error code on fail |
|---|---|---|---|
| `to_uuid` | yes | Opponent is currently in lobby (`OSRS-LobbyMembers` row exists) | `PEER_NOT_IN_LOBBY` |
| `style` | yes | ∈ `ALLOWED_STYLES` | `INVALID_STYLE` |
| `build` | yes | ∈ `ALLOWED_BUILDS` AND ∈ receiver's advertised `builds` | `INVALID_BUILD` |
| `location` | yes | ∈ `ALLOWED_LOCATIONS_<style.upper()>` | `INVALID_LOCATION` |
| (rank-range) | server-side | Both parties' `current_mmr_per_bucket[style]` falls inside the OTHER party's `[min_rank_idx, max_rank_idx]` after resolving MMR → rank_idx | `RANK_OUT_OF_RANGE` |
| (mutual block) | server-side | Neither party has the other blocked | `BLOCKED` |
| (dup) | server-side | No existing pending invite from sender→receiver | `DUPLICATE_INVITE` |

### `lobby/cancel_invite`

Idempotent. Sender voluntarily withdraws their pending invite. Server removes the `OSRS-LobbyInvites` row (or no-ops if already gone) and pushes `lobby/invite_cancelled` to the receiver.

```json
{ "cmd": "lobby/cancel_invite", "data": { "to_uuid": "<opponent-uuid>" } }
```

### `lobby/accept`

Receiver clicks Accept Fight. Server validates the invite still exists + hasn't expired, atomically writes an `OSRS-FightSessions` row, deletes the `OSRS-LobbyInvites` row, and pushes `lobby/fight_proposed` to BOTH players.

```json
{ "cmd": "lobby/accept", "data": { "invite_id": "<server-minted-v4>" } }
```

| Validation | Error code |
|---|---|
| Invite exists | `INVITE_EXPIRED` |
| Caller UUID == invite's `to_uuid` | `BLOCKED` (or a new `NOT_INVITE_RECIPIENT` code — your call) |
| Invite TTL > 0 | `INVITE_EXPIRED` |

### `lobby/decline`

Receiver clicks Decline Fight. Server removes the `OSRS-LobbyInvites` row. No follow-up event pushed to either party (per architecture).

```json
{ "cmd": "lobby/decline", "data": { "invite_id": "<server-minted-v4>" } }
```

### `lobby/confirm`

Mutual-confirm. Server appends caller's UUID to `confirmed_by[]`. If the set has both UUIDs, server promotes to `match_found`, deletes both lobby member rows + the session row, and broadcasts `lobby/match_found` to both. If TTL elapses with only one confirm, server broadcasts `lobby/session_expired` and both players stay in the lobby.

```json
{ "cmd": "lobby/confirm", "data": { "fight_session_id": "<server-minted-v4>" } }
```

| Validation | Error code |
|---|---|
| Session exists and TTL > 0 | `FIGHT_SESSION_EXPIRED` |
| Caller UUID == `player_a_uuid` OR `player_b_uuid` | `BLOCKED` (or new `NOT_SESSION_PARTICIPANT`) |

### `lobby/block`

```json
{ "cmd": "lobby/block", "data": { "blocked_uuid": "<target-uuid>" } }
```

Server writes `OSRS-LobbyBlocks`, cancels any pending invite OR fight session between the two parties, then pushes `lobby/block_added` to the caller.

> Field name is `blocked_uuid` — finalized 2026-05-18 to match the shipped `lobby_handler.py`. Earlier drafts in this doc + `WEBSOCKET_PROTOCOL.md` named it `uuid`; the implementation wins.

### `lobby/unblock`

```json
{ "cmd": "lobby/unblock", "data": { "blocked_uuid": "<target-uuid>" } }
```

Server deletes the row (idempotent), then pushes `lobby/block_removed` to the caller.

---

## 5. Wire Pushes (Server → Plugin)

All pushes are JSON objects with `{"cmd": "<command>", "data": {...}}`. Pushes are fire-and-forget; the plugin does not ack them.

| Command | When fired | Payload |
|---|---|---|
| `lobby/roster` | After `lobby/join` succeeds; on any roster membership change for the viewer; on every `lobby/block` (refresh after server-side cancel side-effects) | `{ "members": [<LobbyMember>, ...] }` (excludes anyone who has me blocked; INCLUDES anyone I have blocked — plugin grays them) |
| `lobby/block_list_snapshot` | Once after `lobby/join` succeeds | `{ "blocked_uuids": ["<uuid>", ...] }` |
| `lobby/block_added` | After `lobby/block` succeeds | `{ "blocked_uuid": "<blocked-uuid>" }` |
| `lobby/block_removed` | After `lobby/unblock` succeeds | `{ "blocked_uuid": "<unblocked-uuid>" }` |
| `lobby/invite_received` | Sender's `lobby/invite` succeeds; replayed on receiver's `lobby/join` for outstanding invites | `<IncomingInvite>` (see §3) |
| `lobby/invite_cancelled` | Sender's `lobby/cancel_invite` succeeds, OR invite TTL elapses | `{ "invite_id": "<v4>" }` |
| `lobby/fight_proposed` | Receiver's `lobby/accept` succeeds (broadcast to BOTH players) | `<FightSession>` (see §3) |
| `lobby/fight_confirmed_by_peer` | First `lobby/confirm` succeeds (push to the OTHER party only) | `{ "fight_session_id": "<v4>" }` |
| `lobby/match_found` | Second `lobby/confirm` succeeds (broadcast to BOTH players) | `<MatchInfo>` (see §3) |
| `lobby/session_expired` | 30 s confirm window elapses with <2 confirms (broadcast to BOTH) | `{ "fight_session_id": "<v4>" }` |
| `presence/count` | Periodic (matches architecture's existing presence flow) | `{ "online": <int> }` |
| `system/error` | Any command rejects | `{ "code": "<ERROR_CODE>", "message": "<human-readable>" }` |
| `system/pong` | Reply to plugin's `system/ping` | `{}` |

---

## 6. DynamoDB Table Changes vs. Architecture

| Table | Architecture column | Replace with | Reason |
|---|---|---|---|
| `OSRS-LobbyMembers` | `sort_bucket: String` | (drop) | Q8 — server doesn't track sort preference |
| `OSRS-LobbyMembers` | `peak_per_bucket: Map` | `current_mmr_per_bucket: Map` | Q5 — current rating, not peak |
| `OSRS-LobbyMembers` | (no column) | `styles: StringSet` | Q8 — server stores advertised set |
| `OSRS-LobbyMembers` | (no column) | `builds: StringSet` | Q1 — first-class build advertisement |
| `OSRS-LobbyInvites` | `message: String` | (drop) | Chat-deferred — no invite message in this release |
| `OSRS-LobbyInvites` | (no column) | `build: String` | Q1 — sender picks one of receiver's builds |
| `OSRS-FightSessions` | (no column) | `build: String` | Q1 — carried through to MeetAt |
| **NEW** `OSRS-LobbyMods` | — | PK `uuid`, attrs `added_at`, `added_by`, `notes` | Q4 — moderator badge source |

---

## 7. Env Var Changes vs. Architecture

| Env var | Architecture value | New value | Reason |
|---|---|---|---|
| `ALLOWED_STYLES` | `nh,veng,multi,dmm` | (unchanged) | — |
| `ALLOWED_BUCKETS` | `nh,veng,multi,dmm,overall` | (unchanged — used by sort/rank-resolve) | — |
| `ALLOWED_LOCATIONS` | `arena,wildy,ffa,other` | **(drop — replaced)** | Q2 |
| **NEW** `ALLOWED_LOCATIONS_NH` | — | `arena,wildy,ffa` | Q2 |
| **NEW** `ALLOWED_LOCATIONS_VENG` | — | `none` | Q2 |
| **NEW** `ALLOWED_LOCATIONS_MULTI` | — | `wilderness,clan_wars` | Q2 |
| **NEW** `ALLOWED_LOCATIONS_DMM` | — | `none` | Q2 |
| **NEW** `ALLOWED_BUILDS` | — | `main,zerker,pure` | Q1 |
| `LOBBY_MIN_PEAK_OVERALL_MMR` | `940` | **rename to** `LOBBY_MIN_CURRENT_OVERALL_MMR` | Q5 |
| `LOBBY_INVITE_MAX_MESSAGE_LEN` | `200` | (drop — chat-deferred kills invite messages) | — |

---

## 8. Error Codes Used by Plugin

The plugin renders human-readable text via a localized table (task `xc-plugin-localized-errors`). Until that table is built, the panel silently drops `system/error` pushes. The backend's job is just to emit the right code; the message field can be empty / English-only for now.

Codes the plugin will eventually localize (subset of architecture's enum):

`UNKNOWN_COMMAND`, `INVALID_MESSAGE`, `MESSAGE_TOO_LARGE`, `RATE_LIMITED`, `UNKNOWN_REGION`, `INVALID_STYLE`, `INVALID_BUILD` (NEW), `INVALID_LOCATION`, `INVALID_DISPLAY_RANK`, `BLOCKED`, `INVITE_EXPIRED`, `FIGHT_SESSION_EXPIRED`, `PEER_NOT_IN_LOBBY`, `RANK_OUT_OF_RANGE`, `SMURF_GUARD`, `DUPLICATE_INVITE`.

`INVALID_BUILD` is the only new code vs. architecture; it covers both (a) the sender's chosen build is not in `ALLOWED_BUILDS` and (b) the sender's chosen build is not in the receiver's advertised set.

---

## 9. Open Items for Backend Session to Decide / Ask About

Items where best practice may differ from your codebase conventions — flag back to user if unsure.

| # | Topic | Default if no input |
|---|---|---|
| 1 | UUID-format for `OSRS-LobbyMods` table — match `OSRS-Whitelist` or `OSRS-BanList` keying conventions | Mirror `OSRS-LobbyModerators` shape from the architecture (PK `uuid`, attrs `added_at`, `added_by`, `notes`) even though chat is deferred — same table fits both eventual use cases |
| 2 | Region casing on wire (`"NA-E"` vs `"na-e"`) | Plugin sends `"NA-E"`; backend either accepts both or lowercases server-side before storage |
| 3 | Whether `OSRS-LobbyInvites` `to-uuid-index` GSI is needed for outstanding-invite replay on reconnect | Yes — needed for the replay flow in §5 |
| 4 | World-table data source for §Q3 MeetAt resolution (in-DB enum, env var per region, or hardcoded constants in `backend/core/lobby.py`) | Operator's call. Lambda env vars are cheapest: `WORLD_NH_ARENA_NA=578`, etc. |
| 5 | Whether `is_mod` should ALSO go into shards for the player-lookup page tooltip | Out of scope for the lobby backend. Add as a separate small task on `leaderboard_cache_writer.py` if/when player-lookup needs it. |
| 6 | Whether to add an `xc-plugin-localized-errors` plugin task to render `system/error` codes | Yes — but post-deployment. The plugin currently silently drops errors. |

---

## 10. Acceptance Checklist (tick when ready for plugin-side wire-up)

The plugin will swap `NoOpLobbyService` → `WebSocketLobbyService` as soon as the backend ticks the following:

- [ ] `wss://api.pvp-leaderboard.com/ws/prod` accepts connection with the `$connect` validation chain from architecture Phase 1.
- [ ] `lobby/join` validates per §4 and responds with `lobby/roster` + `lobby/block_list_snapshot` (and 0+ replay `lobby/invite_received` pushes for outstanding invites).
- [ ] `lobby/invite`, `lobby/cancel_invite`, `lobby/accept`, `lobby/decline`, `lobby/confirm`, `lobby/block`, `lobby/unblock`, `lobby/leave` all implemented per §4.
- [ ] `lobby/roster` push includes `styles`, `builds`, `current_mmr_per_bucket`, `rank_idx`, `region`, `is_mod` per §3.
- [ ] `lobby/invite_received`, `lobby/invite_cancelled`, `lobby/fight_proposed`, `lobby/fight_confirmed_by_peer`, `lobby/match_found`, `lobby/session_expired` all push per §5.
- [ ] `lobby/match_found.world` and `lobby/match_found.meeting_place` resolved per §Q3.
- [ ] `OSRS-LobbyMembers` table has `styles` + `builds` + `current_mmr_per_bucket` columns (no `peak_per_bucket`, no `sort_bucket`).
- [ ] `OSRS-LobbyInvites` table has `build` column (no `message`).
- [ ] `OSRS-FightSessions` table has `build` column.
- [ ] `OSRS-LobbyMods` table created and operator-populated with current mods.
- [ ] Per-style `ALLOWED_LOCATIONS_*` env vars set; `ALLOWED_BUILDS` env var set; `LOBBY_MIN_CURRENT_OVERALL_MMR` renamed from peak.
- [ ] `system/error` returns `INVALID_BUILD` as a new stable code (alongside the existing enum).

When the above is green, the plugin-side change is a single-line swap at [`DashboardPanel.createMatchmakingCard`](../src/main/java/com/pvp/leaderboard/ui/DashboardPanel.java).
