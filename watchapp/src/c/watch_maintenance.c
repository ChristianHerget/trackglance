#include "watch_maintenance.h"

#include <limits.h>

bool watch_maintenance_reached(uint32_t now, uint32_t deadline) {
  return (int32_t)(now - deadline) >= 0;
}

bool watch_maintenance_should_schedule_reconciliation(bool context_active, bool has_profile_id,
                                                      bool request_pending) {
  return context_active && has_profile_id && !request_pending;
}

WatchContextDecision watch_maintenance_context_decision(bool context_changed,
                                                        bool projection_ready) {
  return (WatchContextDecision){
      .reset_projection_ui = context_changed,
      .request_runtime_config = context_changed || !projection_ready,
  };
}

WatchMaintenancePlan watch_maintenance_plan(const WatchMaintenanceDeadlines *deadlines,
                                            WatchMaintenanceClock now) {
  WatchMaintenancePlan plan = {0};
  if (!deadlines) return plan;

  uint32_t nearest_distance = UINT32_MAX;
  for (int kind = 0; kind < WATCH_MAINTENANCE_KIND_COUNT; kind++) {
    const uint32_t bit = WATCH_MAINTENANCE_BIT(kind);
    if (!(deadlines->active & bit)) continue;
    const uint32_t deadline = deadlines->deadlines[kind];
    if (watch_maintenance_reached(now.seconds, deadline)) {
      plan.due |= bit;
      continue;
    }
    const uint32_t distance = deadline - now.seconds;
    if (!plan.has_next || distance < nearest_distance) {
      plan.has_next = true;
      plan.next_deadline = deadline;
      nearest_distance = distance;
    }
  }
  if (plan.has_next) {
    const uint64_t delay = (uint64_t)nearest_distance * 1000u - now.milliseconds;
    plan.delay_ms = delay > UINT32_MAX ? UINT32_MAX : (uint32_t)delay;
    if (!plan.delay_ms) plan.delay_ms = 1;
  }
  return plan;
}
