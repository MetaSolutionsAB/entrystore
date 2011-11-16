/**
 * Copyright (c) 2007-2010
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

package se.kmr.scam.repository.config;

/**
 * Contains most of the property keys used within SCAM.
 * 
 * @author Hannes Ebner
 * @version $Id$
 */
public class Settings {
	
	public static String SCAM_AUTH_SCHEME = "scam.auth.scheme";
	public static String SCAM_AUTH_ADMIN_SECRET = "scam.auth.adminpw";
	
	public static String SCAM_BACKUP_FOLDER = "scam.backup.folder";
	public static String SCAM_BACKUP_SCHEDULER = "scam.backup.scheduler";
	
	public static String SCAM_BACKUP_MAINTENANCE = "scam.backup.maintenance";						 
	public static String SCAM_BACKUP_MAINTENANCE_UPPER_BOUND = "scam.backup.maintenance.bound.upper";
	public static String SCAM_BACKUP_MAINTENANCE_LOWER_BOUND = "scam.backup.maintenance.bound.lower";
	public static String SCAM_BACKUP_MAINTENANCE_EXPIRES_AFTER_DAYS = "scam.backup.maintenance.expires-after-days";
	public static String SCAM_BACKUP_MAINTENANCE_TIMEREGEXP = "scam.backup.maintenance.timeregexp";
	
	public static String SCAM_DATA_FOLDER = "scam.data.folder";
	public static String SCAM_DATA_QUOTA = "scam.data.quota";
	public static String SCAM_DATA_QUOTA_DEFAULT = "scam.data.quota.default";
	
	public static String SCAM_BASE_URL = "scam.baseurl.folder";
	
	public static String SCAM_HARVESTER_OAI = "scam.harvester.oai";
	public static String SCAM_HARVESTER_OAI_MULTITHREADED = "scam.harvester.oai.multithreaded";
	public static String SCAM_HARVESTER_OAI_METADATA_POLICY = "scam.harvester.oai.policy"; // skip | replace
	public static String SCAM_HARVESTER_OAI_FROM_AUTO_DETECT = "scam.harvester.oai.from.auto-detect";
	public static String SCAM_HARVESTER_FAO = "scam.harvester.fao";
	
	public static String SCAM_HARVESTING_TARGET_OAI_BASE_URI = "Identify.scam.baseuri";
	
	public static String SCAM_STORE_USER = "scam.repository.store.user";
	public static String SCAM_STORE_PWD = "scam.repository.store.password";
	public static String SCAM_STORE_DBNAME = "scam.repository.store.database.name";
	public static String SCAM_STORE_PORTNR = "scam.repository.store.port.number";
	public static String SCAM_STORE_SERVERNAME = "scam.repository.store.server.name";
	public static String SCAM_STORE_MAX_TRIPLE_TABLES = "scam.repository.store.max-triple-tables";
	public static String SCAM_STORE_PATH = "scam.repository.store.path";
	public static String SCAM_STORE_INDEXES = "scam.repository.store.indexes";
	public static String SCAM_STORE_TYPE = "scam.repository.store.type";
	
	public static String SCAM_SOLR = "scam.solr";
	public static String SCAM_SOLR_URL = "scam.solr.url";
	public static String SCAM_SOLR_REINDEX_ON_STARTUP = "scam.solr.reindex-on-startup";
	public static String SCAM_SOLR_EXTRACT_FULLTEXT = "scam.solr.extract-fulltext";
	
	public static String SCAM_REPOSITORY_IMPORT = "scam.repository.import";
	public static String SCAM_REPOSITORY_IMPORT_FILE = "scam.repository.import.file";
	public static String SCAM_REPOSITORY_IMPORT_BASE = "scam.repository.import.base";
	
	public static String SCAM_REPOSITORY_CACHE = "scam.repository.cache";
	public static String SCAM_REPOSITORY_CACHE_PATH = "scam.repository.cache.path";
	
	public static String SCAM_REPOSITORY_PUBLIC = "scam.repository.public";
	public static String SCAM_REPOSITORY_PUBLIC_PATH = "scam.repository.public.path";
	public static String SCAM_REPOSITORY_PUBLIC_INDEXES = "scam.repository.public.indexes";
	public static String SCAM_REPOSITORY_PUBLIC_TYPE = "scam.repository.public.type";
	public static String SCAM_REPOSITORY_PUBLIC_REBUILD_ON_STARTUP = "scam.repository.public.rebuild-on-startup";
	
	private Settings() {
	}

}