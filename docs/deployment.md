---
description: Package the application and configure endpoint security, authentication, sessions, and network access.
---
# Deployment and access

A deployment supplies its own OPC UA endpoint, certificates, equipment catalog, application secret, and user access model. The [architecture page](architecture.md#current-scope) describes the current runtime limits.

## Build a distribution

Use JDK 17+:

```bash
sbt -batch test stage
```

The executable is generated under `target/universal/stage/bin/scada-webmcp`. With deployment secrets already supplied by your environment:

```bash
target/universal/stage/bin/scada-webmcp \
  -Dhttp.address=127.0.0.1 \
  -Dhttp.port=9000
```

This binds the application to loopback, suitable for a reverse proxy on the same host. Set the bind address according to your deployment network and access policy.

## Connect equipment

1. Set `SCADA_SIMULATOR_ENABLED=false`.
2. Configure `SCADA_OPCUA_ENDPOINT` and the expected OPC UA security policy and mode.
3. Provision the client key store, trusted server certificates, and any required credentials.
4. Replace the demo's `scada.tags` and `scada.commands` with the actual equipment definitions.

The connector validates certificates and rejects untrusted servers. See [secured OPC UA endpoints](opcua.md#secured-endpoints) for trust-directory details.

## Configure HTTP access

Supply a persistent, randomly generated `APPLICATION_SECRET` through your secret-management mechanism. Configure `play.filters.hosts.allowed` for the hostname served by the application. When using HTTPS, set `SESSION_SECURE=true` and configure Play's forwarded-header handling for your trusted reverse proxy so origin checks see the correct scheme and host.

Built-in identity options:

| Identity | Read catalog and values | Execute configured commands |
| --- | --- | --- |
| Anonymous local demo request | Yes, when enabled on loopback | Yes, on the simulator |
| Reader token or reader session | Yes | No |
| Operator token or operator session | Yes | Yes |
| Anonymous network request | No | No |

Set `SCADA_READER_TOKEN`, `SCADA_OPERATOR_TOKEN`, or both. The HMI exchanges a configured token for a signed session; the default session lifetime is eight hours. Remote MCP clients send a Bearer token directly. A custom Guice-bound `ScadaAccess` subclass can replace this model with your application's identity provider.

## Browser and MCP protections

Browser command, session, and assistant routes require a valid Play CSRF token, including in the local demo. The reusable browser client supplies the token from the page with the session cookie.

Only the `POST /mcp` route uses Play's `+ nocsrf` modifier. The MCP controller performs origin and authentication checks, and the shared engine validates command permissions and arguments. Keep the route configuration from the [integration guide](integration.md#routes-and-tool-catalog).

## Operational behavior

Subscriptions recover after reconnects, and discovery retries unavailable endpoints. Cached measurements become stale when acquisition is unavailable or too old. Surface quality and timestamps in operator views.

Commands are not automatically retried after transport failures. An acknowledged write does not confirm physical completion. Request deduplication is in-memory and does not coordinate multiple application instances or survive a restart.

Accepted commands are logged by the server. Add durable auditing and any required operational monitoring in your deployment. Keep physical interlocks and safety sequences in the PLC.

## Public documentation hosting

This documentation is a static GitHub Pages site at [hackinvent.github.io/scada-webmcp](https://hackinvent.github.io/scada-webmcp/). It does not host the Play runtime or connect to an OPC UA server. The [development guide](development.md#documentation-site) describes how the site is built and published.
