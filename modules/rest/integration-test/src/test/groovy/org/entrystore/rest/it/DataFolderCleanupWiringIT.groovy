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

import spock.lang.TempDir

/**
 * Verifies that the end-of-run data-folder cleanup is wired up. The cleanup itself ({@code deleteDir} in
 * {@link DataFolderCleanupExtension#executionStop}) runs after the whole suite, so it cannot be asserted
 * from inside a feature; instead this checks the preconditions that guarantee it will run:
 *
 * <ol>
 *   <li>the Spock global-extension service descriptor is on the test classpath and registers
 *       {@link DataFolderCleanupExtension}, so Spock discovers and invokes it — this is a regression guard
 *       for the descriptor not being copied to {@code target/test-classes} (it must be listed in the IT
 *       pom's {@code <testResources>} includes, as it is extension-less);</li>
 *   <li>the guard published, in {@link BaseSpec#ownedDataFolder}, exactly the folder the running app
 *       resolved {@code entrystore.data.folder} to, so the extension deletes the right directory.</li>
 * </ol>
 *
 * <p>It also exercises {@link BaseSpec#assertEmptyOrAbsentDir} directly to pin the guard's reject branches
 * (not-a-directory, non-empty) and its accept case (empty or absent).
 */
class DataFolderCleanupWiringIT extends BaseSpec {

	@TempDir
	File tempDir

	def "the cleanup extension is registered via a discoverable service descriptor"() {
		when: 'all Spock global-extension service descriptors on the test classpath are read'
		def descriptors = getClass().classLoader
			.getResources('META-INF/services/org.spockframework.runtime.extension.IGlobalExtension')
			.collect { it.text }
			.join('\n')

		then: 'one registers DataFolderCleanupExtension, so Spock discovers and runs it at end of the run'
		descriptors.contains(DataFolderCleanupExtension.name)
	}

	def "the claimed data folder matches the running app's resolved data folder"() {
		given: 'the data folder the running app actually resolved'
		def resolved = appInstance.environment.getProperty('entrystore.data.folder')

		expect: 'the guard published exactly that folder (canonicalized) for the cleanup extension to delete'
		ownedDataFolder == canonicalFile(toDataFolderFile(resolved))
	}

	def "the guard rejects a data folder that exists but is not a directory"() {
		given: 'entrystore.data.folder points at a regular file'
		def file = new File(tempDir, 'not-a-directory')
		file.text = 'x'

		when:
		assertEmptyOrAbsentDir(file)

		then:
		thrown(IllegalStateException)
	}

	def "the guard rejects a non-empty data folder"() {
		given: 'the data folder already holds a leftover file from a previous run'
		new File(tempDir, 'stale-resource').text = 'x'

		when:
		assertEmptyOrAbsentDir(tempDir)

		then:
		thrown(IllegalStateException)
	}

	def "the guard accepts an empty or absent data folder"() {
		expect: 'an empty directory and a not-yet-created path both pass the ownership proof'
		assertEmptyOrAbsentDir(tempDir)
		assertEmptyOrAbsentDir(new File(tempDir, 'does-not-exist-yet'))
	}
}
