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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Immutable view of the configuration values reported by {@code StatusService} on
 * {@code GET /status/extended}. Purpose-built for that report, hence the baked-in
 * {@code "unconfigured"} display defaults for the string values.
 *
 * <p>Bound through {@code @Value} rather than {@code @ConfigurationProperties} because the keys span
 * several unrelated prefixes and a number of them ({@code entrystore.solr}, {@code entrystore.data.quota},
 * {@code entrystore.harvester.oai}, {@code entrystore.repository.provenance},
 * {@code entrystore.backup.maintenance}) are simultaneously a scalar and the prefix of further keys —
 * a layout a record component can never express.
 *
 * <p>The {@code @Value} annotations sit on the explicit canonical constructor's parameters rather than
 * on the record components — see {@link CorsProperties} for why that distinction matters at startup.
 */
@Component
public record StatusReportProperties(
		String rowstoreUrl,
		String repositoryType,
		String repositoryIndices,
		boolean quota,
		String quotaDefault,
		boolean oaiHarvester,
		boolean oaiHarvesterMultiThreaded,
		boolean provenance,
		boolean signup,
		boolean passwordReset,
		boolean solrEnabled,
		boolean solrReindexOnStartup,
		String backupFormat,
		boolean backupMaintenance,
		String backupCronExpression,
		String backupMaintenanceExpiresAfterDays,
		String backupMaintenanceLowerLimit,
		String backupMaintenanceUpperLimit) {

	/** Display placeholder reported for unset string values; also applied by {@code StatusService}. */
	public static final String UNCONFIGURED = "unconfigured";

	public StatusReportProperties(
			@Value("${entrystore.rowstore.url:" + UNCONFIGURED + "}") String rowstoreUrl,
			@Value("${entrystore.repository.store.type:" + UNCONFIGURED + "}") String repositoryType,
			@Value("${entrystore.repository.store.indexes:" + UNCONFIGURED + "}") String repositoryIndices,
			@Value("${entrystore.data.quota:false}") boolean quota,
			@Value("${entrystore.data.quota.default:" + UNCONFIGURED + "}") String quotaDefault,
			@Value("${entrystore.harvester.oai:false}") boolean oaiHarvester,
			@Value("${entrystore.harvester.oai.multithreaded:false}") boolean oaiHarvesterMultiThreaded,
			@Value("${entrystore.repository.provenance:false}") boolean provenance,
			@Value("${entrystore.auth.signup:false}") boolean signup,
			@Value("${entrystore.auth.password-reset:false}") boolean passwordReset,
			@Value("${entrystore.solr:false}") boolean solrEnabled,
			@Value("${entrystore.solr.reindex-on-startup:false}") boolean solrReindexOnStartup,
			@Value("${entrystore.backup.format:" + UNCONFIGURED + "}") String backupFormat,
			@Value("${entrystore.backup.maintenance:false}") boolean backupMaintenance,
			// Nested default keeps the fallback to the deprecated entrystore.backup.timeregexp key.
			@Value("${entrystore.backup.cronexp:${entrystore.backup.timeregexp:" + UNCONFIGURED + "}}") String backupCronExpression,
			@Value("${entrystore.backup.maintenance.expires-after-days:" + UNCONFIGURED + "}") String backupMaintenanceExpiresAfterDays,
			@Value("${entrystore.backup.maintenance.lower-limit:" + UNCONFIGURED + "}") String backupMaintenanceLowerLimit,
			@Value("${entrystore.backup.maintenance.upper-limit:" + UNCONFIGURED + "}") String backupMaintenanceUpperLimit) {
		this.rowstoreUrl = rowstoreUrl;
		this.repositoryType = repositoryType;
		this.repositoryIndices = repositoryIndices;
		this.quota = quota;
		this.quotaDefault = quotaDefault;
		this.oaiHarvester = oaiHarvester;
		this.oaiHarvesterMultiThreaded = oaiHarvesterMultiThreaded;
		this.provenance = provenance;
		this.signup = signup;
		this.passwordReset = passwordReset;
		this.solrEnabled = solrEnabled;
		this.solrReindexOnStartup = solrReindexOnStartup;
		this.backupFormat = backupFormat;
		this.backupMaintenance = backupMaintenance;
		this.backupCronExpression = backupCronExpression;
		this.backupMaintenanceExpiresAfterDays = backupMaintenanceExpiresAfterDays;
		this.backupMaintenanceLowerLimit = backupMaintenanceLowerLimit;
		this.backupMaintenanceUpperLimit = backupMaintenanceUpperLimit;
	}
}
