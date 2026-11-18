(ns tsukuru.profile
  "The Tsukuru business bot profile (AWAI-3246) and what the bot may do.

  `profiles/business-bot-profile.json` is the canonical registry row
  `bot-profile:cloud-itonami-isic-4610` copied unchanged from
  com-junkawasaki/root. This namespace checks that copy and declares the
  bot's role against the RFQ contract in `tsukuru.rfq`.

  The bot only prepares drafts for the buyer: supplier candidates, RFQ
  drafts, quote comparisons and quote summaries. Every RFQ state change is
  the user's own action, and the effecting ones need the user's approval
  for that exact operation. Payment is not an operation of this app."
  (:require [clojure.string :as str]))

(def blueprint-id "cloud-itonami-isic-4610")
(def governor "commission-broker-governor")

;; Drafts the bot may prepare. None of them changes an inquiry or reaches
;; a supplier; the app shows the draft and the user decides.
(def bot-tasks #{"find-suppliers" "draft-rfq" "compare-quotes" "summarize-quote"})

;; Every event of `tsukuru.rfq/transition`. The bot never applies one.
(def user-events #{"publish" "submit-quote" "withdraw-quote" "award" "place-order" "cancel"})

;; Buyer-side events that send, commit or order: each needs a fresh approval
;; bound to the displayed operation (shared client runtime, each-operation).
(def approval-events #{"publish" "award" "place-order"})

;; Inference for the app's own drafts: on the device first. A remote service
;; is a separate external operation with its own approval, and the Hermes
;; rail is never used here.
(def inference {:rail "itonami" :default "on-device" :remote "per-operation-approval"})

(defn- match? [re s] (and (string? s) (some? (re-matches re s))))
(defn- array-value? [x] #?(:clj (sequential? x) :cljs (js/Array.isArray x)))

(defn profile-problems
  "Problems with a bundled profile document, [] when it is the canonical
  4610 row with provenance. Fail closed: any missing piece is a problem."
  [doc]
  (let [p (:profile doc) bp (:business_blueprint p) money (:money p)]
    (cond-> []
      (not= "itonami.business-bot-profile.v1" (:schema doc)) (conj "schema")
      (not (and (= "com-junkawasaki/root" (get-in doc [:provenance :repository]))
                (match? #"[0-9a-f]{40}" (get-in doc [:provenance :revision])))) (conj "provenance")
      (not= (str "bot-profile:" blueprint-id) (:profile_id p)) (conj "profile-id")
      (not= blueprint-id (:id bp)) (conj "blueprint")
      (not= governor (:governor bp)) (conj "governor")
      (not= governor (get-in p [:runtime :governor])) (conj "runtime-governor")
      (not (str/includes? (str (get-in doc [:mission :statement])) "労働から解放")) (conj "mission")
      (not= "commerce" (get-in p [:mission :domain_family])) (conj "domain-family")
      (not (and (array-value? (:allowed_services money))
                (every? #(contains? #{"murakumo" "kotobase"} %) (:allowed_services money)))) (conj "allowed-services")
      (not (true? (get-in p [:health_rule :fail_closed]))) (conj "health-rule")
      (not= "blueprint-only" (get-in p [:provisioning :state])) (conj "provisioning"))))

(defn bot-may?
  "True only for a draft task. RFQ events, payment and anything unknown: false."
  [task]
  (contains? bot-tasks task))

(defn needs-approval?
  "True when an RFQ event needs the user's approval for that exact operation."
  [event]
  (contains? approval-events event))
