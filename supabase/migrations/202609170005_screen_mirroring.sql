alter type public.device_command_type add value if not exists 'start_screen';
alter type public.device_command_type add value if not exists 'stop_screen';

alter table public.device_status
  add column if not exists screen_share_ready boolean not null default false,
  add column if not exists screen_streaming boolean not null default false;
