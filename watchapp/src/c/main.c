#include <pebble.h>

#include "app_message_handler.h"
#include "i18n.h"
#include "persistent_blob.h"
#include "ui_metrics.h"
#include "watch_build_hash.auto.h"
#include "watch_config.h"
#include "watch_maintenance.h"
#include "watch_maintenance_timer.h"
#include "watch_outbound_retry.h"
#include "watch_state.h"
#include "watch_step_state.h"

#define PROTOCOL_VERSION 5
#define RELEASE_VERSION "0.2.7"
#define MAX_SLOTS WATCH_MAX_SLOTS
#define MAX_PROFILES WATCH_MAX_PROFILES
#define NAME_SIZE WATCH_PROFILE_NAME_SIZE
#define ID_SIZE WATCH_PROFILE_ID_SIZE
#define LOCUS_NAME_SIZE WATCH_LOCUS_NAME_SIZE
#define LOCUS_ID_SIZE WATCH_LOCUS_ID_SIZE
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
#define UNAVAILABLE UI_METRIC_UNAVAILABLE

#define PERSIST_CONFIG_LEGACY 100
#define PERSIST_PENDING_CONFIG_LEGACY 101
#define PERSIST_PROFILE_LIST_LEGACY 102
#define PERSIST_SESSION_COUNTER 104
#define PERSIST_RELAY_TRANSFER_COUNTER 105
#define PERSIST_CONFIG_META 200
#define PERSIST_PENDING_CONFIG_META 201
#define PERSIST_PROFILE_LIST_META 202

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
  MSG_RECORDING_CONTEXT = 10,
  MSG_REQUEST_RUNTIME_CONFIG = 11,
  MSG_STEP_DELTA = 12,
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

typedef WatchProfile Profile;
typedef UiMetricSnapshot Snapshot;

typedef struct {
  int type;
  int command;
  uint32_t command_id;
  int32_t transfer_id;
  int32_t result;
  char text[WAYPOINT_NAME_SIZE];
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
  OUTBOUND_STEPS,
} OutboundKind;

static const PersistentBlob s_config_blob = {
    .record_key = PERSIST_CONFIG_META,
    .legacy_key = PERSIST_CONFIG_LEGACY,
    .chunk_base = 1000,
    .max_chunks = 16,
};
static const PersistentBlob s_pending_config_blob = {
    .record_key = PERSIST_PENDING_CONFIG_META,
    .legacy_key = PERSIST_PENDING_CONFIG_LEGACY,
    .chunk_base = 1040,
    .max_chunks = 16,
};
static const PersistentBlob s_obsolete_profile_list_blob = {
    .record_key = PERSIST_PROFILE_LIST_META,
    .legacy_key = PERSIST_PROFILE_LIST_LEGACY,
    .chunk_base = 1080,
    .max_chunks = 32,
};

static Window *s_main_window;
static Window *s_controls_window;
static Window *s_confirm_window;
static Window *s_waypoint_window;
static StatusBarLayer *s_status_bar;
static TextLayer *s_header;
static TextLayer *s_instruction;
static TextLayer *s_labels[MAX_SLOTS];
static TextLayer *s_value_layers[MAX_SLOTS];
static SimpleMenuLayer *s_menu;
static SimpleMenuLayer *s_confirm_menu;
static SimpleMenuLayer *s_waypoint_menu;
static SimpleMenuItem s_items[5];
static SimpleMenuItem s_confirm_items[2];
static SimpleMenuItem s_waypoint_items[2];
static SimpleMenuSection s_section;
static SimpleMenuSection s_confirm_section;
static SimpleMenuSection s_waypoint_section;

static Snapshot s_snapshot = {
    .state = STATE_UNAVAILABLE,
    .moving_time = UNAVAILABLE,
    .distance = UNAVAILABLE,
    .moving_distance = UNAVAILABLE,
    .current_speed = UNAVAILABLE,
    .average_speed = UNAVAILABLE,
    .max_speed = UNAVAILABLE,
    .current_pace = UNAVAILABLE,
    .average_pace = UNAVAILABLE,
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
    .steps = UNAVAILABLE,
    .altitude_format = FORMAT_M_0,
    .distance_format = FORMAT_M_0,
    .moving_distance_format = FORMAT_M_0,
    .current_speed_format = FORMAT_KPH_1,
    .average_speed_format = FORMAT_KPH_1,
    .max_speed_format = FORMAT_KPH_1,
    .vertical_speed_format = FORMAT_MPS_2,
    .slope_format = FORMAT_PERCENT_0,
    .energy_format = FORMAT_KJ_0,
    .pace_format = FORMAT_PER_KM,
};
static Profile s_profiles[MAX_PROFILES];
static WatchConfig s_parsed_config;
static int s_profile_count;
static int s_selected;
static bool s_dark = true;
static bool s_snapshot_received;
static uint32_t s_launch_second;
static uint32_t s_snapshot_second;
static bool s_no_bridge_escalated;
static bool s_snapshot_stale_processed;
static char s_current_locus_id[LOCUS_ID_SIZE];
static char s_config_locus_id[LOCUS_ID_SIZE];
static uint32_t s_config_fingerprint_a;
static uint32_t s_config_fingerprint_b;
static bool s_activity_ready;
static bool s_context_active;
static uint32_t s_context_started;
static bool s_profile_preparation_escalated;
static uint32_t s_last_runtime_config_request;
static bool s_runtime_config_pending;

static char s_header_text[64] = "Connecting...";
static char s_notice[48];
static char s_value_text[MAX_SLOTS][24];
static uint32_t s_notice_until;

static char s_chunks[CONFIG_SIZE];
static char s_config_work[CONFIG_SIZE];
static WatchConfigTransfer s_config_transfer = {.id = -1};
static bool s_config_durable_generation_seen;
static uint32_t s_transfer_last_activity;
static uint32_t s_transfer_fingerprint_a;
static uint32_t s_transfer_fingerprint_b;
static bool s_config_result_slot_reserved;

static char s_profile_chunks[WATCH_PROFILE_TRANSFER_BUFFER_SIZE];
static WatchProfileTransfer s_profile_transfer;
static bool s_profile_durable_generation_seen;
static bool s_profile_pending_ready;
static int s_profile_pending_result = RESULT_FAILED;
static int32_t s_profile_pending_id = -1;

static ControlMessage s_control_queue[CONTROL_QUEUE_SIZE];
static uint8_t s_control_head;
static uint8_t s_control_count;
static CommandRecord s_command_records[COMMAND_RECORD_COUNT];
static uint32_t s_next_command_id;
static uint32_t s_session_id;

static bool s_outbox_busy;
static OutboundKind s_inflight_kind;
static uint8_t s_send_attempts[OUTBOUND_STEPS + 1];
static AppTimer *s_retry_timer;
static size_t s_relay_offset;
static size_t s_relay_send_end;
static int s_relay_index;
static int s_relay_count;
static int32_t s_relay_id;
static int s_relay_result = RESULT_FAILED;
static bool s_request_profiles_after_relay;

static bool s_watch_hr_to_locus;
static bool s_watch_steps_to_locus;
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
static WatchStepState s_step_state;
#if defined(PBL_PLATFORM_EMERY)
static AppTimer *s_health_timeout;
static uint32_t s_last_hr_sent_second;
#endif

static WatchMaintenanceTimer s_maintenance_timer;

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
static void schedule_maintenance(void);
static bool update_step_state(uint32_t now);
static bool sample_steps(uint32_t now);

static WatchMaintenanceClock current_clock(void) {
  time_t seconds;
  uint16_t milliseconds;
  time_ms(&seconds, &milliseconds);
  return (WatchMaintenanceClock){.seconds = (uint32_t)seconds, .milliseconds = milliseconds};
}

static uint32_t current_second(void) {
  return current_clock().seconds;
}

static void *register_maintenance_timer(void *context, uint32_t delay_ms,
                                        WatchMaintenanceTimerCallback callback,
                                        void *callback_context) {
  (void)context;
  return app_timer_register(delay_ms, callback, callback_context);
}

static bool reschedule_maintenance_timer(void *context, void *timer, uint32_t delay_ms) {
  (void)context;
  return app_timer_reschedule(timer, delay_ms);
}

static void cancel_maintenance_timer(void *context, void *timer) {
  (void)context;
  app_timer_cancel(timer);
}

static void copy_text(char *destination, size_t size, const char *source) {
  if (!destination || !size) return;
  snprintf(destination, size, "%s", source ? source : "");
}

static void show_notice(const char *message, int seconds) {
  copy_text(s_notice, sizeof(s_notice), message);
  s_notice_until = current_second() + (uint32_t)seconds;
  render();
  schedule_maintenance();
}

static void default_profiles(void) {
  memset(s_profiles, 0, sizeof(s_profiles));
  s_dark = true;
  s_selected = 0;
  s_profile_count = 1;
  s_watch_hr_to_locus = false;
  s_watch_steps_to_locus = false;
  s_heart_rate_interval = 5;

  copy_text(s_profiles[0].name, sizeof(s_profiles[0].name), i18n_text(I18N_DEFAULT));
  copy_text(s_profiles[0].id, sizeof(s_profiles[0].id), "unconfigured");
  const uint8_t general[] = {1, 3, 5, 6, 10, 22};
  memcpy(s_profiles[0].metrics, general, sizeof(general));
  s_profiles[0].count = sizeof(general);
}

static void close_secondary_windows(void) {
  if (s_confirm_window && window_stack_contains_window(s_confirm_window)) {
    window_stack_remove(s_confirm_window, false);
  }
  if (s_waypoint_window && window_stack_contains_window(s_waypoint_window)) {
    window_stack_remove(s_waypoint_window, false);
  }
  if (s_controls_window && window_stack_contains_window(s_controls_window)) {
    window_stack_remove(s_controls_window, false);
  }
}

static void install_config(const WatchConfig *config) {
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
  s_watch_steps_to_locus = config->watch_steps_to_locus;
  s_heart_rate_interval = config->heart_rate_interval;
  copy_text(s_config_locus_id, sizeof(s_config_locus_id), config->locus_id);
  s_config_fingerprint_a = config->fingerprint_a;
  s_config_fingerprint_b = config->fingerprint_b;
  s_activity_ready = s_context_active && strcmp(s_current_locus_id, s_config_locus_id) == 0;
  if (s_activity_ready) s_profile_preparation_escalated = true;
  layout_slots();
  apply_theme();
  update_health_subscription();
  update_step_state(current_second());
  render();
}

static bool parse_config_buffer(char *data, WatchConfig *output) {
  char active[ID_SIZE] = "";
  if (s_activity_ready && s_selected >= 0 && s_selected < s_profile_count) {
    copy_text(active, sizeof(active), s_profiles[s_selected].id);
  }
  return watch_config_parse(data, active, output);
}

static bool store_active_config(const char *data) {
  return data && persistent_blob_write(&s_config_blob, data, strlen(data));
}

static void apply_theme(void) {
  const GColor background = s_dark ? GColorBlack : GColorWhite;
  const GColor foreground = s_dark ? GColorWhite : GColorBlack;
  if (s_main_window) window_set_background_color(s_main_window, background);
  if (s_controls_window) window_set_background_color(s_controls_window, background);
  if (s_confirm_window) window_set_background_color(s_confirm_window, background);
  if (s_waypoint_window) window_set_background_color(s_waypoint_window, background);
  if (s_status_bar) status_bar_layer_set_colors(s_status_bar, background, foreground);
  if (s_header) text_layer_set_text_color(s_header, foreground);
  if (s_instruction) text_layer_set_text_color(s_instruction, foreground);
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
  if (s_waypoint_menu) {
    MenuLayer *menu = simple_menu_layer_get_menu_layer(s_waypoint_menu);
    if (menu) {
      menu_layer_set_normal_colors(menu, background, foreground);
      menu_layer_set_highlight_colors(menu, foreground, background);
    }
  }
}

static const char *metric_label(int metric) {
  return ui_metric_label(metric);
}

static void format_metric(char *output, size_t size, int metric) {
  ui_metric_format(output, size, metric, &s_snapshot);
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
  if (s_instruction) {
    layer_set_frame(text_layer_get_layer(s_instruction),
                    GRect(PBL_IF_ROUND_ELSE(20, 8), top + 8,
                          bounds.size.w - PBL_IF_ROUND_ELSE(40, 16), height - 8));
  }
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
    layer_set_frame(text_layer_get_layer(s_value_layers[i]),
                    GRect(x, y + row_height / 3, cell_width, row_height * 2 / 3));
    text_layer_set_text(s_labels[i], metric_label(s_profiles[s_selected].metrics[i]));
  }
}

static void render(void) {
  if (!s_header || s_selected < 0 || s_selected >= s_profile_count) return;
  const uint32_t now = current_second();
  const bool stale = s_snapshot_received &&
                     watch_maintenance_reached(now, s_snapshot_second + SNAPSHOT_STALE_SECONDS + 1);
  const char *state = s_snapshot.state == STATE_RECORDING ? i18n_text(I18N_RECORDING)
                      : s_snapshot.state == STATE_PAUSED  ? i18n_text(I18N_PAUSED)
                      : s_snapshot.state == STATE_STOPPED ? i18n_text(I18N_STOPPED)
                                                          : i18n_text(I18N_NO_LOCUS);
  const bool showing_notice = s_notice_until && !watch_maintenance_reached(now, s_notice_until);
  const bool active = s_context_active && s_activity_ready &&
                      (s_snapshot.state == STATE_RECORDING || s_snapshot.state == STATE_PAUSED);
  if (!s_snapshot_received && !showing_notice) {
    copy_text(s_header_text, sizeof(s_header_text), i18n_text(I18N_CONNECTING));
  } else if (showing_notice) {
    copy_text(s_header_text, sizeof(s_header_text), s_notice);
  } else if (active) {
    snprintf(s_header_text, sizeof(s_header_text), "%s \xc2\xb7 %d/%d%s", state, s_selected + 1,
             s_profile_count, stale ? i18n_text(I18N_STALE_SUFFIX) : "");
  } else {
    snprintf(s_header_text, sizeof(s_header_text), "%s%s", state,
             stale ? i18n_text(I18N_STALE_SUFFIX) : "");
  }
  text_layer_set_text(s_header, s_header_text);

  const char *instruction = NULL;
  if (!s_snapshot_received) {
    instruction = watch_maintenance_reached(now, s_launch_second + 10)
                      ? i18n_text(I18N_NO_BRIDGE_RESPONSE)
                      : NULL;
  } else if (s_snapshot.state == STATE_STOPPED) {
    instruction = i18n_text(I18N_START_IN_LOCUS);
  } else if (s_snapshot.state == STATE_UNAVAILABLE) {
    instruction = i18n_text(I18N_LOCUS_UNAVAILABLE_INSTRUCTION);
  } else if (!s_activity_ready) {
    instruction = watch_maintenance_reached(now, s_context_started + 15)
                      ? i18n_text(I18N_OPEN_WATCH_SETTINGS)
                      : i18n_text(I18N_PREPARING_PROFILE);
  }
  if (s_instruction) {
    text_layer_set_text(s_instruction, instruction ? instruction : "");
    layer_set_hidden(text_layer_get_layer(s_instruction), instruction == NULL);
  }
  for (int i = 0; i < MAX_SLOTS; i++) {
    if (s_labels[i])
      layer_set_hidden(text_layer_get_layer(s_labels[i]),
                       !active || i >= s_profiles[s_selected].count);
    if (s_value_layers[i])
      layer_set_hidden(text_layer_get_layer(s_value_layers[i]),
                       !active || i >= s_profiles[s_selected].count);
  }
  if (!active) return;

  const int count = s_profiles[s_selected].count;
  for (int i = 0; i < count && i < MAX_SLOTS; i++) {
    format_metric(s_value_text[i], sizeof(s_value_text[i]), s_profiles[s_selected].metrics[i]);
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
  const bool coalescible = type == MSG_REQUEST_SNAPSHOT || type == MSG_REQUEST_PROFILE_LIST ||
                           type == MSG_REQUEST_RUNTIME_CONFIG;
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
  record->result_deadline = current_second() + COMMAND_RESULT_TIMEOUT_SECONDS;
  schedule_maintenance();
}

static bool expire_command_records(uint32_t now) {
  bool expired = false;
  for (int i = 0; i < COMMAND_RECORD_COUNT; i++) {
    CommandRecord *record = &s_command_records[i];
    if (record->used && record->awaiting_result &&
        watch_maintenance_reached(now, record->result_deadline)) {
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
    if (valid && message->command == CMD_ADD_WAYPOINT_WITH_NOTE) {
      valid = dict_write_cstring(iterator, MESSAGE_KEY_WAYPOINT_NAME, message->text) == DICT_OK;
    }
  } else if (valid && message->type == MSG_CONFIG_RESULT) {
    valid = dict_write_int32(iterator, MESSAGE_KEY_TRANSFER_ID, message->transfer_id) == DICT_OK &&
            dict_write_int32(iterator, MESSAGE_KEY_RESULT, message->result) == DICT_OK;
  } else if (valid && message->type == MSG_REQUEST_RUNTIME_CONFIG) {
    valid =
        s_current_locus_id[0] &&
        dict_write_cstring(iterator, MESSAGE_KEY_LOCUS_PROFILE_ID, s_current_locus_id) == DICT_OK &&
        dict_write_uint32(iterator, MESSAGE_KEY_CONFIG_FINGERPRINT_A,
                          s_activity_ready ? s_config_fingerprint_a : 0) == DICT_OK &&
        dict_write_uint32(iterator, MESSAGE_KEY_CONFIG_FINGERPRINT_B,
                          s_activity_ready ? s_config_fingerprint_b : 0) == DICT_OK;
  } else if (valid && message->type == MSG_REQUEST_SNAPSHOT) {
    valid = dict_write_uint32(iterator, MESSAGE_KEY_SESSION_ID, s_session_id) == DICT_OK;
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
  const bool valid =
      write_common(iterator, MSG_PROFILE_LIST_CHUNK) &&
      dict_write_int32(iterator, MESSAGE_KEY_RESULT, s_relay_result) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_TRANSFER_ID, s_relay_id) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_TRANSFER_GENERATION, DURABLE_TRANSFER_GENERATION) ==
          DICT_OK &&
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
  const bool valid =
      write_common(iterator, MSG_HEART_RATE) &&
      dict_write_uint32(iterator, MESSAGE_KEY_SESSION_ID, s_session_id) == DICT_OK &&
      dict_write_uint32(iterator, MESSAGE_KEY_HEART_RATE_SEQUENCE, s_hr_send_sequence) == DICT_OK &&
      dict_write_uint32(iterator, MESSAGE_KEY_SAMPLE_EPOCH_SECONDS, s_hr_send_epoch) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_CURRENT_HEART_RATE, s_hr_send_value) == DICT_OK;
  if (!valid) return APP_MSG_BUFFER_OVERFLOW;
  return app_message_outbox_send();
}

static AppMessageResult send_step_packet(void) {
  const WatchStepPacket *packet = watch_step_state_prepare(&s_step_state);
  if (!packet) return APP_MSG_INVALID_STATE;
  DictionaryIterator *iterator = NULL;
  AppMessageResult result = app_message_outbox_begin(&iterator);
  if (result != APP_MSG_OK) return result;
  const bool valid =
      write_common(iterator, MSG_STEP_DELTA) &&
      dict_write_uint32(iterator, MESSAGE_KEY_SESSION_ID, s_session_id) == DICT_OK &&
      dict_write_uint32(iterator, MESSAGE_KEY_STEP_SEQUENCE, packet->sequence) == DICT_OK &&
      dict_write_int32(iterator, MESSAGE_KEY_STEPS, packet->delta) == DICT_OK &&
      dict_write_uint32(iterator, MESSAGE_KEY_RECORDING_START_MILLIS_LOW,
                        packet->recording.recording_start_low) == DICT_OK &&
      dict_write_uint32(iterator, MESSAGE_KEY_RECORDING_START_MILLIS_HIGH,
                        packet->recording.recording_start_high) == DICT_OK;
  if (!valid) return APP_MSG_BUFFER_OVERFLOW;
  return app_message_outbox_send();
}

static void retry_send(void *context) {
  s_retry_timer = NULL;
  send_next();
  schedule_maintenance();
}

static void finish_relay(void);

static void drop_outbound(OutboundKind kind) {
  if (kind == OUTBOUND_CONTROL) {
    ControlMessage *message = control_head();
    if (message && message->type == MSG_COMMAND && command_record_remove(message->command_id)) {
      show_notice(i18n_text(I18N_PHONE_DELIVERY_FAILED), 5);
    }
    control_pop();
  } else if (kind == OUTBOUND_RELAY) {
    finish_relay();
  } else if (kind == OUTBOUND_HEART_RATE) {
    if (s_hr_pending && s_hr_generation == s_hr_send_generation) s_hr_pending = false;
    s_hr_prepared = false;
  } else if (kind == OUTBOUND_STEPS) {
    watch_step_state_finish_prepared(&s_step_state);
  }
}

static void handle_send_failure(OutboundKind kind, AppMessageResult reason) {
  if (kind <= OUTBOUND_NONE || kind > OUTBOUND_STEPS) return;
  uint8_t *attempts = &s_send_attempts[kind];
  APP_LOG(APP_LOG_LEVEL_WARNING, "AppMessage failure kind=%d reason=%d attempt=%d", (int)kind,
          (int)reason, (int)*attempts + 1);
  s_outbox_busy = false;
  s_inflight_kind = OUTBOUND_NONE;
  if (kind == OUTBOUND_HEART_RATE && !s_hr_prepared) {
    *attempts = 0;
    send_next();
    return;
  }
  if (watch_outbound_retry_failed(attempts, MAX_SEND_ATTEMPTS)) {
    drop_outbound(kind);
    send_next();
    return;
  }
  const uint32_t delay = 250u << (*attempts - 1);
  if (s_retry_timer) app_timer_cancel(s_retry_timer);
  s_retry_timer = app_timer_register(delay, retry_send, NULL);
  if (!s_retry_timer) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Could not allocate AppMessage retry timer");
    watch_outbound_retry_reset(attempts);
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
  } else if (watch_step_state_has_outbound(&s_step_state)) {
    s_inflight_kind = OUTBOUND_STEPS;
    result = send_step_packet();
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
  if (!s_request_profiles_after_relay || s_relay_count || s_profile_transfer.id >= 0 ||
      s_profile_pending_ready) {
    return;
  }
  if (control_enqueue(MSG_REQUEST_PROFILE_LIST, 0, 0, NULL)) {
    s_request_profiles_after_relay = false;
  }
}

static void service_pending_control_work_at(uint32_t now) {
  enqueue_deferred_profile_request();
  if (!s_request_profiles_after_relay && s_runtime_config_pending && s_context_active &&
      s_current_locus_id[0] && control_enqueue(MSG_REQUEST_RUNTIME_CONFIG, 0, 0, NULL)) {
    s_runtime_config_pending = false;
    s_last_runtime_config_request = now;
  }
}

static void service_pending_control_work(void) {
  service_pending_control_work_at(current_second());
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
  if (completed > OUTBOUND_NONE && completed <= OUTBOUND_STEPS) {
    watch_outbound_retry_reset(&s_send_attempts[completed]);
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
  } else if (completed == OUTBOUND_STEPS) {
    watch_step_state_finish_prepared(&s_step_state);
  }
  service_pending_control_work();
  send_next();
  schedule_maintenance();
}

static void outbox_failed(DictionaryIterator *iterator, AppMessageResult reason, void *context) {
  handle_send_failure(s_inflight_kind, reason);
  service_pending_control_work();
  schedule_maintenance();
}

static void inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Inbox dropped: %d", (int)reason);
  show_notice(i18n_text(I18N_PHONE_MESSAGE_DROPPED), 4);
}

static void relay_profile_list(void) {
  const size_t length = strlen(s_profile_chunks);
  size_t offset = 0;
  int count = 0;
  do {
    size_t end = offset + PROFILE_CHUNK_BYTES;
    if (end > length) end = length;
    while (end > offset && end < length && (((uint8_t)s_profile_chunks[end] & 0xc0) == 0x80)) {
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
  if (!watch_transfer_serial_reserve_persistent(PERSIST_RELAY_TRANSFER_COUNTER, 0, &transfer_id)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Failed to reserve profile relay transfer ID");
    show_notice(i18n_text(I18N_PROFILE_RELAY_UNAVAILABLE), 5);
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
  if (!watch_profile_transfer_join(&s_profile_transfer, s_profile_chunks, sizeof(s_profile_chunks),
                                   PROFILE_LIST_SIZE - 1, &length) ||
      (s_profile_pending_result == RESULT_OK) != (length > 0) ||
      !watch_profile_list_valid(s_profile_chunks, length)) {
    s_profile_pending_ready = false;
    s_profile_pending_id = -1;
    reset_profile_transfer();
    show_notice(i18n_text(I18N_INVALID_PROFILE_LIST), 4);
    enqueue_deferred_profile_request();
    return;
  }

  s_relay_result = s_profile_pending_result;
  s_profile_pending_ready = false;
  s_profile_pending_id = -1;
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
  s_profile_pending_id = s_profile_transfer.id;
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
  const int generation = app_message_transfer_generation(iterator, DURABLE_TRANSFER_GENERATION);
  if (generation < 0 || (s_profile_durable_generation_seen && generation != 1)) return;
  if (s_relay_count || s_profile_pending_ready) {
    s_request_profiles_after_relay = true;
    return;
  }
  if (!app_message_int32(iterator, MESSAGE_KEY_TRANSFER_ID, &id) || id < 0) return;
  if (!app_message_int32(iterator, MESSAGE_KEY_CHUNK_INDEX, &index) ||
      !app_message_int32(iterator, MESSAGE_KEY_CHUNK_COUNT, &count) ||
      !app_message_int32(iterator, MESSAGE_KEY_RESULT, &result) || count < 1 ||
      count > PROFILE_MAX_CHUNKS || index < 0 || index >= count ||
      (result != RESULT_OK && result != RESULT_FAILED) ||
      !app_message_cstring(iterator, MESSAGE_KEY_CHUNK_DATA, PROFILE_CHUNK_BYTES, &data, &length)) {
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
  const WatchProfileTransferOutcome outcome =
      watch_profile_transfer_accept(&s_profile_transfer, s_profile_chunks, sizeof(s_profile_chunks),
                                    id, index, count, result, data, length, current_second());
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
  s_transfer_fingerprint_a = 0;
  s_transfer_fingerprint_b = 0;
  service_pending_control_work();
}

static bool begin_config_transfer(void) {
  reset_config_transfer();
  if (s_control_count >= CONTROL_QUEUE_SIZE) return false;
  s_config_result_slot_reserved = true;
  s_transfer_last_activity = current_second();
  return true;
}

static void accept_config_chunk(DictionaryIterator *iterator) {
  int32_t id;
  int32_t index;
  int32_t count;
  uint32_t fingerprint_a;
  uint32_t fingerprint_b;
  const char *data;
  size_t length;
  const int generation = app_message_transfer_generation(iterator, DURABLE_TRANSFER_GENERATION);
  if (generation < 0 || (s_config_durable_generation_seen && generation != 1)) return;
  if (!app_message_int32(iterator, MESSAGE_KEY_TRANSFER_ID, &id) || id < 0) return;
  if (!app_message_int32(iterator, MESSAGE_KEY_CHUNK_INDEX, &index) ||
      !app_message_int32(iterator, MESSAGE_KEY_CHUNK_COUNT, &count) ||
      !app_message_uint32(iterator, MESSAGE_KEY_CONFIG_FINGERPRINT_A, &fingerprint_a) ||
      !app_message_uint32(iterator, MESSAGE_KEY_CONFIG_FINGERPRINT_B, &fingerprint_b) ||
      count < 1 || count > CONFIG_MAX_CHUNKS || index < 0 || index >= count ||
      !app_message_cstring(iterator, MESSAGE_KEY_CHUNK_DATA, CONFIG_CHUNK_BYTES, &data, &length)) {
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
  if (index == 0 && s_config_transfer.next_chunk == 0) {
    s_transfer_fingerprint_a = fingerprint_a;
    s_transfer_fingerprint_b = fingerprint_b;
  } else if (fingerprint_a != s_transfer_fingerprint_a ||
             fingerprint_b != s_transfer_fingerprint_b) {
    reset_config_transfer();
    return;
  }
  const WatchTransferOutcome outcome = watch_config_transfer_accept(
      &s_config_transfer, s_chunks, sizeof(s_chunks), id, index, count, data, length);
  if (outcome == WATCH_TRANSFER_IGNORED) return;
  if (outcome == WATCH_TRANSFER_INVALID) {
    reset_config_transfer();
    return;
  }
  s_transfer_last_activity = current_second();
  if (outcome == WATCH_TRANSFER_DUPLICATE || outcome == WATCH_TRANSFER_ACCEPTED) {
    return;
  }

  int result = RESULT_CONFIG_APPLIED;
  copy_text(s_config_work, sizeof(s_config_work), s_chunks);
  const bool valid = parse_config_buffer(s_config_work, &s_parsed_config);
  if (!valid || s_parsed_config.fingerprint_a != s_transfer_fingerprint_a ||
      s_parsed_config.fingerprint_b != s_transfer_fingerprint_b) {
    result = RESULT_INVALID_CONFIG;
    show_notice(i18n_text(I18N_INVALID_CONFIGURATION), 5);
  } else if (strcmp(s_parsed_config.locus_id, s_current_locus_id) != 0) {
    result = RESULT_INVALID_CONFIG;
  } else if (store_active_config(s_chunks)) {
    install_config(&s_parsed_config);
  } else {
    result = RESULT_STORAGE_FAILED;
    show_notice(i18n_text(I18N_CONFIG_STORAGE_FULL), 5);
  }
  if (!control_enqueue_config_result(id, result)) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Reserved config-result slot was unavailable");
    show_notice(i18n_text(I18N_MESSAGE_QUEUE_ERROR), 5);
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
#if defined(PBL_MICROPHONE)
  if (command == CMD_ADD_WAYPOINT_WITH_NOTE) text = s_waypoint_name;
#endif
  if (!command_id || !command_record_add(command_id, command) ||
      !control_enqueue(MSG_COMMAND, command, command_id, text)) {
    command_record_remove(command_id);
    show_notice(i18n_text(I18N_COMMAND_QUEUE_FULL), 4);
    close_controls();
    return;
  }
  show_notice(i18n_text(I18N_SENDING), 5);
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

static void recording_selected(int index, void *context) {
  send_command(CMD_PAUSE_RESUME);
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
    message = i18n_text(I18N_NO_PHONE_INTERNET);
  } else if (status == DictationSessionStatusFailureDisabled) {
    message = i18n_text(I18N_DICTATION_DISABLED);
  } else if (status == DictationSessionStatusFailureNoSpeechDetected) {
    message = i18n_text(I18N_NO_SPEECH);
  } else {
    message = i18n_text(I18N_DICTATION_FAILED);
  }
  show_notice(message, 4);
  close_controls();
}

static void send_dictated_waypoint(void *context) {
  s_dictation_send_timer = NULL;
  send_command(CMD_ADD_WAYPOINT_WITH_NOTE);
}

static void dictation_callback(DictationSession *session, DictationSessionStatus status,
                               char *transcription, void *context) {
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
    s_dictation_session = dictation_session_create(WAYPOINT_NAME_SIZE, dictation_callback, NULL);
  }
  if (!s_dictation_session) {
    show_notice(i18n_text(I18N_DICTATION_UNAVAILABLE), 4);
    close_controls();
    return;
  }
  dictation_session_enable_confirmation(s_dictation_session, true);
  dictation_session_enable_error_dialogs(s_dictation_session, true);
  const DictationSessionStatus status = dictation_session_start(s_dictation_session);
  if (status != DictationSessionStatusSuccess) show_dictation_failure(status);
}
#endif

#if defined(PBL_MICROPHONE)
static void waypoint_menu_selected(int index, void *context) {
  if (index == 0)
    waypoint_selected(index, context);
  else
    dictated_waypoint_selected(index, context);
}

static void waypoints_selected(int index, void *context) {
  if (s_waypoint_window) window_stack_push(s_waypoint_window, true);
}
#endif

static bool rebuild_menu(void) {
  int count = 0;
  if (!s_activity_ready ||
      (s_snapshot.state != STATE_RECORDING && s_snapshot.state != STATE_PAUSED))
    return false;
  s_items[count++] = (SimpleMenuItem){
      .title = s_snapshot.state == STATE_PAUSED ? i18n_text(I18N_RESUME) : i18n_text(I18N_PAUSE),
      .callback = recording_selected,
  };
  s_items[count++] = (SimpleMenuItem){
      .title = i18n_text(I18N_STOP_SAVE),
      .callback = stop_selected,
  };
  if (s_snapshot.state == STATE_RECORDING) {
    s_items[count++] = (SimpleMenuItem){
        .title = PBL_IF_MICROPHONE_ELSE(i18n_text(I18N_WAYPOINTS), i18n_text(I18N_ADD_WAYPOINT)),
        .callback = PBL_IF_MICROPHONE_ELSE(waypoints_selected, waypoint_selected),
    };
  }
  s_section = (SimpleMenuSection){
      .title = i18n_text(I18N_CONTROLS),
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
  if (!s_activity_ready ||
      (s_snapshot.state != STATE_RECORDING && s_snapshot.state != STATE_PAUSED))
    return;
  if (!rebuild_menu()) {
    show_notice(i18n_text(I18N_NOT_ENOUGH_MEMORY), 4);
    return;
  }
  if (s_controls_window) window_stack_push(s_controls_window, true);
}

static void switch_page(int direction) {
  if (!s_activity_ready || s_profile_count < 1 ||
      (s_snapshot.state != STATE_RECORDING && s_snapshot.state != STATE_PAUSED))
    return;
  s_selected = (s_selected + direction + s_profile_count) % s_profile_count;
  layout_slots();
  show_notice(s_profiles[s_selected].name, 2);
}

static void main_up(ClickRecognizerRef recognizer, void *context) {
  switch_page(-1);
}

static void main_down(ClickRecognizerRef recognizer, void *context) {
  switch_page(1);
}

static void click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, main_select);
  window_single_click_subscribe(BUTTON_ID_UP, main_up);
  window_single_click_subscribe(BUTTON_ID_DOWN, main_down);
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
  s_header = make_text(root, GRect(0, STATUS_BAR_LAYER_HEIGHT, bounds.size.w, 25),
                       fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  s_instruction = make_text(root, GRectZero, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
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
  if (s_instruction) text_layer_destroy(s_instruction);
  s_instruction = NULL;
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

#if defined(PBL_MICROPHONE)
static void waypoint_load(Window *window) {
  s_waypoint_items[0] = (SimpleMenuItem){
      .title = i18n_text(I18N_QUICK_WAYPOINT),
      .callback = waypoint_menu_selected,
  };
  s_waypoint_items[1] = (SimpleMenuItem){
      .title = i18n_text(I18N_DICTATED_WAYPOINT),
      .callback = waypoint_menu_selected,
  };
  s_waypoint_section = (SimpleMenuSection){
      .title = i18n_text(I18N_WAYPOINTS),
      .num_items = 2,
      .items = s_waypoint_items,
  };
  Layer *root = window_get_root_layer(window);
  if (!root) return;
  s_waypoint_menu =
      simple_menu_layer_create(layer_get_bounds(root), window, &s_waypoint_section, 1, NULL);
  if (s_waypoint_menu) layer_add_child(root, simple_menu_layer_get_layer(s_waypoint_menu));
  apply_theme();
}

static void waypoint_unload(Window *window) {
  if (s_waypoint_menu) simple_menu_layer_destroy(s_waypoint_menu);
  s_waypoint_menu = NULL;
}
#endif

static void confirm_load(Window *window) {
  s_confirm_items[0] = (SimpleMenuItem){
      .title = i18n_text(I18N_SAVE_STOP),
      .subtitle = i18n_text(I18N_FINISH_RECORDING),
      .callback = confirm_selected,
  };
  s_confirm_items[1] = (SimpleMenuItem){
      .title = i18n_text(I18N_CANCEL),
      .subtitle = i18n_text(I18N_KEEP_RECORDING),
      .callback = confirm_selected,
  };
  s_confirm_section = (SimpleMenuSection){
      .title = i18n_text(I18N_STOP_RECORDING_QUESTION),
      .num_items = 2,
      .items = s_confirm_items,
  };
  Layer *root = window_get_root_layer(window);
  if (!root) return;
  s_confirm_menu =
      simple_menu_layer_create(layer_get_bounds(root), window, &s_confirm_section, 1, NULL);
  if (!s_confirm_menu) {
    show_notice(i18n_text(I18N_NOT_ENOUGH_MEMORY), 4);
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
    show_notice(i18n_text(I18N_NO_HEART_RATE), 5);
  }
}

static void health_event(HealthEventType event, void *context) {
  if (event != HealthEventHeartRateUpdate || !s_health_subscribed) return;
  const uint32_t now = current_second();
  const int32_t bpm = health_service_peek_current_value(HealthMetricHeartRateRawBPM);
  if (bpm < 25 || bpm > 250 ||
      (s_last_hr_sent_valid &&
       !watch_maintenance_reached(now, s_last_hr_sent_second + s_heart_rate_interval))) {
    return;
  }
  s_last_hr_sent_valid = true;
  s_last_hr_sent_second = now;
  s_pending_hr = bpm;
  s_hr_generation++;
  s_hr_pending = true;
  if (s_health_timeout) {
    app_timer_cancel(s_health_timeout);
    s_health_timeout = NULL;
  }
  send_next();
  schedule_maintenance();
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

static bool fresh_recording_snapshot_at(uint32_t now) {
  return s_snapshot_received &&
         !watch_maintenance_reached(now, s_snapshot_second + SNAPSHOT_STALE_SECONDS + 1) &&
         s_snapshot.state == STATE_RECORDING;
}

static void update_health_subscription_at(uint32_t now) {
  const bool wanted = s_watch_hr_to_locus && fresh_recording_snapshot_at(now);
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
    show_notice(i18n_text(I18N_HEART_RATE_UNAVAILABLE), 5);
  }
}

static void update_health_subscription(void) {
  update_health_subscription_at(current_second());
}

static bool sample_steps(uint32_t now) {
  bool accessible = false;
  int64_t total = -1;
#if defined(PBL_HEALTH)
  const time_t end = time(NULL);
  accessible = health_service_metric_accessible(HealthMetricStepCount, time_start_of_today(), end) &
               HealthServiceAccessibilityMaskAvailable;
  if (accessible) {
    total = health_service_sum_today(HealthMetricStepCount);
  }
#endif
  const WatchStepEffects effects =
      watch_step_state_health_read(&s_step_state, accessible, total, now, UNAVAILABLE);
  if (effects.render && !watch_step_state_available(&s_step_state)) {
    s_snapshot.steps = UNAVAILABLE;
  }
  send_next();
  return effects.render;
}

static bool update_step_state(uint32_t now) {
  const bool active = s_snapshot_received &&
                      (s_snapshot.state == STATE_RECORDING || s_snapshot.state == STATE_PAUSED);
  const WatchStepEffects effects =
      watch_step_state_update(&s_step_state, s_watch_steps_to_locus, s_activity_ready, active,
                              s_snapshot_received && !s_snapshot_stale_processed,
                              (WatchStepRecording){
                                  .recording_start_low = s_snapshot.recording_start_low,
                                  .recording_start_high = s_snapshot.recording_start_high,
                              },
                              s_outbox_busy && s_inflight_kind == OUTBOUND_STEPS);
  if (effects.discarded_prepared && s_send_attempts[OUTBOUND_STEPS]) {
    if (s_retry_timer) {
      app_timer_cancel(s_retry_timer);
      s_retry_timer = NULL;
    }
    watch_outbound_retry_reset(&s_send_attempts[OUTBOUND_STEPS]);
  }
  bool render_needed = effects.render;
  if (effects.render && !watch_step_state_available(&s_step_state)) {
    s_snapshot.steps = UNAVAILABLE;
  }
  if (effects.sample_now) render_needed = sample_steps(now) || render_needed;
  send_next();
  return render_needed;
}

static void accept_snapshot(DictionaryIterator *iterator) {
  Snapshot candidate;
  if (!app_message_snapshot(iterator, &candidate)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Rejected incomplete or invalid snapshot");
    return;
  }
  if (!watch_snapshot_epoch_allowed(s_snapshot_received, s_snapshot.sample_epoch,
                                    candidate.sample_epoch)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Rejected older snapshot");
    return;
  }
  const int old_state = s_snapshot.state;
  const uint32_t now = current_second();
  s_snapshot = candidate;
  s_snapshot_received = true;
  s_no_bridge_escalated = true;
  s_snapshot_stale_processed = false;
  s_snapshot_second = now;
  if (s_snapshot.state == STATE_STOPPED || s_snapshot.state == STATE_UNAVAILABLE) {
    s_health_notice_shown = false;
    s_context_active = false;
    s_activity_ready = false;
    s_selected = 0;
    s_context_started = 0;
    s_profile_preparation_escalated = true;
  } else if (!s_context_active) {
    s_context_active = true;
    s_context_started = now;
    s_profile_preparation_escalated = false;
  }
  update_step_state(now);
  update_health_subscription_at(now);
  if (old_state != s_snapshot.state && s_controls_window &&
      window_stack_contains_window(s_controls_window)) {
    rebuild_menu();
  }
  render();
  schedule_maintenance();
}

static void request_runtime_config_at(uint32_t now) {
  if (!s_current_locus_id[0]) return;
  if (control_enqueue(MSG_REQUEST_RUNTIME_CONFIG, 0, 0, NULL)) {
    s_runtime_config_pending = false;
    s_last_runtime_config_request = now;
  } else {
    s_runtime_config_pending = true;
  }
}

static void accept_recording_context(DictionaryIterator *iterator) {
  const uint32_t now = current_second();
  int32_t state = STATE_UNAVAILABLE;
  if (!app_message_int32(iterator, MESSAGE_KEY_RECORDING_STATE, &state) ||
      (state != STATE_RECORDING && state != STATE_PAUSED)) {
    if (state == STATE_STOPPED || state == STATE_UNAVAILABLE) {
      s_context_active = false;
      s_activity_ready = false;
      s_runtime_config_pending = false;
      s_profile_preparation_escalated = true;
      s_selected = 0;
      update_step_state(now);
      render();
    }
    return;
  }
  const char *id = NULL;
  const char *name = NULL;
  size_t id_length = 0;
  size_t name_length = 0;
  if (!app_message_cstring(iterator, MESSAGE_KEY_LOCUS_PROFILE_ID, LOCUS_ID_SIZE - 1, &id,
                           &id_length) ||
      !app_message_cstring(iterator, MESSAGE_KEY_LOCUS_PROFILE_NAME, LOCUS_NAME_SIZE - 1, &name,
                           &name_length) ||
      !id_length || !name_length || !watch_locus_profile_valid(id, name)) {
    s_context_active = true;
    s_activity_ready = false;
    if (!s_context_started) {
      s_context_started = now;
      s_profile_preparation_escalated = false;
    }
    update_step_state(now);
    render();
    return;
  }
  const bool changed = !s_context_active || strcmp(id, s_current_locus_id) != 0;
  copy_text(s_current_locus_id, sizeof(s_current_locus_id), id);
  s_context_active = true;
  s_activity_ready = strcmp(s_current_locus_id, s_config_locus_id) == 0;
  const WatchContextDecision decision =
      watch_maintenance_context_decision(changed, s_activity_ready);
  if (decision.reset_projection_ui) {
    s_selected = 0;
    s_context_started = now;
    s_profile_preparation_escalated = false;
  }
  if (s_activity_ready) s_profile_preparation_escalated = true;
  layout_slots();
  if (decision.request_runtime_config) {
    request_runtime_config_at(now);
  }
  update_step_state(now);
  render();
  send_next();
  schedule_maintenance();
}

static void accept_command_result(DictionaryIterator *iterator) {
  uint32_t session_id;
  uint32_t command_id;
  int32_t result;
  if (!app_message_uint32(iterator, MESSAGE_KEY_SESSION_ID, &session_id) ||
      !app_message_uint32(iterator, MESSAGE_KEY_COMMAND_ID, &command_id) ||
      !app_message_int32(iterator, MESSAGE_KEY_RESULT, &result) || result < RESULT_OK ||
      result > RESULT_INVALID_WAYPOINT_NAME || session_id != s_session_id) {
    return;
  }
  CommandRecord *record = command_record_find(command_id);
  if (!record) return;
  const int command = record->command;
  command_record_remove(command_id);
  vibes_short_pulse();
  if (result == RESULT_INVALID_PROFILE) {
    show_notice(i18n_text(I18N_INVALID_PROFILE), 4);
  } else if (result == RESULT_PROFILE_NOT_FOUND) {
    show_notice(i18n_text(I18N_PROFILE_NOT_IN_LOCUS), 4);
  } else if (result == RESULT_INVALID_WAYPOINT_NAME) {
    show_notice(i18n_text(I18N_INVALID_WAYPOINT_NOTE), 4);
  } else if (result == RESULT_OK &&
             (command == CMD_ADD_WAYPOINT || command == CMD_ADD_WAYPOINT_WITH_NOTE)) {
    show_notice(i18n_text(I18N_WAYPOINT_ADDED), 4);
  } else if (result == RESULT_OK) {
    show_notice(i18n_text(I18N_COMMAND_ACCEPTED), 4);
  } else {
    snprintf(s_notice, sizeof(s_notice), "%s (%ld)", i18n_text(I18N_COMMAND_FAILED), (long)result);
    s_notice_until = current_second() + 4;
    render();
    schedule_maintenance();
  }
}

static void inbox(DictionaryIterator *iterator, void *context) {
  int32_t version;
  if (!app_message_int32(iterator, MESSAGE_KEY_PROTOCOL_VERSION, &version) ||
      version != PROTOCOL_VERSION) {
    show_notice(i18n_text(I18N_PROTOCOL_MISMATCH), 6);
    return;
  }
  const char *release;
  size_t release_length;
  if (!app_message_cstring(iterator, MESSAGE_KEY_APP_VERSION, sizeof(RELEASE_VERSION) - 1, &release,
                           &release_length) ||
      release_length != sizeof(RELEASE_VERSION) - 1 ||
      memcmp(release, RELEASE_VERSION, sizeof(RELEASE_VERSION) - 1) != 0) {
    show_notice(i18n_text(I18N_UPDATE_BRIDGE_WATCH), 10);
    return;
  }
  int32_t type;
  if (!app_message_int32(iterator, MESSAGE_KEY_MESSAGE_TYPE, &type)) return;
  if (type == MSG_CONFIG_CHUNK) {
    accept_config_chunk(iterator);
  } else if (type == MSG_PROFILE_LIST_CHUNK) {
    accept_profile_chunk(iterator);
  } else if (type == MSG_REQUEST_PROFILE_LIST) {
    if (s_relay_count) {
      s_request_profiles_after_relay = true;
    } else if (s_profile_transfer.id >= 0 || s_profile_pending_ready) {
      s_request_profiles_after_relay = true;
    } else {
      if (!control_enqueue(MSG_REQUEST_PROFILE_LIST, 0, 0, NULL)) {
        s_request_profiles_after_relay = true;
      }
      send_next();
    }
  } else if (type == MSG_SNAPSHOT) {
    accept_snapshot(iterator);
  } else if (type == MSG_RECORDING_CONTEXT) {
    accept_recording_context(iterator);
  } else if (type == MSG_COMMAND_RESULT) {
    accept_command_result(iterator);
  }
  schedule_maintenance();
}

static void maintenance_add(WatchMaintenanceDeadlines *deadlines, WatchMaintenanceKind kind,
                            uint32_t deadline) {
  deadlines->active |= WATCH_MAINTENANCE_BIT(kind);
  deadlines->deadlines[kind] = deadline;
}

static WatchMaintenanceDeadlines maintenance_deadlines(void) {
  WatchMaintenanceDeadlines deadlines = {0};
  if (s_notice_until) maintenance_add(&deadlines, WATCH_MAINTENANCE_NOTICE, s_notice_until);
  if (s_config_transfer.id >= 0) {
    maintenance_add(&deadlines, WATCH_MAINTENANCE_CONFIG_TRANSFER,
                    s_transfer_last_activity + CONFIG_TRANSFER_TIMEOUT_SECONDS);
  }
  if (s_profile_transfer.id >= 0) {
    maintenance_add(&deadlines, WATCH_MAINTENANCE_PROFILE_TRANSFER,
                    s_profile_transfer.last_activity + PROFILE_TRANSFER_TIMEOUT_SECONDS);
  }
  bool command_deadline = false;
  for (int i = 0; i < COMMAND_RECORD_COUNT; i++) {
    const CommandRecord *record = &s_command_records[i];
    if (!record->used || !record->awaiting_result) continue;
    if (!command_deadline ||
        (int32_t)(record->result_deadline - deadlines.deadlines[WATCH_MAINTENANCE_COMMAND]) < 0) {
      deadlines.deadlines[WATCH_MAINTENANCE_COMMAND] = record->result_deadline;
      command_deadline = true;
    }
  }
  if (command_deadline) deadlines.active |= WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_COMMAND);
  if (s_snapshot_received && !s_snapshot_stale_processed) {
    maintenance_add(&deadlines, WATCH_MAINTENANCE_STALE,
                    s_snapshot_second + SNAPSHOT_STALE_SECONDS + 1);
  }
  if (!s_snapshot_received && !s_no_bridge_escalated) {
    maintenance_add(&deadlines, WATCH_MAINTENANCE_NO_BRIDGE, s_launch_second + 10);
  }
  if (s_context_active && !s_activity_ready && !s_profile_preparation_escalated) {
    maintenance_add(&deadlines, WATCH_MAINTENANCE_PROFILE_PREPARATION, s_context_started + 15);
  }
  if (watch_maintenance_should_schedule_reconciliation(
          s_context_active, s_current_locus_id[0] != '\0', s_runtime_config_pending)) {
    maintenance_add(&deadlines, WATCH_MAINTENANCE_RECONCILIATION,
                    s_last_runtime_config_request + 60);
  }
  uint32_t step_deadline = 0;
  if (watch_step_state_sample_deadline(&s_step_state, &step_deadline)) {
    maintenance_add(&deadlines, WATCH_MAINTENANCE_STEPS, step_deadline);
  }
  return deadlines;
}

static void process_maintenance(WatchMaintenancePlan plan, uint32_t now) {
  bool needs_render = false;
  if (plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_NOTICE)) {
    s_notice_until = 0;
    needs_render = true;
  }
  if (plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_CONFIG_TRANSFER)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Discarding expired configuration transfer");
    reset_config_transfer();
  }
  if (plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_PROFILE_TRANSFER)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Discarding expired profile-list transfer");
    reset_profile_transfer();
  }
  if ((plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_COMMAND)) &&
      expire_command_records(now)) {
    copy_text(s_notice, sizeof(s_notice), i18n_text(I18N_COMMAND_RESPONSE_TIMEOUT));
    s_notice_until = now + 4;
    needs_render = true;
  }
  if (plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_STALE)) {
    s_snapshot_stale_processed = true;
    needs_render = update_step_state(now) || needs_render;
    update_health_subscription_at(now);
    needs_render = true;
  }
  if (plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_STEPS)) {
    needs_render = sample_steps(now) || needs_render;
  }
  if (plan.due & (WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_NO_BRIDGE) |
                  WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_PROFILE_PREPARATION))) {
    if (plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_NO_BRIDGE)) {
      s_no_bridge_escalated = true;
    }
    if (plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_PROFILE_PREPARATION)) {
      s_profile_preparation_escalated = true;
    }
    needs_render = true;
  }
  service_pending_control_work_at(now);
  if (plan.due & WATCH_MAINTENANCE_BIT(WATCH_MAINTENANCE_RECONCILIATION)) {
    request_runtime_config_at(now);
  }
  if (needs_render) render();
  send_next();
}

static void maintenance_callback(void *context) {
  (void)context;
  const WatchMaintenanceClock now = current_clock();
  const WatchMaintenanceDeadlines deadlines = maintenance_deadlines();
  const WatchMaintenancePlan plan = watch_maintenance_plan(&deadlines, now);
  process_maintenance(plan, now.seconds);
  const WatchMaintenanceDeadlines next_deadlines = maintenance_deadlines();
  const WatchMaintenancePlan next_plan = watch_maintenance_plan(&next_deadlines, now);
  const bool has_deadline = next_plan.due || next_plan.has_next;
  const uint32_t deadline = next_plan.due ? now.seconds : next_plan.next_deadline;
  const uint32_t delay = next_plan.due ? 1 : next_plan.delay_ms;
  if (!watch_maintenance_timer_schedule(&s_maintenance_timer, has_deadline, deadline, delay) &&
      has_deadline) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Could not allocate maintenance timer");
  }
}

static void schedule_maintenance(void) {
  const WatchMaintenanceClock now = current_clock();
  const WatchMaintenanceDeadlines deadlines = maintenance_deadlines();
  const WatchMaintenancePlan plan = watch_maintenance_plan(&deadlines, now);
  const bool has_deadline = plan.due || plan.has_next;
  const uint32_t deadline = plan.due ? now.seconds : plan.next_deadline;
  const uint32_t delay = plan.due ? 1 : plan.delay_ms;
  if (!watch_maintenance_timer_schedule(&s_maintenance_timer, has_deadline, deadline, delay) &&
      has_deadline) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Could not allocate maintenance timer");
  }
}

static bool create_windows(void) {
  s_main_window = window_create();
  s_controls_window = window_create();
  s_confirm_window = window_create();
  s_waypoint_window = window_create();
  if (!s_main_window || !s_controls_window || !s_confirm_window || !s_waypoint_window) return false;
  window_set_window_handlers(s_main_window, (WindowHandlers){
                                                .load = main_load,
                                                .unload = main_unload,
                                            });
  window_set_click_config_provider(s_main_window, click_config);
  window_set_window_handlers(s_controls_window, (WindowHandlers){
                                                    .unload = controls_unload,
                                                });
  window_set_window_handlers(s_confirm_window, (WindowHandlers){
                                                   .load = confirm_load,
                                                   .unload = confirm_unload,
                                               });
#if defined(PBL_MICROPHONE)
  window_set_window_handlers(s_waypoint_window, (WindowHandlers){
                                                    .load = waypoint_load,
                                                    .unload = waypoint_unload,
                                                });
#endif
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
  watch_maintenance_timer_initialize(&s_maintenance_timer,
                                     (WatchMaintenanceTimerOperations){
                                         .register_timer = register_maintenance_timer,
                                         .reschedule_timer = reschedule_maintenance_timer,
                                         .cancel_timer = cancel_maintenance_timer,
                                     },
                                     NULL, maintenance_callback, NULL);
  watch_step_state_initialize(&s_step_state);
  watch_config_transfer_initialize(&s_config_transfer);
  watch_profile_transfer_initialize(&s_profile_transfer);
  i18n_set_locale(i18n_locale(i18n_get_system_locale()));
  default_profiles();
  // Version 0.2.1 caches only the active projection; old journals and catalogs are obsolete.
  (void)persistent_blob_delete(&s_pending_config_blob);
  (void)persistent_blob_delete(&s_obsolete_profile_list_blob);
  if (persistent_blob_read(&s_config_blob, s_config_work, sizeof(s_config_work))) {
    if (parse_config_buffer(s_config_work, &s_parsed_config)) {
      install_config(&s_parsed_config);
    } else {
      APP_LOG(APP_LOG_LEVEL_ERROR, "Stored configuration is invalid");
    }
  }
  s_session_id = create_session_id();
  s_next_command_id = 1;
  s_launch_second = current_second();
  APP_LOG(APP_LOG_LEVEL_INFO, "Persistent capacity: %lu", (unsigned long)persist_get_max_size());

  if (!create_windows()) return false;
  app_message_register_inbox_received(inbox);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_register_outbox_sent(outbox_sent);
  app_message_register_outbox_failed(outbox_failed);
  const AppMessageResult open_result = app_message_open(512, 512);
  if (open_result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "AppMessage open failed: %d", (int)open_result);
    copy_text(s_notice, sizeof(s_notice), i18n_text(I18N_MESSAGING_UNAVAILABLE));
    s_notice_until = current_second() + 30;
  }
  window_stack_push(s_main_window, true);
  if (open_result == APP_MSG_OK) {
    control_enqueue(MSG_REQUEST_SNAPSHOT, 0, 0, NULL);
    control_enqueue(MSG_REQUEST_PROFILE_LIST, 0, 0, NULL);
    send_next();
  }
  schedule_maintenance();
  return true;
}

static void destroy_window(Window **window) {
  if (!window || !*window) return;
  window_destroy(*window);
  *window = NULL;
}

static void deinit(void) {
  stop_health();
  watch_maintenance_timer_cancel(&s_maintenance_timer);
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
  destroy_window(&s_waypoint_window);
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
