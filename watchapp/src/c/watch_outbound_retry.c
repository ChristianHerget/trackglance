#include "watch_outbound_retry.h"

bool watch_outbound_retry_failed(uint8_t *attempts, uint8_t maximum_attempts) {
  if (!attempts || !maximum_attempts) return true;
  (*attempts)++;
  if (*attempts < maximum_attempts) return false;
  *attempts = 0;
  return true;
}

void watch_outbound_retry_reset(uint8_t *attempts) {
  if (attempts) *attempts = 0;
}
