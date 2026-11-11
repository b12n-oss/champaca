;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Files: java.io streams, RandomAccessFile and java.io.UnixFileSystem, and
;; the natives under java.nio.file and FileChannel (sun.nio.fs.UnixNative-
;; Dispatcher, sun.nio.ch.UnixFileDispatcherImpl). All of them call libc
;; through Chez's foreign interface on real file descriptors, so a
;; FileDescriptor from java.io works with a FileChannel and the other way
;; round. Paths reach the NIO natives as addresses of C strings in native
;; memory (Unsafe.allocateMemory, natives.ss), and go to libc as they are.
;; fds 0, 1 and 2 keep going through Chez's own ports, which jolt shares.
;;
;; struct stat, struct dirent, struct flock and the disk-space struct are read
;; through fs-layout, one table per system, each measured with offsetof on that
;; system (ci/linux/layout.c prints Linux's). On a system with no table the
;; natives that read them throw UnsupportedOperationException, and java.io's
;; size and regular-file checks fall back to lseek; the rest only need libc.

(define fs-macos? (and (memq (machine-type) '(tarm64osx arm64osx ta6osx a6osx)) #t))
(define fs-linux? (and (memq (machine-type) '(tarm64le arm64le ta6le a6le)) #t))

;; field -> (offset . foreign type). dirent-namlen is macOS's d_namlen; Linux's
;; d_name is NUL-terminated. space-unit is the block size the totals count in:
;; macOS's statfs f_bsize, Linux's statvfs f_frsize, as each JDK reads them.
;; flock-* are fcntl's commands and lock types, which differ too.
(define fs-layout-macos
  '((st_dev 0 . integer-32) (st_mode 4 . unsigned-16) (st_nlink 6 . unsigned-16)
    (st_ino 8 . unsigned-64) (st_uid 16 . unsigned-32) (st_gid 20 . unsigned-32)
    (st_rdev 24 . integer-32)
    (st_atime_sec 32 . integer-64) (st_atime_nsec 40 . integer-64)
    (st_mtime_sec 48 . integer-64) (st_mtime_nsec 56 . integer-64)
    (st_ctime_sec 64 . integer-64) (st_ctime_nsec 72 . integer-64)
    (st_birthtime_sec 80 . integer-64)
    (st_size 96 . integer-64)
    (dirent-name 21 . #f) (dirent-namlen 18 . unsigned-16)
    (space-unit 0 . unsigned-32) (space-total 8 . unsigned-64) (space-free 16 . unsigned-64) (space-usable 24 . unsigned-64)
    ;; struct statvfs (UnixFileStoreAttributes): 32-bit block counts on macOS
    (vfs-frsize 8 . unsigned-64) (vfs-blocks 16 . unsigned-32) (vfs-bfree 20 . unsigned-32) (vfs-bavail 24 . unsigned-32)
    (flock-start 0 . integer-64) (flock-len 8 . integer-64) (flock-pid 16 . integer-32)
    (flock-type 20 . integer-16) (flock-whence 22 . integer-16)
    (F_SETLK 8 . #f) (F_SETLKW 9 . #f) (F_RDLCK 1 . #f) (F_WRLCK 3 . #f) (F_UNLCK 2 . #f)))
;; glibc on arm64 (the generic layout), from ci/linux/layout.c
(define fs-layout-linux-arm64
  '((st_dev 0 . unsigned-64) (st_ino 8 . unsigned-64) (st_mode 16 . unsigned-32)
    (st_nlink 20 . unsigned-32) (st_uid 24 . unsigned-32) (st_gid 28 . unsigned-32)
    (st_rdev 32 . unsigned-64) (st_size 48 . integer-64)
    (st_atime_sec 72 . integer-64) (st_atime_nsec 80 . integer-64)
    (st_mtime_sec 88 . integer-64) (st_mtime_nsec 96 . integer-64)
    (st_ctime_sec 104 . integer-64) (st_ctime_nsec 112 . integer-64)
    (dirent-name 19 . #f)
    (space-unit 8 . unsigned-64) (space-total 16 . unsigned-64) (space-free 24 . unsigned-64) (space-usable 32 . unsigned-64)
    (vfs-frsize 8 . unsigned-64) (vfs-blocks 16 . unsigned-64) (vfs-bfree 24 . unsigned-64) (vfs-bavail 32 . unsigned-64)
    (flock-type 0 . integer-16) (flock-whence 2 . integer-16) (flock-start 8 . integer-64)
    (flock-len 16 . integer-64) (flock-pid 24 . integer-32)
    (F_SETLK 6 . #f) (F_SETLKW 7 . #f) (F_RDLCK 0 . #f) (F_WRLCK 1 . #f) (F_UNLCK 2 . #f)))
;; glibc on x86_64: struct stat differs from arm64's (st_nlink is 8 bytes and
;; comes before st_mode); the rest is the same. From ci/linux/layout.c.
(define fs-layout-linux-x86_64
  (append
   '((st_dev 0 . unsigned-64) (st_ino 8 . unsigned-64) (st_nlink 16 . unsigned-64)
     (st_mode 24 . unsigned-32) (st_uid 28 . unsigned-32) (st_gid 32 . unsigned-32)
     (st_rdev 40 . unsigned-64))
   (filter (lambda (f) (not (memq (car f) '(st_dev st_ino st_mode st_nlink st_uid st_gid st_rdev))))
           fs-layout-linux-arm64)))
(define fs-layout
  (case (machine-type)
    [(tarm64osx arm64osx ta6osx a6osx) fs-layout-macos]
    [(tarm64le arm64le) fs-layout-linux-arm64]
    [(ta6le a6le) fs-layout-linux-x86_64]
    [else #f]))
(define (fs-off name) (cadr (or (assq name fs-layout) (error 'champaca "no layout field" name))))
(define (fs-has? name) (and fs-layout (assq name fs-layout) #t))
(define (fs-ref buf name)
  (let ([f (or (assq name fs-layout) (error 'champaca "no layout field" name))])
    (foreign-ref (cddr f) buf (cadr f))))
(define (fs-set! buf name v)
  (let ([f (or (assq name fs-layout) (error 'champaca "no layout field" name))])
    (foreign-set! (cddr f) buf (cadr f) v)))
(define fs-libc-loaded
  (guard (e [#t #f])
    (load-shared-object (if fs-macos? "libSystem.dylib" "libc.so.6"))
    #t))
;; whether struct stat and friends can be read here: a table for this machine,
;; and a libc that exports stat itself. glibc does since 2.33; before that, and
;; on a libc whose name isn't libc.so.6 (musl), stat is missing and
;; java.nio.file fails at init rather than at the first stat.
(define fs-stat-layout?
  (and fs-layout fs-libc-loaded
       (foreign-entry? (if (memq (machine-type) '(ta6osx a6osx)) "stat$INODE64" "stat"))
       #t))

;; a libc function, or one that throws when this libc lacks it
(define-syntax deflibc
  (syntax-rules ()
    [(_ id name conv (arg ...) ret)
     (define id
       (if (and fs-libc-loaded (foreign-entry? name))
           (foreign-procedure conv name (arg ...) ret)
           (lambda xs (jthrow "java/lang/UnsupportedOperationException" (string-append "libc has no " name)))))]))

(deflibc libc-errno-location (if fs-macos? "__error" "__errno_location") #f () uptr)
(deflibc libc-open "open" (__varargs_after 2) (uptr int int) int)
(deflibc libc-open-string "open" (__varargs_after 2) (utf-8 int int) int)
(deflibc libc-close "close" #f (int) int)
(deflibc libc-isatty "isatty" #f (int) int)
(deflibc libc-read "read" #f (int uptr size_t) ssize_t)
(deflibc libc-write "write" #f (int uptr size_t) ssize_t)
(deflibc libc-pread "pread" #f (int uptr size_t integer-64) ssize_t)
(deflibc libc-pwrite "pwrite" #f (int uptr size_t integer-64) ssize_t)
(deflibc libc-lseek "lseek" #f (int integer-64 int) integer-64)
(deflibc libc-ftruncate "ftruncate" #f (int integer-64) int)
(deflibc libc-fsync "fsync" #f (int) int)
(deflibc libc-dup "dup" #f (int) int)
(deflibc libc-stat (if (memq (machine-type) '(ta6osx a6osx)) "stat$INODE64" "stat") #f (uptr uptr) int)
(deflibc libc-lstat (if (memq (machine-type) '(ta6osx a6osx)) "lstat$INODE64" "lstat") #f (uptr uptr) int)
(deflibc libc-fstat (if (memq (machine-type) '(ta6osx a6osx)) "fstat$INODE64" "fstat") #f (int uptr) int)
(deflibc libc-stat-string (if (memq (machine-type) '(ta6osx a6osx)) "stat$INODE64" "stat") #f (utf-8 uptr) int)
;; the disk-space call each JDK makes: statfs on macOS, statvfs on Linux
(deflibc libc-space-string (cond [(memq (machine-type) '(ta6osx a6osx)) "statfs$INODE64"]
                                 [fs-macos? "statfs"]
                                 [else "statvfs"])
  #f (utf-8 uptr) int)
(deflibc libc-mkdir "mkdir" #f (uptr int) int)
(deflibc libc-mkdir-string "mkdir" #f (utf-8 int) int)
(deflibc libc-rmdir "rmdir" #f (uptr) int)
(deflibc libc-unlink "unlink" #f (uptr) int)
(deflibc libc-remove-string "remove" #f (utf-8) int)
(deflibc libc-rename "rename" #f (uptr uptr) int)
(deflibc libc-rename-string "rename" #f (utf-8 utf-8) int)
(deflibc libc-link "link" #f (uptr uptr) int)
(deflibc libc-symlink "symlink" #f (uptr uptr) int)
(deflibc libc-readlink "readlink" #f (uptr uptr size_t) ssize_t)
(deflibc libc-realpath "realpath" #f (uptr uptr) uptr)
(deflibc libc-realpath-string "realpath" #f (utf-8 uptr) uptr)
(deflibc libc-chmod "chmod" #f (uptr int) int)
(deflibc libc-chmod-string "chmod" #f (utf-8 int) int)
(deflibc libc-fchmod "fchmod" #f (int int) int)
(deflibc libc-chown "chown" #f (uptr int int) int)
(deflibc libc-lchown "lchown" #f (uptr int int) int)
(deflibc libc-fchown "fchown" #f (int int int) int)
(deflibc libc-access "access" #f (uptr int) int)
(deflibc libc-access-string "access" #f (utf-8 int) int)
(deflibc libc-utimensat "utimensat" #f (int uptr uptr int) int)
(deflibc libc-utimensat-string "utimensat" #f (int utf-8 uptr int) int)
(deflibc libc-futimens "futimens" #f (int uptr) int)
(deflibc libc-opendir (if (memq (machine-type) '(ta6osx a6osx)) "opendir$INODE64" "opendir") #f (uptr) uptr)
(deflibc libc-readdir (if (memq (machine-type) '(ta6osx a6osx)) "readdir$INODE64" "readdir") #f (uptr) uptr)
(deflibc libc-closedir "closedir" #f (uptr) int)
(deflibc libc-getcwd "getcwd" #f (uptr size_t) uptr)
(deflibc libc-strerror "strerror" #f (int) string)
(deflibc libc-realloc "realloc" #f (uptr size_t) uptr)
(deflibc libc-getpagesize "getpagesize" #f () int)
(deflibc libc-pathconf-string "pathconf" #f (utf-8 int) long)
(deflibc libc-fcntl-lock "fcntl" (__varargs_after 2) (int int uptr) int)
(deflibc libc-fcntl-int "fcntl" (__varargs_after 2) (int int int) int)
(deflibc libc-mmap "mmap" #f (uptr size_t int int int integer-64) uptr)
(deflibc libc-munmap "munmap" #f (uptr size_t) int)
(deflibc libc-msync "msync" #f (uptr size_t int) int)
(deflibc libc-getpwuid "getpwuid" #f (unsigned-32) uptr)
(deflibc libc-getgrgid "getgrgid" #f (unsigned-32) uptr)
(deflibc libc-clonefile (if fs-macos? "clonefile" "no-clonefile-here") #f (uptr uptr int) int)
(deflibc libc-setattrlist (if fs-macos? "setattrlist" "no-setattrlist-here") #f (uptr uptr uptr unsigned-long unsigned-int) int)
(deflibc libc-fsetattrlist (if fs-macos? "fsetattrlist" "no-fsetattrlist-here") #f (int uptr uptr unsigned-long unsigned-int) int)
(deflibc libc-statvfs "statvfs" #f (uptr uptr) int)
;; extended attributes on a descriptor: macOS's calls take a position and
;; options after the others, which the JDK's native passes as 0
(deflibc libc-fgetxattr-linux (if fs-linux? "fgetxattr" "no-fgetxattr-here") #f (int uptr uptr size_t) ssize_t)
(deflibc libc-fgetxattr-macos (if fs-macos? "fgetxattr" "no-fgetxattr-here") #f (int uptr uptr size_t unsigned-32 int) ssize_t)
(deflibc libc-fsetxattr-linux (if fs-linux? "fsetxattr" "no-fsetxattr-here") #f (int uptr uptr size_t int) int)
(deflibc libc-fsetxattr-macos (if fs-macos? "fsetxattr" "no-fsetxattr-here") #f (int uptr uptr size_t unsigned-32 int) int)
(deflibc libc-fremovexattr-linux (if fs-linux? "fremovexattr" "no-fremovexattr-here") #f (int uptr) int)
(deflibc libc-fremovexattr-macos (if fs-macos? "fremovexattr" "no-fremovexattr-here") #f (int uptr int) int)
(deflibc libc-flistxattr-linux (if fs-linux? "flistxattr" "no-flistxattr-here") #f (int uptr size_t) ssize_t)
(deflibc libc-flistxattr-macos (if fs-macos? "flistxattr" "no-flistxattr-here") #f (int uptr size_t int) ssize_t)
;; Linux's birth time comes from statx, as the JDK's Linux native reads it
(deflibc libc-statx (if fs-linux? "statx" "no-statx-here") #f (int uptr int unsigned-32 uptr) int)
(define fs-statx? (and fs-linux? fs-libc-loaded (foreign-entry? "statx")))

(define (errno) (foreign-ref 'int (libc-errno-location) 0))
;; call thunk again while it fails with EINTR, as the JDK's RESTARTABLE does
(define (restartable thunk)
  (let loop ()
    (let ([r (thunk)])
      (if (and (< r 0) (fx= (errno) 4)) (loop) r))))
(define (errno-clear!) (foreign-set! 'int (libc-errno-location) 0 0))

;; --- calls that may wait, with the GIL given up -----------------------------------
;; A read from a pipe, a terminal or a FIFO, a write to a full pipe and a
;; blocking file lock can wait indefinitely. Holding the GIL through one would
;; stop every other Java thread, so these give it up (gil-released,
;; natives.ss) and go through __collect_safe entries, which let other threads
;; collect while this one waits in C. Such a call touches no Java object and
;; not fs-scratch, which the GIL guards: it has a buffer of its own. Reads and
;; writes on a regular file keep the GIL, since they don't wait on anything.
(define-syntax deflibc-blocking
  (syntax-rules ()
    [(_ id name (arg ...) ret)
     (define id
       (if (and fs-libc-loaded (foreign-entry? name))
           (foreign-procedure __collect_safe name (arg ...) ret)
           (lambda xs (jthrow "java/lang/UnsupportedOperationException" (string-append "libc has no " name)))))]))
(deflibc-blocking libc-read-blocking "read" (int uptr size_t) ssize_t)
(deflibc-blocking libc-write-blocking "write" (int uptr size_t) ssize_t)
(define libc-open-blocking
  (if (and fs-libc-loaded (foreign-entry? "open"))
      (foreign-procedure __collect_safe (__varargs_after 2) "open" (uptr int int) int)
      (lambda xs (jthrow "java/lang/UnsupportedOperationException" "libc has no open"))))
(define libc-fcntl-lock-blocking
  (if (and fs-libc-loaded (foreign-entry? "fcntl"))
      (foreign-procedure __collect_safe (__varargs_after 2) "fcntl" (int int uptr) int)
      (lambda xs (jthrow "java/lang/UnsupportedOperationException" "libc has no fcntl"))))

(define (fd-may-wait? fd) (not (fd-regular? fd)))

;; (call) without the GIL, again while it fails with EINTR; the result, with
;; errno as the call left it, read before the GIL is taken back
(define (released-call call)
  (let ([r+e (gil-released
              (lambda ()
                (let loop ()
                  (let ([r (call)])
                    (if (< r 0)
                        (let ([e (errno)]) (if (fx= e 4) (loop) (cons r e)))
                        (cons r 0))))))])
    (when (< (car r+e) 0) (foreign-set! 'int (libc-errno-location) 0 (cdr r+e)))
    (car r+e)))

;; up to n bytes from fd into a fresh foreign buffer, given to (k buf r)
(define (released-read fd n k)
  (let ([buf (foreign-alloc (fxmax n 1))])
    (guard (e [#t (foreign-free buf) (raise e)])
      (let ([v (k buf (released-call (lambda () (libc-read-blocking fd buf n))))])
        (foreign-free buf)
        v))))
(define (strerror-text e) (libc-strerror e))

(define (need-layout what)
  (unless fs-stat-layout?
    (jthrow "java/lang/UnsupportedOperationException"
            (string-append what " needs macOS, or Linux with glibc 2.33 or newer on arm64 or x86_64 (this is "
                           (symbol->string (machine-type))
                           (if (and fs-layout fs-libc-loaded) ", and its libc doesn't export stat" "")
                           ")"))))

;; a C string at address p as a Java byte[]
(define (cbytes p n)
  (let ([v (make-vector n)])
    (do ([i 0 (fx+ i 1)]) ((fx= i n) (make-jarr "B" v))
      (vector-set! v i (i2b (foreign-ref 'unsigned-8 p i))))))
(define (cstring-length p) (let loop ([i 0]) (if (fx= 0 (foreign-ref 'unsigned-8 p i)) i (loop (fx+ i 1)))))
(define (cstring->bytes p) (cbytes p (cstring-length p)))

;; reusable scratch memory; one thread runs Java at a time (natives.ss)
(define fs-scratch (foreign-alloc 4096))
(define fs-stat-buf (foreign-alloc 256))

;; --- errors --------------------------------------------------------------------------

;; errno is read before anything else can change it
(define (unix-error)
  (let ([e (errno)]) (raise (construct "sun/nio/fs/UnixException" "(I)V" e))))
(define (unix-check r) (if (< r 0) (unix-error) r))
(define (io-error what)
  (let ([e (errno)]) (jthrow "java/io/IOException" (string-append what " (" (strerror-text e) ")"))))
(define (file-not-found path)
  (let ([e (errno)]) (jthrow "java/io/FileNotFoundException" (string-append path " (" (strerror-text e) ")"))))

;; --- file descriptors -------------------------------------------------------------

(define (fdo-fd fdo) (jslot (nn fdo) (jfield-index "java/io/FileDescriptor" "fd")))
(define (fdo-fd-set! fdo fd) (jslot-set! fdo (jfield-index "java/io/FileDescriptor" "fd") fd))
(define (holder-fdo this cls) (jslot this (jfield-index cls "fd")))
(define (holder-fd this cls)
  (let ([fd (fdo-fd (holder-fdo this cls))])
    (if (fx< fd 0) (jthrow "java/io/IOException" "Stream Closed") fd)))

(define (fs-const name)
  (jinit "sun/nio/fs/UnixConstants")
  (top-level-value (string->symbol (string-append "sun/nio/fs/UnixConstants:" name))))

(define seek-set 0)
(define seek-cur 1)
(define seek-end 2)

;; fstat into the scratch buffer; #f on failure, or with no layout table
(define (fd-stat fd)
  (and fs-stat-layout? (if (< (libc-fstat fd fs-stat-buf) 0) #f fs-stat-buf)))
(define (stat-mode buf) (fs-ref buf 'st_mode))
(define (stat-size buf) (fs-ref buf 'st_size))
(define (mode-type m) (fxlogand m #o170000))
(define s-ifreg #o100000)
(define s-ifdir #o040000)
(define (fd-size fd what)
  (if fs-stat-layout?
      (let ([b (fd-stat fd)]) (if b (stat-size b) (io-error what)))
      ;; without struct stat: seek to the end and back
      (let* ([cur (libc-lseek fd 0 seek-cur)] [end (libc-lseek fd 0 seek-end)])
        (when (< end 0) (io-error what))
        (libc-lseek fd cur seek-set)
        end)))
;; a regular file; off macOS, any file that can seek
(define (fd-regular? fd)
  (if fs-stat-layout?
      (let ([b (fd-stat fd)]) (and b (fx= (mode-type (stat-mode b)) s-ifreg)))
      (>= (libc-lseek fd 0 seek-cur) 0)))

;; read up to n bytes from fd into Java byte[] arr at off, through scratch
;; memory; 0 at end of file
;; what the JDK's C natives check before touching a byte[]
(define (check-bounds arr off len)
  (nn arr)
  (when (or (fx< off 0) (fx< len 0) (fx> (fx+ off len) (vector-length (jarr-vec arr))))
    (jthrow "java/lang/IndexOutOfBoundsException" #f)))

(define (fd-read-into fd arr off n)
  (let ([want (fxmin n 4096)]
        [copy (lambda (buf r)
                (when (< r 0) (io-error "Read error"))
                (native->vector! buf 0 (jarr-vec arr) off r)
                r)])
    (if (fd-may-wait? fd)
        (released-read fd want copy)
        (copy fs-scratch (restartable (lambda () (libc-read fd fs-scratch want)))))))

(define (fd-write-from fd arr off n)
  (let* ([v (jarr-vec arr)]
         [wait? (fd-may-wait? fd)]
         [buf (if wait? (foreign-alloc 4096) fs-scratch)])
    (guard (e [#t (when wait? (foreign-free buf)) (raise e)])
      (let loop ([done 0])
        (when (fx< done n)
          (let ([k (fxmin (fx- n done) 4096)])
            (vector->native! v (fx+ off done) buf 0 k)
            (let ([w (if wait?
                         (released-call (lambda () (libc-write-blocking fd buf k)))
                         (restartable (lambda () (libc-write fd buf k))))])
              (when (< w 0) (io-error "Write error"))
              (loop (fx+ done w))))))
      (when wait? (foreign-free buf)))))

;; fds 0-2 stay open: Chez's own ports use them. A failed close is reported,
;; except EINTR, after which the fd is gone anyway.
(define (fd-close fd)
  (when (and (fx> fd 2) (< (libc-close fd) 0) (not (fx= (errno) 4)))
    (io-error "close failed")))

;; open for java.io: FileNotFoundException with the reason, and a directory
;; refused as the JDK refuses it. Opening a FIFO waits for the other end, so
;; that open goes without the GIL, its path a C string of its own; any other
;; keeps it, since releasing it on every open made opens a quarter slower.
;; Without struct stat every open is treated as a FIFO's.
(define s-ififo #o010000)
(define (fifo-mode? r) (or (< r 0) (fx= (mode-type (stat-mode fs-stat-buf)) s-ififo)))
(define (path-fifo? path)
  (or (not fs-stat-layout?) (fifo-mode? (libc-stat-string path fs-stat-buf))))
(define (cpath-fifo? cpath)
  (or (not fs-stat-layout?) (fifo-mode? (libc-stat cpath fs-stat-buf))))
(define (string->c-string s)
  (let* ([bv (string->utf8 s)] [n (bytevector-length bv)] [p (foreign-alloc (fx+ n 1))])
    (do ([i 0 (fx+ i 1)]) ((fx= i n)) (#3%foreign-set! 'unsigned-8 p i (bytevector-u8-ref bv i)))
    (foreign-set! 'unsigned-8 p n 0)
    p))
(define (java-io-open path flags)
  (let ([fd (if (path-fifo? path)
                (let* ([cpath (string->c-string path)]
                       [r (released-call (lambda () (libc-open-blocking cpath flags #o666)))])
                  (foreign-free cpath)
                  r)
                (restartable (lambda () (libc-open-string path flags #o666))))])
    (when (< fd 0) (file-not-found path))
    (when (file-directory? path)
      (libc-close fd)
      (jthrow "java/io/FileNotFoundException" (string-append path " (Is a directory)")))
    fd))

;; --- FileInputStream -----------------------------------------------------------------
;; fd 0 reads through Chez's standard input port.

(define fis "java/io/FileInputStream")

;; as Console_md.c: both stdin and stdout are terminals
(defnative "java/io/Console.istty()Z" ()
  (if (and (fx= 1 (libc-isatty 0)) (fx= 1 (libc-isatty 1))) 1 0))

(defnative "java/io/FileInputStream.open0(Ljava/lang/String;)V" (this name)
  (fdo-fd-set! (holder-fdo this fis) (java-io-open (jstring->scheme (nn name)) (fs-const "O_RDONLY"))))

;; Standard input is read through one Chez port, without the GIL (a console
;; read waits for the user), one reader at a time since the port has no lock
;; of its own. One port for the process: (standard-input-port) makes a new
;; buffered port per call, and bytes buffered in a dropped one were lost.
(define stdin-port (standard-input-port))
(define stdin-mutex (make-mutex))
(define (stdin-read proc)
  (gil-released (lambda () (with-mutex stdin-mutex (proc stdin-port)))))

(defnative "java/io/FileInputStream.read0()I" (this)
  (let ([fd (holder-fd this fis)])
    (if (fx= fd 0)
        (let ([b (stdin-read get-u8)]) (if (eof-object? b) -1 b))
        (let ([one (lambda (buf r)
                     (cond [(< r 0) (io-error "Read error")] [(fx= r 0) -1] [else (foreign-ref 'unsigned-8 buf 0)]))])
          (if (fd-may-wait? fd)
              (released-read fd 1 one)
              (one fs-scratch (restartable (lambda () (libc-read fd fs-scratch 1)))))))))

(defnative "java/io/FileInputStream.readBytes([BII)I" (this bytes off len)
  (check-bounds bytes off len)
  (let ([fd (holder-fd this fis)])
    (cond
      [(fx= len 0) 0]
      [(fx= fd 0)
       ;; whatever is available, up to len, so a console read returns a line
       (let* ([bv (make-bytevector len)] [n (stdin-read (lambda (p) (get-bytevector-some! p bv 0 len)))])
         (if (eof-object? n)
             -1
             (let ([v (jarr-vec bytes)])
               (do ([i 0 (fx+ i 1)]) ((fx= i n) n)
                 (vector-set! v (fx+ off i) (i2b (bytevector-u8-ref bv i)))))))]
      [else (let ([r (fd-read-into fd bytes off len)]) (if (fx= r 0) -1 r))])))

;; Bytes a read can take without waiting, as io_util_md.c handleAvailable
;; counts them: ioctl FIONREAD for a terminal or other character device, a
;; pipe or a socket, a regular file's size past the position. Anything else,
;; or a failed ioctl, is 0. Standard input adds what its port has buffered.
(define s-ifchr #o020000)
(define s-ifsock #o140000)
(define (fd-fionread fd)
  (let ([buf (foreign-alloc 4)])
    (foreign-set! 'int buf 0 0)
    (let* ([r (libc-ioctl fd (net-const 'FIONREAD) buf)] [n (foreign-ref 'int buf 0)])
      (foreign-free buf)
      (if (< r 0) 0 n))))
(define (fd-available fd what)
  (let ([type (and fs-stat-layout? (let ([b (fd-stat fd)]) (and b (mode-type (stat-mode b)))))])
    (cond
      [(memv type (list s-ifchr s-ififo s-ifsock)) (fd-fionread fd)]
      [(fd-regular? fd) (max 0 (min #x7fffffff (- (fd-size fd what) (libc-lseek fd 0 seek-cur))))]
      [else 0])))
(defnative "java/io/FileInputStream.available0()I" (this)
  (let ([fd (holder-fd this fis)])
    (if (fx= fd 0)
        ;; read without the stdin mutex: a reader holds it while it waits
        ;; for input, so taking it here could wait as long
        (min #x7fffffff (+ (port-input-count stdin-port) (fd-available 0 "Available error")))
        (fd-available fd "Available error"))))
;; as the JDK: seek from the current position, past the end if asked
(defnative "java/io/FileInputStream.skip0(J)J" (this n)
  (let* ([fd (holder-fd this fis)] [cur (libc-lseek fd 0 seek-cur)])
    (when (< cur 0) (io-error "Seek error"))
    (let ([end (libc-lseek fd n seek-cur)])
      (when (< end 0) (io-error "Seek error"))
      (- end cur))))
(defnative "java/io/FileInputStream.length0()J" (this) (fd-size (holder-fd this fis) "Length error"))
(defnative "java/io/FileInputStream.position0()J" (this) (libc-lseek (holder-fd this fis) 0 seek-cur))
(defnative "java/io/FileInputStream.isRegularFile0(Ljava/io/FileDescriptor;)Z" (this fdo)
  (if (fd-regular? (fdo-fd fdo)) 1 0))

(defnative "java/io/FileDescriptor.close0()V" (this)
  (let ([fd (fdo-fd this)])
    (fd-close fd)
    (fdo-fd-set! this -1)))

;; --- FileOutputStream ------------------------------------------------------------
;; fds 1 and 2 write through Chez's ports (natives.ss).

(define fos "java/io/FileOutputStream")

;; as io_util_md.c's fileOpen, the FileDescriptor remembers O_APPEND: a
;; FileChannel on it reports the file's size as its position
(defnative "java/io/FileOutputStream.open0(Ljava/lang/String;Z)V" (this name append)
  (let ([fdo (holder-fdo this fos)])
    (fdo-fd-set! fdo
                 (java-io-open (jstring->scheme (nn name))
                               (fxlogor (fs-const "O_WRONLY") (fs-const "O_CREAT")
                                        (if (eqv? append 1) (fs-const "O_APPEND") (fs-const "O_TRUNC")))))
    (jslot-set! fdo (jfield-index "java/io/FileDescriptor" "append") (if (eqv? append 1) 1 0))))

(define (console-fd? fd) (or (fx= fd 1) (fx= fd 2)))

(defnative "java/io/FileOutputStream.writeBytes([BIIZ)V" (this bytes off len append)
  (check-bounds bytes off len)
  ;; io_util.c's writeBytes returns on an empty write before it looks at the
  ;; fd, so writing nothing to a closed stream is no error
  (unless (fxzero? len)
   (let ([fd (holder-fd this fos)])
    (if (console-fd? fd) (console-write-bytes fd bytes off len) (fd-write-from fd bytes off len)))))
(defnative "java/io/FileOutputStream.write(IZ)V" (this b append)
  (let ([fd (holder-fd this fos)])
    (if (console-fd? fd)
        (console-write-byte fd b)
        (fd-write-from fd (make-jarr "B" (vector b)) 0 1))))

;; --- RandomAccessFile ----------------------------------------------------------------
;; mode bits are RandomAccessFile's own: 1 read, 2 read-write, 4 sync, 8 dsync

(define raf "java/io/RandomAccessFile")

(defnative "java/io/RandomAccessFile.open0(Ljava/lang/String;I)V" (this name mode)
  (fdo-fd-set! (holder-fdo this raf)
               (java-io-open (jstring->scheme (nn name))
                             (fxlogor
                              (if (fxpositive? (fxlogand mode 2))
                                  (fxlogor (fs-const "O_RDWR") (fs-const "O_CREAT"))
                                  (fs-const "O_RDONLY"))
                              (if (fxpositive? (fxlogand mode 4)) (fs-const "O_SYNC") 0)
                              (if (fxpositive? (fxlogand mode 8)) (fs-const "O_DSYNC") 0)))))
(defnative "java/io/RandomAccessFile.read0()I" (this)
  (let ([fd (holder-fd this raf)]
        [one (lambda (buf r)
               (cond [(< r 0) (io-error "Read error")] [(fx= r 0) -1] [else (foreign-ref 'unsigned-8 buf 0)]))])
    (if (fd-may-wait? fd)
        (released-read fd 1 one)
        (one fs-scratch (restartable (lambda () (libc-read fd fs-scratch 1)))))))
(defnative "java/io/RandomAccessFile.readBytes0([BII)I" (this bytes off len)
  (check-bounds bytes off len)
  (if (fx= len 0) 0 (let ([r (fd-read-into (holder-fd this raf) bytes off len)]) (if (fx= r 0) -1 r))))
(defnative "java/io/RandomAccessFile.write0(I)V" (this b)
  (fd-write-from (holder-fd this raf) (make-jarr "B" (vector b)) 0 1))
(defnative "java/io/RandomAccessFile.writeBytes0([BII)V" (this bytes off len)
  (check-bounds bytes off len)
  (fd-write-from (holder-fd this raf) bytes off len))
(defnative "java/io/RandomAccessFile.getFilePointer()J" (this)
  (let ([r (libc-lseek (holder-fd this raf) 0 seek-cur)]) (if (< r 0) (io-error "Seek failed") r)))
(defnative "java/io/RandomAccessFile.seek0(J)V" (this pos)
  (when (< (libc-lseek (holder-fd this raf) pos seek-set) 0) (io-error "Seek failed")))
(defnative "java/io/RandomAccessFile.length0()J" (this) (fd-size (holder-fd this raf) "Length failed"))
;; as the JDK: the file pointer stays put unless it was past the new end
(defnative "java/io/RandomAccessFile.setLength0(J)V" (this n)
  (let* ([fd (holder-fd this raf)] [cur (libc-lseek fd 0 seek-cur)])
    (when (< (libc-ftruncate fd n) 0) (io-error "setLength failed"))
    (when (> cur n) (libc-lseek fd n seek-set))))

;; --- java.io.UnixFileSystem: changes and the queries libc answers best ---------------

(define (io-file-path f) (jstring->scheme (jslot (nn f) (jfield-index "java/io/File" "path"))))
(define (ok? r) (if (fx= r 0) 1 0))

;; realpath of the longest prefix that exists, then the rest with . and ..
;; folded, as the JDK's canonicalize_md.c does
(define (canonical-path p)
  (let ([r (libc-realpath-string p 0)])
    (if (not (eqv? r 0))
        (let ([s (utf8->string (bytevector-copy-from-c r))]) (foreign-free r) s)
        (let* ([slash (let loop ([i (fx- (string-length p) 1)]) (cond [(fx< i 0) #f] [(char=? (string-ref p i) #\/) i] [else (loop (fx- i 1))]))])
          (if (or (not slash) (fx= slash 0))
              (fold-dots p)
              (let ([head (canonical-path (substring p 0 slash))] [tail (substring p (fx+ slash 1) (string-length p))])
                (fold-dots (string-append (if (string=? head "/") "" head) "/" tail))))))))
(define (bytevector-copy-from-c p)
  (let* ([n (cstring-length p)] [bv (make-bytevector n)])
    (do ([i 0 (fx+ i 1)]) ((fx= i n) bv) (bytevector-u8-set! bv i (#3%foreign-ref 'unsigned-8 p i)))))
(define (fold-dots p)
  (let loop ([parts (split-slashes p)] [acc '()])
    (cond [(null? parts) (string-append "/" (join-slashes (reverse acc)))]
          [(or (string=? (car parts) "") (string=? (car parts) ".")) (loop (cdr parts) acc)]
          [(string=? (car parts) "..") (loop (cdr parts) (if (pair? acc) (cdr acc) acc))]
          [else (loop (cdr parts) (cons (car parts) acc))])))
(define (split-slashes s)
  (let loop ([i 0] [start 0] [acc '()])
    (cond [(fx= i (string-length s)) (reverse (cons (substring s start i) acc))]
          [(char=? (string-ref s i) #\/) (loop (fx+ i 1) (fx+ i 1) (cons (substring s start i) acc))]
          [else (loop (fx+ i 1) start acc)])))
(define (join-slashes parts)
  (if (null? parts) "" (fold-left (lambda (a b) (string-append a "/" b)) (car parts) (cdr parts))))

(defnative "java/io/UnixFileSystem.canonicalize0(Ljava/lang/String;)Ljava/lang/String;" (this p)
  (jstring (canonical-path (jstring->scheme (nn p)))))
(defnative "java/io/UnixFileSystem.checkAccess0(Ljava/io/File;I)Z" (this f access)
  (ok? (libc-access-string (io-file-path f) access)))
(defnative "java/io/UnixFileSystem.setPermission0(Ljava/io/File;IZZ)Z" (this f access enable owner-only)
  (need-layout "File.setPermission")
  (let ([p (io-file-path f)])
    (if (< (libc-stat-string p fs-stat-buf) 0)
        0
        (let* ([bits (if (eqv? owner-only 1) (fxsll access 6) (fxlogor (fxsll access 6) (fxsll access 3) access))]
               [m (fxlogand (stat-mode fs-stat-buf) #o7777)])
          (ok? (libc-chmod-string p (if (eqv? enable 1) (fxlogor m bits) (fxlogand m (fxlognot bits)))))))))
(defnative "java/io/UnixFileSystem.setReadOnly0(Ljava/io/File;)Z" (this f)
  (need-layout "File.setReadOnly")
  (let ([p (io-file-path f)])
    (if (< (libc-stat-string p fs-stat-buf) 0)
        0
        (ok? (libc-chmod-string p (fxlogand (stat-mode fs-stat-buf) #o7555))))))
(defnative "java/io/UnixFileSystem.createFileExclusively0(Ljava/lang/String;)Z" (this path)
  (let* ([p (jstring->scheme (nn path))]
         [fd (libc-open-string p (fxlogor (fs-const "O_RDWR") (fs-const "O_CREAT") (fs-const "O_EXCL")) #o666)])
    (cond [(fx>= fd 0) (libc-close fd) 1]
          [(fx= (errno) (fs-const "EEXIST")) 0]
          [else (io-error p)])))
(defnative "java/io/UnixFileSystem.delete0(Ljava/io/File;)Z" (this f) (ok? (libc-remove-string (io-file-path f))))
(defnative "java/io/UnixFileSystem.createDirectory0(Ljava/io/File;)Z" (this f) (ok? (libc-mkdir-string (io-file-path f) #o777)))
(defnative "java/io/UnixFileSystem.rename0(Ljava/io/File;Ljava/io/File;)Z" (this a b)
  (ok? (libc-rename-string (io-file-path a) (io-file-path b))))
;; atime left alone (UTIME_OMIT), mtime set
(defnative "java/io/UnixFileSystem.setLastModifiedTime0(Ljava/io/File;J)Z" (this f ms)
  (put-timespec! fs-scratch 0 0 (if fs-macos? -2 #x3ffffffe))
  (put-timespec! fs-scratch 16 (div ms 1000) (* (mod ms 1000) 1000000))
  (ok? (libc-utimensat-string (fs-const "AT_FDCWD") (io-file-path f) fs-scratch 0)))
;; t: 0 total, 1 free, 2 usable
(defnative "java/io/UnixFileSystem.getSpace0(Ljava/io/File;I)J" (this f t)
  (need-layout "File.getTotalSpace")
  (let ([path (io-file-path f)] [b (foreign-alloc 2400)])
    (let ([r (if (< (libc-space-string path b) 0)
                 0
                 (* (fs-ref b 'space-unit)
                    (fs-ref b (case t [(0) 'space-total] [(1) 'space-free] [else 'space-usable]))))])
      (foreign-free b)
      r)))
(defnative "java/io/UnixFileSystem.getNameMax0(Ljava/lang/String;)J" (this path)
  ;; _PC_NAME_MAX is 4 on macOS, 3 on Linux
  (let ([r (libc-pathconf-string (jstring->scheme (nn path)) (if fs-macos? 4 3))]) (if (< r 0) 255 r)))

(define (put-timespec! buf off sec nsec)
  (foreign-set! 'integer-64 buf off sec)
  (foreign-set! 'integer-64 buf (fx+ off 8) nsec))

;; --- sun.nio.fs.UnixNativeDispatcher ---------------------------------------------------

(define und "sun/nio/fs/UnixNativeDispatcher.")
(define-syntax defund
  (syntax-rules () [(_ sig args body ...) (defnative (string-append und sig) args body ...)]))

;; capabilities: birth times where struct stat has them (macOS), no *at
;; calls, no extended attributes advertised (the xattr natives below
;; serve the copy path). Without a layout table nothing in
;; java.nio.file can work, so it fails here, once, rather than in every stat.
(defund "init()I" ()
  (need-layout "java.nio.file")
  (if (or (fs-has? 'st_birthtime_sec) fs-statx?) (fxsll 1 16) 0))
(defund "fchmodatNoFollowSupported0()Z" () 0)
;; LinuxFileSystem copies a file's extended attributes on every copy with
;; COPY_ATTRIBUTES, whatever init answered
(defund "fgetxattr0(IJJI)I" (fd name value n)
  (unix-check (if fs-macos? (libc-fgetxattr-macos fd name value n 0 0) (libc-fgetxattr-linux fd name value n))))
(defund "fsetxattr0(IJJI)V" (fd name value n)
  (unix-check (if fs-macos? (libc-fsetxattr-macos fd name value n 0 0) (libc-fsetxattr-linux fd name value n 0)))
  (void))
(defund "fremovexattr0(IJ)V" (fd name)
  (unix-check (if fs-macos? (libc-fremovexattr-macos fd name 0) (libc-fremovexattr-linux fd name)))
  (void))
(defund "flistxattr(IJI)I" (fd list n)
  (unix-check (if fs-macos? (libc-flistxattr-macos fd list n 0) (libc-flistxattr-linux fd list n))))

(defund "getcwd()[B" ()
  (if (eqv? 0 (libc-getcwd fs-scratch 4096)) (unix-error) (cstring->bytes fs-scratch)))
(defund "strerror(I)[B" (e)
  (let ([s (string->utf8 (strerror-text e))])
    (make-jarr "B" (list->vector (map i2b (bytevector->u8-list s))))))
(defund "dup(I)I" (fd) (unix-check (libc-dup fd)))
;; path is the caller's native memory; a FIFO's open waits for the other end
(defund "open0(JII)I" (path flags mode)
  (unix-check (if (cpath-fifo? path)
                  (released-call (lambda () (libc-open-blocking path flags mode)))
                  (restartable (lambda () (libc-open path flags mode))))))
(defund "close0(I)V" (fd)
  (when (and (fx> fd 2) (< (libc-close fd) 0) (not (fx= (errno) 4))) (unix-error)))
;; buf is the caller's native memory, which stays put while the GIL is away
(defund "read0(IJI)I" (fd buf n)
  (unix-check (if (fd-may-wait? fd)
                  (released-call (lambda () (libc-read-blocking fd buf n)))
                  (restartable (lambda () (libc-read fd buf n))))))
(defund "write0(IJI)I" (fd buf n)
  (console-flush fd)
  (unix-check (if (fd-may-wait? fd)
                  (released-call (lambda () (libc-write-blocking fd buf n)))
                  (restartable (lambda () (libc-write fd buf n))))))
(defund "link0(JJ)V" (a b) (unix-check (libc-link a b)))
(defund "unlink0(J)V" (p) (unix-check (libc-unlink p)))
(defund "rename0(JJ)V" (a b) (unix-check (libc-rename a b)))
(defund "mkdir0(JI)V" (p mode) (unix-check (libc-mkdir p mode)))
(defund "rmdir0(J)V" (p) (unix-check (libc-rmdir p)))
(defund "symlink0(JJ)V" (target link) (unix-check (libc-symlink target link)))
(defund "chmod0(JI)V" (p mode) (unix-check (libc-chmod p mode)))
(defund "fchmod0(II)V" (fd mode) (unix-check (libc-fchmod fd mode)))
(defund "chown0(JII)V" (p uid gid) (unix-check (libc-chown p uid gid)))
(defund "lchown0(JII)V" (p uid gid) (unix-check (libc-lchown p uid gid)))
(defund "fchown0(III)V" (fd uid gid) (unix-check (libc-fchown fd uid gid)))
(defund "access0(JI)I" (p amode) (if (< (libc-access p amode) 0) (errno) 0))
(defund "readlink0(J)[B" (p)
  (let ([n (libc-readlink p fs-scratch 4096)])
    (if (< n 0) (unix-error) (cbytes fs-scratch n))))
(defund "realpath0(J)[B" (p)
  (let ([r (libc-realpath p 0)])
    (if (eqv? r 0) (unix-error) (let ([b (cstring->bytes r)]) (foreign-free r) b))))

;; times are nanoseconds since the epoch
(define (nanos->timespec! buf off t) (put-timespec! buf off (div t 1000000000) (mod t 1000000000)))
(defund "utimensat0(IJJJI)V" (fd p t0 t1 flags)
  (nanos->timespec! fs-scratch 0 t0)
  (nanos->timespec! fs-scratch 16 t1)
  (unix-check (libc-utimensat fd p fs-scratch flags)))
(defund "futimens0(IJJ)V" (fd t0 t1)
  (nanos->timespec! fs-scratch 0 t0)
  (nanos->timespec! fs-scratch 16 t1)
  (unix-check (libc-futimens fd fs-scratch)))

(define ufa "sun/nio/fs/UnixFileAttributes")
;; struct statx is the kernel's own, the same on every architecture
;; (ci/linux/layout.c's statx section): stx_mask 0, stx_btime 80 and 88
(define statx-btime #x800)
(define at-fdcwd -100)
(define at-symlink-nofollow #x100)
(define at-empty-path #x1000)
(define fs-statx-buf (and fs-statx? (foreign-alloc 256)))
(define fs-empty-cstr (let ([p (foreign-alloc 1)]) (foreign-set! 'unsigned-8 p 0 0) p))
;; Linux: a birth time where the file system keeps one; dirfd/path/flags as statx takes them
(define (fill-birthtime! attrs dirfd path flags)
  (when (and fs-statx? (fx= 0 (libc-statx dirfd path flags statx-btime fs-statx-buf))
             (fxpositive? (fxlogand (foreign-ref 'unsigned-32 fs-statx-buf 0) statx-btime)))
    (jslot-set! attrs (jfield-index ufa "st_birthtime_sec") (foreign-ref 'integer-64 fs-statx-buf 80))
    (jslot-set! attrs (jfield-index ufa "st_birthtime_nsec") (foreign-ref 'unsigned-32 fs-statx-buf 88))
    (jslot-set! attrs (jfield-index ufa "birthtime_available") 1)))
(define (fill-attrs! attrs buf)
  (let ([set (lambda (f v) (jslot-set! attrs (jfield-index ufa f) v))]
        [get (lambda (f) (fs-ref buf f))])
    (set "st_dev" (i64 (get 'st_dev)))
    (set "st_mode" (i32 (get 'st_mode)))
    (set "st_nlink" (i32 (get 'st_nlink)))
    (set "st_ino" (i64 (get 'st_ino)))
    (set "st_uid" (i32 (get 'st_uid)))
    (set "st_gid" (i32 (get 'st_gid)))
    (set "st_rdev" (i64 (get 'st_rdev)))
    (set "st_atime_sec" (get 'st_atime_sec)) (set "st_atime_nsec" (get 'st_atime_nsec))
    (set "st_mtime_sec" (get 'st_mtime_sec)) (set "st_mtime_nsec" (get 'st_mtime_nsec))
    (set "st_ctime_sec" (get 'st_ctime_sec)) (set "st_ctime_nsec" (get 'st_ctime_nsec))
    ;; only macOS's struct stat carries a birth time, and the JDK's macOS
    ;; native takes its seconds only: creationTime has no fraction there
    (when (fs-has? 'st_birthtime_sec)
      (set "st_birthtime_sec" (get 'st_birthtime_sec))
      (set "birthtime_available" 1))
    (set "st_size" (get 'st_size))))

;; stat0 answers an errno rather than throwing
(defund "stat0(JLsun/nio/fs/UnixFileAttributes;)I" (p attrs)
  (need-layout "stat")
  (if (< (libc-stat p fs-stat-buf) 0)
      (errno)
      (begin (fill-attrs! attrs fs-stat-buf) (fill-birthtime! attrs at-fdcwd p 0) 0)))
(defund "lstat0(JLsun/nio/fs/UnixFileAttributes;)V" (p attrs)
  (need-layout "lstat")
  (unix-check (libc-lstat p fs-stat-buf))
  (fill-attrs! attrs fs-stat-buf)
  (fill-birthtime! attrs at-fdcwd p at-symlink-nofollow))
(defund "fstat0(ILsun/nio/fs/UnixFileAttributes;)V" (fd attrs)
  (need-layout "fstat")
  (unix-check (libc-fstat fd fs-stat-buf))
  (fill-attrs! attrs fs-stat-buf)
  (fill-birthtime! attrs fd fs-empty-cstr at-empty-path))

;; the file system's block size and counts (Files.copy sizes its buffer by it)
(define ufsa "sun/nio/fs/UnixFileStoreAttributes")
(defund "statvfs0(JLsun/nio/fs/UnixFileStoreAttributes;)V" (p attrs)
  (need-layout "statvfs")
  (unix-check (libc-statvfs p fs-stat-buf))
  (for-each (lambda (f) (jslot-set! attrs (jfield-index ufsa (car f)) (i64 (fs-ref fs-stat-buf (cdr f)))))
            '(("f_frsize" . vfs-frsize) ("f_blocks" . vfs-blocks) ("f_bfree" . vfs-bfree) ("f_bavail" . vfs-bavail))))

(defund "opendir0(J)J" (p)
  (let ([d (libc-opendir p)]) (if (eqv? d 0) (unix-error) d)))
(defund "closedir(J)V" (d) (unix-check (libc-closedir d)))
;; the next name, or null at the end; "." and ".." are left to the caller
(defund "readdir0(J)[B" (d)
  (need-layout "readdir")
  (errno-clear!)
  (let ([e (libc-readdir d)])
    (if (eqv? e 0)
        (if (fx= (errno) 0) #f (unix-error))
        (let ([name (+ e (fs-off 'dirent-name))])
          (cbytes name (if (fs-has? 'dirent-namlen) (fs-ref e 'dirent-namlen) (cstring-length name)))))))

;; a user's or group's name: pw_name and gr_name are the first field
(define (entry-name p)
  (if (eqv? p 0)
      (let ([e (errno)]) (raise (construct "sun/nio/fs/UnixException" "(I)V" (if (fx= e 0) (fs-const "ENOENT") e))))
      (cstring->bytes (foreign-ref 'uptr p 0))))
(defund "getpwuid(I)[B" (uid) (errno-clear!) (entry-name (libc-getpwuid uid)))
(defund "getgrgid(I)[B" (gid) (errno-clear!) (entry-name (libc-getgrgid gid)))

(defnative "sun/nio/fs/MacOSXNativeDispatcher.normalizepath([CI)[C" (path form) path)

;; clonefile throws on failure: BsdFileSystem.clone turns ENOTSUP, EXDEV and
;; ENOTDIR into a fallback copy, and anything else into an IOException
(defnative "sun/nio/fs/BsdNativeDispatcher.clonefile0(JJI)I" (src dst flags)
  (unix-check (libc-clonefile src dst flags))
  0)
;; setattrlist(2) and fsetattrlist(2) for the times macOS sets that way,
;; creation time included, as BsdNativeDispatcher.c lays them out: a struct
;; attrlist (bitmapcount 5, then the common attributes, 24 bytes) and one
;; timespec per attribute asked for, creation then modification then access
;; time, seconds and nanoseconds split as C's / and % split them
(define attr-cmn-crtime #x200)
(define attr-cmn-modtime #x400)
(define attr-cmn-acctime #x1000)
(define (attrlist-call call commonattr times options)
  (let ([al (foreign-alloc 24)] [buf (foreign-alloc 48)])
    (do ([i 0 (fx+ i 1)]) ((fx= i 24)) (foreign-set! 'unsigned-8 al i 0))
    (foreign-set! 'unsigned-16 al 0 5)
    (foreign-set! 'unsigned-32 al 4 commonattr)
    (let ([n (fold-left (lambda (n bit t)
                          (if (fxzero? (fxlogand commonattr bit))
                              n
                              (begin (put-timespec! buf (fx* n 16) (quotient t 1000000000) (remainder t 1000000000))
                                     (fx+ n 1))))
                        0
                        (list attr-cmn-crtime attr-cmn-modtime attr-cmn-acctime)
                        times)])
      (let* ([r (call al buf (fx* n 16) options)] [e (errno)])
        (foreign-free al)
        (foreign-free buf)
        (when (< r 0) (raise (construct "sun/nio/fs/UnixException" "(I)V" e)))))))
(defnative "sun/nio/fs/BsdNativeDispatcher.setattrlist0(JIJJJJ)V" (path commonattr mod acc create options)
  (attrlist-call (lambda (l b n o) (libc-setattrlist path l b n o)) commonattr (list create mod acc) options))
(defnative "sun/nio/fs/BsdNativeDispatcher.fsetattrlist0(IIJJJJ)V" (fd commonattr mod acc create options)
  (attrlist-call (lambda (l b n o) (libc-fsetattrlist fd l b n o)) commonattr (list create mod acc) options))
;; a plain read/write loop; with a pipe or FIFO on either side it runs
;; without the GIL, in a buffer of its own
(define (copy-fd-to-fd dst src)
  (let* ([wait? (or (fd-may-wait? src) (fd-may-wait? dst))]
         [buf (if wait? (foreign-alloc 4096) fs-scratch)]
         [rd (if wait?
                 (lambda () (released-call (lambda () (libc-read-blocking src buf 4096))))
                 (lambda () (libc-read src buf 4096)))]
         [wr (if wait?
                 (lambda (p k) (released-call (lambda () (libc-write-blocking dst p k))))
                 (lambda (p k) (libc-write dst p k)))])
    (guard (e [#t (when wait? (foreign-free buf)) (raise e)])
      (let loop ()
        (let ([n (rd)])
          (cond [(< n 0) (unix-error)]
                [(> n 0)
                 (let wloop ([done 0])
                   (when (< done n)
                     (let ([w (wr (+ buf done) (- n done))])
                       (when (< w 0) (unix-error))
                       (wloop (+ done w)))))
                 (loop)])))
      (when wait? (foreign-free buf)))))
(defnative "sun/nio/fs/BsdFileSystem.directCopy0(IIJ)I" (dst src cancel) (copy-fd-to-fd dst src) 0)
(defnative "sun/nio/fs/UnixFileSystem.bufferedCopy0(IIJIJ)V" (dst src buf size cancel) (copy-fd-to-fd dst src))

;; --- mounted file systems: Files.getFileStore, FileSystem.getFileStores ----------

;; a UnixMountEntry's byte[] fields, as the JDK's natives fill them
(define (mount-entry-set! entry name dir fstype opts)
  (for-each (lambda (f v) (jslot-set! entry (jfield-index "sun/nio/fs/UnixMountEntry" f) v))
            '("name" "dir" "fstype" "opts")
            (list name dir fstype opts)))

;; macOS: getfsstat's array of struct statfs (64-bit inode form, 2168 bytes:
;; f_fstypename at 72, f_mntonname at 88, f_mntfromname at 1112, f_flags at
;; 64), walked by an iterator the Java side holds as a long. Options are
;; "ro" for a read-only mount and empty otherwise, as the JDK's
;; BsdNativeDispatcher.c makes them; dev stays 0.
(define statfs-size 2168)
(deflibc libc-getfsstat (if (memq (machine-type) '(ta6osx a6osx)) "getfsstat$INODE64" "getfsstat") #f (uptr int int) int)
(deflibc libc-statfs (if (memq (machine-type) '(ta6osx a6osx)) "statfs$INODE64" "statfs") #f (uptr uptr) int)
(define fsstat-iters (make-eqv-hashtable))
(define fsstat-next-id 1)
(defnative "sun/nio/fs/BsdNativeDispatcher.getfsstat()J" ()
  (let* ([n (unix-check (libc-getfsstat 0 0 2))]          ; MNT_NOWAIT
         [buf (foreign-alloc (* (max n 1) statfs-size))]
         [got (libc-getfsstat buf (* (max n 1) statfs-size) 2)])
    (when (< got 0) (let ([e (errno)]) (foreign-free buf) (raise (construct "sun/nio/fs/UnixException" "(I)V" e))))
    (let ([id fsstat-next-id])
      (set! fsstat-next-id (+ id 1))
      (hashtable-set! fsstat-iters id (vector buf got 0))
      id)))
(defnative "sun/nio/fs/BsdNativeDispatcher.fsstatEntry(JLsun/nio/fs/UnixMountEntry;)I" (iter entry)
  (let ([it (hashtable-ref fsstat-iters iter #f)])
    (if (or (not it) (fx>= (vector-ref it 2) (vector-ref it 1)))
        -1
        (let ([p (+ (vector-ref it 0) (* (vector-ref it 2) statfs-size))])
          (vector-set! it 2 (fx+ (vector-ref it 2) 1))
          (mount-entry-set! entry (cstring->bytes (+ p 1112)) (cstring->bytes (+ p 88)) (cstring->bytes (+ p 72))
                            (make-jarr "B" (if (odd? (foreign-ref 'unsigned-32 p 64)) (vector 114 111) (vector)))) ; MNT_RDONLY: "ro"
          0))))
(defnative "sun/nio/fs/BsdNativeDispatcher.endfsstat(J)V" (iter)
  (let ([it (hashtable-ref fsstat-iters iter #f)])
    (when it (foreign-free (vector-ref it 0)) (hashtable-delete! fsstat-iters iter))))
;; statfs's f_mntonname: the directory the file's file system is mounted on
(defnative "sun/nio/fs/BsdNativeDispatcher.getmntonname0(J)[B" (path)
  (let ([buf (foreign-alloc statfs-size)])
    (if (< (libc-statfs path buf) 0)
        (let ([e (errno)]) (foreign-free buf) (raise (construct "sun/nio/fs/UnixException" "(I)V" e)))
        (let ([dir (cstring->bytes (+ buf 88))]) (foreign-free buf) dir))))

;; Linux: a mount table (/proc/mounts, /etc/mtab) through setmntent and
;; getmntent_r, whose struct mntent starts with four char pointers (fsname,
;; dir, type, opts); getlinelen and rewind are UnixNativeDispatcher's, as
;; LinuxFileSystem.getMountEntries sizes its buffer by the longest line
(deflibc libc-setmntent "setmntent" #f (uptr uptr) uptr)
(deflibc libc-getmntent-r "getmntent_r" #f (uptr uptr uptr int) uptr)
(deflibc libc-endmntent "endmntent" #f (uptr) int)
(deflibc libc-getline "getline" #f (uptr uptr uptr) ssize_t)
(deflibc libc-rewind "rewind" #f (uptr) void)
(deflibc libc-feof "feof" #f (uptr) int)
(deflibc libc-ferror "ferror" #f (uptr) int)
(deflibc libc-free "free" #f (uptr) void)
(defnative "sun/nio/fs/LinuxNativeDispatcher.setmntent0(JJ)J" (path type)
  (let ([fp (libc-setmntent path type)])
    (if (= fp 0) (unix-error) fp)))
(defnative "sun/nio/fs/LinuxNativeDispatcher.getmntent0(JLsun/nio/fs/UnixMountEntry;JI)I" (fp entry buf len)
  (let* ([ent (foreign-alloc 40)] [m (libc-getmntent-r fp ent buf len)])
    (if (= m 0)
        (begin (foreign-free ent) -1)
        (let ([field (lambda (i) (cstring->bytes (foreign-ref 'uptr m (* 8 i))))])
          (mount-entry-set! entry (field 0) (field 1) (field 2) (field 3))
          (foreign-free ent)
          0))))
(defnative "sun/nio/fs/LinuxNativeDispatcher.endmntent(J)V" (fp) (libc-endmntent fp) (void))
(defund "getlinelen(J)I" (fp)
  (let ([cell (foreign-alloc 16)])
    (foreign-set! 'uptr cell 0 0)
    (foreign-set! 'uptr cell 8 0)
    (let* ([r (libc-getline cell (+ cell 8) fp)] [e (errno)])
      (libc-free (foreign-ref 'uptr cell 0))
      (foreign-free cell)
      (cond [(not (fx= 0 (libc-feof fp))) -1]
            [(< r 0) (raise (construct "sun/nio/fs/UnixException" "(I)V" e))]
            [else r]))))
(defund "rewind(J)V" (fp)
  (libc-rewind fp)
  (unless (fx= 0 (libc-ferror fp)) (unix-error)))

;; Chez's buffered console output goes out before a raw write to fd 1 or 2
(define (console-flush fd)
  (when (or (fx= fd 1) (fx= fd 2))
    (flush-output-port (current-output-port))
    (flush-output-port (current-error-port))))

;; --- FileChannel: sun.nio.ch ---------------------------------------------------------------
;; IOStatus: -1 end of file, -2 unavailable, -3 interrupted

(define ufd "sun/nio/ch/UnixFileDispatcherImpl.")
(define-syntax defufd
  (syntax-rules () [(_ sig args body ...) (defnative (string-append ufd sig) args body ...)]))

(define (io-status r what)
  (cond [(fx> r 0) r]
        [(fx= r 0) -1]
        [else (let ([e (errno)])
                (cond [(fx= e (fs-const "EAGAIN")) -2]
                      [(fx= e 4) -3] ; EINTR, 4 on macOS and Linux
                      [else (jthrow "java/io/IOException" (string-append what " (" (strerror-text e) ")"))]))]))
(define (write-status r what)
  (if (fx>= r 0) r (io-status r what)))

;; read and write on fd, without the GIL when fd may wait; addr is native
;; memory the caller holds, so it stays put meanwhile. EINTR comes back as
;; -3, as the JDK's natives answer it, so the caller decides about retrying.
(define (fd-read fd addr n)
  (if (fd-may-wait? fd)
      (released-call* (lambda () (libc-read-blocking fd addr n)))
      (libc-read fd addr n)))
(define (fd-write fd addr n)
  (if (fd-may-wait? fd)
      (released-call* (lambda () (libc-write-blocking fd addr n)))
      (libc-write fd addr n)))
(define (released-call* call)
  (let ([r+e (gil-released (lambda () (let ([r (call)]) (cons r (if (< r 0) (errno) 0)))))])
    (when (< (car r+e) 0) (foreign-set! 'int (libc-errno-location) 0 (cdr r+e)))
    (car r+e)))

(defufd "read0(Ljava/io/FileDescriptor;JI)I" (fdo addr n)
  (if (fx= n 0) 0 (io-status (fd-read (fdo-fd fdo) addr n) "Read failed")))
(defufd "pread0(Ljava/io/FileDescriptor;JIJ)I" (fdo addr n pos)
  (if (fx= n 0) 0 (io-status (libc-pread (fdo-fd fdo) addr n pos) "Read failed")))
(defufd "write0(Ljava/io/FileDescriptor;JI)I" (fdo addr n)
  (console-flush (fdo-fd fdo))
  (write-status (fd-write (fdo-fd fdo) addr n) "Write failed"))
;; scattering and gathering: an array of n struct iovec {base, len}, done one
;; at a time; a short transfer ends it as readv/writev would
(define (iov-loop fdo addr n op)
  (let loop ([i 0] [total 0])
    (if (fx= i n)
        total
        (let* ([base (foreign-ref 'uptr addr (fx* i 16))] [len (foreign-ref 'uptr addr (fx+ (fx* i 16) 8))]
               [r (if (fx= len 0) 0 (op (fdo-fd fdo) base len))])
          (cond [(< r 0) (if (fx= total 0) r total)]
                [(< r len) (+ total r)]
                [else (loop (fx+ i 1) (+ total r))])))))
(defufd "readv0(Ljava/io/FileDescriptor;JI)J" (fdo addr n)
  (io-status (iov-loop fdo addr n fd-read) "Read failed"))
(defufd "writev0(Ljava/io/FileDescriptor;JI)J" (fdo addr n)
  (console-flush (fdo-fd fdo))
  (write-status (iov-loop fdo addr n fd-write) "Write failed"))
(defufd "pwrite0(Ljava/io/FileDescriptor;JIJ)I" (fdo addr n pos)
  (write-status (libc-pwrite (fdo-fd fdo) addr n pos) "Write failed"))
;; a negative offset asks for the position
(defufd "seek0(Ljava/io/FileDescriptor;J)J" (fdo off)
  (let ([r (if (< off 0) (libc-lseek (fdo-fd fdo) 0 seek-cur) (libc-lseek (fdo-fd fdo) off seek-set))])
    (if (< r 0) (io-error "lseek failed") r)))
(defufd "size0(Ljava/io/FileDescriptor;)J" (fdo) (fd-size (fdo-fd fdo) "Size failed"))
(defufd "truncate0(Ljava/io/FileDescriptor;J)I" (fdo n)
  (if (< (libc-ftruncate (fdo-fd fdo) n) 0) (io-error "Truncation failed") 0))
;; macOS's fsync leaves the drive's cache unflushed; F_FULLFSYNC (51) doesn't,
;; and fsync is the fallback where it isn't supported, as in the JDK
(define (full-fsync fd)
  (if (and fs-macos? (fx= 0 (libc-fcntl-int fd 51 0)))
      0
      (libc-fsync fd)))
(defufd "force0(Ljava/io/FileDescriptor;Z)I" (fdo meta)
  (if (< (full-fsync (fdo-fd fdo)) 0) (io-error "Force failed") 0))
(defnative "sun/nio/ch/FileDispatcherImpl.force0(Ljava/io/FileDescriptor;Z)I" (fdo meta)
  (if (< (full-fsync (fdo-fd fdo)) 0) (io-error "Force failed") 0))
(defnative "java/io/FileDescriptor.sync0()V" (this)
  (when (< (full-fsync (fdo-fd this)) 0) (jthrow "java/io/SyncFailedException" "sync failed")))
(defnative "java/io/FileCleanable.cleanupClose0(IJ)V" (fd handle) (fd-close fd))
(defufd "available0(Ljava/io/FileDescriptor;)I" (fdo)
  (fd-available (fdo-fd fdo) "Available failed"))
(defufd "isOther0(Ljava/io/FileDescriptor;)Z" (fdo)
  (need-layout "isOther")
  (let ([b (fd-stat (fdo-fd fdo))])
    (if (and b (not (memv (mode-type (stat-mode b)) (list s-ifreg s-ifdir)))) 1 0)))
(defufd "closeIntFD(I)V" (fd) (fd-close fd))

;; FileChannel.map: prot is FileChannelImpl's 0 read-only, 1 read-write, 2
;; private. The mapping is native memory, which Unsafe reads directly.
(defufd "map0(Ljava/io/FileDescriptor;IJJZ)J" (fdo prot pos len sync)
  (let ([a (libc-mmap 0 len (if (fx= prot 0) 1 3) (if (fx= prot 2) 2 1) (fdo-fd fdo) pos)])
    (if (= a (- (expt 2 64) 1)) (io-error "Map failed") a)))
(defufd "unmap0(JJ)I" (a len) (libc-munmap a len))
;; MappedByteBuffer.force: MS_SYNC is 16 on macOS, 4 on Linux
(defnative "java/nio/MappedMemoryUtils.force0(Ljava/io/FileDescriptor;JJ)V" (fdo a len)
  (when (< (libc-msync a len (if fs-macos? 16 4)) 0) (io-error "msync failed")))
;; residency hints: report the pages as loaded, and load or unload nothing
(defnative "java/nio/MappedMemoryUtils.isLoaded0(JJJ)Z" (a len pages) 1)
(defnative "java/nio/MappedMemoryUtils.load0(JJ)V" (a len) (void))
(defnative "java/nio/MappedMemoryUtils.unload0(JJ)V" (a len) (void))

;; locks through fcntl and this system's struct flock. FileDispatcher: 0
;; locked, -1 not, 2 interrupted. cmd and type are layout names (F_SETLK ...).
;; F_SETLKW waits for another process's lock, so it goes without the GIL,
;; with a struct flock of its own.
(define (fcntl-lock fd cmd type pos size)
  (need-layout "file locks")
  (let* ([wait? (eq? cmd 'F_SETLKW)]
         [buf (if wait? (foreign-alloc 64) fs-scratch)])
    (do ([i 0 (fx+ i 1)]) ((fx= i 32)) (foreign-set! 'unsigned-8 buf i 0))
    (fs-set! buf 'flock-start pos)
    ;; Long.MAX_VALUE means to the end of the file, as l_len 0 does
    (fs-set! buf 'flock-len (if (= size #x7fffffffffffffff) 0 size))
    (fs-set! buf 'flock-type (fs-off type))
    (fs-set! buf 'flock-whence seek-set)
    (if wait?
        (let ([r (released-call* (lambda () (libc-fcntl-lock-blocking fd (fs-off cmd) buf)))])
          (foreign-free buf)
          r)
        (libc-fcntl-lock fd (fs-off cmd) buf))))
(defufd "lock0(Ljava/io/FileDescriptor;ZJJZ)I" (fdo blocking pos size shared)
  (if (fx= 0 (fcntl-lock (fdo-fd fdo) (if (eqv? blocking 1) 'F_SETLKW 'F_SETLK) (if (eqv? shared 1) 'F_RDLCK 'F_WRLCK) pos size))
      0
      (let ([e (errno)])
        (cond [(and (not (eqv? blocking 1)) (memv e (list (fs-const "EAGAIN") (fs-const "EACCES")))) -1]
              [(fx= e 4) 2]
              [else (jthrow "java/io/IOException" (string-append "Lock failed (" (strerror-text e) ")"))]))))
(defufd "release0(Ljava/io/FileDescriptor;JJ)V" (fdo pos size)
  (unless (fx= 0 (fcntl-lock (fdo-fd fdo) 'F_SETLK 'F_UNLCK pos size)) (io-error "Release failed")))
;; a file's identity, for the lock table: device and inode
(defnative "sun/nio/ch/FileKey.init(Ljava/io/FileDescriptor;[J)V" (fdo finfo)
  (need-layout "FileKey")
  (let ([b (fd-stat (fdo-fd fdo))])
    (unless b (io-error "fstat failed"))
    (vector-set! (jarr-vec finfo) 0 (i64 (fs-ref b 'st_dev)))
    (vector-set! (jarr-vec finfo) 1 (i64 (fs-ref b 'st_ino)))))
;; no sendfile: FileChannel.transferTo copies through a buffer instead
(defnative "sun/nio/ch/FileDispatcherImpl.transferTo0(Ljava/io/FileDescriptor;JJLjava/io/FileDescriptor;Z)J" (src pos n dst append) -4)

;; Linux's JDK declares these where macOS's doesn't. init0 and init only look
;; up optional kernel calls (sendfile, copy_file_range); without them the
;; JDK takes its generic paths, which IOStatus.UNSUPPORTED (-4) asks for, as
;; transferTo0 above does. posix_fadvise is a hint, and ignoring it is safe.
(defnative "sun/nio/ch/FileDispatcherImpl.init0()V" () (void))
(defnative "sun/nio/ch/FileDispatcherImpl.transferFrom0(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;JJZ)J" (src dst pos n append) -4)
(defnative "sun/nio/fs/LinuxNativeDispatcher.init()V" () (void))
(defnative "sun/nio/fs/LinuxNativeDispatcher.directCopy0(IIJ)I" (dst src cancel) -4)
(defnative "sun/nio/fs/LinuxNativeDispatcher.posix_fadvise(IJJI)I" (fd off len advice) 0)
(defufd "allocationGranularity0()J" () (libc-getpagesize))

(defnative "sun/nio/ch/IOUtil.fdVal(Ljava/io/FileDescriptor;)I" (fdo) (fdo-fd fdo))
(defnative "sun/nio/ch/IOUtil.setfdVal(Ljava/io/FileDescriptor;I)V" (fdo fd) (fdo-fd-set! fdo fd))
(defnative "sun/nio/ch/IOUtil.iovMax()I" () 1024)
(defnative "sun/nio/ch/IOUtil.writevMax()J" () #x7fffffffffffffff)
(defnative "sun/nio/ch/IOUtil.fdLimit()I" () 10240)

(defnative "jdk/internal/misc/Unsafe.reallocateMemory0(JJ)J" (this a n)
  (let ([b (libc-realloc a (max n 1))])
    (when (eqv? b 0) (jthrow "java/lang/OutOfMemoryError" "Unable to allocate"))
    b))

;; The VM fills in UnsafeConstants after its <clinit> zeroes them. With an
;; address size of 0, Unsafe.allocateMemory rounds every size down to 0 and
;; hands out address 0.
(define-override! "jdk/internal/misc/UnsafeConstants.<clinit>()V"
  (lambda ()
    (let ([set (lambda (f v) (set-top-level-value! (string->symbol (string-append "jdk/internal/misc/UnsafeConstants:" f)) v))])
      (set "ADDRESS_SIZE0" 8)
      (set "PAGE_SIZE" (if fs-libc-loaded (libc-getpagesize) 4096))
      (set "BIG_ENDIAN" 0)
      (set "UNALIGNED_ACCESS" 0)
      (set "DATA_CACHE_LINE_FLUSH_SIZE" 0))))

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
