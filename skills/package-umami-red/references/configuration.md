# Configuration

Required non-secret keys are demonstrated in the package `colors.yml`. The
package advertises one compute provider, `digitalocean`, through a one-entry
registry that conforms to the workspace Compute Provider Standard;
`provider-compute` selects it, and only the selected provider's keys and
credential are required. Keys of another provider are accepted and ignored.

## Credentials

Every deployment requires these private environment variables:

```text
COLORS_PAR_CLOUDFLARE_API_TOKEN
COLORS_PAR_POSTGRES_PASSWORD
COLORS_PAR_APP_SECRET_KEY                # or COLORS_PAR_UMAMI_APP_SECRET
COLORS_PAR_UMAMI_ADMIN_PASSWORD
COLORS_PAR_BACKUP_R2_ACCESS_KEY_ID       # or COLORS_PAR_R2_ACCESS_KEY_ID
COLORS_PAR_BACKUP_R2_SECRET_ACCESS_KEY   # or COLORS_PAR_R2_SECRET_ACCESS_KEY
```

plus the selected compute provider's:

```text
COLORS_PAR_DO_TOKEN          # provider-compute: digitalocean
```

and, with `provider-backend: r2`, the state bucket's
`COLORS_PAR_R2_ACCESS_KEY_ID` and `COLORS_PAR_R2_SECRET_ACCESS_KEY`.

Never set `COLORS_PAR_PROFILE`.

## Compute providers

### DigitalOcean (`provider-compute: digitalocean`)

| Key | Required | Meaning |
|---|---|---|
| `digitalocean-region` | yes | Droplet region, e.g. `ams3` |
| `digitalocean-size` | yes | Droplet size, e.g. `s-4vcpu-8gb` |
| `digitalocean-image` | yes | Image slug, `ubuntu-24-04-x64` |
| `digitalocean-ssh-sources` | yes | CIDRs admitted to TCP 22 |
| `digitalocean-http-sources` | yes | CIDRs admitted to TCP 80 and 443 |
| `digitalocean-name` | no | Droplet name; the profile by default |
| `digitalocean-ssh-keys` | no | An existing account key id; absent means keygen mode |

No VPC UUID or CIDR is accepted: the package looks up
`default-<digitalocean-region>` at runtime and never creates a VPC.
`digitalocean-https-sources`, which older desired state carries, is accepted
and ignored; 443 opens from `digitalocean-http-sources`.

### Firewall sources

`digitalocean-ssh-sources` must list at least one CIDR, and every entry of both
source keys must be a syntactically valid IPv4 or IPv6 CIDR; both are checked
before any provider call. An empty `digitalocean-http-sources` is allowed and
means no public HTTP: the template emits its 80 and 443 rules only when there
is a source to name, because a DigitalOcean rule with no source is an API
error at apply rather than a closed port. The provider firewall admits 22, 80
and 443 from those sources and nothing else.

### The machine keypair

When `digitalocean-ssh-keys` is absent (keygen mode, the default), the first
real `create` generates an ed25519 keypair at `~/.ssh/<profile>` and registers
it as an account key named after the profile; `delete` removes the local
keypair after the machine is destroyed. The key is not generated output: it
survives regeneration of `.colors/`, and a fresh clone on another workstation
does not carry it. A key on disk with no matching state, or an account key of
that name this deployment does not own, refuses the create rather than being
overwritten or adopted. Set `digitalocean-ssh-keys` to an existing account key
id to opt out; the package then creates and deletes no key material.

### The `~/.ssh/config` block

A real `create` writes one managed block into `~/.ssh/config`, after the
machine exists and before it is converged, so `ssh <profile>` needs no
address, no user and no `-i` flag:

```sshconfig
# BEGIN <profile> ANSIBLE MANAGED BLOCK
Host <profile>
    HostName <ip>
    User root
    Port 22
    IdentityFile ~/.ssh/<profile>      # keygen mode only
    IdentitiesOnly yes                 # keygen mode only
    StrictHostKeyChecking accept-new
    ForwardAgent no
# END <profile> ANSIBLE MANAGED BLOCK
```

The alias is the profile; there is no separate key for it. The `IdentityFile`
pair appears only in keygen mode, where the package knows the key because it
generated it; with `digitalocean-ssh-keys` set the operator's own arrangements
find the key. `delete` removes the block before the machine is destroyed (the
keypair, by contrast, goes after it). `build` and `--dry-run` never read the
file.

The block is inserted at the top of the file, because `ssh_config` takes the
first value it obtains and a `Host *` stanza above it would win on `User` and
`IdentityFile`. Two layouts make a real create refuse rather than rewrite the
file, each naming the file and the line: a `Host <profile>` stanza outside
the markers (remove or rename it if it is stale, or change `profile` if it
belongs to something else — the package never overwrites it), and an option
standing above the first `Host` or `Match` line, which is global today and
would be captured into this one stanza (move it below the managed block, or
into an explicit `Host *` stanza at the end of the file).

### Switching providers

Every provider shares one state key per profile, so switching is a rebuild:
`delete` on the provider recorded in state, then `create` on the new one. A
changed `provider-compute` on a profile whose state holds a machine is refused
on both `create` and `delete`; a state recorded before the package wrote the
provider is treated as DigitalOcean's.

## Images

`umami-version` (or `umami-image`), `postgres-version` (or `postgres-image`),
and `caddy-image` are exact pins.

## Backups

A systemd timer runs `umami-backup` on `backup-oncalendar`. Each run dumps
PostgreSQL to gzip, restores the dump into a scratch database before uploading
so an unrestorable archive fails the unit instead of reaching the bucket, and
uploads to R2 under the profile prefix. Retention applies to both sides:
`backup-retention-days` prunes the local directory and the
`r2:<bucket>/<profile>` prefix.
