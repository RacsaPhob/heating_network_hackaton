"""Regenerate the small catalog from DOCX tables; requires python-docx.

Run from repository root. Scalar formulas/rules are transcribed from sections
5-9; tabular numeric values are extracted, not retyped. This is a dev tool.
"""
import hashlib
import json
from pathlib import Path

from docx import Document

ROOT = Path(__file__).resolve().parents[1]


def number(text):
    return float("".join(text.split()).replace(",", "."))


def extract(source):
    doc = Document(source)
    tables = doc.tables
    if len(tables) != 13 or len(tables[2].rows) != 19 or len(tables[3].rows) != 19:
        raise ValueError("DOCX table structure changed: review extraction manually")
    dimensions = {}
    for row in tables[3].rows[1:]:
        values = [number(cell.text) for cell in row.cells]
        dimensions[int(values[0])] = dict(zip(
            ["shell_diameter_m", "shell_gap_m", "pair_width_m", "pair_height_m"], values[1:]))
    diameters = []
    for row in tables[2].rows[1:]:
        diameter, capacity, length, new, reconstruction = [number(c.text) for c in row.cells]
        diameters.append({"diameter_mm": int(diameter), "capacity_tph": capacity,
                          "max_continuous_length_m": int(length),
                          "construction_rub_per_m": int(new),
                          "reconstruction_rub_per_m": int(reconstruction),
                          **dimensions[int(diameter)]})
    chambers = []
    for row in tables[5].rows[1:]:
        low, high = row.cells[0].text.strip().replace("–", "-").split("-")
        chambers.append({"min_diameter_mm": int(low), "max_diameter_mm": int(high),
                         "cost_rub": int(number(row.cells[1].text))})
    output_fields = {}
    for index, kind in enumerate(["heat_network", "tie_in", "heat_network_reconstruction",
                                  "heat_chamber", "heat_chamber_reconstruction",
                                  "technical_node", "variant_summary"], start=6):
        output_fields[kind] = [row.cells[1].text.strip() for row in tables[index].rows[1:]]
    return {
        "version": "0.1",
        "source": {"file": source.name, "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                   "sections": ["4.1", "4.2", "5.1", "6", "7", "8", "9", "10"],
                   "note": "Tables extracted automatically; other rules transcribed. Unresolved questions: docs/DATASET_REVIEW.md"},
        "input_crs": "EPSG:4326", "calculation_crs": "EPSG:32637",
        "diameters": diameters,
        "chambers": chambers,
        "tie_in_cost_rub": 5000000,
        "existing_chamber_snap_distance_m": 10,
        "max_chamber_degree": 4,
        "penalty": {"fixed_rub_per_oks": 100000000, "rub_per_tph": 500000},
        "ranking": {"cost_weight": 0.7, "cost_scale_rub": 25000000,
                    "length_weight": 0.3, "length_scale_m": 100},
        "building_clearance_bands": [
            {"min_diameter_mm": 50, "max_diameter_mm": 400, "clearance_m": 5},
            {"min_diameter_mm": 500, "max_diameter_mm": 800, "clearance_m": 7},
            {"min_diameter_mm": 900, "max_diameter_mm": 1400, "clearance_m": 9}],
        "restrictions": {
            **{kind: {"crossing": "forbidden", "clearance_m": 1}
               for kind in ["park", "social_area", "prohibited_site", "water"]},
            "road": {"crossing": "special", "clearance_m": 1.5, "min_angle_deg": 45,
                     "extension_each_side_m": 3, "coefficient": 1.60, "min_top_depth_m": 1.0},
            "tram_tracks": {"crossing": "special", "clearance_m": 1.5, "min_angle_deg": 45,
                            "extension_each_side_m": 3, "coefficient": 1.75, "min_top_depth_m": 1.2},
            "gas_pipeline": {"crossing": "special", "clearance_m": 2,
                             "extension_each_side_m": 2, "coefficient": 1.25, "vertical_gap_m": 0.2,
                             "existing_top_depth_m": 2.8, "existing_width_m": 0.4, "existing_height_m": 0.4},
            "power_cable": {"crossing": "special", "clearance_m": 2,
                            "extension_each_side_m": 2, "coefficient": 1.15, "vertical_gap_m": 0.5,
                            "existing_top_depth_m": 2.7, "existing_width_m": 0.2, "existing_height_m": 0.2},
            "heat_network": {"crossing": "special", "clearance_m": 1,
                             "extension_each_side_m": 2, "coefficient": 1.05, "vertical_gap_m": 0.5,
                             "existing_top_depth_m": 3.0}},
        "unknown_restriction_policy": "error",
        "depth": {"optional_mode": True, "base_top_depth_m": 3.0, "min_top_depth_m": 0.7,
                  "step_m": 0.5, "max_slope": 0.10, "extra_cost_per_m_below_base": 0.10,
                  "max_depth_m": None, "grid_origin_m": None,
                  "note": "Maximum depth and grid origin require clarification; null is not an unlimited search range."},
        "output_required_properties": output_fields,
    }


if __name__ == "__main__":
    source = ROOT / "Датасет" / "Техническое приложение.docx"
    target = ROOT / "contracts" / "rules.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(extract(source), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(target)
