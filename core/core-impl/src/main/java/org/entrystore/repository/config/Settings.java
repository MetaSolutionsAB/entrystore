/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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
	
	public static String AUTH_SCHEME = "entrystore.auth.scheme";
	public static String AUTH_ADMIN_SECRET = "entrystore.auth.adminpw";
	
	public static String AUTH_OPENID = "entrystore.auth.openid";
	public static String AUTH_OPENID_GOOGLE = "entrystore.auth.openid.google";
	public static String AUTH_OPENID_YAHOO = "entrystore.auth.openid.yahoo";
	public static String AUTH_OPENID_MYOPENID = "entrystore.auth.openid.myopenid";
	
	public static String BACKUP_FOLDER = "entrystore.backup.folder";
	public static String BACKUP_SCHEDULER = "entrystore.backup.scheduler";
	public static String BACKUP_TIMEREGEXP = "entrystore.backup.timeregexp";
	public static String BACKUP_GZIP = "entrystore.backup.gzip";
	
	public static String BACKUP_MAINTENANCE = "entrystore.backup.maintenance";						 
	public static String BACKUP_MAINTENANCE_UPPER_LIMIT = "entrystore.backup.maintenance.upper-limit";
	public static String BACKUP_MAINTENANCE_LOWER_LIMIT = "entrystore.backup.maintenance.lower-limit";
	public static String BACKUP_MAINTENANCE_EXPIRES_AFTER_DAYS = "entrystore.backup.maintenance.expires-after-days";
	
	public static String DATA_FOLDER = "entrystore.data.folder";
	public static String DATA_QUOTA = "entrystore.data.quota";
	public static String DATA_QUOTA_DEFAULT = "entrystore.data.quota.default";
	
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

	public static String STOREJS_JS = "entrystore.repository.storejs.js";
	public static String STOREJS_CSS = "entrystore.repository.storejs.css";
	
	public static String SOLR = "entrystore.solr";
	public static String SOLR_URL = "entrystore.solr.url";
	public static String SOLR_REINDEX_ON_STARTUP = "entrystore.solr.reindex-on-startup";
	public static String SOLR_EXTRACT_FULLTEXT = "entrystore.solr.extract-fulltext";

	public static String REPOSITORY_REWRITE_BASEREFERENCE = "entrystore.repository.rewrite-basereference";
	
	public static String REPOSITORY_CACHE = "entrystore.repository.cache";
	public static String REPOSITORY_CACHE_PATH = "entrystore.repository.cache.path";
	
	public static String REPOSITORY_PUBLIC = "entrystore.repository.public";
	public static String REPOSITORY_PUBLIC_PATH = "entrystore.repository.public.path";
	public static String REPOSITORY_PUBLIC_INDEXES = "entrystore.repository.public.indexes";
	public static String REPOSITORY_PUBLIC_TYPE = "entrystore.repository.public.type";
	public static String REPOSITORY_PUBLIC_REBUILD_ON_STARTUP = "entrystore.repository.public.rebuild-on-startup";

	public static String SMTP_HOST = "entrystore.smtp.host";
	public static String SMTP_PORT = "entrystore.smtp.port";
	public static String SMTP_SECURITY = "entrystore.smtp.security";
	public static String SMTP_USERNAME = "entrystore.smtp.username";
	public static String SMTP_PASSWORD = "entrystore.smtp.password";

	public static String SIGNUP = "entrystore.signup";
	public static String SIGNUP_FROM_EMAIL = "entrystore.signup.email.from";
	public static String SIGNUP_BCC_EMAIL = "entrystore.signup.email.bcc";
	public static String SIGNUP_SUBJECT = "entrystore.signup.email.subject";
	public static String SIGNUP_CONFIRMATION_MESSAGE_TEMPLATE_PATH = "entrystore.signup.email.template";
	public static String SIGNUP_RECAPTCHA = "entrystore.signup.recaptcha";
	public static String SIGNUP_RECAPTCHA_PRIVATE_KEY = "entrystore.signup.recaptcha.private-key";
	public static String SIGNUP_RECAPTCHA_PUBLIC_KEY = "entrystore.signup.recaptcha.public-key";
	
	private Settings() {
	}

}