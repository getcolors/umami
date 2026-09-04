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

(deftest the-spec-carries-this-packages-registry-sources-and-default
  ;; The operations are ONCE's; this is the data they run over. A colour
  ;; whose registry, sources or default drifts fails here, in that colour.
  (is (= #{"digitalocean"} (set (keys (:registry validate/spec)))))
  (is (= validate/compute-providers (:registry validate/spec)))
  (is (= {:required [:digitalocean-region :digitalocean-size :digitalocean-image
                     :digitalocean-ssh-sources :digitalocean-http-sources]
          :secrets [:do-token]
          :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}
         (get-in validate/spec [:registry "digitalocean"])))
  (is (= {:non-empty ["ssh-sources"] :may-be-empty ["http-sources"]} (:sources validate/spec)))
  ;; DigitalOcean: the default is what a legacy state without params.provider
  ;; is, and every state this package ever wrote is a DigitalOcean one.
  (is (= "digitalocean" (:default validate/spec)))
  (is (= validate/default-compute-provider (:default validate/spec)))
  (is (not (contains? validate/spec :name-rules)) "the name rules are ONCE's"))

;; --- the compute-provider registry

(deftest compute-provider-must-be-one-the-package-has-a-template-for
  ;; The registry is the only list; a provider accepted here with no template
  ;; directory would fail at render time instead of at validation.
  (let [errors (validate/state-errors (fixture :provider-compute "vultr"))]
    (is (some #{":provider-compute must be one of digitalocean"} errors))))

(deftest name-and-machine-key-are-never-required
  ;; `digitalocean-name` is an optional override of the profile and
  ;; `digitalocean-ssh-keys` is meaningful by its absence, so neither may be in
  ;; the registry's required list -- a required machine key would make keygen
  ;; mode unreachable.
  (doseq [entry (vals validate/compute-providers) k (:required entry)]
    (is (not (str/ends-with? (name k) "-name")) (str k))
    (is (not (str/ends-with? (name k) "-ssh-keys")) (str k)))
  (is (= [] (validate/state-errors (fixture :digitalocean-name nil :digitalocean-ssh-keys nil)))))

(deftest unselected-provider-keys-are-ignored-not-refused
  ;; One colors.yml may carry another provider's block; only the selected
  ;; provider's keys are read. `digitalocean-https-sources`, which older
  ;; desired state carries, is likewise accepted and ignored.
  (is (= [] (validate/state-errors (fixture :vultr-plan "vc2-2c-4gb" :vultr-os-id "ubuntu"))))
  (is (= [] (validate/state-errors (fixture :digitalocean-https-sources ["0.0.0.0/0"]))))
  (is (some #(str/includes? % "digitalocean-size")
            (validate/state-errors (fixture :digitalocean-size nil)))))

(deftest absent-machine-key-selects-keygen
  (is (validate/keygen? (keygen)))
  (is (not (validate/keygen? (fixture))))
  (is (validate/keygen? (fixture :digitalocean-ssh-keys nil)) "absence, not a flag, is the switch"))

(deftest compute-name-falls-back-to-the-profile
  (is (= "umami-fixture" (validate/compute-name (fixture))))
  (is (= "umami-keygen-fixture" (validate/compute-name (keygen))))
  (is (= "custom" (validate/compute-name (fixture :digitalocean-name "custom"))))
  (is (= :digitalocean-ssh-sources (validate/compute-key (fixture) "ssh-sources"))))

(deftest compute-credentials-follow-the-provider
  (is (= {:do-token "DIGITALOCEAN_TOKEN"} (validate/tofu-env (fixture) :provider-compute)))
  (is (= {} (validate/tofu-env (fixture :provider-compute "vultr") :provider-compute))))

;; --- the network contract, wired through state-errors with ONCE's messages

(deftest ssh-sources-must-not-be-empty
  ;; A machine nobody can reach is not a deployment; an empty HTTP list is
  ;; simply no public HTTP.
  (is (some #{":digitalocean-ssh-sources must list at least one CIDR"}
            (validate/state-errors (fixture :digitalocean-ssh-sources []))))
  (is (= [] (validate/state-errors (fixture :digitalocean-http-sources [])))))

(deftest malformed-sources-are-refused-before-any-provider-call
  (is (some #{":digitalocean-http-sources entry \"203.0.113.0\" is not an IPv4 or IPv6 CIDR"}
            (validate/state-errors (fixture :digitalocean-http-sources ["203.0.113.0"]))))
  (is (some #{":digitalocean-ssh-sources entry \"nope\" is not an IPv4 or IPv6 CIDR"}
            (validate/state-errors (fixture :digitalocean-ssh-sources ["0.0.0.0/0" "nope"]))))
  (is (= [] (validate/state-errors (fixture :digitalocean-ssh-sources ["2001:db8::/32" "203.0.113.4/32"])))))

;; --- provider checks run only for the selected provider

(deftest provider-checks-are-scoped-to-the-selected-provider
  (testing "DigitalOcean's VPC keys are refused on DigitalOcean"
    (is (some #(str/includes? % "must be absent")
              (validate/state-errors (fixture :digitalocean-vpc-cidr "10.0.0.0/16"))))
    (is (some #(str/includes? % "vpc-uuid")
              (validate/state-errors (fixture :digitalocean-vpc-uuid "forbidden")))))
  (testing "the resolved droplet name is held to DigitalOcean's rules"
    (is (some #(str/includes? % "digitalocean-name must be a hostname-like name")
              (validate/state-errors (fixture :digitalocean-name "Not Valid"))))))

(deftest reports-all-errors
  (let [errors (validate/state-errors
                (fixture :umami-host "bad" :caddy-image "floating"
                         :backup-retention-days -1
                         :provider-dns "other" :digitalocean-vpc-uuid "forbidden"))]
    (is (<= 5 (count errors)))
    (doseq [part ["host" "image" "retention" "provider-dns" "vpc-uuid"]]
      (is (some #(str/includes? % part) errors)))))

(deftest forbids-vpc-configuration
  (is (some #(str/includes? % "must be absent")
            (validate/state-errors (fixture :digitalocean-vpc-cidr "10.0.0.0/16")))))

(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "other"})))
  (is (nil? (validate/env-errors {}))))

(deftest names-all-package-secrets
  (let [errors (str/join "\n" (validate/secret-errors (assoc (fixture) :provider-backend "r2")))]
    (doseq [name ["COLORS_PAR_DO_TOKEN" "COLORS_PAR_CLOUDFLARE_API_TOKEN"
                  "COLORS_PAR_R2_ACCESS_KEY_ID" "COLORS_PAR_R2_SECRET_ACCESS_KEY"
                  "COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID"
                  "COLORS_PAR_BACKUP_R2_SECRET_ACCESS_KEY"
                  "COLORS_PAR_POSTGRES_PASSWORD" "COLORS_PAR_APP_SECRET_KEY"
                  "COLORS_PAR_UMAMI_ADMIN_PASSWORD"]]
      (is (str/includes? errors name)))))

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
