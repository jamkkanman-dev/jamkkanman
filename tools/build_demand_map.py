"""
지자체용 '정차 수요 지도' 시제품 데이터를 만든다 (dashboard/demand.js).

앱에 이미 들어 있는 공공데이터(app/src/main/assets)만 쓴다. 외부 라이브러리 없음.
- 단속 기록 25m 칸 (서울시 불법주정차 단속 정보, 2025년 10~12월)  → 100m 칸으로 다시 묶음
- 공영주차장, 어린이보호구역, 버스정류장·교차로 이름 (금지구역 데이터)
- 지도 바탕: 국토교통부 표준노드링크 도로 (roads_daechi.json)

'안심 승하차 구역 후보지' = 강남구 안에서 단속이 많이 반복되는데(=잠깐 세우려는 수요가 있는데)
200m 안에 공영주차장이 없는(=합법적으로 세울 곳이 없는) 100m 칸. 단속 건수 순으로 10곳.

실제 서비스에서는 이 자리에 잠깐만 사용자가 동의한 정차 기록(약 50m 칸·시간대·요일)이 더해진다.

사용: python3 tools/build_demand_map.py
"""
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ROOT, "dashboard", "demand.js")

CELL_M = 100.0
NO_PARKING_NEAR_M = 200.0
SCHOOL_NEAR_M = 300.0
TOP_N = 10
GANGNAM_DONG = ("신사동", "논현동", "압구정동", "청담동", "삼성동", "대치동", "역삼동", "도곡동",
                "개포동", "세곡동", "일원동", "수서동", "자곡동", "율현동")


def main():
    zones_doc = json.load(open(os.path.join(ASSETS, "zones_daechi.json"), encoding="utf-8"))
    roads_doc = json.load(open(os.path.join(ASSETS, "roads_daechi.json"), encoding="utf-8"))
    lat0, lon0 = roads_doc["origin"]
    kx = 111_320.0 * math.cos(math.radians(lat0))
    ky = 110_540.0

    def xy(lat, lon):
        return (lon - lon0) * kx, (lat - lat0) * ky

    cov = zones_doc["coverage"]
    cov_kx = 111_320.0 * math.cos(math.radians(cov["lat0"]))
    cov_cells = set(cov["cells"])

    def covered(lat, lon):
        col = math.floor((lon - cov["lon0"]) * cov_kx / cov["cellM"])
        row = math.floor((lat - cov["lat0"]) * 110_540.0 / cov["cellM"])
        return 0 <= col < cov["cols"] and row >= 0 and (row * cov["cols"] + col) in cov_cells

    # 강남구 경계: 소방용수시설 주소(강남구는 '역삼동 …', 다른 구는 '서초구 …')로 가장 가까운 소화전의 동을 쓴다
    hydrants = []
    for z in zones_doc["zones"]:
        if z["type"] != "HYDRANT":
            continue
        addr = z["name"].split("·")[-1].strip()
        dong = addr.split()[0] if addr else ""
        hydrants.append((*xy(z["lat"], z["lon"]), dong if dong in GANGNAM_DONG else ""))
    buckets = {}
    for h in hydrants:
        buckets.setdefault((math.floor(h[0] / 200), math.floor(h[1] / 200)), []).append(h)

    def dong_at(x, y):
        bx, by = math.floor(x / 200), math.floor(y / 200)
        best, bd = None, float("inf")
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for h in buckets.get((bx + dx, by + dy), ()):
                    d = (h[0] - x) ** 2 + (h[1] - y) ** 2
                    if d < bd:
                        best, bd = h, d
        return best[2] if best else ""

    enf = zones_doc["enforcement"]
    cells = {}
    total_in = 0
    for lat, lon, n in enf["cells"]:
        if not covered(lat, lon):
            continue
        x, y = xy(lat, lon)
        if not dong_at(x, y):
            continue
        total_in += n
        key = (math.floor(x / CELL_M), math.floor(y / CELL_M))
        c = cells.setdefault(key, {"n": 0, "sx": 0.0, "sy": 0.0})
        c["n"] += n
        c["sx"] += x * n
        c["sy"] += y * n

    parking = [(s["name"], *xy(s["lat"], s["lon"]), s.get("note", "")) for s in zones_doc["spots"]]
    schools = [(z["name"], *xy(z["lat"], z["lon"]), z.get("radius", 100.0)) for z in zones_doc["zones"] if z["type"] == "SCHOOL_ZONE"]
    named = [(z["type"], z["name"], *xy(z["lat"], z["lon"])) for z in zones_doc["zones"] if z["type"] in ("BUS_STOP", "CORNER")]
    zone_counts = {}
    for z in zones_doc["zones"]:
        if z["type"] == "SCHOOL_ZONE":
            continue
        x, y = xy(z["lat"], z["lon"])
        key = (math.floor(x / CELL_M), math.floor(y / CELL_M))
        zone_counts[key] = zone_counts.get(key, 0) + 1

    def nearest(items, x, y, ix=1):
        best, bd = None, float("inf")
        for it in items:
            d = math.hypot(it[ix] - x, it[ix + 1] - y)
            if d < bd:
                best, bd = it, d
        return best, bd

    def place_name(x, y):
        # 가까운 버스정류장 이름을 먼저, 없으면 교차로(도로 이름 두 개)
        bus = [n for n in named if n[0] == "BUS_STOP"]
        b, bd = nearest(bus, x, y, ix=2)
        if b is not None and bd <= 120:
            return b[1].replace(" 정류장", "") + " 정류장 부근"
        corners = [n for n in named if n[0] == "CORNER"]
        c, cd = nearest(corners, x, y, ix=2)
        if c is None:
            return "골목"
        roads = " · ".join([p.strip() for p in c[1].split("·")[1:]][:2]) or "교차로"
        if cd <= 150:
            return f"{roads} 교차로 부근"
        return f"골목 ({roads} 교차로에서 {round(cd / 10) * 10}m)"

    grid = []
    for (cx, cy), c in cells.items():
        x, y = c["sx"] / c["n"], c["sy"] / c["n"]
        grid.append([cx, cy, c["n"], round(x), round(y)])
    grid.sort(key=lambda g: -g[2])

    candidates = []
    for cx, cy, n, x, y in grid:
        p, pd = nearest(parking, x, y)
        if pd < NO_PARKING_NEAR_M:
            continue
        s, sd = nearest(schools, x, y)
        candidates.append({
            "rank": len(candidates) + 1,
            "x": x, "y": y,
            "count": n,
            "dong": dong_at(x, y),
            "name": place_name(x, y),
            "parking": p[0] if p else "",
            "parkingM": round(pd),
            "school": s[0] if s and sd <= SCHOOL_NEAR_M else "",
            "zones": zone_counts.get((cx, cy), 0),
        })
        if len(candidates) >= TOP_N:
            break

    counts = sorted((g[2] for g in grid), reverse=True)
    top10_share = sum(counts[: max(1, len(counts) // 10)]) / max(1, total_in)

    # 지도 바탕: 강남구 확인 범위 근처 도로만, 1m 단위로 줄여 넣는다
    xs = [g[3] for g in grid]
    ys = [g[4] for g in grid]
    bx0, bx1, by0, by1 = min(xs) - 400, max(xs) + 400, min(ys) - 400, max(ys) + 400
    roads = []
    for r in roads_doc["roads"]:
        rank, lanes, name, *pts = r
        coords = [round(v) for v in pts]
        if not any(bx0 <= coords[i] <= bx1 and by0 <= coords[i + 1] <= by1 for i in range(0, len(coords), 2)):
            continue
        roads.append([rank, lanes, *coords])

    out = {
        "region": zones_doc["meta"]["region"],
        "period": enf["label"],
        "source": enf.get("source", ""),
        "cellM": CELL_M,
        "totalRecords": total_in,
        "cellCount": len(grid),
        "top10Share": round(top10_share, 3),
        "noParkingNearM": NO_PARKING_NEAR_M,
        "bbox": [bx0, by0, bx1, by1],
        "grid": [[g[0], g[1], g[2]] for g in grid],
        "parking": [[name, round(x), round(y), note] for name, x, y, note in parking if bx0 <= x <= bx1 and by0 <= y <= by1],
        "schools": [[name, round(x), round(y), r] for name, x, y, r in schools],
        "candidates": candidates,
        "roads": roads,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("// tools/build_demand_map.py 로 만든 파일 (직접 고치지 않음)\n")
        f.write("window.DEMAND = ")
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
        f.write(";\n")

    print(f"{out['region']} · {out['period']} · 단속 {total_in:,}건 · 100m 칸 {len(grid):,}개 · 상위 10% 칸 비중 {top10_share:.1%}")
    print(f"도로 {len(roads):,}개 · 공영주차장 {len(out['parking'])}곳 · 어린이보호구역 {len(schools)}곳 · 파일 {os.path.getsize(OUT) / 1024:.0f}KB")
    for c in candidates:
        print(f"{c['rank']:2d}. {c['dong']} {c['name']}: 단속 {c['count']:,}건 · 공영주차장 {c['parkingM']}m ({c['parking']}) · 금지구역 {c['zones']}곳"
              + (f" · 어린이보호구역 {c['school']}" if c["school"] else ""))


if __name__ == "__main__":
    sys.exit(main())
