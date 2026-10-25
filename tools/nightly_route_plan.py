#!/usr/bin/env python3
"""Nightly decisions: observations SELECT-only, route plans proposal-only, no HTTP/guard.

--apply-decisions writes ONLY vehicle_route_pattern_daily. Migrations are separate.
"""
import argparse
from collections import Counter, defaultdict
from datetime import date, timedelta
from fractions import Fraction
import json
import re
import sys
import observation_compare as obs
import window_plan as w
import unresolved_recheck as recheck
import load_adopted_route_plan as adopted
from load_route_plan_windows import literal

ROOT=adopted.ROOT
SOURCE='NIGHTLY_OBSERVATION_V1'


def real_observations(rows,start,end):
    raw=[r for r in rows if r.get('source')=='TAGO_OBSERVATION'
         and str(r.get('interpolated',False)).lower() not in ('true','1')]
    parsed=obs.parse_observations(raw)
    names={obs.vehicle_id(r['vehicle_number']):r['vehicle_number'] for r in raw}
    return [o for o in parsed if start<=o.at.date()<=end],names


def read_observations(args,start,end):
    if args.observations_csv:
        return real_observations(w.read_csv(args.observations_csv),start,end)
    sql="""BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout='60s';
SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY observed_at,vehicle_number),'[]'::jsonb)
FROM (SELECT vehicle_number,route_id,observed_at,node_order,node_id,source,
 COALESCE(raw_payload->>'interpolated','false') AS interpolated
 FROM bus_positions WHERE source='TAGO_OBSERVATION'
 AND observed_at >= """+literal(str(start)+' 00:00+09')+"""::timestamptz
 AND observed_at < """+literal(str(end+timedelta(days=1))+' 00:00+09')+"""::timestamptz) p;
ROLLBACK;"""
    return real_observations(adopted.psql(args,sql),start,end)


def unique_evidence(rows):
    """Union candidates, not majority vote; repeated rows on one date count once."""
    grouped=defaultdict(set)
    for row in rows:
        for key in filter(None,row['observed_candidate_keycodes'].split('|')):
            grouped[key].add(row['date'])
    if len(grouped)!=1:
        return None,0,'AMBIGUOUS' if grouped else 'NO_EVIDENCE'
    key,days=next(iter(grouped.items()))
    return key,len(days),'CONFIRMED' if len(days)>=2 else 'INSUFFICIENT_DAYS'


def decide_vehicles(observations,names,patterns,local,hundred,calendar,start,target):
    actual=[o for o in observations if not o.interpolated and o.source=='TAGO_OBSERVATION'
            and start<=o.at.date()<=target]
    index=obs.ObservationIndex(actual)
    vehicles=sorted({o.vehicle for o in actual})
    daily=[]; candidates=[]; details=[]; evidence=defaultdict(list)
    day=start
    while day<=target:
        day_type='휴일' if calendar.is_holiday(day) else '평일'
        for vehicle in vehicles:
            rows=[o for o in index.rows.get(vehicle,[]) if o.at.date()==day]
            record=dict(vehicle_number=names[vehicle],service_date=str(day),day_type=day_type,
                pattern_code=None,pattern_family=None,matched_count=0,evidence_date_count=0,
                decision_status='NO_OBSERVATIONS',source=SOURCE,decision_details={})
            scored=[]
            if rows:
                for code,expected in sorted(patterns.items()):
                    if not obs.pattern_allowed(code,day,calendar):
                        continue
                    score,items=obs.evaluate(index,vehicle,day,code,expected,{},15,
                        hundred if obs.family(code)=='100' else local)
                    rank=(Fraction(score['matched'],score['expected']),score['matched'],-score['mismatched'])
                    scored.append((rank,code,score,items))
                    candidates.append(dict(vehicle_number=names[vehicle],service_date=str(day),**score))
                best=max((s[0] for s in scored),default=None)
                winners=[s for s in scored if s[0]==best and s[2]['matched']>0]
                record['decision_status']='AMBIGUOUS' if len(winners)>1 else 'NO_EVIDENCE'
                if len(winners)==1:
                    _,code,score,items=winners[0]
                    record.update(pattern_code=code,pattern_family=re.match(r'[a-z]+',code)[0],
                        matched_count=score['matched'],decision_status='INSUFFICIENT_DAYS')
                    details.extend(dict(full_vehicle_number=names[vehicle],**item) for item in items)
                if winners:
                    evidence[vehicle,day_type].append(dict(date=str(day),
                        observed_candidate_keycodes='|'.join(s[1] for s in winners)))
                record['decision_details']=dict(candidates=[s[1] for s in winners],actual_rows=len(rows))
            daily.append(record)
        day+=timedelta(days=1)
    for row in daily:
        if row['decision_status'] in ('NO_OBSERVATIONS','NO_EVIDENCE'):
            continue
        eligible=[r for r in evidence[obs.vehicle_id(row['vehicle_number']),row['day_type']]
                  if r['date']<=row['service_date']]
        code,count,status=unique_evidence(eligible)
        if row['decision_status']=='AMBIGUOUS' or code and code!=row['pattern_code']:
            status='AMBIGUOUS'
        row.update(evidence_date_count=count,decision_status=status)
        row['decision_details']['evidence_dates']=[r['date'] for r in eligible
            if code and r['observed_candidate_keycodes']==code]
    return daily,candidates,details


def proposed_plan(config,actual,start,target,out):
    rules=w.Rules.load(adopted.path(config['window_rules_csv']))
    for key in ('pre_departure_minutes','default_trip_minutes','post_trip_minutes','hundred_interval_seconds',
                'hundred_start','hundred_end','backup_start','backup_end','daily_budget','retry_reserve'):
        rules.values[key]=str(config[key])
    rules.values.update(local_intervals_seconds=str(config['local_interval_seconds']),
        backup_intervals_seconds=str(config['backup_interval_seconds']),backup_route_count=str(config['expected_sweep_routes']))
    mapping=w.load_mapping(adopted.path(config['mapping_csv']),rules)
    departures,warnings,_=w.read_workbook(adopted.path(config['workbook']),'관내',rules)
    trips=w.load_pattern_trips(adopted.path(config['patterns_csv']),mapping)
    base,failures=w.map_departures(departures,mapping,{},trips)
    unresolved=w.unresolved_details(failures,mapping,adopted.path(config['workbook']),'관내')
    if (len(departures),len(base),len(unresolved))!=(333,266,67):
        raise obs.InputError('Baseline differs from 266 + 67 = 333; stop')
    actual=[o for o in actual if not o.interpolated and o.source=='TAGO_OBSERVATION']
    observed_days={o.at.date() for o in actual}
    daily,_,_,_=recheck.recheck(unresolved,actual,mapping,
        obs.InterpolationRules.load(ROOT/'tools/config/interpolation_rules.json'),start,target)
    daily=[r for r in daily if date.fromisoformat(r['date']) in observed_days]
    _,provisional=recheck.aggregate_departures(daily) if daily else ([],[])
    strict=[r for r in provisional if r['observation_date_count']>=2]
    fields=['sheet_row','route_name','direction','departure_time','keycode','route_id','grade','start_date','end_date',
            'actual_count','interpolated_count','observation_date_count','observation_count','evidence_dates']
    strict_path=out/'confirmed_mapping_strict.csv'
    w.write_csv(strict_path,strict,fields)
    mapped,backup=w.map_departures(departures,mapping,w.confirmed_mapping_overrides(strict_path,failures,mapping),trips)
    wins=w.build_windows(mapped,rules)
    if set(wins)&mapping[3] or len(mapped)+len(backup)!=333:
        raise obs.InputError('Local/hundred partition or total failed')
    budget=w.budget({config['local_interval_seconds']:sum(w.calls(a,b,config['local_interval_seconds'])
        for items in wins.values() for a,b,_ in items)},len(mapping[3]),rules)[0]
    return dict(proposal_only=True,enabled=False,service_date=str(target+timedelta(days=1)),
        evidence_start=str(start),evidence_end=str(target),base_confirmed=len(base),strict_additional=len(strict),
        confirmed_departures=len(mapped),backup_only_departures=len(backup),budget=budget,warnings=warnings,
        routes=[dict(route_id=route,enabled=False,local_windows=[
            dict(start=w.clock(a),end=w.clock(b),interval_seconds=config['local_interval_seconds'])
            for a,b,_ in wins.get(route,[])]) for route in sorted(mapping[2])],
        note='Proposal only. Existing hundred-service/backup columns retained; no vehicle dispatch assignment.')


def decision_sql(records):
    if not records:
        return "BEGIN; SELECT jsonb_build_object('affected_rows',0); COMMIT;"
    if len({(r['vehicle_number'],r['service_date']) for r in records})!=len(records):
        raise obs.InputError('Duplicate vehicle/date decision')
    values=[]
    for r in records:
        if r['source']!=SOURCE or r['decision_status'] not in (
            'CONFIRMED','INSUFFICIENT_DAYS','AMBIGUOUS','NO_EVIDENCE','NO_OBSERVATIONS'):
            raise obs.InputError('Invalid decision source/status')
        if r['decision_status']=='CONFIRMED' and r['evidence_date_count']<2:
            raise obs.InputError('Strict decision requires two days')
        values.append('('+','.join([literal(r['vehicle_number']),literal(r['service_date'])+'::date',
            literal(r['day_type']),literal(r['pattern_code']) if r['pattern_code'] else 'NULL',
            literal(r['pattern_family']) if r['pattern_family'] else 'NULL',str(int(r['matched_count'])),
            literal(SOURCE),literal(r['decision_status']),str(int(r['evidence_date_count'])),
            literal(json.dumps(r['decision_details'],ensure_ascii=False))+'::jsonb'])+')')
    dates=','.join(sorted({literal(r['service_date'])+'::date' for r in records}))
    return """BEGIN;
SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='30s';
SELECT pg_advisory_xact_lock(17,53424954);
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM vehicle_route_pattern_daily WHERE service_date IN ("""+dates+""")
 AND source<>'NIGHTLY_OBSERVATION_V1') THEN
 RAISE EXCEPTION 'Non-batch records exist; refusing overwrite'; END IF;
END $$;
WITH changed AS (
INSERT INTO vehicle_route_pattern_daily
(vehicle_number,service_date,day_type,pattern_code,pattern_family,matched_count,source,decision_status,evidence_date_count,decision_details)
VALUES """+',\n'.join(values)+"""
ON CONFLICT(vehicle_number,service_date) DO UPDATE SET
 day_type=EXCLUDED.day_type,pattern_code=EXCLUDED.pattern_code,pattern_family=EXCLUDED.pattern_family,
 matched_count=EXCLUDED.matched_count,decision_status=EXCLUDED.decision_status,
 evidence_date_count=EXCLUDED.evidence_date_count,decision_details=EXCLUDED.decision_details,evaluated_at=CURRENT_TIMESTAMP
WHERE vehicle_route_pattern_daily.source=EXCLUDED.source AND
 (vehicle_route_pattern_daily.day_type,vehicle_route_pattern_daily.pattern_code,vehicle_route_pattern_daily.pattern_family,
 vehicle_route_pattern_daily.matched_count,vehicle_route_pattern_daily.decision_status,
 vehicle_route_pattern_daily.evidence_date_count,vehicle_route_pattern_daily.decision_details)
 IS DISTINCT FROM
 (EXCLUDED.day_type,EXCLUDED.pattern_code,EXCLUDED.pattern_family,EXCLUDED.matched_count,EXCLUDED.decision_status,
 EXCLUDED.evidence_date_count,EXCLUDED.decision_details)
RETURNING 1)
SELECT jsonb_build_object('affected_rows',(SELECT count(*) FROM changed));
COMMIT;"""


def persist_decisions(args,daily):
    before=adopted.db_state(args)
    if not {'decision_status','evidence_date_count','decision_details','evaluated_at'}<=set(before['columns']['vehicle_route_pattern_daily']):
        raise obs.InputError('Apply V017 separately first')
    if any(r['enabled'] for r in before['rows']):
        raise obs.InputError('Active plans found; stop')
    sql=decision_sql(daily)
    first=adopted.psql(args,sql); repeat=adopted.psql(args,sql)
    after=adopted.db_state(args)
    if repeat['affected_rows']!=0 or before['rows']!=after['rows'] or before['counts']['guard_usage_daily']!=after['counts']['guard_usage_daily']:
        raise obs.InputError('Idempotency/plan/guard preservation failed')
    return first,repeat


def run(args):
    target=date.fromisoformat(args.target_date); start=date.fromisoformat(args.history_start)
    if start>target:
        raise obs.InputError('history-start exceeds target-date')
    out=adopted.path(args.output_dir)
    if out.exists():
        raise obs.InputError('Output directory exists; choose fresh directory')
    hashes=adopted.integrity.protected_hashes(); before_status=adopted.integrity.git_status()
    config=json.loads(adopted.path(args.config).read_text(encoding='utf-8-sig'))
    if config['enabled'] is not False:
        raise obs.InputError('Configuration must stay disabled')
    actual,names=read_observations(args,start,target)
    keys,routes,hundred=obs.load_mapping(adopted.path(config['mapping_csv']))
    patterns,local,excluded,warnings=obs.load_patterns([adopted.path(config['patterns_csv']),
        ROOT/'군민운수 운행관리시스템/광주 운행 패턴.csv'],keys,routes,hundred)
    calendar=obs.load_calendar(ROOT/'tools/config/holidays_2026.csv')
    daily,candidates,details=decide_vehicles(actual,names,patterns,local,hundred,calendar,start,target)
    out.mkdir(parents=True,exist_ok=False)
    proposal=proposed_plan(config,actual,start,target,out)
    (out/'route_call_plan_proposal.json').write_text(json.dumps(proposal,ensure_ascii=False,indent=2),encoding='utf-8')
    for name,records,fields in (
        ('vehicle_decisions.csv',daily,['vehicle_number','service_date','day_type','pattern_code','pattern_family',
            'matched_count','evidence_date_count','decision_status','source','decision_details']),
        ('pattern_candidates.csv',candidates,['vehicle_number','service_date','vehicle','date','pattern','expected','matched',
            'mismatched','pending_missing','match_rate_pct','evidence_coverage_pct','matched_expected_pct']),
        ('expected_results.csv',details,['full_vehicle_number','vehicle','date','pattern','time','expected_route','status','observed_routes'])):
        export=[dict(r,decision_details=json.dumps(r['decision_details'],ensure_ascii=False))
                if 'decision_details' in r else r for r in records]
        w.write_csv(out/name,export,fields)
    first=repeat=None
    if args.apply_decisions:
        first,repeat=persist_decisions(args,daily)
    if hashes!=adopted.integrity.protected_hashes():
        raise obs.InputError('Protected originals/settings changed')
    day_counts=Counter(o.at.date() for o in actual); states=Counter(r['decision_status'] for r in daily)
    audit=dict(target_date=str(target),history_start=str(start),observed_dates=len(day_counts),actual_rows=len(actual),
        days={str(start+timedelta(days=n)):day_counts[start+timedelta(days=n)] for n in range((target-start).days+1)},
        decision_status_counts=dict(states),strict_additional=proposal['strict_additional'],
        confirmed_departures=proposal['confirmed_departures'],backup_only_departures=proposal['backup_only_departures'],
        budget=proposal['budget'],first_write=first,repeat_write=repeat,protected_hashes=hashes,
        git_status_before=before_status,git_status_after=adopted.integrity.git_status(),warnings=warnings,excluded=excluded)
    (out/'audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
    text='\n'.join([f'판정 대상: {start} ~ {target}, 관측 날짜 {len(day_counts)} / 기간 {(target-start).days+1}일',
        f"출발 대응 엄격 확정: 기존 {proposal['base_confirmed']} + 추가 {proposal['strict_additional']} = {proposal['confirmed_departures']}; 백업 전용 {proposal['backup_only_departures']}",
        f'차량·날짜 판정 {len(daily)}건: {dict(states)}',
        '관측 없는 날짜: '+', '.join(k for k,v in audit['days'].items() if not v),
        f"예산: {proposal['budget']}",f'DB 판정 이력 기록: {first}; 재실행: {repeat}',
        '실제 관측만 운행 판정. 보간은 출발 대응 근거 전용. 유일 후보 + 서로 다른 2일 이상만 엄격 확정.',
        '차량 패턴: 요일 유형별 일별 최고 점수 유일 후보를 근거로 사용. 동점/경쟁 후보는 모호. 과거 날짜에 미래 근거 소급 금지.',
        '계획은 제안 파일만, enabled=false. 수집기/guard/운영설정/관측 모드/원본 무변경.',
        '입력 경고: '+str(warnings),'실패 항목: 없음'])+'\n'
    (out/'summary.txt').write_text(text,encoding='utf-8-sig'); print(text)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--target-date',required=True,help='Last observation day KST; proposal is next day')
    p.add_argument('--history-start',required=True,help='Inclusive evidence start, never reads future days')
    p.add_argument('--config',default='tools/config/adopted_route_plan.json')
    p.add_argument('--observations-csv')
    p.add_argument('--container',default='smartbis_jangsung-postgres-db-1')
    p.add_argument('--db-user',default='smartbis_user'); p.add_argument('--db-name',default='smartbis_db')
    p.add_argument('--output-dir',required=True)
    p.add_argument('--apply-decisions',action='store_true',help='Write ONLY decision audit, never route plans')
    try:
        run(p.parse_args())
    except Exception as error:
        print(str(error),file=sys.stderr); return 2
    return 0


if __name__=='__main__':
    raise SystemExit(main())
