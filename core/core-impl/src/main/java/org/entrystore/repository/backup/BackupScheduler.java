/*
 * Copyright (c) 2007-2024 MetaSolutions AB
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

import lombok.Getter;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.MetadataUtil;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.util.LinkedList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supports continuously backing up the repository.
 *
 * @author Hannes Ebner
 */
public class BackupScheduler {

	private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

	Scheduler scheduler;

	JobDetail job;

	RepositoryManager rm;

	boolean gzip;

	@Getter
	String cronExpression;

	boolean maintenance;

	int upperLimit;

	int lowerLimit;

	int expiresAfterDays;

	boolean simple;

	boolean deleteAfter;

	boolean includeFiles;

	RDFFormat format;

	public static BackupScheduler instance;

	private BackupScheduler(RepositoryManager rm, String cronExp, boolean gzip, boolean deleteAfter, boolean includeFiles, boolean maintenance, int upperLimit, int lowerLimit, int expiresAfterDays, RDFFormat format) {
		try {
			scheduler = StdSchedulerFactory.getDefaultScheduler();
		} catch (SchedulerException e) {
			log.error(e.getMessage());
		}

		this.rm = rm;
		this.gzip = gzip;
		this.deleteAfter = deleteAfter;
		this.cronExpression = cronExp;
		this.includeFiles = includeFiles;
		this.maintenance = maintenance;
		this.upperLimit = upperLimit;
		this.lowerLimit = lowerLimit;
		this.expiresAfterDays = expiresAfterDays;
		this.format = format;

		if (upperLimit < 2 && lowerLimit < 2 && expiresAfterDays < 2) {
			log.info("Switching to simple backup strategy with one folder without date and time in name");
			this.simple = true;
			this.maintenance = false;
		}

		log.info("Created backup scheduler");
	}

	public static synchronized BackupScheduler getInstance(RepositoryManager rm) {
		if (instance == null) {
			log.info("Loading backup configuration");
			Config config = rm.getConfiguration();
			String cronExp = config.getString(Settings.BACKUP_CRONEXP, config.getString(Settings.BACKUP_TIMEREGEXP_DEPRECATED));
			if (cronExp == null) {
				return null;
			}
			boolean gzip = config.getBoolean(Settings.BACKUP_GZIP, false);
			boolean maintenance = config.getBoolean(Settings.BACKUP_MAINTENANCE, false);
			boolean deleteAfter = config.getBoolean(Settings.BACKUP_DELETE_AFTER, false);
			boolean includeFiles = config.getBoolean(Settings.BACKUP_INCLUDE_FILES, true);
			int upperLimit = config.getInt(Settings.BACKUP_MAINTENANCE_UPPER_LIMIT, -1);
			int lowerLimit = config.getInt(Settings.BACKUP_MAINTENANCE_LOWER_LIMIT, -1);
			int expiresAfterDays = config.getInt(Settings.BACKUP_MAINTENANCE_EXPIRES_AFTER_DAYS, -1);

			RDFFormat format = MetadataUtil.getRDFFormat(config.getString(Settings.BACKUP_FORMAT, RDFFormat.TRIX.getName()));
			if (format == null) {
				log.warn("Invalid backup format {}, falling back to TriX", config.getString(Settings.BACKUP_FORMAT));
				format = RDFFormat.TRIX;
			}

			if (cronExp.toLowerCase().contains("rnd")) {
				cronExp = randomizeCronString(cronExp);
			}

			log.info("Cron expression: {}", cronExp);
			log.info("GZIP: {}", gzip);
			log.info("Include files: {}", includeFiles);
			log.info("Delete previous backup after new backup: {}", deleteAfter);
			log.info("Maintenance: {}", maintenance);
			log.info("Maintenance upper limit: {}", upperLimit);
			log.info("Maintenance lower limit: {}", lowerLimit);
			log.info("Maintenance expires after days: {}", expiresAfterDays);

			instance = new BackupScheduler(rm, cronExp, gzip, deleteAfter, includeFiles, maintenance, upperLimit, lowerLimit, expiresAfterDays, format);
		}

		return instance;
	}

	private static String randomizeCronString(String cronExp) {
		String[] parts = cronExp.split("\\s+");

		if (parts.length < 6) {
			log.warn("Cron expression seems to be incorrect and cannot be parsed correctly: {}", cronExp);
			return cronExp;
		}

		LinkedList<String> result = new LinkedList<>();
		Pattern pattern = Pattern.compile("rnd\\(([\\*0-9]+)[\\-]?([0-9]*)\\)", Pattern.CASE_INSENSITIVE);

		for (int i = 0; i < parts.length; i++) {
			String p = parts[i];
			Matcher matcher = pattern.matcher(p);
			if (!matcher.matches()) {
				result.add(p);
			} else {
				String first = matcher.group(1);
				String second = matcher.group(2);
				if ("*".equals(first)) {
					if (i == 0 || i == 1) {
						// second or minute
						result.add(Integer.toString(ThreadLocalRandom.current().ints(0, 60).limit(1).findFirst().getAsInt()));
					} else if (i == 2) {
						// hour
						result.add(Integer.toString(ThreadLocalRandom.current().ints(0, 24).limit(1).findFirst().getAsInt()));
					} else {
						result.add(first);
					}
				} else if (isInt(first) && isInt(second)) {
					int i1 = Integer.parseInt(first);
					int i2 = Integer.parseInt(second);
					if (i == 0 || i == 1) {
						// second or minute
						result.add(Integer.toString(ThreadLocalRandom.current().ints(i1, i2 + 1).limit(1).findFirst().getAsInt()));
					} else if (i == 2) {
						// hour
						result.add(Integer.toString(ThreadLocalRandom.current().ints(i1, i2 + 1).limit(1).findFirst().getAsInt()));
					} else {
						result.add(first);
					}
				} else {
					result.add(p);
				}
			}
		}

		return String.join(" ", result);
	}

	private static boolean isInt(String s) {
		try {
			Integer.parseInt(s);
		} catch (NumberFormatException nfe) {
			return false;
		}
		return true;
	}

	public void run() {
		if (!rm.getConfiguration().getBoolean(Settings.BACKUP_SCHEDULER, false)) {
			log.warn("Backup is disabled in configuration");
			return;
		}

		try {
			String[] names = scheduler.getJobNames("backupGroup");

			int index = 1;
			if (names.length > 0) {
				// this only works for up to 10 jobs in this group
				index = Integer.parseInt(names[names.length - 1]);
				index++;
			}
			String jobIndex = String.valueOf(index);

			job = new JobDetail(jobIndex, "backupGroup", BackupJob.class);
			job.getJobDataMap().put("rm", this.rm);
			job.getJobDataMap().put("gzip", this.gzip);
			job.getJobDataMap().put("includeFiles", this.includeFiles);
			job.getJobDataMap().put("deleteAfter", this.deleteAfter);
			job.getJobDataMap().put("maintenance", this.maintenance);
			job.getJobDataMap().put("upperLimit", this.upperLimit);
			job.getJobDataMap().put("lowerLimit", this.lowerLimit);
			job.getJobDataMap().put("expiresAfterDays", this.expiresAfterDays);
			job.getJobDataMap().put("format", this.format);
			job.getJobDataMap().put("simple", this.simple);

			CronTrigger trigger = new CronTrigger("trigger" + jobIndex, "backupGroup", jobIndex, "backupGroup", this.cronExpression);
			scheduler.addJob(job, true);
			scheduler.scheduleJob(trigger);
			scheduler.start();
		} catch (ParseException | SchedulerException e) {
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
