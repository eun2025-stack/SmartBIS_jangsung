import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid
import local_collection_admin as admin
import local_collection_control as control
import collection_run_analysis as analysis
import observation_compare
from types import SimpleNamespace
from datetime import date

class LocalControlTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);(self.root/'.tmp').mkdir()
        values=dict(admin.SETTINGS,SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/smartbis_db_copy2',
                    SPRING_DATASOURCE_USERNAME='fixture',SPRING_DATASOURCE_PASSWORD='fixture',LOCAL_COLLECTION_STOP_AT='2026-11-08T12:00:00Z')
        self.env=self.root/'.tmp/local-collection.env'
        self.env.write_text(''.join(k+'='+v+'\n' for k,v in values.items()),encoding='utf-8')
        resources=self.root/'server/egov-backend/src/main/resources';resources.mkdir(parents=True)
        for name in ('application-local-collection.properties','rev17-routes.csv','rev17-departures.csv'):(resources/name).write_text('fixture')
        self.directory=self.root/'.tmp/control'
        patcher=patch.object(control,'commit_hash',return_value='a'*40);patcher.start();self.addCleanup(patcher.stop)
    def prepare(self):return control.prepare(self.directory,root=self.root)
    def test_prepare_creates_id_and_defaults_off_without_process_or_database(self):
        with patch.object(control.subprocess,'run',side_effect=AssertionError('external command')):
            run=self.prepare()
        self.assertNotEqual(uuid.UUID(run['collection_run_id']).int,0)
        self.assertEqual((self.directory/'enabled').read_text().strip(),'off')
        self.assertEqual(run['commit_hash'],'a'*40);self.assertEqual(len(run['config_hash']),64)
    def test_enable_needs_confirmed_gates_and_backend_prepared_identity(self):
        run=self.prepare()
        with self.assertRaises(ValueError):control.enable(run['collection_run_id'],False,self.directory,self.root)
        with self.assertRaises(ValueError):control.enable(run['collection_run_id'],True,self.directory,self.root)
        control.atomic_json(self.directory/'ready.json',{k:run[k] for k in ('collection_run_id','commit_hash','config_hash')})
        control.enable(run['collection_run_id'],True,self.directory,self.root)
        self.assertEqual((self.directory/'enabled').read_text().strip(),run['collection_run_id'])
    def test_old_descriptors_preserved_and_stale_ready_marker_rejected(self):
        old=self.prepare()
        with self.assertRaises(ValueError):self.prepare()
        new=control.prepare(self.directory,new_run=True,root=self.root)
        self.assertNotEqual(old['collection_run_id'],new['collection_run_id'])
        self.assertTrue((self.directory/'history'/(old['collection_run_id']+'.json')).exists())
        with self.assertRaises(ValueError):control.enable(old['collection_run_id'],True,self.directory,self.root)
    def test_config_change_cannot_reuse_run(self):
        self.prepare();self.env.write_text(self.env.read_text().replace('LOCAL_COLLECTION_STOP_AT=','UNUSED_KEY='))
        with self.assertRaises(ValueError):control.validated_run(self.directory,self.root)
    def test_compose_explicit_env_local_profile_no_dependencies_no_latest(self):
        args=control.compose_args(self.root)
        self.assertIn('--env-file',args);self.assertEqual(args[args.index('--env-file')+1],str(self.env))
        self.assertIn('--no-deps',args);self.assertEqual(args[-1],'local-collection-backend')
        self.assertNotIn('latest',' '.join(args))
    def test_analysis_requires_one_live_run_and_scopes_all_queries(self):
        run=str(uuid.uuid4())
        class Fake:
            def __init__(self):self.queries=[]
            def query(self,sql):self.queries.append(sql);return '1' if 'FROM collection_runs' in sql else '2'
        db=Fake();result=analysis.analyze(db,run)
        self.assertEqual(result['bus_positions_outside_pauses'],2)
        self.assertTrue(all(run in q for q in db.queries))
        self.assertTrue(all(q.lstrip().startswith('SELECT') for q in db.queries))
        with self.assertRaises(ValueError):analysis.analyze(db,str(uuid.UUID(int=0)))
    def test_six_settings_reject_mismatch_without_showing_value(self):
        self.env.write_text(self.env.read_text().replace('TAGO_ARRIVAL_ENABLED=true','TAGO_ARRIVAL_ENABLED=false'))
        with self.assertRaisesRegex(ValueError,'LOCAL_SIX_SETTINGS_MISMATCH'):self.prepare()
    def test_existing_judgement_loader_limits_run_before_readonly_query(self):
        run=str(uuid.uuid4())
        args=SimpleNamespace(collection_run_id=run,observations_csv=None,podman_container='smartbis_jangsung-postgres-db-1',db_name='smartbis_db_copy2',db_user='fixture',db_host=None,db_port=None)
        response=SimpleNamespace(returncode=0,stdout=b'vehicle_number,route_id,observed_at,node_order\n',stderr=b'')
        with patch.object(admin,'bounded',return_value=b'[{"Default":true,"URI":"ssh://fixture@127.0.0.1:22"}]'),patch.object(observation_compare.subprocess,'run',return_value=response) as call:
            self.assertEqual(observation_compare.load_observations(args,date(2026,11,3),date(2026,11,4)),[])
            sql=call.call_args.kwargs['input'].decode()
        self.assertIn(run,sql);self.assertIn('BEGIN TRANSACTION READ ONLY',sql);self.assertIn('collection_pause_intervals',sql)
        args.db_name='smartbis_db'
        with patch.object(observation_compare.subprocess,'run',side_effect=AssertionError('must not connect')):
            with self.assertRaises(observation_compare.InputError):observation_compare.load_observations(args,date(2026,11,3),date(2026,11,4))

if __name__=='__main__':unittest.main()
