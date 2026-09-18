# Configurazione dei sistemi esterni

## 1. Supabase

### Database

Opzione CLI:

```powershell
npx supabase login
npx supabase link --project-ref IL_TUO_PROJECT_REF
npx supabase db push
```

In alternativa, copiare il contenuto di
`supabase/migrations/202609160001_findme.sql` nel **SQL Editor** ed eseguirlo.

Le migrazioni creano tabelle, anagrafica ricevitori, relazioni di pairing,
replica Realtime e policy RLS. La migrazione
`202609170002_tracking_performance.sql` aggiunge impostazioni adattive, lease
temporanei e la RPC protetta per il percorso storico; la successiva
`202609170003_tracking_policy_fix.sql` rende la lettura RLS non ricorsiva. Non
disabilitare RLS. La migrazione
`202609180001_delete_location_history.sql` abilita la cancellazione dello
storico soltanto per trasmettitori associati a un ricevitore posseduto
dall’utente autenticato.

### Identità automatica

In **Authentication > Providers > Anonymous Sign-Ins** abilitare gli accessi
anonimi. Le app creano e conservano automaticamente una sessione tecnica:
l'utente non deve inserire email o password.

Il ricevitore mostra un codice univoco di 10 caratteri. Inserendolo nel
trasmettitore si crea la relazione uno-a-molti in `receiver_transmitters`.

### Edge Function LiveKit

Creare prima il progetto LiveKit, poi:

```powershell
npx supabase secrets set `
  LIVEKIT_URL=wss://IL_TUO_PROGETTO.livekit.cloud `
  LIVEKIT_API_KEY=... `
  LIVEKIT_API_SECRET=...
npx supabase functions deploy livekit-token
npx supabase functions deploy pair-device
npx supabase functions deploy receiver-access
```

`SUPABASE_URL` e `SUPABASE_ANON_KEY` sono disponibili automaticamente nelle
Edge Functions ospitate. Le funzioni verificano il JWT anonimo, la proprietà del
dispositivo e la relazione di pairing prima di associare dispositivi o emettere
un token LiveKit.

### Domanda di accesso

Ogni ricevitore può avere `access_question` e `access_answer_hash`. La risposta
viene normalizzata e verificata tramite bcrypt lato database; non viene mai
salvata in chiaro né restituita alle app. La funzione `receiver-access` espone
soltanto la domanda e l'esito della verifica al trasmettitore associato.

### Retention

La funzione SQL `delete_expired_findme_data()` mantiene 30 giorni di posizioni e
7 giorni di comandi. Con Supabase Cron/pg_cron pianificarla una volta al giorno:

```sql
select cron.schedule(
  'findme-retention',
  '15 3 * * *',
  $$select public.delete_expired_findme_data()$$
);
```

## 2. LiveKit Cloud

1. Creare un progetto su LiveKit Cloud.
2. Copiare WebSocket URL, API key e API secret.
3. Mettere soltanto l'URL in `local.properties`.
4. Salvare key e secret nei Supabase secrets come indicato sopra.

Non occorre creare stanze manualmente: vengono create alla prima connessione con
nome `device-<uuid>`.

## 3. Mappe gratuite

Il ricevente usa MapLibre Native con le mappe vettoriali OpenFreeMap basate su
OpenStreetMap. Non servono account, carta di credito o API key.

Lo stile configurato è:

```text
https://tiles.openfreemap.org/styles/liberty
```

L'istanza pubblica è gratuita e include l'attribuzione richiesta. Non offre uno
SLA: per un futuro impiego critico sarà possibile ospitare OpenFreeMap
autonomamente senza modificare il database.

## 4. Device Owner sul trasmettitore

La modalità Device Owner consente all'app dedicata di concedere i permessi e
riavviare il foreground service dopo boot. Il telefono deve essere privo di
account, normalmente appena ripristinato.

Per sviluppo:

```powershell
adb install -r transmitter\build\outputs\apk\debug\transmitter-debug.apk
adb shell dpm set-device-owner `
  it.xcc.findme.transmitter/.FindMeDeviceAdminReceiver
```

Nel provisioning QR inserire il codice del ricevitore negli admin extras:

```json
{
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
    "findme_receiver_code": "CODICE_RICEVITORE"
  }
}
```

`FindMeDeviceAdminReceiver` salva il codice al completamento del provisioning e
l'app esegue il pairing senza mostrare una schermata preliminare. La prima
schermata visibile è quindi sempre la domanda di accesso.

Il comando ADB `dpm set-device-owner` non supporta admin extras: in sviluppo il
dispositivo deve essere già associato nel database oppure va usato il
provisioning completo. Build firmata, checksum, generazione locale del QR e
procedura sul telefono sono descritti in
[`QR_PROVISIONING.md`](QR_PROVISIONING.md).

Poi premere almeno una volta **Avvia monitoraggio**. Il flag viene conservato e
`BootReceiver` ripristina il servizio ai riavvii.

Per rimuovere il Device Owner durante lo sviluppo potrebbe essere necessario un
factory reset; verificare questa procedura sul dispositivo di test prima del
provisioning.

## 5. Impostazioni del telefono

- attivare lo switch **Nessuna restrizione batteria** in FindMe Trasmettitore;
- sui produttori che lo prevedono, abilitare l'avvio automatico;
- su Xiaomi/MIUI/HyperOS aprire anche le informazioni app e scegliere
  **Risparmio batteria > Nessuna restrizione**: la whitelist Android standard
  non disabilita sempre il gestore energetico proprietario;
- mantenere rete dati/Wi-Fi disponibile;
- concedere camera, microfono e posizione;
- concedere posizione “Sempre” se si vuole tracciare anche fuori dalla sessione.

Android continuerà a mostrare notifica foreground e indicatori camera/microfono.

## 6. Watchdog

Il trasmettitore usa l’heartbeat configurato dal ricevitore (30/60/90/120
secondi). Il ricevitore considera offline il dispositivo dopo due intervalli.
La resilienza è composta da:

- `START_STICKY` per la ricreazione del servizio;
- un solo client Supabase per processo, così attività, servizio e FCM non
  competono nella rotazione del refresh token;
- refresh esplicito della sessione e rinnovo dei canali ogni 15 minuti;
- timeout di 20 secondi per sessione, heartbeat e upload posizione;
- retry esponenziale da 5 a 60 secondi, anticipato quando Android segnala il
  ritorno della rete;
- watchdog che ricrea tutti i flussi se heartbeat, autenticazione o Realtime si
  bloccano;
- retry persistente anche sul ricevitore e messaggi tecnici rimossi dopo il
  recupero;
- connessione LiveKit solo quando audio, video o schermo sono richiesti;
- cache locale delle ultime impostazioni tracking valide;
- lease rapido di 90 secondi, rinnovato ogni 20 secondi e con fallback offline;
- `BootReceiver` quando l'app è Device Owner;
- stato online/offline derivato dal timestamp Supabase.

Una perdita reale di Internet rende inevitabilmente il dispositivo
temporaneamente offline. Al ripristino della rete non è necessario riaprire
l’app né disattivare e riattivare il monitoraggio.

## 7. Mirroring schermo

Il primo setup del trasmettitore mostra la conferma Android MediaProjection.
La cattura resta pronta nel foreground service, ma non viene pubblicata e non
consuma participant-minutes LiveKit finché **Schermo** è OFF sul ricevitore.

MediaProjection non sopravvive a reboot, aggiornamento APK, arresto del processo
o revoca dalla notifica di sistema. In questi casi la notifica FindMe apre la
dashboard, dove **Riattiva** ripresenta il consenso. Contenuti DRM, finestre con
`FLAG_SECURE` e alcune schermate di sistema possono apparire nere. L’audio
interno non è acquisito.

FCM non è necessario per controllare camera e microfono sui telefoni Device
Owner e non può aggirare i vincoli Android sui telefoni standard. È invece
usato per consegnare al ricevitore gli avvisi area descritti di seguito.

## 8. Firebase Cloud Messaging per gli avvisi area

Gli avvisi di uscita area usano FCM e arrivano al ricevitore anche quando l’app
non è aperta. Firebase Cloud Messaging non richiede un piano a pagamento.

1. Creare un progetto nella Firebase Console.
2. Aggiungere un’app Android con package `it.xcc.findme.receiver`.
3. Scaricare `google-services.json` e copiarlo in `receiver/google-services.json`.
   Il file è escluso da Git; senza di esso l’app compila, ma registra nei log che
   le notifiche remote non sono configurate.
4. Abilitare la Firebase Cloud Messaging API.
5. In **Impostazioni progetto > Account di servizio**, generare una chiave JSON
   e salvarla temporaneamente come `firebase-service-account.json` fuori dal
   repository oppure nella root (il nome è escluso da Git).
6. Salvare il JSON compresso nei secrets Supabase e distribuire la funzione:

```powershell
$firebase = Get-Content .\firebase-service-account.json -Raw |
  ConvertFrom-Json |
  ConvertTo-Json -Compress
npx supabase secrets set "FIREBASE_SERVICE_ACCOUNT_JSON=$firebase"
npx supabase functions deploy geofence-alert
```

Non inserire mai il service account nell’app, in `local.properties` o in Git.
Dopo aver aggiunto `google-services.json`, ricostruire e reinstallare il
ricevitore, aprirlo una volta e concedere il permesso notifiche. L’identificativo
di installazione FCM viene registrato automaticamente in
`receiver_push_tokens`.

Il trasmettitore invoca `geofence-alert` solo se l’avviso area è attivo. La RPC
atomica `evaluate_geofence` notifica una sola transizione interno→esterno; un
rientro nell’area riarma l’avviso.
