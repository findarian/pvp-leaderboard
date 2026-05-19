# Backend Handoff — SMURF_GUARD v2 (match-count gate)

> **From**: plugin team. **To**: backend team (OSRS-MMR / backend/core).
> **Date**: 2026-05-18. **Plugin commit**: uncommitted WIP — request is to land backend changes BEFORE the next plugin push so the wire contract is symmetric on first release.
> **Pair docs**: [`BACKEND_HANDOFF_LOBBY.md`](./BACKEND_HANDOFF_LOBBY.md) (original 2026-05-14 design session), [`BACKEND_HANDOFF_TO_PLUGIN.md §2a`](./BACKEND_HANDOFF_TO_PLUGIN.md) (mirrored copy of this).

---

## TL;DR

Replace the lobby's anti-smurf gate. Drop the MMR threshold check; gate on **per-style match count** instead. Mods bypass.

| Field | Old (v1) | **New (v2)** |
|---|---|---|
| What gates `lobby/join` | `current_overall_mmr < LOBBY_MIN_CURRENT_OVERALL_MMR` | per-advertised-style `wins + losses + ties < 20` |
| Trusted source | `OSRS-Connections.current_mmr_per_bucket` (`$connect` step 9 snapshot) | `OSRS-Connections.current_match_count_per_bucket` (same step, new field) |
| Mod bypass | n/a | yes — UUIDs in `OSRS-LobbyMods` skip the check |
| Error code | `SMURF_GUARD` | `SMURF_GUARD` (unchanged — wire-level code is the same; only its underlying meaning changes) |

---

## 1. Why

The MMR threshold was a weak smurf signal — a level-3 brand-new account can hit non-zero MMR after one win and bypass the gate. Match count is the actual smurf signal: you can't fake having played 20 fights. Per-style enforcement also matches the plugin UX where users advertise specific styles; a level-3 with 50 Multi matches but 0 NH matches shouldn't be able to queue NH.

Plugin-side UX is already shipped: pre-lobby gate shows the user their per-style counts ("NH 18/20 — 2 more needed"), locks the style toggle below threshold, manual `[Refresh count]` button + hourly auto-refresh. See [`LobbyJoinGate`](../src/main/java/com/pvp/leaderboard/lobby/LobbyJoinGate.java) + [`UserProfileLobbyJoinGate`](../src/main/java/com/pvp/leaderboard/lobby/UserProfileLobbyJoinGate.java). The local gate is **UX only** — your server check is the authoritative one. The plugin doesn't ship the count over the wire because it's spoof-resistant only when trusted.

---

## 2. Concrete backend changes

### 2.1 `backend/core/lobby.py::validate_join_payload`

Drop the `current_overall_mmr: int | float | None` parameter and the SMURF_GUARD branch on it. Add two new kwargs:

```python
def validate_join_payload(
    *,
    region: Any,
    styles: Any,
    builds: Any,
    min_rank_idx: Any,
    max_rank_idx: Any,
    connection_match_count_per_bucket: dict[str, int] | None,  # NEW
    is_mod: bool = False,                                       # NEW
) -> tuple[str, frozenset[str], frozenset[str], int, int]:
```

Replace the MMR SMURF_GUARD block:

```python
# OLD — delete
if current_overall_mmr is None or current_overall_mmr < LOBBY_MIN_CURRENT_OVERALL_MMR:
    raise SmurfGuardError(
        f"current overall MMR {current_overall_mmr!r} < "
        f"{LOBBY_MIN_CURRENT_OVERALL_MMR}"
    )
```

with:

```python
# NEW — per-advertised-style match-count check, mods bypass
LOBBY_MIN_MATCH_COUNT_PER_STYLE = 20  # mirror to plugin LobbyJoinGate.THRESHOLD

if not is_mod:
    if connection_match_count_per_bucket is None:
        # Fail-closed: the $connect snapshot writer is supposed to have
        # populated this; if it didn't, we'd rather block the join than
        # let a smurf through silently. Matches the existing MMR
        # defense-in-depth pattern in p2-mmr-trust-fix.
        raise SmurfGuardError(
            "connection_match_count_per_bucket missing — $connect snapshot "
            "did not populate; fail-closed per anti-smurf policy"
        )
    for style in style_set:
        count = int(connection_match_count_per_bucket.get(style, 0) or 0)
        if count < LOBBY_MIN_MATCH_COUNT_PER_STYLE:
            raise SmurfGuardError(
                f"insufficient matches in {style}: {count} < "
                f"{LOBBY_MIN_MATCH_COUNT_PER_STYLE}"
            )
```

`LOBBY_MIN_CURRENT_OVERALL_MMR` can stay defined (we may want it back later for a different feature) but is no longer referenced by lobby join. Optional cleanup: delete it + its env var if nothing else uses it.

### 2.2 `OSRS-MMR/lambda_code/websocket_handler.py::handle_default` (lobby branch)

The branch that already reads `connection_uuid` + `current_mmr_per_bucket` from the `OSRS-Connections` row now also reads `current_match_count_per_bucket` + `is_mod`, and passes both as kwargs to `handle_lobby_cmd`:

```python
# Existing read (keep)
conn_row = conn_table.get_item(Key={"connection_id": cid}).get("Item", {})
connection_uuid = conn_row.get("connection_uuid")
# ... existing current_mmr_per_bucket read can stay or be removed; lobby no longer uses it

# NEW
match_count_raw = conn_row.get("current_match_count_per_bucket") or {}
connection_match_count_per_bucket = {
    str(k): int(v) for k, v in match_count_raw.items()
    if isinstance(k, str) and isinstance(v, (int, float, Decimal))
}
is_mod = bool(conn_row.get("is_mod", False))

# Existing call (extend with new kwargs)
handle_lobby_cmd(
    ...,
    connection_match_count_per_bucket=connection_match_count_per_bucket,
    is_mod=is_mod,
)
```

`handle_lobby_cmd` threads the two kwargs into `_handle_join`, which forwards them to `validate_join_payload`. Same pattern as the existing `connection_mmr_per_bucket` kwarg in `p2-mmr-trust-fix`.

### 2.3 `$connect` snapshot writer (the same step that writes `current_mmr_per_bucket`)

The existing step 9 of `$connect` reads the player's `OSRS-MMR-table` row and writes `current_mmr_per_bucket` onto `OSRS-Connections`. Same step now also writes:

```python
# Source: the same OSRS-MMR-table row's cumulative stats. The /user
# endpoint already aggregates this into ratings.<bucket>.{wins,losses,ties};
# read those fields directly to avoid double-querying.
ratings_map = mmr_row.get("ratings") or {}
match_count_per_bucket = {}
for bucket in ("nh", "veng", "multi", "dmm"):
    bkt = ratings_map.get(bucket) or {}
    wins = int(bkt.get("wins", 0) or 0)
    losses = int(bkt.get("losses", 0) or 0)
    ties = int(bkt.get("ties", 0) or 0)
    match_count_per_bucket[bucket] = wins + losses + ties

# Also: is_mod from OSRS-LobbyMods
is_mod_row = lobby_mods_table.get_item(Key={"uuid": uuid}).get("Item")
is_mod = is_mod_row is not None

# Write onto OSRS-Connections alongside the existing fields
conn_table.update_item(
    Key={"connection_id": cid},
    UpdateExpression="SET current_match_count_per_bucket = :mc, is_mod = :im",
    ExpressionAttributeValues={":mc": match_count_per_bucket, ":im": is_mod},
)
```

The `is_mod` write is also a write-once-at-$connect — same trust model as MMR. If a UUID is added to `OSRS-LobbyMods` mid-session, the new mod status doesn't take effect until the next `$connect` (i.e. socket reconnect). Acceptable since mods are stable (< 10 total per the plugin team's earlier answer).

### 2.4 Match-recorder hook for live count refresh

Recommended but not blocking: when `match_handler.py` persists a finished match into `OSRS-MMR-table` (increments `ratings.<bucket>.{wins,losses}`), also update the participants' open `OSRS-Connections` rows' `current_match_count_per_bucket[bucket]`. Without this, the gate count goes stale until the next `$connect` (which is typically the next game logout/login). The plugin's `[Refresh count]` button hits `GET /user` which is always live, so the user can manually bump anytime — but live wire-side updates would mean the auto-bumped 21st match unlocks `lobby/join` without a relog.

**Edge case if you skip 2.4**: a user with 19 nh matches plays one more, opens the plugin, hits `[Refresh count]`, sees "NH: 20/20 ✓", clicks Go-to-lobby — server rejects with SMURF_GUARD because `connection_match_count_per_bucket["nh"]` is still 19. Workaround for the user: log out and back in. Annoying but recoverable. Land the 2.4 hook in a follow-up PR if it's not trivial.

---

## 3. Regression tests (mirror of `TestLobbyJoinMmrTrustBoundary`)

New class `TestLobbyJoinMatchCountGate` in `tests/unit/lambda_code/test_lobby_handler.py`. Pin the contract:

| Test | Input | Expected |
|---|---|---|
| `test_below_threshold_per_style_fires_smurf_guard` | `styles=[nh]`, count `{nh:18, veng:0, multi:22, dmm:22}`, `is_mod=False` | `SMURF_GUARD` |
| `test_at_threshold_admits` | `styles=[nh]`, count `{nh:20, ...}` | join succeeds (boundary value — 20 is the minimum that passes) |
| `test_unlocked_styles_admit` | `styles=[multi]`, count `{nh:0, veng:0, multi:22, dmm:0}` | join succeeds |
| `test_mixed_locked_and_unlocked_fires_if_any_locked` | `styles=[nh, multi]`, count `{nh:18, multi:22, …}` | `SMURF_GUARD` (any locked style in the set blocks the join) |
| `test_mod_bypasses_gate_zero_matches` | `is_mod=True`, count `{nh:0, veng:0, multi:0, dmm:0}`, `styles=[nh]` | join succeeds |
| `test_missing_connection_dict_fails_closed` | `connection_match_count_per_bucket=None`, `is_mod=False` | `SMURF_GUARD` |
| `test_missing_connection_dict_with_mod_still_admits` | `connection_match_count_per_bucket=None`, `is_mod=True` | join succeeds (mod bypass runs BEFORE the None check) |
| `test_member_row_persists_validated_styles_only` | `styles=[nh, veng]`, count `{nh:20, veng:20, …}` | `put_item` stores both — confirms the validated `style_set` is what hits the row, not the wire payload |
| `test_negative_count_treated_as_zero` | count `{nh: -5}` (defensive — shouldn't happen but) | `SMURF_GUARD` (the `int(...) or 0` coercion + `< 20` check treats negative as below threshold) |

Plus an integration-flavoured one if the existing test infrastructure supports it:

| `test_connection_snapshot_writer_populates_match_count_per_bucket` | A simulated `$connect` with a player whose `OSRS-MMR-table` row has `ratings={"nh": {"wins": 5, "losses": 10, "ties": 2}, ...}` | Resulting `OSRS-Connections` row has `current_match_count_per_bucket["nh"] == 17` |

---

## 4. Field reference update for `BACKEND_HANDOFF_TO_PLUGIN.md §4`

After landing, update the §4 wire-field table:

```
| `lobby/join` | `region`, `styles`, `builds`, `min_rank_idx`, `max_rank_idx`, optional `sort_bucket`. **MMR and match-count are server-resolved** from the `$connect` snapshot — do not include either in the wire payload (silently ignored per v2 trust boundary). |
```

And in §3 stable error-code list, the `SMURF_GUARD` description shifts from "MMR-based" to "match-count-based, per advertised style".

---

## 5. Plugin-side mirror (already shipped, FYI only)

For the backend team to know what the plugin already does:

- [`LobbyJoinGate`](../src/main/java/com/pvp/leaderboard/lobby/LobbyJoinGate.java) — interface with `THRESHOLD = 20`, `AUTO_REFRESH_INTERVAL_MS = 1h`. If you bump the server's `LOBBY_MIN_MATCH_COUNT_PER_STYLE`, ping the plugin team so we keep these in lockstep — a drift would surface as "client says unlocked, server rejects" or vice versa.
- [`UserProfileLobbyJoinGate`](../src/main/java/com/pvp/leaderboard/lobby/UserProfileLobbyJoinGate.java) — production impl. Reads `cumulative_stats.<bucket>.{wins,losses,ties}` from the existing `GET /user` endpoint (no new endpoint required) every hour + on-demand.
- [`MatchmakingLobbyPanel`](../src/main/java/com/pvp/leaderboard/ui/MatchmakingLobbyPanel.java) — pre-lobby gate renders per-style remaining counts, locks style toggles below threshold, `[Refresh count]` button.
- Mod-bypass UX: the plugin doesn't know if the local user is a mod (server resolves it from `OSRS-LobbyMods` at `$connect`). For mods the local gate still shows "you need 20 matches" but the server admits them anyway — acceptable since (a) mods are < 10 people who already know the system, (b) real mods have far more than 20 matches so the gate doesn't actually apply, (c) we don't want to expose mod status to the plugin (privacy / audit-surface argument). If you want to flip this — add an `is_mod` field to the `lobby/joined` echo push and ping the plugin team.

---

## 6. Sign-off checklist

- [ ] `validate_join_payload` signature updated, MMR check deleted, match-count check added with mod bypass
- [ ] `websocket_handler.handle_default` lobby branch reads `current_match_count_per_bucket` + `is_mod` from `OSRS-Connections` row
- [ ] `$connect` snapshot writer populates `current_match_count_per_bucket` + `is_mod` on the conn row
- [ ] (Recommended) `match_handler` post-match hook bumps `current_match_count_per_bucket` on open conn rows
- [ ] `TestLobbyJoinMatchCountGate` class with 9 tests above
- [ ] [`BACKEND_HANDOFF_TO_PLUGIN.md §3`](./BACKEND_HANDOFF_TO_PLUGIN.md) `SMURF_GUARD` description updated + §4 wire-field table reflects v2
- [ ] Plugin team pinged once deployed so we can flip a known-mod test account from "expected to fail" → "expected to pass" during beta

---

*If this drifts from the plugin reality, ping the plugin chat with the diff. Plugin's `LobbyJoinGate.THRESHOLD` is the single source of truth for the number — mirror it server-side, don't introduce a separate constant that can drift.*
