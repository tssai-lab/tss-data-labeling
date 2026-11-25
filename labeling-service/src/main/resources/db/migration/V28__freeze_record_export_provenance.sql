-- Preserve the exact reviewer/author metadata together with the accepted snapshot.
-- Historical snapshots keep NULL rather than guessing from a later mutable review.
ALTER TABLE final_snapshot_item ADD COLUMN annotation_provenance JSONB;
