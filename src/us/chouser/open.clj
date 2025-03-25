(ns us.chouser.open)

(defmacro try+
  "Like `try`, but all forms in the `try` are macroexpanded before `catch` and
  `finally` clauses are parsed, allowing general extention. See catch-info and
  safe-finally."
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

#_
(try+
 (throw (ex-info "hello" {:a 2 :b 2}))
 (catch-info {:a 1} {:keys [b]} [:got :b b])
 (catch Error e [:got :err e])
 (safe-finally
   (throw (Error. "ick"))
   (prn 99)))

(defprotocol Closeable
  (close [_]
    "Dispose all resources owned by this object, probably rendering it unusable. Returns nil."))

(defprotocol WithCloseFn
  (-with-close-fn [x f]
    "Return a version of x satisfies Closeable by invoking f on close. If x is
    already Closeable, the closing the returned object must close x before
    calling f."))

(extend-protocol Closeable
  nil (close [_])
  java.io.Closeable (close [x] (.close x))
  java.lang.AutoCloseable (close [x] (.close x))
  clojure.lang.IMeta (close [x]
                       (if-let [c (-> x meta ::close)]
                         (c x)
                         (throw (ex-info "no close fn found" {:closeable x})))
                       nil))

(extend-protocol WithCloseFn
  clojure.lang.IMeta (-with-close-fn [x f1]
                       (let [f2 (if-let [c (-> x meta ::close)]
                                  (fn [_]
                                    (try+
                                     (f1 x)
                                     (finally
                                       (c x))))
                                  f1)]
                         (vary-meta x assoc ::close f2))))

(defn with-close-fn
  "Adds f as metadata to x so that to close x, with-open+ will call f.
  f must be a function of one parameterj the value of x."
  [x f]
  {:pre [(instance? clojure.lang.IFn f)]}
  (vary-meta x assoc ::close f))

(defn add-close-fn!
  "Mutates metadata of x so that to close x, with-open+ will call f.
  f must be a function of one parameterj the value of x."
  [x f]
  {:pre [(instance? clojure.lang.IFn f)]}
  (alter-meta! x assoc ::close f)
  x)

;; It would be straighforward for this to take fns instead of closeables, and
;; for the macro to wrap such a fn around each closeable. Such a close-all would
;; be more flexible, but in a probably useless way that would also either generate
;; more classes #(close %), or embed current value of the close fn in
;; macroexpand sites (partial close) which can frustrate code-reloading and
;; with-redefs. So this function is specific to closing, which seems appropriate
;; for this namespace.
(defn ^:private close-all
  "closeable-pairs must be a sequential of pairs: closeable and hint. Calls
  close on closeables in the order given; if close throws, the exception is
  wrapped to include the associated hint to aid in learning which close threw.
  If an exception is passed in or thrown by a close, any subsequent close
  exceptions are added as suppressed to the original. Returns the resulting
  exception if any."
  [closeable-pairs orig-throwable]
  (reduce (fn [prev-throwable [closeable hint]]
            (try
              (close closeable)
              prev-throwable
              (catch Throwable t
                (let [t (ex-info "Error during closing" {:hint hint} t)]
                  (prn :ca t)
                  (if prev-throwable
                    (doto prev-throwable (.addSuppressed t))
                    t)))))
          orig-throwable
          closeable-pairs))

(defmacro with-open+
  "Like with-open, but:
   - Supports destructuring
   - Uses provided close fn instead of .close
   - Throws original exception (if any) instead of exceptions thrown during closing
   - Attaches exceptions thrown during closing as suppressed to original exception"
  [bindings & body]
  (let [closeables (gensym "closeables")]
    `(let [~closeables (volatile! ())]
       (try
         (let ~(->> bindings
                    (partition-all 2)
                    (mapcat (fn [[bind expr]]
                              `[x# ~expr
                                ~bind (do (vswap! ~closeables conj [x# '~bind]) x#)]))
                    vec)
           ~@body)
         (catch Throwable t#
           (let [t# (#'close-all @~closeables t#)]
             (vreset! ~closeables nil) ;; don't close again in finally
             (throw t#)))
         (finally
           (when-let [t# (#'close-all @~closeables nil)]
             (throw t#)))))))

(defmacro with-compound-open
  "Body may (should?) evaluate to a Closeable"
  [bindings & body]
  (let [closeables (gensym "closeables")]
    `(let [~closeables (volatile! ())]
       (try
         (let ~(->> bindings
                    (partition-all 2)
                    (mapcat (fn [[bind expr]]
                              `[x# ~expr
                                ~bind (do (vswap! ~closeables conj [x# '~bind]) x#)]))
                    vec)
           ;; TODO what if body doesn't support metadata?
           ;; TODO what if body already has its own close -- are we overwriting it?
           (with-close-fn (do ~@body)
             (fn [comp#]
               (when-let [t# (#'close-all (cons [comp# 'compound-open-body] @~closeables) nil)]
                 (throw t#)))))
         (catch Throwable t#
           (let [t# (#'close-all @~closeables t#)]
             ;; just abandon closeables since there's no finally
             (throw t#)))))))

