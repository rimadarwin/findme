create type public.device_role as enum ('receiver', 'transmitter');
create type public.device_command_type as enum (
  'start_audio',
  'stop_audio',
  'start_video',
  'stop_video',
  'start_monitoring',
  'stop_monitoring'
);

create table public.devices (
  id uuid primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  role public.device_role not null,
  created_at timestamptz not null default now(),
  unique (id, owner_id)
);

create table public.device_status (
  device_id uuid primary key references public.devices(id) on delete cascade,
  is_monitoring boolean not null default false,
  battery_percent integer check (battery_percent between 0 and 100),
  camera_available boolean not null default false,
  microphone_available boolean not null default false,
  last_heartbeat timestamptz not null default now()
);

create table public.device_locations (
  device_id uuid primary key references public.devices(id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  accuracy real check (accuracy is null or accuracy >= 0),
  recorded_at timestamptz not null default now()
);

create table public.location_history (
  id bigint generated always as identity primary key,
  device_id uuid not null references public.devices(id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  accuracy real check (accuracy is null or accuracy >= 0),
  recorded_at timestamptz not null default now()
);
create index location_history_device_time_idx
  on public.location_history (device_id, recorded_at desc);

create table public.device_commands (
  id bigint generated always as identity primary key,
  device_id uuid not null references public.devices(id) on delete cascade,
  command public.device_command_type not null,
  status text not null default 'pending' check (status in ('pending', 'applied', 'failed')),
  created_at timestamptz not null default now(),
  applied_at timestamptz
);
create index device_commands_pending_idx
  on public.device_commands (device_id, created_at)
  where status = 'pending';

alter table public.devices enable row level security;
alter table public.device_status enable row level security;
alter table public.device_locations enable row level security;
alter table public.location_history enable row level security;
alter table public.device_commands enable row level security;

create policy "owners manage devices" on public.devices
  for all using (owner_id = auth.uid())
  with check (owner_id = auth.uid());

create policy "owners read status" on public.device_status
  for select using (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  );
create policy "owned transmitters write status" on public.device_status
  for all using (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  ) with check (
    exists (
      select 1 from public.devices d
      where d.id = device_id and d.owner_id = auth.uid() and d.role = 'transmitter'
    )
  );

create policy "owners read current locations" on public.device_locations
  for select using (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  );
create policy "owned transmitters write current locations" on public.device_locations
  for all using (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  ) with check (
    exists (
      select 1 from public.devices d
      where d.id = device_id and d.owner_id = auth.uid() and d.role = 'transmitter'
    )
  );

create policy "owners read location history" on public.location_history
  for select using (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  );
create policy "owned transmitters append location history" on public.location_history
  for insert with check (
    exists (
      select 1 from public.devices d
      where d.id = device_id and d.owner_id = auth.uid() and d.role = 'transmitter'
    )
  );

create policy "owners read commands" on public.device_commands
  for select using (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  );
create policy "owners create commands" on public.device_commands
  for insert with check (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  );
create policy "owners update commands" on public.device_commands
  for update using (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  ) with check (
    exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
  );

alter publication supabase_realtime add table
  public.devices,
  public.device_status,
  public.device_locations,
  public.device_commands;

create or replace function public.delete_expired_findme_data()
returns void
language sql
security definer
set search_path = ''
as $$
  delete from public.location_history where recorded_at < now() - interval '30 days';
  delete from public.device_commands where created_at < now() - interval '7 days';
$$;

revoke all on function public.delete_expired_findme_data() from public, anon, authenticated;
