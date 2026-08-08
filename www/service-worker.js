/* Near — service worker (офлайн-режим)
 *
 * Раньше кэш работал «сначала кэш»: один раз сохранённый index.html отдавался
 * навсегда, и обновления приложения не доходили до людей вообще — на телефоне
 * оставалась та версия, которую открыли в первый раз.
 *
 * Теперь наоборот: сначала сеть, кэш — только запасной вариант, когда интернета
 * нет. Офлайн-режим при этом сохраняется.
 */
const CACHE = 'near-v3';
const ASSETS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('message', (e) => {
  if (e.data === 'skipWaiting') self.skipWaiting();
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  // Не перехватываем сторонние запросы (карта, тайлы, Firebase) — пусть идут в сеть
  if (new URL(e.request.url).origin !== self.location.origin) return;

  e.respondWith(
    fetch(e.request)
      .then((res) => {
        // складываем свежую копию на случай, когда интернета не будет
        if (res && res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        }
        return res;
      })
      .catch(() =>
        caches.match(e.request).then((cached) => cached || caches.match('./index.html'))
      )
  );
});
