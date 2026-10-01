alter table public.device_status
    add column if not exists camera_interrupted boolean not null default false;

comment on column public.device_status.camera_interrupted is
    'True when an active camera stream was interrupted by local camera use or a capture failure.';
