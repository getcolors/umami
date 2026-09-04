"""Desired-state and credential validation, the port of
io.github.getcolors.umami.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from package_once_blue import compute as once_compute
from package_once_blue import ssh as once_ssh
from package_once_blue.validate import providers as once_providers

profile_par = par_name("profile")

# provider-compute -> what that choice implies.
#
# `required` are the non-secret keys that provider's template interpolates,
# `secrets` the credentials it needs through COLORS_PAR_*, and `tofu-env` the
# subset OpenTofu reads from the process environment itself. Keeping the three
# together is what stops a provider being validated against one set of keys and
# run with another -- a stage exporting a credential nobody checked for, or a
# check demanding a key no template uses. The keys of this map are the
# advertised providers; a provider without a template directory and a golden
# is not advertised. One entry today: this package conforms to the Compute
# Provider Standard with a one-entry registry, and a second provider would be
# a copy of this shape rather than a design.
#
# The provider needs firewall sources because this package puts a provider
# firewall in front of the host; ONCE's compute templates have none, so its
# registry entries are shorter.
#
# Two keys the template reads are deliberately not required. `digitalocean-name`
# is an optional override of the profile (Compute Name Standard), and
# `digitalocean-ssh-keys` is meaningful by its absence (SSH Keypair Standard).
# `digitalocean-https-sources`, which older desired state carries, is accepted
# and ignored: the template opens 443 from `digitalocean-http-sources`.
compute_providers = {
    "digitalocean": {
        "required": ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources", "digitalocean-http-sources"],
        "secrets": ["do-token"],
        "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
    },
}

# The provider a deployment created before this package recorded one in its
# compute output must be running. A legacy state -- `params` without
# `provider` -- is whatever this value says it is, and every state this package
# has ever written is a DigitalOcean one (`umami-digitalocean` holds no live
# droplet today, but its R2 state may still carry such a `params`). The Compute
# Provider Standard's legacy rule accepts a legacy state on this provider alone.
default_compute_provider = "digitalocean"

# How this package describes itself to ONCE's `compute`, the Compute Provider
# Standard's operations over a package-owned registry. The registry and the
# default are the data above; `sources` names the firewall lists the template
# reads -- SSH must list at least one CIDR, an empty HTTP list means no public
# HTTP. The name rules are ONCE's.
spec: once_compute.ComputeSpec = {
    "registry": compute_providers,
    "default": default_compute_provider,
    "sources": {"non_empty": ["ssh-sources"], "may_be_empty": ["http-sources"]},
}

# Every key desired state must carry whichever provider is selected. The
# provider-scoped keys come from `compute_providers`.
required = [
    "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
    "compute-prevent-destroy", "umami-host", "caddy-image",
]

image_keys = ["caddy-image", "umami-image", "postgres-image"]

positive_int_keys = [
    "backup-retention-days", "umami-backup-retention-days", "umami-port", "postgres-port",
]

HOST_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
IMAGE_RE = re.compile(r"^[^\s:@]+(?:/[^\s:@]+)*:[^\s:@]+$")


def missing(value) -> bool:
    return value is None or (isinstance(value, str) and value.strip() == "")


def env_errors(env: dict) -> list[str]:
    if str(env.get(profile_par) or ""):
        return [f"{profile_par} is set; profile must come from colors.yml only"]
    return []


# `<provider>-<suffix>`: desired state names compute keys after the provider,
# so the shared steps reach them through the selected provider rather than a
# fixed prefix. ONCE's; named here so `tools` reads the same.
compute_key = once_compute.compute_key

# What this deployment's machine is called: `digitalocean-name` when present,
# else the profile (Compute Name Standard). ONCE's; the template, the firewall
# and the playbook derive every label from this one answer.
compute_name = once_compute.compute_name


def keygen(opts: dict) -> bool:
    """Whether this deployment owns its machine keypair. Delegates to ONCE, the
    standard's reference implementation, so one rule decides it everywhere."""
    return once_ssh.keygen(opts)


# A source list as desired state or an overlay string carries it. ONCE's, so
# the validator and the template can never disagree about what an entry is.
cidrs = once_compute.cidrs


def _positive_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def state_errors(opts: dict) -> list[str]:
    """Every problem with desired state at once: the missing keys (this
    package's and the selected provider's), the package's own checks, then the
    Compute Provider Standard's -- selection, the network contract and the
    provider rules, DigitalOcean's VPC refusal among them -- which are ONCE's
    over `spec`."""
    errors: list[str] = []
    for key in [*required, *once_compute.required_keys(spec, opts)]:
        if missing(opts.get(key)):
            errors.append(f":{key} is required")
    if opts.get("provider-dns") != "cloudflare":
        errors.append(":provider-dns must be cloudflare")
    if opts.get("provider-backend") not in ("local", "s3", "r2"):
        errors.append(":provider-backend must be local, s3, or r2")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append(":compute-prevent-destroy must be true or false")
    if not (missing(opts.get("umami-host"))
            or HOST_RE.fullmatch(str(opts.get("umami-host")))):
        errors.append(":umami-host must be a fully qualified hostname")
    for key in image_keys:
        value = opts.get(key)
        if not missing(value) and not IMAGE_RE.fullmatch(str(value)):
            errors.append(f":{key} must carry an explicit image tag")
    for key in positive_int_keys:
        value = opts.get(key)
        if not missing(value) and not _positive_int(value):
            errors.append(f":{key} must be a positive integer")
    errors += once_compute.state_errors(spec, opts)
    return errors


def backend_secrets(opts: dict) -> list[str]:
    return (once_providers.get("provider-backend", {})
            .get(str(opts.get("provider-backend")), {})
            .get("secrets", []))


def secret_errors(opts: dict) -> list[str]:
    """Credentials a real create or delete needs: the selected compute
    provider's, Cloudflare's, the application's, the backup bucket's, and the
    backend's."""
    keys = [*once_compute.secrets(spec, opts),
            "cloudflare-api-token", "postgres-password", "umami-admin-password"]
    # The compose template interpolates these at run time and carries no
    # fallback, so an unset value would silently render an empty password or
    # signing key.
    if missing(opts.get("app-secret-key")) and missing(opts.get("umami-app-secret")):
        keys.append("app-secret-key")
    if (missing(opts.get("backup-r2-access-key-id"))
            and missing(opts.get("umami-backup-r2-access-key-id"))
            and missing(opts.get("r2-access-key-id"))):
        keys.append("backup-r2-access-key-id")
    if (missing(opts.get("backup-r2-secret-access-key"))
            and missing(opts.get("umami-backup-r2-secret-access-key"))
            and missing(opts.get("r2-secret-access-key"))):
        keys.append("backup-r2-secret-access-key")
    keys.extend(backend_secrets(opts))
    return [f"required credential is not set: {par_name(key)}"
            for key in dict.fromkeys(keys) if missing(opts.get(key))]


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    if slot == "provider-compute":
        return once_compute.tofu_env(spec, opts)
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        return (once_providers.get("provider-backend", {})
                .get(str(opts.get("provider-backend")), {})
                .get("tofu-env", {}))
    return {}
