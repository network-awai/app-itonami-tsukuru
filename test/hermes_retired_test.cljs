(ns hermes-retired-test
  "AWAI-3315: the retired Hermes profiles stay retired and off the murakumo rail."
  (:require ["node:fs" :as fs] ["node:test" :refer [test]] ["node:assert/strict" :as a]))

(def profiles ["tsukuru-design-review" "tsukuru-rfq" "tsukuru-sim"])

(defn read-text [path] (fs/readFileSync path "utf8"))

(test "retired record copies keep no murakumo/mishima rail pin"
  (fn []
    (doseq [p profiles
            f [(str "hermes/profiles/" p "/config.yaml")
               (str "hermes/profiles/" p "/profile.yaml")
               (str "hermes/profiles/" p "/SOUL.md")]]
      (let [text (read-text f)]
        (doseq [banned ["murakumo" "mishima"]]
          (a/equal (.includes text banned) false (str f " names " banned)))))))

(test "tsukuru-scout is retired elsewhere, not in this repo"
  (fn []
    (a/equal (fs/existsSync "hermes/profiles/tsukuru-scout") false)))

(test "the retired notice is kept so nobody runs these profiles again"
  (fn []
    (let [text (read-text "hermes/README.md")]
      (a/ok (.includes text "Retired 2026-11-18 (AWAI-3246)"))
      (a/ok (.includes text "Do not"))
      (a/ok (.includes text "materialize")))))
