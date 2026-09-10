---
layout: home
title: Industrial supervision with Play Java
description: A reusable Play Java core that connects OPC UA data to HTML interfaces, MCP clients, and browser-native WebMCP tools.
hero:
  name: Play SCADA
  text: Your process data. Your interface.
  tagline: Build lightweight HTML supervision screens on a shared Java core for OPC UA, MCP, and WebMCP.
  image:
    src: /architecture.svg
    alt: OPC UA connected to a shared Play Java core for HTML HMI, MCP clients, and an optional assistant
  actions:
    - theme: brand
      text: Run the demo
      link: /getting-started
    - theme: alt
      text: Build your HMI
      link: /integration
    - theme: alt
      text: View on GitHub
      link: https://github.com/HackInvent/scada-webmcp
features:
  - title: Connect to OPC UA
    details: Real Eclipse Milo connections, monitored values, reconnection, certificate validation, and a local simulator for development.
    link: /opcua
    linkText: Connector guide
  - title: Share a command catalog
    details: Configured tags and commands use the same Java validation and permissions across the HMI and the MCP server.
    link: /architecture
    linkText: Explore the architecture
  - title: Keep the interface lightweight
    details: Bind HTML elements to live values and commands. Integrate browser tools through the HackInvent play-webmcp module.
    link: /webmcp
    linkText: WebMCP integration
---

<div class="home-section">
  <h2>From a running demo to your own application</h2>
  <p>The repository includes a working tank-and-pump demo and three reusable modules. Start with a real local OPC UA connection, define your equipment catalog, then build the views your operators need.</p>
  <div class="home-paths">
    <div class="home-path">
      <h3>01 / Explore</h3>
      <p>Run the Play Java application and simulator. Inspect live measurements, quality, timestamps, and command behavior.</p>
      <a href="./getting-started.html">Get started →</a>
    </div>
    <div class="home-path">
      <h3>02 / Integrate</h3>
      <p>Bring your own Twirl views and connect the shared catalog to HTML elements, MCP clients, and browser tools.</p>
      <a href="./integration.html">Build an HMI →</a>
    </div>
    <div class="home-path">
      <h3>03 / Deploy</h3>
      <p>Configure your OPC UA endpoint, certificates, application identity, and operator access for your environment.</p>
      <a href="./deployment.html">Deployment guide →</a>
    </div>
  </div>
</div>
<div class="home-section">
  <h2>A practical foundation with a defined scope</h2>
  <p>Play Java 3.0.11 · Java 17+ · Eclipse Milo 1.1.6 · HackInvent play-webmcp 0.5.0. The optional OpenAI assistant provides read-only observation. Historical storage, alarms, and high availability remain future work. <a href="./architecture.html#current-scope">Read the current scope →</a></p>
</div>
