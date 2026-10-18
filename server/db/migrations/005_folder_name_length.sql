-- Preserve the existing mobile editor's 200-character folder-name contract.
ALTER TABLE work_folders DROP CONSTRAINT work_folders_name_check;
ALTER TABLE work_folders ADD CONSTRAINT work_folders_name_check CHECK(length(trim(name)) BETWEEN 1 AND 200);
