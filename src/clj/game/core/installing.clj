(in-ns 'game.core)

(declare host in-play? install-locked? make-rid reveal run-flag? locale-list locale->zone set-prop system-msg
         turn-flag? update-breaker-strength update-character-strength update-run-character)

;;;; Functions for the installation and deactivation of cards.

;;; Deactivate a card
(defn- dissoc-card
  "Dissoc relevant keys in card"
  [card keep-counter]
  (let [c (dissoc card :current-strength :abilities :subroutines :challenger-abilities :revealed :special :new
                  :added-virus-counter :subtype-target :sifr-used :sifr-target)
        c (if keep-counter c (dissoc c :counter :rec-counter :advance-counter :extra-advance-counter))]
    (if (and (= (:side c) "Challenger") (not= (last (:zone c)) :facedown))
      (dissoc c :installed :facedown :counter :rec-counter :pump :locale-target) c)))

(defn- trigger-leave-effect
  "Triggers leave effects for specified card if relevant"
  [state side {:keys [disabled installed revealed facedown zone host] :as card}]
  (when-let [leave-effect (:leave-play (card-def card))]
    (when (and (not disabled)
               (not (and (= (:side card) "Challenger") host (not installed) (not facedown)))
               (or (and (= (:side card) "Challenger") installed (not facedown))
                   revealed
                   (and host (not facedown))
                   (= (first zone) :current)
                   (= (first zone) :scored)))
      (leave-effect state side (make-eid state) card nil))))

(defn- handle-prevent-effect
  "Handles prevent effects on the card"
  [state card]
  (when-let [prevent (:prevent (card-def card))]
     (doseq [[ptype pvec] prevent]
       (doseq [psub pvec]
         (swap! state update-in [:prevent ptype psub]
                (fn [pv] (remove #(= (:cid %) (:cid card)) pv)))))))

(defn deactivate
  "Deactivates a card, unregistering its events, removing certain attribute keys, and triggering
  some events."
  ([state side card] (deactivate state side card nil))
  ([state side card keep-counter]
   (unregister-events state side card)
   (trigger-leave-effect state side card)
   (handle-prevent-effect state card)
   (when (and (:memoryunits card) (:installed card) (not (:facedown card)))
     (gain state :challenger :memory (:memoryunits card)))
   (when (and (find-cid (:cid card) (all-installed state side))
              (not (:disabled card))
              (or (:revealed card) (:installed card)))
     (when-let [in-play (:in-play (card-def card))]
       (apply lose state side in-play)))
   (dissoc-card card keep-counter)))


;;; Initialising a card
(defn- ability-init
  "Gets abilities associated with the card"
  [cdef]
  (let [abilities (if (:recurring cdef)
                    (conj (:abilities cdef) {:msg "Take 1 [Recurring Credits]"})
                    (:abilities cdef))]
    (for [ab abilities]
      (assoc (select-keys ab [:cost :pump :breaks]) :label (make-label ab)))))

(defn- challenger-ability-init
  "Gets abilities associated with the card"
  [cdef]
  (for [ab (:challenger-abilities cdef)]
    (assoc (select-keys ab [:cost]) :label (make-label ab))))

(defn- subroutines-init
  "Initialised the subroutines associated with the card, these work as abilities"
  [cdef]
  (let [subs (:subroutines cdef)]
    (for [sub subs]
      {:label (make-label sub)})))

(defn card-init
  "Initializes the abilities and events of the given card."
  ([state side card] (card-init state side card {:resolve-effect true :init-data true}))
  ([state side card args] (card-init state side (make-eid state) card args))
  ([state side eid card {:keys [resolve-effect init-data] :as args}]
   (let [cdef (card-def card)
         recurring (:recurring cdef)
         abilities (ability-init cdef)
         run-abs (challenger-ability-init cdef)
         subroutines (subroutines-init cdef)
         c (merge card
                  (when init-data (:data cdef))
                  {:abilities abilities :subroutines subroutines :challenger-abilities run-abs})
         c (if (number? recurring) (assoc c :rec-counter recurring) c)
         c (if (string? (:strength c)) (assoc c :strength 0) c)]
     (when recurring
       (let [r (if (number? recurring)
                 (effect (set-prop card :rec-counter recurring))
                 recurring)]
         (register-events state side
                          {(if (= side :contestant) :contestant-phase-12 :challenger-phase-12)
                           {:effect r}} c)))
     (when-let [prevent (:prevent cdef)]
       (doseq [[ptype pvec] prevent]
         (doseq [psub pvec]
           (swap! state update-in [:prevent ptype psub] #(conj % card)))))
     (update! state side c)
     (when-let [events (:events cdef)]
       (register-events state side events c))
     (if (and resolve-effect (is-ability? cdef))
       (resolve-ability state side eid cdef c nil)
       (effect-completed state side eid))
     (when-let [in-play (:in-play cdef)]
       (apply gain state side in-play))
     (get-card state c))))


;;; Intalling a contestant card
(defn- contestant-can-install-reason
  "Checks if the specified card can be installed.
   Returns true if there are no problems
   Returns :region if Narly check fails
   Returns :character if Character check fails
   !! NB: This should only be used in a check with `true?` as all return values are truthy"
  [state side card dest-zone]
  (cond
    ;; Narly check
    (and (has-subtype? card "Narly")
         (some #(has-subtype? % "Narly") dest-zone))
    :region
    ;; Character install prevented by Unscheduled Maintenance
    (and (character? card)
         (not (turn-flag? state side card :can-install-character)))
    :character
    ;; Installing not locked
    (install-locked? state side) :lock-install
    ;; no restrictions
    :default true))

(defn- contestant-can-install?
  "Checks `contestant-can-install-reason` if not true, toasts reason and returns false"
  [state side card dest-zone]
  (let [reason (contestant-can-install-reason state side card dest-zone)
        reason-toast #(do (toast state side % "warning") false)
        title (:title card)]
    (case reason
      ;; pass on true value
      true true
      ;; failed region check
      :region
      (reason-toast (str "Cannot install " (:title card) ", limit of one Region per locale"))
      ;; failed install lock check
      :lock-install
      (reason-toast (str "Unable to install " title ", installing is currently locked"))
      ;; failed Character check
      :character
      (reason-toast (str "Unable to install " title ": can only install 1 piece of Character per turn")))))

(defn contestant-installable-type?
  "Is the card of an acceptable type to be installed in a locale"
  [card]
  (some? (#{"Site" "Agenda" "Character" "Region" "Resource"} (:type card))))

(defn- contestant-install-site-agenda
  "Forces the contestant to trash an existing site or agenda if a second was just installed."
  [state side eid card dest-zone locale]
  (let [prev-card (some #(when (#{"Hazard"} (:type %)) %) dest-zone)]
    (if (and (#{"Hazard"} (:type card))
             prev-card
             (not (:host card)))
      (resolve-ability state side eid {:prompt (str "The " (:title prev-card) " in " locale " will now be trashed.")
                                       :choices ["OK"]
                                       :effect (req (system-msg state :contestant (str "trashes " (card-str state prev-card)))
                                                    (when (get-card state prev-card) ; make sure they didn't trash the card themselves
                                                    (trash state :contestant prev-card {:keep-locale-alive true})))}
                       nil nil)
      (effect-completed state side eid))))

(defn- contestant-install-message
  "Prints the correct install message."
  [state side card locale install-state cost-str]
  (let [card-name (if (or (= :revealed-no-cost install-state)
                          (= :face-up install-state)
                          (:revealed card))
                    (:title card)
                    (if (character? card) "Character" "a card"))
        locale-name (if (= locale "New party")
                      (str (party-num->name (get-in @state [:rid])) " (new party)")
                      locale)]
    (system-msg state side (str (build-spend-msg cost-str "install") card-name
                                (if (character? card) " protecting " " in ") locale-name))))

(defn contestant-install-list
  "Returns a list of targets for where a given card can be installed."
  [state card]
  (let [hosts (filter #(when-let [can-host (:can-host (card-def %))]
                        (and (revealed? %)
                             (can-host state :contestant (make-eid state) % [card])))
                      (all-installed state :contestant))]
    (concat hosts (locale-list state card))))

(defn contestant-install
  ([state side card locale] (contestant-install state side (make-eid state) card locale nil))
  ([state side card locale args] (contestant-install state side (make-eid state) card locale args))
  ([state side eid card locale {:keys [extra-cost no-install-cost install-state host-card action] :as args}]
   (cond
     ;; No locale selected; show prompt to select an install site (Interns, Lateral Growth, etc.)
     (not locale)
     (continue-ability state side
                       {:prompt (str "Choose a location to install " (:title card))
                        :choices (contestant-install-list state card)
                        :delayed-completion true
                        :effect (effect (contestant-install eid card target args))}
                       card nil)
     ;; A card was selected as the locale; recurse, with the :host-card parameter set.
     (and (map? locale) (not host-card))
     (contestant-install state side eid card locale (assoc args :host-card locale))
     ;; A locale was selected
     :else
     (let [cdef (card-def card)
           slot (if host-card
                  (:zone host-card)
                  (conj (locale->zone state locale) (if (character? card) :characters :content)))
           dest-zone (get-in @state (cons :contestant slot))]
       ;; trigger :pre-contestant-install before computing install costs so that
       ;; event handlers may adjust the cost.
       (trigger-event state side :pre-contestant-install card {:locale locale :dest-zone dest-zone})
       (let [character-cost (if (and (character? card)
                               (not no-install-cost)
                               (not (ignore-install-cost? state side)))
                        (count dest-zone) 0)
             all-cost (concat extra-cost [:credit character-cost])
             end-cost (if no-install-cost 0 (install-cost state side card all-cost))
             install-state (or install-state (:install-state cdef))]
         (when (and (contestant-can-install? state side card dest-zone) (not (install-locked? state :contestant)))
           (if-let [cost-str (pay state side card end-cost action)]
             (do (let [c (-> card
                             (assoc :advanceable (:advanceable cdef) :new true)
                             (dissoc :seen :disabled))]
                   (when (= locale "New party")
                     (trigger-event state side :locale-created card))
                   (when (not host-card)
                     (contestant-install-message state side c locale install-state cost-str))
                   (play-sfx state side "install-contestant")

                   (let [moved-card (if host-card
                                      (host state side host-card (assoc c :installed true))
                                      (move state side c slot))]
                     (trigger-event state side :contestant-install moved-card)
                     (when (is-type? c "Agenda")
                       (update-advancement-cost state side moved-card))

                     ;; Check to see if a second agenda/site was installed.
                     (when-completed (contestant-install-site-agenda state side moved-card dest-zone locale)
                                     (do (cond
                                           ;; Ignore all costs. Pass eid to reveal.
                                           (= install-state :revealed-no-cost)
                                           (reveal state side eid moved-card {:ignore-cost :all-costs})

                                           ;; Pay costs. Pass eid to reveal.
                                           (= install-state :revealed)
                                           (reveal state side eid moved-card nil)

                                           ;; "Face-up" cards. Trigger effect-completed manually.
                                           (= install-state :face-up)
                                           (do (if (:install-state cdef)
                                                 (card-init state side
                                                            (assoc (get-card state moved-card) :revealed true :seen true)
                                                            {:resolve-effect false
                                                             :init-data true})
                                                 (update! state side (assoc (get-card state moved-card) :revealed true :seen true)))
                                               (when-not (:delayed-completion cdef)
                                                 (effect-completed state side eid)))

                                           ;; All other cards. Trigger effect-completed.
                                           :else
                                           (effect-completed state side eid))

                                         (when-let [dre (:hidden-events cdef)]
                                           (when-not (:revealed (get-card state moved-card))
                                             (register-events state side dre moved-card))))))))))
         (clear-install-cost-bonus state side))))))


;;; Installing a challenger card
(defn- challenger-can-install-reason
  "Checks if the specified card can be installed.
   Checks uniqueness of card and installed console.
   Returns true if there are no problems
   Returns :console if Console check fails
   Returns :unique if uniqueness check fails
   Returns :req if card-def :req check fails
   !! NB: This should only be used in a check with `true?` as all return values are truthy"
  [state side card facedown]
  (let [req (:req (card-def card))
        uniqueness (:uniqueness card)]
    (cond
      ;; Can always install a card facedown
      facedown true
      ;; Console check
      (and (has-subtype? card "Console")
           (some #(has-subtype? % "Console") (all-installed state :challenger)))
      :console
      ;; Installing not locked
      (install-locked? state side) :lock-install
      ;; Uniqueness check
      (and uniqueness (in-play? state card)) :unique
      ;; Req check
      (and req (not (req state side (make-eid state) card nil))) :req
      ;; Nothing preventing install
      :default true)))

(defn- challenger-can-install?
  "Checks `challenger-can-install-reason` if not true, toasts reason and returns false"
  [state side card facedown]
  (let [reason (challenger-can-install-reason state side card facedown)
        reason-toast #(do (toast state side % "warning") false)
        title (:title card)]
    (case reason
      ;; pass on true value
      true true
      ;; failed unique check
      :unique
      (reason-toast (str "Cannot install a second copy of " title " since it is unique. Please trash currently"
                         " installed copy first"))
      ;; failed install lock check
      :lock-install
      (reason-toast (str "Unable to install " title " since installing is currently locked"))
      ;; failed console check
      :console
      (reason-toast (str "Unable to install " title ": an installed console prevents the installation of a replacement"))
      :req
      (reason-toast (str "Installation requirements are not fulfilled for " title)))))

(defn- challenger-get-cost
  "Get the total install cost for specified card"
  [state side {:keys [cost memoryunits] :as card}
   {:keys [extra-cost no-cost facedown] :as params}]
  (install-cost state side card
                (concat extra-cost
                        (when (and (not no-cost) (not facedown)) [:credit cost])
                        (when (and memoryunits (not facedown)) [:memory memoryunits]))))

(defn- challenger-install-message
  "Prints the correct msg for the card install"
  [state side card-title cost-str
   {:keys [no-cost host-card facedown custom-message] :as params}]
  (if facedown
    (system-msg state side "installs a card facedown")
    (if custom-message
      (system-msg state side custom-message)
      (system-msg state side
                  (str (build-spend-msg cost-str "install") card-title
                       (when host-card (str " on " (:title host-card)))
                       (when no-cost " at no cost"))))))

(defn- handle-virus-counter-flag
  "Deal with setting the added-virus-counter flag"
  [state side installed-card]
  (if (and (has-subtype? installed-card "Virus")
           (pos? (get-in installed-card [:counter :virus] 0)))
    (update! state side (assoc installed-card :added-virus-counter true))))

(defn challenger-install
  "Installs specified challenger card if able
  Params include extra-cost, no-cost, host-card, facedown and custom-message."
  ([state side card] (challenger-install state side (make-eid state) card nil))
  ([state side card params] (challenger-install state side (make-eid state) card params))
  ([state side eid card {:keys [host-card facedown] :as params}]
   (if (and (empty? (get-in @state [side :locked (-> card :zone first)]))
            (not (seq (get-in @state [:challenger :lock-install]))))
     (if-let [hosting (and (not host-card) (not facedown) (:hosting (card-def card)))]
       (continue-ability state side
                         {:choices hosting
                          :prompt (str "Choose a card to host " (:title card) " on")
                          :delayed-completion true
                          :effect (effect (challenger-install eid card (assoc params :host-card target)))}
                         card nil)
       (do (trigger-event state "Challenger" :pre-install card facedown)
           (let [cost (challenger-get-cost state side card params)]
             (if (challenger-can-install? state side card facedown)
               (if-let [cost-str (pay state side card cost)]
                 (let [c (if host-card
                           (host state side host-card card)
                           (move state side card
                                 [:rig (if facedown :facedown (to-keyword (:type card)))]))
                       c (assoc c :installed true :new true)
                       installed-card (if facedown
                                        (update! state side c)
                                        (card-init state side c {:resolve-effect false
                                                                 :init-data true}))]
                   (challenger-install-message state side (:title card) cost-str params)
                   (play-sfx state side "install-challenger")
                   (when (and (is-type? card "Resource") (neg? (get-in @state [:challenger :memory])))
                     (toast state :challenger "You have run out of memory units!"))
                   (handle-virus-counter-flag state side installed-card)
                   (when (is-type? card "Muthereff")
                     (swap! state assoc-in [:challenger :register :installed-muthereff] true))
                   (when (has-subtype? c "Icebreaker")
                     (update-breaker-strength state side c))
                   (trigger-event-simult state side eid :challenger-install
                                         {:card-ability (card-as-handler installed-card)}
                                         installed-card))
                 (effect-completed state side eid))
               (effect-completed state side eid)))
           (clear-install-cost-bonus state side)))
     (effect-completed state side eid))))
