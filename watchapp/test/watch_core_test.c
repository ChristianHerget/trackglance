#include "persistent_blob.h"
#include "watch_config.h"

#include <assert.h>
#include <setjmp.h>
#include <stdio.h>
#include <string.h>

#include <pebble.h>

#define STORE_KEYS 256

typedef struct {
  bool exists;
  size_t length;
  unsigned char data[PERSIST_DATA_MAX_LENGTH];
} StoredValue;

static StoredValue s_store[STORE_KEYS];
static int s_fail_write_key = -1;
static int s_torn_write_key = -1;
static size_t s_torn_write_bytes;
static int s_fail_delete_key = -1;
static int s_crash_before_delete = -1;
static int s_delete_calls;
static jmp_buf s_power_cut;
static size_t s_persist_max_size = STORE_KEYS * PERSIST_DATA_MAX_LENGTH;

static StoredValue *stored(uint32_t key) {
  assert(key < STORE_KEYS);
  return &s_store[key];
}

bool persist_exists(uint32_t key) {
  return stored(key)->exists;
}

static size_t persist_used(void) {
  size_t total = 0;
  for (size_t i = 0; i < STORE_KEYS; i++) {
    if (s_store[i].exists) total += s_store[i].length;
  }
  return total;
}

size_t persist_get_max_size(void) {
  return s_persist_max_size;
}

int persist_get_size(uint32_t key) {
  StoredValue *value = stored(key);
  return value->exists ? (int)value->length : E_DOES_NOT_EXIST;
}

int persist_read_data(uint32_t key, void *buffer, size_t buffer_size) {
  StoredValue *value = stored(key);
  if (!value->exists) return E_DOES_NOT_EXIST;
  const size_t size = value->length < buffer_size ? value->length : buffer_size;
  memcpy(buffer, value->data, size);
  return (int)size;
}

int persist_read_string(uint32_t key, char *buffer, size_t buffer_size) {
  return persist_read_data(key, buffer, buffer_size);
}

int persist_write_data(uint32_t key, const void *data, size_t size) {
  if ((int)key == s_fail_write_key || size > PERSIST_DATA_MAX_LENGTH) return E_ERROR;
  StoredValue *value = stored(key);
  const size_t written_size = (int)key == s_torn_write_key && s_torn_write_bytes < size ?
      s_torn_write_bytes : size;
  if (persist_used() - (value->exists ? value->length : 0) + written_size >
      s_persist_max_size) {
    return E_ERROR;
  }
  if (written_size) memcpy(value->data, data, written_size);
  value->length = written_size;
  value->exists = true;
  return (int)key == s_torn_write_key ? E_ERROR : (int)size;
}

int persist_write_string(uint32_t key, const char *cstring) {
  return persist_write_data(key, cstring, strlen(cstring) + 1);
}

status_t persist_delete(uint32_t key) {
  if (s_crash_before_delete >= 0 && s_delete_calls++ == s_crash_before_delete) {
    longjmp(s_power_cut, 1);
  }
  if ((int)key == s_fail_delete_key) return E_ERROR;
  StoredValue *value = stored(key);
  if (!value->exists) return E_DOES_NOT_EXIST;
  memset(value, 0, sizeof(*value));
  return S_TRUE;
}

static void reset_store(void) {
  memset(s_store, 0, sizeof(s_store));
  s_fail_write_key = -1;
  s_torn_write_key = -1;
  s_torn_write_bytes = 0;
  s_fail_delete_key = -1;
  s_crash_before_delete = -1;
  s_delete_calls = 0;
  s_persist_max_size = STORE_KEYS * PERSIST_DATA_MAX_LENGTH;
}

static void fill(char *output, size_t length, char seed) {
  for (size_t i = 0; i < length; i++) output[i] = (char)(seed + i % 20);
  output[length] = '\0';
}

static const PersistentBlob TEST_BLOB = {
  .metadata_key = {1, 3},
  .legacy_key = 2,
  .bank_base = {10, 20},
  .max_chunks = 4,
};

static void assert_blob_equals(const PersistentBlob *blob, const char *expected, size_t length) {
  char output[1025];
  memset(output, 0xa5, sizeof(output));
  assert(persistent_blob_read(blob, output, sizeof(output)));
  assert(memcmp(output, expected, length) == 0);
  assert(output[length] == '\0');
}

static bool delete_with_power_cut(const PersistentBlob *blob, int crash_before_delete) {
  s_delete_calls = 0;
  s_crash_before_delete = crash_before_delete;
  if (setjmp(s_power_cut) != 0) {
    s_crash_before_delete = -1;
    return false;
  }
  const bool deleted = persistent_blob_delete(blob);
  s_crash_before_delete = -1;
  assert(deleted);
  return true;
}

static void test_persistent_blob_boundaries(void) {
  reset_store();
  char value[1026];
  const size_t lengths[] = {0, 255, 256, 257, 1024};
  for (size_t i = 0; i < sizeof(lengths) / sizeof(lengths[0]); i++) {
    fill(value, lengths[i], (char)('A' + i));
    assert(persistent_blob_write(&TEST_BLOB, value, lengths[i]));
    assert_blob_equals(&TEST_BLOB, value, lengths[i]);
  }
  assert(!persistent_blob_write(&TEST_BLOB, value, 1025));
  fill(value, 1024, 'E');
  assert_blob_equals(&TEST_BLOB, value, 1024);

  char too_small[1024];
  assert(!persistent_blob_read(&TEST_BLOB, too_small, sizeof(too_small)));
  assert(too_small[0] == '\0');
}

static void test_persistent_blob_recovery(void) {
  reset_store();
  const PersistentBlob blob = {
    .metadata_key = {1, 3},
    .legacy_key = 2,
    .bank_base = {10, 20},
    .max_chunks = 4,
  };
  char first[701];
  char second[601];
  fill(first, 700, 'A');
  fill(second, 600, 'a');

  assert(!persistent_blob_exists(&blob));
  assert(persistent_blob_write(&blob, first, strlen(first)));
  assert(persistent_blob_exists(&blob));
  assert_blob_equals(&blob, first, strlen(first));

  s_fail_write_key = 20;
  assert(!persistent_blob_write(&blob, second, strlen(second)));
  s_fail_write_key = -1;
  assert_blob_equals(&blob, first, strlen(first));

  s_torn_write_key = 20;
  s_torn_write_bytes = 113;
  assert(!persistent_blob_write(&blob, second, strlen(second)));
  s_torn_write_key = -1;
  assert_blob_equals(&blob, first, strlen(first));

  s_fail_write_key = 3;
  assert(!persistent_blob_write(&blob, second, strlen(second)));
  s_fail_write_key = -1;
  assert_blob_equals(&blob, first, strlen(first));

  s_torn_write_key = 3;
  s_torn_write_bytes = 7;
  assert(!persistent_blob_write(&blob, second, strlen(second)));
  s_torn_write_key = -1;
  assert_blob_equals(&blob, first, strlen(first));

  assert(persistent_blob_write(&blob, second, strlen(second)));
  assert_blob_equals(&blob, second, strlen(second));

  stored(3)->data[4] ^= 1;
  assert_blob_equals(&blob, first, strlen(first));
  stored(3)->data[4] ^= 1;
  stored(20)->data[0] ^= 1;
  assert_blob_equals(&blob, first, strlen(first));

  const char legacy[] = "legacy configuration";
  assert(persist_write_string(blob.legacy_key, legacy) == (int)sizeof(legacy));
  stored(10)->data[0] ^= 1;
  char output[1025];
  assert(!persistent_blob_read(&blob, output, sizeof(output)));
  assert(output[0] == '\0');

  assert(persistent_blob_delete(&blob));
  assert(!persistent_blob_exists(&blob));

  assert(persist_write_string(blob.legacy_key, legacy) == (int)sizeof(legacy));
  assert(persistent_blob_read(&blob, output, sizeof(output)));
  assert(strcmp(legacy, output) == 0);
  assert(persistent_blob_write(&blob, first, strlen(first)));
  assert(!persist_exists(blob.legacy_key));
  assert(persistent_blob_delete(&blob));
  assert(persistent_blob_delete(&blob));
}

static void test_persistent_blob_legacy_barrier(void) {
  const char legacy[] = "legacy configuration";
  char current[301];
  char output[1025];
  fill(current, 300, 'A');

  reset_store();
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  s_fail_delete_key = (int)TEST_BLOB.legacy_key;
  assert(!persistent_blob_write(&TEST_BLOB, current, 300));
  s_fail_delete_key = -1;
  assert(persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(strcmp(output, legacy) == 0);
  assert(!persist_exists(TEST_BLOB.metadata_key[0]));
  assert(!persist_exists(TEST_BLOB.bank_base[0]));

  reset_store();
  assert(persistent_blob_write(&TEST_BLOB, current, 300));
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  stored(TEST_BLOB.bank_base[0])->data[0] ^= 1;
  memset(output, 0xa5, sizeof(output));
  assert(!persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(output[0] == '\0');

  reset_store();
  assert(persistent_blob_write(&TEST_BLOB, current, 300));
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  stored(TEST_BLOB.metadata_key[0])->data[4] ^= 1;
  memset(output, 0xa5, sizeof(output));
  assert(persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(strcmp(output, legacy) == 0);

  reset_store();
  assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));
  s_torn_write_key = (int)TEST_BLOB.metadata_key[0];
  s_torn_write_bytes = 7;
  s_fail_delete_key = (int)TEST_BLOB.metadata_key[0];
  assert(!persistent_blob_write(&TEST_BLOB, current, 300));
  s_torn_write_key = -1;
  s_fail_delete_key = -1;
  assert(persist_get_size(TEST_BLOB.metadata_key[0]) == 7);
  assert(persistent_blob_read(&TEST_BLOB, output, sizeof(output)));
  assert(strcmp(output, legacy) == 0);
}

static void test_persistent_blob_delete_power_cuts(void) {
  char first[301];
  char second[302];
  const char legacy[] = "ancient configuration";
  fill(first, 300, 'A');
  fill(second, 301, 'a');

  bool reached_completion = false;
  for (int cut = 0; cut < 20; cut++) {
    reset_store();
    assert(persistent_blob_write(&TEST_BLOB, first, 300));
    assert(persistent_blob_write(&TEST_BLOB, second, 301));
    assert(persist_write_string(TEST_BLOB.legacy_key, legacy) == (int)sizeof(legacy));

    if (delete_with_power_cut(&TEST_BLOB, cut)) {
      reached_completion = true;
      assert(!persistent_blob_exists(&TEST_BLOB));
      break;
    }

    char output[1025];
    const bool readable = persistent_blob_read(&TEST_BLOB, output, sizeof(output));
    if (readable) {
      assert(memcmp(output, second, 301) == 0);
      assert(output[301] == '\0');
    } else {
      assert(output[0] == '\0');
    }
    assert(!readable || strcmp(output, legacy) != 0);
    assert(!readable || memcmp(output, first, 300) != 0 || output[300] != '\0');
    assert(persistent_blob_delete(&TEST_BLOB));
    assert(!persistent_blob_exists(&TEST_BLOB));
  }
  assert(reached_completion);
}

static void test_persistent_blob_delete_failures(void) {
  reset_store();
  char first[301];
  char second[302];
  char third[303];
  fill(first, 300, 'A');
  fill(second, 301, 'a');
  fill(third, 302, 'K');

  assert(persistent_blob_write(&TEST_BLOB, first, 300));
  assert(persistent_blob_write(&TEST_BLOB, second, 301));

  s_fail_delete_key = (int)TEST_BLOB.metadata_key[0];
  assert(!persistent_blob_write(&TEST_BLOB, third, 302));
  s_fail_delete_key = -1;
  assert_blob_equals(&TEST_BLOB, second, 301);

  s_fail_delete_key = (int)TEST_BLOB.bank_base[0];
  assert(!persistent_blob_write(&TEST_BLOB, third, 302));
  s_fail_delete_key = -1;
  assert_blob_equals(&TEST_BLOB, second, 301);

  s_fail_delete_key = (int)TEST_BLOB.metadata_key[1];
  assert(!persistent_blob_delete(&TEST_BLOB));
  assert(persist_exists(TEST_BLOB.metadata_key[1]));
  s_fail_delete_key = -1;
  assert(persistent_blob_delete(&TEST_BLOB));
  assert(!persistent_blob_exists(&TEST_BLOB));

  assert(persistent_blob_write(&TEST_BLOB, first, 300));
  s_fail_delete_key = (int)TEST_BLOB.bank_base[0];
  assert(!persistent_blob_delete(&TEST_BLOB));
  assert(persist_exists(TEST_BLOB.bank_base[0]));
  s_fail_delete_key = -1;
  assert(persistent_blob_delete(&TEST_BLOB));
  assert(!persistent_blob_exists(&TEST_BLOB));
}

static void test_persistent_blob_capacity(void) {
  reset_store();
  char first[258];
  char second[258];
  fill(first, 257, 'A');
  fill(second, 257, 'a');

  assert(persistent_blob_write(&TEST_BLOB, first, 257));
  const size_t first_usage = persist_used();
  const size_t metadata_size = stored(TEST_BLOB.metadata_key[0])->length;
  assert(first_usage == 257 + metadata_size);

  s_persist_max_size = first_usage + 257 + metadata_size - 1;
  assert(!persistent_blob_write(&TEST_BLOB, second, 257));
  assert_blob_equals(&TEST_BLOB, first, 257);
  assert(persist_used() == first_usage);

  assert(persistent_blob_delete(&TEST_BLOB));
  s_persist_max_size = 257 + metadata_size - 1;
  assert(!persistent_blob_write(&TEST_BLOB, first, 257));
  assert(!persistent_blob_exists(&TEST_BLOB));

  s_persist_max_size = 257 + metadata_size;
  assert(persistent_blob_write(&TEST_BLOB, first, 257));
  assert_blob_equals(&TEST_BLOB, first, 257);

  reset_store();
  const char unrelated[] = "other persisted state";
  assert(persist_write_data(50, unrelated, sizeof(unrelated)) == (int)sizeof(unrelated));
  s_persist_max_size = sizeof(unrelated) + 257 + metadata_size - 1;
  assert(!persistent_blob_write(&TEST_BLOB, first, 257));
  assert(persist_used() == sizeof(unrelated));
  assert(!persistent_blob_exists(&TEST_BLOB));

  s_persist_max_size = sizeof(unrelated) + 257 + metadata_size;
  assert(persistent_blob_write(&TEST_BLOB, first, 257));
  assert_blob_equals(&TEST_BLOB, first, 257);
}

static void test_persistent_blob_invalid_layout(void) {
  reset_store();
  assert(!persistent_blob_delete(NULL));
  const PersistentBlob invalid[] = {
    {
      .metadata_key = {1, 2},
      .legacy_key = 3,
      .bank_base = {10, 12},
      .max_chunks = 4,
    },
    {
      .metadata_key = {1, 1},
      .legacy_key = 3,
      .bank_base = {10, 20},
      .max_chunks = 4,
    },
    {
      .metadata_key = {10, 2},
      .legacy_key = 3,
      .bank_base = {10, 20},
      .max_chunks = 4,
    },
    {
      .metadata_key = {1, 2},
      .legacy_key = 20,
      .bank_base = {10, 20},
      .max_chunks = 4,
    },
    {
      .metadata_key = {1, 2},
      .legacy_key = 3,
      .bank_base = {UINT32_MAX - 1, 20},
      .max_chunks = 4,
    },
  };
  for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); i++) {
    assert(!persistent_blob_write(&invalid[i], "value", 5));
    assert(!persistent_blob_exists(&invalid[i]));
    assert(!persistent_blob_delete(&invalid[i]));
  }
}

static bool parse(const char *value, const char *active, WatchConfig *output) {
  char buffer[4096];
  const size_t length = strlen(value);
  assert(length < sizeof(buffer));
  memcpy(buffer, value, length + 1);
  return watch_config_parse(buffer, active, output);
}

static void test_watch_config_transfer(void) {
  WatchConfigTransfer transfer;
  char buffer[WATCH_CONFIG_BUFFER_SIZE];
  watch_config_transfer_reset(&transfer);
  buffer[0] = '\0';

  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 7, 0, 3, "ab", 2) == WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 7, 1, 3, "cd", 2) == WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 7, 0, 3, "ab", 2) == WATCH_TRANSFER_DUPLICATE);
  assert(transfer.next_chunk == 2);
  assert(transfer.length == 4);
  assert(strcmp(buffer, "abcd") == 0);
  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 7, 1, 3, "cd", 2) == WATCH_TRANSFER_DUPLICATE);

  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 99, 1, 3, "unrelated", 9) == WATCH_TRANSFER_IGNORED);
  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 99, 1, WATCH_CONFIG_MAX_CHUNKS + 1,
      "bad", 3) == WATCH_TRANSFER_IGNORED);
  assert(transfer.id == 7);
  assert(transfer.next_chunk == 2);

  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 7, 2, 3, "ef", 2) == WATCH_TRANSFER_COMPLETE);
  assert(strcmp(buffer, "abcdef") == 0);

  watch_config_transfer_reset(&transfer);
  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 8, 0, 2, "one", 3) == WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 8, 0, 2, "changed", 7) == WATCH_TRANSFER_INVALID);
  assert(transfer.id == -1);

  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 9, 0, 2, "one", 3) == WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(
      &transfer, buffer, sizeof(buffer), 9, 1, 3, "two", 3) == WATCH_TRANSFER_INVALID);
  assert(transfer.id == -1);

  char small[5];
  assert(watch_config_transfer_accept(
      &transfer, small, sizeof(small), 10, 0, 2, "four", 4) == WATCH_TRANSFER_ACCEPTED);
  assert(watch_config_transfer_accept(
      &transfer, small, sizeof(small), 10, 1, 2, "x", 1) == WATCH_TRANSFER_INVALID);
  assert(transfer.id == -1);
}

static void test_watch_text_validation(void) {
  assert(watch_waypoint_name_valid("Summit note"));
  assert(watch_waypoint_name_valid("pipe | is allowed"));
  assert(watch_waypoint_name_valid("\xE2\x80\x83 summit \xE2\x80\x83"));
  assert(!watch_waypoint_name_valid(""));
  assert(!watch_waypoint_name_valid("   "));
  assert(!watch_waypoint_name_valid("\xC2\xA0\xC2\xA0"));
  assert(!watch_waypoint_name_valid("\xEF\xBB\xBF"));
  assert(!watch_waypoint_name_valid("bad\tname"));
  assert(!watch_waypoint_name_valid("\xC0\xAF"));

  char maximum[WATCH_WAYPOINT_NAME_BYTES + 2];
  memset(maximum, 'x', WATCH_WAYPOINT_NAME_BYTES);
  maximum[WATCH_WAYPOINT_NAME_BYTES] = '\0';
  assert(watch_waypoint_name_valid(maximum));
  maximum[WATCH_WAYPOINT_NAME_BYTES] = 'x';
  maximum[WATCH_WAYPOINT_NAME_BYTES + 1] = '\0';
  assert(!watch_waypoint_name_valid(maximum));

  assert(watch_profile_names_equal("\xC3\x84rger", "\xC3\xA4rger"));
  assert(watch_profile_names_equal("\xCE\xA3", "\xCF\x83"));
  assert(watch_profile_names_equal("\xD0\xAF", "\xD1\x8F"));
  assert(!watch_profile_names_equal("\xC4\x80", "\xC4\x81"));
}

static void test_watch_config(void) {
  WatchConfig config;
  const char valid[] =
      "dark|1|1|10\n"
      "Walking|Walking|0|1,3,5|walk\n"
      "Cycling|Cycling|1|1,3,7|cycle";
  assert(parse(valid, "walk", &config));
  assert(config.profile_count == 2);
  assert(config.selected == 0);
  assert(config.dark);
  assert(config.watch_hr_to_locus);
  assert(config.heart_rate_interval == 10);
  assert(!config.profiles[1].protected_profile);
  assert(config.profiles[1].metrics[2] == 7);

  assert(!parse("blue|0\nOnly|Hiking|0|1|id", NULL, &config));
  assert(!parse("dark|x\nOnly|Hiking|0|1|id", NULL, &config));
  assert(!parse("dark|0|2|5\nOnly|Hiking|0|1|id", NULL, &config));
  assert(!parse("dark|0|0|0\nOnly|Hiking|0|1|id", NULL, &config));
  assert(!parse("dark|0\nOnly|Hiking|2|1|id", NULL, &config));
  assert(!parse("dark|0\nOnly|Hiking|0|1x|id", NULL, &config));
  assert(!parse("dark|0\nOnly|Hiking|0|1,1|id", NULL, &config));
  assert(!parse("dark|0\nOnly|Hiking|0|1|same\nOther|Bike|0|2|same", NULL, &config));
  assert(!parse("dark|0\nOnly|Hiking|0|1|one\nonly|Bike|0|2|two", NULL, &config));
  assert(!parse("dark|0\n\xC3\x84rger|Hiking|0|1|one\n\xC3\xA4rger|Bike|0|2|two", NULL, &config));
  assert(parse("dark|0\n\xC4\x80|Hiking|0|1|one\n\xC4\x81|Bike|0|2|two", NULL, &config));
  assert(parse("light|0\nOnly|Hiking|0|1", NULL, &config));
  assert(strncmp(config.profiles[0].id, "legacy-0-", strlen("legacy-0-")) == 0);
  assert(strlen(config.profiles[0].id) < sizeof(config.profiles[0].id));

  assert(!parse("dark|0|0|5|extra\nOnly|Hiking|0|1|id", NULL, &config));
  assert(!parse("dark|0\nOnly|Hiking|0|1|id|extra", NULL, &config));
  assert(!parse("dark|0\n   |Hiking|0|1|id", NULL, &config));
  assert(!parse("dark|0\nOnly|   |0|1|id", NULL, &config));
  assert(!parse("dark|0\nOnly|Hiking|0|1|1234567890123456789012345678901234567890", NULL, &config));

  char unicode_name[85];
  size_t offset = 0;
  for (int i = 0; i < 20; i++) {
    const unsigned char boot[] = {0xf0, 0x9f, 0xa5, 0xbe};
    memcpy(unicode_name + offset, boot, sizeof(boot));
    offset += sizeof(boot);
  }
  unicode_name[offset] = '\0';
  char unicode_config[512];
  snprintf(unicode_config, sizeof(unicode_config), "dark|0\n%s|Hiking|0|1|unicode", unicode_name);
  assert(parse(unicode_config, NULL, &config));
  strcat(unicode_name, "x");
  snprintf(unicode_config, sizeof(unicode_config), "dark|0\n%s|Hiking|0|1|unicode", unicode_name);
  assert(!parse(unicode_config, NULL, &config));

  unicode_name[80] = '\0';
  snprintf(unicode_config, sizeof(unicode_config), "dark|0\n%s|Hiking|0|1", unicode_name);
  assert(parse(unicode_config, NULL, &config));
  assert(strncmp(config.profiles[0].id, "legacy-0-", strlen("legacy-0-")) == 0);
  assert(strlen(config.profiles[0].id) < sizeof(config.profiles[0].id));

  char long_locus[WATCH_LOCUS_NAME_SIZE + 1];
  fill(long_locus, WATCH_LOCUS_NAME_SIZE - 1, 'a');
  char locus_config[512];
  snprintf(locus_config, sizeof(locus_config), "dark|0\nOnly|%s|0|1|id", long_locus);
  assert(parse(locus_config, NULL, &config));
  long_locus[WATCH_LOCUS_NAME_SIZE - 1] = 'x';
  long_locus[WATCH_LOCUS_NAME_SIZE] = '\0';
  snprintf(locus_config, sizeof(locus_config), "dark|0\nOnly|%s|0|1|id", long_locus);
  assert(!parse(locus_config, NULL, &config));

  char too_many[1024] = "dark|0";
  for (int i = 0; i < 9; i++) {
    char line[80];
    snprintf(line, sizeof(line), "\nProfile%d|Locus%d|0|1|id%d", i, i, i);
    strcat(too_many, line);
  }
  assert(!parse(too_many, NULL, &config));

  assert(watch_profile_list_valid("Walking\nWandern \xC3\x84", strlen("Walking\nWandern \xC3\x84")));
  assert(!watch_profile_list_valid("Walking\n", strlen("Walking\n")));
  assert(!watch_profile_list_valid("Walking\n\nCycling", strlen("Walking\n\nCycling")));
  assert(!watch_profile_list_valid("Walking\nCycling\nWalking", strlen("Walking\nCycling\nWalking")));
  const char invalid_utf8[] = {'B', 'a', 'd', (char)0xc0, (char)0xaf};
  assert(!watch_profile_list_valid(invalid_utf8, sizeof(invalid_utf8)));
}

int main(void) {
  test_persistent_blob_boundaries();
  test_persistent_blob_recovery();
  test_persistent_blob_legacy_barrier();
  test_persistent_blob_delete_power_cuts();
  test_persistent_blob_delete_failures();
  test_persistent_blob_capacity();
  test_persistent_blob_invalid_layout();
  test_watch_config_transfer();
  test_watch_text_validation();
  test_watch_config();
  return 0;
}
