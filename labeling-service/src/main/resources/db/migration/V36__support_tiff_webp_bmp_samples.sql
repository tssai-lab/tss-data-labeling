-- Additive format support; existing samples and their hashes remain unchanged.
ALTER TABLE sample DROP CONSTRAINT sample_media_type_check;
ALTER TABLE sample ADD CONSTRAINT sample_media_type_check
    CHECK (media_type IN ('image/jpeg','image/png','image/tiff','image/webp','image/bmp','text/csv','application/json'));
