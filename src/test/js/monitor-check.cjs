const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

class Node {
  constructor() {
    this.children = [];
    this.fields = new Map();
    this.classList = { add() {} };
    this.dataset = {};
  }
  append(node) { node.parent = this; this.children.push(node); }
  replaceChildren() { this.children = []; }
  querySelector(selector) {
    if (selector === '.empty-state') return this.children.find(node => node.className === 'empty-state');
    if (!this.fields.has(selector)) this.fields.set(selector, new Node());
    return this.fields.get(selector);
  }
  remove() { this.parent.children = this.parent.children.filter(node => node !== this); }
  cloneNode() { return new Node(); }
  addEventListener() {}
}
const timeline = new Node();
const elements = new Map([['#timeline', timeline]]);
const template = new Node();
template.content = { firstElementChild: new Node() };
elements.set('#message-template', template);
const sandbox = {
  document: {
    querySelector(selector) {
      if (!elements.has(selector)) elements.set(selector, new Node());
      return elements.get(selector);
    },
    createElement() { return new Node(); },
  },
  console,
  fetch() { throw new Error('unexpected fetch'); },
};
vm.createContext(sandbox);
const source = fs.readFileSync('src/main/resources/monitor/app.js', 'utf8');
// Exercise the UI functions without starting a browser, network, or refresh timer.
vm.runInContext(source.slice(0, source.indexOf('\nloadActiveSessions()\n')), sandbox);
const event = (id, text, speaker = 'caller', segmentId = 'segment-c') => ({
  id, text, speaker, segmentId, sessionId: 'call-1', timestamp: '2026-10-13T15:00:00+09:00', final: false,
});
const run = text => vm.runInContext(text, sandbox);
sandbox.one = event(1, '予約を');
sandbox.two = event(3, '予約を変更したい');
sandbox.ai = event(2, 'はい', 'assistant', 'segment-a');
run('renderEvent(one); renderEvent(ai); renderEvent(two); renderEvent(two);');
assert.equal(timeline.children.length, 2);
assert.equal(timeline.children[0].querySelector('.text').textContent, '予約を変更したい');
run('replaceTimeline([one, ai], "call-1")');
assert.equal(timeline.children.length, 2);
assert.equal(timeline.children[1].querySelector('.text').textContent, '予約を変更したい');
run('renderEvent(one)');
assert.equal(timeline.children.length, 2);

(async () => {
  let respond;
  sandbox.fetch = () => new Promise(resolve => { respond = resolve; });
  const pending = run('loadSessionHistory("call-1")');
  run('selectSession("call-2", {loadHistory: false})');
  respond({ ok: true, json: async () => ({ events: [event(9, 'old history')] }) });
  await pending;
  assert.equal(timeline.children.length, 0, 'old request cannot replace newly selected session');
  run('selectSession("call-1", {loadHistory: false})');
  const sameSession = run('loadSessionHistory("call-1")');
  run('renderEvent(two)');
  respond({ ok: true, json: async () => ({ events: [event(1, '予約を')] }) });
  await sameSession;
  assert.equal(timeline.children.length, 1);
  assert.equal(timeline.children[0].querySelector('.text').textContent, '予約を変更したい');
  console.log('Monitor checks passed: snapshots, overlapping speakers, history races, duplicate events');
})().catch(error => { console.error(error); process.exitCode = 1; });
