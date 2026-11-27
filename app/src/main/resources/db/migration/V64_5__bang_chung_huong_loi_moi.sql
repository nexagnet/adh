-- Keep state values compatible with older app images. Proof applies only to the timestamp it was read for.
ALTER TABLE zalo_friend_observation ADD COLUMN outgoing_verified_at timestamptz;
ALTER TABLE zalo_friend_observation_event ADD COLUMN outgoing_verified boolean NOT NULL DEFAULT false;
