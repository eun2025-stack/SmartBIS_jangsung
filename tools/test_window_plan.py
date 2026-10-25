import ast
import contextlib
import csv
import io
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import window_plan as w


class WindowPlanTests(unittest.TestCase):
    def setUp(self):
        self.rules=w.Rules.load(Path(w.__file__).parent/'config'/'window_rules.csv')
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)

    def d(self,at='10:00',route='R1',duration=3600):
        return w.Departure(4,'기점',w.seconds(at),'1',duration,route_id=route)

    def test_window_start_end_and_intervals(self):
        windows=w.build_windows([self.d()],self.rules)['R1']
        self.assertEqual(windows[0][:2],(w.seconds('09:45'),w.seconds('11:10')))
        self.assertEqual(w.calls(*windows[0][:2],300),17)
        self.assertEqual(w.calls(*windows[0][:2],600),9)

    def test_business_values_are_configurable(self):
        self.rules.values.update(pre_departure_minutes='5',post_trip_minutes='20')
        windows=w.build_windows([self.d(duration=1800)],self.rules)['R1']
        self.assertEqual(windows[0][:2],(w.seconds('09:55'),w.seconds('10:50')))

    def test_overlap_merged_once_per_route(self):
        windows=w.build_windows([self.d('10:00'),self.d('10:30')],self.rules)['R1']
        self.assertEqual(len(windows),1)
        self.assertEqual(windows[0][:2],(w.seconds('09:45'),w.seconds('11:40')))
        self.assertEqual(windows[0][2],{'10:00','10:30'})

    def test_different_routes_never_merge(self):
        windows=w.build_windows([self.d(route='A'),self.d(route='B')],self.rules)
        self.assertEqual(set(windows),{'A','B'})
        self.assertEqual(sum(map(len,windows.values())),2)

    def test_midnight_at_both_ends(self):
        parts=w.split_midnight(-600,3600)
        self.assertEqual(parts,[(w.DAY-600,w.DAY),(0,3600)])
        parts=w.split_midnight(w.DAY-600,w.DAY+3600)
        self.assertEqual(parts,[(w.DAY-600,w.DAY),(0,3600)])
        self.assertEqual(w.clock(w.DAY),'24:00')
        windows=w.build_windows([self.d('23:50')],self.rules)['R1']
        self.assertEqual(len(windows),2)
        self.assertTrue(all(0<=start<end<=w.DAY for start,end,_ in windows))

    def test_half_open_calls_and_backup_budget(self):
        self.assertEqual(w.calls(0,600,600),1)
        rows=w.budget({300:1000,600:600},17,self.rules)
        by={(r['local_interval_seconds'],r['backup_interval_seconds']):r for r in rows}
        self.assertEqual(by[300,1200]['hundred_calls'],1581)
        self.assertEqual(by[300,1200]['backup_calls'],7560)
        self.assertEqual(by[300,1200]['total_calls'],10141)
        self.assertFalse(by[300,1200]['within_budget'])
        self.assertEqual(by[600,1800]['total_calls'],7221)
        self.assertTrue(by[600,1800]['retry_reserve_ok'])

    def test_retry_margin_inside_operating_budget(self):
        # 9500 - (2450 + 1581 + 5040) = 429: within budget but insufficient reserve.
        row=[r for r in w.budget({600:2450},17,self.rules) if r['backup_interval_seconds']==1800][0]
        self.assertTrue(row['within_budget'])
        self.assertFalse(row['retry_reserve_ok'])

    def test_hundred_is_not_mapped_into_local(self):
        mapping=({'x':{'H'}},{'1':{'H'}},{'H':'100'},{'H'},{'L'})
        mapped,failed=w.map_departures([self.d()],mapping,{})
        self.assertEqual(mapped,[])
        self.assertIn('100번',failed[0]['reason'])

    def test_pattern_exact_time_resolves_confirmed_route(self):
        mapping=({}, {'2':{'A','B'}}, {'A':'2','B':'2'},set(),{'A','B'})
        dep=w.Departure(4,'기점',w.seconds('10:00'),'2',3600)
        mapped,failed=w.map_departures([dep],mapping,{}, {('B',w.seconds('10:00'))})
        self.assertEqual(mapped[0].route_id,'B'); self.assertFalse(failed)
        mapped,failed=w.map_departures([dep],mapping,{}, {('B',w.seconds('10:01'))})
        self.assertFalse(mapped); self.assertEqual(len(failed),1)

    def test_explicit_keycode_override_not_first_match(self):
        mapping=({'chosen':{'B'}}, {'2':{'A','B'}}, {'A':'2','B':'2'},set(),{'A','B'})
        dep=w.Departure(4,'종점',w.seconds('10:00'),'2',3600)
        mapped,failed=w.map_departures([dep],mapping,{(4,'종점',w.seconds('10:00')):'chosen'})
        self.assertEqual(mapped[0].route_id,'B'); self.assertFalse(failed)

    def test_workbook_time_parsing_duration_and_no_distance_as_minutes(self):
        row=[1,'장성',None,'종점',12,2,None,'10:00\n09:00(경유)',None]
        dep,warnings,audits=w.parse_departures([[None]*9]*3+[row],self.rules)
        self.assertEqual(len(dep),2)
        self.assertEqual(dep[0].trip_seconds,3600)
        self.assertTrue(any('역전' in item for item in warnings))
        self.rules.values['distance_speed_kmh']='24'
        dep,_,_=w.parse_departures([[None]*9]*3+[row],self.rules)
        self.assertEqual(dep[0].trip_seconds,1800)

    def test_actual_sheet_has_333_departures(self):
        root=Path(w.__file__).parents[1]
        path=root/'장성교통 노선도'/'군민운수 자료'/'노선(사이텍).xlsx'
        dep,warnings,audits=w.read_workbook(path,'관내',self.rules)
        self.assertEqual(len(dep),333)
        self.assertTrue(any('노선 70:' in a for a in audits))

    def file(self,name,fields,rows):
        path=self.root/name
        w.write_csv(path,rows,fields)
        return path

    def args(self):
        self.rules.values.update(expected_hundred_routes='17',expected_local_routes='1',expected_departures='1')
        rule_path=self.file('rules.csv',['key','value'],[dict(key=k,value=v) for k,v in self.rules.values.items()])
        mapping=self.file('mapping.csv',['ROUTE_ID','ROUTE_NM','OBE_NM'],
                          [dict(ROUTE_ID='TSB999000001',ROUTE_NM='1',OBE_NM='key')]+
                          [dict(ROUTE_ID=f'TSB342000{n:03d}',ROUTE_NM='예시100',OBE_NM=f'key{n}') for n in range(101,118)])
        return SimpleNamespace(rules=rule_path,workbook=self.root/'not-written.xlsx',sheet='관내',mapping=mapping,
                               departure_keys=None,patterns=None,output_dir=self.root/'out')

    def test_unresolved_excluded_and_backup_only(self):
        args=self.args()
        dep=w.Departure(4,'기점',w.seconds('10:00'),'unknown',3600)
        with patch.object(w,'read_workbook',return_value=([dep],[],[])), patch.object(w,'build_windows',wraps=w.build_windows) as builder:
            with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(w.run(args),0)
            self.assertEqual(builder.call_args.args[0],[])
        self.assertTrue((args.output_dir/'mapping_failures.csv').is_file())
        self.assertTrue((args.output_dir/'budget_check.csv').exists())
        self.assertEqual(w.read_csv(args.output_dir/'window_plan.csv'),[])
        unresolved=w.read_csv(args.output_dir/'unresolved_departures.csv')
        self.assertEqual(unresolved[0]['collection_mode'],'백업 스윕 전용')
        self.assertEqual(unresolved[0]['candidate_keycodes'],'')
        self.assertEqual(unresolved[0]['candidate_stop_order_match_pct'],'')
        self.assertEqual(sum(int(r['unresolved_departure_count']) for r in w.read_csv(args.output_dir/'window_summary.csv')),1)

    def test_departure_total_mismatch_stops(self):
        args=self.args()
        with patch.object(w,'read_workbook',return_value=([self.d(),self.d()],[],[])):
            with self.assertRaisesRegex(w.InputError,'Departure total mismatch'):
                w.run(args)
        self.assertFalse(args.output_dir.exists())

    def test_unresolved_cannot_build_windows(self):
        with self.assertRaisesRegex(w.InputError,'Unresolved'):
            w.build_windows([self.d(route='')],self.rules)

    def test_concurrency_half_open_unique_routes(self):
        windows={'A':[(0,600,set()),(600,1200,set())],'B':[(600,900,set())]}
        result=w.concurrency(windows)
        self.assertEqual([(r['time'],r['active_route_count']) for r in result],
                         [('00:00',1),('00:10',2),('00:15',1),('00:20',0)])

    def test_stop_order_score_is_not_set_overlap_or_automatic_assignment(self):
        self.assertEqual(w.sequence_score(['a','b','c'],['a','b','c']),100)
        self.assertEqual(w.sequence_score(['a','b','c'],['c','b','a']),33.33)
        self.assertEqual(w.sequence_score([],['a']),'')
        mapping=({'20':{'A'},'21':{'B'}},{'2':{'A','B'}},{'A':'2','B':'2'},set(),{'A','B'})
        mapped,failed=w.map_departures([w.Departure(4,'종점',w.seconds('10:00'),'2',3600)],mapping,{})
        rows=w.unresolved_details(failed,mapping,'unused','unused')
        self.assertFalse(mapped)
        self.assertEqual(rows[0]['candidate_keycodes'],'20|21')
        self.assertEqual(rows[0]['candidate_stop_order_match_pct'],'20:A=|21:B=')

    def test_actual_confirmed_266_unresolved_67_and_no_leak(self):
        root=Path(w.__file__).parents[1]
        mapping=w.load_mapping(root/'군민운수 운행관리시스템'/'routeid&kecode_matching_ts.csv',self.rules)
        trips=w.load_pattern_trips(root/'군민운수 운행관리시스템'/'관내 운행 패턴.csv',mapping)
        departures,_,_=w.read_workbook(root/'장성교통 노선도'/'군민운수 자료'/'노선(사이텍).xlsx','관내',self.rules)
        mapped,failed=w.map_departures(departures,mapping,{},trips)
        self.assertEqual((len(mapped),len(failed)),(266,67))
        self.assertEqual(len(mapped)+len(failed),333)
        failed_keys={(r['sheet_row'],r['side'],r['departure_time']) for r in failed}
        self.assertFalse({(d.sheet_row,d.side,w.clock(d.departure)) for d in mapped} & failed_keys)
        self.assertTrue(set(w.build_windows(mapped,self.rules)) <= mapping[4])

    def test_successful_outputs_are_bom_and_only_output_directory(self):
        args=self.args()
        dep=w.Departure(4,'기점',w.seconds('10:00'),'1',3600)
        with patch.object(w,'read_workbook',return_value=([dep],[],[])),contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(w.run(args),0)
        for name in ('window_plan.csv','window_summary.csv','budget_check.csv','summary.txt','concurrency.csv','unresolved_departures.csv'):
            self.assertTrue((args.output_dir/name).read_bytes().startswith(b'\xef\xbb\xbf'))
        rows=w.read_csv(args.output_dir/'window_summary.csv')
        self.assertEqual(rows[0]['calls_300s'],'17')
        self.assertEqual(rows[0]['calls_600s'],'9')

    def test_actual_mapping_has_17_hundred_and_88_local_without_prefix_rule(self):
        path=Path(w.__file__).parents[1]/'군민운수 운행관리시스템'/'routeid&kecode_matching_ts.csv'
        _,_,_,hundred,local=w.load_mapping(path,self.rules)
        self.assertEqual(len(hundred),17); self.assertEqual(len(local),88)
        self.assertIn('TSB342000117',hundred)
        self.assertFalse(hundred & local)

    def test_missing_required_file_raises_without_substitution(self):
        with self.assertRaisesRegex(w.InputError,'Required timetable not found'):
            w.read_workbook(self.root/'missing.xlsx','관내',self.rules)

    def test_confirmed_mapping_only_adds_original_unresolved(self):
        mapping=({'20':{'A'},'21':{'B'}},{'2':{'A','B'}},{'A':'2','B':'2'},set(),{'A','B'})
        d=w.Departure(6,'종점',w.seconds('10:00'),'2',3600)
        _,failures=w.map_departures([d],mapping,{})
        row=dict(sheet_row=6,route_name='2',direction='종점',departure_time='10:00',keycode='20',route_id='A',grade='확정 후보')
        path=self.file('confirmed.csv',list(row),[row])
        overrides=w.confirmed_mapping_overrides(path,failures,mapping)
        mapped,failed=w.map_departures([d],mapping,overrides)
        self.assertEqual(mapped[0].route_id,'A'); self.assertFalse(failed)
        with self.assertRaises(w.InputError):
            w.confirmed_mapping_overrides(path,[],mapping)
        self.file('confirmed.csv',list(row),[dict(row,grade='모호')])
        with self.assertRaises(w.InputError):
            w.confirmed_mapping_overrides(path,failures,mapping)

    def test_no_http_db_or_process_imports(self):
        tree=ast.parse(Path(w.__file__).read_text(encoding='utf-8'))
        imports={node.module.split('.')[0] for node in ast.walk(tree) if isinstance(node,ast.ImportFrom) and node.module}
        imports|={name.name.split('.')[0] for node in ast.walk(tree) if isinstance(node,ast.Import) for name in node.names}
        self.assertFalse(imports & {'requests','urllib','http','socket','psycopg','psycopg2','sqlite3','subprocess'})


if __name__=='__main__':
    unittest.main()
