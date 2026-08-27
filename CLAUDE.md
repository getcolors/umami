# CLAUDE.md

## Repository

`umami` is a tri-colour Package Skill (green, red, blue) for a
production-oriented single-machine Umami web analytics deployment. It
provisions a DigitalOcean Droplet in the configured region, looks up that
region's default VPC at runtime, manages Cloudflare DNS, installs Docker
Compose and Caddy, and runs pinned Umami web analytics with colocated
PostgreSQL 17.

The container images are:
- Umami: `ghcr.io/umami-software/umami:postgresql-v2.14.0`
- PostgreSQL: `postgres:17-alpine`
- Caddy: `caddy:2.11.4`

Desired state is `colors.yml`; secrets are `COLORS_PAR_*` environment values.
Never read `.envrc.private`, set `COLORS_PAR_PROFILE`, edit `.colors/`, weaken
`compute-prevent-destroy`, or expose PostgreSQL and internal Umami interfaces.

## Commands

The three implementations live in the tri-colour layout, matching `netbird`
and `clickstack`: canonical Clojure in `green/` (`green/bb.edn`,
`green/deps.edn`, `green/src/`, `green/tasks/`, tests under `green/test/clj`),
TypeScript/Bun in `red/`, and Python/uv in `blue/`. Green is canonical: a
behavioural change lands in all three colours in the same commit and passes
`scripts/parity.sh`, which renders the fixture through every colour and diffs
the trees — and the colour template trees (`red/resources`, blue's embedded
`resources/`) — byte for byte. The fixture and the goldens are shared across
colours at the repository root — `test/fixtures/` and `test/resources/golden/`
— with `green/test/fixtures` and `green/test/resources` symlinks pointing at
them. Each colour dir holds a launcher symlink to its skill payload
(`green/green`, `red/red`, `blue/blue`).

```sh
cd green && bb test
cd green && bb golden
cd green && bb golden:accept
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, byte for byte
./scripts/launcher.sh          # from the repository root
cd green && ./green build
cd green && ./green create --dry-run
cd green && ./green create     # requires explicit authorization
cd green && ./green delete     # guarded and destructive
```

Never read `.envrc.private`, edit `.colors/`, export `COLORS_PAR_PROFILE`, or
weaken `compute-prevent-destroy`. Build and dry-run are credential-free.

## Coupling

The package pins Green and ONCE in `green/deps.edn`, the Red SDK and
`package-once-red` in `red/package.json`, and the Blue SDK and
`package-once-blue` in `blue/pyproject.toml`. All three colours pin ONCE at
the **same rev** — ONCE's own parity is what guarantees its colours agree per
commit. ONCE supplies the backend provider registry the validators consume.
Use `GREEN_LIB_ROOT`, `ONCE_LIB_ROOT`, and `UMAMI_LIB_ROOT` for working-tree
development (`UMAMI_LIB_ROOT` names the repository root for every colour; red
also accepts the `red/` dir directly). Final launchers use a pushed SHA
managed by `bb pin`, which stamps all three payloads from their unpinned birth
forms; deployment launchers are copies, not symlinks.

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
