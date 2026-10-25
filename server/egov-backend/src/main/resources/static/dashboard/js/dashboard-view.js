export class DashboardView {
  constructor(documentRef = document) { this.document = documentRef; }

  byId(id) { return this.document.getElementById(id); }
  text(id, value) { this.byId(id).textContent = value ?? '—'; }
  message(id, value, tone = '') {
    const element = this.byId(id);
    element.textContent = value;
    element.className = tone;
  }
  cell(row, value) {
    const td = this.document.createElement('td');
    td.textContent = value == null ? '—' : String(value);
    row.append(td);
    return td;
  }
  clear(id) { this.byId(id).replaceChildren(); }

  showDetail(sectionId, textId, value) {
    this.text(textId, JSON.stringify(value, null, 2));
    const section = this.byId(sectionId);
    section.hidden = false;
    section.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  renderErrors(records, labels, onDetail) {
    const body = this.byId('rows');
    body.replaceChildren();
    records.forEach(item => {
      const row = this.document.createElement('tr');
      this.cell(row, new Date(item.created_at).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }));
      this.cell(row, `${item.batch_id} / ${item.content_id || '배치 전체'}`);
      this.cell(row, `${item.error_code}: ${item.error_message}`);
      this.cell(row, labels[item.status] || item.status);
      const button = this.document.createElement('button');
      button.type = 'button'; button.textContent = '상세 보기';
      button.addEventListener('click', () => onDetail(item));
      this.cell(row, '').append(button);
      body.append(row);
    });
  }

  renderContents(records, query, selectedStatus, statusLabels, callbacks) {
    const needle = query.trim().toLocaleLowerCase('ko-KR');
    const countFor = status => records.filter(item => item.publicationStatus === status).length;
    this.text('publication-active-count', countFor('ACTIVE'));
    this.text('publication-paused-count', countFor('PAUSED'));
    this.text('publication-removed-count', countFor('REMOVED'));
    this.text('publication-total-count', records.length);
    const matches = records.filter(item => (selectedStatus === 'ALL' || item.publicationStatus === selectedStatus)
      && [item.contentId, item.title, item.contentType, item.category, item.presentationType, item.template]
        .join(' ').toLocaleLowerCase('ko-KR').includes(needle));
    const body = this.byId('content-rows');
    body.replaceChildren();

    matches.forEach(item => {
      const row = this.document.createElement('tr');
      this.cell(row, `${item.contentId}\n${item.title}`);
      this.cell(row, `${item.category || '분류 미상'} / ${item.presentationType || item.contentType} / ${item.template}\n첨부 ${item.assets?.length || 0}개`);
      this.cell(row, `${item.displayStartDate || '시작 제한 없음'} ~ ${item.displayEndDate || '종료 제한 없음'}`);
      this.cell(row, item.targetRegions == null ? '전체 지역' : item.targetRegions.join(', '));
      const changed = item.updatedAt
        ? `${item.updatedBy || '담당자 미상'} · ${new Date(item.updatedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}`
        : '—';
      this.cell(row, `${statusLabels[item.publicationStatus] || item.publicationStatus}\n${changed}`);

      const actions = this.document.createElement('div'); actions.className = 'actions';
      const detail = this.document.createElement('button');
      detail.type = 'button'; detail.textContent = '상세';
      detail.addEventListener('click', () => callbacks.detail(item));
      actions.append(detail);
      const addAction = (label, next, className = '') => {
        const button = this.document.createElement('button');
        button.type = 'button'; button.textContent = label; button.className = className;
        button.addEventListener('click', () => callbacks.change(item, next, button));
        actions.append(button);
      };
      if (item.publicationStatus === 'ACTIVE') addAction('중지', 'PAUSED');
      else addAction('재개', 'ACTIVE');
      if (item.publicationStatus !== 'REMOVED') addAction('게시 제외', 'REMOVED', 'danger');
      this.cell(row, '').append(actions);
      body.append(row);
    });
    return matches;
  }

  renderPolicies(items, callbacks) {
    const body = this.byId('policy-rows');
    body.replaceChildren();
    items.forEach(item => {
      const row = this.document.createElement('tr');
      this.cell(row, item.policy_key);
      const numeric = (value, label, positive) => {
        const input = this.document.createElement('input');
        input.type = 'number'; input.step = '1'; input.className = 'policy-input';
        if (positive) input.min = '1';
        input.value = value == null ? '' : value;
        input.setAttribute('aria-label', label);
        return input;
      };
      const seconds = numeric(item.display_seconds, `${item.policy_key} 표출 시간`, true);
      const interval = numeric(item.repeat_interval_seconds, `${item.policy_key} 반복 간격`, true);
      const priority = numeric(item.priority, `${item.policy_key} 우선순위`, false);
      priority.required = true;
      this.cell(row, '').append(seconds);
      this.cell(row, '').append(interval);
      this.cell(row, '').append(priority);
      const enabled = this.document.createElement('input');
      enabled.type = 'checkbox'; enabled.checked = item.enabled;
      enabled.setAttribute('aria-label', `${item.policy_key} 사용`);
      this.cell(row, '').append(enabled);
      const save = this.document.createElement('button');
      save.type = 'button'; save.textContent = '저장';
      this.cell(row, '').append(save);
      save.addEventListener('click', () => callbacks.save(item, { seconds, interval, priority, enabled, button: save }));
      body.append(row);
    });
  }

  renderDeviceStatuses(items) {
    const body = this.byId('device-status-rows');
    body.replaceChildren();
    const now = Date.now();
    items.forEach(item => {
      const row = this.document.createElement('tr');
      const seen = item.last_seen_at ? new Date(item.last_seen_at) : null;
      const age = seen ? Math.max(0, now - seen.getTime()) : Number.POSITIVE_INFINITY;
      const online = age <= 90_000;
      this.cell(row, item.vehicle_number);
      this.cell(row, online ? (item.playback_status === 'ERROR' ? '오류' : '온라인') : '오프라인');
      this.cell(row, [item.display_type, item.content_title, item.content_id].filter(Boolean).join(' · ') || '표출 대기');
      this.cell(row, item.app_version || '—');
      this.cell(row, seen ? seen.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '보고 없음');
      this.cell(row, item.error_message || '—');
      body.append(row);
    });
    return items.length;
  }

  renderDeviceVehicleAssignments(items) {
    const body = this.byId('device-assignment-rows');
    body.replaceChildren();
    items.forEach(item => {
      const row = this.document.createElement('tr');
      this.cell(row, item.device_number);
      this.cell(row, item.vehicle_number || '차량번호 미배정');
      const changed = item.updated_at || item.assigned_at;
      this.cell(row, changed ? new Date(changed).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '—');
      body.append(row);
    });
    return items.length;
  }

  navigate(view, titles) {
    Object.entries(titles).forEach(([key]) => {
      const panel = this.document.querySelector(`[data-panel="${key}"]`);
      if (panel) panel.hidden = key !== view;
    });
    this.document.querySelectorAll('.nav [data-view]').forEach(button => {
      const active = button.dataset.view === view;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    this.text('page-title', titles[view]);
  }
}
