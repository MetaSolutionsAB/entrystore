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

import org.apache.commons.io.FileUtils;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.SailException;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.DateUtils;
import org.entrystore.repository.util.FileOperations;
import org.quartz.InterruptableJob;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * Runs a backup of the repository.
 *
 * @author Hannes Ebner
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 */
public class BackupJob implements Job, InterruptableJob {

	private static final Logger log = LoggerFactory.getLogger(BackupJob.class);

	private static boolean interrupted = false;

	private static void writeErrorStatus(File errorFile, List<String> errors, String backupDateTime) {
		String errorFileContent = backupDateTime + "\n" + String.join("\n", errors);
		FileOperations.writeStringToFile(errorFile, errorFileContent);
	}

	private static File getErrorStatusFile(File backupDirectory) {
		return new File(backupDirectory, "BACKUP_FAILED");
	}

	public void execute(JobExecutionContext context) {
		if (interrupted) {
			throw new RuntimeException("Backup job \"execute()\" was interrupted");
		}

		try {
			JobDataMap dataMap = context.getJobDetail().getJobDataMap();
			RepositoryManagerImpl rm = (RepositoryManagerImpl) dataMap.get("rm");

			URI realURI = rm.getPrincipalManager().getAuthenticatedUserURI();
			try {
				// temporarily make the current user to admin
				rm.getPrincipalManager().setAuthenticatedUserURI(rm.getPrincipalManager().getAdminUser().getURI());
				// we just allow the GET requests during the backup
				rm.setModificationLockOut(true);
				runBackup(context);
			} finally {
				rm.setModificationLockOut(false);
				// sets the current user back to the actually logged-in user
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

	synchronized public static void runBackup(JobExecutionContext jobContext) {
		if (interrupted) {
			throw new RuntimeException("Backup job \"runBackup()\" was interrupted");
		}

		long beforeTotal = System.currentTimeMillis();
		log.info("Starting backup job");

		String currentDateTime = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
		JobDataMap dataMap = jobContext.getJobDetail().getJobDataMap();
		RepositoryManagerImpl rm = (RepositoryManagerImpl) dataMap.get("rm");
		boolean gzip = dataMap.getBoolean("gzip");
		boolean includeFiles = dataMap.getBoolean("includeFiles");
		RDFFormat format = (RDFFormat) dataMap.getOrDefault("format", RDFFormat.TRIX);
		log.info("Backup gzip: {}", gzip);

		String exportPath = rm.getConfiguration().getString(Settings.BACKUP_FOLDER);
		if (exportPath == null) {
			log.error("Unknown backup path, please check the following setting: {}", Settings.BACKUP_FOLDER);
		} else {
			boolean simple = dataMap.getBoolean("simple");
			boolean deleteAfter = dataMap.getBoolean("deleteAfter");
			File oldBackupDirectory = new File(exportPath, "all-old");
			File newBackupDirectory = new File(exportPath, currentDateTime);
			List<String> errors = new LinkedList<>();

			try {
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
				}

				if (!newBackupDirectory.exists()) {
					if (newBackupDirectory.mkdirs()) {
						log.info("Created directory {}", newBackupDirectory);
					} else {
						// sadly, since we don't have a directory to write to,
						// we cannot create an error status file either
						log.error("Unable to create directory {}", newBackupDirectory);
						log.info("Aborting backup due to unrecoverable error");
						return;
					}
				}

				// Main repo
				long beforeMainExport = System.currentTimeMillis();
				log.info("Exporting main repository");
				String mainRepoFile = "repository." + format.getDefaultFileExtension() + (gzip ? ".gz" : "");
				try {
					rm.exportToFile(rm.getRepository(), new File(newBackupDirectory, mainRepoFile).toURI(), gzip, format);
					log.info("Exporting main repository took {} ms", System.currentTimeMillis() - beforeMainExport);
				} catch (SailException se) {
					log.error("Unable to export main repository {}", se.getMessage());
					errors.add(se.getMessage());
				}

				// Provenance repo
				if (rm.getProvenanceRepository() != null) {
					long beforeProvExport = System.currentTimeMillis();
					log.info("Exporting provenance repository");
					String provRepoFile = "repository_prov." + format.getDefaultFileExtension() + (gzip ? ".gz" : "");
					try {
						rm.exportToFile(rm.getProvenanceRepository(), new File(newBackupDirectory, provRepoFile).toURI(), gzip, format);
						log.info("Exporting provenance repository took {} ms", System.currentTimeMillis() - beforeProvExport);
					} catch (SailException se) {
						log.error("Unable to export provenance repository {}", se.getMessage());
						errors.add(se.getMessage());
					}
				} else {
					log.info("Provenance repository is not configured and is therefore not be included in the backup");
				}

				// Files/binary data
				if (includeFiles) {
					String dataPath = rm.getConfiguration().getString(Settings.DATA_FOLDER);
					if (dataPath == null) {
						log.error("Unknown data path, please check the following setting: {}", Settings.DATA_FOLDER);
					} else {
						long beforeFileExport = System.currentTimeMillis();
						File dataPathFile = new File(dataPath);
						log.info("Copying data folder from {} to {}", dataPathFile, newBackupDirectory);
						try {
							FileOperations.copyPath(dataPathFile.toPath(), newBackupDirectory.toPath());
							log.info("Copying data folder took {} ms", System.currentTimeMillis() - beforeFileExport);
						} catch (IOException ioe) {
							log.error("Unable to copy data folder from {} to {}", dataPathFile, newBackupDirectory);
							errors.add(ioe.getMessage());
						}
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
						log.warn("Unable to delete {}: {}", oldBackupDirectory, e.getMessage());
					}
				}

				// Writing time stamp
				FileOperations.writeStringToFile(new File(newBackupDirectory, "BACKUP_DATE"), currentDateTime);

				// Removing eventually an existing failed status file
				try {
					Files.deleteIfExists(getErrorStatusFile(newBackupDirectory).toPath());
				} catch (IOException e) {
					log.warn("Unable to delete {}: {}", getErrorStatusFile(newBackupDirectory), e.getMessage());
				}
			} catch (Exception e) {
				log.error("Backup failed: {}", e.getMessage(), e);
				errors.add(e.getMessage());
			}

			if (!errors.isEmpty()) {
				File errorStatusFile = getErrorStatusFile(newBackupDirectory);
				writeErrorStatus(errorStatusFile, errors, currentDateTime);
			}
		}

		log.info("Backup job done with execution, took {} ms in total", System.currentTimeMillis() - beforeTotal);
	}

	synchronized public static void runBackupMaintenance(JobExecutionContext jobContext) {
		if (interrupted) {
			throw new RuntimeException("Backup job \"runBackupMaintenance()\" was interrupted");
		}

		log.info("Starting backup maintenance job");

		JobDataMap dataMap = jobContext.getJobDetail().getJobDataMap();
		RepositoryManagerImpl rm = (RepositoryManagerImpl) dataMap.get("rm");

		int upperLimit = (Integer) dataMap.get("upperLimit");
		int lowerLimit = (Integer) dataMap.get("lowerLimit");
		int expiresAfterDays = (Integer) dataMap.get("expiresAfterDays");

		log.info("upperlimit: {}, lowerLimit: {}, expiresAfterDays: {}", upperLimit, lowerLimit, expiresAfterDays);

		String exportPath = rm.getConfiguration().getString(Settings.BACKUP_FOLDER);
		Date today = new Date();

		if (exportPath == null) {
			log.error("Unknown backup path, please check the following setting: {}", Settings.BACKUP_FOLDER);
		} else {
			File backupFolder = new File(exportPath);
			DateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");

			List<Date> backupFolders = new LinkedList<>();
			for (File file : Objects.requireNonNull(backupFolder.listFiles())) {
				if (!file.isDirectory() || file.isHidden()) {
					log.info("Ignoring: {}", file);
					continue;
				}

				try {
					Date date = formatter.parse(file.getName());
					backupFolders.add(date);
					log.info("Found backup folder: {}", file);
				} catch (ParseException pe) {
					log.warn("Skipping path: {}. Reason: {}", file, pe.getMessage());
				}
			}

			if (backupFolders.size() <= lowerLimit) {
				log.info("Lower limit not reached - backup maintenance job done with execution");
				return;
			}

			backupFolders.sort((a, b) -> {
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
				log.info("Upper limit is {}, will delete {} backup folder(s)", upperLimit, nrRemoveItems);
				for (int i = 0; i < nrRemoveItems; i++) {
					String folder = formatter.format(backupFolders.get(i));
					File f = new File(exportPath, folder);
					if (FileOperations.deleteDirectory(f)) {
						backupFolders.remove(i);
						log.info("Deleted {}", f);
					} else {
						log.info("Unable to delete {}", f);
					}
				}
			}

			Date oldestDate = backupFolders.get(0);

			if (DateUtils.daysBetween(oldestDate, today) > expiresAfterDays) {
				for (int size = backupFolders.size(), i = 0; lowerLimit < size; size--) {
					Date d = backupFolders.get(i);
					if (DateUtils.daysBetween(d, today) > expiresAfterDays) {
						String folder = formatter.format(backupFolders.get(i));
						File f = new File(exportPath, folder);
						if (FileOperations.deleteDirectory(f)) {
							backupFolders.remove(i);
							log.info("Deleted {}", f);
						} else {
							log.info("Unable to delete {}", f);
						}
					} else {
						break;
					}
				}
			}
		}

		log.info("Backup maintenance job done with execution");
	}

	public void interrupt() {
		interrupted = true;
	}

}
