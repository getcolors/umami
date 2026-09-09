// Desired-state and credential validation, the port of
// io.github.getcolors.umami.validate.
//
// Green renders its keys as Clojure keywords, so every message here carries the
// same leading colon — the three colours must report identical errors for one
// colors.yml.

import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers as onceProviders } from "package-once-red";
import * as compute from "./compute.ts";
import {keyMode} from "colors-compute-red";

export const profilePar = parName("profile");

export const defaultComputeProvider="digitalocean";

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
export function keygen(opts:Opts){try{return keyMode(opts).mode==='managed';}catch{return true;}}

// Every problem with desired state at once: the missing keys (this package's
// and the selected provider's), the package's own checks, then the Compute
// Provider Standard's -- selection, the network contract and the provider
// rules, DigitalOcean's VPC refusal among them -- which are ONCE's over `spec`.
export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of required) {
    if (missing(opts[key])) errors.push(`:${key} is required`);
  }
  if (opts["provider-dns"] !== "cloudflare") {
    errors.push(":provider-dns must be cloudflare");
  }
  if (!["s3", "r2"].includes(String(opts["provider-backend"]))) {
    errors.push(":provider-backend must be s3 or r2");
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
  errors.push(...compute.errors(opts));
  return errors;
}

export function backendSecrets(opts: Opts): string[] {
  return onceProviders["provider-backend"]?.[String(opts["provider-backend"])]?.secrets ?? [];
}

// Credentials a real create or delete needs: the selected compute provider's,
// Cloudflare's, the application's, the backup bucket's, and the backend's.
export function secretErrors(opts: Opts): string[] {
  const keys = [
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
      return {};
    case "provider-dns":
      return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend":
      return onceProviders["provider-backend"]?.[String(opts["provider-backend"])]?.tofuEnv ?? {};
    default:
      return {};
  }
}
