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

package se.kmr.scam.repository.backup;

import java.net.URI;
import java.text.ParseException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;

/**
 * Supports continuously backing up the repository.
 * 
 * @author Hannes Ebner
 */
public class BackupScheduler {

	Log log = LogFactory.getLog(BackupScheduler.class);
	Scheduler scheduler;
	JobDetail job;
	RepositoryManagerImpl rm;
	boolean gzip;
	String timeRegularExpression;
	URI backupEntryURI;
	boolean maintenance;
	int upperLimit;
	int lowerLimit;
	int expiresAfterDays;

	public BackupScheduler(URI entryURI, RepositoryManagerImpl rm, String timeRegExp, boolean gzip, boolean maintenance, int upperLimit, int lowerLimit, int expiresAfterDays) {
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
		
		this.backupEntryURI = entryURI;
		log.info("Created backup scheduler");
	}

	public void run() {
		String backupStatus = rm.getConfiguration().getString(Settings.SCAM_BACKUP_SCHEDULER, "off");
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
			job.getJobDataMap().put("contextURI", this.backupEntryURI);
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

//	private JobDetail getJob() {
//		try {
//			String[] names = scheduler.getJobNames("group");
//			for(int i = 0; i < names.length; i++) {
//				JobDetail job = scheduler.getJobDetail(names[i].toString(), "group");
//				if (((URI)job.getJobDataMap().get("contextURI")).toString().equals(this.backupEntryURI)) {
//					return job;
//				}
//			}
//		} catch (SchedulerException e) {
//			log.error(e.getMessage());
//		}
//		return null; 
//	}

	public boolean hasCompression() {
		return gzip;
	}
	
	public boolean hasMaintenance() {
		return maintenance;
	}

	public String getTimeRegularExpression() {
		return timeRegularExpression;
	}
	
	public int getUpperLimit() {
		return upperLimit;
	}
	
	public int getLowerLimit() {
		return lowerLimit;
	}
	
	public int getExpiresAfterDays() {
		return expiresAfterDays;
	}

}