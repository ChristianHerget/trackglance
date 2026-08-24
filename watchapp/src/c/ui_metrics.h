#ifndef LOCUS_UI_METRICS_H
#define LOCUS_UI_METRICS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define UI_METRIC_UNAVAILABLE INT32_MIN

enum {
  METRIC_ELAPSED = 1,
  METRIC_MOVING_TIME = 2,
  METRIC_DISTANCE = 3,
  METRIC_MOVING_DISTANCE = 4,
  METRIC_CURRENT_SPEED = 5,
  METRIC_AVERAGE_SPEED = 6,
  METRIC_MAX_SPEED = 7,
  METRIC_CURRENT_PACE = 8,
  METRIC_AVERAGE_PACE = 9,
  METRIC_ALTITUDE = 10,
  METRIC_ASCENT = 11,
  METRIC_DESCENT = 12,
  METRIC_VERTICAL_SPEED = 13,
  METRIC_SLOPE = 14,
  METRIC_AVG_HR = 15,
  METRIC_MAX_HR = 16,
  METRIC_AVG_CADENCE = 17,
  METRIC_MAX_CADENCE = 18,
  METRIC_AVG_POWER = 19,
  METRIC_MAX_POWER = 20,
  METRIC_ENERGY = 21,
  METRIC_CURRENT_HR = 22,
};

typedef struct {
  int state;
  uint32_t sample_epoch;
  uint32_t elapsed;
  int32_t moving_time;
  int32_t distance;
  int32_t moving_distance;
  int32_t current_speed;
  int32_t average_speed;
  int32_t max_speed;
  int32_t altitude;
  int32_t ascent;
  int32_t descent;
  int32_t vertical_speed;
  int32_t slope;
  int32_t avg_hr;
  int32_t max_hr;
  int32_t current_hr;
  int32_t avg_cadence;
  int32_t max_cadence;
  int32_t avg_power;
  int32_t max_power;
  int32_t energy;
} UiMetricSnapshot;

const char *ui_metric_label(int metric, bool german);
void ui_metric_format(
    char *output,
    size_t size,
    int metric,
    const UiMetricSnapshot *snapshot,
    uint32_t elapsed);

#endif
