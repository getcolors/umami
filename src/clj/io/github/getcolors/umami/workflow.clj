(ns io.github.getcolors.umami.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.umami.tools :as tools]
            [io.github.getcolors.umami.validate :as validate]))

(def defaults {:provider-compute "digitalocean" :provider-dns "cloudflare"
               :provider-backend "local" :compute-prevent-destroy true
               :workdir ".colors" :umami-port 3000 :postgres-port 5432
               :postgres-db "umami" :postgres-user "umami"
               :postgres-data-dir "/var/lib/umami/postgres"
               :backup-dir "/var/backups/umami"
               :backup-r2-bucket "umami-backup"
               :backup-r2-region "auto"
               :backup-oncalendar "*-*-* 03:00:00"
               :backup-retention-days 7
               :caddy-image "caddy:2.11.4"
               :cloudflare-proxied false})

(defn state-output [opts]
  (try (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                             (tools/backend-credential-env opts))
               :params walk/keywordize-keys)
       (catch Exception _ nil)))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (contains? #{:create :delete} event))
               (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
          :after-validate
          (fn [opts _ {:keys [event real?]}]
            (if (and real? (= :delete event))
              (merge opts (or (state-output opts) {}) {:green/exit 0})
              (assoc opts :green/exit 0)))} env)))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :umami/start [start-step :umami/ansible]
      :umami/ansible [tools/ansible-step :umami/dns]
      :umami/dns [tools/dns-step :umami/infrastructure]
      :umami/infrastructure [tools/infrastructure-step])
    (case step
      :umami/start [start-step :umami/infrastructure]
      :umami/infrastructure [tools/infrastructure-step :umami/dns]
      :umami/dns [tools/dns-step :umami/ansible]
      :umami/ansible [tools/ansible-step :umami/acceptance]
      :umami/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting [:umami/infrastructure :umami/dns :umami/ansible :umami/acceptance])

(def workflow
  (-> (wf/workflow {:start :umami/start :wire-fn wire-fn})
      (wf/advice-add :umami/infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      (wf/advice-add :umami/dns :before ::backend (backend-advice tools/dns-tool))
      progress/advise
      (dry-run/advise side-effecting)))
