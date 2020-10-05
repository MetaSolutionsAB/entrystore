/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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
public class Settings {
	public static String AUTH_ADMIN_SECRET = "entrystore.auth.adminpw";
	
	public static String AUTH_OPENID = "entrystore.auth.openid";
	public static String AUTH_OPENID_GOOGLE = "entrystore.auth.openid.google";
	public static String AUTH_OPENID_YAHOO = "entrystore.auth.openid.yahoo";
	public static String AUTH_OPENID_MYOPENID = "entrystore.auth.openid.myopenid";

	public static String AUTH_TOKEN_MAX_AGE = "entrystore.auth.token.max-age";

	public static String AUTH_CAS = "entrystore.auth.cas";
	public static String AUTH_CAS_VERSION = "entrystore.auth.cas.version";
	public static String AUTH_CAS_SERVER_URL = "entrystore.auth.cas.server.url";
	public static String AUTH_CAS_SERVER_LOGIN_URL = "entrystore.auth.cas.server.url.login";
	public static String AUTH_CAS_USER_AUTO_PROVISIONING = "entrystore.auth.cas.user-auto-provisioning";

	public static String AUTH_SAML = "entrystore.auth.saml";
	public static String AUTH_SAML_RELYING_PARTY_ID = "entrystore.auth.saml.relying-party-id";
	public static String AUTH_SAML_ASSERTION_CONSUMER_SERVICE_URL = "entrystore.auth.saml.assertion-consumer-service.url";
	public static String AUTH_SAML_IDP_METADATA_URL = "entrystore.auth.saml.idp-metadata.url";
	public static String AUTH_SAML_USER_AUTO_PROVISIONING = "entrystore.auth.saml.user-auto-provisioning";
	public static String AUTH_SAML_REDIRECT_METHOD = "entrystore.auth.saml.redirect-method";

	public static String AUTH_COOKIE_PATH = "entrystore.auth.cookie.path";

	public static String AUTH_PASSWORD = "entrystore.auth.password";
	public static String AUTH_PASSWORD_WHITELIST = "entrystore.auth.password.whitelist";
	
	public static String BACKUP_FOLDER = "entrystore.backup.folder";
	public static String BACKUP_SCHEDULER = "entrystore.backup.scheduler";
	public static String BACKUP_TIMEREGEXP_DEPRECATED = "entrystore.backup.timeregexp";
	public static String BACKUP_CRONEXP = "entrystore.backup.cronexp";
	public static String BACKUP_GZIP = "entrystore.backup.gzip";
	public static String BACKUP_FORMAT = "entrystore.backup.format";
	
	public static String BACKUP_MAINTENANCE = "entrystore.backup.maintenance";
	public static String BACKUP_MAINTENANCE_UPPER_LIMIT = "entrystore.backup.maintenance.upper-limit";
	public static String BACKUP_MAINTENANCE_LOWER_LIMIT = "entrystore.backup.maintenance.lower-limit";
	public static String BACKUP_MAINTENANCE_EXPIRES_AFTER_DAYS = "entrystore.backup.maintenance.expires-after-days";
	
	public static String DATA_FOLDER = "entrystore.data.folder";
	public static String DATA_QUOTA = "entrystore.data.quota";
	public static String DATA_QUOTA_DEFAULT = "entrystore.data.quota.default";
	public static String DATA_MAX_FILE_SIZE = "entrystore.data.max-file-size";
	
	public static String BASE_URL = "entrystore.baseurl.folder";
	
	public static String HARVESTER_OAI = "entrystore.harvester.oai";
	public static String HARVESTER_OAI_MULTITHREADED = "entrystore.harvester.oai.multithreaded";
	public static String HARVESTER_OAI_METADATA_POLICY = "entrystore.harvester.oai.policy"; // skip | replace
	public static String HARVESTER_OAI_FROM_AUTO_DETECT = "entrystore.harvester.oai.from.auto-detect";
	public static String HARVESTER_FAO = "entrystore.harvester.fao";
	
	public static String HARVESTING_TARGET_OAI_BASE_URI = "Identify.scam.baseuri";
	
	public static String STORE_USER = "entrystore.repository.store.user";
	public static String STORE_PWD = "entrystore.repository.store.password";
	public static String STORE_DBNAME = "entrystore.repository.store.database.name";
	public static String STORE_PORTNR = "entrystore.repository.store.port.number";
	public static String STORE_SERVERNAME = "entrystore.repository.store.server.name";
	public static String STORE_PATH = "entrystore.repository.store.path";
	public static String STORE_URL = "entrystore.repository.store.url";
	public static String STORE_ENDPOINT_QUERY = "entrystore.repository.store.endpoint-query";
	public static String STORE_ENDPOINT_UPDATE = "entrystore.repository.store.endpoint-update";
	public static String STORE_INDEXES = "entrystore.repository.store.indexes";
	public static String STORE_TYPE = "entrystore.repository.store.type";
	public static String STORE_INIT_WITH_TEST_DATA = "entrystore.repository.store.init-with-test-data";

	public static String STOREJS_JS = "entrystore.repository.storejs.js";
	public static String STOREJS_CSS = "entrystore.repository.storejs.css";
	
	public static String SOLR = "entrystore.solr";
	public static String SOLR_URL = "entrystore.solr.url";
	public static String SOLR_REINDEX_ON_STARTUP = "entrystore.solr.reindex-on-startup";
	public static String SOLR_REINDEX_ON_STARTUP_WAIT = "entrystore.solr.reindex-on-startup.wait";
	public static String SOLR_EXTRACT_FULLTEXT = "entrystore.solr.extract-fulltext";
	public static String SOLR_MAX_LIMIT = "entrystore.solr.max-limit";

	public static String SOLR_RELATED = "entrystore.solr.related";
	public static String SOLR_RELATED_PROPERTIES = "entrystore.solr.related.properties";

	public static String REPOSITORY_REWRITE_BASEREFERENCE = "entrystore.repository.rewrite-basereference";
	
	public static String REPOSITORY_CACHE = "entrystore.repository.cache";
	public static String REPOSITORY_CACHE_PATH = "entrystore.repository.cache.path";
	
	public static String REPOSITORY_PUBLIC = "entrystore.repository.public";
	public static String REPOSITORY_PUBLIC_PATH = "entrystore.repository.public.path";
	public static String REPOSITORY_PUBLIC_INDEXES = "entrystore.repository.public.indexes";
	public static String REPOSITORY_PUBLIC_TYPE = "entrystore.repository.public.type";
	public static String REPOSITORY_PUBLIC_REBUILD_ON_STARTUP = "entrystore.repository.public.rebuild-on-startup";

	public static String REPOSITORY_PROVENANCE = "entrystore.repository.provenance";
	public static String REPOSITORY_PROVENANCE_PATH = "entrystore.repository.provenance.path";
	public static String REPOSITORY_PROVENANCE_INDEXES = "entrystore.repository.provenance.indexes";
	public static String REPOSITORY_PROVENANCE_TYPE = "entrystore.repository.provenance.type";
	public static String REPOSITORY_PROVENANCE_REBUILD_ON_STARTUP = "entrystore.repository.provenance.rebuild-on-startup";

	public static String REPOSITORY_TRACK_DELETED = "entrystore.repository.track-deleted-entries";
	public static String REPOSITORY_TRACK_DELETED_CLEANUP = "entrystore.repository.track-deleted-entries.cleanup";

	public static String PROXY_WHITELIST_ANONYMOUS = "entrystore.proxy.whitelist.anonymous";

	public static String SMTP_HOST = "entrystore.smtp.host";
	public static String SMTP_PORT = "entrystore.smtp.port";
	public static String SMTP_SECURITY = "entrystore.smtp.security";
	public static String SMTP_USERNAME = "entrystore.smtp.username";
	public static String SMTP_PASSWORD = "entrystore.smtp.password";
	public static String SMTP_EMAIL_FROM = "entrystore.smtp.email.from";
	public static String SMTP_EMAIL_BCC = "entrystore.smtp.email.bcc";
	public static String SMTP_EMAIL_REPLYTO = "entrystore.smtp.email.reply-to";

	public static String AUTH_FROM_EMAIL_DEPRECATED = "entrystore.auth.email.from";
	public static String AUTH_BCC_EMAIL_DEPRECATED = "entrystore.auth.email.bcc";

	public static String AUTH_RECAPTCHA = "entrystore.auth.recaptcha";
	public static String AUTH_RECAPTCHA_PRIVATE_KEY = "entrystore.auth.recaptcha.private-key";
	public static String AUTH_RECAPTCHA_PUBLIC_KEY = "entrystore.auth.recaptcha.public-key";

	public static String SIGNUP = "entrystore.auth.signup";
	public static String SIGNUP_SUBJECT = "entrystore.auth.signup.email.subject";
	public static String SIGNUP_CONFIRMATION_MESSAGE_TEMPLATE_PATH = "entrystore.auth.signup.email.template";
	public static String SIGNUP_WHITELIST = "entrystore.auth.signup.whitelist";
	public static String SIGNUP_CREATE_HOME_CONTEXT = "entrystore.auth.signup.create-home-context";

	public static String PASSWORD_RESET = "entrystore.auth.password-reset";
	public static String PASSWORD_RESET_CONFIRMATION_MESSAGE_TEMPLATE_PATH = "entrystore.auth.password-reset.email.template";
	public static String PASSWORD_RESET_SUBJECT = "entrystore.auth.password-reset.email.subject";

	public static String PASSWORD_CHANGE_SUBJECT = "entrystore.auth.password-change.email.subject";
	public static String PASSWORD_CHANGE_CONFIRMATION_MESSAGE_TEMPLATE_PATH = "entrystore.auth.password-change.email.template";

	public static String CORS = "entrystore.cors";
	public static String CORS_ORIGINS = "entrystore.cors.origins";
	public static String CORS_HEADERS = "entrystore.cors.headers";
	public static String CORS_MAX_AGE = "entrystore.cors.max-age";

	public static String NONADMIN_GROUPCONTEXT_CREATION = "entrystore.nonadmin.group-context-creation";

	public static String TRAVERSAL_PROFILE = "entrystore.traversal";

	public static String ROWSTORE_URL = "entrystore.rowstore.url";

	public static String HTTPS_DISABLE_VERIFICATION = "entrystore.https.disable-verification";

	public static String HTTP_ALLOW_CONTENT_DISPOSITION_INLINE = "entrystore.http.allow-content-disposition-inline";
	
	private Settings() {
	}

}