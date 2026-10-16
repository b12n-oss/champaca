;; Files: java.io streams, RandomAccessFile and java.io.UnixFileSystem, and
;; the natives under java.nio.file and FileChannel (sun.nio.fs.UnixNative-
;; Dispatcher, sun.nio.ch.UnixFileDispatcherImpl). All of them call libc
;; through Chez's foreign interface on real file descriptors, so a
;; FileDescriptor from java.io works with a FileChannel and the other way
;; round. Paths reach the NIO natives as addresses of C strings in native
;; memory (Unsafe.allocateMemory, natives.ss), and go to libc as they are.
;; fds 0, 1 and 2 keep going through Chez's own ports, which jolt shares.
;;
;; struct stat, struct dirent and struct statfs are read at macOS's offsets
;; (64-bit inodes, arm64 and x86_64). On another system the natives that read
;; them throw UnsupportedOperationException, and java.io's size and
;; regular-file checks fall back to lseek; the rest only need libc.

(define fs-macos? (and (memq (machine-type) '(tarm64osx arm64osx ta6osx a6osx)) #t))
;; whether struct stat and friends are read here: the macOS offsets below
(define fs-stat-layout? fs-macos?)
(define fs-libc-loaded
  (guard (e [#t #f])
    (load-shared-object (if fs-macos? "libSystem.dylib" "libc.so.6"))
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
(deflibc libc-statfs-string (if (memq (machine-type) '(ta6osx a6osx)) "statfs$INODE64" "statfs") #f (utf-8 uptr) int)
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
(deflibc libc-clonefile (if fs-macos? "clonefile" "no-clonefile-here") #f (uptr uptr int) int)

(define (errno) (foreign-ref 'int (libc-errno-location) 0))
(define (errno-clear!) (foreign-set! 'int (libc-errno-location) 0 0))
(define (strerror-text e) (libc-strerror e))

(define (need-macos what)
  (unless fs-stat-layout?
    (jthrow "java/lang/UnsupportedOperationException" (string-append what " reads macOS's struct layout"))))

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

;; fstat fields at macOS offsets; #f on failure, or off macOS
(define (fd-stat fd)
  (and fs-stat-layout? (if (< (libc-fstat fd fs-stat-buf) 0) #f fs-stat-buf)))
(define (stat-mode buf) (foreign-ref 'unsigned-16 buf 4))
(define (stat-size buf) (foreign-ref 'integer-64 buf 96))
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
(define (fd-read-into fd arr off n)
  (let* ([want (fxmin n 4096)] [r (libc-read fd fs-scratch want)])
    (when (< r 0) (io-error "Read error"))
    (let ([v (jarr-vec arr)])
      (do ([i 0 (fx+ i 1)]) ((fx= i r) r)
        (vector-set! v (fx+ off i) (i2b (foreign-ref 'unsigned-8 fs-scratch i)))))))

(define (fd-write-from fd arr off n)
  (let ([v (jarr-vec arr)])
    (let loop ([done 0])
      (when (fx< done n)
        (let ([k (fxmin (fx- n done) 4096)])
          (do ([i 0 (fx+ i 1)]) ((fx= i k))
            (foreign-set! 'unsigned-8 fs-scratch i (fxlogand (vector-ref v (fx+ off done i)) #xff)))
          (let ([w (libc-write fd fs-scratch k)])
            (when (< w 0) (io-error "Write error"))
            (loop (fx+ done w))))))))

(define (fd-close fd) (when (fx> fd 2) (libc-close fd)))

;; open for java.io: FileNotFoundException with the reason, and a directory
;; refused as the JDK refuses it
(define (java-io-open path flags)
  (let ([fd (libc-open-string path flags #o666)])
    (when (< fd 0) (file-not-found path))
    (when (file-directory? path)
      (libc-close fd)
      (jthrow "java/io/FileNotFoundException" (string-append path " (Is a directory)")))
    fd))

;; --- FileInputStream -----------------------------------------------------------------
;; fd 0 reads through Chez's standard input port.

(define fis "java/io/FileInputStream")

(defnative "java/io/FileInputStream.open0(Ljava/lang/String;)V" (this name)
  (fdo-fd-set! (holder-fdo this fis) (java-io-open (jstring->scheme (nn name)) (fs-const "O_RDONLY"))))

(defnative "java/io/FileInputStream.read0()I" (this)
  (let ([fd (holder-fd this fis)])
    (if (fx= fd 0)
        (let ([b (get-u8 (standard-input-port))]) (if (eof-object? b) -1 b))
        (let ([r (libc-read fd fs-scratch 1)])
          (cond [(< r 0) (io-error "Read error")] [(fx= r 0) -1] [else (foreign-ref 'unsigned-8 fs-scratch 0)])))))

(defnative "java/io/FileInputStream.readBytes([BII)I" (this bytes off len)
  (let ([fd (holder-fd this fis)])
    (cond
      [(fx= len 0) 0]
      [(fx= fd 0)
       ;; whatever is available, up to len, so a console read returns a line
       (let* ([bv (make-bytevector len)] [n (get-bytevector-some! (standard-input-port) bv 0 len)])
         (if (eof-object? n)
             -1
             (let ([v (jarr-vec bytes)])
               (do ([i 0 (fx+ i 1)]) ((fx= i n) n)
                 (vector-set! v (fx+ off i) (i2b (bytevector-u8-ref bv i)))))))]
      [else (let ([r (fd-read-into fd bytes off len)]) (if (fx= r 0) -1 r))])))

(defnative "java/io/FileInputStream.available0()I" (this)
  (let ([fd (holder-fd this fis)])
    (if (and (fx> fd 0) (fd-regular? fd))
        (max 0 (min #x7fffffff (- (fd-size fd "Available error") (libc-lseek fd 0 seek-cur))))
        0)))
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

(defnative "java/io/FileOutputStream.open0(Ljava/lang/String;Z)V" (this name append)
  (fdo-fd-set! (holder-fdo this fos)
               (java-io-open (jstring->scheme (nn name))
                             (fxlogor (fs-const "O_WRONLY") (fs-const "O_CREAT")
                                      (if (eqv? append 1) (fs-const "O_APPEND") (fs-const "O_TRUNC"))))))

(define (console-fd? fd) (or (fx= fd 1) (fx= fd 2)))

(defnative "java/io/FileOutputStream.writeBytes([BIIZ)V" (this bytes off len append)
  (let ([fd (holder-fd this fos)])
    (if (console-fd? fd) (console-write-bytes fd bytes off len) (fd-write-from fd bytes off len))))
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
                             (if (fxpositive? (fxlogand mode 2))
                                 (fxlogor (fs-const "O_RDWR") (fs-const "O_CREAT"))
                                 (fs-const "O_RDONLY")))))
(defnative "java/io/RandomAccessFile.read0()I" (this)
  (let ([r (libc-read (holder-fd this raf) fs-scratch 1)])
    (cond [(< r 0) (io-error "Read error")] [(fx= r 0) -1] [else (foreign-ref 'unsigned-8 fs-scratch 0)])))
(defnative "java/io/RandomAccessFile.readBytes0([BII)I" (this bytes off len)
  (if (fx= len 0) 0 (let ([r (fd-read-into (holder-fd this raf) bytes off len)]) (if (fx= r 0) -1 r))))
(defnative "java/io/RandomAccessFile.write0(I)V" (this b)
  (fd-write-from (holder-fd this raf) (make-jarr "B" (vector b)) 0 1))
(defnative "java/io/RandomAccessFile.writeBytes0([BII)V" (this bytes off len)
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
    (do ([i 0 (fx+ i 1)]) ((fx= i n) bv) (bytevector-u8-set! bv i (foreign-ref 'unsigned-8 p i)))))
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
  (need-macos "File.setPermission")
  (let ([p (io-file-path f)])
    (if (< (libc-stat-string p fs-stat-buf) 0)
        0
        (let* ([bits (if (eqv? owner-only 1) (fxsll access 6) (fxlogor (fxsll access 6) (fxsll access 3) access))]
               [m (fxlogand (stat-mode fs-stat-buf) #o7777)])
          (ok? (libc-chmod-string p (if (eqv? enable 1) (fxlogor m bits) (fxlogand m (fxlognot bits)))))))))
(defnative "java/io/UnixFileSystem.setReadOnly0(Ljava/io/File;)Z" (this f)
  (need-macos "File.setReadOnly")
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
  (need-macos "File.getTotalSpace")
  (let ([b (foreign-alloc 2400)])
    (let ([r (if (< (libc-statfs-string (io-file-path f) b) 0)
                 0
                 (* (foreign-ref 'unsigned-32 b 0)
                    (foreign-ref 'unsigned-64 b (case t [(0) 8] [(1) 16] [else 24]))))])
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

;; capabilities: birth times, no *at calls, no extended attributes
(defund "init()I" () (if fs-stat-layout? (fxsll 1 16) 0))
(defund "fchmodatNoFollowSupported0()Z" () 0)

(defund "getcwd()[B" ()
  (if (eqv? 0 (libc-getcwd fs-scratch 4096)) (unix-error) (cstring->bytes fs-scratch)))
(defund "strerror(I)[B" (e)
  (let ([s (string->utf8 (strerror-text e))])
    (make-jarr "B" (list->vector (map i2b (bytevector->u8-list s))))))
(defund "dup(I)I" (fd) (unix-check (libc-dup fd)))
(defund "open0(JII)I" (path flags mode) (unix-check (libc-open path flags mode)))
(defund "close0(I)V" (fd) (unix-check (libc-close fd)))
(defund "read0(IJI)I" (fd buf n) (unix-check (libc-read fd buf n)))
(defund "write0(IJI)I" (fd buf n) (unix-check (libc-write fd buf n)))
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
(define (fill-attrs! attrs buf)
  (let ([set (lambda (f v) (jslot-set! attrs (jfield-index ufa f) v))]
        [s64 (lambda (off) (foreign-ref 'integer-64 buf off))])
    (set "st_dev" (foreign-ref 'integer-32 buf 0))
    (set "st_mode" (foreign-ref 'unsigned-16 buf 4))
    (set "st_nlink" (foreign-ref 'unsigned-16 buf 6))
    (set "st_ino" (i64 (foreign-ref 'unsigned-64 buf 8)))
    (set "st_uid" (i32 (foreign-ref 'unsigned-32 buf 16)))
    (set "st_gid" (i32 (foreign-ref 'unsigned-32 buf 20)))
    (set "st_rdev" (foreign-ref 'integer-32 buf 24))
    (set "st_atime_sec" (s64 32)) (set "st_atime_nsec" (s64 40))
    (set "st_mtime_sec" (s64 48)) (set "st_mtime_nsec" (s64 56))
    (set "st_ctime_sec" (s64 64)) (set "st_ctime_nsec" (s64 72))
    (set "st_birthtime_sec" (s64 80)) (set "st_birthtime_nsec" (s64 88))
    (set "birthtime_available" 1)
    (set "st_size" (s64 96))))

;; stat0 answers an errno rather than throwing
(defund "stat0(JLsun/nio/fs/UnixFileAttributes;)I" (p attrs)
  (need-macos "stat")
  (if (< (libc-stat p fs-stat-buf) 0) (errno) (begin (fill-attrs! attrs fs-stat-buf) 0)))
(defund "lstat0(JLsun/nio/fs/UnixFileAttributes;)V" (p attrs)
  (need-macos "lstat")
  (unix-check (libc-lstat p fs-stat-buf))
  (fill-attrs! attrs fs-stat-buf))
(defund "fstat0(ILsun/nio/fs/UnixFileAttributes;)V" (fd attrs)
  (need-macos "fstat")
  (unix-check (libc-fstat fd fs-stat-buf))
  (fill-attrs! attrs fs-stat-buf))

(defund "opendir0(J)J" (p)
  (let ([d (libc-opendir p)]) (if (eqv? d 0) (unix-error) d)))
(defund "closedir(J)V" (d) (unix-check (libc-closedir d)))
;; the next name, or null at the end; "." and ".." are left to the caller
(defund "readdir0(J)[B" (d)
  (need-macos "readdir")
  (errno-clear!)
  (let ([e (libc-readdir d)])
    (if (eqv? e 0)
        (if (fx= (errno) 0) #f (unix-error))
        (cbytes (+ e 21) (foreign-ref 'unsigned-16 e 18)))))

(defnative "sun/nio/fs/MacOSXNativeDispatcher.normalizepath([CI)[C" (path form) path)

;; clonefile answers an errno; BsdFileSystem falls back to copying on any
(defnative "sun/nio/fs/BsdNativeDispatcher.clonefile0(JJI)I" (src dst flags)
  (if (< (libc-clonefile src dst flags) 0) (errno) 0))
;; a plain read/write loop
(define (copy-fd-to-fd dst src)
  (let loop ()
    (let ([n (libc-read src fs-scratch 4096)])
      (cond [(< n 0) (unix-error)]
            [(> n 0)
             (let wloop ([done 0])
               (when (< done n)
                 (let ([w (libc-write dst (+ fs-scratch done) (- n done))])
                   (when (< w 0) (unix-error))
                   (wloop (+ done w)))))
             (loop)]))))
(defnative "sun/nio/fs/BsdFileSystem.directCopy0(IIJ)I" (dst src cancel) (copy-fd-to-fd dst src) 0)
(defnative "sun/nio/fs/UnixFileSystem.bufferedCopy0(IIJIJ)V" (dst src buf size cancel) (copy-fd-to-fd dst src))

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

(defufd "read0(Ljava/io/FileDescriptor;JI)I" (fdo addr n)
  (if (fx= n 0) 0 (io-status (libc-read (fdo-fd fdo) addr n) "Read failed")))
(defufd "pread0(Ljava/io/FileDescriptor;JIJ)I" (fdo addr n pos)
  (if (fx= n 0) 0 (io-status (libc-pread (fdo-fd fdo) addr n pos) "Read failed")))
(defufd "write0(Ljava/io/FileDescriptor;JI)I" (fdo addr n)
  (write-status (libc-write (fdo-fd fdo) addr n) "Write failed"))
(defufd "pwrite0(Ljava/io/FileDescriptor;JIJ)I" (fdo addr n pos)
  (write-status (libc-pwrite (fdo-fd fdo) addr n pos) "Write failed"))
;; a negative offset asks for the position
(defufd "seek0(Ljava/io/FileDescriptor;J)J" (fdo off)
  (let ([r (if (< off 0) (libc-lseek (fdo-fd fdo) 0 seek-cur) (libc-lseek (fdo-fd fdo) off seek-set))])
    (if (< r 0) (io-error "lseek failed") r)))
(defufd "size0(Ljava/io/FileDescriptor;)J" (fdo) (fd-size (fdo-fd fdo) "Size failed"))
(defufd "truncate0(Ljava/io/FileDescriptor;J)I" (fdo n)
  (if (< (libc-ftruncate (fdo-fd fdo) n) 0) (io-error "Truncation failed") 0))
(defufd "force0(Ljava/io/FileDescriptor;Z)I" (fdo meta)
  (if (< (libc-fsync (fdo-fd fdo)) 0) (io-error "Force failed") 0))
(defnative "sun/nio/ch/FileDispatcherImpl.force0(Ljava/io/FileDescriptor;Z)I" (fdo meta)
  (if (< (libc-fsync (fdo-fd fdo)) 0) (io-error "Force failed") 0))
(defufd "available0(Ljava/io/FileDescriptor;)I" (fdo)
  (let ([fd (fdo-fd fdo)])
    (if (fd-regular? fd)
        (max 0 (min #x7fffffff (- (fd-size fd "Available failed") (libc-lseek fd 0 seek-cur))))
        0)))
(defufd "isOther0(Ljava/io/FileDescriptor;)Z" (fdo)
  (need-macos "isOther")
  (let ([b (fd-stat (fdo-fd fdo))])
    (if (and b (not (memv (mode-type (stat-mode b)) (list s-ifreg s-ifdir)))) 1 0)))
(defufd "closeIntFD(I)V" (fd) (fd-close fd))
(defufd "allocationGranularity0()J" () (libc-getpagesize))

(defnative "sun/nio/ch/IOUtil.fdVal(Ljava/io/FileDescriptor;)I" (fdo) (fdo-fd fdo))
(defnative "sun/nio/ch/IOUtil.setfdVal(Ljava/io/FileDescriptor;I)V" (fdo fd) (fdo-fd-set! fdo fd))
(defnative "sun/nio/ch/IOUtil.iovMax()I" () 1024)
(defnative "sun/nio/ch/IOUtil.writevMax()J" () #x7fffffffffffffff)
(defnative "sun/nio/ch/IOUtil.fdLimit()I" () 10240)
(defnative "sun/nio/ch/IOUtil.configureBlocking(Ljava/io/FileDescriptor;Z)V" (fdo blocking) (void))

;; no signalling of native threads: current() is 0, so nothing is signalled
(defnative "sun/nio/ch/NativeThread.init()V" () (void))
(defnative "sun/nio/ch/NativeThread.current0()J" () 0)
(defnative "sun/nio/ch/NativeThread.signal0(J)V" (tid) (void))
(defnative "sun/nio/ch/NativeThread.supportPendingSignals0()Z" () 0)

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
