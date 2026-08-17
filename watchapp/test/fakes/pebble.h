#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PERSIST_DATA_MAX_LENGTH 256
#define PERSIST_STRING_MAX_LENGTH PERSIST_DATA_MAX_LENGTH

enum {
  S_SUCCESS = 0,
  S_TRUE = 1,
  E_ERROR = -1,
  E_DOES_NOT_EXIST = -9,
};

typedef int32_t status_t;

bool persist_exists(uint32_t key);
size_t persist_get_max_size(void);
int persist_get_size(uint32_t key);
int persist_read_data(uint32_t key, void *buffer, size_t buffer_size);
int persist_read_string(uint32_t key, char *buffer, size_t buffer_size);
int persist_write_data(uint32_t key, const void *data, size_t size);
int persist_write_string(uint32_t key, const char *cstring);
status_t persist_delete(uint32_t key);
