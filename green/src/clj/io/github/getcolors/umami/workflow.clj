(ns io.github.getcolors.umami.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.once.compute :as compute]
            [io.github.getcolors.umami.ssh :as ssh]
            [io.github.getcolors.umami.ssh-config :as ssh-config]
            [io.github.getcolors.umami.tools :as tools]
            [io.github.getcolors.umami.validate :as validate]))

(def defaults {:provider-compute validate/default-compute-provider
               :provider-dns "cloudflare"
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
               ;; Proxied by default: an unproxied record publishes the
               ;; droplet's address. Note this map is the effective default --
               ;; it seeds the key, so tools/dns-data always sees it supplied
               ;; and its own fallback never runs. Both have to agree.
               :cloudflare-proxied true})

(defn state-output
  "Compute params recorded in the infrastructure state; nil when the state
  holds none. An unreadable backend throws the SDK's step error, which
  `compute/read-state` turns into `{:error message}` -- create and delete
  treat the two differently. Kept local so tests can redefine it."
  [opts]
  (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                        (tools/backend-credential-env opts))
          :params walk/keywordize-keys))

(defn adopt-state
  "A real delete runs the ansible cleanup before the infrastructure step, so
  the instance address must come out of the existing state here. The adoption
  itself is ONCE's (`compute/adopt-state`): a readable state without compute
  params leaves :ip unset and the cleanup step skips itself; an unreadable
  backend fails loudly -- swallowing it is how a live teardown ended up
  converging against 192.0.2.10. What this package adds is the address
  override: an explicit :ip (COLORS_PAR_IP) never skips the read or the
  provider guard, it only replaces the cleanup address once the read has
  succeeded, for a state whose recorded address is stale. ONCE deliberately
  applies no such override, so no other package gains a way to point a
  delete's cleanup at an arbitrary host."
  [opts state]
  (let [adopted (compute/adopt-state opts :delete state)]
    (if (and (not (wf/failed? adopted)) (:ip opts))
      (assoc adopted :ip (:ip opts))
      adopted)))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   ;; The state is read once, up front, on the same defaulted and overlaid
   ;; opts the validators see -- the overlay is what carries the backend
   ;; credentials -- and only for the two events that touch a provider. The
   ;; validator and the after-validate share the one read.
   (let [overlaid (green-cli/read-pars (merge defaults opts) env)
         context {:event (:green/event overlaid) :real? (lifecycle/real-run? overlaid)}
         state (when (compute/lifecycle-event? context)
                 (compute/read-state overlaid state-output))]
     (lifecycle/preflight
      opts {:defaults defaults :overlay green-cli/read-pars
            :validators
            [(fn [_ env _] (validate/env-errors env))
             (fn [opts _ _] (validate/state-errors opts))
             ;; Standard §4 before the credentials: a recorded provider that
             ;; differs from the selected one reports the actionable error, not
             ;; a missing token for the provider that was just selected.
             (fn [opts _ ctx]
               (when (compute/lifecycle-event? ctx)
                 (compute/provider-validator validate/spec opts (:params state)
                                             #(validate/secret-errors opts))))
             (fn [opts _ {:keys [event real?]}]
               (when (and real? (= :delete event) (:compute-prevent-destroy opts))
                 [(str "compute destruction is protected; set "
                       (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
            :after-validate
            ;; The machine key's create matrix and the provider preflight run
            ;; before any template is rendered: an unowned key on disk or at
            ;; the provider stops the run while stopping is still free. Delete
            ;; fills the same template values -- a destroy renders before it
            ;; destroys -- and adopts the recorded address, but checks no key,
            ;; because its key cleanup runs after the compute destroy.
            (fn [opts _ {:keys [event real?]}]
              (cond
                (and real? (= :delete event))
                (adopt-state opts state)

                (and real? (= :create event))
                (let [opts (ssh/ensure-key! opts (fn [_] (:params state)))]
                  (if (wf/failed? opts)
                    opts
                    (let [opts (ssh/preflight! (ssh/with-machine-key opts))
                          opts (if (wf/failed? opts) opts (ssh-config/preflight! opts))]
                      (if (wf/failed? opts) opts (assoc opts :green/exit 0)))))

                :else
                (assoc (ssh/with-machine-key opts) :green/exit 0)))} env))))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :umami/start [start-step :umami/ansible]
      :umami/ansible [tools/ansible-step :umami/dns]
      ;; The `~/.ssh/config` block goes before the destroy, the opposite of the
      ;; keypair below. A block that outlives its host is stale but harmless; a
      ;; key that predeceases its host locks the operator out of a machine that
      ;; still exists. Both orders are deliberate; see standards/ssh-config.md.
      :umami/dns [tools/dns-step :umami/ssh-config]
      :umami/ssh-config [tools/ansible-local-step :umami/infrastructure]
      ;; The keypair goes strictly after the compute destroy: a key that
      ;; predeceases its host locks the operator out of a machine that still
      ;; exists (SSH Keypair Standard §3.3).
      :umami/infrastructure [tools/infrastructure-step :umami/ssh-cleanup]
      :umami/ssh-cleanup [ssh/cleanup-step])
    (case step
      :umami/start [start-step :umami/infrastructure]
      ;; After compute, which is where the address first exists, and before the
      ;; stage that converges the machine.
      :umami/infrastructure [tools/infrastructure-step :umami/ssh-config]
      :umami/ssh-config [tools/ansible-local-step :umami/dns]
      :umami/dns [tools/dns-step :umami/ansible]
      :umami/ansible [tools/ansible-step :umami/acceptance]
      :umami/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting [:umami/infrastructure :umami/dns :umami/ssh-config
                     :umami/ansible :umami/acceptance :umami/ssh-cleanup])

(def workflow
  (-> (wf/workflow {:start :umami/start :wire-fn wire-fn})
      (wf/advice-add :umami/infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      (wf/advice-add :umami/dns :before ::backend (backend-advice tools/dns-tool))
      progress/advise
      (dry-run/advise side-effecting)))
