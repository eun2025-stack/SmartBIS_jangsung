"""Local-copy setup/verification only. Never starts a collector or calls TAGO."""
import argparse
import csv
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
from urllib.parse import urlsplit,unquote

ROOT=Path(__file__).resolve().parent.parent
RESOURCES=ROOT/'server/egov-backend/src/main/resources'
TARGET='smartbis_db_copy2'
TIMEOUT='C:/Program Files/Git/usr/bin/timeout.exe'
CONTAINER='smartbis_jangsung-postgres-db-1'
ENV=ROOT/'.tmp/local-collection.env'
SETTINGS={
 'SPRING_PROFILES_ACTIVE':'local-collection','TAGO_ENABLED':'true','TAGO_VEHICLE_LOCATION_ENABLED':'true',
 'TAGO_ARRIVAL_ENABLED':'true','TAGO_LOCATION_OBSERVATION_MODE':'true','TAGO_CATALOG_SYNC_CRON':'-',
 'TAGO_MOCK_MODE':'false','TAGO_LOCATION_OPERATIONS_ENABLED':'true',
 'PUBLIC_BASE_URL':'https://jangseong.go.kr:8443',
}
PROFILE_SETTINGS={
 'tago.enabled':'true','tago.mock-mode':'false','tago.vehicle-location.enabled':'true','tago.arrival.enabled':'true',
 'tago.location.observation-mode':'true','tago.catalog.sync-cron':'-','tago.location.operations.enabled':'true',
 'tago.location.operations.daily-limit':'9500','tago.arrival.daily-limit':'9000','budget.retry_max':'2','budget.retry_gap_sec':'10',
 'tago.location.operations.retry-delay-ms':'10000','tago.location.operations.lane-a-start':'05:15',
 'tago.location.operations.lane-a-end':'21:15','tago.location.operations.lane-a-interval-seconds':'600',
 'tago.location.operations.local-lead-minutes':'15','tago.location.operations.variant-lead-minutes':'10',
 'tago.location.operations.close-after-last-minutes':'5','tago.location.operations.no-observation-max-minutes':'60',
 'tago.location.operations.local.interval-seconds':'600','tago.location.operations.hundred.interval-seconds':'600',
 'tago.location.operations.lane-c-start':'06:00','tago.location.operations.lane-c-end':'21:00',
 'tago.location.operations.lane-c-interval-seconds':'1800','tago.location.operations.lane-c-inter-route-delay-seconds':'2',
 'tago.location.operations.lane-c-429-delay-step2-seconds':'3','tago.location.operations.lane-c-429-delay-step3-seconds':'5',
 'tago.arrival.stop-step':'2','collection.capture-metadata':'true','spring.flyway.enabled':'false',
}
def read_env(path):
    result={}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            k,v=line.split('=',1);result[k.strip()]=v.strip().strip('\"\'')
    return result
def validate(values):
    url=values.get('SPRING_DATASOURCE_URL','')
    u=urlsplit(url[5:] if url.startswith('jdbc:') else '')
    if u.scheme!='postgresql' or u.hostname not in ('127.0.0.1','localhost','::1') or unquote(u.path.lstrip('/'))!=TARGET:
        raise ValueError('LOCAL_COPY_DATABASE_AND_LOOPBACK_REQUIRED')
    for key in ('SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD'):
        if not values.get(key): raise ValueError('LOCAL_CREDENTIAL_KEY_MISSING')
def bounded(args,**kwargs):
    result=subprocess.run([TIMEOUT,'30',*args],capture_output=True,timeout=35,**kwargs)
    if result.returncode: raise RuntimeError('LOCAL_COMMAND_FAILED_EXIT_'+str(result.returncode))
    return result.stdout
class Database:
    def __init__(self,values):
        validate(values)
        connections=json.loads(bounded(['podman','system','connection','list','--format','json']))
        default=[c for c in connections if c.get('Default')]
        if len(default)!=1 or urlsplit(default[0].get('URI','')).hostname not in ('127.0.0.1','localhost','::1'):
            raise ValueError('LOCAL_PODMAN_CONNECTION_REQUIRED')
        self.prefix=['podman','exec','-i','--env','PGCLIENTENCODING=UTF8',CONTAINER]
        self.flags=['-X','-w','-A','-t','-v','ON_ERROR_STOP=1','-U',values['SPRING_DATASOURCE_USERNAME'],'-d',TARGET]
        if self.query('SELECT current_database();')!=TARGET: raise ValueError('DATABASE_MISMATCH')
    def query(self,sql):
        args=self.prefix.copy();args[3:3]=['--env','PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=15000']
        return bounded([*args,'psql',*self.flags,'-c',sql]).decode('utf-8').strip()
    def apply(self,sql):
        # Entire schema and plan installation is one transaction; no CASCADE or old-data DELETE.
        return bounded([*self.prefix,'psql',*self.flags,'-1','-f','-'],input=sql.encode('utf-8'))
    def backup(self,values):
        path=ROOT/'.tmp/local-collection-before.dump'
        if path.exists(): return
        data=bounded([*self.prefix,'pg_dump','-Fc','--no-owner','-U',values['SPRING_DATASOURCE_USERNAME'],'-d',TARGET])
        if len(data)<5 or data[:5]!=b'PGDMP': raise RuntimeError('BACKUP_INVALID')
        path.write_bytes(data)
def plans():
    with (RESOURCES/'rev17-routes.csv').open(encoding='utf-8-sig') as source: routes=list(csv.DictReader(source))
    with (RESOURCES/'rev17-departures.csv').open(encoding='utf-8-sig') as source: departures=list(csv.DictReader(source))
    if len(routes)!=105 or len({r['route_id'] for r in routes})!=105: raise ValueError('ROUTE_SET_105_REQUIRED')
    lane_a={r['route_id'] for r in routes if r['lane_a'].lower()=='true'}
    lane_b={r['route_id'] for r in routes if r['lane_b_kind']}
    if len(lane_a)!=3 or len(lane_b)!=100 or lane_a&lane_b: raise ValueError('LANE_SETS_INVALID')
    source=ROOT/'노선 및 운행 관련 자료/군민운수 운행관리시스템/운행패턴.csv'
    text=source.read_text(encoding='utf-8-sig')
    source_rows=list(csv.reader(text.splitlines()))
    family_ids={key:set() for key in ('ud','ld','js','gj','hjs','hgj')}
    source_trips=[]
    for row in source_rows[1:]:
        if len(row)<2: continue
        match=re.match(r'(hjs|hgj|ud|ld|js|gj)',row[1].strip().lower())
        if not match: continue
        for index in range(2,len(row),3):
            time=row[index+1].strip() if index+1<len(row) else ''
            route=row[index+2].strip() if index+2<len(row) else ''
            if not time and not route: continue
            if not time or not re.fullmatch(r'TSB342\d{6}',route): raise ValueError('PATTERN_TRIP_INCOMPLETE')
            hour,minute=map(int,time.split(':')[:2]);time=f'{hour:02}:{minute:02}'
            family_ids[match[1]].add(route);source_trips.append((row[1].strip(),route,time))
    regular=family_ids['ud']|family_ids['ld']
    hundred=set.union(*(family_ids[f] for f in ('js','gj','hjs','hgj')))
    if regular!={r['route_id'] for r in routes if r['lane_b_kind']=='regular'} or hundred-lane_a!=lane_b-regular:
        raise ValueError('PATTERN_FAMILY_ID_SOURCE_MISMATCH')
    if sorted(source_trips)!=sorted((d['pattern'],d['route_id'],d['departure_time'][:5]) for d in departures):
        raise ValueError('PATTERN_TIMETABLE_SOURCE_MISMATCH')
    revision=uuid.uuid5(uuid.NAMESPACE_URL,hashlib.sha256((json.dumps(routes,sort_keys=True)+json.dumps(departures,sort_keys=True)+'local-v1').encode()).hexdigest())
    output=[]
    for route in routes:
        rid=route['route_id']
        if not re.fullmatch(r'TSB342\d{6}',rid): raise ValueError('ROUTE_ID_INVALID')
        windows=[]
        if rid in lane_b:
            for d in departures:
                if d['route_id']!=rid: continue
                hour,minute=map(int,d['departure_time'].split(':')[:2])
                start=max(0,hour*3600+minute*60-(15 if route['lane_b_kind']=='regular' else 10)*60)
                def time(seconds): return f'{seconds//3600:02}:{seconds//60%60:02}:{seconds%60:02}'
                window={'start':time(start),'end':time(min(86400,start+3600)),'interval_seconds':600,'day_type':d['day_type']}
                if window not in windows: windows.append(window)
        output.append({'route_id':rid,'group':'100번' if rid in lane_a or route['lane_b_kind']=='variant' else '관내',
            'windows':windows,'a':rid in lane_a,'b':rid in lane_b})
    return revision,output
def plan_sql(revision,rows):
    inserts=[]
    for r in rows:
        windows=json.dumps(r['windows'],ensure_ascii=False).replace("'","''")
        inserts.append("('%s','%s',600,true,'%s'::jsonb,%s,%s,1800,21600,75600,'%s')" %
            (r['route_id'],r['group'],windows,'18900' if r['a'] else 'NULL','76500' if r['a'] else 'NULL',revision))
    return """
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM route_call_plan WHERE plan_revision_id='%s') THEN
   UPDATE route_call_plan SET enabled=false,deactivated_at=CURRENT_TIMESTAMP,deactivation_reason='로컬 수집 계획 새 세대 생성'
   WHERE deactivated_at IS NULL;
   INSERT INTO route_call_plan(route_id,route_group,interval_seconds,enabled,local_windows,service_start_seconds,service_end_seconds,
     backup_interval_seconds,backup_start_seconds,backup_end_seconds,plan_revision_id) VALUES %s;
 END IF;
 IF (SELECT count(*) FROM route_call_plan WHERE plan_revision_id='%s' AND enabled)<>105 THEN
   RAISE EXCEPTION 'ACTIVE_LOCAL_PLAN_105_REQUIRED';
 END IF;
END $$;
""" % (revision,','.join(inserts),revision)
def prepare_env():
    values=read_env(ROOT/'.tmp/copy-measure.env');validate(values)
    selected={k:values[k] for k in ('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','TAGO_SERVICE_KEY','TAGO_CITY_CODE')}
    selected.update(SETTINGS)
    if values.get('TAGO_SERVICE_KEY_MODE'): selected['TAGO_SERVICE_KEY_MODE']=values['TAGO_SERVICE_KEY_MODE']
    now=dt.datetime.now(dt.timezone(dt.timedelta(hours=9)))
    end=(now+dt.timedelta(days=(6-now.weekday())%7)).replace(hour=21,minute=0,second=0,microsecond=0)
    if end<=now: end+=dt.timedelta(days=7)
    selected['LOCAL_COLLECTION_STOP_AT']=end.astimezone(dt.timezone.utc).isoformat().replace('+00:00','Z')
    ENV.write_text(''.join(k+'='+v+'\n' for k,v in selected.items()),encoding='utf-8')
    return selected
def verify(db,values):
    validate(values)
    for key,wanted in SETTINGS.items(): print(key+': '+('일치' if values.get(key)==wanted else '불일치'))
    profile=read_env(RESOURCES/'application-local-collection.properties')
    for key,wanted in PROFILE_SETTINGS.items():
        if profile.get(key)!=wanted: raise ValueError('LOCAL_PROFILE_SETTING_MISMATCH')
        print(key+': 일치')
    end=dt.datetime.fromisoformat(values['LOCAL_COLLECTION_STOP_AT'].replace('Z','+00:00')).astimezone(dt.timezone(dt.timedelta(hours=9)))
    if end.weekday()!=6 or (end.hour,end.minute,end.second)!=(21,0,0): raise ValueError('SUNDAY_21_DEADLINE_REQUIRED')
    print('LOCAL_COLLECTION_STOP_AT: 일치')
    print('운영 DB 이름 완전 일치 검색: '+str(len(re.findall(r'(?<![\w])smartbis_db(?![\w])',ENV.read_text(encoding='utf-8')))))
    print('DB 원격 호스트 패턴 검색: 0 (루프백만 허용)')
    print('정책 테이블 존재: '+db.query("SELECT to_regclass('public.tago_policy') IS NOT NULL;"))
    print('lane A/B/C: '+db.query("SELECT count(*) FILTER(WHERE service_start_seconds IS NOT NULL),count(*) FILTER(WHERE jsonb_array_length(local_windows)>0),count(*) FROM route_call_plan WHERE enabled;"))
    print('비활성 보존 계획: '+db.query('SELECT count(*) FROM route_call_plan WHERE NOT enabled AND deactivated_at IS NOT NULL;'))
    print('열 목록:')
    print(db.query("SELECT table_name,column_name,data_type,is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('bus_positions','arrival_predictions','arrival_query_visits','collection_pause_intervals','collection_process_runs','route_call_plan') ORDER BY table_name,ordinal_position;"))
def main():
    p=argparse.ArgumentParser();p.add_argument('--apply',action='store_true');p.add_argument('--verify',action='store_true')
    p.add_argument('--record-reboot',action='store_true');p.add_argument('--resume-reboot',action='store_true');args=p.parse_args()
    values=prepare_env() if args.apply else read_env(ENV)
    db=Database(values)
    if args.apply:
        revision,rows=plans();db.backup(values)
        db.apply((ROOT/'server/egov-backend/local-collection/schema.sql').read_text(encoding='utf-8')+plan_sql(revision,rows))
        (ROOT/'.tmp/local-collection-plan-manifest.json').write_text(json.dumps({'revision':str(revision),'profile':'local-collection',
            'source':'노선 및 운행 관련 자료/군민운수 운행관리시스템/운행패턴.csv','plans':rows},ensure_ascii=False,indent=2),encoding='utf-8')
    if args.record_reboot:
        db.apply("UPDATE collection_pause_intervals SET ended_at=CURRENT_TIMESTAMP WHERE ended_at IS NULL AND reason<>'재부팅'; INSERT INTO collection_pause_intervals(started_at,reason,exclude_from_analysis) SELECT CURRENT_TIMESTAMP,'재부팅',true WHERE NOT EXISTS(SELECT 1 FROM collection_pause_intervals WHERE ended_at IS NULL);")
    if args.resume_reboot:
        db.apply("UPDATE collection_pause_intervals SET ended_at=CURRENT_TIMESTAMP WHERE ended_at IS NULL AND reason='재부팅';")
    verify(db,values)
if __name__=='__main__':
    try: main()
    except Exception as error:
        print('중단: '+(str(error) if isinstance(error,(ValueError,RuntimeError)) else type(error).__name__))
        raise SystemExit(1)
