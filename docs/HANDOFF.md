# Handoff

## Stato

MVP iniziale implementato:

- progetto Android multi-modulo;
- app trasmittente con foreground service, GPS, heartbeat, comandi e LiveKit;
- app ricevente con elenco realtime, mappa MapLibre/OpenFreeMap, comandi e renderer LiveKit;
- migrazione Supabase con RLS;
- Edge Function per token LiveKit;
- Device Owner e ripartenza al boot;
- test unitari sul rilevamento online/offline.

Evoluzioni implementate e validate sui dispositivi reali:

- accesso anonimo automatico senza login visibile;
- anagrafica ricevitori e relazione uno-a-molti tramite codice di pairing;
- domanda di accesso del ricevitore con risposta conservata come hash bcrypt;
- dashboard trasmettitore scura neon blu;
- permessi camera/microfono/GPS, posizione e notifiche rappresentati come switch;
- riconnessione LiveKit e rendering video tramite `TextureViewRenderer`;
- home ricevitore neon con profilo, codice di associazione e schede dei
  trasmettitori;
- indicatore verde di monitoraggio basato su stato e heartbeat recente;
- dettaglio dispositivo con tab Posizione, Video e Audio;
- streaming audio/video controllato da switch e stato sincronizzato via
  Supabase Realtime;
- cambio remoto tra camera frontale e posteriore;
- visualizzatore audio animato basato sul livello LiveKit, senza dipendenze
  aggiuntive;
- alias personalizzabile per ogni trasmettitore, salvato nella relazione col
  ricevitore e affiancato al nome originale;
- icone distinte per trasmittente e ricevente, entrambe installate come
  `FindMe`.
- frequenze GPS offline/online, storico e heartbeat configurabili dal
  ricevitore con valori vincolati dal database;
- tracking rapido per dispositivo basato su lease rinnovabile, con ritorno
  offline automatico anche in caso di crash o perdita rete;
- GPS bilanciato in background e ad alta precisione durante il tracking rapido;
- storico separato dalla posizione corrente, con intervallo moltiplicato e
  filtro di movimento dipendente anche dalla precisione GPS;
- LiveKit completamente on-demand e listener comandi Supabase indipendente;
- storico posizioni con filtri 6h/24h/7 giorni e data/ora, percorso MapLibre,
  timeline fino a 1.500 punti e lista paginata a 50 righe.

Le migrazioni `202609160005_media_state.sql`,
`202609170001_receiver_device_alias.sql` e
`202609170002_tracking_performance.sql`, inclusa la correzione RLS
`202609170003_tracking_policy_fix.sql`, devono essere applicate prima di
installare le nuove versioni delle app.

Nota di implementazione: i campi booleani dello stato media usano
`@EncodeDefault`, perché Supabase deve ricevere esplicitamente anche `false`.
Senza questa annotazione l'upsert omette il valore predefinito e uno stream
precedentemente attivo rimane erroneamente visualizzato come ON.

## Prima esecuzione

Completare `local.properties`, applicare le migrazioni e distribuire le Edge
Function seguendo `docs/EXTERNAL_SETUP.md`.

## Evoluzioni dopo validazione su telefoni reali

- provisioning Device Owner tramite QR/Android Management anziché ADB;
- notifiche FCM per telefoni non gestiti;
- miniature video multiple con simulcast;
- test strumentali su Android 14/15 e dispositivi dei produttori scelti;
- firma release e pipeline CI.
