# FindMe

Sistema Android composto da due app:

- **transmitter**: pubblica posizione e, su comando, audio/video;
- **receiver**: mostra trasmettitori, stato, mappa e flusso LiveKit.

Il backend applicativo è Supabase; i media realtime passano attraverso LiveKit SFU.

## Prerequisiti

- JDK 17
- Android SDK 35
- account Supabase
- progetto LiveKit Cloud

## Configurazione locale

1. Copiare `local.properties.example` in `local.properties`.
2. Inserire URL e chiave **publishable** Supabase e URL WebSocket LiveKit.
3. Non inserire mai `sb_secret`, `service_role` o `LIVEKIT_API_SECRET` nel progetto Android.

## Build

```powershell
.\gradlew.bat test
.\gradlew.bat :transmitter:assembleDebug :receiver:assembleDebug
```

APK:

- `transmitter/build/outputs/apk/debug/transmitter-debug.apk`
- `receiver/build/outputs/apk/debug/receiver-debug.apk`

## Primo utilizzo

1. Installare il ricevitore: l'app crea automaticamente un'identità anonima sicura
   e mostra il proprio codice di associazione.
2. Installare/provisionare il trasmettitore passando quel codice come
   `findme_receiver_code`; l'associazione avviene in modo invisibile.
3. Alla prima apertura compare direttamente la domanda configurata dal
   ricevitore; rispondere per aprire la dashboard.
4. Sul trasmettitore attivare gli switch dei permessi e premere
   **Avvia monitoraggio**.
   Attivare anche **Nessuna restrizione batteria** e, sui dispositivi Xiaomi,
   impostare manualmente FindMe su **Risparmio batteria > Nessuna restrizione**
   e abilitare l’avvio automatico.
5. Sul ricevitore selezionare il dispositivo e attivare audio/video.

## Tracking e prestazioni

L’ingranaggio nella home del ricevitore configura per tutti i trasmettitori:

- posizione in background: 30/60/90/120 secondi;
- posizione rapida: 5/10/15/20 secondi;
- storico: 1x/2x/3x e salvataggio solo in movimento;
- heartbeat: 30/60/90/120 secondi.

Sessione Supabase, heartbeat e canali Realtime sono sorvegliati
indipendentemente. Dopo una perdita di rete entrambe le app ricreano
automaticamente il piano dati con retry progressivo; il monitoraggio locale
rimane attivo e non richiede interventi sull’interruttore.

Nel tab **Posizione**, l’occhio abilita temporaneamente il tracking rapido.
**Storico rapido** applica la stessa frequenza anche alla registrazione dello
storico. La sessione scade automaticamente dopo 90 secondi e viene rinnovata
solo mentre la vista è attiva.

Audio, video e mirroring schermo sono realmente on-demand: senza uno stream attivo, né il
trasmettitore né il ricevitore mantengono una connessione LiveKit. Lo storico,
con retention di 30 giorni, offre filtri 6h/24h/7 giorni o data/ora, percorso
MapLibre, timeline e lista paginata.

Durante lo streaming video il ricevitore può cambiare fotocamera e salvare il
fotogramma visualizzato nella galleria, dentro `Pictures/FindMe`.

Il ricevitore può inoltre registrare clip locali indipendenti: video senza
audio in `Movies/FindMe` (`.mp4`) e audio in `Music/FindMe` (`.m4a`). La
registrazione usa start/stop manuale, si arresta automaticamente lasciando il
tab o interrompendo lo stream e ha un limite di sicurezza di 30 minuti.

Il tab **Schermo** visualizza e registra in MP4 lo schermo del trasmettitore.
Android richiede una conferma MediaProjection al primo setup e nuovamente dopo
riavvio, aggiornamento, arresto del processo o revoca. La dashboard del
trasmettitore mostra lo stato e il pulsante **Riattiva**. La cattura autorizzata
rimane pronta, mentre LiveKit viene collegato solo quando lo switch remoto è ON.
L’audio interno delle applicazioni non viene acquisito: resta disponibile lo
stream separato del microfono.

Dal tab Posizione è inoltre possibile:

- aprire la mappa in modalità immersiva orizzontale, mantenendo coordinate e
  controlli in un pannello laterale;
- aprire anche lo storico in modalità immersiva, con percorso a sinistra e
  soltanto timeline e dettaglio del punto selezionato nel pannello destro;
- attivare un avviso area centrato sulla posizione corrente, con raggio
  50/100/250/500/1000 metri;
- ricevere una notifica FCM quando il trasmettitore passa dall’interno
  all’esterno dell’area. L’avviso si riarma dopo il rientro.

Non servono email o password. Il pairing iniziale usa un codice univoco di 10
caratteri fornito durante il provisioning; il nome del dispositivo rimane
soltanto un'etichetta leggibile. Dopo l'accesso è possibile cambiare ricevitore
dalla dashboard.
La risposta alla domanda non viene salvata in chiaro: il database conserva
soltanto un hash bcrypt.

Per database, Edge Function, LiveKit, mappe e Device Owner vedere
[`docs/EXTERNAL_SETUP.md`](docs/EXTERNAL_SETUP.md).

## Limiti intenzionali

- Notifica foreground e indicatori privacy Android restano visibili.
- In modalità standard, dopo reboot è necessario aprire l'app trasmittente.
- Il riavvio completamente automatico richiede il provisioning Device Owner.
- Camera e schermo possono essere pubblicati insieme e sono visualizzati in tab separati.
