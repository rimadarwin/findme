/**
 * @author Infinity
 * @description Consente al proprietario del ricevitore di leggere e aggiornare la challenge.
 * @modified 01.10.2026 - Infinity | Aggiunti configurazione protetta e aggiornamento atomico.
 */
create table public.receiver_access_configurations (
  receiver_id uuid primary key
    references public.receivers(device_id) on delete cascade,
  question text not null check (
    question = btrim(question)
    and char_length(question) between 3 and 160
  ),
  answer text not null check (
    answer = btrim(answer)
    and char_length(answer) between 1 and 160
  ),
  updated_at timestamptz not null default now()
);

alter table public.receiver_access_configurations enable row level security;

create policy "owners read receiver access configuration"
on public.receiver_access_configurations
for select
using (
  exists (
    select 1
    from public.receivers r
    where r.device_id = receiver_id
      and r.owner_id = auth.uid()
  )
);

grant select on public.receiver_access_configurations to authenticated;

insert into public.receiver_access_configurations (
  receiver_id,
  question,
  answer
)
select
  r.device_id,
  r.access_question,
  'bubbu'
from public.receivers r
where r.access_question is not null
  and r.access_answer_hash is not null
on conflict (receiver_id) do nothing;

create function public.update_receiver_access_configuration(
  target_receiver_id uuid,
  requested_question text,
  requested_answer text
)
returns setof public.receiver_access_configurations
language plpgsql
security definer
set search_path = ''
as $$
declare
  normalized_question text := btrim(requested_question);
  normalized_answer text := btrim(requested_answer);
begin
  if normalized_question is null
    or char_length(normalized_question) not between 3 and 160
  then
    raise exception 'Invalid access question';
  end if;

  if normalized_answer is null
    or char_length(normalized_answer) not between 1 and 160
  then
    raise exception 'Invalid access answer';
  end if;

  if not exists (
    select 1
    from public.receivers r
    where r.device_id = target_receiver_id
      and r.owner_id = auth.uid()
  ) then
    raise exception 'Receiver access denied';
  end if;

  update public.receivers
  set access_question = normalized_question,
      access_answer_hash = extensions.crypt(
        lower(normalized_answer),
        extensions.gen_salt('bf', 10)
      )
  where device_id = target_receiver_id;

  insert into public.receiver_access_configurations (
    receiver_id,
    question,
    answer,
    updated_at
  )
  values (
    target_receiver_id,
    normalized_question,
    normalized_answer,
    now()
  )
  on conflict (receiver_id) do update
  set question = excluded.question,
      answer = excluded.answer,
      updated_at = excluded.updated_at;

  return query
  select configuration.*
  from public.receiver_access_configurations configuration
  where configuration.receiver_id = target_receiver_id;
end;
$$;

revoke all on function public.update_receiver_access_configuration(uuid, text, text)
  from public, anon;
grant execute on function public.update_receiver_access_configuration(uuid, text, text)
  to authenticated;

comment on function public.update_receiver_access_configuration(uuid, text, text)
  is 'Aggiorna domanda, risposta protetta e hash di verifica del ricevitore.';
