"""
Writes the in-app demo server's data (app/src/main/assets/demo/server.json) from the mock's canned data.

Times are generated around a fixed MOCK_NOW so the app can shift them to the current time on load.

  python export_demo.py   (needs the same websockets install as the mock, since it imports it)
"""
import json, os, sys

GEN_NOW = 2_000_000_000
os.environ["MOCK_NOW"] = str(GEN_NOW)
os.environ["MOCK_HOSTNAME"] = "keel-demo"
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mock_truenas as m  # noqa: E402

# Methods whose answer depends on params are served from the raw tables below instead.
DYNAMIC = {"app.query", "zfs.resource.snapshot.query", "reporting.netdata_get_data", "filesystem.listdir",
           "sharing.smb.query", "sharing.nfs.query", "alert.list", "service.query", "core.get_jobs"}
static = {k: v() for k, v in m.responses([[]]).items() if k not in DYNAMIC}

out = dict(
    generatedAt=GEN_NOW,
    static=static,
    apps=list(m.APPS.values()),
    appConfig=m.APP_CONFIG,
    appSchema={"jellyfin": m.JELLYFIN_SCHEMA},
    alerts=m.ALERTS,
    services=m.SERVICES,
    smbShares=m.SMB_SHARES,
    nfsShares=m.NFS_SHARES,
    dirs=m.DIRS,
    snapshots={d["name"]: m.snaps(d["name"]) for d in m.DATASETS},
)
dest = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "demo", "server.json")
os.makedirs(os.path.dirname(dest), exist_ok=True)
with open(dest, "w") as f:
    json.dump(out, f, separators=(",", ":"), sort_keys=True)
print("wrote", os.path.relpath(dest), os.path.getsize(dest), "bytes")
