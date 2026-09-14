-- P3-1 Migration: Audit trail integrity
-- Change order_audit_logs FK from ON DELETE CASCADE to ON DELETE RESTRICT
-- so that deleting an order is blocked if audit logs exist (preserving evidence)

-- Drop the existing FK constraint and re-create with RESTRICT
ALTER TABLE order_audit_logs
  DROP CONSTRAINT IF EXISTS order_audit_logs_order_id_fkey;

ALTER TABLE order_audit_logs
  ADD CONSTRAINT order_audit_logs_order_id_fkey
  FOREIGN KEY (order_id)
  REFERENCES orders(order_id)
  ON DELETE RESTRICT;
