'use strict';
const assert = require('assert');
const { JSDOM } = require('jsdom');
const config = require('../src/pkjs/index.js');

assert.strictEqual(config.locale('de_DE'), 'de');
assert.strictEqual(config.locale('fr_FR'), 'en');
assert.deepStrictEqual(config.defaultsFor('de').profiles.map(p => p.name), ['Gehen', 'Radfahren']);
assert.deepStrictEqual(config.defaultsFor('de_DE').profiles.map(p => p.locus), ['Gehen', 'Radfahren']);
assert.deepStrictEqual(config.defaultsFor('en').profiles.map(p => p.name), ['Walking', 'Cycling']);
assert.strictEqual(config.defaults.watchHrToLocus,false);
assert.strictEqual(config.defaults.heartRateIntervalSeconds,5);
assert(config.defaults.profiles.every(p => p.metrics[5]===22),'fresh defaults show current HR in slot 6');
const original = JSON.parse(JSON.stringify(config.defaults));
original.profiles.forEach(p => { p.protected = true; });
const migrated = config.parse(config.serialize(config.migrate(original)));
assert(migrated.profiles.every(p => p.protected === false));
assert(config.validate(migrated));
assert(config.validName('Ä'.repeat(20)),'display names are limited by code points, not UTF-8 bytes');
assert(config.validName('🥾'.repeat(20)),'twenty four-byte code points fit the shared 80-byte field');
assert(!config.validName('🥾'.repeat(21)));
assert(!config.validName('bad\tname'));
assert(!config.validName('bad\u007fname'));
assert(config.validName('trail\u0085name'),'U+0085 is shared whitespace, not a rejected C0/DEL scalar');
assert(!config.validName('\u0085'),'an all-U+0085 field is blank under the shared whitespace set');
assert(!config.validName('\ufeff\ufeff'),'all-Unicode-whitespace names are rejected consistently');
assert(!config.validLocus('L'.repeat(256)));
const namedPair = (first,second) => {
  const candidate=JSON.parse(JSON.stringify(config.defaults));
  candidate.profiles[0].name=first;
  candidate.profiles[1].name=second;
  return candidate;
};
const foldedPairs=[
  ['A','a'],['À','à'],['Ö','ö'],['Ø','ø'],['Þ','þ'],
  ['Α','α'],['Ρ','ρ'],['Σ','σ'],['Ϋ','ϋ'],
  ['А','а'],['Я','я'],['Ѐ','ѐ'],['Џ','џ'],
];
for (const [upper,lower] of foldedPairs) {
  assert.strictEqual(config.profileNameKey(upper),config.profileNameKey(lower));
  assert(!config.validate(namedPair(upper,lower)),`${upper}/${lower} must collide under the watch fold`);
}
assert.notStrictEqual(config.profileNameKey('Ā'),config.profileNameKey('ā'));
assert(config.validate(namedPair('Ā','ā')),'Latin Extended-A remains exact under the bounded watch fold');
assert(config.validate(namedPair('ΟΣ','Ος')),'the watch fold is scalar-wise and does not use contextual Greek lowercasing');
assert(config.validate(namedPair('İ','i\u0307')),'the watch fold never expands one scalar into multiple scalars');
const invalidId = JSON.parse(JSON.stringify(config.defaults));
invalidId.profiles[0].id = 'x'.repeat(40);
config.migrate(invalidId);
assert(config.validId(invalidId.profiles[0].id),'oversized legacy IDs are replaced before serialization');

const transfer = new config.Transfer();
const packet = (id,index,count,data,result=0) => ({33:id,30:index,31:count,32:data,4:result});
assert.strictEqual(transfer.accept(packet(1,1,2,'ing')), null);
assert.strictEqual(transfer.accept(packet(1,0,2,'Hik')), null);
assert.deepStrictEqual(transfer.accept(packet(1,1,2,'ing')), {payload:'Hiking',result:0});
const reorderedTransfer = new config.Transfer();
assert.strictEqual(reorderedTransfer.accept(packet(2,0,3,'A')), null);
assert.strictEqual(reorderedTransfer.accept(packet(2,1,3,'B')), null);
assert.strictEqual(reorderedTransfer.accept(packet(2,0,3,'A')), null,
  'an identical late chunk zero must preserve later chunks');
assert.deepStrictEqual(reorderedTransfer.accept(packet(2,2,3,'C')), {payload:'ABC',result:0});
const conflictingTransfer = new config.Transfer();
assert.strictEqual(conflictingTransfer.accept(packet(3,0,3,'old-')), null);
assert.strictEqual(conflictingTransfer.accept(packet(3,2,3,'tail')), null);
assert.strictEqual(conflictingTransfer.accept(packet(3,0,3,'new-')), null,
  'a conflicting same-ID chunk zero invalidates the partial transfer');
assert.strictEqual(conflictingTransfer.accept(packet(3,1,3,'middle-')), null,
  'later chunks cannot revive an invalidated transfer');
assert.strictEqual(conflictingTransfer.accept(packet(3,0,3,'new-')), null);
assert.strictEqual(conflictingTransfer.accept(packet(3,1,3,'middle-')), null);
assert.deepStrictEqual(conflictingTransfer.accept(packet(3,2,3,'tail')),
  {payload:'new-middle-tail',result:0});
const metadataConflictTransfer = new config.Transfer();
assert.strictEqual(metadataConflictTransfer.accept(packet(6,0,3,'same')), null);
assert.strictEqual(metadataConflictTransfer.accept(packet(6,0,2,'same')), null,
  'a same-ID chunk-zero count conflict invalidates the partial transfer');
assert.strictEqual(metadataConflictTransfer.accept(packet(6,1,3,'middle')), null);
assert.strictEqual(metadataConflictTransfer.accept(packet(6,2,3,'tail')), null,
  'later chunks cannot complete after a chunk-zero count conflict');
assert.strictEqual(metadataConflictTransfer.accept(packet(7,0,3,'same')), null);
assert.strictEqual(metadataConflictTransfer.accept(packet(7,0,3,'same',3)), null,
  'a same-ID chunk-zero result conflict invalidates the partial transfer');
assert.strictEqual(metadataConflictTransfer.accept(packet(7,1,3,'middle')), null);
assert.strictEqual(metadataConflictTransfer.accept(packet(7,2,3,'tail')), null,
  'later chunks cannot complete after a chunk-zero result conflict');
const newIdTransfer = new config.Transfer();
assert.strictEqual(newIdTransfer.accept(packet(4,0,2,'old-')), null);
assert.strictEqual(newIdTransfer.accept(packet(5,0,2,'new-')), null,
  'chunk zero with a new ID restarts the active transfer');
assert.deepStrictEqual(newIdTransfer.accept(packet(5,1,2,'tail')),
  {payload:'new-tail',result:0});
assert.strictEqual(newIdTransfer.accept(packet(4,0,1,'delayed-old')),null,
  'a delayed older chunk zero cannot replace a completed newer profile transfer');
assert.strictEqual(newIdTransfer.accept(packet(4,0,2,'delayed-')),null);
assert.strictEqual(newIdTransfer.accept(packet(4,1,2,'old')),null,
  'a delayed older multipart transfer cannot replace a completed newer profile transfer');
assert.strictEqual(newIdTransfer.accept(packet(5,0,1,'same-id')),null,
  'a completed profile transfer ignores a same-ID replay');
const activeSerialTransfer = new config.Transfer();
assert.strictEqual(activeSerialTransfer.accept(packet(101,0,2,'new-')),null);
assert.strictEqual(activeSerialTransfer.accept(packet(100,0,1,'old')),null,
  'an older chunk zero cannot clobber an active newer transfer');
assert.deepStrictEqual(activeSerialTransfer.accept(packet(101,1,2,'tail')),
  {payload:'new-tail',result:0});
const wrappedTransfer = new config.Transfer();
assert.deepStrictEqual(wrappedTransfer.accept(packet(0x7fffffff,0,1,'edge')),
  {payload:'edge',result:0});
assert.deepStrictEqual(wrappedTransfer.accept(packet(0,0,1,'wrapped')),
  {payload:'wrapped',result:0},'serial zero follows 0x7fffffff at the 31-bit wrap');
assert.strictEqual(wrappedTransfer.accept(packet(0x7fffffff,0,1,'stale')),null);
assert.strictEqual(wrappedTransfer.accept(packet(0x40000000,0,1,'ambiguous')),null,
  'the exact half-range serial distance is rejected as ambiguous');
assert.deepStrictEqual(transfer.accept(packet(3,0,1,'Neu\nLaufen')), {payload:'Neu\nLaufen',result:0});
assert.strictEqual(transfer.accept(packet(4,0,1,'',3)).payload, '');
assert.strictEqual(transfer.accept({33:5,30:'0',31:1,32:'Hiking',4:0}), null,'numeric strings are rejected');
assert.strictEqual(transfer.accept(packet(-1,0,1,'Hiking')), null,'transfer IDs are nonnegative int32 values');
assert.strictEqual(transfer.accept(packet(6,0,1,'Ä'.repeat(41))), null,'chunks use UTF-8 byte limits');
assert.strictEqual(transfer.accept(packet(7,0,2,'first')),null);
assert.strictEqual(transfer.accept(packet(7,1,2,'second',3)),null,'a same-ID result conflict resets the transfer');
assert.strictEqual(transfer.accept(packet(7,1,2,'second')),null,'the invalidated transfer cannot later complete');
assert.strictEqual(config.profilePayload('Wandern\nLaufen\nWandern'), null,'duplicates invalidate a relay');
assert.strictEqual(config.profilePayload('bad\rname'), null);
assert.deepStrictEqual(config.profilePayload('__proto__\nconstructor'),['__proto__','constructor'],
  'valid profile names must not collide with Object prototype properties');
assert(config.chunks('Ä'.repeat(60), 80).every(x => Buffer.byteLength(x) <= 80));
assert.strictEqual(config.chunks('🥾'.repeat(30), 80).join(''), '🥾'.repeat(30));

assert(config.serialNewer(0,0x7fffffff));
assert(!config.serialNewer(0x7fffffff,0));
assert(!config.serialNewer(0x40000000,0));
const serialStorage = Object.create(null);
const serialAdapter = {
  getItem:key => Object.prototype.hasOwnProperty.call(serialStorage,key)?serialStorage[key]:null,
  setItem:(key,value) => { serialStorage[key]=value; },
};
const firstCounter = new config.DurableSerialCounter(serialAdapter,'serial');
assert.strictEqual(firstCounter.reserve(),0);
assert.strictEqual(firstCounter.reserve(),1);
const restartedCounter = new config.DurableSerialCounter(serialAdapter,'serial');
assert.strictEqual(restartedCounter.reserve(),2,
  'a recreated sender advances the durable reservation instead of reseeding');
serialStorage.serial=String(config.LIMIT.transferSerialMask);
assert.strictEqual(restartedCounter.reserve(),0,'the durable serial wraps from the maximum to zero');
const failingCounter = new config.DurableSerialCounter({
  getItem:()=>null,
  setItem:()=>{throw new Error('full');},
},'serial',()=>7);
assert.strictEqual(failingCounter.reserve(),null,'storage failure authorizes no transfer ID');
const ignoredWriteCounter = new config.DurableSerialCounter({
  getItem:()=>null,
  setItem:()=>{},
},'serial',()=>7);
assert.strictEqual(ignoredWriteCounter.reserve(),null,'an unconfirmed serial write fails closed');
const durableFloorStorage = Object.create(null);
const floorAdapter = {
  getItem:key => Object.prototype.hasOwnProperty.call(durableFloorStorage,key)?durableFloorStorage[key]:null,
  setItem:(key,value) => { durableFloorStorage[key]=value; },
};
const firstReceiver = new config.Transfer(floorAdapter,'profile-floor');
assert.deepStrictEqual(firstReceiver.accept(packet(77,0,1,'new')),{payload:'new',result:0});
const restartedReceiver = new config.Transfer(floorAdapter,'profile-floor');
assert.strictEqual(restartedReceiver.accept(packet(76,0,1,'old')),null,
  'a recreated receiver retains its completed serial floor');
assert.strictEqual(restartedReceiver.accept(packet(77,0,1,'same')),null);
assert.deepStrictEqual(restartedReceiver.accept(packet(78,0,1,'newer')),
  {payload:'newer',result:0});
const ambiguousFloorStorage = Object.create(null);
let confirmFloorWrites = false;
const ambiguousFloorAdapter = {
  getItem:key => confirmFloorWrites && Object.prototype.hasOwnProperty.call(ambiguousFloorStorage,key) ?
    ambiguousFloorStorage[key] : null,
  setItem:(key,value) => { ambiguousFloorStorage[key]=value; },
};
const ambiguousReceiver = new config.Transfer(ambiguousFloorAdapter,'profile-floor');
assert.strictEqual(ambiguousReceiver.accept(packet(90,0,1,'unconfirmed')),null);
confirmFloorWrites = true;
assert.strictEqual(ambiguousReceiver.accept(packet(89,0,1,'older')),null,
  'an ambiguous durable floor write blocks the live receiver instead of permitting rollback');
assert.strictEqual(JSON.parse(ambiguousFloorStorage['profile-floor']).id,90,
  'a delayed older transfer cannot overwrite an ambiguously committed receiver floor');
const recoveredReceiver = new config.Transfer(ambiguousFloorAdapter,'profile-floor');
assert.deepStrictEqual(recoveredReceiver.accept(packet(91,0,1,'recovered')),
  {payload:'recovered',result:0},'process recreation reloads and advances the confirmed floor');
const generationStorage = Object.create(null);
const generationAdapter = {
  getItem:key => Object.prototype.hasOwnProperty.call(generationStorage,key) ?
    generationStorage[key] : null,
  setItem:(key,value) => { generationStorage[key]=value; },
};
const generationTransfer = new config.Transfer(generationAdapter,'profile-floor');
assert.deepStrictEqual(generationTransfer.accept(packet(100,0,1,'legacy')),
  {payload:'legacy',result:0});
assert.strictEqual(generationTransfer.accept({...packet(0,1,2,'late'),39:1}),null,
  'a marked nonzero chunk cannot trigger the generation transition');
assert.deepStrictEqual(generationTransfer.accept(packet(101,0,1,'legacy-newer')),
  {payload:'legacy-newer',result:0},'legacy traffic remains usable before marked chunk zero');
assert.deepStrictEqual(generationTransfer.accept({...packet(0,0,1,'durable-zero'),39:1}),
  {payload:'durable-zero',result:0},'the first valid marked chunk zero resets the legacy floor');
assert.strictEqual(generationTransfer.accept(packet(102,0,1,'delayed-legacy')),null,
  'legacy frames are rejected after the durable generation transition');
assert.deepStrictEqual(generationTransfer.accept({...packet(1,0,1,'durable-one'),39:1}),
  {payload:'durable-one',result:0});
assert.strictEqual(JSON.parse(generationStorage['profile-floor']).generation,1,
  'the durable generation marker survives receiver recreation');
const restartedGenerationTransfer = new config.Transfer(generationAdapter,'profile-floor');
assert.strictEqual(restartedGenerationTransfer.accept(packet(2,0,1,'legacy')),null);
assert.deepStrictEqual(restartedGenerationTransfer.accept({...packet(2,0,1,'durable-two'),39:1}),
  {payload:'durable-two',result:0});

const successfulFrames = [], first = {name:'first'}, second = {name:'second'}, request = {name:'request'};
const serializedOutbox = new config.Outbox((frame,ok,fail) => successfulFrames.push({frame,ok,fail}),3,0);
serializedOutbox.enqueue([first,second]);
serializedOutbox.enqueue([request]);
assert.deepStrictEqual(successfulFrames.map(call => call.frame.name),['first']);
successfulFrames[0].ok();
assert.deepStrictEqual(successfulFrames.map(call => call.frame.name),['first','second']);
successfulFrames[1].ok();
assert.deepStrictEqual(successfulFrames.map(call => call.frame.name),['first','second','request']);

const failedFrames = [], completion = [];
const retryingOutbox = new config.Outbox((frame,ok,fail) => failedFrames.push({frame,ok,fail}),3,0);
retryingOutbox.enqueue([first,second],ok => completion.push(ok));
retryingOutbox.enqueue([request]);
failedFrames[0].fail();failedFrames[1].fail();
assert(failedFrames.slice(0,3).every(call => call.frame===first),'a NACK retries the identical frame');
failedFrames[2].fail();
assert.deepStrictEqual(completion,[false]);
assert.deepStrictEqual(failedFrames.map(call => call.frame.name),['first','first','first','request'],
  'exhaustion aborts the remaining transaction before the next queued message');

const ackTimers = new Map(), ackOutcomes = [];
let ackTimerId = 0;
const acknowledgements = new config.AckTracker(
  50,
  callback => { const id=++ackTimerId;ackTimers.set(id,callback);return id; },
  id => ackTimers.delete(id),
);
acknowledgements.register(41,outcome => ackOutcomes.push(outcome));
assert.strictEqual(acknowledgements.accept(99,config.RESULTS.applied),false,'a wrong transfer ID is ignored');
assert(acknowledgements.transport(41,true));
assert(acknowledgements.accept(41,config.RESULTS.queued));
assert.deepStrictEqual(ackOutcomes,[{kind:'result',id:41,result:config.RESULTS.queued}]);
assert.strictEqual(acknowledgements.accept(41,config.RESULTS.applied),false,'a late duplicate ACK is ignored');
acknowledgements.register(42,outcome => ackOutcomes.push(outcome));
acknowledgements.transport(42,true);
[...ackTimers.values()][0]();
assert.deepStrictEqual(ackOutcomes[1],{kind:'timeout',id:42});
assert.deepStrictEqual(config.configResultMessage({0:3,1:9,35:'0.1.7',33:5,4:7}),{id:5,result:7});
assert.strictEqual(config.configResultMessage({0:3,1:9,35:'0.1.6',33:5,4:7}),null);
assert.strictEqual(config.configResultMessage({0:3,1:9,35:'0.1.7',33:5,4:'7'}),null);

const names = ['Walking','Cycling'];
const pageUrl = config.page(config.defaults, names, 'en', 'fresh', true);
const html = decodeURIComponent(pageUrl.split(',').slice(1).join(','));
const embeddedScript = html.match(/<script>([\s\S]*)<\/script>/)[1];
assert.doesNotThrow(() => new Function(embeddedScript));
assert(!embeddedScript.includes('Array.from('));
let closed = null;
const dom = new JSDOM(html,{runScripts:'dangerously',beforeParse(w){w.__pebbleConfigClose=x=>{closed=x;};w.confirm=()=>true;w.alert=()=>{};}});
const d = dom.window.document;
for (const [upper,lower] of foldedPairs) {
  assert.strictEqual(dom.window.fold(upper),dom.window.fold(lower),'embedded settings fold matches the module');
}
for (const [left,right] of [['Ā','ā'],['ΟΣ','Ος'],['İ','i\u0307']]) {
  assert.notStrictEqual(dom.window.fold(left),dom.window.fold(right),'embedded settings preserves exact scalars');
}
assert.strictEqual(dom.window.scan('trail\u0085name',20),10,
  'embedded settings permits U+0085 inside a nonblank field');
assert.strictEqual(dom.window.scan('\u0085',20),-1,
  'embedded settings treats an all-U+0085 field as blank');
assert.strictEqual(dom.window.scan('bad\u007fname',20),-1,
  'embedded settings rejects DEL explicitly');
assert(d.getElementById('heartRate'));
assert.strictEqual(d.getElementById('watchHr').checked,false);
assert.strictEqual(d.getElementById('hrIntervalRow').className,'hidden');
d.getElementById('watchHr').click();
assert(d.getElementById('hrIntervalRow').className.includes('row'));
d.getElementById('hrInterval').value='60';d.getElementById('hrInterval').oninput();
const rows = () => d.querySelectorAll('#profiles .row');
assert.strictEqual(rows().length,2);
assert.strictEqual(d.querySelectorAll('#actions .actionrow').length,2,'shared actions use two fixed rows');
assert.strictEqual(d.querySelector('[name=active]'),null,'phone settings has no active-profile control');
assert.strictEqual(rows()[0].classList.contains('selected'),true);
assert.strictEqual(rows()[0].textContent.trim(),'Walking☰','rows contain only names and handles');
d.getElementById('edit').click();
assert.strictEqual(d.getElementById('name').disabled,false,'defaults are editable');
assert.strictEqual(d.getElementById('save').closest('header').className,'hidden','global Save is hidden in editor');
d.getElementById('editorCancel').click();
d.getElementById('add').click();
assert.strictEqual(d.getElementById('editor').className,'','add opens editor');
d.getElementById('name').value='Strolling';
d.getElementById('locus').value='Walking';
d.getElementById('editorDone').click();
assert.strictEqual(rows().length,3);
assert.notStrictEqual(config.defaults.profiles[0].id,undefined);
d.getElementById('copy').click();
assert.strictEqual(d.getElementById('editor').className,'','copy opens editor');
d.getElementById('editorDone').click();
assert.strictEqual(rows().length,4);
const copiedId = JSON.parse(JSON.stringify(config.defaults)).profiles[0].id;
d.getElementById('delete').click();
assert.strictEqual(rows().length,3);
rows()[0].ondragstart();
rows()[2].ondrop({preventDefault(){}});
assert.strictEqual(rows()[2].querySelector('strong').textContent,'Walking','drag reorders profiles');
d.getElementById('save').click();
assert(closed);
const saved=JSON.parse(closed);
assert.strictEqual(saved.selected,0);
assert.strictEqual(saved.watchHrToLocus,true);
assert.strictEqual(saved.heartRateIntervalSeconds,60);
assert(saved.profiles.every(p=>p.protected===false));
assert.strictEqual(new Set(saved.profiles.map(p=>p.id)).size,saved.profiles.length);
assert(!saved.profiles.some(p=>p.id===copiedId&&p.name.includes('copy')),'copies receive fresh IDs');

function saveFoldPair(first,second){
  const candidate=namedPair(first,second);
  const candidateHtml=decodeURIComponent(config.page(candidate,['Walking','Cycling'],'en','fresh',false).split(',').slice(1).join(','));
  let response=null,alerts=0;
  const candidateDom=new JSDOM(candidateHtml,{runScripts:'dangerously',beforeParse(w){w.__pebbleConfigClose=x=>{response=x;};w.alert=()=>{alerts++;};}});
  candidateDom.window.document.getElementById('save').click();
  candidateDom.window.close();
  return {response,alerts};
}
assert(saveFoldPair('Ā','ā').response,'embedded settings accepts names that the watch treats as distinct');
const foldedUiDuplicate=saveFoldPair('Σ','σ');
assert.strictEqual(foldedUiDuplicate.response,null,'embedded settings rejects a watch-fold duplicate');
assert.strictEqual(foldedUiDuplicate.alerts,1);

const legacy='dark|0\nOnly|Hiking|0|1';
const legacyParsed=config.parse(legacy);
assert.strictEqual(legacyParsed.watchHrToLocus,false);
assert.strictEqual(legacyParsed.heartRateIntervalSeconds,5);
assert(legacyParsed.profiles[0].id,'four-field profiles migrate to stable IDs');
const stable=config.parse(config.serialize(legacyParsed));
assert.strictEqual(stable.profiles[0].id,legacyParsed.profiles[0].id,'IDs survive serialization');
const one={theme:'dark',selected:0,profiles:[{name:'Only',locus:'Hiking',protected:false,metrics:[1],id:'only'}]};
assert(config.validate(one));
const prototypeNames=JSON.parse(JSON.stringify(config.defaults));
prototypeNames.profiles[0].name='__proto__';
prototypeNames.profiles[0].locus='constructor';
prototypeNames.profiles[0].id='__proto__';
assert(config.validate(prototypeNames), 'valid names and IDs may match Object prototype properties');
assert(!config.remove(one,0));
while(one.profiles.length<8)assert(config.add(one,null,'en'));
assert(!config.add(one));
assert(config.rename(one,0,'Renamed'));
assert(!config.rename(one,1,'renamed'));
const foldedRename=namedPair('Σ','Other');
assert(!config.rename(foldedRename,1,'σ'),'rename uses the bounded watch fold');
const exactRename=namedPair('Ā','Other');
assert(config.rename(exactRename,1,'ā'),'rename preserves exact scalars outside the bounded fold');
assert(config.add(foldedRename,foldedRename.profiles[0]));
assert.strictEqual(foldedRename.profiles[2].name,'Σ 2','copy naming detects shared-fold duplicates');
assert.strictEqual(new Set(one.profiles.map(p=>p.id)).size,8);

assert(config.validHeartRateMessage({0:3,1:8,35:'0.1.7',7:1,38:0,6:1000,37:123}));
assert(!config.validHeartRateMessage({0:2,1:8,35:'0.1.7',7:1,38:0,6:1000,37:123}));
assert(!config.validHeartRateMessage({0:3,1:8,35:'0.1.7',7:1,38:0,6:1000,37:251}));

// Exercise the actual Pebble lifecycle, including asynchronous AppMessage callbacks.
const handlers = {}, storage = {}, sent = {}, fakeTimers = new Map();
const originalSetTimeout = global.setTimeout, originalClearTimeout = global.clearTimeout;
let sentCount = 0, timerId = 0;
const noticeText = url => decodeURIComponent(url).match(/<div id="notice" class="notice">([^<]*)<\/div>/)[1];
global.setTimeout = (callback,delay) => { const id=++timerId;fakeTimers.set(id,{callback,delay});return id; };
global.clearTimeout = id => { fakeTimers.delete(id); };
global.localStorage = {
  getItem: key => Object.prototype.hasOwnProperty.call(storage,key) ? storage[key] : null,
  setItem: (key,value) => { storage[key] = value; },
  removeItem: key => { delete storage[key]; },
};
global.Pebble = {
  addEventListener: (name,callback) => { handlers[name] = callback; },
  getActiveWatchInfo: () => ({language:'de_DE',platform:'gabbro',isEmulator:false}),
  sendAppMessage: (message,ok,fail) => { sent[sentCount++]={message,ok,fail}; },
  openURL: url => { global.openedSettingsURL = url; },
};
delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
assert(handlers.ready && handlers.showConfiguration && handlers.appmessage && handlers.webviewclosed);
handlers.ready();
assert.strictEqual(sentCount,1,'startup starts only the first configuration frame');
const startupChunkCount=sent[0].message[31];
const startupTransferId=sent[0].message[33];
for(let i=0;i<startupChunkCount;i++){
  assert.strictEqual(sent[i].message[1],5);
  assert.strictEqual(sent[i].message[30],i);
  assert.strictEqual(sent[i].message[39],1,
    'every durable configuration frame carries the generation marker');
  sent[i].ok();
}
assert.strictEqual(sent[startupChunkCount].message[1],7,
  'the startup profile request waits for the complete configuration transfer');
handlers.appmessage({payload:{0:3,1:9,35:'0.1.7',33:startupTransferId,4:0}});
sent[startupChunkCount].ok();

global.openedSettingsURL = null;
storage['locusProfiles.v3']=JSON.stringify({names:['Legacy cache'],updated:Date.now()});
handlers.showConfiguration();
assert.strictEqual(global.openedSettingsURL,null,
  'a cache without exact protocol and release metadata is ignored while awaiting a fresh response');
sent[sentCount-1].ok();
handlers.appmessage({payload:{0:3,1:6,4:0,35:'0.1.7',33:77,30:0,31:1,32:'Wandern\nRadfahren\nLaufen'}});
assert(global.openedSettingsURL && global.openedSettingsURL.startsWith('data:text/html'),
  'the first settings click opens as soon as a complete fresh response arrives');
let lifecycleHtml = decodeURIComponent(global.openedSettingsURL.split(',').slice(1).join(','));
assert(lifecycleHtml.includes('Wandern'));
assert(noticeText(global.openedSettingsURL).includes('Locus-Profile aktualisiert'));

global.openedSettingsURL = null;
handlers.appmessage({payload:{PROTOCOL_VERSION:3,MESSAGE_TYPE:6,RESULT:0,APP_VERSION:'0.1.7',TRANSFER_ID:79,
  CHUNK_INDEX:0,CHUNK_COUNT:1,CHUNK_DATA:'Spazieren\nMountainbike'}});
handlers.showConfiguration();
assert(decodeURIComponent(global.openedSettingsURL).includes('Mountainbike'),
  'real PebbleKit named AppMessage keys are accepted');
assert(noticeText(global.openedSettingsURL).includes('Zuletzt gespeicherte Locus-Profile'),
  'an in-memory response is demoted to stale until the current settings refresh completes');
sent[sentCount-1].ok();

handlers.appmessage({payload:{0:3,1:6,4:3,35:'0.1.7',33:80,30:0,31:1,32:''}});
assert.deepStrictEqual(JSON.parse(storage['locusProfiles.v3']).names,[],
  'a complete empty result atomically replaces stale cached names');
global.openedSettingsURL = null;
handlers.showConfiguration();
lifecycleHtml=decodeURIComponent(global.openedSettingsURL);
assert(noticeText(global.openedSettingsURL).includes('Zuletzt gespeicherte Locus-Profile'),
  'a previously received empty list is also stale on a later settings opening');
sent[sentCount-1].ok();

handlers.appmessage({payload:{0:3,1:6,4:0,35:'0.1.2',33:81,30:0,31:1,32:'Hiking'}});
global.openedSettingsURL = null;
handlers.showConfiguration();
lifecycleHtml=decodeURIComponent(global.openedSettingsURL);
assert(noticeText(global.openedSettingsURL).toLowerCase().includes('inkompatibel'));
sent[sentCount-1].ok();

handlers.appmessage({payload:{0:3,1:6,4:0,35:'0.1.7',33:82,30:0,31:1,32:'Wandern'}});
global.openedSettingsURL = null;
handlers.showConfiguration();
lifecycleHtml=decodeURIComponent(global.openedSettingsURL);
assert(lifecycleHtml.includes('Wandern') && !noticeText(global.openedSettingsURL).toLowerCase().includes('inkompatibel'),
  'a later complete compatible transfer clears a stale incompatibility state');
sent[sentCount-1].ok();

function candidateNamed(name){const value=config.parse(storage.config);value.profiles[0].name=name;return value;}
function closeWith(value){handlers.webviewclosed({response:encodeURIComponent(JSON.stringify(value))});}
function finishConfigAt(start){const count=sent[start].message[31],id=sent[start].message[33],parts=[];for(let i=0;i<count;i++){assert.strictEqual(sent[start+i].message[33],id);parts.push(sent[start+i].message[32]);sent[start+i].ok();}return {id,wire:parts.join(''),next:start+count};}
function exhaustFinalFrameAt(start){const count=sent[start].message[31],id=sent[start].message[33],parts=[];for(let i=0;i<count-1;i++){assert.strictEqual(sent[start+i].message[33],id);parts.push(sent[start+i].message[32]);sent[start+i].ok();}const final=start+count-1;parts.push(sent[final].message[32]);sent[final].fail();sent[final+1].fail();sent[final+2].fail();return {id,wire:parts.join(''),next:final+3};}
function configAck(id,result){handlers.appmessage({payload:{0:3,1:9,35:'0.1.7',33:id,4:result}});}
function fireAckTimeout(){const entry=[...fakeTimers.entries()].find(([,timer])=>timer.delay===10000);assert(entry,'an ACK deadline must be armed only after transport completion');fakeTimers.delete(entry[0]);entry[1].callback();}

const committedBeforeSave=storage.config;
let configStart=sentCount;
closeWith(candidateNamed('Alpha'));
let durableBeforeTransport=JSON.parse(storage['configPending.v3']).items[0];
assert.strictEqual(durableBeforeTransport.selfUncertain,true,
  'a candidate is durably marked possibly applied before its first frame can reach the watch');
let alpha=finishConfigAt(configStart);
assert.strictEqual(storage.config,committedBeforeSave,'transport success alone must not commit settings');
const sentBeforeConcurrentSave=sentCount;
closeWith(candidateNamed('Beta'));
assert.strictEqual(sentCount,sentBeforeConcurrentSave,'a concurrent save waits for the prior correlated result');
configAck(alpha.id+1,0);
assert.strictEqual(storage.config,committedBeforeSave,'a wrong-ID result cannot commit a candidate');
configAck(alpha.id,0);
assert(config.parse(storage.config).profiles[0].name==='Alpha','APPLIED promotes the first durable candidate');
configStart=sentBeforeConcurrentSave;
const beta=finishConfigAt(configStart);
configAck(beta.id,7);
assert(config.parse(storage.config).profiles[0].name==='Beta','QUEUED promotes the next serialized candidate');
configAck(alpha.id,0);
assert(config.parse(storage.config).profiles[0].name==='Beta','a stale ACK cannot overwrite a newer commit');
assert.strictEqual(storage['configPending.v3'],undefined,'all acknowledged candidates leave the durable queue');

configStart=sentCount;
closeWith(candidateNamed('Rejected'));
const rejected=finishConfigAt(configStart);
configAck(rejected.id,8);
assert(config.parse(storage.config).profiles[0].name==='Beta','an explicit rejection keeps the prior commit');
global.openedSettingsURL=null;
handlers.showConfiguration();
assert(noticeText(global.openedSettingsURL).includes('abgelehnt'),'the next settings opening reports rejection');
sent[sentCount-1].ok();

configStart=sentCount;
closeWith(candidateNamed('Older ACK candidate'));
const olderAckOne=finishConfigAt(configStart);
const beforeNewerAckSave=sentCount;
closeWith(candidateNamed('Newer ACK candidate'));
assert.strictEqual(sentCount,beforeNewerAckSave,'a newer save waits while the older transfer is in flight');
fireAckTimeout();
const olderAckTwo=finishConfigAt(olderAckOne.next);
fireAckTimeout();
const olderAckThree=finishConfigAt(olderAckTwo.next);
fireAckTimeout();
const newerAfterAckTimeout=finishConfigAt(olderAckThree.next);
assert(newerAfterAckTimeout.wire.includes('Newer ACK candidate'),
  'exhausting an ambiguous older candidate immediately advances to the newer save');
assert(config.parse(storage.config).profiles[0].name==='Beta','the failed older candidate is never committed');
configAck(olderAckOne.id,0);
assert(config.parse(storage.config).profiles[0].name==='Beta','a late result for the retired candidate is ignored');
configAck(newerAfterAckTimeout.id,0);
assert(config.parse(storage.config).profiles[0].name==='Newer ACK candidate');

configStart=sentCount;
closeWith(candidateNamed('Old transport'));
const olderTransportId=sent[configStart].message[33];
closeWith(candidateNamed('New transport'));
const lostFinalAck=exhaustFinalFrameAt(configStart);
assert(lostFinalAck.wire.includes('Old transport'));
const newerAfterTransportFailure=finishConfigAt(lostFinalAck.next);
assert(newerAfterTransportFailure.wire.includes('New transport'),
  'final-frame ACK loss advances to a durable newer save');
assert(JSON.parse(storage['configPending.v3']).items[0].fallbackWires[0].includes('Old transport'),
  'final-frame ACK loss hands the possibly applied wire to the newer candidate as a fallback');
configAck(olderTransportId,0);
assert(config.parse(storage.config).profiles[0].name==='Newer ACK candidate');
configAck(newerAfterTransportFailure.id,7);
assert(config.parse(storage.config).profiles[0].name==='New transport');

configStart=sentCount;
closeWith(candidateNamed('Retry me'));
const retryOne=finishConfigAt(configStart);
fireAckTimeout();
const retryTwo=finishConfigAt(retryOne.next);
fireAckTimeout();
const retryThree=finishConfigAt(retryTwo.next);
assert.strictEqual(retryOne.id,retryTwo.id,'an ACK timeout retries the complete same-ID transfer');
assert.strictEqual(retryTwo.id,retryThree.id);
fireAckTimeout();
assert(config.parse(storage.config).profiles[0].name==='New transport','exhausted ACK retries do not commit ambiguous settings');
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Retry me'),
  'an ambiguous candidate remains durable for restart reconciliation');
configAck(retryOne.id,0);
assert(config.parse(storage.config).profiles[0].name==='New transport','a result after the bounded deadline is stale');

configStart=sentCount;
closeWith(candidateNamed('Superseding save'));
const superseding=finishConfigAt(configStart);
assert(superseding.wire.includes('Superseding save'),'a new save supersedes and unblocks a timed-out candidate');
assert.strictEqual(JSON.parse(storage['configPending.v3']).items.length,1,'failed pending state is last-write-wins');
configAck(superseding.id,0);
assert(config.parse(storage.config).profiles[0].name==='Superseding save');

configStart=sentCount;
closeWith(candidateNamed('Restart recovery'));
const restartTryOne=finishConfigAt(configStart);
fireAckTimeout();
const restartTryTwo=finishConfigAt(restartTryOne.next);
fireAckTimeout();
const restartTryThree=finishConfigAt(restartTryTwo.next);
fireAckTimeout();
const reconnectStart=sentCount;
handlers.ready();
const reconnectOne=finishConfigAt(reconnectStart);
assert(reconnectOne.wire.includes('Restart recovery'),'a repeated ready event retries pending state, never the old commit');
assert.strictEqual(sent[reconnectOne.next].message[1],7);
sent[reconnectOne.next].ok();
fireAckTimeout();
const reconnectTwo=finishConfigAt(reconnectOne.next+1);
fireAckTimeout();
const reconnectThree=finishConfigAt(reconnectTwo.next);
fireAckTimeout();
const restartStart=sentCount;
delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
handlers.ready();
const restarted=finishConfigAt(restartStart);
assert(restarted.wire.includes('Restart recovery'),'startup retries the durable candidate instead of reverting the watch');
configAck(restarted.id,0);
assert(config.parse(storage.config).profiles[0].name==='Restart recovery');
assert.strictEqual(storage['configPending.v3'],undefined);
assert.strictEqual(sent[restarted.next].message[1],7,'startup profile discovery still follows transport, not the ACK wait');
sent[restarted.next].ok();

configStart=sentCount;
closeWith(candidateNamed('Direct storage fail'));
const immediateStorageFailure=finishConfigAt(configStart);
configAck(immediateStorageFailure.id,9);
assert(config.parse(storage.config).profiles[0].name==='Restart recovery',
  'a first-attempt storage failure is definitive and keeps the prior commit');
assert.strictEqual(storage['configPending.v3'],undefined,
  'a first-attempt storage failure retires the rejected candidate');

configStart=sentCount;
closeWith(candidateNamed('Ambiguous retry'));
const ambiguousOne=finishConfigAt(configStart);
fireAckTimeout();
const ambiguousTwo=finishConfigAt(ambiguousOne.next);
configAck(ambiguousTwo.id,9);
let ambiguousPending=JSON.parse(storage['configPending.v3']).items[0];
assert(ambiguousPending.wire.includes('Ambiguous retry'));
assert.strictEqual(ambiguousPending.uncertain,true,
  'an application-result timeout is recorded durably before a whole-transfer retry');
assert(config.parse(storage.config).profiles[0].name==='Restart recovery',
  'a storage result after an earlier timeout cannot promote or discard ambiguous settings');
configAck(ambiguousOne.id,0);
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Ambiguous retry'),
  'a late success cannot reorder the already settled retry result');
global.openedSettingsURL=null;
handlers.showConfiguration();
assert(noticeText(global.openedSettingsURL).includes('Bestätigung steht noch aus'),
  'the next settings opening describes the ambiguous state without claiming the old value remained');
sent[sentCount-1].ok();
global.openedSettingsURL=null;
handlers.showConfiguration();
assert(noticeText(global.openedSettingsURL).includes('Bestätigung steht noch aus'),
  'durable unresolved state remains visible on repeated settings openings');
sent[sentCount-1].ok();

const ambiguousRestartStart=sentCount;
delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
handlers.ready();
const ambiguousRecovery=finishConfigAt(ambiguousRestartStart);
assert(ambiguousRecovery.wire.includes('Ambiguous retry'),
  'restart retries the durable ambiguous candidate instead of the older committed settings');
configAck(ambiguousRecovery.id,0);
assert(config.parse(storage.config).profiles[0].name==='Ambiguous retry');
assert.strictEqual(storage['configPending.v3'],undefined);
assert.strictEqual(sent[ambiguousRecovery.next].message[1],7);
sent[ambiguousRecovery.next].ok();

configStart=sentCount;
closeWith(candidateNamed('Possible A'));
const possibleAOne=finishConfigAt(configStart);
fireAckTimeout();
closeWith(candidateNamed('Newer B'));
const possibleATwo=finishConfigAt(possibleAOne.next);
fireAckTimeout();
const possibleAThree=finishConfigAt(possibleATwo.next);
fireAckTimeout();
const inheritedStorage=finishConfigAt(possibleAThree.next);
assert(inheritedStorage.wire.includes('Newer B'));
configAck(inheritedStorage.id,9);
const inheritedRecovery=finishConfigAt(inheritedStorage.next);
assert(inheritedRecovery.wire.includes('Possible A'),
  'a definitive first-attempt failure of B restores its possibly applied A fallback');
let inheritedPending=JSON.parse(storage['configPending.v3']).items[0];
assert(inheritedPending.wire.includes('Possible A'));
assert.strictEqual(inheritedPending.selfUncertain,true);
assert(config.parse(storage.config).profiles[0].name==='Ambiguous retry',
  'restoring an ambiguous fallback cannot expose the older committed configuration');
configAck(inheritedRecovery.id,0);
assert(config.parse(storage.config).profiles[0].name==='Possible A');
assert.strictEqual(storage['configPending.v3'],undefined);

configStart=sentCount;
closeWith(candidateNamed('Fallback A'));
const fallbackAOne=finishConfigAt(configStart);
fireAckTimeout();
closeWith(candidateNamed('Invalid B'));
const fallbackATwo=finishConfigAt(fallbackAOne.next);
fireAckTimeout();
const fallbackAThree=finishConfigAt(fallbackATwo.next);
fireAckTimeout();
const invalidSuperseding=finishConfigAt(fallbackAThree.next);
assert(invalidSuperseding.wire.includes('Invalid B'));
configAck(invalidSuperseding.id,8);
const restoredFallback=finishConfigAt(invalidSuperseding.next);
assert(restoredFallback.wire.includes('Fallback A'),
  'an invalid superseding save restores the only possibly applied safe baseline');
assert(!restoredFallback.wire.includes('Invalid B'));
configAck(restoredFallback.id,0);
assert(config.parse(storage.config).profiles[0].name==='Fallback A');
assert.strictEqual(storage['configPending.v3'],undefined);

configStart=sentCount;
closeWith(candidateNamed('Deterministic A'));
const deterministicA=finishConfigAt(configStart);
closeWith(candidateNamed('Downstream B'));
let downstreamPending=JSON.parse(storage['configPending.v3']).items[1];
assert(downstreamPending.fallbackWires[0].includes('Deterministic A'),
  'a save staged during transport initially preserves the in-flight fallback');
configAck(deterministicA.id,9);
const downstreamB=finishConfigAt(deterministicA.next);
downstreamPending=JSON.parse(storage['configPending.v3']).items[0];
assert(downstreamPending.wire.includes('Downstream B'));
assert.deepStrictEqual(downstreamPending.fallbackWires,[],
  'a first-attempt storage failure proves A was not applied and clears B\'s inherited marker');
configAck(downstreamB.id,0);
assert(config.parse(storage.config).profiles[0].name==='Downstream B');
assert.strictEqual(storage['configPending.v3'],undefined);

// Simulate process death after enqueue but before even the first transport callback. The durable
// pre-send ambiguity must make a later storage failure non-terminal because the abandoned process
// may have completed the transfer after dying from PKJS's point of view.
configStart=sentCount;
closeWith(candidateNamed('Crash window'));
const abandonedId=sent[configStart].message[33];
const crashPending=JSON.parse(storage['configPending.v3']).items[0];
assert.strictEqual(crashPending.selfUncertain,true);
assert(crashPending.wire.includes('Crash window'));
delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
const recreatedStart=sentCount;
handlers.ready();
const recreated=finishConfigAt(recreatedStart);
assert(recreated.wire.includes('Crash window'));
configAck(recreated.id,9);
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Crash window'),
  'storage failure after process recreation preserves the possibly applied candidate');
assert(config.parse(storage.config).profiles[0].name==='Downstream B');
configAck(abandonedId,0);
assert(config.parse(storage.config).profiles[0].name==='Downstream B',
  'a late result from the abandoned process cannot bypass the live retry correlation');
sent[recreated.next].ok();

delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
const reconciliationStart=sentCount;
handlers.ready();
const reconciled=finishConfigAt(reconciliationStart);
assert(reconciled.wire.includes('Crash window'));
configAck(reconciled.id,0);
assert(config.parse(storage.config).profiles[0].name==='Crash window');
assert.strictEqual(storage['configPending.v3'],undefined);
sent[reconciled.next].ok();

// The same durable rule applies after every chunk has transported: process death before the
// correlated result loses the live proof that a later failure belongs to the first attempt.
configStart=sentCount;
closeWith(candidateNamed('Post-transport crash'));
const transportedBeforeCrash=finishConfigAt(configStart);
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Post-transport crash'));
delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
const postTransportRestart=sentCount;
handlers.ready();
const postTransportRetry=finishConfigAt(postTransportRestart);
configAck(postTransportRetry.id,9);
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Post-transport crash'),
  'a storage failure after post-transport recreation cannot discard a possibly applied wire');
configAck(transportedBeforeCrash.id,0);
assert(config.parse(storage.config).profiles[0].name==='Crash window',
  'the abandoned attempt result is stale relative to the recreated operation');
sent[postTransportRetry.next].ok();

delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
const postTransportReconcileStart=sentCount;
handlers.ready();
const postTransportReconciled=finishConfigAt(postTransportReconcileStart);
configAck(postTransportReconciled.id,0);
assert(config.parse(storage.config).profiles[0].name==='Post-transport crash');
assert.strictEqual(storage['configPending.v3'],undefined);
sent[postTransportReconciled.next].ok();

// A failed transport callback for the final frame does not prove the watch missed that frame. The
// retry after process recreation must retain the candidate if its correlated storage attempt fails.
configStart=sentCount;
closeWith(candidateNamed('Lost final ACK'));
const finalAckLoss=exhaustFinalFrameAt(configStart);
assert(finalAckLoss.wire.includes('Lost final ACK'));
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Lost final ACK'));
delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
const finalAckRestartStart=sentCount;
handlers.ready();
const finalAckRetry=finishConfigAt(finalAckRestartStart);
configAck(finalAckRetry.id,9);
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Lost final ACK'),
  'restart followed by storage failure preserves a final-frame ACK-loss candidate');
assert(config.parse(storage.config).profiles[0].name==='Post-transport crash');
sent[finalAckRetry.next].ok();

delete require.cache[require.resolve('../src/pkjs/index.js')];
require('../src/pkjs/index.js');
const finalAckReconcileStart=sentCount;
handlers.ready();
const finalAckReconciled=finishConfigAt(finalAckReconcileStart);
configAck(finalAckReconciled.id,0);
assert(config.parse(storage.config).profiles[0].name==='Lost final ACK');
assert.strictEqual(storage['configPending.v3'],undefined);
sent[finalAckReconciled.next].ok();

function wireNamed(name){return config.serialize(candidateNamed(name));}
function reloadLifecycle(){delete require.cache[require.resolve('../src/pkjs/index.js')];require('../src/pkjs/index.js');}

// Pending records written by the original 0.1.7 queue had no uncertainty fields even after an
// ambiguous transport or application timeout. They must be migrated conservatively.
const legacyAmbiguousWire=wireNamed('Legacy ambiguous');
storage['configPending.v3']=JSON.stringify({protocol:3,release:'0.1.7',items:[{
  token:'legacy-ambiguous',wire:legacyAmbiguousWire,
}]});
reloadLifecycle();
let recoveryStart=sentCount;
handlers.ready();
const legacyRetry=finishConfigAt(recoveryStart);
let migratedPending=JSON.parse(storage['configPending.v3']);
assert.strictEqual(migratedPending.format,2);
assert.strictEqual(migratedPending.items[0].selfUncertain,true,
  'legacy pending state is ambiguous because the old process may already have sent it');
configAck(legacyRetry.id,9);
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Legacy ambiguous'),
  'a storage failure cannot discard migrated legacy ambiguity');
assert(config.parse(storage.config).profiles[0].name==='Lost final ACK');
sent[legacyRetry.next].ok();

// A parseable same-protocol queue survives a release update and is rewritten to the current
// release before it is retried. A later storage failure still cannot erase it.
const releaseRecoveryWire=wireNamed('Release recovery');
storage['configPending.v3']=JSON.stringify({protocol:3,release:'0.1.6',format:2,items:[{
  token:'release-recovery',wire:releaseRecoveryWire,uncertain:true,selfUncertain:true,fallbackWires:[],
}]});
reloadLifecycle();
recoveryStart=sentCount;
handlers.ready();
const releaseRetry=finishConfigAt(recoveryStart);
assert(releaseRetry.wire.includes('Release recovery'),
  'startup retries a parseable prior-release pending wire before committed settings');
migratedPending=JSON.parse(storage['configPending.v3']);
assert.strictEqual(migratedPending.release,'0.1.7');
assert.strictEqual(migratedPending.format,2);
configAck(releaseRetry.id,9);
assert(JSON.parse(storage['configPending.v3']).items[0].wire.includes('Release recovery'));
sent[releaseRetry.next].ok();

// Existing but unmigratable state is not equivalent to absence: startup must fail closed instead
// of sending the older committed configuration over a watch that may contain a newer value.
const malformedPriorRelease=JSON.stringify({protocol:3,release:'0.1.6',items:[{
  token:'broken-recovery',wire:'not a canonical configuration',
}]});
storage['configPending.v3']=malformedPriorRelease;
reloadLifecycle();
recoveryStart=sentCount;
handlers.ready();
assert.strictEqual(sent[recoveryStart].message[1],7,
  'blocked pending storage permits profile discovery but sends no committed config');
assert.strictEqual(storage['configPending.v3'],malformedPriorRelease,
  'unmigratable recovery evidence remains intact for a future compatible release');
assert.strictEqual(storage['configNotice.v3'],'uncertain');
sent[recoveryStart].ok();

const tamperedWire=wireNamed('Tampered lineage');
const tamperedLineage=JSON.stringify({protocol:3,release:'0.1.7',format:2,items:[{
  token:'tampered-lineage',wire:tamperedWire,uncertain:true,selfUncertain:true,
  fallbackWires:[tamperedWire],
}]});
storage['configPending.v3']=tamperedLineage;
reloadLifecycle();
recoveryStart=sentCount;
handlers.ready();
assert.strictEqual(sent[recoveryStart].message[1],7,
  'a duplicate/tampered recovery lineage blocks committed configuration delivery');
assert.strictEqual(storage['configPending.v3'],tamperedLineage);
sent[recoveryStart].ok();

const invariantA=wireNamed('Invariant A'),invariantB=wireNamed('Invariant B'),invariantC=wireNamed('Invariant C');
const brokenTwoItemLineage=JSON.stringify({protocol:3,release:'0.1.7',format:2,items:[{
  token:'invariant-b',wire:invariantB,uncertain:true,selfUncertain:true,fallbackWires:[invariantA],
},{
  token:'invariant-c',wire:invariantC,uncertain:true,selfUncertain:false,
  fallbackWires:[invariantB],
}]});
storage['configPending.v3']=brokenTwoItemLineage;
reloadLifecycle();
recoveryStart=sentCount;
handlers.ready();
assert.strictEqual(sent[recoveryStart].message[1],7,
  'a downstream item that omits part of the current lineage fails closed');
assert.strictEqual(storage['configPending.v3'],brokenTwoItemLineage);
sent[recoveryStart].ok();

// The uncertainty bound is fail-closed. A ninth distinct possible wire cannot truncate the oldest
// predecessor merely to accept another save.
const boundedWires=[];
for(let i=0;i<8;i++)boundedWires.push(wireNamed('Bounded '+i));
const boundedState={protocol:3,release:'0.1.7',format:2,items:[{
  token:'bounded-state',wire:boundedWires[0],uncertain:true,selfUncertain:true,
  fallbackWires:boundedWires.slice(1),
}]};
storage['configPending.v3']=JSON.stringify(boundedState);
reloadLifecycle();
recoveryStart=sentCount;
closeWith(candidateNamed('Ninth distinct'));
assert.strictEqual(sentCount,recoveryStart,'a lineage-overflow save is not sent');
assert.deepStrictEqual(JSON.parse(storage['configPending.v3']),boundedState,
  'lineage overflow preserves all eight prior possibilities without truncation');
assert.strictEqual(storage['configNotice.v3'],'uncertain');
closeWith(config.parse(boundedWires[7]));
const boundedExistingRetry=finishConfigAt(recoveryStart);
const boundedExistingPending=JSON.parse(storage['configPending.v3']).items[0];
assert.strictEqual(new Set([boundedExistingPending.wire].concat(boundedExistingPending.fallbackWires)).size,8,
  'resaving an existing possible wire reorders but does not grow or truncate a full lineage');
configAck(boundedExistingRetry.id,0);
assert.strictEqual(storage['configPending.v3'],undefined);

// Preserve the complete A/B/C predecessor chain. If C and then B are deterministically invalid,
// A remains the only possible applied configuration and is reconciled rather than forgotten.
const nestedA=wireNamed('Nested A'),nestedB=wireNamed('Nested B');
storage['configPending.v3']=JSON.stringify({protocol:3,release:'0.1.7',format:2,items:[{
  token:'nested-b',wire:nestedB,uncertain:true,selfUncertain:true,fallbackWires:[nestedA],
}]});
reloadLifecycle();
recoveryStart=sentCount;
closeWith(candidateNamed('Nested C'));
const nestedC=finishConfigAt(recoveryStart);
configAck(nestedC.id,8);
const nestedBRetry=finishConfigAt(nestedC.next);
let nestedPending=JSON.parse(storage['configPending.v3']).items[0];
assert(nestedPending.wire.includes('Nested B'));
assert(nestedPending.fallbackWires[0].includes('Nested A'),
  'restoring B also restores B\'s predecessor lineage');
configAck(nestedBRetry.id,8);
const nestedARetry=finishConfigAt(nestedBRetry.next);
nestedPending=JSON.parse(storage['configPending.v3']).items[0];
assert(nestedPending.wire.includes('Nested A'));
assert.deepStrictEqual(nestedPending.fallbackWires,[]);
configAck(nestedARetry.id,0);
assert(config.parse(storage.config).profiles[0].name==='Nested A');
assert.strictEqual(storage['configPending.v3'],undefined);

global.setTimeout = originalSetTimeout;
global.clearTimeout = originalClearTimeout;
delete global.openedSettingsURL;
delete global.Pebble;
delete global.localStorage;
