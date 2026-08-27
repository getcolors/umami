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

(defn deletable-fixture
  "A fixture that passes real-delete preflight: guard lifted, secrets present."
  [& {:as overrides}]
  (merge (fixture :compute-prevent-destroy false
                  :do-token "t" :cloudflare-api-token "t"
                  :postgres-password "p" :umami-admin-password "p"
                  :app-secret-key "s"
                  :backup-r2-access-key-id "k"
                  :backup-r2-secret-access-key "s")
         overrides))

(deftest delete-fails-loudly-when-state-is-unreadable
  ;; Swallowing a failed state read is how a live teardown ended up pointing
  ;; the cleanup playbook at 192.0.2.10: stale backend credentials made
  ;; `tofu output` fail, nil was merged, and the inventory fell back to
  ;; TEST-NET. The failure must surface here, before any playbook runs.
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "Unauthorized" {})))]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete) {})]
      (is (= 1 (:green/exit r)))
      (is (str/includes? (:green/err r) "Unauthorized"))
      (is (str/includes? (:green/err r) "COLORS_PAR_IP")))))

(deftest delete-with-explicit-ip-skips-the-state-read
  ;; COLORS_PAR_IP is the operator's escape hatch when the state backend is
  ;; unreachable; it must not require the read it exists to replace.
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "must not be called" {})))]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete
                                                    :ip "203.0.113.7") {})]
      (is (= 0 (:green/exit r)))
      (is (= "203.0.113.7" (:ip r))))))

(deftest delete-with-empty-state-proceeds-without-an-address
  ;; State readable, no compute recorded: the instance is already gone, the
  ;; cleanup step skips itself, and the rest of the teardown still runs.
  (with-redefs [workflow/state-output (fn [_] nil)]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete) {})]
      (is (= 0 (:green/exit r)))
      (is (nil? (:ip r))))))

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
