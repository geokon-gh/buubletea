;; **JavaFX** is used through the [**fn-fx**](https://github.com/halgari/fn-fx) library. When the application is run through `lein run` it should initialize the **JavaFX** GUI and **fn-fx** itself.
;;
;; [**thi.ng/geom-viz**](https://github.com/thi-ng/geom/blob/master/geom-viz/src/index.org) is for making plots and other pretty SVG things
;;
;; **OpenCV** is used through [**origami**](https://github.com/hellonico/origami) library which comes with precompiled libs/jars for common systems (linux/windows/mac) so we inject those into our project with `:injections`. Very fresh versions are hosted in a separate repository and since I needed a [[https://github.com/hellonico/origami/issues/5][few fixes]] for the webcam to work on my Debian machine the respository needs to be added for the time being with ~:repositories~ (everything else is still directly from /Clojars/).
;;
;; [**FranzXaver**](afester.github.io/FranzXaver/) is for converting the output SVGs to something that can be put into a Group in JavaFX. 
(ns buubletea.core
  (:require [fn-fx.fx-dom :as dom] ;; The JavaFX libraries
            [fn-fx.diff :refer [component defui render should-update?]]
            [fn-fx.controls :as ui]
            [thi.ng.geom.core :as g] ;; The graphing libraires
            [thi.ng.geom.viz.core :as viz]
            [thi.ng.geom.svg.core :as svgthing]
            [thi.ng.math.core :as m :refer [PI TWO_PI]]
            [opencv3.core :as oc] ;; The OpenCV libraries
            [opencv3.video :as ov]
            [opencv3.utils :as ou])
  (:import  [afester.javafx.svg SvgLoader]))

;; # Globals

(def main-font (ui/font :family "Helvetica" :size 20))
(def default-webcam-options ;; this is used in (-main) and as a default when using 'webcam-image' with no arguements
  {:frame
   {:color "00" :title "video"}
   :video
   {:device 0
    :width 320
    :height 240
    :datatype oc/CV_8UC3 }})


;; # Event Handler

(defmulti handle-event
  "This is the event handler multimethod through which all events go through. It will 'switch' on the :event key"
  (fn [state event]
    (:event event)))


;; # Get an image from the webcam

(defn webcam-image
  "Get a picture from the webcam through OpenCV and return an OpenCV Mat (color is in RGB order)"
  ([]
   (webcam-image default-webcam-options))
  ([webcam-options]
   (let [webcam-capture (ov/new-videocapture)
         webcam-mat (oc/new-mat)]

     (doto webcam-capture
       (.open (int (-> webcam-options :video :device)))
       (.set ov/CAP_PROP_FRAME_WIDTH (-> webcam-options :video :width))
       (.set ov/CAP_PROP_FRAME_HEIGHT (-> webcam-options :video :height)))

     (ou/.read webcam-capture webcam-mat)
     (oc/cvt-color! webcam-mat oc/COLOR_BGR2RGB)
     (.release webcam-capture)
     webcam-mat)))

(defn get-mat-byte-buffer
  "Go into an OpenCV Mat and return the internal byte-array buffer.

  We do it by making an empty byte buffer and copying over the Mat data"
  ([mat]
   (get-mat-byte-buffer mat (.width mat) (.height mat) (.channels mat)))
  ([mat width height channels]
   (get-mat-byte-buffer mat (* width height) channels))
  ([mat number-of-pixels channels]
   (get-mat-byte-buffer mat (* number-of-pixels channels)))
  ([mat size]
   (let [image-buffer (byte-array size)]
     (.get mat 0 0 image-buffer)
     image-buffer)))

(defn make-jfx-image-from-byte-buffer
  "Take in a byte array of RGB value (RGBRGBRGB..) and some dimensions and turn it into a JFXImage.

  JavaFX has a limited number of options other than RGB, so it's hardcoded for now to 3-chan RGB.
  see: javafx.scene.image.PixelFormat"
  [image-buffer width height]
  (let [writable-image (new javafx.scene.image.WritableImage width height)
        ^javafx.scene.image.PixelWriter pixel-writer (.getPixelWriter writable-image)]
    (.setPixels pixel-writer 0 0 width height
                (javafx.scene.image.PixelFormat/getByteRgbInstance) 
                image-buffer
                0
                (* 3 width))
    writable-image))

;; this is a simple wrapper around the last function.. but sometimes u want to reuse the buffer
;; so it's good to hve both

(defn make-jfx-image-from-mat
  "Take an OpenCV Mat and turn it into a JFXImage"
  ([mat]
   (make-jfx-image-from-byte-buffer 
    (get-mat-byte-buffer mat)
    (.width mat)
    (.height mat)
    )))

;; # Build Histogram from Image

(defn unsigned-byte-to-int
  "Takes a byte which will show as signed number over the range -126:126 and turns it into an int on the 0:255 range"
  [ubyte]
  (cond 
    (neg? ubyte) (int (+ 256 ubyte)) ;; negative numbers have the sign bit flipped and are inverted
    :else (int ubyte)))


(defn build-histogram-vector
  "Takes a SEQUENCE of repeating continuous values and bins them it into a vector based on their values.
  Can be given a MAX-INTEGER value to initialize the continuous bin vector
  Otherwise looks for the max-value before bining"
  ([input-sequence]
   (build-histogram-vector input-sequence (apply max input-sequence)))
  ([input-sequence max-integer]
   (reduce
    (fn [old-histogram-vector new-value]
      (let [new-int (unsigned-byte-to-int new-value)]
        (assoc
         old-histogram-vector
         new-int
         (inc (get old-histogram-vector new-int)))))
    (vec (repeat max-integer 0))
    input-sequence)))

(defn index-vector
  "Take a vector of numbers [a b c d ..] and makes an indexed-pair version
  [[0 a] [1 b] [2 c] [3 d] ..]"
  ([vector]
   (index-vector vector (count vector)))
  ([vector length]
   (map (fn [i] [i (get vector i)]) (range 0 (dec length)))))

(defn string->stream
  "Takes a string and turns it into an input stream"
  ([s] (string->stream s "UTF-8"))
  ([s encoding]
   (-> s
       (.getBytes encoding)
       (java.io.ByteArrayInputStream.))))

(defn svg-to-javafx-group
  "Use the FranzXaver library to turn a string of XML describing an SVG 
  into a JavaFX compatible Group Node (which shows up as a picture)
  This is using Batik under the hood somehow"
  [svg-xml-string]
  (.loadSvg (SvgLoader.) (string->stream svg-xml-string)))


(defn histogram-spec 
  "Generate a histogram spec of the given size"
  [width height max-value min-value]
  {:x-axis (viz/linear-axis
            {:domain [0 255]
             :range  [0 width]
             :major  1
             :pos    height ;280
             :label  (viz/default-svg-label int)
             :visible false})
   :y-axis (viz/linear-axis
            {:domain      [0 max-value]
             :range       [height 0]
             :major       10
             :minor       5
             :pos         0
             :visible false }) ;; based off the example in geom-viz
   
   :grid   {:minor-y true}})

(defn bar-spec
  "Generate a spec for the data in the histrogram"
  [num width histogram-values]
  (fn [idx col]
    {:values     histogram-values
     :attribs    {:stroke       col
                  :stroke-width (str (dec width) "px")}
     :layout     viz/svg-bar-plot
     :interleave num
     :bar-width  width
     :offset     idx}))


(defn image-to-histogram
  "Given an image's byte buffer and it's length, returns a histogram of a given same size.
  The histogram is generated using thi.ng/geom-viz and then transfromed into a JavaFX Group"
  [image-byte-vector length output-width output-height]
  (let [ histogram-vector (build-histogram-vector image-byte-vector length)
        max-value (apply max histogram-vector)
        min-value (apply min histogram-vector)
        indexed-histrogram-vector (index-vector histogram-vector length)]
    (svg-to-javafx-group  (-> (histogram-spec output-width output-height max-value min-value)
                              (assoc :data [((bar-spec 1 2 indexed-histrogram-vector) 0 "#000")])
                              (viz/svg-plot2d-cartesian)
                              (#(svgthing/svg {:width output-width :height output-height} %))
                              (svgthing/serialize)))))

;; ## Image Entry
;; This holds one image and its accompanying analysis

(defui ImageEntry
  (render [this {:keys [done? idx text image histogram image-edges]}]
          (ui/border-pane
           :padding (ui/insets
                     :top 10
                     :bottom 10
                     :left 0
                     :right 0)
           :left (ui/check-box
                  :font main-font
                  :text text
                  :selected done?
                  :on-action {:event :swap-status :idx idx})

           :right (ui/button :text "X"
                             :on-action {:event :delete-item :idx idx})
           :bottom (ui/h-box
                    :children [(ui/label :graphic (ui/image-view :image image))
                               histogram
                               (ui/label :graphic (ui/image-view :image image-edges))
                               #_(ui/button :text "X"
                                            :on-action {:event :delete-item :idx idx})]))))
;; ## MainWindow
;; the root node of the scene-graph. It will track the scene size/resizing

(defui MainWindow
  (render [this {:keys [todos]}]
          (ui/v-box
           :style
           "-fx-base: rgb(255, 255, 255);
-fx-focus-color: transparent;"
           :padding (ui/insets
                     :top-right-bottom-left 25)
           :children [(ui/text-field
                       :id ::new-item
                       :prompt-text "What needs to be done?"
                       :font main-font
                       :on-action {:event :add-item
                                   :fn-fx/include {::new-item #{:text}}})
                      (ui/scroll-pane
                       :vbar-policy javafx.scene.control.ScrollPane$ScrollBarPolicy/ALWAYS
                       :fit-to-height false
                       :fit-to-width false
                       :content
                       (ui/v-box
                        :children
                        (map-indexed
                         (fn [idx todo]
                           (image-entry (assoc todo :idx idx)))
                         todos)))])))

;; ## Stage
;; the JavaFX top level container that stands for a window
;; The stage has a scene container for all content ie. a scene-graph of nodes.
;; Each Stage/Window displays *one* scene at a time

(defui Stage 
  (render [this args]
          (ui/stage
           :title "ToDos"
           :min-height 600
           :shown true
           :scene (ui/scene 
                   :root (main-window args)))))

(defmethod handle-event :swap-status
  [state {:keys [idx]}]
  (update-in state [:todos idx :done?] (fn [x]
                                         (not x))))

(defmethod handle-event :delete-item
  [state {:keys [idx]}]
  (update-in state [:todos] (fn [itms]
                              (println itms idx)
                              (vec (concat (take idx itms)
                                           (drop (inc idx) itms))))))

(defmethod handle-event :add-item
  [state {:keys [fn-fx/includes webcam-params]}]
  (println (:current-webcam-params state))
  (let [webcam-params (:current-webcam-params state)
        width (-> webcam-params :video :width)
        height (-> webcam-params :video :height)
        snapshot (webcam-image webcam-params)
        snapshot-buffer (get-mat-byte-buffer snapshot)
        snapshot-edges (-> snapshot
                           (oc/cvt-color! oc/COLOR_RGB2GRAY)
                           (oc/canny! 300.0 100.0 3 true)
                           (oc/bitwise-not!)
                           (oc/cvt-color! oc/COLOR_GRAY2RGB))]
    (update-in state [:todos] conj {:done? false
                                    :text (get-in includes [::new-item :text])
                                    :webcam-params webcam-params
                                    :image (make-jfx-image-from-byte-buffer snapshot-buffer width height)
                                    :histogram (image-to-histogram snapshot-buffer 256 width height)
                                    :image-edges (make-jfx-image-from-mat snapshot-edges)})))

(defmethod handle-event :toggle-text
  [state event]
  (assoc state :comment-text "hello"))

(defmethod handle-event :default
  [state event]
  (println "No hander for event " (:type event) event)
  state)

;; # Launching fn-fx
;; - create an initial state
;; - add an intial picture to the state
;; - intialize the event handlers so that they update the state.
;;   The eventhandler multimethod itself will generate new states
;; - add a watch on the state. When the state changes, update/redraw the UI

(defn -main 
  "This is where we initialize the whole fn-fx monster"
  []
  (let [starting-webcam-params default-webcam-options
        width (-> starting-webcam-params :video :width)
        height (-> starting-webcam-params :video :height)
        first-image (webcam-image starting-webcam-params)
        first-image-buffer (get-mat-byte-buffer first-image)
        first-image-edges (-> first-image ; THIS CHANGES THE MAT INPLACE!
                              (oc/cvt-color! oc/COLOR_RGB2GRAY)
                              (oc/canny! 300.0 100.0 3 true)
                              (oc/bitwise-not!)
                              (oc/cvt-color! oc/COLOR_GRAY2RGB))
        
        data-state (atom {:current-webcam-params starting-webcam-params
                          :todos [{:done? false
                                   :text  "George's confused face when the app launches..."
                                   :webcam-params starting-webcam-params
                                   :image (make-jfx-image-from-byte-buffer first-image-buffer width height)
                                   :histogram (image-to-histogram first-image-buffer 256 width height)
                                   :image-edges (make-jfx-image-from-mat first-image-edges)}]})

        handler-fn (fn [event]
                     (try
                       (swap! data-state handle-event event)
                       (catch Throwable ex
                         (println ex))))

        ui-state   (agent (dom/app (stage @data-state) handler-fn))]

    (add-watch data-state :ui (fn [_ _ _ _]
                                (send ui-state
                                      (fn [old-ui]
                                        (try
                                          (dom/update-app old-ui (stage @data-state))
                                          (catch Throwable ex
                                            (println ex)))))))))

(comment
  (-main)

  )
