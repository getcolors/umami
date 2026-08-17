# CLAUDE.md

## Repository

`umami` is a Green-only Package Skill for a production-oriented single-machine
Umami web analytics deployment. It provisions a DigitalOcean Droplet in the
configured region, looks up that region's default VPC at runtime, manages
Cloudflare DNS, installs Docker Compose and Caddy, and runs pinned Umami
web analytics with colocated PostgreSQL 17.

The container images are:
- Umami: `ghcr.io/umami-software/umami:postgresql-v2.14.0`
- PostgreSQL: `postgres:17-alpine`
- Caddy: `caddy:2.11.4`

Desired state is `colors.yml`; secrets are `COLORS_PAR_*` environment values.
Never read `.envrc.private`, set `COLORS_PAR_PROFILE`, edit `.colors/`, weaken
`compute-prevent-destroy`, or expose PostgreSQL and internal Umami interfaces.

## Commands

```sh
bb test
bb golden
bb golden:accept
./scripts/launcher.sh
./green build
./green create --dry-run
./green create                 # requires explicit authorization
./green delete                 # guarded and destructive
```

Never read `.envrc.private`, edit `.colors/`, export `COLORS_PAR_PROFILE`, or
weaken `compute-prevent-destroy`. Build and dry-run are credential-free.

## Coupling

The package pins Green and ONCE in `deps.edn`. Develop with `GREEN_LIB_ROOT`,
`ONCE_LIB_ROOT`, and `UMAMI_LIB_ROOT`; finalize launchers only with `bb pin` after
a pushed package commit. Deployment launchers are copies, not symlinks.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.
