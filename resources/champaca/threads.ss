;; Copyright (C) 2026  champaca contributors
;; Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
;;   Anthony Green <green@moxielogic.com>
;;
;; SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
;; See COPYING and COPYING.CLASSPATH-EXCEPTION.

;; Threads, monitors and java.lang.Thread's natives: the GIL handoff, thread
;; states and statistics, parking, monitors and their waits. Split from
;; natives.ss, whose defnative and helpers these use; loaded after it.

;; --- threads -------------------------------------------------------------------
;; Each started Java thread is a Chez thread. One global lock, the GIL, lets
;; one of them run Java code at a time, so the class table, the JIT and the
;; runtime's hashtables need no locks of their own. A thread gives the GIL up
;; when it blocks (a contended monitor, wait, sleep, park, a class another
;; thread is initializing), and at backward branches (jsafepoint) once
;; another thread is ready to run.
;;
;; The runtime keeps some per-thread values in globals: the current
;; java.lang.Thread, the CHAMPACA_BT shadow stack, the scoped value cache,
;; the no-yield depth.
;; They are saved when a thread gives the GIL up and restored when it takes
;; it back.

(define-record-type jthread-state
  (fields (mutable jthread) (mutable jstack) (mutable svc) (mutable permit) (mutable depth) (mutable tid)
          (mutable no-yield) (mutable stack))
  (sealed #t) (nongenerative champaca-jthread-state-3))
;; This Chez thread's jthread-state. A forked Chez thread inherits its parent's
;; value, so a thread jolt started (a future, say) would see its parent's
;; state: gil-enter! checks the thread id before trusting it.
(define self (make-thread-parameter #f))
(define main-state (make-jthread-state #f '() #f #f 1 (get-thread-id) 0 #f))
(self main-state)

(define gil (make-mutex))
(define gil-cv (make-condition))  ; broadcast whenever something a blocked thread waits for may have changed
(define gil-owner main-state)      ; the jthread-state running Java code, or #f
(define gil-blocked 0)             ; threads waiting in gil-wait!
(define gil-ready 0)               ; of those, the ones ready to run but for the GIL
(define gil-takes 0)               ; how many times the GIL has changed hands

(define main-thread #f)
(define current-jthread #f)        ; the running thread's java.lang.Thread
(define scoped-value-cache #f)
(define no-yield-depth 0)          ; see without-yields

(define (gil-save! s)
  (jthread-state-jstack-set! s jstack)
  (jthread-state-svc-set! s scoped-value-cache)
  (jthread-state-no-yield-set! s no-yield-depth))
(define (gil-restore! s)
  (set! no-yield-depth (jthread-state-no-yield s))
  (set! current-jthread (jthread-state-jthread s))
  (set! jstack (jthread-state-jstack s))
  (set! scoped-value-cache (jthread-state-svc s)))

;; With gil held and the GIL not ours: wait until (ready?) holds, or deadline
;; (a time-utc, or #f) passes, and the GIL is free; then take it. Returns
;; what ready? said.
(define (gil-wait! s ready? deadline)
  (set! gil-blocked (fx+ gil-blocked 1))
  (let loop ([counted #f])
    (let* ([r (ready?)]
           [go (or r (and deadline (time>=? (current-time 'time-utc) deadline)))])
      (if (and go (not gil-owner))
          (begin
            (when counted (set! gil-ready (fx- gil-ready 1)))
            (set! gil-blocked (fx- gil-blocked 1))
            (set! gil-owner s)
            (set! gil-takes (fx+ gil-takes 1))
            ;; a thread that yielded waits for this take; woken, it counts
            ;; itself ready, so the new owner yields back at a safepoint
            (when (fx> gil-blocked 0) (condition-broadcast gil-cv))
            r)
          (begin
            (unless (eq? go counted)
              (set! gil-ready (if go (fx+ gil-ready 1) (fx- gil-ready 1)))
              (condition-broadcast gil-cv))     ; a yielding thread watches gil-ready
            (if (and deadline (not go))
                (condition-wait gil-cv gil deadline)
                (condition-wait gil-cv gil))
            (loop go))))))

;; Another thread's stack (Thread.getStackTrace, getAllStackTraces): a Java
;; thread that gives the GIL up does so inside a call/1cc receiver whose
;; continuation it puts in its state's stack field first, and clears only
;; after it holds the GIL again. Every other Java thread is in such a call
;; while one runs, and it can't return into those frames before it has the
;; GIL, so the thread holding it can walk them (raw-frames) safely. The field
;; is set and put back under the GIL, back to what it held before, since a
;; downcall can upcall into Java that blocks again on the same thread (the
;; outer stack is published again once the inner call is done); a raise
;; leaving the body puts it back too, so no stale continuation stays
;; published for a thread that has returned into it. thread-states finds a
;; java.lang.Thread's state, filled once per thread; an ephemeron table,
;; since a state holds its Thread and a weak one would keep both.
(define thread-states (make-ephemeron-eq-hashtable))
(define (bind-jthread! s t)
  (jthread-state-jthread-set! s t)
  (when t (eq-hashtable-set! thread-states t s)))
(define-syntax with-published-stack
  (syntax-rules ()
    [(_ s e ...)
     (let ([before (jthread-state-stack s)])
       (call/1cc
        (lambda (k)
          (jthread-state-stack-set! s k)
          ;; a handler rather than a guard: it puts the field back before
          ;; the raise goes on outward, without the guard's continuation
          (let ([r (with-exception-handler
                    (lambda (x) (jthread-state-stack-set! s before) (raise x))
                    (lambda () e ...))])
            (jthread-state-stack-set! s before)
            r))))]))

;; The running thread blocks: give the GIL up until (ready?) or the deadline.
(define (gil-block! ready? deadline)
  (let ([s (self)])
    (gil-save! s)
    (let ([r (with-published-stack s
              (with-mutex gil
                (set! gil-owner #f)
                (condition-broadcast gil-cv)
                (gil-wait! s ready? deadline)))])
      (gil-restore! s)
      r)))

;; Enter Java code from a thread that may not hold the GIL (a new thread, or
;; jolt calling in); re-entrant. A Chez thread champaca has not seen before
;; (one jolt started) gets a java.lang.Thread of its own, the way a JVM
;; attaches a native thread.
(define (gil-enter!)
  (let* ([s0 (self)]
         [known (and s0 (eqv? (jthread-state-tid s0) (get-thread-id)))]
         [s (if known s0 (let ([s (make-jthread-state #f '() #f #f 0 (get-thread-id) 0 #f)]) (self s) s))])
    (if (eq? gil-owner s)
        (jthread-state-depth-set! s (fx+ (jthread-state-depth s) 1))
        (begin
          (with-mutex gil (gil-wait! s (lambda () #t) #f))
          (jthread-state-depth-set! s 1)
          (gil-restore! s)
          (unless known (attach-jthread! s))))))

(define (gil-leave!)
  (let* ([s (self)] [d (fx- (jthread-state-depth s) 1)])
    (jthread-state-depth-set! s d)
    (when (fx= d 0)
      (gil-save! s)
      (with-mutex gil
        (set! gil-owner #f)
        (condition-broadcast gil-cv)))))

;; Run thunk without the GIL, for a call that may wait in C indefinitely (a
;; pipe or terminal read, files.ss): other Java threads run meanwhile. The
;; thunk must not touch Java objects or runtime state. The GIL is taken back
;; before anything leaves, a raise included.
(define (gil-released thunk)
  (let ([s (self)])
    (gil-save! s)
    (with-published-stack s
     (with-mutex gil
       (set! gil-owner #f)
       (condition-broadcast gil-cv))
     (let ([retake (lambda () (with-mutex gil (gil-wait! s (lambda () #t) #f)) (gil-restore! s))])
       (let ([r (guard (e [#t (retake) (raise e)]) (thunk))])
         (retake)
         r)))))

;; Tell blocked threads to look again at what they wait for.
(define (gil-poke!)
  (when (fx> gil-blocked 0)
    (with-mutex gil (condition-broadcast gil-cv))))

;; thunk runs without giving the GIL up at a safepoint. The depth is a
;; per-thread value (saved and restored with the GIL), so a thunk that does
;; block, waiting for another thread's <clinit> or a monitor, holds back no
;; other thread's safepoints.
(define (without-yields thunk)
  (dynamic-wind
    (lambda () (set! no-yield-depth (fx+ no-yield-depth 1)))
    thunk
    (lambda () (set! no-yield-depth (fx- no-yield-depth 1)))))

;; Let a thread that is ready run first, then queue for the GIL again.
(define (gil-yield!)
  (let ([n gil-takes])
    (gil-block! (lambda () (or (not (fx= gil-takes n)) (fx= gil-ready 0))) #f)))

;; Generated code checks gil-ready inline at each backward branch and calls
;; this only when some thread is waiting for the GIL. While no-yield-depth is
;; above zero the thread keeps the GIL (see without-yields).
(define safepoint-count 0)
(define (jsafepoint)
  (set! safepoint-count (fx+ safepoint-count 1))
  (when (and (fx>= safepoint-count 1000) (fx= no-yield-depth 0))
    (set! safepoint-count 0)
    (gil-yield!)))

(define (deadline-after-nanos n)
  (add-duration (current-time 'time-utc) (make-time 'time-duration (remainder n 1000000000) (quotient n 1000000000))))

;; --- monitors -----------------------------------------------------------------------
;; One per object that has been locked, found through a weak table. A waiter
;; is a vector #(thread notified? deadline). blocked counts the threads waiting to enter
;; it, so releasing an uncontended monitor wakes nobody: a thread parked for
;; good elsewhere (the Reference Handler) would otherwise make every exit
;; take the GIL's mutex and broadcast, which cost 60% on synchronized code.

;; hash: the object's identity hash once it has one (runtime.ss identity-hash)
(define-record-type jmonitor (fields (mutable owner) (mutable count) (mutable waiters) (mutable blocked) (mutable seq) (mutable hash))
  (sealed #t) (nongenerative champaca-jmonitor-3))
;; An object keeps its monitor in its lock field; an array's sits in a weak
;; table. Only the GIL's owner runs Java code, so no other thread races the
;; first lock.
;; seq orders the monitors a thread holds for a thread dump, the most
;; recently entered first.
(define monitors (make-weak-eq-hashtable))
(define monitor-seq 0)
(define (monitor-of o)
  (if (jobj? o)
      (let ([l (jobj-lock o)])
        (if (jmonitor? l)
            l
            ;; l is #f or the identity hash, which the monitor keeps
            (let ([m (make-jmonitor #f 0 '() 0 0 l)]) (jobj-lock-set! o m) m)))
      (or (eq-hashtable-ref monitors o #f)
          (let ([m (make-jmonitor #f 0 '() 0 0 #f)]) (eq-hashtable-set! monitors o m) m))))

;; wait, holding the GIL, until o's monitor m is free, then own it with
;; count n
(define (monitor-acquire! o m s n)
  (when (jmonitor-owner m)
    (jmonitor-blocked-set! m (fx+ (jmonitor-blocked m) 1))
    (with-thread-status status-blocked o (lambda () (gil-block! (lambda () (not (jmonitor-owner m))) #f)))
    (jmonitor-blocked-set! m (fx- (jmonitor-blocked m) 1)))
  (jmonitor-owner-set! m s)
  (jmonitor-count-set! m n)
  (set! monitor-seq (fx+ monitor-seq 1))
  (jmonitor-seq-set! m monitor-seq))

(define (monitor-release! m)
  (jmonitor-owner-set! m #f)
  (jmonitor-count-set! m 0)
  (when (fx> (jmonitor-blocked m) 0) (gil-poke!)))

;; The thread running Java code is always the GIL's owner, which a global
;; holds; (self) reads a thread parameter.
(define (jmonitor-enter o)
  (let ([m (monitor-of (nn o))] [s gil-owner])
    (if (eq? (jmonitor-owner m) s)
        (jmonitor-count-set! m (fx+ (jmonitor-count m) 1))
        (monitor-acquire! o m s 1))))

;; Unbalanced exits are ignored rather than thrown, as monitors predate threads here.
(define (jmonitor-exit o)
  (let ([m (monitor-of (nn o))])
    (when (eq? (jmonitor-owner m) gil-owner)
      (let ([n (fx- (jmonitor-count m) 1)])
        (if (fx= n 0)
            (monitor-release! m)
            (jmonitor-count-set! m n))))))

(define (owned-monitor o)
  (let ([m (monitor-of (nn o))])
    (unless (eq? (jmonitor-owner m) (self))
      (jthrow "java/lang/IllegalMonitorStateException" "current thread is not owner"))
    m))

(define interrupted-slot #f)
(define (interrupted? t) (fx= 1 (jslot t interrupted-slot)))
(define (take-interrupt! t)
  (and (interrupted? t) (begin (jslot-set! t interrupted-slot 0) #t)))

;; A notified thread returns normally even if it was interrupted too (its
;; interrupt stays pending), which JLS 17.2.4 allows, so a notify is never
;; lost to an interrupt. notify skips a waiter whose timeout has passed or
;; that was interrupted: it is leaving anyway, and the notify goes to one that
;; is still waiting.
(define (waiter-leaving? w)
  (or (interrupted? (vector-ref w 0))
      (let ([d (vector-ref w 2)]) (and d (time>=? (current-time 'time-utc) d)))))

(defnative "java/lang/Object.wait0(J)V" (this ms)
  (let* ([m (owned-monitor this)] [s (self)] [t current-jthread]
         [deadline (and (> ms 0) (deadline-after-nanos (* ms 1000000)))]
         [w (vector t #f deadline)] [count (jmonitor-count m)])
    (when (take-interrupt! t) (jthrow "java/lang/InterruptedException" #f))
    (jmonitor-waiters-set! m (append (jmonitor-waiters m) (list w)))
    (monitor-release! m)
    (with-thread-status (status-object-wait deadline) this
      (lambda () (gil-block! (lambda () (or (vector-ref w 1) (interrupted? t))) deadline)))
    (jmonitor-waiters-set! m (remq w (jmonitor-waiters m)))
    (monitor-acquire! this m s count)
    (when (and (not (vector-ref w 1)) (take-interrupt! t))
      (jthrow "java/lang/InterruptedException" #f))))

(defnative "java/lang/Object.notify()V" (this)
  (let ([m (owned-monitor this)])
    (let ([w (find (lambda (w) (not (or (vector-ref w 1) (waiter-leaving? w)))) (jmonitor-waiters m))])
      (when w (vector-set! w 1 #t) (gil-poke!)))))

(defnative "java/lang/Object.notifyAll()V" (this)
  (let ([m (owned-monitor this)])
    (for-each (lambda (w) (vector-set! w 1 #t)) (jmonitor-waiters m))
    (gil-poke!)))

(defnative "java/lang/Thread.holdsLock(Ljava/lang/Object;)Z" (o)
  (if (eq? (jmonitor-owner (monitor-of (nn o))) (self)) 1 0))

;; --- Thread ---------------------------------------------------------------------------
;; currentThread answers main-thread before its constructor has run, because
;; Thread's constructor itself asks for the current thread.

(define jthread-states (make-weak-eq-hashtable))  ; java.lang.Thread -> jthread-state
(define java-live-threads '())                          ; started and not yet terminated

;; JVMTI thread state bits, as Thread.holder.threadStatus holds them
(define status-runnable 5)    ; ALIVE | RUNNABLE
(define status-terminated 2)
;; HotSpot's statuses for a thread that waits (javaThreadStatus.hpp): ALIVE
;; with WAITING (#x80) and WAITING_INDEFINITELY (#x10) or
;; WAITING_WITH_TIMEOUT (#x20), and SLEEPING (#x40), IN_OBJECT_WAIT (#x100)
;; or PARKED (#x200); BLOCKED_ON_MONITOR_ENTER (#x400) for a monitor
(define status-sleeping #xE1)
(define (status-object-wait timed?) (if timed? #x1A1 #x191))
(define (status-parked timed?) (if timed? #x2A1 #x291))
(define status-blocked #x401)

(define (thread-status-set! t v)
  (let ([holder (jslot t (jfield-index "java/lang/Thread" "holder"))])
    (jslot-set! holder (jfield-index "java/lang/Thread$FieldHolder" "threadStatus") v)))
;; ThreadMXBean's per-thread contention figures, as HotSpot's
;; ThreadStatistics keeps them: how often a thread blocked entering a
;; monitor and how often it waited (Object.wait, sleep, park), the time each
;; took while contention monitoring is on (nanoseconds here, milliseconds in
;; a ThreadInfo), and the object it blocks or waits on now, if any (a parked
;; thread's is its parkBlocker field, read when asked)
(define-record-type thread-stat
  (fields (mutable blocked) (mutable blocked-ns) (mutable waited) (mutable waited-ns) (mutable blocker))
  (sealed #t) (nongenerative champaca-thread-stat))
(define thread-stats (make-weak-eq-hashtable))   ; java.lang.Thread -> thread-stat
(define (thread-stat-of t)
  (or (eq-hashtable-ref thread-stats t #f)
      (let ([st (make-thread-stat 0 0 0 0 #f)]) (eq-hashtable-set! thread-stats t st) st)))
(define contention-enabled 0)
;; run thunk with the current Java thread's status set to v, as HotSpot
;; sets it around a wait, so Thread.getState says what the thread waits
;; for, counting the wait (and timing it while contention monitoring is on)
;; with blocker as what it waits on; the status it had comes back after,
;; however thunk leaves. A thread outside Java (no current Java thread) has
;; no status to set
(define (with-thread-status v blocker thunk)
  (let ([t current-jthread])
    (if (jobj? t)
        (let* ([holder (jslot t (jfield-index "java/lang/Thread" "holder"))]
               [i (jfield-index "java/lang/Thread$FieldHolder" "threadStatus")]
               [before (jslot holder i)]
               [st (thread-stat-of t)]
               [blocked? (fx= v status-blocked)]
               [start (and (fx= contention-enabled 1) (now-nanos))])
          (if blocked?
              (thread-stat-blocked-set! st (+ (thread-stat-blocked st) 1))
              (thread-stat-waited-set! st (+ (thread-stat-waited st) 1)))
          (dynamic-wind
            (lambda () (jslot-set! holder i v) (thread-stat-blocker-set! st blocker))
            thunk
            (lambda ()
              (jslot-set! holder i before)
              (thread-stat-blocker-set! st #f)
              (when start
                (let ([d (- (now-nanos) start)])
                  (if blocked?
                      (thread-stat-blocked-ns-set! st (+ (thread-stat-blocked-ns st) d))
                      (thread-stat-waited-ns-set! st (+ (thread-stat-waited-ns st) d))))))))
        (thunk))))
(define (thread-daemon? t)
  (let ([holder (jslot t (jfield-index "java/lang/Thread" "holder"))])
    (fx= 1 (jslot holder (jfield-index "java/lang/Thread$FieldHolder" "daemon")))))
;; eetop is the VM thread's address; isAlive() is eetop != 0
(define (thread-alive! t on?)
  (jslot-set! t (jfield-index "java/lang/Thread" "eetop") (if on? 1 0))
  (thread-status-set! t (if on? status-runnable status-terminated)))

(defnative "java/lang/Thread.currentThread()Ljava/lang/Thread;" () current-jthread)
(defnative "java/lang/Thread.currentCarrierThread()Ljava/lang/Thread;" () current-jthread)
(defnative "java/lang/Thread.setCurrentThread(Ljava/lang/Thread;)V" (this t) (void))
(defnative "java/lang/Thread.setPriority0(I)V" (this p) (void))
(defnative "java/lang/Thread.setNativeName(Ljava/lang/String;)V" (this n) (void))
;; the counter ThreadIdentifiers.next() takes ids from; main has Thread's
;; PRIMORDIAL_TID, 3, so the counter starts after it, as HotSpot's does
(defnative "java/lang/Thread.getNextThreadIdOffset()J" () (native-alloc! 4))
(defnative "java/lang/Thread.scopedValueCache()[Ljava/lang/Object;" () scoped-value-cache)
(defnative "java/lang/Thread.setScopedValueCache([Ljava/lang/Object;)V" (c) (set! scoped-value-cache c))
(defnative "java/lang/Thread.findScopedValueBindings()Ljava/lang/Object;" () #f)
(defnative "java/lang/Thread.ensureMaterializedForStackWalk(Ljava/lang/Object;)V" (o) (void))
(defnative "java/lang/Thread.clearInterruptEvent()V" () (void))
(defnative "java/lang/Thread.interrupt0()V" (this)
  (let ([s (eq-hashtable-ref jthread-states this #f)])
    (when s (jthread-state-permit-set! s #t)))   ; HotSpot unparks it too
  (gil-poke!))
(defnative "java/lang/Thread.yield0()V" () (when (fx> gil-ready 0) (gil-yield!)))

(defnative "java/lang/Thread.sleepNanos0(J)V" (nanos)
  (let ([t current-jthread])
    (when (take-interrupt! t) (jthrow "java/lang/InterruptedException" "sleep interrupted"))
    (when (with-thread-status status-sleeping #f
            (lambda () (gil-block! (lambda () (interrupted? t)) (deadline-after-nanos (max nanos 0)))))
      (take-interrupt! t)
      (jthrow "java/lang/InterruptedException" "sleep interrupted"))))

;; Virtual threads need continuations, which are not here; the JDK then
;; makes Thread.ofVirtual() unsupported and runs everything else on platform
;; threads.
(defnative "jdk/internal/vm/ContinuationSupport.isSupported0()Z" () 0)

;; LockSupport.park / unpark: one permit per thread.
(defnative "jdk/internal/misc/Unsafe.park(ZJ)V" (this absolute time)
  (let ([s (self)] [t current-jthread])
    (unless (or (jthread-state-permit s) (interrupted? t)
                (and (fx= absolute 1) (<= time (quotient (now-nanos) 1000000))))
      (let ([deadline (cond [(fx= absolute 1)
                             (let ([n (* time 1000000)])
                               (make-time 'time-utc (remainder n 1000000000) (quotient n 1000000000)))]
                            [(> time 0) (deadline-after-nanos time)]
                            [else #f])])
        (with-thread-status (status-parked deadline) #f
          (lambda () (gil-block! (lambda () (or (jthread-state-permit s) (interrupted? t))) deadline)))))
    (jthread-state-permit-set! s #f)))

(defnative "jdk/internal/misc/Unsafe.unpark(Ljava/lang/Object;)V" (this t)
  (let ([s (and t (eq-hashtable-ref jthread-states t #f))])
    (when s
      (jthread-state-permit-set! s #t)
      (gil-poke!))))

;; What HotSpot does as a thread ends (JavaThread::exit): Thread.exit(), then
;; ensure_join, which marks the thread terminated and notifies its monitor,
;; which is what join() waits on. unlisted! takes the thread off the live
;; list before the notify, so a joiner that wakes doesn't find it listed.
;; Thread.exit()'s exceptions are dropped as HotSpot drops them, but not a
;; System.exit, which only main raises (halt0 ends the process on any other).
(define (thread-terminated! t unlisted!)
  (guard (e [(java-halt? e) (raise e)] [#t (void)])
    ((method-proc "java/lang/Thread" "exit()V") t))
  (jmonitor-enter t)
  (thread-alive! t #f)
  (unlisted!)
  ((native-ref "java/lang/Object.notifyAll()V") t)
  (jmonitor-exit t))

;; The launcher's main thread ends when main returns, before the launcher
;; waits for the other non-daemon threads, so they can join it. Only the
;; launcher's path does this: a jolt program calling into Java keeps its main
;; thread, since jolt goes on running on it. Then, as HotSpot's DestroyJavaVM
;; attaches a new thread for the wait and the shutdown hooks, this Chez
;; thread goes on as a live non-daemon java.lang.Thread named DestroyJavaVM in
;; main's group, sharing main's jthread-state; main's own entry goes, so
;; interrupting or unparking the ended main reaches nothing.
(define main-ended #f)
(define destroy-jthread #f)
(define (main-thread-exit!)
  (thread-terminated! main-thread (lambda () (set! main-ended #t)))
  (eq-hashtable-delete! jthread-states main-thread)
  (eq-hashtable-delete! thread-states main-thread)
  (let ([t (jnew "java/lang/Thread")])
    (bind-jthread! main-state t)
    (set! current-jthread t)
    (eq-hashtable-set! jthread-states t main-state)
    ((method-proc "java/lang/Thread" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     t main-group (jstring "DestroyJavaVM"))
    (note-native-thread! t)
    (thread-alive! t #t)
    (set! destroy-jthread t)))

;; A started thread: run(), then what HotSpot does on the way out. An
;; uncaught exception goes to dispatchUncaughtException, which prints it the
;; JDK's way; anything else (a runtime bug) is reported here. Then
;; thread-terminated!.
;; a thread the runtime starts for itself (the Notification Thread) runs a
;; Scheme procedure where a Java thread runs run()
(define scheme-thread-bodies (make-weak-eq-hashtable))
(define (thread-body t)
  (call/cc
   (lambda (k)
     (with-exception-handler
      (lambda (raised)
        (k (let ([e (java-raise-of raised)])
            (cond
             [(java-halt? e) (exit-process (java-halt-status e))]
             [(jobj? e)
              (let ([r (dispatch-uncaught t e)])
                (cond [(java-halt? r) (exit-process (java-halt-status r))]
                      [(not (eq? r 'done)) (report-uncaught r)]))]
             [else (report-uncaught e)]))))
      (lambda () (let ([p (eq-hashtable-ref scheme-thread-bodies t #f)]) (if p (p) ((jvirt t "run()V") t)))))))
  (thread-terminated! t (lambda () (set! java-live-threads (remq t java-live-threads))))
  (flush-output-port (current-output-port)))

(define threads-started 0)   ; for the management beans; main isn't counted
(define threads-peak 1)
(defnative "java/lang/Thread.start0()V" (this)
  (let ([s (make-jthread-state this '() #f #f 0 #f 0 #f)])
    (eq-hashtable-set! thread-states this s)
    (eq-hashtable-set! jthread-states this s)
    (thread-alive! this #t)
    (set! java-live-threads (cons this java-live-threads))
    (set! threads-started (fx+ threads-started 1))
    (set! threads-peak (fxmax threads-peak (fx+ 1 (length java-live-threads))))
    ;; jolt's fork-thread/daemon, so jolt's own exit waits for the thread
    ;; exactly when a JVM would: a daemon thread doesn't hold the process open
    (fork-thread/daemon
     (thread-daemon? this)
     (lambda ()
       (jthread-state-tid-set! s (get-thread-id))
       (self s)
       (gil-enter!)
       (note-native-thread! this)   ; the table is shared: under the GIL
       (thread-body this)
       (gil-leave!)))
    (void)))

;; A java.lang.Thread for a Chez thread that calls in from jolt, named
;; jolt-<id> in main's group. Like the main thread at boot, currentThread
;; answers it before its constructor has run.
(define (attach-jthread! s)
  (let ([t (jnew "java/lang/Thread")])
    (bind-jthread! s t)
    (set! current-jthread t)
    (eq-hashtable-set! jthread-states t s)
    ((method-proc "java/lang/Thread" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     t main-group
     (jstring (string-append "jolt-" (number->string (get-thread-id)))))
    (note-native-thread! t)
    (thread-alive! t #t)))

;; The live Java threads, main first until it ends, then DestroyJavaVM, as
;; Thread.getThreads and the management beans list them (a jolt thread
;; calling in isn't one, as a native thread attached to a JVM is)
(define (live-jthreads)
  (let ([others (reverse java-live-threads)])
    (if main-ended (cons destroy-jthread others) (cons main-thread others))))
(defnative "java/lang/Thread.getThreads()[Ljava/lang/Thread;" ()
  (make-jarr "Ljava/lang/Thread;" (list->vector (live-jthreads))))
;; another thread's Java frames, from the stack it published when it gave the
;; GIL up; none for a thread that hasn't (not started, finished, or a thread
;; outside Java)
(define (published-stack t)
  (let ([s (eq-hashtable-ref thread-states t #f)])
    (and s (jthread-state-stack s))))
;; a thread waiting in a native the runtime wrote has no frame for it, so
;; its status says which one, and that frame goes on top as HotSpot shows it
(define (waiting-native-frame t)
  (let ([v (thread-status-of t)])
    (cond [(fx= (fxlogand v #x100) #x100) '(("java/lang/Object" "wait0" "(J)V" -2))]
          [(fx= (fxlogand v #x40) #x40) '(("java/lang/Thread" "sleepNanos0" "(J)V" -2))]
          [(fx= (fxlogand v #x200) #x200) '(("jdk/internal/misc/Unsafe" "park" "(ZJ)V" -2))]
          [else '()])))
(define (published-frames t)
  (let ([k (published-stack t)])
    (if k (append (waiting-native-frame t) (visible-frames (java-frames-of (raw-frames k)))) '())))
(define (other-thread-stack t) (stack-trace-array (published-frames t)))
;; unformatted, as asyncGetStackTrace formats them; null for a thread that
;; published nothing, which it takes as no stack
(defnative "java/lang/Thread.getStackTrace0()Ljava/lang/Object;" (this)
  (and (published-stack this) (stack-trace-elements (published-frames this))))
;; Thread.getAllStackTraces: the current thread's stack, from the native's
;; caller down, with the native's own frame on top as the JVM shows it; each
;; other thread's from where it gave the GIL up, its natives (Thread.sleep0,
;; say) having no frame of their own
(defnative "java/lang/Thread.dumpThreads([Ljava/lang/Thread;)[[Ljava/lang/StackTraceElement;" (threads)
  (make-jarr "[Ljava/lang/StackTraceElement;"
             (vector-map (lambda (t)
                           (if (eq? t current-jthread)
                               (stack-trace-array
                                (cons (list "java/lang/Thread" "dumpThreads" "([Ljava/lang/Thread;)[[Ljava/lang/StackTraceElement;" -2)
                                      (visible-frames (java-frames-of (call/1cc (lambda (k) (raw-frames k)))))))
                               (other-thread-stack t)))
                         (jarr-vec (nn threads)))))

;; ThreadMXBean's CPU times, measured as HotSpot measures them. On macOS that
;; is thread_info's user and system times (microseconds) through the
;; thread's mach port; on Linux the total is the thread's CPU clock
;; (pthread_getcpuclockid) and the user time utime in
;; /proc/self/task/<tid>/stat. Each Java thread's pthread_t, and on Linux
;; its kernel tid, is noted on the thread itself once it holds the GIL, so a
;; thread asked about before that (just started) answers -1. A thread is
;; looked up among the live ones under the GIL, and a thread leaves that
;; list before its OS thread can end, so its pthread_t is still good.
(define thread-natives (make-weak-eq-hashtable))   ; java.lang.Thread -> (pthread . tid)
(define thread-pthread-self (foreign-procedure "pthread_self" () uptr))
(define thread-gettid   ; glibc 2.30+; without it, user times are -1
  (or (and (not natives-macos?) (guard (e [#t #f]) (foreign-procedure "gettid" () int))) (lambda () 0)))
(define (note-native-thread! t) (eq-hashtable-set! thread-natives t (cons (thread-pthread-self) (thread-gettid))))
(define thread-cpu-enabled 1)   ; HotSpot measures by default
(defnative "sun/management/ThreadImpl.setThreadCpuTimeEnabled0(Z)V" (on) (set! thread-cpu-enabled on))

;; macOS: (user-ns . system-ns), or #f
(define mach-thread-of (and natives-macos? (foreign-procedure "pthread_mach_thread_np" (uptr) unsigned-32)))
(define mach-thread-info (and natives-macos? (foreign-procedure "thread_info" (unsigned-32 int uptr uptr) int)))
(define (mach-thread-times pthread)
  ;; struct thread_basic_info: user_time and system_time, each two int32s
  ;; (seconds, microseconds), then six more; THREAD_BASIC_INFO is 3, its
  ;; count 10 natural_t
  (let ([info (foreign-alloc 40)] [count (foreign-alloc 4)])
    (foreign-set! 'unsigned-32 count 0 10)
    (let* ([ok (eqv? 0 (mach-thread-info (mach-thread-of pthread) 3 info count))]
           [us (lambda (off) (+ (* 1000000 (foreign-ref 'integer-32 info off)) (foreign-ref 'integer-32 info (fx+ off 4))))]
           [r (and ok (cons (* 1000 (us 0)) (* 1000 (us 8))))])
      (foreign-free info)
      (foreign-free count)
      r)))
;; Linux
(define cpu-clock-of (and (not natives-macos?) (foreign-procedure "pthread_getcpuclockid" (uptr uptr) int)))
(define cpu-clock-gettime (foreign-procedure "clock_gettime" (int uptr) int))
(define (linux-thread-cpu-ns pthread)
  (let ([id (foreign-alloc 4)] [ts (foreign-alloc 16)])
    (let ([r (and (eqv? 0 (cpu-clock-of pthread id))
                  (eqv? 0 (cpu-clock-gettime (foreign-ref 'integer-32 id 0) ts))
                  (+ (* 1000000000 (foreign-ref 'integer-64 ts 0)) (foreign-ref 'integer-64 ts 8)))])
      (foreign-free id)
      (foreign-free ts)
      r)))
(define clock-ticks (and (not natives-macos?) ((foreign-procedure "sysconf" (int) long) 2)))   ; _SC_CLK_TCK
(define (linux-thread-user-ns tid)
  ;; tid 0: no gettid. The fields after the command's closing paren: state is the first,
  ;; utime (field 14) the twelfth
  (guard (e [#t #f])
    (let* ([_ (when (eqv? tid 0) (raise 'no-tid))]
           [line (call-with-input-file (string-append "/proc/self/task/" (number->string tid) "/stat") get-line)]
           [rest (let loop ([i (fx- (string-length line) 1)])
                   (if (char=? (string-ref line i) #\)) (substring line (fx+ i 2) (string-length line)) (loop (fx- i 1))))]
           [fields (let split ([i 0] [start 0] [acc '()])
                     (cond [(fx= i (string-length rest)) (reverse (cons (substring rest start i) acc))]
                           [(char=? (string-ref rest i) #\space) (split (fx+ i 1) (fx+ i 1) (cons (substring rest start i) acc))]
                           [else (split (fx+ i 1) start acc)]))])
      (* (string->number (list-ref fields 11)) (quotient 1000000000 clock-ticks)))))
;; id 0 is the current thread; -1 for a thread that isn't alive
(define (thread-cpu-ns id user?)
  (let* ([tid-slot (jfield-index "java/lang/Thread" "tid")]
         [t (if (eqv? id 0) current-jthread (find (lambda (t) (eqv? id (jslot t tid-slot))) (live-jthreads)))]
         [n (and t (eq-hashtable-ref thread-natives t #f))])
    (or (and n (if natives-macos?
                   (let ([ts (mach-thread-times (car n))]) (and ts (if user? (car ts) (+ (car ts) (cdr ts)))))
                   (if user? (linux-thread-user-ns (cdr n)) (linux-thread-cpu-ns (car n)))))
        -1)))
(defnative "sun/management/ThreadImpl.getThreadTotalCpuTime0(J)J" (id) (thread-cpu-ns id #f))
(defnative "sun/management/ThreadImpl.getThreadUserCpuTime0(J)J" (id) (thread-cpu-ns id #t))
(define (thread-cpu-times! ids result user?)
  (let ([iv (jarr-vec (nn ids))] [rv (jarr-vec (nn result))])
    (do ([i 0 (fx+ i 1)]) ((fx= i (vector-length iv))) (vector-set! rv i (thread-cpu-ns (vector-ref iv i) user?)))))
(defnative "sun/management/ThreadImpl.getThreadTotalCpuTime1([J[J)V" (ids result) (thread-cpu-times! ids result #f))
(defnative "sun/management/ThreadImpl.getThreadUserCpuTime1([J[J)V" (ids result) (thread-cpu-times! ids result #t))

;; ThreadInfo, thread dumps and deadlock detection, from what the runtime
;; knows of each live thread: its status bits, the object it blocks or waits
;; on (thread-stats) or its parkBlocker, a monitor's owner, the monitors it
;; holds and the ownable synchronizers it holds (heap-locks). A held monitor's stack depth is -1, as for one a
;; native locked: the runtime doesn't note which frame entered it.
(define (thread-of-id id)
  (let ([tid-slot (jfield-index "java/lang/Thread" "tid")])
    (find (lambda (t) (eqv? id (jslot t tid-slot))) (live-jthreads))))
(define (thread-status-of t)
  (jslot (jslot t (jfield-index "java/lang/Thread" "holder")) (jfield-index "java/lang/Thread$FieldHolder" "threadStatus")))
(define (state-of-thread t) (if (eq? t current-jthread) gil-owner (eq-hashtable-ref thread-states t #f)))
;; the Thread owning o's monitor, without making a monitor for o
(define (monitor-owner-thread o)
  (let* ([m (if (jobj? o) (let ([l (jobj-lock o)]) (and (jmonitor? l) l)) (eq-hashtable-ref monitors o #f))]
         [s (and m (jmonitor-owner m))])
    (and s (jthread-state-jthread s))))
(define aos "java/util/concurrent/locks/AbstractOwnableSynchronizer")
(define (synchronizer-owner o)
  (and (jinstance? o aos) (jslot o (jfield-index aos "exclusiveOwnerThread"))))
;; (lock . owner) for t: a monitor it blocks entering or waits on, with the
;; monitor's owner, or the parkBlocker of a parked thread, with the owner of
;; an ownable synchronizer; (#f . #f) for any other thread
(define (thread-lock t)
  (let ([v (thread-status-of t)])
    (cond [(eq? t current-jthread) '(#f . #f)]
          [(fx= (fxlogand v #x200) #x200)
           (let ([b (jslot t (jfield-index "java/lang/Thread" "parkBlocker"))])
             (cons b (and b (synchronizer-owner b))))]
          [(fx= (fxlogand v #x500) 0) '(#f . #f)]
          [else (let* ([st (eq-hashtable-ref thread-stats t #f)] [o (and st (thread-stat-blocker st))])
                  (cons o (and o (monitor-owner-thread o))))])))

;; The locks every thread holds, found as HotSpot finds ownable
;; synchronizers, by walking the heap: a full collection with Chez's object
;; backreferences on lists every object, so a dump asking for locked
;; monitors or synchronizers costs a collection and a walk (about 120 ms for
;; 4 million objects) and taking a lock costs nothing more. (monitors .
;; synchronizers): each object with a monitor some thread owns, as (o owner
;; . seq), and each ownable synchronizer with an owner, as (o . owner)
(define (heap-locks)
  (enable-object-backreferences #t)
  (explicit-collect! "end of major GC" "Heap Inspection Initiated GC")
  (enable-object-backreferences #f)
  (let ([aos? (let ([memo (make-hashtable string-hash string=?)])
                (lambda (x)
                  (let ([c (class-name-of x)])
                    (let ([v (hashtable-ref memo c 'none)])
                      (if (eq? v 'none)
                          (let ([v (class-assignable? c aos)]) (hashtable-set! memo c v) v)
                          v)))))]
        [mons '()] [syncs '()])
    (define (note-monitor! o m)
      (when (and (jmonitor? m) (jmonitor-owner m))
        (set! mons (cons (cons o (cons (jmonitor-owner m) (jmonitor-seq m))) mons))))
    (for-each (lambda (gen)
                (for-each (lambda (p)
                            (let ([x (car p)])
                              (when (jobj? x)
                                (note-monitor! x (jobj-lock x))
                                (when (aos? x)
                                  (let ([t (synchronizer-owner x)]) (when t (set! syncs (cons (cons x t) syncs))))))))
                          gen))
              (object-backreferences))
    (vector-for-each note-monitor! (hashtable-keys monitors) (hashtable-values monitors))
    (cons mons syncs)))
;; the objects whose monitors s owns, the most recently entered first
(define (held-monitors locks s)
  (map car (list-sort (lambda (a b) (fx> (cddr a) (cddr b)))
                      (filter (lambda (e) (eq? (cadr e) s)) (car locks)))))
(define (held-synchronizers locks t)
  (map car (filter (lambda (e) (eq? (cdr e) t)) (cdr locks))))

;; t's stack for a ThreadInfo: the current thread's from the native's
;; caller with the native's frame on top, as HotSpot shows it, another
;; thread's as it published it; at most depth frames unless depth is -1
(define (thread-info-stack t native depth)
  (let* ([frames (if (eq? t current-jthread)
                     (cons (list "sun/management/ThreadImpl" (car native) (cdr native) -2)
                           (visible-frames (java-frames-of (call/1cc (lambda (k) (raw-frames k))))))
                     (published-frames t))])
    (stack-trace-array (if (and (fx>= depth 0) (fx< depth (length frames))) (list-head frames depth) frames))))
(define (contention-ms ns) (if (fx= contention-enabled 1) (quotient ns 1000000) -1))
;; locks is heap-locks's answer when monitors? or syncs?
(define (thread-info t native depth monitors? syncs? locks)
  (jinit "java/lang/management/ThreadInfo")
  (let* ([lock (thread-lock t)]
         [st (or (eq-hashtable-ref thread-stats t #f) (make-thread-stat 0 0 0 0 #f))]
         [s (state-of-thread t)]
         [held (if (and monitors? s) (held-monitors locks s) '())]
         [info (jnew "java/lang/management/ThreadInfo")])
    ((method-proc "java/lang/management/ThreadInfo"
                  "<init>(Ljava/lang/Thread;ILjava/lang/Object;Ljava/lang/Thread;JJJJ[Ljava/lang/StackTraceElement;[Ljava/lang/Object;[I[Ljava/lang/Object;)V")
     info t (thread-status-of t) (car lock) (cdr lock)
     (thread-stat-blocked st) (contention-ms (thread-stat-blocked-ns st))
     (thread-stat-waited st) (contention-ms (thread-stat-waited-ns st))
     (thread-info-stack t native depth)
     (make-jarr "Ljava/lang/Object;" (list->vector held))
     (make-jarr "I" (make-vector (length held) -1))
     (make-jarr "Ljava/lang/Object;" (list->vector (if syncs? (held-synchronizers locks t) '()))))
    info))
(defnative "sun/management/ThreadImpl.getThreadInfo1([JI[Ljava/lang/management/ThreadInfo;)V" (ids depth result)
  (let ([iv (jarr-vec (nn ids))] [rv (jarr-vec (nn result))])
    (do ([i 0 (fx+ i 1)]) ((fx= i (vector-length iv)))
      (let ([t (thread-of-id (vector-ref iv i))])
        (vector-set! rv i (and t (thread-info t '("getThreadInfo1" . "([JI[Ljava/lang/management/ThreadInfo;)V") depth #f #f #f)))))))
(defnative "sun/management/ThreadImpl.dumpThreads0([JZZI)[Ljava/lang/management/ThreadInfo;" (ids monitors syncs depth)
  (let* ([ts (if ids (map thread-of-id (vector->list (jarr-vec ids))) (live-jthreads))]
         [locks (and (or (eqv? monitors 1) (eqv? syncs 1)) (heap-locks))])
    (make-jarr "Ljava/lang/management/ThreadInfo;"
               (list->vector
                (map (lambda (t)
                       (and t (thread-info t '("dumpThreads0" . "([JZZI)[Ljava/lang/management/ThreadInfo;") depth
                                           (eqv? monitors 1) (eqv? syncs 1) locks)))
                     ts)))))

;; the threads in a cycle of threads each waiting for the next: blocked
;; entering a monitor (in Object.wait's re-entry too) another holds, or,
;; with synchronizers, parked on an ownable synchronizer another owns; null
;; for none, as HotSpot's find_deadlocks answers. A thread behind a cycle
;; isn't in it.
;; the thread t waits for: the owner of the monitor it blocks entering or,
;; with synchronizers, of the ownable synchronizer it's parked on
(define (thread-waits-for t syncs?)
  (let ([v (thread-status-of t)])
    (and (not (eq? t current-jthread))
         (or (fx= v status-blocked) (and syncs? (fx= (fxlogand v #x200) #x200)))
         (let ([o (cdr (thread-lock t))]) (and o (not (eq? o t)) o)))))
;; path's members (newest first) from the newest back to t, oldest first
(define (cycle-in path t)
  (let take ([p path] [acc '()])
    (if (eq? (car p) t) (cons t acc) (take (cdr p) (cons (car p) acc)))))
;; the cycle t's chain of waits runs into, or ()
(define (cycle-from t0 syncs?)
  (let walk ([t t0] [path '()])
    (cond [(memq t path) (cycle-in path t)]
          [(thread-waits-for t syncs?) => (lambda (u) (walk u (cons t path)))]
          [else '()])))
(define (deadlocked-threads syncs?)
  (let ([found (fold-left (lambda (acc t)
                            (fold-left (lambda (acc x) (if (memq x acc) acc (cons x acc))) acc (cycle-from t syncs?)))
                          '() (live-jthreads))])
    (and (pair? found) (make-jarr "Ljava/lang/Thread;" (list->vector (reverse found))))))
(defnative "sun/management/ThreadImpl.findMonitorDeadlockedThreads0()[Ljava/lang/Thread;" () (deadlocked-threads #f))
(defnative "sun/management/ThreadImpl.findDeadlockedThreads0()[Ljava/lang/Thread;" () (deadlocked-threads #t))
(defnative "sun/management/ThreadImpl.resetPeakThreadCount0()V" ()
  (set! threads-peak (length (live-jthreads))))
;; HotSpot starts the times again from zero when monitoring is turned on
(defnative "sun/management/ThreadImpl.setThreadContentionMonitoringEnabled0(Z)V" (on)
  (when (and (eqv? on 1) (fx= contention-enabled 0))
    (vector-for-each (lambda (st) (thread-stat-blocked-ns-set! st 0) (thread-stat-waited-ns-set! st 0))
                     (hashtable-values thread-stats)))
  (set! contention-enabled on))
(defnative "sun/management/ThreadImpl.resetContentionTimes0(J)V" (id)
  (vector-for-each (lambda (t st)
                     (when (or (eqv? id 0) (eqv? id (jslot t (jfield-index "java/lang/Thread" "tid"))))
                       (thread-stat-blocked-ns-set! st 0) (thread-stat-waited-ns-set! st 0)))
                   (hashtable-keys thread-stats) (hashtable-values thread-stats)))

;; The launcher's DestroyJavaVM: wait for every non-daemon thread, then run
;; the shutdown hooks.
(define (await-non-daemon-threads!)
  (let ([others (lambda () (filter (lambda (t) (not (thread-daemon? t))) java-live-threads))])
    (unless (null? (others))
      (gil-block! (lambda () (null? (others))) #f))))

(define (java-shutdown!)
  (await-non-daemon-threads!)
  (jinit "java/lang/Shutdown")
  ((method-proc "java/lang/Shutdown" "shutdown()V")))

;; SIGINT, SIGTERM or SIGHUP: jolt's signal watcher runs its shutdown hooks,
;; one of which (champaca.main) calls this, then ends the process with
;; 128 + the signal, the status HotSpot's exit gives. Java's shutdown hooks
;; run here, as HotSpot runs them on those signals, unless Java's own exit
;; has begun or the VM has already shut down (a normal end, where they ran
;; already). #t when they ran, so the caller saves the startup cache too.
(define (java-signal-shutdown!)
  (jinit "jdk/internal/misc/VM")
  (if (or java-exit-begun (eqv? 1 ((method-proc "jdk/internal/misc/VM" "isShutdown()Z"))))
      #f
      (begin (jinit "java/lang/Shutdown")
             ((method-proc "java/lang/Shutdown" "shutdown()V"))
             #t)))

;; main's ThreadGroup, which jolt's threads and DestroyJavaVM join: the main
;; thread's getThreadGroup() is null once it has ended
(define main-group #f)
(define (boot-threads!)
  (jinit "java/lang/ThreadGroup")
  (let ([system (jnew "java/lang/ThreadGroup")]
        [main (jnew "java/lang/ThreadGroup")])
    (set! main-group main)
    ((method-proc "java/lang/ThreadGroup" "<init>()V") system)
    ((method-proc "java/lang/ThreadGroup" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     main system (jstring "main"))
    (jinit "java/lang/Thread")
    (set! main-thread (jnew "java/lang/Thread"))
    (set! current-jthread main-thread)
    (bind-jthread! main-state main-thread)
    (eq-hashtable-set! jthread-states main-thread main-state)
    (set! interrupted-slot (jfield-index "java/lang/Thread" "interrupted"))
    ((method-proc "java/lang/Thread" "<init>(Ljava/lang/ThreadGroup;Ljava/lang/String;)V")
     main-thread main (jstring "main"))
    (note-native-thread! main-thread)
    (thread-alive! main-thread #t)))
