(ns io.github.getcolors.umami.tools
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.cli :as green-cli]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.umami.validate :as validate]))

(def infrastructure-tool "umami-infrastructure")
(def dns-tool "umami-dns")
(def ansible-tool "umami-ansible")
(def root "io.github.getcolors.umami.tools")
(def template-opts sc/preserve-jinja-delimiters)
(defn tool-dir [opts tool] (green-cli/stage-dir opts tool {:default-profile "umami"}))
(defn template [path file] (keyword (str root "." path) file))
(defn spec [source target data] {:template source :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(defn cidrs [opts k]
  (let [v (get opts k) xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

(defn credential-env [opts & slots]
  (not-empty
   (into {} (keep (fn [[k env-var]]
                    (when-let [v (not-empty (str (get opts k)))] [env-var v])))
         (apply merge (map #(validate/tofu-env opts %) (conj (vec slots) :provider-backend))))))
(defn backend-credential-env [opts] (credential-env opts))

(defn fallback-params [opts]
  {:ip "192.0.2.10" :user "root" :sudoer "root" :name (:profile opts)})
(defn output-params [result]
  (some-> (get-in result [:tofu/outputs :params]) walk/keywordize-keys))

(defn infrastructure-data [opts]
  (assoc opts
         :ssh-sources-hcl (tofu/hcl-list (cidrs opts :digitalocean-ssh-sources))
         :http-sources-hcl (tofu/hcl-list (cidrs opts :digitalocean-http-sources))))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        specs [(spec (template "infrastructure" "main.tf") (str dir "/main.tf")
                     (infrastructure-data opts))]
        result (tofu/tofu-with-spec opts specs
                                    {:dir dir :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts)) (merge result (fallback-params opts))
      (= :delete (:green/event opts)) result
      :else (merge result (fallback-params opts) (output-params result)))))

(defn zone-id [zone] (format "${data.cloudflare_zone.zone.id}" zone))

(defn dns-data [opts]
  (let [host (str (:umami-host opts))
        zone (or (:cloudflare-zone opts)
                 (let [parts (str/split host #"\.")]
                   (if (> (count parts) 2)
                     (str/join "." (rest parts))
                     host)))]
    (assoc opts
           :ip (or (:ip opts) (:ip (fallback-params opts)))
           :cloudflare-zone zone
           :cloudflare-proxied (if (some? (:cloudflare-proxied opts))
                                 (:cloudflare-proxied opts)
                                 false))))

(defn dns-json [opts]
  (tofu/constructs-json
   [(tofu/construct :resource :cloudflare_dns_record :umami
                    {:zone_id (zone-id (:cloudflare-zone opts))
                     :name (:umami-host opts) :content (:ip opts) :type "A"
                     :proxied (boolean (:cloudflare-proxied opts)) :ttl 1})]))

(defn dns-step [opts]
  (let [dir (tool-dir opts dns-tool)
        data (dns-data opts)
        specs [(spec (template "dns" "main.tf") (str dir "/main.tf") data)
               (raw-spec (str dir "/record.tf.json") (dns-json data))]]
    (tofu/tofu-with-spec opts specs {:dir dir :env (credential-env opts :provider-dns)})))

(defn inventory [opts]
  (json/generate-string
   {:all {:children {:umami {:hosts {(:profile opts)
                                     {:ansible_host (or (:ip opts) "192.0.2.10")
                                      :ansible_user "root"}}}}}}
   {:pretty true}))

(defn ansible-data [opts]
  (assoc opts
         :ip (or (:ip opts) "192.0.2.10")
         :umami-image (or (:umami-image opts)
                          (str "ghcr.io/umami-software/umami:postgresql-"
                               (or (:umami-version opts) "v2.14.0")))
         :postgres-image (or (:postgres-image opts)
                             (str "postgres:" (or (:postgres-version opts) "17") "-alpine"))
         :postgres-db (or (:postgres-database opts) (:postgres-db opts) "umami")
         :postgres-user (or (:postgres-user opts) "umami")
         :postgres-data-dir (or (:postgres-data-dir opts) "/var/lib/umami/postgres")
         :umami-port (or (:umami-port opts) 3000)
         :backup-dir (or (:backup-dir opts) (:umami-backup-dir opts) "/var/backups/umami")
         :backup-r2-bucket (or (:backup-r2-bucket opts) (:umami-backup-r2-bucket opts) "umami-backup")
         :backup-r2-endpoint (or (:backup-r2-endpoint opts) (:umami-backup-r2-endpoint opts))
         :backup-oncalendar (or (:backup-oncalendar opts) (:umami-backup-oncalendar opts) "*-*-* 03:00:00")
         :backup-retention-days (or (:backup-retention-days opts) (:umami-backup-retention-days opts) 7)))

(defn ansible-specs [opts]
  (let [dir (tool-dir opts ansible-tool) data (ansible-data opts)]
    [(spec (template "ansible" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible" "main.yml") (str dir "/main.yml") data)
     (spec (template "ansible" "cleanup.yml") (str dir "/cleanup.yml") data)
     (spec (template "ansible" "compose.yml") (str dir "/compose.yml") data)
     (spec (template "ansible" "Caddyfile") (str dir "/Caddyfile") data)
     (spec (template "ansible" "backup") (str dir "/backup") data)
     (raw-spec (str dir "/inventory.json") (inventory data))]))

(defn ansible-step [opts]
  (let [dir (tool-dir opts ansible-tool)]
    (ansible/ansible-with-spec opts
      {:dir dir :inventory "inventory.json"
       :playbooks {:create "main.yml" :delete "cleanup.yml"}
       :host-key-checking false}
      (ansible-specs opts))))

(defn run-json [args timeout]
  (let [r (process/run-with-timeout args {} timeout)]
    (if (zero? (:exit r))
      [(try (json/parse-string (:out r) true) (catch Exception _ nil)) nil]
      [nil (str (:err r) (:out r))])))

(defn wait-health [url attempts]
  (loop [n attempts]
    (let [r (process/run-with-timeout ["curl" "-fsS" (str url "/api/heartbeat")] {} 10000)]
      (cond (zero? (:exit r)) true
            (pos? n) (do (Thread/sleep 5000) (recur (dec n)))
            :else false))))

(defn send-synthetic-event [base host]
  (let [payload (json/generate-string
                 {:type "event"
                  :payload {:website "00000000-0000-0000-0000-000000000000"
                            :hostname host
                            :url "/benchmark"
                            :name "synthetic-test-event"}})
        r (process/run-with-timeout
           ["curl" "-fsS" "-X" "POST"
            "-H" "content-type: application/json"
            "-H" "User-Agent: Mozilla/5.0 (Benchmark Acceptance)"
            "--data" payload
            (str base "/api/send")] {} 15000)]
    (zero? (:exit r))))

(defn run-backup-check [ip]
  (let [r (process/run-with-timeout
           ["ssh" "-o" "StrictHostKeyChecking=no" "-o" "ConnectTimeout=10"
            (str "root@" ip) "systemctl start umami-backup.service && systemctl is-active umami-backup.timer"]
           {} 30000)]
    (zero? (:exit r))))

(defn acceptance-step [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [base (str "https://" (:umami-host opts))]
      (cond
        (not (wait-health base 60))
        (assoc opts :green/exit 1 :green/err "HTTPS heartbeat endpoint did not become ready")

        (not (send-synthetic-event base (:umami-host opts)))
        (assoc opts :green/exit 1 :green/err "Synthetic event ingestion via /api/send failed")

        (not (run-backup-check (:ip opts)))
        (assoc opts :green/exit 1 :green/err "Backup service check failed on droplet")

        :else
        (assoc opts :green/exit 0 :umami/acceptance {:health "ok" :event "ingested" :backup "verified"})))))
