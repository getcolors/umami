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
    (is (str/includes? json "192.0.2.10"))))

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
