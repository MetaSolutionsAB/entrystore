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

package se.kmr.scam.repository.impl.converters;

import org.ieee.ltsc.lom.LOM;

/**
 * Defines additional vocabularies and vocabulary elements for LRE, extending
 * the respective LOM interfaces.
 * 
 * @author Hannes Ebner
 */
public interface LRE {
	
	public static String LRE_V3P0_VOCABULARY = "LREv3.0";

	public interface MetaMetadata {

		public interface Contribute {

			public interface Role extends LOM.MetaMetadata.Contribute.Role {

				public static String ENRICHER = "enricher";
				
				public static String PROVIDER = "provider";

			}

		}

	}

	public interface Technical {

		public interface Facet {

			public interface Name {
				
				public static String PACKAGED_FORMAT = "packaged format";

				public static String SCORM_1_2 = "SCORM 1.2";
				
				public static String SCORM_2004 = "SCORM 2004";

			}

			public interface Value {

				public static String APPLICATION_ZIP = "application/zip";
				
				public static String ENHANCED = "enhanced";
				
				public static String REQUIRED = "required";

			}

		}

	}

	public interface Educational {

		// we don't inherit from LOM.Educational.LearningResourceType as this vocabulary is LRE specific
		public interface LearningResourceType {

			public static String APPLICATION = "application";

			public static String ASSESSMENT = "assessment";

			public static String BROADCAST = "broadcast";

			public static String CASE_STUDY = "case study";

			public static String COURSE = "course";

			public static String DEMONSTRATION = "demonstration";

			public static String DRILL_AND_PRACTICE = "drill and practice";

			public static String EDUCATIONAL_GAME = "educational game";

			public static String ENQUIRY_ORIENTED_ACTIVITY = "enquiry-oriented activity";

			public static String EXPERIMENT = "experiment";

			public static String EXPLORATION = "exploration";

			public static String GLOSSARY = "glossary";

			public static String GUIDE_ADVICE_SHEETS = "guide (advice sheets)";

			public static String AUDIO = "audio";

			public static String DATA = "data";

			public static String IMAGE = "image";

			public static String MODEL = "model";

			public static String TEXT = "text";

			public static String VIDEO = "video";

			public static String LESSON_PLAN = "lesson plan";

			public static String OPEN_ACTIVITY = "open activity";

			public static String PRESENTATION = "presentation";

			public static String PROJECT = "project";

			public static String REFERENCE = "reference";

			public static String ROLE_PLAY = "role play";

			public static String SIMULATION = "simulation";

			public static String TOOL = "tool";

			public static String WEBLOG = "weblog";

			public static String WEB_PAGE = "web page";

			public static String WIKI = "wiki";

			public static String OTHER_WEB_RESOURCE = "other web resource";

			public static String OTHER = "other";

		}

		public interface IntendedEndUserRole extends LOM.Educational.IntendedEndUserRole {

			public static String COUNSELLOR = "counsellor";

			public static String PARENT = "parent";

			public static String OTHER = "other";

		}

		// we don't inherit from LOM.Educational.Context as this vocabulary is LRE specific
		public interface LearningContext {

			public static String PRE_SCHOOL = "pre-school";

			public static String COMPULSORY_EDUCATION = "compulsory education";

			public static String SPECIAL_EDUCATION = "special education";

			public static String VOCATIONAL_EDUCATION = "vocational education";

			public static String HIGHER_EDUCATION = "higher education";

			public static String DISTANCE_EDUCATION = "distance education";

			public static String CONTINUING_EDUCATION = "continuing education";

			public static String PROFESSIONAL_DEVELOPMENT = "professional development";

			public static String LIBRARY = "library";

			public static String EDUCATIONAL_ADMINISTRATION = "educational administration";

			public static String POLICY_MAKING = "policy making";

			public static String OTHER = "other";

		}

	}

	public interface Relation {

		public interface Kind extends LOM.Relation.Kind {

			public static String HAS_PREVIEW = "haspreview";

			public static String IS_PREVIEW_OF = "ispreviewof";

		}

	}

}