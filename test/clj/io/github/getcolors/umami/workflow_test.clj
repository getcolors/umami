(ns io.github.getcolors.umami.workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.umami.validate-test :refer [fixture]]
            [io.github.getcolors.umami.workflow :as workflow]))

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {})))))

(deftest real-create-requires-credentials
  (let [r (workflow/start-step (assoc (fixture) :provider-backend "r2" :green/event :create) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? (:green/err r) "COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID"))))

(deftest delete-is-protected
  (let [r (workflow/start-step (assoc (fixture) :green/event :delete) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COMPUTE_PREVENT_DESTROY"))))

(deftest graph-orders-private-stack
  (is (= [:umami/infrastructure]
         (vec (rest (workflow/wire-fn :umami/start {:green/event :create})))))
  (is (= [:umami/dns]
         (vec (rest (workflow/wire-fn :umami/infrastructure {:green/event :create})))))
  (is (= [:umami/ansible]
         (vec (rest (workflow/wire-fn :umami/start {:green/event :delete}))))))

(deftest proxying-default-lives-here-not-only-in-dns-data
  ;; This map seeds :cloudflare-proxied, so tools/dns-data always sees the key
  ;; supplied and its own fallback never runs on the real path. Flipping only
  ;; the fallback would change nothing and move no golden -- assert the value
  ;; that actually decides it.
  (is (true? (:cloudflare-proxied workflow/defaults))))
