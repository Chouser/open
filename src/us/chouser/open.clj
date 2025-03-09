(ns us.chouser.open)

(defprotocol Closeable
  (close [_]
    "Dispose all resources owned by this object, probably rendering it unusable. Returns nil."))

(extend-protocol Closeable
  nil (close [_])
  java.io.Closeable (close [x] (.close x))
  java.lang.AutoCloseable (close [x] (.close x))
  clojure.lang.IMeta (close [x]
                       (if-let [c (-> x meta ::close)]
                         (c x)
                         (throw (ex-info "no close fn found" {:closeable x})))
                       nil))

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