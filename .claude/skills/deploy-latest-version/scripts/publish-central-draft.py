#!/usr/bin/env python3
"""Publish a validated Sonatype Central draft, then wait until PUBLISHED.

Reads Portal user-token username/password from ~/.m2/settings.xml
(server id: central). Does not print credentials.

Usage:
  python3 publish-central-draft.py --deployment-id UUID
  python3 publish-central-draft.py --from-maven-log mvn.log
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

BASE = "https://central.sonatype.com/api/v1/publisher"
SETTINGS = Path.home() / ".m2" / "settings.xml"
UUID_RE = re.compile(
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
)
DEPLOYMENT_ID_RE = re.compile(r"deploymentId:\s*(" + UUID_RE.pattern + r")")


def die(msg: str, code: int = 1) -> None:
    print(msg, file=sys.stderr)
    raise SystemExit(code)


def qname(ns: str, tag: str) -> str:
    return f"{{{ns}}}{tag}" if ns else tag


def load_central_creds() -> tuple[str, str]:
    if not SETTINGS.is_file():
        die(f"missing {SETTINGS}")
    root = ET.parse(SETTINGS).getroot()
    ns = root.tag.split("}")[0][1:] if root.tag.startswith("{") else ""
    servers = root.find(qname(ns, "servers"))
    if servers is None:
        die("no <servers> in settings.xml")
    for server in list(servers):
        if server.findtext(qname(ns, "id")) == "central":
            user = server.findtext(qname(ns, "username"))
            password = server.findtext(qname(ns, "password"))
            if not user or not password:
                die("central server is missing username or password")
            return user, password
    die('no <server><id>central</id> in ~/.m2/settings.xml')


def bearer_token(user: str, password: str) -> str:
    return base64.b64encode(f"{user}:{password}".encode()).decode()


def request(method: str, url: str, token: str, timeout: int = 60) -> tuple[int, str]:
    req = urllib.request.Request(
        url,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as err:
        return err.code, err.read().decode(errors="replace")


def deployment_status(token: str, deployment_id: str) -> dict:
    code, body = request("POST", f"{BASE}/status?id={deployment_id}", token)
    if code != 200:
        die(f"status failed HTTP {code}: {body}")
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        die(f"status returned non-JSON: {body}")


def parse_deployment_id(text: str) -> str | None:
    match = DEPLOYMENT_ID_RE.search(text)
    if match:
        return match.group(1)
    ids = UUID_RE.findall(text)
    return ids[-1] if ids else None


def extract_id_from_log(path: Path) -> str:
    text = path.read_text(errors="replace")
    found = parse_deployment_id(text)
    if not found:
        die(f"no deploymentId in {path}")
    return found


def verify_repo1(group_id: str, artifact_id: str, version: str) -> bool:
    path = "/".join(group_id.split("."))
    url = (
        f"https://repo1.maven.org/maven2/{path}/{artifact_id}/"
        f"{version}/{artifact_id}-{version}.pom"
    )
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print(f"repo1 HTTP {resp.status} {url}")
            return resp.status == 200
    except urllib.error.HTTPError as err:
        print(f"repo1 HTTP {err.code} {url}")
        return False
    except urllib.error.URLError as err:
        print(f"repo1 error {err} {url}")
        return False


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deployment-id")
    parser.add_argument("--from-maven-log", type=Path)
    parser.add_argument("--wait-seconds", type=int, default=300)
    parser.add_argument("--poll-seconds", type=int, default=10)
    parser.add_argument("--group-id", default="com.machingclee")
    parser.add_argument("--artifact-id", default="domain-util")
    parser.add_argument("--version")
    args = parser.parse_args()

    if args.deployment_id:
        deployment_id = args.deployment_id.strip()
    elif args.from_maven_log:
        deployment_id = extract_id_from_log(args.from_maven_log)
    else:
        die("pass --deployment-id or --from-maven-log")

    if not UUID_RE.fullmatch(deployment_id):
        die(f"invalid deployment id: {deployment_id}")

    user, password = load_central_creds()
    token = bearer_token(user, password)
    print(f"using central token user {user[:4]}… from {SETTINGS}")
    print(f"deploymentId {deployment_id}")

    st = deployment_status(token, deployment_id)
    state = st.get("deploymentState")
    print(f"current state: {state}")
    if st.get("errors"):
        print("errors:", json.dumps(st.get("errors")))
    if st.get("warnings"):
        print("warnings:", json.dumps(st.get("warnings")))

    if state == "FAILED":
        die("deployment FAILED; will not publish")
    if state not in {"VALIDATED", "PUBLISHING", "PUBLISHED"}:
        die(f"cannot publish from state {state}")

    if state == "VALIDATED":
        code, body = request(
            "POST", f"{BASE}/deployment/{deployment_id}", token
        )
        print(f"publish HTTP {code}")
        if body:
            print(body)
        if code not in (200, 204):
            die(f"publish failed HTTP {code}")
        print("publish request accepted")

    deadline = time.time() + args.wait_seconds
    last = st
    while True:
        last = deployment_status(token, deployment_id)
        state = last.get("deploymentState")
        print(f"{time.strftime('%H:%M:%S')} {state}")
        if state == "PUBLISHED":
            break
        if state == "FAILED":
            die(f"deployment FAILED: {json.dumps(last)}")
        if time.time() >= deadline:
            die(f"timed out waiting for PUBLISHED; last state {state}")
        time.sleep(args.poll_seconds)

    print(json.dumps(last, indent=2))
    if args.version:
        ok = verify_repo1(args.group_id, args.artifact_id, args.version)
        if not ok:
            print(
                "PUBLISHED, but repo1 POM is not visible yet "
                "(search.maven.org often lags too)."
            )
            raise SystemExit(2)
    print("PUBLISHED")


if __name__ == "__main__":
    main()
