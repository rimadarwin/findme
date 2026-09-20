alter table public.device_commands
  add constraint device_commands_voice_message_payload check (
    (command = 'play_voice_message' and voice_message_id is not null)
    or (command <> 'play_voice_message' and voice_message_id is null)
  );

create or replace function public.create_voice_message(
  target_receiver_id uuid,
  target_transmitter_id uuid,
  target_message_id uuid,
  requested_volume public.voice_message_volume,
  requested_duration_ms integer
)
returns setof public.voice_messages
language plpgsql
security definer
set search_path = ''
as $$
declare
  expected_path text :=
    target_receiver_id::text || '/' ||
    target_transmitter_id::text || '/' ||
    target_message_id::text || '.m4a';
begin
  if requested_duration_ms not between 1000 and 60000 then
    raise exception 'Invalid voice message duration';
  end if;

  if not exists (
    select 1
    from public.receivers r
    where r.device_id = target_receiver_id
      and r.owner_id = auth.uid()
  ) then
    raise exception 'Receiver access denied';
  end if;

  if not exists (
    select 1
    from public.receiver_transmitters rt
    where rt.receiver_id = target_receiver_id
      and rt.transmitter_id = target_transmitter_id
  ) then
    raise exception 'Transmitter is not paired';
  end if;

  if not exists (
    select 1
    from storage.objects o
    where o.bucket_id = 'voice-messages'
      and o.name = expected_path
  ) then
    raise exception 'Voice message file not found';
  end if;

  insert into public.voice_messages (
    id,
    receiver_id,
    transmitter_id,
    storage_path,
    volume,
    duration_ms
  )
  values (
    target_message_id,
    target_receiver_id,
    target_transmitter_id,
    expected_path,
    requested_volume,
    requested_duration_ms
  );

  insert into public.device_commands (
    device_id,
    command,
    voice_message_id
  )
  values (
    target_transmitter_id,
    'play_voice_message',
    target_message_id
  );

  return query
  select vm.*
  from public.voice_messages vm
  where vm.id = target_message_id;
end;
$$;

revoke all on function public.create_voice_message(
  uuid,
  uuid,
  uuid,
  public.voice_message_volume,
  integer
) from public, anon;

grant execute on function public.create_voice_message(
  uuid,
  uuid,
  uuid,
  public.voice_message_volume,
  integer
) to authenticated;
