import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, workflow, type Opts, type WireDecl } from "red/workflow";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "provider-compute": "digitalocean", "provider-dns": "cloudflare",
  "provider-backend": "local", "compute-prevent-destroy": true,
  workdir: ".colors", "umami-port": 3000, "postgres-port": 5432,
  "postgres-db": "umami", "postgres-user": "umami",
  "postgres-data-dir": "/var/lib/umami/postgres",
  "backup-dir": "/var/backups/umami",
  "backup-r2-bucket": "umami-backup",
  "backup-r2-region": "auto",
  "backup-oncalendar": "*-*-* 03:00:00",
  "backup-retention-days": 7,
  "caddy-image": "caddy:2.11.4",
  // Proxied by default: an unproxied record publishes the droplet's address.
  // Note this map is the effective default -- it seeds the key, so
  // tools.dnsData always sees it supplied and its own fallback never runs.
  // Both have to agree.
  "cloudflare-proxied": true,
};

// Compute params recorded in the infrastructure state; undefined when the
// state holds none. An unreadable backend throws — the delete path treats that
// as fatal rather than falling back to the documentation address.
export async function stateOutput(opts: Opts): Promise<Record<string, unknown> | undefined> {
  const outputs = await tofu.outputs(
    tools.toolDir(opts, tools.infrastructureTool),
    tools.backendCredentialEnv(opts),
  );
  const params = (outputs as Record<string, unknown> | undefined)?.params;
  return params && typeof params === "object" ? params as Record<string, unknown> : undefined;
}

export type StateFn = (opts: Opts) => Promise<Record<string, unknown> | undefined>;

// A real delete runs the ansible cleanup before the infrastructure step, so
// the instance address must come out of the existing state here. An explicit
// ip (COLORS_PAR_IP) skips the read; a readable state without compute params
// leaves ip unset and the cleanup step skips itself; an unreadable backend
// fails loudly — swallowing it is how a live teardown ended up converging
// against 192.0.2.10.
export async function adoptState(opts: Opts, stateFn: StateFn = stateOutput): Promise<Opts> {
  if (opts.ip) return { ...opts, "red/exit": 0 };
  try {
    return { ...opts, ...(await stateFn(opts) ?? {}), "red/exit": 0 };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return {
      ...opts, "red/exit": 1,
      "red/err": "could not read the infrastructure state for " +
        `the delete cleanup: ${message}\n` +
        "fix the backend credentials, or supply " +
        `${parName("ip")} to address the instance directly`,
    };
  }
}

export async function startStep(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
  stateFn: StateFn = stateOutput,
): Promise<Opts> {
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_opts, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      (current, _environment, { event, real }) =>
        real && (event === "create" || event === "delete")
          ? validate.secretErrors(current)
          : [],
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`]
          : [],
    ],
    afterValidate: async (current, _environment, { event, real }) => {
      if (real && event === "delete") return adoptState(current, stateFn);
      return { ...current, "red/exit": 0 };
    },
  }, env);
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "umami/start": [startStep, "umami/ansible"],
      "umami/ansible": [tools.ansibleStep, "umami/dns"],
      "umami/dns": [tools.dnsStep, "umami/infrastructure"],
      "umami/infrastructure": [tools.infrastructureStep],
    };
    return graph[step];
  }
  const graph: Record<string, WireDecl> = {
    "umami/start": [startStep, "umami/infrastructure"],
    "umami/infrastructure": [tools.infrastructureStep, "umami/dns"],
    "umami/dns": [tools.dnsStep, "umami/ansible"],
    "umami/ansible": [tools.ansibleStep, "umami/acceptance"],
    "umami/acceptance": [tools.acceptanceStep],
  };
  return graph[step];
}

export function backendAdvice(tool: string) {
  return tofu.conventionalBackendAdvice({
    dir: (opts) => tools.toolDir(opts, tool),
    key: (opts) => `${opts.profile ?? ""}/${tool}.tfstate`,
  });
}

export const sideEffecting = [
  "umami/infrastructure", "umami/dns", "umami/ansible", "umami/acceptance",
];

function create() {
  let wf = workflow({ start: "umami/start", wireFn });
  wf = adviceAdd(wf, "umami/infrastructure", "before", "umami.workflow/backend",
    backendAdvice(tools.infrastructureTool));
  wf = adviceAdd(wf, "umami/dns", "before", "umami.workflow/backend",
    backendAdvice(tools.dnsTool));
  return dryRun.advise(progress.advise(wf), sideEffecting);
}

export const umamiWorkflow = create();
