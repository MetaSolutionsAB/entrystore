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

import org.entrystore.PrincipalManager;
import org.entrystore.User;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.backup.BackupScheduler;
import org.entrystore.repository.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arming the Quartz schedule moved off the guaranteed bean-creation path onto an event listener, so
 * these cases pin that the listener still arms it — and still arms it as admin, since
 * {@code BackupScheduler.run()} reads repository state a guest cannot see.
 */
@ExtendWith(MockitoExtension.class)
class BackupSchedulerStarterTest {

	private static final URI ADMIN_URI = URI.create("http://localhost:8181/_principals/resource/_admin");

	@Mock
	private BackupScheduler backupScheduler;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private User adminUser;

	@Test
	void startBackupScheduler_enabled_armsTheScheduleAsAdmin() {
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(ADMIN_URI);

		starter(Optional.of(backupScheduler)).startBackupScheduler();

		verify(backupScheduler).run();
		verify(principalManager).setAuthenticatedUserURI(ADMIN_URI);
	}

	@Test
	void disabledSetting_omitsTheStarter() {
		// The scheduler bean is conditional too, but an "off" setting must not arm anything even if a
		// scheduler bean somehow exists.
		new ApplicationContextRunner()
				.withUserConfiguration(BackupSchedulerStarter.class)
				.withBean(BackupScheduler.class, () -> backupScheduler)
				.withBean(PrincipalManager.class, () -> principalManager)
				.withPropertyValues("entrystore.backup.scheduler=off")
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertFalse(context.containsBean("backupSchedulerStarter"));

					context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
							context.getSourceApplicationContext(), Duration.ZERO));

					verify(backupScheduler, never()).run();
				});
	}

	@Test
	void startBackupScheduler_enabledButNoSchedulerCreated_doesNotThrow() {
		// createInstance returns null without a cron expression, which leaves the injection point empty.
		var starter = starter(Optional.empty());
		assertDoesNotThrow(starter::startBackupScheduler);
	}

	@Test
	void schedulerBeanAndStarterShareOneGate() throws NoSuchMethodException {
		// A gate that disagrees would create the starter without a scheduler, so backups would never run.
		var starterGate = BackupSchedulerStarter.class.getAnnotation(ConditionalOnBooleanConfig.class);
		var schedulerGate = EntryStoreConfiguration.class
				.getMethod("backupScheduler", RepositoryManagerImpl.class)
				.getAnnotation(ConditionalOnBooleanConfig.class);

		assertEquals(Settings.BACKUP_SCHEDULER, schedulerGate.value());
		assertEquals(schedulerGate, starterGate);
	}

	@ParameterizedTest(name = "entrystore.backup.scheduler={0} arms the schedule only when ready")
	@ValueSource(strings = {"true", "on", "yes", "1"})
	void enabledStarterWaitsForApplicationReadyEvent(String value) {
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(ADMIN_URI);

		new ApplicationContextRunner()
				.withUserConfiguration(BackupSchedulerStarter.class)
				.withBean(BackupScheduler.class, () -> backupScheduler)
				.withBean(PrincipalManager.class, () -> principalManager)
				.withPropertyValues("entrystore.backup.scheduler=" + value)
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertTrue(context.containsBean("backupSchedulerStarter"));
					verify(backupScheduler, never()).run();

					context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
							context.getSourceApplicationContext(), Duration.ZERO));

					verify(backupScheduler).run();
					verify(principalManager).setAuthenticatedUserURI(ADMIN_URI);
				});
	}

	private BackupSchedulerStarter starter(Optional<BackupScheduler> scheduler) {
		return new BackupSchedulerStarter(scheduler, principalManager);
	}
}
