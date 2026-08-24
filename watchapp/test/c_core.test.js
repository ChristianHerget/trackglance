'use strict';

const assert = require('assert');
const childProcess = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const root = path.join(__dirname, '..');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'locus-watch-core-'));
const executable = path.join(temporary, 'watch_core_test');
const sanitizerFlags = [
  '-fsanitize=address,undefined',
  '-fno-sanitize-recover=undefined',
  '-fno-omit-frame-pointer',
];
const commonFlags = [
  '-std=c11', '-Wall', '-Wextra', '-Werror', '-pedantic',
  ...sanitizerFlags,
  '-I', path.join(root, 'test/fakes'),
  '-I', path.join(root, 'src/c'),
];

try {
  const productionSources = ['watch_config.c', 'watch_state.c', 'persistent_blob.c', 'ui_metrics.c'];
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
    ...sanitizerFlags,
    testObject, ...objects, '-o', executable,
  ], {encoding: 'utf8'});
  assert.strictEqual(link.status, 0, link.stderr || link.stdout);

  const sanitizerEnvironment = {
    ...process.env,
    ASAN_OPTIONS: 'detect_leaks=1:halt_on_error=1',
    UBSAN_OPTIONS: 'halt_on_error=1:print_stacktrace=1',
  };
  const run = childProcess.spawnSync(executable, [], {
    encoding: 'utf8',
    env: sanitizerEnvironment,
  });
  assert.strictEqual(run.status, 0, run.stderr || run.stdout);

  const undefinedBehaviorProbe = path.join(temporary, 'undefined_behavior_probe');
  const compileProbe = childProcess.spawnSync('cc', [
    ...commonFlags,
    '-x', 'c', '-', '-o', undefinedBehaviorProbe,
  ], {
    encoding: 'utf8',
    input: '#include <limits.h>\nint main(void) { volatile int value = INT_MAX; return value + 1; }\n',
  });
  assert.strictEqual(compileProbe.status, 0, compileProbe.stderr || compileProbe.stdout);
  const runProbe = childProcess.spawnSync(undefinedBehaviorProbe, [], {
    encoding: 'utf8',
    env: sanitizerEnvironment,
  });
  assert.notStrictEqual(runProbe.status, 0, 'UBSan must terminate a process after undefined behavior');
  assert.match(runProbe.stderr, /runtime error:/, 'the fail-closed probe must exercise UBSan');
} finally {
  fs.rmSync(temporary, {recursive: true, force: true});
}
