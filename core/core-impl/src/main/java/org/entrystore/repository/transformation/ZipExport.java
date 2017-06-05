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

package org.entrystore.repository.transformation;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Context;
import org.entrystore.Data;
import org.entrystore.Entry;
import org.entrystore.List;
import org.entrystore.ResourceType;
import org.entrystore.repository.util.FileOperations;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;


public class ZipExport {
	public static final URI DCtitle;
	public static final URI DCTermstitle;

	public static final String DCbase = "http://purl.org/dc/elements/1.1/";
	public static final String DCTermsbase = "http://purl.org/dc/terms/";


	static {
		ValueFactory vf = ValueFactoryImpl.getInstance();
		DCtitle = vf.createURI(DCbase + "title");
		DCTermstitle = vf.createURI(DCTermsbase + "title");
	}

	private Context context;
	private Set<Entry> visited = new HashSet<Entry>();
	private File dir;

	public ZipExport(Context contextToExport, String exportPath) {
		this.context = contextToExport;
		if (exportPath != null) {
			this.dir = new File(exportPath);
			this.dir.mkdirs();
		}
		if (this.dir == null || !this.dir.exists()) {
			try {
				this.dir = FileOperations.createTempDirectory("toZip", null);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public void export() {
		Entry top = context.get("_top");
		visited.add(top);
		recurse(dir, top);
	}
	protected void recurse(File parentFile, Entry folderEntry) {
		File folderFile = new File(parentFile, getLabel(folderEntry));
		folderFile.mkdirs();
		List folderList = (List) folderEntry.getResource();
		for (java.net.URI child : folderList.getChildren()) {
			Entry childEntry = context.getByEntryURI(child);
			if (childEntry != null) {
				if (visited.contains(childEntry)) {
					return;
				}
				visited.add(childEntry);
				if (isFile(childEntry)) {
					File childFile = new File(folderFile, getLabel(childEntry));
					Data data = (Data) childEntry.getResource(); 
					try {
						FileOperations.copyFile(data.getDataFile(), childFile);
					} catch (IOException e) {
						e.printStackTrace();
					}
				} else if (isLocalDir(childEntry)) {
					recurse(folderFile, childEntry);
				} else {
					System.out.println("HELP!!!");
					//TODO
				}
			}
		}		
	}
	
	protected boolean isFile(Entry entry) {
		return entry.getEntryType() == EntryType.Local
				&& entry.getGraphType() == GraphType.None
				&& entry.getResourceType() == ResourceType.InformationResource;
	}

	protected boolean isLocalDir(Entry entry) {
		return entry.getEntryType() == EntryType.Local
				&& entry.getGraphType() == GraphType.List;
	}

	protected String getLabel(Entry entry) {
		String label = getLabelUnfiltered(entry);
		return label.replace('/', '-');
	}

	protected String getLabelUnfiltered(Entry entry) {
		if (isFile(entry)) {
			String filename = entry.getFilename();
			if (filename != null) {
				return filename;
			}
		}
		Iterator<Statement> it = entry.getLocalMetadata().getGraph().match(null, DCtitle, null);
		if (it.hasNext()) {
			return it.next().getObject().stringValue();
		}
		it = entry.getLocalMetadata().getGraph().match(null, DCTermstitle, null);
		if (it.hasNext()) {
			return it.next().getObject().stringValue();
		}
		
		return entry.getId();
	}
}
