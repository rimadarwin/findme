/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Riduce il ritardo massimo predefinito nel recupero dei comandi remoti.
 * @modified 23.09.2026 - MDS | Aggiunto polling comandi a cinque secondi.
 */
alter table public.receivers
  drop constraint if exists receivers_command_poll_interval_sec_check;

alter table public.receivers
  alter column command_poll_interval_sec set default 5;

alter table public.receivers
  add constraint receivers_command_poll_interval_sec_check
  check (command_poll_interval_sec in (5, 15, 30, 60, 120, 300));

update public.receivers
set command_poll_interval_sec = 5
where command_poll_interval_sec = 60;
