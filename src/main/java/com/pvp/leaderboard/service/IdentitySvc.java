package com.pvp.leaderboard.service;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import javax.inject.*;
import net.runelite.client.config.*;

@Singleton
public class IdentitySvc
{
    private final ConfigManager configManager;
    private String clientUniqueId;

    @Inject
    public IdentitySvc(ConfigManager configManager)
    {
        this.configManager = configManager;
    }

    /**
     * Initializes the client ID on startup.
     * Logic: Prefer global file ~/.runelite/pvp-leaderboard.id. If missing, generate and save.
     * Also syncs to RuneLite profile config for backup.
     */
    public void ensureId()
    {
        if (clientUniqueId != null) return; // Already loaded

        File globalFile = new File(System.getProperty("user.home"), ".runelite/pvp-leaderboard.id");
        String finalId = null;

        try
        {
            // A missing file throws here, like an unreadable one
            finalId = new String(Files.readAllBytes(globalFile.toPath()), StandardCharsets.UTF_8).trim();
        }
        catch (Exception e)
        {
            // log.debug("Failed to read global identity file", e);
        }

        // If disk file missing, try to recover from profile config
        String profileId = configManager.getConfiguration("PvPLeaderboard", "clientUniqueId");
        if (finalId == null || finalId.isEmpty())
        {
            finalId = profileId;
        }

        // If still no ID, generate new one
        if (finalId == null || finalId.isEmpty())
        {
            finalId = UUID.randomUUID().toString();
        }

        // Save to global file
        try
        {
            Files.createDirectories(globalFile.toPath().getParent());
            Files.write(globalFile.toPath(), finalId.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            // log.debug("Failed to write global identity file", e);
        }

        // Sync to profile
        if (!finalId.equals(profileId))
        {
            configManager.setConfiguration("PvPLeaderboard", "clientUniqueId", finalId);
        }

        this.clientUniqueId = finalId;
    }

    public String getClientUniqueId()
    {
        if (clientUniqueId == null) ensureId();
        return clientUniqueId;
    }
}
