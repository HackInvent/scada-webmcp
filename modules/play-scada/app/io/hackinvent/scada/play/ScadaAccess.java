package io.hackinvent.scada.play;

import com.typesafe.config.Config;
import play.mvc.Http;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Demo permission is confined to loopback while the embedded simulator is enabled. */
@Singleton
public class ScadaAccess {
    private final Config config;
    @Inject public ScadaAccess(Config config) { this(config, true); }

    /**
     * Authentication extensions may call {@code super(config, false)} when they replace
     * {@link #canRead}, {@link #isOperator}, and {@link #authenticate} with their own identity
     * provider. Bind the subclass to {@code ScadaAccess} in a Guice module. Passing false only
     * skips built-in token configuration validation; it grants no access by itself.
     *
     * @param config application configuration
     * @param requireBuiltInTokens true for the built-in bearer-token/session authentication
     */
    protected ScadaAccess(Config config, boolean requireBuiltInTokens) {
        this.config = java.util.Objects.requireNonNull(config);
        if (requireBuiltInTokens && !config.getBoolean("scada.simulator.enabled")
            && config.getString("scada.security.reader-token").isBlank()
            && config.getString("scada.security.operator-token").isBlank())
            throw new IllegalArgumentException("Configure SCADA_READER_TOKEN or SCADA_OPERATOR_TOKEN for built-in authentication");
    }
    public boolean isLocalDemo(Http.Request request) {
        String remote = request.remoteAddress();
        return config.getBoolean("scada.simulator.enabled") && config.getBoolean("scada.security.local-demo-access")
            && (remote.equals("127.0.0.1") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1"));
    }
    public boolean canRead(Http.Request request) {
        return isLocalDemo(request) || isOperator(request) || tokenMatches(bearer(request), "reader-token") || sessionRole(request).equals("reader");
    }
    public boolean isOperator(Http.Request request) {
        return isLocalDemo(request) || tokenMatches(bearer(request), "operator-token") || sessionRole(request).equals("operator");
    }
    public String authenticate(String token) {
        if (tokenMatches(token, "operator-token")) return "operator";
        if (tokenMatches(token, "reader-token")) return "reader";
        return "";
    }
    private String sessionRole(Http.Request request) {
        try {
            long expiry = Long.parseLong(request.session().get("scada.expires").orElse("0"));
            if (expiry <= Instant.now().getEpochSecond()) return "";
            return request.session().get("scada.role").orElse("");
        } catch (NumberFormatException e) { return ""; }
    }
    public long sessionExpiry() { return Instant.now().plus(config.getDuration("scada.security.session-duration")).getEpochSecond(); }
    private String bearer(Http.Request request) {
        String header = request.header("Authorization").orElse("");
        return header.startsWith("Bearer ") ? header.substring(7) : "";
    }
    private boolean tokenMatches(String token, String key) {
        String configured = config.getString("scada.security." + key);
        return token != null && !configured.isBlank() && MessageDigest.isEqual(
            configured.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
    public boolean isAllowedOrigin(Http.Request request) {
        String origin = request.header("Origin").orElse(null);
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            String scheme = request.secure() ? "https" : "http";
            return scheme.equals(uri.getScheme()) && request.host().equalsIgnoreCase(uri.getRawAuthority())
                && uri.getRawUserInfo() == null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
}
