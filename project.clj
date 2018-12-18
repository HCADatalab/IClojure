(defproject hcadatalab/iclojure "0.3.0-SNAPSHOT"
  :description "An IPython/JupyterLab kernel for Clojure."
  :url "http://github.com/HCADatalab/IClojure"
  :license {:name "EPL"}
  :dependencies [[beckon "0.1.1"]
                 [cheshire "5.7.0"]
                 [clj-time "0.11.0"]
                 [com.taoensso/timbre "4.8.0"]
                 [org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/data.codec "0.1.0"]
                 [org.zeromq/cljzmq "0.1.4" :exclusions [org.zeromq/jzmq]]
                 [org.zeromq/jeromq "0.3.4"] ; "0.3.5" (modern) fails on zmq/bind.
                 [net.cgrand/packed-printer "0.3.0"]
                 [net.cgrand/xforms "0.19.0"]
                 [pandect "0.5.4"]
                 [org.clojure/tools.deps.alpha "0.5.417"]]
  :aot [iclj.core]
  :main iclj.core
 #_#_ :jvm-opts ["-Xmx250m"]
  :keep-non-project-classes true)

