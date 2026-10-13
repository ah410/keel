# TrueNAS API reference (as used by this app)

Confirmed on 2026-10-11 against a live TrueNAS SCALE server reporting API versions up to `v26.0.0`
(`GET /api/versions`), using the server's own docs at `/api/docs/current`.

## Transport

The REST API (`/api/v2.0/...`) is gone on 26.0; it returns 404. Everything goes through JSON-RPC 2.0
over a WebSocket at `wss://<host>[:port]/api/current`. Requests are `{"jsonrpc":"2.0","id":N,"method":"...","params":[...]}`
and `params` is always an array. Errors come back as `error.code` (`-32001` for a method call error)
with the useful detail in `error.data.errname` and `error.data.reason`, for example
`ENOTAUTHENTICATED`. Server pushes arrive as `collection_update` notifications with no `id`.

`core.ping` and `auth.mechanism_choices` work before login. The test server offered
`API_KEY_PLAIN`, `TOKEN_PLAIN`, `PASSWORD_PLAIN` and `SCRAM`.

## Authentication: SCRAM-SHA-512

The app only uses `SCRAM`, so the API key itself is never sent. Both messages go to `auth.login_ex` on
the same socket:

```json
{"mechanism": "SCRAM", "scram_type": "CLIENT_FIRST_MESSAGE", "rfc_str": "n,,n=<user>:<keyId>,r=<nonce>"}
{"mechanism": "SCRAM", "scram_type": "CLIENT_FINAL_MESSAGE", "rfc_str": "c=biws,r=<combined>,p=<proof>"}
```

Responses have `response_type: "SCRAM_RESPONSE"` with `scram_type` `SERVER_FIRST_RESPONSE` (carrying
`r=,s=,i=`) and then `SERVER_FINAL_RESPONSE` (carrying `v=<server signature>` plus `user_info`, which
is `auth.me` output). Failures return `AUTH_ERR`, `EXPIRED`, `DENIED`, `OTP_REQUIRED` or `REDIRECT`.

Details that differ from a textbook RFC 5802 client, all verified against
[truenas_scram](https://github.com/truenas/truenas_scram) (the library middlewared uses):

- A raw API key is `<id>-<secret>` with a 64-character alphanumeric secret. The username sent is
  `<user>:<id>` and the PBKDF2 password is only the secret half.
- Key derivation is PBKDF2-HMAC-SHA512 with the server's 16-byte salt and 500,000 iterations by
  default (the library allows 50k to 5M). ClientKey/ServerKey use the usual "Client Key"/"Server Key" HMACs.
- The client nonce must be exactly 32 random bytes, base64 encoded. Other sizes are rejected with
  "unexpected nonce size".
- The combined nonce is `base64(clientNonceBytes || serverNonceBytes)`, so with a padded client nonce
  it is not a text prefix match. The app compares decoded bytes.
- No channel binding (`c=biws`), because a reverse proxy in front of TrueNAS would change the TLS
  endpoint the binding is computed over.

`app/src/test/.../ScramTest.kt` contains a transcript produced by truenas_scram itself and checks
the app reproduces it byte for byte.

## Subscriptions and live data

`core.subscribe` takes an event name, optionally with JSON parameters appended after a colon
(`reporting.realtime:{"interval": 2}`), and returns a subscription id for `core.unsubscribe`. The
server then sends `collection_update` notifications with `msg` (`added`, `changed`, `removed`),
`collection`, `id` and `fields`. For dynamic sources the `collection` can arrive bare or with the
parameters, so the app matches both. A refused subscription (usually a missing role) arrives as a
`notify_unsubscribed` notification carrying `collection` and an `error` with `reason`.

The app subscribes for as long as a screen is visible and unsubscribes when it leaves. Query
collections (`pool.query`, `app.query`, `alert.list`, `service.query`, `vm.query`) are loaded once
and then patched from their events; a `changed` event can carry only some fields, so it is merged
into the existing item. If the subscription is refused the screen falls back to polling.

| Event | Used for | Role |
|---|---|---|
| `reporting.realtime` | Dashboard CPU (`cpu.cpu.usage`, `cpu.cpu.temp`, `cpuN.usage`), memory, per-interface `received_bytes_rate`/`sent_bytes_rate` | REPORTING_READ |
| `pool.query`, `pool.scan` | Pool changes, scrub/resilver progress | POOL_READ, POOL_SCRUB_READ |
| `app.query`, `app.stats` | App state; per-app `cpu_usage`, `memory`, `networks[]` (fields is an array) | APPS_READ |
| `app.container_log_follow:{"app_name", "container_id", "tail_lines"}` | Live container logs (`data`, `timestamp`) | APPS_READ |
| `alert.list`, `service.query`, `vm.query` | Live lists | ALERT_LIST_READ, SERVICE_READ, VM_READ |
| `container.metrics` | LXC CPU/memory (untyped in the schema, parsed leniently) | CONTAINER_READ |
| `core.get_jobs` | Job progress for actions started from the app | none |

## Jobs

Methods documented as "This method is a job" return an integer job id instead of their result.
The app follows the job through `core.get_jobs` events (`state` WAITING/RUNNING/SUCCESS/FAILED/
ABORTED, `progress.percent`, `progress.description`, `error`, `result`) and also queries
`core.get_jobs [["id", "=", id]]` right after starting it, and every few seconds while waiting, in
case the job finished before the subscription caught it. Without FULL_ADMIN, `core.get_jobs` only
returns the session's own jobs, which is all the app needs.

## Roles

Login returns `user_info` (the same shape as `auth.me`) whose `privilege.roles` lists the granted
roles, either as an array or wrapped as `{"$set": [...]}`. Roles include other roles. Right after
login the app calls `privilege.roles` (any session may) with `select: ["name", "includes"]` and
expands the granted roles with the server's own table (`data/Roles.kt`), so for example
`POOL_WRITE` implies `POOL_SCRUB_WRITE`, and `READONLY_ADMIN` implies every `*_READ` role. If that
call fails, only the granted roles count, which hides actions rather than offering ones that fail.
`SNAPSHOT_DELETE` is separate from `SNAPSHOT_WRITE`. Reboot and shutdown need `FULL_ADMIN`.

## Reads

| Method | Params | Role | Notes |
|---|---|---|---|
| `system.info` | none | READONLY_ADMIN | hostname, version, physmem, model, cores, loadavg, uptime_seconds |
| `pool.query` | none | POOL_READ | status, healthy, size/allocated/free, topology, scan |
| `disk.query` | `[[], {"extra": {"pools": true}}]` | DISK_READ | |
| `disk.temperatures` | `[["sda", ...]]` | DISK_READ | name to Celsius; cached server-side 5 minutes |
| `pool.dataset.query` | `[[], {"extra": {"flat": true, "retrieve_children": false, "retrieve_user_props": false, "properties": [...]}}]` | DATASET_READ | properties come as `{parsed, rawvalue, value, source}` |
| `zfs.resource.snapshot.query` | `[{"paths": [ds], "properties": ["used", "referenced", "creation"], "get_holds": true}]` | SNAPSHOT_READ | 26.0 API; properties come as `{raw, value, source}`, creation in epoch seconds |
| `pool.snapshottask.query`, `pool.scrub.query` | none | SNAPSHOT_TASK_READ, POOL_SCRUB_READ | task `state` is `{state, datetime, error}` |
| `replication.query` | select only | REPLICATION_TASK_READ | full rows include SSH credentials |
| `cloudsync.query` | select only, incl. `credentials.name` | CLOUD_SYNC_READ | full rows include cloud secrets |
| `app.query` | `[[], {"extra": {"host_ip": host}}]` | APPS_READ | `host_ip` replaces 0.0.0.0 in portal URLs |
| `app.container_ids`, `app.upgrade_summary`, `app.rollback_versions` | app name | APPS_READ | |
| `alert.list`, `service.query` | none | ALERT_LIST_READ, SERVICE_READ | |
| `update.status` | none | SYSTEM_UPDATE_READ | current train/profile, new version, release notes, download progress |
| `boot.environment.query`, `interface.query` | none | BOOT_ENV_READ, NETWORK_INTERFACE_READ | |
| `audit.query` | `[{"services": ["MIDDLEWARE"], "query-filters": [...], "query-options": {"limit": N, "order_by": ["-message_timestamp"]}}]` | AUDIT_READ | |
| `reporting.netdata_graphs`, `reporting.netdata_get_data` | `[[{name, identifier}], {"unit": "HOUR", "aggregate": true}]` | REPORTING_READ | rows are `[time, v1, v2...]` matching `legend` |
| `sharing.smb.query`, `sharing.nfs.query`, `user.query`, `vm.query`, `container.query` | select only | matching *_READ | user rows include password hashes; VM rows include display passwords |

## Writes

Every write the app can make is defined in `data/Actions.kt` with its role and confirmation tier.

| Method | Job | Role | Tier |
|---|---|---|---|
| `alert.dismiss`, `alert.restore` | no | ALERT_LIST_WRITE | benign |
| `app.start`, `app.stop`, `app.redeploy` | yes | APPS_WRITE | confirm |
| `app.upgrade [name, {"app_version", "snapshot_hostpaths"}]`, `app.rollback [name, {"app_version", "rollback_snapshot": true}]` | yes | APPS_WRITE | confirm + device auth |
| `service.control [verb, service, {"silent": false}]`, `service.update [service, {"enable"}]` | control is | SERVICE_WRITE or the share's write role | confirm |
| `zfs.resource.snapshot.create [{"dataset", "name", "recursive"}]` | no | SNAPSHOT_WRITE | benign |
| `zfs.resource.snapshot.destroy [{"path"}]` | no | SNAPSHOT_DELETE | confirm + device auth |
| `zfs.resource.snapshot.rollback [{"path"}]` | no | SNAPSHOT_WRITE | type dataset + device auth |
| `pool.snapshottask.run [id]` | no | SNAPSHOT_TASK_WRITE | confirm |
| `replication.run [id]`, `cloudsync.sync [id, {"dry_run": false}]` | yes | REPLICATION_TASK_WRITE, CLOUD_SYNC_WRITE | confirm |
| `pool.scrub.scrub [pool, "START"/"STOP"]` | yes | POOL_WRITE | confirm |
| `sharing.smb.update`, `sharing.nfs.update [id, {...changed fields}]` | no | SHARING_SMB_WRITE, SHARING_NFS_WRITE | confirm |
| `sharing.smb.create`, `sharing.nfs.create [{...}]` | no | SHARING_SMB_WRITE, SHARING_NFS_WRITE | confirm |
| `sharing.smb.delete`, `sharing.nfs.delete [id]` | no | SHARING_SMB_WRITE, SHARING_NFS_WRITE | confirm + device auth |
| `app.update [name, {"values"}]` or `[name, {"custom_compose_config"}]` | yes | APPS_WRITE | confirm + device auth |
| `update.run [{"version", "reboot": false}]` | yes | SYSTEM_UPDATE_WRITE | confirm + device auth |
| `system.reboot [reason]`, `system.shutdown [reason]` | yes | FULL_ADMIN | type hostname + device auth |
| `vm.start [id]`, `vm.stop [id, {...}]`, `vm.poweroff [id]` | stop is | VM_WRITE | confirm; power off also device auth |
| `container.start [id]`, `container.stop [id, {...}]` | stop is | CONTAINER_WRITE | confirm |

`system.reboot` has no page in the 26.0 docs (the URL shows the `system.reboot.info` namespace);
it's called as a job with a single reason string.

App settings come from `app.query` filtered by name with `extra: {"retrieve_config": true,
"include_app_schema": true}`: `config` holds the values (secrets included) and
`version_details.schema` the questions form. If the schema is missing the app falls back to
`catalog.get_app_details [name, {"train"}]` and reads `versions[<version>].schema`. Updates send only
the top-level question variables, never the server's `ix_*` context. Custom apps send their compose
file as `custom_compose_config`. Share paths are browsed with `filesystem.listdir` filtered to
directories.

## Not yet verified against a real server

These shapes are untyped in the schema and parsed leniently, so check them on a device: the
realtime `cpu` object (aggregate vs per-core keys), `interfaces`, `container.metrics`, the holds
field on snapshots, the `state` object on snapshot and replication tasks, and where `app.query`
puts the settings schema (`version_details.schema` is assumed, with the catalog as fallback).
