'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const watchapp = path.resolve(__dirname, '..');
const bundle = path.join(watchapp, 'build/watchapp.pbw');
const packageJson = require('../package.json');
const bundleMtime = fs.statSync(bundle).mtimeMs;
for (const input of [
  'package.json', 'src/pkjs/index.js', 'src/c/main.c',
  'src/c/persistent_blob.c', 'src/c/persistent_blob.h',
  'src/c/watch_config.c', 'src/c/watch_config.h',
]) {
  assert(
    fs.statSync(path.join(watchapp,input)).mtimeMs <= bundleMtime,
    `PBW is older than ${input}; run a clean Pebble build before verification`,
  );
}
const entries = execFileSync('unzip',['-Z1',bundle],{encoding:'utf8'}).trim().split('\n');
const appInfo = JSON.parse(execFileSync('unzip',['-p',bundle,'appinfo.json'],{encoding:'utf8'}));
const expectedPlatforms = packageJson.pebble.targetPlatforms.slice().sort();
const bundledPlatforms = [...new Set(entries.filter(name => name.includes('/')).map(name => name.split('/')[0]))].sort();

assert.deepStrictEqual(bundledPlatforms,expectedPlatforms,'PBW platform directories must exactly match package.json');
assert(entries.includes('pebble-js-app.js'),'PBW must contain embedded PKJS');
assert(entries.includes('pebble-js-app.js.map'),'PBW must contain the PKJS source map used for source parity');
const sourceMap = JSON.parse(execFileSync('unzip',['-p',bundle,'pebble-js-app.js.map'],{encoding:'utf8'}));
const pkjsSourceIndex = sourceMap.sources.indexOf('./src/pkjs/index.js');
assert(pkjsSourceIndex >= 0 && Array.isArray(sourceMap.sourcesContent));
const currentPkjs = fs.readFileSync(path.join(watchapp,'src/pkjs/index.js'),'utf8');
const bundledPkjs = sourceMap.sourcesContent[pkjsSourceIndex];
assert.strictEqual(
  bundledPkjs.slice(0,currentPkjs.length),
  currentPkjs,
  'PBW embedded PKJS must be built from the current source, not a stale bundle',
);
assert.match(
  bundledPkjs.slice(currentPkjs.length),
  /^\n+\/{18}\n\/\/ WEBPACK FOOTER\n\/\/ \.\/src\/pkjs\/index\.js\n\/\/ module id = \d+\n\/\/ module chunks = \d+$/,
  'PBW source map may append only the SDK webpack footer to the current PKJS source',
);
for (const platform of expectedPlatforms) {
  for (const file of ['pebble-app.bin','app_resources.pbpack','manifest.json']) {
    assert(entries.includes(`${platform}/${file}`),`PBW is missing ${platform}/${file}`);
  }
  const manifest = JSON.parse(execFileSync('unzip',['-p',bundle,`${platform}/manifest.json`],{encoding:'utf8'}));
  assert.strictEqual(manifest.type,'application');
  assert.strictEqual(manifest.application.name,'pebble-app.bin');
  assert.strictEqual(manifest.resources.name,'app_resources.pbpack');
  assert.strictEqual(
    manifest.application.size,
    execFileSync('unzip',['-p',bundle,`${platform}/pebble-app.bin`]).length,
  );
  assert.strictEqual(
    manifest.resources.size,
    execFileSync('unzip',['-p',bundle,`${platform}/app_resources.pbpack`]).length,
  );
}
assert.strictEqual(appInfo.uuid,packageJson.pebble.uuid);
assert.strictEqual(appInfo.versionLabel,packageJson.version);
assert.deepStrictEqual(appInfo.targetPlatforms.slice().sort(),expectedPlatforms);
assert.deepStrictEqual(appInfo.appKeys,packageJson.pebble.messageKeys);
assert.deepStrictEqual(appInfo.capabilities.slice().sort(),packageJson.pebble.capabilities.slice().sort());
assert.deepStrictEqual(appInfo.companionApp,packageJson.pebble.companionApp);
