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

package se.kmr.scam.harvesting.fao;

import java.util.List;

/**
 * Holds FAO metadata.
 * 
 * Trimming is used because some fields have trailing spaces (sometimes), which
 * could have bad effects e.g. for URI handling.
 * 
 * @author Hannes Ebner
 */
public class FAOMetadata {
	
	private String metadataURL;
	
	private String source;
	
	private int id = -1;
	
	private String title;
	
	private String description;
	
	private String language;
	
	private String creator;
	
	private String year;
	
	private int pages = -1;
	
	private String uri;
	
	private String pdfURI;
	
	private String jobNr;
	
	private List<FAOSubject> subjects;
	
	public String toString() {
		StringBuffer buf = new StringBuffer();
		
		buf.append("[");
		buf.append("id=").append(id).append(", ");
		buf.append("title=").append(title).append(", ");
		buf.append("description=").append(description).append(", ");
		buf.append("language=").append(language).append(", ");
		buf.append("creator=").append(creator).append(", ");
		buf.append("year=").append(year).append(", ");
		buf.append("pages=").append(pages).append(", ");
		buf.append("uri=").append(uri).append(", ");
		buf.append("pdfURI=").append(pdfURI).append(", ");
		buf.append("jobNr=").append(jobNr).append(", ");
		buf.append("subjects=").append(subjects).append(", ");
		buf.append("source=").append(source);
		buf.append("]");
		
		return buf.toString();
	}

	public String getCreator() {
		return creator;
	}

	public void setCreator(String creator) {
		if (creator != null) {
			this.creator = creator.trim();
		} else {
			this.creator = creator;
		}
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		if (description != null) {
			this.description = description.trim();
		} else {
			this.description = description;
		}
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getJobNr() {
		return jobNr;
	}

	public void setJobNr(String jobNr) {
		if (jobNr != null) {
			this.jobNr = jobNr.trim();
		} else {
			this.jobNr = jobNr;
		}
	}

	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		if (language != null) {
			this.language = language.trim();
		} else {
			this.language = language;
		}
	}

	public String getMetadataURL() {
		return metadataURL;
	}

	public void setMetadataURL(String metadataURL) {
		if (metadataURL != null) {
			this.metadataURL = metadataURL.trim();
		} else {
			this.metadataURL = metadataURL;
		}
	}

	public int getPages() {
		return pages;
	}

	public void setPages(int pages) {
		this.pages = pages;
	}

	public String getPdfURI() {
		return pdfURI;
	}

	public void setPdfURI(String pdfURI) {
		if (pdfURI != null) {
			this.pdfURI = pdfURI.trim();
		} else {
			this.pdfURI = pdfURI;
		}
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		if (source != null) {
			this.source = source.trim();
		} else {
			this.source = source;
		}
	}

	public List<FAOSubject> getSubjects() {
		return subjects;
	}

	public void setSubjects(List<FAOSubject> subjects) {
		this.subjects = subjects;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		if (title != null) {
			this.title = title.trim();
		} else {
			this.title = title;
		}
	}

	public String getUri() {
		return uri;
	}

	public void setUri(String uri) {
		if (uri != null) {
			this.uri = uri.trim();
		} else {
			this.uri = uri;
		}
	}

	public String getYear() {
		return year;
	}

	public void setYear(String year) {
		if (year != null) {
			this.year = year.trim();	
		} else {
			this.year = year;
		}
	}

}