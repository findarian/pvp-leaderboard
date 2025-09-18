package com.pvp.leaderboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

final class HttpUtil
{
    private HttpUtil() {}

    static String readResponseBody(HttpURLConnection connection) throws IOException
    {
        int status = 0;
        try { status = connection.getResponseCode(); } catch (IOException ignore) {}
        InputStream stream = (status >= 200 && status < 300) ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)))
        {
            String line; while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    static String toUserMessage(int status, String rawBody)
    {
        if (status == 401 || status == 403) return "Not authorized. Please login.";
        if (status == 404) return "Not found.";
        if (status == 429) return "Too many requests. Try again later.";
        if (status >= 400 && status < 500) return "Request error (" + status + ").";
        if (status >= 500) return "Server error (" + status + "). Try again later.";
        return "Network error.";
    }
}


