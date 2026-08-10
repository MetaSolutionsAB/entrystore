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
 * {@code GET /management/status/extended}. Purpose-built for that report, hence the baked-in
 * {@code "unconfigured"} display defaults for the string values.
 *
 * <p>Bound through {@code @Value} rather than {@code @ConfigurationProperties} because the keys span
 * several unrelated prefixes and a number of them ({@code entrystore.solr}, {@code entrystore.data.quota},
 * {@code entrystore.harvester.oai}, {@code entrystore.repository.provenance},
 * {@code entrystore.backup.maintenance}) are simultaneously a scalar and the prefix of further keys —
 * a layout a record component can never express.
 *
 * <p>The boolean keys bind as raw strings and are resolved by {@link #relaxedBoolean(String)}, which
 * mirrors the legacy {@code Config.getBoolean} exactly ({@code on} enables, {@code off} disables,
 * anything else falls to {@code Boolean.parseBoolean} — never throws). Two reasons, both consequences
 * of this being a report-only DTO: it is an eagerly-instantiated component, so a strict boolean bind
 * would put a cosmetic report on the startup-critical path and abort the boot (naming
 * {@code constructor parameter N}, not the key) for a value such as {@code enabled} that the actual
 * consumers tolerate; and every one of these keys is still read by core with legacy semantics
 * ({@code RepositoryManagerImpl}, {@code BackupScheduler}, the OAI harvester — several via a literal
 * {@code "on"} comparison), so a stricter parse here would make the report disagree with the behaviour
 * it reports on ({@code entrystore.solr=1} would report an index core never started).
 *
 * <p>The {@code @Value} annotations sit on the explicit canonical constructor's parameters rather than
 * on the record components — see {@link CorsProperties} for why that distinction matters at startup.
 */
@Component
public record StatusReportProperties(
		String rowstoreUrl,
		String repositoryType,
		String repositoryIndices,
		String quotaRaw,
		String quotaDefault,
		String oaiHarvesterRaw,
		String oaiHarvesterMultiThreadedRaw,
		String provenanceRaw,
		String signupRaw,
		String passwordResetRaw,
		String solrEnabledRaw,
		String solrReindexOnStartupRaw,
		String backupFormat,
		String backupMaintenanceRaw,
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
			@Value("${entrystore.data.quota:false}") String quotaRaw,
			@Value("${entrystore.data.quota.default:" + UNCONFIGURED + "}") String quotaDefault,
			@Value("${entrystore.harvester.oai:false}") String oaiHarvesterRaw,
			@Value("${entrystore.harvester.oai.multithreaded:false}") String oaiHarvesterMultiThreadedRaw,
			@Value("${entrystore.repository.provenance:false}") String provenanceRaw,
			@Value("${entrystore.auth.signup:false}") String signupRaw,
			@Value("${entrystore.auth.password-reset:false}") String passwordResetRaw,
			@Value("${entrystore.solr:false}") String solrEnabledRaw,
			@Value("${entrystore.solr.reindex-on-startup:false}") String solrReindexOnStartupRaw,
			@Value("${entrystore.backup.format:" + UNCONFIGURED + "}") String backupFormat,
			@Value("${entrystore.backup.maintenance:false}") String backupMaintenanceRaw,
			// Nested default keeps the fallback to the deprecated entrystore.backup.timeregexp key.
			@Value("${entrystore.backup.cronexp:${entrystore.backup.timeregexp:" + UNCONFIGURED + "}}") String backupCronExpression,
			@Value("${entrystore.backup.maintenance.expires-after-days:" + UNCONFIGURED + "}") String backupMaintenanceExpiresAfterDays,
			@Value("${entrystore.backup.maintenance.lower-limit:" + UNCONFIGURED + "}") String backupMaintenanceLowerLimit,
			@Value("${entrystore.backup.maintenance.upper-limit:" + UNCONFIGURED + "}") String backupMaintenanceUpperLimit) {
		this.rowstoreUrl = rowstoreUrl;
		this.repositoryType = repositoryType;
		this.repositoryIndices = repositoryIndices;
		this.quotaRaw = quotaRaw;
		this.quotaDefault = quotaDefault;
		this.oaiHarvesterRaw = oaiHarvesterRaw;
		this.oaiHarvesterMultiThreadedRaw = oaiHarvesterMultiThreadedRaw;
		this.provenanceRaw = provenanceRaw;
		this.signupRaw = signupRaw;
		this.passwordResetRaw = passwordResetRaw;
		this.solrEnabledRaw = solrEnabledRaw;
		this.solrReindexOnStartupRaw = solrReindexOnStartupRaw;
		this.backupFormat = backupFormat;
		this.backupMaintenanceRaw = backupMaintenanceRaw;
		this.backupCronExpression = backupCronExpression;
		this.backupMaintenanceExpiresAfterDays = backupMaintenanceExpiresAfterDays;
		this.backupMaintenanceLowerLimit = backupMaintenanceLowerLimit;
		this.backupMaintenanceUpperLimit = backupMaintenanceUpperLimit;
	}

	public boolean quota() {
		return relaxedBoolean(quotaRaw);
	}

	public boolean oaiHarvester() {
		return relaxedBoolean(oaiHarvesterRaw);
	}

	public boolean oaiHarvesterMultiThreaded() {
		return relaxedBoolean(oaiHarvesterMultiThreadedRaw);
	}

	public boolean provenance() {
		return relaxedBoolean(provenanceRaw);
	}

	public boolean signup() {
		return relaxedBoolean(signupRaw);
	}

	public boolean passwordReset() {
		return relaxedBoolean(passwordResetRaw);
	}

	public boolean solrEnabled() {
		return relaxedBoolean(solrEnabledRaw);
	}

	public boolean solrReindexOnStartup() {
		return relaxedBoolean(solrReindexOnStartupRaw);
	}

	public boolean backupMaintenance() {
		return relaxedBoolean(backupMaintenanceRaw);
	}

	/** Mirrors {@code Config.getBoolean}: {@code on}/{@code true} enable, everything else is false — never throws. */
	private static boolean relaxedBoolean(String value) {
		if ("on".equalsIgnoreCase(value)) {
			return true;
		}
		if ("off".equalsIgnoreCase(value)) {
			return false;
		}
		return Boolean.parseBoolean(value);
	}
}
