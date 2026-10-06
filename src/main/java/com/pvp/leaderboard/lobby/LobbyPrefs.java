package com.pvp.leaderboard.lobby;

import java.util.*;
import java.util.logging.*;
import javax.inject.*;
import net.runelite.client.config.*;

/**
 * Persisted lobby gate preferences — region, advertised styles, advertised
 * builds, rank slider bounds, a "user already finished gate setup" sticky
 * flag, and a "user explicitly left the lobby" opt-out flag (suppresses
 * zero-config auto-join until a manual re-join).
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
 * existing {@link com.pvp.leaderboard.lobby.NoOpLobby}-only call
 * sites). The in-memory variant simulates a fresh "no preferences yet" user
 * — every getter returns its supplied default and setters write to a local
 * map that's discarded when the JVM exits.
 */
@Singleton
public class LobbyPrefs
{
    private static final Logger LOG = Logger.getLogger(LobbyPrefs.class.getName());

    /** Same group used by {@link com.pvp.leaderboard.config.PvPLeaderboardConfig}
     *  so the values share a profile namespace with the existing
     *  user-facing settings. */
    public static final String CONFIG_GROUP = "PvPLeaderboard";

    public static final String KEY_REGION = "lobbyRegion";

    /** Plan 10 F.1 (2026-09-21): the queue's own picks — the wait preference
     *  (seconds, one of QueueService.WAIT_CHOICES; also the server-side
     *  shared pref) and the optional rank range for the queue, kept apart
     *  from the lobby slider so the two features never fight over one key. */
    public static final String KEY_WAIT = "queueWaitPrefS";
    public static final String KEY_RANGE_ON = "queueRangeEnabled";
    public static final String KEY_MIN_RANK = "queueMinRankIdx";
    public static final String KEY_MAX_RANK = "queueMaxRankIdx";

    /** The side panel's "Show streaks" switch. Not a lobby key: {@link #clear()} leaves it. */
    public static final String KEY_STREAKS = "sidePanelShowStreaks";;

    /** {@code null} ⇒ in-memory mode. */
    private final ConfigManager configManager;
    /** Backing store for in-memory mode. {@code null} when {@link #configManager}
     *  is non-null — we always use one or the other. */
    private final Map<String, String> memoryStore;

    @Inject
    public LobbyPrefs(ConfigManager configManager)
    {
        this.configManager = configManager;
        memoryStore = null;
    }

    private LobbyPrefs()
    {
        configManager = null;
        memoryStore = new HashMap<>();
    }

    /** No-persistence variant for unit tests + null-config call sites. The
     *  returned instance behaves identically to a fresh user (every getter
     *  returns its supplied default until a setter is called); writes are
     *  scoped to the returned instance and never escape the JVM. */
    public static LobbyPrefs inMemory()
    {
        return new LobbyPrefs();
    }

    // -------------------- Queue (Plan 10 F.1) --------------------

    public int getWaitPrefS(int defaultValue)
    {
        return readInt(KEY_WAIT, defaultValue);
    }

    public void setWaitPrefS(int seconds)
    {
        writeRaw(KEY_WAIT, Integer.toString(seconds));
    }

    public boolean getRangeOn()
    {
        return "true".equals(readRaw(KEY_RANGE_ON));
    }

    public void setRangeOn(boolean enabled)
    {
        writeRaw(KEY_RANGE_ON, Boolean.toString(enabled));
    }

    public int getMinRank(int defaultValue)
    {
        return readInt(KEY_MIN_RANK, defaultValue);
    }

    public void setMinRank(int idx)
    {
        writeRaw(KEY_MIN_RANK, Integer.toString(idx));
    }

    public int getMaxRank(int defaultValue)
    {
        return readInt(KEY_MAX_RANK, defaultValue);
    }

    public void setMaxRank(int idx)
    {
        writeRaw(KEY_MAX_RANK, Integer.toString(idx));
    }

    // -------------------- Side panel --------------------

    public boolean getShowStreaks()
    {
        return "true".equalsIgnoreCase(readRaw(KEY_STREAKS));
    }

    public void setShowStreaks(boolean shown)
    {
        writeRaw(KEY_STREAKS, Boolean.toString(shown));
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

    // -------------------- Internals --------------------

    private String readRaw(String key)
    {
        try
        {
            if (configManager != null)
            {
                return configManager.getConfiguration(CONFIG_GROUP, key);
            }
            return memoryStore.get(key);
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
                memoryStore.remove(key);
            }
            else
            {
                memoryStore.put(key, value);
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
}
