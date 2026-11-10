// Copyright (C) 2026  champaca contributors
// Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
//   Anthony Green <green@moxielogic.com>
//
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See COPYING and COPYING.CLASSPATH-EXCEPTION.

// ForeignCheck's native library: jolt test builds it as target/libffitest
// (cc -shared). Structs of each kind the ABIs pass differently by value:
// in integer registers, as floats or doubles (HFAs), through memory when
// larger than 16 bytes, padded, nested, unions, and on the stack after the
// registers run out. The ffi_apply_* functions call a function pointer
// they're given: UpcallCheck's upcalls.
#include <stdbool.h>
#include <pthread.h>
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
typedef struct { char a, b, c; } C3;
typedef struct { int a; int pad; int b; } PI;
int pad_sum(PI p) { return p.a * 10 + p.b; }
int v_cs(int a, int b, int c, int d, int e, int f, int g, int h, C3 v, short s) { return v.a * 1000 + v.b * 100 + v.c * 10 + s; }
int64_t many_big(int a, int b, int c, int d, int e, int f, int g, int h, L3 v) { return v.a + v.b + v.c; }
typedef struct { float a; } F1;
float h3_mix(int a, int b, int c, int d, int e, int f, int g, int h, double i, double j, double k, double l, double m, double n, double o, double p, F3 x, char ch, F3 y) { return x.a + x.b + x.c + ch * 1000 + y.a * 100 + y.b * 10 + y.c; }
float f1_mix(int a, int b, int c, int d, int e, int f, int g, int h, double i, double j, double k, double l, double m, double n, double o, double p, F1 x, char ch, F1 y) { return x.a * 100 + ch * 10 + y.a; }
int chars_short(int a, int b, int c, int d, int e, int f, int g, int h, char p, char q, char r, short s) { return p * 1000 + q * 100 + r * 10 + s; }
void *minus_one(void) { return (void *) -1; }
float h3_pair(int a, int b, int c, int d, int e, int f, int g, int h, double i, double j, double k, double l, double m, double n, double o, double p, F3 x, F3 y) { return x.a + x.b + x.c + y.a * 100 + y.b * 10 + y.c; }

/* upcalls: C calling a function pointer it was given (UpcallCheck) */
int ffi_apply_int(int (*f)(int), int x) { return f(x); }
long long ffi_apply_long(long long (*f)(long long), long long x) { return f(x); }
double ffi_apply_double2(double (*f)(double, double), double a, double b) { return f(a, b); }
float ffi_apply_float(float (*f)(float), float x) { return f(x); }
bool ffi_apply_bool(bool (*f)(bool), bool x) { return f(x); }
char ffi_apply_char(char (*f)(char), char x) { return f(x); }
short ffi_apply_short(short (*f)(short), short x) { return f(x); }
void *ffi_apply_ptr(void *(*f)(void *), void *p) { return f(p); }
void ffi_call_void(void (*f)(void)) { f(); }
int ffi_call_n(int (*f)(int), int n) { int s = 0; for (int i = 0; i < n; i++) s += f(i); return s; }
long long ffi_apply_mixed(long long (*f)(int, double, long long), int a, double b, long long c) { return f(a, b, c); }

/* struct arguments and results through upcalls */
int ffi_apply_p2(int (*f)(P2), int x, int y) { P2 p = { x, y }; return f(p); }
double ffi_apply_d4(double (*f)(D4), double a) { D4 d = { a, a + 1, a + 2, a + 3 }; return f(d); }
int ffi_apply_l3(int64_t (*f)(L3), int64_t a) { L3 l = { a, a * 2, a * 3 }; return (int) f(l); }
int ffi_make_p2(P2 (*f)(int), int x) { P2 p = f(x); return p.x * 100 + p.y; }
double ffi_make_d4(D4 (*f)(double), double a) { D4 d = f(a); return d.a + d.b + d.c + d.d; }
int64_t ffi_make_l3(L3 (*f)(int64_t), int64_t a) { L3 l = f(a); return l.a + l.b + l.c; }
typedef struct { int64_t a, b; } L2;
double ffi_make_f3(F3 (*f)(float), float a) { F3 r = f(a); return r.a + r.b * 10 + r.c * 100; }
double ffi_make_cd(CD (*f)(char), char c) { CD r = f(c); return r.c + r.d; }
int64_t ffi_make_l2(L2 (*f)(int64_t), int64_t a) { L2 r = f(a); return r.a * 1000 + r.b; }

/* upcalls with arguments past the registers: on x86_64 a struct over 16
   bytes always goes on the stack, and a smaller one does when the registers
   left can't hold all of it, the arguments after it still taking them */
typedef struct { double a, b; } D2;
typedef struct { int a, b, c, d, e; } I5;
int ffi_apply_ints10(int (*f)(int, int, int, int, int, int, int, int, int, int)) { return f(1, 2, 3, 4, 5, 6, 7, 8, 9, 10); }
int64_t ffi_apply_l2_late(int64_t (*f)(int, int, int, int, int, L2, int)) { L2 v = { 6, 7 }; return f(1, 2, 3, 4, 5, v, 8); }
double ffi_apply_d2_late(double (*f)(double, double, double, double, double, double, double, D2, double)) { D2 v = { 8.5, 9.25 }; return f(1, 2, 3, 4, 5, 6, 7, v, 10); }
int ffi_apply_i5(int (*f)(int, I5, int), int k) { I5 v = { 1, 2, 3, 4, 5 }; return f(k, v, k + 1); }
int64_t ffi_make_l2_late(L2 (*f)(int, int, int, int, int, int, int, int, int, int, double)) { L2 r = f(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 0.5); return r.a * 1000 + r.b; }
int ffi_make_i5_late(I5 (*f)(int, int, int, int, int, int, int, int, int, int)) { I5 r = f(1, 2, 3, 4, 5, 6, 7, 8, 9, 10); return r.a + r.b * 2 + r.c * 3 + r.d * 4 + r.e * 5; }

/* an upcall from a thread C made, not Java */
struct ffi_thread_call { int (*f)(int); int x; int times; int r; };
static void *ffi_thread_main(void *p) {
  struct ffi_thread_call *c = p;
  for (int i = 0; i < c->times; i++) c->r += c->f(c->x + i);
  return NULL;
}
/* f called times times, with x, x+1, ..., on one new thread: the sum */
int ffi_apply_in_thread(int (*f)(int), int x, int times) {
  struct ffi_thread_call c = { f, x, times, 0 };
  pthread_t t;
  if (pthread_create(&t, NULL, ffi_thread_main, &c) != 0) return -1;
  pthread_join(t, NULL);
  return c.r;
}
