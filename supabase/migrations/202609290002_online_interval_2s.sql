/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Abilita l'intervallo online di due secondi.
 * @modified 29.09.2026 - MDS | Esteso il vincolo delle frequenze rapide.
 */
alter table public.receivers
  drop constraint receivers_online_location_interval_sec_check;

alter table public.receivers
  add constraint receivers_online_location_interval_sec_check
  check (online_location_interval_sec in (2, 5, 10, 15, 20));
