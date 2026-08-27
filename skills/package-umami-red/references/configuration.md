# Configuration

Required non-secret keys are demonstrated in the package `colors.yml`.

The enabled deployment requires these private environment variables:

```text
COLORS_PAR_DO_TOKEN
COLORS_PAR_CLOUDFLARE_API_TOKEN
COLORS_PAR_R2_ACCESS_KEY_ID
COLORS_PAR_R2_SECRET_ACCESS_KEY
COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID
COLORS_PAR_BACKUP_R2_SECRET_ACCESS_KEY
```

Never set `COLORS_PAR_PROFILE`. No VPC UUID or CIDR is accepted: the package
looks up `default-<digitalocean-region>` at runtime and never creates a VPC.

`umami-version` (or `umami-image`), `postgres-version` (or `postgres-image`),
and `caddy-image` are exact pins. Backups dump PostgreSQL to gzip and upload to
R2, retaining local backups according to `backup-retention-days`.
