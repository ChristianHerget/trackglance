#ifndef WATCH_OUTBOUND_RETRY_H
#define WATCH_OUTBOUND_RETRY_H

#include <stdbool.h>
#include <stdint.h>

bool watch_outbound_retry_failed(uint8_t *attempts, uint8_t maximum_attempts);
void watch_outbound_retry_reset(uint8_t *attempts);

#endif
