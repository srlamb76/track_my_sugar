'use strict';

// ---------------------------------------------------------------------------
// API client

const api = {
  async request(method, path, body) {
    if (window.TmsNative) {
      // Android app: answered in-process by the native bridge.
      const res = JSON.parse(window.TmsNative.request(
        method, '/api' + path, body === undefined ? null : JSON.stringify(body)));
      if (res.status === 204) return null;
      const data = res.body ? JSON.parse(res.body) : {};
      if (res.status >= 400) throw new Error(data.error || `Request failed (${res.status})`);
      return data;
    }
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

// id -> name lookups, rebuilt whenever the lists load, so rendering thousands
// of readings costs one Map hit per row instead of scanning the whole list.
const lookup = { person: new Map(), metric: new Map() };
const personLabel = (id) => lookup.person.get(id) ?? `#${id}`;
const metricLabel = (id) => lookup.metric.get(id) ?? `#${id}`;

// The readings table shows at most this many rows (the newest); filters narrow
// it. Keeps the DOM small however large the history grows.
const MAX_READING_ROWS = 500;

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
function crudForm({ form, title, noun, resource, toPayload, onSaved, afterReset }) {
  const formEl = $(form);
  const titleEl = $(title);

  function reset() {
    formEl.reset();
    formEl.elements.id.value = '';
    titleEl.textContent = `Add ${noun}`;
    if (afterReset) afterReset();  // e.g. refill a default that reset() clears
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
  lookup.person = new Map(state.persons.map((p) => [p.id, p.name]));
  $('#person-rows').innerHTML = state.persons.length
    ? state.persons.map((p) => `<tr>
        <td data-label="ID">${p.id}</td>
        <td data-label="Name">${escapeHtml(p.name)}</td>
        <td data-label="Email">${escapeHtml(p.email)}</td>
        ${actionButtons(p.id)}
      </tr>`).join('')
    : emptyRow(4, 'No people yet');
  fillSelect($('#reading-form').elements.person_id, state.persons, (p) => p.name);
  fillSelect($('#reading-filter-person'), state.persons, (p) => p.name, { blank: 'All' });
  fillSelect($('#an-person'), state.persons, (p) => p.name, { blank: 'All people' });
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
  lookup.metric = new Map(state.metricTypes.map((m) => [m.id, m.type]));
  $('#metric-type-rows').innerHTML = state.metricTypes.length
    ? state.metricTypes.map((m) => `<tr>
        <td data-label="ID">${m.id}</td>
        <td data-label="Type">${escapeHtml(m.type)}</td>
        <td data-label="Description">${escapeHtml(m.description)}</td>
        ${actionButtons(m.id)}
      </tr>`).join('')
    : emptyRow(4, 'No metric types yet');
  const label = (m) => `${m.type} — ${m.description}`;
  fillSelect($('#reading-form').elements.metric_type_id, state.metricTypes, label);
  fillSelect($('#reading-filter-metric'), state.metricTypes, (m) => m.type, { blank: 'All' });
  fillSelect($('#an-metric'), state.metricTypes, (m) => m.type, { blank: 'All metric types' });
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
  const total = rows.length;
  if (total > MAX_READING_ROWS) rows = rows.slice(-MAX_READING_ROWS);  // newest rows
  state.readings = rows;

  $('#reading-rows').innerHTML = (total > rows.length
      ? emptyRow(5, `Showing the newest ${rows.length} of ${total} readings. Use the filters to see others.`)
      : '') + (rows.length
    ? rows.map((r) => `<tr>
        <td data-label="When">${escapeHtml(r.created_date_time)}</td>
        <td data-label="Person">${escapeHtml(personLabel(r.person_id))}</td>
        <td data-label="Metric">${escapeHtml(metricLabel(r.metric_type_id))}</td>
        <td class="num" data-label="Value">${escapeHtml(r.health_metric)}</td>
        ${actionButtons(r.id)}
      </tr>`).join('')
    : emptyRow(5, 'No readings yet'));
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
  afterReset: () => setNowDefault(),
});

// Pre-fills "Date & time" with the current local time, so a new reading is
// stamped now unless the user changes it. It is refreshed whenever the form
// resets and when the Readings tab is shown, so it never goes stale on a page
// left open. Skipped while editing, which must keep the reading's own time.
function setNowDefault() {
  const f = $('#reading-form').elements;
  if (f.id.value) return;
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  f.created_date_time.value =
    `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
setNowDefault();

tableActions({
  tbody: '#reading-rows', rows: () => state.readings, resource: 'health-details',
  noun: 'reading', form: readingForm, onDeleted: loadReadings,
});

$('#reading-filter-person').addEventListener('change', () => run(loadReadings));
$('#reading-filter-metric').addEventListener('change', () => run(loadReadings));

// ---------------------------------------------------------------------------
// Analytics: line graph of readings over time

const SVG_NS = 'http://www.w3.org/2000/svg';
const CHART = { w: 680, h: 340, l: 52, r: 18, t: 14, b: 38 };
const SERIES_COLORS = 8;
const MAX_DOTS = 500;

const parseTime = (s) => Date.parse(String(s).replace(' ', 'T'));

// Round axis tick values (1, 2, 2.5, 5 x a power of ten) covering [min, max],
// so the y axis shows readable numbers instead of the raw extremes.
function niceTicks(min, max, count = 5) {
  if (min === max) { min -= 1; max += 1; }
  const rough = (max - min) / count;
  const pow = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * pow).find((s) => s >= rough);
  const start = Math.floor(min / step) * step;
  const ticks = [];
  for (let v = start; v < max + step * 0.999; v += step) ticks.push(+v.toFixed(10));
  return ticks;
}

// Creates an SVG element (these need the SVG namespace, unlike HTML ones).
function svgEl(name, attrs = {}, text) {
  const el = document.createElementNS(SVG_NS, name);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  if (text !== undefined) el.textContent = text;
  return el;
}

// X axis label: date and time for spans under two days, otherwise just the date.
function formatTick(ms, spanMs) {
  const d = new Date(ms);
  const day = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  return spanMs < 2 * 864e5 ? `${day.slice(5)} ${hm}` : day;
}

// Readings matching the person / metric type / date-range filters.
function analyticsRows() {
  const personId = $('#an-person').value;
  const metricId = $('#an-metric').value;
  const from = $('#an-from').value;
  const to = $('#an-to').value;
  return state.allReadings.filter((r) => {
    const day = String(r.created_date_time).slice(0, 10);
    return (!personId || String(r.person_id) === personId) &&
           (!metricId || String(r.metric_type_id) === metricId) &&
           (!from || day >= from) &&
           (!to || day <= to);
  });
}

// One series (line) per person + metric type combination.
function buildSeries(rows) {
  const byKey = new Map();
  for (const r of rows) {
    const key = `${r.person_id}:${r.metric_type_id}`;
    if (!byKey.has(key)) {
      byKey.set(key, { key, name: `${personLabel(r.person_id)} · ${metricLabel(r.metric_type_id)}`, points: [] });
    }
    byKey.get(key).points.push({ t: parseTime(r.created_date_time), v: r.health_metric, when: r.created_date_time });
  }
  const series = [...byKey.values()].sort((a, b) => a.name.localeCompare(b.name));
  series.forEach((s, i) => {
    s.color = i % SERIES_COLORS;
    s.points.sort((a, b) => a.t - b.t);
  });
  return series;
}

// Draws the line graph into #chart. `x` and `y` map a timestamp / value to SVG
// coordinates inside the plot area (the viewBox minus the CHART margins).
function renderChart(series) {
  const host = $('#chart');
  host.innerHTML = '';
  if (!series.length) {
    host.innerHTML = '<p class="empty-chart">No readings match these filters</p>';
    return;
  }
  const { w, h, l, r, t, b } = CHART;
  // One pass for the extents. (Math.min(...array) would overflow the call
  // stack on very large series.)
  let tMin = Infinity, tMax = -Infinity, vMin = Infinity, vMax = -Infinity, pointCount = 0;
  for (const s of series) {
    for (const p of s.points) {
      if (p.t < tMin) tMin = p.t;
      if (p.t > tMax) tMax = p.t;
      if (p.v < vMin) vMin = p.v;
      if (p.v > vMax) vMax = p.v;
    }
    pointCount += s.points.length;
  }
  if (tMin === tMax) { tMin -= 432e5; tMax += 432e5; }
  const yTicks = niceTicks(vMin, vMax);
  const yMin = yTicks[0];
  const yMax = yTicks[yTicks.length - 1];
  const x = (v) => l + ((v - tMin) / (tMax - tMin)) * (w - l - r);
  const y = (v) => t + (1 - (v - yMin) / (yMax - yMin)) * (h - t - b);

  const svg = svgEl('svg', { viewBox: `0 0 ${w} ${h}`, role: 'img', 'aria-label': 'Line graph of readings over time', id: 'chart-svg' });

  for (const v of yTicks) {
    svg.append(svgEl('line', { class: 'grid', x1: l, x2: w - r, y1: y(v), y2: y(v) }));
    svg.append(svgEl('text', { class: 'tick', x: l - 8, y: y(v) + 4, 'text-anchor': 'end' }, String(v)));
  }
  const xCount = 5;
  for (let i = 0; i < xCount; i++) {
    const tv = tMin + ((tMax - tMin) * i) / (xCount - 1);
    svg.append(svgEl('text', {
      class: 'tick', x: x(tv), y: h - b + 18,
      'text-anchor': i === 0 ? 'start' : i === xCount - 1 ? 'end' : 'middle',
    }, formatTick(tv, tMax - tMin)));
  }
  svg.append(svgEl('line', { class: 'axis', x1: l, x2: w - r, y1: h - b, y2: h - b }));

  for (const s of series) {
    const g = svgEl('g', { class: `series c${s.color}`, 'data-series': s.name });
    if (s.points.length > 1) {
      g.append(svgEl('polyline', {
        class: 'line', fill: 'none',
        points: s.points.map((p) => `${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join(' '),
      }));
    }
    // Past MAX_DOTS points the markers (one DOM node and tooltip each) are
    // dropped and only the lines are drawn; the table below still has the totals.
    for (const p of pointCount > MAX_DOTS ? [] : s.points) {
      const dot = svgEl('circle', { class: 'dot', cx: x(p.t).toFixed(1), cy: y(p.v).toFixed(1), r: 4 });
      dot.append(svgEl('title', {}, `${s.name}\n${p.when}\n${p.v}`));
      g.append(dot);
    }
    svg.append(g);
  }
  host.append(svg);
  if (pointCount > MAX_DOTS) {
    host.insertAdjacentHTML('beforeend',
      `<p class="chart-note">${pointCount} readings: points are hidden for speed. Narrow the filters to see them.</p>`);
  }
}

// Fills the legend and the per-series summary table below the graph.
function renderLegendAndStats(series) {
  $('#chart-legend').innerHTML = series.map((s) =>
    `<li><span class="swatch c${s.color}"></span>${escapeHtml(s.name)}</li>`).join('');
  const fmt = (n) => String(+n.toFixed(2));
  $('#an-stat-rows').innerHTML = series.length
    ? series.map((s) => {
        let min = Infinity, max = -Infinity, sum = 0;
        for (const p of s.points) { if (p.v < min) min = p.v; if (p.v > max) max = p.v; sum += p.v; }
        const vals = s.points;
        const avg = sum / vals.length;
        return `<tr>
          <td data-label="Series"><span class="swatch c${s.color}"></span>${escapeHtml(s.name)}</td>
          <td class="num" data-label="Count">${vals.length}</td>
          <td class="num" data-label="Min">${fmt(min)}</td>
          <td class="num" data-label="Avg">${fmt(avg)}</td>
          <td class="num" data-label="Max">${fmt(max)}</td>
          <td class="num" data-label="Latest">${fmt(vals[vals.length - 1].v)}</td>
        </tr>`;
      }).join('')
    : emptyRow(6, 'No data');
}

// Every reading, loaded once per visit to the Analytics tab. Filtering happens
// in the browser (analyticsRows), so changing a filter needs no new request.
state.allReadings = [];

async function loadAnalytics() {
  state.allReadings = await api.list('health-details');
  renderAnalytics();
}

// Drops the loaded readings and the drawn chart. Called when leaving the
// Analytics tab so a large history isn't held in memory while it isn't shown.
function releaseAnalytics() {
  state.allReadings = [];
  $('#chart').innerHTML = '';
  $('#chart-legend').innerHTML = '';
  $('#an-stat-rows').innerHTML = '';
}

function renderAnalytics() {
  const series = buildSeries(analyticsRows());
  renderChart(series);
  renderLegendAndStats(series);
}

// ---------------------------------------------------------------------------
// Export: download the readings that match the analytics filters

// Columns of an export, in order. Names match the REST API fields, plus the
// person and metric type names so the file is readable without the lookup
// tables.
const EXPORT_COLUMNS = [
  'id', 'person_id', 'person', 'metric_type_id', 'metric_type',
  'health_metric', 'created_date_time',
];

// The filtered readings as flat objects, oldest first (ties broken by id).
function exportRows() {
  const personName = (id) => lookup.person.get(id) ?? '';
  const metricName = (id) => lookup.metric.get(id) ?? '';
  return analyticsRows()
    .map((r) => ({
      id: r.id,
      person_id: r.person_id,
      person: personName(r.person_id),
      metric_type_id: r.metric_type_id,
      metric_type: metricName(r.metric_type_id),
      health_metric: r.health_metric,
      created_date_time: r.created_date_time,
    }))
    .sort((a, b) => String(a.created_date_time).localeCompare(String(b.created_date_time)) || a.id - b.id);
}

// One CSV field, quoted per RFC 4180 when it contains a comma, quote or
// line break. Text starting with = + - @ is prefixed with an apostrophe so
// spreadsheet programs show it as text instead of running it as a formula
// (CSV injection); numbers are never altered.
function csvField(value) {
  if (value === null || value === undefined) return '';
  let text = String(value);
  if (typeof value === 'string' && /^[=+\-@\t\r]/.test(text)) text = "'" + text;
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

// CSV text with a header row and CRLF line endings. The leading byte order
// mark makes Excel read the file as UTF-8, so accented names survive.
function toCsv(rows) {
  const lines = [EXPORT_COLUMNS.join(',')];
  for (const row of rows) lines.push(EXPORT_COLUMNS.map((c) => csvField(row[c])).join(','));
  return '﻿' + lines.join('\r\n') + '\r\n';
}

const toJson = (rows) => JSON.stringify(rows, null, 2) + '\n';

// Saves `text` as a file the user can open. In the Android app the native
// bridge writes it to the Downloads folder (WebView can't download blob:
// URLs); in a browser it is a normal download. Returns the saved file name.
function downloadFile(filename, mime, text) {
  if (window.TmsNative) {
    const res = JSON.parse(window.TmsNative.saveFile(filename, mime, text));
    if (!res.ok) throw new Error(res.error || 'Could not save the file');
    return res.name;
  }
  const url = URL.createObjectURL(new Blob([text], { type: `${mime};charset=utf-8` }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return filename;
}

function exportReadings(format) {
  const rows = exportRows();
  if (!rows.length) {
    showMessage('Nothing to export: no readings match these filters', 'error');
    return;
  }
  const d = new Date();
  const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const csv = format === 'csv';
  run(async () => {
    const saved = downloadFile(
      `track-my-sugar-readings-${today}.${format}`,
      csv ? 'text/csv' : 'application/json',
      csv ? toCsv(rows) : toJson(rows));
    showMessage(`Exported ${rows.length} reading${rows.length === 1 ? '' : 's'} to ${saved}`);
  });
}

$('#an-export-csv').addEventListener('click', () => exportReadings('csv'));
$('#an-export-json').addEventListener('click', () => exportReadings('json'));

for (const sel of ['#an-person', '#an-metric', '#an-from', '#an-to']) {
  $(sel).addEventListener('change', renderAnalytics);
}
// The filters apply as you change them; Enter must not submit the form (a page reload).
$('#analytics-filters').addEventListener('submit', (e) => e.preventDefault());
$('#an-clear').addEventListener('click', () => {
  $('#an-person').value = '';
  $('#an-metric').value = '';
  $('#an-from').value = '';
  $('#an-to').value = '';
  renderAnalytics();
});

// ---------------------------------------------------------------------------
// Tabs & startup

const TOP_PANELS = ['readings', 'analytics', 'administration'];

document.querySelectorAll('header .tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('header .tab').forEach((t) => t.classList.toggle('active', t === tab));
    TOP_PANELS.forEach((id) => { $('#' + id).hidden = id !== tab.dataset.tab; });
    if (tab.dataset.tab !== 'analytics') releaseAnalytics();
    // Re-read the data so the chart and lists reflect any recent changes.
    if (tab.dataset.tab === 'analytics') run(async () => { await Promise.all([loadPersons(), loadMetricTypes()]); await loadAnalytics(); });
    if (tab.dataset.tab === 'readings') { setNowDefault(); run(loadReadings); }
  });
});

// Administration sub-sections (People / Metric types).
document.querySelectorAll('.subnav .subtab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.subnav .subtab').forEach((t) => t.classList.toggle('active', t === tab));
    document.querySelectorAll('.subpanel').forEach((p) => { p.hidden = p.id !== tab.dataset.subtab; });
  });
});

// Shows the app version (the single source is version.txt, which the Android
// build also reads). A failure just leaves the badge hidden.
fetch('version.txt', { cache: 'no-cache' })
  .then((res) => (res.ok ? res.text() : Promise.reject(new Error('no version'))))
  .then((text) => {
    const version = text.trim();
    if (!/^\d+\.\d+\.\d+$/.test(version)) return;
    const badge = $('#app-version');
    badge.textContent = `v${version}`;
    badge.hidden = false;
  })
  .catch(() => {});

run(async () => {
  await Promise.all([loadPersons(), loadMetricTypes()]);
  await loadReadings();
});
