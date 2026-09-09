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




# --- the compute-provider registry






def test_unselected_provider_keys_are_ignored_not_refused():
    # One colors.yml may carry another provider's block; only the selected
    # provider's keys are read. `digitalocean-https-sources`, which older
    # desired state carries, is likewise accepted and ignored.
    assert validate.state_errors(fixture({"vultr-plan": "vc2-2c-4gb", "vultr-os-id": "ubuntu"})) == []
    assert validate.state_errors(fixture({"digitalocean-https-sources": ["0.0.0.0/0"]})) == []
    assert any("compute deployment" in e
               for e in validate.state_errors(fixture({"digitalocean-size": None})))


def test_absent_machine_key_selects_keygen():
    assert validate.keygen(keygen())
    assert not validate.keygen(fixture())
    # Absence, not a flag, is the switch.
    assert validate.keygen(fixture({"digitalocean-ssh-keys": None}))






# --- the network contract, wired through state_errors with ONCE's messages






# --- provider checks run only for the selected provider




def test_reports_all_errors():
    errors = validate.state_errors(fixture({
        "umami-host": "bad", "caddy-image": "floating",
        "backup-retention-days": -1,
        "provider-dns": "other", "digitalocean-vpc-uuid": "forbidden"}))
    assert len(errors) >= 5
    for part in ["host", "image", "retention", "provider-dns", "compute deployment"]:
        assert any(part in e for e in errors), part




def test_profile_overlay_is_refused():
    assert validate.env_errors({"COLORS_PAR_PROFILE": "other"})
    assert not validate.env_errors({})




def test_accepts_the_alternate_app_secret_name():
    errors = "\n".join(validate.secret_errors(fixture({"umami-app-secret": "alternate"})))
    assert "COLORS_PAR_APP_SECRET_KEY" not in errors


def test_compose_template_carries_no_default_credential():
    compose = (RESOURCES / "tools" / "ansible" / "compose.yml").read_text()
    assert "default('umami'" not in compose
    assert "secret_hash_key" not in compose.lower()
    # The password reaches Umami inside a URL, so it must be percent-encoded.
    assert "urlencode | replace('/', '%2F')" in compose
