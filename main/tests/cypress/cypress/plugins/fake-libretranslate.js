// A tiny fake LibreTranslate server for the "Translate column" e2e tests, so that
// tests never call a real translation service (Decision D8).
//
// OpenRefine must be started pointing at it:
//   LIBRETRANSLATE_URL=http://localhost:5055 ./refine e2e_tests
//
// It implements POST /translate with fixed, predictable translations, and can be
// switched "down" (closes the port, so connections are refused) to test the
// "service unreachable" error.

const http = require('http');

const DEFAULT_PORT = 5055;

// Fixed translations, keyed by target language then source text.
// Text that is not listed is returned as "[<target>] <text>", so it is still predictable.
const TRANSLATIONS = {
  es: { hello: 'hola', 'thank you': 'gracias', 'good morning': 'buenos días' },
  zh: { hello: '你好', 'thank you': '谢谢', 'good morning': '早上好' },
  en: { hola: 'hello', gracias: 'thank you', 你好: 'hello' },
};

function translate(text, target) {
  const table = TRANSLATIONS[target] || {};
  return Object.prototype.hasOwnProperty.call(table, text) ? table[text] : `[${target}] ${text}`;
}

function createFakeLibreTranslate(port = DEFAULT_PORT) {
  let requestCount = 0;

  const server = http.createServer((req, res) => {
    if (req.method !== 'POST' || req.url !== '/translate') {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Not found' }));
      return;
    }
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      requestCount++;
      let json;
      try {
        json = JSON.parse(body);
      } catch {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Invalid JSON' }));
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ translatedText: translate(json.q, json.target) }));
    });
  });

  const start = () =>
    new Promise((resolve, reject) => {
      if (server.listening) {
        resolve(null);
        return;
      }
      server.once('error', reject);
      server.listen(port, 'localhost', () => {
        server.off('error', reject);
        resolve(null);
      });
    });

  const stop = () =>
    new Promise((resolve) => {
      if (!server.listening) {
        resolve(null);
        return;
      }
      server.close(() => resolve(null));
      // OpenRefine keeps connections alive; drop them so new requests are really refused.
      server.closeAllConnections();
    });

  return {
    port,
    start,
    // Cypress tasks. Each must return a value (null is fine).
    tasks: {
      'fakeLibreTranslate:up': () => start(),
      'fakeLibreTranslate:down': () => stop(),
      'fakeLibreTranslate:resetRequestCount': () => {
        requestCount = 0;
        return null;
      },
      'fakeLibreTranslate:requestCount': () => requestCount,
      // Resolves with the request count as soon as it is > 0, or after timeoutMs (then possibly 0).
      'fakeLibreTranslate:waitForRequests': (timeoutMs = 10000) =>
        new Promise((resolve) => {
          const deadline = Date.now() + timeoutMs;
          const check = () => {
            if (requestCount > 0 || Date.now() >= deadline) {
              resolve(requestCount);
            } else {
              setTimeout(check, 100);
            }
          };
          check();
        }),
    },
  };
}

module.exports = { createFakeLibreTranslate, DEFAULT_PORT };
