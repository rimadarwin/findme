alter table public.receiver_transmitters
  add column if not exists alias text
    check (alias is null or char_length(alias) between 1 and 80);

create policy "receiver owners update relationship aliases"
on public.receiver_transmitters
for update
using (
  exists (
    select 1
    from public.receivers r
    where r.device_id = receiver_id
      and r.owner_id = auth.uid()
  )
)
with check (
  exists (
    select 1
    from public.receivers r
    where r.device_id = receiver_id
      and r.owner_id = auth.uid()
  )
);

grant update on public.receiver_transmitters to authenticated;
