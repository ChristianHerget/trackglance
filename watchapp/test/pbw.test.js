'use strict';

const assert = require('assert');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const watchapp = path.resolve(__dirname, '..');
const bundle = path.join(watchapp, 'build/watchapp.pbw');
const packageJson = require('../package.json');

function unzipEntry(name, encoding) {
  const options = encoding ? {encoding:encoding} : undefined;
  return execFileSync('unzip',['-p',bundle,name],options);
}

function webpackModuleBody(javascript, moduleId) {
  const marker = `/* ${moduleId} */\n/***/ (function`;
  const moduleStart = javascript.indexOf(marker);
  assert(moduleStart >= 0,`PBW PKJS is missing executable webpack module ${moduleId}`);
  assert.strictEqual(
    javascript.indexOf(marker,moduleStart + marker.length),
    -1,
    `PBW PKJS contains duplicate webpack module ${moduleId}`,
  );
  const bodyOpening = javascript.indexOf(' {\n\n',moduleStart + marker.length);
  assert(bodyOpening >= 0,`PBW PKJS module ${moduleId} has an unsupported wrapper`);
  const bodyStart = bodyOpening + ' {\n\n'.length;
  const bodyEnd = javascript.indexOf('\n\n/***/ })',bodyStart);
  assert(bodyEnd >= 0,`PBW PKJS module ${moduleId} has no closing wrapper`);
  return javascript.slice(bodyStart,bodyEnd);
}

const STM32_CRC_POLY = 0x04c11db7;
const stm32CrcTable = [];
for (let value = 0; value < 256; value += 1) {
  let remainder = (value << 24) >>> 0;
  for (let bit = 0; bit < 8; bit += 1) {
    remainder = remainder & 0x80000000
      ? ((remainder << 1) ^ STM32_CRC_POLY) >>> 0
      : (remainder << 1) >>> 0;
  }
  stm32CrcTable.push(remainder);
}

function stm32Crc32(data) {
  let crc = 0xffffffff;
  for (let offset = 0; offset < data.length; offset += 4) {
    const length = Math.min(4,data.length - offset);
    const bytes = [];
    if (length < 4) {
      // Pebble's STM32 routine pads before a final partial word.
      for (let index = length; index < 4; index += 1) bytes.push(0);
      for (let index = 0; index < length; index += 1) bytes.push(data[offset + index]);
    } else {
      for (let index = 3; index >= 0; index -= 1) bytes.push(data[offset + index]);
    }
    for (const byte of bytes) {
      crc = (((crc << 8) >>> 0) ^ stm32CrcTable[((crc >>> 24) ^ byte) & 0xff]) >>> 0;
    }
  }
  return crc >>> 0;
}

function collectRegularFiles(directory) {
  const files = [];
  assert(fs.existsSync(directory),`Watch source path is missing: ${path.relative(watchapp,directory)}`);
  const directoryStatus = fs.lstatSync(directory);
  assert(
    directoryStatus.isDirectory() && !directoryStatus.isSymbolicLink(),
    `Watch source path is not a regular local directory: ${path.relative(watchapp,directory)}`,
  );
  for (const entry of fs.readdirSync(directory,{withFileTypes:true})) {
    const entryPath = path.join(directory,entry.name);
    assert(
      !entry.isSymbolicLink(),
      `Watch build input must not be a symlink: ${path.relative(watchapp,entryPath)}`,
    );
    if (entry.isDirectory()) files.push(...collectRegularFiles(entryPath));
    else if (entry.isFile()) files.push(entryPath);
  }
  return files;
}

function currentWatchBuildInputHash() {
  const digest = crypto.createHash('sha256');
  const sourcePaths = new Set(collectRegularFiles(path.join(watchapp,'src')));
  for (const relative of ['wscript','package.json']) {
    const inputPath = path.join(watchapp,relative);
    assert(
      fs.lstatSync(inputPath).isFile() && !fs.lstatSync(inputPath).isSymbolicLink(),
      `Watch build input is not a regular local file: ${relative}`,
    );
    sourcePaths.add(inputPath);
  }
  for (const resource of packageJson.pebble.resources.media) {
    if (!resource || typeof resource.file !== 'string') continue;
    const resourcePath = path.resolve(watchapp,resource.file);
    const relative = path.relative(watchapp,resourcePath);
    assert(
      relative && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative),
      `Pebble resource escapes the watchapp directory: ${resource.file}`,
    );
    let currentPath = watchapp;
    for (const pathPart of relative.split(path.sep)) {
      currentPath = path.join(currentPath,pathPart);
      assert(
        !fs.lstatSync(currentPath).isSymbolicLink(),
        `Pebble resource must not use symlinks: ${resource.file}`,
      );
    }
    assert(
      fs.statSync(resourcePath).isFile(),
      `Pebble resource is not a regular file: ${resource.file}`,
    );
    sourcePaths.add(resourcePath);
  }
  const inputs = [...sourcePaths]
    .map(source => ({
      absolute:source,
      relative:path.relative(watchapp,source).split(path.sep).join('/'),
    }))
    .sort((left,right) => Buffer.compare(Buffer.from(left.relative),Buffer.from(right.relative)));
  for (const input of inputs) {
    digest.update(input.relative,'utf8');
    digest.update(Buffer.from([0]));
    digest.update(fs.readFileSync(input.absolute));
    digest.update(Buffer.from([0]));
  }
  return digest.digest('hex');
}

assert.strictEqual(stm32Crc32(Buffer.from('123 567 901 34')),0x89f3bab2);
assert.strictEqual(stm32Crc32(Buffer.from('123456789')),0xaff19057);
assert.strictEqual(stm32Crc32(Buffer.from([0xfe,0xff,0xfe,0xff])),0x0519b130);
assert.strictEqual(stm32Crc32(Buffer.from([0xfe,0xff,0xfe,0xff,0x88])),0x495e02ca);

const entries = execFileSync('unzip',['-Z1',bundle],{encoding:'utf8'}).trim().split('\n');
const appInfo = JSON.parse(unzipEntry('appinfo.json','utf8'));
const expectedPlatforms = packageJson.pebble.targetPlatforms.slice().sort();
const bundledPlatforms = [...new Set(entries.filter(name => name.includes('/')).map(name => name.split('/')[0]))].sort();

assert.deepStrictEqual(bundledPlatforms,expectedPlatforms,'PBW platform directories must exactly match package.json');
assert(entries.includes('pebble-js-app.js'),'PBW must contain embedded PKJS');
assert(entries.includes('pebble-js-app.js.map'),'PBW must contain the PKJS source map');
const bundledJavascript = unzipEntry('pebble-js-app.js','utf8');
const entryMatch = bundledJavascript.match(/return __webpack_require__\((\d+)\);/);
assert(entryMatch,'PBW PKJS has no executable webpack entry module');
const entryBody = webpackModuleBody(bundledJavascript,entryMatch[1]);
const applicationMatch = entryBody.match(/module\.exports = __webpack_require__\((\d+)\);/);
assert(applicationMatch,'PBW PKJS entry module does not load an application module');
const currentPkjs = fs.readFileSync(path.join(watchapp,'src/pkjs/index.js'),'utf8');
const moduleGuard = "if(typeof module!=='undefined')";
assert.strictEqual(
  currentPkjs.split(moduleGuard).length - 1,
  1,
  'PKJS source must contain exactly one webpack-folded CommonJS guard',
);
const webpackPkjs = currentPkjs.replace(moduleGuard,'if(true)').replace(/\n$/,'');
const expectedApplicationBody = webpackPkjs.split('\n').map(line => `\t${line}`).join('\n') + '\n';
assert.strictEqual(
  webpackModuleBody(bundledJavascript,applicationMatch[1]),
  expectedApplicationBody,
  'PBW executable PKJS must be compiled from the current source, not a stale source map',
);
const expectedBuildMarker = Buffer.from(
  `LOCUS_WATCH_BUILD_SHA256:${currentWatchBuildInputHash()}`,
  'ascii',
);
for (const platform of expectedPlatforms) {
  for (const file of ['pebble-app.bin','app_resources.pbpack','manifest.json']) {
    assert(entries.includes(`${platform}/${file}`),`PBW is missing ${platform}/${file}`);
  }
  const manifest = JSON.parse(unzipEntry(`${platform}/manifest.json`,'utf8'));
  const application = unzipEntry(`${platform}/pebble-app.bin`);
  const resources = unzipEntry(`${platform}/app_resources.pbpack`);
  assert.strictEqual(manifest.type,'application');
  assert.strictEqual(manifest.application.name,'pebble-app.bin');
  assert.strictEqual(manifest.resources.name,'app_resources.pbpack');
  assert.strictEqual(manifest.application.size,application.length);
  assert.strictEqual(manifest.resources.size,resources.length);
  assert.strictEqual(
    manifest.application.crc,
    stm32Crc32(application),
    `${platform} application CRC must match its manifest`,
  );
  assert.strictEqual(
    manifest.resources.crc,
    stm32Crc32(resources),
    `${platform} resource CRC must match its manifest`,
  );
  assert(
    application.includes(expectedBuildMarker),
    `${platform} application must embed the current deterministic watch build-input hash`,
  );
}
assert.strictEqual(appInfo.uuid,packageJson.pebble.uuid);
assert.strictEqual(appInfo.versionLabel,packageJson.version);
assert.strictEqual(appInfo.name,packageJson.name);
assert.strictEqual(appInfo.displayName,packageJson.pebble.displayName);
assert.strictEqual(appInfo.shortName,packageJson.pebble.displayName);
assert.strictEqual(appInfo.longName,packageJson.pebble.displayName);
assert.strictEqual(appInfo.companyName,packageJson.author);
assert.strictEqual(appInfo.sdkVersion,packageJson.pebble.sdkVersion);
assert.strictEqual(appInfo.enableMultiJS,packageJson.pebble.enableMultiJS);
assert.deepStrictEqual(appInfo.targetPlatforms.slice().sort(),expectedPlatforms);
assert.deepStrictEqual(appInfo.appKeys,packageJson.pebble.messageKeys);
assert.deepStrictEqual(appInfo.messageKeys,packageJson.pebble.messageKeys);
assert.deepStrictEqual(appInfo.capabilities.slice().sort(),packageJson.pebble.capabilities.slice().sort());
assert.deepStrictEqual(appInfo.watchapp,packageJson.pebble.watchapp);
assert.deepStrictEqual(appInfo.resources,packageJson.pebble.resources);
assert.deepStrictEqual(appInfo.companionApp,packageJson.pebble.companionApp);
