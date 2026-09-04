from datetime import datetime
from pathlib import Path

import pytest
from blue.scaffold import render_template
from conftest import fixture, keygen
from package_umami_blue import tools

RESOURCES = (Path(__file__).resolve().parents[1]
             / "src" / "package_umami_blue" / "resources")


def _render_infrastructure(opts: dict) -> str:
    """The compute template for `opts`' provider, rendered as `build` would."""
    return render_template(tools.template(f"infrastructure.{opts.get('provider-compute')}", "main.tf"),
                           tools.infrastructure_data(opts), tools.template_opts)


async def test_delete_cleanup_skips_when_state_has_no_compute(tmp_path):
    # With the instance already gone the inventory would render 192.0.2.10;
    # there is no host to reach, so the step must not run the playbook and the
    # teardown must continue past it.
    async def refuse(*_args, **_kwargs):
        raise AssertionError("playbook must not run")

    result = await tools.ansible_step(
        fixture({"blue/event": "delete", "workdir": str(tmp_path)}), run_fn=refuse)
    assert result["blue/exit"] == 0
    assert result["umami/cleanup"] == "skipped-no-compute"


async def test_delete_cleanup_targets_the_adopted_address(tmp_path):
    # When the start step recovered the instance address from state, the
    # cleanup playbook runs against it, never the documentation fallback.
    async def record(opts, _specs, **_kwargs):
        return {**opts, "blue/exit": 0, "ran-against": opts.get("ip")}

    result = await tools.ansible_step(
        fixture({"blue/event": "delete", "ip": "203.0.113.7",
                 "workdir": str(tmp_path)}), run_fn=record)
    assert result["ran-against"] == "203.0.113.7"


def test_infrastructure_discovers_default_vpc():
    data = tools.infrastructure_data(fixture())
    assert tools.cidrs(data, "digitalocean-http-sources") == ["0.0.0.0/0", "::/0"]
    assert "0.0.0.0/0" in data["http-sources-hcl"]


def test_hostname_is_provider_neutral():
    # The playbook used digitalocean-name, which renders empty without the
    # override; the resolved name is what every label derives from.
    assert tools.compute_name(fixture()) == "umami-fixture"
    assert tools.compute_name(fixture({"digitalocean-name": None})) == "umami-fixture"
    assert "<{ compute-name }>" in (RESOURCES / "tools" / "ansible" / "main.yml").read_text()


def test_infrastructure_data_carries_the_name_and_the_keypair_mode():
    # One resolved name and one mode reach every template, so no template
    # branches on the provider or re-derives either.
    optout = tools.infrastructure_data(fixture())
    assert optout["compute-name"] == "umami-fixture"
    assert optout["ssh-keygen"] is False
    generated = tools.infrastructure_data(keygen())
    assert generated["compute-name"] == "umami-keygen-fixture"
    assert generated["ssh-keygen"] is True
    assert tools.ansible_data(keygen())["ssh-keygen"] is True
    assert tools.ansible_data(fixture())["ssh-keygen"] is False


def test_templates_name_the_machine_from_one_resolved_value():
    # Every label -- droplet name, firewall name and params.name --
    # interpolates compute-name, never a provider key or the profile directly.
    template = (RESOURCES / "tools" / "infrastructure" / "digitalocean" / "main.tf").read_text()
    assert "<{ digitalocean-name }>" not in template
    assert 'name     = "<{ compute-name }>"' in template
    assert 'provider = "digitalocean"' in template
    rendered = _render_infrastructure(fixture({"digitalocean-name": "custom-label"}))
    assert 'name     = "custom-label"' in rendered
    assert 'name        = "custom-label-firewall"' in rendered
    assert 'name = "custom-label"' in rendered


def test_empty_http_sources_renders_no_public_http():
    # An empty `digitalocean-http-sources` is allowed and means no public HTTP:
    # the 80/443 rules are a dynamic block over an empty list, because a rule
    # with no source is an API error to DigitalOcean, not a closed port. SSH
    # stays.
    empty = _render_infrastructure(fixture({"digitalocean-http-sources": []}))
    assert "length([]) > 0 ? [" in empty
    assert "source_addresses = []" in empty
    assert 'port_range       = "22"' in empty
    full = _render_infrastructure(fixture())
    assert 'length(["0.0.0.0/0", "::/0"]) > 0 ? [' in full
    assert '{ protocol = "tcp", port_range = "80" }' in full
    assert '{ protocol = "tcp", port_range = "443" }' in full
    assert 'udp", port_range = "443' not in full


def test_keygen_mode_renders_the_key_resource_and_opt_out_keeps_the_literal():
    generated = _render_infrastructure(keygen())
    assert 'resource "digitalocean_ssh_key" "machine"' in generated
    assert "ssh_keys = [digitalocean_ssh_key.machine.id]" in generated
    assert "ssh_key_id = digitalocean_ssh_key.machine.id" in generated
    opted_out = _render_infrastructure(fixture())
    assert "digitalocean_ssh_key" not in opted_out
    assert 'ssh_keys = ["58495393"]' in opted_out
    assert "ssh_key_id" not in opted_out


def test_dns_computes_zone_and_record():
    json_text = tools.dns_json(tools.dns_data(fixture({"ip": "192.0.2.10"})))
    assert "umami.example.com" in json_text
    assert "192.0.2.10" in json_text
    assert '"proxied" : true' in json_text


def test_dns_proxying_defaults_on_and_can_be_declined():
    assert tools.dns_data(fixture())["cloudflare-proxied"] is True
    assert '"proxied" : false' in tools.dns_json(tools.dns_data(
        fixture({"ip": "192.0.2.10", "cloudflare-proxied": False})))


def test_inventory_keeps_one_target():
    inventory = tools.inventory(fixture({"ip": "192.0.2.10"}))
    assert "192.0.2.10" in inventory
    assert "umami-fixture" in inventory


def test_ingestion_is_judged_by_the_stored_row_not_the_status():
    assert tools.ingestion_verdict("200", 4, 5) == "ingested"
    # The failure this gate exists for: the endpoint accepts and nothing lands.
    assert tools.ingestion_verdict("200", 4, 4) == "dropped"
    assert tools.ingestion_verdict("202", 4, None) == "dropped"
    assert tools.ingestion_verdict("400", 4, 4) == "rejected"
    assert tools.ingestion_verdict(None, 4, 4) == "unreachable"


def test_backup_must_be_fresh_and_non_empty():
    since = datetime.fromisoformat("2026-08-17T03:00:00+00:00")

    def entry(size, mod_time):
        return {"Size": size, "ModTime": mod_time}

    assert tools.fresh_backup([entry(1024, "2026-08-17T03:00:05Z")], since)
    assert tools.fresh_backup([entry(1024, "2026-08-17T05:00:05+02:00")], since)
    # A stale object from an earlier run must not certify today's drill.
    assert not tools.fresh_backup([entry(1024, "2026-08-16T03:00:05Z")], since)
    # An empty upload is not a backup.
    assert not tools.fresh_backup([entry(0, "2026-08-17T03:00:05Z")], since)
    assert not tools.fresh_backup([], since)
    assert not tools.fresh_backup(None, since)


def test_backup_proves_it_restores_and_prunes_the_bucket():
    # An archive that exists is not an archive that restores, and pruning only
    # the local disk leaves R2 growing without bound.
    script = (RESOURCES / "tools" / "ansible" / "backup").read_text()
    assert "CREATE DATABASE" in script
    assert "information_schema.tables" in script
    assert "rclone delete --min-age" in script
    # The restore must happen before the upload, so a bad dump never lands.
    restore = script.index("restore check restored no tables")
    upload = script.index("rclone copyto")
    assert restore < upload


def test_acceptance_provisions_its_own_website():
    # With no website the step reports "not-configured" and sends nothing, so
    # the synthetic request is never exercised — exactly how the sibling
    # package carried a payload its API had always rejected.
    src = (Path(__file__).resolve().parents[1]
           / "src" / "package_umami_blue" / "tools.py").read_text()
    assert "ensure_acceptance_website" in src
    assert "umami-acceptance-website-domain" in src
    # Never the operator's own website.
    assert "select website_id from website limit 1" not in src
    # Idempotent, and the id must look like one.
    assert "where not exists" in src
    assert "[0-9a-f-]{36}" in src


def test_a_missing_compute_output_fails_loudly():
    # The documentation address belongs to build and dry-run. Merging it into a
    # real converge would point Ansible at TEST-NET instead of failing.
    assert tools.resolved_compute({}, {"ip": "192.0.2.10"}, {"ip": "1.2.3.4"})["ip"] == "1.2.3.4"
    assert tools.resolved_compute({}, {"ip": "192.0.2.10"}, None)["blue/exit"] == 1
    assert tools.resolved_compute({}, {"ip": "192.0.2.10"}, {})["blue/exit"] == 1
    assert "blue/exit" not in tools.resolved_compute({}, {"ip": "192.0.2.10"}, {"ip": "5.6.7.8"})


@pytest.fixture(scope="module")
def caddyfile():
    return (RESOURCES / "tools" / "ansible" / "Caddyfile").read_text()


@pytest.fixture(scope="module")
def compose():
    return (RESOURCES / "tools" / "ansible" / "compose.yml").read_text()


@pytest.fixture(scope="module")
def playbook():
    return (RESOURCES / "tools" / "ansible" / "main.yml").read_text()


def test_caddy_access_logging_is_on_and_bounded(caddyfile, compose):
    # Access logging is off by default in Caddy, so a successful request left
    # no trace and ingestion had no request-level evidence to debug from.
    assert "log {" in caddyfile
    assert "output stdout" in caddyfile
    # On, but bounded: json-file never rotates on its own and this endpoint
    # writes a line per request.
    assert "max-size" in compose
    assert "max-file" in compose


def test_caddy_reload_is_convergent_not_change_triggered(playbook):
    # The Caddyfile is a single-file bind mount, so copy-by-rename leaves the
    # container on the old inode and `up -d` will not recreate an unchanged
    # service: the host file looked right while Caddy served the old config.
    assert "--force-recreate caddy" in playbook
    assert "sha256sum /etc/caddy/Caddyfile" in playbook
    # And it must run once the stack is up, or it recreates against a compose
    # file that has not been rendered yet.
    converge = playbook.index("Build and converge pinned containers")
    reload_ = playbook.index("--force-recreate caddy")
    health = playbook.index("Wait for Umami health endpoint")
    assert converge < reload_ < health


def test_access_log_records_the_visitor_not_the_proxy(caddyfile):
    # Behind the Cloudflare proxy every connection arrives from an edge
    # address, so without trusted_proxies Caddy attributes each request to
    # Cloudflare and the access log answers "who sent this?" with the proxy.
    assert "trusted_proxies static" in caddyfile
    assert "162.158.0.0/15" in caddyfile
    assert "2400:cb00::/32" in caddyfile
