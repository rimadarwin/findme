/**
 * @author Infinity
 * @description Configura provider LiveKit dedicati per singolo ricevitore.
 * @modified 24.09.2026 - MDS | Aggiunta configurazione multi-tenant con riferimenti agli Edge Secrets.
 */
create table public.receiver_service_configs (
  receiver_id uuid primary key
    references public.receivers(device_id) on delete cascade,
  livekit_url text not null
    check (livekit_url ~ '^wss://[^[:space:]]+$'),
  livekit_api_key_secret_name text not null
    check (livekit_api_key_secret_name ~ '^[A-Z][A-Z0-9_]{2,127}$'),
  livekit_api_secret_secret_name text not null
    check (livekit_api_secret_secret_name ~ '^[A-Z][A-Z0-9_]{2,127}$'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (livekit_api_key_secret_name <> livekit_api_secret_secret_name)
);

comment on table public.receiver_service_configs is
  'Override LiveKit per ricevitore; contiene solo nomi di Edge Secrets, mai credenziali.';

alter table public.receiver_service_configs enable row level security;

revoke all on table public.receiver_service_configs from anon, authenticated;
