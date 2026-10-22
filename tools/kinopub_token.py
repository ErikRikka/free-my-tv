#!/usr/bin/env python3
"""Signs Free My TV in to kino.pub (KINO.WATCH) with a device code and writes kinopub_refresh_token to
config.properties. Run it, open the address it prints on any device, type the code, done."""
import json
import os
import re
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config.properties")
API = "https://api.service-kp.com"
CLIENT = {"client_id": "xbmc", "client_secret": "cgg3gtifu46urtfp2zp1nqtba0k2ezxh"}   # Kodi's public client


def post(path, **fields):
    data = urllib.parse.urlencode(dict(CLIENT, **fields)).encode()
    try:
        return json.load(urllib.request.urlopen(urllib.request.Request(API + path, data=data), timeout=15))
    except urllib.error.HTTPError as e:
        return json.loads(e.read() or b"{}")


def main():
    d = post("/oauth2/device", grant_type="device_code")
    print("Open %s and type the code: %s" % (d["verification_uri"], d["user_code"]), flush=True)
    deadline = time.time() + d.get("expires_in", 300)
    while time.time() < deadline:
        time.sleep(d.get("interval", 5))
        t = post("/oauth2/device", grant_type="device_token", code=d["code"])
        if "refresh_token" in t:
            break
    else:
        raise SystemExit("The code expired; run it again.")
    req = urllib.request.Request(API + "/v1/device/notify", data=urllib.parse.urlencode(
        {"title": "Free My TV", "hardware": "TCL TV", "software": "Android TV"}).encode(),
        headers={"Authorization": "Bearer " + t["access_token"]})
    try:
        urllib.request.urlopen(req, timeout=15)
    except Exception:
        pass
    text = open(CONFIG, encoding="utf-8").read()
    line = "kinopub_refresh_token=" + t["refresh_token"]
    if re.search(r"^\s*kinopub_refresh_token\s*=", text, re.M):
        text = re.sub(r"^\s*kinopub_refresh_token\s*=.*$", line, text, flags=re.M)
    else:
        text = text.rstrip("\n") + "\n\n# KINO.WATCH (kino.pub): python3 tools/kinopub_token.py fills it in.\n" + line + "\n"
    open(CONFIG, "w", encoding="utf-8").write(text)
    print("Signed in. Saved kinopub_refresh_token in config.properties.", flush=True)


if __name__ == "__main__":
    main()
