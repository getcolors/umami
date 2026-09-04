import * as ansible from "red/ansible";
import { stageDir } from "red/cli";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, scaffold, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import { runtime } from "red/runtime";
import type { Opts } from "red/workflow";
import { failed } from "red/workflow";
import { compute } from "package-once-red";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as validate from "./validate.ts";

import ansibleLocalCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import ansibleLocalInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import ansibleLocalMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import ansibleCfg from "../resources/tools/ansible/ansible.cfg" with { type: "text" };
import ansibleMain from "../resources/tools/ansible/main.yml" with { type: "text" };
import ansibleCleanup from "../resources/tools/ansible/cleanup.yml" with { type: "text" };
import ansibleCompose from "../resources/tools/ansible/compose.yml" with { type: "text" };
import ansibleCaddyfile from "../resources/tools/ansible/Caddyfile" with { type: "text" };
import ansibleBackup from "../resources/tools/ansible/backup" with { type: "text" };
import dnsMainTf from "../resources/tools/dns/main.tf" with { type: "text" };
import infrastructureDigitaloceanTf from "../resources/tools/infrastructure/digitalocean/main.tf" with { type: "text" };

export const infrastructureTool = "umami-infrastructure";
export const dnsTool = "umami-dns";
export const ansibleTool = "umami-ansible";
export const ansibleLocalTool = "umami-ansible-local";
export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "umami" });
}

// The template tree this colour carries, keyed the way green names its
// classpath resources: "<path>/<file>" with dots as directories.
const templates: Record<string, string> = {
  "ansible-local/ansible.cfg": ansibleLocalCfg,
  "ansible-local/inventory.ini": ansibleLocalInventory,
  "ansible-local/main.yml": ansibleLocalMain,
  "ansible/ansible.cfg": ansibleCfg,
  "ansible/main.yml": ansibleMain,
  "ansible/cleanup.yml": ansibleCleanup,
  "ansible/compose.yml": ansibleCompose,
  "ansible/Caddyfile": ansibleCaddyfile,
  "ansible/backup": ansibleBackup,
  "dns/main.tf": dnsMainTf,
  "infrastructure/digitalocean/main.tf": infrastructureDigitaloceanTf,
};

export function template(path: string, file: string): Template {
  const name = `${path.replaceAll(".", "/")}/${file}`;
  const content = templates[name];
  if (content === undefined) throw new Error(`template not found: ${name}`);
  return { name, content };
}

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

// The source lists as validate parses them, so the template and the
// validator can never disagree about what an entry is. ONCE's.
export const cidrs = validate.cidrs;

export function credentialEnv(opts: Opts, ...slots: string[]): Record<string, string> | undefined {
  const mapping: Record<string, string> = Object.assign(
    {},
    ...[...slots, "provider-backend"].map((slot) => validate.tofuEnv(opts, slot)),
  );
  const env: Record<string, string> = {};
  for (const [key, envVar] of Object.entries(mapping)) {
    const value = String(opts[key] ?? "");
    if (value.length > 0) env[envVar] = value;
  }
  return Object.keys(env).length > 0 ? env : undefined;
}

export const backendCredentialEnv = (opts: Opts) => credentialEnv(opts);

// What `build` and `--dry-run` render in place of a compute output: the
// documentation address, shaped like the selected provider's real `params` so
// every later stage sees the same keys either way. ONCE's.
export const fallbackParams = compute.fallbackParams;

// Refuse to hand 192.0.2.10 to Ansible on a real converge whose compute output
// carries no `ip`. ONCE's; `infrastructureStep` is what wires it.
export const resolvedCompute = compute.resolvedCompute;

// `<provider>-<suffix>`, the selected provider's key. ONCE's, via validate.
export const computeKey = validate.computeKey;

// The machine's name: `digitalocean-name` when present, else the profile.
// ONCE's, via validate; the template and the playbook derive every label from
// it.
export const computeName = validate.computeName;

// ---------------------------------------------------------------- compute

// Template values for the compute stage. The name, the keypair mode and the
// source lists are resolved here once, so a template interpolates values and
// never branches on which provider it belongs to.
export function infrastructureData(opts: Opts): Opts {
  return {
    ...opts,
    "ssh-keygen": validate.keygen(opts),
    "compute-name": computeName(opts),
    "ssh-sources-hcl": tofu.hclList(cidrs(opts, computeKey(opts, "ssh-sources"))),
    "http-sources-hcl": tofu.hclList(cidrs(opts, computeKey(opts, "http-sources"))),
  };
}

// Providers are selected by template directory, not by conditionals inside one
// file: `tools/infrastructure/<provider>/main.tf`, rendered to the one
// `<stage>/main.tf` every later stage reads.
export function infrastructureSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, infrastructureTool);
  return [spec(template(`infrastructure.${opts["provider-compute"]}`, "main.tf"),
               `${dir}/main.tf`, infrastructureData(opts))];
}

export async function infrastructureStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, infrastructureTool);
  const result = await tofu.tofuWithSpec(opts, infrastructureSpecs(opts),
    { dir, env: credentialEnv(opts, "provider-compute") });
  if (failed(result)) return result;
  if (opts["red/event"] === "build") return { ...result, ...fallbackParams(opts) };
  if (opts["red/event"] === "delete") return result;
  return resolvedCompute(result, fallbackParams(opts), compute.outputParams(result));
}

// -------------------------------------------------------------------- dns

export function dnsData(opts: Opts): Opts {
  const host = String(opts["umami-host"] ?? "");
  const parts = host.split(".");
  const zone = opts["cloudflare-zone"] ??
    (parts.length > 2 ? parts.slice(1).join(".") : host);
  return {
    ...opts,
    ip: opts.ip ?? fallbackParams(opts).ip,
    "cloudflare-zone": zone,
    // Kept in step with the workflow defaults, which seed this key and
    // therefore decide it on the real path -- this fallback only runs when
    // dnsData is called with bare opts, as the tests do.
    "cloudflare-proxied": opts["cloudflare-proxied"] != null ? opts["cloudflare-proxied"] : true,
  };
}

export function dnsJson(opts: Opts): string {
  return tofu.constructsJson([
    tofu.construct("resource", "cloudflare_dns_record", "umami", {
      zone_id: "${data.cloudflare_zone.zone.id}",
      name: opts["umami-host"], content: opts.ip, type: "A",
      proxied: Boolean(opts["cloudflare-proxied"]), ttl: 1,
    }),
  ]);
}

export async function dnsStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, dnsTool);
  const data = dnsData(opts);
  const specs = [
    spec(template("dns", "main.tf"), `${dir}/main.tf`, data),
    rawSpec(`${dir}/record.tf.json`, dnsJson(data)),
  ];
  return tofu.tofuWithSpec(opts, specs, { dir, env: credentialEnv(opts, "provider-dns") });
}

// ---------------------------------------------------------------- ansible

function pretty(value: unknown, indent = 0): string {
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return `[ ${value.map((item) => pretty(item, indent)).join(", ")} ]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    const pad = " ".repeat(indent + 2);
    return `{\n${entries
      .map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${pretty(nested, indent + 2)}`)
      .join(",\n")}\n${" ".repeat(indent)}}`;
  }
  return JSON.stringify(value ?? null);
}

// ---------------------------------------------------------- ansible (local)

// Only what a `build` genuinely knows. The address, the user and the alias are
// run-time facts and reach the play as extra-vars instead, so the rendered
// playbook carries no IP and is identical on every workstation (SSH Config
// Standard §6).
export function ansibleLocalData(opts: Opts): Opts {
  return {
    ...opts,
    "ssh-keygen": validate.keygen(opts),
    "ssh-config-identity-file": sshConfig.identityFile(opts),
  };
}

export function ansibleLocalSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleLocalTool);
  const data = ansibleLocalData(opts);
  return [
    spec(template("ansible-local", "ansible.cfg"), `${dir}/ansible.cfg`, data),
    spec(template("ansible-local", "inventory.ini"), `${dir}/inventory.ini`, data),
    spec(template("ansible-local", "main.yml"), `${dir}/main.yml`, data),
  ];
}

// Write or remove the `~/.ssh/config` block. The same playbook serves both
// events; `block_state` is what distinguishes them.
export async function ansibleLocalStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleLocalTool);
  const isDelete = opts["red/event"] === "delete";
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.ini",
    playbooks: { create: "main.yml", delete: "main.yml" },
    extraVars: {
      host_alias: sshConfig.hostAlias(opts),
      ip: opts.ip ?? fallbackParams(opts).ip,
      user: opts.user ?? "root",
      block_state: isDelete ? "absent" : "present",
    },
  }, ansibleLocalSpecs(opts));
}

// ---------------------------------------------------------------- ansible

export function inventory(opts: Opts): string {
  return pretty({
    all: {
      children: {
        umami: {
          hosts: {
            [String(opts.profile)]: {
              ansible_host: opts.ip ?? "192.0.2.10",
              ansible_user: "root",
            },
          },
        },
      },
    },
  });
}

// Template values for the Ansible stage. `ssh-private-key-path` reaches
// ansible.cfg so convergence uses the deployment's own key in keygen mode,
// where nothing guarantees an agent holds it; `compute-name` is the hostname
// the playbook sets.
export function ansibleData(opts: Opts): Opts {
  return {
    ...opts,
    ip: opts.ip ?? "192.0.2.10",
    "ssh-keygen": validate.keygen(opts),
    "compute-name": computeName(opts),
    "umami-image": opts["umami-image"] ??
      `ghcr.io/umami-software/umami:postgresql-${opts["umami-version"] ?? "v2.14.0"}`,
    "postgres-image": opts["postgres-image"] ??
      `postgres:${opts["postgres-version"] ?? "17"}-alpine`,
    "postgres-db": opts["postgres-database"] ?? opts["postgres-db"] ?? "umami",
    "postgres-user": opts["postgres-user"] ?? "umami",
    "postgres-data-dir": opts["postgres-data-dir"] ?? "/var/lib/umami/postgres",
    "umami-port": opts["umami-port"] ?? 3000,
    "backup-dir": opts["backup-dir"] ?? opts["umami-backup-dir"] ?? "/var/backups/umami",
    "backup-r2-bucket": opts["backup-r2-bucket"] ?? opts["umami-backup-r2-bucket"] ?? "umami-backup",
    "backup-r2-endpoint": opts["backup-r2-endpoint"] ?? opts["umami-backup-r2-endpoint"],
    "backup-oncalendar": opts["backup-oncalendar"] ?? opts["umami-backup-oncalendar"] ?? "*-*-* 03:00:00",
    "backup-retention-days": opts["backup-retention-days"] ?? opts["umami-backup-retention-days"] ?? 7,
  };
}

export function ansibleSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleTool);
  const data = ansibleData(opts);
  return [
    ...["ansible.cfg", "main.yml", "cleanup.yml", "compose.yml", "Caddyfile", "backup"]
      .map((name) => spec(template("ansible", name), `${dir}/${name}`, data)),
    rawSpec(`${dir}/inventory.json`, inventory(data)),
  ];
}

export async function ansibleStep(
  opts: Opts,
  runFn: typeof ansible.ansibleWithSpec = ansible.ansibleWithSpec,
): Promise<Opts> {
  const dir = toolDir(opts, ansibleTool);
  if (opts["red/event"] === "delete" && !opts.ip) {
    // No compute in state: there is no host to clean up, and the rendered
    // inventory would fall back to 192.0.2.10. Remove the rendered tree the
    // way a completed cleanup would and let the teardown continue.
    return { ...scaffold(opts, ansibleSpecs(opts)),
             "red/exit": 0, "umami/cleanup": "skipped-no-compute" };
  }
  return runFn(opts, {
    dir,
    inventory: "inventory.json",
    playbooks: { create: "main.yml", delete: "cleanup.yml" },
    hostKeyChecking: false,
  }, ansibleSpecs(opts));
}

// ------------------------------------------------------------- acceptance
//
// Every claim this step reports must be one it checked. TLS is verified (never
// `curl -k`), an ingested event is read back out of PostgreSQL rather than
// inferred from a status code, and the backup drill is confirmed by a fresh
// object in R2 rather than by systemd reporting that it started something.

export async function httpStatus(args: string[]): Promise<string | undefined> {
  const r = await runtime.exec(
    ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", ...args],
    { timeoutMs: 20000 });
  return r.exit === 0 ? String(r.out ?? "").trim() : undefined;
}

// Run `command` on the host over ssh. The deployment's own key is selected in
// keygen mode (`ssh.identityArgs`), because nothing guarantees an agent holds
// it; opt-out mode adds nothing and relies on the operator's identities.
export async function sshOut(opts: Opts, ip: unknown, command: string, timeoutMs: number): Promise<string | undefined> {
  const r = await runtime.exec(
    ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10",
     ...ssh.identityArgs(opts), `root@${ip}`, command],
    { timeoutMs });
  return r.exit === 0 ? String(r.out ?? "").trim() : undefined;
}

export async function sql(opts: Opts, ip: unknown, query: string): Promise<string | undefined> {
  const out = String((await sshOut(opts, ip,
    `cd /opt/umami && docker compose exec -T postgres psql -U ${opts["postgres-user"]}` +
    ` -d ${opts["postgres-db"]} -tAc '${query}'`, 30000)) ?? "");
  return out.length > 0 ? out : undefined;
}

function parseLong(s: string): number | undefined {
  return /^[+-]?\d+$/.test(s) ? Number(s) : undefined;
}

export async function eventCount(opts: Opts, ip: unknown): Promise<number | undefined> {
  const out = await sql(opts, ip, "select count(*) from website_event");
  return out === undefined ? undefined : parseLong(out);
}

export const acceptanceWebsiteId = "00000000-c010-4000-8000-000000000001";

// A dedicated throwaway website, created on demand. Without one the step
// reports "not-configured" and sends nothing, so the synthetic request is
// never exercised -- which is how the sibling Rybbit package carried a payload
// the API had always rejected. Sending to a real website instead would write a
// test pageview into the operator's analytics on every converge.
//
// Literals are dollar-quoted because the query travels inside single quotes in
// a remote shell, and psql prints the INSERT tag before the SELECT result, so
// the id comes off the last line.
export async function ensureAcceptanceWebsite(opts: Opts, ip: unknown): Promise<string | undefined> {
  const configured = String(opts["umami-acceptance-website-domain"] ?? "").trim();
  const domain = configured.length > 0 ? configured : "colors-acceptance.invalid";
  const owner = '(select user_id from "user" limit 1)';
  const out = await sql(opts, ip,
    "insert into website (website_id, name, domain, created_by, user_id) " +
    `select $$${acceptanceWebsiteId}$$::uuid, $$colors-acceptance$$, ` +
    `$$${domain}$$, ${owner}, ${owner} ` +
    "where not exists (select 1 from website " +
    `where website_id = $$${acceptanceWebsiteId}$$::uuid); ` +
    "select website_id from website " +
    `where website_id = $$${acceptanceWebsiteId}$$::uuid`);
  if (out === undefined) return undefined;
  const lines = out.split(/\r?\n/);
  const candidate = String(lines[lines.length - 1] ?? "").trim();
  return /^[0-9a-f-]{36}$/.test(candidate) ? candidate : undefined;
}

export async function waitHealth(url: string, attempts: number): Promise<boolean> {
  for (let remaining = attempts; ; remaining -= 1) {
    const r = await runtime.exec(["curl", "-fsS", `${url}/api/heartbeat`], { timeoutMs: 10000 });
    if (r.exit === 0) return true;
    if (remaining <= 0) return false;
    await Bun.sleep(5000);
  }
}

// Umami seeds admin/umami. A deployment that still answers to it is not one
// whose acceptance may pass.
export async function defaultAdminActive(base: string): Promise<boolean> {
  return (await httpStatus(["-X", "POST", "-H", "content-type: application/json",
                            "--data", '{"username":"admin","password":"umami"}',
                            `${base}/api/auth/login`])) === "200";
}

export async function sendEvent(base: string, host: unknown, website: string): Promise<string | undefined> {
  return httpStatus(["-X", "POST", "-H", "content-type: application/json",
                     "-H", "User-Agent: Mozilla/5.0 (Colors acceptance)",
                     "--data", JSON.stringify({
                       type: "event",
                       payload: { website, hostname: host,
                                  url: "/colors-acceptance",
                                  name: "colors-acceptance" },
                     }),
                     `${base}/api/send`]);
}

export function ingestionVerdict(
  status: string | undefined | null,
  before: unknown,
  after: unknown,
): string {
  if (status == null) return "unreachable";
  if (typeof before === "number" && Number.isInteger(before) &&
      typeof after === "number" && Number.isInteger(after) && after > before) {
    return "ingested";
  }
  if (/^2\d\d$/.test(String(status))) return "dropped";
  return "rejected";
}

export async function waitIngested(
  opts: Opts, ip: unknown, baseline: number, attempts: number,
): Promise<number | undefined> {
  for (let remaining = attempts; ; remaining -= 1) {
    const after = await eventCount(opts, ip);
    if (typeof after === "number" && Number.isInteger(after) && after > baseline) return after;
    if (remaining <= 0) return after;
    await Bun.sleep(3000);
  }
}

export const rcloneEnv =
  "RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare " +
  "RCLONE_CONFIG_R2_REGION=auto RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true";

export interface BackupEntry {
  Size?: number;
  ModTime?: string;
}

// Objects under this profile's prefix, listed on the droplet with the
// credentials the backup unit already holds.
export async function backupListing(opts: Opts, ip: unknown): Promise<BackupEntry[] | undefined> {
  const out = await sshOut(opts, ip,
    `set -a; . /etc/umami-backup.env; set +a; ${rcloneEnv}` +
    ' RCLONE_CONFIG_R2_ACCESS_KEY_ID="$BACKUP_R2_ACCESS_KEY_ID"' +
    ' RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$BACKUP_R2_SECRET_ACCESS_KEY"' +
    ` RCLONE_CONFIG_R2_ENDPOINT="${opts["backup-r2-endpoint"]}"` +
    ` rclone lsjson --files-only r2:${opts["backup-r2-bucket"]}` +
    `/${opts.profile}`,
    120000);
  if (out === undefined || out.length === 0) return undefined;
  try {
    return JSON.parse(out) as BackupEntry[];
  } catch {
    return undefined;
  }
}

export function parseInstant(s: unknown): Date | undefined {
  // Like java.time.OffsetDateTime/parse, an offset is required; a string
  // without one is not an instant.
  const text = String(s ?? "");
  if (!/(Z|[+-]\d{2}:\d{2})$/.test(text)) return undefined;
  const t = new Date(text);
  return Number.isNaN(t.getTime()) ? undefined : t;
}

export function freshBackup(entries: BackupEntry[] | undefined | null, since: Date): boolean {
  return Boolean(entries?.some(({ Size, ModTime }) => {
    if (!((Size ?? 0) > 0)) return false;
    const t = parseInstant(ModTime);
    return t !== undefined && t.getTime() >= since.getTime();
  }));
}

export async function runBackup(opts: Opts, ip: unknown): Promise<string | undefined> {
  return sshOut(opts, ip,
    "systemctl start umami-backup.service && systemctl is-active umami-backup.timer",
    300000);
}

export async function acceptanceStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] !== "create") return { ...opts, "red/exit": 0 };
  const base = `https://${opts["umami-host"]}`;
  const ip = opts.ip;
  const since = new Date(Date.now() - 120000);
  if (!(await waitHealth(base, 60))) {
    return { ...opts, "red/exit": 1,
      "red/err": "HTTPS heartbeat did not become ready with a valid certificate" };
  }
  if (await defaultAdminActive(base)) {
    return { ...opts, "red/exit": 1,
      "red/err": "the seeded admin/umami credentials still authenticate; rotate them" };
  }
  const website = await ensureAcceptanceWebsite(opts, ip);
  const before = await eventCount(opts, ip);
  if (!(typeof before === "number" && Number.isInteger(before))) {
    return { ...opts, "red/exit": 1,
      "red/err": "could not read website_event from PostgreSQL to verify ingestion" };
  }
  let verdict: string;
  if (!website) {
    verdict = "not-configured";
  } else {
    const status = await sendEvent(base, opts["umami-host"], website);
    const after = await waitIngested(opts, ip, before, 10);
    verdict = ingestionVerdict(status, before, after);
  }
  if (["dropped", "rejected", "unreachable"].includes(verdict)) {
    return { ...opts, "red/exit": 1,
      "red/err": `synthetic event was not ingested: ${verdict}` };
  }
  if ((await runBackup(opts, ip)) === undefined) {
    return { ...opts, "red/exit": 1, "red/err": "backup unit or timer is not healthy" };
  }
  if (!freshBackup(await backupListing(opts, ip), since)) {
    return { ...opts, "red/exit": 1,
      "red/err": `no backup object newer than this run under r2:${opts["backup-r2-bucket"]}/${opts.profile}` };
  }
  return { ...opts, "red/exit": 0,
    "umami/acceptance": { health: "ok", "default-admin": "rejected",
                          event: verdict, backup: "verified-in-r2" } };
}
