-- Fix default values for dolphin task parameters
-- Units: timeout_seconds is in minutes for DolphinScheduler, retry_interval is in minutes

-- First, update existing data that has old default values
-- Update timeout_seconds from 3600 (old default) to 1 (new default in minutes)
UPDATE `data_task` SET `timeout_seconds` = 1 WHERE `timeout_seconds` = 3600;

-- Update retry_times from 0 (old default) to 1 (new default)
UPDATE `data_task` SET `retry_times` = 1 WHERE `retry_times` = 0;

-- Update retry_interval from 60 (old default in seconds) to 1 (new default in minutes)
UPDATE `data_task` SET `retry_interval` = 1 WHERE `retry_interval` = 60;

-- Then, update column defaults for new records
ALTER TABLE `data_task` ALTER COLUMN `timeout_seconds` SET DEFAULT 1;
ALTER TABLE `data_task` ALTER COLUMN `retry_times` SET DEFAULT 1;
ALTER TABLE `data_task` ALTER COLUMN `retry_interval` SET DEFAULT 1;

-- Update column comments to reflect correct unit (minutes)
ALTER TABLE `data_task` MODIFY COLUMN `timeout_seconds` INT DEFAULT 1 COMMENT '超时时间(分钟)';
ALTER TABLE `data_task` MODIFY COLUMN `retry_interval` INT DEFAULT 1 COMMENT '重试间隔(分钟)';
ALTER TABLE `data_task` MODIFY COLUMN `retry_times` INT DEFAULT 1 COMMENT '重试次数';

