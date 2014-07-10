(ns terp.core
  (:refer-clojure :exclude [eval macroexpand macroexpand-1])
  (:require [clojure.walk :as walk]))

(def default-env
  {'+ +, '- -, '* *, '/ /, '< <, '<= <=, '> >, '>= >=, '= =,
   'defn (with-meta (fn [name args body]
                      `(def ~name (fn* ~args ~body)))
                    {:macro true})})

;; macroexpansion

(defn macro? [f]
  (:macro (meta f)))

(defn macroexpand-1 [form env]
  (if (and (seq? form) (macro? (env (first form))))
    (apply (env (first form)) (rest form))
    form))

(defn macroexpand [form env]
  (let [expanded (macroexpand-1 form env)]
    (if (= expanded form)
      form
      (recur expanded env))))

(defn macroexpand-all [form env]
  (walk/prewalk #(macroexpand % env) form))

;; special forms + function application

(declare eval-exp)

(defmulti eval-seq (fn [exp _] (first exp)))

(defmethod eval-seq :default [exp env]
  (let [vs (map #(first (eval-exp % env)) exp)]
    [(apply (first vs) (rest vs)) env]))

(defmethod eval-seq nil [_ env]
  [() env])

(defmethod eval-seq 'def [[_ sym arg] env]
  (let [[v env'] (eval-exp arg env)]
    [v (assoc env' sym v)]))

(defmethod eval-seq 'if [[_ test then else] env]
  (let [[test-v env'] (eval-exp test env)]
    (eval-exp (if test-v then else) env')))

(defmethod eval-seq 'fn* [[_ arg-names body] env]
  (let [f (fn [& args]
            (let [benv (merge env (zipmap arg-names args))]
              (first (eval-exp body benv))))]
    [f env]))

(defmethod eval-seq 'let* [[_ bvec body] env]
  (loop [bpairs (partition 2 bvec) benv env]
    (if-let [[bsym bform] (first bpairs)]
      (let [[v benv'] (eval-exp bform benv)]
        (recur (rest bpairs) (assoc benv' bsym v)))
      (eval-exp body benv))))

(defmethod eval-seq 'quote [[_ arg] env]
  [arg env])

;; generic evaluation

(defn eval-exp [exp env]
  (let [eval-subexp #(first (eval-exp % env))]
    (condp apply [exp]
      map? [(->> (interleave (keys exp) (vals exp))
              (map eval-subexp)
              (apply hash-map)) env]
      seq? (eval-seq exp env)
      set? [(set (map eval-subexp exp)) env]
      symbol? [(env exp) env]
      vector? [(mapv eval-subexp exp) env]
      [exp env])))

(defn eval
  ([form]
    (eval form default-env))
  ([form env]
    (-> form (macroexpand-all env) (eval-exp env))))

(defn eval-all
  ([forms]
    (eval-all forms default-env))
  ([forms env]
    (loop [forms forms prev-v nil env env]
      (if-let [form (first forms)]
        (let [[v env'] (eval form env)]
          (recur (rest forms) v env'))
        [prev-v env]))))