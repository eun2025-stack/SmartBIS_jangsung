#!/usr/bin/env python3
"""Populate V016 metadata without enabling routes or making HTTP calls."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import load_adopted_route_plan as adopted


def literal(value):
    return "'"+str(value).replace("'","''")+"'"


def window_sql(manifest):
    if manifest.get('enabled') is not False or len(manifest['routes'])!=105:
        raise adopted.w.InputError('105 disabled plans required')
    rows=[]
    for route in manifest['routes']:
        if route['enabled'] is not False or route['backup_sweep']['enabled'] is not False:
            raise adopted.w.InputError('Refusing to enable plans')
        windows=[dict(start=w['start'],end=w['end'],interval_seconds=w['interval_seconds']) for w in route['windows']]
        service=route['hundred_service']; backup=route['backup_sweep']
        start=str(adopted.w.seconds(service['start'])) if service else 'NULL'
        end=str(adopted.w.seconds(service['end'])) if service else 'NULL'
        rows.append('('+','.join([literal(route['route_id']),literal(json.dumps(windows,ensure_ascii=False))+'::jsonb',start,end,
            str(backup['interval_seconds']),str(adopted.w.seconds(backup['start'])),str(adopted.w.seconds(backup['end'])),
            literal(manifest['plan_date'])+'::date'])+')')
    return """BEGIN;
SET LOCAL lock_timeout='10s';
LOCK TABLE route_call_plan IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF (SELECT count(*) FROM route_call_plan)<>105 OR EXISTS(SELECT 1 FROM route_call_plan WHERE enabled) THEN
  RAISE EXCEPTION 'Require 105 disabled route summaries';
 END IF;
END $$;
WITH input(route_id,local_windows,service_start_seconds,service_end_seconds,backup_interval_seconds,backup_start_seconds,backup_end_seconds,plan_date) AS (VALUES
"""+',\n'.join(rows)+"""), changed AS (
 UPDATE route_call_plan p SET local_windows=i.local_windows,service_start_seconds=i.service_start_seconds,
  service_end_seconds=i.service_end_seconds,backup_interval_seconds=i.backup_interval_seconds,
  backup_start_seconds=i.backup_start_seconds,backup_end_seconds=i.backup_end_seconds,plan_date=i.plan_date,
  updated_at=CURRENT_TIMESTAMP
 FROM input i WHERE p.route_id=i.route_id AND p.enabled=false AND
  (p.local_windows,p.service_start_seconds,p.service_end_seconds,p.backup_interval_seconds,p.backup_start_seconds,p.backup_end_seconds,p.plan_date)
  IS DISTINCT FROM (i.local_windows,i.service_start_seconds,i.service_end_seconds,i.backup_interval_seconds,i.backup_start_seconds,i.backup_end_seconds,i.plan_date)
 RETURNING p.route_id)
SELECT jsonb_build_object('affected_rows',(SELECT count(*) FROM changed));
COMMIT;"""


def run(args):
    config=json.loads(adopted.path(args.config).read_text(encoding='utf-8-sig'))
    manifest=adopted.build_plan(config)
    before=adopted.db_state(args)
    needed={'local_windows','service_start_seconds','service_end_seconds','backup_interval_seconds','backup_start_seconds','backup_end_seconds','plan_date','failure_reason','last_failure_at','last_attempt_at'}
    if not needed<=set(before['columns']['route_call_plan']):
        raise adopted.w.InputError('V016 is not applied; no writes performed')
    hashes=adopted.integrity.protected_hashes()
    sql=window_sql(manifest)
    first=adopted.psql(args,sql); after_first=adopted.db_state(args)
    second=adopted.psql(args,sql); after=adopted.db_state(args)
    if second['affected_rows']!=0 or after_first['rows']!=after['rows']:
        raise adopted.w.InputError('V016 load is not idempotent')
    expected={r['route_id']:r for r in manifest['routes']}
    for actual in after['rows']:
        row=expected[actual['route_id']]
        windows=[dict(start=w['start'],end=w['end'],interval_seconds=w['interval_seconds']) for w in row['windows']]
        service=row['hundred_service']; backup=row['backup_sweep']
        if (actual['enabled'] or actual['local_windows']!=windows or actual['plan_date']!=manifest['plan_date']
            or actual['service_start_seconds']!=(adopted.w.seconds(service['start']) if service else None)
            or actual['service_end_seconds']!=(adopted.w.seconds(service['end']) if service else None)
            or actual['backup_interval_seconds']!=backup['interval_seconds']
            or actual['backup_start_seconds']!=adopted.w.seconds(backup['start'])
            or actual['backup_end_seconds']!=adopted.w.seconds(backup['end'])):
            raise adopted.w.InputError('DB window metadata mismatch')
    if after['counts']!=before['counts'] or hashes!=adopted.integrity.protected_hashes():
        raise adopted.w.InputError('Counts or protected original settings changed')
    out=adopted.path(args.output_dir); out.mkdir(parents=True,exist_ok=True)
    (out/'database_plan_snapshot.json').write_text(json.dumps(after,ensure_ascii=False,indent=2),encoding='utf-8')
    (out/'load_audit.json').write_text(json.dumps(dict(first=first,second=second,before=before,after=after,
        budget=manifest['budget'],protected_hashes=hashes,git_status=adopted.integrity.git_status()),ensure_ascii=False,indent=2),encoding='utf-8')
    print(f"V016 metadata loaded: first={first['affected_rows']} second={second['affected_rows']} routes={len(after['rows'])} enabled=0 budget={manifest['budget']['total_calls']}")


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config',default='tools/config/adopted_route_plan.json')
    p.add_argument('--container',default='smartbis_jangsung-postgres-db-1')
    p.add_argument('--db-user',default='smartbis_user'); p.add_argument('--db-name',default='smartbis_db')
    p.add_argument('--output-dir',default='reports/route-plan/v016-2026-10-23')
    try:
        run(p.parse_args())
    except Exception as error:
        print(str(error),file=sys.stderr); return 2
    return 0


if __name__=='__main__':
    raise SystemExit(main())
