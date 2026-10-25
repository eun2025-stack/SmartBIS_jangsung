'use strict';

const http = require('node:http');

function sendJson(response, statusCode, body) {
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store'
  });
  response.end(JSON.stringify(body));
}

function createStatusServer({ pool, getState = () => ({}) }) {
  return http.createServer(async (request, response) => {
    if (request.method !== 'GET') {
      response.setHeader('Allow', 'GET');
      sendJson(response, 405, { status: 'METHOD_NOT_ALLOWED' });
      return;
    }

    if (request.url !== '/health') {
      sendJson(response, 404, { status: 'NOT_FOUND' });
      return;
    }

    try {
      await pool.query('SELECT 1');
      sendJson(response, 200, {
        status: 'UP',
        database: 'UP',
        uptimeSeconds: Math.floor(process.uptime()),
        checkedAt: new Date().toISOString(),
        ...getState()
      });
    } catch {
      sendJson(response, 503, {
        status: 'DOWN',
        database: 'DOWN',
        checkedAt: new Date().toISOString(),
        ...getState()
      });
    }
  });
}

module.exports = { createStatusServer };
