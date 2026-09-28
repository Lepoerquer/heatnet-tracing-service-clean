-- M1: хранение загрузок и объектов ingest в PostGIS (GiST)

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS upload_session (
    id              UUID PRIMARY KEY,
    original_filename VARCHAR(512) NOT NULL,
    stored_path     VARCHAR(1024) NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    report_json     JSONB NOT NULL,
    feature_count   INT NOT NULL DEFAULT 0,
    warning_count   INT NOT NULL DEFAULT 0,
    error_count     INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ingested_feature (
    id                BIGSERIAL PRIMARY KEY,
    session_id        UUID NOT NULL REFERENCES upload_session(id) ON DELETE CASCADE,
    external_id       VARCHAR(64) NOT NULL,
    object_type       VARCHAR(64) NOT NULL,
    restriction_type  VARCHAR(64),
    properties        JSONB,
    geom_wgs84        GEOMETRY(Geometry, 4326),
    geom_utm          GEOMETRY(Geometry, 32637),
    diameter          INT,
    flow_tph          DOUBLE PRECISION,
    heat_load         DOUBLE PRECISION,
    upstream_object_id VARCHAR(64),
    reference_only    BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_ingested_feature_geom_wgs84
    ON ingested_feature USING GIST (geom_wgs84);
CREATE INDEX IF NOT EXISTS idx_ingested_feature_geom_utm
    ON ingested_feature USING GIST (geom_utm);
CREATE INDEX IF NOT EXISTS idx_ingested_feature_session
    ON ingested_feature (session_id);
CREATE INDEX IF NOT EXISTS idx_ingested_feature_type
    ON ingested_feature (object_type);

CREATE TABLE IF NOT EXISTS ingested_network_segment (
    id                 BIGSERIAL PRIMARY KEY,
    session_id         UUID NOT NULL REFERENCES upload_session(id) ON DELETE CASCADE,
    external_id        VARCHAR(64) NOT NULL,
    diameter           INT NOT NULL,
    flow_tph           DOUBLE PRECISION,
    upstream_object_id VARCHAR(64),
    length_m           DOUBLE PRECISION NOT NULL,
    geom_utm           GEOMETRY(LineString, 32637)
);

CREATE INDEX IF NOT EXISTS idx_ingested_network_segment_geom
    ON ingested_network_segment USING GIST (geom_utm);
CREATE INDEX IF NOT EXISTS idx_ingested_network_segment_session
    ON ingested_network_segment (session_id);

CREATE TABLE IF NOT EXISTS ingested_network_chamber (
    id                 BIGSERIAL PRIMARY KEY,
    session_id         UUID NOT NULL REFERENCES upload_session(id) ON DELETE CASCADE,
    external_id        VARCHAR(64) NOT NULL,
    diameter           INT,
    upstream_object_id VARCHAR(64),
    geom_utm           GEOMETRY(Point, 32637)
);

CREATE INDEX IF NOT EXISTS idx_ingested_network_chamber_geom
    ON ingested_network_chamber USING GIST (geom_utm);
CREATE INDEX IF NOT EXISTS idx_ingested_network_chamber_session
    ON ingested_network_chamber (session_id);

CREATE TABLE IF NOT EXISTS ingested_source (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES upload_session(id) ON DELETE CASCADE,
    external_id VARCHAR(64) NOT NULL,
    geom_wgs84  GEOMETRY(Point, 4326)
);

CREATE INDEX IF NOT EXISTS idx_ingested_source_geom
    ON ingested_source USING GIST (geom_wgs84);
