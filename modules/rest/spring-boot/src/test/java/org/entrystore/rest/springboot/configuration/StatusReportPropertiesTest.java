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

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.entrystore.rest.springboot.configuration.StatusReportProperties.UNCONFIGURED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No other {@code @Value} in the REST layer reads these keys, and only two of them are covered by an IT,
 * so a typo here makes {@code GET /management/status/extended} report a default for a value the operator
 * did configure. Several are also read by core through {@code Settings} — {@code entrystore.data.quota},
 * {@code .repository.provenance}, {@code .solr} and {@code .harvester.oai} — each with its own literal
 * comparison, which the record's per-consumer predicates deliberately mirror, so the report cannot
 * disagree with the behaviour it reports on and a stray spelling cannot abort startup over a cosmetic DTO.
 *
 * <p>Each string key gets a distinct value, and the booleans alternate, so transposing two adjacent
 * constructor parameters fails. Two booleans further apart that happen to share a value cannot be
 * distinguished this way — that is a limit of the type, not an oversight.
 */
class StatusReportPropertiesTest {

	@Test
	void everyKey_bindsToItsOwnAccessor() {
		runner().withPropertyValues(
						"entrystore.rowstore.url=https://rowstore.example",
						"entrystore.repository.store.type=native",
						"entrystore.repository.store.indexes=spoc,posc",
						"entrystore.data.quota=on",
						"entrystore.data.quota.default=1024",
						"entrystore.harvester.oai=off",
						"entrystore.harvester.oai.multithreaded=on",
						"entrystore.repository.provenance=off",
						"entrystore.auth.signup=true",
						"entrystore.auth.password-reset=false",
						"entrystore.solr=on",
						"entrystore.solr.reindex-on-startup=false",
						"entrystore.backup.format=trig",
						"entrystore.backup.maintenance=true",
						"entrystore.backup.cronexp=0 0 3 * * ?",
						"entrystore.backup.maintenance.expires-after-days=30",
						"entrystore.backup.maintenance.lower-limit=3",
						"entrystore.backup.maintenance.upper-limit=7")
				.run(context -> {
					StatusReportProperties properties = context.getBean(StatusReportProperties.class);

					assertEquals("https://rowstore.example", properties.rowstoreUrl());
					assertEquals("native", properties.repositoryType());
					assertEquals("spoc,posc", properties.repositoryIndices());
					assertTrue(properties.quota());
					assertEquals("1024", properties.quotaDefault());
					assertFalse(properties.oaiHarvester());
					assertTrue(properties.oaiHarvesterMultiThreaded());
					assertFalse(properties.provenance());
					assertTrue(properties.signup());
					assertFalse(properties.passwordReset());
					assertTrue(properties.solrEnabled());
					assertFalse(properties.solrReindexOnStartup());
					assertEquals("trig", properties.backupFormat());
					assertTrue(properties.backupMaintenance());
					assertEquals("0 0 3 * * ?", properties.backupCronExpression());
					assertEquals("30", properties.backupMaintenanceExpiresAfterDays());
					assertEquals("3", properties.backupMaintenanceLowerLimit());
					assertEquals("7", properties.backupMaintenanceUpperLimit());
				});
	}

	@Test
	void noKeysSet_reportsTheDisplayPlaceholderAndFalse() {
		runner().run(context -> {
			StatusReportProperties properties = context.getBean(StatusReportProperties.class);

			assertEquals(UNCONFIGURED, properties.rowstoreUrl());
			assertEquals(UNCONFIGURED, properties.repositoryType());
			assertEquals(UNCONFIGURED, properties.repositoryIndices());
			assertEquals(UNCONFIGURED, properties.quotaDefault());
			assertEquals(UNCONFIGURED, properties.backupFormat());
			assertEquals(UNCONFIGURED, properties.backupCronExpression());
			assertEquals(UNCONFIGURED, properties.backupMaintenanceExpiresAfterDays());
			assertEquals(UNCONFIGURED, properties.backupMaintenanceLowerLimit());
			assertEquals(UNCONFIGURED, properties.backupMaintenanceUpperLimit());
			assertFalse(properties.quota());
			assertFalse(properties.oaiHarvester());
			assertFalse(properties.provenance());
			assertFalse(properties.signup());
			assertFalse(properties.passwordReset());
			assertFalse(properties.solrEnabled());
			assertFalse(properties.backupMaintenance());
		});
	}

	@Test
	void booleans_mirrorEachKeysActualConsumer() {
		// Each key is resolved with the predicate its real consumer uses, so the report cannot disagree
		// with the behaviour it reports on: RepositoryManagerImpl/ListRecordsJob enable only on the
		// literal "on" (so "true"/"1"/"enabled" report false), while OAIHarvesterFactory disables only on
		// the literal "off" — entrystore.harvester.oai=yes runs the harvester, and must report true.
		runner().withPropertyValues(
						"entrystore.data.quota=on",
						"entrystore.harvester.oai=yes",
						"entrystore.harvester.oai.multithreaded=true",
						"entrystore.repository.provenance=1",
						"entrystore.solr=enabled",
						"entrystore.auth.signup=off")
				.run(context -> {
					StatusReportProperties properties = context.getBean(StatusReportProperties.class);

					assertTrue(properties.quota());
					assertTrue(properties.oaiHarvester(), "anything but the literal 'off' runs the harvester");
					assertFalse(properties.oaiHarvesterMultiThreaded(), "ListRecordsJob requires the literal 'on'");
					assertFalse(properties.provenance());
					assertFalse(properties.solrEnabled(), "RepositoryManagerImpl requires the literal 'on'");
					assertFalse(properties.signup());
				});
	}

	@Test
	void unrecognisedBooleanSpelling_doesNotAbortStartup() {
		// This component is eagerly instantiated, so a strict boolean bind would put a report-only DTO on
		// the startup-critical path: an existing deployment carrying entrystore.solr=enabled would refuse
		// to boot with an error naming "constructor parameter N" rather than the key.
		runner().withPropertyValues("entrystore.solr=enabled")
				.run(context -> assertTrue(context.getStartupFailure() == null,
						"a stray boolean spelling must not fail context startup"));
	}

	@Test
	void cronExpression_fallsBackToTheDeprecatedTimeregexpKey() {
		runner().withPropertyValues("entrystore.backup.timeregexp=0 0 4 * * ?")
				.run(context -> assertEquals("0 0 4 * * ?",
						context.getBean(StatusReportProperties.class).backupCronExpression()));
	}

	@Test
	void cronExpression_prefersTheCanonicalKeyOverTheDeprecatedOne() {
		runner().withPropertyValues(
						"entrystore.backup.cronexp=0 0 3 * * ?",
						"entrystore.backup.timeregexp=0 0 4 * * ?")
				.run(context -> assertEquals("0 0 3 * * ?",
						context.getBean(StatusReportProperties.class).backupCronExpression()));
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(StatusReportProperties.class);
	}
}
