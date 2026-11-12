#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <limits.h>
#define PACK_STRUCT_BEGIN __pragma(pack(push, 1))
#define PACK_STRUCT_END __pragma(pack(pop))
#define PACK_STRUCT_STRUCT
#define PACK_STRUCT_FIELD(x) x
#define LWIP_RAND() ((uint32_t)rand())
typedef int sys_prot_t;
