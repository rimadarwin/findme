<!--
/**
 * @author Infinity
 * @description Presentazione, build e uso essenziale del progetto FindMe.
 * @modified 01.10.2026 - Infinity | Documentate configurazione challenge e nuove distanze area.
 * @modified 29.09.2026 - MDS | Documentati verifica distanza e messaggi in primo piano.
 * @modified 29.09.2026 - MDS | Documentato l'avviso area one-shot con tracking persistente.
 */
-->
# FindMe

Sistema Android composto da due app:

- **transmitter**: pubblica posizione e, su comando, audio/video/schermo e
  riproduce messaggi vocali;
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
- posizione rapida: 2/5/10/15/20 secondi;
- storico: 1x/2x/3x e salvataggio solo in movimento;
- heartbeat: 30/60/90/120 secondi.
- controllo comandi di sicurezza: 30/60/120/300 secondi.

Sessione Supabase, heartbeat e canali Realtime sono sorvegliati
indipendentemente. Dopo una perdita di rete entrambe le app ricreano
automaticamente il piano dati con retry progressivo; il monitoraggio locale
rimane attivo e non richiede interventi sull’interruttore. I comandi vengono
anche riletti periodicamente via REST: un WebSocket Realtime bloccato non può
più lasciare audio, video o schermo senza risposta. Il publisher LiveKit viene
ricostruito automaticamente dopo tre controlli media consecutivi non validi.
Gli arretrati vengono compattati applicando solo l’ultimo stato richiesto per
ciascuno stream, evitando rapide sequenze camera ON/OFF durante il recupero.

Nel tab **Posizione**, l’occhio abilita temporaneamente il tracking rapido.
**Storico rapido** applica la stessa frequenza anche alla registrazione dello
storico. La sessione scade automaticamente dopo 90 secondi e viene rinnovata
solo mentre la vista è attiva. L’uscita dall’area sorvegliata attiva invece
entrambi i controlli in modo persistente: restano ON finché vengono disattivati
manualmente.

Da **Configurazioni generali** è possibile cancellare definitivamente lo
storico scegliendo uno o più trasmettitori associati e confermando
esplicitamente l’operazione. Nella stessa schermata il ricevitore può modificare
la domanda e la risposta richieste all’apertura dei trasmettitori.

Audio, video e mirroring schermo sono realmente on-demand: senza uno stream attivo, né il
trasmettitore né il ricevitore mantengono una connessione LiveKit. Lo storico,
con retention di 30 giorni, offre filtri 6h/24h/7 giorni o data/ora, percorso
MapLibre, timeline e lista paginata.

Dalla home del ricevitore l’icona griglia consente di selezionare almeno due
trasmettitori e gestire insieme video e audio. I video supportano griglia
fullscreen 2×2 e fullscreen singolo; l’audio viene riprodotto simultaneamente.
Le registrazioni restano separate per dispositivo e sono limitate a due
contemporanee per tipo.

Durante lo streaming video il ricevitore può cambiare fotocamera e salvare il
fotogramma visualizzato nella galleria, dentro `Pictures/FindMe`.

Il ricevitore può inoltre registrare clip locali indipendenti: video senza
audio in `Movies/FindMe` (`.mp4`) e audio in `Music/FindMe` (`.m4a`). La
registrazione usa start/stop manuale, si arresta automaticamente lasciando il
tab o interrompendo lo stream e ha un limite di sicurezza di 30 minuti.
Durante streaming o registrazione il ricevitore mantiene temporaneamente
schermo e CPU attivi. Un blocco manuale non interrompe la sessione; passando
volontariamente a un’altra app gli stream vengono invece chiusi.

Nel tab **Audio** il ricevitore può registrare e inviare un messaggio vocale
AAC/M4A di massimo 60 secondi, scegliendo volume basso, medio o alto. Il file
passa da un bucket Supabase Storage privato e il comando resta persistente:
viene quindi riprodotto anche se il trasmettitore torna online in seguito.
Durante la riproduzione il microfono remoto viene temporaneamente sospeso per
evitare eco; volume e streaming precedenti vengono ripristinati al termine.

Il tab **Schermo** visualizza e registra in MP4 lo schermo del trasmettitore.
Android richiede una conferma MediaProjection al primo setup e nuovamente dopo
riavvio, aggiornamento, arresto del processo o revoca. La dashboard del
trasmettitore mostra lo stato e il pulsante **Riattiva**. La cattura autorizzata
rimane pronta, mentre LiveKit viene collegato solo quando lo switch remoto è ON.
L’audio interno delle applicazioni non viene acquisito: resta disponibile lo
stream separato del microfono.

Il tab **Messaggio** invia fino a 500 caratteri anche a un trasmettitore
temporaneamente offline. Il testo compare al centro sopra le altre app e resta
visibile finché viene chiuso con la X; il trasmettitore deve autorizzare una
volta **Messaggi in primo piano**.

Dal tab Posizione è inoltre possibile:

- aprire la mappa in modalità immersiva orizzontale, mantenendo coordinate e
  controlli in un pannello laterale;
- aprire anche lo storico in modalità immersiva, con percorso a sinistra e
  soltanto timeline e dettaglio del punto selezionato nel pannello destro;
- confrontare su mappa le posizioni di ricevitore e trasmettitore, con linea
  tratteggiata, coordinate, distanza in linea d’aria e fullscreen;
- attivare un avviso area centrato sulla posizione corrente, con raggio
  10/25/50/100/250/500/1000 metri;
- ricevere una notifica FCM quando il trasmettitore passa dall’interno
  all’esterno dell’area. Alla prima uscita l’avviso termina e abilita
  automaticamente aggiornamento rapido e storico rapido persistenti.

Non servono email o password. Il pairing iniziale usa un codice univoco di 10
caratteri fornito durante il provisioning; il nome del dispositivo rimane
soltanto un'etichetta leggibile. Dopo l'accesso è possibile cambiare ricevitore
dalla dashboard.
La verifica del trasmettitore usa un hash bcrypt. Per consentire al proprietario
di visualizzare e modificare la risposta, il valore corrente è conservato anche
in una tabella separata, leggibile soltanto dall’account anonimo proprietario
del ricevitore tramite RLS.

Manuali operativi con schermate reali (Markdown e PDF):

- [`docs/MANUALE_UTENTE_TRASMETTITORE.md`](docs/MANUALE_UTENTE_TRASMETTITORE.md) · [PDF](docs/manuale/MANUALE_UTENTE_TRASMETTITORE.pdf)
- [`docs/MANUALE_UTENTE_RICEVITORE.md`](docs/MANUALE_UTENTE_RICEVITORE.md) · [PDF](docs/manuale/MANUALE_UTENTE_RICEVITORE.pdf)

Per database, Edge Function, LiveKit, mappe e Device Owner vedere
[`docs/EXTERNAL_SETUP.md`](docs/EXTERNAL_SETUP.md).
Per creare una build firmata e un QR Device Owner completo vedere
[`docs/QR_PROVISIONING.md`](docs/QR_PROVISIONING.md).

## Limiti intenzionali

- Notifica foreground e indicatori privacy Android restano visibili.
- In modalità standard, dopo reboot è necessario aprire l'app trasmittente.
- Il provisioning QR Device Owner abilita il ripristino automatico del
  monitoraggio e pre-approva i permessi gestibili da Android.
- Il consenso MediaProjection per il mirroring non è concedibile dal Device
  Owner e va riconfermato dopo riavvio o revoca.
- Camera e schermo possono essere pubblicati insieme e sono visualizzati in tab separati.
