package io.hackinvent.scada.play;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import play.mvc.Http;

import java.time.Instant;
import java.util.Map;

import static org.junit.Assert.*;

public class ScadaAccessTest {
    private static ScadaAccess access(boolean simulator, boolean localDemo) {
        Config config = ConfigFactory.parseMap(Map.of(
                "scada.simulator.enabled", simulator,
                "scada.security.local-demo-access", localDemo,
                "scada.security.reader-token", "test-reader-token",
                "scada.security.operator-token", "test-operator-token",
                "scada.security.session-duration", "15m"));
        return new ScadaAccess(config);
    }

    @Test
    public void demoAccessRequiresSimulatorAndLoopbackAndExplicitSetting() {
        ScadaAccess demo = access(true, true);
        for (String address : new String[]{"127.0.0.1", "::1", "0:0:0:0:0:0:0:1"}) {
            assertTrue(demo.isOperator(request().remoteAddress(address).build()));
        }
        Http.Request remote = request().remoteAddress("192.0.2.10").header("X-Forwarded-For", "127.0.0.1").build();
        assertFalse(demo.canRead(remote));
        assertFalse(demo.isOperator(remote));
        assertFalse(access(false, true).canRead(request().build()));
        assertFalse(access(true, false).canRead(request().build()));
    }

    @Test
    public void readerAndOperatorTokensHaveDistinctPermissions() {
        ScadaAccess secured = access(false, false);
        Http.Request reader = request().header("Authorization", "Bearer test-reader-token").build();
        assertTrue(secured.canRead(reader));
        assertFalse(secured.isOperator(reader));
        Http.Request operator = request().header("Authorization", "Bearer test-operator-token").build();
        assertTrue(secured.canRead(operator));
        assertTrue(secured.isOperator(operator));
        assertEquals("reader", secured.authenticate("test-reader-token"));
        assertEquals("operator", secured.authenticate("test-operator-token"));
        assertEquals("", secured.authenticate("incorrect"));
        assertFalse(secured.canRead(request().header("Authorization", "Basic test-operator-token").build()));
        assertFalse(secured.canRead(request().header("Authorization", "Bearer incorrect").build()));
    }

    @Test
    public void emptyConfiguredTokensCannotAuthorizeAnonymousRequests() {
        Config emptyTokens = ConfigFactory.parseMap(Map.of(
                "scada.simulator.enabled", false, "scada.security.local-demo-access", false,
                "scada.security.reader-token", "", "scada.security.operator-token", ""));
        assertThrows(IllegalArgumentException.class, () -> new ScadaAccess(emptyTokens));
        ScadaAccess secured = new ScadaAccess(emptyTokens, false) { };
        assertFalse(secured.canRead(request().build()));
        assertFalse(secured.isOperator(request().header("Authorization", "Bearer ").build()));
        assertEquals("", secured.authenticate(""));
    }

    @Test
    public void customAuthenticationDoesNotRequireDummyBearerTokens() {
        Config config = ConfigFactory.parseMap(Map.of(
                "scada.simulator.enabled", false, "scada.security.local-demo-access", false,
                "scada.security.reader-token", "", "scada.security.operator-token", ""));
        ScadaAccess custom = new ScadaAccess(config, false) {
            @Override public boolean isOperator(Http.Request request) {
                return request.session().get("company.role").orElse("").equals("operator");
            }
            @Override public boolean canRead(Http.Request request) { return isOperator(request); }
            @Override public String authenticate(String token) { return ""; }
        };
        assertTrue(custom.isOperator(request().session("company.role", "operator").build()));
        assertTrue(custom.canRead(request().session("company.role", "operator").build()));
        assertFalse(custom.canRead(request().build()));
        assertEquals("", custom.authenticate("anything"));
    }

    @Test
    public void sessionsRequireRecognizedRoleAndFutureExpiry() {
        ScadaAccess secured = access(false, false);
        String future = Long.toString(Instant.now().plusSeconds(600).getEpochSecond());
        Http.Request operator = request().session("scada.role", "operator").session("scada.expires", future).build();
        assertTrue(secured.isOperator(operator));
        Http.Request reader = request().session("scada.role", "reader").session("scada.expires", future).build();
        assertTrue(secured.canRead(reader));
        assertFalse(secured.isOperator(reader));
        for (String expiry : new String[]{"1", "not-a-date", ""}) {
            assertFalse(secured.canRead(request().session("scada.role", "operator").session("scada.expires", expiry).build()));
        }
        assertFalse(secured.canRead(request().session("scada.role", "operator").build()));
        assertFalse(secured.canRead(request().session("scada.role", "admin").session("scada.expires", future).build()));
    }

    @Test
    public void originMustExactlyMatchSchemeHostAndPortWithoutExtraUriParts() {
        ScadaAccess secured = access(false, false);
        assertTrue(secured.isAllowedOrigin(request().build()));
        assertTrue(secured.isAllowedOrigin(request().header("Origin", "http://localhost:19000").build()));
        for (String origin : new String[]{"null", "http://evil.example", "https://localhost:19000",
                "http://localhost:9000", "http://localhost:19000/", "http://localhost:19000/path",
                "http://localhost:19000?query=x", "http://localhost:19000#fragment",
                "http://user@localhost:19000", "http://localhost:19000 http://evil.example", "not a uri"}) {
            assertFalse(origin, secured.isAllowedOrigin(request().header("Origin", origin).build()));
        }
        assertTrue(secured.isAllowedOrigin(request().secure(true).header("Origin", "https://localhost:19000").build()));
    }

    private static Http.RequestBuilder request() {
        return new Http.RequestBuilder().method("POST").uri("/mcp").host("localhost:19000").remoteAddress("127.0.0.1");
    }
}
