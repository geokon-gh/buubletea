(defproject bubbletea "0.1.0-SNAPSHOT"
  :description
  "This is a basic example for working in **Clojure** with **JavaFX**, **OpenCV** and simple plotting"
  :url "https://geokon-gh.github.io/buubletea/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories [["vendredi" "https://repository.hellonico.info/repository/hellonico/"]]
  :injections [ (clojure.lang.RT/loadLibrary org.opencv.core.Core/NATIVE_LIBRARY_NAME)]
  :main ^:skip-aot buubletea.core
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [halgari/fn-fx "0.4.0"]
                 [seesaw "1.5.0"]
                 [com.github.afester.javafx/FranzXaver "0.1"]
                 [tikkba "0.6.0"]
                 [thi.ng/geom-viz "0.0.908"]
                 [opencv/opencv-native "3.3.1_7"]
                 [origami "0.1.10"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
