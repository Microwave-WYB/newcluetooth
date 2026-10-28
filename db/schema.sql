-- Cluetooth database schema (generated, do not edit by hand).
--
-- Generated with:
--   pg_dump --schema-only --no-owner --no-privileges
-- against a database migrated with dbmate to the latest migration, then
-- stripped of psql meta-commands, SET lines and the version banner.
-- The schema is defined by db/db/migrations/; this file is a readable snapshot.

-- Name: wigle; Type: SCHEMA; Schema: -
--

CREATE SCHEMA wigle;


--
-- Name: postgis; Type: EXTENSION; Schema: -
--

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;


--
-- Name: EXTENSION postgis; Type: COMMENT; Schema: -
--

COMMENT ON EXTENSION postgis IS 'PostGIS geometry and geography spatial types and functions';


--
-- Name: ble_adv_manufacturer_ids(bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_adv_manufacturer_ids(raw bytea) RETURNS integer[]
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
  with manufacturer_data as (
    select decode(item.value ->> 'data', 'hex') as data
    from jsonb_array_elements(public.parse_ble_adv(raw)) as item(value)
    where (item.value ->> 'type')::int = 255
      and length(item.value ->> 'data') >= 4
  ),
  manufacturer_ids as (
    select get_byte(data, 0) + get_byte(data, 1) * 256 as company_id
    from manufacturer_data
  )
  select coalesce(
    array_agg(distinct company_id order by company_id),
    array[]::integer[]
  )
  from manufacturer_ids
$$;


--
-- Name: ble_adv_service_data_uuids(bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_adv_service_data_uuids(raw bytea) RETURNS text[]
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $$
declare
    ad_struct jsonb;
    ad_type int;
    data bytea;
    uuid text;
    result text[] := array[]::text[];
begin
    for ad_struct in select value from jsonb_array_elements(public.parse_ble_adv(raw)) loop
        ad_type := (ad_struct ->> 'type')::int;
        data := decode(ad_struct ->> 'data', 'hex');

        if ad_type = 22 and octet_length(data) >= 2 then
            uuid := lpad(to_hex(get_byte(data, 1) * 256 + get_byte(data, 0)), 4, '0');
            result := array_append(result, uuid);
        elsif ad_type = 32 and octet_length(data) >= 4 then
            uuid :=
                lpad(to_hex(get_byte(data, 3)), 2, '0') ||
                lpad(to_hex(get_byte(data, 2)), 2, '0') ||
                lpad(to_hex(get_byte(data, 1)), 2, '0') ||
                lpad(to_hex(get_byte(data, 0)), 2, '0');
            result := array_append(result, uuid);
        elsif ad_type = 33 and octet_length(data) >= 16 then
            uuid := public.ble_uuid128_le(substring(data from 1 for 16));
            if uuid is not null then
                result := array_append(result, uuid);
            end if;
        end if;
    end loop;

    return coalesce(
        (select array_agg(distinct u.uuid order by u.uuid) from unnest(result) as u(uuid)),
        array[]::text[]
    );
end;
$$;


--
-- Name: ble_adv_service_uuids(bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_adv_service_uuids(raw bytea) RETURNS text[]
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $$
declare
    ad_struct jsonb;
    ad_type int;
    data bytea;
    byte_offset int;
    uuid text;
    result text[] := array[]::text[];
begin
    for ad_struct in select value from jsonb_array_elements(public.parse_ble_adv(raw)) loop
        ad_type := (ad_struct ->> 'type')::int;
        data := decode(ad_struct ->> 'data', 'hex');

        if ad_type in (2, 3) then
            byte_offset := 0;
            while byte_offset + 2 <= octet_length(data) loop
                uuid := lpad(
                    to_hex(get_byte(data, byte_offset + 1) * 256 + get_byte(data, byte_offset)),
                    4,
                    '0'
                );
                result := array_append(result, uuid);
                byte_offset := byte_offset + 2;
            end loop;
        elsif ad_type in (4, 5) then
            byte_offset := 0;
            while byte_offset + 4 <= octet_length(data) loop
                uuid :=
                    lpad(to_hex(get_byte(data, byte_offset + 3)), 2, '0') ||
                    lpad(to_hex(get_byte(data, byte_offset + 2)), 2, '0') ||
                    lpad(to_hex(get_byte(data, byte_offset + 1)), 2, '0') ||
                    lpad(to_hex(get_byte(data, byte_offset)), 2, '0');
                result := array_append(result, uuid);
                byte_offset := byte_offset + 4;
            end loop;
        elsif ad_type in (6, 7) then
            byte_offset := 0;
            while byte_offset + 16 <= octet_length(data) loop
                uuid := public.ble_uuid128_le(substring(data from byte_offset + 1 for 16));
                if uuid is not null then
                    result := array_append(result, uuid);
                end if;
                byte_offset := byte_offset + 16;
            end loop;
        end if;
    end loop;

    return coalesce(
        (select array_agg(distinct u.uuid order by u.uuid) from unnest(result) as u(uuid)),
        array[]::text[]
    );
end;
$$;


--
-- Name: ble_adv_split(bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_adv_split(raw bytea) RETURNS TABLE(ord smallint, ad_type smallint, data bytea)
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $$
declare
    bytes_len int := octet_length(raw);
    byte_offset int := 0;
    field_len int;
    n smallint := 0;
begin
    while byte_offset < bytes_len loop
        field_len := get_byte(raw, byte_offset);
        exit when field_len = 0 or byte_offset + field_len >= bytes_len;
        n := n + 1;
        ord := n;
        ad_type := get_byte(raw, byte_offset + 1);
        data := substring(raw from byte_offset + 3 for field_len - 1);
        return next;
        byte_offset := byte_offset + field_len + 1;
    end loop;
    if byte_offset < bytes_len then
        ord := n + 1;
        ad_type := null;
        data := substring(raw from byte_offset + 1);
        return next;
    end if;
end;
$$;


--
-- Name: ble_adv_types(bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_adv_types(raw bytea) RETURNS smallint[]
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
  select coalesce(
    array_agg((item.value ->> 'type')::smallint order by item.ordinality),
    array[]::smallint[]
  )
  from jsonb_array_elements(public.parse_ble_adv(raw)) with ordinality as item(value, ordinality)
$$;


--
-- Name: ble_structure_bytes(smallint, bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_structure_bytes(ad_type smallint, data bytea) RETURNS bytea
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
  select set_byte(set_byte('\x0000'::bytea, 0, octet_length(data) + 1), 1, ad_type) || data
$$;


--
-- Name: ble_structure_uuids(smallint, bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_structure_uuids(ad_type smallint, data bytea) RETURNS text[]
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
  select case
    when ad_type in (2, 3, 4, 5, 6, 7)
      then public.ble_adv_service_uuids(public.ble_structure_bytes(ad_type, data))
    when ad_type in (22, 32, 33)
      then nullif(
        public.ble_adv_service_data_uuids(public.ble_structure_bytes(ad_type, data)),
        array[]::text[]
      )
  end
$$;


--
-- Name: ble_utf8_or_null(bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_utf8_or_null(data bytea) RETURNS text
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $$
begin
    return nullif(convert_from(rtrim(data, '\x00'::bytea), 'UTF8'), '');
exception
    when others then
        return null;
end;
$$;


--
-- Name: ble_uuid128_le(bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ble_uuid128_le(uuid_bytes bytea) RETURNS text
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $$
declare
    h text;
begin
    if uuid_bytes is null or octet_length(uuid_bytes) != 16 then
        return null;
    end if;

    h := encode(uuid_bytes, 'hex');
    return
        substring(h from 31 for 2) ||
        substring(h from 29 for 2) ||
        substring(h from 27 for 2) ||
        substring(h from 25 for 2) ||
        '-' ||
        substring(h from 23 for 2) ||
        substring(h from 21 for 2) ||
        '-' ||
        substring(h from 19 for 2) ||
        substring(h from 17 for 2) ||
        '-' ||
        substring(h from 15 for 2) ||
        substring(h from 13 for 2) ||
        '-' ||
        substring(h from 11 for 2) ||
        substring(h from 9 for 2) ||
        substring(h from 7 for 2) ||
        substring(h from 5 for 2) ||
        substring(h from 3 for 2) ||
        substring(h from 1 for 2);
end;
$$;


--
-- Name: ensure_sighting_partition(date); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.ensure_sighting_partition(month date) RETURNS boolean
    LANGUAGE plpgsql
    AS $$
declare
    start_month date := date_trunc('month', month)::date;
    partition_name text := format(
        'sighting_minutes_y%sm%s', to_char(start_month, 'YYYY'), to_char(start_month, 'MM')
    );
begin
    if to_regclass(format('public.%I', partition_name)) is not null then
        return true;
    end if;
    execute format(
        'create table if not exists public.%I partition of public.sighting_minutes '
        'for values from (%L) to (%L)',
        partition_name,
        start_month::text || ' 00:00:00+00',
        (start_month + interval '1 month')::date::text || ' 00:00:00+00'
    );
    return true;
exception
    when duplicate_table or unique_violation then
        return true;
    when check_violation then
        raise notice 'sighting_minutes_default holds rows for %; leaving them there',
            start_month;
        return false;
end;
$$;


--
-- Name: jsonb_counts_keys(jsonb); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.jsonb_counts_keys(counts jsonb) RETURNS bigint[]
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
  select coalesce(array_agg(key::bigint order by key::bigint), array[]::bigint[])
  from jsonb_object_keys(counts) as key
$$;


--
-- Name: jsonb_counts_merge(jsonb, jsonb); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.jsonb_counts_merge(a jsonb, b jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
  select coalesce(jsonb_object_agg(key, total), '{}'::jsonb)
  from (
    select key, sum(value::bigint) as total
    from (
      select * from jsonb_each_text(coalesce(a, '{}'::jsonb))
      union all
      select * from jsonb_each_text(coalesce(b, '{}'::jsonb))
    ) entries
    group by key
    having sum(value::bigint) <> 0
  ) totals
$$;


--
-- Name: parse_ble_adv(bytea); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.parse_ble_adv(raw bytea) RETURNS jsonb
    LANGUAGE plpgsql IMMUTABLE STRICT
    AS $$
declare
    bytes_len int;
    byte_offset int := 0;
    field_len int;
    field_type int;
    field_data bytea;
    result jsonb := '[]'::jsonb;
begin
    if raw is null then
        return result;
    end if;

    bytes_len := octet_length(raw);
    if bytes_len = 0 then
        return result;
    end if;

    while byte_offset < bytes_len loop
        field_len := get_byte(raw, byte_offset);

        if field_len = 0 then
            exit;
        end if;

        if byte_offset + field_len >= bytes_len then
            exit;
        end if;

        field_type := get_byte(raw, byte_offset + 1);
        field_data := substring(raw from byte_offset + 3 for field_len - 1);

        result := result || jsonb_build_array(
            jsonb_build_object(
                'type', field_type,
                'data', encode(field_data, 'hex')
            )
        );

        byte_offset := byte_offset + field_len + 1;
    end loop;

    return result;
end;
$$;


--
-- Name: payload_raw(bigint); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.payload_raw(payload_id bigint) RETURNS bytea
    LANGUAGE sql STABLE STRICT
    AS $$
  select
    coalesce(
      (
        select string_agg(public.ble_structure_bytes(s.ad_type, s.data), ''::bytea order by u.ord)
        from unnest(p.structure_ids) with ordinality as u(structure_id, ord)
        join public.ad_structures s on s.id = u.structure_id
      ),
      ''::bytea
    )
    || coalesce(p.tail, decode(repeat('00', p.zero_padding), 'hex'))
  from public.payloads p
  where p.id = payload_id
$$;


--
-- Name: set_location(); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.set_location() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
begin
    if new.lat is not null and new.lon is not null then
        new.location = st_setsrid(st_makepoint(new.lon, new.lat), 4326);
    else
        new.location = null;
    end if;
    return new;
end;
$$;


--
-- Name: sighting_sample_better(double precision, real, timestamp with time zone, double precision, real, timestamp with time zone); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.sighting_sample_better(a_score double precision, a_accuracy real, a_seen timestamp with time zone, b_score double precision, b_accuracy real, b_seen timestamp with time zone) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    AS $$
  select case
    when a_seen is null then false
    when b_seen is null then true
    when a_score is distinct from b_score then
      coalesce(a_score > b_score, a_score is not null)
    when greatest(coalesce(a_accuracy, 50), 1) <> greatest(coalesce(b_accuracy, 50), 1) then
      greatest(coalesce(a_accuracy, 50), 1) < greatest(coalesce(b_accuracy, 50), 1)
    else a_seen < b_seen
  end
$$;


--
-- Name: sighting_sample_score(smallint, real); Type: FUNCTION; Schema: public
--

CREATE FUNCTION public.sighting_sample_score(rssi smallint, accuracy real) RETURNS double precision
    LANGUAGE sql IMMUTABLE
    AS $$
  -- log(double precision) is the base-10 logarithm.
  select rssi::float8 / 20 - 2 * log(greatest(coalesce(accuracy, 50), 1)::float8)
$$;


--
-- Name: ad_structures; Type: TABLE; Schema: public
--

CREATE TABLE public.ad_structures (
    id bigint NOT NULL,
    ad_type smallint NOT NULL,
    data bytea NOT NULL,
    local_name text GENERATED ALWAYS AS (
CASE
    WHEN (ad_type = ANY (ARRAY[8, 9])) THEN public.ble_utf8_or_null(data)
    ELSE NULL::text
END) STORED,
    company_id integer GENERATED ALWAYS AS (
CASE
    WHEN ((ad_type = 255) AND (octet_length(data) >= 2)) THEN (get_byte(data, 0) + (get_byte(data, 1) * 256))
    ELSE NULL::integer
END) STORED,
    uuids text[] GENERATED ALWAYS AS (public.ble_structure_uuids(ad_type, data)) STORED
);


--
-- Name: ad_structures_id_seq; Type: SEQUENCE; Schema: public
--

ALTER TABLE public.ad_structures ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.ad_structures_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: adv_enrichments; Type: TABLE; Schema: public
--

CREATE TABLE public.adv_enrichments (
    addr macaddr CONSTRAINT adv_enrichments_addr_not_null1 NOT NULL,
    payload_id bigint NOT NULL,
    enrichment_kind text CONSTRAINT adv_enrichments_enrichment_kind_not_null1 NOT NULL,
    enrichment_id text CONSTRAINT adv_enrichments_enrichment_id_not_null1 NOT NULL,
    enrichment_revision text CONSTRAINT adv_enrichments_enrichment_revision_not_null1 NOT NULL,
    recipe_git_commit text,
    recipe_path text,
    data jsonb CONSTRAINT adv_enrichments_data_not_null1 NOT NULL,
    computed_at timestamp with time zone DEFAULT now() CONSTRAINT adv_enrichments_computed_at_not_null1 NOT NULL
);


--
-- Name: adv_enrichments_old; Type: TABLE; Schema: public
--

CREATE TABLE public.adv_enrichments_old (
    addr macaddr CONSTRAINT adv_enrichments_addr_not_null NOT NULL,
    raw bytea CONSTRAINT adv_enrichments_raw_not_null NOT NULL,
    enrichment_kind text CONSTRAINT adv_enrichments_enrichment_kind_not_null NOT NULL,
    enrichment_id text CONSTRAINT adv_enrichments_enrichment_id_not_null NOT NULL,
    enrichment_revision text CONSTRAINT adv_enrichments_enrichment_revision_not_null NOT NULL,
    recipe_git_commit text,
    recipe_path text,
    data jsonb CONSTRAINT adv_enrichments_data_not_null NOT NULL,
    computed_at timestamp with time zone DEFAULT now() CONSTRAINT adv_enrichments_computed_at_not_null NOT NULL
);


--
-- Name: payloads; Type: TABLE; Schema: public
--

CREATE TABLE public.payloads (
    id bigint NOT NULL,
    sha256 bytea NOT NULL,
    structure_ids bigint[] NOT NULL,
    zero_padding smallint DEFAULT 0 NOT NULL,
    tail bytea,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    sightings bigint DEFAULT 0 NOT NULL,
    CONSTRAINT payloads_tail_or_padding CHECK (((tail IS NULL) OR (zero_padding = 0)))
)
WITH (fillfactor='80');


--
-- Name: payload_fields; Type: VIEW; Schema: public
--

CREATE VIEW public.payload_fields AS
 SELECT id AS payload_id,
    sha256,
    ( SELECT s.local_name
           FROM (unnest(p.structure_ids) WITH ORDINALITY u(structure_id, ord)
             JOIN public.ad_structures s ON ((s.id = u.structure_id)))
          WHERE (s.ad_type = ANY (ARRAY[8, 9]))
          ORDER BY s.ad_type DESC, u.ord
         LIMIT 1) AS local_name,
    COALESCE(( SELECT array_agg(s.ad_type ORDER BY u.ord) AS array_agg
           FROM (unnest(p.structure_ids) WITH ORDINALITY u(structure_id, ord)
             JOIN public.ad_structures s ON ((s.id = u.structure_id)))), ARRAY[]::smallint[]) AS adv_types,
    COALESCE(( SELECT array_agg((octet_length(s.data))::smallint ORDER BY u.ord) AS array_agg
           FROM (unnest(p.structure_ids) WITH ORDINALITY u(structure_id, ord)
             JOIN public.ad_structures s ON ((s.id = u.structure_id)))), ARRAY[]::smallint[]) AS ad_data_lengths,
    COALESCE(( SELECT array_agg(DISTINCT s.company_id ORDER BY s.company_id) AS array_agg
           FROM public.ad_structures s
          WHERE ((s.id = ANY (p.structure_ids)) AND (s.company_id IS NOT NULL))), ARRAY[]::integer[]) AS manufacturer_ids,
    COALESCE(( SELECT array_agg(DISTINCT x.uuid ORDER BY x.uuid) AS array_agg
           FROM (public.ad_structures s
             CROSS JOIN LATERAL unnest(s.uuids) x(uuid))
          WHERE ((s.id = ANY (p.structure_ids)) AND (s.ad_type = ANY (ARRAY[2, 3, 4, 5, 6, 7])))), ARRAY[]::text[]) AS service_uuids,
    COALESCE(( SELECT array_agg(DISTINCT x.uuid ORDER BY x.uuid) AS array_agg
           FROM (public.ad_structures s
             CROSS JOIN LATERAL unnest(s.uuids) x(uuid))
          WHERE ((s.id = ANY (p.structure_ids)) AND (s.ad_type = ANY (ARRAY[22, 32, 33])))), ARRAY[]::text[]) AS service_data_uuids
   FROM public.payloads p;


--
-- Name: sighting_minutes; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes (
    id bigint NOT NULL,
    minute timestamp with time zone NOT NULL,
    addr macaddr NOT NULL,
    payload_id bigint NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 NOT NULL,
    rssi_count bigint DEFAULT 0 NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
)
PARTITION BY RANGE (minute);


--
-- Name: adv_shapes; Type: VIEW; Schema: public
--

CREATE VIEW public.adv_shapes AS
 SELECT m.addr,
    m.payload_id,
    f.sha256,
    m.first_seen,
    m.last_seen,
    m.sightings,
    m.minutes,
    m.rssi_min,
    m.rssi_max,
    f.local_name,
    f.adv_types,
    f.manufacturer_ids,
    f.service_uuids,
    f.service_data_uuids,
    m.min_lat,
    m.max_lat,
    m.min_lon,
    m.max_lon,
    f.adv_types AS ad_types_ordered,
    f.ad_data_lengths
   FROM (( SELECT sighting_minutes.addr,
            sighting_minutes.payload_id,
            min(sighting_minutes.first_seen) AS first_seen,
            max(sighting_minutes.last_seen) AS last_seen,
            (sum(sighting_minutes.sightings))::bigint AS sightings,
            count(*) AS minutes,
            min(sighting_minutes.rssi_min) AS rssi_min,
            max(sighting_minutes.rssi_max) AS rssi_max,
            min(sighting_minutes.min_lat) AS min_lat,
            max(sighting_minutes.max_lat) AS max_lat,
            min(sighting_minutes.min_lon) AS min_lon,
            max(sighting_minutes.max_lon) AS max_lon
           FROM public.sighting_minutes
          GROUP BY sighting_minutes.addr, sighting_minutes.payload_id) m
     JOIN public.payload_fields f ON ((f.payload_id = m.payload_id)));


--
-- Name: advs_old; Type: TABLE; Schema: public
--

CREATE TABLE public.advs_old (
    addr macaddr CONSTRAINT advs_addr_not_null NOT NULL,
    raw bytea CONSTRAINT advs_raw_not_null NOT NULL,
    scans_through_id bigint CONSTRAINT advs_scans_through_id_not_null NOT NULL,
    first_seen timestamp with time zone CONSTRAINT advs_first_seen_not_null NOT NULL,
    last_seen timestamp with time zone CONSTRAINT advs_last_seen_not_null NOT NULL,
    scans_count bigint CONSTRAINT advs_scans_count_not_null NOT NULL,
    rssi_min smallint,
    local_name text,
    adv_types smallint[] CONSTRAINT advs_adv_types_not_null NOT NULL,
    manufacturer_ids integer[] CONSTRAINT advs_manufacturer_ids_not_null NOT NULL,
    service_uuids text[] CONSTRAINT advs_service_uuids_not_null NOT NULL,
    service_data_uuids text[] CONSTRAINT advs_service_data_uuids_not_null NOT NULL,
    centroid public.geometry(Point,4326),
    bbox public.geometry(Polygon,4326),
    centroid_lat double precision GENERATED ALWAYS AS (public.st_y(centroid)) STORED,
    centroid_lon double precision GENERATED ALWAYS AS (public.st_x(centroid)) STORED,
    min_lat double precision GENERATED ALWAYS AS (public.st_ymin(public.box3d(bbox))) STORED,
    max_lat double precision GENERATED ALWAYS AS (public.st_ymax(public.box3d(bbox))) STORED,
    min_lon double precision GENERATED ALWAYS AS (public.st_xmin(public.box3d(bbox))) STORED,
    max_lon double precision GENERATED ALWAYS AS (public.st_xmax(public.box3d(bbox))) STORED
);


--
-- Name: blobs; Type: TABLE; Schema: public
--

CREATE TABLE public.blobs (
    uri text NOT NULL,
    app_version text,
    meta jsonb,
    updated_at timestamp with time zone,
    synced_at timestamp with time zone,
    success boolean,
    error text,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    id bigint NOT NULL,
    session_id uuid,
    uploader_id integer
);


--
-- Name: blobs_id_seq; Type: SEQUENCE; Schema: public
--

ALTER TABLE public.blobs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.blobs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: gatt_attributes; Type: TABLE; Schema: public
--

CREATE TABLE public.gatt_attributes (
    discovery_id bigint NOT NULL,
    attribute_id integer NOT NULL,
    parent_attribute_id integer,
    kind text NOT NULL,
    uuid text NOT NULL,
    is_primary boolean,
    instance integer,
    properties text[],
    value bytea,
    read_status text,
    CONSTRAINT gatt_attributes_kind CHECK ((kind = ANY (ARRAY['service'::text, 'characteristic'::text, 'descriptor'::text]))),
    CONSTRAINT gatt_attributes_parent CHECK (((kind = 'service'::text) = (parent_attribute_id IS NULL))),
    CONSTRAINT gatt_attributes_properties CHECK (((kind = 'characteristic'::text) OR (properties IS NULL))),
    CONSTRAINT gatt_attributes_read CHECK (
CASE kind
    WHEN 'service'::text THEN ((value IS NULL) AND (read_status IS NULL))
    ELSE ((read_status IS NOT NULL) AND ((value IS NULL) OR (read_status = 'ok'::text)))
END),
    CONSTRAINT gatt_attributes_read_status CHECK (((read_status IS NULL) OR (read_status = ANY (ARRAY['ok'::text, 'insufficient_authentication'::text, 'insufficient_encryption'::text, 'read_not_permitted'::text, 'timeout'::text, 'not_read'::text])) OR (read_status ~ '^error:-?[0-9]+$'::text))),
    CONSTRAINT gatt_attributes_service_fields CHECK (((kind = 'service'::text) OR (is_primary IS NULL)))
);


--
-- Name: gatt_discoveries; Type: TABLE; Schema: public
--

CREATE TABLE public.gatt_discoveries (
    id bigint NOT NULL,
    blob_id bigint NOT NULL,
    discovery_uuid uuid NOT NULL,
    session_id uuid,
    app_version text,
    device_model text,
    android_version text,
    addr macaddr NOT NULL,
    addr_type text,
    trigger_payload_id bigint,
    trigger_rssi smallint,
    trigger_scanned_at timestamp with time zone,
    trigger_is_legacy boolean,
    trigger_primary_phy smallint,
    trigger_secondary_phy smallint,
    trigger_adv_sid smallint,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    lat double precision,
    lon double precision,
    accuracy real,
    location public.geometry(Point,4326),
    outcome text NOT NULL,
    stage_reached text NOT NULL,
    error_stage text,
    error_status integer,
    error_name text,
    disconnect_initiator text,
    disconnect_status integer,
    mtu smallint,
    phy_tx smallint,
    phy_rx smallint,
    connect_ms integer,
    mtu_ms integer,
    discover_ms integer,
    read_ms integer,
    profile_hash text,
    raw jsonb NOT NULL,
    CONSTRAINT gatt_discoveries_accuracy_semantics CHECK ((((accuracy IS NULL) OR (lat IS NOT NULL)) AND ((accuracy IS NULL) OR ((accuracy >= (0)::double precision) AND (accuracy < 'Infinity'::real))))),
    CONSTRAINT gatt_discoveries_coordinates_paired CHECK (((lat IS NULL) = (lon IS NULL))),
    CONSTRAINT gatt_discoveries_ended CHECK (((ended_at IS NULL) OR (ended_at >= started_at))),
    CONSTRAINT gatt_discoveries_lat_range CHECK (((lat IS NULL) OR ((lat >= ('-90'::integer)::double precision) AND (lat <= (90)::double precision)))),
    CONSTRAINT gatt_discoveries_lon_range CHECK (((lon IS NULL) OR ((lon >= ('-180'::integer)::double precision) AND (lon <= (180)::double precision)))),
    CONSTRAINT gatt_discoveries_mtu_range CHECK (((mtu IS NULL) OR ((mtu >= 23) AND (mtu <= 517)))),
    CONSTRAINT gatt_discoveries_outcome CHECK ((outcome = ANY (ARRAY['complete'::text, 'partial'::text, 'failed'::text, 'cancelled'::text]))),
    CONSTRAINT gatt_discoveries_phy_range CHECK ((((phy_tx IS NULL) OR ((phy_tx >= 1) AND (phy_tx <= 3))) AND ((phy_rx IS NULL) OR ((phy_rx >= 1) AND (phy_rx <= 3))))),
    CONSTRAINT gatt_discoveries_stage CHECK ((stage_reached = ANY (ARRAY['connect'::text, 'mtu'::text, 'discover'::text, 'read'::text, 'disconnect'::text])))
);


--
-- Name: gatt_discoveries_id_seq; Type: SEQUENCE; Schema: public
--

ALTER TABLE public.gatt_discoveries ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.gatt_discoveries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payloads_id_seq; Type: SEQUENCE; Schema: public
--

ALTER TABLE public.payloads ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.payloads_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: projection_state_old; Type: TABLE; Schema: public
--

CREATE TABLE public.projection_state_old (
    name text CONSTRAINT projection_state_name_not_null NOT NULL,
    scans_through_id bigint DEFAULT 0 CONSTRAINT projection_state_scans_through_id_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT projection_state_updated_at_not_null NOT NULL
);


--
-- Name: recipe_interaction_events; Type: TABLE; Schema: public
--

CREATE TABLE public.recipe_interaction_events (
    run_id integer NOT NULL,
    seq integer NOT NULL,
    at timestamp with time zone NOT NULL,
    event text NOT NULL,
    data jsonb NOT NULL
);


--
-- Name: recipe_interaction_runs; Type: TABLE; Schema: public
--

CREATE TABLE public.recipe_interaction_runs (
    id integer NOT NULL,
    recipe_id text NOT NULL,
    recipe_sha256 text NOT NULL,
    recipe_git_commit text NOT NULL,
    recipe_path text NOT NULL,
    addr macaddr NOT NULL,
    raw_adv bytea,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    status text NOT NULL,
    result jsonb
);


--
-- Name: recipe_interaction_runs_id_seq; Type: SEQUENCE; Schema: public
--

CREATE SEQUENCE public.recipe_interaction_runs_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: recipe_interaction_runs_id_seq; Type: SEQUENCE OWNED BY; Schema: public
--

ALTER SEQUENCE public.recipe_interaction_runs_id_seq OWNED BY public.recipe_interaction_runs.id;


--
-- Name: scans_old; Type: TABLE; Schema: public
--

CREATE TABLE public.scans_old (
    id bigint CONSTRAINT scans_id_not_null NOT NULL,
    blob text,
    addr macaddr CONSTRAINT scans_addr_not_null NOT NULL,
    rssi smallint,
    scanned_at timestamp with time zone CONSTRAINT scans_scanned_at_not_null NOT NULL,
    raw bytea CONSTRAINT scans_raw_not_null NOT NULL,
    local_name text,
    tx_power smallint,
    is_connectable boolean,
    lat double precision,
    lon double precision,
    accuracy real,
    location public.geometry(Point,4326)
);


--
-- Name: scans_id_seq; Type: SEQUENCE; Schema: public
--

CREATE SEQUENCE public.scans_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: scans_id_seq; Type: SEQUENCE OWNED BY; Schema: public
--

ALTER SEQUENCE public.scans_id_seq OWNED BY public.scans_old.id;


--
-- Name: schema_migrations; Type: TABLE; Schema: public
--

CREATE TABLE public.schema_migrations (
    version character varying NOT NULL
);


--
-- Name: sighting_minutes_default; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_default (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_id_seq; Type: SEQUENCE; Schema: public
--

ALTER TABLE public.sighting_minutes ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.sighting_minutes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sighting_minutes_y2025m01; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m01 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m02; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m02 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m03; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m03 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m04; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m04 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m05; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m05 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m06; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m06 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m07; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m07 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m08; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m08 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m09; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m09 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m10; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m10 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m11; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m11 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2025m12; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2025m12 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m01; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m01 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m02; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m02 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m03; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m03 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m04; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m04 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m05; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m05 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m06; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m06 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m07; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m07 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m08; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m08 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m09; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m09 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m10; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m10 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m11; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m11 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2026m12; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2026m12 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m01; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m01 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m02; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m02 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m03; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m03 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m04; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m04 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m05; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m05 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m06; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m06 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m07; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m07 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m08; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m08 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m09; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m09 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m10; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m10 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m11; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m11 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: sighting_minutes_y2027m12; Type: TABLE; Schema: public
--

CREATE TABLE public.sighting_minutes_y2027m12 (
    id bigint CONSTRAINT sighting_minutes_id_not_null NOT NULL,
    minute timestamp with time zone CONSTRAINT sighting_minutes_minute_not_null NOT NULL,
    addr macaddr CONSTRAINT sighting_minutes_addr_not_null NOT NULL,
    payload_id bigint CONSTRAINT sighting_minutes_payload_id_not_null NOT NULL,
    uploader_id integer,
    session_id uuid,
    blob_counts jsonb CONSTRAINT sighting_minutes_blob_counts_not_null NOT NULL,
    blob_ids bigint[] GENERATED ALWAYS AS (public.jsonb_counts_keys(blob_counts)) STORED,
    sightings bigint CONSTRAINT sighting_minutes_sightings_not_null NOT NULL,
    first_seen timestamp with time zone,
    last_seen timestamp with time zone,
    rssi_min smallint,
    rssi_max smallint,
    rssi_sum bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_sum_not_null NOT NULL,
    rssi_count bigint DEFAULT 0 CONSTRAINT sighting_minutes_rssi_count_not_null NOT NULL,
    rssi_mean double precision GENERATED ALWAYS AS (
CASE
    WHEN (rssi_count > 0) THEN ((rssi_sum)::double precision / (rssi_count)::double precision)
    ELSE NULL::double precision
END) STORED,
    location public.geography(Point,4326),
    best_rssi smallint,
    best_accuracy real,
    best_score double precision,
    best_seen timestamp with time zone,
    min_lat double precision,
    max_lat double precision,
    min_lon double precision,
    max_lon double precision,
    tx_power smallint,
    is_connectable boolean,
    addr_type smallint,
    adv_length smallint,
    is_legacy boolean,
    primary_phy smallint,
    secondary_phy smallint,
    adv_sid smallint,
    adv_tx_power smallint,
    periodic_interval integer,
    data_status smallint,
    CONSTRAINT sighting_minutes_extent_range CHECK ((((min_lat IS NULL) OR ((min_lat >= ('-90'::integer)::double precision) AND (min_lat <= (90)::double precision))) AND ((max_lat IS NULL) OR ((max_lat >= ('-90'::integer)::double precision) AND (max_lat <= (90)::double precision))) AND ((min_lon IS NULL) OR ((min_lon >= ('-180'::integer)::double precision) AND (min_lon <= (180)::double precision))) AND ((max_lon IS NULL) OR ((max_lon >= ('-180'::integer)::double precision) AND (max_lon <= (180)::double precision))))),
    CONSTRAINT sighting_minutes_v3_ranges CHECK ((((primary_phy IS NULL) OR (primary_phy = ANY (ARRAY[1, 3]))) AND ((secondary_phy IS NULL) OR ((secondary_phy >= 0) AND (secondary_phy <= 3))) AND ((adv_sid IS NULL) OR ((adv_sid >= 0) AND (adv_sid <= 15))) AND ((periodic_interval IS NULL) OR ((periodic_interval >= 1) AND (periodic_interval <= 65536))) AND ((data_status IS NULL) OR ((data_status >= 0) AND (data_status <= 2))) AND ((addr_type IS NULL) OR ((addr_type >= 0) AND (addr_type <= 255)))))
);


--
-- Name: uploaders; Type: TABLE; Schema: public
--

CREATE TABLE public.uploaders (
    id integer NOT NULL,
    uploader text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: uploaders_id_seq; Type: SEQUENCE; Schema: public
--

ALTER TABLE public.uploaders ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.uploaders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: wigle_upload_batch_minutes; Type: TABLE; Schema: public
--

CREATE TABLE public.wigle_upload_batch_minutes (
    batch_id bigint NOT NULL,
    minute_id bigint NOT NULL,
    minute timestamp with time zone NOT NULL
);


--
-- Name: wigle_upload_batch_scans_old; Type: TABLE; Schema: public
--

CREATE TABLE public.wigle_upload_batch_scans_old (
    batch_id bigint CONSTRAINT wigle_upload_batch_scans_batch_id_not_null NOT NULL,
    scan_id bigint CONSTRAINT wigle_upload_batch_scans_scan_id_not_null NOT NULL
);


--
-- Name: wigle_upload_batches; Type: TABLE; Schema: public
--

CREATE TABLE public.wigle_upload_batches (
    id bigint NOT NULL,
    client_token text,
    status text NOT NULL,
    row_count bigint DEFAULT 0 NOT NULL,
    min_item_id bigint,
    max_item_id bigint,
    filename text,
    csv_sha256 text,
    wigle_transids jsonb DEFAULT '[]'::jsonb NOT NULL,
    wigle_status text,
    upload_response jsonb,
    status_response jsonb,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    exported_at timestamp with time zone,
    uploaded_at timestamp with time zone,
    status_checked_at timestamp with time zone,
    completed_at timestamp with time zone,
    CONSTRAINT wigle_upload_batches_status_check CHECK ((status = ANY (ARRAY['created'::text, 'exported'::text, 'uploading'::text, 'uploaded'::text, 'completed'::text, 'failed'::text])))
);


--
-- Name: wigle_upload_batches_id_seq; Type: SEQUENCE; Schema: public
--

CREATE SEQUENCE public.wigle_upload_batches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: wigle_upload_batches_id_seq; Type: SEQUENCE OWNED BY; Schema: public
--

ALTER SEQUENCE public.wigle_upload_batches_id_seq OWNED BY public.wigle_upload_batches.id;


--
-- Name: responses; Type: TABLE; Schema: wigle
--

CREATE TABLE wigle.responses (
    id bigint NOT NULL,
    endpoint text NOT NULL,
    params jsonb NOT NULL,
    params_hash text NOT NULL,
    search_after text,
    next_search_after text,
    total bigint,
    http_status integer,
    error text,
    caller text,
    called_at timestamp with time zone DEFAULT now() NOT NULL,
    response jsonb
);


--
-- Name: responses_id_seq; Type: SEQUENCE; Schema: wigle
--

ALTER TABLE wigle.responses ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME wigle.responses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sighting_minutes_default; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_default DEFAULT;


--
-- Name: sighting_minutes_y2025m01; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m01 FOR VALUES FROM ('2025-01-01 00:00:00+00') TO ('2025-02-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m02; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m02 FOR VALUES FROM ('2025-02-01 00:00:00+00') TO ('2025-03-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m03; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m03 FOR VALUES FROM ('2025-03-01 00:00:00+00') TO ('2025-04-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m04; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m04 FOR VALUES FROM ('2025-04-01 00:00:00+00') TO ('2025-05-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m05; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m05 FOR VALUES FROM ('2025-05-01 00:00:00+00') TO ('2025-06-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m06; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m06 FOR VALUES FROM ('2025-06-01 00:00:00+00') TO ('2025-07-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m07; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m07 FOR VALUES FROM ('2025-07-01 00:00:00+00') TO ('2025-08-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m08; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m08 FOR VALUES FROM ('2025-08-01 00:00:00+00') TO ('2025-09-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m09; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m09 FOR VALUES FROM ('2025-09-01 00:00:00+00') TO ('2025-10-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m10; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m10 FOR VALUES FROM ('2025-10-01 00:00:00+00') TO ('2025-11-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m11; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m11 FOR VALUES FROM ('2025-11-01 00:00:00+00') TO ('2025-12-01 00:00:00+00');


--
-- Name: sighting_minutes_y2025m12; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2025m12 FOR VALUES FROM ('2025-12-01 00:00:00+00') TO ('2026-01-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m01; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m01 FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-02-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m02; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m02 FOR VALUES FROM ('2026-02-01 00:00:00+00') TO ('2026-03-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m03; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m03 FOR VALUES FROM ('2026-03-01 00:00:00+00') TO ('2026-04-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m04; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m04 FOR VALUES FROM ('2026-04-01 00:00:00+00') TO ('2026-05-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m05; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m05 FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m06; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m06 FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m07; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m07 FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m08; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m09; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m10; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m10 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m11; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m11 FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');


--
-- Name: sighting_minutes_y2026m12; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2026m12 FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m01; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m01 FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m02; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m02 FOR VALUES FROM ('2027-02-01 00:00:00+00') TO ('2027-03-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m03; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m03 FOR VALUES FROM ('2027-03-01 00:00:00+00') TO ('2027-04-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m04; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m04 FOR VALUES FROM ('2027-04-01 00:00:00+00') TO ('2027-05-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m05; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m05 FOR VALUES FROM ('2027-05-01 00:00:00+00') TO ('2027-06-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m06; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m06 FOR VALUES FROM ('2027-06-01 00:00:00+00') TO ('2027-07-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m07; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m07 FOR VALUES FROM ('2027-07-01 00:00:00+00') TO ('2027-08-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m08; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m08 FOR VALUES FROM ('2027-08-01 00:00:00+00') TO ('2027-09-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m09; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m09 FOR VALUES FROM ('2027-09-01 00:00:00+00') TO ('2027-10-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m10; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m10 FOR VALUES FROM ('2027-10-01 00:00:00+00') TO ('2027-11-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m11; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m11 FOR VALUES FROM ('2027-11-01 00:00:00+00') TO ('2027-12-01 00:00:00+00');


--
-- Name: sighting_minutes_y2027m12; Type: TABLE ATTACH; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes ATTACH PARTITION public.sighting_minutes_y2027m12 FOR VALUES FROM ('2027-12-01 00:00:00+00') TO ('2028-01-01 00:00:00+00');


--
-- Name: recipe_interaction_runs id; Type: DEFAULT; Schema: public
--

ALTER TABLE ONLY public.recipe_interaction_runs ALTER COLUMN id SET DEFAULT nextval('public.recipe_interaction_runs_id_seq'::regclass);


--
-- Name: scans_old id; Type: DEFAULT; Schema: public
--

ALTER TABLE ONLY public.scans_old ALTER COLUMN id SET DEFAULT nextval('public.scans_id_seq'::regclass);


--
-- Name: wigle_upload_batches id; Type: DEFAULT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batches ALTER COLUMN id SET DEFAULT nextval('public.wigle_upload_batches_id_seq'::regclass);


--
-- Name: ad_structures ad_structures_ad_type_data_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.ad_structures
    ADD CONSTRAINT ad_structures_ad_type_data_key UNIQUE (ad_type, data);


--
-- Name: ad_structures ad_structures_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.ad_structures
    ADD CONSTRAINT ad_structures_pkey PRIMARY KEY (id);


--
-- Name: adv_enrichments_old adv_enrichments_old_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.adv_enrichments_old
    ADD CONSTRAINT adv_enrichments_old_pkey PRIMARY KEY (addr, raw, enrichment_kind, enrichment_id, enrichment_revision);


--
-- Name: adv_enrichments adv_enrichments_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.adv_enrichments
    ADD CONSTRAINT adv_enrichments_pkey PRIMARY KEY (addr, payload_id, enrichment_kind, enrichment_id, enrichment_revision);


--
-- Name: advs_old advs_old_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.advs_old
    ADD CONSTRAINT advs_old_pkey PRIMARY KEY (addr, raw);


--
-- Name: blobs blobs_id_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.blobs
    ADD CONSTRAINT blobs_id_key UNIQUE (id);


--
-- Name: blobs blobs_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.blobs
    ADD CONSTRAINT blobs_pkey PRIMARY KEY (uri);


--
-- Name: gatt_attributes gatt_attributes_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.gatt_attributes
    ADD CONSTRAINT gatt_attributes_pkey PRIMARY KEY (discovery_id, attribute_id);


--
-- Name: gatt_discoveries gatt_discoveries_blob_id_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.gatt_discoveries
    ADD CONSTRAINT gatt_discoveries_blob_id_key UNIQUE (blob_id);


--
-- Name: gatt_discoveries gatt_discoveries_discovery_uuid_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.gatt_discoveries
    ADD CONSTRAINT gatt_discoveries_discovery_uuid_key UNIQUE (discovery_uuid);


--
-- Name: gatt_discoveries gatt_discoveries_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.gatt_discoveries
    ADD CONSTRAINT gatt_discoveries_pkey PRIMARY KEY (id);


--
-- Name: payloads payloads_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.payloads
    ADD CONSTRAINT payloads_pkey PRIMARY KEY (id);


--
-- Name: payloads payloads_sha256_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.payloads
    ADD CONSTRAINT payloads_sha256_key UNIQUE (sha256);


--
-- Name: projection_state_old projection_state_old_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.projection_state_old
    ADD CONSTRAINT projection_state_old_pkey PRIMARY KEY (name);


--
-- Name: recipe_interaction_events recipe_interaction_events_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.recipe_interaction_events
    ADD CONSTRAINT recipe_interaction_events_pkey PRIMARY KEY (run_id, seq);


--
-- Name: recipe_interaction_runs recipe_interaction_runs_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.recipe_interaction_runs
    ADD CONSTRAINT recipe_interaction_runs_pkey PRIMARY KEY (id);


--
-- Name: scans_old scans_accuracy_semantics; Type: CHECK CONSTRAINT; Schema: public
--

ALTER TABLE public.scans_old
    ADD CONSTRAINT scans_accuracy_semantics CHECK ((((accuracy IS NULL) OR (lat IS NOT NULL)) AND ((accuracy IS NULL) OR ((accuracy >= (0)::double precision) AND (accuracy < 'Infinity'::real))))) NOT VALID;


--
-- Name: scans_old scans_coordinates_paired; Type: CHECK CONSTRAINT; Schema: public
--

ALTER TABLE public.scans_old
    ADD CONSTRAINT scans_coordinates_paired CHECK (((lat IS NULL) = (lon IS NULL))) NOT VALID;


--
-- Name: scans_old scans_lat_range; Type: CHECK CONSTRAINT; Schema: public
--

ALTER TABLE public.scans_old
    ADD CONSTRAINT scans_lat_range CHECK (((lat IS NULL) OR ((lat >= ('-90'::integer)::double precision) AND (lat <= (90)::double precision)))) NOT VALID;


--
-- Name: scans_old scans_lon_range; Type: CHECK CONSTRAINT; Schema: public
--

ALTER TABLE public.scans_old
    ADD CONSTRAINT scans_lon_range CHECK (((lon IS NULL) OR ((lon >= ('-180'::integer)::double precision) AND (lon <= (180)::double precision)))) NOT VALID;


--
-- Name: scans_old scans_old_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.scans_old
    ADD CONSTRAINT scans_old_pkey PRIMARY KEY (id);


--
-- Name: schema_migrations schema_migrations_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.schema_migrations
    ADD CONSTRAINT schema_migrations_pkey PRIMARY KEY (version);


--
-- Name: sighting_minutes sighting_minutes_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes
    ADD CONSTRAINT sighting_minutes_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_default sighting_minutes_default_minute_addr_payload_id_uploader_id_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_default
    ADD CONSTRAINT sighting_minutes_default_minute_addr_payload_id_uploader_id_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes sighting_minutes_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes
    ADD CONSTRAINT sighting_minutes_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_default sighting_minutes_default_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_default
    ADD CONSTRAINT sighting_minutes_default_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m01 sighting_minutes_y2025m01_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m01
    ADD CONSTRAINT sighting_minutes_y2025m01_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m01 sighting_minutes_y2025m01_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m01
    ADD CONSTRAINT sighting_minutes_y2025m01_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m02 sighting_minutes_y2025m02_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m02
    ADD CONSTRAINT sighting_minutes_y2025m02_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m02 sighting_minutes_y2025m02_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m02
    ADD CONSTRAINT sighting_minutes_y2025m02_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m03 sighting_minutes_y2025m03_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m03
    ADD CONSTRAINT sighting_minutes_y2025m03_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m03 sighting_minutes_y2025m03_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m03
    ADD CONSTRAINT sighting_minutes_y2025m03_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m04 sighting_minutes_y2025m04_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m04
    ADD CONSTRAINT sighting_minutes_y2025m04_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m04 sighting_minutes_y2025m04_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m04
    ADD CONSTRAINT sighting_minutes_y2025m04_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m05 sighting_minutes_y2025m05_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m05
    ADD CONSTRAINT sighting_minutes_y2025m05_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m05 sighting_minutes_y2025m05_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m05
    ADD CONSTRAINT sighting_minutes_y2025m05_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m06 sighting_minutes_y2025m06_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m06
    ADD CONSTRAINT sighting_minutes_y2025m06_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m06 sighting_minutes_y2025m06_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m06
    ADD CONSTRAINT sighting_minutes_y2025m06_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m07 sighting_minutes_y2025m07_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m07
    ADD CONSTRAINT sighting_minutes_y2025m07_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m07 sighting_minutes_y2025m07_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m07
    ADD CONSTRAINT sighting_minutes_y2025m07_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m08 sighting_minutes_y2025m08_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m08
    ADD CONSTRAINT sighting_minutes_y2025m08_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m08 sighting_minutes_y2025m08_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m08
    ADD CONSTRAINT sighting_minutes_y2025m08_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m09 sighting_minutes_y2025m09_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m09
    ADD CONSTRAINT sighting_minutes_y2025m09_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m09 sighting_minutes_y2025m09_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m09
    ADD CONSTRAINT sighting_minutes_y2025m09_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m10 sighting_minutes_y2025m10_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m10
    ADD CONSTRAINT sighting_minutes_y2025m10_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m10 sighting_minutes_y2025m10_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m10
    ADD CONSTRAINT sighting_minutes_y2025m10_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m11 sighting_minutes_y2025m11_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m11
    ADD CONSTRAINT sighting_minutes_y2025m11_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m11 sighting_minutes_y2025m11_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m11
    ADD CONSTRAINT sighting_minutes_y2025m11_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2025m12 sighting_minutes_y2025m12_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m12
    ADD CONSTRAINT sighting_minutes_y2025m12_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2025m12 sighting_minutes_y2025m12_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2025m12
    ADD CONSTRAINT sighting_minutes_y2025m12_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m01 sighting_minutes_y2026m01_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m01
    ADD CONSTRAINT sighting_minutes_y2026m01_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m01 sighting_minutes_y2026m01_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m01
    ADD CONSTRAINT sighting_minutes_y2026m01_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m02 sighting_minutes_y2026m02_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m02
    ADD CONSTRAINT sighting_minutes_y2026m02_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m02 sighting_minutes_y2026m02_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m02
    ADD CONSTRAINT sighting_minutes_y2026m02_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m03 sighting_minutes_y2026m03_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m03
    ADD CONSTRAINT sighting_minutes_y2026m03_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m03 sighting_minutes_y2026m03_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m03
    ADD CONSTRAINT sighting_minutes_y2026m03_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m04 sighting_minutes_y2026m04_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m04
    ADD CONSTRAINT sighting_minutes_y2026m04_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m04 sighting_minutes_y2026m04_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m04
    ADD CONSTRAINT sighting_minutes_y2026m04_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m05 sighting_minutes_y2026m05_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m05
    ADD CONSTRAINT sighting_minutes_y2026m05_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m05 sighting_minutes_y2026m05_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m05
    ADD CONSTRAINT sighting_minutes_y2026m05_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m06 sighting_minutes_y2026m06_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m06
    ADD CONSTRAINT sighting_minutes_y2026m06_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m06 sighting_minutes_y2026m06_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m06
    ADD CONSTRAINT sighting_minutes_y2026m06_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m07 sighting_minutes_y2026m07_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m07
    ADD CONSTRAINT sighting_minutes_y2026m07_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m07 sighting_minutes_y2026m07_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m07
    ADD CONSTRAINT sighting_minutes_y2026m07_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m08 sighting_minutes_y2026m08_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m08
    ADD CONSTRAINT sighting_minutes_y2026m08_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m08 sighting_minutes_y2026m08_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m08
    ADD CONSTRAINT sighting_minutes_y2026m08_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m09 sighting_minutes_y2026m09_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m09
    ADD CONSTRAINT sighting_minutes_y2026m09_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m09 sighting_minutes_y2026m09_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m09
    ADD CONSTRAINT sighting_minutes_y2026m09_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m10 sighting_minutes_y2026m10_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m10
    ADD CONSTRAINT sighting_minutes_y2026m10_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m10 sighting_minutes_y2026m10_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m10
    ADD CONSTRAINT sighting_minutes_y2026m10_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m11 sighting_minutes_y2026m11_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m11
    ADD CONSTRAINT sighting_minutes_y2026m11_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m11 sighting_minutes_y2026m11_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m11
    ADD CONSTRAINT sighting_minutes_y2026m11_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2026m12 sighting_minutes_y2026m12_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m12
    ADD CONSTRAINT sighting_minutes_y2026m12_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2026m12 sighting_minutes_y2026m12_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2026m12
    ADD CONSTRAINT sighting_minutes_y2026m12_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m01 sighting_minutes_y2027m01_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m01
    ADD CONSTRAINT sighting_minutes_y2027m01_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m01 sighting_minutes_y2027m01_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m01
    ADD CONSTRAINT sighting_minutes_y2027m01_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m02 sighting_minutes_y2027m02_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m02
    ADD CONSTRAINT sighting_minutes_y2027m02_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m02 sighting_minutes_y2027m02_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m02
    ADD CONSTRAINT sighting_minutes_y2027m02_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m03 sighting_minutes_y2027m03_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m03
    ADD CONSTRAINT sighting_minutes_y2027m03_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m03 sighting_minutes_y2027m03_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m03
    ADD CONSTRAINT sighting_minutes_y2027m03_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m04 sighting_minutes_y2027m04_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m04
    ADD CONSTRAINT sighting_minutes_y2027m04_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m04 sighting_minutes_y2027m04_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m04
    ADD CONSTRAINT sighting_minutes_y2027m04_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m05 sighting_minutes_y2027m05_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m05
    ADD CONSTRAINT sighting_minutes_y2027m05_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m05 sighting_minutes_y2027m05_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m05
    ADD CONSTRAINT sighting_minutes_y2027m05_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m06 sighting_minutes_y2027m06_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m06
    ADD CONSTRAINT sighting_minutes_y2027m06_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m06 sighting_minutes_y2027m06_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m06
    ADD CONSTRAINT sighting_minutes_y2027m06_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m07 sighting_minutes_y2027m07_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m07
    ADD CONSTRAINT sighting_minutes_y2027m07_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m07 sighting_minutes_y2027m07_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m07
    ADD CONSTRAINT sighting_minutes_y2027m07_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m08 sighting_minutes_y2027m08_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m08
    ADD CONSTRAINT sighting_minutes_y2027m08_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m08 sighting_minutes_y2027m08_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m08
    ADD CONSTRAINT sighting_minutes_y2027m08_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m09 sighting_minutes_y2027m09_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m09
    ADD CONSTRAINT sighting_minutes_y2027m09_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m09 sighting_minutes_y2027m09_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m09
    ADD CONSTRAINT sighting_minutes_y2027m09_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m10 sighting_minutes_y2027m10_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m10
    ADD CONSTRAINT sighting_minutes_y2027m10_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m10 sighting_minutes_y2027m10_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m10
    ADD CONSTRAINT sighting_minutes_y2027m10_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m11 sighting_minutes_y2027m11_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m11
    ADD CONSTRAINT sighting_minutes_y2027m11_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m11 sighting_minutes_y2027m11_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m11
    ADD CONSTRAINT sighting_minutes_y2027m11_pkey PRIMARY KEY (id, minute);


--
-- Name: sighting_minutes_y2027m12 sighting_minutes_y2027m12_minute_addr_payload_id_uploader_i_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m12
    ADD CONSTRAINT sighting_minutes_y2027m12_minute_addr_payload_id_uploader_i_key UNIQUE NULLS NOT DISTINCT (minute, addr, payload_id, uploader_id, session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status);


--
-- Name: sighting_minutes_y2027m12 sighting_minutes_y2027m12_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.sighting_minutes_y2027m12
    ADD CONSTRAINT sighting_minutes_y2027m12_pkey PRIMARY KEY (id, minute);


--
-- Name: uploaders uploaders_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.uploaders
    ADD CONSTRAINT uploaders_pkey PRIMARY KEY (id);


--
-- Name: uploaders uploaders_uploader_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.uploaders
    ADD CONSTRAINT uploaders_uploader_key UNIQUE (uploader);


--
-- Name: wigle_upload_batch_minutes wigle_upload_batch_minutes_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batch_minutes
    ADD CONSTRAINT wigle_upload_batch_minutes_pkey PRIMARY KEY (batch_id, minute_id);


--
-- Name: wigle_upload_batch_scans_old wigle_upload_batch_scans_old_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batch_scans_old
    ADD CONSTRAINT wigle_upload_batch_scans_old_pkey PRIMARY KEY (batch_id, scan_id);


--
-- Name: wigle_upload_batches wigle_upload_batches_client_token_key; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batches
    ADD CONSTRAINT wigle_upload_batches_client_token_key UNIQUE (client_token);


--
-- Name: wigle_upload_batches wigle_upload_batches_pkey; Type: CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batches
    ADD CONSTRAINT wigle_upload_batches_pkey PRIMARY KEY (id);


--
-- Name: responses responses_pkey; Type: CONSTRAINT; Schema: wigle
--

ALTER TABLE ONLY wigle.responses
    ADD CONSTRAINT responses_pkey PRIMARY KEY (id);


--
-- Name: ad_structures_company_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX ad_structures_company_id_idx ON public.ad_structures USING btree (company_id) WHERE (company_id IS NOT NULL);


--
-- Name: ad_structures_local_name_idx; Type: INDEX; Schema: public
--

CREATE INDEX ad_structures_local_name_idx ON public.ad_structures USING btree (local_name) WHERE (local_name IS NOT NULL);


--
-- Name: ad_structures_uuids_idx; Type: INDEX; Schema: public
--

CREATE INDEX ad_structures_uuids_idx ON public.ad_structures USING gin (uuids) WHERE (uuids IS NOT NULL);


--
-- Name: adv_enrichments_data_idx; Type: INDEX; Schema: public
--

CREATE INDEX adv_enrichments_data_idx ON public.adv_enrichments USING gin (data jsonb_path_ops);


--
-- Name: adv_enrichments_enrichment_idx; Type: INDEX; Schema: public
--

CREATE INDEX adv_enrichments_enrichment_idx ON public.adv_enrichments USING btree (enrichment_kind, enrichment_id);


--
-- Name: adv_enrichments_old_data_idx; Type: INDEX; Schema: public
--

CREATE INDEX adv_enrichments_old_data_idx ON public.adv_enrichments_old USING gin (data jsonb_path_ops);


--
-- Name: adv_enrichments_old_enrichment_idx; Type: INDEX; Schema: public
--

CREATE INDEX adv_enrichments_old_enrichment_idx ON public.adv_enrichments_old USING btree (enrichment_kind, enrichment_id);


--
-- Name: advs_old_adv_types_idx; Type: INDEX; Schema: public
--

CREATE INDEX advs_old_adv_types_idx ON public.advs_old USING gin (adv_types);


--
-- Name: advs_old_bbox_idx; Type: INDEX; Schema: public
--

CREATE INDEX advs_old_bbox_idx ON public.advs_old USING gist (bbox);


--
-- Name: advs_old_centroid_idx; Type: INDEX; Schema: public
--

CREATE INDEX advs_old_centroid_idx ON public.advs_old USING gist (centroid);


--
-- Name: advs_old_last_seen_idx; Type: INDEX; Schema: public
--

CREATE INDEX advs_old_last_seen_idx ON public.advs_old USING btree (last_seen);


--
-- Name: advs_old_manufacturer_ids_idx; Type: INDEX; Schema: public
--

CREATE INDEX advs_old_manufacturer_ids_idx ON public.advs_old USING gin (manufacturer_ids);


--
-- Name: advs_old_scans_through_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX advs_old_scans_through_id_idx ON public.advs_old USING btree (scans_through_id);


--
-- Name: advs_old_service_data_uuids_idx; Type: INDEX; Schema: public
--

CREATE INDEX advs_old_service_data_uuids_idx ON public.advs_old USING gin (service_data_uuids);


--
-- Name: advs_old_service_uuids_idx; Type: INDEX; Schema: public
--

CREATE INDEX advs_old_service_uuids_idx ON public.advs_old USING gin (service_uuids);


--
-- Name: blobs_uploader_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX blobs_uploader_id_idx ON public.blobs USING btree (uploader_id);


--
-- Name: gatt_attributes_kind_uuid_idx; Type: INDEX; Schema: public
--

CREATE INDEX gatt_attributes_kind_uuid_idx ON public.gatt_attributes USING btree (kind, uuid);


--
-- Name: gatt_attributes_uuid_idx; Type: INDEX; Schema: public
--

CREATE INDEX gatt_attributes_uuid_idx ON public.gatt_attributes USING btree (uuid);


--
-- Name: gatt_discoveries_addr_idx; Type: INDEX; Schema: public
--

CREATE INDEX gatt_discoveries_addr_idx ON public.gatt_discoveries USING btree (addr);


--
-- Name: gatt_discoveries_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX gatt_discoveries_location_idx ON public.gatt_discoveries USING gist (location);


--
-- Name: gatt_discoveries_profile_hash_idx; Type: INDEX; Schema: public
--

CREATE INDEX gatt_discoveries_profile_hash_idx ON public.gatt_discoveries USING btree (profile_hash);


--
-- Name: gatt_discoveries_started_at_idx; Type: INDEX; Schema: public
--

CREATE INDEX gatt_discoveries_started_at_idx ON public.gatt_discoveries USING btree (started_at);


--
-- Name: gatt_discoveries_trigger_payload_idx; Type: INDEX; Schema: public
--

CREATE INDEX gatt_discoveries_trigger_payload_idx ON public.gatt_discoveries USING btree (trigger_payload_id) WHERE (trigger_payload_id IS NOT NULL);


--
-- Name: payloads_last_seen_idx; Type: INDEX; Schema: public
--

CREATE INDEX payloads_last_seen_idx ON public.payloads USING btree (last_seen);


--
-- Name: payloads_structure_ids_idx; Type: INDEX; Schema: public
--

CREATE INDEX payloads_structure_ids_idx ON public.payloads USING gin (structure_ids);


--
-- Name: recipe_interaction_events_event_idx; Type: INDEX; Schema: public
--

CREATE INDEX recipe_interaction_events_event_idx ON public.recipe_interaction_events USING btree (event);


--
-- Name: recipe_interaction_runs_addr_started_at_idx; Type: INDEX; Schema: public
--

CREATE INDEX recipe_interaction_runs_addr_started_at_idx ON public.recipe_interaction_runs USING btree (addr, started_at);


--
-- Name: recipe_interaction_runs_recipe_idx; Type: INDEX; Schema: public
--

CREATE INDEX recipe_interaction_runs_recipe_idx ON public.recipe_interaction_runs USING btree (recipe_id, recipe_sha256);


--
-- Name: scans_old_addr_idx; Type: INDEX; Schema: public
--

CREATE INDEX scans_old_addr_idx ON public.scans_old USING btree (addr);


--
-- Name: scans_old_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX scans_old_location_idx ON public.scans_old USING gist (location);


--
-- Name: scans_old_scanned_at_idx; Type: INDEX; Schema: public
--

CREATE INDEX scans_old_scanned_at_idx ON public.scans_old USING btree (scanned_at);


--
-- Name: sighting_minutes_addr_payload_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_addr_payload_idx ON ONLY public.sighting_minutes USING btree (addr, payload_id);


--
-- Name: sighting_minutes_default_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_default_addr_payload_id_idx ON public.sighting_minutes_default USING btree (addr, payload_id);


--
-- Name: sighting_minutes_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_location_idx ON ONLY public.sighting_minutes USING gist (location);


--
-- Name: sighting_minutes_default_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_default_location_idx ON public.sighting_minutes_default USING gist (location);


--
-- Name: sighting_minutes_y2025m01_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m01_addr_payload_id_idx ON public.sighting_minutes_y2025m01 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m01_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m01_location_idx ON public.sighting_minutes_y2025m01 USING gist (location);


--
-- Name: sighting_minutes_y2025m02_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m02_addr_payload_id_idx ON public.sighting_minutes_y2025m02 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m02_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m02_location_idx ON public.sighting_minutes_y2025m02 USING gist (location);


--
-- Name: sighting_minutes_y2025m03_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m03_addr_payload_id_idx ON public.sighting_minutes_y2025m03 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m03_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m03_location_idx ON public.sighting_minutes_y2025m03 USING gist (location);


--
-- Name: sighting_minutes_y2025m04_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m04_addr_payload_id_idx ON public.sighting_minutes_y2025m04 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m04_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m04_location_idx ON public.sighting_minutes_y2025m04 USING gist (location);


--
-- Name: sighting_minutes_y2025m05_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m05_addr_payload_id_idx ON public.sighting_minutes_y2025m05 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m05_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m05_location_idx ON public.sighting_minutes_y2025m05 USING gist (location);


--
-- Name: sighting_minutes_y2025m06_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m06_addr_payload_id_idx ON public.sighting_minutes_y2025m06 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m06_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m06_location_idx ON public.sighting_minutes_y2025m06 USING gist (location);


--
-- Name: sighting_minutes_y2025m07_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m07_addr_payload_id_idx ON public.sighting_minutes_y2025m07 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m07_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m07_location_idx ON public.sighting_minutes_y2025m07 USING gist (location);


--
-- Name: sighting_minutes_y2025m08_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m08_addr_payload_id_idx ON public.sighting_minutes_y2025m08 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m08_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m08_location_idx ON public.sighting_minutes_y2025m08 USING gist (location);


--
-- Name: sighting_minutes_y2025m09_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m09_addr_payload_id_idx ON public.sighting_minutes_y2025m09 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m09_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m09_location_idx ON public.sighting_minutes_y2025m09 USING gist (location);


--
-- Name: sighting_minutes_y2025m10_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m10_addr_payload_id_idx ON public.sighting_minutes_y2025m10 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m10_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m10_location_idx ON public.sighting_minutes_y2025m10 USING gist (location);


--
-- Name: sighting_minutes_y2025m11_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m11_addr_payload_id_idx ON public.sighting_minutes_y2025m11 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m11_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m11_location_idx ON public.sighting_minutes_y2025m11 USING gist (location);


--
-- Name: sighting_minutes_y2025m12_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m12_addr_payload_id_idx ON public.sighting_minutes_y2025m12 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2025m12_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2025m12_location_idx ON public.sighting_minutes_y2025m12 USING gist (location);


--
-- Name: sighting_minutes_y2026m01_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m01_addr_payload_id_idx ON public.sighting_minutes_y2026m01 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m01_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m01_location_idx ON public.sighting_minutes_y2026m01 USING gist (location);


--
-- Name: sighting_minutes_y2026m02_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m02_addr_payload_id_idx ON public.sighting_minutes_y2026m02 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m02_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m02_location_idx ON public.sighting_minutes_y2026m02 USING gist (location);


--
-- Name: sighting_minutes_y2026m03_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m03_addr_payload_id_idx ON public.sighting_minutes_y2026m03 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m03_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m03_location_idx ON public.sighting_minutes_y2026m03 USING gist (location);


--
-- Name: sighting_minutes_y2026m04_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m04_addr_payload_id_idx ON public.sighting_minutes_y2026m04 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m04_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m04_location_idx ON public.sighting_minutes_y2026m04 USING gist (location);


--
-- Name: sighting_minutes_y2026m05_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m05_addr_payload_id_idx ON public.sighting_minutes_y2026m05 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m05_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m05_location_idx ON public.sighting_minutes_y2026m05 USING gist (location);


--
-- Name: sighting_minutes_y2026m06_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m06_addr_payload_id_idx ON public.sighting_minutes_y2026m06 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m06_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m06_location_idx ON public.sighting_minutes_y2026m06 USING gist (location);


--
-- Name: sighting_minutes_y2026m07_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m07_addr_payload_id_idx ON public.sighting_minutes_y2026m07 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m07_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m07_location_idx ON public.sighting_minutes_y2026m07 USING gist (location);


--
-- Name: sighting_minutes_y2026m08_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m08_addr_payload_id_idx ON public.sighting_minutes_y2026m08 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m08_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m08_location_idx ON public.sighting_minutes_y2026m08 USING gist (location);


--
-- Name: sighting_minutes_y2026m09_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m09_addr_payload_id_idx ON public.sighting_minutes_y2026m09 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m09_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m09_location_idx ON public.sighting_minutes_y2026m09 USING gist (location);


--
-- Name: sighting_minutes_y2026m10_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m10_addr_payload_id_idx ON public.sighting_minutes_y2026m10 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m10_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m10_location_idx ON public.sighting_minutes_y2026m10 USING gist (location);


--
-- Name: sighting_minutes_y2026m11_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m11_addr_payload_id_idx ON public.sighting_minutes_y2026m11 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m11_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m11_location_idx ON public.sighting_minutes_y2026m11 USING gist (location);


--
-- Name: sighting_minutes_y2026m12_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m12_addr_payload_id_idx ON public.sighting_minutes_y2026m12 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2026m12_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2026m12_location_idx ON public.sighting_minutes_y2026m12 USING gist (location);


--
-- Name: sighting_minutes_y2027m01_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m01_addr_payload_id_idx ON public.sighting_minutes_y2027m01 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m01_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m01_location_idx ON public.sighting_minutes_y2027m01 USING gist (location);


--
-- Name: sighting_minutes_y2027m02_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m02_addr_payload_id_idx ON public.sighting_minutes_y2027m02 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m02_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m02_location_idx ON public.sighting_minutes_y2027m02 USING gist (location);


--
-- Name: sighting_minutes_y2027m03_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m03_addr_payload_id_idx ON public.sighting_minutes_y2027m03 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m03_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m03_location_idx ON public.sighting_minutes_y2027m03 USING gist (location);


--
-- Name: sighting_minutes_y2027m04_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m04_addr_payload_id_idx ON public.sighting_minutes_y2027m04 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m04_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m04_location_idx ON public.sighting_minutes_y2027m04 USING gist (location);


--
-- Name: sighting_minutes_y2027m05_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m05_addr_payload_id_idx ON public.sighting_minutes_y2027m05 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m05_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m05_location_idx ON public.sighting_minutes_y2027m05 USING gist (location);


--
-- Name: sighting_minutes_y2027m06_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m06_addr_payload_id_idx ON public.sighting_minutes_y2027m06 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m06_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m06_location_idx ON public.sighting_minutes_y2027m06 USING gist (location);


--
-- Name: sighting_minutes_y2027m07_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m07_addr_payload_id_idx ON public.sighting_minutes_y2027m07 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m07_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m07_location_idx ON public.sighting_minutes_y2027m07 USING gist (location);


--
-- Name: sighting_minutes_y2027m08_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m08_addr_payload_id_idx ON public.sighting_minutes_y2027m08 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m08_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m08_location_idx ON public.sighting_minutes_y2027m08 USING gist (location);


--
-- Name: sighting_minutes_y2027m09_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m09_addr_payload_id_idx ON public.sighting_minutes_y2027m09 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m09_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m09_location_idx ON public.sighting_minutes_y2027m09 USING gist (location);


--
-- Name: sighting_minutes_y2027m10_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m10_addr_payload_id_idx ON public.sighting_minutes_y2027m10 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m10_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m10_location_idx ON public.sighting_minutes_y2027m10 USING gist (location);


--
-- Name: sighting_minutes_y2027m11_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m11_addr_payload_id_idx ON public.sighting_minutes_y2027m11 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m11_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m11_location_idx ON public.sighting_minutes_y2027m11 USING gist (location);


--
-- Name: sighting_minutes_y2027m12_addr_payload_id_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m12_addr_payload_id_idx ON public.sighting_minutes_y2027m12 USING btree (addr, payload_id);


--
-- Name: sighting_minutes_y2027m12_location_idx; Type: INDEX; Schema: public
--

CREATE INDEX sighting_minutes_y2027m12_location_idx ON public.sighting_minutes_y2027m12 USING gist (location);


--
-- Name: wigle_upload_batch_minutes_minute_idx; Type: INDEX; Schema: public
--

CREATE INDEX wigle_upload_batch_minutes_minute_idx ON public.wigle_upload_batch_minutes USING btree (minute_id, minute);


--
-- Name: wigle_upload_batch_scans_old_scan_idx; Type: INDEX; Schema: public
--

CREATE INDEX wigle_upload_batch_scans_old_scan_idx ON public.wigle_upload_batch_scans_old USING btree (scan_id);


--
-- Name: wigle_upload_batches_status_idx; Type: INDEX; Schema: public
--

CREATE INDEX wigle_upload_batches_status_idx ON public.wigle_upload_batches USING btree (status, id);


--
-- Name: responses_called_at_idx; Type: INDEX; Schema: wigle
--

CREATE INDEX responses_called_at_idx ON wigle.responses USING btree (called_at);


--
-- Name: responses_params_gin_idx; Type: INDEX; Schema: wigle
--

CREATE INDEX responses_params_gin_idx ON wigle.responses USING gin (params jsonb_path_ops);


--
-- Name: responses_params_page_idx; Type: INDEX; Schema: wigle
--

CREATE INDEX responses_params_page_idx ON wigle.responses USING btree (params_hash, search_after);


--
-- Name: sighting_minutes_default_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_default_addr_payload_id_idx;


--
-- Name: sighting_minutes_default_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_default_location_idx;


--
-- Name: sighting_minutes_default_minute_addr_payload_id_uploader_id_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_default_minute_addr_payload_id_uploader_id_key;


--
-- Name: sighting_minutes_default_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_default_pkey;


--
-- Name: sighting_minutes_y2025m01_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m01_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m01_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m01_location_idx;


--
-- Name: sighting_minutes_y2025m01_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m01_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m01_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m01_pkey;


--
-- Name: sighting_minutes_y2025m02_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m02_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m02_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m02_location_idx;


--
-- Name: sighting_minutes_y2025m02_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m02_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m02_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m02_pkey;


--
-- Name: sighting_minutes_y2025m03_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m03_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m03_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m03_location_idx;


--
-- Name: sighting_minutes_y2025m03_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m03_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m03_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m03_pkey;


--
-- Name: sighting_minutes_y2025m04_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m04_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m04_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m04_location_idx;


--
-- Name: sighting_minutes_y2025m04_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m04_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m04_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m04_pkey;


--
-- Name: sighting_minutes_y2025m05_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m05_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m05_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m05_location_idx;


--
-- Name: sighting_minutes_y2025m05_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m05_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m05_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m05_pkey;


--
-- Name: sighting_minutes_y2025m06_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m06_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m06_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m06_location_idx;


--
-- Name: sighting_minutes_y2025m06_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m06_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m06_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m06_pkey;


--
-- Name: sighting_minutes_y2025m07_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m07_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m07_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m07_location_idx;


--
-- Name: sighting_minutes_y2025m07_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m07_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m07_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m07_pkey;


--
-- Name: sighting_minutes_y2025m08_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m08_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m08_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m08_location_idx;


--
-- Name: sighting_minutes_y2025m08_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m08_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m08_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m08_pkey;


--
-- Name: sighting_minutes_y2025m09_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m09_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m09_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m09_location_idx;


--
-- Name: sighting_minutes_y2025m09_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m09_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m09_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m09_pkey;


--
-- Name: sighting_minutes_y2025m10_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m10_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m10_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m10_location_idx;


--
-- Name: sighting_minutes_y2025m10_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m10_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m10_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m10_pkey;


--
-- Name: sighting_minutes_y2025m11_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m11_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m11_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m11_location_idx;


--
-- Name: sighting_minutes_y2025m11_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m11_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m11_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m11_pkey;


--
-- Name: sighting_minutes_y2025m12_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2025m12_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2025m12_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2025m12_location_idx;


--
-- Name: sighting_minutes_y2025m12_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2025m12_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2025m12_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2025m12_pkey;


--
-- Name: sighting_minutes_y2026m01_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m01_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m01_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m01_location_idx;


--
-- Name: sighting_minutes_y2026m01_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m01_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m01_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m01_pkey;


--
-- Name: sighting_minutes_y2026m02_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m02_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m02_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m02_location_idx;


--
-- Name: sighting_minutes_y2026m02_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m02_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m02_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m02_pkey;


--
-- Name: sighting_minutes_y2026m03_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m03_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m03_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m03_location_idx;


--
-- Name: sighting_minutes_y2026m03_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m03_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m03_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m03_pkey;


--
-- Name: sighting_minutes_y2026m04_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m04_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m04_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m04_location_idx;


--
-- Name: sighting_minutes_y2026m04_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m04_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m04_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m04_pkey;


--
-- Name: sighting_minutes_y2026m05_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m05_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m05_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m05_location_idx;


--
-- Name: sighting_minutes_y2026m05_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m05_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m05_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m05_pkey;


--
-- Name: sighting_minutes_y2026m06_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m06_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m06_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m06_location_idx;


--
-- Name: sighting_minutes_y2026m06_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m06_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m06_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m06_pkey;


--
-- Name: sighting_minutes_y2026m07_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m07_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m07_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m07_location_idx;


--
-- Name: sighting_minutes_y2026m07_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m07_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m07_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m07_pkey;


--
-- Name: sighting_minutes_y2026m08_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m08_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m08_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m08_location_idx;


--
-- Name: sighting_minutes_y2026m08_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m08_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m08_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m08_pkey;


--
-- Name: sighting_minutes_y2026m09_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m09_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m09_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m09_location_idx;


--
-- Name: sighting_minutes_y2026m09_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m09_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m09_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m09_pkey;


--
-- Name: sighting_minutes_y2026m10_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m10_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m10_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m10_location_idx;


--
-- Name: sighting_minutes_y2026m10_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m10_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m10_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m10_pkey;


--
-- Name: sighting_minutes_y2026m11_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m11_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m11_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m11_location_idx;


--
-- Name: sighting_minutes_y2026m11_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m11_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m11_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m11_pkey;


--
-- Name: sighting_minutes_y2026m12_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2026m12_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2026m12_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2026m12_location_idx;


--
-- Name: sighting_minutes_y2026m12_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2026m12_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2026m12_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2026m12_pkey;


--
-- Name: sighting_minutes_y2027m01_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m01_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m01_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m01_location_idx;


--
-- Name: sighting_minutes_y2027m01_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m01_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m01_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m01_pkey;


--
-- Name: sighting_minutes_y2027m02_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m02_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m02_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m02_location_idx;


--
-- Name: sighting_minutes_y2027m02_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m02_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m02_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m02_pkey;


--
-- Name: sighting_minutes_y2027m03_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m03_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m03_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m03_location_idx;


--
-- Name: sighting_minutes_y2027m03_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m03_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m03_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m03_pkey;


--
-- Name: sighting_minutes_y2027m04_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m04_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m04_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m04_location_idx;


--
-- Name: sighting_minutes_y2027m04_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m04_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m04_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m04_pkey;


--
-- Name: sighting_minutes_y2027m05_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m05_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m05_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m05_location_idx;


--
-- Name: sighting_minutes_y2027m05_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m05_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m05_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m05_pkey;


--
-- Name: sighting_minutes_y2027m06_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m06_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m06_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m06_location_idx;


--
-- Name: sighting_minutes_y2027m06_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m06_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m06_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m06_pkey;


--
-- Name: sighting_minutes_y2027m07_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m07_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m07_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m07_location_idx;


--
-- Name: sighting_minutes_y2027m07_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m07_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m07_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m07_pkey;


--
-- Name: sighting_minutes_y2027m08_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m08_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m08_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m08_location_idx;


--
-- Name: sighting_minutes_y2027m08_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m08_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m08_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m08_pkey;


--
-- Name: sighting_minutes_y2027m09_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m09_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m09_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m09_location_idx;


--
-- Name: sighting_minutes_y2027m09_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m09_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m09_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m09_pkey;


--
-- Name: sighting_minutes_y2027m10_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m10_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m10_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m10_location_idx;


--
-- Name: sighting_minutes_y2027m10_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m10_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m10_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m10_pkey;


--
-- Name: sighting_minutes_y2027m11_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m11_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m11_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m11_location_idx;


--
-- Name: sighting_minutes_y2027m11_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m11_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m11_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m11_pkey;


--
-- Name: sighting_minutes_y2027m12_addr_payload_id_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_addr_payload_idx ATTACH PARTITION public.sighting_minutes_y2027m12_addr_payload_id_idx;


--
-- Name: sighting_minutes_y2027m12_location_idx; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_location_idx ATTACH PARTITION public.sighting_minutes_y2027m12_location_idx;


--
-- Name: sighting_minutes_y2027m12_minute_addr_payload_id_uploader_i_key; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_key ATTACH PARTITION public.sighting_minutes_y2027m12_minute_addr_payload_id_uploader_i_key;


--
-- Name: sighting_minutes_y2027m12_pkey; Type: INDEX ATTACH; Schema: public
--

ALTER INDEX public.sighting_minutes_pkey ATTACH PARTITION public.sighting_minutes_y2027m12_pkey;


--
-- Name: gatt_discoveries gatt_discoveries_set_location; Type: TRIGGER; Schema: public
--

CREATE TRIGGER gatt_discoveries_set_location BEFORE INSERT OR UPDATE ON public.gatt_discoveries FOR EACH ROW EXECUTE FUNCTION public.set_location();


--
-- Name: scans_old scans_set_location; Type: TRIGGER; Schema: public
--

CREATE TRIGGER scans_set_location BEFORE INSERT OR UPDATE ON public.scans_old FOR EACH ROW EXECUTE FUNCTION public.set_location();


--
-- Name: adv_enrichments adv_enrichments_payload_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.adv_enrichments
    ADD CONSTRAINT adv_enrichments_payload_id_fkey FOREIGN KEY (payload_id) REFERENCES public.payloads(id);


--
-- Name: blobs blobs_uploader_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.blobs
    ADD CONSTRAINT blobs_uploader_id_fkey FOREIGN KEY (uploader_id) REFERENCES public.uploaders(id);


--
-- Name: gatt_attributes gatt_attributes_discovery_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.gatt_attributes
    ADD CONSTRAINT gatt_attributes_discovery_id_fkey FOREIGN KEY (discovery_id) REFERENCES public.gatt_discoveries(id) ON DELETE CASCADE;


--
-- Name: gatt_attributes gatt_attributes_discovery_id_parent_attribute_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.gatt_attributes
    ADD CONSTRAINT gatt_attributes_discovery_id_parent_attribute_id_fkey FOREIGN KEY (discovery_id, parent_attribute_id) REFERENCES public.gatt_attributes(discovery_id, attribute_id);


--
-- Name: gatt_discoveries gatt_discoveries_blob_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.gatt_discoveries
    ADD CONSTRAINT gatt_discoveries_blob_id_fkey FOREIGN KEY (blob_id) REFERENCES public.blobs(id);


--
-- Name: gatt_discoveries gatt_discoveries_trigger_payload_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.gatt_discoveries
    ADD CONSTRAINT gatt_discoveries_trigger_payload_id_fkey FOREIGN KEY (trigger_payload_id) REFERENCES public.payloads(id);


--
-- Name: recipe_interaction_events recipe_interaction_events_run_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.recipe_interaction_events
    ADD CONSTRAINT recipe_interaction_events_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.recipe_interaction_runs(id) ON DELETE CASCADE;


--
-- Name: scans_old scans_blob_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.scans_old
    ADD CONSTRAINT scans_blob_fkey FOREIGN KEY (blob) REFERENCES public.blobs(uri);


--
-- Name: sighting_minutes sighting_minutes_payload_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE public.sighting_minutes
    ADD CONSTRAINT sighting_minutes_payload_id_fkey FOREIGN KEY (payload_id) REFERENCES public.payloads(id);


--
-- Name: sighting_minutes sighting_minutes_uploader_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE public.sighting_minutes
    ADD CONSTRAINT sighting_minutes_uploader_id_fkey FOREIGN KEY (uploader_id) REFERENCES public.uploaders(id);


--
-- Name: wigle_upload_batch_minutes wigle_upload_batch_minutes_batch_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batch_minutes
    ADD CONSTRAINT wigle_upload_batch_minutes_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES public.wigle_upload_batches(id) ON DELETE CASCADE;


--
-- Name: wigle_upload_batch_minutes wigle_upload_batch_minutes_minute_id_minute_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batch_minutes
    ADD CONSTRAINT wigle_upload_batch_minutes_minute_id_minute_fkey FOREIGN KEY (minute_id, minute) REFERENCES public.sighting_minutes(id, minute) ON DELETE RESTRICT;


--
-- Name: wigle_upload_batch_scans_old wigle_upload_batch_scans_batch_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batch_scans_old
    ADD CONSTRAINT wigle_upload_batch_scans_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES public.wigle_upload_batches(id) ON DELETE CASCADE;


--
-- Name: wigle_upload_batch_scans_old wigle_upload_batch_scans_scan_id_fkey; Type: FK CONSTRAINT; Schema: public
--

ALTER TABLE ONLY public.wigle_upload_batch_scans_old
    ADD CONSTRAINT wigle_upload_batch_scans_scan_id_fkey FOREIGN KEY (scan_id) REFERENCES public.scans_old(id) ON DELETE RESTRICT;


--
--
