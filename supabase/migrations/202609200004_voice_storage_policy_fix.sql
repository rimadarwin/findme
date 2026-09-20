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

drop policy if exists "receivers upload voice messages" on storage.objects;
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

drop policy if exists "paired devices download voice messages" on storage.objects;
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

drop policy if exists "paired devices delete voice messages" on storage.objects;
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
