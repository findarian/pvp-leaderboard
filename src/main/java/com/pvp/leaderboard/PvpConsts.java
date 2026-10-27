package com.pvp.leaderboard;

import net.runelite.client.*;

/**
 * Single source of truth for cross-service URLs used by the plugin.
 */
public final class PvpConsts
{
    /**
     * The website origin: the static site, the leaderboard shards and the
     * whitelist. Used as the base for {@code /whitelist.json},
     * {@code /rank_idx/...}, {@code /profile.html?player=...}, etc. The bare
     * origin opens the website's landing page.
     */
    public static final String SITE_URL = "https://pvp-leaderboard.com";

    /** The REST API's stage base: {@code /matchresult}, {@code /heartbeat}, {@code /user}, {@code /matches}, ... */
    public static final String API_BASE_URL = "https://l5xya0wf0d.execute-api.us-east-1.amazonaws.com/prod";

    /**
     * WebSocket endpoint for the socket-lobby system. The trailing
     * {@code /prod} is the stage segment.
     *
     * <p>The plugin appends a per-install identifier on the query string
     * and a {@code User-Agent: RuneLite/<ver>} header at {@code $connect}.
     */
    public static final String SOCKET_URL = "wss://api.pvp-leaderboard.com/prod";

    public static final String VERSION = "2.1.1";

    /** The {@code User-Agent} of the plugin's own requests (the socket's
     *  $connect, the heartbeat, the membership feed). */
    public static final String USER_AGENT = "RuneLite/" + RuneLiteProperties.getVersion();

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
     * worlds AND physically inside an {@link #LMS_AREAS} rectangle.
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
