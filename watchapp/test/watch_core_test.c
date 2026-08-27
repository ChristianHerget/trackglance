#include "persistent_blob.h"
#include "i18n.h"
#include "ui_metrics.h"
#include "watch_config.h"
#include "watch_state.h"
#include "watch_maintenance.h"
#include "watch_maintenance_timer.h"

#include <assert.h>
#include <setjmp.h>
#include <stdio.h>
#include <string.h>

#include <pebble.h>

#define STORE_KEYS 256

typedef struct {
  int register_calls;
  int reschedule_calls;
  int cancel_calls;
  uint32_t last_delay;
  bool register_succeeds;
  bool reschedule_succeeds;
  WatchMaintenanceTimerCallback callback;
  void *callback_context;
  int callback_calls;
  WatchMaintenanceTimer *timer;
} FakeTimerOperations;

static void *fake_timer_register(void *context, uint32_t delay_ms,
                                 WatchMaintenanceTimerCallback callback, void *callback_context) {
  FakeTimerOperations *fake = context;
  fake->register_calls++;
  fake->last_delay = delay_ms;
  fake->callback = callback;
  fake->callback_context = callback_context;
  return fake->register_succeeds ? fake : NULL;
}

static bool fake_timer_reschedule(void *context, void *timer, uint32_t delay_ms) {
  FakeTimerOperations *fake = context;
  assert(timer == fake);
  fake->reschedule_calls++;
  fake->last_delay = delay_ms;
  return fake->reschedule_succeeds;
}

static void fake_timer_cancel(void *context, void *timer) {
  FakeTimerOperations *fake = context;
  assert(timer == fake);
  fake->cancel_calls++;
}

static void fake_timer_callback(void *context) {
  FakeTimerOperations *fake = context;
  fake->callback_calls++;
  if (fake->callback_calls == 1 && fake->timer) {
    assert(watch_maintenance_timer_schedule(fake->timer, true, 30, 300));
  }
}

static void test_watch_maintenance_timer(void) {
  FakeTimerOperations fake = {
      .register_succeeds = true,
      .reschedule_succeeds = true,
  };
  WatchMaintenanceTimer timer;
  fake.timer = &timer;
  watch_maintenance_timer_initialize(&timer,
                                     (WatchMaintenanceTimerOperations){
                                         .register_timer = fake_timer_register,
                                         .reschedule_timer = fake_timer_reschedule,
                                         .cancel_timer = fake_timer_cancel,
                                     },
                                     &fake, fake_timer_callback, &fake);

  assert(watch_maintenance_timer_schedule(&timer, true, 10, 100));
  assert(fake.register_calls == 1 && fake.last_delay == 100);
  assert(watch_maintenance_timer_schedule(&timer, true, 10, 90));
  assert(fake.register_calls == 1 && fake.reschedule_calls == 0);

  assert(watch_maintenance_timer_schedule(&timer, true, 20, 200));
  assert(fake.reschedule_calls == 1 && fake.last_delay == 200);
  assert(watch_maintenance_timer_schedule(&timer, false, 0, 0));
  assert(fake.cancel_calls == 1);
  assert(watch_maintenance_timer_schedule(&timer, false, 0, 0));
  assert(fake.cancel_calls == 1);

  assert(watch_maintenance_timer_schedule(&timer, true, 25, 250));
  assert(fake.register_calls == 2);
  fake.callback(fake.callback_context);
  assert(fake.callback_calls == 1);
  assert(fake.register_calls == 3 && fake.reschedule_calls == 1);

  fake.reschedule_succeeds = false;
  assert(watch_maintenance_timer_schedule(&timer, true, 40, 400));
  assert(fake.reschedule_calls == 2 && fake.cancel_calls == 2 && fake.register_calls == 4);

  fake.register_succeeds = false;
  assert(!watch_maintenance_timer_schedule(&timer, true, 50, 500));
  assert(fake.reschedule_calls == 3 && fake.cancel_calls == 3 && fake.register_calls == 5);
  fake.register_succeeds = true;
  assert(watch_maintenance_timer_schedule(&timer, true, 50, 500));
  assert(fake.register_calls == 6);
  watch_maintenance_timer_cancel(&timer);
  assert(fake.cancel_calls == 4);
}
static void test_watch_maintenance_planner(void) {
  assert(watch_maintenance_should_schedule_reconciliation(true, true, false));
  assert(!watch_maintenance_should_schedule_reconciliation(true, true, true));
  assert(!watch_maintenance_should_schedule_reconciliation(false, true, false));
  assert(!watch_maintenance_should_schedule_reconciliation(true, false, false));

  WatchMaintenanceDeadlines deadlines = {0};
  deadlines.active = WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_NO_BRIDGE) |
                     WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_STALE);
  deadlines.deadlines[WATCH_MAINTENANCE_NO_BRIDGE] = 110;
  deadlines.deadlines[WATCH_MAINTENANCE_STALE] = 131;

  WatchMaintenancePlan plan = watch_maintenance_plan(
      &deadlines, (WatchMaintenanceClock){.seconds = 100, .milliseconds = 250});
  assert(plan.due == 0);
  assert(plan.has_next && plan.next_deadline == 110 && plan.delay_ms == 9750);

  plan = watch_maintenance_plan(&deadlines,
                                (WatchMaintenanceClock){.seconds = 110, .milliseconds = 0});
  assert(plan.due == WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_NO_BRIDGE));
  assert(plan.has_next && plan.next_deadline == 131 && plan.delay_ms == 21000);

  deadlines.deadlines[WATCH_MAINTENANCE_STALE] = 110;
  plan = watch_maintenance_plan(&deadlines,
                                (WatchMaintenanceClock){.seconds = 110, .milliseconds = 999});
  assert(plan.due == deadlines.active);
  assert(!plan.has_next && plan.delay_ms == 0);

  deadlines.active = WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_COMMAND);
  deadlines.deadlines[WATCH_MAINTENANCE_COMMAND] = 5;
  plan = watch_maintenance_plan(
      &deadlines, (WatchMaintenanceClock){.seconds = UINT32_MAX - 2, .milliseconds = 500});
  assert(!plan.due && plan.has_next && plan.delay_ms == 7500);
  assert(!watch_maintenance_reached(UINT32_MAX - 2, 5));
  assert(watch_maintenance_reached(5, 5));

  deadlines.active = 0;
  plan =
      watch_maintenance_plan(&deadlines, (WatchMaintenanceClock){.seconds = 1, .milliseconds = 0});
  assert(!plan.due && !plan.has_next);

  deadlines.active = WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_RECONCILIATION);
  deadlines.deadlines[WATCH_MAINTENANCE_RECONCILIATION] = 61;
  plan = watch_maintenance_plan(&deadlines,
                                (WatchMaintenanceClock){.seconds = 60, .milliseconds = 999});
  assert(!plan.due && plan.has_next && plan.delay_ms == 1);

  plan =
      watch_maintenance_plan(&deadlines, (WatchMaintenanceClock){.seconds = 61, .milliseconds = 0});
  assert(plan.due == WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_RECONCILIATION));
  assert(!plan.has_next && plan.delay_ms == 0);
}

typedef struct {
  bool exists;
  size_t length;
  unsigned char data[PERSIST_DATA_MAX_LENGTH];
} StoredValue;

static StoredValue s_store[STORE_KEYS];
static int s_fail_write_key = -1;
static int s_torn_write_key = -1;
static size_t s_torn_write_bytes;
static int s_fail_delete_key = -1;
static int s_crash_before_delete = -1;
static int s_delete_calls;
static int s_crash_before_mutation = -1;
static int s_mutation_calls;
static jmp_buf s_power_cut;
static size_t s_persist_max_size = STORE_KEYS * PERSIST_DATA_MAX_LENGTH;
static WatchProfileTransfer s_profile_transfer;
static char s_profile_transfer_buffer[WATCH_PROFILE_TRANSFER_BUFFER_SIZE];

static void test_ui_metrics(void) {
  UiMetricSnapshot snapshot = {
      .state = 1,
      .moving_time = 65,
      .elapsed = 90061,
      .distance = 123,
      .current_speed = 90,
      .current_pace = 301,
      .current_hr = 142,
      .altitude_format = FORMAT_M_0,
      .distance_format = FORMAT_KM_1,
      .moving_distance_format = FORMAT_MI_2,
      .current_speed_format = FORMAT_KPH_1,
      .average_speed_format = FORMAT_MPH_1,
      .max_speed_format = FORMAT_KNOT_0,
      .vertical_speed_format = FORMAT_MPS_2,
      .slope_format = FORMAT_PERCENT_0,
      .energy_format = FORMAT_KJ_0,
      .pace_format = FORMAT_PER_KM,
  };
  char output[24];
  assert(i18n_catalog_complete());
  assert(i18n_locale("de_DE") == I18N_LOCALE_DE);
  assert(i18n_locale("de-DE") == I18N_LOCALE_DE);
  assert(i18n_locale("en_US") == I18N_LOCALE_EN);
  assert(i18n_locale("fr_FR") == I18N_LOCALE_FR);
  assert(i18n_locale("es_ES") == I18N_LOCALE_ES);
  assert(i18n_locale("it_IT") == I18N_LOCALE_IT);
  assert(i18n_locale("pt_PT") == I18N_LOCALE_PT);
  assert(i18n_locale("zh_CN") == I18N_LOCALE_ZH_CN);
  assert(i18n_locale("zh_TW") == I18N_LOCALE_ZH_TW);
  i18n_set_locale(I18N_LOCALE_EN);
  assert(strcmp(ui_metric_label(METRIC_DISTANCE), "Distance") == 0);
  ui_metric_format(output, sizeof(output), METRIC_ELAPSED, &snapshot);
  assert(strcmp(output, "25:01") == 0);
  ui_metric_format(output, sizeof(output), METRIC_DISTANCE, &snapshot);
  assert(strcmp(output, "12.3 km") == 0);
  ui_metric_format(output, sizeof(output), METRIC_CURRENT_SPEED, &snapshot);
  assert(strcmp(output, "9.0 km/h") == 0);
  ui_metric_format(output, sizeof(output), METRIC_CURRENT_PACE, &snapshot);
  assert(strcmp(output, "5:01 /km") == 0);
  ui_metric_format(output, sizeof(output), METRIC_CURRENT_HR, &snapshot);
  assert(strcmp(output, "142 bpm") == 0);
  snapshot.current_pace = UI_METRIC_UNAVAILABLE;
  ui_metric_format(output, sizeof(output), METRIC_CURRENT_PACE, &snapshot);
  assert(strcmp(output, "—") == 0);
  snapshot.current_pace = 301;
  snapshot.distance = -123;
  i18n_set_locale(I18N_LOCALE_DE);
  assert(strcmp(ui_metric_label(METRIC_DISTANCE), "Strecke") == 0);
  ui_metric_format(output, sizeof(output), METRIC_DISTANCE, &snapshot);
  assert(strcmp(output, "-12,3 km") == 0);
  snapshot.distance = INT32_MIN + 1;
  snapshot.distance_format = FORMAT_MI_2;
  ui_metric_format(output, sizeof(output), METRIC_DISTANCE, &snapshot);
  assert(strcmp(output, "-21474836,47 mi") == 0);
  for (int format = 0; format < FORMAT_COUNT; format++)
    assert(ui_format_code_valid(format));
  assert(!ui_format_code_valid(-1));
  assert(!ui_format_code_valid(FORMAT_COUNT));
  snapshot.distance = 0;
  assert(ui_metric_snapshot_valid(&snapshot));
  snapshot.pace_format = FORMAT_KPH_1;
  assert(!ui_metric_snapshot_valid(&snapshot));
  snapshot.pace_format = FORMAT_PER_KM;
  snapshot.distance_format = FORMAT_KPH_1;
  assert(!ui_metric_snapshot_valid(&snapshot));
}

typedef struct {
  bool received;
  uint32_t epoch;
  uint16_t age;
  int health_updates;
  int pending_applies;
} SnapshotState;

static bool accept_snapshot_epoch(SnapshotState *state, uint32_t epoch, bool stopped) {
  if (!watch_snapshot_epoch_allowed(state->received, state->epoch, epoch)) return false;
  state->received = true;
  state->epoch = epoch;
  state->age = 0;
  state->health_updates++;
  if (stopped) state->pending_applies++;
  return true;
}

static void maybe_cut_power_before_mutation(void) {
  if (s_crash_before_mutation >= 0 && s_mutation_calls++ == s_crash_before_mutation) {
    longjmp(s_power_cut, 1);
  }
}

static StoredValue *stored(uint32_t key) {
  assert(key < STORE_KEYS);
  return &s_store[key];
}

bool persist_exists(uint32_t key) {
  return stored(key)->exists;
}

static size_t persist_used(void) {
  size_t total = 0;
  for (size_t i = 0; i < STORE_KEYS; i++) {
    if (s_store[i].exists) total += s_store[i].length;
  }
  return total;
}

size_t persist_get_max_size(void) {
  return s_persist_max_size;
}

int persist_get_size(uint32_t key) {
  StoredValue *value = stored(key);
  return value->exists ? (int)value->length : E_DOES_NOT_EXIST;
}

int persist_read_data(uint32_t key, void *buffer, size_t buffer_size) {
  StoredValue *value = stored(key);
  if (!value->exists) return E_DOES_NOT_EXIST;
  const size_t size = value->length < buffer_size ? value->length : buffer_size;
  memcpy(buffer, value->data, size);
  return (int)size;
}

int persist_read_string(uint32_t key, char *buffer, size_t buffer_size) {
  return persist_read_data(key, buffer, buffer_size);
}

int persist_write_data(uint32_t key, const void *data, size_t size) {
  maybe_cut_power_before_mutation();
  if ((int)key == s_fail_write_key || size > PERSIST_DATA_MAX_LENGTH) return E_ERROR;
  StoredValue *value = stored(key);
  const size_t written_size =
      (int)key == s_torn_write_key && s_torn_write_bytes < size ? s_torn_write_bytes : size;
  if (persist_used() - (value->exists ? value->length : 0) + written_size > s_persist_max_size) {
    return E_ERROR;
  }
  if (written_size) memcpy(value->data, data, written_size);
  value->length = written_size;
  value->exists = true;
  return (int)key == s_torn_write_key ? E_ERROR : (int)size;
}

int persist_write_string(uint32_t key, const char *cstring) {
  return persist_write_data(key, cstring, strlen(cstring) + 1);
}

status_t persist_delete(uint32_t key) {
  maybe_cut_power_before_mutation();
  if (s_crash_before_delete >= 0 && s_delete_calls++ == s_crash_before_delete) {
    longjmp(s_power_cut, 1);
  }
  if ((int)key == s_fail_delete_key) return E_ERROR;
  StoredValue *value = stored(key);
  if (!value->exists) return E_DOES_NOT_EXIST;
  memset(value, 0, sizeof(*value));
  return S_TRUE;
}

static void reset_store(void) {
  memset(s_store, 0, sizeof(s_store));
  s_fail_write_key = -1;
  s_torn_write_key = -1;
  s_torn_write_bytes = 0;
  s_fail_delete_key = -1;
  s_crash_before_delete = -1;
  s_delete_calls = 0;
  s_crash_before_mutation = -1;
  s_mutation_calls = 0;
  s_persist_max_size = STORE_KEYS * PERSIST_DATA_MAX_LENGTH;
}

static void fill(char *output, size_t length, char seed) {
  for (size_t i = 0; i < length; i++)
    output[i] = (char)(seed + i % 20);
  output[length] = '\0';
}

static const PersistentBlob TEST_BLOB = {
    .record_key = 1,
    .legacy_key = 2,
    .chunk_base = 10,
    .max_chunks = 4,
};

static const PersistentBlob ACTIVE_CONFIG_BLOB = {
    .record_key = 4,
    .legacy_key = 5,
    .chunk_base = 30,
    .max_chunks = 4,
};

static const PersistentBlob PENDING_CONFIG_BLOB = {
    .record_key = 7,
    .legacy_key = 8,
    .chunk_base = 50,
    .max_chunks = 4,
};

static void assert_blob_equals(const PersistentBlob *blob, const char *expected, size_t length) {
  char output[1025];
  memset(output, 0xa5, sizeof(output));
  assert(persistent_blob_read(blob, output, sizeof(output)));
  assert(memcmp(output, expected, length) == 0);
  assert(output[length] == '\0');
}

static bool delete_with_power_cut(const PersistentBlob *blob, int crash_before_delete) {
  s_delete_calls = 0;
  s_crash_before_delete = crash_before_delete;
  if (setjmp(s_power_cut) != 0) {
    s_crash_before_delete = -1;
    return false;
  }
  const bool deleted = persistent_blob_delete(blob);
  s_crash_before_delete = -1;
  assert(deleted);
  return true;
}

static void test_persistent_blob_boundaries(void) {
  reset_store();
  char value[1026];
  const size_t lengths[] = {0, 255, 256, 257, 1024};
  for (size_t i = 0; i < sizeof(lengths) / sizeof(lengths[0]); i++) {
    fill(value, lengths[i], (char)('A' + i));
    assert(persistent_blob_write(&TEST_BLOB, value, lengths[i]));
    assert_blob_equals(&TEST_BLOB, value, lengths[i]);
  }
  assert(!persistent_blob_write(&TEST_BLOB, value, 1025));
  fill(value, 1024, 'E');
  assert_blob_equals(&TEST_BLOB, value, 1024);

  char too_small[1024];
  assert(!persistent_blob_read(&TEST_BLOB, too_small, sizeof(too_small)));
  assert(too_small[0] == '\0');
}

static void test_persistent_blob_recovery(void) {
  reset_store();
  const PersistentBlob blob = {
      .record_key = 1,
      .legacy_key = 2,
      .chunk_base = 10,
      .max_chunks = 4,
  };
  char first[701];
  char second[601];
  fill(first, 700, 'A');
  fill(second, 600, 'a');

  assert(!persistent_blob_exists(&blob));
  assert(persistent_blob_write(&blob, first, strlen(first)));
  assert(persistent_blob_exists(&blob));
  assert_blob_equals(&blob, first, strlen(first));

  s_fail_write_key = 10;
  assert(!persistent_blob_write(&blob, second, strlen(second)));
  s_fail_write_key = -1;
  char output[1025];
  assert(!persistent_blob_read(&blob, output, sizeof(output)));
  assert(output[0] == '\0');

  s_torn_write_key = 10;
  s_torn_write_bytes = 113;
  assert(!persistent_blob_write(&blob, second, strlen(second)));
  s_torn_write_key = -1;
  assert(!persistent_blob_read(&blob, output, sizeof(output)));

  s_fail_write_key = 1;
  assert(!persistent_blob_write(&blob, second, strlen(second)));
  s_fail_write_key = -1;
  assert(!persistent_blob_read(&blob, output, sizeof(output)));

  s_torn_write_key = 1;
  s_torn_write_bytes = 7;
  assert(!persistent_blob_write(&blob, second, strlen(second)));
  s_torn_write_key = -1;
  assert(!persistent_blob_read(&blob, output, sizeof(output)));

  assert(persistent_blob_write(&blob, second, strlen(second)));
  assert_blob_equals(&blob, second, strlen(second));

  const char legacy[] = "legacy configuration";
  assert(persist_write_string(blob.legacy_key, legacy) == (int)sizeof(legacy));
  stored(blob.record_key)->data[4] = 0xff;
  assert(!persistent_blob_read(&blob, output, sizeof(output)));
  assert(output[0] == '\0');

  assert(persistent_blob_delete(&blob));
  assert(!persistent_blob_exists(&blob));

  assert(persist_write_string(blob.legacy_key, legacy) == (int)sizeof(legacy));
  assert(persistent_blob_read(&blob, output, sizeof(output)));
  assert(strcmp(legacy, output) == 0);
  assert(persistent_blob_write(&blob, first, strlen(first)));
  assert(!persist_exists(blob.legacy_key));
  assert(persistent_blob_delete(&blob));
  assert(persistent_blob_delete(&blob));
}

static void test_persistent_blob_legacy_barrier(void) {
  const char legacy[] = "legacy configuration";
  char current[301];
  char output[1025];
  fill(current, 300, 'A');

  reset_store();
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  s_fail_delete_key = (int)TEST_BLOB.legacy_key;
  assert(persistent_blob_write(&TEST_BLOB, current, 300));
  s_fail_delete_key = -1;
  assert(persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(memcmp(output, current, 300) == 0);
  assert(output[300] == '\0');
  assert(persist_exists(TEST_BLOB.legacy_key));

  reset_store();
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  s_fail_write_key = (int)TEST_BLOB.chunk_base;
  assert(!persistent_blob_write(&TEST_BLOB, current, 300));
  s_fail_write_key = -1;
  assert(persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(strcmp(output, legacy) == 0);

  reset_store();
  assert(persistent_blob_write(&TEST_BLOB, current, 300));
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  stored(TEST_BLOB.chunk_base)->length--;
  memset(output, 0xa5, sizeof(output));
  assert(!persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(output[0] == '\0');

  reset_store();
  assert(persistent_blob_write(&TEST_BLOB, current, 300));
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  stored(TEST_BLOB.record_key)->data[4] ^= 1;
  memset(output, 0xa5, sizeof(output));
  assert(!persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(output[0] == '\0');

  reset_store();
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  s_torn_write_key = (int)TEST_BLOB.record_key;
  s_torn_write_bytes = 7;
  s_fail_delete_key = (int)TEST_BLOB.record_key;
  assert(!persistent_blob_write(&TEST_BLOB, current, 300));
  s_torn_write_key = -1;
  s_fail_delete_key = -1;
  assert(persist_get_size(TEST_BLOB.record_key) == 7);
  assert(!persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(output[0] == '\0');
}

static void test_persistent_blob_delete_power_cuts(void) {
  char first[301];
  char second[302];
  const char legacy[] = "ancient configuration";
  fill(first, 300, 'A');
  fill(second, 301, 'a');

  bool reached_completion = false;
  for (int cut = 0; cut < 20; cut++) {
    reset_store();
    assert(persistent_blob_write(&TEST_BLOB, first, 300));
    assert(persistent_blob_write(&TEST_BLOB, second, 301));
    assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));

    if (delete_with_power_cut(&TEST_BLOB, cut)) {
      reached_completion = true;
      assert(!persistent_blob_exists(&TEST_BLOB));
      break;
    }

    char output[1025];
    const bool readable = persistent_blob_read(&TEST_BLOB, output, sizeof(output));
    if (readable) {
      const bool current_value = memcmp(output, second, 301) == 0 && output[301] == '\0';
      assert(current_value || strcmp(output, legacy) == 0);
    } else {
      assert(output[0] == '\0');
    }
    assert(persistent_blob_delete(&TEST_BLOB));
    assert(!persistent_blob_exists(&TEST_BLOB));
  }
  assert(reached_completion);
}

static void test_persistent_blob_delete_failures(void) {
  reset_store();
  char first[301];
  char second[302];
  char third[303];
  fill(first, 300, 'A');
  fill(second, 301, 'a');
  fill(third, 302, 'K');

  assert(persistent_blob_write(&TEST_BLOB, first, 300));
  assert(persistent_blob_write(&TEST_BLOB, second, 301));

  s_fail_delete_key = (int)TEST_BLOB.record_key;
  assert(!persistent_blob_write(&TEST_BLOB, third, 302));
  s_fail_delete_key = -1;
  assert_blob_equals(&TEST_BLOB, second, 301);

  s_fail_delete_key = (int)TEST_BLOB.chunk_base;
  assert(!persistent_blob_write(&TEST_BLOB, third, 302));
  s_fail_delete_key = -1;
  char unreadable[1025];
  assert(!persistent_blob_read(&TEST_BLOB, unreadable, sizeof(unreadable)));
  assert(persistent_blob_write(&TEST_BLOB, third, 302));
  assert_blob_equals(&TEST_BLOB, third, 302);

  s_fail_delete_key = (int)TEST_BLOB.record_key;
  assert(!persistent_blob_delete(&TEST_BLOB));
  assert(persist_exists(TEST_BLOB.record_key));
  s_fail_delete_key = -1;
  assert(persistent_blob_delete(&TEST_BLOB));
  assert(!persistent_blob_exists(&TEST_BLOB));

  assert(persistent_blob_write(&TEST_BLOB, first, 300));
  s_fail_delete_key = (int)TEST_BLOB.chunk_base;
  assert(!persistent_blob_delete(&TEST_BLOB));
  assert(persist_exists(TEST_BLOB.chunk_base));
  s_fail_delete_key = -1;
  assert(persistent_blob_delete(&TEST_BLOB));
  assert(!persistent_blob_exists(&TEST_BLOB));
}

static void setup_config_replacement(const char *current, const char *queued) {
  reset_store();
  assert(persistent_blob_write(&ACTIVE_CONFIG_BLOB, current, strlen(current)));
  assert(persistent_blob_write(&PENDING_CONFIG_BLOB, queued, strlen(queued)));
}

// This is the persistence-only sequence used by main.c after both inputs have been
// parsed. Keeping it here lets the failure-injecting store prove every durable prefix;
// watch_stack.test.js separately locks production to the same ordering.
static bool replace_config_preserving_pending(const char *replacement) {
  char queued[1025];
  if (!persistent_blob_read(&PENDING_CONFIG_BLOB, queued, sizeof(queued)) ||
      !persistent_blob_write(&ACTIVE_CONFIG_BLOB, queued, strlen(queued)) ||
      !persistent_blob_write(&ACTIVE_CONFIG_BLOB, replacement, strlen(replacement))) {
    return false;
  }
  return persistent_blob_delete(&PENDING_CONFIG_BLOB);
}

static int config_value_kind(const char *value, const char *current, const char *queued,
                             const char *replacement) {
  if (strcmp(value, current) == 0) return 0;
  if (strcmp(value, queued) == 0) return 1;
  if (strcmp(value, replacement) == 0) return 2;
  return -1;
}

static void assert_config_replacement_invariant(const char *current, const char *queued,
                                                const char *replacement) {
  char active[1025];
  char pending[1025];
  assert(persistent_blob_read(&ACTIVE_CONFIG_BLOB, active, sizeof(active)));
  const int active_kind = config_value_kind(active, current, queued, replacement);
  assert(active_kind >= 0);

  const bool pending_exists = persistent_blob_exists(&PENDING_CONFIG_BLOB);
  const bool pending_readable =
      persistent_blob_read(&PENDING_CONFIG_BLOB, pending, sizeof(pending));
  if (pending_readable) {
    assert(strcmp(pending, queued) == 0);
    assert(active_kind == 0 || active_kind == 1);
  } else if (pending_exists) {
    // Pending deletion may have lost power after its data disappeared but before
    // metadata cleanup. Promotion must already have made the queued baseline active.
    assert(active_kind == 1);
  } else {
    assert(active_kind == 1 || active_kind == 2);
  }
  if (active_kind == 2) assert(!pending_exists);
}

static void recover_config_replacement(const char *queued, const char *replacement) {
  char pending[1025];
  if (persistent_blob_read(&PENDING_CONFIG_BLOB, pending, sizeof(pending))) {
    assert(strcmp(pending, queued) == 0);
    assert(persistent_blob_write(&ACTIVE_CONFIG_BLOB, pending, strlen(pending)));
  }
  if (persistent_blob_exists(&PENDING_CONFIG_BLOB)) {
    assert(persistent_blob_delete(&PENDING_CONFIG_BLOB));
  }
  assert(persistent_blob_write(&ACTIVE_CONFIG_BLOB, replacement, strlen(replacement)));
  assert_blob_equals(&ACTIVE_CONFIG_BLOB, replacement, strlen(replacement));
  assert(!persistent_blob_exists(&PENDING_CONFIG_BLOB));
}

static void test_config_replacement_preserves_queued_baseline(void) {
  const char current[] = "current configuration";
  const char queued[] = "confirmed queued configuration";
  const char replacement[] = "direct replacement configuration";

  setup_config_replacement(current, queued);
  s_fail_write_key = (int)ACTIVE_CONFIG_BLOB.chunk_base;
  assert(!replace_config_preserving_pending(replacement));
  s_fail_write_key = -1;
  char unreadable_active[1025];
  assert(!persistent_blob_read(&ACTIVE_CONFIG_BLOB, unreadable_active, sizeof(unreadable_active)));
  assert_blob_equals(&PENDING_CONFIG_BLOB, queued, strlen(queued));

  setup_config_replacement(current, queued);
  s_fail_delete_key = (int)PENDING_CONFIG_BLOB.chunk_base;
  assert(!replace_config_preserving_pending(replacement));
  s_fail_delete_key = -1;
  assert_blob_equals(&ACTIVE_CONFIG_BLOB, replacement, strlen(replacement));
  assert(!persistent_blob_exists(&PENDING_CONFIG_BLOB));

  setup_config_replacement(current, queued);
  s_fail_delete_key = (int)PENDING_CONFIG_BLOB.record_key;
  assert(!replace_config_preserving_pending(replacement));
  s_fail_delete_key = -1;
  assert_blob_equals(&ACTIVE_CONFIG_BLOB, replacement, strlen(replacement));
  assert(persistent_blob_exists(&PENDING_CONFIG_BLOB));
  char unreadable[1025];
  assert(!persistent_blob_read(&PENDING_CONFIG_BLOB, unreadable, sizeof(unreadable)));
  recover_config_replacement(queued, replacement);

  setup_config_replacement(current, queued);
  s_fail_write_key = (int)ACTIVE_CONFIG_BLOB.chunk_base;
  assert(!replace_config_preserving_pending(replacement));
  s_fail_write_key = -1;
  assert(!persistent_blob_read(&ACTIVE_CONFIG_BLOB, unreadable_active, sizeof(unreadable_active)));
  assert_blob_equals(&PENDING_CONFIG_BLOB, queued, strlen(queued));

  setup_config_replacement(current, queued);
  assert(replace_config_preserving_pending(replacement));
  assert_config_replacement_invariant(current, queued, replacement);

  // A same-ID application retry repeats the durable write. It is safe whether the
  // previous result was lost before or after the replacement commit.
  assert(persistent_blob_write(&ACTIVE_CONFIG_BLOB, replacement, strlen(replacement)));
  assert_blob_equals(&ACTIVE_CONFIG_BLOB, replacement, strlen(replacement));
}

static void test_persistent_blob_capacity(void) {
  reset_store();
  char first[258];
  char second[258];
  fill(first, 257, 'A');
  fill(second, 257, 'a');

  assert(persistent_blob_write(&TEST_BLOB, first, 257));
  const size_t first_usage = persist_used();
  const size_t metadata_size = stored(TEST_BLOB.record_key)->length;
  assert(first_usage == 257 + metadata_size);

  s_persist_max_size = first_usage - 1;
  assert(!persistent_blob_write(&TEST_BLOB, second, 257));
  char unreadable[1025];
  assert(!persistent_blob_read(&TEST_BLOB, unreadable, sizeof(unreadable)));
  assert(persist_used() == 0);

  assert(persistent_blob_delete(&TEST_BLOB));
  s_persist_max_size = 257 + metadata_size - 1;
  assert(!persistent_blob_write(&TEST_BLOB, first, 257));
  assert(!persistent_blob_exists(&TEST_BLOB));

  s_persist_max_size = 257 + metadata_size;
  assert(persistent_blob_write(&TEST_BLOB, first, 257));
  assert_blob_equals(&TEST_BLOB, first, 257);

  reset_store();
  const char unrelated[] = "other persisted state";
  assert(persist_write_data(50, unrelated, sizeof(unrelated)) == (int)sizeof(unrelated));
  s_persist_max_size = sizeof(unrelated) + 257 + metadata_size - 1;
  assert(!persistent_blob_write(&TEST_BLOB, first, 257));
  assert(persist_used() == sizeof(unrelated));
  assert(!persistent_blob_exists(&TEST_BLOB));

  s_persist_max_size = sizeof(unrelated) + 257 + metadata_size;
  assert(persistent_blob_write(&TEST_BLOB, first, 257));
  assert_blob_equals(&TEST_BLOB, first, 257);
}

static void test_persistent_blob_invalid_layout(void) {
  reset_store();
  assert(!persistent_blob_delete(NULL));
  const PersistentBlob invalid[] = {
      {
          .record_key = 1,
          .legacy_key = 3,
          .chunk_base = 10,
          .max_chunks = 0,
      },
      {
          .record_key = 1,
          .legacy_key = 1,
          .chunk_base = 10,
          .max_chunks = 4,
      },
      {
          .record_key = 10,
          .legacy_key = 3,
          .chunk_base = 10,
          .max_chunks = 4,
      },
      {
          .record_key = 1,
          .legacy_key = 11,
          .chunk_base = 10,
          .max_chunks = 4,
      },
      {
          .record_key = 1,
          .legacy_key = 3,
          .chunk_base = UINT32_MAX - 1,
          .max_chunks = 4,
      },
  };
  for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); i++) {
    assert(!persistent_blob_write(&invalid[i], "value", 5));
    assert(!persistent_blob_exists(&invalid[i]));
    assert(!persistent_blob_delete(&invalid[i]));
  }
}

static bool parse(const char *value, const char *active, WatchConfig *output) {
  char buffer[4096];
  const size_t length = strlen(value);
  assert(length < sizeof(buffer));
  memcpy(buffer, value, length + 1);
  return watch_config_parse(buffer, active, output);
}

static void test_watch_config_transfer(void) {
  WatchConfigTransfer transfer;
  char buffer[WATCH_CONFIG_BUFFER_SIZE];
  watch_config_transfer_initialize(&transfer);
  buffer[0] = '\0';

  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 7, 0, 3, "ab", 2) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 7, 1, 3, "cd", 2) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 7, 0, 3, "ab", 2) ==
         WATCH_TRANSFER_DUPLICATE);
  assert(transfer.next_chunk == 2);
  assert(transfer.length == 4);
  assert(strcmp(buffer, "abcd") == 0);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 7, 1, 3, "cd", 2) ==
         WATCH_TRANSFER_DUPLICATE);

  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 99, 1, 3, "unrelated",
                                      9) == WATCH_TRANSFER_IGNORED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 99, 1,
                                      WATCH_CONFIG_MAX_CHUNKS + 1, "bad",
                                      3) == WATCH_TRANSFER_IGNORED);
  assert(transfer.id == 7);
  assert(transfer.next_chunk == 2);

  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 7, 2, 3, "ef", 2) ==
         WATCH_TRANSFER_COMPLETE);
  assert(strcmp(buffer, "abcdef") == 0);

  watch_config_transfer_initialize(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 7, 0, 1, "abcdef", 6) ==
         WATCH_TRANSFER_COMPLETE);
  assert(strcmp(buffer, "abcdef") == 0);

  watch_config_transfer_reset(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 8, 0, 2, "one", 3) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 8, 0, 2, "changed", 7) ==
         WATCH_TRANSFER_INVALID);
  assert(transfer.id == -1);

  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 9, 0, 2, "one", 3) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 9, 1, 3, "two", 3) ==
         WATCH_TRANSFER_INVALID);
  assert(transfer.id == -1);

  char small[5];
  assert(watch_config_transfer_accept(&transfer, small, sizeof(small), 10, 0, 2, "four", 4) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, small, sizeof(small), 10, 1, 2, "x", 1) ==
         WATCH_TRANSFER_INVALID);
  assert(transfer.id == -1);

  watch_config_transfer_initialize(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 21, 0, 1, "new", 3) ==
         WATCH_TRANSFER_COMPLETE);
  watch_config_transfer_reset(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 20, 0, 1, "old", 3) ==
         WATCH_TRANSFER_IGNORED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 20, 0, 2, "old-", 4) ==
         WATCH_TRANSFER_IGNORED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 20, 1, 2, "tail", 4) ==
         WATCH_TRANSFER_IGNORED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 21, 0, 1, "changed", 7) ==
         WATCH_TRANSFER_INVALID);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 21, 0, 1, "new", 3) ==
         WATCH_TRANSFER_COMPLETE);
  assert(strcmp(buffer, "new") == 0);

  watch_config_transfer_initialize(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 22, 0, 2, "ab", 2) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 22, 1, 2, "cd", 2) ==
         WATCH_TRANSFER_COMPLETE);
  watch_config_transfer_reset(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 22, 0, 2, "ab", 2) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 22, 1, 2, "XX", 2) ==
         WATCH_TRANSFER_INVALID);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 22, 0, 2, "ab", 2) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 22, 1, 2, "cd", 2) ==
         WATCH_TRANSFER_COMPLETE);
  assert(strcmp(buffer, "abcd") == 0);

  watch_config_transfer_initialize(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 31, 0, 2, "new-", 4) ==
         WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 30, 0, 1, "old", 3) ==
         WATCH_TRANSFER_IGNORED);
  assert(transfer.id == 31);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 31, 1, 2, "tail", 4) ==
         WATCH_TRANSFER_COMPLETE);
  assert(strcmp(buffer, "new-tail") == 0);

  watch_config_transfer_initialize(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), INT32_MAX, 0, 1, "edge",
                                      4) == WATCH_TRANSFER_COMPLETE);
  watch_config_transfer_reset(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 0, 0, 1, "wrapped", 7) ==
         WATCH_TRANSFER_COMPLETE);
  watch_config_transfer_reset(&transfer);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), INT32_MAX, 0, 1, "stale",
                                      5) == WATCH_TRANSFER_IGNORED);
  assert(watch_config_transfer_accept(&transfer, buffer, sizeof(buffer), 0x40000000, 0, 1,
                                      "ambiguous", 9) == WATCH_TRANSFER_IGNORED);
}

static void test_transfer_serial_reservation(void) {
  const uint32_t key = 150;
  int32_t reserved = -1;
  reset_store();
  assert(watch_transfer_serial_reserve_persistent(key, 7, &reserved));
  assert(reserved == 7);
  assert(watch_transfer_serial_reserve_persistent(key, 99, &reserved));
  assert(reserved == 8);

  const int32_t maximum = INT32_MAX;
  assert(persist_write_data(key, &maximum, sizeof(maximum)) == (int)sizeof(maximum));
  assert(watch_transfer_serial_reserve_persistent(key, 99, &reserved));
  assert(reserved == 0);

  s_fail_write_key = (int)key;
  reserved = 123;
  assert(!watch_transfer_serial_reserve_persistent(key, 99, &reserved));
  assert(reserved == 123);
  s_fail_write_key = -1;
  assert(watch_transfer_serial_reserve_persistent(key, 99, &reserved));
  assert(reserved == 1);

  s_torn_write_key = (int)key;
  s_torn_write_bytes = sizeof(int32_t);
  reserved = 123;
  assert(!watch_transfer_serial_reserve_persistent(key, 99, &reserved));
  assert(reserved == 123);
  s_torn_write_key = -1;
  s_torn_write_bytes = 0;
  assert(watch_transfer_serial_reserve_persistent(key, 99, &reserved));
  assert(reserved == 3);

  const int32_t corrupt = -1;
  assert(persist_write_data(key, &corrupt, sizeof(corrupt)) == (int)sizeof(corrupt));
  assert(!watch_transfer_serial_reserve_persistent(key, 99, &reserved));
}

static void test_watch_text_validation(void) {
  assert(watch_waypoint_name_valid("Summit note"));
  assert(watch_waypoint_name_valid("pipe | is allowed"));
  assert(watch_waypoint_name_valid("\xE2\x80\x83 summit \xE2\x80\x83"));
  assert(!watch_waypoint_name_valid(""));
  assert(!watch_waypoint_name_valid("   "));
  assert(!watch_waypoint_name_valid("\xC2\xA0\xC2\xA0"));
  assert(!watch_waypoint_name_valid("\xEF\xBB\xBF"));
  assert(!watch_waypoint_name_valid("bad\tname"));
  assert(!watch_waypoint_name_valid("\xC0\xAF"));

  char maximum[WATCH_WAYPOINT_NAME_BYTES + 2];
  memset(maximum, 'x', WATCH_WAYPOINT_NAME_BYTES);
  maximum[WATCH_WAYPOINT_NAME_BYTES] = '\0';
  assert(watch_waypoint_name_valid(maximum));
  maximum[WATCH_WAYPOINT_NAME_BYTES] = 'x';
  maximum[WATCH_WAYPOINT_NAME_BYTES + 1] = '\0';
  assert(!watch_waypoint_name_valid(maximum));

  assert(watch_profile_names_equal("\xC3\x84rger", "\xC3\xA4rger"));
  assert(watch_profile_names_equal("\xCE\xA3", "\xCF\x83"));
  assert(watch_profile_names_equal("\xD0\xAF", "\xD1\x8F"));
  assert(!watch_profile_names_equal("\xC4\x80", "\xC4\x81"));

  assert(watch_locus_profile_valid("1", "Hiking"));
  assert(watch_locus_profile_valid("0", "Default"));
  assert(watch_locus_profile_valid("-9223372036854775808", "Internal"));
  assert(watch_locus_profile_valid("9223372036854775807", "Wandern \xC3\x84"));
  assert(!watch_locus_profile_valid("-0", "Hiking"));
  assert(!watch_locus_profile_valid("9223372036854775808", "Hiking"));
  assert(!watch_locus_profile_valid("12x", "Hiking"));
  assert(!watch_locus_profile_valid("12", "bad|name"));
  assert(!watch_locus_profile_valid("12", "bad\nname"));
}

static void test_watch_config(void) {
  WatchConfig config;
  const char valid[] = "dark|1|10|12345|4294967295|42\n"
                       "Default|1,3,5|walk\n"
                       "Climb|1,10,11|climb";
  assert(parse(valid, "walk", &config));
  assert(config.profile_count == 2);
  assert(config.selected == 0);
  assert(config.dark);
  assert(config.watch_hr_to_locus);
  assert(config.heart_rate_interval == 10);
  assert(strcmp(config.locus_id, "12345") == 0);
  assert(config.fingerprint_a == UINT32_MAX);
  assert(config.fingerprint_b == 42);
  assert(!config.profiles[1].protected_profile);
  assert(config.profiles[1].metrics[2] == 11);
  assert(parse(valid, "climb", &config));
  assert(config.selected == 1);

  assert(!parse("blue|0|5|1|1|2\nOnly|1|id", NULL, &config));
  assert(!parse("dark|2|5|1|1|2\nOnly|1|id", NULL, &config));
  assert(!parse("dark|0|0|1|1|2\nOnly|1|id", NULL, &config));
  assert(parse("dark|0|5|0|1|2\nOnly|1|id", NULL, &config));
  assert(!parse("dark|0|5|-0|1|2\nOnly|1|id", NULL, &config));
  assert(!parse("dark|0|5|01|1|2\nOnly|1|id", NULL, &config));
  assert(!parse("dark|0|5|1|x|2\nOnly|1|id", NULL, &config));
  assert(!parse("dark|0|5|1|1|2\nOnly|1x|id", NULL, &config));
  assert(!parse("dark|0|5|1|1|2\nOnly|1,1|id", NULL, &config));
  assert(!parse("dark|0|5|1|1|2\nOnly|1|same\nOther|2|same", NULL, &config));
  assert(parse("dark|0|5|1|1|2\nOnly|1|one\nonly|2|two", NULL, &config));
  assert(!parse("dark|0|5|1|1|2\nOnly|1|id|extra", NULL, &config));
  assert(!parse("dark|0|5|1|1|2\n   |1|id", NULL, &config));
  assert(!parse("dark|0|5|1|1|2\nOnly|1|1234567890123456789012345678901234567890", NULL, &config));

  char unicode_name[85];
  size_t offset = 0;
  for (int i = 0; i < 20; i++) {
    const unsigned char boot[] = {0xf0, 0x9f, 0xa5, 0xbe};
    memcpy(unicode_name + offset, boot, sizeof(boot));
    offset += sizeof(boot);
  }
  unicode_name[offset] = '\0';
  char unicode_config[512];
  snprintf(unicode_config, sizeof(unicode_config), "dark|0|5|1|1|2\n%s|1|unicode", unicode_name);
  assert(parse(unicode_config, NULL, &config));
  strcat(unicode_name, "x");
  snprintf(unicode_config, sizeof(unicode_config), "dark|0|5|1|1|2\n%s|1|unicode", unicode_name);
  assert(!parse(unicode_config, NULL, &config));

  char too_many[1024] = "dark|0|5|1|1|2";
  for (int i = 0; i < 5; i++) {
    char line[80];
    snprintf(line, sizeof(line), "\nProfile%d|1|id%d", i, i);
    strcat(too_many, line);
  }
  assert(!parse(too_many, NULL, &config));

  assert(watch_profile_list_valid("1|Walking\n42|Wandern \xC3\x84",
                                  strlen("1|Walking\n42|Wandern \xC3\x84")));
  assert(watch_profile_list_valid("0|Default\n-1|Internal", strlen("0|Default\n-1|Internal")));
  assert(watch_profile_list_valid("-9223372036854775808|Minimum",
                                  strlen("-9223372036854775808|Minimum")));
  assert(!watch_profile_list_valid("1|Walking\n", strlen("1|Walking\n")));
  assert(!watch_profile_list_valid("1|Walking\n\n2|Cycling", strlen("1|Walking\n\n2|Cycling")));
  assert(!watch_profile_list_valid("1|Walking\n2|Cycling\n1|Running",
                                   strlen("1|Walking\n2|Cycling\n1|Running")));
  assert(!watch_profile_list_valid("-0|Walking", strlen("-0|Walking")));
  assert(!watch_profile_list_valid("01|Walking", strlen("01|Walking")));
  assert(!watch_profile_list_valid("-9223372036854775809|Walking",
                                   strlen("-9223372036854775809|Walking")));
  assert(!watch_profile_list_valid("1|Walk|ing", strlen("1|Walk|ing")));
  const char invalid_utf8[] = {'1', '|', 'B', 'a', 'd', (char)0xc0, (char)0xaf};
  assert(!watch_profile_list_valid(invalid_utf8, sizeof(invalid_utf8)));
}

static WatchProfileTransferOutcome accept_profile_part(int32_t id, int index, int count, int result,
                                                       const char *data, uint32_t uptime_seconds) {
  return watch_profile_transfer_accept(&s_profile_transfer, s_profile_transfer_buffer,
                                       sizeof(s_profile_transfer_buffer), id, index, count, result,
                                       data, strlen(data), uptime_seconds);
}

static void test_snapshot_epoch_ordering(void) {
  SnapshotState state = {
      .received = true,
      .epoch = 200,
      .age = 0,
  };
  assert(!accept_snapshot_epoch(&state, 199, true));
  assert(state.epoch == 200);
  assert(state.age == 0);
  assert(state.health_updates == 0);
  assert(state.pending_applies == 0);

  state.age = 31;
  assert(!accept_snapshot_epoch(&state, 100, true));
  assert(state.epoch == 200);
  assert(state.age == 31);
  assert(state.health_updates == 0);
  assert(state.pending_applies == 0);

  assert(accept_snapshot_epoch(&state, 200, true));
  assert(state.epoch == 200);
  assert(state.age == 0);
  assert(state.health_updates == 1);
  assert(state.pending_applies == 1);

  SnapshotState reopened = {0};
  assert(accept_snapshot_epoch(&reopened, 100, false));
  assert(reopened.epoch == 100);
}

static void test_profile_transfer_reordering(void) {
  watch_profile_transfer_initialize(&s_profile_transfer);
  assert(accept_profile_part(7, 0, 3, 0, "A", 1) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(7, 1, 3, 0, "B", 2) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(7, 0, 3, 0, "A", 3) == WATCH_PROFILE_TRANSFER_DUPLICATE);
  assert(s_profile_transfer.received_count == 2);
  assert(s_profile_transfer.received[1]);
  assert(memcmp(s_profile_transfer_buffer + WATCH_PROFILE_CHUNK_BYTES, "B", 1) == 0);
  assert(s_profile_transfer.last_activity == 3);
  assert(accept_profile_part(7, 2, 3, 0, "C", 4) == WATCH_PROFILE_TRANSFER_COMPLETE);
  assert(s_profile_transfer.received_count == 3);
  size_t joined_length = 0;
  assert(watch_profile_transfer_join(&s_profile_transfer, s_profile_transfer_buffer,
                                     sizeof(s_profile_transfer_buffer), 8191, &joined_length));
  assert(joined_length == 3);
  assert(strcmp(s_profile_transfer_buffer, "ABC") == 0);

  watch_profile_transfer_reset(&s_profile_transfer);
  assert(accept_profile_part(8, 0, 3, 0, "old-", 5) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(8, 2, 3, 0, "tail", 6) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(8, 0, 3, 0, "new-", 7) == WATCH_PROFILE_TRANSFER_INVALID);
  assert(s_profile_transfer.id == -1);
  assert(s_profile_transfer.received_count == 0);
  assert(accept_profile_part(8, 1, 3, 0, "middle-", 8) == WATCH_PROFILE_TRANSFER_IGNORED);
  assert(accept_profile_part(8, 0, 3, 0, "new-", 9) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(8, 1, 3, 0, "middle-", 10) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(8, 2, 3, 0, "tail", 11) == WATCH_PROFILE_TRANSFER_COMPLETE);

  watch_profile_transfer_reset(&s_profile_transfer);
  assert(accept_profile_part(9, 0, 3, 0, "same", 12) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(9, 0, 2, 0, "same", 13) == WATCH_PROFILE_TRANSFER_INVALID);
  assert(s_profile_transfer.id == -1);
  assert(accept_profile_part(10, 0, 3, 0, "same", 14) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(10, 0, 3, 3, "same", 15) == WATCH_PROFILE_TRANSFER_INVALID);
  assert(s_profile_transfer.id == -1);

  assert(accept_profile_part(11, 0, 2, 0, "old-", 16) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(12, 0, 2, 0, "new-", 17) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(s_profile_transfer.id == 12);
  assert(s_profile_transfer.received_count == 1);
  assert(accept_profile_part(12, 1, 2, 0, "tail", 18) == WATCH_PROFILE_TRANSFER_COMPLETE);

  watch_profile_transfer_reset(&s_profile_transfer);
  assert(accept_profile_part(11, 0, 1, 0, "stale", 19) == WATCH_PROFILE_TRANSFER_IGNORED);
  assert(accept_profile_part(11, 0, 2, 0, "stale-", 19) == WATCH_PROFILE_TRANSFER_IGNORED);
  assert(accept_profile_part(11, 1, 2, 0, "tail", 19) == WATCH_PROFILE_TRANSFER_IGNORED);
  assert(accept_profile_part(12, 0, 2, 0, "new-", 20) == WATCH_PROFILE_TRANSFER_IGNORED);
  assert(s_profile_transfer.id == -1);

  assert(accept_profile_part(13, 0, 2, 0, "fresh-", 21) == WATCH_PROFILE_TRANSFER_ACCEPTED);
  assert(accept_profile_part(12, 0, 1, 0, "stale", 22) == WATCH_PROFILE_TRANSFER_IGNORED);
  assert(s_profile_transfer.id == 13);
  assert(accept_profile_part(13, 1, 2, 0, "tail", 23) == WATCH_PROFILE_TRANSFER_COMPLETE);

  watch_profile_transfer_initialize(&s_profile_transfer);
  assert(accept_profile_part(INT32_MAX, 0, 1, 0, "edge", 24) == WATCH_PROFILE_TRANSFER_COMPLETE);
  watch_profile_transfer_reset(&s_profile_transfer);
  assert(accept_profile_part(0, 0, 1, 0, "wrapped", 25) == WATCH_PROFILE_TRANSFER_COMPLETE);
  watch_profile_transfer_reset(&s_profile_transfer);
  assert(accept_profile_part(INT32_MAX, 0, 1, 0, "stale", 26) == WATCH_PROFILE_TRANSFER_IGNORED);
  assert(accept_profile_part(0x40000000, 0, 1, 0, "ambiguous", 27) ==
         WATCH_PROFILE_TRANSFER_IGNORED);
}

int main(void) {
  test_watch_maintenance_planner();
  test_watch_maintenance_timer();
  test_ui_metrics();
  test_persistent_blob_boundaries();
  test_persistent_blob_recovery();
  test_persistent_blob_legacy_barrier();
  test_persistent_blob_delete_power_cuts();
  test_persistent_blob_delete_failures();
  test_config_replacement_preserves_queued_baseline();
  test_persistent_blob_capacity();
  test_persistent_blob_invalid_layout();
  test_watch_config_transfer();
  test_transfer_serial_reservation();
  test_watch_text_validation();
  test_watch_config();
  test_snapshot_epoch_ordering();
  test_profile_transfer_reordering();
  return 0;
}
