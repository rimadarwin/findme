/**
 * @author Infinity
 * @description Estende i raggi area supportati con i valori 10 e 25 metri.
 * @modified 01.10.2026 - Infinity | Aggiornati i vincoli receiver e relazione.
 */
alter table public.receivers
  drop constraint if exists receivers_geofence_radius_m_check;

alter table public.receivers
  add constraint receivers_geofence_radius_m_check
  check (geofence_radius_m in (10, 25, 50, 100, 250, 500, 1000));

alter table public.receiver_transmitters
  drop constraint if exists receiver_transmitters_geofence_radius_m_check;

alter table public.receiver_transmitters
  add constraint receiver_transmitters_geofence_radius_m_check
  check (
    geofence_radius_m is null
    or geofence_radius_m in (10, 25, 50, 100, 250, 500, 1000)
  );
