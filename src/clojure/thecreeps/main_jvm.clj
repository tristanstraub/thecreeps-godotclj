(ns thecreeps.main-jvm
  (:require [nrepl.cmdline]
            [thecreeps.main]))

(defonce repl
  (delay
    (try
      (apply nrepl.cmdline/-main
             ["--middleware"
              "[\"refactor-nrepl.middleware/wrap-refactor\", \"cider.nrepl/cider-middleware\"]"])

      (catch Exception e
        (println e)))))

(defn register-methods
  [p-handle]
  (future @repl)
  (thecreeps.main/register-methods p-handle))
