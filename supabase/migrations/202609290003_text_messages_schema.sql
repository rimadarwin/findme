/**
 * @author Infinity
 * @description Aggiunge messaggi testuali persistenti e relativo comando.
 * @modified 29.09.2026 - MDS | Creati enum, tabella, RLS e payload comando.
 */
alter type public.device_command_type
  add value if not exists 'show_text_message';

create type public.text_message_status as enum (
  'pending',
  'waiting_permission',
  'displaying',
  'dismissed',
  'failed'
);

create table public.text_messages (
  id uuid primary key,
  receiver_id uuid not null references public.receivers(device_id) on delete cascade,
  transmitter_id uuid not null references public.devices(id) on delete cascade,
  body text not null check (
    char_length(btrim(body)) between 1 and 500
    and body = btrim(body)
  ),
  status public.text_message_status not null default 'pending',
  error_message text check (
    error_message is null or char_length(error_message) <= 300
  ),
  created_at timestamptz not null default now(),
  displayed_at timestamptz,
  dismissed_at timestamptz
);

create index text_messages_transmitter_created_idx
  on public.text_messages (transmitter_id, created_at);

alter table public.device_commands
  add column text_message_id uuid
    references public.text_messages(id) on delete cascade;

alter table public.text_messages enable row level security;

create policy "paired devices read text messages"
on public.text_messages
for select
using (
  exists (
    select 1
    from public.receivers r
    where r.device_id = receiver_id
      and r.owner_id = auth.uid()
  )
  or exists (
    select 1
    from public.devices d
    where d.id = transmitter_id
      and d.owner_id = auth.uid()
      and d.role = 'transmitter'
  )
);

create policy "transmitters update text message delivery"
on public.text_messages
for update
using (
  exists (
    select 1
    from public.devices d
    where d.id = transmitter_id
      and d.owner_id = auth.uid()
      and d.role = 'transmitter'
  )
)
with check (
  exists (
    select 1
    from public.devices d
    where d.id = transmitter_id
      and d.owner_id = auth.uid()
      and d.role = 'transmitter'
  )
);

grant select, update on public.text_messages to authenticated;
