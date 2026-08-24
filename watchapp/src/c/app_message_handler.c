#include "app_message_handler.h"

#include <limits.h>
#include <string.h>

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

bool app_message_int32(DictionaryIterator *iterator, uint32_t key, int32_t *output) {
  Tuple *tuple = iterator ? dict_find(iterator, key) : NULL;
  if (tuple_signed_value(tuple, output)) return true;
  uint32_t value;
  if (!tuple_unsigned_value(tuple, &value) || value > INT32_MAX) return false;
  *output = (int32_t)value;
  return true;
}

bool app_message_uint32(DictionaryIterator *iterator, uint32_t key, uint32_t *output) {
  Tuple *tuple = iterator ? dict_find(iterator, key) : NULL;
  if (tuple_unsigned_value(tuple, output)) return true;
  int32_t value;
  if (!tuple_signed_value(tuple, &value) || value < 0) return false;
  *output = (uint32_t)value;
  return true;
}

bool app_message_cstring(DictionaryIterator *iterator, uint32_t key, size_t max_bytes,
                         const char **output, size_t *length) {
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

int app_message_transfer_generation(DictionaryIterator *iterator, int32_t expected_generation) {
  if (!iterator || !dict_find(iterator, MESSAGE_KEY_TRANSFER_GENERATION)) return 0;
  int32_t generation = 0;
  return app_message_int32(iterator, MESSAGE_KEY_TRANSFER_GENERATION, &generation) &&
                 generation == expected_generation
             ? 1
             : -1;
}

bool app_message_snapshot(DictionaryIterator *iterator, UiMetricSnapshot *output) {
  if (!iterator || !output) return false;
  UiMetricSnapshot snapshot = {0};
  int32_t state;
  if (!app_message_int32(iterator, MESSAGE_KEY_RECORDING_STATE, &state) ||
      !app_message_uint32(iterator, MESSAGE_KEY_SAMPLE_EPOCH_SECONDS, &snapshot.sample_epoch) ||
      !app_message_uint32(iterator, MESSAGE_KEY_ELAPSED_SECONDS, &snapshot.elapsed) ||
      !app_message_int32(iterator, MESSAGE_KEY_MOVING_SECONDS, &snapshot.moving_time) ||
      !app_message_int32(iterator, MESSAGE_KEY_DISTANCE_VALUE, &snapshot.distance) ||
      !app_message_int32(iterator, MESSAGE_KEY_MOVING_DISTANCE_VALUE, &snapshot.moving_distance) ||
      !app_message_int32(iterator, MESSAGE_KEY_CURRENT_SPEED_VALUE, &snapshot.current_speed) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_SPEED_VALUE, &snapshot.average_speed) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_SPEED_VALUE, &snapshot.max_speed) ||
      !app_message_int32(iterator, MESSAGE_KEY_CURRENT_PACE_SECONDS, &snapshot.current_pace) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_PACE_SECONDS, &snapshot.average_pace) ||
      !app_message_int32(iterator, MESSAGE_KEY_ALTITUDE_VALUE, &snapshot.altitude) ||
      !app_message_int32(iterator, MESSAGE_KEY_ASCENT_VALUE, &snapshot.ascent) ||
      !app_message_int32(iterator, MESSAGE_KEY_DESCENT_VALUE, &snapshot.descent) ||
      !app_message_int32(iterator, MESSAGE_KEY_VERTICAL_SPEED_VALUE, &snapshot.vertical_speed) ||
      !app_message_int32(iterator, MESSAGE_KEY_SLOPE_VALUE, &snapshot.slope) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_HEART_RATE, &snapshot.avg_hr) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_HEART_RATE, &snapshot.max_hr) ||
      !app_message_int32(iterator, MESSAGE_KEY_CURRENT_HEART_RATE, &snapshot.current_hr) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_CADENCE, &snapshot.avg_cadence) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_CADENCE, &snapshot.max_cadence) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_POWER, &snapshot.avg_power) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_POWER, &snapshot.max_power) ||
      !app_message_int32(iterator, MESSAGE_KEY_ENERGY_VALUE, &snapshot.energy) ||
      !app_message_int32(iterator, MESSAGE_KEY_ALTITUDE_FORMAT, &snapshot.altitude_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_DISTANCE_FORMAT, &snapshot.distance_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_MOVING_DISTANCE_FORMAT,
                         &snapshot.moving_distance_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_CURRENT_SPEED_FORMAT,
                         &snapshot.current_speed_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_SPEED_FORMAT,
                         &snapshot.average_speed_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_SPEED_FORMAT, &snapshot.max_speed_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_VERTICAL_SPEED_FORMAT,
                         &snapshot.vertical_speed_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_SLOPE_FORMAT, &snapshot.slope_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_ENERGY_FORMAT, &snapshot.energy_format) ||
      !app_message_int32(iterator, MESSAGE_KEY_PACE_FORMAT, &snapshot.pace_format)) {
    return false;
  }
  snapshot.state = state;
  if (!ui_metric_snapshot_valid(&snapshot)) return false;
  *output = snapshot;
  return true;
}
