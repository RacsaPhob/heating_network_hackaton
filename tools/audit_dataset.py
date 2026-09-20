"""Read-only structural audit for small GeoJSON samples, using Python stdlib.

Not the production importer: hard limit 32 MiB; geometry/topology calculations
and metric distances are deliberately outside this diagnostic tool.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import sys

REQUIRED = {
    "source": ("id", "object_type"),
    "heat_network": ("id", "object_type", "diameter", "flow_tph", "upstream_object_id"),
    "heat_chamber": ("id", "object_type", "diameter", "upstream_object_id"),
    "oks_future": ("id", "object_type", "flow_tph", "heat_load"),
    "oks_connection_point": ("id", "object_type", "oks_id"),
    "oks_existing": ("id", "object_type"),
    "restriction": ("id", "object_type", "restriction_type"),
}
GEOMETRIES = {
    "source": {"Point"}, "heat_network": {"LineString"}, "heat_chamber": {"Point"},
    "oks_future": {"Polygon", "MultiPolygon"}, "oks_connection_point": {"Point"},
    "oks_existing": {"Polygon", "MultiPolygon"},
    "restriction": {"Polygon", "MultiPolygon", "LineString", "MultiLineString"},
}
ROOT = Path(__file__).resolve().parents[1]
MAX_BYTES = 32 * 1024 * 1024


def finite_nonnegative(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def audit(data, rules):
    groups = {}

    def issue(code, object_id, message):
        group = groups.setdefault(code, {"code": code, "severity": "error", "message": message,
                                         "count": 0, "example_object_ids": []})
        group["count"] += 1
        if len(group["example_object_ids"]) < 10:
            group["example_object_ids"].append(str(object_id))

    if not isinstance(data, dict) or data.get("type") != "FeatureCollection" or not isinstance(data.get("features"), list):
        return {"strict_structural_validation_passed": False, "feature_count": 0,
                "errors": [{"code": "INVALID_COLLECTION", "severity": "error", "count": 1,
                            "message": "Expected GeoJSON FeatureCollection with features array", "example_object_ids": []}]}
    features = data["features"]
    by_id = {}
    kinds = Counter()
    restrictions = Counter()
    parsed = []
    diameters = {row["diameter_mm"] for row in rules["diameters"]}
    for index, feature in enumerate(features):
        if not isinstance(feature, dict) or feature.get("type") != "Feature" or not isinstance(feature.get("properties"), dict):
            issue("INVALID_FEATURE", index, "Expected Feature with object properties")
            continue
        props = feature["properties"]
        object_id = props.get("id", "index:" + str(index))
        kind = props.get("object_type")
        if not isinstance(kind, str) or kind not in REQUIRED:
            issue("UNKNOWN_OBJECT_TYPE", object_id, "Unknown or missing object_type")
            continue
        kinds[kind] += 1
        parsed.append((object_id, kind, props))
        key = str(object_id)
        if key in by_id:
            issue("DUPLICATE_ID", object_id, "Duplicate ID, including string conversion collisions")
        else:
            by_id[key] = props
        if not isinstance(props.get("id"), str) or not props.get("id"):
            issue("ID_NOT_STRING", object_id, "The appendix requires nonempty string IDs")
        for field in REQUIRED[kind]:
            if field not in props or props[field] is None:
                issue("MISSING_" + kind.upper() + "_" + field.upper(), object_id,
                      "Missing required property " + kind + "." + field)
        for field in ("flow_tph", "heat_load"):
            if field in props and not finite_nonnegative(props[field]):
                issue("INVALID_" + field.upper(), object_id, field + " must be a finite nonnegative number")
        if "diameter" in props and (type(props["diameter"]) is not int or props["diameter"] not in diameters):
            issue("INVALID_DIAMETER", object_id, "Diameter must be an integer from table 4.1")
        for field in ("upstream_object_id", "oks_id"):
            if field in props and (not isinstance(props[field], str) or not props[field]):
                issue("INVALID_" + field.upper(), object_id, field + " must be a nonempty string")
        geometry = feature.get("geometry")
        if not isinstance(geometry, dict) or geometry.get("type") not in GEOMETRIES[kind] or not isinstance(geometry.get("coordinates"), list) or not geometry["coordinates"]:
            issue("INVALID_GEOMETRY_TYPE", object_id, "Missing geometry/coordinates or unexpected geometry type")
        if kind == "restriction":
            restriction = props.get("restriction_type")
            label = restriction if isinstance(restriction, str) else "<invalid>"
            restrictions[label] += 1
            if label not in rules["restrictions"]:
                issue("UNKNOWN_RESTRICTION_" + label.upper(), object_id, "No defined rule for restriction_type=" + label)

    connections = defaultdict(list)
    for object_id, kind, props in parsed:
        if kind == "oks_connection_point" and isinstance(props.get("oks_id"), str):
            reference = props["oks_id"]
            connections[reference].append(object_id)
            if by_id.get(reference, {}).get("object_type") != "oks_future":
                issue("INVALID_OKS_REFERENCE", object_id, "oks_id must reference oks_future")
        if kind in ("heat_network", "heat_chamber") and isinstance(props.get("upstream_object_id"), str):
            visited = {str(object_id)}
            upstream = props["upstream_object_id"]
            while True:
                if upstream in visited:
                    issue("UPSTREAM_CYCLE", object_id, "Upstream chain contains a cycle")
                    break
                visited.add(upstream)
                target = by_id.get(upstream)
                if target is None or target.get("object_type") not in ("source", "heat_network", "heat_chamber"):
                    issue("INVALID_UPSTREAM_REFERENCE", object_id, "Upstream must reference source/network/chamber")
                    break
                if target.get("object_type") == "source":
                    break
                upstream = target.get("upstream_object_id")
                if not isinstance(upstream, str) or not upstream:
                    issue("INCOMPLETE_UPSTREAM_CHAIN", object_id, "Upstream chain does not reach source")
                    break
    for object_id, kind, props in parsed:
        if kind == "oks_future" and len(connections[str(object_id)]) != 1:
            issue("OKS_CONNECTION_COUNT", object_id, "Expected one connection point per future OKS")
    if kinds["source"] != 1:
        issue("SOURCE_COUNT", "dataset", "Expected exactly one source")
    if kinds["oks_future"] == 0:
        issue("NO_FUTURE_OKS", "dataset", "No oks_future objects for connection calculation")
    point_flow = sum(props["flow_tph"] for _, kind, props in parsed
                     if kind == "oks_connection_point" and finite_nonnegative(props.get("flow_tph")))
    return {
        "strict_structural_validation_passed": not groups,
        "feature_count": len(features), "object_counts": dict(sorted(kinds.items())),
        "restriction_counts": dict(sorted(restrictions.items())),
        "flow_in_connection_points_tph": round(point_flow, 6),
        "errors": [groups[k] for k in sorted(groups)],
        "not_checked": ["coordinate validity and CRS transformation", "polygon validity",
                        "spatial topology and metric clearances", "capacity and diameter monotonicity",
                        "routing feasibility", "complete conformity to the competition requirements"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.input.stat().st_size > MAX_BYTES:
        parser.error("This developer audit only supports files up to 32 MiB; use the future streaming importer")
    raw = args.input.read_bytes()
    rules = json.loads((ROOT / "contracts" / "rules.json").read_text(encoding="utf-8"))
    report = audit(json.loads(raw.decode("utf-8-sig")), rules)
    report["file"] = args.input.name
    report["size_bytes"] = len(raw)
    report["sha256"] = hashlib.sha256(raw).hexdigest()
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text)
    return 0 if report["strict_structural_validation_passed"] else 2


if __name__ == "__main__":
    sys.exit(main())
