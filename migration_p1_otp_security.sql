-- P1-1 Migration: Secure OTP lifecycle fields
-- Adds otp_created_at for expiration checks and otp_failed_attempts for rate-limiting

ALTER TABLE orders
ADD COLUMN IF NOT EXISTS otp_created_at TIMESTAMP WITH TIME ZONE,
ADD COLUMN IF NOT EXISTS otp_failed_attempts INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;

-- Reset failed attempts when a new OTP is generated (handled in application layer,
-- but this default ensures clean state for existing rows)
UPDATE orders SET otp_failed_attempts = 0 WHERE otp_failed_attempts IS NULL;
UPDATE orders SET version = 0 WHERE version IS NULL;

