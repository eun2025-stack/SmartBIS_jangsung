"""Read-only run-scoped counts. No TAGO, interpolation, assignment or DB writes."""
import argparse
import json
import uuid
from pathlib import Path
import local_collection_admin as admin

def analyze(db,run_id):
    run=str(uuid.UUID(run_id))
    if uuid.UUID(run).int==0:raise ValueError('ARCHIVED_PRE_RUN_EXCLUDED')
    if db.query("SELECT count(*) FROM collection_runs WHERE collection_run_id='%s'::uuid AND NOT archived;"%run)!='1':
        raise ValueError('ACTIVE_ANALYSIS_RUN_NOT_FOUND')
    result={'collection_run_id':run}
    for table,time_column in (('bus_positions','observed_at'),('arrival_predictions','observed_at')):
        actual=("AND d.source='TAGO_OBSERVATION' AND COALESCE(d.raw_payload->>'interpolated','false') NOT IN ('true','1')"
            if table=='bus_positions' else 'AND d.is_final AND NOT d.interpolated')
        sql="""SELECT count(*) FROM %s d
          WHERE d.collection_run_id='%s'::uuid %s AND NOT EXISTS (
            SELECT 1 FROM collection_pause_intervals p WHERE p.collection_run_id=d.collection_run_id
              AND p.exclude_from_analysis AND d.%s>=p.started_at
              AND (p.ended_at IS NULL OR d.%s<p.ended_at));"""%(table,run,actual,time_column,time_column)
        result[table+'_outside_pauses']=int(db.query(sql))
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--collection-run-id',required=True);p.add_argument('--output',required=True);args=p.parse_args()
    output=Path(args.output).resolve()
    if admin.ROOT/'.tmp' not in output.parents:raise ValueError('OUTPUT_MUST_BE_UNDER_PROJECT_TMP')
    db=admin.Database(admin.read_env(admin.ENV))
    result=analyze(db,args.collection_run_id)
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result))

if __name__=='__main__':
    try:main()
    except Exception as error:print('분석 중단: '+type(error).__name__);raise SystemExit(1)
