/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Rende atomica l'uscita area e mantiene tracking rapido e notifica affidabili.
 * @modified 29.09.2026 - MDS | Aggiunti tracking persistente e coda retry FCM sulla relazione.
 */
alter table public.receiver_transmitters
  add column live_tracking_persistent boolean not null default false,
  add column geofence_notification_pending boolean not null default false,
  add column geofence_notification_distance_m double precision
    check (
      geofence_notification_distance_m is null
      or geofence_notification_distance_m >= 0
    ),
  add column geofence_notification_radius_m integer
    check (
      geofence_notification_radius_m is null
      or geofence_notification_radius_m in (50, 100, 250, 500, 1000)
    ),
  add column geofence_notification_attempts integer not null default 0
    check (geofence_notification_attempts >= 0),
  add column geofence_notification_next_attempt_at timestamptz,
  add column geofence_notification_created_at timestamptz,
  add column geofence_notification_last_error text
    check (
      geofence_notification_last_error is null
      or char_length(geofence_notification_last_error) <= 2000
    ),
  add constraint receiver_transmitters_geofence_notification_complete check (
    not geofence_notification_pending
    or (
      geofence_notification_distance_m is not null
      and geofence_notification_radius_m is not null
      and geofence_notification_created_at is not null
    )
  );

create index receiver_transmitters_geofence_notification_retry_idx
  on public.receiver_transmitters (
    transmitter_id,
    geofence_notification_next_attempt_at
  )
  where geofence_notification_pending;

drop function public.evaluate_geofence(uuid, double precision, double precision);

create function public.evaluate_geofence(
  target_device_id uuid,
  current_latitude double precision,
  current_longitude double precision
)
returns table (
  receiver_id uuid,
  should_notify boolean,
  distance_m double precision,
  radius_m integer,
  notification_attempt integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  relationship public.receiver_transmitters%rowtype;
  calculated_distance double precision;
  currently_outside boolean;
  claimed_attempt integer;
  retry_delay_seconds integer;
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

  if not found then
    return;
  end if;

  if relationship.geofence_notification_pending then
    if relationship.geofence_notification_next_attempt_at is not null
      and relationship.geofence_notification_next_attempt_at > now()
    then
      return;
    end if;

    claimed_attempt := relationship.geofence_notification_attempts + 1;
    retry_delay_seconds := case
      when claimed_attempt <= 1 then 15
      when claimed_attempt = 2 then 30
      when claimed_attempt = 3 then 60
      when claimed_attempt = 4 then 120
      else 300
    end;

    update public.receiver_transmitters
    set geofence_notification_attempts = claimed_attempt,
        geofence_notification_next_attempt_at =
          now() + make_interval(secs => retry_delay_seconds)
    where transmitter_id = target_device_id;

    return query
    select
      relationship.receiver_id,
      true,
      relationship.geofence_notification_distance_m,
      relationship.geofence_notification_radius_m,
      claimed_attempt;
    return;
  end if;

  if not relationship.geofence_enabled then
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

  if currently_outside and not relationship.geofence_is_outside then
    update public.receiver_transmitters
    set geofence_enabled = false,
        geofence_center_latitude = null,
        geofence_center_longitude = null,
        geofence_radius_m = null,
        geofence_is_outside = false,
        geofence_updated_at = now(),
        live_tracking_until = null,
        live_tracking_persistent = true,
        live_history = true,
        geofence_notification_pending = true,
        geofence_notification_distance_m = calculated_distance,
        geofence_notification_radius_m = relationship.geofence_radius_m,
        geofence_notification_attempts = 1,
        geofence_notification_next_attempt_at = now() + interval '15 seconds',
        geofence_notification_created_at = now(),
        geofence_notification_last_error = null
    where transmitter_id = target_device_id;

    return query
    select
      relationship.receiver_id,
      true,
      calculated_distance,
      relationship.geofence_radius_m,
      1;
    return;
  end if;

  update public.receiver_transmitters
  set geofence_is_outside = currently_outside,
      geofence_updated_at = now()
  where transmitter_id = target_device_id
    and geofence_is_outside is distinct from currently_outside;

  return query
  select
    relationship.receiver_id,
    false,
    calculated_distance,
    relationship.geofence_radius_m,
    0;
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
comment on function public.evaluate_geofence(
  uuid,
  double precision,
  double precision
) is 'Valuta atomicamente uscita area e claim dei retry FCM.';

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
      live_history = case
        when live_tracking_persistent then live_history
        else false
      end
  where live_tracking_until is not null
    and live_tracking_until < now();
$$;
comment on function public.delete_expired_findme_data()
  is 'Applica retention e scadenza lease preservando il tracking persistente.';
