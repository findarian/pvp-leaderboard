package com.pvp.leaderboard.lobby;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.runelite.client.config.ConfigManager;

/**
 * Persisted lobby gate preferences — region, advertised styles, advertised
 * builds, rank slider bounds, and a "user already finished gate setup" sticky
 * flag.
 *
 * <p>The matchmaking panel reads through this on construction so a user who
 * has previously completed the gate is dropped straight into the lobby on
 * subsequent logins, with their picks pre-restored. {@link
 * com.pvp.leaderboard.ui.MatchmakingLobbyPanel#resetGateOptions()} writes
 * through to {@link #clear()} so the explicit "I want to re-pick" path
 * forgets everything.
 *
 * <p>Backed by RuneLite's {@link ConfigManager} — the values land in the
 * active RuneLite profile's config alongside the existing
 * {@code PvPLeaderboard.*} settings. The keys deliberately don't carry
 * {@code @ConfigItem} annotations because we don't want them to surface in
 * the RuneLite settings panel; they're hidden runtime state, not a user
 * setting.
 *
 * <p>An {@link #inMemory()} factory returns a no-op variant that keeps the
 * panel building without a real {@code ConfigManager} (unit tests, the
 * existing {@link com.pvp.leaderboard.lobby.NoOpLobbyService}-only call
 * sites). The in-memory variant simulates a fresh "no preferences yet" user
 * — every getter returns its supplied default and setters write to a local
 * map that's discarded when the JVM exits.
 */
@Singleton
public class LobbyPreferences
{
    private static final Logger LOG = Logger.getLogger(LobbyPreferences.class.getName());

    /** Same group used by {@link com.pvp.leaderboard.config.PvPLeaderboardConfig}
     *  so the values share a profile namespace with the existing
     *  user-facing settings. */
    public static final String CONFIG_GROUP = "PvPLeaderboard";

    public static final String KEY_REGION = "lobbyRegion";
    public static final String KEY_STYLES = "lobbyStyles";
    public static final String KEY_BUILDS = "lobbyBuilds";
    public static final String KEY_MIN_RANK_IDX = "lobbyMinRankIdx";
    public static final String KEY_MAX_RANK_IDX = "lobbyMaxRankIdx";
    public static final String KEY_HAS_JOINED = "lobbyHasJoined";

    /** Aggregated list so {@link #clear()} can wipe in one loop without
     *  drifting from the constants above. */
    private static final String[] ALL_KEYS = {
        KEY_REGION, KEY_STYLES, KEY_BUILDS,
        KEY_MIN_RANK_IDX, KEY_MAX_RANK_IDX, KEY_HAS_JOINED,
    };

    /** {@code null} ⇒ in-memory mode. */
    private final ConfigManager configManager;
    /** Backing store for in-memory mode. {@code null} when {@link #configManager}
     *  is non-null — we always use one or the other. */
    private final Map<String, String> inMemoryStore;

    @Inject
    public LobbyPreferences(ConfigManager configManager)
    {
        this.configManager = configManager;
        this.inMemoryStore = null;
    }

    private LobbyPreferences()
    {
        this.configManager = null;
        this.inMemoryStore = new HashMap<>();
    }

    /** No-persistence variant for unit tests + null-config call sites. The
     *  returned instance behaves identically to a fresh user (every getter
     *  returns its supplied default until a setter is called); writes are
     *  scoped to the returned instance and never escape the JVM. */
    public static LobbyPreferences inMemory()
    {
        return new LobbyPreferences();
    }

    // -------------------- Region --------------------

    public String getRegion(String defaultValue)
    {
        String raw = readRaw(KEY_REGION);
        return (raw == null || raw.isEmpty()) ? defaultValue : raw;
    }

    public void setRegion(String region)
    {
        writeRaw(KEY_REGION, region == null ? "" : region);
    }

    // -------------------- Styles --------------------

    /** Returns the persisted style set, or {@code defaultStyles} (defensively
     *  copied) when nothing is persisted yet. Unknown enum names in the
     *  persisted CSV are silently dropped — handles the case where an old
     *  plugin version persisted a style that no longer exists. */
    public Set<Style> getStyles(Set<Style> defaultStyles)
    {
        String raw = readRaw(KEY_STYLES);
        if (raw == null || raw.isEmpty())
        {
            return defaultStyles == null
                ? EnumSet.noneOf(Style.class)
                : EnumSet.copyOf(defaultStyles);
        }
        EnumSet<Style> out = EnumSet.noneOf(Style.class);
        for (String token : raw.split(","))
        {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try
            {
                out.add(Style.valueOf(trimmed));
            }
            catch (IllegalArgumentException ignored)
            {
                // Ignore unknown enum names — preserves forward/backward
                // compat across plugin versions.
            }
        }
        return out;
    }

    public void setStyles(Set<Style> styles)
    {
        writeRaw(KEY_STYLES, encodeEnumSet(styles));
    }

    // -------------------- Builds --------------------

    public Set<BuildType> getBuilds(Set<BuildType> defaultBuilds)
    {
        String raw = readRaw(KEY_BUILDS);
        if (raw == null || raw.isEmpty())
        {
            return defaultBuilds == null
                ? EnumSet.noneOf(BuildType.class)
                : EnumSet.copyOf(defaultBuilds);
        }
        EnumSet<BuildType> out = EnumSet.noneOf(BuildType.class);
        for (String token : raw.split(","))
        {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try
            {
                out.add(BuildType.valueOf(trimmed));
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }
        return out;
    }

    public void setBuilds(Set<BuildType> builds)
    {
        writeRaw(KEY_BUILDS, encodeEnumSet(builds));
    }

    // -------------------- Rank slider --------------------

    public int getMinRankIdx(int defaultValue)
    {
        return readInt(KEY_MIN_RANK_IDX, defaultValue);
    }

    public void setMinRankIdx(int idx)
    {
        writeRaw(KEY_MIN_RANK_IDX, Integer.toString(idx));
    }

    public int getMaxRankIdx(int defaultValue)
    {
        return readInt(KEY_MAX_RANK_IDX, defaultValue);
    }

    public void setMaxRankIdx(int idx)
    {
        writeRaw(KEY_MAX_RANK_IDX, Integer.toString(idx));
    }

    // -------------------- Has-joined sticky --------------------

    public boolean getHasJoined()
    {
        String raw = readRaw(KEY_HAS_JOINED);
        return "true".equalsIgnoreCase(raw);
    }

    public void setHasJoined(boolean joined)
    {
        writeRaw(KEY_HAS_JOINED, Boolean.toString(joined));
    }

    // -------------------- Wipe --------------------

    /** Wipes every persisted lobby preference. Called by Reset Options so
     *  the user is dropped back at a "first launch" gate on next login.
     *  Idempotent — safe to call when nothing has been persisted yet. */
    public void clear()
    {
        for (String key : ALL_KEYS)
        {
            writeRaw(key, null);
        }
    }

    /** Read-only view of the in-memory store, for tests / debugging. Returns
     *  {@link Collections#emptyMap()} in {@link ConfigManager}-backed mode
     *  since the underlying store is not introspectable through this class. */
    public Map<String, String> snapshotInMemoryForTest()
    {
        return inMemoryStore == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(inMemoryStore));
    }

    // -------------------- Internals --------------------

    private String readRaw(String key)
    {
        try
        {
            if (configManager != null)
            {
                return configManager.getConfiguration(CONFIG_GROUP, key);
            }
            return inMemoryStore.get(key);
        }
        catch (RuntimeException e)
        {
            LOG.log(Level.WARNING, "LobbyPreferences read failed for key " + key, e);
            return null;
        }
    }

    private void writeRaw(String key, String value)
    {
        try
        {
            if (configManager != null)
            {
                if (value == null)
                {
                    configManager.unsetConfiguration(CONFIG_GROUP, key);
                }
                else
                {
                    configManager.setConfiguration(CONFIG_GROUP, key, value);
                }
                return;
            }
            if (value == null)
            {
                inMemoryStore.remove(key);
            }
            else
            {
                inMemoryStore.put(key, value);
            }
        }
        catch (RuntimeException e)
        {
            LOG.log(Level.WARNING, "LobbyPreferences write failed for key " + key, e);
        }
    }

    private int readInt(String key, int defaultValue)
    {
        String raw = readRaw(key);
        if (raw == null || raw.isEmpty()) return defaultValue;
        try
        {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    private static <E extends Enum<E>> String encodeEnumSet(Set<E> set)
    {
        if (set == null || set.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (E e : set)
        {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.name());
        }
        return sb.toString();
    }
}
