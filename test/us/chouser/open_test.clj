(ns us.chouser.open-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [us.chouser.open :as open
    :refer [close with-close-fn with-open+ try+ safe-finally]]))

(def *acts (atom []))

(deftest test-safe-finally
  (testing "body throws"
    (reset! *acts [])
    (let [ex (try
               (try+
                (throw (ex-info "x" {:y :z}))
                (safe-finally
                 (swap! *acts conj :f)))
               (catch Exception ex ex))]
      (is (= {:y :z} (ex-data ex)))
      (is (= [:f] @*acts))))
  (testing "finally throws"
    (reset! *acts [])
    (let [ex (try
               (try+
                (swap! *acts conj :body)
                (safe-finally
                 (throw (ex-info "x" {:y :z}))))
               (catch Exception ex ex))]
      (is (= {:y :z} (ex-data ex)))
      (is (= [:body] @*acts))))
  (testing "both throw"
    (reset! *acts [])
    (let [ex (try
               (try+
                (throw (ex-info "x" {:body 1}))
                (safe-finally
                 (throw (ex-info "x" {:finally 1}))))
               (catch Exception ex ex))]
      (is (= {:body 1} (ex-data ex)))
      (is (= {:finally 1} (ex-data (first (.getSuppressed ex))))))))

(deftest test-normal-catch-finally
  (reset! *acts [])
  (is (= :io
         (try+
           (swap! *acts conj :body)
          (throw (java.io.IOException. "foo"))
          (catch java.io.IOException _
            (swap! *acts conj :catch)
            :io)
          (finally
            (swap! *acts conj :finally)))))
  (is (= [:body :catch :finally] @*acts))
  (is (= :ok (try+ :ok))))

(deftest test-catch-info
  (is (= [:got :b 2]
         (try+
          (throw (ex-info "hello" {:a 2 :b 2}))
          (open/catch-info {:a 2} {:keys [b]} [:got :b b])
          (catch Error e [:got :err e])))))

(deftest test-protocol
  (testing "java.io.Close"
    (let [x (java.io.StringReader. "foo")]
      (.read x)
      (is (= nil (close x)))
      (is (thrown? java.io.IOException (.read x)))))

  (testing "java.lang.AutoCloseable"
    (let [x (java.util.Scanner. "foo")]
      (.hasNext x)
      (is (= nil (close x)))
      (is (thrown? IllegalStateException (.hasNext x)))))

  (testing "nil"
    (is (= nil (close nil))))

  (testing "clojure.lang.IObj"
    (let [*y (atom nil)
          x (with-close-fn {:a 1} (fn [y] (reset! *y y)))]
      (is (= nil (close x)))
      (is (= x @*y)))
    (let [ex (try (close {:not :closeable}) (catch Exception ex ex))]
      (is (= {:closeable {:not :closeable}} (ex-data ex))))))

(deftest test-fn-as-closeable
  (testing "happy path"
    (reset! *acts [])
    (let [c (open/fn-as-closeable (fn [f]
                                    (swap! *acts conj :before)
                                    (f [10])
                                    (swap! *acts conj :after)))]
      (is (= [10] c))
      (is (= [:before] @*acts))
      (close c)
      (is (= [:before :after] @*acts))))
  (testing "throw during open"
    (reset! *acts [])
    (let [ex (try
              (open/fn-as-closeable (fn [f]
                                      (swap! *acts conj :before)
                                      (throw (ex-info "open" {:open 1}))
                                      (f [10])
                                      (swap! *acts conj :after)))
              (catch Exception ex ex))]
      (is (= [:before] @*acts))
      (is (= {:open 1} (ex-data ex)))))
  (testing "throw during close"
    (reset! *acts [])
    (let [c (open/fn-as-closeable (fn [f]
                                     (swap! *acts conj :before)
                                     (f [10])
                                     (swap! *acts conj :after)
                                     (throw (ex-info "close" {:close 1}))))
          ex (try (close c)
                  (catch Exception ex ex))]
      (is (= [10] c))
      (is (= [:before :after] @*acts))
      (is (= {:close 1} (ex-data ex))))))

(defn cfn [err-id]
  (fn [act]
    (swap! *acts conj act)
    (when err-id
      (throw (ex-info "test-err" {:err-id err-id})))))

(deftest test-close-all
  (testing "no close errors"
    (reset! *acts [])
    (is (= nil (#'open/close-all [[(with-close-fn [:a] (cfn nil))]
                                  [(with-close-fn [:b] (cfn nil))]
                                  [(with-close-fn [:c] (cfn nil))]]
                                 nil)))
    (is (= [[:a] [:b] [:c]] @*acts)))
  (testing "early close throws"
    (reset! *acts [])
    (let [ex (#'open/close-all [[(with-close-fn [:a] (cfn :t1)) :h1]
                                [(with-close-fn [:b] (cfn nil)) :h2]]
                               nil)]
      (is (= [[:a] [:b]] @*acts))
      (is (= {:hint :h1} (-> ex ex-data)))
      (is (= {:err-id :t1} (-> ex ex-cause ex-data)))))
  (testing "late close throws"
    (reset! *acts [])
    (let [ex (#'open/close-all [[(with-close-fn [:a] (cfn nil)) :h1]
                                [(with-close-fn [:b] (cfn :t2)) :h2]]
                               nil)]
      (is (= [[:a] [:b]] @*acts))
      (is (= {:hint :h2} (-> ex ex-data)))
      (is (= {:err-id :t2} (-> ex ex-cause ex-data)))))
  (testing "only body throws"
    (reset! *acts [])
    (let [ex (#'open/close-all [[(with-close-fn [:a] (cfn nil)) :h1]
                                [(with-close-fn [:b] (cfn nil)) :h2]]
                               (ex-info "test body throw" {:err-id :body}))]
      (is (= [[:a] [:b]] @*acts))
      (is (= {:err-id :body} (-> ex ex-data)))))
  (testing "body and close both throw"
    (reset! *acts [])
    (let [ex (#'open/close-all [[(with-close-fn [:a] (cfn :t1)) :h1]
                                [(with-close-fn [:b] (cfn nil)) :h2]]
                               (ex-info "test body throw" {:err-id :body}))]
      (is (= [[:a] [:b]] @*acts))
      (is (= {:err-id :body} (-> ex ex-data)))
      (is (= {:hint :h1} (-> ex .getSuppressed first ex-data))))))

(deftest test-with-open+
  (testing "no exception"
    (reset! *acts [])
    (let [orig-c (atom :c)]
      (is (= :result
             (with-open+ [{:keys [a]} (with-close-fn {:a 1} (cfn nil))
                          [b] (with-close-fn [2] (cfn nil))
                          c (with-close-fn orig-c (cfn nil))]
               (is (= a 1))
               (is (= b 2))
               (is (= @c :c))
               ((cfn nil) :body)
               :result)))
      (is (= [:body orig-c [2] {:a 1}] @*acts))))
  (testing "body exception"
    (reset! *acts [])
    (let [ex (try
               (with-open+ [_ (with-close-fn {:a 1} (cfn nil))
                            _ (with-close-fn [2] (cfn nil))
                            _ (with-close-fn 'c (cfn nil))]
                 ((cfn nil) :body)
                 (throw (ex-info "body throw" {:err-id :body})))
               (catch Exception ex ex))]
      (is (= '[:body c [2] {:a 1}] @*acts))
      (is (= {:err-id :body} (-> ex ex-data)))))
  (testing "close exception hint"
    (reset! *acts [])
    (let [ex (try
               (with-open+ [_a (with-close-fn {:a 1} (cfn nil))
                            _b (with-close-fn [2] (cfn :ex2))
                            _c (with-close-fn 'c (cfn nil))]
                 ((cfn nil) :body))
               (catch Exception ex ex))]
      (is (instance? Throwable ex))
      (is (= '[:body c [2] {:a 1}] @*acts))
      (is (= '{:hint _b} (-> ex ex-data))))))

(deftest test-composition
  (let [simple (fn [id {:keys [open-err? close-err?]}]
                 (when open-err?
                   (throw (ex-info "open-err" {:err-id id :phase :open})))
                 (swap! *acts conj [:start id])
                 (with-close-fn {:id id} (cfn (when close-err? id))))
        compose (fn [id & [b-errs]]
                  (open/compose-closeable
                   [a (simple :a {})
                    b (simple :b b-errs)]
                   {:id id :a a :b b}))]
    (testing "compose happy path"
      (reset! *acts [])
      (with-open+ [_ (compose :comp1)]
        ((cfn nil) :body))
      (is (= [[:start :a] [:start :b] :body {:id :b} {:id :a}] @*acts)))
    (testing "compose open b fails"
      (reset! *acts [])
      (let [ex (try
                 (with-open+ [_ (compose :comp1 {:open-err? true})]
                   ((cfn nil) :body))
                 (catch Exception ex ex))]
        (is (= {:err-id :b, :phase :open} (ex-data ex)))
        (is (= [[:start :a] {:id :a}] @*acts))))
    (testing "compose close b fails"
        (reset! *acts [])
        (prn :begin)
        (let [ex (try
                   (close (compose :comp1 {:close-err? true}))
                   (catch Exception ex ex))]
          (is (= {:hint 'b} (ex-data ex)))
          (is (= [[:start :a] [:start :b] {:id :b} {:id :a}] @*acts))))))

(deftest examples
  (is (= nil
         (with-open+ [x (reify
                          Object (toString [_] "object x")
                          java.lang.AutoCloseable (close [_] (prn :close-x)))]
           (prn :body (str x)))))

  (let [ex (try
             (with-open+ [a (with-close-fn [:object-a] (fn [_] (prn :close-a)))
                          b (with-close-fn [:object-b] (fn [_] (throw (ex-info "close failed" {}))))]
               (prn :body)
               (throw (ex-info "body failed" {})))
             (catch Exception ex ex))]
    (is (= "body failed" (.getMessage ex))))

  (let [my-map-resource
        (with-close-fn {:type :map-resource, :value 10}
          #(println "Closing map resource:" %))]
    (is (= nil
           (with-open+ [{resource-type :type, resource-value :value} my-map-resource]
             (println "Resource type:" resource-type)
             (println "Resource value:" resource-value))))))



#_
(let [c (compose-closeable [x o1
                            y o2]
                           {:x x :y y})
      prep-stuff 123]
  (reify Foo
    ...
    Closeable
    (close [_]
      (try+
       my-close
       (safe-finally
        (close c))))))

#_
(compose-closeable [x o1
                    y o2
                    mine (let [prep-stuff 123]
                           (with-close-fn {... ...}
                             (fn [x] my-close)))]
                   mine)