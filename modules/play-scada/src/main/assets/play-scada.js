(function (global) {
  'use strict';

  /** A reusable HTML bridge; OPC UA, credentials and command authorization stay on the server. */
  function createClient(options = {}) {
    if (!options.baseUrl) throw new TypeError('PlayScada requires a baseUrl for the SCADA API.');
    const baseUrl = new URL(String(options.baseUrl).replace(/\/?$/, '/'), document.baseURI);
    if (baseUrl.origin !== location.origin) throw new TypeError('The SCADA API must use the page origin.');
    const lifetime = new AbortController();
    const bindings = new Map();
    let catalog;
    let snapshot;
    let registration;
    let stream;
    let timer;
    let streamHealthy = false;
    let refreshing = false;
    let commandPending = false;
    let started = false;
    let disposed = false;

    const onError = (error, operation) => options.onError?.(error, operation);
    const commandEvent = event => {
      for (const root of bindings.keys()) updateBindings(root);
      options.onCommand?.(event);
    };
    const csrf = () => typeof options.csrfToken === 'function' ? options.csrfToken()
      : options.csrfToken ?? document.querySelector('input[name="csrfToken"]')?.value;

    async function request(path, { method = 'GET', body, signal, requestId } = {}) {
      if (disposed) throw new Error('This SCADA client has been disposed.');
      const abort = signal || lifetime.signal;
      abort.throwIfAborted();
      const headers = { Accept: 'application/json' };
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      if (method !== 'GET') {
        if (!csrf()) throw new Error('Missing CSRF token; reload the page.');
        headers['Csrf-Token'] = csrf();
      }
      if (requestId) headers['Idempotency-Key'] = requestId;
      const response = await fetch(new URL(path, baseUrl), {
        method, headers, credentials: 'same-origin', signal: abort,
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      });
      abort.throwIfAborted();
      if (!response.headers.get('content-type')?.includes('application/json')) {
        const error = new Error(`Unexpected server response (HTTP ${response.status}).`);
        error.httpStatus = response.status;
        throw error;
      }
      const data = await response.json();
      if (!response.ok) {
        const error = new Error(data.message || (typeof data.error === 'string' ? data.error : data.error?.message)
          || `Request failed (HTTP ${response.status}).`);
        error.httpStatus = response.status;
        throw error;
      }
      return data;
    }

    function applyCatalog(data) {
      if (!data || !Array.isArray(data.tags) || !Array.isArray(data.commands) || !data.access) {
        throw new Error('Invalid SCADA catalog response.');
      }
      catalog = data;
      for (const root of bindings.keys()) updateBindings(root);
      options.onCatalog?.(data);
      return data;
    }

    function applySnapshot(data) {
      if (!data || !data.values || typeof data.values !== 'object' || Array.isArray(data.values)) {
        throw new Error('Invalid SCADA snapshot response.');
      }
      snapshot = data;
      for (const root of bindings.keys()) updateBindings(root);
      options.onSnapshot?.(data);
      return data;
    }

    async function refreshCatalog(signal) { return applyCatalog(await request('catalog', { signal })); }
    async function refreshSnapshot(signal) { return applySnapshot(await request('snapshot', { signal })); }

    async function listTags(_args = {}, context = {}) {
      const data = await refreshCatalog(context.signal);
      return { tags: data.tags, connection: data.connection };
    }
    async function listCommands(_args = {}, context = {}) {
      const data = await refreshCatalog(context.signal);
      return { commands: data.commands, canCommand: data.access.canCommand };
    }
    async function readTags({ ids } = {}, context = {}) {
      if (ids !== undefined && (!Array.isArray(ids) || ids.some(id => typeof id !== 'string'))) {
        throw new TypeError('ids must be an array of tag identifiers.');
      }
      const data = await refreshSnapshot(context.signal);
      if (!ids?.length) return data;
      for (const id of ids) if (!Object.hasOwn(data.values, id)) throw new Error(`Unknown configured tag: ${id}`);
      return { ...data, values: Object.fromEntries(ids.map(id => [id, data.values[id]])) };
    }

    function valueType(command) {
      const type = catalog?.tags.find(tag => tag.id === command.tagId)?.dataType?.toLowerCase();
      return type === 'boolean' ? 'boolean' : type === 'string' ? 'string' : 'number';
    }

    async function executeCommand({ commandId, value, requestId } = {}, context = {}) {
      context.signal?.throwIfAborted();
      if (commandPending) throw new Error('Another command is still pending.');
      commandPending = true;
      let command;
      let sent = false;
      let accepted = false;
      try {
        if (!catalog) await refreshCatalog(context.signal);
        command = catalog.commands.find(item => item.id === commandId);
        if (!command) throw new Error(`Unknown configured command: ${commandId}`);
        if (!catalog.access.canCommand) throw new Error('Operator permission is required.');
        const kind = valueType(command);
        if (command.requiresValue && (typeof value !== kind || (kind === 'number' && !Number.isFinite(value)))) {
          throw new TypeError(`Command value must have type ${kind}.`);
        }
        if (!command.requiresValue && value !== undefined) throw new TypeError('This command has a fixed value.');
        if (command.requiresValue && ((command.min != null && value < command.min) || (command.max != null && value > command.max))) {
          throw new RangeError(`Command value must be between ${command.min} and ${command.max}.`);
        }
        if (requestId !== undefined && (typeof requestId !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(requestId))) {
          throw new TypeError('requestId must contain 1–128 safe characters.');
        }
        const args = { commandId, value, requestId };
        commandEvent({ phase: 'confirming', command, args });
        const confirm = options.confirmCommand || ((item, input) => global.confirm(
          `${item.label}${item.requiresValue ? `: ${String(input.value)}` : ''} — confirm command?`));
        const confirmed = await confirm(command, args, context);
        context.signal?.throwIfAborted();
        if (!confirmed) {
          commandEvent({ phase: 'cancelled', command, args });
          return { commandId, status: 'cancelled', cancelled: true };
        }
        const idempotencyKey = requestId || global.crypto.randomUUID();
        commandEvent({ phase: 'sending', command, args, requestId: idempotencyKey });
        sent = true;
        const result = await request(`commands/${encodeURIComponent(commandId)}`, {
          method: 'POST', body: command.requiresValue ? { value } : {},
          requestId: idempotencyKey, signal: context.signal
        });
        accepted = true;
        commandEvent({ phase: 'accepted', command, args, result });
        // Observe acquisition after acknowledgement. Never retransmit a write automatically.
        await refreshSnapshot(context.signal);
        commandEvent({ phase: 'complete', command, args, result });
        return result;
      } catch (error) {
        const rejected = sent && error.httpStatus >= 400 && error.httpStatus < 500;
        commandEvent({ phase: 'error', command, commandId, error, sent, accepted,
          uncertain: sent && !accepted && !rejected, rejected });
        if (sent && !accepted && !rejected) refreshSnapshot(lifetime.signal).catch(() => {});
        throw error;
      } finally {
        commandPending = false;
        commandEvent({ phase: 'idle' });
      }
    }

    function formatValue(value, unit = '') {
      if (value === undefined || value === null) return '—';
      const text = typeof value === 'number'
        ? new Intl.NumberFormat(options.locale, { maximumFractionDigits: 2 }).format(value) : String(value);
      return unit ? `${text} ${unit}` : text;
    }

    function updateBindings(root) {
      if (bindings.get(root)?.commands) root.querySelectorAll('[data-scada-command]').forEach(node => {
        if ('disabled' in node) node.disabled = !catalog?.access.canCommand || commandPending;
      });
      if (!snapshot) return;
      root.querySelectorAll('[data-scada-value]').forEach(node => {
        const sample = snapshot.values[node.dataset.scadaValue];
        const text = formatValue(sample?.value, node.dataset.scadaUnit || '');
        if (node instanceof HTMLInputElement || node instanceof HTMLSelectElement || node instanceof HTMLTextAreaElement) {
          // Editable inputs belong to the user; bind their current value explicitly in application code.
          if (node.readOnly) node.value = sample?.value ?? '';
        } else node.textContent = text;
        node.dataset.scadaSampleQuality = sample?.quality || 'UNAVAILABLE';
      });
      root.querySelectorAll('[data-scada-quality]').forEach(node => {
        node.textContent = snapshot.values[node.dataset.scadaQuality]?.quality || 'UNAVAILABLE';
      });
      root.querySelectorAll('[data-scada-timestamp]').forEach(node => {
        const timestamp = snapshot.values[node.dataset.scadaTimestamp]?.sourceTimestamp;
        node.textContent = timestamp ? new Date(timestamp).toLocaleString(options.locale) : '—';
      });
    }

    function bind(root = document, { commands = true } = {}) {
      if (!root || typeof root.querySelectorAll !== 'function') throw new TypeError('A DOM container is required.');
      if (bindings.has(root)) return bindings.get(root).unbind;
      const controller = new AbortController();
      if (commands) root.addEventListener('click', async event => {
        const button = event.target.closest?.('[data-scada-command]');
        if (!button || !root.contains(button) || button.disabled) return;
        event.preventDefault();
        try {
          if (!catalog) await refreshCatalog();
          const command = catalog.commands.find(item => item.id === button.dataset.scadaCommand);
          const args = { commandId: button.dataset.scadaCommand };
          if (command?.requiresValue) {
            const input = button.dataset.scadaInput ? root.querySelector(button.dataset.scadaInput) : null;
            if (input?.reportValidity && !input.reportValidity()) return;
            const raw = input?.type === 'checkbox' ? String(input.checked)
              : input ? input.value : button.dataset.scadaCommandValue;
            const kind = valueType(command);
            if (raw === undefined || (kind === 'number' && raw.trim() === '')) throw new Error('A command value is required.');
            if (kind === 'boolean' && raw !== 'true' && raw !== 'false') throw new TypeError('Boolean command values must be true or false.');
            args.value = kind === 'number' ? Number(raw) : kind === 'boolean' ? raw === 'true' : raw;
          }
          button.disabled = true;
          const result = await executeCommand(args);
          button.dispatchEvent(new CustomEvent('scada:command', { bubbles: true, detail: result }));
        } catch (error) {
          onError(error, 'command');
          button.dispatchEvent(new CustomEvent('scada:error', { bubbles: true, detail: error }));
        } finally { button.disabled = !catalog?.access.canCommand; }
      }, { signal: controller.signal });
      const unbind = () => { controller.abort(); bindings.delete(root); };
      bindings.set(root, { unbind, commands });
      updateBindings(root);
      return unbind;
    }

    async function registerTools({ root = document } = {}) {
      if (registration) return registration;
      if (!global.PlayWebMcp?.registerTools) throw new Error('Load the HackInvent play-webmcp runtime before play-scada.');
      registration = await global.PlayWebMcp.registerTools({ listTags, readTags, listCommands, executeCommand }, { root, signal: lifetime.signal });
      return registration;
    }

    async function start({ root = document, bindCommands = true } = {}) {
      if (disposed) throw new Error('This SCADA client has been disposed.');
      if (started) return registration;
      started = true;
      bind(root, { commands: bindCommands });
      const results = await Promise.allSettled([refreshCatalog(), refreshSnapshot(), registerTools({ root })]);
      results.forEach((result, index) => { if (result.status === 'rejected') onError(result.reason, ['catalog', 'snapshot', 'webmcp'][index]); });
      if (disposed) return registration;
      if (typeof global.EventSource === 'function') {
        stream = new global.EventSource(new URL('events', baseUrl), { withCredentials: true });
        stream.addEventListener('snapshot', event => {
          try { applySnapshot(JSON.parse(event.data)); streamHealthy = true; }
          catch (error) { streamHealthy = false; onError(error, 'snapshot'); }
        });
        stream.addEventListener('error', () => { streamHealthy = false; });
      }
      timer = setInterval(async () => {
        if (snapshot && Date.now() - Date.parse(snapshot.timestamp) > 5000) {
          streamHealthy = false;
          applySnapshot({ ...snapshot, transportStale: true,
            values: Object.fromEntries(Object.entries(snapshot.values).map(([id, sample]) =>
              [id, { ...sample, quality: sample.value == null ? 'UNAVAILABLE' : 'STALE' }])) });
        }
        if (!streamHealthy && !refreshing) {
          refreshing = true;
          try {
            if (!catalog) await refreshCatalog();
            await refreshSnapshot();
          } catch (error) { if (!disposed) onError(error, 'snapshot'); }
          finally { refreshing = false; }
        }
      }, 2000);
      return registration;
    }

    async function openSession(token, context = {}) {
      await request('session', { method: 'POST', body: { token }, signal: context.signal });
      return refreshCatalog(context.signal);
    }
    async function closeSession(context = {}) {
      await request('session', { method: 'DELETE', signal: context.signal });
      return refreshCatalog(context.signal);
    }
    function askAssistant(message, context = {}) {
      return request('assistant', { method: 'POST', body: { message }, signal: context.signal });
    }
    async function dispose() {
      disposed = true;
      lifetime.abort();
      stream?.close();
      clearInterval(timer);
      for (const binding of bindings.values()) binding.unbind();
      return registration ? registration.dispose() : { remaining: [], errors: [] };
    }
    return Object.freeze({ start, bind, registerTools, dispose, refreshCatalog, refreshSnapshot,
      listTags, readTags, listCommands, executeCommand, openSession, closeSession, askAssistant,
      get catalog() { return catalog; }, get snapshot() { return snapshot; },
      get commandPending() { return commandPending; } });
  }

  global.PlayScada = Object.freeze({ createClient });
})(globalThis);
