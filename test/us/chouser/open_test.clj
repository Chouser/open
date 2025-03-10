(ns us.chouser.open-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [us.chouser.open :as open
    :refer [close with-close-fn with-open+]]))

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

(def *acts (atom []))

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

(deftest test-macro
  (testing "no exception"
    (reset! *acts [])
    (let [orig-c (atom :c)]
      (is (= :result
             (with-open+ [{:keys [a]} (with-close-fn {:a 1} (cfn nil))
                          [b] (with-close-fn [2] (cfn nil))
                          c (open/add-close-fn! orig-c (cfn nil))]
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
      (is (= '[:body c [2] {:a 1}] @*acts))
      (is (= '{:hint _b} (-> ex ex-data))))))

(deftest test-composition
  (let [simple (fn [id {:keys [open-err? close-err?]}]
                 (when open-err?
                   (throw (ex-info "open-err" {:err-id id :phase :open})))
                 (swap! *acts conj [:start id])
                 (with-close-fn {:id id} (cfn (when close-err? id))))
        naive-compound (fn [id & [b-errs]]
                         (let [a (simple :a {})
                               b (simple :b b-errs)]
                           (with-close-fn {:id id :a a :b b}
                             (fn [_]
                               (open/close b)
                               (open/close a)))))]
    (testing "naive-compound happy path"
      (reset! *acts [])
      (with-open+ [_ (naive-compound :comp1)]
        ((cfn nil) :body))
      (is (= [[:start :a] [:start :b] :body {:id :b} {:id :a}] @*acts)))
    ;; hmph. Naive compound object misbehaves in face of exceptions
    (testing "naive-compound open b fails"
      (reset! *acts [])
      (let [ex (try
                 (with-open+ [_ (naive-compound :comp1 {:open-err? true})]
                   ((cfn nil) :body))
                 (catch Exception ex ex))]
        (is (= {:err-id :b, :phase :open} (ex-data ex)))
        ;; wrong:
        (is (= [[:start :a]] @*acts))
        ;; should be:
        #_(is (= [[:start :a] {:id :a}] @*acts))))
    (testing "naive-compound close b fails"
      (reset! *acts [])
      (let [ex (try
                 (with-open+ [_ (naive-compound :comp1 {:close-err? true})]
                   ((cfn nil) :body))
                 (catch Exception ex ex))]
        ;; wrong:
        (is (= {:hint '_} (ex-data ex)))
        ;; should be:
        #_(is (= {:hint 'b} (ex-data ex)))

        ;; wrong:
        (is (= [[:start :a] [:start :b] :body {:id :b}] @*acts))
        ;; should be:
        #_(is (= [[:start :a] [:start :b] :body {:id :b} {:id :a}] @*acts))))))

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
