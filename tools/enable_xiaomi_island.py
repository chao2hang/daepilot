#!/usr/bin/env python3
"""Grant DaePilot Xiaomi HyperOS focus-notification ("Super Island") rights.

HyperOS only renders the island for apps that xmsf has authorized. The
decision is cached locally in com.xiaomi.xmsf's auth_database.db:

  packageInfos(packageName, clientId)   the app was seen by xmsf
  apkSigns(packageInfoId, value)        SHA-256 of the signing certificate
  scopeInfos(packageInfoId, scope, ...) granted capabilities; scope 20032 is
                                        the focus-notification grant

An app that is not registered on Xiaomi's open platform has no way to obtain
the grant through the official channel, so on a rooted device the cache can be
seeded directly. This script does exactly that and nothing else; it never
touches the app's own files.

Usage:
    python tools/enable_xiaomi_island.py enable [--package io.github.daepilot.debug]
    python tools/enable_xiaomi_island.py status
    python tools/enable_xiaomi_island.py disable

Requires adb on PATH (or --adb), python's stdlib sqlite3, and apksigner to read
the installed APK's certificate (or --digest). A reboot or an xmsf sync can drop
the rows; re-run `enable` if the island stops appearing.
"""

import argparse
import contextlib
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time

FOCUS_SCOPE = 20032
DURATION_MS = 10 * 365 * 24 * 3600 * 1000  # the cache TTL is normally 1 hour
XMSF = "com.xiaomi.xmsf"
DB = "/data/data/com.xiaomi.xmsf/databases/auth_database.db"
NOTIFICATION_ID = 1001  # NotificationHelper.CONTROL_NOTIFICATION_ID


def run(args, **kw):
    proc = subprocess.run(args, capture_output=True, text=True, **kw)
    if proc.returncode != 0:
        raise SystemExit(f"command failed: {' '.join(args)}\n{proc.stdout}{proc.stderr}")
    return proc.stdout


def sh(adb, serial, command):
    base = [adb] + (["-s", serial] if serial else [])
    return run(base + ["shell", "su", "-c", command])


def apk_digest(adb, serial, pkg, apksigner):
    remote = sh(adb, serial, f"pm path {pkg}").strip().splitlines()[0].split(":", 1)[1].strip()
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tmp:
        local = tmp.name
    try:
        base = adb_base(adb, serial)
        run(base + ["pull", remote, local])
        # Windows cannot exec a .bat directly through CreateProcess.
        launcher = [apksigner]
        if apksigner.lower().endswith((".bat", ".cmd")):
            launcher = ["cmd", "/c", apksigner]
        out = run(launcher + ["verify", "--print-certs", local])
        match = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", out, re.I)
        if not match:
            raise SystemExit("could not read the certificate digest from the APK")
        return match.group(1).lower()
    finally:
        os.unlink(local)


def adb_base(adb, serial):
    return [adb] + (["-s", serial] if serial else [])


def cat_remote(adb, serial, remote, local):
    """Copy a root-only file out through `su cat`.

    /data/local/tmp cannot be used as a staging area here: files created there
    by root keep an SELinux label that rejects chmod, so `adb pull` could not
    read them.
    """
    out = subprocess.run(
        adb_base(adb, serial) + ["exec-out", "su", "-c", f"cat {remote}"],
        capture_output=True,
    )
    if out.returncode != 0 or not out.stdout:
        return False
    with open(local, "wb") as handle:
        handle.write(out.stdout)
    return True


@contextlib.contextmanager
def pull_db(adb, serial, workdir):
    stage = os.path.join(workdir, "auth_database.db")
    if not cat_remote(adb, serial, DB, stage):
        raise SystemExit(f"could not read {DB}; is {XMSF} installed on this device?")
    cat_remote(adb, serial, DB + "-wal", stage + "-wal")
    # Keep the untouched bytes: the in-place write below can only be verified
    # after it happened, and a failed write must not leave xmsf with a
    # half-written authorization cache.
    shutil.copyfile(stage, stage + ".orig")
    yield stage


def remote_size(adb, serial, path):
    out = sh(adb, serial, f"stat -c %s {path} 2>/dev/null").strip().splitlines()
    return int(out[-1]) if out and out[-1].isdigit() else 0


def push_db(adb, serial, path, fallback):
    # Root may write xmsf's database bytes but not truncate or replace the file
    # (SELinux denies it on the app-owned, category-labelled inode), so the new
    # content is written over the existing inode with dd conv=notrunc and padded
    # to the current size. Everything runs in one root shell because xmsf
    # reopens the database between two `su -c` calls.
    current = remote_size(adb, serial, DB)
    data = open(path, "rb").read()
    if len(data) < current:
        data = data + bytes(current - len(data))
        with open(path, "wb") as handle:
            handle.write(data)
    # xmsf keeps its own frames in the WAL and rewrites it the moment it starts,
    # so a partially cleared WAL replays the old rows over the new database.
    # Both side files are zeroed completely while xmsf is stopped.
    wal_blocks = (remote_size(adb, serial, DB + "-wal") + 4095) // 4096
    shm_blocks = (remote_size(adb, serial, DB + "-shm") + 4095) // 4096
    run(adb_base(adb, serial) + ["push", path, "/data/local/tmp/auth_stage.db"])
    sh(
        adb, serial,
        "am force-stop " + XMSF + "; sleep 1; "
        f"dd if=/dev/zero of={DB}-wal bs=4096 count={wal_blocks} conv=notrunc >/dev/null 2>&1; "
        f"dd if=/dev/zero of={DB}-shm bs=4096 count={shm_blocks} conv=notrunc >/dev/null 2>&1; "
        f"dd if=/data/local/tmp/auth_stage.db of={DB} bs=4096 conv=notrunc >/dev/null 2>&1; "
        f"head -c 6 {DB} | od -An -tx1; rm -f /data/local/tmp/auth_stage.db; "
        f"am force-stop {XMSF}",
    )
    check = sh(adb, serial, f"head -c 6 {DB} | od -An -tx1")
    if "53514c69" not in check.replace(" ", ""):
        run(adb_base(adb, serial) + ["push", fallback, "/data/local/tmp/auth_stage.db"])
        sh(adb, serial, "am force-stop " + XMSF + "; sleep 1; dd if=/data/local/tmp/auth_stage.db of="
                        + DB + " bs=4096 conv=notrunc >/dev/null 2>&1; rm -f /data/local/tmp/auth_stage.db; "
                        "am force-stop " + XMSF)
        raise SystemExit("the write did not leave a valid SQLite header; restored the original database")


def report(db, pkg):
    row = db.execute("select id, clientId from packageInfos where packageName=?", (pkg,)).fetchone()
    if not row:
        print(f"{pkg}: not seen by xmsf yet (post a notification once, then re-run)")
        return
    pid = row[0]
    signs = [v for (v,) in db.execute("select value from apkSigns where packageInfoId=?", (pid,))]
    scopes = list(db.execute(
        "select scope, startTime, duration from scopeInfos where packageInfoId=?", (pid,)))
    print(f"{pkg}: id={pid} clientId={row[1]}")
    print(f"  signatures: {signs or 'none'}")
    print(f"  scopes: {scopes or 'none'}")
    print(f"  focus grant: {'yes' if any(s[0] == FOCUS_SCOPE for s in scopes) else 'no'}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("action", choices=["enable", "disable", "status"])
    ap.add_argument("--package", default="io.github.daepilot.debug")
    ap.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    ap.add_argument("--serial", default=os.environ.get("ADB_SERIAL"))
    ap.add_argument("--apksigner", default=os.environ.get("APKSIGNER"))
    ap.add_argument("--digest", help="skip the APK read and use this certificate digest")
    args = ap.parse_args()

    if args.action == "enable" and not args.digest:
        if not args.apksigner:
            raise SystemExit("pass --apksigner (or APKSIGNER) so the digest can be read, "
                             "or pass --digest directly")
        args.digest = apk_digest(args.adb, args.serial, args.package, args.apksigner)
        print(f"certificate digest {args.digest}")

    with tempfile.TemporaryDirectory() as work:
        with pull_db(args.adb, args.serial, work) as local:
            db = sqlite3.connect(local)
            if args.action == "status":
                report(db, args.package)
                db.close()
                return
            pid = db.execute(
                "select id from packageInfos where packageName=?", (args.package,)
            ).fetchone()
            if pid is None:
                if args.action == "disable":
                    print("nothing to remove")
                    db.close()
                    return
                cur = db.execute(
                    "insert into packageInfos(packageName,isDebug,appType) values(?,0,0)",
                    (args.package,),
                )
                pid = (cur.lastrowid,)
                print(f"registered packageInfos id={pid[0]}")
            pid = pid[0]
            key = f"0|{args.package}|{NOTIFICATION_ID}|null"
            if args.action == "disable":
                db.execute("delete from apkSigns where packageInfoId=?", (pid,))
                db.execute("delete from scopeInfos where packageInfoId=? and scope=?",
                           (pid, FOCUS_SCOPE))
            else:
                if not db.execute("select 1 from apkSigns where packageInfoId=? and value=?",
                                  (pid, args.digest)).fetchone():
                    db.execute("insert into apkSigns(packageInfoId,value) values(?,?)",
                               (pid, args.digest))
                now = int(time.time() * 1000)
                if db.execute("select 1 from scopeInfos where packageInfoId=? and scope=?",
                              (pid, FOCUS_SCOPE)).fetchone():
                    db.execute("update scopeInfos set startTime=?, duration=?"
                               " where packageInfoId=? and scope=?", (now, DURATION_MS, pid, FOCUS_SCOPE))
                else:
                    db.execute("insert into scopeInfos(packageInfoId,scope,startTime,duration)"
                               " values(?,?,?,?)", (pid, FOCUS_SCOPE, now, DURATION_MS))
            db.commit()
            report(db, args.package)
            db.execute("pragma journal_mode=delete")
            db.close()
            for suffix in ("-wal", "-shm"):
                with contextlib.suppress(OSError):
                    os.remove(local + suffix)
            push_db(args.adb, args.serial, local, local + ".orig")

    print(f"notification key: {key}|<uid>  (see `adb shell dumpsys notification | grep {args.package})")
    print("restart DaePilot so it re-posts the notification, then check:")
    print(f"  adb logcat -d | grep FocusPlugin   -> expect onAuthSuccess for {args.package}")
    if args.action == "enable" and shutil.which(args.adb) is None:
        print("note: adb was resolved through an absolute path; keep it for re-runs")


if __name__ == "__main__":
    main()
