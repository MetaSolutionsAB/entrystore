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
import org.entrystore.repository.backup.BackupScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

		starter(Optional.of(backupScheduler), "on").startBackupScheduler();

		verify(backupScheduler).run();
		verify(principalManager).setAuthenticatedUserURI(ADMIN_URI);
	}

	@Test
	void startBackupScheduler_disabled_leavesTheScheduleUnarmed() {
		// The Optional is empty in this case too (@ConditionalOnProperty), but an "off" setting must not
		// arm anything even if a scheduler bean somehow exists.
		starter(Optional.of(backupScheduler), "off").startBackupScheduler();

		verify(backupScheduler, never()).run();
	}

	@Test
	void startBackupScheduler_enabledButNoSchedulerCreated_doesNotThrow() {
		// createInstance returns null without a cron expression, which leaves the injection point empty.
		assertDoesNotThrow(() -> starter(Optional.empty(), "on").startBackupScheduler());
	}

	@Test
	void backupSchedulerSetting_bindsFromTheConfiguredProperty() {
		// Pins the placeholder key itself, which the reflection-set field above cannot: a misspelt
		// @Value key would leave the cases above green while a production entrystore.backup.scheduler=on
		// resolved to the "off" default and backups silently never ran.
		when(principalManager.getAdminUser()).thenReturn(adminUser);
		when(adminUser.getURI()).thenReturn(ADMIN_URI);

		new ApplicationContextRunner()
				.withBean(PropertySourcesPlaceholderConfigurer.class)
				.withBean(BackupScheduler.class, () -> backupScheduler)
				.withBean(PrincipalManager.class, () -> principalManager)
				.withBean(BackupSchedulerStarter.class)
				.withPropertyValues("entrystore.backup.scheduler=on")
				.run(context -> {
					context.getBean(BackupSchedulerStarter.class).startBackupScheduler();
					verify(backupScheduler).run();
				});
	}

	private BackupSchedulerStarter starter(Optional<BackupScheduler> scheduler, String setting) {
		BackupSchedulerStarter starter = new BackupSchedulerStarter(scheduler, principalManager);
		// @Value-injected field — set via reflection since we're not using a Spring context here.
		ReflectionTestUtils.setField(starter, "backupSchedulerSetting", setting);
		return starter;
	}
}
