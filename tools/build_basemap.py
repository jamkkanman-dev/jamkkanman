"""행정안전부 도로명주소 도형(SHP) → 앱 지도 배경용 basemap_daechi.json

사용법
  1) 어떤 레이어가 있는지 보기
     python3 tools/build_basemap.py inspect <압축 푼 폴더>
  2) 지도 파일 만들기 (레이어이름=shp경로[:필드=값|값...])
     python3 tools/build_basemap.py build app/src/main/assets/basemap_daechi.json \
         building=<폴더>/TL_SPBD_BULD.shp \
         roadarea=<폴더>/TL_SPRD_RW.shp \
         roadline=<폴더>/TL_SPRD_MANAGE.shp \
         water=<폴더>/하천.shp park=<폴더>/공원.shp

- 입력: 주소기반산업지원서비스(business.juso.go.kr) › 제공하는 주소 › 도로명주소 건물 도형 / 도로명이 부여된 도로 도형 / 기타자료
- 좌표계: .prj 파일을 읽어 횡축 메르카토르(GRS80) 변수를 그대로 쓴다. .prj 가 없으면 도로명주소 기본값(UTM-K, EPSG:5179)
- 대치동 주변 사각형 안만 잘라 내고, 0.5m 보다 작은 굴곡은 줄여서 앱이 가볍게 그리도록 한다.
- 외부 라이브러리 없이 동작한다 (build_roads.py 와 같은 방식).
"""
import json
import math
import os
import re
import struct
import sys

# 지도에 담을 범위: 금지구역 데이터가 있는 곳 + 여유 200m
LAT_MIN, LAT_MAX = 37.486, 37.518
LON_MIN, LON_MAX = 127.044, 127.082
ORIGIN = (37.500, 127.060)  # roads_daechi.json 과 같은 기준점

KX = 111_320.0 * math.cos(math.radians(ORIGIN[0]))
KY = 110_540.0

# ---------------------------------------------------------------- 좌표 변환 (Snyder, Map Projections 1987)
A = 6378137.0
F = 1 / 298.257222101
E2 = 2 * F - F * F
EP2 = E2 / (1 - E2)
E1 = (1 - math.sqrt(1 - E2)) / (1 + math.sqrt(1 - E2))


def _m(phi):
    e4, e6 = E2 * E2, E2 * E2 * E2
    return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * phi)
                - (35 * e6 / 3072) * math.sin(6 * phi))


class TM:
    """GRS80 횡축 메르카토르. 기본값은 UTM-K(EPSG:5179)."""

    def __init__(self, lat0=38.0, lon0=127.5, k0=0.9996, fe=1_000_000.0, fn=2_000_000.0):
        self.lat0, self.lon0 = math.radians(lat0), math.radians(lon0)
        self.k0, self.fe, self.fn = k0, fe, fn
        self.m0 = _m(self.lat0)

    @classmethod
    def from_prj(cls, shp_path):
        prj = os.path.splitext(shp_path)[0] + ".prj"
        if not os.path.exists(prj):
            return cls()
        wkt = open(prj, encoding="utf-8", errors="replace").read()

        def p(name, default):
            m = re.search(r'PARAMETER\["%s",\s*([-0-9.eE]+)\]' % name, wkt, re.I)
            return float(m.group(1)) if m else default

        return cls(p("latitude_of_origin", 38.0), p("central_meridian", 127.5),
                   p("scale_factor", 0.9996), p("false_easting", 1_000_000.0), p("false_northing", 2_000_000.0))

    def forward(self, lat, lon):
        phi, lam = math.radians(lat), math.radians(lon)
        n = A / math.sqrt(1 - E2 * math.sin(phi) ** 2)
        t = math.tan(phi) ** 2
        c = EP2 * math.cos(phi) ** 2
        a = (lam - self.lon0) * math.cos(phi)
        x = self.fe + self.k0 * n * (a + (1 - t + c) * a ** 3 / 6
                                     + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a ** 5 / 120)
        y = self.fn + self.k0 * (_m(phi) - self.m0 + n * math.tan(phi) * (
            a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a ** 4 / 24
            + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a ** 6 / 720))
        return x, y

    def inverse(self, x, y):
        m = self.m0 + (y - self.fn) / self.k0
        mu = m / (A * (1 - E2 / 4 - 3 * E2 ** 2 / 64 - 5 * E2 ** 3 / 256))
        phi1 = (mu + (3 * E1 / 2 - 27 * E1 ** 3 / 32) * math.sin(2 * mu)
                + (21 * E1 ** 2 / 16 - 55 * E1 ** 4 / 32) * math.sin(4 * mu)
                + (151 * E1 ** 3 / 96) * math.sin(6 * mu)
                + (1097 * E1 ** 4 / 512) * math.sin(8 * mu))
        c1 = EP2 * math.cos(phi1) ** 2
        t1 = math.tan(phi1) ** 2
        n1 = A / math.sqrt(1 - E2 * math.sin(phi1) ** 2)
        r1 = A * (1 - E2) / (1 - E2 * math.sin(phi1) ** 2) ** 1.5
        d = (x - self.fe) / (n1 * self.k0)
        phi = phi1 - (n1 * math.tan(phi1) / r1) * (
            d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d ** 4 / 24
            + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d ** 6 / 720)
        lam = self.lon0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6
                           + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d ** 5 / 120) / math.cos(phi1)
        return math.degrees(phi), math.degrees(lam)


def to_local(lat, lon):
    return (lon - ORIGIN[1]) * KX, (lat - ORIGIN[0]) * KY


# 잘라 낼 사각형 (기준점 기준 미터)
BX0, BY0 = to_local(LAT_MIN, LON_MIN)
BX1, BY1 = to_local(LAT_MAX, LON_MAX)


# ---------------------------------------------------------------- shp / dbf 읽기
def read_dbf(path):
    enc = "cp949"
    cpg = os.path.splitext(path)[0] + ".cpg"
    if os.path.exists(cpg):
        tag = open(cpg).read().strip().lower()
        if "utf" in tag:
            enc = "utf-8"
    f = open(path, "rb")
    head = f.read(32)
    nrec, hlen, rlen = struct.unpack("<IHH", head[4:12])
    fields, off = [], 1
    while True:
        d = f.read(32)
        if not d or d[0] == 0x0D:
            break
        name = d[:11].split(b"\x00")[0].decode("ascii", "replace")
        fields.append((name, off, d[16]))
        off += d[16]

    def record(i):
        f.seek(hlen + i * rlen)
        rec = f.read(rlen)
        return {n: rec[o:o + l].decode(enc, "replace").strip() for n, o, l in fields}

    return nrec, [n for n, _, _ in fields], record


SHAPE_NAMES = {1: "point", 3: "line", 5: "polygon", 8: "multipoint", 11: "point", 13: "line", 15: "polygon",
               21: "point", 23: "line", 25: "polygon"}


def iter_shapes(path):
    """(순번, 도형종류, 바운딩박스, 파트별 좌표 리스트)"""
    with open(path, "rb") as shp:
        shp.seek(100)
        idx = 0
        while True:
            h = shp.read(8)
            if len(h) < 8:
                break
            _, clen = struct.unpack(">ii", h)
            content = shp.read(clen * 2)
            stype = struct.unpack("<i", content[:4])[0]
            kind = SHAPE_NAMES.get(stype, "null")
            if kind in ("line", "polygon"):
                bbox = struct.unpack("<4d", content[4:36])
                nparts, npts = struct.unpack("<ii", content[36:44])
                yield idx, kind, bbox, (content, nparts, npts)
            elif kind == "point":
                x, y = struct.unpack("<2d", content[4:20])
                yield idx, kind, (x, y, x, y), None
            idx += 1


def parts_of(raw):
    content, nparts, npts = raw
    starts = list(struct.unpack(f"<{nparts}i", content[44:44 + 4 * nparts])) + [npts]
    p0 = 44 + 4 * nparts
    coords = struct.unpack(f"<{2 * npts}d", content[p0:p0 + 16 * npts])
    return [[(coords[2 * k], coords[2 * k + 1]) for k in range(starts[i], starts[i + 1])] for i in range(nparts)]


# ---------------------------------------------------------------- 도형 다듬기
def simplify(pts, tol):
    if len(pts) < 3:
        return pts
    (x0, y0), (x1, y1) = pts[0], pts[-1]
    dx, dy = x1 - x0, y1 - y0
    norm = math.hypot(dx, dy)
    best, idx = 0.0, 0
    for i in range(1, len(pts) - 1):
        if norm < 1e-9:
            d = math.hypot(pts[i][0] - x0, pts[i][1] - y0)
        else:
            d = abs(dy * (pts[i][0] - x0) - dx * (pts[i][1] - y0)) / norm
        if d > best:
            best, idx = d, i
    if best <= tol:
        return [pts[0], pts[-1]]
    return simplify(pts[: idx + 1], tol)[:-1] + simplify(pts[idx:], tol)


def simplify_ring(ring, tol):
    if len(ring) < 4:
        return ring
    # 닫힌 고리는 가장 먼 두 점으로 나눠서 줄인다
    far = max(range(len(ring)), key=lambda i: (ring[i][0] - ring[0][0]) ** 2 + (ring[i][1] - ring[0][1]) ** 2)
    a = simplify(ring[: far + 1], tol)
    b = simplify(ring[far:] + [ring[0]], tol)
    return a[:-1] + b[:-1]


def clip_ring(ring):
    """Sutherland–Hodgman: 사각형 범위로 다각형 고리를 자른다."""

    def clip(pts, inside, cross):
        out = []
        for i in range(len(pts)):
            cur, prev = pts[i], pts[i - 1]
            if inside(cur):
                if not inside(prev):
                    out.append(cross(prev, cur))
                out.append(cur)
            elif inside(prev):
                out.append(cross(prev, cur))
        return out

    def at_x(xc):
        return lambda p, q: (xc, p[1] + (q[1] - p[1]) * (xc - p[0]) / (q[0] - p[0]))

    def at_y(yc):
        return lambda p, q: (p[0] + (q[0] - p[0]) * (yc - p[1]) / (q[1] - p[1]), yc)

    pts = ring
    for inside, cross in ((lambda p: p[0] >= BX0, at_x(BX0)), (lambda p: p[0] <= BX1, at_x(BX1)),
                          (lambda p: p[1] >= BY0, at_y(BY0)), (lambda p: p[1] <= BY1, at_y(BY1))):
        if not pts:
            break
        pts = clip(pts, inside, cross)
    return pts


def area(ring):
    s = 0.0
    for i in range(len(ring)):
        x0, y0 = ring[i - 1]
        x1, y1 = ring[i]
        s += x0 * y1 - x1 * y0
    return abs(s) / 2


def flat(pts):
    out = []
    for x, y in pts:
        out += [round(x, 1), round(y, 1)]
    return out


# ---------------------------------------------------------------- 명령: inspect
def inspect(folder):
    for root, _, files in os.walk(folder):
        for fn in sorted(files):
            if not fn.lower().endswith(".shp"):
                continue
            path = os.path.join(root, fn)
            dbf = os.path.splitext(path)[0] + ".dbf"
            kinds, n = {}, 0
            for _, kind, _, _ in iter_shapes(path):
                kinds[kind] = kinds.get(kind, 0) + 1
                n += 1
            line = f"{os.path.relpath(path, folder)}  records={n} kinds={kinds}"
            if os.path.exists(dbf):
                nrec, names, record = read_dbf(dbf)
                line += f"\n    fields={names}"
                if nrec:
                    line += f"\n    first={record(0)}"
            prj = os.path.splitext(path)[0] + ".prj"
            if os.path.exists(prj):
                line += f"\n    prj={open(prj, errors='replace').read()[:160]}"
            print(line)


# ---------------------------------------------------------------- 명령: build
def parse_layer(arg):
    name, rest = arg.split("=", 1)
    path, filt = rest, None
    if ":" in rest and not os.path.exists(rest):
        path, cond = rest.rsplit(":", 1)
        field, values = cond.split("=", 1)
        filt = (field, set(values.split("|")))
    return name, path, filt


def load_layer(path, filt, want_fields=()):
    tm = TM.from_prj(path)
    gx0, gy0 = tm.forward(LAT_MIN, LON_MIN)
    gx1, gy1 = tm.forward(LAT_MAX, LON_MAX)
    gx0b, _ = tm.forward(LAT_MAX, LON_MIN)
    gx1b, _ = tm.forward(LAT_MIN, LON_MAX)
    x_lo, x_hi = min(gx0, gx0b) - 30, max(gx1, gx1b) + 30
    y_lo, y_hi = gy0 - 30, gy1 + 30
    dbf_path = os.path.splitext(path)[0] + ".dbf"
    record = read_dbf(dbf_path)[2] if os.path.exists(dbf_path) else (lambda i: {})
    for idx, kind, bbox, raw in iter_shapes(path):
        bx0, by0, bx1, by1 = bbox
        if bx1 < x_lo or bx0 > x_hi or by1 < y_lo or by0 > y_hi:
            continue
        attrs = record(idx)
        if filt and attrs.get(filt[0], "") not in filt[1]:
            continue
        if raw is None:
            continue
        parts = []
        for part in parts_of(raw):
            parts.append([to_local(*tm.inverse(x, y)) for x, y in part])
        yield kind, parts, {k: attrs.get(k, "") for k in want_fields}


def build(out, layer_args):
    doc = {
        "source": "행정안전부 도로명주소 도형 (주소기반산업지원서비스, 2026-09)",
        "origin": list(ORIGIN),
        "bbox": [LAT_MIN, LON_MIN, LAT_MAX, LON_MAX],
        "areas": {},   # 이름 → [[고리1 flat], [고리2 flat] ...] 의 목록
        "lines": [],   # [이름, 등급, 폭m, x0, y0, ...] (도로명 표시용)
    }
    for arg in layer_args:
        name, path, filt = parse_layer(arg)
        if name == "roadline":
            n = 0
            for kind, parts, attrs in load_layer(path, filt, ("RN", "ROA_CLS_SE", "ROAD_BT")):
                if kind != "line":
                    continue
                rn = attrs.get("RN", "")
                cls = int(attrs.get("ROA_CLS_SE") or 4) if (attrs.get("ROA_CLS_SE") or "").isdigit() else 4
                try:
                    bt = float(attrs.get("ROAD_BT") or 0)
                except ValueError:
                    bt = 0.0
                for part in parts:
                    pts = simplify(part, 1.0)
                    if len(pts) >= 2:
                        doc["lines"].append([rn, cls, round(bt, 1), *flat(pts)])
                        n += 1
            print(f"{name}: {n} lines")
            continue
        tol = 0.5 if name == "building" else 1.0
        polys = doc["areas"].setdefault(name, [])
        for kind, parts, _ in load_layer(path, filt):
            if kind != "polygon":
                continue
            rings = []
            for part in parts:
                ring = part[:-1] if len(part) > 1 and part[0] == part[-1] else part
                ring = clip_ring(simplify_ring(ring, tol))
                if len(ring) >= 3 and area(ring) >= 4.0:
                    rings.append(flat(ring))
            if rings:
                polys.append(rings)
        print(f"{name}: {len(polys)} polygons")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {out} ({os.path.getsize(out) / 1024:.0f} KB)")


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "inspect":
        inspect(sys.argv[2])
    elif len(sys.argv) >= 4 and sys.argv[1] == "build":
        build(sys.argv[2], sys.argv[3:])
    else:
        print(__doc__)
