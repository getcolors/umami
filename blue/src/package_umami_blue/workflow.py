"""The graph, the port of io.github.getcolors.umami.workflow."""

from __future__ import annotations

import os

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, failed, workflow
from package_once_blue import compute as once_compute

from . import ssh, ssh_config, tools, validate

DEFAULTS = {"provider-compute": validate.default_compute_provider,
            "provider-dns": "cloudflare",
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
    """Compute params recorded in the infrastructure state; None when the
    state holds none. An unreadable backend raises the SDK's `StepError`,
    which `once_compute.read_state` turns into `{"error": message}` — create
    and delete treat the two differently. Kept local, and looked up on this
    module at call time, so tests can replace it."""
    outputs = await tofu.outputs(tools.tool_dir(opts, tools.infrastructure_tool),
                                 tools.backend_credential_env(opts))
    return (outputs or {}).get("params")


def adopt_state(opts: dict, state: dict) -> dict:
    """A real delete runs the ansible cleanup before the infrastructure step, so
    the instance address must come out of the existing state here. The
    adoption itself is ONCE's (`once_compute.adopt_state`): a readable state
    without compute params leaves `ip` unset and the cleanup step skips
    itself; an unreadable backend fails loudly — swallowing it is how a live
    teardown ended up converging against 192.0.2.10. What this package adds
    is the address override: an explicit `ip` (COLORS_PAR_IP) never skips the
    read or the provider guard, it only replaces the cleanup address once the
    read has succeeded, for a state whose recorded address is stale. ONCE
    deliberately applies no such override, so no other package gains a way
    to point a delete's cleanup at an arbitrary host."""
    adopted = once_compute.adopt_state(opts, "delete", state)
    if not failed(adopted) and opts.get("ip"):
        return {**adopted, "ip": opts["ip"]}
    return adopted


async def start_step(original: dict, env: dict | None = None) -> dict:
    # The state is read once, up front, on the same defaulted and overlaid
    # opts the validators see — the overlay is what carries the backend
    # credentials — and only for the two events that touch a provider. The
    # validator and the after-validate share the one read.
    environment = dict(os.environ if env is None else env)
    overlaid = read_pars({**DEFAULTS, **original}, environment)
    context = {"event": overlaid.get("blue/event"), "real": not overlaid.get("blue/dry-run")}
    state = (await once_compute.read_state(overlaid, state_output)
             if once_compute.lifecycle_event(context) else {})

    # The machine key's create matrix and the provider preflight run before
    # any template is rendered: an unowned key on disk or at the provider
    # stops the run while stopping is still free. Delete fills the same
    # template values — a destroy renders before it destroys — and adopts the
    # recorded address, but checks no key, because its key cleanup runs after
    # the compute destroy.
    async def after(opts, _env, ctx):
        real, event = ctx["real"], ctx["event"]
        if real and event == "delete":
            return adopt_state(opts, state)
        if real and event == "create":
            async def recorded(_opts):
                return state.get("params")
            opts = await ssh.ensure_key(opts, recorded)
            if failed(opts):
                return opts
            opts = ssh.preflight(ssh.with_machine_key(opts))
            if failed(opts):
                return opts
            opts = ssh_config.preflight(opts)
            if failed(opts):
                return opts
            return {**opts, "blue/exit": 0}
        return {**ssh.with_machine_key(opts), "blue/exit": 0}

    return await preflight(
        original, defaults=DEFAULTS, overlay=read_pars, env=environment,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            # Standard §4 before the credentials: a recorded provider that
            # differs from the selected one reports the actionable error, not
            # a missing token for the provider that was just selected.
            lambda o, _e, c: (once_compute.provider_validator(
                validate.spec, o, state.get("params"), lambda: validate.secret_errors(o))
                if once_compute.lifecycle_event(c) else []),
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
            # The `~/.ssh/config` block goes before the destroy, the opposite
            # of the keypair below. A block that outlives its host is stale but
            # harmless; a key that predeceases its host locks the operator out
            # of a machine that still exists. Both orders are deliberate; see
            # standards/ssh-config.md.
            "umami/dns": (tools.dns_step, "umami/ssh-config"),
            "umami/ssh-config": (tools.ansible_local_step, "umami/infrastructure"),
            # The keypair goes strictly after the compute destroy: a key that
            # predeceases its host locks the operator out of a machine that
            # still exists (SSH Keypair Standard §3.3).
            "umami/infrastructure": (tools.infrastructure_step, "umami/ssh-cleanup"),
            "umami/ssh-cleanup": (ssh.cleanup_step,),
        }.get(step)
    return {
        "umami/start": (start_step, "umami/infrastructure"),
        # After compute, which is where the address first exists, and before
        # the stage that converges the machine.
        "umami/infrastructure": (tools.infrastructure_step, "umami/ssh-config"),
        "umami/ssh-config": (tools.ansible_local_step, "umami/dns"),
        "umami/dns": (tools.dns_step, "umami/ansible"),
        "umami/ansible": (tools.ansible_step, "umami/acceptance"),
        "umami/acceptance": (tools.acceptance_step,),
    }.get(step)


def backend_advice(tool: str):
    return tofu.conventional_backend_advice(
        dir=lambda o, tool=tool: tools.tool_dir(o, tool),
        key=lambda o, tool=tool: f"{o.get('profile') or ''}/{tool}.tfstate")


side_effecting = ["umami/infrastructure", "umami/dns", "umami/ssh-config",
                  "umami/ansible", "umami/acceptance", "umami/ssh-cleanup"]


def create_workflow():
    wf = workflow(start="umami/start", wire_fn=wire_fn)
    wf = advice_add(wf, "umami/infrastructure", "before", "umami.workflow/backend",
                    backend_advice(tools.infrastructure_tool))
    wf = advice_add(wf, "umami/dns", "before", "umami.workflow/backend",
                    backend_advice(tools.dns_tool))
    return dry_run.advise(progress.advise(wf), side_effecting)


umami_workflow = create_workflow()
