package controllers;

import io.hackinvent.scada.play.ScadaRuntime;
import javax.inject.Inject;
import play.filters.csrf.AddCSRFToken;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

/** A consuming application only supplies its own HTML supervision view. */
public final class HomeController extends Controller {
    private final ScadaRuntime runtime;

    @Inject public HomeController(ScadaRuntime runtime) {
        this.runtime = runtime;
    }

    @AddCSRFToken
    public Result index(Http.Request request) {
        return ok(views.html.index.render(runtime.browserTools(), request.asScala()));
    }
}
