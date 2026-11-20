/* Guarda la app para que abra rápido y sin internet. Gemini siempre va por la red. */
const V = "yasuo-v2";
const FILES = ["./", "index.html", "manifest.json", "icon-192.png", "icon-512.png", "img/berserker-s-greaves.webp", "img/blade-of-the-ruined-king.webp", "img/bone-plating.webp", "img/brutal.webp", "img/conqueror.webp", "img/coup-de-grace.webp", "img/courage-of-the-colossus.webp", "img/cut-down.webp", "img/death-s-dance.webp", "img/demolish.webp", "img/essence-reaver.webp", "img/exhaust.webp", "img/flash.webp", "img/giant-slayer.webp", "img/grasp-of-the-undying.webp", "img/guardian-angel.webp", "img/ignite.webp", "img/infinity-edge.webp", "img/legend-alacrity.webp", "img/lethal-tempo.webp", "img/long-sword.webp", "img/lord-dominik-s-regards.webp", "img/maw-of-malmortius.webp", "img/mercurial-scimitar.webp", "img/mercury-s-treads.webp", "img/mortal-reminder.webp", "img/overgrowth.webp", "img/perseverance.webp", "img/plated-steelcaps.webp", "img/second-wind.webp", "img/unflinching.webp", "img/wit-s-end.webp"];
self.addEventListener("install", e => { e.waitUntil(caches.open(V).then(c => c.addAll(FILES)).then(() => self.skipWaiting())); });
self.addEventListener("activate", e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== V).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener("fetch", e => {
  const u = new URL(e.request.url);
  if(e.request.method !== "GET" || u.origin !== location.origin) return;   // Gemini y fuentes: red directa
  /* la página: primero red (para recibir actualizaciones), si no hay, la guardada */
  if(e.request.mode === "navigate"){ e.respondWith(fetch(e.request).then(r => { const cp = r.clone(); caches.open(V).then(c => c.put("index.html", cp)); return r; }).catch(() => caches.match("index.html"))); return; }
  e.respondWith(caches.match(e.request).then(r => r || fetch(e.request)));
});
