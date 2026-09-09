# OPC UA connector

`MiloScadaConnector` implements the Java `ScadaConnector` contract with Eclipse Milo 1.1.6 (Java 17+). Discovery, browse (including continuation points), reads, monitored values and writes use a real OPC UA TCP connection. Discovery retries every two seconds, Milo reconnects sessions, and lost subscriptions are recreated with namespace URIs resolved again. Each value preserves OPC UA status and source/server timestamps.

## Local simulator

```java
var simulator = new DemoOpcUaServer(4840);
simulator.start().toCompletableFuture().join();
var connector = new MiloScadaConnector(OpcUaConnectorConfig.localDemo(simulator.endpointUrl()));
connector.connect().toCompletableFuture().join();
// Supply connector to ScadaEngine. In Play stop hooks, compose connector.shutdown().
// close() is the blocking equivalent; close the simulator as well.
```

The simulator binds only to `127.0.0.1` and explicitly offers `SecurityPolicy.None`. Its URI is `opc.tcp://127.0.0.1:4840/scada`.

| Application tag | Configured OPC UA node | Type | Access |
| --- | --- | --- | --- |
| tank.temperature | `nsu=urn:hackinvent:scada:demo;s=tank.temperature` | Double | Read |
| pump.running | `nsu=urn:hackinvent:scada:demo;s=pump.running` | Boolean | Read/write |
| tank.setpoint | `nsu=urn:hackinvent:scada:demo;s=tank.setpoint` | Double | Read/write |

Every 250 ms the temperature moves toward the setpoint when the pump runs, and toward 30 °C when stopped. The simulator is for development, never a physical control model.

## Secured endpoints

Construct `OpcUaConnectorConfig` with the exact endpoint security policy (for example `Basic256Sha256`) and mode (`SignAndEncrypt`), an existing PKCS12 client key store, its password and private-key alias, a trust directory, and the application URI present in the certificate. Username/password is optional and is rejected with an unsecured policy. Keep secrets in runtime configuration, outside source control.

The connector uses Milo's `DefaultClientCertificateValidator` with `ALL_OPTIONAL_CHECKS` (trust, validity, hostname, application URI, key usage and revocation checks): unknown server certificates fail validation and are quarantined in `<trustDirectory>/rejected/certs`. Provision approved certificates under `<trustDirectory>/trusted/certs` (and issuer certificates/CRLs as required by the server PKI). The OPC UA server must independently trust the client certificate. No trust-all validator is installed. Use the namespace-URI node form above to avoid depending on a server's assigned namespace index.

The connector does not retry writes: transport failure can leave their physical outcome unknown. Command authorization, ranges and request deduplication belong to the shared `ScadaEngine`/Play layer. The connector writes Boolean, Double, Float, Int32, Int64 and String. The shared ScadaEngine currently exposes Boolean, Double, Float, Int32 (or Integer) and String commands; Int64 requires extending its validation. Browsing exposes node metadata; a project still supplies its configured semantic tag and command catalogue.

Run `sbt 'opcua/test'` for real loopback tests, including reads, subscription updates, server-side access rejection and restart recovery. No OpenAI credentials or external OPC UA server are required.

API sources: [Milo tagged client](https://github.com/eclipse-milo/milo/blob/v1.1.6/opc-ua-sdk/sdk-client/src/main/java/org/eclipse/milo/opcua/sdk/client/OpcUaClient.java), [subscription example](https://github.com/eclipse-milo/milo/blob/v1.1.6/milo-examples/client-examples/src/main/java/org/eclipse/milo/examples/client/SubscriptionDataExample.java), [server example](https://github.com/eclipse-milo/milo/blob/v1.1.6/milo-examples/server-examples/src/main/java/org/eclipse/milo/examples/server/ExampleServer.java).

## Play configuration and environment

The demo application enables the simulator by default; the reusable module defaults it to disabled. For an external server, set `SCADA_SIMULATOR_ENABLED=false` and `SCADA_OPCUA_ENDPOINT=opc.tcp://your-server:4840/path`, and replace `scada.tags` and `scada.commands` with your actual catalogue.

| Setting in `application.conf` | Environment override in the demo |
| --- | --- |
| `scada.simulator.enabled` | `SCADA_SIMULATOR_ENABLED` |
| `scada.simulator.port` | `SCADA_SIMULATOR_PORT` |
| `scada.opcua.endpoint` | `SCADA_OPCUA_ENDPOINT` |
| `scada.opcua.keystore-password` | `SCADA_KEYSTORE_PASSWORD` |
| `scada.opcua.username` | `SCADA_OPCUA_USERNAME` |
| `scada.opcua.password` | `SCADA_OPCUA_PASSWORD` |

Set other connection options in your configuration:

```hocon
scada.opcua {
  security-policy = "Basic256Sha256"
  security-mode = "SignAndEncrypt"
  application-uri = "urn:your-company:scada:client"
  keystore = "/run/secrets/opcua-client.p12"
  key-alias = "client"
  trust-directory = "/var/lib/scada/opcua/pki"
  publish-interval-ms = 500
}
```

The Play runtime refreshes configured tags every three seconds in addition to subscriptions, keeping unchanged values fresh. Source timestamps continue to represent the measurement source. The browser marks disconnected or old cached values stale.
