"""서울시 단속 CCTV 위치 + 실제 단속 기록 → zones_daechi.json 의 cctv / enforcement

사용법:
  python3 tools/add_enforcement.py "<서울시 강남구 불법주정차 위반 단속 CCTV 위치정보.csv>" \
      "<불법주정차단속 위치정보(25년10월1일_25년12월31일).csv>" app/src/main/assets/zones_daechi.json

- 단속 CCTV: 서울 열린데이터광장 '서울시 강남구 불법주정차 위반 단속 CCTV 위치정보'(OA-20494, 공공누리 1유형)
  → 고정형 CCTV 위치. 앱은 50m 안에 있으면 경고에 '단속 카메라도 있는 곳'을 덧붙인다.
- 단속 기록: 서울 열린데이터광장 '서울시 불법주정차 단속 정보'(OA-22190, 공공누리 1유형, 단속일시·주소·위도·경도)
  → 대치동 주변 기록을 25m 칸으로 모아 건수만 넣는다 (개별 기록·주소는 넣지 않음).
  앱은 반경 50m 안 건수를 보여 주고, 금지구역이 아니어도 단속이 잦으면 노란 선을 확인하라고 알린다.
"""
import collections
import csv
import io
import json
import math
import os
import sys

CCTV_CSV, ENF_CSV, ZONES = sys.argv[1], sys.argv[2], sys.argv[3]
CELL_M = 25.0
MARGIN_DEG = 0.0035  # 앱의 확인 범위(금지구역 바깥 사각형 + 약 300m)보다 조금 넉넉히

SRC_CCTV = "서울시 강남구 불법주정차 위반 단속 CCTV 위치정보(OA-20494)"
SRC_ENF = "서울시 불법주정차 단속 정보(OA-22190)"


def read_csv(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "cp949"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    rows = list(csv.reader(io.StringIO(text)))
    return rows[0], rows[1:]


doc = json.load(open(ZONES, encoding="utf-8"))
zs = doc["zones"]
lat_lo, lat_hi = min(z["lat"] for z in zs) - MARGIN_DEG, max(z["lat"] for z in zs) + MARGIN_DEG
lon_lo, lon_hi = min(z["lon"] for z in zs) - MARGIN_DEG, max(z["lon"] for z in zs) + MARGIN_DEG


def inside(lat, lon):
    return lat_lo <= lat <= lat_hi and lon_lo <= lon <= lon_hi


# ---------------------------------------------------------------- 단속 CCTV
hdr, rows = read_csv(CCTV_CSV)
i_addr, i_lat, i_lon, i_name = hdr.index("고정형CCTV지번주소"), hdr.index("위도"), hdr.index("경도"), hdr.index("단속지점명")
cctv, seen = [], set()
for r in rows:
    try:
        lat, lon = float(r[i_lat]), float(r[i_lon])
    except (ValueError, IndexError):
        continue
    if not inside(lat, lon):
        continue
    key = (round(lat, 5), round(lon, 5))
    if key in seen:
        continue
    seen.add(key)
    name = r[i_name].strip()
    if name.startswith("[") and "]" in name:  # "[대치1-101] 대치상가 주변" → "대치상가 주변"
        name = name[name.index("]") + 1:].strip()
    cctv.append({"name": name or "단속 CCTV", "addr": r[i_addr].strip(), "lat": key[0], "lon": key[1]})

# ---------------------------------------------------------------- 실제 단속 기록 → 25m 칸
hdr, rows = read_csv(ENF_CSV)
i_day, i_time, i_old = hdr.index("단속일"), hdr.index("단속시간"), hdr.index("구주소")
i_lat, i_lon = hdr.index("위도"), hdr.index("경도")
kx = 111_320.0 * math.cos(math.radians((lat_lo + lat_hi) / 2))
ky = 110_540.0
grid = collections.Counter()
days, hours_daechi, n_daechi, n_seoul = [], collections.Counter(), 0, 0
for r in rows:
    n_seoul += 1
    try:
        lat, lon = float(r[i_lat]), float(r[i_lon])
    except (ValueError, IndexError):
        continue
    if "대치동" in r[i_old]:
        n_daechi += 1
        hours_daechi[int(r[i_time][:2] or 0)] += 1
    if not inside(lat, lon):
        continue
    days.append(r[i_day])
    grid[(int((lon - lon_lo) * kx // CELL_M), int((lat - lat_lo) * ky // CELL_M))] += 1

d0, d1 = min(days), max(days)
label = f"{d0[:4]}년 {int(d0[4:6])}~{int(d1[4:6])}월" if d0[:4] == d1[:4] else f"{d0[:4]}.{d0[4:6]}~{d1[:4]}.{d1[4:6]}"
cells = []
for (ix, iy), n in sorted(grid.items()):
    lon = lon_lo + (ix + 0.5) * CELL_M / kx
    lat = lat_lo + (iy + 0.5) * CELL_M / ky
    cells.append([round(lat, 5), round(lon, 5), n])

# ---------------------------------------------------------------- 시연 지점: 금지구역은 아니지만 단속이 잦은 곳
def dist_m(a_lat, a_lon, b_lat, b_lon):
    return math.hypot((b_lon - a_lon) * 111_320.0 * math.cos(math.radians(a_lat)), (b_lat - a_lat) * ky)


def dist_zone(z, lat, lon):
    """구역까지 거리(m): 모양이 있으면 테두리까지(안이면 0), 없으면 중심점까지 (앱의 Zone.distanceTo와 같은 계산)"""
    s = z.get("shape")
    if not s or len(s) < 6:
        return dist_m(lat, lon, z["lat"], z["lon"])
    kxl = 111_320.0 * math.cos(math.radians(lat))
    pts = [((s[q + 1] - lon) * kxl, (s[q] - lat) * ky) for q in range(0, len(s), 2)]
    inside_poly, best = False, float("inf")
    for q in range(len(pts)):
        (x0, y0), (x1, y1) = pts[q], pts[(q + 1) % len(pts)]
        if (y0 > 0) != (y1 > 0) and x0 + (x1 - x0) * (0 - y0) / (y1 - y0) > 0:
            inside_poly = not inside_poly
        dx, dy = x1 - x0, y1 - y0
        l2 = dx * dx + dy * dy
        t = 0.0 if l2 < 1e-12 else max(0.0, min(1.0, -(x0 * dx + y0 * dy) / l2))
        best = min(best, math.hypot(x0 + t * dx, y0 + t * dy))
    return 0.0 if inside_poly else best


RADIUS = {"HYDRANT": 5, "CORNER": 5, "BUS_STOP": 10, "CROSSWALK": 10, "SCHOOL_ZONE": 100}

# 차가 설 수 있는 자리여야 하므로 도로(표준노드링크) 가장자리 근처인 점만 고른다
road_doc = json.load(open(os.path.join(os.path.dirname(ZONES), "roads_daechi.json"), encoding="utf-8"))
o_lat, o_lon = road_doc["origin"]
rkx, rky = 111_320.0 * math.cos(math.radians(o_lat)), 110_540.0
road_segs = []
for rd in road_doc["roads"]:
    lanes, xy = rd[1], rd[3:]
    for q in range(0, len(xy) - 2, 2):
        road_segs.append((xy[q], xy[q + 1], xy[q + 2], xy[q + 3], lanes * 3.0))


def on_road_edge(lat, lon):
    """도로 중심선에서 (차로 폭 + 2m) 안: 차도 가장자리쯤"""
    px, py = (lon - o_lon) * rkx, (lat - o_lat) * rky
    for x0, y0, x1, y1, w in road_segs:
        if min(x0, x1) - 40 > px or max(x0, x1) + 40 < px or min(y0, y1) - 40 > py or max(y0, y1) + 40 < py:
            continue
        dx, dy = x1 - x0, y1 - y0
        l2 = dx * dx + dy * dy
        t = 0.0 if l2 < 1e-9 else max(0.0, min(1.0, ((px - x0) * dx + (py - y0) * dy) / l2))
        if math.hypot(px - x0 - t * dx, py - y0 - t * dy) <= w + 2.0:
            return True
    return False
BUSY_COUNT = 30  # 앱 ZoneChecker.BUSY_COUNT와 같게
demo = [p for p in doc.get("demo", []) if p.get("label") != "단속 잦은 곳"]
base = next((p for p in demo if p.get("label") == "학원가 버스정류장"), None)
if base is not None:
    best = None
    for lat, lon, _ in cells:
        if dist_m(base["lat"], base["lon"], lat, lon) > 700:
            continue
        recent = sum(c[2] for c in cells if abs(c[0] - lat) < 0.0008 and abs(c[1] - lon) < 0.001
                     and dist_m(lat, lon, c[0], c[1]) <= 50)
        if recent < BUSY_COUNT or (best is not None and recent <= best[0]) or not on_road_edge(lat, lon):
            continue
        near = [z for z in zs if abs(z["lat"] - lat) < 0.0015 and abs(z["lon"] - lon) < 0.002]
        if any(dist_zone(z, lat, lon) <= z.get("radius", RADIUS.get(z["type"], 10)) + 3 for z in near):
            continue
        best = (recent, lat, lon)
    if best is not None:
        pos = next((q + 1 for q, p in enumerate(demo) if p.get("label") == "교차로 모퉁이"), len(demo))
        demo.insert(pos, {"label": "단속 잦은 곳", "lat": best[1], "lon": best[2], "ref": f"{label} 반경 50m 단속 {best[0]}건"})
        print("demo:", demo[pos])
doc["demo"] = demo

# ---------------------------------------------------------------- 기존 파일에 반영
doc["cctv"] = cctv
doc["enforcement"] = {"label": label, "from": d0, "to": d1, "cellM": CELL_M, "source": SRC_ENF, "cells": cells}
meta = doc.setdefault("meta", {})
meta["sources"] = [s for s in meta.get("sources", []) if "단속" not in s] + [
    f"서울 열린데이터광장 · {SRC_CCTV} · 공공누리 1유형",
    f"서울 열린데이터광장 · {SRC_ENF}, {d0}~{d1} · 공공누리 1유형 · 25m 칸별 건수만 사용",
]
counts = meta.setdefault("counts", {})
counts["CCTV"] = len(cctv)
counts["ENFORCEMENT_RECORDS"] = sum(grid.values())
with open(ZONES, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))

print(f"cctv_in_area={len(cctv)} enforcement_in_area={sum(grid.values())} cells={len(cells)} period={label}")
print(f"seoul_records={n_seoul} daechi_address_records={n_daechi}")
print("daechi_by_hour:", ", ".join(f"{h}시 {hours_daechi[h]}" for h in range(24)))
print(f"size={os.path.getsize(ZONES) // 1024}KB")
