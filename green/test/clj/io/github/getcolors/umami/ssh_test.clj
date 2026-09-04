(ns io.github.getcolors.umami.ssh-test
  "Conformance tests for the SSH Keypair Standard, as this package wires it.

  The matrix itself is ONCE's and tested there; these prove the delegation:
  that absence of `digitalocean-ssh-keys` selects keygen, that a build renders
  the placeholder path and never names `$HOME`, that opt-out passes through
  untouched, and that the create matrix, the preflight and the cleanup reach
  ONCE with this package's fixtures. Every test redirects `~/.ssh` into a
  temporary home: nothing here may touch the real one."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.umami.ssh :as ssh]
            [io.github.getcolors.umami.validate-test :refer [fixture keygen]]))

(defn- with-home
  "Run `f` with `~/.ssh` redirected into a fresh temporary home."
  [f]
  (let [home (str (fs/create-temp-dir {:prefix "umami-ssh"}))]
    (try
      (with-redefs [once-ssh/home-dir (constantly home)] (f home))
      (finally (fs/delete-tree home)))))

(defn- write! [path content]
  (io/make-parents path)
  (spit path content))

;;; ------------------------------------------------------------------ mode

(deftest build-renders-a-stable-placeholder-path
  ;; Goldens are committed, so a build must not name the operator's home.
  (with-home
    (fn [_]
      (let [opts (ssh/with-machine-key (assoc (keygen) :green/event :build))]
        (is (str/starts-with? (:ssh-public-key-path opts) ssh/build-placeholder-dir))
        ;; ONCE's table decides which desired-state key carries the machine
        ;; key, so a second provider would need no second branch here.
        (is (= (:ssh-public-key-path opts) (:digitalocean-ssh-keys opts)))
        (is (not (str/includes? (:ssh-private-key-path opts) (System/getProperty "user.home")))))
      (let [opts (ssh/with-machine-key (assoc (fixture) :green/event :build))]
        (is (= "58495393" (:digitalocean-ssh-keys opts)))
        (is (nil? (:ssh-public-key-path opts)))))))

(deftest a-dry-run-renders-the-placeholder-too
  (with-home
    (fn [_]
      (let [opts (ssh/with-machine-key (assoc (keygen) :green/event :create :green/dry-run true))]
        (is (str/starts-with? (:ssh-public-key-path opts) ssh/build-placeholder-dir))))))

(deftest real-events-render-the-real-path
  (with-home
    (fn [home]
      (let [opts (ssh/with-machine-key (assoc (keygen) :green/event :create))]
        (is (= (str (io/file home ".ssh" "umami-keygen-fixture")) (:ssh-private-key-path opts)))
        (is (= (str (io/file home ".ssh" "umami-keygen-fixture.pub")) (:ssh-public-key-path opts)))))))

(deftest opt-out-passes-through-untouched
  (with-home
    (fn [_]
      (doseq [event [:build :create :delete]]
        (let [opts (ssh/with-machine-key (assoc (fixture) :green/event event))]
          (is (= "58495393" (:digitalocean-ssh-keys opts)))
          (is (nil? (:ssh-public-key-path opts)) (str event))
          (is (nil? (:ssh-keygen opts)) (str event)))))))

(deftest identity-args-select-the-generated-key-only-in-keygen-mode
  ;; The acceptance step's ssh threads these: in keygen mode nothing
  ;; guarantees an agent holds the key.
  (with-home
    (fn [_]
      (let [opts (ssh/with-machine-key (assoc (keygen) :green/event :create))]
        (is (= ["-o" "IdentitiesOnly=yes" "-i" (:ssh-private-key-path opts)] (ssh/identity-args opts))))
      (is (= [] (ssh/identity-args (ssh/with-machine-key (assoc (fixture) :green/event :create))))))))

;;; -------------------------------------------------------- the create matrix

(deftest first-create-generates-the-keypair
  (with-home
    (fn [home]
      (let [opts (ssh/ensure-key! (assoc (keygen) :green/event :create) (constantly nil))
            prv (io/file home ".ssh" "umami-keygen-fixture")
            pub (io/file home ".ssh" "umami-keygen-fixture.pub")]
        (is (not (contains? opts :green/err)) (:green/err opts))
        (is (.exists prv))
        (is (.exists pub))
        (testing "ed25519, no passphrase, profile-named comment"
          (is (str/includes? (slurp pub) "ssh-ed25519"))
          (is (str/includes? (slurp pub) "umami-keygen-fixture managed by Colors")))
        (testing "600 on the private key, 700 on ~/.ssh"
          (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions prv))))
          (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions (io/file home ".ssh"))))))))))

(deftest a-key-without-state-is-never-overwritten
  (with-home
    (fn [home]
      (let [prv (str (io/file home ".ssh" "umami-keygen-fixture"))]
        (write! prv "irreplaceable")
        (write! (str prv ".pub") "ssh-ed25519 AAAA test")
        (let [opts (ssh/ensure-key! (assoc (keygen) :green/event :create) (constantly nil))]
          (is (= 1 (:green/exit opts)))
          (is (str/includes? (:green/err opts) "no compute state is readable"))
          (is (str/includes? (:green/err opts) "survives")
              "the message must make the human the authorization boundary")
          (is (= "irreplaceable" (slurp prv)) "the key on disk is left alone"))))))

(deftest state-without-a-key-is-an-error
  (with-home
    (fn [_]
      (let [opts (ssh/ensure-key! (assoc (keygen) :green/event :create)
                                  (constantly {:ip "192.0.2.10"}))]
        (is (= 1 (:green/exit opts)))
        (is (str/includes? (:green/err opts) "does not hold the machine key"))))))

(deftest opt-out-generates-nothing
  (with-home
    (fn [home]
      (let [r (ssh/ensure-key! (assoc (fixture) :green/event :create) (constantly nil))]
        (is (not (contains? r :green/err))))
      (is (empty? (fs/list-dir home)) "opt-out mode must not touch ~/.ssh"))))

;;; ------------------------------------------------------------- preflight

(deftest preflight-lists-keys-with-the-digitalocean-token
  ;; ONCE selects the REST API and the token by provider; this proves the
  ;; delegation hands DigitalOcean its own credential.
  (with-home
    (fn [_]
      (let [seen (atom [])]
        (with-redefs [once-ssh/fetch-account-keys (fn [provider token] (swap! seen conj [provider token]) [])]
          (ssh/preflight! (ssh/with-machine-key (assoc (keygen) :green/event :create
                                                       :do-token "do-secret" :vultr-api-key "wrong"))))
        (is (= [["digitalocean" "do-secret"]] @seen))))))

(deftest preflight-refuses-a-foreign-key-and-says-do-not-delete-it
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "umami-keygen-fixture.pub")) "ssh-ed25519 OURS comment")
      (with-redefs [once-ssh/fetch-account-keys
                    (fn [_ _] [{:id "abc" :name "umami-keygen-fixture" :public "ssh-ed25519 THEIRS"}])]
        (let [opts (ssh/preflight! (ssh/with-machine-key (assoc (keygen) :green/event :create)))]
          (is (= 1 (:green/exit opts)))
          (is (str/includes? (:green/err opts) "Do not delete it")))))))

(deftest preflight-is-skipped-in-opt-out-mode
  (with-home
    (fn [_]
      (with-redefs [once-ssh/fetch-account-keys (fn [_ _] (throw (ex-info "must not be called" {})))]
        (is (not (contains? (ssh/preflight! (assoc (fixture) :green/event :create)) :green/err)))))))

;;; --------------------------------------------------------------- cleanup

(deftest delete-removes-the-keypair
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "umami-keygen-fixture")) "private")
      (write! (str (io/file home ".ssh" "umami-keygen-fixture.pub")) "public")
      (ssh/cleanup-step (assoc (keygen) :green/event :delete :ssh-keygen true))
      (is (not (.exists (io/file home ".ssh" "umami-keygen-fixture"))))
      (is (not (.exists (io/file home ".ssh" "umami-keygen-fixture.pub"))))
      (is (.exists (io/file home ".ssh")) "~/.ssh itself is the operator's, never removed"))))

(deftest cleanup-is-inert-on-create-and-in-opt-out-mode
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "umami-keygen-fixture")) "private")
      (ssh/cleanup-step (assoc (keygen) :green/event :create :ssh-keygen true))
      (is (.exists (io/file home ".ssh" "umami-keygen-fixture")))
      (ssh/cleanup-step (assoc (fixture) :green/event :delete))
      (is (.exists (io/file home ".ssh" "umami-keygen-fixture"))))))
