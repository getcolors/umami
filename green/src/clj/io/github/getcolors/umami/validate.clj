(ns io.github.getcolors.umami.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.umami.compute :as compute]
            [io.github.getcolors.compute-ssh :as compute-ssh]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def default-compute-provider "digitalocean")

(def required
  "Every key desired state must carry whichever provider is selected. The
  provider-scoped keys come from `compute-providers`."
  [:profile :workdir :provider-compute :provider-dns :provider-backend
   :compute-prevent-destroy :umami-host :caddy-image])
(def host-re #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def image-re #"^[^\s:@]+(?:/[^\s:@]+)*:[^\s:@]+$")
(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn keygen? [opts] (try (= "managed" (:mode (compute-ssh/mode opts))) (catch Exception _ true)))

(defn state-errors
  "Every problem with desired state at once: the missing keys (this package's
  and the selected provider's), the package's own checks, then the Compute
  Provider Standard's -- selection, the network contract and the provider
  rules, DigitalOcean's VPC refusal among them -- which are ONCE's over
  `spec`."
  [opts]
  (vec
   (concat
    (for [k required
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (= "cloudflare" (:provider-dns opts))
      [":provider-dns must be cloudflare"])
    (when-not (contains? #{"s3" "r2"} (:provider-backend opts))
      [":provider-backend must be s3 or r2"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when-not (or (missing? (:umami-host opts))
                  (re-matches host-re (str (:umami-host opts))))
      [":umami-host must be a fully qualified hostname"])
    (for [k [:caddy-image :umami-image :postgres-image]
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (re-matches image-re (str v))))]
      (str k " must carry an explicit image tag"))
    (for [k [:backup-retention-days :umami-backup-retention-days :umami-port :postgres-port]
          :let [v (get opts k)]
          :when (and (not (missing? v))
                     (not (and (integer? v) (pos? v))))]
      (str k " must be a positive integer"))
    (compute/errors opts))))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(defn secret-errors
  "Credentials a real create or delete needs: the selected compute provider's,
  Cloudflare's, the application's, the backup bucket's, and the backend's."
  [opts]
  (let [keys (concat
                     [:cloudflare-api-token :postgres-password
                      :umami-admin-password]
                     ;; The compose template interpolates these at run time and
                     ;; carries no fallback, so an unset value would silently
                     ;; render an empty password or signing key.
                     (when (and (missing? (:app-secret-key opts))
                                (missing? (:umami-app-secret opts)))
                       [:app-secret-key])
                     (when (and (missing? (:backup-r2-access-key-id opts))
                                (missing? (:umami-backup-r2-access-key-id opts))
                                (missing? (:r2-access-key-id opts)))
                       [:backup-r2-access-key-id])
                     (when (and (missing? (:backup-r2-secret-access-key opts))
                                (missing? (:umami-backup-r2-secret-access-key opts))
                                (missing? (:r2-secret-access-key opts)))
                       [:backup-r2-secret-access-key])
                     (backend-secrets opts))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

(defn tofu-env [opts slot]
  (case slot
    :provider-compute {}
    :provider-dns {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
