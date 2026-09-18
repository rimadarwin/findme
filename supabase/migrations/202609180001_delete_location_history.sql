create or replace function public.delete_receiver_location_history(
  target_receiver_id uuid,
  target_transmitter_ids uuid[]
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null or not exists (
    select 1
    from public.receivers r
    where r.device_id = target_receiver_id
      and r.owner_id = auth.uid()
  ) then
    raise insufficient_privilege
      using message = 'Receiver ownership verification failed';
  end if;

  if target_transmitter_ids is null
    or cardinality(target_transmitter_ids) = 0
  then
    raise invalid_parameter_value
      using message = 'At least one transmitter is required';
  end if;

  if exists (
    select 1
    from unnest(target_transmitter_ids) as selected(transmitter_id)
    where not exists (
      select 1
      from public.receiver_transmitters rt
      where rt.receiver_id = target_receiver_id
        and rt.transmitter_id = selected.transmitter_id
    )
  ) then
    raise insufficient_privilege
      using message = 'One or more transmitters are not associated with this receiver';
  end if;

  delete from public.location_history lh
  where lh.device_id = any(target_transmitter_ids);
end;
$$;

revoke all on function public.delete_receiver_location_history(uuid, uuid[])
  from public, anon;
grant execute on function public.delete_receiver_location_history(uuid, uuid[])
  to authenticated;
