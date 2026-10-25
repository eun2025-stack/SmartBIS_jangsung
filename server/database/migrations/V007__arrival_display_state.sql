-- Remember the last upcoming stop shown so one active arrival prediction
-- does not monopolize the display API. Additive and safe for existing rows.
BEGIN;

ALTER TABLE display_states
    ADD COLUMN IF NOT EXISTS arrival_last_display_key VARCHAR(150),
    ADD COLUMN IF NOT EXISTS arrival_last_displayed_at TIMESTAMPTZ;

COMMIT;
