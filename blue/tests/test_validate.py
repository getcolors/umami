from pathlib import Path

from conftest import fixture
from package_umami_blue import validate

RESOURCES = (Path(__file__).resolve().parents[1]
             / "src" / "package_umami_blue" / "resources")


def test_fixture_is_valid():
    assert validate.state_errors(fixture()) == []


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
