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

package org.entrystore.repository.config;

import java.time.Duration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entrystore.config.Config;

import java.awt.*;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import org.entrystore.config.DurationStyle;

/**
 * Wrapper around Java's Properties.
 * Some methods have been simplified, others just wrapped.<br>
 *
 * <p>
 * See the static methods of the class Configurations for wrappers around the
 * Config interface, e.g. to get synchronized view of the object.
 *
 * <p>
 * If a key maps only to one value, it is done the standard way:<br>
 * <pre>key=value</pre>
 *
 * <p>
 * If a key maps to multiple values, the key is numbered:<br>
 * <pre>key.1=value1</pre>
 * <pre>key.2=value2</pre>
 *
 * @author Hannes Ebner
 * @version $Id$
 * @see org.entrystore.config.Configurations
 * @see org.entrystore.config.Config
 */
public class PropertiesConfiguration implements Config {

	Log log = LogFactory.getLog(PropertiesConfiguration.class);

	/**
	 * The main resource in this object. Contains the configuration.
	 */
	private SortedProperties config;

	private PropertyChangeSupport pcs;

	private String configName;

	private boolean modified = false;

	/* Constructors */

	/**
	 * Initializes the object with an empty Configuration.
	 *
	 * @param configName
	 *            Name of the configuration (appears as comment in the
	 *            configuration file).
	 */
	public PropertiesConfiguration(String configName) {
		this.configName = configName;
		config = new SortedProperties();
		pcs = new PropertyChangeSupport(this);
	}

	/* Generic helpers */

	/**
	 * Sets the modified status of this configuration.
	 *
	 * @param modified
	 *            Status.
	 */
	private void setModified(boolean modified) {
		this.modified = modified;
	}

	private void checkFirePropertyChange(String key, Object oldValue, Object newValue) {
		if ((oldValue == null) && (newValue != null)) {
			pcs.firePropertyChange(key, oldValue, newValue);
		} else if ((oldValue != null) && (!oldValue.equals(newValue))) {
			pcs.firePropertyChange(key, oldValue, newValue);
		}
	}

	/*
	 * List helpers
	 */

	private String numberedKey(String key, int number) {
		return key + "." + number;
	}

	private int getPropertyValueCount(String key) {
		int valueCount = 0;
		if (config.containsKey(key)) {
			valueCount = 1;
		} else {
			while (config.containsKey(numberedKey(key, valueCount + 1))) {
				valueCount++;
			}
		}
		return valueCount;
	}

	private synchronized void addPropertyValue(String key, Object value) {
		int valueCount = getPropertyValueCount(key);
		if ((valueCount == 1) && config.containsKey(key)) {
			String oldValue = config.getProperty(key);
			config.remove(key);
			config.setProperty(numberedKey(key, 1), oldValue);
			config.setProperty(numberedKey(key, 2), value.toString());
		} else if (valueCount > 1){
			config.setProperty(numberedKey(key, valueCount + 1), value.toString());
		} else if (valueCount == 0) {
			config.setProperty(key, value.toString());
		}
	}

	private void addPropertyValues(String key, List values) {
		addPropertyValues(key, values.iterator());
	}

	private synchronized void addPropertyValues(String key, Iterator it) {
		while (it.hasNext()) {
			addPropertyValue(key, it.next());
		}
	}

	private synchronized List<String> getPropertyValues(String key) {
		int valueCount = getPropertyValueCount(key);
		List<String> result = new ArrayList<>();
		if (valueCount == 1) {
			String value = config.getProperty(key);
			if (value == null) {
				value = config.getProperty(numberedKey(key, 1));
			}
			if (value != null) {
				result.add(value);
			}
		} else {
			for (int i = 1; i <= valueCount; i++) {
				result.add(config.getProperty(numberedKey(key, i)));
			}
		}
		return result;
	}

	private synchronized void clearPropertyValues(String key) {
		int valueCount = getPropertyValueCount(key);
		if (valueCount > 1) {
			for (int i = 1; i <= valueCount; i++) {
				config.remove(numberedKey(key, i));
			}
		}
		config.remove(key);
	}

	private void setPropertyValues(String key, List values) {
		setPropertyValues(key, values.iterator());
	}

	private synchronized void setPropertyValues(String key, Iterator it) {
		clearPropertyValues(key);
		addPropertyValues(key, it);
	}

	/*
	 * Interface implementation
	 */

	/* Generic */

	@Override
	public void clear() {
		config.clear();
		setModified(true);
	}

	@Override
	public boolean isEmpty() {
		return config.isEmpty();
	}

	@Override
	public boolean isModified() {
		return modified;
	}

	@Override
	public void load(URL configURL) throws IOException {
		InputStreamReader isr = null;
		try {
			URL escapedURL = new URL(configURL.toString().replaceAll(" ", "%20"));
			isr = new InputStreamReader(escapedURL.openStream(), StandardCharsets.UTF_8);
			config.load(isr);
		} finally {
			if (isr != null) {
				isr.close();
			}
		}
	}

	@Override
	public void save(URL configURL) throws IOException {
		try {
			String escapedURL = configURL.toString().replaceAll(" ", "%20");
			URI url = new URI(escapedURL.toString());
			File file = new File(url);
			OutputStreamWriter output = new OutputStreamWriter(Files.newOutputStream(file.toPath()), "UTF-8");
			config.store(output, configName);
			output.close();
		} catch (URISyntaxException e) {
			throw new IOException(e.getMessage());
		}
		setModified(false);
	}

	/* Property Change Listeners */

	@Override
	public void addPropertyChangeListener(PropertyChangeListener listener) {
		pcs.addPropertyChangeListener(listener);
	}

	@Override
	public void addPropertyChangeListener(String key, PropertyChangeListener listener) {
		pcs.addPropertyChangeListener(key, listener);
	}

	@Override
	public void removePropertyChangeListener(PropertyChangeListener listener) {
		pcs.removePropertyChangeListener(listener);
	}

	@Override
	public void removePropertyChangeListener(String key, PropertyChangeListener listener) {
		pcs.removePropertyChangeListener(key, listener);
	}

	/* Properties / Set Values */

	@Override
	public void clearProperty(String key) {
		int valueCount = getPropertyValueCount(key);
		Object oldValue = null;
		if (valueCount == 0) {
			return;
		} else if (valueCount == 1) {
			oldValue = getString(key);
		} else if (valueCount > 1) {
			oldValue = getStringList(key);
		}
		clearPropertyValues(key);
		setModified(true);
		checkFirePropertyChange(key, oldValue, null);
	}

	@Override
	public void addProperty(String key, Object value) {
		addPropertyValue(key, value);
		setModified(true);
		pcs.firePropertyChange(key, null, value);
	}

	@Override
	public void addProperties(String key, List values) {
		addPropertyValues(key, values);
		setModified(true);
		pcs.firePropertyChange(key, null, values);
	}

	@Override
	public void addProperties(String key, Iterator values) {
		addPropertyValues(key, values);
		setModified(true);
		pcs.firePropertyChange(key, null, values);
	}

	@Override
	public void setProperty(String key, Object value) {
		String oldValue = null;
		oldValue = getString(key);
		config.setProperty(key, value.toString());
		setModified(true);
		checkFirePropertyChange(key, oldValue, value);
	}

	@Override
	public void setProperties(String key, List values) {
		List<String> oldValues = getStringList(key);
		setPropertyValues(key, values);
		setModified(true);
		checkFirePropertyChange(key, oldValues, values);
	}

	@Override
	public void setProperties(String key, Iterator values) {
		List<String> oldValues = getStringList(key);
		setPropertyValues(key, values);
		setModified(true);
		checkFirePropertyChange(key, oldValues, values);
	}

	/* Keys */

	@Override
	public boolean containsKey(String key) {
		return config.containsKey(key);
	}

	@Override
	public List<String> getKeyList() {
		return getKeyList(null);
	}

	@Override
	public List<String> getKeyList(String prefix) {
		Enumeration keyIterator = config.propertyNames();
		ArrayList<String> result = new ArrayList<String>();

		while (keyIterator.hasMoreElements()) {
			String next = (String) keyIterator.nextElement();
			if ((prefix != null) && !next.startsWith(prefix)) {
				continue;
			}
			result.add(next);
		}
		return result;
	}

	/* Get Values */

	@Override
	public String getString(String key) {
		return config.getProperty(key);
	}

	@Override
	public String getString(String key, String defaultValue) {
		return config.getProperty(key, defaultValue);
	}

	@Override
	public List<String> getStringList(String key) {
		return getPropertyValues(key);
	}

	@Override
	public List<String> getStringList(String key, List<String> defaultValues) {
		List<String> result = getPropertyValues(key);
		if (result.isEmpty()) {
			result = defaultValues;
		}
		return result;
	}

	@Override
	public boolean getBoolean(String key) {
		return getBoolean(key, false);
	}

	@Override
	public boolean getBoolean(String key, boolean defaultValue) {
		String strValue = config.getProperty(key);
		if ("on".equalsIgnoreCase(strValue)) {
			return true;
		} else if ("off".equalsIgnoreCase(strValue)) {
			return false;
		}

		if (strValue != null) {
			return Boolean.parseBoolean(strValue);
		} else {
			return defaultValue;
		}
	}

	@Override
	public byte getByte(String key) {
		String strValue = config.getProperty(key);
		byte byteValue = 0;

		if (strValue != null) {
			byteValue = Byte.parseByte(strValue);
		}

		return byteValue;
	}

	@Override
	public byte getByte(String key, byte defaultValue) {
		String strValue = config.getProperty(key);
		byte byteValue = 0;

		if (strValue != null) {
			byteValue = Byte.parseByte(strValue);
		} else {
			byteValue = defaultValue;
		}

		return byteValue;
	}

	@Override
	public double getDouble(String key) {
		String strValue = config.getProperty(key);
		double doubleValue = 0;

		if (strValue != null) {
			doubleValue = Double.parseDouble(strValue);
		}

		return doubleValue;
	}

	@Override
	public double getDouble(String key, double defaultValue) {
		String strValue = config.getProperty(key);
		double doubleValue = 0;

		if (strValue != null) {
			doubleValue = Double.parseDouble(strValue);
		} else {
			doubleValue = defaultValue;
		}

		return doubleValue;
	}

	@Override
	public float getFloat(String key) {
		String strValue = config.getProperty(key);
		float floatValue = 0;

		if (strValue != null) {
			floatValue = Float.parseFloat(strValue);
		}

		return floatValue;
	}

	@Override
	public float getFloat(String key, float defaultValue) {
		String strValue = config.getProperty(key);
		float floatValue = 0;


		if (strValue != null) {
			floatValue = Float.parseFloat(strValue);
		} else {
			floatValue = defaultValue;
		}

		return floatValue;
	}

	@Override
	public int getInt(String key) {
		String strValue = config.getProperty(key);
		int intValue = 0;

		if (strValue != null) {
			intValue = Integer.parseInt(strValue);
		}

		return intValue;
	}

	@Override
	public int getInt(String key, int defaultValue) {
		String strValue = config.getProperty(key);
		int intValue = 0;

		if (strValue != null) {
			intValue = Integer.parseInt(strValue);
		} else {
			intValue = defaultValue;
		}

		return intValue;
	}

	@Override
	public long getLong(String key) {
		String strValue = config.getProperty(key);
		long longValue = 0;

		if (strValue != null) {
			longValue = Long.parseLong(strValue);
		}

		return longValue;
	}

	@Override
	public long getLong(String key, long defaultValue) {
		String strValue = config.getProperty(key);
		long longValue = 0;

		if (strValue != null) {
			longValue = Long.parseLong(strValue);
		} else {
			longValue = defaultValue;
		}

		return longValue;
	}

	@Override
	public short getShort(String key) {
		String strValue = config.getProperty(key);
		short shortValue = 0;

		if (strValue != null) {
			shortValue = Short.parseShort(strValue);
		}

		return shortValue;
	}

	@Override
	public short getShort(String key, short defaultValue) {
		String strValue = config.getProperty(key);
		short shortValue = 0;

		if (strValue != null) {
			shortValue = Short.parseShort(strValue);
		} else {
			shortValue = defaultValue;
		}

		return shortValue;
	}

	@Override
	public URI getURI(String key) {
		try {
			String uri = config.getProperty(key);
			if (uri != null) {
				return new URI(uri);
			}
		} catch (URISyntaxException ignored) {
		}
		return null;
	}

	@Override
	public URI getURI(String key, URI defaultValue) {
		URI result = getURI(key);
		if (result == null) {
			return defaultValue;
		}
		return result;
	}

	@Override
	public URL getURL(String key) {
		try {
			String uri = config.getProperty(key);
			if (uri != null) {
				return new URL(uri);
			}
		} catch (MalformedURLException ignored) {
		}
		return null;
	}

    @Override
		public URL getURL(String key, URL defaultValue) {
		URL result = getURL(key);
		if (result == null) {
			return defaultValue;
		}
		return result;
    }

    @Override
		public Color getColor(String key) {
		Color result = null;
		String value = getString(key);

		if (value != null) {
			try {
				if (!value.startsWith("0x"))
					result = Color.decode(value);
				else {
					int rgb = Long.decode(value).intValue();
					result = new Color(rgb);
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return result;
	}

	@Override
	public Color getColor(String key, Color defaultValue) {
		Color result = getColor(key);
		if (result == null) {
			return defaultValue;
		}
		return result;
	}

	@Override
	public Duration getDuration(String key) {
		String durationString = config.getProperty(key);
		if (durationString == null) {
			return null;
		}
		return DurationStyle.detectAndParse(durationString);
	}

	@Override
	public Duration getDuration(String key, Duration defaultValue) {
		String durationString = config.getProperty(key);
		if (durationString == null) {
			return defaultValue;
		}
		return DurationStyle.detectAndParse(durationString);
	}

	@Override
	public Duration getDuration(String key, String defaultValue) {
		String durationString = config.getProperty(key);
		if (durationString == null) {
			return DurationStyle.detectAndParse(defaultValue);
		}
		return DurationStyle.detectAndParse(durationString);
	}

	@Override
	public Duration getDuration(String key, long defaultValue) {
		String durationString = config.getProperty(key);
		if (durationString == null) {
			return Duration.ofMillis(defaultValue);
		}
		Duration duration = DurationStyle.detectAndParse(durationString);
		return duration;
	}
}
