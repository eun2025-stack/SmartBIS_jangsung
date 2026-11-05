import unittest
from datetime import date
from assignment_rules import candidate


class AssignmentRuleTests(unittest.TestCase):
    def test_ud_sunday_boundary_first_and_eighth(self):
        args = (date(2026, 11, 1), 'ud', {'ud': 0}, {'ud': ['ud1', 'ud2', 'ud3']})
        self.assertEqual('ud1', candidate(date(2026, 11, 1), *args)['pattern'])
        self.assertEqual('ud1', candidate(date(2026, 11, 7), *args)['pattern'])
        self.assertEqual('ud2', candidate(date(2026, 11, 8), *args)['pattern'])

    def test_ld_sunday_wrap(self):
        self.assertEqual('ld1', candidate(date(2026, 11, 8), date(2026, 11, 1),
            'ld', {'ld': 1}, {'ld': ['ld1', 'ld2']})['pattern'])

    def test_js_advances_only_on_workdays(self):
        anchor = date(2026, 11, 2)
        seeds = {'js': 0, 'hjs': 0}
        slots = {'js': ['js1', 'js2', 'js3'], 'hjs': ['hjs1', None]}
        self.assertEqual('js1', candidate(anchor, anchor, 'js', seeds, slots)['pattern'])
        self.assertEqual('js2', candidate(date(2026, 11, 3), anchor, 'js', seeds, slots)['pattern'])
        self.assertEqual('js3', candidate(date(2026, 11, 9), anchor, 'js', seeds, slots)['pattern'])

    def test_gj_workday_wrap(self):
        self.assertEqual('gj1', candidate(date(2026, 11, 4), date(2026, 11, 2),
            'gj', {'gj': 0}, {'gj': ['gj1', 'gj2']})['pattern'])

    def test_holiday_blank_is_off_not_skipped(self):
        anchor = date(2026, 11, 7)
        slots = {'hjs': ['hjs1', None, 'hjs2']}
        r = candidate(date(2026, 11, 8), anchor, 'js', {'hjs': 0}, slots)
        self.assertIsNone(r['pattern'])
        self.assertEqual('DEFAULT_DAY_OFF', r['status'])
        self.assertEqual('hjs2', candidate(date(2026, 11, 14), anchor, 'js', {'hjs': 0}, slots)['pattern'])

    def test_hgj_advances_only_on_holiday_workdays(self):
        self.assertEqual('hgj1', candidate(date(2026, 11, 14), date(2026, 11, 7),
            'gj', {'hgj': 0}, {'hgj': ['hgj1', 'hgj2']})['pattern'])

    def test_configured_public_holiday_uses_holiday_cycle(self):
        r = candidate(date(2026, 11, 3), date(2026, 11, 2), 'js',
                      {'js': 0, 'hjs': 0}, {'js': ['js1'], 'hjs': ['hjs1']},
                      holidays=frozenset([date(2026, 11, 3)]))
        self.assertEqual('hjs1', r['pattern'])

    def test_unplanned_7536_and_reserves_remain_unknown(self):
        for division in ('js', 'rb'):
            r = candidate(date(2026, 11, 8), date(2026, 11, 1), division,
                          {}, {}, planned=False)
            self.assertEqual({'pattern': None, 'family': None, 'status': 'PLAN_UNKNOWN'}, r)


if __name__ == '__main__':
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(AssignmentRuleTests))
    print(f'Tests run: {result.testsRun}, Failures: {len(result.failures)}, Errors: {len(result.errors)}')
    raise SystemExit(not result.wasSuccessful())
