# Build a Play Java HMI

## Dependency and configuration

The repository contains a demo application and three reusable libraries. To publish them to your local Ivy repository:

```bash
sbt 'core/publishLocal' 'opcua/publishLocal' 'playScada/publishLocal'
```

In a **PlayJava 3.x** application using Java 17+ and Scala 2.13:

```scala
resolvers += "HackInvent play-webmcp" at "https://raw.githubusercontent.com/HackInvent/play-webmcp/maven"
libraryDependencies += "io.github.hackinvent" %% "play-scada" % "0.1.0-SNAPSHOT"
```

`play-scada` includes `scada-core`, `scada-opcua`, and `io.github.alexusel:play-webmcp_2.13:0.5.0` as transitive dependencies. The consuming application supplies its own views, application controllers, and styles. Application code and SCADA modules are written in Java; `build.sbt` and Twirl templates use Play's standard syntax.

Load the module defaults in `application.conf`:

```hocon
include "scada-reference.conf"
```

Then configure the OPC UA server, PKI, and `scada.tags` / `scada.commands` lists. Use the demo catalog only with the simulator; your project must define the identifiers and ranges appropriate to its actual equipment.

## Routes and tool catalog

Mount the `/api/scada/*` and `/mcp` routes shown in [`conf/routes`](../conf/routes). Browser mutations use an explicit CSRF action that requires a valid Play token even without a `Cookie` or `Authorization` header, including in the local demo. The client sends the token in `Csrf-Token` along with the session cookie. Keep Play's CSRF protections enabled. Your page controller can inject `io.hackinvent.scada.play.ScadaRuntime` and pass `runtime.browserTools()` to its view. Add `@AddCSRFToken` to the GET action.

Place the `+ nocsrf` modifier only before the `POST /mcp` route, as shown in the provided routes file. Remote MCP clients use Bearer authentication without a browser CSRF token. The MCP controller retains its origin and authentication checks. Do not apply this modifier to command, session, or assistant routes.

```java
@AddCSRFToken
public Result index(Http.Request request) {
    return ok(views.html.index.render(runtime.browserTools(), request.asScala()));
}
```

Guice injects components through their Java constructors. `ScadaRuntime` starts the connection and stops it with the Play application lifecycle. To integrate your application's identity provider, subclass `ScadaAccess` and bind it through Guice. Controllers call `canRead`, `isOperator`, and `isAllowedOrigin` for each request. The protected constructor `super(config, false)` lets you replace token authentication without configuring placeholder tokens. Also override `authenticate` if you retain the session creation route.

## Minimal HTML view

A view can consist of HTML elements and client initialization. Example Twirl template:

```html
@(tools: java.util.List[playwebmcp.Tool])(implicit request: play.api.mvc.RequestHeader)
@import playwebmcp.javadsl.WebMcp
@import scala.jdk.CollectionConverters._

<div hidden>@helper.CSRF.formField</div>
<output data-scada-value="tank.temperature" data-scada-unit="°C">—</output>
<span data-scada-quality="tank.temperature"></span>
<time data-scada-timestamp="tank.temperature"></time>
<button type="button" data-scada-command="pump.start">Start pump</button>
<button type="button" data-scada-command="pump.stop">Stop pump</button>

<input id="setpoint" type="number" min="10" max="80" value="24">
<button type="button" data-scada-command="tank.setpoint"
        data-scada-input="#setpoint">Apply setpoint</button>

@for(tool <- tools.asScala) { @WebMcp.tool(tool) }
<script defer src="@controllers.routes.Assets.versioned("lib/play-webmcp/play-webmcp.global.js")"></script>
<script defer src="@controllers.routes.Assets.versioned("lib/play-scada/play-scada.js")"></script>
<script defer src="@controllers.routes.Assets.versioned("javascripts/my-hmi.js")"></script>
```

In `my-hmi.js`:

```javascript
const client = PlayScada.createClient({
  baseUrl: '/api/scada/',
  locale: 'en',
  onError(error) { console.error(error.message); }
});
client.start({ root: document }).catch(error => console.error(error.message));
window.addEventListener('pagehide', event => {
  if (!event.persisted) client.dispose().catch(error => console.error(error.message));
});
```

Adjust `baseUrl` if the application is mounted under a path prefix; the URL must share the page's origin. Checking `event.persisted` keeps the client alive when the browser caches the page for back/forward navigation. The client reads Play's CSRF field, updates bound elements, receives the measurement stream, and registers tools with the `play-webmcp` runtime. The HMI remains usable when WebMCP is unavailable. Supply a `confirmCommand` callback to customize the confirmation dialog; permission checks and final validation run on the server.

## Browser client API

| Method | Purpose |
| --- | --- |
| `start({root, bindCommands})` | Load the catalog and measurements, then start acquisition and WebMCP |
| `refreshCatalog()` / `refreshSnapshot()` | Refresh the page's data |
| `listTags(args, context)` / `readTags({ids}, context)` | Query tag definitions and measurements |
| `listCommands(args, context)` | Describe available commands |
| `executeCommand({commandId, value, requestId}, context)` | Confirm and send a command without automatic retries |
| `openSession(token)` / `closeSession()` | Open or close a server session |
| `askAssistant(message)` | Send a question to the optional assistant |
| `registerTools({root})` / `bind(root)` | Integrate HTML components |
| `dispose()` | Stop streams and unregister this client's tools |

Use the `onCatalog`, `onSnapshot`, `onCommand`, and `onError` callbacks to customize presentation. The WebMCP tool parameter `context.signal` is forwarded to requests. A network interruption after a write can leave its outcome unknown; the client reads the state again but never automatically resends the command.

## Data and commands

Each measurement retains `value`, `quality`, `statusCode`, `sourceTimestamp`, `serverTimestamp`, and `receivedAt`. The snapshot can replace the displayed quality with `STALE` when the connection is lost or acquisition is too old; the original OPC UA status code and timestamps are preserved.

Commands have a stable identifier, a target tag, and either a fixed value or a typed argument with bounds. `ScadaEngine` rejects unknown or read-only tags, incorrect types, non-finite numbers, out-of-range values, callers with reader permissions, and inconsistent reuse of request identifiers.

The browser and MCP use this same engine. In this version, the OpenAI API receives only read tools. HMI controls and WebMCP annotations never grant additional server permissions.

The `scada.openai.timeout` setting bounds the entire assistant request, including model calls, tool rounds, and OPC UA reads. Exceeding the deadline ends the request with `assistant_timeout` (HTTP 504); application shutdown cancels pending requests.
