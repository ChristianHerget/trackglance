#include "ui_metrics.h"

#include <stdio.h>

static const char *translated(bool german, const char *english, const char *german_text) {
  return german ? german_text : english;
}

const char *ui_metric_label(int metric, bool german) {
  switch (metric) {
    case METRIC_ELAPSED: return translated(german, "Elapsed", "Gesamtzeit");
    case METRIC_MOVING_TIME: return translated(german, "Moving", "Bewegungszeit");
    case METRIC_DISTANCE: return translated(german, "Distance", "Strecke");
    case METRIC_MOVING_DISTANCE: return translated(german, "Move dist", "Bewegungsstr.");
    case METRIC_CURRENT_SPEED: return translated(german, "Speed", "Tempo");
    case METRIC_AVERAGE_SPEED: return translated(german, "Average", "Durchschnitt");
    case METRIC_MAX_SPEED: return translated(german, "Max speed", "Max. Tempo");
    case METRIC_CURRENT_PACE: return translated(german, "Pace", "Pace");
    case METRIC_AVERAGE_PACE: return translated(german, "Avg pace", "Ø Pace");
    case METRIC_ALTITUDE: return translated(german, "Altitude", "Höhe");
    case METRIC_ASCENT: return translated(german, "Ascent", "Anstieg");
    case METRIC_DESCENT: return translated(german, "Descent", "Abstieg");
    case METRIC_VERTICAL_SPEED: return translated(german, "Vertical", "Vertikal");
    case METRIC_SLOPE: return translated(german, "Slope", "Steigung");
    case METRIC_AVG_HR: return translated(german, "Avg HR", "Ø Puls");
    case METRIC_MAX_HR: return translated(german, "Max HR", "Max. Puls");
    case METRIC_AVG_CADENCE: return translated(german, "Avg cadence", "Ø Frequenz");
    case METRIC_MAX_CADENCE: return translated(german, "Max cadence", "Max. Frequenz");
    case METRIC_AVG_POWER: return translated(german, "Avg power", "Ø Leistung");
    case METRIC_MAX_POWER: return translated(german, "Max power", "Max. Leistung");
    case METRIC_ENERGY: return translated(german, "Energy", "Energie");
    case METRIC_CURRENT_HR: return translated(german, "Current HR", "Aktueller Puls");
    default: return "";
  }
}

static int32_t metric_value(int metric, const UiMetricSnapshot *snapshot) {
  if (!snapshot) return UI_METRIC_UNAVAILABLE;
  switch (metric) {
    case METRIC_MOVING_TIME: return snapshot->moving_time;
    case METRIC_DISTANCE: return snapshot->distance;
    case METRIC_MOVING_DISTANCE: return snapshot->moving_distance;
    case METRIC_CURRENT_SPEED:
    case METRIC_CURRENT_PACE: return snapshot->current_speed;
    case METRIC_AVERAGE_SPEED:
    case METRIC_AVERAGE_PACE: return snapshot->average_speed;
    case METRIC_MAX_SPEED: return snapshot->max_speed;
    case METRIC_ALTITUDE: return snapshot->altitude;
    case METRIC_ASCENT: return snapshot->ascent;
    case METRIC_DESCENT: return snapshot->descent;
    case METRIC_VERTICAL_SPEED: return snapshot->vertical_speed;
    case METRIC_SLOPE: return snapshot->slope;
    case METRIC_AVG_HR: return snapshot->avg_hr;
    case METRIC_MAX_HR: return snapshot->max_hr;
    case METRIC_AVG_CADENCE: return snapshot->avg_cadence;
    case METRIC_MAX_CADENCE: return snapshot->max_cadence;
    case METRIC_AVG_POWER: return snapshot->avg_power;
    case METRIC_MAX_POWER: return snapshot->max_power;
    case METRIC_ENERGY: return snapshot->energy;
    case METRIC_CURRENT_HR: return snapshot->current_hr;
    default: return UI_METRIC_UNAVAILABLE;
  }
}

static uint32_t magnitude(int32_t value) {
  return value < 0 ? (uint32_t)(-(int64_t)value) : (uint32_t)value;
}

static void format_fixed(
    char *output,
    size_t size,
    int32_t value,
    uint32_t scale,
    uint8_t digits,
    const char *suffix) {
  const uint32_t absolute = magnitude(value);
  uint32_t divisor = 1;
  for (uint8_t i = 0; i < digits; i++) divisor *= 10;
  const uint32_t fraction = (absolute % scale) * divisor / scale;
  if (digits == 1) {
    snprintf(output, size, "%s%lu.%01lu%s", value < 0 ? "-" : "",
             (unsigned long)(absolute / scale), (unsigned long)fraction, suffix);
  } else {
    snprintf(output, size, "%s%lu.%02lu%s", value < 0 ? "-" : "",
             (unsigned long)(absolute / scale), (unsigned long)fraction, suffix);
  }
}

static void format_time(char *output, size_t size, uint32_t seconds) {
  snprintf(output, size, "%02lu:%02lu:%02lu",
           (unsigned long)(seconds / 3600),
           (unsigned long)((seconds / 60) % 60),
           (unsigned long)(seconds % 60));
}

static bool nonnegative_metric(int metric) {
  return metric == METRIC_MOVING_TIME ||
      (metric >= METRIC_DISTANCE && metric <= METRIC_AVERAGE_PACE) ||
      metric == METRIC_ASCENT || metric == METRIC_DESCENT ||
      (metric >= METRIC_AVG_HR && metric <= METRIC_CURRENT_HR);
}

void ui_metric_format(
    char *output,
    size_t size,
    int metric,
    const UiMetricSnapshot *snapshot,
    uint32_t elapsed) {
  if (!output || !size) return;
  if (metric == METRIC_ELAPSED) {
    format_time(output, size, elapsed);
    return;
  }
  const int32_t value = metric_value(metric, snapshot);
  if (value == UI_METRIC_UNAVAILABLE || (nonnegative_metric(metric) && value < 0)) {
    snprintf(output, size, "—");
    return;
  }
  if (metric == METRIC_MOVING_TIME) {
    format_time(output, size, (uint32_t)value);
  } else if (metric == METRIC_DISTANCE || metric == METRIC_MOVING_DISTANCE) {
    format_fixed(output, size, value, 1000, 2, " km");
  } else if (metric >= METRIC_CURRENT_SPEED && metric <= METRIC_MAX_SPEED) {
    const int32_t tenths_kph = (int32_t)(((int64_t)value * 36) / 100);
    format_fixed(output, size, tenths_kph, 10, 1, " km/h");
  } else if (metric == METRIC_CURRENT_PACE || metric == METRIC_AVERAGE_PACE) {
    if (value <= 0) {
      snprintf(output, size, "—");
    } else {
      const int seconds = 100000 / value;
      snprintf(output, size, "%d:%02d /km", seconds / 60, seconds % 60);
    }
  } else if (metric >= METRIC_ALTITUDE && metric <= METRIC_DESCENT) {
    format_fixed(output, size, value, 10, 1, " m");
  } else if (metric == METRIC_VERTICAL_SPEED) {
    format_fixed(output, size, value, 100, 2, " m/s");
  } else if (metric == METRIC_SLOPE) {
    format_fixed(output, size, value, 10, 1, "%");
  } else if (metric == METRIC_AVG_HR || metric == METRIC_MAX_HR ||
             metric == METRIC_CURRENT_HR) {
    snprintf(output, size, "%ld bpm", (long)value);
  } else if (metric == METRIC_AVG_CADENCE || metric == METRIC_MAX_CADENCE) {
    snprintf(output, size, "%ld rpm", (long)value);
  } else if (metric == METRIC_AVG_POWER || metric == METRIC_MAX_POWER) {
    snprintf(output, size, "%ld W", (long)value);
  } else {
    snprintf(output, size, "%ld kcal", (long)value);
  }
}
