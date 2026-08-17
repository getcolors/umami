(ns io.github.getcolors.umami.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.umami.tools :as tools]
            [io.github.getcolors.umami.validate-test :refer [fixture]]))

(deftest infrastructure-discovers-default-vpc
  (let [data (tools/infrastructure-data (fixture))]
    (is (= ["0.0.0.0/0" "::/0"] (tools/cidrs data :digitalocean-http-sources)))))

(deftest dns-computes-zone-and-record
  (let [json (tools/dns-json (tools/dns-data (assoc (fixture) :ip "192.0.2.10")))]
    (is (str/includes? json "umami.example.com"))
    (is (str/includes? json "192.0.2.10"))
    (is (str/includes? json "\"proxied\" : true"))))

(deftest dns-proxying-defaults-on-and-can-be-declined
  (is (true? (:cloudflare-proxied (tools/dns-data (fixture)))))
  (is (str/includes? (tools/dns-json
                      (tools/dns-data (assoc (fixture) :ip "192.0.2.10"
                                             :cloudflare-proxied false)))
                     "\"proxied\" : false")))

(deftest inventory-keeps-one-target
  (let [inventory (tools/inventory (assoc (fixture) :ip "192.0.2.10"))]
    (is (str/includes? inventory "192.0.2.10"))
    (is (str/includes? inventory "umami-fixture"))))

(deftest ingestion-is-judged-by-the-stored-row-not-the-status
  (is (= :ingested (tools/ingestion-verdict "200" 4 5)))
  ;; The failure this gate exists for: the endpoint accepts and nothing lands.
  (is (= :dropped (tools/ingestion-verdict "200" 4 4)))
  (is (= :dropped (tools/ingestion-verdict "202" 4 nil)))
  (is (= :rejected (tools/ingestion-verdict "400" 4 4)))
  (is (= :unreachable (tools/ingestion-verdict nil 4 4))))

(deftest backup-must-be-fresh-and-non-empty
  (let [since (java.time.Instant/parse "2026-08-17T03:00:00Z")
        entry (fn [size mod-time] {:Size size :ModTime mod-time})]
    (is (tools/fresh-backup? [(entry 1024 "2026-08-17T03:00:05Z")] since))
    (is (tools/fresh-backup? [(entry 1024 "2026-08-17T05:00:05+02:00")] since))
    ;; A stale object from an earlier run must not certify today's drill.
    (is (not (tools/fresh-backup? [(entry 1024 "2026-08-16T03:00:05Z")] since)))
    ;; An empty upload is not a backup.
    (is (not (tools/fresh-backup? [(entry 0 "2026-08-17T03:00:05Z")] since)))
    (is (not (tools/fresh-backup? [] since)))
    (is (not (tools/fresh-backup? nil since)))))

(def backup-script
  (delay (slurp "src/resources/io/github/getcolors/umami/tools/ansible/backup")))

(deftest backup-proves-it-restores-and-prunes-the-bucket
  ;; An archive that exists is not an archive that restores, and pruning only
  ;; the local disk leaves R2 growing without bound.
  (is (str/includes? @backup-script "CREATE DATABASE"))
  (is (str/includes? @backup-script "information_schema.tables"))
  (is (str/includes? @backup-script "rclone delete --min-age"))
  ;; The restore must happen before the upload, so a bad dump never lands.
  (let [restore (str/index-of @backup-script "restore check restored no tables")
        upload (str/index-of @backup-script "rclone copyto")]
    (is (< restore upload))))

(deftest acceptance-provisions-its-own-website
  ;; With no website the step reports :not-configured and sends nothing, so the
  ;; synthetic request is never exercised — exactly how the sibling package
  ;; carried a payload its API had always rejected.
  (let [src (slurp "src/clj/io/github/getcolors/umami/tools.clj")]
    (is (str/includes? src "ensure-acceptance-website"))
    (is (str/includes? src "umami-acceptance-website-domain"))
    ;; Never the operator's own website.
    (is (not (str/includes? src "select website_id from website limit 1")))
    ;; Idempotent, and the id must look like one.
    (is (str/includes? src "where not exists"))
    (is (str/includes? src "[0-9a-f-]{36}"))))

(deftest a-missing-compute-output-fails-loudly
  ;; The documentation address belongs to build and dry-run. Merging it into a
  ;; real converge would point Ansible at TEST-NET instead of failing.
  (is (= "1.2.3.4" (:ip (tools/resolved-compute {} {:ip "192.0.2.10"} {:ip "1.2.3.4"}))))
  (is (= 1 (:green/exit (tools/resolved-compute {} {:ip "192.0.2.10"} nil))))
  (is (= 1 (:green/exit (tools/resolved-compute {} {:ip "192.0.2.10"} {}))))
  (is (nil? (:green/exit (tools/resolved-compute {} {:ip "192.0.2.10"} {:ip "5.6.7.8"})))))
