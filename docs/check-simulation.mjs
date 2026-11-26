// Self-contained demo contract checks. Uses only Node's standard library.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('./app-simulation.html', import.meta.url), 'utf8');
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
scripts.forEach(script => new vm.Script(script));
const context = vm.createContext({
  document: { querySelector: () => ({ content: '' }) },
  Intl, setTimeout, clearTimeout,
});
vm.runInContext(scripts.find(s => s.includes('const Thor =')), context);
vm.runInContext(scripts.find(s => s.includes('const ThorSearch =')), context);
const thor = vm.runInContext('Thor', context);
const search = vm.runInContext('ThorSearch', context);
const features = thor.FUNCS;
assert.equal(thor.SLOTS.length, 33);
assert.equal(new Set(features.map(f => f.id)).size, features.length);
assert.equal(new Set(thor.SLOTS.map(s => s.id)).size, 33);
for (const id of ['last', 'closeapps', 'record', 'macro', 'gyro', 'calibrate', 'media', 'power', 'standby', 'status', 'refresh', 'lights', 'mouse', 'dnd', 'mic', 'home-mode', 'inputs', 'pause', 'exceptions', 'overview', 'backup', 'welcome', 'updates', 'access', 'privacy']) {
  assert.ok(thor.FN[id], `Missing feature: ${id}`);
}
assert.ok(thor.FN.appctl.opts.includes('Restart'));
assert.ok(thor.FN.volume.opts.includes('Toggle mute'));
assert.ok(thor.FN.macro.comboOnly);
assert.ok(features.filter(f => f.cat === 'setup').every(f => f.fx === 'message'));
const hits = query => search.rows(features, query);
const has = (query, id, option = null) => assert.ok(hits(query).some(h => h.f.id === id && h.option === option), `${query} should find ${id} / ${option}`);
has('louder', 'volume', 'Up');
has('  UP   volume ', 'volume', 'Up');
has('picture', 'shot');
has('commentary', 'record');
has('alt tab', 'last');
has('drift', 'calibrate');
has('max cooling', 'fan', 'Sports');
has('battery saving', 'refresh', '60 Hz');
has('turning speed', 'gyro');
has('push-to-talk', 'record');
has('backup', 'backup');
assert.equal(hits('no-such-demo-action-zzzzz').length, 0);
assert.ok(hits('   ').every(h => h.option === null));
assert.ok(hits('in front').every(h => h.option === null), 'Options must not inherit group descriptions');
const labels = Array.from(hits('a'), h => h.label);
const collator = new Intl.Collator('en', { sensitivity: 'base' });
assert.deepEqual(labels, [...labels].sort(collator.compare));
for (const feature of features) {
  has(feature.name, feature.id);
  for (const option of feature.opts || []) has(`${feature.name} ${option}`, feature.id, option);
}
// Execute every screen-effect route and option against minimal display nodes. This checks
// cancellation-independent logic; real browser layout/input remains an owner check.
class Node {
  constructor() {
    this.style = { setProperty() {} };
    this.classList = { add() {}, remove() {}, toggle() {} };
    this.firstElementChild = { textContent: '' };
    this.nodes = new Map();
    this.dataset = {};
  }
  querySelector(selector) {
    if (!this.nodes.has(selector)) this.nodes.set(selector, new Node());
    return this.nodes.get(selector);
  }
  querySelectorAll() { return Array.from({ length: 8 }, () => new Node()); }
  getBBox() { return {}; }
}
const root = new Node();
Object.defineProperty(root, 'firstElementChild', { get() { return this.querySelector('.device'); } });
thor.build(root);
const clock = { sleep: async () => {}, alive: () => true };
for (const feature of features) {
  for (const option of feature.opts || [null]) await thor.fx(clock, feature.id, option);
}
for (const slot of thor.SLOTS) {
  await thor.playSlot(clock, slot.id, 'Back');
  if (slot.id !== 'android-home') await thor.playSlot(clock, slot.id, 'Home');
}
assert.ok(!html.includes('all empty on a fresh install'));
assert.ok(!html.includes('v1.5.0'));
console.log(`Simulation checks passed: ${features.length} features; 33 Back inputs; Home examples; all effect/options routes; search words, synonyms, A–Z and option results.`);
