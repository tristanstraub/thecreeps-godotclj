(ns thecreeps.main
  (:require [godotclj.api :as api :refer [mapped-instance]]
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
  (let [{:keys [get-main-loop]}              (mapped-instance "_Engine")
        {:keys [get-current-scene get-root]} (get-main-loop)]
    (get-root)))

(defn player-start
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [show set-position get-node]} (mapped-instance "Object" (Pointer. p_instance))
        {:keys [set-disabled]}               (get-node "CollisionShape2D")]
    (let [size (:screen-size @state)
          pos  [(/ (size 0) 2) (/ (size 1) 2)]]
      (swap! state assoc-in [:player :position] pos)
      (set-position (api/vec2 pos)))

    (show)
    (set-disabled false)))

(defn player-ready
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [get-viewport-rect
                hide
                connect
                set-position
                hide] :as ob} (mapped-instance "Object" (Pointer. p_instance))
        rect                  (godot/rect2->size (:godot/object (get-viewport-rect)))]

    (hide)

    (let [size [(godot/vector2-x rect)
                (godot/vector2-y rect)]]
      (swap! state assoc :screen-size size)))

  nil)

(defn hud-show-message
  [{:keys [get-node] :as hud} text]
  (let [{:keys [set-text show hide]}      (get-node "Message")
        {:keys [start] :as message-timer} (get-node "MessageTimer")]
    (set-text text)
    (show)
    (start)
    (async/go
      (async/<! (listen message-timer "timeout"))
      (async/<! (defer hide)))))

(defn hud-show-game-over
  [{:keys [get-node get-tree] :as hud}]
  (async/go
    (async/<! (defer #(hud-show-message hud "Game Over!!")))
    (defer #(hud-show-message hud "Dodge the Creeps!"))
    (let [{:keys [create-timer]} (get-tree)
          one-shot-timer         (create-timer 1)
          {:keys [show]}         (get-node "StartButton")]
      (async/<! (listen one-shot-timer "timeout"))
      (defer show))))

(defn main-game-over
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [get-node get-tree] :as main} (mapped-instance "Object" (Pointer. p_instance))
        {:keys [stop]}                       (get-node "Music")
        {:keys [play]}                       (get-node "DeathSound")
        {stop-score-timer :stop}             (get-node "ScoreTimer")
        {stop-mob-timer :stop}               (get-node "MobTimer")
        hud                                  (get-node "HUD")
        {:keys [call-group]}                 (get-tree)]

    (stop)
    (play)

    (call-group "mobs" "queue_free")

    (stop-score-timer)
    (stop-mob-timer)

    (hud-show-game-over hud)))

(defn hud-update-score
  [{:keys [get-node] :as hud} score]
  (let [{:keys [set-text]} (get-node "ScoreLabel")]
    (set-text (str score))))

(defn main-new-game
  [p_instance p_method_data p_user_data n-args args]
  (swap! state assoc :score 0)
  (let [{:keys [get-node] :as main} (mapped-instance "Object" (Pointer. p_instance))
        {:keys [play]}              (get-node "Music")
        {:keys [get-position]}      (get-node "StartPosition")
        {:keys [callv]}             (get-node "Player")
        {start-timer :start}        (get-node "StartTimer")
        hud                         (get-node "HUD")]

    (play)

    (hud-update-score hud (:score @state))
    (hud-show-message hud "Get Ready")

    (callv "start" [(get-position)])
    (start-timer)))

(defn player-body-entered
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [hide get-node emit-signal]} (mapped-instance "Object" (Pointer. p_instance))
        {:keys [set-deferred]}       (get-node "CollisionShape2D")]
    (emit-signal "hit")
    (set-deferred "disabled" true)
    (hide))

  nil)

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
  (let [speed                       400
        delta                       (proto/->clj (first (godot/variants n-args p_args)))
        {:keys [is-action-pressed]} (mapped-instance "Input")
        {:keys [set-position
                get-node
                hide
                show]}              (mapped-instance "Object" (Pointer. p_instance))
        {:keys [set-flip-v
                set-flip-h
                set-animation
                play
                stop]}              (get-node "AnimatedSprite")
        dir                         (cond (is-action-pressed "ui_right") [1 0]
                                          (is-action-pressed "ui_left")  [-1 0]
                                          (is-action-pressed "ui_up")    [0 -1]
                                          (is-action-pressed "ui_down")  [0 1])]
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
        (set-position (api/vec2 (get-in @state [:player :position])))
        (set-flip-h (not (pos? (dir 0))))
        (set-flip-v (pos? (dir 1)))
        (set-animation "walk")
        (play))
      (stop)))

  nil)

(defn mob-ready
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [get-node] :as ob}                                     (mapped-instance "Object" (Pointer. p_instance))
        {:keys [set-animation get-sprite-frames] :as animated-sprite} (get-node "AnimatedSprite")
        {:keys [get-animation-names]}                                 (get-sprite-frames)
        mob-types                                                     (map proto/->clj (api/pool-string-array->vec (:godot/object (get-animation-names))))]
    (set-animation (rand-nth mob-types)))

  nil)

(defn mob-screen-exited
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [queue-free] :as ob} (mapped-instance "Object" (Pointer. p_instance))]
    (queue-free)))

(defn main-start-timer-timeout
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [get-node]}         (mapped-instance "Object" (Pointer. p_instance))
        {start-score-timer :start} (get-node "ScoreTimer")
        {start-mob-timer :start}   (get-node "MobTimer")]
    (start-score-timer)
    (start-mob-timer))
  nil)

(defn main-score-timer-timeout
  [p_instance p_method_data p_user_data n-args args]
  (swap! state update :score inc)
  (let [{:keys [get-node]} (mapped-instance "Object" (Pointer. p_instance))
        hud                (get-node "HUD")]
    (hud-update-score hud (:score @state)))
  nil)

(defn rand-range
  [a b]
  (let [d (- b a)]
    (+ a (* (rand) d))))

(defn main-mob-timer-timeout
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [add-child get-node get-class get-name]}         (mapped-instance "Object" (Pointer. p_instance))
        {:keys [set-offset get-rotation get-position] :as node} (get-node "MobPath/MobSpawnLocation")]
    (set-offset (rand-int Integer/MAX_VALUE))
    (let [{:keys [instance]}                             (:mob @state)
          {:keys [set-position
                  set-rotation
                  set-linear-velocity
                  get-linear-velocity] :as mob-instance} (instance)]
      (add-child mob-instance)

      (let [direction (+ (proto/->clj (get-rotation))
                         (/ Math/PI 2.0)
                         (rand-range (- (/ Math/PI 4.0))
                                     (/ Math/PI 4.0)))]
        (set-position (get-position))
        (set-rotation direction)

        (set-linear-velocity (api/vec2 [(rand-range 150 ;; TODO minspeed
                                                    250)
                                        0]))

        (let [result (godot/new-struct :godot-vector-2)
              v      (mapped-instance "Vector2" (dtype-ffi/->pointer result))]
          (godot/godot_vector2_rotated_wrapper (dtype-ffi/->pointer (get-linear-velocity))
                                               direction
                                               (dtype-ffi/->pointer result))
          (set-linear-velocity v)))))

  nil)

(defn main-set-mob
  [p_instance p_method_data p_user_data value]
  (let [ob  (godot/variant->object (dtype-ffi/ptr->struct :godot-variant (Pointer. value)))
        mob (mapped-instance "Object" ob)]

    (swap! state assoc :mob mob)))

(defn get-node-by-path
  [path]
  (let [{:keys [get-node]} (get-root)]
    (get-node path)))

(defn hud-start-button-pressed
  [p_instance p_method_data p_user_data n-args args]
  (let [{:keys [emit-signal get-node] :as hud} (mapped-instance "Object" (Pointer. p_instance))
        {:keys [hide] :as start-button}        (get-node "StartButton")]
    (hide)
    (emit-signal "start_game")))

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
  (let [{:keys [get-tree]} (get-root)
        {:keys [call-deferred reload-current-scene]} (get-tree)]
    ;; nodes need to be added to scene on main thread
    (call-deferred "reload_current_scene")))
