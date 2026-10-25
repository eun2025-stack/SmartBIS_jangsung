-- Enable automatic registration for SmartBIS agent IDs.
-- Run only against the Headwind MDM database after backing it up.
-- The Android agent and Nginx enrollment route enforce BIS_JS_NNN format;
-- devices.number remains uniquely constrained by Headwind.
BEGIN;

DO $$
DECLARE
    default_configuration_id integer;
BEGIN
    SELECT id
      INTO default_configuration_id
      FROM public.configurations
     WHERE customerid = 1
       AND name = 'Common - Minimal'
     ORDER BY id
     LIMIT 1;

    IF default_configuration_id IS NULL THEN
        RAISE EXCEPTION 'Headwind configuration "Common - Minimal" was not found for customer 1';
    END IF;

    UPDATE public.settings
       SET createnewdevices = TRUE,
           newdeviceconfigurationid = default_configuration_id
     WHERE customerid = 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Headwind settings row for customer 1 was not found';
    END IF;
END $$;

COMMIT;
