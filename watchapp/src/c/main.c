#include <pebble.h>

#include "persistent_blob.h"
#include "watch_build_hash.auto.h"
#include "watch_config.h"
#include "watch_state.h"

#define PROTOCOL_VERSION 3
#define RELEASE_VERSION "0.1.8"
#define MAX_SLOTS WATCH_MAX_SLOTS
#define MAX_PROFILES WATCH_MAX_PROFILES
#define NAME_SIZE WATCH_PROFILE_NAME_SIZE
#define ID_SIZE WATCH_PROFILE_ID_SIZE
#define LOCUS_NAME_SIZE WATCH_LOCUS_NAME_SIZE
#define WAYPOINT_NAME_BYTES WATCH_WAYPOINT_NAME_BYTES
#define WAYPOINT_NAME_SIZE (WAYPOINT_NAME_BYTES + 1)
#define CONFIG_SIZE WATCH_CONFIG_BUFFER_SIZE
#define PROFILE_LIST_SIZE 8192
#define CONFIG_CHUNK_BYTES WATCH_CONFIG_CHUNK_BYTES
#define PROFILE_CHUNK_BYTES WATCH_PROFILE_CHUNK_BYTES
#define CONFIG_MAX_CHUNKS WATCH_CONFIG_MAX_CHUNKS
#define PROFILE_MAX_CHUNKS WATCH_PROFILE_MAX_CHUNKS
#define DURABLE_TRANSFER_GENERATION 1
#define CONTROL_QUEUE_SIZE 9
#define COMMAND_RECORD_COUNT 6
#define MAX_SEND_ATTEMPTS 4
#define CONFIG_TRANSFER_TIMEOUT_SECONDS 45
#define PROFILE_TRANSFER_TIMEOUT_SECONDS 45
#define COMMAND_RESULT_TIMEOUT_SECONDS 120
#define SNAPSHOT_STALE_SECONDS 30
#define UNAVAILABLE INT32_MIN

#define PERSIST_CONFIG_LEGACY 100
#define PERSIST_PENDING_CONFIG_LEGACY 101
#define PERSIST_PROFILE_LIST_LEGACY 102
#define PERSIST_ACTIVE_ID 103
#define PERSIST_SESSION_COUNTER 104
#define PERSIST_RELAY_TRANSFER_COUNTER 105
#define PERSIST_CONFIG_META 200
#define PERSIST_PENDING_CONFIG_META 201
#define PERSIST_PROFILE_LIST_META 202
#define PERSIST_CONFIG_META_BACKUP 203
#define PERSIST_PENDING_CONFIG_META_BACKUP 204
#define PERSIST_PROFILE_LIST_META_BACKUP 205

enum {
  MSG_SNAPSHOT = 1,
  MSG_COMMAND = 2,
  MSG_COMMAND_RESULT = 3,
  MSG_REQUEST_SNAPSHOT = 4,
  MSG_CONFIG_CHUNK = 5,
  MSG_PROFILE_LIST_CHUNK = 6,
  MSG_REQUEST_PROFILE_LIST = 7,
  MSG_HEART_RATE = 8,
  MSG_CONFIG_RESULT = 9,
};

enum {
  STATE_STOPPED = 0,
  STATE_RECORDING = 1,
  STATE_PAUSED = 2,
  STATE_UNAVAILABLE = 3,
};

enum {
  CMD_START = 1,
  CMD_PAUSE_RESUME = 2,
  CMD_STOP_SAVE = 3,
  CMD_ADD_WAYPOINT = 4,
  CMD_ADD_WAYPOINT_WITH_NOTE = 5,
};

enum {
  RESULT_OK = 0,
  RESULT_INVALID_STATE = 1,
  RESULT_LOCUS_UNAVAILABLE = 2,
  RESULT_FAILED = 3,
  RESULT_INVALID_PROFILE = 4,
  RESULT_PROFILE_NOT_FOUND = 5,
  RESULT_INVALID_WAYPOINT_NAME = 6,
  RESULT_CONFIG_APPLIED = RESULT_OK,
  RESULT_CONFIG_QUEUED = 7,
  RESULT_INVALID_CONFIG = 8,
  RESULT_STORAGE_FAILED = 9,
};

enum {
  METRIC_ELAPSED = 1,
  METRIC_MOVING_TIME = 2,
  METRIC_DISTANCE = 3,
  METRIC_MOVING_DISTANCE = 4,
  METRIC_CURRENT_SPEED = 5,
  METRIC_AVERAGE_SPEED = 6,
  METRIC_MAX_SPEED = 7,
  METRIC_CURRENT_PACE = 8,
  METRIC_AVERAGE_PACE = 9,
  METRIC_ALTITUDE = 10,
  METRIC_ASCENT = 11,
  METRIC_DESCENT = 12,
  METRIC_VERTICAL_SPEED = 13,
  METRIC_SLOPE = 14,
  METRIC_AVG_HR = 15,
  METRIC_MAX_HR = 16,
  METRIC_AVG_CADENCE = 17,
  METRIC_MAX_CADENCE = 18,
  METRIC_AVG_POWER = 19,
  METRIC_MAX_POWER = 20,
  METRIC_ENERGY = 21,
  METRIC_CURRENT_HR = 22,
};

typedef WatchProfile Profile;

typedef struct {
  int state;
  uint32_t sample_epoch;
  uint32_t elapsed;
  int32_t moving_time;
  int32_t distance;
  int32_t moving_distance;
  int32_t current_speed;
  int32_t average_speed;
  int32_t max_speed;
  int32_t altitude;
  int32_t ascent;
  int32_t descent;
  int32_t vertical_speed;
  int32_t slope;
  int32_t avg_hr;
  int32_t max_hr;
  int32_t current_hr;
  int32_t avg_cadence;
  int32_t max_cadence;
  int32_t avg_power;
  int32_t max_power;
  int32_t energy;
} Snapshot;

typedef struct {
  int type;
  int command;
  uint32_t command_id;
  int32_t transfer_id;
  int32_t result;
  char text[LOCUS_NAME_SIZE];
} ControlMessage;

typedef struct {
  bool used;
  bool awaiting_result;
  uint32_t command_id;
  uint32_t result_deadline;
  int command;
} CommandRecord;

typedef enum {
  OUTBOUND_NONE,
  OUTBOUND_CONTROL,
  OUTBOUND_RELAY,
  OUTBOUND_HEART_RATE,
} OutboundKind;

static const PersistentBlob s_config_blob = {
  .metadata_key = {PERSIST_CONFIG_META, PERSIST_CONFIG_META_BACKUP},
  .legacy_key = PERSIST_CONFIG_LEGACY,
  .bank_base = {1000, 1020},
  .max_chunks = 16,
};
static const PersistentBlob s_pending_config_blob = {
  .metadata_key = {PERSIST_PENDING_CONFIG_META, PERSIST_PENDING_CONFIG_META_BACKUP},
  .legacy_key = PERSIST_PENDING_CONFIG_LEGACY,
  .bank_base = {1040, 1060},
  .max_chunks = 16,
};
static const PersistentBlob s_profile_list_blob = {
  .metadata_key = {PERSIST_PROFILE_LIST_META, PERSIST_PROFILE_LIST_META_BACKUP},
  .legacy_key = PERSIST_PROFILE_LIST_LEGACY,
  .bank_base = {1080, 1120},
  .max_chunks = 32,
};

static Window *s_main_window;
static Window *s_controls_window;
static Window *s_confirm_window;
static Window *s_profile_window;
static StatusBarLayer *s_status_bar;
static TextLayer *s_header;
static TextLayer *s_labels[MAX_SLOTS];
static TextLayer *s_value_layers[MAX_SLOTS];
static SimpleMenuLayer *s_menu;
static SimpleMenuLayer *s_confirm_menu;
static SimpleMenuLayer *s_profile_menu;
static SimpleMenuItem s_items[5];
static SimpleMenuItem s_confirm_items[2];
static SimpleMenuItem s_profile_items[MAX_PROFILES];
static SimpleMenuSection s_section;
static SimpleMenuSection s_confirm_section;
static SimpleMenuSection s_profile_section;

static Snapshot s_snapshot = {
  .state = STATE_UNAVAILABLE,
  .moving_time = UNAVAILABLE,
  .distance = UNAVAILABLE,
  .moving_distance = UNAVAILABLE,
  .current_speed = UNAVAILABLE,
  .average_speed = UNAVAILABLE,
  .max_speed = UNAVAILABLE,
  .altitude = UNAVAILABLE,
  .ascent = UNAVAILABLE,
  .descent = UNAVAILABLE,
  .vertical_speed = UNAVAILABLE,
  .slope = UNAVAILABLE,
  .avg_hr = UNAVAILABLE,
  .max_hr = UNAVAILABLE,
  .current_hr = UNAVAILABLE,
  .avg_cadence = UNAVAILABLE,
  .max_cadence = UNAVAILABLE,
  .avg_power = UNAVAILABLE,
  .max_power = UNAVAILABLE,
  .energy = UNAVAILABLE,
};
static Profile s_profiles[MAX_PROFILES];
static WatchConfig s_parsed_config;
static int s_profile_count;
static int s_selected;
static bool s_dark = true;
static bool s_german;
static bool s_snapshot_received;
static uint16_t s_snapshot_age;
static uint32_t s_uptime_seconds;

static char s_header_text[64] = "Connecting...";
static char s_notice[48];
static char s_value_text[MAX_SLOTS][24];
static time_t s_notice_until;

static char s_chunks[CONFIG_SIZE];
static char s_pending_chunks[CONFIG_SIZE];
static char s_config_work[CONFIG_SIZE];
static WatchConfigTransfer s_config_transfer = {.id = -1};
static bool s_config_durable_generation_seen;
static uint32_t s_transfer_last_activity;
static bool s_config_result_slot_reserved;
static bool s_pending_cleanup_required;

static char s_profile_chunks[WATCH_PROFILE_TRANSFER_BUFFER_SIZE];
static WatchProfileTransfer s_profile_transfer;
static bool s_profile_durable_generation_seen;
static bool s_profile_pending_ready;
static int s_profile_pending_result = RESULT_FAILED;

static ControlMessage s_control_queue[CONTROL_QUEUE_SIZE];
static uint8_t s_control_head;
static uint8_t s_control_count;
static CommandRecord s_command_records[COMMAND_RECORD_COUNT];
static uint32_t s_next_command_id;
static uint32_t s_session_id;

static bool s_outbox_busy;
static OutboundKind s_inflight_kind;
static uint8_t s_send_attempts[OUTBOUND_HEART_RATE + 1];
static AppTimer *s_retry_timer;
static size_t s_relay_offset;
static size_t s_relay_send_end;
static int s_relay_index;
static int s_relay_count;
static int32_t s_relay_id;
static int s_relay_result = RESULT_FAILED;
static bool s_request_profiles_after_relay;

static bool s_watch_hr_to_locus;
static bool s_health_subscribed;
static bool s_health_notice_shown;
static bool s_hr_pending;
static bool s_hr_prepared;
static bool s_last_hr_sent_valid;
static uint8_t s_heart_rate_interval = 5;
static uint32_t s_hr_sequence;
static uint32_t s_hr_generation;
static uint32_t s_hr_send_generation;
static uint32_t s_hr_send_sequence;
static uint32_t s_hr_send_epoch;
static int32_t s_pending_hr;
static int32_t s_hr_send_value;
#if defined(PBL_PLATFORM_EMERY)
static AppTimer *s_health_timeout;
static uint32_t s_last_hr_sent_uptime;
#endif

#if defined(PBL_MICROPHONE)
static DictationSession *s_dictation_session;
static AppTimer *s_dictation_send_timer;
static char s_waypoint_name[WAYPOINT_NAME_SIZE];
#endif

static void render(void);
static void apply_theme(void);
static void layout_slots(void);
static void update_health_subscription(void);
static void send_next(void);

static const char *tr(const char *english, const char *german) {
  return s_german ? german : english;
}

static void copy_text(char *destination, size_t size, const char *source) {
  if (!destination || !size) return;
  snprintf(destination, size, "%s", source ? source : "");
}

static void show_notice(const char *message, int seconds) {
  copy_text(s_notice, sizeof(s_notice), message);
  const time_t now = time(NULL);
  s_notice_until = now + seconds;
  render();
}

static bool tuple_signed_value(const Tuple *tuple, int32_t *output) {
  if (!tuple || !output || tuple->type != TUPLE_INT) return false;
  if (tuple->length == 1) {
    *output = tuple->value->int8;
    return true;
  }
  if (tuple->length == 2) {
    int16_t value;
    memcpy(&value, &tuple->value->int16, sizeof(value));
    *output = value;
    return true;
  }
  if (tuple->length == 4) {
    memcpy(output, &tuple->value->int32, sizeof(*output));
    return true;
  }
  return false;
}

static bool tuple_unsigned_value(const Tuple *tuple, uint32_t *output) {
  if (!tuple || !output || tuple->type != TUPLE_UINT) return false;
  if (tuple->length == 1) {
    *output = tuple->value->uint8;
    return true;
  }
  if (tuple->length == 2) {
    uint16_t value;
    memcpy(&value, &tuple->value->uint16, sizeof(value));
    *output = value;
    return true;
  }
  if (tuple->length == 4) {
    memcpy(output, &tuple->value->uint32, sizeof(*output));
    return true;
  }
  return false;
}

static bool dictionary_int32(DictionaryIterator *iterator, uint32_t key, int32_t *output) {
  Tuple *tuple = iterator ? dict_find(iterator, key) : NULL;
  if (tuple_signed_value(tuple, output)) return true;
  uint32_t value;
  if (!tuple_unsigned_value(tuple, &value) || value > INT32_MAX) return false;
  *output = (int32_t)value;
  return true;
}

// 0 is an absent legacy marker, 1 is the current durable generation, and -1 is malformed.
static int transfer_generation(DictionaryIterator *iterator) {
  if (!iterator || !dict_find(iterator, MESSAGE_KEY_TRANSFER_GENERATION)) return 0;
  int32_t generation = 0;
  return dictionary_int32(iterator, MESSAGE_KEY_TRANSFER_GENERATION, &generation) &&
      generation == DURABLE_TRANSFER_GENERATION ? 1 : -1;
}

static bool dictionary_uint32(DictionaryIterator *iterator, uint32_t key, uint32_t *output) {
  Tuple *tuple = iterator ? dict_find(iterator, key) : NULL;
  if (tuple_unsigned_value(tuple, output)) return true;
  int32_t value;
  if (!tuple_signed_value(tuple, &value) || value < 0) return false;
  *output = (uint32_t)value;
  return true;
}

static bool dictionary_cstring(
    DictionaryIterator *iterator,
    uint32_t key,
    size_t max_bytes,
    const char **output,
    size_t *length) {
  Tuple *tuple = iterator ? dict_find(iterator, key) : NULL;
  if (!tuple || tuple->type != TUPLE_CSTRING || tuple->length < 1 ||
      (size_t)tuple->length > max_bytes + 1) {
    return false;
  }
  const char *value = tuple->value->cstring;
  const char *terminator = memchr(value, '\0', tuple->length);
  if (terminator != value + tuple->length - 1) return false;
  if (output) *output = value;
  if (length) *length = tuple->length - 1;
  return true;
}

static bool read_persisted_string(uint32_t key, char *output, size_t output_size) {
  if (!output || output_size < 1) return false;
  const int size = persist_get_size(key);
  if (size < 1 || (size_t)size > output_size ||
      persist_read_string(key, output, output_size) != size) {
    output[0] = '\0';
    return false;
  }
  const char *terminator = memchr(output, '\0', (size_t)size);
  if (terminator != output + size - 1) {
    output[0] = '\0';
    return false;
  }
  return true;
}

static bool write_active_id(void) {
  if (s_selected < 0 || s_selected >= s_profile_count) return false;
  const char *id = s_profiles[s_selected].id;
  const size_t size = strlen(id) + 1;
  return size <= PERSIST_STRING_MAX_LENGTH && persist_write_string(PERSIST_ACTIVE_ID, id) == (int)size;
}

static void default_profiles(void) {
  memset(s_profiles, 0, sizeof(s_profiles));
  s_dark = true;
  s_selected = 0;
  s_profile_count = 2;
  s_watch_hr_to_locus = false;
  s_heart_rate_interval = 5;

  copy_text(s_profiles[0].name, sizeof(s_profiles[0].name), tr("Walking", "Gehen"));
  copy_text(s_profiles[0].locus, sizeof(s_profiles[0].locus), tr("Walking", "Gehen"));
  copy_text(s_profiles[0].id, sizeof(s_profiles[0].id), "default-walking");
  const uint8_t walking[] = {1, 3, 5, 6, 10, 22};
  memcpy(s_profiles[0].metrics, walking, sizeof(walking));
  s_profiles[0].count = sizeof(walking);

  copy_text(s_profiles[1].name, sizeof(s_profiles[1].name), tr("Cycling", "Radfahren"));
  copy_text(s_profiles[1].locus, sizeof(s_profiles[1].locus), tr("Cycling", "Radfahren"));
  copy_text(s_profiles[1].id, sizeof(s_profiles[1].id), "default-cycling");
  const uint8_t cycling[] = {1, 3, 5, 6, 7, 22};
  memcpy(s_profiles[1].metrics, cycling, sizeof(cycling));
  s_profiles[1].count = sizeof(cycling);

  char active[ID_SIZE] = "";
  read_persisted_string(PERSIST_ACTIVE_ID, active, sizeof(active));
  for (int i = 0; i < s_profile_count; i++) {
    if (strcmp(active, s_profiles[i].id) == 0) s_selected = i;
  }
}

static void close_secondary_windows(void) {
  if (s_confirm_window && window_stack_contains_window(s_confirm_window)) {
    window_stack_remove(s_confirm_window, false);
  }
  if (s_profile_window && window_stack_contains_window(s_profile_window)) {
    window_stack_remove(s_profile_window, false);
  }
  if (s_controls_window && window_stack_contains_window(s_controls_window)) {
    window_stack_remove(s_controls_window, false);
  }
}

static void install_config(const WatchConfig *config, bool persist_selection) {
  if (!config || config->profile_count < 1 || config->profile_count > MAX_PROFILES ||
      config->selected < 0 || config->selected >= config->profile_count) {
    return;
  }
  close_secondary_windows();
  memcpy(s_profiles, config->profiles, sizeof(s_profiles));
  s_profile_count = config->profile_count;
  s_selected = config->selected;
  s_dark = config->dark;
  s_watch_hr_to_locus = config->watch_hr_to_locus;
  s_heart_rate_interval = config->heart_rate_interval;
  if (persist_selection && !write_active_id()) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Failed to persist active profile");
  }
  layout_slots();
  apply_theme();
  update_health_subscription();
  render();
}

static bool parse_config_buffer(char *data, WatchConfig *output) {
  char active[ID_SIZE] = "";
  read_persisted_string(PERSIST_ACTIVE_ID, active, sizeof(active));
  return watch_config_parse(data, active, output);
}

static bool store_active_config(const char *data) {
  return data && persistent_blob_write(&s_config_blob, data, strlen(data));
}

static bool cleanup_pending_config(const char *failure_log) {
  if (persistent_blob_delete(&s_pending_config_blob)) {
    s_pending_cleanup_required = false;
    return true;
  }
  s_pending_cleanup_required = true;
  APP_LOG(APP_LOG_LEVEL_ERROR, "%s", failure_log);
  show_notice(tr("Config cleanup failed", "Konfig.-Bereinigung fehlgeschlagen"), 5);
  return false;
}

static bool prepare_pending_config_for_direct_apply(void) {
  if (s_pending_cleanup_required) {
    return cleanup_pending_config("Pending configuration cleanup retry failed");
  }
  if (!persistent_blob_exists(&s_pending_config_blob)) return true;
  if (!persistent_blob_read(&s_pending_config_blob, s_pending_chunks, sizeof(s_pending_chunks))) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Discarding unreadable pending configuration before direct apply");
    return cleanup_pending_config("Unreadable pending configuration cleanup failed");
  }
  copy_text(s_config_work, sizeof(s_config_work), s_pending_chunks);
  if (!parse_config_buffer(s_config_work, &s_parsed_config)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Discarding invalid pending configuration before direct apply");
    return cleanup_pending_config("Invalid pending configuration cleanup failed");
  }

  // A queued result made this configuration the confirmed baseline. Promote it before
  // deleting the pending copy so every persistence prefix retains either this value or
  // the incoming replacement. If cleanup fails, installing it also keeps RAM and the
  // active blob aligned while a retry prevents a later restart from rolling back.
  if (!store_active_config(s_pending_chunks)) {
    show_notice(tr("Config storage full", "Konfig.-Speicher voll"), 5);
    return false;
  }
  install_config(&s_parsed_config, true);
  return cleanup_pending_config("Promoted pending configuration cleanup failed");
}

static void apply_pending_config_if_stopped(void) {
  if (!s_snapshot_received || s_snapshot.state != STATE_STOPPED) return;
  if (s_pending_cleanup_required) {
    cleanup_pending_config("Pending configuration cleanup retry failed");
    return;
  }
  if (!persistent_blob_exists(&s_pending_config_blob)) return;
  if (!persistent_blob_read(&s_pending_config_blob, s_pending_chunks, sizeof(s_pending_chunks))) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Pending configuration is unreadable");
    if (cleanup_pending_config("Unreadable pending configuration cleanup failed")) {
      show_notice(tr("Config storage error", "Konfigurationsfehler"), 5);
    }
    return;
  }
  copy_text(s_config_work, sizeof(s_config_work), s_pending_chunks);
  if (!parse_config_buffer(s_config_work, &s_parsed_config)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Pending configuration is invalid");
    if (cleanup_pending_config("Invalid pending configuration cleanup failed")) {
      show_notice(tr("Invalid configuration", "Ungültige Konfiguration"), 5);
    }
    return;
  }
  if (!store_active_config(s_pending_chunks)) {
    show_notice(tr("Config storage full", "Konfig.-Speicher voll"), 5);
    return;
  }
  install_config(&s_parsed_config, true);
  cleanup_pending_config("Applied pending configuration but cleanup failed");
}

static void apply_theme(void) {
  const GColor background = s_dark ? GColorBlack : GColorWhite;
  const GColor foreground = s_dark ? GColorWhite : GColorBlack;
  if (s_main_window) window_set_background_color(s_main_window, background);
  if (s_controls_window) window_set_background_color(s_controls_window, background);
  if (s_confirm_window) window_set_background_color(s_confirm_window, background);
  if (s_profile_window) window_set_background_color(s_profile_window, background);
  if (s_status_bar) status_bar_layer_set_colors(s_status_bar, background, foreground);
  if (s_header) text_layer_set_text_color(s_header, foreground);
  for (int i = 0; i < MAX_SLOTS; i++) {
    if (s_labels[i]) text_layer_set_text_color(s_labels[i], foreground);
    if (s_value_layers[i]) text_layer_set_text_color(s_value_layers[i], foreground);
  }
  if (s_menu) {
    MenuLayer *menu = simple_menu_layer_get_menu_layer(s_menu);
    if (menu) {
      menu_layer_set_normal_colors(menu, background, foreground);
      menu_layer_set_highlight_colors(menu, foreground, background);
    }
  }
  if (s_confirm_menu) {
    MenuLayer *menu = simple_menu_layer_get_menu_layer(s_confirm_menu);
    if (menu) {
      menu_layer_set_normal_colors(menu, background, foreground);
      menu_layer_set_highlight_colors(menu, foreground, background);
    }
  }
  if (s_profile_menu) {
    MenuLayer *menu = simple_menu_layer_get_menu_layer(s_profile_menu);
    if (menu) {
      menu_layer_set_normal_colors(menu, background, foreground);
      menu_layer_set_highlight_colors(menu, foreground, background);
    }
  }
}

static const char *metric_label(int metric) {
  switch (metric) {
    case METRIC_ELAPSED: return tr("Elapsed", "Gesamtzeit");
    case METRIC_MOVING_TIME: return tr("Moving", "Bewegungszeit");
    case METRIC_DISTANCE: return tr("Distance", "Strecke");
    case METRIC_MOVING_DISTANCE: return tr("Move dist", "Bewegungsstr.");
    case METRIC_CURRENT_SPEED: return tr("Speed", "Tempo");
    case METRIC_AVERAGE_SPEED: return tr("Average", "Durchschnitt");
    case METRIC_MAX_SPEED: return tr("Max speed", "Max. Tempo");
    case METRIC_CURRENT_PACE: return tr("Pace", "Pace");
    case METRIC_AVERAGE_PACE: return tr("Avg pace", "Ø Pace");
    case METRIC_ALTITUDE: return tr("Altitude", "Höhe");
    case METRIC_ASCENT: return tr("Ascent", "Anstieg");
    case METRIC_DESCENT: return tr("Descent", "Abstieg");
    case METRIC_VERTICAL_SPEED: return tr("Vertical", "Vertikal");
    case METRIC_SLOPE: return tr("Slope", "Steigung");
    case METRIC_AVG_HR: return tr("Avg HR", "Ø Puls");
    case METRIC_MAX_HR: return tr("Max HR", "Max. Puls");
    case METRIC_AVG_CADENCE: return tr("Avg cadence", "Ø Frequenz");
    case METRIC_MAX_CADENCE: return tr("Max cadence", "Max. Frequenz");
    case METRIC_AVG_POWER: return tr("Avg power", "Ø Leistung");
    case METRIC_MAX_POWER: return tr("Max power", "Max. Leistung");
    case METRIC_ENERGY: return tr("Energy", "Energie");
    case METRIC_CURRENT_HR: return tr("Current HR", "Aktueller Puls");
    default: return "";
  }
}

static int32_t metric_value(int metric) {
  switch (metric) {
    case METRIC_MOVING_TIME: return s_snapshot.moving_time;
    case METRIC_DISTANCE: return s_snapshot.distance;
    case METRIC_MOVING_DISTANCE: return s_snapshot.moving_distance;
    case METRIC_CURRENT_SPEED:
    case METRIC_CURRENT_PACE: return s_snapshot.current_speed;
    case METRIC_AVERAGE_SPEED:
    case METRIC_AVERAGE_PACE: return s_snapshot.average_speed;
    case METRIC_MAX_SPEED: return s_snapshot.max_speed;
    case METRIC_ALTITUDE: return s_snapshot.altitude;
    case METRIC_ASCENT: return s_snapshot.ascent;
    case METRIC_DESCENT: return s_snapshot.descent;
    case METRIC_VERTICAL_SPEED: return s_snapshot.vertical_speed;
    case METRIC_SLOPE: return s_snapshot.slope;
    case METRIC_AVG_HR: return s_snapshot.avg_hr;
    case METRIC_MAX_HR: return s_snapshot.max_hr;
    case METRIC_AVG_CADENCE: return s_snapshot.avg_cadence;
    case METRIC_MAX_CADENCE: return s_snapshot.max_cadence;
    case METRIC_AVG_POWER: return s_snapshot.avg_power;
    case METRIC_MAX_POWER: return s_snapshot.max_power;
    case METRIC_ENERGY: return s_snapshot.energy;
    case METRIC_CURRENT_HR: return s_snapshot.current_hr;
    default: return UNAVAILABLE;
  }
}

static uint32_t magnitude(int32_t value) {
  return value < 0 ? (uint32_t)(-(int64_t)value) : (uint32_t)value;
}

static void format_fixed(
    char *output,
    size_t size,
    int32_t value,
    uint32_t scale,
    uint8_t digits,
    const char *suffix) {
  const uint32_t absolute = magnitude(value);
  uint32_t divisor = 1;
  for (uint8_t i = 0; i < digits; i++) divisor *= 10;
  const uint32_t fraction = (absolute % scale) * divisor / scale;
  if (digits == 1) {
    snprintf(output, size, "%s%lu.%01lu%s", value < 0 ? "-" : "",
             (unsigned long)(absolute / scale), (unsigned long)fraction, suffix);
  } else {
    snprintf(output, size, "%s%lu.%02lu%s", value < 0 ? "-" : "",
             (unsigned long)(absolute / scale), (unsigned long)fraction, suffix);
  }
}

static void format_time(char *output, size_t size, uint32_t seconds) {
  snprintf(output, size, "%02lu:%02lu:%02lu",
           (unsigned long)(seconds / 3600),
           (unsigned long)((seconds / 60) % 60),
           (unsigned long)(seconds % 60));
}

static bool nonnegative_metric(int metric) {
  return metric == METRIC_MOVING_TIME ||
      (metric >= METRIC_DISTANCE && metric <= METRIC_AVERAGE_PACE) ||
      metric == METRIC_ASCENT || metric == METRIC_DESCENT ||
      (metric >= METRIC_AVG_HR && metric <= METRIC_CURRENT_HR);
}

static void format_metric(char *output, size_t size, int metric, uint32_t elapsed) {
  if (metric == METRIC_ELAPSED) {
    format_time(output, size, elapsed);
    return;
  }
  const int32_t value = metric_value(metric);
  if (value == UNAVAILABLE || (nonnegative_metric(metric) && value < 0)) {
    copy_text(output, size, "—");
    return;
  }
  if (metric == METRIC_MOVING_TIME) {
    format_time(output, size, (uint32_t)value);
  } else if (metric == METRIC_DISTANCE || metric == METRIC_MOVING_DISTANCE) {
    format_fixed(output, size, value, 1000, 2, " km");
  } else if (metric >= METRIC_CURRENT_SPEED && metric <= METRIC_MAX_SPEED) {
    const int32_t tenths_kph = (int32_t)(((int64_t)value * 36) / 100);
    format_fixed(output, size, tenths_kph, 10, 1, " km/h");
  } else if (metric == METRIC_CURRENT_PACE || metric == METRIC_AVERAGE_PACE) {
    if (value <= 0) {
      copy_text(output, size, "—");
    } else {
      const int seconds = 100000 / value;
      snprintf(output, size, "%d:%02d /km", seconds / 60, seconds % 60);
    }
  } else if (metric >= METRIC_ALTITUDE && metric <= METRIC_DESCENT) {
    format_fixed(output, size, value, 10, 1, " m");
  } else if (metric == METRIC_VERTICAL_SPEED) {
    format_fixed(output, size, value, 100, 2, " m/s");
  } else if (metric == METRIC_SLOPE) {
    format_fixed(output, size, value, 10, 1, "%");
  } else if (metric == METRIC_AVG_HR || metric == METRIC_MAX_HR ||
             metric == METRIC_CURRENT_HR) {
    snprintf(output, size, "%ld bpm", (long)value);
  } else if (metric == METRIC_AVG_CADENCE || metric == METRIC_MAX_CADENCE) {
    snprintf(output, size, "%ld rpm", (long)value);
  } else if (metric == METRIC_AVG_POWER || metric == METRIC_MAX_POWER) {
    snprintf(output, size, "%ld W", (long)value);
  } else {
    snprintf(output, size, "%ld kcal", (long)value);
  }
}

static void layout_slots(void) {
  if (!s_main_window || s_selected < 0 || s_selected >= s_profile_count ||
      s_profiles[s_selected].count < 1 || s_profiles[s_selected].count > MAX_SLOTS) {
    return;
  }
  Layer *root = window_get_root_layer(s_main_window);
  if (!root) return;
  const GRect bounds = layer_get_bounds(root);
  const int count = s_profiles[s_selected].count;
  int top = STATUS_BAR_LAYER_HEIGHT + 25;
  int inset = 0;
#ifdef PBL_ROUND
  inset = 24;
  top += 6;
#endif
  const int width = bounds.size.w - 2 * inset;
  const int height = bounds.size.h - top - PBL_IF_ROUND_ELSE(18, 0);
  for (int i = 0; i < MAX_SLOTS; i++) {
    if (!s_labels[i] || !s_value_layers[i]) continue;
    const bool visible = i < count;
    layer_set_hidden(text_layer_get_layer(s_labels[i]), !visible);
    layer_set_hidden(text_layer_get_layer(s_value_layers[i]), !visible);
    if (!visible) continue;

    int row;
    int column;
    int cell_width;
    int row_height;
    int x;
    int y;
    if (count <= 3) {
      row = i;
      column = 0;
      cell_width = width;
      row_height = height / count;
      x = inset;
      y = top + row * row_height;
    } else if (count == 5 && i == 0) {
      row = 0;
      column = 0;
      cell_width = width;
      row_height = height / 3;
      x = inset;
      y = top;
    } else {
      const int slot = count == 5 ? i - 1 : i;
      row = (count == 5 ? 1 : 0) + slot / 2;
      column = slot % 2;
      cell_width = width / 2;
      row_height = height / (count == 4 ? 2 : 3);
      x = inset + column * cell_width;
      y = top + row * row_height;
    }
    layer_set_frame(text_layer_get_layer(s_labels[i]), GRect(x, y, cell_width, row_height / 2));
    layer_set_frame(
        text_layer_get_layer(s_value_layers[i]),
        GRect(x, y + row_height / 3, cell_width, row_height * 2 / 3));
    text_layer_set_text(s_labels[i], metric_label(s_profiles[s_selected].metrics[i]));
  }
}

static void render(void) {
  if (!s_header || s_selected < 0 || s_selected >= s_profile_count) return;
  const time_t now = time(NULL);
  const bool stale = !s_snapshot_received || s_snapshot_age > SNAPSHOT_STALE_SECONDS;
  const char *state = s_snapshot.state == STATE_RECORDING ? tr("Recording", "Aufzeichnung") :
      s_snapshot.state == STATE_PAUSED ? tr("Paused", "Pausiert") :
      s_snapshot.state == STATE_STOPPED ? tr("Stopped", "Gestoppt") :
      tr("No Locus", "Kein Locus");
  const bool showing_notice = now < s_notice_until;
  snprintf(s_header_text, sizeof(s_header_text), "%s%s",
           showing_notice ? s_notice : state,
           !showing_notice && stale ? tr(" | stale", " | veraltet") : "");
  text_layer_set_text(s_header, s_header_text);

  uint32_t elapsed = s_snapshot.elapsed;
  if (!stale && s_snapshot.state == STATE_RECORDING) {
    elapsed = UINT32_MAX - elapsed < s_snapshot_age ? UINT32_MAX : elapsed + s_snapshot_age;
  }
  const int count = s_profiles[s_selected].count;
  for (int i = 0; i < count && i < MAX_SLOTS; i++) {
    format_metric(s_value_text[i], sizeof(s_value_text[i]), s_profiles[s_selected].metrics[i], elapsed);
    if (s_value_layers[i]) text_layer_set_text(s_value_layers[i], s_value_text[i]);
  }
}

static ControlMessage *control_head(void) {
  return s_control_count ? &s_control_queue[s_control_head] : NULL;
}

static void control_pop(void) {
  if (!s_control_count) return;
  memset(&s_control_queue[s_control_head], 0, sizeof(s_control_queue[s_control_head]));
  s_control_head = (uint8_t)((s_control_head + 1) % CONTROL_QUEUE_SIZE);
  s_control_count--;
}

static bool control_contains_type(int type) {
  for (uint8_t i = 0; i < s_control_count; i++) {
    const uint8_t index = (uint8_t)((s_control_head + i) % CONTROL_QUEUE_SIZE);
    if (s_control_queue[index].type == type) return true;
  }
  return false;
}

static bool control_enqueue(int type, int command, uint32_t command_id, const char *text) {
  const bool coalescible = type == MSG_REQUEST_SNAPSHOT || type == MSG_REQUEST_PROFILE_LIST;
  if (coalescible && control_contains_type(type)) return true;
  const uint8_t limit = CONTROL_QUEUE_SIZE - (s_config_result_slot_reserved ? 1 : 0);
  if (s_control_count >= limit) return false;
  const uint8_t index = (uint8_t)((s_control_head + s_control_count) % CONTROL_QUEUE_SIZE);
  ControlMessage *message = &s_control_queue[index];
  memset(message, 0, sizeof(*message));
  message->type = type;
  message->command = command;
  message->command_id = command_id;
  copy_text(message->text, sizeof(message->text), text);
  s_control_count++;
  return true;
}

static bool control_enqueue_config_result(int32_t transfer_id, int result) {
  if (!s_config_result_slot_reserved || s_control_count >= CONTROL_QUEUE_SIZE) {
    return false;
  }
  const uint8_t index = (uint8_t)((s_control_head + s_control_count) % CONTROL_QUEUE_SIZE);
  ControlMessage *message = &s_control_queue[index];
  memset(message, 0, sizeof(*message));
  message->type = MSG_CONFIG_RESULT;
  message->transfer_id = transfer_id;
  message->result = result;
  s_control_count++;
  s_config_result_slot_reserved = false;
  return true;
}

static CommandRecord *command_record_find(uint32_t command_id) {
  for (int i = 0; i < COMMAND_RECORD_COUNT; i++) {
    if (s_command_records[i].used && s_command_records[i].command_id == command_id) {
      return &s_command_records[i];
    }
  }
  return NULL;
}

static bool command_record_add(uint32_t command_id, int command) {
  if (!command_id) return false;
  for (int i = 0; i < COMMAND_RECORD_COUNT; i++) {
    if (!s_command_records[i].used) {
      s_command_records[i].used = true;
      s_command_records[i].awaiting_result = false;
      s_command_records[i].command_id = command_id;
      s_command_records[i].result_deadline = 0;
      s_command_records[i].command = command;
      return true;
    }
  }
  return false;
}

static bool command_record_remove(uint32_t command_id) {
  CommandRecord *record = command_record_find(command_id);
  if (!record) return false;
  memset(record, 0, sizeof(*record));
  return true;
}

static void command_record_mark_delivered(uint32_t command_id) {
  CommandRecord *record = command_record_find(command_id);
  if (!record) return;
  record->awaiting_result = true;
  record->result_deadline = s_uptime_seconds + COMMAND_RESULT_TIMEOUT_SECONDS;
}

static bool deadline_reached(uint32_t now, uint32_t deadline) {
  return (int32_t)(now - deadline) >= 0;
}

static bool expire_command_records(void) {
  bool expired = false;
  for (int i = 0; i < COMMAND_RECORD_COUNT; i++) {
    CommandRecord *record = &s_command_records[i];
    if (record->used && record->awaiting_result &&
        deadline_reached(s_uptime_seconds, record->result_deadline)) {
      memset(record, 0, sizeof(*record));
      expired = true;
    }
  }
  return expired;
}

static uint32_t next_command_id(void) {
  for (int i = 0; i <= COMMAND_RECORD_COUNT; i++) {
    const uint32_t value = s_next_command_id;
    s_next_command_id++;
    if (!s_next_command_id) s_next_command_id = 1;
    if (value && !command_record_find(value)) return value;
  }
  return 0;
}

static bool write_common(DictionaryIterator *iterator, int type) {
  return iterator &&
      dict_write_int32(iterator, MESSAGE_KEY_PROTOCOL_VERSION, PROTOCOL_VERSION) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_MESSAGE_TYPE, type) == DICT_OK &&
      dict_write_cstring(iterator, MESSAGE_KEY_APP_VERSION, RELEASE_VERSION) == DICT_OK;
}

static AppMessageResult send_control_packet(const ControlMessage *message) {
  if (!message) return APP_MSG_INVALID_ARGS;
  DictionaryIterator *iterator = NULL;
  AppMessageResult result = app_message_outbox_begin(&iterator);
  if (result != APP_MSG_OK) return result;
  bool valid = write_common(iterator, message->type);
  if (valid && message->type == MSG_COMMAND) {
    valid = dict_write_uint32(iterator, MESSAGE_KEY_COMMAND_ID, message->command_id) == DICT_OK &&
        dict_write_uint32(iterator, MESSAGE_KEY_SESSION_ID, s_session_id) == DICT_OK &&
        dict_write_int32(iterator, MESSAGE_KEY_COMMAND, message->command) == DICT_OK;
    if (valid && (message->command == CMD_START ||
                  message->command == CMD_ADD_WAYPOINT_WITH_NOTE)) {
      const uint32_t key = message->command == CMD_START ?
          MESSAGE_KEY_LOCUS_PROFILE_NAME : MESSAGE_KEY_WAYPOINT_NAME;
      valid = dict_write_cstring(iterator, key, message->text) == DICT_OK;
    }
  } else if (valid && message->type == MSG_CONFIG_RESULT) {
    valid = dict_write_int32(iterator, MESSAGE_KEY_TRANSFER_ID, message->transfer_id) == DICT_OK &&
        dict_write_int32(iterator, MESSAGE_KEY_RESULT, message->result) == DICT_OK;
  }
  if (!valid) return APP_MSG_BUFFER_OVERFLOW;
  return app_message_outbox_send();
}

static AppMessageResult send_relay_packet(void) {
  const size_t length = strlen(s_profile_chunks);
  if (s_relay_index >= s_relay_count || s_relay_offset > length) return APP_MSG_INVALID_STATE;
  size_t end = s_relay_offset + PROFILE_CHUNK_BYTES;
  if (end > length) end = length;
  while (end > s_relay_offset && end < length &&
         (((uint8_t)s_profile_chunks[end] & 0xc0) == 0x80)) {
    end--;
  }
  if (end == s_relay_offset && end < length) return APP_MSG_INVALID_ARGS;

  char part[PROFILE_CHUNK_BYTES + 1];
  const size_t part_length = end - s_relay_offset;
  memcpy(part, s_profile_chunks + s_relay_offset, part_length);
  part[part_length] = '\0';

  DictionaryIterator *iterator = NULL;
  AppMessageResult result = app_message_outbox_begin(&iterator);
  if (result != APP_MSG_OK) return result;
  const bool valid = write_common(iterator, MSG_PROFILE_LIST_CHUNK) &&
      dict_write_int32(iterator, MESSAGE_KEY_RESULT, s_relay_result) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_TRANSFER_ID, s_relay_id) == DICT_OK &&
      dict_write_int32(
          iterator,
          MESSAGE_KEY_TRANSFER_GENERATION,
          DURABLE_TRANSFER_GENERATION) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_CHUNK_INDEX, s_relay_index) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_CHUNK_COUNT, s_relay_count) == DICT_OK &&
      dict_write_cstring(iterator, MESSAGE_KEY_CHUNK_DATA, part) == DICT_OK;
  if (!valid) return APP_MSG_BUFFER_OVERFLOW;
  result = app_message_outbox_send();
  if (result == APP_MSG_OK) s_relay_send_end = end;
  return result;
}

static void prepare_heart_rate(void) {
  if (s_hr_prepared || !s_hr_pending) return;
  s_hr_prepared = true;
  s_hr_send_generation = s_hr_generation;
  s_hr_send_sequence = s_hr_sequence++;
  s_hr_send_epoch = (uint32_t)time(NULL);
  s_hr_send_value = s_pending_hr;
}

static AppMessageResult send_heart_rate_packet(void) {
  prepare_heart_rate();
  if (!s_hr_prepared) return APP_MSG_INVALID_STATE;
  DictionaryIterator *iterator = NULL;
  AppMessageResult result = app_message_outbox_begin(&iterator);
  if (result != APP_MSG_OK) return result;
  const bool valid = write_common(iterator, MSG_HEART_RATE) &&
      dict_write_uint32(iterator, MESSAGE_KEY_SESSION_ID, s_session_id) == DICT_OK &&
      dict_write_uint32(iterator, MESSAGE_KEY_HEART_RATE_SEQUENCE, s_hr_send_sequence) == DICT_OK &&
      dict_write_uint32(iterator, MESSAGE_KEY_SAMPLE_EPOCH_SECONDS, s_hr_send_epoch) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_CURRENT_HEART_RATE, s_hr_send_value) == DICT_OK;
  if (!valid) return APP_MSG_BUFFER_OVERFLOW;
  return app_message_outbox_send();
}

static void retry_send(void *context) {
  s_retry_timer = NULL;
  send_next();
}

static void finish_relay(void);

static void drop_outbound(OutboundKind kind) {
  if (kind == OUTBOUND_CONTROL) {
    ControlMessage *message = control_head();
    if (message && message->type == MSG_COMMAND && command_record_remove(message->command_id)) {
      show_notice(tr("Phone delivery failed", "Telefon nicht erreichbar"), 5);
    }
    control_pop();
  } else if (kind == OUTBOUND_RELAY) {
    finish_relay();
  } else if (kind == OUTBOUND_HEART_RATE) {
    if (s_hr_pending && s_hr_generation == s_hr_send_generation) s_hr_pending = false;
    s_hr_prepared = false;
  }
}

static void handle_send_failure(OutboundKind kind, AppMessageResult reason) {
  if (kind <= OUTBOUND_NONE || kind > OUTBOUND_HEART_RATE) return;
  uint8_t *attempts = &s_send_attempts[kind];
  APP_LOG(APP_LOG_LEVEL_WARNING, "AppMessage failure kind=%d reason=%d attempt=%d",
          (int)kind, (int)reason, (int)*attempts + 1);
  s_outbox_busy = false;
  s_inflight_kind = OUTBOUND_NONE;
  if (kind == OUTBOUND_HEART_RATE && !s_hr_prepared) {
    *attempts = 0;
    send_next();
    return;
  }
  (*attempts)++;
  if (*attempts >= MAX_SEND_ATTEMPTS) {
    *attempts = 0;
    drop_outbound(kind);
    send_next();
    return;
  }
  const uint32_t delay = 250u << (*attempts - 1);
  if (s_retry_timer) app_timer_cancel(s_retry_timer);
  s_retry_timer = app_timer_register(delay, retry_send, NULL);
  if (!s_retry_timer) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Could not allocate AppMessage retry timer");
    *attempts = 0;
    drop_outbound(kind);
    send_next();
  }
}

static void send_next(void) {
  if (s_outbox_busy || s_retry_timer) return;
  AppMessageResult result;
  if (s_control_count) {
    s_inflight_kind = OUTBOUND_CONTROL;
    result = send_control_packet(control_head());
  } else if (s_relay_index < s_relay_count) {
    s_inflight_kind = OUTBOUND_RELAY;
    result = send_relay_packet();
  } else if (s_hr_pending || s_hr_prepared) {
    s_inflight_kind = OUTBOUND_HEART_RATE;
    result = send_heart_rate_packet();
  } else {
    s_inflight_kind = OUTBOUND_NONE;
    return;
  }
  if (result == APP_MSG_OK) {
    s_outbox_busy = true;
  } else {
    const OutboundKind failed = s_inflight_kind;
    handle_send_failure(failed, result);
  }
}

static void relay_pending_profile_list(void);

static void reset_profile_transfer(void) {
  watch_profile_transfer_reset(&s_profile_transfer);
}

static void enqueue_deferred_profile_request(void) {
  if (!s_request_profiles_after_relay || s_relay_count ||
      s_profile_transfer.id >= 0 || s_profile_pending_ready) {
    return;
  }
  if (control_enqueue(MSG_REQUEST_PROFILE_LIST, 0, 0, NULL)) {
    s_request_profiles_after_relay = false;
  } else {
    show_notice(tr("Message queue full", "Nachrichtenwarteschl. voll"), 4);
  }
}

static void finish_relay(void) {
  s_relay_offset = 0;
  s_relay_send_end = 0;
  s_relay_index = 0;
  s_relay_count = 0;
  if (s_profile_pending_ready) relay_pending_profile_list();
  if (s_relay_count) {
    s_request_profiles_after_relay = false;
  } else {
    enqueue_deferred_profile_request();
  }
}

static void outbox_sent(DictionaryIterator *iterator, void *context) {
  const OutboundKind completed = s_inflight_kind;
  s_outbox_busy = false;
  s_inflight_kind = OUTBOUND_NONE;
  if (completed > OUTBOUND_NONE && completed <= OUTBOUND_HEART_RATE) {
    s_send_attempts[completed] = 0;
  }
  if (completed == OUTBOUND_CONTROL) {
    ControlMessage *message = control_head();
    if (message && message->type == MSG_COMMAND) {
      command_record_mark_delivered(message->command_id);
    }
    control_pop();
  } else if (completed == OUTBOUND_RELAY) {
    s_relay_offset = s_relay_send_end;
    s_relay_index++;
    if (s_relay_index >= s_relay_count) finish_relay();
  } else if (completed == OUTBOUND_HEART_RATE) {
    if (s_hr_pending && s_hr_generation == s_hr_send_generation) s_hr_pending = false;
    s_hr_prepared = false;
  }
  send_next();
}

static void outbox_failed(
    DictionaryIterator *iterator,
    AppMessageResult reason,
    void *context) {
  handle_send_failure(s_inflight_kind, reason);
}

static void inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Inbox dropped: %d", (int)reason);
  show_notice(tr("Phone message dropped", "Telefonnachricht verloren"), 4);
}

static void relay_profile_list(void) {
  const size_t length = strlen(s_profile_chunks);
  size_t offset = 0;
  int count = 0;
  do {
    size_t end = offset + PROFILE_CHUNK_BYTES;
    if (end > length) end = length;
    while (end > offset && end < length &&
           (((uint8_t)s_profile_chunks[end] & 0xc0) == 0x80)) {
      end--;
    }
    if (end == offset && end < length) {
      APP_LOG(APP_LOG_LEVEL_ERROR, "Invalid UTF-8 relay boundary");
      return;
    }
    offset = end;
    count++;
  } while (offset < length);
  int32_t transfer_id = -1;
  if (!watch_transfer_serial_reserve_persistent(
          PERSIST_RELAY_TRANSFER_COUNTER,
          0,
          &transfer_id)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Failed to reserve profile relay transfer ID");
    show_notice(tr("Profile relay unavailable", "Profilübertr. nicht verfügbar"), 5);
    return;
  }
  s_relay_offset = 0;
  s_relay_send_end = 0;
  s_relay_index = 0;
  s_relay_count = count;
  s_relay_id = transfer_id;
}

static void relay_pending_profile_list(void) {
  if (!s_profile_pending_ready || s_relay_count) return;
  size_t length = 0;
  if (!watch_profile_transfer_join(
          &s_profile_transfer,
          s_profile_chunks,
          sizeof(s_profile_chunks),
          PROFILE_LIST_SIZE - 1,
          &length) ||
      (s_profile_pending_result == RESULT_OK) != (length > 0) ||
      !watch_profile_list_valid(s_profile_chunks, length)) {
    s_profile_pending_ready = false;
    reset_profile_transfer();
    show_notice(tr("Invalid profile list", "Ungültige Profilliste"), 4);
    enqueue_deferred_profile_request();
    return;
  }

  if (!persistent_blob_write(&s_profile_list_blob, s_profile_chunks, length)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Profile list cache could not be persisted");
  }
  s_relay_result = s_profile_pending_result;
  s_profile_pending_ready = false;
  reset_profile_transfer();
  relay_profile_list();
  if (s_relay_count) {
    s_request_profiles_after_relay = false;
  } else {
    enqueue_deferred_profile_request();
  }
}

static void complete_profile_transfer(void) {
  s_profile_pending_result = s_profile_transfer.result;
  s_profile_pending_ready = true;
  s_profile_transfer.id = -1;
  relay_pending_profile_list();
}

static void accept_profile_chunk(DictionaryIterator *iterator) {
  int32_t id;
  int32_t index;
  int32_t count;
  int32_t result;
  const char *data;
  size_t length;
  const int generation = transfer_generation(iterator);
  if (generation < 0 || (s_profile_durable_generation_seen && generation != 1)) return;
  if (s_relay_count || s_profile_pending_ready) {
    s_request_profiles_after_relay = true;
    return;
  }
  if (!dictionary_int32(iterator, MESSAGE_KEY_TRANSFER_ID, &id) || id < 0) return;
  if (!dictionary_int32(iterator, MESSAGE_KEY_CHUNK_INDEX, &index) ||
      !dictionary_int32(iterator, MESSAGE_KEY_CHUNK_COUNT, &count) ||
      !dictionary_int32(iterator, MESSAGE_KEY_RESULT, &result) ||
      count < 1 || count > PROFILE_MAX_CHUNKS || index < 0 || index >= count ||
      (result != RESULT_OK && result != RESULT_FAILED) ||
      !dictionary_cstring(iterator, MESSAGE_KEY_CHUNK_DATA, PROFILE_CHUNK_BYTES, &data, &length)) {
    if (id == s_profile_transfer.id) {
      reset_profile_transfer();
      enqueue_deferred_profile_request();
      send_next();
    }
    return;
  }
  if (generation == 1 && !s_profile_durable_generation_seen) {
    if (index != 0) return;
    watch_profile_transfer_initialize(&s_profile_transfer);
    s_profile_durable_generation_seen = true;
  }
  const WatchProfileTransferOutcome outcome = watch_profile_transfer_accept(
      &s_profile_transfer,
      s_profile_chunks,
      sizeof(s_profile_chunks),
      id,
      index,
      count,
      result,
      data,
      length,
      s_uptime_seconds);
  if (outcome == WATCH_PROFILE_TRANSFER_COMPLETE) {
    complete_profile_transfer();
    send_next();
  } else if (outcome == WATCH_PROFILE_TRANSFER_INVALID) {
    enqueue_deferred_profile_request();
    send_next();
  }
}

static void reset_config_transfer(void) {
  watch_config_transfer_reset(&s_config_transfer);
  s_transfer_last_activity = 0;
  s_config_result_slot_reserved = false;
  s_chunks[0] = '\0';
}

static bool begin_config_transfer(void) {
  if (s_control_count >= CONTROL_QUEUE_SIZE) return false;
  reset_config_transfer();
  s_config_result_slot_reserved = true;
  s_transfer_last_activity = s_uptime_seconds;
  return true;
}

static void accept_config_chunk(DictionaryIterator *iterator) {
  int32_t id;
  int32_t index;
  int32_t count;
  const char *data;
  size_t length;
  const int generation = transfer_generation(iterator);
  if (generation < 0 || (s_config_durable_generation_seen && generation != 1)) return;
  if (!dictionary_int32(iterator, MESSAGE_KEY_TRANSFER_ID, &id) || id < 0) return;
  if (!dictionary_int32(iterator, MESSAGE_KEY_CHUNK_INDEX, &index) ||
      !dictionary_int32(iterator, MESSAGE_KEY_CHUNK_COUNT, &count) ||
      count < 1 || count > CONFIG_MAX_CHUNKS || index < 0 || index >= count ||
      !dictionary_cstring(iterator, MESSAGE_KEY_CHUNK_DATA, CONFIG_CHUNK_BYTES, &data, &length)) {
    if (generation == 1 && !s_config_durable_generation_seen) return;
    if (id == s_config_transfer.id) reset_config_transfer();
    return;
  }

  if (generation == 1 && !s_config_durable_generation_seen) {
    if (index != 0 || !begin_config_transfer()) return;
    watch_config_transfer_initialize(&s_config_transfer);
    s_config_durable_generation_seen = true;
  } else if (index == 0 && (s_config_transfer.id < 0 || id != s_config_transfer.id)) {
    if (!watch_config_transfer_may_start(&s_config_transfer, id)) return;
    if (!begin_config_transfer()) return;
  }
  const WatchTransferOutcome outcome = watch_config_transfer_accept(
      &s_config_transfer,
      s_chunks,
      sizeof(s_chunks),
      id,
      index,
      count,
      data,
      length);
  if (outcome == WATCH_TRANSFER_IGNORED) return;
  if (outcome == WATCH_TRANSFER_INVALID) {
    reset_config_transfer();
    return;
  }
  s_transfer_last_activity = s_uptime_seconds;
  if (outcome == WATCH_TRANSFER_DUPLICATE || outcome == WATCH_TRANSFER_ACCEPTED) {
    return;
  }

  int result = RESULT_CONFIG_APPLIED;
  copy_text(s_config_work, sizeof(s_config_work), s_chunks);
  const bool valid = parse_config_buffer(s_config_work, &s_parsed_config);
  if (!valid) {
    result = RESULT_INVALID_CONFIG;
    show_notice(tr("Invalid configuration", "Ungültige Konfiguration"), 5);
  } else if (s_snapshot_received && s_snapshot_age <= SNAPSHOT_STALE_SECONDS &&
             s_snapshot.state == STATE_STOPPED) {
    if (!prepare_pending_config_for_direct_apply()) {
      result = RESULT_STORAGE_FAILED;
    } else {
      // Reparse after reconciliation because promoting a queued baseline may update the
      // stable active-profile ID used to select a profile in the incoming configuration.
      copy_text(s_config_work, sizeof(s_config_work), s_chunks);
      if (!parse_config_buffer(s_config_work, &s_parsed_config)) {
        result = RESULT_INVALID_CONFIG;
        APP_LOG(
            APP_LOG_LEVEL_ERROR,
            "Validated configuration failed to reparse after reconciliation");
        show_notice(tr("Invalid configuration", "Ungültige Konfiguration"), 5);
      } else if (store_active_config(s_chunks)) {
        install_config(&s_parsed_config, true);
      } else {
        result = RESULT_STORAGE_FAILED;
        show_notice(tr("Config storage full", "Konfig.-Speicher voll"), 5);
      }
    }
  } else if (!persistent_blob_write(
          &s_pending_config_blob, s_chunks, s_config_transfer.length)) {
    result = RESULT_STORAGE_FAILED;
    show_notice(tr("Config storage full", "Konfig.-Speicher voll"), 5);
  } else {
    s_pending_cleanup_required = false;
    result = RESULT_CONFIG_QUEUED;
    show_notice(tr("Config queued until stop", "Konfig. nach dem Stopp"), 4);
  }
  if (!control_enqueue_config_result(id, result)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Reserved config-result slot was unavailable");
    show_notice(tr("Message queue error", "Nachrichtenfehler"), 5);
  }
  reset_config_transfer();
  send_next();
}

static void close_controls(void) {
  if (s_controls_window && window_stack_contains_window(s_controls_window)) {
    window_stack_remove(s_controls_window, true);
  }
}

static void send_command(int command) {
  if (s_selected < 0 || s_selected >= s_profile_count) return;
  const uint32_t command_id = next_command_id();
  const char *text = NULL;
  if (command == CMD_START) text = s_profiles[s_selected].locus;
#if defined(PBL_MICROPHONE)
  if (command == CMD_ADD_WAYPOINT_WITH_NOTE) text = s_waypoint_name;
#endif
  if (!command_id || !command_record_add(command_id, command) ||
      !control_enqueue(MSG_COMMAND, command, command_id, text)) {
    command_record_remove(command_id);
    show_notice(tr("Command queue full", "Befehlswarteschl. voll"), 4);
    close_controls();
    return;
  }
  show_notice(tr("Sending...", "Senden..."), 5);
  send_next();
  close_controls();
}

static void confirm_selected(int index, void *context) {
  if (index == 0) {
    window_stack_pop(false);
    send_command(CMD_STOP_SAVE);
  } else {
    window_stack_pop(true);
  }
}

static void profile_choice_selected(int index, void *context) {
  if (index < 0 || index >= s_profile_count) {
    close_secondary_windows();
    return;
  }
  if (s_snapshot.state == STATE_RECORDING || s_snapshot.state == STATE_PAUSED) {
    show_notice(tr("Stop to change profile", "Zum Wechseln stoppen"), 4);
  } else {
    s_selected = index;
    if (!write_active_id()) show_notice(tr("Profile was not saved", "Profil nicht gespeichert"), 4);
    layout_slots();
    render();
  }
  if (s_profile_window && window_stack_contains_window(s_profile_window)) window_stack_pop(true);
  if (s_controls_window && window_stack_contains_window(s_controls_window)) window_stack_pop(true);
}

static void profile_selected(int index, void *context) {
  if (s_profile_window) window_stack_push(s_profile_window, true);
}

static void recording_selected(int index, void *context) {
  send_command(s_snapshot.state == STATE_STOPPED ? CMD_START : CMD_PAUSE_RESUME);
}

static void stop_selected(int index, void *context) {
  if (s_confirm_window) window_stack_push(s_confirm_window, true);
}

static void waypoint_selected(int index, void *context) {
  send_command(CMD_ADD_WAYPOINT);
}

#if defined(PBL_MICROPHONE)
static void show_dictation_failure(DictationSessionStatus status) {
  const char *message;
  if (status == DictationSessionStatusFailureConnectivityError) {
    message = tr("No phone/internet", "Kein Telefon/Internet");
  } else if (status == DictationSessionStatusFailureDisabled) {
    message = tr("Dictation disabled", "Diktat deaktiviert");
  } else if (status == DictationSessionStatusFailureNoSpeechDetected) {
    message = tr("No speech detected", "Keine Sprache erkannt");
  } else {
    message = tr("Dictation failed", "Diktat fehlgeschlagen");
  }
  show_notice(message, 4);
  close_controls();
}

static void send_dictated_waypoint(void *context) {
  s_dictation_send_timer = NULL;
  send_command(CMD_ADD_WAYPOINT_WITH_NOTE);
}

static void dictation_callback(
    DictationSession *session,
    DictationSessionStatus status,
    char *transcription,
    void *context) {
  APP_LOG(APP_LOG_LEVEL_INFO, "Dictation status %d", (int)status);
  if (status == DictationSessionStatusSuccess) {
    if (!watch_waypoint_name_valid(transcription)) {
      show_dictation_failure(DictationSessionStatusFailureRecognizerError);
      return;
    }
    copy_text(s_waypoint_name, sizeof(s_waypoint_name), transcription);
    if (s_dictation_send_timer) app_timer_cancel(s_dictation_send_timer);
    s_dictation_send_timer = app_timer_register(1, send_dictated_waypoint, NULL);
    if (!s_dictation_send_timer) show_dictation_failure(DictationSessionStatusFailureInternalError);
  } else if (status != DictationSessionStatusFailureTranscriptionRejected) {
    show_dictation_failure(status);
  }
}

static void dictated_waypoint_selected(int index, void *context) {
  if (!s_dictation_session) {
    s_dictation_session = dictation_session_create(
        WAYPOINT_NAME_SIZE, dictation_callback, NULL);
  }
  if (!s_dictation_session) {
    show_notice(tr("Dictation unavailable", "Diktat nicht verfügbar"), 4);
    close_controls();
    return;
  }
  dictation_session_enable_confirmation(s_dictation_session, true);
  dictation_session_enable_error_dialogs(s_dictation_session, true);
  const DictationSessionStatus status = dictation_session_start(s_dictation_session);
  if (status != DictationSessionStatusSuccess) show_dictation_failure(status);
}
#endif

static bool rebuild_menu(void) {
  int count = 0;
  s_items[count++] = (SimpleMenuItem) {
    .title = s_snapshot.state == STATE_STOPPED ? tr("Start recording", "Aufzeichnung starten") :
        s_snapshot.state == STATE_PAUSED ? tr("Resume", "Fortsetzen") :
        s_snapshot.state == STATE_RECORDING ? tr("Pause", "Pausieren") :
        tr("Locus unavailable", "Locus nicht verfügbar"),
    .callback = s_snapshot.state == STATE_UNAVAILABLE ? NULL : recording_selected,
  };
  s_items[count++] = (SimpleMenuItem) {
    .title = tr("Profile", "Profil"),
    .subtitle = s_profiles[s_selected].name,
    .callback = profile_selected,
  };
  if (s_snapshot.state == STATE_RECORDING || s_snapshot.state == STATE_PAUSED) {
    s_items[count++] = (SimpleMenuItem) {
      .title = tr("Stop & save", "Stoppen & speichern"),
      .callback = stop_selected,
    };
  }
  if (s_snapshot.state == STATE_RECORDING) {
    s_items[count++] = (SimpleMenuItem) {
      .title = tr("Add waypoint", "Wegpunkt hinzufügen"),
      .callback = waypoint_selected,
    };
  }
  if (s_snapshot.state == STATE_RECORDING && PBL_IF_MICROPHONE_ELSE(true, false)) {
#if defined(PBL_MICROPHONE)
    s_items[count++] = (SimpleMenuItem) {
      .title = tr("Add waypoint + note", "Wegpunkt + Notiz"),
      .callback = dictated_waypoint_selected,
    };
#endif
  }
  s_section = (SimpleMenuSection) {
    .title = tr("Controls", "Steuerung"),
    .num_items = count,
    .items = s_items,
  };
  if (s_menu) simple_menu_layer_destroy(s_menu);
  s_menu = NULL;
  if (!s_controls_window) return false;
  Layer *root = window_get_root_layer(s_controls_window);
  if (!root) return false;
  s_menu = simple_menu_layer_create(layer_get_bounds(root), s_controls_window, &s_section, 1, NULL);
  if (!s_menu) return false;
  layer_add_child(root, simple_menu_layer_get_layer(s_menu));
  apply_theme();
  return true;
}

static void main_select(ClickRecognizerRef recognizer, void *context) {
  if (!rebuild_menu()) {
    show_notice(tr("Not enough memory", "Nicht genug Speicher"), 4);
    return;
  }
  if (s_controls_window) window_stack_push(s_controls_window, true);
}

static void click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, main_select);
}

static TextLayer *make_text(Layer *root, GRect frame, GFont font) {
  if (!root) return NULL;
  TextLayer *layer = text_layer_create(frame);
  if (!layer) return NULL;
  text_layer_set_background_color(layer, GColorClear);
  text_layer_set_font(layer, font);
  text_layer_set_text_alignment(layer, GTextAlignmentCenter);
  layer_add_child(root, text_layer_get_layer(layer));
  return layer;
}

static void main_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  if (!root) return;
  const GRect bounds = layer_get_bounds(root);
  s_status_bar = status_bar_layer_create();
  if (s_status_bar) layer_add_child(root, status_bar_layer_get_layer(s_status_bar));
  s_header = make_text(
      root,
      GRect(0, STATUS_BAR_LAYER_HEIGHT, bounds.size.w, 25),
      fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  for (int i = 0; i < MAX_SLOTS; i++) {
    s_labels[i] = make_text(root, GRectZero, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
    s_value_layers[i] = make_text(root, GRectZero, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  }
  layout_slots();
  apply_theme();
  render();
}

static void main_unload(Window *window) {
  if (s_status_bar) status_bar_layer_destroy(s_status_bar);
  s_status_bar = NULL;
  if (s_header) text_layer_destroy(s_header);
  s_header = NULL;
  for (int i = 0; i < MAX_SLOTS; i++) {
    if (s_labels[i]) text_layer_destroy(s_labels[i]);
    if (s_value_layers[i]) text_layer_destroy(s_value_layers[i]);
    s_labels[i] = NULL;
    s_value_layers[i] = NULL;
  }
}

static void controls_unload(Window *window) {
  if (s_menu) simple_menu_layer_destroy(s_menu);
  s_menu = NULL;
}

static void profile_load(Window *window) {
  for (int i = 0; i < s_profile_count; i++) {
    s_profile_items[i] = (SimpleMenuItem) {
      .title = s_profiles[i].name,
      .subtitle = i == s_selected ? tr("Active", "Aktiv") : NULL,
      .callback = profile_choice_selected,
    };
  }
  s_profile_section = (SimpleMenuSection) {
    .title = tr("Choose profile", "Profil wählen"),
    .num_items = s_profile_count,
    .items = s_profile_items,
  };
  Layer *root = window_get_root_layer(window);
  if (!root) return;
  s_profile_menu = simple_menu_layer_create(
      layer_get_bounds(root), window, &s_profile_section, 1, NULL);
  if (!s_profile_menu) {
    show_notice(tr("Not enough memory", "Nicht genug Speicher"), 4);
    return;
  }
  layer_add_child(root, simple_menu_layer_get_layer(s_profile_menu));
  apply_theme();
}

static void profile_unload(Window *window) {
  if (s_profile_menu) simple_menu_layer_destroy(s_profile_menu);
  s_profile_menu = NULL;
}

static void confirm_load(Window *window) {
  s_confirm_items[0] = (SimpleMenuItem) {
    .title = tr("Save & stop", "Speichern & stoppen"),
    .subtitle = tr("Finish the recording", "Aufzeichnung beenden"),
    .callback = confirm_selected,
  };
  s_confirm_items[1] = (SimpleMenuItem) {
    .title = tr("Cancel", "Abbrechen"),
    .subtitle = tr("Keep recording", "Weiter aufzeichnen"),
    .callback = confirm_selected,
  };
  s_confirm_section = (SimpleMenuSection) {
    .title = tr("Stop recording?", "Aufzeichnung stoppen?"),
    .num_items = 2,
    .items = s_confirm_items,
  };
  Layer *root = window_get_root_layer(window);
  if (!root) return;
  s_confirm_menu = simple_menu_layer_create(
      layer_get_bounds(root), window, &s_confirm_section, 1, NULL);
  if (!s_confirm_menu) {
    show_notice(tr("Not enough memory", "Nicht genug Speicher"), 4);
    return;
  }
  layer_add_child(root, simple_menu_layer_get_layer(s_confirm_menu));
  apply_theme();
}

static void confirm_unload(Window *window) {
  if (s_confirm_menu) simple_menu_layer_destroy(s_confirm_menu);
  s_confirm_menu = NULL;
}

#if defined(PBL_PLATFORM_EMERY)
static void health_timeout(void *context) {
  s_health_timeout = NULL;
  if (s_health_subscribed && !s_last_hr_sent_valid && !s_health_notice_shown) {
    s_health_notice_shown = true;
    show_notice(tr("No heart rate", "Kein Puls verfügbar"), 5);
  }
}

static void health_event(HealthEventType event, void *context) {
  if (event != HealthEventHeartRateUpdate || !s_health_subscribed) return;
  const int32_t bpm = health_service_peek_current_value(HealthMetricHeartRateRawBPM);
  if (bpm < 25 || bpm > 250 ||
      (s_last_hr_sent_valid &&
       s_uptime_seconds - s_last_hr_sent_uptime < s_heart_rate_interval)) {
    return;
  }
  s_last_hr_sent_valid = true;
  s_last_hr_sent_uptime = s_uptime_seconds;
  s_pending_hr = bpm;
  s_hr_generation++;
  s_hr_pending = true;
  if (s_health_timeout) {
    app_timer_cancel(s_health_timeout);
    s_health_timeout = NULL;
  }
  send_next();
}
#endif

static void stop_health(void) {
#if defined(PBL_PLATFORM_EMERY)
  if (s_health_timeout) {
    app_timer_cancel(s_health_timeout);
    s_health_timeout = NULL;
  }
  if (s_health_subscribed) {
    health_service_set_heart_rate_sample_period(0);
    health_service_events_unsubscribe();
  }
#endif
  s_health_subscribed = false;
  s_hr_pending = false;
  s_hr_prepared = false;
  s_send_attempts[OUTBOUND_HEART_RATE] = 0;
  s_last_hr_sent_valid = false;
}

static bool fresh_recording_snapshot(void) {
  return s_snapshot_received && s_snapshot_age <= SNAPSHOT_STALE_SECONDS &&
      s_snapshot.state == STATE_RECORDING;
}

static void update_health_subscription(void) {
  const bool wanted = s_watch_hr_to_locus && fresh_recording_snapshot();
  if (!wanted) {
    stop_health();
    return;
  }
  if (s_health_subscribed) return;
#if defined(PBL_PLATFORM_EMERY)
  s_last_hr_sent_valid = false;
  s_health_subscribed = health_service_events_subscribe(health_event, NULL);
  if (s_health_subscribed) {
    if (health_service_set_heart_rate_sample_period(s_heart_rate_interval)) {
      uint32_t wait = s_heart_rate_interval * 2;
      if (wait < 10) wait = 10;
      s_health_timeout = app_timer_register(wait * 1000, health_timeout, NULL);
      return;
    }
    APP_LOG(APP_LOG_LEVEL_WARNING, "Heart-rate sampling request failed");
    if (!health_service_events_unsubscribe()) {
      APP_LOG(APP_LOG_LEVEL_WARNING, "HealthService rollback unsubscribe failed");
    }
    s_health_subscribed = false;
  }
#endif
  if (!s_health_notice_shown) {
    s_health_notice_shown = true;
    show_notice(tr("Heart rate unavailable", "Puls nicht verfügbar"), 5);
  }
}

static bool nonnegative_wire_value(int32_t value) {
  return value == UNAVAILABLE || value >= 0;
}

static bool snapshot_values_valid(const Snapshot *snapshot) {
  return snapshot && snapshot->state >= STATE_STOPPED && snapshot->state <= STATE_UNAVAILABLE &&
      nonnegative_wire_value(snapshot->moving_time) &&
      nonnegative_wire_value(snapshot->distance) &&
      nonnegative_wire_value(snapshot->moving_distance) &&
      nonnegative_wire_value(snapshot->current_speed) &&
      nonnegative_wire_value(snapshot->average_speed) &&
      nonnegative_wire_value(snapshot->max_speed) &&
      nonnegative_wire_value(snapshot->ascent) &&
      nonnegative_wire_value(snapshot->descent) &&
      nonnegative_wire_value(snapshot->avg_hr) &&
      nonnegative_wire_value(snapshot->max_hr) &&
      nonnegative_wire_value(snapshot->current_hr) &&
      nonnegative_wire_value(snapshot->avg_cadence) &&
      nonnegative_wire_value(snapshot->max_cadence) &&
      nonnegative_wire_value(snapshot->avg_power) &&
      nonnegative_wire_value(snapshot->max_power) &&
      nonnegative_wire_value(snapshot->energy);
}

static bool parse_snapshot(DictionaryIterator *iterator, Snapshot *output) {
  if (!iterator || !output) return false;
  Snapshot snapshot = {0};
  int32_t state;
  int32_t unit_system;
  if (!dictionary_int32(iterator, MESSAGE_KEY_RECORDING_STATE, &state) ||
      !dictionary_uint32(iterator, MESSAGE_KEY_SAMPLE_EPOCH_SECONDS, &snapshot.sample_epoch) ||
      !dictionary_uint32(iterator, MESSAGE_KEY_ELAPSED_SECONDS, &snapshot.elapsed) ||
      !dictionary_int32(iterator, MESSAGE_KEY_MOVING_SECONDS, &snapshot.moving_time) ||
      !dictionary_int32(iterator, MESSAGE_KEY_DISTANCE_METRES, &snapshot.distance) ||
      !dictionary_int32(iterator, MESSAGE_KEY_MOVING_DISTANCE_METRES, &snapshot.moving_distance) ||
      !dictionary_int32(iterator, MESSAGE_KEY_CURRENT_SPEED_CMPS, &snapshot.current_speed) ||
      !dictionary_int32(iterator, MESSAGE_KEY_AVERAGE_SPEED_CMPS, &snapshot.average_speed) ||
      !dictionary_int32(iterator, MESSAGE_KEY_MAX_SPEED_CMPS, &snapshot.max_speed) ||
      !dictionary_int32(iterator, MESSAGE_KEY_ALTITUDE_DECIMETRES, &snapshot.altitude) ||
      !dictionary_int32(iterator, MESSAGE_KEY_ASCENT_DECIMETRES, &snapshot.ascent) ||
      !dictionary_int32(iterator, MESSAGE_KEY_DESCENT_DECIMETRES, &snapshot.descent) ||
      !dictionary_int32(iterator, MESSAGE_KEY_VERTICAL_SPEED_CMPS, &snapshot.vertical_speed) ||
      !dictionary_int32(iterator, MESSAGE_KEY_SLOPE_TENTHS_PERCENT, &snapshot.slope) ||
      !dictionary_int32(iterator, MESSAGE_KEY_AVERAGE_HEART_RATE, &snapshot.avg_hr) ||
      !dictionary_int32(iterator, MESSAGE_KEY_MAX_HEART_RATE, &snapshot.max_hr) ||
      !dictionary_int32(iterator, MESSAGE_KEY_CURRENT_HEART_RATE, &snapshot.current_hr) ||
      !dictionary_int32(iterator, MESSAGE_KEY_AVERAGE_CADENCE, &snapshot.avg_cadence) ||
      !dictionary_int32(iterator, MESSAGE_KEY_MAX_CADENCE, &snapshot.max_cadence) ||
      !dictionary_int32(iterator, MESSAGE_KEY_AVERAGE_POWER, &snapshot.avg_power) ||
      !dictionary_int32(iterator, MESSAGE_KEY_MAX_POWER, &snapshot.max_power) ||
      !dictionary_int32(iterator, MESSAGE_KEY_ENERGY_KCAL, &snapshot.energy) ||
      !dictionary_int32(iterator, MESSAGE_KEY_UNIT_SYSTEM, &unit_system)) {
    return false;
  }
  snapshot.state = state;
  if (unit_system != 0 || !snapshot_values_valid(&snapshot)) return false;
  *output = snapshot;
  return true;
}

static void accept_snapshot(DictionaryIterator *iterator) {
  Snapshot candidate;
  if (!parse_snapshot(iterator, &candidate)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Rejected incomplete or invalid snapshot");
    return;
  }
  if (!watch_snapshot_epoch_allowed(
          s_snapshot_received, s_snapshot.sample_epoch, candidate.sample_epoch)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Rejected older snapshot");
    return;
  }
  const int old_state = s_snapshot.state;
  s_snapshot = candidate;
  s_snapshot_received = true;
  s_snapshot_age = 0;
  if (s_snapshot.state == STATE_STOPPED) s_health_notice_shown = false;
  update_health_subscription();
  if (s_snapshot.state == STATE_STOPPED) apply_pending_config_if_stopped();
  if (old_state != s_snapshot.state && s_controls_window &&
      window_stack_contains_window(s_controls_window)) {
    rebuild_menu();
  }
  render();
}

static void accept_command_result(DictionaryIterator *iterator) {
  uint32_t session_id;
  uint32_t command_id;
  int32_t result;
  if (!dictionary_uint32(iterator, MESSAGE_KEY_SESSION_ID, &session_id) ||
      !dictionary_uint32(iterator, MESSAGE_KEY_COMMAND_ID, &command_id) ||
      !dictionary_int32(iterator, MESSAGE_KEY_RESULT, &result) ||
      result < RESULT_OK || result > RESULT_INVALID_WAYPOINT_NAME ||
      session_id != s_session_id) {
    return;
  }
  CommandRecord *record = command_record_find(command_id);
  if (!record) return;
  const int command = record->command;
  command_record_remove(command_id);
  vibes_short_pulse();
  if (result == RESULT_INVALID_PROFILE) {
    show_notice(tr("Invalid profile", "Ungültiges Profil"), 4);
  } else if (result == RESULT_PROFILE_NOT_FOUND) {
    show_notice(tr("Profile not in Locus", "Profil nicht in Locus"), 4);
  } else if (result == RESULT_INVALID_WAYPOINT_NAME) {
    show_notice(tr("Invalid waypoint note", "Ungültige Wegpunktnotiz"), 4);
  } else if (result == RESULT_OK &&
             (command == CMD_ADD_WAYPOINT || command == CMD_ADD_WAYPOINT_WITH_NOTE)) {
    show_notice(tr("Waypoint added", "Wegpunkt hinzugefügt"), 4);
  } else if (result == RESULT_OK) {
    show_notice(tr("Command accepted", "Befehl angenommen"), 4);
  } else {
    snprintf(s_notice, sizeof(s_notice), "%s (%ld)",
             tr("Command failed", "Befehl fehlgeschlagen"), (long)result);
    s_notice_until = time(NULL) + 4;
    render();
  }
}

static void inbox(DictionaryIterator *iterator, void *context) {
  int32_t version;
  if (!dictionary_int32(iterator, MESSAGE_KEY_PROTOCOL_VERSION, &version) ||
      version != PROTOCOL_VERSION) {
    show_notice(tr("Protocol mismatch", "Protokoll nicht kompatibel"), 6);
    return;
  }
  const char *release;
  size_t release_length;
  if (!dictionary_cstring(
          iterator, MESSAGE_KEY_APP_VERSION, sizeof(RELEASE_VERSION) - 1,
          &release, &release_length) ||
      release_length != sizeof(RELEASE_VERSION) - 1 ||
      memcmp(release, RELEASE_VERSION, sizeof(RELEASE_VERSION) - 1) != 0) {
    show_notice(tr("Update bridge/watch", "Bridge/Watch aktualisieren"), 10);
    return;
  }
  int32_t type;
  if (!dictionary_int32(iterator, MESSAGE_KEY_MESSAGE_TYPE, &type)) return;
  if (type == MSG_CONFIG_CHUNK) {
    accept_config_chunk(iterator);
  } else if (type == MSG_PROFILE_LIST_CHUNK) {
    accept_profile_chunk(iterator);
  } else if (type == MSG_REQUEST_PROFILE_LIST) {
    if (s_relay_count) {
      s_request_profiles_after_relay = true;
    } else if (s_profile_transfer.id >= 0 || s_profile_pending_ready) {
      s_request_profiles_after_relay = true;
    } else if (persistent_blob_read(
            &s_profile_list_blob, s_profile_chunks, sizeof(s_profile_chunks)) &&
        watch_profile_list_valid(s_profile_chunks, strlen(s_profile_chunks))) {
      s_relay_result = s_profile_chunks[0] ? RESULT_OK : RESULT_FAILED;
      s_request_profiles_after_relay = true;
      relay_profile_list();
      if (!s_relay_count) enqueue_deferred_profile_request();
      send_next();
    } else {
      if (persistent_blob_exists(&s_profile_list_blob) &&
          !persistent_blob_delete(&s_profile_list_blob)) {
        APP_LOG(APP_LOG_LEVEL_WARNING, "Corrupt profile cache cleanup failed");
      }
      control_enqueue(MSG_REQUEST_PROFILE_LIST, 0, 0, NULL);
      send_next();
    }
  } else if (type == MSG_SNAPSHOT) {
    accept_snapshot(iterator);
  } else if (type == MSG_COMMAND_RESULT) {
    accept_command_result(iterator);
  }
}

static void tick(struct tm *time, TimeUnits units) {
  s_uptime_seconds++;
  if (s_config_transfer.id >= 0 &&
      s_uptime_seconds - s_transfer_last_activity >= CONFIG_TRANSFER_TIMEOUT_SECONDS) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Discarding expired configuration transfer");
    reset_config_transfer();
  }
  if (s_profile_transfer.id >= 0 &&
      s_uptime_seconds - s_profile_transfer.last_activity >= PROFILE_TRANSFER_TIMEOUT_SECONDS) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Discarding expired profile-list transfer");
    reset_profile_transfer();
  }
  if (expire_command_records()) {
    show_notice(tr("Command response timeout", "Befehlsantwort fehlt"), 4);
  }
  if (s_snapshot_received && s_snapshot_age < UINT16_MAX) {
    s_snapshot_age++;
    if (s_snapshot_age == SNAPSHOT_STALE_SECONDS + 1) update_health_subscription();
  }
  enqueue_deferred_profile_request();
  render();
  send_next();
}

static bool create_windows(void) {
  s_main_window = window_create();
  s_controls_window = window_create();
  s_profile_window = window_create();
  s_confirm_window = window_create();
  if (!s_main_window || !s_controls_window || !s_profile_window || !s_confirm_window) return false;
  window_set_window_handlers(s_main_window, (WindowHandlers) {
    .load = main_load,
    .unload = main_unload,
  });
  window_set_click_config_provider(s_main_window, click_config);
  window_set_window_handlers(s_controls_window, (WindowHandlers) {
    .unload = controls_unload,
  });
  window_set_window_handlers(s_profile_window, (WindowHandlers) {
    .load = profile_load,
    .unload = profile_unload,
  });
  window_set_window_handlers(s_confirm_window, (WindowHandlers) {
    .load = confirm_load,
    .unload = confirm_unload,
  });
  return true;
}

static uint32_t create_session_id(void) {
  uint32_t counter = 0;
  if (persist_exists(PERSIST_SESSION_COUNTER)) {
    counter = (uint32_t)persist_read_int(PERSIST_SESSION_COUNTER);
  }
  counter++;
  if (!counter) counter = 1;
  if (persist_write_int(PERSIST_SESSION_COUNTER, (int32_t)counter) != (int)sizeof(int32_t)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Failed to persist session counter");
    time_t seconds;
    uint16_t milliseconds;
    time_ms(&seconds, &milliseconds);
    counter ^= (uint32_t)seconds ^ ((uint32_t)milliseconds << 16);
    if (!counter) counter = 1;
  }
  return counter;
}

static bool init(void) {
  APP_LOG(APP_LOG_LEVEL_DEBUG, "%s", LOCUS_WATCH_BUILD_SHA256_MARKER);
  watch_config_transfer_initialize(&s_config_transfer);
  watch_profile_transfer_initialize(&s_profile_transfer);
  const char *locale = i18n_get_system_locale();
  s_german = locale && strncmp(locale, "de", 2) == 0;
  default_profiles();
  if (persistent_blob_read(&s_config_blob, s_config_work, sizeof(s_config_work))) {
    if (parse_config_buffer(s_config_work, &s_parsed_config)) {
      install_config(&s_parsed_config, false);
    } else {
      APP_LOG(APP_LOG_LEVEL_ERROR, "Stored configuration is invalid");
    }
  }
  s_session_id = create_session_id();
  s_next_command_id = 1;
  APP_LOG(APP_LOG_LEVEL_INFO, "Persistent capacity: %lu", (unsigned long)persist_get_max_size());

  if (!create_windows()) return false;
  app_message_register_inbox_received(inbox);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_register_outbox_sent(outbox_sent);
  app_message_register_outbox_failed(outbox_failed);
  const AppMessageResult open_result = app_message_open(512, 512);
  if (open_result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "AppMessage open failed: %d", (int)open_result);
    copy_text(s_notice, sizeof(s_notice), tr("Messaging unavailable", "Nachrichten nicht verfügbar"));
    s_notice_until = time(NULL) + 30;
  }
  tick_timer_service_subscribe(SECOND_UNIT, tick);
  window_stack_push(s_main_window, true);
  if (open_result == APP_MSG_OK) {
    control_enqueue(MSG_REQUEST_SNAPSHOT, 0, 0, NULL);
    control_enqueue(MSG_REQUEST_PROFILE_LIST, 0, 0, NULL);
    send_next();
  }
  return true;
}

static void destroy_window(Window **window) {
  if (!window || !*window) return;
  window_destroy(*window);
  *window = NULL;
}

static void deinit(void) {
  stop_health();
  tick_timer_service_unsubscribe();
  if (s_retry_timer) {
    app_timer_cancel(s_retry_timer);
    s_retry_timer = NULL;
  }
#if defined(PBL_MICROPHONE)
  if (s_dictation_send_timer) {
    app_timer_cancel(s_dictation_send_timer);
    s_dictation_send_timer = NULL;
  }
  if (s_dictation_session) {
    dictation_session_destroy(s_dictation_session);
    s_dictation_session = NULL;
  }
#endif
  app_message_deregister_callbacks();
  destroy_window(&s_confirm_window);
  destroy_window(&s_profile_window);
  destroy_window(&s_controls_window);
  destroy_window(&s_main_window);
}

int main(void) {
  if (!init()) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Initialization failed");
    deinit();
    return 1;
  }
  app_event_loop();
  deinit();
  return 0;
}
