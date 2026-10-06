package com.pvp.leaderboard;

import net.runelite.client.*;

/**
 * Single source of truth for cross-service URLs used by the plugin.
 * Centralising these constants keeps a future domain change to a
 * one-file edit (the previous duplication across {@code WhitelistService},
 * {@code PvpApi}, {@code Dashboard}, and {@code LoginPanel}
 * was prone to silent drift).
 */
public final class PvpConsts
{
    /**
     * Public-facing CloudFront origin for the static site, leaderboard
     * shards, whitelist, and Cognito callback. Used as the base for
     * {@code /whitelist.json}, {@code /rank_idx/...},
     * {@code /profile.html?player=...}, etc. The bare origin (no path
     * suffix) opens the website's default landing page; CloudFront
     * resolves the index document — no need to append {@code /index.html}.
     */
    public static final String SITE_URL = "https://pvp-leaderboard.com";

    /** The REST API's stage base: {@code /matchresult}, {@code /heartbeat}, {@code /user}, {@code /matches}, ... */
    public static final String API_BASE_URL = "https://l5xya0wf0d.execute-api.us-east-1.amazonaws.com/prod";

    /**
     * WebSocket endpoint for the socket-lobby system. Backed by AWS API
     * Gateway WebSocket API behind the {@code api.pvp-leaderboard.com}
     * custom domain. The trailing {@code /prod} is the stage segment.
     *
     * <p>The plugin appends a per-install identifier on the query string
     * and a {@code User-Agent: RuneLite/<ver>} header at {@code $connect};
     * one identifier = one connection (the server force-closes the prior
     * connection on a duplicate).
     */
    public static final String SOCKET_URL = "wss://api.pvp-leaderboard.com/prod";

    public static final String VERSION = "2.0.0";

    /** The {@code User-Agent} of the plugin's own requests (the socket's
     *  $connect, the heartbeat, the membership feed). */
    public static final String USER_AGENT = "RuneLite/" + RuneLiteProperties.getVersion();

    /**
     * Raw API Gateway invoke URL for the standalone {@code OSRS-DiscordAuth-API}
     * stage (no custom domain). Single source of the deployed API id — must
     * match the website's {@code script.js} / {@code auth/discord-callback.html}
     * / {@code auth/discord-plugin-callback.html}. If the auth API is ever
     * re-created, update this id here and in those website files together.
     */
    private static final String DISCORD_API =
        "https://5l3oex7zaa.execute-api.us-east-1.amazonaws.com/prod";

    /**
     * Loopback-free plugin login — start endpoint
     * ({@code GET /auth/discord/plugin-init}). The backend mints the handshake
     * + PKCE (server-held) and returns {@code {login_id, authorize_url}}. The
     * OAuth redirect lands on a hosted page (NOT {@code 127.0.0.1}); the plugin
     * then polls {@link #POLL_URL}. This replaces the old
     * {@code http://127.0.0.1:49215/callback} loopback server.
     */
    public static final String INIT_URL =
        DISCORD_API + "/auth/discord/plugin-init";

    /**
     * Loopback-free plugin login — poll endpoint
     * ({@code GET /auth/discord/plugin-poll?login_id=}). Returns
     * {@code {status: pending|complete|error|expired, session?}} until the
     * browser side finishes; the plugin adopts the minimal session on
     * {@code complete}.
     */
    public static final String POLL_URL =
        DISCORD_API + "/auth/discord/plugin-poll";

    // ------------------------------------------------------------------
    // LMS (Last Man Standing) freeze-log detection
    // ------------------------------------------------------------------

    /**
     * Template-coordinate bounding boxes (plane 0) for the two LMS
     * arena maps, each as {@code {minX, minY, maxX, maxY}}. There are
     * exactly two LMS maps; coordinates were read from Explv's Map
     * corner tiles.
     *
     * <p><b>Instance note:</b> LMS arenas are instanced (hence the
     * {@code y > 5700} template band, well outside the overworld), so
     * a caller MUST convert the local player's position through
     * {@code WorldPoint.fromLocalInstance(...)} before testing against
     * these bounds — a raw {@code getWorldLocation()} returns
     * dynamic-scene coordinates that will never match.
     */
    private static final int[][] LMS_AREAS = {
        // Map 1: corners NW 3456,6206 / NE 3646,6207 / SW 3455,6015 / SE 3647,6015
        {3455, 6015, 3647, 6207},
        // Map 2: corners 3391,5887 / 3519,5887 / 3519,5760 / 3391,5760
        {3391, 5760, 3519, 5887},
    };

    /**
     * True iff {@code world} hosts Last Man Standing (390, 559, 580).
     * Freeze-log detection is gated on the player being on one of these
     * worlds AND physically inside an {@link #LMS_AREAS} rectangle. Kept in
     * sync with the backend compact world map's {@code "LMS"} key.
     */
    public static boolean isLmsWorld(int world)
    {
        return world == 390 || world == 559 || world == 580;
    }

    /**
     * True iff the template coordinate {@code (x, y)} falls inside either
     * of the {@link #LMS_AREAS} rectangles (inclusive bounds). Pure
     * integer math so it is unit-testable without a RuneLite client;
     * plane is intentionally not consulted (the y-band is unique to the
     * instanced arenas, and the arenas span a single plane).
     */
    public static boolean isInLmsArea(int x, int y)
    {
        return in(x, y, LMS_AREAS[0]) || in(x, y, LMS_AREAS[1]);
    }

    public static final int[] FFA_AREA = {3263, 4735, 3391, 4864};

    public static boolean isFfaArea(int x, int y)
    {
        return in(x, y, FFA_AREA);
    }

    public static final int[] BOUNTY_AREA = {3338, 3989, 3501, 4139};

    public static boolean isBountyArea(int x, int y)
    {
        return in(x, y, BOUNTY_AREA);
    }

    /** {@code (x, y)} inside the inclusive rectangle {@code {minX, minY, maxX, maxY}}. */
    private static boolean in(int x, int y, int[] r)
    {
        return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
    }
}
