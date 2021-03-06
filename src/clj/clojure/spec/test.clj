;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.spec.test
  (:refer-clojure :exclude [test])
  (:require
   [clojure.pprint :as pp]
   [clojure.spec :as s]
   [clojure.spec.gen :as gen]
   [clojure.string :as str]))

(in-ns 'clojure.spec.test.check)
(in-ns 'clojure.spec.test)
(alias 'stc 'clojure.spec.test.check)

(defn- throwable?
  [x]
  (instance? Throwable x))

(defn ->sym
  [x]
  (@#'s/->sym x))

(defn- ->var
  [s-or-v]
  (if (var? s-or-v)
    s-or-v
    (let [v (and (symbol? s-or-v) (resolve s-or-v))]
      (if (var? v)
        v
        (throw (IllegalArgumentException. (str (pr-str s-or-v) " does not name a var")))))))

(defn- collectionize
  [x]
  (if (symbol? x)
    (list x)
    x))

(defn enumerate-namespace
  "Given a symbol naming an ns, or a collection of such symbols,
returns the set of all symbols naming vars in those nses."
  [ns-sym-or-syms]
  (into
   #{}
   (mapcat (fn [ns-sym]
             (map
              (fn [name-sym]
                (symbol (name ns-sym) (name name-sym)))
              (keys (ns-interns ns-sym)))))
   (collectionize ns-sym-or-syms)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; instrument ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private ^:dynamic *instrument-enabled*
  "if false, instrumented fns call straight through"
  true)

(defn- fn-spec?
  "Fn-spec must include at least :args or :ret specs."
  [m]
  (or (:args m) (:ret m)))

(defmacro with-instrument-disabled
  "Disables instrument's checking of calls, within a scope."
  [& body]
  `(binding [*instrument-enabled* nil]
     ~@body))

(defn- interpret-stack-trace-element
  "Given the vector-of-syms form of a stacktrace element produced
by e.g. Throwable->map, returns a map form that adds some keys
guessing the original Clojure names. Returns a map with

  :class         class name symbol from stack trace
  :method        method symbol from stack trace
  :file          filename from stack trace
  :line          line number from stack trace
  :var-scope     optional Clojure var symbol scoping fn def
  :local-fn      optional local Clojure symbol scoping fn def

For non-Clojure fns, :scope and :local-fn will be absent."
  [[cls method file line]]
  (let [clojure? (contains? '#{invoke invokeStatic} method)
        demunge #(clojure.lang.Compiler/demunge %)
        degensym #(str/replace % #"--.*" "")
        [ns-sym name-sym local] (when clojure?
                                  (->> (str/split (str cls) #"\$" 3)
                                       (map demunge)))]
    (merge {:file file
            :line line
            :method method
            :class cls}
           (when (and ns-sym name-sym)
             {:var-scope (symbol ns-sym name-sym)})
           (when local
             {:local-fn (symbol (degensym local))}))))

(defn- stacktrace-relevant-to-instrument
  "Takes a coll of stack trace elements (as returned by
StackTraceElement->vec) and returns a coll of maps as per
interpret-stack-trace-element that are relevant to a
failure in instrument."
  [elems]
  (let [plumbing? (fn [{:keys [var-scope]}]
                    (contains? '#{clojure.spec.test/spec-checking-fn} var-scope))]
    (sequence (comp (map StackTraceElement->vec)
                    (map interpret-stack-trace-element)
                    (filter :var-scope)
                    (drop-while plumbing?))
              elems)))

(defn- spec-checking-fn
  [v f fn-spec]
  (let [fn-spec (@#'s/maybe-spec fn-spec)
        conform! (fn [v role spec data args]
                   (let [conformed (s/conform spec data)]
                     (if (= ::s/invalid conformed)
                       (let [caller (->> (.getStackTrace (Thread/currentThread))
                                         stacktrace-relevant-to-instrument
                                         first)
                             ed (merge (assoc (s/explain-data* spec [role] [] [] data)
                                         ::s/args args
                                         ::s/failure :instrument-check-failed)
                                       (when caller
                                         {::caller (dissoc caller :class :method)}))]
                         (throw (ex-info
                                 (str "Call to " v " did not conform to spec:\n" (with-out-str (s/explain-out ed)))
                                 ed)))
                       conformed)))]
    (fn
     [& args]
     (if *instrument-enabled*
       (with-instrument-disabled
         (when (:args fn-spec) (conform! v :args (:args fn-spec) args args))
         (binding [*instrument-enabled* true]
           (.applyTo ^clojure.lang.IFn f args)))
       (.applyTo ^clojure.lang.IFn f args)))))

(defn- no-fn-spec
  [v spec]
  (ex-info (str "Fn at " v " is not spec'ed.")
           {:var v :spec spec ::s/failure :no-fn-spec}))

(def ^:private instrumented-vars
     "Map for instrumented vars to :raw/:wrapped fns"
     (atom {}))

(defn- instrument-choose-fn
  "Helper for instrument."
  [f spec sym {over :gen :keys [stub replace]}]
  (if (some #{sym} stub)
    (-> spec (s/gen over) gen/generate)
    (get replace sym f)))

(defn- instrument-choose-spec
  "Helper for instrument"
  [spec sym {overrides :spec}]
  (get overrides sym spec))

(defn- instrument-1
  [s opts]
  (when-let [v (resolve s)]
    (let [spec (s/get-spec v)
          {:keys [raw wrapped]} (get @instrumented-vars v)
          current @v
          to-wrap (if (= wrapped current) raw current)
          ospec (or (instrument-choose-spec spec s opts)
                      (throw (no-fn-spec v spec)))
          ofn (instrument-choose-fn to-wrap ospec s opts)
          checked (spec-checking-fn v ofn ospec)]
      (alter-var-root v (constantly checked))
      (swap! instrumented-vars assoc v {:raw to-wrap :wrapped checked}))
    (->sym v)))

(defn- unstrument-1
  [s]
  (when-let [v (resolve s)]
    (when-let [{:keys [raw wrapped]} (get @instrumented-vars v)]
      (let [current @v]
        (when (= wrapped current)
          (alter-var-root v (constantly raw))))
      (swap! instrumented-vars dissoc v))
    (->sym v)))

(defn- opt-syms
  "Returns set of symbols referenced by 'instrument' opts map"
  [opts]
  (reduce into #{} [(:stub opts) (keys (:replace opts)) (keys (:spec opts))]))

(defn- fn-spec-name?
  [s]
  (symbol? s))

(defn instrumentable-syms
  "Given an opts map as per instrument, returns the set of syms
that can be instrumented."
  ([] (instrumentable-syms nil))
  ([opts]
     (assert (every? ident? (keys (:gen opts))) "instrument :gen expects ident keys")
     (reduce into #{} [(filter fn-spec-name? (keys (s/registry)))
                       (keys (:spec opts))
                       (:stub opts)
                       (keys (:replace opts))])))

(defn instrument
  "Instruments the vars named by sym-or-syms, a symbol or collection
of symbols, or all instrumentable vars if sym-or-syms is not
specified.

If a var has an :args fn-spec, sets the var's root binding to a
fn that checks arg conformance (throwing an exception on failure)
before delegating to the original fn.

The opts map can be used to override registered specs, and/or to
replace fn implementations entirely. Opts for symbols not included
in sym-or-syms are ignored. This facilitates sharing a common
options map across many different calls to instrument.

The opts map may have the following keys:

  :spec     a map from var-name symbols to override specs
  :stub     a set of var-name symbols to be replaced by stubs
  :gen      a map from spec names to generator overrides
  :replace  a map from var-name symbols to replacement fns

:spec overrides registered fn-specs with specs your provide. Use
:spec overrides to provide specs for libraries that do not have
them, or to constrain your own use of a fn to a subset of its
spec'ed contract.

:stub replaces a fn with a stub that checks :args, then uses the
:ret spec to generate a return value.

:gen overrides are used only for :stub generation.

:replace replaces a fn with a fn that checks args conformance, then
invokes the fn you provide, enabling arbitrary stubbing and mocking.

:spec can be used in combination with :stub or :replace.

Returns a collection of syms naming the vars instrumented."
  ([] (instrument (instrumentable-syms)))
  ([sym-or-syms] (instrument sym-or-syms nil))
  ([sym-or-syms opts]
     (locking instrumented-vars
       (into
        []
        (comp (filter (instrumentable-syms opts))
              (distinct)
              (map #(instrument-1 % opts))
              (remove nil?))
        (collectionize sym-or-syms)))))

(defn unstrument
  "Undoes instrument on the vars named by sym-or-syms, specified
as in instrument. With no args, unstruments all instrumented vars.
Returns a collection of syms naming the vars unstrumented."
  ([] (unstrument (map ->sym (keys @instrumented-vars))))
  ([sym-or-syms]
     (locking instrumented-vars
       (into
        []
        (comp (filter symbol?)
              (map unstrument-1)
              (remove nil?))
        (collectionize sym-or-syms)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; testing  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- explain-test
  [args spec v role]
  (ex-info
   "Specification-based test failed"
   (when-not (s/valid? spec v nil)
     (assoc (s/explain-data* spec [role] [] [] v)
       ::args args
       ::val v
       ::s/failure :test-failed))))

(defn- check-call
  "Returns true if call passes specs, otherwise *returns* an exception
with explain-data under ::check-call."
  [f specs args]
  (let [cargs (when (:args specs) (s/conform (:args specs) args))]
    (if (= cargs ::s/invalid)
      (explain-test args (:args specs) args :args)
      (let [ret (apply f args)
            cret (when (:ret specs) (s/conform (:ret specs) ret))]
        (if (= cret ::s/invalid)
          (explain-test args (:ret specs) ret :ret)
          (if (and (:args specs) (:ret specs) (:fn specs))
            (if (s/valid? (:fn specs) {:args cargs :ret cret})
              true
              (explain-test args (:fn specs) {:args cargs :ret cret} :fn))
            true))))))

(defn- check-fn
  [f specs {gen :gen opts ::stc/opts}]
  (let [{:keys [num-tests] :or {num-tests 1000}} opts
        g (try (s/gen (:args specs) gen) (catch Throwable t t))]
    (if (throwable? g)
      {:result g}
      (let [prop (gen/for-all* [g] #(check-call f specs %))]
        (apply gen/quick-check num-tests prop (mapcat identity opts))))))

(defn- failure-type
  [x]
  (::s/failure (ex-data x)))

(defn- make-test-result
  "Builds spec result map."
  [test-sym spec test-check-ret]
  (merge {:spec spec
          ::stc/ret test-check-ret}
         (when test-sym
           {:sym test-sym})
         (when-let [result (-> test-check-ret :result)]
           {:result result})
         (when-let [shrunk (-> test-check-ret :shrunk)]
           {:result (:result shrunk)})))

(defn- test-1
  [{:keys [s f v spec]} {:keys [result-callback] :as opts}]
  (when v (unstrument s))
  (try
   (let [f (or f (when v @v))]
     (cond
      (nil? f)
      {::s/failure :no-fn :sym s :spec spec}
    
      (:args spec)
      (let [tcret (check-fn f spec opts)]
        (make-test-result s spec tcret))
    
      :default
      {::s/failure :no-args-spec :sym s :spec spec}))
   (finally
    (when v (instrument s)))))

(defn- sym->test-map
  [s]
  (let [v (resolve s)]
    {:s s
     :v v
     :spec (when v (s/get-spec v))}))

(defn- validate-test-opts
  [opts]
  (assert (every? ident? (keys (:gen opts))) "test :gen expects ident keys"))

(defn test-fn
  "Runs generative tests for fn f using spec and opts. See
'test' for options and return."
  ([f spec] (test-fn f spec nil))
  ([f spec opts]
     (validate-test-opts opts)
     (test-1 {:f f :spec spec} opts)))

(defn testable-syms
  "Given an opts map as per test, returns the set of syms that
can be tested."
  ([] (testable-syms nil))
  ([opts]
     (validate-test-opts opts)
     (reduce into #{} [(filter fn-spec-name? (keys (s/registry)))
                       (keys (:spec opts))])))

(defn test
  "Run generative tests for spec conformance on vars named by
sym-or-syms, a symbol or collection of symbols. If sym-or-syms
is not specified, test all testable vars.

The opts map includes the following optional keys, where stc
aliases clojure.spec.test.check: 

::stc/opts  opts to flow through test.check/quick-check
:gen        map from spec names to generator overrides

The ::stc/opts include :num-tests in addition to the keys
documented by test.check. Generator overrides are passed to
spec/gen when generating function args.

Returns a lazy sequence of test result maps with the following
keys

:spec       the spec tested
:type       the type of the test result
:sym        optional symbol naming the var tested
:result     optional test result
::stc/ret   optional value returned by test.check/quick-check

Values for the :result key can be one of

true        passing test
exception   code under test threw
map         with explain-data under :clojure.spec/problems

Values for the :type key can be one of

:pass       test passed
:fail       test failed
:error      test threw
:no-argspec no :args in fn-spec
:no-gen     unable to generate :args
:no-fn      unable to resolve fn to test
"
  ([] (test (testable-syms)))
  ([sym-or-syms] (test sym-or-syms nil))
  ([sym-or-syms opts]
     (->> (collectionize sym-or-syms)
          (filter (testable-syms opts))
          (pmap
           #(test-1 (sym->test-map %) opts)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; test reporting  ;;;;;;;;;;;;;;;;;;;;;;;;

(defn- unwrap-failure
  [x]
  (if (failure-type x)
    (ex-data x)
    x))

(defn- result-type
  [ret]
  (let [result (:result ret)]
    (cond
     (true? result) :test-passed
     (failure-type result) (failure-type result)
     :default :test-threw)))

(defn abbrev-result
  "Given a test result, returns an abbreviated version
suitable for summary use."
  [x]
  (if (true? (:result x))
    (dissoc x :spec ::stc/ret :result)
    (-> (dissoc x ::stc/ret)
        (update :spec s/describe)
        (update :result unwrap-failure))))

(defn summarize-results
  "Given a collection of test-results, e.g. from 'test', pretty
prints the summary-result (default abbrev-result) of each.

Returns a map with :total, the total number of results, plus a
key with a count for each different :type of result."
  ([test-results] (summarize-results test-results abbrev-result))
  ([test-results summary-result]
     (reduce
      (fn [summary result]
        (pp/pprint (summary-result result))
        (-> summary
            (update :total inc)
            (update (result-type result) (fnil inc 0))))
      {:total 0}
       test-results)))



