#include "persistent_blob.h"

#include <pebble.h>
#include <string.h>

#define PERSISTENT_BLOB_MAGIC 0x50424c42u
#define PERSISTENT_BLOB_FORMAT 1

typedef struct {
  uint32_t magic;
  uint16_t length;
  uint8_t format;
  uint8_t chunk_count;
} PersistentBlobHeader;

static uint8_t chunk_count(size_t length) {
  return (uint8_t)((length + PERSIST_DATA_MAX_LENGTH - 1) / PERSIST_DATA_MAX_LENGTH);
}

static bool blob_valid(const PersistentBlob *blob) {
  if (!blob || !blob->max_chunks || blob->chunk_base > UINT32_MAX - (blob->max_chunks - 1))
    return false;
  const uint32_t chunk_end = blob->chunk_base + blob->max_chunks - 1;
  return (blob->record_key < blob->chunk_base || blob->record_key > chunk_end) &&
         (blob->legacy_key < blob->chunk_base || blob->legacy_key > chunk_end) &&
         blob->record_key != blob->legacy_key;
}

static bool delete_key(uint32_t key) {
  return !persist_exists(key) || persist_delete(key) >= 0;
}

static bool delete_chunks(const PersistentBlob *blob) {
  bool success = true;
  for (uint8_t index = 0; index < blob->max_chunks; index++) {
    if (!delete_key(blob->chunk_base + index)) success = false;
  }
  return success;
}

static bool read_legacy(const PersistentBlob *blob, char *output, size_t output_size) {
  const int stored_size = persist_get_size(blob->legacy_key);
  if (stored_size < 1 || (size_t)stored_size > output_size ||
      persist_read_string(blob->legacy_key, output, output_size) != stored_size)
    return false;
  const char *terminator = memchr(output, '\0', (size_t)stored_size);
  return terminator == output + stored_size - 1;
}

bool persistent_blob_exists(const PersistentBlob *blob) {
  return blob_valid(blob) && (persist_exists(blob->record_key) || persist_exists(blob->legacy_key));
}

bool persistent_blob_read(const PersistentBlob *blob, char *output, size_t output_size) {
  if (!blob_valid(blob) || !output || output_size < 1) return false;
  output[0] = '\0';
  if (!persist_exists(blob->record_key)) return read_legacy(blob, output, output_size);

  PersistentBlobHeader header;
  const size_t capacity = (size_t)blob->max_chunks * PERSIST_DATA_MAX_LENGTH;
  if (persist_get_size(blob->record_key) != (int)sizeof(header) ||
      persist_read_data(blob->record_key, &header, sizeof(header)) != (int)sizeof(header) ||
      header.magic != PERSISTENT_BLOB_MAGIC || header.format != PERSISTENT_BLOB_FORMAT ||
      header.length > capacity || header.length >= output_size ||
      header.chunk_count != chunk_count(header.length) || header.chunk_count > blob->max_chunks)
    return false;

  size_t offset = 0;
  for (uint8_t index = 0; index < header.chunk_count; index++) {
    const size_t remaining = header.length - offset;
    const size_t expected =
        remaining < PERSIST_DATA_MAX_LENGTH ? remaining : PERSIST_DATA_MAX_LENGTH;
    if (persist_get_size(blob->chunk_base + index) != (int)expected ||
        persist_read_data(blob->chunk_base + index, output + offset, expected) != (int)expected) {
      output[0] = '\0';
      return false;
    }
    offset += expected;
  }
  output[header.length] = '\0';
  return true;
}

bool persistent_blob_write(const PersistentBlob *blob, const char *data, size_t length) {
  if (!blob_valid(blob) || (!data && length) || length > UINT16_MAX ||
      length > (size_t)blob->max_chunks * PERSIST_DATA_MAX_LENGTH)
    return false;

  // Keep a readable legacy value until the new-format header commits successfully.
  // The header is the commit marker, so an interrupted write cannot appear complete.
  if (!delete_key(blob->record_key) || !delete_chunks(blob)) return false;
  size_t offset = 0;
  const uint8_t chunks = chunk_count(length);
  for (uint8_t index = 0; index < chunks; index++) {
    const size_t remaining = length - offset;
    const size_t size = remaining < PERSIST_DATA_MAX_LENGTH ? remaining : PERSIST_DATA_MAX_LENGTH;
    if (persist_write_data(blob->chunk_base + index, data + offset, size) != (int)size) {
      delete_chunks(blob);
      return false;
    }
    offset += size;
  }

  const PersistentBlobHeader header = {
      .magic = PERSISTENT_BLOB_MAGIC,
      .length = (uint16_t)length,
      .format = PERSISTENT_BLOB_FORMAT,
      .chunk_count = chunks,
  };
  if (persist_write_data(blob->record_key, &header, sizeof(header)) != (int)sizeof(header)) {
    delete_key(blob->record_key);
    delete_chunks(blob);
    return false;
  }
  // The current-format record takes precedence, so stale-key cleanup is best-effort.
  delete_key(blob->legacy_key);
  return true;
}

bool persistent_blob_delete(const PersistentBlob *blob) {
  if (!blob_valid(blob)) return false;
  const bool record_deleted = delete_key(blob->record_key);
  const bool chunks_deleted = delete_chunks(blob);
  const bool legacy_deleted = delete_key(blob->legacy_key);
  return record_deleted && chunks_deleted && legacy_deleted;
}
