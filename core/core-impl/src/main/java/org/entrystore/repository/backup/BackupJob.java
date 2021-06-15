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

import org.apache.commons.io.FileUtils;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.FileOperations;
import org.openrdf.rio.RDFFormat;
import org.quartz.InterruptableJob;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.UnableToInterruptJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;


/**
 * Runs a backup of the repository.
 * 
 * @author Hannes Ebner
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 */
public class BackupJob implements Job, InterruptableJob {

	private static final Logger log = LoggerFactory.getLogger(BackupJob.class);

	private static boolean interrupted = false; 

	public void execute(JobExecutionContext context) throws JobExecutionException {
		if (!interrupted) {
			try {
				JobDataMap dataMap = context.getJobDetail().getJobDataMap();
				RepositoryManagerImpl rm = (RepositoryManagerImpl) dataMap.get("rm");

				URI realURI = rm.getPrincipalManager().getAuthenticatedUserURI();
				try {
					// temporarily make the current user to admin
					rm.getPrincipalManager().setAuthenticatedUserURI(rm.getPrincipalManager().getAdminUser().getURI());
					// we just allow GET requests during the backup
					rm.setModificationLockOut(true);
					runBackup(context);
				} finally {
					rm.setModificationLockOut(false);
					// sets the current user back to the actually logged in user
					rm.getPrincipalManager().setAuthenticatedUserURI(realURI);
				}
				
				boolean maintenance = (Boolean) dataMap.get("maintenance");
				if (maintenance) {
					try {
						rm.getPrincipalManager().setAuthenticatedUserURI(rm.getPrincipalManager().getAdminUser().getURI());
						runBackupMaintenance(context);
					} finally {
						rm.getPrincipalManager().setAuthenticatedUserURI(realURI);
					}
				} else {
					log.info("Backup maintenance not active");
				}
			} catch (Exception e) {
				log.error(e.getMessage());
			}
		}
	}

	synchronized public static void runBackup(JobExecutionContext jobContext) throws Exception {
		if (interrupted) {
			return;
		}

		long beforeTotal = System.currentTimeMillis();
		log.info("Starting backup job");

		String currentDateTime = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
		JobDataMap dataMap = jobContext.getJobDetail().getJobDataMap();
		RepositoryManagerImpl rm = (RepositoryManagerImpl) dataMap.get("rm");
		boolean gzip = dataMap.getBoolean("gzip");
		boolean includeFiles = dataMap.getBoolean("includeFiles");
		RDFFormat format = (RDFFormat) dataMap.getOrDefault("format", RDFFormat.TRIX);
		log.info("Backup gzip: " + gzip);

		String exportPath = rm.getConfiguration().getString(Settings.BACKUP_FOLDER);
		if (exportPath == null) {
			log.error("Unknown backup path, please check the following setting: " + Settings.BACKUP_FOLDER);			
		} else {
			boolean simple = dataMap.getBoolean("simple");
			boolean deleteAfter = dataMap.getBoolean("deleteAfter");
			File newBackupDirectory;
			File oldBackupDirectory = new File(exportPath, "all-old");
			if (simple) {
				newBackupDirectory = new File(exportPath, "all");
				if (newBackupDirectory.isDirectory()) {
					if (oldBackupDirectory.exists()) {
						try {
							FileUtils.deleteDirectory(oldBackupDirectory);
							log.info("Deleted {}", oldBackupDirectory);
						} catch (IOException e) {
							log.warn("Unable to delete {}: {}", oldBackupDirectory, e);
						}
					}

					if (deleteAfter) {
						if (newBackupDirectory.renameTo(oldBackupDirectory)) {
							log.info("Renamed {} to {}", newBackupDirectory, oldBackupDirectory);
						} else {
							log.warn("Unable to rename {} to {}", newBackupDirectory, oldBackupDirectory);
						}
					} else {
						try {
							FileUtils.deleteDirectory(newBackupDirectory);
							log.info("Deleted {}", newBackupDirectory);
						} catch (IOException e) {
							log.warn("Unable to delete {}: {}", newBackupDirectory, e);
						}
					}
				}
			} else {
				newBackupDirectory = new File(exportPath, currentDateTime);
			}

			if (!newBackupDirectory.exists()) {
				if (newBackupDirectory.mkdirs()) {
					log.info("Created directory {}", newBackupDirectory);
				} else {
					log.error("Unable to create directory {}", newBackupDirectory);
					log.info("Aborting backup due to error");
					return;
				}
			}

			long before = System.currentTimeMillis();

			// Writing time stamp
			FileOperations.writeStringToFile(new File(newBackupDirectory, "BACKUP_DATE"), currentDateTime);

			// Main repo
			log.info("Exporting main repository");

			String fileName = "repository." + format.getDefaultFileExtension() + (gzip ? ".gz" : "");
			rm.exportToFile(rm.getRepository(), new File(newBackupDirectory, fileName).toURI(), gzip, format);
			log.info("Exporting main repository took " + (System.currentTimeMillis() - before) + " ms");

			// Provenance repo
			if (rm.getProvenanceRepository() != null) {
				before = System.currentTimeMillis();
				log.info("Exporting provenance repository");
				fileName = "repository_prov." + format.getDefaultFileExtension() + (gzip ? ".gz" : "");
				rm.exportToFile(rm.getProvenanceRepository(), new File(newBackupDirectory, fileName).toURI(), gzip, format);
				log.info("Exporting provenance repository took " + (System.currentTimeMillis() - before) + " ms");
			} else {
				log.info("Provenance repository is unconfigured and is therefore not be included in the backup");
			}

			// Files/binary data
			if (includeFiles) {
				String dataPath = rm.getConfiguration().getString(Settings.DATA_FOLDER);
				if (dataPath == null) {
					log.error("Unknown data path, please check the following setting: " + Settings.DATA_FOLDER);
				} else {
					before = System.currentTimeMillis();
					File dataPathFile = new File(dataPath);
					log.info("Copying data folder from " + dataPathFile + " to " + newBackupDirectory);
					FileOperations.copyPath(dataPathFile.toPath(), newBackupDirectory.toPath());
					log.info("Copying data folder took " + (System.currentTimeMillis() - before) + " ms");
				}
			} else {
				log.warn("Files not included in backup due to configuration");
			}

			// Clean-up with "delete-after"
			if (simple && deleteAfter && oldBackupDirectory.exists()) {
				try {
					FileUtils.deleteDirectory(oldBackupDirectory);
					log.info("Deleted {}", oldBackupDirectory);
				} catch (IOException e) {
					log.warn("Unable to delete {}: {}", oldBackupDirectory, e);
				}
			}
		}
		log.info("Backup job done with execution, took " + (System.currentTimeMillis() - beforeTotal) + " ms in total");
	}
	
	synchronized public static void runBackupMaintenance(JobExecutionContext jobContext) throws Exception {
		if (interrupted) {
			return;
		}

		log.info("Starting backup maintenance job");

		JobDataMap dataMap = jobContext.getJobDetail().getJobDataMap();
		RepositoryManagerImpl rm = (RepositoryManagerImpl) dataMap.get("rm");		
		
		int upperLimit = (Integer) dataMap.get("upperLimit");
		int lowerLimit = (Integer) dataMap.get("lowerLimit");
		int expiresAfterDays = (Integer) dataMap.get("expiresAfterDays");
		
		log.info("upperlimit: " + upperLimit + ", lowerLimit: " + lowerLimit + ", expiresAfterDays: " + expiresAfterDays);

		String exportPath = rm.getConfiguration().getString(Settings.BACKUP_FOLDER);
		Date today = new Date();

		if (exportPath == null) {
			log.error("Unknown backup path, please check the following setting: " + Settings.BACKUP_FOLDER);			
		} else {
			File backupFolder = new File(exportPath);
			DateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");

			List<Date> backupFolders = new LinkedList<>();
			for (File file : backupFolder.listFiles()) {
				if (!file.isDirectory() || file.isHidden()) {
					log.info("Ignoring: " + file);
					continue;
				}

				try {
					Date date = formatter.parse(file.getName());
					backupFolders.add(date);
					log.info("Found backup folder: " + file);
				} catch (ParseException pe) {
					log.warn("Skipping path: " + file + ". Reason: "+ pe.getMessage());
					continue;
				}
			}
			
			if (backupFolders.size() <= lowerLimit) {
				log.info("Lower limit not reached - backup maintenance job done with execution");
				return;
			}

			Collections.sort(backupFolders, (a, b) -> {
				if (a.after(b)) {
					return 1;
				} else if (a.before(b)) {
					return -1;
				} else {
					return 0;
				}
			});

			if (backupFolders.size() > upperLimit) {
				int nrRemoveItems = backupFolders.size() - upperLimit;
				log.info("Upper limit is " + upperLimit + ", will delete " + nrRemoveItems + " backup folder(s)");
				for (int i = 0; i < nrRemoveItems; i++) {
					String folder = formatter.format(backupFolders.get(i));
					File f = new File(exportPath, folder);
					if (FileOperations.deleteDirectory(f)) {
						backupFolders.remove(i);
						log.info("Deleted " + f);
					} else {
						log.info("Unable to delete " + f);
					}
				}
			}

			Date oldestDate = backupFolders.get(0);
			
			if (daysBetween(oldestDate, today) > expiresAfterDays) {
				for (int size = backupFolders.size(), i = 0; lowerLimit < size; size--) {
					Date d = backupFolders.get(i); 
					if (daysBetween(d, today) > expiresAfterDays) {
						String folder = formatter.format(backupFolders.get(i)); 
						File f = new File(exportPath, folder);
						if (FileOperations.deleteDirectory(f)) {
							backupFolders.remove(i);
							log.info("Deleted " + f);
						} else {
							log.info("Unable to delete " + f);
						}
					} else {
						break;
					}
				}
			}
		}

		log.info("Backup maintenance job done with execution");
	}
	
	public static long daysBetween(Date d1, Date d2) {
		long oneHour = 60 * 60 * 1000L;
		return ((d2.getTime() - d1.getTime() + oneHour) / (oneHour * 24));
	}

	public void interrupt() throws UnableToInterruptJobException {
		interrupted = true;
	}

}