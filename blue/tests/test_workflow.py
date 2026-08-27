from conftest import fixture
from package_umami_blue import workflow


async def test_build_and_dry_run_need_no_credentials():
    result = await workflow.start_step({**fixture(), "blue/event": "build"}, env={})
    assert result["blue/exit"] == 0
    result = await workflow.start_step(
        {**fixture(), "blue/event": "create", "blue/dry-run": True}, env={})
    assert result["blue/exit"] == 0


async def test_real_create_requires_credentials():
    result = await workflow.start_step(
        {**fixture(), "provider-backend": "r2", "blue/event": "create"}, env={})
    assert result["blue/exit"] == 2
    assert "COLORS_PAR_DO_TOKEN" in result["blue/err"]
    assert "COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID" in result["blue/err"]


async def test_delete_is_protected():
    result = await workflow.start_step({**fixture(), "blue/event": "delete"}, env={})
    assert result["blue/exit"] == 2
    assert "COMPUTE_PREVENT_DESTROY" in result["blue/err"]


def deletable_fixture(overrides: dict | None = None) -> dict:
    """A fixture that passes real-delete preflight: guard lifted, secrets present."""
    return fixture({"compute-prevent-destroy": False,
                    "do-token": "t", "cloudflare-api-token": "t",
                    "postgres-password": "p", "umami-admin-password": "p",
                    "app-secret-key": "s",
                    "backup-r2-access-key-id": "k",
                    "backup-r2-secret-access-key": "s",
                    **(overrides or {})})


async def test_delete_fails_loudly_when_state_is_unreadable(monkeypatch):
    # Swallowing a failed state read is how a live teardown ended up pointing
    # the cleanup playbook at 192.0.2.10: stale backend credentials made
    # `tofu output` fail, nothing was merged, and the inventory fell back to
    # TEST-NET. The failure must surface here, before any playbook runs.
    async def unreadable(_opts):
        raise RuntimeError("Unauthorized")

    monkeypatch.setattr(workflow, "state_output", unreadable)
    result = await workflow.start_step(
        {**deletable_fixture(), "blue/event": "delete"}, env={})
    assert result["blue/exit"] == 1
    assert "Unauthorized" in result["blue/err"]
    assert "COLORS_PAR_IP" in result["blue/err"]


async def test_delete_with_explicit_ip_skips_the_state_read(monkeypatch):
    # COLORS_PAR_IP is the operator's escape hatch when the state backend is
    # unreachable; it must not require the read it exists to replace.
    async def forbidden(_opts):
        raise AssertionError("must not be called")

    monkeypatch.setattr(workflow, "state_output", forbidden)
    result = await workflow.start_step(
        {**deletable_fixture(), "blue/event": "delete", "ip": "203.0.113.7"}, env={})
    assert result["blue/exit"] == 0
    assert result["ip"] == "203.0.113.7"


async def test_delete_with_empty_state_proceeds_without_an_address(monkeypatch):
    # State readable, no compute recorded: the instance is already gone, the
    # cleanup step skips itself, and the rest of the teardown still runs.
    async def empty(_opts):
        return None

    monkeypatch.setattr(workflow, "state_output", empty)
    result = await workflow.start_step(
        {**deletable_fixture(), "blue/event": "delete"}, env={})
    assert result["blue/exit"] == 0
    assert result.get("ip") is None


def test_graph_orders_private_stack():
    create = {"blue/event": "create"}
    delete = {"blue/event": "delete"}
    assert workflow.wire_fn("umami/start", create)[1:] == ("umami/infrastructure",)
    assert workflow.wire_fn("umami/infrastructure", create)[1:] == ("umami/dns",)
    assert workflow.wire_fn("umami/start", delete)[1:] == ("umami/ansible",)


def test_proxying_default_lives_here_not_only_in_dns_data():
    # This map seeds cloudflare-proxied, so tools.dns_data always sees the key
    # supplied and its own fallback never runs on the real path. Flipping only
    # the fallback would change nothing and move no golden -- assert the value
    # that actually decides it.
    assert workflow.DEFAULTS["cloudflare-proxied"] is True
