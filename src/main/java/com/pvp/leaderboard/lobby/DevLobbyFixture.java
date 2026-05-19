package com.pvp.leaderboard.lobby;

import com.pvp.leaderboard.util.RankUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Mock {@link LobbyService} implementation used during the pre-backend
 * window. Generates a deterministic 50-player roster, schedules mock-
 * "opponent accepted" / "opponent confirmed" timers, and pushes the
 * resulting events to the registered {@link LobbyEventListener}.
 *
 * <p>Extracted from {@code MatchmakingLobbyPanel} as part of
 * {@code p1-plugin-mock-refactor}. Every constant + helper here used to
 * live as a {@code MOCK_*}, {@code seedMockRoster()}, {@code rollBuilds()},
 * {@code seedMockInvites()}, or inner {@code Timer} on the panel. The
 * panel now reads through this seam, so swapping the fixture for the real
 * {@code WebSocketLobbyService} at {@code p2-plugin-service} is a one-line
 * wire change.
 *
 * <p><b>Deterministic seed.</b> Default seed is {@code 0xC0FFEEL} (same as
 * the legacy panel) so the same roster appears on every launch and visual
 * regressions are easy to spot. Override via the test-only constructor.
 *
 * <p><b>Threading.</b> All listener callbacks are invoked on the calling
 * thread (when fired in response to a command) or via the
 * {@link MockScheduler} (when fired by a delay-timer). The default
 * scheduler is {@link SwingTimerScheduler} so timer callbacks land on the
 * EDT — same thread the panel reads on. Tests inject a synchronous
 * scheduler so delays can be advanced deterministically.
 *
 * <p><b>Bounded mutability.</b> Listener events fire on read paths only;
 * the fixture mutates its own state under any thread that calls in, but
 * because the panel always calls from the EDT and timer callbacks always
 * land on the EDT (production-mock-mode) or the test thread (tests),
 * there is no concurrent-mutation hazard in the supported usage shapes.
 */
public final class DevLobbyFixture implements LobbyService
{
    /** Default mock-auto-accept delay — how long after the local user sends
     *  an invite the fake opponent "accepts" it. Was
     *  {@code MatchmakingLobbyPanel.MOCK_AUTO_ACCEPT_DELAY_MS}. */
    public static final long DEFAULT_AUTO_ACCEPT_DELAY_MS = 5_000L;

    /** Default mock-peer-confirm delay — how long after a {@link FightSession}
     *  begins the fake opponent "confirms" it. Was
     *  {@code MatchmakingLobbyPanel.MOCK_AUTO_CONFIRM_DELAY_MS}. */
    public static final long DEFAULT_AUTO_CONFIRM_DELAY_MS = 6_000L;

    /** 10-minute outgoing-invite TTL. Mirrors the server-side
     *  {@code LOBBY_INVITE_TTL_SEC=600} env var (architecture § Phase 2). */
    public static final long FIGHT_INVITE_TTL_MS = 10L * 60L * 1000L;

    /** 30-second mutual-confirm window. Mirrors server-side
     *  {@code LOBBY_FIGHT_CONFIRM_TTL_SEC=30}. */
    public static final long FIGHT_CONFIRM_WINDOW_MS = 30L * 1000L;

    /** Stable seed for reproducible roster generation. */
    public static final long DEFAULT_SEED = 0xC0FFEEL;

    /** Hard cap on player-name length — matches the panel's design spec.
     *  Mock-roster generation asserts every seed name is at most this long
     *  so wide-character stress entries surface real horizontal-clipping
     *  regressions. */
    public static final int MAX_NAME_LENGTH = 12;

    /** Region short codes used both as the gate dropdown values and as
     *  {@link LobbyMember#getRegion}. Keep order stable. */
    private static final String[] REGION_CODES =
        {"NA-E", "NA-W", "EU", "BR", "OCE", "Other"};

    private final MockScheduler scheduler;
    private final long seed;
    private final long autoAcceptDelayMs;
    private final long autoConfirmDelayMs;

    private LobbyEventListener listener;
    private boolean started;

    /** Cached deterministic roster — generated once on first {@link #joinLobby}. */
    private List<LobbyMember> roster;

    /** Pending sent invites keyed by opponent {@code player_id}. One per
     *  opponent. */
    private final Map<String, PendingOutgoing> outgoing = new HashMap<>();

    /** Pending received invites keyed by invite id. The fixture owns these
     *  so {@link #acceptInvite}/{@link #declineInvite} can verify the invite
     *  is one we pushed. */
    private final Map<String, IncomingInvite> incoming = new HashMap<>();

    /** Currently-active mutual-confirm session, if any. */
    private PendingSession session;

    /** Production-mock constructor — wires {@link SwingTimerScheduler} and
     *  default 5-s / 6-s delays. */
    public DevLobbyFixture()
    {
        this(new SwingTimerScheduler(), DEFAULT_SEED,
            DEFAULT_AUTO_ACCEPT_DELAY_MS, DEFAULT_AUTO_CONFIRM_DELAY_MS);
    }

    /** Test-only constructor — inject a {@code ManualScheduler} and short
     *  delays so unit tests can advance time deterministically. */
    public DevLobbyFixture(MockScheduler scheduler, long seed,
                           long autoAcceptDelayMs, long autoConfirmDelayMs)
    {
        this.scheduler = scheduler;
        this.seed = seed;
        this.autoAcceptDelayMs = autoAcceptDelayMs;
        this.autoConfirmDelayMs = autoConfirmDelayMs;
    }

    @Override
    public void setListener(LobbyEventListener listener)
    {
        this.listener = listener;
    }

    @Override
    public void start()
    {
        if (!started) started = true;
    }

    @Override
    public void stop()
    {
        if (started)
        {
            cancelAllTimers();
            started = false;
        }
    }

    @Override
    public void joinLobby(String region, Set<Style> styles, Set<BuildType> builds,
                          int minDisplayRankIdx, int maxDisplayRankIdx)
    {
        if (roster == null) roster = seedRoster();
        fireOnRosterSnapshot(roster);
        // Seed 2 demonstration incoming invites so the receiver-side UI is
        // immediately reviewable without waiting for a real socket push.
        // Idempotent — only seed if there are no live incoming invites yet
        // (so a rejoin doesn't pile on more cards).
        if (incoming.isEmpty() && roster.size() >= 7)
        {
            LobbyMember a = roster.get(0);
            LobbyMember b = roster.get(Math.min(roster.size() - 1, 6));
            IncomingInvite inv1 = newIncoming(a, Style.NH,
                firstAdvertisedBuild(a, BuildType.MAIN), "Arena");
            IncomingInvite inv2 = newIncoming(b, Style.VENG,
                firstAdvertisedBuild(b, BuildType.PURE), "Wildy");
            incoming.put(inv1.inviteId, inv1);
            incoming.put(inv2.inviteId, inv2);
            fireOnIncomingInvite(inv1);
            fireOnIncomingInvite(inv2);
        }
    }

    @Override
    public void leaveLobby()
    {
        cancelAllTimers();
        roster = null;
        incoming.clear();
        outgoing.clear();
        session = null;
        fireOnRosterSnapshot(Collections.emptyList());
    }

    @Override
    public void sendInvite(LobbyMember opponent, Style style, BuildType build, String location)
    {
        if (opponent == null || opponent.playerId == null) return;
        if (outgoing.containsKey(opponent.playerId)) return; // idempotent
        long now = System.currentTimeMillis();
        final OutgoingInvite oi = new OutgoingInvite(
            genId("oi"), opponent, style, build, location,
            now, now + FIGHT_INVITE_TTL_MS);
        MockScheduler.Cancellable handle = scheduler.schedule(
            () -> mockOpponentAccepts(oi), autoAcceptDelayMs);
        outgoing.put(opponent.playerId, new PendingOutgoing(oi, handle));
    }

    @Override
    public void cancelInvite(LobbyMember opponent)
    {
        if (opponent == null || opponent.playerId == null) return;
        PendingOutgoing po = outgoing.remove(opponent.playerId);
        if (po != null) po.cancellable.cancel();
    }

    @Override
    public void acceptInvite(IncomingInvite invite)
    {
        if (invite == null || invite.inviteId == null) return;
        IncomingInvite stored = incoming.remove(invite.inviteId);
        if (stored == null)
        {
            fireOnError("UNKNOWN_INVITE", "no such pending invite: " + invite.inviteId);
            return;
        }
        beginSession(stored.sender, stored.style, stored.build, stored.location);
    }

    @Override
    public void declineInvite(IncomingInvite invite)
    {
        if (invite == null || invite.inviteId == null) return;
        incoming.remove(invite.inviteId);
    }

    @Override
    public void confirmFight()
    {
        if (session == null)
        {
            fireOnError("NO_ACTIVE_SESSION", "no active fight session to confirm");
            return;
        }
        session.iConfirmed = true;
        if (session.peerConfirmed)
        {
            transitionToMatchFound();
        }
        // else: panel will sit in "Waiting on other player" until the
        // mock-peer-confirm timer lands and flips peerConfirmed=true.
    }

    @Override
    public void block(LobbyMember member)
    {
        if (member == null || member.playerId == null) return;
        cancelInvite(member);
        if (session != null
            && member.playerId.equals(session.session.opponent.playerId))
        {
            cancelSessionTimers();
            session = null;
        }
    }

    @Override
    public void unblock(LobbyMember member)
    {
        // No-op in mock; the architecture's mutual-hide rule is server-enforced.
    }

    // -------------------- internal mock state transitions --------------------

    /** Mock-auto-accept callback. Fires {@link #autoAcceptDelayMs} after the
     *  local user calls {@link #sendInvite}. */
    private void mockOpponentAccepts(OutgoingInvite oi)
    {
        PendingOutgoing po = outgoing.remove(oi.opponent.playerId);
        if (po == null) return; // was cancelled
        beginSession(oi.opponent, oi.style, oi.build, oi.location);
    }

    private void beginSession(LobbyMember opp, Style style, BuildType build, String location)
    {
        long now = System.currentTimeMillis();
        FightSession fs = new FightSession(
            genId("fs"), opp, style, build, location,
            now + FIGHT_CONFIRM_WINDOW_MS);
        // Cancel any prior session (shouldn't happen in normal flow, but
        // safe-guard against double-acceptance edge cases).
        if (session != null) cancelSessionTimers();
        session = new PendingSession(fs);
        session.peerConfirmTimer = scheduler.schedule(this::mockPeerConfirms, autoConfirmDelayMs);
        session.expiryTimer = scheduler.schedule(this::mockSessionExpired, FIGHT_CONFIRM_WINDOW_MS);
        fireOnFightProposed(fs);
    }

    private void mockPeerConfirms()
    {
        if (session == null) return;
        session.peerConfirmed = true;
        if (session.iConfirmed)
        {
            transitionToMatchFound();
        }
        else
        {
            fireOnFightConfirmedByPeer(session.session.fightSessionId);
        }
    }

    private void mockSessionExpired()
    {
        if (session == null) return;
        // Defensive: if both already confirmed, the transition has already
        // fired onMatchFound and cleared the session. Nothing to do.
        if (session.iConfirmed && session.peerConfirmed) return;
        String id = session.session.fightSessionId;
        cancelSessionTimers();
        session = null;
        fireOnFightSessionExpired(id);
    }

    private void transitionToMatchFound()
    {
        if (session == null) return;
        FightSession fs = session.session;
        cancelSessionTimers();
        MatchInfo mi = new MatchInfo(
            fs.fightSessionId, fs.opponent, fs.style, fs.build, fs.location,
            "TBD", meetAtPlace(fs.style, fs.location));
        session = null;
        fireOnMatchFound(mi);
    }

    private void cancelSessionTimers()
    {
        if (session == null) return;
        if (session.peerConfirmTimer != null) session.peerConfirmTimer.cancel();
        if (session.expiryTimer != null) session.expiryTimer.cancel();
    }

    private void cancelAllTimers()
    {
        for (PendingOutgoing po : outgoing.values())
        {
            po.cancellable.cancel();
        }
        outgoing.clear();
        cancelSessionTimers();
        session = null;
    }

    // -------------------- listener fan-out --------------------

    private void fireOnRosterSnapshot(List<LobbyMember> r)
    {
        if (listener != null) listener.onRosterSnapshot(r);
    }

    private void fireOnIncomingInvite(IncomingInvite invite)
    {
        if (listener != null) listener.onIncomingInvite(invite);
    }

    private void fireOnFightProposed(FightSession fs)
    {
        if (listener != null) listener.onFightProposed(fs);
    }

    private void fireOnFightConfirmedByPeer(String id)
    {
        if (listener != null) listener.onFightConfirmedByPeer(id);
    }

    private void fireOnMatchFound(MatchInfo mi)
    {
        if (listener != null) listener.onMatchFound(mi);
    }

    private void fireOnFightSessionExpired(String id)
    {
        if (listener != null) listener.onFightSessionExpired(id);
    }

    private void fireOnError(String code, String message)
    {
        if (listener != null) listener.onError(code, message);
    }

    // -------------------- helpers --------------------

    /** Meeting place per style + sub-location. Mirrors the design spec
     *  (NH Arena → Arena; NH Wildy/FFA Portal → Ferox Enclave;
     *   Veng → Grand Exchange; Multi → Ferox Enclave; DMM → Grand Exchange).
     *  Real world numbers are still TBD — see the {@code meet-at-worlds-tbd}
     *  TODO in {@code PLUGIN_PROGRESS.md}. */
    static String meetAtPlace(Style style, String location)
    {
        if (style == Style.NH)
        {
            if ("Arena".equalsIgnoreCase(location)) return "Arena";
            return "Ferox Enclave";
        }
        if (style == Style.VENG) return "Grand Exchange";
        if (style == Style.MULTI) return "Ferox Enclave";
        if (style == Style.DMM) return "Grand Exchange";
        return "TBD";
    }

    /** Picks {@code preferred} if {@code member} advertises it, otherwise the
     *  first build they do advertise. Mirrors the panel's pre-refactor
     *  {@code firstAdvertisedBuild}; safe under the ≥1-build contract. */
    static BuildType firstAdvertisedBuild(LobbyMember member, BuildType preferred)
    {
        if (member.builds.contains(preferred)) return preferred;
        for (BuildType b : BuildType.values())
        {
            if (member.builds.contains(b)) return b;
        }
        return preferred;
    }

    private IncomingInvite newIncoming(LobbyMember sender, Style s, BuildType b, String location)
    {
        long now = System.currentTimeMillis();
        return new IncomingInvite(genId("ii"), sender, s, b, location,
            now + FIGHT_INVITE_TTL_MS);
    }

    private static String genId(String prefix)
    {
        return prefix + "-" + UUID.randomUUID();
    }

    // -------------------- deterministic mock roster (moved from panel) --------------------

    /** Builds the 50-ish-player deterministic roster. Ported verbatim from
     *  the pre-refactor {@code MatchmakingLobbyPanel.seedMockRoster()} so
     *  visual review of the lobby is stable across the refactor — same
     *  names, same ranks, same chip combos. */
    List<LobbyMember> seedRoster()
    {
        String[] stressNames = {
            "MMMMMMMMMMMM", // 12 — pure-wide-M torture test
            "WWWWWWWWWWWW", // 12 — pure-wide-W torture test
            "MWMWMWMWMWMW", // 12 — alternating
            "Mmm Www Mmm",  // 11 — wide caps + spaces
            "MMM WWW MMW",  // 11 — three groups
            "WWWWMaxedWW",  // 11 — wide bookends
        };
        String[] coreNames = {
            "Zezima", "RangerBob", "PinkBeauty", "StrPure", "Toyco",
            "Lynx Titan", "RUNE H4X", "Doublej", "B0aty", "FRAYK",
            "DAGGAA", "Skiddler", "Manked", "Odablock", "EVScape",
            "JcWoolly", "Westham", "Sammyy", "Tornadough", "Vepex"
        };
        String[] fillerNames = {
            "MaxedMageMM", "WWWMaster", "Wmw Mw Wmw", "MMM HCIM",
            "MMMM Pure", "Mmmm Btw", "WWWW Med", "TornM Madm",
            "Mwwwwww x", "Wmmm Pure", "MMM Tank M", "Wm GG",
            "PvM Maxed", "MMM Wesly", "Wmw Tank x", "MM Mwm GG",
            "Wmmmmmmmmmm", "MMMxBoaty", "WMage Maxx", "Mwm Wm GG",
            "Wesley M M", "MaxedTitan", "WWW Wsk x", "MMMrunex",
        };
        String[] regions = REGION_CODES;
        Random r = new Random(seed);
        List<LobbyMember> out = new ArrayList<>();
        int rankCount = RankUtils.THRESHOLDS.length;

        // Stress entries get top tiers so they render at the top of the
        // sorted list — wide-character torture surfaces clipping bugs fast.
        int[] stressRanks = {24, 24, 23, 23, 22, 22};
        for (int i = 0; i < stressNames.length; i++)
        {
            String name = stressNames[i];
            assert name.length() <= MAX_NAME_LENGTH;
            Set<Style> styles = rollStyles(r, 0.55, 0.30, 0.15);
            out.add(new LobbyMember(
                derivePlayerId(name), name, styles, rollBuilds(r),
                Math.min(rankCount - 1, stressRanks[i]),
                regions[r.nextInt(regions.length)], false));
        }

        int[] coreRanks = {21, 22, 19, 17, 20, 23, 18, 16, 20, 21,
            14, 12, 17, 19, 11, 9, 8, 13, 15, 10};
        for (int i = 0; i < coreNames.length; i++)
        {
            String name = coreNames[i];
            assert name.length() <= MAX_NAME_LENGTH;
            Set<Style> styles = rollStyles(r, 0.55, 0.30, 0.15);
            out.add(new LobbyMember(
                derivePlayerId(name), name, styles, rollBuilds(r),
                Math.min(rankCount - 1, coreRanks[i]),
                regions[r.nextInt(regions.length)], false));
        }

        for (String name : fillerNames)
        {
            assert name.length() <= MAX_NAME_LENGTH;
            Set<Style> styles = rollStyles(r, 0.45, 0.25, 0.10);
            int rankIdx = Math.max(0, Math.min(rankCount - 1,
                3 + r.nextInt(Math.max(1, rankCount - 4))));
            out.add(new LobbyMember(
                derivePlayerId(name), name, styles, rollBuilds(r), rankIdx,
                regions[r.nextInt(regions.length)], false));
        }

        // Hand-picked mod players so [MOD] chip rendering is verifiable on
        // distinct build combos (one Main+Pure, one solo Zerker).
        out.add(new LobbyMember(
            derivePlayerId("StaffMike"), "StaffMike",
            EnumSet.of(Style.NH, Style.VENG),
            EnumSet.of(BuildType.MAIN, BuildType.PURE),
            22, "NA-E", true));
        out.add(new LobbyMember(
            derivePlayerId("ModBeauty"), "ModBeauty",
            EnumSet.of(Style.NH, Style.MULTI),
            EnumSet.of(BuildType.ZERKER),
            20, "EU", true));

        return Collections.unmodifiableList(out);
    }

    /** Canonical {@code player_id} derived from a mock player's display
     *  name — the lowercased form, mirroring the server's
     *  {@code canon_name()}. Same name → same id across launches, so
     *  tests asserting "this member's id" don't flake on the random
     *  seed. Pre-{@code p2-scrub-uuids-from-lobby} this returned a
     *  {@code UUID.nameUUIDFromBytes(name)} string — that was the
     *  pre-{@code p2-plugin-acct-sha-cutover} identity for mock
     *  members. With UUIDs scrubbed from the wire and the
     *  {@link LobbyMember} model, the canonical lowercase name is the
     *  correct identifier. */
    private static String derivePlayerId(String name)
    {
        return name == null ? "" : name.toLowerCase();
    }

    /** Styles roll: NH is always set, then probabilistic for Veng / Multi /
     *  DMM. Matches the pre-refactor mock distribution. */
    private static Set<Style> rollStyles(Random r, double vengP, double multiP, double dmmP)
    {
        Set<Style> styles = new LinkedHashSet<>();
        styles.add(Style.NH);
        if (r.nextDouble() < vengP) styles.add(Style.VENG);
        if (r.nextDouble() < multiP) styles.add(Style.MULTI);
        if (r.nextDouble() < dmmP) styles.add(Style.DMM);
        return styles;
    }

    /** Builds roll. Mirrors realistic OSRS-PvP distribution: ~55% Main,
     *  ~30% Pure, ~15% Zerker primary, plus ~15% chance of a secondary
     *  build (idempotent — collapses if it matches primary). Always
     *  returns a non-empty set. */
    static Set<BuildType> rollBuilds(Random r)
    {
        Set<BuildType> set = EnumSet.noneOf(BuildType.class);
        double roll = r.nextDouble();
        if (roll < 0.55) set.add(BuildType.MAIN);
        else if (roll < 0.85) set.add(BuildType.PURE);
        else set.add(BuildType.ZERKER);
        if (r.nextDouble() < 0.15)
        {
            BuildType[] values = BuildType.values();
            set.add(values[r.nextInt(values.length)]);
        }
        return set;
    }

    /** Accessor for the active session — package-private for the test
     *  matrix, which asserts post-condition state after each command. */
    PendingSession currentSession() { return session; }

    /** Accessor for the outgoing-invites map — package-private for tests. */
    Map<String, PendingOutgoing> outgoingInvites() { return outgoing; }

    /** Accessor for the incoming-invites map — package-private for tests. */
    Map<String, IncomingInvite> incomingInvites() { return incoming; }

    /** Accessor for the cached roster — package-private for tests. */
    List<LobbyMember> currentRoster() { return roster == null ? Collections.emptyList() : roster; }

    /** Pending outgoing invite + cancellable timer handle. Held in a map
     *  keyed by opponent {@code player_id} so cancel-by-opponent is O(1). */
    static final class PendingOutgoing
    {
        final OutgoingInvite invite;
        final MockScheduler.Cancellable cancellable;

        PendingOutgoing(OutgoingInvite invite, MockScheduler.Cancellable cancellable)
        {
            this.invite = invite;
            this.cancellable = cancellable;
        }
    }

    /** Active mutual-confirm session — the immutable {@link FightSession}
     *  plus mutable local confirm state and the two timer handles. */
    static final class PendingSession
    {
        final FightSession session;
        boolean iConfirmed;
        boolean peerConfirmed;
        MockScheduler.Cancellable peerConfirmTimer;
        MockScheduler.Cancellable expiryTimer;

        PendingSession(FightSession session)
        {
            this.session = session;
        }
    }
}
