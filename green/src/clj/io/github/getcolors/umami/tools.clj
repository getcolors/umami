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

(defn resolved-compute
  "Refuse to hand 192.0.2.10 to Ansible. That is the documentation address the
   credential-free build and dry-run paths render with; on a real converge a
   missing compute output must fail loudly rather than quietly point the whole
   playbook at TEST-NET."
  [result fallback outputs]
  (if (:ip outputs)
    (merge result fallback outputs)
    (assoc result :green/exit 1
           :green/err (str "compute produced no ip output; refusing to converge "
                           "against the documentation address"))))

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
      :else (resolved-compute result (fallback-params opts) (output-params result)))))

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
           ;; Kept in step with the workflow defaults, which seed this key and
           ;; therefore decide it on the real path -- this fallback only runs
           ;; when dns-data is called with bare opts, as the tests do.
           :cloudflare-proxied (if (some? (:cloudflare-proxied opts))
                                 (:cloudflare-proxied opts)
                                 true))))

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
    (if (and (= :delete (:green/event opts)) (not (:ip opts)))
      ;; No compute in state: there is no host to clean up, and the rendered
      ;; inventory would fall back to 192.0.2.10. Remove the rendered tree the
      ;; way a completed cleanup would and let the teardown continue.
      (assoc (sc/scaffold opts (ansible-specs opts))
             :green/exit 0 :umami/cleanup :skipped-no-compute)
      (ansible/ansible-with-spec opts
        {:dir dir :inventory "inventory.json"
         :playbooks {:create "main.yml" :delete "cleanup.yml"}
         :host-key-checking false}
        (ansible-specs opts)))))

;; --- Acceptance --------------------------------------------------------------
;;
;; Every claim this step reports must be one it checked. TLS is verified (never
;; `curl -k`), an ingested event is read back out of PostgreSQL rather than
;; inferred from a status code, and the backup drill is confirmed by a fresh
;; object in R2 rather than by systemd reporting that it started something.

(defn http-status [args]
  (let [r (process/run-with-timeout
           (into ["curl" "-sS" "-o" "/dev/null" "-w" "%{http_code}"] args) {} 20000)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn ssh-out [ip command timeout]
  (let [r (process/run-with-timeout
           ["ssh" "-o" "StrictHostKeyChecking=no" "-o" "ConnectTimeout=10"
            (str "root@" ip) command] {} timeout)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn sql [opts ip query]
  (not-empty
   (str (ssh-out ip (str "cd /opt/umami && docker compose exec -T postgres psql -U "
                         (:postgres-user opts) " -d " (:postgres-db opts)
                         " -tAc '" query "'")
                 30000))))

(defn event-count [opts ip]
  (some-> (sql opts ip "select count(*) from website_event") parse-long))

(def acceptance-website-id "00000000-c010-4000-8000-000000000001")

(defn ensure-acceptance-website
  "A dedicated throwaway website, created on demand. Without one the step
   reports :not-configured and sends nothing, so the synthetic request is never
   exercised -- which is how the sibling Rybbit package carried a payload the
   API had always rejected. Sending to a real website instead would write a
   test pageview into the operator's analytics on every converge.

   Literals are dollar-quoted because the query travels inside single quotes in
   a remote shell, and psql prints the INSERT tag before the SELECT result, so
   the id comes off the last line."
  [opts ip]
  (let [domain (or (not-empty (str (:umami-acceptance-website-domain opts)))
                   "colors-acceptance.invalid")
        owner "(select user_id from \"user\" limit 1)"]
    (some->> (sql opts ip
                  (str "insert into website (website_id, name, domain, created_by, user_id) "
                       "select $$" acceptance-website-id "$$::uuid, $$colors-acceptance$$, "
                       "$$" domain "$$, " owner ", " owner " "
                       "where not exists (select 1 from website "
                       "where website_id = $$" acceptance-website-id "$$::uuid); "
                       "select website_id from website "
                       "where website_id = $$" acceptance-website-id "$$::uuid"))
             str/split-lines
             last
             str/trim
             (re-matches #"[0-9a-f-]{36}"))))

(defn wait-health [url attempts]
  (loop [n attempts]
    (let [r (process/run-with-timeout ["curl" "-fsS" (str url "/api/heartbeat")] {} 10000)]
      (cond (zero? (:exit r)) true
            (pos? n) (do (Thread/sleep 5000) (recur (dec n)))
            :else false))))

(defn default-admin-active?
  "Umami seeds admin/umami. A deployment that still answers to it is not one
   whose acceptance may pass."
  [base]
  (= "200" (http-status ["-X" "POST" "-H" "content-type: application/json"
                         "--data" "{\"username\":\"admin\",\"password\":\"umami\"}"
                         (str base "/api/auth/login")])))

(defn send-event [base host website]
  (http-status ["-X" "POST" "-H" "content-type: application/json"
                "-H" "User-Agent: Mozilla/5.0 (Colors acceptance)"
                "--data" (json/generate-string
                          {:type "event"
                           :payload {:website website :hostname host
                                     :url "/colors-acceptance"
                                     :name "colors-acceptance"}})
                (str base "/api/send")]))

(defn ingestion-verdict [status before after]
  (cond (nil? status) :unreachable
        (and (integer? before) (integer? after) (> after before)) :ingested
        (re-matches #"2\d\d" (str status)) :dropped
        :else :rejected))

(defn wait-ingested [opts ip baseline attempts]
  (loop [n attempts]
    (let [after (event-count opts ip)]
      (cond (and (integer? after) (> after baseline)) after
            (pos? n) (do (Thread/sleep 3000) (recur (dec n)))
            :else after))))

(def rclone-env
  (str "RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare "
       "RCLONE_CONFIG_R2_REGION=auto RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true"))

(defn backup-listing
  "Objects under this profile's prefix, listed on the droplet with the
   credentials the backup unit already holds."
  [opts ip]
  (some-> (ssh-out ip (str "set -a; . /etc/umami-backup.env; set +a; " rclone-env
                           " RCLONE_CONFIG_R2_ACCESS_KEY_ID=\"$BACKUP_R2_ACCESS_KEY_ID\""
                           " RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=\"$BACKUP_R2_SECRET_ACCESS_KEY\""
                           " RCLONE_CONFIG_R2_ENDPOINT=\"" (:backup-r2-endpoint opts) "\""
                           " rclone lsjson --files-only r2:" (:backup-r2-bucket opts)
                           "/" (:profile opts))
                   120000)
          not-empty
          (json/parse-string true)))

(defn parse-instant [s]
  (try (.toInstant (java.time.OffsetDateTime/parse (str s))) (catch Exception _ nil)))

(defn fresh-backup? [entries since]
  (boolean (some (fn [{:keys [Size ModTime]}]
                   (and (pos? (or Size 0))
                        (when-let [t (parse-instant ModTime)]
                          (not (.isBefore t since)))))
                 entries)))

(defn run-backup [ip]
  (ssh-out ip "systemctl start umami-backup.service && systemctl is-active umami-backup.timer"
           300000))

(defn acceptance-step [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [base (str "https://" (:umami-host opts))
          ip (:ip opts)
          since (.minusSeconds (java.time.Instant/now) 120)]
      (cond
        (not (wait-health base 60))
        (assoc opts :green/exit 1
               :green/err "HTTPS heartbeat did not become ready with a valid certificate")

        (default-admin-active? base)
        (assoc opts :green/exit 1
               :green/err "the seeded admin/umami credentials still authenticate; rotate them")

        :else
        (let [website (ensure-acceptance-website opts ip)
              before (event-count opts ip)]
          (if-not (integer? before)
            (assoc opts :green/exit 1
                   :green/err "could not read website_event from PostgreSQL to verify ingestion")
            (let [verdict (if-not website
                            :not-configured
                            (let [status (send-event base (:umami-host opts) website)
                                  after (wait-ingested opts ip before 10)]
                              (ingestion-verdict status before after)))]
              (cond
                (contains? #{:dropped :rejected :unreachable} verdict)
                (assoc opts :green/exit 1
                       :green/err (str "synthetic event was not ingested: " (name verdict)))

                (nil? (run-backup ip))
                (assoc opts :green/exit 1 :green/err "backup unit or timer is not healthy")

                (not (fresh-backup? (backup-listing opts ip) since))
                (assoc opts :green/exit 1
                       :green/err (str "no backup object newer than this run under r2:"
                                       (:backup-r2-bucket opts) "/" (:profile opts)))

                :else
                (assoc opts :green/exit 0
                       :umami/acceptance {:health :ok :default-admin :rejected
                                          :event verdict :backup :verified-in-r2})))))))))
