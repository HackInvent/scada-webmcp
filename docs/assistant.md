---
description: Enable the optional read-only OpenAI assistant and understand server-side tools, deadlines, and data flow.
---
# Optional OpenAI assistant

The assistant answers questions about the configured SCADA catalog and measurements. It runs in the Play backend, uses the Responses API, and receives only the module's read tools. The HMI, OPC UA connector, MCP server, and WebMCP integration work without it.

## Enable the assistant

Supply both environment variables through your deployment's secret and configuration management:

```bash
# Provide a valid API key through your secret manager.
export OPENAI_API_KEY="$YOUR_OPENAI_API_KEY"
# Choose a Responses API model with function-calling support.
export OPENAI_MODEL="$YOUR_OPENAI_MODEL"
```

The placeholders above refer to values you supply; the application does not choose a model. Keep the API key on the server. Restart the application after changing its configuration.

The HMI's assistant panel submits a question through `POST /api/scada/assistant`. Your own UI can use:

```javascript
const response = await client.askAssistant('What is the current tank temperature and data quality?');
console.log(response.answer);
```

## Execution flow

1. Play checks read permission, origin, and the browser CSRF token.
2. The adapter sends the question and allowed tool definitions to OpenAI.
3. Requested read tools execute in the Java backend against the shared catalog or OPC UA engine.
4. Tool results are supplied to the model until it produces an answer or reaches a configured limit.

The assistant cannot execute commands. Both tool selection and execution checks enforce this restriction. Operators use the HMI or MCP command path with their existing permissions.

## Limits and errors

| Limit | Default behavior |
| --- | --- |
| Question length | Maximum 4,000 characters |
| Concurrent requests | Four; additional requests receive `assistant_busy` (429) |
| Overall deadline | 45 seconds, including model calls and local tools |
| Model rounds | Six by default |
| Output budget | 2,048 tokens per model request by default |

A deadline returns `assistant_timeout` (504). Incomplete model output returns `assistant_incomplete` (502). Application shutdown cancels pending assistant requests. See the [configuration reference](configuration.md#assistant-settings) for adjustable values.

## Data flow and verification

Questions, tool definitions, and consulted results are sent to OpenAI. The API key remains in the backend. Requests use `store=false`; this setting does not disable all provider retention policies. Assess which equipment metadata and measurements your deployment allows the assistant to send.

Automated tests use a local HTTP provider to exercise the tool loop, write restrictions, incomplete responses, deadlines, and shutdown. They do not consume OpenAI credits. An actual model call requires your deployment credentials and should be verified with the selected model.
