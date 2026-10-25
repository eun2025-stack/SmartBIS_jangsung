import { DashboardModel } from './dashboard-model.js';
import { DashboardView } from './dashboard-view.js';

const model = new DashboardModel();
const view = new DashboardView();
const $ = id => view.byId(id);
const titles = { home: '운영 현황', approval: '데이터 관리', policy: '표출 정책', collect: '수집 상태', monitor: '표출 모니터', errors: '오류 관리' };
const errorLabels = { OPEN: '미해결', WAITING_PROVIDER: '제공측 확인 대기', RETRY_REQUESTED: '재처리 요청', RESOLVED: '해결' };
const publicationLabels = { ACTIVE: '게시 중', PAUSED: '중지', REMOVED: '게시 제외', ALL: '전체' };
const limit = 50;
let offset = 0;
let errorBusy = false;
let moreErrors = false;
let errorQuery = { all: false, q: '', status: '' };
let contentRecords = [];

function normalizeDashboardUrl() {
  if (location.protocol === 'file:') {
    const served = new URL('https://localhost:8443/api/dashboard/index.html');
    served.hash = location.hash;
    location.replace(served.href);
    return false;
  }
  if (!['localhost', '127.0.0.1', '[::1]', '::1'].includes(location.hostname.toLowerCase())) return true;
  const canonical = new URL(location.href);
  if (canonical.protocol === 'https:' && canonical.port === '8443') return true;
  canonical.protocol = 'https:';
  canonical.port = '8443';
  location.replace(canonical.href);
  return false;
}

function updatePaging() {
  $('prev').disabled = errorBusy || offset === 0;
  $('next').disabled = errorBusy || !moreErrors;
  view.text('page', `${offset / limit + 1} 페이지 · 최대 ${limit}건`);
}

async function loadErrors() {
  if (errorBusy) return;
  errorBusy = true;
  updatePaging();
  view.message('message', '오류 목록을 불러오는 중…');
  view.clear('rows');
  $('detail').hidden = true;
  ['recent', 'open', 'resolved'].forEach(id => view.text(id, '—'));
  try {
    const { summary, records } = await model.errorPage({ ...errorQuery, limit, offset });
    view.text('recent', summary.recent_3day_count);
    view.text('open', summary.open_count);
    view.text('resolved', summary.resolved_count);
    moreErrors = records.length > limit;
    view.renderErrors(records.slice(0, limit), errorLabels, item => view.showDetail('detail', 'detailText', item));
    view.message('message', records.length
      ? `${errorQuery.all ? '전체 기간' : '최근 3일'} · 이 페이지 ${Math.min(limit, records.length)}건 (한국 시간)`
      : '조회 조건에 해당하는 오류가 없습니다.', 'success');
  } catch (error) {
    moreErrors = false;
    view.message('message', `조회 실패: ${error.message}. HTTPS 인증서 신뢰 여부와 서버 상태를 확인한 뒤 다시 조회하세요.`, 'failure');
  } finally {
    errorBusy = false;
    updatePaging();
  }
}

async function loadContents() {
  view.message('content-message', '게시 목록을 불러오는 중…');
  view.clear('content-rows');
  $('content-detail').hidden = true;
  try {
    contentRecords = await model.contents('ALL');
    renderContents();
  } catch (error) {
    contentRecords = [];
    view.message('content-message', `조회 실패: ${error.message}`, 'failure');
  }
}

function renderContents() {
  const matches = view.renderContents(contentRecords, $('content-search').value, $('content-status').value, publicationLabels, {
    detail: item => view.showDetail('content-detail', 'content-detail-text', item),
    change: changePublicationState
  });
  view.message('content-message', matches.length
    ? `콘텐츠 ${matches.length}건 · 상태 ${publicationLabels[$('content-status').value] || '전체'}`
    : '조회 조건에 해당하는 홍보 콘텐츠가 없습니다.', matches.length ? 'success' : '');
}

async function changePublicationState(item, status, button) {
  const explanation = {
    PAUSED: '중지하면 이미 재생 중인 항목은 재생을 마친 뒤 다음 표출부터 제외됩니다. 파일과 DB 기록은 유지됩니다.',
    REMOVED: '게시 목록에서 제외합니다. 파일·DB 콘텐츠·수신 이력은 삭제되지 않으며 전체 상태에서 복구할 수 있습니다.',
    ACTIVE: '게시 대상으로 다시 활성화합니다.'
  };
  if (!confirm(`${item.title}\n\n${explanation[status]}`)) return;
  button.disabled = true;
  try {
    await model.updatePublication(item.contentId, status);
    view.message('content-message', `${item.contentId} 상태 변경 완료`, 'success');
    await loadContents();
  } catch (error) {
    view.message('content-message', `상태 변경 실패: ${error.message}. 목록을 새로고침해 현재 DB 상태를 확인하세요.`, 'failure');
    button.disabled = false;
  }
}

async function loadAdminIdentity() {
  try {
    const session = await model.session();
    view.text('admin-identity', `${session.username} · ${(session.roles || []).join(', ')}`);
  } catch (error) { view.text('admin-identity', `세션 확인 실패: ${error.message}`); }
}

async function loadOverview() {
  try {
    const summary = await model.requestJson('../v1/admin/ingest-errors/summary');
    view.text('home-recent', summary.recent_3day_count);
    view.text('home-open', summary.open_count);
    view.text('home-resolved', summary.resolved_count);
    view.message('home-message', 'DB 운영 요약을 확인했습니다.', 'success');
  } catch (error) {
    ['home-recent', 'home-open', 'home-resolved'].forEach(id => view.text(id, '—'));
    view.message('home-message', `요약 조회 실패: ${error.message}`, 'failure');
  }
}

async function loadWatcherStatus() {
  view.message('watcher-status', '상태 확인 중…');
  try {
    const result = await model.systemStatus();
    const watcher = result.nodeWatcher || {};
    const state = watcher.watcher === 'RUNNING' ? '감시 중' : '감시 초기화 중';
    const work = watcher.processing ? '배치 처리 중' : '대기 중';
    view.message('watcher-status', result.status === 'UP'
      ? `정상 · DB ${watcher.database} · ${state} · ${work} · 가동 ${watcher.uptimeSeconds}초`
      : '연결 실패 · watcher 컨테이너와 내부 상태 API를 확인하세요', result.status === 'UP' ? 'success' : 'failure');
  } catch (error) {
    const message = error instanceof TypeError && error.message === 'Failed to fetch'
      ? `서버 연결 실패 · HTTPS 주소, 사설 CA 신뢰, 관리자 로그인 상태 확인 · ${location.href}`
      : `조회 실패: ${error.message} · 관리자 로그인 상태를 확인하세요.`;
    view.message('watcher-status', message, 'failure');
  }
}

async function loadTagoCatalog() {
  view.message('tago-status', '카탈로그 상태 확인 중…');
  try {
    const result = await model.tagoCatalog();
    const synced = result.status === 'SYNCED';
    const last = result.lastSyncedAt
      ? new Date(result.lastSyncedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '아직 없음';
    const unknown = Number(result.unknownDirectionCount || 0);
    view.message('tago-status', `${synced ? '카탈로그 저장됨' : '아직 동기화 전'} · 활성 노선 ${result.routeCount}개 · 활성 정류소 ${result.stopCount}개 · 경유 ${result.routeStopCount ?? '—'}건 · 방향 미분류 ${unknown}건 · 지역 분류 ${result.regionAssignedStopCount ?? '—'}개 · 지역 미분류 ${result.regionUnassignedStopCount ?? '—'}개 · 마지막 성공 ${last}`,
      synced && unknown === 0 ? 'success' : unknown ? 'notice' : '');

    const runs = result.recentSyncRuns || [];
    const status = { RUNNING: '진행 중', SUCCESS: '성공', SUCCESS_WITH_WARNINGS: '경고와 함께 저장', FAILED: '실패' };
    const history = runs.length ? '최근 TAGO 동기화 이력 (최대 10건)\n' + runs.map(run => {
      const date = run.completedAt || run.startedAt;
      const when = date ? new Date(date).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '시각 미상';
      const counts = run.routeCount == null ? '' : ` · 노선 ${run.routeCount} · 경유 ${run.routeStopCount} · 미분류 방향 ${run.unresolvedDirectionCount ?? 0} · 순번 보완 ${run.inferredOrderCount ?? 0}`;
      const change = run.status === 'FAILED' ? ` · 오류 ${run.failureCode || '상세 로그 확인'}` : ` · ${run.catalogChanged ? '자료 변경 감지' : '자료 변경 없음'}`;
      return `${when} · ${run.triggerSource === 'AUTOMATIC' ? '자동' : '수동'} · ${status[run.status] || run.status}${counts}${change}`;
    }).join('\n') : '동기화 실행 이력 없음 · 자동 확인 매일 03:00(Asia/Seoul)';
    view.text('tago-run-history', history);

    const system = await model.systemStatus();
    const live = system.tagoVehicleLocation || {};
    const formatTime = value => {
      if (!value) return '기록 없음';
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
    };
    const routeSummary = live.status === 'NO_ASSIGNED_ROUTES'
      ? '차량 배정 노선 없음 · TAGO 호출 안 함'
      : live.routeCount
        ? `차량 배정 노선 ${live.routeCount}개 · 최근 ${live.lastPolledRouteId || '노선 미상'} 위치 요청 ${live.failedRouteCount ? '실패' : '성공'} · 노선 순환 약 ${live.estimatedRouteRefreshSeconds ?? '—'}초`
        : '아직 수집 실행 기록 없음';
    const requestBudget = live.minRequestGapMs
      ? ` · 차량 위치 요청 최소 ${Math.ceil(live.minRequestGapMs / 1000)}초 간격(최대 ${live.maxLocationRequestsPerDayAtConfiguredGap ?? '—'}회/일, 도착정보 요청 별도)` : '';
    const retryAt = live.nextAllowedPollAt ? ` · 재개 예정 ${formatTime(live.nextAllowedPollAt)}` : '';
    const sweepTime = live.estimatedRouteRefreshSeconds
      ? ` · 배정 노선 전체 순환 예상 ${Math.ceil(live.estimatedRouteRefreshSeconds / 60)}분` : '';
    const failureReason = live.lastFailureReason === 'TAGO_HTTP_429'
      ? 'TAGO 요청 제한(HTTP 429)'
      : live.lastFailureReason?.startsWith('TAGO_HTTP_')
        ? `TAGO HTTP 오류(${live.lastFailureReason.slice('TAGO_HTTP_'.length)})`
        : live.lastFailureReason?.startsWith('TAGO_RESULT_')
          ? `TAGO 응답 오류(${live.lastFailureReason.slice('TAGO_RESULT_'.length)})`
          : live.lastFailureReason || '';
    const liveText = ['DISABLED', 'MOCK_MODE'].includes(live.status)
      ? `실시간 위치수집 비활성 · TAGO_ENABLED=${live.enabled ? 'true' : 'false'} · mock mode=${live.mockMode}`
      : `${live.status} · ${routeSummary} · 최근 위치 저장 ${live.lastSavedCount ?? 0}건 · 마지막 수집 ${formatTime(live.lastCompletedAt)} · DB 최신 위치 ${formatTime(live.latestPositionAt)}${failureReason ? ` · 최근 실패 ${live.lastFailedRouteId || '노선 미상'}: ${failureReason}` : ''}${retryAt}${requestBudget}${sweepTime}`;
    view.message('tago-live-status', liveText, ['DISABLED', 'MOCK_MODE'].includes(live.status) ? 'notice' : live.status === 'UP' ? 'success' : 'failure');
  } catch (error) {
    view.message('tago-status', `카탈로그 상태 조회 실패: ${error.message}`, 'failure');
    view.text('tago-run-history', '동기화 이력 조회 실패 · 로그인 및 DB 마이그레이션 여부를 확인하세요.');
  }
}

async function synchronizeTago() {
  const button = $('sync-tago-catalog');
  const refresh = $('refresh-tago-catalog');
  if (!confirm('TAGO 전체 노선과 경유 정류소를 조회해 카탈로그를 갱신할까요? 전체 조회와 검증 성공 후에만 DB에 반영됩니다.')) return;
  button.disabled = true; refresh.disabled = true;
  view.message('tago-sync-message', 'TAGO 자료를 순차 확인하고 있습니다. 완료까지 잠시 기다려 주세요…');
  try {
    const result = await model.syncTagoCatalog();
    const grouping = result.regionClassification || {};
    const groupText = grouping.status === 'SUCCESS'
      ? ` · 장성 지역분류 ${grouping.stopsAssigned} · 광주 그룹 ${grouping.gwangjuFallbackAssigned} · 최종 미분류 ${grouping.unmatchedCount}`
      : ' · 지역분류 실패(다음 회차 재시도)';
    const text = `동기화 ${result.status === 'SUCCESS' ? '성공' : '경고'} · 장성군 ${result.cityCode} · 노선 ${result.routeCount}개 · 경유 ${result.routeStopCount}건 · 고유 정류소 ${result.uniqueStopCount}개 · 방향 미분류 ${result.unresolvedDirectionCount ?? 0} · 순번 보완 ${result.inferredOrderCount ?? 0}${groupText}`;
    view.message('tago-sync-message', text, result.status === 'SUCCESS' ? 'success' : 'notice');
    await loadTagoCatalog();
  } catch (error) {
    view.message('tago-sync-message', `동기화 실패: ${error.message}. 세션·CSRF와 백엔드 로그를 확인하세요.`, 'failure');
  } finally { button.disabled = false; refresh.disabled = false; }
}

async function loadPolicies() {
  view.message('policy-message', '표출 정책 조회 중…');
  $('reload-policies').disabled = true;
  try {
    const items = await model.policies();
    view.renderPolicies(items, { save: savePolicy });
    view.message('policy-message', `${items.length}개 정책 조회 완료`, 'success');
  } catch (error) { view.message('policy-message', `조회 실패: ${error.message}`, 'failure'); }
  finally { $('reload-policies').disabled = false; }
}

async function loadDeviceStatuses() {
  view.message('device-status-message', '단말 상태 조회 중…');
  try {
    const items = await model.deviceStatuses();
    const count = view.renderDeviceStatuses(items);
    view.message('device-status-message', count ? `${count}대 상태 보고 · 온라인 판정은 최근 90초 이내 보고 기준` : '아직 상태를 보고한 Android 단말이 없습니다.', count ? 'success' : '');
  } catch (error) {
    view.message('device-status-message', `단말 상태 조회 실패: ${error.message}`, 'failure');
  }
}

async function loadDeviceVehicleAssignments() {
  view.message('device-assignment-message', 'ID·차량 매핑 조회 중…');
  try {
    const assignments = await model.deviceVehicleAssignments();
    const count = view.renderDeviceVehicleAssignments(assignments);
    const unassigned = assignments.filter(item => !item.vehicle_number).length;
    view.message('device-assignment-message', `등록 단말 ${count}대 · 차량 미배정 ${unassigned}대`, 'success');
  } catch (error) {
    view.message('device-assignment-message', `매핑 조회 실패: ${error.message}`, 'failure');
  }
}

async function savePolicy(item, fields) {
  const { seconds, interval, priority, enabled, button } = fields;
  if (![seconds, interval, priority].every(input => input.reportValidity())) return;
  if (!confirm(`${item.policy_key} 정책을 저장하시겠습니까? 실제 표출 설정이 변경됩니다.`)) return;
  button.disabled = true;
  try {
    await model.savePolicy(item.policy_key, {
      displaySeconds: seconds.value === '' ? null : Number(seconds.value),
      repeatIntervalSeconds: interval.value === '' ? null : Number(interval.value),
      priority: Number(priority.value), enabled: enabled.checked
    });
    view.message('policy-message', `${item.policy_key} 정책 저장 완료`, 'success');
  } catch (error) { view.message('policy-message', `저장 실패: ${error.message}. 입력 내용을 유지했습니다.`, 'failure'); }
  finally { button.disabled = false; }
}

function navigate(key) {
  const target = titles[key] ? key : 'home';
  view.navigate(target, titles);
  if (target === 'home') loadOverview();
  if (target === 'approval') loadContents();
  if (target === 'errors') loadErrors();
  if (target === 'policy') loadPolicies();
  if (target === 'collect') { loadWatcherStatus(); loadTagoCatalog(); }
  if (target === 'monitor') loadDeviceStatuses();
  if (target === 'monitor') loadDeviceVehicleAssignments();
}

function bindEvents() {
  $('filters').addEventListener('submit', event => {
    event.preventDefault();
    if (errorBusy) return;
    errorQuery = { all: $('period').value === 'all', q: $('q').value.trim(), status: $('status').value };
    offset = 0; loadErrors();
  });
  $('prev').addEventListener('click', () => { if (!errorBusy) { offset = Math.max(0, offset - limit); loadErrors(); } });
  $('next').addEventListener('click', () => { if (!errorBusy && moreErrors) { offset += limit; loadErrors(); } });
  $('close').addEventListener('click', () => { $('detail').hidden = true; });
  $('content-filters').addEventListener('submit', event => { event.preventDefault(); loadContents(); });
  $('content-search').addEventListener('input', renderContents);
  $('reload-contents').addEventListener('click', loadContents);
  $('close-content-detail').addEventListener('click', () => { $('content-detail').hidden = true; });
  $('reload-policies').addEventListener('click', loadPolicies);
  $('refresh-watcher-status').addEventListener('click', loadWatcherStatus);
  $('refresh-device-status').addEventListener('click', loadDeviceStatuses);
  $('refresh-device-assignments').addEventListener('click', loadDeviceVehicleAssignments);
  $('sync-tago-catalog').addEventListener('click', synchronizeTago);
  $('refresh-tago-catalog').addEventListener('click', loadTagoCatalog);
  document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => { location.hash = button.dataset.view; }));
  window.addEventListener('hashchange', () => navigate(location.hash.slice(1)));
  $('logout').addEventListener('click', async () => {
    $('logout').disabled = true;
    try { await model.logout(); location.assign('../dashboard/login.html?logout'); }
    catch (error) { view.text('admin-identity', `로그아웃 실패: ${error.message}`); $('logout').disabled = false; }
  });
}

if (normalizeDashboardUrl()) {
  bindEvents();
  loadAdminIdentity();
  navigate(location.hash.slice(1));
  window.setInterval(() => { if (location.hash.slice(1) === 'collect') loadWatcherStatus(); }, 30000);
  window.setInterval(() => { if (location.hash.slice(1) === 'monitor') loadDeviceStatuses(); }, 30000);
}
