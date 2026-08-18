#include "watch_config.h"

#include <limits.h>
#include <stdio.h>
#include <string.h>

static char *next_token(char **cursor, char delimiter) {
  if (!cursor || !*cursor) return NULL;
  char *value = *cursor;
  char *end = strchr(value, delimiter);
  if (end) {
    *end = '\0';
    *cursor = end + 1;
  } else {
    *cursor = NULL;
  }
  return value;
}

static bool parse_uint(const char *text, uint32_t *output) {
  if (!text || !*text || !output) return false;
  uint32_t value = 0;
  for (const unsigned char *p = (const unsigned char *)text; *p; p++) {
    if (*p < '0' || *p > '9') return false;
    const uint32_t digit = (uint32_t)(*p - '0');
    if (value > (UINT32_MAX - digit) / 10) return false;
    value = value * 10 + digit;
  }
  *output = value;
  return true;
}

static bool utf8_code_point(
    const unsigned char *data,
    size_t length,
    size_t *width,
    uint32_t *code_point) {
  if (!data || !length || !width || !code_point) return false;
  const unsigned char a = data[0];
  if (a <= 0x7f) {
    *width = 1;
    *code_point = a;
    return true;
  }
  if (a >= 0xc2 && a <= 0xdf && length >= 2 && (data[1] & 0xc0) == 0x80) {
    *width = 2;
    *code_point = ((uint32_t)(a & 0x1f) << 6) | (data[1] & 0x3f);
    return true;
  }
  if (a >= 0xe0 && a <= 0xef && length >= 3 &&
      (data[1] & 0xc0) == 0x80 && (data[2] & 0xc0) == 0x80 &&
      !(a == 0xe0 && data[1] < 0xa0) && !(a == 0xed && data[1] >= 0xa0)) {
    *width = 3;
    *code_point = ((uint32_t)(a & 0x0f) << 12) |
        ((uint32_t)(data[1] & 0x3f) << 6) | (data[2] & 0x3f);
    return true;
  }
  if (a >= 0xf0 && a <= 0xf4 && length >= 4 &&
      (data[1] & 0xc0) == 0x80 && (data[2] & 0xc0) == 0x80 &&
      (data[3] & 0xc0) == 0x80 &&
      !(a == 0xf0 && data[1] < 0x90) && !(a == 0xf4 && data[1] >= 0x90)) {
    *width = 4;
    *code_point = ((uint32_t)(a & 0x07) << 18) |
        ((uint32_t)(data[1] & 0x3f) << 12) |
        ((uint32_t)(data[2] & 0x3f) << 6) | (data[3] & 0x3f);
    return true;
  }
  return false;
}

static bool unicode_space(uint32_t value) {
  return value == 0x20 || value == 0x85 || value == 0xa0 || value == 0x1680 ||
      (value >= 0x2000 && value <= 0x200a) || value == 0x2028 || value == 0x2029 ||
      value == 0x202f || value == 0x205f || value == 0x3000 || value == 0xfeff;
}

static bool field_valid(
    const char *value,
    size_t length,
    size_t max_bytes,
    size_t max_code_points,
    bool reject_pipe) {
  if (!value || !length || length > max_bytes) return false;
  size_t offset = 0;
  size_t count = 0;
  bool non_space = false;
  while (offset < length) {
    size_t width = 0;
    uint32_t code_point = 0;
    if (!utf8_code_point((const unsigned char *)value + offset, length - offset, &width, &code_point) ||
        code_point < 0x20 || code_point == 0x7f || (reject_pipe && code_point == '|') ||
        code_point == '\n' || code_point == '\r') {
      return false;
    }
    count++;
    if (max_code_points && count > max_code_points) return false;
    if (!unicode_space(code_point)) non_space = true;
    offset += width;
  }
  return non_space;
}

static bool profile_name_valid(const char *value) {
  return value && field_valid(
      value,
      strlen(value),
      WATCH_PROFILE_NAME_SIZE - 1,
      WATCH_PROFILE_NAME_CODEPOINTS,
      true);
}

static bool locus_name_valid(const char *value) {
  return value && field_valid(value, strlen(value), WATCH_LOCUS_NAME_SIZE - 1, 0, true);
}

static bool id_valid(const char *value) {
  return value && field_valid(value, strlen(value), WATCH_PROFILE_ID_SIZE - 1, 0, true);
}

static uint32_t simple_case_fold(uint32_t value) {
  if (value >= 'A' && value <= 'Z') return value + ('a' - 'A');
  if ((value >= 0xc0 && value <= 0xd6) || (value >= 0xd8 && value <= 0xde)) {
    return value + 0x20;
  }
  if ((value >= 0x391 && value <= 0x3a1) || (value >= 0x3a3 && value <= 0x3ab)) {
    return value + 0x20;
  }
  if (value >= 0x410 && value <= 0x42f) return value + 0x20;
  if (value >= 0x400 && value <= 0x40f) return value + 0x50;
  return value;
}

bool watch_profile_names_equal(const char *left, const char *right) {
  if (!left || !right) return false;
  while (*left && *right) {
    const size_t left_length = strlen(left);
    const size_t right_length = strlen(right);
    size_t left_width = 0;
    size_t right_width = 0;
    uint32_t left_code_point = 0;
    uint32_t right_code_point = 0;
    if (!utf8_code_point(
            (const unsigned char *)left,
            left_length,
            &left_width,
            &left_code_point) ||
        !utf8_code_point(
            (const unsigned char *)right,
            right_length,
            &right_width,
            &right_code_point) ||
        simple_case_fold(left_code_point) != simple_case_fold(right_code_point)) {
      return false;
    }
    left += left_width;
    right += right_width;
  }
  return *left == *right;
}

static bool copy_field(char *destination, size_t size, const char *source) {
  if (!destination || !size || !source) return false;
  const size_t length = strlen(source);
  if (length >= size) return false;
  memcpy(destination, source, length + 1);
  return true;
}

static uint32_t stable_id_hash(const char *value) {
  uint32_t hash = 2166136261u;
  for (const unsigned char *p = (const unsigned char *)value; *p; p++) {
    hash ^= *p;
    hash *= 16777619u;
  }
  return hash;
}

static bool parse_metrics(char *value, WatchProfile *profile) {
  if (!value || !profile) return false;
  char *cursor = value;
  char *metric = NULL;
  while ((metric = next_token(&cursor, ','))) {
    if (profile->count >= WATCH_MAX_SLOTS) return false;
    uint32_t id = 0;
    if (!parse_uint(metric, &id) || id < 1 || id > 22) return false;
    for (uint8_t i = 0; i < profile->count; i++) {
      if (profile->metrics[i] == id) return false;
    }
    profile->metrics[profile->count++] = (uint8_t)id;
  }
  return profile->count > 0;
}

static bool parse_profile(char *line, int index, WatchConfig *output) {
  if (!line || !output || index < 0 || index >= WATCH_MAX_PROFILES) return false;
  char *cursor = line;
  char *name = next_token(&cursor, '|');
  char *locus = next_token(&cursor, '|');
  char *protected_text = next_token(&cursor, '|');
  char *metrics = next_token(&cursor, '|');
  char *id = next_token(&cursor, '|');
  if (!name || !locus || !protected_text || !metrics || cursor ||
      !profile_name_valid(name) || !locus_name_valid(locus)) {
    return false;
  }
  uint32_t protected_value = 0;
  if (!parse_uint(protected_text, &protected_value) || protected_value > 1) return false;

  WatchProfile *profile = &output->profiles[index];
  if (!copy_field(profile->name, sizeof(profile->name), name) ||
      !copy_field(profile->locus, sizeof(profile->locus), locus)) {
    return false;
  }
  profile->protected_profile = false;
  if (!parse_metrics(metrics, profile)) return false;

  if (id && *id) {
    if (!id_valid(id)) return false;
    if (!copy_field(profile->id, sizeof(profile->id), id)) return false;
  } else {
    const int written = snprintf(
        profile->id,
        sizeof(profile->id),
        "legacy-%d-%08lx",
        index,
        (unsigned long)stable_id_hash(name));
    if (written < 0 || written >= (int)sizeof(profile->id)) return false;
  }

  for (int i = 0; i < index; i++) {
    if (watch_profile_names_equal(output->profiles[i].name, profile->name) ||
        strcmp(output->profiles[i].id, profile->id) == 0) {
      return false;
    }
  }
  return true;
}

bool watch_config_parse(char *data, const char *active_id, WatchConfig *output) {
  if (!data || !output) return false;
  memset(output, 0, sizeof(*output));

  char *line_cursor = data;
  char *header = next_token(&line_cursor, '\n');
  if (!header) return false;
  char *header_cursor = header;
  char *theme = next_token(&header_cursor, '|');
  char *selected_text = next_token(&header_cursor, '|');
  char *watch_hr_text = next_token(&header_cursor, '|');
  char *interval_text = next_token(&header_cursor, '|');
  if (!theme || !selected_text || header_cursor ||
      (strcmp(theme, "dark") != 0 && strcmp(theme, "light") != 0)) {
    return false;
  }

  uint32_t selected = 0;
  if (!parse_uint(selected_text, &selected) || selected >= WATCH_MAX_PROFILES) return false;
  output->dark = strcmp(theme, "dark") == 0;
  output->heart_rate_interval = 5;
  if (watch_hr_text) {
    uint32_t watch_hr = 0;
    if (!parse_uint(watch_hr_text, &watch_hr) || watch_hr > 1) return false;
    output->watch_hr_to_locus = watch_hr == 1;
  }
  if (interval_text) {
    uint32_t interval = 0;
    if (!parse_uint(interval_text, &interval) || interval < 1 || interval > 60) return false;
    output->heart_rate_interval = (uint8_t)interval;
  }

  char *line = NULL;
  while ((line = next_token(&line_cursor, '\n'))) {
    if (output->profile_count >= WATCH_MAX_PROFILES ||
        !parse_profile(line, output->profile_count, output)) {
      return false;
    }
    output->profile_count++;
  }
  if (output->profile_count < 1 || selected >= (uint32_t)output->profile_count) return false;

  int active_index = -1;
  if (active_id && *active_id) {
    for (int i = 0; i < output->profile_count; i++) {
      if (strcmp(active_id, output->profiles[i].id) == 0) active_index = i;
    }
  }
  output->selected = active_index >= 0 ? active_index : ((active_id && *active_id) ? 0 : (int)selected);
  return true;
}

bool watch_profile_list_valid(const char *data, size_t length) {
  if (!data) return false;
  if (!length) return true;
  size_t start = 0;
  while (start < length) {
    size_t end = start;
    while (end < length && data[end] != '\n') end++;
    if (!field_valid(data + start, end - start, WATCH_LOCUS_NAME_SIZE - 1, 0, true)) return false;
    size_t previous = 0;
    while (previous < start) {
      size_t previous_end = previous;
      while (previous_end < start && data[previous_end] != '\n') previous_end++;
      if (previous_end - previous == end - start &&
          memcmp(data + previous, data + start, end - start) == 0) {
        return false;
      }
      previous = previous_end + 1;
    }
    start = end + 1;
  }
  return data[length - 1] != '\n';
}

bool watch_waypoint_name_valid(const char *value) {
  return value && field_valid(
      value,
      strlen(value),
      WATCH_WAYPOINT_NAME_BYTES,
      0,
      false);
}

void watch_config_transfer_initialize(WatchConfigTransfer *transfer) {
  if (!transfer) return;
  memset(transfer, 0, sizeof(*transfer));
  transfer->id = -1;
}

void watch_config_transfer_reset(WatchConfigTransfer *transfer) {
  if (!transfer) return;
  const WatchTransferSerialFloor serial = transfer->serial;
  const WatchConfigTransferIdentity completed_identity = transfer->completed_identity;
  memset(transfer, 0, sizeof(*transfer));
  transfer->id = -1;
  transfer->serial = serial;
  transfer->completed_identity = completed_identity;
}

bool watch_config_transfer_may_start(const WatchConfigTransfer *transfer, int32_t id) {
  return transfer && watch_transfer_serial_may_start(&transfer->serial, id, true);
}

static void transfer_checksums(
    const char *data,
    size_t length,
    uint32_t *checksum_a,
    uint32_t *checksum_b) {
  uint32_t fnv = 2166136261u;
  uint32_t crc = UINT32_MAX;
  for (size_t i = 0; i < length; i++) {
    const uint8_t byte = (uint8_t)data[i];
    fnv ^= byte;
    fnv *= 16777619u;
    crc ^= byte;
    for (int bit = 0; bit < 8; bit++) {
      crc = (crc >> 1) ^ (0xedb88320u & (uint32_t)-(int32_t)(crc & 1u));
    }
  }
  *checksum_a = fnv;
  *checksum_b = ~crc;
}

static bool completed_first_chunk_matches(
    const WatchConfigTransferIdentity *identity,
    int count,
    const char *data,
    size_t length) {
  if (!identity || !identity->valid || identity->chunk_count != count ||
      identity->first_length != length) {
    return false;
  }
  uint32_t checksum_a = 0;
  uint32_t checksum_b = 0;
  transfer_checksums(data, length, &checksum_a, &checksum_b);
  return checksum_a == identity->first_checksum_a &&
      checksum_b == identity->first_checksum_b;
}

static bool completed_payload_matches(
    const WatchConfigTransferIdentity *identity,
    const char *data,
    size_t length) {
  if (!identity || !identity->valid || identity->length != length) return false;
  uint32_t checksum_a = 0;
  uint32_t checksum_b = 0;
  transfer_checksums(data, length, &checksum_a, &checksum_b);
  return checksum_a == identity->checksum_a && checksum_b == identity->checksum_b;
}

static void remember_completed_payload(WatchConfigTransfer *transfer, const char *buffer) {
  WatchConfigTransferIdentity *identity = &transfer->completed_identity;
  memset(identity, 0, sizeof(*identity));
  identity->valid = true;
  identity->chunk_count = transfer->chunk_count;
  identity->length = transfer->length;
  identity->first_length = transfer->lengths[0];
  transfer_checksums(buffer, transfer->length, &identity->checksum_a, &identity->checksum_b);
  transfer_checksums(
      buffer + transfer->offsets[0],
      transfer->lengths[0],
      &identity->first_checksum_a,
      &identity->first_checksum_b);
}

WatchTransferOutcome watch_config_transfer_accept(
    WatchConfigTransfer *transfer,
    char *buffer,
    size_t buffer_size,
    int32_t id,
    int index,
    int count,
    const char *data,
    size_t length) {
  if (!transfer || !buffer || buffer_size < 1 || id < 0 || count < 1 ||
      count > WATCH_CONFIG_MAX_CHUNKS || index < 0 || index >= count || !data ||
      length > WATCH_CONFIG_CHUNK_BYTES) {
    if (transfer && transfer->id >= 0 && id >= 0 && id != transfer->id) {
      return WATCH_TRANSFER_IGNORED;
    }
    if (transfer && id >= 0 && id == transfer->id) watch_config_transfer_reset(transfer);
    return WATCH_TRANSFER_INVALID;
  }

  if (index == 0) {
    if (transfer->id == id && transfer->next_chunk > 0) {
      if (count != transfer->chunk_count) {
        watch_config_transfer_reset(transfer);
        return WATCH_TRANSFER_INVALID;
      }
      const size_t offset = transfer->offsets[0];
      if (transfer->lengths[0] == length && offset <= transfer->length &&
          length <= transfer->length - offset && memcmp(buffer + offset, data, length) == 0) {
        return WATCH_TRANSFER_DUPLICATE;
      }
      watch_config_transfer_reset(transfer);
      return WATCH_TRANSFER_INVALID;
    }
    if (!watch_config_transfer_may_start(transfer, id)) return WATCH_TRANSFER_IGNORED;
    const bool completed_retry =
        transfer->serial.valid && transfer->serial.completed && transfer->serial.id == id;
    if (completed_retry && !completed_first_chunk_matches(
            &transfer->completed_identity, count, data, length)) {
      return WATCH_TRANSFER_INVALID;
    }
    watch_config_transfer_reset(transfer);
    transfer->id = id;
    transfer->chunk_count = count;
    transfer->verifying_completed_retry = completed_retry;
    if (!completed_retry) {
      watch_transfer_serial_started(&transfer->serial, id);
      transfer->completed_identity.valid = false;
    }
    buffer[0] = '\0';
  } else if (id != transfer->id) {
    return WATCH_TRANSFER_IGNORED;
  } else if (count != transfer->chunk_count) {
    watch_config_transfer_reset(transfer);
    return WATCH_TRANSFER_INVALID;
  }

  if (index < transfer->next_chunk) {
    const size_t offset = transfer->offsets[index];
    if (transfer->lengths[index] == length && offset <= transfer->length &&
        length <= transfer->length - offset && memcmp(buffer + offset, data, length) == 0) {
      return WATCH_TRANSFER_DUPLICATE;
    }
    watch_config_transfer_reset(transfer);
    return WATCH_TRANSFER_INVALID;
  }
  if (index != transfer->next_chunk || transfer->length + length >= buffer_size) {
    watch_config_transfer_reset(transfer);
    return WATCH_TRANSFER_INVALID;
  }

  transfer->offsets[index] = (uint16_t)transfer->length;
  transfer->lengths[index] = (uint8_t)length;
  memcpy(buffer + transfer->length, data, length);
  transfer->length += length;
  buffer[transfer->length] = '\0';
  transfer->next_chunk++;
  if (transfer->next_chunk == transfer->chunk_count) {
    if (transfer->verifying_completed_retry) {
      if (!completed_payload_matches(
              &transfer->completed_identity, buffer, transfer->length)) {
        watch_config_transfer_reset(transfer);
        return WATCH_TRANSFER_INVALID;
      }
    } else {
      remember_completed_payload(transfer, buffer);
      watch_transfer_serial_completed(&transfer->serial, id);
    }
    return WATCH_TRANSFER_COMPLETE;
  }
  return WATCH_TRANSFER_ACCEPTED;
}
