#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "watch_state.h"

#define WATCH_MAX_SLOTS 6
#define WATCH_MAX_PROFILES 4
#define WATCH_PROFILE_NAME_CODEPOINTS 20
#define WATCH_PROFILE_NAME_SIZE 81
#define WATCH_LOCUS_NAME_SIZE 256
#define WATCH_LOCUS_ID_SIZE 21
#define WATCH_PROFILE_ID_SIZE 40
#define WATCH_WAYPOINT_NAME_BYTES 120
#define WATCH_CONFIG_BUFFER_SIZE 4096
#define WATCH_CONFIG_CHUNK_BYTES 80
#define WATCH_CONFIG_MAX_CHUNKS 52

typedef struct {
  char name[WATCH_PROFILE_NAME_SIZE];
  char id[WATCH_PROFILE_ID_SIZE];
  bool protected_profile;
  uint8_t count;
  uint8_t metrics[WATCH_MAX_SLOTS];
} WatchProfile;

typedef struct {
  WatchProfile profiles[WATCH_MAX_PROFILES];
  int profile_count;
  int selected;
  bool dark;
  bool watch_hr_to_locus;
  uint8_t heart_rate_interval;
  char locus_id[WATCH_LOCUS_ID_SIZE];
  uint32_t fingerprint_a;
  uint32_t fingerprint_b;
} WatchConfig;

typedef struct {
  bool valid;
  int chunk_count;
  size_t length;
  size_t first_length;
  uint32_t checksum_a;
  uint32_t checksum_b;
  uint32_t first_checksum_a;
  uint32_t first_checksum_b;
} WatchConfigTransferIdentity;

typedef struct {
  int32_t id;
  int chunk_count;
  int next_chunk;
  size_t length;
  uint16_t offsets[WATCH_CONFIG_MAX_CHUNKS];
  uint8_t lengths[WATCH_CONFIG_MAX_CHUNKS];
  WatchTransferSerialFloor serial;
  WatchConfigTransferIdentity completed_identity;
  bool verifying_completed_retry;
} WatchConfigTransfer;

typedef enum {
  WATCH_TRANSFER_IGNORED,
  WATCH_TRANSFER_ACCEPTED,
  WATCH_TRANSFER_DUPLICATE,
  WATCH_TRANSFER_COMPLETE,
  WATCH_TRANSFER_INVALID,
} WatchTransferOutcome;

bool watch_config_parse(char *data, const char *active_id, WatchConfig *output);
bool watch_profile_list_valid(const char *data, size_t length);
bool watch_locus_profile_valid(const char *id, const char *name);
bool watch_waypoint_name_valid(const char *value);
bool watch_profile_names_equal(const char *left, const char *right);
void watch_config_transfer_initialize(WatchConfigTransfer *transfer);
void watch_config_transfer_reset(WatchConfigTransfer *transfer);
bool watch_config_transfer_may_start(const WatchConfigTransfer *transfer, int32_t id);
WatchTransferOutcome watch_config_transfer_accept(
    WatchConfigTransfer *transfer,
    char *buffer,
    size_t buffer_size,
    int32_t id,
    int index,
    int count,
    const char *data,
    size_t length);
