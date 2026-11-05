"""국토교통부 전국표준노드링크 → zones_daechi.json 의 교차로 모퉁이(CORNER) 금지구역

사용법:
  python3 tools/add_corners.py "<[2026-10-14]NODELINKDATA.zip>" app/src/main/assets/zones_daechi.json

- 도로교통법 제32조 제2호: 교차로의 가장자리나 도로의 모퉁이로부터 5미터 이내 정차·주차 금지 (주민신고제 대상)
- 교차로: 노드 유형 101(교차로) 가운데 일반도로(도로유형 000, 연결로·도시고속도로 제외)가 세 갈래 이상 만나는 곳
- 교차로 가장자리(모양): 만나는 도로마다 방향(노드에서 12m 지점)과 폭(방향별 차로 수 합 × 3.0m)을 구하고,
  이웃한 두 도로의 연석선이 만나는 점(= 모퉁이)을 이어 교차로 영역을 만든다. 앱은 이 테두리에서 5m 안인지 잰다.
  차로 폭 3.0m: 「도로의 구조·시설 기준에 관한 규칙」 제10조 도시지역 설계속도 70km/h 미만 최소 차로 폭.
  한쪽이 거의 일직선(150° 이상)이면 모퉁이가 없으므로, 맞은편 도로 폭만큼만 연석선을 따라 테두리를 잡는다 (T자 교차로).
- 왕복 분리 도로가 만나는 큰 교차로는 차도마다 노드가 따로 있어, 이름이 같고 35m 안의 링크로 이어진 교차로
  노드를 묶고(묶음 폭 70m 이하) 조각들을 감싸는 볼록 다각형을 교차로 영역으로 본다.
- 실제 연석·보도 폭은 노드링크에 없어 '추정'이다. 실폭도로 도형은 국외 반출 금지 자료라 쓰지 않았다.
- 외부 라이브러리 없이 zip 안의 shapefile·dbf를 바로 읽는다.
"""
import json
import math
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_basemap as bb  # noqa: E402  (좌표 변환을 함께 쓴다)

ZIP, ZONES = sys.argv[1], sys.argv[2]

SRC = "국토교통부 전국표준노드링크(2026-10-14) 교차로로 추정"
LANE_W = 3.0          # 차로 하나 폭(m)
MIN_HALF = 2.5        # 가장 좁은 골목도 중심선에서 연석까지 이 정도는 있다고 본다
SAMPLE_M = 12.0       # 도로 방향은 노드에서 이 거리 지점으로 잰다 (곡선 영향 줄이기)
MERGE_DEG = 20.0      # 이보다 가까운 방향의 두 도로는 한 도로로 합친다
STRAIGHT_DEG = 150.0  # 이웃한 두 도로 사이 각이 이 이상이면 모퉁이가 없다고 본다

tm = bb.TM(lat0=38.0, lon0=127.0, k0=1.0, fe=200_000.0, fn=600_000.0)  # MOCT_*.prj (중부원점 60만)

# ---------------------------------------------------------------- 범위: 기존 금지구역 데이터와 같은 곳만 (확인 범위가 바뀌지 않게)
doc = json.load(open(ZONES, encoding="utf-8"))
others = [z for z in doc["zones"] if z.get("type") != "CORNER"]
lat_min, lat_max = min(z["lat"] for z in others), max(z["lat"] for z in others)
lon_min, lon_max = min(z["lon"] for z in others), max(z["lon"] for z in others)
corners_xy = [tm.forward(a, b) for a in (lat_min, lat_max) for b in (lon_min, lon_max)]
x_lo, x_hi = min(p[0] for p in corners_xy) - 150, max(p[0] for p in corners_xy) + 150
y_lo, y_hi = min(p[1] for p in corners_xy) - 150, max(p[1] for p in corners_xy) + 150

z = zipfile.ZipFile(ZIP)


def dbf_header(f):
    """헤더 전체를 읽어 필드 위치를 돌려준다. 끝나면 f는 첫 레코드 앞에 있다."""
    head = f.read(32)
    nrec, hlen, rlen = struct.unpack("<IHH", head[4:12])
    rest = f.read(hlen - 32)
    fields, off, p = {}, 1, 0
    while p + 32 <= len(rest) and rest[p] != 0x0D:
        d = rest[p:p + 32]
        fields[d[:11].split(b"\x00")[0].decode("ascii")] = (off, off + d[16])
        off += d[16]
        p += 32
    return nrec, rlen, fields


def text(rec, fields, name):
    a, b = fields[name]
    return rec[a:b].decode("cp949", "replace").strip()


# ---------------------------------------------------------------- 노드 (범위 안)
shp = z.read("MOCT_NODE.shp")
pos, idx, node_xy = 100, 0, {}
while pos < len(shp):
    _, clen = struct.unpack(">ii", shp[pos:pos + 8])
    c = shp[pos + 8:pos + 8 + clen * 2]
    if struct.unpack("<i", c[:4])[0] == 1:
        x, y = struct.unpack("<2d", c[4:20])
        if x_lo <= x <= x_hi and y_lo <= y <= y_hi:
            node_xy[idx] = (x, y)
    pos += 8 + clen * 2
    idx += 1
del shp

nodes = {}
with z.open("MOCT_NODE.dbf") as f:
    nrec, rlen, fields = dbf_header(f)
    data = f.read()
for i, (x, y) in node_xy.items():
    rec = data[i * rlen:(i + 1) * rlen]
    nodes[text(rec, fields, "NODE_ID")] = {"x": x, "y": y, "type": text(rec, fields, "NODE_TYPE"),
                                           "name": text(rec, fields, "NODE_NAME")}
del data
ids = set(nodes)

# ---------------------------------------------------------------- 링크 (범위 안 노드에 닿는 것)
links = {}
with z.open("MOCT_LINK.dbf") as f:
    nrec, rlen, fields = dbf_header(f)
    done = 0
    while done < nrec:
        k = min(20_000, nrec - done)
        buf = f.read(rlen * k)
        for j in range(k):
            rec = buf[j * rlen:(j + 1) * rlen]
            fn, tn = text(rec, fields, "F_NODE"), text(rec, fields, "T_NODE")
            if fn in ids or tn in ids:
                links[done + j] = {
                    "f": fn, "t": tn,
                    "lanes": int(float(text(rec, fields, "LANES") or 1) or 1),
                    "rank": text(rec, fields, "ROAD_RANK"), "rtype": text(rec, fields, "ROAD_TYPE"),
                    "use": text(rec, fields, "ROAD_USE"), "connect": text(rec, fields, "CONNECT"),
                    "name": text(rec, fields, "ROAD_NAME"),
                }
        done += k

with z.open("MOCT_LINK.shp") as f:
    f.read(100)
    i = 0
    while True:
        h = f.read(8)
        if len(h) < 8:
            break
        _, clen = struct.unpack(">ii", h)
        c = f.read(clen * 2)
        if i in links:
            nparts, npts = struct.unpack("<ii", c[36:44])
            p0 = 44 + 4 * nparts
            xy = struct.unpack(f"<{2 * npts}d", c[p0:p0 + 16 * npts])
            links[i]["pts"] = [(xy[2 * q], xy[2 * q + 1]) for q in range(npts)]
        i += 1


def usable(l):
    """모퉁이 판단에 쓰는 도로: 일반도로, 연결로·도시고속도로·통행 불가 표시 제외"""
    return l["rtype"] == "000" and l["connect"] == "0" and l["rank"] not in ("101", "102") and l["use"] == "0"


def heading(pts, x0, y0):
    """노드에서 SAMPLE_M 떨어진 지점 방향 (단위 벡터). 방향을 못 구하면 None"""
    # 도형이 링크 방향과 반대로 그려진 경우가 있어, 노드에 가까운 끝에서 시작한다
    if math.hypot(pts[-1][0] - x0, pts[-1][1] - y0) < math.hypot(pts[0][0] - x0, pts[0][1] - y0):
        pts = pts[::-1]
    acc, px, py = 0.0, pts[0][0], pts[0][1]
    tx, ty = pts[-1]
    for qx, qy in pts[1:]:
        seg = math.hypot(qx - px, qy - py)
        if acc + seg >= SAMPLE_M:
            r = (SAMPLE_M - acc) / seg
            tx, ty = px + (qx - px) * r, py + (qy - py) * r
            break
        acc += seg
        px, py = qx, qy
    dx, dy = tx - x0, ty - y0
    n = math.hypot(dx, dy)
    if n < 1.0:
        return None
    return dx / n, dy / n


# ---------------------------------------------------------------- 노드마다 만나는 도로
roads_at = {nid: {} for nid in nodes}
for l in links.values():
    if not usable(l) or "pts" not in l:
        continue
    for here, there, out in ((l["f"], l["t"], True), (l["t"], l["f"], False)):
        if here not in nodes:
            continue
        n = nodes[here]
        h = heading(l["pts"], n["x"], n["y"])
        if h is None:
            continue
        r = roads_at[here].setdefault(there, {"out": 0, "in": 0, "dirs": [], "name": l["name"]})
        r["out" if out else "in"] = max(r["out" if out else "in"], l["lanes"])
        r["dirs"].append(h)


def ang(v):
    return math.atan2(v[1], v[0])


def gap(a, b):
    """a에서 시계 반대 방향으로 b까지 각 (0~2π)"""
    return (b - a) % (2 * math.pi)


def corner_point(ai, hi, aj, hj):
    """도로 i의 왼쪽 연석선과 도로 j의 오른쪽 연석선이 만나는 점"""
    di = (math.cos(ai), math.sin(ai))
    dj = (math.cos(aj), math.sin(aj))
    pi = (-di[1] * hi, di[0] * hi)          # 왼쪽으로 hi
    pj = (dj[1] * hj, -dj[0] * hj)          # 오른쪽으로 hj
    det = di[0] * (-dj[1]) - di[1] * (-dj[0])
    if abs(det) < 1e-6:
        return None
    rx, ry = pj[0] - pi[0], pj[1] - pi[1]
    t = (rx * (-dj[1]) - ry * (-dj[0])) / det
    return pi[0] + t * di[0], pi[1] + t * di[1]


def node_shape(nid):
    """노드 하나의 교차로 영역(노드 기준 좌표 목록)과 만나는 도로 목록"""
    roads = []
    for r in roads_at[nid].values():
        sx, sy = sum(d[0] for d in r["dirs"]), sum(d[1] for d in r["dirs"])
        half = max((r["out"] + r["in"]) * LANE_W / 2, MIN_HALF)
        roads.append({"a": math.atan2(sy, sx), "half": half, "name": r["name"]})
    # 방향이 거의 같은 도로는 합친다
    roads.sort(key=lambda r: r["a"])
    merged = []
    for r in roads:
        if merged and math.degrees(gap(merged[-1]["a"], r["a"])) < MERGE_DEG:
            m = merged[-1]
            m["a"] = math.atan2(math.sin(m["a"]) + math.sin(r["a"]), math.cos(m["a"]) + math.cos(r["a"]))
            m["half"] = max(m["half"], r["half"])
        else:
            merged.append(dict(r))
    if len(merged) > 1 and math.degrees(gap(merged[-1]["a"], merged[0]["a"])) < MERGE_DEG:
        last = merged.pop()
        merged[0]["half"] = max(merged[0]["half"], last["half"])
    if len(merged) < 3:
        # 두 갈래뿐인 점: 도로 폭만큼만 (큰 교차로를 이루는 조각일 때 함께 쓴다)
        ring = []
        for r in merged:
            d = (math.cos(r["a"]), math.sin(r["a"]))
            ring += [(-d[1] * r["half"], d[0] * r["half"]), (d[1] * r["half"], -d[0] * r["half"])]
        return ring, merged, False

    widest = max(r["half"] for r in merged)
    limit = 3 * widest + 15
    ring = []
    cnt = len(merged)
    for k in range(cnt):
        ri, rj = merged[k], merged[(k + 1) % cnt]
        g = math.degrees(gap(ri["a"], rj["a"]))
        p = corner_point(ri["a"], ri["half"], rj["a"], rj["half"]) if g < STRAIGHT_DEG else None
        if p is not None and math.hypot(*p) <= limit:
            ring.append(p)
            continue
        # 모퉁이가 없는 쪽(거의 일직선): 맞은편 도로 폭만큼 연석선을 따라 테두리를 잡는다
        extent = max([r["half"] for q, r in enumerate(merged) if q not in (k, (k + 1) % cnt)] or [MIN_HALF])
        for a, h, side in ((ri["a"], ri["half"], 1), (rj["a"], rj["half"], -1)):
            d = (math.cos(a), math.sin(a))
            off = (-d[1] * h * side, d[0] * h * side)
            ring.append((off[0] + d[0] * extent, off[1] + d[1] * extent))
    return ring, merged, True


def hull(points):
    """볼록 껍질 (Andrew monotone chain), 시계 반대 방향"""
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


# ---------------------------------------------------------------- 큰 교차로 묶기
# 왕복 분리 도로가 만나는 큰 교차로는 방향별 차도마다 노드가 있어 한 교차로가 여러 노드로 나뉜다.
# 한 교차로를 이루는 노드들은 이름이 같다 (예: 네 노드 모두 '한티역앞교차로'). 그래서 '이름이 같고
# CLUSTER_M 안의 링크로 바로 이어진' 교차로 노드만 묶는다. 묶음이 CLUSTER_SPAN_M보다 넓으면 묶지 않는다.
CLUSTER_M = 35.0
CLUSTER_SPAN_M = 70.0
inter = [nid for nid, n in nodes.items() if n["type"] == "101"]
parent = {nid: nid for nid in inter}


def find(a):
    while parent[a] != a:
        parent[a] = parent[parent[a]]
        a = parent[a]
    return a


for l in links.values():
    if not usable(l) or "pts" not in l or l["f"] not in parent or l["t"] not in parent:
        continue
    name = nodes[l["f"]]["name"]
    if not name or name != nodes[l["t"]]["name"]:
        continue
    length = sum(math.hypot(l["pts"][q + 1][0] - l["pts"][q][0], l["pts"][q + 1][1] - l["pts"][q][1])
                 for q in range(len(l["pts"]) - 1))
    if length < CLUSTER_M:
        ra, rb = find(l["f"]), find(l["t"])
        if ra != rb:
            parent[ra] = rb

clusters, too_wide = {}, 0
for nid in inter:
    clusters.setdefault(find(nid), []).append(nid)
for key, members in list(clusters.items()):
    span = max((math.hypot(nodes[a]["x"] - nodes[b]["x"], nodes[a]["y"] - nodes[b]["y"])
                for a in members for b in members), default=0.0)
    if span > CLUSTER_SPAN_M:
        too_wide += 1
        del clusters[key]
        for nid in members:
            clusters[nid] = [nid]

zones, skipped_small, merged_nodes = [], 0, 0
for members in clusters.values():
    shapes = {nid: node_shape(nid) for nid in members}
    if not any(full for _, _, full in shapes.values()):
        skipped_small += len(members)
        continue
    pts = []
    for nid, (ring, _, _) in shapes.items():
        n = nodes[nid]
        pts += [(n["x"] + px, n["y"] + py) for px, py in ring]
    outline = hull(pts)
    cx = sum(nodes[nid]["x"] for nid in members) / len(members)
    cy = sum(nodes[nid]["y"] for nid in members) / len(members)
    lat, lon = tm.inverse(cx, cy)
    if not (lat_min <= lat <= lat_max and lon_min <= lon <= lon_max):
        continue
    if len(members) > 1:
        merged_nodes += len(members)
    shape = []
    for px, py in outline:
        a, b = tm.inverse(px, py)
        shape += [round(a, 6), round(b, 6)]
    # 이름: '○○사거리' 같은 교차로 이름이 있으면 그것, 없으면 넓은 도로 이름 둘
    label = next((nodes[nid]["name"] for nid in members
                  if any(w in nodes[nid]["name"] for w in ("사거리", "삼거리", "오거리", "교차로"))), "")
    if not label:
        names = []
        for _, merged, _ in shapes.values():
            for r in sorted(merged, key=lambda r: -r["half"]):
                if r["name"] and r["name"] != "-" and r["name"] not in names:
                    names.append(r["name"])
        label = " · ".join(names[:2])
    zones.append({"id": f"k-{min(members)}", "type": "CORNER", "name": f"교차로 모퉁이 · {label}" if label else "교차로 모퉁이",
                  "lat": round(lat, 6), "lon": round(lon, 6), "shape": shape, "source": SRC})

# ---------------------------------------------------------------- 시연 지점: 다른 금지구역에는 걸리지 않고 모퉁이에만 걸리는 자리
def dist_to(zone, lat, lon):
    """점에서 구역까지 거리(m): 모양이 있으면 테두리까지(안이면 0), 없으면 중심점까지"""
    kx, ky = 111_320.0 * math.cos(math.radians(lat)), 110_540.0
    s = zone.get("shape")
    if not s or len(s) < 6:
        return math.hypot((zone["lon"] - lon) * kx, (zone["lat"] - lat) * ky)
    pts = [((s[q + 1] - lon) * kx, (s[q] - lat) * ky) for q in range(0, len(s), 2)]
    inside, best = False, float("inf")
    for q in range(len(pts)):
        (x0, y0), (x1, y1) = pts[q], pts[(q + 1) % len(pts)]
        if (y0 > 0) != (y1 > 0) and x0 + (x1 - x0) * (0 - y0) / (y1 - y0) > 0:
            inside = not inside
        dx, dy = x1 - x0, y1 - y0
        l2 = dx * dx + dy * dy
        t = 0.0 if l2 < 1e-12 else max(0.0, min(1.0, -(x0 * dx + y0 * dy) / l2))
        best = min(best, math.hypot(x0 + t * dx, y0 + t * dy))
    return 0.0 if inside else best


RADIUS = {"HYDRANT": 5, "BUS_STOP": 10, "CROSSWALK": 10, "SCHOOL_ZONE": 100}
demo = [d for d in doc.get("demo", []) if d.get("label") != "교차로 모퉁이"]
base = next((d for d in demo if d.get("label") == "학원가 버스정류장"), None)
if base is not None and zones:
    best = None
    for zc in sorted(zones, key=lambda q: (q["lat"] - base["lat"]) ** 2 + ((q["lon"] - base["lon"]) * 0.79) ** 2)[:40]:
        s = zc["shape"]
        ring = [tm.forward(s[q], s[q + 1]) for q in range(0, len(s), 2)]
        # 껍질은 시계 반대 방향이라 변의 왼쪽이 교차로(도로) 쪽이다
        for q, v in enumerate(ring):
            for other, side in ((ring[q - 1], 1.0), (ring[(q + 1) % len(ring)], -1.0)):
                ex, ey = v[0] - other[0], v[1] - other[1]
                el = math.hypot(ex, ey) or 1.0
                ex, ey = ex / el, ey / el
                # 모퉁이에서 연석을 따라 3m 바깥, 도로 쪽으로 1.2m (차가 연석 옆에 선 자리)
                px, py = v[0] + ex * 3.0 - ey * 1.2 * side, v[1] + ey * 3.0 + ex * 1.2 * side
                la, lo = tm.inverse(px, py)
                # 교차로 테두리 밖 2~4m (모퉁이 5m 안)인 자리만. 거의 일직선인 꼭짓점에서는 안쪽으로 들어가 버린다
                if not 2.0 <= dist_to(zc, la, lo) <= 4.0:
                    continue
                near = [o for o in others if o["type"] in RADIUS and abs(o["lat"] - la) < 0.0012 and abs(o["lon"] - lo) < 0.0015]
                if any(dist_to(o, la, lo) <= o.get("radius", RADIUS[o["type"]]) + 3 for o in near):
                    continue
                if any(dist_to(o, la, lo) <= 5.0 + 1 for o in zones if o is not zc and abs(o["lat"] - la) < 0.001):
                    continue
                d = (la - base["lat"]) ** 2 + ((lo - base["lon"]) * 0.79) ** 2
                if best is None or d < best[0]:
                    best = (d, la, lo, zc["name"])
        if best is not None and best[0] < (0.004 ** 2):
            break
    if best is not None:
        pos = next((q + 1 for q, d in enumerate(demo) if d.get("label") == "학원가 횡단보도"), len(demo))
        demo.insert(pos, {"label": "교차로 모퉁이", "lat": round(best[1], 6), "lon": round(best[2], 6), "ref": best[3]})
        print("demo:", demo[pos])
doc["demo"] = demo

# ---------------------------------------------------------------- 기존 파일에 반영
doc["zones"] = others + zones
meta = doc.setdefault("meta", {})
meta["sources"] = [s for s in meta.get("sources", []) if "표준노드링크" not in s] + [
    "국토교통부 국가교통정보센터 · 전국표준노드링크(2026-10-14) · 교차로 모퉁이 추정"]
meta.setdefault("counts", {})["CORNER"] = len(zones)
meta["note"] = ("공식 공공데이터에서 대치동 주변만 골라 만든 파일. 어린이보호구역은 시설 위치 반경 100m로 추정. "
                "횡단보도는 모양(면) 테두리에서 10m로 판단. 교차로 모퉁이는 표준노드링크의 교차로와 차로 수로 "
                "교차로 영역을 추정해 그 테두리에서 5m로 판단.")
with open(ZONES, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))

print(f"nodes_in_area={len(nodes)} links={len(links)} corner_zones={len(zones)} "
      f"nodes_merged_into_big_intersections={merged_nodes} too_wide={too_wide} skipped_two_way_points={skipped_small}")
print(f"size={os.path.getsize(ZONES) // 1024}KB")
for zz in zones[:: max(1, len(zones) // 12)]:
    print(zz["name"], len(zz["shape"]) // 2, "pts")
