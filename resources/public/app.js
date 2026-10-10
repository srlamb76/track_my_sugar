'use strict';

// ---------------------------------------------------------------------------
// API client

const api = {
  async request(method, path, body) {
    const opts = { method, headers: {} };
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    const res = await fetch('/api' + path, opts);
    if (res.status === 204) return null;
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
  },
  list:   (resource)           => api.request('GET', `/${resource}`),
  create: (resource, data)     => api.request('POST', `/${resource}`, data),
  update: (resource, id, data) => api.request('PUT', `/${resource}/${id}`, data),
  remove: (resource, id)       => api.request('DELETE', `/${resource}/${id}`),
  personReadings: (personId, metricTypeId) =>
    api.request('GET', `/persons/${personId}/health-details` +
                       (metricTypeId ? `?metric_type_id=${metricTypeId}` : '')),
};

// ---------------------------------------------------------------------------
// Helpers

const $ = (sel) => document.querySelector(sel);

const state = { persons: [], metricTypes: [] };

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

let messageTimer;
function showMessage(text, kind = 'ok') {
  const el = $('#message');
  el.textContent = text;
  el.className = kind;
  el.hidden = false;
  clearTimeout(messageTimer);
  messageTimer = setTimeout(() => { el.hidden = true; }, 4000);
}

async function run(action, successText) {
  try {
    await action();
    if (successText) showMessage(successText);
  } catch (err) {
    showMessage(err.message, 'error');
  }
}

function actionButtons(id) {
  return `<td class="row-actions">
    <button type="button" class="link" data-edit="${id}">Edit</button>
    <button type="button" class="link danger" data-delete="${id}">Delete</button>
  </td>`;
}

function emptyRow(cols, text) {
  return `<tr><td class="empty" colspan="${cols}">${escapeHtml(text)}</td></tr>`;
}

function fillSelect(select, items, label, { blank } = {}) {
  const current = select.value;
  select.innerHTML =
    (blank ? `<option value="">${escapeHtml(blank)}</option>` : '') +
    items.map((i) => `<option value="${i.id}">${escapeHtml(label(i))}</option>`).join('');
  if ([...select.options].some((o) => o.value === current)) select.value = current;
}

// SQLite stores "YYYY-MM-DD HH:MM:SS"; datetime-local uses "YYYY-MM-DDTHH:MM[:SS]".
const toInputDate = (s) => (s ? s.replace(' ', 'T') : '');
function fromInputDate(s) {
  if (!s) return null;
  const v = s.replace('T', ' ');
  return v.length === 16 ? v + ':00' : v;
}

// Generic form controller: handles add vs. edit mode for a CRUD form.
function crudForm({ form, title, noun, resource, toPayload, onSaved }) {
  const formEl = $(form);
  const titleEl = $(title);

  function reset() {
    formEl.reset();
    formEl.elements.id.value = '';
    titleEl.textContent = `Add ${noun}`;
  }

  function edit(row) {
    for (const el of formEl.elements) {
      if (el.name && el.name in row) {
        el.value = el.type === 'datetime-local' ? toInputDate(row[el.name]) : (row[el.name] ?? '');
      }
    }
    formEl.elements.id.value = row.id;
    titleEl.textContent = `Edit ${noun} #${row.id}`;
    formEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  formEl.addEventListener('submit', (e) => {
    e.preventDefault();
    const id = formEl.elements.id.value;
    const payload = toPayload(formEl.elements, Boolean(id));
    run(async () => {
      if (id) await api.update(resource, id, payload);
      else await api.create(resource, payload);
      reset();
      await onSaved();
    }, id ? `${noun[0].toUpperCase() + noun.slice(1)} updated` : `${noun[0].toUpperCase() + noun.slice(1)} added`);
  });

  formEl.querySelector('[data-cancel]').addEventListener('click', reset);

  return { edit, reset };
}

// Wire Edit/Delete buttons in a table body.
function tableActions({ tbody, rows, resource, noun, form, onDeleted }) {
  $(tbody).addEventListener('click', (e) => {
    const editId = e.target.dataset.edit;
    const deleteId = e.target.dataset.delete;
    if (editId) {
      const row = rows().find((r) => String(r.id) === editId);
      if (row) form.edit(row);
    } else if (deleteId && confirm(`Delete this ${noun}?`)) {
      run(async () => {
        await api.remove(resource, deleteId);
        form.reset();
        await onDeleted();
      }, `${noun[0].toUpperCase() + noun.slice(1)} deleted`);
    }
  });
}

// ---------------------------------------------------------------------------
// People

async function loadPersons() {
  state.persons = await api.list('persons');
  $('#person-rows').innerHTML = state.persons.length
    ? state.persons.map((p) => `<tr>
        <td>${p.id}</td>
        <td>${escapeHtml(p.name)}</td>
        <td>${escapeHtml(p.email)}</td>
        ${actionButtons(p.id)}
      </tr>`).join('')
    : emptyRow(4, 'No people yet');
  fillSelect($('#reading-form').elements.person_id, state.persons, (p) => p.name);
  fillSelect($('#reading-filter-person'), state.persons, (p) => p.name, { blank: 'All' });
}

const personForm = crudForm({
  form: '#person-form',
  title: '#person-form-title',
  noun: 'person',
  resource: 'persons',
  toPayload: (f) => ({ name: f.name.value.trim(), email: f.email.value.trim() || null }),
  onSaved: () => Promise.all([loadPersons(), loadReadings()]),
});

tableActions({
  tbody: '#person-rows', rows: () => state.persons, resource: 'persons',
  noun: 'person', form: personForm, onDeleted: loadPersons,
});

// ---------------------------------------------------------------------------
// Metric types

async function loadMetricTypes() {
  state.metricTypes = await api.list('metric-types');
  $('#metric-type-rows').innerHTML = state.metricTypes.length
    ? state.metricTypes.map((m) => `<tr>
        <td>${m.id}</td>
        <td>${escapeHtml(m.type)}</td>
        <td>${escapeHtml(m.description)}</td>
        ${actionButtons(m.id)}
      </tr>`).join('')
    : emptyRow(4, 'No metric types yet');
  const label = (m) => `${m.type} — ${m.description}`;
  fillSelect($('#reading-form').elements.metric_type_id, state.metricTypes, label);
  fillSelect($('#reading-filter-metric'), state.metricTypes, (m) => m.type, { blank: 'All' });
}

const metricTypeForm = crudForm({
  form: '#metric-type-form',
  title: '#metric-type-form-title',
  noun: 'metric type',
  resource: 'metric-types',
  toPayload: (f) => ({ type: f.type.value.trim(), description: f.description.value.trim() }),
  onSaved: () => Promise.all([loadMetricTypes(), loadReadings()]),
});

tableActions({
  tbody: '#metric-type-rows', rows: () => state.metricTypes, resource: 'metric-types',
  noun: 'metric type', form: metricTypeForm, onDeleted: loadMetricTypes,
});

// ---------------------------------------------------------------------------
// Readings

state.readings = [];

async function loadReadings() {
  const personId = $('#reading-filter-person').value;
  const metricId = $('#reading-filter-metric').value;
  let rows = personId
    ? await api.personReadings(personId, metricId)
    : await api.list('health-details');
  if (!personId && metricId) rows = rows.filter((r) => String(r.metric_type_id) === metricId);
  if (!personId) rows.sort((a, b) => String(a.created_date_time).localeCompare(String(b.created_date_time)) || a.id - b.id);
  state.readings = rows;

  const personName = (id) => state.persons.find((p) => p.id === id)?.name ?? `#${id}`;
  const metricName = (id) => state.metricTypes.find((m) => m.id === id)?.type ?? `#${id}`;

  $('#reading-rows').innerHTML = rows.length
    ? rows.map((r) => `<tr>
        <td>${escapeHtml(r.created_date_time)}</td>
        <td>${escapeHtml(personName(r.person_id))}</td>
        <td>${escapeHtml(metricName(r.metric_type_id))}</td>
        <td class="num">${escapeHtml(r.health_metric)}</td>
        ${actionButtons(r.id)}
      </tr>`).join('')
    : emptyRow(5, 'No readings yet');
}

const readingForm = crudForm({
  form: '#reading-form',
  title: '#reading-form-title',
  noun: 'reading',
  resource: 'health-details',
  toPayload: (f) => {
    const payload = {
      person_id: Number(f.person_id.value),
      metric_type_id: Number(f.metric_type_id.value),
      health_metric: Number(f.health_metric.value),
    };
    const ts = fromInputDate(f.created_date_time.value);
    if (ts) payload.created_date_time = ts;
    return payload;
  },
  onSaved: loadReadings,
});

tableActions({
  tbody: '#reading-rows', rows: () => state.readings, resource: 'health-details',
  noun: 'reading', form: readingForm, onDeleted: loadReadings,
});

$('#reading-filter-person').addEventListener('change', () => run(loadReadings));
$('#reading-filter-metric').addEventListener('change', () => run(loadReadings));

// ---------------------------------------------------------------------------
// Tabs & startup

document.querySelectorAll('nav .tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('nav .tab').forEach((t) => t.classList.toggle('active', t === tab));
    document.querySelectorAll('.panel').forEach((p) => { p.hidden = p.id !== tab.dataset.tab; });
  });
});

run(async () => {
  await Promise.all([loadPersons(), loadMetricTypes()]);
  await loadReadings();
});
