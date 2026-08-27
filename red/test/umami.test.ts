import { describe, expect, test } from "bun:test";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Opts } from "red/workflow";
import * as tools from "../src/tools.ts";
import * as validate from "../src/validate.ts";
import * as workflow from "../src/workflow.ts";

const fixtureFile = join(import.meta.dir, "../../test/fixtures/colors.yml");

function fixture(overrides: Opts = {}): Opts {
  const text = readFileSync(fixtureFile, "utf8").replaceAll("WORKDIR", ".colors");
  return { ...(Bun.YAML.parse(text) as Opts), ...overrides };
}

// --- desired state -----------------------------------------------------------

describe("validate", () => {
  test("the fixture is valid", () => {
    expect(validate.stateErrors(fixture())).toEqual([]);
  });

  test("reports all errors at once", () => {
    const errors = validate.stateErrors(fixture({
      "umami-host": "bad", "caddy-image": "floating",
      "backup-retention-days": -1,
      "provider-dns": "other", "digitalocean-vpc-uuid": "forbidden",
    }));
    expect(errors.length).toBeGreaterThanOrEqual(5);
    for (const part of ["host", "image", "retention", "provider-dns", "vpc-uuid"]) {
      expect(errors.some((e) => e.includes(part))).toBe(true);
    }
  });

  test("forbids vpc configuration", () => {
    expect(validate.stateErrors(fixture({ "digitalocean-vpc-cidr": "10.0.0.0/16" }))
      .some((e) => e.includes("must be absent"))).toBe(true);
  });

  test("profile overlay is refused", () => {
    expect(validate.envErrors({ COLORS_PAR_PROFILE: "other" }).length).toBe(1);
    expect(validate.envErrors({})).toEqual([]);
  });

  test("names all package secrets", () => {
    const errors = validate.secretErrors(fixture({ "provider-backend": "r2" })).join("\n");
    for (const name of ["COLORS_PAR_DO_TOKEN", "COLORS_PAR_CLOUDFLARE_API_TOKEN",
                        "COLORS_PAR_R2_ACCESS_KEY_ID", "COLORS_PAR_R2_SECRET_ACCESS_KEY",
                        "COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID",
                        "COLORS_PAR_BACKUP_R2_SECRET_ACCESS_KEY",
                        "COLORS_PAR_POSTGRES_PASSWORD", "COLORS_PAR_APP_SECRET_KEY",
                        "COLORS_PAR_UMAMI_ADMIN_PASSWORD"]) {
      expect(errors).toContain(name);
    }
  });

  test("accepts the alternate app secret name", () => {
    const errors = validate.secretErrors(fixture({ "umami-app-secret": "alternate" })).join("\n");
    expect(errors).not.toContain("COLORS_PAR_APP_SECRET_KEY");
  });

  test("the compose template carries no default credential", () => {
    const compose = readFileSync(
      join(import.meta.dir, "../resources/tools/ansible/compose.yml"), "utf8");
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
      expect(result["red/exit"]).toBe(0);
      expect(result["umami/cleanup"]).toBe("skipped-no-compute");
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
        fixture({ "red/event": "delete", ip: "203.0.113.7", workdir: work }),
        async (opts) => ({ ...opts, "red/exit": 0, "ran-against": opts.ip }));
      expect(result["ran-against"]).toBe("203.0.113.7");
    } finally {
      rmSync(work, { recursive: true, force: true });
    }
  });

  test("infrastructure discovers the default vpc", () => {
    const data = tools.infrastructureData(fixture());
    expect(tools.cidrs(data, "digitalocean-http-sources")).toEqual(["0.0.0.0/0", "::/0"]);
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

  const backupScript = readFileSync(
    join(import.meta.dir, "../resources/tools/ansible/backup"), "utf8");

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

  test("a missing compute output fails loudly", () => {
    // The documentation address belongs to build and dry-run. Merging it into
    // a real converge would point Ansible at TEST-NET instead of failing.
    expect(tools.resolvedCompute({}, { ip: "192.0.2.10" }, { ip: "1.2.3.4" }).ip).toBe("1.2.3.4");
    expect(tools.resolvedCompute({}, { ip: "192.0.2.10" }, undefined)["red/exit"]).toBe(1);
    expect(tools.resolvedCompute({}, { ip: "192.0.2.10" }, {})["red/exit"]).toBe(1);
    expect(tools.resolvedCompute({}, { ip: "192.0.2.10" }, { ip: "5.6.7.8" })["red/exit"]).toBeUndefined();
  });

  const caddyfile = readFileSync(
    join(import.meta.dir, "../resources/tools/ansible/Caddyfile"), "utf8");
  const compose = readFileSync(
    join(import.meta.dir, "../resources/tools/ansible/compose.yml"), "utf8");
  const playbook = readFileSync(
    join(import.meta.dir, "../resources/tools/ansible/main.yml"), "utf8");

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

// --- workflow ----------------------------------------------------------------

// A fixture that passes real-delete preflight: guard lifted, secrets present.
function deletableFixture(overrides: Opts = {}): Opts {
  return fixture({
    "compute-prevent-destroy": false,
    "do-token": "t", "cloudflare-api-token": "t",
    "postgres-password": "p", "umami-admin-password": "p",
    "app-secret-key": "s",
    "backup-r2-access-key-id": "k",
    "backup-r2-secret-access-key": "s",
    ...overrides,
  });
}

describe("workflow", () => {
  test("build and dry-run need no credentials", async () => {
    expect((await workflow.startStep(fixture({ "red/event": "build" }), {}))["red/exit"]).toBe(0);
    expect((await workflow.startStep(
      fixture({ "red/event": "create", "red/dry-run": true }), {}))["red/exit"]).toBe(0);
  });

  test("a real create requires credentials", async () => {
    const result = await workflow.startStep(
      fixture({ "provider-backend": "r2", "red/event": "create" }), {});
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).toContain("COLORS_PAR_DO_TOKEN");
    expect(String(result["red/err"])).toContain("COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID");
  });

  test("delete is protected", async () => {
    const result = await workflow.startStep(fixture({ "red/event": "delete" }), {});
    expect(result["red/exit"]).toBe(2);
    expect(String(result["red/err"])).toContain("COMPUTE_PREVENT_DESTROY");
  });

  test("delete fails loudly when state is unreadable", async () => {
    // Swallowing a failed state read is how a live teardown ended up pointing
    // the cleanup playbook at 192.0.2.10: stale backend credentials made
    // `tofu output` fail, nothing was merged, and the inventory fell back to
    // TEST-NET. The failure must surface here, before any playbook runs.
    const result = await workflow.startStep(
      deletableFixture({ "red/event": "delete" }), {},
      async () => { throw new Error("Unauthorized"); });
    expect(result["red/exit"]).toBe(1);
    expect(String(result["red/err"])).toContain("Unauthorized");
    expect(String(result["red/err"])).toContain("COLORS_PAR_IP");
  });

  test("delete with an explicit ip skips the state read", async () => {
    // COLORS_PAR_IP is the operator's escape hatch when the state backend is
    // unreachable; it must not require the read it exists to replace.
    const result = await workflow.startStep(
      deletableFixture({ "red/event": "delete", ip: "203.0.113.7" }), {},
      async () => { throw new Error("must not be called"); });
    expect(result["red/exit"]).toBe(0);
    expect(result.ip).toBe("203.0.113.7");
  });

  test("delete with an empty state proceeds without an address", async () => {
    // State readable, no compute recorded: the instance is already gone, the
    // cleanup step skips itself, and the rest of the teardown still runs.
    const result = await workflow.startStep(
      deletableFixture({ "red/event": "delete" }), {},
      async () => undefined);
    expect(result["red/exit"]).toBe(0);
    expect(result.ip).toBeUndefined();
  });

  test("the graph orders the private stack", () => {
    const next = (step: string, event: string) =>
      (workflow.wireFn(step, { "red/event": event }) ?? []).slice(1);
    expect(next("umami/start", "create")).toEqual(["umami/infrastructure"]);
    expect(next("umami/infrastructure", "create")).toEqual(["umami/dns"]);
    expect(next("umami/start", "delete")).toEqual(["umami/ansible"]);
  });

  test("the proxying default lives here, not only in dnsData", () => {
    // This map seeds cloudflare-proxied, so tools.dnsData always sees the key
    // supplied and its own fallback never runs on the real path. Flipping only
    // the fallback would change nothing and move no golden -- assert the value
    // that actually decides it.
    expect(workflow.defaults["cloudflare-proxied"]).toBe(true);
  });
});
