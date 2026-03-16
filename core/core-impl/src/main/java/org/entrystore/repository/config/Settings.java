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

package org.entrystore.repository.config;

/**
 * Contains most of the property keys used within EntryStore.
 *
 * @author Hannes Ebner
 */
public interface Settings {
	String AUTH_ADMIN_SECRET = "entrystore.auth.adminpw";

	String AUTH_CAS = "entrystore.auth.cas";
	String AUTH_CAS_VERSION = "entrystore.auth.cas.version";
	String AUTH_CAS_SERVER_URL = "entrystore.auth.cas.server.url";
	String AUTH_CAS_SERVER_LOGIN_URL = "entrystore.auth.cas.server.url.login";
	String AUTH_CAS_USER_AUTO_PROVISIONING = "entrystore.auth.cas.user-auto-provisioning";
	String AUTH_CAS_REDIRECT_SUCCESS_URL = "entrystore.auth.cas.redirect-success.url";
	String AUTH_CAS_REDIRECT_FAILURE_URL = "entrystore.auth.cas.redirect-failure.url";

	String AUTH_SAML_ENABLED = "entrystore.auth.saml.enabled";
	String AUTH_SAML_ASSERTION_CONSUMER_SERVICE_URL = "entrystore.auth.saml.assertion-consumer-service.url";
	String AUTH_SAML_REDIRECT_SUCCESS_URL = "entrystore.auth.saml.redirect-success.url";
	String AUTH_SAML_REDIRECT_FAILURE_URL = "entrystore.auth.saml.redirect-failure.url";
	String AUTH_SAML_REDIRECT_DOMAIN_WHITELIST = "entrystore.auth.saml.redirect-domain-whitelist";

	String AUTH_SAML_DEFAULT_IDP = "entrystore.auth.saml.default-idp";
	String AUTH_SAML_IDPS = "entrystore.auth.saml.idps";
	String AUTH_SAML_IDP_RELYING_PARTY_ID = "entrystore.auth.saml.idp.%s.relying-party-id";
	String AUTH_SAML_IDP_METADATA_URL = "entrystore.auth.saml.idp.%s.metadata.url";
	String AUTH_SAML_IDP_METADATA_MAXAGE = "entrystore.auth.saml.idp.%s.metadata.max-age";
	String AUTH_SAML_IDP_USER_AUTO_PROVISIONING = "entrystore.auth.saml.idp.%s.user-auto-provisioning";
	String AUTH_SAML_IDP_REDIRECT_METHOD = "entrystore.auth.saml.idp.%s.redirect-method";
	String AUTH_SAML_IDP_DOMAINS = "entrystore.auth.saml.idp.%s.domains";

	String AUTH_SAML_LEGACY_RELYING_PARTY_ID = "entrystore.auth.saml.relying-party-id";
	String AUTH_SAML_LEGACY_ASSERTION_CONSUMER_SERVICE_URL = "entrystore.auth.saml.assertion-consumer-service.url";
	String AUTH_SAML_LEGACY_IDP_METADATA_URL = "entrystore.auth.saml.idp-metadata.url";
	String AUTH_SAML_LEGACY_IDP_METADATA_MAXAGE = "entrystore.auth.saml.idp-metadata.max-age";
	String AUTH_SAML_LEGACY_USER_AUTO_PROVISIONING = "entrystore.auth.saml.user-auto-provisioning";
	String AUTH_SAML_LEGACY_REDIRECT_METHOD = "entrystore.auth.saml.redirect-method";
	String AUTH_SAML_LEGACY_REDIRECT_SUCCESS_URL = "entrystore.auth.saml.redirect-success.url";
	String AUTH_SAML_LEGACY_REDIRECT_FAILURE_URL = "entrystore.auth.saml.redirect-failure.url";

	String AUTH_COOKIE_PATH = "entrystore.auth.cookie.path";
	String AUTH_COOKIE_HTTPONLY = "entrystore.auth.cookie.httponly";
	String AUTH_COOKIE_SECURE = "entrystore.auth.cookie.secure";
	String AUTH_COOKIE_SAMESITE = "entrystore.auth.cookie.samesite";
	String AUTH_COOKIE_MAX_AGE = "entrystore.auth.cookie.max-age";
	String AUTH_COOKIE_REFRESH_EXPIRATION_ON_ACCESS = "entrystore.auth.cookie.refresh-expiration-on-access";
	String AUTH_COOKIE_INVALID_TOKEN_ERROR = "entrystore.auth.cookie.invalid-token-error";
	@Deprecated String AUTH_TOKEN_MAX_AGE = "entrystore.auth.cookie.max-age";

	String AUTH_PASSWORD = "entrystore.auth.password";
	String AUTH_PASSWORD_WHITELIST = "entrystore.auth.password.whitelist";
	String AUTH_PASSWORD_BLACKLIST = "entrystore.auth.password.blacklist";

	String AUTH_PASSWORD_REQUIRE_CURRENT_PASSWORD = "entrystore.auth.password.require-current-password";

	String AUTH_PASSWORD_RULE_LOWERCASE = "entrystore.auth.password.rule.lowercase";
	String AUTH_PASSWORD_RULE_UPPERCASE = "entrystore.auth.password.rule.uppercase";
	String AUTH_PASSWORD_RULE_NUMBER = "entrystore.auth.password.rule.number";
	String AUTH_PASSWORD_RULE_SYMBOL = "entrystore.auth.password.rule.symbol";
	String AUTH_PASSWORD_RULE_MINLENGTH = "entrystore.auth.password.rule.min-length";
	String AUTH_PASSWORD_RULE_CUSTOM = "entrystore.auth.password.rule.custom";

	String AUTH_PASSWORD_RESET = "entrystore.auth.password-reset";
	String AUTH_PASSWORD_RESET_CONFIRMATION_MESSAGE_TEMPLATE_PATH = "entrystore.auth.password-reset.email.template";
	String AUTH_PASSWORD_RESET_SUBJECT = "entrystore.auth.password-reset.email.subject";

	String AUTH_PASSWORD_CHANGE_SUBJECT = "entrystore.auth.password-change.email.subject";
	String AUTH_PASSWORD_CHANGE_CONFIRMATION_MESSAGE_TEMPLATE_PATH = "entrystore.auth.password-change.email.template";

	String AUTH_TEMP_LOCKOUT_MAX_ATTEMPTS = "entrystore.auth.temp.lockout.max.attempts";
	String AUTH_TEMP_LOCKOUT_DURATION = "entrystore.auth.temp.lockout.duration";
	String AUTH_TEMP_LOCKOUT_ADMIN = "entrystore.auth.temp.lockout.admin";

	String AUTH_HTTP_BASIC_ENABLED = "entrystore.auth.http-basic.enabled";

	String AUTH_FROM_EMAIL_DEPRECATED = "entrystore.auth.email.from";
	String AUTH_BCC_EMAIL_DEPRECATED = "entrystore.auth.email.bcc";

	String AUTH_RECAPTCHA = "entrystore.auth.recaptcha";
	String AUTH_RECAPTCHA_URL = "entrystore.auth.recaptcha.url";
	String AUTH_RECAPTCHA_PRIVATE_KEY = "entrystore.auth.recaptcha.private-key";
	String AUTH_RECAPTCHA_PUBLIC_KEY = "entrystore.auth.recaptcha.public-key";

	String AUTH_PERMITTED_REDIRECTS = "entrystore.auth.permitted.redirects";

	String BACKUP_FOLDER = "entrystore.backup.folder";
	String BACKUP_SCHEDULER = "entrystore.backup.scheduler";
	String BACKUP_TIMEREGEXP_DEPRECATED = "entrystore.backup.timeregexp";
	String BACKUP_CRONEXP = "entrystore.backup.cronexp";
	String BACKUP_GZIP = "entrystore.backup.gzip";
	String BACKUP_FORMAT = "entrystore.backup.format";
	String BACKUP_DELETE_AFTER = "entrystore.backup.delete-after";
	String BACKUP_INCLUDE_FILES = "entrystore.backup.include-files";

	String BACKUP_MAINTENANCE = "entrystore.backup.maintenance";
	String BACKUP_MAINTENANCE_UPPER_LIMIT = "entrystore.backup.maintenance.upper-limit";
	String BACKUP_MAINTENANCE_LOWER_LIMIT = "entrystore.backup.maintenance.lower-limit";
	String BACKUP_MAINTENANCE_EXPIRES_AFTER_DAYS = "entrystore.backup.maintenance.expires-after-days";

	String DATA_FOLDER = "entrystore.data.folder";
	String DATA_QUOTA = "entrystore.data.quota";
	String DATA_QUOTA_DEFAULT = "entrystore.data.quota.default";
	String DATA_MAX_FILE_SIZE = "entrystore.data.max-file-size";

	String BASE_URL = "entrystore.baseurl.folder";

	String HARVESTER_OAI = "entrystore.harvester.oai";
	String HARVESTER_OAI_MULTITHREADED = "entrystore.harvester.oai.multithreaded";
	String HARVESTER_OAI_METADATA_POLICY = "entrystore.harvester.oai.policy"; // skip | replace
	String HARVESTER_OAI_FROM_AUTO_DETECT = "entrystore.harvester.oai.from.auto-detect";
	String HARVESTER_FAO = "entrystore.harvester.fao";

	String HARVESTING_TARGET_OAI_BASE_URI = "Identify.scam.baseuri";

	String STORE_USER = "entrystore.repository.store.user";
	String STORE_PWD = "entrystore.repository.store.password";
	String STORE_DBNAME = "entrystore.repository.store.database.name";
	String STORE_PORTNR = "entrystore.repository.store.port.number";
	String STORE_SERVERNAME = "entrystore.repository.store.server.name";
	String STORE_PATH = "entrystore.repository.store.path";
	String STORE_URL = "entrystore.repository.store.url";
	String STORE_ENDPOINT_QUERY = "entrystore.repository.store.endpoint-query";
	String STORE_ENDPOINT_UPDATE = "entrystore.repository.store.endpoint-update";
	String STORE_INDEXES = "entrystore.repository.store.indexes";
	String STORE_TYPE = "entrystore.repository.store.type";
	String STORE_INIT_WITH_TEST_DATA = "entrystore.repository.store.init-with-test-data";

	String STOREJS_JS = "entrystore.repository.storejs.js";
	String STOREJS_CSS = "entrystore.repository.storejs.css";

	String SOLR = "entrystore.solr";
	String SOLR_URL = "entrystore.solr.url";
	String SOLR_REINDEX_ON_STARTUP = "entrystore.solr.reindex-on-startup";
	String SOLR_REINDEX_ON_STARTUP_WAIT = "entrystore.solr.reindex-on-startup.wait";
	String SOLR_EXTRACT_FULLTEXT = "entrystore.solr.extract-fulltext";
	String SOLR_MAX_LIMIT = "entrystore.solr.max-limit";
	String SOLR_FACET_MAX_LIMIT = "entrystore.solr.facet-max-limit";
	String SOLR_SCHEMA_URL = "entrystore.solr.schema.url";
	String SOLR_CONFIG_URL = "entrystore.solr.config.url";
	String SOLR_DEFAULT_SORTING_LANG = "entrystore.solr.default-sorting-lang";
	String SOLR_AUTH_USERNAME = "entrystore.solr.auth.username";
	String SOLR_AUTH_PASSWORD = "entrystore.solr.auth.password";
	String SOLR_RELATED = "entrystore.solr.related";
	String SOLR_RELATED_PROPERTIES = "entrystore.solr.related.properties";

	String SYNDICATION_URL_TEMPLATE = "entrystore.syndication.url-template";

	String REPOSITORY_REWRITE_BASEREFERENCE = "entrystore.repository.rewrite-basereference";

	String REPOSITORY_PUBLIC = "entrystore.repository.public";
	String REPOSITORY_PUBLIC_PATH = "entrystore.repository.public.path";
	String REPOSITORY_PUBLIC_INDEXES = "entrystore.repository.public.indexes";
	String REPOSITORY_PUBLIC_TYPE = "entrystore.repository.public.type";
	String REPOSITORY_PUBLIC_REBUILD_ON_STARTUP = "entrystore.repository.public.rebuild-on-startup";
	String REPOSITORY_PUBLIC_SPARQL_MAX_EXECUTION_TIME = "entrystore.repository.public.sparql.max-execution-time";

	String REPOSITORY_PROVENANCE = "entrystore.repository.provenance";
	String REPOSITORY_PROVENANCE_PATH = "entrystore.repository.provenance.path";
	String REPOSITORY_PROVENANCE_INDEXES = "entrystore.repository.provenance.indexes";
	String REPOSITORY_PROVENANCE_TYPE = "entrystore.repository.provenance.type";
	String REPOSITORY_PROVENANCE_REBUILD_ON_STARTUP = "entrystore.repository.provenance.rebuild-on-startup";

	String REPOSITORY_TRACK_DELETED = "entrystore.repository.track-deleted-entries";
	String REPOSITORY_TRACK_DELETED_CLEANUP = "entrystore.repository.track-deleted-entries.cleanup";

	String PROXY_WHITELIST_ANONYMOUS = "entrystore.proxy.whitelist.anonymous";
	String PROXY_WHITELIST_LOCAL = "entrystore.proxy.whitelist.local";

	String SMTP_HOST = "entrystore.smtp.host";
	String SMTP_PORT = "entrystore.smtp.port";
	String SMTP_SECURITY = "entrystore.smtp.security";
	String SMTP_USERNAME = "entrystore.smtp.username";
	String SMTP_PASSWORD = "entrystore.smtp.password";
	String SMTP_EMAIL_FROM = "entrystore.smtp.email.from";
	String SMTP_EMAIL_BCC = "entrystore.smtp.email.bcc";
	String SMTP_EMAIL_REPLYTO = "entrystore.smtp.email.reply-to";

	String SIGNUP = "entrystore.auth.signup";
	String SIGNUP_SUBJECT = "entrystore.auth.signup.email.subject";
	String SIGNUP_CONFIRMATION_MESSAGE_TEMPLATE_PATH = "entrystore.auth.signup.email.template";
	String SIGNUP_WHITELIST = "entrystore.auth.signup.whitelist";
	String SIGNUP_CREATE_HOME_CONTEXT = "entrystore.auth.signup.create-home-context";

	String CORS = "entrystore.cors";
	String CORS_ORIGINS = "entrystore.cors.origins";
	String CORS_ORIGINS_ALLOW_CREDENTIALS = "entrystore.cors.origins.allow-credentials";
	String CORS_HEADERS = "entrystore.cors.headers";
	String CORS_MAX_AGE = "entrystore.cors.max-age";

	String NONADMIN_GROUPCONTEXT_CREATION = "entrystore.nonadmin.group-context-creation";

	String TRAVERSAL_PROFILE = "entrystore.traversal.%s";
	String TRAVERSAL_PROFILE_MAX_DEPTH = "entrystore.traversal.%s.max-depth";
	String TRAVERSAL_PROFILE_LIMIT = "entrystore.traversal.%s.limit";
	String TRAVERSAL_PROFILE_REPOSITORY_SCOPE = "entrystore.traversal.%s.repository-scope";
	String TRAVERSAL_PROFILE_BLACKLIST = "entrystore.traversal.%s.blacklist";

	String ROWSTORE_URL = "entrystore.rowstore.url";

	String HTTPS_DISABLE_VERIFICATION = "entrystore.https.disable-verification";

	String HTTP_ALLOW_CONTENT_DISPOSITION_INLINE = "entrystore.http.allow-content-disposition-inline";

	String HTTP_ALLOW_MEDIA_TYPE_JAVASCRIPT = "entrystore.http.allow-media-type-javascript";

	String HTTP_HEADER_SERVER = "entrystore.http.header.server";

	String JSONP = "entrystore.jsonp";

	String RDF4J_SOFT_FAIL_ON_CORRUPT_DATA_AND_REPAIR_INDEXES = "org.eclipse.rdf4j.sail.nativerdf.softFailOnCorruptDataAndRepairIndexes";

	String METRICS = "entrystore.metrics";
}
