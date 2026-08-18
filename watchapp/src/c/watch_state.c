#include "watch_state.h"

#include <string.h>

#include <pebble.h>

bool watch_snapshot_epoch_allowed(
    bool current_received,
    uint32_t current_epoch,
    uint32_t candidate_epoch) {
  return !current_received || candidate_epoch >= current_epoch;
}

bool watch_transfer_serial_is_newer(int32_t candidate, int32_t reference) {
  if (candidate < 0 || reference < 0) return false;
  const uint32_t distance =
      ((uint32_t)candidate - (uint32_t)reference) & WATCH_TRANSFER_SERIAL_MASK;
  return distance > 0 && distance < WATCH_TRANSFER_SERIAL_HALF_RANGE;
}

bool watch_transfer_serial_may_start(
    const WatchTransferSerialFloor *floor,
    int32_t candidate,
    bool allow_completed_retry) {
  if (!floor || candidate < 0) return false;
  if (!floor->valid) return true;
  if (candidate == floor->id) return !floor->completed || allow_completed_retry;
  return watch_transfer_serial_is_newer(candidate, floor->id);
}

void watch_transfer_serial_started(WatchTransferSerialFloor *floor, int32_t id) {
  if (!floor || id < 0) return;
  floor->valid = true;
  floor->completed = false;
  floor->id = id;
}

void watch_transfer_serial_completed(WatchTransferSerialFloor *floor, int32_t id) {
  if (!floor || !floor->valid || floor->id != id) return;
  floor->completed = true;
}

bool watch_transfer_serial_reserve_persistent(
    uint32_t key,
    int32_t seed,
    int32_t *reserved) {
  if (!reserved || seed < 0) return false;
  int32_t candidate = seed;
  if (persist_exists(key)) {
    int32_t previous = -1;
    if (persist_get_size(key) != (int)sizeof(previous) ||
        persist_read_data(key, &previous, sizeof(previous)) != (int)sizeof(previous) ||
        previous < 0) {
      return false;
    }
    candidate = (int32_t)(((uint32_t)previous + 1u) & WATCH_TRANSFER_SERIAL_MASK);
  }
  if (persist_write_data(key, &candidate, sizeof(candidate)) != (int)sizeof(candidate)) {
    return false;
  }
  int32_t confirmed = -1;
  if (persist_get_size(key) != (int)sizeof(confirmed) ||
      persist_read_data(key, &confirmed, sizeof(confirmed)) != (int)sizeof(confirmed) ||
      confirmed != candidate) {
    return false;
  }
  *reserved = candidate;
  return true;
}

void watch_profile_transfer_initialize(WatchProfileTransfer *transfer) {
  if (!transfer) return;
  memset(transfer, 0, sizeof(*transfer));
  transfer->id = -1;
}

void watch_profile_transfer_reset(WatchProfileTransfer *transfer) {
  if (!transfer) return;
  transfer->id = -1;
  transfer->chunk_count = 0;
  transfer->received_count = 0;
  transfer->result = 0;
  transfer->last_activity = 0;
  memset(transfer->part_lengths, 0, sizeof(transfer->part_lengths));
  memset(transfer->received, 0, sizeof(transfer->received));
}

bool watch_profile_transfer_join(
    const WatchProfileTransfer *transfer,
    char *buffer,
    size_t buffer_size,
    size_t maximum_length,
    size_t *joined_length) {
  if (!transfer || !buffer || !buffer_size || !joined_length ||
      transfer->chunk_count < 1 || transfer->chunk_count > WATCH_PROFILE_MAX_CHUNKS ||
      maximum_length >= buffer_size) {
    return false;
  }
  size_t length = 0;
  for (int i = 0; i < transfer->chunk_count; i++) {
    const size_t part_length = transfer->part_lengths[i];
    const size_t source = (size_t)i * WATCH_PROFILE_CHUNK_BYTES;
    if (!transfer->received[i] || source >= buffer_size ||
        part_length > buffer_size - source || part_length > maximum_length - length) {
      return false;
    }
    memmove(buffer + length, buffer + source, part_length);
    length += part_length;
  }
  buffer[length] = '\0';
  *joined_length = length;
  return true;
}

static bool chunk_matches(
    const WatchProfileTransfer *transfer,
    const char *buffer,
    int index,
    const char *data,
    size_t length) {
  const size_t offset = (size_t)index * WATCH_PROFILE_CHUNK_BYTES;
  return transfer->received[index] && transfer->part_lengths[index] == length &&
      memcmp(buffer + offset, data, length) == 0;
}

WatchProfileTransferOutcome watch_profile_transfer_accept(
    WatchProfileTransfer *transfer,
    char *buffer,
    size_t buffer_size,
    int32_t id,
    int index,
    int count,
    int result,
    const char *data,
    size_t length,
    uint32_t uptime_seconds) {
  if (!transfer) return WATCH_PROFILE_TRANSFER_INVALID;
  if (id < 0 || index < 0 || count < 1 || count > WATCH_PROFILE_MAX_CHUNKS ||
      index >= count || !data || length > WATCH_PROFILE_CHUNK_BYTES) {
    if (id == transfer->id) watch_profile_transfer_reset(transfer);
    return WATCH_PROFILE_TRANSFER_INVALID;
  }
  const size_t offset = (size_t)index * WATCH_PROFILE_CHUNK_BYTES;
  if (!buffer || buffer_size < 1 || offset >= buffer_size ||
      length > buffer_size - 1 - offset) {
    if (id == transfer->id) watch_profile_transfer_reset(transfer);
    return WATCH_PROFILE_TRANSFER_INVALID;
  }

  if (index == 0) {
    if (id == transfer->id) {
      if (count != transfer->chunk_count || result != transfer->result ||
          !chunk_matches(transfer, buffer, 0, data, length)) {
        watch_profile_transfer_reset(transfer);
        return WATCH_PROFILE_TRANSFER_INVALID;
      }
      transfer->last_activity = uptime_seconds;
      return WATCH_PROFILE_TRANSFER_DUPLICATE;
    }
    if (!watch_transfer_serial_may_start(&transfer->serial, id, false)) {
      return WATCH_PROFILE_TRANSFER_IGNORED;
    }
    watch_profile_transfer_reset(transfer);
    transfer->id = id;
    transfer->chunk_count = count;
    transfer->result = result;
    watch_transfer_serial_started(&transfer->serial, id);
  } else if (id != transfer->id) {
    return WATCH_PROFILE_TRANSFER_IGNORED;
  } else if (count != transfer->chunk_count || result != transfer->result) {
    watch_profile_transfer_reset(transfer);
    return WATCH_PROFILE_TRANSFER_INVALID;
  }

  transfer->last_activity = uptime_seconds;
  if (transfer->received[index]) {
    if (!chunk_matches(transfer, buffer, index, data, length)) {
      watch_profile_transfer_reset(transfer);
      return WATCH_PROFILE_TRANSFER_INVALID;
    }
    return WATCH_PROFILE_TRANSFER_DUPLICATE;
  }
  memcpy(buffer + offset, data, length);
  transfer->part_lengths[index] = (uint8_t)length;
  transfer->received[index] = true;
  transfer->received_count++;
  if (transfer->received_count == transfer->chunk_count) {
    watch_transfer_serial_completed(&transfer->serial, id);
    return WATCH_PROFILE_TRANSFER_COMPLETE;
  }
  return WATCH_PROFILE_TRANSFER_ACCEPTED;
}
