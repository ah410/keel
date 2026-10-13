# Roadmap

## Where things stand (October 2026)

Keel covers the dashboard, apps (lifecycle, upgrades, rollback, settings, logs), storage (pools,
datasets, snapshots, scrubs, snapshot/replication/cloud sync tasks), alerts with optional
background notifications, services, updates, power, reporting, boot environments, network, the
audit log, VMs, LXC containers, SMB and NFS shares, users, multiple servers and a home screen
widget. It was built and tested against TrueNAS 26.0, on a real server and on an emulator against
`tools/mock-truenas`.

## What the API offers that Keel doesn't use yet

The 26.0 API has about 895 methods; Keel calls around 65. Most of the rest are admin setup that
belongs in the web interface (or is dangerous to do from a phone), and some are worth adding.
The groupings below are a judgement call, not a promise.

### Worth adding

| Feature | Methods | Why |
|---|---|---|
| Revoke API keys | `api_key.query`, `api_key.delete` | If a phone or laptop is lost, revoking its key from another device is the first thing you want |
| Active sessions | `auth.sessions`, `auth.terminate_session` | See who is logged in to the server and kick a session you don't recognise |
| Who's connected to shares | `smb.status`, `nfs.get_nfs4_clients`, `nfs.get_nfs3_clients` | Read-only and useful before disabling a share |
| Install apps from the catalog | `app.available`, `catalog.get_app_details`, `app.create` | Reuses the settings form built for editing |
| Delete apps | `app.delete` | Critical tier, type the app name |
| Pull new images | `app.pull_images` | Lighter than a redeploy when only images changed |
| Rsync tasks and cloud backups | `rsynctask.query`/`run`, `cloud_backup.query`/`sync` | Fit next to the existing data protection tasks |
| Cron jobs | `cronjob.query`, `cronjob.run` | Run a scheduled script on demand |
| Datasets | `pool.dataset.create`, `pool.dataset.update`, `pool.dataset.delete` | Quotas and compression from the phone; delete in the critical tier |
| Boot environments | `boot.environment.activate`, `keep`, `destroy` | Falling back to the previous release after a bad update |
| Reboot reason | `system.reboot.info` | Show "reboot required" and why on the dashboard |
| Users | `user.update` (lock, unlock, SMB access), `group.query` | Small, common account chores |

### Possible later

Snapshot task and replication task editing (`pool.snapshottask.create/update`,
`replication.create/update`), alert service setup (`alertservice.*`), the update train
(`update.config`), certificates read-only (`certificate.query`), and UPS status (`ups.config`).
These work fine but are rarely changed and have large forms.

### Not planned

Pool creation and disk replacement, network interface changes (a mistake can cut the phone off
from the server), directory services, Kerberos, iSCSI, NVMe-oF, Fibre Channel, JBOF, KMIP,
failover, IPMI, enclosures, VMware snapshots, TrueCommand, TrueNAS Connect, support tickets, system
config upload and reset, tunables, SED, ACL editing, and VM or container consoles. These are
admin setup tasks that are safer and easier in the web interface, or need a desktop-sized screen.

## Release work

F-Droid inclusion (metadata is in `fastlane/`), and an IzzyOnDroid listing in the meantime.
