(ns thecreeps.main
  (:require [clojure.core.async :as async]
            [godotclj.api :as api :refer [->object]]
            [godotclj.bindings.godot :as godot]
            [godotclj.callbacks :as callbacks :refer [defer listen]]
            [godotclj.proto :as proto])
  (:import [godotclj.api.gdscript IGodotArea2D IGodotCanvasLayer IGodotCollisionShape2D IGodotNode IGodotObject IGodotTree IGodotRigidBody2D IGodot_Engine IGodotLabel IGodotTimer IGodotSceneTree IGodotControl IGodotAudioStreamPlayer IGodotPosition2D IGodotInput IGodotAnimatedSprite IGodotSpriteFrames IGodotPathFollow2D IGodotPackedScene IGodotButton]))

(comment "See godotclj.api.gdscript namespace for methods that are available on gdscript objects")

(defonce state
  (atom {:player      {:position [100 100]
                       :dir      [0 0]}
         :score       0
         :callback-id 0}))

(defn vec-normalize
  [[a b]]
  (let [m (Math/sqrt (+ (* a a) (* b b)))]
    [(/ a m) (/ b m)]))

(defn get-root ^IGodotNode
  []
  (.getRoot ^IGodotTree (.getMainLoop ^IGodot_Engine (->object "_Engine"))))

(defn player-start
  [^IGodotArea2D player p_method_data p_user_data vs]
  (let [size   (:screen-size @state)
        shape  ^IGodotCollisionShape2D (.getNode player "CollisionShape2D")
        pos    [(/ (size 0) 2) (/ (size 1) 2)]]
    (swap! state assoc-in [:player :position] pos)
    (.setPosition player (api/vec2 pos))

    (.show player)
    (.setDisabled shape false)))

(defn player-ready
  [^IGodotArea2D player p_method_data p_user_data vs]
  (.hide player)

  (swap! state assoc :screen-size (vec (seq (.getViewportRect player)))))

(defn hud-show-message
  [^IGodotCanvasLayer hud text]
  (let [message       ^IGodotLabel (.getNode hud "Message")
        message-timer ^IGodotTimer (.getNode hud "MessageTimer")]
    (doto message
      (.setText text)
      (.show))

    (.start message-timer 0)
    (async/go
      (async/<! (listen message-timer "timeout"))
      (async/<! (defer #(.hide message))))))

(defn hud-show-game-over
  [^IGodotCanvasLayer hud]
  (async/go
    (async/<! (defer #(hud-show-message hud "Game Over!")))
    (defer #(hud-show-message hud "Dodge the Creeps!"))
    (let [one-shot-timer (.createTimer ^IGodotSceneTree (.getTree hud) 1)]
      (async/<! (listen one-shot-timer "timeout"))
      (defer #(.show ^IGodotControl (.getNode hud "StartButton"))))))

(defn main-game-over
  [^IGodotArea2D main p_method_data p_user_data vs]
  (.stop ^IGodotAudioStreamPlayer (.getNode main "Music"))
  (.play ^IGodotAudioStreamPlayer (.getNode main "DeathSound"))

  (.callGroup ^IGodotSceneTree (.getTree main) "mobs" "queue_free")

  (doto main
    (-> ^IGodotTimer (.getNode "ScoreTimer") .stop)
    (-> ^IGodotTimer (.getNode "MobTimer") .stop))

  (hud-show-game-over (.getNode main "HUD")))

(defn hud-update-score
  [^IGodotCanvasLayer hud score]
  (.setText ^IGodotLabel (.getNode hud "ScoreLabel") (str score)))

(defn main-new-game
  [^IGodotArea2D main p_method_data p_user_data vs]
  (swap! state assoc :score 0)
  (.play ^IGodotAudioStreamPlayer (.getNode main "Music"))

  (doto (.getNode main "HUD")
    (hud-update-score (:score @state))
    (hud-show-message "Get Ready"))

  (.callv ^IGodotArea2D (.getNode main "Player") "start" [(.getPosition ^IGodotPosition2D (.getNode main "StartPosition"))])
  (.start ^IGodotTimer (.getNode main "StartTimer")))

(defn player-body-entered
  [^IGodotArea2D player p_method_data p_user_data vs]
  (let [shape ^IGodotCollisionShape2D (.getNode player "CollisionShape2D")]
    (.emitSignal ^IGodotArea2D player "hit")
    (.setDeferred shape "disabled" true)
    (.hide player)))

(defn main-ready
  [^IGodotNode main p_method_data p_user_data vs]
  ;; randomize mentioned in tutorial
  nil)

(defn clamp
  [[a b] & {:keys [to]}]
  (let [[right bottom] to]
    [(Math/min (Math/max (float 0) (float a)) (float right))
     (Math/min (Math/max (float 0) (float b)) (float bottom))]))

(defn player-process
  [^IGodotArea2D player p_method_data p_user_data vs]
  (let [speed           400
        delta           (first vs)
        input           ^IGodotInput (->object "Input")
        animated-sprite ^IGodotAnimatedSprite (.getNode player "AnimatedSprite")
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
  [^IGodotRigidBody2D mob p_method_data p_user_data vs]
  (let [animated-sprite ^IGodotAnimatedSprite (.getNode mob "AnimatedSprite")
        mob-types       (. ^IGodotSpriteFrames (. animated-sprite getSpriteFrames) getAnimationNames)]
    (.setAnimation animated-sprite (rand-nth mob-types))))

(defn mob-screen-exited
  [^IGodotRigidBody2D mob p_method_data p_user_data vs]
  (.queueFree mob))

(defn main-start-timer-timeout
  [^IGodotNode main p_method_data p_user_data vs]
  (doto main
    (-> ^IGodotTimer (.getNode "ScoreTimer") .start)
    (-> ^IGodotTimer (.getNode "MobTimer") .start)))

(defn main-score-timer-timeout
  [^IGodotNode main p_method_data p_user_data vs]
  (swap! state update :score inc)
  (hud-update-score (.getNode main "HUD")
                    (:score @state)))

(defn rand-range
  [a b]
  (let [d (- b a)]
    (+ a (* (rand) d))))

(defn main-mob-timer-timeout
  [^IGodotNode main p_method_data p_user_data vs]
  (let [node ^IGodotPathFollow2D (.getNode main "MobPath/MobSpawnLocation")]
    (.setOffset node (rand-int Integer/MAX_VALUE))
    ;; TODO IGodotRigidBody2D is not derived
    (let [mob-instance ^IGodotRigidBody2D (.instance ^IGodotPackedScene (:mob @state))]
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

        (->> (godot/vector2-rotated (.getLinearVelocity mob-instance) direction)
             (->object "Vector2")
             (.setLinearVelocity mob-instance))))))

(defn main-set-mob
  [p_instance p_method_data p_user_data value]
  (swap! state assoc :mob (->object (proto/pvariant->object value))))

(defn hud-start-button-pressed
  [^IGodotCanvasLayer hud p_method_data p_user_data vs]
  (let [start-button ^IGodotButton (.getNode hud "StartButton")]
    (.hide start-button)
    (.emitSignal hud "start_game")))

(def classes
  {"Main"   {:base       "Control"
             :create     (fn [& args] (println :create))
             :destroy    (fn [& args] (println :destroy))
             :properties {"Mob" {:type   :packed-scene
                                 :value  nil
                                 :setter #'main-set-mob
                                 :getter (fn [& args]
                                           (println :get-mob args))}}
             :methods    {"new_game"               #'main-new-game
                          "_ready"                 #'main-ready
                          "game_over"              #'main-game-over
                          "_on_StartTimer_timeout" #'main-start-timer-timeout
                          "_on_ScoreTimer_timeout" #'main-score-timer-timeout
                          "_on_MobTimer_timeout"   #'main-mob-timer-timeout}}

   "HUD"    {:base    "CanvasLayer"
             :create  (fn [& args] (println :create))
             :destroy (fn [& args] (println :destroy))
             :signals #{"start_game"}
             :methods {"_on_StartButton_pressed" #'hud-start-button-pressed}}

   "Player" {:base    "Area2D"
             :create  (fn [& args] (println :create))
             :destroy (fn [& args] (println :destroy))
             :methods {"_process"                #'player-process
                       "_ready"                  #'player-ready
                       "_on_Player_body_entered" #'player-body-entered
                       "start"                   #'player-start}
             :signals #{"hit"}}

   "Mob"    {:base    "RigidBody2D"
             :create  (fn [& args] (println :create))
             :destroy (fn [& args] (println :destroy))

             :properties {"min_speed" {:type  nil
                                       :value 150.0}
                          "max_speed" {:type  nil
                                       :value 250.0}}

             :methods {"_ready"                                 #'mob-ready
                       "_on_VisibilityNotifier2D_screen_exited" #'mob-screen-exited}}



   })

(defn register-methods
  [p-handle]
  (godot/register-classes p-handle classes)

  (callbacks/register-callbacks p-handle "Main" "HUD" "Mob" "Player"))

(defn reload-scene
  []
  (.callDeferred ^IGodotObject (.getTree (get-root)) "reload_current_scene"))
