"""The graph, the port of io.github.getcolors.umami.workflow."""

from __future__ import annotations

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, workflow

from . import tools, validate

DEFAULTS = {"provider-compute": "digitalocean", "provider-dns": "cloudflare",
            "provider-backend": "local", "compute-prevent-destroy": True,
            "workdir": ".colors", "umami-port": 3000, "postgres-port": 5432,
            "postgres-db": "umami", "postgres-user": "umami",
            "postgres-data-dir": "/var/lib/umami/postgres",
            "backup-dir": "/var/backups/umami",
            "backup-r2-bucket": "umami-backup",
            "backup-r2-region": "auto",
            "backup-oncalendar": "*-*-* 03:00:00",
            "backup-retention-days": 7,
            "caddy-image": "caddy:2.11.4",
            # Proxied by default: an unproxied record publishes the droplet's
            # address. Note this map is the effective default -- it seeds the
            # key, so tools.dns_data always sees it supplied and its own
            # fallback never runs. Both have to agree.
            "cloudflare-proxied": True}


async def state_output(opts: dict) -> dict | None:
    """Compute params recorded in the infrastructure state; None when the state
    holds none. An unreadable backend raises — the delete path treats that as
    fatal rather than falling back to the documentation address."""
    outputs = await tofu.outputs(tools.tool_dir(opts, tools.infrastructure_tool),
                                 tools.backend_credential_env(opts))
    return (outputs or {}).get("params")


async def adopt_state(opts: dict) -> dict:
    """A real delete runs the ansible cleanup before the infrastructure step,
    so the instance address must come out of the existing state here. An
    explicit ip (COLORS_PAR_IP) skips the read; a readable state without
    compute params leaves ip unset and the cleanup step skips itself; an
    unreadable backend fails loudly — swallowing it is how a live teardown
    ended up converging against 192.0.2.10."""
    if opts.get("ip"):
        return {**opts, "blue/exit": 0}
    try:
        return {**opts, **((await state_output(opts)) or {}), "blue/exit": 0}
    except Exception as e:  # noqa: BLE001 — the message is the point
        return {**opts, "blue/exit": 1,
                "blue/err": ("could not read the infrastructure state for "
                             f"the delete cleanup: {e}\n"
                             "fix the backend credentials, or supply "
                             f"{par_name('ip')} to address the instance directly")}


async def start_step(original: dict, env: dict | None = None) -> dict:
    async def after(opts, _env, context):
        if context["real"] and context["event"] == "delete":
            return await adopt_state(opts)
        return {**opts, "blue/exit": 0}

    return await preflight(
        original, defaults=DEFAULTS, overlay=read_pars, env=env,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            lambda o, _e, c: (validate.secret_errors(o)
                              if c["real"] and c["event"] in ("create", "delete") else []),
            lambda o, _e, c: ([f"compute destruction is protected; set "
                               f"{par_name('compute-prevent-destroy')}=false to delete"]
                              if c["real"] and c["event"] == "delete"
                              and o.get("compute-prevent-destroy") else []),
        ],
        after_validate=after)


def wire_fn(step: str, run_opts: dict):
    if run_opts.get("blue/event") == "delete":
        return {
            "umami/start": (start_step, "umami/ansible"),
            "umami/ansible": (tools.ansible_step, "umami/dns"),
            "umami/dns": (tools.dns_step, "umami/infrastructure"),
            "umami/infrastructure": (tools.infrastructure_step,),
        }.get(step)
    return {
        "umami/start": (start_step, "umami/infrastructure"),
        "umami/infrastructure": (tools.infrastructure_step, "umami/dns"),
        "umami/dns": (tools.dns_step, "umami/ansible"),
        "umami/ansible": (tools.ansible_step, "umami/acceptance"),
        "umami/acceptance": (tools.acceptance_step,),
    }.get(step)


def backend_advice(tool: str):
    return tofu.conventional_backend_advice(
        dir=lambda o, tool=tool: tools.tool_dir(o, tool),
        key=lambda o, tool=tool: f"{o.get('profile') or ''}/{tool}.tfstate")


side_effecting = ["umami/infrastructure", "umami/dns", "umami/ansible", "umami/acceptance"]


def create_workflow():
    wf = workflow(start="umami/start", wire_fn=wire_fn)
    wf = advice_add(wf, "umami/infrastructure", "before", "umami.workflow/backend",
                    backend_advice(tools.infrastructure_tool))
    wf = advice_add(wf, "umami/dns", "before", "umami.workflow/backend",
                    backend_advice(tools.dns_tool))
    return dry_run.advise(progress.advise(wf), side_effecting)


umami_workflow = create_workflow()
