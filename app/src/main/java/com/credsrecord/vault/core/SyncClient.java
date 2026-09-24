package com.credsrecord.vault.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Synchronous HTTP client for the vault API. Invoke its methods from a background thread. */
public final class SyncClient {
    public static final int MAX_RESPONSE_BYTES = 13 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private final String baseUrl;

    /**
     * Creates a client for a service origin (optionally including a base path).
     * HTTPS is required except for localhost, 127.0.0.1, and 10.0.2.2 development URLs.
     */
    public SyncClient(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Base URL must not be empty.");
        }
        try {
            URI uri = URI.create(baseUrl.trim()).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Base URL must be an absolute HTTP(S) URL without credentials, query, or fragment.");
            }
            boolean https = "https".equalsIgnoreCase(scheme);
            boolean localHttp = "http".equalsIgnoreCase(scheme)
                    && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "10.0.2.2".equals(host));
            if (!https && !localHttp) {
                throw new IllegalArgumentException("HTTPS is required except for localhost, 127.0.0.1, or 10.0.2.2 development URLs.");
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            this.baseUrl = normalized;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid or insecure API base URL: " + e.getMessage(), e);
        }
    }

    /** Registers an account; the server returns token, revision, and vault. */
    public JSONObject register(String username, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        return request("POST", "/api/register", null, body);
    }

    /** Authenticates an account; the server returns token, revision, and vault. */
    public JSONObject login(String username, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        return request("POST", "/api/login", null, body);
    }

    /** Fetches the current revision and encrypted vault envelope. */
    public JSONObject getVault(String token) throws Exception {
        return request("GET", "/api/vault", token, null);
    }

    /** Uploads an encrypted vault envelope using optimistic revision checking. */
    public JSONObject putVault(String token, int revision, String envelope) throws Exception {
        if (envelope == null) {
            throw new IllegalArgumentException("Vault envelope must not be null.");
        }
        JSONObject body = new JSONObject();
        body.put("revision", revision);
        body.put("vault", envelope);
        return request("PUT", "/api/vault", token, body);
    }

    private JSONObject request(String method, String path, String token, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");

            if (token != null) {
                if (token.isEmpty()) {
                    throw new IllegalArgumentException("Bearer token must not be empty.");
                }
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(requestBytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(requestBytes);
                    output.flush();
                }
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            byte[] responseBytes = readBounded(responseStream);
            String responseText = new String(responseBytes, StandardCharsets.UTF_8);

            if (status == HttpURLConnection.HTTP_CONFLICT) {
                throw createConflict(responseText);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Vault API request failed with HTTP " + status
                        + (responseText.isEmpty() ? "." : ": " + responseText));
            }
            try {
                return new JSONObject(responseText);
            } catch (JSONException e) {
                throw new IOException("Vault API returned an invalid JSON object.", e);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > MAX_RESPONSE_BYTES - total) {
                    throw new IOException("Vault API response exceeds the 13 MiB wire size limit.");
                }
                total += count;
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static ConflictException createConflict(String responseText) {
        int revision = -1;
        String vault = null;
        try {
            JSONObject body = new JSONObject(responseText);
            revision = body.optInt("revision", body.optInt("serverRevision", -1));
            Object vaultValue = body.opt("vault");
            if (!(vaultValue instanceof String)) {
                vaultValue = body.opt("serverVault");
            }
            if (vaultValue instanceof String) {
                vault = (String) vaultValue;
            }
        } catch (JSONException ignored) {
            // Preserve the raw server response on the exception when the body isn't JSON.
        }
        return new ConflictException(revision, vault, responseText);
    }

    /** HTTP 409 revision conflict, with the server's current revision and vault if supplied. */
    public static final class ConflictException extends Exception {
        public final int serverRevision;
        public final String serverVault;
        private final String responseBody;

        public ConflictException(int serverRevision, String serverVault, String responseBody) {
            super("Vault revision conflict (server revision " + serverRevision + ").");
            this.serverRevision = serverRevision;
            this.serverVault = serverVault;
            this.responseBody = responseBody;
        }

        public int getServerRevision() {
            return serverRevision;
        }

        public String getServerVault() {
            return serverVault;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
