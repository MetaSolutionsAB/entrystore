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

package org.entrystore.harvesting.fao;

/**
 * Holds FAO metadata, i.e. their resource subjects.
 * 
 * @author Hannes Ebner
 */
public class FAOSubject {

	enum Scheme {
		AGROVOC, AREA, INFOTYPE, MEDIA, TARGET;
	}

	private int id;

	private Scheme scheme;

	private String language;

	private String subject;

	private String name;

	public String toString() {
		StringBuffer buf = new StringBuffer();

		buf.append("[");
		buf.append("id=").append(id).append(", ");
		buf.append("scheme=").append(scheme).append(", ");
		buf.append("language=").append(language).append(", ");
		buf.append("subject=").append(subject).append(", ");
		buf.append("name=").append(name);
		buf.append("]");

		return buf.toString();
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
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

	public String getName() {
		return name;
	}

	public void setName(String name) {
		if (name != null) {
			this.name = name.trim();
		} else {
			this.name = name;
		}
	}

	public Scheme getScheme() {
		return scheme;
	}

	public void setScheme(Scheme scheme) {
		this.scheme = scheme;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		if (subject != null) {
			this.subject = subject.trim();
		} else {
			this.subject = subject;
		}
	}

}