create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

create table public.receivers (
  device_id uuid primary key references public.devices(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  pairing_code text not null unique,
  created_at timestamptz not null default now()
);

create or replace function public.assign_receiver_pairing_code()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.pairing_code is null or trim(new.pairing_code) = '' then
    new.pairing_code := upper(substr(encode(extensions.gen_random_bytes(6), 'hex'), 1, 10));
  end if;
  return new;
end;
$$;

create trigger receivers_pairing_code
before insert on public.receivers
for each row execute function public.assign_receiver_pairing_code();

alter table public.receivers alter column pairing_code drop not null;

insert into public.receivers (device_id, owner_id, name)
select id, owner_id, name
from public.devices
where role = 'receiver'
on conflict (device_id) do nothing;

alter table public.receivers alter column pairing_code set not null;

create table public.receiver_transmitters (
  receiver_id uuid not null references public.receivers(device_id) on delete cascade,
  transmitter_id uuid not null references public.devices(id) on delete cascade,
  paired_at timestamptz not null default now(),
  primary key (receiver_id, transmitter_id)
);
create index receiver_transmitters_transmitter_idx
  on public.receiver_transmitters (transmitter_id);

insert into public.receiver_transmitters (receiver_id, transmitter_id)
select r.device_id, t.id
from public.receivers r
join public.devices t
  on t.owner_id = r.owner_id
 and t.role = 'transmitter'
on conflict do nothing;

alter table public.receivers enable row level security;
alter table public.receiver_transmitters enable row level security;

create or replace function public.can_access_transmitter(target_device_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.devices d
    where d.id = target_device_id
      and d.role = 'transmitter'
      and (
        d.owner_id = auth.uid()
        or exists (
          select 1
          from public.receiver_transmitters rt
          join public.receivers r on r.device_id = rt.receiver_id
          where rt.transmitter_id = d.id
            and r.owner_id = auth.uid()
        )
      )
  );
$$;

revoke all on function public.can_access_transmitter(uuid) from public;
grant execute on function public.can_access_transmitter(uuid) to authenticated;

create policy "owners manage receiver profiles" on public.receivers
  for all using (owner_id = auth.uid())
  with check (
    owner_id = auth.uid()
    and exists (
      select 1 from public.devices d
      where d.id = device_id and d.owner_id = auth.uid() and d.role = 'receiver'
    )
  );

create policy "paired devices read relationships" on public.receiver_transmitters
  for select using (
    exists (
      select 1 from public.receivers r
      where r.device_id = receiver_id and r.owner_id = auth.uid()
    )
    or exists (
      select 1 from public.devices d
      where d.id = transmitter_id and d.owner_id = auth.uid()
    )
  );

create policy "receiver owners remove relationships" on public.receiver_transmitters
  for delete using (
    exists (
      select 1 from public.receivers r
      where r.device_id = receiver_id and r.owner_id = auth.uid()
    )
  );

create policy "paired receivers read transmitters" on public.devices
  for select using (public.can_access_transmitter(id));

create policy "paired receivers read status" on public.device_status
  for select using (public.can_access_transmitter(device_id));

create policy "paired receivers read current locations" on public.device_locations
  for select using (public.can_access_transmitter(device_id));

create policy "paired receivers read location history" on public.location_history
  for select using (public.can_access_transmitter(device_id));

create policy "paired devices read commands" on public.device_commands
  for select using (public.can_access_transmitter(device_id));

create policy "paired receivers create commands" on public.device_commands
  for insert with check (public.can_access_transmitter(device_id));

grant select, insert, update on public.receivers to authenticated;
grant select, delete on public.receiver_transmitters to authenticated;

alter publication supabase_realtime add table
  public.receivers,
  public.receiver_transmitters;
