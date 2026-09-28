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

import org.entrystore.repository.config.PropertiesConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.entrystore.rest.springboot.configuration.StatusReportProperties.UNCONFIGURED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No other {@code @Value} in the REST layer reads these keys, and only two of them are covered by an IT,
 * so a typo here makes {@code GET /management/status/extended} report a default for a value the operator
 * did configure. The boolean accessors must agree with core's relaxed parsing without making a
 * report-only component fail startup on an invalid value tolerated by core.
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

	@ParameterizedTest(name = "{0} reports enabled: {1}")
	@CsvSource({
			"true, true", "on, true", "yes, true", "1, true",
			"false, false", "off, false", "no, false", "0, false",
			"ON, true", "OFF, false", "' yes ', true", "' no ', false",
			"enabled, false", "typo, false", "'', false"
	})
	void booleans_mirrorEachKeysActualConsumer(String value, boolean expected) {
		// Pins core to the same table, so neither parser can drift from the other alone.
		var core = new PropertiesConfiguration("test");
		core.setProperty("feature", value);
		assertEquals(expected, core.getBoolean("feature", false));

		// Raw property source: withPropertyValues trims, which would hide the whitespace rows.
		runner().withInitializer(context -> context.getEnvironment().getPropertySources()
						.addFirst(new MapPropertySource("raw", Map.of(
								"entrystore.data.quota", value,
								"entrystore.harvester.oai", value,
								"entrystore.harvester.oai.multithreaded", value,
								"entrystore.repository.provenance", value,
								"entrystore.solr", value,
								"entrystore.solr.reindex-on-startup", value,
								"entrystore.backup.maintenance", value,
								"entrystore.auth.signup", value,
								"entrystore.auth.password-reset", value))))
				.run(context -> {
					StatusReportProperties properties = context.getBean(StatusReportProperties.class);

					assertEquals(expected, properties.quota());
					assertEquals(expected, properties.oaiHarvester());
					assertEquals(expected, properties.oaiHarvesterMultiThreaded());
					assertEquals(expected, properties.provenance());
					assertEquals(expected, properties.solrEnabled());
					assertEquals(expected, properties.solrReindexOnStartup());
					assertEquals(expected, properties.backupMaintenance());
					assertEquals(expected, properties.signup());
					assertEquals(expected, properties.passwordReset());
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
