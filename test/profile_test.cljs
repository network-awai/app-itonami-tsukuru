(ns profile-test
  (:require [tsukuru.profile :as p] [tsukuru.rfq :as r]
            ["node:fs" :as fs] ["node:test" :refer [test]] ["node:assert/strict" :as a]))

(def profile (js/JSON.parse (fs/readFileSync "profiles/business-bot-profile.json" "utf8")))
(defn clone [x] (js/JSON.parse (js/JSON.stringify x)))
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))

(test "bundled profile is the canonical 4610 row with provenance"
  (fn []
    (a/deepEqual (p/profile-problems profile) [])
    (a/equal (get-in profile [:profile :business_blueprint :isic_rev5]) "4610")
    (let [x (clone profile)] (aset (aget x "provenance") "revision" "main")
      (a/ok (some #{"provenance"} (p/profile-problems x))))
    (let [x (clone profile)] (aset (aget (aget x "profile") "business_blueprint") "governor" "other")
      (a/ok (some #{"governor"} (p/profile-problems x))))
    (let [x (clone profile)] (aset (aget (aget x "profile") "provisioning") "state" "launched")
      (a/ok (some #{"provisioning"} (p/profile-problems x))))
    (let [x (clone profile)] (aset (aget x "profile") "profile_id" "bot-profile:cloud-itonami-unspsc-73")
      (a/ok (some #{"profile-id"} (p/profile-problems x))))
    (a/ok (seq (p/profile-problems {})))))

(test "the bot only drafts; it never applies an RFQ event or pays"
  (fn []
    (doseq [t ["find-suppliers" "draft-rfq" "compare-quotes" "summarize-quote"]] (a/ok (p/bot-may? t)))
    (doseq [e (concat p/user-events ["pay" "send-rfq" "" nil])] (a/equal (p/bot-may? e) false))))

(test "approval events are real RFQ events and cover sending, awarding and ordering"
  (fn []
    (a/deepEqual (vec (sort p/approval-events)) ["award" "place-order" "publish"])
    (doseq [e p/approval-events] (a/ok (contains? p/user-events e)))
    (a/equal (p/needs-approval? "withdraw-quote") false)
    (let [draft {:schema r/schema :state "draft"}]
      (doseq [e p/user-events]
        (a/notEqual (reason #(r/transition draft {:type e} 0)) "event-unknown" e))
      (a/equal (reason #(r/transition draft {:type "pay"} 0)) "event-unknown"))))

(test "inference stays on the device unless a remote call is approved per operation"
  (fn []
    (a/equal (:default p/inference) "on-device")
    (a/equal (:rail p/inference) "itonami")))
