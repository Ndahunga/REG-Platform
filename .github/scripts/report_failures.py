"""Turns Maven/surefire failures into GitHub annotations (readable through the API without downloading logs)."""
import glob, sys, xml.etree.ElementTree as ET

msgs = []
try:
    for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
        if line.startswith("[ERROR]") and (".java:[" in line or "COMPILATION" in line or "Failed to execute" in line):
            msgs.append(line.strip()[:400])
except FileNotFoundError:
    pass
for f in glob.glob("target/surefire-reports/TEST-*.xml") + glob.glob("target/failsafe-reports/TEST-*.xml"):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for tc in root.iter("testcase"):
        for kind in ("failure", "error"):
            el = tc.find(kind)
            if el is not None:
                body = el.text or ""
                causes = [l.strip() for l in body.splitlines() if l.strip().startswith("Caused by")]
                if "failure threshold" in (el.get("message") or ""):
                    continue
                text = (el.get("message") or "")[:600] + " | " + " || ".join(causes[-3:])[:1500] + " | " + body[:400]
                msgs.append(f"{tc.get('classname')}.{tc.get('name')} [{kind}]: {text}")
def esc(s):
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
chunks, cur = [], ""
for m in msgs:
    if len(cur) + len(m) > 3500:
        chunks.append(cur); cur = ""
    cur += m + "\n"
if cur:
    chunks.append(cur)
for c in chunks[:10]:
    print("::error title=Backend failure::" + esc(c))
print(f"{len(msgs)} failure messages", file=sys.stderr)
