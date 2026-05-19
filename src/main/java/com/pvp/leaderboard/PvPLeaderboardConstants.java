package com.pvp.leaderboard;

/**
 * Single source of truth for cross-service constants used by the plugin.
 *
 * <p>Prior to this class, the public-site URL was duplicated across
 * {@code WhitelistService}, {@code PvPDataService}, {@code DashboardPanel},
 * and {@code LoginPanel}, which made every domain change a four-file edit
 * prone to silent drift. The {@code pvp-leaderboard.com} migration
 * (TODO.md §19 / OSRS-MMR/docs/DOMAIN_MIGRATION.md in the backend repo)
 * was the trigger for centralising it.
 *
 * <p>The legacy domain {@code devsecopsautomated.com} continues to work for
 * any user on an older plugin build because the backend's CloudFront
 * distribution is dual-aliased during the migration window — both domains
 * serve byte-identical content, and the auto-banner accepts either Referer
 * via the {@code endpoints_pvp/} carbon-copy + router dispatch. A future
 * domain change only requires editing this single file and shipping a new
 * plugin build.
 */
public final class PvPLeaderboardConstants
{
    /**
     * Public-facing CloudFront origin for the static site, leaderboard
     * shards, whitelist, and Cognito callback. Used as the base for
     * {@code /whitelist.json}, {@code /rank_idx/...}, {@code /index.html},
     * {@code /profile.html?player=...}, etc.
     *
     * <p>NOT the REST API base — that remains the raw API Gateway
     * execute-api URL until the socket-phase work begins (see
     * SOCKET_LOBBY_ARCHITECTURE.md, section "Hard Pre-conditions" →
     * {@code precond-domain}).
     */
    public static final String PUBLIC_SITE_BASE_URL = "https://pvp-leaderboard.com";

    /**
     * WebSocket endpoint for the socket-lobby system. Backed by AWS API
     * Gateway WebSocket API behind the {@code api.pvp-leaderboard.com}
     * custom domain (see
     * {@code OSRS-MMR/lambda_code/docs/WEBSOCKET_PROTOCOL.md §1}). The
     * trailing {@code /prod} is the stage segment — staging uses
     * {@code wss://api-staging.pvp-leaderboard.com/dev}.
     *
     * <p>The plugin appends {@code ?uuid=<uuid-v4-lowercase>} and a
     * {@code User-Agent: RuneLite/<ver>} header at {@code $connect};
     * malformed / missing UUID or User-Agent → stealth refuse + WAF auto-ban
     * (see protocol §3 validation chain). One UUID = one connection;
     * the server force-closes the prior connection on a duplicate.
     */
    public static final String WEBSOCKET_URL = "wss://api.pvp-leaderboard.com/prod";

    private PvPLeaderboardConstants()
    {
        // utility class — no instantiation
    }
}
