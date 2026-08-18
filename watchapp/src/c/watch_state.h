#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define WATCH_PROFILE_CHUNK_BYTES 80
#define WATCH_PROFILE_MAX_CHUNKS 103
#define WATCH_PROFILE_TRANSFER_BUFFER_SIZE (WATCH_PROFILE_MAX_CHUNKS * WATCH_PROFILE_CHUNK_BYTES + 1)
#define WATCH_TRANSFER_SERIAL_MASK 0x7fffffffu
#define WATCH_TRANSFER_SERIAL_HALF_RANGE 0x40000000u

typedef struct {
  bool valid;
  bool completed;
  int32_t id;
} WatchTransferSerialFloor;

typedef struct {
  int32_t id;
  int chunk_count;
  int received_count;
  int result;
  uint32_t last_activity;
  uint8_t part_lengths[WATCH_PROFILE_MAX_CHUNKS];
  bool received[WATCH_PROFILE_MAX_CHUNKS];
  WatchTransferSerialFloor serial;
} WatchProfileTransfer;

typedef enum {
  WATCH_PROFILE_TRANSFER_IGNORED,
  WATCH_PROFILE_TRANSFER_ACCEPTED,
  WATCH_PROFILE_TRANSFER_DUPLICATE,
  WATCH_PROFILE_TRANSFER_COMPLETE,
  WATCH_PROFILE_TRANSFER_INVALID,
} WatchProfileTransferOutcome;

bool watch_snapshot_epoch_allowed(
    bool current_received,
    uint32_t current_epoch,
    uint32_t candidate_epoch);
bool watch_transfer_serial_is_newer(int32_t candidate, int32_t reference);
bool watch_transfer_serial_may_start(
    const WatchTransferSerialFloor *floor,
    int32_t candidate,
    bool allow_completed_retry);
void watch_transfer_serial_started(WatchTransferSerialFloor *floor, int32_t id);
void watch_transfer_serial_completed(WatchTransferSerialFloor *floor, int32_t id);
bool watch_transfer_serial_reserve_persistent(
    uint32_t key,
    int32_t seed,
    int32_t *reserved);
void watch_profile_transfer_initialize(WatchProfileTransfer *transfer);
void watch_profile_transfer_reset(WatchProfileTransfer *transfer);
bool watch_profile_transfer_join(
    const WatchProfileTransfer *transfer,
    char *buffer,
    size_t buffer_size,
    size_t maximum_length,
    size_t *joined_length);
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
    uint32_t uptime_seconds);
