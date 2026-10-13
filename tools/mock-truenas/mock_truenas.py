"""
Fake TrueNAS 26.0 JSON-RPC server for testing the app's UI in an emulator without a real NAS.

It speaks the real WebSocket JSON-RPC and SCRAM-SHA-512 login, serves canned data for every screen,
pushes realtime/app stats/log events, and fakes jobs. The API key below is a made-up test value.

  pip install websockets
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
      -keyout key.pem -out cert.pem -days 30 -subj "/CN=truenas.local"
  python mock_truenas.py

In the emulator connect to 10.0.2.2 port 8443 (Secure mode), user truenas_admin, key 7-<SECRET>.
Key 8 has only ALERT_LIST_READ (for alert notifications), key 9 only READONLY_ADMIN.
Set ROLES=... to change key 7's roles.
"""
import asyncio, base64, hashlib, hmac, json, os, random, ssl, sys, time
import websockets

SECRET = "aB3dE5gH7jK9mN1pQ3sT5vW7yZ9bC1eF3hJ5kL7nP9rS1uV3xY5zA7cE9gI1kM3o"
KEY_ID = 7
USER = "truenas_admin"
ROLES = os.environ.get("ROLES", "READONLY_ADMIN,APPS_WRITE,ALERT_LIST_WRITE,SERVICE_WRITE,SNAPSHOT_WRITE,SNAPSHOT_DELETE,POOL_WRITE,SNAPSHOT_TASK_WRITE,REPLICATION_TASK_WRITE,SYSTEM_UPDATE_WRITE,VM_WRITE,SHARING_SMB_WRITE,SHARING_NFS_WRITE").split(",")
# A simplified role table for privilege.roles: each *_WRITE includes its *_READ, and READONLY_ADMIN every *_READ.
AREAS = ["ACCOUNT", "ALERT_LIST", "ALERT", "APPS", "CATALOG", "BOOT_ENV", "CLOUD_SYNC", "CONTAINER", "DATASET", "DISK",
         "NETWORK_INTERFACE", "POOL", "POOL_SCRUB", "REPLICATION_TASK", "REPORTING", "SERVICE", "SHARING_SMB", "SHARING_NFS",
         "SNAPSHOT", "SNAPSHOT_TASK", "SYSTEM_AUDIT", "SYSTEM_GENERAL", "SYSTEM_UPDATE", "VM"]
ROLE_TABLE = [dict(name=f"{a}_WRITE", includes=[f"{a}_READ"]) for a in AREAS] + \
    [dict(name=f"{a}_READ", includes=[]) for a in AREAS] + \
    [dict(name="READONLY_ADMIN", includes=[f"{a}_READ" for a in AREAS]), dict(name="FULL_ADMIN", includes=[])]
SALT = b"0123456789abcdef"
ITER = 500000
SALTED = hashlib.pbkdf2_hmac("sha512", SECRET.encode(), SALT, ITER)

def H(k, m): return hmac.new(k, m.encode() if isinstance(m, str) else m, hashlib.sha512).digest()

CLIENT_KEY = H(SALTED, "Client Key"); STORED = hashlib.sha512(CLIENT_KEY).digest(); SERVER_KEY = H(SALTED, "Server Key")
GiB = 1024**3; TiB = 1024**4
now = time.time()

def iso(t): return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))

APPS = {
  n: dict(name=n, id=n, state=s, upgrade_available=u, latest_version="1.3.0" if u else "1.2.0", image_updates_available=False,
          custom_app=False, migrated=False, human_version=hv, version="1.2.0",
          metadata=dict(title=t, train="community", description=d), notes=None,
          portals={"Web UI": f"http://0.0.0.0:{p}/"} if p else {},
          active_workloads=dict(containers=1, used_ports=[dict(container_port=p or 80, protocol="tcp", host_ports=[dict(host_port=p or 80, host_ip="0.0.0.0")])],
              used_host_ips=[], container_details=[dict(id=f"{n}-c0ffee", service_name=n, image=f"{n}/{n}:latest", port_config=[], state="running" if s=="RUNNING" else "exited", volume_mounts=[])],
              volumes=[dict(source=f"/mnt/tank/apps/{n}", destination="/config", mode="rw", type="bind")], images=[f"{n}/{n}:latest"], networks=[]))
  for n, s, u, hv, t, d, p in [
    ("jellyfin", "RUNNING", True, "10.10.7_1.2.0", "Jellyfin", "Free software media system", 30013),
    ("immich", "RUNNING", False, "v1.135.0_1.2.0", "Immich", "Self-hosted photo and video backup", 30041),
    ("nextcloud", "RUNNING", False, "31.0.6_1.2.0", "Nextcloud", "File hosting and collaboration", 30027),
    ("minecraft", "RUNNING", False, "2025.6.0_1.2.0", "Minecraft", "Minecraft server", 42069),
    ("qbittorrent", "STOPPED", False, "5.1.0_1.2.0", "qBittorrent", "BitTorrent client", 30024),
    ("pihole", "CRASHED", False, "2025.06.2_1.2.0", "Pi-hole", "Network-wide ad blocking", 20720),
  ]
}

APPS["qbittorrent"]["custom_app"] = True
JELLYFIN_SCHEMA = dict(groups=[dict(name="Jellyfin Configuration", description=""), dict(name="Network Configuration", description=""), dict(name="Storage Configuration", description="")],
  questions=[
    dict(variable="TZ", label="Timezone", group="Jellyfin Configuration", schema=dict(type="string", required=True, enum=[dict(value=z, description=z) for z in ["Etc/UTC", "America/Los_Angeles", "Europe/London"]])),
    dict(variable="jellyfin", label="", group="Jellyfin Configuration", schema=dict(type="dict", attrs=[
      dict(variable="publish_server_url", label="Published Server URL", description="Optional</br>The URL Jellyfin advertises.", schema=dict(type="string", default="")),
      dict(variable="api_token", label="API Token", schema=dict(type="string", private=True, default="")),
      dict(variable="additional_envs", label="Additional Environment Variables", schema=dict(type="list", default=[], items=[
        dict(variable="env", label="Environment Variable", schema=dict(type="dict", attrs=[
          dict(variable="name", label="Name", schema=dict(type="string", required=True)),
          dict(variable="value", label="Value", schema=dict(type="string"))]))]))])),
    dict(variable="network", label="", group="Network Configuration", schema=dict(type="dict", attrs=[
      dict(variable="host_network", label="Host Network", description="Bind to the host network.", schema=dict(type="boolean", default=False)),
      dict(variable="web_port", label="WebUI Port", schema=dict(type="dict", show_if=[["host_network", "=", False]], attrs=[
        dict(variable="bind_mode", label="Port Bind Mode", schema=dict(type="string", default="published", enum=[dict(value="published", description="Publish on the host"), dict(value="exposed", description="Expose to other containers"), dict(value="", description="None")])),
        dict(variable="port_number", label="Port Number", schema=dict(type="int", min=1, max=65535, required=True, show_if=[["bind_mode", "=", "published"]]))]))])),
    dict(variable="storage", label="", group="Storage Configuration", schema=dict(type="dict", attrs=[
      dict(variable="config", label="Config Storage", schema=dict(type="dict", attrs=[
        dict(variable="type", label="Type", schema=dict(type="string", immutable=True, default="host_path", enum=[dict(value="host_path", description="Host Path"), dict(value="ix_volume", description="ixVolume")])),
        dict(variable="host_path", label="Host Path", schema=dict(type="hostpath", show_if=[["type", "=", "host_path"]], required=True))]))])),
  ])
APP_CONFIG = {
  "jellyfin": dict(TZ="America/Los_Angeles", jellyfin=dict(publish_server_url="", api_token="not-a-real-token", additional_envs=[dict(name="JELLYFIN_CACHE_DIR", value="/cache")]),
                   network=dict(host_network=False, web_port=dict(bind_mode="published", port_number=30013)), storage=dict(config=dict(type="host_path", host_path="/mnt/tank/apps/jellyfin")),
                   ix_context=dict(app_name="jellyfin")),
  "qbittorrent": {"services": {"qbittorrent": {"image": "lscr.io/linuxserver/qbittorrent:latest", "ports": ["8080:8080"], "environment": {"PUID": "568", "PGID": "568"}, "volumes": ["/mnt/tank/downloads:/downloads"]}}},
}
SMB_SHARES = [dict(id=1, name="media", path="/mnt/tank/media", purpose="DEFAULT_SHARE", enabled=True, comment="Movies and TV", readonly=True, browsable=True, access_based_share_enumeration=False, locked=False, options=None),
              dict(id=2, name="backups", path="/mnt/tank/backups", purpose="TIMEMACHINE_SHARE", enabled=False, comment="", readonly=False, browsable=True, access_based_share_enumeration=False, locked=False, options=None)]
NFS_SHARES = [dict(id=1, path="/mnt/tank/media", comment="", enabled=True, ro=True, networks=["192.168.0.0/24"], hosts=[], maproot_user=None, maproot_group=None, mapall_user=None, mapall_group=None, locked=False)]
DIRS = {"/mnt": ["tank", "fast"], "/mnt/tank": ["media", "backups", "apps", "downloads"], "/mnt/fast": ["vms"], "/mnt/tank/media": ["movies", "tv"]}

def app_query(params):
    out = [dict(a) for a in APPS.values() if not (params and params[0] and params[0][0][2] != a["name"])]
    extra = (params[1] if len(params) > 1 else {}).get("extra", {})
    for a in out:
        if extra.get("retrieve_config"): a["config"] = APP_CONFIG.get(a["name"], {})
        if extra.get("include_app_schema") and not a["custom_app"]: a["version_details"] = dict(schema=JELLYFIN_SCHEMA if a["name"] == "jellyfin" else None)
    return out

def by_id(rows, params): return [r for r in rows if not (params and params[0] and params[0][0][0] == "id" and params[0][0][2] != r["id"])]

def share_write(rows, method, params):
    if method.endswith(".create"):
        row = dict(params[0], id=max([r["id"] for r in rows] + [0]) + 1, locked=False); rows.append(row); return row
    row = next(r for r in rows if r["id"] == params[0])
    if method.endswith(".delete"): rows.remove(row); return True
    row.update(params[1]); return row

POOLS = [
  dict(id=1, name="tank", status="ONLINE", healthy=True, warning=False, status_detail=None, size=32*TiB, allocated=int(19.4*TiB), free=int(12.6*TiB), fragmentation="4",
       scan=dict(function="SCRUB", state="FINISHED", percentage=100.0, errors=0, start_time=iso(now-86400*6), end_time=iso(now-86400*6+5*3600), total_secs_left=None),
       topology=dict(data=[dict(name="raidz2-0", type="RAIDZ2", status="ONLINE", stats=dict(read_errors=0, write_errors=0, checksum_errors=0),
          children=[dict(name=f"sd{c}1", type="DISK", disk=f"sd{c}", status="ONLINE", children=[]) for c in "abcdef"])], log=[], cache=[], spare=[], special=[], dedup=[])),
  dict(id=2, name="fast", status="DEGRADED", healthy=False, warning=True, status_detail="One or more devices has experienced an unrecoverable error.", size=2*TiB, allocated=int(1.75*TiB), free=int(0.25*TiB), fragmentation="12",
       scan=dict(function="SCRUB", state="SCANNING", percentage=42.5, errors=0, start_time=iso(now-3600), end_time=None, total_secs_left=5400),
       topology=dict(data=[dict(name="mirror-0", type="MIRROR", status="DEGRADED", stats=dict(read_errors=0, write_errors=0, checksum_errors=3),
          children=[dict(name="nvme0n1p1", type="DISK", disk="nvme0n1", status="ONLINE", children=[]), dict(name="nvme1n1p1", type="DISK", disk="nvme1n1", status="FAULTED", children=[])])], log=[], cache=[], spare=[], special=[], dedup=[])),
]
DISKS = [dict(identifier=f"{{serial}}S{c}", name=f"sd{c}", serial=f"WD-WX{ord(c)}2K", size=8*TiB, model="WDC WD80EFPX", type="HDD", rotationrate=5400, pool="tank") for c in "abcdef"] + \
        [dict(identifier=f"{{serial}}N{i}", name=f"nvme{i}n1", serial=f"S6B{i}NX0T", size=2*TiB, model="Samsung 990 PRO", type="SSD", rotationrate=None, pool="fast") for i in range(2)]
ALERTS = [
  dict(uuid="a1", klass="ZpoolDegraded", level="CRITICAL", text="Pool fast state is DEGRADED", formatted="Pool fast state is DEGRADED: <b>One or more devices has experienced an unrecoverable error.</b>", datetime=iso(now-7200), dismissed=False, source=""),
  dict(uuid="a2", klass="AppUpdate", level="INFO", text="An update is available for jellyfin", formatted=None, datetime=iso(now-86400), dismissed=False, source=""),
  dict(uuid="a3", klass="SMART", level="WARNING", text="Device /dev/sdc: 8 Currently unreadable (pending) sectors", formatted=None, datetime=iso(now-86400*3), dismissed=True, source=""),
]
SERVICES = [dict(id=i, service=s, enable=e, state=st, pids=[]) for i, (s, e, st) in enumerate([("cifs", True, "RUNNING"), ("nfs", True, "RUNNING"), ("ssh", True, "RUNNING"), ("ups", False, "STOPPED"), ("smartd", True, "STOPPED"), ("ftp", False, "STOPPED")], 1)]

def prop(v, txt=None): return dict(parsed=v, rawvalue=str(v), value=txt or str(v), source="NONE")
DATASETS = []
for name, used, avail in [("tank", 19*TiB, 12*TiB), ("tank/media", 14*TiB, 12*TiB), ("tank/media/movies", 9*TiB, 12*TiB), ("tank/media/tv", 5*TiB, 12*TiB), ("tank/apps", 120*GiB, 12*TiB), ("tank/apps/immich", 800*GiB, 12*TiB), ("tank/backups", 4*TiB, 12*TiB), ("fast", 1*TiB, 200*GiB), ("fast/vms", 600*GiB, 200*GiB)]:
    DATASETS.append(dict(id=name, name=name, type="FILESYSTEM", pool=name.split("/")[0], encrypted=name=="tank/backups", locked=False, key_loaded=True,
        used=prop(used), available=prop(avail), usedbysnapshots=prop(used//20), compression=prop("LZ4", "LZ4"), compressratio=prop("1.32", "1.32x"),
        readonly=prop(False, "OFF"), deduplication=prop("OFF", "OFF"), recordsize=prop(131072, "128K"), mountpoint=f"/mnt/{name}"))

def snaps(ds):
    out = []
    for i in range(8):
        t = int(now - 86400 * i)
        out.append(dict(createtxg=1000-i, guid=i, name=f"{ds}@auto-{time.strftime('%Y-%m-%d_00-00', time.gmtime(t))}", pool=ds.split("/")[0], dataset=ds,
            snapshot_name=f"auto-{time.strftime('%Y-%m-%d_00-00', time.gmtime(t))}", type="SNAPSHOT", holds={},
            properties=dict(used=dict(raw=str(1024**2*(i+1)*37), value=1024**2*(i+1)*37, source=None), referenced=dict(raw="1", value=5*TiB, source=None), creation=dict(raw=str(t), value=t, source=None))))
    return out

SYSINFO = dict(version="26.0.0-BETA.1", hostname="arceusvault", physmem=64*GiB, model="AMD Ryzen 7 5700G with Radeon Graphics", cores=16, physical_cores=8,
               loadavg=[0.82, 0.71, 0.66], uptime_seconds=86400*12+3600*5, ecc_memory=False, system_product="B550M AORUS ELITE", system_manufacturer="Gigabyte Technology Co., Ltd.")

job_id = [100]
alert_logins = [0]
KEY_ROLES = {"8": ["ALERT_LIST_READ"], "9": ["READONLY_ADMIN"]}

async def main_handler(ws):
    subs = {}
    authed = False
    first_bare = server_first = None
    async def send(obj): await ws.send(json.dumps(obj))
    async def event(coll, msg, id_, fields): await send(dict(jsonrpc="2.0", method="collection_update", params=dict(msg=msg, collection=coll, id=id_, fields=fields)))

    async def run_job(method, title, then=None, secs=3):
        job_id[0] += 1; jid = job_id[0]
        async def go():
            for pct in (10, 45, 80):
                await asyncio.sleep(secs / 3)
                if "core.get_jobs" in subs.values(): await event("core.get_jobs", "changed", jid, dict(id=jid, method=method, state="RUNNING", progress=dict(percent=pct, description=title)))
            if then: await then()
            if "core.get_jobs" in subs.values(): await event("core.get_jobs", "changed", jid, dict(id=jid, method=method, state="SUCCESS", progress=dict(percent=100, description="done"), result=None))
        asyncio.create_task(go())
        return jid

    async def realtime_loop(name):
        while name in subs:
            cores = [random.uniform(2, 40) for _ in range(16)]
            await event("reporting.realtime", "added", None, dict(
                cpu=dict(cpu=dict(usage=sum(cores)/16, temp=47+random.random()*4), **{f"cpu{i}": dict(usage=c) for i, c in enumerate(cores)}),
                memory=dict(physical_memory_total=64*GiB, physical_memory_available=int(18*GiB), arc_size=int(31*GiB), arc_free_memory=0, arc_available_memory=0),
                interfaces=dict(enp6s0=dict(received_bytes_rate=random.uniform(1e5, 9e6), sent_bytes_rate=random.uniform(1e5, 3e6), link_state="LINK_STATE_UP"),
                                veth1234=dict(received_bytes_rate=1, sent_bytes_rate=1, link_state="LINK_STATE_UP")),
                disks={}, zfs={}, pools={}))
            await asyncio.sleep(2)

    async def stats_loop(name):
        while name in subs:
            await event("app.stats", "added", None, [dict(app_name=a, cpu_usage=random.randint(0, 25), memory=random.randint(100, 4000)*1024**2,
                networks=[dict(interface_name="eth0", rx_bytes=random.randint(0, 2_000_000), tx_bytes=random.randint(0, 500_000))], blkio=dict(read=0, write=0))
                for a, v in APPS.items() if v["state"] == "RUNNING"])
            await asyncio.sleep(3)

    async def logs_loop(name):
        lines = ["[INF] Starting Jellyfin 10.10.7", "[INF] Loading plugins", "[WRN] Transcoding hardware acceleration is disabled", "[INF] Startup complete 0:00:04.21",
                 "[ERR] Error processing request: Connection reset by peer", "[INF] Scheduled task Scan Media Library completed"]
        for i in range(60):
            await event("app.container_log_follow", "added", None, dict(data=f"2026-10-11 02:{i//60:02d}:{i%60:02d} {lines[i % len(lines)]} #{i}", timestamp=None))
        while name in subs:
            await asyncio.sleep(1.5)
            await event("app.container_log_follow", "added", None, dict(data=f"{time.strftime('%H:%M:%S')} [INF] heartbeat ok", timestamp=None))

    async for raw in ws:
        msg = json.loads(raw)
        mid, method, params = msg.get("id"), msg.get("method"), msg.get("params", [])
        def ok(result): return send(dict(jsonrpc="2.0", id=mid, result=result))
        def err(reason, errname="EFAULT"): return send(dict(jsonrpc="2.0", id=mid, error=dict(code=-32001, message="Method call error", data=dict(errname=errname, reason=reason))))
        try:
            if method == "auth.login_ex":
                p = params[0]
                if p["scram_type"] == "CLIENT_FIRST_MESSAGE":
                    first_bare = p["rfc_str"][3:]
                    login_key = dict(kv.split("=", 1) for kv in first_bare.split(","))["n"].split(":")[-1]
                    cn = dict(kv.split("=", 1) for kv in first_bare.split(","))["r"]
                    combined = base64.b64encode(base64.b64decode(cn) + os.urandom(32)).decode()
                    server_first = f"r={combined},s={base64.b64encode(SALT).decode()},i={ITER}"
                    await ok(dict(response_type="SCRAM_RESPONSE", scram_type="SERVER_FIRST_RESPONSE", rfc_str=server_first))
                else:
                    final = p["rfc_str"]; without = final[:final.index(",p=")]
                    proof = base64.b64decode(final.split(",p=")[1])
                    auth_msg = f"{first_bare},{server_first},{without}"
                    sig = H(STORED, auth_msg)
                    ck = bytes(a ^ b for a, b in zip(proof, sig))
                    if hashlib.sha512(ck).digest() != STORED:
                        await ok(dict(response_type="AUTH_ERR")); continue
                    authed = True
                    roles = KEY_ROLES.get(login_key, ROLES)
                    if login_key == "8":
                        alert_logins[0] += 1
                        # From the second alert-key login on, a new critical alert "appears".
                        if alert_logins[0] >= 2 and not any(a["uuid"] == "a4" for a in ALERTS):
                            ALERTS.append(dict(uuid="a4", klass="ZpoolStatus", level="CRITICAL", text="Pool tank state is FAULTED", formatted=None, datetime=iso(time.time()), dismissed=False, source=""))
                    await ok(dict(response_type="SCRAM_RESPONSE", scram_type="SERVER_FINAL_RESPONSE", rfc_str="v=" + base64.b64encode(H(SERVER_KEY, auth_msg)).decode(),
                                  user_info=dict(pw_name=USER, privilege=dict(roles={"$set": roles}))))
                continue
            if not authed: await err("Not authenticated", "ENOTAUTHENTICATED"); continue
            if method == "core.subscribe":
                sid = f"s{len(subs)+1}{random.randint(0,9999)}"; subs[sid] = params[0]; await ok(sid)
                base = params[0].split(":")[0]
                if base == "reporting.realtime": asyncio.create_task(realtime_loop(sid))
                if base == "app.stats": asyncio.create_task(stats_loop(sid))
                if base == "app.container_log_follow": asyncio.create_task(logs_loop(sid))
                continue
            if method == "core.unsubscribe": subs.pop(params[0], None); await ok(None); continue
            r = {
              "system.info": lambda: SYSINFO, "auth.me": lambda: {}, "privilege.roles": lambda: ROLE_TABLE, "pool.query": lambda: POOLS, "disk.query": lambda: DISKS,
              "disk.temperatures": lambda: {d["name"]: 34 + i for i, d in enumerate(DISKS)},
              "alert.list": lambda: ALERTS, "service.query": lambda: SERVICES,
              "app.query": lambda: app_query(params),
              "filesystem.listdir": lambda: [dict(name=n, path=f"{params[0].rstrip('/')}/{n}", type="DIRECTORY") for n in DIRS.get(params[0].rstrip('/'), [])],
              "app.upgrade_summary": lambda: dict(latest_version="1.3.0", latest_human_version="10.11.0_1.3.0", upgrade_version="1.3.0", upgrade_human_version="10.11.0_1.3.0",
                  available_versions_for_upgrade=[dict(version="1.3.0", human_version="10.11.0_1.3.0", changelog="<p>Jellyfin 10.11.0 with library performance improvements.</p>")]),
              "app.rollback_versions": lambda: ["1.1.9", "1.1.8"],
              "pool.dataset.query": lambda: DATASETS, "zfs.resource.snapshot.query": lambda: snaps(params[0]["paths"][0] if params[0]["paths"] else "tank"),
              "pool.snapshottask.query": lambda: [dict(id=1, dataset="tank/media", recursive=True, lifetime_value=2, lifetime_unit="WEEK", enabled=True, naming_schema="auto-%Y-%m-%d_%H-%M",
                    schedule=dict(minute="0", hour="0", dom="*", month="*", dow="*"), state=dict(state="FINISHED", datetime={"$date": int((now-3600*2)*1000)}))],
              "replication.query": lambda: [dict(id=1, name="tank/media to backup box", direction="PUSH", transport="SSH", source_datasets=["tank/media"], target_dataset="backup/media", enabled=True, auto=True,
                    state=dict(state="ERROR", datetime={"$date": int((now-86400)*1000)}, error="ssh: connect to host 10.0.0.9 port 22: No route to host"))],
              "cloudsync.query": lambda: [dict(id=1, description="Photos to B2", path="/mnt/tank/apps/immich", direction="PUSH", transfer_mode="SYNC", enabled=True,
                    schedule=dict(minute="0", hour="3", dom="*", month="*", dow="*"), credentials=dict(name="Backblaze B2"), job=dict(state="SUCCESS", time_finished=iso(now-80000)))],
              "pool.scrub.query": lambda: [dict(id=1, pool=1, pool_name="tank", threshold=35, description="", enabled=True, schedule=dict(minute="00", hour="00", dom="*", month="*", dow="7"))],
              "update.status": lambda: dict(code="NORMAL", status=dict(current_version=dict(train="TrueNAS-SCALE-Goldeye", profile="GENERAL", matches_profile=True),
                    new_version=dict(version="26.0.0-BETA.2", manifest={}, release_notes="Bug fixes and improvements to the apps subsystem.", release_notes_url="https://www.truenas.com/docs/")), error=None, update_download_progress=None),
              "boot.environment.query": lambda: [dict(id="26.0.0-BETA.1", dataset="boot-pool/ROOT/26.0.0-BETA.1", active=True, activated=True, created=iso(now-86400*12), used_bytes=3*GiB, keep=False, can_activate=True),
                    dict(id="25.10.2", dataset="boot-pool/ROOT/25.10.2", active=False, activated=False, created=iso(now-86400*60), used_bytes=int(2.8*GiB), keep=True, can_activate=True)],
              "interface.query": lambda: [dict(id="enp6s0", name="enp6s0", type="PHYSICAL", description="", mtu=1500, ipv4_dhcp=False, aliases=[dict(type="INET", address="192.168.0.78", netmask=24)],
                    state=dict(link_state="LINK_STATE_UP", link_address="d8:5e:d3:00:11:22", active_media_subtype="2500Base-T", aliases=[dict(type="INET", address="192.168.0.78", netmask=24)]))],
              "audit.query": lambda: [dict(audit_id=f"x{i}", message_timestamp=int(now-600*i), address="100.101.102.103", username=u, service="MIDDLEWARE", event="METHOD_CALL",
                    event_data=dict(method=m, description=dsc), success=s) for i, (u, m, dsc, s) in enumerate([(USER, "app.stop", "Stop app qbittorrent", True), ("root", "pool.scrub.scrub", None, True), (USER, "system.reboot", None, False), ("admin", "auth.login_ex", "Authentication", True)])],
              "reporting.netdata_graphs": lambda: [dict(name="cpu", title="CPU Usage", vertical_label="%CPU", identifiers=None), dict(name="memory", title="Physical memory utilization", vertical_label="Bytes", identifiers=None),
                    dict(name="interface", title="Interface Traffic ({identifier})", vertical_label="Kilobits/s", identifiers=["enp6s0"]), dict(name="disktemp", title="Disks Temperature ({identifier})", vertical_label="Celsius", identifiers=["sda", "sdb"])],
              "reporting.netdata_get_data": lambda: [dict(name=g["name"], identifier=g.get("identifier"), legend=["time", "user", "system"] if g["name"] == "cpu" else ["time", "received", "sent"] if g["name"] == "interface" else ["time", "value"],
                    data=[[int(now-60*k)] + [abs(30*__import__("math").sin(k/9))+random.random()*5 + j*4 for j in range(2 if g["name"] in ("cpu", "interface") else 1)] for k in range(120, 0, -1)], aggregations={}, start=1, end=2) for g in params[0]],
              "user.query": lambda: [dict(id=1, uid=0, username="root", full_name="root", builtin=True, locked=False, smb=False, password_disabled=True, twofactor_auth_configured=False, roles=["FULL_ADMIN"], api_keys=[]),
                    dict(id=70, uid=950, username=USER, full_name="TrueNAS Admin", builtin=False, locked=False, smb=False, password_disabled=False, twofactor_auth_configured=True, roles=["FULL_ADMIN"], api_keys=[3]),
                    dict(id=71, uid=3000, username="phone", full_name="Phone (read-only)", builtin=False, locked=False, smb=False, password_disabled=True, twofactor_auth_configured=False, roles=["READONLY_ADMIN"], api_keys=[7])],
              "sharing.smb.query": lambda: by_id(SMB_SHARES, params),
              "sharing.nfs.query": lambda: by_id(NFS_SHARES, params),
              "vm.query": lambda: [dict(id=1, name="HomeAssistant", description="HAOS", vcpus=1, cores=2, threads=1, memory=4096, autostart=True, bootloader="UEFI", status=dict(state="RUNNING", pid=1234, domain_state="RUNNING")),
                    dict(id=2, name="Windows11", description="", vcpus=1, cores=4, threads=2, memory=16384, autostart=False, bootloader="UEFI", status=dict(state="STOPPED", pid=None, domain_state="SHUTOFF"))],
              "container.query": lambda: [dict(id=1, name="debian-dev", description="Debian 13 dev box", autostart=False, dataset="tank/containers/debian-dev", status=dict(state="RUNNING", pid=4321))],
              "core.get_jobs": lambda: [],
            }.get(method)
            if r: await ok(r()); continue
            if method in ("app.stop", "app.start", "app.redeploy", "app.upgrade", "app.rollback"):
                name = params[0]
                async def then(name=name, method=method):
                    APPS[name]["state"] = "STOPPED" if method == "app.stop" else "RUNNING"
                    if method == "app.upgrade": APPS[name]["upgrade_available"] = False; APPS[name]["human_version"] = "10.11.0_1.3.0"
                    if "app.query" in [v.split(":")[0] for v in subs.values()]: await event("app.query", "changed", name, dict(state=APPS[name]["state"], upgrade_available=APPS[name]["upgrade_available"], human_version=APPS[name]["human_version"]))
                await ok(await run_job(method, f"{method} {name}", then)); continue
            if method in ("service.control", "pool.scrub.scrub", "replication.run", "cloudsync.sync", "update.run", "vm.stop", "container.stop", "system.reboot", "system.shutdown"):
                await ok(await run_job(method, method)); continue
            if method == "alert.dismiss" or method == "alert.restore":
                for a in ALERTS:
                    if a["uuid"] == params[0]:
                        a["dismissed"] = method == "alert.dismiss"
                        if "alert.list" in subs.values(): await event("alert.list", "changed", a["uuid"], a)
                await ok(None); continue
            if method in ("service.update", "zfs.resource.snapshot.create", "zfs.resource.snapshot.destroy", "zfs.resource.snapshot.rollback", "pool.snapshottask.run", "vm.start", "vm.poweroff", "container.start"):
                await ok(None); continue
            if method.startswith("sharing.smb.") or method.startswith("sharing.nfs."):
                print("WRITE", method, json.dumps(params), flush=True)
                await ok(share_write(SMB_SHARES if ".smb." in method else NFS_SHARES, method, params)); continue
            if method == "app.update":
                print("WRITE", method, json.dumps(params), flush=True)
                name, upd = params
                async def then(name=name, upd=upd):
                    if upd.get("values"): APP_CONFIG[name] = dict(upd["values"], ix_context=dict(app_name=name))
                    if upd.get("custom_compose_config"): APP_CONFIG[name] = upd["custom_compose_config"]
                await ok(await run_job(method, f"Updating {name}", then)); continue
            await err(f"Mock has no method {method}", "ENOMETHOD")
        except Exception as e:
            print("ERR", method, e, file=sys.stderr)
            await err(str(e))

async def main():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    d = os.path.dirname(__file__)
    ctx.load_cert_chain(os.path.join(d, "cert.pem"), os.path.join(d, "key.pem"))
    async with websockets.serve(main_handler, "0.0.0.0", 8443, ssl=ctx, max_size=None):
        print("mock up on 8443", flush=True)
        await asyncio.Future()

asyncio.run(main())
