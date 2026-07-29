/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.rest.springboot.util.PrincipalManagerUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Arms the Quartz backup schedule once the application is up. Constructing the
 * {@link BackupScheduler} is wiring and stays in {@code EntryStoreConfiguration}; handing it to
 * Quartz is runtime work and belongs here, so the backup job cannot fire against a
 * half-initialised context mid-refresh.
 *
 * <p>Uses {@code ApplicationReadyEvent} rather than {@code SmartLifecycle} because
 * {@code BackupScheduler} cannot stop its Quartz scheduler — that field is package-private, and the
 * scheduler itself is the JVM-wide {@code StdSchedulerFactory.getDefaultScheduler()}. Its public
 * {@code delete()} only unschedules the job, which the single-context production lifecycle does not
 * need; a {@code stop()} half calling it would matter only if the same JVM refreshed the context
 * more than once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupSchedulerStarter {

	private final Optional<BackupScheduler> backupScheduler;
	private final PrincipalManager principalManager;

	// Read even though an absent Optional already implies "not on" (the bean carries
	// @ConditionalOnProperty), so the two cases can be told apart in the log: switched off entirely
	// versus switched on but declined by createInstance.
	@Value("${entrystore.backup.scheduler:off}")
	private String backupSchedulerSetting;

	@EventListener(ApplicationReadyEvent.class)
	public void startBackupScheduler() {
		if (!"on".equalsIgnoreCase(backupSchedulerSetting)) {
			log.warn("Backup is disabled in configuration");
			return;
		}
		backupScheduler.ifPresentOrElse(scheduler -> {
			log.info("Starting backup scheduler");
			PrincipalManagerUtil.runAsAdmin(principalManager, scheduler::run);
		}, () -> log.warn("Backup is enabled but no scheduler was created; see the reason logged by "
				+ "BackupScheduler.createInstance (typically no cron expression)"));
	}
}
