(ns io.github.getcolors.umami.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.ansible :as ansible]
            [green.scaffold :as sc]
            [io.github.getcolors.umami.tools :as tools]
            [io.github.getcolors.umami.validate-test :refer [fixture keygen]]))

(defn- render-infrastructure
  "The compute template for `opts`' provider, rendered as `build` would."
  [opts]
  (sc/render-template (tools/template (str "infrastructure." (:provider-compute opts)) "main.tf")
                      (tools/infrastructure-data opts)
                      tools/template-opts))

(deftest delete-cleanup-skips-when-state-has-no-compute
  ;; With the instance already gone the inventory would render 192.0.2.10;
  ;; there is no host to reach, so the step must not run the playbook and the
  ;; teardown must continue past it.
  (with-redefs [ansible/ansible-with-spec
                (fn [& _] (throw (ex-info "playbook must not run" {})))]
    (let [r (tools/ansible-step (fixture :green/event :delete))]
      (is (= 0 (:green/exit r)))
      (is (= :skipped-no-compute (:umami/cleanup r))))))

(deftest delete-cleanup-targets-the-adopted-address
  ;; When the start step recovered the instance address from state, the
  ;; cleanup playbook runs against it, never the documentation fallback.
  (with-redefs [ansible/ansible-with-spec
                (fn [opts _ _] (assoc opts :green/exit 0 ::ran-against (:ip opts)))]
    (let [r (tools/ansible-step (fixture :green/event :delete :ip "203.0.113.7"))]
      (is (= "203.0.113.7" (::ran-against r))))))

(deftest infrastructure-discovers-default-vpc
  (let [data (tools/infrastructure-data (fixture))]
    (is (= ["0.0.0.0/0" "::/0"] (tools/cidrs data :digitalocean-http-sources)))
    (is (str/includes? (:http-sources-hcl data) "0.0.0.0/0"))))

(deftest hostname-is-provider-neutral
  ;; The playbook used digitalocean-name, which renders empty without the
  ;; override; the resolved name is what every label derives from.
  (is (= "umami-fixture" (tools/compute-name (fixture))))
  (is (= "umami-fixture" (tools/compute-name (fixture :digitalocean-name nil))))
  (is (str/includes? (slurp "src/resources/io/github/getcolors/umami/tools/ansible/main.yml")
                     "<{ compute-name }>")))

(deftest infrastructure-data-carries-the-name-and-the-keypair-mode
  ;; One resolved name and one mode reach every template, so no template
  ;; branches on the provider or re-derives either.
  (let [data (tools/infrastructure-data (fixture))]
    (is (= "umami-fixture" (:compute-name data)))
    (is (false? (:ssh-keygen data))))
  (let [data (tools/infrastructure-data (keygen))]
    (is (= "umami-keygen-fixture" (:compute-name data)))
    (is (true? (:ssh-keygen data))))
  (is (true? (:ssh-keygen (tools/ansible-data (keygen)))))
  (is (false? (:ssh-keygen (tools/ansible-data (fixture))))))

(deftest templates-name-the-machine-from-one-resolved-value
  ;; Every label -- droplet name, firewall name and params.name --
  ;; interpolates compute-name, never a provider key or the profile directly,
  ;; so an override and the fallback land everywhere at once.
  (let [template (slurp "src/resources/io/github/getcolors/umami/tools/infrastructure/digitalocean/main.tf")]
    (is (not (str/includes? template "<{ digitalocean-name }>")))
    (is (str/includes? template "name     = \"<{ compute-name }>\""))
    (is (str/includes? template "provider = \"digitalocean\"")))
  (let [rendered (render-infrastructure (fixture :digitalocean-name "custom-label"))]
    (is (str/includes? rendered "name     = \"custom-label\""))
    (is (str/includes? rendered "name        = \"custom-label-firewall\""))
    (is (str/includes? rendered "name = \"custom-label\""))))

(deftest empty-http-sources-renders-no-public-http
  ;; An empty `digitalocean-http-sources` is allowed and means no public HTTP:
  ;; the 80/443 rules are a dynamic block over an empty list, because a rule
  ;; with no source is an API error to DigitalOcean, not a closed port. SSH
  ;; stays.
  (let [rendered (render-infrastructure (fixture :digitalocean-http-sources []))]
    (is (str/includes? rendered "length([]) > 0 ? ["))
    (is (str/includes? rendered "source_addresses = []"))
    (is (str/includes? rendered "port_range       = \"22\"")))
  (let [rendered (render-infrastructure (fixture))]
    (is (str/includes? rendered "length([\"0.0.0.0/0\", \"::/0\"]) > 0 ? ["))
    (is (str/includes? rendered "{ protocol = \"tcp\", port_range = \"80\" }"))
    (is (str/includes? rendered "{ protocol = \"tcp\", port_range = \"443\" }"))
    (is (not (str/includes? rendered "udp\", port_range = \"443")))))

(deftest keygen-mode-renders-the-key-resource-and-opt-out-keeps-the-literal
  (let [generated (render-infrastructure (keygen))
        opted-out (render-infrastructure (fixture))]
    (is (str/includes? generated "resource \"digitalocean_ssh_key\" \"machine\""))
    (is (str/includes? generated "ssh_keys = [digitalocean_ssh_key.machine.id]"))
    (is (str/includes? generated "ssh_key_id = digitalocean_ssh_key.machine.id"))
    (is (not (str/includes? opted-out "digitalocean_ssh_key")))
    (is (str/includes? opted-out "ssh_keys = [\"58495393\"]"))
    (is (not (str/includes? opted-out "ssh_key_id")))))

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

(def caddyfile
  (delay (slurp "src/resources/io/github/getcolors/umami/tools/ansible/Caddyfile")))

(def compose
  (delay (slurp "src/resources/io/github/getcolors/umami/tools/ansible/compose.yml")))

(def playbook
  (delay (slurp "src/resources/io/github/getcolors/umami/tools/ansible/main.yml")))

(deftest caddy-access-logging-is-on-and-bounded
  ;; Access logging is off by default in Caddy, so a successful request left no
  ;; trace and ingestion had no request-level evidence to debug from.
  (is (str/includes? @caddyfile "log {"))
  (is (str/includes? @caddyfile "output stdout"))
  ;; On, but bounded: json-file never rotates on its own and this endpoint
  ;; writes a line per request.
  (is (str/includes? @compose "max-size"))
  (is (str/includes? @compose "max-file")))

(deftest caddy-reload-is-convergent-not-change-triggered
  ;; The Caddyfile is a single-file bind mount, so copy-by-rename leaves the
  ;; container on the old inode and `up -d` will not recreate an unchanged
  ;; service: the host file looked right while Caddy served the old config.
  (is (str/includes? @playbook "--force-recreate caddy"))
  (is (str/includes? @playbook "sha256sum /etc/caddy/Caddyfile"))
  ;; And it must run once the stack is up, or it recreates against a compose
  ;; file that has not been rendered yet.
  (let [converge (str/index-of @playbook "Build and converge pinned containers")
        reload (str/index-of @playbook "--force-recreate caddy")
        health (str/index-of @playbook "Wait for Umami health endpoint")]
    (is (< converge reload health))))

(deftest access-log-records-the-visitor-not-the-proxy
  ;; Behind the Cloudflare proxy every connection arrives from an edge address,
  ;; so without trusted_proxies Caddy attributes each request to Cloudflare and
  ;; the access log answers "who sent this?" with the proxy. Verified against a
  ;; live deployment: the arm with this block logged the real client address
  ;; and the arm without it logged 162.158.x.
  (is (str/includes? @caddyfile "trusted_proxies static"))
  (is (str/includes? @caddyfile "162.158.0.0/15"))
  (is (str/includes? @caddyfile "2400:cb00::/32")))
