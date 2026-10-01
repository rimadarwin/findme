/**
 * @author Infinity
 * @description Crea atomicamente messaggio testuale e comando persistente.
 * @modified 29.09.2026 - MDS | Aggiunta RPC autorizzata per il ricevitore associato.
 */
alter table public.device_commands
  add constraint device_commands_text_message_payload check (
    (command = 'show_text_message' and text_message_id is not null)
    or (command <> 'show_text_message' and text_message_id is null)
  );

create function public.create_text_message(
  target_receiver_id uuid,
  target_transmitter_id uuid,
  target_message_id uuid,
  requested_body text
)
returns setof public.text_messages
language plpgsql
security definer
set search_path = ''
as $$
declare
  normalized_body text := btrim(requested_body);
begin
  if normalized_body is null
    or char_length(normalized_body) not between 1 and 500
  then
    raise exception 'Invalid text message body';
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

  insert into public.text_messages (
    id,
    receiver_id,
    transmitter_id,
    body
  )
  values (
    target_message_id,
    target_receiver_id,
    target_transmitter_id,
    normalized_body
  );

  insert into public.device_commands (
    device_id,
    command,
    text_message_id
  )
  values (
    target_transmitter_id,
    'show_text_message',
    target_message_id
  );

  return query
  select tm.*
  from public.text_messages tm
  where tm.id = target_message_id;
end;
$$;

revoke all on function public.create_text_message(uuid, uuid, uuid, text)
  from public, anon;
grant execute on function public.create_text_message(uuid, uuid, uuid, text)
  to authenticated;

comment on function public.create_text_message(uuid, uuid, uuid, text)
  is 'Crea messaggio testuale e comando remoto nella stessa transazione.';

create or replace function public.delete_expired_findme_data()
returns void
language sql
security definer
set search_path = ''
as $$
  delete from public.location_history
  where recorded_at < now() - interval '30 days';

  delete from public.device_commands
  where created_at < now() - interval '7 days'
    and status <> 'pending';

  delete from public.text_messages
  where created_at < now() - interval '30 days'
    and status in ('dismissed', 'failed');

  update public.receiver_transmitters
  set live_tracking_until = null,
      live_history = case
        when live_tracking_persistent then live_history
        else false
      end
  where live_tracking_until is not null
    and live_tracking_until < now();
$$;
