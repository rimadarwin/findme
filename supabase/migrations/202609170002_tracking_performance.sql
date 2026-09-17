alter table public.receivers
  add column offline_location_interval_sec integer not null default 60
    check (offline_location_interval_sec in (30, 60, 90, 120)),
  add column online_location_interval_sec integer not null default 10
    check (online_location_interval_sec in (5, 10, 15, 20)),
  add column history_multiplier smallint not null default 2
    check (history_multiplier in (1, 2, 3)),
  add column only_movement boolean not null default true,
  add column heartbeat_interval_sec integer not null default 60
    check (heartbeat_interval_sec in (30, 60, 90, 120));

alter table public.receiver_transmitters
  add column live_tracking_until timestamptz,
  add column live_history boolean not null default false;

create index receiver_transmitters_live_tracking_idx
  on public.receiver_transmitters (transmitter_id, live_tracking_until)
  where live_tracking_until is not null;

create policy "paired transmitters read receiver tracking settings"
on public.receivers
for select
using (
  exists (
    select 1
    from public.receiver_transmitters rt
    join public.devices d on d.id = rt.transmitter_id
    where rt.receiver_id = receivers.device_id
      and d.owner_id = auth.uid()
      and d.role = 'transmitter'
  )
);

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

revoke all on function public.get_location_route(
  uuid,
  timestamptz,
  timestamptz,
  integer
) from public, anon;
grant execute on function public.get_location_route(
  uuid,
  timestamptz,
  timestamptz,
  integer
) to authenticated;

create or replace function public.delete_expired_findme_data()
returns void
language sql
security definer
set search_path = ''
as $$
  delete from public.location_history
  where recorded_at < now() - interval '30 days';

  delete from public.device_commands
  where created_at < now() - interval '7 days';

  update public.receiver_transmitters
  set live_tracking_until = null,
      live_history = false
  where live_tracking_until is not null
    and live_tracking_until < now();
$$;
