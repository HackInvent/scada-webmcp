package io.hackinvent.scada.opcua;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/** Connection settings. Secrets are deliberately excluded from the string representation. */
public record OpcUaConnectorConfig(
    String endpointUrl, String securityPolicy, String securityMode,
    Path keyStorePath, String keyStorePassword, String keyAlias, Path trustDirectory,
    String applicationUri, String username, String password, double publishingIntervalMillis) {

  public OpcUaConnectorConfig {
    Objects.requireNonNull(endpointUrl, "endpointUrl");
    Objects.requireNonNull(securityPolicy, "securityPolicy");
    Objects.requireNonNull(securityMode, "securityMode");
    Objects.requireNonNull(applicationUri, "applicationUri");
    org.eclipse.milo.opcua.stack.core.security.SecurityPolicy.valueOf(securityPolicy);
    org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode.valueOf(securityMode);
    URI endpoint = URI.create(endpointUrl);
    if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
      throw new IllegalArgumentException("OPC UA endpoint must not include credentials, query parameters or a fragment");
    }
    if (!"opc.tcp".equals(endpoint.getScheme()) || endpoint.getHost() == null) {
      throw new IllegalArgumentException("An opc.tcp endpoint URL is required");
    }
    if (!Double.isFinite(publishingIntervalMillis) || publishingIntervalMillis < 50) {
      throw new IllegalArgumentException("Publishing interval must be at least 50 ms");
    }
    if ("None".equals(securityPolicy)) {
      if (!"None".equals(securityMode)) throw new IllegalArgumentException("None policy requires None mode");
      if (username != null && !username.isBlank()) {
        throw new IllegalArgumentException("Username authentication requires a secured OPC UA endpoint");
      }
    } else {
      if ("None".equals(securityMode)) throw new IllegalArgumentException("Secured policy requires Sign or SignAndEncrypt");
      Objects.requireNonNull(keyStorePath, "keyStorePath is required for secured OPC UA");
      Objects.requireNonNull(trustDirectory, "trustDirectory is required for secured OPC UA");
      Objects.requireNonNull(keyAlias, "keyAlias");
      Objects.requireNonNull(keyStorePassword, "keyStorePassword");
    }
  }

  /** Explicitly unsecured connection, restricted to the local simulator. */
  public static OpcUaConnectorConfig localDemo(String endpointUrl) {
    String host = URI.create(endpointUrl).getHost();
    if (!"localhost".equals(host) && !"127.0.0.1".equals(host) && !"[::1]".equals(host)) {
      throw new IllegalArgumentException("The demo endpoint must be loopback");
    }
    return new OpcUaConnectorConfig(endpointUrl, "None", "None", null, null, null, null,
        "urn:hackinvent:scada:client", null, null, 250.0);
  }

  @Override public String toString() {
    return "OpcUaConnectorConfig[endpointUrl=" + endpointUrl + ", securityPolicy="
        + securityPolicy + ", securityMode=" + securityMode + "]";
  }
}
