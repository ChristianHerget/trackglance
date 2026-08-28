#ifndef WATCH_STEP_STATE_H
#define WATCH_STEP_STATE_H

#include <stdbool.h>
#include <stdint.h>

typedef struct {
  uint32_t recording_start_low;
  uint32_t recording_start_high;
} WatchStepRecording;

typedef struct {
  WatchStepRecording recording;
  uint32_t sequence;
  int32_t delta;
} WatchStepPacket;

typedef enum {
  WATCH_STEP_AVAILABILITY_UNKNOWN = 0,
  WATCH_STEP_AVAILABILITY_UNAVAILABLE,
  WATCH_STEP_AVAILABILITY_AVAILABLE,
} WatchStepAvailability;

typedef struct {
  bool render;
  bool sample_now;
  bool discarded_prepared;
} WatchStepEffects;

typedef struct {
  bool source_enabled;
  bool projection_ready;
  bool recording_active;
  bool snapshot_fresh;
  WatchStepRecording recording;
  bool baseline_valid;
  int32_t baseline;
  WatchStepAvailability availability;
  bool sample_deadline_valid;
  uint32_t sample_deadline;
  bool pending;
  WatchStepRecording pending_recording;
  int32_t pending_delta;
  bool prepared;
  WatchStepPacket prepared_packet;
  uint32_t next_sequence;
  bool sequence_exhausted;
} WatchStepState;

void watch_step_state_initialize(WatchStepState *state);
WatchStepEffects watch_step_state_update(WatchStepState *state, bool source_enabled,
                                         bool projection_ready, bool recording_active,
                                         bool snapshot_fresh, WatchStepRecording recording,
                                         bool prepared_may_deliver);
WatchStepEffects watch_step_state_health_read(WatchStepState *state, bool accessible, int64_t total,
                                              uint32_t now, int32_t unavailable_value);
bool watch_step_state_sample_deadline(const WatchStepState *state, uint32_t *deadline);
bool watch_step_state_has_outbound(const WatchStepState *state);
const WatchStepPacket *watch_step_state_prepare(WatchStepState *state);
void watch_step_state_finish_prepared(WatchStepState *state);
bool watch_step_state_available(const WatchStepState *state);

#endif
