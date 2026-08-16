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

assert(source.includes('#define PERSIST_ACTIVE_ID 103'), 'the active profile ID must have persistent storage');
assert(source.includes('persist_write_string(PERSIST_ACTIVE_ID,s_profiles[s_selected].id)'),
  'choosing a profile must persist its stable ID');
assert(source.includes('.subtitle=i==s_selected?tr("Active","Aktiv"):NULL'),
  'the chooser must mark the active profile');
assert(source.includes('.num_items=s_profile_count'), 'the chooser must list every configured profile');
assert(source.includes('s.state==STATE_RECORDING||s.state==STATE_PAUSED'),
  'profile selection must remain locked while recording or paused');
assert(source.includes('active_index=active[0]?0:selected'),
  'deleting the active ID must fall back to the first profile');
assert(!source.includes('s_selected=(s_selected+1)%s_profile_count'),
  'the profile control must open a chooser instead of cycling profiles');

assert(source.includes('#define WAYPOINT_NAME_BYTES 120'),
  'dictated waypoint names must retain single-message headroom');
assert(source.includes('static char s_waypoint_name[WAYPOINT_NAME_SIZE]'),
  'the dictated waypoint buffer must use static storage');
assert(source.includes('PBL_IF_MICROPHONE_ELSE(true,false)'),
  'the dictated menu entry must be gated by microphone capability');
assert(source.includes('tr("Add waypoint + note","Wegpunkt + Notiz")'),
  'the microphone action must be localized');
assert(source.includes('dictation_session_enable_confirmation(s_dictation_session,true)'),
  'dictated text must be shown for confirmation before use');
assert(source.includes('dictation_session_enable_error_dialogs(s_dictation_session,true)'),
  'Pebble dictation UI must retain its retry-capable error dialogs');
assert(source.includes('dict_write_cstring(it,MESSAGE_KEY_WAYPOINT_NAME,s_waypoint_name)'),
  'the accepted transcription must be sent in the waypoint-name field');
assert(source.includes('app_timer_register(1,send_dictated_waypoint,NULL)'),
  'sending must wait until Pebble has closed the system confirmation overlay');
assert(source.includes('window_stack_remove(s_controls_window,true)'),
  'command completion must remove the controls window even after a system overlay');
assert(source.includes('if(s_dictation_session)dictation_session_destroy(s_dictation_session)'),
  'the dictation session must be released during shutdown');
assert(source.includes('status!=DictationSessionStatusFailureTranscriptionRejected'),
  'user cancellation must return silently without creating a waypoint');
