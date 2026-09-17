alter table public.receivers
  add column geofence_radius_m integer not null default 100
    check (geofence_radius_m in (50, 100, 250, 500, 1000));

alter table public.receiver_transmitters
  add column geofence_enabled boolean not null default false,
  add column geofence_center_latitude double precision
    check (geofence_center_latitude is null or geofence_center_latitude between -90 and 90),
  add column geofence_center_longitude double precision
    check (geofence_center_longitude is null or geofence_center_longitude between -180 and 180),
  add column geofence_radius_m integer
    check (geofence_radius_m is null or geofence_radius_m in (50, 100, 250, 500, 1000)),
  add column geofence_is_outside boolean not null default false,
  add column geofence_updated_at timestamptz,
  add constraint receiver_transmitters_geofence_complete check (
    not geofence_enabled
    or (
      geofence_center_latitude is not null
      and geofence_center_longitude is not null
      and geofence_radius_m is not null
    )
  );

create table public.receiver_push_tokens (
  token text primary key check (char_length(token) between 20 and 4096),
  receiver_id uuid not null references public.receivers(device_id) on delete cascade,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index receiver_push_tokens_receiver_idx
  on public.receiver_push_tokens (receiver_id);

alter table public.receiver_push_tokens enable row level security;

create policy "receiver owners manage push tokens"
on public.receiver_push_tokens
for all
using (
  exists (
    select 1
    from public.receivers r
    where r.device_id = receiver_id
      and r.owner_id = auth.uid()
  )
)
with check (
  exists (
    select 1
    from public.receivers r
    where r.device_id = receiver_id
      and r.owner_id = auth.uid()
  )
);

grant select, insert, update, delete on public.receiver_push_tokens to authenticated;

create or replace function public.evaluate_geofence(
  target_device_id uuid,
  current_latitude double precision,
  current_longitude double precision
)
returns table (
  receiver_id uuid,
  should_notify boolean,
  distance_m double precision,
  radius_m integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  relationship public.receiver_transmitters%rowtype;
  calculated_distance double precision;
  currently_outside boolean;
begin
  if not exists (
    select 1
    from public.devices d
    where d.id = target_device_id
      and d.owner_id = auth.uid()
      and d.role = 'transmitter'
  ) then
    raise exception 'Transmitter access denied';
  end if;

  select rt.*
  into relationship
  from public.receiver_transmitters rt
  where rt.transmitter_id = target_device_id
  for update;

  if not found or not relationship.geofence_enabled then
    return;
  end if;

  calculated_distance := 6371000.0 * 2.0 * asin(
    least(
      1.0,
      sqrt(
        power(
          sin(radians(current_latitude - relationship.geofence_center_latitude) / 2.0),
          2
        )
        + cos(radians(relationship.geofence_center_latitude))
        * cos(radians(current_latitude))
        * power(
          sin(radians(current_longitude - relationship.geofence_center_longitude) / 2.0),
          2
        )
      )
    )
  );
  currently_outside := calculated_distance > relationship.geofence_radius_m;

  update public.receiver_transmitters
  set geofence_is_outside = currently_outside,
      geofence_updated_at = now()
  where transmitter_id = target_device_id
    and geofence_is_outside is distinct from currently_outside;

  return query
  select
    relationship.receiver_id,
    currently_outside and not relationship.geofence_is_outside,
    calculated_distance,
    relationship.geofence_radius_m;
end;
$$;

revoke all on function public.evaluate_geofence(
  uuid,
  double precision,
  double precision
) from public, anon;
grant execute on function public.evaluate_geofence(
  uuid,
  double precision,
  double precision
) to authenticated;
