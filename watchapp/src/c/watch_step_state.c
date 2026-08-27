#include "watch_step_state.h"

#include <limits.h>
#include <stddef.h>

static bool recording_valid(WatchStepRecording recording) {
  return recording.recording_start_low || recording.recording_start_high;
}

static bool recording_equal(WatchStepRecording left, WatchStepRecording right) {
  return left.recording_start_low == right.recording_start_low &&
         left.recording_start_high == right.recording_start_high;
}

static bool sampling_wanted(const WatchStepState *state) {
  return state && state->source_enabled && state->projection_ready && state->recording_active &&
         state->snapshot_fresh && recording_valid(state->recording);
}

static void queue_delta(WatchStepState *state, int32_t delta, int32_t unavailable_value) {
  if (!state || !recording_valid(state->recording)) return;
  if (!state->pending || !recording_equal(state->pending_recording, state->recording)) {
    state->pending = false;
    state->pending_delta = 0;
  }
  if (delta == unavailable_value) {
    state->pending_delta = unavailable_value;
  } else if (!state->pending || state->pending_delta == unavailable_value) {
    state->pending_delta = delta;
  } else if (delta <= INT32_MAX - state->pending_delta) {
    state->pending_delta += delta;
  } else {
    state->pending_delta = INT32_MAX;
  }
  state->pending_recording = state->recording;
  state->pending = true;
}

static WatchStepEffects set_unavailable(WatchStepState *state, int32_t unavailable_value) {
  WatchStepEffects effects = {0};
  if (!state || !state->recording_active || !recording_valid(state->recording)) return effects;
  if (state->availability != WATCH_STEP_AVAILABILITY_UNAVAILABLE) {
    state->availability = WATCH_STEP_AVAILABILITY_UNAVAILABLE;
    queue_delta(state, unavailable_value, unavailable_value);
    effects.render = true;
  }
  return effects;
}

void watch_step_state_initialize(WatchStepState *state) {
  if (state) *state = (WatchStepState){0};
}

WatchStepEffects watch_step_state_update(WatchStepState *state, bool source_enabled,
                                         bool projection_ready, bool recording_active,
                                         bool snapshot_fresh, WatchStepRecording recording,
                                         bool prepared_may_deliver) {
  WatchStepEffects effects = {0};
  if (!state) return effects;
  const bool next_active = recording_active && recording_valid(recording);
  const bool identity_changed = state->recording_active != next_active ||
                                (next_active && !recording_equal(state->recording, recording));
  if (identity_changed) {
    state->pending = false;
    if (state->prepared && !recording_equal(state->prepared_packet.recording, recording) &&
        !prepared_may_deliver) {
      state->prepared = false;
      effects.discarded_prepared = true;
    }
    state->baseline_valid = false;
    state->availability = WATCH_STEP_AVAILABILITY_UNKNOWN;
    state->sample_deadline_valid = false;
  }
  state->source_enabled = source_enabled;
  state->projection_ready = projection_ready;
  state->recording_active = next_active;
  state->snapshot_fresh = snapshot_fresh;
  state->recording = next_active ? recording : (WatchStepRecording){0};

  if (!sampling_wanted(state)) {
    state->baseline_valid = false;
    state->sample_deadline_valid = false;
    const WatchStepEffects unavailable = set_unavailable(state, INT32_MIN);
    effects.render = effects.render || unavailable.render;
    return effects;
  }
  if (!state->sample_deadline_valid) effects.sample_now = true;
  return effects;
}

WatchStepEffects watch_step_state_health_read(WatchStepState *state, bool accessible, int64_t total,
                                              uint32_t now, int32_t unavailable_value) {
  WatchStepEffects effects = {0};
  if (!state || !sampling_wanted(state)) return effects;
  state->sample_deadline_valid = true;
  state->sample_deadline = now + 60;
  if (!accessible || total < 0 || total > INT32_MAX) {
    state->baseline_valid = false;
    return set_unavailable(state, unavailable_value);
  }

  const int32_t current = (int32_t)total;
  if (!state->baseline_valid) {
    state->baseline = current;
    state->baseline_valid = true;
    if (state->availability != WATCH_STEP_AVAILABILITY_AVAILABLE) {
      state->availability = WATCH_STEP_AVAILABILITY_AVAILABLE;
      queue_delta(state, 0, unavailable_value);
      effects.render = true;
    }
    return effects;
  }

  const int32_t delta = current >= state->baseline ? current - state->baseline : current;
  state->baseline = current;
  if (state->availability != WATCH_STEP_AVAILABILITY_AVAILABLE) {
    state->availability = WATCH_STEP_AVAILABILITY_AVAILABLE;
    queue_delta(state, 0, unavailable_value);
    effects.render = true;
  }
  if (delta > 0) queue_delta(state, delta, unavailable_value);
  return effects;
}

bool watch_step_state_sample_deadline(const WatchStepState *state, uint32_t *deadline) {
  if (!state || !deadline || !sampling_wanted(state) || !state->sample_deadline_valid) return false;
  *deadline = state->sample_deadline;
  return true;
}

bool watch_step_state_has_outbound(const WatchStepState *state) {
  return state && (state->pending || state->prepared);
}

const WatchStepPacket *watch_step_state_prepare(WatchStepState *state) {
  if (!state) return NULL;
  if (state->prepared) return &state->prepared_packet;
  if (!state->pending || state->sequence_exhausted) return NULL;
  state->prepared_packet = (WatchStepPacket){
      .recording = state->pending_recording,
      .sequence = state->next_sequence,
      .delta = state->pending_delta,
  };
  state->pending = false;
  state->prepared = true;
  if (state->next_sequence == UINT32_MAX) {
    state->sequence_exhausted = true;
  } else {
    state->next_sequence++;
  }
  return &state->prepared_packet;
}

void watch_step_state_finish_prepared(WatchStepState *state) {
  if (state) state->prepared = false;
}

bool watch_step_state_available(const WatchStepState *state) {
  return state && state->availability == WATCH_STEP_AVAILABILITY_AVAILABLE;
}
