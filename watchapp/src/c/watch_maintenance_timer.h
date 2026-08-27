#ifndef WATCH_MAINTENANCE_TIMER_H
#define WATCH_MAINTENANCE_TIMER_H

#include <stdbool.h>
#include <stdint.h>

typedef void (*WatchMaintenanceTimerCallback)(void *context);

typedef struct {
  void *(*register_timer)(void *operations_context, uint32_t delay_ms,
                          WatchMaintenanceTimerCallback callback, void *callback_context);
  bool (*reschedule_timer)(void *operations_context, void *timer, uint32_t delay_ms);
  void (*cancel_timer)(void *operations_context, void *timer);
} WatchMaintenanceTimerOperations;

typedef struct {
  WatchMaintenanceTimerOperations operations;
  void *operations_context;
  void *timer;
  uint32_t deadline;
  bool deadline_valid;
  WatchMaintenanceTimerCallback callback;
  void *callback_context;
} WatchMaintenanceTimer;

void watch_maintenance_timer_initialize(WatchMaintenanceTimer *timer,
                                        WatchMaintenanceTimerOperations operations,
                                        void *operations_context,
                                        WatchMaintenanceTimerCallback callback,
                                        void *callback_context);
bool watch_maintenance_timer_schedule(WatchMaintenanceTimer *timer, bool has_deadline,
                                      uint32_t deadline, uint32_t delay_ms);
void watch_maintenance_timer_cancel(WatchMaintenanceTimer *timer);

#endif
