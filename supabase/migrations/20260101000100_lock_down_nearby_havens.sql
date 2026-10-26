-- nearby_havens is only used by the alert triggers (SECURITY DEFINER, owner) and the service role.
revoke execute on function public.nearby_havens(double precision, double precision, double precision) from public, anon, authenticated;
grant execute on function public.nearby_havens(double precision, double precision, double precision) to service_role;

-- mesh_packets is service-role only: say so explicitly.
create policy mesh_packets_no_client_access on public.mesh_packets for all to anon, authenticated using (false) with check (false);
