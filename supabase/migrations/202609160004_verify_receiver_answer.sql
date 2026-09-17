create or replace function public.verify_receiver_answer(
  target_receiver_id uuid,
  candidate_answer text
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    (
      select access_answer_hash = extensions.crypt(
        lower(trim(candidate_answer)),
        access_answer_hash
      )
      from public.receivers
      where device_id = target_receiver_id
    ),
    false
  );
$$;

revoke all on function public.verify_receiver_answer(uuid, text)
  from public, anon, authenticated;
grant execute on function public.verify_receiver_answer(uuid, text)
  to service_role;
