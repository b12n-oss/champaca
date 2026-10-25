;; Sockets: the natives of java.net (InetAddress and Inet4AddressImpl) and of
;; sun.nio.ch's Net, SocketDispatcher, UnixDispatcher and IOUtil's blocking
;; mode, through libc as files.ss does files (its deflibc bindings, errno and
;; GIL helpers are reused). java.net.Socket and ServerSocket run on
;; NioSocketImpl, so a blocking socket is a real socket fd configured
;; non-blocking when a timeout is set, polled with poll(2).
;;
;; IPv4 only: Net.isIPv6Available0 and InetAddress.isIPv6Supported both say
;; no, so every address is a sockaddr_in and an Inet6Address is refused with
;; the JDK's "Protocol family unavailable". Blocking calls (connect, accept,
;; poll, read, write, getaddrinfo) give the GIL up through __collect_safe
;; entries. A write to a socket whose peer is gone raises SIGPIPE, which
;; would end a process that hasn't ignored it. Chez's runtime ignores it
;; (jolt's process.ss notes the same, and a probe found SIG_IGN before
;; champaca boots), so the write fails with EPIPE; for a host that doesn't,
;; every socket also gets SO_NOSIGPIPE on macOS and a write goes through
;; send with MSG_NOSIGNAL on Linux.

(define net-macos? fs-macos?)

;; what differs between macOS and Linux: option names, ioctl requests, errno
;; values, struct addrinfo's field order (macOS puts ai_canonname before
;; ai_addr), sockaddr_in's leading byte (macOS has sin_len)
(define net-consts-macos
  '((SOL_SOCKET . #xffff) (SO_REUSEADDR . 4) (SO_REUSEPORT . #x200) (SO_ERROR . #x1007) (SO_LINGER . #x80)
    (SO_NOSIGPIPE . #x1022) (MSG_NOSIGNAL . 0) (FIONREAD . #x4004667f) (O_NONBLOCK . 4) (NI_NAMEREQD . 4)
    (EAGAIN . 35) (EINPROGRESS . 36) (EALREADY . 37) (EISCONN . 56) (ECONNREFUSED . 61) (ECONNRESET . 54)
    (ETIMEDOUT . 60) (ENOTCONN . 57) (EHOSTUNREACH . 65) (EADDRINUSE . 48) (EADDRNOTAVAIL . 49) (EPROTO . 100)
    (ai-addr . 32)))
(define net-consts-linux
  '((SOL_SOCKET . 1) (SO_REUSEADDR . 2) (SO_REUSEPORT . 15) (SO_ERROR . 4) (SO_LINGER . 13)
    (SO_NOSIGPIPE . #f) (MSG_NOSIGNAL . #x4000) (FIONREAD . #x541b) (O_NONBLOCK . 2048) (NI_NAMEREQD . 8)
    (EAGAIN . 11) (EINPROGRESS . 115) (EALREADY . 114) (EISCONN . 106) (ECONNREFUSED . 111) (ECONNRESET . 104)
    (ETIMEDOUT . 110) (ENOTCONN . 107) (EHOSTUNREACH . 113) (EADDRINUSE . 98) (EADDRNOTAVAIL . 99) (EPROTO . 71)
    (ai-addr . 24)))
(define (net-const name)
  (cdr (or (assq name (if net-macos? net-consts-macos net-consts-linux)) (error 'champaca "no net constant" name))))
;; the same on both: AF_INET 2, SOCK_STREAM 1, SOCK_DGRAM 2, EINTR 4, EPIPE 32,
;; EACCES 13, F_GETFL 3, F_SETFL 4, MSG_OOB 1, POLLIN 1, POLLOUT 4, POLLERR 8,
;; POLLHUP 16, POLLNVAL 32; struct addrinfo: ai_family at 4, ai_socktype at 8,
;; ai_next at 40, 48 bytes; struct pollfd {int fd; short events, revents};
;; struct linger {int l_onoff; int l_linger}; sockaddr_in 16 bytes with the
;; port at 2 and the address at 4, both big-endian

(deflibc libc-socket "socket" #f (int int int) int)
(deflibc libc-bind "bind" #f (int uptr unsigned-32) int)
(deflibc libc-listen "listen" #f (int int) int)
(deflibc libc-getsockname "getsockname" #f (int uptr uptr) int)
(deflibc libc-getpeername "getpeername" #f (int uptr uptr) int)
(deflibc libc-getsockopt "getsockopt" #f (int int int uptr uptr) int)
(deflibc libc-setsockopt "setsockopt" #f (int int int uptr unsigned-32) int)
(deflibc libc-shutdown "shutdown" #f (int int) int)
(deflibc libc-ioctl "ioctl" (__varargs_after 2) (int unsigned-long uptr) int)
(deflibc libc-dup2 "dup2" #f (int int) int)
(deflibc libc-socketpair "socketpair" #f (int int int uptr) int)
(deflibc libc-gethostname "gethostname" #f (uptr size_t) int)
(deflibc libc-freeaddrinfo "freeaddrinfo" #f (uptr) void)
(deflibc libc-gai-strerror "gai_strerror" #f (int) string)
(deflibc libc-getnameinfo "getnameinfo" #f (uptr unsigned-32 uptr unsigned-32 uptr unsigned-32 int) int)
(deflibc-blocking libc-connect-blocking "connect" (int uptr unsigned-32) int)
(deflibc-blocking libc-accept-blocking "accept" (int uptr uptr) int)
(deflibc-blocking libc-poll-blocking "poll" (uptr unsigned-32 int) int)
(deflibc-blocking libc-send-blocking "send" (int uptr size_t int) ssize_t)
(deflibc-blocking libc-getaddrinfo-blocking "getaddrinfo" (uptr uptr uptr uptr) int)

;; --- errors, as Net.c's handleSocketError maps them, with strerror's text ---------
(define (socket-exception-class e)
  (cond [(fx= e (net-const 'EPROTO)) "java/net/ProtocolException"]
        [(memv e (list (net-const 'ECONNREFUSED) (net-const 'ETIMEDOUT) (net-const 'ENOTCONN))) "java/net/ConnectException"]
        [(fx= e (net-const 'EHOSTUNREACH)) "java/net/NoRouteToHostException"]
        [(memv e (list (net-const 'EADDRINUSE) (net-const 'EADDRNOTAVAIL) 13)) "java/net/BindException"]
        [else "java/net/SocketException"]))
(define (socket-error e) (jthrow (socket-exception-class e) (strerror-text e)))
(define (check-socket r) (when (< r 0) (socket-error (errno))) r)

;; --- addresses -----------------------------------------------------------------------
(define (ia-holder ia) (jslot (nn ia) (jfield-index "java/net/InetAddress" "holder")))
(define (ia-family ia) (jslot (ia-holder ia) (jfield-index "java/net/InetAddress$InetAddressHolder" "family")))
;; an InetAddress's IPv4 address as a 32-bit unsigned number
(define (ia-ipv4 ia)
  (unless (fx= (ia-family ia) 1) (jthrow "java/net/SocketException" "Protocol family unavailable"))
  (fxlogand (jslot (ia-holder ia) (jfield-index "java/net/InetAddress$InetAddressHolder" "address")) #xffffffff))
(define (inet4 host ip)   ; host a jstring or #f
  (jinit "java/net/Inet4Address")
  (construct "java/net/Inet4Address" "(Ljava/lang/String;I)V" host (i32 ip)))
(define (inet-socket-address ip port)
  (construct "java/net/InetSocketAddress" "(Ljava/net/InetAddress;I)V" (inet4 #f ip) port))

(define sockaddr-size 16)
(define (sockaddr-in! sa ip port)
  (do ([i 0 (fx+ i 1)]) ((fx= i sockaddr-size)) (foreign-set! 'unsigned-8 sa i 0))
  (if net-macos?
      (begin (foreign-set! 'unsigned-8 sa 0 sockaddr-size) (foreign-set! 'unsigned-8 sa 1 2))
      (foreign-set! 'unsigned-16 sa 0 2))
  (foreign-set! 'unsigned-8 sa 2 (fxlogand (fxsrl port 8) 255))
  (foreign-set! 'unsigned-8 sa 3 (fxlogand port 255))
  (do ([i 0 (fx+ i 1)]) ((fx= i 4))
    (foreign-set! 'unsigned-8 sa (fx+ 4 i) (fxlogand (fxsrl ip (fx* 8 (fx- 3 i))) 255))))
(define (sockaddr-port sa) (fxlogor (fxsll (foreign-ref 'unsigned-8 sa 2) 8) (foreign-ref 'unsigned-8 sa 3)))
(define (sockaddr-ip sa)
  (let loop ([i 0] [acc 0])
    (if (fx= i 4) acc (loop (fx+ i 1) (fxlogor (fxsll acc 8) (foreign-ref 'unsigned-8 sa (fx+ 4 i)))))))
;; (k sa) with a sockaddr_in for ia:port in fresh foreign memory, freed after
(define (with-sockaddr ia port k)
  (let ([ip (ia-ipv4 ia)] [sa (foreign-alloc sockaddr-size)])
    (sockaddr-in! sa ip port)
    (let ([r (guard (e [#t (foreign-free sa) (raise e)]) (k sa))])
      (foreign-free sa) r)))
;; (k sa) with an empty sockaddr_in and its length cell, for getsockname and the like
(define (with-sockaddr-out k)
  (let ([sa (foreign-alloc sockaddr-size)] [len (foreign-alloc 4)])
    (do ([i 0 (fx+ i 1)]) ((fx= i sockaddr-size)) (foreign-set! 'unsigned-8 sa i 0))
    (foreign-set! 'unsigned-32 len 0 sockaddr-size)
    (let ([r (guard (e [#t (foreign-free sa) (foreign-free len) (raise e)]) (k sa len))])
      (foreign-free sa) (foreign-free len) r)))

(define (net-fd fdo) (fdo-fd fdo))
(define (local-sockaddr fd k) (with-sockaddr-out (lambda (sa len) (check-socket (libc-getsockname fd sa len)) (k sa))))
(define (peer-sockaddr fd k) (with-sockaddr-out (lambda (sa len) (check-socket (libc-getpeername fd sa len)) (k sa))))

;; --- options ---------------------------------------------------------------------------
;; errno is read right after the libc call, before the buffers are freed (free
;; may change it); (failed e) runs after the frees when r is negative
(define (after-free r e failed) (when (< r 0) (failed e)) r)
(define (set-int-option! fd level opt v)
  (let ([buf (foreign-alloc 4)])
    (foreign-set! 'int buf 0 v)
    (let* ([r (libc-setsockopt fd level opt buf 4)] [e (errno)])
      (foreign-free buf)
      (after-free r e socket-error))))
(define (get-int-option fd level opt)
  (let ([buf (foreign-alloc 8)] [len (foreign-alloc 4)])
    (foreign-set! 'int buf 0 0)
    (foreign-set! 'unsigned-32 len 0 4)
    (let* ([r (libc-getsockopt fd level opt buf len)] [e (errno)] [v (foreign-ref 'int buf 0)])
      (foreign-free buf) (foreign-free len)
      (after-free r e socket-error)
      v)))
(define (linger? level opt) (and (fx= level (net-const 'SOL_SOCKET)) (fx= opt (net-const 'SO_LINGER))))
;; SO_LINGER carries struct linger: -1 when off, else the seconds
(define (get-linger fd)
  (let ([buf (foreign-alloc 8)] [len (foreign-alloc 4)])
    (foreign-set! 'int buf 0 0) (foreign-set! 'int buf 4 0)
    (foreign-set! 'unsigned-32 len 0 8)
    (let* ([r (libc-getsockopt fd (net-const 'SOL_SOCKET) (net-const 'SO_LINGER) buf len)] [e (errno)]
           [on (foreign-ref 'int buf 0)] [secs (foreign-ref 'int buf 4)])
      (foreign-free buf) (foreign-free len)
      (after-free r e socket-error)
      (if (fx= on 0) -1 secs))))
(define (set-linger! fd arg)
  (let ([buf (foreign-alloc 8)])
    (foreign-set! 'int buf 0 (if (fx>= arg 0) 1 0))
    (foreign-set! 'int buf 4 (if (fx>= arg 0) arg 0))
    (let* ([r (libc-setsockopt fd (net-const 'SOL_SOCKET) (net-const 'SO_LINGER) buf 8)] [e (errno)])
      (foreign-free buf)
      (after-free r e socket-error))))
;; a socket of ours never raises SIGPIPE on macOS (Linux's writes say so per
;; call). Best effort: setsockopt fails with EINVAL on a socket whose peer
;; has already reset it, which accept hands out too, and a peer that is gone
;; can't be written to anyway
(define (quiet-pipe! fd)
  (when (net-const 'SO_NOSIGPIPE)
    (let ([buf (foreign-alloc 4)])
      (foreign-set! 'int buf 0 1)
      (libc-setsockopt fd (net-const 'SOL_SOCKET) (net-const 'SO_NOSIGPIPE) buf 4)
      (foreign-free buf))))

;; --- sun.nio.ch.Net ------------------------------------------------------------------
(define (defnet sig proc) (define-native! (string-append "sun/nio/ch/Net." sig) proc))
(defnet "isIPv6Available0()Z" (lambda () 0))
(defnet "isReusePortAvailable0()Z" (lambda () 1))
(defnet "isExclusiveBindAvailable()I" (lambda () -1))
;; macOS needs the write side shut before close so the peer sees the FIN
;; before a reset, as the JDK's native answers there
(defnet "shouldShutdownWriteBeforeClose0()Z" (lambda () (if net-macos? 1 0)))
(defnet "shouldSetBothIPv4AndIPv6Options0()Z" (lambda () (if net-macos? 0 1)))
(defnet "canIPv6SocketJoinIPv4Group0()Z" (lambda () 0))
(defnet "canJoin6WithIPv4Group0()Z" (lambda () 0))
(defnet "canUseIPv6OptionsWithIPv4LocalAddress0()Z" (lambda () 0))
(defnet "pollinValue()S" (lambda () 1))
(defnet "polloutValue()S" (lambda () 4))
(defnet "pollerrValue()S" (lambda () 8))
(defnet "pollhupValue()S" (lambda () 16))
(defnet "pollnvalValue()S" (lambda () 32))
(defnet "pollconnValue()S" (lambda () 4))

(defnet "socket0(ZZZZ)I"
  (lambda (ipv6 stream reuse fast)
    (let ([fd (check-socket (libc-socket 2 (if (eqv? stream 1) 1 2) 0))])
      ;; the new fd doesn't leak when its option can't be set
      (when (eqv? reuse 1)
        (guard (e [#t (libc-close fd) (raise e)])
          (set-int-option! fd (net-const 'SOL_SOCKET) (net-const 'SO_REUSEADDR) 1)))
      (quiet-pipe! fd)
      fd)))
(defnet "bind0(Ljava/io/FileDescriptor;ZZLjava/net/InetAddress;I)V"
  (lambda (fdo ipv6 excl ia port)
    (with-sockaddr ia port (lambda (sa) (check-socket (libc-bind (net-fd fdo) sa sockaddr-size)) (void)))))
(defnet "listen(Ljava/io/FileDescriptor;I)V"
  (lambda (fdo backlog) (check-socket (libc-listen (net-fd fdo) backlog)) (void)))
;; 1 connected; -2 in progress (a non-blocking socket); -3 interrupted
(defnet "connect0(ZLjava/io/FileDescriptor;Ljava/net/InetAddress;I)I"
  (lambda (ipv6 fdo ia port)
    (with-sockaddr ia port
      (lambda (sa)
        (let ([r (released-call* (lambda () (libc-connect-blocking (net-fd fdo) sa sockaddr-size)))])
          (if (fx>= r 0)
              1
              (let ([e (errno)])
                (cond [(fx= e (net-const 'EINPROGRESS)) -2]
                      [(fx= e 4) -3]
                      [else (socket-error e)]))))))))
;; 1 with newfd and isaa[0] set; -2 nothing waiting (non-blocking); -3 interrupted
(defnet "accept(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;[Ljava/net/InetSocketAddress;)I"
  (lambda (fdo newfdo isaa)
    (with-sockaddr-out
     (lambda (sa len)
       (let ([r (released-call* (lambda () (libc-accept-blocking (net-fd fdo) sa len)))])
         (if (fx>= r 0)
             (begin (fdo-fd-set! newfdo r)   ; the caller owns the fd from here, whatever follows
                    (quiet-pipe! r)
                    (vector-set! (jarr-vec isaa) 0 (inet-socket-address (sockaddr-ip sa) (sockaddr-port sa)))
                    1)
             (let ([e (errno)])
               (cond [(fx= e (net-const 'EAGAIN)) -2]
                     [(fx= e 4) -3]
                     [else (socket-error e)]))))))))
(defnet "shutdown(Ljava/io/FileDescriptor;I)V"
  (lambda (fdo how)
    (when (and (< (libc-shutdown (net-fd fdo) how) 0) (not (fx= (errno) (net-const 'ENOTCONN))))
      (socket-error (errno)))
    (void)))
(defnet "localPort(Ljava/io/FileDescriptor;)I" (lambda (fdo) (local-sockaddr (net-fd fdo) sockaddr-port)))
(defnet "localInetAddress(Ljava/io/FileDescriptor;)Ljava/net/InetAddress;"
  (lambda (fdo) (local-sockaddr (net-fd fdo) (lambda (sa) (inet4 #f (sockaddr-ip sa))))))
(defnet "remotePort(Ljava/io/FileDescriptor;)I" (lambda (fdo) (peer-sockaddr (net-fd fdo) sockaddr-port)))
(defnet "remoteInetAddress(Ljava/io/FileDescriptor;)Ljava/net/InetAddress;"
  (lambda (fdo) (peer-sockaddr (net-fd fdo) (lambda (sa) (inet4 #f (sockaddr-ip sa))))))
(defnet "getIntOption0(Ljava/io/FileDescriptor;ZII)I"
  (lambda (fdo conv level opt)
    (if (linger? level opt) (get-linger (net-fd fdo)) (get-int-option (net-fd fdo) level opt))))
(defnet "setIntOption0(Ljava/io/FileDescriptor;ZIIIZ)V"
  (lambda (fdo conv level opt arg ipv6)
    (if (linger? level opt) (set-linger! (net-fd fdo) arg) (set-int-option! (net-fd fdo) level opt arg))
    (void)))
;; the events that happened (0 on a timeout or an interrupt), as the JDK's poll answers
(define (poll-fd fd events timeout)
  (let ([pfd (foreign-alloc 8)])
    (foreign-set! 'int pfd 0 fd)
    (foreign-set! 'short pfd 4 events)
    (foreign-set! 'short pfd 6 0)
    (let* ([r (released-call* (lambda () (libc-poll-blocking pfd 1 (if (< timeout 0) -1 (min timeout #x7fffffff)))))]
           [e (errno)]
           [revents (foreign-ref 'short pfd 6)])
      (foreign-free pfd)
      (cond [(fx>= r 0) (if (fx= r 0) 0 revents)]
            [(fx= e 4) 0]
            [else (socket-error e)]))))
(defnet "poll(Ljava/io/FileDescriptor;IJ)I" (lambda (fdo events timeout) (poll-fd (net-fd fdo) events timeout)))
;; whether a non-blocking connect finished; its failure is thrown here
(defnet "pollConnect(Ljava/io/FileDescriptor;J)Z"
  (lambda (fdo timeout)
    (let* ([fd (net-fd fdo)] [revents (poll-fd fd 4 timeout)])
      (if (fx= revents 0)
          0
          (let ([e (get-int-option fd (net-const 'SOL_SOCKET) (net-const 'SO_ERROR))])
            (unless (fx= e 0) (socket-error e))
            1)))))
(defnet "available(Ljava/io/FileDescriptor;)I"
  (lambda (fdo)
    (let ([buf (foreign-alloc 4)])
      (foreign-set! 'int buf 0 0)
      (let* ([r (libc-ioctl (net-fd fdo) (net-const 'FIONREAD) buf)] [e (errno)] [n (foreign-ref 'int buf 0)])
        (foreign-free buf)
        (after-free r e socket-error)
        n))))
(defnet "sendOOB(Ljava/io/FileDescriptor;B)I"
  (lambda (fdo b)
    (let ([buf (foreign-alloc 1)])
      (foreign-set! 'unsigned-8 buf 0 (fxlogand b 255))
      (let* ([r (released-call* (lambda () (libc-send-blocking (net-fd fdo) buf 1 (fxlogor 1 (net-const 'MSG_NOSIGNAL)))))]
             [e (errno)])
        (foreign-free buf)
        (socket-status* r e #f)))))
;; pending urgent data isn't drained (the JDK reads it with MSG_OOB); nothing
;; on the blocking paths asks
(defnet "discardOOB(Ljava/io/FileDescriptor;)Z" (lambda (fdo) 0))

;; jdk.net's platform options (keep-alive tuning, quick ack, don't fragment,
;; peer credentials): none supported, so ExtendedSocketOptions offers the
;; standard options only and Net never asks for their natives
(for-each (lambda (n) (define-native! n (lambda () 0)))
  '("jdk/net/MacOSXSocketOptions.keepAliveOptionsSupported0()Z" "jdk/net/MacOSXSocketOptions.ipDontFragmentSupported0()Z"
    "jdk/net/LinuxSocketOptions.keepAliveOptionsSupported0()Z" "jdk/net/LinuxSocketOptions.quickAckSupported0()Z"
    "jdk/net/LinuxSocketOptions.incomingNapiIdSupported0()Z" "jdk/net/LinuxSocketOptions.ipDontFragmentSupported0()Z"))

;; --- the dispatchers ----------------------------------------------------------------
;; IOStatus as files.ss: -1 end of stream, -2 unavailable, -3 interrupted. On
;; a read, a peer that reset the connection (ECONNRESET, or EPIPE) is the
;; JDK's ConnectionResetException, which NioSocketImpl turns into
;; SocketException "Connection reset". A write's failure, a reset or a broken
;; pipe included, is an IOException with strerror's bare text ("Connection
;; reset by peer", "Broken pipe"), as the JDK's write0 only converts the
;; return value; so does any other failure of a read. Seen on the JVM: a
;; write after the peer reset says either text, depending on timing.
(define (socket-status* r e reading)
  (cond [(fx> r 0) r]
        [(fx= r 0) (if reading -1 0)]
        [(fx= e (net-const 'EAGAIN)) -2]
        [(fx= e 4) -3]
        [(and reading (memv e (list (net-const 'ECONNRESET) 32))) (jthrow "sun/net/ConnectionResetException" "Connection reset")]
        [else (jthrow "java/io/IOException" (strerror-text e))]))
(define (socket-status r reading) (socket-status* r (if (< r 0) (errno) 0) reading))
;; A Pipe's ends go through SocketDispatcher too, and send refuses a pipe
;; with ENOTSOCK (88 on Linux), so that falls back to write as the JDK's
;; write0 always does
(define (socket-write fd addr n)
  (if (fx= (net-const 'MSG_NOSIGNAL) 0)
      (fd-write fd addr n)
      (let ([r (released-call* (lambda () (libc-send-blocking fd addr n (net-const 'MSG_NOSIGNAL))))])
        (if (and (< r 0) (fx= (errno) 88))
            (fd-write fd addr n)
            r))))
(define (defsd sig proc) (define-native! (string-append "sun/nio/ch/SocketDispatcher." sig) proc))
(defsd "read0(Ljava/io/FileDescriptor;JI)I"
  (lambda (fdo addr n) (if (fx= n 0) 0 (socket-status (fd-read (net-fd fdo) addr n) #t))))
(defsd "readv0(Ljava/io/FileDescriptor;JI)J"
  (lambda (fdo addr n) (socket-status (iov-loop fdo addr n fd-read) #t)))
(defsd "write0(Ljava/io/FileDescriptor;JI)I"
  (lambda (fdo addr n) (socket-status (socket-write (net-fd fdo) addr n) #f)))
(defsd "writev0(Ljava/io/FileDescriptor;JI)J"
  (lambda (fdo addr n) (socket-status (iov-loop fdo addr n socket-write) #f)))

(defnative "sun/nio/ch/UnixDispatcher.init()V" () (void))
(defnative "sun/nio/ch/UnixDispatcher.close0(Ljava/io/FileDescriptor;)V" (fdo)
  (let ([fd (net-fd fdo)])
    (when (fx>= fd 0) (when (< (libc-close fd) 0) (io-error "Close failed")))
    (void)))
;; the JDK points a closing socket's fd at a dead descriptor first, so a
;; thread still using it fails instead of touching a reused number: one end
;; of a socketpair whose other end is closed, so a read sees the end and a
;; write EPIPE. Reached when another thread is in a read, write or accept
;; on the socket (NativeThread, below), just before that thread is signalled.
(define preclose-fd #f)
(define (preclose-fd!)
  (or preclose-fd
      (let ([sv (foreign-alloc 8)])
        (let* ([r (libc-socketpair 1 1 0 sv)] [e (errno)] [a (foreign-ref 'int sv 0)] [b (foreign-ref 'int sv 4)])
          (foreign-free sv)
          (after-free r e socket-error)
          (libc-close b)
          (quiet-pipe! a)
          (set! preclose-fd a)
          a))))
(defnative "sun/nio/ch/UnixDispatcher.preClose0(Ljava/io/FileDescriptor;)V" (fdo)
  (when (< (libc-dup2 (preclose-fd!) (net-fd fdo)) 0) (io-error "Pre-close failed"))
  (void))
(defnative "sun/nio/ch/IOUtil.configureBlocking(Ljava/io/FileDescriptor;Z)V" (fdo blocking)
  (let* ([fd (fdo-fd fdo)] [flags (libc-fcntl-int fd 3 0)] [nb (net-const 'O_NONBLOCK)])
    (when (< flags 0) (io-error "Configure blocking failed"))
    (when (< (libc-fcntl-int fd 4 (if (eqv? blocking 1) (fxlogand flags (fxlognot nb)) (fxlogor flags nb))) 0)
      (io-error "Configure blocking failed"))
    (void)))

;; --- waking a blocked thread: sun.nio.ch.NativeThread ----------------------------------
;; A thread in a blocking socket call (accept, read, poll) is woken when
;; another thread closes the socket: NioSocketImpl and the channels remember
;; the reader's and writer's native thread (current0), pre-close the fd, and
;; signal them (signal0); the interrupted call returns EINTR (-3, or 0 from
;; poll), the Java code sees the socket closed and throws "Socket closed" or
;; AsynchronousCloseException. As the JDK's NativeThread.c: the thread id is
;; pthread_self, the signal SIGIO on macOS and SIGRTMAX-2 (62) on Linux,
;; delivered with pthread_kill to a handler installed without SA_RESTART so
;; the call is not restarted. The handler has to be C (a Scheme procedure
;; can't run on a thread that is inside a __collect_safe call), and does
;; nothing: libc's getpid, async-signal-safe, which takes no argument, so
;; the signal number arrives in a register it never reads (the arm64 and
;; x86_64 conventions let a callee ignore extra arguments). Chez's own
;; keyboard interrupt is a signal handled while Scheme code runs, so a
;; signal landing on a thread in Scheme code is nothing new to it.
(define wake-signal (if net-macos? 23 62))
(deflibc libc-pthread-self "pthread_self" #f () uptr)
(deflibc libc-pthread-kill "pthread_kill" #f (uptr int) int)
(deflibc libc-sigaction "sigaction" #f (int uptr uptr) int)
(define (install-wake-handler!)
  ;; struct sigaction: the handler at 0, sa_mask then sa_flags (macOS: a
  ;; 4-byte mask at 8, flags at 12, 16 bytes; glibc: a 128-byte mask at 8,
  ;; flags at 136, sa_restorer at 144, 152 bytes), everything else zero
  (let* ([size (if net-macos? 16 152)] [sa (foreign-alloc size)])
    (do ([i 0 (fx+ i 1)]) ((fx= i size)) (foreign-set! 'unsigned-8 sa i 0))
    (foreign-set! 'uptr sa 0 (foreign-entry "getpid"))
    (let* ([r (libc-sigaction wake-signal sa 0)] [e (errno)])
      (foreign-free sa)
      (when (< r 0) (jthrow "java/lang/InternalError" (string-append "sigaction: " (strerror-text e)))))))
(defnative "sun/nio/ch/NativeThread.init()V" () (install-wake-handler!))
(defnative "sun/nio/ch/NativeThread.current0()J" () (libc-pthread-self))
(defnative "sun/nio/ch/NativeThread.signal0(J)V" (tid)
  (let ([r (libc-pthread-kill tid wake-signal)])
    ;; ESRCH (3) and EINVAL (22): the thread is gone, nothing to wake (glibc
    ;; before 2.34 may fault instead of answering ESRCH for an exited
    ;; thread; the JDK has the same exposure, and its locks make the window
    ;; small)
    (unless (memv r '(0 3 22)) (jthrow "java/lang/InternalError" (string-append "pthread_kill: " (strerror-text r))))
    (void)))
(defnative "sun/nio/ch/NativeThread.supportPendingSignals0()Z" () 0)

;; --- java.net.InetAddress ------------------------------------------------------------
(defnative "java/net/InetAddress.init()V" () (void))
(defnative "java/net/Inet4Address.init()V" () (void))
(defnative "java/net/Inet6Address.init()V" () (void))
(defnative "java/net/NetworkInterface.init()V" () (void))
(defnative "java/net/InetAddress.isIPv4Available()Z" () 1)
(defnative "java/net/InetAddress.isIPv6Supported()Z" () 0)

(define (c-string s)   ; a fresh NUL-terminated copy in foreign memory
  (let* ([bv (string->utf8 s)] [n (bytevector-length bv)] [p (foreign-alloc (fx+ n 1))])
    (do ([i 0 (fx+ i 1)]) ((fx= i n)) (foreign-set! 'unsigned-8 p i (bytevector-u8-ref bv i)))
    (foreign-set! 'unsigned-8 p n 0)
    p))
(define (c-string-text p)   ; the text at p, up to its NUL
  (let loop ([i 0] [acc '()])
    (let ([b (foreign-ref 'unsigned-8 p i)])
      (if (fx= b 0) (utf8->string (u8-list->bytevector (reverse acc))) (loop (fx+ i 1) (cons b acc))))))

(defnative "java/net/Inet4AddressImpl.getLocalHostName()Ljava/lang/String;" (this)
  (let ([buf (foreign-alloc 256)])
    (let ([r (libc-gethostname buf 255)])
      (foreign-set! 'unsigned-8 buf 255 0)
      (let ([name (if (< r 0) "localhost" (c-string-text buf))])
        (foreign-free buf)
        (jstring name)))))
;; getaddrinfo for IPv4 stream addresses, the GIL given up while it waits on
;; the resolver; the JDK's message is "host: gai_strerror"
(define (lookup-ipv4 host)
  (let ([cname (c-string host)] [hints (foreign-alloc 48)] [res (foreign-alloc 8)])
    (do ([i 0 (fx+ i 1)]) ((fx= i 48)) (foreign-set! 'unsigned-8 hints i 0))
    (foreign-set! 'int hints 4 2)   ; AF_INET
    (foreign-set! 'int hints 8 1)   ; SOCK_STREAM, one entry per address
    (foreign-set! 'uptr res 0 0)
    (let ([r (gil-released (lambda () (libc-getaddrinfo-blocking cname 0 hints res)))])
      (foreign-free cname) (foreign-free hints)
      (if (not (fx= r 0))
          (begin (foreign-free res)
                 (jthrow "java/net/UnknownHostException" (string-append host ": " (libc-gai-strerror r))))
          (let ([head (foreign-ref 'uptr res 0)])
            (foreign-free res)
            (let loop ([p head] [acc '()])
              (if (= p 0)
                  (begin (unless (= head 0) (libc-freeaddrinfo head))
                         (reverse acc))
                  (let ([family (foreign-ref 'int p 4)] [sa (foreign-ref 'uptr p (net-const 'ai-addr))])
                    (loop (foreign-ref 'uptr p 40)
                          (if (and (fx= family 2) (not (= sa 0)) (not (memv (sockaddr-ip sa) acc)))
                              (cons (sockaddr-ip sa) acc)
                              acc))))))))))
(defnative "java/net/Inet4AddressImpl.lookupAllHostAddr(Ljava/lang/String;)[Ljava/net/InetAddress;" (this host)
  (let* ([h (jstring->scheme (nn host))] [ips (lookup-ipv4 h)])
    (when (null? ips) (jthrow "java/net/UnknownHostException" h))
    (make-jarr "Ljava/net/InetAddress;" (list->vector (map (lambda (ip) (inet4 host ip)) ips)))))
(defnative "java/net/Inet4AddressImpl.getHostByAddr([B)Ljava/lang/String;" (this addr)
  (let* ([v (jarr-vec addr)]
         [ip (let loop ([i 0] [acc 0]) (if (fx= i 4) acc (loop (fx+ i 1) (fxlogor (fxsll acc 8) (fxlogand (vector-ref v i) 255)))))]
         [sa (foreign-alloc sockaddr-size)] [buf (foreign-alloc 1025)])
    (sockaddr-in! sa ip 0)
    (let ([r (gil-released (lambda () (libc-getnameinfo sa sockaddr-size buf 1025 0 0 (net-const 'NI_NAMEREQD))))])
      (foreign-free sa)
      (if (fx= r 0)
          (let ([name (c-string-text buf)]) (foreign-free buf) (jstring name))
          (begin (foreign-free buf) (jthrow "java/net/UnknownHostException" #f))))))

;; --- selectors: KQueue (macOS), EPoll and EventFD (Linux), IOUtil's pipe -----------------
;; The JDK's selectors keep their event arrays in Unsafe-allocated memory
;; and read them with Unsafe, so the natives only make the system calls, as
;; KQueue.c, EPoll.c and EventFD.c do. A wait with a timeout other than 0
;; gives the GIL up; a wakeup from another thread writes the selector's pipe
;; (macOS) or eventfd (Linux), and an interrupt goes through the same wakeup.
;; struct kevent on macOS: ident (uintptr_t) at 0, filter (int16) at 8, flags
;; (uint16) at 10, fflags, data, udata, 32 bytes. struct epoll_event is
;; packed on x86_64 (events at 0, data at 4, 12 bytes) and not on arm64
;; (data at 8, 16 bytes).
(define epoll-packed? (and (memq (machine-type) '(ta6le a6le)) #t))
(deflibc libc-pipe "pipe" #f (uptr) int)
(deflibc libc-kqueue (if net-macos? "kqueue" "no-kqueue-here") #f () int)
(deflibc libc-kevent (if net-macos? "kevent" "no-kevent-here") #f (int uptr int uptr int uptr) int)
(deflibc-blocking libc-kevent-blocking (if net-macos? "kevent" "no-kevent-here") (int uptr int uptr int uptr) int)
(deflibc libc-epoll-create1 (if net-macos? "no-epoll-here" "epoll_create1") #f (int) int)
(deflibc libc-epoll-ctl (if net-macos? "no-epoll-here" "epoll_ctl") #f (int int int uptr) int)
(deflibc libc-epoll-wait (if net-macos? "no-epoll-here" "epoll_wait") #f (int uptr int int) int)
(deflibc-blocking libc-epoll-wait-blocking (if net-macos? "no-epoll-here" "epoll_wait") (int uptr int int) int)
(deflibc libc-eventfd (if net-macos? "no-eventfd-here" "eventfd") #f (unsigned-32 int) int)

;; the IOException of JNU_ThrowIOExceptionWithLastError, whose message is
;; strerror's text alone (the C code's "what" is only its fallback)
(define (last-error-io e) (jthrow "java/io/IOException" (strerror-text e)))
;; a wait's result: the events, or -3 (IOStatus.INTERRUPTED) on EINTR
(define (wait-status r e)
  (cond [(fx>= r 0) r]
        [(fx= e 4) -3]
        [else (last-error-io e)]))

(defnative "sun/nio/ch/KQueue.keventSize()I" () 32)
(defnative "sun/nio/ch/KQueue.identOffset()I" () 0)
(defnative "sun/nio/ch/KQueue.filterOffset()I" () 8)
(defnative "sun/nio/ch/KQueue.flagsOffset()I" () 10)
(defnative "sun/nio/ch/KQueue.create()I" ()
  (let ([kq (libc-kqueue)])
    (when (< kq 0) (last-error-io (errno)))
    kq))
;; one change, applied at once; errno on failure (the JDK's caller decides)
(defnative "sun/nio/ch/KQueue.register(IIII)I" (kq fd filter flags)
  (let ([ev (foreign-alloc 32)])
    (do ([i 0 (fx+ i 8)]) ((fx= i 32)) (foreign-set! 'unsigned-64 ev i 0))
    (foreign-set! 'uptr ev 0 fd)
    (foreign-set! 'integer-16 ev 8 filter)
    (foreign-set! 'unsigned-16 ev 10 (fxlogand flags #xffff))
    (let* ([r (restartable (lambda () (libc-kevent kq ev 1 0 0 0)))] [e (errno)])
      (foreign-free ev)
      (if (< r 0) e 0))))
;; timeout in milliseconds, negative for none
(defnative "sun/nio/ch/KQueue.poll(IJIJ)I" (kq addr n timeout)
  (let ([ts (and (>= timeout 0) (foreign-alloc 16))])
    (when ts
      (foreign-set! 'long ts 0 (quotient timeout 1000))
      (foreign-set! 'long ts 8 (* (remainder timeout 1000) 1000000)))
    (let* ([r (if (eqv? timeout 0)
                  (libc-kevent kq 0 0 addr n ts)
                  (released-call* (lambda () (libc-kevent-blocking kq 0 0 addr n (or ts 0)))))]
           [e (errno)])
      (when ts (foreign-free ts))
      (wait-status r e))))

(defnative "sun/nio/ch/EPoll.eventSize()I" () (if epoll-packed? 12 16))
(defnative "sun/nio/ch/EPoll.eventsOffset()I" () 0)
(defnative "sun/nio/ch/EPoll.dataOffset()I" () (if epoll-packed? 4 8))
(defnative "sun/nio/ch/EPoll.create()I" ()
  (let ([epfd (libc-epoll-create1 #x80000)])   ; EPOLL_CLOEXEC
    (when (< epfd 0) (last-error-io (errno)))
    epfd))
;; the data word carries the fd, as EPoll.c sets event.data.fd
(defnative "sun/nio/ch/EPoll.ctl(IIII)I" (epfd op fd events)
  (let ([ev (foreign-alloc 16)])
    (foreign-set! 'unsigned-64 ev 0 0) (foreign-set! 'unsigned-64 ev 8 0)
    (foreign-set! 'unsigned-32 ev 0 (logand events #xffffffff))
    (foreign-set! 'int ev (if epoll-packed? 4 8) fd)
    (let* ([r (restartable (lambda () (libc-epoll-ctl epfd op fd ev)))] [e (errno)])
      (foreign-free ev)
      (if (< r 0) e 0))))
(defnative "sun/nio/ch/EPoll.wait(IJII)I" (epfd addr n timeout)
  (let* ([r (if (eqv? timeout 0)
                (libc-epoll-wait epfd addr n 0)
                (released-call* (lambda () (libc-epoll-wait-blocking epfd addr n timeout))))]
         [e (errno)])
    (wait-status r e)))
(defnative "sun/nio/ch/EventFD.eventfd0()I" ()
  (let ([efd (libc-eventfd 0 0)])
    (when (< efd 0) (last-error-io (errno)))
    efd))
(defnative "sun/nio/ch/EventFD.set0(I)I" (efd)
  (let ([buf (foreign-alloc 8)])
    (foreign-set! 'unsigned-64 buf 0 1)
    (let* ([r (libc-write efd buf 8)] [e (errno)])
      (foreign-free buf)
      (when (< r 0) (last-error-io e))
      r)))

;; the read end in the high 32 bits, the write end in the low, as IOUtil.c
(defnative "sun/nio/ch/IOUtil.makePipe(Z)J" (blocking)
  (let ([fds (foreign-alloc 8)])
    (let* ([r (libc-pipe fds)] [e (errno)] [rd (foreign-ref 'int fds 0)] [wr (foreign-ref 'int fds 4)])
      (foreign-free fds)
      (when (< r 0) (last-error-io e))
      (unless (eqv? blocking 1)
        (let ([nb (net-const 'O_NONBLOCK)])
          (for-each (lambda (fd)
                      (let ([flags (libc-fcntl-int fd 3 0)])
                        (when (or (< flags 0) (< (libc-fcntl-int fd 4 (fxlogor flags nb)) 0))
                          (let ([e (errno)])
                            (libc-close rd) (libc-close wr)
                            (last-error-io e)))))
                    (list rd wr))))
      (logor (ash rd 32) wr))))
(defnative "sun/nio/ch/IOUtil.write1(IB)I" (fd b)
  (let ([buf (foreign-alloc 1)])
    (foreign-set! 'integer-8 buf 0 b)
    (let* ([r (libc-write fd buf 1)] [e (errno)])
      (foreign-free buf)
      (cond [(fx>= r 0) r]
            [(fx= e (net-const 'EAGAIN)) -2]
            [(fx= e 4) -3]
            [else (last-error-io e)]))))
;; read 128 bytes at a time until less comes back; whether anything was read
(defnative "sun/nio/ch/IOUtil.drain(I)Z" (fd)
  (let ([buf (foreign-alloc 128)])
    (let loop ([total 0])
      (let* ([r (libc-read fd buf 128)] [e (errno)])
        (cond [(fx= r 128) (loop (fx+ total 128))]
              [(and (< r 0) (not (fx= e (net-const 'EAGAIN))))
               (foreign-free buf) (last-error-io e)]
              [else (foreign-free buf) (if (fx> (fx+ total (fxmax r 0)) 0) 1 0)])))))
(defnative "sun/nio/ch/IOUtil.drain1(I)I" (fd)
  (let ([buf (foreign-alloc 1)])
    (let* ([r (libc-read fd buf 1)] [e (errno)])
      (foreign-free buf)
      (cond [(fx>= r 0) r]
            [(fx= e (net-const 'EAGAIN)) 0]
            [(fx= e 4) -3]
            [else (last-error-io e)]))))
