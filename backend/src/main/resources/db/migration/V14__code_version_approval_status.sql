ALTER TABLE code_version
    ADD COLUMN IF NOT EXISTS approval_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';

UPDATE code_version
SET approval_status = 'APPROVED'
WHERE id = 'code-ver-consistency-test-v1';
