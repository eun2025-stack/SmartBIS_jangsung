'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createStatusServer } = require('../src/status-server');

async function withServer(options, run) {
  const server = createStatusServer(options);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();

  try {
    await run(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
}

test('health endpoint reports watcher and database status without sensitive details', async () => {
  await withServer({
    pool: { query: async sql => { assert.equal(sql, 'SELECT 1'); } },
    getState: () => ({ watcher: 'RUNNING', processing: false })
  }, async baseUrl => {
    const response = await fetch(`${baseUrl}/health`);
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(body.status, 'UP');
    assert.equal(body.database, 'UP');
    assert.equal(body.watcher, 'RUNNING');
    assert.equal(body.processing, false);
    assert.equal(response.headers.get('cache-control'), 'no-store');
  });
});

test('health endpoint reports database outage without exposing exception text', async () => {
  await withServer({
    pool: { query: async () => { throw new Error('secret connection details'); } }
  }, async baseUrl => {
    const response = await fetch(`${baseUrl}/health`);
    const body = await response.json();

    assert.equal(response.status, 503);
    assert.equal(body.status, 'DOWN');
    assert.equal(body.database, 'DOWN');
    assert.equal(JSON.stringify(body).includes('secret connection details'), false);
  });
});

test('health endpoint accepts only GET /health', async () => {
  await withServer({ pool: { query: async () => {} } }, async baseUrl => {
    const missing = await fetch(`${baseUrl}/not-health`);
    const wrongMethod = await fetch(`${baseUrl}/health`, { method: 'POST' });

    assert.equal(missing.status, 404);
    assert.equal(wrongMethod.status, 405);
    assert.equal(wrongMethod.headers.get('allow'), 'GET');
  });
});
