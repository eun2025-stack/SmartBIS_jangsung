import unittest
from datetime import date,datetime
import observation_compare as obs
import unresolved_recheck as r


class RecheckTests(unittest.TestCase):
    day=date(2026,10,16)
    mapping=({'20':{'A'},'21':{'B'}},{'2':{'A','B'}},{'A':'2','B':'2'},set(),{'A','B'})
    row=dict(sheet_row='6',route_name='2',departure_time='10:00',direction='종점',candidate_keycodes='20|21')

    def observation(self,route='A',at='10:00',order='3'):
        return obs.Observation('7500',route,datetime.fromisoformat(f'2026-10-16T{at}:00+09:00'),order)

    def check(self,observations,row=None):
        return r.recheck([row or self.row],observations,self.mapping,obs.InterpolationRules(5,30),self.day,self.day)[0][0]

    def test_grades_and_exact_15_minute_boundary(self):
        self.assertEqual(self.check([])['grade'],'미관측')
        self.assertEqual(self.check([self.observation(at='09:45')])['grade'],'확정 후보')
        self.assertEqual(self.check([self.observation(at='09:44')])['grade'],'미관측')
        self.assertEqual(self.check([self.observation(),self.observation('B')])['grade'],'모호')

    def test_interpolation_is_evidence_only_not_operating_judgment(self):
        row=dict(self.row,departure_time='10:04')
        original=[self.observation(at='09:48',order='1'),self.observation(at='10:20',order='5')]
        # over 30 minutes, so no inference even though intermediate time would match.
        self.assertEqual(self.check(original,row)['grade'],'미관측')
        original=[self.observation(at='09:49',order='1'),self.observation(at='10:19',order='5')]
        # Endpoint boundary counts are inclusive; use a zero-minute direct recheck to isolate inference.
        result,_,passages,_=r.recheck([row],original,self.mapping,obs.InterpolationRules(5,30),self.day,self.day,minutes=0)
        self.assertEqual(result[0]['grade'],'확정 후보')
        self.assertEqual(result[0]['actual_observation_count'],0)
        self.assertEqual(result[0]['interpolated_evidence_count'],1)
        idx=obs.ObservationIndex(passages)
        self.assertEqual(obs.classify(idx,'7500',self.day,obs.Expected(604,'A'),0,{'A'})[0],obs.MISSING)

    def test_no_candidates_no_invented_assignment(self):
        row=dict(self.row,candidate_keycodes='')
        result=self.check([self.observation()],row)
        self.assertEqual(result['grade'],'미관측')
        self.assertEqual(result['candidate_counts'],'{}')

    def test_wrong_family_rejected_and_input_unchanged(self):
        row=dict(self.row,route_name='87')
        with self.assertRaises(obs.InputError):
            self.check([],row)
        original=[self.observation()]; before=tuple(original)
        self.check(original)
        self.assertEqual(tuple(original),before)

    def test_unique_departure_merges_dates_not_majority(self):
        first=self.check([self.observation()])
        second=dict(first,date='2026-10-17')
        unique,confirmed=r.aggregate_departures([first,second])
        self.assertEqual(len(unique),1); self.assertEqual(len(confirmed),1)
        self.assertEqual(confirmed[0]['keycode'],'20')
        self.assertEqual(confirmed[0]['actual_count'],2)
        conflict=dict(self.check([self.observation('B')]),date='2026-10-18')
        unique,confirmed=r.aggregate_departures([first,second,conflict])
        self.assertEqual(unique[0]['grade'],'모호'); self.assertFalse(confirmed)

    def test_unobserved_days_do_not_erase_evidence_and_duplicate_day_rejected(self):
        first=self.check([self.observation()])
        missing=dict(self.check([]),date='2026-10-17')
        unique,confirmed=r.aggregate_departures([first,missing])
        self.assertEqual(unique[0]['grade'],'확정 후보'); self.assertEqual(len(confirmed),1)
        with self.assertRaises(obs.InputError):
            r.aggregate_departures([first,first])
        unique,confirmed=r.aggregate_departures([missing])
        self.assertEqual(unique[0]['grade'],'미관측'); self.assertFalse(confirmed)


if __name__=='__main__':
    unittest.main()
