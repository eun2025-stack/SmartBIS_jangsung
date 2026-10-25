#!/usr/bin/env python3
"""Read-only observation evidence recheck. Never assigns a departure to a route."""
import argparse
import json
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
from datetime import date, datetime, time, timedelta
from pathlib import Path

import observation_compare as obs
import window_plan as windows

ROOT=Path(__file__).resolve().parents[1]
GRADES=('확정 후보','모호','미관측')


def candidate_routes(row,mapping):
    keys,names,routes,hundred,local=mapping
    family=row['route_name'].split('-')[0]
    result={}
    for key in filter(None,row['candidate_keycodes'].split('|')):
        ids=keys.get(key,set())
        if len(ids)!=1 or not ids<=local or any(routes[r].split('-')[0]!=family for r in ids):
            raise obs.InputError(f'후보 keycode 대응 오류: {row["route_name"]} / {key}')
        result[key]=next(iter(ids))
    return result


def recheck(departures,observations,mapping,rules,start,end,minutes=15):
    actual=[o for o in observations if not o.interpolated and o.source=='TAGO_OBSERVATION']
    passages,unverified=obs.interpolate_observations(actual,rules)
    grouped=defaultdict(list)
    for o in passages:
        grouped[o.route].append(o)
    indices={}
    for route,rows in grouped.items():
        rows.sort(key=lambda o:o.at)
        indices[route]=([o.at for o in rows],rows)
    result=[]; delta=timedelta(minutes=minutes)
    day=start
    while day<=end:
        for row in departures:
            moment=datetime.combine(day,time(),obs.KST)+timedelta(seconds=windows.seconds(row['departure_time']))
            counts={}; seen=[]; real_total=inferred_total=0
            for key,route in sorted(candidate_routes(row,mapping).items()):
                times,rows=indices.get(route,([],[]))
                hits=rows[bisect_left(times,moment-delta):bisect_right(times,moment+delta)]
                real=sum(not o.interpolated for o in hits); inferred=sum(o.interpolated for o in hits)
                counts[key]=dict(route_id=route,actual_count=real,interpolated_count=inferred,
                                 vehicles=sorted({o.vehicle for o in hits}))
                if hits:
                    seen.append(key)
                real_total+=real; inferred_total+=inferred
            grade=GRADES[0] if len(seen)==1 else GRADES[1] if len(seen)>1 else GRADES[2]
            result.append(dict(date=day.isoformat(),sheet_row=row['sheet_row'],route_name=row['route_name'],
                departure_time=row['departure_time'],direction=row['direction'],candidate_keycodes=row['candidate_keycodes'],
                candidate_counts=json.dumps(counts,ensure_ascii=False,sort_keys=True),
                observed_candidate_keycodes='|'.join(seen),observed_candidate_count=len(seen),
                actual_observation_count=real_total,interpolated_evidence_count=inferred_total,grade=grade,
                note='후보 없음: 대응표 미확정' if not counts else '대응 참고 근거만 사용; 자동 배정·운행 판정 변경 없음'))
        day+=timedelta(days=1)
    return result,actual,passages,unverified


def aggregate_departures(daily):
    """Union evidence across dates, never vote or select a majority candidate."""
    groups=defaultdict(list)
    for row in daily:
        groups[row['route_name'],row['direction'],row['departure_time']].append(row)
    result=[]; confirmed=[]
    for identity,rows in sorted(groups.items()):
        if len({r['sheet_row'] for r in rows})!=1:
            raise obs.InputError(f'동일 출발의 시간표 행 충돌: {identity}')
        if len({r['date'] for r in rows})!=len(rows):
            raise obs.InputError(f'동일 출발/날짜 중복: {identity}')
        candidates={}; evidence_days=defaultdict(set)
        for row in rows:
            for key,data in json.loads(row['candidate_counts']).items():
                value=candidates.setdefault(key,dict(route_id=data['route_id'],actual_count=0,interpolated_count=0))
                if value['route_id']!=data['route_id']:
                    raise obs.InputError(f'keycode ROUTE_ID 충돌: {key}')
                value['actual_count']+=data['actual_count']; value['interpolated_count']+=data['interpolated_count']
                if data['actual_count']+data['interpolated_count']:
                    evidence_days[key].add(row['date'])
        seen=sorted(evidence_days)
        grade=GRADES[0] if len(seen)==1 else GRADES[1] if seen else GRADES[2]
        first=rows[0]
        item=dict(route_name=identity[0],direction=identity[1],departure_time=identity[2],sheet_row=first['sheet_row'],
                  start_date=min(r['date'] for r in rows),end_date=max(r['date'] for r in rows),days_checked=len(rows),
                  candidate_counts=json.dumps(candidates,ensure_ascii=False,sort_keys=True),
                  observed_candidate_keycodes='|'.join(seen),grade=grade,
                  evidence_dates=json.dumps({k:sorted(v) for k,v in evidence_days.items()},ensure_ascii=False,sort_keys=True))
        result.append(item)
        if len(seen)==1:
            key=seen[0]
            confirmed.append(dict(sheet_row=first['sheet_row'],route_name=identity[0],direction=identity[1],
                departure_time=identity[2],keycode=key,route_id=candidates[key]['route_id'],grade=grade,
                start_date=item['start_date'],end_date=item['end_date'],
                actual_count=candidates[key]['actual_count'],interpolated_count=candidates[key]['interpolated_count'],
                observation_date_count=len(evidence_days[key]),
                observation_count=candidates[key]['actual_count']+candidates[key]['interpolated_count'],
                evidence_dates='|'.join(sorted(evidence_days[key]))))
    return result,confirmed


def run(args, observations=None):
    start=date.fromisoformat(args.start_date); end=date.fromisoformat(args.end_date)
    if end<start:
        raise obs.InputError('날짜 범위 역전')
    rules=windows.Rules.load(ROOT/'tools/config/window_rules.csv')
    mapping=windows.load_mapping(args.mapping,rules)
    departures=windows.read_csv(args.unresolved)
    # Validate the input really contains ONLY the original unresolved departures.
    source,_,_=windows.read_workbook(args.workbook,'관내',rules)
    trips=windows.load_pattern_trips(args.patterns,mapping)
    confirmed,failed=windows.map_departures(source,mapping,{},trips)
    identities=lambda rows,side: {(str(r['sheet_row']),r[side],r['departure_time']) for r in rows}
    expected=identities(failed,'side'); provided=identities(departures,'direction')
    if len(departures)!=67 or len(confirmed)!=266 or len(expected)!=67 or provided!=expected or len(provided)!=len(departures):
        raise obs.InputError('확정 266회 제외/미확정 67회 입력 검증 실패')
    for row in departures:
        candidate_routes(row,mapping)
    interpolation=obs.InterpolationRules.load(args.interpolation_rules)
    if observations is None:
        observations=obs.load_observations(args,start-timedelta(days=1),end+timedelta(days=2))
    result,actual,passages,unknown=recheck(departures,observations,mapping,interpolation,start,end)
    out=Path(args.output_dir); out.mkdir(parents=True,exist_ok=True)
    aggregate,mapping_confirmed=aggregate_departures(result)
    if len(aggregate)!=67:
        raise obs.InputError(f'고유 출발 수 불일치: {len(aggregate)} != 67')
    windows.write_csv(out/'unresolved_recheck_daily.csv',result,list(result[0]))
    windows.write_csv(out/'unresolved_recheck.csv',aggregate,list(aggregate[0]))
    windows.write_csv(out/'confirmed_mapping.csv',mapping_confirmed,
        ['sheet_row','route_name','direction','departure_time','keycode','route_id','grade','start_date','end_date',
         'actual_count','interpolated_count','observation_date_count','observation_count','evidence_dates'])
    totals=Counter(r['grade'] for r in result)
    unique_totals=Counter(r['grade'] for r in aggregate)
    lines=[f'미확정 출발 재검사: {start} ~ {end} (KST)',
           '결과 상태: '+('잠정(10-23 오후 관측 종료 후 재실행 필요)' if getattr(args,'provisional',False) else '재검사 완료'),
           '입력 검증: 확정 266회 제외, 미확정 67회만 포함',
           '대표 등급은 고유 (노선 번호, 방향, 출발 시각) 기준. 기간 전체 후보 합집합 1개만 확정 목록으로 분리.',
           '하루라도 다른 후보가 관측되면 모호. 다수결/마지막 날짜 우선으로 선택하지 않음.',
           '고유 출발: 67건; '+', '.join(f'{g} {unique_totals[g]}건' for g in GRADES),
           f'창 계산 입력: 기존 266회 + 추가 {len(mapping_confirmed)}회 = {266+len(mapping_confirmed)}회; 백업 전용 {67-len(mapping_confirmed)}회',
           '차량 배차 정보 없이 같은 노선 번호의 모든 차량 관측을 비교. 방향/기점 출발을 확정하지 않음.',
           f'±15분(경계 포함), source=TAGO_OBSERVATION만 사용. 누락 {interpolation.max_gap_stops}개/시간 {interpolation.max_gap_minutes:g}분 이내 보간.',
           'actual_count와 interpolated_count 분리. 보간은 대응 근거에만 사용하고 운행 판정에는 사용하지 않음.',
           f'기간 내 실제 관측: {sum(start<=o.at.date()<=end for o in actual)}행',
           f'기간 내 보간: {sum(o.interpolated and start<=o.at.date()<=end for o in passages)}행',
           f'출발×날짜 합계: {len(result)}건',
           '날짜별 중복 포함 등급 합계(고유 출발 수 아님): '+', '.join(f'{g} {totals[g]}건' for g in GRADES),
           '[날짜별 등급]']
    for day in sorted({r['date'] for r in result}):
        counts=Counter(r['grade'] for r in result if r['date']==day)
        lines.append(day+': '+', '.join(f'{g} {counts[g]}건' for g in GRADES))
    lines+=obs.origin_review_lines(actual,obs.load_route_stop_orders(args.route_stops),start,end)
    lines+=['원본 관측/DB/운영 설정 변경 없음. TAGO HTTP 호출 없음. 확정 목록은 오프라인 창 계산 입력용이며 운영 배정 변경 없음.']
    (out/'summary.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8-sig')
    print('\n'.join(lines))
    return aggregate,mapping_confirmed


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unresolved',default=str(ROOT/'reports/window-plan/unresolved_departures.csv'))
    parser.add_argument('--mapping',default=str(ROOT/'군민운수 운행관리시스템/routeid&kecode_matching_ts.csv'))
    parser.add_argument('--patterns',default=str(ROOT/'군민운수 운행관리시스템/관내 운행 패턴.csv'))
    parser.add_argument('--workbook',default=str(ROOT/'장성교통 노선도/군민운수 자료/노선(사이텍).xlsx'))
    parser.add_argument('--interpolation-rules',default=str(ROOT/'tools/config/interpolation_rules.json'))
    parser.add_argument('--start-date',default='2026-10-16'); parser.add_argument('--end-date',default='2026-10-22')
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--observations-csv'); source.add_argument('--podman-container')
    parser.add_argument('--db-user',default='smartbis_user'); parser.add_argument('--db-name',default='smartbis_db')
    parser.add_argument('--db-host'); parser.add_argument('--db-port')
    parser.add_argument('--output-dir',required=True)
    parser.add_argument('--provisional',action='store_true',help='Mark partial observation-day results as provisional')
    parser.add_argument('--route-stops',default=str(ROOT/'장성교통 노선도/TAGO_장성군_노선별_경유정류소_20261002.csv'))
    args=parser.parse_args(); args.include_tago=False
    run(args)


if __name__=='__main__':
    main()
