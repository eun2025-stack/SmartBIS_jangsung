#!/usr/bin/env python3
"""One-shot, read-only final analysis, gated at 2026-10-23 21:30 KST."""
import argparse
import contextlib
import csv
import hashlib
import io
import json
import subprocess
import sys
import unittest
from collections import Counter
from datetime import date,datetime,timedelta
from pathlib import Path
from types import SimpleNamespace

import observation_compare as obs
import unresolved_recheck as recheck
import window_plan as window

ROOT=Path(__file__).resolve().parents[1]
START=date(2026,10,16); END=date(2026,10,23)
NOT_BEFORE=datetime(2026,10,23,21,30,tzinfo=obs.KST)
CUTOFF=datetime(2026,10,23,21,15,tzinfo=obs.KST)
MAPPING_FIELDS=['sheet_row','route_name','direction','departure_time','keycode','route_id','grade',
                'start_date','end_date','actual_count','interpolated_count','observation_date_count',
                'observation_count','evidence_dates']


def require_execution_time(now):
    if now.tzinfo is None or now<NOT_BEFORE:
        raise obs.InputError('최종 실행은 2026-10-23 21:30 KST 이후만 허용')


def observation_end(observations):
    today=[o for o in observations if not o.interpolated and o.source=='TAGO_OBSERVATION' and o.at.date()==END]
    last=max((o.at for o in today),default=None)
    return dict(date=END.isoformat(),row_count=len(today),last_observed_at=last.isoformat() if last else '',
                warning='관측 종료 시각 부족' if last is None or last<CUTOFF else '')


def split_confirmed(rows):
    provisional=list(rows)
    for r in provisional:
        days=set(filter(None,r['evidence_dates'].split('|')))
        if r['observation_date_count']!=len(days) or not days or r['grade']!='확정 후보':
            raise obs.InputError('확정 후보 날짜 수 검증 실패')
    strict=[r for r in provisional if r['observation_date_count']>=2]
    excluded=[r for r in provisional if r['observation_date_count']<2]
    return strict,provisional,excluded


def audit_budget(row):
    required={'local_calls','hundred_calls','backup_calls','total_calls','budget_limit',
              'remaining_calls','retry_reserve_required','within_budget','retry_reserve_ok'}
    if not required<=set(row) or any(row[k] in ('',None) for k in required):
        return '합계 불완전'
    try:
        local,hundred,backup,total=[int(row[k]) for k in ('local_calls','hundred_calls','backup_calls','total_calls')]
        limit=int(row['budget_limit']); remaining=int(row['remaining_calls']); reserve=int(row['retry_reserve_required'])
        truth=lambda v:str(v).lower()=='true'
        backup_expected={1200:7560,1800:5040}.get(int(row.get('backup_interval_seconds',0)))
        if (min(local,hundred,backup)<0 or local+hundred+backup!=total or hundred!=1581
                or backup_expected is None or backup!=backup_expected
                or remaining!=limit-total or truth(row['within_budget'])!=(total<=limit)
                or truth(row['retry_reserve_ok'])!=(remaining>=reserve)):
            return '합계 불완전'
    except (ValueError,TypeError):
        return '합계 불완전'
    return '완전'


def protected_hashes():
    files=set()
    for folder in ('군민운수 운행관리시스템','장성교통 노선도'):
        files.update(p for p in (ROOT/folder).rglob('*') if p.is_file() and p.suffix.lower() in ('.csv','.xlsx','.xls'))
    for name in ('.env','compose.yaml','tools/config/window_rules.csv','tools/config/interpolation_rules.json',
                 'reports/window-plan/unresolved_departures.csv'):
        files.add(ROOT/name)
    files.update((ROOT/'server/egov-backend/src/main/resources').glob('*.properties'))
    files.update((ROOT/'server/database/migrations').glob('*.sql'))
    return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files) if p.is_file()}


def git_status():
    p=subprocess.run(['git','-c','core.quotepath=false','status','--short'],cwd=ROOT,
                     capture_output=True,encoding='utf-8',errors='replace',timeout=30)
    if p.returncode:
        raise obs.InputError('git status 실패: '+p.stderr)
    return p.stdout


def run_tests():
    suite=unittest.defaultTestLoader.discover(str(ROOT/'tools'),pattern='test_*.py')
    result=unittest.TextTestRunner(stream=io.StringIO(),verbosity=1).run(suite)
    if not result.wasSuccessful():
        raise obs.InputError(f'단위 테스트 실패: failures={result.failures}, errors={result.errors}')
    return result.testsRun


def budget_lines(label,rows):
    lines=[]
    for row in rows:
        quality=audit_budget(row)
        if quality!='완전':
            raise obs.InputError(f'{label}: 합계 불완전 (필수 항목 누락 또는 합산/예산 검증 실패)')
        lines.append(f"{label}: 관내 {int(row['local_interval_seconds'])//60}분/백업 {int(row['backup_interval_seconds'])//60}분, "
                     f"관내 {row['local_calls']} + 100번 {row['hundred_calls']} + 백업 {row['backup_calls']} "
                     f"= {row['total_calls']}, 합계 {quality}, 9500 이하={row['within_budget']}, "
                     f"여유 {row['remaining_calls']}, 500 여유={row['retry_reserve_ok']}")
    return lines


def verify_window(folder,extra,hundred):
    summary=window.read_csv(folder/'window_summary.csv')
    confirmed=sum(int(r['confirmed_departure_count']) for r in summary)
    backup=sum(int(r['unresolved_departure_count']) for r in summary)
    if confirmed!=266+extra or backup!=67-extra or confirmed+backup!=333:
        raise obs.InputError('확정+백업 전용=333 검증 실패')
    if {r['route_id'] for r in window.read_csv(folder/'window_plan.csv')} & hundred:
        raise obs.InputError('100번 노선이 관내 창에 포함됨')
    return confirmed,backup


def run(args, now=None):
    now=now or datetime.now(obs.KST)
    require_execution_time(now)
    reports=Path(args.output_root)
    analysis=reports/'observation/2026-10-16_23-final'
    reassess=reports/'observation/unresolved-recheck-final'
    plans=reports/'window-plan/final-2026-10-16_23'
    if any(p.exists() for p in (analysis,reassess,plans)):
        raise obs.InputError('최종 출력 폴더가 이미 있음: 덮어쓰기 금지')
    before=protected_hashes(); status_before=git_status()
    passed=run_tests()
    shared=dict(observations_csv=None,podman_container=args.podman_container,db_user='smartbis_user',
                db_name='smartbis_db',db_host=None,db_port=None,include_tago=False)
    # Only one read-only snapshot feeds every downstream analysis.
    observations=obs.load_observations(SimpleNamespace(**shared),START-timedelta(days=2),END+timedelta(days=2))
    completeness=observation_end(observations)
    mapping=str(ROOT/'군민운수 운행관리시스템/routeid&kecode_matching_ts.csv')
    local_patterns=str(ROOT/'군민운수 운행관리시스템/관내 운행 패턴.csv')
    stops=str(ROOT/'장성교통 노선도/TAGO_장성군_노선별_경유정류소_20261002.csv')
    workbook=str(ROOT/'장성교통 노선도/군민운수 자료/노선(사이텍).xlsx')
    for folder in (analysis,reassess,plans):
        folder.mkdir(parents=True,exist_ok=False)
    lines=[f'최종 실행 시작: {now.isoformat()}',f'관측 기간: {START} ~ {END} (KST)',
           f"10-23 관측 행 수: {completeness['row_count']}; 마지막 observed_at: {completeness['last_observed_at']}",
           completeness['warning'] or '관측 종료 시각 기준 충족(21:15 이후)',
           '정식 실행. 기본 채택=엄격(후보 유일 + 관측 근거 날짜 2일 이상). 운영 배정 변경 없음.']
    for folder in (analysis,reassess,plans):
        (folder/'execution_metadata.json').write_text(json.dumps(dict(started_at=now.isoformat(),observation_end=completeness,
            status='RUNNING'),ensure_ascii=False,indent=2),encoding='utf-8')
    try:
        common=dict(shared,start_date=START.isoformat(),end_date=END.isoformat(),mapping=mapping)
        compare_args=SimpleNamespace(**common,patterns=[local_patterns,str(ROOT/'군민운수 운행관리시스템/광주 운행 패턴.csv')],
            dispatch=[str(ROOT/'군민운수 운행관리시스템/관내배차.csv'),str(ROOT/'군민운수 운행관리시스템/광주배차.csv')],
            assignment_month='2026-08',dispatch_confirmed=False,holiday_calendar=str(ROOT/'tools/config/holidays_2026.csv'),
            skip_incomplete=False,window_minutes=15,route_stops=stops,output_dir=analysis)
        recheck_args=SimpleNamespace(**common,patterns=local_patterns,workbook=workbook,route_stops=stops,
            unresolved=str(ROOT/'reports/window-plan/unresolved_departures.csv'),
            interpolation_rules=str(ROOT/'tools/config/interpolation_rules.json'),provisional=False,output_dir=reassess)
        with contextlib.redirect_stdout(io.StringIO()):
            obs.compare(compare_args,observations=observations)
            aggregate,candidates=recheck.run(recheck_args,observations=observations)
        strict,provisional,difference=split_confirmed(candidates)
        window.write_csv(reassess/'confirmed_mapping_strict.csv',strict,MAPPING_FIELDS)
        window.write_csv(reassess/'confirmed_mapping_provisional.csv',provisional,MAPPING_FIELDS)
        window.write_csv(reassess/'confirmed_mapping.csv',strict,MAPPING_FIELDS)
        window.write_csv(reassess/'strict_excluded_departures.csv',difference,MAPPING_FIELDS)
        previous_path=ROOT/'reports/observation/2026-10-16_23-unresolved-recheck/confirmed_mapping.csv'
        previous=window.read_csv(previous_path) if previous_path.is_file() else []
        identity=lambda r:(r['route_name'],r['direction'],r['departure_time'])
        old_map={identity(r):r for r in previous}
        changes=[]
        for criterion,items in (('strict',strict),('provisional',provisional)):
            current={identity(r):r for r in items}
            for key in sorted(set(old_map)|set(current)):
                old_key=old_map.get(key,{}).get('keycode',''); new_key=current.get(key,{}).get('keycode','')
                if old_key!=new_key:
                    changes.append(dict(criterion=criterion,route_name=key[0],direction=key[1],departure_time=key[2],
                        previous_keycode=old_key,current_keycode=new_key,
                        change='추가' if not old_key else '제외' if not new_key else 'keycode 변경'))
        window.write_csv(reassess/'previous_39_changes.csv',changes,
            ['criterion','route_name','direction','departure_time','previous_keycode','current_keycode','change'])
        grades=Counter(r['grade'] for r in aggregate)
        lines += [f'고유 출발 67건: 확정 후보 {grades["확정 후보"]}, 모호 {grades["모호"]}, 미관측 {grades["미관측"]}',
                  f'엄격 확정 {len(strict)}, 엄격 근거 부족 {len(difference)}, 모호 {grades["모호"]}, 미관측 {grades["미관측"]} (합계 67)',
                  f'잠정 확정 {len(provisional)}, 모호 {grades["모호"]}, 미관측 {grades["미관측"]} (합계 67)',
                  '관측 날짜 수는 대응 근거가 있는 서로 다른 날짜 수(실제/보간 건수 별도 기록).',
                  f'기존 잠정 39건 대비: 엄격 {len(strict)-39:+d}, 잠정 {len(provisional)-39:+d}',
                  f'기존 잠정 목록 원본 확인: {len(previous)}건; 변경 출발 상세: previous_39_changes.csv',
                  '[엄격 기준에서 빠지는 1일 근거 출발]']
        lines += [f"{r['route_name']} {r['direction']} {r['departure_time']} keycode={r['keycode']} "
                  f"날짜수={r['observation_date_count']} 건수={r['observation_count']} 근거일={r['evidence_dates']}" for r in difference]
        lines += ['[이전 39건 목록과 출발별 차이]']
        lines += [f"{r['criterion']} {r['route_name']} {r['direction']} {r['departure_time']}: "
                  f"{r['change']} {r['previous_keycode']} → {r['current_keycode']}" for r in changes]
        if len(previous)!=39:
            lines += ['경고: 기존 잠정 39건 목록을 그대로 확인할 수 없음. 현재 발견 목록과 차이만 기록함.']
        # 10-19 empty input must not become evidence or reduce the 2-evidence-date count.
        absent=not any(o.at.date()==date(2026,10,19) for o in observations)
        if absent and any('2026-10-19' in r['evidence_dates'] for r in candidates):
            raise obs.InputError('10-19 무관측일이 확정 근거에 포함됨')
        lines += [f'10-19 관측 없음: {absent}; 무관측일은 확정 근거 날짜 수에서 제외. 창은 시간표 기준으로 계산.']
        rules_path=ROOT/'tools/config/window_rules.csv'
        route_mapping=window.load_mapping(mapping,window.Rules.load(rules_path))
        for label,rows in (('strict',strict),('provisional',provisional)):
            folder=plans/label
            wargs=SimpleNamespace(workbook=workbook,sheet='관내',mapping=mapping,patterns=local_patterns,
                rules=rules_path,route_stops=stops,departure_keys=None,
                confirmed_mapping=reassess/f'confirmed_mapping_{label}.csv',output_dir=folder)
            with contextlib.redirect_stdout(io.StringIO()):
                window.run(wargs)
            confirmed,backup=verify_window(folder,len(rows),route_mapping[3])
            lines += [f'{label}: 창 입력 {confirmed}회 (266+{len(rows)}), 백업 전용 {backup}회, 합계 {confirmed+backup}회']
            lines += budget_lines(label,window.read_csv(folder/'budget_check.csv'))
            concurrency=window.read_csv(folder/'concurrency.csv')
            peak=max(int(r['active_route_count']) for r in concurrency)
            times=[r['time']+'~'+r['until'] for r in concurrency if int(r['active_route_count'])==peak]
            lines += [f'{label} 관내 동시 활성 최댓값 {peak}개: '+', '.join(times)]
        # Root files are the default strict plan; both variants remain in their subdirectories.
        for path in (plans/'strict').glob('*.csv'):
            (plans/path.name).write_bytes(path.read_bytes())
        old=ROOT/'reports/window-plan/rechecked-2026-10-16_23-provisional/budget_check.csv'
        historic=[r for r in window.read_csv(old) if int(r['total_calls'])==8717] if old.is_file() else []
        lines += ['[기존 잠정 예산 8717 검증]']
        if historic:
            lines += budget_lines('이전 잠정',historic)
        else:
            lines += ['합계 불완전: 이전 8717 결과를 찾지 못해 검증 불가']
        origin=obs.origin_review_lines(observations,obs.load_route_stop_orders(stops),START,END)
        lines += origin
        after=protected_hashes(); status_after=git_status()
        changed=sorted(k for k in set(before)|set(after) if before.get(k)!=after.get(k))
        audit=dict(before=before,after=after,changed=changed,git_status_before=status_before,git_status_after=status_after)
        (analysis/'source_integrity.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
        if changed:
            raise obs.InputError('보호 대상 변경 발견: '+str(changed))
        lines += [f'원본/운영 설정 해시 검증: {len(before)}개, 변경 0개. git status 전후는 source_integrity.json 참조.',
                  '기존 dirty 상태는 되돌리지 않았으며 이번 분석이 원본/설정을 수정하지 않음.',
                  f'전체 단위 테스트: {passed}개 통과',
                  '기본 채택: strict. TAGO HTTP 호출/DB 쓰기/운영 설정 변경 없음. 8월 예시표를 10월 실배차로 사용하지 않음.']
        for folder in (analysis,reassess,plans,plans/'strict',plans/'provisional'):
            # Preserve tool-specific detail summaries; store the overall report separately too.
            if (folder/'summary.txt').is_file():
                (folder/'detail_summary.txt').write_bytes((folder/'summary.txt').read_bytes())
            (folder/'summary.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8-sig')
        for folder in (analysis,reassess,plans):
            (folder/'execution_metadata.json').write_text(json.dumps(dict(started_at=now.isoformat(),
                finished_at=datetime.now(obs.KST).isoformat(),observation_end=completeness,status='COMPLETE',
                tests_passed=passed),ensure_ascii=False,indent=2),encoding='utf-8')
        print('\n'.join(lines))
    except Exception as error:
        for folder in (analysis,reassess,plans):
            (folder/'FAILED.txt').write_text(str(error),encoding='utf-8-sig')
            (folder/'summary.txt').write_text('\n'.join(lines)+f'\n최종 실행 실패: {error}\n',encoding='utf-8-sig')
        raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--podman-container',default='smartbis_jangsung-postgres-db-1')
    parser.add_argument('--output-root',default=str(ROOT/'reports'))
    args=parser.parse_args()
    try:
        run(args)
    except Exception as error:
        print(str(error),file=sys.stderr)
        return 2
    return 0


if __name__=='__main__':
    raise SystemExit(main())
