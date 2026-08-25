#ifndef LOCUS_APP_MESSAGE_HANDLER_H
#define LOCUS_APP_MESSAGE_HANDLER_H

#include <pebble.h>

#include "ui_metrics.h"

bool app_message_int32(DictionaryIterator *iterator, uint32_t key, int32_t *output);
bool app_message_uint32(DictionaryIterator *iterator, uint32_t key, uint32_t *output);
bool app_message_cstring(DictionaryIterator *iterator, uint32_t key, size_t max_bytes,
                         const char **output, size_t *length);
int app_message_transfer_generation(DictionaryIterator *iterator, int32_t expected_generation);
bool app_message_snapshot(DictionaryIterator *iterator, UiMetricSnapshot *output);

#endif
