#include <stdlib.h>
char* ltoa(long, char*, int);
char* ultoa(unsigned long, char*, int);
char* itoa(int v, char* s, int r) { return ltoa(v, s, r); }
char* utoa(unsigned v, char* s, int r) { return ultoa(v, s, r); }
