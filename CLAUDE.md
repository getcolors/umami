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

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.
