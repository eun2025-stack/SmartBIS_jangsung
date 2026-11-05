import unittest
from build_route_call_plan import (LANE_A, VARIANTS, SWEEP_ONLY, DAY_TYPES,
                                   build_plan, parse_patterns, read_routes, PlanError)


def fixture():
    routes = sorted({f'TSB342{i:06d}' for i in range(1, 89)} | LANE_A | VARIANTS)
    rows = [['div', 'patt', 'seq', 'time', 'route']]
    divs = ['ud']*10 + ['ld']*8 + ['js']*9 + ['gj']*2 + ['hjs']*8 + ['hgj']*2
    for i, div in enumerate(divs):
        rows.append([div, f'{div}{i+1}'])
    available = [r for r in routes if r not in SWEEP_ONLY]
    for i in range(425):
        row = rows[i % 39 + 1]
        minutes = 480 + i
        row.extend([str(i+1), f'{minutes//60:02}:{minutes%60:02}', available[i % len(available)]])
    rows[1].extend(rows[1][2:5])
    return routes, rows


class BuildRouteCallPlanTest(unittest.TestCase):
    def generate(self, routes=None, rows=None):
        r, p = fixture()
        routes, rows = routes or r, rows or p
        groups = {x: '100번' if x in LANE_A | VARIANTS else '관내' for x in routes}
        return build_plan(rows, routes, routes, groups, {'start','end','interval_seconds','day_type'})

    def test_426_trips_39_patterns_and_425_unique_windows(self):
        _, summary = self.generate()
        self.assertEqual((426, 39, 425), (summary['trip_count'], summary['pattern_count'], summary['deduplicated_windows_including_a']))

    def test_rejects_trip_count_other_than_426(self):
        routes, rows = fixture(); rows[1] = rows[1][:-3]
        with self.assertRaisesRegex(PlanError, 'TRIP_COUNT'):
            self.generate(routes, rows)

    def test_day_type_mapping(self):
        self.assertEqual({'ud':'all','ld':'all','js':'wkd','gj':'wkd','hjs':'hol','hgj':'hol'}, DAY_TYPES)

    def test_lead_10_variant_15_regular_and_normalization(self):
        rows = [['div','patt','seq','time','route'], ['ud','ud1','1','07:15','TBS342000118','2','08:00','TSB342000001']]
        _, trips = parse_patterns(rows)
        self.assertEqual([25500,27900], [x['start_seconds'] for x in trips])
        self.assertEqual([10,15], [x['lead_min'] for x in trips])
        self.assertEqual('TSB342000118', trips[0]['route_id'])

    def test_deduplication_and_lane_distribution(self):
        _, s = self.generate()
        self.assertEqual(1, s['duplicate_windows_removed'])
        self.assertEqual({'A':3,'B':100,'C':105,'sweep_only':2}, s['lanes'])
        self.assertEqual(s['lane_b_windows'],sum(s['lane_b_windows_by_day_type'].values()))

    def test_a_and_sweep_only_have_no_windows(self):
        plans, _ = self.generate()
        for row in plans:
            if row['route_id'] in LANE_A | SWEEP_ONLY:
                self.assertEqual([],row['local_windows'])
        self.assertEqual(3,sum(p['service_start_seconds']==18900 and p['service_end_seconds']==76500 for p in plans))

    def test_route_set_mismatch_rejected(self):
        routes, rows = fixture(); groups={r:'관내' for r in routes}
        with self.assertRaisesRegex(PlanError,'ROUTE_SET'):
            build_plan(rows,routes,routes[:-1],groups)

    def test_105_unique_routes_required(self):
        with self.assertRaisesRegex(PlanError,'ROUTE_COUNT'):
            read_routes([['ROUTE_ID'],['TSB342000001']])

    def test_no_switch_on_or_run_id_allocation(self):
        plans,_=self.generate()
        for p in plans:
            self.assertFalse(p['enabled'])
            self.assertNotIn('plan_revision_id',p)
            self.assertNotIn('collection_run_id',p)
            self.assertEqual((1800,21600,75600),(p['backup_interval_seconds'],p['backup_start_seconds'],p['backup_end_seconds']))

    def test_existing_json_contract_for_all_weekday_holiday(self):
        plans,_=self.generate()
        windows=[w for p in plans for w in p['local_windows']]
        self.assertTrue(windows)
        self.assertTrue(any('day_type' not in w for w in windows))
        self.assertEqual({'WEEKDAY','HOLIDAY'}, {w['day_type'] for w in windows if 'day_type' in w})
        for w in windows:
            self.assertEqual({'start','end','interval_seconds'} | ({'day_type'} if 'day_type' in w else set()),set(w))


if __name__=='__main__': unittest.main()
