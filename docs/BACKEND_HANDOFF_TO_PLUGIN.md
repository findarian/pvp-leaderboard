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
| `lobby/join` | `joinLobby(region, styles, builds, minRankIdx, maxRankIdx)` | `UNKNOWN_REGION` / `INVALID_STYLE` / `INVALID_BUILD` / `INVALID_DISPLAY_RANK` / `SMURF_GUARD` (per-style match-count, mods bypass) |
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

`SMURF_GUARD` semantics changed at `p2-smurf-guard-v2` (2026-05-18): the wire code is unchanged, but it now means "this advertised style has < 20 finished matches on your account". The plugin's localised message-table entry should be updated to reflect the new meaning (e.g. "Need more matches in {style}" instead of "Rank too low to lobby").

---

## 2. ~~Open question — `lobby/join` MMR source~~ — **RESOLVED 2026-05-18**

> **Decision**: option 2 (backend reads from `OSRS-Connections` `$connect` snapshot). Shipped as `p2-mmr-trust-fix`. The plugin's `LobbyService.joinLobby(...)` signature stays as-is — **does NOT carry MMR**.

### What's now live on the backend

`websocket_handler.handle_default` extracts `current_mmr_per_bucket` from the same `OSRS-Connections` row it already reads for `connection_uuid` (the row was written at `$connect` step 9 from `OSRS-MMR-table`), and passes it through as the `connection_mmr_per_bucket` kwarg of `handle_lobby_cmd`. **Any `current_mmr_per_bucket` in the wire payload is ignored entirely.** The trusted MMR is still used by `send_invite`'s `RANK_OUT_OF_RANGE` check (and persisted onto `OSRS-LobbyMembers` for that downstream read), but it no longer gates `lobby/join` itself — see §2a.

### Plugin wire-format implication

Don't include `current_mmr_per_bucket` in the `lobby/join` payload — it's silently ignored. Per-cmd field reference table in §4 below is the authoritative list.

---

## 2a. SMURF_GUARD v2 — match-count gate, mods bypass — **SHIPPED 2026-05-18**

> Plugin team's request from [`BACKEND_HANDOFF_SMURF_GUARD_V2.md`](./BACKEND_HANDOFF_SMURF_GUARD_V2.md) — **shipped as `p2-smurf-guard-v2`** in the same delivery as `p2-mmr-trust-fix`.

### Behaviour live on the wire

`lobby/join` now gates per advertised style. Each style in `data.styles` must have ≥ 20 finished matches (`wins + losses + ties`) on the player's `OSRS-MMR-table.ratings.<bucket>` row. Mods bypass.

`SMURF_GUARD` is the same wire-level error code — the underlying meaning shifted from "MMR-based" to "match-count-based, per advertised style". Plugin's `LobbyJoinGate.THRESHOLD = 20` is the single source of truth for the number; ping the backend team if it changes.

### Trusted source

Both `current_match_count_per_bucket` and `is_mod` are written onto the `OSRS-Connections` row at `$connect` step 9:

- `current_match_count_per_bucket`: aggregated from `OSRS-MMR-table.ratings.<bucket>.{wins,losses,ties}` (same get_item that already populated `current_mmr_per_bucket`).
- `is_mod`: separate `OSRS-LobbyMods.GetItem` lookup; frozen at `$connect` (mid-session promotions take effect on next reconnect).

The trio (`current_mmr_per_bucket` + `current_match_count_per_bucket` + `is_mod`) is threaded through `handle_default` → `handle_lobby_cmd` → `_handle_join` → `validate_join_payload`. The wire-payload counterparts are ignored — a modified plugin cannot spoof past these gates.

### Regression tests

Backend repo:

- `tests/unit/lambda_code/test_lobby_handler.py::TestLobbyJoinMatchCountGate` — 9 cases mirroring the handoff §3 table (boundary, locked-style mix, mod bypass, fail-closed on missing snapshot, mod bypass overrides fail-closed, etc.).
- `tests/unit/lambda_code/test_lobby_handler.py::TestLobbyJoinMmrRowStorageTrustBoundary` — pins that wire-payload MMR / match-count / is_mod values are all ignored.
- `tests/unit/lambda_code/test_websocket_handler.py::TestConnectHappyPath` — pins that `$connect` step 9 populates `current_match_count_per_bucket` + `is_mod` on the conn row.
- `tests/unit/lambda_code/test_websocket_handler.py::TestDefaultLobbyDispatch` — pins that the dispatcher reads both fields from the conn row and threads them into `handle_lobby_cmd`.

### Live count refresh — DEFERRED

Per the handoff §2.4, the optional `match_handler` post-match hook to bump open conn rows' `current_match_count_per_bucket` was **not shipped** in this delivery. A user playing their 21st match still needs to reconnect (game logout/login) before the gate unlocks. Manual `[Refresh count]` against `/user` shows the new total but the server still rejects until the snapshot is refreshed at the next `$connect`. Acceptable per the handoff — track as a follow-up if it becomes an actual UX paper-cut during beta.

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
| `lobby/join` | `region`, `styles`, `builds`, `min_rank_idx`, `max_rank_idx`, optional `sort_bucket`. **MMR, match-count, and `is_mod` are server-resolved** from the `$connect` snapshot — do not include `current_mmr_per_bucket`, `current_match_count_per_bucket`, or `is_mod` in the payload (all silently ignored per `p2-mmr-trust-fix` + `p2-smurf-guard-v2`). |
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
