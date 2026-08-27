#ifndef WATCH_MAINTENANCE_H
#define WATCH_MAINTENANCE_H

#include <stdbool.h>
#include <stdint.h>

typedef enum {
  WATCH_MAINTENANCE_NOTICE = 0,
  WATCH_MAINTENANCE_CONFIG_TRANSFER,
  WATCH_MAINTENANCE_PROFILE_TRANSFER,
  WATCH_MAINTENANCE_COMMAND,
  WATCH_MAINTENANCE_STALE,
  WATCH_MAINTENANCE_NO_BRIDGE,
  WATCH_MAINTENANCE_PROFILE_PREPARATION,
  WATCH_MAINTENANCE_RECONCILIATION,
  WATCH_MAINTENANCE_KIND_COUNT,
} WatchMaintenanceKind;

typedef struct {
  uint32_t seconds;
  uint16_t milliseconds;
} WatchMaintenanceClock;

typedef struct {
  uint32_t active;
  uint32_t deadlines[WATCH_MAINTENANCE_KIND_COUNT];
} WatchMaintenanceDeadlines;

typedef struct {
  uint32_t due;
  bool has_next;
  uint32_t next_deadline;
  uint32_t delay_ms;
} WatchMaintenancePlan;

typedef struct {
  bool reset_projection_ui;
  bool request_runtime_config;
} WatchContextDecision;

#define WATCH_MAINTENANCE_BIT(kind) (1u << (kind))

bool watch_maintenance_reached(uint32_t now, uint32_t deadline);
bool watch_maintenance_should_schedule_reconciliation(bool context_active, bool has_profile_id,
                                                      bool request_pending);
WatchContextDecision watch_maintenance_context_decision(bool context_changed,
                                                        bool projection_ready);
WatchMaintenancePlan watch_maintenance_plan(const WatchMaintenanceDeadlines *deadlines,
                                            WatchMaintenanceClock now);

#endif
