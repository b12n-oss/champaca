;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Network interfaces and reachability: java.net.NetworkInterface's natives
;; through getifaddrs and ioctl, and InetAddress.isReachable's TCP ping. Split
;; from net.ss, whose sockets, constants and libc bindings these use; loaded
;; after it.

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
(defnative "java/net/Inet4AddressImpl.isReachable0([BI[BI)Z" (this addr timeout ifaddr ttl)
  (tcp-ping (jbytes-list addr) 0 timeout (jbytes-list ifaddr) ttl))
(defnative "java/net/Inet6AddressImpl.isReachable0([BII[BII)Z" (this addr scope timeout ifaddr ttl if-scope)
  (tcp-ping (jbytes-list addr) scope timeout (jbytes-list ifaddr) ttl))
