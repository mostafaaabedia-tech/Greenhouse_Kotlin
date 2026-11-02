#pragma once
#include <string.h>
#define PGM_P const char*
#define PSTR(x) (x)
#define memcpy_P memcpy
#define strlen_P strlen
#define strcmp_P strcmp
#define strncpy_P strncpy
#define pgm_read_byte(p) (*(const unsigned char*)(p))
#define pgm_read_ptr(p) (*(void* const*)(p))
