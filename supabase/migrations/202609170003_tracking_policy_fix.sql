create or replace function public.can_read_receiver_tracking(target_receiver_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.receiver_transmitters rt
    join public.devices d on d.id = rt.transmitter_id
    where rt.receiver_id = target_receiver_id
      and d.owner_id = auth.uid()
      and d.role = 'transmitter'
  );
$$;

revoke all on function public.can_read_receiver_tracking(uuid) from public, anon;
grant execute on function public.can_read_receiver_tracking(uuid) to authenticated;

drop policy if exists "paired transmitters read receiver tracking settings"
  on public.receivers;

create policy "paired transmitters read receiver tracking settings"
on public.receivers
for select
using (public.can_read_receiver_tracking(device_id));

create or replace function public.get_location_route(
  target_device_id uuid,
  from_time timestamptz,
  to_time timestamptz,
  max_points integer default 1500
)
returns table (
  id bigint,
  device_id uuid,
  latitude double precision,
  longitude double precision,
  accuracy real,
  recorded_at timestamptz
)
language sql
stable
security definer
set search_path = ''
as $$
  with numbered as (
    select
      lh.id,
      lh.device_id,
      lh.latitude,
      lh.longitude,
      lh.accuracy,
      lh.recorded_at,
      row_number() over (order by lh.recorded_at) as row_number,
      count(*) over () as total
    from public.location_history lh
    where lh.device_id = target_device_id
      and lh.recorded_at >= from_time
      and lh.recorded_at <= to_time
      and public.can_access_transmitter(target_device_id)
  ),
  sampled as (
    select *,
      greatest(
        ceil(
          greatest(total - 1, 1)::numeric /
          greatest(greatest(2, least(max_points, 1500)) - 1, 1)
        )::bigint,
        1
      ) as stride
    from numbered
  )
  select
    sampled.id,
    sampled.device_id,
    sampled.latitude,
    sampled.longitude,
    sampled.accuracy,
    sampled.recorded_at
  from sampled
  where row_number = 1
     or row_number = total
     or mod(row_number - 1, stride) = 0
  order by recorded_at
  limit greatest(2, least(max_points, 1500));
$$;
