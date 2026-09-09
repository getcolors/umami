(ns io.github.getcolors.umami.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.cli :as green-cli]
            [io.github.getcolors.umami.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def keygen-file "test/fixtures/keygen.yml")
(defn read-fixture [file overrides]
  (merge (green-cli/read-state file (str/replace (slurp file) "WORKDIR" ".colors"))
         overrides))
(defn fixture
  "DigitalOcean, opt-out mode: an explicit key id and a name equal to the
  profile -- the shape every umami deployment has had."
  [& {:as overrides}] (read-fixture fixture-file overrides))
(defn keygen
  "DigitalOcean, keygen mode: no `digitalocean-ssh-keys`, no `digitalocean-name`."
  [& {:as overrides}] (read-fixture keygen-file overrides))

(deftest fixture-is-valid (is (= [] (validate/state-errors (fixture)))))
(deftest keygen-fixture-is-valid (is (= [] (validate/state-errors (keygen)))))

;; --- the spec handed to ONCE

(deftest unselected-provider-keys-are-ignored-not-refused
  ;; One colors.yml may carry another provider's block; only the selected
  ;; provider's keys are read. `digitalocean-https-sources`, which older
  ;; desired state carries, is likewise accepted and ignored.
  (is (= [] (validate/state-errors (fixture :vultr-plan "vc2-2c-4gb" :vultr-os-id "ubuntu"))))
  (is (= [] (validate/state-errors (fixture :digitalocean-https-sources ["0.0.0.0/0"]))))
  (is (some #(str/includes? % "compute deployment")
            (validate/state-errors (fixture :digitalocean-size nil)))))

(deftest absent-machine-key-selects-keygen
  (is (validate/keygen? (keygen)))
  (is (not (validate/keygen? (fixture))))
  (is (validate/keygen? (fixture :digitalocean-ssh-keys nil)) "absence, not a flag, is the switch"))

(deftest reports-all-errors
  (let [errors (validate/state-errors
                (fixture :umami-host "bad" :caddy-image "floating"
                         :backup-retention-days -1
                         :provider-dns "other" :digitalocean-vpc-uuid "forbidden"))]
    (is (<= 5 (count errors)))
    (doseq [part ["host" "image" "retention" "provider-dns" "compute deployment"]]
      (is (some #(str/includes? % part) errors)))))

(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "other"})))
  (is (nil? (validate/env-errors {}))))

(deftest accepts-the-alternate-app-secret-name
  (let [errors (str/join "\n" (validate/secret-errors
                               (assoc (fixture) :umami-app-secret "alternate")))]
    (is (not (str/includes? errors "COLORS_PAR_APP_SECRET_KEY")))))

(deftest compose-template-carries-no-default-credential
  (let [compose (slurp "src/resources/io/github/getcolors/umami/tools/ansible/compose.yml")]
    (is (not (str/includes? compose "default('umami'")))
    (is (not (re-find #"(?i)secret_hash_key" compose)))
    ;; The password reaches Umami inside a URL, so it must be percent-encoded.
    (is (str/includes? compose "urlencode | replace('/', '%2F')"))))
