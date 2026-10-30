#!/usr/bin/env bash
# Seeds a realistic catalog through the admin API, for load tests and query measurements.
#
#   scripts/seed-catalog.sh [base-url]        (default http://localhost:8080)
#
# Defaults: 1,000 products across 20 categories in store DEFAULT. Each product gets
# 4 attributes (one value from each of color, size, material and finish), 3 variants
# (color variations) and 3 images, plus a 400-character description.
# Tune with environment variables:
#   PRODUCTS=1000 CATEGORIES=20 ATTRIBUTES=4 VARIANTS=3 IMAGES=3 WORKERS=4
#   ADMIN_USER=admin@shopizer.com ADMIN_PASSWORD=password STORE=DEFAULT
# Keep WORKERS small. Each product write holds a pooled connection for the whole request,
# and the table-based id generator borrows a second one while it holds a JVM-wide lock;
# with 8 writers and slow requests the 15-connection mysql pool stalled in testing.
#
# Safe to re-run: options, values, variations and categories are looked up before they
# are created, and a product whose SKU already exists is skipped. It does not touch the
# oak-bench product or the furniture category that scripts/demo-checkout.sh creates.
# Products get sortOrder 10 and up, so a demo product with the default 0 still lists first.
set -euo pipefail
BASE="${1:-http://localhost:8080}"

exec python3 - "$BASE" <<'PY'
import json, os, sys, threading, time, uuid, urllib.error, urllib.request, zlib, struct
from concurrent.futures import ThreadPoolExecutor

BASE = sys.argv[1].rstrip("/")
API = BASE + "/api"
STORE = os.environ.get("STORE", "DEFAULT")
PRODUCTS = int(os.environ.get("PRODUCTS", "1000"))
CATEGORIES = int(os.environ.get("CATEGORIES", "20"))
ATTRIBUTES = int(os.environ.get("ATTRIBUTES", "4"))
VARIANTS = int(os.environ.get("VARIANTS", "3"))
IMAGES = int(os.environ.get("IMAGES", "3"))
WORKERS = int(os.environ.get("WORKERS", "4"))
USER = os.environ.get("ADMIN_USER", "admin@shopizer.com")
PASSWORD = os.environ.get("ADMIN_PASSWORD", "password")

OPTIONS = {
    "color": ["red", "blue", "green", "black", "white"],
    "size": ["xs", "s", "m", "l", "xl"],
    "material": ["oak", "walnut", "steel", "linen", "wool"],
    "finish": ["matte", "gloss", "satin", "natural", "oiled"],
}
CATEGORY_NAMES = ["Chairs", "Tables", "Sofas", "Beds", "Desks", "Shelves", "Lamps", "Rugs", "Mirrors",
                  "Cabinets", "Benches", "Stools", "Wardrobes", "Dressers", "Ottomans", "Cushions",
                  "Curtains", "Planters", "Clocks", "Frames", "Vases", "Baskets", "Throws", "Trays"]
ADJECTIVES = ["Nordic", "Classic", "Rustic", "Modern", "Compact", "Grand", "Soft", "Coastal", "Urban", "Heritage"]
LOREM = ("Made by a small workshop from responsibly sourced materials. Every piece is finished by hand, "
         "checked twice and packed in recycled cardboard. The frame is built for daily use and the surface "
         "resists stains, so it keeps its looks for years. Assembly takes about ten minutes with the tool "
         "in the box. Care: wipe with a soft dry cloth and avoid direct sunlight. Two-year warranty included.")

_local = threading.local()
token = None


def call(method, path, body=None, raw=None, content_type="application/json", auth=True, ok=(200, 201)):
    # writes occasionally fail with a 5xx or time out under concurrency (the image store,
    # and the async product index listener sharing the request's session), so a 5xx or a
    # timeout is retried twice before the product is reported as failed. A retried write
    # may already have been applied; seed_product accepts the duplicate in that case.
    for attempt in range(3):
        status, result = call_once(method, path, body, raw, content_type, auth, ok)
        if status < 500:
            return status, result
        time.sleep(0.5 * (attempt + 1))
    return status, result


def call_once(method, path, body, raw, content_type, auth, ok):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(API + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", content_type)
    if auth:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            text = r.read().decode() or "{}"
            return r.status, json.loads(text) if text.strip().startswith(("{", "[")) else {}
    except urllib.error.HTTPError as e:
        text = e.read().decode(errors="replace")
        if e.code in ok:
            return e.code, {}
        return e.code, {"error": text[:300]}
    except (urllib.error.URLError, OSError) as e:
        # timeouts and dropped connections: 599 so call() retries them like a 5xx
        return 599, {"error": f"{type(e).__name__}: {e}"}


def must(status, body, what, duplicate_ok=False):
    if duplicate_ok and status >= 400 and "Duplicate entry" in str(body):
        return body
    if status not in (200, 201):
        raise SystemExit(f"{what} failed: HTTP {status} {body}")
    return body


def png(seed):
    r, g, b = (seed * 37) % 256, (seed * 91) % 256, (seed * 53) % 256
    raw = b"".join(b"\x00" + bytes([r, g, b]) * 8 for _ in range(8))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def multipart(filename, payload):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + payload + f"\r\n--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def login():
    global token
    s, b = call("POST", "/v1/private/login", {"username": USER, "password": PASSWORD}, auth=False)
    token = must(s, b, "admin login")["token"]


def ensure_options():
    s, b = call("GET", "/v1/private/product/options?count=500&store=" + STORE)
    have = {o["code"]: o["id"] for o in must(s, b, "list options").get("options", [])}
    s, b = call("GET", "/v1/private/product/options/values?count=1000&store=" + STORE)
    have_values = {v["code"]: v["id"] for v in must(s, b, "list option values").get("optionValues", [])}
    option_ids, value_ids = {}, {}
    for order, (code, values) in enumerate(list(OPTIONS.items())[:max(ATTRIBUTES, 1)]):
        opt = "seed-" + code
        if opt not in have:
            s, b = call("POST", "/v1/private/product/option", {"code": opt, "type": "select", "order": order,
                        "descriptions": [{"language": "en", "name": code.capitalize()}]})
            have[opt] = must(s, b, "create option " + opt)["id"]
        option_ids[code] = have[opt]
        for i, v in enumerate(values):
            vc = f"seed-{code}-{v}"
            if vc not in have_values:
                s, b = call("POST", "/v1/private/product/option/value", {"code": vc, "order": i,
                            "descriptions": [{"language": "en", "name": v.capitalize()}]})
                have_values[vc] = must(s, b, "create option value " + vc)["id"]
            value_ids[(code, v)] = have_values[vc]
    return option_ids, value_ids


def ensure_variations(option_ids, value_ids):
    def listing():
        s, b = call("GET", "/v2/private/product/variations?count=500&store=" + STORE)
        return {v["code"]: v["id"] for v in must(s, b, "list variations").get("items", [])}

    have = listing()
    for v in OPTIONS["color"]:
        code = "seed-var-color-" + v
        if code not in have:
            # the create response carries id 0, so ids come from the listing below
            s, b = call("POST", "/v2/private/product/variation", {"code": code, "option": option_ids["color"],
                        "optionValue": value_ids[("color", v)]})
            must(s, b, "create variation " + code)
    have = listing()
    return [have["seed-var-color-" + v] for v in OPTIONS["color"]]


def ensure_categories():
    ids = []
    for i in range(CATEGORIES):
        name = CATEGORY_NAMES[i % len(CATEGORY_NAMES)] + ("" if i < len(CATEGORY_NAMES) else f" {i}")
        slug = "seed-" + name.lower().replace(" ", "-")
        s, b = call("GET", f"/v1/category/{slug}?store={STORE}&lang=en", auth=False)
        if s == 200 and b.get("id"):
            ids.append(b["id"])
            continue
        s, b = call("POST", "/v1/private/category", {"code": slug, "visible": True, "sortOrder": i,
                    "descriptions": [{"language": "en", "name": name, "friendlyUrl": slug, "title": name}]})
        ids.append(must(s, b, "create category " + slug)["id"])
    return ids


def seed_product(n, category_ids, value_ids, variation_ids):
    sku = f"seed-{n:05d}"
    s, b = call("GET", f"/v1/private/product/unique?code={sku}&store={STORE}")
    if s == 200 and b.get("exists"):
        return "skipped"
    cat = category_ids[n % len(category_ids)]
    cats = [{"id": cat}]
    if n % 3 == 0 and len(category_ids) > 1:
        cats.append({"id": category_ids[(n + 7) % len(category_ids)]})
    name = f"{ADJECTIVES[n % len(ADJECTIVES)]} {CATEGORY_NAMES[n % len(CATEGORY_NAMES)][:-1]} {n}"
    product = {
        "identifier": sku, "sku": sku, "visible": True, "shipeable": True, "canBePurchased": True,
        "price": round(19 + (n * 7.31) % 480, 2), "quantity": 20 + n % 200, "sortOrder": 10 + n,
        "dateAvailable": "2026-01-01", "categories": cats, "type": "GENERAL",
        "productSpecifications": {"weight": 5, "height": 40, "width": 60, "length": 50},
        "descriptions": [{"language": "en", "name": name, "title": name, "friendlyUrl": sku,
                          "description": LOREM, "metaDescription": LOREM[:150], "keyWords": "seed,catalog"}],
    }
    s, b = call("POST", "/v2/private/product", product)
    pid = must(s, b, "create product " + sku)["id"]
    for a, code in enumerate(list(OPTIONS)[:ATTRIBUTES]):
        values = OPTIONS[code]
        v = values[(n + a) % len(values)]
        s, b = call("POST", f"/v1/private/product/{pid}/attribute", {"option": {"id": OPTION_IDS[code]},
                    "optionValue": {"id": value_ids[(code, v)]}, "productAttributePrice": 0, "sortOrder": a})
        must(s, b, f"attribute {code} on {sku}", duplicate_ok=True)
    for k in range(VARIANTS):
        var = variation_ids[(n + k) % len(variation_ids)]
        s, b = call("POST", f"/v2/private/product/{pid}/variant", {"sku": f"{sku}-v{k}", "variation": var,
                    "available": True, "dateAvailable": "2026-01-01", "sortOrder": k})
        must(s, b, f"variant {k} on {sku}", duplicate_ok=True)
    for k in range(IMAGES):
        body, ctype = multipart(f"{sku}-{k}.png", png(n * 10 + k))
        s, b = call("POST", f"/v1/private/product/{pid}/image", raw=body, content_type=ctype)
        must(s, b, f"image {k} on {sku}")
    return "created"


started = time.time()
login()
OPTION_IDS, VALUE_IDS = ensure_options()
VARIATION_IDS = ensure_variations(OPTION_IDS, VALUE_IDS) if VARIANTS > 0 else []
CATEGORY_IDS = ensure_categories()
print(f"options {len(OPTION_IDS)}, values {len(VALUE_IDS)}, variations {len(VARIATION_IDS)}, "
      f"categories {len(CATEGORY_IDS)}", flush=True)

counts = {"created": 0, "skipped": 0, "failed": 0}
lock = threading.Lock()


def work(n):
    try:
        result = seed_product(n, CATEGORY_IDS, VALUE_IDS, VARIATION_IDS)
    except SystemExit as e:
        print(f"  {e}", flush=True)
        result = "failed"
    with lock:
        counts[result] += 1
        done = sum(counts.values())
        if done % 100 == 0:
            print(f"  {done}/{PRODUCTS} products ({time.time() - started:.0f}s)", flush=True)


with ThreadPoolExecutor(max_workers=WORKERS) as pool:
    for f in [pool.submit(work, n) for n in range(1, PRODUCTS + 1)]:
        f.result()

s, b = call("GET", f"/v1/products?store={STORE}&lang=en&count=1", auth=False)
print(f"done in {time.time() - started:.0f}s: created {counts['created']}, skipped {counts['skipped']}, "
      f"failed {counts['failed']}; "
      f"storefront recordsTotal {b.get('recordsTotal')}")
PY
