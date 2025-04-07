# us.chouser.open

This library provides extended versions of Clojure's `try` and `with-open`
operators, named `try+` and `with-open+`.

```clojure
;; deps.edn
{:deps {us.chouser/open {:git/url "https://github.com/chouser/open"
                         :git/tag "v1.0"
                         :git/sha "4866d47"}}}
```
## Overview

This library provides:

- `with-open+` - Enhanced version of Clojure's `with-open` with destructuring and better exception handling
- `compose-closeable` - Combine multiple closeables into a single closeable
- `with-close-fn` - Attach custom close functions to Clojure data structures
- `fn-as-closeable` - Convert functional resource patterns to closeables
- `try+` - Extensible try/catch
- `safe-finally` - Alternative to `finally` that suppresses secondary exceptions in `try+`
- `catch-info` - Pattern matching for ex-info exceptions in `try+`

## Rationale

<img src="images/flower-logo.svg" style="width: 400px" align="right">

#### Entangled capabilities
Clojure's `with-open` allows you to use objects with separate _open_ and
_closed_ states in a way that guarantees the _close_ will happen if the _open_
succeeds. Such objects have an implicit contract where it is your responsibility
to know what the _close_ method is and to make sure you call it at some point
after the open. The `with-open` macro allows you to make this explicit by tying
the object to a lexical scope, and then it takes the responsibility to make sure
`.close` is called with or without exceptions being thrown.

The `with-open` macro also allows you to group multiple such objects together,
properly handling the possibility of a later _open_ throwing an exception and
therefore needing to close the previously opened objects, even though the body
of the `with-open` site is in this case never reached.

These two key capabilities are entangled in `with-open`. If you want the
grouping mechanism, you must also tie the group to a lexical scope and a single
thread. This library provides a `compose-closeable` macro that provides the
composition capability by itself. It opens multiple objects and, if those are
successful, returns a single composition object with a `close` method that
closes all the contained objects.

#### Lost exceptions

When a close method runs, it may throw an exception. If the reason for closing is
an exception from the `with-open` site body, there are now two exceptions in
play, but only one may be the primary one thrown. A similar scenario is when
there was no exception from the body, but more than once _close_ throws.
Clojure's `with-open` favours the most recently thrown exception (which would
always be from a _close_), and any others are completely lost. This library
attaches all secondary exceptions to the primary using Java's `.addSuppressed`
method.

Which exception to treat as the primary is a judgement call, but in my
experience the _first_ exception is usually the most helpful one, so this
library uses that as the primary. This is also the behavior of Java's
[_try-with-resources_](https://docs.oracle.com/javase/8/docs/technotes/guides/language/try-with-resources.html)
syntax. This suppression pattern is built into `compose-closeable` and
`with-open+` macros, and can be opted-into using `safe-finally` in `try+`.

The `with-open+` and `compose-closeable` macros also wrap any exception from a
_close_ in an ex-info that includes a `:hint` indicating which binding is
responsible for the object that threw. This is meant to aid debugging in the
face of the line numbers in the stack trace being generally unhelpful in this
case.

#### Close functions via metadata

Creating new closeable objects (via composition or otherwise) presents the
desire to make such objects be good ol' Clojure maps, rather than needing to
`reify` an interface or protocol. Clojure has demonstrated putting functions in
metadata as a way to provide methods on objects [since the
beginning](https://github.com/clojure/clojure/blame/master/src/clj/clojure/zip.clj),
an approach that fits well here. This library provides a `with-close-fn`
function to chain _close_ functions on objects that satisfy the `UpdateCloseFn`
protocol, including Clojure objects that support metadata.

Since Clojure collection types can now easily support the `Closeable` protocol, it might be convenient to destructure these objects, so `with-open+` and `compose-closeable` support destructuring.

#### General resource lifecycle management

Together these features provide a sufficient alternative to systems like [Sierra Components](https://github.com/stuartsierra/component) for general management of a system of objects that have _open/close_ or _start/stop_ lifecycles. The tradeoffs are interesting and somewhat subtle. Some of the key differences include:

* This library does not manage the state between _create_ and _open_: it is not responsible for an object until it's open. Whereas Component is given created but not-yet-started objects and it takes the responsibility of calling all the start methods.
* This library does not manage the dependency graph between objects. Instead it is the user's responsibility to create objects in a correct order, passing objects opened earlier as parameters to objects that need to be created later. This explicit ordering and normal lexical scope for parameters may be preferable.

#### Extensible `try+` macro

The suppression of secondary expressions could be useful in other scenarios and
would be convenient as a `safe-finally` clause in a `try` block.  However,
Clojure's `try` operator is a special form that parses out `catch` and `finally`
clauses _before_ doing any macroexpansion, making unsuitable for extension.

This library provides a `try+` macro that is fully backward compatible with
Clojure's `try`, but supports `safe-finally` and also macroexpands all the
contained forms before parsing `catch` and `finally` clauses. This allows you to
use macros that expand to `catch` or `finally`. A `catch-info` macro is provided
as an example of such, for convenient handling of Clojure ex-info exceptions.

## Usage

### `with-open+`

The `with-open+` macro is (mostly) backwards-compatible with Clojure's built-in `with-open`:

```clojure
(require '[us.chouser.open :refer [with-open+ with-close-fn]
         '[clojure.java.io :as io]])

(with-open+ [r1 (io/reader (java.io.StringReader. "hello"))
             r2 (io/reader (java.io.StringReader. "world"))]
  (println (.readLine r1) (.readLine r2)))
```

You can use `with-close-fn` to make your own Closeables. When used on
collections that support destructuring, the destructuring works in `with-open+`
and `compose-closeable`:

```clojure
(with-open+ [{:keys [a]} (with-close-fn (do (println "before")
                                            {:a 1, :b 2})
                           (fn [{:keys [b]}]
                             (println "after" b)))]
  (println "middle" a))
```

You can also use `with-close-fn` on reference objects like Atoms, but beware it
_mutates_ the metadata of such objects:

```clojure
(with-open+ [a (with-close-fn (atom 1)
                 #(swap! % inc))]
  (println "a:" @a))
```

If both the body and a close throw exceptions, `with-open+` rethrows the body
exception. This is different from the exception you'd see with Clojure's
`with-open`:

```clojure
(with-open+ [bad-closeable (with-close-fn [:object-a]
                             (fn [_] (throw (ex-info "close failed" {}))))]
  (throw (ex-info "body failed" {})))
;; throws "body failed"
```

You can use `.getSuppressed` on the thrown exception to see any exceptions thrown during closing:

```clojure
(.getSuppressed *e)

[#error {
 :cause "close failed"
 :data {}
 :via [{:type clojure.lang.ExceptionInfo
        :message "Error during closing"
        :data {:hint bad-closeable}}      ;; <-- the :hint indicates which clause threw
       {:type clojure.lang.ExceptionInfo
        :message "close failed"
        :data {}}]}]
```

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