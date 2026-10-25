'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { normalizeContent } = require('../src/content-contract');

function v2(presentationType, assets = []) {
  return {
    content_id: 'TEST_001', category: 'EVENT', presentation_type: presentationType,
    title: '테스트', content: '테스트 본문', template: 'EVENT',
    target_regions: null, assets
  };
}

test('legacy v1 single VIDEO file is normalized as a one-asset VIDEO', () => {
  const item = normalizeContent({
    content_id: 'OLD_1', content_type: 'VIDEO', title: '옛 형식',
    content: '본문', template: '행사', target_file_name: 'clip.mp4'
  }, '1.0');

  assert.equal(item.category, 'EVENT');
  assert.equal(item.presentation_type, 'VIDEO');
  assert.deepEqual(item.assets, [{ role: 'VIDEO', file_name: 'clip.mp4', sequence: 1 }]);
});

test('v2 CARD_NEWS orders multiple image assets by sequence', () => {
  const item = normalizeContent(v2('CARD_NEWS', [
    { role: 'IMAGE', file_name: 'second.jpg', sequence: 2 },
    { role: 'IMAGE', file_name: 'first.jpg', sequence: 1 }
  ]), '2.0');

  assert.deepEqual(item.assets.map(asset => asset.file_name), ['first.jpg', 'second.jpg']);
});

test('v2 TEXT permits no assets and requires body text', () => {
  assert.deepEqual(normalizeContent(v2('TEXT'), '2.0').assets, []);
  assert.throws(() => normalizeContent({ ...v2('TEXT'), content: '' }, '2.0'), /본문이 비어 있습니다/);
});

test('v2 rejects incomplete card-news, path traversal, and duplicate sequence', () => {
  assert.throws(() => normalizeContent(v2('CARD_NEWS', [
    { role: 'IMAGE', file_name: 'only.jpg', sequence: 1 }
  ]), '2.0'), /첨부 개수 오류/);
  assert.throws(() => normalizeContent(v2('IMAGE', [
    { role: 'IMAGE', file_name: '../outside.jpg', sequence: 1 }
  ]), '2.0'), /파일명\/순번 오류/);
  assert.throws(() => normalizeContent(v2('CARD_NEWS', [
    { role: 'IMAGE', file_name: 'a.jpg', sequence: 1 },
    { role: 'IMAGE', file_name: 'b.jpg', sequence: 1 }
  ]), '2.0'), /파일명\/순번 오류/);
});
