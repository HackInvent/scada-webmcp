---
description: Connect MCP clients to the SCADA catalog over Streamable HTTP, with operator permissions and command idempotency.
---
# MCP server

The Play module exposes **`POST /mcp`** using stateless Streamable HTTP. The server uses the same tag definitions, command validation, and OPC UA engine as the HMI.

## Connect a client

For a client that accepts an HTTP server URL:

```json
{
  "mcpServers": {
    "scada": {
      "url": "http://127.0.0.1:9000/mcp"
    }
  }
}
```

Outside the local demo, configure `Authorization: Bearer <token>` using your client's header settings. A reader token provides observation tools. An operator token also permits configured commands. See [deployment and access](deployment.md).

## Available tools

| Tool | Arguments | Result |
| --- | --- | --- |
| `scada_list_tags` | `{}` | Configured tags, labels, types, units, and write metadata |
| `scada_read_tags` | Optional `ids` array | Current readings keyed by tag identifier, with quality and timestamps |
| `scada_list_commands` | `{}` | Command definitions and a `canExecute` flag |
| `scada_execute_command` | `commandId`, optional `value`, and `requestId` or HTTP idempotency header | Write acknowledgement and operation metadata |

`scada_execute_command` is omitted from `tools/list` for readers. A direct call by a reader is also rejected. The server does not provide arbitrary node writes or infer command definitions from the OPC UA address space.

## Inspect the protocol

Initialization example for the local demo:

```bash
curl http://127.0.0.1:9000/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"scada-example","version":"1.0"}}}'
```

A normal client negotiates the version and sends `notifications/initialized`. For later requests, supply the negotiated `MCP-Protocol-Version`. Read a configured tag:

```bash
curl http://127.0.0.1:9000/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-11-25' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"scada_read_tags","arguments":{"ids":["tank.temperature"]}}}'
```

## Execute a configured command

Example `tools/call` request for the local simulator:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "scada_execute_command",
    "arguments": {
      "commandId": "tank.setpoint",
      "value": 24,
      "requestId": "example-setpoint-001"
    }
  }
}
```

Use a new request identifier for each intended operation. Retain it if you need to check the same operation again. Reuse with a different command or value returns `idempotency_conflict`. Identifiers contain 1–128 characters from `A–Z`, `a–z`, `0–9`, `_`, `.`, `:`, and `-`.

You can supply `Idempotency-Key` as an HTTP header instead of the argument. If both are present, they must match. Deduplication is local to the running process; see [current scope](architecture.md#current-scope).

## Transport behavior

- Supported protocol versions: `2025-11-25`, `2025-06-18`, and `2025-03-26`.
- Requests use JSON and accept both `application/json` and `text/event-stream`. Responses use JSON.
- No MCP session identifier or server-initiated SSE stream is created. `GET /mcp` and `DELETE /mcp` return 405.
- Valid notifications return 202. A notification cannot execute a tool call.
- The request body is limited to 64 KiB. Malformed JSON, duplicate keys, and trailing tokens are rejected.
- Legacy batches of 1–32 messages are supported only for protocol `2025-03-26`.
- Origin checks apply when the `Origin` header is supplied.
- OAuth, deferred tasks, and MCP resources are not implemented.

Tool execution failures use the MCP tool error result. Invalid JSON-RPC requests and unknown tool names are protocol errors. Clients should inspect `isError` and the returned error code before treating a result as successful.

The repository's [MCP client tests](https://github.com/HackInvent/scada-webmcp/blob/main/tests/mcp-client.mjs) verify interoperability with the official client SDK.
