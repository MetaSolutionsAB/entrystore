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

package org.entrystore.impl;

import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.MessageDigestAlgorithms;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.QuotaException;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.FileOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.apache.commons.codec.Charsets.UTF_8;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256;
import static org.eclipse.rdf4j.model.util.Values.iri;


/**
 * Data class to handle binary local resources.
 *
 * @author Eric Johansson
 * @author Hannes Ebner
 */
public class DataImpl extends ResourceImpl implements Data {

	private static final Logger log = LoggerFactory.getLogger(DataImpl.class);
	public static final String SHA_256_POSTFIX = ".sha256";

	private File file = null;

	public DataImpl(Entry entry) {
		super((EntryImpl) entry, iri(entry.getResourceURI().toString()));
	}

	private File getFile() throws IOException {
		if (file == null) {
			String dataDirStr = entry.getRepositoryManager().getConfiguration().getString(Settings.DATA_FOLDER);
			if (dataDirStr != null) {
				// Workaround to handle allowed "file:" prefixes.
				dataDirStr = StringUtils.removeStart(dataDirStr, "file://");
				dataDirStr = StringUtils.removeStart(dataDirStr, "file:");
				File dataDir = new File(dataDirStr);
				if (!dataDir.exists()) {
					if (!dataDir.mkdirs()) {
						log.error("Unable to create data folder");
					}
				}
				File contextDir = new File(dataDir, entry.getContext().getEntry().getId());
				if (!contextDir.exists()) {
					contextDir.mkdir();
				}
				file = new File(contextDir, entry.getId());
			}
			if (file == null) {
				throw new IOException("Unable to get local file of resource");
			}
		}
		return file;
	}

	public InputStream getData() {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
		try {
			if (getFile() != null) {
				return Files.newInputStream(getFile().toPath());
			}
		} catch (IOException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public void setData(InputStream is) throws QuotaException, IOException {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		MessageDigest sha = null;
		try {
			sha = MessageDigest.getInstance(SHA_256);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		Path dataPath = getFile().toPath();
		long bytes = FileOperations.copyFile(new DigestInputStream(is, sha), Files.newOutputStream(dataPath));
		writeDigest(sha);

		if (entry.getRepositoryManager().hasQuotas()) {
			try {
				entry.getContext().increaseQuotaFillLevel(bytes);
			} catch (QuotaException qe) {
				if (file.exists()) {
					file.delete();
				}
				File digestFile = getDigestFile();
				if (digestFile != null && digestFile.exists()) {
					digestFile.delete();
				}
				throw qe;
			}
		}

		entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
	}

	public void useData(File file) throws IOException {
		if (file == null) {
			throw new IllegalArgumentException("File must not be null");
		}

		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		long sizeBefore = 0;
		long sizeAfter = 0;
		if (entry.getRepositoryManager().hasQuotas()) {
			if (getFile() != null && getFile().exists()) {
				sizeBefore = getFile().length();
			}
			if (file != null && file.exists()) {
				sizeAfter = file.length();
			}
		}

		FileOperations.copyFile(file, getFile());

		if (entry.getRepositoryManager().hasQuotas()) {
			entry.getContext().decreaseQuotaFillLevel(sizeBefore);
			try {
				entry.getContext().increaseQuotaFillLevel(sizeAfter);
			} catch (QuotaException qe) {
				getFile().delete();
			}
		}

		entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
	}

	@Override
	public void remove(RepositoryConnection rc) throws Exception {
		super.remove(rc);
		delete();
	}

	public boolean delete() {
		boolean success = false;
		try {
			File f = getFile();
			File digestFile = getDigestFile();
			if (f != null && f.exists()) {
				long size = f.length();
				success = f.delete();
				if (success && entry.getRepositoryManager().hasQuotas()) {
					entry.getContext().decreaseQuotaFillLevel(size);
				}
				if (digestFile != null && digestFile.exists()) {
					digestFile.delete();
				}
				entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceDeleted));
			}
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
		}
		return success;
	}

	public File getDataFile() {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
		try {
			File f = getFile();
			if (f != null && f.exists()) {
				return f;
			}
		} catch (IOException ioe) {
			log.error(ioe.getMessage());
		}
		return null;
	}

	private File getDigestFile() {
		File dataFile = getDataFile();
		if (dataFile == null) {
			return null;
		}
		String digestFileName = null;
		try {
			digestFileName = dataFile.getCanonicalPath() + SHA_256_POSTFIX;
		} catch (IOException e) {
			log.error("Could not get canonical path of: " + dataFile.getAbsolutePath(), e);
			return null;
		}
		File digestFile = new File(digestFileName);
		return digestFile;
	}

	private void writeDigest(MessageDigest messageDigest) throws IOException {
		byte[] digest = messageDigest.digest();
		String s = String.valueOf(Hex.encodeHex(digest));
		FileUtils.writeStringToFile(getDigestFile(), s, UTF_8);
	}

	public String readDigest() {
		File digestFile = getDigestFile();
		if (digestFile == null) {
			return null;
		}
		try {
			return FileUtils.readFileToString(digestFile, UTF_8);
		} catch (IOException e) {
			return null;
		}
	}
}
