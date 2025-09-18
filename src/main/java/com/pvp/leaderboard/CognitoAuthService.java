package com.pvp.leaderboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.client.config.ConfigManager;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public class CognitoAuthService {
    private static final String COGNITO_DOMAIN = "osrs-mmr-a8959e04.auth.us-east-1.amazoncognito.com";
    private static final String CLIENT_ID = "5ho4mj5d17v44s4vavnkmp2mmo";
    private static final String REDIRECT_URI = "http://127.0.0.1:49215/callback";
    private static final int CALLBACK_PORT = 49215;
    
    private final ConfigManager configManager;
    private String transientVerifier;
    private String transientState;
    // Local HTTP server for OAuth callback
    private HttpServer httpServer;
    private String accessToken;
    private String idToken;
    private String refreshToken;
    private long tokenExpiry;
    
    public CognitoAuthService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    // No-arg constructor for tests or environments without ConfigManager
    public CognitoAuthService() {
        this.configManager = null;
    }

    public CompletableFuture<Boolean> login() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Generate PKCE
                String verifier = generatePKCEVerifier();
                String challenge = generatePKCEChallenge(verifier);
                if (configManager != null) {
                    configManager.setConfiguration("PvPLeaderboard", "pkce_verifier", verifier);
                } else {
                    transientVerifier = verifier;
                }

                // Start local HTTP server to capture callback
                CompletableFuture<String> codeFuture = new CompletableFuture<>();
                startCallbackServer(codeFuture);

                // Open browser to login with random OAuth state
                String state = generateRandomState();
                if (configManager != null) {
                    configManager.setConfiguration("PvPLeaderboard", "oauth_state", state);
                } else {
                    transientState = state;
                }
                String loginUrl = String.format(
                    "https://%s/login?client_id=%s&response_type=code&scope=openid+email+profile&redirect_uri=%s&code_challenge_method=S256&code_challenge=%s&state=%s",
                    COGNITO_DOMAIN, CLIENT_ID, URLEncoder.encode(REDIRECT_URI, "UTF-8"), challenge, URLEncoder.encode(state, "UTF-8")
                );
                Desktop.getDesktop().browse(URI.create(loginUrl));

                // Wait for code up to 180s
                String code = codeFuture.get(180, java.util.concurrent.TimeUnit.SECONDS);
                stopCallbackServer();
                if (code == null || code.isEmpty()) {
                    // Ensure auth artifacts are cleared on failure
                    clearTokens();
                    return false;
                }
                exchangeCodeForTokens(code.trim());
                return true;
            } catch (Exception e) {
                stopCallbackServer();
                clearTokens();
                return false;
            }
        });
    }
    
    private void startCallbackServer(CompletableFuture<String> codeFuture) throws IOException {
        if (httpServer != null) stopCallbackServer();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", CALLBACK_PORT);
        httpServer = HttpServer.create(addr, 0);
        httpServer.createContext("/callback", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    URI uri = ex.getRequestURI();
                    String query = uri.getRawQuery();
                    String code = extractParam(query, "code");
                    // Validate returned state to mitigate CSRF
                    String state = extractParam(query, "state");
                    String expectedState = null;
                    if (configManager != null) {
                        Object s = configManager.getConfiguration("PvPLeaderboard", "oauth_state");
                        expectedState = s != null ? String.valueOf(s) : null;
                    } else {
                        expectedState = transientState;
                    }
                    boolean stateOk = expectedState != null && expectedState.equals(state);
                    String html = stateOk && code != null && !code.isEmpty()
                        ? "<html><head><title>Login Complete</title></head><body style=\"background:#111;color:#ffcc00;font-family:Arial\"><h2>Login complete</h2><p>You may now return to RuneLite.</p></body></html>"
                        : "<html><head><title>Login Failed</title></head><body style=\"background:#111;color:#ff6666;font-family:Arial\"><h2>Login failed</h2><p>Invalid login state.</p></body></html>";
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
                    if (stateOk && code != null && !code.isEmpty() && !codeFuture.isDone()) {
                        codeFuture.complete(code);
                    } else if (!codeFuture.isDone()) {
                        codeFuture.complete(null);
                    }
                } catch (Exception ignore) {
                    try { if (!codeFuture.isDone()) codeFuture.complete(null); } catch (Exception ignored) {}
                }
            }
        });
        httpServer.start();
    }

    private void stopCallbackServer() {
        try { if (httpServer != null) httpServer.stop(0); } catch (Exception ignore) {}
        httpServer = null;
    }
    
    private void exchangeCodeForTokens(String code) throws Exception {
        String verifier = null;
        if (configManager != null) {
            Object v = configManager.getConfiguration("PvPLeaderboard", "pkce_verifier");
            verifier = v != null ? String.valueOf(v) : null;
        } else {
            verifier = transientVerifier;
        }
        
        // Form-encode ALL values to avoid '+' being treated as space etc.
        String postData =
            "grant_type=" + URLEncoder.encode("authorization_code", "UTF-8") +
            "&client_id=" + URLEncoder.encode(CLIENT_ID, "UTF-8") +
            "&code=" + URLEncoder.encode(code, "UTF-8") +
            "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8") +
            "&code_verifier=" + URLEncoder.encode(String.valueOf(verifier), "UTF-8");
        
        URL url = new URL("https://" + COGNITO_DOMAIN + "/oauth2/token");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(postData.getBytes(StandardCharsets.UTF_8));
        }
        
        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) {
            throw new IOException("No response from token endpoint (status=" + status + ")");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) sb.append(line);
        }
        String response = sb.toString();
        if (status < 200 || status >= 300) {
            throw new IOException("Token exchange failed (status=" + status + "): " + response);
        }
        
        JsonObject tokens = JsonParser.parseString(response).getAsJsonObject();
        accessToken = tokens.has("access_token") && !tokens.get("access_token").isJsonNull() ? tokens.get("access_token").getAsString() : null;
        idToken = tokens.has("id_token") && !tokens.get("id_token").isJsonNull() ? tokens.get("id_token").getAsString() : null;
        refreshToken = tokens.has("refresh_token") && !tokens.get("refresh_token").isJsonNull() ? tokens.get("refresh_token").getAsString() : null;
        int expiresIn = tokens.has("expires_in") ? tokens.get("expires_in").getAsInt() : 3600;
        tokenExpiry = System.currentTimeMillis() + (expiresIn * 1000L);

        // Validate ID token claims before proceeding (iss/aud/exp/token_use)
        if (idToken == null || !validateIdTokenClaims(idToken)) {
            clearTokens();
            throw new IOException("Invalid ID token claims");
        }
        
        // Clean up
        if (configManager != null) {
            configManager.unsetConfiguration("PvPLeaderboard", "pkce_verifier");
            configManager.unsetConfiguration("PvPLeaderboard", "oauth_state");
        }
        transientVerifier = null;
        transientState = null;
    }
    
    public String getAccessToken() {
        return accessToken;
    }
    

    
    public boolean isLoggedIn() {
        return accessToken != null && System.currentTimeMillis() < tokenExpiry;
    }
    
    public void logout() {
        clearTokens();
    }
    
    public String getStoredIdToken() {
        return idToken;
    }
    
    private String generatePKCEVerifier() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom random = new SecureRandom();
        StringBuilder verifier = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            verifier.append(chars.charAt(random.nextInt(chars.length())));
        }
        return verifier.toString();
    }
    
    private String generatePKCEChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).replace('+', '-').replace('/', '_');
    }
    
    private String generateRandomState() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void clearTokens() {
        accessToken = null;
        idToken = null;
        refreshToken = null;
        tokenExpiry = 0;
        try { if (configManager != null) configManager.unsetConfiguration("PvPLeaderboard", "pkce_verifier"); } catch (Exception ignore) {}
        try { if (configManager != null) configManager.unsetConfiguration("PvPLeaderboard", "oauth_state"); } catch (Exception ignore) {}
        transientVerifier = null;
        transientState = null;
    }

    private boolean validateIdTokenClaims(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return false;
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            // Required claims
            String tokenUse = payload.has("token_use") && !payload.get("token_use").isJsonNull() ? payload.get("token_use").getAsString() : null;
            String aud = payload.has("aud") && !payload.get("aud").isJsonNull() ? payload.get("aud").getAsString() : null;
            String iss = payload.has("iss") && !payload.get("iss").isJsonNull() ? payload.get("iss").getAsString() : null;
            long exp = payload.has("exp") && !payload.get("exp").isJsonNull() ? payload.get("exp").getAsLong() : 0L;
            long now = System.currentTimeMillis() / 1000L;

            if (!"id".equalsIgnoreCase(tokenUse)) return false;
            if (aud == null || !aud.equals(CLIENT_ID)) return false;
            if (iss == null || !iss.startsWith("https://cognito-idp.us-east-1.amazonaws.com/")) return false;
            if (exp <= (now - 60)) return false; // allow small skew

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    
    private String extractParam(String query, String param) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && param.equals(kv[0])) {
                try {
                    return URLDecoder.decode(kv[1], "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}
