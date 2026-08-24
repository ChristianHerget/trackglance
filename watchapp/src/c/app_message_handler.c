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

bool app_message_cstring(
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

int app_message_transfer_generation(DictionaryIterator *iterator, int32_t expected_generation) {
  if (!iterator || !dict_find(iterator, MESSAGE_KEY_TRANSFER_GENERATION)) return 0;
  int32_t generation = 0;
  return app_message_int32(iterator, MESSAGE_KEY_TRANSFER_GENERATION, &generation) &&
      generation == expected_generation ? 1 : -1;
}

static bool nonnegative_wire_value(int32_t value) {
  return value == UI_METRIC_UNAVAILABLE || value >= 0;
}

static bool snapshot_values_valid(const UiMetricSnapshot *snapshot) {
  return snapshot && snapshot->state >= 0 && snapshot->state <= 3 &&
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

bool app_message_snapshot(DictionaryIterator *iterator, UiMetricSnapshot *output) {
  if (!iterator || !output) return false;
  UiMetricSnapshot snapshot = {0};
  int32_t state;
  int32_t unit_system;
  if (!app_message_int32(iterator, MESSAGE_KEY_RECORDING_STATE, &state) ||
      !app_message_uint32(iterator, MESSAGE_KEY_SAMPLE_EPOCH_SECONDS, &snapshot.sample_epoch) ||
      !app_message_uint32(iterator, MESSAGE_KEY_ELAPSED_SECONDS, &snapshot.elapsed) ||
      !app_message_int32(iterator, MESSAGE_KEY_MOVING_SECONDS, &snapshot.moving_time) ||
      !app_message_int32(iterator, MESSAGE_KEY_DISTANCE_METRES, &snapshot.distance) ||
      !app_message_int32(iterator, MESSAGE_KEY_MOVING_DISTANCE_METRES, &snapshot.moving_distance) ||
      !app_message_int32(iterator, MESSAGE_KEY_CURRENT_SPEED_CMPS, &snapshot.current_speed) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_SPEED_CMPS, &snapshot.average_speed) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_SPEED_CMPS, &snapshot.max_speed) ||
      !app_message_int32(iterator, MESSAGE_KEY_ALTITUDE_DECIMETRES, &snapshot.altitude) ||
      !app_message_int32(iterator, MESSAGE_KEY_ASCENT_DECIMETRES, &snapshot.ascent) ||
      !app_message_int32(iterator, MESSAGE_KEY_DESCENT_DECIMETRES, &snapshot.descent) ||
      !app_message_int32(iterator, MESSAGE_KEY_VERTICAL_SPEED_CMPS, &snapshot.vertical_speed) ||
      !app_message_int32(iterator, MESSAGE_KEY_SLOPE_TENTHS_PERCENT, &snapshot.slope) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_HEART_RATE, &snapshot.avg_hr) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_HEART_RATE, &snapshot.max_hr) ||
      !app_message_int32(iterator, MESSAGE_KEY_CURRENT_HEART_RATE, &snapshot.current_hr) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_CADENCE, &snapshot.avg_cadence) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_CADENCE, &snapshot.max_cadence) ||
      !app_message_int32(iterator, MESSAGE_KEY_AVERAGE_POWER, &snapshot.avg_power) ||
      !app_message_int32(iterator, MESSAGE_KEY_MAX_POWER, &snapshot.max_power) ||
      !app_message_int32(iterator, MESSAGE_KEY_ENERGY_KCAL, &snapshot.energy) ||
      !app_message_int32(iterator, MESSAGE_KEY_UNIT_SYSTEM, &unit_system)) {
    return false;
  }
  snapshot.state = state;
  if (unit_system != 0 || !snapshot_values_valid(&snapshot)) return false;
  *output = snapshot;
  return true;
}
