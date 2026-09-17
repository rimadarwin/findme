alter table public.receivers
  add column access_question text
    check (access_question is null or char_length(access_question) between 3 and 160),
  add column access_answer_hash text;

drop index if exists public.receiver_transmitters_transmitter_idx;
create unique index receiver_transmitters_one_receiver_idx
  on public.receiver_transmitters (transmitter_id);

update public.receivers
set
  access_question = 'Dove sei nato?',
  access_answer_hash = extensions.crypt(
    lower(trim('bubbu')),
    extensions.gen_salt('bf', 10)
  )
where pairing_code = '7E3D42DB25';
