"""Conformance tests for the SSH Keypair Standard, as this package wires it.

The matrix itself is ONCE's and tested there; these prove the delegation:
that absence of `digitalocean-ssh-keys` selects keygen, that a build renders
the placeholder path and never names `$HOME`, that opt-out passes through
untouched, and that the create matrix, the preflight and the cleanup reach
ONCE with this package's fixtures. Every test redirects `~/.ssh` into a
temporary home: nothing here may touch the real one.
"""

import os
import stat
from pathlib import Path

import pytest
from conftest import fixture, keygen
from package_umami_blue import ssh


@pytest.fixture
def home(tmp_path, monkeypatch):
    """Redirect `~/.ssh` into a fresh temporary home."""
    monkeypatch.setenv("HOME", str(tmp_path))
    return tmp_path


async def none_state(_opts):
    return None


def state(params):
    async def f(_opts):
        return params
    return f


def write(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


# ------------------------------------------------------------------ mode


def test_build_renders_a_stable_placeholder_path(home):
    # Goldens are committed, so a build must not name the operator's home.
    opts = ssh.with_machine_key({**keygen(), "blue/event": "build"})
    assert opts["ssh-public-key-path"].startswith(ssh.build_placeholder_dir)
    # ONCE's table decides which desired-state key carries the machine key.
    assert opts["ssh-public-key-path"] == opts["digitalocean-ssh-keys"]
    assert str(home) not in opts["ssh-private-key-path"]
    opted_out = ssh.with_machine_key({**fixture(), "blue/event": "build"})
    assert opted_out["digitalocean-ssh-keys"] == "58495393"
    assert opted_out.get("ssh-public-key-path") is None


def test_a_dry_run_renders_the_placeholder_too(home):
    opts = ssh.with_machine_key({**keygen(), "blue/event": "create", "blue/dry-run": True})
    assert opts["ssh-public-key-path"].startswith(ssh.build_placeholder_dir)


def test_real_events_render_the_real_path(home):
    opts = ssh.with_machine_key({**keygen(), "blue/event": "create"})
    assert opts["ssh-private-key-path"] == str(home / ".ssh" / "umami-keygen-fixture")
    assert opts["ssh-public-key-path"] == str(home / ".ssh" / "umami-keygen-fixture.pub")


def test_opt_out_passes_through_untouched(home):
    for event in ["build", "create", "delete"]:
        opts = ssh.with_machine_key({**fixture(), "blue/event": event})
        assert opts["digitalocean-ssh-keys"] == "58495393"
        assert opts.get("ssh-public-key-path") is None, event
        assert opts.get("ssh-keygen") is None, event


def test_identity_args_select_the_generated_key_only_in_keygen_mode(home):
    # The acceptance step's ssh threads these: in keygen mode nothing
    # guarantees an agent holds the key.
    opts = ssh.with_machine_key({**keygen(), "blue/event": "create"})
    assert ssh.identity_args(opts) == ["-o", "IdentitiesOnly=yes", "-i", opts["ssh-private-key-path"]]
    assert ssh.identity_args(ssh.with_machine_key({**fixture(), "blue/event": "create"})) == []


# -------------------------------------------------------- the create matrix


async def test_first_create_generates_the_keypair(home):
    opts = await ssh.ensure_key({**keygen(), "blue/event": "create"}, none_state)
    prv = home / ".ssh" / "umami-keygen-fixture"
    pub = home / ".ssh" / "umami-keygen-fixture.pub"
    assert "blue/err" not in opts, opts.get("blue/err")
    assert prv.exists()
    assert pub.exists()
    # ed25519, no passphrase, profile-named comment
    assert "ssh-ed25519" in pub.read_text()
    assert "umami-keygen-fixture managed by Colors" in pub.read_text()
    # 600 on the private key, 700 on ~/.ssh
    assert stat.S_IMODE(os.stat(prv).st_mode) == 0o600
    assert stat.S_IMODE(os.stat(home / ".ssh").st_mode) == 0o700


async def test_a_key_without_state_is_never_overwritten(home):
    prv = home / ".ssh" / "umami-keygen-fixture"
    write(prv, "irreplaceable")
    write(home / ".ssh" / "umami-keygen-fixture.pub", "ssh-ed25519 AAAA test")
    opts = await ssh.ensure_key({**keygen(), "blue/event": "create"}, none_state)
    assert opts["blue/exit"] == 1
    assert "no compute state is readable" in opts["blue/err"]
    # The message must make the human the authorization boundary.
    assert "survives" in opts["blue/err"]
    assert prv.read_text() == "irreplaceable", "the key on disk is left alone"


async def test_state_without_a_key_is_an_error(home):
    opts = await ssh.ensure_key({**keygen(), "blue/event": "create"},
                                state({"ip": "192.0.2.10"}))
    assert opts["blue/exit"] == 1
    assert "does not hold the machine key" in opts["blue/err"]


async def test_opt_out_generates_nothing(home):
    result = await ssh.ensure_key({**fixture(), "blue/event": "create"}, none_state)
    assert "blue/err" not in result
    assert not list(home.iterdir()), "opt-out mode must not touch ~/.ssh"


# ------------------------------------------------------------- preflight


def test_preflight_lists_keys_with_the_digitalocean_token(home):
    # ONCE selects the REST API and the token by provider; this proves the
    # delegation hands DigitalOcean its own credential.
    seen = []

    def capture(provider, token):
        seen.append((provider, token))
        return []
    ssh.preflight(ssh.with_machine_key({**keygen(), "blue/event": "create",
                                        "do-token": "do-secret", "vultr-api-key": "wrong"}), capture)
    assert seen == [("digitalocean", "do-secret")]


def test_preflight_refuses_a_foreign_key_and_says_do_not_delete_it(home):
    write(home / ".ssh" / "umami-keygen-fixture.pub", "ssh-ed25519 OURS comment")
    opts = ssh.preflight(
        ssh.with_machine_key({**keygen(), "blue/event": "create"}),
        lambda _p, _t: [{"id": "abc", "name": "umami-keygen-fixture",
                         "public": "ssh-ed25519 THEIRS"}])
    assert opts["blue/exit"] == 1
    assert "Do not delete it" in opts["blue/err"]


def test_preflight_is_skipped_in_opt_out_mode(home):
    def boom(_provider, _token):
        raise AssertionError("must not be called")
    opts = ssh.preflight({**fixture(), "blue/event": "create"}, boom)
    assert "blue/err" not in opts


# --------------------------------------------------------------- cleanup


def test_delete_removes_the_keypair(home):
    write(home / ".ssh" / "umami-keygen-fixture", "private")
    write(home / ".ssh" / "umami-keygen-fixture.pub", "public")
    ssh.cleanup_step({**keygen(), "blue/event": "delete", "ssh-keygen": True})
    assert not (home / ".ssh" / "umami-keygen-fixture").exists()
    assert not (home / ".ssh" / "umami-keygen-fixture.pub").exists()
    assert (home / ".ssh").exists(), "~/.ssh itself is the operator's, never removed"


def test_cleanup_is_inert_on_create_and_in_opt_out_mode(home):
    write(home / ".ssh" / "umami-keygen-fixture", "private")
    ssh.cleanup_step({**keygen(), "blue/event": "create", "ssh-keygen": True})
    assert (home / ".ssh" / "umami-keygen-fixture").exists()
    ssh.cleanup_step({**fixture(), "blue/event": "delete"})
    assert (home / ".ssh" / "umami-keygen-fixture").exists()
