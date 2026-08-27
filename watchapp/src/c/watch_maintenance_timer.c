#include "watch_maintenance_timer.h"

#include <stddef.h>

static void timer_fired(void *context) {
  WatchMaintenanceTimer *timer = context;
  if (!timer) return;
  timer->timer = NULL;
  timer->deadline_valid = false;
  if (timer->callback) timer->callback(timer->callback_context);
}

void watch_maintenance_timer_initialize(WatchMaintenanceTimer *timer,
                                        WatchMaintenanceTimerOperations operations,
                                        void *operations_context,
                                        WatchMaintenanceTimerCallback callback,
                                        void *callback_context) {
  if (!timer) return;
  *timer = (WatchMaintenanceTimer){
      .operations = operations,
      .operations_context = operations_context,
      .callback = callback,
      .callback_context = callback_context,
  };
}

void watch_maintenance_timer_cancel(WatchMaintenanceTimer *timer) {
  if (!timer) return;
  if (timer->timer && timer->operations.cancel_timer) {
    timer->operations.cancel_timer(timer->operations_context, timer->timer);
  }
  timer->timer = NULL;
  timer->deadline_valid = false;
}

bool watch_maintenance_timer_schedule(WatchMaintenanceTimer *timer, bool has_deadline,
                                      uint32_t deadline, uint32_t delay_ms) {
  if (!timer) return false;
  if (!has_deadline) {
    watch_maintenance_timer_cancel(timer);
    return true;
  }
  if (!delay_ms) delay_ms = 1;
  if (timer->timer && timer->deadline_valid && timer->deadline == deadline) return true;
  if (timer->timer && timer->operations.reschedule_timer &&
      timer->operations.reschedule_timer(timer->operations_context, timer->timer, delay_ms)) {
    timer->deadline = deadline;
    timer->deadline_valid = true;
    return true;
  }
  watch_maintenance_timer_cancel(timer);
  if (!timer->operations.register_timer) return false;
  timer->timer =
      timer->operations.register_timer(timer->operations_context, delay_ms, timer_fired, timer);
  if (!timer->timer) return false;
  timer->deadline = deadline;
  timer->deadline_valid = true;
  return true;
}
