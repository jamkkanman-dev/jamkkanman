"""서울 강남구 공공데이터 → 앱용 zones_daechi.json (파일 이름은 처음 시연 지역 이름을 그대로 쓴다)

사용법: python3 tools/build_zones.py <내려받은 파일 폴더> app/src/main/assets/zones_daechi.json

- 범위: 강남구 소방용수시설이 있는 곳의 바깥 사각형 + 약 400m. 경계 근처 옆 구의 시설도 함께 넣는다.
- 확인 범위(coverage): 강남구 소방용수시설 반경 300m 안의 100m 칸. 소화전은 거의 모든 길에 있어
  강남구 모양을 따라가므로, 옆 구로 넘어가면 앱이 '확인 범위 밖'으로 안내한다.

입력(공식 다운로드 파일):
  서울시 소방재난본부_소방용수시설_20251231.csv   (서울 열린데이터광장 OA-21306)
  서울시버스정류소위치정보(20260902).xlsx          (서울 열린데이터광장 OA-15067)
  서울시 공영주차장 안내 정보.csv                  (서울 열린데이터광장 OA-13122)
  전국어린이보호구역표준데이터.csv                  (공공데이터포털 15012891)
  전국주차장정보표준데이터.csv                      (공공데이터포털 15012896)
"""
import hashlib
import json
import math
import sys

import pandas as pd

DATA = sys.argv[1]
OUT = sys.argv[2]

REGION = "강남구"
MARGIN_DEG = 0.004   # 약 400m
COVER_CELL_M = 100.0
COVER_RADIUS_M = 300.0

# 범위는 강남구 소방용수시설 위치로 정한다 (아래에서 채움)
LAT = (0.0, 0.0)
LON = (0.0, 0.0)


def inside(lat, lon):
    try:
        lat, lon = float(lat), float(lon)
    except (TypeError, ValueError):
        return False
    return LAT[0] <= lat <= LAT[1] and LON[0] <= lon <= LON[1]


def dist(a, b, c, d):
    p1, p2 = math.radians(a), math.radians(c)
    dp, dl = p2 - p1, math.radians(d - b)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def short_addr(s):
    s = str(s or "").replace("서울특별시 ", "").replace("강남구 ", "").strip()
    return " ".join(s.split())


zones, spots = [], []

# 1) 소방용수시설 (소화전 등) 5m
h = pd.read_csv(f"{DATA}/서울시 소방재난본부_소방용수시설_20251231.csv", encoding="cp949", encoding_errors="replace", dtype=str)
h["lat"] = pd.to_numeric(h["위도"], errors="coerce")
h["lon"] = pd.to_numeric(h["경도"], errors="coerce")
h = h.dropna(subset=["lat", "lon"])
home = h[h["시군구명"] == REGION]
# 좌표를 잘못 적은 행(예: 경도 137도)은 범위 계산에서 뺀다: 강남구 중심에서 10km 안만
_clat, _clon = home["lat"].median(), home["lon"].median()
home = home[(abs(home["lat"] - _clat) < 0.09) & (abs(home["lon"] - _clon) < 0.11)]
LAT = (home["lat"].min() - MARGIN_DEG, home["lat"].max() + MARGIN_DEG)
LON = (home["lon"].min() - MARGIN_DEG, home["lon"].max() + MARGIN_DEG)

# 확인 범위: 강남구 소방용수시설 반경 300m 안의 100m 칸
cov_lat0, cov_lon0 = LAT[0], LON[0]
cov_kx = 111_320.0 * math.cos(math.radians(cov_lat0))
cov_ky = 110_540.0
cov_cols = int((LON[1] - LON[0]) * cov_kx // COVER_CELL_M) + 1
cov_cells = set()
reach = int(COVER_RADIUS_M // COVER_CELL_M) + 1
for la, lo in zip(home["lat"], home["lon"]):
    cx, cy = (lo - cov_lon0) * cov_kx, (la - cov_lat0) * cov_ky
    c0, r0 = int(cx // COVER_CELL_M), int(cy // COVER_CELL_M)
    for dr in range(-reach, reach + 1):
        for dc in range(-reach, reach + 1):
            c, r = c0 + dc, r0 + dr
            if c < 0 or r < 0 or c >= cov_cols:
                continue
            if math.hypot((c + 0.5) * COVER_CELL_M - cx, (r + 0.5) * COVER_CELL_M - cy) <= COVER_RADIUS_M:
                cov_cells.add(r * cov_cols + c)

h = h[h.apply(lambda r: inside(r["lat"], r["lon"]), axis=1)]
for _, r in h.iterrows():
    zones.append({
        "id": "h-" + r["시설번호"],
        "type": "HYDRANT",
        "name": "소방용수시설 · " + short_addr(r["소재지지번주소"]),
        "lat": round(float(r["위도"]), 6),
        "lon": round(float(r["경도"]), 6),
        "source": "서울시 소방용수시설 현황(2025-12-31)",
    })
n_hyd = len(zones)

# 2) 버스정류소 10m (중앙차로 정류소·선착장 제외: 가장자리 정차와 무관)
b = pd.read_excel(f"{DATA}/서울시버스정류소위치정보(20260902).xlsx", dtype={"NODE_ID": str, "ARS_ID": str})
b = b[~b["정류소타입"].isin(["중앙차로", "한강선착장"])]
b = b[b.apply(lambda r: inside(r["Y좌표"], r["X좌표"]), axis=1)]
for _, r in b.iterrows():
    zones.append({
        "id": "b-" + str(r["NODE_ID"]),
        "type": "BUS_STOP",
        "name": f"{r['정류소명']} 정류장",
        "lat": round(float(r["Y좌표"]), 6),
        "lon": round(float(r["X좌표"]), 6),
        "source": "서울시 버스정류소 위치정보(2026-09-02)",
    })
n_bus = len(zones) - n_hyd

# 3) 어린이보호구역: 초등학교와 학원가 (시설 위치 반경 100m로 추정)
s = pd.read_csv(f"{DATA}/전국어린이보호구역표준데이터.csv", encoding="cp949", dtype=str)
s = s[s["시설종류"].isin(["초등학교", "학원"])]
s = s[s.apply(lambda r: inside(r["위도"], r["경도"]), axis=1)]
s = s.drop_duplicates(subset=["대상시설명"])
schools = []
for _, r in s.iterrows():
    name = r["대상시설명"]
    label = "대치동 학원가" if name == "대치동학원가" else name
    z = {
        "id": "s-" + hashlib.md5(name.encode()).hexdigest()[:8],
        "type": "SCHOOL_ZONE",
        "name": f"{label} 어린이보호구역",
        "lat": round(float(r["위도"]), 6),
        "lon": round(float(r["경도"]), 6),
        "radius": 100,
        "source": f"전국어린이보호구역표준데이터({r['데이터기준일자']})",
    }
    zones.append(z)
    schools.append(z)
n_sch = len(schools)

# 4) 공영주차장 (세울 수 있는 곳) — 좌표가 있는 전국주차장정보표준데이터를 쓰고, 서울시 파일에서 좌표 있는 곳을 보탠다
p = pd.read_csv(f"{DATA}/전국주차장정보표준데이터.csv", encoding="cp949", dtype=str)
p = p[(p["주차장구분"] == "공영") & p.apply(lambda r: inside(r["위도"], r["경도"]), axis=1)]
for _, r in p.iterrows():
    note = []
    if str(r.get("요금정보")) == "무료":
        note.append("무료")
    elif str(r.get("주차기본시간")) not in ("nan", "None") and str(r.get("주차기본요금")) not in ("nan", "None"):
        note.append(f"기본 {int(float(r['주차기본시간']))}분 {int(float(r['주차기본요금'])):,}원")
    note.append(f"{r['주차장유형']} 주차장")
    spots.append({
        "id": "p-" + str(r["주차장관리번호"]),
        "kind": "PUBLIC_PARKING",
        "name": str(r["주차장명"]).strip() + ("" if "주차장" in str(r["주차장명"]) else " 공영주차장"),
        "lat": round(float(r["위도"]), 6),
        "lon": round(float(r["경도"]), 6),
        "note": " · ".join(note),
        "source": f"전국주차장정보표준데이터({r['데이터기준일자']})",
    })
q = pd.read_csv(f"{DATA}/서울시 공영주차장 안내 정보.csv", encoding="cp949", dtype=str)
q = q[q.apply(lambda r: inside(r["위도"], r["경도"]), axis=1)].drop_duplicates(subset=["주차장명"])
for _, r in q.iterrows():
    lat, lon = round(float(r["위도"]), 6), round(float(r["경도"]), 6)
    if any(dist(lat, lon, x["lat"], x["lon"]) < 30 for x in spots):
        continue
    spots.append({
        "id": "p-" + str(r["주차장코드"]),
        "kind": "PUBLIC_PARKING",
        "name": str(r["주차장명"]).strip(),
        "lat": lat,
        "lon": lon,
        "note": f"기본 {int(float(r['기본 주차 시간(분 단위)']))}분 {int(float(r['기본 주차 요금'])):,}원 · {r['주차장 종류명']}",
        "source": "서울시 공영주차장 안내 정보",
    })


def hits(lat, lon):
    out = []
    for z in zones:
        rad = z.get("radius") or {"HYDRANT": 5, "BUS_STOP": 10, "SCHOOL_ZONE": 100}[z["type"]]
        if dist(lat, lon, z["lat"], z["lon"]) <= rad:
            out.append(z)
    return out


def offset(lat, lon, north_m, east_m):
    return (round(lat + north_m / 111_320, 6), round(lon + east_m / (111_320 * math.cos(math.radians(lat))), 6))


# 시연 지점 고르기 (모두 실제 데이터 기준)
academy = next(z for z in schools if "학원가" in z["name"])
not_school = lambda la, lo: all(x["type"] != "SCHOOL_ZONE" for x in hits(la, lo))

bus_list = sorted([z for z in zones if z["type"] == "BUS_STOP"], key=lambda z: dist(academy["lat"], academy["lon"], z["lat"], z["lon"]))
bus_demo = next(z for z in bus_list if not_school(*offset(z["lat"], z["lon"], 3, 0)))
hyd_list = sorted([z for z in zones if z["type"] == "HYDRANT"], key=lambda z: dist(academy["lat"], academy["lon"], z["lat"], z["lon"]))
hyd_demo = next(z for z in hyd_list if not_school(*offset(z["lat"], z["lon"], 2, 0)) and len(hits(*offset(z["lat"], z["lon"], 2, 0))) == 1)
school_demo = sorted([z for z in schools if "학원가" not in z["name"]], key=lambda z: dist(academy["lat"], academy["lon"], z["lat"], z["lon"]))[0]
clear_demo = None
for sp in sorted(spots, key=lambda z: dist(academy["lat"], academy["lon"], z["lat"], z["lon"])):
    if not hits(sp["lat"], sp["lon"]):
        clear_demo = sp
        break

demo = [
    {"label": "학원가 버스정류장", "lat": offset(bus_demo["lat"], bus_demo["lon"], 3, 0)[0], "lon": offset(bus_demo["lat"], bus_demo["lon"], 3, 0)[1], "ref": bus_demo["name"]},
    {"label": "학원가 어린이보호구역", "lat": offset(academy["lat"], academy["lon"], 0, 20)[0], "lon": offset(academy["lat"], academy["lon"], 0, 20)[1], "ref": academy["name"]},
    {"label": "초등학교 앞", "lat": offset(school_demo["lat"], school_demo["lon"], 30, 0)[0], "lon": offset(school_demo["lat"], school_demo["lon"], 30, 0)[1], "ref": school_demo["name"]},
    {"label": "소화전 앞", "lat": offset(hyd_demo["lat"], hyd_demo["lon"], 2, 0)[0], "lon": offset(hyd_demo["lat"], hyd_demo["lon"], 2, 0)[1], "ref": hyd_demo["name"]},
]
if clear_demo:
    demo.append({"label": "공영주차장 앞", "lat": clear_demo["lat"], "lon": clear_demo["lon"], "ref": clear_demo["name"]})

doc = {
    "meta": {
        "region": "서울 강남구",
        "sample": False,
        "note": "공식 공공데이터에서 서울 강남구와 경계 주변만 골라 만든 파일. 어린이보호구역은 시설 위치 반경 100m로 추정.",
        "sources": [
            "서울 열린데이터광장 · 서울시 소방용수시설 현황(OA-21306), 2025-12-31 기준 · 공공누리 1유형",
            "서울 열린데이터광장 · 서울시 버스정류소 위치정보(OA-15067), 2026-09-02 기준 · 공공누리 1유형",
            "서울 열린데이터광장 · 서울시 공영주차장 안내 정보(OA-13122) · 공공누리 1유형",
            "공공데이터포털 · 전국어린이보호구역표준데이터(15012891)",
            "공공데이터포털 · 전국주차장정보표준데이터(15012896)",
        ],
        "counts": {"HYDRANT": n_hyd, "BUS_STOP": n_bus, "SCHOOL_ZONE": n_sch, "PUBLIC_PARKING": len(spots)},
    },
    "zones": zones,
    "spots": spots,
    "demo": demo,
    "coverage": {"region": REGION, "lat0": round(cov_lat0, 6), "lon0": round(cov_lon0, 6), "cellM": COVER_CELL_M,
                 "cols": cov_cols, "cells": sorted(cov_cells)},
}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))

print(json.dumps(doc["meta"]["counts"], ensure_ascii=False))
print(f"bbox lat {LAT[0]:.4f}~{LAT[1]:.4f} lon {LON[0]:.4f}~{LON[1]:.4f} · 강남구 소방용수시설 {len(home)} · 확인 범위 {len(cov_cells)}칸(약 {len(cov_cells) * COVER_CELL_M ** 2 / 1e6:.1f}km²)")
for d in demo:
    hs = hits(d["lat"], d["lon"])
    print(d["label"], d["lat"], d["lon"], "←", d["ref"], "| 걸림:", [(x["type"], x["name"]) for x in hs])
print("학교:", [z["name"] for z in schools])
