package io.hackinvent.scada.play;

import com.fasterxml.jackson.databind.JsonNode;
import io.hackinvent.scada.core.ScadaException;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.util.ByteString;
import play.api.http.SessionConfiguration;
import play.api.libs.crypto.CSRFTokenSigner;
import play.filters.csrf.CSRF;
import play.filters.csrf.CSRFActionHelper;
import play.filters.csrf.CSRFConfig;
import play.libs.Json;
import play.mvc.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Singleton
public final class ScadaController extends Controller {
    private final ScadaRuntime runtime;
    private final ScadaAccess access;
    private final ScadaAssistant assistant;
    @Inject public ScadaController(ScadaRuntime runtime, ScadaAccess access, ScadaAssistant assistant) {
        this.runtime = runtime; this.access = access; this.assistant = assistant;
    }
    public Result catalog(Http.Request request) {
        if (!access.canRead(request)) return unauthorized(error("unauthorized", "Authentification requise"));
        return ok(runtime.catalog(access.isOperator(request))).withHeader("Cache-Control", "no-store");
    }
    public CompletionStage<Result> snapshot(Http.Request request) {
        if (!access.canRead(request)) return CompletableFuture.completedFuture(unauthorized(error("unauthorized", "Authentification requise")));
        return respond(runtime::snapshot);
    }
    public Result events(Http.Request request) {
        if (!access.canRead(request)) return unauthorized(error("unauthorized", "Authentification requise"));
        Source<ByteString, ?> events = Source.tick(Duration.ZERO, Duration.ofSeconds(1), "tick")
            .takeWhile(ignored -> access.canRead(request))
            .mapAsync(1, ignored -> runtime.snapshot())
            .map(value -> ByteString.fromString("event: snapshot\ndata: " + Json.stringify(value) + "\n\n"));
        return ok().chunked(events).as("text/event-stream")
            .withHeader("Cache-Control", "no-cache, no-store").withHeader("X-Accel-Buffering", "no");
    }
    @With(BrowserCsrfCheck.class)
    public CompletionStage<Result> command(Http.Request request, String id) {
        if (!access.isAllowedOrigin(request)) return CompletableFuture.completedFuture(forbidden(error("origin_rejected", "Origine refusée")));
        return respond(() -> runtime.executeCommand(id, request.body().asJson(), access.isOperator(request),
            request.header("Idempotency-Key").orElse(null)));
    }
    @With(BrowserCsrfCheck.class)
    public Result login(Http.Request request) {
        if (!access.isAllowedOrigin(request)) return forbidden(error("origin_rejected", "Origine refusée"));
        JsonNode json = request.body().asJson();
        if (json == null || !json.path("token").isTextual() || json.path("token").asText().length() > 4096)
            return badRequest(error("invalid_arguments", "Jeton requis"));
        String role = access.authenticate(json.get("token").asText());
        if (role.isEmpty()) return unauthorized(error("unauthorized", "Jeton invalide"));
        return ok(Json.newObject().put("role", role))
            .withSession(request.session().adding("scada.role", role).adding("scada.expires", Long.toString(access.sessionExpiry())))
            .withHeader("Cache-Control", "no-store");
    }
    @With(BrowserCsrfCheck.class)
    public Result logout(Http.Request request) {
        if (!access.isAllowedOrigin(request)) return forbidden(error("origin_rejected", "Origine refusée"));
        return ok(Json.newObject().put("loggedOut", true))
            .withSession(request.session().removing("scada.role").removing("scada.expires"));
    }
    @With(BrowserCsrfCheck.class)
    public CompletionStage<Result> assistant(Http.Request request) {
        if (!access.canRead(request)) return CompletableFuture.completedFuture(unauthorized(error("unauthorized", "Authentification requise")));
        if (!access.isAllowedOrigin(request)) return CompletableFuture.completedFuture(forbidden(error("origin_rejected", "Origine refusée")));
        return respond(() -> {
            JsonNode json = request.body().asJson(); ScadaRuntime.requireObject(json);
            return assistant.ask(ScadaRuntime.requiredText(json, "message"));
        });
    }
    /**
     * Browser mutations always require a valid Play CSRF token, including local demo
     * requests with no Cookie/Authorization header. Play's default RequireCSRFCheck
     * retains that header-based exemption, which is inappropriate for demo access.
     */
    public static final class BrowserCsrfCheck extends Action.Simple {
        private final CSRFActionHelper helper;
        private final CSRF.TokenProvider tokens;
        private final CSRFConfig config;

        @Inject public BrowserCsrfCheck(SessionConfiguration session, CSRFConfig config,
                                       CSRFTokenSigner signer, CSRF.TokenProvider tokens) {
            this.helper = new CSRFActionHelper(session, config, signer, tokens);
            this.tokens = tokens;
            this.config = config;
        }

        @Override public CompletionStage<Result> call(Http.Request request) {
            var expected = helper.getTokenToValidate(request.asScala());
            var header = helper.getHeaderToken(request.asScala());
            String supplied = header.isDefined() ? header.get() : null;
            if (supplied == null && request.body().asFormUrlEncoded() != null) {
                String[] values = request.body().asFormUrlEncoded().get(config.tokenName());
                if (values != null && values.length == 1) supplied = values[0];
            }
            if (expected.isDefined() && supplied != null && tokens.compareTokens(expected.get(), supplied)) {
                return delegate.call(request);
            }
            return CompletableFuture.completedFuture(forbidden(error("csrf_rejected", "Jeton CSRF absent ou invalide"))
                .withHeader("Cache-Control", "no-store"));
        }
    }

    private CompletionStage<Result> respond(Supplier<CompletionStage<JsonNode>> operation) {
        try { return operation.get().handle((value, failure) -> failure == null
            ? ok(value).withHeader("Cache-Control", "no-store") : failure(failure)); }
        catch (RuntimeException failure) { return CompletableFuture.completedFuture(failure(failure)); }
    }
    private Result failure(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        if (failure instanceof ScadaException error) return status(error.getStatus(), error(error.getCode(), error.getMessage()));
        return status(502, error("backend_unavailable", "Le service est indisponible. Vérifiez son état avant de réessayer une commande."));
    }
    private static JsonNode error(String code, String message) {
        return Json.newObject().put("error", code).put("message", message);
    }
}
