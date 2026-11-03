"""국토교통부 전국 표준노드링크(MOCT_LINK) → 앱 지도용 roads_daechi.json

사용법: python3 tools/build_roads.py <MOCT_LINK.shp 가 있는 폴더> app/src/main/assets/roads_daechi.json

- 입력: 국가교통정보센터(ITS) 전국표준노드링크 [2026-10-14]NODELINKDATA.zip 의 MOCT_LINK.shp/.dbf
- 좌표계: GRS80 횡축 메르카토르 (중부원점 38N 127E, 가산 200000/600000) → 위도·경도 → 기준점 기준 미터
- 외부 라이브러리 없이 동작하도록 shapefile·dbf 읽기와 좌표 변환을 직접 구현했다.
"""
import json
import math
import struct
import sys

SRC = sys.argv[1]
OUT = sys.argv[2]

# 지도에 담을 범위 (대치동과 그 주변)
LAT_MIN, LAT_MAX = 37.484, 37.520
LON_MIN, LON_MAX = 127.040, 127.088
ORIGIN = (37.500, 127.060)

# ---------------------------------------------------------------- 좌표 변환 (Snyder, Map Projections 1987)
A = 6378137.0
F = 1 / 298.257222101
E2 = 2 * F - F * F
EP2 = E2 / (1 - E2)
LAT0, LON0, K0, FE, FN = math.radians(38.0), math.radians(127.0), 1.0, 200000.0, 600000.0


def _m(phi):
    e4, e6 = E2 * E2, E2 * E2 * E2
    return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * math.sin(4 * phi)
                - (35 * e6 / 3072) * math.sin(6 * phi))


M0 = _m(LAT0)


def forward(lat, lon):
    phi, lam = math.radians(lat), math.radians(lon)
    n = A / math.sqrt(1 - E2 * math.sin(phi) ** 2)
    t = math.tan(phi) ** 2
    c = EP2 * math.cos(phi) ** 2
    a = (lam - LON0) * math.cos(phi)
    x = FE + K0 * n * (a + (1 - t + c) * a ** 3 / 6 + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a ** 5 / 120)
    y = FN + K0 * (_m(phi) - M0 + n * math.tan(phi) * (a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a ** 4 / 24
                                                     + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a ** 6 / 720))
    return x, y


E1 = (1 - math.sqrt(1 - E2)) / (1 + math.sqrt(1 - E2))


def inverse(x, y):
    m = M0 + (y - FN) / K0
    mu = m / (A * (1 - E2 / 4 - 3 * E2 ** 2 / 64 - 5 * E2 ** 3 / 256))
    phi1 = (mu + (3 * E1 / 2 - 27 * E1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * E1 ** 2 / 16 - 55 * E1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * E1 ** 3 / 96) * math.sin(6 * mu)
            + (1097 * E1 ** 4 / 512) * math.sin(8 * mu))
    c1 = EP2 * math.cos(phi1) ** 2
    t1 = math.tan(phi1) ** 2
    n1 = A / math.sqrt(1 - E2 * math.sin(phi1) ** 2)
    r1 = A * (1 - E2) / (1 - E2 * math.sin(phi1) ** 2) ** 1.5
    d = (x - FE) / (n1 * K0)
    phi = phi1 - (n1 * math.tan(phi1) / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d ** 4 / 24
                                               + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d ** 6 / 720)
    lam = LON0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6
                  + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d ** 5 / 120) / math.cos(phi1)
    return math.degrees(phi), math.degrees(lam)


KX = 111_320.0 * math.cos(math.radians(ORIGIN[0]))
KY = 110_540.0


def to_local(lat, lon):
    return (lon - ORIGIN[1]) * KX, (lat - ORIGIN[0]) * KY


# ---------------------------------------------------------------- 단순화 (Douglas–Peucker, 1m)
def simplify(pts, tol=1.0):
    if len(pts) < 3:
        return pts
    (x0, y0), (x1, y1) = pts[0], pts[-1]
    dx, dy = x1 - x0, y1 - y0
    norm = math.hypot(dx, dy) or 1e-9
    best, idx = 0.0, 0
    for i in range(1, len(pts) - 1):
        d = abs(dy * (pts[i][0] - x0) - dx * (pts[i][1] - y0)) / norm
        if d > best:
            best, idx = d, i
    if best <= tol:
        return [pts[0], pts[-1]]
    return simplify(pts[: idx + 1], tol)[:-1] + simplify(pts[idx:], tol)


# ---------------------------------------------------------------- dbf
def open_dbf(path):
    f = open(path, "rb")
    head = f.read(32)
    nrec, hlen, rlen = struct.unpack("<IHH", head[4:12])
    fields, off = [], 1
    while True:
        d = f.read(32)
        if d[0] == 0x0D:
            break
        name = d[:11].split(b"\x00")[0].decode("ascii", "replace")
        flen = d[16]
        fields.append((name, off, flen))
        off += flen
    return f, nrec, hlen, rlen, {n: (o, l) for n, o, l in fields}


def read_dbf(dbf, idx, names):
    f, _, hlen, rlen, fmap = dbf
    f.seek(hlen + idx * rlen)
    rec = f.read(rlen)
    out = {}
    for n in names:
        o, l = fmap[n]
        out[n] = rec[o:o + l].decode("cp949", "replace").strip()
    return out


# ---------------------------------------------------------------- 실행
x_min, y_min = forward(LAT_MIN, LON_MIN)
x_max, y_max = forward(LAT_MAX, LON_MAX)
x_lo, x_hi = min(x_min, forward(LAT_MAX, LON_MIN)[0]) - 50, max(x_max, forward(LAT_MIN, LON_MAX)[0]) + 50
y_lo, y_hi = y_min - 50, y_max + 50

dbf = open_dbf(f"{SRC}/MOCT_LINK.dbf")
want = [n for n in ("ROAD_RANK", "LANES", "ROAD_NAME", "ROAD_USE") if n in dbf[4]]
roads = []
with open(f"{SRC}/MOCT_LINK.shp", "rb") as shp:
    shp.seek(100)
    idx = 0
    while True:
        h = shp.read(8)
        if len(h) < 8:
            break
        _, clen = struct.unpack(">ii", h)
        content = shp.read(clen * 2)
        stype = struct.unpack("<i", content[:4])[0]
        if stype in (3, 13, 23):
            bx0, by0, bx1, by1 = struct.unpack("<4d", content[4:36])
            if not (bx1 < x_lo or bx0 > x_hi or by1 < y_lo or by0 > y_hi):
                nparts, npts = struct.unpack("<ii", content[36:44])
                p0 = 44 + 4 * nparts
                coords = struct.unpack(f"<{2 * npts}d", content[p0:p0 + 16 * npts])
                pts = []
                for k in range(npts):
                    lat, lon = inverse(coords[2 * k], coords[2 * k + 1])
                    pts.append(to_local(lat, lon))
                pts = simplify(pts)
                attrs = read_dbf(dbf, idx, want)
                rank = int(attrs.get("ROAD_RANK") or 107)
                lanes = int(float(attrs.get("LANES") or 1) or 1)
                flat = []
                for x, y in pts:
                    flat += [round(x, 1), round(y, 1)]
                roads.append([rank, lanes, attrs.get("ROAD_NAME", ""), *flat])
        idx += 1

doc = {
    "source": "국토교통부 국가교통정보센터 전국표준노드링크 (2026-10-14)",
    "origin": list(ORIGIN),
    "bbox": [LAT_MIN, LON_MIN, LAT_MAX, LON_MAX],
    "roads": roads,
}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
names = sorted({r[2] for r in roads if r[2]})
print(f"links={len(roads)} records_scanned={idx} names={len(names)}")
print("fields:", list(dbf[4].keys()))
print("sample names:", names[:25])
