alter type public.device_command_type
  add value if not exists 'play_voice_message';

create type public.voice_message_volume as enum ('low', 'medium', 'high');
create type public.voice_message_status as enum (
  'pending',
  'downloading',
  'playing',
  'completed',
  'failed'
);

create table public.voice_messages (
  id uuid primary key,
  receiver_id uuid not null references public.receivers(device_id) on delete cascade,
  transmitter_id uuid not null references public.devices(id) on delete cascade,
  storage_path text not null unique,
  volume public.voice_message_volume not null,
  duration_ms integer not null check (duration_ms between 1000 and 60000),
  status public.voice_message_status not null default 'pending',
  error_message text check (error_message is null or char_length(error_message) <= 300),
  created_at timestamptz not null default now(),
  started_at timestamptz,
  completed_at timestamptz
);

create index voice_messages_transmitter_created_idx
  on public.voice_messages (transmitter_id, created_at desc);

alter table public.device_commands
  add column voice_message_id uuid references public.voice_messages(id) on delete cascade;

alter table public.voice_messages enable row level security;

create policy "paired devices read voice messages"
on public.voice_messages
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

create policy "transmitters update voice message delivery"
on public.voice_messages
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

grant select, update on public.voice_messages to authenticated;

create or replace function public.can_upload_voice_message_object(
  receiver_folder text,
  transmitter_folder text
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.receivers r
    join public.receiver_transmitters rt
      on rt.receiver_id = r.device_id
    where r.device_id::text = receiver_folder
      and rt.transmitter_id::text = transmitter_folder
      and r.owner_id = auth.uid()
  );
$$;

create or replace function public.can_access_voice_message_object(
  receiver_folder text,
  transmitter_folder text
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select
    exists (
      select 1
      from public.receivers r
      where r.device_id::text = receiver_folder
        and r.owner_id = auth.uid()
    )
    or exists (
      select 1
      from public.devices d
      join public.receiver_transmitters rt
        on rt.transmitter_id = d.id
      where d.id::text = transmitter_folder
        and rt.receiver_id::text = receiver_folder
        and d.owner_id = auth.uid()
        and d.role = 'transmitter'
    );
$$;

revoke all on function public.can_upload_voice_message_object(text, text)
  from public, anon;
revoke all on function public.can_access_voice_message_object(text, text)
  from public, anon;
grant execute on function public.can_upload_voice_message_object(text, text)
  to authenticated;
grant execute on function public.can_access_voice_message_object(text, text)
  to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'voice-messages',
  'voice-messages',
  false,
  2097152,
  array['audio/mp4']
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

create policy "receivers upload voice messages"
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'voice-messages'
  and public.can_upload_voice_message_object(
    (storage.foldername(name))[1],
    (storage.foldername(name))[2]
  )
);

create policy "paired devices download voice messages"
on storage.objects
for select
to authenticated
using (
  bucket_id = 'voice-messages'
  and public.can_access_voice_message_object(
    (storage.foldername(name))[1],
    (storage.foldername(name))[2]
  )
);

create policy "paired devices delete voice messages"
on storage.objects
for delete
to authenticated
using (
  bucket_id = 'voice-messages'
  and public.can_access_voice_message_object(
    (storage.foldername(name))[1],
    (storage.foldername(name))[2]
  )
);
