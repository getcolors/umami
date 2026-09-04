(ns io.github.getcolors.umami.ssh-config-test
  "Conformance with the workspace SSH Config Standard.

  Every test that needs a config file redirects `config-path` into a temporary
  directory: nothing here may read or write the real `~/.ssh/config`."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.scaffold :as sc]
            [io.github.getcolors.umami.ssh-config :as ssh-config]
            [io.github.getcolors.umami.tools :as tools]
            [io.github.getcolors.umami.validate-test :refer [fixture keygen]]
            [io.github.getcolors.umami.workflow :as workflow]))

(defn- with-config
  "Run `f` with `~/.ssh/config` redirected to a fresh temporary file holding
  `content` (or absent when nil)."
  [content f]
  (let [home (str (fs/create-temp-dir {:prefix "umami-ssh-config"}))
        file (io/file home ".ssh" "config")]
    (try
      (when content
        (io/make-parents file)
        (spit file content))
      (with-redefs [ssh-config/config-path (constantly file)] (f file))
      (finally (fs/delete-tree home)))))

;; §2 the alias and the identity file

(deftest alias-is-the-profile
  (is (= "umami-fixture" (ssh-config/host-alias (fixture)))))

(deftest identity-file-keeps-the-tilde
  ;; An expanded home directory would make the rendered block differ per
  ;; workstation; OpenSSH expands the tilde itself.
  (is (= "~/.ssh/umami-fixture" (ssh-config/identity-file (fixture))))
  (is (not (str/includes? (ssh-config/identity-file (fixture))
                          (System/getProperty "user.home")))))

(deftest the-marker-is-the-alias-alone
  ;; The profile is <package>-<suffix>, so a marker carrying the package name
  ;; too would repeat it: "# BEGIN umami umami-vultr".
  (is (= "# BEGIN umami-vultr ANSIBLE MANAGED BLOCK"
         (ssh-config/begin-marker "umami-vultr")))
  (is (= "# END umami-vultr ANSIBLE MANAGED BLOCK"
         (ssh-config/end-marker "umami-vultr"))))

(deftest owned-markers-hold-the-one-marker
  ;; Born conforming: no marker migration is in flight, so the set of markers
  ;; this package recognises as its own holds exactly the current one.
  (is (= {:begin #{"# BEGIN umami-vultr ANSIBLE MANAGED BLOCK"}
          :end #{"# END umami-vultr ANSIBLE MANAGED BLOCK"}}
         (ssh-config/owned-markers "umami-vultr"))))

;; §5 never adopt

(deftest host-patterns-are-read-from-a-host-line
  (is (= ["umami-fixture"] (ssh-config/host-patterns "Host umami-fixture")))
  (is (= ["web" "umami-fixture" "db"] (ssh-config/host-patterns "  host   web umami-fixture  db ")))
  (is (nil? (ssh-config/host-patterns "    HostName 192.0.2.1")))
  (is (nil? (ssh-config/host-patterns "Match host umami-fixture"))))

(deftest a-foreign-stanza-is-found
  (let [lines ["Host other" "    HostName 192.0.2.1" "" "Host umami-fixture"]]
    (is (= 4 (ssh-config/foreign-stanza-line lines "umami-fixture")))))

(deftest our-own-block-is-not-foreign
  (let [alias "umami-fixture"
        lines [(ssh-config/begin-marker alias)
               (str "Host " alias)
               "    HostName 192.0.2.1"
               (ssh-config/end-marker alias)]]
    (is (nil? (ssh-config/foreign-stanza-line lines alias)))))

(deftest a-stanza-after-our-block-is-still-foreign
  (let [alias "umami-fixture"
        lines [(ssh-config/begin-marker alias)
               (str "Host " alias)
               (ssh-config/end-marker alias)
               (str "Host " alias)]]
    (is (= 4 (ssh-config/foreign-stanza-line lines alias)))))

(deftest a-block-under-a-package-prefixed-marker-is-foreign
  ;; This package never wrote a `# BEGIN umami <alias>` marker, so a block
  ;; carrying one belongs to nobody this package knows and must stop the run
  ;; rather than being silently overwritten. Recognising a marker means
  ;; putting it in owned-markers at the same time.
  (let [alias "umami-vultr"
        lines [(str "# BEGIN umami " alias " ANSIBLE MANAGED BLOCK")
               (str "Host " alias)
               (str "# END umami " alias " ANSIBLE MANAGED BLOCK")]]
    (is (= 2 (ssh-config/foreign-stanza-line lines alias)))))

(deftest a-multi-pattern-host-line-counts
  (is (= 1 (ssh-config/foreign-stanza-line ["Host web umami-fixture db"]
                                           "umami-fixture"))))

(deftest an-unrelated-file-is-left-alone
  (is (nil? (ssh-config/foreign-stanza-line ["Host build" "Host umami-other"]
                                            "umami-fixture"))))

(deftest adopt-error-names-the-file-and-the-line
  (with-config "Host other\n    HostName 192.0.2.1\n\nHost umami-fixture\n    User root\n"
    (fn [file]
      (let [err (ssh-config/adopt-error (fixture))]
        (is (str/includes? err (.getPath file)))
        (is (str/includes? err "`Host umami-fixture` at line 4"))
        (is (str/includes? err "will not overwrite it"))))))

(deftest adopt-error-passes-our-own-block-and-a-missing-file
  (with-config (str (ssh-config/begin-marker "umami-fixture") "\n"
                    "Host umami-fixture\n    HostName 192.0.2.1\n"
                    (ssh-config/end-marker "umami-fixture") "\n")
    (fn [_] (is (nil? (ssh-config/adopt-error (fixture))))))
  (with-config nil
    (fn [_] (is (nil? (ssh-config/adopt-error (fixture)))))))

(deftest preflight-refuses-rather-than-overwrites
  (with-redefs [ssh-config/adopt-error (fn [_] "already declares `Host x`")
                ssh-config/placement-error (fn [_] nil)]
    (let [r (ssh-config/preflight! (fixture))]
      (is (= 1 (:green/exit r)))
      (is (str/includes? (:green/err r) "already declares")))))

(deftest preflight-passes-a-clean-file
  (with-redefs [ssh-config/adopt-error (fn [_] nil)
                ssh-config/placement-error (fn [_] nil)]
    (is (nil? (:green/exit (ssh-config/preflight! (fixture)))))))

(deftest preflight-reads-the-redirected-file
  ;; End to end through the real readers: a foreign stanza refuses, a clean
  ;; file passes, and the placement check runs after the ownership check.
  (with-config "Host umami-fixture\n    HostName 192.0.2.1\n"
    (fn [_]
      (let [r (ssh-config/preflight! (fixture))]
        (is (= 1 (:green/exit r)))
        (is (str/includes? (:green/err r) "already declares")))))
  (with-config "ServerAliveInterval 60\nHost a\n"
    (fn [_]
      (let [r (ssh-config/preflight! (fixture))]
        (is (= 1 (:green/exit r)))
        (is (str/includes? (:green/err r) "line 1")))))
  (with-config "Host a\n    User root\n"
    (fn [_] (is (nil? (:green/exit (ssh-config/preflight! (fixture))))))))

;; §5 placement. The block is written with insertbefore: BOF, because
;; blockinfile anchors insertbefore on the *last* match and has no firstmatch.

(deftest an-option-above-the-first-host-is-refused
  ;; It is global today; a BOF insert would capture it into one stanza.
  (is (= 1 (ssh-config/leading-option-line ["ServerAliveInterval 60" "Host a"])))
  (is (= 3 (ssh-config/leading-option-line ["# comment" "" "IdentitiesOnly yes" "Host a"]))))

(deftest a-file-that-opens-with-a-host-is-fine
  (is (nil? (ssh-config/leading-option-line ["Host a" "    User root"])))
  (is (nil? (ssh-config/leading-option-line ["# lead comment" "" "Host a" "    User root"])))
  (is (nil? (ssh-config/leading-option-line ["Match host b" "    User root"]))))

(deftest a-file-of-only-comments-is-fine
  (is (nil? (ssh-config/leading-option-line ["# nothing here" ""]))))

(deftest placement-error-mentions-the-recovery
  (with-config "# comment\n\n\nIdentitiesOnly yes\nHost a\n"
    (fn [file]
      (let [err (ssh-config/placement-error (fixture))]
        (is (str/includes? err (.getPath file)))
        (is (str/includes? err "line 4"))
        (is (str/includes? err "Host *"))))))

;; §6 build determinism

(deftest build-and-dry-run-never-read-the-config
  ;; The only readers are adopt-error and placement-error, and they must not
  ;; run on a rendered-only event. Redefining them to throw proves nothing in
  ;; the build path calls them.
  (with-redefs [ssh-config/adopt-error (fn [_] (throw (ex-info "read ~/.ssh/config" {})))
                ssh-config/placement-error (fn [_] (throw (ex-info "read ~/.ssh/config" {})))]
    (doseq [opts [(assoc (fixture) :green/event :build)
                  (assoc (keygen) :green/event :build)
                  (assoc (fixture) :green/event :create :green/dry-run true)]]
      (is (= 0 (:green/exit (workflow/start-step opts {})))))))

(deftest the-local-play-renders-no-address
  ;; Address, user and alias are run-time facts and travel as extra-vars, so
  ;; the rendered playbook carries none of them.
  (let [data (tools/ansible-local-data (assoc (fixture) :ip "203.0.113.7"))]
    (is (not (contains? data :ip-rendered)))
    (is (= "~/.ssh/umami-fixture" (:ssh-config-identity-file data)))))

(deftest the-local-stage-renders-three-files
  (let [targets (map #(str (:target %)) (tools/ansible-local-specs (fixture)))]
    (is (some #(str/ends-with? % "/ansible.cfg") targets))
    (is (some #(str/ends-with? % "/inventory.ini") targets))
    (is (some #(str/ends-with? % "/main.yml") targets))
    (is (every? #(str/includes? % "umami-ansible-local") targets))))

;; §3 the identity file follows keygen mode

(deftest keygen-mode-decides-the-identity-lines
  (is (true? (:ssh-keygen (tools/ansible-local-data (keygen)))))
  (is (false? (:ssh-keygen (tools/ansible-local-data (fixture))))))

(defn- render-play [opts]
  (sc/render-template (tools/template "ansible-local" "main.yml")
                      (tools/ansible-local-data opts)
                      tools/template-opts))

(deftest the-rendered-play-carries-the-identity-pair-only-in-keygen-mode
  (let [keygen-play (render-play (keygen))
        optout-play (render-play (fixture))]
    (is (str/includes? keygen-play "IdentityFile ~/.ssh/umami-keygen-fixture"))
    (is (str/includes? keygen-play "IdentitiesOnly yes"))
    ;; The header comment names the pair; the rendered option lines must not.
    (is (not (str/includes? optout-play "IdentityFile ~/.ssh/")))
    (is (not (str/includes? optout-play "IdentitiesOnly yes")))
    ;; Address, user and alias are Ansible's, never Selmer's.
    (doseq [play [keygen-play optout-play]]
      (is (str/includes? play "insertbefore: BOF"))
      (is (str/includes? play "Host {{ host_alias }}"))
      (is (str/includes? play "HostName {{ ip }}"))
      (is (str/includes? play "StrictHostKeyChecking accept-new"))
      (is (not (re-find #"([0-9]{1,3}\.){3}[0-9]{1,3}" play))))))

;; §4 lifecycle

(deftest create-writes-the-block-after-compute-and-before-convergence
  (is (= [:umami/ssh-config]
         (vec (rest (workflow/wire-fn :umami/infrastructure {:green/event :create})))))
  (is (= [:umami/dns]
         (vec (rest (workflow/wire-fn :umami/ssh-config {:green/event :create}))))))

(deftest delete-removes-the-block-before-the-destroy
  ;; The opposite of the keypair, which goes last. A stale block is harmless; a
  ;; key removed early locks the operator out of a machine that still exists.
  (is (= [:umami/ssh-config]
         (vec (rest (workflow/wire-fn :umami/dns {:green/event :delete})))))
  (is (= [:umami/infrastructure]
         (vec (rest (workflow/wire-fn :umami/ssh-config {:green/event :delete})))))
  (is (= [:umami/ssh-cleanup]
         (vec (rest (workflow/wire-fn :umami/infrastructure {:green/event :delete}))))))
