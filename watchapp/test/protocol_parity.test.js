'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const packageJson = JSON.parse(read('watchapp/package.json'));
const packageLock = JSON.parse(read('watchapp/package-lock.json'));
const kotlin = read('android/app/src/main/java/app/locuspebble/bridge/protocol/BridgeProtocol.kt');
const androidBuild = read('android/app/build.gradle.kts');
const watch = read('watchapp/src/c/main.c');
const watchConfigSource = read('watchapp/src/c/watch_config.c');
const watchConfig = read('watchapp/src/c/watch_config.h');
const protocol = read('protocol/README.md');
const pkjs = require('../src/pkjs/index.js');

function capture(text, expression, description) {
  const match = expression.exec(text);
  assert(match, `Missing ${description}`);
  return match[1];
}

function numericDefines(text) {
  const values = {};
  for (const match of text.matchAll(/^#define\s+([A-Z][A-Z0-9_]*)\s+(\d+)\s*$/gm)) {
    values[match[1]] = Number(match[2]);
  }
  return values;
}

const keys = packageJson.pebble.messageKeys;
assert.deepStrictEqual(Object.values(keys).sort((a,b) => a-b),Array.from({length:39},(_,i) => i),
  'AppMessage keys must be unique and contiguous from 0 through 38');

const keyBlock = capture(kotlin,/object Key \{([\s\S]*?)\n    \}/,'Kotlin key block');
const kotlinKeys = {};
for (const match of keyBlock.matchAll(/const val ([A-Z][A-Z0-9_]*) = (\d+)/g)) {
  kotlinKeys[match[1]] = Number(match[2]);
}
const expectedKotlinKeys = {...keys};
expectedKotlinKeys.VERSION = expectedKotlinKeys.PROTOCOL_VERSION;
delete expectedKotlinKeys.PROTOCOL_VERSION;
assert.deepStrictEqual(kotlinKeys,expectedKotlinKeys,'Kotlin and package.json must define the same complete key map');

const versionName = capture(androidBuild,/versionName\s*=\s*"([^"]+)"/,'Android versionName');
const uuid = packageJson.pebble.uuid;
assert.strictEqual(packageJson.version,versionName);
assert.strictEqual(packageLock.version,versionName);
assert.strictEqual(packageLock.packages[''].version,versionName);
assert.strictEqual(pkjs.RELEASE,versionName);
assert(watch.includes(`#define RELEASE_VERSION "${versionName}"`));
assert(protocol.includes(`currently \`${versionName}\``));
assert(protocol.includes(`\`${versionName}\` APK and PBW`));
assert(kotlin.includes(`fromString("${uuid}")`));
assert(protocol.includes(`UUID \`${uuid}\``));

const kotlinVersion = Number(capture(kotlin,/object BridgeProtocol \{\s*const val VERSION = (\d+)/,'Kotlin protocol version'));
const cVersion = Number(capture(watch,/#define PROTOCOL_VERSION (\d+)/,'C protocol version'));
assert.strictEqual(pkjs.VERSION,kotlinVersion);
assert.strictEqual(cVersion,kotlinVersion);
assert(protocol.startsWith(`# Bridge protocol v${kotlinVersion}`));

const documentedKeys = new Set();
for (const match of protocol.matchAll(/^\|\s*([0-9, \u2013-]+)\s*\|/gm)) {
  for (const part of match[1].split(',')) {
    const range = part.trim().split(/[\u2013-]/).map(Number);
    for (let value=range[0];value<=(range[1]===undefined?range[0]:range[1]);value++) documentedKeys.add(value);
  }
}
assert.deepStrictEqual([...documentedKeys].sort((a,b) => a-b),Object.values(keys).sort((a,b) => a-b),
  'the protocol key table must cover every AppMessage key');

const keyAliases = {
  v:'PROTOCOL_VERSION', type:'MESSAGE_TYPE', result:'RESULT', time:'SAMPLE_EPOCH_SECONDS',
  session:'SESSION_ID', index:'CHUNK_INDEX', count:'CHUNK_COUNT', data:'CHUNK_DATA',
  id:'TRANSFER_ID', release:'APP_VERSION', hr:'CURRENT_HEART_RATE', sequence:'HEART_RATE_SEQUENCE',
};
for (const [pkjsName,packageName] of Object.entries(keyAliases)) {
  assert.strictEqual(pkjs.KEYS[pkjsName],keys[packageName],`PKJS key ${pkjsName} drifted`);
}

assert.strictEqual(pkjs.TYPES.configResult,9);
assert(watch.includes('MSG_CONFIG_RESULT = 9'));
assert(kotlin.includes('CONFIG_RESULT(9)'));
assert(protocol.includes('config result `9`'));
assert.deepStrictEqual(
  [pkjs.RESULTS.applied,pkjs.RESULTS.queued,pkjs.RESULTS.invalidConfig,pkjs.RESULTS.storageFailed],
  [0,7,8,9],
);
for (const declaration of [
  'RESULT_CONFIG_QUEUED = 7', 'RESULT_INVALID_CONFIG = 8', 'RESULT_STORAGE_FAILED = 9',
]) assert(watch.includes(declaration));
for (const declaration of ['CONFIG_QUEUED(7)', 'INVALID_CONFIG(8)', 'STORAGE_FAILED(9)']) {
  assert(kotlin.includes(declaration));
}

const defines = numericDefines(watchConfig);
assert.strictEqual(pkjs.LIMIT.profiles,defines.WATCH_MAX_PROFILES);
assert.strictEqual(pkjs.LIMIT.displayNameCodePoints,defines.WATCH_PROFILE_NAME_CODEPOINTS);
assert.strictEqual(pkjs.LIMIT.displayNameBytes,defines.WATCH_PROFILE_NAME_SIZE-1);
assert.strictEqual(pkjs.LIMIT.locusNameBytes,defines.WATCH_LOCUS_NAME_SIZE-1);
assert.strictEqual(pkjs.LIMIT.idBytes,defines.WATCH_PROFILE_ID_SIZE-1);

const mainDefines = numericDefines(watch);
const androidLimit = name => Number(capture(
  kotlin,
  new RegExp(`const val ${name} = (\\d+)`),
  `Android ${name}`,
));
assert.strictEqual(pkjs.LIMIT.profiles,androidLimit('MAX_PROFILES'));
assert.strictEqual(pkjs.LIMIT.displayNameCodePoints,androidLimit('MAX_PROFILE_NAME_LENGTH'));
assert.strictEqual(pkjs.LIMIT.locusNameBytes,androidLimit('MAX_LOCUS_PROFILE_NAME_BYTES'));
assert(watch.includes('#define CONFIG_SIZE WATCH_CONFIG_BUFFER_SIZE'));
assert(watch.includes('#define CONFIG_CHUNK_BYTES WATCH_CONFIG_CHUNK_BYTES'));
assert(watch.includes('#define CONFIG_MAX_CHUNKS WATCH_CONFIG_MAX_CHUNKS'));
assert.strictEqual(pkjs.LIMIT.configBytes,defines.WATCH_CONFIG_BUFFER_SIZE-1);
assert.strictEqual(defines.WATCH_CONFIG_MAX_CHUNKS,Math.ceil(pkjs.LIMIT.configBytes/pkjs.LIMIT.chunkBytes));
assert.strictEqual(pkjs.LIMIT.profileListBytes,mainDefines.PROFILE_LIST_SIZE-1);
assert.strictEqual(pkjs.LIMIT.profileListBytes,androidLimit('MAX_PROFILE_LIST_BYTES'));
assert.strictEqual(pkjs.LIMIT.chunkBytes,defines.WATCH_CONFIG_CHUNK_BYTES);
assert.strictEqual(pkjs.LIMIT.chunkBytes,mainDefines.PROFILE_CHUNK_BYTES);
assert.strictEqual(pkjs.LIMIT.chunkBytes,androidLimit('MAX_CHUNK_BYTES'));
assert.strictEqual(pkjs.LIMIT.profileChunks,mainDefines.PROFILE_MAX_CHUNKS);
assert.strictEqual(pkjs.LIMIT.profileChunks,Math.ceil(pkjs.LIMIT.profileListBytes/pkjs.LIMIT.chunkBytes));
assert.strictEqual(pkjs.LIMIT.profileChunks,androidLimit('MAX_PROFILE_LIST_CHUNKS'));

const watchFold = capture(
  watchConfigSource,
  /static uint32_t simple_case_fold\(uint32_t value\) \{([\s\S]*?)\n\}/,
  'watch profile-name fold',
);
for (const expression of [
  "value >= 'A' && value <= 'Z'",
  'value >= 0xc0 && value <= 0xd6',
  'value >= 0xd8 && value <= 0xde',
  'value >= 0x391 && value <= 0x3a1',
  'value >= 0x3a3 && value <= 0x3ab',
  'value >= 0x410 && value <= 0x42f',
  'value >= 0x400 && value <= 0x40f',
]) assert(watchFold.includes(expression),`watch fold is missing ${expression}`);
for (const [upper,lower] of [
  ['A','a'],['À','à'],['Þ','þ'],['Α','α'],['Ϋ','ϋ'],['А','а'],['Ѐ','ѐ'],['Џ','џ'],
]) assert.strictEqual(pkjs.profileNameKey(upper),pkjs.profileNameKey(lower),`${upper}/${lower} fold drifted`);
for (const [left,right] of [['Ā','ā'],['ΟΣ','Ος'],['İ','i\u0307']]) {
  assert.notStrictEqual(pkjs.profileNameKey(left),pkjs.profileNameKey(right),`${left}/${right} must remain exact`);
}
for (const documentedRange of [
  'U+0041–U+005A', 'U+00C0–U+00D6', 'U+00D8–U+00DE',
  'U+0391–U+03A1', 'U+03A3–U+03AB', 'U+0410–U+042F', 'U+0400–U+040F',
]) assert(protocol.includes(documentedRange),`protocol is missing case-fold range ${documentedRange}`);
assert(protocol.includes('all other Unicode scalars compare exactly'));
assert.deepStrictEqual(packageJson.pebble.targetPlatforms.slice().sort(),['emery','gabbro']);
assert.deepStrictEqual(packageJson.pebble.capabilities.slice().sort(),['configurable','health']);

const companion = packageJson.pebble.companionApp.android;
const applicationId = capture(androidBuild,/applicationId\s*=\s*"([^"]+)"/,'Android application ID');
assert.deepStrictEqual(companion.apps,[{package:applicationId}]);
assert.strictEqual(companion.url,'https://github.com/ChristianHerget/pebble-locus-map');
