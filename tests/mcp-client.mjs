import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { after, before, test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

// Run against the demo: BASE_URL=http://localhost:19000 npm run test:mcp
// The official SDK validates the handshake and tool response envelopes.
const baseURL = new URL(process.env.BASE_URL || 'http://localhost:9000');
const headers = process.env.MCP_TOKEN ? { Authorization: `Bearer ${process.env.MCP_TOKEN}` } : {};
const client = new Client({ name: 'hackinvent-scada-interoperability', version: '0.1.0' });

before(async () => {
  await client.connect(new StreamableHTTPClientTransport(new URL('/mcp', baseURL), {
    requestInit: { headers }
  }));
});
after(async () => { await client.close(); });

function output(result) {
  const text = result.content.find(item => item.type === 'text');
  assert.ok(text, 'MCP tools provide a text representation of their result');
  const value = JSON.parse(text.text);
  if (result.structuredContent) assert.deepEqual(result.structuredContent, value);
  return value;
}

async function simulatorSnapshot() {
  assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(baseURL.hostname), 'Command tests target loopback only');
  const response = await fetch(new URL('/api/scada/snapshot', baseURL), { headers });
  assert.equal(response.status, 200);
  const snapshot = await response.json();
  assert.equal(snapshot.simulated, true, 'Command tests require the embedded OPC UA simulator');
  assert.equal(snapshot.connected, true);
  return snapshot;
}

test('official SDK negotiates the server and discovers its shared SCADA catalog', async () => {
  assert.equal(client.getServerVersion().name, 'hackinvent-play-scada');
  assert.ok(client.getServerCapabilities().tools);
  const { tools } = await client.listTools();
  const names = tools.map(tool => tool.name);
  for (const name of ['scada_list_tags', 'scada_read_tags', 'scada_list_commands']) assert.ok(names.includes(name));
  const readTool = tools.find(tool => tool.name === 'scada_read_tags');
  assert.equal(readTool.inputSchema.type, 'object');
  assert.equal(readTool.annotations.readOnlyHint, true);
  const tags = await client.callTool({ name: 'scada_list_tags', arguments: {} });
  assert.equal(tags.isError, false);
  assert.ok(output(tags).tags.some(tag => tag.id === 'tank.temperature'));
});

test('MCP reads actual OPC UA values with quality and source timestamps', async () => {
  const result = await client.callTool({ name: 'scada_read_tags', arguments: { ids: ['tank.temperature'] } });
  assert.equal(result.isError, false);
  const { values } = output(result);
  assert.deepEqual(Object.keys(values), ['tank.temperature']);
  assert.equal(typeof values['tank.temperature'].value, 'number');
  assert.equal(values['tank.temperature'].quality, 'GOOD');
  assert.equal(values['tank.temperature'].statusCode, 0);
  assert.ok(Number.isFinite(Date.parse(values['tank.temperature'].sourceTimestamp)));
  assert.ok(Number.isFinite(Date.parse(values['tank.temperature'].receivedAt)));
});

test('unknown tags are tool errors and unknown tool names are protocol errors', async () => {
  const unknownTag = await client.callTool({ name: 'scada_read_tags', arguments: { ids: ['not.configured'] } });
  assert.equal(unknownTag.isError, true);
  assert.equal(output(unknownTag).code, 'unknown_tag');
  await assert.rejects(client.callTool({ name: 'not_a_scada_tool', arguments: {} }), error => error.code === -32602);
});

test('invalid simulator command values are refused before writing', async () => {
  const before = await simulatorSnapshot();
  const invalid = await client.callTool({ name: 'scada_execute_command', arguments: {
    commandId: 'tank.setpoint', value: 1000000, requestId: randomUUID()
  } });
  assert.equal(invalid.isError, true);
  assert.equal(output(invalid).code, 'out_of_range');
  const current = output(await client.callTool({ name: 'scada_read_tags', arguments: { ids: ['tank.setpoint'] } }));
  assert.equal(current.values['tank.setpoint'].value, before.values['tank.setpoint'].value);
});

test('simulator command uses a stable idempotency key and rejects conflicting retries', async () => {
  await simulatorSnapshot();
  const initial = output(await client.callTool({ name: 'scada_read_tags', arguments: { ids: ['tank.setpoint'] } }));
  const originalValue = initial.values['tank.setpoint'].value;
  assert.equal(typeof originalValue, 'number');
  const args = { commandId: 'tank.setpoint', value: 43, requestId: randomUUID() };
  try {
    const first = await client.callTool({ name: 'scada_execute_command', arguments: args });
    assert.equal(first.isError, false);
    assert.equal(output(first).status, 'accepted');
    const retry = await client.callTool({ name: 'scada_execute_command', arguments: args });
    assert.equal(retry.isError, false);
    assert.deepEqual(output(retry), output(first));
    const conflict = await client.callTool({ name: 'scada_execute_command', arguments: { ...args, value: 44 } });
    assert.equal(conflict.isError, true);
    assert.equal(output(conflict).code, 'idempotency_conflict');
    const current = output(await client.callTool({ name: 'scada_read_tags', arguments: { ids: ['tank.setpoint'] } }));
    assert.equal(current.values['tank.setpoint'].value, 43);
  } finally {
    const restore = await client.callTool({ name: 'scada_execute_command', arguments: {
      commandId: 'tank.setpoint', value: originalValue, requestId: randomUUID()
    } });
    assert.equal(restore.isError, false);
  }
});

test('transport rejects unrelated origins and advertises no standalone SSE stream', async () => {
  const stream = await fetch(new URL('/mcp', baseURL), { headers: { ...headers, Accept: 'text/event-stream' } });
  assert.equal(stream.status, 405);
  assert.equal(stream.headers.get('allow'), 'POST');
  const origin = await fetch(new URL('/mcp', baseURL), {
    method: 'POST', headers: {
      ...headers, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', Origin: 'https://unrelated.example'
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: 99, method: 'ping' })
  });
  assert.equal(origin.status, 403);
});
