(ns us.chouser.open
  (:import (java.util.concurrent.locks LockSupport)))

(defmacro try+
  "Like `try`, but all forms are macroexpanded before `catch` and `finally`
  clauses are parsed, allowing extention. See catch-info and safe-finally."
  [& forms]
  (let [forms (->> forms
                   (map (fn [form]
                          (case (and (seq? form) (first form))
                            catch form
                            finally form
                            (macroexpand form)))))
        [forms safe-finally] (if (and (seq? (last forms))
                                      (= `safe-finally-mark (first (last forms))))
                               [(drop-last forms) (last forms)]
                               [forms nil])
        main-try (with-meta (cons 'try forms) (meta &form))]
    (if-not safe-finally
      main-try
      ;; t-sym is used to save the orig throwable in case its needed in the finally
      (let [t-sym (gensym)]
        `(let [~t-sym (volatile! nil)]
           (try
             ~main-try
             (catch Throwable t#
               (vreset! ~t-sym t#))
             (finally
               (try
                 ~@(rest safe-finally)
                 ;; let any original exception flow out here
                 (catch Throwable t#
                   (throw (if-let [orig-t# @~t-sym]
                            (doto orig-t# (.addSuppressed t#))
                            t#)))))))))))

(defmacro safe-finally
  "Can be used in try+ instead of `finally`. Any exception thrown in
  safe-finally, after some original exception is thrown in the try body or a
  catch clause, is added as suppressed on the original exception and the
  original is thrown instead."
  [& finally-body]
  (cons `safe-finally-mark finally-body))

(defn submap= [a b]
  (= a (select-keys b (keys a))))

(defmacro catch-info [& triples]
  (let [data (gensym)]
    `(catch clojure.lang.ExceptionInfo ex#
       (let [~data (vary-meta (ex-data ex#) assoc ::exception ex#)]
         (condp submap= ~data
           ~@(->> triples
                  (partition-all 3)
                  (mapcat (fn [[ptn binding then]]
                            `[~ptn (let [~binding ~data] ~then)])))
           (throw ex#))))))

(defprotocol UpdateCloseFn
  (get-close-fn [x]
    "Returns the close function attached to x if any, otherwise nil")
  (set-close-fn [x f]
    "Returns a version of x with its close function set to f. Mutates x if necessary."))

(extend-protocol UpdateCloseFn
  clojure.lang.IReference
  (get-close-fn [x] (-> x meta ::close))
  (set-close-fn [x f] (alter-meta! x assoc ::close f) x)
  clojure.lang.IObj
  (get-close-fn [x] (-> x meta ::close))
  (set-close-fn [x f] (vary-meta x assoc ::close f)))

(defn with-close-fn
  "Return a version of x that satisfies Closeable `close` by invoking f. If x is
  already Closeable, the returned object will close x before calling f. x must
  satisfy UpdateCloseFn and will be mutated if necessary. Clojure collection
  and reference types satisfy UpdateCloseFn"
  [x f1]
  {:pre [(satisfies? UpdateCloseFn x)
         (instance? clojure.lang.IFn f1)]}
  (let [f2 (if-let [c (get-close-fn x)]
             (fn [_]
               (try+
                (f1 x)
                (safe-finally
                 (c x))))
             f1)]
    (set-close-fn x f2)))

(defprotocol Closeable
  (close [_]
    "Dispose all resources owned by this object, probably rendering it unusable. Returns nil."))

(extend-protocol Closeable
  nil (close [_]) ;; close on nil is supported and does nothing
  java.io.Closeable (close [x] (.close x))
  java.lang.AutoCloseable (close [x] (.close x))
  java.lang.Object (close [x] ;; default implemenataion relies on UpdateCloseFn
                     (if-let [c (get-close-fn x)]
                       (do
                         (prn :close-fn c)
                         (c x))
                       #_(throw (ex-info "no close fn found" {:closeable x})))
                     nil))

;; TODO make this provice a new closeable-value protocol instead
(defn fn-as-closeable
  "Invokes (f g) in a virtual thread, where g is a function of one arg. Returns
  a Closeable. When f invokes (g x), x will be returned with a close-fn added
  using with-close-fn such that when closed, g will return so that f can do
  closing behaviors. This is meant to act as an adapter to jarohen/with-open
  functions."
  [f]
  (let [*result (promise)
        thread (-> (Thread/ofVirtual)
                   (.start (bound-fn []
                             (try
                               (f (fn [x]
                                    (deliver *result {:return x})
                                    (LockSupport/park)
                                    nil))
                               (catch Throwable t
                                 (deliver *result {:thrown t}))))))
        {:keys [return thrown]} @*result]
    (when thrown (throw thrown))
    (with-close-fn return (fn [_]
                            (LockSupport/unpark thread)
                            (.join thread)))))

;; It would be straighforward for this to take fns instead of closeables, and
;; for the macro to wrap such a fn around each closeable. Such a close-all would
;; be more flexible, but in a probably useless way that would also either generate
;; more classes #(close %), or embed current value of the close fn in
;; macroexpand sites (partial close) which can frustrate code-reloading and
;; with-redefs. So this function is specific to closing, which seems appropriate
;; for this namespace.
(defn close-all
  "closeable-pairs must be a sequential of pairs: closeable and hint. Calls
  close on closeables in the order given; if close throws, the exception is
  wrapped to include the associated hint to aid in learning which close threw.
  If an exception is passed in or thrown by a close, any subsequent close
  exceptions are added as suppressed to the original. Returns the resulting
  exception if any."
  [closeable-pairs orig-throwable]
  (reduce (fn [prev-throwable [closeable hint]]
            (try
              (prn :close hint)
              (close closeable)
              (prn :done-close hint)
              prev-throwable
              (catch Throwable t
                (prn :attach (.getMessage t) :to prev-throwable)
                (let [t (ex-info "Error during closing" {:hint hint} t)]
                  (if prev-throwable
                    (doto prev-throwable (.addSuppressed t))
                    t)))))
          orig-throwable
          closeable-pairs))

(defn collect-closeable [*cs c hint]
  (vswap! *cs conj [c hint])
  c)

(defmacro compose-closeable
  "Binds locals to expression values and returns the body, like `let`. However,
  each bound value must satisfy Closeable and the body must satisfy
  UpdateCloseFn. Before the body value is returned, with-close-fn is used to add
  a close fn that calls close on all the bound values in reverse order. If an
  exception is thrown at any time during binding or close, all values are
  closed. If no exception is thrown, the caller is responsible for calling close
  on the returned body value."
  [bindings & body]
  (let [closeables (gensym "closeables")]
    `(let [~closeables (volatile! ())]
       (try
         (let [~@(->> (partition-all 2 bindings)
                      (mapcat (fn [[bind expr]]
                                `[~bind (collect-closeable ~closeables ~expr '~bind)])))]
           (with-close-fn
             (do ~@body)
             (fn [_#]
               (when-let [t# (close-all @~closeables nil)]
                 (throw t#)))))
         (catch Throwable t#
           (throw (close-all @~closeables t#)))))))

(defmacro with-open+
  "Like with-open, and backward-compatible with it, but also:
   - Supports destructuring
   - Uses Closeable protocol instead of .close. See with-close-fn
   - Throws original exception (if any) instead of exceptions thrown during
     closing (this is a breaking change from with-open)
   - Attaches exceptions thrown during closing as suppressed to original exception"
  [bindings & body]
  `(let [c# (compose-closeable [~@bindings
                                body# (set-close-fn [(do ~@body)] (fn [x#] (prn :close-body x#)))]
                                (prn :body (get-close-fn body#))
                               body#)]
     (prn :pre-close)
     (close c#)
     (prn :post-close)
     (nth c# 0)))
