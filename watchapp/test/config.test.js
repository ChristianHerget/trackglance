'use strict';

const assert = require('assert');
const {JSDOM} = require('jsdom');
const config = require('../src/pkjs/index.js');

assert.strictEqual(config.VERSION, 4);
assert.strictEqual(config.RELEASE, '0.2.2');
assert.strictEqual(config.LIMIT.pages, 4);
assert.strictEqual(config.KEYS.locusId, 51);
assert.strictEqual(config.KEYS.fingerprintA, 52);
assert.strictEqual(config.KEYS.fingerprintB, 53);
assert.strictEqual(config.TYPES.recordingContext, 10);
assert.strictEqual(config.TYPES.requestRuntimeConfig, 11);
assert(config.catalogComplete());
assert(config.validLocusId('0'));
assert(config.validLocusId('-1'));
assert(config.validLocusId('-9223372036854775808'));
assert(!config.validLocusId('-0'));
assert(!config.validLocusId('-9223372036854775809'));

const catalog = [
  {id: '30', name: 'Cycling'},
  {id: '10', name: 'Hiking'},
  {id: '20', name: 'Running'},
  {id: '40', name: 'Paddling'},
];
let reconciled = config.reconcile(config.defaultsFor('en'), catalog, 'en');
assert(reconciled.authoritative);
assert.strictEqual(reconciled.config.activities.length, 4);
assert.deepStrictEqual(config.activity(reconciled.config, '10').pages[0].metrics, [1,3,10,11,5,22]);
assert.deepStrictEqual(config.activity(reconciled.config, '20').pages[0].metrics, [1,3,8,9,11,22]);
assert.deepStrictEqual(config.activity(reconciled.config, '30').pages[0].metrics, [1,3,5,6,7,22]);
assert.deepStrictEqual(config.activity(reconciled.config, '40').pages[0].metrics, [1,3,5,6,10,22]);
assert(reconciled.config.activities.every(activity => activity.pages[0].name === 'Default'));

const german = config.reconcile(config.defaultsFor('de'), [{id:'1', name:'Wandern'}], 'de').config;
assert.strictEqual(german.activities[0].pages[0].name, 'Standard');
assert.deepStrictEqual(german.activities[0].pages[0].metrics, [1,3,10,11,5,22]);
assert.strictEqual(
  config.reconcile(german, [{id:'1', name:'Wandern'}], 'en').config.activities[0].pages[0].name,
  'Standard',
  'generated display names are not retranslated',
);

const legacyWire = 'dark|0|1|7\nTrail|Hiking|0|1,3,11|legacy-page';
const legacy = config.parse(legacyWire);
assert(legacy && legacy.legacy);
const migrated = config.reconcile(legacy, [{id:'987',name:'Hiking'}], 'en').config;
assert.strictEqual(migrated.activities[0].locusId, '987');
assert.strictEqual(migrated.activities[0].pages[0].id, 'legacy-page');
assert.strictEqual(migrated.activities[0].pages[0].name, 'Trail');

const retained = config.reconcile(reconciled.config, [
  {id:'10',name:'Hiking renamed'}, {id:'50',name:'New activity'}
], 'en').config;
assert.deepStrictEqual(retained.activities.map(activity => activity.locusId), ['10','50']);
assert.strictEqual(retained.activities[0].locusName, 'Hiking renamed');
assert.strictEqual(retained.activities[0].pages[0].name, 'Default');
assert.strictEqual(config.reconcile(retained, [], 'en').authoritative, false);
assert.deepStrictEqual(config.reconcile(retained, [], 'en').config, retained);
assert.deepStrictEqual(config.reconcile(retained, [{id:'bad|id',name:'Bad'}], 'en').config, retained);
assert.strictEqual(
  config.reconcile(retained, [{id:'10',name:'One'},{id:'10',name:'Two'}], 'en').authoritative,
  false,
);

const unlimitedCatalog = Array.from({length: 32}, (_, index) => ({id:String(index + 1),name:`Activity ${index}`}));
const unlimited = config.reconcile(config.defaultsFor('en'), unlimitedCatalog, 'en').config;
assert.strictEqual(unlimited.activities.length, 32);
assert(config.validate(unlimited));

const editable = config.reconcile(config.defaultsFor('en'), catalog.slice(0, 2), 'en').config;
const hiking = config.activity(editable, '10');
assert(config.add(editable, '10', hiking.pages[0], 'en'));
assert(config.add(editable, '10', hiking.pages[0], 'en'));
assert(config.add(editable, '10', hiking.pages[0], 'en'));
assert.strictEqual(hiking.pages.length, 4);
assert(!config.add(editable, '10', hiking.pages[0], 'en'));
assert(config.move(editable, '10', 3, 0));
assert(config.rename(editable, '10', 0, 'Priority'));
assert(!config.rename(editable, '10', 1, 'priority'));
while (hiking.pages.length > 1) assert(config.remove(editable, '10', 0, 'en'));
const oldOnlyId = hiking.pages[0].id;
assert(config.remove(editable, '10', 0, 'en'));
assert.strictEqual(hiking.pages.length, 1);
assert.notStrictEqual(hiking.pages[0].id, oldOnlyId);

const sourcePage = hiking.pages[0];
assert(config.moveToActivity(editable, '10', 0, '30', 'en'));
assert.strictEqual(hiking.pages.length, 1, 'moving the last page recreates the source default');
assert(config.activity(editable, '30').pages.some(page => page.id === sourcePage.id));
while (config.activity(editable, '30').pages.length < 4) {
  assert(config.add(editable, '30', config.activity(editable, '30').pages[0], 'en'));
}
assert(!config.moveToActivity(editable, '10', 0, '30', 'en'));

const reset = config.resetLibrary(catalog, 'en');
assert(reset.authoritative);
assert.strictEqual(reset.config.theme, 'dark');
assert.strictEqual(reset.config.watchHrToLocus, false);
assert(reset.config.activities.every(activity => activity.pages.length === 1));
assert.deepStrictEqual(config.activity(reset.config, '20').pages[0].metrics, [1,3,8,9,11,22]);

const wire = config.projection(editable, '10');
assert(wire.startsWith('dark|0|5|10|'));
assert(wire.split('\n').length <= 5);
assert(Buffer.byteLength(wire, 'utf8') <= config.LIMIT.configBytes);
assert.deepStrictEqual(config.fingerprints(editable), config.fingerprints(config.parse(config.serialize(editable))));
const changed = JSON.parse(config.serialize(editable));
changed.theme = 'light';
assert.notDeepStrictEqual(config.fingerprints(editable), config.fingerprints(changed));

assert.deepStrictEqual(config.profilePayload('10|Hiking\n20|Running'), catalog.slice(1,3).map((item, index) => index === 0 ? {id:'10',name:'Hiking'} : {id:'20',name:'Running'}));
assert.strictEqual(config.profilePayload(''), null);
assert.strictEqual(config.profilePayload('10|Hiking\n10|Renamed'), null);
assert.deepStrictEqual(config.profilePayload('0|Hiking\n-1|Internal'), [{id:'0',name:'Hiking'},{id:'-1',name:'Internal'}]);
assert.strictEqual(config.profilePayload('-0|Hiking'), null);
assert.strictEqual(config.profilePayload('10|Bad|Name'), null);

const transfer = new config.Transfer();
assert.strictEqual(transfer.accept({33:7,30:1,31:2,4:0,32:'king'}), null);
assert.strictEqual(transfer.accept({33:7,30:0,31:2,4:0,32:'10|Hi'}), null);
assert.deepStrictEqual(transfer.accept({33:7,30:1,31:2,4:0,32:'king'}), {id:7,result:0,payload:'10|Hiking'});

const floorValues = {};
const floorStorage = {
  getItem: key => Object.prototype.hasOwnProperty.call(floorValues, key) ? floorValues[key] : null,
  setItem: (key, value) => { floorValues[key] = value; },
};
const marked = id => ({33:id,30:0,31:1,4:0,32:'10|Hiking',39:1});
assert.deepStrictEqual(
  new config.Transfer(floorStorage, 'floor').accept(marked(7)),
  {id:7,result:0,payload:'10|Hiking'},
);
const reopenedTransfer = new config.Transfer(floorStorage, 'floor');
assert.strictEqual(reopenedTransfer.accept(marked(7)), null, 'completed transfer cannot replay');
assert.strictEqual(reopenedTransfer.accept(marked(6)), null, 'older transfer cannot replace floor');
assert.strictEqual(reopenedTransfer.accept({33:8,30:0,31:1,4:0,32:'10|Hiking'}), null, 'durable floor rejects unmarked traffic');
assert.deepStrictEqual(reopenedTransfer.accept(marked(8)), {id:8,result:0,payload:'10|Hiking'});

const serialValues = {};
const serialStorage = {
  getItem: key => Object.prototype.hasOwnProperty.call(serialValues, key) ? serialValues[key] : null,
  setItem: (key, value) => { serialValues[key] = value; },
};
const serial = new config.DurableSerialCounter(serialStorage, 'serial');
assert.strictEqual(serial.reserve(), 0);
assert.strictEqual(serial.reserve(), 1);
serialValues.serial = '01';
assert.strictEqual(serial.reserve(), null, 'corrupt transfer serial fails closed');

const pageUrl = config.settingsPage(reconciled.config, catalog, 'en', 'fresh', null);
const html = decodeURIComponent(pageUrl.slice(pageUrl.indexOf(',') + 1));
const gabbroHtml = decodeURIComponent(config.settingsPage(
  reconciled.config, catalog, 'en', 'fresh', null, false,
).split(',').slice(1).join(','));
assert(gabbroHtml.includes('class="heart-rate-settings hidden"'), 'Gabbro hides watch heart-rate controls');
assert(html.includes('class="heart-rate-settings"'), 'Emery/default settings expose watch heart-rate controls');
let closedSettings = null;
const dom = new JSDOM(html, {runScripts:'dangerously', beforeParse(window) {
  window.__pebbleConfigClose = value => { closedSettings = value; };
  window.confirm = () => true;
  window.alert = () => {};
}});
const headings = [...dom.window.document.querySelectorAll('h2')].map(node => node.textContent);
assert.deepStrictEqual(headings.slice(0, 4), ['Cycling','Hiking','Paddling','Running']);
assert.strictEqual(dom.window.document.querySelectorAll('.page').length, 4);
assert(dom.window.document.querySelectorAll('.handle').length >= 4);
assert(!html.includes('id="addProfile"'), 'activity groups do not expose an add button');
assert(dom.window.document.querySelector('#reset'), 'settings expose an explicit canonical reset');
dom.window.document.querySelector('.page .name').click();
assert(!dom.window.document.querySelector('#editor').classList.contains('hidden'), 'tapping a page opens Edit');
dom.window.document.querySelector('#editorCancel').click();
dom.window.document.querySelector('#save').click();
assert.deepStrictEqual(JSON.parse(closedSettings), reconciled.config, 'Save returns canonical JSON once');
dom.window.c.activities[0].pages[0].name = '🥾'.repeat(20);
dom.window.document.querySelector('.clone').click();
assert(dom.window.c.activities[0].pages.every(page => config.validName(page.name)));

assert(config.validHeartRateMessage({0:4,1:8,35:'0.2.2',7:1,38:2,6:3,37:100}));
assert(!config.validHeartRateMessage({0:4,1:8,35:'0.2.0',7:1,38:2,6:3,37:100}));
assert.deepStrictEqual(config.configResultMessage({0:4,1:9,35:'0.2.2',33:4,4:0}), {id:4,result:0});
