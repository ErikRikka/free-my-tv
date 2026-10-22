#!/usr/bin/env python3
"""Signs Free My TV in to your YouTube account (read-only) with a device code and writes youtube_refresh_token to
config.properties. Needs youtube_client_id and youtube_client_secret there: a Google Cloud OAuth client of type
"TVs and Limited Input devices", with the YouTube Data API v3 enabled in that project."""
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config.properties")
SCOPE = "https://www.googleapis.com/auth/youtube.readonly"


def prop(text, name):
    m = re.search(r"^\s*" + name + r"\s*=(.*)$", text, re.M)
    return m.group(1).strip() if m else ""


def post(url, fields):
    try:
        return json.load(urllib.request.urlopen(urllib.request.Request(url, data=urllib.parse.urlencode(fields).encode()), timeout=15))
    except urllib.error.HTTPError as e:
        return json.loads(e.read() or b"{}")


def main():
    text = open(CONFIG, encoding="utf-8").read()
    client, secret = prop(text, "youtube_client_id"), prop(text, "youtube_client_secret")
    if not client or not secret:
        raise SystemExit("Fill in youtube_client_id and youtube_client_secret in config.properties first.")
    d = post("https://oauth2.googleapis.com/device/code", {"client_id": client, "scope": SCOPE})
    if "user_code" not in d:
        raise SystemExit("Google said: " + json.dumps(d))
    print("Open %s and type the code: %s" % (d["verification_url"], d["user_code"]), flush=True)
    deadline, interval = time.time() + d.get("expires_in", 1800), d.get("interval", 5)
    while time.time() < deadline:
        time.sleep(interval)
        t = post("https://oauth2.googleapis.com/token", {"client_id": client, "client_secret": secret,
                 "device_code": d["device_code"], "grant_type": "urn:ietf:params:oauth:grant-type:device_code"})
        if "refresh_token" in t:
            break
        if t.get("error") == "slow_down":
            interval += 5
        elif t.get("error") not in ("authorization_pending", None):
            raise SystemExit("Google said: " + t.get("error", "") + " " + t.get("error_description", ""))
    else:
        raise SystemExit("The code expired; run it again.")
    line = "youtube_refresh_token=" + t["refresh_token"]
    if re.search(r"^\s*youtube_refresh_token\s*=", text, re.M):
        text = re.sub(r"^\s*youtube_refresh_token\s*=.*$", line, text, flags=re.M)
    else:
        text = text.rstrip("\n") + "\n" + line + "\n"
    open(CONFIG, "w", encoding="utf-8").write(text)
    print("Signed in. Saved youtube_refresh_token in config.properties.", flush=True)


if __name__ == "__main__":
    main()
