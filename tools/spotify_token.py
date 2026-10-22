#!/usr/bin/env python3
"""Gets a Spotify refresh token for "Now playing" and writes it to config.properties (spotify_refresh_token).

Needs spotify_client_id and spotify_client_secret in config.properties, and http://127.0.0.1:8888/callback as a
redirect URI of that Spotify app. Opens the browser to sign in once.
"""
import base64
import http.server
import json
import os
import re
import secrets
import urllib.parse
import urllib.request
import webbrowser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config.properties")
REDIRECT = "http://127.0.0.1:8888/callback"
SCOPES = "user-read-currently-playing user-read-playback-state"


def prop(text, name):
    m = re.search(r"^\s*" + name + r"\s*=(.*)$", text, re.M)
    return m.group(1).strip() if m else ""


def main():
    text = open(CONFIG, encoding="utf-8").read()
    client_id, secret = prop(text, "spotify_client_id"), prop(text, "spotify_client_secret")
    if not client_id or not secret:
        raise SystemExit("Fill in spotify_client_id and spotify_client_secret in config.properties first.")
    state = secrets.token_urlsafe(16)
    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if q.get("state", [""])[0] == state and "code" in q:
                result["code"] = q["code"][0]
                body = "Done, you can close this tab."
            else:
                body = "Spotify said: " + q.get("error", ["unknown"])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(body.encode())

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 8888), Handler)
    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": client_id, "response_type": "code", "redirect_uri": REDIRECT, "scope": SCOPES, "state": state})
    print("Opening the browser to sign in to Spotify...")
    webbrowser.open(url)
    while "code" not in result:
        server.handle_request()
    auth = base64.b64encode((client_id + ":" + secret).encode()).decode()
    req = urllib.request.Request("https://accounts.spotify.com/api/token", data=urllib.parse.urlencode({
        "grant_type": "authorization_code", "code": result["code"], "redirect_uri": REDIRECT}).encode(),
        headers={"Authorization": "Basic " + auth, "Content-Type": "application/x-www-form-urlencoded"})
    token = json.load(urllib.request.urlopen(req))["refresh_token"]
    if re.search(r"^\s*spotify_refresh_token\s*=", text, re.M):
        text = re.sub(r"^\s*spotify_refresh_token\s*=.*$", "spotify_refresh_token=" + token, text, flags=re.M)
    else:
        text = text.rstrip("\n") + "\nspotify_refresh_token=" + token + "\n"
    open(CONFIG, "w", encoding="utf-8").write(text)
    print("Saved spotify_refresh_token in config.properties. Now run ./build.sh and install.")


if __name__ == "__main__":
    main()
