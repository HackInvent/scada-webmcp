---
description: Configure tags, commands, acquisition intervals, authentication, and environment overrides.
---
# Configuration reference

Load the module defaults, then add your application's settings:

```hocon
include "scada-reference.conf"
```

The [demo configuration](https://github.com/HackInvent/scada-webmcp/blob/main/conf/application.conf) enables the local simulator and defines three tags and three commands. The [module defaults](https://github.com/HackInvent/scada-webmcp/blob/main/modules/play-scada/conf/scada-reference.conf) disable the simulator and leave the catalog empty.

## Define tags and commands

A tag gives a stable application name to an OPC UA node. A command explicitly allows a particular operation against a writable tag.

```hocon
scada.tags = [
  {
    id = "tank.setpoint"
    nodeId = "nsu=urn:hackinvent:scada:demo;s=tank.setpoint"
    label = "Tank temperature setpoint"
    unit = "°C"
    dataType = "Double"
    writable = true
  }
]
scada.commands = [
  {
    id = "tank.setpoint"
    label = "Set tank temperature"
    description = "Set the simulator temperature target between 10 and 80 degrees Celsius"
    tagId = "tank.setpoint"
    requiresValue = true
    min = 10
    max = 80
  }
]
```

For a fixed-value command, set `requiresValue = false` and specify `fixedValue`, such as `true` for starting a pump. Use the node identifiers, types, and allowed ranges defined for your equipment. Namespace URI identifiers avoid depending on a server's assigned namespace index.

## Acquisition settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `scada.simulator.enabled` | Module: `false`; demo: `true` | Start the embedded local OPC UA server |
| `scada.simulator.port` | `12686` | Simulator TCP port |
| `scada.refresh-interval` | `3 seconds` | Periodic read of configured tags in addition to subscriptions; runtime minimum is 500 ms |
| `scada.stale-after` | `10 seconds` | Cached value freshness threshold |
| `scada.opcua.publish-interval-ms` | `500` | Requested publish interval for external OPC UA subscriptions |

The embedded simulator uses the local-demo connector configuration. For external endpoint security, certificates, and connection options, see the [OPC UA guide](opcua.md#play-configuration-and-environment).

## Environment overrides

| Environment variable | Configuration |
| --- | --- |
| `SCADA_SIMULATOR_ENABLED` | `scada.simulator.enabled` in the demo |
| `SCADA_SIMULATOR_PORT` | `scada.simulator.port` in the demo |
| `SCADA_OPCUA_ENDPOINT` | `scada.opcua.endpoint` in the demo |
| `SCADA_KEYSTORE_PASSWORD` | `scada.opcua.keystore-password` |
| `SCADA_OPCUA_USERNAME` | `scada.opcua.username` |
| `SCADA_OPCUA_PASSWORD` | `scada.opcua.password` |
| `SCADA_READER_TOKEN` | `scada.security.reader-token` |
| `SCADA_OPERATOR_TOKEN` | `scada.security.operator-token` |
| `OPENAI_API_KEY` | `scada.openai.api-key` |
| `OPENAI_MODEL` | `scada.openai.model` |
| `APPLICATION_SECRET` | `play.http.secret.key` in the demo |
| `SESSION_SECURE` | `play.http.session.secure` in the demo |

`SCADA_JAVA_HOME` and `SCADA_HTTP_PORT` are options of `scripts/dev.sh`, not module configuration keys. An application consuming the module should define its own environment overrides where the table identifies demo-only mappings.

## Access settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `scada.security.reader-token` | Empty | Built-in read access token |
| `scada.security.operator-token` | Empty | Built-in operator access token |
| `scada.security.local-demo-access` | `true` | Allow anonymous loopback access while the simulator is enabled |
| `scada.security.session-duration` | `8 hours` | Lifetime of a signed HMI session |

With the simulator disabled, the built-in authentication requires at least one non-empty reader or operator token. Custom `ScadaAccess` implementations can integrate an existing identity provider; see [HMI integration](integration.md#routes-and-tool-catalog).

## Assistant settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `scada.openai.endpoint` | `https://api.openai.com/v1/responses` | Server-side Responses API endpoint |
| `scada.openai.timeout` | `45 seconds` | Deadline for the whole request, including tool execution |
| `scada.openai.max-tool-rounds` | `6` | Maximum model rounds; configured values are bounded to 1–10 |
| `scada.openai.max-output-tokens` | `2048` | Output token budget per model request; bounded to 128–8192 |

Both `scada.openai.api-key` and `scada.openai.model` must be set to enable the assistant. See the [assistant guide](assistant.md) for its execution model and data flow.
