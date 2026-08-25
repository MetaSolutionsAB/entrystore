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
 * <p>The boolean keys bind as raw strings and each accessor resolves its key with the predicate the
 * key's <em>actual consumer</em> uses, so the report cannot disagree with the behaviour it reports on
 * and no boolean parse can abort startup over a report-only DTO (a strict bind on this
 * eagerly-instantiated component would refuse to boot, naming {@code constructor parameter N} rather
 * than the key, for a value the real consumer tolerates):
 * <ul>
 * <li>{@link #literalOn} — {@code data.quota}, {@code repository.provenance}, {@code solr}
 * ({@code RepositoryManagerImpl}) and {@code harvester.oai.multithreaded} ({@code ListRecordsJob}) are
 * enabled only by the literal {@code on}, case-insensitively.</li>
 * <li>{@link #anythingButOff} — {@code harvester.oai} ({@code OAIHarvesterFactory}) is disabled only by
 * the literal, case-sensitive {@code off}; hence its {@code off} default below.</li>
 * <li>{@link #relaxedBoolean} — {@code solr.reindex-on-startup} and {@code backup.maintenance} are
 * genuine {@code Config.getBoolean} reads ({@code on}/{@code off}, else {@code Boolean.parseBoolean});
 * {@code auth.signup} and {@code auth.password-reset} have no runtime consumer at all — nothing gates
 * on them — so the report applies the same lenient parse.</li>
 * </ul>
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
			// Default off, not false: the consumer (OAIHarvesterFactory) enables for anything that is not
			// the literal "off", so any other default would report an unset harvester as running.
			@Value("${entrystore.harvester.oai:off}") String oaiHarvesterRaw,
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
		return literalOn(quotaRaw);
	}

	public boolean oaiHarvester() {
		return anythingButOff(oaiHarvesterRaw);
	}

	public boolean oaiHarvesterMultiThreaded() {
		return literalOn(oaiHarvesterMultiThreadedRaw);
	}

	public boolean provenance() {
		return literalOn(provenanceRaw);
	}

	public boolean signup() {
		return relaxedBoolean(signupRaw);
	}

	public boolean passwordReset() {
		return relaxedBoolean(passwordResetRaw);
	}

	public boolean solrEnabled() {
		return literalOn(solrEnabledRaw);
	}

	public boolean solrReindexOnStartup() {
		return relaxedBoolean(solrReindexOnStartupRaw);
	}

	public boolean backupMaintenance() {
		return relaxedBoolean(backupMaintenanceRaw);
	}

	/** Mirrors {@code RepositoryManagerImpl} and {@code ListRecordsJob}: only the literal {@code on} enables. */
	private static boolean literalOn(String value) {
		return "on".equalsIgnoreCase(value);
	}

	/** Mirrors {@code OAIHarvesterFactory}: only the literal, case-sensitive {@code off} disables. */
	private static boolean anythingButOff(String value) {
		return !"off".equals(value);
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
