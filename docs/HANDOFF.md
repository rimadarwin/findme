/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Stato tecnico e indicazioni di passaggio del progetto FindMe.
 * @modified 23.09.2026 - MDS | Documentata la persistenza del tracking rapido al blocco.
 * @modified 23.09.2026 - MDS | Documentato il feedback dei comandi multimediali.
 */
# Handoff

## Stato

MVP iniziale implementato:

- progetto Android multi-modulo;
- app trasmittente con foreground service, GPS, heartbeat, comandi e LiveKit;
- app ricevente con elenco realtime, mappa MapLibre/OpenFreeMap, comandi e renderer LiveKit;
- migrazione Supabase con RLS;
- Edge Function per token LiveKit;
- Device Owner e ripartenza al boot;
- provisioning Device Owner QR per Android 12+ con attività DPC moderne,
  admin extras, firma release parametrica e generatore locale di checksum/QR;
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
- messaggi vocali asincroni AAC/M4A fino a 60 secondi dal ricevitore al
  trasmettitore, con Storage privato, consegna persistente, stato di
  riproduzione e volume temporaneo basso/medio/alto;
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
- mirroring schermo MediaProjection con cattura persistente indipendente dalla
  stanza LiveKit, quarta tab source-aware e riattivazione manuale dopo
  reboot/revoca;
- storico posizioni con filtri 6h/24h/7 giorni e data/ora, percorso MapLibre,
  timeline fino a 1.500 punti e lista paginata a 50 righe.
- mappa Posizione immersiva in orientamento landscape con pannello laterale;
- storico immersivo landscape con timeline progressiva e dettaglio del solo
  punto selezionato, senza elenco completo nel pannello laterale;
- alert area circolare con raggi predefiniti, centro fotografato
  all’attivazione, overlay MapLibre e transizioni uscita/rientro idempotenti;
- notifiche FCM al ricevitore anche ad app chiusa, con token protetti da RLS e
  invio tramite Edge Function.
- acquisizione di un fotogramma dal video remoto e salvataggio nella galleria
  del ricevitore in `Pictures/FindMe`.
- registrazione locale indipendente di video H.264/MP4 in `Movies/FindMe` e
  schermo H.264/MP4 in `Movies/FindMe` e audio AAC/M4A in `Music/FindMe`, con
  REC manuale, timer, limite 30 minuti e finalizzazione automatica su uscita
  dal tab, stop stream o disconnessione.
- protezione energetica temporanea del ricevitore durante media e registrazioni:
  `FLAG_KEEP_SCREEN_ON`, wake lock CPU e mantenimento LiveKit durante il blocco
  schermo, con rilascio automatico alla chiusura degli stream.
- resilienza di rete end-to-end: client Supabase unico per processo, refresh
  sessione esplicito, rinnovo periodico Realtime, watchdog heartbeat, retry
  esponenziale e riavvio immediato del piano dati al ritorno della rete.
- recupero comandi ibrido: Realtime per la bassa latenza e polling REST
  configurabile 5/15/30/60/120/300 secondi indipendente dal WebSocket; il fetch
  iniziale precede sempre la sottoscrizione e il publisher LiveKit viene
  ricostruito dopo tre controlli media consecutivi non validi. Gli arretrati
  sono compattati all’ultimo stato audio/video/schermo prima dell’applicazione.
- feedback immediato dei comandi video, audio e mirroring con switch ottimistico
  bloccato durante l’invio, conferma sullo stato reale, timeout a 20 secondi e
  possibilità di riprovare senza generare comandi duplicati.
- tracking e storico rapidi protetti come una sessione media: il lease continua
  a rinnovarsi col ricevitore bloccato e termina soltanto alla disattivazione,
  alla chiusura del dettaglio o al normale passaggio in background.
- esenzione dall’ottimizzazione batteria richiedibile dalla dashboard del
  trasmettitore; sui firmware Xiaomi resta necessaria anche l’impostazione
  proprietaria “Nessuna restrizione”.
- cancellazione selettiva dello storico dalle configurazioni del ricevitore,
  con selezione multipla, doppia conferma e RPC autorizzata lato database.

Le migrazioni `202609160005_media_state.sql`,
`202609170001_receiver_device_alias.sql` e
`202609170002_tracking_performance.sql`, inclusa la correzione RLS
`202609170003_tracking_policy_fix.sql`, e
`202609170004_geofence_alert.sql` e
`202609170005_screen_mirroring.sql` e
`202609180001_delete_location_history.sql` e
`202609200001_command_recovery.sql` e
`202609200002_voice_messages_schema.sql` e
`202609200003_voice_messages_rpc.sql` e
`202609200004_voice_storage_policy_fix.sql` e
`202609230001_fast_command_recovery.sql` devono essere applicate prima di
installare le nuove versioni delle app.

Nota di implementazione: i campi booleani dello stato media usano
`@EncodeDefault`, perché Supabase deve ricevere esplicitamente anche `false`.
Senza questa annotazione l'upsert omette il valore predefinito e uno stream
precedentemente attivo rimane erroneamente visualizzato come ON.

Nota di affidabilità: “online” dimostra che gli heartbeat REST funzionano, ma
non garantisce che il WebSocket Realtime dei comandi sia vivo. Per questo il
polling comandi deve restare indipendente dalla sottoscrizione Realtime.

I messaggi vocali non usano LiveKit: il receiver carica il file nel bucket
privato `voice-messages`, quindi la RPC crea nello stesso commit il record e il
comando `play_voice_message`. Il transmitter sospende temporaneamente il
microfono per evitare eco, riproduce in foreground, ripristina il volume
precedente ed elimina l’oggetto Storage dopo il completamento.

## Prima esecuzione

Completare `local.properties`, applicare le migrazioni e distribuire le Edge
Function seguendo `docs/EXTERNAL_SETUP.md`.

## Evoluzioni dopo validazione su telefoni reali

- validazione end-to-end del QR sui Setup Wizard Xiaomi/Samsung scelti;
- miniature video multiple con simulcast;
- test strumentali su Android 14/15 e dispositivi dei produttori scelti;
- pubblicazione protetta degli APK release e pipeline CI.
