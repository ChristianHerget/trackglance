'use strict';

const assert = require('assert');
const childProcess = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const root = path.join(__dirname, '..');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'locus-watch-core-'));
const executable = path.join(temporary, 'watch_core_test');
const commonFlags = [
  '-std=c11', '-Wall', '-Wextra', '-Werror', '-pedantic',
  '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
  '-I', path.join(root, 'test/fakes'),
  '-I', path.join(root, 'src/c'),
];

try {
  const productionSources = ['watch_config.c', 'persistent_blob.c'];
  const objects = [];
  productionSources.forEach(source => {
    const object = path.join(temporary, `${source}.o`);
    const compile = childProcess.spawnSync('cc', [
      ...commonFlags,
      '-Wframe-larger-than=512', '-Wstack-usage=512',
      '-c', path.join(root, 'src/c', source), '-o', object,
    ], {encoding: 'utf8'});
    assert.strictEqual(compile.status, 0, compile.stderr || compile.stdout);
    objects.push(object);
  });

  const testObject = path.join(temporary, 'watch_core_test.o');
  const compileTest = childProcess.spawnSync('cc', [
    ...commonFlags,
    '-c', path.join(root, 'test/watch_core_test.c'), '-o', testObject,
  ], {encoding: 'utf8'});
  assert.strictEqual(compileTest.status, 0, compileTest.stderr || compileTest.stdout);

  const link = childProcess.spawnSync('cc', [
    '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
    testObject, ...objects, '-o', executable,
  ], {encoding: 'utf8'});
  assert.strictEqual(link.status, 0, link.stderr || link.stdout);

  const run = childProcess.spawnSync(executable, [], {
    encoding: 'utf8',
    env: {...process.env, ASAN_OPTIONS: 'detect_leaks=1:halt_on_error=1'},
  });
  assert.strictEqual(run.status, 0, run.stderr || run.stdout);
} finally {
  fs.rmSync(temporary, {recursive: true, force: true});
}
