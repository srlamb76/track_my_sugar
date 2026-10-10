(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/track-my-sugar.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Builds a standalone jar: java -jar target/track-my-sugar.jar"
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis @basis :ns-compile '[track-my-sugar.server] :class-dir class-dir})
  (b/uber {:class-dir class-dir :uber-file uber-file :basis @basis
           :main 'track-my-sugar.server})
  (println "Built" uber-file))
