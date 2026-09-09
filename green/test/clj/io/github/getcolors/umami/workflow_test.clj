(ns io.github.getcolors.umami.workflow-test
 (:require [clojure.test :refer [deftest is]]
 [io.github.getcolors.umami.workflow :as workflow]
 [io.github.getcolors.umami.compute :as compute]
 [io.github.getcolors.umami.validate-test :refer [keygen fixture]]))
(deftest offline-start
 (doseq [f [keygen fixture]]
  (is (= 0 (:green/exit (workflow/start-step (assoc (f) :green/event :build) {}))))))
(deftest singleton-library-contract
 (is (= [{:role nil :count 1}] compute/topology))
 (is (= ["umami-keygen-fixture/umami-infrastructure.tfstate"] (:legacy_state_keys (compute/requirements (keygen))))))
(deftest errors-and-observed-nodes
 (is (= "legacy compute state requires migration" (:green/err (compute/attach (keygen) {:status "error" :errors ["legacy compute state requires migration"]}))))
 (is (= "ubuntu" (:user (compute/attach (keygen) {:status "present" :cluster {:nodes [{:ip "203.0.113.7" :user "ubuntu"}]}}))))
 (is (:umami/already-destroyed (compute/attach (keygen) {:status "destroyed"}))))
