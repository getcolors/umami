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
