import json
import re
import sys
import urllib.parse
import urllib.request
import http.cookiejar

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
SEEDS = [
    "streamingunity.win",
    "streamingcommunityz.taxi",
    "streamingunity.vip",
    "streamingunity.cc",
    "streamingunity.dog",
    "streamingunity.biz",
]


def opener():
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar)), jar


def get(op, url, headers=None, data=None):
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
    req.add_header("User-Agent", UA)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with op.open(req, timeout=25) as r:
        return r.status, r.geturl(), r.read()


def app_url_of(host):
    op, _ = opener()
    try:
        _, final, body = get(op, "https://%s/it/archive" % host)
    except Exception:
        return None, None
    m = re.search(rb'id="app"[^>]*data-page="([^"]*)"', body)
    if not m:
        return None, urllib.parse.urlsplit(final).netloc
    import html as _h
    props = json.loads(_h.unescape(m.group(1).decode("utf-8", "replace")))["props"]
    return props.get("app_url"), urllib.parse.urlsplit(final).netloc


def validate(host):
    op, jar = opener()
    try:
        status, final, body = get(op, "https://%s/it/archive" % host)
    except Exception as e:
        return False, "archive failed: %s" % e
    if status != 200:
        return False, "archive HTTP %s" % status
    if urllib.parse.urlsplit(final).netloc.lower() != host.lower():
        return False, "archive redirects to %s" % urllib.parse.urlsplit(final).netloc
    try:
        get(op, "https://%s/sanctum/csrf-cookie" % host,
            {"Referer": "https://%s/it/" % host, "X-Requested-With": "XMLHttpRequest"})
    except Exception as e:
        return False, "csrf failed: %s" % e
    token = ""
    for c in jar:
        if c.name == "XSRF-TOKEN":
            token = urllib.parse.unquote(c.value)
    try:
        status, _, body = get(
            op, "https://%s/api/sliders/fetch?lang=it" % host,
            {"X-XSRF-TOKEN": token, "Content-Type": "application/json",
             "X-Requested-With": "XMLHttpRequest", "Referer": "https://%s/it/" % host},
            json.dumps({"sliders": [{"name": "trending", "genre": None}]}).encode(),
        )
    except Exception as e:
        return False, "sliders POST failed: %s" % e
    if status != 200 or not body.lstrip().startswith(b"["):
        return False, "sliders POST HTTP %s" % status
    return True, "ok"


def main():
    current = sys.argv[1] if len(sys.argv) > 1 else ""
    candidates = []
    for h in ([current] if current else []) + SEEDS:
        if h and h not in candidates:
            candidates.append(h)
    for seed in SEEDS:
        declared, landed = app_url_of(seed)
        for h in (urllib.parse.urlsplit(declared).netloc if declared else None, landed):
            if h and h not in candidates:
                candidates.append(h)
        if declared or landed:
            break
    for h in candidates:
        ok, why = validate(h)
        print("  %-28s %s" % (h, why), file=sys.stderr)
        if ok:
            print(h)
            return 0
    print("no working host found", file=sys.stderr)
    return 1


sys.exit(main())
