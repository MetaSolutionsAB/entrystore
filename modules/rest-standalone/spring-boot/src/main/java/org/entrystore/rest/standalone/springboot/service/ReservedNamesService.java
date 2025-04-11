package org.entrystore.rest.standalone.springboot.service;

import org.springframework.stereotype.Service;

import java.util.HashSet;

@Service
public class ReservedNamesService extends HashSet<String> {

	public ReservedNamesService() {
		add("favicon.ico");
		add("echo");
		add("lookup");
		add("proxy");
		add("search");
		add("sparql");
		add("validator");
		add("message");
		add("auth");
		add("management");
	}
}
