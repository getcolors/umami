(ns io.github.getcolors.umami.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.umami.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(defn fixture [& {:as overrides}]
  (merge (green-cli/read-state fixture-file
                               (str/replace (slurp fixture-file) "WORKDIR" ".colors")) overrides))

(deftest fixture-is-valid (is (= [] (validate/state-errors (fixture)))))

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
