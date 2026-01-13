package com.pvp.leaderboard.game;

import com.google.gson.JsonObject;
import com.pvp.leaderboard.config.PvPLeaderboardConfig;
import com.pvp.leaderboard.overlay.RankOverlay;
import com.pvp.leaderboard.service.PvPDataService;
import com.pvp.leaderboard.ui.DashboardPanel;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

@Slf4j
@Singleton
public class MenuHandler
{
    private final PvPLeaderboardConfig config;
    private final MenuManager menuManager;
    private final ClientToolbar clientToolbar;
    private final PvPDataService pvpDataService;

    // Dependencies set after startup via init/update
    private DashboardPanel dashboardPanel;
    private RankOverlay rankOverlay;
    private NavigationButton navButton;

    @Inject
    public MenuHandler(PvPLeaderboardConfig config, MenuManager menuManager, ClientToolbar clientToolbar, PvPDataService pvpDataService)
    {
        this.config = config;
        this.menuManager = menuManager;
        this.clientToolbar = clientToolbar;
        this.pvpDataService = pvpDataService;
    }

    public void init(DashboardPanel dashboardPanel, RankOverlay rankOverlay, NavigationButton navButton)
    {
        this.dashboardPanel = dashboardPanel;
        this.rankOverlay = rankOverlay;
        this.navButton = navButton;
        
        refreshMenuOption();
    }

    public void updateNavButton(NavigationButton navButton)
    {
        this.navButton = navButton;
    }

    public void refreshMenuOption()
    {
        try {
            if (config.enablePvpLookupMenu()) {
                menuManager.addPlayerMenuItem("PvP lookup");
            } else {
                menuManager.removePlayerMenuItem("PvP lookup");
            }
        } catch (Exception ignore) {}
    }

    public void shutdown()
    {
        menuManager.removePlayerMenuItem("PvP lookup");
    }

    public void handleMenuOptionClicked(MenuOptionClicked event)
    {
        try
        {
            if (!config.enablePvpLookupMenu()) {
                return;
            }
            if (event.getMenuAction() != MenuAction.RUNELITE_PLAYER) {
                return;
            }
            if (!"PvP lookup".equals(event.getMenuOption())) {
                return;
            }

            String target = event.getMenuTarget();
            if (target == null) {
                return;
            }

            String cleaned = Text.removeTags(target);
            // Remove trailing (level-xxx)
            cleaned = cleaned.replaceAll("\\s*\\(level-\\d+\\)$", "");
            // Remove any parenthetical annotation like (Skill 1234)
            cleaned = cleaned.replaceAll("\\([^)]*\\)", "");
            // Normalize without converting underscores/hyphens to spaces
            // (RuneScape treats space, underscore, hyphen as equivalent, but we preserve original format)
            String playerName = cleaned.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");

            if (dashboardPanel != null) {
                dashboardPanel.loadMatchHistory(playerName);
            }

            // Open plugin side panel
            if (clientToolbar != null && navButton != null) {
                SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
            }
            
            // Fetch rank and add to looked-up players cache for overlay display
            if (config.showLookedUpRanks() && rankOverlay != null && pvpDataService != null) {
                fetchAndCacheLookedUpRank(playerName);
            }
        }
        catch (Exception e)
        {
             // log.debug("Uncaught exception in handleMenuOptionClicked", e);
        }
    }
    
    /**
     * Fetch all bucket ranks for a looked-up player and add to the overlay cache.
     * Fetches the full profile to get ranks for all buckets at once.
     */
    private void fetchAndCacheLookedUpRank(String playerName)
    {
        log.debug("[MenuHandler] Fetching full profile for looked-up player: {}", playerName);
        
        pvpDataService.getUserProfile(playerName, null).thenAccept(profile -> {
            if (profile == null) {
                log.debug("[MenuHandler] No profile found for looked-up player: {}", playerName);
                return;
            }
            
            // Extract ranks for all buckets
            Map<String, String> bucketRanks = extractAllBucketRanks(profile);
            
            if (bucketRanks.isEmpty()) {
                log.debug("[MenuHandler] No ranks found in profile for: {}", playerName);
                return;
            }
            
            log.debug("[MenuHandler] Got {} bucket ranks for {}: {}", bucketRanks.size(), playerName, bucketRanks);
            if (rankOverlay != null) {
                rankOverlay.addLookedUpPlayer(playerName, bucketRanks);
            }
        }).exceptionally(ex -> {
            log.debug("[MenuHandler] Failed to fetch profile for {}: {}", playerName, ex.getMessage());
            return null;
        });
    }
    
    /**
     * Extract rank/tier from all buckets in a user profile.
     * Returns a map of bucket name -> tier string (e.g., "nh" -> "Dragon 2")
     */
    private Map<String, String> extractAllBucketRanks(JsonObject profile)
    {
        Map<String, String> ranks = new HashMap<>();
        
        // Extract overall (top-level) rank
        String overallTier = extractTierFromData(profile);
        if (overallTier != null) {
            ranks.put("overall", overallTier);
        }
        
        // Extract bucket-specific ranks
        if (profile.has("buckets") && profile.get("buckets").isJsonObject()) {
            JsonObject buckets = profile.getAsJsonObject("buckets");
            String[] bucketNames = {"nh", "veng", "multi", "dmm"};
            
            for (String bucket : bucketNames) {
                if (buckets.has(bucket) && buckets.get(bucket).isJsonObject()) {
                    JsonObject bucketData = buckets.getAsJsonObject(bucket);
                    String tier = extractTierFromData(bucketData);
                    if (tier != null) {
                        ranks.put(bucket, tier);
                    }
                }
            }
        }
        
        return ranks;
    }
    
    /**
     * Extract tier string from a bucket data object.
     * Combines rank name and division (e.g., "Dragon" + 2 = "Dragon 2")
     */
    private String extractTierFromData(JsonObject data)
    {
        if (data == null) return null;
        
        String rank = null;
        int division = 0;
        
        if (data.has("rank") && !data.get("rank").isJsonNull()) {
            rank = data.get("rank").getAsString();
        }
        if (data.has("division") && !data.get("division").isJsonNull()) {
            division = data.get("division").getAsInt();
        }
        
        if (rank != null && !rank.isEmpty()) {
            return division > 0 ? rank + " " + division : rank;
        }
        return null;
    }
}
