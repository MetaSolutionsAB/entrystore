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

package org.entrystore.rest.it

import org.slf4j.LoggerFactory
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.ISpockExecution

/**
 * Deletes the integration-test data folder once the entire Spock run has finished.
 *
 * <p>Hook choice matters: {@code SpockEngineDescriptor.after()} runs at the end of engine execution inside
 * the live test JVM and drives both {@link #executionStop} and {@link #stop()}. We use {@code executionStop}
 * because its contract runs it exactly once at end of execution and hands us the {@link ISpockExecution};
 * {@code stop()} by contract "can be called more than once or also not at all" and is additionally invoked
 * from a {@code RunContext} JVM shutdown hook — by then Failsafe has stopped capturing stdout and Logback
 * may be down, so a log line there would not reach the Maven output. Running inside {@code after()} keeps
 * the log line below visible while Failsafe is still streaming. Discovered via
 * {@code META-INF/services/org.spockframework.runtime.extension.IGlobalExtension}; needs no dependency
 * beyond spock-core.
 *
 * <p>It deletes only the folder {@link BaseSpec} validated and published as owned — one that was empty or
 * absent at startup and matched the running app's resolved {@code entrystore.data.folder}. If no
 * shared-app IT ran (the field is null), this is a no-op. The app is closed first so no open file handles
 * block deletion.
 */
class DataFolderCleanupExtension implements IGlobalExtension {

	private static final def log = LoggerFactory.getLogger(DataFolderCleanupExtension)

	@Override
	void executionStop(ISpockExecution execution) {
		File owned = BaseSpec.ownedDataFolder
		if (owned == null) {
			return // no shared-app IT validated a data folder to clean this run
		}
		// Close any still-running app so its open file handles release the data folder before we delete it.
		// Defensive: a close failure must not prevent the delete below.
		try {
			BaseSpec.stopPreexistingAppIfRunning()
		} catch (Exception e) {
			log.warn('Could not stop app before cleaning IT data folder {}. Error: {}', owned.absolutePath, e.message, e)
		}
		if (!owned.isDirectory()) {
			log.info('IT data folder absent, nothing to clean: {}', owned)
			return
		}
		log.info('Cleaning up IT-managed data folder: {}', owned.absolutePath)
		if (!owned.deleteDir()) {
			log.error('Incomplete cleanup of IT data folder {} — has to be cleaned up manually before next test run start', owned.absolutePath)
		}
	}
}
