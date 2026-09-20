import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("audit_dataset", ROOT / "tools" / "audit_dataset.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)
RULES = json.loads((ROOT / "contracts" / "rules.json").read_text(encoding="utf-8"))


def feature(kind, object_id, coordinates, **properties):
    geometry_type = {"source": "Point", "heat_network": "LineString", "oks_future": "Polygon",
                     "oks_connection_point": "Point", "restriction": "Polygon"}[kind]
    return {"type": "Feature", "properties": {"id": object_id, "object_type": kind, **properties},
            "geometry": {"type": geometry_type, "coordinates": coordinates}}


def complete_sample():
    return {"type": "FeatureCollection", "features": [
        feature("source", "s", [37.6, 55.7]),
        feature("heat_network", "n", [[37.6, 55.7], [37.601, 55.7]], diameter=200, flow_tph=0,
                upstream_object_id="s"),
        feature("oks_future", "o", [[[37.602, 55.7], [37.603, 55.7], [37.603, 55.701],
                                     [37.602, 55.701], [37.602, 55.7]]], flow_tph=10, heat_load=1),
        feature("oks_connection_point", "c", [37.602, 55.7], oks_id="o")
    ]}


class AuditTests(unittest.TestCase):
    def codes(self, sample):
        return {e["code"] for e in AUDIT.audit(sample, RULES)["errors"]}

    def test_explicit_zero_flow_is_valid(self):
        self.assertTrue(AUDIT.audit(complete_sample(), RULES)["strict_structural_validation_passed"])

    def test_missing_flow_is_not_zero(self):
        sample = complete_sample()
        del sample["features"][1]["properties"]["flow_tph"]
        self.assertIn("MISSING_HEAT_NETWORK_FLOW_TPH", self.codes(sample))

    def test_upstream_cycle(self):
        sample = complete_sample()
        sample["features"][1]["properties"]["upstream_object_id"] = "n"
        self.assertIn("UPSTREAM_CYCLE", self.codes(sample))

    def test_missing_reference(self):
        sample = complete_sample()
        sample["features"][1]["properties"]["upstream_object_id"] = "missing"
        self.assertIn("INVALID_UPSTREAM_REFERENCE", self.codes(sample))

    def test_duplicate_ids_after_normalization(self):
        sample = complete_sample()
        sample["features"][0]["properties"]["id"] = 1
        sample["features"][1]["properties"]["id"] = "1"
        self.assertIn("DUPLICATE_ID", self.codes(sample))

    def test_unknown_restriction_never_silently_passes(self):
        sample = complete_sample()
        sample["features"].append(feature("restriction", "r", [[[37, 55], [38, 55], [37, 56], [37, 55]]], restriction_type="railway"))
        self.assertIn("UNKNOWN_RESTRICTION_RAILWAY", self.codes(sample))

    def test_audit_does_not_mutate_input(self):
        sample = complete_sample()
        original = copy.deepcopy(sample)
        AUDIT.audit(sample, RULES)
        self.assertEqual(original, sample)

    def test_supplied_sample_is_diagnosed(self):
        paths = list((ROOT / "Датасет").glob("*.geojson"))
        if not paths:
            self.skipTest("Competition sample not included")
        result = AUDIT.audit(json.loads(paths[0].read_text(encoding="utf-8-sig")), RULES)
        self.assertFalse(result["strict_structural_validation_passed"])
        self.assertEqual(144, result["feature_count"])
        self.assertAlmostEqual(488.72, result["flow_in_connection_points_tph"])
        codes = {e["code"] for e in result["errors"]}
        self.assertIn("NO_FUTURE_OKS", codes)
        self.assertIn("MISSING_HEAT_NETWORK_UPSTREAM_OBJECT_ID", codes)


if __name__ == "__main__":
    unittest.main()
