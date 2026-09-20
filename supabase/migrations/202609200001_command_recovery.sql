alter table public.receivers
  add column if not exists command_poll_interval_sec integer not null default 60
    check (command_poll_interval_sec in (30, 60, 120, 300));
