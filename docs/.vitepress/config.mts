import { defineConfig } from 'vitepress';

const repository = 'https://github.com/HackInvent/scada-webmcp';
const site = 'https://hackinvent.github.io/scada-webmcp/';

export default defineConfig({
  lang: 'en-US',
  title: 'Play SCADA',
  description: 'Build HTML supervision interfaces on a shared Play Java core for OPC UA, MCP, and WebMCP.',
  base: '/scada-webmcp/',
  lastUpdated: true,
  markdown: { languageAlias: { hocon: 'properties' } },
  sitemap: { hostname: site },
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/scada-webmcp/logo.svg' }],
    ['meta', { name: 'theme-color', content: '#087f79' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'Play SCADA' }]
  ],
  transformPageData(page) {
    if (page.relativePath === '404.md') return;
    const url = site + page.relativePath.replace(/index\.md$/, '').replace(/\.md$/, '.html');
    page.frontmatter.head ??= [];
    page.frontmatter.head.push(
      ['link', { rel: 'canonical', href: url }],
      ['meta', { property: 'og:url', content: url }],
      ['meta', { property: 'og:title', content: `${page.title || 'Documentation'} | Play SCADA` }],
      ['meta', { property: 'og:description', content: page.description || 'A reusable Java core for industrial supervision.' }]
    );
  },
  themeConfig: {
    logo: { src: '/logo.svg', alt: 'Play SCADA' },
    nav: [
      { text: 'Get started', link: '/getting-started' },
      { text: 'Architecture', link: '/architecture' },
      { text: 'Guides', items: [
        { text: 'Build an HMI', link: '/integration' },
        { text: 'Connect OPC UA', link: '/opcua' },
        { text: 'MCP server', link: '/mcp' },
        { text: 'WebMCP in the browser', link: '/webmcp' }
      ] },
      { text: '0.1.0-SNAPSHOT', link: `${repository}/blob/main/build.sbt` }
    ],
    sidebar: [
      { text: 'Start here', items: [
        { text: 'Overview', link: '/' },
        { text: 'Getting started', link: '/getting-started' },
        { text: 'Architecture and scope', link: '/architecture' }
      ] },
      { text: 'Build and integrate', items: [
        { text: 'Your Play Java HMI', link: '/integration' },
        { text: 'OPC UA connector', link: '/opcua' },
        { text: 'MCP server', link: '/mcp' },
        { text: 'WebMCP integration', link: '/webmcp' },
        { text: 'OpenAI assistant', link: '/assistant' }
      ] },
      { text: 'Reference and operations', items: [
        { text: 'Configuration', link: '/configuration' },
        { text: 'Deployment and access', link: '/deployment' },
        { text: 'Development and verification', link: '/development' }
      ] }
    ],
    search: { provider: 'local' },
    outline: { level: [2, 3] },
    socialLinks: [{ icon: 'github', link: repository }],
    editLink: { pattern: `${repository}/edit/main/docs/:path`, text: 'Edit this page on GitHub' },
    footer: { message: 'Built by HackInvent · Play Java + OPC UA + MCP + WebMCP' }
  }
});
