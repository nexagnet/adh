ALTER TABLE zalo_shared_group_join ADD COLUMN actor_group_id varchar(32);
UPDATE zalo_shared_group_join SET actor_group_id = owner_group_id;
ALTER TABLE zalo_shared_group_join ALTER COLUMN actor_group_id SET NOT NULL;
ALTER TABLE zalo_shared_group_join ADD CHECK (actor_group_id ~ '^[0-9]{1,32}$');
