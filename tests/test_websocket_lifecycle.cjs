// Exercise the real hook with React, a DOM, fake sockets and controlled timers.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '..');
const localRequire = createRequire(path.join(root, 'frontend/nextjs/package.json'));
const React = localRequire('react');
const { JSDOM } = localRequire('jsdom');
const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost:3002' });
global.window = dom.window;
global.document = dom.window.document;
global.IS_REACT_ACT_ENVIRONMENT = true;
const { createRoot } = localRequire('react-dom/client');
const act = React.act || localRequire('react-dom/test-utils').act;
const ts = localRequire('typescript');
const timers = new Map();
let timerId = 0;
window.setInterval = callback => { timers.set(++timerId, callback); return timerId; };
window.clearInterval = id => timers.delete(id);
class Socket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static instances = [];
  constructor(url) { this.url = url; this.readyState = 0; this.sent = []; this.closed = 0; Socket.instances.push(this); }
  send(value) { this.sent.push(value); }
  close() { this.closed++; this.readyState = 2; }
  open() { this.readyState = 1; this.onopen?.(); }
  message(data) { this.onmessage?.({ data: JSON.stringify(data) }); }
}
const source = fs.readFileSync(path.join(root, 'frontend/nextjs/hooks/useWebSocket.ts'), 'utf8');
const sandbox = { exports: {}, window, WebSocket: Socket, console,
  require: name => name === 'react' ? React : { getHost: () => 'http://localhost:8000' } };
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, sandbox);
let current;
function Harness() {
  const [events, setEvents] = React.useState([]);
  const [answer, setAnswer] = React.useState('');
  const [loading, setLoading] = React.useState(true);
  const [, setFeedback] = React.useState(false);
  const [, setQuestion] = React.useState(false);
  const connection = sandbox.exports.useWebSocket(setEvents, setAnswer, setLoading, setFeedback, setQuestion);
  current = { ...connection, events, answer, loading };
  return null;
}
const view = createRoot(document.getElementById('root'));
const settings = { report_type: 'research_report', report_source: 'hybrid', tone: 'Objective', document_scope: 'selected', document_ids: ['doc-1'] };
act(() => view.render(React.createElement(React.StrictMode, null, React.createElement(Harness))));
act(() => current.initializeWebSocket('first', settings));
const first = Socket.instances.at(-1);
act(() => current.closeConnection());
act(() => first.open());
assert.equal(first.closed, 1);
assert.equal(first.sent.length, 0, 'stopped connecting socket must not start research');
assert.equal(timers.size, 0);
act(() => current.initializeWebSocket('second', settings));
const second = Socket.instances.at(-1);
act(() => second.open());
assert.equal(current.socket, second, 'hook exposes current connection after rerender');
const start = JSON.parse(second.sent[0].slice(6));
assert.deepEqual(start.document_ids, ['doc-1']);
assert.equal(start.document_scope, 'selected');
assert.equal(timers.size, 1);
act(() => second.message({ type: 'report', output: 'partial report' }));
assert.equal(current.answer, 'partial report');
act(() => current.initializeWebSocket('third', settings));
const third = Socket.instances.at(-1);
act(() => third.open());
const count = current.events.length;
act(() => { second.onclose(); second.message({ type: 'report', output: 'stale' }); second.onerror(new Error('stale')); });
assert.equal(current.socket, third);
assert.equal(current.events.length, count);
assert.equal(current.answer, 'partial report');
assert.equal(timers.size, 1, 'old close cannot clear current heartbeat');
act(() => { for (const tick of timers.values()) tick(); });
assert.equal(third.sent.at(-1), 'ping');
act(() => current.closeConnection());
assert.equal(timers.size, 0);
assert.equal(current.answer, 'partial report', 'stopping preserves results');
act(() => current.initializeWebSocket('fourth', settings));
const fourth = Socket.instances.at(-1);
act(() => { fourth.open(); fourth.onclose(); });
assert.equal(current.socket, null);
assert.equal(current.loading, false);
assert.equal(timers.size, 0);
act(() => current.initializeWebSocket('unmount', settings));
const last = Socket.instances.at(-1);
act(() => view.unmount());
act(() => last.open());
assert.equal(last.closed, 1);
assert.equal(last.sent.length, 0);
assert.equal(timers.size, 0);
dom.window.close();
console.log('WebSocket lifecycle regression checks passed.');
