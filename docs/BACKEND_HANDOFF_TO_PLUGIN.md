# Backend → Plugin Handoff (Phase 2 Lobby)

> **Pair to**: [`BACKEND_HANDOFF_LOBBY.md`](./BACKEND_HANDOFF_LOBBY.md) (the 2026-05-14 design session that drove the backend implementation). This document is the symmetric direction — backend is now done, plugin team takes over.
>
> **Status**: `7f58614` (`p2-handler-lobby`) and `f5bbe41` (`p2-docs`) pushed to `main` on 2026-05-18. Phase 2 backend is **code-complete**. Remaining backend item is `p2-beta` (joint validation against the deployed system per [`BACKEND_HANDOFF_LOBBY.md` §10](./BACKEND_HANDOFF_LOBBY.md)) — that gates on plugin wiring.

---

## 1. What is now live on the wire

Once CI for `f5bbe41` finishes applying the Terraform (Lambda zip-source change → cold-start picks up the two new modules), every cmd below round-trips against `wss://api.pvp-leaderboard.com/prod`.

### Client → server (9 cmds)

| Wire cmd | `LobbyService` method | Backend validation |
|---|---|---|
| `lobby/join` | `joinLobby(region, styles, builds, minRankIdx, maxRankIdx)` | `UNKNOWN_REGION` / `INVALID_STYLE` / `INVALID_BUILD` / `INVALID_DISPLAY_RANK` / `SMURF_GUARD` |
| `lobby/leave` | `leaveLobby()` | (idempotent) |
| `lobby/invite` | `sendInvite(opponent, style, build, location)` | `PEER_NOT_IN_LOBBY` / `INVALID_STYLE` / `INVALID_BUILD` / `INVALID_LOCATION` / `RANK_OUT_OF_RANGE` / `BLOCKED` / `DUPLICATE_INVITE` |
| `lobby/cancel_invite` | `cancelInvite(opponent)` | (idempotent) |
| `lobby/accept` | `acceptInvite(invite)` | `INVITE_EXPIRED` / `BLOCKED` (caller is not recipient) |
| `lobby/decline` | `declineInvite(invite)` | (idempotent) |
| `lobby/confirm` | `confirmFight()` | `FIGHT_SESSION_EXPIRED` / `BLOCKED` (caller is not participant) |
| `lobby/block` | `block(member)` | `BLOCKED` (self-block) |
| `lobby/unblock` | `unblock(member)` | (idempotent) |

### Server → client (11 pushes)

| Wire cmd | `LobbyEventListener` callback | Notes |
|---|---|---|
| `lobby/joined` | **NEW** — needs callback or swallow | Echo ack of own `lobby/join`. Carries `echo_at_epoch_ms` for clock-drift debug. Can ignore. |
| `lobby/roster` | `onRosterSnapshot(roster)` | Full member list with per-row `rank_idx` + `is_mod` enrichment. Fires on join + after every roster mutation by anyone. **Special case**: when `data.members == null && data.stale == true`, that's the leave-cascade fast-path — re-issue `lobby/join` on next opportunity. |
| `lobby/block_list_snapshot` | **NEW** — needs callback | Fires once on join. Payload: `{"blocked_uuids": [...]}`. Use to gray-out the rows the local user has blocked (server doesn't filter these — that would let the user verify someone is online by toggling block on/off). |
| `lobby/block_added` | **NEW** — optional | Server confirmation that `lobby/block` landed. The local UI can update from its own state after `block()` returns; this push is for cross-device sync. |
| `lobby/block_removed` | **NEW** — optional | Same for `lobby/unblock`. |
| `lobby/invite_received` | `onIncomingInvite(invite)` | Carries sender enrichment (`sender_name` / `sender_region` / `sender_styles` / `sender_builds`) so the plugin renders the invite card without an extra read. **Reconnect replay**: on `lobby/join` the server re-fires this for every non-expired pending invite where the joiner is the receiver. |
| `lobby/invite_cancelled` | `onIncomingInviteCancelled(inviteId)` | Fires for: (a) sender cancelled, (b) receiver declined (push goes to sender), (c) block-cascade (`reason="blocked"`), (d) leave-cascade. The `inviteId` is the only stable correlation key — match on that, not on participant UUIDs. |
| `lobby/fight_proposed` | `onFightProposed(session)` | Both parties get this simultaneously. Carries `expires_at_epoch_ms` for the 30s confirm-window countdown. |
| `lobby/fight_confirmed_by_peer` | `onFightConfirmedByPeer(fightSessionId)` | Asymmetric — only the OTHER party gets this on a first-confirm. The confirmer doesn't get an echo. |
| `lobby/match_found` | `onMatchFound(match)` | Both parties. Carries server-resolved `world` (e.g. `"W578"`) + `meeting_place` (e.g. `"Arena"`) — render verbatim, no client-side world picking. **Server has already deleted both `OSRS-LobbyMembers` rows + the session row at this point.** |
| `lobby/session_expired` | `onFightSessionExpired(fightSessionId)` | Fires when 30s confirm window elapsed with only one confirm, OR session was cancelled by a block (`reason="blocked"`). Both paths return both players to the lobby with no penalty. |

### `error/lobby` — typed-error envelope

Every backend rejection arrives as:

```json
{"cmd": "error/lobby", "data": {"code": "<ErrorCode>", "message": "<debug text>", "cmd": "<originating client cmd>"}}
```

The `code` field is one of the constants in [`backend/core/socket_protocol.py` ErrorCode](../../DevSecOpsWebsite/backend/core/socket_protocol.py). Plugin's localized error-message table should match on `code` only — `message` is debug text (English, may change without notice). The `cmd` field is the originating client cmd, useful for the plugin's router-table to correlate with the in-flight cmd.

Maps to `LobbyEventListener.onError(code, message)`.

**Stable code list** (current as of 2026-05-18):

```
UNKNOWN_REGION             INVALID_STYLE          INVALID_BUILD
INVALID_LOCATION           INVALID_DISPLAY_RANK   SMURF_GUARD
PEER_NOT_IN_LOBBY          RANK_OUT_OF_RANGE      BLOCKED
DUPLICATE_INVITE           INVITE_EXPIRED         FIGHT_SESSION_EXPIRED
INVALID_MESSAGE            UNKNOWN_COMMAND
```

---

## 2a. SMURF_GUARD v2 — match-count gate (plugin-side shipped 2026-05-18, backend side **TODO**)

> **Dedicated handoff doc**: [`BACKEND_HANDOFF_SMURF_GUARD_V2.md`](./BACKEND_HANDOFF_SMURF_GUARD_V2.md) — concrete code diffs, test specs, and the 6-item sign-off checklist for the backend team. The summary below mirrors that doc's TL;DR.
>
> **Decision** (user, 2026-05-18): the lobby's anti-smurf gate is no longer MMR-based — it's a **per-style match-count** gate. Players must have **≥ 20 `wins + losses + ties` in a style** to advertise that style. The legacy `current_overall_mmr < LOBBY_MIN_CURRENT_OVERALL_MMR` check is dropped entirely.

### Plugin side (shipped this commit)

The plugin owns a {@link com.pvp.leaderboard.lobby.LobbyJoinGate} that fetches `cumulative_stats.{nh,veng,multi,dmm}.{wins,losses,ties}` from the existing `GET /user` endpoint once on `GameState.LOGGED_IN` and every hour thereafter (manual refresh button also available). Style toggles in the pre-lobby gate render disabled with a tooltip + "NH: 17 more needed" status line below; Go-to-lobby is blocked until every selected style is unlocked. Production impl: `UserProfileLobbyJoinGate`; tests use `NoOpLobbyJoinGate`.

The plugin gate is **UX only** — it gives the user immediate feedback without round-tripping `lobby/join` for every gate-fail. The backend still has the final word.

### Backend side (TODO — required before plugin can claim release-ready)

1. **`validate_join_payload` in `backend/core/lobby.py`** — drop the `current_overall_mmr` parameter + `SMURF_GUARD` branch on it. Replace with a per-style check against a new `connection_match_count_per_bucket: dict[str, int]` kwarg. For each style in the (already-validated) `style_set`:
   - If `connection_match_count_per_bucket.get(style, 0) < 20`, raise `SmurfGuardError(f"insufficient matches in {style}: ... < 20")`.
   - Mods (UUIDs in `OSRS-LobbyMods`) bypass the check entirely. Pass an `is_mod: bool` kwarg sourced the same way the existing `lobby/roster` enrichment does.

2. **`websocket_handler.handle_default`** (the `lobby/*` branch) — replace the `connection_mmr_per_bucket` read from `OSRS-Connections` with `connection_match_count_per_bucket`. The `$connect` snapshot step needs to be updated to write the trusted match-count dict in addition to (or in place of) the MMR dict.

3. **`$connect` snapshot writer** (the existing step 9 that reads `OSRS-MMR-table` and writes the trusted MMR dict onto the `OSRS-Connections` row) — also read the same row's `total_wins` + `total_losses` + `total_ties` (or the per-bucket `ratings.<bucket>.{wins,losses,ties}` fields the `/user` endpoint already returns) and write `current_match_count_per_bucket: {nh: int, veng: int, multi: int, dmm: int}` onto the same row.

4. **Regression tests** mirroring the existing `TestLobbyJoinMmrTrustBoundary` class — pin the contract:
   - `test_below_threshold_per_style_fires_smurf_guard` — user with `{nh: 18, veng: 0, multi: 22, dmm: 22}` joining with `styles=[nh]` → `SMURF_GUARD`.
   - `test_unlocked_styles_admit` — same user joining with `styles=[multi]` → join succeeds.
   - `test_mixed_locked_and_unlocked_still_fires_if_any_locked` — `styles=[nh, multi]` → `SMURF_GUARD` because nh is locked.
   - `test_mod_bypasses_gate` — `is_mod=True` + zero matches everywhere → join succeeds.
   - `test_missing_connection_dict_fails_closed` — `connection_match_count_per_bucket=None` → `SMURF_GUARD` (matches the existing fail-closed defense-in-depth pattern for the MMR case).

5. **Stable error codes** — `SMURF_GUARD` keeps its name; the underlying meaning changes but the wire-level code is unchanged so the plugin's localized error-message table doesn't need an update.

### Plugin → backend contract until backend ships

Until the backend lands the v2 logic, the existing `current_overall_mmr` SMURF_GUARD still fires server-side. The plugin's local 20-match gate is a strict superset of the MMR check (any user with ≥ 20 matches in a style trivially has non-zero MMR there), so users who pass the local gate will also pass the legacy server check. No release-blocker on the backend transition.

---

## 2b. ~~Open question — `lobby/join` MMR source~~ — **RESOLVED 2026-05-18**

> **Decision**: option 2 (backend reads from `OSRS-Connections` `$connect` snapshot). Shipped as `p2-mmr-trust-fix`. The plugin's `LobbyService.joinLobby(...)` signature stays as-is — **does NOT carry MMR**.

### What's now live on the backend

`websocket_handler.handle_default` extracts `current_mmr_per_bucket` from the same `OSRS-Connections` row it already reads for `connection_uuid` (the row was written at `$connect` step 9 from `OSRS-MMR-table`), and passes it through as the new `connection_mmr_per_bucket` kwarg of `handle_lobby_cmd`. `lobby_handler._handle_join` uses that trusted value as the SOLE input to `validate_join_payload`'s `SMURF_GUARD` check. **Any `current_mmr_per_bucket` in the wire payload is ignored entirely.**

Regression tests pin the contract (`tests/unit/lambda_code/test_lobby_handler.py::TestLobbyJoinMmrTrustBoundary`):

- `test_spoofed_high_wire_mmr_does_not_bypass_smurf_guard` — wire `{overall: 9999}` + trusted `{overall: 800}` → SMURF_GUARD fires.
- `test_wire_mmr_absent_with_trusted_high_is_admitted` — no wire MMR + trusted `{overall: 1500}` → join succeeds, `put_item` carries the trusted value.
- `test_member_row_persists_only_trusted_mmr_not_wire_value` — wire `{overall: 9999}` + trusted `{overall: 1200}` → join succeeds, stored row carries `1200`, never `9999`.
- `test_no_trusted_mmr_at_all_emits_smurf_guard` — defense-in-depth: if the conn_row somehow lacks the MMR field (e.g. DDB outage during `$connect` step 9), join fails closed with SMURF_GUARD, never silently bypasses.

### Plugin wire-format implication

Don't include `current_mmr_per_bucket` in the `lobby/join` payload — it's silently ignored. The full `data` keys for `lobby/join` are now exactly:

```
region, styles, builds, min_rank_idx, max_rank_idx, sort_bucket  (optional)
```

Per-cmd field reference table in §4 below is the authoritative list.

---

## 3. Plugin TODO — what's left to wire

Tracking against the existing plugin progress doc convention. None of these block on backend changes (modulo the MMR question above).

1. **`WebSocketLobbyService` implementing `LobbyService`** — fire-and-forget cmd dispatcher over the existing Phase 1 socket. Each method maps to one `lobby/*` envelope per the table in §1.
2. **Push-event handlers** — for the 8 existing `LobbyEventListener` callbacks.
3. **4 new listener methods** (or have `WebSocketLobbyService` swallow them silently) — `lobby/joined`, `lobby/block_list_snapshot`, `lobby/block_added`, `lobby/block_removed`. The block_list_snapshot one is functionally required (drives the gray-out feature); the other three are nice-to-haves for cross-device sync.
4. **`error/lobby` router-table** — every code in the stable list above needs a localized message string. Don't pattern-match on `message`.
5. **Reconnect handling** — on socket reconnect, re-issue `lobby/join`. Server replays outstanding invites automatically. No client-side persistence required.
6. **`DashboardPanel.createMatchmakingCard` swap** — `NoOpLobbyService` → `WebSocketLobbyService`. This is the last wire that flips Phase 2 live.

---

## 4. Trust boundary — uuid is server-resolved

The plugin **does not** include its UUID in any `lobby/*` payload. The backend re-reads the trusted UUID from the `OSRS-Connections` row written at `$connect` step 11 (the canonical-resolution step) and uses that for every cmd. Spoofing a different `from_uuid` in the wire would be ignored (and would fail validation anyway since the receiver is keyed on `to_uuid` only).

Specifically: don't include `from_uuid` in `lobby/invite` payloads. The fields each cmd actually uses are:

| Cmd | Wire `data` keys |
|---|---|
| `lobby/join` | `region`, `styles`, `builds`, `min_rank_idx`, `max_rank_idx`, optional `sort_bucket`. **MMR is server-resolved** from the `$connect` snapshot — do not include `current_mmr_per_bucket` (silently ignored per `p2-mmr-trust-fix`). |
| `lobby/leave` | (none — empty `{}` is fine) |
| `lobby/invite` | `to_uuid`, `style`, `build`, `location` |
| `lobby/cancel_invite` | `to_uuid` |
| `lobby/accept` | `invite_id` |
| `lobby/decline` | `invite_id` |
| `lobby/confirm` | `fight_session_id` |
| `lobby/block` | `blocked_uuid` |
| `lobby/unblock` | `blocked_uuid` |

---

## 5. Reference docs

| Doc | What it covers | Repo |
|---|---|---|
| [`OSRS-MMR/docs/LOBBY_SYSTEM.md`](../../DevSecOpsWebsite/OSRS-MMR/docs/LOBBY_SYSTEM.md) | Narrative — state machine, validation rules, MeetAt resolution, block visibility asymmetry, anti-smurf rationale, mod operator runbook, reconnect replay | DevSecOpsWebsite |
| [`OSRS-MMR/lambda_code/docs/WEBSOCKET_PROTOCOL.md`](../../DevSecOpsWebsite/OSRS-MMR/lambda_code/docs/WEBSOCKET_PROTOCOL.md) | Byte-level wire spec — envelope shape, size limits, allowed cmds | DevSecOpsWebsite |
| [`OSRS-MMR/docs/SOCKET_LOBBY_ARCHITECTURE.md`](../../DevSecOpsWebsite/OSRS-MMR/docs/SOCKET_LOBBY_ARCHITECTURE.md) | Original architecture — overridden by `BACKEND_HANDOFF_LOBBY.md` where they disagree | DevSecOpsWebsite |
| [`backend/core/lobby.py`](../../DevSecOpsWebsite/backend/core/lobby.py) | Source of truth for validation logic + error subclasses | DevSecOpsWebsite |
| [`backend/core/socket_protocol.py`](../../DevSecOpsWebsite/backend/core/socket_protocol.py) | `ErrorCode` enum + `Envelope` parsing | DevSecOpsWebsite |
| [`OSRS-MMR/lambda_code/lobby_handler.py`](../../DevSecOpsWebsite/OSRS-MMR/lambda_code/lobby_handler.py) | Cmd router — exact cmd→push mapping + payload shapes | DevSecOpsWebsite |
| [`tests/unit/lambda_code/test_lobby_handler.py`](../../DevSecOpsWebsite/tests/unit/lambda_code/test_lobby_handler.py) | 26 tests pinning the wire-side contract — useful as living examples of payload shapes | DevSecOpsWebsite |
| [`docs/BACKEND_HANDOFF_LOBBY.md`](./BACKEND_HANDOFF_LOBBY.md) | Original 2026-05-14 design session — design rationale for every field | this repo |

---

## 6. `p2-beta` joint validation checklist

Once the plugin's `WebSocketLobbyService` is wired, run through [`BACKEND_HANDOFF_LOBBY.md` §10](./BACKEND_HANDOFF_LOBBY.md)'s acceptance checklist with both repos pointing at production. Backend metrics to watch during beta:

- CloudWatch log line `lobby_handler: UNHANDLED` → bug or DDB outage; investigate
- CloudWatch log line `lobby_handler: roster_build_error viewer=...` → one peer's roster broadcast failed but others may have succeeded; review
- DDB GetItem/PutItem counts on the 5 lobby tables — should match expected per-cmd ratios
- API Gateway `5xx` count on the `$default` route — should be 0; any non-zero is a Lambda error

Tag any bug reports with `p2-beta` so we can split them between repos.

---

*Authored: 2026-05-18 by the backend agent immediately after `f5bbe41` landed on `main`. If you find this document drifting from reality, ping the backend repo with the diff.*
