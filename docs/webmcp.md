---
description: Expose HMI tools through HackInvent play-webmcp while reusing the Java backend and browser client.
---
# WebMCP integration

The browser integration uses the published **[HackInvent/play-webmcp](https://github.com/HackInvent/play-webmcp) 0.5.0** module. `play-scada` includes it as a transitive dependency and supplies the handlers that connect page tools to the SCADA HTTP API.

## How a page exposes tools

1. Your Java controller passes `runtime.browserTools()` to its Twirl view.
2. The view renders each tool with `playwebmcp.javadsl.WebMcp.tool`.
3. The page loads the upstream `play-webmcp.global.js` asset and the reusable `play-scada.js` browser client.
4. `PlayScada.createClient().start()` registers the tool handlers, loads the catalog, and starts measurement updates.

See the [complete minimal view](integration.md#minimal-html-view) for the template and initialization code.

## Shared handlers

| Page tool | Browser handler | Backend operation |
| --- | --- | --- |
| `scada_list_tags` | `listTags` | Read the configured catalog |
| `scada_read_tags` | `readTags` | Obtain measurements through the browser snapshot API |
| `scada_list_commands` | `listCommands` | Read command definitions and current access |
| `scada_execute_command` | `executeCommand` | Confirm and send a configured command |

MCP clients connect to the server's `/mcp` endpoint. WebMCP tools run in the context of an HMI page and use that page's browser client and session. Both paths reach the same Java command engine.

## Bind ordinary HTML

```html
<output data-scada-value="tank.temperature" data-scada-unit="°C">—</output>
<span data-scada-quality="tank.temperature"></span>
<time data-scada-timestamp="tank.temperature"></time>
<button type="button" data-scada-command="pump.start">Start pump</button>
```

After loading the assets and rendering the CSRF field described in the integration guide:

```javascript
const client = PlayScada.createClient({
  baseUrl: '/api/scada/',
  locale: 'en',
  confirmCommand: command => window.confirm(`Execute ${command.label}?`),
  onError: error => console.error(error.message)
});
await client.start({ root: document });
```

The `locale` option controls number and date formatting. Labels come from your HTML and configured equipment catalog.

For dynamic page fragments, use `client.bind(root)` and `client.registerTools({ root })`. Call `client.dispose()` when the client is no longer needed to stop streams and unregister tools. Preserve the client when a page is retained in the browser's back/forward cache, as shown in the integration guide.

## Permissions and cancellation

Browser mutations require a valid Play CSRF token. The client transmits it with the session cookie, sends a stable idempotency key for commands, and confirms command execution. Server permissions and value validation remain authoritative.

The `context.signal` supplied to a WebMCP tool is forwarded to requests. Aborting a request after a write was sent cannot establish whether the server applied it. The client reads back the state and does not automatically resend an uncertain command.

## Browser support

WebMCP availability depends on browser and agent capabilities. The HMI continues to work through its ordinary buttons, forms, and live measurement bindings when WebMCP is unavailable.

Automated browser tests exercise the normal HMI and an injected WebMCP API to verify the registration contract. They do not claim support for every browser's native WebMCP implementation.
