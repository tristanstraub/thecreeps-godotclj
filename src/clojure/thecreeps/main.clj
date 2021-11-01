(ns thecreeps.main
  (:require [godotclj.api :as api :refer [->object]]
            [godotclj.bindings.godot :as godot]
            [godotclj.callbacks :as callbacks :refer [defer listen]]
            [clojure.core.async :as async]
            [godotclj.proto :as proto]
            [tech.v3.datatype.ffi :as dtype-ffi]
            [tech.v3.datatype.struct :as dtype-struct])
  (:import [tech.v3.datatype.ffi Pointer]))

(defonce state
  (atom {:player      {:position [100 100]
                       :dir      [0 0]}
         :score       0
         :callback-id 0}))

(defn vec-normalize
  [[a b]]
  (let [m (Math/sqrt (+ (* a a) (* b b)))]
    [(/ a m) (/ b m)]))

(defn get-root
  []
  (.getRoot (.getMainLoop (->object "_Engine"))))

(defn player-start
  [p_instance p_method_data p_user_data n-args args]
  (let [player (->object p_instance)
        size   (:screen-size @state)
        shape  (.getNode player "CollisionShape2D")
        pos    [(/ (size 0) 2) (/ (size 1) 2)]]
    (swap! state assoc-in [:player :position] pos)
    (.setPosition player (api/vec2 pos))

    (.show player)
    (.setDisabled shape false)))

(defn player-ready
  [p_instance p_method_data p_user_data n-args args]
  (let [ob   (->object p_instance)
        rect (godot/rect2->size (.getViewportRect ob))]
    (.hide ob)

    (let [size [(godot/vector2-x rect)
                (godot/vector2-y rect)]]
      (swap! state assoc :screen-size size))))

(defn hud-show-message
  [hud text]
  (let [message       (.getNode hud "Message")
        message-timer (.getNode hud "MessageTimer")]
    (doto message
      (.setText text)
      (.show))

    (.start message-timer 0)
    (async/go
      (async/<! (listen message-timer "timeout"))
      (async/<! (defer #(.hide message))))))

(defn hud-show-game-over
  [hud]
  (async/go
    (async/<! (defer #(hud-show-message hud "Game Over!")))
    (defer #(hud-show-message hud "Dodge the Creeps!"))
    (let [one-shot-timer (.createTimer (.getTree hud) 1)]
      (async/<! (listen one-shot-timer "timeout"))
      (defer #(.show (.getNode hud "StartButton"))))))

(defn main-game-over
  [p_instance p_method_data p_user_data n-args args]
  (let [main (->object p_instance)]
    (.stop (.getNode main "Music"))
    (.play (.getNode main "DeathSound"))

    (.callGroup (.getTree main) "mobs" "queue_free")

    (doto main
      (-> (.getNode "ScoreTimer") .stop)
      (-> (.getNode "MobTimer") .stop))

    (hud-show-game-over (.getNode main "HUD"))))

(defn hud-update-score
  [hud score]
  (.setText (.getNode hud "ScoreLabel") (str score)))

(defn main-new-game
  [p_instance p_method_data p_user_data n-args args]
  (swap! state assoc :score 0)
  (let [main (->object p_instance)]
    (.play (.getNode main "Music"))

    (doto (.getNode main "HUD")
      (hud-update-score (:score @state))
      (hud-show-message "Get Ready"))

    (.callv (.getNode main "Player") "start" [(.getPosition (.getNode main "StartPosition"))])
    (.start (.getNode main "StartTimer"))))

(defn player-body-entered
  [p_instance p_method_data p_user_data n-args args]
  (let [ob    (->object p_instance)
        shape (.getNode ob "CollisionShape2D")]
    (.emitSignal ob "hit")
    (.setDeferred shape "disabled" true)
    (.hide ob)))

(defn main-ready
  [p_instance p_method_data p_user_data n-args args]
  ;; randomize mentioned in tutorial
  nil)

(defn clamp
  [[a b] & {:keys [to]}]
  (let [[right bottom] to]
    [(Math/min (Math/max (float 0) (float a)) (float right))
     (Math/min (Math/max (float 0) (float b)) (float bottom))]))

(defn player-process
  [p_instance p_method_data p_user_data n-args p_args]
  (let [speed           400
        delta           (proto/->clj (first (godot/variants n-args p_args)))
        input           (->object "Input")
        player          (->object p_instance)
        animated-sprite (.getNode player "AnimatedSprite")
        dir             (cond (.isActionPressed input "ui_right") [1 0]
                              (.isActionPressed input "ui_left")  [-1 0]
                              (.isActionPressed input "ui_up")    [0 -1]
                              (.isActionPressed input "ui_down")  [0 1])]
    (if dir
      (let [old-dir (or (:dir @state) [0 0])]
        (swap! state
               (fn [{:keys [screen-size] :as state}]
                 (-> state
                     (update-in [:player :position]
                                (fn [pos]
                                  (if screen-size
                                    (clamp (->> (vec-normalize dir)
                                                (mapv * (vec (repeat 2 (* delta speed))))
                                                (mapv + pos))
                                           :to screen-size)
                                    pos)))
                     (assoc :dir dir))))
        (.setPosition player (api/vec2 (get-in @state [:player :position])))
        (doto animated-sprite
          (.setFlipH (not (pos? (dir 0))))
          (.setFlipV (pos? (dir 1)))
          (.setAnimation "walk")
          (.play "walk" false)))
      (.stop animated-sprite)))

  nil)

(defn mob-ready
  [p_instance p_method_data p_user_data n-args args]
  (let [ob              (->object p_instance)
        animated-sprite (.getNode ob "AnimatedSprite")
        mob-types       (.. animated-sprite getSpriteFrames getAnimationNames)]
    (.setAnimation animated-sprite (rand-nth mob-types))))

(defn mob-screen-exited
  [p_instance p_method_data p_user_data n-args args]
  (.queueFree (->object p_instance)))

(defn main-start-timer-timeout
  [p_instance p_method_data p_user_data n-args args]
  (doto (->object p_instance)
    (-> (.getNode "ScoreTimer") .start)
    (-> (.getNode "MobTimer") .start)))

(defn main-score-timer-timeout
  [p_instance p_method_data p_user_data n-args args]
  (swap! state update :score inc)
  (hud-update-score (.getNode (->object p_instance) "HUD")
                    (:score @state)))

(defn rand-range
  [a b]
  (let [d (- b a)]
    (+ a (* (rand) d))))

(defn main-mob-timer-timeout
  [p_instance p_method_data p_user_data n-args args]
  (let [main (->object p_instance)
        node (.getNode main "MobPath/MobSpawnLocation")]
    (.setOffset node (rand-int Integer/MAX_VALUE))
    (let [mob-instance (.instance (:mob @state))]
      (.addChild main mob-instance)

      (let [direction (+ (.getRotation node)
                         (/ Math/PI 2.0)
                         (rand-range (- (/ Math/PI 4.0))
                                     (/ Math/PI 4.0)))]
        (doto mob-instance
          (.setPosition (.getPosition node))
          (.setRotation direction)

          (.setLinearVelocity (api/vec2 [(rand-range 150 ;; TODO minspeed
                                                     250)
                                         0])))

        (let [result (godot/new-struct :godot-vector2)]
          (godot/godot_vector2_rotated_wrapper (dtype-ffi/->pointer (.getLinearVelocity mob-instance))
                                               direction
                                               (dtype-ffi/->pointer result))
          (.setLinearVelocity mob-instance (->object "Vector2" (dtype-ffi/->pointer result))))))))

(defn main-set-mob
  [p_instance p_method_data p_user_data value]
  (swap! state assoc :mob (->object (proto/pvariant->object value))))

(defn hud-start-button-pressed
  [p_instance p_method_data p_user_data n-args args]
  (doto (->object p_instance)
    (.. (getNode "StartButton") (hide))
    (.emitSignal "start_game")))

(defn register-methods
  [p-handle]
  (godot/register-class p-handle
                        "Main" "Control"
                        (fn [& args] (println :create))
                        (fn [& args] (println :destroy)))
  (godot/register-property p-handle
                           "Main" "Mob"
                           #'main-set-mob
                           (fn [& args]
                             (println :get-mob args))
                           :type :packed-scene :value nil)

  (godot/register-method p-handle "Main" "new_game" #'main-new-game)
  (godot/register-method p-handle "Main" "_ready" #'main-ready)
  (godot/register-method p-handle "Main" "game_over" #'main-game-over)

  (godot/register-method p-handle "Main" "_on_StartTimer_timeout" #'main-start-timer-timeout)
  (godot/register-method p-handle "Main" "_on_ScoreTimer_timeout" #'main-score-timer-timeout)
  (godot/register-method p-handle "Main" "_on_MobTimer_timeout" #'main-mob-timer-timeout)

  (godot/register-class p-handle
                        "HUD" "CanvasLayer"
                        (fn [& args] (println :create))
                        (fn [& args] (println :destroy)))

  (godot/register-signal p-handle "HUD" "start_game")
  (godot/register-method p-handle "HUD" "_on_StartButton_pressed" #'hud-start-button-pressed)

  (godot/register-class p-handle
                        "Player" "Area2D"
                        (fn [& args] (println :create))
                        (fn [& args] (println :destroy)))

  (godot/register-method p-handle "Player" "_process" #'player-process)
  (godot/register-method p-handle "Player" "_ready" #'player-ready)
  (godot/register-method p-handle "Player" "_on_Player_body_entered" #'player-body-entered)
  (godot/register-method p-handle "Player" "start" #'player-start)

  (godot/register-signal p-handle "Player" "hit")

  (godot/register-class p-handle
                        "Mob" "RigidBody2D"
                        (fn [& args] (println :create))
                        (fn [& args] (println :destroy)))

  (godot/register-property p-handle "Mob" "min_speed" nil nil :value 150.0)
  (godot/register-property p-handle "Mob" "max_speed" nil nil :value 250.0)

  (godot/register-method p-handle "Mob" "_ready" #'mob-ready)
  (godot/register-method p-handle "Mob" "_on_VisibilityNotifier2D_screen_exited" #'mob-screen-exited)

  (callbacks/register-callbacks p-handle "Main" "HUD" "Mob" "Player"))

(defn reload-scene
  []
  (.callDeferred (.getTree (get-root)) "reload_current_scene"))
