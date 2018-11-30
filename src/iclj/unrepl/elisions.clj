(ns iclj.unrepl.elisions)

(def ^:private shorthands (atom {:direct {} :reverse {}}))

(defn lookup [shorthand]
  (get-in @shorthands [:direct shorthand]))

(defn intern [elision]
  (let [{:keys [direct reverse] :as whole} @shorthands]
    (or (reverse elision)
      (let [shorthand (str (inc (count reverse)))]
        (if (compare-and-set! shorthands whole
              {:direct (assoc direct shorthand elision) :reverse (assoc reverse elision shorthand)})
          shorthand
          (recur elision))))))

(defn elision? [x]
  (and (tagged-literal? x)
    (= 'unrepl/... (:tag x))))

(def unreachable (tagged-literal 'unrepl/... nil))