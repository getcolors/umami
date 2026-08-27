import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers } from "package-once-red";

export const profilePar = parName("profile");

export const required = [
  "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
  "compute-prevent-destroy", "umami-host", "caddy-image",
  "digitalocean-name", "digitalocean-region", "digitalocean-size",
  "digitalocean-image", "digitalocean-ssh-keys", "digitalocean-ssh-sources",
  "digitalocean-http-sources",
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

export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of required) {
    if (missing(opts[key])) errors.push(`:${key} is required`);
  }
  if (opts["provider-compute"] !== "digitalocean") {
    errors.push(":provider-compute must be digitalocean");
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
  if ("digitalocean-vpc-uuid" in opts) {
    errors.push(":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime");
  }
  if ("digitalocean-vpc-cidr" in opts) {
    errors.push(":digitalocean-vpc-cidr must be absent; this package must not create a VPC");
  }
  return errors;
}

export function backendSecrets(opts: Opts): string[] {
  return providers["provider-backend"]?.[String(opts["provider-backend"])]?.secrets ?? [];
}

export function secretErrors(opts: Opts): string[] {
  const keys = ["do-token", "cloudflare-api-token", "postgres-password", "umami-admin-password"];
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
      return { "do-token": "DIGITALOCEAN_TOKEN" };
    case "provider-dns":
      return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend":
      return providers["provider-backend"]?.[String(opts["provider-backend"])]?.tofuEnv ?? {};
    default:
      return {};
  }
}
