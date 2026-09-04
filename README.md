# Umami Package Skill

A reproducible [Colors](https://www.getcolors.ai) tri-colour Package Skill
(green, red, blue) for single-node [Umami](https://umami.is) web analytics on
DigitalOcean with origin TLS via Caddy, Cloudflare DNS, and disaster recovery
via scheduled R2 backups.

## Architecture

- **Umami**: `ghcr.io/umami-software/umami:postgresql-v2.14.0` on internal Docker network.
- **Database**: PostgreSQL 17 (`postgres:17-alpine`) with persistent data on `/var/lib/umami/postgres`.
- **Ingress**: Caddy (`caddy:2.11.4`) terminating TLS on 80/443 and proxying to port 3000.
- **Disaster Recovery**: Systemd timer `umami-backup.timer` executing `/usr/local/sbin/umami-backup` to `pg_dump` and upload via `rclone` to Cloudflare R2.
- **Compute**: Single DigitalOcean Droplet with dynamic account default VPC
  discovery, selected by `provider-compute` from a one-entry registry. The
  provider operations — selection, the CIDR checks, the rebuild-only switch
  rule — are ONCE's `compute` namespace over that registry (the workspace
  Compute Provider Standard).
- **Access**: The machine keypair is generated and owned by the deployment at
  `~/.ssh/<profile>` (the SSH Keypair Standard); set `digitalocean-ssh-keys` to
  an existing account key to opt out.
- **Reach**: `ssh <profile>` works: the package writes a managed block in
  `~/.ssh/config` (the SSH Config Standard) on create and removes it on delete.

## Quick Start

```sh
npx skills add getcolors/umami
cp .agents/skills/package-umami-green/green ./green
chmod +x green
./green build
./green create --dry-run
```

The same deployment can run through the TypeScript (`package-umami-red`) or
Python (`package-umami-blue`) implementation — all three render byte-identical
artifacts from one `colors.yml`.

`build` and `create --dry-run` need no credentials and contact nothing, which
makes them the safe way to check a `colors.yml` edit.

## Development

```sh
cd green && bb test      # unit tests (canonical Clojure implementation)
cd green && bb golden    # render both fixtures and diff against committed output
cd red && bun test && bun run typecheck   # TypeScript implementation
cd blue && uv run pytest                  # Python implementation
./scripts/parity.sh      # three colours, two keypair modes, byte-identical trees
./scripts/launcher.sh    # launcher payload and profile-guard checks
```

Point the launchers at working trees with `UMAMI_LIB_ROOT`, `GREEN_LIB_ROOT`
and `ONCE_LIB_ROOT`.
