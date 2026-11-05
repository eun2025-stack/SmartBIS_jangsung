"""Build a disabled route plan from explicit input files; never connects to a DB.

DB metadata is supplied as a read-only snapshot. SQL output contains INSERT only;
backup and transactional replacement are separate, explicitly approved operations.
"""
import argparse
import csv
import io
import json
from collections import Counter
from pathlib import Path

LANE_A = {f'TSB342000{x:03d}' for x in (102,103,155)}
VARIANTS = {f'TSB342000{x:03d}' for x in (104,105,106,107,108,109,110,111,112,117,118,119,153,154)}
SWEEP_ONLY = {'TSB342000006','TSB342000074'}
DAY_TYPES = {'ud':'all','ld':'all','js':'wkd','gj':'wkd','hjs':'hol','hgj':'hol'}


class PlanError(ValueError): pass


def normalize_route(value):
    value=value.strip()
    return 'TSB'+value[3:] if value.startswith('TBS') else value


def read_csv(path):
    data=Path(path).read_bytes()
    for encoding in ('utf-8-sig','cp949'):
        try: return list(csv.reader(io.StringIO(data.decode(encoding))))
        except UnicodeError: continue
    raise PlanError('CSV_ENCODING')


def read_routes(rows):
    try: col=rows[0].index('ROUTE_ID')
    except (ValueError,IndexError): raise PlanError('ROUTE_ID_COLUMN_REQUIRED') from None
    routes=[normalize_route(r[col]) for r in rows[1:] if any(x.strip() for x in r)]
    if len(routes)!=105 or len(set(routes))!=105: raise PlanError('ROUTE_COUNT_REQUIRED_105')
    return sorted(routes)


def seconds(value):
    try:
        parts=[int(v) for v in value.strip().split(':')]
        if len(parts)==2: parts.append(0)
        h,m,s=parts
        if not (0<=h<24 and 0<=m<60 and 0<=s<60): raise ValueError()
        return h*3600+m*60+s
    except (ValueError,TypeError): raise PlanError('INVALID_DEPARTURE_TIME') from None


def parse_patterns(rows):
    if not rows or rows[0][:2]!=['div','patt']: raise PlanError('PATTERN_HEADER')
    for i in range(2,len(rows[0]),3):
        if rows[0][i:i+3]!=['seq','time','route']: raise PlanError('PATTERN_TRIPLE_HEADER')
    patterns=[]; trips=[]
    for row in rows[1:]:
        if not any(x.strip() for x in row): continue
        div,patt=(x.strip() for x in row[:2])
        if div not in DAY_TYPES or not patt: raise PlanError('PATTERN_DIV_OR_LABEL')
        patterns.append((div,patt))
        for i in range(2,len(row),3):
            cells=row[i:i+3]+['']*3
            if not cells[2].strip(): continue
            route=normalize_route(cells[2]); departure=seconds(cells[1])
            lead=10 if route in VARIANTS else 15
            start=departure-lead*60
            if start<0: raise PlanError('NEGATIVE_WINDOW_START')
            trips.append(dict(route_id=route,day_type=DAY_TYPES[div],start_seconds=start,
                              base_departure_seconds=departure,lead_min=lead,pattern=patt))
    return patterns,trips


def clock(value): return f'{value//3600:02}:{value//60%60:02}:{value%60:02}'


def build_plan(pattern_rows,routes,db_routes,groups,window_keys=None):
    if len(routes)!=105 or len(set(routes))!=105: raise PlanError('ROUTE_COUNT_REQUIRED_105')
    if set(routes)!=set(db_routes): raise PlanError('ROUTE_SET_MISMATCH')
    if not LANE_A|VARIANTS|SWEEP_ONLY <= set(routes): raise PlanError('REQUIRED_ROUTE_MISSING')
    patterns,trips=parse_patterns(pattern_rows)
    if len(patterns)!=39 or len(set(patterns))!=39: raise PlanError('PATTERN_COUNT_REQUIRED_39')
    if len(trips)!=426: raise PlanError('TRIP_COUNT_REQUIRED_426')
    if any(t['route_id'] not in set(routes) for t in trips): raise PlanError('PATTERN_ROUTE_NOT_IN_CATALOG')
    if any(t['route_id'] in SWEEP_ONLY for t in trips): raise PlanError('SWEEP_ONLY_HAS_PATTERN')
    unique={}
    for trip in trips:
        unique.setdefault((trip['route_id'],trip['day_type'],trip['start_seconds']),trip)
    if len(unique)!=425: raise PlanError('UNIQUE_WINDOWS_REQUIRED_425')
    b_routes=set(routes)-LANE_A-SWEEP_ONLY
    if {t['route_id'] for t in unique.values()}-LANE_A != b_routes: raise PlanError('LANE_B_COVERAGE_REQUIRED_100')
    expected_keys={'start','end','interval_seconds','day_type'}
    if window_keys and set(window_keys)!=expected_keys: raise PlanError('UNSUPPORTED_EXISTING_WINDOW_KEYS')
    vocabulary=set(groups.values())
    needed={'관내','100번'}
    if not needed<=vocabulary: raise PlanError('ROUTE_GROUP_VOCABULARY_MISSING')
    plans=[]
    for route in sorted(routes):
        windows=[]
        if route in b_routes:
            for t in sorted(unique.values(),key=lambda x:(x['route_id'],x['start_seconds'],x['day_type'])):
                if t['route_id']!=route: continue
                if window_keys:
                    # Existing reader treats absent day_type as all days and accepts
                    # WEEKDAY/HOLIDAY. Preserve its start/end/interval contract.
                    w=dict(start=clock(t['start_seconds']),end=clock(min(86400,t['start_seconds']+3600)),interval_seconds=600)
                    if t['day_type']!='all': w['day_type']='WEEKDAY' if t['day_type']=='wkd' else 'HOLIDAY'
                else:
                    w={k:t[k] for k in ('day_type','start_seconds','base_departure_seconds','lead_min','pattern')}
                windows.append(w)
        group='100번' if route in LANE_A|VARIANTS else '관내'
        if group not in vocabulary: raise PlanError('NEW_ROUTE_GROUP_FORBIDDEN')
        plans.append(dict(route_id=route,route_group=group,interval_seconds=600,enabled=False,local_windows=windows,
                          service_start_seconds=18900 if route in LANE_A else None,
                          service_end_seconds=76500 if route in LANE_A else None,
                          backup_interval_seconds=1800,backup_start_seconds=21600,backup_end_seconds=75600,
                          plan_date=None,last_attempt_at=None,last_failure_at=None,failure_reason=''))
    by_day=Counter(t['day_type'] for t in unique.values())
    b_by_day=Counter(t['day_type'] for t in unique.values() if t['route_id'] in b_routes)
    return plans,dict(pattern_count=len(patterns),trip_count=len(trips),route_count=len(routes),
                      windows_before_dedup=len(trips),deduplicated_windows_including_a=len(unique),
                      duplicate_windows_removed=len(trips)-len(unique),lane_b_windows=sum(b_by_day.values()),
                      all_windows_by_day_type={k:by_day[k] for k in ('all','wkd','hol')},
                      lane_b_windows_by_day_type={k:b_by_day[k] for k in ('all','wkd','hol')},
                      lanes=dict(A=3,B=len(b_routes),C=len(routes),sweep_only=2),
                      json_day_type_mapping={'all':'day_type omitted','wkd':'WEEKDAY','hol':'HOLIDAY'} if window_keys else None)


def literal(value):
    if value is None:return 'NULL'
    if isinstance(value,bool):return 'TRUE' if value else 'FALSE'
    if isinstance(value,int):return str(value)
    if isinstance(value,list):return "'"+json.dumps(value,ensure_ascii=False,separators=(',',':')).replace("'","''")+"'::jsonb"
    return "'"+str(value).replace("'","''")+"'"


def insert_sql(plans):
    columns=list(plans[0])
    return 'INSERT INTO public.route_call_plan ('+','.join(columns)+') VALUES\n'+',\n'.join('('+','.join(literal(row[k]) for k in columns)+')' for row in plans)+';\n'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--patterns',required=True);parser.add_argument('--routes',required=True)
    parser.add_argument('--metadata-json',required=True);parser.add_argument('--output-dir',required=True)
    args=parser.parse_args()
    db=json.loads(Path(args.metadata_json).read_text(encoding='utf-8'))['db']
    keys={k for p in db['plans'] for w in p['local_windows'] for k in w}
    plans,summary=build_plan(read_csv(args.patterns),read_routes(read_csv(args.routes)),db['routes'],
                             {p['route_id']:p['route_group'] for p in db['plans']},keys)
    out=Path(args.output_dir);out.mkdir(parents=True,exist_ok=True)
    with (out/'route_call_plan_generated.csv').open('w',encoding='utf-8-sig',newline='') as file:
        writer=csv.DictWriter(file,fieldnames=list(plans[0]));writer.writeheader()
        writer.writerows(dict(p,local_windows=json.dumps(p['local_windows'],ensure_ascii=False,separators=(',',':'))) for p in plans)
    (out/'route_call_plan_insert.sql').write_text(insert_sql(plans),encoding='utf-8')
    (out/'route_call_plan_generated.json').write_text(json.dumps(dict(plans=plans,summary=summary),ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))


if __name__=='__main__': main()
