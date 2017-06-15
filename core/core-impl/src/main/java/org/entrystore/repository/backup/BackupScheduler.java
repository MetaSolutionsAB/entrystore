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

package org.entrystore.repository.backup;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

import java.text.ParseException;


/**
 * Supports continuously backing up the repository.
 * 
 * @author Hannes Ebner
 */
public class BackupScheduler {

	static Log log = LogFactory.getLog(BackupScheduler.class);

	Scheduler scheduler;

	JobDetail job;

	RepositoryManager rm;

	boolean gzip;

	String timeRegularExpression;

	boolean maintenance;

	int upperLimit;

	int lowerLimit;

	int expiresAfterDays;

	public static BackupScheduler instance;

	private BackupScheduler(RepositoryManager rm, String timeRegExp, boolean gzip, boolean maintenance, int upperLimit, int lowerLimit, int expiresAfterDays) {
		try {
			scheduler = StdSchedulerFactory.getDefaultScheduler();
		} catch (SchedulerException e) {
			log.error(e.getMessage());
		}
		this.rm = rm;
		this.gzip = gzip;
		this.timeRegularExpression = timeRegExp;
		this.maintenance = maintenance;
		this.upperLimit = upperLimit;
		this.lowerLimit = lowerLimit;
		this.expiresAfterDays = expiresAfterDays;
		
		if (upperLimit == -1 || lowerLimit == -1 || expiresAfterDays == -1) {
			this.maintenance = false;
		}
		log.info("Created backup scheduler");
	}

	public static synchronized BackupScheduler getInstance(RepositoryManager rm) {
		if (instance == null) {
			log.info("Loading backup configuration");
			Config config = rm.getConfiguration();
			String timeRegExp = config.getString(Settings.BACKUP_TIMEREGEXP);
			if (timeRegExp == null) {
				return null;
			}
			boolean gzip = "on".equalsIgnoreCase(config.getString(Settings.BACKUP_GZIP, "off"));
			boolean maintenance = "on".equalsIgnoreCase(config.getString(Settings.BACKUP_MAINTENANCE, "off"));
			int upperLimit = config.getInt(Settings.BACKUP_MAINTENANCE_UPPER_LIMIT, -1);
			int lowerLimit = config.getInt(Settings.BACKUP_MAINTENANCE_LOWER_LIMIT, -1);
			int expiresAfterDays = config.getInt(Settings.BACKUP_MAINTENANCE_EXPIRES_AFTER_DAYS, -1);

			log.info("Time regular expression: " + timeRegExp);
			log.info("GZIP: " + gzip);
			log.info("Maintenance: " + maintenance);
			log.info("Maintenance upper limit: " + upperLimit);
			log.info("Maintenance lower limit: " + lowerLimit);
			log.info("Maintenance expires after days: " + expiresAfterDays);

			instance = new BackupScheduler(rm, timeRegExp, gzip, maintenance, upperLimit, lowerLimit, expiresAfterDays);
		}

		return instance;
	}

	public void run() {
		String backupStatus = rm.getConfiguration().getString(Settings.BACKUP_SCHEDULER, "off");
		if ("off".equalsIgnoreCase(backupStatus.trim())) {
			log.warn("Backup is disabled in configuration");
			return;
		}
		
		try {
			String[] names = scheduler.getJobNames("backupGroup"); 

			int index = 1;
			if (names.length > 0) {
				// this only works for up to 10 jobs in this group
				index = Integer.valueOf(names[names.length-1]);
				index++;
			}
			String jobIndex = String.valueOf(index); 

			job = new JobDetail(jobIndex, "backupGroup", BackupJob.class);
			job.getJobDataMap().put("rm", this.rm);
			job.getJobDataMap().put("gzip", this.gzip);
			job.getJobDataMap().put("maintenance", this.maintenance);
			job.getJobDataMap().put("upperLimit", this.upperLimit);
			job.getJobDataMap().put("lowerLimit", this.lowerLimit);
			job.getJobDataMap().put("expiresAfterDays", this.expiresAfterDays);
			
			CronTrigger trigger = new CronTrigger("trigger" + jobIndex, "backupGroup", jobIndex, "backupGroup", this.timeRegularExpression); 
			scheduler.addJob(job, true);
			scheduler.scheduleJob(trigger);
			scheduler.start();
		} catch (ParseException e) {
			log.error(e.getMessage());
		} catch (SchedulerException e) {
			log.error(e.getMessage());
		}
	}

//	public void stop() {
//		try {
//			scheduler.standby();
//		} catch (SchedulerException e) {
//			log.error(e.getMessage()); 
//			e.printStackTrace();
//		} 
//	}
//
//	public void start() {
//		try {
//			scheduler.start();
//		} catch (SchedulerException e) {
//			log.error(e.getMessage()); 
//		}
//	}
	
	public boolean delete() {
		try {
			if (job != null) {
				log.info("Deleting backup job");
				scheduler.deleteJob(job.getName(), job.getGroup());
				job = null;
			}
		} catch (SchedulerException e) {
			log.error(e.getMessage());
			return false;
		}
		return true;
	}

}