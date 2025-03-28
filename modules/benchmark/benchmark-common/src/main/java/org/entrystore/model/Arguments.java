package org.entrystore.model;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Getter
@Setter
public class Arguments {
	String storeType;
	String baseUrl;
	int sizeToGenerate = 0;
	boolean isComplex = false;
	boolean withTransactions = false;
	boolean withInterRequests = false;
	int interRequestsModulo = -1;
	boolean withInterContexts = false;
	boolean withAcl = false;
	File storePath;
	File solrPath;

	public void setStorePath() throws IOException {
		Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), "benchmark-store-" + UUID.randomUUID());
		this.storePath = Files.createDirectories(path).toFile();
	}

	public void setStorePath(String storePath) {
		this.storePath = new File(storePath);
	}

	public void setSolrPath() throws IOException {
		Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), "benchmark-solr-" + UUID.randomUUID());
		this.solrPath = Files.createDirectories(path).toFile();
	}
}
