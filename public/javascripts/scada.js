(async function () {
  'use strict';

  const rootPath = document.body.dataset.scadaBase.replace(/\/?$/, '/');
  const apiBase = new URL(`${rootPath}api/scada/`, location.origin);
  const csrfToken = document.querySelector('#csrf-fields input[name="csrfToken"]')?.value;
  const byId = id => document.getElementById(id);
  const numberFormat = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 });
  const timeFormat = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  let catalog = null;
  let snapshot = null;
  let catalogueSignature = '';
  let client;

  function notice(message) {
    byId('page-notice').textContent = message;
    byId('page-notice').hidden = !message;
  }

  function dateLabel(value) {
    const date = new Date(value);
    return value && Number.isFinite(date.getTime()) ? timeFormat.format(date) : '—';
  }

  function valueLabel(value, unit = '') {
    if (value === null || value === undefined) return '—';
    if (typeof value === 'boolean') return value ? 'En marche' : 'À l’arrêt';
    const text = typeof value === 'number' ? numberFormat.format(value) : String(value);
    return unit ? `${text} ${unit}` : text;
  }

  function quality(sample) {
    if (!sample) return { label: 'Indisponible', state: 'bad' };
    const received = Date.parse(sample.receivedAt);
    if (!snapshot?.connected || (Number.isFinite(received) && Date.now() - received > 5000)) {
      return { label: 'Périmée', state: 'uncertain' };
    }
    const status = String(sample.quality || '').toLowerCase();
    if (status === 'good') return { label: 'Bonne', state: 'good' };
    if (status === 'stale') return { label: 'Périmée', state: 'uncertain' };
    if (status === 'uncertain') return { label: 'Incertaine', state: 'uncertain' };
    return { label: status === 'bad' ? 'Mauvaise' : 'Indisponible', state: 'bad' };
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function setAccess() {
    const access = catalog?.access || {};
    byId('access-status').textContent = access.canCommand
      ? (access.demo ? 'Commandes disponibles sur le simulateur local.' : 'Session opérateur active.')
      : 'Lecture seule. Ouvrez une session opérateur pour commander.';
    document.querySelectorAll('#commands button, #commands input, #commands select').forEach(control => {
      control.disabled = !access.canCommand || client?.commandPending;
    });
    byId('assistant-status').textContent = access.canUseAssistant ? 'Disponible' : 'Indisponible';
    byId('assistant-description').textContent = access.canUseAssistant
      ? 'Interrogez l’assistant sur les valeurs et les équipements du catalogue.'
      : 'L’assistant doit être activé sur le serveur et autorisé pour votre session.';
    document.querySelectorAll('#assistant-form input, #assistant-form button').forEach(control => {
      control.disabled = !access.canUseAssistant;
    });
  }

  function renderCatalog(data) {
    catalog = data;
    const signature = JSON.stringify({ tags: data.tags, commands: data.commands });
    if (signature !== catalogueSignature) {
      catalogueSignature = signature;
      byId('metrics').replaceChildren();
      byId('tag-rows').replaceChildren();
      for (const tag of data.tags) {
        const card = element('article', 'metric');
        card.dataset.tag = tag.id;
        const top = element('div', 'metric-top');
        top.append(element('span', 'metric-label', tag.label), element('span', 'metric-type', tag.writable ? 'CONSIGNE / ÉTAT' : 'MESURE'));
        const value = element('div', 'metric-value', '—');
        value.dataset.scadaValue = tag.id;
        value.dataset.scadaUnit = tag.unit || '';
        const foot = element('div', 'metric-foot');
        const status = element('span', 'quality', 'Indisponible');
        status.dataset.scadaQuality = tag.id;
        foot.append(element('code', '', tag.id), status);
        card.append(top, value, foot);
        byId('metrics').append(card);

        const row = element('tr');
        const label = element('td', 'tag-label', tag.label);
        label.append(element('code', 'tag-id', tag.id));
        const cell = element('td', '', '—');
        cell.dataset.scadaValue = tag.id;
        cell.dataset.scadaUnit = tag.unit || '';
        const qualityCell = element('td');
        const qualityLabel = element('span', 'quality', 'Indisponible');
        qualityLabel.dataset.scadaQuality = tag.id;
        qualityCell.append(qualityLabel);
        const time = element('td', 'tag-time', '—');
        time.dataset.scadaTimestamp = tag.id;
        row.append(label, cell, qualityCell, time);
        byId('tag-rows').append(row);
      }
      byId('tag-count').textContent = `${data.tags.length} variables`;
      renderCommands(data.commands);
    }
    setAccess();
    if (snapshot) renderSnapshot(snapshot);
    return data;
  }

  function renderCommands(commands) {
    byId('commands').replaceChildren();
    for (const command of commands) {
      const form = element('form', 'command-item');
      form.dataset.commandId = command.id;
      const label = element('div', 'command-label', command.label);
      form.append(label, element('p', 'command-description', command.description || ''));
      const controls = element('div', 'input-row');
      if (command.requiresValue) {
        const kind = commandValueType(command);
        const input = element(kind === 'boolean' ? 'select' : 'input');
        if (kind !== 'boolean') input.type = kind === 'number' ? 'number' : 'text';
        else {
          for (const [value, label] of [['false', 'Désactivé'], ['true', 'Activé']]) {
            const option = element('option', '', label);
            option.value = value;
            input.append(option);
          }
        }
        input.name = 'value';
        input.step = 'any';
        input.required = true;
        input.setAttribute('aria-label', `${command.label} — valeur`);
        if (command.min !== null && command.min !== undefined) input.min = command.min;
        if (command.max !== null && command.max !== undefined) input.max = command.max;
        input.addEventListener('input', () => { input.dataset.dirty = 'true'; });
        controls.append(input);
      }
      const button = element('button', `button${command.id.endsWith('.stop') ? ' button-secondary' : ''}`, command.requiresValue ? 'Appliquer' : command.label);
      button.type = 'submit';
      button.dataset.scadaCommand = command.id;
      controls.append(button);
      form.append(controls);
      form.addEventListener('submit', async event => {
        event.preventDefault();
        if (!form.reportValidity()) return;
        const args = { commandId: command.id };
        if (command.requiresValue) {
          const raw = form.elements.namedItem('value').value;
          const kind = commandValueType(command);
          args.value = kind === 'number' ? Number(raw) : kind === 'boolean' ? raw === 'true' : raw;
        }
        try { await executeCommand(args); } catch { /* The shared handler displays the result. */ }
      });
      byId('commands').append(form);
    }
  }

  function commandValueType(command) {
    const type = catalog?.tags.find(tag => tag.id === command.tagId)?.dataType?.toLowerCase();
    return type === 'boolean' ? 'boolean' : type === 'string' ? 'string' : 'number';
  }

  function renderSnapshot(data) {
    snapshot = data;
    const recent = Number.isFinite(Date.parse(data.timestamp)) && Date.now() - Date.parse(data.timestamp) < 5000;
    const connected = data.connected && recent;
    byId('mode-badge').textContent = data.simulated ? 'SIMULATION LOCALE' : 'SERVEUR OPC UA';
    byId('connection-status').textContent = connected ? 'Acquisition active' : 'Acquisition interrompue';
    byId('connection-status').dataset.state = connected ? 'connected' : 'disconnected';
    byId('last-update').textContent = `Dernière acquisition · ${dateLabel(data.timestamp)}`;
    document.querySelectorAll('[data-scada-value]').forEach(node => {
      const sample = data.values[node.dataset.scadaValue];
      const unit = node.dataset.scadaUnit || '';
      if (node.classList.contains('metric-value') && typeof sample?.value === 'number' && unit) {
        node.replaceChildren(document.createTextNode(valueLabel(sample.value)), element('span', 'unit', unit));
      } else {
        node.textContent = valueLabel(sample?.value, unit);
      }
    });
    document.querySelectorAll('[data-scada-quality]').forEach(node => {
      const result = quality(data.values[node.dataset.scadaQuality]);
      node.textContent = result.label;
      node.dataset.quality = result.state;
    });
    document.querySelectorAll('[data-scada-timestamp]').forEach(node => {
      node.textContent = dateLabel(data.values[node.dataset.scadaTimestamp]?.sourceTimestamp);
    });
    document.querySelectorAll('[data-tag]').forEach(node => {
      node.classList.toggle('stale', quality(data.values[node.dataset.tag]).state !== 'good');
    });
    byId('pump-symbol').dataset.running = data.values['pump.running']?.value === true;
    document.querySelectorAll('#commands form').forEach(form => {
      const command = catalog?.commands.find(item => item.id === form.dataset.commandId);
      const input = form.elements.namedItem('value');
      const value = command && data.values[command.tagId]?.value;
      if (input && input.dataset.dirty !== 'true' && document.activeElement !== input && value !== null && value !== undefined) {
        input.value = value;
      }
    });
  }

  function logCommand(text) {
    byId('command-log').querySelector('.empty-log')?.remove();
    const entry = element('li');
    const time = element('time', '', dateLabel(new Date().toISOString()));
    time.dateTime = new Date().toISOString();
    entry.append(time, document.createTextNode(text));
    byId('command-log').prepend(entry);
    while (byId('command-log').children.length > 20) byId('command-log').lastElementChild.remove();
  }

  // The reusable module supplies the same command path to HTML and WebMCP.
  function executeCommand(args, context) { return client.executeCommand(args, context); }

  function confirmCommand(command, args) {
    const form = Array.from(document.querySelectorAll('#commands form')).find(item => item.dataset.commandId === command.id);
    if (command.requiresValue && form) {
      form.elements.namedItem('value').value = args.value;
      form.elements.namedItem('value').dataset.dirty = 'true';
    }
    const detail = command.requiresValue ? `${command.label} : ${valueLabel(args.value)}` : command.label;
    return window.confirm(`Confirmer la commande « ${detail} » ?`);
  }

  function displayCommand(event) {
    const status = byId('command-notice');
    if (event.phase === 'confirming') { status.dataset.error = 'false'; setAccess(); }
    if (event.phase === 'cancelled') status.textContent = 'Commande annulée avant envoi.';
    if (event.phase === 'sending') status.textContent = 'Envoi de la commande…';
    if (event.phase === 'accepted') logCommand(`${event.command.label} : écriture acceptée par le serveur OPC UA.`);
    if (event.phase === 'complete') status.textContent = `${event.command.label} : écriture acceptée par le serveur OPC UA. Mesures actualisées.`;
    if (event.phase === 'error') {
      status.dataset.error = 'true';
      if (event.accepted) {
        status.textContent = 'Écriture acceptée. La nouvelle lecture a échoué ; vérifiez les mesures avant toute autre commande.';
      } else if (event.rejected) {
        status.textContent = `Commande refusée : ${event.error.message}`;
        logCommand(`${event.command?.label || event.commandId} : commande refusée.`);
      } else if (event.uncertain) {
        status.textContent = 'Résultat de commande non confirmé. Vérifiez les mesures et le journal serveur avant de réessayer.';
        logCommand(`${event.command?.label || event.commandId} : résultat non confirmé.`);
      } else status.textContent = event.error.name === 'AbortError' ? 'Commande annulée avant envoi.' : event.error.message;
    }
    if (event.phase === 'idle') setAccess();
  }

  byId('session-form').addEventListener('submit', async event => {
    event.preventDefault();
    const input = byId('operator-token');
    const token = input.value;
    input.value = '';
    try {
      await client.openSession(token);
      byId('session-status').textContent = 'Session opérateur ouverte.';
    } catch (error) {
      byId('session-status').textContent = error.message;
    }
  });

  byId('logout').addEventListener('click', async () => {
    try {
      await client.closeSession();
      byId('session-status').textContent = 'Session opérateur fermée.';
    } catch (error) {
      byId('session-status').textContent = error.message;
    }
  });

  byId('assistant-form').addEventListener('submit', async event => {
    event.preventDefault();
    const button = event.currentTarget.querySelector('button');
    const message = byId('assistant-message').value.trim();
    if (!message || button.disabled) return;
    button.disabled = true;
    byId('assistant-answer').textContent = 'L’assistant consulte les informations…';
    try {
      const response = await client.askAssistant(message);
      byId('assistant-answer').textContent = response.answer || 'L’assistant n’a pas fourni de réponse.';
    } catch (error) {
      byId('assistant-answer').textContent = error.message;
    } finally {
      button.disabled = !catalog?.access.canUseAssistant;
    }
  });

  client = globalThis.PlayScada.createClient({
    baseUrl: apiBase,
    csrfToken,
    locale: 'fr-FR',
    onCatalog: renderCatalog,
    onSnapshot(data) {
      renderSnapshot(data);
      if (!data.transportStale) notice('');
    },
    confirmCommand,
    onCommand: displayCommand,
    onError(error, operation) {
      if (operation === 'webmcp') {
        byId('webmcp-status').textContent = 'WebMCP : enregistrement indisponible';
        console.error('WebMCP registration failed', error);
      } else notice(`L’acquisition n’est pas disponible : ${error.message}`);
    }
  });

  // This demo customizes rendering and forms. A lightweight view can call start()
  // with its defaults to bind data-scada-value/data-scada-command automatically.
  const registration = await client.start({ bindCommands: false });
  if (registration) {
    byId('webmcp-status').textContent = registration.supported
      ? `WebMCP actif · ${registration.registered.length} outils de page`
      : 'WebMCP non disponible dans ce navigateur';
  }
  window.addEventListener('pagehide', event => {
    if (!event.persisted) client.dispose().catch(() => {});
  });
})();
