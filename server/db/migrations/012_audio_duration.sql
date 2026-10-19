-- Measured PCM duration only. Legacy and typed utterances stay NULL.
ALTER TABLE utterances ADD COLUMN audio_duration_ms integer
  CHECK (audio_duration_ms BETWEEN 1 AND 30000);
