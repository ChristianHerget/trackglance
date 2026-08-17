'use strict';

const assert = require('assert');
const crypto = require('crypto');
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
const watchState = read('watchapp/src/c/watch_state.h');
const androidTransport = read('android/app/src/main/java/app/locuspebble/bridge/pebble/PebbleTransport.kt');
const androidRuntime = read('android/app/src/main/java/app/locuspebble/bridge/core/BridgeRuntime.kt');
const androidMessages = read('android/app/src/main/java/app/locuspebble/bridge/pebble/PebbleMessages.kt');
const androidProfileSerial = read(
  'android/app/src/main/java/app/locuspebble/bridge/core/ProfileTransferSerialStore.kt');
const protocol = read('protocol/README.md');
const development = read('docs/development.md');
const endToEnd = read('docs/end-to-end-testing.md');
const settings = read('settings.gradle.kts');
const verificationMetadata = read('gradle/verification-metadata.xml');
const wrapperProperties = read('gradle/wrapper/gradle-wrapper.properties');
const wrapperJar = fs.readFileSync(path.join(root,'gradle/wrapper/gradle-wrapper.jar'));
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
assert.deepStrictEqual(Object.values(keys).sort((a,b) => a-b),Array.from({length:40},(_,i) => i),
  'AppMessage keys must be unique and contiguous from 0 through 39');

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
  generation:'TRANSFER_GENERATION',
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
const stateDefines = numericDefines(watchState);
assert.strictEqual(pkjs.LIMIT.profiles,defines.WATCH_MAX_PROFILES);
assert.strictEqual(pkjs.LIMIT.metrics,defines.WATCH_MAX_SLOTS);
assert.strictEqual(pkjs.LIMIT.displayNameCodePoints,defines.WATCH_PROFILE_NAME_CODEPOINTS);
assert.strictEqual(pkjs.LIMIT.displayNameBytes,defines.WATCH_PROFILE_NAME_SIZE-1);
assert.strictEqual(pkjs.LIMIT.locusNameBytes,defines.WATCH_LOCUS_NAME_SIZE-1);
assert.strictEqual(pkjs.LIMIT.idBytes,defines.WATCH_PROFILE_ID_SIZE-1);

const mainDefines = numericDefines(watch);
const hexadecimalConstant = (text,name,suffix,description) => Number.parseInt(capture(
  text,
  new RegExp(`${name}\\s+(?:=\\s+)?0x([0-9a-f_]+)${suffix}`,'i'),
  description,
).replace(/_/g,''),16);
const kotlinNumericConstant = name => Number(capture(
  kotlin,
  new RegExp(`const val ${name} = ([\\d_]+)L?`),
  `Android ${name}`,
).replace(/_/g,''));
const androidLimit = name => Number(capture(
  kotlin,
  new RegExp(`const val ${name} = (\\d+)`),
  `Android ${name}`,
));
assert.strictEqual(pkjs.LIMIT.profiles,androidLimit('MAX_PROFILES'));
assert.strictEqual(pkjs.LIMIT.displayNameCodePoints,androidLimit('MAX_PROFILE_NAME_LENGTH'));
assert.strictEqual(pkjs.LIMIT.displayNameBytes,androidLimit('MAX_PROFILE_NAME_BYTES'));
assert.strictEqual(pkjs.LIMIT.locusNameBytes,androidLimit('MAX_LOCUS_PROFILE_NAME_BYTES'));
assert.strictEqual(defines.WATCH_WAYPOINT_NAME_BYTES,androidLimit('MAX_WAYPOINT_NAME_BYTES'));
assert(watch.includes('#define CONFIG_SIZE WATCH_CONFIG_BUFFER_SIZE'));
assert(watch.includes('#define CONFIG_CHUNK_BYTES WATCH_CONFIG_CHUNK_BYTES'));
assert(watch.includes('#define CONFIG_MAX_CHUNKS WATCH_CONFIG_MAX_CHUNKS'));
assert.strictEqual(pkjs.LIMIT.configBytes,defines.WATCH_CONFIG_BUFFER_SIZE-1);
assert.strictEqual(defines.WATCH_CONFIG_MAX_CHUNKS,Math.ceil(pkjs.LIMIT.configBytes/pkjs.LIMIT.chunkBytes));
assert.strictEqual(pkjs.LIMIT.profileListBytes,mainDefines.PROFILE_LIST_SIZE-1);
assert.strictEqual(pkjs.LIMIT.profileListBytes,androidLimit('MAX_PROFILE_LIST_BYTES'));
assert.strictEqual(pkjs.LIMIT.chunkBytes,defines.WATCH_CONFIG_CHUNK_BYTES);
assert.strictEqual(pkjs.LIMIT.chunkBytes,stateDefines.WATCH_PROFILE_CHUNK_BYTES);
assert(watch.includes('#define PROFILE_CHUNK_BYTES WATCH_PROFILE_CHUNK_BYTES'));
assert.strictEqual(pkjs.LIMIT.chunkBytes,androidLimit('MAX_CHUNK_BYTES'));
assert.strictEqual(pkjs.LIMIT.profileChunks,stateDefines.WATCH_PROFILE_MAX_CHUNKS);
assert(watch.includes('#define PROFILE_MAX_CHUNKS WATCH_PROFILE_MAX_CHUNKS'));
assert.strictEqual(pkjs.LIMIT.profileChunks,Math.ceil(pkjs.LIMIT.profileListBytes/pkjs.LIMIT.chunkBytes));
assert.strictEqual(pkjs.LIMIT.profileChunks,androidLimit('MAX_PROFILE_LIST_CHUNKS'));
const cTransferSerialMask = hexadecimalConstant(
  watchState,'WATCH_TRANSFER_SERIAL_MASK','u','C transfer serial mask');
const cTransferSerialHalfRange = hexadecimalConstant(
  watchState,'WATCH_TRANSFER_SERIAL_HALF_RANGE','u','C transfer serial half range');
const androidTransferSerialMask = hexadecimalConstant(
  kotlin,'TRANSFER_SERIAL_MASK','L','Android transfer serial mask');
const androidTransferSerialHalfRange = hexadecimalConstant(
  kotlin,'TRANSFER_SERIAL_HALF_RANGE','L','Android transfer serial half range');
assert.strictEqual(pkjs.LIMIT.transferSerialMask,cTransferSerialMask);
assert.strictEqual(pkjs.LIMIT.transferSerialMask,androidTransferSerialMask);
assert.strictEqual(pkjs.LIMIT.transferSerialHalfRange,cTransferSerialHalfRange);
assert.strictEqual(pkjs.LIMIT.transferSerialHalfRange,androidTransferSerialHalfRange);
assert.strictEqual(pkjs.LIMIT.transferSerialMask,0x7fffffff);
assert.strictEqual(pkjs.LIMIT.transferSerialHalfRange,0x40000000);
assert(androidRuntime.includes('profileTransferSerialStore.reserve()') &&
    androidProfileSerial.includes('if (previous == null)') &&
    androidProfileSerial.includes('0L') &&
    androidProfileSerial.includes('(previous + 1L) and BridgeProtocol.TRANSFER_SERIAL_MASK'),
  'Android profile transfers must reserve a dedicated zero-seeded durable +1 serial under the shared mask');
assert.strictEqual(mainDefines.DURABLE_TRANSFER_GENERATION,androidLimit('DURABLE_TRANSFER_GENERATION'));
assert.strictEqual(mainDefines.DURABLE_TRANSFER_GENERATION,1);
assert(androidMessages.includes('BridgeProtocol.Key.TRANSFER_GENERATION') &&
    androidMessages.includes('BridgeProtocol.DURABLE_TRANSFER_GENERATION') &&
    watch.includes('MESSAGE_KEY_TRANSFER_GENERATION') &&
    watch.includes('DURABLE_TRANSFER_GENERATION'),
  'Android and watch profile chunks must carry the durable transfer-generation marker');

const deliveryAttempts = kotlinNumericConstant('DELIVERY_MAX_ATTEMPTS');
const deliveryAttemptMillis = kotlinNumericConstant('DELIVERY_ATTEMPT_TIMEOUT_MILLIS');
const retryBaseMillis = kotlinNumericConstant('DELIVERY_RETRY_BASE_MILLIS');
const confirmationMillis = kotlinNumericConstant('COMMAND_CONFIRMATION_MILLIS');
const transferTimeoutSeconds = kotlinNumericConstant('RECEIVER_TRANSFER_TIMEOUT_SECONDS');
const commandTimeoutSeconds = kotlinNumericConstant('RECEIVER_COMMAND_RESULT_TIMEOUT_SECONDS');
const maxDeliveryMillis = deliveryAttempts * deliveryAttemptMillis +
  retryBaseMillis * deliveryAttempts * (deliveryAttempts - 1) / 2;
assert.strictEqual(deliveryAttempts,pkjs.LIMIT.sendAttempts);
assert.strictEqual(deliveryAttemptMillis,pkjs.LIMIT.ackTimeoutMillis);
assert.strictEqual(mainDefines.CONFIG_TRANSFER_TIMEOUT_SECONDS,transferTimeoutSeconds);
assert.strictEqual(mainDefines.PROFILE_TRANSFER_TIMEOUT_SECONDS,transferTimeoutSeconds);
assert(transferTimeoutSeconds * 1000 > maxDeliveryMillis,
  'receiver transfer lifetime must exceed a full reliable frame-delivery window');
assert.strictEqual(mainDefines.COMMAND_RESULT_TIMEOUT_SECONDS,commandTimeoutSeconds);
assert(commandTimeoutSeconds * 1000 > maxDeliveryMillis * 3 + confirmationMillis,
  'command correlation must outlive an older snapshot, confirmation, barrier, and result delivery');
assert(androidTransport.includes('BridgeProtocol.DELIVERY_MAX_ATTEMPTS') &&
    androidTransport.includes('BridgeProtocol.DELIVERY_ATTEMPT_TIMEOUT_MILLIS') &&
    androidTransport.includes('BridgeProtocol.DELIVERY_RETRY_BASE_MILLIS'),
  'production Android transport must use the timing constants checked above');
assert(androidRuntime.includes('BridgeProtocol.COMMAND_CONFIRMATION_MILLIS'),
  'production command confirmation must use the timing constant checked above');

const displayLimits = /display names: at most (\d+) Unicode scalar values and (\d+) bytes; Locus names: at most (\d+) bytes/.exec(protocol);
assert(displayLimits,'protocol must document the three profile-name limits in the key table');
assert.deepStrictEqual(displayLimits.slice(1).map(Number),[
  pkjs.LIMIT.displayNameCodePoints,pkjs.LIMIT.displayNameBytes,pkjs.LIMIT.locusNameBytes,
]);
assert.strictEqual(
  Number(capture(protocol,/stable IDs are limited to\s+(\d+) bytes/i,'documented stable-ID byte limit')),
  pkjs.LIMIT.idBytes,
);
const configLimits = /complete serialized configuration is at most\s+(\d+) bytes and (\d+) chunks/.exec(protocol);
assert(configLimits,'protocol must document configuration byte and chunk limits together');
assert.deepStrictEqual(configLimits.slice(1).map(Number),[
  pkjs.LIMIT.configBytes,defines.WATCH_CONFIG_MAX_CHUNKS,
]);
const profileListLimits = /complete list is\s+at most (\d+) bytes and (\d+) chunks/.exec(protocol);
assert(profileListLimits,'protocol must document profile-list byte and chunk limits together');
assert.deepStrictEqual(profileListLimits.slice(1).map(Number),[
  pkjs.LIMIT.profileListBytes,pkjs.LIMIT.profileChunks,
]);
assert.strictEqual(
  Number(capture(protocol,/chunk index\/count\/data\/transfer ID[^\n]*at most (\d+) UTF-8 bytes/,'documented chunk byte limit')),
  pkjs.LIMIT.chunkBytes,
);
assert.strictEqual(
  Number(capture(protocol,/exceed (\d+) UTF-8 bytes before calling Locus/,'documented waypoint byte limit')),
  defines.WATCH_WAYPOINT_NAME_BYTES,
);
const numberWord = ['zero','one','two','three','four','five','six','seven','eight'];
assert(protocol.includes(`one through ${numberWord[pkjs.LIMIT.profiles]} profiles`));
assert(protocol.includes(`one through ${numberWord[pkjs.LIMIT.metrics]} unique metric IDs`));
assert(protocol.includes('only when that query succeeds and authoritatively returns an empty list'),
  'protocol must reserve FAILED + empty for a successful authoritative empty query');
assert(protocol.includes('produce no completed profile-list transfer, preserving the watch and PKJS stale caches'),
  'protocol must document no-transfer cache preservation for profile source/validation failure');
assert(protocol.includes('rejects every later snapshot with a lower delivery epoch even after') &&
    protocol.includes('equal delivery epochs remain valid') &&
    protocol.includes('durable floor') &&
    protocol.includes('Close the watchapp before such a reset') &&
    protocol.includes('Ordinary process\nrestart or phone-clock correction needs no watchapp reopen'),
  'protocol must document the lifetime floor, durable sender ordering, and coordinated reset');
assert(protocol.includes('orders deliveries rather than dating the underlying Locus observation') &&
    protocol.includes('can be ahead of phone wall time') &&
    protocol.includes('actual Unix timestamp and is never a delivery-order stamp'),
  'protocol must distinguish snapshot delivery ordering from HR sample time');
assert(protocol.includes(`${commandTimeoutSeconds} seconds`) &&
    protocol.includes(`${transferTimeoutSeconds} seconds`),
  'protocol must document receiver command and transfer retention budgets');
assert(protocol.includes('an identical chunk 0 with the same count and result is a harmless') &&
    /conflicts\s+in\s+data,\s+count,\s+or\s+result\s+invalidates the entire partial transfer/.test(protocol),
  'protocol must document duplicate and conflicting active chunk-zero behavior');
assert(protocol.includes('Transfer IDs form a serial space modulo `2^31`') &&
    protocol.includes('fully envelope-valid marked chunk 0') &&
    protocol.includes('ignores every unmarked or unknown-generation frame') &&
    protocol.includes('`0 < distance < 2^30`') &&
    protocol.includes('exactly-half-range ambiguous value') &&
    protocol.includes('equal-ID chunk 0 may begin the documented whole-transfer retry') &&
    protocol.includes('dual whole-payload checksums') &&
    protocol.includes('completed profile-list floor'),
  'protocol must document transfer serial wrap, ambiguity, and equal-ID completion behavior');
assert(protocol.includes('Transport failure is always ambiguous'),
  'protocol must not claim that a missing final-frame acknowledgement proves non-application');

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
assert.strictEqual((watchFold.match(/return value \+ 0x20;/g)||[]).length,3,
  'the three non-ASCII simple-fold blocks must use the documented +0x20 offset');
assert(watchFold.includes("return value + ('a' - 'A')"),'the ASCII fold offset drifted');
assert(watchFold.includes('return value + 0x50'),'the Cyrillic supplement fold offset drifted');
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
for (const scalarOrRange of [
  'U+0000–U+001F','U+007F','U+0020','U+0085','U+00A0','U+1680','U+2000–U+200A',
  'U+2028','U+2029','U+202F','U+205F','U+3000','U+FEFF',
]) assert(protocol.includes(scalarOrRange),`protocol is missing text-validation scalar ${scalarOrRange}`);
assert.deepStrictEqual(packageJson.pebble.targetPlatforms.slice().sort(),['emery','gabbro']);
assert.deepStrictEqual(packageJson.pebble.capabilities.slice().sort(),['configurable','health']);

const companion = packageJson.pebble.companionApp.android;
const applicationId = capture(androidBuild,/applicationId\s*=\s*"([^"]+)"/,'Android application ID');
assert.deepStrictEqual(companion.apps,[{package:applicationId}]);
assert.strictEqual(companion.url,'https://github.com/ChristianHerget/pebble-locus-map');

for (const document of [development,endToEnd]) {
  assert(document.includes('https://astral.sh/uv/0.12.4/install.sh'),
    'development setup must pin the uv installer');
  assert(document.includes("uv tool install 'pebble-tool==5.0.39' --python 3.13"),
    'development setup must pin Pebble Tool');
}
assert(endToEnd.includes('git checkout --detach 38fd4c6892599d6a02b4b3ca0b3fd518a51d6170'),
  'the reproducible CoreApp source setup must check out the verified commit');
for (const group of ['com.github.asamm','com.github.asamm.locus-api']) {
  assert(settings.includes(`includeGroup("${group}")`),`JitPack must be restricted to ${group}`);
}

assert(verificationMetadata.includes('<verify-metadata>true</verify-metadata>'));
const verifiedArtifacts = [...verificationMetadata.matchAll(/<artifact name="[^"]+">([\s\S]*?)<\/artifact>/g)];
assert(verifiedArtifacts.length > 0,'Gradle dependency verification must cover resolved artifacts');
for (const artifact of verifiedArtifacts) {
  const hashes = [...artifact[1].matchAll(/<sha256 value="([0-9a-f]+)"/g)];
  assert.strictEqual(hashes.length,1,'every verified artifact must have exactly one SHA-256 digest');
  assert.strictEqual(hashes[0][1].length,64,'Gradle SHA-256 digests must be complete');
}
for (const component of [
  ['com.android.tools.build','gradle','9.3.1'],
  ['com.android.tools.build','aapt2','9.3.1-15703166'],
  ['org.jetbrains.kotlin','kotlin-gradle-plugin','2.4.10'],
  ['androidx.activity','activity-compose','1.12.4'],
  ['io.rebble.pebblekit2','client','1.2.0'],
  ['com.github.asamm.locus-api','locus-api-android','0.10.1'],
]) {
  assert(verificationMetadata.includes(
    `<component group="${component[0]}" name="${component[1]}" version="${component[2]}">`,
  ),`Gradle verification metadata is missing ${component.join(':')}`);
}
for (const [artifact,sha256] of [
  ['aapt2-9.3.1-15703166-linux.jar','e772a3dae8354764f1b0793903218427f483982445207f2e4ffc8c2026755bd4'],
  ['aapt2-9.3.1-15703166-osx.jar','1e35bc2ce18c3aae840be2a29659ce50d6043e907a44d98ee1cf375d044fa29c'],
  ['aapt2-9.3.1-15703166-windows.jar','b1006ecec7e5936257e95e97f3eba7ef439d3e44178967cc048f86c9119fb231'],
]) {
  assert(verificationMetadata.includes(
    `<artifact name="${artifact}">\n            <sha256 value="${sha256}"`,
  ),`Gradle verification metadata is missing the verified ${artifact}`);
}
assert(wrapperProperties.includes('gradle-9.6.1-bin.zip'));
assert(wrapperProperties.includes(
  'distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14',
));
assert.strictEqual(
  crypto.createHash('sha256').update(wrapperJar).digest('hex'),
  '497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7',
  'Gradle 9.6.1 wrapper JAR checksum drifted',
);
