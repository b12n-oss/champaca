;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Sockets: the natives of java.net (InetAddress and Inet4AddressImpl) and of
;; sun.nio.ch's Net, SocketDispatcher, UnixDispatcher and IOUtil's blocking
;; mode, through libc as files.ss does files (its deflibc bindings, errno and
;; GIL helpers are reused). java.net.Socket and ServerSocket run on
;; NioSocketImpl, so a blocking socket is a real socket fd configured
;; non-blocking when a timeout is set, polled with poll(2).
;;
;; IPv6 as the JDK has it (net_util.c ipv6_available): available when an
;; AF_INET6 socket can be made (on Linux, when /proc/net/if_inet6 lists an
;; address) and java.net.preferIPv4Stack isn't true. The JDK then makes every
;; socket AF_INET6 with IPV6_V6ONLY off, an IPv4 address going as
;; ::ffff:a.b.c.d, and resolves through Inet6AddressImpl; without it every
;; address is a sockaddr_in and an Inet6Address is "Protocol family
;; unavailable". Blocking calls (connect, accept,
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
    (ENETUNREACH . 51) (EAFNOSUPPORT . 47) (ai-addr . 32) (AF_INET6 . 30) (IPV6_V6ONLY . 27)
    (ENOPROTOOPT . 42) (EOPNOTSUPP . 102) (IP_ADD_MEMBERSHIP . 12) (IP_DROP_MEMBERSHIP . 13)
    (IP_ADD_SOURCE_MEMBERSHIP . 70) (IP_DROP_SOURCE_MEMBERSHIP . 71) (IPV6_JOIN_GROUP . 12) (IPV6_LEAVE_GROUP . 13)))
(define net-consts-linux
  '((SOL_SOCKET . 1) (SO_REUSEADDR . 2) (SO_REUSEPORT . 15) (SO_ERROR . 4) (SO_LINGER . 13)
    (SO_NOSIGPIPE . #f) (MSG_NOSIGNAL . #x4000) (FIONREAD . #x541b) (O_NONBLOCK . 2048) (NI_NAMEREQD . 8)
    (EAGAIN . 11) (EINPROGRESS . 115) (EALREADY . 114) (EISCONN . 106) (ECONNREFUSED . 111) (ECONNRESET . 104)
    (ETIMEDOUT . 110) (ENOTCONN . 107) (EHOSTUNREACH . 113) (EADDRINUSE . 98) (EADDRNOTAVAIL . 99) (EPROTO . 71)
    (ENETUNREACH . 101) (EAFNOSUPPORT . 97) (ai-addr . 24) (AF_INET6 . 10) (IPV6_V6ONLY . 26)
    (ENOPROTOOPT . 92) (EOPNOTSUPP . 95) (IP_ADD_MEMBERSHIP . 35) (IP_DROP_MEMBERSHIP . 36)
    (IP_ADD_SOURCE_MEMBERSHIP . 39) (IP_DROP_SOURCE_MEMBERSHIP . 40) (IP_BLOCK_SOURCE . 38) (IP_UNBLOCK_SOURCE . 37)
    (IPV6_JOIN_GROUP . 20) (IPV6_LEAVE_GROUP . 21) (MCAST_JOIN_SOURCE_GROUP . 46) (MCAST_LEAVE_SOURCE_GROUP . 47)
    (MCAST_BLOCK_SOURCE . 43) (MCAST_UNBLOCK_SOURCE . 44)))
(define (net-const name)
  (cdr (or (assq name (if net-macos? net-consts-macos net-consts-linux)) (error 'champaca "no net constant" name))))
;; the same on both: AF_INET 2, SOCK_STREAM 1, SOCK_DGRAM 2, EINTR 4, EPIPE 32,
;; EACCES 13, F_GETFL 3, F_SETFL 4, MSG_OOB 1, POLLIN 1, POLLOUT 4, POLLERR 8,
;; POLLHUP 16, POLLNVAL 32; struct addrinfo: ai_family at 4, ai_socktype at 8,
;; ai_next at 40, 48 bytes; struct pollfd {int fd; short events, revents};
;; struct linger {int l_onoff; int l_linger}; sockaddr_in 16 bytes with the
;; port at 2 and the address at 4, both big-endian; sockaddr_in6 28 bytes, the
;; port at 2, flow info at 4, the address at 8, the scope id at 24;
;; IPPROTO_IPV6 41

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
(define (ia-v4-raw ia) (fxlogand (jslot (ia-holder ia) (jfield-index "java/net/InetAddress$InetAddressHolder" "address")) #xffffffff))
;; an InetAddress's IPv4 address as a 32-bit unsigned number
(define (ia-ipv4 ia)
  (unless (fx= (ia-family ia) 1) (jthrow "java/net/SocketException" "Protocol family unavailable"))
  (ia-v4-raw ia))
(define (ia-holder6 ia) (jslot (nn ia) (jfield-index "java/net/Inet6Address" "holder6")))
(define (holder6-field name) (jfield-index "java/net/Inet6Address$Inet6AddressHolder" name))
;; an Inet6Address's 16 bytes as a list, and its scope id (0 when none)
(define (ia-ipv6-bytes ia)
  (map (lambda (b) (fxlogand b 255)) (vector->list (jarr-vec (jslot (ia-holder6 ia) (holder6-field "ipaddress"))))))
(define (ia-scope ia) (jslot (ia-holder6 ia) (holder6-field "scope_id")))
(define (inet4 host ip)   ; host a jstring or #f
  (jinit "java/net/Inet4Address")
  (construct "java/net/Inet4Address" "(Ljava/lang/String;I)V" host (i32 ip)))
;; an Inet6Address of 16 bytes; a scope of 0 is none, as NET_SockaddrToInetAddress
;; sets it (so ::1 prints without a %0)
(define (inet6 host bytes scope)
  (jinit "java/net/Inet6Address")
  (construct "java/net/Inet6Address" "(Ljava/lang/String;[BI)V" host
             (make-jarr "B" (list->vector (map i2b bytes))) (if (eqv? scope 0) -1 scope)))
;; an IPv4-mapped address, ::ffff:a.b.c.d, is the Inet4Address a.b.c.d
(define (v4-mapped? bytes)
  (let loop ([i 0] [bs bytes])
    (cond [(fx= i 10) (and (fx= (car bs) 255) (fx= (cadr bs) 255))]
          [(fx= (car bs) 0) (loop (fx+ i 1) (cdr bs))]
          [else #f])))
(define (bytes->ip4 bs) (fold-left (lambda (acc b) (fxlogor (fxsll acc 8) b)) 0 bs))
(define (inet-socket-address-of ia port)
  (construct "java/net/InetSocketAddress" "(Ljava/net/InetAddress;I)V" ia port))
(define (inet-socket-address ip port) (inet-socket-address-of (inet4 #f ip) port))

(define sockaddr-size 16)
(define sockaddr6-size 28)
(define (sockaddr-family sa) (if net-macos? (foreign-ref 'unsigned-8 sa 1) (foreign-ref 'unsigned-16 sa 0)))
(define (sockaddr-head! sa size family)
  (do ([i 0 (fx+ i 1)]) ((fx= i size)) (foreign-set! 'unsigned-8 sa i 0))
  (if net-macos?
      (begin (foreign-set! 'unsigned-8 sa 0 size) (foreign-set! 'unsigned-8 sa 1 family))
      (foreign-set! 'unsigned-16 sa 0 family)))
(define (sockaddr-port! sa port)
  (foreign-set! 'unsigned-8 sa 2 (fxlogand (fxsrl port 8) 255))
  (foreign-set! 'unsigned-8 sa 3 (fxlogand port 255)))
(define (sockaddr-in! sa ip port)
  (sockaddr-head! sa sockaddr-size 2)
  (sockaddr-port! sa port)
  (do ([i 0 (fx+ i 1)]) ((fx= i 4))
    (foreign-set! 'unsigned-8 sa (fx+ 4 i) (fxlogand (fxsrl ip (fx* 8 (fx- 3 i))) 255))))
(define (sockaddr-in6! sa bytes scope port)
  (sockaddr-head! sa sockaddr6-size (net-const 'AF_INET6))
  (sockaddr-port! sa port)
  (let loop ([i 0] [bs bytes]) (unless (null? bs) (foreign-set! 'unsigned-8 sa (fx+ 8 i) (car bs)) (loop (fx+ i 1) (cdr bs))))
  (foreign-set! 'unsigned-32 sa 24 scope))
(define (sockaddr-port sa) (fxlogor (fxsll (foreign-ref 'unsigned-8 sa 2) 8) (foreign-ref 'unsigned-8 sa 3)))
(define (sockaddr-ip sa)
  (let loop ([i 0] [acc 0])
    (if (fx= i 4) acc (loop (fx+ i 1) (fxlogor (fxsll acc 8) (foreign-ref 'unsigned-8 sa (fx+ 4 i)))))))
(define (sockaddr6-bytes sa)
  (let loop ([i 15] [acc '()]) (if (fx< i 0) acc (loop (fx- i 1) (cons (foreign-ref 'unsigned-8 sa (fx+ 8 i)) acc)))))
;; the InetAddress a sockaddr holds, as NET_SockaddrToInetAddress makes it
(define (sockaddr->inet sa)
  (if (fx= (sockaddr-family sa) (net-const 'AF_INET6))
      (let ([bs (sockaddr6-bytes sa)])
        (if (v4-mapped? bs)
            (inet4 #f (bytes->ip4 (list-tail bs 12)))
            (inet6 #f bs (foreign-ref 'unsigned-32 sa 24))))
      (inet4 #f (sockaddr-ip sa))))
;; ia:port as the socket's family has it (v6 true for an AF_INET6 socket: an
;; IPv4 address mapped), into sa (sockaddr6-size bytes); answers its length.
;; An Inet6Address for an IPv4 socket is "Protocol family unavailable"
(define (sockaddr-for! sa ia port v6)
  (cond [(not v6) (sockaddr-in! sa (ia-ipv4 ia) port) sockaddr-size]
        [(fx= (ia-family ia) 1)
         ;; 0.0.0.0 is ::, the dual-stack wildcard (::ffff:0.0.0.0 would hear
         ;; IPv4 only), as NET_InetAddressToSockaddr maps it
         (let ([ip (ia-v4-raw ia)])
           (sockaddr-in6! sa (if (fx= ip 0)
                                 (make-list 16 0)
                                 (append '(0 0 0 0 0 0 0 0 0 0 255 255)
                                         (list (fxsrl ip 24) (fxlogand (fxsrl ip 16) 255) (fxlogand (fxsrl ip 8) 255) (fxlogand ip 255))))
                          0 port)
           sockaddr6-size)]
        [else (sockaddr-in6! sa (ia-ipv6-bytes ia) (ia-scope ia) port) sockaddr6-size]))
;; (k sa len) with a sockaddr for ia:port in fresh foreign memory, freed after
(define (with-sockaddr ia port v6 k)
  (let ([sa (foreign-alloc sockaddr6-size)])
    (let ([r (guard (e [#t (foreign-free sa) (raise e)]) (k sa (sockaddr-for! sa ia port v6)))])
      (foreign-free sa) r)))
;; (k sa len) with an empty sockaddr, room for either family, and its length cell,
;; for getsockname and the like
(define (with-sockaddr-out k)
  (let ([sa (foreign-alloc sockaddr6-size)] [len (foreign-alloc 4)])
    (do ([i 0 (fx+ i 1)]) ((fx= i sockaddr6-size)) (foreign-set! 'unsigned-8 sa i 0))
    (foreign-set! 'unsigned-32 len 0 sockaddr6-size)
    (let ([r (guard (e [#t (foreign-free sa) (foreign-free len) (raise e)]) (k sa len))])
      (foreign-free sa) (foreign-free len) r)))

;; whether IPv6 is there, as net_util.c's ipv6_available decides it, once
(define net-ipv6-answer 'unknown)
(define (prefer-ipv4-stack?)
  (let ([v ((method-proc "java/lang/System" "getProperty(Ljava/lang/String;)Ljava/lang/String;") (jstring "java.net.preferIPv4Stack"))])
    (and v (string-ci=? (jstring->scheme v) "true"))))
(define (net-ipv6?)
  (when (eq? net-ipv6-answer 'unknown)
    (set! net-ipv6-answer
          (and (let ([fd (libc-socket (net-const 'AF_INET6) 1 0)])
                 (and (fx>= fd 0) (begin (libc-close fd) #t)))
               (or net-macos?
                   (guard (e [#t #f])
                     (and (file-exists? "/proc/net/if_inet6")
                          (not (eof-object? (call-with-input-file "/proc/net/if_inet6" get-line))))))
               (not (prefer-ipv4-stack?)))))
  net-ipv6-answer)

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
(defnet "isIPv6Available0()Z" (lambda () (if (net-ipv6?) 1 0)))
(defnet "isReusePortAvailable0()Z" (lambda () 1))
(defnet "isExclusiveBindAvailable()I" (lambda () -1))
;; false on both, as HotSpot 25.0.2 answers (champaca said true on macOS)
(defnet "shouldShutdownWriteBeforeClose0()Z" (lambda () 0))
(defnet "shouldSetBothIPv4AndIPv6Options0()Z" (lambda () (if net-macos? 0 1)))
;; as HotSpot 25.0.2 answers them: an IPv6 socket can join an IPv4 group on
;; both, IPV6_JOIN_GROUP takes an IPv4-mapped group on macOS only, and IPV6_
;; options work on an IPv6 socket bound to an IPv4 address on both
(defnet "canIPv6SocketJoinIPv4Group0()Z" (lambda () 1))
(defnet "canJoin6WithIPv4Group0()Z" (lambda () (if net-macos? 1 0)))
(defnet "canUseIPv6OptionsWithIPv4LocalAddress0()Z" (lambda () 1))
(defnet "pollinValue()S" (lambda () 1))
(defnet "polloutValue()S" (lambda () 4))
(defnet "pollerrValue()S" (lambda () 8))
(defnet "pollhupValue()S" (lambda () 16))
(defnet "pollnvalValue()S" (lambda () 32))
(defnet "pollconnValue()S" (lambda () 4))

;; as the JDK's socket0 sets a datagram socket up: on macOS a send buffer of
;; at least 65507 bytes, since net.inet.udp.maxdgram (9216) caps a datagram
;; at the buffer's size, best effort as there (the JDK checks neither call);
;; on Linux IP_MULTICAST_ALL off (49), so a socket only hears the groups it
;; joined, where only a kernel without the option (ENOPROTOOPT, 92) is let
;; pass and any other failure is the JDK's IOException; an IPv6 one sets
;; IPV6_MULTICAST_ALL (29) instead and IPV6_MULTICAST_HOPS (18) to 1, as
;; Linux's default is the route's
(define (datagram-socket! fd v6)
  (if net-macos?
      (let ([buf (foreign-alloc 4)] [len (foreign-alloc 4)])
        (foreign-set! 'int buf 0 0)
        (foreign-set! 'unsigned-32 len 0 4)
        (when (and (fx= 0 (libc-getsockopt fd (net-const 'SOL_SOCKET) #x1001 buf len))
                   (< (foreign-ref 'int buf 0) 65507))
          (foreign-set! 'int buf 0 65507)
          (libc-setsockopt fd (net-const 'SOL_SOCKET) #x1001 buf 4))
        (foreign-free buf) (foreign-free len))
      (let ([buf (foreign-alloc 4)])
        (foreign-set! 'int buf 0 0)
        (let* ([r (libc-setsockopt fd (if v6 41 0) (if v6 29 49) buf 4)] [e (errno)])
          (when (and (< r 0) (not (fx= e 92)))
            (foreign-free buf)
            (libc-close fd)
            (jthrow "java/io/IOException" (strerror-text e)))
          (when v6
            (foreign-set! 'int buf 0 1)
            (let* ([r (libc-setsockopt fd 41 18 buf 4)] [e (errno)])
              (when (< r 0)
                (foreign-free buf)
                (libc-close fd)
                (jthrow "java/io/IOException" (strerror-text e)))))
          (foreign-free buf)))))
(defnet "socket0(ZZZZ)I"
  (lambda (ipv6 stream reuse fast)
    (let* ([v6 (eqv? ipv6 1)]
           [fd (check-socket (libc-socket (if v6 (net-const 'AF_INET6) 2) (if (eqv? stream 1) 1 2) 0))])
      ;; the new fd doesn't leak when its option can't be set
      (guard (e [#t (libc-close fd) (raise e)])
        ;; dual stack: an IPv4 peer reaches an IPv6 socket through a mapped address
        (when v6 (set-int-option! fd 41 (net-const 'IPV6_V6ONLY) 0))
        (when (eqv? reuse 1)
          (set-int-option! fd (net-const 'SOL_SOCKET) (net-const 'SO_REUSEADDR) 1)))
      (quiet-pipe! fd)
      (unless (eqv? stream 1) (datagram-socket! fd v6))
      fd)))
(defnet "bind0(Ljava/io/FileDescriptor;ZZLjava/net/InetAddress;I)V"
  (lambda (fdo ipv6 excl ia port)
    (with-sockaddr ia port (eqv? ipv6 1) (lambda (sa len) (check-socket (libc-bind (net-fd fdo) sa len)) (void)))))
(defnet "listen(Ljava/io/FileDescriptor;I)V"
  (lambda (fdo backlog) (check-socket (libc-listen (net-fd fdo) backlog)) (void)))
;; 1 connected; -2 in progress (a non-blocking socket); -3 interrupted
(defnet "connect0(ZLjava/io/FileDescriptor;Ljava/net/InetAddress;I)I"
  (lambda (ipv6 fdo ia port)
    (with-sockaddr ia port (eqv? ipv6 1)
      (lambda (sa len)
        (let ([r (released-call* (lambda () (libc-connect-blocking (net-fd fdo) sa len)))])
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
                    (vector-set! (jarr-vec isaa) 0 (inet-socket-address-of (sockaddr->inet sa) (sockaddr-port sa)))
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
  (lambda (fdo) (local-sockaddr (net-fd fdo) sockaddr->inet)))
(defnet "remotePort(Ljava/io/FileDescriptor;)I" (lambda (fdo) (peer-sockaddr (net-fd fdo) sockaddr-port)))
(defnet "remoteInetAddress(Ljava/io/FileDescriptor;)Ljava/net/InetAddress;"
  (lambda (fdo) (peer-sockaddr (net-fd fdo) sockaddr->inet)))
;; IP_MULTICAST_TTL and IP_MULTICAST_LOOP are one byte, as the JDK passes them
;; when it says they may need converting (macOS 10 and 11, Linux 33 and 34)
(define (byte-option? conv level opt)
  (and (eqv? conv 1) (fx= level 0) (memv opt (if net-macos? '(10 11) '(33 34)))))
(define (get-byte-option fd level opt)
  (let ([buf (foreign-alloc 4)] [len (foreign-alloc 4)])
    (foreign-set! 'unsigned-8 buf 0 0)
    (foreign-set! 'unsigned-32 len 0 1)
    (let* ([r (libc-getsockopt fd level opt buf len)] [e (errno)] [v (foreign-ref 'unsigned-8 buf 0)])
      (foreign-free buf) (foreign-free len)
      (after-free r e socket-error)
      v)))
(define (set-byte-option! fd level opt v)
  (let ([buf (foreign-alloc 1)])
    (foreign-set! 'unsigned-8 buf 0 (fxlogand v 255))
    (let* ([r (libc-setsockopt fd level opt buf 1)] [e (errno)])
      (foreign-free buf)
      (after-free r e socket-error))))
(defnet "getIntOption0(Ljava/io/FileDescriptor;ZII)I"
  (lambda (fdo conv level opt)
    (cond [(linger? level opt) (get-linger (net-fd fdo))]
          [(byte-option? conv level opt) (get-byte-option (net-fd fdo) level opt)]
          [else (get-int-option (net-fd fdo) level opt)])))
(defnet "setIntOption0(Ljava/io/FileDescriptor;ZIIIZ)V"
  (lambda (fdo conv level opt arg ipv6)
    (cond [(linger? level opt) (set-linger! (net-fd fdo) arg)]
          [(byte-option? conv level opt) (set-byte-option! (net-fd fdo) level opt arg)]
          [else (set-int-option! (net-fd fdo) level opt arg)])
    (void)))
;; IP_MULTICAST_IF (macOS 9, Linux 32) as a struct in_addr, the JDK's int in
;; host order; 0 is any interface
(define ip-multicast-if (if net-macos? 9 32))
;; a struct in_addr at off from the JDK's host-order int
(define (put-in4! buf off ip)
  (do ([i 0 (fx+ i 1)]) ((fx= i 4)) (foreign-set! 'unsigned-8 buf (fx+ off i) (fxlogand (ash ip (fx* -8 (fx- 3 i))) 255))))
(defnet "getInterface4(Ljava/io/FileDescriptor;)I"
  (lambda (fdo)
    (let ([buf (foreign-alloc 4)] [len (foreign-alloc 4)])
      (foreign-set! 'unsigned-32 buf 0 0)
      (foreign-set! 'unsigned-32 len 0 4)
      (let* ([r (libc-getsockopt (net-fd fdo) 0 ip-multicast-if buf len)] [e (errno)]
             [ip (let loop ([i 0] [acc 0]) (if (fx= i 4) acc (loop (fx+ i 1) (fxlogor (fxsll acc 8) (foreign-ref 'unsigned-8 buf i)))))])
        (foreign-free buf) (foreign-free len)
        (after-free r e socket-error)
        (i32 ip)))))
(defnet "setInterface4(Ljava/io/FileDescriptor;I)V"
  (lambda (fdo interf)
    (let ([buf (foreign-alloc 4)])
      (put-in4! buf 0 interf)
      (let* ([r (libc-setsockopt (net-fd fdo) 0 ip-multicast-if buf 4)] [e (errno)])
        (foreign-free buf)
        (after-free r e socket-error)
        (void)))))
;; IPV6_MULTICAST_IF (macOS 9, Linux 17): an interface index, 0 for any
(define ipv6-multicast-if (if net-macos? 9 17))
(defnet "getInterface6(Ljava/io/FileDescriptor;)I"
  (lambda (fdo) (get-int-option (net-fd fdo) 41 ipv6-multicast-if)))
(defnet "setInterface6(Ljava/io/FileDescriptor;I)V"
  (lambda (fdo index) (set-int-option! (net-fd fdo) 41 ipv6-multicast-if index) (void)))
;; Group membership and source filters, as Net.c's joinOrDrop4/6 and
;; blockOrUnblock4/6 set them. IPPROTO_IP's requests are struct ip_mreq (group,
;; interface) and struct ip_mreq_source, whose three addresses macOS lays out
;; group, source, interface and glibc group, interface, source. IPPROTO_IPV6's
;; are struct ipv6_mreq (the address, the interface index at 16) and, on Linux,
;; struct group_source_req (the index, then sockaddr_in6s for group and source
;; at 8 and 136, 264 bytes). macOS's JDK filters sources only through an IPv4
;; join on an IPv4 socket and answers IOS_UNAVAILABLE (-2) for the rest, which
;; is also the answer to a join or a block the kernel has no option for
;; (ENOPROTOOPT, EOPNOTSUPP). macOS retries one ENOMEM, a kernel bug the JDK
;; works around. A failed join or drop says "<strerror> (setsockopt failed)",
;; a failed block or unblock strerror's text alone.
(define (zeroed-buf n)
  (let ([b (foreign-alloc n)]) (do ([i 0 (fx+ i 1)]) ((fx= i n) b) (foreign-set! 'unsigned-8 b i 0))))
(define (put-bytes! buf off bytes)
  (let loop ([i off] [bs bytes]) (unless (null? bs) (foreign-set! 'unsigned-8 buf i (car bs)) (loop (fx+ i 1) (cdr bs)))))
(define (membership! fdo level opt len fill! may-be-unavailable message)
  (let ([buf (zeroed-buf len)] [fd (net-fd fdo)])
    (fill! buf)
    (let* ([r (libc-setsockopt fd level opt buf len)]
           [r (if (and (< r 0) net-macos? (fx= (errno) 12)) (libc-setsockopt fd level opt buf len) r)]
           [e (errno)])
      (foreign-free buf)
      (cond [(>= r 0) 0]
            [(and may-be-unavailable (memv e (list (net-const 'ENOPROTOOPT) (net-const 'EOPNOTSUPP)))) -2]
            [message (jthrow (socket-exception-class e) (string-append (strerror-text e) " (" message ")"))]
            [else (socket-error e)]))))
;; an ip_mreq_source: group at 0, then source and interface in the system's order
(define (fill-mreq-source! buf group interf source)
  (put-in4! buf 0 group)
  (put-in4! buf (if net-macos? 4 8) source)
  (put-in4! buf (if net-macos? 8 4) interf))
(define (fill-group-source-req! buf group index source)
  (foreign-set! 'unsigned-32 buf 0 index)
  (foreign-set! 'unsigned-16 buf 8 (net-const 'AF_INET6))
  (put-bytes! buf 16 (jbytes-list group))
  (foreign-set! 'unsigned-16 buf 136 (net-const 'AF_INET6))
  (put-bytes! buf 144 (jbytes-list source)))
(defnet "joinOrDrop4(ZLjava/io/FileDescriptor;III)I"
  (lambda (join fdo group interf source)
    (let ([join (eqv? join 1)])
      (if (eqv? source 0)
          (membership! fdo 0 (net-const (if join 'IP_ADD_MEMBERSHIP 'IP_DROP_MEMBERSHIP)) 8
                       (lambda (buf) (put-in4! buf 0 group) (put-in4! buf 4 interf)) join "setsockopt failed")
          (membership! fdo 0 (net-const (if join 'IP_ADD_SOURCE_MEMBERSHIP 'IP_DROP_SOURCE_MEMBERSHIP)) 12
                       (lambda (buf) (fill-mreq-source! buf group interf source)) join "setsockopt failed")))))
(defnet "blockOrUnblock4(ZLjava/io/FileDescriptor;III)I"
  (lambda (block fdo group interf source)
    (if net-macos?
        -2
        (let ([block (eqv? block 1)])
          (membership! fdo 0 (net-const (if block 'IP_BLOCK_SOURCE 'IP_UNBLOCK_SOURCE)) 12
                       (lambda (buf) (fill-mreq-source! buf group interf source)) block #f)))))
(defnet "joinOrDrop6(ZLjava/io/FileDescriptor;[BI[B)I"
  (lambda (join fdo group index source)
    (let ([join (eqv? join 1)])
      (cond [(not source)
             (membership! fdo 41 (net-const (if join 'IPV6_JOIN_GROUP 'IPV6_LEAVE_GROUP)) 20
                          (lambda (buf) (put-bytes! buf 0 (jbytes-list group)) (foreign-set! 'unsigned-32 buf 16 index))
                          join "setsockopt failed")]
            [net-macos? -2]
            [else (membership! fdo 41 (net-const (if join 'MCAST_JOIN_SOURCE_GROUP 'MCAST_LEAVE_SOURCE_GROUP)) 264
                               (lambda (buf) (fill-group-source-req! buf group index source)) join "setsockopt failed")]))))
(defnet "blockOrUnblock6(ZLjava/io/FileDescriptor;[BI[B)I"
  (lambda (block fdo group index source)
    (if net-macos?
        -2
        (let ([block (eqv? block 1)])
          (membership! fdo 41 (net-const (if block 'MCAST_BLOCK_SOURCE 'MCAST_UNBLOCK_SOURCE)) 264
                       (lambda (buf) (fill-group-source-req! buf group index source)) block #f)))))
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
(defnative "java/net/InetAddress.isIPv6Supported()Z" () (if (net-ipv6?) 1 0))

(define (c-string s)   ; a fresh NUL-terminated copy in foreign memory
  (let* ([bv (string->utf8 s)] [n (bytevector-length bv)] [p (foreign-alloc (fx+ n 1))])
    (do ([i 0 (fx+ i 1)]) ((fx= i n)) (foreign-set! 'unsigned-8 p i (bytevector-u8-ref bv i)))
    (foreign-set! 'unsigned-8 p n 0)
    p))
(define (c-string-text p)   ; the text at p, up to its NUL
  (let loop ([i 0] [acc '()])
    (let ([b (foreign-ref 'unsigned-8 p i)])
      (if (fx= b 0) (utf8->string (u8-list->bytevector (reverse acc))) (loop (fx+ i 1) (cons b acc))))))

(define (local-host-name)
  (let ([buf (foreign-alloc 256)])
    (let ([r (libc-gethostname buf 255)])
      (foreign-set! 'unsigned-8 buf 255 0)
      (let ([name (if (< r 0) "localhost" (c-string-text buf))])
        (foreign-free buf)
        (jstring name)))))
(defnative "java/net/Inet4AddressImpl.getLocalHostName()Ljava/lang/String;" (this) (local-host-name))
;; getaddrinfo for stream addresses of family (2 AF_INET, AF_INET6, or 0 for
;; both), the GIL given up while it waits on the resolver; the JDK's message is
;; "host: gai_strerror". An address is an ip number (IPv4) or (bytes . scope),
;; in the resolver's order, each once
(define (lookup-addresses host family)
  (let ([cname (c-string host)] [hints (foreign-alloc 48)] [res (foreign-alloc 8)])
    (do ([i 0 (fx+ i 1)]) ((fx= i 48)) (foreign-set! 'unsigned-8 hints i 0))
    (foreign-set! 'int hints 4 family)
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
                  (let* ([fam (foreign-ref 'int p 4)] [sa (foreign-ref 'uptr p (net-const 'ai-addr))]
                         [a (cond [(= sa 0) #f]
                                  [(fx= fam 2) (sockaddr-ip sa)]
                                  [(fx= fam (net-const 'AF_INET6)) (cons (sockaddr6-bytes sa) (foreign-ref 'unsigned-32 sa 24))]
                                  [else #f])])
                    (loop (foreign-ref 'uptr p 40) (if (and a (not (member a acc))) (cons a acc) acc))))))))))
(define (lookup-ipv4 host) (lookup-addresses host 2))
(define (address->inet host a) (if (pair? a) (inet6 host (car a) (cdr a)) (inet4 host a)))
;; The JDK's macOS fallback (Inet4AddressImpl.c lookupIfLocalhost, JDK bug
;; 8170910): when the resolver fails for this machine's own hostname (a
;; network whose DNS doesn't know it), the IPv4 addresses of its interfaces
;; stand in, in getifaddrs order, the loopbacks left out unless they are all
;; there is. The JDK's Linux code has no such fallback, so neither does this.
(define (local-host-name? h)
  (let ([buf (foreign-alloc 256)])
    (let ([r (libc-gethostname buf 255)])
      (foreign-set! 'unsigned-8 buf 255 0)
      (let ([name (and (>= r 0) (c-string-text buf))])
        (foreign-free buf)
        (and name (string=? name h))))))
(define (local-interface-ips)
  (let ([head (foreign-alloc 8)])
    (foreign-set! 'uptr head 0 0)
    (let* ([r (libc-getifaddrs head)] [first (foreign-ref 'uptr head 0)])
      (foreign-free head)
      (if (< r 0)
          '()
          (let loop ([p first] [acc '()])   ; acc: (ip . loopback?), newest first
            (if (= p 0)
                (begin (unless (= first 0) (libc-freeifaddrs first))
                       (let* ([all (reverse acc)]
                              [real (filter (lambda (a) (not (cdr a))) all)])
                         (map car (if (null? real) all real))))
                (let ([sa (foreign-ref 'uptr p 24)] [next (foreign-ref 'uptr p 0)] [name (foreign-ref 'uptr p 8)])
                  (loop next
                        (if (and (not (= sa 0)) (fx= (sockaddr-family sa) 2)
                                 (not (= name 0)) (not (fx= (foreign-ref 'unsigned-8 name 0) 0)))
                            (cons (cons (sockaddr-ip sa) (fxlogtest (foreign-ref 'unsigned-32 p 16) 8)) acc)   ; IFF_LOOPBACK
                            acc)))))))))
(defnative "java/net/Inet4AddressImpl.lookupAllHostAddr(Ljava/lang/String;)[Ljava/net/InetAddress;" (this host)
  (let* ([h (jstring->scheme (nn host))]
         [ips (guard (e [(and (jobj? e) (jinstance? e "java/net/UnknownHostException") net-macos? (local-host-name? h))
                         (let ([local (local-interface-ips)])
                           (if (null? local) (raise e) local))])
                (lookup-ipv4 h))])
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

;; Inet6AddressImpl, which InetAddress uses for every lookup once IPv6 is there.
;; characteristics (InetAddress.addressesFor): 1 IPv4, 2 IPv6, 4 IPv4 first, 8
;; IPv6 first; getaddrinfo is asked for the families wanted and its order kept
;; unless one family goes first, as Inet6AddressImpl.c does
(defnative "java/net/Inet6AddressImpl.getLocalHostName()Ljava/lang/String;" (this) (local-host-name))
(defnative "java/net/Inet6AddressImpl.lookupAllHostAddr(Ljava/lang/String;I)[Ljava/net/InetAddress;" (this host chars)
  (let* ([h (jstring->scheme (nn host))]
         [v4 (fxlogtest chars 1)] [v6 (fxlogtest chars 2)]
         [family (cond [(and v4 v6) 0] [v6 (net-const 'AF_INET6)] [else 2])]
         [found (guard (e [(and (jobj? e) (jinstance? e "java/net/UnknownHostException") net-macos? v4 (local-host-name? h))
                           (let ([local (local-interface-ips)])
                             (if (null? local) (raise e) local))])
                  (lookup-addresses h family))]
         [fours (filter (lambda (a) (not (pair? a))) found)]
         [sixes (filter pair? found)]
         [ordered (cond [(fxlogtest chars 4) (append fours sixes)]
                        [(fxlogtest chars 8) (append sixes fours)]
                        [else found])])
    (when (null? ordered) (jthrow "java/net/UnknownHostException" h))
    (make-jarr "Ljava/net/InetAddress;" (list->vector (map (lambda (a) (address->inet host a)) ordered)))))
(defnative "java/net/Inet6AddressImpl.getHostByAddr([B)Ljava/lang/String;" (this addr)
  (let* ([bs (map (lambda (b) (fxlogand b 255)) (vector->list (jarr-vec addr)))]
         [v4 (fx= (length bs) 4)]
         [sa (foreign-alloc sockaddr6-size)] [buf (foreign-alloc 1025)])
    (if v4 (sockaddr-in! sa (bytes->ip4 bs) 0) (sockaddr-in6! sa bs 0 0))
    (let ([r (gil-released (lambda () (libc-getnameinfo sa (if v4 sockaddr-size sockaddr6-size) buf 1025 0 0 (net-const 'NI_NAMEREQD))))])
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

;; --- datagrams: DatagramChannelImpl, DatagramDispatcher, NativeSocketAddress -----------
;; DatagramSocket runs on DatagramChannelImpl, as the selectors' channels do.
;; NativeSocketAddress is the JDK's SOCKETADDRESS union in Unsafe memory, which
;; the JDK encodes and decodes itself, so its natives give the layout only:
;; sockaddr_in6's offsets, the family a byte at 1 after macOS's sin_len and a
;; short at 0 on Linux. A connected socket hears a refusal from the peer's
;; host as ECONNREFUSED, which is PortUnreachableException with no message;
;; an unconnected one never should, and receive0 ignores it as the JDK does.
(deflibc-blocking libc-recvfrom-blocking "recvfrom" (int uptr size_t int uptr uptr) ssize_t)
(deflibc-blocking libc-sendto-blocking "sendto" (int uptr size_t int uptr unsigned-32) ssize_t)
(deflibc-blocking libc-recv-blocking "recv" (int uptr size_t int) ssize_t)
(deflibc-blocking libc-send0-blocking "send" (int uptr size_t int) ssize_t)
(deflibc-blocking libc-readv-blocking "readv" (int uptr int) ssize_t)
(deflibc-blocking libc-writev-blocking "writev" (int uptr int) ssize_t)
(deflibc libc-connect "connect" #f (int uptr unsigned-32) int)
(deflibc libc-disconnectx (if net-macos? "disconnectx" "no-disconnectx-here") #f (int unsigned-32 unsigned-32) int)

(define (defnsa sig v) (define-native! (string-append "sun/nio/ch/NativeSocketAddress." sig) (lambda () v)))
(defnsa "AFINET()I" 2)
(defnsa "AFINET6()I" (if net-macos? 30 10))
(defnsa "sizeofSockAddr4()I" 16)
(defnsa "sizeofSockAddr6()I" 28)
(defnsa "sizeofFamily()I" (if net-macos? 1 2))
(defnsa "offsetFamily()I" (if net-macos? 1 0))
(defnsa "offsetSin4Port()I" 2)
(defnsa "offsetSin4Addr()I" 4)
(defnsa "offsetSin6Port()I" 2)
(defnsa "offsetSin6Addr()I" 8)
(defnsa "offsetSin6ScopeId()I" 24)
(defnsa "offsetSin6FlowInfo()I" 4)

(define (port-unreachable) (jthrow "java/net/PortUnreachableException" #f))
;; IOStatus as the JDK's convertReturnVal answers it, so an empty datagram read
;; through a connected channel is -1, as on the JVM
(define (datagram-status r e reading)
  (cond [(fx> r 0) r]
        [(fx= r 0) (if reading -1 0)]
        [(fx= e (net-const 'EAGAIN)) -2]
        [(fx= e 4) -3]
        [(fx= e (net-const 'ECONNREFUSED)) (port-unreachable)]
        [else (jthrow "java/io/IOException" (strerror-text e))]))
(define (datagram-call call reading)
  (let* ([r (released-call* call)] [e (if (< r 0) (errno) 0)])
    (datagram-status r e reading)))

(define (defdcm sig proc) (define-native! (string-append "sun/nio/ch/DatagramChannelImpl." sig) proc))
;; the sender's address goes to the NativeSocketAddress at sender; a zero-length
;; datagram is 0
(defdcm "receive0(Ljava/io/FileDescriptor;JIJZ)I"
  (lambda (fdo addr len sender connected)
    (let ([salen (foreign-alloc 4)] [fd (net-fd fdo)])
      (let retry ()
        (foreign-set! 'unsigned-32 salen 0 28)
        (let* ([r (released-call* (lambda () (libc-recvfrom-blocking fd addr len 0 sender salen)))]
               [e (if (< r 0) (errno) 0)])
          (cond [(fx>= r 0) (foreign-free salen) r]
                [(and (fx= e (net-const 'ECONNREFUSED)) (not (eqv? connected 1))) (retry)]
                [else (foreign-free salen)
                      (cond [(fx= e (net-const 'EAGAIN)) -2]
                            [(fx= e 4) -3]
                            [(fx= e (net-const 'ECONNREFUSED)) (port-unreachable)]
                            [else (socket-error e)])]))))))
(defdcm "send0(Ljava/io/FileDescriptor;JIJI)I"
  (lambda (fdo addr len target tlen)
    (let* ([r (released-call* (lambda () (libc-sendto-blocking (net-fd fdo) addr len 0 target tlen)))]
           [e (if (< r 0) (errno) 0)])
      (cond [(fx>= r 0) r]
            [(fx= e (net-const 'EAGAIN)) -2]
            [(fx= e 4) -3]
            [(fx= e (net-const 'ECONNREFUSED)) (port-unreachable)]
            [else (socket-error e)]))))
;; macOS dissolves the association with disconnectx, Linux with a connect to
;; AF_UNSPEC; a BSD kernel may answer EADDRNOTAVAIL, which means done
(defdcm "disconnect0(Ljava/io/FileDescriptor;Z)V"
  (lambda (fdo ipv6)
    (let ([fd (net-fd fdo)])
      (if net-macos?
          (when (and (< (libc-disconnectx fd 0 0) 0) (not (fx= (errno) (net-const 'EADDRNOTAVAIL))))
            (socket-error (errno)))
          (let ([sa (foreign-alloc sockaddr-size)])
            (do ([i 0 (fx+ i 1)]) ((fx= i sockaddr-size)) (foreign-set! 'unsigned-8 sa i 0))
            (let* ([r (libc-connect fd sa sockaddr-size)] [e (errno)])
              (foreign-free sa)
              (after-free r e socket-error))))
      (void))))

;; a connected channel's read and write: one datagram each, so the vector
;; forms are readv and writev themselves, never one call per buffer
(define (defdd sig proc) (define-native! (string-append "sun/nio/ch/DatagramDispatcher." sig) proc))
(defdd "read0(Ljava/io/FileDescriptor;JI)I"
  (lambda (fdo addr n) (datagram-call (lambda () (libc-recv-blocking (net-fd fdo) addr n 0)) #t)))
(defdd "readv0(Ljava/io/FileDescriptor;JI)J"
  (lambda (fdo addr n) (datagram-call (lambda () (libc-readv-blocking (net-fd fdo) addr n)) #t)))
(defdd "write0(Ljava/io/FileDescriptor;JI)I"
  (lambda (fdo addr n) (datagram-call (lambda () (libc-send0-blocking (net-fd fdo) addr n 0)) #f)))
(defdd "writev0(Ljava/io/FileDescriptor;JI)J"
  (lambda (fdo addr n) (datagram-call (lambda () (libc-writev-blocking (net-fd fdo) addr n)) #f)))
;; the JDK's repairSocket puts a fresh socket in place of the old fd's
(defdd "dup0(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;)V"
  (lambda (fdo1 fdo2)
    (when (< (libc-dup2 (net-fd fdo1) (net-fd fdo2)) 0) (last-error-io (errno)))
    (void)))

;; --- java.net.NetworkInterface ----------------------------------------------------------
;; The interfaces with an address, from getifaddrs, as the JDK's
;; enumIPv4Interfaces and then enumIPv6Interfaces find them: each new
;; interface and each address goes in front, the IPv4 pass first, so the order
;; is the JVM's for the IPv4 part. IPv6 addresses only when IPv6 is there;
;; each is scoped to its interface, as the JDK's are. Flags and the MTU come
;; from ioctl on a datagram socket, as in the JDK, with its messages; the
;; hardware address from the AF_LINK entry on macOS (6 bytes or none) and
;; SIOCGIFHWADDR on Linux (none when all zeros). struct ifaddrs is the same
;; on both: next 0, name 8, flags 16, addr 24, netmask 32, broadaddr 40.
(deflibc libc-getifaddrs "getifaddrs" #f (uptr) int)
(deflibc libc-freeifaddrs "freeifaddrs" #f (uptr) void)
(deflibc libc-if-nametoindex "if_nametoindex" #f (uptr) unsigned-32)
(define ifr-size 40)
(define (ifconst name)
  (cdr (assq name (if net-macos?
                      '((SIOCGIFFLAGS . #xc0206911) (SIOCGIFMTU . #xc0206933) (IFF_MULTICAST . #x8000))
                      '((SIOCGIFFLAGS . #x8913) (SIOCGIFMTU . #x8921) (SIOCGIFHWADDR . #x8927) (IFF_MULTICAST . #x1000))))))
(define (prefix-length sa)
  (let ([at (if (fx= (sockaddr-family sa) (net-const 'AF_INET6)) 8 4)]
        [n (if (fx= (sockaddr-family sa) (net-const 'AF_INET6)) 16 4)])
    (let loop ([i 0] [bits 0])
      (if (fx= i n) bits (loop (fx+ i 1) (fx+ bits (fxbit-count (foreign-ref 'unsigned-8 sa (fx+ at i)))))))))
(define (interface-index name)
  (let* ([p (c-string name)] [i (libc-if-nametoindex p)])
    (foreign-free p) i))

;; ((name . addresses) ...), newest first; an address is (a prefix
;; broadcast-ip-or-#f), a an ip number (IPv4) or (bytes . scope) (IPv6)
(define (interface-entry p fam)
  (let ([sa (foreign-ref 'uptr p 24)])
    (cond
      [(or (= sa 0) (not (fx= (sockaddr-family sa) fam))) #f]
      [(fx= fam 2)
       (let ([flags (foreign-ref 'unsigned-32 p 16)] [mask (foreign-ref 'uptr p 32)] [broad (foreign-ref 'uptr p 40)])
         (list (sockaddr-ip sa)
               (if (= mask 0) 0 (prefix-length mask))
               (and (fxlogtest flags 2) (not (= broad 0)) (sockaddr-ip broad))))]
      [else
       (let* ([bs (sockaddr6-bytes sa)] [scope (foreign-ref 'unsigned-32 sa 24)] [mask (foreign-ref 'uptr p 32)]
              ;; macOS (KAME) keeps a link-local address's interface index in
              ;; bytes 2 and 3; the address itself has zeros there
              [kame (and net-macos? (fx= (car bs) #xfe) (fx= (fxlogand (cadr bs) #xc0) #x80))]
              [scope (if (and kame (fx= scope 0)) (fxlogor (fxsll (caddr bs) 8) (cadddr bs)) scope)]
              [bs (if kame (cons (car bs) (cons (cadr bs) (cons 0 (cons 0 (list-tail bs 4))))) bs)])
         (list (cons bs scope) (if (= mask 0) 0 (prefix-length mask)) #f))])))
(define (all-interfaces)
  (let ([head (foreign-alloc 8)])
    (foreign-set! 'uptr head 0 0)
    (let* ([r (libc-getifaddrs head)] [e (errno)] [first (foreign-ref 'uptr head 0)])
      (foreign-free head)
      (when (< r 0) (socket-error e))
      (let ([ifs (fold-left (lambda (ifs fam) (interfaces-of-family first fam ifs))
                            '()
                            (if (net-ipv6?) (list 2 (net-const 'AF_INET6)) '(2)))])
        (unless (= first 0) (libc-freeifaddrs first))
        ifs))))
;; ifs with the family's addresses from the getifaddrs list at p added, each
;; interface (name addr ...) new to ifs in front
(define (interfaces-of-family p fam ifs)
  (if (= p 0)
      ifs
      (let ([addr (interface-entry p fam)] [next (foreign-ref 'uptr p 0)])
        (interfaces-of-family next fam
                              (if addr (add-interface-address ifs (c-string-text (foreign-ref 'uptr p 8)) addr) ifs)))))
(define (add-interface-address ifs name addr)
  (let ([old (assoc name ifs)])
    (if old
        (begin (set-cdr! old (cons addr (cdr old))) ifs)
        (cons (list name addr) ifs))))

(define (address-inet a) (if (pair? (car a)) (inet6 #f (caar a) (cdar a)) (inet4 #f (car a))))
(define (interface-address a inet)
  (let ([ia (construct "java/net/InterfaceAddress" "()V")])
    (jslot-set! ia (jfield-index "java/net/InterfaceAddress" "address") inet)
    (jslot-set! ia (jfield-index "java/net/InterfaceAddress" "broadcast") (and (caddr a) (inet4 #f (caddr a))))
    (jslot-set! ia (jfield-index "java/net/InterfaceAddress" "maskLength") (cadr a))
    ia))
;; an IPv6 address of an interface is scoped to it, its id the interface's index
(define (scope-to! inet ni index)
  (let ([h (ia-holder6 inet)])
    (jslot-set! h (holder6-field "scope_ifname") ni)
    (jslot-set! h (holder6-field "scope_id") index)
    (jslot-set! h (holder6-field "scope_id_set") 1)))
(define (network-interface entry)
  (let* ([name (jstring (car entry))] [addrs (cdr entry)] [index (interface-index (car entry))]
         [inets (map address-inet addrs)]
         [ni (construct "java/net/NetworkInterface" "(Ljava/lang/String;I[Ljava/net/InetAddress;)V"
                        name index (make-jarr "Ljava/net/InetAddress;" (list->vector inets)))])
    (for-each (lambda (a inet) (when (pair? (car a)) (scope-to! inet ni index))) addrs inets)
    (jslot-set! ni (jfield-index "java/net/NetworkInterface" "displayName") name)
    (jslot-set! ni (jfield-index "java/net/NetworkInterface" "bindings")
                (make-jarr "Ljava/net/InterfaceAddress;" (list->vector (map interface-address addrs inets))))
    (jslot-set! ni (jfield-index "java/net/NetworkInterface" "childs") (make-jarr "Ljava/net/NetworkInterface;" (vector)))
    ni))
(define (find-interface pred)
  (let loop ([ifs (all-interfaces)])
    (cond [(null? ifs) #f]
          [(pred (car ifs)) (network-interface (car ifs))]
          [else (loop (cdr ifs))])))
;; the interface holding an InetAddress (its scope aside)
(define (holds-address? ia)
  (if (fx= (ia-family ia) 1)
      (let ([ip (ia-v4-raw ia)])
        (lambda (entry) (exists (lambda (a) (eqv? (car a) ip)) (cdr entry))))
      (let ([bs (ia-ipv6-bytes ia)])
        (lambda (entry) (exists (lambda (a) (and (pair? (car a)) (equal? (caar a) bs))) (cdr entry))))))

(define (defni sig proc) (define-native! (string-append "java/net/NetworkInterface." sig) proc))
(defni "getAll()[Ljava/net/NetworkInterface;"
  (lambda () (make-jarr "Ljava/net/NetworkInterface;" (list->vector (map network-interface (all-interfaces))))))
(defni "getByName0(Ljava/lang/String;)Ljava/net/NetworkInterface;"
  (lambda (name) (let ([n (jstring->scheme (nn name))]) (find-interface (lambda (e) (string=? (car e) n))))))
(defni "getByIndex0(I)Ljava/net/NetworkInterface;"
  (lambda (index) (find-interface (lambda (e) (= (interface-index (car e)) index)))))
(defni "getByInetAddress0(Ljava/net/InetAddress;)Ljava/net/NetworkInterface;"
  (lambda (ia) (find-interface (holds-address? (nn ia)))))
(defni "boundInetAddress0(Ljava/net/InetAddress;)Z"
  (lambda (ia) (if (exists (holds-address? (nn ia)) (all-interfaces)) 1 0)))

;; (k ifr) with a struct ifreq naming the interface, on a datagram socket; the
;; request's failure is "<strerror> (<what> failed)" as the JDK says it
(define (with-ifreq name what request k)
  (let ([bv (string->utf8 (jstring->scheme (nn name)))] [fd (libc-socket 2 2 0)])
    (when (< fd 0) (socket-error (errno)))
    (let ([ifr (foreign-alloc ifr-size)])
      (do ([i 0 (fx+ i 1)]) ((fx= i ifr-size)) (foreign-set! 'unsigned-8 ifr i 0))
      (do ([i 0 (fx+ i 1)]) ((fx= i (fxmin 15 (bytevector-length bv)))) (foreign-set! 'unsigned-8 ifr i (bytevector-u8-ref bv i)))
      (let* ([r (libc-ioctl fd request ifr)] [e (errno)])
        (libc-close fd)
        (if (< r 0)
            (begin (foreign-free ifr)
                   (jthrow "java/net/SocketException" (string-append (strerror-text e) " (" what " failed)")))
            (let ([v (k ifr)]) (foreign-free ifr) v))))))
(define (interface-flags name)
  (with-ifreq name "getFlags()" (ifconst 'SIOCGIFFLAGS) (lambda (ifr) (foreign-ref 'unsigned-16 ifr 16))))
(define (flag-native sig test)
  (defni sig (lambda (name index) (if (test (interface-flags name)) 1 0))))
;; up is IFF_UP and IFF_RUNNING, as the JDK asks
(flag-native "isUp0(Ljava/lang/String;I)Z" (lambda (f) (and (fxlogtest f 1) (fxlogtest f #x40))))
(flag-native "isLoopback0(Ljava/lang/String;I)Z" (lambda (f) (fxlogtest f 8)))
(flag-native "isP2P0(Ljava/lang/String;I)Z" (lambda (f) (fxlogtest f #x10)))
(flag-native "supportsMulticast0(Ljava/lang/String;I)Z" (lambda (f) (fxlogtest f (ifconst 'IFF_MULTICAST))))
(defni "getMTU0(Ljava/lang/String;I)I"
  (lambda (name index) (with-ifreq name "ioctl(SIOCGIFMTU)" (ifconst 'SIOCGIFMTU) (lambda (ifr) (foreign-ref 'int ifr 16)))))
(define (byte-list->jbytes l) (make-jarr "B" (list->vector (map i2b l))))
(defni "getMacAddr0([BLjava/lang/String;I)[B"
  (lambda (inaddr name index)
    (if net-macos?
        ;; the AF_LINK entry's sockaddr_dl: name length at 5, address length
        ;; at 6, the name and then the address from 8
        (let ([n (jstring->scheme (nn name))] [head (foreign-alloc 8)])
          (foreign-set! 'uptr head 0 0)
          (let* ([r (libc-getifaddrs head)] [first (foreign-ref 'uptr head 0)])
            (foreign-free head)
            (let loop ([p (if (< r 0) 0 first)])
              (if (= p 0)
                  (begin (unless (or (< r 0) (= first 0)) (libc-freeifaddrs first)) #f)
                  (let ([sa (foreign-ref 'uptr p 24)])
                    (if (and (not (= sa 0)) (fx= (sockaddr-family sa) 18) (fx= (foreign-ref 'unsigned-8 sa 6) 6)
                             (string=? (c-string-text (foreign-ref 'uptr p 8)) n))
                        (let* ([at (fx+ 8 (foreign-ref 'unsigned-8 sa 5))]
                               [mac (let collect ([i 5] [acc '()])
                                      (if (fx< i 0) acc (collect (fx- i 1) (cons (foreign-ref 'unsigned-8 sa (fx+ at i)) acc))))])
                          (libc-freeifaddrs first)
                          (byte-list->jbytes mac))
                        (loop (foreign-ref 'uptr p 0))))))))
        ;; ifr_hwaddr is a struct sockaddr at 16, its data from 18
        (let ([mac (with-ifreq name "ioctl(SIOCGIFHWADDR)" (ifconst 'SIOCGIFHWADDR)
                               (lambda (ifr) (let collect ([i 5] [acc '()])
                                               (if (fx< i 0) acc (collect (fx- i 1) (cons (foreign-ref 'unsigned-8 ifr (fx+ 18 i)) acc))))))])
          (and (exists (lambda (b) (not (fx= b 0))) mac) (byte-list->jbytes mac))))))

;; --- InetAddress.isReachable --------------------------------------------------------
;; The JDK sends ICMP echo when it may open a raw socket (as root) and else
;; tries TCP port 7: a connection or a refusal both mean the host answered.
;; champaca always takes the TCP way. The wait for the connection goes on
;; with the time left when a signal interrupts it, as the JDK's NET_Wait does:
;; a socket closed on another thread signals blocked threads (NativeThread)
(define (wait-writable fd timeout)   ; #t when fd turned writable within timeout ms
  (let ([pfd (foreign-alloc 8)]
        [deadline (+ (real-time) timeout)])
    (let loop ([left timeout])
      (foreign-set! 'int pfd 0 fd)
      (foreign-set! 'short pfd 4 4)
      (foreign-set! 'short pfd 6 0)
      (let* ([r (released-call* (lambda () (libc-poll-blocking pfd 1 (max 0 (min left #x7fffffff)))))]
             [e (if (< r 0) (errno) 0)])
        (cond [(> r 0) (foreign-free pfd) #t]
              [(and (< r 0) (fx= e 4)) (loop (- deadline (real-time)))]
              [else (foreign-free pfd) #f])))))
;; TCP echo to port 7 of addr (bytes), from ifaddr's when given, as the JDK's
;; tcp_ping4 and tcp_ping6: a connection or a refusal means the host answered
(define (tcp-ping addr scope timeout ifaddr ttl)
  (let* ([v6 (fx= (length addr) 16)]
         [fd (libc-socket (if v6 (net-const 'AF_INET6) 2) 1 0)])
    (when (< fd 0) (socket-error (errno)))
    (let ([sa (foreign-alloc sockaddr6-size)] [len (if v6 sockaddr6-size sockaddr-size)])
      (define (fill! bytes port) (if v6 (sockaddr-in6! sa bytes scope port) (sockaddr-in! sa (bytes->ip4 bytes) port)))
      (let ([r (guard (e [#t (foreign-free sa) (libc-close fd) (raise e)])
                 (when (> ttl 0) (set-ping-ttl! fd v6 ttl))
                 (if (and ifaddr (begin (fill! ifaddr 0) (< (libc-bind fd sa len) 0)))
                     0
                     (begin
                       (libc-fcntl-int fd 4 (fxlogor (libc-fcntl-int fd 3 0) (net-const 'O_NONBLOCK)))
                       (fill! addr 7)
                       (ping-connect fd sa len timeout))))])
        (foreign-free sa)
        (libc-close fd)
        r))))
;; IP_TTL, or IPV6_UNICAST_HOPS (macOS 4, Linux 16)
(define (set-ping-ttl! fd v6 ttl)
  (let ([buf (foreign-alloc 4)])
    (foreign-set! 'int buf 0 ttl)
    (if v6 (libc-setsockopt fd 41 (if net-macos? 4 16) buf 4) (libc-setsockopt fd 0 (if net-macos? 4 2) buf 4))
    (foreign-free buf)))
;; the errors tcp_ping takes as no answer rather than a ConnectException
(define (ping-unreachable? e)
  (memv e (append (list (net-const 'ENETUNREACH) (net-const 'EAFNOSUPPORT) (net-const 'EADDRNOTAVAIL))
                  (if net-macos? '() (list 22 (net-const 'EHOSTUNREACH))))))
;; 1 when the host answered the non-blocking connect, 0 when it didn't
(define (ping-connect fd sa len timeout)
  (let* ([r (libc-connect fd sa len)] [e (if (< r 0) (errno) 0)])
    (cond [(or (fx= r 0) (fx= e (net-const 'ECONNREFUSED))) 1]
          [(ping-unreachable? e) 0]
          [(fx= e (net-const 'EINPROGRESS)) (ping-answer fd timeout)]
          [else (jthrow "java/net/ConnectException" (string-append (strerror-text e) " (connect failed)"))])))
(define (ping-answer fd timeout)
  (if (wait-writable fd timeout)
      (let ([err (guard (x [#t -1]) (get-int-option fd (net-const 'SOL_SOCKET) (net-const 'SO_ERROR)))])
        (if (or (fx= err 0) (fx= err (net-const 'ECONNREFUSED))) 1 0))
      0))
(define (jbytes-list a) (and a (map (lambda (b) (fxlogand b 255)) (vector->list (jarr-vec a)))))
(defnative "java/net/Inet4AddressImpl.isReachable0([BI[BI)Z" (this addr timeout ifaddr ttl)
  (tcp-ping (jbytes-list addr) 0 timeout (jbytes-list ifaddr) ttl))
(defnative "java/net/Inet6AddressImpl.isReachable0([BII[BII)Z" (this addr scope timeout ifaddr ttl if-scope)
  (tcp-ping (jbytes-list addr) scope timeout (jbytes-list ifaddr) ttl))
