-- Safe Heaven: schema, PostGIS, RLS, nearby_havens(), haven trigger, realtime.
create extension if not exists postgis with schema extensions;

-- ───────────────────────── tables ─────────────────────────
create table public.users (
  id                 uuid primary key references auth.users (id) on delete cascade,
  name               text not null default '',
  phone              text not null default '',
  mesh_sender_id     bigint unique check (mesh_sender_id between 0 and 4294967295),
  mesh_key_encrypted text,            -- AES-GCM(MESH_KEY_SECRET, 16B AES key || 16B HMAC key), base64
  created_at         timestamptz not null default now()
);

create table public.trusted_contacts (
  id        uuid primary key default gen_random_uuid(),
  user_id   uuid not null references public.users (id) on delete cascade,
  name      text not null,
  phone     text not null,
  priority  int  not null default 1
);
create index on public.trusted_contacts (user_id, priority);

create table public.trips (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references public.users (id) on delete cascade,
  plate            text not null,
  plate_photo_path text,
  destination      geography(Point, 4326) not null,
  route_polyline   text not null,
  eta              timestamptz not null,
  status           text not null default 'active' check (status in ('active', 'arrived', 'cancelled', 'alert')),
  started_at       timestamptz not null default now()
);
create index on public.trips (user_id, started_at desc);

create table public.alerts (
  id         uuid primary key,                 -- generated on the device so the hash chain can start offline
  user_id    uuid not null references public.users (id) on delete cascade,
  trip_id    uuid references public.trips (id) on delete set null,
  status     text not null default 'silent' check (status in ('silent', 'active', 'resolved')),
  source     text not null default 'direct' check (source in ('direct', 'mesh', 'sms')),
  started_at timestamptz not null default now(),
  location   geography(Point, 4326),           -- first known position, drives haven lookup
  plate_hint text
);
create index on public.alerts (user_id, started_at desc);
create index on public.alerts (trip_id);

create table public.location_points (
  id          bigserial primary key,
  alert_id    uuid not null references public.alerts (id) on delete cascade,
  recorded_at timestamptz not null,
  location    geography(Point, 4326) not null,
  accuracy_m  real,
  source      text not null default 'gps' check (source in ('gps', 'mesh', 'sms'))
);
create index on public.location_points (alert_id, recorded_at);
create unique index location_points_dedup on public.location_points (alert_id, recorded_at, source);

create table public.mesh_packets (
  id           bigserial primary key,
  sender_id    bigint not null,
  counter      int not null,
  hops         int not null check (hops between 0 and 5),
  relay_device text,
  received_at  timestamptz not null default now(),
  unique (sender_id, counter)
);

create table public.evidence_chunks (
  alert_id           uuid not null references public.alerts (id) on delete cascade,
  seq                int  not null,
  kind               text not null check (kind in ('photo', 'audio')),
  storage_path       text not null,
  sha256             text not null,            -- chain hash h_seq (hex)
  prev_hash          text,                     -- h_(seq-1); null for the photo (h0)
  client_ts          timestamptz not null,
  server_received_at timestamptz not null default now(),
  device_id          text,
  location           geography(Point, 4326),
  primary key (alert_id, seq)
);

create table public.safe_havens (
  id            uuid primary key default gen_random_uuid(),
  name          text not null,
  type          text not null,                 -- pharmacy, hospital, police, shop, ...
  location      geography(Point, 4326) not null,
  -- {"tz":"Asia/Colombo","always":true} or {"tz":"Asia/Colombo","mon":[["08:00","20:00"]], ...}
  opening_hours jsonb not null default '{"always": true}'::jsonb,
  verified      boolean not null default false
);
create index safe_havens_gix on public.safe_havens using gist (location);

create table public.haven_alerts (
  alert_id     uuid not null references public.alerts (id) on delete cascade,
  haven_id     uuid not null references public.safe_havens (id) on delete cascade,
  distance_m   real not null,
  status       text not null default 'pending' check (status in ('pending', 'acknowledged', 'declined', 'expired')),
  responded_at timestamptz,
  primary key (alert_id, haven_id)
);
create index on public.haven_alerts (haven_id);

-- ───────────────────────── opening hours ─────────────────────────
create or replace function public.haven_is_open(hours jsonb, at_ts timestamptz default now())
returns boolean language plpgsql stable set search_path = public as $$
declare
  tz text := coalesce(hours ->> 'tz', 'Asia/Colombo');
  local_ts timestamp := at_ts at time zone tz;
  day_key text := lower(to_char(local_ts, 'dy'));      -- mon, tue, ...
  t time := local_ts::time;
  slot jsonb;
begin
  if coalesce((hours ->> 'always')::boolean, false) then return true; end if;
  if hours -> day_key is null then return false; end if;
  for slot in select * from jsonb_array_elements(hours -> day_key) loop
    if (slot ->> 0)::time <= (slot ->> 1)::time then
      if t >= (slot ->> 0)::time and t < (slot ->> 1)::time then return true; end if;
    else                                                -- wraps past midnight
      if t >= (slot ->> 0)::time or t < (slot ->> 1)::time then return true; end if;
    end if;
  end loop;
  return false;
end $$;

-- ───────────────────────── nearby_havens ─────────────────────────
-- Open + verified havens within radius_m (default 300 m); widens to 1000 m when none are found.
create or replace function public.nearby_havens(lat double precision, lon double precision, radius_m double precision default 300)
returns table (haven_id uuid, name text, type text, distance_m double precision)
language plpgsql stable security definer set search_path = public, extensions as $$
declare
  origin geography := st_setsrid(st_makepoint(lon, lat), 4326)::geography;
  r double precision := radius_m;
begin
  loop
    return query
      select h.id, h.name, h.type, st_distance(h.location, origin)
      from public.safe_havens h
      where h.verified
        and st_dwithin(h.location, origin, r)
        and public.haven_is_open(h.opening_hours, now())
      order by st_distance(h.location, origin);
    if found or r >= 1000 then return; end if;
    r := 1000;
  end loop;
end $$;

-- Populate haven_alerts as soon as an alert row exists; Realtime pushes the inserts to haven clients.
create or replace function public.alerts_notify_havens() returns trigger
language plpgsql security definer set search_path = public, extensions as $$
begin
  if new.location is null then return new; end if;
  insert into public.haven_alerts (alert_id, haven_id, distance_m)
  select new.id, n.haven_id, n.distance_m
  from public.nearby_havens(st_y(new.location::geometry), st_x(new.location::geometry), 300) n
  on conflict do nothing;
  return new;
end $$;

create trigger alerts_after_insert_havens
  after insert on public.alerts
  for each row execute function public.alerts_notify_havens();

-- If the first position only arrives later (mesh / late GPS), run the lookup then.
create or replace function public.alerts_notify_havens_on_location() returns trigger
language plpgsql security definer set search_path = public, extensions as $$
begin
  if old.location is null and new.location is not null then
    insert into public.haven_alerts (alert_id, haven_id, distance_m)
    select new.id, n.haven_id, n.distance_m
    from public.nearby_havens(st_y(new.location::geometry), st_x(new.location::geometry), 300) n
    on conflict do nothing;
  end if;
  return new;
end $$;

create trigger alerts_after_update_havens
  after update of location on public.alerts
  for each row execute function public.alerts_notify_havens_on_location();

-- Trigger / lookup functions must not be callable straight from the public REST API.
revoke execute on function public.alerts_notify_havens() from public, anon, authenticated;
revoke execute on function public.alerts_notify_havens_on_location() from public, anon, authenticated;
revoke execute on function public.nearby_havens(double precision, double precision, double precision) from public, anon;
grant execute on function public.nearby_havens(double precision, double precision, double precision) to authenticated, service_role;

-- ───────────────────────── append-only evidence ─────────────────────────
create or replace function public.evidence_chunks_immutable() returns trigger
language plpgsql set search_path = public as $$
begin
  raise exception 'evidence_chunks is append-only';
end $$;

create trigger evidence_no_update before update on public.evidence_chunks
  for each row execute function public.evidence_chunks_immutable();
create trigger evidence_no_delete before delete on public.evidence_chunks
  for each row execute function public.evidence_chunks_immutable();
create trigger evidence_no_truncate before truncate on public.evidence_chunks
  for each statement execute function public.evidence_chunks_immutable();

-- ───────────────────────── RLS ─────────────────────────
alter table public.users            enable row level security;
alter table public.trusted_contacts enable row level security;
alter table public.trips            enable row level security;
alter table public.alerts           enable row level security;
alter table public.location_points  enable row level security;
alter table public.mesh_packets     enable row level security;
alter table public.evidence_chunks  enable row level security;
alter table public.safe_havens      enable row level security;
alter table public.haven_alerts     enable row level security;

create policy users_own_select on public.users for select using (id = (select auth.uid()));
create policy users_own_insert on public.users for insert with check (id = (select auth.uid()));
create policy users_own_update on public.users for update using (id = (select auth.uid())) with check (id = (select auth.uid()));

create policy contacts_own_all on public.trusted_contacts for all
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy trips_own_all on public.trips for all
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy alerts_own_select on public.alerts for select using (user_id = (select auth.uid()));
-- inserts/updates on alerts and location_points go through the `alerts` Edge Function (service role)

create policy points_own_select on public.location_points for select
  using (exists (select 1 from public.alerts a where a.id = alert_id and a.user_id = (select auth.uid())));

-- mesh_packets: service role only (no policies = no access for anon/authenticated)

-- evidence: INSERT only, for the owner of the alert. No UPDATE / DELETE policy exists, the
-- immutability triggers above block them even for the service role.
create policy evidence_owner_select on public.evidence_chunks for select
  using (exists (select 1 from public.alerts a where a.id = alert_id and a.user_id = (select auth.uid())));
create policy evidence_owner_insert on public.evidence_chunks for insert
  with check (exists (select 1 from public.alerts a where a.id = alert_id and a.user_id = (select auth.uid())));

create policy havens_read_verified on public.safe_havens for select to authenticated using (verified);
create policy haven_alerts_owner_select on public.haven_alerts for select
  using (exists (select 1 from public.alerts a where a.id = alert_id and a.user_id = (select auth.uid())));

-- ───────────────────────── storage ─────────────────────────
insert into storage.buckets (id, name, public) values ('plate-photos', 'plate-photos', false)
  on conflict do nothing;
insert into storage.buckets (id, name, public) values ('evidence', 'evidence', false)
  on conflict do nothing;

create policy plate_photos_insert_own on storage.objects for insert to authenticated
  with check (bucket_id = 'plate-photos' and (storage.foldername(name))[1] = (select auth.uid())::text);
create policy plate_photos_select_own on storage.objects for select to authenticated
  using (bucket_id = 'plate-photos' and (storage.foldername(name))[1] = (select auth.uid())::text);
-- `evidence` bucket is written by the Edge Function (service role) only; no client policies.

-- ───────────────────────── realtime ─────────────────────────
alter publication supabase_realtime add table public.haven_alerts;
alter publication supabase_realtime add table public.alerts;
alter publication supabase_realtime add table public.location_points;
