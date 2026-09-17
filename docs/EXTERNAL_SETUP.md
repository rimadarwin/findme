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
replica Realtime e policy RLS. Non disabilitare RLS.

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
dispositivo deve essere già associato nel database oppure va usato un QR di
provisioning completo. Poi premere almeno una volta **Avvia monitoraggio**.
Il flag viene conservato e `BootReceiver` ripristina il servizio ai riavvii.

Per rimuovere il Device Owner durante lo sviluppo potrebbe essere necessario un
factory reset; verificare questa procedura sul dispositivo di test prima del
provisioning.

## 5. Impostazioni del telefono

- disabilitare ottimizzazione batteria per FindMe Trasmettitore;
- sui produttori che lo prevedono, abilitare l'avvio automatico;
- mantenere rete dati/Wi-Fi disponibile;
- concedere camera, microfono e posizione;
- concedere posizione “Sempre” se si vuole tracciare anche fuori dalla sessione.

Android continuerà a mostrare notifica foreground e indicatori camera/microfono.

## 6. Watchdog

Il trasmettitore invia un heartbeat ogni 30 secondi. Il ricevente considera
offline un dispositivo dopo 120 secondi. La resilienza è composta da:

- `START_STICKY` per la ricreazione del servizio;
- riconnessione LiveKit/Supabase ogni 5 secondi;
- `BootReceiver` quando l'app è Device Owner;
- stato online/offline derivato dal timestamp Supabase.

FCM non è necessario sui telefoni Device Owner. Se si decide di supportare
telefoni standard completamente chiusi, FCM può solo invitare l'utente ad aprire
l'app: non può aggirare i vincoli Android su camera e microfono.
