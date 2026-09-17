alter type public.device_command_type add value if not exists 'switch_camera';

alter table public.device_status
  add column if not exists camera_streaming boolean not null default false,
  add column if not exists microphone_streaming boolean not null default false,
  add column if not exists camera_facing text not null default 'front'
    check (camera_facing in ('front', 'back'));
