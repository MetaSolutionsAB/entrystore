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

/**
 * Verifies that the end-of-run data-folder cleanup is wired up. The cleanup itself ({@code deleteDir} in
 * {@link DataFolderCleanupExtension#executionStop}) runs after the whole suite, so it cannot be asserted
 * from inside a feature; instead this checks the two preconditions that guarantee it will run:
 *
 * <ol>
 *   <li>the Spock global-extension service descriptor is on the test classpath and registers
 *       {@link DataFolderCleanupExtension}, so Spock discovers and invokes it — this is a regression guard
 *       for the descriptor not being copied to {@code target/test-classes} (it must be listed in the IT
 *       pom's {@code <testResources>} includes, as it is extension-less);</li>
 *   <li>{@code guardAndClaimDataFolder} published the owned data folder in
 *       {@link BaseSpec#ownedDataFolder}, so the extension has a target to delete.</li>
 * </ol>
 */
class DataFolderCleanupWiringIT extends BaseSpec {

	def "the cleanup extension is registered via a discoverable service descriptor"() {
		when: 'all Spock global-extension service descriptors on the test classpath are read'
		def descriptors = getClass().classLoader
			.getResources('META-INF/services/org.spockframework.runtime.extension.IGlobalExtension')
			.collect { it.text }
			.join('\n')

		then: 'one registers DataFolderCleanupExtension, so Spock discovers and runs it at end of the run'
		descriptors.contains(DataFolderCleanupExtension.name)
	}

	def "the data folder is claimed for cleanup before the app starts"() {
		expect: 'the pre-start guard published the owned data folder for the cleanup extension to delete'
		BaseSpec.ownedDataFolder != null
	}
}
