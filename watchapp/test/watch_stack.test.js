'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const source = fs.readdirSync(path.join(__dirname, '../src/c'))
  .filter(name => name.endsWith('.c'))
  .sort()
  .map(name => fs.readFileSync(path.join(__dirname, '../src/c', name), 'utf8'))
  .join('\n');
const configHeader = fs.readFileSync(path.join(__dirname, '../src/c/watch_config.h'), 'utf8');
const watchConfigSource = configHeader +
  fs.readFileSync(path.join(__dirname, '../src/c/watch_config.c'), 'utf8');
const stateHeader = fs.readFileSync(path.join(__dirname, '../src/c/watch_state.h'), 'utf8');
const stateSource = fs.readFileSync(path.join(__dirname, '../src/c/watch_state.c'), 'utf8');
const constantExpressions = {};
for (const match of `${configHeader}\n${stateHeader}\n${source}`.matchAll(/^#define\s+([A-Z][A-Z0-9_]*)\s+([^\r\n/]+?)\s*$/gm)) {
  constantExpressions[match[1]] = match[2];
}

function constantValue(expression, resolving = new Set()) {
  let unresolved = false;
  const expanded = String(expression).replace(/\b[A-Z][A-Z0-9_]*\b/g, name => {
    if (!Object.prototype.hasOwnProperty.call(constantExpressions, name) || resolving.has(name)) {
      unresolved = true;
      return name;
    }
    const nested = new Set(resolving);
    nested.add(name);
    const value = constantValue(constantExpressions[name], nested);
    if (value === null) unresolved = true;
    return value === null ? name : String(value);
  });
  if (unresolved || !/^[\d\s()+\-*/%<>&|]+$/.test(expanded)) return null;
  try {
    const value = Function(`"use strict"; return (${expanded});`)();
    return Number.isSafeInteger(value) && value >= 0 ? value : null;
  } catch (_) {
    return null;
  }
}

function functionBodies(text) {
  const bodies = [];
  const declaration = /^(?:static\s+)?[\w *]+\s+(\w+)\s*\([^;{}]*\)\s*\{/gm;
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
function splitDeclarators(value) {
  const result = [];
  let start = 0, depth = 0;
  for (let i = 0; i < value.length; i++) {
    if (value[i] === '[') depth++;
    if (value[i] === ']') depth--;
    if (value[i] === ',' && depth === 0) {
      result.push(value.slice(start, i));
      start = i + 1;
    }
  }
  result.push(value.slice(start));
  return result;
}

function stackViolations(text) {
  const oversized = [];
  for (const fn of functionBodies(text)) {
    let explicitArrayBytes = 0;
    const declarations = /\b(char|bool|u?int(?:8|16|32)?_t|int)\s+([^;]+);/g;
    let declaration;
    while ((declaration = declarations.exec(fn.body))) {
      for (const declarator of splitDeclarators(declaration[2])) {
        const dimensions = [...declarator.split('=', 1)[0].matchAll(/\[([^\]]+)\]/g)];
        if (!dimensions.length) continue;
        let elements = 1;
        for (const dimension of dimensions) {
          const length = constantValue(dimension[1]);
          if (length === null) {
            oversized.push(`${fn.name}: local array has an unresolved bound (${dimension[1].trim()})`);
            elements = 0;
            break;
          }
          elements *= length;
        }
        const bytes = primitiveBytes[declaration[1]] * elements;
        explicitArrayBytes += bytes;
        if (bytes > 1024) oversized.push(`${fn.name}: ${bytes}-byte local array`);
      }
    }
    if (explicitArrayBytes > 2048) oversized.push(`${fn.name}: at least ${explicitArrayBytes} bytes of local arrays`);
  }
  return oversized;
}

const oversized = stackViolations(`${source}\n${stateSource}`);
assert.deepStrictEqual(
  oversized,
  [],
  `Pebble stack budget exceeded; large transfer/configuration buffers must use static storage:\n${oversized.join('\n')}`,
);

const formerRegression = '#define CONFIG_SIZE 4096\nstatic void init(void){char raw[CONFIG_SIZE], work[CONFIG_SIZE];}';
assert.strictEqual(stackViolations(formerRegression).length, 3, 'guard must catch the former startup crash');
const multidimensionalRegression = 'static void init(void){char hidden[64][64];}';
assert.deepStrictEqual(stackViolations(multidimensionalRegression), [
  'init: 4096-byte local array',
  'init: at least 4096 bytes of local arrays',
], 'guard must account for every dimension of a local array');

// These are the buffers involved in startup and configuration application. Keep this explicit
// regression alongside the generic budget check so a future parser refactor cannot hide them.
for (const name of ['s_chunks', 's_pending_chunks', 's_config_work']) {
  assert(
    new RegExp(`^static char ${name}\\[CONFIG_SIZE\\]`, 'm').test(source),
    `${name} must remain in static storage, not on Pebble's call stack`,
  );
}
assert(/^static char s_profile_chunks\[WATCH_PROFILE_TRANSFER_BUFFER_SIZE\]/m.test(source),
  'the profile-list receive and relay buffer must remain in static storage');
assert.strictEqual(
  constantValue('WATCH_PROFILE_TRANSFER_BUFFER_SIZE'),
  constantValue('WATCH_PROFILE_MAX_CHUNKS') * constantValue('WATCH_PROFILE_CHUNK_BYTES') + 1,
  'the shared profile buffer must provide one fixed slot per possible chunk plus a terminator');
assert(/^static WatchProfileTransfer s_profile_transfer;$/m.test(source) &&
    !/char parts\[/.test(stateHeader) &&
    stateHeader.includes('#define WATCH_PROFILE_TRANSFER_BUFFER_SIZE'),
  'profile reassembly metadata must share the one static relay buffer instead of duplicating 8 KiB');

const bodies = Object.fromEntries(functionBodies(source).map(fn => [fn.name, fn.body.replace(/\s+/g, '')]));
const configBodies = Object.fromEntries(
  functionBodies(watchConfigSource).map(fn => [fn.name, fn.body.replace(/\s+/g, '')]),
);
const stateBodies = Object.fromEntries(
  functionBodies(stateSource).map(fn => [fn.name, fn.body.replace(/\s+/g, '')]),
);
assert(bodies.init, 'init must remain statically inspectable');
const configTransferInitPosition = bodies.init.indexOf(
  'watch_config_transfer_initialize(&s_config_transfer);');
const profileResetPosition = bodies.init.indexOf(
  'watch_profile_transfer_initialize(&s_profile_transfer);');
const inboxRegistrationPosition = bodies.init.indexOf('app_message_register_inbox_received(inbox);');
assert(configTransferInitPosition >= 0 && profileResetPosition > configTransferInitPosition &&
    inboxRegistrationPosition > profileResetPosition,
  'both transfer states must establish their sentinels and empty serial floors before inbound messages');
const snapshotPosition = bodies.init.indexOf('control_enqueue(MSG_REQUEST_SNAPSHOT');
const profilesPosition = bodies.init.indexOf('control_enqueue(MSG_REQUEST_PROFILE_LIST');
const startupPumpPosition = bodies.init.indexOf('send_next()');
assert(snapshotPosition >= 0 && profilesPosition > snapshotPosition && startupPumpPosition > profilesPosition,
  'startup must queue snapshot and profile requests before starting the single-flight scheduler');

assert(bodies.send_next.includes('if(s_outbox_busy||s_retry_timer)return;'),
  'the scheduler must not overlap an in-flight send or a scheduled retry');
const controlPosition = bodies.send_next.indexOf('if(s_control_count)');
const relayPosition = bodies.send_next.indexOf('elseif(s_relay_index<s_relay_count)');
const heartRatePosition = bodies.send_next.indexOf('elseif(s_hr_pending||s_hr_prepared)');
assert(controlPosition >= 0 && relayPosition > controlPosition && heartRatePosition > relayPosition,
  'the scheduler must prioritize controls, then profile relays, then conflated heart rate');
assert(bodies.outbox_sent.endsWith('send_next();'),
  'a successful callback must advance exactly one queued frame and pump the scheduler');
assert(bodies.outbox_failed.includes('handle_send_failure(s_inflight_kind,reason);'),
  'a failed callback must retry the current outbound kind');
assert(bodies.handle_send_failure.includes('if(*attempts>=MAX_SEND_ATTEMPTS)') &&
    bodies.handle_send_failure.includes('app_timer_register(delay,retry_send,NULL)'),
  'send failures must use a bounded delayed retry before dropping one outbound item');
const maxSendAttempts = constantValue('MAX_SEND_ATTEMPTS');
assert(maxSendAttempts >= 2 && maxSendAttempts <= 5,
  'the retry bound must be finite and small enough for a watch/phone failure');

assert.strictEqual(constantValue('PERSIST_ACTIVE_ID'), 103,
  'the active profile ID must have persistent storage');
assert.strictEqual(constantValue('PERSIST_RELAY_TRANSFER_COUNTER'), 105,
  'profile relays must have a dedicated durable transfer serial');
assert(bodies.write_active_id.includes('constchar*id=s_profiles[s_selected].id;') &&
    bodies.write_active_id.includes('persist_write_string(PERSIST_ACTIVE_ID,id)'),
  'choosing a profile must persist its stable ID');
assert(bodies.profile_load.includes('.subtitle=i==s_selected?tr("Active","Aktiv"):NULL'),
  'the chooser must mark the active profile');
assert(bodies.profile_load.includes('.num_items=s_profile_count'),
  'the chooser must list every configured profile');
assert(bodies.profile_choice_selected.includes(
  's_snapshot.state==STATE_RECORDING||s_snapshot.state==STATE_PAUSED'),
  'profile selection must remain locked while recording or paused');
assert(watchConfigSource.replace(/\s+/g, '').includes(
  'output->selected=active_index>=0?active_index:((active_id&&*active_id)?0:(int)selected)'),
  'deleting the active ID must fall back to the first profile');
assert(!source.includes('s_selected=(s_selected+1)%s_profile_count'),
  'the profile control must open a chooser instead of cycling profiles');
assert(bodies.default_profiles.includes('s_profile_count=2;'),
  'fresh watch defaults must contain only walking and cycling in every language');
assert(bodies.default_profiles.includes('tr("Walking","Gehen")'),
  'the walking default must match the localized Locus default profile name');
assert(bodies.default_profiles.includes('tr("Cycling","Radfahren")'),
  'the German cycling default must match the Locus default profile name');

assert.strictEqual(constantValue('WAYPOINT_NAME_BYTES'), 120,
  'dictated waypoint names must retain single-message headroom');
assert(source.includes('static char s_waypoint_name[WAYPOINT_NAME_SIZE]'),
  'the dictated waypoint buffer must use static storage');
assert(bodies.rebuild_menu.includes('PBL_IF_MICROPHONE_ELSE(true,false)'),
  'the dictated menu entry must be gated by microphone capability');
assert(bodies.rebuild_menu.includes('tr("Addwaypoint+note","Wegpunkt+Notiz")'),
  'the microphone action must be localized');
assert(bodies.dictated_waypoint_selected.includes(
  'dictation_session_enable_confirmation(s_dictation_session,true)'),
  'dictated text must be shown for confirmation before use');
assert(bodies.dictated_waypoint_selected.includes(
  'dictation_session_enable_error_dialogs(s_dictation_session,true)'),
  'Pebble dictation UI must retain its retry-capable error dialogs');
assert(bodies.send_control_packet.includes(
  'MESSAGE_KEY_LOCUS_PROFILE_NAME:MESSAGE_KEY_WAYPOINT_NAME') &&
    bodies.send_command.includes('text=s_waypoint_name'),
  'the accepted transcription must be sent in the waypoint-name field');
assert(bodies.dictation_callback.includes('app_timer_register(1,send_dictated_waypoint,NULL)'),
  'sending must wait until Pebble has closed the system confirmation overlay');
assert(bodies.close_controls.includes('window_stack_remove(s_controls_window,true)'),
  'command completion must remove the controls window even after a system overlay');
assert(bodies.deinit.includes('dictation_session_destroy(s_dictation_session)'),
  'the dictation session must be released during shutdown');
assert(bodies.dictation_callback.includes('status!=DictationSessionStatusFailureTranscriptionRejected'),
  'user cancellation must return silently without creating a waypoint');
assert(bodies.dictation_callback.includes('watch_waypoint_name_valid(transcription)'),
  'dictation must use the shared strict UTF-8 and Unicode-whitespace validator');

assert(bodies.health_event.includes('HealthMetricHeartRateRawBPM'),
  'HR injection must use the newest raw BPM');
assert(bodies.health_event.includes('bpm<25||bpm>250'), 'raw BPM must be range checked');
assert(bodies.health_event.includes('s_hr_pending=true'),
  'HR messages must be conflated in one pending slot');
assert(bodies.fresh_recording_snapshot.includes('s_snapshot_received&&') &&
    bodies.fresh_recording_snapshot.includes('s_snapshot_age<=SNAPSHOT_STALE_SECONDS') &&
    bodies.fresh_recording_snapshot.includes('s_snapshot.state==STATE_RECORDING'),
  'HR collection must require a fresh recording snapshot');
assert(bodies.update_health_subscription.includes(
  's_watch_hr_to_locus&&fresh_recording_snapshot()'),
  'HealthService subscription must use the complete snapshot-freshness gate');
assert(bodies.stop_health.includes('s_send_attempts[OUTBOUND_HEART_RATE]=0;'),
  'stopping HealthService must discard retry history for obsolete heart-rate work');
assert(bodies.handle_send_failure.includes(
  'if(kind==OUTBOUND_HEART_RATE&&!s_hr_prepared){*attempts=0;send_next();return;}'),
  'a late failure for an invalidated heart-rate packet must not consume a future retry');
assert(bodies.tick.includes('s_snapshot_age==SNAPSHOT_STALE_SECONDS+1') &&
    bodies.tick.includes('update_health_subscription();'),
  'the freshness transition must immediately reevaluate and stop HealthService');
assert(bodies.stop_health.includes('health_service_set_heart_rate_sample_period(0)'),
  'shutdown must restore the automatic sample period');
assert(bodies.update_health_subscription.includes(
  'if(health_service_set_heart_rate_sample_period(s_heart_rate_interval))') &&
    bodies.update_health_subscription.includes('health_service_events_unsubscribe()') &&
    bodies.update_health_subscription.includes('s_health_subscribed=false;'),
  'a failed HR sample-period request must roll back the event subscription');
assert(bodies.deinit.startsWith('stop_health();'),
  'watchapp exit must stop HealthService immediately');

assert(source.includes('MSG_CONFIG_RESULT = 9'), 'configuration completion must have a distinct message type');
assert(bodies.send_control_packet.includes('MESSAGE_KEY_TRANSFER_ID,message->transfer_id') &&
    bodies.send_control_packet.includes('MESSAGE_KEY_RESULT,message->result'),
  'configuration results must carry their correlated transfer ID and application result');
assert(bodies.control_enqueue.includes(
  'CONTROL_QUEUE_SIZE-(s_config_result_slot_reserved?1:0)'),
  'normal controls must preserve the active configuration result reservation');
assert(bodies.control_enqueue_config_result.includes('s_config_result_slot_reserved=false;'),
  'completing a configuration must atomically consume its reserved result slot');
assert(!source.includes('MSG_PROFILE_LIST_RESULT'),
  'profile persistence must not add a production acknowledgement used only by automation');
assert(bodies.accept_config_chunk.includes('result=RESULT_INVALID_CONFIG') &&
    bodies.accept_config_chunk.includes('result=RESULT_CONFIG_QUEUED') &&
    bodies.accept_config_chunk.includes('result=RESULT_STORAGE_FAILED') &&
    bodies.accept_config_chunk.includes('s_snapshot_age<=SNAPSHOT_STALE_SECONDS'),
  'configuration ACKs must distinguish validation/storage/queued state and require a fresh stop');
assert(bodies.cleanup_pending_config.includes(
  'if(persistent_blob_delete(&s_pending_config_blob)){s_pending_cleanup_required=false;returntrue;}') &&
    bodies.cleanup_pending_config.includes('s_pending_cleanup_required=true;'),
  'pending cleanup must stay fail-closed until every pending key is removed');
const pendingPreparation = bodies.prepare_pending_config_for_direct_apply;
const pendingRead = pendingPreparation.indexOf(
  'persistent_blob_read(&s_pending_config_blob,s_pending_chunks,sizeof(s_pending_chunks))');
const pendingParse = pendingPreparation.indexOf(
  'parse_config_buffer(s_config_work,&s_parsed_config)');
const pendingPromotion = pendingPreparation.indexOf('store_active_config(s_pending_chunks)');
const pendingInstall = pendingPreparation.indexOf('install_config(&s_parsed_config,true)');
assert(pendingRead >= 0 && pendingParse > pendingRead && pendingPromotion > pendingParse &&
    pendingInstall > pendingPromotion,
  'a readable queued baseline must be validated, promoted, and installed as recovery state');
assert(pendingPreparation.includes(
  'if(s_pending_cleanup_required){returncleanup_pending_config(') &&
    pendingPreparation.includes(
      'returncleanup_pending_config("Unreadablependingconfigurationcleanupfailed")') &&
    pendingPreparation.includes(
      'returncleanup_pending_config("Invalidpendingconfigurationcleanupfailed")'),
  'known-stale, unreadable, and invalid pending state must be cleared before direct replacement');
assert(!bodies.apply_pending_config_if_stopped.includes(
  'persistent_blob_delete(&s_pending_config_blob)') &&
    (bodies.apply_pending_config_if_stopped.match(/cleanup_pending_config\(/g) || []).length === 4,
  'deferred apply must use the same fail-closed cleanup state for stale, corrupt, and applied data');
const directPreparation = bodies.accept_config_chunk.indexOf(
  'prepare_pending_config_for_direct_apply()');
const directReplacement = bodies.accept_config_chunk.indexOf('store_active_config(s_chunks)');
const directCleanup = bodies.accept_config_chunk.indexOf(
  'cleanup_pending_config("Replacedconfigurationbutpendingcleanupfailed")');
assert(directPreparation >= 0 && directReplacement > directPreparation &&
    directCleanup > directReplacement &&
    !bodies.accept_config_chunk.includes('persistent_blob_delete(&s_pending_config_blob)'),
  'direct apply must retain queued recovery until the replacement is stored');
assert(bodies.accept_config_chunk.endsWith('reset_config_transfer();send_next();'),
  'a completed transfer must reset before a same-ID application retry can start');
assert(bodies.accept_config_chunk.includes(
  'if(id==s_config_transfer.id)reset_config_transfer();return;') &&
    bodies.accept_config_chunk.includes(
      'if(index==0&&(s_config_transfer.id<0||id!=s_config_transfer.id))'),
  'malformed unrelated config frames must not cancel the active transfer');
const configSerialPreflight = bodies.accept_config_chunk.indexOf(
  'watch_config_transfer_may_start(&s_config_transfer,id)');
const configSlotReservation = bodies.accept_config_chunk.indexOf(
  'begin_config_transfer()', configSerialPreflight);
assert(configSerialPreflight >= 0 && configSlotReservation > configSerialPreflight,
  'an older config chunk zero must be rejected before it resets state or reserves a result slot');
assert(bodies.accept_config_chunk.includes(
  'if(generation==1&&!s_config_durable_generation_seen){if(index!=0||!begin_config_transfer())return;watch_config_transfer_initialize(&s_config_transfer);s_config_durable_generation_seen=true;}'),
  'the first fully valid marked config chunk zero must atomically replace the legacy floor');
assert(bodies.begin_config_transfer.indexOf('s_control_count>=CONTROL_QUEUE_SIZE') <
    bodies.begin_config_transfer.indexOf('reset_config_transfer();'),
  'result-slot exhaustion must not erase an otherwise viable active transfer');
assert(configBodies.watch_config_transfer_accept.includes(
  'completed_first_chunk_matches(&transfer->completed_identity,count,data,length)') &&
    configBodies.watch_config_transfer_accept.includes(
      'completed_payload_matches(&transfer->completed_identity,buffer,transfer->length)') &&
    configBodies.watch_config_transfer_accept.includes(
      'remember_completed_payload(transfer,buffer);'),
  'same-ID completed config retries must match the retained wire identity before reapplication');
assert(bodies.accept_profile_chunk.includes('if(id==s_profile_transfer.id){') &&
    bodies.accept_profile_chunk.includes('reset_profile_transfer();'),
  'malformed unrelated profile frames must not cancel the active transfer');
const profileGenerationGate = bodies.accept_profile_chunk.indexOf(
  'if(generation<0||(s_profile_durable_generation_seen&&generation!=1))return;');
const profileOwnershipGuard = bodies.accept_profile_chunk.indexOf(
  'if(s_relay_count||s_profile_pending_ready){s_request_profiles_after_relay=true;return;}');
assert(profileGenerationGate >= 0 && profileOwnershipGuard > profileGenerationGate,
  'inbound profile chunks must never write the shared buffer while a relay owns it');
assert(bodies.accept_profile_chunk.includes(
  'if(generation==1&&!s_profile_durable_generation_seen){if(index!=0)return;watch_profile_transfer_initialize(&s_profile_transfer);s_profile_durable_generation_seen=true;}'),
  'the first fully valid marked profile chunk zero must atomically replace the legacy floor');
const profileRequestBusyGuard = bodies.inbox.indexOf(
  'elseif(s_profile_transfer.id>=0||s_profile_pending_ready)');
const profileCacheRead = bodies.inbox.indexOf(
  'persistent_blob_read(&s_profile_list_blob,s_profile_chunks,sizeof(s_profile_chunks))');
assert(profileRequestBusyGuard >= 0 && profileCacheRead > profileRequestBusyGuard &&
    bodies.finish_relay.includes('enqueue_deferred_profile_request();'),
  'PKJS profile requests must defer cache reads and coalesce one refresh while the shared buffer is busy');
assert(bodies.accept_profile_chunk.includes('watch_profile_transfer_accept(') &&
    bodies.accept_profile_chunk.includes('outcome==WATCH_PROFILE_TRANSFER_COMPLETE'),
  'profile reassembly must use the unit-tested state machine and publish only complete transfers');
assert(stateBodies.watch_profile_transfer_accept.includes('if(index==0){if(id==transfer->id){') &&
    stateBodies.watch_profile_transfer_accept.includes(
      'if(count!=transfer->chunk_count||result!=transfer->result||!chunk_matches(') &&
    stateBodies.watch_profile_transfer_accept.includes(
      'chunk_matches(transfer,buffer,0,data,length)') &&
    stateBodies.watch_profile_transfer_accept.includes('returnWATCH_PROFILE_TRANSFER_DUPLICATE;'),
  'an identical same-ID chunk zero must preserve the active transfer');
assert(stateBodies.watch_profile_transfer_accept.includes(
  'watch_profile_transfer_reset(transfer);returnWATCH_PROFILE_TRANSFER_INVALID;'),
  'a conflicting same-ID chunk must invalidate the partial transfer');
assert(stateBodies.watch_profile_transfer_accept.includes(
  'if(!watch_transfer_serial_may_start(&transfer->serial,id,false))') &&
    stateBodies.watch_profile_transfer_accept.includes(
      'watch_transfer_serial_completed(&transfer->serial,id);'),
  'profile reassembly must preserve a completed serial floor and reject older chunk-zero restarts');
assert(stateBodies.watch_transfer_serial_is_newer.includes(
  'distance>0&&distance<WATCH_TRANSFER_SERIAL_HALF_RANGE'),
  '31-bit serial ordering must reject equality and the ambiguous half range');
const relayReservation = bodies.relay_profile_list.indexOf(
  'watch_transfer_serial_reserve_persistent(');
const relayPublish = bodies.relay_profile_list.indexOf('s_relay_count=count;');
assert(relayReservation >= 0 && relayPublish > relayReservation &&
    bodies.relay_profile_list.includes('PERSIST_RELAY_TRANSFER_COUNTER,0,&transfer_id'),
  'a profile relay must durably reserve its serial before making any chunk sendable');
assert(bodies.send_relay_packet.includes(
  'MESSAGE_KEY_TRANSFER_GENERATION,DURABLE_TRANSFER_GENERATION'),
  'every durable watch relay frame must carry its transfer-generation marker');
assert(bodies.relay_pending_profile_list.includes(
  'persistent_blob_write(&s_profile_list_blob,s_profile_chunks,length)'),
  'a complete empty profile list must atomically replace persisted stale data');
assert(bodies.relay_pending_profile_list.includes('watch_profile_transfer_join('),
  'profile relay must compact the shared fixed-slot receive buffer before validation and sending');
assert(bodies.relay_pending_profile_list.includes('s_relay_result=s_profile_pending_result;') &&
    bodies.send_relay_packet.includes('MESSAGE_KEY_RESULT,s_relay_result'),
  'a relay must keep the completed result immutable across all outgoing chunks');
assert(bodies.tick.includes('PROFILE_TRANSFER_TIMEOUT_SECONDS') &&
    bodies.tick.includes('reset_profile_transfer();'),
  'partial profile transfers must expire instead of retaining mixed state forever');
const failureReset = bodies.handle_send_failure.indexOf('*attempts=0;');
const failureDrop = bodies.handle_send_failure.indexOf('drop_outbound(kind);',failureReset);
const failurePump = bodies.handle_send_failure.indexOf('send_next();',failureDrop);
assert(failureReset >= 0 && failureDrop > failureReset && failurePump > failureDrop,
  'retry exhaustion must reset counters, drop exactly one item, then pump the next item');
assert(bodies.accept_command_result.indexOf('result>RESULT_INVALID_WAYPOINT_NAME') <
    bodies.accept_command_result.indexOf('command_record_find(command_id)'),
  'unknown command result codes must not consume a correlated command record');

const requiredSnapshotKeys = [
  'RECORDING_STATE', 'SAMPLE_EPOCH_SECONDS', 'ELAPSED_SECONDS', 'MOVING_SECONDS',
  'DISTANCE_METRES', 'MOVING_DISTANCE_METRES', 'CURRENT_SPEED_CMPS',
  'AVERAGE_SPEED_CMPS', 'MAX_SPEED_CMPS', 'ALTITUDE_DECIMETRES', 'ASCENT_DECIMETRES',
  'DESCENT_DECIMETRES', 'VERTICAL_SPEED_CMPS', 'SLOPE_TENTHS_PERCENT',
  'AVERAGE_HEART_RATE', 'MAX_HEART_RATE', 'CURRENT_HEART_RATE', 'AVERAGE_CADENCE',
  'MAX_CADENCE', 'AVERAGE_POWER', 'MAX_POWER', 'ENERGY_KCAL', 'UNIT_SYSTEM',
];
for (const key of requiredSnapshotKeys) {
  assert(bodies.app_message_snapshot.includes(`MESSAGE_KEY_${key}`),
    `snapshot parsing must require ${key}`);
}
const snapshotValidation = bodies.app_message_snapshot.indexOf(
  'if(unit_system!=0||!snapshot_values_valid(&snapshot))returnfalse;');
const snapshotPublish = bodies.app_message_snapshot.indexOf('*output=snapshot;');
assert(snapshotValidation >= 0 && snapshotPublish > snapshotValidation,
  'a complete metric snapshot must validate before being published to the caller');
assert(bodies.accept_snapshot.includes('Snapshotcandidate;') &&
    bodies.accept_snapshot.includes('if(!app_message_snapshot(iterator,&candidate))') &&
    bodies.accept_snapshot.includes('s_snapshot=candidate;'),
  'snapshot reception must parse into a temporary and replace state atomically');
assert(stateBodies.watch_snapshot_epoch_allowed.includes(
  'return!current_received||candidate_epoch>=current_epoch;'),
  'snapshot epochs must be nondecreasing for the complete watchapp lifetime');
const epochGuard = bodies.accept_snapshot.indexOf('if(!watch_snapshot_epoch_allowed(');
const snapshotInstall = bodies.accept_snapshot.indexOf('s_snapshot=candidate;');
const healthUpdate = bodies.accept_snapshot.indexOf('update_health_subscription();');
const pendingApply = bodies.accept_snapshot.indexOf('apply_pending_config_if_stopped();');
assert(epochGuard >= 0 && snapshotInstall > epochGuard && healthUpdate > snapshotInstall &&
    pendingApply > healthUpdate && !bodies.accept_snapshot.includes('s_snapshot_age<=SNAPSHOT_STALE_SECONDS'),
  'fresh and stale lower epochs must return before snapshot, HR, or pending-config side effects');
