import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { renderTemplate } from "red/scaffold";
import { StepError, type Opts } from "red/workflow";
import * as ssh from "../src/ssh.ts";
import * as sshConfig from "../src/ssh-config.ts";
import * as tools from "../src/tools.ts";
import * as validate from "../src/validate.ts";
import * as compute from "../src/compute.ts";
import * as workflow from "../src/workflow.ts";

const fixtureFile = join(import.meta.dir, "../../test/fixtures/colors.yml");
const keygenFile = join(import.meta.dir, "../../test/fixtures/keygen.yml");

function readFixture(path: string, overrides: Opts): Opts {
  const text = readFileSync(path, "utf8").replaceAll("WORKDIR", ".colors");
  return { ...(Bun.YAML.parse(text) as Opts), ...overrides };
}

// DigitalOcean in opt-out mode (an explicit key id, a name equal to the
// profile — the shape every umami deployment has had) and in keygen mode (no
// `digitalocean-ssh-keys`, no `digitalocean-name`).
const fixture = (overrides: Opts = {}) => readFixture(fixtureFile, overrides);
const keygen = (overrides: Opts = {}) => readFixture(keygenFile, overrides);

// ~/.ssh redirection: ONCE's ssh module and this package's ssh-config both
// read $HOME at call time, exactly so tests can point them at a fresh
// temporary home. Nothing here may touch the real one.
let savedHome: string | undefined;
let home: string;
beforeEach(() => {
  savedHome = process.env.HOME;
  home = mkdtempSync(join(tmpdir(), "umami-red-test"));
  process.env.HOME = home;
});
afterEach(() => {
  process.env.HOME = savedHome;
  rmSync(home, { recursive: true, force: true });
});

function write(path: string, content: string) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

const resource = (name: string) =>
  readFileSync(join(import.meta.dir, "../resources", name), "utf8");

// --- desired state -----------------------------------------------------------

describe("validate", () => {
  test("both fixtures are valid", () => {
    expect(validate.stateErrors(fixture())).toEqual([]);
    expect(validate.stateErrors(keygen())).toEqual([]);
  });







  test("unselected provider keys are ignored, not refused", () => {
    // One colors.yml may carry another provider's block; only the selected
    // provider's keys are read. `digitalocean-https-sources`, which older
    // desired state carries, is likewise accepted and ignored.
    expect(validate.stateErrors(fixture({ "vultr-plan": "vc2-2c-4gb", "vultr-os-id": "ubuntu" }))).toEqual([]);
    expect(validate.stateErrors(fixture({ "digitalocean-https-sources": ["0.0.0.0/0"] }))).toEqual([]);
    expect(validate.stateErrors(fixture({ "digitalocean-size": null }))
      .some((e) => e.includes("compute deployment"))).toBe(true);
  });

  test("absent machine key selects keygen", () => {
    expect(validate.keygen(keygen())).toBe(true);
    expect(validate.keygen(fixture())).toBe(false);
    // Absence, not a flag, is the switch.
    expect(validate.keygen(fixture({ "digitalocean-ssh-keys": null }))).toBe(true);
  });











  test("reports all errors at once", () => {
    const errors = validate.stateErrors(fixture({
      "umami-host": "bad", "caddy-image": "floating",
      "backup-retention-days": -1,
      "provider-dns": "other", "digitalocean-vpc-uuid": "forbidden",
    }));
    expect(errors.length).toBeGreaterThanOrEqual(5);
    for (const part of ["host", "image", "retention", "provider-dns", "compute deployment"]) {
      expect(errors.some((e) => e.includes(part))).toBe(true);
    }
  });



  test("profile overlay is refused", () => {
    expect(validate.envErrors({ COLORS_PAR_PROFILE: "other" }).length).toBe(1);
    expect(validate.envErrors({})).toEqual([]);
  });



  test("accepts the alternate app secret name", () => {
    const errors = validate.secretErrors(fixture({ "umami-app-secret": "alternate" })).join("\n");
    expect(errors).not.toContain("COLORS_PAR_APP_SECRET_KEY");
  });

  test("the compose template carries no default credential", () => {
    const compose = resource("tools/ansible/compose.yml");
    expect(compose).not.toContain("default('umami'");
    expect(/secret_hash_key/i.test(compose)).toBe(false);
    // The password reaches Umami inside a URL, so it must be percent-encoded.
    expect(compose).toContain("urlencode | replace('/', '%2F')");
  });
});

// --- tools -------------------------------------------------------------------

describe("tools", () => {
  test("delete cleanup skips when state has no compute", async () => {
    // With the instance already gone the inventory would render 192.0.2.10;
    // there is no host to reach, so the step must not run the playbook and the
    // teardown must continue past it.
    const work = mkdtempSync(join(tmpdir(), "umami-red-cleanup"));
    try {
      const result = await tools.ansibleStep(
        fixture({ "red/event": "delete", workdir: work }),
        () => { throw new Error("playbook must not run"); });
      expect(result["red/exit"]).toBe(1);
      expect(result["red/err"]).toBe("compute node unavailable");
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  test("delete cleanup targets the adopted address", async () => {
    // When the start step recovered the instance address from state, the
    // cleanup playbook runs against it, never the documentation fallback.
    const work = mkdtempSync(join(tmpdir(), "umami-red-cleanup"));
    try {
      const result = await tools.ansibleStep(
        fixture({ "red/event": "delete", ip: "203.0.113.7", user:"root", name:"umami-fixture", workdir: work }),
        async (opts) => ({ ...opts, "red/exit": 0, "ran-against": opts.ip }));
      expect(result["ran-against"]).toBe("203.0.113.7");
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });













  test("dns computes zone and record", () => {
    const json = tools.dnsJson(tools.dnsData(fixture({ ip: "192.0.2.10" })));
    expect(json).toContain("umami.example.com");
    expect(json).toContain("192.0.2.10");
    expect(json).toContain('"proxied" : true');
  });

  test("dns proxying defaults on and can be declined", () => {
    expect(tools.dnsData(fixture())["cloudflare-proxied"]).toBe(true);
    expect(tools.dnsJson(tools.dnsData(
      fixture({ ip: "192.0.2.10", "cloudflare-proxied": false }))))
      .toContain('"proxied" : false');
  });

  test("the inventory keeps one target", () => {
    const inventory = tools.inventory(fixture({ ip: "192.0.2.10" }));
    expect(inventory).toContain("192.0.2.10");
    expect(inventory).toContain("umami-fixture");
  });

  test("ingestion is judged by the stored row, not the status", () => {
    expect(tools.ingestionVerdict("200", 4, 5)).toBe("ingested");
    // The failure this gate exists for: the endpoint accepts and nothing lands.
    expect(tools.ingestionVerdict("200", 4, 4)).toBe("dropped");
    expect(tools.ingestionVerdict("202", 4, undefined)).toBe("dropped");
    expect(tools.ingestionVerdict("400", 4, 4)).toBe("rejected");
    expect(tools.ingestionVerdict(undefined, 4, 4)).toBe("unreachable");
  });

  test("a backup must be fresh and non-empty", () => {
    const since = new Date("2026-08-17T03:00:00Z");
    const entry = (size: number, modTime: string) => ({ Size: size, ModTime: modTime });
    expect(tools.freshBackup([entry(1024, "2026-08-17T03:00:05Z")], since)).toBe(true);
    expect(tools.freshBackup([entry(1024, "2026-08-17T05:00:05+02:00")], since)).toBe(true);
    // A stale object from an earlier run must not certify today's drill.
    expect(tools.freshBackup([entry(1024, "2026-08-16T03:00:05Z")], since)).toBe(false);
    // An empty upload is not a backup.
    expect(tools.freshBackup([entry(0, "2026-08-17T03:00:05Z")], since)).toBe(false);
    expect(tools.freshBackup([], since)).toBe(false);
    expect(tools.freshBackup(undefined, since)).toBe(false);
  });

  const backupScript = resource("tools/ansible/backup");

  test("backup proves it restores and prunes the bucket", () => {
    // An archive that exists is not an archive that restores, and pruning only
    // the local disk leaves R2 growing without bound.
    expect(backupScript).toContain("CREATE DATABASE");
    expect(backupScript).toContain("information_schema.tables");
    expect(backupScript).toContain("rclone delete --min-age");
    // The restore must happen before the upload, so a bad dump never lands.
    const restore = backupScript.indexOf("restore check restored no tables");
    const upload = backupScript.indexOf("rclone copyto");
    expect(restore).toBeGreaterThanOrEqual(0);
    expect(restore).toBeLessThan(upload);
  });

  test("acceptance provisions its own website", () => {
    // With no website the step reports "not-configured" and sends nothing, so
    // the synthetic request is never exercised — exactly how the sibling
    // package carried a payload its API had always rejected.
    const src = readFileSync(join(import.meta.dir, "../src/tools.ts"), "utf8");
    expect(src).toContain("ensureAcceptanceWebsite");
    expect(src).toContain("umami-acceptance-website-domain");
    // Never the operator's own website.
    expect(src).not.toContain("select website_id from website limit 1");
    // Idempotent, and the id must look like one.
    expect(src).toContain("where not exists");
    expect(src).toContain("[0-9a-f-]{36}");
  });



  const caddyfile = resource("tools/ansible/Caddyfile");
  const compose = resource("tools/ansible/compose.yml");
  const playbook = resource("tools/ansible/main.yml");

  test("caddy access logging is on and bounded", () => {
    // Access logging is off by default in Caddy, so a successful request left
    // no trace and ingestion had no request-level evidence to debug from.
    expect(caddyfile).toContain("log {");
    expect(caddyfile).toContain("output stdout");
    // On, but bounded: json-file never rotates on its own and this endpoint
    // writes a line per request.
    expect(compose).toContain("max-size");
    expect(compose).toContain("max-file");
  });

  test("caddy reload is convergent, not change-triggered", () => {
    // The Caddyfile is a single-file bind mount, so copy-by-rename leaves the
    // container on the old inode and `up -d` will not recreate an unchanged
    // service: the host file looked right while Caddy served the old config.
    expect(playbook).toContain("--force-recreate caddy");
    expect(playbook).toContain("sha256sum /etc/caddy/Caddyfile");
    // And it must run once the stack is up, or it recreates against a compose
    // file that has not been rendered yet.
    const converge = playbook.indexOf("Build and converge pinned containers");
    const reload = playbook.indexOf("--force-recreate caddy");
    const health = playbook.indexOf("Wait for Umami health endpoint");
    expect(converge).toBeGreaterThanOrEqual(0);
    expect(converge).toBeLessThan(reload);
    expect(reload).toBeLessThan(health);
  });

  test("the access log records the visitor, not the proxy", () => {
    // Behind the Cloudflare proxy every connection arrives from an edge
    // address, so without trusted_proxies Caddy attributes each request to
    // Cloudflare and the access log answers "who sent this?" with the proxy.
    expect(caddyfile).toContain("trusted_proxies static");
    expect(caddyfile).toContain("162.158.0.0/15");
    expect(caddyfile).toContain("2400:cb00::/32");
  });
});

// --- ssh ---------------------------------------------------------------------

describe("ssh-config", () => {
  const configFile = () => join(home, ".ssh", "config");

  test("the alias is the profile and the identity file keeps the tilde", () => {
    expect(sshConfig.hostAlias(fixture())).toBe("umami-fixture");
    expect(sshConfig.identityFile(fixture())).toBe("~/.ssh/umami-fixture");
    expect(sshConfig.identityFile(fixture())).not.toContain(home);
  });

  test("the marker is the alias alone, and owned-markers holds only it", () => {
    expect(sshConfig.beginMarker("umami-digitalocean")).toBe("# BEGIN umami-digitalocean ANSIBLE MANAGED BLOCK");
    expect(sshConfig.endMarker("umami-digitalocean")).toBe("# END umami-digitalocean ANSIBLE MANAGED BLOCK");
    // Born conforming: no marker migration is in flight.
    const owned = sshConfig.ownedMarkers("umami-digitalocean");
    expect([...owned.begin]).toEqual(["# BEGIN umami-digitalocean ANSIBLE MANAGED BLOCK"]);
    expect([...owned.end]).toEqual(["# END umami-digitalocean ANSIBLE MANAGED BLOCK"]);
  });

  test("host patterns are read from a Host line", () => {
    expect(sshConfig.hostPatterns("Host umami-fixture")).toEqual(["umami-fixture"]);
    expect(sshConfig.hostPatterns("  host   web umami-fixture  db ")).toEqual(["web", "umami-fixture", "db"]);
    expect(sshConfig.hostPatterns("    HostName 192.0.2.1")).toBeUndefined();
    expect(sshConfig.hostPatterns("Match host umami-fixture")).toBeUndefined();
  });

  test("a foreign stanza is found; our own block is not foreign", () => {
    expect(sshConfig.foreignStanzaLine(
      ["Host other", "    HostName 192.0.2.1", "", "Host umami-fixture"],
      "umami-fixture")).toBe(4);
    const alias = "umami-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, "    HostName 192.0.2.1",
       sshConfig.endMarker(alias)], alias)).toBeUndefined();
  });

  test("a stanza after our block is still foreign", () => {
    const alias = "umami-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, sshConfig.endMarker(alias),
       `Host ${alias}`], alias)).toBe(4);
  });

  test("a block under a package-prefixed marker is foreign", () => {
    // This package never wrote a `# BEGIN umami <alias>` marker, so a block
    // carrying one belongs to nobody this package knows.
    const alias = "umami-digitalocean";
    expect(sshConfig.foreignStanzaLine(
      [`# BEGIN umami ${alias} ANSIBLE MANAGED BLOCK`, `Host ${alias}`,
       `# END umami ${alias} ANSIBLE MANAGED BLOCK`], alias)).toBe(2);
  });

  test("multi-pattern host lines count; unrelated files are left alone", () => {
    expect(sshConfig.foreignStanzaLine(["Host web umami-fixture db"], "umami-fixture")).toBe(1);
    expect(sshConfig.foreignStanzaLine(["Host build", "Host umami-other"], "umami-fixture"))
      .toBeUndefined();
  });

  test("an option above the first Host is refused; comments and Host openers are fine", () => {
    expect(sshConfig.leadingOptionLine(["ServerAliveInterval 60", "Host a"])).toBe(1);
    expect(sshConfig.leadingOptionLine(["# comment", "", "IdentitiesOnly yes", "Host a"])).toBe(3);
    expect(sshConfig.leadingOptionLine(["Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# lead comment", "", "Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["Match host b", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# nothing here", ""])).toBeUndefined();
  });

  test("preflight refuses rather than overwrites", () => {
    const refused = sshConfig.preflight(fixture(), {
      adoptError: () => "already declares `Host x`",
      placementError: () => undefined,
    });
    expect(refused["red/exit"]).toBe(1);
    expect(String(refused["red/err"])).toContain("already declares");
    const clean = sshConfig.preflight(fixture(), {
      adoptError: () => undefined,
      placementError: () => undefined,
    });
    expect(clean["red/exit"]).toBeUndefined();
  });

  test("adopt error names the file and the line; our own block and a missing file pass", () => {
    expect(sshConfig.adoptError(fixture())).toBeUndefined();
    write(configFile(), "Host other\n    HostName 192.0.2.1\n\nHost umami-fixture\n    User root\n");
    const error = String(sshConfig.adoptError(fixture()));
    expect(error).toContain(configFile());
    expect(error).toContain("`Host umami-fixture` at line 4");
    expect(error).toContain("will not overwrite it");
    const alias = "umami-fixture";
    write(configFile(), `${sshConfig.beginMarker(alias)}\nHost ${alias}\n    HostName 192.0.2.1\n${sshConfig.endMarker(alias)}\n`);
    expect(sshConfig.adoptError(fixture())).toBeUndefined();
  });

  test("placement error names the file and the line and mentions the recovery", () => {
    write(configFile(), "# comment\n\n\nIdentitiesOnly yes\nHost a\n");
    const error = String(sshConfig.placementError(fixture()));
    expect(error).toContain(configFile());
    expect(error).toContain("line 4");
    expect(error).toContain("Host *");
  });

  test("preflight reads the redirected file end to end", () => {
    write(configFile(), "Host umami-fixture\n    HostName 192.0.2.1\n");
    const refused = sshConfig.preflight(fixture());
    expect(refused["red/exit"]).toBe(1);
    expect(String(refused["red/err"])).toContain("already declares");
    write(configFile(), "ServerAliveInterval 60\nHost a\n");
    const placed = sshConfig.preflight(fixture());
    expect(placed["red/exit"]).toBe(1);
    expect(String(placed["red/err"])).toContain("line 1");
    write(configFile(), "Host a\n    User root\n");
    expect(sshConfig.preflight(fixture())["red/exit"]).toBeUndefined();
  });

  test("build and dry-run never read the config", async () => {
    // The only readers are adoptError and placementError; a real create is
    // the one event that reaches them, and it stops at the credentials here.
    // A leading-option file that would refuse a real create must not disturb
    // a build or a dry-run.
    write(configFile(), "ServerAliveInterval 60\nHost umami-fixture\n");
    for (const opts of [fixture({ "red/event": "build" }),
                        keygen({ "red/event": "build" }),
                        fixture({ "red/event": "create", "red/dry-run": true })]) {
      expect((await workflow.startStep(opts, {}))["red/exit"]).toBe(0);
    }
  });

  test("the local play renders no address and follows keygen mode", () => {
    const data = tools.ansibleLocalData(fixture({ ip: "203.0.113.7", user:"root", name:"umami-fixture" }));
    expect(data["ssh-config-identity-file"]).toBe("~/.ssh/umami-fixture");
    expect(data["ssh-keygen"]).toBe(false);
    expect(tools.ansibleLocalData(keygen())["ssh-keygen"]).toBe(true);
  });

  test("the local stage renders three files", () => {
    const targets = tools.ansibleLocalSpecs(fixture()).map((s) => String(s.target));
    for (const file of ["/ansible.cfg", "/inventory.ini", "/main.yml"]) {
      expect(targets.some((t) => t.endsWith(file))).toBe(true);
    }
    expect(targets.every((t) => t.includes("umami-ansible-local"))).toBe(true);
  });


});

// --- workflow ----------------------------------------------------------------

describe("library compute", () => {
  test("all fixtures validate and use one library node", () => {
    for(const f of [keygen,fixture]) expect(validate.stateErrors(f())).toEqual([]);
    expect(compute.topology).toEqual([{role:null,count:1}]);
    expect(compute.requirements(keygen()).legacy_state_keys).toEqual(['umami-keygen-fixture/umami-infrastructure.tfstate']);
  });
  test("invalid compute inputs fail before execution", () => {
    for(const update of [{'provider-compute':'unsupported'},{'digitalocean-size':null},{'digitalocean-ssh-sources':[]},{'digitalocean-http-sources':['bad']}]) expect(validate.stateErrors(keygen(update)).length).toBeGreaterThan(0);
  });
  test("compute credentials are deferred to library state inspection", () => {
    const errors=validate.secretErrors(keygen()).join('\n');
    expect(errors).toContain('COLORS_PAR_CLOUDFLARE_API_TOKEN');
    expect(errors).not.toContain('COLORS_PAR_VULTR_API_KEY');
    expect(validate.tofuEnv(keygen(),'provider-compute')).toEqual({});
  });
  test("failed lifecycle diagnostics and observed node identity survive", () => {
    expect(compute.attach(keygen(),{status:'error',errors:['legacy compute state requires migration']})['red/err']).toBe('legacy compute state requires migration');
    const result=compute.attach(keygen(),{status:'present',cluster:{nodes:[{ip:'203.0.113.7',user:'ubuntu'}]},key:{private_key_path:'/tmp/explicit'}});
    expect(result.user).toBe('ubuntu');expect(result['ssh-private-key-path']).toBe('/tmp/explicit');
    expect(compute.attach(keygen(),{status:'destroyed'})['umami/already-destroyed']).toBe(true);
    expect(()=>compute.node({cluster:{nodes:[]}})).toThrow();
  });
  test("offline start needs no credentials", async()=> {
    for(const f of [keygen,fixture]) expect((await workflow.startStep(f({'red/event':'build'}),{}))['red/exit']).toBe(0);
  });
  test("managed build and external SSH identities are deterministic",()=> {
    expect(ssh.withMachineKey(keygen({'red/event':'build'}))['ssh-private-key-path']).toBe('/home/build-placeholder/.ssh/umami-keygen-fixture');
    expect(ssh.withMachineKey(fixture({'red/event':'build'}))).toEqual(fixture({'red/event':'build'}));
    expect(ssh.identityArgs(fixture())[1]).toBe('/home/build-placeholder/.ssh/operator-key');
  });
});

test('managed identity only in the local SSH block',()=>{
 const render=(opts:Opts)=>renderTemplate(tools.template('ansible-local','main.yml'),tools.ansibleLocalData(opts),tools.templateOpts);
 expect(render(keygen())).toContain('colors_keygen: true');expect(render(fixture())).toContain('colors_keygen: false');expect(render(fixture())).toContain('fcntl.flock');
});
