from pathlib import Path

from conftest import fixture, keygen
from package_umami_blue import validate

RESOURCES = (Path(__file__).resolve().parents[1]
             / "src" / "package_umami_blue" / "resources")


def test_fixture_is_valid():
    assert validate.state_errors(fixture()) == []


def test_keygen_fixture_is_valid():
    assert validate.state_errors(keygen()) == []


# --- the spec handed to ONCE


def test_the_spec_carries_this_packages_registry_sources_and_default():
    # The operations are ONCE's; this is the data they run over. A colour
    # whose registry, sources or default drifts fails here, in that colour.
    assert set(validate.spec["registry"]) == {"digitalocean"}
    assert validate.spec["registry"] is validate.compute_providers
    assert validate.spec["registry"]["digitalocean"] == {
        "required": ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources", "digitalocean-http-sources"],
        "secrets": ["do-token"],
        "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
    }
    assert validate.spec["sources"] == {"non_empty": ["ssh-sources"],
                                        "may_be_empty": ["http-sources"]}
    # DigitalOcean: the default is what a legacy state without params.provider
    # is, and every state this package ever wrote is a DigitalOcean one.
    assert validate.spec["default"] == "digitalocean"
    assert validate.spec["default"] == validate.default_compute_provider
    assert "name_rules" not in validate.spec, "the name rules are ONCE's"


# --- the compute-provider registry


def test_compute_provider_must_be_one_the_package_has_a_template_for():
    # The registry is the only list; a provider accepted here with no template
    # directory would fail at render time instead of at validation.
    errors = validate.state_errors(fixture({"provider-compute": "vultr"}))
    assert ":provider-compute must be one of digitalocean" in errors


def test_name_and_machine_key_are_never_required():
    # `digitalocean-name` is an optional override of the profile and
    # `digitalocean-ssh-keys` is meaningful by its absence, so neither may be
    # in the registry's required list -- a required machine key would make
    # keygen mode unreachable.
    for entry in validate.compute_providers.values():
        for key in entry["required"]:
            assert not key.endswith("-name"), key
            assert not key.endswith("-ssh-keys"), key
    assert validate.state_errors(
        fixture({"digitalocean-name": None, "digitalocean-ssh-keys": None})) == []


def test_unselected_provider_keys_are_ignored_not_refused():
    # One colors.yml may carry another provider's block; only the selected
    # provider's keys are read. `digitalocean-https-sources`, which older
    # desired state carries, is likewise accepted and ignored.
    assert validate.state_errors(fixture({"vultr-plan": "vc2-2c-4gb", "vultr-os-id": "ubuntu"})) == []
    assert validate.state_errors(fixture({"digitalocean-https-sources": ["0.0.0.0/0"]})) == []
    assert any("digitalocean-size" in e
               for e in validate.state_errors(fixture({"digitalocean-size": None})))


def test_absent_machine_key_selects_keygen():
    assert validate.keygen(keygen())
    assert not validate.keygen(fixture())
    # Absence, not a flag, is the switch.
    assert validate.keygen(fixture({"digitalocean-ssh-keys": None}))


def test_compute_name_falls_back_to_the_profile():
    assert validate.compute_name(fixture()) == "umami-fixture"
    assert validate.compute_name(keygen()) == "umami-keygen-fixture"
    assert validate.compute_name(fixture({"digitalocean-name": "custom"})) == "custom"
    assert validate.compute_key(fixture(), "ssh-sources") == "digitalocean-ssh-sources"


def test_compute_credentials_follow_the_provider():
    assert validate.tofu_env(fixture(), "provider-compute") == \
        {"do-token": "DIGITALOCEAN_TOKEN"}
    assert validate.tofu_env(fixture({"provider-compute": "vultr"}), "provider-compute") == {}


# --- the network contract, wired through state_errors with ONCE's messages


def test_ssh_sources_must_not_be_empty():
    # A machine nobody can reach is not a deployment; an empty HTTP list is
    # simply no public HTTP.
    assert ":digitalocean-ssh-sources must list at least one CIDR" in \
        validate.state_errors(fixture({"digitalocean-ssh-sources": []}))
    assert validate.state_errors(fixture({"digitalocean-http-sources": []})) == []


def test_malformed_sources_are_refused_before_any_provider_call():
    assert ':digitalocean-http-sources entry "203.0.113.0" is not an IPv4 or IPv6 CIDR' in \
        validate.state_errors(fixture({"digitalocean-http-sources": ["203.0.113.0"]}))
    assert ':digitalocean-ssh-sources entry "nope" is not an IPv4 or IPv6 CIDR' in \
        validate.state_errors(fixture({"digitalocean-ssh-sources": ["0.0.0.0/0", "nope"]}))
    assert validate.state_errors(
        fixture({"digitalocean-ssh-sources": ["2001:db8::/32", "203.0.113.4/32"]})) == []


# --- provider checks run only for the selected provider


def test_provider_checks_are_scoped_to_the_selected_provider():
    # DigitalOcean's VPC keys are refused on DigitalOcean, and the resolved
    # droplet name is held to DigitalOcean's rules.
    assert any("vpc-uuid" in e for e in
               validate.state_errors(fixture({"digitalocean-vpc-uuid": "forbidden"})))
    assert any("digitalocean-name must be a hostname-like name" in e for e in
               validate.state_errors(fixture({"digitalocean-name": "Not Valid"})))


def test_reports_all_errors():
    errors = validate.state_errors(fixture({
        "umami-host": "bad", "caddy-image": "floating",
        "backup-retention-days": -1,
        "provider-dns": "other", "digitalocean-vpc-uuid": "forbidden"}))
    assert len(errors) >= 5
    for part in ["host", "image", "retention", "provider-dns", "vpc-uuid"]:
        assert any(part in e for e in errors), part


def test_forbids_vpc_configuration():
    assert any("must be absent" in e for e in validate.state_errors(
        fixture({"digitalocean-vpc-cidr": "10.0.0.0/16"})))


def test_profile_overlay_is_refused():
    assert validate.env_errors({"COLORS_PAR_PROFILE": "other"})
    assert not validate.env_errors({})


def test_names_all_package_secrets():
    errors = "\n".join(validate.secret_errors(fixture({"provider-backend": "r2"})))
    for name in ["COLORS_PAR_DO_TOKEN", "COLORS_PAR_CLOUDFLARE_API_TOKEN",
                 "COLORS_PAR_R2_ACCESS_KEY_ID", "COLORS_PAR_R2_SECRET_ACCESS_KEY",
                 "COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID",
                 "COLORS_PAR_BACKUP_R2_SECRET_ACCESS_KEY",
                 "COLORS_PAR_POSTGRES_PASSWORD", "COLORS_PAR_APP_SECRET_KEY",
                 "COLORS_PAR_UMAMI_ADMIN_PASSWORD"]:
        assert name in errors, name


def test_accepts_the_alternate_app_secret_name():
    errors = "\n".join(validate.secret_errors(fixture({"umami-app-secret": "alternate"})))
    assert "COLORS_PAR_APP_SECRET_KEY" not in errors


def test_compose_template_carries_no_default_credential():
    compose = (RESOURCES / "tools" / "ansible" / "compose.yml").read_text()
    assert "default('umami'" not in compose
    assert "secret_hash_key" not in compose.lower()
    # The password reaches Umami inside a URL, so it must be percent-encoded.
    assert "urlencode | replace('/', '%2F')" in compose
