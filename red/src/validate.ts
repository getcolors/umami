// Desired-state and credential validation, the port of
// io.github.getcolors.umami.validate.
//
// Green renders its keys as Clojure keywords, so every message here carries the
// same leading colon — the three colours must report identical errors for one
// colors.yml.

import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { compute, providers as onceProviders } from "package-once-red";
import { onceSsh } from "./once.ts";

export const profilePar = parName("profile");

// provider-compute -> what that choice implies.
//
// `required` are the non-secret keys that provider's template interpolates,
// `secrets` the credentials it needs through COLORS_PAR_*, and `tofuEnv` the
// subset OpenTofu reads from the process environment itself. Keeping the three
// together is what stops a provider being validated against one set of keys and
// run with another -- a stage exporting a credential nobody checked for, or a
// check demanding a key no template uses. The keys of this map are the
// advertised providers; a provider without a template directory and a golden
// is not advertised. One entry today: this package conforms to the Compute
// Provider Standard with a one-entry registry, and a second provider would be
// a copy of this shape rather than a design.
//
// The provider needs firewall sources because this package puts a provider
// firewall in front of the host; ONCE's compute templates have none, so its
// registry entries are shorter.
//
// Two keys the template reads are deliberately not required. `digitalocean-name`
// is an optional override of the profile (Compute Name Standard), and
// `digitalocean-ssh-keys` is meaningful by its absence (SSH Keypair Standard).
// `digitalocean-https-sources`, which older desired state carries, is accepted
// and ignored: the template opens 443 from `digitalocean-http-sources`.
export const computeProviders: compute.Registry = {
  digitalocean: {
    required: ["digitalocean-region", "digitalocean-size", "digitalocean-image",
               "digitalocean-ssh-sources", "digitalocean-http-sources"],
    secrets: ["do-token"],
    tofuEnv: { "do-token": "DIGITALOCEAN_TOKEN" },
  },
};

// The provider a deployment created before this package recorded one in its
// compute output must be running. A legacy state -- `params` without
// `provider` -- is whatever this value says it is, and every state this package
// has ever written is a DigitalOcean one (`umami-digitalocean` holds no live
// droplet today, but its R2 state may still carry such a `params`). The Compute
// Provider Standard's legacy rule accepts a legacy state on this provider alone.
export const defaultComputeProvider = "digitalocean";

// How this package describes itself to ONCE's `compute`, the Compute Provider
// Standard's operations over a package-owned registry. The registry and the
// default are the data above; `sources` names the firewall lists the template
// reads -- SSH must list at least one CIDR, an empty HTTP list means no public
// HTTP. The name rules are ONCE's.
export const spec: compute.ComputeSpec = {
  registry: computeProviders,
  default: defaultComputeProvider,
  sources: { nonEmpty: ["ssh-sources"], mayBeEmpty: ["http-sources"] },
};

// Every key desired state must carry whichever provider is selected. The
// provider-scoped keys come from `computeProviders`.
export const required = [
  "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
  "compute-prevent-destroy", "umami-host", "caddy-image",
];

export const imageKeys = ["caddy-image", "umami-image", "postgres-image"];

export const positiveIntKeys = [
  "backup-retention-days", "umami-backup-retention-days", "umami-port", "postgres-port",
];

const hostRe = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
const imageRe = /^[^\s:@]+(?:\/[^\s:@]+)*:[^\s:@]+$/;

export function missing(value: unknown): boolean {
  return value === null || value === undefined ||
    (typeof value === "string" && value.trim() === "");
}

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

// `<provider>-<suffix>`: desired state names compute keys after the provider,
// so the shared steps reach them through the selected provider rather than a
// fixed prefix. ONCE's; named here so `tools` reads the same.
export const computeKey = compute.computeKey;

// What this deployment's machine is called: `digitalocean-name` when present,
// else the profile (Compute Name Standard). ONCE's; the template, the firewall
// and the playbook derive every label from this one answer.
export const computeName = compute.computeName;

// Whether this deployment owns its machine keypair. Delegates to ONCE, the
// standard's reference implementation, so one rule decides it everywhere.
export function keygen(opts: Opts): boolean {
  return onceSsh.keygen(opts);
}

// A source list as desired state or an overlay string carries it. ONCE's, so
// the validator and the template can never disagree about what an entry is.
export const cidrs = compute.cidrs;

// Every problem with desired state at once: the missing keys (this package's
// and the selected provider's), the package's own checks, then the Compute
// Provider Standard's -- selection, the network contract and the provider
// rules, DigitalOcean's VPC refusal among them -- which are ONCE's over `spec`.
export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of [...required, ...compute.requiredKeys(spec, opts)]) {
    if (missing(opts[key])) errors.push(`:${key} is required`);
  }
  if (opts["provider-dns"] !== "cloudflare") {
    errors.push(":provider-dns must be cloudflare");
  }
  if (!["local", "s3", "r2"].includes(String(opts["provider-backend"]))) {
    errors.push(":provider-backend must be local, s3, or r2");
  }
  if (typeof opts["compute-prevent-destroy"] !== "boolean") {
    errors.push(":compute-prevent-destroy must be true or false");
  }
  if (!missing(opts["umami-host"]) && !hostRe.test(String(opts["umami-host"]))) {
    errors.push(":umami-host must be a fully qualified hostname");
  }
  for (const key of imageKeys) {
    const value = opts[key];
    if (!missing(value) && !imageRe.test(String(value))) {
      errors.push(`:${key} must carry an explicit image tag`);
    }
  }
  for (const key of positiveIntKeys) {
    const value = opts[key];
    if (!missing(value) && !(typeof value === "number" && Number.isInteger(value) && value > 0)) {
      errors.push(`:${key} must be a positive integer`);
    }
  }
  errors.push(...compute.stateErrors(spec, opts));
  return errors;
}

export function backendSecrets(opts: Opts): string[] {
  return onceProviders["provider-backend"]?.[String(opts["provider-backend"])]?.secrets ?? [];
}

// Credentials a real create or delete needs: the selected compute provider's,
// Cloudflare's, the application's, the backup bucket's, and the backend's.
export function secretErrors(opts: Opts): string[] {
  const keys = [...compute.secrets(spec, opts),
                "cloudflare-api-token", "postgres-password", "umami-admin-password"];
  // The compose template interpolates these at run time and carries no
  // fallback, so an unset value would silently render an empty password or
  // signing key.
  if (missing(opts["app-secret-key"]) && missing(opts["umami-app-secret"])) {
    keys.push("app-secret-key");
  }
  if (missing(opts["backup-r2-access-key-id"]) &&
      missing(opts["umami-backup-r2-access-key-id"]) &&
      missing(opts["r2-access-key-id"])) {
    keys.push("backup-r2-access-key-id");
  }
  if (missing(opts["backup-r2-secret-access-key"]) &&
      missing(opts["umami-backup-r2-secret-access-key"]) &&
      missing(opts["r2-secret-access-key"])) {
    keys.push("backup-r2-secret-access-key");
  }
  keys.push(...backendSecrets(opts));
  return [...new Set(keys)].filter((key) => missing(opts[key]))
    .map((key) => `required credential is not set: ${parName(key)}`);
}

export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  switch (slot) {
    case "provider-compute":
      return compute.tofuEnv(spec, opts);
    case "provider-dns":
      return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend":
      return onceProviders["provider-backend"]?.[String(opts["provider-backend"])]?.tofuEnv ?? {};
    default:
      return {};
  }
}
