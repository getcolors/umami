---
name: package-umami-red
description: Provisions and operates production-oriented single-node Umami web analytics with PostgreSQL and Caddy on DigitalOcean.
license: MIT
---

# Umami with Red

Operate one Umami web analytics deployment from non-secret `colors.yml`. Read
[references/configuration.md](references/configuration.md) before changing
configuration or running a lifecycle operation.

## Safety

- Keep credentials in gitignored `.envrc.private` as `COLORS_PAR_*` variables.
- Never set `COLORS_PAR_PROFILE` or edit/commit `.colors/`.
- Keep `compute-prevent-destroy: true`; deletion requires separate explicit
  authorization and a one-run environment override.
- Build and dry-run before a real create.
- Never publish Umami port 3000 or PostgreSQL port 5432 directly.

```sh
./red build
./red create --dry-run
./red create
```

Real create includes public HTTPS health, synthetic event ingestion, and backup checks.
