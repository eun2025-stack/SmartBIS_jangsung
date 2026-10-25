import csv
import io
import re
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import date, datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import observation_compare as c


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.day = date(2026,10,16)
        self.local = 'TSB342000001'
        self.other = 'TSB342000002'
        self.bus = 'TSB342000102'
        self.hundred = {self.bus, 'TSB342000117'} | {f'TSB342000{n:03d}' for n in range(201,216)}
        self.calendar_path = Path(c.__file__).parent/'config'/'holidays_2026.csv'

    def file(self,name,rows):
        path = self.root/name
        with path.open('w',encoding='utf-8-sig',newline='') as f:
            csv.writer(f).writerows(rows)
        return path

    def index(self,rows):
        return c.ObservationIndex([c.Observation(v,r,datetime.fromisoformat(t),'') for v,r,t in rows])

    def mapping_file(self,name,rows):
        existing = {c.route_id(row[0]) for row in rows}
        return self.file(name,[['ROUTE_ID','ROUTE_NM','OBE_NM']]+rows+
                         [[r,'예시100','key'+r] for r in sorted(self.hundred-existing)])

    def test_boundary_and_same_vehicle(self):
        idx = self.index([('7500',self.local,'2026-10-16T09:45:00+09:00'),
                          ('7501',self.local,'2026-10-16T10:00:00+09:00')])
        exp = c.Expected(600,self.local)
        self.assertEqual(c.classify(idx,'7500',self.day,exp,15,{self.local})[0],c.MATCH)
        self.assertEqual(c.classify(idx,'7502',self.day,exp,15,{self.local})[0],c.MISSING)
        idx = self.index([('7500',self.local,'2026-10-16T10:15:01+09:00')])
        self.assertEqual(c.classify(idx,'7500',self.day,exp,15,{self.local})[0],c.MISSING)

    def test_other_route_and_match_priority(self):
        idx = self.index([('7500',self.other,'2026-10-16T10:00:00+09:00')])
        self.assertEqual(c.classify(idx,'7500',self.day,c.Expected(600,self.local),15,{self.local,self.other})[0],c.WRONG)
        idx = self.index([('7500',r,'2026-10-16T10:00:00+09:00') for r in (self.other,self.local)])
        self.assertEqual(c.classify(idx,'7500',self.day,c.Expected(600,self.local),15,{self.local,self.other})[0],c.MATCH)

    def test_route_family_not_vehicle_family(self):
        idx = self.index([('7500',self.bus,'2026-10-16T10:00:00+09:00')])
        self.assertEqual(c.classify(idx,'7500',self.day,c.Expected(600,self.local),15,{self.local})[0],c.MISSING)
        self.assertEqual(c.classify(idx,'7500',self.day,c.Expected(600,self.bus),15,self.hundred)[0],c.MATCH)
        self.assertEqual(len(self.hundred),17)

    def test_consecutive_absence_only_same_assignment(self):
        idx = self.index([('9999',self.local,f'2026-10-{d}T10:00:00+09:00') for d in (15,16)])
        args = (idx,'7500',self.day,'ud1',[c.Expected(600,self.local)])
        assigned = {('7500',self.day):'ud1'}
        self.assertEqual(c.evaluate(*args,assigned,15,{self.local})[1][0]['status'],c.MISSING)
        assigned[('7500',date(2026,10,15))] = 'ud1'
        self.assertEqual(c.evaluate(*args,assigned,15,{self.local})[1][0]['status'],c.REPEATED)
        assigned[('7500',date(2026,10,15))] = 'ud2'
        self.assertEqual(c.evaluate(*args,assigned,15,{self.local})[1][0]['status'],c.MISSING)

    def test_no_prior_day_dataset_does_not_promote(self):
        idx = self.index([('9999',self.local,'2026-10-16T10:00:00+09:00')])
        assigned = {('7500',d):'ud1' for d in (self.day,date(2026,10,15))}
        self.assertEqual(c.evaluate(idx,'7500',self.day,'ud1',[c.Expected(600,self.local)],assigned,15,{self.local})[1][0]['status'],c.MISSING)

    def test_previous_match_prevents_promotion(self):
        idx = self.index([('7500',self.local,'2026-10-15T10:00:00+09:00')])
        assigned = {('7500',d):'ud1' for d in (self.day,date(2026,10,15))}
        self.assertEqual(c.evaluate(idx,'7500',self.day,'ud1',[c.Expected(600,self.local)],assigned,15,{self.local})[1][0]['status'],c.MISSING)

    def test_mapping_typo_keycode_and_missing(self):
        path = self.mapping_file('mapping.csv',[['TBS342000001','1','10']])
        mapping,routes,hundred = c.load_mapping(path)
        p = self.file('patterns.csv',[['div','patt','time','route'],['ud','ud1','10:00','10']])
        self.assertEqual(c.load_patterns([p],mapping,routes,hundred)[0]['ud1'][0].route,self.local)
        p = self.file('bad.csv',[['patt','time','route'],['ud1','10:00','999']])
        with self.assertRaisesRegex(c.InputError,'Mapping failures'):
            c.load_patterns([p],mapping,routes,hundred,True)
        p = self.file('typo.csv',[['patt','time','route'],['ud1','10:00','TBS342000001']])
        self.assertEqual(c.load_patterns([p],mapping,routes,hundred)[0]['ud1'][0].route,self.local)

    def test_ambiguous_keycode_stops(self):
        p = self.file('patterns.csv',[['patt','time','route'],['ud1','10:00','10']])
        with self.assertRaisesRegex(c.InputError,'ambiguous'):
            c.load_patterns([p],{'10':{self.local,self.other}},{self.local,self.other},self.hundred)

    def test_dispatch_sections_rest_and_conflicts(self):
        p = self.file('dispatch.csv',[['장성팀','15','16'],['7521[터]','hjs','hjs1'],['광주팀','15','16'],['7501','gj1','gj2']])
        result = c.load_assignments([p],'2026-10',{'hjs1':[], 'gj1':[], 'gj2':[]})
        self.assertEqual(result[('7521',date(2026,10,15))],'휴차')
        self.assertEqual(result[('7521',self.day)],'hjs1')
        bad = self.file('conflict.csv',[['car','16'],['7501','gj1']])
        with self.assertRaisesRegex(c.InputError,'Conflicting'):
            c.load_assignments([p,bad],'2026-10',{'hjs1':[], 'gj1':[], 'gj2':[]})

    def test_null_zero_order_and_timezone(self):
        rows = [dict(vehicle_number='전남76아7500',route_id=self.local,observed_at='2026-10-16T01:00:00Z',node_order=n) for n in ('', '0')]
        obs = c.parse_observations(rows)
        self.assertEqual([o.node_order for o in obs],['','0'])
        self.assertEqual(obs[0].at.hour,10)

    def test_suffix_collision_stops(self):
        rows = [dict(vehicle_number=v,route_id=self.local,observed_at='2026-10-16T10:00+09:00') for v in ('전남76아7500','전남77아7500')]
        with self.assertRaisesRegex(c.InputError,'sharing'):
            c.parse_observations(rows)

    def test_midnight_window(self):
        idx = self.index([('7500',self.local,'2026-10-15T23:55:00+09:00')])
        self.assertEqual(c.classify(idx,'7500',self.day,c.Expected(5,self.local),15,{self.local})[0],c.MATCH)

    def test_incomplete_strict_and_diagnostic(self):
        p = self.file('incomplete.csv',[['patt','time','route','time','route'],['ud1','10:00',self.local,'11:00','']])
        with self.assertRaisesRegex(c.InputError,'Unpaired'):
            c.load_patterns([p],{}, {self.local},self.hundred)
        patterns,_,excluded,_ = c.load_patterns([p],{}, {self.local},self.hundred,True)
        self.assertEqual(len(patterns['ud1']),1)
        self.assertEqual(len(excluded),1)

    def test_readonly_query(self):
        args = SimpleNamespace(observations_csv=None,podman_container='test-db',db_user='u',db_name='d',db_host=None,db_port=None)
        response = SimpleNamespace(returncode=0,stdout=b'vehicle_number,route_id,observed_at,node_order\n',stderr=b'')
        with patch.object(c.subprocess,'run',return_value=response) as run:
            self.assertEqual(c.load_observations(args,self.day,date(2026,10,17)),[])
        sql = run.call_args.kwargs['input'].decode()
        self.assertIn('BEGIN TRANSACTION READ ONLY;',sql)
        self.assertIn('ROLLBACK;',sql)
        self.assertIn("source = 'TAGO_OBSERVATION'",sql)
        self.assertNotRegex(sql,r'\b(INSERT|UPDATE|DELETE|CREATE|DROP)\b')

    def test_end_to_end_csv_and_bom(self):
        mapping = self.mapping_file('m.csv',[[self.local,'1','10'],[self.bus,'100','1000']])
        patterns = self.file('p.csv',[['patt','time','route'],['ud1','10:00','10'],['js1','10:00','1000']])
        dispatch = self.file('d.csv',[['car','16'],['7500','ud1']])
        obs = self.file('o.csv',[['vehicle_number','route_id','observed_at','node_order'],['전남76아7500',self.local,'2026-10-16T10:00:00+09:00','0'],['전남76아7501',self.bus,'2026-10-16T10:00:00+09:00','']])
        args = SimpleNamespace(start_date='2026-10-16',end_date=None,assignment_month='2026-10',mapping=mapping,
                               patterns=[patterns],dispatch=[dispatch],observations_csv=obs,output_dir=self.root/'out',window_minutes=15,skip_incomplete=False,
                               dispatch_confirmed=True,include_tago=False,holiday_calendar=self.calendar_path)
        with redirect_stdout(io.StringIO()):
            daily = c.compare(args)
        self.assertEqual(daily[0]['match_rate_pct'],100)
        unassigned = c.read_csv(self.root/'out/unassigned.csv')
        self.assertEqual(unassigned[1][0],'7501')
        for name in ('vehicle_daily.csv','pattern_candidates.csv','recommendations.csv','mismatches.csv'):
            self.assertTrue((self.root/'out'/name).read_bytes().startswith(b'\xef\xbb\xbf'))

    def comparison_args(self, **changes):
        mapping = self.mapping_file('all-mapping.csv',[[self.local,'1','10'],[self.bus,'100','1000']])
        patterns = self.file('all-patterns.csv',[['patt','time','route'],['ud1','10:00','10'],
                             ['js7','10:00','1000','20:30','TSB342000117'],
                             ['gj1','10:00','1000'],['hjs7','10:00','1000','20:30','TSB342000117'],['hgj1','10:00','1000']])
        dispatch = self.file('all-dispatch.csv',[['car','16','17','18','19'],['7500','ud1','ud1','ud1','ud1']])
        observations = self.file('all-observations.csv',[['vehicle_number','route_id','observed_at','node_order','source'],
                             ['전남76아7500',self.local,'2026-10-16T10:00+09:00','0','TAGO_OBSERVATION'],
                             ['전남76아7500',self.bus,'2026-10-17T10:00+09:00','','TAGO_OBSERVATION'],
                             ['전남76아7500',self.bus,'2026-10-15T10:00+09:00','','TAGO']])
        args = SimpleNamespace(start_date='2026-10-16',end_date='2026-10-19',assignment_month='2026-08',
                               mapping=mapping,patterns=[patterns],dispatch=[dispatch],observations_csv=observations,
                               output_dir=self.root/'comparison',window_minutes=15,skip_incomplete=False,
                               dispatch_confirmed=False,include_tago=False,holiday_calendar=self.calendar_path)
        for key,value in changes.items():
            setattr(args,key,value)
        return args

    def test_interpolation_export_preserves_input_and_actual_counts(self):
        args=self.comparison_args()
        args.observations_csv=self.file('gap.csv',[
            ['vehicle_number','route_id','observed_at','node_order','source'],
            ['전남76아7500',self.local,'2026-10-16T10:00:00+09:00','3','TAGO_OBSERVATION'],
            ['전남76아7500',self.local,'2026-10-16T10:08:00+09:00','7','TAGO_OBSERVATION']])
        before=args.observations_csv.read_bytes()
        args.route_stops=self.file('stops.csv',[['route_id','nodeord_raw']]+[[self.local,n] for n in range(1,11)])
        self.compare_quiet(args)
        self.assertEqual(args.observations_csv.read_bytes(),before)
        path=args.output_dir/'observations_with_interpolation.csv'
        rows=list(csv.DictReader(io.StringIO(path.read_text(encoding='utf-8-sig'))))
        self.assertEqual(len(rows),5)
        self.assertEqual([r['node_order'] for r in rows if r['interpolated']=='true'],['4','5','6'])
        self.assertEqual([r['node_order'] for r in rows if r['interpolated']=='false'],['3','7'])
        days=list(csv.DictReader(io.StringIO((args.output_dir/'day_summary.csv').read_text(encoding='utf-8-sig'))))
        self.assertEqual(days[0]['observation_rows'],'2')
        self.assertEqual(days[-1]['status'],'관측 없음')
        rates=list(csv.DictReader(io.StringIO((args.output_dir/'route_collection_rates.csv').read_text(encoding='utf-8-sig'))))
        self.assertEqual(len(rates),2)
        self.assertTrue(all(r['observed_stop_count']=='2' and r['collection_rate_pct']=='20.0' for r in rates))
        self.assertIn('2/10 20.0%',(args.output_dir/'summary.txt').read_text(encoding='utf-8-sig'))
        for name in ('observations_with_interpolation.csv','unverified_segments.csv'):
            self.assertTrue((args.output_dir/name).read_bytes().startswith(b'\xef\xbb\xbf'))

    def compare_quiet(self,args):
        with redirect_stdout(io.StringIO()):
            return c.compare(args)

    def test_actual_mapping_17_includes_117(self):
        path = Path(c.__file__).parents[1]/'군민운수 운행관리시스템'/'routeid&kecode_matching_ts.csv'
        _,_,hundred = c.load_mapping(path)
        self.assertEqual(len(hundred),17)
        self.assertIn('TSB342000117',hundred)

    def test_mapping_count_and_typo_aggregate(self):
        p = self.mapping_file('count.csv',[[self.local,'1','10'],['TBS342000117','비아100','600']])
        _,_,hundred = c.load_mapping(p)
        self.assertEqual(len(hundred),17)
        self.assertIn('TSB342000117',hundred)
        rows = c.read_csv(p)
        p = self.file('short.csv',rows[:-1])
        with self.assertRaisesRegex(c.InputError,'16개'):
            c.load_mapping(p)

    def test_2026_holidays(self):
        cal = c.load_calendar(self.calendar_path)
        for value in ('2026-08-15','2026-08-17','2026-10-05','2026-10-09','2026-10-17','2026-10-18','2026-06-03'):
            self.assertTrue(cal.is_holiday(date.fromisoformat(value)),value)
        for value in ('2026-08-18','2026-10-16'):
            self.assertFalse(cal.is_holiday(date.fromisoformat(value)),value)
        self.assertEqual(len(cal.holidays),20)
        self.assertTrue(self.calendar_path.read_bytes().startswith(b'\xef\xbb\xbf'))

    def test_calendar_values_not_hardcoded(self):
        p = self.file('custom.csv',[['date','name','kind'],['2026-10-16','설정 휴일','사용자']])
        cal = c.load_calendar(p)
        self.assertTrue(cal.is_holiday(self.day))
        self.assertFalse(cal.is_holiday(date(2026,8,17)))

    def test_weekday_holiday_candidates_and_117_expectations(self):
        args = self.comparison_args()
        self.compare_quiet(args)
        rows = list(csv.DictReader(io.StringIO((args.output_dir/'expected_schedule.csv').read_text(encoding='utf-8-sig'))))
        for row in rows:
            if row['date']=='2026-10-16':
                self.assertFalse(row['pattern'].startswith(('hjs','hgj')))
            if row['date']=='2026-10-17':
                self.assertFalse(row['pattern'].startswith(('js','gj')))
        self.assertTrue(any(r['pattern']=='js7' and r['route_id']=='TSB342000117' for r in rows))
        self.assertTrue(any(r['pattern']=='hjs7' and r['route_id']=='TSB342000117' for r in rows))
        candidates = c.read_csv(args.output_dir/'pattern_candidates.csv')
        self.assertFalse(any(r[1]=='2026-10-16' and r[4].startswith(('hjs','hgj')) for r in candidates[1:]))

    def test_local_is_mapping_minus_hundred_not_prefix(self):
        p = self.file('non342.csv',[['patt','time','route'],['ud1','10:00','TSB999000001','11:00',self.bus]])
        patterns,local,excluded,_ = c.load_patterns([p],{}, {'TSB999000001',self.bus},self.hundred)
        self.assertEqual(local,{'TSB999000001'})
        self.assertEqual(patterns['ud1'][0].route,'TSB999000001')
        self.assertEqual(excluded[0]['reason'],'범위 밖 노선(관내 패턴의 100번 계열 항목)')

    def test_october_requires_month_and_confirmation(self):
        for month,confirmed in (('2026-08',False),('2026-10',False),('2026-08',True)):
            args = self.comparison_args(assignment_month=month,dispatch_confirmed=confirmed)
            daily = self.compare_quiet(args)
            self.assertTrue(all(r['match_rate_pct']=='' for r in daily))
            self.assertTrue(all(r['assignment_status']=='배차 미확정' for r in daily))
            self.assertTrue(all(r['status']=='배차 미확정' for r in daily if r['date']=='2026-10-16'))
            self.assertEqual(len(c.read_csv(args.output_dir/'mismatches.csv')),1)
        args = self.comparison_args(assignment_month='2026-10',dispatch_confirmed=True)
        daily = self.compare_quiet(args)
        self.assertEqual(daily[0]['match_rate_pct'],100)

    def test_no_observation_day_skipped(self):
        args = self.comparison_args()
        daily = self.compare_quiet(args)
        self.assertTrue(all(r['status']=='관측 없음' and r['match_rate_pct']=='' for r in daily if r['date']=='2026-10-19'))
        for name in ('pattern_candidates.csv','expected_results.csv','mismatches.csv'):
            self.assertNotIn('2026-10-19', (args.output_dir/name).read_text(encoding='utf-8-sig'))
        self.assertIn('2026-10-19 평일: 관측 없음',(args.output_dir/'summary.txt').read_text(encoding='utf-8-sig'))

    def test_include_tago_is_opt_in(self):
        row = dict(vehicle_number='전남76아7500',route_id=self.local,observed_at='2026-10-15T10:00+09:00',source='TAGO')
        self.assertEqual(c.parse_observations([row]),[])
        self.assertEqual(len(c.parse_observations([row],True)),1)
        args = self.comparison_args(start_date='2026-10-15',include_tago=True)
        self.compare_quiet(args)
        self.assertIn('TAGO (참고용)',(args.output_dir/'summary.txt').read_text(encoding='utf-8-sig'))

    def test_reversed_time_warns_and_continues(self):
        p = self.file('js9.csv',[['patt','time','route','time','route'],['js9','09:55',self.bus,'01:10',self.bus]])
        patterns,_,_,warnings = c.load_patterns([p],{}, {self.bus},self.hundred)
        self.assertEqual(patterns['js9'][0].minute,70)
        self.assertIn('시간 역전',warnings[0])
        args = self.comparison_args()
        args.patterns = [p]
        args.dispatch = [self.file('js9-dispatch.csv',[['car','16'],['7500','js9']])]
        self.compare_quiet(args)
        self.assertIn('시간 역전',(args.output_dir/'summary.txt').read_text(encoding='utf-8-sig'))
        self.assertTrue((args.output_dir/'recommendations.csv').exists())

    def test_ud5_missing_route_strict_and_diagnostic_outputs(self):
        args = self.comparison_args()
        p = self.file('ud5-blank.csv',[['patt','time','route','time','route'],['ud5','10:00',self.local,'20:00','']])
        args.patterns = [p]
        args.dispatch = [self.file('ud5-dispatch.csv',[['car','16'],['7500','ud5']])]
        original = p.read_bytes()
        with self.assertRaisesRegex(c.InputError,'Unpaired'):
            self.compare_quiet(args)
        args.skip_incomplete = True
        self.compare_quiet(args)
        rows = c.read_csv(args.output_dir/'excluded_expectations.csv')
        self.assertTrue(any(r[1]=='ud5' and r[2]=='20:00' and '제외' in r[4] for r in rows[1:]))
        self.assertTrue(all(re.fullmatch(r'2026-10-\d{2}',r[0]) for r in rows[1:]))
        self.assertIn('실행 유형: 진단',(args.output_dir/'summary.txt').read_text(encoding='utf-8-sig'))
        self.assertEqual(original,p.read_bytes())

    def test_schedule_check_no_db_and_conflict_stops(self):
        args = self.comparison_args()
        args.dispatch = [self.file('aug.csv',[['car','15','17','18'],['7500','hjs7','hjs7','js7']])]
        args.output_dir = self.root/'check'
        with patch.object(c,'load_observations',side_effect=AssertionError('DB must not be read')):
            with redirect_stdout(io.StringIO()):
                c.check_schedule(args)
        self.assertIn('배차 상충: 0건',(args.output_dir/'summary.txt').read_text(encoding='utf-8-sig'))
        self.assertFalse((args.output_dir/'vehicle_daily.csv').exists())
        args.dispatch = [self.file('aug-conflict.csv',[['car','18'],['7500','hjs7']])]
        with redirect_stdout(io.StringIO()), self.assertRaisesRegex(c.InputError,'배차 상충'):
            c.check_schedule(args)


class InterpolationTests(unittest.TestCase):
    def test_origin_is_catalog_minimum_and_missing_nodeid_is_diagnostic_only(self):
        day=date(2026,10,16); at=datetime.fromisoformat('2026-10-16T10:00:00+09:00')
        original=[c.Observation('7500','R',at,'5'),c.Observation('7500','R',at,'6')]
        lines=c.origin_review_lines(original,{'R':{5,6,7}},day,day)
        self.assertTrue(any('기점 좌표 검토 필요: 1행' in s for s in lines))
        lines=c.origin_review_lines(original,{'R':{0,5,6,7}},day,day)
        self.assertTrue(any('기점 좌표 검토 필요: 0행' in s for s in lines))
        lines=c.origin_review_lines([c.Observation('7500','R',at,'5',node_id='NODE')],{'R':{5,6}},day,day)
        self.assertTrue(any('기점 좌표 검토 필요: 0행' in s for s in lines))
        self.assertEqual(c.ObservationIndex(original).rows['7500'],original)

    def test_nodeid_alias_preserved(self):
        row=dict(vehicle_number='전남76아7500',route_id='TSB342000001',observed_at='2026-10-16T10:00:00+09:00',node_order='0',nodeid='NODE')
        self.assertEqual(c.parse_observations([row])[0].node_id,'NODE')

    def obs(self,order,at='10:00',route='TSB342000001',vehicle='7500',flag=False):
        return c.Observation(vehicle,route,datetime.fromisoformat(f'2026-10-16T{at}:00+09:00'),str(order),interpolated=flag)

    def run_gap(self,left,right,rules=None):
        return c.interpolate_observations([left,right],rules or c.InterpolationRules(5,30))

    def test_3_to_7_generates_4_5_6_linear_times(self):
        rows,unknown=self.run_gap(self.obs(3),self.obs(7,'10:08'))
        generated=[o for o in rows if o.interpolated]
        self.assertEqual([o.node_order for o in generated],['4','5','6'])
        self.assertEqual([o.at.strftime('%H:%M') for o in generated],['10:02','10:04','10:06'])
        self.assertFalse(unknown)

    def test_decreasing_order_no_interpolation(self):
        rows,unknown=self.run_gap(self.obs(7),self.obs(3,'10:08'))
        self.assertEqual(len(rows),2)
        self.assertEqual(unknown[0]['reason'],'node_order 감소')

    def test_route_switch_no_interpolation_and_no_bridge(self):
        original=[self.obs(3),self.obs(4,'10:03',route='TSB342000002'),self.obs(7,'10:08')]
        rows,unknown=c.interpolate_observations(original,c.InterpolationRules(10,30))
        self.assertEqual(len(rows),3)
        self.assertEqual(len(unknown),2)

    def test_limits_and_exact_boundary(self):
        for right,reason in [(self.obs(15,'10:10'),'max_gap_stops 초과'),
                              (self.obs(7,'10:31'),'max_gap_minutes 초과')]:
            rows,unknown=self.run_gap(self.obs(3),right)
            self.assertEqual(len(rows),2)
            self.assertEqual(unknown[0]['status'],'구간 미확인')
            self.assertEqual(unknown[0]['reason'],reason)
        rows,unknown=self.run_gap(self.obs(3),self.obs(9,'10:30'))
        self.assertEqual(sum(o.interpolated for o in rows),5)
        self.assertFalse(unknown)

    def test_originals_preserved_including_duplicate_and_blank(self):
        original=[self.obs(3),self.obs(3),self.obs(7,'10:08'),self.obs('','10:09')]
        before=tuple(original)
        rows,_=c.interpolate_observations(original,c.InterpolationRules(10,30))
        self.assertEqual(tuple(original),before)
        self.assertEqual([o for o in rows if not o.interpolated],original)
        self.assertTrue(all(any(o is r for r in rows) for o in original))

    def test_interpolation_not_used_for_classification_or_observed_days(self):
        left,right=self.obs(3),self.obs(7,'10:08')
        rows,_=self.run_gap(left,right)
        index=c.ObservationIndex(rows)
        at=c.Expected(10*60+4,left.route)
        self.assertEqual(c.classify(index,left.vehicle,left.at.date(),at,0,{left.route})[0],c.MISSING)
        inferred=c.ObservationIndex([o for o in rows if o.interpolated])
        self.assertEqual(inferred.observed_days,set())
        self.assertEqual(c.classify(index,left.vehicle,left.at.date(),c.Expected(600,left.route),0,{left.route})[0],c.MATCH)

    def test_zero_missing_orders_equal_time_and_vehicle_isolation(self):
        rows,_=self.run_gap(self.obs(0),self.obs(2,'10:02'))
        self.assertEqual([o.node_order for o in rows if o.interpolated],['1'])
        for a,b in [(self.obs(3),self.obs(7)),(self.obs(''),self.obs(7,'10:08')),
                    (self.obs(3),self.obs(7,'10:08',vehicle='7501'))]:
            rows,_=self.run_gap(a,b)
            self.assertFalse(any(o.interpolated for o in rows))

    def test_config_defaults_and_no_recursive_interpolation(self):
        rules=c.InterpolationRules.load(Path(c.__file__).parent/'config'/'interpolation_rules.json')
        self.assertEqual((rules.max_gap_stops,rules.max_gap_minutes),(5,30))
        with self.assertRaisesRegex(c.InputError,'real observations only'):
            self.run_gap(self.obs(3,flag=True),self.obs(7,'10:08'))

    def test_five_missing_allowed_six_missing_unverified(self):
        for end,expected in [(7,5),(8,0),(11,0)]:
            rows,unknown=self.run_gap(self.obs(1),self.obs(end,'10:10'))
            self.assertEqual(sum(o.interpolated for o in rows),expected)
            self.assertEqual(bool(unknown),expected==0)
            if unknown:
                self.assertEqual(unknown[0]['reason'],'max_gap_stops 초과')

    def test_scattered_missing_40_of_50_all_interpolated_rate_stays_80(self):
        missing={4,5,12,13,20,21,28,29,36,37}
        original=[self.obs(n,f'{10+(n-1)//60:02d}:{(n-1)%60:02d}') for n in range(1,51) if n not in missing]
        before=tuple(original)
        rows,unknown=c.interpolate_observations(original,c.InterpolationRules(5,30))
        self.assertFalse(unknown)
        self.assertEqual(len(rows),50)
        self.assertEqual(sum(o.interpolated for o in rows),10)
        self.assertEqual(tuple(original),before)
        rates=c.collection_rates(rows,{original[0].route:set(range(1,51))},original[0].at.date(),original[0].at.date())
        self.assertEqual(len(rates),2)
        self.assertTrue(all(r['observed_stop_count']==40 and r['collection_rate_pct']==80 for r in rates))

    def test_collection_rate_run_split_duplicates_and_unknown_denominator(self):
        original=[self.obs(3),self.obs(7,'10:08'),self.obs(3,'10:10'),self.obs(3,'10:11')]
        rates=c.collection_rates(original,{original[0].route:set(range(1,11))},original[0].at.date(),original[0].at.date())
        self.assertEqual([r['collection_rate_pct'] for r in rates],[20,10,20])
        missing=c.collection_rates(original,{},original[0].at.date(),original[0].at.date())
        self.assertTrue(all(r['collection_rate_pct']=='' for r in missing))


if __name__=='__main__':
    unittest.main()
