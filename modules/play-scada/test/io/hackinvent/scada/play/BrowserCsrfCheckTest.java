package io.hackinvent.scada.play;

import org.junit.Before;
import org.junit.Test;
import play.api.http.HttpConfiguration;
import play.api.http.SecretConfiguration;
import play.api.libs.crypto.DefaultCSRFTokenSigner;
import play.api.libs.crypto.DefaultCookieSigner;
import play.filters.csrf.CSRF;
import play.filters.csrf.CSRFConfig;
import play.libs.Json;
import play.mvc.Action;
import play.mvc.Http;
import play.mvc.Result;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class BrowserCsrfCheckTest {
    private DefaultCSRFTokenSigner signer;
    private CSRF.TokenProvider tokens;
    private AtomicInteger calls;

    @Before public void setup() {
        signer = new DefaultCSRFTokenSigner(new DefaultCookieSigner(
                new SecretConfiguration("isolated-csrf-unit-test-secret-0123456789", scala.Option.empty())), Clock.systemUTC());
        tokens = new CSRF.SignedTokenProvider(signer);
        calls = new AtomicInteger();
    }

    @Test public void anonymousJsonRequiresTokenEvenWhenPlayWouldBypassIt() throws Exception {
        CSRFConfig config = new CSRFConfig().withShouldProtect(request -> false).withContentTypes(type -> false);
        assertEquals(403, check(config, request()).status());
        assertEquals(403, check(config, request().header("Csrf-Token", tokens.generateToken())).status());
        assertEquals(0, calls.get());
    }

    @Test public void validSignedSessionAndHeaderReachActionOnce() throws Exception {
        String token = tokens.generateToken();
        // Play randomizes the signature per response; compare the underlying token, not literal text.
        String resigned = signer.signToken(signer.extractSignedToken(token).get());
        Result result = check(new CSRFConfig(), request().session("csrfToken", token).header("Csrf-Token", resigned));
        assertEquals(204, result.status());
        assertEquals(1, calls.get());
    }

    @Test public void missingMismatchedAndForgedTokensNeverReachAction() throws Exception {
        String token = tokens.generateToken();
        assertEquals(403, check(new CSRFConfig(), request().session("csrfToken", token)).status());
        assertEquals(403, check(new CSRFConfig(), request().session("csrfToken", token)
                .header("Csrf-Token", tokens.generateToken())).status());
        assertEquals(403, check(new CSRFConfig(), request().session("csrfToken", "forged")
                .header("Csrf-Token", "forged")).status());
        assertEquals(0, calls.get());
    }

    @Test public void configuredCsrfCookieAndCustomHeaderRemainSupported() throws Exception {
        String token = tokens.generateToken();
        CSRFConfig config = new CSRFConfig().withCookieName(Optional.of("csrf-cookie")).withHeaderName("X-Scada-Csrf");
        Result result = check(config, request().cookie(Http.Cookie.builder("csrf-cookie", token).build())
                .header("X-Scada-Csrf", token));
        assertEquals(204, result.status());
        assertEquals(1, calls.get());
    }

    private Result check(CSRFConfig config, Http.RequestBuilder request) throws Exception {
        var guard = new ScadaController.BrowserCsrfCheck(HttpConfiguration.createWithDefaults().session(), config, signer, tokens);
        guard.delegate = new Action.Simple() {
            @Override public CompletionStage<Result> call(Http.Request request) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(noContent());
            }
        };
        return guard.call(request.build()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Http.RequestBuilder request() {
        return new Http.RequestBuilder().method("POST").uri("/api/scada/commands/pump.start")
                .remoteAddress("127.0.0.1").bodyJson(Json.newObject());
    }
}
