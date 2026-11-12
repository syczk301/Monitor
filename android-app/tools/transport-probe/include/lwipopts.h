#include "production_lwipopts.h"
/* Compiler interop only. TCP, NO_SYS, netconn and core-lock settings stay intact. */
#undef LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS 1
/* Android's Unix sys_arch has 128 slots. Win32 defaults to 100. */
#ifndef MAX_QUEUE_ENTRIES
#define MAX_QUEUE_ENTRIES 128
#endif
