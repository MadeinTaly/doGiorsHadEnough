import re
import subprocess
import sys

SRC = "StreamingCommunity/src/main/kotlin/it/dogior/hadEnough/StreamingCommunity.kt"
GRADLE = "StreamingCommunity/build.gradle.kts"


def current_host():
    s = open(SRC, encoding="utf-8").read()
    return re.search(r'DEFAULT_BASE_URL = "https://([^/"]+)/"', s).group(1)


def apply(old, new):
    s = open(SRC, encoding="utf-8").read()
    s = s.replace('DEFAULT_BASE_URL = "https://%s/"' % old,
                  'DEFAULT_BASE_URL = "https://%s/"' % new)
    m = re.search(r'(\n(\s+)"([^"]+)" -> DEFAULT_BASE_URL\.toHttpUrl\(\)\.host)', s)
    indent, last = m.group(2), m.group(3)
    if old != last:
        s = s.replace(m.group(1),
                      '\n%s"%s",\n%s"%s" -> DEFAULT_BASE_URL.toHttpUrl().host'
                      % (indent, last, indent, old))
    open(SRC, "w", encoding="utf-8").write(s)

    g = open(GRADLE, encoding="utf-8").read()
    v = int(re.search(r"^version = (\d+)$", g, re.M).group(1))
    g = re.sub(r"^version = \d+$", "version = %d" % (v + 1), g, count=1, flags=re.M)
    open(GRADLE, "w", encoding="utf-8").write(g)
    return v + 1


old = current_host()
new = subprocess.run([sys.executable, ".github/scripts/resolve-sc-domain.py", old],
                     capture_output=True, text=True).stdout.strip()
if not new:
    print("resolver found no working host, leaving the source untouched")
    sys.exit(1)
if new == old:
    print("domain unchanged: %s" % old)
    sys.exit(0)
version = apply(old, new)
print("domain changed: %s -> %s (StreamingCommunity version %d)" % (old, new, version))
open("domain_change.txt", "w").write("%s|%s|%d" % (old, new, version))
