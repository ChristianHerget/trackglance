'use strict';

const assert = require('assert');

const values = new Map();
global.localStorage = {
  getItem(key) { return values.has(key) ? values.get(key) : null; },
  setItem(key, value) { values.set(key, String(value)); },
  removeItem(key) { values.delete(key); },
};

const listeners = {};
const sent = [];
global.Pebble = {
  addEventListener(name, handler) { listeners[name] = handler; },
  sendAppMessage(frame, success) { sent.push(frame); success(); },
  getActiveWatchInfo() { return {language: 'en_US', platform: 'emery'}; },
  openURL() {},
};

const runtime = require('../src/pkjs/index.js');
const K = runtime.KEYS;
const M = runtime.TYPES;
const R = runtime.RESULTS;
const catalog = [{id: '10', name: 'Hiking'}];
const canonical = runtime.reconcile(runtime.defaultsFor('en'), catalog, 'en').config;
global.localStorage.setItem(runtime.STORAGE_KEYS.config, runtime.serialize(canonical));
const fingerprint = runtime.fingerprints(canonical, 'en');

function inbound(type, extra) {
  listeners.appmessage({payload: Object.assign({
    [K.v]: runtime.VERSION,
    [K.release]: runtime.RELEASE,
    [K.type]: type,
  }, extra)});
}

function configFrames() {
  return sent.filter(frame => frame[K.type] === M.configChunk);
}

function acknowledgeLastTransfer() {
  const frames = configFrames();
  if (!frames.length) return;
  inbound(M.configResult, {[K.id]: frames[frames.length - 1][K.id], [K.result]: R.applied});
}

function acceptCatalog(id, payload) {
  inbound(M.profileChunk, {
    [K.id]: id,
    [K.index]: 0,
    [K.count]: 1,
    [K.result]: R.applied,
    [K.data]: payload,
    [K.generation]: 1,
  });
}

// A first request after PKJS restart still suppresses an exact fingerprint match.
inbound(M.requestRuntimeConfig, {
  [K.locusId]: '10',
  [K.fingerprintA]: fingerprint.a,
  [K.fingerprintB]: fingerprint.b,
});
assert.strictEqual(configFrames().length, 0);

// The missing sentinel and either mismatch transfer the active projection.
inbound(M.requestRuntimeConfig, {
  [K.locusId]: '10',
  [K.fingerprintA]: 0,
  [K.fingerprintB]: 0,
});
assert(configFrames().length > 0);
acknowledgeLastTransfer();
sent.length = 0;
inbound(M.requestRuntimeConfig, {
  [K.locusId]: '10',
  [K.fingerprintA]: fingerprint.a,
  [K.fingerprintB]: (fingerprint.b + 1) >>> 0,
});
assert(configFrames().length > 0);
acknowledgeLastTransfer();

// An identical fresh catalog does not push, while a canonical change does.
sent.length = 0;
acceptCatalog(1, '10|Hiking');
assert.strictEqual(configFrames().length, 0);
acceptCatalog(2, '10|Hiking\n20|Running');
assert(configFrames().length > 0);
acknowledgeLastTransfer();

// Saving valid Watch Settings still pushes immediately for the remembered active ID.
sent.length = 0;
const saved = runtime.parse(global.localStorage.getItem(runtime.STORAGE_KEYS.config));
saved.theme = saved.theme === 'dark' ? 'light' : 'dark';
listeners.webviewclosed({response: encodeURIComponent(JSON.stringify(saved))});
assert(configFrames().length > 0);
acknowledgeLastTransfer();

delete global.Pebble;
delete global.localStorage;
