<!--
/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Specifica funzionale completa del prodotto FindMe.
 * @modified 29.09.2026 - MDS | Descritto l'avviso area one-shot con controlli rapidi persistenti.
 */
-->
# FindMe — Analisi funzionale completa

## 1. Scopo del documento

Questo documento descrive il comportamento funzionale e l’esperienza utente
dell’attuale progetto FindMe. È pensato come specifica di ricostruzione: una
persona o un’altra AI devono poter riprodurre il prodotto senza dover
ricostruire le decisioni prese durante lo sviluppo.

La descrizione fotografa lo stato corrente del repository. I riferimenti
tecnici necessari a implementare i comportamenti sono approfonditi in
`docs/ANALISI_TECNICA.md`.

## 2. Visione del prodotto

FindMe è un sistema composto da due applicazioni Android distinte, entrambe
visualizzate sul telefono con il nome **FindMe** ma installate con package e
icone differenti:

- **Ricevitore** (`it.xcc.findme.receiver`): mostra e controlla uno o più
  telefoni trasmettitori associati.
- **Trasmettitore** (`it.xcc.findme.transmitter`): invia posizione e stato,
  pubblica su richiesta camera, microfono o schermo e riproduce messaggi vocali
  ricevuti.

Il caso d’uso previsto è la gestione di telefoni appartenenti allo stesso
proprietario. Questo non elimina i vincoli Android: notifica del foreground
service, indicatori privacy e consenso MediaProjection rimangono visibili e
non devono essere aggirati.

I principi funzionali sono:

1. un ricevitore può controllare più trasmettitori;
2. ogni trasmettitore è associato a un solo ricevitore alla volta;
3. posizione, stato e comandi passano dal backend Supabase;
4. audio, video e mirroring sono realmente on-demand tramite LiveKit;
5. nessuna email o password è mostrata all’utente;
6. pairing e accesso sono separati: il codice associa i dispositivi, la domanda
   di sicurezza protegge l’apertura della dashboard del trasmettitore;
7. il monitoraggio deve recuperare automaticamente rete, sessione e canali
   bloccati senza richiedere uno stop/start manuale;
8. le operazioni distruttive richiedono conferma esplicita.

## 3. Ruoli, identità e relazione tra dispositivi

### 3.1 Identità locale

Ogni installazione genera al primo utilizzo un UUID persistente. Il nome
predefinito è il modello Android (`Build.MODEL`) e può essere modificato nel
trasmettitore. L’identità viene conservata nelle preferenze private
`findme_device`.

### 3.2 Ricevitore

Alla prima inizializzazione il ricevitore:

1. crea automaticamente una sessione Supabase anonima;
2. registra il proprio device con ruolo `receiver`;
3. crea il profilo ricevitore;
4. riceve dal database un codice univoco esadecimale di 10 caratteri;
5. mostra codice e UUID nella home.

### 3.3 Trasmettitore

Il trasmettitore crea una sessione anonima distinta e registra un device con
ruolo `transmitter`. L’associazione può avvenire:

- automaticamente, leggendo `findme_receiver_code` dagli admin extras del
  provisioning QR;
- manualmente dalla dashboard già sbloccata, inserendo un nuovo codice nel
  riquadro **Ricevitore** e premendo **Cambia ricevitore**.

Il pairing manuale o automatico sostituisce l’eventuale relazione precedente.

### 3.4 Alias

Il ricevitore può assegnare un alias a ogni trasmettitore dalla scheda nella
home. L’alias:

- è salvato nella relazione ricevitore–trasmettitore;
- viene modificato tramite dialog **Nome personalizzato**, con massimo 80
  caratteri;
- è specifico di quel ricevitore;
- se vuoto o composto da soli spazi viene eliminato;
- se assente, l’interfaccia usa il nome originale del trasmettitore;
- nella home, quando presente, viene mostrato insieme al nome originale più
  piccolo;
- nella testata della pagina di dettaglio viene mostrato soltanto il nome
  effettivo, senza matita e senza seconda riga con il codice/nome originale.

## 4. Linguaggio visivo condiviso

Entrambe le applicazioni usano Jetpack Compose e un tema scuro neon:

- colore primario: azzurro neon `#00D9FF`;
- colore secondario: blu `#4D8DFF`;
- sfondo: quasi nero blu `#020812`;
- superfici/card: `#091522`;
- variante superficie: `#10263A`;
- testo principale: `#E8F7FF`;
- testo secondario: `#AAC9D8`;
- errore: rosa/rosso `#FF6B8A`;
- indicatore monitoraggio attivo: verde `#38F28D`;
- indicatore inattivo: grigio-blu `#40505B`.

Le card sono usate per raggruppare informazioni e controlli. La home del
ricevitore applica alle schede dei trasmettitori un bordo da 1 dp del colore
primario. Le spaziature principali sono 12–16 dp; le liste usano separazione
verticale uniforme.

Le quattro sezioni del dettaglio dispositivo usano icone, non testo, per
evitare che le etichette vadano a capo:

- posizione: puntatore;
- video: videocamera;
- audio: microfono;
- schermo: screen share.

Ogni icona tab misura 26 dp e ha una descrizione accessibile con il nome della
sezione.

## 5. Flusso del trasmettitore

### 5.1 Avvio e inizializzazione

All’apertura appare il titolo **FindMe**, centrato e in azzurro. Se
`local.properties` non contiene configurazioni valide, viene mostrato:
**Configurazione mancante: completa local.properties seguendo README.md.**

Durante registrazione e autenticazione appare:
**Inizializzazione sicura del dispositivo…**

In caso di assenza temporanea della rete, l’app non termina il flusso:
visualizza **Connessione temporaneamente assente. Riprovo automaticamente…**
ed esegue retry progressivi.

### 5.2 Schermata di accesso

Prima della dashboard viene sempre presentata la domanda configurata sul
ricevitore. Non esiste una schermata preliminare di pairing nel percorso
normale QR.

Il layout contiene:

- titolo globale **FindMe**;
- card con la sola domanda, in colore primario;
- campo risposta alto 56 dp, con bordo azzurro e angoli arrotondati;
- placeholder **La tua risposta**;
- estensione destra del campo larga 56 dp e riempita di azzurro;
- triangolo “play” rivolto a destra per inviare.

Il pulsante è abilitato solo con risposta non vuota e verifica non già in
corso. La risposta viene normalizzata con trim e minuscole.

Comportamenti:

- risposta corretta: apre immediatamente la dashboard;
- risposta errata: resta sulla stessa schermata senza messaggio negativo;
- challenge non disponibile: mostra **Provisioning incompleto**, una
  spiegazione e il pulsante **Riprova**;
- se è presente un codice ricevuto dal provisioning, l’app tenta prima il
  pairing automatico e poi ricarica la domanda.

### 5.3 Dashboard trasmettitore

La dashboard è una colonna verticale scrollabile con tre card.

#### Card Dispositivo

Contiene:

- titolo **Dispositivo**;
- campo modificabile **Nome dispositivo**;
- stato **Device Owner attivo** oppure **Modalità standard**;
- UUID tecnico in corpo piccolo.

La modifica del nome viene salvata localmente durante la digitazione.

#### Card Ricevitore

Contiene:

- titolo **Ricevitore**;
- campo **Nuovo codice ricevitore**, convertito in maiuscolo e limitato a 10
  caratteri;
- pulsante **Cambia ricevitore**, abilitato soltanto con 10 caratteri.

Dopo l’associazione viene ricaricata la domanda di sicurezza del nuovo
ricevitore.

#### Card Autorizzazioni

Ogni voce usa testo, descrizione e switch; le righe sono separate da divisori.

1. **Monitoraggio attivo**
   - ON: foreground service in esecuzione;
   - OFF: servizio fermo;
   - non può essere attivato prima di camera, microfono e GPS.
2. **Mirroring schermo**
   - non usa uno switch ma un pulsante **Riattiva**;
   - stati: non autorizzato, attivazione in corso, pronto;
   - il pulsante è abilitato soltanto con monitoraggio e permessi attivi e
     MediaProjection non disponibile.
3. **Camera, microfono e GPS**
   - ON richiede i permessi runtime;
   - OFF apre le impostazioni dell’app, perché Android non consente la revoca
     diretta da uno switch applicativo.
4. **Posizione sempre**
   - richiede il permesso background sui sistemi che lo prevedono;
   - se negato, la posizione continua mentre il foreground service è attivo ma
     non viene promessa la stessa affidabilità in ogni condizione.
5. **Nessuna restrizione batteria**
   - apre la richiesta di esclusione dalle ottimizzazioni;
   - disattivazione o gestione avanzata aprono le impostazioni di sistema.
6. **Notifiche**
   - gestisce solo le notifiche FindMe;
   - da Android 13 usa il permesso runtime;
   - per disattivare apre la pagina notifiche dell’app.

### 5.4 Notifica persistente

Con monitoraggio attivo Android mostra una notifica foreground non
eliminabile:

- titolo: **Monitoraggio FindMe attivo**;
- testo normale: posizione, camera, microfono e schermo disponibili da remoto;
- se MediaProjection era stata concessa ma non è più attiva: invita a toccare
  per riattivare il mirroring.

La notifica è intenzionale e necessaria. Non deve essere nascosta.

### 5.5 Ripristino dopo riavvio

Il flag **Monitoraggio attivo** è persistente. In Device Owner, il ricevitore
di boot riavvia il servizio dopo:

- `BOOT_COMPLETED`;
- `LOCKED_BOOT_COMPLETED`;
- aggiornamento dell’APK (`MY_PACKAGE_REPLACED`).

In modalità standard può essere necessario aprire nuovamente l’app. Il
consenso MediaProjection non sopravvive a reboot, revoca, aggiornamento o
arresto del processo.

## 6. Home del ricevitore

La home è una `LazyColumn` con distanza verticale di 12 dp.

### 6.1 Sezione Questo telefono

La testata mostra:

- **Questo telefono** in colore primario;
- icona ingranaggio a destra, con descrizione **Configurazioni generali**.

La card sottostante contiene:

- nome del ricevitore o fallback **Ricevitore FindMe**;
- etichetta **Codice di associazione**;
- codice di 10 caratteri evidenziato in azzurro e carattere grande;
- riga tecnica `ID <uuid>` in corpo piccolo.

### 6.2 Elenco dispositivi associati

Il titolo è **Dispositivi associati (N)**. Se l’elenco è vuoto appare
**Nessun dispositivo associato.**

Ogni scheda:

- occupa tutta la larghezza;
- ha sfondo surface e bordo azzurro da 1 dp;
- è interamente cliccabile;
- mostra alias/nome e controllo modifica alias;
- mostra a destra un pallino di 12 dp;
- mostra sotto `Online/Offline • Batteria N%` oppure `n/d`;
- usa 8 dp di spazio verticale uniforme.

Il pallino è verde solo se:

1. `is_monitoring` è vero;
2. l’ultimo heartbeat è più recente di due volte l’intervallo heartbeat
   configurato.

Il dispositivo può quindi essere **Online** ma con monitoraggio non attivo.

## 7. Dettaglio trasmettitore

### 7.1 Testata compatta

La card superiore contiene soltanto:

- simbolo `‹` per tornare indietro;
- nome effettivo del dispositivo, una riga con ellissi;
- riga `Online/Offline • Batteria N%`;
- pallino stato monitoraggio.

Non contiene matita, codice dispositivo o nome originale. Padding: 10 dp;
distanza tra elementi: 8 dp.

Quando si lascia il dettaglio, il ricevitore:

- ferma registrazioni locali;
- scarta un’eventuale bozza vocale;
- invia stop per audio, video e schermo;
- chiude la connessione LiveKit quando non serve più.

### 7.2 Tab Posizione

È la tab predefinita.

Se non esiste ancora una posizione mostra:
**Posizione non ancora disponibile.**

Altrimenti mostra:

- coordinate con 6 decimali;
- accuratezza `±N m`;
- mappa MapLibre/OpenFreeMap alta 320 dp nella vista normale;
- marker della posizione;
- overlay circolare della geofence quando attiva;
- pulsante fullscreen in alto a destra della mappa.

Sotto la mappa sono presenti:

#### Aggiornamento rapido

- richiede posizioni più frequenti;
- è abilitabile solo se il trasmettitore è online;
- crea una lease di 90 secondi;
- il ricevitore la rinnova ogni 20 secondi mentre la vista resta attiva;
- alla chiusura o scadenza il trasmettitore torna automaticamente alla
  frequenza offline.

#### Storico rapido

- è abilitabile solo se **Aggiornamento rapido** è attivo;
- usa la frequenza online come base anche per lo storico;
- l’intervallo effettivo resta moltiplicato per il moltiplicatore configurato.

#### Avviso uscita area

- quando viene acceso fotografa la posizione corrente come centro;
- usa il raggio configurato nelle configurazioni generali;
- richiede posizione disponibile e trasmettitore online;
- quando attivo mostra raggio e stato `dentro l’area`;
- mostra le coordinate del centro;
- allo spegnimento elimina centro, raggio e stato esterno.

La prima uscita viene gestita atomicamente: l’avviso area si spegne,
**Aggiornamento rapido** e **Storico rapido** si accendono in modo persistente
e viene accodata una notifica. I due controlli restano attivi, anche dopo
blocco o chiusura del ricevitore, finché l’utente li disattiva manualmente.
Un errore FCM non annulla l’evento: la consegna viene ritentata con backoff.

#### Storico

Il link **Consulta storico posizioni** apre la pagina dedicata.

### 7.3 Fullscreen posizione

La mappa fullscreen:

- forza orientamento landscape, senza rotazione automatica;
- nasconde status bar e navigation bar;
- occupa la maggior parte dello schermo a sinistra;
- usa a destra un pannello scrollabile largo 310 dp per nome, coordinate,
  controlli rapidi, geofence, storico e ritorno;
- ripristina orientamento e barre di sistema all’uscita;
- deve lasciare fluidi pan, zoom e rotazione della mappa senza conflitti con lo
  scroll Compose.

### 7.4 Tab Video

Contiene:

1. card **Streaming video** con switch;
2. icona cambio camera da 30 dp;
3. icona fotografia da 28 dp;
4. card **Registra video**;
5. riquadro nero 260 dp;
6. indicazione camera frontale/posteriore quando lo stream è attivo.

Lo switch è abilitato solo con dispositivo online e camera disponibile.

Il cambio camera:

- alterna frontale e posteriore;
- è disabilitato mentre una registrazione video è attiva.

La fotografia:

- è abilitata solo con stream attivo;
- salva il frame visualizzato nella galleria, `Pictures/FindMe`;
- non mostra un toast testuale;
- visualizza una miniatura che si riduce e si sposta diagonalmente verso il
  basso a destra, simulando l’animazione screenshot.

Il riquadro mostra **Video non attivo** quando lo stream è spento.

### 7.5 Registrazione video

Il controllo REC:

- richiede streaming e track LiveKit disponibili;
- usa icona rossa record/stop da 32 dp;
- mostra stato di avvio, timer, salvataggio;
- ha limite massimo 30 minuti;
- produce MP4 H.264 senza audio in `Movies/FindMe`;
- finalizza automaticamente lasciando tab, fermando stream o perdendo la
  sorgente;
- non deve lasciare file parziali visibili in galleria in caso di errore.

### 7.6 Tab Audio

La tab è verticalmente scrollabile con 96 dp di padding inferiore per rendere
sempre raggiungibili i controlli.

Contiene:

- switch **Streaming audio**, abilitato con dispositivo online e microfono
  disponibile;
- controllo **Registra audio**;
- visualizzatore animato del livello LiveKit;
- testo **Livello audio in tempo reale** oppure **Audio non attivo**;
- card **Messaggio vocale**.

La registrazione locale:

- richiede stream e track audio;
- salva AAC/M4A in `Music/FindMe`;
- usa timer e limite 30 minuti;
- si arresta e finalizza automaticamente nelle stesse condizioni del video.

### 7.7 Messaggio vocale asincrono

La card spiega che il messaggio verrà consegnato anche con trasmettitore
offline.

L’utente seleziona:

- **Basso**: 25% del volume media massimo;
- **Medio**: 60%;
- **Alto**: 100%.

Flusso UI:

1. stato inattivo: pulsante **Registra**;
2. registrazione: timer `trascorso / 01:00` e pulsante **Ferma**;
3. bozza pronta: durata e pulsanti **Annulla** / **Invia**;
4. invio: spinner e testo **Invio…**;
5. feedback testuale finale.

Vincoli:

- durata minima 1 secondo;
- durata massima 60 secondi;
- dimensione massima 2 MiB;
- formato AAC in contenitore M4A;
- volume non modificabile durante registrazione o invio;
- la bozza viene scartata lasciando il dettaglio.

Consegna:

- il file viene caricato in Storage privato;
- il comando persistente resta pendente se il trasmettitore è offline;
- alla riproduzione il trasmettitore sospende temporaneamente il proprio
  microfono per evitare eco;
- richiede audio focus, imposta il volume scelto e riproduce come parlato;
- al termine ripristina volume e stato microfono;
- elimina file temporaneo locale e oggetto Storage;
- aggiorna lo stato a `completed` o `failed`.

Dopo l’upload il ricevitore interroga lo stato ogni 2 secondi per un massimo di
45 tentativi (circa 90 secondi), mostrando attesa, download, riproduzione,
completamento o errore. Il comando rimane comunque persistente anche dopo la
fine di questo feedback UI.

### 7.8 Tab Schermo

Contiene:

- switch **Mirroring schermo** senza icona ridondante;
- controllo **Registra schermo**;
- anteprima verticale centrata;
- pulsante fullscreen nell’angolo dell’anteprima.

Lo switch è abilitato soltanto se:

- il trasmettitore è online;
- `screen_share_ready` è vero, quindi MediaProjection è autorizzata.

Stati visuali:

- pronto ma spento: **Mirroring non attivo**;
- non autorizzato: **Mirroring non autorizzato**;
- attivo: renderer video;
- non viene mostrata la vecchia riga “Riattiva il mirroring dall’app del
  trasmettitore”.

L’anteprima occupa il 60% della larghezza e usa rapporto `576/1280`, così una
sorgente portrait non viene tagliata o zoomata.

La modalità fullscreen:

- resta portrait e blocca la rotazione automatica;
- nasconde le barre di sistema;
- usa sfondo nero e scaling aspect-fit;
- mostra il pulsante di uscita in alto a destra;
- ripristina orientamento e barre all’uscita.

La registrazione schermo produce MP4 H.264 in `Movies/FindMe`, separato dalla
registrazione camera e con lo stesso limite di 30 minuti.

## 8. Storico posizioni

### 8.1 Apertura e query iniziale

La pagina si apre sul periodo delle ultime 24 ore e avvia subito la ricerca.
Il titolo è **Storico posizioni**, seguito dal nome del dispositivo.

I filtri sono inizialmente compressi. La testata mostra il preset selezionato o
l’intervallo sintetico.

### 8.2 Filtri

Preset:

- 6 ore;
- 24 ore;
- 7 giorni.

Intervallo personalizzato:

- campo **Da**;
- campo **A** della stessa larghezza;
- selezione data e poi ora/minuti;
- pulsante **Cerca** alto 48 dp, allineato alla riga **A**;
- spazio vuoto sopra il pulsante per mantenere l’allineamento richiesto.

Validazioni:

- inizio precedente alla fine;
- massimo 7 giorni per singola ricerca;
- dati disponibili soltanto negli ultimi 30 giorni.

Timestamp e dettagli dei punti includono i secondi nel formato
`dd/MM/yy HH:mm:ss`.

### 8.3 Percorso e timeline

Il backend restituisce al massimo 1.500 punti campionati, mantenendo primo e
ultimo. La mappa:

- mostra il percorso fino al punto selezionato, non sempre l’intera traccia;
- inizializza lo slider sull’ultimo punto;
- spostando lo slider verso l’inizio ridisegna progressivamente il percorso;
- evidenzia il punto selezionato;
- mostra `Percorso visualizzato: X di N punti`.

Sotto lo slider viene mostrata una card con timestamp, coordinate e accuratezza
del punto selezionato.

### 8.4 Elenco paginato

La sezione **Punti registrati** carica 50 righe alla volta, ordinate dal più
recente. Il punto coincidente con quello selezionato viene evidenziato. Se
esistono altre righe appare **Carica altri 50 punti**.

La schermata intera deve scorrere verticalmente.

### 8.5 Fullscreen storico

Il fullscreen:

- forza landscape e modalità immersiva;
- mostra la mappa a sinistra;
- mostra a destra, in un pannello scrollabile largo 310 dp, soltanto slider,
  conteggio e dettaglio del punto selezionato;
- non replica la lista completa dei punti;
- usa `‹`/controllo coerente per tornare al dettaglio;
- intercetta il tasto Android Back;
- nasconde anche la navigation bar, evitando che copra lo slider.

## 9. Configurazioni generali

La schermata è raggiunta dall’ingranaggio della home e usa una colonna
scrollabile.

Le modifiche sono salvate immediatamente e valgono per tutti i trasmettitori
associati.

Opzioni:

- **Frequenza offline**: 30, 60, 90, 120 secondi.
- **Frequenza online**: 5, 10, 15, 20 secondi.
- **Frequenza storico**: 1x, 2x, 3x.
- **Solo movimento**: salva soltanto dopo spostamento significativo.
- **Raggio avviso area**: 50, 100, 250, 500, 1000 metri.
- **Frequenza heartbeat**: 30, 60, 90, 120 secondi.
- **Frequenza controllo comandi**: 30, 60, 120, 300 secondi.

Ogni gruppo mostra titolo, descrizione e chip orizzontali scrollabili.

### 9.1 Cancellazione storico

In fondo appare il pulsante rosso **Cancella storico posizioni**.

Flusso:

1. dialog con tutti i trasmettitori associati e checkbox multiple;
2. **Continua** abilitato solo con almeno una selezione;
3. secondo dialog **Conferma cancellazione**;
4. riepilogo dei nomi selezionati e avviso di irreversibilità;
5. pulsante rosso **Cancella**;
6. indicatore di avanzamento e messaggio finale.

La cancellazione riguarda soltanto i trasmettitori selezionati e viene
autorizzata nuovamente dal backend.

## 10. Comportamento online, recovery e consumo

### 10.1 Definizione di online

**Online** significa che l’ultimo heartbeat è arrivato entro due intervalli
configurati. Non dimostra da solo che il WebSocket dei comandi o LiveKit siano
sani.

### 10.2 Media on-demand

LiveKit viene connesso soltanto se almeno uno fra camera, microfono e schermo è
richiesto. Spegnendo l’ultimo stream la stanza viene disconnessa.

Camera e schermo possono essere pubblicati contemporaneamente e sono distinti
per source.

### 10.3 Recupero comandi

Ogni comando è persistito nel database. Il trasmettitore:

- legge subito i pendenti;
- ascolta Realtime per bassa latenza;
- esegue anche polling REST configurabile;
- compatta gli arretrati mantenendo solo l’ultimo ON/OFF per ogni famiglia;
- non compatta cambio camera e messaggi vocali;
- marca ogni comando applicato.

Questa ridondanza risolve il caso in cui il dispositivo appare online ma il
listener Realtime è bloccato.

### 10.4 Recupero media

Un watchdog controlla lo stato ogni 5 secondi. Dopo tre controlli consecutivi
non validi ricostruisce il publisher LiveKit e riallinea lo stato desiderato.

### 10.5 Recupero rete e sessione

Il sistema:

- usa retry progressivo da 5 a 60 secondi;
- anticipa il retry quando Android segnala il ritorno della rete;
- usa timeout di 20 secondi;
- rinnova periodicamente sessione e canali;
- usa un solo client Supabase per processo;
- misura gli intervalli con un clock che include il deep sleep;
- mantiene una cache locale dell’ultima configurazione tracking.

### 10.6 Protezione energetica

Il trasmettitore mantiene un partial wake lock per heartbeat e timer di
recovery.

Il ricevitore acquisisce temporaneamente:

- `FLAG_KEEP_SCREEN_ON`;
- partial wake lock CPU;
- mantenimento della sessione media se il telefono si blocca.

La protezione è attiva soltanto durante stream o registrazioni. Passare
volontariamente a un’altra app può chiudere gli stream; il semplice blocco
schermo non deve interromperli.

## 11. Servizi esterni dal punto di vista funzionale

### 11.1 Supabase

Fornisce:

- identità anonime;
- database e associazioni;
- stato, posizione e storico;
- coda comandi persistente;
- Realtime;
- funzioni protette;
- Storage privato per messaggi vocali.

### 11.2 LiveKit

Fornisce soltanto trasporto realtime di:

- camera;
- microfono;
- schermo.

Non trasporta posizione, comandi o messaggi vocali asincroni.

### 11.3 MapLibre e OpenFreeMap

Mostrano mappe vettoriali senza API key o carta di credito. Lo stile previsto è
`https://tiles.openfreemap.org/styles/liberty`.

### 11.4 Firebase Cloud Messaging

Serve esclusivamente per notificare al ricevitore l’uscita dalla geofence,
anche con app chiusa. Non controlla camera, audio o schermo e non sostituisce
Supabase Realtime.

## 12. Provisioning e primo utilizzo operativo

Percorso raccomandato:

1. configurare Supabase, LiveKit e, se desiderate le geofence, Firebase;
2. installare e aprire il ricevitore;
3. annotare il codice di associazione;
4. generare APK release trasmettitore firmato;
5. pubblicarlo a URL HTTPS diretto;
6. generare QR con checksum, rete Wi-Fi e codice ricevitore;
7. eseguire factory reset del trasmettitore;
8. avviare il lettore QR dal Setup Wizard;
9. completare Device Owner;
10. aprire FindMe e rispondere alla domanda;
11. verificare permessi e nessuna restrizione batteria;
12. attivare **Monitoraggio attivo**;
13. concedere MediaProjection;
14. verificare comparsa del trasmettitore nella home del ricevitore.

Per sviluppo è possibile usare ADB per Device Owner, ma questo percorso non
trasporta gli admin extras: il pairing deve essere già predisposto o completato
separatamente.

## 13. Vincoli e casi limite da preservare

- La notifica foreground e gli indicatori privacy Android sono obbligatori.
- MediaProjection non può essere concessa “per sempre”, neppure dal Device
  Owner.
- Contenuti DRM, finestre `FLAG_SECURE` e alcune schermate di sistema possono
  risultare nere.
- Il mirroring non acquisisce l’audio interno; l’audio remoto resta lo stream
  microfono separato.
- Senza rete il telefono risulta inevitabilmente offline; deve però recuperare
  senza intervento appena torna la connettività.
- Firmware Xiaomi/HyperOS e altri OEM possono richiedere **Nessuna
  restrizione** e avvio automatico anche oltre la whitelist Android standard.
- Il Setup Wizard può variare fra produttori: il QR va validato sui modelli
  reali.
- Il bucket dei messaggi vocali deve restare privato.
- Le risposte alla domanda di sicurezza non devono essere salvate in chiaro.
- Uscendo dal dettaglio gli stream devono spegnersi; per questo la home non
  mostra etichette ridondanti “Video OFF/Audio OFF”.
- I renderer video devono sopravvivere ai cambi tab senza doppia
  inizializzazione o rilascio prematuro.
- La sorgente screen portrait deve essere visualizzata con aspect-fit, non
  center-crop.
- Le barre di sistema e l’orientamento devono essere sempre ripristinati
  uscendo dai fullscreen.

## 14. Scenari minimi di accettazione

### Scenario A — pairing QR

Dato un ricevitore configurato, quando un trasmettitore viene provisionato con
il suo codice, la prima schermata applicativa visibile deve essere la domanda
di sicurezza e, dopo risposta corretta, il trasmettitore deve comparire nel
ricevitore.

### Scenario B — più trasmettitori

Con due relazioni associate, la home deve mostrare due card indipendenti con
alias, stato e batteria; l’apertura di una card non deve mostrare dati o stream
dell’altra.

### Scenario C — recovery senza USB

Dopo perdita e ritorno della rete, il trasmettitore deve tornare online e
ricevere nuovi comandi senza stop/start manuale del monitoraggio.

### Scenario D — Realtime bloccato

Se il WebSocket comandi non consegna un evento ma il record è nel database, il
polling REST deve applicarlo entro l’intervallo configurato.

### Scenario E — media

Attivando uno stream deve aprirsi LiveKit; spegnendo l’ultimo deve chiudersi.
Camera, audio e schermo devono poter essere controllati indipendentemente.

### Scenario F — registrazioni

REC deve creare file riproducibili nelle raccolte corrette, chiuderli in modo
valido allo stop e interrompersi automaticamente al cambio tab o stop stream.

### Scenario G — messaggio vocale offline

Un messaggio inviato mentre il trasmettitore è offline deve restare pendente,
essere riprodotto al ritorno online con il volume selezionato e ripristinare
volume e microfono.

### Scenario H — geofence

L’attivazione deve fissare il centro corrente; la prima uscita deve produrre
una notifica, spegnere l’avviso e attivare tracking e storico rapidi
persistenti. Gli aggiornamenti successivi non devono creare nuovi eventi; un
fallimento FCM deve restare pendente fino alla consegna.

### Scenario I — blocco schermo ricevitore

Con stream o registrazione attivi, il blocco automatico/manuale del display non
deve interrompere media o file in registrazione.

### Scenario J — storico

La pagina deve aprirsi sull’ultimo punto, ridisegnare progressivamente il
percorso con lo slider, applicare filtri e paginazione e ripristinare
correttamente orientamento e barre dopo il fullscreen.

## 15. Mappa dei file funzionali

- `transmitter/.../MainActivity.kt`: access gate, dashboard e permessi.
- `transmitter/.../MonitoringService.kt`: comportamento del monitoraggio.
- `receiver/.../ReceiverHomeScreen.kt`: home e card dispositivi.
- `receiver/.../DeviceDetailScreen.kt`: testata e quattro tab.
- `receiver/.../TrackingSettingsScreen.kt`: configurazioni e cancellazione.
- `receiver/.../LocationHistoryScreen.kt`: filtri, timeline e lista.
- `receiver/.../PositionFullscreenScreen.kt`: fullscreen posizione.
- `receiver/.../HistoryFullscreenScreen.kt`: fullscreen storico.
- `receiver/.../DeviceMap.kt`, `HistoryMap.kt`: rendering mappe.
- `receiver/.../AudioVisualizer.kt`: onda audio.
- `receiver/.../SnapshotCaptureAnimation.kt`: animazione fotografia.
- `receiver/.../ReceiverActivity.kt`: orchestrazione di navigazione e media.
- `core/.../Models.kt`: stati condivisi e regole online.
- `core/.../EffectiveTrackingConfig.kt`: frequenze e movimento.
- `docs/EXTERNAL_SETUP.md`: setup operativo dei servizi.
- `docs/QR_PROVISIONING.md`: provisioning Device Owner.
