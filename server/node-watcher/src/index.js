const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { createReadStream } = require('node:fs');
const chokidar = require('chokidar');
const cron = require('node-cron');
const { Pool } = require('pg');
const { recordBatchFailure, recordBatchSuccess } = require('./ingest-history');
const { createStatusServer } = require('./status-server');
const { normalizeContent } = require('./content-contract');

const UPLOAD_ROOT = process.env.UPLOAD_ROOT || '/app/upload-store';
const ARCHIVE_ROOT = path.join(UPLOAD_ROOT, 'archive');
const ERROR_ROOT = path.join(UPLOAD_ROOT, 'error');
const CONTENT_ROOT = process.env.CONTENT_ROOT || '/app/content-store';
const TIME_ZONE = process.env.TZ || 'Asia/Seoul';
const RUN_PROCESS_NOW = process.env.RUN_PROCESS_NOW === 'true';
const STABILITY_INTERVAL = Number(process.env.STABILITY_CHECK_INTERVAL_MS || 10000);
const STABILITY_COUNT = Number(process.env.STABILITY_CHECK_COUNT || 3);
const STATUS_PORT = Number(process.env.STATUS_PORT || 3000);

const pool = new Pool({
  host: process.env.DB_HOST || 'postgres-db',
  port: Number(process.env.DB_PORT || 5432),
  database: process.env.POSTGRES_DB || 'smartbis_db',
  user: process.env.POSTGRES_USER || 'smartbis_user',
  password: process.env.POSTGRES_PASSWORD || 'change_this_password',
  max: 5,
  connectionTimeoutMillis: 5000
});

let processing = false;
let watcherReady = false;
let statusServer;

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function isBatchFolder(folder) {
  return !['archive', 'error'].includes(path.basename(folder));
}

async function ensureDirectories() {
  await fs.mkdir(UPLOAD_ROOT, { recursive: true });
  await fs.mkdir(ARCHIVE_ROOT, { recursive: true });
  await fs.mkdir(ERROR_ROOT, { recursive: true });
  await fs.mkdir(CONTENT_ROOT, { recursive: true });
}

async function listBatchFolders(directory) {
  const result = [];
  const entries = await fs.readdir(directory, { withFileTypes: true });

  for (const entry of entries) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    if (['archive', 'error'].includes(entry.name)) continue;

    const folder = path.join(directory, entry.name);
    const ready = path.join(folder, '.ready');
    const manifest = path.join(folder, 'contents.json');

    try {
      await fs.access(ready);
      await fs.access(manifest);
      result.push(folder);
    } catch {
      // 날짜 폴더처럼 배치 폴더가 아닌 디렉터리는 하위 폴더를 계속 탐색한다.
      result.push(...await listBatchFolders(folder));
    }
  }

  return result;
}

async function waitForStableFile(filePath) {
  let previous = await fs.stat(filePath);
  let stable = 0;

  while (stable < STABILITY_COUNT) {
    await sleep(STABILITY_INTERVAL);
    const current = await fs.stat(filePath);

    if (
      previous.size === current.size &&
      previous.mtimeMs === current.mtimeMs
    ) {
      stable += 1;
    } else {
      stable = 0;
    }

    previous = current;
  }
}

async function verifyMedia(batchFolder, item) {
  const assets = item.assets || [];
  if (assets.length === 0) return [];

  const verified = [];
  for (const asset of assets) {
    const mediaName = asset.file_name;
    if (path.basename(mediaName) !== mediaName || /[\\/]/.test(mediaName) || mediaName.includes('..')) {
      throw new Error(`허용되지 않는 파일 경로: ${item.content_id}`);
    }

    const mediaPath = path.resolve(batchFolder, mediaName);
    const relative = path.relative(batchFolder, mediaPath);

    if (relative.startsWith('..') || path.isAbsolute(relative)) {
      throw new Error(`허용되지 않는 파일 경로: ${item.content_id}`);
    }

    await fs.access(mediaPath);
    await waitForStableFile(mediaPath);

    const hash = crypto.createHash('sha256');
    let size = 0;

    await new Promise((resolve, reject) => {
      const stream = createReadStream(mediaPath);

      stream.on('data', chunk => {
        size += chunk.length;
        hash.update(chunk);
      });
      stream.on('end', resolve);
      stream.on('error', reject);
    });

    verified.push({
      ...asset,
      path: mediaPath,
      sha256: hash.digest('hex'),
      size,
      mimeType: mimeTypeFor(mediaName)
    });
  }
  return verified;
}

function mimeTypeFor(fileName) {
  const extension = path.extname(fileName).toLowerCase();
  return ({
    '.mp4': 'video/mp4', '.webm': 'video/webm', '.mov': 'video/quicktime',
    '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png',
    '.gif': 'image/gif', '.webp': 'image/webp'
  })[extension] || 'application/octet-stream';
}

async function copyMediaToContentStore(batchFolder, batch, media) {
  if (!media || media.length === 0) return [];

  const contentDate = path.basename(path.dirname(batchFolder));
  const destinationDir = path.join(
    CONTENT_ROOT,
    contentDate,
    batch.batchId
  );
  await fs.mkdir(destinationDir, { recursive: true });

  const stored = [];
  for (const asset of media) {
    const destination = path.join(destinationDir, path.basename(asset.path));
    await fs.copyFile(asset.path, destination);
    stored.push({
      ...asset,
      storedPath: destination,
      relativePath: path.relative(CONTENT_ROOT, destination).split(path.sep).join('/')
    });
  }
  return stored;
}

async function readBatch(batchFolder) {
  const manifestPath = path.join(batchFolder, 'contents.json');
  await waitForStableFile(manifestPath);

  const payload = JSON.parse(await fs.readFile(manifestPath, 'utf8'));

  const schemaVersion = payload.schema_version || '1.0';
  if (!['1.0', '2.0'].includes(schemaVersion) || !payload.batch_id || !Array.isArray(payload.contents)) {
    throw new Error('batch_id 또는 contents 배열이 없습니다.');
  }

  if (payload.contents.length === 0) {
    throw new Error('contents 배열이 비어 있습니다.');
  }
  if (schemaVersion === '2.0' && path.basename(batchFolder) !== payload.batch_id) {
    throw new Error('batch_id가 수신 배치 폴더명과 일치하지 않습니다.');
  }

  const ids = new Set();
  const items = [];

  for (const item of payload.contents) {
    const normalizedItem = normalizeContent(item, schemaVersion);

    if (ids.has(normalizedItem.content_id)) {
      throw new Error(`배치 내부 중복 content_id: ${item.content_id}`);
    }

    ids.add(normalizedItem.content_id);
    const media = await verifyMedia(batchFolder, normalizedItem);
    items.push({ item: normalizedItem, media });
  }

  return { batchId: payload.batch_id, schemaVersion, items };
}

async function saveBatch(batchFolder, batch) {
  const client = await pool.connect();

  try {
    await client.query('BEGIN');

    for (const { item, media } of batch.items) {
      const storedAssets = await copyMediaToContentStore(
        batchFolder,
        batch,
        media
      );

      await client.query(
        `
        INSERT INTO contents (
          content_id, batch_id, content_type, title, content,
          target_file_name, display_start_date, display_end_date,
          target_regions, template, source_path, category, presentation_type
        )
        VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9::jsonb,$10,$11,$12,$13)
        ON CONFLICT (content_id)
        DO UPDATE SET
          batch_id = EXCLUDED.batch_id,
          content_type = EXCLUDED.content_type,
          title = EXCLUDED.title,
          content = EXCLUDED.content,
          target_file_name = EXCLUDED.target_file_name,
          display_start_date = EXCLUDED.display_start_date,
          display_end_date = EXCLUDED.display_end_date,
          target_regions = EXCLUDED.target_regions,
          template = EXCLUDED.template,
          source_path = EXCLUDED.source_path,
          category = EXCLUDED.category,
          presentation_type = EXCLUDED.presentation_type,
          updated_at = CURRENT_TIMESTAMP
        `,
        [
          item.content_id,
          batch.batchId,
          ['VIDEO', 'IMAGE'].includes(item.presentation_type)
            ? item.presentation_type : 'CARD',
          item.title,
          item.content,
          storedAssets[0]?.file_name ?? null,
          item.display_start_date ?? null,
          item.display_end_date ?? null,
          JSON.stringify(item.target_regions ?? null),
          item.template,
          storedAssets[0]?.storedPath ?? null,
          item.category,
          item.presentation_type
        ]
      );

      await client.query('DELETE FROM content_assets WHERE content_id = $1', [item.content_id]);
      for (const asset of storedAssets) {
        await client.query(`
          INSERT INTO content_assets (
            content_id, asset_role, sequence_no, file_name, file_path,
            mime_type, file_size_bytes, sha256
          ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
        `, [
          item.content_id, asset.role, asset.sequence, asset.file_name,
          asset.relativePath, asset.mimeType, asset.size, asset.sha256
        ]);
      }
    }

    await client.query('COMMIT');
    console.log(`[database] 배치 저장 성공: ${batch.batchId} (${batch.items.length}건)`);
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

async function moveFolder(source, root) {
  const destination = path.join(
    root,
    `${Date.now()}_${path.basename(source)}`
  );
  await fs.rename(source, destination);
  return destination;
}

async function processBatch(batchFolder) {
  const folderBatchId = path.basename(batchFolder);

  try {
    console.log(`[process] 배치 처리 시작: ${batchFolder}`);
    const batch = await readBatch(batchFolder);
    await saveBatch(batchFolder, batch);
    const archived = await moveFolder(batchFolder, ARCHIVE_ROOT);
    console.log(`[archive] 배치 이동 완료: ${archived}`);

    try {
      await recordBatchSuccess(pool, batch.batchId, archived);
      console.log(`[database] 배치 이력 완료 처리: ${batch.batchId}`);
    } catch (historyError) {
      // Content and archive are already committed. Do not reclassify a successful
      // batch as failed just because its operational audit write failed.
      console.error(`[database] 배치 성공 이력 기록 실패: ${historyError.message}`);
    }
  } catch (error) {
    console.error(`[batch] 처리 실패: ${batchFolder}`);
    console.error(`[batch] 사유: ${error.message}`);

    let failedPath = null;
    try {
      failedPath = await moveFolder(batchFolder, ERROR_ROOT);
      console.error(`[error] 실패 배치 보관: ${failedPath}`);
    } catch (moveError) {
      console.error(`[error] 실패 배치 이동 오류: ${moveError.message}`);
    }

    try {
      const recorded = await recordBatchFailure(
        pool,
        error.batchId || folderBatchId,
        batchFolder,
        failedPath,
        error
      );
      console.log(
        `[database] 실패 이력 저장: ${recorded.batchId} (${recorded.errorCode})`
      );
    } catch (recordError) {
      console.error(`[database] 실패 이력 저장 불가: ${recordError.message}`);
      console.error('[database] 원본 배치는 upload-store/error 또는 기존 위치에 보존되어 있습니다.');
    }
  }
}

async function processPendingBatches() {
  if (processing) return;
  processing = true;

  try {
    const batches = await listBatchFolders(UPLOAD_ROOT);

    if (batches.length === 0) {
      console.log('[scheduler] 처리할 READY 배치가 없습니다.');
      return;
    }

    console.log(`[scheduler] 처리 대상 배치: ${batches.length}개`);

    for (const batchFolder of batches) {
      await processBatch(batchFolder);
    }

    console.log('[scheduler] 배치 일괄 처리 완료');
  } finally {
    processing = false;
  }
}

async function checkDatabase() {
  const result = await pool.query(
    'SELECT current_database() AS database_name, current_user AS user_name'
  );
  console.log(
    `[database] 연결 성공: ${result.rows[0].database_name} / ${result.rows[0].user_name}`
  );
}

function startWatcher() {
  const watcher = chokidar.watch(UPLOAD_ROOT, {
    ignored: [
      /(^|[\\/])\../,
      /(^|[\\/])archive([\\/])/,
      /(^|[\\/])error([\\/])/,
      /\.uploading$/
    ],
    ignoreInitial: false,
    persistent: true,
    usePolling: true,
    interval: 500,
    awaitWriteFinish: {
      stabilityThreshold: 2000,
      pollInterval: 100
    }
  });

  watcher.on('addDir', folder => {
    if (isBatchFolder(folder)) {
      console.log(`[receive] 폴더 수신 확인, .ready 대기: ${folder}`);
    }
  });

  watcher.on('add', file => {
    console.log(`[receive] 파일 수신 확인, 04:00 처리 대기: ${file}`);
  });

  watcher.on('error', error => {
    watcherReady = false;
    console.error(`[watcher] 오류: ${error.message}`);
  });

  watcher.on('ready', () => {
    watcherReady = true;
    console.log('[watcher] 파일 감시 초기화 완료');
  });

  console.log(`[watcher] 감시 시작: ${UPLOAD_ROOT}`);
  return watcher;
}

function startStatusServer() {
  statusServer = createStatusServer({
    pool,
    getState: () => ({
      watcher: watcherReady ? 'RUNNING' : 'STARTING',
      processing
    })
  });
  statusServer.listen(STATUS_PORT, '0.0.0.0', () => {
    console.log(`[status] 내부 상태 API 시작: :${STATUS_PORT}/health`);
  });
}

async function main() {
  await ensureDirectories();
  await checkDatabase();
  startWatcher();
  if (!RUN_PROCESS_NOW) startStatusServer();

  cron.schedule(
    '0 4 * * *',
    async () => {
      console.log(`[scheduler] ${TIME_ZONE} 기준 04:00 처리 시작`);
      await processPendingBatches();
    },
    { timezone: TIME_ZONE }
  );

  console.log(`[scheduler] 매일 ${TIME_ZONE} 04:00 일괄 처리 예약`);

  if (RUN_PROCESS_NOW) {
    console.log('[scheduler] RUN_PROCESS_NOW=true, 즉시 처리합니다.');
    await processPendingBatches();
    await pool.end();
    process.exit(0);
  }
}

main().catch(error => {
  console.error(`[watcher] 시작 실패: ${error.message}`);
  process.exit(1);
});

async function shutdown(signal) {
  console.log(`[watcher] ${signal} 수신, 종료 중`);
  if (statusServer) {
    await new Promise(resolve => statusServer.close(resolve));
  }
  await pool.end();
  process.exit(0);
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
