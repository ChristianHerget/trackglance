/* Offline configuration for CoreApp. This file intentionally has no network dependencies. */
(function (root) {
  'use strict';

  var V = 4;
  var RELEASE = '0.2.2';
  var CONFIG = 'config';
  var CACHE = 'locusProfiles.v4';
  var NOTICE = 'configNotice.v4';
  var CONFIG_TRANSFER_SERIAL = 'configTransferSerial.v4';
  var PROFILE_TRANSFER_FLOOR = 'profileTransferFloor.v4';
  var DURABLE_TRANSFER_GENERATION = 1;
  var SERIAL_MASK = 0x7fffffff;
  var SERIAL_HALF_RANGE = 0x40000000;
  var LIMIT = {
    pages: 4, metrics: 6, displayNameCodePoints: 20, displayNameBytes: 80,
    locusNameBytes: 255, locusIdBytes: 20, idBytes: 39, configBytes: 4095,
    profileListBytes: 8191, chunkBytes: 80, profileChunks: 103,
    transferSerialMask: SERIAL_MASK, transferSerialHalfRange: SERIAL_HALF_RANGE,
    sendAttempts: 3, ackTimeoutMillis: 10000
  };
  var K = {
    v: 0, type: 1, result: 4, time: 6, session: 7, locusName: 9,
    index: 30, count: 31, data: 32, id: 33, release: 35, hr: 37,
    sequence: 38, generation: 39, locusId: 51, fingerprintA: 52, fingerprintB: 53
  };
  var M = {
    snapshot: 1, command: 2, commandResult: 3, requestSnapshot: 4,
    configChunk: 5, profileChunk: 6, requestProfiles: 7, heartRate: 8,
    configResult: 9, recordingContext: 10, requestRuntimeConfig: 11
  };
  var R = {applied: 0, failed: 3, queued: 7, invalidConfig: 8, storageFailed: 9};
  var strings = {
    en: {
      title: 'Locus Bridge', activities: 'Activities', theme: 'Theme', dark: 'Dark', light: 'Light',
      heartRate: 'Heart Rate', sendHr: 'Send watch heart rate to Locus', hrInterval: 'Heart rate interval',
      seconds: 'seconds', edit: 'Edit', save: 'Save', done: 'Done', cancel: 'Cancel', reset: 'Reset',
      name: 'Display name', mapping: 'Locus activity', metrics: 'Metrics', addMetric: 'Add metric', clone: 'Clone page',
      remove: 'Remove', defaultName: 'Default', copySuffix: ' copy', drag: 'Drag to reorder',
      deleteLast: 'Deleting the last page immediately creates a new heuristic Default page. Continue?',
      confirmReset: 'Reset all activity pages and global settings to automatic defaults?',
      moveFull: 'The destination activity already has four pages.',
      invalid: 'Choose 1–6 unique metrics and valid page names.',
      duplicate: 'Display names must be unique within an activity.',
      discard: 'Discard all unsaved changes?', fresh: 'Locus activities updated.',
      stale: 'Using the last saved Locus activity list.',
      empty: 'Locus returned no recording activities; saved settings were not changed.',
      unavailable: 'No activity response received from the bridge yet.',
      incompatible: 'Incompatible bridge/watch version. Install version ' + RELEASE + ' on both devices.',
      storage: 'Settings could not be stored; the previous configuration was kept.'
    },
    de: {
      title: 'Locus Bridge', activities: 'Aktivitäten', theme: 'Darstellung', dark: 'Dunkel', light: 'Hell',
      heartRate: 'Herzfrequenz', sendHr: 'Uhrenpuls an Locus senden', hrInterval: 'Pulsintervall',
      seconds: 'Sekunden', edit: 'Bearbeiten', save: 'Speichern', done: 'Fertig', cancel: 'Abbrechen', reset: 'Zurücksetzen',
      name: 'Anzeigename', mapping: 'Locus-Aktivität', metrics: 'Messwerte', addMetric: 'Messwert hinzufügen', clone: 'Seite kopieren',
      remove: 'Entfernen', defaultName: 'Standard', copySuffix: ' Kopie', drag: 'Zum Sortieren ziehen',
      deleteLast: 'Beim Löschen der letzten Seite wird sofort eine heuristische Standardseite erstellt. Fortfahren?',
      confirmReset: 'Alle Aktivitätsseiten und allgemeinen Einstellungen automatisch zurücksetzen?',
      moveFull: 'Die Zielaktivität hat bereits vier Seiten.',
      invalid: '1–6 eindeutige Messwerte und gültige Seitennamen wählen.',
      duplicate: 'Anzeigenamen müssen innerhalb einer Aktivität eindeutig sein.',
      discard: 'Ungespeicherte Änderungen verwerfen?', fresh: 'Locus-Aktivitäten aktualisiert.',
      stale: 'Zuletzt gespeicherte Locus-Aktivitäten werden verwendet.',
      empty: 'Locus liefert keine Aufzeichnungsaktivitäten; gespeicherte Einstellungen wurden nicht geändert.',
      unavailable: 'Noch keine Aktivitätsantwort von der Bridge empfangen.',
      incompatible: 'Bridge und Watch sind inkompatibel. Version ' + RELEASE + ' auf beiden Geräten installieren.',
      storage: 'Einstellungen konnten nicht gespeichert werden; die vorige Konfiguration bleibt erhalten.'
    }
  };
  var metricNames = {
    en: ['Elapsed time','Moving time','Total distance','Moving distance','Current speed','Average speed','Max speed','Current pace','Average pace','Altitude','Ascent','Descent','Vertical speed','Slope','Average heart rate','Max heart rate','Average cadence','Max cadence','Average power','Max power','Energy','Current heart rate'],
    de: ['Gesamtzeit','Zeit in Bewegung','Gesamtstrecke','Strecke in Bewegung','Aktuelle Geschwindigkeit','Durchschnittsgeschwindigkeit','Höchstgeschwindigkeit','Aktuelles Tempo','Durchschnittstempo','Höhe','Anstieg','Abstieg','Vertikalgeschwindigkeit','Steigung','Durchschnittspuls','Maximalpuls','Durchschnittliche Trittfrequenz','Maximale Trittfrequenz','Durchschnittsleistung','Maximalleistung','Energie','Aktueller Puls']
  };

  function locale(value) { return String(value || 'en').toLowerCase().split(/[-_]/)[0] === 'de' ? 'de' : 'en'; }
  function catalogComplete() {
    var keys = Object.keys(strings.en).sort();
    return keys.length === Object.keys(strings.de).length && keys.every(function (key) {
      return strings.en[key] && strings.de[key];
    }) && metricNames.en.length === 22 && metricNames.de.length === 22;
  }
  function utf8Bytes(value) { try { return unescape(encodeURIComponent(String(value))).length; } catch (_) { return -1; } }
  function scanCodePoints(value, visitor) {
    for (var i = 0, count = 0; i < value.length; i++, count++) {
      var first = value.charCodeAt(i), point = first;
      if (first >= 0xd800 && first <= 0xdbff) {
        if (i + 1 >= value.length) return -1;
        var second = value.charCodeAt(++i);
        if (second < 0xdc00 || second > 0xdfff) return -1;
        point = 0x10000 + ((first - 0xd800) << 10) + second - 0xdc00;
      } else if (first >= 0xdc00 && first <= 0xdfff) return -1;
      if (visitor && !visitor(point, count)) return -1;
    }
    return count;
  }
  function unicodeSpace(point) { return point === 0x20 || point === 0x85 || point === 0xa0 || point === 0x1680 || (point >= 0x2000 && point <= 0x200a) || point === 0x2028 || point === 0x2029 || point === 0x202f || point === 0x205f || point === 0x3000 || point === 0xfeff; }
  function validField(value, maxBytes, maxPoints, rejectPipe) {
    if (typeof value !== 'string' || utf8Bytes(value) < 1 || utf8Bytes(value) > maxBytes) return false;
    var nonSpace = false;
    var points = scanCodePoints(value, function (point, count) {
      if (point < 0x20 || point === 0x7f || (rejectPipe && point === 0x7c) || (maxPoints && count >= maxPoints)) return false;
      if (!unicodeSpace(point)) nonSpace = true;
      return true;
    });
    return points >= 0 && nonSpace;
  }
  function validName(value) { return validField(value, LIMIT.displayNameBytes, LIMIT.displayNameCodePoints, true); }
  function validLocus(value) { return validField(value, LIMIT.locusNameBytes, 0, true); }
  function validId(value) { return validField(value, LIMIT.idBytes, 0, true); }
  function validLocusId(value) {
    if (typeof value !== 'string' || !/^(0|-?[1-9][0-9]{0,18})$/.test(value)) return false;
    var negative = value.charAt(0) === '-', magnitude = negative ? value.slice(1) : value;
    return magnitude.length < 19 || magnitude <= (negative ? '9223372036854775808' : '9223372036854775807');
  }
  function integer(value, min, max) { return typeof value === 'number' && isFinite(value) && Math.floor(value) === value && value >= min && value <= max ? value : null; }
  function decimal(value, min, max) { return typeof value === 'string' && /^\d+$/.test(value) && integer(Number(value), min, max) !== null ? Number(value) : null; }
  function fold(value) { return String(value).toLocaleLowerCase(); }
  function profileNameKey(value) { return typeof value === 'string' && scanCodePoints(value) >= 0 ? fold(value) : null; }
  function truncatePoints(value, limit) {
    value = String(value); var end = 0, count = 0;
    while (end < value.length && count++ < limit) end += value.charCodeAt(end) >= 0xd800 && value.charCodeAt(end) <= 0xdbff ? 2 : 1;
    return value.slice(0, end);
  }
  function clone(value) { return JSON.parse(JSON.stringify(value)); }
  var nextId = 0;
  function newId() { return 'p' + Date.now().toString(36) + (++nextId).toString(36) + Math.floor(Math.random() * 0x100000).toString(36); }
  function presetFor(name) {
    var value = fold(name);
    if (/(walk|hik|trek|wander|wandern|gehen|spazier)/.test(value)) return [1,3,10,11,5,22];
    if (/(run|jogg|lauf)/.test(value)) return [1,3,8,9,11,22];
    if (/(cycl|bike|bicycle|rad|fahrrad|rennrad|mtb)/.test(value)) return [1,3,5,6,7,22];
    return [1,3,5,6,10,22];
  }
  function defaultPage(activityName, lang) { return {id: newId(), name: strings[locale(lang)].defaultName, metrics: presetFor(activityName)}; }
  function defaultsFor() { return {schema: 2, theme: 'dark', watchHrToLocus: false, heartRateIntervalSeconds: 5, activities: []}; }
  var defaults = defaultsFor('en');
  function legacyFromWire(wire) {
    try {
      var lines = String(wire).split('\n'), header = lines.shift().split('|');
      if (header.length < 2 || (header[0] !== 'dark' && header[0] !== 'light')) return null;
      var config = defaultsFor();
      config.theme = header[0]; config.watchHrToLocus = header[2] === '1';
      config.heartRateIntervalSeconds = Number(header[3] || 5);
      lines.forEach(function (line) {
        var item = line.split('|'); if (item.length < 4) throw new Error('legacy');
        config.activities.push({locusId: '', locusName: item[1], pages: [{name: item[0], metrics: item[3].split(',').map(Number), id: item[4] || newId()}]});
      });
      config.legacy = true; return config;
    } catch (_) { return null; }
  }
  function migrate(config) {
    if (typeof config === 'string') return legacyFromWire(config);
    if (!config || typeof config !== 'object') return config;
    if (Array.isArray(config.profiles)) {
      var old = defaultsFor(); old.theme = config.theme || 'dark'; old.watchHrToLocus = config.watchHrToLocus === true;
      old.heartRateIntervalSeconds = Number(config.heartRateIntervalSeconds || 5); old.legacy = true;
      config.profiles.forEach(function (profile) { old.activities.push({locusId: '', locusName: profile.locus, pages: [{id: profile.id || newId(), name: profile.name, metrics: profile.metrics}]}); });
      return old;
    }
    config.schema = 2; if (!Array.isArray(config.activities)) config.activities = []; return config;
  }
  function validatePage(page) {
    if (!page || !validId(page.id) || !validName(page.name) || !Array.isArray(page.metrics) || page.metrics.length < 1 || page.metrics.length > LIMIT.metrics) return false;
    var seen = {};
    return page.metrics.every(function (metric) { if (integer(metric, 1, 22) === null || seen[metric]) return false; seen[metric] = true; return true; });
  }
  function validate(config, allowLegacy) {
    config = migrate(config);
    if (!config || config.schema !== 2 || (config.theme !== 'dark' && config.theme !== 'light') || typeof config.watchHrToLocus !== 'boolean' || integer(config.heartRateIntervalSeconds, 1, 60) === null || !Array.isArray(config.activities)) return false;
    var activityIds = {}, pageIds = {};
    return config.activities.every(function (activity) {
      if (!activity || (!validLocusId(activity.locusId) && !(allowLegacy && activity.locusId === '')) || !validLocus(activity.locusName) || activityIds[activity.locusId] || !Array.isArray(activity.pages) || activity.pages.length < 1 || activity.pages.length > LIMIT.pages) return false;
      activityIds[activity.locusId] = true; var names = {};
      return activity.pages.every(function (page) { var key = profileNameKey(page.name); if (!validatePage(page) || names[key] || pageIds[page.id]) return false; names[key] = true; pageIds[page.id] = true; return true; });
    });
  }
  function canonicalObject(config) {
    return {schema:2,theme:config.theme,watchHrToLocus:config.watchHrToLocus,heartRateIntervalSeconds:config.heartRateIntervalSeconds,activities:config.activities.map(function (a) { return {locusId:a.locusId,locusName:a.locusName,pages:a.pages.map(function (p) { return {id:p.id,name:p.name,metrics:p.metrics.slice()}; })}; })};
  }
  function serialize(config) { config = migrate(config); if (!validate(config, false)) throw new Error('Invalid configuration'); return JSON.stringify(canonicalObject(config)); }
  function parse(raw) {
    if (typeof raw !== 'string' || !raw) return null; var config;
    try { config = raw.charAt(0) === '{' ? JSON.parse(raw) : legacyFromWire(raw); } catch (_) { return null; }
    config = migrate(config); return validate(config, !!config.legacy) ? config : null;
  }
  function profilePayload(payload) {
    if (typeof payload !== 'string' || utf8Bytes(payload) > LIMIT.profileListBytes || !payload) return null;
    var ids = {}, profiles = [], lines = payload.split('\n');
    for (var i = 0; i < lines.length; i++) {
      var separator = lines[i].indexOf('|');
      if (separator < 1 || lines[i].indexOf('|', separator + 1) >= 0) return null;
      var id = lines[i].slice(0, separator), name = lines[i].slice(separator + 1);
      if (!validLocusId(id) || !validLocus(name) || ids[id]) return null;
      ids[id] = true; profiles.push({id:id,name:name});
    }
    return profiles;
  }
  function reconcile(config, catalog, lang) {
    config = clone(migrate(config) || defaultsFor(lang));
    if (!Array.isArray(catalog) || !catalog.length || catalog.some(function (item) { return !item || !validLocusId(item.id) || !validLocus(item.name); })) return {config:config,changed:false,authoritative:false};
    var catalogIds = {};
    for (var catalogIndex = 0; catalogIndex < catalog.length; catalogIndex++) {
      if (catalogIds[catalog[catalogIndex].id]) {
        return {config:config,changed:false,authoritative:false};
      }
      catalogIds[catalog[catalogIndex].id] = true;
    }
    var previous = JSON.stringify(config), byId = {}, used = {};
    config.activities.forEach(function (a) { if (validLocusId(a.locusId)) byId[a.locusId] = a; });
    if (config.legacy) {
      config.activities.forEach(function (a) {
        if (a.locusId) return;
        var matches = catalog.filter(function (item) { return item.name === a.locusName; });
        if (matches.length !== 1) matches = catalog.filter(function (item) { return fold(item.name) === fold(a.locusName); });
        if (matches.length === 1 && !byId[matches[0].id]) { a.locusId = matches[0].id; byId[a.locusId] = a; }
      });
      delete config.legacy;
    }
    var activities = [];
    catalog.forEach(function (item) {
      if (used[item.id]) return; used[item.id] = true;
      var a = byId[item.id]; if (!a) a = {locusId:item.id,locusName:item.name,pages:[defaultPage(item.name,lang)]};
      a.locusName = item.name; if (!a.pages || !a.pages.length) a.pages = [defaultPage(item.name,lang)]; activities.push(a);
    });
    config.activities = activities;
    return {config:config,changed:previous !== JSON.stringify(config),authoritative:true};
  }
  function activity(config, locusId) { return config.activities.filter(function (item) { return item.locusId === locusId; })[0] || null; }
  function uniqueName(group, base) { var value=truncatePoints(base,LIMIT.displayNameCodePoints),suffix,counter=2; while(group.pages.some(function(p){return fold(p.name)===fold(value);})){suffix=' '+counter++;value=truncatePoints(base,LIMIT.displayNameCodePoints-scanCodePoints(suffix))+suffix;}return value; }
  function add(config,locusId,source,lang){var group=activity(config,locusId);if(!group||group.pages.length>=LIMIT.pages)return false;var p=clone(source||defaultPage(group.locusName,lang));p.id=newId();p.name=uniqueName(group,p.name+(source?strings[locale(lang)].copySuffix:''));group.pages.push(p);return true;}
  function remove(config,locusId,index,lang){var group=activity(config,locusId);if(!group||!group.pages[index])return false;group.pages.splice(index,1);if(!group.pages.length)group.pages.push(defaultPage(group.locusName,lang));return true;}
  function rename(config,locusId,index,name){var group=activity(config,locusId);if(!group||!group.pages[index]||!validName(name)||group.pages.some(function(p,i){return i!==index&&fold(p.name)===fold(name);}))return false;group.pages[index].name=name;return true;}
  function move(config,locusId,from,to){var group=activity(config,locusId);if(!group||from<0||to<0||from>=group.pages.length||to>=group.pages.length)return false;group.pages.splice(to,0,group.pages.splice(from,1)[0]);return true;}
  function moveToActivity(config,sourceId,pageIndex,destinationId,lang){var source=activity(config,sourceId),destination=activity(config,destinationId);if(!source||!destination||!source.pages[pageIndex]||destination.pages.length>=LIMIT.pages)return false;var p=source.pages.splice(pageIndex,1)[0];p.name=uniqueName(destination,p.name);destination.pages.push(p);if(!source.pages.length)source.pages.push(defaultPage(source.locusName,lang));return true;}
  function resetLibrary(catalog,lang){return reconcile(defaultsFor(lang),catalog,lang);}
  function page(config,locusId,index){var group=activity(config,locusId);return group&&group.pages[index]?clone(group.pages[index]):null;}
  function hashBytes(value){var bytes=unescape(encodeURIComponent(value)),fnv=2166136261>>>0,crc=0xffffffff;for(var i=0;i<bytes.length;i++){var b=bytes.charCodeAt(i);fnv^=b;fnv=Math.imul(fnv,16777619)>>>0;crc^=b;for(var bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}return{a:fnv>>>0,b:(~crc)>>>0};}
  function fingerprints(config){return hashBytes(serialize(config));}
  function projection(config,locusId){var group=activity(config,locusId);if(!group)return null;var fp=fingerprints(config);return[config.theme,config.watchHrToLocus?'1':'0',config.heartRateIntervalSeconds,locusId,fp.a,fp.b].join('|')+'\n'+group.pages.map(function(p){return[p.name,p.metrics.join(','),p.id].join('|');}).join('\n');}
  function chunks(payload,size){var output=[],part='',bytes=0;for(var i=0;i<payload.length;i++){var ch=payload.charAt(i),first=payload.charCodeAt(i);if(first>=0xd800&&first<=0xdbff)ch+=payload.charAt(++i);var count=utf8Bytes(ch);if(bytes+count>size&&part){output.push(part);part='';bytes=0;}part+=ch;bytes+=count;}if(part||!output.length)output.push(part);return output;}
  function serialNewer(candidate,reference){var distance=(candidate-reference)&SERIAL_MASK;return distance>0&&distance<SERIAL_HALF_RANGE;}
  function DurableSerialCounter(storage,key){this.storage=storage;this.key=key;}
  DurableSerialCounter.prototype.reserve=function(){try{if(!this.storage||typeof this.storage.getItem!=='function'||typeof this.storage.setItem!=='function')return null;var raw=this.storage.getItem(this.key),value;if(raw===null)value=0;else{var previous=decimal(raw,0,SERIAL_MASK);if(previous===null||String(previous)!==raw)return null;value=(previous+1)&SERIAL_MASK;}this.storage.setItem(this.key,String(value));return this.storage.getItem(this.key)===String(value)?value:null;}catch(_){return null;}};
  function Outbox(sendFunction,maxAttempts,timeoutMillis){this.sendFunction=sendFunction;this.maxAttempts=maxAttempts||3;this.timeoutMillis=timeoutMillis===undefined?10000:timeoutMillis;this.queue=[];this.busy=false;}
  Outbox.prototype.enqueue=function(frames,done){if(!frames||!frames.length){if(done)done(false);return;}this.queue.push({frames:frames,index:0,attempts:0,done:done});this.pump();};
  Outbox.prototype.pump=function(){var self=this;if(this.busy||!this.queue.length)return;var op=this.queue[0],settled=false,timer=null,frame=op.frames[op.index];this.busy=true;function finish(ok){if(settled)return;settled=true;if(timer!==null)clearTimeout(timer);self.busy=false;if(ok){op.index++;op.attempts=0;if(op.index===op.frames.length){self.queue.shift();if(op.done)op.done(true);}}else if(++op.attempts>=self.maxAttempts){self.queue.shift();if(op.done)op.done(false);}self.pump();}if(this.timeoutMillis>0)timer=setTimeout(function(){finish(false);},this.timeoutMillis);try{this.sendFunction(frame,function(){finish(true);},function(){finish(false);});}catch(_){finish(false);}};
  function AckTracker(timeoutMillis,setTimer,clearTimer){this.timeoutMillis=timeoutMillis;this.setTimer=setTimer||setTimeout;this.clearTimer=clearTimer||clearTimeout;this.pending={};}
  AckTracker.prototype.register=function(id,callback){this.pending[id]={callback:callback,timer:null};};
  AckTracker.prototype.transport=function(id,success){var self=this,entry=this.pending[id];if(!entry)return false;if(!success){delete this.pending[id];entry.callback({kind:'transport-failed'});return false;}entry.timer=this.setTimer(function(){delete self.pending[id];entry.callback({kind:'timeout'});},this.timeoutMillis);return true;};
  AckTracker.prototype.accept=function(id,result){var entry=this.pending[id];if(!entry)return false;if(entry.timer)this.clearTimer(entry.timer);delete this.pending[id];entry.callback({kind:'result',result:result});return true;};
  function Transfer(storage,floorKey){this.floorStorage=storage||null;this.floorKey=floorKey||null;this.floorId=null;this.floorCompleted=false;this.floorBlocked=false;this.durableGenerationSeen=false;if(this.floorStorage&&this.floorKey){try{var raw=this.floorStorage.getItem(this.floorKey);if(raw!==null){var value=JSON.parse(raw),keys=value&&typeof value==='object'&&!Array.isArray(value)?Object.keys(value):[],legacy=value&&value.generation===undefined,allowed=keys.every(function(key){return key==='generation'||key==='id'||key==='completed';});if(!value||!allowed||(!legacy&&value.generation!==DURABLE_TRANSFER_GENERATION)||integer(value.id,0,SERIAL_MASK)===null||typeof value.completed!=='boolean')this.floorBlocked=true;else{this.floorId=value.id;this.floorCompleted=value.completed;this.durableGenerationSeen=!legacy;}}}catch(_){this.floorBlocked=true;}}this.reset();}
  Transfer.prototype.reset=function(){this.id=null;this.count=0;this.result=null;this.parts=[];};
  Transfer.prototype.storeFloor=function(id,completed){if(this.floorBlocked)return false;if(this.floorStorage&&this.floorKey){try{var value={id:id,completed:completed};if(this.durableGenerationSeen)value.generation=DURABLE_TRANSFER_GENERATION;var encoded=JSON.stringify(value);this.floorStorage.setItem(this.floorKey,encoded);if(this.floorStorage.getItem(this.floorKey)!==encoded){this.floorBlocked=true;return false;}}catch(_){this.floorBlocked=true;return false;}}this.floorId=id;this.floorCompleted=completed;return true;};
  Transfer.prototype.accept=function(payload){var generationValue=incoming(payload,K.generation,'TRANSFER_GENERATION'),marked=integer(generationValue,1,1)===DURABLE_TRANSFER_GENERATION;if((generationValue!==undefined&&!marked)||(this.durableGenerationSeen&&!marked))return null;var id=integer(incoming(payload,K.id,'TRANSFER_ID'),0,SERIAL_MASK),index=integer(incoming(payload,K.index,'CHUNK_INDEX'),0,LIMIT.profileChunks-1),count=integer(incoming(payload,K.count,'CHUNK_COUNT'),1,LIMIT.profileChunks),result=integer(incoming(payload,K.result,'RESULT'),0,3),data=incoming(payload,K.data,'CHUNK_DATA');if(id===null||index===null||count===null||index>=count||(result!==R.applied&&result!==R.failed)||typeof data!=='string'||utf8Bytes(data)<0||utf8Bytes(data)>LIMIT.chunkBytes){if(id!==null&&id===this.id&&(!marked||this.durableGenerationSeen))this.reset();return null;}if(marked&&!this.durableGenerationSeen){if(index!==0)return null;this.reset();this.floorId=null;this.floorCompleted=false;this.durableGenerationSeen=true;}if(index===0){if(id===this.id){if(count!==this.count||result!==this.result||this.parts[0]!==data)this.reset();return null;}if(this.floorBlocked||(this.floorId!==null&&(id===this.floorId?this.floorCompleted:!serialNewer(id,this.floorId))))return null;if(id!==this.floorId&&!this.storeFloor(id,false))return null;this.reset();this.id=id;this.count=count;this.result=result;}else if(this.id===null||id!==this.id||count!==this.count||result!==this.result){if(id===this.id)this.reset();return null;}if(this.parts[index]!==undefined){if(this.parts[index]!==data)this.reset();return null;}this.parts[index]=data;for(var i=0;i<count;i++)if(this.parts[i]===undefined)return null;var value=this.parts.join(''),bytes=utf8Bytes(value),complete={id:id,result:this.result,payload:value};if(bytes<0||bytes>LIMIT.profileListBytes||!this.storeFloor(id,true))return null;this.reset();return complete;};
  function incoming(payload,key,name){return payload[key]!==undefined?payload[key]:payload[name];}
  function compatibleEnvelope(payload,type){return integer(incoming(payload,K.v,'PROTOCOL_VERSION'),V,V)===V&&incoming(payload,K.release,'APP_VERSION')===RELEASE&&integer(incoming(payload,K.type,'MESSAGE_TYPE'),type,type)===type;}
  function validHeartRateMessage(payload){return compatibleEnvelope(payload,M.heartRate)&&integer(incoming(payload,K.session,'SESSION_ID'),0,0xffffffff)!==null&&integer(incoming(payload,K.sequence,'HEART_RATE_SEQUENCE'),0,0xffffffff)!==null&&integer(incoming(payload,K.time,'SAMPLE_EPOCH_SECONDS'),0,0xffffffff)!==null&&integer(incoming(payload,K.hr,'CURRENT_HEART_RATE'),25,250)!==null;}
  function configResultMessage(payload){if(!compatibleEnvelope(payload,M.configResult))return null;var id=integer(incoming(payload,K.id,'TRANSFER_ID'),0,SERIAL_MASK),result=integer(incoming(payload,K.result,'RESULT'),0,9);return id===null?null:{id:id,result:result};}
  var outbox,acks,counter;
  function runtimeOutbox(){if(!outbox)outbox=new Outbox(function(frame,success,failure){Pebble.sendAppMessage(frame,success,failure);});return outbox;}
  function runtimeAcks(){if(!acks)acks=new AckTracker(LIMIT.ackTimeoutMillis);return acks;}
  function runtimeCounter(){if(!counter)counter=new DurableSerialCounter(localStorage,CONFIG_TRANSFER_SERIAL);return counter;}
  function send(config,locusId,done){var wire=projection(config,locusId);if(wire===null)return null;var parts=chunks(wire,LIMIT.chunkBytes),id=runtimeCounter().reserve(),fp=fingerprints(config);if(id===null){if(done)done(false);return null;}var frames=parts.map(function(part,index){var frame={};frame[K.v]=V;frame[K.release]=RELEASE;frame[K.type]=M.configChunk;frame[K.index]=index;frame[K.count]=parts.length;frame[K.data]=part;frame[K.id]=id;frame[K.generation]=DURABLE_TRANSFER_GENERATION;frame[K.fingerprintA]=fp.a;frame[K.fingerprintB]=fp.b;return frame;});runtimeAcks().register(id,function(outcome){if(done)done(outcome.kind==='result'&&(outcome.result===R.applied||outcome.result===R.queued));});runtimeOutbox().enqueue(frames,function(ok){runtimeAcks().transport(id,ok);});return id;}
  function readConfig(){try{return parse(localStorage.getItem(CONFIG))||defaultsFor(watchLanguage());}catch(_){return defaultsFor(watchLanguage());}}
  function storeConfig(config){var previous=null;try{previous=localStorage.getItem(CONFIG);var wire=serialize(config);localStorage.setItem(CONFIG,wire);if(localStorage.getItem(CONFIG)!==wire)throw new Error('storage');return true;}catch(_){try{if(previous===null)localStorage.removeItem(CONFIG);else localStorage.setItem(CONFIG,previous);}catch(__){}return false;}}
  function readCache(){try{var cache=JSON.parse(localStorage.getItem(CACHE)||'null');return cache&&cache.protocol===V&&cache.release===RELEASE&&Array.isArray(cache.profiles)?cache:null;}catch(_){return null;}}
  function writeCache(profiles){try{localStorage.setItem(CACHE,JSON.stringify({protocol:V,release:RELEASE,profiles:profiles,updated:Date.now()}));}catch(_){}}
  function watchLanguage(){try{return locale(Pebble.getActiveWatchInfo().language);}catch(_){return'en';}}
  function watchSupportsHeartRate(){try{return Pebble.getActiveWatchInfo().platform==='emery';}catch(_){return false;}}
  function requestProfiles(){var frame={};frame[K.v]=V;frame[K.release]=RELEASE;frame[K.type]=M.requestProfiles;runtimeOutbox().enqueue([frame]);}
  var catalog=null,catalogState='unavailable',activeLocusId=null,transfer=new Transfer(typeof localStorage==='undefined'?null:localStorage,PROFILE_TRANSFER_FLOOR),pendingOpen=false,openTimer=null;
  function acceptCatalog(profiles){catalog=profiles;catalogState='fresh';writeCache(profiles);var result=reconcile(readConfig(),profiles,watchLanguage()),effective=result.config;if(result.authoritative&&!storeConfig(result.config)){try{localStorage.setItem(NOTICE,'storage');}catch(_){}effective=readConfig();}if(activeLocusId&&activity(effective,activeLocusId))send(effective,activeLocusId);if(pendingOpen)openSettings();}
  function safe(value){return String(value).replace(/[&<>"']/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function encodedSettingsPage(config,profiles,lang,state,notice){
    var l=locale(lang),t=strings[l],mn=metricNames[l],data=encodeURIComponent(JSON.stringify(config)),cat=encodeURIComponent(JSON.stringify(profiles||[]));
    return 'data:text/html;charset=utf-8,'+encodeURIComponent('<!doctype html><html lang="'+l+'"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:16px sans-serif;margin:0;background:#f3f3f3;color:#171717}header,.bar{position:sticky;top:0;background:#111;color:#fff;padding:14px;z-index:2}section{background:#fff;margin:10px;padding:12px;border-radius:8px}h2{margin:0 0 8px}.page,.metric{display:flex;gap:8px;align-items:center;border-top:1px solid #ddd;padding:9px 0}.page .name{flex:1;text-align:left;background:none;border:0;font:inherit}.handle{font-size:22px;touch-action:none}.hidden{display:none}label{display:block;margin:10px 0}input,select,button{font:inherit;padding:8px}input,select{max-width:100%;box-sizing:border-box}.bar{bottom:0;top:auto;display:flex;gap:8px}.bar button{flex:1}.note{padding:10px;color:#555}</style><body><header><b>'+safe(t.title)+'</b></header><div class="note">'+safe(notice||t[state]||'')+'</div><main id="overview"><section><label>'+safe(t.theme)+' <select id="theme"><option value="dark">'+safe(t.dark)+'</option><option value="light">'+safe(t.light)+'</option></select></label><div class="heart-rate-settings"><label><input id="watchHr" type="checkbox"> '+safe(t.sendHr)+'</label><label>'+safe(t.hrInterval)+' <input id="interval" type="number" min="1" max="60"> '+safe(t.seconds)+'</label></div></section><div id="groups"></div></main><main id="editor" class="hidden"><section><label>'+safe(t.name)+'<br><input id="pageName"></label><label>'+safe(t.mapping)+'<br><select id="mapping"></select></label><h2>'+safe(t.metrics)+'</h2><div id="metrics"></div><button id="addMetric">'+safe(t.addMetric)+'</button></section><div class="bar"><button id="editorCancel">'+safe(t.cancel)+'</button><button id="editorDone">'+safe(t.done)+'</button></div></main><div id="mainBar" class="bar"><button id="cancel">'+safe(t.cancel)+'</button><button id="save">'+safe(t.save)+'</button></div><script>var c=JSON.parse(decodeURIComponent("'+data+'")),catalog=JSON.parse(decodeURIComponent("'+cat+'")),T='+JSON.stringify(t)+',MN='+JSON.stringify(mn)+',editing=null,draft=null;function q(id){return document.getElementById(id)}function group(id){return c.activities.filter(function(a){return a.locusId===id})[0]}function fold(s){return String(s).toLocaleLowerCase()}function newId(){return"p"+Date.now().toString(36)+Math.random().toString(36).slice(2,9)}function preset(n){n=fold(n);if(/(walk|hik|trek|wander|wandern|gehen|spazier)/.test(n))return[1,3,10,11,5,22];if(/(run|jogg|lauf)/.test(n))return[1,3,8,9,11,22];if(/(cycl|bike|bicycle|rad|fahrrad|rennrad|mtb)/.test(n))return[1,3,5,6,7,22];return[1,3,5,6,10,22]}function defaultPage(a){return{id:newId(),name:T.defaultName,metrics:preset(a.locusName)}}function unique(g,n){var base=n,i=2;while(g.pages.some(function(p){return fold(p.name)===fold(n)}))n=base+" "+i++;return n}function drag(el,arr,index,redraw){var start=0;el.onpointerdown=function(e){start=e.clientY;el.setPointerCapture(e.pointerId)};el.onpointerup=function(e){var to=Math.max(0,Math.min(arr.length-1,index+Math.round((e.clientY-start)/46)));arr.splice(to,0,arr.splice(index,1)[0]);redraw()}}function draw(){q("theme").value=c.theme;q("watchHr").checked=c.watchHrToLocus;q("interval").value=c.heartRateIntervalSeconds;var box=q("groups");box.innerHTML="";c.activities.slice().sort(function(a,b){return a.locusName.localeCompare(b.locusName)}).forEach(function(a){var s=document.createElement("section"),h=document.createElement("h2");h.textContent=a.locusName;s.appendChild(h);a.pages.forEach(function(p,i){var r=document.createElement("div");r.className="page";var handle=document.createElement("button");handle.className="handle";handle.textContent="☰";handle.setAttribute("aria-label",T.drag);drag(handle,a.pages,i,draw);r.appendChild(handle);var n=document.createElement("button");n.className="name";n.textContent=p.name;n.onclick=function(){openEditor(a.locusId,i)};r.appendChild(n);var cp=document.createElement("button");cp.textContent="⧉";cp.onclick=function(){if(a.pages.length>=4)return;var x=JSON.parse(JSON.stringify(p));x.id=newId();x.name=unique(a,x.name+T.copySuffix);a.pages.splice(i+1,0,x);draw()};r.appendChild(cp);var del=document.createElement("button");del.textContent="−";del.onclick=function(){if(a.pages.length===1&&!confirm(T.deleteLast))return;a.pages.splice(i,1);if(!a.pages.length)a.pages.push(defaultPage(a));draw()};r.appendChild(del);s.appendChild(r)});box.appendChild(s)})}function openEditor(id,index){editing={id:id,index:index};draft=JSON.parse(JSON.stringify(group(id).pages[index]));q("overview").className="hidden";q("mainBar").className="hidden";q("editor").className="";q("pageName").value=draft.name;var m=q("mapping");m.innerHTML="";c.activities.slice().sort(function(a,b){return a.locusName.localeCompare(b.locusName)}).forEach(function(a){var o=document.createElement("option");o.value=a.locusId;o.textContent=a.locusName;o.selected=a.locusId===id;m.appendChild(o)});drawMetrics()}function drawMetrics(){var box=q("metrics");box.innerHTML="";draft.metrics.forEach(function(metric,i){var r=document.createElement("div");r.className="metric";var h=document.createElement("button");h.className="handle";h.textContent="☰";h.setAttribute("aria-label",T.drag);drag(h,draft.metrics,i,drawMetrics);r.appendChild(h);var s=document.createElement("select");MN.forEach(function(name,j){var o=document.createElement("option");o.value=j+1;o.textContent=name;o.selected=j+1===metric;s.appendChild(o)});s.onchange=function(){draft.metrics[i]=+s.value};r.appendChild(s);var d=document.createElement("button");d.textContent="−";d.onclick=function(){if(draft.metrics.length>1){draft.metrics.splice(i,1);drawMetrics()}};r.appendChild(d);box.appendChild(r)})}q("addMetric").onclick=function(){if(draft.metrics.length>=6)return;for(var i=1;i<=22;i++)if(draft.metrics.indexOf(i)<0){draft.metrics.push(i);break}drawMetrics()};function closeEditor(){q("editor").className="hidden";q("overview").className="";q("mainBar").className="bar";editing=null;draft=null;draw()}q("editorCancel").onclick=closeEditor;q("editorDone").onclick=function(){var source=group(editing.id),destination=group(q("mapping").value),name=q("pageName").value;if(!name||draft.metrics.length<1||draft.metrics.filter(function(x,i,a){return a.indexOf(x)===i}).length!==draft.metrics.length||destination.pages.some(function(p){return p.id!==draft.id&&fold(p.name)===fold(name)})){alert(T.duplicate);return}if(source!==destination&&destination.pages.length>=4){alert(T.moveFull);return}draft.name=name;if(source===destination)source.pages[editing.index]=draft;else{source.pages.splice(editing.index,1);destination.pages.push(draft);if(!source.pages.length)source.pages.push(defaultPage(source))}closeEditor()};q("cancel").onclick=function(){closeConfig("")};q("save").onclick=function(){c.theme=q("theme").value;c.watchHrToLocus=q("watchHr").checked;c.heartRateIntervalSeconds=+q("interval").value;closeConfig(encodeURIComponent(JSON.stringify(c)))};draw();</script></body></html>');
  }
  function settingsPage(config,profiles,lang,state,notice,supportsHeartRate){
    var prefix='data:text/html;charset=utf-8,';
    var page=decodeURIComponent(
      encodedSettingsPage(config,profiles,lang,state,notice).slice(prefix.length));
    page=page.replace(
      '<script>',
      '<script>function closeConfig(x){if(typeof window.__pebbleConfigClose==="function")window.__pebbleConfigClose(x);else location.href="pebblejs://close#"+encodeURIComponent(x)}');
    if(supportsHeartRate===false)page=page.replace('class="heart-rate-settings"','class="heart-rate-settings hidden"');
    page=page.replace(
      'closeConfig(encodeURIComponent(JSON.stringify(c)))',
      'closeConfig(JSON.stringify(c))');
    page=page.replace(
      'pages.push(defaultPage(a))',
      'pages.push(defaultPage(a.locusName))');
    page=page.replace(
      'pages.push(defaultPage(source))',
      'pages.push(defaultPage(source.locusName))');
    page=page.replace(
      '<button id="save">',
      '<button id="reset">'+safe(strings[locale(lang)].reset)+'</button><button id="save">');
    page=page.replace(
      '.handle{font-size:22px;',
      '.clone{position:relative;width:48px;height:42px}.clone:before,.clone:after{content:"";position:absolute;width:13px;height:13px;border:2px solid currentColor}.clone:before{left:12px;top:8px}.clone:after{left:18px;top:14px;background:#eee}.handle{font-size:22px;');
    page=page.replace(
      'cp.textContent="⧉";',
      'cp.className="clone";cp.setAttribute("aria-label",T.clone);');
    page=page.replace(
      'function unique(g,n){var base=n,i=2;while(g.pages.some(function(p){return fold(p.name)===fold(n)}))n=base+" "+i++;return n}',
      'function scalars(s){return Array.from(String(s))}function cut(s,n){return scalars(s).slice(0,n).join("")}function goodName(s){try{return scalars(s).length>0&&scalars(s).length<=20&&unescape(encodeURIComponent(s)).length<=80&&!/[\\x00-\\x1f\\x7f|]/.test(s)&&/\\S/.test(s)}catch(_){return false}}function unique(g,n){var base=cut(n,20),i=2;n=base;while(g.pages.some(function(p){return fold(p.name)===fold(n)})){var suffix=" "+i++;n=cut(base,20-scalars(suffix).length)+suffix}return n}');
    page=page.replace(
      'if(!name||draft.metrics.length<1',
      'if(!goodName(name)||draft.metrics.length<1');
    page=page.replace(
      'c.heartRateIntervalSeconds=+q("interval").value;closeConfig(JSON.stringify(c))',
      'c.heartRateIntervalSeconds=+q("interval").value;if(c.heartRateIntervalSeconds<1||c.heartRateIntervalSeconds>60){alert(T.invalid);return}closeConfig(JSON.stringify(c))');
    page=page.replace(
      'q("cancel").onclick=',
      'q("reset").onclick=function(){if(!confirm(T.confirmReset))return;c={schema:2,theme:"dark",watchHrToLocus:false,heartRateIntervalSeconds:5,activities:catalog.map(function(a){return{locusId:a.id,locusName:a.name,pages:[defaultPage(a.name)]}})};draw()};q("cancel").onclick=');
    return prefix+encodeURIComponent(page);
  }
  function openSettings(){if(openTimer){clearTimeout(openTimer);openTimer=null;}pendingOpen=false;var cached=readCache(),profiles=catalog||(cached&&cached.profiles)||[],state=catalog?catalogState:cached?'stale':'unavailable',config=catalog?reconcile(readConfig(),profiles,watchLanguage()).config:readConfig(),notice=null;try{notice=localStorage.getItem(NOTICE);localStorage.removeItem(NOTICE);}catch(_){}Pebble.openURL(settingsPage(config,profiles,watchLanguage(),state,notice==='storage'?strings[watchLanguage()].storage:null,watchSupportsHeartRate()));}
  if(typeof Pebble!=='undefined'){
    Pebble.addEventListener('ready',function(){requestProfiles();});
    Pebble.addEventListener('showConfiguration',function(){pendingOpen=true;requestProfiles();openTimer=setTimeout(openSettings,500);});
    Pebble.addEventListener('webviewclosed',function(event){if(!event.response)return;try{var config=migrate(JSON.parse(decodeURIComponent(event.response)));if(!validate(config,false))return;if(!storeConfig(config)){localStorage.setItem(NOTICE,'storage');return;}if(activeLocusId&&activity(config,activeLocusId))send(config,activeLocusId,function(ok){if(!ok)localStorage.setItem(NOTICE,'transport');});}catch(_){}});
    Pebble.addEventListener('appmessage',function(event){var payload=event&&event.payload||{},type=integer(incoming(payload,K.type,'MESSAGE_TYPE'),1,11);if(type===M.configResult){var ack=configResultMessage(payload);if(ack)runtimeAcks().accept(ack.id,ack.result);return;}if(type===M.heartRate){validHeartRateMessage(payload);return;}if(type===M.requestRuntimeConfig&&compatibleEnvelope(payload,M.requestRuntimeConfig)){var id=incoming(payload,K.locusId,'LOCUS_PROFILE_ID');if(!validLocusId(id))return;var changedActivity=activeLocusId!==id;activeLocusId=id;var config=readConfig(),fp=fingerprints(config),wa=integer(incoming(payload,K.fingerprintA,'CONFIG_FINGERPRINT_A'),0,0xffffffff),wb=integer(incoming(payload,K.fingerprintB,'CONFIG_FINGERPRINT_B'),0,0xffffffff);if(activity(config,id)&&(changedActivity||wa!==fp.a||wb!==fp.b))send(config,id);return;}if(type!==M.profileChunk||!compatibleEnvelope(payload,M.profileChunk))return;var complete=transfer.accept(payload);if(!complete||complete.result!==R.applied)return;var profiles=profilePayload(complete.payload);if(profiles)acceptCatalog(profiles);});
  }
  if(typeof module!=='undefined')module.exports={VERSION:V,RELEASE:RELEASE,LIMIT:LIMIT,KEYS:K,TYPES:M,RESULTS:R,STORAGE_KEYS:{config:CONFIG,cache:CACHE,notice:NOTICE,configSerial:CONFIG_TRANSFER_SERIAL,profileFloor:PROFILE_TRANSFER_FLOOR},catalogComplete:catalogComplete,defaults:defaults,defaultsFor:defaultsFor,locale:locale,validate:validate,serialize:serialize,parse:parse,migrate:migrate,reconcile:reconcile,resetLibrary:resetLibrary,presetFor:presetFor,defaultPage:defaultPage,add:add,remove:remove,rename:rename,move:move,moveToActivity:moveToActivity,page:page,activity:activity,projection:projection,fingerprints:fingerprints,Transfer:Transfer,DurableSerialCounter:DurableSerialCounter,serialNewer:serialNewer,Outbox:Outbox,AckTracker:AckTracker,profilePayload:profilePayload,chunks:chunks,utf8Bytes:utf8Bytes,profileNameKey:profileNameKey,validName:validName,validLocus:validLocus,validLocusId:validLocusId,validId:validId,validHeartRateMessage:validHeartRateMessage,configResultMessage:configResultMessage,settingsPage:settingsPage};
})(this);
