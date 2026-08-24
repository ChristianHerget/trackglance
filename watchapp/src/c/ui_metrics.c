#include "ui_metrics.h"

#include <stdio.h>

typedef struct { uint8_t decimals; const char *suffix; } FormatDescriptor;

static const FormatDescriptor s_formats[FORMAT_COUNT] = {
  {0, " m"}, {1, " km"}, {0, " km"}, {0, " ft"}, {0, " yd"},
  {2, " mi"}, {1, " mi"}, {0, " mi"}, {1, " nmi"}, {0, " nmi"},
  {1, " km/h"}, {0, " km/h"}, {1, " mi/h"}, {0, " mi/h"},
  {1, " nmi/h"}, {0, " nmi/h"}, {1, " kn"}, {0, " kn"},
  {2, " m/s"}, {2, " ft/s"}, {0, "%"}, {0, "°"}, {0, " kJ"},
  {0, " kcal"}, {0, " /km"}, {0, " /mi"}, {0, " /nmi"},
};

bool ui_format_code_valid(int32_t format) {
  return format >= 0 && format < FORMAT_COUNT && s_formats[format].suffix;
}

static bool distance_format(int32_t format) { return format >= FORMAT_M_0 && format <= FORMAT_NMI_0; }
static bool speed_format(int32_t format) { return format >= FORMAT_KPH_1 && format <= FORMAT_KNOT_0; }

static bool nonnegative(int32_t value) {
  return value == UI_METRIC_UNAVAILABLE || value >= 0;
}

bool ui_metric_snapshot_valid(const UiMetricSnapshot *s) {
  if (!s || s->state < 0 || s->state > 3 ||
      !nonnegative(s->moving_time) || !nonnegative(s->distance) ||
      !nonnegative(s->moving_distance) || !nonnegative(s->current_speed) ||
      !nonnegative(s->average_speed) || !nonnegative(s->max_speed) ||
      !nonnegative(s->current_pace) || !nonnegative(s->average_pace) ||
      !nonnegative(s->ascent) || !nonnegative(s->descent) || !nonnegative(s->avg_hr) ||
      !nonnegative(s->max_hr) || !nonnegative(s->current_hr) || !nonnegative(s->avg_cadence) ||
      !nonnegative(s->max_cadence) || !nonnegative(s->avg_power) ||
      !nonnegative(s->max_power) || !nonnegative(s->energy)) return false;
  const int32_t formats[] = {
    s->altitude_format, s->distance_format, s->moving_distance_format,
    s->current_speed_format, s->average_speed_format, s->max_speed_format,
    s->vertical_speed_format, s->slope_format, s->energy_format, s->pace_format,
  };
  for (size_t i = 0; i < sizeof(formats) / sizeof(formats[0]); i++) {
    if (!ui_format_code_valid(formats[i])) return false;
  }
  return (s->altitude_format == FORMAT_M_0 || s->altitude_format == FORMAT_FT_0) &&
      distance_format(s->distance_format) && distance_format(s->moving_distance_format) &&
      speed_format(s->current_speed_format) && speed_format(s->average_speed_format) &&
      speed_format(s->max_speed_format) &&
      (s->vertical_speed_format == FORMAT_MPS_2 || s->vertical_speed_format == FORMAT_FPS_2) &&
      (s->slope_format == FORMAT_PERCENT_0 || s->slope_format == FORMAT_DEGREE_0) &&
      (s->energy_format == FORMAT_KJ_0 || s->energy_format == FORMAT_KCAL_0) &&
      (s->pace_format == FORMAT_PER_KM || s->pace_format == FORMAT_PER_MI ||
       s->pace_format == FORMAT_PER_NMI);
}

const char *ui_metric_label(int metric) {
  if (metric < METRIC_ELAPSED || metric > METRIC_CURRENT_HR) return "";
  return i18n_text((I18nString)(I18N_METRIC_ELAPSED + metric - METRIC_ELAPSED));
}

static int32_t metric_value(int metric, const UiMetricSnapshot *s) {
  if (!s) return UI_METRIC_UNAVAILABLE;
  switch (metric) {
    case METRIC_MOVING_TIME: return s->moving_time;
    case METRIC_DISTANCE: return s->distance;
    case METRIC_MOVING_DISTANCE: return s->moving_distance;
    case METRIC_CURRENT_SPEED: return s->current_speed;
    case METRIC_AVERAGE_SPEED: return s->average_speed;
    case METRIC_MAX_SPEED: return s->max_speed;
    case METRIC_CURRENT_PACE: return s->current_pace;
    case METRIC_AVERAGE_PACE: return s->average_pace;
    case METRIC_ALTITUDE: return s->altitude;
    case METRIC_ASCENT: return s->ascent;
    case METRIC_DESCENT: return s->descent;
    case METRIC_VERTICAL_SPEED: return s->vertical_speed;
    case METRIC_SLOPE: return s->slope;
    case METRIC_AVG_HR: return s->avg_hr;
    case METRIC_MAX_HR: return s->max_hr;
    case METRIC_CURRENT_HR: return s->current_hr;
    case METRIC_AVG_CADENCE: return s->avg_cadence;
    case METRIC_MAX_CADENCE: return s->max_cadence;
    case METRIC_AVG_POWER: return s->avg_power;
    case METRIC_MAX_POWER: return s->max_power;
    case METRIC_ENERGY: return s->energy;
    default: return UI_METRIC_UNAVAILABLE;
  }
}

static int32_t metric_format(int metric, const UiMetricSnapshot *s) {
  switch (metric) {
    case METRIC_DISTANCE: return s->distance_format;
    case METRIC_MOVING_DISTANCE: return s->moving_distance_format;
    case METRIC_CURRENT_SPEED: return s->current_speed_format;
    case METRIC_AVERAGE_SPEED: return s->average_speed_format;
    case METRIC_MAX_SPEED: return s->max_speed_format;
    case METRIC_CURRENT_PACE:
    case METRIC_AVERAGE_PACE: return s->pace_format;
    case METRIC_ALTITUDE:
    case METRIC_ASCENT:
    case METRIC_DESCENT: return s->altitude_format;
    case METRIC_VERTICAL_SPEED: return s->vertical_speed_format;
    case METRIC_SLOPE: return s->slope_format;
    case METRIC_ENERGY: return s->energy_format;
    default: return -1;
  }
}

static uint32_t magnitude(int32_t value) {
  return value < 0 ? (uint32_t)(-(int64_t)value) : (uint32_t)value;
}

static void format_fixed(char *output, size_t size, int32_t value, int32_t format) {
  const FormatDescriptor descriptor = s_formats[format];
  const uint32_t absolute = magnitude(value);
  uint32_t divisor = 1;
  for (uint8_t i = 0; i < descriptor.decimals; i++) divisor *= 10;
  const char separator = i18n_current_locale() == I18N_LOCALE_DE ? ',' : '.';
  if (descriptor.decimals == 0) {
    snprintf(output, size, "%s%lu%s", value < 0 ? "-" : "", (unsigned long)absolute,
             descriptor.suffix);
  } else if (descriptor.decimals == 1) {
    snprintf(output, size, "%s%lu%c%01lu%s", value < 0 ? "-" : "",
             (unsigned long)(absolute / divisor), separator,
             (unsigned long)(absolute % divisor), descriptor.suffix);
  } else {
    snprintf(output, size, "%s%lu%c%02lu%s", value < 0 ? "-" : "",
             (unsigned long)(absolute / divisor), separator,
             (unsigned long)(absolute % divisor), descriptor.suffix);
  }
}

static void format_time(char *output, size_t size, uint32_t seconds) {
  snprintf(output, size, "%02lu:%02lu", (unsigned long)(seconds / 3600),
           (unsigned long)((seconds / 60) % 60));
}

void ui_metric_format(char *output, size_t size, int metric, const UiMetricSnapshot *snapshot) {
  if (!output || !size) return;
  if (!snapshot) { snprintf(output, size, "—"); return; }
  if (metric == METRIC_ELAPSED) { format_time(output, size, snapshot->elapsed); return; }
  const int32_t value = metric_value(metric, snapshot);
  if (value == UI_METRIC_UNAVAILABLE) { snprintf(output, size, "—"); return; }
  if (metric == METRIC_MOVING_TIME) { format_time(output, size, (uint32_t)value); return; }
  if (metric == METRIC_CURRENT_PACE || metric == METRIC_AVERAGE_PACE) {
    if (value <= 0) snprintf(output, size, "—");
    else snprintf(output, size, "%ld:%02ld%s", (long)(value / 60), (long)(value % 60),
                  s_formats[snapshot->pace_format].suffix);
    return;
  }
  const int32_t format = metric_format(metric, snapshot);
  if (ui_format_code_valid(format)) { format_fixed(output, size, value, format); return; }
  if (metric == METRIC_AVG_HR || metric == METRIC_MAX_HR || metric == METRIC_CURRENT_HR) {
    snprintf(output, size, "%ld bpm", (long)value);
  } else if (metric == METRIC_AVG_CADENCE || metric == METRIC_MAX_CADENCE) {
    snprintf(output, size, "%ld rpm", (long)value);
  } else if (metric == METRIC_AVG_POWER || metric == METRIC_MAX_POWER) {
    snprintf(output, size, "%ld W", (long)value);
  } else {
    snprintf(output, size, "—");
  }
}
