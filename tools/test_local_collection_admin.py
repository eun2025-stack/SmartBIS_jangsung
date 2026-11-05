import unittest
from source_test_policy import source_material_test
from unittest.mock import patch
import local_collection_admin as admin

class LocalPlanTest(unittest.TestCase):
    @source_material_test
    def test_exact_disjoint_lane_sets(self):
        revision,plans=admin.plans()
        self.assertEqual((3,100,105),(sum(p['a'] for p in plans),sum(p['b'] for p in plans),len(plans)))
        self.assertFalse(any(p['a'] and p['b'] for p in plans))
        for suffix in ('006','074'):
            self.assertFalse(next(p for p in plans if p['route_id']=='TSB342000'+suffix)['b'])
    @source_material_test
    def test_plan_rows_have_windows_and_generation_is_repeatable(self):
        revision,rows=admin.plans()
        self.assertEqual(revision,admin.plans()[0])
        self.assertTrue(all(bool(r['windows'])==r['b'] for r in rows))
        sql=admin.plan_sql(revision,rows)
        self.assertIn('IF NOT EXISTS',sql);self.assertIn('deactivation_reason',sql)
        self.assertNotIn('DELETE',sql.upper());self.assertNotIn('ON CONFLICT',sql.upper())
    def test_production_and_remote_database_rejected_before_connection(self):
        for url in ('jdbc:postgresql://127.0.0.1:5432/smartbis_db','jdbc:postgresql://example.invalid:5432/smartbis_db_copy2'):
            with patch.object(admin,'bounded') as calls:
                with self.assertRaises(ValueError): admin.Database({'SPRING_DATASOURCE_URL':url})
                calls.assert_not_called()
    def test_local_copy_requires_credentials_without_printing_them(self):
        with self.assertRaises(ValueError): admin.validate({'SPRING_DATASOURCE_URL':'jdbc:postgresql://127.0.0.1:5432/smartbis_db_copy2'})

if __name__=='__main__': unittest.main()
