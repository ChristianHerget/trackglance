#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
  uint32_t metadata_key[2];
  uint32_t legacy_key;
  uint32_t bank_base[2];
  uint8_t max_chunks;
} PersistentBlob;

bool persistent_blob_exists(const PersistentBlob *blob);
bool persistent_blob_read(const PersistentBlob *blob, char *output, size_t output_size);
bool persistent_blob_write(const PersistentBlob *blob, const char *data, size_t length);
bool persistent_blob_delete(const PersistentBlob *blob);
