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

package org.entrystore.repository.backup;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.function.IORunnable;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.entrystore.impl.DataImpl;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.DateUtils;
import org.entrystore.repository.util.FileOperations;
import org.quartz.DisallowConcurrentExecution;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs a backup of the repository. {@link DisallowConcurrentExecution} keeps Quartz from starting a backup
 * while one is still running; overlapping fires would otherwise run redundant backups back-to-back, each
 * locking out writes again.
 *
 * @author Hannes Ebner
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 */
@DisallowConcurrentExecution
public class BackupJob implements Job, InterruptableJob {

	private static final Logger log = LoggerFactory.getLogger(BackupJob.class);

	private static boolean interrupted = false;

	private static void writeErrorStatus(File errorFile, Throwable failure, String backupDateTime) {
		String errorFileContent = Stream.concat(Stream.of(backupDateTime, failure.toString()),
				Arrays.stream(failure.getSuppressed()).map(Throwable::toString)).collect(Collectors.joining("\n"));
		try {
			FileOperations.writeStringToFile(errorFile, errorFileContent);
		} catch (IOException e) {
			log.error("Unable to write {}", errorFile, e);
		}
	}

	private static File getErrorStatusFile(File backupDirectory) {
		return new File(backupDirectory, "BACKUP_FAILED");
	}

	private static File getBackupDateFile(File backupDirectory) {
		return new File(backupDirectory, "BACKUP_DATE");
	}

	/**
	 * Whether a status marker is present. Only a regular file counts, as the data folder copied into a backup may
	 * hold a directory of the same name.
	 */
	private static boolean isMarker(File marker) {
		return Files.isRegularFile(marker.toPath());
	}

	/**
	 * Whether {@code backupDirectory} holds a completed backup. Older versions also wrote BACKUP_DATE for a failed
	 * backup, next to BACKUP_FAILED, so both files are checked.
	 */
	private static boolean isSuccessfulBackup(File backupDirectory) {
		return isMarker(getBackupDateFile(backupDirectory)) && !isMarker(getErrorStatusFile(backupDirectory));
	}

	/**
	 * Whether a dated backup folder is marked as failed by this version. A folder with both markers, written by
	 * earlier versions when the data copy failed, holds a complete repository dump and counts as a backup, as does a
	 * folder without markers, written before 4.11 or by a run that was killed.
	 */
	private static boolean isFailedBackup(File backupDirectory) {
		return isMarker(getErrorStatusFile(backupDirectory)) && !isMarker(getBackupDateFile(backupDirectory));
	}

	private static void deleteBackupDirectory(File backupDirectory) {
		if (backupDirectory.exists()) {
			try {
				FileUtils.forceDelete(backupDirectory);
				log.info("Deleted {}", backupDirectory);
			} catch (IOException e) {
				log.warn("Unable to delete {}", backupDirectory, e);
			}
		}
	}

	/** Runs one step of a backup; its failure is collected, so that the remaining steps still run. */
	private static void attempt(List<Exception> failures, IORunnable step) {
		try {
			step.run();
		} catch (Exception e) {
			failures.add(e);
		}
	}

	private static void exportRepository(RepositoryManagerImpl rm, Repository repository, String name, File file,
			boolean gzip, RDFFormat format) throws IOException {
		long before = System.currentTimeMillis();
		log.info("Exporting {}", name);
		rm.exportToFile(repository, file.toURI(), gzip, format);
		log.info("Exporting {} took {} ms", name, System.currentTimeMillis() - before);
	}

	/**
	 * Deletes failed backups once a newer completed backup supersedes them. Newer ones are kept, as a run that failed
	 * after its export holds the newest restorable repository dump, but at most {@code upperLimit} of them (at least
	 * one), so that a failure on every run cannot fill the disk. Backup folder names sort chronologically.
	 */
	private static void deleteFailedBackups(List<File> failedBackupFolders, String newestCompletedBackup,
			int upperLimit) {
		failedBackupFolders.sort(Comparator.comparing(File::getName).reversed());
		int kept = 0;
		for (File failedBackup : failedBackupFolders) {
			if (failedBackup.getName().compareTo(newestCompletedBackup) > 0 && kept < Math.max(upperLimit, 1)) {
				kept++;
			} else {
				deleteBackupDirectory(failedBackup);
			}
		}
	}

	/**
	 * Writes BACKUP_DATE. A partly written one is deleted on failure, as it would mark the backup completed.
	 */
	private static void writeBackupDate(File backupDirectory, String backupDateTime) throws IOException {
		File backupDateFile = getBackupDateFile(backupDirectory);
		try {
			FileOperations.writeStringToFile(backupDateFile, backupDateTime);
		} catch (IOException e) {
			try {
				Files.deleteIfExists(backupDateFile.toPath());
			} catch (IOException deleteFailure) {
				e.addSuppressed(deleteFailure);
			}
			throw e;
		}
	}

	public void execute(JobExecutionContext context) {
		if (interrupted) {
			interrupted = false;
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
			log.error("Backup execution failed", e);
		}
	}

	/**
	 * Runs a backup. An error in one step does not stop the others, so that as much as possible is backed up, but the
	 * backup is then marked with BACKUP_FAILED instead of BACKUP_DATE. A run without a backup folder setting, that
	 * cannot create its folder, or that cannot replace "all-old" with "all" writes neither marker; a failed replace
	 * leaves "all" untouched. Any other failure to delete "all-old" is only logged.
	 *
	 * @return whether the backup completed without errors
	 */
	synchronized public static boolean runBackup(JobExecutionContext jobContext) {
		if (interrupted) {
			interrupted = false;
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

		boolean succeeded = false;
		String exportPath = rm.getConfiguration().getString(Settings.BACKUP_FOLDER);
		if (exportPath == null) {
			log.error("Unknown backup path, please check the following setting: {}", Settings.BACKUP_FOLDER);
		} else {
			boolean simple = dataMap.getBoolean("simple");
			boolean deleteAfter = dataMap.getBoolean("deleteAfter");
			File oldBackupDirectory = new File(exportPath, "all-old");
			File newBackupDirectory = new File(exportPath, currentDateTime);

			try {
				if (simple) {
					newBackupDirectory = new File(exportPath, "all");
					// failing to delete "all" fails the backup, as files left from the previous one would be dated anew
					if (newBackupDirectory.isDirectory()) {
						if (!deleteAfter) {
							deleteBackupDirectory(oldBackupDirectory);
							FileUtils.deleteDirectory(newBackupDirectory);
						} else if (isSuccessfulBackup(newBackupDirectory) || !isSuccessfulBackup(oldBackupDirectory)) {
							try {
								if (oldBackupDirectory.exists()) {
									FileUtils.forceDelete(oldBackupDirectory);
								}
								Files.move(newBackupDirectory.toPath(), oldBackupDirectory.toPath());
								log.info("Renamed {} to {}", newBackupDirectory, oldBackupDirectory);
							} catch (IOException e) {
								// "all" may hold the only restorable backup and must not be overwritten
								log.error("Unable to replace {} with {}", oldBackupDirectory, newBackupDirectory, e);
								log.info("Aborting backup due to unrecoverable error");
								return false;
							}
						} else {
							// a failed backup is replaced, keeping the last completed one in "all-old"
							FileUtils.deleteDirectory(newBackupDirectory);
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
						return false;
					}
				}

				String extension = "." + format.getDefaultFileExtension() + (gzip ? ".gz" : "");
				File backupDirectory = newBackupDirectory;
				List<Exception> failures = new ArrayList<>();
				attempt(failures, () -> exportRepository(rm, rm.getRepository(), "main repository",
						new File(backupDirectory, "repository" + extension), gzip, format));
				if (rm.getProvenanceRepository() != null) {
					attempt(failures, () -> exportRepository(rm, rm.getProvenanceRepository(), "provenance repository",
							new File(backupDirectory, "repository_prov" + extension), gzip, format));
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
						log.info("Copying data folder from {} to {}", dataPathFile, backupDirectory);
						attempt(failures, () -> {
							FileOperations.copyPath(dataPathFile.toPath(), backupDirectory.toPath(),
									DataImpl::isStagingFile);
							log.info("Copying data folder took {} ms", System.currentTimeMillis() - beforeFileExport);
						});
					}
				} else {
					log.warn("Files not included in backup due to configuration");
				}

				if (!failures.isEmpty()) {
					Exception failure = failures.getFirst();
					failures.stream().skip(1).forEach(failure::addSuppressed);
					throw failure;
				}

				writeBackupDate(newBackupDirectory, currentDateTime);
				// Clean-up with "delete-after", once the new backup is complete
				if (simple && deleteAfter) {
					deleteBackupDirectory(oldBackupDirectory);
				}
				succeeded = true;
			} catch (Throwable e) {
				log.error("Backup failed", e);
				writeErrorStatus(getErrorStatusFile(newBackupDirectory), e, currentDateTime);
				if (e instanceof Error error) {
					throw error;
				}
			}
		}

		log.info("Backup job done with execution, took {} ms in total", System.currentTimeMillis() - beforeTotal);
		return succeeded;
	}

	synchronized public static void runBackupMaintenance(JobExecutionContext jobContext) {
		if (interrupted) {
			interrupted = false;
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
			if (!backupFolder.exists() || !backupFolder.isDirectory() || backupFolder.listFiles() == null) {
				log.error("Backup folder {} does not exist or is not a directory", exportPath);
			} else {
				DateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");

				List<Date> backupFolders = new LinkedList<>();
				List<File> failedBackupFolders = new ArrayList<>();
				// only a dated backup supersedes failed ones; an unmarked folder may hold a truncated dump
				String newestCompletedBackup = "";
				for (File file : backupFolder.listFiles()) {
					if (!file.isDirectory() || file.isHidden()) {
						log.info("Ignoring: {}", file);
						continue;
					}

					try {
						Date date = formatter.parse(file.getName());
						if (!formatter.format(date).equals(file.getName())) {
							log.warn("Skipping path: {}. Reason: not a backup folder name", file);
						} else if (isFailedBackup(file)) {
							failedBackupFolders.add(file);
							log.info("Found failed backup folder: {}", file);
						} else {
							backupFolders.add(date);
							log.info("Found backup folder: {}", file);
							if (isSuccessfulBackup(file) && file.getName().compareTo(newestCompletedBackup) > 0) {
								newestCompletedBackup = file.getName();
							}
						}
					} catch (ParseException pe) {
						log.warn("Skipping path: {}. Reason: {}", file, pe.getMessage());
					}
				}

				deleteFailedBackups(failedBackupFolders, newestCompletedBackup, upperLimit);

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

				Date oldestDate = backupFolders.getFirst();

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
		}

		log.info("Backup maintenance job done with execution");
	}

	public void interrupt() {
		interrupted = true;
	}

}
