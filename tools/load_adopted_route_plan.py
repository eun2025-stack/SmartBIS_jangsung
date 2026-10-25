#!/usr/bin/env python3
"""Load a disabled V015 route summary; keep windows and sweep details in an offline manifest.

Never migrates, creates tables, calls TAGO or updates collector configuration.
"""
import argparse
import contextlib
import hashlib
import io
import json
import re
import subprocess
import sys
from datetime import date,datetime
from pathlib import Path

import window_plan as w
import final_observation_run as integrity

ROOT=Path(__file__).resolve().parents[1]
TABLE_COLUMNS={
    'vehicle_route_pattern_daily':['vehicle_number','service_date','day_type','pattern_code','pattern_family','matched_count','source','created_at'],
    'route_call_plan':['route_id','route_group','interval_seconds','enabled','updated_at'],
    'guard_usage_daily':['api_name','usage_date','call_count','limit_count','blocked_at']}


def path(value):
    p=Path(value)
    return p if p.is_absolute() else ROOT/p


def build_plan(config):
    if config.get('enabled') is not False:
        raise w.InputError('enabled must be false')
    date.fromisoformat(config['plan_date'])
    rules=w.Rules.load(path(config['window_rules_csv']))
    for key in ('pre_departure_minutes','default_trip_minutes','post_trip_minutes','hundred_interval_seconds',
                'hundred_start','hundred_end','backup_start','backup_end','daily_budget','retry_reserve'):
        rules.values[key]=str(config[key])
    rules.values['local_intervals_seconds']=str(config['local_interval_seconds'])
    rules.values['backup_intervals_seconds']=str(config['backup_interval_seconds'])
    rules.values['backup_route_count']=str(config['expected_sweep_routes'])
    rules.values['expected_hundred_routes']=str(config['expected_hundred_routes'])
    mapping=w.load_mapping(path(config['mapping_csv']),rules)
    departures,_,_=w.read_workbook(path(config['workbook']),'관내',rules)
    trips=w.load_pattern_trips(path(config['patterns_csv']),mapping)
    base,failed=w.map_departures(departures,mapping,{},trips)
    strict_path=path(config['confirmed_mapping_strict_csv'])
    strict=w.read_csv(strict_path)
    if any(int(r['observation_date_count'])<2 for r in strict):
        raise w.InputError('Strict mapping requires at least two evidence dates')
    overrides=w.confirmed_mapping_overrides(strict_path,failed,mapping)
    mapped,failed=w.map_departures(departures,mapping,overrides,trips)
    if (len(base)!=266 or len(mapped)!=config['expected_confirmed_trips'] or
            len(failed)!=config['expected_backup_only_trips'] or len(mapped)+len(failed)!=333):
        raise w.InputError('293 confirmed / 40 backup-only / 333 total validation failed')
    wins=w.build_windows(mapped,rules)
    if set(wins)&mapping[3]:
        raise w.InputError('100-series route found in local windows')
    if len(mapping[2])!=config['expected_sweep_routes'] or len(mapping[3])!=config['expected_hundred_routes']:
        raise w.InputError('Route counts are not 105 / 17')
    local_calls=sum(w.calls(a,b,config['local_interval_seconds']) for items in wins.values() for a,b,_ in items)
    budget=w.budget({config['local_interval_seconds']:local_calls},len(mapping[3]),rules)[0]
    if budget['total_calls']!=config['expected_budget'] or not budget['within_budget'] or not budget['retry_reserve_ok']:
        raise w.InputError('Adopted 8679 budget validation failed: '+str(budget))
    records=[]
    for route,name in sorted(mapping[2].items()):
        kind='100번' if route in mapping[3] else '관내'
        interval=config['hundred_interval_seconds'] if kind=='100번' else config['local_interval_seconds'] if route in wins else config['backup_interval_seconds']
        records.append(dict(route_id=route,route_name=name,route_group=kind,interval_seconds=interval,enabled=False,
            windows=[dict(start=w.clock(a),end=w.clock(b),departure_times=sorted(times),interval_seconds=config['local_interval_seconds'],enabled=False)
                     for a,b,times in wins.get(route,[])],
            hundred_service=dict(start=config['hundred_start'],end=config['hundred_end'],interval_seconds=config['hundred_interval_seconds'],enabled=False) if kind=='100번' else None,
            backup_sweep=dict(start=config['backup_start'],end=config['backup_end'],interval_seconds=config['backup_interval_seconds'],enabled=False)))
    return dict(plan_date=config['plan_date'],enabled=False,budget=budget,routes=records,
        confirmed_departures=[dict(sheet_row=d.sheet_row,route_id=d.route_id,route_name=d.route_name,direction=d.side,departure_time=w.clock(d.departure)) for d in mapped],
        backup_only_departures=failed,
        schema_note='DB route_call_plan is a single disabled row per route_id. Windows/parallel 105-route sweep/date are in this offline manifest; not an activated scheduler.',
        untouched_tables=['vehicle_route_pattern_daily: no confirmed vehicle/date patterns to seed','guard_usage_daily: no fabricated API usage'])


def psql(args,sql):
    command=['podman','exec','-i','-e','PGCLIENTENCODING=UTF8',args.container,'psql','-X','-q','-A','-t',
             '-U',args.db_user,'-d',args.db_name,'-v','ON_ERROR_STOP=1','-P','pager=off']
    result=subprocess.run(command,input=sql.encode('utf-8'),capture_output=True,timeout=60)
    if result.returncode:
        raise w.InputError('PostgreSQL transaction failed; connection rollback: '+result.stderr.decode('utf-8',errors='replace'))
    lines=[line for line in result.stdout.decode('utf-8-sig').splitlines() if line.strip()]
    return json.loads(lines[-1]) if lines else None


def db_state(args):
    return psql(args,"""BEGIN TRANSACTION READ ONLY;
SELECT jsonb_build_object(
 'database',current_database(),'user',current_user,
 'flyway_schema_history',to_regclass('public.flyway_schema_history'),
 'columns',(SELECT jsonb_object_agg(table_name,cols) FROM
   (SELECT table_name,jsonb_agg(column_name ORDER BY ordinal_position) cols FROM information_schema.columns
    WHERE table_schema='public' AND table_name IN ('vehicle_route_pattern_daily','route_call_plan','guard_usage_daily') GROUP BY table_name) c),
 'counts',jsonb_build_object('vehicle_route_pattern_daily',(SELECT count(*) FROM vehicle_route_pattern_daily),
    'route_call_plan',(SELECT count(*) FROM route_call_plan),'guard_usage_daily',(SELECT count(*) FROM guard_usage_daily)),
 'rows',COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY route_id) FROM route_call_plan r),'[]'::jsonb));
ROLLBACK;""")


def load_sql(records):
    if len(records)!=105 or len({r['route_id'] for r in records})!=105 or any(r['enabled'] is not False for r in records):
        raise w.InputError('Exactly 105 unique disabled routes required')
    values=[]
    for row in records:
        if (not re.fullmatch(r'TSB\d{9}',row['route_id']) or row['route_group'] not in ('관내','100번')
                or type(row['interval_seconds']) is not int or row['interval_seconds']<=0):
            raise w.InputError('Invalid route record')
        values.append(f"('{row['route_id']}','{row['route_group']}',{row['interval_seconds']},false)")
    ids=','.join("'"+r['route_id']+"'" for r in records)
    return """BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='30s';
DO $$ BEGIN PERFORM pg_advisory_xact_lock(15,53424954); END $$;
LOCK TABLE route_call_plan IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM route_call_plan WHERE enabled) THEN
  RAISE EXCEPTION 'Active plan found; refusing to change it';
 END IF;
 IF EXISTS (SELECT 1 FROM route_call_plan WHERE route_id NOT IN ("""+ids+""")) THEN
  RAISE EXCEPTION 'Unrelated plan rows found; refusing to overwrite user data';
 END IF;
END $$;
WITH changed AS (
 INSERT INTO route_call_plan(route_id,route_group,interval_seconds,enabled) VALUES
"""+',\n'.join(values)+"""
 ON CONFLICT(route_id) DO UPDATE SET route_group=EXCLUDED.route_group,
  interval_seconds=EXCLUDED.interval_seconds,enabled=false,updated_at=CURRENT_TIMESTAMP
 WHERE route_call_plan.enabled=false AND
   (route_call_plan.route_group IS DISTINCT FROM EXCLUDED.route_group OR
    route_call_plan.interval_seconds IS DISTINCT FROM EXCLUDED.interval_seconds)
 RETURNING route_id
)
SELECT jsonb_build_object('affected_rows',(SELECT count(*) FROM changed));
DO $$ BEGIN
 IF (SELECT count(*) FROM route_call_plan)<>105 OR EXISTS(SELECT 1 FROM route_call_plan WHERE enabled) THEN
  RAISE EXCEPTION '105 disabled plans validation failed';
 END IF;
END $$;
COMMIT;"""


def verify_state(state,manifest):
    if state['database']!='smartbis_db' or state['columns']!=TABLE_COLUMNS:
        raise w.InputError('Database/schema mismatch; V015 only must be applied first')
    actual={r['route_id']:(r['route_group'],r['interval_seconds'],r['enabled']) for r in state['rows']}
    expected={r['route_id']:(r['route_group'],r['interval_seconds'],False) for r in manifest['routes']}
    if actual!=expected:
        raise w.InputError('Loaded disabled route summaries do not match manifest')


def run(args):
    cfg_path=path(args.config)
    config=json.loads(cfg_path.read_text(encoding='utf-8-sig'))
    manifest=build_plan(config)
    report=path(args.output_dir)
    report.mkdir(parents=True,exist_ok=True)
    before_hashes=integrity.protected_hashes()
    before_status=integrity.git_status()
    (report/'route_plan_manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    (report/'adopted_route_plan.json').write_bytes(cfg_path.read_bytes())
    if not args.apply:
        print('DRY RUN: validated 293/40/17/105; budget='+str(manifest['budget']['total_calls'])+'; DB not touched')
        return
    before=db_state(args)
    if before['columns']!=TABLE_COLUMNS or before['database']!='smartbis_db':
        raise w.InputError('Wrong schema/database; stop before any write')
    (report/'db_before.json').write_text(json.dumps(before,ensure_ascii=False,indent=2),encoding='utf-8')
    sql=load_sql(manifest['routes'])
    first_result=psql(args,sql)
    first=db_state(args); verify_state(first,manifest)
    second_result=psql(args,sql) if args.verify_idempotency else None
    after=db_state(args); verify_state(after,manifest)
    if args.verify_idempotency and (first['counts']!=after['counts'] or first['rows']!=after['rows'] or second_result['affected_rows']!=0):
        raise w.InputError('Second load was not idempotent')
    for name in ('vehicle_route_pattern_daily','guard_usage_daily'):
        if before['counts'][name]!=after['counts'][name]:
            raise w.InputError('Unrequested data change in '+name)
    if integrity.protected_hashes()!=before_hashes:
        raise w.InputError('Original/operating configuration hash changed')
    audit=dict(applied_at=datetime.now().astimezone().isoformat(),config=str(cfg_path),
        before=before,first_load=first_result,second_load=second_result,after=after,
        source_hashes=before_hashes,git_status_before=before_status,git_status_after=integrity.git_status())
    (report/'load_audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
    b=manifest['budget']
    lines=['V015 applied separately: 3 tables exist; Flyway history absent, no fabricated version entries.',
        f"관내 창 입력 회차: {len(manifest['confirmed_departures'])}; 백업 전용 회차: {len(manifest['backup_only_departures'])}",
        f"100번 계열 ID: {sum(r['route_group']=='100번' for r in manifest['routes'])}; 전체 백업 스윕 ID: {len(manifest['routes'])}",
        f"예산: 관내 {b['local_calls']} + 100번 {b['hundred_calls']} + 백업 {b['backup_calls']} = {b['total_calls']}; 여유 {b['remaining_calls']}",
        f"적재 전: {before['counts']}; 1차 후: {first['counts']}; 2차 후: {after['counts']}",
        f"1차 변경 행 수: {first_result['affected_rows']}; 2차 변경 행 수: {second_result['affected_rows'] if second_result else '미실행'}",
        'enabled=true: 0행. 기존 3테이블 외 DB 쓰기 없음. 실제 쓰기는 route_call_plan만.',
        'DB에는 route_id별 기본 주기 요약만 저장. 창/날짜/병렬 백업 스윕 세부는 route_plan_manifest.json의 비활성 계획에 보존.',
        'vehicle_route_pattern_daily는 확정 차량별 배차 정보가 없으므로 0행 유지; guard_usage_daily에 가짜 호출 사용량을 넣지 않음.',
        '운영 수집기 설정 및 원본 파일 해시 변경 없음. TAGO HTTP 호출 없음.',
        '실패 항목: 없음. V015에는 창/백업 중복 역할을 별도 저장할 컬럼이 없다는 스키마 제한은 유지.']
    (report/'summary.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8-sig')
    print('\n'.join(lines))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config',default='tools/config/adopted_route_plan.json')
    p.add_argument('--container',default='smartbis_jangsung-postgres-db-1')
    p.add_argument('--db-user',default='smartbis_user'); p.add_argument('--db-name',default='smartbis_db')
    p.add_argument('--output-dir',default='reports/route-plan/2026-10-23')
    p.add_argument('--apply',action='store_true')
    p.add_argument('--verify-idempotency',action='store_true')
    args=p.parse_args()
    try:
        run(args)
    except (w.InputError,OSError,ValueError,subprocess.TimeoutExpired) as error:
        print(str(error),file=sys.stderr); return 2
    return 0


if __name__=='__main__':
    raise SystemExit(main())
