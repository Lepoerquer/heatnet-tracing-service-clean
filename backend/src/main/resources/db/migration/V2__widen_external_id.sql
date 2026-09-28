-- F17: внешние id датасета могут быть длиннее 64 символов
ALTER TABLE ingested_feature ALTER COLUMN external_id TYPE VARCHAR(256);
ALTER TABLE ingested_network_segment ALTER COLUMN external_id TYPE VARCHAR(256);
ALTER TABLE ingested_network_chamber ALTER COLUMN external_id TYPE VARCHAR(256);
ALTER TABLE ingested_source ALTER COLUMN external_id TYPE VARCHAR(256);
ALTER TABLE ingested_feature ALTER COLUMN object_type TYPE VARCHAR(128);
ALTER TABLE ingested_feature ALTER COLUMN restriction_type TYPE VARCHAR(128);
