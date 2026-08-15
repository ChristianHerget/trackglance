'use strict';
const assert = require('assert');
const { JSDOM } = require('jsdom');
const config = require('../src/pkjs/index.js');

assert.strictEqual(config.locale('de_DE'), 'de');
assert.strictEqual(config.locale('fr_FR'), 'en');
assert.strictEqual(config.defaultsFor('de').profiles[0].name, 'Wandern');
const original = JSON.parse(JSON.stringify(config.defaults));
original.profiles.forEach(p => { p.protected = true; });
const migrated = config.parse(config.serialize(config.migrate(original)));
assert(migrated.profiles.every(p => p.protected === false));
assert(config.validate(migrated));

const transfer = new config.Transfer();
const packet = (id,index,count,data) => ({33:id,30:index,31:count,32:data});
assert.strictEqual(transfer.accept(packet(1,1,2,'ing')), null);
assert.strictEqual(transfer.accept(packet(1,0,2,'Hik')), 'Hiking');
assert.strictEqual(transfer.accept(packet(2,0,2,'Old')), null);
assert.strictEqual(transfer.accept(packet(3,0,1,'Neu\nLaufen')), 'Neu\nLaufen');
assert.deepStrictEqual(config.profilePayload('Wandern\nLaufen\nWandern'), ['Wandern','Laufen']);
assert.strictEqual(config.profilePayload('bad\rname'), null);
assert(config.chunks('Ä'.repeat(60), 80).every(x => Buffer.byteLength(x) <= 80));
assert.strictEqual(config.chunks('🥾'.repeat(30), 80).join(''), '🥾'.repeat(30));

const names = ['Hiking','Cycling','Running'];
const pageUrl = config.page(config.defaults, names, 'en', 'fresh');
const html = decodeURIComponent(pageUrl.split(',').slice(1).join(','));
const embeddedScript = html.match(/<script>([\s\S]*)<\/script>/)[1];
assert.doesNotThrow(() => new Function(embeddedScript));
assert(!embeddedScript.includes('Array.from('));
let closed = null;
const dom = new JSDOM(html,{runScripts:'dangerously',beforeParse(w){w.__pebbleConfigClose=x=>{closed=x;};w.confirm=()=>true;w.alert=()=>{};}});
const d = dom.window.document;
const rows = () => d.querySelectorAll('#profiles .row');
assert.strictEqual(rows().length,3);
assert.strictEqual(d.querySelectorAll('#actions .actionrow').length,2,'shared actions use two fixed rows');
assert.strictEqual(d.querySelector('[name=active]'),null,'phone settings has no active-profile control');
assert.strictEqual(rows()[0].classList.contains('selected'),true);
assert.strictEqual(rows()[0].textContent.trim(),'Hiking☰','rows contain only names and handles');
d.getElementById('edit').click();
assert.strictEqual(d.getElementById('name').disabled,false,'defaults are editable');
assert.strictEqual(d.getElementById('save').closest('header').className,'hidden','global Save is hidden in editor');
d.getElementById('editorCancel').click();
d.getElementById('add').click();
assert.strictEqual(d.getElementById('editor').className,'','add opens editor');
d.getElementById('name').value='Walking';
d.getElementById('locus').value='Hiking';
d.getElementById('editorDone').click();
assert.strictEqual(rows().length,4);
assert.notStrictEqual(config.defaults.profiles[0].id,undefined);
d.getElementById('copy').click();
assert.strictEqual(d.getElementById('editor').className,'','copy opens editor');
d.getElementById('editorDone').click();
assert.strictEqual(rows().length,5);
const copiedId = JSON.parse(JSON.stringify(config.defaults)).profiles[0].id;
d.getElementById('delete').click();
assert.strictEqual(rows().length,4);
rows()[0].ondragstart();
rows()[2].ondrop({preventDefault(){}});
assert.strictEqual(rows()[2].querySelector('strong').textContent,'Hiking','drag reorders profiles');
d.getElementById('save').click();
assert(closed);
const saved=JSON.parse(closed);
assert.strictEqual(saved.selected,0);
assert(saved.profiles.every(p=>p.protected===false));
assert.strictEqual(new Set(saved.profiles.map(p=>p.id)).size,saved.profiles.length);
assert(!saved.profiles.some(p=>p.id===copiedId&&p.name.includes('copy')),'copies receive fresh IDs');

const legacy='dark|0\nOnly|Hiking|0|1';
const legacyParsed=config.parse(legacy);
assert(legacyParsed.profiles[0].id,'four-field profiles migrate to stable IDs');
const stable=config.parse(config.serialize(legacyParsed));
assert.strictEqual(stable.profiles[0].id,legacyParsed.profiles[0].id,'IDs survive serialization');
const one={theme:'dark',selected:0,profiles:[{name:'Only',locus:'Hiking',protected:false,metrics:[1],id:'only'}]};
assert(config.validate(one));
assert(!config.remove(one,0));
while(one.profiles.length<8)assert(config.add(one,null,'en'));
assert(!config.add(one));
assert(config.rename(one,0,'Renamed'));
assert(!config.rename(one,1,'renamed'));
assert.strictEqual(new Set(one.profiles.map(p=>p.id)).size,8);

// Exercise the actual Pebble lifecycle, not only the generated page.
const handlers = {}, storage = {};
global.localStorage = {
  getItem: key => Object.prototype.hasOwnProperty.call(storage,key) ? storage[key] : null,
  setItem: (key,value) => { storage[key] = value; },
};
global.Pebble = {
  addEventListener: (name,callback) => { handlers[name] = callback; },
  getActiveWatchInfo: () => ({language:'de_DE'}),
  sendAppMessage: (message,ok) => { if (ok) ok(); },
  openURL: url => { global.openedSettingsURL = url; },
};
delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
assert(handlers.ready && handlers.showConfiguration && handlers.appmessage);
handlers.ready();
handlers.showConfiguration();
assert(global.openedSettingsURL && global.openedSettingsURL.startsWith('data:text/html'),
  'settings opens on the first click without waiting for the profile relay');
global.openedSettingsURL = null;
handlers.appmessage({payload:{1:6,4:3,35:'0.1.4',33:76,30:0,31:1,32:''}});
assert.strictEqual(global.openedSettingsURL,null,'a background response does not reopen settings');
handlers.appmessage({payload:{1:6,4:0,35:'0.1.4',33:77,30:0,31:1,32:'Wandern\nRadfahren\nLaufen'}});
handlers.showConfiguration();
assert(global.openedSettingsURL && global.openedSettingsURL.startsWith('data:text/html'));
const lifecycleHtml = decodeURIComponent(global.openedSettingsURL.split(',').slice(1).join(','));
assert(lifecycleHtml.includes('Wandern'));
global.openedSettingsURL = null;
handlers.appmessage({payload:{MESSAGE_TYPE:6,RESULT:0,APP_VERSION:'0.1.4',TRANSFER_ID:79,
  CHUNK_INDEX:0,CHUNK_COUNT:1,CHUNK_DATA:'Spazieren\nMountainbike'}});
handlers.showConfiguration();
assert(decodeURIComponent(global.openedSettingsURL).includes('Mountainbike'),
  'real PebbleKit named AppMessage keys are accepted');
global.openedSettingsURL = null;
handlers.appmessage({payload:{1:6,35:'0.1.2',33:78,30:0,31:1,32:'Hiking'}});
handlers.showConfiguration();
assert(decodeURIComponent(global.openedSettingsURL).includes('Inkompatibel') || decodeURIComponent(global.openedSettingsURL).includes('inkompatibel'));
delete global.Pebble;
delete global.localStorage;
