# us.chouser.open

A library that provides a `with-open+` macro that addresses some shortcomings of Clojure's standard `with-open`, offering:

<img src="images/flower-logo.svg" style="width: 400px" align="right">

- **Destructuring Support:** Bindings in `with-open+` fully support destructuring, just like `let`.
- **Custom Close Functions:** You can specify a custom closing function for each resource via a protocol, interface, or metadata.
- **Preserves Original Exception:** If an exception occurs in the body, `with-open+` throws the *original* exception, not one from the closing process.
- **Suppressed Close Exceptions:** Exceptions that occur during resource closing are attached as suppressed exceptions to the original body exception, providing comprehensive error information.
- **Hints for Close Errors:**  Exceptions during closing include hints to help identify which resource failed to close, simplifying debugging.

## Installation

```clojure
;; deps.edn
{:deps {us.chouser/open {:git/url "https://github.com/chouser/open"
                         :git/tag "v1.0"}}}
```

## Usage

### Basic Usage

The `with-open+` macro works similarly to Clojure's built-in `with-open`, but adds several important enhancements:

```clojure
(require '[us.chouser.open :refer [with-open+ with-close-fn]])

;; Simple usage with Java closeables
(with-open+ [reader (java.io.StringReader. "hello world")]
  (println (.read reader)))

;; Using destructuring
(with-open+ [{:keys [a b]} (get-resource-that-needs-closing)]
  (do-something-with a b))

;; Multiple clauses are closed in reverse order
(with-open+ [a (io/reader "a.txt")
             b (io/reader "b.txt")]
  (read-from-both a b))
```

### Custom Close Functions

For non-Closeable objects, you can attach a custom close function using `with-close-fn`:

```clojure
;; Define a custom resource
(def my-map-resource
  (with-close-fn {:type :map-resource, :value 10}
                 #(println "Closing map resource:" %)))

;; Use it in with-open+
(with-open+ [{resource-type :type, resource-value :value} my-map-resource]
  (println "Resource type:" resource-type)
  (println "Resource value:" resource-value))

;; Prints:
;; Resource type: :map-resource
;; Resource value: 10
;; Closing map resource: {:type :map-resource, :value 10}
```

For mutable reference objects like `atom`, use `add-close-fn!` instead. And of
course you can still define an object that implements `java.lang.AutoCloseable` or
`java.io.Closeable` interfaces or the `us.chouser.open/Closeable` protocol:

```clojure
(with-open+ [x (reify
                 Object (toString [_] "object x")
                 java.lang.AutoCloseable (close [_] (prn :close-x)))]
  (prn :body (str x)))
;; Prints:
;; :body "object x"
;; :close-x
```

### Improved Exception Behavior

One of the key improvements in `with-open+` is its handling of exceptions. When an exception occurs:

1. The original exception is preserved as the primary exception
2. Any exceptions during resource closing are attached as suppressed exceptions
3. Diagnostic information is added to help identify which resource failed to close

```clojure
(with-open+ [a (with-close-fn [:object-a] (fn [_] (prn :close-a)))
             b (with-close-fn [:object-b] (fn [_] (throw (ex-info "close failed" {}))))]
  (prn :body)
  (throw (ex-info "body failed" {})))
;; Prints:
;; :body
;; :close-a
;; Throws:
;; Execution error (ExceptionInfo)...
;; body failed
```

As with Clojure's `with-open`, event after the body throws an exception, `b` is
closed. And even thought `b` threw during closing, `a` is closed anyway.

But `with-open+` differs in that it suppresses the closing error and instead
throws the original `body failed` exception.

You can use `.getSuppressed` on the thrown exception to see any exceptions thrown during closing:

```clojure
(.getSuppressed *e)

[#error {
 :cause "close failed"
 :data {}
 :via [{:type clojure.lang.ExceptionInfo
        :message "Error during closing"
        :data {:hint b}                 ;; <-- the :hint indicates which clause threw
        :at [us.chouser.open$close_all$fn__19366 invoke "open.clj" 52]}
       {:type clojure.lang.ExceptionInfo
        :message "close failed"
        :data {}
        :at [us.chouser.open_test$eval20087$fn__20090 invoke "NO_SOURCE_FILE" 122]}]}]
```

### Supported Closeable Types

The `Closeable` protocol is extended to:

- `java.io.Closeable` (calls `.close`)
- `java.lang.AutoCloseable` (calls `.close`)
- `clojure.lang.IMeta` (uses the `:us.chouser.open/close` metadata function)
- `nil` (no-op)

## Comparison with jarohen/with-open

This library was inspired by James Henderson's
[jarohen/with-open](https://github.com/jarohen/with-open) and David McNeil's
[fork of it](https://github.com/david-mcneil/with-open) which adds the exception
suppression among other things.

A key difference is that this library does not support the callback mechanism because that pattern has some drawbacks:
  1. Creates extra stack frames in traces, multiple for each clause in the bindings
  2. Can tempt developers to use it to obscure dynamic var binding

The metadata approach used here is less flexible because it requires that the
closeable support metadata. If you prefer the callback approach, use one of
those libraries. They've served me well.

## License

Copyright © 2025 Chris Houser

Distributed under the Eclipse Public License version 1.0