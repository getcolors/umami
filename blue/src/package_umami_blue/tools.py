"""The steps and every template spec, the port of io.github.getcolors.umami.tools."""

from __future__ import annotations

import asyncio
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from blue import tofu
from blue.ansible import ansible_with_spec
from blue.cli import stage_dir
from blue.runtime import runtime
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, content_spec, scaffold

from . import validate

infrastructure_tool = "umami-infrastructure"
dns_tool = "umami-dns"
ansible_tool = "umami-ansible"
ROOT = Path(__file__).parent / "resources"
template_opts = PRESERVE_JINJA_DELIMITERS


def tool_dir(opts: dict, tool: str) -> str:
    return stage_dir(opts, tool, default_profile="umami")


def template(path: str, file: str) -> dict:
    name = f"tools/{path}/{file}"
    return {"name": name, "content": (ROOT / name).read_text()}


def spec(source: dict, target: str, data: dict) -> dict:
    return {"template": source, "target": target, "data": data, "opts": template_opts}


def raw_spec(target: str, content: str) -> dict:
    return content_spec(target, content)


def cidrs(opts: dict, key: str) -> list[str]:
    value = opts.get(key)
    xs = value if isinstance(value, list) else re.split(
        r"[,\s]+", "" if value is None else str(value))
    return [s for s in (str(x).strip() for x in xs) if s]


def credential_env(opts: dict, *slots: str) -> dict[str, str] | None:
    merged: dict[str, str] = {}
    for slot in [*slots, "provider-backend"]:
        merged.update(validate.tofu_env(opts, slot))
    result = {}
    for key, env_var in merged.items():
        value = "" if opts.get(key) is None else str(opts.get(key))
        if value:
            result[env_var] = value
    return result or None


def backend_credential_env(opts: dict) -> dict[str, str] | None:
    return credential_env(opts)


def fallback_params(opts: dict) -> dict:
    return {"ip": "192.0.2.10", "user": "root", "sudoer": "root",
            "name": opts.get("profile")}


def output_params(result: dict) -> dict | None:
    return (result.get("tofu/outputs") or {}).get("params")


# ---------------------------------------------------------------- compute


def infrastructure_data(opts: dict) -> dict:
    return {**opts,
            "ssh-sources-hcl": tofu.hcl_list(cidrs(opts, "digitalocean-ssh-sources")),
            "http-sources-hcl": tofu.hcl_list(cidrs(opts, "digitalocean-http-sources"))}


def resolved_compute(result: dict, fallback: dict, outputs: dict | None) -> dict:
    """Refuse to hand 192.0.2.10 to Ansible. That is the documentation address
    the credential-free build and dry-run paths render with; on a real converge
    a missing compute output must fail loudly rather than quietly point the
    whole playbook at TEST-NET."""
    if (outputs or {}).get("ip"):
        return {**result, **fallback, **(outputs or {})}
    return {**result, "blue/exit": 1,
            "blue/err": ("compute produced no ip output; refusing to converge "
                         "against the documentation address")}


async def infrastructure_step(opts: dict) -> dict:
    dir = tool_dir(opts, infrastructure_tool)
    specs = [spec(template("infrastructure", "main.tf"), f"{dir}/main.tf",
                  infrastructure_data(opts))]
    result = await tofu.tofu_with_spec(
        opts, specs, dir=dir, env=credential_env(opts, "provider-compute"))
    if (result.get("blue/exit") or 0) > 0:
        return result
    if opts.get("blue/event") == "build":
        return {**result, **fallback_params(opts)}
    if opts.get("blue/event") == "delete":
        return result
    return resolved_compute(result, fallback_params(opts), output_params(result))


# -------------------------------------------------------------------- dns


def dns_data(opts: dict) -> dict:
    host = "" if opts.get("umami-host") is None else str(opts.get("umami-host"))
    parts = host.split(".")
    zone = opts.get("cloudflare-zone") or (
        ".".join(parts[1:]) if len(parts) > 2 else host)
    return {**opts,
            "ip": opts.get("ip") or fallback_params(opts)["ip"],
            "cloudflare-zone": zone,
            # Kept in step with the workflow defaults, which seed this key and
            # therefore decide it on the real path -- this fallback only runs
            # when dns_data is called with bare opts, as the tests do.
            "cloudflare-proxied": (opts.get("cloudflare-proxied")
                                   if opts.get("cloudflare-proxied") is not None
                                   else True)}


def dns_json(opts: dict) -> str:
    return tofu.constructs_json([
        tofu.construct("resource", "cloudflare_dns_record", "umami",
                       {"zone_id": "${data.cloudflare_zone.zone.id}",
                        "name": opts.get("umami-host"),
                        "content": opts.get("ip"), "type": "A",
                        "proxied": bool(opts.get("cloudflare-proxied")),
                        "ttl": 1})])


async def dns_step(opts: dict) -> dict:
    dir = tool_dir(opts, dns_tool)
    data = dns_data(opts)
    specs = [spec(template("dns", "main.tf"), f"{dir}/main.tf", data),
             raw_spec(f"{dir}/record.tf.json", dns_json(data))]
    return await tofu.tofu_with_spec(
        opts, specs, dir=dir, env=credential_env(opts, "provider-dns"))


# ---------------------------------------------------------------- ansible


def _pretty(value, indent=0):
    """Cheshire's pretty JSON, byte for byte — Green's artifact contract."""
    if isinstance(value, list):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(_pretty(item, indent) for item in value) + " ]"
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad = " " * (indent + 2)
        body = ",\n".join(f"{pad}{json.dumps(str(k))} : {_pretty(v, indent + 2)}"
                          for k, v in value.items())
        return "{\n" + body + "\n" + " " * indent + "}"
    return json.dumps(value)


def inventory(opts: dict) -> str:
    return _pretty(
        {"all": {"children": {"umami": {"hosts": {
            opts.get("profile"): {"ansible_host": opts.get("ip") or "192.0.2.10",
                                  "ansible_user": "root"}}}}}})


def _first(opts: dict, *keys: str):
    for key in keys:
        if opts.get(key) is not None:
            return opts.get(key)
    return None


def ansible_data(opts: dict) -> dict:
    umami_image = opts.get("umami-image") or (
        "ghcr.io/umami-software/umami:postgresql-"
        + str(opts.get("umami-version") or "v2.14.0"))
    postgres_image = opts.get("postgres-image") or (
        "postgres:" + str(opts.get("postgres-version") or "17") + "-alpine")
    return {**opts,
            "ip": opts.get("ip") or "192.0.2.10",
            "umami-image": umami_image,
            "postgres-image": postgres_image,
            "postgres-db": _first(opts, "postgres-database", "postgres-db") or "umami",
            "postgres-user": opts.get("postgres-user") or "umami",
            "postgres-data-dir": opts.get("postgres-data-dir") or "/var/lib/umami/postgres",
            "umami-port": opts.get("umami-port") or 3000,
            "backup-dir": _first(opts, "backup-dir", "umami-backup-dir")
            or "/var/backups/umami",
            "backup-r2-bucket": _first(opts, "backup-r2-bucket", "umami-backup-r2-bucket")
            or "umami-backup",
            "backup-r2-endpoint": _first(opts, "backup-r2-endpoint",
                                         "umami-backup-r2-endpoint"),
            "backup-oncalendar": _first(opts, "backup-oncalendar", "umami-backup-oncalendar")
            or "*-*-* 03:00:00",
            "backup-retention-days": _first(opts, "backup-retention-days",
                                            "umami-backup-retention-days") or 7}


ANSIBLE_FILES = ["ansible.cfg", "main.yml", "cleanup.yml", "compose.yml", "Caddyfile", "backup"]


def ansible_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_tool)
    data = ansible_data(opts)
    return [*[spec(template("ansible", name), f"{dir}/{name}", data)
              for name in ANSIBLE_FILES],
            raw_spec(f"{dir}/inventory.json", inventory(data))]


async def ansible_step(opts: dict, run_fn=ansible_with_spec) -> dict:
    dir = tool_dir(opts, ansible_tool)
    if opts.get("blue/event") == "delete" and not opts.get("ip"):
        # No compute in state: there is no host to clean up, and the rendered
        # inventory would fall back to 192.0.2.10. Remove the rendered tree the
        # way a completed cleanup would and let the teardown continue.
        return {**scaffold(opts, ansible_specs(opts)),
                "blue/exit": 0, "umami/cleanup": "skipped-no-compute"}
    return await run_fn(
        opts, ansible_specs(opts),
        dir=dir, inventory="inventory.json",
        playbooks={"create": "main.yml", "delete": "cleanup.yml"},
        host_key_checking=False)


# ------------------------------------------------------------- acceptance
#
# Every claim this step reports must be one it checked. TLS is verified (never
# `curl -k`), an ingested event is read back out of PostgreSQL rather than
# inferred from a status code, and the backup drill is confirmed by a fresh
# object in R2 rather than by systemd reporting that it started something.


async def http_status(args: list[str]) -> str | None:
    r = await runtime.exec(
        ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", *args],
        timeout_ms=20000)
    return str(r.out or "").strip() if r.exit == 0 else None


async def ssh_out(ip, command: str, timeout_ms: int) -> str | None:
    r = await runtime.exec(
        ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10",
         f"root@{ip}", command],
        timeout_ms=timeout_ms)
    return str(r.out or "").strip() if r.exit == 0 else None


async def sql(opts: dict, ip, query: str) -> str | None:
    out = str(await ssh_out(
        ip,
        "cd /opt/umami && docker compose exec -T postgres psql -U "
        f"{opts.get('postgres-user')} -d {opts.get('postgres-db')}"
        f" -tAc '{query}'",
        30000) or "")
    return out or None


def _parse_long(s: str) -> int | None:
    return int(s) if re.fullmatch(r"[+-]?\d+", s) else None


async def event_count(opts: dict, ip) -> int | None:
    out = await sql(opts, ip, "select count(*) from website_event")
    return None if out is None else _parse_long(out)


acceptance_website_id = "00000000-c010-4000-8000-000000000001"


async def ensure_acceptance_website(opts: dict, ip) -> str | None:
    """A dedicated throwaway website, created on demand. Without one the step
    reports "not-configured" and sends nothing, so the synthetic request is
    never exercised -- which is how the sibling Rybbit package carried a
    payload the API had always rejected. Sending to a real website instead
    would write a test pageview into the operator's analytics on every
    converge.

    Literals are dollar-quoted because the query travels inside single quotes
    in a remote shell, and psql prints the INSERT tag before the SELECT result,
    so the id comes off the last line."""
    configured = str(opts.get("umami-acceptance-website-domain") or "").strip()
    domain = configured or "colors-acceptance.invalid"
    owner = '(select user_id from "user" limit 1)'
    out = await sql(
        opts, ip,
        "insert into website (website_id, name, domain, created_by, user_id) "
        f"select $${acceptance_website_id}$$::uuid, $$colors-acceptance$$, "
        f"$${domain}$$, {owner}, {owner} "
        "where not exists (select 1 from website "
        f"where website_id = $${acceptance_website_id}$$::uuid); "
        "select website_id from website "
        f"where website_id = $${acceptance_website_id}$$::uuid")
    if out is None:
        return None
    candidate = out.splitlines()[-1].strip() if out.splitlines() else ""
    return candidate if re.fullmatch(r"[0-9a-f-]{36}", candidate) else None


async def wait_health(url: str, attempts: int) -> bool:
    n = attempts
    while True:
        r = await runtime.exec(["curl", "-fsS", f"{url}/api/heartbeat"],
                               timeout_ms=10000)
        if r.exit == 0:
            return True
        if n > 0:
            await asyncio.sleep(5)
            n -= 1
        else:
            return False


async def default_admin_active(base: str) -> bool:
    """Umami seeds admin/umami. A deployment that still answers to it is not
    one whose acceptance may pass."""
    return await http_status(
        ["-X", "POST", "-H", "content-type: application/json",
         "--data", '{"username":"admin","password":"umami"}',
         f"{base}/api/auth/login"]) == "200"


async def send_event(base: str, host, website: str) -> str | None:
    return await http_status(
        ["-X", "POST", "-H", "content-type: application/json",
         "-H", "User-Agent: Mozilla/5.0 (Colors acceptance)",
         "--data", json.dumps(
             {"type": "event",
              "payload": {"website": website, "hostname": host,
                          "url": "/colors-acceptance",
                          "name": "colors-acceptance"}}),
         f"{base}/api/send"])


def _integer(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def ingestion_verdict(status, before, after) -> str:
    if status is None:
        return "unreachable"
    if _integer(before) and _integer(after) and after > before:
        return "ingested"
    if re.fullmatch(r"2\d\d", str(status)):
        return "dropped"
    return "rejected"


async def wait_ingested(opts: dict, ip, baseline: int, attempts: int) -> int | None:
    n = attempts
    while True:
        after = await event_count(opts, ip)
        if _integer(after) and after > baseline:
            return after
        if n > 0:
            await asyncio.sleep(3)
            n -= 1
        else:
            return after


rclone_env = ("RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare "
              "RCLONE_CONFIG_R2_REGION=auto RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true")


async def backup_listing(opts: dict, ip) -> list[dict] | None:
    """Objects under this profile's prefix, listed on the droplet with the
    credentials the backup unit already holds."""
    out = await ssh_out(
        ip,
        f"set -a; . /etc/umami-backup.env; set +a; {rclone_env}"
        ' RCLONE_CONFIG_R2_ACCESS_KEY_ID="$BACKUP_R2_ACCESS_KEY_ID"'
        ' RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$BACKUP_R2_SECRET_ACCESS_KEY"'
        f" RCLONE_CONFIG_R2_ENDPOINT=\"{opts.get('backup-r2-endpoint')}\""
        f" rclone lsjson --files-only r2:{opts.get('backup-r2-bucket')}"
        f"/{opts.get('profile')}",
        120000)
    if not out:
        return None
    try:
        return json.loads(out)
    except ValueError:
        return None


def parse_instant(s) -> datetime | None:
    """Like java.time.OffsetDateTime/parse, an offset is required; a string
    without one is not an instant."""
    try:
        t = datetime.fromisoformat(str(s))
    except ValueError:
        return None
    return t if t.tzinfo is not None else None


def fresh_backup(entries, since: datetime) -> bool:
    for entry in entries or []:
        if (entry.get("Size") or 0) > 0:
            t = parse_instant(entry.get("ModTime"))
            if t is not None and t >= since:
                return True
    return False


async def run_backup(ip) -> str | None:
    return await ssh_out(
        ip,
        "systemctl start umami-backup.service && systemctl is-active umami-backup.timer",
        300000)


async def acceptance_step(opts: dict) -> dict:
    if opts.get("blue/event") != "create":
        return {**opts, "blue/exit": 0}
    base = f"https://{opts.get('umami-host')}"
    ip = opts.get("ip")
    since = datetime.now(timezone.utc) - timedelta(seconds=120)
    if not await wait_health(base, 60):
        return {**opts, "blue/exit": 1,
                "blue/err": "HTTPS heartbeat did not become ready with a valid certificate"}
    if await default_admin_active(base):
        return {**opts, "blue/exit": 1,
                "blue/err": "the seeded admin/umami credentials still authenticate; rotate them"}
    website = await ensure_acceptance_website(opts, ip)
    before = await event_count(opts, ip)
    if not _integer(before):
        return {**opts, "blue/exit": 1,
                "blue/err": "could not read website_event from PostgreSQL to verify ingestion"}
    if not website:
        verdict = "not-configured"
    else:
        status = await send_event(base, opts.get("umami-host"), website)
        after = await wait_ingested(opts, ip, before, 10)
        verdict = ingestion_verdict(status, before, after)
    if verdict in ("dropped", "rejected", "unreachable"):
        return {**opts, "blue/exit": 1,
                "blue/err": f"synthetic event was not ingested: {verdict}"}
    if await run_backup(ip) is None:
        return {**opts, "blue/exit": 1,
                "blue/err": "backup unit or timer is not healthy"}
    if not fresh_backup(await backup_listing(opts, ip), since):
        return {**opts, "blue/exit": 1,
                "blue/err": ("no backup object newer than this run under r2:"
                             f"{opts.get('backup-r2-bucket')}/{opts.get('profile')}")}
    return {**opts, "blue/exit": 0,
            "umami/acceptance": {"health": "ok", "default-admin": "rejected",
                                 "event": verdict, "backup": "verified-in-r2"}}
