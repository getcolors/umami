"""Desired-state and credential validation, the port of
io.github.getcolors.umami.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from . import compute
from colors_compute.ssh import _mode
from package_once_blue.validate import providers as once_providers

profile_par = par_name("profile")

default_compute_provider = "digitalocean"

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


def keygen(opts):
    try:
        return _mode(opts)['mode'] == 'managed'
    except ValueError:
        return True


def _positive_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def state_errors(opts: dict) -> list[str]:
    """Every problem with desired state at once: the missing keys (this
    package's and the selected provider's), the package's own checks, then the
    Compute Provider Standard's -- selection, the network contract and the
    provider rules, DigitalOcean's VPC refusal among them -- which are ONCE's
    over `spec`."""
    errors: list[str] = []
    for key in required:
        if missing(opts.get(key)):
            errors.append(f":{key} is required")
    if opts.get("provider-dns") != "cloudflare":
        errors.append(":provider-dns must be cloudflare")
    if opts.get("provider-backend") not in ("s3", "r2"):
        errors.append(":provider-backend must be s3 or r2")
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
    errors += compute.errors(opts)
    return errors


def backend_secrets(opts: dict) -> list[str]:
    return (once_providers.get("provider-backend", {})
            .get(str(opts.get("provider-backend")), {})
            .get("secrets", []))


def secret_errors(opts: dict) -> list[str]:
    """Credentials a real create or delete needs: the selected compute
    provider's, Cloudflare's, the application's, the backup bucket's, and the
    backend's."""
    keys = [
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
        return {}
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        return (once_providers.get("provider-backend", {})
                .get(str(opts.get("provider-backend")), {})
                .get("tofu-env", {}))
    return {}
