const test = require('node:test');
const assert = require('node:assert/strict');

const {
  classifyBatchError,
  recordBatchFailure,
  recordBatchSuccess
} = require('../src/ingest-history');

function createPoolStub({ failOnQuery } = {}) {
  const calls = [];
  let released = false;
  const client = {
    async query(sql, params = []) {
      const normalizedSql = sql.trim().replace(/\s+/g, ' ');
      calls.push({ sql: normalizedSql, params });
      if (failOnQuery && failOnQuery(normalizedSql, calls.length)) {
        throw new Error('simulated database error');
      }
      return { rows: [] };
    },
    release() {
      released = true;
    }
  };

  return {
    calls,
    client,
    get released() {
      return released;
    },
    async connect() {
      return client;
    }
  };
}

test('classifies malformed input, missing files and database errors', () => {
  assert.equal(classifyBatchError(new SyntaxError('bad JSON')), 'INVALID_JSON');
  assert.equal(classifyBatchError(Object.assign(new Error(), { code: 'ENOENT' })), 'INPUT_FILE_MISSING');
  assert.equal(classifyBatchError(Object.assign(new Error(), { code: 'ECONNREFUSED' })), 'DATABASE_UNAVAILABLE');
  assert.equal(classifyBatchError(Object.assign(new Error(), { code: '23514' })), 'DATABASE_CONSTRAINT_ERROR');
  assert.equal(classifyBatchError(new Error('unexpected')), 'BATCH_PROCESSING_ERROR');
});

test('records the failed batch and dashboard error row in one transaction', async () => {
  const pool = createPoolStub();
  const error = new SyntaxError('invalid contents.json');

  const result = await recordBatchFailure(
    pool,
    'BATCH_20261002_0001',
    '/app/upload-store/2026-10-02/BATCH_20261002_0001',
    '/app/upload-store/error/123_BATCH',
    error
  );

  assert.deepEqual(result, {
    batchId: 'BATCH_20261002_0001',
    errorCode: 'INVALID_JSON'
  });
  assert.equal(pool.calls[0].sql, 'BEGIN');
  assert.match(pool.calls[1].sql, /INSERT INTO content_ingest_batches/);
  assert.deepEqual(pool.calls[1].params.slice(0, 2), [
    'BATCH_20261002_0001',
    '/app/upload-store/error/123_BATCH'
  ]);
  assert.match(pool.calls[2].sql, /INSERT INTO content_validation_errors/);
  assert.deepEqual(pool.calls[2].params.slice(0, 3), [
    'BATCH_20261002_0001',
    'INVALID_JSON',
    'invalid contents.json'
  ]);
  assert.equal(pool.calls[3].sql, 'COMMIT');
  assert.equal(pool.released, true);
});

test('rolls back and releases the connection when failure logging fails', async () => {
  const pool = createPoolStub({
    failOnQuery: sql => sql.startsWith('INSERT INTO content_validation_errors')
  });

  await assert.rejects(
    recordBatchFailure(pool, 'BATCH_1', '/incoming/BATCH_1', null, new Error('bad')),
    /simulated database error/
  );
  assert.equal(pool.calls.at(-1).sql, 'ROLLBACK');
  assert.equal(pool.released, true);
});

test('marks a successfully reprocessed batch and resolves its open errors atomically', async () => {
  const pool = createPoolStub();

  await recordBatchSuccess(
    pool,
    'BATCH_20261002_0001',
    '/app/upload-store/archive/123_BATCH_20261002_0001'
  );

  assert.equal(pool.calls[0].sql, 'BEGIN');
  assert.match(pool.calls[1].sql, /status = 'PROCESSED'/);
  assert.match(pool.calls[2].sql, /UPDATE content_validation_errors/);
  assert.deepEqual(pool.calls[2].params, ['BATCH_20261002_0001']);
  assert.equal(pool.calls[3].sql, 'COMMIT');
  assert.equal(pool.released, true);
});
