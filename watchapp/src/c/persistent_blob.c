#include "persistent_blob.h"

#include <pebble.h>
#include <string.h>

#define PERSISTENT_BLOB_MAGIC 0x50424c42u
#define PERSISTENT_BLOB_FORMAT 2
#define FNV_OFFSET 2166136261u
#define FNV_PRIME 16777619u

typedef struct {
  uint32_t magic;
  uint32_t generation;
  uint32_t data_checksum;
  uint32_t metadata_checksum;
  uint16_t length;
  uint8_t format;
  uint8_t bank;
  uint8_t chunk_count;
  uint8_t reserved[3];
} PersistentBlobMetadata;

typedef struct {
  bool present;
  uint8_t slot;
  PersistentBlobMetadata metadata;
} MetadataCandidate;

static uint32_t checksum_update(uint32_t value, const void *data, size_t length) {
  const uint8_t *bytes = data;
  for (size_t i = 0; i < length; i++) {
    value ^= bytes[i];
    value *= FNV_PRIME;
  }
  return value;
}

static uint32_t data_checksum(const char *data, size_t length) {
  return checksum_update(FNV_OFFSET, data, length);
}

static uint32_t metadata_checksum(const PersistentBlobMetadata *metadata) {
  uint32_t value = FNV_OFFSET;
  value = checksum_update(value, &metadata->magic, sizeof(metadata->magic));
  value = checksum_update(value, &metadata->generation, sizeof(metadata->generation));
  value = checksum_update(value, &metadata->data_checksum, sizeof(metadata->data_checksum));
  value = checksum_update(value, &metadata->length, sizeof(metadata->length));
  value = checksum_update(value, &metadata->format, sizeof(metadata->format));
  value = checksum_update(value, &metadata->bank, sizeof(metadata->bank));
  value = checksum_update(value, &metadata->chunk_count, sizeof(metadata->chunk_count));
  value = checksum_update(value, metadata->reserved, sizeof(metadata->reserved));
  return value;
}

static uint8_t chunk_count(size_t length) {
  return (uint8_t)((length + PERSIST_DATA_MAX_LENGTH - 1) / PERSIST_DATA_MAX_LENGTH);
}

static bool key_in_bank(const PersistentBlob *blob, uint8_t bank, uint32_t key) {
  if (!blob || bank > 1 || !blob->max_chunks) return false;
  const uint32_t first = blob->bank_base[bank];
  const uint32_t last = first + blob->max_chunks - 1;
  return key >= first && key <= last;
}

static bool blob_valid(const PersistentBlob *blob) {
  if (!blob || !blob->max_chunks ||
      blob->bank_base[0] > UINT32_MAX - (blob->max_chunks - 1) ||
      blob->bank_base[1] > UINT32_MAX - (blob->max_chunks - 1) ||
      blob->metadata_key[0] == blob->metadata_key[1] ||
      blob->metadata_key[0] == blob->legacy_key ||
      blob->metadata_key[1] == blob->legacy_key) {
    return false;
  }
  const uint32_t first_end = blob->bank_base[0] + blob->max_chunks - 1;
  const uint32_t second_end = blob->bank_base[1] + blob->max_chunks - 1;
  if (!(first_end < blob->bank_base[1] || second_end < blob->bank_base[0])) return false;
  for (uint8_t bank = 0; bank < 2; bank++) {
    if (key_in_bank(blob, bank, blob->metadata_key[0]) ||
        key_in_bank(blob, bank, blob->metadata_key[1]) ||
        key_in_bank(blob, bank, blob->legacy_key)) {
      return false;
    }
  }
  return true;
}

static bool metadata_read(
    const PersistentBlob *blob,
    uint8_t slot,
    PersistentBlobMetadata *metadata) {
  if (!blob_valid(blob) || slot > 1 || !metadata ||
      persist_get_size(blob->metadata_key[slot]) != (int)sizeof(*metadata) ||
      persist_read_data(blob->metadata_key[slot], metadata, sizeof(*metadata)) !=
          (int)sizeof(*metadata)) {
    return false;
  }
  const size_t capacity = (size_t)blob->max_chunks * PERSIST_DATA_MAX_LENGTH;
  return metadata->magic == PERSISTENT_BLOB_MAGIC &&
      metadata->format == PERSISTENT_BLOB_FORMAT && metadata->bank < 2 &&
      metadata->length <= capacity &&
      metadata->chunk_count == chunk_count(metadata->length) &&
      metadata->chunk_count <= blob->max_chunks && metadata->reserved[0] == 0 &&
      metadata->reserved[1] == 0 && metadata->reserved[2] == 0 &&
      metadata->metadata_checksum == metadata_checksum(metadata);
}

static bool generation_after(uint32_t left, uint32_t right) {
  return left != right && (uint32_t)(left - right) < 0x80000000u;
}

static void order_candidates(MetadataCandidate candidates[2]) {
  if (!candidates[0].present ||
      (candidates[1].present && generation_after(
          candidates[1].metadata.generation,
          candidates[0].metadata.generation))) {
    const MetadataCandidate temporary = candidates[0];
    candidates[0] = candidates[1];
    candidates[1] = temporary;
  }
}

static void read_candidates(
    const PersistentBlob *blob,
    MetadataCandidate candidates[2]) {
  memset(candidates, 0, sizeof(MetadataCandidate) * 2);
  for (uint8_t slot = 0; slot < 2; slot++) {
    candidates[slot].slot = slot;
    candidates[slot].present = metadata_read(
        blob,
        slot,
        &candidates[slot].metadata);
  }
  order_candidates(candidates);
}

static bool read_data(
    const PersistentBlob *blob,
    const PersistentBlobMetadata *metadata,
    char *output,
    size_t output_size) {
  if (!blob || !metadata || !output || metadata->length >= output_size) return false;
  size_t offset = 0;
  for (uint8_t i = 0; i < metadata->chunk_count; i++) {
    const size_t remaining = metadata->length - offset;
    const size_t expected = remaining < PERSIST_DATA_MAX_LENGTH ?
        remaining : PERSIST_DATA_MAX_LENGTH;
    const uint32_t key = blob->bank_base[metadata->bank] + i;
    if (persist_get_size(key) != (int)expected ||
        persist_read_data(key, output + offset, expected) != (int)expected) {
      return false;
    }
    offset += expected;
  }
  if (data_checksum(output, metadata->length) != metadata->data_checksum) return false;
  output[metadata->length] = '\0';
  return true;
}

static bool stored_data_valid(
    const PersistentBlob *blob,
    const PersistentBlobMetadata *metadata) {
  uint8_t buffer[PERSIST_DATA_MAX_LENGTH];
  uint32_t value = FNV_OFFSET;
  size_t offset = 0;
  for (uint8_t i = 0; i < metadata->chunk_count; i++) {
    const size_t remaining = metadata->length - offset;
    const size_t expected = remaining < sizeof(buffer) ? remaining : sizeof(buffer);
    const uint32_t key = blob->bank_base[metadata->bank] + i;
    if (persist_get_size(key) != (int)expected ||
        persist_read_data(key, buffer, expected) != (int)expected) {
      return false;
    }
    value = checksum_update(value, buffer, expected);
    offset += expected;
  }
  return value == metadata->data_checksum;
}

static bool delete_key(uint32_t key) {
  return !persist_exists(key) || persist_delete(key) >= 0;
}

static bool delete_bank(const PersistentBlob *blob, uint8_t bank) {
  if (!blob_valid(blob) || bank > 1) return false;
  bool success = true;
  for (uint8_t i = 0; i < blob->max_chunks; i++) {
    if (!delete_key(blob->bank_base[bank] + i)) success = false;
  }
  return success;
}

static bool retain_key_within(size_t *available, uint32_t key) {
  if (!available) return false;
  const int stored_size = persist_get_size(key);
  if (stored_size <= 0) return true;
  if ((size_t)stored_size > *available) return false;
  *available -= (size_t)stored_size;
  return true;
}

static bool write_fits(
    const PersistentBlob *blob,
    uint8_t target_bank,
    uint8_t target_slot,
    size_t length) {
  size_t available = persist_get_max_size();
  if (!blob_valid(blob) || target_bank > 1 || target_slot > 1 ||
      length > available || sizeof(PersistentBlobMetadata) > available - length) {
    return false;
  }
  available -= length + sizeof(PersistentBlobMetadata);

  const uint8_t retained_bank = (uint8_t)(1 - target_bank);
  const uint8_t retained_slot = (uint8_t)(1 - target_slot);
  if (!retain_key_within(&available, blob->metadata_key[retained_slot]) ||
      !retain_key_within(&available, blob->legacy_key)) {
    return false;
  }
  for (uint8_t i = 0; i < blob->max_chunks; i++) {
    if (!retain_key_within(&available, blob->bank_base[retained_bank] + i)) return false;
  }
  return true;
}

static bool legacy_read(const PersistentBlob *blob, char *output, size_t output_size) {
  const int stored_size = persist_get_size(blob->legacy_key);
  if (stored_size < 1 || (size_t)stored_size > output_size ||
      persist_read_string(blob->legacy_key, output, output_size) != stored_size) {
    return false;
  }
  const char *terminator = memchr(output, '\0', (size_t)stored_size);
  return terminator == output + stored_size - 1;
}

bool persistent_blob_exists(const PersistentBlob *blob) {
  return blob_valid(blob) && (persist_exists(blob->metadata_key[0]) ||
      persist_exists(blob->metadata_key[1]) || persist_exists(blob->legacy_key));
}

bool persistent_blob_read(const PersistentBlob *blob, char *output, size_t output_size) {
  if (!blob_valid(blob) || !output || output_size < 1) return false;
  output[0] = '\0';

  MetadataCandidate candidates[2];
  read_candidates(blob, candidates);
  for (uint8_t i = 0; i < 2; i++) {
    if (!candidates[i].present) continue;
    if (candidates[i].metadata.length >= output_size) {
      if (stored_data_valid(blob, &candidates[i].metadata)) return false;
      continue;
    }
    if (read_data(blob, &candidates[i].metadata, output, output_size)) return true;
  }
  output[0] = '\0';
  if (candidates[0].present || candidates[1].present) return false;
  if (legacy_read(blob, output, output_size)) return true;
  output[0] = '\0';
  return false;
}

bool persistent_blob_write(const PersistentBlob *blob, const char *data, size_t length) {
  if (!blob_valid(blob) || (!data && length) || length > UINT16_MAX ||
      length > (size_t)blob->max_chunks * PERSIST_DATA_MAX_LENGTH) {
    return false;
  }

  MetadataCandidate candidates[2];
  read_candidates(blob, candidates);
  MetadataCandidate *current = NULL;
  for (uint8_t i = 0; i < 2; i++) {
    if (candidates[i].present && stored_data_valid(blob, &candidates[i].metadata)) {
      current = &candidates[i];
      break;
    }
  }

  const uint8_t bank = current ? (uint8_t)(1 - current->metadata.bank) : 0;
  const uint8_t slot = current ? (uint8_t)(1 - current->slot) : 0;
  const uint32_t generation = current ? current->metadata.generation + 1 : 1;
  const uint8_t chunks = chunk_count(length);

  if (!write_fits(blob, bank, slot, length)) return false;
  if (!delete_key(blob->metadata_key[slot]) || !delete_bank(blob, bank)) return false;

  size_t offset = 0;
  for (uint8_t i = 0; i < chunks; i++) {
    const size_t remaining = length - offset;
    const size_t size = remaining < PERSIST_DATA_MAX_LENGTH ?
        remaining : PERSIST_DATA_MAX_LENGTH;
    if (persist_write_data(blob->bank_base[bank] + i, data + offset, size) != (int)size) {
      delete_bank(blob, bank);
      return false;
    }
    offset += size;
  }

  PersistentBlobMetadata metadata = {
    .magic = PERSISTENT_BLOB_MAGIC,
    .generation = generation,
    .data_checksum = data_checksum(data, length),
    .metadata_checksum = 0,
    .length = (uint16_t)length,
    .format = PERSISTENT_BLOB_FORMAT,
    .bank = bank,
    .chunk_count = chunks,
    .reserved = {0, 0, 0},
  };
  metadata.metadata_checksum = metadata_checksum(&metadata);
  if (persist_write_data(blob->metadata_key[slot], &metadata, sizeof(metadata)) !=
      (int)sizeof(metadata)) {
    delete_key(blob->metadata_key[slot]);
    delete_bank(blob, bank);
    return false;
  }

  if (!delete_key(blob->legacy_key)) {
    delete_key(blob->metadata_key[slot]);
    delete_bank(blob, bank);
    return false;
  }
  return true;
}

bool persistent_blob_delete(const PersistentBlob *blob) {
  if (!blob_valid(blob)) return false;

  MetadataCandidate candidates[2];
  read_candidates(blob, candidates);
  if (candidates[0].present) {
    const uint8_t newest_slot = candidates[0].slot;
    const uint8_t newest_bank = candidates[0].metadata.bank;
    if (!delete_key(blob->metadata_key[1 - newest_slot])) return false;
    if (!delete_key(blob->legacy_key)) return false;
    if (!delete_bank(blob, (uint8_t)(1 - newest_bank))) return false;
    if (!delete_bank(blob, newest_bank)) return false;
    return delete_key(blob->metadata_key[newest_slot]);
  }

  if (!delete_key(blob->metadata_key[0])) return false;
  if (!delete_key(blob->metadata_key[1])) return false;
  if (!delete_bank(blob, 0)) return false;
  if (!delete_bank(blob, 1)) return false;
  return delete_key(blob->legacy_key);
}
