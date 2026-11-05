"""서울시 횡단보도 공공데이터 → zones_daechi.json 의 횡단보도(CROSSWALK) 금지구역 (범위는 이미 만든 금지구역 파일을 따른다)

사용법:
  python3 tools/add_crosswalks.py <A004_A.shp> <서울시 자치구 횡단보도 정보.csv> app/src/main/assets/zones_daechi.json

- 모양: 서울 열린데이터광장 '서울시 교통안전시설물 횡단보도 정보'(OA-15554) A004_A (면, EPSG:5186)
  → 횡단보도 테두리에서 10m 안인지 정확히 재기 위해 다각형을 그대로 넣는다.
- 보완: 서울 열린데이터광장 '서울특별시 자치구 횡단보도 정보'(OA-22906, 2026-03-20 기준, 점)
  → 모양 데이터(2021)에 없는 새 횡단보도는 점 + 반경 10m로 넣는다.
- 모양 데이터의 상태코드(STAT_CDE)는 정의서에 값이 나와 있지 않아, 대부분인 001은 그대로 쓰고
  나머지는 2026년 점 데이터에도 횡단보도가 있을 때만 쓴다.
- 도로교통법 제32조: 횡단보도로부터 10미터 이내 정차·주차 금지 (주민신고제 대상)
"""
import csv
import io
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_basemap as bb  # noqa: E402  (shp/dbf 읽기와 좌표 변환을 함께 쓴다)

SHP, CSV_PATH, ZONES = sys.argv[1], sys.argv[2], sys.argv[3]
# 범위: 이미 만든 금지구역 파일(소화전·정류장 등)의 바깥 사각형 + 약 100m
_base = [z for z in json.load(open(ZONES, encoding="utf-8"))["zones"] if z.get("type") not in ("CROSSWALK", "CORNER")]
LAT_MIN, LAT_MAX = min(z["lat"] for z in _base) - 0.001, max(z["lat"] for z in _base) + 0.001
LON_MIN, LON_MAX = min(z["lon"] for z in _base) - 0.001, max(z["lon"] for z in _base) + 0.001
MATCH_M = 15.0  # 점과 모양이 이 거리 안이면 같은 횡단보도로 본다

SRC_SHAPE = "서울시 교통안전시설물 횡단보도 정보(OA-15554, 2021-08)"
SRC_POINT = "서울특별시 자치구 횡단보도 정보(OA-22906, 2026-03-20)"


def local(lat, lon, lat0, lon0):
    return (lon - lon0) * 111_320.0 * math.cos(math.radians(lat0)), (lat - lat0) * 110_540.0


def dist_point_polygon(lat, lon, ring):
    """점에서 다각형(위도·경도 목록)까지 거리(m). 안이면 0"""
    pts = [local(a, b, lat, lon) for a, b in ring]
    inside = False
    n = len(pts)
    best = float("inf")
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % n]
        if (y0 > 0) != (y1 > 0) and 0 < x0 + (x1 - x0) * (0 - y0) / (y1 - y0):
            inside = not inside
        dx, dy = x1 - x0, y1 - y0
        t = max(0.0, min(1.0, -(x0 * dx + y0 * dy) / (dx * dx + dy * dy or 1e-12)))
        best = min(best, math.hypot(x0 + t * dx, y0 + t * dy))
    return 0.0 if inside else best


# ---------------------------------------------------------------- 2026년 점 데이터
raw = open(CSV_PATH, "rb").read()
text = raw.decode("utf-8-sig") if raw[:3] == b"\xef\xbb\xbf" else raw.decode("cp949")
rows = list(csv.reader(io.StringIO(text)))
head = rows[0]
ix = {k: head.index(k) for k in ("횡단보도관리번호", "소재지지번주소", "횡단보도종류", "경도", "위도", "데이터기준일자")}
points = []
for r in rows[1:]:
    try:
        lat, lon = float(r[ix["위도"]]), float(r[ix["경도"]])
    except (ValueError, IndexError):
        continue
    if LAT_MIN <= lat <= LAT_MAX and LON_MIN <= lon <= LON_MAX:
        addr = r[ix["소재지지번주소"]].strip()
        if addr.endswith(" 도"):  # 지목 표시(도로)는 뺀다
            addr = addr[:-2]
        points.append({"id": r[ix["횡단보도관리번호"]], "addr": addr, "lat": lat, "lon": lon})

# ---------------------------------------------------------------- 모양 데이터
tm = bb.TM.from_prj(SHP)
gx0, gy0 = tm.forward(LAT_MIN, LON_MIN)
gx1, gy1 = tm.forward(LAT_MAX, LON_MAX)
_, _, record = bb.read_dbf(os.path.splitext(SHP)[0] + ".dbf")
shapes = []
for idx, kind, (bx0, by0, bx1, by1), raw_shape in bb.iter_shapes(SHP):
    if kind != "polygon" or bx1 < gx0 or bx0 > gx1 or by1 < gy0 or by0 > gy1:
        continue
    attrs = record(idx)
    outer = bb.parts_of(raw_shape)[0]
    ring = [tm.inverse(x, y) for x, y in outer]
    if len(ring) > 1 and ring[0] == ring[-1]:
        ring = ring[:-1]
    lat0 = sum(p[0] for p in ring) / len(ring)
    lon0 = sum(p[1] for p in ring) / len(ring)
    # 0.3m보다 작은 굴곡은 줄인다
    loc = [local(a, b, lat0, lon0) for a, b in ring]
    simp = bb.simplify_ring(loc, 0.3)
    keep = [ring[loc.index(p)] for p in simp]
    if len(keep) < 3:
        continue
    shapes.append({"id": attrs.get("MGRNU", str(idx)), "stat": attrs.get("STAT_CDE", ""), "ring": keep, "lat": lat0, "lon": lon0})

# ---------------------------------------------------------------- 맞춰 보기
# 넓은 범위에서도 빠르게 찾도록 점을 칸(약 66m × 70m)으로 나눠 둔다
GLAT, GLON = 0.0006, 0.0008


def cell(lat, lon):
    return int(lat // GLAT), int(lon // GLON)


def around(grid, lat, lon):
    r, c = cell(lat, lon)
    for dr in (-1, 0, 1):
        for dc in (-1, 0, 1):
            yield from grid.get((r + dr, c + dc), ())


point_grid = {}
for _p in points:
    point_grid.setdefault(cell(_p["lat"], _p["lon"]), []).append(_p)


def nearest_point(shape):
    best, hit = float("inf"), None
    for p in around(point_grid, shape["lat"], shape["lon"]):
        if abs(p["lat"] - shape["lat"]) > 0.0006 or abs(p["lon"] - shape["lon"]) > 0.0008:
            continue
        d = dist_point_polygon(p["lat"], p["lon"], shape["ring"])
        if d < best:
            best, hit = d, p
    return best, hit


zones, used_points, skipped = [], set(), 0
for s in shapes:
    d, p = nearest_point(s)
    if s["stat"] != "001" and (p is None or d > MATCH_M):
        skipped += 1
        continue
    if p is not None and d <= MATCH_M:
        used_points.add(p["id"])
    name = f"횡단보도 · {p['addr']}" if p is not None and d <= MATCH_M and p["addr"] else "횡단보도"
    flat = []
    for a, b in s["ring"]:
        flat += [round(a, 6), round(b, 6)]
    zones.append({"id": f"c-{s['id']}", "type": "CROSSWALK", "name": name,
                  "lat": round(s["lat"], 6), "lon": round(s["lon"], 6), "shape": flat, "source": SRC_SHAPE})

shape_grid = {}
for _s in shapes:
    shape_grid.setdefault(cell(_s["lat"], _s["lon"]), []).append(_s)

added_points = 0
for p in points:
    if p["id"] in used_points:
        continue
    near = any(dist_point_polygon(p["lat"], p["lon"], s["ring"]) <= MATCH_M
               for s in around(shape_grid, p["lat"], p["lon"]) if abs(p["lat"] - s["lat"]) < 0.0006 and abs(p["lon"] - s["lon"]) < 0.0008)
    if near:
        continue
    zones.append({"id": f"c-p-{p['id']}", "type": "CROSSWALK", "name": f"횡단보도 · {p['addr']}" if p["addr"] else "횡단보도",
                  "lat": round(p["lat"], 6), "lon": round(p["lon"], 6), "source": SRC_POINT})
    added_points += 1

# ---------------------------------------------------------------- 기존 파일에 반영
doc = json.load(open(ZONES, encoding="utf-8"))
doc["zones"] = [z for z in doc["zones"] if z.get("type") != "CROSSWALK"] + zones
meta = doc.setdefault("meta", {})
src = [s for s in meta.get("sources", []) if "횡단보도" not in s]
src += [f"서울 열린데이터광장 · {SRC_SHAPE} · 공공누리 1유형",
        f"서울 열린데이터광장 · {SRC_POINT} · 이용허락범위 제한 없음"]
meta["sources"] = src
counts = meta.setdefault("counts", {})
counts["CROSSWALK"] = len(zones)
meta["note"] = ("공식 공공데이터에서 서울 강남구와 경계 주변만 골라 만든 파일. 어린이보호구역은 시설 위치 반경 100m로 추정. "
                "횡단보도는 모양(면) 테두리에서 10m로 판단.")
# 시연 지점: 학원가 버스정류장에서 가장 가까운 횡단보도
demo = [d for d in doc.get("demo", []) if d.get("label") != "학원가 횡단보도"]
base = next((d for d in demo if d.get("label") == "학원가 버스정류장"), None)
if base is not None and zones:
    shaped = [z for z in zones if "shape" in z]
    near = min(shaped or zones, key=lambda z: (z["lat"] - base["lat"]) ** 2 + ((z["lon"] - base["lon"]) * 0.79) ** 2)
    demo.insert(2, {"label": "학원가 횡단보도", "lat": near["lat"], "lon": near["lon"], "ref": near["name"]})
doc["demo"] = demo
with open(ZONES, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))

print(f"points_in_bbox={len(points)} shapes_in_bbox={len(shapes)} shape_zones={len(zones) - added_points} "
      f"skipped_status={skipped} point_only_added={added_points} total_crosswalk={len(zones)}")
print(f"size={os.path.getsize(ZONES) // 1024}KB")
