#include <pebble.h>

enum {
  MSG_SNAPSHOT = 1, MSG_COMMAND = 2, MSG_COMMAND_RESULT = 3, MSG_REQUEST_SNAPSHOT = 4
};
enum { STATE_STOPPED = 0, STATE_RECORDING = 1, STATE_PAUSED = 2, STATE_UNAVAILABLE = 3 };
enum { CMD_START = 1, CMD_PAUSE_RESUME = 2, CMD_STOP_SAVE = 3, CMD_ADD_WAYPOINT = 4 };

typedef struct {
  int state;
  uint32_t sample_epoch;
  uint32_t elapsed_seconds;
  int32_t distance_metres;
  int32_t current_speed_cmps;
  int32_t average_speed_cmps;
  int32_t altitude_decimetres;
  int32_t ascent_decimetres;
} Snapshot;

static Window *s_main_window;
static Window *s_controls_window;
static Window *s_confirm_window;
static TextLayer *s_status_layer;
static TextLayer *s_label_layers[6];
static TextLayer *s_value_layers[6];
static SimpleMenuLayer *s_simple_menu;
static SimpleMenuLayer *s_confirm_menu;
static SimpleMenuItem s_menu_items[4];
static SimpleMenuItem s_confirm_items[2];
static SimpleMenuSection s_menu_section;
static SimpleMenuSection s_confirm_section;
static Snapshot s_snapshot = {.state = STATE_UNAVAILABLE};
static uint32_t s_next_command_id = 1;
static char s_values[6][20];
static char s_status[32] = "Connecting...";

static int32_t tuple_int(DictionaryIterator *iter, uint32_t key, int32_t fallback) {
  Tuple *tuple = dict_find(iter, key);
  return tuple ? tuple->value->int32 : fallback;
}

static void render(void) {
  time_t now = time(NULL);
  bool stale = s_snapshot.sample_epoch == 0 || now - (time_t)s_snapshot.sample_epoch > 30;
  const char *state = s_snapshot.state == STATE_RECORDING ? "Recording" :
      s_snapshot.state == STATE_PAUSED ? "Paused" :
      s_snapshot.state == STATE_STOPPED ? "Stopped" : "No Locus";
  snprintf(s_status, sizeof(s_status), "%s%s", state, stale ? " | stale" : "");
  text_layer_set_text(s_status_layer, s_status);

  uint32_t elapsed = s_snapshot.elapsed_seconds;
  if (!stale && s_snapshot.state == STATE_RECORDING && now > (time_t)s_snapshot.sample_epoch) {
    elapsed += (uint32_t)(now - (time_t)s_snapshot.sample_epoch);
  }
  snprintf(s_values[0], sizeof(s_values[0]), "%02lu:%02lu:%02lu",
      (unsigned long)(elapsed / 3600), (unsigned long)((elapsed / 60) % 60),
      (unsigned long)(elapsed % 60));
  snprintf(s_values[1], sizeof(s_values[1]), "%ld.%02ld km",
      (long)(s_snapshot.distance_metres / 1000),
      (long)((s_snapshot.distance_metres % 1000) / 10));
  int32_t current_kmh10 = s_snapshot.current_speed_cmps * 36 / 100;
  int32_t average_kmh10 = s_snapshot.average_speed_cmps * 36 / 100;
  snprintf(s_values[2], sizeof(s_values[2]), "%ld.%ld km/h",
      (long)(current_kmh10 / 10), (long)(current_kmh10 % 10));
  snprintf(s_values[3], sizeof(s_values[3]), "%ld.%ld km/h",
      (long)(average_kmh10 / 10), (long)(average_kmh10 % 10));
  snprintf(s_values[4], sizeof(s_values[4]), "%ld m", (long)(s_snapshot.altitude_decimetres / 10));
  snprintf(s_values[5], sizeof(s_values[5]), "%ld m", (long)(s_snapshot.ascent_decimetres / 10));
  for (int i = 0; i < 6; ++i) text_layer_set_text(s_value_layers[i], s_values[i]);
}

static void send_message(int message_type, int command) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) return;
  dict_write_int32(iter, MESSAGE_KEY_PROTOCOL_VERSION, 1);
  dict_write_int32(iter, MESSAGE_KEY_MESSAGE_TYPE, message_type);
  if (message_type == MSG_COMMAND) {
    dict_write_uint32(iter, MESSAGE_KEY_COMMAND_ID, s_next_command_id++);
    dict_write_int32(iter, MESSAGE_KEY_COMMAND, command);
  }
  app_message_outbox_send();
}

static void send_command(int command) {
  snprintf(s_status, sizeof(s_status), "Sending...");
  text_layer_set_text(s_status_layer, s_status);
  send_message(MSG_COMMAND, command);
  window_stack_pop(true);
}

static void control_selected(int index, void *context) {
  int command;
  if (s_snapshot.state == STATE_STOPPED) {
    command = CMD_START;
  } else if (index == 0) {
    command = CMD_PAUSE_RESUME;
  } else if (index == 1) {
    command = CMD_STOP_SAVE;
  } else {
    command = CMD_ADD_WAYPOINT;
  }
  if (command == CMD_STOP_SAVE) {
    window_stack_push(s_confirm_window, true);
  } else {
    send_command(command);
  }
}

static void rebuild_menu(void) {
  int count = 0;
  if (s_snapshot.state == STATE_STOPPED) {
    s_menu_items[count++] = (SimpleMenuItem){.title = "Start recording", .callback = control_selected};
  } else if (s_snapshot.state == STATE_RECORDING || s_snapshot.state == STATE_PAUSED) {
    s_menu_items[count++] = (SimpleMenuItem){
        .title = s_snapshot.state == STATE_PAUSED ? "Resume" : "Pause",
        .callback = control_selected};
    s_menu_items[count++] = (SimpleMenuItem){.title = "Stop & save", .callback = control_selected};
    if (s_snapshot.state == STATE_RECORDING) {
      s_menu_items[count++] = (SimpleMenuItem){.title = "Add waypoint", .callback = control_selected};
    }
  } else {
    s_menu_items[count++] = (SimpleMenuItem){.title = "Locus unavailable", .subtitle = "Open Locus Map"};
  }
  s_menu_section = (SimpleMenuSection){.title = "Recording controls", .num_items = count,
      .items = s_menu_items};
  if (s_simple_menu) layer_remove_from_parent(simple_menu_layer_get_layer(s_simple_menu));
  s_simple_menu = simple_menu_layer_create(layer_get_bounds(window_get_root_layer(s_controls_window)),
      s_controls_window, &s_menu_section, 1, NULL);
  layer_add_child(window_get_root_layer(s_controls_window), simple_menu_layer_get_layer(s_simple_menu));
}

static void main_select(ClickRecognizerRef recognizer, void *context) {
  rebuild_menu();
  window_stack_push(s_controls_window, true);
}

static void main_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, main_select);
}

static void confirm_selected(int index, void *context) {
  if (index == 0) {
    window_stack_pop(false);
    send_command(CMD_STOP_SAVE);
  } else {
    window_stack_pop(true);
  }
}

static void confirm_load(Window *window) {
  s_confirm_items[0] = (SimpleMenuItem){
      .title = "Save & stop", .subtitle = "Finish the recording", .callback = confirm_selected};
  s_confirm_items[1] = (SimpleMenuItem){
      .title = "Cancel", .subtitle = "Keep recording", .callback = confirm_selected};
  s_confirm_section = (SimpleMenuSection){
      .title = "Stop recording?", .num_items = 2, .items = s_confirm_items};
  s_confirm_menu = simple_menu_layer_create(
      layer_get_bounds(window_get_root_layer(window)), window, &s_confirm_section, 1, NULL);
  layer_add_child(window_get_root_layer(window), simple_menu_layer_get_layer(s_confirm_menu));
}

static void confirm_unload(Window *window) {
  simple_menu_layer_destroy(s_confirm_menu);
  s_confirm_menu = NULL;
}

static void inbox_received(DictionaryIterator *iter, void *context) {
  if (tuple_int(iter, MESSAGE_KEY_PROTOCOL_VERSION, 0) != 1) return;
  int type = tuple_int(iter, MESSAGE_KEY_MESSAGE_TYPE, 0);
  if (type == MSG_SNAPSHOT) {
    s_snapshot.state = tuple_int(iter, MESSAGE_KEY_RECORDING_STATE, STATE_UNAVAILABLE);
    s_snapshot.sample_epoch = (uint32_t)tuple_int(iter, MESSAGE_KEY_SAMPLE_EPOCH_SECONDS, 0);
    s_snapshot.elapsed_seconds = (uint32_t)tuple_int(iter, MESSAGE_KEY_ELAPSED_SECONDS, 0);
    s_snapshot.distance_metres = tuple_int(iter, MESSAGE_KEY_DISTANCE_METRES, 0);
    s_snapshot.current_speed_cmps = tuple_int(iter, MESSAGE_KEY_CURRENT_SPEED_CMPS, 0);
    s_snapshot.average_speed_cmps = tuple_int(iter, MESSAGE_KEY_AVERAGE_SPEED_CMPS, 0);
    s_snapshot.altitude_decimetres = tuple_int(iter, MESSAGE_KEY_ALTITUDE_DECIMETRES, 0);
    s_snapshot.ascent_decimetres = tuple_int(iter, MESSAGE_KEY_ASCENT_DECIMETRES, 0);
    render();
  } else if (type == MSG_COMMAND_RESULT) {
    int result = tuple_int(iter, MESSAGE_KEY_RESULT, 3);
    vibes_short_pulse();
    snprintf(s_status, sizeof(s_status), result == 0 ? "Command accepted" : "Command failed (%d)", result);
    text_layer_set_text(s_status_layer, s_status);
  }
}

static void tick(struct tm *tick_time, TimeUnits units_changed) { render(); }

static TextLayer *make_text(Layer *root, GRect frame, GFont font, GTextAlignment align) {
  TextLayer *layer = text_layer_create(frame);
  text_layer_set_background_color(layer, GColorClear);
  text_layer_set_text_color(layer, GColorBlack);
  text_layer_set_font(layer, font);
  text_layer_set_text_alignment(layer, align);
  layer_add_child(root, text_layer_get_layer(layer));
  return layer;
}

static void main_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);
#if defined(PBL_PLATFORM_EMERY) || defined(PBL_PLATFORM_GABBRO)
  const int status_height = 26;
  GFont status_font = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  GFont label_font = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  GFont value_font = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
#else
  const int status_height = 20;
  GFont status_font = fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD);
  GFont label_font = fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD);
  GFont value_font = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
#endif
  s_status_layer = make_text(root, GRect(2, 0, bounds.size.w - 4, status_height),
      status_font, GTextAlignmentCenter);
  const char *labels[] = {"Time", "Distance", "Speed", "Average", "Altitude", "Ascent"};
#ifdef PBL_ROUND
  const int content_x = 24;
  const int content_width = bounds.size.w - 48;
  const int content_y = 30;
  const int row_height = (bounds.size.h - 52) / 3;
#else
  const int content_x = 0;
  const int content_width = bounds.size.w;
  const int content_y = status_height;
  const int row_height = (bounds.size.h - status_height) / 3;
#endif
  const int column_width = content_width / 2;
  for (int i = 0; i < 6; ++i) {
    int col = i % 2, row = i / 2;
    int x = content_x + col * column_width, y = content_y + row * row_height;
    s_label_layers[i] = make_text(root, GRect(x + 2, y, column_width - 4, row_height / 2),
        label_font, GTextAlignmentCenter);
    text_layer_set_text(s_label_layers[i], labels[i]);
    s_value_layers[i] = make_text(root,
        GRect(x + 1, y + row_height / 3, column_width - 2, row_height * 2 / 3),
        value_font, GTextAlignmentCenter);
  }
  render();
}

static void main_unload(Window *window) {
  text_layer_destroy(s_status_layer);
  for (int i = 0; i < 6; ++i) {
    text_layer_destroy(s_label_layers[i]);
    text_layer_destroy(s_value_layers[i]);
  }
}

static void controls_unload(Window *window) {
  if (s_simple_menu) { simple_menu_layer_destroy(s_simple_menu); s_simple_menu = NULL; }
}

static void init(void) {
  s_main_window = window_create();
  window_set_window_handlers(s_main_window, (WindowHandlers){.load = main_load, .unload = main_unload});
  window_set_click_config_provider(s_main_window, main_click_config);
  s_controls_window = window_create();
  window_set_window_handlers(s_controls_window, (WindowHandlers){.unload = controls_unload});
  s_confirm_window = window_create();
  window_set_window_handlers(s_confirm_window, (WindowHandlers){.load = confirm_load, .unload = confirm_unload});

  app_message_register_inbox_received(inbox_received);
  app_message_open(512, 128);
  tick_timer_service_subscribe(SECOND_UNIT, tick);
  window_stack_push(s_main_window, true);
  send_message(MSG_REQUEST_SNAPSHOT, 0);
}

static void deinit(void) {
  tick_timer_service_unsubscribe();
  window_destroy(s_confirm_window);
  window_destroy(s_controls_window);
  window_destroy(s_main_window);
}

int main(void) { init(); app_event_loop(); deinit(); }
