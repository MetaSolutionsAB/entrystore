package se.kmr.scam.repository.util;

public class StringUtils {

	public static String replace(String data, String from, String to) {
		StringBuffer buf = new StringBuffer(data.length());
		int pos = -1;
		int i = 0;
		while ((pos = data.indexOf(from, i)) != -1) {
			buf.append(data.substring(i, pos)).append(to);
			i = pos + from.length();
		}
		buf.append(data.substring(i));
		return buf.toString();
	}

}