import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { chromium } from 'playwright';

const baseURL = process.env.BASE_URL || 'http://localhost:9000';
let browser;

before(async () => {
  browser = await chromium.launch({ headless: true });
});
after(async () => { await browser?.close(); });

async function pageWithTools() {
  const page = await browser.newPage();
  // A browser-API test double checks our integration with the actual upstream
  // runtime. This does not assert support from a native browser or real agent.
  await page.addInitScript(() => {
    globalThis.pageTools = {};
    Object.defineProperty(document, 'modelContext', { configurable: true, value: {
      registerTool(tool, options) {
        globalThis.pageTools[tool.name] = tool;
        options?.signal?.addEventListener('abort', () => { delete globalThis.pageTools[tool.name]; });
      }
    } });
  });
  await page.goto(baseURL);
  await page.getByRole('button', { name: /démarrer/i }).waitFor();
  await page.waitForFunction(() => Object.keys(globalThis.pageTools).length >= 4);
  return page;
}

test('the Java page serves the upstream runtime and displays real acquisition', async () => {
  const page = await browser.newPage();
  try {
    await page.addInitScript(() => {
      Object.defineProperty(document, 'modelContext', { configurable: true, value: undefined });
      Object.defineProperty(navigator, 'modelContext', { configurable: true, value: undefined });
    });
    await page.goto(baseURL);
    await page.getByRole('heading', { name: 'Supervision de la cuve' }).waitFor();
    await page.waitForFunction(() => document.querySelector('#connection-status')?.dataset.state === 'connected');
    const cards = page.locator('[data-tag]');
    assert.equal(await cards.count(), 3);
    assert.match(await page.locator('[data-tag="tank.temperature"]').innerText(), /°C/);
    assert.match(await page.locator('[data-tag="pump.running"]').innerText(), /En marche|À l’arrêt/);
    const runtimeUrl = await page.locator('script[src*="play-webmcp.global.js"]').getAttribute('src');
    const response = await page.request.get(new URL(runtimeUrl, baseURL).href);
    assert.equal(response.status(), 200);
    assert.match(await response.text(), /registerTools/);
    assert.equal(await page.locator('script[data-play-webmcp]').count(), 4);
    assert.match(await page.locator('#webmcp-status').innerText(), /non disponible/);
  } finally { await page.close(); }
});

test('HTML commands require confirmation, send CSRF and deduplication headers, then reread values', async () => {
  const page = await browser.newPage();
  try {
    await page.goto(baseURL);
    const input = page.getByRole('spinbutton');
    await input.waitFor();
    await input.fill('47');
    page.once('dialog', dialog => dialog.dismiss());
    await page.getByRole('button', { name: 'Appliquer' }).click();
    assert.match(await page.locator('#command-notice').innerText(), /annulée avant envoi/);

    const requestPromise = page.waitForRequest(request => request.url().includes('/commands/tank.setpoint') && request.method() === 'POST');
    const responsePromise = page.waitForResponse(response => response.url().includes('/commands/tank.setpoint') && response.request().method() === 'POST');
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Appliquer' }).click();
    const commandRequest = await requestPromise;
    assert.ok(commandRequest.headers()['csrf-token']);
    assert.match(commandRequest.headers()['idempotency-key'], /^[0-9a-f-]{36}$/i);
    assert.deepEqual(commandRequest.postDataJSON(), { value: 47 });
    const response = await responsePromise;
    assert.equal(response.status(), 200);
    assert.equal((await response.json()).status, 'accepted');
    await page.waitForFunction(() => document.querySelector('#command-notice').textContent.includes('Mesures actualisées'));
    await page.waitForFunction(() => document.querySelector('[data-tag="tank.setpoint"] [data-scada-value]').textContent.includes('47'));
  } finally { await page.close(); }
});

test('WebMCP tools use the same acquisition and command path and reject unknown tags', async () => {
  const page = await pageWithTools();
  try {
    const read = await page.evaluate(async () => {
      const tool = Object.values(globalThis.pageTools).find(item => item.name.includes('read'));
      return tool.execute({ ids: ['tank.temperature'] });
    });
    assert.deepEqual(Object.keys(read.values), ['tank.temperature']);
    assert.equal(typeof read.values['tank.temperature'].value, 'number');
    const unknown = await page.evaluate(async () => {
      try {
        const tool = Object.values(globalThis.pageTools).find(item => item.name.includes('read'));
        await tool.execute({ ids: ['not.configured'] });
        return 'unexpected success';
      } catch (error) { return error.message; }
    });
    assert.match(unknown, /Unknown configured tag/);
    page.once('dialog', dialog => dialog.accept());
    const response = await page.evaluate(async () => {
      const tool = Object.values(globalThis.pageTools).find(item => item.name.includes('execute'));
      return tool.execute({ commandId: 'tank.setpoint', value: 42, requestId: crypto.randomUUID() });
    });
    assert.equal(response.status, 'accepted');
    await page.waitForFunction(() => document.querySelector('[data-tag="tank.setpoint"] [data-scada-value]').textContent.includes('42'));
  } finally { await page.close(); }
});

test('a lost command response is not retried automatically', async () => {
  const page = await browser.newPage();
  let writes = 0;
  try {
    await page.goto(baseURL);
    await page.getByRole('spinbutton').waitFor();
    await page.route('**/commands/tank.setpoint', async route => {
      writes++;
      await route.abort('failed');
    });
    await page.getByRole('spinbutton').fill('45');
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Appliquer' }).click();
    await page.waitForFunction(() => document.querySelector('#command-notice').textContent.includes('non confirmé'));
    assert.equal(writes, 1);
    assert.match(await page.locator('#command-notice').innerText(), /Vérifiez les mesures/);
  } finally { await page.close(); }
});

test('a lightweight HTML view uses the reusable bridge without application JavaScript', async () => {
  const page = await browser.newPage();
  try {
    await page.addInitScript(() => {
      Object.defineProperty(document, 'modelContext', { configurable: true, value: undefined });
      Object.defineProperty(navigator, 'modelContext', { configurable: true, value: undefined });
    });
    await page.goto(baseURL);
    await page.getByRole('spinbutton').waitFor();
    const integration = await page.evaluate(() => ({
      metadata: Array.from(document.querySelectorAll('script[data-play-webmcp]')).map(node => node.outerHTML).join('\n'),
      csrf: document.querySelector('input[name="csrfToken"]').value,
      webmcp: document.querySelector('script[src*="play-webmcp.global.js"]').src,
      bridge: document.querySelector('script[src*="play-scada.js"]').src,
      baseUrl: new URL(`${document.body.dataset.scadaBase.replace(/\/?$/, '/')}api/scada/`, location.origin).href
    }));
    const escape = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
    await page.route('**/lightweight-test', route => route.fulfill({
      contentType: 'text/html; charset=utf-8',
      body: `<!doctype html><html lang="fr"><head><meta charset="utf-8"><title>Minimal consumer</title>
        <script defer src="${escape(integration.webmcp)}"></script>
        <script defer src="${escape(integration.bridge)}"></script></head><body>
        <input type="hidden" name="csrfToken" value="${escape(integration.csrf)}">
        <output aria-label="Consigne actuelle" data-scada-value="tank.setpoint" data-scada-unit="°C"></output>
        <input id="setpoint" type="number" value="43" min="10" max="80" required>
        <button data-scada-command="tank.setpoint" data-scada-input="#setpoint">Régler</button>
        ${integration.metadata}</body></html>`
    }));
    await page.goto(new URL('lightweight-test', baseURL).href);
    const supported = await page.evaluate(async baseUrl => {
      globalThis.lightClient = globalThis.PlayScada.createClient({ baseUrl });
      return (await globalThis.lightClient.start()).supported;
    }, integration.baseUrl);
    assert.equal(supported, false);
    await page.waitForFunction(() => document.querySelector('output').textContent.includes('°C'));
    page.once('dialog', dialog => dialog.accept());
    await page.getByRole('button', { name: 'Régler' }).click();
    await page.waitForFunction(() => document.querySelector('output').textContent.includes('43'));
    const cleanup = await page.evaluate(() => globalThis.lightClient.dispose());
    assert.deepEqual(cleanup.remaining, []);
  } finally { await page.close(); }
});
