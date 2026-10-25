import contextlib
import io
import json
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import load_adopted_route_plan as loader


class AdoptedPlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config=json.loads((loader.ROOT/'tools/config/adopted_route_plan.json').read_text(encoding='utf-8'))
        cls.manifest=loader.build_plan(cls.config)

    def test_adopted_counts_budget_disabled_and_full_backup_membership(self):
        m=self.manifest
        self.assertEqual(len(m['confirmed_departures']),293)
        self.assertEqual(len(m['backup_only_departures']),40)
        self.assertEqual(len(m['routes']),105)
        self.assertEqual(sum(r['route_group']=='100번' for r in m['routes']),17)
        self.assertEqual(m['budget']['total_calls'],8679)
        self.assertEqual((m['budget']['local_calls'],m['budget']['hundred_calls'],m['budget']['backup_calls']),(2058,1581,5040))
        self.assertTrue(all(not r['enabled'] and r['backup_sweep']['interval_seconds']==1800 and not r['backup_sweep']['enabled'] for r in m['routes']))
        self.assertTrue(all(not r['windows'] for r in m['routes'] if r['route_group']=='100번'))

    def test_plan_cannot_be_enabled_or_wrong_budget_silently_accepted(self):
        with self.assertRaises(loader.w.InputError):
            loader.build_plan(dict(self.config,enabled=True))
        with self.assertRaisesRegex(loader.w.InputError,'budget'):
            loader.build_plan(dict(self.config,post_trip_minutes=0))

    def test_insert_targets_route_summary_only_and_unchanged_upsert_is_noop(self):
        sql=loader.load_sql(self.manifest['routes'])
        self.assertIn('ON CONFLICT(route_id)',sql)
        self.assertIn('IS DISTINCT FROM',sql)
        self.assertIn('WHERE route_call_plan.enabled=false',sql)
        self.assertIn('Active plan found',sql)
        self.assertIn('Unrelated plan rows found',sql)
        self.assertNotRegex(sql.upper(),r'\b(DROP|DELETE|TRUNCATE|CREATE|ALTER)\b')
        self.assertNotIn('INSERT INTO guard_usage_daily',sql)
        self.assertNotIn('INSERT INTO vehicle_route_pattern_daily',sql)

    def test_duplicate_routes_and_enabled_rows_rejected(self):
        rows=deepcopy(self.manifest['routes'])
        rows[0]['enabled']=True
        with self.assertRaises(loader.w.InputError):
            loader.load_sql(rows)
        rows=deepcopy(self.manifest['routes']); rows[0]=rows[1]
        with self.assertRaises(loader.w.InputError):
            loader.load_sql(rows)

    def state(self,empty=False):
        rows=[] if empty else [dict(route_id=r['route_id'],route_group=r['route_group'],interval_seconds=r['interval_seconds'],
                                   enabled=False,updated_at='2026-10-23T23:00:00+09:00') for r in self.manifest['routes']]
        return dict(database='smartbis_db',columns=loader.TABLE_COLUMNS,rows=rows,flyway_schema_history=None,
                    counts=dict(vehicle_route_pattern_daily=0,route_call_plan=len(rows),guard_usage_daily=0))

    def test_second_load_same_rows_counts_and_zero_changes(self):
        with tempfile.TemporaryDirectory() as tmp:
            args=SimpleNamespace(config=str(loader.ROOT/'tools/config/adopted_route_plan.json'),output_dir=tmp,
                apply=True,verify_idempotency=True)
            with patch.object(loader,'db_state',side_effect=[self.state(True),self.state(),self.state()]),\
                 patch.object(loader,'psql',side_effect=[{'affected_rows':105},{'affected_rows':0}]) as db,\
                 patch.object(loader.integrity,'protected_hashes',return_value={'source':'unchanged'}),\
                 patch.object(loader.integrity,'git_status',return_value='existing dirty'),contextlib.redirect_stdout(io.StringIO()):
                loader.run(args)
            self.assertEqual(db.call_count,2)
            audit=json.loads((Path(tmp)/'load_audit.json').read_text(encoding='utf-8'))
            self.assertEqual(audit['first_load']['affected_rows'],105)
            self.assertEqual(audit['second_load']['affected_rows'],0)
            self.assertEqual(audit['after']['counts']['route_call_plan'],105)

    def test_dry_run_has_no_database_access(self):
        with tempfile.TemporaryDirectory() as tmp:
            args=SimpleNamespace(config=str(loader.ROOT/'tools/config/adopted_route_plan.json'),output_dir=tmp,apply=False)
            with patch.object(loader,'psql',side_effect=AssertionError('must not connect')),\
                 patch.object(loader.integrity,'protected_hashes',return_value={}),\
                 patch.object(loader.integrity,'git_status',return_value='dirty'),contextlib.redirect_stdout(io.StringIO()):
                loader.run(args)
            self.assertTrue((Path(tmp)/'route_plan_manifest.json').is_file())


if __name__=='__main__':
    unittest.main()
