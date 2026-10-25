export class DashboardModel {
  constructor() {
    this.errorsUrl = '../v1/admin/ingest-errors';
  }

  async requestJson(path, options = {}) {
    const headers = { Accept: 'application/json', ...(options.headers || {}) };
    const response = await fetch(path, { ...options, headers, cache: 'no-store' });
    if (response.status === 401) {
      location.assign('../dashboard/login.html?expired');
      throw new Error('관리자 로그인 세션이 만료되어 로그인 화면으로 이동합니다.');
    }
    if (!response.ok) throw new Error(`서버 응답 HTTP ${response.status}`);
    return response.json();
  }

  async csrfHeaders(extra = {}) {
    const token = await this.requestJson('../csrf');
    return { ...extra, [token.headerName]: token.token };
  }

  async errorPage({ all, q, status, limit, offset }) {
    const url = new URL(this.errorsUrl, location.href);
    url.search = new URLSearchParams({ all: String(all), q, status, limit: String(limit + 1), offset: String(offset) });
    const [summary, records] = await Promise.all([
      this.requestJson(`${this.errorsUrl}/summary`),
      this.requestJson(url)
    ]);
    return { summary, records };
  }

  contents(status) {
    const url = new URL('../v1/admin/contents', location.href);
    url.searchParams.set('status', status);
    return this.requestJson(url);
  }

  session() { return this.requestJson('../v1/admin/session'); }
  policies() { return this.requestJson('../v1/admin/display-policies'); }
  systemStatus() { return this.requestJson('../v1/admin/system-status'); }
  deviceStatuses() { return this.requestJson('../v1/admin/device-status'); }
  deviceVehicleAssignments() { return this.requestJson('../v1/admin/device-vehicle-assignments'); }
  tagoCatalog() { return this.requestJson('../v1/admin/tago/catalog'); }

  async updatePublication(contentId, status) {
    return this.requestJson(`../v1/admin/contents/${encodeURIComponent(contentId)}/publication-state`, {
      method: 'PUT',
      headers: await this.csrfHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ status })
    });
  }

  async savePolicy(policyKey, policy) {
    return this.requestJson(`../v1/admin/display-policies/${encodeURIComponent(policyKey)}`, {
      method: 'PUT',
      headers: await this.csrfHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(policy)
    });
  }

  async syncTagoCatalog() {
    return this.requestJson('../v1/admin/tago/catalog/sync', {
      method: 'POST', headers: await this.csrfHeaders()
    });
  }

  async logout() {
    const headers = await this.csrfHeaders({ Accept: 'text/html' });
    const response = await fetch('../dashboard/logout', { method: 'POST', headers });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
  }
}
