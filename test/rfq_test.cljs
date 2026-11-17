(ns rfq-test
  (:require [tsukuru.rfq :as r] ["node:test" :refer [test]] ["node:assert/strict" :as a]))

(def now (js/Date.parse "2026-11-18T00:00:00Z"))
(def hour 3600000)
(defn reason [f] (try (f) nil (catch :default e (:reason (ex-data e)))))

(def inquiry
  {:schema r/schema :id "rfq-20261118-0001" :buyer_id "did:web:buyer.example"
   :title "Aluminium enclosure, anodized" :hs_code "761699" :quantity 5000 :unit "pcs"
   :currency "USD" :incoterm "FOB" :delivery_country "JP"
   :quote_deadline "2026-11-25T00:00:00Z" :fulfillment "mto"
   :artifacts [{:kind "step" :name "enclosure-v3.step"
                :sha256 "0f343b0931126a20f133d67c2b018a3b1d2c7a1f0e8b6c5d4e3f2a1b0c9d8e7f"}]})

(defn quote* [supplier price lead]
  {:supplier_id supplier :unit_price_micros price :currency "USD" :incoterm "FOB"
   :moq 1000 :lead_time_days lead :valid_until "2026-12-01T00:00:00Z"})

(defn open-with-quotes []
  (-> (r/create inquiry)
      (r/transition {:type "publish"} now)
      (r/transition {:type "submit-quote" :quote (quote* "did:web:factory-a.example" 2100000 30)} now)
      (r/transition {:type "submit-quote" :quote (quote* "did:web:factory-b.example" 1900000 45)} now)
      (r/transition {:type "submit-quote" :quote (quote* "did:web:factory-c.example" 1900000 20)} now)))

(test "happy path: create, publish, quote, award, place order with a bound approval"
  (fn []
    (a/deepEqual (r/rfq-problems inquiry) [])
    (let [o (open-with-quotes)
          ranked (r/ranked-quotes o now)
          _ (a/deepEqual (mapv :supplier_id ranked)
                         ["did:web:factory-c.example" "did:web:factory-b.example" "did:web:factory-a.example"])
          aw (r/transition o {:type "award" :supplier_id "did:web:factory-c.example"} now)
          req (r/approval-request aw)
          approval (assoc req :approved_by "did:web:buyer.example")
          done (r/transition aw {:type "place-order" :approval approval} (+ now hour))
          po (:order done)]
      (a/equal (:state o) "open")
      (a/equal (:state aw) "awarded")
      (a/equal (:total_micros req) 9500000000)
      (a/equal (:state done) "ordered")
      (a/equal (:schema po) r/order-schema)
      (a/equal (:supplier_id po) "did:web:factory-c.example")
      (a/equal (:total_micros po) 9500000000)
      (a/equal (:lead_time_days po) 20))))

(test "missing or invalid inquiry fields are refused"
  (fn []
    (a/deepEqual (r/rfq-problems (dissoc inquiry :hs_code)) ["hs-code"])
    (a/deepEqual (r/rfq-problems (assoc inquiry :quantity 0)) ["quantity"])
    (a/deepEqual (r/rfq-problems (assoc inquiry :currency "usd")) ["currency"])
    (a/deepEqual (r/rfq-problems (assoc inquiry :incoterm "XYZ")) ["incoterm"])
    (a/deepEqual (r/rfq-problems (assoc inquiry :quote_deadline "next week")) ["quote-deadline"])
    (a/deepEqual (r/rfq-problems (assoc inquiry :artifacts [])) ["missing-design-artifacts"])
    (a/deepEqual (r/rfq-problems (assoc inquiry :fulfillment "stock" :artifacts [])) [])
    (a/deepEqual (r/rfq-problems (assoc inquiry :artifacts [{:kind "exe" :name "x" :sha256 "0"}])) ["artifacts"])
    (a/deepEqual (r/rfq-problems (assoc inquiry :id "__proto__")) ["id"])
    (a/equal (reason #(r/create (dissoc inquiry :buyer_id))) "buyer-id")))

(test "invalid quotes are refused"
  (fn []
    (let [o (-> (r/create inquiry) (r/transition {:type "publish"} now))
          q (quote* "did:web:factory-a.example" 2100000 30)
          submit (fn [x] (reason #(r/transition o {:type "submit-quote" :quote x} now)))]
      (a/equal (submit (assoc q :currency "EUR")) "currency-mismatch")
      (a/equal (submit (assoc q :incoterm "EXW")) "incoterm-mismatch")
      (a/equal (submit (assoc q :moq 6000)) "moq")
      (a/equal (submit (assoc q :unit_price_micros 0)) "unit-price")
      (a/equal (submit (assoc q :valid_until "2026-11-17T00:00:00Z")) "valid-until")
      (a/equal (submit (assoc q :supplier_id "did:web:buyer.example")) "self-quote")
      (a/equal (submit (assoc q :unit_price_micros 9007199254740991)) "total-overflow")
      (let [o2 (r/transition o {:type "submit-quote" :quote q} now)]
        (a/equal (reason #(r/transition o2 {:type "submit-quote" :quote q} now)) "duplicate-quote")))))

(test "invalid state transitions are refused, never ignored"
  (fn []
    (let [d (r/create inquiry)
          o (open-with-quotes)
          aw (r/transition o {:type "award" :supplier_id "did:web:factory-a.example"} now)
          late (+ (js/Date.parse "2026-11-25T00:00:00Z") 1)]
      (a/equal (reason #(r/transition d {:type "submit-quote" :quote (quote* "did:web:f.example" 1 1)} now)) "state-draft")
      (a/equal (reason #(r/transition d {:type "award" :supplier_id "x"} now)) "state-draft")
      (a/equal (reason #(r/transition d {:type "publish"} late)) "deadline-passed")
      (a/equal (reason #(r/transition o {:type "place-order" :approval {}} now)) "state-open")
      (a/equal (reason #(r/transition o {:type "award" :supplier_id "did:web:nobody.example"} now)) "no-such-quote")
      (a/equal (reason #(r/transition o {:type "submit-quote" :quote (quote* "did:web:late.example" 1 1)} late)) "deadline-passed")
      (a/equal (reason #(r/transition aw {:type "publish"} now)) "state-awarded")
      (a/equal (reason #(r/transition aw {:type "teleport"} now)) "event-unknown")
      (a/equal (reason #(r/transition (assoc aw :state "shipped") {:type "cancel"} now)) "state-unknown")
      (let [c (r/transition aw {:type "cancel"} now)]
        (a/equal (:state c) "cancelled")
        (a/equal (reason #(r/transition c {:type "cancel"} now)) "state-cancelled")))))

(test "place-order needs an approval bound to the exact awarded quote"
  (fn []
    (let [aw (r/transition (open-with-quotes) {:type "award" :supplier_id "did:web:factory-a.example"} now)
          ok (assoc (r/approval-request aw) :approved_by "did:web:buyer.example")
          place (fn [x t] (reason #(r/transition aw {:type "place-order" :approval x} t)))]
      (a/equal (place nil now) "approval-mismatch")
      (a/equal (place (assoc ok :approved_by "did:web:factory-a.example") now) "approval-mismatch")
      (a/equal (place (assoc ok :supplier_id "did:web:factory-b.example") now) "approval-mismatch")
      (a/equal (place (assoc ok :total_micros 1) now) "approval-mismatch")
      (a/equal (place (assoc ok :action "award") now) "approval-mismatch")
      (a/equal (place ok (js/Date.parse "2026-12-02T00:00:00Z")) "quote-expired")
      (a/equal (reason #(r/transition (assoc aw :awarded_supplier_id "did:web:gone.example")
                                      {:type "place-order" :approval ok} now)) "no-such-quote")
      (a/equal (:state (r/transition aw {:type "place-order" :approval ok} now)) "ordered"))))

(test "withdrawn and expired quotes drop out of the ranking"
  (fn []
    (let [o (open-with-quotes)
          w (r/transition o {:type "withdraw-quote" :supplier_id "did:web:factory-c.example"} now)]
      (a/deepEqual (mapv :supplier_id (r/ranked-quotes w now))
                   ["did:web:factory-b.example" "did:web:factory-a.example"])
      (a/deepEqual (r/ranked-quotes o (js/Date.parse "2026-12-01T00:00:00Z")) [])
      (a/equal (reason #(r/transition w {:type "withdraw-quote" :supplier_id "did:web:factory-c.example"} now)) "no-such-quote"))))
