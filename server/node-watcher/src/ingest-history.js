function classifyBatchError(error) {
  if (error instanceof SyntaxError) return 'INVALID_JSON';
  if (error?.code === 'ENOENT') return 'INPUT_FILE_MISSING';
  if (
    ['ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT', 'EHOSTUNREACH'].includes(
      error?.code
    ) ||
    (typeof error?.code === 'string' && error.code.startsWith('08'))
  ) {
    return 'DATABASE_UNAVAILABLE';
  }
  if (typeof error?.code === 'string' && /^(22|23)/.test(error.code)) {
    return 'DATABASE_CONSTRAINT_ERROR';
  }
  return 'BATCH_PROCESSING_ERROR';
}

async function recordBatchFailure(pool, batchId, originalPath, failedPath, error) {
  const client = await pool.connect();
  const safeBatchId = String(batchId).slice(0, 100);
  const errorCode = classifyBatchError(error);
  const errorMessage = String(error?.message || '알 수 없는 배치 오류').slice(0, 4000);
  const errorDetail = JSON.stringify({
    originalPath,
    failedPath,
    errorName: error?.name || 'Error',
    databaseCode: error?.code || null
  });

  try {
    await client.query('BEGIN');
    await client.query(
      `
      INSERT INTO content_ingest_batches (
        batch_id, source_path, status, retry_count, last_error, updated_at
      )
      VALUES ($1, $2, 'FAILED', 1, $3, CURRENT_TIMESTAMP)
      ON CONFLICT (batch_id) DO UPDATE SET
        source_path = EXCLUDED.source_path,
        status = 'FAILED',
        retry_count = content_ingest_batches.retry_count + 1,
        last_error = EXCLUDED.last_error,
        updated_at = CURRENT_TIMESTAMP
      `,
      [safeBatchId, failedPath || originalPath, errorMessage]
    );
    await client.query(
      `
      INSERT INTO content_validation_errors (
        batch_id, content_id, error_code, error_message,
        error_detail, status, created_at
      )
      VALUES ($1, NULL, $2, $3, $4::jsonb, 'OPEN', CURRENT_TIMESTAMP)
      `,
      [safeBatchId, errorCode, errorMessage, errorDetail]
    );
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally {
    client.release();
  }

  return { batchId: safeBatchId, errorCode };
}

async function recordBatchSuccess(pool, batchId, archivedPath) {
  const client = await pool.connect();

  try {
    await client.query('BEGIN');
    await client.query(
      `
      INSERT INTO content_ingest_batches (
        batch_id, source_path, status, validated_at, last_error, updated_at
      )
      VALUES ($1, $2, 'PROCESSED', CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)
      ON CONFLICT (batch_id) DO UPDATE SET
        source_path = EXCLUDED.source_path,
        status = 'PROCESSED',
        validated_at = CURRENT_TIMESTAMP,
        last_error = NULL,
        updated_at = CURRENT_TIMESTAMP
      `,
      [batchId, archivedPath]
    );
    await client.query(
      `
      UPDATE content_validation_errors
         SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP
       WHERE batch_id = $1
         AND status <> 'RESOLVED'
      `,
      [batchId]
    );
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally {
    client.release();
  }
}

module.exports = {
  classifyBatchError,
  recordBatchFailure,
  recordBatchSuccess
};
