'use strict';

const path = require('node:path');

const PRESENTATION_TYPES = new Set([
  'POSTER_TEXT', 'TEXT', 'CARD_NEWS', 'IMAGE', 'VIDEO'
]);

function normalizeContent(item, schemaVersion) {
  if (schemaVersion !== '2.0') {
    validateLegacyItem(item);
    const assets = item.target_file_name ? [{
      role: item.content_type === 'VIDEO' ? 'VIDEO' : 'IMAGE',
      file_name: item.target_file_name,
      sequence: 1
    }] : [];
    return {
      ...item,
      category: ['행사', 'EVENT'].includes(String(item.template).toUpperCase()) ? 'EVENT' : 'NOTICE',
      presentation_type: item.content_type === 'CARD' ? 'TEXT' : item.content_type,
      assets
    };
  }

  if (!item || !['NOTICE', 'EVENT'].includes(item.category)) {
    throw new Error(`지원하지 않는 category: ${item?.category}`);
  }
  if (!PRESENTATION_TYPES.has(item.presentation_type)) {
    throw new Error(`지원하지 않는 presentation_type: ${item.presentation_type}`);
  }
  if (!Array.isArray(item.assets)) {
    throw new Error(`assets 배열이 필요합니다: ${item.content_id}`);
  }
  if (typeof item.content !== 'string') {
    throw new Error(`content 문자열이 필요합니다: ${item.content_id}`);
  }

  const assets = item.assets;
  const expected = {
    POSTER_TEXT: ['POSTER', 1],
    TEXT: [null, 0],
    CARD_NEWS: ['IMAGE', 2],
    IMAGE: ['IMAGE', 1],
    VIDEO: ['VIDEO', 1]
  }[item.presentation_type];
  if (assets.length < expected[1] ||
      (item.presentation_type !== 'CARD_NEWS' && assets.length !== expected[1])) {
    throw new Error(`${item.presentation_type} 첨부 개수 오류: ${item.content_id}`);
  }
  if (expected[0] && assets.some(asset => asset.role !== expected[0])) {
    throw new Error(`${item.presentation_type} 첨부 역할 오류: ${item.content_id}`);
  }

  const sequences = new Set();
  for (const asset of assets) {
    if (!asset || !['POSTER', 'IMAGE', 'VIDEO'].includes(asset.role) ||
        typeof asset.file_name !== 'string' ||
        !asset.file_name.trim() ||
        path.basename(asset.file_name) !== asset.file_name ||
        asset.file_name.includes('..') || /[\\/]/.test(asset.file_name) ||
        !Number.isInteger(asset.sequence) || asset.sequence < 1 ||
        sequences.has(asset.sequence)) {
      throw new Error(`첨부파일 필드/파일명/순번 오류: ${item.content_id}`);
    }
    sequences.add(asset.sequence);
  }

  if (item.presentation_type === 'TEXT' && item.content?.trim() === '') {
    throw new Error(`TEXT 본문이 비어 있습니다: ${item.content_id}`);
  }
  if (item.presentation_type === 'POSTER_TEXT' && item.content?.trim() === '') {
    throw new Error(`POSTER_TEXT 본문이 비어 있습니다: ${item.content_id}`);
  }

  validateLegacyItem({
    ...item,
    content_type: ['VIDEO', 'IMAGE'].includes(item.presentation_type) ? item.presentation_type : 'CARD',
    content: item.content.trim() ? item.content : '(미디어 전용 콘텐츠)',
    target_file_name: assets[0]?.file_name || null
  });
  return { ...item, assets: [...assets].sort((a, b) => a.sequence - b.sequence) };
}

function validateLegacyItem(item) {
  const required = ['content_id', 'content_type', 'title', 'content', 'template'];
  for (const field of required) {
    if (item[field] === undefined || item[field] === null ||
        (typeof item[field] === 'string' && !item[field].trim())) {
      throw new Error(`${field} 필드가 없습니다: ${item.content_id || 'unknown'}`);
    }
  }
  if (!['VIDEO', 'IMAGE', 'CARD'].includes(item.content_type)) {
    throw new Error(`지원하지 않는 content_type: ${item.content_type}`);
  }
  if (item.target_regions !== undefined && item.target_regions !== null &&
      !Array.isArray(item.target_regions)) {
    throw new Error(`target_regions는 배열 또는 null이어야 합니다: ${item.content_id}`);
  }
  for (const field of ['display_start_date', 'display_end_date']) {
    if (item[field] !== undefined && item[field] !== null &&
        !/^\d{4}-\d{2}-\d{2}$/.test(item[field])) {
      throw new Error(`${field} 형식 오류: ${item.content_id}`);
    }
  }
  if (item.display_start_date && item.display_end_date &&
      item.display_end_date < item.display_start_date) {
    throw new Error(`기간 순서 오류: ${item.content_id}`);
  }
}

module.exports = { normalizeContent, validateLegacyItem };
