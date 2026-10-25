import copy
from datetime import date,datetime
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import nightly_route_plan as batch
import observation_compare as obs


class NightlyDecisionTests(unittest.TestCase):
    def row(self,day='2026-10-16',key='ud1'):
        return dict(date=day,observed_candidate_keycodes=key)

    def observations(self,days,interpolated=False,route='TSB342000001'):
        return [obs.Observation('7500',route,datetime.fromisoformat(d+'T08:00:00+09:00'),
            '3','TAGO_OBSERVATION',interpolated) for d in days]

    def decide(self,observations,patterns=None,target=date(2026,10,20)):
        patterns=patterns or {'ud1':[obs.Expected(480,'TSB342000001')]}
        return batch.decide_vehicles(observations,{'7500':'전남76아7500'},patterns,
            {'TSB342000001'},set(),obs.HolidayCalendar(frozenset()),date(2026,10,16),target)

    def test_one_day_cannot_confirm(self):
        self.assertEqual(batch.unique_evidence([self.row()]),('ud1',1,'INSUFFICIENT_DAYS'))

    def test_two_days_confirm(self):
        self.assertEqual(batch.unique_evidence([self.row(),self.row('2026-10-20')]),('ud1',2,'CONFIRMED'))

    def test_same_date_twice_is_one_day(self):
        self.assertEqual(batch.unique_evidence([self.row(),self.row()])[1],1)

    def test_competing_candidate_not_majority_vote(self):
        self.assertEqual(batch.unique_evidence([self.row(),self.row('2026-10-20'),self.row('2026-10-21','ud2')])[2],'AMBIGUOUS')

    def test_interpolated_rows_not_counted_and_originals_preserved(self):
        rows=self.observations(['2026-10-16'])+self.observations(['2026-10-20'],True)
        original=copy.deepcopy(rows)
        daily,_,_=self.decide(rows)
        self.assertNotIn('CONFIRMED',[r['decision_status'] for r in daily])
        self.assertEqual(rows,original)

    def test_no_observation_1019_not_evidence(self):
        daily,_,details=self.decide(self.observations(['2026-10-16','2026-10-20']))
        missing=next(r for r in daily if r['service_date']=='2026-10-19')
        self.assertEqual(missing['decision_status'],'NO_OBSERVATIONS')
        self.assertEqual(missing['evidence_date_count'],0)
        self.assertFalse(any(r['date']=='2026-10-19' for r in details))
        self.assertEqual(daily[-1]['decision_status'],'CONFIRMED')
        self.assertEqual(daily[0]['decision_status'],'INSUFFICIENT_DAYS')

    def test_ties_remain_ambiguous(self):
        patterns={k:[obs.Expected(480,'TSB342000001')] for k in ('ud1','ud2')}
        daily,_,_=self.decide(self.observations(['2026-10-16','2026-10-20']),patterns)
        self.assertEqual(daily[-1]['decision_status'],'AMBIGUOUS')

    def test_holiday_patterns_separate_from_weekday_evidence(self):
        patterns={k:[obs.Expected(480,'TSB342000001')] for k in ('js1','hjs1')}
        observations=self.observations(['2026-10-16','2026-10-17'])
        daily,_,_=batch.decide_vehicles(observations,{'7500':'전남76아7500'},patterns,set(),
            {'TSB342000001'},obs.HolidayCalendar(frozenset()),date(2026,10,16),date(2026,10,17))
        self.assertEqual([r['pattern_code'] for r in daily],['js1','hjs1'])
        self.assertTrue(all(r['evidence_date_count']==1 for r in daily))

    def test_loader_filters_source_flags_and_future(self):
        common=dict(vehicle_number='전남76아7500',route_id='TSB342000001',node_order='3',source='TAGO_OBSERVATION')
        rows=[dict(common,observed_at='2026-10-16T08:00:00+09:00'),
              dict(common,observed_at='2026-10-17T08:00:00+09:00',interpolated='true'),
              dict(common,observed_at='2026-10-20T08:00:00+09:00'),
              dict(common,observed_at='2026-10-16T08:00:00+09:00',source='TAGO')]
        actual,names=batch.real_observations(rows,date(2026,10,16),date(2026,10,18))
        self.assertEqual(len(actual),1); self.assertEqual(names['7500'],'전남76아7500')

    def test_observation_db_query_readonly_and_guard_free(self):
        with patch.object(batch.adopted,'psql',return_value=[]) as sql:
            batch.read_observations(SimpleNamespace(observations_csv=None),date(2026,10,16),date(2026,10,23))
        query=sql.call_args.args[1]
        self.assertIn('TRANSACTION READ ONLY',query)
        self.assertNotRegex(query,r'\b(UPDATE|INSERT|DELETE|TRUNCATE|CREATE)\b')
        self.assertNotIn('guard',query)

    def test_writer_only_decision_table_idempotent(self):
        daily,_,_=self.decide(self.observations(['2026-10-16','2026-10-20']))
        sql=batch.decision_sql(daily)
        self.assertIn('INSERT INTO vehicle_route_pattern_daily',sql)
        self.assertIn('IS DISTINCT FROM',sql)
        self.assertNotIn('route_call_plan',sql)
        self.assertNotIn('guard_usage_daily',sql)
        self.assertNotRegex(sql,r'\b(DELETE|TRUNCATE|DROP|CREATE|ALTER)\b')

    def test_invalid_confirmation_and_duplicate_rejected(self):
        daily,_,_=self.decide(self.observations(['2026-10-16']))
        with self.assertRaises(obs.InputError):
            batch.decision_sql([daily[0],daily[0]])
        daily[0]['decision_status']='CONFIRMED'
        with self.assertRaises(obs.InputError):
            batch.decision_sql(daily)

    def test_collector_does_not_depend_on_batch(self):
        folder=batch.ROOT/'server/egov-backend/src/main/java/com/smartbis/backend/tago'
        for name in ('RouteCallPlanScheduler.java','JdbcRouteCallPlanStore.java','RouteCallSchedule.java'):
            text=(folder/name).read_text(encoding='utf-8')
            self.assertNotIn('vehicle_route_pattern_daily',text)
            self.assertNotIn('nightly_route_plan',text)
        sql=(batch.ROOT/'server/database/migrations/V017__pattern_decision_audit.sql').read_text(encoding='utf-8')
        self.assertNotIn('route_call_plan',sql)
        self.assertNotIn('bus_positions',sql)

    def test_plan_is_read_only_during_apply(self):
        state=dict(columns={'vehicle_route_pattern_daily':['decision_status','evidence_date_count','decision_details','evaluated_at']},
            rows=[dict(route_id='TSB342000001',enabled=False,local_windows=[])],counts={'guard_usage_daily':0})
        daily,_,_=self.decide(self.observations(['2026-10-16','2026-10-20']))
        with patch.object(batch.adopted,'db_state',return_value=state), patch.object(batch.adopted,'psql',
                side_effect=[{'affected_rows':5},{'affected_rows':0}]) as writer:
            first,repeat=batch.persist_decisions(None,daily)
        self.assertEqual(repeat['affected_rows'],0)
        for call in writer.call_args_list:
            self.assertNotIn('route_call_plan',call.args[1])
            self.assertNotIn('guard_usage_daily',call.args[1])

    def test_active_plan_aborts_before_decision_write(self):
        state=dict(columns={'vehicle_route_pattern_daily':['decision_status','evidence_date_count','decision_details','evaluated_at']},
            rows=[dict(enabled=True)])
        with patch.object(batch.adopted,'db_state',return_value=state),patch.object(batch.adopted,'psql') as writer:
            with self.assertRaises(obs.InputError):
                batch.persist_decisions(None,[])
            writer.assert_not_called()


if __name__=='__main__':
    unittest.main()
