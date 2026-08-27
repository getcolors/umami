import * as ansible from "red/ansible";
import { stageDir } from "red/cli";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, scaffold, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import { runtime } from "red/runtime";
import type { Opts } from "red/workflow";
import { failed } from "red/workflow";
import * as validate from "./validate.ts";

import ansibleCfg from "../resources/tools/ansible/ansible.cfg" with { type: "text" };
import ansibleMain from "../resources/tools/ansible/main.yml" with { type: "text" };
import ansibleCleanup from "../resources/tools/ansible/cleanup.yml" with { type: "text" };
import ansibleCompose from "../resources/tools/ansible/compose.yml" with { type: "text" };
import ansibleCaddyfile from "../resources/tools/ansible/Caddyfile" with { type: "text" };
import ansibleBackup from "../resources/tools/ansible/backup" with { type: "text" };
import dnsMainTf from "../resources/tools/dns/main.tf" with { type: "text" };
import infrastructureMainTf from "../resources/tools/infrastructure/main.tf" with { type: "text" };

export const infrastructureTool = "umami-infrastructure";
export const dnsTool = "umami-dns";
export const ansibleTool = "umami-ansible";
export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "umami" });
}

const template = (name: string, content: string): Template => ({ name, content });

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

export function cidrs(opts: Opts, key: string): string[] {
  const value = opts[key];
  const parts = Array.isArray(value) ? value : String(value ?? "").split(/[,\s]+/);
  return parts.map((part) => String(part).trim()).filter((part) => part.length > 0);
}

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

export function fallbackParams(opts: Opts): Record<string, unknown> {
  return { ip: "192.0.2.10", user: "root", sudoer: "root", name: opts.profile };
}

export function outputParams(result: Opts): Record<string, unknown> | undefined {
  const params = (result["tofu/outputs"] as Record<string, unknown> | undefined)?.params;
  return params && typeof params === "object" ? params as Record<string, unknown> : undefined;
}

// ---------------------------------------------------------------- compute

export function infrastructureData(opts: Opts): Opts {
  return {
    ...opts,
    "ssh-sources-hcl": tofu.hclList(cidrs(opts, "digitalocean-ssh-sources")),
    "http-sources-hcl": tofu.hclList(cidrs(opts, "digitalocean-http-sources")),
  };
}

// Refuse to hand 192.0.2.10 to Ansible. That is the documentation address the
// credential-free build and dry-run paths render with; on a real converge a
// missing compute output must fail loudly rather than quietly point the whole
// playbook at TEST-NET.
export function resolvedCompute(
  result: Opts,
  fallback: Record<string, unknown>,
  outputs: Record<string, unknown> | undefined,
): Opts {
  if (outputs?.ip) return { ...result, ...fallback, ...outputs };
  return {
    ...result, "red/exit": 1,
    "red/err": "compute produced no ip output; refusing to converge against the documentation address",
  };
}

export async function infrastructureStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, infrastructureTool);
  const specs = [spec(template("infrastructure/main.tf", infrastructureMainTf),
                      `${dir}/main.tf`, infrastructureData(opts))];
  const result = await tofu.tofuWithSpec(opts, specs,
    { dir, env: credentialEnv(opts, "provider-compute") });
  if (failed(result)) return result;
  if (opts["red/event"] === "build") return { ...result, ...fallbackParams(opts) };
  if (opts["red/event"] === "delete") return result;
  return resolvedCompute(result, fallbackParams(opts), outputParams(result));
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
    spec(template("dns/main.tf", dnsMainTf), `${dir}/main.tf`, data),
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

export function ansibleData(opts: Opts): Opts {
  return {
    ...opts,
    ip: opts.ip ?? "192.0.2.10",
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
  const files: Array<[string, string]> = [
    ["ansible.cfg", ansibleCfg],
    ["main.yml", ansibleMain],
    ["cleanup.yml", ansibleCleanup],
    ["compose.yml", ansibleCompose],
    ["Caddyfile", ansibleCaddyfile],
    ["backup", ansibleBackup],
  ];
  return [
    ...files.map(([name, content]) =>
      spec(template(`ansible/${name}`, content), `${dir}/${name}`, data)),
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

export async function sshOut(ip: unknown, command: string, timeoutMs: number): Promise<string | undefined> {
  const r = await runtime.exec(
    ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10",
     `root@${ip}`, command],
    { timeoutMs });
  return r.exit === 0 ? String(r.out ?? "").trim() : undefined;
}

export async function sql(opts: Opts, ip: unknown, query: string): Promise<string | undefined> {
  const out = String((await sshOut(ip,
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
  const out = await sshOut(ip,
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

export async function runBackup(ip: unknown): Promise<string | undefined> {
  return sshOut(ip,
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
  if ((await runBackup(ip)) === undefined) {
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
