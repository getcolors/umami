terraform {
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean", version = "~> 2.0" }
  }
}
provider "digitalocean" {}

# Discover the configured region's account default at plan/apply time. The UUID
# is deliberately neither configured nor persisted in colors.yml.
data "digitalocean_vpc" "default" {
  name = "default-<{ digitalocean-region }>"
}

<% if ssh-keygen %># The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable. Never reference a
# literal key id here in keygen mode.
resource "digitalocean_ssh_key" "machine" {
  name       = "<{ profile }>"
  public_key = trimspace(file("<{ ssh-public-key-path }>"))
}

<% endif %>resource "digitalocean_droplet" "umami" {
  name     = "<{ compute-name }>"
  region   = "<{ digitalocean-region }>"
  size     = "<{ digitalocean-size }>"
  image    = "<{ digitalocean-image }>"
  vpc_uuid = data.digitalocean_vpc.default.id
<% if ssh-keygen %>  ssh_keys = [digitalocean_ssh_key.machine.id]
<% else %>  ssh_keys = ["<{ digitalocean-ssh-keys }>"]
<% endif %>  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

resource "digitalocean_firewall" "umami" {
  name        = "<{ compute-name }>-firewall"
  droplet_ids = [digitalocean_droplet.umami.id]
  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = <{ ssh-sources-hcl|safe }>
  }
  # 80 and 443 from the HTTP sources, and nothing else. A rule with no source
  # is not "closed" to DigitalOcean but an API error, so the HTTP rules are
  # emitted only when there is a source to name; an empty http-sources list
  # means no public HTTP at all.
  dynamic "inbound_rule" {
    for_each = length(<{ http-sources-hcl|safe }>) > 0 ? [
      { protocol = "tcp", port_range = "80" },
      { protocol = "tcp", port_range = "443" },
    ] : []
    content {
      protocol         = inbound_rule.value.protocol
      port_range       = inbound_rule.value.port_range
      source_addresses = <{ http-sources-hcl|safe }>
    }
  }
  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
}

output "params" {
  value = { provider = "digitalocean", ip = digitalocean_droplet.umami.ipv4_address, user = "root", sudoer = "root", name = "<{ compute-name }>"<% if ssh-keygen %>, ssh_key_id = digitalocean_ssh_key.machine.id<% endif %> }
}
