# Umami Package Skill

A reproducible [Colors](https://www.getcolors.ai) Package Skill for single-node
[Umami](https://umami.is) web analytics on DigitalOcean with origin TLS via Caddy,
Cloudflare DNS, and disaster recovery via scheduled R2 backups.

## Architecture

- **Umami**: `ghcr.io/umami-software/umami:postgresql-v2.14.0` on internal Docker network.
- **Database**: PostgreSQL 17 (`postgres:17-alpine`) with persistent data on `/var/lib/umami/postgres`.
- **Ingress**: Caddy (`caddy:2.11.4`) terminating TLS on 80/443 and proxying to port 3000.
- **Disaster Recovery**: Systemd timer `umami-backup.timer` executing `/usr/local/sbin/umami-backup` to `pg_dump` and upload via `rclone` to Cloudflare R2.
- **Compute**: Single DigitalOcean Droplet with dynamic account default VPC discovery in `ams3`.

## Quick Start

```sh
npx skills add getcolors/umami
cp .agents/skills/package-umami-green/green ./green
chmod +x green
./green build
./green create --dry-run
```

## Commands

```sh
bb test                        # run clojure test suite
bb golden                      # verify rendered golden templates
./scripts/launcher.sh          # test package launcher
./green build                  # render workdir (.colors/)
./green create --dry-run       # walk DAG without provider calls
```
