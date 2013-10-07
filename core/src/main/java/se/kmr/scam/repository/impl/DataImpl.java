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

package se.kmr.scam.repository.impl;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.openrdf.model.impl.URIImpl;
import org.openrdf.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.Data;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.QuotaException;
import se.kmr.scam.repository.RepositoryEvent;
import se.kmr.scam.repository.RepositoryEventObject;
import se.kmr.scam.repository.PrincipalManager.AccessProperty;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.util.FileOperations;

/**
 * Data class to handle binary local resources.
 * 
 * @author Eric Johansson (eric.johansson@educ.umu.se)
 * @author Hannes Ebner
 */
public class DataImpl extends ResourceImpl implements Data {

	private File file = null;
	
	private static Logger log = LoggerFactory.getLogger(DataImpl.class);

	public DataImpl(Entry entry) {
		super((EntryImpl) entry, new URIImpl(entry.getResourceURI().toString()));
	}

	private File getFile() {
		if (file == null) {
			String dataPath = entry.getRepositoryManager().getConfiguration().getString(Settings.DATA_FOLDER, System.getProperty("user.home") + "/scam-data-files/");
			File dataDir = new File(dataPath);
			if (dataDir.exists() == false) {
				if (dataDir.mkdirs() == false) {
					dataPath = System.getProperty("user.home");
				}
			}
			File contextDir = new File(dataDir, entry.getContext().getEntry().getId());
			if (contextDir.exists() == false) {
				contextDir.mkdir();
			}

			file = new File(contextDir, entry.getId());
		}
		return file;
	}

	public OutputStream getData() {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
		try {
			return new FileOutputStream(getFile());
		} catch (FileNotFoundException e) {
			log.error(e.getMessage());
			return null;
		}
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
		File f = getFile();
		if (f.exists()) {
			long size = f.length();
			boolean success = f.delete();
			if (success && entry.getRepositoryManager().hasQuotas()) {
				entry.getContext().decreaseQuotaFillLevel(size);
			}
			entry.getRepositoryManager().fireRepositoryEvent(new RepositoryEventObject(entry, RepositoryEvent.ResourceDeleted));
			return success;
		}
		return true; // file did not exist
	}

	public File getDataFile() {
		this.entry.getRepositoryManager().getPrincipalManager().checkAuthenticatedUserAuthorized(entry, AccessProperty.ReadResource);
		File f = getFile();
		if (f.exists()) {
			return f;
		}
		return null;
	}

}