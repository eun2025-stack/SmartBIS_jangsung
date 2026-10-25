import contextlib
import io
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import final_observation_run as final
import observation_compare as obs
import window_plan as window


class FinalRunTests(unittest.TestCase):
    def candidate(self,days):
        return dict(evidence_dates='|'.join(days),observation_date_count=len(days),grade='확정 후보')

    def budget(self,**changes):
        row=dict(local_interval_seconds='600',backup_interval_seconds='1800',local_calls='2096',
            hundred_calls='1581',backup_calls='5040',total_calls='8717',budget_limit='9500',remaining_calls='783',
            retry_reserve_required='500',within_budget='True',retry_reserve_ok='True')
        row.update(changes)
        return row

    def test_time_gate_exact_boundary_and_naive_time(self):
        with self.assertRaises(obs.InputError):
            final.require_execution_time(datetime(2026,10,23,21,29,59,tzinfo=obs.KST))
        final.require_execution_time(final.NOT_BEFORE)
        with self.assertRaises(obs.InputError):
            final.require_execution_time(datetime(2026,10,23,21,30))

    def test_early_run_does_not_query_or_create_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'not-created'
            with patch.object(obs,'load_observations',side_effect=AssertionError('no query')):
                with self.assertRaises(obs.InputError):
                    final.run(SimpleNamespace(output_root=output),now=datetime(2026,10,23,20,tzinfo=obs.KST))
            self.assertFalse(output.exists())

    def test_observation_end_cutoff_and_empty(self):
        def row(at):
            return obs.Observation('7500','R',datetime.fromisoformat(at))
        self.assertEqual(final.observation_end([])['warning'],'관측 종료 시각 부족')
        early=final.observation_end([row('2026-10-23T21:14:59+09:00')])
        self.assertEqual(early['row_count'],1); self.assertTrue(early['warning'])
        self.assertFalse(final.observation_end([row('2026-10-23T21:15:00+09:00')])['warning'])
        inferred=obs.Observation('7500','R',datetime(2026,10,23,21,20,tzinfo=obs.KST),interpolated=True)
        self.assertEqual(final.observation_end([inferred])['row_count'],0)

    def test_two_distinct_days_not_two_rows_or_empty_day(self):
        first=self.candidate(['2026-10-16'])
        second=self.candidate(['2026-10-16','2026-10-23'])
        strict,provisional,diff=final.split_confirmed([first,second])
        self.assertEqual(strict,[second]); self.assertEqual(diff,[first]); self.assertEqual(len(provisional),2)
        self.assertNotIn('2026-10-19',second['evidence_dates'])
        with self.assertRaises(obs.InputError):
            final.split_confirmed([dict(first,observation_date_count=2)])

    def test_budget_contains_three_components_and_exact_sum(self):
        self.assertEqual(final.audit_budget(self.budget()),'완전')
        for key in ('local_calls','hundred_calls','backup_calls'):
            row=self.budget(); del row[key]
            self.assertEqual(final.audit_budget(row),'합계 불완전')
        self.assertEqual(final.audit_budget(self.budget(total_calls='7136')),'합계 불완전')
        self.assertEqual(final.audit_budget(self.budget(hundred_calls='0',total_calls='7136',remaining_calls='2364')),'합계 불완전')
        self.assertEqual(final.audit_budget(self.budget(retry_reserve_ok='False')),'합계 불완전')

    def test_missing_budget_line_is_explicit_incomplete_not_silent(self):
        row=self.budget(); del row['hundred_calls']
        with self.assertRaisesRegex(obs.InputError,'합계 불완전'):
            final.budget_lines('strict',[row])

    def test_existing_final_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp); folder=output/'observation/unresolved-recheck-final'; folder.mkdir(parents=True)
            sentinel=folder/'keep.txt'; sentinel.write_text('keep')
            with self.assertRaises(obs.InputError):
                final.run(SimpleNamespace(output_root=output),now=final.NOT_BEFORE)
            self.assertEqual(sentinel.read_text(),'keep')

    def test_integrated_final_pipeline_uses_one_snapshot_only_and_strict_default(self):
        # A disposable fixture clock and output directory, no DB or final report writes.
        data=[obs.Observation('7500','TSB342000002',datetime(2026,10,d,6,45,tzinfo=obs.KST),'0') for d in (16,23)]
        data.append(obs.Observation('7500','TSB342000002',datetime(2026,10,23,21,20,tzinfo=obs.KST),'0'))
        with tempfile.TemporaryDirectory() as tmp:
            args=SimpleNamespace(output_root=Path(tmp),podman_container='not-used')
            before=tuple(data)
            with patch.object(final,'run_tests',return_value=999),patch.object(final,'git_status',return_value='existing dirty'),\
                 patch.object(final,'protected_hashes',return_value={'source':'unchanged'}),\
                 patch.object(obs,'load_observations',return_value=data) as loader,contextlib.redirect_stdout(io.StringIO()):
                final.run(args,now=final.NOT_BEFORE)
            self.assertEqual(loader.call_count,1); self.assertEqual(tuple(data),before)
            reassess=Path(tmp)/'observation/unresolved-recheck-final'
            strict=window.read_csv(reassess/'confirmed_mapping_strict.csv')
            provisional=window.read_csv(reassess/'confirmed_mapping_provisional.csv')
            self.assertTrue(strict)
            self.assertTrue(all(int(r['observation_date_count'])>=2 for r in strict))
            self.assertGreaterEqual(len(provisional),len(strict))
            plans=Path(tmp)/'window-plan/final-2026-10-16_23'
            self.assertEqual((plans/'window_plan.csv').read_bytes(),(plans/'strict/window_plan.csv').read_bytes())
            self.assertTrue((plans/'provisional/window_plan.csv').exists())
            text=(plans/'summary.txt').read_text(encoding='utf-8-sig')
            self.assertIn('합계 333회',text); self.assertIn('10-19 관측 없음: True',text)
            self.assertIn('기점 좌표 검토 필요',text)
            self.assertIn('이전 잠정',text)
            self.assertTrue((reassess/'confirmed_mapping_strict.csv').read_bytes().startswith(b'\xef\xbb\xbf'))


if __name__=='__main__':
    unittest.main()
