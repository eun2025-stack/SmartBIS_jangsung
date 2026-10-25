import json
import unittest
import load_adopted_route_plan as adopted
import load_route_plan_windows as windows


class RouteWindowLoadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cfg=json.loads((adopted.ROOT/'tools/config/adopted_route_plan.json').read_text(encoding='utf-8'))
        cls.manifest=adopted.build_plan(cfg)

    def test_sql_only_updates_disabled_metadata_and_is_idempotent(self):
        sql=windows.window_sql(self.manifest)
        self.assertIn('p.enabled=false',sql)
        self.assertIn('IS DISTINCT FROM',sql)
        self.assertIn('local_windows',sql)
        self.assertNotIn('enabled=true',sql)
        self.assertNotIn('guard_usage_daily',sql)
        self.assertNotRegex(sql,r'\b(CREATE|DROP|DELETE|TRUNCATE|INSERT)\b')

    def test_source_plan_must_stay_disabled(self):
        with self.assertRaises(adopted.w.InputError):
            windows.window_sql(dict(self.manifest,enabled=True))

    def test_quotes_and_midnight_are_preserved(self):
        self.assertEqual(windows.literal("a'b"),"'a''b'")
        self.assertIn('86400',windows.window_sql(self.manifest))
        self.assertEqual(self.manifest['budget']['total_calls'],8679)


if __name__=='__main__':
    unittest.main()
