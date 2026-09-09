---
name: package-umami-green
description: Provisions and operates production-oriented single-node Umami web analytics with PostgreSQL and Caddy on one VM through colors-compute.
license: MIT
---

# Umami with Green

Operate one Umami web analytics deployment from non-secret `colors.yml`. Read
[references/configuration.md](references/configuration.md) before changing
configuration or running a lifecycle operation.

## Compute ownership

The pinned `colors-compute` library owns provider selection, remote S3/R2
state, deployment coordination, machine keys, network policy and the single
node. This package supplies singleton topology and SSH/HTTP ingress, then
uses the returned address, login user and SSH identity for its application
steps. New provider support belongs in the library; consumers update its pin.
The application needs a supported Ubuntu image and sufficient memory for
Umami and its database. Build first to check adapter capabilities.

Use `umami-ssh-sources` and `umami-http-sources` for neutral CIDR
allowlists. Existing selected-provider source options remain compatible.
External account key references require `ssh-private-key-path`; external
private keys are never generated or removed. The local SSH block writes
`IdentityFile` only for a managed deployment key.

Existing `<profile>/umami-infrastructure.tfstate` is refused before
compute mutation. Do not remove it to bypass this check: migrate ownership
explicitly or destroy the old deployment through its original version first.
Unreadable state and provider mismatches fail closed.

The default adapter remains `digitalocean`. The node requests TCP22/80/443;
The application and database ports remain private to Compose.

## Safety

- Keep credentials in gitignored `.envrc.private` as `COLORS_PAR_*` variables.
- Never set `COLORS_PAR_PROFILE` or edit/commit `.colors/`.
- Keep `compute-prevent-destroy: true`; deletion requires separate explicit
  authorization and a one-run environment override.
- Build and dry-run before a real create.
- Never publish Umami port 3000 or PostgreSQL port 5432 directly.

```sh
./green build
./green create --dry-run
./green create
```

Real create includes public HTTPS health, synthetic event ingestion, and backup checks.
