// ForeignCheck's native library: bb test builds it as target/libffitest
// (cc -shared). Structs of each kind the ABIs pass differently by value:
// in integer registers, as floats or doubles (HFAs), through memory when
// larger than 16 bytes, padded, nested, unions, and on the stack after the
// registers run out.
#include <stdint.h>
#include <stdarg.h>
#include <string.h>
#include <unistd.h>
typedef struct { int x, y; } P2;
typedef struct { float a, b, c; } F3;
typedef struct { double a, b, c, d; } D4;
typedef struct { char c; double d; } CD;
typedef struct { int64_t a, b, c; } L3;
typedef struct { P2 p; short s[3]; } N;
typedef union { int i; float f; } U;
typedef struct { uint8_t a; uint8_t b; } B2;
P2 p2_add(P2 a, P2 b) { P2 r = { a.x + b.x, a.y + b.y }; return r; }
float f3_sum(F3 f) { return f.a + f.b + f.c; }
F3 f3_scale(F3 f, float k) { F3 r = { f.a*k, f.b*k, f.c*k }; return r; }
D4 d4_rev(D4 d) { D4 r = { d.d, d.c, d.b, d.a }; return r; }
double cd_sum(CD v) { return v.c + v.d; }
L3 l3_mix(int k, L3 v, double z) { L3 r = { v.a * k, v.b + (int64_t) z, v.c - 1 }; return r; }
int n_sum(N n) { return n.p.x + n.p.y + n.s[0] + n.s[1] + n.s[2]; }
int u_bits(U u) { return u.i; }
B2 b2_swap(B2 v) { B2 r = { v.b, v.a }; return r; }
int many(int a, int b, int c, int d, int e, int f, int g, int h, int i, double x, P2 p) { return a+b+c+d+e+f+g+h+i+(int)x+p.x*100+p.y*1000; }
int sum_va(int n, ...) { va_list ap; va_start(ap, n); int s = 0; for (int i = 0; i < n; i++) s += va_arg(ap, int); va_end(ap); return s; }
double dsum_va(int n, ...) { va_list ap; va_start(ap, n); double s = 0; for (int i = 0; i < n; i++) s += va_arg(ap, double); va_end(ap); return s; }
void fill(int *p, int n) { for (int i = 0; i < n; i++) p[i] = i * i; }
int8_t neg8(int8_t v) { return -v; }
uint16_t ch(uint16_t c) { return c + 1; }
short sh(short s) { return s * 2; }
_Bool is_odd(int v) { return v & 1; }
int many_i(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j) { return i * 10 + j; }
int many_p(int a, int b, int c, int d, int e, int f, int g, int h, int i, P2 p) { return i * 10000 + p.x * 100 + p.y; }
int many_p8(int a, int b, int c, int d, int e, int f, int g, int h, P2 p) { return p.x * 100 + p.y; }
int many_l(int a, int b, int c, int d, int e, int f, int g, int h, int i, int64_t l) { return i * 10000 + (int) l; }
int v_pp(int a, int b, int c, int d, int e, int f, int g, int h, P2 p, P2 q) { return p.x*1000 + p.y*100 + q.x*10 + q.y; }
int v_ip(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j, P2 p) { return i*10000 + j*1000 + p.x*10 + p.y; }
int v_sp(int a, int b, int c, int d, int e, int f, int g, int h, short i, P2 p) { return i*10000 + p.x*10 + p.y; }
typedef struct { int64_t x; } L1;
int v_il(int a, int b, int c, int d, int e, int f, int g, int h, int i, L1 p) { return i*10000 + (int) p.x; }
void nap(int us) { usleep(us); }
