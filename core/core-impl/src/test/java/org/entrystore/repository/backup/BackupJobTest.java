/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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

package org.entrystore.repository.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BackupJobTest {

	@Test
	public void interruptExecute() {
		BackupJob job = new BackupJob();
		job.interrupt();
		RuntimeException ex = assertThrows(RuntimeException.class, () -> job.execute(null));
		assertEquals("Backup job \"execute()\" was interrupted", ex.getMessage());
	}

	@Test
	public void interruptRunBackup() {
		BackupJob job = new BackupJob();
		job.interrupt();
		RuntimeException ex = assertThrows(RuntimeException.class, () -> BackupJob.runBackup(null));
		assertEquals("Backup job \"runBackup()\" was interrupted", ex.getMessage());
	}

	@Test
	public void interruptRunBackupMaintenance() {
		BackupJob job = new BackupJob();
		job.interrupt();
		RuntimeException ex = assertThrows(RuntimeException.class, () -> BackupJob.runBackupMaintenance(null));
		assertEquals("Backup job \"runBackupMaintenance()\" was interrupted", ex.getMessage());
	}

}
