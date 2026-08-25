'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'src/c/main.c'), 'utf8');
const config = fs.readFileSync(path.join(root, 'src/c/watch_config.c'), 'utf8');
const header = fs.readFileSync(path.join(root, 'src/c/watch_config.h'), 'utf8');

for (const declaration of [
  'static char s_chunks[CONFIG_SIZE]',
  'static char s_config_work[CONFIG_SIZE]',
  'static char s_profile_chunks[WATCH_PROFILE_TRANSFER_BUFFER_SIZE]',
  'static Profile s_profiles[MAX_PROFILES]',
]) assert(source.includes(declaration), `${declaration} must remain out of function-local stack storage`);

assert(header.includes('#define WATCH_MAX_PROFILES 4'));
assert(source.includes('MSG_RECORDING_CONTEXT = 10'));
assert(source.includes('MSG_REQUEST_RUNTIME_CONFIG = 11'));
assert(source.includes('MESSAGE_KEY_CONFIG_FINGERPRINT_A'));
assert(source.includes('MESSAGE_KEY_CONFIG_FINGERPRINT_B'));
assert(source.includes('strcmp(s_parsed_config.locus_id, s_current_locus_id)'));
assert(source.includes('s_parsed_config.fingerprint_a != s_transfer_fingerprint_a'));
assert(!config.includes('watch_profile_names_equal(output->profiles[i].name, profile->name)'));
assert(config.includes('strcmp(output->profiles[i].id, profile->id) == 0'));

assert(source.includes('i18n_text(I18N_START_IN_LOCUS)'));
assert(source.includes('i18n_text(I18N_LOCUS_UNAVAILABLE_INSTRUCTION)'));
assert(source.includes('s_uptime_seconds >= 10 ? i18n_text(I18N_NO_BRIDGE_RESPONSE) : NULL'));
assert(source.includes('!s_snapshot_received && s_uptime_seconds == 10'));
assert(source.includes('i18n_text(I18N_CONNECTING)'));
assert(source.includes('i18n_text(I18N_PREPARING_PROFILE)'));
assert(source.includes('i18n_text(I18N_OPEN_WATCH_SETTINGS)'));
assert(source.includes('s_uptime_seconds - s_context_started >= 15'));
assert(source.includes('s_uptime_seconds - s_last_runtime_config_request >= 60'));
assert(source.includes('switch_page(-1)') && source.includes('switch_page(1)'));
assert(source.includes('(s_selected + direction + s_profile_count) % s_profile_count'));
assert(source.includes(String.raw`"%s \xc2\xb7 %d/%d%s"`));
assert(source.includes('show_notice(s_profiles[s_selected].name, 2)'));
assert(config.includes('strcmp(active_id, output->profiles[i].id) == 0'));

assert(source.includes('if (!s_activity_ready ||') && source.includes('return;\n  if (!rebuild_menu())'));
assert(!source.includes('send_command(s_snapshot.state == STATE_STOPPED ? CMD_START'));
assert(!source.includes('I18N_START_RECORDING) :'));
assert(!source.includes('.callback = profile_selected'));
assert(source.includes('.title = PBL_IF_MICROPHONE_ELSE(i18n_text(I18N_WAYPOINTS)'));
assert(source.includes('I18N_QUICK_WAYPOINT') && source.includes('I18N_DICTATED_WAYPOINT'));
assert(source.includes('PBL_IF_MICROPHONE_ELSE(waypoints_selected, waypoint_selected)'));

const tickStart = source.indexOf('static void tick(');
const tickEnd = source.indexOf('\n}', tickStart);
const tick = source.slice(tickStart, tickEnd);
assert(!/\brender\(\);\s*$/.test(tick), 'tick rendering must stay conditional');
assert(source.includes('app_message_open(512, 512)'));
assert(source.includes('PROFILE_TRANSFER_TIMEOUT_SECONDS'));
assert(source.includes('CONFIG_TRANSFER_TIMEOUT_SECONDS'));
