'use strict';

const assert = require('assert');
const {JSDOM} = require('jsdom');
const config = require('../src/pkjs/index.js');

assert.strictEqual(config.VERSION, 4);
assert.strictEqual(config.RELEASE, '0.2.3');
assert.strictEqual(config.LIMIT.pages, 4);
assert.strictEqual(config.KEYS.locusId, 51);
assert.strictEqual(config.KEYS.fingerprintA, 52);
assert.strictEqual(config.KEYS.fingerprintB, 53);
assert.strictEqual(config.TYPES.recordingContext, 10);
assert.strictEqual(config.TYPES.requestRuntimeConfig, 11);
assert(config.catalogComplete());
const supportedLocales = ['en_US','fr_FR','de_DE','es_ES','it_IT','pt_PT','zh_CN','zh_TW'];
assert.deepStrictEqual(supportedLocales.map(config.locale), ['en','fr','de','es','it','pt','zh_CN','zh_TW']);
assert.strictEqual(config.locale('en_CN'), 'zh_CN');
assert.strictEqual(config.locale('en_TW'), 'zh_TW');
assert.strictEqual(config.locale('nl_NL'), 'en');
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
assert(reconciled.config.activities.every(activity => activity.pages.length === 4));
assert(reconciled.config.activities.every(activity => activity.pages[0].name === null));
assert(reconciled.config.activities.every(activity => activity.pages.slice(1).every(page => page.metrics.length === 0)));

const german = config.reconcile(config.defaultsFor('de'), [{id:'1', name:'Wandern'}], 'de').config;
assert.strictEqual(german.activities[0].pages[0].name, null);
assert.deepStrictEqual(german.activities[0].pages[0].metrics, [1,3,10,11,5,22]);
assert.strictEqual(
  config.projection(german, '1', 'de').split('\n')[1].split('|')[0],
  'Seite 1',
  'automatic display names are projected from locale',
);

const legacyWire = 'dark|0|1|7\nTrail|Hiking|0|1,3,11|legacy-page';
const legacy = config.parse(legacyWire);
assert(legacy && legacy.legacy);
const migrated = config.reconcile(legacy, [{id:'987',name:'Hiking'}], 'en').config;
assert.strictEqual(migrated.activities[0].locusId, '987');
assert.strictEqual(migrated.activities[0].pages[0].id, 'legacy-page');
assert.strictEqual(migrated.activities[0].pages[0].name, 'Trail');
const schemaTwo = {schema:2,theme:'light',watchHrToLocus:true,heartRateIntervalSeconds:7,
  activities:[{locusId:'9',locusName:'Walk',pages:[{id:'kept',name:'Custom',metrics:[1,3]}]}]};
const schemaThree = config.migrate(schemaTwo);
assert.strictEqual(schemaThree.schema, 3);
assert.strictEqual(schemaThree.activities[0].pages.length, 4);
assert.deepStrictEqual(schemaThree.activities[0].pages[0], {id:'kept',type:'metrics',name:'Custom',metrics:[1,3]});

const retained = config.reconcile(reconciled.config, [
  {id:'10',name:'Hiking renamed'}, {id:'50',name:'New activity'}
], 'en').config;
assert.deepStrictEqual(retained.activities.map(activity => activity.locusId), ['10','50']);
assert.strictEqual(retained.activities[0].locusName, 'Hiking renamed');
assert.strictEqual(retained.activities[0].pages[0].name, null);
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

const editable = config.reconcile(config.defaultsFor('en'), catalog, 'en').config;
const hiking = config.activity(editable, '10');
assert.strictEqual(hiking.pages.length, 4);
assert(config.add(editable, '10', 1, 1));
assert(config.add(editable, '10', 1, 3));
assert(!config.add(editable, '10', 1, 3));
assert(config.rename(editable, '10', 0, 'Priority'));
assert(config.rename(editable, '10', 1, 'Priority'), 'duplicate display names are allowed');
assert(config.moveMetric(editable, '10', 1, 1, 0));
assert(config.remove(editable, '10', 1, 0));
assert(config.remove(editable, '10', 1, 0));
assert(!config.remove(editable, '10', 1, 0));
assert(config.move(editable, '10', 3, 0));

const reset = config.resetLibrary(catalog, 'en');
assert(reset.authoritative);
assert.strictEqual(reset.config.theme, 'dark');
assert.strictEqual(reset.config.watchHrToLocus, false);
assert(reset.config.activities.every(activity => activity.pages.length === 4));
assert.deepStrictEqual(config.activity(reset.config, '20').pages[0].metrics, [1,3,8,9,11,22]);
const beforeOther = JSON.stringify(config.activity(editable, '20').pages);
assert(config.resetActivity(editable, '10'));
assert.strictEqual(JSON.stringify(config.activity(editable, '20').pages), beforeOther);
editable.theme = 'light'; config.resetGeneral(editable); assert.strictEqual(editable.theme, 'dark');

const wire = config.projection(editable, '10');
assert(wire.startsWith('dark|0|5|10|'));
assert(wire.split('\n').length <= 5);
assert.strictEqual(wire.split('\n').length, 2, 'inactive slots are not projected');
assert(Buffer.byteLength(wire, 'utf8') <= config.LIMIT.configBytes);
assert.deepStrictEqual(config.fingerprints(editable), config.fingerprints(config.parse(config.serialize(editable))));
assert.notDeepStrictEqual(config.fingerprints(editable, 'en'), config.fingerprints(editable, 'de'));
const changed = JSON.parse(config.serialize(editable));
changed.theme = 'light';
assert.notDeepStrictEqual(config.fingerprints(editable), config.fingerprints(changed));
const duplicateNames = JSON.parse(config.serialize(editable));
duplicateNames.activities[0].pages[0].name = 'Same';
duplicateNames.activities[0].pages[1].name = 'Same';
duplicateNames.activities[0].pages[1].metrics = [1];
assert(config.validate(duplicateNames));
const invalidType = JSON.parse(config.serialize(editable));
invalidType.activities[0].pages[0].type = 'map';
assert(!config.validate(invalidType));

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
  window.__scrollDeltas = [];
  window.scrollBy = (_x, y) => { window.__scrollDeltas.push(y); };
  window.requestAnimationFrame = callback => { window.__dragFrame = callback; return 1; };
  window.cancelAnimationFrame = () => {};
}});
const activities = [...dom.window.document.querySelectorAll('.activity .label')].map(node => node.textContent);
assert.deepStrictEqual(activities, ['Cycling','Hiking','Paddling','Running']);
const generalLink = dom.window.document.querySelector('#generalOpen');
const activitiesHeading = dom.window.document.querySelector('.section-title');
assert.strictEqual(generalLink.querySelector('.label').textContent, 'General settings');
assert(generalLink.compareDocumentPosition(activitiesHeading) & dom.window.Node.DOCUMENT_POSITION_FOLLOWING,
  'General settings is a separate navigation row above Activities');
assert(!html.includes('id="mapping"'), 'activity editor has no Locus mapping dropdown');
dom.window.document.querySelector('.activity').click();
assert.strictEqual(dom.window.document.querySelectorAll('.page').length, 4);
assert.strictEqual(dom.window.document.querySelectorAll('.badge')[0].textContent, '6/6');
assert.strictEqual(dom.window.document.querySelectorAll('.badge')[1].textContent, '0/6');
assert(dom.window.document.querySelectorAll('.handle').length >= 8, 'pages and metrics expose keyboard reorder actions');
assert(html.includes('margin:0 12px 12px 36px') && html.includes('border-left:2px solid var(--border)'),
  'metrics are indented beneath their page with a visual rail');
const fullAdd = dom.window.document.querySelectorAll('.add')[0];
assert(fullAdd.disabled, 'Add Metric is disabled at 6/6');
const fullCount = dom.window.draft.pages[0].metrics.length;
fullAdd.click();
assert.strictEqual(dom.window.draft.pages[0].metrics.length, fullCount, 'disabled Add Metric does nothing');

function key(target, value) {
  target.dispatchEvent(new dom.window.KeyboardEvent('keydown', {key:value, bubbles:true}));
}
function touch(target, type, x, y) {
  const event = new dom.window.Event(type, {bubbles:true, cancelable:true});
  const points = type === 'touchend' || type === 'touchcancel' ? [] : [{clientX:x, clientY:y}];
  Object.defineProperty(event, 'touches', {value:points});
  Object.defineProperty(event, 'changedTouches', {value:[{clientX:x, clientY:y}]});
  target.dispatchEvent(event);
}
function setPageGeometry(window, height = 250, gap = 50) {
  [...window.document.querySelectorAll('.page')].forEach((page, index) => {
    page.getBoundingClientRect = () => ({left:0, top:index * (height + gap), width:320, height});
  });
}
function setMetricGeometry(page, top) {
  [...page.querySelectorAll('.metric')].forEach((metric, index) => {
    metric.getBoundingClientRect = () => ({left:40, top:top + index * 50, width:260, height:44});
  });
}

const pageBeforeKeyboard = dom.window.draft.pages.map(page => page.id);
let keyboardHandle = dom.window.document.querySelector('.handle[data-key="page"][data-index="1"]');
key(keyboardHandle, ' ');
key(keyboardHandle, 'ArrowUp');
key(dom.window.document.activeElement, ' ');
assert.strictEqual(dom.window.draft.pages.map(page => page.id).join(','),
  [pageBeforeKeyboard[1], pageBeforeKeyboard[0], pageBeforeKeyboard[2], pageBeforeKeyboard[3]].join(','));
assert.strictEqual(dom.window.document.activeElement.dataset.index, '0', 'keyboard reorder restores handle focus');
assert(dom.window.document.querySelector('#reorderStatus').textContent.includes('Dropped'));

const pagesBeforeDrag = dom.window.draft.pages.map(page => page.id);
const draggedHandle = dom.window.document.querySelector('.handle[data-key="page"][data-index="0"]');
setPageGeometry(dom.window, 80, 20);
touch(draggedHandle, 'touchstart', 20, 60);
touch(dom.window.document.querySelector('#activityEditor'), 'touchmove', 20, 390);
touch(dom.window.document.querySelector('#activityEditor'), 'touchend', 20, 390);
assert.strictEqual(dom.window.draft.pages[3].id, pagesBeforeDrag[0], 'pointer drag reorders page slots');

const firstActivePageIndex = dom.window.draft.pages.findIndex(page => page.metrics.length === 6);
const metricKey = `metric-${firstActivePageIndex}`;
const metricsBeforeKeyboard = dom.window.draft.pages[firstActivePageIndex].metrics.slice();
keyboardHandle = dom.window.document.querySelector(`.handle[data-key="${metricKey}"][data-index="0"]`);
key(keyboardHandle, 'Enter');
key(keyboardHandle, 'ArrowDown');
key(dom.window.document.activeElement, 'Enter');
assert.strictEqual(dom.window.draft.pages[firstActivePageIndex].metrics.slice(0, 2).join(','),
  [metricsBeforeKeyboard[1], metricsBeforeKeyboard[0]].join(','), 'keyboard reorders metrics within their page');
const metricsBeforeDrag = dom.window.draft.pages[firstActivePageIndex].metrics.slice();
const activePageElement = dom.window.document.querySelectorAll('.page')[firstActivePageIndex];
activePageElement.getBoundingClientRect = () => ({left:0, top:100, width:320, height:400});
setMetricGeometry(activePageElement, 120);
const draggedMetric = activePageElement.querySelector('.metric .handle[data-index="0"]');
touch(draggedMetric, 'touchstart', 60, 130);
touch(dom.window.document.querySelector('#activityEditor'), 'touchmove', 60, 410);
touch(dom.window.document.querySelector('#activityEditor'), 'touchend', 60, 410);
assert.strictEqual(dom.window.draft.pages[firstActivePageIndex].metrics[5], metricsBeforeDrag[0],
  'touch drag reorders metrics within its page');

dom.window.draft.pages[0].metrics = [1,2];
dom.window.draft.pages[1].metrics = [3,4,5,6,7,8];
dom.window.draft.pages[2].metrics = [];
dom.window.drawPages();
setPageGeometry(dom.window);
setMetricGeometry(dom.window.document.querySelectorAll('.page')[0], 20);
const crossingMetric = dom.window.document.querySelectorAll('.page')[0].querySelector('.metric .handle');
touch(crossingMetric, 'touchstart', 60, 30);
touch(dom.window.document.querySelector('#activityEditor'), 'touchmove', 60, 760);
dom.window.__dragFrame();
assert(dom.window.__scrollDeltas.some(delta => delta > 0), 'dragging near the viewport edge auto-scrolls');
touch(dom.window.document.querySelector('#activityEditor'), 'touchmove', 60, 350);
assert(dom.window.document.querySelectorAll('.page')[1].classList.contains('drop-unavailable'),
  'a full page is unavailable as a destination');
touch(dom.window.document.querySelector('#activityEditor'), 'touchmove', 60, 650);
touch(dom.window.document.querySelector('#activityEditor'), 'touchend', 60, 650);
assert.deepStrictEqual([...dom.window.draft.pages[0].metrics], [2]);
assert.deepStrictEqual([...dom.window.draft.pages[1].metrics], [3,4,5,6,7,8],
  'dragging across a full page leaves it unchanged');
assert.deepStrictEqual([...dom.window.draft.pages[2].metrics], [1],
  'a metric moves to the exact valid page beyond a full page');

dom.window.draft.pages[0].metrics = [9];
dom.window.draft.pages[2].metrics = [9];
dom.window.drawPages();
setPageGeometry(dom.window);
setMetricGeometry(dom.window.document.querySelectorAll('.page')[0], 20);
const duplicateMetric = dom.window.document.querySelectorAll('.page')[0].querySelector('.metric .handle');
touch(duplicateMetric, 'touchstart', 60, 30);
touch(dom.window.document.querySelector('#activityEditor'), 'touchmove', 60, 650);
touch(dom.window.document.querySelector('#activityEditor'), 'touchend', 60, 650);
assert.deepStrictEqual([...dom.window.draft.pages[0].metrics], [9], 'invalid duplicate drop restores the source');
assert.strictEqual(dom.window.document.querySelector('#dragMessage').textContent,
  'This metric is already on that page.');

dom.window.document.querySelector('#activityCancel').click();
dom.window.document.querySelectorAll('.activity')[0].click();
assert(dom.window.document.querySelector('#dragMessage').classList.contains('hidden'),
  'leaving an activity clears invalid-drop feedback');
assert.strictEqual(dom.window.document.querySelector('#dragMessage').textContent, '');

const pagesBeforeImplicitDrop = dom.window.draft.pages.map(page => page.id);
keyboardHandle = dom.window.document.querySelector('.handle[data-key="page"][data-index="0"]');
key(keyboardHandle, ' ');
key(keyboardHandle, 'ArrowDown');
dom.window.document.querySelector('#activityDone').click();
dom.window.document.querySelectorAll('.activity')[0].click();
assert.strictEqual(dom.window.draft.pages[1].id, pagesBeforeImplicitDrop[0],
  'Activity Done keeps the current keyboard-grab position');
dom.window.document.querySelector('#activityCancel').click();
dom.window.document.querySelectorAll('.activity')[1].click();
assert.strictEqual(dom.window.document.querySelectorAll('.handle[aria-pressed="true"]').length, 0,
  'keyboard grab state does not leak into another activity');
const unrelatedPages = JSON.stringify(dom.window.draft.pages);
key(dom.window.document.querySelector('.handle[data-key="page"][data-index="0"]'), 'Escape');
assert.strictEqual(JSON.stringify(dom.window.draft.pages), unrelatedPages,
  'Escape cannot restore another activity snapshot');
dom.window.document.querySelector('#activityCancel').click();
dom.window.document.querySelectorAll('.activity')[0].click();

dom.window.draft.pages[0].metrics = [1,2];
dom.window.draft.pages[1].metrics = [];
dom.window.draft.pages[2].metrics = [3,4,5,6,7,8];
dom.window.draft.pages[3].metrics = [1];
dom.window.drawPages();
let menuHandle = dom.window.document.querySelector('.handle[data-key="metric-0"][data-index="0"]');
touch(menuHandle, 'touchstart', 60, 30);
touch(dom.window.document.querySelector('#activityEditor'), 'touchend', 60, 30);
assert.strictEqual(dom.window.document.querySelectorAll('.move-menu').length, 1,
  'tapping a handle opens exactly one inline move menu');
assert.strictEqual(dom.window.document.querySelectorAll('.drag-floating').length, 0,
  'a handle tap does not select a floating row');
let menuButtons = [...dom.window.document.querySelectorAll('.move-menu button')];
assert(menuButtons.find(button => button.textContent.startsWith('Move to Page 3')).disabled,
  'the fallback disables a full destination');
assert(menuButtons.find(button => button.textContent.startsWith('Move to Page 4')).disabled,
  'the fallback disables a destination containing the metric');
menuButtons.find(button => button.textContent === 'Move down').click();
assert.deepStrictEqual([...dom.window.draft.pages[0].metrics], [2,1]);
assert.strictEqual(dom.window.document.querySelectorAll('.move-menu').length, 1,
  'the move menu remains open after an action');
menuButtons = [...dom.window.document.querySelectorAll('.move-menu button')];
menuButtons.find(button => button.textContent === 'Move to Page 2').click();
assert.deepStrictEqual([...dom.window.draft.pages[0].metrics], [2]);
assert.deepStrictEqual([...dom.window.draft.pages[1].metrics], [1],
  'the move menu appends a metric to another page');
assert.strictEqual(dom.window.document.querySelectorAll('.move-menu').length, 1);

dom.window.draft.pages[2].metrics = [];
dom.window.drawPages();
const beforeKeyboardCancel = JSON.stringify(dom.window.draft.pages);
keyboardHandle = dom.window.document.querySelector('.handle[data-key="metric-0"][data-index="0"]');
key(keyboardHandle, ' ');
key(keyboardHandle, 'ArrowDown');
key(dom.window.document.activeElement, 'Escape');
assert.strictEqual(JSON.stringify(dom.window.draft.pages), beforeKeyboardCancel,
  'Escape cancels a keyboard move across pages');

const beforeActivityCancel = JSON.stringify(dom.window.editing.pages);
dom.window.draft.pages[0].metrics = [1,2,3,4,5,6];
dom.window.draft.pages[1].metrics = [];
dom.window.drawPages();
dom.window.document.querySelectorAll('.page')[0].querySelector('.metric .remove').click();
assert(!dom.window.document.querySelectorAll('.page')[0].querySelector('.add').disabled,
  'removing a metric re-enables Add Metric');
dom.window.document.querySelectorAll('.page')[0].querySelector('.add').click();
assert(dom.window.document.querySelectorAll('.page')[0].querySelector('.add').disabled,
  'adding the sixth metric disables Add Metric again');
dom.window.document.querySelectorAll('.add')[1].click();
assert.strictEqual(dom.window.document.querySelectorAll('.badge')[1].textContent, '1/6');
dom.window.document.querySelector('#activityCancel').click();
assert.strictEqual(JSON.stringify(dom.window.c.activities[0].pages), beforeActivityCancel,
  'activity Cancel discards its draft');
dom.window.document.querySelector('#generalOpen').click();
dom.window.document.querySelector('#theme').value = 'light';
dom.window.document.querySelector('#generalDone').click();
dom.window.document.querySelector('#save').click();
assert.strictEqual(JSON.parse(closedSettings).theme, 'light', 'final Save returns the combined draft once');

supportedLocales.forEach(language => {
  const localized = config.reconcile(config.defaultsFor(language), catalog, language).config;
  const localizedHtml = decodeURIComponent(config.settingsPage(
    localized, catalog, language, 'fresh', null,
  ).split(',').slice(1).join(','));
  const localizedDom = new JSDOM(localizedHtml, {runScripts: 'dangerously'});
  assert(localizedDom.window.document.querySelector('#save').textContent.trim());
  assert(localizedDom.window.document.querySelector('#generalOpen .label').textContent.trim());
  const firstActivity = localizedDom.window.document.querySelector('.activity');
  assert(firstActivity, `settings page renders an activity for ${language}`);
  firstActivity.click();
  assert.strictEqual(localizedDom.window.document.querySelectorAll('.metric').length > 0, true);
});

assert(config.validHeartRateMessage({0:4,1:8,35:config.RELEASE,7:1,38:2,6:3,37:100}));
assert(!config.validHeartRateMessage({0:4,1:8,35:'0.2.0',7:1,38:2,6:3,37:100}));
assert.deepStrictEqual(config.configResultMessage({0:4,1:9,35:config.RELEASE,33:4,4:0}), {id:4,result:0});
