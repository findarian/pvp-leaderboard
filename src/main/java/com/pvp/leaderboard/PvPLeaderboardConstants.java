package com.pvp.leaderboard;

/**
 * Single source of truth for cross-service URLs used by the plugin.
 * Centralising these constants keeps a future domain change to a
 * one-file edit (the previous duplication across {@code WhitelistService},
 * {@code PvPDataService}, {@code DashboardPanel}, and {@code LoginPanel}
 * was prone to silent drift).
 */
public final class PvPLeaderboardConstants
{
    /**
     * Public-facing CloudFront origin for the static site, leaderboard
     * shards, whitelist, and Cognito callback. Used as the base for
     * {@code /whitelist.json}, {@code /rank_idx/...},
     * {@code /profile.html?player=...}, etc. The bare origin (no path
     * suffix) opens the website's default landing page; CloudFront
     * resolves the index document — no need to append {@code /index.html}.
     */
    public static final String PUBLIC_SITE_BASE_URL = "https://pvp-leaderboard.com";

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
    public static final String WEBSOCKET_URL = "wss://api.pvp-leaderboard.com/prod";

    private PvPLeaderboardConstants()
    {
    }
}
