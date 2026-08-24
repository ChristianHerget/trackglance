'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const packageJson = JSON.parse(read('watchapp/package.json'));
const packageLock = JSON.parse(read('watchapp/package-lock.json'));
const pkjs = require('../src/pkjs/index.js');
const kotlin = read('android/app/src/main/java/io/github/christianherget/trackglance/bridge/protocol/BridgeProtocol.kt');
const androidBuild = read('android/app/build.gradle.kts');
const androidMessages = read('android/app/src/main/java/io/github/christianherget/trackglance/bridge/pebble/PebbleMessages.kt');
const androidIngress = read('android/app/src/main/java/io/github/christianherget/trackglance/bridge/pebble/BridgePebbleListenerService.kt');
const watch = read('watchapp/src/c/main.c');
const watchConfig = read('watchapp/src/c/watch_config.h');
const protocol = read('protocol/README.md');
const sphinx = read('docs/sphinx/conf.py');

function capture(text, expression, label) {
  const match = expression.exec(text);
  assert(match, `missing ${label}`);
  return match[1];
}

const keys = packageJson.pebble.messageKeys;
assert.deepStrictEqual(Object.values(keys).sort((a,b) => a-b), Array.from({length:54},(_,i) => i));
const keyBlock = capture(kotlin, /object Key \{([\s\S]*?)\n    \}/, 'Kotlin keys');
const kotlinKeys = {};
for (const match of keyBlock.matchAll(/const val ([A-Z][A-Z0-9_]*) = (\d+)/g)) kotlinKeys[match[1]] = +match[2];
const expected = {...keys, VERSION:keys.PROTOCOL_VERSION};
delete expected.PROTOCOL_VERSION;
assert.deepStrictEqual(kotlinKeys, expected);

assert.strictEqual(packageJson.version, '0.2.2');
assert.strictEqual(packageLock.version, '0.2.2');
assert.strictEqual(packageLock.packages[''].version, '0.2.2');
assert.strictEqual(pkjs.RELEASE, '0.2.2');
assert(androidBuild.includes('versionCode = 13'));
assert(androidBuild.includes('versionName = "0.2.2"'));
assert(watch.includes('#define RELEASE_VERSION "0.2.2"'));
assert(sphinx.includes("version = '0.2.2'") && sphinx.includes("release = '0.2.2'"));
assert(protocol.startsWith('# Bridge protocol v4'));
assert(protocol.includes('currently `0.2.2`'));

assert.strictEqual(pkjs.VERSION, 4);
assert(kotlin.includes('const val VERSION = 4'));
assert(watch.includes('#define PROTOCOL_VERSION 4'));
assert.strictEqual(pkjs.TYPES.recordingContext, 10);
assert.strictEqual(pkjs.TYPES.requestRuntimeConfig, 11);
assert(kotlin.includes('RECORDING_CONTEXT(10)') && kotlin.includes('REQUEST_RUNTIME_CONFIG(11)'));
assert(watch.includes('MSG_RECORDING_CONTEXT = 10') && watch.includes('MSG_REQUEST_RUNTIME_CONFIG = 11'));
assert.strictEqual(pkjs.KEYS.locusId, keys.LOCUS_PROFILE_ID);
assert.strictEqual(pkjs.KEYS.fingerprintA, keys.CONFIG_FINGERPRINT_A);
assert.strictEqual(pkjs.KEYS.fingerprintB, keys.CONFIG_FINGERPRINT_B);

assert.strictEqual(pkjs.LIMIT.pages, 4);
assert(watchConfig.includes('#define WATCH_MAX_PROFILES 4'));
assert(kotlin.includes('const val MAX_ACTIVITY_PAGES = 4'));
assert(!('profiles' in pkjs.LIMIT), 'canonical activity count must be unlimited');
assert(androidIngress.includes('if (wireCommand == BridgeProtocol.Command.START.wire) return ReceiveResult.Nack'));
assert(watch.includes('CMD_START = 1'), 'obsolete Start stays reserved');
assert(!watch.includes('send_command(s_snapshot.state == STATE_STOPPED ? CMD_START'));

assert(androidMessages.includes('return base'), 'snapshots must omit profile metadata');
assert(androidMessages.includes('fun recordingContext('));
assert(androidMessages.includes('BridgeProtocol.Key.LOCUS_PROFILE_ID'));
assert(protocol.includes('smaller than the 512-byte inbox/outbox allocation'));
assert(protocol.includes('`id|name`'));
assert(protocol.includes('one through four pages'));
assert(protocol.includes('every 60'));
assert(protocol.includes('failed catalog') || protocol.includes('Query failure'));

for (const [name, value] of Object.entries({
  v:'PROTOCOL_VERSION', type:'MESSAGE_TYPE', result:'RESULT', index:'CHUNK_INDEX',
  count:'CHUNK_COUNT', data:'CHUNK_DATA', id:'TRANSFER_ID', release:'APP_VERSION',
  generation:'TRANSFER_GENERATION', locusId:'LOCUS_PROFILE_ID',
  fingerprintA:'CONFIG_FINGERPRINT_A', fingerprintB:'CONFIG_FINGERPRINT_B'
})) assert.strictEqual(pkjs.KEYS[name], keys[value], `PKJS key ${name}`);

const documented = new Set();
for (const match of protocol.matchAll(/^\|\s*([0-9, –-]+)\s*\|/gm)) {
  for (const part of match[1].split(',')) {
    const range = part.trim().split(/[–-]/).map(Number);
    for (let value=range[0]; value<=(range[1] === undefined ? range[0] : range[1]); value++) documented.add(value);
  }
}
assert.deepStrictEqual([...documented].sort((a,b)=>a-b), Object.values(keys).sort((a,b)=>a-b));
