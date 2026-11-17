(ns tsukuru.rfq
  "Pure, fail-closed domain for the minimal B2B RFQ flow (AWAI-3244):
  create an inquiry, publish it, collect supplier quotes, award one quote,
  place the order with an approval bound to that exact quote.

  No I/O here. Sending an RFQ to suppliers, notifying them or paying is an
  external operation for the app layer and needs its own per-operation consent
  through the shared client runtime. This namespace only decides whether a
  state change is valid. Runs in the browser (Squint) and on a JVM host."
  (:require [clojure.string :as str]))

(def schema "itonami.tsukuru.rfq.v1")
(def order-schema "itonami.tsukuru.purchase-order.v1")

;; Fulfillment modes carried over from the legacy tsukuru production-order
;; design. Anything other than stock is made for this buyer and needs at least
;; one design artifact.
(def fulfillment-modes #{"stock" "bto" "mto" "cto"})

;; Artifact kinds: neutral CAD and references from the legacy supplier exchange
;; (`tsukuru-alibaba-supplier-rfq-json` formats), minus the server-side ones.
(def artifact-kinds #{"step" "iges" "gltf" "autocad-dxf" "autocad-dwg-reference"
                      "fusion360-archive-reference" "pdf-drawing" "spec-text"})

(def incoterms #{"EXW" "FCA" "CPT" "CIP" "DAP" "DPU" "DDP" "FAS" "FOB" "CFR" "CIF"})

(def states #{"draft" "open" "awarded" "ordered" "cancelled"})

(def max-quantity 1000000000)
(def max-quotes 200)
(def max-artifacts 50)

(defn- match? [re s] (and (string? s) (some? (re-matches re s))))
(defn- int-value? [x] #?(:clj (integer? x) :cljs (js/Number.isSafeInteger x)))
(defn- array-value? [x] #?(:clj (sequential? x) :cljs (js/Array.isArray x)))
(defn- pos-int? [x] (and (int-value? x) (> x 0)))
(defn- short-text? [x n] (and (string? x) (not (str/blank? x)) (<= (count x) n)))
(defn- refuse [reason] (throw (ex-info reason {:reason reason})))

(defn safe-id? [x]
  (and (match? #"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}" x)
       (not (contains? #{"__proto__" "constructor" "prototype"} x))))

(defn parse-time [s]
  (when (match? #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z" s)
    #?(:clj (try (.toEpochMilli (java.time.Instant/parse s)) (catch Exception _ nil))
       :cljs (let [t (js/Date.parse s)] (when (js/Number.isFinite t) t)))))

(defn- currency? [x] (match? #"[A-Z]{3}" x))
(defn- country? [x] (match? #"[A-Z]{2}" x))

;; ---- inquiry ---------------------------------------------------------------

(defn- artifact-ok? [a]
  (and (contains? artifact-kinds (:kind a))
       (short-text? (:name a) 200)
       (match? #"[0-9a-f]{64}" (:sha256 a))))

(defn rfq-problems
  "Every missing or invalid field of a new inquiry, as reason strings.
  An empty vector means the inquiry may be created."
  [r]
  (let [arts (:artifacts r)]
    (cond-> []
      (not= schema (:schema r)) (conj "schema")
      (not (safe-id? (:id r))) (conj "id")
      (not (safe-id? (:buyer_id r))) (conj "buyer-id")
      (not (short-text? (:title r) 200)) (conj "title")
      (not (match? #"\d{6,10}" (:hs_code r))) (conj "hs-code")
      (not (and (pos-int? (:quantity r)) (<= (:quantity r) max-quantity))) (conj "quantity")
      (not (short-text? (:unit r) 20)) (conj "unit")
      (not (currency? (:currency r))) (conj "currency")
      (not (contains? incoterms (:incoterm r))) (conj "incoterm")
      (not (country? (:delivery_country r))) (conj "delivery-country")
      (nil? (parse-time (:quote_deadline r))) (conj "quote-deadline")
      (not (contains? fulfillment-modes (:fulfillment r))) (conj "fulfillment")
      (not (and (array-value? arts) (<= (count arts) max-artifacts) (every? artifact-ok? arts))) (conj "artifacts")
      (and (not= "stock" (:fulfillment r)) (array-value? arts) (empty? arts)) (conj "missing-design-artifacts"))))

(defn create
  "A new inquiry in draft. Refuses with the first problem."
  [r]
  (let [p (rfq-problems r)]
    (when (seq p) (refuse (first p)))
    (assoc r :state "draft" :quotes [] :awarded_supplier_id nil :order nil)))

;; ---- quotes ----------------------------------------------------------------

(defn quote-problems
  "Problems of one supplier quote against an open inquiry at now-ms."
  [r q now-ms]
  (let [valid (parse-time (:valid_until q))
        moq (:moq q)]
    (cond-> []
      (not (safe-id? (:supplier_id q))) (conj "supplier-id")
      (= (:supplier_id q) (:buyer_id r)) (conj "self-quote")
      (not (pos-int? (:unit_price_micros q))) (conj "unit-price")
      (not= (:currency r) (:currency q)) (conj "currency-mismatch")
      (not= (:incoterm r) (:incoterm q)) (conj "incoterm-mismatch")
      (not (and (pos-int? moq) (<= moq (:quantity r)))) (conj "moq")
      (not (and (pos-int? (:lead_time_days q)) (<= (:lead_time_days q) 730))) (conj "lead-time")
      (not (and valid (> valid now-ms))) (conj "valid-until")
      (and (pos-int? (:unit_price_micros q))
           (not (int-value? (* (:quantity r) (:unit_price_micros q))))) (conj "total-overflow"))))

(defn total-micros [r q] (* (:quantity r) (:unit_price_micros q)))

(defn- quote-of [r supplier-id]
  (first (filter #(= supplier-id (:supplier_id %)) (:quotes r))))

(defn- live-quotes [r now-ms]
  (filterv #(> (parse-time (:valid_until %)) now-ms) (:quotes r)))

(defn ranked-quotes
  "Quotes still valid at now-ms, cheapest total first, then shortest lead time.
  For the bot's comparison view; ranking is advice, the award stays the buyer's."
  [r now-ms]
  (let [cmp (fn [a b]
              (let [ta (total-micros r a) tb (total-micros r b)
                    la (:lead_time_days a) lb (:lead_time_days b)]
                (cond (< ta tb) -1 (> ta tb) 1
                      (< la lb) -1 (> la lb) 1
                      :else (compare (:supplier_id a) (:supplier_id b)))))]
    (vec (sort cmp (live-quotes r now-ms)))))

;; ---- transitions -----------------------------------------------------------

(defn- require-state [r & allowed]
  (when-not (some #(= % (:state r)) allowed) (refuse (str "state-" (:state r)))))

(defn- before-deadline [r now-ms]
  (when-not (< now-ms (parse-time (:quote_deadline r))) (refuse "deadline-passed")))

(defn- approval-ok? [r q a]
  (and (= "place-order" (:action a))
       (= (:buyer_id r) (:approved_by a))
       (= (:id r) (:rfq_id a))
       (= (:supplier_id q) (:supplier_id a))
       (= (:unit_price_micros q) (:unit_price_micros a))
       (= (:quantity r) (:quantity a))
       (= (:currency r) (:currency a))
       (= (total-micros r q) (:total_micros a))))

(defn transition
  "Apply one event to an inquiry at now-ms and return the new inquiry.
  Any event that is not valid in the current state is refused, never ignored."
  [r e now-ms]
  (when-not (contains? states (:state r)) (refuse "state-unknown"))
  (case (:type e)
    "publish"
    (do (require-state r "draft")
        (before-deadline r now-ms)
        (assoc r :state "open"))

    "submit-quote"
    (let [q (:quote e)]
      (require-state r "open")
      (before-deadline r now-ms)
      (let [p (quote-problems r q now-ms)] (when (seq p) (refuse (first p))))
      (when (quote-of r (:supplier_id q)) (refuse "duplicate-quote"))
      (when (>= (count (:quotes r)) max-quotes) (refuse "too-many-quotes"))
      (assoc r :quotes (conj (vec (:quotes r)) q)))

    "withdraw-quote"
    (do (require-state r "open")
        (when-not (quote-of r (:supplier_id e)) (refuse "no-such-quote"))
        (assoc r :quotes (filterv #(not= (:supplier_id e) (:supplier_id %)) (:quotes r))))

    "award"
    (let [q (quote-of r (:supplier_id e))]
      (require-state r "open")
      (when-not q (refuse "no-such-quote"))
      (when-not (> (parse-time (:valid_until q)) now-ms) (refuse "quote-expired"))
      (assoc r :state "awarded" :awarded_supplier_id (:supplier_id q)))

    "place-order"
    (let [q (quote-of r (:awarded_supplier_id r))]
      (require-state r "awarded")
      (when-not q (refuse "no-such-quote"))
      (when-not (> (parse-time (:valid_until q)) now-ms) (refuse "quote-expired"))
      (when-not (approval-ok? r q (:approval e)) (refuse "approval-mismatch"))
      (assoc r :state "ordered"
             :order {:schema order-schema :rfq_id (:id r) :buyer_id (:buyer_id r)
                     :supplier_id (:supplier_id q) :quantity (:quantity r) :unit (:unit r)
                     :unit_price_micros (:unit_price_micros q) :total_micros (total-micros r q)
                     :currency (:currency r) :incoterm (:incoterm r)
                     :delivery_country (:delivery_country r) :lead_time_days (:lead_time_days q)}))

    "cancel"
    (do (require-state r "draft" "open" "awarded")
        (assoc r :state "cancelled"))

    (refuse "event-unknown")))

(defn approval-request
  "What the buyer must see and approve before place-order: bound to the exact
  inquiry, supplier, price, quantity and total. The app shows this and records
  the buyer's choice; the domain only checks that the choice matches."
  [r]
  (require-state r "awarded")
  (let [q (quote-of r (:awarded_supplier_id r))]
    {:action "place-order" :rfq_id (:id r) :supplier_id (:supplier_id q)
     :quantity (:quantity r) :unit_price_micros (:unit_price_micros q)
     :currency (:currency r) :total_micros (total-micros r q)}))
