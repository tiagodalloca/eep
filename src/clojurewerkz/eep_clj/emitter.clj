(ns ^{:doc "Event Emitter, right now it's a poor man's clone of "}
  clojurewerkz.eep-clj.emitter
  (:require [clojure.set :as clj-set])
  (:import [java.util.concurrent Executors]))

(def global-handler :___global)

(def pool-size (-> (Runtime/getRuntime)
                   (.availableProcessors)
                   inc))

(defn make-executor
  []
  (Executors/newFixedThreadPool pool-size))


(defprotocol IEmitter
  (add-handler [_ t f initial-state] [_ f initial-state] "Adds handler to the current emmiter.
Handler state is stored in atom, that is first initialized with `initial-state`.

  2-arity version: `(event-type f initial-state)`

`(f handler-state new-value)` is a function of 2 arguments, first one is current Handler state,
second one is a new value. Function return becomes a new Handler state.

  3-arity version: `(event-type f)`

`(f handler-state)` is a function of 1 argument, that's used to add a Stateless Handler,
potentially having side-effects. By enclosing emitter you can achieve capturing state of all
or any handlers.")
  (delete-handler [_ t f] "Removes the handler `f` from the current emitter, that's used for event
type `t`. ")
  (which-handlers [_] [_ t] "Returns all currently registered Handlers for Emitter")
  (flush-futures [_] "Under some circumstances, you may want to make sure that all the pending tasks
are executed by some point. By calling `flush-futures`, you force-complete all the pending tasks.")
  (notify [_ type args] "Asynchronous (default) event dispatch function. All the Handlers (both
stateful and stateless).")
  (! [_ type args] "Erlang-style alias for `notify`")
  (sync-notify [_ type args] "Synchronous event dispatch function. Dispatches an event to all the
handlers (both stateful and stateless), waits until each handler completes synchronously.")
  (swap-handler [_ t old-f new-f] "Replaces `old-f` event handlers with `new-f` event handlers for type
`t`")
  (stop [_] "Cancels all pending tasks, stops event emission."))

(defprotocol IHandler
  (run [_ args])
  (state [_]))

(defmacro run-async
  [executor h & args]
  `(let [runnable# (fn [] (run ~h ~@args))]
     (.submit ~executor runnable#)))

(defn- collect-garbage
  "As we may potentially accumulate rather large amount of futures, we have to garbage-collect them."
  [futures]
  (filter #(not (.isDone %)) futures))

(deftype Handler [handler state_]
  IHandler
  (run [_ args]
    (swap! state_ handler args))

  (state [_]
    @state_)

  Object
  (toString [_]
    (str "Handler: " handler ", state: " @state_) ))

(deftype StatelessHandler [handler]
  IHandler
  (run [_ args]
    (handler args))
  (state [_]
    nil))

(defn- get-handlers
  [t handlers]
  (clj-set/union (t handlers) (global-handler handlers)))

(defn- add-handler-intern
  [handlers event-type handler]
  (swap! handlers #(update-in % [event-type]
                              (fn [v]
                                (if (nil? v)
                                  #{handler}
                                  (conj v handler))))))

(deftype Emitter [handlers futures executor]
  IEmitter
  (add-handler [_ event-type f initial-state]
    (add-handler-intern handlers event-type (Handler. f (atom initial-state))))

  (add-handler [_ event-type f]
    (add-handler-intern handlers event-type (StatelessHandler. f)))

  (delete-handler [_ event-type f]
    (swap! handlers (fn [h]
                      (update-in h [event-type]
                                   (fn [v]
                                     (disj v (first (filter #(= f (.handler %)) v))))))))

  (notify [_ t args]
    (doseq [h (get-handlers t @handlers)]
      (swap! futures conj (run-async executor h args)))
    (swap! futures collect-garbage))

  (flush-futures [_]
    (doseq [future @futures] (if-not (.isDone future) (.get future))))

  ;; TODO: we may want to add third function, which is dispatching all given handlers in
  ;; parallel, although waits for _all_ of them, rather than _each one_ of them to complete

  (sync-notify [_ t args]
    (doseq [h (get-handlers t @handlers)]
      (run h args)))

  (! [this t args]
    (notify this t args))

  (which-handlers [_]
    @handlers)

  (which-handlers [_ t]
    (t @handlers))

  (toString [_]
    (str "Handlers: " (mapv #(.toString %) @handlers))))

(defn new-emitter
  "Creates a fresh Event Emitter with a default executor (Fixed Thread Pool, with pool size of `available processors + 1"
  []
  (Emitter. (atom {}) (atom []) (make-executor)))
