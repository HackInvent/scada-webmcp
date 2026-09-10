---
description: Build and test the Java modules, verify browser and MCP integration, and maintain the English documentation site.
---
# Development and verification

## Java modules

Use JDK 17+ and sbt:

```bash
sbt test
sbt stage
```

The Java suite covers command validation and concurrent idempotency, real OPC UA TCP exchanges and reconnection, certificate validation, MCP transport, access control, signed CSRF tokens, runtime lifecycle, and the optional assistant against a local HTTP provider.

To consume the libraries from another local Play project:

```bash
sbt 'core/publishLocal' 'opcua/publishLocal' 'playScada/publishLocal'
```

See [HMI integration](integration.md) for dependency coordinates. The SCADA modules are distributed as source and local artifacts; no public Maven publication is configured for them.

## Browser and MCP integration

With Node.js 20+:

```bash
npm ci
npx playwright install chromium
```

Start the demo in another terminal, then run the suites in sequence because they issue commands against the same simulator:

```bash
npm run test:browser
npm run test:mcp
```

To use another demo HTTP port:

```bash
BASE_URL=http://127.0.0.1:9100 npm run test:browser
BASE_URL=http://127.0.0.1:9100 npm run test:mcp
```

The browser suite verifies ordinary HMI use, injected WebMCP registration, a minimal HTML integration, and a lost command response without an automatic resend. MCP tests use the official client SDK. Command integration tests target only a loopback instance with the embedded simulator enabled.

## Documentation site

Documentation is written in English and generated from this repository's `docs` directory with VitePress. The existing integration and OPC UA guides are the same source files used by the site.

```bash
npm ci
npm run docs:dev
```

The development command binds to loopback. Open the URL printed in the terminal, including the `/scada-webmcp/` base path.

Build and preview the static output:

```bash
npm run docs:build
npm run docs:preview
```

The build checks internal documentation links and writes generated files to `docs/.vitepress/dist`, which is excluded from Git. The theme provides local search, code highlighting, responsive navigation, and a dark mode. `package.json` pins VitePress 1.6.4 and overrides its Vite dependency to 6.4.3 to include development-server security fixes; keep the documentation build and browser checks passing when updating either dependency.

The `Documentation site` workflow builds documentation changes for pull requests and pushes. A successful build on `main` deploys the static site through GitHub Pages. The site does not require Java, an OPC UA server, or OpenAI credentials.

## Writing conventions

Use English for documentation, examples, GitHub project content, and commit messages. Keep reference material aligned with the implemented APIs and clearly distinguish available functionality from planned extensions. The repository's [AGENTS.md](https://github.com/HackInvent/scada-webmcp/blob/main/AGENTS.md) records this convention for future changes.
