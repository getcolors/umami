(ns io.github.getcolors.umami.workflow-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.umami.validate-test :refer [fixture keygen]]
            [io.github.getcolors.umami.workflow :as workflow]))

;; The compute state is read once per run, through `state-output`, on a real
;; create or delete. Every lifecycle test stubs it: nil is a readable state
;; holding no compute, a map is a recorded `params`, and a throw is a backend
;; that cannot be read.
(defn- start [opts state]
  (with-redefs [workflow/state-output (fn [_] state)]
    (workflow/start-step opts {})))

(defn- start-unreadable [opts]
  ;; The shape `green.tofu/outputs` throws: an ex-info carrying `:dir`. Only
  ;; that is an unreadable backend; anything else propagates as a defect.
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "tofu output failed: no backend" {:dir "x"})))]
    (workflow/start-step opts {})))

(def credentials
  {:do-token "d" :cloudflare-api-token "c"
   :postgres-password "p" :umami-admin-password "p" :app-secret-key "s"
   :backup-r2-access-key-id "k" :backup-r2-secret-access-key "s"})

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {})))))

(deftest build-and-dry-run-never-touch-ssh-or-state
  ;; The standard forbids reading, creating, or requiring anything under ~/.ssh
  ;; on a build or dry-run: they render from desired state alone. Nor do they
  ;; read the backend: a throwing state read proves nothing on these paths
  ;; reaches it.
  (doseq [opts [(assoc (keygen) :green/event :build)
                (assoc (keygen) :green/event :create :green/dry-run true)
                (assoc (keygen) :green/event :delete :green/dry-run true)]]
    (let [result (start-unreadable opts)]
      (is (= 0 (:green/exit result)))
      (is (str/starts-with? (str (:ssh-public-key-path result)) "/home/build-placeholder")
          "a build must not name the operator's home directory"))))

(deftest real-create-requires-credentials
  (let [r (start (assoc (fixture) :provider-backend "r2" :green/event :create) nil)]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? (:green/err r) "COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID"))))

(deftest delete-is-protected
  (let [r (start (assoc (fixture) :green/event :delete) nil)]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COMPUTE_PREVENT_DESTROY"))))

;; --- provider switching is a rebuild, never an apply

(deftest a-provider-switch-is-refused-on-create-and-delete
  ;; The registry has one entry, so the recorded provider can only differ from
  ;; the selected one when the state was written by something else -- and that
  ;; is exactly the state this package must not render a destroy against.
  (doseq [event [:create :delete]]
    (testing (str "DigitalOcean selected, Vultr recorded, on " (name event))
      (let [r (start (assoc (fixture) :green/event event :compute-prevent-destroy false)
                     {:provider "vultr" :ip "203.0.113.9"})]
        (is (= 2 (:green/exit r)))
        (is (str/includes? (:green/err r)
                           "state holds a vultr machine; set provider-compute back to vultr and delete first"))
        ;; The validator order is the thing under test: the actionable error,
        ;; not a missing token for the provider that was just selected.
        (is (not (str/includes? (:green/err r) "required credential is not set")))
        (is (not (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN")))))))

(deftest legacy-state-is-accepted-on-digitalocean
  ;; A state recorded before this package wrote params.provider -- what
  ;; umami-digitalocean's R2 state may still hold -- is a DigitalOcean one, and
  ;; the default says so: accepted, and the run proceeds to the credentials.
  (doseq [event [:create :delete]]
    (let [r (start (assoc (fixture) :green/event event :compute-prevent-destroy false)
                   {:ip "203.0.113.9"})]
      (is (= 2 (:green/exit r)) (name event))
      (is (not (str/includes? (:green/err r) "state holds")) (name event))
      (is (str/includes? (:green/err r) "required credential is not set") (name event)))))

(deftest a-matching-provider-passes-to-the-credentials
  (let [r (start (assoc (fixture) :green/event :create) {:provider "digitalocean" :ip "203.0.113.9"})]
    (is (= 2 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "state holds")))
    (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))))

(deftest an-unreadable-backend-counts-as-no-state-on-create
  ;; A fresh clone has no readable state and must still be able to create.
  (let [r (start-unreadable (assoc (fixture) :green/event :create))]
    (is (= 2 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "could not read")))
    (is (not (str/includes? (:green/err r) "state holds")))
    (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))))

(deftest a-real-create-on-a-fresh-work-directory-reports-the-credentials-not-a-crash
  ;; No state stub: the real `state-output` runs against a work directory
  ;; that holds no stage yet, as a fresh clone's does. Green's SDK shells out
  ;; to tofu in a directory that does not exist and reports that launch
  ;; failure itself as its `tofu output failed:` step error, which ONCE's
  ;; `read-state` counts as an unreadable state, so the create reports its
  ;; credentials instead of crashing.
  (let [work (str (fs/create-temp-dir {:prefix "umami-fresh"}))]
    (try
      (let [r (workflow/start-step (assoc (fixture) :workdir work :green/event :create) {})]
        (is (= 2 (:green/exit r)))
        (is (str/includes? (str (:green/err r)) "COLORS_PAR_DO_TOKEN"))
        (is (not (str/includes? (str (:green/err r)) "could not read"))))
      (finally (fs/delete-tree work)))))

(defn deletable-fixture
  "A fixture that passes real-delete preflight: guard lifted, secrets present."
  [& {:as overrides}]
  (merge (fixture :compute-prevent-destroy false) credentials overrides))

(deftest delete-fails-loudly-when-state-is-unreadable
  ;; Swallowing a failed state read is how a live teardown ended up pointing
  ;; the cleanup playbook at 192.0.2.10: stale backend credentials made
  ;; `tofu output` fail, nil was merged, and the inventory fell back to
  ;; TEST-NET. The failure must surface here, before any playbook runs, with
  ;; the standard's wording.
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "Unauthorized" {:dir "x"})))]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete) {})]
      (is (= 1 (:green/exit r)))
      (is (str/includes? (:green/err r) "could not read the infrastructure state for the delete cleanup"))
      (is (str/includes? (:green/err r) "Unauthorized")))))

(deftest delete-with-explicit-ip-overrides-the-adopted-address-after-the-read
  ;; COLORS_PAR_IP replaces a stale recorded address; it never skips the read
  ;; or the provider guard. On a readable state the override wins over the
  ;; recorded address; an unreadable backend still fails closed with it set.
  (let [r (start (deletable-fixture :green/event :delete :ip "203.0.113.7")
                 {:provider "digitalocean" :ip "198.51.100.1" :user "root"})]
    (is (= 0 (:green/exit r)))
    (is (= "203.0.113.7" (:ip r))))
  (let [r (start-unreadable (deletable-fixture :green/event :delete :ip "203.0.113.7"))]
    (is (= 1 (:green/exit r)))
    (is (str/includes? (:green/err r) "could not read the infrastructure state for the delete cleanup"))))

(deftest delete-with-empty-state-proceeds-without-an-address
  ;; State readable, no compute recorded: the instance is already gone, the
  ;; cleanup step skips itself, and the rest of the teardown still runs.
  (let [r (start (deletable-fixture :green/event :delete) nil)]
    (is (= 0 (:green/exit r)))
    (is (nil? (:ip r)))))

(deftest a-real-delete-adopts-the-recorded-address
  (let [r (start (deletable-fixture :green/event :delete)
                 {:provider "digitalocean" :ip "203.0.113.9" :user "root"})]
    (is (= 0 (:green/exit r)))
    (is (= "203.0.113.9" (:ip r)))))

(deftest graph-orders-private-stack
  (is (= [:umami/infrastructure]
         (vec (rest (workflow/wire-fn :umami/start {:green/event :create})))))
  (is (= [:umami/ssh-config]
         (vec (rest (workflow/wire-fn :umami/infrastructure {:green/event :create})))))
  (is (= [:umami/dns]
         (vec (rest (workflow/wire-fn :umami/ssh-config {:green/event :create})))))
  (is (= [:umami/ansible]
         (vec (rest (workflow/wire-fn :umami/dns {:green/event :create})))))
  (is (= [:umami/acceptance]
         (vec (rest (workflow/wire-fn :umami/ansible {:green/event :create})))))
  (is (= [:umami/ansible]
         (vec (rest (workflow/wire-fn :umami/start {:green/event :delete}))))))

(deftest delete-removes-the-config-block-before-the-destroy
  ;; The opposite of the keypair below: a block that outlives its host is
  ;; stale but harmless, so removing it early costs nothing.
  (is (= [:umami/dns]
         (vec (rest (workflow/wire-fn :umami/ansible {:green/event :delete})))))
  (is (= [:umami/ssh-config]
         (vec (rest (workflow/wire-fn :umami/dns {:green/event :delete})))))
  (is (= [:umami/infrastructure]
         (vec (rest (workflow/wire-fn :umami/ssh-config {:green/event :delete})))))
  (is (some #{:umami/ssh-config} workflow/side-effecting) "a dry-run never writes ~/.ssh/config"))

(deftest delete-removes-the-key-after-the-compute-destroy
  ;; The ordering is what makes "key present <=> deployment exists" hold: a
  ;; failed destroy never reaches the cleanup step, and correctly leaves the
  ;; key that is still the only credential to whatever survived.
  (is (= [:umami/ssh-cleanup]
         (vec (rest (workflow/wire-fn :umami/infrastructure {:green/event :delete})))))
  (is (empty? (rest (workflow/wire-fn :umami/ssh-cleanup {:green/event :delete}))))
  (is (some #{:umami/ssh-cleanup} workflow/side-effecting) "a dry-run delete touches no key"))

(deftest proxying-default-lives-here-not-only-in-dns-data
  ;; This map seeds :cloudflare-proxied, so tools/dns-data always sees the key
  ;; supplied and its own fallback never runs on the real path. Flipping only
  ;; the fallback would change nothing and move no golden -- assert the value
  ;; that actually decides it.
  (is (true? (:cloudflare-proxied workflow/defaults))))
