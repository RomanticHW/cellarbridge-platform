import unittest
import xml.etree.ElementTree as ET

from performance.render_summary import render


class RenderSummaryTest(unittest.TestCase):
    def test_renders_metrics_and_rejects_failed_correctness(self) -> None:
        result = {
            "schemaVersion": "cellarbridge.performance-suite.v1",
            "profile": "smoke",
            "durationSeconds": 12.3456,
            "correctnessScenarios": {"inventory": {"passed": True}},
            "performance": {
                "catalogSearch": {
                    "metrics": {
                        "p50Ms": 1.0,
                        "p95Ms": 2.0,
                        "p99Ms": 4.0,
                        "throughputPerSecond": 125.0,
                    }
                },
                "routeEvaluation": {
                    "p50Micros": 5.0,
                    "p95Micros": 7.5,
                    "p99Micros": 10.0,
                    "throughputPerSecond": 5000.0,
                },
            },
        }

        first = render(result)
        self.assertEqual(first, render(result))
        self.assertEqual(ET.fromstring(first).tag, "{http://www.w3.org/2000/svg}svg")
        self.assertIn("4.000 ms", first)
        self.assertIn("10.000 μs", first)
        self.assertIn("duration: 12.346s", first)

        result["correctnessScenarios"]["inventory"]["passed"] = False
        with self.assertRaisesRegex(ValueError, "failed correctness scenarios"):
            render(result)


if __name__ == "__main__":
    unittest.main()
