package com.pvp.leaderboard.service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

@Slf4j
@Singleton
public class ClientIdentityService
{
    private final ConfigManager configManager;
    private String clientUniqueId;

    @Inject
    public ClientIdentityService(ConfigManager configManager)
    {
        this.configManager = configManager;
    }

    /**
     * Initializes the client ID on startup.
     * Logic: Prefer global file ~/.runelite/pvp-leaderboard.id. If missing, generate and save.
     * Also syncs to RuneLite profile config for backup.
     */
    public void loadOrGenerateId()
    {
        if (clientUniqueId != null) return; // Already loaded

        File globalFile = new File(System.getProperty("user.home"), ".runelite/pvp-leaderboard.id");
        String globalId = null;

        try
        {
            if (globalFile.exists())
            {
                byte[] bytes = Files.readAllBytes(globalFile.toPath());
                globalId = new String(bytes, StandardCharsets.UTF_8).trim();
            }
        }
        catch (Exception e)
        {
            // log.debug("Failed to read global identity file", e);
        }

        String finalId = globalId;
        if (finalId == null || finalId.isEmpty())
        {
            finalId = UUID.randomUUID().toString();
            // Save back to global file
            try
            {
                File parent = globalFile.getParentFile();
                if (parent != null && !parent.exists())
                {
                    parent.mkdirs();
                }
                Files.write(globalFile.toPath(), finalId.getBytes(StandardCharsets.UTF_8));
            }
            catch (Exception e)
            {
                // log.debug("Failed to write global identity file", e);
            }
        }

        // Sync to profile
        String profileId = configManager.getConfiguration("PvPLeaderboard", "clientUniqueId");
        if (!finalId.equals(profileId))
        {
            configManager.setConfiguration("PvPLeaderboard", "clientUniqueId", finalId);
        }

        this.clientUniqueId = finalId;
    }

    public String getClientUniqueId()
    {
        if (clientUniqueId == null) loadOrGenerateId();
        return clientUniqueId;
    }
}
