'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, '../src/c/main.c'), 'utf8');
const constants = {};
for (const match of source.matchAll(/^#define\s+([A-Z][A-Z0-9_]*)\s+(\d+)\s*$/gm)) {
  constants[match[1]] = Number(match[2]);
}

function functionBodies(text) {
  const bodies = [];
  const declaration = /^static\s+[\w *]+\s+(\w+)\s*\([^;]*\)\s*\{/gm;
  let match;
  while ((match = declaration.exec(text))) {
    let depth = 1;
    let cursor = declaration.lastIndex;
    while (cursor < text.length && depth) {
      if (text[cursor] === '{') depth++;
      if (text[cursor] === '}') depth--;
      cursor++;
    }
    bodies.push({name: match[1], body: text.slice(declaration.lastIndex, cursor - 1)});
    declaration.lastIndex = cursor;
  }
  return bodies;
}

const primitiveBytes = {char:1, bool:1, uint8_t:1, int8_t:1, int16_t:2, uint16_t:2, int:4, int32_t:4, uint32_t:4};
function stackViolations(text) {
  const oversized = [];
  for (const fn of functionBodies(text)) {
    let explicitArrayBytes = 0;
    const declarations = /\b(char|bool|u?int(?:8|16|32)?_t|int)\s+([^;]+);/g;
    let declaration;
    while ((declaration = declarations.exec(fn.body))) {
      const arrays = declaration[2].matchAll(/\[\s*([A-Z][A-Z0-9_]*|\d+)\s*\]/g);
      for (const array of arrays) {
        const length = constants[array[1]] || Number(array[1]);
        const bytes = primitiveBytes[declaration[1]] * length;
        explicitArrayBytes += bytes;
        if (bytes > 1024) oversized.push(`${fn.name}: ${bytes}-byte local array`);
      }
    }
    if (explicitArrayBytes > 2048) oversized.push(`${fn.name}: at least ${explicitArrayBytes} bytes of local arrays`);
  }
  return oversized;
}

const oversized = stackViolations(source);
assert.deepStrictEqual(
  oversized,
  [],
  `Pebble stack budget exceeded; large transfer/configuration buffers must use static storage:\n${oversized.join('\n')}`,
);

const formerRegression = '#define CONFIG_SIZE 4096\nstatic void init(void){char raw[CONFIG_SIZE], work[CONFIG_SIZE];}';
assert.strictEqual(stackViolations(formerRegression).length, 3, 'guard must catch the former startup crash');

// These are the buffers involved in startup and configuration application. Keep this explicit
// regression alongside the generic budget check so a future parser refactor cannot hide them.
for (const name of ['s_chunks', 's_pending_chunks', 's_config_work']) {
  assert(
    new RegExp(`^static char ${name}\\[CONFIG_SIZE\\]`, 'm').test(source),
    `${name} must remain in static storage, not on Pebble's call stack`,
  );
}

assert(
  source.includes('s_request_profiles_after_send=true;send_message(MSG_REQUEST_SNAPSHOT,0);'),
  'startup must queue the profile request instead of starting two AppMessage sends back-to-back',
);
assert(
  source.includes('if(s_request_profiles_after_send){s_request_profiles_after_send=false;send_message(MSG_REQUEST_PROFILE_LIST,0);}'),
  'the outbox callback must deliver the queued profile request',
);
