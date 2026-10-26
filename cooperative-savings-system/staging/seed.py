"""Seeds a fresh staging database with demo saving schemes, one per operating model, and (when COMRADES_SEED_JSON is
set in the environment) the migration of a real scheme. Runs once: it stops if the demo schemes already exist.

Everything goes through the public API, exactly as an officer would do it, so the seed also exercises the features.
"""
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date

BASE = f"http://127.0.0.1:{os.environ.get('PORT', '8080')}"
ADMIN_USER = "superadmin"
ADMIN_DEFAULT = "ChangeMe@123!"
ADMIN_PASSWORD = os.environ.get("STAGING_ADMIN_PASSWORD") or ADMIN_DEFAULT
DEMO_PASSWORD = os.environ.get("STAGING_DEMO_PASSWORD") or "Demo@2026!"
TODAY = date.today()


def log(*a):
    print("[seed]", *a, flush=True)


class ApiError(Exception):
    pass


def call(method, path, token=None, body=None, raw=None, content_type=None, accept_bytes=False):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if raw is not None:
        data = raw
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = "Bearer " + token
    headers["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            payload = r.read()
            if accept_bytes:
                return payload
            return json.loads(payload or b"{}").get("data")
    except urllib.error.HTTPError as e:
        text = e.read().decode(errors="replace")[:1500]
        raise ApiError(f"{method} {path} -> {e.code}: {text}")


def login(username, password):
    return call("POST", "/api/v1/auth/login", body={"username": username, "password": password})["accessToken"]


def wait_for_api():
    for _ in range(240):
        try:
            with urllib.request.urlopen(BASE + "/actuator/health", timeout=5) as r:
                if r.status == 200:
                    return
        except Exception:
            pass
        time.sleep(3)
    raise SystemExit("[seed] backend did not come up")


def month_add(y, m, k):
    t = y * 12 + (m - 1) + k
    return t // 12, t % 12 + 1


# --------------------------------------------------------------------------- workbooks

def filled_workbook(template_bytes, sheets):
    """Fills the downloaded template: sheets = {sheet name: [ {header: value} ]}."""
    import openpyxl

    wb = openpyxl.load_workbook(io.BytesIO(template_bytes))
    for name, rows in sheets.items():
        ws = wb[name]
        headers = [c.value for c in ws[1]]
        for i, row in enumerate(rows, start=2):
            for j, h in enumerate(headers, start=1):
                if h in row and row[h] is not None:
                    ws.cell(row=i, column=j, value=row[h])
    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


def multipart(file_bytes, filename):
    boundary = "----seed" + uuid.uuid4().hex
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
        "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n"
    ).encode() + file_bytes + f"\r\n--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def import_workbook(coop, token, template_path, sheets, cutover, funds=None):
    template = call("GET", f"/api/v1/cooperatives/{coop}/historical-imports/{template_path}", token, accept_bytes=True)
    data = filled_workbook(template, sheets)
    body, ctype = multipart(data, "seed.xlsx")
    q = f"?cutoverDate={cutover}" + (f"&openingAvailableFunds={funds}" if funds is not None else "")
    preview = call("POST", f"/api/v1/cooperatives/{coop}/historical-imports/preview{q}", token, raw=body, content_type=ctype)
    errors = preview.get("errors") or []
    if errors:
        log("import preview errors:", json.dumps(errors)[:1500])
    imp = preview.get("id") or preview.get("importId")
    call("POST", f"/api/v1/cooperatives/{coop}/historical-imports/{imp}/confirm", token)
    return imp


PHONE = [788100000]


def next_phone():
    PHONE[0] += 1
    return f"250{PHONE[0]}"


def member_rows(members, email_domain):
    reg = []
    cap = []
    for m in members:
        reg.append({
            "Full Name": m["name"],
            "Username": m["username"],
            "Email": m.get("email") or f"{m['username']}@{email_domain}",
            "Phone Number": m.get("phone") or next_phone(),
            "Total Contributions to Date (RWF)": m.get("contributions", 0),
        })
        cap.append({
            "Username": m["username"],
            "Share Count": m["shares"],
            "Share Capital Contributed to Date (RWF)": m.get("capital", 0),
        })
    return {"Member Registration": reg, "Share Capital Registration": cap}


# --------------------------------------------------------------------------- schemes

def create_scheme(admin, spec):
    president = spec["president"]
    body = {
        "name": spec["name"],
        "description": spec.get("description", "Staging demo"),
        "registrationNumber": spec["reg"],
        "contactEmail": f"{spec['reg'].lower()}@staging.local",
        "contactPhone": "250788000000",
        "currency": "RWF",
        "financialYearStartMonth": 1,
        "monthlyContributionAmount": spec["unit"],
        "contributionDueDay": 10,
        "registrationDate": spec.get("registered", "2025-01-01"),
        "operatingModel": spec["model"],
        "president": {
            "username": president["username"],
            "email": president.get("email") or f"{president['username']}@staging.local",
            "firstName": president["first"],
            "lastName": president["last"],
            "temporaryPassword": DEMO_PASSWORD,
        },
    }
    coop = call("POST", "/api/v1/cooperatives", admin, body=body)
    log("created", spec["name"], coop["id"])
    return coop["id"]


def register(coop, token, username, first, last, role="MEMBER", email=None):
    return call("POST", f"/api/v1/cooperatives/{coop}/members", token, body={
        "firstName": first, "lastName": last, "username": username,
        "email": email or f"{username}@staging.local", "temporaryPassword": DEMO_PASSWORD,
        "roleInCooperative": role, "membershipDate": "2025-01-01",
    })


def members_by_username(coop, token):
    page = call("GET", f"/api/v1/cooperatives/{coop}/members?size=500", token)
    rows = page.get("content", page) if isinstance(page, dict) else page
    return {r["username"]: r["userId"] for r in rows}


def turns_from_plan(plan, ids):
    """plan: [(month index, username, shares, received)]"""
    return [
        {"monthIndex": mi, "memberUserId": ids[u], "shares": s,
         **({"openingReceivedAmount": rec} if rec else {})}
        for mi, u, s, rec in plan
    ]


def seed_accumulating(admin):
    spec = {"name": "Demo Accumulating Savings", "reg": "DEMO-ACC", "unit": 20000, "model": "ACCUMULATING",
            "president": {"username": "acc.president", "first": "Aline", "last": "Uwase"}}
    coop = create_scheme(admin, spec)
    pres = login("acc.president", DEMO_PASSWORD)
    register(coop, pres, "acc.accountant", "Eric", "Habimana", "ACCOUNTANT")
    members = [
        {"name": "Aline Uwase", "username": "acc.president", "shares": 3, "capital": 720000, "contributions": 720000},
        {"name": "Eric Habimana", "username": "acc.accountant", "shares": 2, "capital": 480000, "contributions": 480000},
        {"name": "Claudine Mukamana", "username": "acc.claudine", "shares": 1, "capital": 240000, "contributions": 240000},
        {"name": "Patrick Nshuti", "username": "acc.patrick", "shares": 2, "capital": 400000, "contributions": 400000},
        {"name": "Grace Ingabire", "username": "acc.grace", "shares": 1, "capital": 200000, "contributions": 200000},
    ]
    import_workbook(coop, pres, "template", member_rows(members, "staging.local"), TODAY.replace(day=1).isoformat(), 1640000)
    call("PUT", f"/api/v1/cooperatives/{coop}/loan-settings", pres, body={
        "interestRatePercent": 2, "interestType": "FLAT", "maxTermMonths": 12, "allowMemberRequests": True,
        "maxLoanPerShare": 150000, "blockWhenContributionOverdue": True})
    call("PUT", f"/api/v1/cooperatives/{coop}/fine-settings", pres, body={
        "autoFinesEnabled": True, "fineMode": "BANDED", "baseFineAmount": 0, "dailyIncrement": 0, "graceDays": 0,
        "penaltyBands": "1-5:1000;6-15:2500;16-30:5000", "perShare": True,
        "creditConversionDays": 30, "creditConversionRatePercent": 2})
    return coop


def seed_rotating(admin):
    spec = {"name": "Demo Rotating Circle", "reg": "DEMO-ROT", "unit": 50000, "model": "ROTATING",
            "president": {"username": "rot.president", "first": "Jeanne", "last": "Mukeshimana"}}
    coop = create_scheme(admin, spec)
    pres = login("rot.president", DEMO_PASSWORD)
    register(coop, pres, "rot.accountant", "Olivier", "Mugisha", "ACCOUNTANT")
    members = [
        {"name": "Jeanne Mukeshimana", "username": "rot.president", "shares": 2},
        {"name": "Olivier Mugisha", "username": "rot.accountant", "shares": 1},
        {"name": "Divine Uwimana", "username": "rot.divine", "shares": 1},
        {"name": "Samuel Niyonzima", "username": "rot.samuel", "shares": 2},
        {"name": "Ange Iradukunda", "username": "rot.ange", "shares": 1},
        {"name": "Kevin Hakizimana", "username": "rot.kevin", "shares": 1},
    ]
    import_workbook(coop, pres, "template", member_rows(members, "staging.local"), TODAY.replace(day=1).isoformat(), 0)
    ny, nm = month_add(TODAY.year, TODAY.month, 1)
    call("POST", f"/api/v1/cooperatives/{coop}/rotation/configurations", pres,
         body={"effectiveYear": ny, "effectiveMonth": nm, "rotatingBps": 10000, "retainedBps": 0})
    ids = members_by_username(coop, pres)
    plan = [(1, "rot.president", 1, 0), (2, "rot.samuel", 1, 0), (3, "rot.divine", 1, 0), (4, "rot.accountant", 1, 0),
            (5, "rot.president", 1, 0), (6, "rot.ange", 1, 0), (7, "rot.samuel", 1, 0), (8, "rot.kevin", 1, 0)]
    cycle = call("POST", f"/api/v1/cooperatives/{coop}/rotation/share-cycles", pres, body={
        "startYear": ny, "startMonth": nm, "months": 8, "payoutPerShare": 400000,
        "turnMode": "ONE_SHARE_PER_TURN", "turns": turns_from_plan(plan, ids)})
    call("POST", f"/api/v1/cooperatives/{coop}/rotation/cycles/{cycle['id']}/activate", pres)
    return coop


def seed_hybrid(admin):
    spec = {"name": "Demo Hybrid Group", "reg": "DEMO-HYB", "unit": 70000, "model": "HYBRID",
            "president": {"username": "hyb.president", "first": "Esther", "last": "Uwera"}}
    coop = create_scheme(admin, spec)
    pres = login("hyb.president", DEMO_PASSWORD)
    register(coop, pres, "hyb.accountant", "David", "Twagirimana", "ACCOUNTANT")
    members = [
        {"name": "Esther Uwera", "username": "hyb.president", "shares": 1, "capital": 100000},
        {"name": "David Twagirimana", "username": "hyb.accountant", "shares": 2, "capital": 200000},
        {"name": "Alice Mutoni", "username": "hyb.alice", "shares": 1, "capital": 100000},
        {"name": "Bosco Ndayisaba", "username": "hyb.bosco", "shares": 1, "capital": 100000},
        {"name": "Chantal Umutesi", "username": "hyb.chantal", "shares": 1, "capital": 100000},
    ]
    # A six-month cycle that started five months ago: 50,000 of 70,000 rotates, 300,000 per share.
    sy, sm = month_add(TODAY.year, TODAY.month, -4)
    elapsed = 4
    import_workbook(coop, pres, "template", member_rows(members, "staging.local"), TODAY.replace(day=1).isoformat(),
                    600000 + 6 * 50000 * elapsed - 3 * 300000)
    ids = members_by_username(coop, pres)
    plan = [(1, "hyb.alice", 1, 300000), (2, "hyb.accountant", 2, 600000), (3, "hyb.bosco", 1, 0),
            (4, "hyb.president", 1, 0), (5, "hyb.chantal", 1, 0)]
    paid = {u: 50000 * m["shares"] * elapsed for u, m in ((m["username"], m) for m in members)}
    result = call("POST", f"/api/v1/cooperatives/{coop}/rotation/migration", pres, body={
        "cutoverYear": TODAY.year, "cutoverMonth": TODAY.month, "rotatingUnitAmount": 50000,
        "cycle": {"startYear": sy, "startMonth": sm, "months": 5, "payoutPerShare": 300000,
                  "turnMode": "GROUPED_BY_MONTH", "turns": turns_from_plan(plan, ids)},
        "members": [{"memberUserId": ids[u], "rotatedPaid": v} for u, v in paid.items()],
        "rotationCashAtCutover": sum(paid.values()) - 900000})
    log("hybrid rotation import:", json.dumps({k: result.get(k) for k in ("expectedRotationCash", "difference", "warnings")}))
    return coop


def seed_comrades(admin, data):
    """data (from the COMRADES_SEED_JSON environment variable, never committed):
    {name, reg, unit, rotated, cutover, start:'YYYY-MM', months, payoutPerShare, cash, members:[{name, username, shares,
     capital, contributions, rotatedPaid, received, turnMonth, email?, president?, loginPassword?}], loans:[{...row}]}"""
    pres_m = next(m for m in data["members"] if m.get("president"))
    first, _, last = pres_m["name"].partition(" ")
    spec = {"name": data["name"], "reg": data.get("reg", "COMRADES"), "unit": data["unit"], "model": "HYBRID",
            "registered": data.get("registered", "2024-12-01"),
            "president": {"username": pres_m["username"], "first": first, "last": last or first,
                          "email": pres_m.get("email")}}
    coop = create_scheme(admin, spec)
    pres = login(pres_m["username"], DEMO_PASSWORD)
    for m in data["members"]:
        if m.get("officer"):
            f, _, l = m["name"].partition(" ")
            register(coop, pres, m["username"], f, l or f, m["officer"], m.get("email"))
    import_workbook(coop, pres, "template", member_rows(data["members"], "comrades.staging"), data["cutover"], data.get("openingFunds"))
    if data.get("loans"):
        try:
            import_workbook(coop, pres, "loan-template", {"Loan Registration": data["loans"]}, data["cutover"])
        except ApiError as e:
            log("loans import skipped:", e)
    ids = members_by_username(coop, pres)
    sy, sm = (int(x) for x in data["start"].split("-"))
    plan = {}
    for m in data["members"]:
        for t in m.get("turns", []):
            key = (t["month"], m["username"])
            plan[key] = (t["month"], m["username"], t["shares"], t.get("received", 0))
    result = call("POST", f"/api/v1/cooperatives/{coop}/rotation/migration", pres, body={
        "cutoverYear": TODAY.year, "cutoverMonth": TODAY.month, "rotatingUnitAmount": data["rotated"],
        "cycle": {"startYear": sy, "startMonth": sm, "months": data["months"], "payoutPerShare": data["payoutPerShare"],
                  "turnMode": "GROUPED_BY_MONTH", "turns": turns_from_plan(sorted(plan.values()), ids)},
        "members": [{"memberUserId": ids[m["username"]], "rotatedPaid": m.get("rotatedPaid", 0)} for m in data["members"]],
        "rotationCashAtCutover": data.get("cash")})
    log("comrades rotation import:", json.dumps({k: result.get(k) for k in ("expectedRotationCash", "declaredRotationCash", "difference", "warnings")}))
    return coop


def main():
    wait_for_api()
    time.sleep(5)
    try:
        admin = login(ADMIN_USER, ADMIN_DEFAULT)
    except ApiError:
        log("default admin password no longer works: database already seeded")
        return
    existing = call("GET", "/api/v1/cooperatives?size=100", admin)
    rows = existing.get("content", existing) if isinstance(existing, dict) else existing
    names = {r.get("name") for r in (rows or [])}
    steps = [("Demo Accumulating Savings", seed_accumulating), ("Demo Rotating Circle", seed_rotating),
             ("Demo Hybrid Group", seed_hybrid)]
    failures = 0
    for name, fn in steps:
        if name in names:
            continue
        try:
            fn(admin)
        except Exception as e:  # keep seeding the others
            failures += 1
            log(f"{name} failed:", e)
    comrades = os.environ.get("COMRADES_SEED_JSON")
    if comrades:
        data = json.loads(comrades)
        if data["name"] not in names:
            try:
                seed_comrades(admin, data)
            except Exception as e:
                failures += 1
                log("comrades failed:", e)
    if ADMIN_PASSWORD != ADMIN_DEFAULT:
        call("POST", "/api/v1/auth/change-password", admin,
             body={"currentPassword": ADMIN_DEFAULT, "newPassword": ADMIN_PASSWORD})
        log("super admin password changed")
    log("done" if failures == 0 else f"done with {failures} failure(s)")
    if failures and os.environ.get("SEED_STRICT"):
        sys.exit(1)


if __name__ == "__main__":
    main()
