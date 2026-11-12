;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; java.util.zip's Inflater and Deflater over zlib. Split from files.ss, whose
;; defnative helpers and errno handling these use; loaded after it.

;; --- java.util.zip: Inflater and Deflater over zlib -------------------------------------
;; A z_stream is 112 bytes of native memory, at zlib's 64-bit offsets:
;; next_in 0, avail_in 8, next_out 24, avail_out 32, msg 48, adler 96. Each
;; call returns read | written << 31 | finished << 62 | flag << 63, where the
;; flag is "needs a dictionary" for Inflater and "parameters not applied yet"
;; for Deflater, as the JDK's Inflater.c and Deflater.c do.

(define fs-zlib-loaded
  (guard (e [#t #f]) (load-shared-object (if fs-macos? "libz.dylib" "libz.so.1")) #t))
(define-syntax deflibz
  (syntax-rules ()
    [(_ id name (arg ...) ret)
     (define id
       (if (and fs-zlib-loaded (foreign-entry? name))
           (foreign-procedure name (arg ...) ret)
           (lambda xs (jthrow "java/lang/UnsupportedOperationException" (string-append "no zlib " name)))))]))
(deflibz zlib-version-string "zlibVersion" () string)
(deflibz zlib-inflate-init2 "inflateInit2_" (uptr int string int) int)
(deflibz zlib-inflate "inflate" (uptr int) int)
(deflibz zlib-inflate-reset "inflateReset" (uptr) int)
(deflibz zlib-inflate-end "inflateEnd" (uptr) int)
(deflibz zlib-inflate-set-dictionary "inflateSetDictionary" (uptr uptr unsigned-32) int)
(deflibz zlib-deflate-init2 "deflateInit2_" (uptr int int int int int string int) int)
(deflibz zlib-deflate "deflate" (uptr int) int)
(deflibz zlib-deflate-params "deflateParams" (uptr int int) int)
(deflibz zlib-deflate-reset "deflateReset" (uptr) int)
(deflibz zlib-deflate-end "deflateEnd" (uptr) int)
(deflibz zlib-deflate-set-dictionary "deflateSetDictionary" (uptr uptr unsigned-32) int)

(define z-stream-size 112)
(define (z-msg strm what)
  (let ([m (foreign-ref 'uptr strm 48)])
    (if (eqv? m 0) what (utf8->string (bytevector-copy-from-c m)))))
(define (new-z-stream)
  (let ([strm (foreign-alloc z-stream-size)])
    (do ([i 0 (fx+ i 8)]) ((fx= i z-stream-size) strm) (foreign-set! 'integer-64 strm i 0))))
(define (z-init strm r)
  (cond [(fx= r 0) strm]
        [(fx= r -4) (foreign-free strm) (jthrow "java/lang/OutOfMemoryError" #f)]
        ;; Z_STREAM_ERROR: a bad level, strategy or window
        [(fx= r -2) (foreign-free strm) (jthrow "java/lang/IllegalArgumentException" #f)]
        [else (let ([m (z-msg strm "zlib init failed")]) (foreign-free strm) (jthrow "java/lang/InternalError" m))]))

;; a byte[] slice copied into fresh native memory, and the copy back
(define (bytes->native arr off len)
  (let ([p (foreign-alloc (fxmax len 1))])
    (vector->native! (jarr-vec arr) off p 0 len)
    p))
(define (native->bytes! p arr off n)
  (native->vector! p 0 (jarr-vec arr) off n))

(define (z-result read written finished flag)
  (i64 (+ read (ash written 31) (if finished (ash 1 62) 0) (if flag (ash 1 63) 0))))

;; run step on strm with in/out pointers, answer (values read written code)
(define (z-run strm in in-len out out-len step)
  (foreign-set! 'uptr strm 0 in)
  (foreign-set! 'unsigned-32 strm 8 in-len)
  (foreign-set! 'uptr strm 24 out)
  (foreign-set! 'unsigned-32 strm 32 out-len)
  (let ([code (step)])
    (values (fx- in-len (foreign-ref 'unsigned-32 strm 8)) (fx- out-len (foreign-ref 'unsigned-32 strm 32)) code)))

;; One side of a zlib call: a byte[] slice (arr from offset pos) or, when
;; arr is #f, the native address pos; len bytes either way.
(define (z-buf arr pos len) (vector arr pos len))

;; k gets the input as a native pointer: the address itself, or a copy of the
;; byte[] slice freed however k ends.
(define (with-z-input in k)
  (let ([arr (vector-ref in 0)] [pos (vector-ref in 1)] [len (vector-ref in 2)])
    (if arr
        (let ([p #f])
          (dynamic-wind
            (lambda () (set! p (bytes->native arr pos len)))
            (lambda () (k p))
            (lambda () (when p (foreign-free p)) (set! p #f))))
        (k pos))))

;; f gets the output as a native pointer and answers (values read written
;; result failure), failure being #f or a thunk that throws. For a byte[]
;; slice f writes into a native buffer, and what it wrote reaches the byte[]
;; before a failure is thrown, as with the JDK's C code writing in place; the
;; buffer is freed however the call ends.
(define (with-z-output out f)
  (let ([arr (vector-ref out 0)] [pos (vector-ref out 1)] [len (vector-ref out 2)])
    (if arr
        (let ([p #f])
          (dynamic-wind
            (lambda () (set! p (foreign-alloc (fxmax len 1))))
            (lambda ()
              (let-values ([(read written result failure) (f p)])
                (native->bytes! p arr pos written)
                (if failure (failure) result)))
            (lambda () (when p (foreign-free p)) (set! p #f))))
        (let-values ([(read written result failure) (f pos)])
          (if failure (failure) result)))))

;; the four shapes of each call, input and output each a z-buf: f takes
;; native pointers and answers as with-z-output's f
(define (with-z-buffers in out f)
  (with-z-input in (lambda (i) (with-z-output out (lambda (o) (f i o))))))

;; bad data: like Inflater.c, the bytes used so far go to inputConsumed and
;; outputConsumed, which Inflater reads to advance its position, then throw
(define (inflate-step this strm in in-len out out-len)
  (let-values ([(read written code) (z-run strm in in-len out out-len (lambda () (zlib-inflate strm 1)))])
    (case code
      [(1) (values read written (z-result read written #t #f) #f)]
      [(0) (values read written (z-result read written #f #f) #f)]
      [(2) (values read written (z-result read written #f #t) #f)]
      [(-5) (values 0 0 0 #f)]
      [(-3) (let ([m (z-msg strm "invalid data")])
              (jslot-set! this (jfield-index "java/util/zip/Inflater" "inputConsumed") read)
              (jslot-set! this (jfield-index "java/util/zip/Inflater" "outputConsumed") written)
              (values read written 0 (lambda () (jthrow "java/util/zip/DataFormatException" m))))]
      [(-4) (values 0 0 0 (lambda () (jthrow "java/lang/OutOfMemoryError" #f)))]
      [else (let ([m (z-msg strm "inflate failed")]) (values 0 0 0 (lambda () (jthrow "java/lang/InternalError" m))))])))

(define zi "java/util/zip/Inflater.")
(defnative (string-append zi "init(Z)J") (nowrap)
  (let ([strm (new-z-stream)])
    (z-init strm (zlib-inflate-init2 strm (if (eqv? nowrap 1) -15 15) (zlib-version-string) z-stream-size))))
(defnative (string-append zi "inflateBytesBytes(J[BII[BII)J") (this strm in off len out ooff olen)
  (with-z-buffers (z-buf in off len) (z-buf out ooff olen) (lambda (i o) (inflate-step this strm i len o olen))))
(defnative (string-append zi "inflateBytesBuffer(J[BIIJI)J") (this strm in off len oaddr olen)
  (with-z-buffers (z-buf in off len) (z-buf #f oaddr olen) (lambda (i o) (inflate-step this strm i len o olen))))
(defnative (string-append zi "inflateBufferBytes(JJI[BII)J") (this strm iaddr len out ooff olen)
  (with-z-buffers (z-buf #f iaddr len) (z-buf out ooff olen) (lambda (i o) (inflate-step this strm i len o olen))))
(defnative (string-append zi "inflateBufferBuffer(JJIJI)J") (this strm iaddr len oaddr olen)
  (with-z-buffers (z-buf #f iaddr len) (z-buf #f oaddr olen) (lambda (i o) (inflate-step this strm i len o olen))))
(defnative (string-append zi "getAdler(J)I") (strm) (i32 (foreign-ref 'unsigned-64 strm 96)))
(defnative (string-append zi "reset(J)V") (strm)
  (unless (fx= 0 (zlib-inflate-reset strm)) (jthrow "java/lang/InternalError" #f)))
(defnative (string-append zi "end(J)V") (strm) (zlib-inflate-end strm) (foreign-free strm))
(define (z-dictionary strm set p len)
  (let ([r (set strm p len)])
    (unless (fx= r 0) (jthrow "java/lang/IllegalArgumentException" (z-msg strm "bad dictionary")))))
(define (z-dictionary-bytes strm set b off len)
  (let ([p (bytes->native b off len)])
    (dynamic-wind void (lambda () (z-dictionary strm set p len)) (lambda () (foreign-free p)))))
(defnative (string-append zi "setDictionary(J[BII)V") (strm b off len)
  (z-dictionary-bytes strm zlib-inflate-set-dictionary b off len))
(defnative (string-append zi "setDictionaryBuffer(JJI)V") (strm addr len)
  (z-dictionary strm zlib-inflate-set-dictionary addr len))

;; params: bit 0 set to apply strategy (bits 1-2) and level (bits 3 up) first
(define (deflate-step strm in in-len out out-len flush params)
  (if (fxodd? params)
      (let-values ([(read written code)
                    (z-run strm in in-len out out-len
                           (lambda () (zlib-deflate-params strm (fxsra params 3) (fxlogand (fxsra params 1) 3))))])
        (case code
          [(0) (values read written (z-result read written #f #f) #f)]
          [(-5) (values read written (z-result read written #f #t) #f)]
          [else (let ([m (z-msg strm "deflateParams failed")]) (values 0 0 0 (lambda () (jthrow "java/lang/InternalError" m))))]))
      (let-values ([(read written code) (z-run strm in in-len out out-len (lambda () (zlib-deflate strm flush)))])
        (case code
          [(1) (values read written (z-result read written #t #f) #f)]
          [(0 -5) (values read written (z-result read written #f #f) #f)]
          [else (let ([m (z-msg strm "deflate failed")]) (values 0 0 0 (lambda () (jthrow "java/lang/InternalError" m))))]))))

(define zd "java/util/zip/Deflater.")
(defnative (string-append zd "init(IIZ)J") (level strategy nowrap)
  (let ([strm (new-z-stream)])
    ;; method Z_DEFLATED 8, memLevel 8 as the JDK uses
    (z-init strm (zlib-deflate-init2 strm level 8 (if (eqv? nowrap 1) -15 15) 8 strategy (zlib-version-string) z-stream-size))))
(defnative (string-append zd "deflateBytesBytes(J[BII[BIIII)J") (this strm in off len out ooff olen flush params)
  (with-z-buffers (z-buf in off len) (z-buf out ooff olen) (lambda (i o) (deflate-step strm i len o olen flush params))))
(defnative (string-append zd "deflateBytesBuffer(J[BIIJIII)J") (this strm in off len oaddr olen flush params)
  (with-z-buffers (z-buf in off len) (z-buf #f oaddr olen) (lambda (i o) (deflate-step strm i len o olen flush params))))
(defnative (string-append zd "deflateBufferBytes(JJI[BIIII)J") (this strm iaddr len out ooff olen flush params)
  (with-z-buffers (z-buf #f iaddr len) (z-buf out ooff olen) (lambda (i o) (deflate-step strm i len o olen flush params))))
(defnative (string-append zd "deflateBufferBuffer(JJIJIII)J") (this strm iaddr len oaddr olen flush params)
  (with-z-buffers (z-buf #f iaddr len) (z-buf #f oaddr olen) (lambda (i o) (deflate-step strm i len o olen flush params))))
(defnative (string-append zd "getAdler(J)I") (strm) (i32 (foreign-ref 'unsigned-64 strm 96)))
(defnative (string-append zd "reset(J)V") (strm)
  (unless (fx= 0 (zlib-deflate-reset strm)) (jthrow "java/lang/InternalError" #f)))
(defnative (string-append zd "end(J)V") (strm) (zlib-deflate-end strm) (foreign-free strm))
(defnative (string-append zd "setDictionary(J[BII)V") (strm b off len)
  (z-dictionary-bytes strm zlib-deflate-set-dictionary b off len))
(defnative (string-append zd "setDictionaryBuffer(JJI)V") (strm addr len)
  (z-dictionary strm zlib-deflate-set-dictionary addr len))
