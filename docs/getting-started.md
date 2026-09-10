---
description: Run the Play Java application and its real OPC UA simulator, then choose your integration path.
---
# Getting started

## Requirements

- JDK 17 or 21 and sbt.
- Git to clone the repository.
- Node.js 20+ for browser tests or the documentation site. The Play application itself does not require Node.js.

## Start the application

```bash
git clone https://github.com/HackInvent/scada-webmcp.git
cd scada-webmcp
./scripts/dev.sh
```

Open [http://127.0.0.1:9000](http://127.0.0.1:9000). On the first run, sbt downloads the Java and Play dependencies. Wait for the OPC UA connection indicator before trying a command.

The development script binds HTTP to `127.0.0.1`. The embedded simulator listens at `opc.tcp://127.0.0.1:12686/scada`.

If the system defaults to an older JDK, select an installed JDK explicitly:

```bash
SCADA_JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./scripts/dev.sh
```

Replace that path with the JDK installation on your machine. To use a different HTTP port:

```bash
SCADA_HTTP_PORT=9100 ./scripts/dev.sh
```

## Explore the demo

| Tag | What to observe |
| --- | --- |
| `tank.temperature` | Simulated tank temperature, including OPC UA quality and source timestamps |
| `pump.running` | Pump state, controlled by the start and stop commands |
| `tank.setpoint` | Temperature target, adjustable from 10 to 80 °C |

The temperature moves toward the setpoint while the pump runs and toward 30 °C while stopped. The illustrated tank level is decorative; there is no level measurement.

The local demo grants operator access to loopback requests while the simulator is enabled. Command buttons ask for confirmation. Successful commands acknowledge an OPC UA write; read back the measurements to observe the resulting state.

The HMI works without WebMCP support in the browser and without OpenAI credentials. The public documentation site describes the application; run Play locally to use the live HMI.

## Try the read APIs

With the demo running:

```bash
curl http://127.0.0.1:9000/api/scada/catalog
curl http://127.0.0.1:9000/api/scada/snapshot
```

For an MCP client that accepts an HTTP URL, configure `http://127.0.0.1:9000/mcp`. See the [MCP guide](mcp.md) for initialization, tools, and authentication.

## Choose the next step

- [Build a Play Java HMI](integration.md) using the reusable browser client.
- [Connect an external OPC UA server](opcua.md) with your own certificates and catalog.
- [Understand the architecture](architecture.md) before extending the core.
- [Configure deployment and access](deployment.md) before exposing an instance on a network.

## Startup troubleshooting

| Symptom | Check |
| --- | --- |
| Java version or class-file error | Use JDK 17+ for sbt, including through `SCADA_JAVA_HOME` |
| HTTP port already in use | Stop the previous instance or set `SCADA_HTTP_PORT` |
| OPC UA simulator cannot bind | Stop the previous simulator or set `SCADA_SIMULATOR_PORT` to a free port |
| No initial measurements | Check the server logs and wait for the OPC UA connection to complete |
| Commands unavailable from another machine | Configure authentication; anonymous demo permission is limited to loopback |
| Assistant unavailable | Set both `OPENAI_API_KEY` and `OPENAI_MODEL` on the server if you want to enable it |
