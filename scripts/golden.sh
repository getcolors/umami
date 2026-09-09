#!/usr/bin/env bash
set -euo pipefail

# Green's regression net against the committed goldens: render every fixture
# and diff against committed output. scripts/parity.sh is the net across
# colours.
#
# Two fixtures cover managed/external keys; inspect application changes before acceptance.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

accept=0
[[ ${1:-} == --accept ]] && accept=1

key_resource() {
  case $1 in
    digitalocean) echo 'resource "digitalocean_ssh_key" "machine"' ;;
  esac
}

status=0
for variant in colors keygen; do
  fixture="$tmp/$variant.yml"
  sed "s#WORKDIR#$tmp/work#" "$root/test/fixtures/$variant.yml" > "$fixture"
  (cd "$root/green" && UMAMI_LIB_ROOT="$root" ./green build -f "$fixture" >/dev/null)

  profile=$(sed -n 's/^profile: //p' "$fixture")
  provider=$(sed -n 's/^provider-compute: //p' "$fixture")
  actual="$tmp/work/$profile"
  golden="$root/test/resources/golden/local/$profile"
  mode=external; [[ $variant == keygen ]] && mode=managed
  python3 "$root/scripts/check-compute-plan.py" "$actual" "$mode"

  # No rendered artefact may carry a real secret into a committed golden.
  # Checked before --accept copies anything. POSIX grep on purpose: a missing
  # binary inside `if` is simply false, so the guard must not depend on one that
  # may be absent.
  if grep -rEq 'client-key-data|client-certificate-data|BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_' "$actual"; then
    echo "golden: a credential-shaped value was rendered for $profile" >&2; exit 1
  fi
  # A build that reached the real ~/.ssh would leak the operator's home into
  # committed bytes and make the goldens workstation-specific.
  if grep -rq "$HOME/.ssh" "$actual"; then
    echo "golden: $profile rendered a real home directory; build must use the placeholder" >&2; exit 1
  fi
  # The converge play takes its address from the inventory at run time, so
  # the rendered play itself must carry no machine address — a literal one
  # would be a workstation- or deployment-specific byte in a golden. Loopback
  # (the in-container health check) and the unspecified address are not
  # machine addresses and are allowed.
  if grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' "$actual/umami-ansible/main.yml" | grep -Evq '^(127\.0\.0\.1|0\.0\.0\.0)$'; then
    echo "golden: $profile rendered a machine address into the converge play" >&2; exit 1
  fi
  # SSH Config Standard §6: the local stage takes the address, the user and the
  # alias as Ansible extra-vars, never through Selmer, so its rendered playbook
  # carries no address at all. A dotted quad here means someone templated a
  # run-time fact and the goldens stopped being workstation-independent.
  if grep -rEq '([0-9]{1,3}\.){3}[0-9]{1,3}' "$actual/umami-ansible-local"; then
    echo "golden: $profile rendered an address into the local ssh_config stage" >&2; exit 1
  fi
  if [[ $accept == 1 ]]; then
    rm -rf "$golden"; mkdir -p "$(dirname "$golden")"; cp -a "$actual" "$golden"; continue
  fi
  [[ -d "$golden" ]] || { echo "golden missing for $profile; inspect build then run bb golden:accept" >&2; exit 1; }
  diff -ru "$golden" "$actual" || status=1
done

exit "$status"
