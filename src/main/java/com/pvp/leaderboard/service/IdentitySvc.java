package com.pvp.leaderboard.service;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.extern.slf4j.*;
import net.runelite.client.config.*;
import net.runelite.client.util.*;

@Slf4j
@Singleton
public class IdentitySvc
{
    private static final String GROUP = "PvPLeaderboard";
    private static final String KEY = "clientUniqueId";
    /** A blank id file younger than this may be another client's new file still being written. */
    private static final int FRESH_SECS = 10;

    private final ConfigManager configManager;
    private volatile String clientUniqueId;

    @Inject
    public IdentitySvc(ConfigManager configManager)
    {
        this.configManager = configManager;
    }

    /**
     * Loads the id once, at start-up: the id file, else the profile copy, else a new UUID; after a read error, the
     * profile copy or none. The profile copy is kept equal to the id.
     */
    public synchronized void load(Callable<Filepath> dataPath)
    {
        if (clientUniqueId != null) return;
        String profile = configManager.getConfiguration(GROUP, KEY);
        String id;
        try
        {
            id = readOrCreate(idFile(dataPath), profile);
        }
        catch (Exception e)
        {
            log.warn("PvP Leaderboard could not read its id file: {}", e.toString());
            id = blank(profile) ? null : profile;
        }
        if (id != null && !id.equals(profile)) configManager.setConfiguration(GROUP, KEY, id);
        clientUniqueId = id;
    }

    public String getClientUniqueId()
    {
        return clientUniqueId;
    }

    /** The plugin's data path (the old id file, renamed there by RuneLite), or {@code id} inside it if it is a folder.
     *  Asked twice: another client may have renamed the old file at the same moment. */
    private static Filepath idFile(Callable<Filepath> dataPath) throws Exception
    {
        Filepath p;
        try
        {
            p = dataPath.call();
        }
        catch (IOException e)
        {
            p = dataPath.call();
        }
        return p.isDirectory() ? p.joinSegment("id") : p;
    }

    private static String readOrCreate(Filepath file, String profile) throws IOException
    {
        if (file.exists())
        {
            String id = read(file);
            if (!id.isEmpty())
            {
                return id;
            }
            if (blank(profile) && Duration.between(file.getLastModifiedTime().toInstant(), Instant.now()).getSeconds() < FRESH_SECS)
            {
                throw new IOException("blank id file, possibly still being written");
            }
            String fresh = fresh(profile);
            file.write(fresh);
            return fresh;
        }
        String fresh = fresh(profile);
        try
        {
            file.write(fresh, StandardOpenOption.CREATE_NEW);
            return fresh;
        }
        catch (FileAlreadyExistsException e)
        {
            String id = read(file);
            if (id.isEmpty()) throw new IOException("blank id file, still being written");
            return id;
        }
    }

    private static String read(Filepath file) throws IOException
    {
        try (InputStream in = file.openInputStream())
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static String fresh(String profile)
    {
        return blank(profile) ? UUID.randomUUID().toString() : profile;
    }

    private static boolean blank(String s)
    {
        return s == null || s.isEmpty();
    }
}
