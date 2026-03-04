SET NAMES utf8mb4;

ALTER TABLE `assistant_run_step`
    ADD KEY `idx_assistant_run_step_key` (`run_id`, `step_key`, `step_order`);

ALTER TABLE `assistant_artifact`
    ADD KEY `idx_assistant_artifact_run_type` (`run_id`, `artifact_type`, `created_at`);

ALTER TABLE `assistant_message`
    ADD KEY `idx_assistant_message_session_run` (`session_id`, `run_id`, `created_at`);
