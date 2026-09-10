# Play SCADA + MCP + WebMCP

[Documentation website](https://hackinvent.github.io/scada-webmcp/) · [Getting started](docs/getting-started.md) · [Architecture](docs/architecture.md)

A reusable supervision core built with **Play Java 3.0.11 / Java 17+**, connected to OPC UA. A shared tag and command catalog powers HTML views, the MCP server, and each page's WebMCP tools.

The browser integration uses [`HackInvent/play-webmcp` 0.5.0](https://github.com/HackInvent/play-webmcp), resolved from its Maven branch. The project depends on the published module rather than copying its source code.

## Run the demo

Requirements: JDK 17 or 21 and sbt. Node.js 20+ is only required for browser and MCP tests.

```bash
./scripts/dev.sh
```

Open **http://127.0.0.1:9000**. The OPC UA simulator listens at `opc.tcp://127.0.0.1:12686/scada`. The script binds the demo to the local machine. If Java 11 is your default:

```bash
SCADA_JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./scripts/dev.sh
```

The page displays tank temperature, pump status, and a temperature setpoint adjustable from 10 to 80 °C. Buttons, forms, and WebMCP tools use the same Java services. Measurements come from a **real OPC UA connection** to the simulator.

Anonymous operator access is available only when the simulator is enabled **and** the HTTP request comes from a loopback address. Network deployments require configured authentication tokens. The tank level drawing is illustrative; the demo does not provide a level measurement.

## Modules

| Module | Responsibility |
| --- | --- |
| `modules/scada-core` | Java model, catalog, command validation, tag state, and deduplication |
| `modules/scada-opcua` | Eclipse Milo 1.1.6 client, subscriptions, reconnection, certificates, and simulator |
| `modules/play-scada` | Play services and controllers, MCP server, HTML/WebMCP client, and optional OpenAI assistant |
| `app`, `conf`, `public` | Example HMI application that integrators can replace with their own views |

The MCP server and browser share application-level identifiers. Each tag identifier maps to an OPC UA `NodeId` in `conf/application.conf`. Commands target tags explicitly configured as writable.

## Build your own HMI

Define `scada.tags` and `scada.commands`, import the `play-scada` module, mount its controllers, and write your Twirl views. The reusable browser client binds `data-scada-value` and `data-scada-command` elements, receives measurements, supplies the CSRF token, and registers tools through `play-webmcp`.

See the [integration guide](docs/integration.md). The project is currently distributed as source; `sbt 'core/publishLocal' 'opcua/publishLocal' 'playScada/publishLocal'` publishes all three artifacts locally for use by other projects. The SCADA core is not currently published to a public Maven repository.

## MCP server

Endpoint: **`POST /mcp`**, using **Streamable HTTP** with stateless JSON responses.

| Tool | Purpose |
| --- | --- |
| `scada_list_tags` | Tag catalog, data types, and units |
| `scada_read_tags` | OPC UA readings, quality, and timestamps; optional `ids` filter |
| `scada_list_commands` | Command catalog and limits |
| `scada_execute_command` | Execute a configured command; requires operator permission and a request identifier |

Example configuration for an MCP client that supports HTTP servers:

```json
{
  "mcpServers": {
    "scada": {
      "url": "http://127.0.0.1:9000/mcp"
    }
  }
}
```

Outside the local demo, supply `Authorization: Bearer <token>` using your client's configuration format. Readers do not receive the execution tool in `tools/list`; direct calls to that tool are also rejected by the server.

Supported MCP versions are `2025-11-25`, `2025-06-18`, and `2025-03-26`. Initialization negotiates the version; subsequent requests can include `MCP-Protocol-Version`. Requests must include both `application/json` and `text/event-stream` in `Accept`; the server returns JSON responses. `GET /mcp` and `DELETE /mcp` return 405 because the server provides neither an MCP SSE stream nor session state. Notifications receive 202. OAuth, the legacy SSE transport, deferred tasks, and MCP resources are not implemented.

For commands, supply a stable `requestId` argument or the HTTP `Idempotency-Key` header. An `accepted` response means **the OPC UA server acknowledged the write**. Read the measurements to verify its effect on the process.

## External OPC UA server

Disable the simulator and configure the server, certificates, tags, and actual commands. See the [OPC UA guide](docs/opcua.md).

```bash
export SCADA_SIMULATOR_ENABLED=false
export SCADA_OPCUA_ENDPOINT=opc.tcp://your-server:4840/path
# Set SCADA_OPERATOR_TOKEN or SCADA_READER_TOKEN through your secrets manager.
# Configure the PKI and catalog in application.conf.
```

Reader and operator tokens are deployment secrets and must remain outside source control. The HMI can exchange a token for a signed session, which expires after eight hours by default. In production, supply `APPLICATION_SECRET`, enable `SESSION_SECURE=true` behind HTTPS, and configure `play.filters.hosts.allowed` for the host being served. To replace the built-in authentication, bind a custom `ScadaAccess` implementation through Guice.

## Optional OpenAI assistant

Configure **`OPENAI_API_KEY` and `OPENAI_MODEL`** on the server. The assistant uses the Responses API and executes tool calls in the backend; the key is never sent to the browser. The HMI, OPC UA, MCP, and WebMCP work without this configuration.

The assistant in this version is **read-only**. Operators can still issue commands through the HMI and MCP. Both the assistant's tool catalog and its execution checks exclude writes. Questions, tool definitions, and retrieved results are sent to OpenAI. Requests use `store=false`, which does not disable all provider data-retention policies. Model selection is a deployment choice.

## Verification

```bash
sbt test
npm ci
npx playwright install chromium
# With the demo already running:
npm run test:browser
npm run test:mcp
```

Java tests cover real OPC UA TCP exchanges, reconnection, certificate rejection, command permissions and limits, concurrent idempotency, the MCP protocol, and an OpenAI tool loop against a local HTTP test provider. The five browser tests cover the HMI without a WebMCP API, a minimal HTML client, and handling a lost write response. The WebMCP scenario uses an injected API to verify the integration contract. MCP tests use the official client SDK.

Calling a real OpenAI model requires deployment credentials; automated tests do not consume OpenAI credits.

## Scope of this first version

- One OPC UA endpoint per runtime. Supported command types are Boolean, Double, Float, Int32, and String. The connector also exposes node browsing for future integration features.
- Tag and command catalogs are configured in HOCON. Historical storage, OPC UA alarms, and high availability are not implemented.
- Command deduplication is in-memory, with capacity for 4,096 identifiers and a minimum retention period of 30 minutes. It does not survive a restart or provide a durable audit trail. Accepted commands are logged on the server; the page's command journal is a local visual aid.
- The server validates types, ranges, and permissions. Physical interlocks and safety sequences remain in the PLC; this project does not implement a safety controller.
- WebMCP availability depends on browser and agent capabilities. The HMI remains usable without WebMCP or OpenAI.
