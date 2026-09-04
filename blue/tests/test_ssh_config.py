"""Conformance with the workspace SSH Config Standard.

`config_path` reads `$HOME` at call time, so every test that needs a config
file points `HOME` at a temporary directory: nothing here may read or write
the real `~/.ssh/config`.
"""

import re

import pytest
from blue.scaffold import render_template
from conftest import fixture, keygen
from package_umami_blue import ssh_config, tools, workflow


@pytest.fixture
def home(tmp_path, monkeypatch):
    """Redirect `~/.ssh` into a fresh temporary home."""
    monkeypatch.setenv("HOME", str(tmp_path))
    return tmp_path


def write_config(home, content: str):
    config = home / ".ssh" / "config"
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text(content)
    return config


# §2 the alias and the identity file


def test_alias_is_the_profile():
    assert ssh_config.host_alias(fixture()) == "umami-fixture"


def test_identity_file_keeps_the_tilde(home):
    # An expanded home directory would make the rendered block differ per
    # workstation; OpenSSH expands the tilde itself.
    assert ssh_config.identity_file(fixture()) == "~/.ssh/umami-fixture"
    assert str(home) not in ssh_config.identity_file(fixture())


def test_the_marker_is_the_alias_alone():
    # The profile is <package>-<suffix>, so a marker carrying the package name
    # too would repeat it: "# BEGIN umami umami-vultr".
    assert ssh_config.begin_marker("umami-vultr") == "# BEGIN umami-vultr ANSIBLE MANAGED BLOCK"
    assert ssh_config.end_marker("umami-vultr") == "# END umami-vultr ANSIBLE MANAGED BLOCK"


def test_owned_markers_hold_the_one_marker():
    # Born conforming: no marker migration is in flight, so the set of markers
    # this package recognises as its own holds exactly the current one.
    assert ssh_config.owned_markers("umami-vultr") == {
        "begin": {"# BEGIN umami-vultr ANSIBLE MANAGED BLOCK"},
        "end": {"# END umami-vultr ANSIBLE MANAGED BLOCK"}}


# §5 never adopt


def test_host_patterns_are_read_from_a_host_line():
    assert ssh_config.host_patterns("Host umami-fixture") == ["umami-fixture"]
    assert ssh_config.host_patterns("  host   web umami-fixture  db ") == ["web", "umami-fixture", "db"]
    assert ssh_config.host_patterns("    HostName 192.0.2.1") is None
    assert ssh_config.host_patterns("Match host umami-fixture") is None


def test_a_foreign_stanza_is_found():
    lines = ["Host other", "    HostName 192.0.2.1", "", "Host umami-fixture"]
    assert ssh_config.foreign_stanza_line(lines, "umami-fixture") == 4


def test_our_own_block_is_not_foreign():
    alias = "umami-fixture"
    lines = [ssh_config.begin_marker(alias),
             f"Host {alias}",
             "    HostName 192.0.2.1",
             ssh_config.end_marker(alias)]
    assert ssh_config.foreign_stanza_line(lines, alias) is None


def test_a_stanza_after_our_block_is_still_foreign():
    alias = "umami-fixture"
    lines = [ssh_config.begin_marker(alias),
             f"Host {alias}",
             ssh_config.end_marker(alias),
             f"Host {alias}"]
    assert ssh_config.foreign_stanza_line(lines, alias) == 4


def test_a_block_under_a_package_prefixed_marker_is_foreign():
    # This package never wrote a `# BEGIN umami <alias>` marker, so a block
    # carrying one belongs to nobody this package knows and must stop the run
    # rather than being silently overwritten.
    alias = "umami-vultr"
    lines = [f"# BEGIN umami {alias} ANSIBLE MANAGED BLOCK",
             f"Host {alias}",
             f"# END umami {alias} ANSIBLE MANAGED BLOCK"]
    assert ssh_config.foreign_stanza_line(lines, alias) == 2


def test_a_multi_pattern_host_line_counts():
    assert ssh_config.foreign_stanza_line(["Host web umami-fixture db"], "umami-fixture") == 1


def test_an_unrelated_file_is_left_alone():
    assert ssh_config.foreign_stanza_line(["Host build", "Host umami-other"],
                                          "umami-fixture") is None


def test_adopt_error_names_the_file_and_the_line(home):
    config = write_config(home, "Host other\n    HostName 192.0.2.1\n\nHost umami-fixture\n    User root\n")
    error = ssh_config.adopt_error(fixture())
    assert str(config) in error
    assert "`Host umami-fixture` at line 4" in error
    assert "will not overwrite it" in error


def test_adopt_error_passes_our_own_block_and_a_missing_file(home):
    assert ssh_config.adopt_error(fixture()) is None
    write_config(home, f"{ssh_config.begin_marker('umami-fixture')}\n"
                       "Host umami-fixture\n    HostName 192.0.2.1\n"
                       f"{ssh_config.end_marker('umami-fixture')}\n")
    assert ssh_config.adopt_error(fixture()) is None


def test_preflight_refuses_rather_than_overwrites(monkeypatch):
    monkeypatch.setattr(ssh_config, "adopt_error", lambda _o: "already declares `Host x`")
    monkeypatch.setattr(ssh_config, "placement_error", lambda _o: None)
    result = ssh_config.preflight(fixture())
    assert result["blue/exit"] == 1
    assert "already declares" in result["blue/err"]


def test_preflight_passes_a_clean_file(monkeypatch):
    monkeypatch.setattr(ssh_config, "adopt_error", lambda _o: None)
    monkeypatch.setattr(ssh_config, "placement_error", lambda _o: None)
    assert ssh_config.preflight(fixture()).get("blue/exit") is None


def test_preflight_reads_the_redirected_file(home):
    # End to end through the real readers: a foreign stanza refuses, a leading
    # option refuses, and a clean file passes.
    write_config(home, "Host umami-fixture\n    HostName 192.0.2.1\n")
    refused = ssh_config.preflight(fixture())
    assert refused["blue/exit"] == 1
    assert "already declares" in refused["blue/err"]
    write_config(home, "ServerAliveInterval 60\nHost a\n")
    placed = ssh_config.preflight(fixture())
    assert placed["blue/exit"] == 1
    assert "line 1" in placed["blue/err"]
    write_config(home, "Host a\n    User root\n")
    assert ssh_config.preflight(fixture()).get("blue/exit") is None


# §5 placement. The block is written with insertbefore: BOF, because
# blockinfile anchors insertbefore on the *last* match and has no firstmatch.


def test_an_option_above_the_first_host_is_refused():
    # It is global today; a BOF insert would capture it into one stanza.
    assert ssh_config.leading_option_line(["ServerAliveInterval 60", "Host a"]) == 1
    assert ssh_config.leading_option_line(["# comment", "", "IdentitiesOnly yes", "Host a"]) == 3


def test_a_file_that_opens_with_a_host_is_fine():
    assert ssh_config.leading_option_line(["Host a", "    User root"]) is None
    assert ssh_config.leading_option_line(["# lead comment", "", "Host a", "    User root"]) is None
    assert ssh_config.leading_option_line(["Match host b", "    User root"]) is None


def test_a_file_of_only_comments_is_fine():
    assert ssh_config.leading_option_line(["# nothing here", ""]) is None


def test_placement_error_mentions_the_recovery(home):
    config = write_config(home, "# comment\n\n\nIdentitiesOnly yes\nHost a\n")
    error = ssh_config.placement_error(fixture())
    assert str(config) in error
    assert "line 4" in error
    assert "Host *" in error


# §6 build determinism


async def test_build_and_dry_run_never_read_the_config(monkeypatch):
    # The only readers are adopt_error and placement_error, and they must not
    # run on a rendered-only event. Making them raise proves nothing in the
    # build path calls them.
    def forbidden(_opts):
        raise RuntimeError("read ~/.ssh/config")
    monkeypatch.setattr(ssh_config, "adopt_error", forbidden)
    monkeypatch.setattr(ssh_config, "placement_error", forbidden)
    for opts in [{**fixture(), "blue/event": "build"},
                 {**keygen(), "blue/event": "build"},
                 {**fixture(), "blue/event": "create", "blue/dry-run": True}]:
        assert (await workflow.start_step(opts, env={}))["blue/exit"] == 0


def test_the_local_play_renders_no_address():
    # Address, user and alias are run-time facts and travel as extra-vars, so
    # the rendered playbook carries none of them.
    data = tools.ansible_local_data({**fixture(), "ip": "203.0.113.7"})
    assert data["ssh-config-identity-file"] == "~/.ssh/umami-fixture"


def test_the_local_stage_renders_three_files():
    targets = [str(spec["target"]) for spec in tools.ansible_local_specs(fixture())]
    assert any(t.endswith("/ansible.cfg") for t in targets)
    assert any(t.endswith("/inventory.ini") for t in targets)
    assert any(t.endswith("/main.yml") for t in targets)
    assert all("umami-ansible-local" in t for t in targets)


# §3 the identity file follows keygen mode


def test_keygen_mode_decides_the_identity_lines():
    assert tools.ansible_local_data(keygen())["ssh-keygen"] is True
    assert tools.ansible_local_data(fixture())["ssh-keygen"] is False


def _render_play(opts: dict) -> str:
    return render_template(tools.template("ansible-local", "main.yml"),
                           tools.ansible_local_data(opts), tools.template_opts)


def test_the_rendered_play_carries_the_identity_pair_only_in_keygen_mode():
    keygen_play = _render_play(keygen())
    optout_play = _render_play(fixture())
    assert "IdentityFile ~/.ssh/umami-keygen-fixture" in keygen_play
    assert "IdentitiesOnly yes" in keygen_play
    # The header comment names the pair; the rendered option lines must not.
    assert "IdentityFile ~/.ssh/" not in optout_play
    assert "IdentitiesOnly yes" not in optout_play
    # Address, user and alias are Ansible's, never Selmer's.
    for play in (keygen_play, optout_play):
        assert "insertbefore: BOF" in play
        assert "Host {{ host_alias }}" in play
        assert "HostName {{ ip }}" in play
        assert "StrictHostKeyChecking accept-new" in play
        assert re.search(r"([0-9]{1,3}\.){3}[0-9]{1,3}", play) is None


# §4 lifecycle


def test_create_writes_the_block_after_compute_and_before_convergence():
    create = {"blue/event": "create"}
    assert workflow.wire_fn("umami/infrastructure", create)[1:] == ("umami/ssh-config",)
    assert workflow.wire_fn("umami/ssh-config", create)[1:] == ("umami/dns",)


def test_delete_removes_the_block_before_the_destroy():
    # The opposite of the keypair, which goes last. A stale block is harmless;
    # a key removed early locks the operator out of a machine that still
    # exists.
    delete = {"blue/event": "delete"}
    assert workflow.wire_fn("umami/dns", delete)[1:] == ("umami/ssh-config",)
    assert workflow.wire_fn("umami/ssh-config", delete)[1:] == ("umami/infrastructure",)
    assert workflow.wire_fn("umami/infrastructure", delete)[1:] == ("umami/ssh-cleanup",)
