-- Demo havens around Galle Face / Colombo 03. Replace with real, verified data before any pilot.
insert into public.safe_havens (name, type, location, opening_hours, verified) values
 ('Demo Pharmacy – Galle Road',   'pharmacy', st_setsrid(st_makepoint(79.8470, 6.9180), 4326)::geography, '{"always": true}', true),
 ('Demo Police Post – Galle Face','police',   st_setsrid(st_makepoint(79.8450, 6.9275), 4326)::geography, '{"always": true}', true),
 ('Demo Hospital – Colombo South','hospital', st_setsrid(st_makepoint(79.8600, 6.8900), 4326)::geography, '{"always": true}', true),
 ('Demo Corner Shop',             'shop',     st_setsrid(st_makepoint(79.8480, 6.9100), 4326)::geography, '{"tz":"Asia/Colombo","mon":[["08:00","20:00"]]}', true);
