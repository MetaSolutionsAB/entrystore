/*
 * Copyright (c) 2007-2014
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

package org.entrystore.transforms.pcaxis;

import com.mysema.commons.lang.Assert;
import com.mysema.converters.DateTimeConverter;
import com.mysema.rdfbean.model.DC;
import com.mysema.rdfbean.model.DCTERMS;
import com.mysema.rdfbean.model.ID;
import com.mysema.rdfbean.model.LIT;
import com.mysema.rdfbean.model.NODE;
import com.mysema.rdfbean.model.RDF;
import com.mysema.rdfbean.model.RDFS;
import com.mysema.rdfbean.model.UID;
import com.mysema.rdfbean.model.XSD;
import com.mysema.rdfbean.owl.OWL;
import com.mysema.stat.STAT;
import com.mysema.stat.pcaxis.Dataset;
import com.mysema.stat.pcaxis.DatasetHandler;
import com.mysema.stat.pcaxis.Dimension;
import com.mysema.stat.pcaxis.DimensionType;
import com.mysema.stat.pcaxis.Item;
import com.mysema.stat.scovo.SCV;
import com.mysema.stat.scovo.XMLID;
import org.apache.commons.codec.binary.Hex;
import org.joda.time.DateTime;
import org.openrdf.model.Model;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by hannes on 1/7/14.
 */
public class ScovoHandler implements DatasetHandler {

	public static final String DIMENSIONS = "dimensions";

	public static final String ITEMS_NS = "items/";

	public static final String DIMENSION_NS = DIMENSIONS + "/";

	private static final String DATASETS = "datasets";

	public static final String DATASET_CONTEXT_BASE = DATASETS + "#";

	private static final String UNITS_LOCAL_NAME = "Yksikk\u00F6";

	private static final Logger logger = LoggerFactory.getLogger(ScovoHandler.class);

	private static final int TX_TIMEOUT = -1;

	private static final int TX_ISOLATION = Connection.TRANSACTION_READ_COMMITTED;

	private static final Pattern AREA_NAME_PATTERN = Pattern.compile("[\\d\\s]+(.*)");

	private static final DateTimeConverter DATE_TIME_CONVERTER = new DateTimeConverter();

	private static final Map<String, LIT> DECIMAL_CACHE = new HashMap<String, LIT>();

	private final String baseURI;

	private Set<Statement> statements;

	private final Model model;

	private Map<Dimension, UID> dimensions;

	private List<UID> datasets;

	private static final int batchSize = 2000;

	private int itemCount = 0;

	private int skippedCount = 0;

	private Set<String> ignoredValues = Collections.singleton("\".\"");

	private boolean exportContexts;

	static {
		for (int i = 0; i <= 1000; i++) {
			String str = Integer.toString(i);
			DECIMAL_CACHE.put(str, new LIT(str, XSD.decimalType));
		}
	}

	public ScovoHandler(Model model, String baseURI, boolean exportContexts) {
		this.model = model;
		this.baseURI = baseURI;
		this.exportContexts = exportContexts;
		Assert.notNull(baseURI, "baseURI");
		Assert.assertThat(baseURI.endsWith("/"), "baseURI doesn't end with /", null, null);
	}

	private void add(ID subject, UID predicate, DateTime dateTime, UID context) {
		add(subject, predicate, new LIT(DATE_TIME_CONVERTER.toString(dateTime), XSD.dateTime), context);
	}

	private void add(ID subject, UID predicate, String name, UID context) {
		add(subject, predicate, new LIT(name), context);
	}

	public static UID datasetUID(String baseURI, String datasetName) {
		return new UID(baseURI + DATASET_CONTEXT_BASE, ScovoHandler.encodeID(datasetName));
	}

	public static String encodeID(String name) {
		return XMLID.toXMLID(name);
	}

	private void add(ID subject, UID predicate, NODE object, UID context) {
		ValueFactory vf = new ValueFactoryImpl();

		Resource s = null;
		if (subject.isURI()) {
			s = vf.createURI(subject.getValue());
		} else if (subject.isBNode()) {
			s = vf.createBNode(subject.getValue());
		}

		URI p = vf.createURI(predicate.getValue());

		Value o = null;
		if (object.isURI()) {
			o = vf.createURI(object.getValue());
		} else if (object.isBNode()) {
			o = vf.createBNode(object.getValue());
		} else if (object.isLiteral()) {
			LIT objectLiteral = object.asLiteral();
			if (objectLiteral.getDatatype() != null) {
				o = vf.createLiteral(objectLiteral.getValue(), vf.createURI(objectLiteral.getDatatype().getValue()));
			}
			if (objectLiteral.getLang() != null) {
				o = vf.createLiteral(objectLiteral.getValue(), objectLiteral.getLang().toString());
			}
		}

		Resource c = null;
		if (exportContexts && context != null) {
			c = vf.createURI(context.getValue());
		}

		if (c != null) {
			statements.add(vf.createStatement(s, p , o, c));
		} else {
			statements.add(vf.createStatement(s, p , o));
		}
	}

	public static UID datasetsContext(String baseURI) {
		return new UID(baseURI, DATASETS);
	}

	@Override
	public void addDataset(Dataset dataset) {
		UID datasetsContext = datasetsContext(baseURI);
		UID datasetUID = datasetUID(baseURI, dataset.getName());

		datasets.add(datasetUID);
		add(datasetUID, RDF.type, SCV.Dataset, datasetsContext);
		if (dataset.getTitle() != null) {
			add(datasetUID, DC.title, dataset.getTitle(), datasetsContext);
		}
		if (dataset.getDescription() != null) {
			add(datasetUID, DC.description, dataset.getDescription(), datasetsContext);
		}
		if (dataset.getPublisher() != null) {
			add(datasetUID, DC.publisher, dataset.getPublisher(), datasetsContext);
		}

		add(datasetUID, DCTERMS.created, new DateTime(), datasetsContext);

		UID domainContext = new UID(baseURI, DIMENSIONS);
		String dimensionBase = baseURI + DIMENSION_NS;
		UID unitDimension = new UID(dimensionBase, encodeID(UNITS_LOCAL_NAME));
		boolean unitFound = false;

		Map<String, String> namespaces = new HashMap<String, String>();
		// SCHEMA: DimensionTypes
		for (DimensionType type : dataset.getDimensionTypes()) {
			UID dimensionUID = new UID(dimensionBase, encodeID(type.getName()));
			unitFound = unitFound || dimensionUID.equals(unitDimension);

			addDimensionType(type, datasetsContext, datasetUID, domainContext,
					dimensionUID, namespaces);
		}

		// Units
		if (!unitFound) {
			// Create dynamic dimension type and value from Dataset's UNITS property
			if (dataset.getUnits() != null) {
				String units = dataset.getUnits();
				DimensionType type = new DimensionType(UNITS_LOCAL_NAME);
				type.addDimension(units.substring(0, 1).toUpperCase(Locale.ENGLISH) + units.substring(1)); // henkilö -> Henkilö

				dataset.addDimensionType(type);

				addDimensionType(type, datasetsContext, datasetUID, domainContext, unitDimension, namespaces);
			} else {
				logger.warn("Dataset " + dataset.getName() + " has no unit!");
			}
		}

		flush();
	}

	private void addDimensionType(DimensionType type, UID datasetsContext,
								  UID datasetUID, UID domainContext, UID dimensionUID,
								  Map<String, String> namespaces) {
		String dimensionNs = dimensionUID.getId() + "#";

		add(dimensionUID, RDF.type, RDFS.Class, domainContext);
		add(dimensionUID, RDF.type, OWL.Class, domainContext);
		add(dimensionUID, RDFS.subClassOf, SCV.Dimension, domainContext);
		add(dimensionUID, DC.title, type.getName(), domainContext);

		// Namespace for dimension instances
		namespaces.put(dimensionNs, dimensionUID.getLocalName().toLowerCase(Locale.ENGLISH));

		// INSTANCES: Dimensions
		for (Dimension dimension : type.getDimensions()) {
			UID d = new UID(dimensionNs, encodeID(dimension.getName()));
			dimensions.put(dimension, d);

			add(d, RDF.type, dimensionUID, dimensionUID);
			add(d, DC.identifier, dimension.getName(), dimensionUID);

			if ("Alue".equals(type.getName())) {
				add(d, DC.title, getAreaName(dimension.getName()), dimensionUID);
			} else {
				add(d, DC.title, dimension.getName(), dimensionUID);
			}

			add(datasetUID, STAT.datasetDimension, d, datasetsContext);

			// TODO: hierarchy?
			// TODO: subProperty of scv:dimension?
		}
	}

	private String getAreaName(String name) {
		Matcher m = AREA_NAME_PATTERN.matcher(name);
		if (m.find()) {
			return m.group(1);
		} else {
			return name;
		}
	}

	private void flush() {
		model.addAll(statements);
		statements.clear();
	}

	@Override
	public void addItem(Item item) {
		if (ignoredValues.contains(item.getValue())) {
			if (++skippedCount % 1000 == 0) {
				logger.info(item.getDataset().getName() + ": skipped " + skippedCount + " items");
			}
		} else {
			try {
				Dataset dataset = item.getDataset();
				UID datasetContext = datasetUID(baseURI, dataset.getName());

				MessageDigest md = MessageDigest.getInstance("SHA-1");
				List<NODE[]> properties = new ArrayList<NODE[]>();

				// PROPERTIES from which an ID for the Item is derived
				addProperty(RDF.type, SCV.Item, properties, md);

				String value = item.getValue();
				if (value.startsWith("\"")) {
					addProperty(RDF.value, value.substring(1, value.length() - 1), properties, md);
				} else {
					addDecimal(RDF.value, value, properties, md);
				}
				addProperty(SCV.dataset, datasetContext, properties, md);

				for (Dimension dimension : item.getDimensions()) {
					addProperty(SCV.dimension, dimensions.get(dimension), properties, md);
				}
				// ADD TRIPLES
				UID id = new UID(baseURI + ITEMS_NS + encodeID(dataset.getName()) + "/", encodeID(new String(Hex.encodeHex(md.digest()))));
				for (NODE[] property : properties) {
					add(id, (UID) property[0], property[1], datasetContext);
				}

				if (++itemCount % batchSize == 0) {
					flush();
					logger.info(dataset.getName() + ": loaded " + itemCount + " items");
				}
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			} catch (UnsupportedEncodingException e) {
				throw new IllegalStateException(e);
			}

		}
	}

	private void addDecimal(UID predicate, String object, List<NODE[]> properties, MessageDigest md) throws UnsupportedEncodingException {
		LIT value = DECIMAL_CACHE.get(object);
		if (value == null) {
			value = new LIT(object, XSD.decimalType);
		}
		addProperty(predicate, value, properties, md);
	}

	private void addProperty(UID predicate, String object, List<NODE[]> properties, MessageDigest md) throws UnsupportedEncodingException {
		addProperty(predicate, new LIT(object), properties, md);
	}

	private void addProperty(UID predicate, NODE object, List<NODE[]> properties, MessageDigest md) throws UnsupportedEncodingException {
		properties.add(new NODE[]{predicate, object});
		md.update(predicate.getId().getBytes("UTF-8"));
		md.update(object.toString().getBytes("UTF-8"));
	}

	public void setIgnoredValues(String... values) {
		this.ignoredValues = new HashSet<String>(Arrays.asList(values));
	}

	@Override
	public void begin() {
		statements = new LinkedHashSet<Statement>(batchSize * 10);
		dimensions = new HashMap<Dimension, UID>();
		datasets = new ArrayList<UID>();
	}

	@Override
	public void rollback() {
	}

	@Override
	public void commit() {
		DateTime now = new DateTime();
		UID datasetsContext = datasetsContext(baseURI);
		for (UID dataset : datasets) {
			add(dataset, DCTERMS.modified, now, datasetsContext);
		}
		flush();
	}

}