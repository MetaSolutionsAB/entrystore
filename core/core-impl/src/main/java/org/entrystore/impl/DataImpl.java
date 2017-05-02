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

package org.entrystore.impl;

import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.QuotaException;
import org.entrystore.repository.RepositoryEvent;
import org.entrystore.repository.RepositoryEventObject;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.FileOperations;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;


/**
 * Data class to handle binary local resources.
 * 
 * @author Eric Johansson
 * @author Hannes Ebner
 */
public class DataImpl extends ResourceImpl implements Data {

	private File file = null;
	
	private static Logger log = LoggerFactory.getLogger(DataImpl.class);

	public DataImpl(Entry entry) {
		super((EntryImpl) entry, new URIImpl(entry.getResourceURI().toString()));
	}

	private File getFile() throws IOException {
		if (file == null) {
			String dataDirStr = entry.getRepositoryManager().getConfiguration().getString(Settings.DATA_FOLDER);
			if (dataDirStr != null) {
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
				return new FileInputStream(getFile());
			}
		} catch (IOException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	public void setData(InputStream is) throws QuotaException, IOException {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.WriteResource);

		long bytes = FileOperations.copyFile(is, new FileOutputStream(getFile()));

		if (entry.getRepositoryManager().hasQuotas()) {
			try {
				entry.getContext().increaseQuotaFillLevel(bytes);
			} catch (QuotaException qe) {
				if (file.exists()) {
					file.delete();
				}
				throw qe;
			}
		}
		
		entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceUpdated));
	}

	public void useData(File file) throws QuotaException, IOException {
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
			if (f != null && f.exists()) {
				long size = f.length();
				success = f.delete();
				if (success && entry.getRepositoryManager().hasQuotas()) {
					entry.getContext().decreaseQuotaFillLevel(size);
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

}