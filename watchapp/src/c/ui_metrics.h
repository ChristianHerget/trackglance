#ifndef LOCUS_UI_METRICS_H
#define LOCUS_UI_METRICS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "i18n.h"

#define UI_METRIC_UNAVAILABLE INT32_MIN

enum {
  METRIC_ELAPSED = 1, METRIC_MOVING_TIME = 2, METRIC_DISTANCE = 3,
  METRIC_MOVING_DISTANCE = 4, METRIC_CURRENT_SPEED = 5, METRIC_AVERAGE_SPEED = 6,
  METRIC_MAX_SPEED = 7, METRIC_CURRENT_PACE = 8, METRIC_AVERAGE_PACE = 9,
  METRIC_ALTITUDE = 10, METRIC_ASCENT = 11, METRIC_DESCENT = 12,
  METRIC_VERTICAL_SPEED = 13, METRIC_SLOPE = 14, METRIC_AVG_HR = 15,
  METRIC_MAX_HR = 16, METRIC_AVG_CADENCE = 17, METRIC_MAX_CADENCE = 18,
  METRIC_AVG_POWER = 19, METRIC_MAX_POWER = 20, METRIC_ENERGY = 21,
  METRIC_CURRENT_HR = 22,
};

typedef enum {
  FORMAT_M_0 = 0, FORMAT_KM_1 = 1, FORMAT_KM_0 = 2, FORMAT_FT_0 = 3,
  FORMAT_YD_0 = 4, FORMAT_MI_2 = 5, FORMAT_MI_1 = 6, FORMAT_MI_0 = 7,
  FORMAT_NMI_1 = 8, FORMAT_NMI_0 = 9, FORMAT_KPH_1 = 10, FORMAT_KPH_0 = 11,
  FORMAT_MPH_1 = 12, FORMAT_MPH_0 = 13, FORMAT_NMIH_1 = 14, FORMAT_NMIH_0 = 15,
  FORMAT_KNOT_1 = 16, FORMAT_KNOT_0 = 17, FORMAT_MPS_2 = 18, FORMAT_FPS_2 = 19,
  FORMAT_PERCENT_0 = 20, FORMAT_DEGREE_0 = 21, FORMAT_KJ_0 = 22,
  FORMAT_KCAL_0 = 23, FORMAT_PER_KM = 24, FORMAT_PER_MI = 25,
  FORMAT_PER_NMI = 26, FORMAT_COUNT = 27,
} UiFormatCode;

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
  int32_t current_pace;
  int32_t average_pace;
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
  int32_t altitude_format;
  int32_t distance_format;
  int32_t moving_distance_format;
  int32_t current_speed_format;
  int32_t average_speed_format;
  int32_t max_speed_format;
  int32_t vertical_speed_format;
  int32_t slope_format;
  int32_t energy_format;
  int32_t pace_format;
} UiMetricSnapshot;

bool ui_format_code_valid(int32_t format);
bool ui_metric_snapshot_valid(const UiMetricSnapshot *snapshot);
const char *ui_metric_label(int metric);
void ui_metric_format(char *output, size_t size, int metric, const UiMetricSnapshot *snapshot);

#endif
