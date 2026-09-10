---
description: Module boundaries, data flow, command validation, and the current scope of the reusable SCADA core.
---
# Architecture and scope

Play SCADA shares one configured tag and command catalog across the Java runtime, HTML HMI, MCP server, and WebMCP tools. Your application supplies equipment definitions and views; the modules provide acquisition, validation, access checks, and integration services.

<img class="architecture-map" src="/architecture.svg" alt="An OPC UA server connects through Eclipse Milo to a shared Play Java core, which serves an HTML HMI with WebMCP, MCP clients, and an optional read-only assistant." />

## Module boundaries

| Module | Main responsibilities |
| --- | --- |
| `scada-core` | Java records and connector contract, tag state, type and range validation, command permissions, request deduplication |
| `scada-opcua` | Eclipse Milo client, server discovery, namespace resolution, reads, subscriptions, writes, reconnection, certificates, simulator |
| `play-scada` | Play lifecycle, configuration, HTTP controllers, MCP transport, browser client, optional OpenAI adapter |
| Demo application | Twirl views, presentation JavaScript and CSS, example equipment catalog, route mounting |

The core and OPC UA libraries are ordinary Java projects. The Play module and demo use PlayJava 3.0.11, with Twirl and sbt using the framework's standard Scala syntax.

## Acquisition path

1. `ScadaRuntime` loads `scada.tags` and `scada.commands` and creates the connector.
2. `MiloScadaConnector` connects to the configured OPC UA endpoint and resolves namespace URIs.
3. Subscriptions feed measurements into the core. A periodic read refreshes configured tags every three seconds by default, including unchanged values.
4. The HTTP snapshot and SSE endpoints expose the cached state to the browser. The MCP `scada_read_tags` tool performs a read through the shared engine.

Each measurement carries `value`, `quality`, `statusCode`, `sourceTimestamp`, `serverTimestamp`, and `receivedAt`. The cached snapshot marks values `STALE` when disconnected or older than `scada.stale-after`, which defaults to ten seconds. Source timestamps still identify when the server says the measurement originated.

## Command path

Commands address configured application identifiers such as `pump.start`; each definition maps to a writable tag. The browser and MCP call the same engine, which checks permission, the target tag, value type, configured bounds, and the operation's request identifier before writing through OPC UA.

The browser asks for confirmation and sends a CSRF token. The MCP controller checks the caller's identity and tool permissions. Neither a browser tool declaration nor a confirmation dialog grants additional server rights.

Deduplication uses a stable `requestId` or `Idempotency-Key`. A repeated identifier with the same operation returns the stored result; conflicting reuse is rejected. An interrupted write can have an unknown outcome, so the client does not automatically resend it.

An `accepted` result acknowledges the OPC UA write. It does not confirm that a physical operation has completed.

## Integration paths

| Integration | Entry point | Execution |
| --- | --- | --- |
| HTML HMI | `PlayScada.createClient()` | Same-origin HTTP and SSE to Play |
| Browser agent | `play-webmcp` tool registration | Browser handlers use the same HMI client |
| Remote MCP client | `POST /mcp` | Stateless Streamable HTTP to Play tools |
| Optional assistant | `POST /api/scada/assistant` | Server calls OpenAI and executes allowed read tools locally |

## Current scope

- One OPC UA endpoint per runtime.
- A configured semantic catalog; node browsing is available in the connector but is not exposed as a general-purpose MCP tool.
- Command types: Boolean, Double, Float, Int32, and String.
- In-memory command deduplication with capacity for 4,096 identifiers and a minimum retention of 30 minutes. It does not survive application restarts.
- Server logs for accepted commands and a local page journal. A durable audit trail is not implemented.
- No historian, OPC UA alarm integration, or high-availability coordination.
- Physical interlocks and safety sequences remain in the PLC.

Use these boundaries when planning an integration. Historical storage, durable command tracking, and additional endpoint support require application or module extensions.
