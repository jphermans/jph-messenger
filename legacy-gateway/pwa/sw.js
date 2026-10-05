const CACHE_NAME = 'jph-messenger-v1';
const urlsToCache = [
  '/app/',
  '/pwa/manifest.json',
  '/pwa/icon-192.png',
  '/pwa/icon-512.png'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(urlsToCache))
  );
});

self.addEventListener('fetch', event => {
  event.respondWith(
    caches.match(event.request).then(response => response || fetch(event.request))
  );
});
